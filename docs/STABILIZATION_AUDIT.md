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
- **Recorded, NOT fixed — B-8b (NOW FIXED; see the `DONE — B-8b` block below).** `thoracic_extension_reps`
  is **not** a B-8 victim (its trunk frame is
  already authoritative — identity and frame-invariant). Its residual (ELBOW_A 29.93 / HAND_A 17.91) is
  a different defect: the pose derives both arm targets from `neck!!.worldPosition` while the neck's
  local offsets are written by the engine (`resolveHeadTarget`, Phase 7), so the first build realizes
  against a target it never sees again (declared-target delta 17.87 units). It was pinned by attribution in
  `ColdFrameLimbRealizationTest` so it could not be masked or mis-attributed while open; that pin is
  **removed** by the B-8b fix (the pose now projects the head base from its own authored gaze, chest
  frame and `def.neckLength`), and the frame-consistency assertion it was excluded from now covers it.
- **Still open (P11 backlog).** §12.7 flag lifecycle — **its verification half is now CLOSED** (see the
  `DONE — §12.7 flag lifecycle …` block below: the lifecycle/ownership behaviour is proven on the
  production path with counterfactual RED evidence); the remaining *configuration-ownership* question
  (R14 creator-owned knob vs the landed single declared surface) is recorded OPEN with options in plan
  §12.7; and — recorded so the label is unambiguous — the
  **P11 branch's own B-6** (`docs/AUDIT_P11_WHOLE_SYSTEM.md` §2: `SkeletonPipeline.resetHistory()`
  clears the dynamics chain but not the smoothing history; class NEEDS ARCHITECTURAL DECISION + DEAD
  API — zero production callers — and deliberately pinned by
  `arch/InterFrameSmoothingTest.resetHistoryKeepsPreP5Semantics`), which this tracker does not carry
  and this change does not touch.
  (T-7 landed as PR #230; B-2 + B-3, B-4, B-5 and B-6 — this tracker's B-6, the `EnvironmentPenetrationTest`
  vacuity — landed; see the blocks below.)

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

### DONE — B-6 the support-contact surface invariant is evaluated for real (P11, test-only)

PR #234 (`fix/b6-support-contact-surface-invariant`). The active tracker's B-6 — the
`EnvironmentPenetrationTest` **vacuity** — is the validation-path half of
the P11 whole-system audit's §4 test findings (T-1 "no test asserts a declared support contact actually
lands on its support surface", T-2 the skip, T-6 the private side-map copy). **Test-only change: no
production file is touched** (whole-corpus dump md5 identical — see below). Branch
`fix/b6-support-contact-surface-invariant` off `origin/main` `6e96275`.

- **Root cause — the file asserted nothing about most of what it claimed.** Measured by running the
  pre-fix file (verbatim logic, instrumented with coverage counters) on the unmodified baseline:
  | measurement | value |
  |---|---|
  | `evaluatedJointObservations` | **790** |
  | `posesActuallyEvaluated` | **18** of its own 25 hand-picked `variants` (the corpus is **51** classes) |
  | `variantsSkippedForEmptyDeclaration` (`if (contacts.isEmpty()) continue`) | **7** — `GluteBridgePose`, `BirdDogPose`, `CatCowPose`, `DeadBugPose`, `SupermanPose`, `LegRaisePose`, `HipCarsPose` |
  | `declaredContactsResolvedToEmptyList` (private `supportJoints()`'s `else -> emptyList()`) | **[`LEFT_FOREARM`, `RIGHT_FOREARM`]** — 20 declared-contact joint-observations compared against nothing |
  | `failuresReported` | **0** (green, one-sided, with no count of what was evaluated) |

  Because a pose that silently LOSES its declaration (the B-2 defect class) could only shrink what the
  file asserted, and because the file carried a fourth copy of the `SupportPoint ↔ Joint` mapping B-4
  had established authority for, the invariant could not fail for the defects it was written for.
- **…and the vacuity hid a real production violation.** With the canonical mapping
  (`SupportMath.jointsFor`), the engine's own surface rule (ONE plane per contact, from that contact's
  canonical centroid — `SkeletonPoseFinalizer.supportPlaneNormalFor`) and the whole corpus, the
  unmodified baseline reports exactly three pose/contact pairs below their declared surface:

  | pose | declared contact | worst penetration | offending joint |
  |---|---|---|---|
  | `StaticForearmPlankPose` | `LEFT_FOREARM` | **−44.752396** | `ELBOW_A` |
  | `StaticForearmPlankPose` | `RIGHT_FOREARM` | **−44.752396** | `ELBOW_P` |
  | `IsometricSidePlankPose` | `RIGHT_FOREARM` | **−37.863190** | `ELBOW_P` |

  Both poses declare the mat as their support and author their support elbow *below their own floor* —
  the residual the B-3 record lists as the poses' own §7 debt and the P11 pose inventory lists as M2 —
  invisible to the pre-fix file precisely because `*_FOREARM` resolved to ∅. Every other
  declared-contact joint of every other production pose sits inside the engine's unchanged 2-unit band.
- **A false-positive class the same rule change removes (measured).** Under the replaced per-joint
  surface rule `UnderhandChinUpPose`'s hand joints read as penetrating by up to **−5.109** — a grip on a
  bar being compared against the floor; under the production per-contact rule they are not violations at
  all (pinned by `aBarGripIsNotReportedAsPenetratingTheFloor`). The same rule also split single
  contacts across unrelated surfaces: measured `HangPose` `LEFT_HAND`, `HAND_A` against the bar top
  (`500.000`) while `PALM_A`/`KNUCKLES_A`/`FINGERTIPS_A` of that same declaration were compared against
  the ground (`0.000`).
- **Fix (test-only).** `EnvironmentPenetrationTest` now: enumerates **every** concrete production pose
  class and pins the **26** non-declaring classes as an exact census, so a pose that drops its
  declaration becomes a new member and fails (sensitivity control:
  `theDeclarationCensusDetectsASilentlyDroppedDeclaration`); resolves every declared contact through the
  ONE canonical map and asserts a non-empty joint family
  (`everyDeclaredSupportContactResolvesThroughTheOneCanonicalMap`); resolves the surface **once per
  contact** by the engine's own rule and asserts every joint of a contact is judged against that one
  surface (`everyJointsOfAContactIsComparedAgainstThatContactsOwnSurface`); reconciles the observation
  count against the count the declarations imply, so a silent ∅/skip cannot cost nothing
  (`everyDeclaredContactJointIsActuallyEvaluated`); applies the unchanged 2-unit band to every
  observation (`noDeclaredSupportContactPenetratesItsSupportSurface`); samples both frame conditions — a
  genuinely cold first frame (fresh pose, fresh pipeline) and an advancing-frame frame — captures every
  frame BY VALUE (`copyFrom`; the pipeline publishes the Finalizer's reused buffer) and refuses to treat
  one reused buffer as five observations (`sampledFramesAreDistinctObservationsNotTheReusedOutputBuffer`);
  and carries a controlled perturbation proving the check is live
  (`aContactSunkBelowItsDeclaredSurfaceIsReported`: a hand 60 units under the floor is reported at −60.000).
- **Measured.** Focused `EnvironmentPenetrationTest` **9 tests / 0F / 0E / 0S** (`--rerun-tasks`). RED on
  the untouched baseline: the same file with the attribution block emptied fails
  `noDeclaredSupportContactPenetratesItsSupportSurface` listing the three pairs above; with the
  attribution as shipped the same file is green on the untouched baseline **and** on this branch
  (the pins are the baseline's own numbers, to 1e-3: |−44.752396 − (−44.752)| = 3.96e-4). The pre-fix
  file, run on the same baseline, is green (`failuresReported = 0`) while the invariant it names is
  violated — the false-green this change replaces.
- **Corpus validation.** 51 production pose classes × 5 progress × 2 frame conditions × every canonical
  joint of every declared contact = **2,170 observations** (equal to the declaration-implied count; the
  pre-fix file evaluated 790). Full suite: `origin/main` `6e96275` **111 classes / 496 tests / 0F / 0E /
  0S** → this branch **111 / 504 / 0F / 0E / 0S** (one vacuous test replaced by nine real ones). Release:
  `:app:compileReleaseKotlin --rerun-tasks` green, `:app:assembleRelease -x lintVitalRelease` green;
  `:app:lintVitalRelease` fails **identically on both trees** (pre-existing `themes.xml` `ResourceCycle`
  + `ExpiredTargetSdkVersion`) — not a regression of this branch.
- **Production geometry impact: NONE.** Whole-corpus dump (51 poses × 5 progress × cold/warm, every
  declared-contact joint with its y, resolved surface and delta) is byte-identical between pristine
  `6e96275` and this branch: md5 `9d516fb8fb06b12e6756c90b00bcbb78` on both.
- **Residual — recorded, NOT fixed (pose geometry, not B-6).** The two plank forearms' penetration is
  attributed in `EnvironmentPenetrationTest.attributedDebt` with its exact measured magnitude and two
  guards: any NEW violation fails the suite, and the pin must be **deleted** when the pose is fixed (a
  stale pin fails). It is not a widened threshold — the 2-unit band is unchanged and applies everywhere.
  Fixing it re-authors the plank arm chain (measured on `StaticForearmPlankPose` at progress 0.5:
  shoulder `36.48`, elbow `−35.81`, planted hand `15.00` with the arm unclamped; the pose's own KDoc
  records the "long upper arm vs. the low braced-shoulder height" debt), i.e. a visible pose-geometry
  change belonging to the M2/§7 item with its own review. Deliberately not silently closed.
  **(Fixed by B-7 below: the geometry was re-authored, `attributedDebt` was deleted and the invariant
  is now a plain `violations.isEmpty()`.)**
- **Deliberately NOT asserted (measured, so it is not re-derived as a missing assertion).** An absolute
  rest *height* ("no float") is not expressible from the architecture: no engine channel declares a
  contact's rest height — it is authored per pose against the definition's contact-radius convention
  (`FootDefinition.ankleHeight = 15`, `PushUpPlank.BASE_KNEE_HEIGHT = 15`), which is why a planted
  push-up toe legitimately reads `y = 25.000` and a planted knee `y = 15.000`. In-plane coplanarity of a
  contact's joints is likewise not a contract: 18 pose/contact pairs measure off-plane within the band
  (spread 2.1–59.8) for modelled reasons — jump-squat toe-off **19.76**, pull-up hand rotation about the
  bar **2.3–20.7**, `PikePushUpPose`'s planted toe **24.75**.

### DONE — B-7 the planted forearm is one physical chain on the mat (P11; production geometry)

Branch `fix/plank-forearm-support-geometry` off `origin/main` `e075c6e` (the B-6 merge). **Production
geometry change: `BasePlankPose`, `StaticForearmPlankPose`, `IsometricSidePlankPose`** — plus the two
B-6 / `NewEnginePosesTest` assertions those poses' anchors invalidate and the new
`PlankForearmSupportGeometryTest` gate. Successor to B-4 (the canonical `SupportPoint ↔ Joint` mapping)
and B-6 (the surface invariant), and the close-out of the pose-geometry residual both of those records
explicitly left open ("the poses' own §7 debt", P11 §M2).

- **Root cause — the trunk was authored independently of the plant the arm must reach.** `*_FOREARM`
  resolves to the contact's own `elbow → hand` pair (`SupportMath.jointsFor`: `LEFT_FOREARM →
  [ELBOW_A, HAND_A]`, `RIGHT_FOREARM → [ELBOW_P, HAND_P]`, B-4), and the engine realises the elbow from
  the arm's IK solve — it is not a pose-authored position. Measured on pristine `e075c6e` through the
  production pipeline (`StaticForearmPlankPose` @ p=0.5): shoulder `36.48`, elbow `−35.81`, planted hand
  `15.00`; the side plank's support shoulder `15.10`, elbow `−37.86`, hand `15.00`. The authored hand
  plant sat only `75.90` units ahead of the shoulder (a shoulder→hand separation of `77.26`) while the
  arm's own segments are `80 + 66 = 146`: the solve therefore had to bulge the elbow `60.4` units out of
  the shoulder→hand chord, and the authored pole `(-0.5, -1, -0.3)` aimed that bulge at the mat — the
  elbow's height decomposes as `36.48 (shoulder) − 14.2 (along-chord) − 58.0 (pole side) = −35.8`, i.e.
  the pose planted a hand and drove its elbow through the floor.
  Both plank BPS specs state the geometry this violates: "the upper arm is vertical from the elbow
  (under the shoulder) to the shoulder", "Elbows flexed ~90°, directly under the shoulders; the upper
  arms vertical", "the supporting forearm lies flat on the floor" (§6/§11, Plank (Forearm) / Plank
  (Side)) and one straight `shoulder → hip → ankle` line (§3).
- **The quantified constraint (why the trunk had to move).** With the elbow on the mat's planted-forearm
  plane (`BasePlankPose.contactY = 15`, the height the hand is already planted at) and the hand level
  with it, the upper arm's `80` units place the shoulder at `15 + 80·cos(lean)` — `95.00` for a vertical
  pillar, which is what the BPS's braced hold asks for. The pre-fix authoring put the shoulder at
  `15.10 … 57.76` over the rep (`37.2 … 79.9` units too low), and the pose's whole authored pitch range
  (`−1.57 → −1.38`, i.e. `120·sin 22.4° = 45.7` units of shoulder rise) cannot close that deficit: **no
  pole and no pitch inside the pose's declared range yields a planted forearm at that trunk.** The
  correction is therefore *not* a pole edit — it is to derive the trunk from the plant.
- **Exact authoring correction (the geometry model before → after).** Before: author the hip height and
  the trunk pitch, then throw a hand target (`shoulder.x + forearmLength·1.15`) at the arm and hope.
  After, in `BasePlankPose` (one shared, allocation-free path; no new solver):
  1. **The plant is authored from its contact** (`planPlantedForearm`): the elbow at
     `(forearmPlantX, contactY, ±shoulderWidth)` — directly under its shoulder — and the hand one
     forearm length ahead of it, level with it (the flat forearm keeps the pose's mild A-frame tuck,
     `handZ = 0.6·shoulderWidth`, so the horizontal run is `√(66² − 18.4²) = 63.38`).
  2. **The shoulder is the pillar's top**: `elbow + 80·(−sin(lean), cos(lean), 0)` — vertical at the
     braced hold, leaning back over its elbow by the pose's authored settled lean.
  3. **The pole is derived, not hand-tuned**: the perpendicular offset of the elbow's mat contact from
     the shoulder→hand chord, i.e. the chain's own statement of where the elbow bends.
  4. **The trunk hangs off the propped shoulder**: the chest is placed at the shoulder's `(x, y)`, the
     pelvis's height is authored (settled → the braced line), and the trunk's inclination
     (`proppedTrunkPitch`) and the hip's world X (`proppedHipX`) are derived from those two heights —
     the trunk is rigid, so the propped shoulder and the authored hip height pin both exactly (no plant
     drift, no second solve). `bracedBodyY` derives the braced hip height from the one straight
     `shoulder → hip → ankle` line the BPS requires, so the braced hold is a perfect plank by
     construction.
  5. **Legs and feet untouched** (the same toe plant, same targets): the planted ankle/toe/heel
     positions are unchanged to `< 5e-4` units.
  Authored per-pose values, each recorded at its constant with the reach record that fixes it:
  `StaticForearmPlankPose.SETTLED_BODY_Y = 30` with a `12°` settled pillar lean (the planted leg
  measures `188.1` of its `210`-unit length, `17.7` inside the `0.98` band), and
  `IsometricSidePlankPose.SETTLED_BODY_Y = 21` with a `20°` lean (its side-rolled down-side hip sits
  `hipWidth` off the centre line, so the settle spends most of the leg's slack first: the hip→ankle span
  is `197.6`, `8.2` units inside the band).
- **Measured (published frames, both trees, same pipeline).**

  | pose @ p | shoulder Y | elbow Y | hand Y | ‖elbow − hand‖ | elbow interior | IK clamp |
  |---|---|---|---|---|---|---|
  | `StaticForearmPlankPose` p=0.5 | `36.48` → `94.56` | `−35.81` → `+14.69` | `15.00` → `15.00` | `50.81` → `0.31` | `63.05°` → `92.73°` | `0` → `0` |
  | `StaticForearmPlankPose` p=1 (braced) | `57.76` → `95.00` | `−18.68` → `+15.00` | `15.00` | `33.68` → `0.00` | `74.46°` → `90.00°` | `0` → `0` |
  | `IsometricSidePlankPose` p=1 (braced) | `80.71` → `95.00` | `+7.89` → `+15.00` | `15.00` | `22.09` → `0.00` | `106.73°` → `90.00°` | `0` → `0` |
  | `IsometricSidePlankPose` p=0 | `15.10` → `90.18` | `−37.86` → `+15.00` | `15.00` | `52.86` → `0.00` | `83.05°` → `104.20°` | `0` → `0` |

  At the braced hold the support elbow is now exactly `0.000` units horizontally off its shoulder — the
  BPS §6/§11 vertical pillar with a `90.00°` elbow — and the two forearm contacts are level to `0.000`
  (side plank) / `0.383` (flat plank, the breath-driven COM drift mid-rep) instead of `52.9`/`59.8`
  apart. `maxIkClampAmount = 0.0000`, `boneLengthsVerified = true`, `straightIntentDropped = false` at
  every sampled frame on both trees: the correction introduces **no clamp** (the pre-fix chain was not
  clamped either — that is *why* it looked "solved").
- **The three B-6 attribution pins are gone.** `EnvironmentPenetrationTest.attributedDebt` /
  `debtTolerance` are deleted and the invariant is a plain `violations.isEmpty()`; the 2-unit band is
  unchanged. RED proof on the untouched baseline with the pins removed (the shipped file): the same test
  fails on `e075c6e` listing exactly `StaticForearmPlankPose LEFT_FOREARM −44.752396`,
  `RIGHT_FOREARM −44.752396`, `IsometricSidePlankPose RIGHT_FOREARM −37.863190` — and green on this
  branch. A new forearm penetration is now a hard failure with nothing to absorb it.
- **Corpus impact (measured, not assumed).** 51 production pose classes × 5 progress × every joint:
  **only the two corrected poses change** — 43 distinct joints for the flat plank, 38 for the side plank,
  at every sampled progress; **394 of 405** journaled observations are inside those two poses and the
  other **49 classes are byte-identical** (digest `−340803699455685852`, computed identically on pristine
  `e075c6e` and on this branch with the two corrected poses excluded; pinned in the new test). Maximum
  joint delta over the two poses: `97.67` (`HEAD_POS`, flat plank p=0 — the body is now propped at the
  pillar). Planted hand plants moved: flat plank `(195.90, 15, ∓27.60) → (183.38, 15, ∓27.60)`,
  side plank `(205.80, 15, 0) → (167.33, 15, 0)`; the feet/ankles/toes moved `< 5e-4`.
- **Regression coverage (fresh runs).** New `PlankForearmSupportGeometryTest` (**9 tests**): the contact
  invariant with a flatness band (`ELBOW` and `HAND` level, both inside the engine's unchanged 2-unit
  penetration band, both planks, both frame conditions, every sampled progress); the canonical +
  published-carrier resolution (non-empty family, one limb family, the anchor first, the declaration
  reaching `SkeletonPose.supportedPoints`); frame-by-value integrity (distinct objects, immune to later
  frames); the reach/clamp/segment-length/angular-band record from the production carrier
  (`maxIkClampAmount`, `boneLengthsVerified`, `limbTargets`); the BPS braced-hold shape (elbow directly
  under the shoulder, `90°`); the declared limb targets; declaration and mapping preservation; and the
  49-pose byte-identity digest. RED on pristine `e075c6e` (with the two B-7 pose constants inlined):
  `forearmContactIsPlantedFlatOnItsDeclaredSurface` (every sample lists `ELBOW.y = −44.752 … −18.681`
  vs `HAND.y = 15.000`) and `bracedHoldRealizesTheVerticalPillarAndElbowUnderShoulder` (the braced
  offset measures `23.60` instead of `0.00`); the other seven pass on both trees (the declarations and
  the mapping were already right, and the pre-fix geometry was not clamped). `NewEnginePosesTest`'s two
  plank assertions — which encoded the old `pelvis 15 → 35` literals — now assert the derived anchors
  instead: the settled height from the pose's own constant and the braced height derived from the
  published frame's `shoulder → hip → ankle` line.
- **Motion contracts preserved (measured).** The repaired hips' travel is `37.15` (flat plank,
  `30 → 67.15`) and `44.01` (side plank, `21 → 65.01`) against `CoreMotionTest`'s `30`/`40` floors —
  unchanged, not weakened. Real-playback continuity at 60 fps: the largest per-frame joint displacement
  is `0.1729` (flat plank, was `0.2602`) and `0.3409` (side plank, was `0.3630`) versus the validator's
  `15`-unit `POSITION_DISCONTINUITY` threshold; an untouched control (`StandardPushUpPose`) measures
  `1.1234` identically on both trees. (A synthetic probe that feeds the validator *quarter-rep* jumps
  at `1/60 s` flags fewer discontinuities than the baseline does — `11` vs `40` for the side plank,
  `0` vs `1` for the flat plank — i.e. no discontinuity was introduced.)
- **Verification.** Full suite `--rerun-tasks`: `origin/main` `e075c6e` **111 classes / 504 tests /
  0F / 0E / 0S** → this branch **112 / 513 / 0F / 0E / 0S** (+1 class / +9 tests, the new gate).
  Focused set green: `EnvironmentPenetrationTest` 9, `PlankForearmSupportGeometryTest` 9,
  `NewEnginePosesTest` 12, `KneePushUpPlankGeometryTest` 7, `ColdFrameLimbRealizationTest` 7,
  `PushUpPlankTest` 4, `CoreMotionTest` 1. Release: `:app:compileReleaseKotlin --rerun-tasks`,
  `:app:compileReleaseJavaWithJavac`, `:app:assembleDebug` and `:app:assembleRelease -x lintVitalRelease`
  all green; `:app:lintVitalRelease` fails **identically on both trees** (pre-existing `themes.xml`
  `ResourceCycle` + `ExpiredTargetSdkVersion`).
- **Deliberately NOT touched (the mission's out-of-scope residuals, still open).** B-5's residual, the
  P11-branch `SkeletonPipeline.resetHistory()` B-6 decision, the §12.7 flag lifecycle
  (**verification closed 2026-09-12 — see the block below; its configuration-ownership half is still
  open**), the
  `*_KNEE` / `*_ELBOW` support-consumer architecture, M9/M10 (missing
  declarations), the renderer, and every validator threshold (the 2-unit band included). (**B-8b /
  `ThoracicExtensionPose` was in this list when B-7 landed; it is now fixed — see the `DONE — B-8b`
  block below.**)

### DONE — B-8b the thoracic-extension arm target is pose-owned (PR #236; P11; production geometry)

The one family the B-8 task matrix lists whose residual was **not** a trunk-frame defect. Pose-side
fix only: no engine file, no phase order, no carrier, no API, and **no head/neck ownership change** —
`SkeletonPoseFinalizer.resolveHeadTarget` (Phase 7) remains the sole writer of the neck's local
offsets.

- **Defect.** `ThoracicExtensionPose` derived BOTH arm targets from the engine-owned neck node
  (`val neckW = neck!!.worldPosition`, `ThoracicExtensionPose.kt:90` pre-fix;
  `target* = neckW + (-12, +6, ±0.55·shoulderWidth)`). The neck's local offsets are written by the
  engine in Phase 7 — at the END of a frame — so a build can only ever read the PREVIOUS frame's
  neck; on a builder's first build the skeleton template still carries a zero neck offset, so the
  COLD frame anchored its hands to the **chest** (the neck sat *at* the chest) and realized a target
  it never sees again. Frames ≥ 1 only looked right because the reused node tree carried the
  engine's previous write — correctness by cross-build buffer reuse, not by the frame. Same class as
  B-8, different mechanism (B-8 was the trunk frame; this is the target source).
- **Measured (`origin/main` @ `691c6a7`, cold first frame vs the same instance settled, p=0).**
  Declared arm target `(-12.000000, 253.000000, ∓25.300000)` vs
  `(-14.144614, 270.871796, ∓25.300000)` = **17.8718**; published `ELBOW_A`/`HAND_A` `29.9277` /
  `17.9135`; published `maxIkClampAmount` **15.4668** on the cold frame against **5.5162** in the
  rep. Cold-vs-settled arm-chain delta per progress: `29.9277` (p=0), `30.8876`, `31.5530`,
  `31.9853`, `32.2308` (p=1).
- **Why `neck.worldPosition` was the wrong authoritative source.** The arm target is Phase-0 pose
  intent and must be authored from pose-owned geometry; the neck node is a Phase-7 engine product
  whose position is rewritten *after* the limb target is read, so no build can see the value it
  authored against. The dependency was not an explicit architectural contract — the neck was simply a
  convenient positional reference, and it made `limbTargets` frame-dependent.
- **Fix (smallest expression of already-existing pose geometry).** The engine places the neck along
  the gaze the pose declares (`buildGaze`, with the pose's own `headDir` and the definition's
  `def.neckLength`) inside the chest frame the pose declares, so that point is expressible from
  authored intent alone: `headDir · def.neckLength`, rotated to world by the declared chest frame via
  the family's existing helper `BaseThoracicPose.chestLocalToWorld` (`BaseThoracicPose.kt:111` — the
  helper `QuadrupedThoracicRotationsPose` and `DynamicWorldsGreatestStretchPose` already use for a
  thorax-following reach). The authored `(-12, +6, ±0.55·shoulderWidth)` hand offset, the poles, the
  trunk frame and `bakeIkLimb` as the authoring path are all untouched; nothing was added to the
  engine, and no new carrier or conversion abstraction was introduced.
- **After.** Declared arm-target delta cold vs settled **0.0000** at every progress; cold-vs-settled
  arm-chain delta **0.0000**; the cold frame's `maxIkClampAmount` equals the rep's
  (`5.5162 / 4.5738 / 3.7950 / 3.1927 / 2.7763` per progress — no new clamp, and the frame-dependent
  `15.4668` artifact is gone); `boneLengthsVerified = true`; the authored base sits exactly on the
  neck base the engine publishes (`NECK_END`, measured `0.0000` at every progress — i.e. the target
  is anchored to the head the engine actually produces, not to an arbitrary chest offset).
- **Corpus impact (measured, not assumed).** 49 production pose classes (`PoseRegistry`) × 5
  progress × {cold first frame, settled rep} × every joint plus the `limbTargets` carrier, at full
  `%.6f`: **490 rows compared → 5 changed, all five `thoracic_extension_reps` COLD frames, 12 joints
  each (both arm chains), max joint delta `29.9277` (p=0) … `32.2308` (p=1)**; the other **48 classes
  byte-identical at 1e-6**, and `thoracic_extension_reps` byte-identical on every settled frame.
  `PlankForearmSupportGeometryTest.UNAFFECTED_CORPUS_DIGEST` was re-baselined once
  (`−340803699455685852 → 8354470872339933400`) with the reason recorded at the constant — that guard
  was observed RED on this change first, which is its own mutation check, and its 49-class corpus is
  unchanged so any further drift still fails there. **No other golden changed**
  (`arch/RuntimeArchitectureBaselineTest` green and untouched — its fixtures pin the bare-pose path).
- **Regression (fresh runs).** New `ThoracicExtensionArmTargetTest` (**6 tests**), RED **6/6** on
  `origin/main` @ `691c6a7` with the numbers above quoted in the failures: declared-target
  frame-invariance over the published `limbTargets` carrier (17.8718); cold == frame 1 == settled for
  the whole published arm chain (29.9277); the authored base == the engine's published `NECK_END`
  (2.1446 off on the cold frame); a non-vacuity guard that recomputes the **pre-fix** expression from
  the pose's own build and requires the authored target to differ by > 5u (pre-fix: 0.0000, i.e. the
  two were the same value); a source-scan guard that the pose may not read the neck node's world
  position (flagged `ThoracicExtensionPose.kt:90 val neckW = neck!!.worldPosition`); and the
  published carriers (`limbTargets` with its declared bone lengths + constraint, `boneLengthsVerified`,
  and no clamp above the recorded pre-fix steady-state per progress). Every assertion reads primitives
  or by-value copies — no pipeline buffer or node is retained, no cold-only or warm-only sampling.
- **The attribution pin is removed, not weakened.** `ColdFrameLimbRealizationTest`'s B-8b pin test and
  its `settledBuildTargets` helper are deleted, and `ThoracicExtensionPose` joins the identity-trunk
  control (`trunkFramesTheEngineDerivesAsIdentityAreNotRewritten`) it was deliberately held out of
  while B-8b was open. Measured accounting: on the B-8b baseline that file is RED **1 failed / 5
  passed** — exactly that control, whose ThoracicExtension arm pair measures the `29.9277` delta —
  and GREEN **6/6** after this change. No assertion was softened to accommodate the old value.
- **Verification.** Full suite `--rerun-tasks`, results dir purged: `origin/main` @ `691c6a7`
  **112 classes / 513 tests / 0F / 0E / 0S** → this branch **113 / 518 / 0F / 0E / 0S** (+1 class /
  +5 tests = the new 6-test class minus the removed pin; no collateral anywhere). Release:
  `:app:compileReleaseKotlin --rerun-tasks`, `:app:assembleDebug` and
  `:app:assembleRelease -x lintVitalRelease` all green (fresh APKs); `:app:lintVitalRelease` fails
  **identically on both trees** — the pre-existing `themes.xml` `ResourceCycle` +
  `ExpiredTargetSdkVersion`, whose error lines `diff` empty between trees.
- **Residual (recorded, deliberately NOT fixed — a different question from the target source).** This
  pose authors its hands INSIDE the arm's minimum-reach annulus: `ArmConstraint.minimumFlexionAngle =
  30°` fixes the closest reachable end-effector at `40.1344` from the shoulder while the authored
  target sits `34.6182` away at p=0 (`24.6670` on the pre-fix cold frame), so the solver honestly
  reports `maxIkClampAmount` `5.5162` (p=0) … `2.7763` (p=1) and places the hand on the target's own
  ray at `40.1344`. That is a pre-existing authoring/reachability question about where this rep puts
  its hands, not a target-source defect; this change neither introduces nor hides it (the cold stamp
  now equals the rep's instead of being larger) and pins it as an upper bound in the new test.
- **Not touched.** B-8 (its cold-frame limb-realization contract is verified unchanged and stays
  green), the Finalizer, phase ordering, head/neck semantics, and every other pose.

### DONE — §12.7 flag lifecycle / single-active-solver ownership verification (P12; the last P11-backlog item naming P12, 2026-09-12)

Split by what was actually missing, because on `main` @ `3f6733d` the enforcement half is already live
and green: this change is verification + record, not a new mechanism.

- **Diagnosis (measured).** §12.7's gating contract holds as written: the three registered authoring
  bakes run their registration effects (Limb Target + Contact Declaration) and the F2 build-window
  bookkeeping in BOTH configurations while their realization block (solve + stamp folds + limb node
  writes) sits behind the configuration gate; `IkStage.apply` is the sole realization site under the
  deployed state 3; and `SkeletonPipeline.runStages` rejects a frame on per-implementation EXECUTION
  evidence (window count + per-limb duplicate mask), never on output comparison. What was missing:
  (i) no test induced the §12.7a violation mode itself — an authoring realization co-executing with
  the engine stage inside ONE build cycle. The existing counterfactuals only doubled ONE
  implementation (two `bakeIkLimb` calls, a duplicated Limb Target, or a second stage window), and
  the two increment sites are flag-mutually-exclusive, so nothing in the suite exercised the shape
  the gate exists to prevent; (ii) the straight-intent flag's current-build truthfulness across
  consecutive builds of a REUSED carrier was asserted nowhere; (iii) three records still described
  state 2 as the deployed state, one of them a test comment contradicting the shipped default.
- **What landed (test-only + KDoc).** New `arch/SingleActiveSolverLifecycleTest` (7 tests): declared
  limbs realized exactly once per configuration (realized set == declared set in BOTH); the
  double-realization trap driven through the registered production path with its output-equivalence
  premise (the two single-realization frames are raw-bit identical, so a geometry-only test cannot
  see the violation and the rejection is provably execution evidence); registration preservation at
  FIELD level under the active stage (target, pole, straight intent, declared lengths, constraint,
  plus the Contact Declaration) with the gated bake proven not to write limb node geometry, and an
  authoring-configuration anti-vacuity control for exactly those node locals; and four
  flag-lifecycle tests (fresh and reused builders, multi-limb OR merge, the re-arm that makes a
  later successful build publish `false`, and a bent-only rebuild that cannot inherit a previous
  drop). Records corrected to the deployed state: `SingleActiveSolverEnforcementTest` (two comments
  and one local that labelled the authoring configuration "deployed") and
  `RuntimeSolverOwnershipAuditTest`'s class KDoc; `IkStage.kt`'s flag KDoc no longer claims the
  declaration "lives beside its sole reader rather than in a global flag object" — it IS a
  file-level `var`, and the KDoc now states that, states why R14's creator-owned knob needs its own
  §12.4 proposal, and points at the suite that proves the lifecycle.
- **Counterfactual RED gates (each executed against the defective shape before green was accepted).**
  (1) §12.7a realization gate removed from the three registered bakes → the new suite **7/7 FAILS**
  (the enforcement reports `windows executed this frame = 2`).
  (2) The pipeline's enforcement disabled (`if (BuildConfig.DEBUG && false)`) → **exactly** the
  double-realization trap FAILS: "no violation raised".
  (3) The F2 re-arm gated off (the WP-F defect shape) →
  `droppedReadingIsReArmedByTheNextBuild` + `bentOnlyBuildDoesNotInheritAPreviousDrop` FAIL on the
  stale `true`.
  Each gate's file was restored byte-identically afterwards (`git diff` empty).
- **No production behaviour changed.** The only production edit is KDoc in `IkStage.kt`; no pose, no
  solver, no finalizer, no validator rule, no threshold, no golden, no RFC byte. Full forced suite and
  release compile re-run on the branch (see the PR record for the exact counts).
- **Still OPEN, recorded and deliberately NOT decided here — configuration ownership.** §12.7's first
  bullet requires the declaration to move to "an engine-supplied configuration input (constructor/
  definition-level knob supplied by the creator per R14)". The landed mechanism is the single declared
  file-level surface — one declaration, zero production writes, reads confined to the four
  realization-decision files, no environment/system-property channel — audited statically and at
  runtime, but NOT creator-owned/lifetime-scoped. Reaching R14 means adding a configuration channel
  into the authoring `build()` path (the bakes are called by pose code and receive no engine
  configuration), i.e. the intent/carrier class of change plan §12.4 requires be raised as a separate
  clarification proposal. Options recorded in `docs/IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md` §12.7 and
  in `IkStage.kt`'s flag KDoc: **(A)** raise the §12.4 proposal and implement the definition-level
  knob (largest diff: every registered authoring call site + the package-level bake signature);
  **(B)** accept the landed surface as the R14 substitute and close the item; **(C)** carry it as an
  open debt-ledger entry.
- **Same owner, second recorded observation (flagged, not changed).** On the no-rebuild re-produce
  path (`SkeletonRenderer.kt:55`, `SkeletonSnapshotRenderer.kt:83`) the engine stage re-realizes each
  frame while the stage's F2 block re-arms only `boneLengthsVerified`, so the straight-intent reading
  is OR-merged (`ValidationStampMerge.dropped`, strengthen-only) across those frames instead of being
  frame-local. Not reachable in the 39-entry §12.9 corpus (0 deltas), and not a P12 regression (the
  authoring configuration performs no realization at all on that path), so it is flagged rather than
  changed: making it frame-local in the engine configuration would break cross-configuration parity
  on exactly that path.

### DONE — M1 the Step-Up's planted foot stands on the step the pose declares (PR #238; production geometry)

Branch `fix/m1-stepup-geometry-support` off `main` `1da6458` (the §12.7 merge). **Production geometry
change: `StepUpPose` only** — plus the re-baselined B-7 corpus digest and the new
`M1StepUpGeometryTest` gate. This is the §3 P1 **M1** finding ("Lead/trail feet at Z=∓25.3 but step
prop spans only Z∈[−22,+22]; both feet overhang the step"), resolved against the current tree; the
finding's own measurement reproduced exactly as recorded.

- **Diagnosis (measured on `1da6458`, published frames, `produceFrame(pose, ctx)` — the
  metadata-derived entry point playback uses, 5 progress samples, cold and advancing pipelines).**
  The declared step is `StepProp(center (12, 18, 0), 44 × 36 × 44)` → top plane `y = 36`, footprint
  `X ∈ [−10, 34] × Z ∈ [−22, 22]`; both feet are declared as support contacts. The produced frames:
  the lead ankle is at `(12, 25, −25.3)` at the seam and `(12, 36, −25.3)` at the top — i.e. the foot
  the pose puts "on the step" is at the step's own **top plane** while lying **outside** the step's
  footprint in `Z` (and its toe at `X = 36.85` outside the tread's front edge at `34`). The engine's
  support rule (`supportPlaneNormalFor`: a contact's surface is a box/step/bench **top** only when the
  contact's canonical joint centroid is inside its footprint, otherwise the ground) therefore resolves
  **both** feet to the **ground** at every sampled frame — the declared step never supports anything —
  and the "ascent" is a **13.0-unit** pelvis rise (11 net + the 2-unit breath) against a **36-unit**
  step: a step-shaped prop with nothing standing on it — a leg raise beside a step, not a step-up.
- **Root cause — the support limb is placed from the pose's body constants, not from the step it
  declares.** `StepUpPose` derived the feet from `hipWidth * 1.15` (`Z = ∓25.3`, a body-relative
  stance), the raw top-surface height as if it were a **joint** height, and the tread's placement from
  an independent literal (`X = 12`), while the prop was declared from separate literals. Three
  authorings of one physical fact (where the foot stands, where the step is, how high a foot on it
  rides) that cannot agree by construction, and no engine derivation can reconcile them: the engine
  orients a declared extremity against its surface, it does not move a foot onto a prop.
- **The height half, quantified.** A foot standing on a surface rides its own contact radius above it:
  this pose's floor rest (`footRestY = 25`), the engine's own convention
  (`PushUpPlank.ANKLE_HEIGHT = BASE_ANKLE_HEIGHT + supportElevation`, `BASE_ANKLE_HEIGHT = 25`) and
  the measured relationship on the one other foot-on-a-prop production pose (`DeclinePushUpPose`: a
  `40`-unit box, foot contacts at `65.0`) all agree. Using `stepTop` as a joint height put the
  planted foot's contacts **0.0** above the step's top plane (burying it to the ankle) instead of
  `25.0`, and capped the ascent at a third of the step.
- **The naive fix is refuted by measurement (and was tried, locally).** Widening the run so its
  footprint covers the athlete's *floor* stance (`depth = 60` → `Z ∈ [−30, 30]`, the literal reading of
  the audit's `Z` measurement) with the pre-fix feet makes the **rest** pose land inside the widened
  footprint while it is on the floor, so the engine resolves its surface to the step's top and its
  contact joints sit `−11.0` below the surface they are declared to rest on: `EnvironmentPenetrationTest`
  goes **RED** with `StepUpPose LEFT_FOOT worst=-11.0 joints=[ANKLE_F, HEEL_F, TOE_F]` and the same for
  `RIGHT_FOOT` — a foot physically inside the step's solid volume. The support foot has to be **placed
  on the tread**; the trailing foot stays on the floor's side of the run.
- **Exact authoring correction (`StepUpPose`, one file; no engine, no solver, no carrier, no API).**
  The step's numbers are now one source used by BOTH the declared prop and the foot placement
  (`stepTop`, `stepTread`, `stepRun`, `leadFootX`, `trailFootX`, `treadCenterX`):
  1. **A foot standing on the step rides the same contact radius it rides on the floor**:
     `onStepY = stepTop + footRestY` (`36 + 25 = 61`), so the ascent buys the step's height and the
     planted foot is ON the tread instead of through it.
  2. **The planted foot is placed from the run**: `onStepZ = −(stepRun/2 − 3)` (`−19.0`), reached by a
     lateral placement tied to the same `leadUp` window as the rise, so the foot is over the tread by
     the time it is at the step's plane and back at the floor stance at the rep's endpoints (the
     `PING_PONG` seam is byte-identical to the pre-fix frames).
  3. **The tread is centred on the planted foot**, not on the body (`treadCenterX = leadFootX +
     0.42 · 17.5 = 19.35` — the default foot's mid-point offset), so the whole foot lands on the tread
     with margin at both ends rather than overhanging the front edge.
  4. **The trailing foot tracks the lead's height** (`trailUp` window `0.05 → 0.50` instead of the
     pre-fix `0.30 → 0.85`): it stays on the floor's side of the run (so its own resolved surface is
     the ground, never the step) and is lifted clear at the top. The lag is a **contract** consequence,
     not a taste call — both legs are ~`legSpan` long, so the two ankles have to stay inside this
     pose's own limb-asymmetry band; with the pre-fix lag and the corrected full-height ascent the gap
     opens to `32.4` units and both asymmetry assertions fail (measured `maxLegAsymmetry = 32.35`).
- **Measured (published frames, same pipeline, pre-fix → post-fix).**

  | frame | `LEFT_FOOT` ankle | its resolved surface | its offset above that surface | `RIGHT_FOOT` surface | pelvis |
  |---|---|---|---|---|---|
  | p=0 (seam) | `(12, 25, −25.3)` | ground `0` → ground `0` | `25.0` → `25.0` | ground → ground | `228.0` |
  | p=0.25 | `(12, 36, −25.3)` → `(12, 61, −19.0)` | `0` → **`36.0`** | `36.0` → **`25.0`** | ground → ground | `232.3` → **`239.8`** |
  | p=0.5 (top) | `(12, 36, −25.3)` → `(12, 61, −19.0)` | `0` → **`36.0`** | `36.0` → **`25.0`** | ground → ground | `241.0` → **`266.0`** |
  | p=0.75 | as p=0.25 | `0` → `36.0` | `36.0` → `25.0` | ground → ground | `232.3` → `239.8` |
  | p=1 | = p=0 (byte-identical, both trees) | | | | `228.0` |

  Pelvis rise `13.0` → **`38.0`** (`36` = the step's height + the 2-unit breath); all three
  `LEFT_FOOT` joints inside the tread footprint at the planted frames; `RIGHT_FOOT` never resolved to
  the step (it is the trailing foot, held on the floor's side) and lifted `36.0` clear of the floor at
  the top; `maxIkClampAmount = 0.0`, `boneLengthsVerified = true` at every sampled frame; the planted
  foot's XZ spread across the planted frames `≤ 1e-3` (placed = fixed).
- **Corpus impact (measured, not inferred).** A whole-corpus dump of **51 production pose classes ×
  5 progress samples × every joint XYZ** (`8415` rows, full float bits) on both trees differs in
  **exactly 99 rows — all of them `StepUpPose`**, at `p ∈ {0.25, 0.5, 0.75}` (33 joints each); the
  seam frames and the other **50 classes are byte-identical**. The M1 gate's own digest (the same
  recipe, `StepUpPose` excluded) is **equal on both trees** (`2746720065314572970`, pinned in the new
  test), and the B-7 digest — which includes every class — is re-baselined
  `8354470872339933400` → `−2908768886375429885` with that attribution recorded at the constant
  (observed RED on the pre-fix value first).
- **Regression coverage (fresh runs).** New `M1StepUpGeometryTest` (**9 tests**): the planted foot's
  resolved surface is the step at the planted frames and the ground at the seam; every joint of the
  declared `LEFT_FOOT` contact inside the tread footprint and above its top plane; the contact radius
  preserved when standing on the step (band = the engine's unchanged 2 units, checked against the
  same pose's floor rest — no absolute rest height is invented, per B-6's own disclaimer); the ascent
  buying the step's height; the trailing foot on the ground's side of the step, never penetrating its
  surface and lifted clear at the top; no sampled frame penetrating its resolved surface with honest
  carriers (`maxIkClampAmount`, `boneLengthsVerified`); published-by-value distinct snapshots carrying
  the declaration, with the placement movement actually exercised (the anti-vacuity guard that fails on
  a foot that never moves); the planted foot fixed while planted plus the closed `PING_PONG` seam; and
  the 50-pose byte-identity digest. **RED on the pre-fix tree: 5 of 9 fail**, quoting the numbers
  (`resolves to surface y=0.0000 instead of 36.0000`; `ANKLE_F=(12.0000, 36.0000, −25.3000) is OUTSIDE
  the tread footprint`; `ride 0.0000 above the step's top plane, but the same pose's floor-rest foot
  rides 25.0000`; `the pelvis rises 13.0000 while the declared step is 36.0000 high`;
  `ANKLE_F.z: [−25.3, −25.3, −25.3, −25.3, −25.3]`).
- **Counterfactual RED gates (each executed against the defective shape, then restored byte-identically
  and re-verified by `md5sum -c`).** (1) The whole pre-fix pose (stash round-trip) → **5/9 fail**.
  (2) Only the height correction reverted (`onStepY = stepTop`) → **3/9 fail** (ascent, containment
  above the top plane, contact radius). (3) Only the placement correction reverted
  (`leadZ = −floorStanceZ`) → **3/9 fail** (resolved surface, tread containment, the
  placement-exercised guard). The two halves are independently load-bearing, and neither can be
  removed without a specific, named failure.
- **Movement review (published frames, settled, the pose's own camera — the same numbers the SVG of
  the rep was drawn from).** Reviewed as a movement, not as test output:

  | p | pelvis Y | lead ankle | trailing ankle | lead leg span / extension | knee interior | knee over the foot? | trunk tilt from vertical |
  |---|---|---|---|---|---|---|---|
  | 0 (seam) | `228.0` | `(12, 25, −25.3)` | `(−12, 25, +25.3)` | `203.4 / 96.9%` | `151.1°` | yes | `0.0°` |
  | 0.25 | `265.0` | `(12, 61, −19.0)` | `(−12, 61, +25.3)` | `204.4 / 97.3%` | `153.4°` | yes | `5.7°` |
  | 0.5 (top) | `266.0` | `(12, 61, −19.0)` | `(−12, 61, +25.3)` | `205.4 / 97.8%` | `155.9°` | yes | `0.0°` |
  | 0.75 | `265.0` | `(12, 61, −19.0)` | `(−12, 61, +25.3)` | `204.4 / 97.3%` | `153.4°` | yes | `5.7°` |
  | 1 | `228.0` | identical to `p=0` | | `203.4 / 96.9%` | `151.1°` | yes | `0.0°` |

  Pre-fix, on the same measurement: the pelvis topped at `241.0`, the lead ankle never left
  `Z = −25.3` and the knee was **not** over the ankle–toe range at `p = 0.25/0.75` (`false`, knee
  interior `138.9°`); post-fix the knee tracks over the foot at every sampled frame (BPS §11/§13
  "knee tracks over the toes, no valgus" and "lead hip extends fully"). Both hips stay level
  (`Δ = 0.00`), both feet stay flat (`|heel.y − ankle.y| = 0.00`), and the trunk tilt stays inside
  BPS §9's `0–15°` band (`0.0°` at the seam/top, `5.7°` mid-transition). 60 fps playback continuity:
  the largest per-frame joint displacement is `2.44` against the validator's
  `POSITION_DISCONTINUITY = 15` (pre-fix `1.38`) — the added placement movement costs about one unit
  per frame at its fastest, nowhere near a discontinuity.
- **Verification (fresh runs, measured in the same environment).** `./gradlew :app:testDebugUnitTest
  --rerun-tasks` on the pre-fix tree with this change's test moved aside: **114 classes / 525 tests /
  0F / 0E / 0S**; on this branch: **115 / 534 / 0F / 0E / 0S** — exactly `+1` class / `+9` tests (the new
  gate), no other count moved. Focused set green: `M1StepUpGeometryTest` 9, `StepUpPoseTest` 1,
  `LungePosesTest` 4, `EnvironmentPenetrationTest` 9, `PlankForearmSupportGeometryTest` 9,
  `SquatMotionTest` 1, `HeadTargetBaselineTest` 1, `SupportDeclarationChannelTest` 4,
  `SupportPointSideConsumptionTest` 6. Release/build: `:app:compileReleaseKotlin`,
  `:app:compileReleaseJavaWithJavac`, `:app:assembleDebug`, `:app:assembleRelease -x lintVitalRelease`.
- **Contracts preserved (measured, not weakened).** `StepUpPoseTest` green; `LungePosesTest` green with
  `armAsym=0.0 legAsym=9.9353485 footSlide=0.0 supportDrift=0.31368256` (its `legAsym < 15` and
  `supportDrift < 0.5` bands unchanged; the pre-fix pose measured `legAsym=11.087173 supportDrift=0.0000076`,
  so the corrected rep sits at the same order of asymmetry, not a looser one); `SquatMotionTest` green (the pose's `8`-unit travel floor against a
  `38`-unit rise); `HeadTargetBaselineTest` green; `EnvironmentPenetrationTest` (9) green;
  `SupportDeclarationChannelTest` (4) / `SupportPointSideConsumptionTest` (6) green;
  `PlankForearmSupportGeometryTest` (9) green after its documented re-baseline.
- **Recorded, deliberately NOT decided here (product/design questions, unchanged by this pass).**
  (i) In this variant the trailing foot comes up to the step's **level** beside the tread rather than
  being placed **on** the tread (BPS §7: "the trailing foot is lifted off the floor (or steps up to
  meet the box, depending on the variant)"), and it cannot simply be held on the floor while the lead
  foot climbs — that would break the pose's limb-asymmetry band. Which of the BPS's two variants this
  exercise should be is a design decision, not a geometry defect. (ii) The step's **height** (`36`
  against BPS §11's "box height appropriate (lead thigh ~parallel or above when foot placed)") is the
  authored tuning value the audit did not raise; M1 makes the ascent honest about it, it does not
  re-tune it. (iii) `StepUpPoseTest`'s foot-slide metric samples only frames where a foot joint is
  below `y = 12`, and no step-up foot is ever below `12` — the assertion is vacuous there today
  (recorded as an observation of the T-5/T-7 class, not touched by this pass).
- **Deliberately NOT touched.** The engine (no finalizer, solver, pipeline, validator-threshold or
  `SupportMath` change — the 2-unit band included), every other pose and every other P11 finding
  (M2–M15, B-1…B-8b residuals, §12.7's configuration half), and the renderer.

### DONE — M3 + M5 the prone trunk family's geometry: extension owned by the spine, Superman's basis, the snow angel's trunk chain (branch `fix/m3-m5-prone-trunk-geometry`; production geometry)

Branch `fix/m3-m5-prone-trunk-geometry` off `main` `2bb4525` (the M1 merge). **Production geometry
change: three pose files** (`ProneCobraStretchPose`, `SupermanPose`, `ReverseSnowAngelPose`) plus the
new `M3M5ProneTrunkGeometryTest` gate and two re-baselined corpus digests. No engine file, no solver,
no carrier, no API, no ownership change.

**Finding status re-verified on `2bb4525` (published frames, `produceFrame(pose, ctx)` — the
metadata-derived entry point playback uses, 5 progress samples, warmed pipeline; the cold and settled
frames of these three poses are identical, so warming is a harness property, not a confound).**

| finding | recorded as | status on `2bb4525` |
|---|---|---|
| **M3** `ProneCobraStretchPose` | "Whole −1.57→−0.9 trunk extension on PELVIS, not thoracolumbar/lumbar → chest follows rigidly (same class as the S3 ThoracicExtension fix)" | **STILL PRESENT** — reproduced exactly: the pelvis's own world rotation sweeps `1.5700 → 0.9000` (Δ `0.6700` rad = `38.4°`), its own trunk base reaches `0.6226` (`38.4°`) out of the floor plane, and `chest.localRotation.angle == 0.0000` at every sampled frame |
| **M4** `SupermanPose` (the M5 record's sibling; the mission's "Superman" half) | "Back extension by rotating pelvis→chest vector, no lumbar articulation; missing support declaration/exerciseFamily/bodyOrientation metadata" | **STILL PRESENT, and worse than recorded** — the pose renders **SUPINE** (`facing.y = +1.0000`, the ventral axis points at the sky) against a BPS whose §1/§3/§8 specify a prone, anterior-fulcrum exercise; the arch also rides the root (`1.5708 → 1.3708`), the chest carries `0.0000`, and at the rest phase **12 joints sit below the pose's own declared ground** (`HEAD_POS y = −24.4818`, `HAND_A/P y = −4.2842`, `FINGERTIPS_A/P y = −9.8092`) |
| **M5** `ReverseSnowAngelPose` | "Missing support declaration/exerciseFamily/bodyOrientation despite planted legs; arm arc maxSweep=170° at fixed Y=15 never clears overhead" | **CHANGED / SUPERSEDED — the named symptoms do not reproduce.** The basis is already prone (`facing.y = −0.9975`, spine `+X`), the pelvis rotation is already constant across the rep (`1.5000` at every sample), there is no ground violation, and the arm arc **does** reach overhead (measured `5.51°` from the trunk axis at the `p = 0/1` extremes → the BPS §9 "arms up by the head" end; the sweep is `5.5° → 85.1° → 169.7° → back`). What remains, and what this pass corrects, is the pose's **legacy trunk**: its hand-rolled tree has no lower-spine segment, so `Joint.LUMBAR` publishes at the **world origin** (`|LUMBAR − PELVIS| = 18.0278u`, with `CLAVICLE_A/P`, `SCAPULA_A/P`, `WRIST_A/P`), and its authored maintained extension rides the **root** (the pelvis is tilted out of the prone layout by `0.0708` rad while `chest.localRotation.angle == 0.0000`) |

- **First incorrect production representation (one shared abstraction, three poses).** In all three
  the *whole-body layout* and the *spine articulation* are authored as one ROOT rotation: the
  extension amount goes into `declarePelvisTilt(...)` and the chest node carries `localRotation.angle
  == 0.0000` — the pelvis, which every one of the three BPS documents names as the floor fulcrum
  ("the pelvis remains neutral and stays on the floor; the extension originates from the paraspinals,
  not from tilting the pelvis"; "Hips stay grounded"; "pelvis neutral and grounded — no arching or
  tucking"), is the hinge, and the two-segment `PELVIS → LUMBAR → CHEST` model the engine provides has
  no representation. In `ReverseSnowAngelPose` the same abstraction is expressed even more strongly:
  the pose builds its **own** node tree, so its trunk has no `LUMBAR` at all. `SupermanPose` adds a
  second, independent error on top: the P12 WP-D conversion reproduced the legacy world-position
  layout as "trunk toward −X with a +90° root tilt", which is this engine's **supine** basis
  (`DeadBug`/`LegRaise`), not the BPS's prone one.
- **Diagnosis discipline — the pose was never blamed for the engine's own conventions.** Three
  candidate mechanisms were measured and ruled out before editing: (i) the trunk length is correct in
  all three (`|CHEST − PELVIS| = 120.0000` at every frame — the "collapsed trunk looks like the pose
  stopped being prone" failure mode); (ii) the legs' published positions are invariant to the root's
  rotation (the IK targets are world-space and the hips' offsets are lateral, so `KNEE_F/ANKLE_F` are
  byte-identical at every sampled frame pre-fix — the deficit is not limb drift); (iii) the supine
  basis is the pose's own declaration (`declarePelvisTilt(..., +π/2 + chestLean)`), not a solver or
  finalizer relocation.
- **Exact production corrections (three pose files).**
  1. `ProneCobraStretchPose` — the root now carries the pose's own authored **prone layout**
     (`proneLayoutPitch = −1.57`, constant) and the rep's extension (`extensionRad = 0.67`, measured
     pre-fix as the pelvis span) is authored on the spine through the repository's single authorized
     trunk-lean path: `buildSpineCurve(lumbar, chest, extension, extension*0.4, axisZ)` — the S3
     `ThoracicExtensionPose` shape and proportion, which is the class the audit names. The stale
     duplicate `declareJointIntent(Joint.PELVIS, …)` (it carried the animated value the root no longer
     authors) is removed; `declarePelvisTilt` already records that carrier.
  2. `SupermanPose` — the layout is mirrored onto the **prone** basis the sibling prone poses publish
     (`rotZ(−π/2)`: spine `+Y` → world `+X` head end, ventral `+X` → world `−Y` face down) and is
     constant across the rep; the arch (`0.2` rad, the pre-fix authored amount) is articulated on the
     same two-segment shape; the arms are anchored to the pose's own prone floor line
     (`PRONE_BODY_Y`) instead of a level that does not exist for a prone body (pre-fix the hands hung
     `4.28u` under the ground at rest); the head is authored in the chain's **own** frame instead of a
     world-space `headTarget` (`resolveHeadTarget` derives its direction from a world delta and writes
     it as a LOCAL offset, which cannot express a gaze on a rolled body — the documented B-7/B-8b
     constraint; the pose-side conversion that would recover it is prohibited by `MIGRATION_RULES` A8).
  3. `ReverseSnowAngelPose` — the hand-rolled tree is replaced by the canonical
     `SkeletonFactory.createStandardSkeleton()` tree (the pose's own KDoc already claimed this; the
     factory's added nodes are pass-throughs between the chest and the shoulder, so the migration is
     geometry-neutral for everything the pose authors), and its maintained extension
     (`π/2 − 1.50 = 0.0708` rad, the pose's own authored tilt — not re-tuned) is held on the spine
     instead of the root, isometrically (BPS §9 "a maintained posture … held isometrically").
- **Measured (published frames, pre-fix → post-fix).**

  | pose | quantity | pre-fix | post-fix |
  |---|---|---|---|
  | cobra | pelvis world rotation (span over the rep) | `1.5700 → 0.9000` (Δ`0.6700`) | `1.5700` constant (Δ`0.0000`) |
  | cobra | pelvis own trunk base out of the floor plane (worst) | `38.4°` | `0.05°` |
  | cobra | chest node's own rotation (thoracic share, at the top) | `0.0000` rad | `0.2680` rad (`15.4°`) |
  | cobra | chest rise / head Y at the top | `74.59` / `95.80` | `74.59` (**identical**) / `104.97` (head extends with the spine, BPS §4) |
  | cobra | pelvis Y, ankle Y (the grounded chain) | `15.00` / `15.00` | `15.00` / `15.00` (unchanged) |
  | superman | basis (ventral axis `rot·X`) | `(0, +1, 0)` **SUPINE** | `(0, −0.98, 0)` **PRONE** |
  | superman | trunk axis (`rot·Y`) | `(−1, 0, 0)` (head end `−X`) | `(+1, 0, 0)` (head end `+X`, the family convention) |
  | superman | pelvis world rotation (span) | `1.5708 → 1.3708` (Δ`0.2000`) | `−1.5708` constant |
  | superman | chest node's own rotation at the top | `0.0000` | `0.0800` rad (`4.6°`) |
  | superman | `HEAD_POS` Y over the rep | `−24.48 → +2.10` (**under the floor**) | `+10.00 → +43.79` |
  | superman | `HAND_A` Y over the rep | `−4.28 → +89.56` | `+10.00 → +66.86` (same authored lift `56.86u`) |
  | superman | worst joint Y / joints below ground | `−24.4818` / **12** at p=0 | `+6.59` / **0** |
  | snow angel | `LUMBAR` published position | `(0, 0, 0)` — the world origin, `18.0278u` from the pelvis | at the pelvis (`(15.00, 10.00, 0.00)`), `0.0000u` |
  | snow angel | pelvis own trunk base out of the plane | `4.06°` | `0.00°` |
  | snow angel | chest node's own rotation (maintained) | `0.0000` | `0.0283` rad (`1.6°`), held constant |
  | snow angel | chest Y / head Y (maintained) | `18.49` / `21.04` | `18.49` (**identical**) / `22.05` |
  | snow angel | worst joint Y | `0.00` (a phantom `CLAVICLE_A` at the origin) | `+6.28` (a real joint) |
- **Corpus impact (measured, not inferred).** A whole-corpus dump of **51 production pose classes ×
  5 progress samples × every joint XYZ** (`8415` rows, full float bits) on both trees differs in
  **exactly 377 rows — all of them the three corrected poses** (`SupermanPose` 153,
  `ReverseSnowAngelPose` 143, `ProneCobraStretchPose` 81); the other **48 classes are byte-identical**,
  and the orientation census of those 48 (declared orientation, measured basis, pelvis frame) is
  byte-identical too. The repository's two shared corpus digests are re-baselined with that
  attribution recorded at each constant, each observed RED on its pre-fix value first:
  `PlankForearmSupportGeometryTest.UNAFFECTED_CORPUS_DIGEST` `−2908768886375429885` →
  `3799530965937589305`; `M1StepUpGeometryTest.UNAFFECTED_CORPUS_DIGEST` `2746720065314572970` →
  `−8991724156081959456`. The new gate pins its own digest with the three corrected classes excluded —
  **equal on both trees** (`−517042293001259057`), which is where "the other 48 classes are untouched"
  is actually gated.
- **Regression coverage (fresh runs).** New `M3M5ProneTrunkGeometryTest` (**9 tests**): the prone
  basis of the production frame (ventral axis, head end, head/feet sides); the pelvis as the floor
  fulcrum (its published world rotation and position constant across the rep, its own frame in the
  layout); the dynamic reps' extension articulated on the spine (the thoracic share above the trunk
  line, the chest lifting off the floor, the seam flat); the isometric member's maintained extension
  held on the spine; the authored depth preserved (no re-tuning); the declared floor per pose (the
  grounded chain on the floor line, the chest off it, the Superman's fulcrum anterior with **no** joint
  below ground and the arms hovering and lifting); the published trunk chain complete (a real
  `LUMBAR` junction, the chest owning the trunk length); anti-vacuity (distinct by-value snapshots
  that move); and the 48-class byte-identity digest. **RED on the pre-fix tree: 7 of 9 fail**, quoting
  the numbers (`body is NOT prone — facing axis (0.0000,1.0000,0.0000)`; `the pelvis frame MOVES …
  axis (0,0,−1)/1.4025 vs the seam 1.5700`; `the chest node carries NO thoracic extension above the
  trunk line (share 0.0000 rad)`; `HEAD_POS is BELOW the declared ground (y=−24.4818)`; `the
  lower-spine junction LUMBAR (0,0,0) is not at the pelvis (15.00,10.00,0.00) (gap 18.0278u)`;
  `thoracic share per sample [0.0000, …]`).
- **Counterfactual RED gates (each executed against the defective shape, then restored and re-verified
  by `md5sum -c`).** (1) The cobra's extension back on the root → **2/9 fail** (the pelvis guard and
  the spine-articulation guard). (2) Superman's pre-fix authoring restored (supine basis + root arch) →
  **4/9 fail** (basis, pelvis, articulation, authored depth). (3) Superman with the prone basis KEPT
  but the arch back on the root (isolating the ownership correction) → **2/9 fail** (the pelvis guard
  and the depth guard, which catches the double-applied rotation). (4) The snow angel's pre-fix bytes
  restored from `HEAD` → **2/9 fail** (the trunk-chain guard and the maintained-extension guard). Each
  correction is independently load-bearing.
- **Movement review (published frames, the pose's own axes).** Cobra: the chest rises `0.10 → 74.59`
  with the pelvis pinned to the floor line and the legs flat at `Y = 15.00`; the trunk's inclination
  goes `0.05° → 38.43°` while the pelvis's own frame stays at the layout, and the head rides the arch
  (`8.06 → 104.97`). Superman: flat prone at the seam (pelvis, chest and the arms all on the body's
  floor line), then a shallow symmetric bow — chest `10.00 → 33.84`, head `10.00 → 43.79`, legs
  `10.00 → 70.82`, arms `10.00 → 66.86` — with the pelvis held at `10.00` throughout. Snow angel: the
  trunk held steady (chest `18.49`, head `22.05`), legs flat and still, arms sweeping
  `5.5° → 85.1° → 169.7°` abduction and back, `p = 0` ≡ `p = 1` (the `LOOP` seam closes). Side-view
  plots of all three at `p = 0 / 0.5 / 1` read as the exercise the BPS describes; the arm sweep of the
  snow angel lies in the transverse plane (its side view correctly shows it edge-on).
- **Verification (fresh runs, measured in the same environment).** `./gradlew :app:testDebugUnitTest
  --rerun-tasks` on the pre-fix tree with this change's test moved aside: **115 classes / 534 tests /
  0F / 0E / 0S**; on this branch: **116 / 543 / 0F / 0E / 0S** — exactly `+1` class / `+9` tests (the
  new gate), no other count moved. Release/build: `:app:compileReleaseKotlin`,
  `:app:compileReleaseJavaWithJavac`, `:app:assembleDebug`, `:app:assembleRelease -x lintVitalRelease`.
- **Recorded, deliberately NOT decided here (product/tuning questions, unchanged by this pass).**
  (i) **M5's declaration half** (`support`/`exerciseFamily`/`bodyOrientation`): not added — those
  fields have no production consumer, the repository has no canonical family vocabulary for this group
  (`bodyOrientation` values in use are `Prone`/`Hanging`/`Side-lying`/`upright` with no contract), and
  the declaration channel for the contact-bearing families is owned by M8/M9/M10. Declaring them here
  would be a vocabulary decision, not a geometry correction. (ii) The snow angel's arm arc lower bound:
  the BPS §9 window is "~90° (arms to the sides) … ~150–180° overhead"; the pose sweeps to ~`10°`
  abduction (arms alongside the body) at its extreme. Measured, not re-tuned — an authored tuning
  choice outside the trunk/legacy-path finding. (iii) `SupermanPose` is `MotionCurve.LINEAR` while its
  siblings are `EASE_IN_OUT` (the pre-existing open item the P11 report already records).
  (iv) `cobra_stretch_hold`'s `FINGERTIPS_A/P` sit `6.99` below the declared ground at the seam — the
  T-1/B-3 class (the pose declares no support model, so the engine's extremity projection has nothing
  to key on); untouched, out of scope. (v) The engine's `0.98` reach cap means a straight limb is
  never realized (measured knee bulge `38.87` on the cobra's legs, `20.85` on the Superman's) — an
  engine-wide property, not a trunk finding.
- **Deliberately NOT touched.** The engine (no finalizer, solver, pipeline, validator, `SupportMath` or
  carrier change), the renderer, M1, M6–M15, and the P2 cleanup list.

### DONE — M6 + M7 the swing's hip hinge / straight arms and the burpee's rep geometry (production geometry)

Branch `fix/m6-m7-swing-burpee-geometry` off `main` `fc65695` (the M3/M5 merge, PR #239). **Production
geometry change: `KettlebellSwingPose` and `BurpeePose` only** — plus the three re-baselined corpus
digests and the new `M6M7SwingBurpeeGeometryTest` gate. Both findings re-measured against the current
tree first; one of the two audit descriptions does **not** reproduce as written and the corrected
statement is recorded below.

- **M6 — the audit's wording does not reproduce; the defect behind it does.** §3 M6 says the hinge
  profile is "inverted: `pelvisY=lerp(175,210)` makes deep hike taller than top while `leanAngle→0`".
  Measured on `fc65695` (`u = (1−cos 2πp)/2`, so the hinge bottom is `u=0` at the seam, not `u=1`): the
  hinge bottom is the **lower** pose (`175.00`) and the top the taller one (`210.00`) with
  `leanAngle = 0` — the profile is not inverted, and the top is correctly upright. What *is* wrong is
  the profile's **axis**: the pelvis travels `35.00` units **down** and only `20.00` **back**, so the
  legs must shorten to keep the feet planted — the hip→ankle span collapses to `166.57` of the
  210-unit leg, i.e. **75.23° of knee flexion at the hinge bottom** (interior `104.77°`) with the knee
  jutting to `x = +53.07`, `46.9` in front of the ankle. That is the squat the BPS names as the
  swing's first mistake ("Squatting instead of hinging (too much knee bend, thighs dropping)", §12;
  "knee flexion: only slight … this distinguishes the swing from a squat", §9).
- **M6, second authored error (the same class, measured, and inside the pose M6 owns).** The hand
  target was lerped in **world space** from `(-35, 130)` to `(40, pelvisY + torsoLength)`, i.e. to
  chest height **40 units in front of the shoulder** — inside the arm's own minimum reach. The solver
  folded the elbow onto its minimum-flexion stop: published elbow interior **`30.00°`** (150° of elbow
  flexion) at `p ∈ [0.35, 0.65]`, shoulder→hand span **`40.13`** of the 146-unit arm, and the elbow
  flared **`80.61` units sideways** — the character *curls the load to its chest*, the BPS's "the arms
  are straight — they do not curl or press the load" (§11) / "using the arms to curl/press the load up
  instead of thrusting with the hips" (§12) violated on every frame of the upswing. Audit §3's M8
  sentence covers the reach-clamp machinery for this family; the pose-side authoring error is what is
  corrected here (no declaration/carrier work — that stays M8).
- **M7 — reproduces as recorded, and is one defect with two faces.** Measured on `fc65695`: the
  squat's `pelvisY = 35` with the ankle target at `15` puts the authored hip→ankle span at `20.00`,
  inside the knee's minimum reach (`55.99`), so `clampTargetToReach` pushed the effector out along its
  own ray and the published **feet sank through the declared ground** — ankle `-7.88` at `p=0.15`,
  **`-21.01` at `p=0.15…0.20`** (heel/toe the same). And the plank authored the feet at `x = −110`
  against the command's `x = 25`: a hip→ankle span of `65` units (the leg is 210) and hands `48.9`
  behind the shoulders, so the published "plank" was a tucked crouch — knee interior `37.40°`, elbow
  `57.53°`, hips **`13.90`** units off the straight shoulder→ankle line — while the jump-top
  (`pelvisY = 185`, ankle target `100`) folded the legs to `47.17°` and left the "overhead" hands
  `81` units **below** the head.
- **Root cause, both poses, one statement.** Every target the two poses authored was an absolute world
  point chosen by eye, with no relation to the reachable spans of the chains that must realize it: the
  hip→ankle span is fixed by `pelvisY` alone (nothing in the pose ever asked how long a leg is), and
  the hand target was an independent `lerp` in `X`/`Y` (nothing ever asked how long an arm is). The
  solver's honest answer to an unrealizable target is to relocate the effector — which is what the
  published frames show.
- **Exact authoring correction — derive the targets from the chains, keep the poses' structure.**
  No engine file, no solver, no carrier, no API, no new global state; both poses keep their builders,
  metadata, loop mode, phase windows, poles (mirrored per side where the burpee had passed one pole to
  both arms) and node hierarchies.
  1. **A shared, in-band length definition.** Each pose now derives the span it authors from the
     solver's **own** reachable length (`(L1 + L2) · ikConstraint.effectiveExtensionRatio`), at `0.98`
     (swing hinge) / `0.99` (swing top, burpee limbs) of it — so the authored target is inside the
     annulus `clampTargetToReach` respects and the realized limb is what the pose declared.
  2. **Swing (`M6`): the pelvis's path is the hinge.** The hinge bottom pushes the hips back
     `HINGE_HIP_BACK = 60` with the pelvis's height **derived** from the authored leg span
     (`ankleLevel + √(span² − back²)`), the top is one authored span above the ankle. The knee now
     tracks over the ankle (`x = −1.05` at the hinge vs `+53.07`), the shin angle barely changes, and
     the hinge is `4.41°` more knee flexion than the top (was `40.19°`).
  3. **Swing (`M6`): the load is a straight pendulum hung on the shoulder.** The hand target is placed
     on the arm's own reachable circle (`shoulder + span·(sin θ, −cos θ)` with `θ` sweeping
     `−0.52 rad → +1.45 rad` from the downward vertical), so the shoulder→hand distance is the authored
     span at **every** frame; the load travels from behind the knees to chest height in front, both
     phases of the BPS's pendulum, with the elbows straight throughout.
  4. **Burpee (`M7`): the plant schedule.** The rep's contacts are now one geometry: the hands plant
     where the squat's trunk reaches (`plantX = torsoLength·sin 60°`), stay exactly there through the
     whole plant window, and the feet plant one full body line away (`legspan` behind the hands at the
     plank). The squat's depth is **derived** from that plant (`squatY = armSpan − torsoLength·cos 60°`)
     instead of chosen, so the authored span (`66.65`) can never cross the knee's minimum reach.
  5. **Burpee (`M7`): the plank is a pivoting rigid line.** Both ends of the plank are pinned (the
     hands at the plant, the feet at their plant) and the body is one line from the shoulder to the
     ankle; the optional push-up pivots that line about the **planted feet** (a shoulder drop of `42`
     units), which is why the dip now bends the elbows (`112.69°` / `82.16°` interior) while the body
     stays straight and **neither contact moves**. The jump is authored as a whole-body rise with the
     limbs staying extended (`ankle = pelvis − span`), and the landing keeps soft knees by absorbing
     `40` units below the stand before extending back to it — which is also the loop's seam pose.
- **Measured (published frames, same pipeline, pre-fix `fc65695` → this branch).**

  | KettlebellSwing | pre-fix | post-fix |
  |---|---|---|
  | pelvis at the hinge `(x, y)` | `(−20.00, 175.00)` | `(−60.00, 202.55)` |
  | pelvis at the top | `(0.00, 210.00)` | `(0.00, 213.74)` |
  | hip→ankle span at the hinge (of 210) | `166.57` | `201.98` |
  | knee interior, hinge → top | `104.77° → 144.96°` | `148.16° → 152.57°` |
  | knee `x` at the hinge | `+53.07` | `−1.05` |
  | elbow interior (min over the rep) | `30.00°` | `151.82°` |
  | shoulder→hand span (min) | `40.13` | `141.65` |
  | elbow lateral `z` at the top | `−80.61` | `−53.43` |
  | load at the hinge `(x, y)` | `(−23.76, 139.17)` | `(−23.29, 134.32)` |
  | load at the top `(x, y)` | `(40.00, 330.00)` | `(140.32, 316.71)` |
  | trunk tilt, hinge → top | `63.03° → 0.00°` | `63.03° → 0.00°` |

  | Burpee | pre-fix | post-fix |
  |---|---|---|
  | stand pelvis `y` (seam) | `140.00` | `218.74` |
  | squat bottom pelvis `y` / hip→ankle span | `35.00` / `56.01` (clamped) | `81.65` / `66.65` |
  | lowest ankle `y` (whole rep) | `−21.01` (`p=0.15/0.20`) | `+11.92` (mid-kick-back) |
  | plank pelvis / feet `x` / hands `x` | `(−45.00, 45.00)` / `−110.00` / `25.00` | `(−6.51, 94.70)` / `−194.02` / `103.92` |
  | plank hip offset from the body line | `13.90` | `0.00` |
  | plank knee / elbow interior | `37.40°` / `57.53°` | `143.75°` / `140.39°` |
  | push-up bottom elbow interior | `57.53°` (no bend at all) | `82.16°`, plants held to `<0.5` |
  | jump-top pelvis / knee / elbow | `185.00` / `47.17°` / `34.47°` | `263.74` / `152.57°` / `151.82°` |
  | jump-top hand vs head `y` | `260.00` vs `341.00` (arms folded **down**) | `525.30` vs `419.74` (arms overhead) |
  | foot kick-back travel | `110.00` | `194.02` |
  | loop seam (`p=0` vs `p=1`) | exact | exact |

- **Movement review (published frames, the poses' own axes).** Swing: the body rises `35.00`, folds the
  trunk `63.03°` and pushes the hips back `60.00` over barely-bent knees while the load sweeps `−23.3 →
  +140.3` in `x` (behind the knees → chest height) on a constant-length arm. Burpee: stand tall
  (`218.74`) → squat with the hands planted at `103.92` → feet shoot `194` back into a straight
  body-length plank under the shoulders → the push-up dips the line with both plants held → the feet
  return → a full-extension jump with the hands `105.6` above the head → a soft landing back to the
  stand. Both read as the exercise their BPS describes through the whole rep, which is the bar this
  pass was set.
- **RED before / GREEN after (the gate is `M6M7SwingBurpeeGeometryTest`, 15 tests, published frames,
  by-value snapshots).** With the two pose files restored to `fc65695` (`git checkout fc65695 -- <the two
  poses>`, md5s recorded, `git diff fc65695 --stat -- poses/` empty) and the new test left in place:
  **12 of 15 RED** — e.g. `kettlebellSwingHingeBendsTheKneesOnlySlightly` (minimum knee interior
  `104.77°`), `kettlebellSwingArmsStayAStraightPendulum` (`30.00°` elbow, `40.13` span),
  `kettlebellSwingHipsPushBackInsteadOfDropping` (`20.00` back vs `35.00` down),
  `kettlebellSwingLoadTravelsFromBehindTheKneesToChestHeight` (hand `x = 40.00` at the top),
  `burpeeFeetNeverSinkThroughTheirFloor` (`ANKLE_F = −7.88` at `p=0.15`),
  `burpeeSquatBottomStaysInsideTheLegsReachableSpan` (`56.01`, exactly the clamp),
  `burpeeHandsStayPlantedWhileTheyBearThePlank` (`1.01` off the floor),
  `burpeePlankIsAStraightExtendedLineUnderTheShoulders` (`13.90` off the line),
  `burpeePushUpDipBendsTheElbowsWithBothPlantsHeld` (no bend),
  `burpeeFeetShootBackAFullPlankLength` (`110.00`), `burpeeJumpTopIsAFullExtensionWithTheArmsOverhead`
  (`47.17°` knees, hands under the head), `burpeeStandsTallAtTheSeam` (`140.00`). The three that are
  green on both trees are stated rather than hidden: two are non-discriminating spec checks
  (the trunk's rigidity and the loop seam), and the third is the blast-radius digest, which excludes
  the two corrected poses and is therefore green on both trees **by construction**. Restoring the fix
  (md5-verified byte-identical, `md5sum -c /tmp/m6m7fix/hashes`) → **15/15 GREEN** (fresh, on the
  pushed bytes).
- **Blast radius, direct and non-inferred.** Whole-corpus dump (`51` classes × `5` progress × every
  joint XYZ = `8415` rows, full float bits) measured on both trees: **exactly `268` rows differ, all of
  them `KettlebellSwingPose` (`140` = 28 joints × 5 samples) and `BurpeePose` (`128` = 28 joints at each
  interior sample, 22 at each seam sample), i.e. 49 classes are byte-identical** (largest single-joint
  move: `188.41` `BurpeePose KNEE_F`, `126.41` `KettlebellSwingPose FINGERTIPS_A`). The residual
  foot-joint differences are float-level (`ANKLE_F.y` `9.999992` vs `10.000000`) from the different IK
  path, not a semantics change. The three pre-existing digests whose "every other pose" corpora include
  these two poses went **RED on their previous values** (`PlankForearmSupportGeometryTest`
  `3799530965937589305`, `M3M5ProneTrunkGeometryTest` `−517042293001259057`, `M1StepUpGeometryTest`
  `−8991724156081959456`) and are re-baselined here with the responsible change named at each constant;
  the new file pins the complementary guard (`M6M7SwingBurpeeGeometryTest.UNAFFECTED_CORPUS_DIGEST =
  −2275091341366878044`, measured **equal on both trees** with the two corrected classes excluded).
- **Verification (fresh runs, same environment).** Full suite `./gradlew :app:testDebugUnitTest
  --rerun-tasks` with the results directory purged: **117 classes / 558 tests / 0F / 0E / 0S** against
  the pre-fix tree's **116 / 543** — exactly `+1` class / `+15` tests (the new gate), no other count
  moved. Release: `:app:compileReleaseKotlin`, `:app:compileReleaseJavaWithJavac`,
  `:app:assembleDebug`, `:app:assembleRelease -x lintVitalRelease`.
- **Recorded, deliberately NOT fixed (adjacent findings, measured).** (i) The planted hand's
  derivative chain still hangs below the floor (burpee `FINGERTIPS_A.y = −21.00` at the plant) because
  neither pose declares a support model, so the engine's extremity projection has nothing to key on —
  the T-1/M8 class, owned by M8/M9/M10's declaration pass; the pose keeps the convention its authoring
  already had (the wrist joint at the floor, pre-fix `FINGERTIPS_A.y = −18.79` at `p=0.2`). (ii) Both
  poses' feet ride `10`/`15` units above the ground (the pre-existing floating-foot class, unchanged by
  this pass). (iii) The engine's `0.98` reach cap means a straight limb is never realized — measured
  knee bulge `28.2` on the swing's hinge and `27.6` on the plank's legs; the residual bulge is directed
  away from the floor (the poses' leg pole is unchanged). (iv) The burpee's knee dips `2.35` units below
  the ground for one mid-kick-back frame (the deep fold + the leg pole), pre-existing class and
  negligible at this scale; recorded, not chased. (v) `BurpeePoseTest` stores
  `produceFrame(...).pose` references for 30 frames (the T-7 buffer-aliasing class: it validates one
  frame 30×) — an adjacent test-quality finding, untouched here.
- **Deliberately NOT touched.** The engine (no finalizer, solver, pipeline, validator, `SupportMath`,
  `bakeIkLimb` or carrier change), the renderer, the poses' metadata/declarations (`M8`/`M9`/`M10`),
  M11–M15, and the P2 cleanup list.

### DONE — M8 + M9 + M10 the support-model declaration pass (+ M8's authored-frame correction) (branch `fix/m8-m9-m10-support-declaration`; production declarations + production geometry)

Branch created off `fc65695` (the M3/M5 merge, PR #239) and **rebased onto the M6/M7 merge `eea705c` (PR #240)** when that landed — all numbers below re-measured on that merged base (the rebase moved this pass's three corpus digests, its blast-radius row counts, and the burpee's interaction with the re-authored M7 plant; the pose-side correction itself is byte-unchanged, verified by `git diff` on `app/src/main`).

The group's three findings re-measured against the current tree first. **Two of them share ONE root
cause; M8's second clause is a different one** — recorded here as measured, not as the audit row
reads.

- **Root cause 1 (M9's 8 stretch poses + M10's 3 core/hip poses + M8's declaration half).** These
  poses never wrote their support model, so the engine published an EMPTY one for every one of them.
  **The channel was never broken** — `PoseMetadata.support` (`SupportDefinition.pivot` + `.contacts`)
  is the ONE declaration channel since B-2; its `supportPoints` accessor is resolved once per frame by
  `SkeletonPipeline`'s single R8 injection into `SkeletonPose.supportedPoints`, and that carrier is
  what `SkeletonPoseFinalizer.declaredFootSupportPoint` / `declaredHandSupportPoint` /
  `supportPlaneNormalFor` (and every validator) consume. Measured pre-fix: `supportedPoints = []` for
  all 18 at every sampled progress under both frame conditions, and the declaration-driven
  derivation was therefore inert for all 18. **Authoritative channel: `metadata.support`. Consumed
  representation: `SkeletonPose.supportedPoints`.** No consumer is defective; the declaration itself
  was missing. (`exerciseFamily`/`bodyOrientation` remain vocabulary with no production consumer;
  `support.pivot` is read by the push-up family's `KNEES` branch only; `HIPS`/`PELVIS`/`BACK`,
  `*_KNEE` and `*_ELBOW` resolve to joints but no derivation consumes them — the pass's vocabulary
  gap, item (a) below.)
- **Root cause 2 (M8's second clause — "IK targets never run through `clampTargetToReach` →
  unreachable authoring silently solver-clamped").** 5 of the 7 upper/dynamic poses
  (`ArmCirclesPose`, `FacePullPose`, `ScapularRetractionPose`, `WallSlidesPose`, `HipCarsPose`)
  author their limb IK targets as ABSOLUTE world points in the floor-anchored frame the pose itself
  used to write (`targetAnkle = (0, def.foot.ankleHeight, ±z)`,
  `handY = standH + def.torsoLength + …`), while `pelvis.localPosition` is `(0,0,0)` and the coarse
  root height is written by the ConstraintSolver's STANDING posture pin **after** `build` (B3). At
  build time the hip is therefore at the origin and the authored ankle target sits ~15 units ABOVE
  it — below the chain's minimum reach on the wrong side — so the solver relocates the effector
  outward along that upward direction. Measured published frames: `PELVIS/HIP_F 235.0`, `KNEE_F
  281.8595`, `ANKLE_F 288.7450` (the legs realized pointing UP, feet `273` units above the declared
  floor), `maxIkClampAmount` `40.377…220.538`, and the arms frozen at maximum reach — an arm circle
  that must sweep a `253`-unit diameter measured a `36.51`-unit Y span; the wall slide measured
  `0.19` (the "latent bug" `StaticHoldStabilityTest`'s own note had already flagged against the
  BPS's shoulder sliding). No support declaration can be truthful for geometry like that: the
  declaration pass and this correction are one change.
- **Fix (pose-side only — no engine file, no solver path, no carrier, no API, no new global state).**
  1. **17 declarations on the canonical channel:** M8 — `ArmCirclesPose`/`FacePullPose`/
     `ScapularRetractionPose`/`WallSlidesPose`/`KettlebellSwingPose` = both feet; `HipCarsPose` =
     the stance foot only (`RIGHT_FOOT`: the foot derivation has no "planted" gate, so declaring the
     circling foot would drive a limb that has left the mat); `BurpeePose` = both hands (the hand
     derivation IS self-gating — `planted` = hand below elbow — so the stand/jump phases are
     untouched). M9 — `CouchStretchPose` = the front foot (the rear contact is the shin/knee, no
     `*_KNEE` derivation); `HalfKneelingStretchPose`/`LatStretchPose` = both feet;
     `DynamicWorldsGreatestStretchPose` = both feet + the support hand `RIGHT_HAND` (the reaching A
     hand is not declared); `ProneCobraStretchPose`/`ReverseSnowAngelPose` = both hands + both feet;
     `SupermanPose` = both hands (the bow lifts the legs well clear; the foot derivation is
     ungated). M10 — `GluteBridgePose`/`PelvicTiltPose` = both feet; `MountainClimberPose` = both
     hands. The wall contacts (`WallSlidesPose`, `LatStretchPose`) are deliberately NOT declared —
     the wall's contact plane is M15's finding.
  2. **The 5 standing poses' limb targets re-expressed in the frame the pose actually owns:** each
     target relative to the chain root (`hip*.worldPosition` / `shoulder*.worldPosition`) with the
     span taken from the engine's own `SkeletonMath.maxReach(...)`, and the arm targets the chain's
     `30°` minimum-flexion stop still forbids routed through the engine's own
     `SkeletonMath.clampTargetToReach` (the R2 reach-target helper the rest of the corpus authors
     with). Authored intent is preserved: `HipCars` keeps its circle (now lifted above the standing
     foot line it declares), `FacePull` its `−10 → 0` travel from the shoulder, `WallSlides` its
     `−10 → +60` slide, `ArmCircles` its circle centred on the shoulder, `HipCars`' hands their
     hip line.
- **Measured, published frames, pre-fix → post-fix (`p=0.5`, COLD; the same numbers hold at every
  sampled progress).**

  | pose | `ANKLE_F` | `TOE_F` | `HAND_A` | `maxIkClampAmount` |
  |---|---|---|---|---|
  | `ArmCirclesPose` | `288.745 → 29.247` | `297.801 → 29.247` | `480.455 → 355.0` | `124.935 → 0.047` |
  | `FacePullPose` | `288.745 → 29.247` | `297.801 → 29.247` | `497.721 → 345.0` | `87.499 → 0.047` |
  | `ScapularRetractionPose` | `288.745 → 29.247` | `297.801 → 29.247` | `497.494 → 327.3` | `72.805 → 0.047` |
  | `WallSlidesPose` | `288.745 → 29.247` | `297.801 → 29.247` | `497.659 → 387.0` | `117.688 → 0.047` |
  | `HipCarsPose` | `284.550 → 47.200` (the circling leg; the stance foot lands on the `29.247` line) | `284.550 → 47.200` | `450.0 → 215.0` (hands on the hip line) | `40.377 → 0.047` |

  The declared hand contacts are now also realized IN the plane their declaration names (the
  declaration reaching its consumer): `ProneCobraStretchPose` `FINGERTIPS_A` `−6.990 → 14.93`
  (level with its `HAND_A`), `DynamicWorldsGreatestStretchPose` `FINGERTIPS_P` `−12.900 → 7.45`,
  `ReverseSnowAngelPose` `8.659 → 15.000`, `SupermanPose` `6.592 → 10.000`.
- **Regression coverage (fresh runs).** New `M8M9M10SupportDeclarationTest` (**8 tests**): the group's
  declaration census on the canonical channel; the declared model reaching the published carrier on
  every sampled progress under both frame conditions; the corrected family's legs realized DOWN to
  the declared support (and each declared foot in its plane); the corrected family's limb targets
  realizable as declared (`maxIkClampAmount` inside the engine's own `0.1` reachability flag); the
  corrected family KEEPING its authored motion (the anti-freeze guard, floors from the measured
  spans: arm circles `256.96`, wall slide `82.71`, face pull `51.50`, hip-car ankle `24.0`, scapular
  squeeze `19.45`); the declared hand contacts in their declared plane; the counterfactual twin
  (declaration removed → empty carrier and the fetched contact back through its surface,
  `FINGERTIPS_A −6.99`); and the blast-radius digest.
- **RED → GREEN.** The same class on the untouched base tree (no test-side tolerance) is **7 of 8
  FAILED** — every behavioural assertion, with the numbers above quoted in the failures. Run TWICE:
  on `fc65695` (this pass's original base, 7 of 8) and again after the rebase on the merged base
  `eea705c` (the same 7 of 8 — re-measured, not carried over). `unaffectedPosesPublishByteIdenticalGeometry`
  is green on every tree by construction (it excludes the corrected classes), which is the point of
  that guard. On this branch: **8/8**.
  Full suite `--rerun-tasks` with the results directory purged: merged base **117 classes / 558 tests /
  0F / 0E / 0S** → this branch **118 / 566 / 0F / 0E / 0S** — exactly `+1` class / `+8` tests (the
  new gate), no other count moved.
- **Blast radius, direct and non-inferred (measured on the MERGED base `eea705c`).** Whole-corpus
  dump (`51` classes × `5` progress × every joint XYZ = `8415` rows, full float bits) measured on the
  rebased base and on this tree: **exactly `717` rows differ, every one of them inside the pass's own
  classes** — the 5 standing poses (`100` rows each: legs, feet, arms),
  `DynamicWorldsGreatestStretchPose` (`35`), `BurpeePose`/`MountainClimberPose`/
  `ProneCobraStretchPose`/`ReverseSnowAngelPose`/`SupermanPose` (`30` each), `PelvicTiltPose` (`20`),
  `KettlebellSwingPose` (`8`), `GluteBridgePose` (`4`). **The other 37 production classes are
  byte-identical**. Cross-check that the rebased base is what it claims to be: the same dump on
  `fc65695` vs `eea705c` differs in **exactly `268` rows, all `KettlebellSwingPose` (`140`) +
  `BurpeePose` (`128`)** — the M6/M7 merge's own, documented effect, reproduced independently here.
  Four declared poses are truthful and inert on this base and therefore invisible in the dump
  (`CouchStretchPose`, `HalfKneelingStretchPose`, `LatStretchPose` — and `KettlebellSwingPose`,
  whose declaration now moves `8` rows because the M6/M7 merge re-authored its feet). The new gate's
  own digest (`M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST = -9118394861084468944`) covers
  every class OUTSIDE the group and is measured EQUAL on the pre-fix tree, on the merged base and on
  this tree. **Five** pre-existing guards whose corpora include the corrected classes went RED on
  their previous values and are re-baselined with this pass named at each constant
  (`RuntimeArchitectureBaselineTest.ARMCIRCLES` golden — the fixture's role is unchanged, it is
  still the posture-driven, zero-Contact-Declaration representative; `M1StepUpGeometryTest`
  `-8991724156081959456` / `6801737802461053843` → `2391109884830495565`;
  `M3M5ProneTrunkGeometryTest` `-517042293001259057` / `-5490451701131484798` → `5434130474548470574`;
  `PlankForearmSupportGeometryTest` `3799530965937589305` / `-8819852136411858964` → `2399534090990759846`;
  `M6M7SwingBurpeeGeometryTest` `-2275091341366878044` → `-8892365611399986406`).
  `EnvironmentPenetrationTest`'s pinned declaration census moves from 26 to 9 names (the pass's
  classes leave it; the census is what makes a silently-dropped declaration fail), and the invariant
  it owns now evaluates this pass's declarations for real — `BurpeePose`'s declared hands included,
  which is how the plant's derivative chain was verified clean rather than assumed.
- **Contracts whose calibration had been read off the frozen chains, re-derived from the authored
  choreography (not weakened, not retuned).** `MobilityMotionTest`'s `HipCarsPose` floor `35 → 30`
  (the authored circle: `radiusX = 15`, a `30.0`-unit ankle span; the old floor was passed only by
  the clamp artifact — measured knee swing `74.73`). `StaticHoldStabilityTest`: `WallSlidesPose`
  (`0.19 → 82.71`) and `FacePullPose` (`3.92 → 51.50`) REMOVED from the static-hold list — they are
  rep exercises (`wall_slide_standard`, `face_pull_banded`) and were only ever "static" because the
  clamp froze them; their authored motion is now pinned by the new gate's anti-freeze guard instead.
  `ScapularRetractionPose` stays a hold by registration with its ceiling re-pointed `15 → 20` against
  the authored squeeze (`19.45`).
- **Deliberately NOT done (recorded, not silently resolved).** (a) **`HamstringStretchPose` (M9) is
  NOT declared** — its only derivable floor contact is the foot and the pose authors BOTH feet's
  articulation ("front foot points to sky"), so a `*_FOOT` declaration would drive the authored
  pointed foot THROUGH the floor (measured `TOE_F 21.55 → −2.57` at `p=0.5`, i.e. the declaration's
  own derivation creates the penetration). Its real support (pelvis + legs on the mat) has no
  consumed point: the **vocabulary gap** the pass records rather than invents a point for.
  (b) **`BurpeePose`'s FEET are not declared** — the remaining one-line follow-up of this finding.
  (Before the rebase this was *blocked*: on `fc65695` the un-fixed M7 plant drove the declared toe
  chain `9.73` BELOW the floor, `TOES worst=-9.732243`. On the merged base `eea705c` the foot chain is
  clean — measured `ANKLE_F` `15.00–19.10`, `HEEL_F` `11.92–15.00`, `TOE_F` `15.00–36.67`, nothing
  below the surface — so declaring it is unblocked, but it is a further production change and this
  pass keeps its scope.) The HANDS declaration this pass does ship is *verified* on the merged base,
  not assumed: pre-declaration the planted hand's fingertips hung `21.010` BELOW the floor
  (`FINGERTIPS_A/P −21.010` at `p=0.25/0.75`, `KNUCKLES −11.46`, `PALM −5.73`); with it the chain is
  realized in the declared plane (`3.8e-06`) while the stand/jump phases stay untouched (the
  derivation's `planted` gate). (c) Seven production classes remain undeclared with no M-number that would own them
  (`AlternatingBirdDogPose`, `BirdDogPose`, `StaticBirdDogHoldPose`, `QuadrupedThoracicRotationsPose`,
  `ThoracicExtensionPose`, `DeadBugPose`, `LegRaisePose`) — flagged for assignment, deliberately not
  expanded into this pass. (d) `GluteBridgePose`/`PelvicTiltPose` publish their elbows
  `−30.69`/`−33.35` BELOW their own mat (measured, unchanged by this pass: no declared contact, so no
  invariant covers them) — the supine arm authoring, recorded as open. (e) The M6/M7/M11–M15 items
  and the P2 cleanup list: untouched.
- **Determination:** the pass was **not** blocked on an architectural decision; every question it met
  that is a product/design choice (declaration vocabulary for non-derivable contacts; whether the hip
  CAR circle should be wider than `radiusX 15`; whether `scapular_retraction_hold` should be authored
  as a true hold; how much of the `0.98` straight-limb cap is a limb bulge) is recorded above with
  its measurement and left for the user.

### DONE — M13 the hamstring stretch's forward reach is realized where the pose authored it (branch `fix/m13-hamstring-reach`, off `0301563`; production geometry)

- **The audit's stated mechanism does not reproduce.** M13 reads "Forward-reach hand target near/beyond
  arm reach (`~200` vs max `146`) → solver-clamped". Measured on the PUBLISHED frame through the whole
  fold (`SkeletonPipeline.produceFrame(pose, ctx)`, `p = 0.00 … 1.00` step `0.05`,
  `SkeletonDefinition.DEFAULT_ADULT`), the declared shoulder→hand distance runs `37.2108 → 115.1790` —
  at its largest `80.5 %` of the `143.0800` band cap (`maxReach = (80 + 66)·0.98`), i.e. the reach is
  never near and never beyond the arm's maximum. The `~200` in the finding is a frame confusion: the
  target sits `199.4` from the **pelvis** at the end of the fold, but the fold itself carries the
  shoulder `100.9` units forward (`(−18.02, 134.40) → (64.00, 89.59)`), so the arm chain's own
  separation is `115.18`. `146` is the anatomical span (`80 + 66`), not the engine's cap.
- **The reproducible violation of the same rule sits at the other end of the fold.** At `p = 0.00` the
  authored start hand `(11.980, 154.400, ∓36.800)` is `37.2108` from its shoulder — **inside** the
  chain's minimum-flexion reach `SkeletonMath.minReach(80, 66, 30°) = 40.1344`. The solver answers by
  relocating the target along its own ray (`published HAND_A (14.337, 155.972, −36.077)`, relocation
  `2.9237` per arm) and the realized chain lands on exactly its `30.00°` interior-angle stop with
  `ELBOW_A.y − SHOULDER_A.y = +64.583` — the elbows flung above the shoulders, which BPS §6/§11 rule
  out ("Shoulders are relaxed and down, not shrugged"). **First incorrect authored
  representation:** the pose authors its forward reach as a FLOOR-FRAME point derived from the
  extended leg's ankle (`reachX = targetAnkleF.x − 10`, `reachY = targetAnkleF.y + 20`, lateral
  offset a body constant `±0.8·shoulderWidth`) and hands it to the arm chain that must realize it —
  `startHandX/Y = chestW + (30, 20)` — with no projection onto that chain's own reachable band.
- **Fix (pose only; smallest change supported by measurement).** The two authored hand targets are
  projected onto the arm chain's band with the existing R2 helper
  `SkeletonMath.clampTargetToReach` — the same reachable-by-construction fix the M8 pass applied to
  `WallSlidesPose` / `FacePullPose` / `ScapularRetractionPose`. The authored direction and the end
  point are unchanged (the projection is along the root→target ray and is a no-op inside the band);
  only the two phases whose declared distance is outside the band move. No solver, finalizer, engine
  or carrier file is touched; the clamp signal stays live rather than muted.
- **RED → GREEN (fresh `--rerun-tasks` runs, results directory purged, XML mtimes from each run).**
  New `HamstringForwardReachTest` (6 tests, all on the published frame): three witnesses — hands
  realized where declared, declared reach inside the band, realized arm never pinned on the flexion
  stop — plus a reach-intent guard (the hands still start in front of the chest and still end at the
  extended foot), a sweep non-vacuity guard and the blast-radius digest. Base tree **3 of 6 FAILED**
  (every witness, quoting `relocation=2.9237`, `d=37.2108` against the band's `40.9371`, and
  `interior=30.0000°`); this branch **6/6**. Surgical counterfactual — the two projection lines
  removed and nothing else changed → the same `3 of 6` FAILED, then the file restored byte-identically
  (`md5sum -c` `f79fb87158f14bca68932fb11aaa8c4a`).
- **Whole-range inspection (41 phases, step `1/40`), not one frame.** Only `p ∈ {0.000, 0.025}`
  change; 12 arm-chain joints each; max deviation `0.9600` u (`FINGERTIPS_A` at `p = 0.025`); the
  declared target at `p = 0.000` moves `(11.980, 154.400, −36.800) → (14.984, 156.403, −35.879)` and
  the realized arm's interior angle leaves the stop (`30.000° → 30.699°`). `p ≥ 0.05` byte-identical.
- **Blast radius, direct and non-inferred.** Whole-corpus dump (49 registry poses × 5 progress × every
  joint XYZ = `245` pose-frames) measured on both trees through a `git stash` round-trip (pose file
  `md5sum -c`-verified on restore): **exactly `1` frame differs** — `hamstring_stretch_hold` at
  `p = 0.0`, 12 joints, max `0.8930` u — the other **`244`** frames byte-identical, with
  `supportedPoints` and `maxIkClampAmount` unchanged everywhere. **Five** pre-existing scope digests
  whose corpora include this pose went RED on their previous values and are re-baselined with this
  change named at each constant (`M1StepUpGeometryTest` `2391109884830495565` → `6236906328909027759`;
  `M3M5ProneTrunkGeometryTest` `5434130474548470574` → `5051896512474775952`;
  `M6M7SwingBurpeeGeometryTest` `-8892365611399986406` → `-5046569167321454212`;
  `M8M9M10SupportDeclarationTest` `-9118394861084468944` → `-3670557964446835822`;
  `PlankForearmSupportGeometryTest` `2399534090990759846` → `-424882841079246328`). Attribution is
  direct: all `50` of those tests were re-run GREEN with the pose file stashed, so the delta is this
  change and not a drifted base. M13's own guard (`HamstringForwardReachTest.UNAFFECTED_CORPUS_DIGEST
  = 6921547823364851041`, its corpus excluding `HamstringStretchPose`) is measured EQUAL on the
  pre-fix tree and on this tree. No `RuntimeArchitectureBaselineTest` golden covers this pose, so
  none moved.
- **Full suite / build.** `--rerun-tasks`, results purged: merged base `118 classes / 566 tests /
  0F / 0E` → this branch **`119 / 572 / 0F / 0E / 0S`** — exactly `+1` class / `+6` tests, no other
  count moved. `:app:assembleDebug` + `:app:compileReleaseKotlin` successful.
- **Recorded, deliberately NOT done (outside M13's wording).** (a) The same pose authors a
  **tucked-leg** target outside its chain's band: `ANKLE_B` is declared `(5.000, 15.000, 11.000)` —
  `36.6879` from `HIP_B`, i.e. `19.3211` INSIDE the leg chain's minimum reach
  `minReach(112, 98, 30°) = 56.0090` — so the solver relocates the realized foot `19.3211` u
  (`published (23.432, 15.000, 5.207)`) at EVERY phase and the knee stays on exactly its `30.00°`
  stop. That is the pose's whole-pose `maxIkClampAmount = 19.32114`, unchanged by this pass (the
  stamp is a max, so the arm's `2.9237` was never separable from it — the per-limb probe had to
  attribute it). M13 names the forward-reach hand target only, and how close the tucked foot comes
  to the groin is a pose-design question (the authored `35` units forward of the pelvis is beyond
  this chain's fold limit by construction), so it is flagged with its measurement and left open;
  the one-line shape if the fold is to stay is the same R2 projection (the realized foot would then
  move `56.0090 → 57.1289`, `1.1` u). (b) The pose's `maxIkClampAmount` cannot attribute a clamp to a
  limb (leg `19.32114` vs arm `2.9237`) — an engine-side instrumentation note, not in scope.
  (c) After the projection the fold's start still asks for a near-maximal arm fold (interior angle
  `30.699°` at `p = 0`, elbows `64.572` above the shoulders); whether the start hand should be
  authored farther from the shoulder (a genuinely relaxed "hands on the shin" start, BPS §6
  "Hands may hold the shin, ankle, or foot") is a design question — the R2 projection deliberately
  preserves the authored direction and is not a re-choreography.

### DONE — M11 + M12 the remaining limb-realization / carrier migration (branch `fix/m11-m12-limb-realization-migration`, off `a8d07cf`; pose-side representation + carrier declarations)

Branch off `main` `a8d07cf` (the M13 merge, PR #242). **Neither row's literal mechanism reproduces on the
current tree**: P12 WP-D (`5727091`) already removed the direct-`solveIK` bypass from both poses — each
realizes its four limbs through the registered package `bakeIkLimb`, each carries four `limbTargets` with a
complete declared realization context (target, pole, `length1`/`length2`, constraint), and `CatCowPose`
already declares its gaze. What remains is the residue the same rows and §4's "TODO — P1" item 2 name
("for full carrier coverage"), re-measured on the PUBLISHED runtime path
(`SkeletonPipeline.produceFrame(pose, ctx)` — the deployed `IK_STAGE_ACTIVE = true` configuration the
renderer and the validators read), 5 progress samples, before any production edit:

| finding | recorded as | status on `a8d07cf` |
|---|---|---|
| **M11** `LatStretchPose` | "Bypasses `bakeIkLimb` (manual solveIK+rotAround) → not in `limbTargets` carrier" | **mechanism GONE, residue present** — the pose still builds its **own hand-rolled node tree** (`PELVIS → CHEST`, `CHEST → SHOULDER_*`), so five canonical joints are never authored and publish at the WORLD ORIGIN: `\|LUMBAR − PELVIS\| = 144.4507` at every sampled phase (the canonical two-segment pass-through measures `0.0000`), and `\|CLAVICLE_A\| = \|CLAVICLE_P\| = \|SCAPULA_A\| = \|SCAPULA_P\| = 0.0000` |
| **M12** `CatCowPose` | "Raw world positions + `fromJointPositions`, bypassing `bakeIkLimb`/buildGaze/intent carriers" | **mechanism GONE, residue present** — the leg end-effectors are still raw floor-frame literals `(50, ankleHeight, ±hipWidth)` that the pose's own leg chain cannot fold to at ANY phase (`42.5 … 45.0` against `SkeletonMath.minReach(112, 98, 30°) = 56.0090`), so the engine relocates the realized foot (`maxIkClampAmount = 11.0090 → 16.0090`, realized `ANKLE_F` `13.5090` off the declared point at `p = 0.5`) — the M8-second-clause / M13 class; and the pose declares NO support model (`supportedPoints = []`) although its BPS §8 base is a four-point contact — the item `EnvironmentPenetrationTest`'s pinned census already attributes to M12 |

- **First incorrect/legacy ownership point, per pose.** (i) `LatStretchPose`: the **legacy authored
  hierarchy** — the pre-factory node list, which has no lower-spine segment and no shoulder girdle, so the
  five joints above are owned by nobody and publish at the origin (exactly the class the M3/M5 pass
  corrected for `ReverseSnowAngelPose`: "its hand-rolled tree has no lower-spine segment, so
  `Joint.LUMBAR` publishes at the world origin"). (ii) `CatCowPose`: the **leg end-effector authored as an
  absolute floor-frame world literal** — the "raw world positions" the row names, kept by WP-D's
  representation migration and never projected onto the chain's own band — so the realized chain lands on
  exactly its `30.00°` stop at every frame instead of where the pose declared it, with `KNEE_F` publishing
  at `(−19.2853, 3.2497, −91.2853)`: `69.3` units OUT of the hip line the BPS §7/§11 pins ("Knees under
  hips … the thighs do not splay").
- **Fix (pose-side only — no engine file, no solver path, no carrier API, no new state).**
  1. `LatStretchPose.ensureHierarchy` adopts the canonical `SkeletonFactory.createStandardSkeleton()`
     tree (the M3/M5 `ReverseSnowAngelPose` shape and the WP-D idiom). The factory's added nodes are
     pass-throughs (coincident, identity rotation) between the links the pose already authored, so every
     transform it writes resolves exactly as before; what changes is that `LUMBAR`/`CLAVICLE_*`/`SCAPULA_*`
     now carry their authored transforms instead of `(0,0,0)`.
  2. `CatCowPose`'s two leg targets are projected onto their chain's own reachable band with the engine's
     existing R2 helper `SkeletonMath.clampTargetToReach` — the same reachable-by-construction fix the M8
     pass applied to the five standing poses and M13 to the hamstring reach. The authored direction and
     stance are unchanged (a no-op for a target inside the band), and the reachability signal stays live
     rather than muted.
  3. `CatCowPose` declares its four-point base (`LEFT_HAND`/`RIGHT_HAND`/`LEFT_KNEE`/`RIGHT_KNEE`,
     `pivot = KNEES` — the `KneePushUpPose` precedent) on the ONE canonical channel `metadata.support`,
     plus the flat ground its BPS names.
- **RED → GREEN (fresh runs, results directory purged, XML-stamped).** New
  `M11M12LimbRealizationMigrationTest` (**5 tests**, all on the published frame): the canonical-hierarchy
  witness; the authored-head guard (M11-b, below); the leg witness (declared distance inside
  `[minReach, maxReach]` **and** the published end joint equal to the declared target **and** the engine's
  own `0.1` reachability band); the four-point-base witness (the declared set reaching
  `SkeletonPose.supportedPoints` under BOTH frame conditions — cold and mid-playback — with the canonical
  `SupportMath.jointsFor` resolution as the anti-vacuity guard); and the blast-radius digest. On the
  untouched base tree the gate is **3 of 5 RED** (every behavioural witness, quoting `|LUMBAR − PELVIS| =
  144.4507`, `45.0` vs `56.009014`, and the empty carrier); on this branch **5/5**.
- **Counterfactual (each hunk removed ALONE, file restored `md5sum`-verified).** Full production stash ⇒
  the same **3 of 5 RED**; the canonical tree reverted alone ⇒ exactly
  `latStretchPublishesTheCanonicalAuthoredHierarchy` RED; the two projection lines removed alone ⇒ exactly
  `catCowLegTargetsAreReachableAsAuthored` RED; the declaration removed alone ⇒ exactly
  `catCowDeclaresItsFourPointBaseOnTheCanonicalChannel` RED; restored ⇒ `5/5` GREEN (restore hashes
  `a5c9f671320e8765c910678faef8a6b4` / `d9e8cca6811fb6f5d92f351e9494f12d`).
- **Blast radius, direct and non-inferred.** Whole-corpus dump (`50` classes × `5` samples × every joint
  XYZ = `8415` rows, full float bits, `git stash` round-trip on the two pose files): **`95` xyz rows
  differ — `70` in `CatCowPose`, `25` in `LatStretchPose`; the other `48` classes are byte-identical.**
  The `25` `LatStretchPose` rows are exactly the five canonical joints × five samples (worst `204.8020` u,
  `CLAVICLE_A` at `p = 0`: origin → the chest pass-through); the `70` `CatCowPose` rows are the
  reachability correction (ankle `1.1202`, heel `1.2313`, toe `0.8481`, knee `0.0455` u) and the
  declaration's own hand derivation flattening the hand chain onto the mat (`FINGERTIPS_*` `4.3222 → 0`,
  `PALM_*` `1.1788 → 0`, `KNUCKLES_*` `2.3576 → 0` at `p = 0` — those joints sat BELOW the mat before,
  which the declaration now forbids). `165` rows carry the clamp change (`11.0090 … 16.0090 → 0.0000` at
  every phase) and `165` the `supportedPoints` change (`∅ →` the four points). **Six** pre-existing scope
  digests whose corpora include these classes went RED on their previous values and are re-baselined with
  this pass named at each constant (`M1StepUpGeometryTest` `6236906328909027759 → -4852997236878182403`;
  `M3M5ProneTrunkGeometryTest` `5051896512474775952 → -6653724965262809122`;
  `M6M7SwingBurpeeGeometryTest` `-5046569167321454212 → -7395791808799176758`;
  `M8M9M10SupportDeclarationTest` `-3670557964446835822 → 8463731255735644640`;
  `PlankForearmSupportGeometryTest` `-424882841079246328 → 8776294205745763414`;
  `HamstringForwardReachTest` `6921547823364851041 → 4572325181887128495`), and this pass's own guard
  (`M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST = -7010204834070121618`) is measured
  **equal on both trees**. `EnvironmentPenetrationTest`'s pinned declaration census moves `9 → 8` names
  (this pose leaves it), so its B-6 invariant now evaluates the new declarations for real: `2` hands ×
  `4` joints + `2` knees × `1` joint × `5` samples × `2` frame conditions = `100` new observations.
- **Full suite / build.** `--rerun-tasks`, results purged: pre-fix **`119 classes / 572 tests / 0F / 0E`**
  → this branch **`120 / 577 / 0F / 0E / 0S`** — exactly `+1` class / `+5` tests, no other count moved.
  `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` successful.
- **M11-b, recorded and deliberately NOT migrated (measured).** §4's item names the "gaze helpers" as
  M11's other half, and `LatStretchPose` declares no `headTarget`. Migrating it is NOT available as a
  representation change: `SkeletonPoseFinalizer.resolveHeadTarget` derives the gaze DIRECTION from a world
  delta (`headTarget.world − neck.worldPosition`) and writes it verbatim as the neck/head LOCAL offset,
  which the neck's parent rotation then re-applies (`neckParentRot · dir`). This pose's trunk is pitched
  `0.95` rad (`54.4°`), so declaring the authored direction as a world target resolves the head `0.9147`
  rad (`52.4°`) OFF the authored trunk axis — measured by declaring it on this tree (the same constraint
  the M3/M5 record documents for the prone family, where `SupermanPose` authors its head in the chain's
  own frame and the pose-side compensation is prohibited by `MIGRATION_RULES` A8). The pose's authored
  head already lives in the chain's own frame (BPS §4: cervical spine neutral, following the trunk), which
  is the sanctioned representation; the gate pins that resolved behaviour
  (`latStretchAuthoredHeadStaysOnThePosesOwnTrunkAxis`) and is the trap that turns RED the moment someone
  declares the naive world target.
- **Recorded, deliberately NOT fixed (adjacent, measured).** (a) `CatCowPose`'s leg GEOMETRY: the realized
  knee publishes `69.3` units out of the hip line because of the authored pole `(−1, 0, ∓1)` (the bend
  plane is the pose's choice; the BPS wants the knee under the hip with the shin ON the mat and the ankle
  behind the knee — a re-authoring this pass does not own, and the target projection deliberately keeps
  the authored direction/stance). (b) The same pose's spine articulation is authored as the pelvis tilt
  (the M3-class "whole-body layout as one ROOT rotation": `chest.localRotation ≡ 0`, `spineIntent = (0,0)`
  while the pelvis tilt spans only `1.5708 → 1.6124` rad across the rep) — a fidelity question, not a
  carrier one. (c) The realized ankle now sits `2.1292` u below the pose's own mat at `p = 1.0` (pre-fix
  `1.0090`): the reachability projection moves the declared target along its own ray to
  `minReach·(1 + margin) = 57.1290` (the helper's canonical `0.02`), and the pose's `pelvisPos` descends
  across the rep while the floor-frame ankle target does not. The pose's declared four-point base is the
  hands and the knees, so no declared contact is violated; no M-number assigns the foot height. (d)
  `LatStretchPose`'s authored arms put the hands on the wall `84.8` below and `12.4` forward of the
  shoulder with the elbow `51.5` units outboard in Z (shoulder→hand `86.2` of the `146` arm span), against
  a BPS §6/§9/§11 that asks for a full-elevation overhead reach with a straight elbow — an M-class
  authorship question the audit's rows do not name. (e) Both poses keep the P2 items (WRIST mirror lines,
  the duplicate PELVIS intent) — that pass owns them.

### DONE — M15 the wall slide's forearms lie on the wall's own contact plane (branch `fix/m15-wallslides-wall-geometry`, off `4203fff`; production geometry)

Branch off `main` `4203fff` (the M11/M12 merge, PR #243). The row reads "Wall modeled in X but forearms
abducted in Z → 'forearms flat on wall' not enforced" — re-measured on the PUBLISHED runtime path
(`SkeletonPipeline.produceFrame(pose, ctx)`; 9 progress samples; both the genuinely cold first frame of a
fresh builder on a fresh pipeline and the frames an advancing pipeline publishes) before any edit. In this
engine `WallProp.width` is the X extent, so a wall's **+X face is its contact plane**, and the exercise's
own contract is stated in the BPS §6/§8 ("the elbows and wrists maintain wall contact throughout … elbows
and the backs of the wrists/hands contact the wall") and in `Movement Ownership Matrix` §Wall Slide
("Followers: … Elbow, Wrist/Hand (**on wall**)").

| reading | measured on `4203fff` |
|---|---|
| wall prop | center `(-15, 90, 0)`, `8 × 180 × 160` → slab `x ∈ [-19, -11]` (face **`-11`**), `y ∈ [0, 180]`, `z ∈ [-80, 80]` |
| `ELBOW_A` / `HAND_A` X | `-62.58 … -51.90` / `-5.000` — the elbow is `40.9 … 51.6` u **behind** the wall's face |
| forearm X span (its wall-normal component) vs its `66` u length | `46.9 … 65.7` of `66` — the forearm runs roughly ALONG the wall's normal, i.e. through the wall |
| wall contacts' Y | `ELBOW_*` `356.37 … 382.86`, `HAND_*` `332.29 … 415.00` — `152 … 235` u ABOVE the wall's top edge (`y = 180`, itself below the athlete's own pelvis at `235`) |
| `ELBOW_*` Z | `±101.52 … ±104.51` — `21.5 … 24.5` u outside the wall's `z ∈ [-80, 80]` extent |
| wrist above elbow (the arms slide UP the wall) | false at `p ≤ 0.125` (hand `332.29` vs elbow `356.37`) |

- **First incorrect authored representation, and the root cause.** The pose declares the wall as a plane of
  constant X and then authors its arm chain in a **different frame**, in two independent ways.
  (i) The hands are authored at `x = -5` — the plane of the athlete's own spine — while the wall's declared
  face is `-11`, so **no authored wall contact lies on the declared plane at all**; the elbow cannot be
  anywhere near it either. (ii) The elbow pole `Vector3(-1f, 0f, ∓1f)` — whose comment reads "points
  backward and outward to keep contact with the wall plane" — carries a **negative X component**.
  `SkeletonMath.solveTriangleJoint` places the elbow on the circle of radius `h = sqrt(L1² − a²)`
  perpendicular to the shoulder→hand chord (`h ≈ 65.9` at the W) and the pole selects only the DIRECTION of
  that offset, so a pole pointing along `-X` throws the elbow `40.9 … 51.6` u behind the face. The first
  incorrect point is therefore the **wall-contact authoring itself**: the pose's wall plane and the plane
  its arms are authored in are two different planes.
- **Second, measured defect inside the same authoring (why "put the arms on the face" is not enough on its
  own).** The authored "W" (`handY = shoulderY − 10`, `handZ = shoulderWidth + 15` outboard) sits `19.00` u
  from the shoulder, while the arm chain's own `minimumFlexionAngle` stop puts its minimum reachable
  shoulder→hand distance at `SkeletonMath.minReach(80, 66, 30°) = 40.1344` u. The M8 pass's R2 helper
  answers that by projecting the target onto the reachable annulus **along its own ray** — and because the
  shoulder sits `6` u in front of the wall's face, that ray carries an out-of-plane component, so the
  projection replaces the impossible W with a hand up to `6.9` u BEHIND the wall's face (`p = 0 … 0.5`).
  The realized W is the projection, not the authoring: the authored target `(-5, 345, -61)` publishes as
  `(-5.000, 332.292, -80.062)`.
- **Fix (pose-side only — no engine file, no solver path, no carrier, no new global state).**
  1. **One plane.** The prop is placed from a single `WALL_FACE_X` constant and the ARM CHAIN is authored in
     that same plane (`handX = WALL_FACE_X`), so the declared contact plane and the authored arm plane are
     the same plane by construction.
  2. **The elbow's bend plane is the wall's plane.** The pole is no longer a hand-tuned vector: the pose
     derives it (`wallPlanePole`) from the chord's own numbers — the perpendicular offset must carry
     exactly `n.x = (WALL_FACE_X − chordFoot.x) / h` — so the elbow lands ON the face. Of the two solutions
     the pose takes the one whose offset hangs BELOW the chord (the elbow trails the hands up the wall;
     wrist above elbow at every phase). This is the B-7 pole-derivation shape ("derive the pole from the
     chain's own statement of where the elbow bends"), pose-side and allocation-free.
  3. **The authored W is made realizable IN the plane** (`extendToInPlaneReach`): the authored direction is
     kept and extended to `minReach · (1 + 0.02)` — the R2 helper's own band and margin — using only the
     in-plane components, so `clampTargetToReach` then copies the target unchanged and no reach projection
     can relocate a wall contact off the wall. The authored slide (`−10 → +60` from the shoulder) and the
     authored abduction (`15 → 25` outboard) are untouched; the realized hand path moves by ≤ `0.25` u in
     Y/Z against the pre-fix tree.
  4. **The wall is the surface the arms slide ON.** The prop's extent is sized from the measured contact
     envelope: `500` tall (`y ∈ [0, 500]`, against a fingertip apex of `428.42`) and `300` deep
     (`z ∈ [-150, 150]`, against an abducted-elbow apex of `125.72`), with the athlete's `391`-unit
     standing height as the lower bound. Its X placement (`x ∈ [-19, -11]`, face `-11`) is H1's and is
     deliberately NOT moved onto the spine plane — see the refuted alternative below.
- **Measured, published frames, pre-fix → post-fix (the same numbers at every sampled progress).**

  | reading | pre-fix | post-fix |
  |---|---|---|
  | `ELBOW_A` X | `-62.581 … -51.904` | **`-11.000`** (exactly on the face) |
  | `ELBOW_A` Y / Z | `356.37 … 382.86` / `±101.52 … ±104.51` | `275.23 … 374.74` / `±46.95 … ±125.72` |
  | `HAND_A` X | `-5.000` | **`-11.000`** |
  | forearm X span (of its `66` u) | `46.9 … 65.7` | **`0.0000`** |
  | hand chain (`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`) X | `+0.23 … +16.89` (in front of the wall) | **`-11.000`** (on the face) |
  | `maxIkClampAmount` | `0.047028` | `0.047028` (unchanged — no relocation introduced) |
  | `supportedPoints` | `LEFT_FOOT` + `RIGHT_FOOT` | unchanged |
  | feet (`HEEL_F`/`TOE_F`) | `(-15.150, 29.247, -26.445)` / `(19.850, 29.247, -26.286)` | byte-identical |

- **The refuted alternative (measured, recorded because it looks like the smaller fix).** Pulling the
  WALL's face forward onto the athlete's spine plane (`x = -5`) — which would also make BPS §3/§7/§8's
  "back against the wall" literally true — was implemented and measured FIRST: the athlete's ankles sit at
  that same X, and `SkeletonPoseFinalizer.supportPlaneNormalFor` resolves a declared contact's surface from
  the centroid of its canonical joints (its `WallProp` branch takes no distance comparison), so a wall
  footprint covering the spine plane contains the declared FOOT contact's centroid — on the cold frame the
  heel/toe are coincident with the ankle, putting that centroid exactly on the face — and the finalizer
  re-orients the feet onto the wall's face instead of the floor: measured `HEEL_F (-5.000, 29.247,
  -36.549)` / `TOE_F (-5.000, 29.247, -1.549)`, i.e. the foot's long axis rotated from `+X` onto `±Z`,
  against the correct `(-15.150, 29.247, -26.445)` / `(19.850, 29.247, -26.286)`. That is an engine-side
  coupling, out of a pose-side finding's scope, so the `6`-u standoff stays and the BPS §3/§7/§8 back
  contact remains unmodelled — **the H1 complement, still open**.
- **Regression coverage (fresh runs).** New `M15WallSlidesWallGeometryTest` (**6 tests**, both frame
  conditions × 9 progress samples): the forearms lie in the wall's own declared contact plane; the whole
  hand chain does too (BPS §8's "backs of the wrists/hands"); the wall contacts lie inside the wall prop's
  own Y/Z extent; the slide stays coherent through the whole rep (the authored `−10 → +60` travel, the
  definition's segment lengths, the wrist above the elbow, the elbow outboard of the shoulder); the
  declared foot contacts still resolve to the GROUND (the anti-collateral guard — and the measurement that
  refutes the alternative above); and this finding's own blast-radius digest.
- **RED → GREEN.** The class on the untouched base tree (`origin/main` @ `4203fff`, `--rerun-tasks`) is
  **5 of 6 FAILED**, every failure quoting the numbers tabulated above; the 6th (the foot guard) is green on
  both trees by design. On this branch all 6 are green.
- **Per-hunk counterfactuals (fresh runs, each essential correction removed ALONE).**

  | hunk removed | `M15WallSlidesWallGeometryTest` |
  |---|---|
  | the wall prop's extent (`500 × 300` → the pre-M15 `180 × 160`) | **1 RED** — `theWallContactJointsLieOnTheWallsOwnSurface` |
  | the arm chain authored on the wall's face (`handX` `-11` → the body plane `-5`) | **3 RED** — the plane, the whole hand, the contacts-on-wall |
  | the in-plane reach extension of the authored W | **3 RED** — the plane, the whole hand, the contacts-on-wall |
  | the derived elbow pole (→ the pre-fix `(-1, 0, ∓1)`) | **4 RED** — the above plus `theSlideStaysCoherentThroughTheWholeRep` (wrist below elbow) |

  In every variant the two guards (the foot guard, the scope digest) stay GREEN; the restored bytes
  (`md5sum -c`) re-run fully GREEN.
- **Blast radius (direct, not inferred).** Whole-corpus dump — `51` classes × `9` samples × every joint XYZ,
  every `maxIkClampAmount`/`boneLengthsVerified`/`supportedPoints` stamp, the environment props and the
  declared limb targets (`16524` rows) — over a `git stash` round-trip on the corrected pose file with
  `md5sum -c` on restore: **`126` rows differ, all inside `WallSlidesPose`** (the two arm chains'
  `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*` = `12` joints × `9` samples = `108`,
  plus the `9` `TARGETS` and `9` `ENV` rows). The other `50` classes are byte-identical (`0` differing
  rows), the legs/spine/pelvis are untouched, and `maxIkClampAmount` is `0.047028` on both trees. The six
  long-standing scope digests (`M1StepUpGeometryTest`, `M3M5ProneTrunkGeometryTest`,
  `M6M7SwingBurpeeGeometryTest`, `PlankForearmSupportGeometryTest`, `M11M12LimbRealizationMigrationTest`,
  `HamstringForwardReachTest`) were re-baselined with that measurement appended to each constant's KDoc;
  their tests re-run with the pose file stashed (`--rerun-tasks`) are GREEN there, so the digest delta is
  attributable to this change and not to a drifted base. `M8M9M10SupportDeclarationTest`'s digest is
  unchanged (its corpus excludes `WallSlidesPose` — that pass owns the pose's declaration and authored
  frame), and its anti-freeze floor for this pose stays satisfied (`maxIkClampAmount` `0.047028` inside the
  engine's `0.1` reachability flag).
- **Full suite / build.** `--rerun-tasks`, results purged: this branch → **`121 classes / 583 tests /
  0F / 0E / 0S`**, with `:app:assembleDebug` and `:app:compileReleaseKotlin` in the same run. The same
  tree with the corrected pose file stashed and the new test class set aside reads **`120 / 577`** — i.e.
  exactly `+1` class / `+6` tests from this pass and no other count moved (that base-configuration run's
  six failures are the re-baselined scope digests pointing the other way, which is the re-baseline
  direction itself).
- **Instruments that stayed blind (part of the finding, not a footnote).** (a) `ExerciseValidator` has no
  prop-FACE check: every prop rule it has uses `center.y + height/2` — a wall's TOP edge — which is why
  H1's waist-high stub wall passed validation for three passes. (b) `EnvironmentPenetrationTest` resolves a
  wall's surface to the ground by design ("a wall supports on its face; Y stays the ground reference"), so
  neither a `6`-u standoff nor a `51`-u penetration on the X axis is visible to it; the corpus invariant it
  owns is a Y-band on declared contacts. (c) **`WallSlidesPoseTest` — the pose's own validator sweep — is
  another instance of the T-7 reused-buffer aliasing class**: it stores
  `pipeline.produceFrame(rawPose).pose` into a list and validates it `30` times, and MEASURED it holds
  **`1` distinct frame with a `HAND_A` Y spread of `0.00`** — i.e. it validates one frame thirty times and
  cannot see a temporal or contact-plane error at all. That is test-only work outside M15's scope and is
  recorded here for the T-7 family to pick up, NOT fixed by this pass.
- **Recorded, deliberately NOT done (tuning/design questions M15 forces but does not answer).**
  (a) BPS §1/§3's "W" is "upper arms at ~90° abduction, elbows bent ~90°", which puts the hand ≈`103.7` u
  from the shoulder; the pose's authored slide spans only `19.00 → 65.28` u, so the corrected W is the
  closest a real arm gets to the authored one — the arm at its OWN tightest fold (interior `30.70°` at
  `p = 0`), not the documented relaxed W. Whether the slide's amplitude should be re-authored to the
  documented ROM (`103.7` u at the W) is a pose-DESIGN decision, not M15's, and is left open with its
  measurement. (b) The same applies at the top of the rep: the arm reaches `52.04°` of interior angle at
  `p = 1` (`d = 65.28` of the `143`-unit reach), so BPS §9/§11's "up to full overhead … at the top the arms
  are overhead" canonical sub-pose is not realized in this pose's amplitude either. (c) The `6`-u standoff
  (the refuted alternative above) is the H1 complement. (d) `loopMode = LOOP` on a one-way slide snaps back
  at the seam (`p = 1 → 0`) — this pose's pre-existing shape, not M15's.

### DONE — T2 the published below-ground invariant is evaluated for the whole body (test-only)

Branch `test/t2-published-below-ground-invariant` off `origin/main` `07dfe38` (the M15 merge). **Test-only
change: no production file is touched** (the whole-tree diff is the new test class plus this record).

- **Root cause — the floor contract is stated for the whole body and owned by two narrow instruments.**
  `docs/ENGINE.md` §4: *"Y is up. Ground level is a Y value (`GroundDefinition.level`, default 0).
  **'Below ground' means `y < level`**"*. Every entry of `Joint` is a physical body point (the
  `LUMBAR` spine segment, the `CLAVICLE_*`/`SCAPULA_*` girdle bones, the derived
  `PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`/`HEEL_*`/`TOE_*` chains), but the suite checked the floor in
  exactly two places, both on a **subset**: `ExerciseValidator.validateFeetGroundPenetration` keys on
  the `6` foot joints, and `EnvironmentPenetrationTest` (B-6) keys on a pose's declared support
  contacts. The P11 floor findings all end in the same sentence — the M8/M9/M10 record's clause (d)
  (*"publish their elbows `−30.69`/`−33.35` BELOW their own mat … **no declared contact, so no
  invariant covers them**"*) and the M15 record (*"the corpus invariant it owns is a Y-band on declared
  contacts"*). Measured over the whole production corpus (`51` classes × `5` progress × both frame
  conditions, published path `SkeletonPipeline.produceFrame(pose, ctx)`, on `07dfe38`):

  | instrument | keys on | below-ground pose/joint pairs reported |
  |---|---|---|
  | `ExerciseValidator` ground rule (default config) | `FEET_JOINTS` — `ANKLE/HEEL/TOE_F/B` | **`12` issues in `1` of `11` poses** (`CatCowPose` feet, `HEEL_*` worst `−2.422398`); the other `10` poses report `0` |
  | `EnvironmentPenetrationTest` (B-6) | declared support-contact joints | **`0` of `43`** — every offending joint is outside its pose's own declared family |
  | new `PublishedBelowGroundInvariantTest` | **every** joint of **every** published frame | **`43` pose/joint pairs over `11` poses** *(post-B2 the table measures `42` pairs over `10` poses — the runner's-lunge back-knee pair's exit criterion was met by that correction; see the B2 record below)* |

- **What the new invariant exposes (measured, worst depth per joint over both frame conditions).**

  | pose | below-ground joints (worst) |
  |---|---|
  | `DynamicWorldsGreatestStretchPose` | `KNEE_B −46.1056` (every phase) |
  | `PelvicTiltPose` | `ELBOW_A/P −33.3501`, `HEAD_POS −2.5384`, `NECK_END −2.5295`, `CHEST −0.3659`, `SHOULDER_A/P −0.3659` |
  | `GluteBridgePose` | `ELBOW_A/P −30.6914` (clause (d) above) |
  | `IsometricSidePlankPose` | `KNEE_B −25.8897 → −1.1143` |
  | `DiamondPushUpPose` | `ELBOW_A/P −19.9130` at `p = 0.5` |
  | `QuadrupedThoracicRotationsPose` | `FINGERTIPS_P −19.1372`, `KNUCKLES_P −10.4384`, `PALM_P −5.2192`, `FINGERTIPS_A −0.8409` |
  | `AlternatingBirdDogPose` / `BirdDogPose` / `StaticBirdDogHoldPose` | `FINGERTIPS_A/P −18.4842`, `KNUCKLES_A/P −10.0823`, `PALM_A/P −5.0411` (frame-independent) |
  | `CatCowPose` | `HEEL_F/B −2.4224`, `ANKLE_F/B −2.1292`, `TOE_F/B −1.4113` at `p = 0.75/1.0` (the M11/M12 clause (c) residual) |
  | `BurpeePose` | `KNEE_F/B −2.3537` at `p = 0.25/0.75` |

  Four of the eleven (`AlternatingBirdDogPose`, `BirdDogPose`, `QuadrupedThoracicRotationsPose`,
  `StaticBirdDogHoldPose`) declare NO support at all — the §4 item-2 "flagged for assignment" group. A
  declaration-keyed gate can only ever be blind to exactly those, which is why the new invariant reads
  no declaration.
- **The three categories the invariant must distinguish, and how each is witnessed (all measured).**
  1. *Legitimate support geometry*: the rule is a **lower bound only** (`y >= declaredLevel − 0.05`).
     A planted contact is derived ONTO the plane — measured `HAND_A = 0.000000` and the lowest
     published joint of a floor-planted push-up exactly `0.000000` across the family (Standard/Wide/
     Military/Pike/Knee), pinned by `aPlantedSupportJointRestingOnTheDeclaredPlaneIsNotReported`. No
     upper bound is asserted (a planted toe at `25.000` and knee at `15.000` are the definition's own
     contact radii — the B-6 record's "no float" non-assertion still stands).
  2. *Geometry a pose is allowed to publish below `y = 0`*: the repository's existing channel is the
     pose's own declaration — `metadata.environment.ground.level`, the plane `SkeletonPoseFinalizer`,
     `SkeletonRenderer`, `SkeletonSnapshotRenderer` and `ExerciseValidator` all resolve from the
     environment. The invariant is measured against **that** plane, never against zero:
     `theInvariantJudgesAgainstThePosesOwnDeclaredPlane` lowers a twin's plane to `−25`/`−50` and shows
     (i) the published geometry is unchanged (identical joints, the twin declares no support), (ii) the
     same body is a pinned violation at level `0` and clean at the lowered plane. **No new production
     metadata was added** — none is needed for category (2), and `PoseMetadata.groundHeight` (a second,
     **unread** field — zero production readers) is deliberately not resurrected.
  3. *Genuine penetration*: the `43` attributed pairs above. The perturbation control sinks a contact
     joint (`HAND_A`), an IK-realised joint (`ELBOW_P`) and a trunk joint (`HEAD_POS`) and requires all
     three reported at the perturbed depth.
- **Non-vacuity (the B-6 lesson applied to the whole body).** The corpus is every concrete production
  pose class discovered from `poses/` (`51`); every joint of every pose is evaluated on every sample
  under both frame conditions and the count is reconciled
  (`51 × 33 × 5 × 2 = 16830`, asserted equal); every pose must contribute — **including the four
  above-ground-violating poses that declare no support** (`everyPublishedJointOfEveryProductionPoseIsEvaluated`
  pins that census explicitly, which is the silent-`continue` hole this invariant cannot inherit);
  frames are captured **by value** (`copyFrom` — the pipeline publishes the Finalizer's reused buffer,
  the T-7 trap).
- **The pinned table is data, not a tolerance.** Each of the `43` pairs names its measured worst depth
  (`attributionTolerance = 0.01`) and its owning record ([attribution] — asserted to cover exactly the
  same poses, so an entry cannot be added without stating what owns it). An unattributed violation
  fails; a pinned pair that no longer violates fails (**fixing a pose forces its entry out in the same
  change**); a magnitude that moves fails. `groundBand = 0.05` sits three orders of magnitude below the
  smallest genuine violation (`−0.8409`).
- **RED → GREEN, and the counterfactuals (fresh runs, results purged, XML-stamped).**
  * Invariant with the attribution table **emptied** (mutation M1): **RED**, listing exactly the `43`
    measured pairs — e.g. `DynamicWorldsGreatestStretchPose KNEE_B worst=−46.105583`,
    `PelvicTiltPose ELBOW_A worst=−33.350105`, `BirdDogPose FINGERTIPS_P worst=−18.484234`,
    `CatCowPose HEEL_F worst=−2.4223979`, `BurpeePose KNEE_F worst=−2.3537445`, `PelvicTiltPose
    CHEST worst=−0.36589622`. This is the pre-fix gate: the same file on the untouched tree (nothing
    else changes in this PR) reports every case the mission asked it to catch.
  * Invariant **removed** (mutation M2: rule + table emptied): the `43` cases **disappear from the
    gate** — `noPublishedJointPassesBelowItsPosesOwnDeclaredGroundPlane` is green, and the only red
    tests are the two sensitivity controls whose subject was removed. The rule-independent corpus probe
    re-run in the same session reproduces the same numbers (`md5` identical), i.e. the geometry is
    unchanged and the gate is blind, not the body fixed.
  * Then GREEN on the restored bytes (`md5 63d5ce4c786d1fed1565da78992473d3`), focused
    `PublishedBelowGroundInvariantTest` **9 tests / 0F / 0E / 0S** (`--rerun-tasks`).
  * The declaration channel's blindness is also **pinned as a test**:
    `theBelowGroundClassIsInvisibleToTheDeclarationKeyedInstrument` asserts all `43` pinned joints are
    outside their poses' declared families (and that the validator's foot rule can reach exactly `1` of
    the `11` poses).
- **Full suite / build.** `--rerun-tasks`, results purged: pristine base worktree (`origin/main`
  `07dfe38`) **`121 classes / 583 tests / 0F / 0E / 0S`** → this branch **`122 / 592 / 0F / 0E / 0S`** —
  exactly `+1` class / `+9` tests, no other count moved. `:app:compileReleaseKotlin` +
  `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` successful.
- **Attribution / exit criteria (each pinned entry is an open item for its owning pass, NOT an
  exemption).** `AlternatingBirdDogPose`, `BirdDogPose`, `StaticBirdDogHoldPose`,
  `QuadrupedThoracicRotationsPose` — the undeclared "flagged for assignment" group; `GluteBridgePose` /
  `PelvicTiltPose` — the M10-owned supine arm authoring, clause (d) above; `CatCowPose` — the M11/M12
  clause (c) foot residual; `BurpeePose` — M7's plant phases (the knees), with the M8/M9/M10 item (b)
  foot declaration still the remaining one-liner; `DiamondPushUpPose`, `DynamicWorldsGreatestStretchPose`,
  `IsometricSidePlankPose` — measured here for the first time, **no M-number owns them**; flagged for
  the user to assign rather than silently absorbed. **`DynamicWorldsGreatestStretchPose` has since been
  ASSIGNED and CORRECTED as B2 — see the record below**; its pinned pair left this table in that change,
  which is why the table now measures `42` pairs over `10` poses.
- **Deliberately NOT asserted.** (a) An absolute rest height ("no float") — the engine declares no
  per-contact rest height (see B-6). (b) The world-origin class: the `10` poses that build their own
  node tree still publish `LUMBAR`/`CLAVICLE_*`/`SCAPULA_*` at `(0,0,0)` — exactly AT the plane, so this
  invariant does not flag them (it is a lower bound); that is a different, recorded defect (§4 item 2)
  and conflating the two would make this instrument lie about what it measures. (c) The below-ground
  geometry itself: **not fixed here** — this is the instrument, the poses keep it for their own passes.

### DONE — B2 the runner's-lunge back knee (branch `fix/b2-wgs-back-knee-plane`, off `2fb6079`; production geometry)

**Pose-side change only**: one production file (`poses/DynamicWorldsGreatestStretchPose.kt`), the new
focused regression, the T2 pin-table exit and the re-baselined scope digests. No engine/solver/phase/
ownership change (the knee is an IK output — the pose owns the chain root, the ankle target and the
pole), no RFC change, no golden change, no tolerance moved and no assertion weakened.

- **Root cause — the authored back-leg STANCE, not the pole alone.** The pose declares a runner's
  lunge (*"front foot flat forward, back leg extended with the toe on the floor"*, its own KDoc) on the
  plane T2 measures (`thoracicGround`, `level = 0`). The authored back ankle (`pelvisX - 120`,
  `y = 15`) sat `126.4945` from the hip for a `112 / 98` chain
  (`minReach(112, 98, 30°) = 56.0090`, `maxReach(112, 98) = 205.8000`), so the chain had to fold: the
  knee's locus is a circle of radius `h = 83.2989` about the hip→ankle chord, and the authored pole
  `(0.1, -1, 0.2)` selected the branch that realizes
  `KNEE_B = (-55.2148, -46.1056, +38.6517)` — **`46.1056` u BELOW the mat the pose itself declares**
  (`groundBand = 0.05`), and `16.65` u out of the leg's own sagittal plane (`z = +22`), at EVERY
  sampled phase. It is the deepest published joint of the whole pose (the next lowest is
  `TOE_B +1.5996`). Both alternatives are measured and rejected: the mirror in-plane branch puts the
  knee `55.4` u **above the hip** (a chicken-wing back leg, not a lunge), and every branch that keeps
  the knee near the mat requires splaying it `~78` u laterally out of the leg plane. With that stance
  **no** pole yields a legal back leg — which is why the correction is the stance, not a pole retune.
- **Fix — author the extension the pose declares, derive the bend side.** The back ankle is authored
  one full chain reach behind the hip (`SkeletonMath.maxReach(def.thighLength, def.shinLength,
  def.legIKConstraint)`, inside the solver's own reach band by construction: measured
  `|published ANKLE_B − declared target| = 3.4e-5`, no relocation) at the definition's own
  floor-contact height (`def.foot.ankleHeight`, so the toe stays on the floor), and the pole is derived
  as the hip→ankle chord's in-plane perpendicular pointing DOWN (the lunge's back knee hangs toward the
  mat) instead of a hand-tuned literal carrying a lateral component. The front leg's stance, both arm
  chains and the whole trunk/head chain are untouched.
- **Measured, published path `produceFrame(pose, ctx)`, dense sweep (`51` samples × both frame
  conditions, by-value snapshots):**

  | reading | pre-fix | corrected |
  |---|---|---|
  | worst published joint vs the pose's own declared plane | `KNEE_B −46.1056` (every phase) | **`−0.0000`** (the lowest joint is now the back toe at `+0.9686`) |
  | realized `KNEE_B` | `(-55.2148, -46.1056, +38.6517)` | **`(-113.8925, +13.1629, +22.0000)`** |
  | back knee vs its own hip | `101.11` below the hip, `16.6517` out of the leg plane | `41.84` below the hip, **`0.0000`** out of the leg plane |
  | back leg hip→ankle distance | `126.4911` (`61.5 %` of `maxReach`) | **`205.7998`** (`99.9999 %`) |
  | realized back-knee flexion | `106.3°` | **`23.0°`** (the near-straight chain the KDoc's "extended" means) |
  | derived back-foot chain | `HEEL_B y = 20.4734`, `TOE_B y = 1.5996`, `z = 25.0095 / 14.6320` (splayed) | `y = 20.7311 / 0.9686`, **`z = 22.0000 / 22.0000`** (in the leg plane, toe still on the floor) |
  | `maxIkClampAmount` (p = 0 / 0.25 / 0.5) | `21.640945` / `13.396454` / `7.5872955` | identical — the pose's clamp is its support arm's; B2 adds **zero** |
  | front leg (`KNEE_F`/`ANKLE_F`/`HEEL_F`/`TOE_F`) and both arm chains | — | byte-identical (see the corpus diff below) |
- **Blast radius (whole corpus: `51` classes × `5` samples × every joint XYZ = `8415` rows**, dumped
  through the production pipeline in a pristine `origin/main` @ `2fb6079` worktree (`/tmp/b1-base`) and
  on this tree, then diffed): exactly `20` rows differ, **ALL of them inside
  `DynamicWorldsGreatestStretchPose`** — `KNEE_B` / `ANKLE_B` / `HEEL_B` / `TOE_B` × the `5` samples
  (max `82.2520` u at `HEEL_B`) — with the other `50` classes byte-identical. The seven scope digests
  whose corpus contains this pose were re-baselined with a measured paragraph each:
  `M1StepUpGeometryTest` `-6608239793215088689 → -4254161156074832348`,
  `M3M5ProneTrunkGeometryTest` `-8408967521599715408 → -967624648643503611`,
  `M6M7SwingBurpeeGeometryTest` `-9151034365136083044 → -6796955727995826703`,
  `PlankForearmSupportGeometryTest` `7021051649408857128 → 8245693820516700285`,
  `M11M12LimbRealizationMigrationTest` `-8765447390407027904 → -6411368753266771563`,
  `HamstringForwardReachTest` `2817082625550222209 → 5171161262690478550`,
  `M15WallSlidesWallGeometryTest` `-8128235422251913276 → -7273487059142510759`.
  (`M8M9M10SupportDeclarationTest`'s digest is unchanged by construction: its corpus excludes the `17`
  poses of its own declaration group, and this pose is one of them.)
- **The T2 pin table's exit criterion was met, and it forced its entry out.**
  `PublishedBelowGroundInvariantTest`'s stale-pin guard failed on the fixing change listing exactly
  `DynamicWorldsGreatestStretchPose KNEE_B` — the attributed open item whose owner was *"flagged for
  assignment"* — so the entry **and** its `attribution` line were removed in this same change, together
  with the class KDoc's instrument-table counts (`43 → 42` pairs, `11 → 10` poses). The table's own
  guard is the mechanism that makes a fix non-optional here.
- **Verification.** New focused regression `WorldsGreatestStretchBackKneePlaneTest` (`5` tests: the
  whole-body plane invariant over a **dense `51`-sample** sweep × both frame conditions with by-value
  snapshots, the back knee's clearance and bend side, the knee's own sagittal plane, the back leg's
  realized extension and target identity, and the guards that must not move — the declaration, the
  motion contract, the anti-vacuity spread). **RED on the pristine base worktree**
  (`/tmp/b1-base` @ `2fb6079`, the class copied in, `--rerun-tasks`, results purged): **`4` of `5`
  FAILED** — `noPublishedJointPassesBelowThePosesOwnDeclaredPlane` (`KNEE_B −46.1056` at all `51`
  samples of both conditions), `theBackKneeHoversJustAboveTheMat` (`−46.105583`),
  `theBackKneeStaysInTheLegsOwnSagittalPlane` (`16.6517` off), and
  `theBackLegRealizesTheExtensionItsStanceDeclares` (`d = 126.4911` of `maxReach 205.8000`).
  **GREEN on the branch: `5/5`, `0F / 0E / 0S`** (`--rerun-tasks`). Full suite: pristine base worktree
  **`122 classes / 592 tests / 0F / 0E / 0S`** → this branch **`123 / 597 / 0F / 0E / 0S`** — exactly
  `+1` class / `+5` tests.
- **Residuals recorded, NOT fixed** (all measured on the corrected tree; none of them is below the
  plane, and each is a product/design decision the user owns rather than a B2 scope item):
  (a) the FRONT leg keeps the family's literal pole `(1, 0.2, -0.2)`, so its realized knee sits `29.90`
  u out of its own plane (`KNEE_F z = -51.8962` against the leg's `z = -22`) and `53.32` u ABOVE the
  hip (`HIP_F 55.0000`, `KNEE_F 108.3214`) — a deep fold that the pose's own root height
  (`pelvisY = 55`) forces at that stance; correcting it means re-authoring the root (which moves the
  trunk, the arms and the declared `RIGHT_HAND` contact);
  (b) the declared support hand never reaches the floor: `HAND_P y = 21.6371` at p = 0 → `5.2037` at
  p = 0.75 (`FINGERTIPS_P` identical), the arm chain relocated by the solver
  (`maxIkClampAmount = 21.640945` at p = 0) because the authored hand target sits inside the `80 + 66`
  chain's `minReach` there — the pose's own pre-existing reach authoring, untouched by B2;
  (c) the extended stance widens the pose's X extent from `261.68` to `343.93` u while the family camera
  is a fixed-zoom projection (no fit-to-bounds), so the back foot can sit outside a narrow viewport — a
  viewport/framing decision, deliberately not taken here.

### TODO — P1 (next pass, in priority order)

1. H1 complement — **M15 is DONE — see the record above** (the wall's contact plane, the arm chain
   authored in it, the derived elbow pole, the in-plane reachable W, and the prop's extent). What remains
   from this item is the H1 half alone: the wall's face still stands `6` u behind the plane the athlete's
   spine is authored in, so BPS §3/§7/§8's head/upper-back/pelvis wall contact is not modelled. Moving the
   face onto the spine plane is refuted by measurement (the declared FOOT contacts' centroid falls inside
   such a footprint and the finalizer re-orients the feet onto the wall — see the M15 record), so the
   remaining work is an engine-side question (`supportPlaneNormalFor`'s wall branch takes no distance
   comparison), not a pose edit.
2. H2 complement — **M11 (`LatStretchPose`) + M12 (`CatCowPose`) are DONE — see the record above**
   (the canonical authored hierarchy for M11; the reachable-by-construction leg targets + the four-point
   support declaration for M12). **The declaration half of this item is DONE (M8/M9/M10 — see
   the record above): the 7 upper/dynamic poses, the stretch family and the core/hip poses now
   declare on `metadata.support`.** Still open from those passes: `LatStretchPose`'s gaze migration
   (M11-b — recorded as unavailable: the world-space `headTarget` carrier cannot express this pose's
   pitched trunk, `0.9147` rad of error, and `MIGRATION_RULES` A8 prohibits the pose-side compensation;
   the pose authors its head in the chain's own frame, which the gate pins);
   `HamstringStretchPose`'s declaration
   (blocked on the foot-vocabulary gap), `BurpeePose`'s feet (now unblocked — the M7 plant landed
   as PR #240 / `eea705c` and that foot chain measures clean there), and the 7 undeclared classes with no owning M-number (flagged for assignment).
   **Newly measured and unassigned:** the `10` OTHER poses that still publish `LUMBAR`, `CLAVICLE_A/P` and
   `SCAPULA_A/P` at the world origin because they build their own node tree — `ArmCirclesPose`,
   `BurpeePose`, `FacePullPose`, `GluteBridgePose`, `HipCarsPose`, `KettlebellSwingPose`,
   `MountainClimberPose`, `PelvicTiltPose`, `ScapularRetractionPose`, `WallSlidesPose` — the same class
   this pass corrected for `LatStretchPose` (each measures `|LUMBAR| = 0.0000` with the girdle joints at
   the origin); flagged for the user to assign rather than silently expanded into M11.
   Also unassigned: `CatCowPose`'s leg GEOMETRY (the pole's lateral component splays the realized knee
   `69.3` units out of the hip line, against BPS §7/§11) and its spine articulation authored as the pelvis
   tilt — both recorded with measurements in this pass's record.
3. M2/M6/M7 — pose-specific biomechanical-fidelity bugs (side-plank contact
   side — **the declaration side resolved by B-4 and the pose's own planted-forearm floor debt
   resolved by B-7**). **M1 (the step contact), M3 (cobra) + M4 (superman) + M5
   (snow angel's trunk/legacy path), and M6 (the swing's hinge + its straight-arm pendulum) + M7
   (the burpee's rep geometry) are DONE — see the records above** (M6's audit wording is corrected
   there: the profile's *axis*, not its direction, was the defect, and the pose owns a second
   authored error the audit sentence does not name). **M8/M9/M10 (the support-model declaration
   pass) is DONE too — see the record above**, which also covers M5's declaration half.
4. M14 — the decline plank tilt (M13 is DONE — see the record above).

### TODO — P2

Cleanup pass (remaining): WRIST mirror, stale comments, dead `applyBirdDogExtremities`,
JumpSquat dead vals. (Push-Up family items — dead `handDirA`/`handDirP`, redundant PELVIS intent,
A8/A6 leaks — resolved in the Push-Up Family pass above.)

---

## 5. Working rules

- Treat MonkEngine as a finished engine. Do not introduce new architecture, carriers, or API changes.
- Fix the pose, not the engine, when a pose authors motion incorrectly.
- Keep pose-side migrations on the **existing** carrier surface (the H2 fix is the template).
- After any pose change, confirm `./gradlew :app:testDebugUnitTest` stays at 0 failures against the
  current baseline of record (**122 classes / 592 tests** on `origin/main` @ `2fb6079`, the T2 merge,
  measured fresh in this pass's own base worktree; `123 / 597` with the B2 runner's-lunge back-knee
  correction. Earlier standing points: **120 classes / 577 tests** as of the M11/M12 limb-realization migration,
  which added `M11M12LimbRealizationMigrationTest` and re-baselined six scope digests; the M13
  hamstring-reach correction stood at `119 / 572`, the M6/M7 landing plus the
  M8/M9/M10 declaration pass stood at `118 / 566`; `--rerun-tasks` with the results directory purged, XML-stamped fresh; the
  older "282" figure predates P12) before marking a finding resolved.
