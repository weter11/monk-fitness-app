# Architecture v3 — High-Level System Architecture

**Status:** DRAFT
**Supersedes:** Architecture v2 (implementation specification)
**Builds on:** Domain Analysis (`DOMAIN_ANALYSIS_SKELETON.md`)

> This document describes the major architectural subsystems of the skeleton engine.
> It is not a class design.
> It is not a package layout.
> It is not an implementation proposal.
>
> The goal is to identify the large responsibilities of the system and the boundaries between them.

---

## 1. System Overview

The skeleton engine is composed of several cooperating subsystems. Each subsystem owns a distinct responsibility. No subsystem is a monolith; each has a narrow, well-defined scope.

The system transforms a pose author's intent into a validated, rendered skeleton every frame. The pipeline flows from authoring through computation to display, with each subsystem performing exactly one transformation.

The architecture follows the Domain Analysis: domain entities (Segment, Articulation, Attachment Point, Anatomical Mobility, Environment) are not architecture. They are the subject matter that subsystems operate on. Subsystems are defined by responsibility, not by the domain entities they touch.

---

## 2. Identify Major Subsystems

### 2.1 Skeleton Model

**Purpose:** Defines the permanent structure of the skeleton — which segments exist, how they connect, what their lengths are, and what their mobility limits are.

**Owned responsibility:** The fixed, skeleton-wide structural definition. Bone lengths, proportions, joint connectivity, and anatomical mobility declarations.

**Inputs:** Author-provided skeleton parameters (bone lengths, proportions, constraint limits).

**Outputs:** A skeleton model that every other subsystem can reference.

**Lifetime:** Persistent. Created once per skeleton definition, shared across all frames and poses.

**Dependencies:** None. The Skeleton Model is the foundation; no other subsystem defines it.

---

### 2.2 Pose Authoring

**Purpose:** Declares what the body should do — the intent behind a pose. This includes contact declarations, limb targets, posture type, spine configuration, extremity overrides, gaze targets, and environmental context.

**Owned responsibility:** All declarative input. The author says what they want; the engine figures out how to achieve it.

**Inputs:** Exercise definition, frame progress, camera parameters, environment definition.

**Outputs:** A complete intent package — contact declarations, limb targets, posture intent, spine curve, extremity overrides, gaze target, environment context.

**Lifetime:** Per-frame. Recomputed each frame as the exercise progresses.

**Dependencies:** Reads the Skeleton Model (to know what joints and segments exist). Does not depend on any computation subsystem.

---

### 2.3 Forward Kinematics (FK)

**Purpose:** Propagates local transforms up the skeleton hierarchy to compute world-space positions and orientations for every joint.

**Owned responsibility:** Stateless transform propagation. Given local positions and rotations, compute world-space transforms.

**Inputs:** Local transforms (localPosition, localRotation) for every joint; skeleton hierarchy (parent-child relationships).

**Outputs:** World-space transforms (worldPosition, worldRotation) for every joint.

**Lifetime:** Transient. Computed each frame as part of the pipeline.

**Dependencies:** Reads the Skeleton Model (hierarchy). Reads local transforms from the Pose State. Produces world transforms consumed by Projection and Validation.

---

### 2.4 Inverse Kinematics (IK)

**Purpose:** Solves for joint angles that place an end-effector (attachment point in IK effector role) at a target world-space position, respecting anatomical mobility limits.

**Owned responsibility:** World-space limb solving. Two-bone analytical IK, straight-limb bend fallback, bone-length preservation, default pole vector generation.

**Inputs:** Root world position, target world position, bone lengths, world-space pole vector, mobility limits, contact constraints.

**Outputs:** Solved local rotations for the limb chain; clamp amount stamps; bone-length verification; straight-intent-dropped flag.

**Lifetime:** Transient. Computed each frame for each limb target.

**Dependencies:** Reads the Skeleton Model (bone lengths, mobility limits). Reads the Pose State (root position, contact state). Does not know about rendering or projection.

---

### 2.5 Constraint Solver

**Purpose:** Enforces postural constraints after IK solving — root positioning from contacts, posture CCD, contact conflict resolution, inter-frame smoothing.

**Owned responsibility:** Root transform authority and posture resolution. The solver is the sole mover of the root/pelvis transform.

**Inputs:** Pose intent (contacts, posture intent, contact precedence), IK-solved limb transforms, skeleton model (mobility limits).

**Outputs:** Final root transform; adjusted joint angles for posture; contact conflict resolution; temporal smoothing deltas; stamp data (root translation/rotation delta).

**Lifetime:** Transient. Computed each frame after IK solving.

**Dependencies:** Reads the Skeleton Model (mobility limits). Reads IK output (limb transforms). Reads Pose Authoring output (contacts, posture intent). Does not know about rendering or projection.

---

### 2.6 Finalizer

**Purpose:** Applies post-solve geometric corrections — world-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction, and flattening to the final local-transform store.

**Owned responsibility:** Exclusive world↔local frame conversion. The Finalizer is the only subsystem that writes local transforms after the solver has settled.

**Inputs:** Solver-settled world transforms; skeleton model (segment lengths, proportions); authored intent (chest rotation, extremity overrides).

**Outputs:** Final local transforms (localPosition, localRotation) for every joint; derived extremity orientations; chest-frame reconstruction.

**Lifetime:** Transient. Computed each frame after the Constraint Solver.

**Dependencies:** Reads the Skeleton Model. Reads Solver output (world transforms). Does not move solver-settled contact end-effectors. Does not know about rendering or projection.

---

### 2.7 Validator

**Purpose:** Checks the final pose against biomechanical rules — bone lengths preserved, joints within mobility limits, landmarks in valid regions, bilateral symmetry holds.

**Owned responsibility:** Read-only correctness verification. The validator never mutates the pose.

**Inputs:** Final pose state (local and world transforms); skeleton model (mobility limits, bone lengths); intent (ROM declarations); environment (ground plane, props).

**Outputs:** Validation report (issues, severities, results).

**Lifetime:** Transient. Runs once per frame after the Finalizer.

**Dependencies:** Reads the Skeleton Model. Reads the Finalized pose state. Reads Pose Authoring output (environment). Does not write to any pose state.

---

### 2.8 Projection

**Purpose:** Transforms world-space skeleton transforms into screen-space coordinates for display.

**Owned responsibility:** 3D-to-2D transformation. Perspective projection, viewport mapping, screen-space compensation.

**Inputs:** World-space transforms (from FK); camera parameters (view position, projection settings).

**Outputs:** Screen-space skeleton positions; exercise snapshot.

**Lifetime:** Transient. Computed each frame after FK.

**Dependencies:** Reads FK output (world transforms). Reads camera parameters from Pose Authoring. Does not know about IK, constraints, or the solver.

---

### 2.9 Rendering

**Purpose:** Draws the skeleton on screen — bones, joints, and visual styling.

**Owned responsibility:** Visual representation only. Rendering knows about bones, colors, thickness, and screen positions. It knows nothing about IK, constraints, or pose intent.

**Inputs:** Screen-space skeleton positions; bone definitions (lengths, thickness, color); camera parameters.

**Outputs:** Framebuffer output (drawn skeleton).

**Lifetime:** Transient. Executed each frame after Projection.

**Dependencies:** Reads Projection output (screen-space positions). Reads bone definitions from the Skeleton Model. Does not know about IK, constraints, or the solver.

---

### 2.10 Environment

**Purpose:** Defines the physical world the body exists in — ground plane, props (boxes, steps, benches, walls), and their geometric properties.

**Owned responsibility:** Environmental context. The environment defines what surfaces are available for contact. It does not contain pose state.

**Inputs:** Author-provided environment definition (ground level, prop positions, sizes, types).

**Outputs:** Surface definitions (ground plane, prop geometries, normals) consumed by the Constraint Solver and Validator.

**Lifetime:** Persistent per exercise definition. May change between poses but is fixed during a single pose evaluation.

**Dependencies:** None. The Environment is declared by the author and consumed by the solver and validator.

---

### 2.11 Serialization / Asset Definitions

**Purpose:** Loads, stores, and transmits skeleton definitions, pose configurations, and validation profiles.

**Owned responsibility:** Data persistence and transport. Serialization does not interpret or transform data — it moves it between storage and runtime.

**Inputs:** Serialized data (JSON, binary, or other format).

**Outputs:** Deserialized runtime objects; serialized output for transport or storage.

**Lifetime:** Persistent for definitions; transient for runtime state.

**Dependencies:** Reads the Skeleton Model structure. Does not modify any runtime state.

---

### 2.12 Animation

**Purpose:** Drives temporal changes in pose parameters — motion curves, drivers, and interpolation between keyframes.

**Owned responsibility:** Time-based parameter interpolation. Animation knows about timing, curves, and progress. It does not know about IK, constraints, or geometry.

**Inputs:** Motion driver definitions (curves, keyframes, timing); frame progress.

**Outputs:** Interpolated parameter values consumed by Pose Authoring.

**Lifetime:** Transient per frame. Driven by the exercise timeline.

**Dependencies:** Reads motion driver definitions. Feeds interpolated values into Pose Authoring. Does not know about the solver, FK, or rendering.

---

## 3. Dependency Rules

### Allowed dependency directions

```
Skeleton Model
    ↑ (read-only)
    |
Pose Authoring ← Animation
    ↓
IK Solver ← Skeleton Model
    ↓
Constraint Solver ← IK Solver, Pose Authoring, Skeleton Model
    ↓
Finalizer ← Constraint Solver, Skeleton Model
    ↓
FK ← Finalizer, Skeleton Model
    ↓
Projection ← FK, Pose Authoring (camera)
    ↓
Rendering ← Projection, Skeleton Model (bone visuals)
    ↓
Validator ← Finalizer, Skeleton Model, Pose Authoring (environment)
```

### Forbidden directions

- **Rendering must not know about IK.** Rendering only receives screen-space positions.
- **IK must not know about rendering.** IK operates in world space; it has no concept of screen coordinates.
- **Validator must not mutate poses.** Validation is read-only. It produces reports, not corrections.
- **Definitions must remain immutable.** The Skeleton Model is never modified at runtime.
- **Environment must not contain pose state.** The environment defines surfaces; it does not track where the body is.
- **Pose Authoring must not compute geometry.** Pose Authoring declares intent; it does not solve for positions.
- **Constraint Solver must not know about rendering.** The solver operates on world-space transforms; it has no concept of screen space.
- **Finalizer must not move solver-settled contact end-effectors.** The Finalizer respects the solver's contact settlements.

---

## 4. Data Flow

```
Author
  ↓ (declares intent: contacts, targets, posture, environment)
Pose Authoring
  ↓ (produces intent package)
IK Solver
  ↓ (solves limb transforms in world space)
Constraint Solver
  ↓ (resolves root position, posture, contact conflicts)
Finalizer
  ↓ (converts world→local, derives extremities, reconstructs chest)
FK
  ↓ (propagates local transforms to world space)
Projection
  ↓ (converts world→screen space)
Rendering
  ↓ (draws skeleton to framebuffer)
Validator (parallel, reads all stages)
  ↓ (produces validation report)
```

### Major transformations

1. **Intent → World limb transforms** (IK Solver)
2. **World limb transforms → Root + posture settlement** (Constraint Solver)
3. **World transforms → Local transforms** (Finalizer)
4. **Local transforms → World transforms** (FK)
5. **World transforms → Screen-space positions** (Projection)
6. **Screen-space positions → Visual output** (Rendering)
7. **Pose state → Validation report** (Validator)

---

## 5. Ownership

### Skeleton Model
- **Owns:** Bone lengths, proportions, joint connectivity, mobility limits.
- **Reads:** Nothing from other subsystems.
- **Produces:** The skeleton structure that all other subsystems reference.
- **Must never modify:** Its own data is immutable at runtime.

### Pose Authoring
- **Owns:** Intent declarations (contacts, limb targets, posture, spine curve, extremity overrides, gaze, environment, camera).
- **Reads:** The Skeleton Model (to know what joints and segments exist).
- **Produces:** The intent package consumed by IK, Constraint Solver, Validator, and Projection.
- **Must never modify:** The Skeleton Model or any runtime state.

### IK Solver
- **Owns:** Limb solve results, bone-length verification, clamp stamps, default pole vectors.
- **Reads:** Skeleton Model (bone lengths, mobility limits), Pose Authoring output (targets), Pose State (root position, contacts).
- **Produces:** Solved local rotations for limb chains; stamps.
- **Must never modify:** Root transform, contact state, or any non-limb joint.

### Constraint Solver
- **Owns:** Root transform, posture resolution, contact conflict resolution, inter-frame smoothing.
- **Reads:** Skeleton Model (mobility limits), IK output (limb transforms), Pose Authoring output (contacts, posture intent).
- **Produces:** Final root transform, adjusted joint angles, temporal deltas, stamps.
- **Must never modify:** Limb IK results (only reads them), intent declarations.

### Finalizer
- **Owns:** World↔local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction.
- **Reads:** Skeleton Model (segment lengths, proportions), Solver output (world transforms), Pose Authoring output (authored chest rotation, extremity overrides).
- **Produces:** Final local transforms, derived extremity orientations.
- **Must never modify:** Solver-settled contact end-effectors, root transform, or intent declarations.

### FK
- **Owns:** Stateless transform propagation.
- **Reads:** Skeleton Model (hierarchy), Finalizer output (local transforms).
- **Produces:** World-space transforms.
- **Must never modify:** Local transforms or intent declarations.

### Validator
- **Owns:** Rule checks (bone lengths, ROM, penetration, symmetry, reachability).
- **Reads:** Skeleton Model (limits, lengths), Finalized pose state, Pose Authoring output (environment, ROM).
- **Produces:** Validation report.
- **Must never modify:** Any pose state, any runtime data.

### Projection
- **Owns:** 3D-to-2D transformation.
- **Reads:** FK output (world transforms), Pose Authoring output (camera).
- **Produces:** Screen-space positions.
- **Must never modify:** Any pose state or skeleton data.

### Rendering
- **Owns:** Visual representation (bones, joints, colors, thickness).
- **Reads:** Projection output (screen positions), Skeleton Model (bone visuals).
- **Produces:** Framebuffer output.
- **Must never modify:** Any pose state, any skeleton data, any solver output.

### Environment
- **Owns:** Ground plane, prop definitions, surface normals.
- **Reads:** Nothing from runtime subsystems.
- **Produces:** Environmental surface data consumed by Constraint Solver and Validator.
- **Must never contain:** Pose state, joint transforms, or solver output.

### Animation
- **Owns:** Time-based parameter interpolation.
- **Reads:** Motion driver definitions.
- **Produces:** Interpolated parameter values.
- **Must never modify:** Skeleton Model, pose state, or solver output.

### Serialization / Asset Definitions
- **Owns:** Data persistence and transport.
- **Reads:** Serialized data.
- **Produces:** Deserialized objects or serialized output.
- **Must never modify:** Runtime state or skeleton model.

---

## 6. Lifecycle

### Definitions (persistent)
- **Skeleton Model:** Created once when the exercise is loaded. Lives for the lifetime of the exercise session. Immutable at runtime.
- **Environment:** Created once per exercise definition. May change between exercises but is fixed during a single pose evaluation. Immutable at runtime.
- **Animation drivers:** Created once per exercise. Immutable at runtime.

### Runtime State (per-frame, transient)
- **Pose Intent:** Created each frame by Pose Authoring. Consumed by IK, Constraint Solver, Validator, and Projection.
- **IK Results:** Created each frame by IK Solver. Consumed by Constraint Solver.
- **Constraint Solver Results:** Created each frame by Constraint Solver. Consumed by Finalizer.
- **Finalizer Results:** Created each frame by Finalizer. Consumed by FK and Validator.
- **FK Results:** Created each frame by FK. Consumed by Projection and Validator.
- **Projection Results:** Created each frame by Projection. Consumed by Rendering.
- **Validation Report:** Created each frame by Validator. Consumed by the application layer.

### Pipeline State (transient, intermediate)
- **World transforms:** Exist between FK and Projection.
- **Local transforms:** Exist between Finalizer and FK.
- **Screen-space positions:** Exist between Projection and Rendering.

---

## 7. Architectural Boundaries

### Rendering must not know IK
Rendering receives only screen-space positions. It has no concept of joint angles, pole vectors, or solver targets.

### IK must not know rendering
IK operates in world space with world-space targets. It has no concept of screen coordinates, viewport, or visual styling.

### Validator must not mutate poses
Validation reads the finalized pose state and produces a report. It never writes to any pose state or influences geometry.

### Definitions must remain immutable
The Skeleton Model (bone lengths, proportions, connectivity, mobility limits) is never modified at runtime. All runtime mutations happen in transient state objects.

### Environment must not contain pose state
The Environment defines surfaces (ground plane, prop positions, sizes). It does not track where the body is, what joints are touching, or what the solver computed.

### Pose Authoring must not compute geometry
Pose Authoring declares what the body should do (contacts, targets, posture). It does not solve for positions, compute transforms, or apply constraints.

### Constraint Solver must not know about rendering
The solver operates on world-space transforms and produces adjusted joint angles. It has no concept of screen space, viewport, or visual output.

### Finalizer must not move solver-settled contact end-effectors
The Finalizer respects the solver's contact settlements. If the solver has positioned a contact point, the Finalizer must not displace it.

### Animation must not know about IK or constraints
Animation drives time-based parameter interpolation. It does not solve for joint angles or enforce postural constraints.

### Serialization must not interpret data
Serialization moves data between storage and runtime. It does not transform, validate, or interpret the data it handles.

---

## 8. Stable Interfaces

### Intent → Pipeline
Pose Authoring produces an intent package. The pipeline (IK, Constraint Solver, Finalizer) consumes it. The interface is the intent package itself — a self-contained declaration of contacts, targets, posture, spine curve, extremity overrides, gaze, and environment.

### Pipeline → Pose State
Each pipeline stage produces its output in the Pose State. The interface is the Pose State — a single carrier of both intent and derived state.

### Pipeline → Validator
The Validator reads the finalized Pose State and the Skeleton Model. The interface is read-only access to pose state and skeleton parameters.

### Pipeline → Projection
Projection reads world-space transforms from FK and camera parameters from Pose Authoring. The interface is world-space transform data and camera configuration.

### Projection → Rendering
Projection produces screen-space positions. Rendering consumes them. The interface is 2D screen coordinates and bone connectivity.

### Definitions → FK
FK reads the Skeleton Model (hierarchy) and local transforms from the Finalizer. The interface is the skeleton hierarchy and local transform data.

### Definitions → Solver
The Constraint Solver and IK Solver read the Skeleton Model (bone lengths, mobility limits). The interface is skeleton parameters.

### Environment → Solver and Validator
The Constraint Solver and Validator read environmental surface data. The interface is ground plane and prop definitions.

### Animation → Pose Authoring
Animation feeds interpolated parameter values into Pose Authoring. The interface is a set of time-varying parameter values.

---

## 9. Future Extension

### Physics
A physics subsystem would operate between the Constraint Solver and the Finalizer. It would receive world-space transforms and produce physically plausible adjustments (gravity, momentum, contact forces). It would not know about rendering or projection. It would consume the same Skeleton Model and Pose Authoring intent that the solver consumes.

### Muscles
A muscle simulation subsystem would operate between the Finalizer and FK. It would receive local transforms and apply muscle-driven deformations (bulge, stretch, skinning). It would not know about IK, constraints, or rendering. It would consume the Skeleton Model (segment geometry) and the Finalizer's local transforms.

### Motion Capture
A motion capture subsystem would operate alongside Pose Authoring. It would provide real-time target positions and orientations from external tracking data. It would not know about IK, constraints, or rendering. It would feed target data into the same intent interface that manual authoring uses.

### Networking
A networking subsystem would operate alongside Serialization. It would transmit pose state, intent, and validation results between a server and clients. It would not know about IK, constraints, or rendering. It would consume and produce the same data structures that local serialization handles.

### Exercise Library
An exercise library would operate alongside Pose Authoring. It would provide pre-built intent packages (contact declarations, targets, posture types) for common exercises. It would not know about IK, constraints, or rendering. It would feed intent data into the same interface that manual authoring uses.

### Animation Blending
An animation blending subsystem would operate between Animation and Pose Authoring. It would interpolate between multiple motion drivers and produce a blended parameter output. It would not know about IK, constraints, or rendering. It would feed blended values into the same intent interface that single-driver animation uses.

---

*This document describes architecture at the subsystem level. It does not specify classes, packages, files, or implementation details. All architectural decisions are derived from the Domain Analysis and the principles stated in the MonkEngine Design Principles.*