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

The architecture follows the Domain Analysis: domain entities (Segment, Articulation, Attachment Point, Anatomical Mobility) are not architecture. They are the subject matter that subsystems operate on. Subsystems are defined by responsibility, not by the domain entities they touch.

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

**Purpose:** Declares what the body should do — the intent behind a pose. This includes contact declarations, limb targets (expressed in root space), posture type, spine configuration, extremity overrides, and gaze targets.

**Owned responsibility:** All declarative input. The author says what they want; the engine figures out how to achieve it.

**Inputs:** Exercise definition, frame progress.

**Outputs:** A complete intent package — contact declarations, limb targets (expressed in root space), posture intent, spine curve, extremity overrides, gaze target.

**Lifetime:** Per-frame. Recomputed each frame as the exercise progresses.

**Dependencies:** Reads the Skeleton Model (to know what joints and segments exist). Reads Animation Parameter State. Does not depend on any computation subsystem.

---

### 2.3 Transform Propagation (TP)

**Purpose:** Propagates local transforms up the skeleton hierarchy to compute world-space positions and orientations for every joint.

**Owned responsibility:** Stateless transform propagation. Given local positions and rotations, compute world-space transforms.

**Inputs:** Local transforms (localPosition, localRotation) for every joint; skeleton hierarchy (parent-child relationships).

**Outputs:** World-space transforms (worldPosition, worldRotation) for every joint.

**Lifetime:** Transient. Computed each frame as part of the pipeline.

**Dependencies:** Reads the Skeleton Model (hierarchy). Reads local transforms from the Local Transform State. Produces World Transform State.

---

### 2.4 Inverse Kinematics (IK)

**Purpose:** Solves for joint angles that place an end-effector (attachment point in IK effector role) at a target position relative to the root, respecting anatomical mobility limits. The root is assumed at origin for IK solving; the Pose Solver subsequently positions the root in world space.

**Owned responsibility:** Root-relative limb solving.

**Inputs:** Target root-relative position (from Intent State), bone lengths, mobility limits, contact declarations (from Intent State and Skeleton Model).

**Outputs:** Root-relative limb transforms; straight-intent-dropped flag.

**Lifetime:** Transient. Computed each frame for each limb target.

**Dependencies:** Reads the Skeleton Model (bone lengths, mobility limits). Reads the Intent State. Does not know about rendering or presentation.

---

### 2.5 Pose Solver

**Purpose:** Enforces postural constraints — root positioning from contacts, posture resolution, and contact conflict resolution.

**Owned responsibility:** Root transform authority and posture resolution. The solver is the sole authority for the root transform in world space.

**Inputs:** Intent State (contacts, posture intent, contact precedence), IK Result State, skeleton model (bone lengths, mobility limits).

**Outputs:** Final root transform; adjusted joint angles for posture; contact conflict resolution.

**Lifetime:** Transient. Computed each frame after IK solving.

**Dependencies:** Reads the Skeleton Model (bone lengths, mobility limits). Reads IK Result State. Reads Intent State (contacts, posture intent). Does not know about rendering or presentation.

---

### 2.6 Finalizer

**Purpose:** Applies geometric corrections — world-to-local conversion, extremity derivation, relative rotation resolution, chest-frame reconstruction, and flattening to the final local-transform store.

**Owned responsibility:** Exclusive world-to-local frame conversion. The Finalizer is the only subsystem that writes local transforms after the solver has settled. It does not move the root; it converts the solver-produced world root transform into Local Transform State.

**Inputs:** Pose Result State (root transform, posture adjustments, contact settlements); skeleton model (bone lengths, proportions); Intent State (authored chest rotation, extremity overrides).

**Outputs:** Final local transforms (localPosition, localRotation) for every joint; derived extremity orientations; chest-frame reconstruction.

**Lifetime:** Transient. Computed each frame after the Pose Solver.

**Dependencies:** Reads the Skeleton Model. Reads Pose Result State. Reads Intent State. Does not move solver-settled contact end-effectors. Does not know about rendering or presentation.

---

### 2.7 Validator

**Purpose:** Observes the final pose and checks it against biomechanical rules — bone lengths preserved, joints within mobility limits, landmarks in valid regions, bilateral symmetry holds.

**Owned responsibility:** Read-only correctness verification. The validator never mutates the pose.

**Inputs:** Local Transform State; World Transform State; Skeleton Model (mobility limits, bone lengths); Intent State (ROM declarations).

**Outputs:** Validation report (issues, severities, results).

**Lifetime:** Transient. Runs once per frame after all computation subsystems have published their results.

**Dependencies:** Reads the Skeleton Model. Reads Local Transform State, World Transform State, Intent State. Does not write to any pose state. Is independent of the main computation flow.

---

### 2.8 Projection

**Purpose:** Transforms world-space skeleton transforms into screen-space coordinates for display.

**Owned responsibility:** 3D-to-2D transformation and viewport classification.

**Inputs:** World Transform State; camera parameters (view position, projection settings) provided by the host execution environment.

**Outputs:** Screen-space skeleton positions; exercise snapshot.

**Lifetime:** Transient. Computed each frame after TP.

**Dependencies:** Reads TP output (world transforms). Reads camera parameters from the host execution environment. Does not know about IK, constraints, or the solver.

---

### 2.9 Rendering

**Purpose:** Draws the skeleton on screen — bones, joints, and visual styling.

**Owned responsibility:** Visual representation only. Rendering knows about bones, colors, thickness, and screen positions. It knows nothing about IK, constraints, or pose intent.

**Inputs:** Screen Transform State; Rendering Definition (Bone visual definitions, colors, thickness, display connectivity).

**Outputs:** Framebuffer output (drawn skeleton).

**Lifetime:** Transient. Executed each frame after Projection.

**Dependencies:** Reads Screen Transform State. Reads Rendering Definition. Does not know about IK, constraints, or the solver.

---

### 2.10 Serialization / Asset Definitions

**Purpose:** Loads, stores, and transmits skeleton definitions, pose configurations, and validation profiles.

**Owned responsibility:** Data persistence and transport. Serialization does not interpret or transform data — it moves it between storage and runtime.

**Inputs:** Serialized data (JSON, binary, or other format).

**Outputs:** Deserialized runtime objects; serialized output for transport or storage.

**Lifetime:** Persistent for definitions; transient for runtime state.

**Dependencies:** Reads the Skeleton Model structure. Does not modify any runtime state.

---

### 2.11 Animation

**Purpose:** Drives temporal changes in pose parameters over time.

**Owned responsibility:** Time-based parameter interpolation. Animation knows about timing and progress. It does not know about IK, constraints, or geometry.

**Inputs:** Motion driver definitions; frame progress.

**Outputs:** Interpolated parameter values consumed by Pose Authoring.

**Lifetime:** Transient per frame. Driven by the exercise timeline.

**Dependencies:** Reads motion driver definitions. Produces interpolated parameter values in Animation Parameter State. Does not know about the solver, TP, or rendering.

---

## 3. Dependency Rules

### Execution Initiation

The Frame Clock is the sole execution initiator. Each transient state category has exactly one producing subsystem. A subsystem may consume multiple published state categories. Subsystems never directly trigger one another.

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
Pose Solver ← IK Solver, Pose Authoring, Skeleton Model
    ↓
Finalizer ← Pose Solver, Skeleton Model, Pose Authoring
    ↓
TP ← Finalizer, Skeleton Model
    ↓
Projection ← TP, (camera parameters from host)
    ↓
Rendering ← Projection, Rendering Definition

Validator (independent observer, reads Skeleton Model, Local Transform State, World Transform State, Intent State)
    ↑ reads Skeleton Model, Local Transform State, World Transform State, Intent State
```

### Forbidden directions

- **Rendering must not know about IK.** Rendering only receives screen-space positions.
- **IK must not know about rendering.** IK operates in world space; it has no concept of screen coordinates.
- **Validator must not mutate poses.** Validation is read-only. It produces reports, not corrections.
- **Definitions must remain immutable.** The Skeleton Model is never modified at runtime.
- **Pose Authoring must not compute geometry.** Pose Authoring declares intent; it does not solve for positions.
- **Pose Solver must not know about rendering.** The solver operates on world-space transforms; it has no concept of screen space.
- **Finalizer must not move solver-settled contact end-effectors.** The Finalizer respects the solver's contact settlements.

---

## 4. Data Flow

```
Author
  ↓ (declares intent: contacts, targets, posture)
Pose Authoring
  ↓ (produces intent package)
IK Solver
  ↓ (produces limb solve results)
Pose Solver
  ↓ (resolves root position, posture, contact conflicts)
Finalizer
  ↓ (converts world→local, derives extremities, reconstructs chest)
TP
  ↓ (propagates local transforms to world space)
Projection
  ↓ (converts world→screen space)
Rendering
  ↓ (draws skeleton to framebuffer)
```

Intent State flows to IK Solver, Pose Solver, Finalizer, and Validator.

Validator reads the explicitly published states it depends on: Local Transform State, World Transform State, Intent State, and the Skeleton Model. It is not part of the main computation flow.

```
Validator (parallel observer)
  ↑ reads Local Transform State, World Transform State, Intent State, Skeleton Model
  ↓ (produces validation report)
```

### Major transformations

1. **Intent → Root-relative limb transforms** (IK Solver)
2. **Root-relative limb transforms + Root → World limb transforms + posture settlement** (Pose Solver)
3. **Pose Result → Local transforms** (Finalizer)
4. **Local transforms → World transforms** (TP)
5. **World transforms → Screen-space positions** (Projection)
6. **Screen-space positions → Visual output** (Rendering)
7. **Pose state → Validation report** (Validator)

---

## 5. Ownership

Every runtime object has exactly one subsystem that owns its creation, its mutation, and its destruction. No subsystem may create, mutate, or destroy a runtime object owned by another subsystem.

### Skeleton Model
- **Owns:** Bone lengths, proportions, joint connectivity, mobility limits, contact definitions.
- **Reads:** Nothing from other subsystems.
- **Produces:** The skeleton structure that all other subsystems reference.
- **Must never modify:** Its own data is immutable at runtime.

### Rendering Definition
- **Owns:** Bone visual definitions (thickness, color, display connectivity).
- **Reads:** Nothing from runtime subsystems.
- **Produces:** Visual definitions consumed by Rendering.
- **Must never modify:** Its own data is immutable at runtime.

### Pose Authoring
- **Writer:** Pose Authoring
- **Contents:** Contact declarations, limb targets (expressed in root space), posture intent, spine curve, extremity overrides, gaze target, contact precedence
- **Consumers:** IK Solver, Pose Solver, Validator, Finalizer
- **Mutability:** Mutable (recreated each frame by Pose Authoring)

### Animation Parameter State
- **Writer:** Animation
- **Contents:** Interpolated time-based parameter values
- **Consumers:** Pose Authoring
- **Mutability:** Mutable (produced by Animation)

### IK Result State
- **Writer:** IK Solver
- **Contents:** Solved limb transforms, straight-intent-dropped flag
- **Consumers:** Pose Solver
- **Mutability:** Mutable (produced by IK Solver)

### Pose Result State
- **Writer:** Pose Solver
- **Contents:** Root transform, posture adjustments, contact conflict resolution
- **Consumers:** Finalizer
- **Mutability:** Mutable (produced by Pose Solver)

### Local Transform State
- **Writer:** Finalizer
- **Contents:** Local transforms (localPosition, localRotation) for every joint; derived extremity orientations
- **Consumers:** TP, Validator
- **Mutability:** Mutable (produced by Finalizer)

### World Transform State
- **Writer:** TP
- **Contents:** World-space transforms (worldPosition, worldRotation) for every joint
- **Consumers:** Projection, Validator
- **Mutability:** Mutable (produced by TP)

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

---

## 6. State Categories

### State category rules
- Each state category is written by exactly one subsystem.
- Each state category is read by zero or more downstream subsystems.
- No state category may be read while it is being written.
- After a state category is published, it becomes read-only. There is exactly one Writer; after writing completes, state is published. Once published, all consumers have read-only access. No published state category can be modified again.
- State categories form a DAG: Animation Parameter State → Intent State → IK Result State → Pose Result State → Local Transform State → World Transform State → Screen Transform State. World Transform State, Local Transform State, Intent State, and Skeleton Model → Validation State.
- A state category may be published only after it satisfies its structural validity contract.
- Consumers never read partially written or unpublished state.

---

## 7. Architectural Contracts

An architectural contract is a formal agreement between subsystems about what each subsystem guarantees to provide and what it guarantees not to do. Contracts define the responsibilities of subsystems to each other.

### General provider obligations
- Every subsystem must produce bounded output. No subsystem may produce undefined values.
- Every subsystem must produce output that is consistent with its declared inputs.

### General consumer obligations
- Every subsystem must validate the structural integrity of upstream output before consuming it.
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
- **Provider guarantees:** Pose Authoring produces a complete intent package each frame. The intent package contains all contact declarations, limb targets, posture intent, spine curve, extremity overrides, gaze target, and contact precedence.
- **Consumer obligations:** Downstream subsystems must consume the intent package and must not modify it.

#### Contract: IK Solver → Pose Solver
- **Provider guarantees:** IK Solver produces root-relative limb solve results for every limb target. Results are bounded.
- **Consumer obligations:** Pose Solver must consume IK Result State and must not modify it.

#### Contract: Pose Solver → Finalizer
- **Provider guarantees:** Pose Solver produces a final root transform and posture-resolved joint angles. Contact settlements are final.
- **Consumer obligations:** Finalizer must consume Pose Solver results and must not move solver-settled contact end-effectors.

#### Contract: Finalizer → TP
- **Provider guarantees:** Finalizer produces final local transforms for every joint. World-to-local conversion is complete. Extremities are derived.
- **Consumer obligations:** TP must consume local transforms and propagate them. TP must not modify local transforms.

#### Contract: TP → Projection
- **Provider guarantees:** TP produces world-space transforms for every joint. Transforms are consistent with the skeleton hierarchy.
- **Consumer obligations:** Projection must consume world transforms, classify each position relative to the viewport, and produce screen-space positions. Positions outside the viewport must be flagged as out-of-viewport.

#### Contract: Projection → Rendering
- **Provider guarantees:** Projection produces screen-space positions for every joint. Positions are classified as within-viewport or out-of-viewport.
- **Consumer obligations:** Rendering must consume screen-space positions and produce visual output.

#### Contract: Validator → Application Layer
- **Provider guarantees:** Validator produces a validation report for every frame. The report contains all identified issues with severities.
- **Consumer obligations:** The application layer must consume the validation report.

---

## 8. Quality Attributes

The architecture is designed to achieve the following quality attributes.

### Deterministic
Given the same inputs (Skeleton Model, intent, animation parameters), the architecture guarantees the same outputs (world-space transforms, screen-space positions, validation report) on every execution. This guarantee holds on a single thread. Parallel execution may introduce non-determinism if architectural contracts are not maintained.

### Modular
Each subsystem has a narrow, well-defined scope. No subsystem may assume responsibilities outside its declared boundary. Subsystems are defined by responsibility, not by the domain entities they touch.

### Testable
Each subsystem can be tested in isolation. The architectural contracts define the inputs and outputs of each subsystem, enabling unit testing of individual subsystems without requiring the full pipeline.

### Extensible
New subsystems can be added without modifying existing subsystems. Extension points are defined at architectural boundaries (see §16). New subsystems must follow the same ownership, mutability, and failure isolation rules as existing subsystems.

### Predictable
The architecture guarantees that subsystems will behave predictably within their declared boundaries. A subsystem's behavior is determined by its inputs and its contract — not by hidden state or external factors.

### Isolated
Failures in one subsystem are contained within that subsystem. Failures propagate only through subsystem outputs. A downstream subsystem that receives invalid input must handle it according to the fail-fast philosophy.

### Reproducible
The architecture guarantees that the same pose authoring input and animation parameters will produce the same skeleton configuration on every execution. This is a consequence of determinism and isolated state.

### Maintainable
The architecture is organized by responsibility, not by domain entities. Each subsystem has a single, well-defined purpose. This makes it possible to modify one subsystem without affecting others.

### Immutable definitions
The Skeleton Model and Animation driver definitions are immutable at runtime. No subsystem may modify them. This ensures that the structural foundation of the system is stable and predictable.

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

### Animation must not know about IK or constraints
Animation drives time-based parameter interpolation. It does not solve for joint angles or enforce postural constraints.

### Serialization must not interpret data
Serialization moves data between storage and runtime. It does not transform, validate, or interpret the data it handles.

### Architectural invariants
The following invariants must hold at all times during execution. Any violation is an architectural defect.

1. The Skeleton Model is immutable at runtime.
2. Pose Authoring is the sole writer of Intent State.
3. Animation is the sole writer of Animation Parameter State.
4. IK Solver is the sole writer of IK Result State.
5. Pose Solver is the sole writer of Pose Result State.
6. Finalizer is the sole writer of Local Transform State.
7. TP is the sole writer of World Transform State.
8. Projection is the sole writer of Screen Transform State.
9. Validator is the sole writer of Validation State.
10. No state category may be read while it is being written.
11. No state category may be written by more than one subsystem.
12. No subsystem may read a mutable state category that it also writes.
13. Dependencies are acyclic.

### Failure isolation
- Failures are isolated. A failure in one subsystem must not propagate to other subsystems unless the failure produces invalid output that is consumed by a downstream subsystem.
- Failures propagate only through subsystem outputs. A downstream subsystem that receives invalid input must handle it according to the fail-fast philosophy.

### Stability guarantees
- Immutable definitions: The Skeleton Model and Animation driver definitions are immutable at runtime.
- Deterministic ownership: Every runtime object has exactly one owner. Ownership is declared at the architectural level and must not be violated.
- Isolated mutation: Each subsystem may mutate only the state categories it owns. Mutation of another subsystem's state category is an architectural violation.
- Bounded subsystem responsibilities: Each subsystem has a narrow, well-defined scope. No subsystem may assume responsibilities outside its declared boundary.
- Acyclic dependencies: The dependency graph between subsystems is acyclic. No subsystem may depend on a downstream subsystem.

---

## 10. Error Handling

The architecture defines two error classes at subsystem boundaries.

### Structural errors
When a subsystem encounters invalid input or an internal inconsistency, it must signal the error immediately (fail-fast). The invalid state must not be published. Errors propagate only through subsystem outputs. A downstream subsystem that receives invalid input must handle it according to the fail-fast philosophy. Violation of an architectural contract is an architectural defect.

### Numerical limitations
When a subsystem encounters an expected numerical limitation (e.g., joint angle at mobility limit), it may publish a structurally valid, degraded result. The subsystem must not publish invalid or corrupted state.

### Separation of concerns
These two error classes must not be mixed. Structural errors are handled by the architecture; numerical limitations are handled by the subsystem within its contract. Specific fallback and recovery algorithms are defined in Pipeline Specification, not in this document.

---

## 11. Configuration

The architecture is configured through architectural-level parameters that govern subsystem behavior.

### Configuration concerns
- **Skeleton topology** — which joints exist and how they are connected.
- **Bone lengths and proportions** — the physical dimensions of the skeleton.
- **Mobility limits** — the angular ranges of each joint.
- **Contact definitions** — which body points can be in contact with which surfaces.
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

Each subsystem must publish observable state at its boundary. Format, logging, and storage are not part of the architecture.

---

## 13. Performance Model

The architecture is designed for the following performance properties.

- **Deterministic** — the same inputs produce the same outputs.
- **Bounded resource usage** — each subsystem consumes bounded resources.
- **Scalable** — the architecture supports additional subsystems and state categories without requiring changes to existing subsystems.

---

## 14. Threading Assumptions

The architecture makes the following assumptions about threading and concurrency.

### Determinism
The architecture requires deterministic behavior. Given the same inputs, the same outputs must be produced. This guarantee holds on a single thread. Parallel execution may introduce non-determinism if architectural contracts are not maintained.

### Ownership preservation
Ownership and dependency rules must be preserved regardless of threading model. No mutable state category is shared between threads without explicit ownership transfer. Immutable objects (Skeleton Model, Animation drivers) may be read concurrently by multiple threads.

### Parallel execution
Parallel execution is permitted when architectural contracts are maintained. Subsystems with no data dependency on each other may execute concurrently. The architecture does not define synchronization primitives, lock-free data structures, or message queues. These are implementation concerns.

### Synchronization ownership
Synchronization is owned by the Frame Clock / host execution environment, not by subsystems. Subsystems should not own synchronization mechanisms.

---

## 15. Stable Interfaces

### Intent → Pipeline
Pose Authoring produces an intent package. The pipeline (IK, Pose Solver, Finalizer) consumes it. The interface is the intent package itself — a self-contained declaration of contacts, targets, posture, spine curve, extremity overrides, gaze, and contact precedence.

### Pipeline → State Categories
Each pipeline stage produces its output in the corresponding state category. The interface is the state category — a carrier of data in a specific form.

### Pipeline → Validation State
The Validator reads the finalized Local Transform State, World Transform State, Intent State, and the Skeleton Model. The interface is read-only access to transform state and skeleton parameters.

### Pipeline → Screen Transform State
Projection reads world-space transforms from TP and camera parameters directly from the host execution environment. The interface is world-space transform data and camera configuration.

### Screen Transform State → Rendering
Projection produces screen-space positions. Rendering consumes them. The interface is 2D screen coordinates. Display connectivity is provided by Rendering Definition.

### Skeleton Model → TP
TP reads the Skeleton Model (hierarchy) and local transforms from the Finalizer. The interface is the skeleton hierarchy and local transform data.

### Skeleton Model → Solver
The Pose Solver and IK Solver read the Skeleton Model (bone lengths, mobility limits). The interface is skeleton parameters.

### Animation → Animation Parameter State
Animation produces interpolated parameter values in Animation Parameter State. The interface is a set of time-varying parameter values.

### Animation Parameter State → Pose Authoring
Pose Authoring consumes interpolated parameter values from Animation Parameter State. The interface is a set of time-varying parameter values.

---

## 16. Extension Points

New subsystems can only be inserted between published state categories. Each insertion point requires the new subsystem to consume the upstream state category and produce output for the downstream state category, without modifying existing subsystems.

---

*This document describes architecture at the subsystem level. It does not specify classes, packages, files, or implementation details. All architectural decisions are derived from the Domain Analysis and the principles stated in the MonkEngine Design Principles.*
