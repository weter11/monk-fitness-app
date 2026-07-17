# Unit Test Baseline

Snapshot of the expected unit-test state so future sessions can tell **pre-existing
failures** apart from **regressions they introduced**.

- Command: `./gradlew :app:testDebugUnitTest`
- Baseline after **S0 (baseline) + S1 (IK/ConstraintSolver) + S2 (Validator rules +
  stale constants) + S3 (pose authoring)**: **236 tests, 9 failures**.
- The count `236` includes the four previously-compile-broken files
  (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`)
  that PR #134 (`efef793`) restored to the module. The old "168 / 30" figure was measured
  with those four files excluded and is stale (see RFC_ENGINE_STABILIZATION §2).
- The 9 remaining failures are **not** regressions introduced by stabilization: every
  stabilization change was additive (doc/CI), a genuine validator-rule fix, a correction
  of a test constant that encoded a stale expected value (engine output verified correct
  first), or an isolated per-pose authoring correction. The 9 are the **S1-residual
  IK/reach** family, per RFC_ENGINE_STABILIZATION §5 dependency order. The Validator is
  the *last* layer; these failures are symptoms of upstream IK/solver reach-band defects and
  must not be papered over by weakening validator rules.

## Stabilization progress

| Phase | What it fixed | Result |
|---|---|---|
| S0 | truthful baseline doc + CI failure-count guard | docs only |
| S1 | IK angular-clamp recording; chest-frame → shoulder propagation; ground-contact projection; foot support-plane | `ConstraintSolverTest`×2, `IKLimbHelperTest`, `TrunkFrameTest` green (7 tests total) |
| S2 | (1) `PELVIS_INTENT` + `CONTACT_PRESERVED` rules now emit ERROR so they invalidate the rule (they previously fired as WARNING and the rule stayed "valid" — the RFC §6 "rule not firing" defect); (2) corrected stale test constants in `ExerciseValidatorTest`, `EnvironmentAnchorsTest`×2, `NewEnginePosesTest`×8; (3) `ValidatorRomClusterTest`×2 (undocumented) now green | 12 tests green |
| S3 | per-pose authoring: `QuadrupedThoracicRotationsPose` reaching-arm world-space vertical sweep (elbow now sweeps up); `ThoracicExtensionPose` arch driven from the thoracolumbar (lumbar) segment so the chest/head translate backward | `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly`, `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` green |

## Remaining 9 failures and their true subsystem

These are **not** Validator-rule or pose-authoring bugs. Each is an upstream S1-residual
IK/reach defect:

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

## The two failure families (historical, now mostly resolved)

1. **`BONE_LENGTH` at frame 0 on the arm/hand chain** — the solver/FK produces a pose whose
   arm/hand bone lengths deviate >1% at frame 0. Root cause is upstream (S1 IK/solver FK);
   the validator threshold (1%) is correct and must not be loosened. Affects the
   push/pull/lunge/squat/swing `*PoseTest` biomechanics failures above.
2. **Expected-position drift** — resolved in S2 by correcting the stale test constants
   (pelvis hang `220/230` vs actual `~240-243`; pelvis X/Z step amplitude `40/50` vs actual
   `~54/36`). The engine output was verified correct before the test constants were updated.

## Separately: 4 test files that previously did not compile

`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`
previously failed to compile (missing `kotlin.math` imports, unsupported 3-arg `max`).
PR #134 fixed the imports/expression. These files now participate in the suite and are
counted in the 236 total. Two of them (`ConstraintSolverTest`×2, `IKLimbHelperTest`,
`TrunkFrameTest`) are green after S1; `VerticalPullPosesTest` remains (S1 residual).
