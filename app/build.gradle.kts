plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Issue #417 — :app needs kotlinx-serialization to JSON-encode the
    // user's starred radio stations in RadioConfigImpl.
    alias(libs.plugins.kotlin.serialization)
    // Issue #409 — consumer side of the Baseline Profile plugin pair.
    // Pulls the generated `baseline-prof.txt` from :baselineprofile into
    // `app/src/main/baseline-prof.txt` so it's packaged into the APK.
    // The producer (`:baselineprofile`) emits the profile via
    // `BaselineProfileRule`; the plugin wires
    // `:app:generateBaselineProfile` → that producer task and copies
    // the result into :app's main sourceSet. ProfileInstaller (added
    // below as a runtime dep) compiles the profile at install time.
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Realm-sigil git provenance — captured at configure time so BuildConfig
 * can carry hash/branch/dirty/built fields. Uses [providers.exec] which is
 * configuration-cache-compatible, unlike a raw [ProcessBuilder]. Failures
 * fall back to "dev" so non-git checkouts (source tarballs) still build.
 */
fun gitOutput(vararg args: String): String = runCatching {
    val output = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
    }
    output.standardOutput.asText.get().trim()
}.getOrDefault("dev")

val gitHash: String = gitOutput("rev-parse", "--short=8", "HEAD").ifBlank { "dev" }
val gitBranch: String = gitOutput("rev-parse", "--abbrev-ref", "HEAD").ifBlank { "unknown" }
val gitDirty: Boolean = gitOutput("status", "--porcelain").isNotEmpty()
val buildTime: String = gitOutput("log", "-1", "--format=%cI", "HEAD").ifBlank { "dev" }

android {
    namespace = "in.jphe.storyvox"
    compileSdk = 35

    defaultConfig {
        applicationId = "in.jphe.storyvox"
        minSdk = 26
        targetSdk = 35
        versionCode = 156
        versionName = "0.5.45"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Realm-sigil fields — see app/sigil/Sigil.kt for the name generator.
        // Realm "fantasy" matches Library Nocturne aesthetic and RR's primary genre.
        buildConfigField("String", "SIGIL_REALM", "\"fantasy\"")
        buildConfigField("String", "SIGIL_HASH", "\"$gitHash\"")
        buildConfigField("String", "SIGIL_BRANCH", "\"$gitBranch\"")
        buildConfigField("boolean", "SIGIL_DIRTY", "$gitDirty")
        buildConfigField("String", "SIGIL_BUILT", "\"$buildTime\"")
        buildConfigField(
            "String",
            "SIGIL_REPO",
            "\"https://github.com/techempower-org/storyvox\"",
        )
    }

    signingConfigs {
        // Repo-checked-in keystore so every environment (CI runners, each
        // contributor's machine) signs the APK with the SAME certificate.
        // Without this each cold environment generates a fresh keystore →
        // "App not installed: package conflicts" when a CI APK lands over
        // a locally-built one (or v0.4.13 over v0.4.12 if the runner cache
        // rotated).
        //
        // SECURITY TRADE-OFF (acknowledged): committing the private key
        // means anyone can sign an APK with the same applicationId and
        // Android will accept it as an upgrade. For storyvox today this
        // is an acceptable trade-off — distribution is sideload-only via
        // GitHub Releases, the audience is the developer, and the APKs
        // aren't going through any signed-update channel that this would
        // compromise. Before storyvox is distributed to users at scale
        // (Play Store, F-Droid, anything that relies on signature
        // continuity for security guarantees) the release flavor will
        // need its own keystore stored in CI secrets — see issue tracker.
        getByName("debug") {
            storeFile = file("storyvox-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // R8 minification + tree-shaking (#409 part 3). Storyvox
            // ships a single sideload APK signed with the checked-in
            // debug cert (see signingConfigs comment above) — there is
            // no separate release flavor today, so the "debug" build
            // type IS the shipped artifact. Flipping isMinifyEnabled
            // here is what actually delivers the R8 win to users.
            //
            // Resource shrinking stays OFF on debug because
            // androidTest / instrumentation runs reference resources by
            // name and the shrinker would strip them. The release
            // build (which nothing currently consumes) has it on.
            //
            // Comprehensive `app/proguard-rules.pro` carries keep rules
            // for every reflection surface in the app: KSP-generated
            // SourcePluginModule factories, kotlinx-serialization
            // synthesised serializers, Hilt entry points, Room
            // entities/DAOs, Jsoup, OkHttp. See that file's header for
            // the per-rule rationale.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No applicationIdSuffix / versionNameSuffix: the build is
            // marketed and tested as the real app. The label "debug"
            // here is just AGP-internal terminology for "debuggable,
            // signed with the dev cert" — there's no separate release
            // flavor being shipped, and forcing ".debug" / "-debug"
            // into the package id and version string was just visual
            // noise on a sideload-only app. (Pre-#409 part 3 this
            // build was also non-minified; that's no longer true.)
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Release build type isn't shipped today (sideload via the
            // debug build) — kept as a forward-looking placeholder for
            // when storyvox graduates to Play Store / F-Droid (issue
            // #16 / v1.0 prerequisite). R8 stays on; the BaselineProfile
            // producer overrides per-variant (its `nonMinifiedRelease`
            // variant disables R8 specifically so the generator sees
            // un-AOT'd code paths during profile capture).
            // The single-keystore signingConfig reuse mirrors the debug
            // block — without it, AGP refuses to assemble the release
            // variant for the BaselineProfile producer (`./gradlew
            // :baselineprofile:assembleNonMinifiedRelease` fails at
            // signing time).
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Issue #409 — Macrobenchmark target build type. Non-debuggable
        // (so ART honors the installed Baseline Profile), no R8 (JP's
        // design call queued separately), debug-signed (single
        // keystore). Mirrors release; the BaselineProfile plugin uses
        // this as the producer side's target variant. The output APK
        // is NOT what we ship — sideload distribution still rides the
        // `debug` build type. This variant exists so:
        //   1. The BaselineProfileGenerator can install a
        //      non-debuggable target and measure AOT-compiled cold
        //      launch (ART skips profile compilation for debuggable
        //      builds, which would invalidate the with-profile number).
        //   2. The StartupBenchmark gets honest "with profile" /
        //      "without profile" comparisons.
        // The generated `baseline-prof.txt` still gets copied into
        // :app/src/main/ so it's bundled into the debug APK as well,
        // ready to apply when/if storyvox switches its shipped build
        // type to a non-debuggable one.
        create("benchmark") {
            initWith(getByName("release"))
            // signingConfig already inherited from release.initWith.
            // `debuggable = false` already set by initWith too. Adding
            // matchingFallbacks so libraries with only debug/release
            // variants resolve cleanly when consumed from benchmark.
            matchingFallbacks += listOf("release")
            // Profile-enabled but explicitly NOT minified — the JP
            // R8 design call is a separate batch.
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            // Skip the BaselineProfile auto-wiring for this build type
            // itself — we don't want a self-referential dependency.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources visible to the test classpath
            // so it can spin up a stub application context. Required for
            // StoryvoxRoutesTest, which calls into android.net.Uri.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    implementation(project(":core-llm"))
    implementation(project(":core-playback"))
    implementation(project(":core-ui"))
    implementation(project(":source-royalroad"))
    implementation(project(":source-github"))
    implementation(project(":source-mempalace"))
    implementation(project(":source-rss"))
    implementation(project(":source-epub"))
    implementation(project(":source-epub-writer"))
    implementation(project(":source-outline"))
    implementation(project(":source-gutenberg"))
    implementation(project(":source-ao3"))
    implementation(project(":source-standard-ebooks"))
    implementation(project(":source-wikipedia"))
    implementation(project(":source-wikisource"))
    // Issue #417 — :source-radio replaces :source-kvmr. Same Gradle
    // module, generalized: curated stations (KVMR, KQED, KCSB,
    // KXPR, SomaFM Groove Salad) + Radio Browser API search.
    implementation(project(":source-radio"))
    // Issue #417 — JSON serialization for RadioConfigImpl's starred-
    // stations DataStore payload.
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":source-notion"))
    implementation(project(":source-hackernews"))
    implementation(project(":source-arxiv"))
    implementation(project(":source-plos"))
    implementation(project(":source-discord"))
    implementation(project(":source-telegram"))
    // Issue #472 — magic-link Readability catch-all. Must be on the
    // app classpath so its KSP-generated SourcePluginDescriptor binding
    // joins the Hilt multibinding set the registry consumes.
    implementation(project(":source-readability"))
    implementation(project(":source-azure"))
    implementation(project(":core-sync"))
    implementation(project(":feature"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.startup)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coroutines)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Chrome Custom Tabs for the Anthropic Teams OAuth handoff (#181)
    implementation(libs.androidx.browser)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // DataStore (settings persistence)
    implementation(libs.androidx.datastore.preferences)

    // SAF helper for the EPUB import folder picker (#235)
    implementation(libs.androidx.documentfile)

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    // OkHttp for the suggested-feeds registry fetch (#246).
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // OkHttp on the test classpath only — SettingsRepositoryBufferTest
    // constructs a real PalaceDaemonApi (with no HTTP traffic) so the
    // repo signature is satisfied. Production transitively pulls okhttp
    // via :source-mempalace; tests need an explicit testImplementation
    // because the source module is `implementation` (not `api`) which
    // doesn't expose its dep classpath to consumers' tests.
    testImplementation(libs.okhttp)
    // Robolectric supplies a real android.net.Uri implementation under JVM
    // unit tests so StoryvoxRoutesTest can verify the v0.4.25 encoding fix
    // (Uri.encode in the route-builder) — the framework stub jar throws
    // "Method not mocked" on every Uri.* call and isReturnDefaultValues
    // would just hand us null, which won't catch a regression. This is
    // the only Robolectric-using test in the repo today; if more land
    // they should reuse this dep, not add their own.
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Issue #409 — ProfileInstaller compiles the bundled
    // `baseline-prof.txt` at first-run on devices that don't go through
    // a Play Store install (sideload APKs from GitHub Releases). Without
    // this dep the profile is dead code: it ships inside the APK but
    // never reaches the ART compiler. ProfileInstaller wakes up on
    // first app launch, queues the AOT compile on a background thread,
    // and the next cold-launch benefits.
    implementation(libs.androidx.profileinstaller)

    // Tells the AndroidX Baseline Profile Gradle plugin which producer
    // module owns the generator task. Pair-matches the
    // `targetProjectPath = ":app"` declaration in :baselineprofile.
    "baselineProfile"(project(":baselineprofile"))
}

/**
 * Issue #409 — Baseline Profile consumer-side config.
 *
 * `mergeIntoMain = true` makes the plugin write the generated profile
 * to `app/src/main/generated/baselineProfiles/baseline-prof.txt`
 * rather than the default per-variant path
 * (`app/src/release/generated/baselineProfiles/`). Because storyvox
 * ships its `debug` build type (sideload-only, see signingConfigs
 * comment above), a release-only profile wouldn't actually reach
 * users. Merging into `main` makes the profile available to every
 * variant — debug, release, benchmark.
 *
 * `saveInSrc = true` means the profile is checked into git (default
 * behavior in 1.4.x). Without this the profile would land in the
 * build directory only, forcing a regenerate on every clean CI run.
 * We want it committed so the debug APK has it from the start.
 *
 * `automaticGenerationDuringBuild = false` keeps the BaselineProfile
 * generation OFF the critical-path build. CI's `:app:assembleDebug`
 * path needs to stay fast (~2 min); profile regeneration is an
 * instrumented test (~6 min on Tab A7 Lite) so we run it manually on
 * tag pushes or when the nav graph changes.
 *
 * NOTE: ProfileInstaller will only AOT-compile the bundled profile on
 * NON-debuggable APKs. The current `debug` build type IS debuggable,
 * so the profile bundles but doesn't activate at run time for the
 * shipped APK as of v0.5.43. The win this PR captures lands when:
 *   (a) storyvox switches the shipped build type to non-debuggable
 *       (the new `benchmark` variant is ready), OR
 *   (b) JP's separate R8 design call resolves and a release flavor
 *       starts shipping.
 * The infrastructure is fully wired ahead of either of those.
 */
baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
    automaticGenerationDuringBuild = false
}
