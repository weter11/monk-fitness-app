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

**Dependencies:** Reads the Skeleton Model (hierarchy). Reads local transforms from the Transform State. Produces world transforms consumed by Presentation and Validation.

---

### 2.4 Inverse Kinematics (IK)

**Purpose:** Solves for joint angles that place an end-effector (attachment point in IK effector role) at a target world-space position, respecting anatomical mobility limits.

**Owned responsibility:** World-space limb solving. Two-bone analytical IK, straight-limb bend fallback, bone-length preservation, default pole vector generation.

**Inputs:** Root world position, target world position, bone lengths, world-space pole vector, mobility limits, contact constraints.

**Outputs:** Solved local rotations for the limb chain; clamp amount stamps; bone-length verification; straight-intent-dropped flag.

**Lifetime:** Transient. Computed each frame for each limb target.

**Dependencies:** Reads the Skeleton Model (bone lengths, mobility limits). Reads the Intent State and the Solver State. Does not know about rendering or presentation.

---

### 2.5 Constraint Solver

**Purpose:** Enforces postural constraints after IK solving — root positioning from contacts, posture CCD, contact conflict resolution, inter-frame smoothing.

**Owned responsibility:** Root transform authority and posture resolution. The solver is the sole mover of the root/pelvis transform.

**Inputs:** Intent State (contacts, posture intent, contact precedence), IK-solved limb transforms, skeleton model (mobility limits).

**Outputs:** Final root transform; adjusted joint angles for posture; contact conflict resolution; temporal smoothing deltas; stamp data (root translation/rotation delta).

**Lifetime:** Transient. Computed each frame after IK solving.

**Dependencies:** Reads the Skeleton Model (mobility limits). Reads IK output (limb transforms). Reads Intent State (contacts, posture intent). Does not know about rendering or presentation.

---

### 2.6 Finalizer

**Purpose:** Applies post-solve geometric corrections — world-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction, and flattening to the final local-transform store.

**Owned responsibility:** Exclusive world-to-local frame conversion. The Finalizer is the only subsystem that writes local transforms after the solver has settled.

**Inputs:** Solver State (world transforms); skeleton model (segment lengths, proportions); Intent State (authored chest rotation, extremity overrides).

**Outputs:** Final local transforms (localPosition, localRotation) for every joint; derived extremity orientations; chest-frame reconstruction.

**Lifetime:** Transient. Computed each frame after the Constraint Solver.

**Dependencies:** Reads the Skeleton Model. Reads Solver State (world transforms). Does not move solver-settled contact end-effectors. Does not know about rendering or presentation.

---

### 2.7 Validator

**Purpose:** Checks the final pose against biomechanical rules — bone lengths preserved, joints within mobility limits, landmarks in valid regions, bilateral symmetry holds.

**Owned responsibility:** Read-only correctness verification. The validator never mutates the pose.

**Inputs:** Transform State (local and world transforms); Skeleton Model (mobility limits, bone lengths); Intent State (ROM declarations); Environment (ground plane, props).

**Outputs:** Validation report (issues, severities, results).

**Lifetime:** Transient. Runs once per frame after the Finalizer.

**Dependencies:** Reads the Skeleton Model. Reads the Finalized Transform State. Reads Intent State (environment). Does not write to any pose state.

---

### 2.8 Projection

**Purpose:** Transforms world-space skeleton transforms into screen-space coordinates for display.

**Owned responsibility:** 3D-to-2D transformation. Perspective projection, viewport mapping, screen-space compensation.

**Inputs:** Transform State (world transforms from FK); camera parameters (view position, projection settings).

**Outputs:** Screen-space skeleton positions; exercise snapshot.

**Lifetime:** Transient. Computed each frame after FK.

**Dependencies:** Reads FK output (world transforms). Reads camera parameters from Intent State. Does not know about IK, constraints, or the solver.

---

### 2.9 Rendering

**Purpose:** Draws the skeleton on screen — bones, joints, and visual styling.

**Owned responsibility:** Visual representation only. Rendering knows about bones, colors, thickness, and screen positions. It knows nothing about IK, constraints, or pose intent.

**Inputs:** Presentation State (screen-space positions); bone definitions (lengths, thickness, color); camera parameters.

**Outputs:** Framebuffer output (drawn skeleton).

**Lifetime:** Transient. Executed each frame after Projection.

**Dependencies:** Reads Presentation State (screen-space positions). Reads bone definitions from the Skeleton Model. Does not know about IK, constraints, or the solver.

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
- **Reads:** Skeleton Model (bone lengths, mobility limits), Intent State (targets), Solver State (root position, contacts).
- **Produces:** Solved local rotations for limb chains; stamps.
- **Must never modify:** Root transform, contact state, or any non-limb joint.

### Constraint Solver
- **Owns:** Root transform, posture resolution, contact conflict resolution, inter-frame smoothing.
- **Reads:** Skeleton Model (mobility limits), IK output (limb transforms), Intent State (contacts, posture intent).
- **Produces:** Final root transform, adjusted joint angles, temporal deltas, stamps.
- **Must never modify:** Limb IK results (only reads them), intent declarations.

### Finalizer
- **Owns:** World-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction.
- **Reads:** Skeleton Model (segment lengths, proportions), Solver State (world transforms), Intent State (authored chest rotation, extremity overrides).
- **Produces:** Final local transforms, derived extremity orientations.
- **Must never modify:** Solver-settled contact end-effectors, root transform, or intent declarations.

### FK
- **Owns:** Stateless transform propagation.
- **Reads:** Skeleton Model (hierarchy), Finalizer output (local transforms).
- **Produces:** World-space transforms.
- **Must never modify:** Local transforms or intent declarations.

### Validator
- **Owns:** Rule checks (bone lengths, ROM, penetration, symmetry, reachability).
- **Reads:** Skeleton Model (limits, lengths), Finalized Transform State, Intent State (environment, ROM).
- **Produces:** Validation report.
- **Must never modify:** Any pose state, any runtime data.

### Projection
- **Owns:** 3D-to-2D transformation.
- **Reads:** FK output (world transforms), Intent State (camera).
- **Produces:** Screen-space positions.
- **Must never modify:** Any pose state or skeleton data.

### Rendering
- **Owns:** Visual representation (bones, joints, colors, thickness).
- **Reads:** Presentation State (screen positions), Skeleton Model (bone visuals).
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

## 6. Runtime Ownership

Every runtime object has exactly one subsystem that owns its creation, its mutation, and its destruction. No subsystem may create, mutate, or destroy a runtime object owned by another subsystem.

### Owned by Skeleton Model
- Skeleton definitions (bone lengths, proportions, connectivity, mobility limits)
- Created: when the exercise is loaded
- Destroyed: when the exercise is unloaded
- Mutated by: none (immutable at runtime)

### Owned by Pose Authoring
- Pose intent (contacts, limb targets, posture, spine curve, extremity overrides, gaze, environment, camera)
- Created: each frame, at the start of the pipeline
- Destroyed: at the end of the frame, after all consumers have read it
- Mutated by: Pose Authoring only

### Owned by IK Solver
- Limb solve results (solved local rotations, clamp stamps, bone-length verification, straight-intent-dropped flag)
- Created: each frame, when IK solving begins for a limb
- Destroyed: when consumed by the Constraint Solver
- Mutated by: IK Solver only

### Owned by Constraint Solver
- Root transform, posture resolution, contact conflict resolution, temporal smoothing deltas, stamps
- Created: each frame, after IK solving completes
- Destroyed: when consumed by the Finalizer
- Mutated by: Constraint Solver only

### Owned by Finalizer
- Final local transforms (localPosition, localRotation) for every joint
- Derived extremity orientations
- Created: each frame, after Constraint Solver output is available
- Destroyed: when consumed by FK
- Mutated by: Finalizer only

### Owned by FK
- World-space transforms (worldPosition, worldRotation) for every joint
- Created: each frame, when FK propagation begins
- Destroyed: when consumed by Projection and Validator
- Mutated by: FK only

### Owned by Projection
- Screen-space positions
- Created: each frame, when projection begins
- Destroyed: when consumed by Rendering
- Mutated by: Projection only

### Owned by Validator
- Validation report (issues, severities, results)
- Created: each frame, when validation runs
- Destroyed: when consumed by the application layer
- Mutated by: Validator only

### Owned by Environment
- Ground plane, prop definitions, surface normals
- Created: when the exercise is loaded
- Destroyed: when the exercise is unloaded
- Mutated by: none (immutable during a single pose evaluation)

### Owned by Animation
- Interpolated parameter values
- Created: each frame, when animation sampling occurs
- Destroyed: when consumed by Pose Authoring
- Mutated by: Animation only

### Owned by Serialization / Asset Definitions
- Serialized data, deserialized objects
- Created: on load or on demand
- Destroyed: when no longer referenced
- Mutated by: Serialization only

---

## 7. Mutability Rules

Every runtime object is either immutable or mutable. Mutability is declared at the architectural level and must not be violated by any subsystem.

### Immutable objects (must never be modified after creation)
- Skeleton definitions (bone lengths, proportions, connectivity, mobility limits)
- Environment definitions (ground plane, prop geometries, surface normals)
- Animation driver definitions (curves, keyframes, timing)
- Skeleton hierarchy (parent-child relationships)

### Mutable objects (may be modified only by their owning subsystem)
- Pose intent (owned by Pose Authoring)
- IK results (owned by IK Solver)
- Constraint Solver results (owned by Constraint Solver)
- Final local transforms (owned by Finalizer)
- World-space transforms (owned by FK)
- Screen-space positions (owned by Projection)
- Validation report (owned by Validator)
- Interpolated parameter values (owned by Animation)

### Read-only access
- Any subsystem may read immutable objects.
- Any subsystem may read mutable objects that it does not own, provided the owning subsystem has completed writing them.
- No subsystem may write to a mutable object it does not own.

### Transfer of ownership
- When a subsystem produces a mutable object and passes it to a consumer, ownership transfers to the consumer for the duration of consumption.
- The producing subsystem must not modify the object after transferring ownership.
- The consuming subsystem must not transfer ownership to a third party without explicit architectural authorization.

---

## 8. Runtime State Model

The runtime state of the system is organized into five architectural state categories. Each category has a single writer and zero or more readers. State categories are consumed in dependency order.

### Intent State
- **Writer:** Pose Authoring
- **Contents:** Contact declarations, limb targets, posture intent, spine curve, extremity overrides, gaze target, environment context, camera parameters
- **Consumers:** IK Solver, Constraint Solver, Validator, Projection
- **Mutability:** Mutable (recreated each frame by Pose Authoring)

### Solver State
- **Writer:** IK Solver (limb results), Constraint Solver (posture resolution)
- **Contents:** Solved limb transforms, root transform, posture adjustments, contact conflict resolution, temporal smoothing deltas, stamps
- **Consumers:** Finalizer
- **Mutability:** Mutable (produced by IK Solver, then augmented by Constraint Solver)

### Transform State
- **Writer:** Finalizer (local transforms), FK (world transforms), Projection (screen-space positions)
- **Contents:** Local transforms, world-space transforms, screen-space positions
- **Consumers:** Rendering, Validator, Projection (world→screen), FK (local→world)
- **Mutability:** Mutable (each stage produces the next form)

### Presentation State
- **Writer:** Projection
- **Contents:** Screen-space skeleton positions, exercise snapshot
- **Consumers:** Rendering
- **Mutability:** Mutable (produced by Projection, consumed by Rendering)

### Validation State
- **Writer:** Validator
- **Contents:** Validation report (issues, severities, results)
- **Consumers:** Application layer
- **Mutability:** Mutable (produced by Validator, consumed by application)

### State transition rules
- State categories flow in dependency order: Intent State → Solver State → Transform State → Presentation State → Validation State.
- Each state category is written by exactly one subsystem.
- Each state category is read by zero or more downstream subsystems.
- No state category may be read while it is being written.
- No state category may be written by more than one subsystem.

---

## 9. Execution Ownership

The Frame Clock is the sole execution initiator. Each subsystem has exactly one upstream producer. Subsystems never directly trigger one another.

### Frame Clock
- The Frame Clock initiates each frame.
- The Frame Clock is the only entity that starts execution.
- The Frame Clock does not belong to any subsystem.

### Upstream producer relationships
- Pose Authoring is triggered by the Frame Clock.
- Animation is triggered by the Frame Clock.
- IK Solver is triggered by Pose Authoring (its upstream producer).
- Constraint Solver is triggered by IK Solver (its upstream producer).
- Finalizer is triggered by Constraint Solver (its upstream producer).
- FK is triggered by Finalizer (its upstream producer).
- Projection is triggered by FK (its upstream producer).
- Rendering is triggered by Projection (its upstream producer).
- Validator is triggered by Finalizer (its upstream producer).

### Dependency preservation
- Each subsystem depends only on its upstream producer and on immutable definitions (Skeleton Model, Environment).
- No subsystem may depend on a downstream subsystem.
- No subsystem may trigger a subsystem that is not its downstream consumer.

---

## 10. Frame Lifecycle

Each frame follows a deterministic sequence of architectural phases. The phases are ordered by dependency: each phase depends on the output of the preceding phase.

```
Intent → Solve → Finalize → Transform → Presentation → Validation
```

- **Intent:** Pose Authoring produces the intent package.
- **Solve:** IK Solver and Constraint Solver resolve limb transforms and posture.
- **Finalize:** Finalizer converts world transforms to local transforms and derives extremities.
- **Transform:** FK propagates local transforms to world space; Projection converts world transforms to screen space.
- **Presentation:** Rendering draws the skeleton to the framebuffer.
- **Validation:** Validator checks the final pose and produces a report.

The detailed phase-by-phase lifecycle, including iteration counts, retry behavior, and recovery strategies, belongs in Pipeline Specification v3.

---

## 11. Architectural Boundaries

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

## 12. Architectural Invariants

The following invariants must hold at all times during execution. Any violation is an architectural defect.

1. **The Skeleton Model is immutable at runtime.** No subsystem may modify bone lengths, proportions, connectivity, or mobility limits after the Skeleton Model is created.

2. **The Environment is immutable during a single pose evaluation.** No subsystem may modify ground plane, prop geometries, or surface normals while a pose is being solved.

3. **Pose Authoring is the sole writer of intent.** No subsystem may write to the Intent State except Pose Authoring.

4. **IK Solver is the sole writer of limb solve results.** No subsystem may write to Solver State limb results except IK Solver.

5. **Constraint Solver is the sole writer of posture-resolved results.** No subsystem may write to Solver State posture results except Constraint Solver.

6. **Finalizer is the sole writer of final local transforms.** No subsystem may write to Transform State local transforms except Finalizer.

7. **FK is the sole writer of world-space transforms.** No subsystem may write to Transform State world transforms except FK.

8. **Projection is the sole writer of screen-space positions.** No subsystem may write to Presentation State except Projection.

9. **Validator is the sole writer of validation reports.** No subsystem may write to Validation State except Validator.

10. **Animation is the sole writer of interpolated parameters.** No subsystem may write to Intent State interpolated parameters except Animation.

11. **No state category may be read while it is being written.**

12. **No state category may be written by more than one subsystem.**

13. **No subsystem may read a state category that it also writes.**

14. **Dependencies are acyclic.** The dependency graph has no cycles.

---

## 13. Failure Model

The architecture defines the following principles for handling failures. Subsystem-specific recovery behavior belongs in subsystem specifications.

### Failure isolation
- A failure in one subsystem must not propagate to other subsystems unless the failure produces invalid output that is consumed by a downstream subsystem.
- If a subsystem produces invalid output (NaN, infinite values), downstream subsystems must detect and handle the invalid values gracefully.

### Degraded output
- Each subsystem must produce output for every frame, even if the output is a degraded or clamped result.
- No subsystem may produce no output. A missing output is a more severe failure than a degraded output.
- Degradation is contained within the subsystem that produced it.

### Failure propagation
- Failures propagate only through subsystem outputs. A subsystem that receives invalid input from an upstream subsystem must handle it gracefully and produce its own output (degraded if necessary).
- Failures must not propagate through shared mutable state. Ownership and mutability rules ensure that each subsystem writes only to its own state categories.

### Diagnostic logging
- Each subsystem must record failures in a diagnostic log. The diagnostic log is consumed by the application layer.
- The architecture does not define retry logic, circuit breakers, or fallback subsystems. These are implementation concerns of Pipeline Specification v3 and subsystem specifications.

---

## 14. Threading Assumptions

The architecture makes the following assumptions about threading and concurrency.

### Determinism
- The architecture requires deterministic behavior. Given the same inputs, the same outputs must be produced.
- This guarantee holds on a single thread. Parallel execution may introduce non-determinism if architectural contracts are not maintained.

### Ownership preservation
- Ownership and dependency rules must be preserved regardless of threading model.
- No mutable state object is shared between threads without explicit ownership transfer.

### Parallel execution
- Parallel execution is permitted when architectural contracts are maintained.
- Phases that have no data dependency on each other may execute concurrently.
- The architecture does not define synchronization primitives, lock-free data structures, or message queues. These are implementation concerns.

### No shared mutable state
- No mutable state category is shared between threads without explicit ownership transfer.
- Immutable objects (Skeleton Model, Environment, Animation drivers) may be read concurrently by multiple threads.

---

## 15. Stability Guarantees

The architecture provides the following architectural guarantees.

### Immutable definitions
- The Skeleton Model, Environment, and Animation driver definitions are immutable at runtime. No subsystem may modify them.

### Deterministic ownership
- Every runtime object has exactly one owner. Ownership is declared at the architectural level and must not be violated.

### Isolated mutation
- Each subsystem may mutate only the state categories it owns. Mutation of another subsystem's state category is an architectural violation.

### Bounded subsystem responsibilities
- Each subsystem has a narrow, well-defined scope. No subsystem may assume responsibilities outside its declared boundary.

### Acyclic dependencies
- The dependency graph between subsystems is acyclic. No subsystem may depend on a downstream subsystem.

### Implementation guarantees (belong to Pipeline Specification and subsystem specifications)
- The following are not architectural guarantees and must not be stated in this document: crash freedom, deadlock freedom, starvation freedom, or specific recovery behaviors. These belong to Pipeline Specification v3 and subsystem specifications.

---

## 16. Stable Interfaces

### Intent → Pipeline
Pose Authoring produces an intent package. The pipeline (IK, Constraint Solver, Finalizer) consumes it. The interface is the intent package itself — a self-contained declaration of contacts, targets, posture, spine curve, extremity overrides, gaze, and environment.

### Pipeline → Transform State
Each pipeline stage produces its output in the Transform State. The interface is the Transform State — a carrier of local and world transforms.

### Pipeline → Validation State
The Validator reads the finalized Transform State and the Skeleton Model. The interface is read-only access to transform state and skeleton parameters.

### Pipeline → Presentation State
Projection reads world-space transforms from FK and camera parameters from Intent State. The interface is world-space transform data and camera configuration.

### Presentation State → Rendering
Projection produces screen-space positions. Rendering consumes them. The interface is 2D screen coordinates and bone connectivity.

### Skeleton Model → FK
FK reads the Skeleton Model (hierarchy) and local transforms from the Finalizer. The interface is the skeleton hierarchy and local transform data.

### Skeleton Model → Solver
The Constraint Solver and IK Solver read the Skeleton Model (bone lengths, mobility limits). The interface is skeleton parameters.

### Environment → Solver and Validator
The Constraint Solver and Validator read environmental surface data. The interface is ground plane and prop definitions.

### Animation → Pose Authoring
Animation feeds interpolated parameter values into Pose Authoring. The interface is a set of time-varying parameter values.

---

## 17. Extension Points

The architecture defines the following extension points where new subsystems or capabilities may be added without modifying existing subsystems.

### Between Constraint Solver and Finalizer
A new subsystem may be inserted here to apply post-solve adjustments (e.g., physics, muscle simulation) before the Finalizer converts world transforms to local transforms. The new subsystem must consume Solver State and produce output that the Finalizer can consume. The Finalizer must not be modified to accommodate the new subsystem.

### Between Finalizer and FK
A new subsystem may be inserted here to apply additional local-transform adjustments (e.g., animation blending, procedural offsets) before FK propagates them to world space. The new subsystem must consume Transform State (local transforms) and produce output that FK can consume. FK must not be modified to accommodate the new subsystem.

### Alongside Pose Authoring
A new subsystem may be added to produce intent data (e.g., motion capture targets, exercise library presets) that is consumed by Pose Authoring. The new subsystem must not modify Pose Authoring's existing behavior. Pose Authoring must not be modified to accommodate the new subsystem.

### Alongside Animation
A new subsystem may be added to produce interpolated parameter values (e.g., animation blending, procedural animation) that are consumed by Pose Authoring. The new subsystem must not modify Animation's existing behavior. Animation must not be modified to accommodate the new subsystem.

### Alongside Serialization
A new subsystem may be added to provide alternative data transport (e.g., networking, streaming) that produces or consumes serialized data. The new subsystem must not modify Serialization's existing behavior. Serialization must not be modified to accommodate the new subsystem.

### Alongside Validator
A new subsystem may be added to perform additional validation checks (e.g., biomechanical plausibility, style compliance) that consume the same inputs as Validator. The new subsystem must not modify Validator's existing behavior. Validator must not be modified to accommodate the new subsystem.

### Extension rules
- New subsystems must not introduce new dependencies on existing subsystems that are not already in the allowed dependency graph.
- New subsystems must not modify the frame lifecycle phases. They may only insert new phases between existing phases.
- New subsystems must not violate any architectural invariant.
- New subsystems must follow the same ownership, mutability, and failure isolation rules as existing subsystems.

---

*This document describes architecture at the subsystem level. It does not specify classes, packages, files, or implementation details. All architectural decisions are derived from the Domain Analysis and the principles stated in the MonkEngine Design Principles.*
