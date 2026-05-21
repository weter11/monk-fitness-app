# Monk Fitness App

A production-ready Android application for an 8-week structured fitness program.

## Features

- **8-Week Program**: Dynamic workout generation across 4 phases (Intensity increases every 2 weeks).
- **Daily Workouts**: Strength A, Strength B, Mobility, and Functional training.
- **Posture Correction**: Dedicated exercises for better posture (Hang, Face Pull, etc.).
- **Progress Tracking**: Bar chart showing weekly completion and streak counter.
- **Interactive Timers**: Countdown timers for exercises with completion sounds.
- **Daily Reminders**: Configurable notifications using AlarmManager.
- **Multi-language**: Support for English and Russian.
- **Dark Theme**: Modern fitness-style UI.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM
- **Database**: Room
- **Settings**: DataStore
- **Charts**: MPAndroidChart
- **CI/CD**: GitHub Actions

## Build Instructions

### Prerequisites

- Android Studio Iguana or newer
- JDK 17
- Android SDK 24+

### Local Build

To build the debug APK locally, run:

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### GitHub Actions

The project includes a workflow that automatically builds the APK on every push to `main`. You can find the artifacts in the "Actions" tab of the repository.

## Project Structure

- `data/`: Room entities, DAOs, and DataStore management.
- `domain/`: Business logic including the Workout Generator.
- `ui/`: Compose screens, themes, and reusable components.
- `viewmodel/`: State management for the UI.
- `util/`: Helper classes for notifications, timers, and sounds.
