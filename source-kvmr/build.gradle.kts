plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.kvmr"
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
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Plugin-seam Phase 1 (#384) — adds the @SourcePlugin → @IntoSet
    // SourcePluginDescriptor Hilt module emitted by :core-plugin-ksp.
    // The legacy @IntoMap binding in di/KvmrModule.kt is intentionally
    // kept alongside the new descriptor (Phase 1 invariant: registry
    // is additive over the existing wiring). Phase 2 deletes the
    // @IntoMap binding once the registry-driven repository routing
    // lands.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
