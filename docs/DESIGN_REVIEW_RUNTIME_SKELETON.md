# Design Review: Runtime Skeleton Semantic Model

> Verifies the previous audit against the source code.
> No code changes. No renaming. No implementation proposals.

---

## Verification of Previous Audit Claims

### Task 1 — Joint Classification

| Claim in Previous Audit | Verification | Verdict |
|---|---|---|
| 33 Joint enum entries | Confirmed: indices 0–32, all contiguous | **Confirmed** |
| PELVIS = SEGMENT | Confirmed: root body segment, solver repositions it | **Confirmed** |
| LUMBAR = SEGMENT | Confirmed: pass-through segment between PELVIS and CHEST | **Confirmed** |
| CHEST = SEGMENT | Confirmed: thorax segment, carries segment orientation | **Confirmed** |
| NECK_END = ARTICULATION | Confirmed: rotational DOF between CHEST and HEAD | **Confirmed** |
| HEAD_POS = ATTACHMENT | Confirmed: no authored rotation, position derived from neck + head length | **Confirmed** |
| CLAVICLE_A/SCAPULA_A/SHOULDER_A/ELBOW_A = ARTICULATION | Confirmed: each carries rotational DOF | **Confirmed** |
| HAND_A = ATTACHMENT | Confirmed: terminal IK target, no authored rotation | **Confirmed** |
| WRIST_A = HELPER | **Partially correct** — WRIST_A exists in the Joint enum but has NO SkeletonNode in the tree. The SkeletonNodes data class aliases `wristA` to `handA`. WRIST_A is used only as a position marker in the Finalizer (`adjustHandOrientation` sets `wrist.position = hand.position`). It is not a node in the FK tree. | **Partially correct** |
| PALM_A/KNUCKLES_A/FINGERTIPS_A = ATTACHMENT | Confirmed: positions derived by Finalizer, no rotation | **Confirmed** |
| CLAVICLE_P/SCAPULA_P/SHOULDER_P/ELBOW_P = ARTICULATION | Confirmed: mirrors left side | **Confirmed** |
| HAND_P = ATTACHMENT | Confirmed | **Confirmed** |
| WRIST_P = HELPER | Same issue as WRIST_A — no SkeletonNode in tree, alias for handP | **Partially correct** |
| PALM_P/KNUCKLES_P/FINGERTIPS_P = ATTACHMENT | Confirmed | **Confirmed** |
| HIP_F/KNEE_F/ANKLE_F = ARTICULATION | Confirmed: each carries rotational DOF | **Confirmed** |
| HEEL_F/TOE_F = ATTACHMENT | Confirmed: contact markers, positions derived by Finalizer | **Confirmed** |
| HIP_B/KNEE_B/ANKLE_B = ARTICULATION | Confirmed: mirrors left side | **Confirmed** |
| HEEL_B/TOE_B = ATTACHMENT | Confirmed | **Confirmed** |

### Correction: WRIST_A/P Classification

WRIST_A and WRIST_P should be classified as **ATTACHMENT** (or more precisely, as **phantom attachment markers**), not HELPER. They have no SkeletonNode in the tree — they are enum entries used as position markers in the Finalizer. The `SkeletonNodes` data class aliases `wristA` to `handA` and `wristP` to `handP`. There is no separate node for them.

Similarly, ANKLE_F and ANKLE_B are actual SkeletonNodes in the tree (they have children: HEEL_F/TOE_F and HEEL_B/TOE_B), so they are correctly classified as ARTICULATION. But they also serve as attachment hosts (their children are heel/toe markers), which is a dual-role issue.

### Task 2 — SkeletonNode Ownership

| Claim in Previous Audit | Verification | Verdict |
|---|---|---|
| SkeletonNode is a four-in-one: coordinate frame + articulation + segment + attachment host | Confirmed by code. `SkeletonNode` carries `localPosition` (segment offset), `localRotation` (articulation angle), `worldPosition`/`worldRotation` (FK-computed frame), and `children` (attachment topology). | **Confirmed** |
| The node is the primary runtime object | Confirmed — it is the only mutable object that persists across pipeline stages | **Confirmed** |
| The `joint: Joint` field provides biomechanical identity | Confirmed — every node has a Joint enum identity used for FK flattening and lookup | **Confirmed** |

### Missing Detail: SkeletonNode Scratch Buffers

Each `SkeletonNode` has 9 persistent scratch `Vector3` buffers (`pX/Y/Z`, `lX/Y/Z`, `wX/Y/Z`) for FK matrix computation. These are shared across the entire FK traversal, making FK non-parallelizable and creating implicit ordering constraints when the Finalizer calls `updateWorldTransforms` and `flatten` on the same tree.

### Task 3 — Parent-Child Semantics

| Claim in Previous Audit | Verification | Verdict |
|---|---|---|
| PELVIS→LUMBAR = rigid body | Confirmed: LUMBAR is a segment with `localPosition` offset from PELVIS | **Confirmed** |
| LUMBAR→CHEST = rigid body | Confirmed: CHEST is a segment with `localPosition` offset from LUMBAR | **Confirmed** |
| CHEST→NECK_END = articulation | Confirmed: NECK_END carries rotational DOF | **Confirmed** |
| NECK_END→HEAD_POS = attachment | Confirmed: HEAD_POS has no rotation, position derived | **Confirmed** |
| CHEST→CLAVICLE_A = articulation | Confirmed | **Confirmed** |
| CLAVICLE_A→SCAPULA_A = articulation | Confirmed | **Confirmed** |
| SCAPULA_A→SHOULDER_A = articulation | Confirmed | **Confirmed** |
| SHOULDER_A→ELBOW_A = articulation | Confirmed | **Confirmed** |
| ELBOW_A→HAND_A = attachment | Confirmed: HAND_A is terminal, position set by IK | **Confirmed** |
| HAND_A→PALM_A = attachment | **Incorrect** — there is no WRIST_A node between HAND_A and PALM_A. The chain is HAND_A→PALM_A directly. WRIST_A is a phantom enum entry with no node in the tree. | **Incorrect** |
| PALM_A→KNUCKLES_A = attachment | Confirmed | **Confirmed** |
| KNUCKLES_A→FINGERTIPS_A = attachment | Confirmed | **Confirmed** |
| HIP_F→KNEE_F = articulation | Confirmed | **Confirmed** |
| KNEE_F→ANKLE_F = articulation | Confirmed | **Confirmed** |
| ANKLE_F→HEEL_F = attachment | Confirmed | **Confirmed** |
| ANKLE_F→TOE_F = attachment | Confirmed | **Confirmed** |

### Correction: HAND_A→PALM_A Relationship

The previous audit listed `HAND_A→WRIST_A→PALM_A` as a helper→attachment chain. This is incorrect. There is no WRIST_A node in the tree. The chain is `HAND_A→PALM_A` directly. WRIST_A is a phantom enum entry used as a position marker in the Finalizer, not a tree node.

### Task 4 — Subsystem Responsibility Table

| Claim in Previous Audit | Verification | Verdict |
|---|---|---|
| Pose.build() reads SkeletonDefinition and writes SkeletonNode local transforms | Confirmed | **Confirmed** |
| Pose.build() writes SkeletonPose.joints, .rotations, .roots via fromHierarchy | Confirmed | **Confirmed** |
| Pose.build() writes intent carriers (contacts, limbTargets, jointIntents, etc.) | Confirmed | **Confirmed** |
| IkStage.apply() reads pose.limbTargets and writes SkeletonNode.localPosition | Confirmed | **Confirmed** |
| ConstraintSolver.solve() reads pose.contacts, postureIntent, roots and mutates pelvis + contact limb nodes | Confirmed | **Confirmed** |
| ConstraintSolver.solve() calls SkeletonPose.fromHierarchy() at the end (FK + flatten) | Confirmed — line 422 of ConstraintSolver.kt | **Confirmed** |
| SkeletonPoseFinalizer.finalize() reads pose.roots and mutates SkeletonNode.localRotation (chest, head) | Confirmed | **Confirmed** |
| SkeletonPoseFinalizer.finalize() writes SkeletonPose.joints (all positions including derived heel/toe/palm/fingertips) | Confirmed | **Confirmed** |
| SkeletonPoseFinalizer.finalize() writes SkeletonPose.rotations (all rotations via flatten) | Confirmed — flatten writes worldRotation | **Confirmed** |
| SkeletonPoseFinalizer mutates SkeletonPose.hipRomStamps, bilateralSymmetryDelta, bilateralOppositeBend | Confirmed — applyValidationStamps() | **Confirmed** |
| ExerciseValidator reads finalized SkeletonPose and writes ValidationReport | Confirmed | **Confirmed** |
| SkeletonRenderer/SkeletonSnapshotRenderer reads finalized SkeletonPose and writes Bitmap/Compose drawing commands | Confirmed | **Confirmed** |
| SkeletonPipeline mutates input pose's environment and supportedPoints in produceFrame(builtPose) overload | Confirmed — lines 78-80 of SkeletonPipeline.kt | **Confirmed** |

### Missing Detail: SkeletonPose.rotations Stores World Rotations

The previous audit correctly identified that `SkeletonPose.rotations` stores world rotations (from `flatten()` which writes `worldRotation`). However, the intent carriers (`jointIntents`, `extremityArticulations`) store local rotations (relative to parent segment). This is a unit mismatch: the flat pose stores world rotations while the intent carriers store local rotations.

### Missing Detail: SkeletonPose.isTransformsUpdated Flag

The `isTransformsUpdated` flag on `SkeletonPose` controls whether the Finalizer re-computes FK or skips it. When the solver has already run `fromHierarchy()` (which calls `updateWorldTransforms` + `flatten`), the Finalizer skips FK and just copies roots. This flag is a subtle lifecycle control that the previous audit did not document.

---

## Semantic Model of Runtime Concepts

### 1. Segment

**Does it exist explicitly?** No. There is no `Segment` class or type.

**Where is it encoded?** In `SkeletonNode.localPosition` (the bone offset from parent) and in the `SkeletonDefinition` bone lengths (torsoLength, thighLength, etc.). A segment is implicitly the rigid body between two articulations, represented by a parent-child pair of `SkeletonNode` instances.

**Which class owns it?** `SkeletonNode` (the parent node carries the segment offset).

**Which subsystem mutates it?** `PoseBuilder.build()` (authors `localPosition`), `ConstraintSolver.solve()` (re-bakes contact limb offsets into `localPosition`), `SkeletonPoseFinalizer.finalize()` (reconstructs chest frame, which changes `chest.localRotation` and re-FKs the subtree).

**Which subsystem consumes it?** `SkeletonProjector` (uses joint positions to draw bones), `ExerciseValidator` (validates bone lengths), `SkeletonPoseFinalizer` (derives extremity geometry from segment directions).

### 2. Articulation

**Does it exist explicitly?** No. There is no `Articulation` class or type.

**Where is it encoded?** In `SkeletonNode.localRotation` (the joint angle) and in the `Joint` enum identity (which determines the joint type and constraints). The `SkeletonPose.rotations` array stores world-space rotations; the intent carriers (`jointIntents`, `extremityArticulations`) store local-space rotations.

**Which class owns it?** `SkeletonNode` (the `localRotation` field).

**Which subsystem mutates it?** `PoseBuilder.build()` (authors `localRotation`), `ConstraintSolver.solve()` (re-bakes contact limbs, writes `localRotation` via IK), `SkeletonPoseFinalizer.finalize()` (reconstructs chest frame, resolves head target, applies intent carriers).

**Which subsystem consumes it?** `SkeletonPoseFinalizer` (FK traversal uses `localRotation` to compute `worldRotation`), `ExerciseValidator` (validates angular joint limits using world positions, not rotations directly), `SkeletonProjector` (uses `worldRotation` for torso face computation).

### 3. Attachment

**Does it exist explicitly?** No. There is no `Attachment` class or type.

**Where is it encoded?** In `Joint` enum entries like `HEEL_F`, `PALM_A`, `FINGERTIPS_A` that have no authored rotation and whose positions are derived by the Finalizer. Also in `SupportPoint` enum values that map to these joints.

**Which class owns it?** `SkeletonPose` (the `supportedPoints` set and the joint position arrays).

**Which subsystem mutates it?** `SkeletonPoseFinalizer.finalize()` (computes heel/toe/palm/fingertip positions and writes them to `SkeletonPose.joints`).

**Which subsystem consumes it?** `ExerciseValidator` (validates foot ground penetration, hand sliding), `ConstraintSolver` (uses contact specs to identify support points), `SkeletonProjector` (projects attachment positions to screen space).

### 4. IK Effector

**Does it exist explicitly?** No. There is no `IkEffector` or `EndEffector` class.

**Where is it encoded?** In `ContactSpec.endJoint` and `WorldTarget.joint`. The `ContactSpec` is created by `bakeIkLimb()` and consumed by `ConstraintSolver.solve()`. The `WorldTarget` is created by `bakeIkLimb()` and consumed by `IkStage.apply()` (when active).

**Which class owns it?** `SkeletonPose.contacts` (list of `ContactSpec`) and `SkeletonPose.limbTargets` (list of `WorldTarget`).

**Which subsystem mutates it?** `PoseBuilder.build()` (via `bakeIkLimb()` which adds to `contacts` and `limbTargets`).

**Which subsystem consumes it?** `ConstraintSolver.solve()` (reads `contacts`), `IkStage.apply()` (reads `limbTargets`), `SkeletonPoseFinalizer.finalize()` (reads `contacts` for the contact guard in chest-frame reconstruction).

### 5. Contact

**Does it exist explicitly?** Yes — `ContactSpec` is a data class. Also `ContactConstraint` (referenced in `ContactSpec.contact` field).

**Where is it encoded?** `ContactSpec` carries the full IK context for a fixed support contact: endJoint, rootJoint, parentRotationJoint, middleJoint, targetWorld, pole, lengths, constraint, straight flag, and optional ContactConstraint.

**Which class owns it?** `SkeletonPose.contacts` (mutable list).

**Which subsystem mutates it?** `PoseBuilder.build()` (via `bakeIkLimb()` which adds `ContactSpec` to `pose.contacts`).

**Which subsystem consumes it?** `ConstraintSolver.solve()` (primary consumer — repositions root and re-bakes limbs), `SkeletonPoseFinalizer.finalize()` (reads for contact guard in chest reconstruction), `ExerciseValidator` (validates contact preservation).

### 6. Procedural Helper

**Does it exist explicitly?** No. There is no `ProceduralHelper` or `RigHelper` class.

**Where is it encoded?** In `SkeletonNode` entries that carry authored rotation but are not anatomical joints — specifically `WRIST_A` and `WRIST_P` (which are phantom enum entries with no tree node, used as position markers in the Finalizer). Also in the `extremityArticulations` carrier on `SkeletonPose`, which stores wrist/ankle rotations separately from the node tree.

**Which class owns it?** `SkeletonPose.extremityArticulations` (the carrier for wrist/ankle rotations).

**Which subsystem mutates it?** `PoseBuilder.build()` (via `buildWristArticulation()` and `buildAnkleArticulation()` which write to `extremityArticulations`).

**Which subsystem consumes it?** `SkeletonPoseFinalizer.finalize()` (reads `extremityArticulations` for W1 extremity geometry derivation).

### 7. Coordinate Frame

**Does it exist explicitly?** Yes — `SkeletonNode` is the coordinate frame container. Also `JointRotation` (axis-angle) and `Vector3` (position).

**Where is it encoded?** `SkeletonNode.localPosition` + `SkeletonNode.localRotation` (local frame), `SkeletonNode.worldPosition` + `SkeletonNode.worldRotation` (world frame).

**Which class owns it?** `SkeletonNode`.

**Which subsystem mutates it?** `SkeletonNode.updateWorldTransforms()` (computes world frame from parent frame + local frame), `SkeletonPoseFinalizer.finalize()` (mutates `localRotation` for chest reconstruction and head-target resolution).

**Which subsystem consumes it?** `SkeletonNode.flatten()` (writes world frame to `SkeletonPose`), `ConstraintSolver.solve()` (reads world positions for IK solve), `SkeletonProjector` (reads world positions for projection).

### 8. Snapshot

**Does it exist explicitly?** Yes — `SkeletonPose` is the snapshot. Also `PipelineResult` and `ValidatedFrame` are frame-level snapshots.

**Where is it encoded?** `SkeletonPose` with its flat `joints` and `rotations` arrays, plus intent carriers and state stamps.

**Which class owns it?** Created by `PoseBuilder.build()`, owned by the pipeline during `runStages()`, returned to the caller.

**Which subsystem mutates it?** Every pipeline stage mutates it in some way (IkStage mutates node positions, ConstraintSolver mutates node positions and pose stamps, Finalizer mutates node rotations and pose arrays).

**Which subsystem consumes it?** `SkeletonRenderer`, `SkeletonSnapshotRenderer`, `ExerciseValidator`, and the caller of `SkeletonPipeline.produceFrame()`.

### 9. Intent

**Does it exist explicitly?** Yes — multiple intent carriers on `SkeletonPose`: `jointIntents`, `spineIntent`, `limbTargets`, `postureIntent`, `headTarget`, `headings`, `extremityOverrides`, `extremityArticulations`, `contactPrecedence`, `environment`, `supportedPoints`.

**Where is it encoded?** In the §1.1 section of `SkeletonPose` (the intent section, documented in the class KDoc).

**Which class owns it?** `SkeletonPose` (the intent carriers are fields on the pose object).

**Which subsystem mutates it?** `PoseBuilder.build()` (writes all intent carriers), `SkeletonPipeline.produceFrame()` (stamps environment and supportedPoints onto the pose).

**Which subsystem consumes it?** `ConstraintSolver.solve()` (reads postureIntent, contacts, contactPrecedence), `SkeletonPoseFinalizer.finalize()` (reads all intent carriers), `IkStage.apply()` (reads limbTargets).

### 10. Solver State

**Does it exist explicitly?** Partially. `ConstraintSolver` has internal scratch buffers and a `WeakHashMap<SkeletonPose, Vector3>` for inter-frame smoothing. `SkeletonPose` has state stamps (`rootTranslationDelta`, `rootRotationDelta`, `boneLengthsVerified`, `straightIntentDropped`, `maxIkClampAmount`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`).

**Where is it encoded?** In `ConstraintSolver`'s private fields (scratch buffers, `lastSolvedRoot` cache) and in `SkeletonPose`'s state stamp fields.

**Which class owns it?** `ConstraintSolver` (singleton, owns scratch buffers and inter-frame cache), `SkeletonPose` (owns state stamps).

**Which subsystem mutates it?** `ConstraintSolver.solve()` (mutates scratch buffers, updates `lastSolvedRoot` cache, writes state stamps on `SkeletonPose`), `SkeletonPoseFinalizer.finalize()` (writes validation stamps on `SkeletonPose`).

**Which subsystem consumes it?** `ExerciseValidator` (reads state stamps from `SkeletonPose`), `SkeletonPoseFinalizer` (reads `boneLengthsVerified` and `maxIkClampAmount`).

---

## Joint Enum Analysis

### The Joint Enum Is a Flat Index Namespace, Not a Type System

The `Joint` enum serves three simultaneous purposes:

1. **Array index** — `Joint.ordinal` indexes into `SkeletonPose.joints` and `SkeletonPose.rotations` arrays
2. **Node identity** — `SkeletonNode.joint` references a `Joint` enum value to identify which node in the tree it is
3. **Biomechanical label** — the enum name implies a biomechanical role (articulation, segment, attachment)

These three purposes are conflated. The enum has no category field, no type tag, and no way to distinguish a segment (PELVIS) from an articulation (HIP_F) from an attachment (HEEL_F) at the type level.

### Phantom Enum Entries

`WRIST_A` (index 16) and `WRIST_P` (index 22) are in the `Joint` enum but have no corresponding `SkeletonNode` in the tree. The `SkeletonNodes` data class aliases them to `handA` and `handP` respectively. They exist in the enum solely so that `SkeletonPose.joints` and `SkeletonPose.rotations` arrays have entries at those indices. The Finalizer uses them as position markers (`pose.getJoint(Joint.WRIST_A)` returns a zero vector that the Finalizer then overwrites with the hand position).

### Index Gaps

The enum indices are contiguous (0–32), but the ordering is non-intuitive. The first 11 entries (0–10) are the lower body, then CHEST at 11, then the upper body entries are scattered across indices 12–19, then the left/right pairs at 20–25, then NECK_END at 26, HEAD_POS at 27, and the clavicle/scapula entries at 28–31, with LUMBAR at 32. The index ordering does not follow the tree hierarchy.

---

## SkeletonNode Responsibility Analysis

### Every Independent Responsibility

| # | Responsibility | Fundamental or Accidental? | Could be separated without changing runtime behaviour? |
|---|---|---|---|
| 1 | Hierarchical transform container (localPosition, localRotation, worldPosition, worldRotation) | **Fundamental** | No — this is the core FK substrate |
| 2 | Joint identity (the `joint: Joint` field) | **Fundamental** | No — needed for FK flattening and pose array indexing |
| 3 | Parent/children tree links | **Fundamental** | No — needed for FK traversal |
| 4 | Segment offset storage (`localPosition` as bone length/direction) | **Fundamental** | No — this is the bone geometry |
| 5 | Articulation angle storage (`localRotation` as joint angle) | **Fundamental** | No — this is the DOF |
| 6 | Attachment host (children are endpoints like HEEL, PALM, FINGERTIPS) | **Accidental** | Yes — attachment topology could be a separate data structure |
| 7 | IK solve substrate (solver writes `localPosition` into middle/end nodes) | **Accidental** | Yes — IK results could be written to a separate buffer |
| 8 | Validation data source (Finalizer reads node transforms for stamps) | **Accidental** | Yes — stamps could be computed from the flat pose arrays |
| 9 | Chest-frame reconstruction target (Finalizer reads/writes `chest.localRotation`) | **Accidental** | Yes — chest-frame reconstruction could operate on the flat arrays directly |
| 10 | Head-target resolution target (Finalizer writes `neck.localPosition`, `head.localPosition`) | **Accidental** | Yes — head resolution could write to the flat arrays directly |
| 11 | Extremity orientation derivation substrate (Finalizer reads wrist/ankle `localRotation`) | **Accidental** | Yes — wrist/ankle articulation could be read from `extremityArticulations` carrier |
| 12 | Scratch buffer storage (9 persistent `Vector3` buffers per node for FK) | **Accidental** | Yes — scratch buffers could be thread-local or per-FK-call |

### Summary

Of 12 responsibilities, 5 are fundamental (1–5) and 7 are accidental (6–12). The accidental responsibilities are the ones that conflate biomechanical concepts (attachment, IK, validation, reconstruction) with the core FK substrate.

---

## SkeletonPose Lifecycle and Role

### What SkeletonPose Represents

`SkeletonPose` simultaneously represents **four** things:

1. **Simulation state** — the flat joint map (`joints` + `rotations` arrays) that holds the current world positions and rotations of all 33 joints
2. **Intent carrier** — the §1.1 section (`contacts`, `limbTargets`, `jointIntents`, `spineIntent`, `postureIntent`, `extremityOverrides`, `extremityArticulations`, `headTarget`, `headings`, `environment`, `supportedPoints`) that declares what the pose author wants
3. **Transport object** — it is passed between pipeline stages as the data contract
4. **Solver scratch buffer** — the `previous`/`prePrevious` history in `SkeletonPipeline` uses `SkeletonPose.copyFrom()` to snapshot poses for dynamics validation

### Lifecycle

1. **Creation**: `PoseBuilder.build()` creates a `SkeletonPose` via `SkeletonPose.fromHierarchy()` or by directly populating the arrays
2. **Intent population**: `bakeIkLimb()`, `declarePosture()`, `buildSpineCurve()`, `buildGaze()`, etc. populate the intent carriers
3. **Pipeline mutation**: `IkStage.apply()`, `ConstraintSolver.solve()`, and `SkeletonPoseFinalizer.finalize()` mutate the pose's node tree and flat arrays
4. **Consumption**: `SkeletonRenderer`/`SkeletonSnapshotRenderer` reads the finalized pose for rendering; `ExerciseValidator` reads it for validation
5. **History**: `SkeletonPipeline` keeps `previous` and `prePrevious` copies (via `copyFrom`) for dynamics validation
6. **Discard**: The pose is discarded after the frame is rendered/validated (or kept for the next frame's dynamics check)

### Key Observation

The `SkeletonPose` is **not immutable** — it is mutated in-place by every pipeline stage. The `copyFrom()` method is used only for history snapshots, not for stage-to-stage data transfer. This means the pipeline has implicit ordering constraints: stages must run in the correct sequence because each stage mutates the same object.

---

## Migration Feasibility Assessment

### Option A: Minimal Refactor

**Description**: Add category metadata to the `Joint` enum, introduce thin wrapper types for Segment/Articulation/Attachment that delegate to the existing `SkeletonNode`, keep the pipeline stages unchanged.

| Dimension | Assessment |
|---|---|
| **Risk** | Low — the existing pipeline continues to operate on `SkeletonNode` and `SkeletonPose` unchanged. Category metadata is additive. |
| **Code churn** | Low — enum annotation + wrapper type definitions. No pipeline changes. |
| **Compatibility** | High — the flat array contract (`joints[index]`, `rotations[index]`) remains unchanged. The `Joint.ordinal` indexing is preserved. |
| **Long-term maintainability** | Moderate — the wrapper types provide type safety but the underlying `SkeletonNode` still conflates responsibilities. The wrappers would be a thin layer over a confused foundation. |

### Option B: Incremental Semantic Split

**Description**: Introduce separate `Segment`, `Articulation`, `Attachment`, and `Helper` types that each own their specific data. Gradually migrate `SkeletonNode` fields into the appropriate type. Keep the FK traversal and pipeline stages working through adapters.

| Dimension | Assessment |
|---|---|
| **Risk** | Medium — the FK traversal and flatten logic must be adapted to work with the new types. The `SkeletonPose` flat array contract must be maintained during the transition. |
| **Code churn** | High — new types, adapter logic, modified FK traversal, modified flatten, modified solver, modified finalizer. |
| **Compatibility** | Moderate — the `SkeletonPose` flat array contract can be preserved, but the `SkeletonNode` tree structure changes. Pipeline stages that directly access `SkeletonNode.localPosition`/`localRotation` must be updated. |
| **Long-term maintainability** | High — each concept has its own type with clear responsibilities. The FK substrate is separated from biomechanical identity. |

### Option C: Clean-Room Rewrite

**Description**: Design the runtime model from scratch based on the ideal conceptual model (Segment, Articulation, Attachment, EndEffector, Helper, CoordinateFrame, SkeletonPose). Implement a new pipeline alongside the existing one. Migrate pose definitions incrementally.

| Dimension | Assessment |
|---|---|
| **Risk** | High — the new pipeline must be proven byte-identical to the existing one across all exercise poses. The `SkeletonPose` flat array contract may change. |
| **Code churn** | Very high — new types, new pipeline stages, new FK traversal, new solver, new finalizer, new validator integration, new renderer integration. |
| **Compatibility** | Low — the `SkeletonPose` flat array contract may change (e.g., separating intent from state, separating world from local rotations). The `SkeletonNode` tree is replaced. |
| **Long-term maintainability** | Very high — each concept has a clean, separated type. The pipeline stages have clear contracts. The intent/state boundary is structural. |

### Recommendation

**Option B (Incremental Semantic Split)** is the best path. The reasons:

1. The current architecture's core problem is that `SkeletonNode` conflates 4+ responsibilities. Option A (minimal refactor) does not address this — it just adds labels to a confused foundation.
2. Option C (clean-room rewrite) is too risky for a production codebase — the byte-identity guarantee across all exercise poses would be extremely difficult to verify.
3. Option B addresses the root cause (separation of concerns) while maintaining the existing pipeline contracts through adapters. The `SkeletonPose` flat array contract can be preserved during the transition, ensuring backward compatibility with all exercise definitions.
4. The migration can be done in phases: (a) introduce the new types, (b) migrate `SkeletonNode` to use them internally, (c) update the FK traversal and flatten logic, (d) update the solver and finalizer, (e) remove the old `SkeletonNode` fields.

The key risk of Option B is that the adapters between the new types and the existing pipeline stages add complexity. This can be mitigated by making the adapters thin and by ensuring the new types are the canonical representation (the adapters translate from new → old, not the reverse).

---

## Summary of Findings

1. **The Joint enum is a flat index namespace, not a type system.** It conflates segments, articulations, attachments, and phantom entries (WRIST_A/P) under a single enum with no category metadata.

2. **SkeletonNode is a four-in-one abstraction** (coordinate frame + articulation + segment + attachment host) with 7 accidental responsibilities that could be separated without changing runtime behaviour.

3. **SkeletonPose is simultaneously simulation state, intent carrier, transport object, and solver scratch buffer.** The intent/state boundary is convention-based, not structural.

4. **WRIST_A and WRIST_P are phantom enum entries** — they exist in the `Joint` enum but have no `SkeletonNode` in the tree. They are used as position markers in the Finalizer.

5. **SkeletonPose.rotations stores world rotations** (from `flatten()`), while intent carriers store local rotations. This is a unit mismatch.

6. **The architecture can be evolved incrementally** (Option B), but Option A (minimal refactor) does not address the root cause of the confusion, and Option C (clean-room rewrite) is too risky.

7. **The previous audit's classification of WRIST_A/P as HELPER was incorrect** — they are phantom attachment markers with no tree node. The previous audit's parent-child chain `HAND_A→WRIST_A→PALM_A` was also incorrect — the chain is `HAND_A→PALM_A` directly.