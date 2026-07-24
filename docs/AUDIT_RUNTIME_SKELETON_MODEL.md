# Architectural Audit: Runtime Skeleton Model

> No code changes. No renaming. No implementation proposals.
> Documents the runtime model as it currently exists.

---

## Task 1 — Classify Every Joint

| Joint | Category | Why |
|---|---|---|
| `PELVIS` | SEGMENT | The root body segment. Its `worldPosition` is the pelvis center. The solver repositions it. It has no parent and carries the body's world pose. |
| `LUMBAR` | SEGMENT | A lower-spine segment between PELVIS and CHEST. Defaults to pass-through (identity rotation, coincident with pelvis). When authored, it carries independent lumbar DOF. It is a rigid body link, not a joint with independent rotational identity — its rotation is the segment's orientation. |
| `CHEST` | SEGMENT | The thorax segment. Root of the upper body. Carries thoracic twist/side-bend/flex as a segment orientation. The chest's `localRotation` is the segment's rotation relative to its parent (lumbar or pelvis). |
| `NECK_END` | ARTICULATION | A rotational DOF between CHEST and HEAD. The neck flexes/extends/laterally bends/rotates. Its `localRotation` is the joint angle relative to the chest segment. |
| `HEAD_POS` | ATTACHMENT | A position marker, not a true articulation. It has no authored rotation — its position is derived procedurally from the neck direction + a fixed head length (18f). The Finalizer's `resolveHeadTarget` writes `head.localPosition` directly. It participates in FK traversal only because it is a child of NECK_END in the tree. |
| `CLAVICLE_A` | ARTICULATION | Left shoulder girdle. Carries elevation/depression + protraction/retraction rotation. Its `localRotation` is the joint angle relative to the chest. |
| `SCAPULA_A` | ARTICULATION | Left scapula. Carries scular upward/downward rotation + protraction/retraction. Its `localRotation` is the joint angle relative to the clavicle. |
| `SHOULDER_A` | ARTICULATION | Left glenohumeral joint. 3-DOF ball-and-socket. Its `localRotation` is the joint angle relative to the scapula. It is the IK root for the left arm chain. |
| `ELBOW_A` | ARTICULATION | Left elbow. Hinge joint with flexion/extension only. Its `localRotation` is the joint angle relative to the shoulder. |
| `HAND_A` | ATTACHMENT | Left hand terminal point. Its position is set by the IK solver (`bakeIkLimb` → `SkeletonMath.toLocalDirection`). It has no authored rotation. It is a coordinate marker at the end of the arm chain. |
| `WRIST_A` | HELPER | Carries authored wrist articulation (grip, pronation/supination) via `localRotation`. However, it is not an anatomical joint with independent DOF — it is a procedural node that the Finalizer reads to derive palm/fingertip geometry. It serves dual duty as an articulation (rotation storage) and a rig helper (geometry derivation substrate). |
| `PALM_A` | ATTACHMENT | Left palm marker. Position derived by the Finalizer's `adjustHandOrientation` from the wrist position + hand definition. It is a contact point for support detection. |
| `KNUCKLES_A` | ATTACHMENT | Left knuckles marker. Position derived from palm + finger definition. No independent DOF. |
| `FINGERTIPS_A` | ATTACHMENT | Left fingertips terminal marker. Position derived from knuckles + finger definition. Used as a support contact point. |
| `CLAVICLE_P` | ARTICULATION | Right shoulder girdle. Mirror of CLAVICLE_A. |
| `SCAPULA_P` | ARTICULATION | Right scapula. Mirror of SCAPULA_A. |
| `SHOULDER_P` | ARTICULATION | Right glenohumeral joint. Mirror of SHOULDER_A. |
| `ELBOW_P` | ARTICULATION | Right elbow. Mirror of ELBOW_A. |
| `HAND_P` | ATTACHMENT | Right hand terminal point. Mirror of HAND_A. |
| `WRIST_P` | HELPER | Mirror of WRIST_A. Same dual-role issue: stores rotation but is a procedural derivation substrate, not an anatomical joint. |
| `PALM_P` | ATTACHMENT | Right palm marker. Mirror of PALM_A. |
| `KNUCKLES_P` | ATTACHMENT | Right knuckles marker. Mirror of KNUCKLES_A. |
| `FINGERTIPS_P` | ATTACHMENT | Right fingertips terminal marker. Mirror of FINGERTIPS_A. |
| `HIP_F` | ARTICULATION | Left hip ball-and-socket joint. 3-DOF (flexion/extension, abduction/adduction, internal/external rotation). Its `localRotation` is the joint angle relative to the pelvis. |
| `KNEE_F` | ARTICULATION | Left knee. Hinge joint with flexion/extension. Its `localRotation` is the joint angle relative to the hip. |
| `ANKLE_F` | ARTICULATION | Left ankle. 2-DOF (dorsiflexion/plantar-flexion + inversion/eversion). Its `localRotation` is the joint angle relative to the knee. |
| `HEEL_F` | ATTACHMENT | Left heel marker. Position derived by the Finalizer's `adjustFootOrientation` from the ankle position + foot definition. Contact point for support detection. No authored rotation. |
| `TOE_F` | ATTACHMENT | Left toe terminal marker. Position derived from ankle + foot definition. Contact point for support detection. No authored rotation. |
| `KNEE_B` | ARTICULATION | Right knee. Mirror of KNEE_F. |
| `ANKLE_B` | ARTICULATION | Right ankle. Mirror of ANKLE_F. |
| `HEEL_B` | ATTACHMENT | Right heel marker. Mirror of HEEL_F. |
| `TOE_B` | ATTACHMENT | Right toe terminal marker. Mirror of TOE_F. |

### Category Counts

| Category | Count |
|---|---|
| SEGMENT | 3 (PELVIS, LUMBAR, CHEST) |
| ARTICULATION | 14 (NECK_END, CLAVICLE_A, SCAPULA_A, SHOULDER_A, ELBOW_A, CLAVICLE_P, SCAPULA_P, SHOULDER_P, ELBOW_P, HIP_F, KNEE_F, ANKLE_F, KNEE_B, ANKLE_B) |
| ATTACHMENT | 10 (HEAD_POS, HAND_A, PALM_A, KNUCKLES_A, FINGERTIPS_A, HAND_P, PALM_P, KNUCKLES_P, FINGERTIPS_P, HEEL_F, TOE_F, HEEL_B, TOE_B) |
| HELPER | 2 (WRIST_A, WRIST_P) |
| UNKNOWN | 0 |

Note: `WRIST_A` and `WRIST_P` are the only entries classified as HELPER. They store rotation in `localRotation` but do not represent anatomical joints — they are procedural nodes that the Finalizer reads to derive extremity geometry. The `ANKLE_F` and `ANKLE_B` entries are classified as ARTICULATION because they carry authored rotation (dorsiflexion/plantar-flexion + inversion/eversion), but they serve the same dual role as the wrist nodes: the Finalizer reads their `localRotation` for foot derivation.

---

## Task 2 — Runtime Ownership: What Does SkeletonNode Represent?

A `SkeletonNode` simultaneously represents **all four** of the following concepts:

### 2.1 Coordinate Frame

Each `SkeletonNode` carries a local coordinate frame (`localPosition` + `localRotation`) and a world coordinate frame (`worldPosition` + `worldRotation`). The `updateWorldTransforms()` method computes the world frame by concatenating the parent's world frame with the node's local frame using full 3D matrix multiplication. This is the primary runtime role — the node is a transform node in a hierarchical frame graph.

### 2.2 Articulation (Joint)

Each `SkeletonNode` is associated with a `Joint` enum entry. The `localRotation` field stores the articulation angle for that joint. The `Joint` enum identity determines what kind of joint it is (ball-and-socket, hinge, etc.) and which biomechanical constraints apply. The solver and validator operate on articulations by reading/writing `SkeletonNode.localRotation`.

### 2.3 Segment (Rigid Body)

Each `SkeletonNode` carries a `localPosition` offset from its parent, which represents the bone length and direction of the segment between this node and its parent. When the solver re-bakes a contact limb, it writes `localPosition` into the middle and end nodes of the chain. The segment is the rigid body that the IK solver treats as a bone with fixed length.

### 2.4 Attachment Host

Child nodes attach to parent nodes to form extremity chains. The wrist node hosts the palm, knuckles, and fingertips. The ankle node hosts the heel and toe. The shoulder node hosts the elbow, hand, wrist, palm, knuckles, and fingertips. The node's children define the attachment topology.

### Conclusion

A single `SkeletonNode` is a **four-in-one abstraction**: coordinate frame + articulation + segment + attachment host. This is the central architectural tension. The node does not distinguish between these roles — it stores all of them in the same fields (`localPosition`, `localRotation`) and the same tree structure.

---

## Task 3 — Parent-Child Semantics

Every parent→child relationship in the `SkeletonNode` tree built by `SkeletonFactory.createStandardSkeleton()`:

| Parent → Child | Models | Evidence |
|---|---|---|
| PELVIS → LUMBAR | **Rigid body** (segment link) | LUMBAR is a segment; its `localPosition` is the bone offset from pelvis. Its `localRotation` is the segment's orientation relative to the pelvis. |
| LUMBAR → CHEST | **Rigid body** (segment link) | CHEST is a segment; same pattern. The two-segment spine means the lumbar and chest are independent rigid bodies. |
| CHEST → NECK_END | **Articulation** | NECK_END carries a rotational DOF (neck flexion/extension/lateral bend). The CHEST→NECK_END link is the joint between two segments. |
| NECK_END → HEAD_POS | **Attachment** | HEAD_POS has no rotation; its position is derived from the neck direction + head length. It is a marker at the end of the neck chain. |
| CHEST → CLAVICLE_A | **Articulation** | CLAVICLE_A carries a rotational DOF (elevation/depression + protraction/retraction). |
| CLAVICLE_A → SCAPULA_A | **Articulation** | SCAPULA_A carries a rotational DOF (scapular rotation). |
| SCAPULA_A → SHOULDER_A | **Articulation** | SHOULDER_A is the glenohumeral joint (3-DOF ball-and-socket). |
| SHOULDER_A → ELBOW_A | **Articulation** | ELBOW_A is a hinge joint (flexion/extension). |
| ELBOW_A → HAND_A | **Attachment** | HAND_A is a terminal marker; its position is set by IK solve. No rotation. |
| HAND_A → WRIST_A | **Helper** | WRIST_A stores authored wrist rotation but is not an anatomical joint — it is a procedural node for grip/pronation derivation. |
| WRIST_A → PALM_A | **Attachment** | PALM_A is a marker derived from wrist + hand definition. |
| PALM_A → KNUCKLES_A | **Attachment** | KNUCKLES_A is a marker derived from palm + finger definition. |
| KNUCKLES_A → FINGERTIPS_A | **Attachment** | FINGERTIPS_A is a terminal marker. |
| CHEST → CLAVICLE_P | **Articulation** | Mirror of CLAVICLE_A. |
| CLAVICLE_P → SCAPULA_P | **Articulation** | Mirror of SCAPULA_A. |
| SCAPULA_P → SHOULDER_P | **Articulation** | Mirror of SHOULDER_A. |
| SHOULDER_P → ELBOW_P | **Articulation** | Mirror of ELBOW_A. |
| ELBOW_P → HAND_P | **Attachment** | Mirror of HAND_A. |
| HAND_P → WRIST_P | **Helper** | Mirror of WRIST_A. |
| WRIST_P → PALM_P | **Attachment** | Mirror of PALM_A. |
| PALM_P → KNUCKLES_P | **Attachment** | Mirror of KNUCKLES_A. |
| KNUCKLES_P → FINGERTIPS_P | **Attachment** | Mirror of FINGERTIPS_A. |
| PELVIS → HIP_F | **Articulation** | HIP_F is the left hip ball-and-socket joint (3-DOF). |
| HIP_F → KNEE_F | **Articulation** | KNEE_F is a hinge joint (flexion/extension). |
| KNEE_F → ANKLE_F | **Articulation** | ANKLE_F is a 2-DOF joint (dorsiflexion/plantar-flexion + inversion/eversion). |
| ANKLE_F → HEEL_F | **Attachment** | HEEL_F is a contact marker derived from ankle + foot definition. |
| ANKLE_F → TOE_F | **Attachment** | TOE_F is a terminal contact marker. |
| PELVIS → HIP_B | **Articulation** | Mirror of HIP_F. |
| HIP_B → KNEE_B | **Articulation** | Mirror of KNEE_F. |
| KNEE_B → ANKLE_B | **Articulation** | Mirror of ANKLE_B. |
| ANKLE_B → HEEL_B | **Attachment** | Mirror of HEEL_F. |
| ANKLE_B → TOE_B | **Attachment** | Mirror of TOE_B. |

### Summary of Relationship Types

| Relationship Type | Count | Examples |
|---|---|---|
| Rigid body (segment link) | 2 | PELVIS→LUMBAR, LUMBAR→CHEST |
| Articulation | 16 | CHEST→NECK_END, CHEST→CLAVICLE_A, SHOULDER_A→ELBOW_A, HIP_F→KNEE_F, etc. |
| Attachment | 12 | NECK_END→HEAD_POS, ELBOW_A→HAND_A, PALM_A→KNUCKLES_A, ANKLE_F→HEEL_F, etc. |
| Helper | 2 | HAND_A→WRIST_A, HAND_P→WRIST_P |

Note: The CHEST→NECK_END relationship is classified as an articulation, but NECK_END→HEAD_POS is classified as an attachment. This means the neck chain has an articulation followed by an attachment — the HEAD_POS is not a joint but a marker at the end of the articulation chain.

---

## Task 4 — Subsystem Responsibility Table

| Subsystem | Reads | Writes |
|---|---|---|
| **Pose.build()** (`BasePose` + `PoseBuilder`) | `SkeletonDefinition` (bone lengths, constraints), `SkeletonFactory` node tree topology, `PoseContext` (progress, side, deltaTime) | `SkeletonNode.localPosition`, `SkeletonNode.localRotation` (authored by pose helpers); `SkeletonPose.joints`, `SkeletonPose.rotations`, `SkeletonPose.roots` (via `fromHierarchy`); `SkeletonPose.contacts` (via `bakeIkLimb`), `SkeletonPose.limbTargets`, `SkeletonPose.jointIntents`, `SkeletonPose.spineIntent`, `SkeletonPose.postureIntent`, `SkeletonPose.extremityOverrides`, `SkeletonPose.extremityArticulations`, `SkeletonPose.headTarget`, `SkeletonPose.headings`, `SkeletonPose.environment`, `SkeletonPose.supportedPoints` |
| **IkStage.apply()** | `SkeletonPose.limbTargets`, `SkeletonDefinition` (bone lengths, IK constraints), `SkeletonPose.roots` (SkeletonNode tree) | `SkeletonNode.localPosition` (middle and end nodes of each limb target); `SkeletonPose.boneLengthsVerified`, `SkeletonPose.maxIkClampAmount` |
| **ConstraintSolver.solve()** | `SkeletonPose.contacts`, `SkeletonPose.postureIntent`, `SkeletonPose.contactPrecedence`, `SkeletonPose.roots` (SkeletonNode tree), `SkeletonDefinition` (bone lengths, IK constraints) | `SkeletonNode.localPosition` (pelvis, contact limb middle/end nodes), `SkeletonNode.localRotation` (pelvis tilt); `SkeletonPose.rootTranslationDelta`, `SkeletonPose.rootRotationDelta`, `SkeletonPose.boneLengthsVerified`; `SkeletonPose.hipRomStamps` (via `fromHierarchy` call at end) |
| **SkeletonPoseFinalizer.finalize()** | `SkeletonPose.roots` (SkeletonNode tree), `SkeletonDefinition`, `SkeletonPose.jointIntents`, `SkeletonPose.spineIntent`, `SkeletonPose.extremityArticulations`, `SkeletonPose.headTarget`, `SkeletonPose.environment`, `SkeletonPose.supportedPoints`, `SkeletonPose.contacts` | `SkeletonNode.localRotation` (chest-frame reconstruction, head-target resolution); `SkeletonPose.joints` (all joint positions including derived heel/toe/palm/fingertips); `SkeletonPose.rotations` (all joint rotations); `SkeletonPose.hipRomStamps`, `SkeletonPose.bilateralSymmetryDelta`, `SkeletonPose.bilateralOppositeBend`; `SkeletonPose.rootTranslationDelta` (flagged when reconstruction displaces contacts) |
| **ExerciseValidator.validate()** | `SkeletonPose` (all joints, rotations, contacts, stamps), `SkeletonDefinition` (bone lengths, IK constraints, angular limits, hip ROM limits), `EnvironmentDefinition`, `Camera`, `previousPose`, `prePreviousPose` | `ValidationReport`, `ValidationResult` list, `ValidationIssue` list (all newly allocated) |
| **SkeletonRenderer** / **SkeletonSnapshotRenderer** | Finalized `SkeletonPose` (joints + rotations), `SkeletonEngine` (bone list, style), `Camera`, `EnvironmentDefinition` | `ProjectedSkeleton` (joints, bones, faces, grid lines, shadow points), `Bitmap` / Compose drawing commands |
| **SkeletonProjector** | Finalized `SkeletonPose` (joints + rotations), `SkeletonEngine` (bone list), `Camera` | `ProjectedSkeleton` (projected joint points, bones, faces, grid lines, shadow points) |
| **SkeletonPipeline** | `SkeletonDefinition`, `SkeletonPoseFinalizer`, `ExerciseValidator` (optional) | `SkeletonPose` (mutates `environment` and `supportedPoints` on input pose in `produceFrame` overload), `previous`/`prePrevious` pose history |

---

## Task 5 — Naming Inconsistencies

### 5.1 Joint meaning articulation

The `Joint` enum is named as if every entry represents a joint (an articulation with DOF). In reality, entries like `PELVIS`, `CHEST`, `LUMBAR` are body segments (rigid bodies), and entries like `HEAD_POS`, `HEEL_F`, `PALM_A` are attachment markers with no DOF. The name "Joint" implies articulation for all entries.

### 5.2 Joint meaning body segment

`PELVIS`, `LUMBAR`, and `CHEST` are body segments — rigid bodies with position and orientation. They are not joints in the biomechanical sense (they do not have independent rotational DOF relative to their parent in the way articulations do). Yet they are stored in the same `Joint` enum and indexed the same way in `SkeletonPose.joints` and `SkeletonPose.rotations`.

### 5.3 Joint meaning attachment

`HEAD_POS`, `HAND_A`, `HAND_P`, `PALM_A`, `PALM_P`, `FINGERTIPS_A`, `FINGERTIPS_P`, `HEEL_F`, `HEEL_B`, `TOE_F`, `TOE_B`, `KNUCKLES_A`, `KNUCKLES_P` are attachment markers or end-effectors. They have no authored rotation and their positions are derived. Yet they are `Joint` enum entries with the same semantic weight as articulations.

### 5.4 Bone meaning rendering primitive

The `Bone` data class (`parentJoint`, `childJoint`, `thickness`, `colorMultiplier`) is a rendering primitive — it defines which joints to draw a line between and with what visual weight. It is not a biomechanical concept. Yet it lives in the same package as the simulation objects and uses `Joint` enum values as its endpoints, implying a biomechanical relationship where there is only a visual one.

### 5.5 SkeletonPose meaning runtime state

`SkeletonPose` is named as if it represents a single pose state. In reality, it carries both intent (§1.1: contacts, limbTargets, jointIntents, spineIntent, postureIntent, headTarget, headings, extremityOverrides, extremityArticulations) and derived state (§1.2: joints, rotations, roots, boneLengthsVerified, rootTranslationDelta, hipRomStamps, bilateralSymmetryDelta). The name does not distinguish between what the pose author declared and what the engine computed.

### 5.6 SkeletonNode meaning coordinate frame

`SkeletonNode` is named as if it represents a node in a skeleton hierarchy. In reality, it is a coordinate frame container that also carries articulation data (`localRotation`), segment data (`localPosition`), and attachment topology (children). The name implies a structural role but the runtime role is a transform container with biomechanical annotations.

### 5.7 SkeletonFactory meaning skeleton builder

`SkeletonFactory` is named as if it builds a skeleton. In reality, it builds a `SkeletonNodes` container (a named tuple of 33 `SkeletonNode` references) and a `SkeletonNode` tree. The factory does not produce a `SkeletonPose` — it produces the FK substrate that `PoseBuilder.build()` populates.

### 5.8 SkeletonNodes meaning node collection

`SkeletonNodes` (the data class, distinct from `SkeletonNode`) is a convenience container that exposes all 33 nodes by name. It is not a runtime object — it is a build-time convenience. Yet it is returned by `SkeletonFactory.createStandardSkeleton()` and used by pose authors to reference nodes by name (e.g., `nodes.shoulderA`), coupling the authoring API to the internal node topology.

### 5.9 ConstraintSolver meaning contact solver

`ConstraintSolver` is named as if it solves constraints. In reality, it does three things: (1) repositions the root/pelvis to satisfy contact reachability, (2) re-bakes contact limbs from the moved root, and (3) runs a CCD posture pass for over-constrained poses. The name understates its scope — it is a root-repositioning and posture-settling layer, not a general constraint solver.

### 5.10 SkeletonPoseFinalizer meaning pose finalizer

`SkeletonPoseFinalizer` is named as if it finalizes a pose. In reality, it performs five distinct operations: (1) FK traversal + flatten, (2) intent carrier re-application (B2), (3) chest-frame reconstruction (F1), (4) head-target resolution (Phase 7), and (5) extremity orientation derivation (W1) + validation stamp production (B5). The name implies a single finalization step but the class is a multi-stage processor.

### 5.11 BasePose meaning base pose

`BasePose` is named as if it is a base class for poses. In reality, it is a utility class that provides body construction helpers (`buildTorso`, `buildPelvis`, `buildShoulders`, `buildSpineCurve`, `buildChestTwist`, `buildHipFlexion`, `buildHipRotation`, `buildClavicularRotation`, `buildWristArticulation`, `buildAnkleArticulation`), IK wrappers (`bakeIkLimb`), motion drivers, and support definitions. It is not a pose — it is a collection of authoring helpers.

### 5.12 JointRotation meaning joint rotation

`JointRotation` is named as if it represents a joint's rotation. In reality, it is a generic axis-angle rotation used for both local and world rotations on `SkeletonNode`, for the flat rotation array in `SkeletonPose`, and for scratch buffers in the solver and finalizer. It does not carry joint identity — it is a pure rotation data type that happens to be used for joint rotations.

---

## Task 6 — Ideal Conceptual Model

### 6.1 Segment

**Responsibility:** Represents a rigid body in the biomechanical chain. Has a length, a start articulation, an end articulation, and a pose (position + orientation). The segment is the object that the IK solver treats as a bone with fixed length. The validator checks segment length invariance. The renderer draws a bone between the segment's start and end points.

**Why it exists:** In biomechanics, a segment is the rigid body between two joints (e.g., the upper arm is the segment between the shoulder and elbow). The current model conflates segments with articulations in `SkeletonNode`. A dedicated `Segment` concept would separate the rigid body (length, pose) from the joint (rotation, DOF).

### 6.2 Articulation

**Responsibility:** Represents a joint with independent degrees of freedom. Has a joint type (ball-and-socket, hinge, pivot), angular limits, and a local rotation relative to the parent segment. The solver operates on articulations (IK solves target articulations). The validator checks articulation limits (angular joint limits, hip ROM). The pose author declares articulation intent (`jointIntents`, `extremityArticulations`).

**Why it exists:** The current model stores articulation data (`localRotation`) in `SkeletonNode` alongside segment data (`localPosition`) and frame data (`worldPosition`, `worldRotation`). A dedicated `Articulation` concept would separate the joint angle from the transform and the bone length.

### 6.3 Attachment

**Responsibility:** Represents a fixed point on a segment used for environment interaction (contacts) or as a rendering endpoint. Has a parent segment reference, a local offset, and an attachment type (heel, toe, palm, fingertip, knee, elbow). Attachments do not have independent DOF — their position is derived from the segment's pose and the attachment's local offset.

**Why it exists:** The current model uses `Joint` enum entries like `HEEL_F`, `PALM_A`, `FINGERTIPS_A` as attachment markers, but they share the same namespace and data structure as articulations. A dedicated `Attachment` concept would make it explicit that these points have no DOF and their positions are derived.

### 6.4 EndEffector

**Responsibility:** Represents the terminal point of a limb chain. It is the IK solve target and the contact point for support detection. End-effectors are a subset of attachments that are specifically the terminal nodes of IK chains.

**Why it exists:** The current model uses `HAND_A`, `HAND_P`, `FINGERTIPS_A`, `FINGERTIPS_P`, `TOE_F`, `TOE_B` as end-effectors, but they are classified as `ATTACHMENT` in the `Joint` enum. A dedicated `EndEffector` concept would distinguish terminal IK targets from intermediate attachment markers.

### 6.5 Helper (Rig Helper)

**Responsibility:** Represents a procedural node that exists to support the rigging/IK system but does not represent a physical joint or body part. Helpers carry authored rotation for grip/foot plant but are not anatomical joints — they are procedural intermediaries between the IK solve target and the visual endpoint.

**Why it exists:** The current model uses `WRIST_A`, `WRIST_P`, `ANKLE_F`, `ANKLE_B` as articulations, but they serve as rig helpers — they store rotation for the Finalizer's extremity derivation but are not true anatomical joints. A dedicated `Helper` concept would make this role explicit.

### 6.6 CoordinateFrame

**Responsibility:** Represents a local coordinate frame (position + orientation) in the hierarchy. It is the engineering substrate for FK computation. It carries no biomechanical identity — it does not know whether it represents an articulation, a segment, or an attachment. The biomechanical identity is provided by a separate mapping.

**Why it exists:** The current `SkeletonNode` conflates the coordinate frame with biomechanical identity (the `joint: Joint` field). A dedicated `CoordinateFrame` concept would separate the transform computation from the biomechanical semantics.

### 6.7 SkeletonPose (Snapshot)

**Responsibility:** A flat, index-based map of transforms at a single point in time. Carries both intent (what the pose author declared) and derived state (what the engine computed). The snapshot is the data contract between pipeline stages.

**Why it exists:** The current `SkeletonPose` already serves this role, but the intent/state separation is convention-based rather than structural. A dedicated `SkeletonPose` concept with explicit intent and state sections would make the ownership boundary between pose author and engine explicit.

### 6.8 SkeletonDefinition (Metadata)

**Responsibility:** Anatomical metadata: bone lengths, proportions, constraints, angular limits. Read-only configuration.

**Why it exists:** The current `SkeletonDefinition` already serves this role correctly. It is the single source of truth for anatomical measurements and constraints.

---

## Task 7 — Compatibility Assessment

**YES**

The existing implementation could be migrated incrementally without breaking the pipeline. The reasons are:

1. **The `Joint` enum is an index, not a type.** Every `Joint` entry maps to an ordinal that indexes into the `SkeletonPose.joints` and `SkeletonPose.rotations` arrays. The enum values are used as array indices throughout the pipeline. Adding new categories or splitting the enum would not change the ordinal values of existing entries, so the flat array contract would remain intact.

2. **The `SkeletonNode` tree is an FK substrate, not a biomechanical model.** The tree structure (parent/children) and the FK computation (`updateWorldTransforms`, `flatten`) are independent of whether a node represents a segment, articulation, or attachment. The tree topology could be annotated with category metadata without changing the FK math.

3. **The pipeline stages are contract-based, not type-based.** Each stage reads and writes `SkeletonPose` fields by name (e.g., `pose.contacts`, `pose.limbTargets`, `pose.jointIntents`). The stages do not inspect the `Joint` enum values to determine behavior — they iterate over the flat arrays or the node tree. Adding category metadata to `Joint` would not change the stage contracts.

4. **The `SkeletonPoseFinalizer` is the only stage that inspects `Joint` identity.** It uses `findJointNode()` to locate specific nodes by `Joint` enum value (e.g., `Joint.PELVIS`, `Joint.CHEST`, `Joint.SHOULDER_A`). These lookups would still work if the enum values were annotated with categories rather than renamed.

5. **The `ConstraintSolver.chainForEnd()` maps `Joint` to `ContactChain`.** This mapping is a lookup table, not a type-dependent computation. It could be extended or refactored without changing the solver's core logic.

6. **The `ExerciseValidator` validates by `Joint` enum value.** The bone-length validation, IK constraint validation, and dynamics validation all iterate over `Joint.entries` or specific `Joint` values. These would continue to work with categorized enum values.

7. **The `SkeletonEngine.bones` list uses `Joint` enum values.** The rendering bone list references `Joint` values as parent-child pairs. This list could be derived from a categorized skeleton definition rather than hardcoded, without changing the rendering pipeline.

The migration path would be additive: annotate the existing `Joint` enum with category metadata, introduce new conceptual types (Segment, Articulation, Attachment, Helper) as views or wrappers over the existing data, and gradually move responsibilities into the new types. The existing pipeline would continue to function at each step because the underlying data contracts (flat arrays, node tree, intent carriers) remain unchanged.