plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.feature"
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
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    testOptions {
        unitTests {
            // JVM unit tests run against android.jar's stub classes, where
            // every Android-framework method throws "Method ... not mocked"
            // by default. RealBrowsePaginator's failure-path calls
            // android.util.Log.w(...) — production-correct logging that
            // would otherwise crash JVM tests. Returning defaults for
            // unmocked Android methods is the standard AGP pattern (see
            // "Unit testing your app" docs), not lazy mocking — adding
            // Robolectric just to mock android.util.Log would be heavier
            // than the surface here justifies.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-llm"))
    implementation(project(":core-playback"))
    implementation(project(":core-sync"))
    // Issue #117 — EPUB export use case + writer. Pulled in here so the
    // FictionDetail "Export as EPUB" menu can call the use case directly
    // from its ViewModel without routing through the :app module.
    implementation(project(":source-epub-writer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coroutines)
    // Issue #216 — tool-call argument shapes round-trip through
    // [kotlinx.serialization.json.JsonObject]; the chat ViewModel
    // and tool handlers need direct access.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
