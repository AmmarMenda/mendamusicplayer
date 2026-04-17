plugins {
    // Android Gradle Plugin 9.1.0 is compatible with Gradle 9.4.x
    id("com.android.application") version "9.1.0" apply false

    // Kotlin 2.3.20 or higher is required for Gradle 9.4+ stability
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
