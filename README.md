# Menda Music Player

A lightweight, native Android music player built with Kotlin, designed for performance, battery efficiency, and seamless local audio playback.

## Features

* **Local Playback:** Automatically scans and plays audio files from the device's internal `Music` directory using the MediaStore API.
* **Background Audio:** Uninterrupted playback maintained via a Foreground Service.
* **System Integration:** Interactive notification and lock screen controls.
* **Audio Focus Management:** Automatically pauses or ducks volume during phone calls, notifications, or when headphones are disconnected.
* **Optimized Caching:** Battery-efficient album art extraction that caches images to local storage to minimize CPU overhead.
* **Core Controls:** Includes play, pause, next, previous, track seeking, and shuffle functionality.

## Requirements

* **Language:** Kotlin
* **Minimum SDK:** Android 8.0 (API Level 26)
* **Build System:** Gradle

## Building the Project

1. Clone the repository.
2. Open the project in Android Studio.
3. Allow Gradle to sync the project dependencies.
4. Build and deploy the application to an Android emulator or physical device.