# Domain Analysis: What Real Entities Exist in the System

> Pure domain analysis. No code. No implementation. No refactoring.
> Based on the biomechanical meaning of the human skeleton, not on existing class names.

---

## The Real Entities

### 1. Segment

**What it means:** A rigid body in the biomechanical chain. A segment is the physical structure between two joints. The upper arm, forearm, thigh, shin, torso, and skull are all segments. Each segment has a definite length and a definite shape. It is the thing that moves when a joint rotates.

**What it belongs to:** The anatomical body. A segment is a physical object — it has mass, it occupies space, it has a beginning and an end.

**What it does not belong to:** A segment does not have its own rotational freedom. It does not decide how to move. It is moved by the joints at its ends. A segment is not a coordinate system, though it has a position and orientation in space.

**Natural operations:**
- Measure its length (the distance between its two endpoints when joints are at zero)
- Compute its center of mass
- Determine its orientation in space (which direction it points)
- Check whether its length has been preserved (has it been stretched or compressed?)

**How it differs from neighboring entities:**
- A segment differs from an articulation because a segment is a rigid body, while an articulation is a joint that allows rotation. The segment is the thing that moves; the articulation is the hinge that lets it move.
- A segment differs from a coordinate frame because a segment has physical properties (length, mass) while a coordinate frame is purely a mathematical description of position and orientation.
- A segment differs from an attachment because a segment is a volumetric body, while an attachment is a dimensionless point on the segment's surface.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries segment data (`localPosition` as bone offset) alongside articulation data (`localRotation`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `SkeletonDefinition` — carries segment lengths (`torsoLength`, `upperArmLength`, etc.) alongside constraint data and proportion data
- `Bone` — a rendering primitive that represents a segment visually, but also encodes thickness and color (rendering concerns)

---

### 2. Articulation

**What it means:** A joint — a point where two segments meet, allowing relative rotation. An articulation has a specific type (ball-and-socket, hinge, pivot) and a specific range of motion. The shoulder, elbow, hip, knee, and neck are articulations. Each articulation has its own rotational freedom and its own physical limits.

**What it belongs to:** The kinematic chain. An articulation connects two segments and defines how they can move relative to each other.

**What it does not belong to:** An articulation does not own a segment. It does not have length or volume. It is a degree of freedom, not a physical object. An articulation does not define where the segment is in space — it defines how the segment can rotate relative to its parent.

**Natural operations:**
- Rotate within its allowed range (apply a joint angle)
- Check whether a requested rotation exceeds its limits
- Compute the resulting orientation of the child segment after rotation
- Determine its joint type (ball-and-socket, hinge, pivot)

**How it differs from neighboring entities:**
- An articulation differs from a segment because an articulation is a joint (a hinge), while a segment is the rigid body between joints. The articulation is the hinge; the segment is the arm.
- An articulation differs from a coordinate frame because an articulation has physical limits (range of motion), while a coordinate frame has no limits — it simply describes a position and orientation.
- An articulation differs from an attachment because an articulation has rotational DOF, while an attachment is a fixed point with no rotation.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries articulation data (`localRotation`) alongside segment data (`localPosition`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `Joint` enum — uses the same identity for articulations, segments, attachments, and helpers
- `SkeletonPose.rotations` — stores world-space rotations for all joints, including non-articulation entries (segments, attachments)

---

### 3. Attachment

**What it means:** A fixed point on a segment's surface. An attachment is where the segment interacts with the environment or where other structures connect. The palm of the hand, the tip of the finger, the heel of the foot, the top of the head — these are attachments. They have no volume, no rotational freedom, and no independent existence. They are points.

**What it belongs to:** The segment it is attached to. An attachment cannot exist without a segment — it is a point on the surface of a segment.

**What it does not belong to:** An attachment does not belong to the articulation system. It does not rotate. It does not have a joint angle. It does not have a range of motion. It is a geometric point, not a kinematic joint.

**Natural operations:**
- Compute its world position from the parent segment's pose and the attachment's local offset
- Check whether it is in contact with a surface
- Check whether it has moved (is it sliding?)
- Determine which surface it is touching (ground, wall, prop)

**How it differs from neighboring entities:**
- An attachment differs from an articulation because an attachment has no rotational DOF. An articulation can rotate; an attachment is fixed.
- An attachment differs from a segment because an attachment is a dimensionless point, while a segment is a volumetric body with length.
- An attachment differs from an IK effector because an attachment is a passive point on a segment, while an IK effector is an active target that the solver tries to reach.

**Current classes that mix this entity with others:**
- `SkeletonNode` — attachment nodes (HEAD_POS, HEEL_F, PALM_A) are full `SkeletonNode` instances with `localPosition`, `localRotation`, `worldPosition`, `worldRotation`, even though they have no rotation and no independent DOF
- `Joint` enum — attachment entries share the same enum as articulations and segments
- `SkeletonPose.joints` — attachment positions are stored in the same flat array as articulation positions

---

### 4. Coordinate Frame

**What it means:** A position and orientation in 3D space. A coordinate frame describes where something is and which direction it faces. It is a mathematical construct — it has no physical substance, no mass, no length. It is the language through which the skeleton describes its configuration in the world.

**What it belongs to:** The kinematic state of the system. A coordinate frame is the result of FK computation — it is derived from the parent frame and the local transform.

**What it does not belong to:** A coordinate frame does not belong to the anatomical model. It is not a body part. It has no physical meaning — it is a mathematical description of pose. A coordinate frame does not have limits, does not have length, and does not have mass.

**Natural operations:**
- Compose with a parent frame (FK propagation)
- Decompose into position and orientation
- Convert from local space to world space
- Convert from world space to local space
- Interpolate between two frames

**How it differs from neighboring entities:**
- A coordinate frame differs from a segment because a frame is a mathematical description, while a segment is a physical object. A frame has no length; a segment does.
- A coordinate frame differs from an articulation because a frame is the result of rotation, while an articulation is the source of rotation. The articulation produces the frame; the frame does not produce the articulation.
- A coordinate frame differs from an attachment because a frame describes the full pose of a point (position + orientation), while an attachment is just a position (a point with no orientation).

**Current classes that mix this entity with others:**
- `SkeletonNode` — a `SkeletonNode` is simultaneously a coordinate frame (worldPosition, worldRotation), a segment (localPosition), an articulation (localRotation), and an attachment host (children)
- `SkeletonPose.joints` and `SkeletonPose.rotations` — the flat arrays store coordinate frame data (world positions and world rotations) for all joint types, including segments, articulations, and attachments
- `JointRotation` — an axis-angle representation that can describe any rotation, whether it belongs to an articulation or is a computed world orientation

---

### 5. IK Effector

**What it means:** The terminal point of a limb chain that the solver tries to position at a specific world-space location. The hand, the foot, the fingertip — these are IK effectors when they are being positioned by the solver. An IK effector is the goal of an IK solve.

**What it belongs to:** The constraint solving system. An IK effector is a solver concept — it is the target that the IK algorithm tries to reach.

**What it does not belong to:** An IK effector does not belong to the anatomical model. It is not a body part. It is a solver goal. An IK effector does not have a fixed position — it moves as the solver repositions the limb.

**Natural operations:**
- Define a target position in world space
- Determine whether the target is reachable given the limb's bone lengths
- Compute the joint angles that place the effector at the target
- Clamp the target to the reachable workspace if it is unreachable

**How it differs from neighboring entities:**
- An IK effector differs from an attachment because an attachment is a fixed point on a segment (the palm is always at the end of the hand), while an IK effector is a solver goal that can move. The palm is an attachment; the hand's target position is an IK effector.
- An IK effector differs from a contact because a contact is a fixed support point (the hand is on the floor), while an IK effector is a target that may or may not be reachable. A contact is always honored; an IK effector may be clamped.
- An IK effector differs from an articulation because an articulation has a fixed relationship to its parent segment, while an IK effector's position is computed by the solver.

**Current classes that mix this entity with others:**
- `ContactSpec` — carries both the end-effector identity (`endJoint`) and the contact metadata (`targetWorld`, `contact`). The effector and the contact are merged.
- `WorldTarget` — carries the IK target (`joint`, `world`) alongside the contact constraint (`contact` field). The target and the contact are merged.
- `HAND_A`, `HAND_P`, `FINGERTIPS_A`, `FINGERTIPS_P`, `TOE_F`, `TOE_B` — these `Joint` enum entries serve as both attachment markers (fixed points on segments) and IK effectors (solver targets).

---

### 6. Contact Point

**What it means:** A specific point on the body that is in fixed contact with the environment. The foot on the floor, the hand on a bar, the knee on the ground — these are contact points. A contact point is a physical fact: this part of the body is touching this part of the world, and it is not moving relative to it.

**What it belongs to:** The environment interaction system. A contact point is the bridge between the biomechanical model and the physical world.

**What it does not belong to:** A contact point does not belong to the kinematic chain in the same way an articulation does. It is not a degree of freedom. It is a constraint — it removes degrees of freedom by fixing a point in space.

**Natural operations:**
- Register a body point as being in contact with a surface
- Define the surface normal and friction properties
- Determine whether the contact is still valid (has the body point moved off the surface?)
- Resolve conflicts when multiple contacts compete for the same root position

**How it differs from neighboring entities:**
- A contact point differs from an IK effector because a contact is a fixed constraint (the point does not move), while an IK effector is a target (the point moves to reach the target). A contact is always honored; an IK effector may be clamped.
- A contact point differs from an attachment because an attachment is a geometric point on a segment, while a contact point is a physical interaction between a segment and the environment. All contact points are attachments, but not all attachments are contact points.
- A contact point differs from an articulation because a contact removes DOF, while an articulation provides DOF.

**Current classes that mix this entity with others:**
- `ContactSpec` — merges the contact point (endJoint, targetWorld) with the IK chain context (rootJoint, parentRotationJoint, middleJoint, pole, constraint, straight) and the contact metadata (contact field with surface normal)
- `SkeletonPose.contacts` — stores contact specs alongside the pose state, mixing the contact declaration with the kinematic state
- `SupportPoint` enum — maps contact points to body joints, but the mapping is hardcoded rather than derived from the biomechanical model

---

### 7. Landmark

**What it means:** A recognizable, named point on the body used for reference, tracking, or validation. Landmarks are not necessarily articulations — they are points of interest. The head position (for viewport validation), the wrist (for hand sliding detection), the knee (for bilateral symmetry) — these are landmarks.

**What it belongs to:** The validation and tracking system. Landmarks are used to check whether the skeleton is in a valid configuration and to detect anomalies.

**What it does not belong to:** A landmark does not have its own DOF. It does not drive the kinematics. It is a reference point, not a driver of motion.

**Natural operations:**
- Project to screen space for viewport validation
- Compare between frames for motion continuity
- Compare left vs. right for bilateral symmetry
- Check whether the landmark is in a valid region (e.g., head inside viewport, feet above ground)

**How it differs from neighboring entities:**
- A landmark differs from an articulation because a landmark has no rotational DOF. It is a reference point, not a joint.
- A landmark differs from an attachment because an attachment is a structural point on a segment (where something connects), while a landmark is a reference point used for validation or tracking. An attachment can be a landmark, but a landmark is not necessarily an attachment.
- A landmark differs from a coordinate frame because a landmark is a single point of interest, while a coordinate frame describes a full pose (position + orientation).

**Current classes that mix this entity with others:**
- `HEAD_POS` — serves as both an attachment (the head's position on the neck chain) and a landmark (used for viewport validation)
- `HAND_A`, `HAND_P`, `WRIST_A`, `WRIST_P` — serve as both attachments (hand/wrist endpoints) and landmarks (used for hand sliding detection)
- `Joint` enum — landmark entries share the same enum as articulations and segments

---

### 8. Pose Intent

**What it means:** What the pose author wants the body to do. Intent is declarative — it describes desired outcomes without specifying how to achieve them. The pose author says "the right hand should be on the floor" (contact intent), "the head should look at this point" (gaze intent), "the spine should curve this way" (spine intent), "the right foot should be planted" (support intent). Intent is the input to the engine; the engine figures out how to satisfy it.

**What it belongs to:** The pose author. Intent is declared by the exercise definition, not computed by the engine.

**What it does not belong to:** Intent does not belong to the kinematic state. Intent is not the result of computation — it is the input to computation. Intent does not contain positions or rotations; it contains goals and constraints.

**Natural operations:**
- Declare a contact (this body point is on this surface)
- Declare a limb target (this end-effector should reach this world position)
- Declare a posture (the body should be in this general configuration)
- Declare a gaze target (the head should look at this world point)
- Declare a heading (this extremity should face this direction)
- Declare an extremity override (preserve this endpoint's authored geometry)

**How it differs from neighboring entities:**
- Pose intent differs from pose state because intent is what the author wants, while state is what the engine computed. Intent is input; state is output.
- Pose intent differs from the biomechanical model because intent is per-pose and per-frame, while the biomechanical model is permanent and skeleton-wide.
- Pose intent differs from the environment because intent describes what the body wants to do, while the environment describes what the world looks like.

**Current classes that mix this entity with others:**
- `SkeletonPose` — carries both intent (§1.1) and state (§1.2) in one object, making the boundary between input and output convention-based rather than structural
- `SkeletonPose.IntentBuilder` — the sole mutator of intent, but it is an inner class of `SkeletonPose`, coupling intent mutation to the state object
- `PoseMetadata` — carries environment and support definitions alongside camera and timing metadata, mixing environmental intent with production metadata

---

### 9. Pose State

**What it means:** The actual computed configuration of the skeleton at a specific moment. Pose state is the result of satisfying the pose intent through FK, IK, and constraint solving. It contains the position and orientation of every joint, plus derived information (hip ROM stamps, bilateral symmetry, bone length verification).

**What it belongs to:** The engine's computation. Pose state is produced by the pipeline and consumed by the renderer and validator.

**What it does not belong to:** Pose state does not belong to the pose author. The author declares intent; the engine produces state. Pose state does not contain goals or constraints — it contains results.

**Natural operations:**
- Read the position and orientation of any joint
- Read derived stamps (hip ROM, bilateral symmetry, bone length verification)
- Compare between frames for motion continuity
- Project to screen space for rendering
- Validate against biomechanical rules

**How it differs from neighboring entities:**
- Pose state differs from pose intent because state is computed (output), while intent is declared (input). State is the result; intent is the goal.
- Pose state differs from a coordinate frame because state is a collection of all frames, while a coordinate frame is a single position+orientation.
- Pose state differs from the biomechanical model because state is per-frame and per-pose, while the model is permanent and skeleton-wide.

**Current classes that mix this entity with others:**
- `SkeletonPose` — carries both state (§1.2) and intent (§1.1) in one object
- `SkeletonPose.rotations` — stores world-space rotations (state), but the intent carriers (`jointIntents`, `extremityArticulations`) store local-space rotations (intent). The same pose object contains both coordinate systems.

---

### 10. Environment

**What it means:** The physical world that the body exists in. The environment defines what surfaces are available for contact — the ground plane, walls, boxes, steps, benches. It defines where the body can touch and what those surfaces are like.

**What it belongs to:** The physical world. The environment is external to the body — it is the context in which the body moves.

**What it does not belong to:** The environment does not belong to the biomechanical model. The body has a fixed anatomy; the environment changes from pose to pose. The environment does not define the body's structure — it defines the body's context.

**Natural operations:**
- Define ground plane (level, visibility)
- Define props (boxes, steps, benches, walls) with position, size, and type
- Determine which surface a contact point is touching (ground vs. prop)
- Compute support plane normals for contact points
- Determine whether a body point is on a support surface

**How it differs from neighboring entities:**
- The environment differs from pose intent because the environment is the world the body is in, while intent is what the body wants to do. The environment is context; intent is goal.
- The environment differs from the biomechanical model because the environment is external and variable, while the model is internal and fixed.
- The environment differs from a contact point because the environment defines the surfaces, while a contact point is the specific body-surface interaction.

**Current classes that mix this entity with others:**
- `EnvironmentDefinition` — carries both the ground plane and the props list, mixing two different environmental concepts (flat ground vs. 3D props)
- `SkeletonPose.environment` — the environment is stamped onto the pose by the pipeline, mixing environmental context with kinematic state
- `SkeletonPose.supportedPoints` — the set of body points in contact with the environment, mixing the contact declaration with the pose state
- `PoseMetadata` — carries `environment` and `support` alongside camera and timing metadata, mixing environmental context with production metadata

---

### 11. Skeleton Topology

**What it means:** The fixed structural relationships of the skeleton — which segments connect to which articulations, which attachments hang off which joints, and the parent-child relationships that define the kinematic chain. The topology is the skeleton's anatomy — it does not change between poses or frames.

**What it belongs to:** The biomechanical model. Topology is permanent for a given skeleton definition.

**What it does not belong to:** Topology does not belong to the kinematic state. Topology does not change between frames. Topology does not contain positions or rotations — it contains only relationships.

**Natural operations:**
- Traverse the tree (FK computation)
- Look up the parent of any joint
- Look up the children of any joint
- Determine the chain from any joint to the root
- Determine the IK chain for a given end-effector

**How it differs from neighboring entities:**
- Topology differs from the biomechanical model because the model includes measurements (lengths, limits) and constraints, while topology is only the structural relationships.
- Topology differs from kinematic state because topology is fixed, while state changes every frame.
- Topology differs from rendering because topology defines the skeleton's structure, while rendering defines how it is drawn.

**Current classes that mix this entity with others:**
- `SkeletonFactory` — hardcodes the tree topology in `createStandardSkeleton()` and `createPushUpSkeleton()`
- `SkeletonNode` — carries topology information (parent, children) alongside transform data (localPosition, localRotation, worldPosition, worldRotation)
- `SkeletonEngine.bones` — hardcodes the rendering bone list, which duplicates the topology
- `ConstraintSolver.chainForEnd()` — hardcodes the IK chain mapping, which is topology
- `ExerciseValidator.validateBoneLengths()` — hardcodes the bone-length validation pairs, which is topology

---

### 12. Orientation Constraint

**What it means:** A limit on how far a joint can rotate. Every articulation has physical limits — the elbow cannot hyperextend, the knee cannot bend backward, the hip has a limited range of motion in every direction. Orientation constraints enforce these limits.

**What it belongs to:** The biomechanical model. Constraints are permanent properties of the skeleton's anatomy.

**What it does not belong to:** Constraints do not belong to the kinematic state. They do not change between frames. They are not computed — they are defined.

**Natural operations:**
- Check whether a requested rotation is within the allowed range
- Clamp a rotation to the nearest valid value
- Determine the effective reachability of a limb given its constraints

**How it differs from neighboring entities:**
- An orientation constraint differs from an articulation because the articulation is the joint itself (with DOF), while the constraint is the limit on that DOF. The articulation provides the rotation; the constraint restricts it.
- An orientation constraint differs from a contact because a constraint limits rotation, while a contact fixes position. They are different types of constraints.
- An orientation constraint differs from the biomechanical model because the model includes the anatomy (segments, articulations), while constraints are the rules that govern how the anatomy can move.

**Current classes that mix this entity with others:**
- `IKConstraint` — carries both the angular limits and the reachability constraints (effectiveExtensionRatio, minimumFlexionAngle) in one object
- `SkeletonDefinition` — carries constraints alongside measurements (bone lengths, proportions), mixing the rule domain with the measurement domain
- `AngularJointLimits` — shared between arm and leg constraints, but the limits are biomechanically different (arm vs. leg)

---

## Mapping Table: Current Runtime Object → Real Semantic Entity

| Current Runtime Object | Real Semantic Entity | Classification |
|---|---|---|
| `SkeletonNode` | **Coordinate Frame** (primary) + Segment + Articulation + Attachment Host | **Mixture** — carries 4 distinct concepts in one class |
| `SkeletonPose` | **Pose State** (primary) + Pose Intent + Transport Object | **Mixture** — carries state, intent, and transport in one object |
| `Joint` enum | **Semantic Label** for all of: Segment, Articulation, Attachment, Helper | **Mixture** — 4 biomechanical categories in one namespace |
| `Bone` | **Rendering Primitive** (primary) + Structural Definition | **Mixture** — carries visual and structural concerns |
| `SkeletonDefinition` | **Biomechanical Model** (primary) + Constraint Data + Measurement Data | **Mixture** — carries anatomy, constraints, and proportions |
| `SkeletonFactory` | **Topology Builder** | **Proper entity** — builds the fixed tree structure |
| `SkeletonNodes` | **Topology Convenience Container** | **Technical container** — exposes node references by name for authoring |
| `ContactSpec` | **Contact Point** (primary) + IK Effector Context + Solver Configuration | **Mixture** — carries biomechanical contact, IK chain context, and solver parameters |
| `WorldTarget` | **IK Effector** (primary) + Contact Constraint | **Mixture** — carries the IK target and optionally a contact constraint |
| `RelativeArticulation` | **Pose Intent** (articulation declaration) | **Proper entity** — a single intent declaration |
| `SpineCurve` | **Pose Intent** (spine declaration) | **Proper entity** — a single intent declaration |
| `PostureIntent` | **Pose Intent** (posture declaration) | **Proper entity** — a single intent declaration |
| `HeadTarget` | **Pose Intent** (gaze declaration) | **Proper entity** — a single intent declaration |
| `ExtremityOrientationMode` | **Intent Modifier** (ownership flag) | **Proper entity** — modifies how the engine treats an extremity |
| `Extremity` enum | **Semantic Label** for the four extremities | **Proper entity** — identifies the four extremity categories |
| `JointRotation` | **Orientation** (axis-angle representation) | **Proper entity** — a mathematical representation of rotation |
| `Vector3` | **Position** (3D point) | **Proper entity** — a mathematical representation of position |
| `IKConstraint` | **Orientation Constraint** (arm/leg) | **Proper entity** — defines reachability and angular limits |
| `AngularJointLimits` | **Orientation Constraint** (per-plane limits) | **Proper entity** — defines angular limits for a joint type |
| `HipRomLimits` | **Orientation Constraint** (hip-specific ROM) | **Proper entity** — defines hip ROM limits |
| `FootDefinition` | **Segment** (foot anatomy) | **Proper entity** — defines foot bone lengths and ratios |
| `HandDefinition` | **Segment** (hand anatomy) | **Proper entity** — defines hand bone lengths and ratios |
| `SkeletonEngine` | **Rendering Definition** (bone list + style) | **Mixture** — carries rendering bone topology and visual style |
| `SkeletonStyle` | **Rendering Style** | **Proper entity** — visual parameters only |
| `SkeletonProjector` | **Projection Engine** | **Proper entity** — 3D→2D projection |
| `ProjectedSkeleton` | **Screen-Space State** | **Proper entity** — the 2D representation of the skeleton |
| `ExerciseValidator` | **Validation Engine** | **Proper entity** — rule checking |
| `ValidatorConfig` | **Validation Profile** | **Proper entity** — configuration for validation rules |
| `ConstraintSolver` | **Constraint Solver** | **Proper entity** — root repositioning and contact re-baking |
| `SkeletonPoseFinalizer` | **Finalization Engine** | **Proper entity** — FK, reconstruction, derivation, stamps |
| `IkStage` | **IK Solver Stage** | **Proper entity** — limb IK solving (currently gated) |
| `PoseBuilder` / `BasePose` | **Intent Authoring API** | **Proper entity** — the interface for declaring pose intent |
| `SkeletonPipeline` | **Pipeline Orchestrator** | **Proper entity** — drives the ordered stage chain |
| `PoseMetadata` | **Production Metadata** (camera, timing, loop, environment, support) | **Mixture** — carries production metadata and environmental context |
| `EnvironmentDefinition` | **Environment** (ground + props) | **Proper entity** — the physical world context |
| `SupportPoint` enum | **Attachment** (body contact points) | **Proper entity** — identifies which body points can be supports |
| `SupportContact` enum | **Attachment** (which extremities are supported) | **Proper entity** — identifies which extremities are in contact |
| `SupportDefinition` | **Intent** (support configuration) | **Proper entity** — declares which body parts are supported |
| `EnvironmentProp` / `BoxProp` / `StepProp` / `BenchProp` / `WallProp` | **Environment** (prop definitions) | **Proper entity** — environmental objects |
| `Camera` / `CameraDefinition` | **Rendering Configuration** | **Proper entity** — view parameters |
| `ScreenSpaceCompensation` | **Rendering Utility** | **Technical container** — perspective scaling |
| `MotionDrivers` / `MotionCurves` | **Animation Utility** | **Technical container** — motion generation |
| `PivotType` | **Configuration** | **Technical container** — pivot configuration |
| `ExerciseSnapshot` / `ExerciseSnapshotSequence` | **Rendering Output** | **Proper entity** — captured frames |
| `ValidationReport` / `ValidationResult` / `ValidationIssue` / `ValidationSeverity` | **Validation Output** | **Proper entity** — validation results |
| `PipelineResult` / `ValidatedFrame` | **Transport Object** | **Technical container** — pipeline result wrapper |
| `HipRomStamp` | **State Stamp** (engine-produced hip ROM) | **Proper entity** — computed ROM decomposition |
| `SkeletonMath.IKResult` | **Solver Output** | **Technical container** — IK solve result |
| `ContactChain` | **Topology** (IK chain definition) | **Proper entity** — defines the proximal chain for a contact |
| `LocalMatrixScratch` | **Computation Scratch** | **Technical container** — matrix computation buffers |

---

## Summary

The system contains 12 distinct semantic entities:

1. **Segment** — a rigid body in the biomechanical chain
2. **Articulation** — a joint with rotational DOF and limits
3. **Attachment** — a fixed point on a segment
4. **Coordinate Frame** — a position and orientation in 3D space
5. **IK Effector** — the solver's target for a limb end-effector
6. **Contact Point** — a body point in fixed contact with the environment
7. **Landmark** — a reference point used for validation and tracking
8. **Pose Intent** — what the pose author declares
9. **Pose State** — the engine's computed result
10. **Environment** — the physical world the body exists in
11. **Skeleton Topology** — the fixed structural relationships
12. **Orientation Constraint** — limits on joint rotation

Of the 35 current runtime objects, 19 are proper entities (each maps cleanly to one semantic entity), 10 are mixtures (each merges two or more semantic entities), and 6 are technical containers (purely computational, no semantic meaning).

The highest-compression objects are `SkeletonNode` (4 entities merged) and the `Joint` enum (4 categories merged). The lowest-compression objects are the dedicated data classes (`ContactSpec`, `WorldTarget`, `IKConstraint`, etc.) and the intent/state split of `SkeletonPose`.
