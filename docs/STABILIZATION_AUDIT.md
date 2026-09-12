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
directly, hand-write `localPosition` world-deltas, and omit the support declaration — so they silently
opt out of the engine's carrier instrumentation (`limbTargets`, `maxIkClampAmount`,
`boneLengthsVerified`) and of honest contact validation. The corrective pattern is to route every
limb through `bakeIkLimb` and to declare their support contacts (`metadata.support`) where the body
is genuinely planted.

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
| M2 | IsometricSidePlankPose | `support.contacts={RIGHT_FOREARM,RIGHT_FOOT}` but planted forearm authored on P side while `SupportMath` maps `RIGHT_FOREARM→{ELBOW_A,HAND_A}` (A side). | bug |
| M3 | ProneCobraStretchPose | Whole −1.57→−0.9 trunk extension on PELVIS, not thoracolumbar/lumbar → chest follows rigidly (same class as the S3 ThoracicExtension fix). | bug |
| M4 | SupermanPose | Back extension by rotating pelvis→chest vector, no lumbar articulation; missing support declaration/exerciseFamily/bodyOrientation metadata. | bug/tuning |
| M5 | ReverseSnowAngelPose | Missing support declaration/exerciseFamily/bodyOrientation despite planted legs; arm arc maxSweep=170° at fixed Y=15 never clears overhead. | tuning |
| M6 | KettlebellSwingPose | Hinge profile inverted: `pelvisY=lerp(175,210)` makes deep hike taller than top while `leanAngle→0`. | bug |
| M7 | BurpeePose | During plank phases feet translate −110 in X while hands stay X≈25, reversing plank geometry. | bug |
| M8 | 7 upper/dynamic + Burpee/Kettlebell | No `SupportContact` for planted feet / plank-push-up-jump; IK targets never run through `clampTargetToReach` → unreachable authoring silently solver-clamped. | bug |
| M9 | All 8 stretch poses | None declare a support model (`metadata.support.contacts`) despite fully contact-bearing → CONTACT_PRESERVED/SUPPORT/ground-penetration validation silently disabled. | bug/tuning |
| M10 | GluteBridge, PelvicTilt, MountainClimber | Missing support declaration (feet/feet/hands). | tuning |
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
- **Family consistency:** every member now declares its support model (`metadata.support`),
  `exerciseFamily = "push-up"`, `motionType = "Press"`, `bodyOrientation = "Prone"` alongside the
  geometry, matching the Plank family metadata contract. (The `pivotType` / `supportContacts`
  duplicates this pass added were removed by **B-2** — the support fact now has one channel.)

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
- **T-7 relation (measured, three trees, same corrected sweep md5 `97144f78…`; recorded when `origin/main`
  was `4cd8d9a`, the B-1 merge — i.e. **pre-B-8**).** `origin/main` + T-7 →
  **32 ERRORs (28 arm-chain + 4 head/neck)**; B-8 + T-7 → **4 ERRORs (0 arm-chain)**; B-7 + B-8 + T-7 →
  **0 ERRORs (green)**. B-8 was the arm-chain blocker; the correction stays its own change.
- **T-7 correction landed as its own PR (branch `fix/t7-knee-pushup-frame-snapshot`) — TEST FILE ONLY.**
  `KneePushUpPoseTest.testKneePushUpPoseBiomechanicalCompliance` retained `produceFrame(...).pose`, which is
  the Finalizer's reused `outputPose`, so all 100 sweep entries were the same object: `previousPose ===
  currentPose` on every step, and `HAND_SLIDING` / `POSITION_DISCONTINUITY` / `VELOCITY_DISCONTINUITY` could
  only ever compare a frame with itself. Re-measured on the post-B-8 `main` (`60ee581`): pre-fix storage =
  **1** distinct identity, `0.00u` CHEST spread, `99/99` self-comparisons, **0** validator issues (the sweep
  is structurally inert — it stayed green while B-1 and B-7 shipped); corrected sweep = **100/100** distinct
  objects, **57** distinct CHEST heights, `70.44u` spread, and **4 ERRORs — 0 arm-chain + 4 head/neck**
  (`NECK_END` 21.39u / `HEAD_POS` 42.75u position pop at frame 1, velocity jump at frame 2), i.e. exactly
  B-7's residual. With B-7's `BasePose` overlaid the same corrected class is **0 ERRORs (green)**; on pre-B-8
  `4cd8d9a` it was **32 (28 arm-chain + 4 head/neck)**. The corrected test was therefore RED on `main` while
  B-7 was unmerged, and is **green on `main` once B-7 landed** (`dc4cc27`, PR #228 merged 2026-09-12:
  corrected class **0 ERRORs**, 100/100 distinct frames; full suite **106 classes / 468 tests / 0F / 0E /
  0S**). Fix shape: `SkeletonPose().apply { copyFrom(produceFrame(rawPose).pose) }` — the
  by-value rule `MotionProbe` documents and the pattern `SkeletonPipeline` already uses for its own Frame
  History (`previous = SkeletonPose().apply { copyFrom(finalized) }`) — plus a sweep-independence guard
  (100 distinct frame objects + a real CHEST-height spread) so the aliasing cannot return silently. No
  assertion weakened or removed; production runtime untouched.
- **Recorded, NOT fixed — B-8b.** `thoracic_extension_reps` is **not** a B-8 victim (its trunk frame is
  already authoritative — identity and frame-invariant). Its residual (ELBOW_A 29.93 / HAND_A 17.91) is
  a different defect: the pose derives both arm targets from `neck!!.worldPosition` while the neck's
  local offsets are written by the engine (`resolveHeadTarget`, Phase 7), so the first build realizes
  against a target it never sees again (declared-target delta 16.67 units). Pinned by attribution in
  `ColdFrameLimbRealizationTest` so it cannot be masked or mis-attributed.
- **Still open (P11 backlog).** B-6 (`EnvironmentPenetrationTest` vacuity), §12.7 flag lifecycle.
  (T-7 landed as PR #230; B-2 + B-3, B-4 and B-5 landed — see the blocks below.)

### DONE — B-2 + B-3 support declaration channel + contact-kind consumption (P11)

One problem in two halves: *where* support intent is declared, and whether the extremity derivation
consumes the contact KIND the pose declared. Both are engine/declaration changes only — no pose
geometry, no tolerance, no validator rule was touched.

- **B-2 — `PoseMetadata.supportContacts` was a write-only channel.** `PoseMetadata` carried three
  copies of one fact: `support` (`SupportDefinition.pivot` + `.contacts`) — the channel BOTH
  production readers already used (`SkeletonPipeline.buildAndInject` for playback and
  `ExerciseAnimation` for the renderer path) — plus `supportContacts: Set<SupportContact>` (15
  writers / 0 readers in `app/src/main`) and `pivotType: PivotType` (15 writers / 0 readers).
  `StaticForearmPlankPose` and `IsometricSidePlankPose` declared their support **only** on the unread
  channel, so the published frame carried `supportedPoints = []` at every progress value and the
  Finalizer's support-plane derivation was entirely off; their `pivotType = ELBOWS` also contradicted
  their own `support.pivot` (the default `FEET`). Fix: both duplicate channels are deleted —
  `PoseMetadata` now has ONE support declaration — and the two planks declare through
  `metadata.support`. Nothing new was introduced: the pipeline/renderer path
  (`metadata.support.contacts → SkeletonPose.supportedPoints → Finalizer derivation`) already existed
  and was correct; the declaration simply never reached it. Post-fix the two poses publish their
  declared model: `plank_standard` `{LEFT_FOREARM, RIGHT_FOREARM, LEFT_TOES, RIGHT_TOES}`,
  `side_plank_standard` `{RIGHT_FOREARM, RIGHT_FOOT}`.
- **B-3 — `*_TOES` / `*_FOREARM` were never consulted by the extremity derivation.**
  `SkeletonPoseFinalizer.adjustFootOrientation` resolved its support point as
  `footSupportPointFor(ankleId) ∈ {LEFT_FOOT, RIGHT_FOOT}` and `adjustHandOrientation` as
  `LEFT_HAND` / `RIGHT_HAND`, so a `*_TOES`-declaring pose (the whole plank/push-up family) and a
  `*_FOREARM`-declaring pose resolved to `null`, got no support plane, and the extremity was never
  oriented against the surface the pose declared. Fix: the resolution is now **family-aware**
  (`declaredFootSupportPoint` / `declaredHandSupportPoint`) — the pose's declared
  `SupportPoint` is resolved **verbatim** over its declaration family and *that value* is handed to
  `supportPlaneNormalFor`. No contact→joint map, no side convention and no new mapping is
  introduced: the engine's existing `contactJointsFor` already defines `*_TOES` and `*_FOOT` as the
  same `{ankle, heel, toe}` support, and the `A/P/F/B ↔ left/right` convention (B-4) is deliberately
  untouched.
- **Measured (published frames, builder path, both trees; corpus = 51 production pose classes × 5
  progress + the cold first frame, every joint).**

  | measurement | pre-fix (`origin/main` `dc4cc27`) | post-fix |
  | --- | --- | --- |
  | `pushup_standard` `TOE_F−ANKLE_F` / `HEEL_F−ANKLE_F` | `+17.57` / `−7.18` (declared floor contact floating and pointing up) | `0.000` / `0.000` |
  | `pushup_wide` / `military` / `diamond` / `decline` (same two readings) | `+17.57` / `−7.18` | `0.000` / `0.000` |
  | `plank_standard` / `side_plank_standard` published support set | `[]` | the declared sets above |
  | corpus joint diff | — | **6 poses changed** (the push-up family), and **only their 4 heel/toe joints**; the other **45 pose classes byte-identical** |
  | every other joint family (arms, knees, pelvis, head) | — | byte-identical everywhere |

- **Residual, recorded and NOT fixed (pose geometry, not declaration consumption).** The two planks'
  *joint geometry* is identical pre/post: their feet already lie in the support plane
  (`TOE/HEEL − ANKLE = 0.000` at every sampled progress), so the newly-armed foot flattening is a
  no-op there, and their hands remain behind the pre-existing geometric `planted` precondition
  (`hand.y <= elbow.y + 1`) because both poses author their support elbow **below their own floor** —
  `plank_standard` `ELBOW_A/P = −18.68 … −44.75`, `side_plank_standard` `ELBOW_P = −37.86` (measured
  on `origin/main`; these are the same floor violations the P11 audit lists, and the poses' own §7
  debt). Relaxing that precondition is a pose-geometry fix with its own review, not a
  contact-consumption fix.
- **Validation-path effect (observed).** `EnvironmentPenetrationTest` names both planks in its
  variant list and used to `continue` on them (`if (contacts.isEmpty()) continue`), i.e. its
  penetration invariant asserted **nothing** for the two poses whose declaration was invisible —
  the vacuity that hid the defect. With the declaration now published, the invariant evaluates them
  (ankle/heel/toe at y=15…22 above the floor) and stays green. The test's own float-disclaimer
  vacuity and its private contact→joint copy (B-6 / T-6) are untouched and still open.
- **Regression coverage (fresh runs).** `SupportDeclarationChannelTest` (4) +
  `SupportContactKindConsumptionTest` (7) = **11 tests**, all asserted on the **published frame** of
  the production pipeline (never on a helper production does not call), with declaration-removal
  controls. Pre-fix, the same two test files run against an `origin/main` `dc4cc27` worktree:
  **11 completed, 7 failed** — `everyProductionPosePublishesExactlyItsDeclaredSupportModel`,
  `forearmPlankPublishesItsDeclaredSupportModel` (`expected [LEFT_FOREARM, RIGHT_FOREARM, LEFT_TOES,
  RIGHT_TOES] but was []`), `sidePlankPublishesItsDeclaredSupportModel` (`expected [RIGHT_FOREARM,
  RIGHT_FOOT] but was []`), `removingTheDeclarationEmptiesThePublishedSupportModel`,
  `toesDeclaredFeetAreOrientedAgainstTheirSupportPlane` (`heel/toe deviate 17.571602u from the
  ankle`), `forearmDeclaredHandsAreOrientedAgainstTheirSupportPlane` (`palm/fingertips deviate
  20.117798u from the hand`), `declaredContactKindsSurviveVerbatimOnThePublishedFrame`. The four
  controls (`footDeclaredFeetResolveThroughTheSameFamily`, both `…DeclarationLeaves…OffPlane`,
  `authoredAnkleArticulationIsNotOverridden`) are green on BOTH trees by design — they are what makes
  the five positive assertions declaration-driven rather than "every extremity is flattened now".
  Post-fix: **11 / 0F**, full suite **108 classes / 479 tests / 0F / 0E / 0S** (pre-fix baseline on
  `origin/main` `dc4cc27`: 106 classes / 468 tests / 0F / 0E / 0S).

### DONE — B-4 one authoritative SupportPoint ↔ Joint mapping (P11)

The engine defined the `SupportPoint ↔ Joint` relation **four times in production plus once in a
test**, with two opposite side conventions — including two mutually contradictory maps in one file
area. B-2/B-3 deliberately left it open (`IsometricSidePlankPose` declared `RIGHT_FOOT` while the
side convention in force resolved that declaration to the opposite physical foot). B-4 establishes
the authority from repository evidence and removes the duplicates. **No pose geometry, no contact
declaration, no validator rule and no tolerance was changed.**

- **Inventory — every production definition, with the convention it encoded.**
  | # | site | relation | convention | consumer |
  | --- | --- | --- | --- | --- |
  | M1 | `SupportMath.kt:12-30` `jointsForContactMap` | `SupportPoint → List<Joint>` | **LEFT = B/P family** | `computeSupportCentroid` — its only caller is `SupportMathTest` (production-dead) |
  | M1b | `SupportMath.kt:84-95` `getPivotPosition` locals (`left = ANKLE_B`, `left = HAND_P`, …) | implicit encoding of M1 | **LEFT = B/P** | symmetric midpoint (behaviour-neutral, convention-teaching) |
  | M2 | `SkeletonPoseFinalizer.kt:933-944` `footSupportPointFor` / `toesSupportPointFor` | `Joint(ankle) → SupportPoint` | **RIGHT_FOOT = the F foot** | `declaredFootSupportPoint` → the foot support-plane gate (**live**) |
  | M3 | `SkeletonPoseFinalizer.kt:1003-1013` `contactJointsFor` | `SupportPoint → List<Joint>` | **LEFT = A/F family** (forearms side-agnostic: both elbows) | `supportPlaneNormalFor` (**live**) |
  | M4 | `SkeletonPoseFinalizer.kt:984-989` `declaredHandSupportPoint` | `Joint(hand) → SupportPoint` | **LEFT = A family** | the hand support-plane gate (**live**) |
  | M5 | `EnvironmentPenetrationTest.kt:80-88` `supportJoints` | test-only copy of M3 | LEFT = A/F | test-only |
  Contradictions: **M1 ↔ M3/M4/M5** (every paired point inverted) and, inside the same file area,
  **M2 ↔ M3** (M2 and the consumer of M3 are meant to be inverses of each other and were not). M3's
  `LEFT_FOREARM`/`RIGHT_FOREARM` → `{ELBOW_A, ELBOW_P}` was a third variant (side-agnostic).
- **Authority (evidence, in the priority order that decided it).** (1) **`ENGINE.md` §4 "Joint
  naming: A/P and F/B"** (ACTIVE architecture doc): "A = active / foreground limb (left), P = passive
  / background limb (right) for the arms and hands. F = foreground, B = background for the legs and
  feet" — the doc is now explicit that F/B is the same left/right pairing (§4 amended by this PR).
  (2) **Authored geometry** (published frames): `buildPelvis`/`buildShoulders` put `HIP_F` and
  `SHOULDER_A` at −Z and `HIP_B`/`SHOULDER_P` at +Z, so A ≡ F and P ≡ B are the two physical sides;
  the production `Camera` (yaw 1.19, `screenX = 0.37x + 0.93z`) renders the −Z (A/F, near/foreground)
  limb on screen-left. (3) **Pose declarations/comments**: `BirdDogPose` ("RIGHT side -> left arm
  (A) + right leg (B)"; `AlternatingBirdDogPose` names P+left leg / A+right leg), `PikePushUpPose`
  ("Correcting the Right-Side (Side B) Floating Leg Asymmetry", `hipB` at +Z), `WidePushUpPose`
  ("LEFT_HAND (HAND_A)"), `IsometricSidePlankPose` (down-side support = `SHOULDER_P`/`HIP_B`, and it
  declares `RIGHT_FOREARM` + `RIGHT_FOOT`), `ARCHITECTURAL_AUDIT_SKELETON_MODEL.md` (A/F = Left, P/B =
  Right). (4) **Canonical production consumers**: M3 and M4 already encoded LEFT = A/F — and the hand
  gate is the internal cross-check the feet contradicted (a `LEFT_TOES`-only declaration flattened
  the RIGHT foot while `LEFT_HAND` flattened the LEFT hand, in the same frame).  **Minority/legacy:
  M1, M1b, M2.**  Note the `RFC_JOINT_OWNERSHIP_MATRIX.md` §1 table labels the legs "front/back"
  (stride-legacy wording); the geometry shows `HIP_F`/`HIP_B` differ only along Z, and `ENGINE.md` §4
  resolves F/B = foreground/background = the near/far (left/right) pair.
- **Canonical mapping after the fix** (one definition, `SupportMath.jointsBySupportPoint`; first joint
  = the anchor used by centroid math): `LEFT_FOOT → {ANKLE_F, HEEL_F, TOE_F}` · `RIGHT_FOOT →
  {ANKLE_B, HEEL_B, TOE_B}` · `LEFT_TOES → {TOE_F, ANKLE_F, HEEL_F}` · `RIGHT_TOES → {TOE_B, ANKLE_B,
  HEEL_B}` · `LEFT_KNEE → KNEE_F` · `RIGHT_KNEE → KNEE_B` · `LEFT_HAND → {HAND_A, PALM_A, KNUCKLES_A,
  FINGERTIPS_A}` · `RIGHT_HAND → {HAND_P, …}` · `LEFT_ELBOW → ELBOW_A` · `RIGHT_ELBOW → ELBOW_P` ·
  `LEFT_FOREARM → {ELBOW_A, HAND_A}` · `RIGHT_FOREARM → {ELBOW_P, HAND_P}` · `HIPS`/`BACK`/`PELVIS` →
  `{PELVIS, HIP_F, HIP_B}` · `CUSTOM` → ∅ (opaque; no invented joint).
- **Removed / redirected.** M1's inverted table → the canonical table above; M1's private
  `getJointsForContact` deleted (replaced by `SupportMath.jointsFor`, one lookup, still
  allocation-free); M1b's `left`/`right` locals renamed to the canonical `f`/`b`, `a`/`p` (a
  behaviour-neutral name that no longer teaches the inversion); **M2 and M4 deleted entirely** — the
  gates now resolve the declaration family from the canonical map's inverse
  (`SupportMath.supportPointsFor`, ordered `*_FOOT` before `*_TOES`, `*_HAND` before `*_FOREARM`), so
  a joint→side pair can no longer disagree with the joints the same map names; **M3 deleted** —
  `supportPlaneNormalFor` reads the same canonical map (the forearm rows also stop mixing both arms:
  a forearm support is now its OWN elbow→hand). Result: **exactly one production definition**, with
  two thin consumers and no duplicate semantic copy.
- **Measured (whole corpus: 49 registered pose classes × 5 progress values, EVERY joint's x/y/z,
  published frames, builder path; artifacts `/home/wer/devis/p11-audit/b4-full-{before,after}.tsv`).**

  | measurement | pre-fix (`origin/main` `b6ee9f1`, B-4 merged) | post-fix |
  | --- | --- | --- |
  | corpus rows compared | 245 | 245 |
  | **changed rows** | — | **5** — all `side_plank_standard` (`p = 0 … 1`) |
  | **changed joints** | — | only `HEEL_B` (`y 7.82 → 15.00`) and `TOE_B` (`y 32.57 → 15.00`) |
  | other 48 pose classes | — | **byte-identical, every joint** |
  | published `supportedPoints` | — | **identical everywhere** (B-2/B-3 semantics untouched) |
  | `side_plank_standard` foot deviation | `devB = 17.572` (planted foot un-flattened) | `devB = 0.000` |
  | one-sided push-up fixture (`LEFT_TOES` only) | `devF = 17.572` / `devB = 0.000` | `devF = 0.000` / `devB = 17.572` |

- **RED → GREEN (fresh runs, semantic not string assertions).** Two counterfactuals, both measured:
  1. **The pre-fix finalizer maps restored** (M2+M3+M4 verbatim, canonical `SupportMath` present):
     `15 tests completed, 6 failed` — both static-audit tests
     (`expected:<[SupportMath.kt]> but was:<[SupportMath.kt, SkeletonPoseFinalizer.kt]>`, plus the five
     contradictory pair lines at `SkeletonPoseFinalizer.kt:934/935/941/942/985`) and all four
     production-behaviour tests (`LEFT_TOES p=0.0 … deviate 17.571602u`; `side_plank_standard p=0.0 …
     deviates 17.571602u`; the swapped `LEFT_FOOT` side-plank fixture `devF=0.0, devB=0.0` — the
     declaration landing on the other foot; the planted-foot assertion).
  2. **The canonical table's sides inverted** (the legacy convention restored in the map itself):
     `15 tests completed, 9 failed` — the five canonical-mapping/side-semantics tests
     (`expected:<[ANKLE_F, HEEL_F, TOE_F]> but was:<[ANKLE_B, HEEL_B, TOE_B]>`, …) and the same four
     production-behaviour tests. The static guards stay green here (one file, self-consistent but
     inverted) — the two counterfactuals exercise different failure modes by design.
- **Regression coverage (fresh runs).** `SupportPointMappingAuthorityTest` (9: canonical mapping per
  family, the general "no paired point crosses the side boundary" invariant, canonical inverse +
  precedence, map↔inverse agreement, side semantics with the production projection, and the two
  static contradiction guards) + `SupportPointSideConsumptionTest` (6: one-sided declaration on a
  production push-up for both kinds and both extremities, `IsometricSidePlankPose`, the swapped
  side-plank control, the corpus-wide one-sided rule over the production registry, and the two
  `BirdDogPose`/`AlternatingBirdDogPose` convention witnesses) = **15 tests**, all asserted on the
  **published frame**. Whole suite: **110 classes / 494 tests / 0F / 0E / 0S** (pre-fix baseline,
  measured in this same environment: 108 classes / 479 tests / 0F / 0E / 0S).
- **Recorded, NOT fixed (out of B-4 scope).**
  - `SupportPoint.*_KNEE` declarations still have **no consumer** in the extremity derivation (the
    gate is keyed on the ankle/hand joints), so `pushup_knee`'s published `LEFT_KNEE`/`RIGHT_KNEE`
    are declared but never flatten a knee — unchanged by B-4 (B-3 scope; the canonical knee entries
    are now defined and asserted but inert).
  - `*_ELBOW` support points now map to their own elbow instead of ∅; still inert for the same reason
    (no pose declares one, and the derivation's gate never asks for an elbow).
  - `EnvironmentPenetrationTest.supportJoints` (M5) is still a private test copy of the canonical map
    — deliberately not redirected here (that file is B-6's subject: its float-vacuity and its copy are
    the open B-6 finding).
  - `WidePushUpPose.kt:14-15`'s comment states `LEFT_HAND (HAND_A) points left (+Z …)`; the *name*
    pairing is canonical, but the `+Z` sign contradicts the authored geometry (`HAND_A` at −Z) and
    `ENGINE.md` §4. The pose's authored `headings` (`HAND_A → +Z`, `HAND_P → −Z`) therefore point both
    hands inward. Flagged, NOT changed: it is pose `headings` intent, not the support mapping.

### DONE — B-5 the renderer entry point can no longer drop the Frame Context (P11)

PR #233 (`fix/b5-extremity-articulation-runtime-context` @ `661737a`, rebased onto the B-4 merge
`b6ee9f1`; the numbers below were re-measured on that rebased base). Engine-side
declaration/entry-point change only: no pose geometry, no carrier, no phase, no ownership, no
tolerance.

- **Defect.** `SkeletonPipeline.produceFrame(builtPose, environment = EnvironmentDefinition(),
  supportedPoints = emptySet())` — the renderer / bare-pose entry point — resolved the §5 R8 Frame
  Context from its **caller** and defaulted BOTH halves to empty, while `injectRuntimeContext`
  overwrote whatever context the supplied frame already carried. R8's declaration source is
  `PoseMetadata.support` / `.environment`, and R8 / R11 deliberately keep Production Metadata out of
  the carrier, so that entry point has no declaration source of its own: every caller must hand one
  in, and nothing made an omission visible — the audit's "fallback/default behaviour that masks a
  missing declaration".
- **Measured (`origin/main` `b6ee9f1`; 49 registered poses × 5 progress; artifacts in
  `/home/wer/devis/p11-audit/b5/`).**

  | measurement | pre-fix | post-fix |
  | --- | --- | --- |
  | re-entering a produced frame through the renderer path — Frame Context preserved | **125 / 245** frames (**24 poses erased**: support model emptied, environment replaced, republished frame `[]`) | **245 / 245** |
  | builder path vs renderer path fed the *identical resolved context* — max joint deviation | `0.000000` | `0.000000` |
  | renderer path with the declaration dropped — max joint deviation | `22.762941` (`pike_pushup_standard` @1.0 `TOE_F`; `pushup_standard` hand endpoints `20.117798`; `side_plank_standard` foot `17.571602`), 42 frames over 9 poses | unchanged (the declaration-free reading is still the degraded one — that is what the tests' sensitivity controls pin) |
  | builder-path corpus (49 × 5, every joint) | md5 `0a800895dcd0b4ef00caee848a1b3b79`, 0 diff lines | md5 `0a800895…`, 0 diff lines — **byte-identical; no golden fixture updated** |

- **The finding's three call sites.** `ExerciseAnimation` re-derived the Support Declaration by hand
  (`metadata.support.contacts.map { it.point }` — a second copy of the pipeline's derivation);
  `ValidationPoseLauncher` forwarded the environment but **omitted** the declaration, so the
  validation viewer published `supportedPoints = []` for every declaring pose and its extremity
  geometry disagreed with playback; `SkeletonSnapshotRenderer.renderPose` never forwarded even the
  `environment` it draws with. The audit's "trivially correct one-line change, measurably inert until
  the support set is supplied" is now both — one line plus the declaration it was inert without.
- **Fix shape.** (1) `SupportDefinition.supportPoints` is the ONE production resolution of R8's
  "Production Metadata support context"; `SkeletonPipeline.buildAndInject` uses it instead of its
  inline copy (the B-2 block's diagram channel is unchanged — it now has a single named resolution).
  (2) The renderer overload's parameters default to the Frame Context the frame ALREADY carries:
  omission preserves, an explicit argument (including an explicitly empty model) is still forwarded
  verbatim and wins; the self-aliasing case is handled inside `injectRuntimeContext`, so R8's single
  injection point and its ordering are untouched. (3) `SkeletonRenderer` gained the same forwarding
  defaults, `SkeletonSnapshotRenderer.renderPose` gained the parallel `supportedPoints` parameter and
  forwards both, its `renderSequence` supplies the declaration, and `ExerciseAnimation` /
  `ValidationPoseLauncher` resolve `metadata.support.supportPoints`.
- **Architecture check.** R8 (one pipeline-performed injection per frame; sources = External
  Environment Definition + the Production Metadata support declaration) and R11 / §3.1 / A29
  ("Production Metadata bypasses the carrier") are preserved: the declaration stays external, no new
  carrier or field, no phase or ordering change, no public signature removed (both new parameters are
  defaulted). What changed is only *which source* the renderer overload resolves from when the caller
  omits arguments: the frame's own injected context instead of an invented empty model. The audit's
  other option — "carry metadata/support on `SkeletonPose`" — is barred by R8/R11 and was not taken.
- **Regression coverage (fresh runs).** `RuntimeContextDeclarationTest` (6): the erasure sweep over
  the whole production registry; omitted == carried == explicitly-supplied-own-context; the two entry
  points agree to `0.000000` on identical inputs **with a sensitivity control** (dropping the
  declaration must be detectable — 42 frames over 9 poses); an explicit empty Frame Context is still
  honoured; plus two source-level guards (exactly one production resolution of the declaration; every
  renderer call site resolves/forwards the Frame Context). `ExtremityArticulationTest` (6) was
  **rewritten onto the production path**: both legs now run `produceFrame(PoseBuilder, PoseContext)`
  on independently constructed instances of the same pose, the reference leg clearing the carrier
  inside `build` (where the pose authors it), with the shared Frame Context ASSERTED rather than
  assumed, non-vacuity checks (carrier on/off, distinct published frames, no shared node tree), a
  completeness pin on the migrated set derived from the production registry (**11** poses), a
  dropped-declaration sensitivity control, MANUAL_OVERRIDE preservation over all three carrier-only
  endpoints plus its auto-derived control, and the 2-DOF wrist composer with its own non-vacuity.
- **RED → GREEN.** `RuntimeContextDeclarationTest` against a worktree of `b6ee9f1` (only the two API
  differences adapted — `contacts.map { it.point }` instead of the new accessor — so the same
  assertions run): **6 tests / 4 failures** —
  `rendererEntryPointNeverErasesTheFrameContextItIsHanded` (naming all 24 erased poses),
  `omittedFrameContextIsTheContextTheFrameCarries`,
  `productionRendererCallSitesResolveTheFrameContext`, `supportDeclarationHasOneProductionResolution`.
  Post-fix **6 / 0F**. Full suite **110 classes / 494 tests** → **111 / 496 / 0F / 0E / 0S**;
  `:app:compileReleaseKotlin` + `:app:assembleRelease -x lintVitalRelease` + `:app:assembleDebug`
  green.
- **The previous test WAS vulnerable to false-green — recorded explicitly.** `ExtremityArticulationTest`
  compared a builder-path frame against a frame produced through the *other* entry point with a
  test-local reconstruction of the declaration, on 7 of the 11 carrier-authoring poses. It was green
  on `main` for the right values but for a fragile reason: its reconstruction happened to match the
  pipeline's derivation (measured `0.000000`), the 4 unpinned poses (`chinup_standard`,
  `pullup_neutral`, `pullup_wide`, `scapular_pullup_deadhang`) were never compared at all, and the
  same comparison reads `10.652504 … 22.762941` units the moment the declaration is not delivered —
  i.e. the guard could not distinguish "the carrier is equivalent" from "this leg received the new
  input". The rewritten suite removes the second derivation and asserts the shared Frame Context.
- **Recorded, NOT fixed.**
  - The renderer entry point's parameters remain optional, so a caller that hands a **declaration-free
    freshly built** pose still finalizes against the empty model. R8/R11 bar carrying the declaration
    on the carrier, so the remaining guard is the source-level call-site test; making the argument
    mandatory (the audit's option β) is still an open architectural decision, deliberately not taken
    here.
  - Re-finalizing an already-produced frame is NOT byte-idempotent for reasons unrelated to the Frame
    Context (measured identically on both trees: `arm_circles_hold` 268.56 at `TOE_F`, `birddog_hold`
    6.27 at `HEAD_POS`). B-5 guarantees the Frame Context survives re-entry, not that re-entry
    reproduces the frame. New observation, recorded for its own pass.
  - `*_KNEE` / `*_ELBOW` support kinds still have no consumer (unchanged; B-3/B-4 residual).
  - **The carrier aliases the authoring node** (new observation, NOT fixed): the authoring helpers
    record `JointRotation(handNode.localRotation.axis, handNode.localRotation.angle)`
    (`BasePose.kt:171/193`) and `JointRotation.axis` is a stored reference, so the carrier's axis IS
    the node's `Vector3` — measured `axisSameObject = true` for every migrated extremity in the whole
    registry. A later in-place `localRotation.set(...)` on that node silently rewrites the already
    recorded carrier, and carrier-vs-node equality on the *axis* is guaranteed by aliasing rather than
    by value. The angle is a `Float` (copied), so the equivalence guard above still compares values
    where it matters, and no production pose currently rewrites an articulated node after recording
    it. Out of B-5 scope (Branch-C §11 mixed-mode authoring, §1.1 carrier hygiene); recorded for its
    own pass.

### TODO — P1 (next pass, in priority order)

1. H1 complement + M15 — WallSlides wall prop geometry/tuning + forearm contact plane.
2. H2 complement — migrate LatStretchPose (M11) and CatCowPose (M12) onto `bakeIkLimb`/gaze
   helpers for full carrier coverage; declare the support model (`metadata.support`) for the stretch
   family (M9) and the core/hip poses (M10) and the upper/dynamic poses (M8).
3. M1/M2/M3/M4/M6/M7 — pose-specific biomechanical-fidelity bugs (step contact, side-plank contact
   side — **the declaration side resolved by B-4; the residual is the pose's own inline/floor debt** —
   cobra/superman lumbar extension, kettlebell hinge inversion, burpee foot-translation).
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
