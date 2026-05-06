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

        buildConfigField(
            "String",
            "VOXSHERPA_PACKAGE",
            "\"com.CodeBySonu.VoxSherpa\"",
        )
        buildConfigField(
            "String",
            "VOXSHERPA_RELEASES_URL",
            // Point at JP's fork — same package id as upstream
            // (com.CodeBySonu.VoxSherpa) plus the dry-run fix from PR #15
            // until/unless upstream merges it.
            "\"https://github.com/jphein/VoxSherpa-TTS/releases\"",
        )
        buildConfigField(
            "String",
            "VOXSHERPA_LATEST_API",
            // GitHub API endpoint the in-app installer hits to discover the
            // latest release's APK asset URL. Kept in BuildConfig so the
            // installer doesn't need to reach into the same constants from
            // multiple modules.
            "\"https://api.github.com/repos/jphein/VoxSherpa-TTS/releases/latest\"",
        )
        buildConfigField(
            "String",
            "VOXSHERPA_MIN_VERSION",
            // Minimum acceptable VoxSherpa versionName. Anything below this
            // (including upstream's "2.6") triggers the install/update gate
            // because the dry-run fix is mandatory for storyvox.
            "\"2.6.1\"",
        )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OkHttp powers the in-app VoxSherpa installer (download APK, follow
    // GitHub redirects). FileProvider lives in androidx.core.
    implementation(libs.okhttp)

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
    androidTestImplementation(libs.androidx.junit)
}
