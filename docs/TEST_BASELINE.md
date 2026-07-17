# Unit Test Baseline

Snapshot of the expected unit-test state so future sessions can tell **pre-existing
failures** apart from **regressions they introduced**.

- Command: `./gradlew :app:testDebugUnitTest`
- **Current truthful baseline (post-PR #134 + S1 engine stabilization):**
  **236 tests executed, 24 failures, 0 errors.**
- The 4 previously-non-compiling files (`ConstraintSolverTest`, `IKLimbHelperTest`,
  `TrunkFrameTest`, `VerticalPullPosesTest`) now **compile and are counted**. The old
  "168 / 30" snapshot was taken with those four files excluded from compilation and is
  **no longer valid** — do not revert to it.

---

## S1 engine stabilization — DONE (green)

These were the only *engine* (ConstraintSolver / IK / finalizer) defects. All fixed and
committed; the tests pass:

| Subsystem | Tests | Commit | Root-cause fix |
|---|---|---|---|
| ConstraintSolver (ground penetration) | `ConstraintSolverTest` (6/6) | `7aa3dd9` | Support-aware foot orientation: when an ankle is a fixed support contact, the foot's long axis is projected onto the contact plane so heel/toe lie flat instead of penetrating the ground. Generic support-plane reasoning — no per-pose special-casing. |
| ConstraintSolver chest-frame → shoulder chain | `TrunkFrameTest` (all) | `b1d4789` | `BaseThoracicPose.finalizeThoracicPose` returned the reused `jointsBuffer`; a caller holding the result of one `build` saw it aliased by a later build, hiding the (correct) thoracic-twist → shoulder propagation. Now returns an independent snapshot. |
| IK angular clamp | `IKLimbHelperTest` (all) | `cab575d` (prior) | Angular clamp recorded from the requested angle; joint placed at the capped angle. |
| Engine BONE_LENGTH (NECK_END→HEAD_POS) | unblocks many `*PoseTest` validators | `48c6438` | `resolveHeadTarget` rewrote neck/head local offsets *after* the FK flatten, so `HEAD_POS` collapsed onto the neck (length 0). Re-propagates the neck→head subtree into the output pose. |

After these, `ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest` are fully
green. `VerticalPullPosesTest` is green for the IK/reach + BONE_LENGTH portion; its only
remaining failure is `HEAD_VIEWPORT` (a camera/validator framing rule — see S2 below).

---

## Remaining 24 failures — Validator (S2) / Pose-authoring (S3)

Per `RFC_ENGINE_STABILIZATION.md` §3.3/§3.4, these are **not engine defects**. They map
to the Validator rule set (S2) or per-pose authoring (S3), which run *after* S1 in the
roadmap. Do **not** mute them by weakening the engine — they are diagnostic instruments.

### Two failure families

1. **Validator `BONE_LENGTH` frame-0 / "N validation errors"** — the validator flags bone
   lengths the pose produces (or its expected constants are wrong). Remaining cases are the
   arm/hand chain (`HAND_A -> WRIST_A` etc., where the validator expects `0f` and the pose
   leaves the wrist at the hand) and pull/push/lunge/stretch pose validators emitting bulk
   "X validation errors" — these are Validator-threshold / stale-constant issues (S2), not
   geometry the engine gets wrong.
2. **Expected-position drift (stale test constants)** — poses land a few units off a
   hard-coded expected value (pelvis hang `230` vs actual `~240.9`, `220` vs `~242.9`;
   pelvis X/Z step shift). The **test** encodes stale constants (S2 correction) or the pose
   authors a contact/reach target the validator then rejects (S3).
3. **Camera/contact framing rules** — `HEAD_VIEWPORT` (head off the 2D viewport at the top
   of a pull-up), `FOOT_GROUND_PENETRATION` / "Support foot drift" (foot leaves its support
   anchor). Validator/pose-contact concerns (S2/S3).
4. **Pose-authoring biomechanics** — `Elbow A should sweep open/upward` (S3 elbow-sweep
   sign), `Chest should extend backward with thoracic extension` (S3 thoracic-extension
   magnitude).

### The 24 failures (class :: method -> root-cause bucket)

```
BurpeePoseTest :: testBurpeePoseBiomechanicalCompliance
    -> Frame 0 failed validation                         [S2 BONE_LENGTH / bulk errors]
DynamicStretchPosesTest :: testQuadrupedThoracicRotationsPoseBuildsCorrectly
    -> Elbow A should sweep open/upward                   [S3 elbow-sweep sign]
EnvironmentAnchorsTest :: testStandardPullUpPoseAnchorMetadataAndMigration
    -> expected:230.0 but was:240.92883                    [S2/S3 pelvis-hang stale const]
EnvironmentAnchorsTest :: testSupportMathAnchorResolution
    -> expected:220.0 but was:242.91608                    [S2/S3 pelvis-hang stale const]
ExerciseValidatorTest :: testRule7and8and10Dynamics
    -> AssertionError (validator dynamics rule)           [S2 validator rule]
KettlebellSwingPoseTest :: testKettlebellSwingPoseMeetsAllBiomechanicalRequirements
    -> Frame 31 failed: [FOOT_GROUND_PENETRATION]          [S2/S3 foot contact]
KneePushUpPoseTest :: testLegBilateralSymmetryCorrectness
    -> Knee push-up pose has 400 validation errors         [S2 BONE_LENGTH / bulk errors]
LungePosesTest :: testForwardLungeBiomechanics
    -> Support foot drift 88.0                             [S2/S3 foot contact]
LungePosesTest :: testStepUpBiomechanics
    -> Support foot drift 85.99999                          [S2/S3 foot contact]
LungePosesTest :: testSideLungeBiomechanics
    -> Support foot drift 122.59999                         [S2/S3 foot contact]
NewEnginePosesTest :: testNeutralGripPullUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f)            [S2/S3 pelvis-hang stale const]
NewEnginePosesTest :: testProneCobraStretchPoseBuildsCorrectly
    -> Pelvis Z should shift sideways on step expected:50.0 [S2/S3 pelvis step stale const]
NewEnginePosesTest :: testRegistryIntegration
    -> Pelvis Y should start at resting hang height (220f)  [S2/S3 pelvis-hang stale const]
NewEnginePosesTest :: testAlternatingReverseLungesPoseBuildsCorrectly
    -> Pelvis X should shift backward on step expected:-40.0 [S2/S3 pelvis step stale const]
NewEnginePosesTest :: testWideGripPullUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f)            [S2/S3 pelvis-hang stale const]
NewEnginePosesTest :: testAlternatingForwardLungesPoseBuildsCorrectly
    -> Pelvis X should shift forward on step expected:40.0  [S2/S3 pelvis step stale const]
NewEnginePosesTest :: testUnderhandChinUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f)            [S2/S3 pelvis-hang stale const]
NewEnginePosesTest :: testStaticForearmPlankPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f)            [S2/S3 pelvis-hang stale const]
SquatPosesTest :: testAirSquatPoseBuildsCorrectly
    -> Sumo Squat pose has 60 validation errors             [S2 BONE_LENGTH / bulk errors]
StandardPushUpPoseTest :: testPrintStandardPushUpCoordinates
    -> Push-up pose has 60 validation errors                [S2 BONE_LENGTH / bulk errors]
ThoracicAndHamstringStretchPosesTest :: testThoracicExtensionPoseBuildsCorrectly
    -> Chest should extend backward with thoracic extension [S3 thoracic-extension magnitude]
ValidatorRomClusterTest :: hipRomFlaggedWhenOverAdducted
    -> AssertionError (PELVIS_INTENT / CONTACT rule)        [S2 validator rule — undocumented]
ValidatorRomClusterTest :: straightIntentStillDetectableOnBrokenReference
    -> AssertionError (PELVIS_INTENT / CONTACT rule)        [S2 validator rule — undocumented]
VerticalPullPosesTest :: testVerticalPullFamilyBiomechanics
    -> standard frame 71 failed: [HEAD_VIEWPORT]            [S2/S3 camera framing]
```

### Failing classes (14)

`BurpeePoseTest`, `DynamicStretchPosesTest`, `EnvironmentAnchorsTest` (2),
`ExerciseValidatorTest`, `KettlebellSwingPoseTest`, `KneePushUpPoseTest`,
`LungePosesTest` (3), `NewEnginePosesTest` (8), `SquatPosesTest`,
`StandardPushUpPoseTest`, `ThoracicAndHamstringStretchPosesTest`,
`ValidatorRomClusterTest` (2), `VerticalPullPosesTest`.

> Note: `ValidatorRomClusterTest.hipRomFlaggedWhenOverAdducted` and
> `straightIntentStillDetectableOnBrokenReference` are the two **historically-undocumented**
> failures from `RFC_ENGINE_STABILIZATION.md` §2.3 (present on `main`, missing from the old
> baseline). They are Validator-rule defects (S2), not regressions.

---

## Regression check

- S1 engine tests (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`) are green.
- `VerticalPullPosesTest` BONE_LENGTH + IK/reach pass; only `HEAD_VIEWPORT` remains (S2/S3).
- Classes that were failing in older snapshots but are now **green** (no regression, prior
  fixes): `AirSquatPoseTest`, `DeclinePushUpPoseTest`, `LatStretchPoseTest`,
  `MountainClimberPoseTest`, `ReverseSnowAngelPoseTest`, `WidePushUpPoseTest`.
- Total moved from the old "168 / 30" to the truthful **236 / 24**.
