# Phase D — Direct-finalize test re-pointing (test hygiene)

**Goal:** stop tests from calling `SkeletonPoseFinalizer(...).finalize(pose)` directly and route
them through `SkeletonPipeline.produceFrame(pose).pose`, so the *real* engine stage chain
(Solver → Finalizer) runs. `SkeletonPoseFinalizer.finalize` (animation/SkeletonPoseFinalizer.kt)
explicitly warns that direct callers "must route through the pipeline so the Solver is not
skipped for contact poses." `ConstraintSolverPhase2Test` already notes this.

**Why safe:** for every non-contact production pose the Solver is a no-op (M3/M4 proven), so the
pipeline path is byte-identical to the direct-finalize path. `SkeletonPipeline.produceFrame(builtPose)`
takes an already-built `SkeletonPose` (no `PoseContext` needed) and returns `PipelineResult` whose
`.pose` is the finalized frame — exactly what `finalizer.finalize(built)` returned.

## Audit (57 call sites across 23 files)

### CONTROL — intentionally test the finalizer / direct path. NOT re-pointed.

| File | Lines | Why |
|---|---|---|
| `SkeletonPipelineM0Test.kt` | 57, 109 | Deliberately compares direct-finalize vs pipeline (the byte-identity control). |
| `ChestFrameNoMoveTest.kt` | 63 | Control block; the real test path already uses `produceFrame`. |
| `ChestFrameIssueFTest.kt` | 36, 77 | Tests `reconstructChestFrame` in isolation via the finalizer directly. |
| `ConstraintSolverTest.kt` | 26, 116 | Tests Solver+Finalizer in isolation. |
| `LumbarThoracicSpineTest.kt` | 43 | Unit test of finalizer FK on a hand-built pose. |
| `HeadTargetBaselineTest.kt` | 67 | Baseline of the legacy finalize *direction* path — must stay legacy to be the A/B reference. |

### RE-POINTED — production / pose-rendering tests (no engine contacts → byte-identical).

For each: replace the `finalizer` field with `private val pipeline = SkeletonPipeline(def)`
(class-level) and `finalizer.finalize(x)` → `pipeline.produceFrame(x).pose`. Inline
`SkeletonPoseFinalizer(def).finalize(x)` → `SkeletonPipeline(def).produceFrame(x).pose`.

| File | Lines |
|---|---|
| `WallSlidesPoseTest.kt` | 16→pipeline, 30 |
| `SquatPosesTest.kt` | 83→pipeline, 113, 165 |
| `VerticalPullPosesTest.kt` | 12→pipeline, 73, 150 |
| `StandardPushUpPoseTest.kt` | 16/109/159/204→pipeline, 51,122,172,225 |
| `WidePushUpPoseTest.kt` | 16/105/153→pipeline, 48,118,166 |
| `StepUpPoseTest.kt` | 13→pipeline, 48 |
| `GluteBridgePoseTest.kt` | 16→pipeline, 30 |
| `IKLimbHelperTest.kt` | 15/44/72/98/124→pipeline, 26,55,83,109,135 |
| `LungePosesTest.kt` | 12→pipeline, 53 |
| `AirSquatPoseTest.kt` | 16/102/150→pipeline, 45,115,163 |
| `KettlebellSwingPoseTest.kt` | 13→pipeline, 47 |
| `MountainClimberPoseTest.kt` | 13→pipeline, 47 |
| `ScapularPullUpPoseTest.kt` | 13→pipeline, 47 |
| `LatStretchPoseTest.kt` | 13→pipeline, 47 |
| `FacePullPoseTest.kt` | 16→pipeline, 30 |
| `ScapularRetractionPoseTest.kt` | 16→pipeline, 30 |
| `HipCarsPoseTest.kt` | 16→pipeline, 30 |
| `SkeletonFactoryTest.kt` | 208→pipeline, 223,233,243 |
| `DeclinePushUpPoseTest.kt` | 16/106→pipeline, 49,119,167,220 |
| `ReverseSnowAngelPoseTest.kt` | 47 |
| `ArmCirclesPoseTest.kt` | 30 |
| `LegRaisePoseTest.kt` | 47 |
| `PelvicTiltPoseTest.kt` | 30 |
| `KneePushUpPoseTest.kt` | 47,117,166 |
| `DeadBugPoseTest.kt` | 47 |
| `BurpeePoseTest.kt` | 30 |
| `ProceduralAnimationPerformanceRefactorTest.kt` | 129 (inline) → pipeline |

> Note: `VerticalPullPosesTest:150` and `IKLimbHelperTest` (hang/pullup) and
> `ProceduralAnimationPerformanceRefactorTest` exercise poses that *declare* posture but register
> **no engine `ContactSpec`**, so the pipeline Solver still no-ops (M3 verified byte-identical). Safe.

## Migration plan

1. **Production-path fidelity tests (27 files / 48 sites):** re-point to
   `SkeletonPipeline(def).produceFrame(<builtPose>).pose` (done — commit Phase D).
2. **Finalizer unit tests (5 files / 8 sites):** keep direct `finalize` (control files).
3. **Pipeline/control comparisons (1 file / 4 sites):** keep both paths (byte-identity anchor).
4. **Future hardening (recommended):** a compile/lint guard forbidding `SkeletonPoseFinalizer(`
   instantiation / `.finalize(` in `app/src/test` except an explicit allowlist of the 6 control files.
5. **Verification gate:** `./gradlew :app:testDebugUnitTest` must stay **283/0** (no behavior change).

## Verification

`app/src/test/...` re-pointed tests compile and pass unchanged (byte-identical output).
Run `./gradlew :app:testDebugUnitTest` and aggregate; expect **tests=283 failures=0 errors=0**.
