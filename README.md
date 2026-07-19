```
                       _ooOoo_
                      o8888888o
                      88" . "88
                      (| -_- |)
                      O\  =  /O
                   ____/`---'\____
                 .'  \\|     |//  `.
                /  \\|||  :  |||//  \
               /  _||||| -:- |||||-  \
               |   | \\\  -  /// |   |
               | \_|  ''\---/''  |   |
               \  .-\__  `-`  ___/-. /
             ___`. .'  /--.--\  `. . __
          ."" '<  `.___\_<|>_/___.'  >'"".
         | | :  `- \`.;`\ _ /`;.`/ - ` : | |
         \  \ `-.   \_ __\ /__ _/   .-` /  /
::==============``-.___\_____/___.-`____.-'============::
```
                   
# Monk Fitness App

Android application for an 8-week structured fitness program,
featuring an animated, biomechanically-driven skeleton that demonstrates each
exercise.

## Features

- **8-Week Program**: Dynamic workout generation across 4 phases (intensity
  increases every 2 weeks).
- **Daily Workouts**: Strength A, Strength B, Mobility, and Functional training.
- **Animated Exercise Demos**: A skeletal MonkEngine renders each
  movement from a biomechanical description of the pose.
- **Posture Correction**: Dedicated exercises for better posture.
- **Progress Tracking**: Weekly completion chart and streak counter.
- **Interactive Timers**: Countdown timers with completion sounds.
- **Daily Reminders**: Configurable notifications.
- **Multi-language**: English and Russian.
- **Dark Theme**: Modern fitness-style UI.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM
- **Database**: Room
- **Settings**: DataStore
- **Charts**: MPAndroidChart
- **CI/CD**: GitHub Actions

## Architecture at a Glance

The app pairs a standard MVVM Android application with a purpose-built animation
engine. the MonkEngine runtime is organized around four separated responsibilities:

- **Engine** solves motion (kinematics, IK, geometry).
- **Pose** describes biomechanics (how the body should move).
- **Exercise** describes metadata (naming, camera, environment).
- **Validation** verifies correctness (read-only checks).

## Engineering Documentation

the MonkEngine's design, principles, and rules are the project's source of truth.
See `docs/`:

- [`docs/ENGINE.md`](docs/ENGINE.md) — MonkEngine architecture.
- [`docs/BIOMECHANICS.md`](docs/BIOMECHANICS.md) — biomechanical philosophy.
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — validation poses and the
  Engineering Validation subsystem.
- [`docs/CODING_RULES.md`](docs/CODING_RULES.md) — permanent engineering rules
  for contributors.

Contributors should read these before working on the MonkEngine runtime or poses.

## Build Instructions

### Prerequisites

- Android Studio Iguana or newer
- JDK 17
- Android SDK 24+

### Local Build

```bash
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### GitHub Actions

A workflow automatically builds the APK on every push to `main`. Artifacts are
available in the repository's "Actions" tab.

## Project Structure

- `animation/`: Skeletal MonkEngine (see `docs/`).
- `poses/`: Biomechanical pose descriptions for each exercise.
- `validation/`: Engineering Validation subsystem (developer tool).
- `data/`: Room entities, DAOs, and DataStore management.
- `domain/`: Business logic including the Workout Generator.
- `ui/`: Compose screens, themes, and reusable components.
- `viewmodel/`: State management for the UI.
- `util/`: Helper classes for notifications, timers, and sounds.
