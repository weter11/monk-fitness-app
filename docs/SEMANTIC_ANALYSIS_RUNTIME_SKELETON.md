# Semantic Analysis: Runtime Skeleton Model

> Discovering the semantic architecture beneath the implementation.
> No refactoring. No renaming. No code changes.
> Based on source code analysis of the Monk Fitness v2 skeleton pipeline.

---

## Part 1 — Domain Objects

### 1.1 Body Segment

**What it represents physically:** A rigid body in the biomechanical chain. The upper arm, forearm, thigh, shin, torso, and skull are all segments. Each segment has a length and connects two articulations (or an articulation and an attachment).

**Identity:** Yes. Each segment has a unique identity in the skeleton topology. The torso is one segment; the upper arm is another. They are distinct rigid bodies.

**Lifetime:** Permanent. The segment topology is fixed for a given skeleton definition. It does not change between frames or poses.

**Owns state:** Yes. A segment owns its length (from `SkeletonDefinition`: `torsoLength`, `upperArmLength`, `thighLength`, etc.) and its current pose (position + orientation in world space).

**Currently represented:** Implicitly. The segment is not a first-class runtime object. Its length is stored in `SkeletonDefinition` (e.g., `definition.upperArmLength`). Its pose is distributed across two `SkeletonNode` instances — the parent articulation's `worldPosition` and the child articulation's `worldPosition`. The segment's orientation is the parent articulation's `worldRotation`. The segment's length is the distance between the two articulations when the joint is at zero.

**Evidence from code:**
- `SkeletonDefinition` carries `torsoLength`, `neckLength`, `thighLength`, `shinLength`, `footLength`, `upperArmLength`, `forearmLength`, `shoulderWidth`, `hipWidth` — these are segment lengths.
- `ExerciseValidator.validateBoneLengths()` validates segment lengths by measuring the distance between parent and child joint positions in `SkeletonPose.joints`.
- `SkeletonNode.localPosition` stores the bone offset — the vector from parent to child, which encodes the segment's direction and length.

### 1.2 Articulation (Joint with DOF)

**What it represents physically:** A point where two segments meet, allowing relative rotation. The shoulder, elbow, hip, knee, neck, and wrist are articulations. Each has a specific range of motion and joint type (ball-and-socket, hinge, etc.).

**Identity:** Yes. Each articulation has a unique identity (e.g., the left shoulder is distinct from the right shoulder).

**Lifetime:** Permanent. The articulation topology is fixed for a given skeleton definition.

**Owns state:** Yes. An articulation owns its current rotation angle (the joint angle relative to its parent segment). It also owns its angular limits (from `SkeletonDefinition.armAngularLimits` and `legAngularLimits`).

**Currently represented:** Explicitly, but conflated with other concepts. The `Joint` enum entries classified as articulations (SHOULDER_A, ELBOW_A, HIP_F, KNEE_F, etc.) are the primary representation. The rotation is stored in `SkeletonNode.localRotation`. The angular limits are in `SkeletonDefinition`.

**Evidence from code:**
- `ConstraintSolver.solve()` operates on articulations — it re-bakes contact limbs by computing IK solutions for the articulation chain.
- `ExerciseValidator.validateAngularJointLimits()` reads articulation angles from `SkeletonPose.rotations` (world-space) and compares them against limits from `SkeletonDefinition`.
- `BasePose.buildHipFlexion()`, `buildHipRotation()`, `buildChestTwist()`, `buildClavicularRotation()`, `buildWristArticulation()`, `buildAnkleArticulation()` all write to `SkeletonNode.localRotation` — these are articulation angle authors.

### 1.3 Attachment (Fixed Point on a Segment)

**What it represents physically:** A fixed point on a segment used for environment interaction or as a rendering endpoint. The palm, fingertip, heel, and toe are attachments. They do not have independent rotational DOF — their position is derived from the segment's pose.

**Identity:** Yes. Each attachment has a unique identity (e.g., left palm is distinct from right palm).

**Lifetime:** Permanent. The attachment topology is fixed.

**Owns state:** No. An attachment does not own orientation. It owns a local offset from its parent segment (or parent articulation). Its world position is derived from the parent segment's pose plus the offset.

**Currently represented:** Implicitly. Attachments are `Joint` enum entries (PALM_A, FINGERTIPS_A, HEEL_F, TOE_F, etc.) that have `SkeletonNode` instances in the tree, but they carry no authored rotation. Their positions are computed by the Finalizer's extremity derivation logic (`adjustHandOrientation`, `adjustFootOrientation`).

**Evidence from code:**
- `SkeletonPoseFinalizer.adjustHandOrientation()` computes palm, knuckle, and fingertip positions from the wrist direction, hand definition, and wrist rotation.
- `SkeletonPoseFinalizer.adjustFootOrientation()` computes heel and toe positions from the ankle direction, foot definition, and ankle rotation.
- `ExerciseValidator.validateBoneLengths()` validates `HAND_A→WRIST_A` as a zero-length bone (attachment-to-attachment), `WRIST_A→PALM_A` as half-palm-length, etc.

### 1.4 Coordinate Frame

**What it represents physically:** A position and orientation in 3D space. Every segment and articulation has a coordinate frame. The frame describes where the segment is and which direction it faces.

**Identity:** No. Coordinate frames are derived — they are computed from the parent frame and the local transform. They do not have independent identity.

**Lifetime:** Per-frame. Coordinate frames are recomputed every FK traversal.

**Owns state:** No. A coordinate frame is a computed value — it has no independent state. It is derived from the parent frame and the local transform (position offset + rotation).

**Currently represented:** Explicitly, as `SkeletonNode.worldPosition` and `SkeletonNode.worldRotation`. These are computed by `SkeletonNode.updateWorldTransforms()` via FK traversal. They are also stored in the flat `SkeletonPose.joints` and `SkeletonPose.rotations` arrays after `flatten()`.

**Evidence from code:**
- `SkeletonNode.updateWorldTransforms()` computes `worldPosition` by rotating `localPosition` by the parent's `worldRotation` and adding the parent's `worldPosition`. It computes `worldRotation` by concatenating the parent's `worldRotation` matrix with the local `localRotation` matrix.
- `SkeletonNode.flatten()` writes `worldPosition` and `worldRotation` into `SkeletonPose.joints` and `SkeletonPose.rotations`.
- `SkeletonPose.fromHierarchy()` calls `updateWorldTransforms()` and `flatten()` for all roots.

### 1.5 Pose Intent

**What it represents physically:** What the pose author wants the body to do. This includes which body parts are in contact with the environment, where the limbs should reach, what the overall posture is, and how the extremities should be oriented.

**Identity:** No. Intent is per-pose and per-frame. It does not have independent identity — it is a declaration attached to a specific pose snapshot.

**Lifetime:** Per-frame. Intent is declared during `PoseBuilder.build()` and consumed by the pipeline stages.

**Owns state:** No. Intent is declarative — it describes desired outcomes, not computed results. The engine owns the computation that satisfies the intent.

**Currently represented:** Explicitly, as the §1.1 section of `SkeletonPose`. The intent carriers are: `contacts` (ContactSpec list), `limbTargets` (WorldTarget list), `jointIntents` (RelativeArticulation list), `spineIntent` (SpineCurve), `postureIntent` (PostureIntent), `extremityOverrides` (Extremity set), `extremityArticulations` (Map<Extremity, JointRotation>), `headTarget` (HeadTarget), `headings` (Map<Extremity, Vector3>), `environment` (EnvironmentDefinition), `supportedPoints` (Set<SupportPoint>).

**Evidence from code:**
- `SkeletonPose` KDoc explicitly labels the §1.1 intent section: "written by Pose, read by Engine."
- `SkeletonPose.IntentBuilder` is the sole mutator of intent carriers (B0 compile guard).
- `ConstraintSolver.solve()` reads `postureIntent`, `contacts`, `contactPrecedence`.
- `SkeletonPoseFinalizer.finalize()` reads `jointIntents`, `spineIntent`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`.

### 1.6 Pose State

**What it represents physically:** The actual computed position and orientation of every joint in the skeleton at a specific moment. This is the result of satisfying the pose intent through FK, IK, and constraint solving.

**Identity:** No. State is per-pose and per-frame. It is the output of the pipeline, not an independent entity.

**Lifetime:** Per-frame. State is computed during `SkeletonPipeline.runStages()` and consumed by the renderer and validator.

**Owns state:** Yes. The state owns the flat arrays `joints` (positions) and `rotations` (world-space rotations), plus derived stamps (`hipRomStamps`, `bilateralSymmetryDelta`, `boneLengthsVerified`, `rootTranslationDelta`, `rootRotationDelta`).

**Currently represented:** Explicitly, as the §1.2 section of `SkeletonPose`. The state fields are: `joints`, `rotations`, `roots`, `isTransformsUpdated`, `maxIkClampAmount`, `boneLengthsVerified`, `straightIntentDropped`, `rootTranslationDelta`, `rootRotationDelta`, `contacts`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`.

**Evidence from code:**
- `SkeletonPose` KDoc explicitly labels the §1.2 state section: "written by Engine, read by Validation."
- `SkeletonPoseFinalizer.applyValidationStamps()` writes `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`.
- `ConstraintSolver.solve()` writes `rootTranslationDelta`, `rootRotationDelta`, `boneLengthsVerified`.

### 1.7 Contact

**What it represents physically:** A fixed point where a body segment touches the environment. A planted foot on the floor, a hand on a bar, a knee on the ground. The contact defines where the segment is anchored and what surface it rests on.

**Identity:** Yes. Each contact has a unique identity (which body point is in contact, and with what surface).

**Lifetime:** Per-build. Contacts are declared during `PoseBuilder.build()` and consumed by the solver during the same frame.

**Owns state:** Yes. A contact owns its target world position, the surface normal, the contact constraint (if any), and the IK chain context (root joint, parent rotation joint, middle joint, bone lengths, pole direction, straight flag).

**Currently represented:** Explicitly, as `ContactSpec` objects stored in `SkeletonPose.contacts`. Also as `ContactConstraint` objects referenced by `ContactSpec.contact`.

**Evidence from code:**
- `ContactSpec` is a data class with `endJoint`, `rootJoint`, `parentRotationJoint`, `middleJoint`, `targetWorld`, `pole`, `length1`, `length2`, `constraint`, `straight`, `contact`.
- `BasePose.bakeIkLimb()` creates `ContactSpec` objects and adds them to `pose.contacts`.
- `ConstraintSolver.solve()` reads `pose.contacts` to reposition the root and re-bake contact limbs.
- `ConstraintSolver.chainForEnd()` maps each end-effector joint to its proximal IK chain.

### 1.8 IK Target

**What it represents physically:** A world-space position that a limb end-effector should reach. The IK target is the goal that the solver tries to satisfy. It is not the same as a contact — a target can be unreachable (the solver will clamp it), while a contact is always honored.

**Identity:** Yes. Each IK target has a unique identity (which joint is targeted, and where it should be).

**Lifetime:** Per-build. Targets are declared during `PoseBuilder.build()` and consumed by the solver or IK stage.

**Owns state:** No. A target is a declaration — it specifies the desired end-effector position, the pole direction, whether the limb should be straight, and optionally a contact constraint.

**Currently represented:** Explicitly, as `WorldTarget` objects stored in `SkeletonPose.limbTargets`.

**Evidence from code:**
- `WorldTarget` is a data class with `joint`, `world`, `pole`, `straight`, `contact`.
- `BasePose.bakeIkLimb()` adds `WorldTarget` objects to `pose.limbTargets`.
- `IkStage.apply()` consumes `pose.limbTargets` when `IK_STAGE_ACTIVE` is true.
- When `IK_STAGE_ACTIVE` is false (the default), `bakeIkLimb()` solves IK inline and the `limbTargets` carrier is populated but never consumed.

### 1.9 Orientation Constraint

**What it represents physically:** A limit on how far a joint can rotate. Angular limits prevent the elbow from hyperextending, the knee from bending backward, and the hip from exceeding human ROM.

**Identity:** Yes. Each constraint has a unique identity (which joint it applies to, and what the limits are).

**Lifetime:** Permanent. Constraints are defined in `SkeletonDefinition` and do not change between frames.

**Owns state:** Yes. A constraint owns the minimum and maximum flexion angles, the effective extension ratio, and the angular limits (min/max flexion, abduction, rotation).

**Currently represented:** Explicitly, as `IKConstraint` (arm and leg constraints) and `AngularJointLimits` in `SkeletonDefinition`. Also `HipRomLimits` for hip-specific ROM.

**Evidence from code:**
- `SkeletonDefinition` carries `armIKConstraint`, `legIKConstraint`, `armAngularLimits`, `legAngularLimits`, `hipRomLimits`.
- `ConstraintSolver.solve()` uses `spec.constraint.effectiveExtensionRatio` and `spec.constraint.minimumFlexionAngle` for reachability computation.
- `ExerciseValidator.validateAngularJointLimits()` reads `def.armAngularLimits` and `def.legAngularLimits` to check middle-joint flexion angles.
- `ExerciseValidator.validateHipRom()` reads `def.hipRomLimits` to check hip ROM.

### 1.10 Skeleton Topology

**What it represents physically:** The fixed tree structure of the skeleton — which segments connect to which articulations, which attachments hang off which joints, and the parent-child relationships that define the kinematic chain.

**Identity:** Yes. The topology is a single, fixed structure for a given skeleton definition.

**Lifetime:** Permanent. The topology does not change between frames or poses.

**Owns state:** No. Topology is structural — it defines relationships, not values. It has no mutable state.

**Currently represented:** Implicitly, distributed across multiple structures:
- `SkeletonFactory` hardcodes the tree structure (which nodes are children of which).
- `SkeletonNode` parent/children links encode the tree.
- `SkeletonEngine.bones` hardcodes the rendering bone list (which joint pairs are connected by bones).
- `ConstraintSolver.chainForEnd()` hardcodes the IK chain mapping (which joints form which limb chains).
- `ExerciseValidator.validateBoneLengths()` hardcodes the bone-length validation pairs.

**Evidence from code:**
- `SkeletonFactory.createStandardSkeleton()` builds the tree by calling `addChild()` in a fixed order.
- `SkeletonEngine.bones` defines 30 bone pairs as `Bone(parentJoint, childJoint, thickness, colorMultiplier)`.
- `ConstraintSolver.chainForEnd()` maps each contact end-effector to its proximal chain (rootJoint, parentRotationJoint, middleJoint).
- `ExerciseValidator.validateBoneLengths()` validates 24 specific parent→child joint pairs.

---

## Part 2 — Semantic Compression

### Compression 1: SkeletonNode merges Coordinate Frame + Articulation + Segment + Attachment Host

**Concepts merged:**
- Coordinate Frame (position + orientation in 3D space)
- Articulation (joint with rotational DOF)
- Segment (rigid body with length)
- Attachment Host (parent for endpoint nodes)

**Why they are different semantically:**
- A coordinate frame is a computed value — it has no independent identity.
- An articulation is a joint with DOF and limits — it has identity and owns a rotation angle.
- A segment is a rigid body with length — it has identity and owns a bone offset.
- An attachment host is a structural role — it is a parent for terminal nodes.

**Why the runtime treats them as one object:**
`SkeletonNode` is the FK substrate. The FK computation needs a single object that carries both the local transform (position + rotation) and the tree structure (parent + children). The biomechanical identity (articulation, segment, attachment) is added as a `Joint` enum tag. This is the simplest way to implement FK — one object, one traversal, one set of transforms.

**Runtime responsibility each concept actually owns:**
- Coordinate Frame: `worldPosition` + `worldRotation` (computed by FK)
- Articulation: `localRotation` (the joint angle)
- Segment: `localPosition` (the bone offset)
- Attachment Host: `children` list (the terminal nodes)

### Compression 2: Joint enum merges Segment + Articulation + Attachment + Helper

**Concepts merged:**
- Segment (PELVIS, LUMBAR, CHEST — rigid bodies)
- Articulation (SHOULDER_A, ELBOW_A, HIP_F, KNEE_F — joints with DOF)
- Attachment (HEAD_POS, HEEL_F, PALM_A — fixed markers)
- Helper (WRIST_A, WRIST_P — procedural nodes with no tree presence)

**Why they are different semantically:**
- A segment is a rigid body with length and pose.
- An articulation is a joint with rotational DOF and limits.
- An attachment is a fixed point on a segment with no DOF.
- A helper is a procedural node that carries rotation for derivation but is not an anatomical joint.

**Why the runtime treats them as one object:**
The `Joint` enum serves as the single index into the flat `SkeletonPose` arrays (`joints` and `rotations`). Every joint must have an ordinal to index into these arrays. Adding a category field would require changing the enum structure. The enum also serves as the identity for `SkeletonNode` instances in the tree.

**Runtime responsibility each concept actually owns:**
- Segment: owns bone length (from `SkeletonDefinition`) and segment pose
- Articulation: owns rotation angle and angular limits
- Attachment: owns a local offset from its parent segment
- Helper: owns a procedural rotation for extremity derivation

### Compression 3: SkeletonPose merges Intent + State + Transport

**Concepts merged:**
- Pose Intent (what the pose author declared — contacts, limb targets, posture, headings)
- Pose State (the computed result — joint positions, rotations, stamps)
- Transport Object (the data passed between pipeline stages)

**Why they are different semantically:**
- Intent is declarative — it describes desired outcomes.
- State is computed — it describes actual results.
- Transport is structural — it is the data contract between stages.

**Why the runtime treats them as one object:**
`SkeletonPose` is the single data structure that flows through the pipeline. The intent and state are stored in the same object because the pipeline stages need to read both. The §1.1 / §1.2 labeling in the KDoc is a convention, not a structural boundary.

**Runtime responsibility each concept actually owns:**
- Intent: `contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`
- State: `joints`, `rotations`, `roots`, `isTransformsUpdated`, `maxIkClampAmount`, `boneLengthsVerified`, `rootTranslationDelta`, `rootRotationDelta`, `straightIntentDropped`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`
- Transport: the object itself as it passes between stages

### Compression 4: Bone merges Rendering Primitive + Structural Definition

**Concepts merged:**
- Rendering Primitive (a line between two screen-space points with thickness and color)
- Structural Definition (which joints are connected by a bone in the skeleton)

**Why they are different semantically:**
- A rendering primitive is a visual concern — how thick the line is, what color it is, whether it is foreground or background.
- A structural definition is a biomechanical concern — which joints form a bone in the skeleton hierarchy.

**Why the runtime treats them as one object:**
`Bone` is a data class with `parentJoint`, `childJoint`, `thickness`, and `colorMultiplier`. The joint pair defines the structural connection, while the thickness and color define the visual appearance. The `SkeletonEngine` hardcodes both the structural topology and the visual parameters in one list.

**Runtime responsibility each concept actually owns:**
- Rendering Primitive: `thickness`, `colorMultiplier`, `isForeground`
- Structural Definition: `parentJoint`, `childJoint`

### Compression 5: ContactSpec merges Biomechanical Contact + Solver Configuration

**Concepts merged:**
- Biomechanical Contact (a body point touching a surface)
- Solver Configuration (the IK chain context, pole direction, straight flag, constraint)

**Why they are different semantically:**
- A biomechanical contact is a physical fact — the foot is on the floor, the hand is on the bar.
- Solver configuration is an engineering detail — how the solver should re-bake the limb, what the pole direction is, whether the limb should be straight.

**Why the runtime treats them as one object:**
`ContactSpec` is created by `bakeIkLimb()` at authoring time, and the solver needs all the context to re-bake the limb after root repositioning. The biomechanical contact and the solver configuration are tightly coupled because the solver needs to know both the contact point and the IK chain context to re-bake correctly.

**Runtime responsibility each concept actually owns:**
- Biomechanical Contact: `endJoint`, `targetWorld`, `contact` (the `ContactConstraint` with surface normal)
- Solver Configuration: `rootJoint`, `parentRotationJoint`, `middleJoint`, `pole`, `length1`, `length2`, `constraint`, `straight`

### Compression 6: SkeletonNode scratch buffers merge FK Computation + Node Identity

**Concepts merged:**
- FK Computation scratch (matrix columns for rotation math)
- Node identity (the `joint` field)

**Why they are different semantically:**
- FK scratch buffers are temporary computation artifacts — they exist only during the FK traversal.
- Node identity is permanent — it identifies which joint this node represents.

**Why the runtime treats them as one object:**
`SkeletonNode` is a class that carries both permanent state (`joint`, `localPosition`, `localRotation`, `parent`, `children`) and scratch state (`pX/Y/Z`, `lX/Y/Z`, `wX/Y/Z`). The scratch buffers are persistent fields to avoid allocation during FK traversal, but they are logically temporary.

**Runtime responsibility each concept actually owns:**
- FK Computation scratch: `pX/Y/Z`, `lX/Y/Z`, `wX/Y/Z` (matrix columns for rotation math)
- Node identity: `joint` field

---

## Part 3 — Stable Invariants

### Invariant 1: The skeleton tree is a single-rooted forest

**Proof:** `SkeletonFactory.createStandardSkeleton()` creates a single root node (`pelvis = SkeletonNode(Joint.PELVIS)`) and all other nodes are descendants of this root via `addChild()`. The `SkeletonNodes.roots` list contains exactly one node (`listOf(pelvis)`). The `createPushUpSkeleton()` variant has a different root (`ankleF`) but is still a single-rooted tree. The `SkeletonPose.roots` field is a `List<SkeletonNode>` that is populated from the factory output.

### Invariant 2: Every SkeletonNode has exactly one parent (except roots)

**Proof:** `SkeletonNode.addChild()` sets `node.parent = this`. A node can only be added as a child of one parent. The `parent` field is a nullable `SkeletonNode?` that is set exactly once (at `addChild()` time) and never changed afterward.

### Invariant 3: FK is a pure function of local transforms and parent world transforms

**Proof:** `SkeletonNode.updateWorldTransforms(parentWorldPos, parentWorldRotation)` computes `worldPosition` and `worldRotation` deterministically from `localPosition`, `localRotation`, `parentWorldPos`, and `parentWorldRotation`. The computation uses only matrix multiplication and vector addition — no side effects, no randomness, no external state. Given the same inputs, the output is identical.

### Invariant 4: The flat joint arrays are indexed by Joint.ordinal

**Proof:** `SkeletonPose.joints` is `Array<Vector3>(Joint.entries.size) { Vector3() }` and `SkeletonPose.rotations` is `Array<JointRotation>(Joint.entries.size) { JointRotation() }`. `SkeletonNode.flatten()` writes `target.setJoint(joint, worldPosition)` which calls `target.joints[joint.index].set(worldPosition)`. Similarly for `setJointRotation`. Every access to the flat arrays uses `Joint.index` (which equals `Joint.ordinal`).

### Invariant 5: Attachments never have children that are articulations

**Proof:** In the tree built by `SkeletonFactory`, attachment nodes (`HEAD_POS`, `HEEL_F`, `TOE_F`, `PALM_A`, `FINGERTIPS_A`, etc.) are always leaf nodes — they have no children. The `children` list of an attachment node is always empty. Articulations (`SHOULDER_A`, `ELBOW_A`, etc.) are internal nodes that have children.

### Invariant 6: The solver only repositions the root and re-bakes contact limbs

**Proof:** `ConstraintSolver.solve()` only modifies `pelvis.localPosition`, `pelvis.localRotation`, and the `localPosition` of contact limb nodes (middle and end nodes). It never modifies non-contact limb nodes. The solver's `nodeMap` is populated from `pose.roots` and only contact chain nodes are written to.

### Invariant 7: The Finalizer only modifies local transforms, never creates new nodes

**Proof:** `SkeletonPoseFinalizer.finalize()` calls `chest.localRotation.set(...)`, `neck.localPosition.set(...)`, `head.localPosition.set(...)`, and `handJointsBuffer` writes to `pose.getJoint(palmId).set(...)`, etc. It never creates new `SkeletonNode` instances or modifies the tree topology. It only modifies existing node fields and writes to the flat pose arrays.

### Invariant 8: The Joint enum ordinal is stable and contiguous

**Proof:** The `Joint` enum has entries with explicit ordinal values from 0 to 32. The `SkeletonPose` arrays are sized to `Joint.entries.size`. The `ConstraintSolver.nodeMap` is `Array<SkeletonNode?>(Joint.entries.size)`. Adding or reordering enum entries would change array indices and break all code that uses ordinal-based indexing.

### Invariant 9: World rotations in SkeletonPose.rotations are the result of FK composition

**Proof:** `SkeletonNode.flatten()` writes `worldRotation` (not `localRotation`) to `SkeletonPose.rotations`. `worldRotation` is computed by `updateWorldTransforms()` as the concatenation of all parent rotations with the local rotation. Therefore, `SkeletonPose.rotations` contains world-space rotations, not local-space rotations.

### Invariant 10: Intent carriers are cleared and rebuilt each build

**Proof:** `SkeletonPose.IntentBuilder.reset()` clears all intent carriers (`spineIntent`, `jointIntents`, `limbTargets`, `extremityOverrides`, `extremityArticulations`, `headings`, `environment`, `supportedPoints`, `postureIntent`, `contactPrecedence`, `contacts`, `headTarget`). Each `PoseBuilder.build()` call starts with a fresh pose or resets the intent carriers before populating them.

---

## Part 4 — Runtime Layers

### Layer 1: Biomechanical Model

**What belongs here:** The physical description of the human body — segments, articulations, attachments, their lengths, their limits, and their topological relationships.

**Objects:**
- `SkeletonDefinition` — segment lengths, proportions, constraints
- `Joint` enum — the identity of each biomechanical element
- `SkeletonFactory` / `SkeletonNodes` — the fixed topology
- `ContactSpec.chainForEnd()` — the IK chain mapping
- `ExerciseValidator.validateBoneLengths()` — the bone-length pairs

**Cross-layer boundaries:**
- This layer is read by every other layer. It never mutates.
- The biomechanical model does not know about FK, solving, or rendering.

### Layer 2: Intent

**What belongs here:** What the pose author declares — contacts, limb targets, posture, headings, extremity overrides, spine curves, joint articulations.

**Objects:**
- `SkeletonPose.contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`
- `PoseBuilder` / `BasePose` — the authoring API
- `SkeletonPose.IntentBuilder` — the sole mutator of intent carriers

**Cross-layer boundaries:**
- Intent is written by this layer and read by the Constraint Solver, Finalizer, and Validator.
- Intent does not contain computed state — it only declares desired outcomes.

### Layer 3: Topology

**What belongs here:** The tree structure of the skeleton — parent-child relationships, the FK traversal order, the mapping from Joint enum to tree nodes.

**Objects:**
- `SkeletonNode` — the tree node (parent, children, local transform)
- `SkeletonPose.roots` — the root list
- `SkeletonNode.updateWorldTransforms()` — the FK traversal algorithm
- `SkeletonNode.flatten()` — the flattening algorithm

**Cross-layer boundaries:**
- Topology is built from the biomechanical model (Layer 1) and the intent (Layer 2).
- Topology is consumed by the Kinematic State layer (Layer 4) for FK computation.
- Topology is read by the Constraint Solver (Layer 5) for node lookup.

### Layer 4: Kinematic State

**What belongs here:** The computed position and orientation of every joint in the skeleton at a specific moment. This is the result of FK traversal.

**Objects:**
- `SkeletonPose.joints` — flat array of world positions
- `SkeletonPose.rotations` — flat array of world rotations
- `SkeletonPose.isTransformsUpdated` — flag indicating whether FK has been computed
- `SkeletonNode.worldPosition` / `worldRotation` — per-node computed transforms

**Cross-layer boundaries:**
- Kinematic state is computed from topology (Layer 3) and local transforms (from intent, Layer 2, or solver, Layer 5).
- Kinematic state is consumed by the Renderer (Layer 7) and Validator (Layer 8).
- Kinematic state is the input to the Finalizer (Layer 6) for extremity derivation and stamp production.

### Layer 5: Constraint Solving

**What belongs here:** The global solver that repositions the root and re-bakes contact limbs to satisfy fixed support contacts and posture intent.

**Objects:**
- `ConstraintSolver` — the solver singleton
- `SkeletonPose.contacts` — the contact list (also an intent carrier)
- `SkeletonPose.postureIntent` — the posture intent (also an intent carrier)
- `SkeletonPose.rootTranslationDelta`, `rootRotationDelta` — solver output stamps
- `SkeletonPose.boneLengthsVerified` — solver output stamp

**Cross-layer boundaries:**
- The solver reads intent (Layer 2) — contacts, posture intent, contact precedence.
- The solver reads topology (Layer 3) — the node tree, parent-child relationships.
- The solver mutates kinematic state (Layer 4) — node local transforms, flat arrays (via `fromHierarchy`).
- The solver writes state stamps (Layer 4) — root deltas, bone length verification.

### Layer 6: Finalization

**What belongs here:** The post-solve processing that completes the 3D pose — FK traversal, chest-frame reconstruction, head-target resolution, extremity orientation derivation, validation stamp production.

**Objects:**
- `SkeletonPoseFinalizer` — the finalizer
- `SkeletonPose.roots` — the node tree (input)
- `SkeletonPose.joints` / `SkeletonPose.rotations` — the flat arrays (output)
- `SkeletonPose.hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend` — validation stamps
- `SkeletonNode.localRotation` — mutated for chest reconstruction and head-target resolution
- `SkeletonNode.localPosition` — mutated for head-target resolution

**Cross-layer boundaries:**
- The finalizer reads intent (Layer 2) — joint intents, spine intent, extremity articulations, head target, environment, supported points.
- The finalizer reads kinematic state (Layer 4) — the node tree after solver.
- The finalizer mutates kinematic state (Layer 4) — node local transforms, flat arrays.
- The finalizer writes state stamps (Layer 4) — hip ROM stamps, bilateral symmetry.

### Layer 7: Rendering

**What belongs here:** The projection of 3D skeleton state into 2D screen space and the drawing of bones, joints, and faces.

**Objects:**
- `SkeletonRenderer` / `SkeletonSnapshotRenderer` — the rendering entry points
- `SkeletonProjector` — the 3D→2D projection
- `SkeletonEngine.bones` — the rendering bone list
- `SkeletonStyle` — visual parameters
- `ProjectedSkeleton` — the screen-space buffer
- `Camera` — the view configuration

**Cross-layer boundaries:**
- Rendering reads kinematic state (Layer 4) — the finalized `SkeletonPose`.
- Rendering reads the biomechanical model (Layer 1) — `SkeletonEngine.bones` for bone topology, `SkeletonStyle` for visual parameters.
- Rendering does not mutate any state — it is purely read-only on the pose.

### Layer 8: Validation

**What belongs here:** The biomechanical rule checking — finite coordinates, bone lengths, viewport, ground penetration, dynamics, symmetry, IK limits, hip ROM, straight-limb intent, contact preservation, pelvis intent.

**Objects:**
- `ExerciseValidator` — the validator
- `ValidatorConfig` — the validation configuration
- `ValidationReport`, `ValidationResult`, `ValidationIssue` — the validation output

**Cross-layer boundaries:**
- Validation reads kinematic state (Layer 4) — the finalized `SkeletonPose`.
- Validation reads the biomechanical model (Layer 1) — `SkeletonDefinition` for bone lengths and constraints.
- Validation reads state stamps (Layer 4) — `hipRomStamps`, `bilateralSymmetryDelta`, `rootTranslationDelta`, etc.
- Validation reads intent (Layer 2) — indirectly, through the pose's contacts and posture intent.
- Validation does not mutate any state — it is purely read-only on the pose.

### Layer Boundary Crossings

| From Layer | To Layer | Data | Coupling Type |
|---|---|---|---|
| Intent (2) → Topology (3) | PoseBuilder writes node local transforms | `SkeletonNode.localPosition`, `localRotation` | Intent drives topology mutation |
| Intent (2) → Constraint Solver (5) | `pose.contacts`, `pose.postureIntent` | Contact specs, posture intent | Intent drives solving |
| Topology (3) → Kinematic State (4) | FK traversal | `SkeletonPose.joints`, `SkeletonPose.rotations` | Topology produces state |
| Constraint Solver (5) → Kinematic State (4) | Solver repositions root, re-bakes limbs | Node local transforms, flat arrays | Solver mutates state |
| Kinematic State (4) → Finalization (6) | Finalizer reads node tree | `SkeletonPose.roots`, node transforms | State drives finalization |
| Finalization (6) → Kinematic State (4) | Finalizer writes extremity positions, stamps | `SkeletonPose.joints`, `SkeletonPose.rotations`, stamps | Finalization mutates state |
| Kinematic State (4) → Rendering (7) | Renderer reads finalized pose | `SkeletonPose.joints`, `SkeletonPose.rotations` | State drives rendering |
| Biomechanical Model (1) → Rendering (7) | `SkeletonEngine.bones` defines bone topology | Bone list | Model drives rendering |
| Kinematic State (4) → Validation (8) | Validator reads pose and stamps | All `SkeletonPose` fields | State drives validation |
| Biomechanical Model (1) → Validation (8) | `SkeletonDefinition` provides bone lengths and limits | Definition fields | Model drives validation |
| Finalization (6) → Validation (8) | Finalizer produces stamps for validator | `hipRomStamps`, `bilateralSymmetryDelta` | Finalization drives validation |

---

## Part 5 — Semantic Ownership

### SkeletonNode fields

| Field | What concept actually owns it |
|---|---|
| `joint: Joint` | **Skeleton Topology** — the identity of this node in the biomechanical model |
| `parent: SkeletonNode?` | **Skeleton Topology** — the parent-child relationship in the tree |
| `children: MutableList<SkeletonNode>` | **Skeleton Topology** — the children of this node in the tree |
| `localPosition: Vector3` | **Segment** — the bone offset from the parent articulation (the segment's direction and length) |
| `localRotation: JointRotation` | **Articulation** — the joint angle relative to the parent segment |
| `worldPosition: Vector3` | **Coordinate Frame** — the computed world position of this node's coordinate frame |
| `worldRotation: JointRotation` | **Coordinate Frame** — the computed world orientation of this node's coordinate frame |
| `pX/Y/Z, lX/Y/Z, wX/Y/Z` (scratch) | **FK Computation** — temporary matrix columns for rotation math |

### SkeletonPose fields

| Field | What concept actually owns it |
|---|---|
| `joints: Array<Vector3>` | **Kinematic State** — the world positions of all 33 joints |
| `rotations: Array<JointRotation>` | **Kinematic State** — the world rotations of all 33 joints |
| `roots: List<SkeletonNode>` | **Skeleton Topology** — the root nodes of the FK tree |
| `isTransformsUpdated: Boolean` | **Kinematic State** — flag indicating whether FK has been computed |
| `maxIkClampAmount: Float` | **Constraint Solving** — the maximum IK clamp amount across all limbs |
| `rootTranslationDelta: Float` | **Constraint Solving** — how far the solver displaced the root |
| `rootRotationDelta: Float` | **Constraint Solving** — how far the solver rotated the root |
| `boneLengthsVerified: Boolean` | **Constraint Solving** — whether all solved limbs preserved bone lengths |
| `contacts: MutableList<ContactSpec>` | **Intent** — the fixed support contacts declared by the pose |
| `jointIntents: MutableList<RelativeArticulation>` | **Intent** — per-joint rotation declarations |
| `spineIntent: SpineCurve` | **Intent** — the declarative spine curve |
| `limbTargets: MutableList<WorldTarget>` | **Intent** — the IK targets declared by the pose |
| `contactPrecedence: MutableList<String>` | **Intent** — the contact conflict resolution order |
| `postureIntent: PostureIntent` | **Intent** — the coarse posture kind |
| `extremityOverrides: MutableSet<Extremity>` | **Intent** — which extremities are manually authored |
| `extremityArticulations: MutableMap<Extremity, JointRotation>` | **Intent** — the wrist/ankle rotation declarations |
| `headings: MutableMap<Extremity, Vector3>` | **Intent** — the root-relative heading directions |
| `headTarget: HeadTarget?` | **Intent** — the gaze target declaration |
| `environment: EnvironmentDefinition` | **Intent** — the environment the pose rests in |
| `supportedPoints: MutableSet<SupportPoint>` | **Intent** — which body points rest on what |
| `straightIntentDropped: Boolean` | **Constraint Solving** — whether a straight-limb intent was not honored |
| `hipRomStamps: MutableMap<Joint, HipRomStamp>` | **Finalization** — the engine-computed hip ROM decomposition |
| `bilateralSymmetryDelta: Float` | **Finalization** — the engine-computed bilateral symmetry deviation |
| `bilateralOppositeBend: Boolean` | **Finalization** — the engine-computed opposite-bend flag |

---

## Part 6 — Minimal Object Set

Based on the semantic analysis above, the smallest possible set of runtime concepts capable of expressing the entire skeleton model without ambiguity is:

### 1. SkeletonTopology

**Why it exists:** The skeleton has a fixed tree structure — which segments connect to which articulations, which attachments hang off which joints, and the parent-child relationships that define the kinematic chain. This structure is permanent for a given skeleton definition and does not change between frames or poses.

**What it owns:** The node-to-node parent-child relationships, the mapping from `Joint` enum values to biomechanical roles (segment, articulation, attachment, helper), the IK chain definitions (which joints form which limb chains), and the bone-length pairs for validation.

**Currently encoded in:** `SkeletonFactory` (hardcoded tree building), `SkeletonNode` parent/children links, `ConstraintSolver.chainForEnd()`, `SkeletonEngine.bones`, `ExerciseValidator.validateBoneLengths()`.

### 2. Segment

**Why it exists:** A segment is a rigid body in the biomechanical chain. It has a length and connects two articulations (or an articulation and an attachment). The segment's pose (position + orientation) is the result of FK computation.

**What it owns:** Its length (from `SkeletonDefinition`), its start and end articulations, and its current pose (derived from the parent articulation's world transform and the local bone offset).

**Currently encoded in:** `SkeletonNode.localPosition` (bone offset), `SkeletonDefinition` bone lengths, `ExerciseValidator.validateBoneLengths()` pairs.

### 3. Articulation

**Why it exists:** An articulation is a joint with independent rotational degrees of freedom. It has a joint type (ball-and-socket, hinge), angular limits, and a current rotation angle. The solver operates on articulations (IK solves target articulations), and the validator checks articulation limits.

**What it owns:** Its rotation angle (`localRotation`), its angular limits (from `SkeletonDefinition`), and its parent segment reference.

**Currently encoded in:** `SkeletonNode.localRotation`, `Joint` enum entries classified as articulations, `SkeletonDefinition.armAngularLimits`/`legAngularLimits`/`hipRomLimits`, `ExerciseValidator.validateAngularJointLimits()`.

### 4. Attachment

**Why it exists:** An attachment is a fixed point on a segment used for environment interaction or as a rendering endpoint. It has no independent DOF — its position is derived from the parent segment's pose.

**What it owns:** Its parent segment reference, its local offset from the parent articulation, and its attachment type (heel, toe, palm, fingertip).

**Currently encoded in:** `Joint` enum entries classified as attachments (`HEAD_POS`, `HEEL_F`, `PALM_A`, etc.), `SkeletonNode` leaf nodes, `SupportPoint` enum values.

### 5. CoordinateFrame

**Why it exists:** A coordinate frame is the position and orientation of a segment or articulation in 3D space. It is the fundamental unit of kinematic state. Every node in the skeleton has a coordinate frame, and FK computes these frames from the root down.

**What it owns:** A position (`Vector3`) and an orientation (`JointRotation`).

**Currently encoded in:** `SkeletonNode.worldPosition` + `SkeletonNode.worldRotation` (per-node), `SkeletonPose.joints` + `SkeletonPose.rotations` (flat arrays).

### 6. PoseIntent

**Why it exists:** The pose author declares what they want — which body parts are in contact, where the limbs should reach, what the posture is, and how the extremities should be oriented. Intent is declarative and does not contain computed results.

**What it owns:** The contact declarations, limb targets, joint rotation intents, spine curve, posture kind, extremity override flags, extremity articulations, gaze target, headings, environment definition, and support points.

**Currently encoded in:** `SkeletonPose` §1.1 section — `contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`.

### 7. PoseState

**Why it exists:** The computed result of satisfying the pose intent — the actual positions and orientations of all joints, plus derived stamps (hip ROM, bilateral symmetry, bone length verification, root displacement).

**What it owns:** The flat joint position and rotation arrays, the FK tree reference, and the derived validation stamps.

**Currently encoded in:** `SkeletonPose` §1.2 section — `joints`, `rotations`, `roots`, `isTransformsUpdated`, `maxIkClampAmount`, `boneLengthsVerified`, `rootTranslationDelta`, `rootRotationDelta`, `straightIntentDropped`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`.

### 8. Contact

**Why it exists:** A contact is a fixed point where a body segment touches the environment. The solver uses contacts to reposition the root and re-bake contact limbs. Contacts are distinct from IK targets because they are always honored (the solver ensures the end-effector lands on the contact).

**What it owns:** The end-effector joint, the target world position, the surface normal, the IK chain context (root joint, parent rotation joint, middle joint, bone lengths, pole, straight flag, constraint).

**Currently encoded in:** `ContactSpec` data class, stored in `SkeletonPose.contacts`.

### 9. IKTarget

**Why it exists:** An IK target is a world-space position that a limb end-effector should reach. Unlike contacts, targets can be unreachable (the solver will clamp them). Targets are distinct from contacts because they do not carry a surface normal or contact constraint.

**What it owns:** The target joint, the world position, the pole direction, the straight flag, and optionally a contact constraint.

**Currently encoded in:** `WorldTarget` data class, stored in `SkeletonPose.limbTargets`.

### 10. OrientationConstraint

**Why it exists:** A limit on how far a joint can rotate. Angular limits prevent hyperextension and enforce human ROM. Constraints are permanent for a given skeleton definition and do not change between frames.

**What it owns:** The minimum and maximum flexion angles, the effective extension ratio, and the per-plane angular limits (flexion, extension, abduction, adduction, internal rotation, external rotation for hips).

**Currently encoded in:** `IKConstraint` (arm and leg constraints), `AngularJointLimits`, `HipRomLimits` in `SkeletonDefinition`.

---

## Summary

The semantic analysis reveals that the current runtime model contains 10 distinct biomechanical concepts, but the implementation compresses them into fewer objects:

| Concept | Currently encoded in | Compression level |
|---|---|---|
| SkeletonTopology | `SkeletonFactory`, `SkeletonNode` tree, `ConstraintSolver.chainForEnd()`, `SkeletonEngine.bones`, `ExerciseValidator` bone pairs | High — distributed across 5+ structures |
| Segment | `SkeletonNode.localPosition` + `SkeletonDefinition` bone lengths | Medium — segment length is in definition, segment pose is in node |
| Articulation | `SkeletonNode.localRotation` + `Joint` enum + `SkeletonDefinition` limits | Medium — rotation is in node, limits are in definition |
| Attachment | `Joint` enum entries (leaf nodes) + `SupportPoint` enum | High — attachments are just enum entries with no dedicated type |
| CoordinateFrame | `SkeletonNode.worldPosition` + `worldRotation` + `SkeletonPose.joints` + `rotations` | Medium — frame is computed, stored in node and flat array |
| PoseIntent | `SkeletonPose` §1.1 section | Low — intent is structurally separated in the flat array |
| PoseState | `SkeletonPose` §1.2 section | Low — state is structurally separated in the flat array |
| Contact | `ContactSpec` data class | Low — contact is a dedicated type |
| IKTarget | `WorldTarget` data class | Low — target is a dedicated type |
| OrientationConstraint | `IKConstraint`, `AngularJointLimits`, `HipRomLimits` in `SkeletonDefinition` | Low — constraints are dedicated types |

The highest compression occurs in `SkeletonNode` (4 concepts merged) and the `Joint` enum (4 categories merged). The lowest compression occurs in the dedicated data classes (`ContactSpec`, `WorldTarget`, `IKConstraint`, etc.) and the intent/state split of `SkeletonPose`.
