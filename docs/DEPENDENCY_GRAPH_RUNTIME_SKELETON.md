# Dependency Graph: Runtime Skeleton Model

> No code changes. No renaming. No implementation proposals.
> Documents the architecture precisely to enable a future semantic refactor with minimal risk.

---

## Part 1 — Complete Ownership Graph

### SkeletonNode

| Role | Entity |
|---|---|
| Owner | `SkeletonFactory` (creates nodes), `SkeletonPose` (owns the tree via `roots`) |
| Creator | `SkeletonFactory.createStandardSkeleton()` / `createPushUpSkeleton()` |
| Mutator | `PoseBuilder.build()` (authors `localPosition`/`localRotation`), `ConstraintSolver.solve()` (repositions pelvis, re-bakes contact limbs), `SkeletonPoseFinalizer.finalize()` (reconstructs chest frame, resolves head target, applies intent carriers) |
| Reader | `SkeletonPoseFinalizer.finalize()` (FK traversal), `ConstraintSolver.solve()` (collects nodes into `nodeMap`), `IkStage.apply()` (collects nodes into `nodeMap`), `SkeletonProjector` (reads joint positions via `SkeletonPose.getJoint()`) |
| Final consumer | `SkeletonPose` (via `flatten()` which writes world transforms into the flat arrays) |
| Destroyed by | GC (nodes are not explicitly destroyed; the tree is transient per-frame) |

### SkeletonPose

| Role | Entity |
|---|---|
| Owner | `PoseBuilder.build()` creates it; `SkeletonPipeline` owns it during `runStages()`; renderer/validator receives it |
| Creator | `PoseBuilder.build()` (via `SkeletonPose.fromHierarchy()` or direct construction) |
| Mutator | Every pipeline stage mutates it: `IkStage.apply()` (writes `localPosition` on nodes, which `flatten()` then writes to `joints`/`rotations`), `ConstraintSolver.solve()` (mutates node transforms, writes `rootTranslationDelta`, `rootRotationDelta`, `boneLengthsVerified`, `hipRomStamps`), `SkeletonPoseFinalizer.finalize()` (writes `joints`, `rotations`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`), `SkeletonPipeline.produceFrame()` (mutates `environment` and `supportedPoints` on input pose) |
| Reader | `SkeletonRenderer`/`SkeletonSnapshotRenderer` (reads `joints` for projection), `ExerciseValidator` (reads all fields for validation), `SkeletonPoseFinalizer` (reads intent carriers) |
| Final consumer | Renderer (screen-space projection), Validator (validation report) |
| Destroyed by | GC after frame; `previous`/`prePrevious` history keeps 2 frames alive |

### Joint enum

| Role | Entity |
|---|---|
| Owner | JVM class loading (enum singleton) |
| Creator | JVM |
| Mutator | None (enum is immutable) |
| Reader | Every subsystem — used as array index into `SkeletonPose.joints` and `SkeletonPose.rotations`, as node identity in `SkeletonNode.joint`, as lookup key in `ConstraintSolver.nodeMap`, as bone endpoint in `SkeletonEngine.bones`, as validation target in `ExerciseValidator` |
| Final consumer | Every pipeline stage and renderer |
| Destroyed by | JVM class unloading (never explicitly destroyed) |

### Bone

| Role | Entity |
|---|---|
| Owner | `SkeletonEngine` (static list, per-definition) |
| Creator | `SkeletonEngine` constructor |
| Mutator | None (immutable data class) |
| Reader | `SkeletonProjector` (reads `parentJoint`/`childJoint` to draw bones), `SkeletonRenderer`/`SkeletonSnapshotRenderer` (reads for rendering) |
| Final consumer | Renderer (screen-space bone drawing) |
| Destroyed by | GC when `SkeletonEngine` is disposed |

### ContactSpec

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.contacts` (mutable list) |
| Creator | `BasePose.bakeIkLimb()` and package-level `bakeIkLimb()` function |
| Mutator | Added to `pose.contacts` during build; never mutated after creation |
| Reader | `ConstraintSolver.solve()` (primary consumer — repositions root, re-bakes limbs), `SkeletonPoseFinalizer.finalize()` (reads for contact guard in chest reconstruction), `ExerciseValidator` (validates contact preservation) |
| Final consumer | `ConstraintSolver.solve()` |
| Destroyed by | GC when `SkeletonPose` is discarded |

### RelativeArticulation

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.jointIntents` (mutable list) |
| Creator | `BasePose.declareJointIntent()`, `BasePose.buildChestTwist()`, `BasePose.buildHipFlexion()`, `BasePose.buildHipRotation()`, `BasePose.buildClavicularRotation()`, `declarePelvisTilt()` |
| Mutator | Added to `pose.jointIntents` during build; never mutated after creation |
| Reader | `SkeletonPoseFinalizer.applyIntentCarriers()` (consumes for B2 intent re-application) |
| Final consumer | `SkeletonPoseFinalizer.finalize()` |
| Destroyed by | GC when `SkeletonPose` is discarded |

### SpineIntent

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.spineIntent` |
| Creator | `BasePose.buildSpineCurve()`, `SkeletonPose.IntentBuilder.spine()` |
| Mutator | Set during build; never mutated after creation |
| Reader | `SkeletonPoseFinalizer.applyIntentCarriers()` (consumes for B2 spine re-derivation) |
| Final consumer | `SkeletonPoseFinalizer.finalize()` |
| Destroyed by | GC when `SkeletonPose` is discarded |

### ExtremityArticulation

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.extremityArticulations` (mutable map) |
| Creator | `BasePose.buildWristArticulation()`, `BasePose.buildAnkleArticulation()`, `SkeletonPose.IntentBuilder.extremity()` |
| Mutator | Added to map during build; never mutated after creation |
| Reader | `SkeletonPoseFinalizer.articulationFor()` (consumes for W1 extremity geometry derivation) |
| Final consumer | `SkeletonPoseFinalizer.finalize()` |
| Destroyed by | GC when `SkeletonPose` is discarded |

### LimbTarget

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.limbTargets` (mutable list) |
| Creator | `BasePose.bakeIkLimb()` and package-level `bakeIkLimb()` function |
| Mutator | Added to `pose.limbTargets` during build; never mutated after creation |
| Reader | `IkStage.apply()` (consumes when `IK_STAGE_ACTIVE` is true), `SkeletonPoseFinalizer.finalize()` (reads for context) |
| Final consumer | `IkStage.apply()` (when active) |
| Destroyed by | GC when `SkeletonPose` is discarded |

### SupportPoint

| Role | Entity |
|---|---|
| Owner | `SkeletonPose.supportedPoints` (mutable set) |
| Creator | `SkeletonPipeline.produceFrame()` (stamps from `pose.metadata.support.contacts`), `SkeletonPipeline.produceFrame(builtPose)` (stamps from `supportedPoints` parameter) |
| Mutator | `supportedPoints.clear()` + `addAll()` during pipeline execution |
| Reader | `SkeletonPoseFinalizer.adjustHandOrientation()` (checks `pose.isSupported(handPoint)`), `SkeletonPoseFinalizer.adjustFootOrientation()` (checks `pose.isSupported(footPoint)`), `SkeletonPose.isSupported()` |
| Final consumer | `SkeletonPoseFinalizer.finalize()` (uses for extremity flattening) |
| Destroyed by | GC when `SkeletonPose` is discarded |

### SkeletonDefinition

| Role | Entity |
|---|---|
| Owner | `SkeletonPipeline` (holds reference), `SkeletonEngine` (holds reference), `SkeletonPoseFinalizer` (holds reference), `ConstraintSolver` (receives as parameter), `ExerciseValidator` (receives as parameter) |
| Creator | `HumanSkeletonDefinition()` or custom implementation |
| Mutator | None (immutable data class) |
| Reader | Every pipeline stage — `ConstraintSolver.solve()` (bone lengths, IK constraints), `SkeletonPoseFinalizer.finalize()` (neck length, head length, hand/foot definitions), `ExerciseValidator.validate()` (bone lengths, IK constraints, angular limits, hip ROM limits) |
| Final consumer | Every pipeline stage |
| Destroyed by | GC when engine is disposed |

### SkeletonFactory

| Role | Entity |
|---|---|
| Owner | JVM (object + static methods) |
| Creator | JVM |
| Mutator | None (stateless factory) |
| Reader | `SkeletonPose.fromJointPositions()` (calls `createStandardSkeleton()` for legacy bridge) |
| Final consumer | `SkeletonPose.fromJointPositions()` |
| Destroyed by | Never (object singleton) |

### ConstraintSolver

| Role | Entity |
|---|---|
| Owner | JVM (singleton `object`) |
| Creator | JVM |
| Mutator | `solve()` mutates `SkeletonNode` tree (pelvis, contact limb nodes), `SkeletonPose` state stamps, `lastSolvedRoot` WeakHashMap |
| Reader | `SkeletonPipeline.runStages()` (calls `solve()`), `SkeletonPose` (reads `contacts`, `postureIntent`, `contactPrecedence`, `roots`) |
| Final consumer | `SkeletonPipeline.runStages()` |
| Destroyed by | Never (singleton) |

### SkeletonPoseFinalizer

| Role | Entity |
|---|---|
| Owner | `SkeletonPipeline` (created in constructor, stored as `finalizer` field) |
| Creator | `SkeletonPipeline` constructor |
| Mutator | `finalize()` mutates `SkeletonNode` tree (chest.localRotation, neck.localPosition, head.localPosition), creates `SkeletonPose.outputPose` (joints, rotations, stamps) |
| Reader | `SkeletonPipeline.runStages()` (calls `finalize()`), `SkeletonPose` (reads all intent carriers, environment, supportedPoints, contacts) |
| Final consumer | `SkeletonPipeline.runStages()` (returns finalized pose) |
| Destroyed by | GC when `SkeletonPipeline` is disposed |

### ExerciseValidator

| Role | Entity |
|---|---|
| Owner | Caller (passed to `SkeletonPipeline` constructor) |
| Creator | Caller |
| Mutator | None (read-only on pose; allocates `ValidationReport`, `ValidationResult`, `ValidationIssue`) |
| Reader | `SkeletonPipeline.produceFrameValidated()` (calls `validate()`), `SkeletonPose` (all fields), `SkeletonDefinition`, `EnvironmentDefinition`, `Camera` |
| Final consumer | `SkeletonPipeline.produceFrameValidated()` (returns `ValidatedFrame`) |
| Destroyed by | GC when pipeline is disposed |

### SkeletonRenderer

| Role | Entity |
|---|---|
| Owner | Compose runtime (created via `remember`) |
| Creator | Compose runtime |
| Mutator | None (read-only on pose; creates `Pipeline` and `Projector` instances) |
| Reader | `SkeletonPose` (finalized), `SkeletonEngine`, `Camera`, `EnvironmentDefinition` |
| Final consumer | Android Canvas / Compose drawing commands |
| Destroyed by | GC when composable is disposed |

### SkeletonSnapshotRenderer

| Role | Entity |
|---|---|
| Owner | Caller (created with `SkeletonEngine`) |
| Creator | Caller |
| Mutator | None (read-only on pose; creates `Pipeline`, `Projector`, `Compensator` instances) |
| Reader | `SkeletonPose` (finalized), `SkeletonEngine`, `Camera`, `EnvironmentDefinition` |
| Final consumer | `Bitmap` |
| Destroyed by | GC when renderer is disposed |

---

## Part 2 — Semantic Leaks

### Leak 1: Joint == articulation

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonNode.kt` | `SkeletonNode` constructor | 31 | `joint: Joint` implies this node is an articulation | Every node carries a `Joint` enum value, but `PELVIS`, `CHEST`, `LUMBAR` are segments, not articulations | The node cannot distinguish between a joint with DOF and a rigid body segment |
| `SkeletonPose.kt` | `getJoint(id: Joint)` | 308 | All Joint entries are addressable as joint positions | `HEAD_POS`, `HEEL_F`, `PALM_A` etc. have positions in the flat array but no DOF | The flat array conflates positions of articulations, segments, and markers |
| `SkeletonPose.kt` | `setJoint(id: Joint, v: Vector3)` | 310 | Same as above | Same as above | Same as above |
| `SkeletonPose.kt` | `getJointRotation(id: Joint)` | 314 | All Joint entries have rotations | `HEAD_POS`, `HEEL_F`, `PALM_A` etc. have rotation entries in the flat array but no authored rotation | The rotation array contains identity rotations for non-articulation entries |
| `ConstraintSolver.kt` | `chainForEnd(end: Joint)` | 162 | Every Joint can be a contact end-effector | `HEAD_POS`, `TOE_F`, `HEEL_F` are contact endpoints but are not articulations | The solver treats markers as IK chain endpoints, which is correct for contacts but conflates the concepts |
| `ExerciseValidator.kt` | `validateBoneLengths()` | 211 | Every Joint pair in the bone-length list is an articulation pair | `PELVIS→CHEST` is a segment length check, `ANKLE_F→HEEL_F` is a marker-to-marker check | The validator applies the same bone-length rule to segments and markers |

### Leak 2: Joint == body segment

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonNode.kt` | `SkeletonNode` constructor | 31 | `joint: Joint` implies this node represents a body segment | `PELVIS`, `LUMBAR`, `CHEST` are segments, but `SHOULDER_A`, `ELBOW_A` etc. are articulations | The node cannot distinguish between a segment (rigid body) and a joint (DOF) |
| `SkeletonFactory.kt` | `createStandardSkeleton()` | 60-102 | The tree hierarchy implies segments are parents of articulations | `PELVIS→LUMBAR→CHEST` is a segment chain, but `CHEST→SHOULDER_A` is a segment-to-articulation link | The tree structure conflates segment hierarchy with joint hierarchy |
| `SkeletonPose.kt` | `fromHierarchy()` | 486 | All nodes in the hierarchy are treated equally for FK | Segments (PELVIS, LUMBAR, CHEST) and articulations (SHOULDER_A, HIP_F) are both FK nodes | FK treats segments and articulations identically — both get `updateWorldTransforms()` called |

### Leak 3: Joint == attachment

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonFactory.kt` | `createStandardSkeleton()` | 78-81 | `HAND_A` is a child of `ELBOW_A` in the tree | `HAND_A` is a terminal IK target, not an articulation — it has no DOF | The tree structure implies `HAND_A` is a joint when it is actually an endpoint marker |
| `SkeletonFactory.kt` | `createStandardSkeleton()` | 95-96 | `HEEL_F` and `TOE_F` are children of `ANKLE_F` | `HEEL_F` and `TOE_F` are contact markers, not articulations | The tree structure implies markers are joints |
| `SkeletonPoseFinalizer.kt` | `adjustHandOrientation()` | 532-642 | `WRIST_A` is a joint with rotation | `WRIST_A` has no `SkeletonNode` in the tree — it is a phantom enum entry used as a position marker | The Finalizer treats a phantom enum entry as if it were a real joint node |
| `SkeletonPoseFinalizer.kt` | `adjustFootOrientation()` | 644-762 | `ANKLE_F` is both an articulation and an attachment host | `ANKLE_F` carries authored rotation (dorsiflexion) AND hosts `HEEL_F`/`TOE_F` children | The same node serves dual roles — articulation and attachment host |

### Leak 4: Joint == helper

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonFactory.kt` | `SkeletonNodes` class | 52-54 | `wristA`, `wristB`, `wristP` are helper aliases | These are aliases for `handA`/`handP`, not separate nodes | The aliases suggest wrist is a separate concept when it is not a tree node |
| `SkeletonPoseFinalizer.kt` | `articulationFor()` | 501-522 | `Joint.HAND_A` is the wrist articulation node | The method looks up `Joint.HAND_A` in the tree, which is the hand node, not a wrist node | The wrist articulation is stored on the hand node, not a dedicated wrist node |

### Leak 5: Bone == rendering primitive only

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonEngine.kt` | `SkeletonEngine` constructor | 8-38 | `Bone` defines the rendering bone hierarchy | The bone list hardcodes parent-child joint pairs that duplicate the `SkeletonNode` tree topology | Changing the skeleton topology requires updating both the node tree and the bone list independently |
| `SkeletonProjector.kt` | `project()` | 44-56 | `engine.bones` defines which joints to connect with bones | The bone list is a rendering concern masquerading as a structural definition | The rendering topology is decoupled from the simulation topology but duplicates it |

### Leak 6: SkeletonPose == runtime state

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonPose.kt` | class definition | 143 | `SkeletonPose` is a single state object | It carries both intent (§1.1) and derived state (§1.2) in one object | The intent/state boundary is convention-based, not structural |
| `SkeletonPipeline.kt` | `produceFrame(builtPose)` | 78-80 | Mutates input pose's `environment` and `supportedPoints` | The API signature does not reveal this side effect | Callers who reuse the pose object will see unexpected mutations |

### Leak 7: SkeletonNode == coordinate frame

| File | Function | Line | Assumption | Reason | Consequence |
|---|---|---|---|---|---|
| `SkeletonNode.kt` | `updateWorldTransforms()` | 61-79 | The node is purely a transform container | The node also carries biomechanical identity (`joint: Joint`) and segment data (`localPosition` as bone offset) | The FK computation cannot distinguish between transform nodes and biomechanical nodes |
| `SkeletonNode.kt` | `flatten()` | 84-90 | The node writes world transforms to the flat pose | The node also writes the `Joint` identity, which conflates frame index with biomechanical role | The flat array is indexed by biomechanical identity, not by frame index |

---

## Part 3 — Hidden Coupling

### SkeletonNode → SkeletonPose

| Coupling | Description |
|---|---|
| `SkeletonNode.flatten()` writes to `SkeletonPose` | `flatten()` writes `worldPosition` and `worldRotation` into `SkeletonPose.joints` and `SkeletonPose.rotations` arrays by `Joint.ordinal` index. This is the primary data flow from the tree to the flat snapshot. |
| `SkeletonNode.updateWorldTransforms()` mutates node state | The FK computation mutates `worldPosition` and `worldRotation` on every node. These are then read by `flatten()`. |
| `SkeletonPose.roots` holds the tree | The `roots` field on `SkeletonPose` keeps the tree alive for the duration of the pipeline. The tree is not copied — it is shared. |
| **Risk** | Changing the tree structure (e.g., adding/removing nodes) changes the flat array indices. The `Joint.ordinal` indexing means every array access is coupled to the enum definition. |

### ConstraintSolver → SkeletonPoseFinalizer

| Coupling | Description |
|---|---|
| `ConstraintSolver.solve()` calls `SkeletonPose.fromHierarchy()` | At the end of `solve()`, the solver calls `SkeletonPose.fromHierarchy(roots, pose)` which runs FK and flattens the tree into the pose. This means the solver's output is the Finalizer's input. |
| `SkeletonPoseFinalizer.finalize()` assumes solver has run | The Finalizer's `finalize()` method assumes the node tree already has solver-settled transforms. It reads `chest.localRotation` to decide whether to reconstruct the chest frame, and reads `neck.worldPosition` for head-target resolution. |
| **Risk** | If the solver is skipped (e.g., for contact-less poses), the Finalizer still runs FK on the authored node transforms. The Finalizer must handle both solver-settled and authored transforms correctly. |

### Finalizer → Validator

| Coupling | Description |
|---|---|
| `SkeletonPoseFinalizer.applyValidationStamps()` writes state for the validator | The Finalizer produces `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend` on `SkeletonPose`. The validator reads these stamps instead of re-deriving geometry. |
| `ExerciseValidator` reads stamps from `SkeletonPose` | The validator reads `pose.hipRomStamps`, `pose.bilateralSymmetryDelta`, `pose.bilateralOppositeBend` directly. |
| **Risk** | If the Finalizer's stamp computation changes, the validator's behavior changes implicitly. The validator is coupled to the Finalizer's internal implementation. |

### Joint enum → arrays

| Coupling | Description |
|---|---|
| `Joint.ordinal` indexes into `SkeletonPose.joints` and `SkeletonPose.rotations` | Every access to the flat arrays uses `Joint.ordinal` as the index. Adding or reordering enum entries changes array indices. |
| `SkeletonNode.joint` stores the `Joint` enum value | Each node stores its `Joint` identity, which is used by `flatten()` to determine the array index. |
| `ConstraintSolver.nodeMap` is indexed by `Joint.ordinal` | The solver's `nodeMap` array is sized to `Joint.entries.size` and indexed by `node.joint.index`. |
| **Risk** | The `Joint` enum is the single point of coupling between the tree, the flat arrays, and the solver's node map. Any change to the enum affects all three. |

### Joint enum → SkeletonFactory topology

| Coupling | Description |
|---|---|
| `SkeletonFactory` creates `SkeletonNode(Joint.X)` for each enum entry | The factory hardcodes which `Joint` values are nodes in the tree. |
| `SkeletonNodes` data class exposes all 33 nodes by name | The `SkeletonNodes` container has a field for every `Joint` value that has a node in the tree. |
| `SkeletonPose.fromJointPositions()` uses `SkeletonFactory.createStandardSkeleton()` | The legacy bridge uses the factory to build the standard tree, then derives node positions from world positions. |
| **Risk** | The factory topology is hardcoded in two places: the `createStandardSkeleton()` and `createPushUpSkeleton()` methods, and the `SkeletonNodes` data class. Adding a new joint requires updating both. |

### Joint enum → rendering

| Coupling | Description |
|---|---|
| `SkeletonEngine.bones` uses `Joint` enum values as bone endpoints | The bone list hardcodes `Joint.HIP_B → Joint.KNEE_B`, etc. |
| `SkeletonProjector.project()` iterates `Joint.entries` to project all joints | The projector uses `Joint.entries` to iterate all joints for projection. |
| `SkeletonRenderer`/`SkeletonSnapshotRenderer` use `Joint` enum for indicator highlighting | The renderers check `Joint.HEAD_POS` for head indicator rendering. |
| **Risk** | The rendering system is coupled to the `Joint` enum ordering and values. Adding a new joint affects rendering (extra bone, extra projected point). |

### SkeletonNode → ConstraintSolver

| Coupling | Description |
|---|---|
| Solver reads `SkeletonNode.worldPosition` and `SkeletonNode.worldRotation` | The solver uses world transforms for IK computation and root repositioning. |
| Solver writes `SkeletonNode.localPosition` and `SkeletonNode.localRotation` | The solver writes IK results and pelvis adjustments directly into node local transforms. |
| Solver uses `SkeletonNode.parent` for FK chain traversal | The CCD posture pass walks up the parent chain from end-effector to root. |
| **Risk** | The solver is tightly coupled to the `SkeletonNode` tree structure. Changing the tree topology (e.g., adding intermediate nodes) would require solver changes. |

### SkeletonNode → SkeletonPoseFinalizer

| Coupling | Description |
|---|---|
| Finalizer uses `findJointNode()` to locate specific nodes by `Joint` enum | The Finalizer searches the tree for `Joint.PELVIS`, `Joint.CHEST`, `Joint.SHOULDER_A`, etc. |
| Finalizer reads/writes `SkeletonNode.localRotation` for chest reconstruction | The chest-frame reconstruction reads `chest.localRotation` and writes it back. |
| Finalizer reads/writes `SkeletonNode.localPosition` for head-target resolution | The head-target resolver writes `neck.localPosition` and `head.localPosition`. |
| Finalizer calls `SkeletonNode.updateWorldTransforms()` and `SkeletonNode.flatten()` | The Finalizer runs FK on the chest subtree after reconstruction. |
| **Risk** | The Finalizer is tightly coupled to specific `Joint` enum values. Adding a new joint type would require Finalizer changes. |

### SkeletonPose → SkeletonPipeline

| Coupling | Description |
|---|---|
| Pipeline mutates `pose.environment` and `pose.supportedPoints` in `produceFrame(builtPose)` | The pipeline writes to the input pose's environment and support fields. |
| Pipeline stores `previous` and `prePrevious` `SkeletonPose` copies | The pipeline keeps a 2-frame history for dynamics validation. |
| Pipeline creates `SkeletonPoseFinalizer` in constructor | The finalizer is long-lived and owned by the pipeline. |
| **Risk** | The pipeline's mutation of input pose fields is a hidden side effect. The `produceFrame(builtPose)` overload mutates the caller's pose object. |

### Joint enum → SkeletonNodes aliases

| Coupling | Description |
|---|---|
| `SkeletonNodes.wristA` aliases `handA` (Joint.HAND_A) | The alias suggests wrist is a separate concept when it is the same node. |
| `SkeletonNodes.wristP` aliases `handP` (Joint.HAND_P) | Same issue for the right side. |
| `SkeletonNodes.wristB` aliases `handP` (Joint.HAND_P) | The `B` suffix alias is confusing — it maps to the right hand, not a "B" wrist. |
| `SkeletonNodes.hipA` aliases `hipF`, `kneeA` aliases `kneeF`, etc. | The `A`/`B` aliases create confusion about naming conventions. |
| **Risk** | The aliases create a parallel naming system that obscures the actual `Joint` enum values. Code using `nodes.wristA` is reading `handA`'s data, which is confusing. |

---

## Part 4 — Runtime Data Flow

### Flow Diagram

```
PoseBuilder.build(context)
│
├── Authors SkeletonNode.localPosition / localRotation
│   (pose helpers: buildTorso, buildPelvis, buildShoulders, bakeIkLimb, etc.)
│
├── Populates SkeletonPose.intent carriers
│   (contacts, limbTargets, jointIntents, spineIntent, postureIntent,
│    extremityOverrides, extremityArticulations, headTarget, headings,
│    environment, supportedPoints)
│
▼
SkeletonPose (after build)
│   joints: Array<Vector3> (flat, indexed by Joint.ordinal)
│   rotations: Array<JointRotation> (flat, world-space)
│   roots: List<SkeletonNode> (the FK tree)
│   intent carriers (§1.1)
│   state stamps (§1.2, initially default)
│
▼
IkStage.apply(pose, definition)
│   Reads: pose.limbTargets, definition, pose.roots
│   Writes: SkeletonNode.localPosition (middle/end nodes)
│   Writes: pose.boneLengthsVerified, pose.maxIkClampAmount
│   Note: Gated by IK_STAGE_ACTIVE (default false)
│
▼
ConstraintSolver.solve(pose, definition)
│   Reads: pose.contacts, pose.postureIntent, pose.contactPrecedence, pose.roots, definition
│   Writes: pelvis.localPosition, pelvis.localRotation
│   Writes: contact limb SkeletonNode.localPosition
│   Writes: pose.rootTranslationDelta, pose.rootRotationDelta
│   Writes: pose.boneLengthsVerified
│   Calls: SkeletonPose.fromHierarchy(roots, pose) → FK + flatten
│
▼
SkeletonPose (after solver)
│   Node tree has solver-settled transforms
│   Flat arrays have solver-computed positions/rotations
│   State stamps updated
│
▼
SkeletonPoseFinalizer.finalize(pose)
│   Step 1: FK traversal + flatten (if !isTransformsUpdated)
│     Reads: pose.roots, SkeletonNode.localPosition/rotation
│     Writes: outputPose.joints, outputPose.rotations (world-space)
│   Step 2: applyIntentCarriers() (B2)
│     Reads: pose.jointIntents, pose.spineIntent
│     Writes: SkeletonNode.localRotation (re-applies authored rotations)
│     Calls: root.updateWorldTransforms() + root.flatten(outputPose)
│   Step 3: reconstructChestFrame() (F1)
│     Reads: pose.roots (pelvis, chest, shoulderA, shoulderP)
│     Writes: chest.localRotation (if unauthored)
│     Calls: chest.updateWorldTransforms() + chest.flatten(outputPose)
│   Step 4: resolveHeadTarget() (Phase 7)
│     Reads: pose.headTarget, pose.roots (neck, head)
│     Writes: neck.localPosition, head.localPosition
│     Calls: neck.updateWorldTransforms() + neck.flatten(outputPose)
│   Step 5: adjustFootOrientation() (W1)
│     Reads: pose.ankle nodes, pose.supportedPoints, pose.environment
│     Writes: outputPose.joints (heel/toe positions)
│   Step 6: adjustHandOrientation() (W1)
│     Reads: pose.elbow nodes, pose.wrist nodes, pose.supportedPoints
│     Writes: outputPose.joints (palm/knuckle/fingertip positions)
│   Step 7: applyValidationStamps() (B5)
│     Reads: outputPose (all joint positions/rotations)
│     Writes: pose.hipRomStamps, pose.bilateralSymmetryDelta, pose.bilateralOppositeBend
│   Returns: outputPose
│
▼
Finalized SkeletonPose
│   joints: Array<Vector3> (final world positions)
│   rotations: Array<JointRotation> (final world rotations)
│   state stamps (hipRomStamps, bilateralSymmetryDelta, etc.)
│
├──→ SkeletonRenderer / SkeletonSnapshotRenderer
│   │   Reads: finalized SkeletonPose, SkeletonEngine, Camera
│   │   Uses: SkeletonProjector.project() → ProjectedSkeleton
│   │   Produces: Bitmap / Compose drawing commands
│   │
│   ├── SkeletonProjector.project()
│   │   Reads: pose.joints (all), SkeletonEngine.bones
│   │   Writes: ProjectedSkeleton.joints, .bones, .faces, .gridLines, .shadowPoints
│   │
│   └── Rendering pass
│       Reads: ProjectedSkeleton (bones, joints, faces)
│       Produces: Screen-space drawing commands
│
└──→ ExerciseValidator.validate() (optional, in produceFrameValidated)
    │   Reads: finalized SkeletonPose, SkeletonDefinition, EnvironmentDefinition, Camera,
    │          previousPose, prePreviousPose
    │   Produces: ValidationReport (17 rules)
    │
    ├── Rule: FINITE_COORDINATES — reads pose.joints
    ├── Rule: BONE_LENGTH — reads pose.joints, definition
    ├── Rule: HEAD_VIEWPORT — reads pose.joints[HEAD_POS], camera
    ├── Rule: FOOT_GROUND_PENETRATION — reads pose.joints, environment
    ├── Rule: HAND_SLIDING — reads pose.joints, previousPose, environment
    ├── Rule: IK_CONSTRAINT_LIMIT — reads pose.joints, definition
    ├── Rule: POSITION_DISCONTINUITY — reads pose.joints, previousPose
    ├── Rule: VELOCITY_DISCONTINUITY — reads pose.joints, previousPose, prePreviousPose
    ├── Rule: ACCELERATION_SPIKE — reads pose.joints, previousPose, prePreviousPose
    ├── Rule: STATIC_SUPPORT_POLYGON — reads pose.joints, environment
    ├── Rule: BILATERAL_SYMMETRY — reads pose.bilateralSymmetryDelta
    ├── Rule: HAND_SHOULDER_ALIGNMENT — reads pose.joints
    ├── Rule: IK_TARGET_UNREACHABLE — reads pose.maxIkClampAmount
    ├── Rule: ANGULAR_JOINT_LIMIT — reads pose.joints, definition
    ├── Rule: STRAIGHT_LIMB_INTENT — reads pose.contacts, pose.joints
    ├── Rule: CONTACT_PRESERVED — reads pose.contacts, pose.joints
    ├── Rule: PELVIS_INTENT — reads pose.rootTranslationDelta, pose.rootRotationDelta
    └── Rule: HIP_ROM_LIMIT — reads pose.hipRomStamps, definition
```

### Arrow Semantics

| Arrow | Data | Owner | Ownership Change? | Mutation? |
|---|---|---|---|---|
| PoseBuilder → SkeletonPose | Intent carriers + node tree | PoseBuilder creates pose | Yes (pose is new) | Yes (pose is populated) |
| SkeletonPose → IkStage | pose.limbTargets, pose.roots | Pose owns limbTargets | No (read-only) | No |
| IkStage → SkeletonNode | localPosition writes | Node owns localPosition | No (in-place) | Yes (node.localPosition mutated) |
| SkeletonPose → ConstraintSolver | pose.contacts, pose.postureIntent, pose.roots | Pose owns contacts | No (read-only) | No |
| ConstraintSolver → SkeletonNode | pelvis.localPosition/rotation, contact limb localPosition | Node owns local transforms | No (in-place) | Yes (node transforms mutated) |
| ConstraintSolver → SkeletonPose | rootTranslationDelta, rootRotationDelta, boneLengthsVerified | Pose owns stamps | No (in-place) | Yes (stamps mutated) |
| ConstraintSolver → SkeletonPose | fromHierarchy() call (FK + flatten) | Pose owns joints/rotations arrays | No (in-place) | Yes (flat arrays written) |
| SkeletonPose → SkeletonPoseFinalizer | pose.roots, intent carriers, environment | Pose owns roots | No (read-only) | No |
| SkeletonPoseFinalizer → SkeletonNode | chest.localRotation, neck.localPosition, head.localPosition | Node owns local transforms | No (in-place) | Yes (node transforms mutated) |
| SkeletonPoseFinalizer → SkeletonPose | outputPose.joints, outputPose.rotations, stamps | Finalizer creates outputPose | Yes (new pose) | Yes (outputPose populated) |
| SkeletonPose → Renderer | finalized joints/rotations | Pose owns data | No (read-only) | No |
| SkeletonPose → ExerciseValidator | all pose fields | Pose owns data | No (read-only) | No |
| SkeletonPipeline → SkeletonPose | environment, supportedPoints (mutate input) | Pipeline writes to pose | No (in-place) | Yes (input pose mutated) |

---

## Part 5 — Mutation Map

### SkeletonNode mutable fields

| Field | Who writes | Who reads | Lifetime | Can become immutable? |
|---|---|---|---|---|
| `localPosition` | PoseBuilder.build(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize() | SkeletonNode.updateWorldTransforms(), SkeletonNode.flatten(), SkeletonProjector (indirectly via SkeletonPose) | Per-frame (mutated each frame) | No — FK requires mutable localPosition |
| `localRotation` | PoseBuilder.build(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize() | SkeletonNode.updateWorldTransforms(), SkeletonNode.flatten(), SkeletonPoseFinalizer (chest reconstruction, intent carriers) | Per-frame (mutated each frame) | No — FK requires mutable localRotation |
| `worldPosition` | SkeletonNode.updateWorldTransforms() | SkeletonNode.flatten(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize(), SkeletonProjector | Per-frame (recomputed each FK pass) | No — FK requires mutable worldPosition |
| `worldRotation` | SkeletonNode.updateWorldTransforms() | SkeletonNode.flatten(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize(), SkeletonProjector | Per-frame (recomputed each FK pass) | No — FK requires mutable worldRotation |
| `parent` | SkeletonNode.addChild() | SkeletonNode.updateWorldTransforms(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize() | Lifetime of the tree (set once, never changed) | Yes — parent is set once at build time |
| `children` | SkeletonNode.addChild() | SkeletonNode.updateWorldTransforms(), SkeletonNode.flatten(), ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize() | Lifetime of the tree (set once, never changed) | Yes — children are set once at build time |
| `joint` (Joint enum) | SkeletonFactory | SkeletonNode.flatten(), ConstraintSolver.collectNodes(), SkeletonPoseFinalizer.findJointNode(), IkStage.collect() | Lifetime of the tree (set once, never changed) | Yes — joint identity is set once at build time |
| Scratch buffers (pX/Y/Z, lX/Y/Z, wX/Y/Z) | SkeletonNode.updateWorldTransforms() | SkeletonNode.updateWorldTransforms() | Lifetime of the node | No — needed for FK matrix computation |

### SkeletonPose mutable fields

| Field | Who writes | Who reads | Lifetime | Can become immutable? |
|---|---|---|---|---|
| `joints` (Array<Vector3>) | SkeletonNode.flatten(), SkeletonPoseFinalizer.finalize() | SkeletonProjector.project(), ExerciseValidator, SkeletonRenderer, SkeletonSnapshotRenderer | Per-frame (recomputed each frame) | No — FK and finalizer must write positions |
| `rotations` (Array<JointRotation>) | SkeletonNode.flatten(), SkeletonPoseFinalizer.finalize() | SkeletonProjector (indirectly), ExerciseValidator (indirectly via joint positions) | Per-frame (recomputed each frame) | No — FK and finalizer must write rotations |
| `roots` (List<SkeletonNode>) | PoseBuilder.build(), SkeletonPose.fromHierarchy(), SkeletonPoseFinalizer.finalize() | ConstraintSolver.solve(), IkStage.apply(), SkeletonPoseFinalizer.finalize() | Per-frame (reassigned each build) | No — the tree is rebuilt each frame |
| `isTransformsUpdated` (Boolean) | SkeletonPose.fromHierarchy(), SkeletonPoseFinalizer.finalize() | SkeletonPoseFinalizer.finalize() | Per-frame (reset each build) | No — controls FK skip optimization |
| `maxIkClampAmount` (Float) | PoseBuilder.build() (bakeIkLimb), IkStage.apply() | ExerciseValidator (validateIkTargetReachability) | Per-frame (reset each build) | No — must be recomputed each build |
| `rootTranslationDelta` (Float) | ConstraintSolver.solve() | ExerciseValidator (validatePelvisIntent) | Per-frame (reset each build) | No — must reflect solver displacement |
| `rootRotationDelta` (Float) | ConstraintSolver.solve() | ExerciseValidator (validatePelvisIntent) | Per-frame (reset each build) | No — must reflect solver rotation |
| `boneLengthsVerified` (Boolean) | PoseBuilder.build() (bakeIkLimb), ConstraintSolver.solve(), IkStage.apply() | ExerciseValidator (validateBoneLengths) | Per-frame (reset each build) | No — must reflect IK integrity |
| `contacts` (MutableList<ContactSpec>) | PoseBuilder.build() (bakeIkLimb) | ConstraintSolver.solve(), SkeletonPoseFinalizer.finalize(), ExerciseValidator | Per-frame (rebuilt each build) | No — contacts are rebuilt each build |
| `jointIntents` (MutableList<RelativeArticulation>) | PoseBuilder.build() (declareJointIntent, buildChestTwist, etc.) | SkeletonPoseFinalizer.applyIntentCarriers() | Per-frame (rebuilt each build) | No — intents are rebuilt each build |
| `spineIntent` (SpineCurve) | PoseBuilder.build() (buildSpineCurve) | SkeletonPoseFinalizer.applyIntentCarriers() | Per-frame (rebuilt each build) | No — intent is rebuilt each build |
| `limbTargets` (MutableList<WorldTarget>) | PoseBuilder.build() (bakeIkLimb) | IkStage.apply() | Per-frame (rebuilt each build) | No — targets are rebuilt each build |
| `contactPrecedence` (MutableList<String>) | PoseBuilder.build() (declarePosture) | ConstraintSolver.solve() | Per-frame (rebuilt each build) | No — precedence is rebuilt each build |
| `postureIntent` (PostureIntent) | PoseBuilder.build() (declarePosture) | ConstraintSolver.solve() | Per-frame (rebuilt each build) | No — intent is rebuilt each build |
| `extremityOverrides` (MutableSet<Extremity>) | PoseBuilder.build() (overrideExtremityOrientation) | SkeletonPoseFinalizer.finalize() (isExtremityAutomatic) | Per-frame (rebuilt each build) | No — overrides are rebuilt each build |
| `extremityArticulations` (MutableMap<Extremity, JointRotation>) | PoseBuilder.build() (buildWristArticulation, buildAnkleArticulation) | SkeletonPoseFinalizer.finalize() (articulationFor) | Per-frame (rebuilt each build) | No — articulations are rebuilt each build |
| `headings` (MutableMap<Extremity, Vector3>) | PoseBuilder.build() (setHeading) | SkeletonPoseFinalizer.finalize() (getHeading) | Per-frame (rebuilt each build) | No — headings are rebuilt each build |
| `headTarget` (HeadTarget?) | PoseBuilder.build() (buildGaze) | SkeletonPoseFinalizer.finalize() (resolveHeadTarget) | Per-frame (rebuilt each build) | No — target is rebuilt each build |
| `environment` (EnvironmentDefinition) | PoseBuilder.build() (metadata), SkeletonPipeline.produceFrame() (stamped from metadata) | SkeletonPoseFinalizer.finalize() (support plane derivation) | Per-frame (rebuilt each build) | No — environment is rebuilt each build |
| `supportedPoints` (MutableSet<SupportPoint>) | PoseBuilder.build() (metadata), SkeletonPipeline.produceFrame() (stamped from metadata) | SkeletonPoseFinalizer.finalize() (isSupported checks) | Per-frame (rebuilt each build) | No — support points are rebuilt each build |
| `hipRomStamps` (MutableMap<Joint, HipRomStamp>) | SkeletonPoseFinalizer.finalize() (applyValidationStamps) | ExerciseValidator (validateHipRom) | Per-frame (recomputed each finalizer pass) | No — stamps must reflect final geometry |
| `bilateralSymmetryDelta` (Float) | SkeletonPoseFinalizer.finalize() (applyValidationStamps) | ExerciseValidator (validateBilateralSymmetry) | Per-frame (recomputed each finalizer pass) | No — delta must reflect final geometry |
| `bilateralOppositeBend` (Boolean) | SkeletonPoseFinalizer.finalize() (applyValidationStamps) | ExerciseValidator (validateBilateralSymmetry) | Per-frame (recomputed each finalizer pass) | No — flag must reflect final geometry |
| `straightIntentDropped` (Boolean) | ConstraintSolver.solve() | ExerciseValidator (validateStraightLimbIntent) | Per-frame (set by solver) | No — must reflect solver behavior |

---

## Part 6 — Candidate Extraction Points

These are natural points where the future runtime could split into Segment, Articulation, Attachment, IK Effector, and Helper types without changing behaviour.

### Candidate 1: Segment

**Extraction point**: `SkeletonNode.localPosition` when the node is a body segment (PELVIS, LUMBAR, CHEST).

**Current encoding**: A `SkeletonNode` with `Joint.PELVIS`, `Joint.LUMBAR`, or `Joint.CHEST` has `localPosition` representing the bone offset from its parent. This is a segment — a rigid body with length and orientation.

**How to extract**: Introduce a `Segment` type that owns `localPosition` (bone offset) and `length`. The `SkeletonNode` would reference its segment rather than carrying segment data directly. The FK computation would use the segment's length and orientation.

**Behaviour preserved**: The FK computation would produce identical results because the segment length and offset are unchanged.

### Candidate 2: Articulation

**Extraction point**: `SkeletonNode.localRotation` when the node is an articulation (NECK_END, CLAVICLE_A, SHOULDER_A, ELBOW_A, HIP_F, KNEE_F, ANKLE_F, etc.).

**Current encoding**: A `SkeletonNode` with a `Joint` value that represents a joint with DOF has `localRotation` representing the joint angle.

**How to extract**: Introduce an `Articulation` type that owns `localRotation` and joint constraints (type, limits). The `SkeletonNode` would reference its articulation rather than carrying articulation data directly. The solver and validator would operate on articulations.

**Behaviour preserved**: The solver and validator would produce identical results because the joint angle and constraints are unchanged.

### Candidate 3: Attachment

**Extraction point**: `SkeletonNode` entries that are terminal markers with no DOF (HEAD_POS, HEEL_F, TOE_F, PALM_A, FINGERTIPS_A, etc.).

**Current encoding**: These are `SkeletonNode` instances in the tree with no authored rotation. Their positions are derived by the Finalizer.

**How to extract**: Introduce an `Attachment` type that owns a `localOffset` from its parent segment and an `attachmentType` (heel, toe, palm, fingertip). The `SkeletonNode` would reference its attachment rather than being a full node. The Finalizer would compute attachment positions from the parent segment's pose.

**Behaviour preserved**: The Finalizer would produce identical results because the attachment positions are computed the same way.

### Candidate 4: IK Effector

**Extraction point**: `ContactSpec.endJoint` and `WorldTarget.joint` — the terminal IK solve targets (HAND_A, HAND_P, FINGERTIPS_A, FINGERTIPS_P, TOE_F, TOE_B).

**Current encoding**: These are `Joint` enum entries that serve as IK solve targets and contact points. They are also `SkeletonNode` instances in the tree.

**How to extract**: Introduce an `IKEffector` type that owns the end-effector identity, its parent articulation, and its contact metadata. The `ContactSpec` and `WorldTarget` would reference `IKEffector` instead of `Joint`.

**Behaviour preserved**: The solver would produce identical results because the IK chain definition and target positions are unchanged.

### Candidate 5: Helper

**Extraction point**: `WRIST_A`, `WRIST_P`, and the `extremityArticulations` carrier — the procedural nodes that carry grip/foot-plant rotation.

**Current encoding**: `WRIST_A` and `WRIST_P` are phantom enum entries with no `SkeletonNode` in the tree. Their rotation data is stored in `SkeletonPose.extremityArticulations`. The Finalizer reads this carrier for W1 extremity derivation.

**How to extract**: Introduce a `Helper` type that owns the procedural rotation data. The `extremityArticulations` map would reference `Helper` instances instead of `Joint` enum values.

**Behaviour preserved**: The Finalizer would produce identical results because the wrist/ankle rotation data is unchanged.

### Candidate 6: CoordinateFrame

**Extraction point**: `SkeletonNode` as a whole — the FK substrate that carries `localPosition`, `localRotation`, `worldPosition`, `worldRotation`.

**Current encoding**: `SkeletonNode` is the coordinate frame container that also carries biomechanical identity (`joint: Joint`), segment data (`localPosition`), articulation data (`localRotation`), and attachment topology (`children`).

**How to extract**: Introduce a `CoordinateFrame` type that owns only the transform data (`localPosition`, `localRotation`, `worldPosition`, `worldRotation`, `parent`, `children`). The biomechanical identity (segment, articulation, attachment, helper) would be provided by a separate mapping (e.g., a `SkeletonTopology` that maps frame indices to biomechanical roles).

**Behaviour preserved**: The FK computation would produce identical results because the transform data is unchanged.

### Candidate 7: SkeletonPose split

**Extraction point**: `SkeletonPose` as a single object that carries both intent (§1.1) and state (§1.2).

**Current encoding**: `SkeletonPose` has intent fields (`contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`) and state fields (`joints`, `rotations`, `roots`, `hipRomStamps`, `bilateralSymmetryDelta`, etc.).

**How to extract**: Split `SkeletonPose` into `PoseIntent` (the §1.1 section, written by Pose authors) and `PoseState` (the §1.2 section, written by the engine). The pipeline would pass both objects through the stages.

**Behaviour preserved**: The pipeline stages would produce identical results because the data is unchanged — only the ownership boundary is made structural.

---

## Summary

The dependency graph reveals a tightly coupled system where:

1. **The `Joint` enum is the single coupling point** between the tree, the flat arrays, the solver's node map, and the rendering bone list. Every subsystem reads `Joint.ordinal` or `Joint.index`.

2. **`SkeletonNode` is the most coupled object** — it is read and written by every pipeline stage, and its fields serve multiple semantic roles (segment offset, articulation angle, attachment host, IK substrate).

3. **`SkeletonPose` is the data contract** that all stages share, but it is mutated in-place by every stage, creating implicit ordering constraints.

4. **The Finalizer is the most complex consumer** — it reads from and writes to the node tree, the flat arrays, and multiple intent carriers, making it the highest-risk component for any refactor.

5. **The `SkeletonNodes` aliases** (`wristA = handA`, `wristP = handP`, etc.) create a parallel naming system that obscures the actual `Joint` enum values used by the rest of the codebase.

6. **The `WRIST_A`/`WRIST_P` phantom entries** in the `Joint` enum have no corresponding `SkeletonNode` in the tree, yet they occupy array indices in `SkeletonPose.joints` and `SkeletonPose.rotations`.

These coupling points are the natural extraction points for a future semantic refactor. Each candidate in Part 6 identifies a place where a new type can be introduced without changing runtime behaviour, because the data and computation remain the same — only the ownership and type boundaries change.
