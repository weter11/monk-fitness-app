# Static Test Poses — Biomechanics Validation Suite

Four canonical, **fully static** reference poses built exclusively to validate the
modern animation engine (`SkeletonFactory`, `BasePose`, `bakeIkLimb`,
frame-relative pole vectors, `SkeletonPoseFinalizer`, corrected arm
proportions, corrected IK).

These are **not** exercises. Every pose has `durationSeconds = 0`, `loopMode =
HOLD`, `motionCurve = LINEAR`, and its `build()` ignores `PoseContext.progress`.
There is no breathing, no sway, no interpolation and no animation driver — they are
frozen snapshots so any defect is visually obvious instead of being hidden by motion.

Package: `com.monkfitness.app.poses_for_tests`
Registered ids: `test_middle_split`, `test_pike_sit`, `test_deep_overhead_squat`,
`test_dead_hang` (UI family: **For Tests**).

---

## 1. MiddleSplitPose (`test_middle_split`)

**Engine systems validated**
- `SkeletonFactory.createStandardSkeleton()` — standard humanoid hierarchy.
- `BasePose.buildHead` / `buildPelvis` / `buildShoulders`.
- `bakeIkLimb(..., poleLocal, parentRotation, ...)` for both legs and both arms
  (frame-relative pole vectors, parent rotation = identity because the spine is
  perfectly upright).
- `SkeletonPoseFinalizer` — FK flatten + foot/hand detail (heel/toe/palm/knuckles/
  fingertips).
- Corrected IK + corrected leg/arm proportions (reach kept just inside the
  extension clamp so the limbs read as fully straight).

**Joints intentionally stressed**: hips (max abduction), knees (locked),
ankles (neutral/forward), shoulders (horizontal abduction), elbows (locked),
wrists (palms down).

**Architectural problems it would immediately reveal**
- Pelvis asymmetry / off-center pelvis (hips must sit symmetrically at ±hipWidth).
- Hip rotation / femoral torsion errors (legs must stay in the frontal plane).
- Spine tilt (chest world position must sit directly above pelvis).
- Shoulder asymmetry (both arms must reach the identical lateral distance).
- Arm-length errors (T-pose reach exposes upper-arm + forearm sum).
- Left/right symmetry and coordinate-space mistakes (mirror must be exact).

---

## 2. PikeSitPose (`test_pike_sit`)

**Engine systems validated**
- `SkeletonFactory` + `BasePose` helpers + `SkeletonPoseFinalizer`.
- `bakeIkLimb` with frame-relative poles for forward legs and forward arms.
- Corrected IK for the posterior chain (hip flexion + straight knees) and
  shoulder flexion.

**Joints intentionally stressed**: hips (flexion), knees (locked), ankles
(neutral), lumbar + thoracic (upright), shoulders (flexion), elbows (locked),
wrists (palms inward).

**Architectural problems it would immediately reveal**
- Hip-flexion / hamstring geometry errors (legs must stay straight forward).
- Spinal alignment drift (torso must remain vertical, not folding forward).
- Shoulder-flexion / arm-reach errors.
- Wrist orientation (palms must face inward, toward the body centerline).
- Pelvis placement (seated height must be consistent with leg length).

---

## 3. DeepOverheadSquatPose (`test_deep_overhead_squat`)

**Engine systems validated**
- `SkeletonFactory` + `BasePose` helpers + `SkeletonPoseFinalizer`.
- `bakeIkLimb` for a deep squat (wide-stance legs) **and** overhead arms
  simultaneously — the only pose that stresses nearly every joint at once.
- Corrected IK + corrected proportions across the whole body.

**Joints intentionally stressed**: ankles (mobility), knees (tracking over
feet), pelvis (depth, below knees), lumbar (compensation check), thoracic
(extension), shoulders (overhead mobility), elbows (locked), wrists (neutral).

**Architectural problems it would immediately reveal**
- Ankle-mobility / knee-tracking errors (knees must stay over the feet).
- Pelvis position / lumbar compensation (pelvis must drop below the knees
  without the spine folding).
- Thoracic mobility / chest orientation errors.
- Shoulder-mobility / wrist-rotation errors under load of the overhead lockout.
- Whole-body balance and proportion errors (any single bad segment ruins the
  closed-chain pose).

---

## 4. DeadHangPose (`test_dead_hang`)

**Engine systems validated**
- `SkeletonFactory` + `BasePose` helpers + `SkeletonPoseFinalizer`.
- `bakeIkLimb` for **fixed** hand targets on a bar (constant targets — the body
  is derived from the IK solve, the hands never move) and a relaxed hanging
  pendulum for the legs.
- `EnvironmentDefinition` / `EnvironmentAnchor` (the bar) and `SupportDefinition`
  (`HANDS` pivot attached to the bar anchor).
- Corrected IK + corrected arm proportions under a fixed contact.

**Joints intentionally stressed**: shoulder girdle (geometry + natural
elevation), arms (straight-arm reach to the bar), IK (hands must not detach),
wrists (grip orientation), scapular / clavicle behaviour, ankles/feet (relaxed).

**Architectural problems it would immediately reveal**
- IK correctness at a fixed contact (if the hand detaches from the bar, the IK
  clamp / anchor solve is broken).
- Arm-proportion errors (straight-arm reach must land exactly on the bar).
- Shoulder-girdle / clavicle errors (grip width must equal the shoulder spread).
- Wrist / grip-orientation errors.
- Coordinate-space mistakes (bar is at a fixed world Y; the whole body height is
  derived from it).

---

## Remaining limitations discovered while building the poses

1. **Hand-stub convention is cosmetic.** The engine's `SkeletonPoseFinalizer`
   skips its own hand-orientation pass whenever `PALM_*`/`FINGERTIPS_*` nodes
   exist (which they always do in the standard skeleton). We therefore set the
   palm/knuckles/fingertips local offsets manually, matching the rest of the
   codebase. Fine wrist/palm orientation (true "palms down", "palms inward",
   overhand grip rotation) is only approximated by the short fingertip stub; the
   stub direction is best-effort, not a full hand mesh. This is consistent with
   every other pose in the project and does not affect joint-position validation.

2. **Extension clamp (0.98 ratio).** `IKConstraint` maximum-extension ratio is
   `0.98`, so a "fully locked" limb can never be 100% straight — it stops at 98%
   of (L1+L2). Targets are authored just inside this clamp so `maxIkClampAmount`
   stays ~0 and the limb reads as straight. A pose demanding literally 100%
   extension would intentionally show a small clamp.

3. **Thoracic extension / external shoulder rotation** in the overhead squat are
   approximated by keeping the torso perfectly upright and arms straight rather
   than adding a dedicated back-bend or humeral-rotation transform. The pose still
   stresses those joints; the subtlety of the rotation is a known simplification.

4. **No animation metadata, and intentionally not registered as exercises.**
   The poses carry only camera / environment / support definitions needed for
   correct rendering. Per the architecture requirements ("No exercise
   registration"), they are **not** added to the workout/exercise catalog, so
   they never appear in generated workouts or exercise metadata. They surface in
   the UI through the animation engine's pose registry — appended after the
   existing poses in `AnimationRegistry` and `PoseRegistry`, grouped as the
   dedicated **For Tests** family (`test_middle_split`, `test_pike_sit`,
   `test_deep_overhead_squat`, `test_dead_hang`). An engineer opens them
   directly by id; they render through the modern 3D engine exactly like every
   other pose.
