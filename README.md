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
- **Custom Program**: Choose which exercises the program may use, grouped by
  family. Invalid selections are rejected with hard errors and unbalanced ones
  warn without blocking; a change applies only to the next workout that has not
  started yet, and the default selection can be restored without losing history.
- **Adaptive Progression**: A deterministic, on-device engine reads the training
  history the app already records and adapts each exercise family's progression
  (PROGRESS, HOLD, REGRESS, RECOVERY). No ML, no LLM, no network.
- **Daily Workouts**: Strength A, Strength B, Mobility, and Functional training.
- **Animated Exercise Demos**: A skeletal MonkEngine renders each
  movement from a biomechanical description of the pose.
- **Posture Correction**: Dedicated exercises for better posture.
- **Progress Tracking**: Weekly completion chart and streak counter.
- **Interactive Timers**: Countdown timers with completion sounds.
- **Daily Reminders**: Configurable notifications.
- **Multi-language**: English, Ukrainian, Russian.
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

## Adaptive Program (Stage 1)

The 56-day program adapts to what the user actually did. The engine is local and
deterministic — same history and same policy, same decision:

```text
Session history
    ↓
SessionObservation
    ↓
Signal calculation
    ↓
AdaptivePolicy / state machine
    ↓
Adaptive decision
    ↓
Family progression resolver
    ↓
Custom Program + equipment constraints
    ↓
WorkoutGenerator
    ↓
Biomechanical validation
```

Boundaries that hold across the pipeline:

- `WorkoutGenerator` remains the concrete workout builder; the adaptive engine only
  constrains which exercises and adjustments it may use.
- Biomechanical validation remains authoritative.
- Adaptive decisions are deterministic and local: no ML, no LLM, no network.
- Configuration changes apply only to future workouts; a started session keeps the
  configuration it captured.
- Progression lives in `family_progression_state`, separately from the immutable
  audit trail in `adaptive_decision_record`.

Not part of Stage 1: camera-based movement assessment, movement-quality scoring, ML,
LLM integration, and exercises generated outside the existing library.

See [`docs/ADAPTIVE_PROGRAM_STAGE1.md`](docs/ADAPTIVE_PROGRAM_STAGE1.md) for the
boundaries, lifecycle semantics and current limitations.

## Engineering Documentation

the MonkEngine's design, principles, and rules are the project's source of truth.
See `docs/`:

- [`docs/ENGINE.md`](docs/ENGINE.md) — MonkEngine architecture.
- [`docs/BIOMECHANICS.md`](docs/BIOMECHANICS.md) — biomechanical philosophy.
- [`docs/VALIDATION.md`](docs/VALIDATION.md) — validation poses and the
  Engineering Validation subsystem.
- [`docs/CODING_RULES.md`](docs/CODING_RULES.md) — permanent engineering rules
  for contributors.
- [`docs/ADAPTIVE_PROGRAM_STAGE1.md`](docs/ADAPTIVE_PROGRAM_STAGE1.md) — adaptive
  program Stage 1 architecture and boundaries.

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
- `domain/adaptive/`: The adaptive engine — signals, policy, state machine,
  progression resolver — with no Android, Room, DataStore or UI dependency.
- `ui/`: Compose screens, themes, and reusable components.
- `viewmodel/`: State management for the UI.
- `util/`: Helper classes for notifications, timers, and sounds.
