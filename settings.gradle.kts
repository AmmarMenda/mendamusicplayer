pluginManagement {
    repositories {
        google()        // Look for Android plugins here
        mavenCentral()  // Look for Kotlin/other plugins here
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // Look for Android libraries here
        mavenCentral()  // Look for Kotlin libraries here
    }
}

rootProject.name = "MyManualApp"
include(":app")