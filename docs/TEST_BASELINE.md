# Unit Test Baseline

Snapshot of the expected unit-test state so future sessions can tell **pre-existing
failures** apart from **regressions they introduced**.

- Command: `./gradlew :app:testDebugUnitTest`
- Baseline: **168 tests, 30 failures** (with the two-segment spine / Issue E present).
- These same 30 fail on plain `main` too (verified by stashing feature changes), so
  they are **pre-existing** and unrelated to engine refactor work.
- The number `168` includes the 3 `LumbarThoracicSpineTest` tests added by Issue E
  (all green). Without Issue E the count is 165 / 30.

## Two failure families

1. **`BONE_LENGTH` validation at frame 0** — arm/hand chain (`HAND_A -> WRIST_A` etc.)
   fails the bone-length rule. Affects the pull/push/lunge/stretch pose validators.
2. **Expected-position drift** — poses land a few units off a hard-coded expected value
   (e.g. pelvis hang height `230` vs actual `240.9`), i.e. the tests encode stale
   constants.

## The 30 failures (class :: method -> first message line)

```
AirSquatPoseTest :: testAirSquatPoseBiomechanicalCompliance
    -> Air squat pose has 400 validation errors!
BurpeePoseTest :: testBurpeePoseBiomechanicalCompliance
    -> Frame 0 failed validation!
DeclinePushUpPoseTest :: testDeclinePushUpPoseBiomechanicalCompliance
    -> Decline push-up pose has 200 validation errors!
DynamicStretchPosesTest :: testQuadrupedThoracicRotationsPoseBuildsCorrectly
    -> Elbow A should sweep open/upward: elbowAY0=158.51978, elbowAY1=36.84607
EnvironmentAnchorsTest :: testStandardPullUpPoseAnchorMetadataAndMigration
    -> expected:<230.0> but was:<240.92883>
EnvironmentAnchorsTest :: testHangPoseAnchorMetadataAndMigration
    -> expected:<220.0> but was:<242.91608>
ExerciseValidatorTest :: testRule1FiniteCoordinates
    -> java.lang.AssertionError
KettlebellSwingPoseTest :: testKettlebellSwingPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
KneePushUpPoseTest :: testKneePushUpPoseBiomechanicalCompliance
    -> Knee push-up pose has 200 validation errors!
LatStretchPoseTest :: testLatStretchPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
LungePosesTest :: testForwardLungeBiomechanics
    -> Frame 0 (p=0.0) of AlternatingForwardLungesPose failed: [BONE_LENGTH]
LungePosesTest :: testStepUpBiomechanics
    -> Frame 0 (p=0.0) of StepUpPose failed: [BONE_LENGTH]
LungePosesTest :: testReverseLungeBiomechanics
    -> Frame 0 (p=0.0) of AlternatingReverseLungesPose failed: [BONE_LENGTH]
LungePosesTest :: testSideLungeBiomechanics
    -> Frame 0 (p=0.0) of AlternatingSideLungesPose failed: [BONE_LENGTH]
MountainClimberPoseTest :: testMountainClimberPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
NewEnginePosesTest :: testNeutralGripPullUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f) expected:<230.0> but was:<240.0>
NewEnginePosesTest :: testAlternatingSideLungesPoseBuildsCorrectly
    -> Pelvis Z should shift sideways on step expected:<50.0> but was:<36.0>
NewEnginePosesTest :: testHangPoseBuildsCorrectly
    -> Pelvis Y should start at resting hang height (220f) expected:<220.0> but was:<242.91>
NewEnginePosesTest :: testAlternatingReverseLungesPoseBuildsCorrectly
    -> Pelvis X should shift backward on step expected:<-40.0> but was:<-53.32>
NewEnginePosesTest :: testWideGripPullUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f) expected:<230.0> but was:<247.77292>
NewEnginePosesTest :: testAlternatingForwardLungesPoseBuildsCorrectly
    -> Pelvis X should shift forward on step expected:<40.0> but was:<54.559998>
NewEnginePosesTest :: testUnderhandChinUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f) expected:<230.0> but was:<240.07559>
NewEnginePosesTest :: testStandardPullUpPoseBuildsCorrectly
    -> Pelvis Y should start at deep hang (230f) expected:<230.0> but was:<240.92883>
ReverseSnowAngelPoseTest :: testReverseSnowAngelPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
ScapularPullUpPoseTest :: testScapularPullUpPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
SquatPosesTest :: testSumoSquatPoseBiomechanicalCompliance
    -> Sumo Squat pose has 300 validation errors!
StandardPushUpPoseTest :: testStandardPushUpPoseBiomechanicalCompliance
    -> Push-up pose has 120 validation errors!
StepUpPoseTest :: testStepUpPoseMeetsAllBiomechanicalRequirements
    -> Frame 0 (progress=0.0) failed: [BONE_LENGTH] Bone HAND_A ...
ThoracicAndHamstringStretchPosesTest :: testThoracicExtensionPoseBuildsCorrectly
    -> Chest should extend backward with thoracic extension: chestX0=0.0, chestX1=0.0
WidePushUpPoseTest :: testWidePushUpPoseBiomechanicalCompliance
    -> Wide push-up pose has 200 validation errors!
```

## Failing classes (19) — quick reference

`AirSquatPoseTest`, `BurpeePoseTest`, `DeclinePushUpPoseTest`, `DynamicStretchPosesTest`,
`EnvironmentAnchorsTest` (2), `ExerciseValidatorTest`, `KettlebellSwingPoseTest`,
`KneePushUpPoseTest`, `LatStretchPoseTest`, `LungePosesTest` (4),
`MountainClimberPoseTest`, `NewEnginePosesTest` (8), `ReverseSnowAngelPoseTest`,
`ScapularPullUpPoseTest`, `SquatPosesTest`, `StandardPushUpPoseTest`, `StepUpPoseTest`,
`ThoracicAndHamstringStretchPosesTest`, `WidePushUpPoseTest`.

## Separately: 4 test files that do not compile (pre-existing)

`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`
fail to compile (missing `kotlin.math` imports for `.pow`/`abs`, unsupported 3-arg
`max`). Not counted above and not caused by feature work.
