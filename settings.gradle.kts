pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenLocal hosts the engine-lib AAR during local development
        // (./gradlew :engine-lib:publishToMavenLocal in the VoxSherpa fork).
        // Resolved before JitPack so we can iterate without waiting for
        // JitPack's GitHub→build sync each tag bump.
        mavenLocal()
        google()
        mavenCentral()
        // JitPack hosts the techempower-org/VoxSherpa-TTS :engine-lib AAR. We link the
        // engines in-process to bypass Android's TextToSpeech framework path
        // (its small AudioTrack buffer underruns between sentences on modest
        // hardware, which is the gappy-playback problem v0.4.0 fixes).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "storyvox"

include(":app")
include(":wear")
include(":core-data")
// Plugin-seam Phase 1 (#384) — KSP SymbolProcessor module that emits
// Hilt @IntoSet contributions for @SourcePlugin-annotated FictionSource
// classes. Pure Kotlin/JVM (the processor runs in the Kotlin compiler).
include(":core-plugin-ksp")
include(":core-llm")
include(":core-playback")
include(":core-ui")
include(":source-royalroad")
include(":source-github")
include(":source-mempalace")
include(":source-azure")
include(":source-rss")
include(":source-epub")
include(":source-epub-writer")
include(":source-outline")
include(":source-gutenberg")
include(":source-ao3")
include(":source-standard-ebooks")
include(":source-wikipedia")
include(":source-kvmr")
include(":source-notion")
include(":core-sync")
include(":feature")
