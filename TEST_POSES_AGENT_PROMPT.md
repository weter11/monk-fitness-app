# Agent Prompt: Test Only Test Poses — Animation Engine Validation

## Objective
Test **exclusively** the four test poses in `ValidationPoseRegistry`:
- `test_middle_split` (MiddleSplitPose)
- `test_pike_sit` (PikeSitPose)
- `test_deep_overhead_squat` (DeepOverheadSquatPose)
- `test_dead_hang` (DeadHangPose)

These are static reference poses built **purely on the animation engine** (BaseValidationPose → PoseBuilder, SkeletonFactory, SkeletonMath, IK solvers). They are NOT production exercise poses. Their purpose: stress-test the engine and expose architectural shortcomings.

## Requirements

### 1. Test Only Test Poses
- Do NOT test production poses (NewEnginePosesTest.kt, BasePoseFrameworkTest.kt, etc.)
- Do NOT touch workout/catalog logic
- Run validation against the four poses above and nothing else

### 2. Zero Hacks in Poses
- Every pose must use ONLY public engine APIs (SkeletonMath.solveIK, bakeIkLimb, buildPelvis, buildShoulders, etc.)
- No hardcoded joint positions that bypass IK
- No magic numbers compensating for engine bugs
- If a pose "needs a hack" to look correct → that is a bug in the engine, not the pose. Fix the engine.

### 3. Bug-Free Poses
- Each pose must pass **ExerciseValidator** with zero ERROR-level issues
- Acceptable: WARNING on ACCELERATION_SPIKE for static poses (no history)
- Unacceptable: any ERROR on FINITE_COORDINATES, BONE_LENGTH, IK_CONSTRAINT_LIMIT, FOOT_GROUND_PENETRATION, HAND_SLIDING, POSITION_DISCONTINUITY, STATIC_SUPPORT_POLYGON, BILATERAL_SYMMETRY, HAND_SHOULDER_ALIGNMENT, IK_TARGET_UNREACHABLE

### 4. Fix Engine Bugs In-Place
- If validation fails due to engine logic (IK solver, bone length validation, support polygon, symmetry check, etc.), fix the engine code
- Do NOT work around engine bugs in the poses

### 5. Identify Engine Shortcomings
Document every engine limitation discovered:
- Missing IK features (pole vector handling, joint limits, soft constraints)
- Missing validation rules (e.g., no scapula tracking, no ground reaction force)
- Architecture gaps (no temporal coherence for static poses, no procedural foot placement, no dynamic balance solver)
- API gaps (no way to specify "foot flat on ground" vs "heel/toe contact", no center-of-mass projection helper)

## Execution Steps

### Step 1: Create Validation Test File
Create `/app/src/test/java/com/monkfitness/app/ValidationPosesTest.kt` that:
- Iterates all four poses from `ValidationPoseRegistry.ids`
- Builds each pose at `progress = 0f` (static)
- Runs `ExerciseValidator` with strict config:
  ```kotlin
  ValidatorConfig(
      allowFootGroundPenetration = false,
      isStaticExercise = true,
      checkBilateralSymmetry = true,
      checkHandShoulderAlignment = true,
      checkIkTargetReachability = true,
      expectedSupportJoints = setOf(...) // per pose metadata.support.contacts
  )
  ```
- Asserts `report.isValid == true`
- Collects all ERROR issues per pose

### Step 2: Run Tests & Capture Failures
```bash
./gradlew :app:test --tests "com.monkfitness.app.ValidationPosesTest"
```

### Step 3: For Each Failure
1. **Reproduce** - isolate the exact joint/rule causing the error
2. **Diagnose** - trace to root cause in engine (IK solver, SkeletonMath, validator logic, etc.)
3. **Fix Engine** - minimal targeted fix in animation package
4. **Re-run** - verify pose now passes
5. **Document** - add entry to `ENGINE_SHORTCOMINGS.md` (create this file)

### Step 4: Stress Test Edge Cases
For each pose, also test:
- Mirrored side (`side = Side.RIGHT`)
- Different skeleton definitions (DEFAULT_ADULT, DEFAULT_CHILD if exists)
- Progress interpolation (0.0, 0.25, 0.5, 0.75, 1.0) even though poses are static — verify no discontinuities

### Step 5: Produce Final Report
Create `ENGINE_SHORTCOMINGS.md` with:

```markdown
# Animation Engine Shortcomings Discovered During Test Pose Validation

## Critical (Block Test Poses)
| Area | Issue | Root Cause | Fix Applied | File(s) Changed |
|------|-------|------------|-------------|-----------------|
| IK Solver | ... | ... | ... | ... |
| Bone Validation | ... | ... | ... | ... |

## Major (Limit Pose Expressiveness)
| Area | Missing Capability | Workaround Needed | Proposed API |
|------|-------------------|-------------------|--------------|
| Foot Contact | No "flat foot" constraint | Manual heel/toe positioning | `FootContactMode.FLAT` |
| Scapula | No scapular rhythm | Hardcoded shoulder offsets | `ScapulaDriver` |
| COM | No center-of-mass projection | Manual pelvis placement | `projectCOM(supportPolygon)` |

## Minor (Nice to Have)
...
```

## Key Engine Files to Inspect/Fix
- `SkeletonMath.kt` — IK solver, NearStraightLimb, bone math
- `ExerciseValidator.kt` — all 13 validation rules
- `SkeletonFactory.kt` — skeleton topology, joint definitions
- `SkeletonDefinition.kt` — bone lengths, constraints, foot/hand geometry
- `BaseValidationPose.kt` / `BasePose.kt` — shared helpers
- `IKConstraint.kt` — min/max extension, flexion angles
- `SupportDefinition.kt` / `SupportContact.kt` — contact points
- `MotionDrivers.kt` — interpolation curves (should not affect static poses)

## Validation Checklist Per Pose

### Middle Split
- [ ] Bilateral symmetry (hips, knees, ankles, shoulders, elbows)
- [ ] Feet on ground (y=0), no penetration
- [ ] Arms symmetric, shoulders level
- [ ] Pelvis centered in support polygon
- [ ] IK chains within limits (hip→knee→ankle, shoulder→elbow→hand)

### Pike Sit
- [ ] Hamstring geometry (knee near 0° flexion)
- [ ] Spine fold angle consistent
- [ ] Arms reach toes without IK clamp
- [ ] Feet flat, no penetration
- [ ] Pelvis stable on ground

### Deep Overhead Squat
- [ ] Ankle dorsiflexion (knees forward past toes)
- [ ] Thoracic extension (chest up)
- [ ] Overhead arms (full shoulder flexion)
- [ ] Feet flat, heels down
- [ ] Pelvis depth valid
- [ ] Bilateral symmetry

### Dead Hang
- [ ] Hands FIXED on bar (no sliding)
- [ ] Arms fully extended (elbow ~180°)
- [ ] Shoulders depressed (not shrugged)
- [ ] Body hangs vertically
- [ ] Legs relaxed, slight forward pendulum
- [ ] Support polygon = hands on bar

## Success Criteria
- All 4 test poses pass ExerciseValidator with zero ERRORs
- No hacks added to any pose file
- All engine fixes are minimal, targeted, and have no regressions on existing pose tests
- `ENGINE_SHORTCOMINGS.md` documents every architectural gap found

## Constraints
- Max 2-minute timeout per test run
- Do NOT modify production pose tests
- Do NOT modify workout/catalog code
- Only touch: validation poses, animation engine, new test file, report file