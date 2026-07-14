# ENGINE.md — Animation Engine Architecture

> Part of the project constitution. This document describes how the animation
> engine is *intended* to work. It is the source of truth for architectural
> decisions. Where an implementation detail is mentioned, it exists only to
> illustrate a general rule, not to freeze a specific implementation.

---

## 1. Philosophy

The engine exists to **solve motion**, not to describe it.

A skeleton is a hierarchy of joints. A pose is a request for where certain
parts of the body should be. The engine's job is to turn that request into a
complete, anatomically consistent, physically plausible skeleton every frame,
without the caller having to reason about forward kinematics, world transforms,
foot/hand geometry, or projection.

The engine is:

- **Deterministic** — the same inputs produce the same skeleton.
- **Allocation-conscious** — hot paths reuse scratch buffers and never allocate
  per frame.
- **Anatomy-agnostic at the math layer** — the solver knows about bones and
  constraints, not about "push-ups" or "squats".
- **Data-driven at the pose layer** — exercises are configuration on top of
  shared, reusable engine primitives.

The engine never knows *why* a body is moving. It only knows *how* to make a
requested configuration real and correct.

---

## 2. The Four Layers

The system is separated into four strictly-ordered layers. Each layer may
depend only on the layers below it. Crossing these boundaries is an
architectural violation.

```
Exercise      (metadata: what it is, how it's presented)
   │  configures
Pose          (biomechanics: how the body should move)
   │  requests
Engine        (motion: FK, IK, geometry, projection)
   │  produces
Validation    (verification: is the produced skeleton correct?)
```

### Engine — *solves motion*
Pure geometry and kinematics. Two-bone IK, forward kinematics, rotation math,
foot/hand completion, projection, rendering primitives. Knows bones, joints,
constraints, and world space. Knows nothing about a specific exercise.

### Pose — *describes biomechanics*
A `PoseBuilder` that, given a `PoseContext` (progress, side, definition),
returns a `SkeletonPose`. It expresses *intent*: "the hands are fixed on the
bar, the body is pulled up toward them; the earliest motion is scapular." It
calls engine primitives to realize that intent. It does not re-implement them.

### Exercise — *describes metadata*
Naming, family, grip, motion type, camera, environment, loop mode, duration,
support configuration. This is descriptive data carried in `PoseMetadata` and
the surrounding catalog. It never contains motion math.

### Validation — *verifies correctness*
A read-only observer (`ExerciseValidator`) that inspects a finished
`SkeletonPose` and reports whether it obeys the rules of the engine and of
anatomy. It never mutates the pose and never participates in producing it.

---

## 3. Core Responsibilities

### `SkeletonFactory`
Constructs the joint hierarchy (`SkeletonNode` tree) for a given topology.
It owns the *shape of the tree*, not the pose. It provides named accessors
(`SkeletonNodes`) so poses reference joints by role (`chest`, `shoulderA`,
`hipF`) rather than by index.

- `createStandardSkeleton()` — pelvis-rooted upright humanoid.
- `createPushUpSkeleton()` — the same body re-rooted at the planted foot, so a
  fixed ground contact becomes the root of forward kinematics.

Re-rooting is the sanctioned way to express "this contact is fixed and the rest
of the body hangs off it." New topologies belong here, created once and reused,
never duplicated inside individual poses.

### `BasePose`
The shared base class for all production poses. It consolidates:

- reusable scratch buffers (allocation-free),
- body-construction helpers (`buildTorso`, `buildShoulders`, `buildPelvis`,
  `buildHead`, `buildRigidSegment`),
- IK wrappers (`solveArmIK`, `solveLegIK`, `bakeIkLimb`,
  `solveNearStraightLeg`),
- motion-driver and support-contact helpers.

`BasePose` is where cross-exercise commonality lives. Family base classes
(e.g. a vertical-pull base) extend it; concrete exercises extend the family
base and supply only the numbers and overrides that make them unique.

### `SkeletonMath`
The stateless math core: `Vector3`, rotations (Rodrigues / axis-angle),
rotation matrices, the analytical two-bone IK solver, near-straight-limb
solving, and local↔world direction transforms. It allocates nothing on hot
paths (results are written into caller-supplied buffers). It has no knowledge
of joints, poses, or exercises — only vectors, lengths, angles, and
constraints.

### `SkeletonPoseFinalizer`
Runs after a pose is built and before projection. It:

- executes the forward-kinematics traversal so every joint has correct world
  position and rotation,
- completes anatomy the pose does not author directly — feet (heel/toe with
  pitch clamping) and hands (wrist/palm/knuckles/fingertips),
- guarantees the output is a complete, FK-consistent skeleton.

It supports both the modern rotation-driven path (a pose supplies a node
hierarchy) and a legacy position-driven compatibility bridge. New poses target
the rotation-driven path.

### `ExerciseValidator`
The read-only correctness authority. Given a finished pose (and optionally
previous frames, environment, and camera) it checks a fixed battery of rules:
finite coordinates, constant bone lengths, head in viewport, no ground
penetration, no hand sliding on fixed contacts, IK constraint limits, motion
continuity (position/velocity/acceleration), static support polygon, bilateral
symmetry, hand–shoulder alignment, and IK target reachability. It produces a
`ValidationReport`; it never changes the skeleton.

---

## 4. Coordinate Systems

- Positions are `Vector3` of floats in engine units (the default adult model
  uses ~centimetre-scale magnitudes, e.g. torso ≈ 120, thigh ≈ 112).
- **Y is up.** Ground level is a Y value (`GroundDefinition.level`, default 0).
  "Below ground" means `y < level`.
- **Z is depth / lateral.** The two sides of the body are separated along Z
  (shoulders at `±shoulderWidth`, hips at `±hipWidth`).
- **X is the primary long axis of the current frame.** Its world meaning
  depends on the node's frame — this is why authoring happens in local space.

### Joint naming: A/P and F/B
The skeleton is a single-plane silhouette with a near and a far limb per pair:

- **A = active / foreground** limb (left), **P = passive / background** limb
  (right) for the arms and hands.
- **F = foreground**, **B = background** for the legs and feet.

Named aliases (`shoulderB == shoulderP`, `hipA == hipF`, etc.) exist so poses
can use whichever convention is clearer. The engine treats both members of a
pair identically.

---

## 5. Local vs World Space

The engine is authored in **local space** and resolved into **world space** by
forward kinematics.

- Each `SkeletonNode` carries a `localPosition` and `localRotation` relative to
  its parent.
- FK traversal composes parent transforms down the tree to produce
  `worldPosition` and `worldRotation` for every node.
- Poses set **local** transforms (often via IK helpers that *bake* an IK result
  into local offsets). The finalizer performs the traversal.

The rule: **poses describe the body relative to its own frame; the engine
places that frame in the world.** This keeps poses stable when a parent frame
(a leaning torso, a twisting thorax, a re-rooted foot) moves.

---

## 6. Frame-Relative Pole Vectors

The two-bone IK solver needs a **pole vector** to decide which way a knee or
elbow bends. The project's rule is that **poles are authored in the limb
root's local frame** (chest for arms, pelvis for legs) and transformed into
world space using the parent's current rotation before solving.

Why this matters:

- A world-space pole becomes wrong the moment the parent frame rotates,
  producing pole flips, uneven arms, and popping elbows.
- A frame-relative pole "follows the body": as the thorax twists or the torso
  leans, the elbow/knee direction stays anatomically consistent.

The analytical solver itself is unchanged; the frame-relative overloads simply
convert the pole (`toWorldDirection`) before calling it. **All new poses use
frame-relative poles.**

---

## 7. IK Philosophy

- **Analytical, not iterative.** The two-bone solver is closed-form: given a
  root, a target, two lengths, a pole, and a constraint, it produces the middle
  and end joints directly. It is fast, stable, and deterministic.
- **Biologically clamped.** The solver enforces a minimum flexion angle and a
  maximum extension ratio (< 1.0, so a limb is never perfectly straight/locked).
  Requests outside these limits are clamped, and the clamp amount is recorded so
  validation can detect unreachable targets.
- **Targets are honest.** IK is driven by *real* biomechanical targets (where a
  hand grips a bar, where a foot is planted). Targets are never nudged to hide
  a solver artifact.
- **Baking.** `bakeIkLimb` converts a solved world-space chain into the local
  offsets the FK hierarchy expects, so the result flows naturally through the
  rest of the pipeline.
- **Near-straight limbs** use a dedicated flexion-angle solve rather than a
  linear extension ratio, because visual straightness is non-linear near full
  extension.

---

## 8. Joint Hierarchy

The canonical tree is pelvis-rooted:

```
PELVIS
├── CHEST
│   ├── NECK_END ── HEAD_POS
│   ├── SHOULDER_A ── ELBOW_A ── HAND_A ── (WRIST_A) ── PALM_A ── KNUCKLES_A ── FINGERTIPS_A
│   └── SHOULDER_P ── ELBOW_P ── HAND_P ── (WRIST_P) ── PALM_P ── KNUCKLES_P ── FINGERTIPS_P
├── HIP_F ── KNEE_F ── ANKLE_F ── {HEEL_F, TOE_F}
└── HIP_B ── KNEE_B ── ANKLE_B ── {HEEL_B, TOE_B}
```

Alternative topologies (e.g. the push-up skeleton) re-parent the same joints so
a fixed contact becomes the root. The **set of joints is fixed** (`Joint`
enum); only the **parenting** changes between topologies. Hands and feet have
sub-segments (palm/knuckles/fingertips, heel/toe) that the finalizer completes.

---

## 9. Support Contacts

A `SupportContact` declares that a body point (`SupportPoint`: feet, toes,
knees, hands, forearms, hips, custom) is in contact with the environment. A
contact can support weight, be fixed in place, have friction, a height offset,
and an optional environment anchor.

A pose declares its support configuration and `PivotType` (which point the body
pivots around — feet, hands, etc.) in its `PoseMetadata`. Support declarations:

- tell validation which points must not slide or penetrate,
- express the biomechanical fact "these parts are planted",
- inform which topology / rooting is appropriate.

Support is *declared* by the pose and *enforced* by validation. The engine does
not invent contacts.

---

## 10. Camera and Environment Ownership

Camera and environment are **owned by the pose's `PoseMetadata`**, not by the
engine core and not by the rendering surface.

- `PoseMetadata.camera` (`CameraDefinition`) defines the viewpoint for that
  pose.
- `PoseMetadata.environment` (`EnvironmentDefinition`) defines ground, props
  (box/step/bench/wall), and anchors.

The engine *consumes* these to project and to validate (e.g. head-in-viewport,
ground penetration, support on props), but it does not define them. This keeps
each exercise self-describing: the same engine renders a hanging pull-up and a
grounded squat purely from metadata differences.

---

## 11. What Belongs Inside the Engine

- Vector / rotation / matrix math.
- Forward kinematics traversal and world-transform computation.
- The analytical IK solver and its constraints.
- Skeleton topology construction (`SkeletonFactory`).
- Anatomical completion (feet, hands) in the finalizer.
- Projection and rendering primitives.
- Shared, reusable pose scaffolding in `BasePose`.
- Read-only validation rules.

## 12. What NEVER Belongs Inside the Engine

- Knowledge of a specific exercise (no "if push-up" branches in the math core).
- Workout, statistics, progression, achievement, or scheduling logic.
- Magic constants that exist to patch one exercise.
- Compensations that hide a solver or FK bug (e.g. moving a target to mask an
  IK artifact, translating the pelvis to fake a joint that should move).
- Per-frame allocations on hot paths.
- UI/state concerns beyond the minimal animation controller contract.
- Mutations performed by the validator.

---

## 13. Summary

- **Engine solves motion.**
- **Pose describes biomechanics.**
- **Exercise describes metadata.**
- **Validation verifies correctness.**

Keep these responsibilities separate, author in local space, drive IK with
honest frame-relative targets, and let the engine — not individual poses — own
the hard geometry.
