// The Android port of the recorder app (../ios). docs: README.md here, ../docs/recorder.md.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TrainFusion"
// core: plain Kotlin (JVM), no Android: the model's port, the recording format, the backend
// client. So it runs and is tested on a laptop as well as in the app.
include(":core")
include(":app")
