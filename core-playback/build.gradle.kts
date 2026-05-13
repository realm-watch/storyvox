plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.playback"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs Android resources on the unit-test classpath
            // so VoiceManagerTest can spin up an ApplicationContext (filesDir +
            // DataStore) without an emulator. Required for the #28 cleanup
            // policy tests.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-data"))
    // PR-4 (#183) — wires AzureVoiceEngine into EnginePlayer's
    // generateAudioPCM dispatch table. Pure-JVM module, no AAR; brings
    // OkHttp + the SSML builder + the engine handle adapter.
    implementation(project(":source-azure"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp + okio power VoiceManager's voice-model downloads, with
    // redirect-following + streaming writes into context.filesDir/voices/{id}/.
    implementation(libs.okhttp)

    // Persistent voice settings (active voice id, installed voice ids).
    implementation(libs.androidx.datastore.preferences)

    // Direct in-process synthesis. The :engine-lib AAR from jphein's
    // VoxSherpa fork brings KokoroEngine/VoiceEngine/Sonic into our process
    // and pulls sherpa-onnx (the actual ML inference) as a transitive dep.
    // Lets storyvox bypass TextToSpeech.speak() and manage its own
    // AudioTrack with a fat buffer for smooth pipelined playback.
    // JitPack publishes our fork's `engine-lib` Gradle module as the
    // single-module path `com.github.jphein:VoxSherpa-TTS:vX.Y.Z` (it
    // collapses multi-module configs to one root coordinate). The actual
    // AAR file at this URL is engine-lib's release artifact.
    // v2.7.13 (storyvox #193) parameterizes Sonic.setQuality via
    // VoiceEngine.sonicQuality / KokoroEngine.sonicQuality static
    // fields (default 1 — high quality). Storyvox exposes a
    // Settings toggle that writes both fields via
    // [VoiceEngineQualityBridge]. Pre-rendered PCM (post-#97) means
    // the ~20% CPU hit lands once per chapter, not per playback.
    implementation("com.github.jphein:VoxSherpa-TTS:v2.7.13")
    implementation("com.github.k2-fsa:sherpa-onnx:1.12.26")

    // Media3 — session, player base classes
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    // Auto support — MediaBrowserServiceCompat lives here
    implementation(libs.androidx.media)

    // Wear OS data layer (phone side talks to it via play-services-wearable)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Compose — for the engine consent dialog atom
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric supplies a JVM-resident ApplicationContext (filesDir +
    // DataStore) for VoiceManagerTest, which exercises the #28 partial-file
    // cleanup policy without needing an emulator.
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
}
