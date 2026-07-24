# Architectural Audit: Monk Fitness v2 Skeleton Model

> **No code changes proposed.** This document documents the runtime architecture as it currently exists, to inform any future redesign.

---

## 1. What Is Actually Simulated?

The true runtime simulation object is **`SkeletonPose`** — a flat, index-based joint map of 3D positions (`Array<Vector3>`) and rotations (`Array<JointRotation>`), sized to `Joint.entries.size` (33 entries). It is the single carrier of both pose intent (§1.1, written by Pose authors) and derived state (§1.2, written by the engine).

Every other major class serves a role in producing or consuming this flat joint map:

| Class | Runtime Role | Category |
|---|---|---|
| `SkeletonDefinition` | Anatomical metadata (bone lengths, proportions, constraints). Read-only config. | **Metadata only** |
| `SkeletonFactory` | Builds the `SkeletonNode` hierarchy tree from `Joint` enum entries. One-time construction. | **Metadata only** (creates the FK tree) |
| `SkeletonNode` | Mutable hierarchical transform node: local position/rotation, parent/children, world-position/rotation computed by FK. The **runtime simulation object** for the tree. | **Articulation + Segment + Solver node** (mixed) |
| `SkeletonPose` | Flat joint map (positions + rotations) + intent carriers (contacts, limbTargets, jointIntents, etc.) + state stamps. The **frame snapshot**. | **Frame snapshot** |
| `ConstraintSolver` | Global contact-constraint / root-repositioning layer. Repositions the pelvis and re-bakes contact limbs. Stateless object (`object`). | **Solver node** |
| `SkeletonPoseFinalizer` | Completes the 3D pose: FK traversal, chest-frame reconstruction, head-target resolution, extremity orientation derivation, validation stamps. Owns the output buffer. | **Frame processor** |
| `ExerciseValidator` | Read-only biomechanical validation on a `SkeletonPose`. 17 rules (finite coords, bone lengths, viewport, ground penetration, dynamics, symmetry, hip ROM, etc.). Stateless per-config. | **Validator** |
| `SkeletonRenderer` / `SkeletonSnapshotRenderer` | Projects the finalized `SkeletonPose` into screen space and draws bones/joints/faces. Passive rendering. | **Renderer** |
| `PoseBuilder` (interface) / `BasePose` (abstract) | Pose authoring API. Declares intent (joint rotations, contacts, posture, headings, extremity overrides). Produces a `SkeletonPose` via `build(context)`. | **Intent author** |
| `SkeletonPipeline` | Orchestrator. Owns the stage instances (`SkeletonPoseFinalizer`, optional `ExerciseValidator`) and per-frame history. Drives the ordered stage chain. | **Pipeline orchestrator** |
| `SkeletonEngine` | Bone hierarchy for rendering (a `List<Bone>` of parent-child joint pairs + style params). Static definition. | **Metadata only** (rendering bone list) |
| `Bone` | A single rendering bone: parentJoint → childJoint + thickness + colorMultiplier. | **Rendering bone** |
| `Joint` (enum) | Enum entries that index into the flat `SkeletonPose` joint arrays. Each entry has a fixed ordinal used as an array index. | **Joint identifier** (not a runtime object) |

### Key Insight

The runtime simulation is a **two-layer system**:

1. **Tree layer** (`SkeletonNode`): a mutable hierarchy of transforms used for FK computation. This is the "working memory" of the simulation.
2. **Flat layer** (`SkeletonPose`): a compact, index-based joint map that is the input/output contract between stages. This is the "frame snapshot."

The `SkeletonNode` tree is **ephemeral** — it is created by `SkeletonFactory`, populated by `PoseBuilder.build()`, consumed by FK traversal in the Finalizer, and then discarded (or reused). The `SkeletonPose` is the durable artifact that passes between pipeline stages.

---

## 2. What Does Every Joint Enum Entry Actually Represent?

| Joint | Runtime Meaning | Category |
|---|---|---|
| `PELVIS` | Body segment root. World position = pelvis center. Rotation = pelvis orientation (hip tilt, anterior/posterior tilt). | **Segment** |
| `LUMBAR` | Lower spine segment between PELVIS and CHEST. Pass-through by default (identity rotation, coincident with pelvis). Carries independent lumbar DOF when authored. | **Segment** |
| `CHEST` | Thorax segment. Root of the upper body. Rotation = thoracic twist/side-bend/flex. | **Segment** |
| `NECK_END` | Articulation joint between CHEST and HEAD. Rotation = neck flexion/extension/lateral bend. | **Articulation** |
| `HEAD_POS` | Head position marker. Not a true articulation — its position is derived from the neck direction + head length. Rotation is not independently authored. | **Attachment / Marker** |
| `CLAVICLE_A` | Left shoulder girdle (clavicle). Rotation = elevation/depression + protraction/retraction. | **Articulation** |
| `SCAPULA_A` | Left scapula. Rotation = scapular upward/downward rotation + protraction/retraction. | **Articulation** |
| `SHOULDER_A` | Left glenohumeral joint (shoulder). IK root for the left arm. Rotation = shoulder abduction/adduction + flexion/extension + rotation. | **Articulation** |
| `ELBOW_A` | Left elbow. Articulation with flexion/extension only (hinge). | **Articulation** |
| `HAND_A` | Left hand end-effector. Position derived from IK solve. | **Attachment** |
| `WRIST_A` | Left wrist. Carries authored wrist articulation (grip, pronation/supination). | **Articulation** |
| `PALM_A` | Left palm. Position derived from wrist + hand definition. | **Attachment** |
| `KNUCKLES_A` | Left knuckles. Position derived from palm + finger definition. | **Attachment** |
| `FINGERTIPS_A` | Left fingertips. Terminal attachment. | **Attachment** |
| `CLAVICLE_P` | Right shoulder girdle (clavicle). Mirror of CLAVICLE_A. | **Articulation** |
| `SCAPULA_P` | Right scapula. Mirror of SCAPULA_A. | **Articulation** |
| `SHOULDER_P` | Right glenohumeral joint. Mirror of SHOULDER_A. | **Articulation** |
| `ELBOW_P` | Right elbow. Mirror of ELBOW_A. | **Articulation** |
| `HAND_P` | Right hand end-effector. Mirror of HAND_A. | **Attachment** |
| `WRIST_P` | Right wrist. Mirror of WRIST_A. | **Articulation** |
| `PALM_P` | Right palm. Mirror of PALM_A. | **Attachment** |
| `KNUCKLES_P` | Right knuckles. Mirror of KNUCKLES_A. | **Attachment** |
| `FINGERTIPS_P` | Right fingertips. Mirror of FINGERTIPS_A. | **Attachment** |
| `HIP_F` | Left hip ball-and-socket joint. Articulation with flexion/extension + abduction/adduction + internal/external rotation. | **Articulation** |
| `KNEE_F` | Left knee. Hinge articulation with flexion/extension. | **Articulation** |
| `ANKLE_F` | Left ankle. Articulation with dorsiflexion/plantar-flexion + inversion/eversion. | **Articulation** |
| `HEEL_F` | Left heel attachment. Position derived from ankle + foot definition. | **Attachment** |
| `TOE_F` | Left toe attachment. Terminal attachment. | **Attachment** |
| `HIP_B` | Right hip. Mirror of HIP_F. | **Articulation** |
| `KNEE_B` | Right knee. Mirror of KNEE_F. | **Articulation** |
| `ANKLE_B` | Right ankle. Mirror of ANKLE_F. | **Articulation** |
| `HEEL_B` | Right heel. Mirror of HEEL_F. | **Attachment** |
| `TOE_B` | Right toe. Mirror of TOE_F. | **Attachment** |

### Mixed-Concept Entries

- **`HEAD_POS`**: Listed as an "articulation" in the hierarchy (it is a child of NECK_END), but it has no independent rotation — its position is purely derived from the neck direction + a fixed head length (18f). It is a **marker/attachment**, not a true articulation.
- **`WRIST_A` / `WRIST_P`**: These are listed as joints in the enum and appear in the hierarchy, but they serve dual duty: they are both **articulation points** (carrying authored wrist rotation) and **attachment hosts** (the palm/fingertips derive their positions from the wrist). The architecture conflates the two roles.
- **`HEEL_F` / `HEEL_B` / `TOE_F` / `TOE_B`**: These are terminal nodes with no children and no authored rotation. They are purely **attachment/marker** nodes whose positions are derived by the Finalizer's extremity orientation logic. They exist in the enum and hierarchy but are not articulations.

---

## 3. Are SkeletonNode Responsibilities Mixed?

### Every Responsibility of SkeletonNode

1. **Hierarchy node** — parent/children tree structure, `addChild()` method.
2. **Articulation** — carries a `Joint` enum identity and a `localRotation` (axis-angle) that represents the joint's rotation relative to its parent.
3. **Segment** — carries a `localPosition` offset from its parent, representing the bone length/direction.
4. **World transform computation** — `updateWorldTransforms()` computes `worldPosition` and `worldRotation` by concatenating parent transforms with local transforms (full 3D matrix multiplication).
5. **FK traversal** — recursively propagates world transforms to all descendants.
6. **Flattening** — `flatten()` writes `worldPosition` and `worldRotation` into the flat `SkeletonPose` arrays by joint index.
7. **Attachment host** — child nodes (wrists, palms, fingertips, heels, toes) attach to articulation nodes to form the extremity chain.
8. **IK target resolution** — the node tree is the substrate on which IK solves operate; `bakeIkLimb` writes `localPosition` offsets into middle/end nodes.
9. **Solver settlement** — after `ConstraintSolver.solve()`, the node tree holds the solver-adjusted local transforms.
10. **Validation carrier** — the node tree is read by the Finalizer to compute validation stamps (hip ROM, bilateral symmetry).
11. **Chest-frame reconstruction** — the Finalizer reads/writes `chest.localRotation` to reconstruct the thoracic frame when unauthored.
12. **Head-target resolution** — the Finalizer reads/writes `neck.localPosition` and `head.localPosition` to extend the neck along the gaze direction.
13. **Extent orientation derivation** — the Finalizer reads wrist/ankle `localRotation` to compute the relative articulation for heel/toe/palm/fingertip geometry.

### SRP Violation Assessment

**Yes, SkeletonNode violates the Single Responsibility Principle.** It simultaneously serves as:

- A **hierarchy node** (tree structure, parent/children)
- An **articulation** (joint rotation)
- A **segment** (bone length/direction via localPosition)
- An **attachment host** (parent for endpoint nodes)
- A **world transform carrier** (worldPosition, worldRotation)
- An **IK target substrate** (localPosition written by IK solver)
- A **validation data source** (read by Finalizer for stamps)

The node is a **Swiss Army knife** that conflates the biomechanical concept of a joint (articulation) with the engineering concept of a transform node (hierarchy + FK) with the rendering concept of a bone segment (localPosition as offset). This is the central architectural tension in the system.

---

## 4. Runtime Ownership Graph

```
SkeletonFactory (object)
│
├── Creates: SkeletonNode tree (hierarchy of 33 nodes)
│   │
│   ├── Each SkeletonNode owns:
│   │   ├── localPosition: Vector3 (segment offset)
│   │   ├── localRotation: JointRotation (articulation)
│   │   ├── worldPosition: Vector3 (computed by FK)
│   │   ├── worldRotation: JointRotation (computed by FK)
│   │   ├── parent: SkeletonNode?
│   │   └── children: MutableList<SkeletonNode>
│   │
│   └── Tree root: PELVIS
│
▼
PoseBuilder.build(context) → SkeletonPose
│   ├── joints: Array<Vector3> (flat joint positions)
│   ├── rotations: Array<JointRotation> (flat joint rotations)
│   ├── roots: List<SkeletonNode> (the FK tree)
│   ├── contacts: MutableList<ContactSpec> (fixed support contacts)
│   ├── limbTargets: MutableList<WorldTarget> (IK targets)
│   ├── jointIntents: MutableList<RelativeArticulation> (per-joint rotation intents)
│   ├── spineIntent: SpineCurve (declarative spine curve)
│   ├── postureIntent: PostureIntent (coarse posture kind)
│   ├── extremityOverrides: MutableSet<Extremity> (manual override flags)
│   ├── extremityArticulations: MutableMap<Extremity, JointRotation> (wrist/ankle rotations)
│   ├── headTarget: HeadTarget? (gaze target)
│   ├── headings: MutableMap<Extremity, Vector3> (root-relative heading directions)
│   ├── environment: EnvironmentDefinition (ground + props)
│   ├── supportedPoints: MutableSet<SupportPoint> (which body points rest on what)
│   └── state stamps: boneLengthsVerified, rootTranslationDelta, rootRotationDelta,
│       hipRomStamps, bilateralSymmetryDelta, bilateralOppositeBend, straightIntentDropped
│
▼
SkeletonPipeline.runStages(builtPose)
│
├── Stage 1: IkStage.apply(pose, definition)
│   └── Consumes pose.limbTargets, writes middle/end localPosition on SkeletonNode tree
│
├── Stage 2: ConstraintSolver.solve(pose, definition)
│   ├── Reads: pose.contacts, pose.postureIntent, pose.roots (SkeletonNode tree)
│   ├── Mutates: pelvis.localPosition, pelvis.localRotation, all contact limb node localPositions
│   ├── Writes: pose.rootTranslationDelta, pose.rootRotationDelta, pose.boneLengthsVerified
│   └── Calls: SkeletonPose.fromHierarchy(roots, pose) — FK + flatten into pose
│
├── Stage 3: SkeletonPoseFinalizer.finalize(pose)
│   ├── Reads: pose.roots (SkeletonNode tree), pose.jointIntents, pose.spineIntent,
│   │   pose.extremityArticulations, pose.headTarget, pose.environment, pose.supportedPoints
│   ├── Mutates: SkeletonNode localRotations (chest-frame reconstruction, head-target resolution)
│   ├── Mutates: SkeletonPose joint positions (extremity derivation: heel/toe/palm/fingertips)
│   ├── Writes: pose.hipRomStamps, pose.bilateralSymmetryDelta, pose.bilateralOppositeBend
│   └── Returns: outputPose (a new SkeletonPose with finalized positions + rotations)
│
▼
SkeletonRenderer / SkeletonSnapshotRenderer
│   ├── Reads: finalized SkeletonPose (joints + rotations)
│   ├── Uses: SkeletonEngine.bones (rendering bone list), SkeletonProjector
│   └── Produces: Bitmap / Compose drawing commands
│
▼
ExerciseValidator (optional, in produceFrameValidated)
    ├── Reads: finalized SkeletonPose, SkeletonDefinition, EnvironmentDefinition, Camera
    ├── Reads: previousPose, prePreviousPose (for dynamics)
    └── Produces: ValidationReport (17 rules)
```

### Object Lifetime Summary

| Object | Created By | Lives In | Destroyed By |
|---|---|---|---|
| `SkeletonNode` tree | `SkeletonFactory.createStandardSkeleton()` / `createPushUpSkeleton()` | `SkeletonPose.roots` (transient) | GC after frame; nodes are reused across frames via `copyFrom` |
| `SkeletonPose` | `PoseBuilder.build()` or `SkeletonPose()` + `copyFrom` | Pipeline local / renderer | GC after frame; `previous`/`prePrevious` history keeps 2 frames alive |
| `SkeletonPipeline` | Created by `SkeletonRenderer` (via `remember`) or `SkeletonSnapshotRenderer` | Long-lived, per-engine/per-definition | GC when engine is disposed |
| `SkeletonPoseFinalizer` | Created by `SkeletonPipeline` constructor | Long-lived, owned by pipeline | GC when pipeline is disposed |
| `ExerciseValidator` | Created by caller, passed to `SkeletonPipeline` constructor | Long-lived, owned by pipeline | GC when pipeline is disposed |
| `SkeletonEngine` | Created by caller | Long-lived, per-definition | GC when engine is disposed |
| `Joint` enum entries | JVM class loading | Permanent (enum singleton) | Never |
| `SkeletonDefinition` | `HumanSkeletonDefinition()` or custom | Long-lived, per-definition | GC when definition is disposed |

---

## 5. Pipeline Stages

### Stage 0: Pose.build() (PoseBuilder → SkeletonPose)

| Aspect | Detail |
|---|---|
| **Input** | `PoseContext` (progress, side, deltaTime, playbackSpeed, mirrored, phase, loopIndex) |
| **Output** | `SkeletonPose` with `joints` (positions), `rotations` (local rotations), `roots` (SkeletonNode tree), intent carriers populated |
| **Fields mutated** | `SkeletonNode.localPosition`, `SkeletonNode.localRotation` (authored by pose helpers); `SkeletonPose.joints`, `SkeletonPose.rotations`, `SkeletonPose.roots`, `SkeletonPose.contacts`, `SkeletonPose.limbTargets`, `SkeletonPose.jointIntents`, `SkeletonPose.spineIntent`, `SkeletonPose.postureIntent`, `SkeletonPose.extremityOverrides`, `SkeletonPose.extremityArticulations`, `SkeletonPose.headTarget`, `SkeletonPose.headings`, `SkeletonPose.environment`, `SkeletonPose.supportedPoints` |
| **Read-only objects** | `SkeletonDefinition` (bone lengths, constraints), `SkeletonFactory` node tree topology |
| **Objects recreated** | None — the pose reuses the `SkeletonPose` buffer; `SkeletonNode` tree is pre-built by factory |

### Stage 1: IkStage.apply() (pipeline-owned limb IK)

| Aspect | Detail |
|---|---|
| **Input** | `pose.limbTargets` (WorldTarget list), `SkeletonDefinition` (bone lengths, IK constraints) |
| **Output** | `SkeletonNode.localPosition` updates for middle/end nodes of each limb |
| **Fields mutated** | `SkeletonNode.localPosition` (middle and end nodes of each limb target) |
| **Read-only objects** | `pose.roots` (SkeletonNode tree), `SkeletonDefinition`, `pose.limbTargets` |
| **Objects recreated** | None |
| **Note** | Gated by `IK_STAGE_ACTIVE` flag (default `false`). When off, this stage is a no-op and `bakeIkLimb` remains the sole solver. |

### Stage 2: ConstraintSolver.solve() (contact settlement + root repositioning)

| Aspect | Detail |
|---|---|
| **Input** | `pose.contacts` (ContactSpec list), `pose.postureIntent`, `pose.roots` (SkeletonNode tree), `SkeletonDefinition` |
| **Output** | Mutated `SkeletonNode` tree (pelvis position/rotation, contact limb localPositions); `SkeletonPose` state stamps updated |
| **Fields mutated** | `pelvis.localPosition`, `pelvis.localRotation`, all contact limb `SkeletonNode.localPosition`, `pose.rootTranslationDelta`, `pose.rootRotationDelta`, `pose.boneLengthsVerified`, `pose.hipRomStamps` (via `fromHierarchy` call at end) |
| **Read-only objects** | `SkeletonDefinition`, `pose.contacts`, `pose.postureIntent`, `pose.contactPrecedence` |
| **Objects recreated** | None — operates in-place on the node tree and pose |
| **Key behavior** | Root is repositioned to bring out-of-band contacts into reachability band; contact limbs are re-baked from the moved root; posture pass (CCD) distributes residual across free joint angles; pelvis tilt absorbs asymmetric reach residuals |

### Stage 3: SkeletonPoseFinalizer.finalize() (FK + flatten + extremity derivation)

| Aspect | Detail |
|---|---|
| **Input** | `pose.roots` (SkeletonNode tree with solver-settled transforms), `SkeletonDefinition`, `pose` intent carriers |
| **Output** | New `SkeletonPose` (`outputPose`) with finalized world positions + rotations for all joints, including derived extremity geometry |
| **Fields mutated** | `SkeletonNode.localRotation` (chest-frame reconstruction, head-target resolution); `SkeletonPose.joints` (all joint positions including derived heel/toe/palm/fingertips); `SkeletonPose.rotations` (all joint rotations); `SkeletonPose.hipRomStamps`, `SkeletonPose.bilateralSymmetryDelta`, `SkeletonPose.bilateralOppositeBend` |
| **Read-only objects** | `pose.roots`, `SkeletonDefinition`, `pose.extremityArticulations`, `pose.headTarget`, `pose.environment`, `pose.supportedPoints`, `pose.contacts` |
| **Objects recreated** | `outputPose` (new `SkeletonPose` via `copyFrom` then overwrite) |
| **Sub-stages** | (a) FK traversal + flatten, (b) intent carrier re-application (B2), (c) chest-frame reconstruction (F1), (d) head-target resolution (Phase 7), (e) foot orientation derivation (W1), (f) hand orientation derivation (W1), (g) validation stamp production (B5) |

### Stage 4 (optional): ExerciseValidator.validate()

| Aspect | Detail |
|---|---|
| **Input** | Finalized `SkeletonPose`, `SkeletonDefinition`, `EnvironmentDefinition`, `Camera`, `previousPose`, `prePreviousPose`, `deltaTime` |
| **Output** | `ValidationReport` (17 rules, each with isValid + issues) |
| **Fields mutated** | None (fully read-only on the pose) |
| **Read-only objects** | All pose data, definition, environment, camera, previous poses |
| **Objects recreated** | `ValidationReport`, `ValidationResult` list, `ValidationIssue` list |

---

## 6. Segment vs Articulation Confusion

### Where the Architecture Mixes Concepts

The current architecture mixes **segments**, **articulations**, **attachments**, **rendering bones**, and **solver nodes** inside the same `SkeletonNode` abstraction. Here are the specific locations:

#### 6.1 SkeletonNode conflates Segment + Articulation

A `SkeletonNode` simultaneously represents:
- A **segment** (body part with length): it carries `localPosition` (the bone offset from parent) and `localRotation` (the joint angle).
- An **articulation** (joint with DOF): the `localRotation` is the joint's rotation, and the `Joint` enum identity determines what kind of joint it is.

In biomechanics, a **segment** is the rigid body between two joints (e.g., the upper arm is the segment between the shoulder and elbow joints), while an **articulation** is the joint itself (e.g., the shoulder joint). The current model puts both into one object: the node at the shoulder IS the articulation, but it also carries the segment offset (upper arm length) to the elbow.

#### 6.2 The Joint enum mixes attachment markers with articulations

The `Joint` enum contains entries that are clearly articulations (HIP_F, KNEE_F, SHOULDER_A, ELBOW_A) and entries that are clearly attachment markers (HEAD_POS, HEEL_F, TOE_F, PALM_A, FINGERTIPS_A). The enum does not distinguish between these categories — they all share the same ordinal-based indexing into the flat `SkeletonPose` arrays.

#### 6.3 SkeletonEngine.bones mixes rendering bones with the joint hierarchy

`SkeletonEngine` defines a `List<Bone>` where each `Bone` is a parentJoint → childJoint pair with thickness and color. This is a **rendering abstraction** (which joints to draw a bone between) that is separate from the **simulation abstraction** (the SkeletonNode tree). The rendering bone list hardcodes the skeleton topology, duplicating the hierarchy that already exists in the `SkeletonNode` tree.

#### 6.4 ContactSpec mixes solver nodes with attachment points

`ContactSpec` carries `endJoint`, `rootJoint`, `middleJoint`, `parentRotationJoint` — all Joint enum entries. These serve as both **articulation identifiers** (which joint rotates) and **attachment points** (where the limb contacts the environment). The `ContactSpec` also carries `targetWorld` (a position) and `pole` (a direction), which are solver-level concepts, not biomechanical ones.

#### 6.5 The SkeletonPose flat array mixes all joint types

The `SkeletonPose.joints` array (indexed by `Joint.ordinal`) stores world positions for every entry — articulations (HIP_F, SHOULDER_A), segments (PELVIS, CHEST), and attachment markers (HEAD_POS, HEEL_F, FINGERTIPS_A) alike. There is no type distinction at the data level between a joint that has independent DOF and a joint that is a derived marker.

### Summary of Confusion

| Biomechanical Concept | Current Abstraction | Where It Appears |
|---|---|---|
| Segment (rigid body between joints) | `SkeletonNode.localPosition` (bone offset) | SkeletonNode, SkeletonFactory hierarchy |
| Articulation (joint with DOF) | `SkeletonNode.localRotation` + `Joint` enum identity | SkeletonNode, Joint enum |
| Attachment (endpoint marker) | `Joint` enum entries like HEEL_F, PALM_A, FINGERTIPS_A | Joint enum, SkeletonNode tree leaves |
| Rendering bone | `Bone` data class (parentJoint → childJoint) | SkeletonEngine.bones |
| Solver node | `SkeletonNode` (used as IK chain substrate) | ConstraintSolver, IkStage |
| Frame snapshot | `SkeletonPose` (flat joint map) | Pipeline stages |

The fundamental issue is that **`SkeletonNode` is the single abstraction that serves all of these roles**, and the `Joint` enum is the single index that addresses all of them in the flat `SkeletonPose` arrays.

---

## 7. Ideal Conceptual Model

Without changing code, the conceptual model that best matches biomechanics would consist of the following distinct concepts:

### 7.1 Articulation

A joint with independent degrees of freedom. Has:
- A **parent segment** reference
- A **local rotation** (the joint angle)
- A **joint type** (ball-and-socket, hinge, pivot) that constrains the rotation space
- An **angular limit** (the biomechanical range of motion)

Examples: HIP_F, KNEE_F, SHOULDER_A, ELBOW_A, NECK_END.

### 7.2 Segment

A rigid body between two articulations. Has:
- A **length** (bone length)
- A **start articulation** and **end articulation**
- A **local transform** relative to the start articulation (derived from the bone length and the start articulation's rotation)

Examples: upper arm (between SHOULDER_A and ELBOW_A), thigh (between HIP_F and KNEE_F), torso (between PELVIS and CHEST).

### 7.3 Attachment

A fixed point on a segment, used for environment interaction (contacts) or rendering endpoints. Has:
- A **parent segment** reference
- A **local offset** from the segment's start or end articulation
- A **type** (heel, toe, palm, fingertip, knee, elbow)

Examples: HEEL_F, TOE_F, PALM_A, FINGERTIPS_A, KNEE_F.

### 7.4 FrameNode

A node in the hierarchical transform tree. This is the closest existing concept to `SkeletonNode`, but it should be **purely a transform container** — it carries localPosition, localRotation, worldPosition, worldRotation, parent, and children. It does NOT carry biomechanical identity (joint type, bone length, articulation DOF). The biomechanical identity lives in separate structures.

### 7.5 Bone (rendering)

A rendering primitive: a line between two projected points with a thickness and color. Defined by a parentJoint → childJoint pair. This is the existing `Bone` data class and `SkeletonEngine.bones` list. It is purely a presentation concern.

### 7.6 SkeletonPose (snapshot)

A flat, index-based map of joint positions and rotations at a single point in time. The existing `SkeletonPose` already serves this role, but it should be clearly separated into:
- **Intent section** (§1.1): what the pose author declared (contacts, limb targets, joint intents, posture intent, headings, extremity overrides)
- **State section** (§1.2): what the engine derived (finalized joint positions/rotations, stamps, deltas)

### 7.7 Why This Model Is Better

The current architecture's central tension is that `SkeletonNode` tries to be all of these things at once. In biomechanics, these are genuinely distinct concepts:

1. **Articulations** have DOF and limits. They are the joints that the solver and validator operate on.
2. **Segments** have length and mass distribution. They are the rigid bodies that the IK solver treats as bones.
3. **Attachments** are interaction points. They are where the environment contacts the body.
4. **FrameNodes** are the engineering substrate for FK. They carry transforms but have no biomechanical semantics.
5. **Bones** are purely visual. They connect projected joint positions on screen.
6. **SkeletonPose** is the data contract between stages. It is a snapshot, not a simulation object.

Separating these concepts would:
- Make the validator's bone-length rule check segment lengths (not arbitrary joint-to-joint distances)
- Make the solver's IK chain definition explicit (which articulations form which segments)
- Make the renderer's bone list derive from the segment definition (not a hardcoded `SkeletonEngine.bones` list)
- Make the attachment system type-safe (HEEL_F is an attachment, not an articulation)
- Make the intent carriers target the correct concept (jointIntents → articulations, not nodes)

### 7.8 Current Architecture vs. Ideal Model Mapping

| Current Concept | Ideal Model Concept | Gap |
|---|---|---|
| `SkeletonNode` | FrameNode + Articulation + Segment | Triple role; should be split |
| `Joint` enum entries | Articulation OR Attachment | No type distinction |
| `SkeletonNode.localPosition` | Segment length/direction | Carries segment data inside a node |
| `SkeletonNode.localRotation` | Articulation angle | Carries articulation data inside a node |
| `SkeletonEngine.bones` | Rendering Bone | Duplicates hierarchy from SkeletonNode tree |
| `SkeletonPose.joints[ordinal]` | Flat joint map | Mixes articulations, segments, and attachments in one array |
| `ContactSpec.endJoint` | Attachment point | Uses Joint enum which doesn't distinguish attachment from articulation |
| `ConstraintSolver.chainForEnd` | Solver chain definition | Hardcoded topology; should derive from segment definitions |

---

## Appendix: Key File Locations

| File | Path |
|---|---|
| Joint enum | `app/src/main/java/com/monkfitness/app/animation/Joint.kt` |
| SkeletonDefinition | `app/src/main/java/com/monkfitness/app/animation/SkeletonDefinition.kt` |
| SkeletonFactory + SkeletonNodes | `app/src/main/java/com/monkfitness/app/animation/SkeletonFactory.kt` |
| SkeletonNode + JointRotation | `app/src/main/java/com/monkfitness/app/animation/SkeletonNode.kt` |
| SkeletonPose (in PoseDefinition.kt) | `app/src/main/java/com/monkfitness/app/animation/PoseDefinition.kt` |
| SkeletonPipeline | `app/src/main/java/com/monkfitness/app/animation/SkeletonPipeline.kt` |
| SkeletonPoseFinalizer | `app/src/main/java/com/monkfitness/app/animation/SkeletonPoseFinalizer.kt` |
| ConstraintSolver | `app/src/main/java/com/monkfitness/app/animation/ConstraintSolver.kt` |
| PoseBuilder + BasePose | `app/src/main/java/com/monkfitness/app/animation/PoseBuilder.kt` + `BasePose.kt` |
| ExerciseValidator | `app/src/main/java/com/monkfitness/app/animation/ExerciseValidator.kt` |
| SkeletonRenderer (Compose) | `app/src/main/java/com/monkfitness/app/animation/SkeletonRenderer.kt` |
| SkeletonSnapshotRenderer | `app/src/main/java/com/monkfitness/app/animation/SkeletonSnapshotRenderer.kt` |
| SkeletonProjector | `app/src/main/java/com/monkfitness/app/animation/SkeletonProjector.kt` |
| ProjectedSkeleton | `app/src/main/java/com/monkfitness/app/animation/ProjectedSkeleton.kt` |
| Bone | `app/src/main/java/com/monkfitness/app/animation/Bone.kt` |
| SkeletonEngine | `app/src/main/java/com/monkfitness/app/animation/SkeletonEngine.kt` |
| SkeletonStyle | `app/src/main/java/com/monkfitness/app/animation/SkeletonStyle.kt` |
| IkStage | `app/src/main/java/com/monkfitness/app/animation/IkStage.kt` |
| PoseMetadata | `app/src/main/java/com/monkfitness/app/animation/PoseMetadata.kt` |
| PoseContext | `app/src/main/java/com/monkfitness/app/animation/PoseContext.kt` |
| AnimationController | `app/src/main/java/com/monkfitness/app/animation/AnimationController.kt` |
| AnimationState | `app/src/main/java/com/monkfitness/app/animation/AnimationState.kt` |
| ValidationReport/Result/Issue/Severity | `app/src/main/java/com/monkfitness/app/animation/Validation*.kt` |