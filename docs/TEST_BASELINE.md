# Unit Test Baseline

Snapshot of the expected unit-test state so future sessions can tell **pre-existing
failures** apart from **regressions they introduced**.

- Command: `./gradlew :app:testDebugUnitTest`
- **Current truthful baseline (post PR #134 + S1 + S2 + S3 stabilization):**
  **236 tests executed, 9 failures, 0 errors.**
- The count `236` includes the four previously-compile-broken files
  (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`)
  that PR #134 (`efef793`) restored to the module. The old "168 / 30" figure was measured
  with those four files excluded and is stale — do not revert to it (see
  RFC_ENGINE_STABILIZATION §2).

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
Do **not** mute them by weakening the engine or the validator thresholds — they are
diagnostic instruments and must be fixed at the IK/solver source (per RFC §5 the Validator
is the last layer).

```
BurpeePoseTest :: testBurpeePoseBiomechanicalCompliance
    -> Frame 0 failed validation (BONE_LENGTH on arm/hand chain)   [S1 residual: IK/solver FK]
KettlebellSwingPoseTest :: testKettlebellSwingPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 failed: [BONE_LENGTH] Bone HAND_A ...              [S1 residual: IK/solver FK]
KneePushUpPoseTest :: testKneePushUpPoseBiomechanicalCompliance
    -> validation errors (IK_TARGET_UNREACHABLE / BONE_LENGTH)    [S1 residual: IK/reach]
LungePosesTest :: testForwardLungeBiomechanics
    -> Frame 0: [BONE_LENGTH]                                     [S1 residual: IK/solver FK]
LungePosesTest :: testReverseLungeBiomechanics
    -> Frame 0: [BONE_LENGTH]                                     [S1 residual: IK/solver FK]
LungePosesTest :: testSideLungeBiomechanics
    -> Frame 0: [BONE_LENGTH]                                     [S1 residual: IK/solver FK]
SquatPosesTest :: testSumoSquatPoseBiomechanicalCompliance
    -> validation errors (BONE_LENGTH / IK_TARGET_UNREACHABLE)    [S1 residual: IK/solver FK]
StandardPushUpPoseTest :: testStandardPushUpPoseBiomechanicalCompliance
    -> IK_TARGET_UNREACHABLE (hand target outside reach band)     [S1 residual: IK/reach]
VerticalPullPosesTest :: testVerticalPullFamilyBiomechanics
    -> reach/clamp band not respected (pull family)               [S1 residual: IK/reach]
```

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
   `~54/36`). The engine output was verified correct before the test constants were updated.

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
