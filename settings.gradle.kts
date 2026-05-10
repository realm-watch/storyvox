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
        // JitPack hosts the jphein/VoxSherpa-TTS :engine-lib AAR. We link the
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
include(":core-llm")
include(":core-playback")
include(":core-ui")
include(":source-royalroad")
include(":source-github")
include(":source-mempalace")
include(":source-azure")
include(":source-rss")
include(":feature")
