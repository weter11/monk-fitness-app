# Domain Analysis: What Real Entities Exist in the System

> Pure domain analysis. No code. No implementation. No refactoring.
> Based on the biomechanical meaning of the human skeleton, not on existing class names.
>
> This is an ontology document — it describes what exists in the domain, not how to implement it.
> One entity may map to multiple classes, and one class may carry multiple entities.
> Mixture is not inherently wrong; the goal is clarity about which aspect of reality each class represents.
>
> A runtime class may intentionally combine several ontological entities if they always share the same lifecycle.
> The purpose of this document is not to require one class per entity, but to make the different semantic aspects explicit.

---

## Introduction

This document analyzes the human skeleton as a biomechanical domain. It identifies what entities exist in the domain (the ontology), what computational roles those entities play in algorithms, and how they are represented in code. The analysis is organized into three layers:

1. **Domain Ontology** — what exists in the human body (permanent, physical, independent of any algorithm).
2. **Computational Concepts** — what mathematical, algorithmic, and application-level constructs operate on the domain (solver targets, observation references, relationship states, coordinate systems).
3. **Representations** — how domain entities and computational roles are stored and serialized in code.

A separate **Processes** section describes the pipeline stages that transform representations.

Key principles:

- The document is an ontology analysis, not an architecture spec. Do not imply that one entity = one class.
- Separate ontology concerns (what exists in the domain) from implementation concerns (how to code it).
- Coordinate Frame is mathematical, not anatomical.
- IK Effector and Contact Point are roles/states, not independent entities.
- Landmark and Attachment Point can coincide; do not force separation.
- Constraint is a declaration; Solver is an algorithm — separate them.
- Mixture isn't always bad — one responsibility = one aspect, not one entity = one class.

---

## Three-Layer Model

### Layer 1: Domain Ontology — What Exists in the Human Body

The domain ontology describes the human body as a physical structure. These entities exist independently of any algorithm, any coordinate system, any computation. They are the subject area itself. The domain ontology is permanent — it does not change between poses or frames.

#### 1. Segment

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
- A segment differs from an articulation because a segment is a rigid body, while an articulation is a joint that allows rotation. The segment is the thing that moves; the articulation is the hinge that lets it move.
- A segment differs from a coordinate frame because a segment has physical properties (length, mass) while a coordinate frame is purely a mathematical description of position and orientation.
- A segment differs from an attachment point because a segment is a volumetric body, while an attachment point is a dimensionless point on the segment's surface.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries segment data (`localPosition` as bone offset) alongside articulation data (`localRotation`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `SkeletonDefinition` — carries segment lengths (`torsoLength`, `upperArmLength`, etc.) alongside constraint data and proportion data
- `Bone` — a rendering primitive that represents a segment visually, but also encodes thickness and color (rendering concerns)

---

#### 2. Articulation

**What it means:** A joint — a point where two segments meet, allowing relative rotation. An articulation has a specific type (ball-and-socket, hinge, pivot) and a specific range of motion. The shoulder, elbow, hip, knee, and neck are articulations. Each articulation has its own rotational freedom and its own physical limits.

**What it belongs to:** The anatomical body. An articulation is a physical structure — it is the connection between two segments.

**What it does not belong to:** An articulation does not own a segment. It does not have length or volume. It is a degree of freedom, not a physical object. An articulation does not define where the segment is in space — it defines how the segment can rotate relative to its parent.

**Natural operations:**
- Rotate within its allowed range (apply a joint angle)
- Check whether a requested rotation exceeds its limits
- Compute the resulting orientation of the child segment after rotation
- Determine its joint type (ball-and-socket, hinge, pivot)

**How it differs from neighboring entities:**
- An articulation differs from a segment because an articulation is a hinge, while a segment is the rigid body between joints. The articulation is the hinge; the segment is the arm.
- An articulation differs from a coordinate frame because an articulation has physical limits (range of motion), while a coordinate frame has no limits — it simply describes a position and orientation.
- An articulation differs from an attachment point because an articulation has rotational DOF, while an attachment point is a fixed point with no rotation.

**Current classes that mix this entity with others:**
- `SkeletonNode` — carries articulation data (`localRotation`) alongside segment data (`localPosition`) and coordinate frame data (`worldPosition`, `worldRotation`)
- `Joint` enum — uses the same identity for articulations, segments, attachments, and helpers
- `SkeletonPose.rotations` — stores world-space rotations for all joints, including non-articulation entries (segments, attachments)

---

#### 3. Attachment Point

**What it means:** A fixed point on a segment's surface where the engineering model connects to something external. The palm, the fingertip, the heel, the top of the head — these are attachment points. They are engineering reference locations on the body. They have no volume, no rotational freedom, and no independent existence. They are points.

**What it belongs to:** The segment it is attached to. An attachment point cannot exist without a segment — it is a point on the surface of a segment.

**What it does not belong to:** An attachment point does not belong to the articulation system. It does not rotate. It does not have a joint angle. It does not have a range of motion. It is a geometric point on the body, not a computational role.

**Natural operations:**
- Compute its world position from the parent segment's pose and the attachment point's local offset
- Check whether it is in contact with a surface
- Check whether it has moved (is it sliding?)
- Determine which surface it is touching (ground, wall, prop)

**How it differs from neighboring entities:**
- An attachment point differs from an articulation because an attachment point has no rotational DOF. An articulation can rotate; an attachment point is fixed.
- An attachment point differs from a segment because an attachment point is a dimensionless point, while a segment is a volumetric body with length.
- An attachment point may coincide with a landmark — the palm is both an attachment point (where the hand connects to the wrist chain) and a landmark (used for viewport validation). When they coincide, they are the same point on the body serving two different purposes: structural connection and observation reference. An attachment point can be used as a landmark, but a landmark is not a separate entity — it is a role the attachment point plays.

**Current classes that mix this entity with others:**
- `SkeletonNode` — attachment nodes (HEAD_POS, HEEL_F, PALM_A) are full `SkeletonNode` instances with `localPosition`, `localRotation`, `worldPosition`, `worldRotation`, even though they have no rotation and no independent DOF
- `Joint` enum — attachment entries share the same enum as articulations and segments
- `SkeletonPose.joints` — attachment positions are stored in the same flat array as articulation positions

---

#### 4. Anatomical Mobility

**What it means:** A declaration of the limits on how far a joint can rotate — the body's physical boundaries. Every articulation has physical limits — the elbow cannot hyperextend, the knee cannot bend backward, the hip has a limited range of motion in every direction. Anatomical mobility is a rule about the body, not an algorithm. It declares what the body is allowed to do; it does not enforce it.

**What it belongs to:** The anatomical model. Mobility limits are permanent properties of the skeleton's anatomy.

**What it does not belong to:** Mobility limits do not belong to the kinematic state. They do not change between frames. They are not computed — they are defined. A mobility declaration is not a solver; the solver is the algorithm that reads mobility limits and enforces them. The declaration is about the body; the solver is the mechanism that reads it.

**Natural operations (of the mobility declaration):**
- Specify the allowed angular range for each axis of a joint
- Define the effective reachability of a limb given its limits
- State whether a joint can hyperextend or is bounded

**How it differs from neighboring entities:**
- Anatomical mobility differs from an articulation because the articulation is the hinge itself (with DOF), while the mobility declaration is the limit on that DOF. The articulation provides the rotation; the mobility declaration restricts it.
- Anatomical mobility differs from a contact because a mobility declaration limits rotation, while a contact fixes position. They are different types of constraints.
- Anatomical mobility differs from the anatomical model because the model includes the anatomy (segments, articulations), while mobility declarations are the rules that govern how the anatomy can move.
- Anatomical mobility differs from a solver constraint because anatomical mobility is anatomical (it describes the body's physical limits), while a solver constraint is computational (it describes what the solver must enforce).

**Current classes that mix this entity with others:**
- `IKConstraint` — carries both the angular limits and the reachability constraints (effectiveExtensionRatio, minimumFlexionAngle) in one object
- `SkeletonDefinition` — carries constraints alongside measurements (bone lengths, proportions), mixing the rule domain with the measurement domain
- `AngularJointLimits` — shared between arm and leg constraints, but the limits are biomechanically different (arm vs. leg)

---

#### 5. Environment

**What it means:** The physical world that the body exists in and interacts with. The environment defines what surfaces are available for contact — the ground plane, walls, boxes, steps, benches. It defines where the body can touch and what those surfaces are like.

**What it belongs to:** The physical world. The environment is external to the body — it is the context in which the body moves.

**What it does not belong to:** The environment does not belong to the biomechanical model. The body has a fixed anatomy; the environment changes from pose to pose. The environment does not define the body's structure — it defines the body's context.

**Natural operations:**
- Define ground plane (level, visibility)
- Define props (boxes, steps, benches, walls) with position, size, and type
- Determine which surface a body point is touching (ground vs. prop)
- Compute support plane normals for contact points
- Determine whether a body point is on a support surface

**How it differs from neighboring entities:**
- The environment differs from pose intent because the environment is the world the body is in, while intent is what the body wants to do. The environment is context; intent is goal.
- The environment differs from the biomechanical model because the environment is external and variable, while the model is internal and fixed.
- The environment differs from a contact because the environment defines the surfaces, while a contact is the specific body-surface relationship.

**Current classes that mix this entity with others:**
- `EnvironmentDefinition` — carries both the ground plane and the props list, mixing two different environmental concepts (flat ground vs. 3D props)
- `SkeletonPose.environment` — the environment is stamped onto the pose by the pipeline, mixing environmental context with kinematic state
- `PoseMetadata` — carries `environment` and `support` alongside camera and timing metadata, mixing environmental context with production metadata

---

### Layer 2: Computational Concepts — Mathematical, Algorithmic, and Application-Level Constructs

The computational concepts describe the mathematical, algorithmic, and application-level constructs that operate on the domain. These entities do not exist in the human body — they are coordinate systems, roles, states, and constructs that domain objects play within a computational system. A single domain object can simultaneously play multiple computational roles.

#### 7. Coordinate Frame

**What it means:** A mathematical frame of reference consisting of an origin point and an orthogonal basis (three mutually perpendicular axes) that defines a position and orientation in 3D space. A coordinate frame is a purely mathematical construct — it has no physical substance, no mass, no length, and no anatomical meaning. It is the language through which the kinematic model describes the body's configuration in the world.

**What it belongs to:** The computational model. A coordinate frame is a mathematical construct, not a physical entity and not a role.

**What it does not belong to:** A coordinate frame does not belong to the anatomical model. It is not a body part. It has no physical meaning — it is a mathematical description of pose. A coordinate frame does not have limits, does not have length, and does not have mass. It is not an anatomical structure; it is a coordinate system.

**Natural operations:**
- Compose with a parent frame (FK propagation)
- Decompose into position and orientation
- Convert from local space to world space
- Convert from world space to local space
- Interpolate between two frames

**How it differs from neighboring entities:**
- A coordinate frame differs from a segment because a frame is a mathematical description, while a segment is a physical object. A frame has no length; a segment does.
- A coordinate frame differs from an articulation because a frame is the result of rotation, while an articulation is the source of rotation. The articulation produces the frame; the frame does not produce the articulation.
- A coordinate frame differs from an attachment point because a frame describes the full pose of a point (position + orientation), while an attachment point is just a position (a point with no orientation).

**Current classes that mix this entity with others:**
- `SkeletonNode` — a `SkeletonNode` is simultaneously a coordinate frame (worldPosition, worldRotation), a segment (localPosition), an articulation (localRotation), and an attachment host (children)
- `SkeletonPose.joints` and `SkeletonPose.rotations` — the flat arrays store coordinate frame data (world positions and world rotations) for all joint types, including segments, articulations, and attachment points
- `JointRotation` — an axis-angle representation that can describe any rotation, whether it belongs to an articulation or is a computed world orientation

---

#### 8. Pose Intent

**What it means:** What the pose author wants the body to do. Intent is declarative — it describes desired outcomes without specifying how to achieve them. The pose author says "the right hand should be on the floor" (contact intent), "the head should look at this point" (gaze intent), "the spine should curve this way" (spine intent), "the right foot should be planted" (support intent). Intent is the input to the engine; the engine figures out how to satisfy it.

**What it belongs to:** The pose author and the application architecture. Intent is declared by the exercise definition, not computed by the engine. It is an application-level concept, not a biomechanical entity.

**What it does not belong to:** Intent does not belong to the kinematic state. Intent is not the result of computation — it is the input to computation. Intent does not contain positions or rotations; it contains goals and constraints. Intent does not belong to the anatomical model — the body's anatomy does not change based on what the author wants.

**Natural operations:**
- Declare a contact (this body point is on this surface)
- Declare a limb target (this end-effector should reach this world position)
- Declare a posture (the body should be in this general configuration)
- Declare a gaze target (the head should look at this world point)
- Declare a heading (this extremity should face this direction)
- Declare an extremity override (preserve this endpoint's authored geometry)

**How it differs from neighboring entities:**
- Pose intent differs from final pose because intent is what the author wants, while the final pose is what the engine computed. Intent is input; the final pose is output.
- Pose intent differs from the biomechanical model because intent is per-pose and per-frame, while the biomechanical model is permanent and skeleton-wide.
- Pose intent differs from the environment because intent describes what the body wants to do, while the environment describes what the world looks like.

**Current classes that mix this entity with others:**
- `SkeletonPose` — carries both intent (§8) and state (§9) in one object, making the boundary between input and output convention-based rather than structural
- `SkeletonPose.IntentBuilder` — the sole mutator of intent, but it is an inner class of `SkeletonPose`, coupling intent mutation to the state object
- `PoseMetadata` — carries environment and support definitions alongside camera and timing metadata, mixing environmental intent with production metadata

---

#### 9. Pose State

**What it means:** The actual computed configuration of the skeleton at a specific moment. Pose state is the result of satisfying the pose intent through FK, IK, and constraint solving. It contains the position and orientation of every joint, plus derived information (hip ROM stamps, bilateral symmetry, bone length verification).

**What it belongs to:** The engine's computation. Pose state is produced by the pipeline and consumed by the renderer and validator. It is a computational snapshot, not a domain entity.

**What it does not belong to:** Pose state does not belong to the pose author. The author declares intent; the engine produces state. Pose state does not contain goals or constraints — it contains results. Pose state does not belong to the anatomical model — it is a per-frame computational result, while the anatomical model is permanent.

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
- `SkeletonPose` — carries both state (§9) and intent (§8) in one object
- `SkeletonPose.rotations` — stores world-space rotations (state), but the intent carriers (`jointIntents`, `extremityArticulations`) store local-space rotations (intent). The same pose object contains both coordinate systems.

---

#### 10. IK Effector (Solver Role)

**What it means:** A computational role that an attachment point plays when it is selected as a target for inverse kinematics. The hand, the foot, the fingertip — these are attachment points on the body. When the solver needs to position one of these points at a specific world-space location, the attachment point takes on the role of IK effector. An IK effector is not a body part; it is a solver role.

**What it belongs to:** The computational model. An IK effector is a solver concept — it is the target that the IK algorithm tries to reach. It is not a domain entity.

**What it does not belong to:** An IK effector does not belong to the anatomical model. It is not a body part. Humans do not have "IK effectors." An IK effector does not have a fixed position — it moves as the solver repositions the limb.

**Natural operations:**
- Define a target position in world space
- Determine whether the target is reachable given the limb's bone lengths
- Compute the joint angles that place the effector at the target
- Clamp the target to the reachable workspace if it is unreachable

**How it differs from neighboring entities:**
- An IK effector differs from an attachment point because an attachment point is a fixed point on a segment (the palm is always at the end of the hand), while an IK effector is a computational role that an attachment point plays when targeted by the solver. The palm is an attachment point; the hand's target position is an IK effector role.
- An IK effector differs from a contact because a contact is a fixed support relationship, while an IK effector is a solver goal that may or may not be reachable. A contact is always honored; an IK effector may be clamped.
- An IK effector differs from an articulation because an articulation has a fixed relationship to its parent segment, while an IK effector's position is computed by the solver.

**Current classes that mix this entity with others:**
- `ContactSpec` — carries both the end-effector identity (`endJoint`) and the contact metadata (`targetWorld`, `contact`). The effector and the contact are merged.
- `WorldTarget` — carries the IK target (`joint`, `world`) alongside the contact constraint (`contact` field). The target and the contact are merged.
- `HAND_A`, `HAND_P`, `FINGERTIPS_A`, `FINGERTIPS_P`, `TOE_F`, `TOE_B` — these `Joint` enum entries serve as both attachment markers (fixed points on segments) and IK effectors (solver targets).

---

#### 11. Contact (State of Attachment Point + Environment)

**What it means:** The state of an Attachment Point relative to an Environment Surface. The foot on the floor, the hand on a bar, the knee on the ground — these describe a contact state. A contact is a state: this attachment point is touching this surface, and it is not moving relative to it. Contact is not a separate physical point — it is the state of a domain object (attachment point) relative to an environmental object (surface).

**What it belongs to:** The computational model. Contact is a state computed by the engine, not a domain entity.

**What it does not belong to:** Contact does not belong to the anatomical model. It is not a body part. It is the state of a relationship between the body and the environment. Contact does not belong to the kinematic chain in the same way an articulation does — it is not a degree of freedom.

**Natural operations:**
- Register an attachment point as being in contact with a surface
- Define the surface normal and friction properties
- Determine whether the contact is still valid (has the attachment point moved off the surface?)
- Resolve conflicts when multiple contacts compete for the same root position

**How it differs from neighboring entities:**
- A contact differs from an IK effector because a contact is a fixed constraint (the point does not move), while an IK effector is a solver goal (the point moves to reach the target). A contact is always honored; an IK effector may be clamped.
- A contact differs from an attachment point because an attachment point is a geometric point on a segment, while a contact is a physical interaction between a segment and the environment. All contacts involve attachment points, but not all attachment points are in contact.
- A contact differs from an articulation because a contact removes DOF, while an articulation provides DOF.
- A contact differs from a surface because a surface is the world's geometry, while a contact is the specific body-surface relationship.

**Current classes that mix this entity with others:**
- `ContactSpec` — merges the contact (endJoint, targetWorld) with the IK chain context (rootJoint, parentRotationJoint, middleJoint, pole, constraint, straight) and the contact metadata (contact field with surface normal)
- `SkeletonPose.contacts` — stores contact specs alongside the pose state, mixing the contact declaration with the kinematic state
- `SupportPoint` enum — maps contact points to body joints, but the mapping is hardcoded rather than derived from the biomechanical model

---

#### 12. Landmark (Observation/Tracking Role)

**What it means:** A computational role that an attachment point plays when it is used for observation, tracking, or validation. Landmarks do not exist anatomically — there are wrists, elbows, noses, and head centers, but there is no anatomical entity called a "landmark." A landmark is a point on the body that a tracking system, validator, or camera uses as a reference. It is a diagnostic instrument, not a biomechanical object.

**What it belongs to:** The computational model. A landmark is a role that an attachment point plays within a tracking or validation system. It is not a domain entity.

**What it does not belong to:** A landmark does not have its own DOF. It does not drive the kinematics. It is a reference point for observation, not a driver of motion. A landmark does not belong to the anatomical model.

**Natural operations:**
- Project to screen space for viewport validation
- Compare between frames for motion continuity
- Compare left vs. right for bilateral symmetry
- Check whether the landmark is in a valid region (e.g., head inside viewport, feet above ground)

**How it differs from neighboring entities:**
- A landmark differs from an articulation because a landmark has no rotational DOF. It is a reference point for observation, not a joint.
- A landmark may coincide with an attachment point — the palm is both an attachment point (where the hand connects to the wrist chain) and a landmark (used for viewport validation). When they coincide, they are the same point on the body serving two different purposes: structural connection and observation reference. An attachment point can be used as a landmark, but a landmark is not a separate entity — it is a role the attachment point plays.
- A landmark differs from a coordinate frame because a landmark is a single point of interest, while a coordinate frame describes a full pose (position + orientation).

**Current classes that mix this entity with others:**
- `HEAD_POS` — serves as both an attachment point (the head's position on the neck chain) and a landmark (used for viewport validation)
- `HAND_A`, `HAND_P`, `WRIST_A`, `WRIST_P` — serve as both attachment points (hand/wrist endpoints) and landmarks (used for hand sliding detection)
- `Joint` enum — landmark entries share the same enum as articulations and segments

---

#### 13. Solver Constraint (Computational Role)

**What it means:** A computational rule that the solver must enforce during inverse kinematics or constraint solving. Unlike anatomical mobility (which declares the body's physical limits), a solver constraint is an algorithmic requirement — it tells the solver what conditions must hold in the computed result. Examples include: keeping the root position within a support polygon, maintaining a minimum distance between two points, or ensuring that a contact point's surface normal is respected.

**What it belongs to:** The computational model. Solver constraints are algorithmic requirements, not anatomical properties.

**What it does not belong to:** Solver constraints do not belong to the anatomical model. They are not properties of the body — they are properties of the computation. A solver constraint is not a declaration about the body; it is a requirement on the solver's output.

**Natural operations:**
- Define the allowable workspace for an end-effector
- Specify minimum/maximum distances between body points
- Enforce surface normal alignment at contact points
- Constrain the root position relative to the support polygon

**How it differs from neighboring entities:**
- A solver constraint differs from anatomical mobility because mobility describes the body's physical limits (what the body can do), while a solver constraint describes what the computation must enforce (what the result must satisfy).
- A solver constraint differs from a contact because a contact is a state (the body is touching a surface), while a solver constraint is a requirement on the solver's output.
- A solver constraint differs from pose intent because intent is declarative (what the author wants), while a solver constraint is prescriptive (what the solver must guarantee).

**Current classes that mix this entity with others:**
- `IKConstraint` — carries both the angular limits (anatomical mobility) and the reachability constraints (solver constraint) in one object
- `ContactSpec` — carries the contact state and the solver constraint (contact surface normal, friction) in one object
- `SkeletonDefinition` — carries constraints alongside measurements, mixing the rule domain with the measurement domain

---

### Layer 3: Representations — How Data Is Stored and Serialized

Representations are the concrete data structures that store domain entities and computational roles in code. They are implementation artifacts — they exist because code needs to hold and transmit data, not because the domain requires them.

#### 14. SkeletonNode (Runtime Representation)

**What it means:** The runtime object that holds the transform data for a single joint in the skeleton hierarchy. A `SkeletonNode` stores `localPosition`, `localRotation`, `worldPosition`, and `worldRotation` for one entry in the joint tree.

**What it represents:** A `SkeletonNode` is an intentional runtime aggregate — it is not an ontological object but a computational container that stores runtime state for multiple domain entities. It holds state for:
- A **Segment** (via `localPosition` — the bone offset from parent)
- An **Articulation** (via `localRotation` — the joint angle)
- A **Coordinate Frame** (via `worldPosition` and `worldRotation` — the computed pose)
- An **Attachment Point host** (via its children list)

A `SkeletonNode` is not a Segment. A Node is not an Articulation. A Node is not a Coordinate Frame. It is a runtime container that stores the state these entities have at a given moment. The aggregation is intentional — these entities always share the same lifecycle in the runtime, so combining them in one object is a practical convenience, not an ontological claim.

**Current classes that use this representation:**
- `SkeletonNode` — the primary runtime joint representation
- `SkeletonPose.joints` — a flat array of `SkeletonNode` instances

---

#### 15. SkeletonPose (Runtime Representation)

**What it means:** The top-level object that holds the complete pose of the skeleton at a single moment. It contains joint transforms, intent declarations, contact states, and environmental context.

**What it represents:** `SkeletonPose` is a representation that conflates multiple computational roles into a single transport object. It simultaneously represents:
- **Pose State** (via `rotations`, `joints` — the computed configuration)
- **Pose Intent** (via `IntentBuilder`, `jointIntents`, `extremityArticulations` — the declared goals)
- **Contact State** (via `contacts` — which attachment points are touching which surfaces)
- **Environmental Context** (via `environment` — what surfaces are available)

**Current classes that use this representation:**
- `SkeletonPose` — the primary pose representation
- `PipelineResult` / `ValidatedFrame` — wraps a `SkeletonPose` with pipeline metadata

---

#### 16. Joint Enum (Semantic Label Representation)

**What it means:** A symbolic identifier namespace that provides named keys for every joint in the skeleton. The `Joint` enum is not an ontological representation — it is a computational label namespace that maps human-readable names to integer indices used throughout the codebase.

**What it represents:** The `Joint` enum is a representation that conflates multiple domain entities into a single namespace. It simultaneously represents:
- **Articulations** (shoulder, elbow, hip, knee, neck)
- **Segments** (upper arm, forearm, thigh, shin)
- **Attachment Points** (HEAD_POS, HEEL_F, PALM_A)
- **Helper/utility entries** (Wrist_A, Wrist_P, etc.)

**Current classes that use this representation:**
- `Joint` enum — the single namespace for all joint identities
- `SkeletonPose.rotations` — indexed by `Joint`
- `SkeletonPose.joints` — indexed by `Joint`

---

#### 17. Bone (Rendering Representation)

**What it means:** A rendering primitive that represents a segment visually as a line or cylinder between two points in screen space.

**What it represents:** `Bone` is a representation that carries both structural and visual concerns. It represents a **Segment** (the structural entity) but also encodes thickness and color (rendering concerns).

**Current classes that use this representation:**
- `Bone` — the rendering primitive
- `SkeletonEngine.bones` — the list of bones used for rendering

---

#### 18. SkeletonDefinition (Serialization Representation)

**What it means:** The data object that parameterizes the computational skeleton model — bone lengths, proportions, constraint limits, and topology. It is the serialized form of the model's parameters. A SkeletonDefinition is not the skeleton itself; it is the parameterization of a computational skeleton model. Skeleton != SkeletonDefinition. Definition is parameters.

**What it represents:** `SkeletonDefinition` is a representation that parameterizes the skeleton model:
- **Segment** data (bone lengths like `torsoLength`, `upperArmLength`)
- **Anatomical Mobility** data (constraint limits like `effectiveExtensionRatio`, `minimumFlexionAngle`)
- **Proportion** data (ratios between bone lengths)

**Current classes that use this representation:**
- `SkeletonDefinition` — the primary serialization representation
- `SkeletonFactory` — reads `SkeletonDefinition` to build the runtime skeleton

---

#### 19. ContactSpec (Contact Representation)

**What it means:** A data object that represents a contact relationship — which attachment point is touching which surface, and with what metadata.

**What it represents:** `ContactSpec` is an intentional aggregation for one contact interaction. It combines the contact declaration (which attachment point is touching which surface), the solver target (the end-effector identity and target position), and solver-specific metadata (surface normal, friction) into a single object because these pieces always travel together through the pipeline. The aggregation is deliberate — it is not a problematic mixture but a practical grouping of concerns that share the same lifecycle for a single contact event.

It does not carry IK chain context, pole vectors, or other implementation details — those belong to the solver's internal logic, not the ontology.

**Current classes that use this representation:**
- `ContactSpec` — the primary contact representation
- `WorldTarget` — carries IK target and optionally a contact constraint

---

### Processes — Pipeline Stages That Transform Representations

Processes are the ordered stages of the skeleton pipeline. Each stage takes one or more representations as input and produces representations as output. Processes are not entities — they are transformations.

#### 20. FK Pipeline

**What it does:** Propagates local transforms (localPosition, localRotation) up the skeleton tree to compute world-space coordinate frames (worldPosition, worldRotation) for every joint.

**Input representations:** SkeletonDefinition (bone lengths, topology), SkeletonPose (local transforms)
**Output representations:** SkeletonPose (world transforms), Coordinate Frames (computed world positions and orientations)

**Domain entities involved:** Segment, Articulation
**Computational roles involved:** Coordinate Frame

---

#### 21. IK Solver

**What it does:** Computes joint angles that place an end-effector (attachment point in IK effector role) at a target world-space position, respecting anatomical mobility limits and solver constraints.

**Input representations:** SkeletonPose (current state), WorldTarget (IK target + optional contact), SkeletonDefinition (bone lengths, constraints)
**Output representations:** SkeletonPose (updated local rotations), Pose State (computed configuration)

**Domain entities involved:** Segment, Articulation, Attachment Point, Anatomical Mobility, Environment
**Computational roles involved:** IK Effector, Solver Constraint, Pose State

---

#### 22. Constraint Solver

**What it does:** Enforces solver constraints — contact surface normals, root repositioning, support polygon constraints — after IK solving. Adjusts the skeleton's root position and joint angles to satisfy constraints.

**Input representations:** SkeletonPose (IK output), ContactSpec (contact states), SkeletonDefinition (constraints)
**Output representations:** SkeletonPose (constraint-satisfying state), Pose State (final configuration)

**Domain entities involved:** Attachment Point, Environment, Anatomical Mobility
**Computational roles involved:** Contact, Solver Constraint, Pose State

---

#### 23. Finalizer

**What it does:** Applies post-solve corrections — reconstructs the chest frame without overwriting authored thoracic rotation, computes derived stamps (hip ROM, bilateral symmetry, bone length verification), and applies extremity overrides.

**Input representations:** SkeletonPose (constraint-satisfying state), SkeletonDefinition (segment lengths, proportions)
**Output representations:** SkeletonPose (final state with stamps), Pose State (derived data)

**Domain entities involved:** Segment, Articulation, Attachment Point
**Computational roles involved:** Pose State

---

#### 24. Validator

**What it does:** Checks the final pose against biomechanical rules — bone lengths are preserved, joints are within mobility limits, landmarks are in valid regions, bilateral symmetry holds.

**Input representations:** SkeletonPose (final state), SkeletonDefinition (constraints), Landmark data (observation references)
**Output representations:** ValidationReport (issues, severities, results)

**Domain entities involved:** Segment, Articulation, Attachment Point, Anatomical Mobility, Environment
**Computational roles involved:** Landmark, Pose State

---

#### 25. Renderer

**What it does:** Projects the skeleton's world-space coordinate frames into screen space, draws bones and joints, and applies perspective compensation.

**Input representations:** SkeletonPose (final state with world transforms), Camera (view parameters)
**Output representations:** Screen-space output (projected skeleton, exercise snapshot)

**Domain entities involved:** Segment, Attachment Point
**Computational roles involved:** Coordinate Frame, Pose State

---

### Cross-Reference Table: Domain Entity → Computational Role → Representation

| Domain Entity (Layer 1) | Computational Role(s) (Layer 2) | Representation(s) (Layer 3) |
|---|---|---|
| **Segment** | Coordinate Frame (carries segment's transform) | `SkeletonNode` (localPosition), `Bone` (visual), `SkeletonDefinition` (lengths) |
| **Articulation** | Coordinate Frame (carries joint rotation) | `SkeletonNode` (localRotation), `SkeletonPose.rotations` |
| **Attachment Point** | IK Effector (solver target), Landmark (observation reference), Contact (body-surface relationship) | `SkeletonNode` (attachment nodes), `ContactSpec` (contact state), `Joint` enum entries |
| **Anatomical Mobility** | Solver Constraint (computational enforcement of limits) | `IKConstraint`, `SkeletonDefinition` (constraint data), `AngularJointLimits` |
| **Environment** | Contact (surface context for attachment points) | `EnvironmentDefinition`, `ContactSpec` (surface metadata), `PoseMetadata` |
| **Coordinate Frame** | — (mathematical construct, not a domain entity) | `SkeletonNode` (worldPosition, worldRotation), `JointRotation` |
| **Pose Intent** | — (declarative input, not a domain entity) | `SkeletonPose.IntentBuilder`, `RelativeArticulation`, `SpineCurve`, `PostureIntent`, `HeadTarget` |
| **Pose State** | — (computational snapshot, not a domain entity) | `SkeletonPose` (rotations, joints), `PipelineResult`, `ValidatedFrame` |
| **IK Effector** (role of Attachment Point) | — (solver role, not a domain entity) | `WorldTarget`, `ContactSpec` (endJoint), `Joint` enum entries (HAND_A, etc.) |
| **Contact** (state of Attachment Point + Environment) | — (relationship state, not a domain entity) | `ContactSpec`, `SkeletonPose.contacts`, `SupportPoint`, `SupportContact` |
| **Landmark** (role of Attachment Point) | — (observation role, not a domain entity) | `HEAD_POS`, `HAND_A`, `HAND_P`, `WRIST_A`, `WRIST_P`, `Joint` enum entries |
| **Solver Constraint** (computational role) | — (algorithmic requirement, not a domain entity) | `IKConstraint` (reachability), `ContactSpec` (surface normal), `ConstraintSolver` |

---

### Mapping Table: Current Runtime Object → Domain Entity + Computational Role

| Current Runtime Object | Domain Entity (Layer 1) | Computational Concept(s) (Layer 2) | Representation (Layer 3) | Classification |
|---|---|---|---|---|
| `SkeletonNode` | Segment + Articulation + Attachment Point | Coordinate Frame | `SkeletonNode` | **Mixture** — stores runtime state for multiple entities in one class |
| `SkeletonPose` | — | Pose State + Pose Intent + Contact State | `SkeletonPose` | **Mixture** — carries state, intent, and transport in one object |
| `Joint` enum | Semantic Label namespace for all of: Articulation, Segment, Attachment Point, Helper | — | `Joint` enum | **Symbolic identifier namespace** — not an ontological representation; maps names to indices |
| `Bone` | Segment (visual representation) | — | `Bone` | **Mixture** — carries visual and structural concerns |
| `SkeletonDefinition` | Segment + Articulation + Anatomical Mobility | — | `SkeletonDefinition` | **Mixture** — parameterizes the computational skeleton model, constraints, and proportions |
| `SkeletonFactory` | Topology Builder | — | `SkeletonFactory` | **Proper entity** — builds the fixed tree structure |
| `SkeletonNodes` | Topology Convenience Container | — | `SkeletonNodes` | **Technical container** — exposes node references by name for authoring |
| `ContactSpec` | Attachment Point + Environment (Surface) | Contact + IK Effector Context + Solver Constraint | `ContactSpec` | **Intentional aggregation** — contact declaration, solver target, and solver metadata grouped for one contact interaction |
| `WorldTarget` | Attachment Point + Environment (Surface) | IK Effector + Contact Constraint | `WorldTarget` | **Mixture** — carries the IK target and optionally a contact constraint |
| `RelativeArticulation` | — | Pose Goal (articulation declaration) | `RelativeArticulation` | **Proper entity** — a single goal declaration |
| `SpineCurve` | — | Pose Goal (spine declaration) | `SpineCurve` | **Proper entity** — a single goal declaration |
| `PostureIntent` | — | Pose Intent (posture declaration) | `PostureIntent` | **Proper entity** — a single intent declaration |
| `HeadTarget` | — | Pose Intent (gaze declaration) | `HeadTarget` | **Proper entity** — a single intent declaration |
| `ExtremityOrientationMode` | — | Intent Modifier (ownership flag) | `ExtremityOrientationMode` | **Proper entity** — modifies how the engine treats an extremity |
| `Extremity` enum | Semantic Label for the four extremities | — | `Extremity` enum | **Proper entity** — identifies the four extremity categories |
| `JointRotation` | — | Orientation (axis-angle representation) | `JointRotation` | **Proper entity** — a mathematical representation of rotation |
| `Vector3` | — | Position (3D point) | `Vector3` | **Proper entity** — a mathematical representation of position |
| `IKConstraint` | Anatomical Mobility (arm/leg) | Solver Constraint | `IKConstraint` | **Proper entity** — defines reachability and angular limits |
| `AngularJointLimits` | Anatomical Mobility (per-plane limits) | — | `AngularJointLimits` | **Proper entity** — defines angular limits for a joint type |
| `HipRomLimits` | Anatomical Mobility (hip-specific ROM) | — | `HipRomLimits` | **Proper entity** — defines hip ROM limits |
| `FootDefinition` | Segment (foot anatomy) | — | `FootDefinition` | **Proper entity** — defines foot bone lengths and ratios |
| `HandDefinition` | Segment (hand anatomy) | — | `HandDefinition` | **Proper entity** — defines hand bone lengths and ratios |
| `SkeletonEngine` | — | Rendering Definition (bone list + style) | `SkeletonEngine` | **Mixture** — carries rendering bone topology and visual style |
| `SkeletonStyle` | — | Rendering Style | `SkeletonStyle` | **Proper entity** — visual parameters only |
| `SkeletonProjector` | — | Projection Engine | `SkeletonProjector` | **Proper entity** — 3D→2D projection |
| `ProjectedSkeleton` | — | Screen-Space State | `ProjectedSkeleton` | **Proper entity** — the 2D representation of the skeleton |
| `ExerciseValidator` | — | Validation Engine | `ExerciseValidator` | **Proper entity** — rule checking |
| `ValidatorConfig` | — | Validation Profile | `ValidatorConfig` | **Proper entity** — configuration for validation rules |
| `ConstraintSolver` | — | Root Repositioning Engine | `ConstraintSolver` | **Proper entity** — contact re-baking and root adjustment |
| `SkeletonPoseFinalizer` | — | Finalization Engine | `SkeletonPoseFinalizer` | **Proper entity** — FK, reconstruction, derivation, stamps |
| `IkStage` | — | IK Solver Stage | `IkStage` | **Proper entity** — limb IK solving (currently gated) |
| `PoseBuilder` / `BasePose` | — | Intent Authoring API + Pose Goal API | `PoseBuilder` / `BasePose` | **Proper entity** — the interface for declaring pose intent and goals |
| `SkeletonPipeline` | — | Pipeline Orchestrator | `SkeletonPipeline` | **Proper entity** — drives the ordered stage chain |
| `PoseMetadata` | — | Production Metadata (camera, timing, loop, environment, support) | `PoseMetadata` | **Mixture** — carries production metadata and environmental context |
| `EnvironmentDefinition` | Environment (ground + props) | — | `EnvironmentDefinition` | **Proper entity** — the physical world context |
| `SupportPoint` enum | Attachment Point (body contact points) | — | `SupportPoint` enum | **Proper entity** — identifies which body points can be supports |
| `SupportContact` enum | Attachment Point (which extremities are supported) | — | `SupportContact` enum | **Proper entity** — identifies which extremities are in contact |
| `SupportDefinition` | — | Pose Intent (support configuration) | `SupportDefinition` | **Proper entity** — declares which body parts are supported |
| `EnvironmentProp` / `BoxProp` / `StepProp` / `BenchProp` / `WallProp` | Environment (prop definitions) | — | `EnvironmentProp` etc. | **Proper entity** — environmental objects |
| `Camera` / `CameraDefinition` | — | Rendering Configuration | `Camera` / `CameraDefinition` | **Proper entity** — view parameters |
| `ScreenSpaceCompensation` | — | Rendering Utility | `ScreenSpaceCompensation` | **Technical container** — perspective scaling |
| `MotionDrivers` / `MotionCurves` | — | Animation Utility | `MotionDrivers` / `MotionCurves` | **Technical container** — motion generation |
| `PivotType` | — | Configuration | `PivotType` | **Technical container** — pivot configuration |
| `ExerciseSnapshot` / `ExerciseSnapshotSequence` | — | Rendering Output | `ExerciseSnapshot` etc. | **Proper entity** — captured frames |
| `ValidationReport` / `ValidationResult` / `ValidationIssue` / `ValidationSeverity` | — | Validation Result | `ValidationReport` etc. | **Proper entity** — validation results |
| `PipelineResult` / `ValidatedFrame` | — | Transport Object | `PipelineResult` / `ValidatedFrame` | **Technical container** — pipeline result wrapper |
| `HipRomStamp` | — | Derived Data | `HipRomStamp` | **Proper entity** — computed ROM decomposition |
| `SkeletonMath.IKResult` | — | Solver Output | `SkeletonMath.IKResult` | **Technical container** — IK solve result |
| `ContactChain` | Topology (IK chain definition) | — | `ContactChain` | **Proper entity** — defines the proximal chain for a contact |
| `LocalMatrixScratch` | — | Computation Scratch | `LocalMatrixScratch` | **Technical container** — matrix computation buffers |

---

## Summary

The system contains three distinct ontological layers:

### Layer 1: Domain Ontology (what exists in the human body):
1. **Segment** — a rigid body in the biomechanical chain
2. **Articulation** — a joint with rotational DOF and limits
3. **Attachment Point** — a fixed point on a segment's surface for external connections
4. **Anatomical Mobility** — a declaration of angular limits on a joint (the body's physical boundaries)
5. **Environment** — the physical world the body exists in

### Layer 2: Computational Roles (what domain objects do in algorithms):
6. **Coordinate Frame** — a mathematical framework for describing position and orientation
7. **Pose Intent** — a declarative goal declared by the pose author (application-level)
8. **Pose State** — a computational snapshot of the model's configuration
9. **IK Effector** — a role that an attachment point plays when targeted by the solver
10. **Contact** — a relationship state between an attachment point and a surface
11. **Landmark** — a role that an attachment point plays when used for observation/tracking
12. **Solver Constraint** — a computational rule that the solver must enforce in its output

### Layer 3: Representations (how data is stored and serialized):
13. **SkeletonNode** — runtime joint transform container
14. **SkeletonPose** — top-level pose transport object
15. **Joint enum** — semantic label namespace for all joints
16. **Bone** — rendering primitive for segments
17. **SkeletonDefinition** — serialized skeleton parameters
18. **ContactSpec** — contact relationship data object

### Processes (pipeline stages):
19. **FK Pipeline** — propagates local transforms to world-space coordinate frames
20. **IK Solver** — computes joint angles to reach targets
21. **Constraint Solver** — enforces solver constraints (contacts, root position)
22. **Finalizer** — applies post-solve corrections and derives stamps
23. **Validator** — checks the final pose against biomechanical rules
24. **Renderer** — projects to screen space and draws

The highest-compression objects are `SkeletonNode` and the `Joint` enum. The lowest-compression objects are the dedicated data classes (`ContactSpec`, `WorldTarget`, `IKConstraint`, etc.) and the intent/state split of `SkeletonPose`.

The key insight is that computational entities (IK Effector, Landmark, Contact, Solver Constraint) are not independent domain objects — they are roles that domain objects (Attachment Points) play within the computational system. A single attachment point can simultaneously be an IK effector, a landmark, and a contact point. This reduces the number of true domain entities and makes the model cleaner.

The Orientation Constraint from the previous analysis has been split into two distinct concepts: **Anatomical Mobility** (Layer 1 — a declaration about the body's physical limits) and **Solver Constraint** (Layer 2 — a computational rule the solver must enforce). This separation clarifies that constraints are declarations about the body, while solver constraints are requirements on the computation.

---

## Lifecycle Map

A lifecycle map traces how each domain entity is created, used, and consumed across the pipeline, and how computational roles and representations relate to them.

| Entity | Layer | Created By | Used By | Consumed By |
|---|---|---|---|---|
| **Segment** | Domain (L1) | `SkeletonDefinition` / `SkeletonFactory` | FK pipeline, constraint solver, finalizer | Renderer (via `Bone`) |
| **Articulation** | Domain (L1) | `SkeletonDefinition` / `SkeletonFactory` | FK pipeline, IK solver, constraint solver | Finalizer (rotation application) |
| **Attachment Point** | Domain (L1) | `SkeletonDefinition` / `SkeletonFactory` | Contact system, IK effector resolution, landmark tracking | Validator, renderer |
| **Anatomical Mobility** | Domain (L1) | `SkeletonDefinition` | Constraint solver, IK solver | Solver (enforced during solve) |
| **Environment** | Domain (L1) | Pose author (exercise definition) | Contact system, constraint solver | Contact resolution |
| **Coordinate Frame** | Computational (L2) | FK pipeline (from parent frame + local transform) | Renderer, validator, projector | Screen-space output |
| **Pose Intent** | Computational (L2) | Pose author (exercise definition) | Pipeline (all stages) | Engine (consumed to produce state) |
| **Pose State** | Computational (L2) | Pipeline (FK + IK + constraint solving) | Renderer, validator, projector | `ValidatedFrame` / `PipelineResult` |
| **IK Effector** (role of Attachment Point) | Computational (L2) | Pose intent (limb target declaration) | IK solver | Finalizer (applied as joint angles) |
| **Contact** (state of Attachment Point + Environment) | Computational (L2) | Pose intent (contact declaration) + environment surface | Constraint solver, contact re-baking | Finalizer (root repositioning) |
| **Landmark** (role of Attachment Point) | Computational (L2) | Defined in validation profile | Validator, viewport projector | Validation report |
| **Solver Constraint** (computational rule) | Computational (L2) | `SkeletonDefinition` / `IKConstraint` | Constraint solver, IK solver | Solver (enforced during solve) |
| **SkeletonNode** | Representation (L3) | `SkeletonFactory` / `SkeletonDefinition` | FK pipeline, IK solver, renderer | `SkeletonPose` |
| **SkeletonPose** | Representation (L3) | Pipeline (FK + IK + constraint solving) | Renderer, validator, projector | `ValidatedFrame` / `PipelineResult` |
| **Joint enum** | Representation (L3) | `SkeletonDefinition` / `SkeletonFactory` | All pipeline stages (lookup key) | All pipeline stages |
| **Bone** | Representation (L3) | `SkeletonEngine` | Renderer | Screen output |
| **SkeletonDefinition** | Representation (L3) | Author (exercise definition) | `SkeletonFactory`, FK pipeline, IK solver, constraint solver | All pipeline stages |
| **ContactSpec** | Representation (L3) | Pose intent (contact declaration) + environment surface | Constraint solver, contact re-baking | Finalizer |