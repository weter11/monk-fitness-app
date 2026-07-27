# Domain Analysis: What Real Entities Exist in the System

> Pure domain analysis. No code. No implementation. No refactoring.
> Based on the biomechanical meaning of the human skeleton, not on existing class names.
>
> This is an ontology document — it describes what exists in the domain, not how to implement it.
> One entity may map to multiple classes, and one class may carry multiple entities.
> Mixture is not inherently wrong; the goal is clarity about which aspect of reality each class represents.

---

## The Layered Model

The domain is best understood as six independent layers. Each layer describes a different aspect of the system, and each has its own entities, operations, and concerns. Entities from one layer do not belong to another layer — they may reference each other, but they are not the same thing.

The three levels that were previously mixed together are now separated:

- **What the body is** — the anatomical model (Layer 1)
- **How the body is described mathematically** — the kinematic model (Layer 2)
- **What the body interacts with** — the environment model (Layer 3)
- **How the body is computed** — the solver model (Layer 4)
- **What the pose author declares** — the author model (Layer 5)
- **What the engine produces** — the computed state layer (Layer 6)

---

## Layer 1: Anatomical Model — What the Body Is

The anatomical model describes the human body as a physical structure. It is permanent — it does not change between poses or frames. The anatomical model is the core domain: it is the subject area itself.

### 1.1 Segment

**What it means:** A rigid body in the biomechanical chain. The upper arm, forearm, thigh, shin, torso, and skull are all segments. Each segment has a definite length and a definite shape. It is the thing that moves when a joint rotates.

**What it belongs to:** The anatomical body. A segment is a physical object — it has mass, it occupies space, it has a beginning and an end.

**What it does not belong to:** A segment does not have its own rotational freedom. It does not decide how to move. It is moved by the joints at its ends. A segment is not a coordinate system, though it has a position and orientation in space.

**Natural operations:**
- Define its local coordinate system (the frame in which the segment's geometry is authored)
- Measure its length (the distance between its two endpoints when joints are at zero)
- Compute its center of mass
- Determine its orientation in space (which direction it points)
- Check whether its length has been preserved (has it been stretched or compressed?)

**How it differs from neighboring entities:**
- A segment differs from a joint because a segment is a rigid body, while a joint is a hinge that allows rotation. The segment is the thing that moves; the joint is the hinge that lets it move.
- A segment differs from a transform because a segment has physical properties (length, mass) while a transform is purely a mathematical description of position and orientation.
- A segment differs from an attachment point because a segment is a volumetric body, while an attachment point is a dimensionless point on the segment's surface.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries segment data (`localPosition` as bone offset) alongside joint data (`localRotation`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `SkeletonDefinition` — carries segment lengths (`torsoLength`, `upperArmLength`, etc.) alongside constraint data and proportion data
- `Bone` — a rendering primitive that represents a segment visually, but also encodes thickness and color (rendering concerns)

---

### 1.2 Joint

**What it means:** A point where two segments meet, allowing relative rotation. A joint has a specific type (ball-and-socket, hinge, pivot) and a specific range of motion. The shoulder, elbow, hip, knee, and neck are joints. Each joint has its own rotational freedom and its own physical limits.

**What it belongs to:** The anatomical body. A joint is a physical structure — it is the connection between two segments.

**What it does not belong to:** A joint does not own a segment. It does not have length or volume. It is a degree of freedom, not a physical object. A joint does not define where the segment is in space — it defines how the segment can rotate relative to its parent.

**Natural operations:**
- Rotate within its allowed range (apply a joint angle)
- Check whether a requested rotation exceeds its limits
- Compute the resulting orientation of the child segment after rotation
- Determine its joint type (ball-and-socket, hinge, pivot)

**How it differs from neighboring entities:**
- A joint differs from a segment because a joint is a hinge, while a segment is the rigid body between joints. The joint is the hinge; the segment is the arm.
- A joint differs from a transform because a joint has physical limits (range of motion), while a transform has no limits — it simply describes a position and orientation.
- A joint differs from an attachment point because a joint has rotational DOF, while an attachment point is a fixed point with no rotation.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries joint data (`localRotation`) alongside segment data (`localPosition`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `Joint` enum — uses the same identity for joints, segments, attachments, and helpers
- `SkeletonPose.rotations` — stores world-space rotations for all joints, including non-joint entries (segments, attachments)

---

### 1.3 Attachment Point

**What it means:** A fixed point on a segment's surface where the engineering model connects to something external. The palm, the fingertip, the heel, the top of the head — these are attachment points. They are engineering reference locations, not anatomical landmarks. They have no volume, no rotational freedom, and no independent existence. They are points.

**What it belongs to:** The segment it is attached to. An attachment point cannot exist without a segment — it is a point on the surface of a segment.

**What it does not belong to:** An attachment point does not belong to the articulation system. It does not rotate. It does not have a joint angle. It does not have a range of motion. It is a geometric point, not a kinematic joint.

**Natural operations:**
- Compute its world position from the parent segment's pose and the attachment point's local offset
- Check whether it is in contact with a surface
- Check whether it has moved (is it sliding?)
- Determine which surface it is touching (ground, wall, prop)

**How it differs from neighboring entities:**
- An attachment point differs from a joint because an attachment point has no rotational DOF. A joint can rotate; an attachment point is fixed.
- An attachment point differs from a segment because an attachment point is a dimensionless point, while a segment is a volumetric body with length.
- An attachment point may coincide with a landmark — the palm is both an attachment point (where the hand connects to the wrist chain) and a landmark (used for viewport validation). When they coincide, they are the same point serving two different roles: structural connection and observation reference. An attachment point can be a landmark, but a landmark is not necessarily an attachment point.

**Current classes that mix this entity with others:**
- `SkeletonNode` — attachment nodes (HEAD_POS, HEEL_F, PALM_A) are full `SkeletonNode` instances with `localPosition`, `localRotation`, `worldPosition`, `worldRotation`, even though they have no rotation and no independent DOF
- `Joint` enum — attachment entries share the same enum as joints and segments
- `SkeletonPose.joints` — attachment positions are stored in the same flat array as joint positions

---

### 1.4 Topology

**What it means:** The fixed graph structure of the skeleton — which segments connect to which joints, which attachment points hang off which joints, and the parent-child relationships that define the kinematic chain. Topology is the skeleton's anatomy as a graph; it does not contain measurements, limits, or parameters. The skeleton definition supplies the parameters (lengths, proportions, constraint limits); the topology supplies the connectivity.

**What it belongs to:** The anatomical model. Topology is permanent for a given skeleton definition.

**What it does not belong to:** Topology does not belong to the kinematic state. Topology does not change between frames. Topology does not contain positions or rotations — it contains only relationships. Topology is not the same as the skeleton definition: the definition includes measurements and constraints, while topology is only the structural graph.

**Natural operations:**
- Traverse the tree (FK computation)
- Look up the parent of any joint
- Look up the children of any joint
- Determine the chain from any joint to the root
- Determine the IK chain for a given end-effector

**How it differs from neighboring entities:**
- Topology differs from the skeleton definition because the definition includes measurements (lengths, proportions) and constraints, while topology is only the structural relationships (parent-child, joint-to-segment mapping).
- Topology differs from kinematic state because topology is fixed, while state changes every frame.
- Topology differs from rendering because topology defines the skeleton's structure, while rendering defines how it is drawn.

**Current classes that mix this entity with others:**
- `SkeletonFactory` — hardcodes the tree topology in `createStandardSkeleton()` and `createPushUpSkeleton()`
- `SkeletonNode` — carries topology information (parent, children) alongside transform data (localPosition, localRotation, worldPosition, worldRotation)
- `SkeletonEngine.bones` — hardcodes the rendering bone list, which duplicates the topology
- `ConstraintSolver.chainForEnd()` — hardcodes the IK chain mapping, which is topology
- `ExerciseValidator.validateBoneLengths()` — hardcodes the bone-length validation pairs, which is topology

---

### 1.5 Joint Constraint

**What it means:** A declaration of the limits on how far a joint can rotate. Every joint has physical limits — the elbow cannot hyperextend, the knee cannot bend backward, the hip has a limited range of motion in every direction. A joint constraint is a rule, not an algorithm. It declares what is allowed; it does not enforce it.

**What it belongs to:** The anatomical model. Constraints are permanent properties of the skeleton's anatomy.

**What it does not belong to:** Constraints do not belong to the kinematic state. They do not change between frames. They are not computed — they are defined. A constraint is not a solver; the solver is the algorithm that reads constraints and enforces them. The constraint is the declaration; the solver is the mechanism. A joint constraint is distinct from a solver constraint (which is computational).

**Natural operations (of the constraint declaration):**
- Specify the allowed angular range for each axis of a joint
- Define the effective reachability of a limb given its limits
- State whether a joint can hyperextend or is bounded

**How it differs from neighboring entities:**
- A joint constraint differs from a joint because the joint is the hinge itself (with DOF), while the constraint is the limit on that DOF. The joint provides the rotation; the constraint restricts it.
- A joint constraint differs from a contact because a constraint limits rotation, while a contact fixes position. They are different types of constraints.
- A joint constraint differs from the anatomical model because the model includes the anatomy (segments, joints), while constraints are the rules that govern how the anatomy can move.
- A joint constraint differs from a solver constraint because a joint constraint is anatomical (it describes the body's physical limits), while a solver constraint is computational (it describes what the solver must enforce).

**Current classes that mix this entity with others:**
- `IKConstraint` — carries both the angular limits and the reachability constraints (effectiveExtensionRatio, minimumFlexionAngle) in one object
- `SkeletonDefinition` — carries constraints alongside measurements (bone lengths, proportions), mixing the rule domain with the measurement domain
- `AngularJointLimits` — shared between arm and leg constraints, but the limits are biomechanically different (arm vs. leg)

---

## Layer 2: Kinematic Model — How the Body Is Described Mathematically

The kinematic model provides the mathematical language for describing the body's position and orientation in space. It is derived from the anatomical model but is not part of it. A coordinate frame is not a body part; it is a mathematical description of a body part.

### 2.1 Local Transform

**What it means:** The position and orientation of a segment relative to its parent joint. It describes the segment's geometry in the parent's coordinate space. A local transform is the input to FK computation.

**What it belongs to:** The kinematic model. A local transform is a mathematical description of the body's configuration.

**What it does not belong to:** A local transform does not belong to the anatomical model. It is a mathematical description, not a physical object. A local transform does not have mass, length, or volume.

**Natural operations:**
- Compose with a parent transform (FK propagation)
- Decompose into position and orientation
- Convert from local space to parent space
- Interpolate between two local transforms

**How it differs from neighboring entities:**
- A local transform differs from a world transform because a local transform is relative to the parent, while a world transform is relative to the root.
- A local transform differs from a coordinate frame because a local transform is a specific value (a translation + rotation), while a coordinate frame is the framework within which transforms are expressed.
- A local transform differs from a segment because a segment is a physical object, while a local transform is a mathematical description of the segment's position relative to its parent.

**Current classes that mix this entity with others:**
- `SkeletonNode` — a `SkeletonNode` carries local transform data (`localPosition`, `localRotation`) alongside world transform data and segment metadata
- `JointRotation` — an axis-angle representation that can describe any rotation, whether it belongs to a local transform or is a computed world orientation

---

### 2.2 World Transform

**What it means:** The position and orientation of a segment in world space. It is the result of composing all local transforms from the root down the chain. A world transform is the output of FK computation.

**What it belongs to:** The kinematic model. A world transform is a mathematical description of the body's configuration in the world.

**What it does not belong to:** A world transform does not belong to the anatomical model. It is a mathematical description, not a physical object.

**Natural operations:**
- Compose from parent world transform and local transform
- Decompose into position and orientation
- Convert from world to local space
- Interpolate between two world transforms
- Project to screen space for rendering

**How it differs from neighboring entities:**
- A world transform differs from a local transform because a world transform is absolute, while a local transform is relative to the parent.
- A world transform differs from a coordinate frame because a world transform is a specific computed result, while a coordinate frame is the framework.
- A world transform differs from a final pose because a world transform describes a single segment, while a final pose is the collection of all segments' transforms.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries world transform data (`worldPosition`, `worldRotation`) alongside local transform data and segment metadata
- `SkeletonPose.joints` — stores world-space positions and rotations for all joints, mixing world transforms with other data

---

### 2.3 Coordinate Frame

**What it means:** A mathematical frame of reference consisting of an origin point and an orthogonal basis (three mutually perpendicular axes) that defines a position and orientation in 3D space. It is a purely mathematical construct — it has no physical substance, no mass, no length, and no anatomical meaning. It is the language through which the kinematic model describes the body's configuration in the world.

**What it belongs to:** The kinematic model. A coordinate frame is the framework within which transforms are expressed.

**What it does not belong to:** A coordinate frame does not belong to the anatomical model. It is not a body part. It has no physical meaning — it is a mathematical description of pose. A coordinate frame does not have limits, does not have length, and does not have mass. It is not an anatomical structure; it is a coordinate system.

**Natural operations:**
- Compose with a parent frame (FK propagation)
- Decompose into position and orientation
- Convert from local space to world space
- Convert from world space to local space
- Interpolate between two frames

**How it differs from neighboring entities:**
- A coordinate frame differs from a local transform because a frame is the framework, while a local transform is a specific value within that framework.
- A coordinate frame differs from a world transform because a frame is the framework, while a world transform is a specific computed result.
- A coordinate frame differs from an attachment point because a frame describes a full pose (position + orientation), while an attachment point is just a position (a point with no orientation).

**Current classes that mix this entity with others:**
- `SkeletonNode` — a `SkeletonNode` is simultaneously a coordinate frame (worldPosition, worldRotation), a segment (localPosition), a joint (localRotation), and an attachment host (children)
- `SkeletonPose.joints` and `SkeletonPose.rotations` — the flat arrays store coordinate frame data (world positions and world rotations) for all joint types, including segments, joints, and attachment points
- `JointRotation` — an axis-angle representation that can describe any rotation, whether it belongs to a joint or is a computed world orientation

---

## Layer 3: Environment Model — What the Body Interacts With

The environment model describes the physical world that the body exists in and interacts with. It is external to the body — it is the context in which the body moves. Contact is not part of the skeletal model; it is a relationship between the body and the environment.

### 3.1 Surface

**What it means:** A physical surface in the world that the body can interact with. The ground plane, walls, boxes, steps, benches — these are surfaces. Each surface has a position, size, and type.

**What it belongs to:** The environment. Surfaces are external to the body.

**What it does not belong to:** A surface does not belong to the anatomical model. The body has a fixed anatomy; surfaces change from pose to pose. A surface is not a body part.

**Natural operations:**
- Define a surface with position, size, and type
- Compute the surface normal at a point
- Determine whether a body point is touching a surface

**How it differs from neighboring entities:**
- A surface differs from a contact because a surface is the world's geometry, while a contact is the body-surface relationship. A surface exists whether or not the body is touching it; a contact only exists when the body is touching the surface.
- A surface differs from the environment because the environment is the broader context (all physical context), while a surface is a specific geometric object within that context.

**Current classes that mix this entity with others:**
- `EnvironmentDefinition` — carries both the ground plane and the props list, mixing two different environmental concepts (flat ground vs. 3D props)
- `EnvironmentProp` / `BoxProp` / `StepProp` / `BenchProp` / `WallProp` — prop definitions that are surfaces

---

### 3.2 Support

**What it means:** The set of body points that are in contact with surfaces. A support describes which parts of the body are resting on which surfaces. Support is a state of the body-environment relationship.

**What it belongs to:** The environment model. Support is the bridge between the body and the world.

**What it does not belong to:** Support does not belong to the anatomical model. The body's anatomy does not change; support describes the current interaction state. Support does not belong to the kinematic state — it is an environmental relationship.

**Natural operations:**
- Register a body point as being supported by a surface
- Determine whether a body point is still supported (has it lifted off?)
- Resolve conflicts when multiple supports compete for the same root position
- Compute the support plane normal for a supported body point

**How it differs from neighboring entities:**
- Support differs from a contact because support is the set of supported body points, while a contact is a specific body-surface relationship. Support is the aggregate; contact is the individual relationship.
- Support differs from an intent because support is a state (what is happening now), while intent is a goal (what the author wants to happen).

**Current classes that mix this entity with others:**
- `SkeletonPose.supportedPoints` — the set of body points in contact with the environment, mixing the contact declaration with the pose state
- `PoseMetadata` — carries `environment` and `support` alongside camera and timing metadata, mixing environmental context with production metadata
- `SupportPoint` enum — maps support points to body joints, but the mapping is hardcoded rather than derived from the biomechanical model
- `SupportContact` enum — identifies which extremities are in contact
- `SupportDefinition` — declares which body parts are supported

---

### 3.3 Contact

**What it means:** A specific relationship between a body point and a surface. The foot on the floor, the hand on a bar, the knee on the ground — these describe a contact relationship. A contact is a physical fact: this part of the body is touching this part of the world, and it is not moving relative to it. Contact is not part of the skeletal model; it is a relationship between the body and the environment.

**What it belongs to:** The environment model. Contact is the bridge between the biomechanical model and the physical world.

**What it does not belong to:** Contact does not belong to the anatomical model. It is not a body part. It is a relationship between the body and the environment. Contact does not belong to the kinematic chain in the same way a joint does — it is not a degree of freedom.

**Natural operations:**
- Register a body point as being in contact with a surface
- Define the surface normal and friction properties
- Determine whether the contact is still valid (has the body point moved off the surface?)
- Resolve conflicts when multiple contacts compete for the same root position

**How it differs from neighboring entities:**
- A contact differs from an effector because a contact is a fixed constraint (the point does not move), while an effector is a solver goal (the point moves to reach the target). A contact is always honored; an effector may be clamped.
- A contact differs from an attachment point because an attachment point is a geometric point on a segment, while a contact is a physical interaction between a segment and the environment. All contacts involve attachment points, but not all attachment points are in contact.
- A contact differs from a joint because a contact removes DOF, while a joint provides DOF.
- A contact differs from a surface because a surface is the world's geometry, while a contact is the specific body-surface relationship.

**Current classes that mix this entity with others:**
- `ContactSpec` — merges the contact (endJoint, targetWorld) with the IK chain context (rootJoint, parentRotationJoint, middleJoint, pole, constraint, straight) and the contact metadata (contact field with surface normal)
- `SkeletonPose.contacts` — stores contact specs alongside the pose state, mixing the contact declaration with the kinematic state
- `SupportPoint` enum — maps contact points to body joints, but the mapping is hardcoded rather than derived from the biomechanical model

---

## Layer 4: Solver Model — How the Body Is Computed

The solver model describes the computational mechanisms that determine how the body moves to satisfy goals. It is an algorithm layer, not an anatomical layer. An effector is not a body part; it is a solver goal.

### 4.1 IK Chain

**What it means:** The chain of joints that the IK solver operates on to position an end-effector at a target location. The chain is defined by the anatomical topology and the effector's position in the chain. An IK chain is a computational abstraction over the anatomy.

**What it belongs to:** The solver model. An IK chain is a solver construct derived from the anatomical topology.

**What it does not belong to:** An IK chain does not belong to the anatomical model. It is not a body part. It is a solver abstraction over the anatomy. An IK chain does not exist in the physical body — it exists only in the solver's computation.

**Natural operations:**
- Determine the chain from the root to the effector
- Look up the joints in the chain
- Determine the chain length and reachability
- Traverse the chain for FK computation

**How it differs from neighboring entities:**
- An IK chain differs from topology because topology is the full anatomical graph, while an IK chain is a subset of joints for a specific solver goal.
- An IK chain differs from a joint because a joint is an anatomical entity, while an IK chain is a computational grouping of joints.
- An IK chain differs from a local transform because a chain is a structural grouping, while a transform is a mathematical value.

**Current classes that mix this entity with others:**
- `ContactSpec` — carries the IK chain context (rootJoint, parentRotationJoint, middleJoint, pole) alongside the contact metadata
- `ConstraintSolver.chainForEnd()` — hardcodes the IK chain mapping
- `SkeletonNodes` — provides named access to joints that form IK chains

---

### 4.2 Effector

**What it means:** A target point that the IK solver tries to position at a specific world-space location. The hand, the foot, the fingertip — these are effectors when they are being positioned by the solver. An effector is not a body part; it is a solver goal.

**What it belongs to:** The solver model. An effector is a solver concept — it is the target that the IK algorithm tries to reach.

**What it does not belong to:** An effector does not belong to the anatomical model. It is not a body part. It is a solver goal. An effector does not have a fixed position — it moves as the solver repositions the limb. An effector does not belong to the environment model — it is not a physical interaction; it is a computational target.

**Natural operations:**
- Define a target position in world space
- Determine whether the target is reachable given the limb's bone lengths
- Compute the joint angles that place the effector at the target
- Clamp the target to the reachable workspace if it is unreachable

**How it differs from neighboring entities:**
- An effector differs from an attachment point because an attachment point is a fixed point on a segment (the palm is always at the end of the hand), while an effector is a solver goal that can move. The palm is an attachment point; the hand's target position is an effector.
- An effector differs from a contact because a contact is a fixed support point (the hand is on the floor), while an effector is a target that may or may not be reachable. A contact is always honored; an effector may be clamped.
- An effector differs from a joint because a joint has a fixed relationship to its parent segment, while an effector's position is computed by the solver.

**Current classes that mix this entity with others:**
- `ContactSpec` — carries both the end-effector identity (`endJoint`) and the contact metadata (`targetWorld`, `contact`). The effector and the contact are merged.
- `WorldTarget` — carries the IK target (`joint`, `world`) alongside the contact constraint (`contact` field). The target and the contact are merged.
- `HAND_A`, `HAND_P`, `FINGERTIPS_A`, `FINGERTIPS_P`, `TOE_F`, `TOE_B` — these `Joint` enum entries serve as both attachment markers (fixed points on segments) and effectors (solver targets).

---

### 4.3 Solver Constraint

**What it means:** A rule that the solver must respect during computation. This includes joint limits, reachability limits, and contact preservation rules. A solver constraint is distinct from a joint constraint (which is anatomical); a solver constraint is computational.

**What it belongs to:** The solver model. Solver constraints are the rules that govern how the solver operates.

**What it does not belong to:** Solver constraints do not belong to the anatomical model. They are computational rules, not physical limits. A solver constraint is not a body part.

**Natural operations:**
- Check whether a proposed configuration violates any solver constraint
- Clamp a solution to satisfy constraints
- Determine the effective reachability of a limb given solver constraints

**How it differs from neighboring entities:**
- A solver constraint differs from a joint constraint because a joint constraint is anatomical (it describes the body's physical limits), while a solver constraint is computational (it describes what the solver must enforce).
- A solver constraint differs from a contact because a contact is a physical relationship, while a solver constraint is a computational rule.
- A solver constraint differs from an effector because an effector is a target, while a solver constraint is a rule that limits how the solver can reach that target.

**Current classes that mix this entity with others:**
- `IKConstraint` — carries both the angular limits and the reachability constraints (effectiveExtensionRatio, minimumFlexionAngle) in one object
- `ContactSpec` — carries the solver constraint parameters (constraint, straight) alongside the contact metadata
- `WorldTarget` — carries the solver constraint (`constraint` field) alongside the IK target
- `SkeletonDefinition` — carries constraints alongside measurements (bone lengths, proportions), mixing the rule domain with the measurement domain

---

## Layer 5: Author Model — What the Pose Author Declares

The author model describes the intent and goals declared by the pose author. It is the input to the engine, not the body itself. Intent is not a body part; it is a declaration about what the body should do.

### 5.1 Intent

**What it means:** What the pose author wants the body to do. Intent is declarative — it describes desired outcomes without specifying how to achieve them. The pose author says "the right hand should be on the floor" (contact intent), "the head should look at this point" (gaze intent), "the spine should curve this way" (spine intent), "the right foot should be planted" (support intent). Intent is the input to the engine; the engine figures out how to satisfy it.

**What it belongs to:** The pose author. Intent is declared by the exercise definition, not computed by the engine.

**What it does not belong to:** Intent does not belong to the kinematic state. Intent is not the result of computation — it is the input to computation. Intent does not contain positions or rotations; it contains goals and constraints. Intent does not belong to the anatomical model — the body's anatomy does not change based on what the author wants.

**Natural operations:**
- Declare a contact (this body point is on this surface)
- Declare a limb target (this end-effector should reach this world position)
- Declare a posture (the body should be in this general configuration)
- Declare a gaze target (the head should look at this world point)
- Declare a heading (this extremity should face this direction)
- Declare an extremity override (preserve this endpoint's authored geometry)

**How it differs from neighboring entities:**
- Intent differs from final pose because intent is what the author wants, while the final pose is what the engine computed. Intent is input; the final pose is output.
- Intent differs from the anatomical model because intent is per-pose and per-frame, while the anatomical model is permanent and skeleton-wide.
- Intent differs from the environment because intent describes what the body wants to do, while the environment describes what the world looks like.
- Intent differs from a solver constraint because intent is a goal, while a solver constraint is a rule that limits how the goal can be achieved.

**Current classes that mix this entity with others:**
- `SkeletonPose` — carries both intent (§5.1) and state (§6.1) in one object, making the boundary between input and output convention-based rather than structural
- `SkeletonPose.IntentBuilder` — the sole mutator of intent, but it is an inner class of `SkeletonPose`, coupling intent mutation to the state object
- `PoseMetadata` — carries environment and support definitions alongside camera and timing metadata, mixing environmental intent with production metadata

---

### 5.2 Pose Goal

**What it means:** A specific target configuration that the author wants the body to achieve. A pose goal is more specific than intent — it defines a desired end state with concrete positions and orientations. A pose goal is the refined, concrete form of an intent.

**What it belongs to:** The author model. A pose goal is declared by the pose author.

**What it does not belong to:** A pose goal does not belong to the kinematic state. It is a target, not a result. A pose goal does not belong to the anatomical model — it is not a body part.

**Natural operations:**
- Specify target positions for effectors
- Specify target orientations for joints
- Define the tolerance for each target
- Validate that a pose goal is achievable given the anatomical model

**How it differs from neighboring entities:**
- A pose goal differs from intent because intent is declarative and abstract, while a pose goal is specific and concrete. Intent says "the hand should be on the floor"; a pose goal says "the hand should be at position (x, y, z) with orientation (pitch, yaw, roll)."
- A pose goal differs from the final pose because a pose goal is the desired state, while the final pose is the computed state. The final pose may not exactly match the pose goal if constraints prevent it.

**Current classes that mix this entity with others:**
- `PoseBuilder` / `BasePose` — the interface for declaring pose goals and intent
- `RelativeArticulation` — a pose goal for a specific joint
- `SpineCurve` — a pose goal for spine curvature
- `PostureIntent` — a pose goal for overall body posture
- `HeadTarget` — a pose goal for head orientation

---

## Layer 6: Computed State — What the Engine Produces

The computed state layer describes the results produced by the engine after processing intent through the solver. It is the output of computation, not the body itself. A final pose is not a domain object; it is a computational state.

### 6.1 Final Pose

**What it means:** The actual computed configuration of the skeleton at a specific moment. The final pose is the result of satisfying the author's intent through FK, IK, and constraint solving. It contains the position and orientation of every joint, plus derived information.

**What it belongs to:** The engine's computation. The final pose is produced by the pipeline and consumed by the renderer and validator.

**What it does not belong to:** The final pose does not belong to the pose author. The author declares intent; the engine produces the final pose. The final pose does not contain goals or constraints — it contains results. The final pose does not belong to the anatomical model — it is a per-frame computational result, while the anatomical model is permanent.

**Natural operations:**
- Read the position and orientation of any joint
- Read derived stamps (hip ROM, bilateral symmetry, bone length verification)
- Compare between frames for motion continuity
- Project to screen space for rendering
- Validate against biomechanical rules

**How it differs from neighboring entities:**
- The final pose differs from intent because the final pose is computed (output), while intent is declared (input). The final pose is the result; intent is the goal.
- The final pose differs from a coordinate frame because the final pose is a collection of all frames, while a coordinate frame is a single position+orientation.
- The final pose differs from the anatomical model because the final pose is per-frame and per-pose, while the model is permanent and skeleton-wide.

**Current classes that mix this entity with others:**
- `SkeletonPose` — carries both the final pose (§6.1) and intent (§5.1) in one object
- `SkeletonPose.rotations` — stores world-space rotations (the final pose), but the intent carriers (`jointIntents`, `extremityArticulations`) store local-space rotations (intent). The same pose object contains both coordinate systems.

---

### 6.2 Derived Data

**What it means:** Computed information derived from the final pose, such as hip ROM stamps, bilateral symmetry checks, and bone length verification. Derived data is produced by the engine from the final pose. It is diagnostic — it tells you something about the final pose, but it is not the final pose itself.

**What it belongs to:** The computed state layer. Derived data is a result of computation, not an input.

**What it does not belong to:** Derived data does not belong to the anatomical model. It is computed, not defined. Derived data does not belong to the kinematic model — it is not a transform; it is a derived quantity.

**Natural operations:**
- Compute hip ROM from joint angles
- Compare left vs. right for bilateral symmetry
- Verify bone lengths against the anatomical model
- Generate stamps for downstream consumers

**How it differs from neighboring entities:**
- Derived data differs from the final pose because derived data is computed from the final pose, while the final pose is the primary result. Derived data is diagnostic; the final pose is the primary output.
- Derived data differs from intent because derived data is computed, while intent is declared.

**Current classes that mix this entity with others:**
- `HipRomStamp` — computed ROM decomposition, a derived data artifact
- `SkeletonPoseFinalizer` — produces derived data as part of the finalization stage

---

### 6.3 Validation Result

**What it means:** The outcome of checking whether the final pose is valid against biomechanical rules and viewport constraints. A validation result indicates whether the final pose passes or fails, and if it fails, what the issues are.

**What it belongs to:** The computed state layer. Validation is a computation that produces a result.

**What it does not belong to:** A validation result does not belong to the anatomical model. It is a diagnostic output, not a body part. A validation result does not belong to the author model — it is not a declaration; it is a verdict.

**Natural operations:**
- Check whether the final pose is valid
- Identify specific validation issues (out-of-range joint, missing contact, etc.)
- Assign severity levels (info, warning, error)
- Generate a validation report

**How it differs from neighboring entities:**
- A validation result differs from the final pose because a validation result is a diagnostic output, while the final pose is the primary result. The final pose is what the engine computed; the validation result is whether that computation is acceptable.
- A validation result differs from intent because a validation result evaluates whether the final pose satisfies the intent, while intent is the goal itself.

**Current classes that mix this entity with others:**
- `ExerciseValidator` — the validation engine that produces validation results
- `ValidatorConfig` — configuration for validation rules
- `ValidationReport` / `ValidationResult` / `ValidationIssue` / `ValidationSeverity` — validation output entities

---

## Mapping Table: Current Runtime Object → Layer + Entity

| Current Runtime Object | Layer | Entity | Classification |
|---|---|---|---|
| `SkeletonNode` | Kinematic (Layer 2) | Local Transform + World Transform + Coordinate Frame + Attachment Point Host | **Mixture** — carries 4 distinct concepts in one class |
| `SkeletonPose` | Computed State (Layer 6) | Final Pose (primary) + Intent + Transport Object | **Mixture** — carries state, intent, and transport in one object |
| `Joint` enum | Anatomical (Layer 1) | Semantic Label for all of: Joint, Segment, Attachment Point, Helper | **Mixture** — 4 biomechanical categories in one namespace |
| `Bone` | Anatomical (Layer 1) | Segment (visual representation) | **Mixture** — carries visual and structural concerns |
| `SkeletonDefinition` | Anatomical (Layer 1) | Segment + Joint Constraint + Topology parameters | **Mixture** — carries anatomy, constraints, and proportions |
| `SkeletonFactory` | Anatomical (Layer 1) | Topology Builder | **Proper entity** — builds the fixed tree structure |
| `SkeletonNodes` | Anatomical (Layer 1) | Topology Convenience Container | **Technical container** — exposes node references by name for authoring |
| `ContactSpec` | Solver (Layer 4) + Environment (Layer 3) | Effector Context + Contact + Solver Constraint | **Mixture** — carries biomechanical contact, IK chain context, and solver parameters |
| `WorldTarget` | Solver (Layer 4) + Environment (Layer 3) | Effector + Contact Constraint | **Mixture** — carries the IK target and optionally a contact constraint |
| `RelativeArticulation` | Author (Layer 5) | Pose Goal (articulation declaration) | **Proper entity** — a single goal declaration |
| `SpineCurve` | Author (Layer 5) | Pose Goal (spine declaration) | **Proper entity** — a single goal declaration |
| `PostureIntent` | Author (Layer 5) | Intent (posture declaration) | **Proper entity** — a single intent declaration |
| `HeadTarget` | Author (Layer 5) | Intent (gaze declaration) | **Proper entity** — a single intent declaration |
| `ExtremityOrientationMode` | Author (Layer 5) | Intent Modifier (ownership flag) | **Proper entity** — modifies how the engine treats an extremity |
| `Extremity` enum | Author (Layer 5) | Semantic Label for the four extremities | **Proper entity** — identifies the four extremity categories |
| `JointRotation` | Kinematic (Layer 2) | Orientation (axis-angle representation) | **Proper entity** — a mathematical representation of rotation |
| `Vector3` | Kinematic (Layer 2) | Position (3D point) | **Proper entity** — a mathematical representation of position |
| `IKConstraint` | Solver (Layer 4) | Solver Constraint (arm/leg) | **Proper entity** — defines reachability and angular limits |
| `AngularJointLimits` | Anatomical (Layer 1) | Joint Constraint (per-plane limits) | **Proper entity** — defines angular limits for a joint type |
| `HipRomLimits` | Anatomical (Layer 1) | Joint Constraint (hip-specific ROM) | **Proper entity** — defines hip ROM limits |
| `FootDefinition` | Anatomical (Layer 1) | Segment (foot anatomy) | **Proper entity** — defines foot bone lengths and ratios |
| `HandDefinition` | Anatomical (Layer 1) | Segment (hand anatomy) | **Proper entity** — defines hand bone lengths and ratios |
| `SkeletonEngine` | Computed State (Layer 6) | Rendering Definition (bone list + style) | **Mixture** — carries rendering bone topology and visual style |
| `SkeletonStyle` | Computed State (Layer 6) | Rendering Style | **Proper entity** — visual parameters only |
| `SkeletonProjector` | Computed State (Layer 6) | Projection Engine | **Proper entity** — 3D→2D projection |
| `ProjectedSkeleton` | Computed State (Layer 6) | Screen-Space State | **Proper entity** — the 2D representation of the skeleton |
| `ExerciseValidator` | Computed State (Layer 6) | Validation Engine | **Proper entity** — rule checking |
| `ValidatorConfig` | Computed State (Layer 6) | Validation Profile | **Proper entity** — configuration for validation rules |
| `ConstraintSolver` | Solver (Layer 4) | Root Repositioning Engine | **Proper entity** — contact re-baking and root adjustment |
| `SkeletonPoseFinalizer` | Computed State (Layer 6) | Finalization Engine | **Proper entity** — FK, reconstruction, derivation, stamps |
| `IkStage` | Solver (Layer 4) | IK Solver Stage | **Proper entity** — limb IK solving (currently gated) |
| `PoseBuilder` / `BasePose` | Author (Layer 5) | Intent Authoring API + Pose Goal API | **Proper entity** — the interface for declaring pose intent and goals |
| `SkeletonPipeline` | Computed State (Layer 6) | Pipeline Orchestrator | **Proper entity** — drives the ordered stage chain |
| `PoseMetadata` | Author (Layer 5) + Environment (Layer 3) | Production Metadata (camera, timing, loop, environment, support) | **Mixture** — carries production metadata and environmental context |
| `EnvironmentDefinition` | Environment (Layer 3) | Surface Collection (ground + props) | **Proper entity** — the physical world context |
| `SupportPoint` enum | Environment (Layer 3) | Attachment Point (body contact points) | **Proper entity** — identifies which body points can be supports |
| `SupportContact` enum | Environment (Layer 3) | Contact (which extremities are supported) | **Proper entity** — identifies which extremities are in contact |
| `SupportDefinition` | Author (Layer 5) | Intent (support configuration) | **Proper entity** — declares which body parts are supported |
| `EnvironmentProp` / `BoxProp` / `StepProp` / `BenchProp` / `WallProp` | Environment (Layer 3) | Surface (prop definitions) | **Proper entity** — environmental objects |
| `Camera` / `CameraDefinition` | Computed State (Layer 6) | Rendering Configuration | **Proper entity** — view parameters |
| `ScreenSpaceCompensation` | Computed State (Layer 6) | Rendering Utility | **Technical container** — perspective scaling |
| `MotionDrivers` / `MotionCurves` | Computed State (Layer 6) | Animation Utility | **Technical container** — motion generation |
| `PivotType` | Computed State (Layer 6) | Configuration | **Technical container** — pivot configuration |
| `ExerciseSnapshot` / `ExerciseSnapshotSequence` | Computed State (Layer 6) | Rendering Output | **Proper entity** — captured frames |
| `ValidationReport` / `ValidationResult` / `ValidationIssue` / `ValidationSeverity` | Computed State (Layer 6) | Validation Result | **Proper entity** — validation results |
| `PipelineResult` / `ValidatedFrame` | Computed State (Layer 6) | Transport Object | **Technical container** — pipeline result wrapper |
| `HipRomStamp` | Computed State (Layer 6) | Derived Data | **Proper entity** — computed ROM decomposition |
| `SkeletonMath.IKResult` | Solver (Layer 4) | Solver Output | **Technical container** — IK solve result |
| `ContactChain` | Anatomical (Layer 1) | IK Chain (topology) | **Proper entity** — defines the proximal chain for a contact |
| `LocalMatrixScratch` | Kinematic (Layer 2) | Computation Scratch | **Technical container** — matrix computation buffers |

---

## Summary

The domain is organized into six independent layers, each with its own entities and concerns:

**Layer 1 — Anatomical Model (5 entities):**
1. **Segment** — a rigid body in the biomechanical chain
2. **Joint** — a point where two segments meet, allowing relative rotation
3. **Attachment Point** — a fixed point on a segment's surface for external connections
4. **Topology** — the fixed graph structure of the skeleton
5. **Joint Constraint** — a declaration of angular limits on a joint

**Layer 2 — Kinematic Model (3 entities):**
6. **Local Transform** — position and orientation relative to the parent joint
7. **World Transform** — position and orientation in world space
8. **Coordinate Frame** — the mathematical framework (origin + orthogonal basis) for describing transforms

**Layer 3 — Environment Model (3 entities):**
9. **Surface** — a physical surface in the world
10. **Support** — the set of body points resting on surfaces
11. **Contact** — a specific body-point-to-surface relationship

**Layer 4 — Solver Model (3 entities):**
12. **IK Chain** — the chain of joints the solver operates on
13. **Effector** — a solver target point (not a body part)
14. **Solver Constraint** — a computational rule the solver must respect

**Layer 5 — Author Model (2 entities):**
15. **Intent** — declarative goals from the pose author
16. **Pose Goal** — a specific target configuration

**Layer 6 — Computed State (3 entities):**
17. **Final Pose** — the engine's computed configuration
18. **Derived Data** — computed diagnostics (ROM, symmetry, bone length)
19. **Validation Result** — the outcome of validity checking

Of the 35 current runtime objects, 19 are proper entities (each maps cleanly to one entity), 10 are mixtures (each merges two or more entities), and 6 are technical containers (purely computational, no semantic meaning).

The highest-compression objects are `SkeletonNode` (4 entities merged) and the `Joint` enum (4 categories merged). The lowest-compression objects are the dedicated data classes (`ContactSpec`, `WorldTarget`, `IKConstraint`, etc.) and the intent/state split of `SkeletonPose`.

---

## Lifecycle Map

A lifecycle map traces how each entity is created, used, and consumed across the pipeline.

| Entity | Layer | Created By | Used By | Consumed By |
|---|---|---|---|---|
| **Segment** | Anatomical (1) | `SkeletonDefinition` / `SkeletonFactory` | FK pipeline, constraint solver, finalizer | Renderer (via `Bone`) |
| **Joint** | Anatomical (1) | `SkeletonDefinition` / `SkeletonFactory` | FK pipeline, IK solver, constraint solver | Finalizer (rotation application) |
| **Attachment Point** | Anatomical (1) | `SkeletonDefinition` / `SkeletonFactory` | Contact system, IK effector resolution, landmark tracking | Validator, renderer |
| **Topology** | Anatomical (1) | `SkeletonFactory` / `SkeletonDefinition` | FK pipeline, IK solver, constraint solver | All pipeline stages |
| **Joint Constraint** | Anatomical (1) | `SkeletonDefinition` | Constraint solver, IK solver | Solver (enforced during solve) |
| **Local Transform** | Kinematic (2) | Author (pose definition) / FK pipeline | FK pipeline, IK solver | World Transform computation |
| **World Transform** | Kinematic (2) | FK pipeline (composes local transforms) | Renderer, validator, projector | Screen-space output |
| **Coordinate Frame** | Kinematic (2) | FK pipeline (framework for transforms) | Renderer, validator, projector | Screen-space output |
| **Surface** | Environment (3) | Pose author (exercise definition) | Contact system, constraint solver | Contact resolution |
| **Support** | Environment (3) | Contact system (aggregate of contacts) | Constraint solver, contact re-baking | Finalizer (root repositioning) |
| **Contact** | Environment (3) | Pose intent (contact declaration) + environment surface | Constraint solver, contact re-baking | Finalizer (root repositioning) |
| **IK Chain** | Solver (4) | Derived from Topology + Effector position | IK solver | Finalizer (applied as joint angles) |
| **Effector** | Solver (4) | Pose intent (limb target declaration) | IK solver | Finalizer (applied as joint angles) |
| **Solver Constraint** | Solver (4) | `SkeletonDefinition` + `IKConstraint` | IK solver, constraint solver | Solver (enforced during solve) |
| **Intent** | Author (5) | Pose author (exercise definition) | Pipeline (all stages) | Engine (consumed to produce state) |
| **Pose Goal** | Author (5) | Pose author (exercise definition) | Solver (target specification) | Engine (consumed to produce state) |
| **Final Pose** | Computed State (6) | Pipeline (FK + IK + constraint solving) | Renderer, validator, projector | `ValidatedFrame` / `PipelineResult` |
| **Derived Data** | Computed State (6) | Finalizer (post-processing) | Validator, renderer | Validation report, rendering |
| **Validation Result** | Computed State (6) | Validator | Validation report | Downstream consumers |