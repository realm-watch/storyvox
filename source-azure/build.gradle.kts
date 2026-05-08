plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.azure"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
            // android.util.Log calls in production code would otherwise
            // blow up JVM tests — same shape as :core-llm.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // We read API key + region out of the encrypted SharedPreferences
    // bound by :core-data's DataModule (the same one that holds RR
    // cookies, the GitHub PAT, and the LLM provider keys). Pulling
    // :core-data in as a dep keeps :source-azure single-purpose
    // (synthesize SSML → PCM) without re-binding crypto.
    implementation(project(":core-data"))
    // VoiceEngineHandle interface that AzureVoiceEngine satisfies — that's
    // the exact seam EngineStreamingSource in :core-playback already uses
    // for Piper + Kokoro. Wiring AzureVoiceEngine through that same
    // interface means PR-4 (engine activation) is a one-line switch in
    // EnginePlayer.activeVoiceEngineHandle, no new contract.
    implementation(project(":core-playback"))

    implementation(libs.bundles.coroutines)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
