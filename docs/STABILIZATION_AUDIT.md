# MonkEngine Production Exercise Stabilization

**Status:** ACTIVE — Phase S1.
**Engine state:** Architecture migration COMPLETE (Architecture v2). MonkEngine is a finished
engine; no new architecture, carriers, or API changes are in scope.
**Baseline:** `./gradlew :app:testDebugUnitTest` → **282 tests executed, 0 failures, 0 errors.**
**Audit source:** produced by a per-family review of all 60 registered exercises (59 pose files)
across 8 families. Consolidated report: `docs/HISTORICAL` is not the home for this — it lives here
as the active stabilization tracker.

---

## 1. Purpose

Verify every production exercise for:

1. biomechanical correctness,
2. ROM measurements,
3. contacts and posture,
4. Solver/Finalizer interaction,
5. legacy workarounds that are no longer necessary.

Each finding is classified as **bug**, **tuning**, **cleanup**, or **expected behavior**, and
prioritized by severity (P0 HIGH → P1 MEDIUM → P2 LOW).

The architectural-rules background (which the findings must respect) is in `ARCHITECTURE_FREEZE.md`,
`BIOMECHANICS.md`, and `MIGRATION_RULES.md`.

---

## 2. Cross-cutting theme

The dominant theme is that ~14 poses were authored **before** the Branch-B/W1 IK + intent +
contact surface was finalized, and never migrated onto it. Those poses call `SkeletonMath.solveIK`
directly, hand-write `localPosition` world-deltas, and omit `supportContacts` — so they silently
opt out of the engine's carrier instrumentation (`limbTargets`, `maxIkClampAmount`,
`boneLengthsVerified`) and of honest contact validation. The corrective pattern is to route every
limb through `bakeIkLimb` and to declare `supportContacts` where the body is genuinely planted.

---

## 3. Findings by severity

### P0 — HIGH (correctness / honest instrumentation)

| # | Exercise(s) | Finding | Class |
|---|---|---|---|
| H1 | WallSlidesPose | No `WallProp` in environment despite being defined "against the wall" — athlete leans into empty space; validator has no prop to test. | bug |
| H2 | FacePull, ScapularRetraction, WallSlides, ArmCircles, HipCars, KettlebellSwing, Burpee | Bypass `BasePose.bakeIkLimb`, call `SkeletonMath.solveIK` directly → skip `§1.1 limbTargets`, `maxIkClampAmount`, `boneLengthsVerified`; would break under `IK_STAGE_ACTIVE`. | cleanup/bug |

### P1 — MEDIUM (biomechanical fidelity / honest validation)

| # | Exercise(s) | Finding | Class |
|---|---|---|---|
| M1 | StepUpPose | Lead/trail feet at Z=∓25.3 but step prop spans only Z∈[−22,+22]; both feet overhang the step. | bug |
| M2 | IsometricSidePlankPose | `supportContacts={RIGHT_FOREARM,RIGHT_FOOT}` but planted forearm authored on P side while `SupportMath` maps `RIGHT_FOREARM→{ELBOW_A,HAND_A}` (A side). | bug |
| M3 | ProneCobraStretchPose | Whole −1.57→−0.9 trunk extension on PELVIS, not thoracolumbar/lumbar → chest follows rigidly (same class as the S3 ThoracicExtension fix). | bug |
| M4 | SupermanPose | Back extension by rotating pelvis→chest vector, no lumbar articulation; missing supportContacts/exerciseFamily/bodyOrientation metadata. | bug/tuning |
| M5 | ReverseSnowAngelPose | Missing supportContacts/exerciseFamily/bodyOrientation despite planted legs; arm arc maxSweep=170° at fixed Y=15 never clears overhead. | tuning |
| M6 | KettlebellSwingPose | Hinge profile inverted: `pelvisY=lerp(175,210)` makes deep hike taller than top while `leanAngle→0`. | bug |
| M7 | BurpeePose | During plank phases feet translate −110 in X while hands stay X≈25, reversing plank geometry. | bug |
| M8 | 7 upper/dynamic + Burpee/Kettlebell | No `SupportContact` for planted feet / plank-push-up-jump; IK targets never run through `clampTargetToReach` → unreachable authoring silently solver-clamped. | bug |
| M9 | All 8 stretch poses | None declare `supportContacts`/`SupportDefinition` despite fully contact-bearing → CONTACT_PRESERVED/SUPPORT/ground-penetration validation silently disabled. | bug/tuning |
| M10 | GluteBridge, PelvicTilt, MountainClimber | Missing `supportContacts` (feet/feet/hands). | tuning |
| M11 | LatStretchPose | Bypasses `bakeIkLimb` (manual solveIK+rotAround) → not in `limbTargets` carrier. | cleanup |
| M12 | CatCowPose | Raw world positions + fromJointPositions, bypassing bakeIkLimb/buildGaze/intent carriers. | cleanup |
| M13 | HamstringStretchPose | Forward-reach hand target near/beyond arm reach (~200 vs max 146) → solver-clamped. | tuning |
| M14 | DeclinePushUpPose | Decline raises pivot but keeps plank horizontal; real decline tilts head-to-heels plank downward. | tuning |
| M15 | WallSlidesPose | Wall modeled in X but forearms abducted in Z → "forearms flat on wall" not enforced. | tuning |

### P2 — LOW (cleanup / legacy workarounds no longer necessary)

- Redundant double PELVIS joint intent: `declarePelvisTilt` already records `Joint.PELVIS`, yet
  repeated `IntentBuilder(...).joint(PELVIS,…)` immediately after — in PikePushUp, BaseSquatPose +
  4 subclasses, IsometricSidePlank, ProneCobra, ReverseSnowAngel, GluteBridge, PelvicTilt,
  MountainClimber, and the 7 upper/dynamic poses. Delete the duplicate line.
- Dead `handDirA`/`handDirP` in BasePushUpPose (and Wide/Military/Diamond) — never referenced.
- WRIST mirror line (`jointsBuffer.getJoint(WRIST_*).set(HAND_*)`) in push-up/squat/lat/stretch/upper
  poses — engine (W1/Branch C) owns wrist derivation; clobbers `buildWristArticulation`.
- Stale "engine limitation left exposed" / "Phase 4: lean-cancel removed" / "W1" comments.
- Dead `applyBirdDogExtremities` empty no-op still called in all 3 bird-dog variants.
- JumpSquatPose dead abstract-val overrides (squatH=0 etc.) never read.
- HangPose KDoc "shoulders near ears" contradicts `scapularDepressionAt=0`.
- MilitaryPushUpPose `gripWidthMultiplier=1.0` reads shoulder-width, not narrow "military/close".
- SupermanPose / ReverseSnowAngelPose still on legacy `PoseBuilder`+`fromJointPositions` path.
- HamstringStretchPose returns shared `jointsBuffer` directly (not independent snapshot).

---

## 4. Remediation progress

### DONE — P0 (PR #175)

- **H1:** WallSlidesPose now declares a `WallProp` matching the −X lean plane.
- **H2:** Added a package-level `bakeIkLimb` standalone (in `BasePose.kt`, mirroring the
  `BasePose` member) that `PoseBuilder`-direct poses can call. Migrated all 26 limb solves in the
  7 poses onto it. The helper reproduces the exact node local positions while populating
  `limbTargets`, `maxIkClampAmount`, and `boneLengthsVerified`, and correctly converts IK world
  deltas into the parent-local frame (the previous hand-rolled write was only correct for
  identity-rotation parents — subtly wrong for the rotating-pelvis Kettlebell/Burpee, now fixed).
  Removed the redundant duplicate PELVIS joint-intent lines and stale `solveIK`/`rotAround` imports
  in the migrated files.

Result: no compilation/runtime errors; 282/0 baseline holds.

### TODO — P1 (next pass, in priority order)

1. H1 complement + M15 — WallSlides wall prop geometry/tuning + forearm contact plane.
2. H2 complement — migrate LatStretchPose (M11) and CatCowPose (M12) onto `bakeIkLimb`/gaze
   helpers for full carrier coverage; declare `supportContacts` for the stretch family (M9) and the
   core/hip poses (M10) and the upper/dynamic poses (M8).
3. M1/M2/M3/M4/M6/M7 — pose-specific biomechanical-fidelity bugs (step contact, side-plank contact
   side, cobra/superman lumbar extension, kettlebell hinge inversion, burpee foot-translation).
4. M5/M13/M14 — tuning items (snow-angel arc, hamstring reach, decline plank tilt).

### TODO — P2

Cleanup pass: redundant PELVIS intent, dead fields, WRIST mirror, stale comments, dead
`applyBirdDogExtremities`, JumpSquat dead vals.

---

## 5. Working rules

- Treat MonkEngine as a finished engine. Do not introduce new architecture, carriers, or API changes.
- Fix the pose, not the engine, when a pose authors motion incorrectly.
- Keep pose-side migrations on the **existing** carrier surface (the H2 fix is the template).
- After any pose change, confirm `./gradlew :app:testDebugUnitTest` stays at 282/0 before marking
  a finding resolved.
