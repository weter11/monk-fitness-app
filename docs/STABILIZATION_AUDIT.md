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

### DONE — Push-Up Family (PDP-Family, Level 6)

- **M14:** `DeclinePushUpPose` now slopes the head-to-heels plank downward via a `declineTrunkPitch`
  (chest pitch about the mediolateral Z axis, pelvis untouched so feet stay on the box, hands
  re-solve to the floor through IK). The leg chain remains horizontal (residual noted); the upper
  body now visibly declines instead of staying flat.
- **P2 (dead `handDirA`/`handDirP`):** removed the unused `handDirA`/`handDirP` fields from
  `BasePushUpPose` and the `Wide`/`Military`/`Diamond` overrides (never referenced).
- **P2 (redundant PELVIS intent):** removed the duplicate `declareJointIntent(Joint.PELVIS, …)` in
  `PikePushUpPose` — `declarePelvisTilt` already records the carrier.
- **A8 (pole frame-conversion):** `BasePushUpPose` and `PikePushUpPose` now pass world-space elbow
  poles directly to `bakeIkLimb` (the old `toLocalDirection`→`toWorldDirection` round-trip was
  identity for the identity clavicle/scapula) — no pose-side frame conversion.
- **A6 (hand-computed shoulder placement):** `BasePushUpPose` now reads `shoulderA.worldPosition` /
  `shoulderP.worldPosition` after `buildShoulders` + FK instead of `rotAround` (geometry identical).
  Added the missing `buildShoulders` call to `BasePushUpPose` so the girdle is seated before the
  authoring-FK pass.
- **Family consistency:** every member now declares `pivotType`, `supportContacts`,
  `exerciseFamily = "push-up"`, `motionType = "Press"`, `bodyOrientation = "Prone"` alongside the
  existing `support`, matching the Plank family metadata contract.

NOTE: this pass could not be compiled/validated in-session — the build toolchain (JDK + Android SDK)
was absent from the sandbox. Geometry-preserving changes (poles, shoulder placement, dead-field
removal, metadata) are expected byte-identical; the M14 decline tilt is a deliberate geometry change
that requires the `:app:testDebugUnitTest` push-up suite to confirm before marking M14 fully resolved.

### DONE — B-8 cold-frame limb realization (PR #229)

Cross-family Solver/Finalizer-interaction finding (§1.4) from the P11 whole-system audit
(`docs/AUDIT_P11_WHOLE_SYSTEM.md` on the P11 branch), recorded here because it changes production
geometry on `main`. Pose-side fix only: no engine file, no phase order, no ownership change.

- **Defect.** A pose that leaves its trunk (chest) frame unauthored realized its limbs in the
  pelvis-only frame on the **cold first frame**. The engine's trunk frame for a non-upright trunk is
  produced by the Finalizer's `reconstructChestFrame` — a Phase-3 Finalization-phase *fallback*
  (`ARCHITECTURE_V2` §3 PHASE 3, §2.4, INVARIANT 4) — i.e. **after** the Phase-1 limb realization had
  baked the arm chain; the fallback then re-FK'd the chest subtree and dragged the realized arms with
  it. Frames ≥ 1 only looked correct because the fallback's node write survived in the reused builder
  buffer and was read back as *authored* intent (Issue F), so steady-state correctness was a property
  of cross-build buffer reuse, not of the frame.
- **Measured (`origin/main` @ `4cd8d9a`, progress 0, cold first frame vs settled, ELBOW_A/HAND_A).**
  `pushup_standard` 74.31/160.33, `pushup_wide` 79.50/157.05, `pushup_military` 92.91/155.33,
  `pushup_diamond` 89.80/157.05, `pushup_knee` 82.01/108.22, `pike_pushup_standard` 92.21/145.00,
  `side_plank_standard` 62.22/28.00; the standard plank's hands sat **108.84** units above the floor.
  Control: `pushup_decline` authors its trunk pitch, the guard early-returns, delta 0.00/0.00.
- **Fix (ordering at the correct boundary).** The trunk frame is pose-owned Phase-0 intent
  (`ARCHITECTURE_V2` §4.1), so the affected families **declare** it through the single existing path —
  chest node write + its §1.1 joint carrier: `BasePushUpPose.declareFlatPlankTrunkFrame()` (both pivot
  branches, plus `PikePushUpPose`, which authors its own `onBuild`) and `IsometricSidePlankPose` (its
  girdle roll is the layout statement the frame must follow). The Finalizer keeps the fallback and its
  exclusivity; the fallback's trigger condition (identity chest) is simply no longer met for these
  poses, and a member that authors its own trunk pitch keeps it.
- **After.** Cold-vs-settled limb delta **0.000000** for every joint of every fixed family;
  `ColdFrameLimbRealizationTest` **7/7** (RED 3/7 pre-fix with the numbers above). Full suite
  `origin/main` 104 classes / 457 tests → **105 / 464 / 0F / 0E / 0S**. Whole-corpus dump (53 poses ×
  5 progress × cold+settled = 17,490 joint-lines): **200 differ** — the six flat-plank members on the
  COLD frame only (byte-identical on every later frame), `side_plank_standard` additionally
  ≤ 4.6e-5 units on settled frames (authored analytic axis vs the derivation's float-rounded axis), and
  the other **46 poses byte-identical everywhere**. One P0 golden updated
  (`RuntimeArchitectureBaselineTest.PushUpGolden`, documented in the fixture).
- **T-7 relation (measured, three trees, same corrected sweep md5 `97144f78…`).** `origin/main` + T-7 →
  **32 ERRORs (28 arm-chain + 4 head/neck)**; B-8 + T-7 → **4 ERRORs (0 arm-chain)**; B-7 + B-8 + T-7 →
  **0 ERRORs (green)**. B-8 was the arm-chain blocker; the correction stays its own change.
- **Recorded, NOT fixed — B-8b.** `thoracic_extension_reps` is **not** a B-8 victim (its trunk frame is
  already authoritative — identity and frame-invariant). Its residual (ELBOW_A 29.93 / HAND_A 17.91) is
  a different defect: the pose derives both arm targets from `neck!!.worldPosition` while the neck's
  local offsets are written by the engine (`resolveHeadTarget`, Phase 7), so the first build realizes
  against a target it never sees again (declared-target delta 16.67 units). Pinned by attribution in
  `ColdFrameLimbRealizationTest` so it cannot be masked or mis-attributed.
- **Still open (P11 backlog, unchanged).** T-7, B-2 (`PoseMetadata.supportContacts` write-only), B-3
  (`*_TOES`/`*_FOREARM` never consulted), B-4 (three contradictory SupportPoint↔Joint maps), B-5
  (renderer overload passes ∅), B-6 (`EnvironmentPenetrationTest` vacuity), §12.7 flag lifecycle.

### TODO — P1 (next pass, in priority order)

1. H1 complement + M15 — WallSlides wall prop geometry/tuning + forearm contact plane.
2. H2 complement — migrate LatStretchPose (M11) and CatCowPose (M12) onto `bakeIkLimb`/gaze
   helpers for full carrier coverage; declare `supportContacts` for the stretch family (M9) and the
   core/hip poses (M10) and the upper/dynamic poses (M8).
3. M1/M2/M3/M4/M6/M7 — pose-specific biomechanical-fidelity bugs (step contact, side-plank contact
   side, cobra/superman lumbar extension, kettlebell hinge inversion, burpee foot-translation).
4. M5/M13/M14 — tuning items (snow-angel arc, hamstring reach, decline plank tilt).

### TODO — P2

Cleanup pass (remaining): WRIST mirror, stale comments, dead `applyBirdDogExtremities`,
JumpSquat dead vals. (Push-Up family items — dead `handDirA`/`handDirP`, redundant PELVIS intent,
A8/A6 leaks — resolved in the Push-Up Family pass above.)

---

## 5. Working rules

- Treat MonkEngine as a finished engine. Do not introduce new architecture, carriers, or API changes.
- Fix the pose, not the engine, when a pose authors motion incorrectly.
- Keep pose-side migrations on the **existing** carrier surface (the H2 fix is the template).
- After any pose change, confirm `./gradlew :app:testDebugUnitTest` stays at 282/0 before marking
  a finding resolved.
