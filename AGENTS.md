# AGENTS.md — Monk Fitness App

Android app (Kotlin, Compose, Room, MVVM) for an 8-week adaptive strength/mobility program. Exercise
demos come from a procedural animation runtime ("MonkEngine"): **pose = intent, engine = geometry,
validation = observer.**

## Build & test

CI is the verifier: `.github/workflows/android.yml` runs `:app:assembleDebug` + `:app:testDebugUnitTest`.
Sandbox toolchain (bundle paths, TLS-intercept CA, `git push` env): `docs/TOOLCHAIN_PROVISIONING.md`.

```bash
export JAVA_HOME=<jdk-17>  ANDROID_HOME=<android-sdk-34>   # Gradle 8.7 via wrapper
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
./gradlew :app:testDebugUnitTest                           # :app:assembleDebug for the APK
```

## Docs that matter

| Doc | Why |
| --- | --- |
| `docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` | How the runtime is organized today; code cites its §/R-rule numbers. |
| `docs/ARCHITECTURE_V2.md` | Carrier contract: `SkeletonPose` intent vs. state, ownership per subsystem. |
| `docs/MIGRATION_RULES.md` | Enforced pose coding standard; prohibited legacy patterns. |
| `docs/VALIDATION.md` | Validation poses and the validation subsystem. |
| `docs/TOOLCHAIN_PROVISIONING.md` | Local build/test toolchain. |

Reference only, not required reading: `docs/CODING_RULES.md`, `docs/ENGINE_ARCHITECTURE.md` (file/joint
map), `docs/BIOMECHANICS.md`, `docs/API_CONTRACTS.md`, `docs/STABILIZATION_AUDIT.md` (forward work),
the matrices under `docs/architecture/`, `docs/MonkEngine Design Principles.md` (philosophy).

## Rules

- **Compile-first, green baseline.** Never leave the branch non-compiling — a compile error blocks the
  task and is fixed before anything else. `:app:testDebugUnitTest` passes; no green test regresses.
- **Pose/engine boundary.** A pose declares intent only — the engine owns IK/FK, contacts, balance,
  reconstruction and validation. Validation poses are diagnostic instruments: fix the engine or
  record the reading; never retune a pose to read green.
- **Small tasks go straight to implementation.** Docs, one pose, a contained bug fix: change, build,
  test, open the PR. An Execution Plan is not a precondition for small changes; write one for large
  or architectural work.
- **History is not guidance.** `docs/HISTORICAL/` is a read-only record; when a doc conflicts with
  live code, the code wins.
