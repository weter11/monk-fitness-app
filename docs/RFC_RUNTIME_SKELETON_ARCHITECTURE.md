# RFC: Runtime Skeleton Architecture

> **No code changes.** This is a design proposal only.
> Produced as a follow-up to the architectural audit (`ARCHITECTURAL_AUDIT_SKELETON_MODEL.md`).

---

## 1. Joint Enum Inventory

Every value in the `Joint` enum classified into exactly one category.

| Current enum | Index | Category | Why |
|---|---|---|---|
| `PELVIS` | 0 | `ROOT` | Body segment root; the world-space origin of the entire skeleton. Carries the pelvis position and orientation that the solver repositions. |
| `LUMBAR` | 32 | `BODY_SEGMENT` | Lower spine segment between PELVIS and CHEST. Pass-through by default (identity rotation, coincident with pelvis). Carries independent lumbar DOF when authored. |
| `CHEST` | 11 | `BODY_SEGMENT` | Thorax segment. Root of the upper body. Carries thoracic twist/side-bend/flex rotation. |
| `NECK_END` | 26 | `ARTICULATION` | Ball-and-socket joint between CHEST and HEAD. Rotation = neck flexion/extension/lateral bend/rotation. |
| `HEAD_POS` | 27 | `ATTACHMENT` | Head position marker. No independent rotation — position is derived from neck direction + fixed head length (18f). Used for viewport validation and gaze resolution. |
| `CLAVICLE_A` | 28 | `ARTICULATION` | Left shoulder girdle (clavicle). Rotation = elevation/depression + protraction/retraction. |
| `SCAPULA_A` | 29 | `ARTICULATION` | Left scapula. Rotation = scapular upward/downward rotation + protraction/retraction. |
| `SHOULDER_A` | 12 | `ARTICULATION` | Left glenohumeral joint (shoulder). IK root for the left arm. 3-DOF ball-and-socket. |
| `ELBOW_A` | 14 | `ARTICULATION` | Left elbow. Hinge articulation with flexion/extension only. |
| `HAND_A` | 15 | `END_EFFECTOR` | Left hand terminal joint. Position derived from IK solve. No authored rotation. |
| `WRIST_A` | 16 | `ARTICULATION` | Left wrist. Carries authored wrist articulation (grip, pronation/supination). Also serves as attachment host for palm/fingertips. |
| `PALM_A` | 17 | `ATTACHMENT` | Left palm marker. Position derived from wrist + hand definition. Contact point for support detection. |
| `KNUCKLES_A` | 18 | `ATTACHMENT` | Left knuckles marker. Position derived from palm + finger definition. |
| `FINGERTIPS_A` | 19 | `END_EFFECTOR` | Left fingertips terminal marker. Contact point for support detection. |
| `CLAVICLE_P` | 30 | `ARTICULATION` | Right shoulder girdle (clavicle). Mirror of CLAVICLE_A. |
| `SCAPULA_P` | 31 | `ARTICULATION` | Right scapula. Mirror of SCAPULA_A. |
| `SHOULDER_P` | 13 | `ARTICULATION` | Right glenohumeral joint. Mirror of SHOULDER_A. |
| `ELBOW_P` | 20 | `ARTICULATION` | Right elbow. Mirror of ELBOW_A. |
| `HAND_P` | 21 | `END_EFFECTOR` | Right hand terminal joint. Mirror of HAND_A. |
| `WRIST_P` | 22 | `ARTICULATION` | Right wrist. Mirror of WRIST_A. |
| `PALM_P` | 23 | `ATTACHMENT` | Right palm marker. Mirror of PALM_A. |
| `KNUCKLES_P` | 24 | `ATTACHMENT` | Right knuckles marker. Mirror of KNUCKLES_A. |
| `FINGERTIPS_P` | 25 | `END_EFFECTOR` | Right fingertips terminal marker. Mirror of FINGERTIPS_A. |
| `HIP_F` | 1 | `ARTICULATION` | Left hip ball-and-socket joint. 3-DOF (flexion/extension, abduction/adduction, internal/external rotation). |
| `KNEE_F` | 3 | `ARTICULATION` | Left knee. Hinge articulation with flexion/extension. |
| `ANKLE_F` | 4 | `ARTICULATION` | Left ankle. 2-DOF (dorsiflexion/plantar-flexion + inversion/eversion). |
| `HEEL_F` | 5 | `ATTACHMENT` | Left heel marker. Position derived from ankle + foot definition. Contact point for support detection. |
| `TOE_F` | 6 | `END_EFFECTOR` | Left toe terminal marker. Contact point for support detection. |
| `KNEE_B` | 7 | `ARTICULATION` | Right knee. Mirror of KNEE_F. |
| `ANKLE_B` | 8 | `ARTICULATION` | Right ankle. Mirror of ANKLE_F. |
| `HEEL_B` | 9 | `ATTACHMENT` | Right heel marker. Mirror of HEEL_F. |
| `TOE_B` | 10 | `END_EFFECTOR` | Right toe terminal marker. Mirror of TOE_B. |

### Category Summary

| Category | Count | Entries |
|---|---|---|
| `ROOT` | 1 | PELVIS |
| `BODY_SEGMENT` | 2 | LUMBAR, CHEST |
| `ARTICULATION` | 14 | NECK_END, CLAVICLE_A, SCAPULA_A, SHOULDER_A, ELBOW_A, WRIST_A, CLAVICLE_P, SCAPULA_P, SHOULDER_P, ELBOW_P, WRIST_P, HIP_F, KNEE_F, ANKLE_F, KNEE_B, ANKLE_B |
| `ATTACHMENT` | 6 | HEAD_POS, PALM_A, KNUCKLES_A, PALM_P, KNUCKLES_P, HEEL_F, HEEL_B |
| `END_EFFECTOR` | 4 | HAND_A, FINGERTIPS_A, HAND_P, FINGERTIPS_P, TOE_F, TOE_B |
| `RIG_HELPER` | 0 | (none currently — but wrist/ankle nodes serve this role accidentally) |
| `PROCEDURAL` | 0 | (none currently — but HEAD_POS is procedural in origin) |
| `UNKNOWN` | 0 | — |

### Classification Notes

- `WRIST_A` / `WRIST_P` are classified as `ARTICULATION` because they carry authored rotation (grip, pronation/supination), but they also serve as `ATTACHMENT` hosts (palm/fingertips derive from them). This dual role is an inconsistency that the RFC in Section 6 addresses.
- `HEAD_POS` is classified as `ATTACHMENT` because it has no independent DOF — its position is procedurally derived from the neck direction + a fixed head length. It is a tracking marker, not a joint.
- `HEEL_F` / `HEEL_B` are classified as `ATTACHMENT` because they are contact markers whose positions are derived by the Finalizer's extremity orientation logic. They carry no authored rotation.
- `TOE_F` / `TOE_B` are classified as `END_EFFECTOR` because they are terminal contact points used by the solver and validator.
- `HAND_A` / `HAND_P` are classified as `END_EFFECTOR` because they are the IK solve targets for the arm chains. Their positions are set by the solver, not authored.
- `FINGERTIPS_A` / `FINGERTIPS_P` are classified as `END_EFFECTOR` because they are terminal markers used for support detection and rendering.

---

## 2. Runtime Object Inventory

### 2.1 SkeletonNode

| Aspect | Detail |
|---|---|
| **Type** | Mutable class |
| **Ownership** | Created by `SkeletonFactory`; owned by `SkeletonPose.roots` (transient, per-frame) |
| **Lifetime** | Created once by factory, then mutated in-place across frames. The tree topology is fixed; only `localPosition`, `localRotation`, `worldPosition`, `worldRotation` change. |
| **Responsibility** | Hierarchical transform node: carries `localPosition` (segment offset), `localRotation` (articulation angle), `worldPosition`/`worldRotation` (FK-computed), parent/children tree links. Also serves as the substrate for IK solves (solver writes `localPosition` into middle/end nodes), chest-frame reconstruction (Finalizer reads/writes `chest.localRotation`), head-target resolution (Finalizer writes `neck.localPosition`/`head.localPosition`), and extremity orientation derivation (Finalizer reads wrist/ankle `localRotation`). |
| **Problems** | Violates SRP: simultaneously a hierarchy node, articulation, segment, attachment host, IK substrate, and validation data source. |

### 2.2 SkeletonPose

| Aspect | Detail |
|---|---|
| **Type** | Mutable class |
| **Ownership** | Created by `PoseBuilder.build()` or `SkeletonPose()` + `copyFrom`. Owned by the pipeline during `runStages()`, then passed to renderer/validator. |
| **Lifetime** | Per-frame. The pipeline creates it via `build()`, mutates it through stages, and returns it to the caller. The pipeline keeps `previous` and `prePrevious` copies for dynamics validation (2-frame history). |
| **Responsibility** | Flat joint map (`joints: Array<Vector3>`, `rotations: Array<JointRotation>`) + intent carriers (`contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`) + state stamps (`boneLengthsVerified`, `rootTranslationDelta`, `rootRotationDelta`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`, `straightIntentDropped`, `maxIkClampAmount`). |
| **Problems** | Mixes intent (§1.1) and state (§1.2) in one object. The flat array indexed by `Joint.ordinal` conflates articulations, segments, and attachments. |

### 2.3 Bone

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Defined in `SkeletonEngine.bones` (static, per-definition). |
| **Lifetime** | Permanent. Created once with the engine, never mutated. |
| **Responsibility** | Rendering primitive: defines a parentJoint → childJoint pair with thickness and color multiplier. Used by `SkeletonProjector` and `SkeletonRenderer` to draw bones in screen space. |
| **Problems** | Exists only for rendering. Duplicates the hierarchy topology that already exists in the `SkeletonNode` tree. Hardcoded in `SkeletonEngine` rather than derived from the skeleton definition. |

### 2.4 JointRotation

| Aspect | Detail |
|---|---|
| **Type** | Mutable class (axis-angle) |
| **Ownership** | Created as scratch buffers in `SkeletonNode`, `SkeletonPoseFinalizer`, and `ConstraintSolver`. Also stored in `SkeletonPose.rotations` array. |
| **Lifetime** | Scratch buffers are long-lived (persistent fields on classes). The `rotations` array in `SkeletonPose` is per-frame. |
| **Responsibility** | Stores a single rotation as an axis vector + angle (radians). Used for both local and world rotations on `SkeletonNode`, and for the flat rotation array in `SkeletonPose`. |
| **Problems** | Mutable shared scratch buffers create aliasing risk. The axis-angle representation is not the most natural for 3D rotation math (quaternions would avoid gimbal lock), but this is a performance choice. |

### 2.5 ContactSpec

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `BasePose.bakeIkLimb()` and the package-level `bakeIkLimb()` function. Stored in `SkeletonPose.contacts`. Consumed by `ConstraintSolver.solve()`. |
| **Lifetime** | Per-build (per `PoseBuilder.build()` call). Cleared at the start of each build via `IntentBuilder.reset()`. |
| **Responsibility** | Describes a fixed support contact: which joint is the end-effector, what the root joint is, the parent rotation frame, the middle joint, the target world position, the pole direction, bone lengths, IK constraint, straight flag, and optional `ContactConstraint`. |
| **Problems** | Mixes solver-level concepts (rootJoint, parentRotationJoint, middleJoint, pole, constraint) with biomechanical concepts (endJoint, targetWorld). The `contact` field (a `ContactConstraint`) carries environment-specific data that the solver uses but that is not part of the pose's biomechanical intent. |

### 2.6 WorldTarget

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `bakeIkLimb()` and added to `SkeletonPose.limbTargets`. Consumed by `IkStage.apply()` (when `IK_STAGE_ACTIVE` is true). |
| **Lifetime** | Per-build. Cleared by `IntentBuilder.reset()`. |
| **Responsibility** | Declares a world-space target for a limb end-effector or intermediate joint. Carries the full IK context (joint, world position, pole, straight flag, optional contact). |
| **Problems** | The `limbTargets` carrier is populated by pose authoring (`bakeIkLimb`) but consumed by the engine (`IkStage`). When `IK_STAGE_ACTIVE` is false (the default), the carrier is populated but never consumed — it is dead weight that exists only for the future stage. |

### 2.7 RelativeArticulation

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `declareJointIntent()` in `BasePose` and `declarePelvisTilt()`. Stored in `SkeletonPose.jointIntents`. Consumed by `SkeletonPoseFinalizer.applyIntentCarriers()`. |
| **Lifetime** | Per-build. Cleared by `IntentBuilder.reset()`. |
| **Responsibility** | Declares a per-joint relative rotation (articulation) as intent. The `Joint` key identifies which joint; the `JointRotation` value is the rotation relative to the joint's parent segment. |
| **Problems** | Currently consumed by the Finalizer (B2) but only for non-contact poses. For contact poses, the Finalizer skips carrier re-application because the solver has already repositioned the root. This means `jointIntents` is ignored for contact poses — an inconsistency. |

### 2.8 SpineCurve

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `buildSpineCurve()` in `BasePose`. Stored in `SkeletonPose.spineIntent`. Consumed by `SkeletonPoseFinalizer.applyIntentCarriers()`. |
| **Lifetime** | Per-build. Reset to default by `IntentBuilder.reset()`. |
| **Responsibility** | Declares a single declarative spine curve (lumbar + thoracic about a shared axis). Replaces the legacy coupled pelvis+chest dual writes. |
| **Problems** | The `spineIntent` is consumed by the Finalizer but only for non-contact poses (same issue as `jointIntents`). Also, the `axis` field defaults to `Vector3(1f, 0f, 0f)` (X axis) but `buildSpineCurve()` defaults to `axisZ` — the default is inconsistent with the most common usage. |

### 2.9 PostureIntent

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `declarePosture()` in `BasePose`. Stored in `SkeletonPose.postureIntent`. Consumed by `ConstraintSolver.solve()`. |
| **Lifetime** | Per-build. Reset to `CUSTOM` by `IntentBuilder.reset()`. |
| **Responsibility** | Declares the coarse posture family (SEATED_NEAR_FLOOR, HANGING_UNDER_BAR, STANDING, CUSTOM) so the solver can derive the exact pelvis height without the pose hand-computing root arithmetic. |
| **Problems** | The `tolerance` field is passed through but rarely used meaningfully. The `precedence` list is stored as joint names (strings) rather than `Joint` enum values, requiring string comparison in the solver. |

### 2.10 HeadTarget

| Aspect | Detail |
|---|---|
| **Type** | Immutable data class |
| **Ownership** | Created by `buildGaze()` in `BasePose`. Stored in `SkeletonPose.headTarget`. Consumed by `SkeletonPoseFinalizer.resolveHeadTarget()`. |
| **Lifetime** | Per-build. Reset to `null` by `IntentBuilder.reset()`. |
| **Responsibility** | Declares a world-space gaze target for the head. The Finalizer resolves neck/head orientation from this target. |
| **Problems** | The head-target resolution writes `neck.localPosition` and `head.localPosition` directly, bypassing the normal FK chain. This is a special-case mutation that breaks the principle of the Finalizer being a pure FK traversal. |

### 2.11 ExtremityOrientationMode / ExtremityOverrides

| Aspect | Detail |
|---|---|
| **Type** | Enum (`ExtremityOrientationMode`: AUTOMATIC, MANUAL_OVERRIDE) + `SkeletonPose.extremityOverrides: MutableSet<Extremity>` |
| **Ownership** | Set by `overrideExtremityOrientation()` in `BasePose`. Read by `SkeletonPoseFinalizer` during extremity derivation. |
| **Lifetime** | Per-build. Cleared by `IntentBuilder.reset()`. |
| **Responsibility** | Allows a pose to opt an extremity out of engine-derived orientation and into explicit author override. When `MANUAL_OVERRIDE`, the Finalizer preserves the authored endpoint local positions verbatim. |
| **Problems** | The ownership check (`isExtremityAutomatic`) reads from `extremityOverrides` set membership, not from a dedicated field on the extremity. This means the engine must iterate the set to check ownership — a minor inefficiency. |

### 2.12 SkeletonDefinition

| Aspect | Detail |
|---|---|
| **Type** | Interface + `HumanSkeletonDefinition` data class |
| **Ownership** | Created once per definition (e.g., `SkeletonDefinition.DEFAULT_ADULT`). Passed to pipeline, solver, finalizer, and validator. |
| **Lifetime** | Permanent. Lives for the lifetime of the engine instance. |
| **Responsibility** | Anatomical metadata: bone lengths (torso, neck, thigh, shin, foot, upper arm, forearm), proportions (shoulderWidth, hipWidth), foot/hand definitions, camera definition, IK constraints, angular joint limits, hip ROM limits. |
| **Problems** | Carries both measurement data (lengths) and constraint data (IK constraints, angular limits, hip ROM). These are conceptually different — measurements are static, constraints are configurable. |

### 2.13 SkeletonFactory / SkeletonNodes

| Aspect | Detail |
|---|---|
| **Type** | `object` (SkeletonFactory) + data class (SkeletonNodes) |
| **Ownership** | Created by the caller. `SkeletonNodes` holds references to all 33 `SkeletonNode` instances. |
| **Lifetime** | `SkeletonNodes` is typically created once per skeleton type and discarded after the node tree is copied into `SkeletonPose.roots`. |
| **Responsibility** | Builds the standard or push-up skeleton hierarchy. `SkeletonNodes` is a convenience container that exposes all nodes by name for pose authoring. |
| **Problems** | `SkeletonNodes` is a leaky abstraction — it exposes the internal node tree as a public API. The `spine`, `shoulderB`, `hipA` aliases add confusion about which naming convention is canonical. |

### 2.14 SkeletonPipeline

| Aspect | Detail |
|---|---|
| **Type** | Class |
| **Ownership** | Created by `SkeletonRenderer` (via `remember`) and `SkeletonSnapshotRenderer`. Long-lived, per-engine/per-definition. |
| **Lifetime** | Lives as long as the renderer/engine instance. |
| **Responsibility** | Orchestrator. Owns `SkeletonPoseFinalizer` and optional `ExerciseValidator`. Drives the ordered stage chain (`IkStage` → `ConstraintSolver` → `Finalizer`). Maintains per-frame previous/pre-previous pose history for dynamics validation. |
| **Problems** | The pipeline is not thread-safe (single-threaded assumption). The `produceFrame` overload that accepts `SkeletonPose` directly mutates the input pose's `environment` and `supportedPoints` fields — a side effect that is not obvious from the API signature. |

### 2.15 SkeletonPoseFinalizer

| Aspect | Detail |
|---|---|
| **Type** | Class |
| **Ownership** | Created by `SkeletonPipeline` constructor. |
| **Lifetime** | Long-lived, owned by pipeline. |
| **Responsibility** | Completes the 3D pose: FK traversal, chest-frame reconstruction, head-target resolution, extremity orientation derivation (foot and hand), validation stamp production (hip ROM stamps, bilateral symmetry). Owns the `outputPose` buffer. |
| **Problems** | The finalizer is the single largest class (845 lines) and accumulates responsibilities from multiple architectural eras (chest-frame reconstruction from Issue F, head-target resolution from Phase 7, extremity orientation from W1, validation stamps from B5). |

### 2.16 ConstraintSolver

| Aspect | Detail |
|---|---|
| **Type** | `object` (stateless singleton) |
| **Ownership** | Global. Called by `SkeletonPipeline.runStages()`. |
| **Lifetime** | Permanent (object singleton). |
| **Responsibility** | Global contact-constraint / root-repositioning layer. Repositions the pelvis so all fixed contacts are honored, then re-bakes each contact limb. Also runs a CCD posture pass for over-constrained poses. Seeds the root from `PostureIntent`. |
| **Problems** | As a singleton `object`, it cannot be configured or mocked. Its `MAX_ITERATIONS`, `RELAX`, `SMOOTH_GAIN`, `TILT_GAIN`, and `POSTURE_REG` constants are hardcoded. The `lastSolvedRoot` `WeakHashMap` creates a hidden dependency on `SkeletonPose` identity. |

### 2.17 ExerciseValidator

| Aspect | Detail |
|---|---|
| **Type** | Class |
| **Ownership** | Created by the caller, passed to `SkeletonPipeline` constructor. |
| **Lifetime** | Long-lived, owned by pipeline. |
| **Responsibility** | Read-only biomechanical validation on a `SkeletonPose`. 17 rules: finite coordinates, bone lengths, head viewport, foot ground penetration, hand sliding, IK constraints, dynamics (discontinuity, velocity, acceleration), support polygon, bilateral symmetry, hand-shoulder alignment, IK target reachability, angular joint limits, straight-limb intent, contact preservation, pelvis intent, hip ROM. |
| **Problems** | The validator reads engine-produced stamps (`hipRomStamps`, `bilateralSymmetryDelta`) rather than re-deriving geometry, which is correct. However, the `ValidatorConfig` has 15+ flags, most of which are off by default, making the validation behavior configuration-heavy and hard to reason about. |

### 2.18 SkeletonEngine

| Aspect | Detail |
|---|---|
| **Type** | Class |
| **Ownership** | Created by the caller. |
| **Lifetime** | Permanent per-definition. |
| **Responsibility** | Defines the rendering bone hierarchy (`List<Bone>`) and holds `SkeletonStyle` (visual parameters). |
| **Problems** | The bone list hardcodes the skeleton topology (which joints are connected by bones). This duplicates the hierarchy that already exists in the `SkeletonNode` tree. If the skeleton topology changes, both the `SkeletonNode` tree and the `SkeletonEngine.bones` list must be updated. |

### 2.19 SkeletonProjector

| Aspect | Detail |
|---|---|
| **Type** | Class |
| **Ownership** | Created by renderers (`SkeletonRenderer`, `SkeletonSnapshotRenderer`). |
| **Lifetime** | Long-lived, reused across frames. |
| **Responsibility** | Projects a 3D `SkeletonPose` into a 2D `ProjectedSkeleton`. Computes joint screen positions, bone screen positions, torso faces, ground grid, and shadow points. |
| **Problems** | Hardcodes the torso face computation (8 points, 6 faces) based on chest rotation. This assumes a specific torso geometry that may not generalize to all skeleton definitions. |

### 2.20 ProjectedSkeleton / ProjectedPoint / ProjectedBone / ProjectedFace

| Aspect | Detail |
|---|---|
| **Type** | Data classes |
| **Ownership** | Created by `SkeletonProjector`. Held by renderers. |
| **Lifetime** | Per-frame. Reused via buffer pooling (`ProjectedSkeleton` is pre-allocated). |
| **Responsibility** | Screen-space representation of the skeleton after projection. `ProjectedPoint` holds x, y, depth, perspectiveScale. `ProjectedBone` holds two points + thickness + color multiplier. `ProjectedFace` holds 4 points. |
| **Problems** | The depth-based sorting for painter's algorithm is done in the renderer, not the projector, creating a split responsibility. |

---

## 3. Runtime Pipeline

### 3.1 Overview

```
SkeletonFactory
│
├── Creates SkeletonNode tree (33 nodes, fixed topology)
│
▼
PoseBuilder.build(context)
│
├── Authoring: pose helpers write SkeletonNode.localPosition / localRotation
├── Authoring: bakeIkLimb() registers ContactSpec in pose.contacts
├── Authoring: declarePosture() sets postureIntent
├── Authoring: buildSpineCurve() sets spineIntent + jointIntents
├── Authoring: buildGaze() sets headTarget
├── Authoring: overrideExtremityOrientation() sets extremityOverrides
├── Authoring: setHeading() sets headings
│
▼
SkeletonPose (output of build)
│   ├── joints: Array<Vector3> (flat, indexed by Joint.ordinal)
│   ├── rotations: Array<JointRotation> (flat)
│   ├── roots: List<SkeletonNode> (the FK tree)
│   ├── contacts: MutableList<ContactSpec>
│   ├── limbTargets: MutableList<WorldTarget>
│   ├── jointIntents: MutableList<RelativeArticulation>
│   ├── spineIntent: SpineCurve
│   ├── postureIntent: PostureIntent
│   ├── extremityOverrides: MutableSet<Extremity>
│   ├── extremityArticulations: MutableMap<Extremity, JointRotation>
│   ├── headTarget: HeadTarget?
│   ├── headings: MutableMap<Extremity, Vector3>
│   ├── environment: EnvironmentDefinition
│   ├── supportedPoints: MutableSet<SupportPoint>
│   └── state stamps (boneLengthsVerified, rootTranslationDelta, etc.)
│
▼
SkeletonPipeline.runStages(builtPose)
│
├── Stage 1: IkStage.apply(pose, definition)
│   ├── Input: pose.limbTargets, definition
│   ├── Output: SkeletonNode.localPosition updates (middle/end nodes)
│   ├── Mutates: SkeletonNode.localPosition
│   ├── Read-only: pose.roots, definition, pose.limbTargets
│   └── Recreated: None
│
├── Stage 2: ConstraintSolver.solve(pose, definition)
│   ├── Input: pose.contacts, pose.postureIntent, pose.roots, definition
│   ├── Output: Mutated SkeletonNode tree (pelvis, contact limb nodes)
│   ├── Mutates: pelvis.localPosition, pelvis.localRotation, contact limb SkeletonNode.localPosition
│   │           pose.rootTranslationDelta, pose.rootRotationDelta, pose.boneLengthsVerified
│   ├── Read-only: definition, pose.contacts, pose.postureIntent, pose.contactPrecedence
│   └── Recreated: None (in-place mutation)
│
├── Stage 3: SkeletonPoseFinalizer.finalize(pose)
│   ├── Input: pose.roots, definition, pose intent carriers
│   ├── Output: New SkeletonPose (outputPose) with finalized positions + rotations
│   ├── Mutates: SkeletonNode.localRotation (chest, head), SkeletonPose.joints (all),
│   │           SkeletonPose.rotations (all), SkeletonPose.hipRomStamps,
│   │           SkeletonPose.bilateralSymmetryDelta, SkeletonPose.bilateralOppositeBend
│   ├── Read-only: pose.roots, definition, pose.extremityArticulations, pose.headTarget,
│   │              pose.environment, pose.supportedPoints, pose.contacts
│   └── Recreated: outputPose (new SkeletonPose via copyFrom + overwrite)
│
▼
Finalized SkeletonPose
│
├──→ SkeletonRenderer / SkeletonSnapshotRenderer
│   │   ├── Input: finalized SkeletonPose, Camera, SkeletonEngine
│   │   ├── Output: Bitmap / Compose drawing commands
│   │   ├── Mutates: None (read-only on pose)
│   │   └── Recreated: ProjectedSkeleton, RenderItems (per-frame buffers)
│
└──→ ExerciseValidator.validate() (optional, in produceFrameValidated)
    ├── Input: finalized SkeletonPose, definition, environment, camera, previousPose, prePreviousPose
    ├── Output: ValidationReport
    ├── Mutates: None (read-only on pose)
    └── Recreated: ValidationReport, ValidationResult list, ValidationIssue list
```

### 3.2 Stage Data Ownership Detail

#### Stage 1: IkStage.apply()

| Data | Ownership | Mutation |
|---|---|---|
| `pose.limbTargets` | Written by `bakeIkLimb()` during build; read by IkStage | Read-only |
| `pose.roots` (SkeletonNode tree) | Created by SkeletonFactory; owned by pose | Read-only (IkStage reads node positions) |
| `SkeletonNode.localPosition` (middle/end nodes) | Owned by SkeletonNode | **Mutated** by IkStage |
| `pose.boneLengthsVerified` | Owned by SkeletonPose | **Mutated** (reset then AND'd) |
| `pose.maxIkClampAmount` | Owned by SkeletonPose | **Mutated** (max of clamp amounts) |
| `definition` | Owned by pipeline/engine | Read-only |

#### Stage 2: ConstraintSolver.solve()

| Data | Ownership | Mutation |
|---|---|---|
| `pose.roots` (SkeletonNode tree) | Owned by pose | **Mutated** (pelvis localPosition/rotation, contact limb localPosition) |
| `pose.contacts` | Written by `bakeIkLimb()` during build | Read-only |
| `pose.postureIntent` | Written by `declarePosture()` during build | Read-only |
| `pose.contactPrecedence` | Written by `declarePosture()` during build | Read-only |
| `pose.rootTranslationDelta` | Owned by SkeletonPose | **Mutated** (solver displacement magnitude) |
| `pose.rootRotationDelta` | Owned by SkeletonPose | **Mutated** (solver rotation magnitude) |
| `pose.boneLengthsVerified` | Owned by SkeletonPose | **Mutated** (AND'd with per-limb check) |
| `definition` | Owned by pipeline/engine | Read-only |
| `lastSolvedRoot` (WeakHashMap) | Internal to ConstraintSolver | **Mutated** (persists solved root for inter-frame smoothing) |

#### Stage 3: SkeletonPoseFinalizer.finalize()

| Data | Ownership | Mutation |
|---|---|---|
| `pose.roots` (SkeletonNode tree) | Owned by pose | **Mutated** (chest.localRotation, neck.localPosition, head.localPosition) |
| `SkeletonPose.joints` (outputPose) | Owned by outputPose | **Mutated** (all joint positions via flatten + extremity derivation) |
| `SkeletonPose.rotations` (outputPose) | Owned by outputPose | **Mutated** (all joint rotations via flatten) |
| `SkeletonPose.hipRomStamps` | Owned by outputPose | **Mutated** (computed from pelvis + hip + knee rotations) |
| `SkeletonPose.bilateralSymmetryDelta` | Owned by outputPose | **Mutated** (computed from knee/elbow perpendicular deviations) |
| `SkeletonPose.bilateralOppositeBend` | Owned by outputPose | **Mutated** (computed from knee/elbow bend direction comparison) |
| `pose.extremityArticulations` | Owned by pose | Read-only |
| `pose.headTarget` | Owned by pose | Read-only |
| `pose.environment` | Owned by pose | Read-only |
| `pose.supportedPoints` | Owned by pose | Read-only |
| `pose.contacts` | Owned by pose | Read-only |
| `definition` | Owned by pipeline/engine | Read-only |

---

## 4. Runtime Object Survival Assessment

| Object | Verdict | Rationale |
|---|---|---|
| `SkeletonNode` | **Keep** (but split responsibilities) | The FK traversal and hierarchical transform computation are essential. The node should remain as a transform container only; biomechanical identity should move to separate structures. |
| `Joint` enum | **Split** | The enum currently serves as both a joint identifier and an array index. It should be split into: (a) an articulation enum, (b) a segment enum, (c) an attachment enum, and (d) an end-effector enum. Alternatively, a single enum with a category tag per entry. |
| `SkeletonPose` | **Keep** (but separate intent from state) | The flat joint map is the correct data contract between stages. However, the intent carriers (§1.1) and state stamps (§1.2) should be structurally separated to make the ownership boundary explicit. |
| `SkeletonDefinition` | **Keep** | Anatomical metadata is correctly separated from runtime state. |
| `SkeletonFactory` | **Keep** | Factory pattern is correct for building the node tree. |
| `SkeletonNodes` (data class) | **Replace** | This is a convenience container that leaks internal topology. Pose authors should not reference individual nodes by name; they should reference joints by `Joint` enum and let the engine resolve the node. |
| `Bone` | **Keep** (but derive from definition) | Rendering bones are a valid concept, but they should be derived from the skeleton definition rather than hardcoded in `SkeletonEngine`. |
| `SkeletonEngine` | **Replace** | Should be replaced by a `SkeletonRendererDefinition` that derives bone topology from the skeleton definition rather than hardcoding it. |
| `JointRotation` | **Keep** | Axis-angle is a valid compact representation for per-frame computation. |
| `ContactSpec` | **Keep** (but rename and restructure) | The concept of a fixed support contact is essential. However, it should separate solver-level fields (rootJoint, parentRotationJoint, middleJoint, pole, constraint) from biomechanical fields (endJoint, targetWorld, contact). |
| `WorldTarget` | **Keep** | The limb target carrier is correct. When `IK_STAGE_ACTIVE` is true, it is the sole input to the engine-owned IK stage. |
| `RelativeArticulation` | **Keep** | The per-joint intent carrier is correct. It should be consumed by the articulation system, not the node system. |
| `SpineCurve` | **Keep** | The declarative spine curve is a good abstraction. |
| `PostureIntent` | **Keep** | The coarse posture kind is essential for solver root seeding. |
| `HeadTarget` | **Keep** | The gaze-as-target carrier is correct. |
| `ExtremityOrientationMode` | **Keep** | The explicit ownership mode is correct. |
| `SkeletonPipeline` | **Keep** | The orchestrator pattern is correct. |
| `SkeletonPoseFinalizer` | **Keep** (but decompose) | The finalizer is too large (845 lines) and accumulates too many responsibilities. It should be decomposed into: FK traversal, chest-frame reconstruction, head-target resolution, extremity derivation, and validation stamps — each as a separate stage or component. |
| `ConstraintSolver` | **Keep** (but make configurable) | The solver is correct but hardcoded as a singleton with magic constants. It should be a configurable service. |
| `ExerciseValidator` | **Keep** | The validation rules are correct. The config flag proliferation should be addressed by grouping rules into profiles. |
| `SkeletonProjector` | **Keep** | The projection logic is correct. |
| `ProjectedSkeleton` | **Keep** | The screen-space buffer is correct. |
| `SkeletonRenderer` (Compose function) | **Keep** | The rendering function is correct. |
| `SkeletonSnapshotRenderer` | **Keep** | The off-screen renderer is correct. |
| `IntentBuilder` (inner class of SkeletonPose) | **Keep** | The sole-mutator pattern is correct and enforces the pose→engine boundary at compile time. |
| `HeadingBuilder` (inner class of SkeletonPose) | **Keep** | The heading builder is correct. |

---

## 5. Ideal Conceptual Runtime Model

### 5.1 Articulation

**Why it exists:** Biomechanically, an articulation is a joint with independent degrees of freedom. It is the point where two segments meet and relative motion occurs. The solver operates on articulations (IK solves target articulations), the validator checks articulation limits (angular joint limits, hip ROM), and the pose author declares articulation intent (jointIntents, extremityArticulations).

**Properties:**
- A `Joint` enum identity (which joint this is)
- A parent segment reference (which segment this joint connects to)
- A local rotation (the joint angle relative to the parent segment)
- A joint type (ball-and-socket, hinge, pivot) that constrains the rotation space
- Angular limits (the biomechanical range of motion)

**Current mapping:** The `Joint` enum entries classified as `ARTICULATION` in Section 1. The `SkeletonNode.localRotation` stores the articulation angle. The `ConstraintSolver` and `ExerciseValidator` operate on articulations.

### 5.2 Segment

**Why it exists:** A segment is the rigid body between two articulations. It has length, mass distribution, and a pose (position + orientation). The IK solver treats segments as bones with fixed length. The validator checks bone lengths (segment length invariance). The renderer draws bones between segment endpoints.

**Properties:**
- A start articulation and end articulation
- A length (the distance between the two articulations when the joint is at zero)
- A pose (position + orientation of the segment in world space)

**Current mapping:** The `SkeletonNode` carries segment data (`localPosition` as the bone offset) but also carries articulation data (`localRotation`). The `Bone` data class represents a segment for rendering but is hardcoded in `SkeletonEngine`. The `SkeletonDefinition` carries segment lengths (torsoLength, thighLength, etc.) but they are not associated with specific segments in the data model.

### 5.3 Attachment

**Why it exists:** An attachment is a fixed point on a segment used for environment interaction (contacts) or as a rendering endpoint. Attachments do not have independent DOF — their position is derived from the segment's pose and the attachment's local offset. The solver uses attachments as fixed support points. The validator checks that attachments do not penetrate the ground or slide.

**Properties:**
- A parent segment reference
- A local offset from the segment's start or end articulation
- An attachment type (heel, toe, palm, fingertip, knee, elbow)

**Current mapping:** The `Joint` enum entries classified as `ATTACHMENT` in Section 1 (HEAD_POS, PALM_A, KNUCKLES_A, PALM_P, KNUCKLES_P, HEEL_F, HEEL_B). The `SupportPoint` enum maps to these attachments for contact registration.

### 5.4 EndEffector

**Why it exists:** An end-effector is the terminal point of a limb chain. It is the IK solve target and the contact point for support detection. End-effectors are a subset of attachments that are specifically the terminal nodes of IK chains.

**Properties:**
- A parent articulation reference
- A world position (set by the solver)
- A contact flag (whether this end-effector is a fixed support)

**Current mapping:** The `Joint` enum entries classified as `END_EFFECTOR` in Section 1 (HAND_A, FINGERTIPS_A, HAND_P, FINGERTIPS_P, TOE_F, TOE_B). The `ContactSpec.endJoint` points to end-effectors.

### 5.5 RigHelper

**Why it exists:** A rig helper is a procedural node that exists to support the rigging/IK system but does not represent a physical joint or body part. Examples include the wrist and ankle nodes, which carry authored rotation for grip/foot plant but are not anatomical joints — they are procedural intermediaries between the IK solve target and the visual endpoint.

**Properties:**
- A parent articulation reference
- A local rotation (the authored grip/plant rotation)
- A derived position (computed from the parent articulation + bone length)

**Current mapping:** `WRIST_A`, `WRIST_P`, `ANKLE_F`, `ANKLE_B` are currently classified as `ARTICULATION` but serve as rig helpers — they are not anatomical joints with independent DOF in the biomechanical sense; they are procedural nodes that carry the authored wrist/ankle articulation for the Finalizer's extremity derivation.

### 5.6 FrameNode

**Why it exists:** A frame node is the engineering substrate for forward kinematics. It carries a local transform (position + rotation) and a world transform (computed by FK). It has no biomechanical identity — it does not know whether it represents an articulation, a segment, or an attachment. The biomechanical identity is provided by a separate mapping.

**Properties:**
- A local position (the bone offset from the parent)
- A local rotation (the joint angle)
- A world position (computed by FK)
- A world rotation (computed by FK)
- A parent reference
- A children list

**Current mapping:** `SkeletonNode` is the closest existing concept, but it conflates frame node responsibilities with biomechanical identity (the `joint: Joint` field). In the ideal model, `SkeletonNode` would be renamed to `FrameNode` and would not carry a `Joint` enum identity. Instead, a separate mapping (e.g., a `JointMapping` or `SkeletonTopology`) would associate frame nodes with biomechanical concepts.

### 5.7 Transform

**Why it exists:** A transform is the minimal unit of spatial description: a position and a rotation. It is the data that flows through the FK chain and is stored in the flat `SkeletonPose` arrays. Separating the transform from the node that carries it makes the data contract between stages explicit and allows stages to operate on transforms without needing the full node hierarchy.

**Properties:**
- A position (Vector3)
- A rotation (JointRotation or quaternion)

**Current mapping:** The `SkeletonPose.joints` (positions) and `SkeletonPose.rotations` (rotations) arrays are the existing transform storage. The `SkeletonNode.worldPosition` and `SkeletonNode.worldRotation` are the per-node transforms.

### 5.8 SkeletonPose (Snapshot)

**Why it exists:** A snapshot is the data contract between pipeline stages. It is a flat, index-based map of transforms at a single point in time. It carries both intent (what the pose author declared) and state (what the engine derived). The snapshot is immutable during validation and rendering.

**Properties:**
- A transforms array (position + rotation per joint)
- An intent section (contacts, limb targets, joint intents, posture intent, head target, headings, extremity overrides, extremity articulations, environment, supported points)
- A state section (bone length stamps, root displacement deltas, hip ROM stamps, bilateral symmetry stamps)

**Current mapping:** The existing `SkeletonPose` class, with the intent/state separation made structural rather than convention-based.

---

## 6. Architectural Inconsistencies

### 6.1 Enum mixes physical anatomy and rig helpers

The `Joint` enum contains entries that are anatomical articulations (HIP_F, KNEE_F, SHOULDER_A, ELBOW_A), anatomical segments (PELVIS, LUMBAR, CHEST), anatomical attachments (HEAD_POS, HEEL_F, PALM_A), and rig helpers (WRIST_A, WRIST_P, ANKLE_F, ANKLE_B — which carry authored rotation for grip/foot plant but are not true anatomical joints). All share the same enum and the same ordinal-based indexing.

### 6.2 SkeletonNode stores both articulation and segment responsibilities

A `SkeletonNode` carries both `localRotation` (the articulation angle) and `localPosition` (the segment offset). In biomechanics, these are distinct concepts: the articulation is the joint angle, and the segment is the rigid body between joints. The node conflates them into a single object.

### 6.3 Bone exists only for rendering

The `Bone` data class and `SkeletonEngine.bones` list exist solely for rendering — they define which joints to draw a bone between and with what thickness. This rendering topology is hardcoded and duplicates the hierarchy that already exists in the `SkeletonNode` tree. If the skeleton topology changes, both must be updated independently.

### 6.4 JointIntent is ignored for contact poses

`RelativeArticulation` (jointIntents) and `SpineCurve` (spineIntent) are consumed by `SkeletonPoseFinalizer.applyIntentCarriers()`, but this method is a no-op for contact poses (`if (pose.hasContacts()) return`). This means that for any pose with fixed support contacts, the intent carriers are populated but never consumed — the solver repositions the root and re-bakes the contact limbs, but the authored articulations are not re-applied after the solver's FK. The Finalizer's FK traversal uses the solver-settled node transforms, not the intent carriers.

### 6.5 Body segments and articulations share the same identifier namespace

The `Joint` enum is the single identifier for both body segments (PELVIS, LUMBAR, CHEST) and articulations (HIP_F, KNEE_F, SHOULDER_A, etc.). They share the same array index in `SkeletonPose.joints` and `SkeletonPose.rotations`, and the same node in the `SkeletonNode` tree. There is no type-level distinction between a segment (which has no DOF) and an articulation (which has DOF).

### 6.6 HEAD_POS is a marker masquerading as a joint

`HEAD_POS` is a `Joint` enum entry that has no independent DOF. Its position is procedurally derived from the neck direction + a fixed head length (18f). It is a tracking marker, not a joint. Yet it occupies an ordinal index in the flat arrays, participates in FK traversal as a node in the hierarchy, and is validated like a true articulation (bone length check between NECK_END and HEAD_POS).

### 6.7 Wrist/ankle nodes are dual-role: articulation + attachment host

`WRIST_A` / `WRIST_P` and `ANKLE_F` / `ANKLE_B` are classified as articulations (they carry authored rotation) but also serve as attachment hosts (palm/fingertips and heel/toe derive their positions from them). The Finalizer reads the wrist/ankle `localRotation` to compute the relative articulation for extremity derivation, then writes the palm/fingertip/heel/toe positions. This dual role means that a wrist rotation change affects both the forearm orientation (articulation) and the hand position (attachment).

### 6.8 SkeletonEngine.bones hardcodes topology that exists in SkeletonNode tree

The `SkeletonEngine.bones` list defines which joint pairs are connected by rendering bones. This is the same topology as the `SkeletonNode` parent-child relationships. If the skeleton topology changes (e.g., adding a new segment), both the node tree and the bone list must be updated. The bone list should be derived from the skeleton definition, not hardcoded.

### 6.9 ConstraintSolver is a singleton with hardcoded constants

`ConstraintSolver` is an `object` (singleton) with hardcoded constants (`MAX_ITERATIONS = 16`, `RELAX = 0.5f`, `SMOOTH_GAIN = 0.25f`, `TILT_GAIN = 0.01f`, `POSTURE_REG = 0.1f`, etc.). These values cannot be configured per-definition or per-exercise. The `lastSolvedRoot` `WeakHashMap` creates a hidden dependency on `SkeletonPose` identity, meaning the solver's behavior depends on object identity rather than pose content.

### 6.10 SkeletonPoseFinalizer is a monolith (845 lines)

The finalizer accumulates responsibilities from multiple architectural eras: chest-frame reconstruction (Issue F), head-target resolution (Phase 7), extremity orientation derivation (W1), validation stamp production (B5), intent carrier consumption (B2), and contact guard logic (F1/B5). Each of these could be a separate stage or component.

### 6.11 The pipeline mutates input poses

`SkeletonPipeline.produceFrame(builtPose, environment, supportedPoints)` mutates the input `SkeletonPose`'s `environment` and `supportedPoints` fields directly (`builtPose.environment = environment; builtPose.supportedPoints.clear(); builtPose.supportedPoints.addAll(supportedPoints)`). This side effect is not visible in the API signature and can cause bugs if the caller reuses the pose.

### 6.12 The `IK_STAGE_ACTIVE` flag gates a dead code path

`IkStage.apply()` is gated by `IK_STAGE_ACTIVE` (default `false`). When off, `bakeIkLimb` remains the sole limb solver and the `limbTargets` carrier is populated but never consumed. This means the carrier exists as dead weight for the default configuration, and the byte-identity guarantee depends on the flag being off.

### 6.13 `SkeletonNodes` aliases create naming confusion

The `SkeletonNodes` data class provides aliases like `spine = chest`, `shoulderB = shoulderP`, `hipA = hipF`, `wristA = handA`, `wristP = handP`. These aliases suggest that the naming convention is ambiguous — "A" and "B" suffixes are used inconsistently (sometimes left/right, sometimes front/back), and the `spine` alias collapses the two-segment spine (PELVIS → LUMBAR → CHEST) into a single concept.

### 6.14 `ContactSpec` mixes solver-level and biomechanical concepts

`ContactSpec` carries both biomechanical fields (`endJoint`, `targetWorld`) and solver-level fields (`rootJoint`, `parentRotationJoint`, `middleJoint`, `pole`, `constraint`, `straight`). The solver-level fields are implementation details of the constraint solver, not biomechanical intent. A pose author should not need to know about `parentRotationJoint` or `pole` — these are solver internals.

### 6.15 The `SkeletonPose.rotations` array stores world rotations, not local rotations

The `SkeletonPose.rotations` array is populated by `SkeletonNode.flatten()`, which writes `worldRotation` (the world-space rotation computed by FK). However, the intent carriers (`jointIntents`, `extremityArticulations`) store local rotations (relative to the parent segment). This means the flat pose stores world rotations while the intent carriers store local rotations — a unit mismatch that requires conversion when comparing or validating.

### 6.16 `SkeletonNode` scratch buffers are shared across FK traversals

Each `SkeletonNode` has persistent scratch buffers (`pX`, `pY`, `pZ`, `lX`, `lY`, `lZ`, `wX`, `wY`, `wZ`) for FK computation. These are shared across the entire FK traversal, which means the FK is not thread-safe and cannot be parallelized. The scratch buffers are also reused across different FK calls (e.g., the Finalizer calls `updateWorldTransforms` and then `flatten` on the same tree), creating implicit ordering constraints.

### 6.17 `SkeletonSnapshotRenderer` creates a new `SkeletonPipeline` per frame

The `SkeletonSnapshotRenderer` creates a `SkeletonPipeline(engine.definition)` in its constructor, but the `SkeletonRenderer` (Compose) creates a new pipeline via `remember(engine.definition)`. The `SkeletonSnapshotRenderer` pipeline is long-lived, but the `SkeletonRenderer` pipeline is recreated on recomposition. This inconsistency means the two renderers have different lifecycle semantics for the same logical pipeline.

### 6.18 `PoseBuilder` interface has a default `metadata` that is never used by the pipeline

The `PoseBuilder` interface declares `val metadata: PoseMetadata get() = PoseMetadata(camera = CameraDefinition.DEFAULT)`. The metadata is used by `SkeletonPipeline.produceFrame(pose: PoseBuilder, context)` to extract `environment` and `support.contacts`, but the `camera`, `durationSeconds`, `loopMode`, and other metadata fields are never read by the pipeline. The metadata is a partially-used carrier.

---

## Appendix: Document Reference

| Document | Purpose |
|---|---|
| `ARCHITECTURAL_AUDIT_SKELETON_MODEL.md` | The audit that motivated this RFC |
| `docs/ARCHITECTURE_V2.md` | The current architecture specification |
| `docs/ENGINE.md` | The engine philosophy and layer conventions |
| `docs/BIOMECHANICS.md` | Human-movement correctness principles |
| `docs/RFC_MONKENGINE_BASELINE.md` | Governance source of truth |
| `docs/RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` | The system map |
| `docs/RFC_MONKENGINE_TASK_EXECUTION.md` | The execution contract |
| `docs/RFC_MONKENGINE_DEFINITION_OF_DONE.md` | The acceptance gate |