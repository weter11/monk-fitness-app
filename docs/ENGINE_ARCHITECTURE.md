# ENGINE_ARCHITECTURE.md — Engine Entry Point

> **Start here.** This document is the map of the Monk Fitness procedural animation
> engine. It explains how the engine is organized as a whole and points you to the
> document that owns each topic. It is **not** an implementation guide and does not
> duplicate the subsystem documentation — when a section says "see X", go read X
> for the detail.

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

A note on the ordering above (which differs in emphasis from the older
`docs/ENGINE_ARCHITECTURE.md`): `SkeletonFactory` produces the topology the pose
fills in, and `ConstraintSolver` runs *between* IK and the finalizer. The pose
author (`BasePose`) is the consumer of the factory and the caller of IK — they are
different facets of the same build step, not separate stages in time.

---

## 3. Subsystems (and where to read about each)

| Subsystem | Role | Primary doc |
| --- | --- | --- |
| **PoseBuilder / BasePose** | The pose contract and shared authoring scaffolding. `build(context): SkeletonPose` authors the body in local space using reusable helpers and IK wrappers. | `docs/ENGINE.md` §3 (`BasePose`), `docs/CODING_RULES.md` §2 (data-driven poses) |
| **SkeletonFactory** | Constructs the joint hierarchy (`SkeletonNode` tree) for a topology. Owns the *shape* of the tree, not the pose. Named `SkeletonNodes` accessors let poses reference joints by role. | `docs/ENGINE.md` §3, §8; `docs/ENGINE_ARCHITECTURE.md` §3 (joint set, A/P-F/B naming) |
| **SkeletonMath / IK** | Stateless math core: vectors, rotations, forward kinematics, the analytical closed-form two-bone solver, near-straight-limb solving, frame↔world direction transforms. Allocation-free. | `docs/ENGINE.md` §4 (coordinates/axes), §6–7 (poles/IK philosophy) |
| **ConstraintSolver** | Runs after IK, before FK. Reconciles the authored pose with its declared support contacts via damped CCD, regularized toward the authored shape; applies only lateral pelvis roll for balance. | `docs/ENGINE_ARCHITECTURE.md` §6; `docs/ENGINE.md` §5 (local vs world); `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md` (UNI-1/4) |
| **SkeletonPoseFinalizer** | FK traversal, chest-frame reconstruction (preserves authored thoracic rotation), and anatomical completion of feet/hands. | `docs/ENGINE.md` §3 (`SkeletonPoseFinalizer`); `docs/ENGINE_ARCHITECTURE.md` §2 (finalizer step) |
| **Validation (ExerciseValidator)** | Read-only correctness authority. Fixed battery of physical-invariance rules plus engineering-only authored-intent rules. | `docs/VALIDATION.md`; `docs/ENGINE_ARCHITECTURE.md` §7 |
| **Projector / Camera / Renderer** | `SkeletonProjector` converts the 3D pose to screen space via the pose-owned `CameraDefinition`; `SkeletonRenderer` draws depth-sorted bones/joints/ground. Camera and environment are owned by `PoseMetadata`. | `docs/ENGINE.md` §10 (camera/environment ownership); `docs/VALIDATION.md` §11.6 (camera framing) |
| **Biomechanics** | The engineering interpretation of human movement that decides whether a pose is *correct* (honest motion, joint sequencing, pelvis stabilizes, planted parts stay planted). | `docs/BIOMECHANICS.md` |
| **Pelvic / Hip Complex** | Focused architectural investigation of the pelvis and hip joints, ROM limits, and the ConstraintSolver tilt axis. (Note: the project's hip reference is `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; there is no separate `HIP_COMPLEX.md`.) | `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; `docs/ENGINE_INVESTIGATION_REPORT.md` (UNI register) |
| **Engineering Validation** | The hidden developer category of frozen reference poses used to verify the engine. Includes the four-pose defect audit and camera framing pass. | `docs/VALIDATION.md` §4–5, §11; `docs/ENGINEERING_VALIDATION_AUDIT.md` |
| **Coding Rules** | Permanent, standing rules for all development on the engine, poses, and validation. | `docs/CODING_RULES.md` |

> **On `HIP_COMPLEX.md` / `CAMERA.md`:** the task lists these as existing docs.
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

The full characteristic list and roadmap are in `docs/ENGINE_ARCHITECTURE.md` §8
and §11; the pre-existing test-baseline context (30 known failures) is in
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
