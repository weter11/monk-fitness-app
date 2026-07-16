# ENGINE_ARCHITECTURE.md — Engine Entry Point

> **Start here.** This document is the map of the Monk Fitness procedural animation
> engine. It explains how the engine is organized as a whole and points you to the
> document that owns each topic. The first sections are the orientation every new
> engineer should read; the **Reference** section at the end preserves the detailed
> class-, joint-, and file-level facts that are *not* duplicated in the subsystem
> docs. When a section says "see X", go read X for the surrounding philosophy.

---

## 1. Engine Philosophy and Design Goals

The engine exists to **solve motion**, not to describe it (`docs/ENGINE.md` §1).
A skeleton is a hierarchy of joints; a pose is a request for where parts of the
body should be; the engine turns that request into a complete, anatomically
consistent, physically plausible skeleton every frame. It never reasons about
forward kinematics, world transforms, foot/hand geometry, or projection — the
caller only expresses *intent*.

Design goals that drive every architectural decision:

- **Solve motion, not exercises.** The core knows about bones, joints, and
  constraints. It knows nothing about "push-ups" or "squats" (`docs/ENGINE.md` §1,
  `docs/CODING_RULES.md` §2–3).
- **Separated responsibilities.** Four strictly-ordered layers — Exercise (metadata)
  → Pose (biomechanics) → Engine (motion) → Validation (verification). Crossing a
  boundary is an architectural violation (`docs/ENGINE.md` §2).
- **Honest biomechanics.** Animate the *cause*; the visible shape follows. Never
  fake motion by translating the pelvis or sliding a target to approximate a joint
  that should have rotated (`docs/BIOMECHANICS.md` §1, §10).
- **Lightweight runtime.** Plain Kotlin math plus a Compose `Canvas`. No game
  engine, no scene graph, no physics simulation (`docs/ENGINE.md` §11).
- **Read-only correctness.** Validation inspects a finished skeleton and never
  mutates it or participates in producing it (`docs/VALIDATION.md` §1).

---

## 2. High-Level Animation Pipeline

Every animated frame flows through a fixed, ordered pipeline. Each stage has one
job and depends only on the stages before it:

```
PoseBuilder            authors intent (local transforms + IK)
    ↓
BasePose               shared scaffolding: body helpers + IK wrappers
    ↓
SkeletonFactory        builds the joint hierarchy (topology, not pose)
    ↓
IK                     analytical two-bone solve, baked into local offsets
    ↓
ConstraintSolver       reconciles declared support contacts + posture
    ↓
SkeletonPoseFinalizer  FK traversal + anatomical completion + chest frame
    ↓
Projector              ẋ3D pose → screen-space via CameraDefinition
    ↓
Camera                 viewpoint owned by the pose's metadata
    ↓
Renderer               depth-sorted draw on a Compose Canvas
```

`AnimationController` drives the loop: it advances `progress`/`side` (loop modes:
LOOP, HOLD, PING_PONG, ONCE) and feeds the `PoseContext`. The `ExerciseValidator`
runs *independently* as a developer QA tool and is never on the hot path.

A note on the ordering: `SkeletonFactory` produces the topology the pose fills in,
and `ConstraintSolver` runs *between* IK and the finalizer. The pose author
(`BasePose`) is the consumer of the factory and the caller of IK — they are
different facets of the same build step, not separate stages in time.

---

## 3. Subsystems (and where to read about each)

| Subsystem | Role | Primary doc |
| --- | --- | --- |
| **PoseBuilder / BasePose** | The pose contract and shared authoring scaffolding. `build(context): SkeletonPose` authors the body in local space using reusable helpers and IK wrappers. | `docs/ENGINE.md` §3 (`BasePose`), `docs/CODING_RULES.md` §2 (data-driven poses) |
| **SkeletonFactory** | Constructs the joint hierarchy (`SkeletonNode` tree) for a topology. Owns the *shape* of the tree, not the pose. Named `SkeletonNodes` accessors let poses reference joints by role. | `docs/ENGINE.md` §3, §8; `docs/ENGINE_ARCHITECTURE.md` §R2 (joint set, A/P-F/B naming) |
| **SkeletonMath / IK** | Stateless math core: vectors, rotations, forward kinematics, the analytical closed-form two-bone solver, near-straight-limb solving, frame↔world direction transforms. Allocation-free. | `docs/ENGINE.md` §4 (coordinates/axes), §6–7 (poles/IK philosophy) |
| **ConstraintSolver** | Runs after IK, before FK. Reconciles the authored pose with its declared support contacts via damped CCD, regularized toward the authored shape; applies only lateral pelvis roll for balance. | `docs/ENGINE_ARCHITECTURE.md` §R4; `docs/ENGINE.md` §5 (local vs world); `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md` (UNI-1/4) |
| **SkeletonPoseFinalizer** | FK traversal, chest-frame reconstruction (preserves authored thoracic rotation), and anatomical completion of feet/hands. | `docs/ENGINE.md` §3 (`SkeletonPoseFinalizer`); `docs/ENGINE_ARCHITECTURE.md` §R1 (finalizer step) |
| **Validation (ExerciseValidator)** | Read-only correctness authority. Fixed battery of physical-invariance rules plus engineering-only authored-intent rules. | `docs/VALIDATION.md`; `docs/ENGINE_ARCHITECTURE.md` §R5 |
| **Projector / Camera / Renderer** | `SkeletonProjector` converts the 3D pose to screen space via the pose-owned `CameraDefinition`; `SkeletonRenderer` draws depth-sorted bones/joints/ground. Camera and environment are owned by `PoseMetadata`. | `docs/ENGINE.md` §10 (camera/environment ownership); `docs/VALIDATION.md` §11.6 (camera framing) |
| **Biomechanics** | The engineering interpretation of human movement that decides whether a pose is *correct* (honest motion, joint sequencing, pelvis stabilizes, planted parts stay planted). | `docs/BIOMECHANICS.md` |
| **Pelvic / Hip Complex** | Focused architectural investigation of the pelvis and hip joints, ROM limits, and the ConstraintSolver tilt axis. (Note: the project's hip reference is `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; there is no separate `HIP_COMPLEX.md`.) | `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; `docs/ENGINE_INVESTIGATION_REPORT.md` (UNI register) |
| **Engineering Validation** | The hidden developer category of frozen reference poses used to verify the engine. Includes the four-pose defect audit and camera framing pass. | `docs/VALIDATION.md` §4–5, §11; `docs/ENGINEERING_VALIDATION_AUDIT.md` |
| **Coding Rules** | Permanent, standing rules for all development on the engine, poses, and validation. | `docs/CODING_RULES.md` |

> **On `HIP_COMPLEX.md` / `CAMERA.md`:** the project lists these as existing docs.
> In the current tree they do not exist as standalone files. The hip material lives
> in `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; camera ownership/framing is
> covered by `docs/ENGINE.md` §10 and `docs/VALIDATION.md` §11.6. If dedicated
> `HIP_COMPLEX.md` / `CAMERA.md` pages are later extracted, update this table.

---

## 4. Core Architectural Principles

These principles are non-negotiable and are enforced by the coding rules
(`docs/CODING_RULES.md`) and the validator.

- **Procedural.** Motion is described by rules and math, not sampled keyframes. A
  pose is a *function* of progress; the engine computes the body at any progress.
- **Deterministic.** The same inputs (pose, progress, side, definition) always
  produce the same skeleton. No randomness, no frame-order dependence.
- **Allocation-free.** Hot paths reuse persistent scratch buffers (`Vector3`,
  `JointRotation`, matrix columns) and write into caller-supplied outputs. No
  per-frame allocation, no GC pauses on the render loop.
- **Bone-length preserving.** Segment lengths are invariants. The solver clamps
  targets to the reachable band rather than stretching a bone; validation flags any
  length change (`BONE_LENGTH`).
- **Authoring-first.** Poses author in *local space*; the engine places the frame
  in the world via FK. This keeps poses stable when a parent frame (leaning torso,
  twisting thorax, re-rooted foot) moves (`docs/ENGINE.md` §5).
- **Validation-first.** The engine satisfies validation; validation never adapts
  to engine limitations. A failing validation pose is an engine defect to
  investigate, never a target to retune (`docs/VALIDATION.md` §2, §8–9).

Supporting rules worth internalizing early: use frame-relative pole vectors
(`docs/ENGINE.md` §6), drive limbs through shared IK + `bakeIkLimb`, build topology
only in `SkeletonFactory`, and never introduce per-pose magic constants
(`docs/CODING_RULES.md` §2–3).

---

## 5. Current Engine Capabilities

- **Full skeletal humanoid** of 33 contiguous joints (`Joint.kt`, indices 0..32),
  pelvis-rooted, with a two-segment spine (`PELVIS → LUMBAR → CHEST`, Issue E) so
  lumbar and thoracic motion can differ.
- **Two standard topologies:** the upright `createStandardSkeleton()` and the
  re-rooted `createPushUpSkeleton()` (root at the planted contact) — both reuse the
  same joint set; only parenting changes.
- **Analytical two-bone IK** for arms and legs, biologically clamped (minimum
  flexion, maximum extension below full lock), with a dedicated near-straight-limb
  solve and a degenerate bent-limb fallback.
- **Frame-relative poles** so elbow/knee direction stays anatomically consistent as
  parent frames rotate.
- **Contact reconciliation** via `ConstraintSolver` for multi-contact poses
  (plank, pull-up), regularized toward the authored shape.
- **First-class girdle joints** — clavicle/scapula have real
  elevation/protraction/axial DOF (UNI-7); 3-DOF hip ball-and-socket authoring
  (UNI-10); femoral axial rotation kept separate from the IK pole.
- **Anatomical completion** of feet (heel/toe, pitch clamped) and hands
  (wrist/palm/knuckles/fingertips) in the finalizer.
- **Engineering Validation** subsystem: four frozen reference poses (dead hang,
  deep overhead squat, pike sit, middle split) under a hidden developer category.
- **Read-only validator** with a physical-invariance battery plus engineering-only
  authored-intent rules (`STRAIGHT_LIMB_INTENT`, `CONTACT_PRESERVED`,
  `PELVIS_INTENT`, `HIP_ROM_LIMIT`, …).

---

## 6. Current Engine Limitations (intentionally not modeled)

These are real, current scope boundaries — not resolved issues. New engineers
should not try to "fix" them by hacking poses; they are either by-design or
tracked as future engine work.

- **Single-plane silhouette.** Near/far limb pairs (A/P, F/B) for a side-on view.
  No true volumetric 3-D body; depth is expressed by foreground/background
  ordering and camera projection.
- **Two-bone IK only.** Limbs are two-segment chains. No multi-bone IK beyond the
  pelvis–lumbar–chest spine, nor for finger/toe chains.
- **ConstraintSolver is contact-only.** It activates only when a pose declares
  support contacts. Poses with no declared contacts get no global correction.
- **Deterministic, not predictive.** No momentum, velocity, or force beyond static
  continuity validation. Only static/quasi-static support is verified; dynamic
  balance (e.g. catching a fall) is out of scope.
- **No self-collision.** Joints are not checked against each other; interpenetration
  is caught only if it also breaks another rule.
- **Abstract engine units.** Engine-unit magnitudes (~centimetre-scale). No runtime
  calibration to a specific user; proportions come from the shared
  `SkeletonDefinition`.
- **Authored-intent validation is off in product.** The UNI intent rules run only
  in the hidden Engineering Validation category; shipping builds rely on
  physical-invariance rules.
- **Known straight-intent gap (UNI-9).** A `straight = true` limb whose target sits
  inside the proximal-bone length cannot be honored and falls back to a bent solve.
  The Middle Split validation pose deliberately keeps this as a regression
  reference (`docs/VALIDATION.md` §11.1).

The full characteristic list and roadmap are in `docs/ENGINE_ARCHITECTURE.md` §R6
and §R7; the pre-existing test-baseline context (30 known failures) is in
`docs/TEST_BASELINE.md`.

---

## 7. Documentation Map

How the engine documents relate. Read in the order that matches your task.

```
                         AGENTS.md (session memory anchor)
                                   │
                                   ▼
                   ENGINE_ARCHITECTURE.md  ◄── YOU ARE HERE (entry point / map)
                                   │
        ┌──────────────┬───────────┼───────────────┬──────────────────┐
        ▼              ▼           ▼               ▼                  ▼
   ENGINE.md      BIOMECHANICS.md  CODING_RULES.md   VALIDATION.md      TEST_BASELINE.md
  (how it works)  (movement rules) (standing rules) (verify + poses)  (known test state)
        │              │                              │
        └──────┬───────┴──────────────┬───────────────┘
               ▼                      ▼
   PELVIC_HIP_COMPLEX_        ENGINEERING_VALIDATION_AUDIT.md
   INVESTIGATION.md           (4-pose defect audit + camera pass)
   ENGINE_INVESTIGATION_      ENGINE_FIX_PR_PROMPTS.md
   REPORT.md (UNI register)   (fix prompts per issue)
```

Reading paths:

- **New engineer onboarding:** this doc → `ENGINE.md` → `BIOMECHANICS.md` →
  `CODING_RULES.md` → `VALIDATION.md`.
- **Adding/fixing a pose:** `CODING_RULES.md` → `ENGINE.md` (§3–7) →
  `BIOMECHANICS.md`. If a validation pose fails, assume the engine is wrong
  (`VALIDATION.md` §8).
- **Engine/core change:** `ENGINE.md` §11–12 (what belongs inside) → relevant
  subsystem section in `ENGINE_ARCHITECTURE.md` → `TEST_BASELINE.md` to avoid
  regressions.
- **Hip/pelvis work:** `PELVIC_HIP_COMPLEX_INVESTIGATION.md` (focused) →
  `ENGINE_INVESTIGATION_REPORT.md` (UNI register) → `ENGINE_FIX_PR_PROMPTS.md`.
- **Validation/audit:** `VALIDATION.md` → `ENGINEERING_VALIDATION_AUDIT.md`.

> Where documents disagree, the precedence is: `CODING_RULES.md` (contributor
> rules) > `VALIDATION.md` (correctness contract) > `ENGINE.md` (architecture) >
> this map. Investigation reports (`*_INVESTIGATION*.md`,
> `ENGINEERING_VALIDATION_AUDIT.md`) are *evidence*, not source of truth.

---

## 8. Evolution / History

The engine did not start as a clean four-layer system. Understanding the
trajectory prevents repeating old mistakes.

**Phase 1 — Exercise implementation first.** Early work built concrete exercises
directly. Poses re-implemented geometry locally, the pelvis was commonly used to
fake shapes, and IK targets were nudged to hide solver artifacts. This produced
working-looking but biomechanically dishonest motion and a tangle of per-pose
constants.

**Phase 2 — Shared scaffolding.** The response was `BasePose` and family base
classes: cross-exercise commonality (body helpers, IK wrappers, scratch buffers)
moved into one place, and concrete exercises became mostly *configuration*. The
four-layer boundary (Exercise / Pose / Engine / Validation) was written down as
the architecture (`docs/ENGINE.md`) and the standing rules (`docs/CODING_RULES.md`)
were established to forbid compensations.

**Phase 3 — Validation poses.** Once the engine could be trusted to reproduce
intent, the team introduced frozen reference anatomy ("validation poses") and the
hidden Engineering Validation category. The contract was fixed: *the engine
satisfies validation; validation never adapts to the engine* (`docs/VALIDATION.md`
§2). Failures are treated as engine defects, not retuned targets.

**Phase 4 — Biomechanical subsystem audits.** With a stable validator, the team
ran focused architectural investigations of weak areas. The Pelvic/Hip Complex
deep-dive (`docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`) and the consolidated
`docs/ENGINE_INVESTIGATION_REPORT.md` (the UNI-* issue register) audited hip ROM,
the ConstraintSolver tilt axis, scapular initiation, girdle DOF, and
straight-limb intent. Each finding was attributed strictly to AUTHORING, ENGINE,
CAMERA, VALIDATION, or RENDERER, and fixes were routed to the owning layer
(`docs/ENGINEERING_VALIDATION_AUDIT.md`, `docs/ENGINE_FIX_PR_PROMPTS.md`). This is
the current state: a layered engine under continuous, evidence-based audit.

---

## 9. Where To Go Next

Open `docs/ENGINE.md` for coordinate systems, the IK philosophy, and the
layer-by-layer responsibilities. Keep `docs/CODING_RULES.md` open while you write
any code. When in doubt about whether a shape is *correct*, consult
`docs/BIOMECHANICS.md` before changing anything.

---

# Reference — class, joint, and file-level facts

> This section is the engine's detailed reference. It is kept here because no other
> document duplicates it. The "see X" pointers in the sections above resolve to
> these anchors.

## R1. Skeleton, joints, and the pipeline stages in detail

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
  default** (coincident with the pelvis, identity rotation), so existing
  single-bend poses are unchanged.
- **Chest.** The thoracic root. Its world rotation is the source of truth for the
  torso silhouette. Authoring helpers compose flexion (Z) · twist (Y) · side-bend
  (X). See `docs/ENGINE.md` §4 for the plane→axis convention.
- **Clavicle / Scapula.** The proximal shoulder girdle. First-class joints (UNI-7)
  with elevation/protraction/axial DOF so the thorax-follows-shoulder-girdle
  principle reads correctly.
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

### Stage details (the pipeline from §2)

**PoseBuilder.** The contract every pose implements:
`build(context: PoseContext): SkeletonPose`. `PoseContext` carries animation
progress (already run through the motion curve), side, mirroring, timing, and the
`SkeletonDefinition` (shared body proportions). `PoseBuilder` itself is the thin
interface; production poses extend `BasePose`.

**SkeletonFactory.** Constructs the **topology** — the joint hierarchy
(`SkeletonNode` tree) for a given rooting. It owns the *shape of the tree*, not the
pose. Named accessors (`SkeletonNodes`) let a pose reference joints by role
(`chest`, `shoulderA`, `hipF`, `lumbar`) instead of by index. Two standard
topologies exist: `createStandardSkeleton()` (pelvis-rooted upright humanoid) and
`createPushUpSkeleton()` (same body re-rooted at the planted foot so a fixed ground
contact becomes the FK root). New topologies are added here, once, and reused —
never assembled inside individual poses.

**PoseBuilder.build().** Given a context, the pose obtains a skeleton from the
factory, then **authors the body in local space** using `BasePose` helpers
(`buildPelvis`, `buildChestOrientation`, `buildHipRotation`, `buildShoulders`,
`buildHead`, `buildTorso`, etc.). Where a limb must reach a target, it calls the IK
wrappers (`solveArmIK`, `solveLegIK`, `bakeIkLimb`), which solve and **bake** the
result into the limb's parent local frame and register any support contacts. The
output is a `SkeletonPose` whose `roots` reference the live node hierarchy.

**IK.** The analytical two-bone solver turns a root, a target, two bone lengths, a
pole vector, and an `IKConstraint` into middle- and end-joint world positions,
which `bakeIkLimb` converts into the local offsets the FK hierarchy expects. The
solver is closed-form, deterministic, and biologically clamped (minimum flexion,
maximum extension below full lock). Near-straight limbs use a dedicated
flexion-angle solve because visual straightness is non-linear near full extension.

**ConstraintSolver.** Runs **after IK but before forward kinematics**. Because the
engine is otherwise purely local (each limb solved in isolation), inconsistent
geometry can leave a "planted" hand floating, a foot penetrating the ground, or a
knee folded the wrong way. The ConstraintSolver reconciles the authored pose with
its declared support contacts: it may re-bake contact limbs, apply a pelvis tilt
for balance, and run a damped CCD pass that distributes residual error into free
joint angles while regularizing toward the authored shape. It only acts when the
pose declares contacts.

**SkeletonPoseFinalizer.** Runs after build (and after `ConstraintSolver` if
contacts exist). It: (1) performs the **forward-kinematics traversal** so every
joint has correct `worldPosition` and `worldRotation`; (2) reconstructs the chest
frame from the spine direction + shoulder line (only when the chest is unauthored;
preserves authored thoracic rotation otherwise); (3) **completes anatomy** the pose
did not author directly — feet (heel/toe with pitch clamping) and hands
(wrist/palm/knuckles/fingertips); (4) guarantees the output is a complete,
FK-consistent skeleton.

**Renderer.** `SkeletonProjector` converts the finalized 3D `SkeletonPose` into a
screen-space `ProjectedSkeleton` through the pose's `CameraDefinition`, reusing
preallocated buffers. `SkeletonRenderer` (a Compose `@Composable`) then depth-sorts
and draws bones, joints, torso faces, ground, and environment on a `Canvas`. It
never infers rotations from positions; the chest world rotation is the source of
truth for the torso silhouette. `ScreenSpaceCompensation` applies perspective
scale. Rendering is a passive consumer of the finalized pose.

## R2. Joint set and named accessors

`SkeletonFactory` exposes `SkeletonNodes` so poses reference joints by role rather
than index. The full 33-entry set is listed in §R1; the role aliases most used in
poses are: `pelvis`, `lumbar`, `chest`, `neckEnd`, `head`; `clavicleA/P`,
`scapulaA/P`, `shoulderA/P` (= `shoulderF/B`), `elbowA/P`, `handA/P`
(= `wristA/P`), `palmA/P`, `knucklesA/P`, `fingertipsA/P`; `hipF/B` (= `hipA/B`),
`kneeF/B`, `ankleF/B`, `heelF/B`, `toeF/B`.

## R3. Pose authoring — shared helpers

A modern pose is a `BasePose` subclass. It does not re-implement math; it declares
intent and calls shared helpers that call engine primitives.

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
  is a support. `solveArmIK`/`solveLegIK` wrap the solve + bake with both world and
  **frame-relative** pole overloads.

### Frame-relative pole vectors
Poles (which way a knee/elbow bends) are authored in the limb root's **local frame**
(chest for arms, pelvis for legs) and transformed to world space using the parent's
current rotation before solving. A frame-relative pole "follows the body": as the
thorax twists or the torso leans, the elbow/knee direction stays anatomically
consistent. **All new poses use frame-relative poles.**

## R4. ConstraintSolver in detail

`ConstraintSolver` exists because the engine is otherwise **purely local**: each
limb is solved in isolation by two-bone IK, and FK composes it into the body. That
is sufficient for a single free limb, but not when several body parts are declared
**fixed in contact** (planted hands and feet in a plank, or a hanging pull-up with
planted hands and a planted foot). Independently solved limbs produce geometry that
cannot all be satisfied at once, leaving a contact floating, a foot penetrating the
ground, or a knee folded wrong.

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

## R5. Validation rule battery

`ExerciseValidator` is the read-only correctness authority. Given a finished
`SkeletonPose` (and optionally previous frames, environment, camera), it runs a
fixed battery of rules and returns a `ValidationReport`. **It never mutates the
pose and never participates in producing it.**

### Physical-invariance rules (run in the product config)
`FINITE_COORDINATES`, `BONE_LENGTH` (constant lengths), `HEAD_VIEWPORT`,
`FOOT_GROUND_PENETRATION`, `HAND_SLIDING`, `IK_CONSTRAINT_LIMIT`,
`IK_TARGET_UNREACHABLE`, `POSITION` / `VELOCITY` / `ACCELERATION_DISCONTINUITY`
(motion continuity), `STATIC_SUPPORT_POLYGON`, `BILATERAL_SYMMETRY`,
`HAND_SHOULDER_ALIGNMENT`, `ANGULAR_JOINT_LIMIT` (mirrors the solver's middle-joint
band).

### Validator philosophy
The engine **satisfies** validation; validation never adapts to engine limitations
(`docs/VALIDATION.md` §2). A validation pose is a frozen reference of correct
anatomy. If the engine cannot reproduce it cleanly, the **engine is wrong** and is
fixed at the root cause — the pose is never softened to dodge the failure.

### `ENGINEERING_VALIDATION`
`ValidatorConfig.ENGINEERING_VALIDATION` is a companion config that enables the
full cluster, including the **intent** rules that are off in the product build. It
is gated behind the hidden **Engineering Validation** developer category
(`ENGINEERING_VALIDATION_FAMILY_ID`); when the developer setting is off, the
category and its poses are completely invisible and inert.

### Physical validity vs. authored intent
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

## R6. Engine characteristics (enforceable properties)

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

## R7. Future directions

Architectural directions only — no speculative redesigns.

- **More first-class girdle joints.** Extend the proximal/distal girdle treatment
  (clavicle/scapula already promoted under UNI-7) to make the shoulder-thorax and
  hip-pelvis couplings fully expressive.
- **Richer spine model.** The two-segment spine (pelvis–lumbar–chest) is the
  foundation; additional thoracic segments could be added within the same
  `SkeletonFactory` topology without changing the IK/FK core.
- **Generalize ConstraintSolver contacts.** Today contacts map to 2-bone (or
  degenerate 1-bone) chains. A principled way to express more complex support
  topologies (e.g. a seated hip + planted hands) would broaden the multi-contact
  poses the solver can reconcile natively.
- **Expand authored-intent validation.** More intent rules (beyond UNI-2/-3/-6) that
  catch the engine silently dropping author intent, runnable in the engineering
  config.
- **Deeper motion continuity.** A first-class motion model (eased drivers shared
  across families) would centralize easing instead of per-pose curve choices.
- **Keep the four-layer boundary.** Any new capability should land in its correct
  layer: math → `SkeletonMath`, topology → `SkeletonFactory`, authoring → `BasePose`,
  correctness → `ExerciseValidator`. Avoid pulling exercise knowledge into the core
  or mutations into the validator.

## R8. Quick file map (where to look)

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
