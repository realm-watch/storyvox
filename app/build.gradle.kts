plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
        versionCode = 139
        versionName = "0.5.28"

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
            isMinifyEnabled = false
            // No applicationIdSuffix / versionNameSuffix: the build is
            // marketed and tested as the real app. The label "debug"
            // here is just AGP-internal terminology for "debuggable,
            // non-minified, signed with the dev cert" — there's no
            // separate release flavor being shipped, and forcing
            // ".debug" / "-debug" into the package id and version
            // string was just visual noise on a sideload-only app.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":source-kvmr"))
    implementation(project(":source-notion"))
    implementation(project(":source-hackernews"))
    implementation(project(":source-arxiv"))
    implementation(project(":source-plos"))
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
}
