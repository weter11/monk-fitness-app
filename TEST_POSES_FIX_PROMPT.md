# Agent Prompt: Fix Test Poses Using Current Engine Only

## Objective
Fix any bugs in the **4 validation poses** using **only current engine capabilities** — no engine modifications, no hacks, no workarounds.

## Target Poses (in `ValidationPoseRegistry`)
- `test_middle_split` → `MiddleSplitPose.kt`
- `test_pike_sit` → `PikeSitPose.kt`
- `test_deep_overhead_squat` → `DeepOverheadSquatPose.kt`
- `test_dead_hang` → `DeadHangPose.kt`

## Constraints
- **Do NOT modify any engine code** (animation package, SkeletonMath, ExerciseValidator, etc.)
- **Do NOT add hacks** (magic numbers compensating for engine bugs, hardcoded joint positions bypassing IK)
- **Only fix pose code** using public engine APIs: `SkeletonMath.solveIK`, `bakeIkLimb`, `buildPelvis`, `buildShoulders`, `buildHead`, `solveNearStraightLimb`
- Use only what `BaseValidationPose` provides

## What to Fix in Poses
- Incorrect IK target positions (unreachable, wrong side)
- Wrong pole vectors causing flipped elbows/knees
- Bone length violations (joint positions not matching definition)
- Ground penetration (feet/hands below ground level)
- Asymmetry where symmetry is intended
- Hand/foot orientation errors
- Support contact mismatches with metadata

## Execution
1. Run validation test (create temp test if needed) to find pose bugs
2. For each bug: fix the pose using correct engine API usage
3. Verify pose builds without errors using current engine
4. Do not touch: `BaseValidationPose`, `SkeletonMath`, `ExerciseValidator`, `SkeletonFactory`, `SkeletonDefinition`, `IKConstraint`, any animation/* files

## Success Criteria
- All 4 poses build correctly with current engine
- No pose contains hardcoded positions that should come from IK
- No pose contains magic numbers compensating for engine behavior
- Poses use only `bakeIkLimb`, `solveNearStraightLimb`, `build*` helpers