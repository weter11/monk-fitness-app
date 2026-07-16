# ENGINE_ARCHITECTURE.md — Animation Engine Architecture

> **Primary reference document for the animation engine.**
>
> This document explains *how the engine is built and why*, so a new engineer or
> AI agent can understand it without reading every source file. It is architecture,
> not a line-by-line walkthrough. Where a class or file is named, it is named to
> anchor a concept, not to freeze an implementation.
>
> Companion documents (the project constitution) cover related ground:
> - `docs/ENGINE.md` — layer responsibilities, coordinate systems, IK philosophy.
> - `docs/BIOMECHANICS.md` — the movement principles poses must honor.
> - `docs/CODING_RULES.md` — permanent rules for contributors.
> - `docs/VALIDATION.md` — validation poses and the Engineering Validation subsystem.
>
> This document supersedes and consolidates those for the purpose of onboarding.
> Where it differs, `CODING_RULES.md` wins for contributor rules.

---

## 1. Design Goals

The engine was built to render human movement for a fitness app, but its design
choices are deliberate and general:

- **Deterministic animation.** The same inputs (pose, progress, side, definition)
  always produce the same skeleton. No randomness, no frame-order dependence.
- **Zero allocations during animation.** Hot paths reuse persistent scratch
  buffers (`Vector3`, `JointRotation`, matrix-column arrays). Results are written
  into caller-supplied outputs rather than returned as new objects. This keeps the
  render loop allocation-free and GC-pause-free.
- **Procedural animation.** Motion is described by rules and math, not by sampled
  keyframes. A pose is a *function* of progress; the engine computes the body at
  any progress value.
- **Reusable pose builders.** Common body-construction logic lives once
  (`BasePose` and family bases). A concrete exercise is mostly *configuration*
  (lengths, angles, poles, camera, support) layered on shared scaffolding.
- **Anatomical correctness.** The skeleton models real joints with real ranges.
  The pelvis stabilizes by default; the scapula drives pulling; the thorax follows
  the shoulder girdle. Fake motion (translating the pelvis to mimic a joint) is
  forbidden (see `docs/BIOMECHANICS.md`).
- **IK-driven limbs.** Arms and legs are solved analytically (two-bone closed-form
  IK), not posed joint-by-joint. Targets are honest biomechanical positions.
- **Lightweight runtime.** The engine is plain Kotlin math plus a Compose
  `Canvas`. No game engine, no scene graph, no retained-mode renderer.
- **No physics engine dependency.** There is no physics simulation, no gravity,
  no collision solver in the runtime. "Planted" and "balanced" are *declared*
  constraints checked by validation, not emergent physics.

The unifying goal: **the engine solves motion; an exercise describes metadata;
a pose describes biomechanics; validation verifies correctness.** Keep these
separated.

---

## 2. High-Level Pipeline

Every animated frame flows through a fixed, ordered pipeline. Each stage has one
job and depends only on the stages before it.

```
PoseBuilder
    ↓
SkeletonFactory
    ↓
PoseBuilder.build()        (authors local transforms + IK)
    ↓
IK                         (analytical two-bone solve, baked to local offsets)
    ↓
ConstraintSolver          (satisfies fixed contacts + posture, between IK and FK)
    ↓
SkeletonPoseFinalizer     (FK traversal + anatomical completion + chest frame)
    ↓
Renderer                  (project + draw on Canvas)
```

### PoseBuilder
The contract every pose implements: `build(context: PoseContext): SkeletonPose`.
`PoseContext` carries animation progress (already run through the motion curve),
side, mirroring, timing, and the `SkeletonDefinition` (shared body proportions).
`PoseBuilder` itself is the thin interface; production poses extend `BasePose`.

### SkeletonFactory
Constructs the **topology** — the joint hierarchy (`SkeletonNode` tree) for a
given rooting. It owns the *shape of the tree*, not the pose. Named accessors
(`SkeletonNodes`) let a pose reference joints by role (`chest`, `shoulderA`,
`hipF`, `lumbar`) instead of by index. Two standard topologies exist:
`createStandardSkeleton()` (pelvis-rooted upright humanoid) and
`createPushUpSkeleton()` (same body re-rooted at the planted foot so a fixed
ground contact becomes the FK root). New topologies are added here, once, and
reused — never assembled inside individual poses.

### PoseBuilder.build()
Given a context, the pose obtains a skeleton from the factory, then **authors the
body in local space** using `BasePose` helpers (`buildPelvis`, `buildChestOrientation`,
`buildHipRotation`, `buildShoulders`, `buildHead`, `buildTorso`, etc.). Where a limb
must reach a target, it calls the IK wrappers (`solveArmIK`, `solveLegIK`,
`bakeIkLimb`), which solve and **bake** the result into the limb's parent local
frame and register any support contacts. The output is a `SkeletonPose` whose
`roots` reference the live node hierarchy.

### IK
The analytical two-bone solver turns a root, a target, two bone lengths, a pole
vector, and an `IKConstraint` into middle- and end-joint world positions, which
`bakeIkLimb` converts into the local offsets the FK hierarchy expects. The solver
is closed-form, deterministic, and biologically clamped (minimum flexion, maximum
extension below full lock). Near-straight limbs use a dedicated flexion-angle
solve because visual straightness is non-linear near full extension.

### ConstraintSolver
Runs **after IK but before forward kinematics**. Because the engine is otherwise
purely local (each limb solved in isolation), inconsistent geometry can leave a
"planted" hand floating, a foot penetrating the ground, or a knee folded the wrong
way. The ConstraintSolver reconciles the authored pose with its declared support
contacts: it may re-bake contact limbs, apply a pelvis tilt for balance, and run a
damped CCD pass that distributes residual error into free joint angles while
regularizing toward the authored shape. It only acts when the pose declares
contacts.

### SkeletonPoseFinalizer
Runs after build (and after `ConstraintSolver` if contacts exist). It:
1. Performs the **forward-kinematics traversal** so every joint has correct
   `worldPosition` and `worldRotation`.
2. Reconstructs the chest frame from the spine direction + shoulder line (only when
   the chest is unauthored; preserves authored thoracic rotation otherwise).
3. **Completes anatomy** the pose did not author directly — feet (heel/toe with
   pitch clamping) and hands (wrist/palm/knuckles/fingertips).
4. Guarantees the output is a complete, FK-consistent skeleton.

### Renderer
`SkeletonProjector` converts the finalized 3D `SkeletonPose` into a screen-space
`ProjectedSkeleton` through the pose's `CameraDefinition`, reusing preallocated
buffers. `SkeletonRenderer` (a Compose `@Composable`) then depth-sorts and draws
bones, joints, torso faces, ground, and environment on a `Canvas`. It never infers
rotations from positions; the chest world rotation is the source of truth for the
torso silhouette. `ScreenSpaceCompensation` applies perspective scale. Rendering
is a passive consumer of the finalized pose.

> The flow is driven by `AnimationController`, which advances `progress`/`side`
> (loop modes: LOOP, HOLD, PING_PONG, ONCE) and feeds the `PoseContext`. The
> validator (`ExerciseValidator`) runs *independently* as a developer QA tool, not
> on the hot path.

---

## 3. Skeleton

### Hierarchy
The skeleton is a tree of `SkeletonNode`s. Each node carries a `localPosition` and
`localRotation` relative to its **parent**, and (after FK) a `worldPosition` and
`worldRotation`. Parent-child relationships define which joints move together.

The canonical joint set is the `Joint` enum (`Joint.kt`), 33 contiguous entries
(`entries.size = 33`, indices `0..32`). The standard pelvis-rooted tree:

```
PELVIS(0)
├── LUMBAR(32)                         (lower-spine segment; pass-through by default)
│   └── CHEST(11)
│       ├── NECK_END(26) ── HEAD_POS(27)
│       ├── CLAVICLE_A(28) ── SCAPULA_A(29) ── SHOULDER_A(12)
│       │     └── ELBOW_A(14) ── HAND_A(15) ── WRIST_A(16) ── PALM_A(17)
│       │           └── KNUCKLES_A(18) ── FINGERTIPS_A(19)
│       └── CLAVICLE_P(30) ── SCAPULA_P(31) ── SHOULDER_P(13)
│             └── ELBOW_P(20) ── HAND_P(21) ── WRIST_P(22) ── PALM_P(23)
│                   └── KNUCKLES_P(24) ── FINGERTIPS_P(25)
├── HIP_F(1) ── KNEE_F(3) ── ANKLE_F(4) ── {HEEL_F(5), TOE_F(6)}
└── HIP_B(2) ── KNEE_B(7) ── ANKLE_B(8) ── {HEEL_B(9), TOE_B(10)}
```

### Joints and local/world transforms
- Each `SkeletonNode` stores `localPosition` (`Vector3`) and `localRotation`
  (`JointRotation`, an axis-angle). Children store their offset *relative to the
  parent's frame*.
- The FK traversal composes parent transforms down the tree to produce
  `worldPosition`/`worldRotation`. `updateWorldTransforms()` does full 3×3 matrix
  concatenation; `flatten()` copies world data into the `SkeletonPose`.
- **Authoring happens in local space; the engine places the frame in the world.**
  This keeps poses stable when a parent frame (leaning torso, twisting thorax,
  re-rooted foot) moves.

### Parent-child relationships — key regions
- **Pelvis.** The default root. Anchors the trunk and the hips. Stays level by
  default; moves only when biomechanics demand (hip hinge, tilt that *is* the
  exercise).
- **Lumbar.** The lower-spine segment between pelvis and chest (Issue E). Models
  the spine as two real segments (`PELVIS → LUMBAR → CHEST`) so lumbar and
  thoracic motion can differ (good-morning, cat-cow). It is a **pass-through by
  default** (coincident with the pelvis, identity rotation), so existing single-bend
  poses are unchanged.
- **Chest.** The thoracic root. Its world rotation is the source of truth for the
  torso silhouette. Authoring helpers compose flexion (Z) · twist (Y) · side-bend
  (X). See `docs/ENGINE.md` §4 for the plane→axis convention.
- **Clavicle / Scapula.** The proximal shoulder girdle. Previously near-dead
  pass-throughs; now first-class joints (UNI-7) with elevation/protraction/axial
  DOF so the thorax-follows-shoulder-girdle principle reads correctly.
- **Limbs.** Each arm is `SHOULDER → ELBOW → HAND → WRIST → PALM → KNUCKLES →
  FINGERTIPS`; each leg is `HIP → KNEE → ANKLE → {HEEL, TOE}`. Hands and feet have
  sub-segments; heel and toe are separate children of the ankle. Limbs are solved
  by IK and baked into local offsets.

### Naming convention (A/P and F/B)
The skeleton is a single-plane silhouette with a near and far limb per pair:
- **A = active / foreground** (left), **P = passive / background** (right) for
  arms/hands. **F = foreground**, **B = background** for legs/feet.
- Named aliases exist (`shoulderB == shoulderP`, `hipA == hipF`, `wristA == handA`)
  so poses use whichever is clearer. The engine treats both members of a pair
  identically.

---

## 4. Pose Authoring

A **modern pose** is a `BasePose` subclass. It does not re-implement math; it
declares intent and calls shared helpers that call engine primitives.

### The shared base: `BasePose`
`BasePose` consolidates cross-exercise commonality so concrete poses stay thin:
- **Allocation-free scratch buffers** — persistent `Vector3`/`JointRotation`/matrix
  column fields reused across every call (zero per-frame allocation).
- **Body-construction helpers** — `buildTorso`, `buildHead`, `buildPelvis`,
  `buildShoulders`, `buildClavicularRotation`.
- **Orientation helpers** — `buildChestOrientation`, `buildChestTwist`,
  `buildChestSideBend`, `buildHipFlexion`, `buildHipAbduction`, `buildHipRotation`,
  `buildHipOrientation`, `buildLumbarFlexion`, `buildSpineCurve`,
  `buildWristArticulation`, `buildAnkleArticulation`.
- **IK wrappers** — `solveArmIK`, `solveLegIK`, `solveStraightArmIK`,
  `solveStraightLegIK`, `solveNearStraightLeg`, `bakeIkLimb`.
- **Motion / support helpers** — drivers and contact-spec registration.

Family base classes (e.g. `BaseVerticalPullPose`, `BasePlankPose`, `BaseLungePose`,
`BaseThoracicPose`) extend `BasePose` and hold shared behavior for a movement
family; concrete exercises (`StandardPullUpPose`, `MilitaryPushUpPose`, …) extend
the family base and supply only the numbers and overrides that make them unique.

### Representative authoring helpers
- **`buildPelvis`** — sets the pelvis local offset (and, when needed, lean/tilt).
  Used to express a deliberate hip hinge or anterior/posterior tilt that *is* the
  exercise; otherwise left at origin so the pelvis stays put.
- **`buildChestOrientation(lean, twist, sideBend)`** — composes the thoracic
  rotation as `R = Rz(flexion) · Ry(twist) · Rx(side-bend)` into a single
  `JointRotation`. Single-axis variants (`buildChestTwist`, `buildChestSideBend`)
  exist for clarity.
- **`buildHipRotation` / `buildHipFlexion` / `buildHipAbduction` / `buildHipOrientation`**
  — 3-DOF hip ball-and-socket authoring (UNI-10), mirroring `buildChestOrientation`.
  Femoral axial (internal/external) rotation is kept separate from the IK pole so
  it does not fight the solver.
- **`buildShoulders` / `buildClavicularRotation`** — drive the shoulder girdle;
  `buildClavicularRotation` gives the clavicle real elevation/protraction/axial DOF
  (UNI-7) so scapular initiation reads correctly.
- **`buildHead` / `buildTorso`** — set the neck/head offset and the chest local
  position. `buildTorso`'s offset is **live**, not dead: for a re-rooted push-up
  frame it points the spine along **−X**, the same anatomical "up the spine"
  direction expressed in that local frame.
- **`bakeIkLimb`** — the key bridge: takes an IK solution (world positions) and
  writes the limb's middle/end joints as local offsets of the parent, records the
  max IK clamp amount (for validation), and registers a `ContactSpec` if the end
  is a support. `solveArmIK`/`solveLegIK` wrap the solve + bake with both world
  and **frame-relative** pole overloads.

### Frame-relative pole vectors
Poles (which way a knee/elbow bends) are authored in the limb root's **local frame**
(chest for arms, pelvis for legs) and transformed to world space using the parent's
current rotation before solving. A frame-relative pole "follows the body": as the
thorax twists or the torso leans, the elbow/knee direction stays anatomically
consistent. **All new poses use frame-relative poles.**

---

## 5. IK

The limb solver is **analytical, not iterative**: a closed-form two-bone solution.
Given a root, a target, two lengths, a pole vector, and an `IKConstraint`, it
produces the middle and end joints directly. Fast, stable, deterministic.

### `solveIK`
`SkeletonMath.solveIK(...)` (`SkeletonMath.kt`):
- **Distance-band clamp** — if the target is farther than `(L1 + L2) · ratio` or
  nearer than the minimum-flexion reach, it is clamped to the reachable band.
- **Angular-band clamp** — rejects hyperextension and over-folding via the shared
  middle-joint flexion band (`AngularJointLimits`).
- **Contact resolve** — an optional `ContactConstraint` re-solves the end joint
  onto a support plane (`resolveContactPlane`).
- **Degenerate pole fallback** — when the pole is collinear with the bone, falls
  back to world-down rather than producing a degenerate frame.

### `solveStraightLimb`
A dedicated collinear straight-limb solve for near-straight arms/legs. Uses a
flexion-angle solve rather than a linear extension ratio, because visual
straightness is non-linear near full extension. UNI-9 adds a degenerate fallback:
when the target is *closer* than `L1` (an impossible straight limb), it bends
instead of collapsing to a zero-length bone.

### Pole vectors
The pole selects bend direction. Authored **frame-relative** (see §4) and converted
to world via `toWorldDirection` before the solve. World-space poles flip and pop
when the parent frame rotates; frame-relative poles do not.

### Constraints
`IKConstraint` carries `minimumFlexionAngle` and `maximumExtensionRatio` (default
`0.98` — a limb is never perfectly straight/locked). `ArmConstraint` /
`LegConstraint` are the shared named instances. `allowFullExtension` opts a limb
into a `1.0` ratio when straightness is truly intended. The clamp amount is
recorded so validation can detect an unreachable target.

### Contact targets
A support contact's end-effector position is an honest biomechanical target (a
grip on the bar, a planted foot). IK drives the limb to it. Targets are never
nudged to hide a solver artifact — an unreachable target is information to
investigate.

### Straight intent
When a limb is declared `straight`, the author means *visually straight*. The
near-straight solve + `allowFullExtension` honor that; validation (UNI-2) then
checks the resolved limb is within tolerance of straight (≥ ~175°).

---

## 6. Constraint Solver

`ConstraintSolver` exists because the engine is otherwise **purely local**: each
limb is solved in isolation by two-bone IK, and FK composes it into the body. That
is sufficient for a single free limb, but not when several body parts are declared
**fixed in contact** (planted hands and feet in a plank, or a hanging pull-up with
planted hands and a planted foot). Independently solved limbs produce geometry that
cannot all be satisfied at once, leaving a contact floating, a foot penetrating the
ground, or a knee folded wrong.

The ConstraintSolver reconciles authored pose + declared contacts. It runs *between
IK and FK*.

### What it does
- **Contacts** — consumes the `ContactSpec`s registered during build (each maps a
  planted body part to a 2-bone chain; knees/elbows/hips/head are degenerate
  1-bone chains with `middle == end`).
- **CCD (Cyclic Coordinate Descent)** — a damped CCD posture pass distributes
  residual contact error into *free* joint angles. It is **regularized toward the
  authored shape** (`regularizeTowardAuthored`), so over-constrained contacts relax
  naturally into free joints rather than yanking the pelvis (UNI-1).
- **Posture solve** — the step that nudges the body into a posture that honors all
  contacts and the authored intent simultaneously.
- **Pelvis correction** — when balance requires it, applies a pelvis tilt. The tilt
  is a **lateral roll about X** for left/right imbalance, *not* a pitch about Z
  (UNI-4) — a pitch would lean the trunk forward/back, which is wrong.
- **Balance** — keeps the center of mass over the declared support polygon as a
  consequence of satisfying contacts; the solver only translates the root for
  reachability and records the delta (UNI-6).
- **Root delta recording** — `rootTranslationDelta` / `rootRotationDelta` are
  recorded and later validated (pelvis intent + contact preservation, UNI-6).

It is allocation-free (persistent scratch buffers) and iteration-bounded
(`MAX_ITERATIONS`, no-op when a pose declares no contacts).

### Why it exists
To make "fixed contacts + correct posture" a first-class, engine-owned concern
instead of a per-pose hack. Without it, every multi-contact pose would have to
hand-fudge offsets — exactly the compensation the coding rules forbid.

---

## 7. Validation

`ExerciseValidator` is the read-only correctness authority. Given a finished
`SkeletonPose` (and optionally previous frames, environment, camera), it runs a
fixed battery of rules and returns a `ValidationReport`. **It never mutates the
pose and never participates in producing it.**

### Rule battery
Physical-invariant rules (run in the product config):
- `FINITE_COORDINATES`, `BONE_LENGTH` (constant lengths), `HEAD_VIEWPORT`,
- `FOOT_GROUND_PENETRATION`, `HAND_SLIDING`,
- `IK_CONSTRAINT_LIMIT`, `IK_TARGET_UNREACHABLE`,
- `POSITION` / `VELOCITY` / `ACCELERATION_DISCONTINUITY` (motion continuity),
- `STATIC_SUPPORT_POLYGON`, `BILATERAL_SYMMETRY`, `HAND_SHOULDER_ALIGNMENT`,
- `ANGULAR_JOINT_LIMIT` (mirrors the solver's middle-joint band).

### Validator philosophy
The engine **satisfies** validation; validation never adapts to engine limitations
(`docs/VALIDATION.md` §2). A validation pose is a frozen reference of correct
anatomy. If the engine cannot reproduce it cleanly, the **engine is wrong** and is
fixed at the root cause — the pose is never softened to dodge the failure.

### `ENGINEERING_VALIDATION`
`ValidatorConfig.ENGINEERING_VALIDATION` is a companion config that enables the
full cluster, including the **intent** rules that are off in the product build.
It is gated behind the hidden **Engineering Validation** developer category
(`ENGINEERING_VALIDATION_FAMILY_ID`); when the developer setting is off, the
category and its poses are completely invisible and inert.

### Physical validity vs. authored intent
This is the core distinction the validator enforces:
- **Physical validity** — does the skeleton obey the laws the engine must always
  honor? Finite coordinates, constant bone lengths, no ground penetration, no hand
  sliding on fixed contacts, IK within limits, balanced support, symmetric where
  required, continuous motion. These run always.
- **Authored intent** — did the engine preserve *what the author asked for*, even
  when physically valid? Examples: a `straight` limb must resolve straight
  (UNI-2, `STRAIGHT_LIMB_INTENT`); an end-effector must land on its declared anchor
  (`CONTACT_PRESERVED`, UNI-6); the pelvis must not have been shoved beyond
  tolerance (`PELVIS_INTENT`, UNI-6); the femur must stay within acetabular ROM
  (`HIP_ROM_LIMIT`, UNI-3). These are engineering-only because they catch the
  engine *silently dropping* author intent while still producing a "legal" body.

The product checks physical validity. Engineering validation adds authored-intent
fidelity. Both belong to the same `ExerciseValidator`; only the enabled rule set
differs.

---

## 8. Modern Features (UNI-1 … UNI-10)

These are the unification milestones that turned the engine from per-pose hacks into
a coherent, anatomically-driven system. Listed without excessive detail.

- **UNI-1 — Posture relaxation & authored-shape regularization.** `ConstraintSolver`
  gained true damped CCD that distributes over-constraint into free joints and
  regularizes toward the authored shape, so planted contacts no longer float the
  pelvis.
- **UNI-2 — Straight-limb authored intent.** A `straight = true` limb must resolve
  visually straight (≥ ~175°); enforced by validation (`STRAIGHT_LIMB_INTENT`).
- **UNI-3 — Bounded hip ROM.** The hip became a real 3-DOF ball-and-socket with
  acetabular limits (`HipRomLimits`), previously unbounded; mirrored by
  `HIP_ROM_LIMIT` validation.
- **UNI-4 — Correct pelvis-tilt axis.** Balance tilt uses a **lateral roll about X**
  for left/right imbalance, not a pitch about Z (which leaned the trunk
  forward/back). This fixed the original `ConstraintSolver` tilt bug.
- **UNI-6 — Root-displacement intent.** The solver records `rootTranslationDelta` /
  `rootRotationDelta`; validation checks the pelvis stayed within intent tolerance
  and that contacts were preserved (`PELVIS_INTENT`, `CONTACT_PRESERVED`).
- **UNI-7 — Real clavicle.** The clavicle became a first-class proximal girdle joint
  (elevation / protraction / axial) via `buildClavicularRotation`, instead of a dead
  pass-through.
- **UNI-8 — 2-DOF wrist & ankle.** Wrist (flexion + deviation) and ankle
  (dorsi/plantar + inversion/eversion) articulations **combine** instead of
  overwriting, via `buildWristArticulation` / `buildAnkleArticulation`.
- **UNI-9 — Degenerate straight-limb fallback.** `solveStraightLimb` bends instead
  of collapsing to a zero-length bone when the target is inside `L1`.
- **UNI-10 — First-class 3-DOF hip authoring.** `buildHipOrientation` / `buildHipRotation`
  mirror `buildChestOrientation` for the hip; femoral axial rotation is kept separate
  from the IK pole.

> (UNI-5 is referenced as a PR marker in the history but is not separately labeled
> in the shipping code; the descriptive UNI milestones that ship are UNI-1, -2, -3,
> -4, -6, -7, -8, -9, -10.)

---

## 9. Engine Characteristics

Measurable / enforceable properties of the engine:

- **Deterministic** — same inputs → same skeleton; no randomness, no frame-order
  dependence.
- **Procedural** — poses are functions of progress, computed by math, not sampled
  keyframes.
- **No runtime allocations** — hot paths reuse scratch buffers; results written into
  caller-supplied outputs.
- **Frame independent** — motion is parameterized by `progress` (and the motion
  curve); any frame can be computed directly.
- **Reusable builders** — `BasePose` + family bases hold shared scaffolding;
  concrete poses are mostly configuration.
- **Lightweight** — plain Kotlin math + a Compose `Canvas`; no game engine or
  retained-mode scene graph.
- **No physics engine** — no gravity/collision simulation; "planted" and "balanced"
  are declared constraints verified by validation.
- **Anatomically driven** — real joints with real ranges; pelvis stabilizes by
  default; scapula drives pulling; thorax follows the girdle.
- **Extensible** — new topologies live in `SkeletonFactory`; new math in
  `SkeletonMath`; new families in `BasePose` subclasses; new intent rules in
  `ExerciseValidator`. Each extension point is single-owner and reused.

---

## 10. Current Limitations

These are real, current constraints of the engine — not already-resolved issues.

- **Single-plane silhouette.** The skeleton is modeled as a near/far pair per limb
  (A/P, F/B) for a side-on silhouette. There is no true 3-D volumetric body; depth
  is expressed by which limb is foreground/background and by the camera projection.
- **Two-bone IK only.** Limbs are solved as two-segment chains (upper+lower).
  There is no multi-bone IK for, e.g., a fully articulated spine chain beyond the
  pelvis–lumbar–chest two-segment model, nor for finger/toe chains.
- **ConstraintSolver is contact-only.** It activates only when a pose declares
  support contacts. Poses with no declared contacts receive no global correction;
  local per-limb correctness is the author's responsibility.
- **Deterministic, not predictive.** The engine has no notion of momentum,
  velocity, or force beyond the static validation of continuity. Dynamic balance
  (e.g. catching a fall) is out of scope; only static/quasi-static support is
  verified.
- **No self-collision.** Joints are not checked against each other; a limb can
  interpenetrate the torso in authoring, caught only if it also breaks another rule.
- **Engine units are abstract.** Positions are engine-unit magnitudes (≈
  centimetre-scale); there is no runtime calibration to a specific user's body —
  proportions come from the shared `SkeletonDefinition`.
- **Authored-intent validation is off in product.** `ENGINEERING_VALIDATION` (the
  UNI intent rules) runs only in the developer category; shipping builds rely on
  physical-invariance rules alone.

---

## 11. Future Directions

Architectural directions only — no speculative redesigns.

- **More first-class girdle joints.** Extend the proximal/ distal girdle treatment
  (clavicle/scapula already promoted under UNI-7) to make the shoulder-thorax and
  hip-pelvis couplings fully expressive, so thoracic-follows-girdle reads without
  finalizer geometry assumptions.
- **Richer spine model.** The two-segment spine (pelvis–lumbar–chest) is the
  foundation; additional thoracic segments could be added within the same
  `SkeletonFactory` topology without changing the IK/FK core.
- **Generalize ConstraintSolver contacts.** Today contacts map to 2-bone (or
  degenerate 1-bone) chains. A principled way to express more complex support
  topologies (e.g. a seated hip + planted hands) would broaden the multi-contact
  poses the solver can reconcile natively.
- **Expand authored-intent validation.** More intent rules (beyond UNI-2/-3/-6) that
  catch the engine silently dropping author intent, runnable in the engineering
  config, keep the "engine satisfies validation" contract strong as features grow.
- **Deeper motion continuity.** The validator already checks position/velocity/
  acceleration discontinuity; a first-class motion model (eased drivers shared
  across families) would centralize easing instead of per-pose curve choices.
- **Keep the four-layer boundary.** Any new capability should land in its correct
  layer: math → `SkeletonMath`, topology → `SkeletonFactory`, authoring → `BasePose`,
  correctness → `ExerciseValidator`. Avoid pulling exercise knowledge into the core
  or mutations into the validator.

---

## 12. Quick File Map (where to look)

| Concern | Primary file(s) |
| --- | --- |
| Joint set | `animation/Joint.kt` |
| Node + FK | `animation/SkeletonNode.kt` |
| Topology & accessors | `animation/SkeletonFactory.kt` |
| Pose contract | `animation/PoseBuilder.kt`, `animation/PoseContext.kt` |
| Shared authoring | `animation/BasePose.kt` |
| Math + IK | `animation/SkeletonMath.kt` |
| Contacts / support | `animation/Support*.kt`, `animation/SupportContact.kt` |
| Global correction | `animation/ConstraintSolver.kt` |
| FK + completion | `animation/SkeletonPoseFinalizer.kt` |
| Validation | `animation/ExerciseValidator.kt`, `validation/` |
| Projection | `animation/SkeletonProjector.kt` |
| Render | `animation/SkeletonRenderer.kt`, `animation/SkeletonEngine.kt` |
| Drive | `animation/AnimationController.kt` |

> Read `docs/ENGINE.md` for coordinate-system and IK-philosophy detail, and
> `docs/CODING_RULES.md` before changing any of the above.
