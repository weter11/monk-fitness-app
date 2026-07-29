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

## 2. Major Subsystems

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

**Dependencies:** Reads the Skeleton Model (hierarchy). Reads local transforms from the Local Transform State. Produces world transforms consumed by the World Transform State and Validation.

---

### 2.4 Inverse Kinematics (IK)

**Purpose:** Solves for joint angles that place an end-effector (attachment point in IK effector role) at a target world-space position, respecting anatomical mobility limits.

**Owned responsibility:** World-space limb solving.

**Inputs:** Root world position, target world position, bone lengths, mobility limits, contact constraints.

**Outputs:** Solved local rotations for the limb chain; straight-intent-dropped flag.

**Lifetime:** Transient. Computed each frame for each limb target.

**Dependencies:** Reads the Skeleton Model (bone lengths, mobility limits). Reads the Intent State. Does not know about rendering or presentation.

---

### 2.5 Constraint Solver

**Purpose:** Enforces postural constraints — root positioning from contacts, posture resolution, and contact conflict resolution.

**Owned responsibility:** Root transform authority and posture resolution. The solver is the sole mover of the root/pelvis transform.

**Inputs:** Intent State (contacts, posture intent, contact precedence), IK results, skeleton model (mobility limits).

**Outputs:** Final root transform; adjusted joint angles for posture; contact conflict resolution; stamp data (root translation/rotation delta).

**Lifetime:** Transient. Computed each frame after IK solving.

**Dependencies:** Reads the Skeleton Model (mobility limits). Reads IK results. Reads Intent State (contacts, posture intent). Does not know about rendering or presentation.

---

### 2.6 Finalizer

**Purpose:** Applies geometric corrections — world-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction, and flattening to the final local-transform store.

**Owned responsibility:** Exclusive world-to-local frame conversion. The Finalizer is the only subsystem that writes local transforms after the solver has settled.

**Inputs:** IK results; skeleton model (segment lengths, proportions); Intent State (authored chest rotation, extremity overrides).

**Outputs:** Final local transforms (localPosition, localRotation) for every joint; derived extremity orientations; chest-frame reconstruction.

**Lifetime:** Transient. Computed each frame after the Constraint Solver.

**Dependencies:** Reads the Skeleton Model. Reads IK results. Does not move solver-settled contact end-effectors. Does not know about rendering or presentation.

---

### 2.7 Validator

**Purpose:** Checks the final pose against biomechanical rules — bone lengths preserved, joints within mobility limits, landmarks in valid regions, bilateral symmetry holds.

**Owned responsibility:** Read-only correctness verification. The validator never mutates the pose.

**Inputs:** Local Transform State; World Transform State; Skeleton Model (mobility limits, bone lengths); Intent State (ROM declarations); Environment (ground plane, props).

**Outputs:** Validation report (issues, severities, results).

**Lifetime:** Transient. Runs once per frame after the Finalizer.

**Dependencies:** Reads the Skeleton Model. Reads the Finalized Local Transform State and World Transform State. Reads Intent State (environment). Does not write to any pose state.

---

### 2.8 Projection

**Purpose:** Transforms world-space skeleton transforms into screen-space coordinates for display.

**Owned responsibility:** 3D-to-2D transformation.

**Inputs:** World Transform State; camera parameters (view position, projection settings).

**Outputs:** Screen-space skeleton positions; exercise snapshot.

**Lifetime:** Transient. Computed each frame after FK.

**Dependencies:** Reads FK output (world transforms). Reads camera parameters from Intent State. Does not know about IK, constraints, or the solver.

---

### 2.9 Rendering

**Purpose:** Draws the skeleton on screen — bones, joints, and visual styling.

**Owned responsibility:** Visual representation only. Rendering knows about bones, colors, thickness, and screen positions. It knows nothing about IK, constraints, or pose intent.

**Inputs:** Screen Transform State; bone definitions (lengths, thickness, color); camera parameters.

**Outputs:** Framebuffer output (drawn skeleton).

**Lifetime:** Transient. Executed each frame after Projection.

**Dependencies:** Reads Screen Transform State. Reads bone definitions from the Skeleton Model. Does not know about IK, constraints, or the solver.

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

**Purpose:** Drives temporal changes in pose parameters over time.

**Owned responsibility:** Time-based parameter interpolation. Animation knows about timing and progress. It does not know about IK, constraints, or geometry.

**Inputs:** Motion driver definitions; frame progress.

**Outputs:** Interpolated parameter values consumed by Pose Authoring.

**Lifetime:** Transient per frame. Driven by the exercise timeline.

**Dependencies:** Reads motion driver definitions. Feeds interpolated values into Pose Authoring. Does not know about the solver, FK, or rendering.

---

## 3. Dependency Rules

### Execution Initiation

The Frame Clock is the sole execution initiator. Each subsystem has exactly one upstream producer. Subsystems never directly trigger one another.

- The Frame Clock initiates each frame.
- The Frame Clock does not belong to any subsystem.
- Pose Authoring and Animation are triggered by the Frame Clock.
- Each downstream subsystem is triggered by its upstream producer.

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
  ↓ (produces limb solve results)
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

Every runtime object has exactly one subsystem that owns its creation, its mutation, and its destruction. No subsystem may create, mutate, or destroy a runtime object owned by another subsystem.

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
- **Owns:** Limb solve results.
- **Reads:** Skeleton Model (bone lengths, mobility limits), Intent State (targets).
- **Produces:** Solved local rotations for limb chains; stamps.
- **Must never modify:** Root transform, contact state, or any non-limb joint.

### Constraint Solver
- **Owns:** Root transform, posture resolution, contact conflict resolution.
- **Reads:** Skeleton Model (mobility limits), IK results, Intent State (contacts, posture intent).
- **Produces:** Final root transform, adjusted joint angles, temporal deltas, stamps.
- **Must never modify:** Limb IK results (only reads them), intent declarations.

### Finalizer
- **Owns:** World-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction.
- **Reads:** Skeleton Model (segment lengths, proportions), IK results, Intent State (authored chest rotation, extremity overrides).
- **Produces:** Final local transforms, derived extremity orientations.
- **Must never modify:** Solver-settled contact end-effectors, root transform, or intent declarations.

### FK
- **Owns:** Stateless transform propagation.
- **Reads:** Skeleton Model (hierarchy), Finalizer output (local transforms).
- **Produces:** World-space transforms.
- **Must never modify:** Local transforms or intent declarations.

### Validator
- **Owns:** Rule checks (bone lengths, ROM, penetration, symmetry, reachability).
- **Reads:** Skeleton Model (limits, lengths), Local Transform State, World Transform State, Intent State (environment, ROM).
- **Produces:** Validation report.
- **Must never modify:** Any pose state, any runtime data.

### Projection
- **Owns:** 3D-to-2D transformation.
- **Reads:** FK output (world transforms), Intent State (camera).
- **Produces:** Screen-space positions.
- **Must never modify:** Any pose state or skeleton data.

### Rendering
- **Owns:** Visual representation (bones, joints, colors, thickness).
- **Reads:** Screen Transform State, Skeleton Model (bone visuals).
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

### Ownership rules
- Every runtime object has exactly one owner.
- Ownership includes the right to create, mutate, and destroy the object.
- No subsystem may create, mutate, or destroy a runtime object owned by another subsystem.
- Immutable objects (Skeleton Model, Environment, Animation drivers) are owned by the architecture and may be read by any subsystem.

### Mutability rules
- Immutable objects must never be modified after creation.
- Mutable objects may be modified only by their owning subsystem.
- Any subsystem may read immutable objects.
- Any subsystem may read mutable objects that it does not own, provided the owning subsystem has completed writing them.
- No subsystem may write to a mutable object it does not own.

---

## 6. State Categories

The runtime state of the system is organized into architectural state categories. Each category has exactly one writer and zero or more readers. State categories are consumed in dependency order.

### Intent State
- **Writer:** Pose Authoring
- **Contents:** Contact declarations, limb targets, posture intent, spine curve, extremity overrides, gaze target, environment context, camera parameters
- **Consumers:** IK Solver, Constraint Solver, Validator, Projection
- **Mutability:** Mutable (recreated each frame by Pose Authoring)

### IK Result State
- **Writer:** IK Solver
- **Contents:** Solved limb transforms, straight-intent-dropped flag
- **Consumers:** Constraint Solver, Finalizer
- **Mutability:** Mutable (produced by IK Solver)

### Constraint Result State
- **Writer:** Constraint Solver
- **Contents:** Root transform, posture adjustments, contact conflict resolution, stamps
- **Consumers:** Finalizer
- **Mutability:** Mutable (produced by Constraint Solver)

### Local Transform State
- **Writer:** Finalizer
- **Contents:** Local transforms (localPosition, localRotation) for every joint; derived extremity orientations
- **Consumers:** FK, Validator
- **Mutability:** Mutable (produced by Finalizer)

### World Transform State
- **Writer:** FK
- **Contents:** World-space transforms (worldPosition, worldRotation) for every joint
- **Consumers:** Projection, Validator
- **Mutability:** Mutable (produced by FK)

### Screen Transform State
- **Writer:** Projection
- **Contents:** Screen-space positions, exercise snapshot
- **Consumers:** Rendering
- **Mutability:** Mutable (produced by Projection)

### Validation State
- **Writer:** Validator
- **Contents:** Validation report (issues, severities, results)
- **Consumers:** Application layer
- **Mutability:** Mutable (produced by Validator)

### State category rules
- Each state category is written by exactly one subsystem.
- Each state category is read by zero or more downstream subsystems.
- No state category may be read while it is being written.
- State categories flow in dependency order: Intent State → IK Result State → Constraint Result State → Local Transform State → World Transform State → Screen Transform State → Validation State.

---

## 7. Architectural Contracts

An architectural contract is a formal agreement between subsystems about what each subsystem guarantees to provide and what it guarantees not to do. Contracts define the responsibilities of subsystems to each other.

### General provider obligations
- Every subsystem must produce bounded output. No subsystem may produce undefined values.
- Every subsystem must produce output that is consistent with its declared inputs.

### General consumer obligations
- Every subsystem must validate the structural integrity of upstream output before consuming it.
- Every subsystem must handle invalid upstream output gracefully.
- Every subsystem must not modify upstream output that it does not own.

### General assumptions
- The architecture assumes that subsystems will not introduce circular dependencies.
- The architecture assumes that ownership and dependency rules will be preserved.

### General consequences of contract violation
- When a subsystem violates its contract, the architecture does not define recovery behavior. Recovery is an implementation concern of Pipeline Specification and subsystem specifications.
- The architecture defines only that violations are architectural defects.
- The architecture does not specify which subsystem should detect or handle a violation. Detection and handling are implementation concerns.

### Specific contracts

#### Contract: Pose Authoring → Pipeline
- **Provider guarantees:** Pose Authoring produces a complete intent package each frame. The intent package contains all contact declarations, limb targets, posture intent, spine curve, extremity overrides, gaze target, and environment context.
- **Consumer obligations:** Downstream subsystems must consume the intent package and must not modify it.

#### Contract: IK Solver → Constraint Solver
- **Provider guarantees:** IK Solver produces limb solve results for every limb target. Results are bounded.
- **Consumer obligations:** Constraint Solver must consume IK results and must not modify them.

#### Contract: Constraint Solver → Finalizer
- **Provider guarantees:** Constraint Solver produces a final root transform and posture-resolved joint angles. Contact settlements are final.
- **Consumer obligations:** Finalizer must consume Constraint Solver results and must not move solver-settled contact end-effectors.

#### Contract: Finalizer → FK
- **Provider guarantees:** Finalizer produces final local transforms for every joint. World-to-local conversion is complete. Extremities are derived.
- **Consumer obligations:** FK must consume local transforms and propagate them. FK must not modify local transforms.

#### Contract: FK → Projection
- **Provider guarantees:** FK produces world-space transforms for every joint. Transforms are consistent with the skeleton hierarchy.
- **Consumer obligations:** Projection must consume world transforms and produce screen-space positions.

#### Contract: Projection → Rendering
- **Provider guarantees:** Projection produces screen-space positions for every joint. Positions are within the viewport or flagged as out-of-viewport.
- **Consumer obligations:** Rendering must consume screen-space positions and produce visual output.

#### Contract: Validator → Application Layer
- **Provider guarantees:** Validator produces a validation report for every frame. The report contains all identified issues with severities.
- **Consumer obligations:** The application layer must consume the validation report.

---

## 8. Quality Attributes

The architecture is designed to achieve the following quality attributes.

### Deterministic
Given the same inputs (Skeleton Model, intent, environment, animation parameters), the architecture guarantees the same outputs (world-space transforms, screen-space positions, validation report) on every execution. This guarantee holds on a single thread. Parallel execution may introduce non-determinism if architectural contracts are not maintained.

### Modular
Each subsystem has a narrow, well-defined scope. No subsystem may assume responsibilities outside its declared boundary. Subsystems are defined by responsibility, not by the domain entities they touch.

### Testable
Each subsystem can be tested in isolation. The architectural contracts define the inputs and outputs of each subsystem, enabling unit testing of individual subsystems without requiring the full pipeline.

### Extensible
New subsystems can be added without modifying existing subsystems. Extension points are defined at architectural boundaries (see §13). New subsystems must follow the same ownership, mutability, and failure isolation rules as existing subsystems.

### Predictable
The architecture guarantees that subsystems will behave predictably within their declared boundaries. A subsystem's behavior is determined by its inputs and its contract — not by hidden state or external factors.

### Isolated
Failures in one subsystem are contained within that subsystem. Failures propagate only through subsystem outputs. A subsystem that receives invalid input from an upstream subsystem must handle it gracefully and produce its own output.

### Reproducible
The architecture guarantees that the same pose authoring input, environment, and animation parameters will produce the same skeleton configuration on every execution. This is a consequence of determinism and isolated state.

### Maintainable
The architecture is organized by responsibility, not by domain entities. Each subsystem has a single, well-defined purpose. This makes it possible to modify one subsystem without affecting others.

### Immutable definitions
The Skeleton Model, Environment, and Animation driver definitions are immutable at runtime. No subsystem may modify them. This ensures that the structural foundation of the system is stable and predictable.

---

## 9. Architectural Boundaries

### Rendering must not know IK
Rendering receives only screen-space positions. It has no concept of joint angles.

### IK must not know rendering
IK operates in world space. It has no concept of rendering.

### Validator must not mutate poses
Validation reads the finalized pose state and produces a report. It never writes to any pose state or influences geometry.

### Definitions must remain immutable
The Skeleton Model (bone lengths, proportions, connectivity, mobility limits) is never modified at runtime. All runtime mutations happen in transient state objects.

### Environment must not contain pose state
The Environment defines surfaces (ground plane, prop positions, sizes). It does not track where the body is, what joints are touching, or what the solver computed.

### Pose Authoring must not compute geometry
Pose Authoring declares what the body should do (contacts, targets, posture). It does not solve for positions, compute transforms, or apply constraints.

### Constraint Solver must not know about rendering
The solver operates on world-space transforms. It has no concept of rendering.

### Finalizer must not move solver-settled contact end-effectors
The Finalizer respects the solver's contact settlements. If the solver has positioned a contact point, the Finalizer must not displace it.

### Animation must not know about IK or constraints
Animation drives time-based parameter interpolation. It does not solve for joint angles or enforce postural constraints.

### Serialization must not interpret data
Serialization moves data between storage and runtime. It does not transform, validate, or interpret the data it handles.

### Architectural invariants
The following invariants must hold at all times during execution. Any violation is an architectural defect.

1. The Skeleton Model is immutable at runtime.
2. The Environment is immutable during a single pose evaluation.
3. Pose Authoring is the sole writer of Intent State.
4. IK Solver is the sole writer of IK Result State.
5. Constraint Solver is the sole writer of Constraint Result State.
6. Finalizer is the sole writer of Local Transform State.
7. FK is the sole writer of World Transform State.
8. Projection is the sole writer of Screen Transform State.
9. Validator is the sole writer of Validation State.
10. Animation is the sole writer of interpolated parameters in Intent State.
11. No state category may be read while it is being written.
12. No state category may be written by more than one subsystem.
13. No subsystem may read a state category that it also writes.
14. Dependencies are acyclic.

### Failure isolation
- Failures are isolated. A failure in one subsystem must not propagate to other subsystems unless the failure produces invalid output that is consumed by a downstream subsystem.
- Failures propagate only through subsystem outputs. A downstream subsystem that receives invalid input must handle it gracefully and produce its own output.
- Degraded output is preferred over missing output.

### Stability guarantees
- Immutable definitions: The Skeleton Model, Environment, and Animation driver definitions are immutable at runtime.
- Deterministic ownership: Every runtime object has exactly one owner. Ownership is declared at the architectural level and must not be violated.
- Isolated mutation: Each subsystem may mutate only the state categories it owns. Mutation of another subsystem's state category is an architectural violation.
- Bounded subsystem responsibilities: Each subsystem has a narrow, well-defined scope. No subsystem may assume responsibilities outside its declared boundary.
- Acyclic dependencies: The dependency graph between subsystems is acyclic. No subsystem may depend on a downstream subsystem.

---

## 10. Error Handling Strategy

The architecture defines the following principles for handling errors at subsystem boundaries.

### Fail-fast philosophy
When a subsystem encounters invalid input or an internal inconsistency, it must signal the error immediately rather than attempting to continue with potentially corrupted state. The architecture favors early detection over silent continuation.

### Boundary-level isolation
Errors are contained at subsystem boundaries. A subsystem that encounters an error must not propagate the error to other subsystems except through its output. The error must be visible to the consumer of the subsystem's output.

### Graceful degradation
When a subsystem cannot produce its normal output, it must produce the best available alternative. The architecture prefers degraded but valid output over no output. The specific degradation strategy is an implementation concern of Pipeline Specification and subsystem specifications.

### Error propagation
Errors propagate only through subsystem outputs. A downstream subsystem that receives invalid input from an upstream subsystem must handle it according to the fail-fast philosophy. The architecture does not define error recovery strategies — these are implementation concerns.

### Architectural consequences
- Violation of an architectural contract is an architectural defect.
- The architecture does not define retry logic, circuit breakers, or fallback mechanisms. These are implementation concerns of Pipeline Specification and subsystem specifications.
- The architecture does not define error logging or diagnostic strategies. These are implementation concerns.

---

## 11. Configuration

The architecture is configured through architectural-level parameters that govern subsystem behavior.

### Configuration concerns
- **Skeleton topology** — which joints exist and how they are connected.
- **Bone lengths and proportions** — the physical dimensions of the skeleton.
- **Mobility limits** — the angular ranges of each joint.
- **Contact definitions** — which body points can be in contact with which surfaces.
- **Environment geometry** — the ground plane, props, and their properties.
- **Animation drivers** — the motion curves and timing parameters.

### Configuration principles
- Configuration is declared by the author and consumed by the architecture.
- Configuration is immutable at runtime. No subsystem may modify configuration parameters during execution.
- Configuration is separate from runtime state. Configuration defines the skeleton; runtime state defines the pose.
- Configuration is versioned. Changes to configuration must be tracked and must not break existing poses.

### Configuration boundaries
- Configuration belongs to the authoring layer, not to the computation layer.
- The architecture does not define configuration file formats or serialization protocols. These are implementation concerns.
- The architecture defines what configuration parameters exist and how they relate to subsystems, not how they are stored or loaded.

---

## 12. Observability

The architecture defines how the system's internal state is made visible to operators and developers.

### Observability concerns
- **State visibility** — the ability to observe the current state of each subsystem.
- **Contract compliance** — the ability to verify that subsystems are honoring their architectural contracts.
- **Boundary integrity** — the ability to identify when subsystems are violating architectural boundaries.
- **Data flow integrity** — the ability to verify that data is flowing correctly between subsystems.

### Observability principles
- Every subsystem must produce observability data that allows its state to be observed.
- Observability data must be produced at subsystem boundaries — where data enters and leaves each subsystem.
- Observability data must not affect the correctness of subsystem outputs. Observability must not compromise the architecture's guarantees.
- The architecture does not define observability data formats, transport mechanisms, or storage strategies. These are implementation concerns.

### Observability and contracts
Architectural contracts (§7) define what each subsystem must provide. Observability is the means by which contract compliance can be verified. The architecture defines the contracts; observability is the means of checking them.

---

## 13. Performance Model

The architecture defines the performance characteristics it is designed to achieve.

### Performance goals
- **Deterministic execution** — the architecture guarantees that the same inputs produce the same outputs. Performance must not vary based on hidden state or external factors.
- **Bounded resource consumption** — the architecture guarantees that each subsystem consumes bounded resources. The architecture does not define specific targets; these are implementation concerns.
- **Scalability** — the architecture must support additional subsystems and state categories without requiring changes to existing subsystems.
- **Predictable behavior** — the architecture guarantees that subsystems will behave predictably within their declared boundaries. Performance must not be affected by factors outside the subsystem's declared scope.

### Performance constraints
- The architecture does not define frame budgets, latency targets, or throughput requirements. These are implementation concerns of Pipeline Specification.
- The architecture does not define optimization strategies, caching mechanisms, or parallelization approaches. These are implementation concerns.
- The architecture defines what performance characteristics the system must have, not how they are achieved.

---

## 14. Threading Assumptions

The architecture makes the following assumptions about threading and concurrency.

### Determinism
The architecture requires deterministic behavior. Given the same inputs, the same outputs must be produced. This guarantee holds on a single thread. Parallel execution may introduce non-determinism if architectural contracts are not maintained.

### Ownership preservation
Ownership and dependency rules must be preserved regardless of threading model. No mutable state category is shared between threads without explicit ownership transfer. Immutable objects (Skeleton Model, Environment, Animation drivers) may be read concurrently by multiple threads.

### Parallel execution
Parallel execution is permitted when architectural contracts are maintained. Subsystems with no data dependency on each other may execute concurrently. The architecture does not define synchronization primitives, lock-free data structures, or message queues. These are implementation concerns.

---

## 15. Stable Interfaces

### Intent → Pipeline
Pose Authoring produces an intent package. The pipeline (IK, Constraint Solver, Finalizer) consumes it. The interface is the intent package itself — a self-contained declaration of contacts, targets, posture, spine curve, extremity overrides, gaze, and environment.

### Pipeline → State Categories
Each pipeline stage produces its output in the corresponding state category. The interface is the state category — a carrier of data in a specific form.

### Pipeline → Validation State
The Validator reads the finalized Local Transform State and World Transform State and the Skeleton Model. The interface is read-only access to transform state and skeleton parameters.

### Pipeline → Screen Transform State
Projection reads world-space transforms from FK and camera parameters from Intent State. The interface is world-space transform data and camera configuration.

### Screen Transform State → Rendering
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

## 16. Extension Points

The architecture defines the following extension points where new subsystems or capabilities may be added without modifying existing subsystems.

### Between Constraint Solver and Finalizer
A new subsystem may be inserted here to adjust world transforms before the Finalizer converts them to local transforms. The new subsystem must consume Constraint Result State and produce output that the Finalizer can consume. The Finalizer must not be modified to accommodate the new subsystem.

### Between Finalizer and FK
A new subsystem may be inserted here to apply additional local-transform adjustments before FK propagates them to world space. The new subsystem must consume Local Transform State and produce output that FK can consume. FK must not be modified to accommodate the new subsystem.

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
- New subsystems must not modify the state categories. They may only insert new state categories between existing ones.
- New subsystems must not violate any architectural invariant.
- New subsystems must follow the same ownership, mutability, and failure isolation rules as existing subsystems.

---

*This document describes architecture at the subsystem level. It does not specify classes, packages, files, or implementation details. All architectural decisions are derived from the Domain Analysis and the principles stated in the MonkEngine Design Principles.*
