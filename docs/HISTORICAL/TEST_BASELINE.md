# Unit Test Baseline

Snapshot of the expected unit-test state so future sessions can tell **pre-existing
failures** apart from **regressions they introduced**.

- Command: `./gradlew :app:testDebugUnitTest`
- **Current truthful baseline (post Branch B: B1 IkStage + B2 Finalizer intent consumers +
  B3 Posture universality, all byte-identical):**
  **280 tests executed, 0 failures, 0 errors.**
- Progression: `236 / 9` (post-S3) → `239 / 7` (**R1** foot derivation) → `243 / 4` (**R2**
  reach targets) → `243 / 1` (**R3** lunge support anchoring) → **`243 / 0`** (**R4** camera
  framing + hanging-leg reach, legacy engine defects R1–R4 cleared) → **`251 / 0`** (B1) →
  **`275 / 0`** (B2) → **`280 / 0`** (B3, +5 `PostureUniversalityTest`) → **`282 / 0`** (B4 first
  migration, +2 `BranchBFamilyMigrationTest`). The full suite is fully green; every Branch-B carrier
  flip is byte-identical (maxDev 0.0).
- The count `236` includes the four previously-compile-broken files
  (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`)
  that PR #134 (`efef793`) restored to the module. The old "168 / 30" figure was measured
  with those four files excluded and is stale — do not revert to it (see
  RFC_ENGINE_STABILIZATION §2).
- The **9 remaining failures are legacy engine defects, not Intent-Pipeline gaps** (a
  control experiment with the Architecture-v2 flags enabled changed nothing — same 9). They
  were grouped into 4 root-cause defects (R1–R4) and are now **all cleared** under
  **`docs/ENGINE_DEFECT_REMEDIATION_PLAN.md`**, a temporary stabilization detour after which
  work resumes on Architecture v2 / M2. The 9 → R mapping:

  | R | Defect | Failing tests |
  |---|---|---|
  | R1 | Foot extremity derivation | ~~`BurpeePoseTest`, `KettlebellSwingPoseTest`, `KneePushUpPoseTest` (foot bones)~~ **DONE** |
  | R2 | Reach target authoring | ~~`StandardPushUpPoseTest`, `SquatPosesTest` (Sumo), `KneePushUpPoseTest` (arm reach)~~ **DONE** |
  | R3 | Lunge support anchoring | ~~`LungePosesTest` ×3 (Forward/Reverse/Side)~~ **DONE** |
  | R4 | Camera framing (+ hanging-leg reach) | ~~`VerticalPullPosesTest`~~ **DONE** |

  **R4 (DONE):** two facets, both surfaced honestly by the validator (the second was masked
  by the first, which short-circuited the per-frame loop):
  - **R4a — camera framing.** A vertical pull travels a large vertical distance (dead hang →
    full contraction). At `defaultZoom = 1.5` the subject overflowed the frame and `HEAD_POS`
    projected off the **top** of the 1000×1000 viewport around the top of the rep (`standard`
    frame ~71+, `HEAD_VIEWPORT`). Probing the head's projected Y across the rep showed it
    crossing screen-y 0 near frame 71; a zoom sweep confirmed **zoom 1.1** frames the full
    athlete (head→ankles) for every grip variant. Fixed in `BaseVerticalPullPose.verticalPullCamera`
    (yaw/pitch unchanged — the problem was vertical travel, not view angle).
  - **R4b — hanging-leg reach authoring.** With framing fixed, the loop reached the post-loop
    `maxClamp < 0.1` assertion and exposed a latent R2-class defect: as the body rises, the
    authored pendulum-leg ankle target (`rep*8` in X, `−rep*10` in Y) drifts *past* the leg's
    reachable radius (`maxReach = (thigh+shin)*0.98 = 205.8`), so the solver clamped and
    truthfully reported it (`maxIkClampAmount` up to ~4.45). Fixed with the same
    `SkeletonMath.clampTargetToReach` used in R2 — the target is projected onto the reachable
    band so the pendulum legs hang at full-but-valid extension and the solver records **zero**
    clamp, without muting the signal.

  **R3 (DONE):** a plant/swing target-assignment logic error, not a solver/IK issue (verified by
  probe: the planted ankle was rock-steady during its own plant phase). The lunge poses always
  wrote `targetF = plantZ` and `targetB = swingZ`, then chose `plantAnkle = if (plantUsesFrontHip)
  targetF else targetB`. Because which physical foot is the anchor swaps every half cycle, in the
  second half the "plant" ankle inherited the *swing* foot's Z (a sign flip), so the test saw the
  anchor slide up to ~88 units. Fixed by authoring the plant and swing targets **directly** so the
  fixed anchor keeps its own side of the track regardless of role (`assemble` already routes
  `plantAnkle` to the correct hip via `plantUsesFrontHip`). Applied to Forward/Reverse/Side; the
  `StepUp` test in the same class is unaffected. Drift dropped 88 → 0.02.

  **R2 (DONE):** three distinct sub-causes, all "authored IK target outside the reachable
  annulus `[minReach, maxReach]`", each verified by probe (not assumed):
  - R2a `StandardPushUp` — arm hand target too *close* (dMag 34.3 < minReach 40.1).
  - R2b `SumoSquat` — wide-stance foot target too *far* (dMag 213.7 > maxReach 205.8) and the
    crotch-drop hand target too *close* (dMag 9.2).
  - R2c `KneePushUp` — a *posture* bug: the knee-pivot chain pitched the whole trunk ~68° so the
    shoulders stood ~300 units up, making the floor hand target physically unreachable
    (dMag 318 ≫ arm reach 146).

  Fixes: a shared `SkeletonMath.clampTargetToReach` projects an authored target onto the reachable
  band (fixes R2a/R2b — reachable-by-construction, so the solver records **zero** clamp and the
  `IK_TARGET_UNREACHABLE` signal stays honest); R2c adds a hip counter-rotation so the knee push-up
  trunk returns to a level plank (the shin keeps its 45° upward pitch). `ReachTargetTest` locks in
  the band behaviour. Passing variants (`WidePushUp` grip 1.9, `Decline`, `AirSquat`) are unchanged
  — the clamp is a no-op for already-in-band targets.

  **R1 (DONE):** two facets in the MonkEngine runtime foot derivation — (R1a) `FootDefinition.applyPitchClamp`
  produced a non-unit direction for a purely-vertical foot (`|sin 45°| = 0.707`), scaling
  derived heel/toe bones ~29% short → fixed by falling back to a stable horizontal heading so
  the clamped direction stays unit; (R1b) a *neutral* (un-articulated) foot inherited a downward
  pitch from shank geometry and penetrated the ground for poses declaring no support contact →
  fixed in `SkeletonPoseFinalizer.adjustFootOrientation` by clamping the neutral foot direction
  to non-downward (plantar flexion is still honored via the ankle articulation applied after).

> **Audit note (2026-07-17):** an earlier `main` doc commit (`fff841b`) recorded a
> **236 / 24** snapshot taken *after S1 but before S2/S3 landed*. That snapshot is now
> superseded: S2 and S3 fixed 15 of those 24. Its per-test method names were also partly
> approximate. This file is the verified post-S3 state (re-measured on the merge of `main`
> into this branch; `main` added **no code**, only docs, so the count is unchanged at
> **236 / 9**).

---

## Stabilization progress (RFC_ENGINE_STABILIZATION)

| Phase | What it fixed | Result |
|---|---|---|
| S0 | truthful baseline doc + CI failure-count guard | docs only |
| S1 | IK angular-clamp recording; chest-frame → shoulder propagation; ground-contact projection; foot support-plane; head/gaze subtree re-propagation | 7 engine tests green (see table below) |
| S2 | (1) `PELVIS_INTENT` + `CONTACT_PRESERVED` rules now emit ERROR so they invalidate the rule (they previously fired as WARNING and the rule stayed "valid" — the RFC §6 "rule not firing" defect); (2) corrected stale test constants in `ExerciseValidatorTest`, `EnvironmentAnchorsTest`×2, `NewEnginePosesTest`×8; (3) `ValidatorRomClusterTest`×2 (undocumented) now green | 12 tests green |
| S3 | per-pose authoring: `QuadrupedThoracicRotationsPose` reaching-arm world-space vertical sweep (elbow now sweeps up); `ThoracicExtensionPose` arch driven from the thoracolumbar (lumbar) segment so the chest/head translate backward | `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly`, `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` green |

### S1 engine-stabilization commits (green)

These were the only *engine* (ConstraintSolver / IK / finalizer) defects. All fixed and
committed; the tests pass:

| Subsystem | Tests | Commit | Root-cause fix |
|---|---|---|---|
| ConstraintSolver (ground penetration) | `ConstraintSolverTest` | `7aa3dd9` | Support-aware foot orientation: when an ankle is a fixed support contact, the foot's long axis is projected onto the contact plane so heel/toe lie flat instead of penetrating the ground. Generic support-plane reasoning — no per-pose special-casing. |
| ConstraintSolver chest-frame → shoulder chain | `TrunkFrameTest` | `b1d4789` | `BaseThoracicPose.finalizeThoracicPose` returned the reused `jointsBuffer`; a caller holding the result of one `build` saw it aliased by a later build, hiding the (correct) thoracic-twist → shoulder propagation. Now returns an independent snapshot. |
| IK angular clamp | `IKLimbHelperTest` | `cab575d` | Angular clamp recorded from the requested angle; joint placed at the capped angle. |
| Engine BONE_LENGTH (NECK_END→HEAD_POS) | unblocks many `*PoseTest` validators | `48c6438` | `resolveHeadTarget` rewrote neck/head local offsets *after* the FK flatten, so `HEAD_POS` collapsed onto the neck (length 0). Re-propagates the neck→head subtree into the output pose. |

---

## Remaining 9 failures and their true subsystem

These are **not** Validator-rule or pose-authoring bugs. Each is an upstream **S1-residual
IK/reach** defect (`BONE_LENGTH` frame-0 on the arm/hand chain, or `IK_TARGET_UNREACHABLE`).
Do **not** mute them by weakening the MonkEngine runtime or the validator thresholds — they are
diagnostic instruments and must be fixed at the IK/solver source (per RFC §5 the Validator
is the last layer).

Verbatim signals (from JUnit XML), each mapped to its remediation defect:

```
BurpeePoseTest :: testBurpeePoseBiomechanicalCompliance
    -> Frame 0: FOOT_GROUND_PENETRATION TOE_F/TOE_B y=-2.57            [R1 foot derivation]
KettlebellSwingPoseTest :: testKettlebellSwingPoseMeetsAllBiomechanicalRequirements
    -> Frame 31: FOOT_GROUND_PENETRATION TOE y=-0.456                  [R1 foot derivation]
KneePushUpPoseTest :: testKneePushUpPoseBiomechanicalCompliance
    -> BONE_LENGTH ANKLE_B->HEEL_B 7.18 vs 10.15 (29.29%) x200        [R1 foot derivation]
    -> HAND_SHOULDER_ALIGNMENT offset 38.9 x100; IK_TARGET_UNREACHABLE clamp 175 x100 [R2 reach]
SquatPosesTest :: testSumoSquatPoseBiomechanicalCompliance
    -> IK_TARGET_UNREACHABLE x60 (all frames)                         [R2 reach]
StandardPushUpPoseTest :: testStandardPushUpPoseBiomechanicalCompliance
    -> IK_TARGET_UNREACHABLE x60 (all frames)                         [R2 reach]
LungePosesTest :: testForwardLungeBiomechanics
    -> Support foot drift 88.0                                        [R3 lunge anchoring]
LungePosesTest :: testReverseLungeBiomechanics
    -> Support foot drift 86.0                                        [R3 lunge anchoring]
LungePosesTest :: testSideLungeBiomechanics
    -> Support foot drift 122.6                                       [R3 lunge anchoring]
VerticalPullPosesTest :: testVerticalPullFamilyBiomechanics
    -> standard frame 71: HEAD_VIEWPORT (head off 1000x1000 viewport) [R4 camera framing]
```

Note: the earlier "arm/hand chain / IK reach" attribution in prior revisions of this file
was **superseded** by the direct XML trace above — the dominant signals are foot-derivation
and ground-penetration (R1), authored-target reach (R2), support-foot drift (R3), and
camera framing (R4). See `ENGINE_DEFECT_REMEDIATION_PLAN.md` for the full analysis.

### Failing classes (8)

`BurpeePoseTest`, `KettlebellSwingPoseTest`, `KneePushUpPoseTest`, `LungePosesTest` (3),
`SquatPosesTest`, `StandardPushUpPoseTest`, `VerticalPullPosesTest`.

---

## The two historical failure families (now mostly resolved)

1. **`BONE_LENGTH` at frame 0 on the arm/hand chain** — the solver/FK produces a pose whose
   arm/hand bone lengths deviate >1% at frame 0. Root cause is upstream (S1-residual
   IK/solver FK); the validator threshold (1%) is correct and must not be loosened. This is
   the only family still red (the 9 above).
2. **Expected-position drift** — RESOLVED in S2 by correcting the stale test constants
   (pelvis hang `220/230` vs actual `~240-243`; pelvis X/Z step amplitude `40/50` vs actual
   `~54/36`). the MonkEngine runtime output was verified correct before the test constants were updated.

## Regression check

- S1 engine tests (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`) are green.
- `ValidatorRomClusterTest` fully green after S2 (the two historically-undocumented
  `PELVIS_INTENT` / `CONTACT_PRESERVED` rule defects were fixed).
- `VerticalPullPosesTest` BONE_LENGTH portion passes; its remaining failure is the IK/reach
  band (S1 residual).
- Classes green from prior fixes (no regression): `AirSquatPoseTest`,
  `DeclinePushUpPoseTest`, `LatStretchPoseTest`, `MountainClimberPoseTest`,
  `ReverseSnowAngelPoseTest`, `WidePushUpPoseTest`.
- Total moved from the old "168 / 30" → truthful **236 / 24** (post-S1) → **236 / 9**
  (post-S3).

## Separately: 4 test files that previously did not compile

`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`
previously failed to compile (missing `kotlin.math` imports, unsupported 3-arg `max`).
PR #134 fixed the imports/expression. These files now participate in the suite and are
counted in the 236 total. Three (`ConstraintSolverTest`, `IKLimbHelperTest`,
`TrunkFrameTest`) are green after S1; `VerticalPullPosesTest` remains (S1 residual).
