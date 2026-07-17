# RFC_DECLARATIVE_AUTHORING

> **Branch:** Architecture v2 — **Branch B: Declarative Pose Authoring** (future).
> **Document type:** *Semantic inventory of pose authoring* — **not** an implementation plan, **not** an
> API design, **not** pseudocode, **not** a proposal. Its only purpose is to fix the **semantic boundary**
> between declarative authoring and runtime geometry before any Branch B work begins.
> **Status:** authoritative specification for Branch B (companion to `RFC_DECLARATIVE_POSE_AUTHORING.md`,
> which is the design; this document is the per-helper semantic classification that the design must honour).
> **Companion RFCs:** `RFC_DECLARATIVE_POSE_AUTHORING.md` (Branch B design), `RFC_BRANCH_B_IMPLEMENTATION.md`
> (Branch B migration plan), `RFC_PHASE_I_CLOSURE.md` (Branch A closure + ownership model),
> `RFC_INTENT_BUILDER_REWRITE.md` (current-state audit), `RFC_INTENT_LAYER.md` (§1.1 intent model),
> `RFC_GAP_CLOSURE.md` (milestone gates).
> **Date:** 2026-07-17. **Verified against:** `BasePose.kt` helper inventory + §1.1 carrier audit
> (`RFC_INTENT_BUILDER_REWRITE.md` §2) + `RFC_PHASE_I_CLOSURE.md` §2/§5/§6.
>
> **Constraints:** no production code modified; no new API designed; no pseudocode; no implementation
> proposed. This is a classification document.

---

## 0. Principles used for every classification

Three Architecture v2 principles decide the semantic fate of each helper:

- **P1 — Pose expresses intent.** A pose may *declare* what the body should do (a spine curve, a joint
  articulation, a limb world target, a posture, a contact, a head target, an extremity override). It may
  not *compute* the transform that realizes it.
- **P2 — Engine owns geometry.** Every `SkeletonNode` transform (`localPosition` / `localRotation`) is
  written by an engine stage (IkStage / Finalizer / Solver), never by a pose. The engine derives shape
  and placement from intent.
- **P3 — Validation observes.** Validation reads §1.2 + §1.1 ROM only; it never writes a transform.

A helper's classification falls into exactly one of four buckets:

| Bucket | Meaning |
|--------|---------|
| **Engine helper** | stays an engine-stage responsibility; the pose never calls it to write a transform. It is geometry/FK/IK/solver work the engine owns under P2. |
| **Becomes intent** | the pose's *call* is reclassified as a declaration; the geometry it currently writes moves to an engine stage that consumes the declared carrier. |
| **Becomes obsolete** | the helper expresses something the engine should own or that has no place in declarative authoring; it is removed, not migrated. |
| **Splits into engine + intent** | part of the helper is a declaration (pose side, P1) and part is geometry (engine side, P2); the two are separated. |

---

## 1. Semantic inventory — every `BasePose` helper

Each entry states: **what it does today**, **current writes**, **classification**, and **why** (by P1/P2/P3).
Helpers already on intent are noted as such; the rest are classified by the boundary they cross.

### 1.1 Trunk / spine

#### `buildSpineCurve(lower, chest, lowerRad, thoracicRad, axis)`
- **Today:** writes `lower.localRotation` and `chest.localRotation` directly (two-segment trunk lean).
- **Classification:** **Becomes intent.**
- **Why:** a trunk lean is *intent* (the pose wants lumbar X° + thoracic Y° about an axis). Under P1 the
  pose declares `spineIntent` (a `SpineCurve`); under P2 the Finalizer expands that curve into the
  pelvis/lumbar/chest rotations. The pose must stop writing those rotations (it does not own them — P2).
  `spineIntent` is currently **dead** (`RFC_INTENT_BUILDER_REWRITE.md` §2); this helper is exactly the
  authoring path that should populate it.

#### `buildLumbarFlexion(lumbar, flexionRad, axis)`
- **Today:** writes `lumbar.localRotation` directly (lower-spine/ pelvis-tilt segment of the two-segment spine).
- **Classification:** **Becomes intent** (folded into the spine declaration).
- **Why:** it is the lower-spine half of the same trunk intent as `buildSpineCurve`; under P1 it is one
  field of `spineIntent` (lumbar component). The pose declares it; the Finalizer writes the rotation (P2).
  Keeping it as a separate node-writing helper violates P2.

#### `buildChestTwist(chest, twistRad)`
- **Today:** writes `chest.localRotation` (thoracic twist about local +Y).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a CHEST relative articulation).
- **Why:** a chest twist is a *relative articulation of a joint* — the textbook `jointIntents` case
  (P1). The pose declares it; the Finalizer applies it (P2). `jointIntents` is currently **dead**; this
  helper is the authoring path that should populate it.

#### `buildChestSideBend(chest, sideBendRad)`
- **Today:** writes `chest.localRotation` (thoracic side-bend about local +X).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a CHEST relative articulation).
- **Why:** same as `buildChestTwist` — a relative joint articulation (P1 → `jointIntents`; P2 → Finalizer).
  Pose must not write the chest rotation directly (P2).

#### `buildChestOrientation(chest, leanRad, twistRad, sideBendRad)`
- **Today:** composes a 3-DOF chest rotation (lean + twist + side-bend) and writes `chest.localRotation`
  directly via matrix multiply.
- **Classification:** **Becomes intent** (folded into `jointIntents` as a single CHEST relative articulation).
- **Why:** it is the composed form of the three chest articulations above. Under P1 the pose declares one
  CHEST relative articulation (the three components are the *intent*, not three node writes); under P2 the
  Finalizer derives the exact chest rotation from that single declaration. The matrix-math that builds the
  rotation belongs to the engine (P2), not the pose.

### 1.2 Hip

#### `buildHipFlexion(hip, flexionRad)`
- **Today:** writes `hip.localRotation` (sagittal hip flexion about local +Z).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a HIP relative articulation).
- **Why:** a single hip DOF is intent (P1 → `jointIntents`); the engine writes the rotation (P2). Pose must
  not hand-write `hip.localRotation` (P2).

#### `buildHipAbduction(hip, abductionRad, sideSign)`
- **Today:** writes `hip.localRotation` (frontal abduction, mirrored by `sideSign`).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a HIP relative articulation).
- **Why:** intent (P1 → `jointIntents`); engine writes the mirrored rotation (P2). `sideSign` is a
  declaration parameter, not a geometry computation the pose performs.

#### `buildHipRotation(hip, rotationRad, sideSign)`
- **Today:** writes `hip.localRotation` (femoral axial rotation about local +X, mirrored).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a HIP relative articulation).
- **Why:** intent (P1 → `jointIntents`); engine writes the rotation (P2).

#### `buildHipOrientation(hip, flexionRad, abductionRad, rotationRad, sideSign)`
- **Today:** composes a 3-DOF hip rotation and writes `hip.localRotation` directly via `SkeletonMath.buildHipRotation`.
- **Classification:** **Becomes intent** (folded into `jointIntents` as a single HIP relative articulation).
- **Why:** the composed form of the three hip DOFs. Under P1 the pose declares one HIP relative articulation;
  under P2 the engine derives the exact hip rotation (the `SkeletonMath.buildHipRotation` composition is
  engine geometry, P2). The pose must not write `hip.localRotation` (P2).

### 1.3 Shoulder girdle / clavicle

#### `buildClavicularRotation(clavicle, elevation, protraction, axialRotation, sideSign)`
- **Today:** composes a 3-DOF clavicle rotation and writes `clavicle.localRotation` directly via
  `SkeletonMath.buildClavicularRotation` (UNI-7: closes the dead-clavicle gap).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a CLAVICLE relative articulation).
- **Why:** a girdle articulation is a relative joint articulation (P1 → `jointIntents`); the engine writes
  the composed rotation (P2). The pose declares the three components; the engine owns the composition (P2).

### 1.4 Extremities (wrist / ankle)

#### `buildWristArticulation(hand, flexion, deviation)`
- **Today:** writes `hand.localRotation` (2-DOF wrist via `SkeletonMath.buildWristRotation`, UNI-8).
- **Classification:** **Becomes intent** (folded into `jointIntents` as a HAND relative articulation).
- **Why:** a distal joint articulation is intent (P1 → `jointIntents`); the engine writes the composed
  rotation (P2). The pose declares flexion+deviation; the engine composes (P2).

#### `buildAnkleArticulation(ankle, dorsiflexion, inversion)`
- **Today:** writes `ankle.localRotation` (2-DOF ankle via `SkeletonMath.buildAnkleRotation`, UNI-8).
- **Classification:** **Becomes intent** (folded into `jointIntents` as an ANKLE relative articulation).
- **Why:** same as wrist — distal joint articulation (P1 → `jointIntents`; P2 → engine writes rotation).

### 1.5 Rigid segment geometry

#### `buildRigidSegment(parent, child, offsetX, offsetY, offsetZ)`
- **Today:** writes `child.localPosition` directly from a fixed offset.
- **Classification:** **Becomes intent** (declared as a structural segment offset).
- **Why:** a fixed parent→child offset is *intent* (the pose declares the skeleton's proportions/attachment
  points, P1); the engine writes `child.localPosition` from that declaration during tree construction (P2).
  A rigid offset is geometry the engine owns (P2), but the *offset value* is a declaration the pose authors.

#### `buildTorso(pelvis, chest, torsoLength)`
- **Today:** writes `chest.localPosition` from a fixed torso length.
- **Classification:** **Becomes intent** (declared as a structural segment offset).
- **Why:** same as `buildRigidSegment` — a fixed offset is intent (P1); the engine writes the
  `localPosition` (P2). The pose declares the torso length; the engine places the chest.

#### `buildPelvis(pelvis, hipF, hipB, hipWidth)`
- **Today:** writes `hipF.localPosition` / `hipB.localPosition` from hip width.
- **Classification:** **Becomes intent** (declared as a structural segment offset).
- **Why:** fixed offset (hip attachment points) is intent (P1); engine writes the `localPosition`s (P2).

#### `buildShoulders(shoulderA, shoulderP, shoulderWidth)`
- **Today:** writes `shoulderA.localPosition` / `shoulderP.localPosition` from shoulder width.
- **Classification:** **Becomes intent** (declared as a structural segment offset).
- **Why:** fixed offset (shoulder attachment points) is intent (P1); engine writes the `localPosition`s (P2).

#### `buildHead(neck, head, neckLength, headDir)`
- **Today:** writes `neck.localPosition` / `head.localPosition` from neck length + head direction.
- **Classification:** **Splits into engine + intent.**
- **Why:** two distinct concerns. (a) The *neck/head attachment offsets* (lengths) are intent (P1 → declared
  structural offsets; engine writes `localPosition`, P2). (b) The *head direction* (`headDir`) is the gaze
  intent — already realized separately by `buildGaze` → `headTarget` (consumed by the Finalizer's
  `resolveHeadTarget`, Phase 7 complete). So `buildHead` splits: its offset half becomes a structural
  declaration, and its directional half is superseded by the existing `headTarget` intent. The pose must
  not compute head placement as node writes (P2).

### 1.6 Gaze

#### `buildGaze(neck, head, neckLength, gazeDir, targetDistance)`
- **Today:** writes **no node**; records `pose.headTarget` (a synthetic world target along `gazeDir`).
- **Classification:** **Remains an engine helper / already intent (no change in kind).**
- **Why:** this helper is *already* declarative — it declares `headTarget`, which the Finalizer consumes
  (`resolveHeadTarget`, Phase 7 complete). Under P1 it is correct authoring; under P2 the Finalizer owns the
  resulting neck/head transforms. It is the existence proof that the intent model works end-to-end. It
  stays as the gaze-declaration helper; only its name/shape may be normalized by the Branch B API
  (`RFC_DECLARATIVE_POSE_AUTHORING.md` §3), but its *semantic classification* does not change.

### 1.7 IK / limb solving

#### `bakeIkLimb(rootWorldPos, targetWorldPos, length1, length2, pole, constraint, parentRotation, middleNode, endNode, ikBuffer, straight, contact)`
- **Today:** solves IK (`SkeletonMath.solveIK` / `solveStraightLimb`), writes `middleNode.localPosition` /
  `endNode.localPosition` via `toLocalDirection`, stamps `maxIkClampAmount` / `boneLengthsVerified`, and
  (when `contact != null`) registers `pose.contacts`.
- **Classification:** **Splits into engine + intent.**
- **Why:** it currently does *both* jobs in one call, which is precisely the violation Branch B removes:
  - **Intent half (pose side, P1):** the pose *declares* a limb end-effector world target + constraint +
    optional contact. This is `limbTargets` (+ `contacts` when a contact is declared). The pose must stop
    solving and stop writing nodes.
  - **Engine half (P2):** the IK solve, the `toLocalDirection` conversion, and the bone-length stamp are
    geometry the engine owns — extracted into the Branch B `IkStage` (already deferred per
    `RFC_GAP_CLOSURE.md` M2 note). The `contacts` carrier is **live** today (consumed by the Solver); only
    the `limbTargets` carrier is dead and must become the intent vehicle replacing the node writes.
  So `bakeIkLimb` splits: its declaration arguments become an intent call; its solve+write+stamp body
  becomes engine-stage work.

#### `solveArmIK` / `solveLegIK` / `solveStraightArmIK` / `solveStraightLegIK` / `solveNearStraightLeg`
- **Today:** thin wrappers delegating to `SkeletonMath.solveIK` / `solveStraightLimb` / `solveNearStraightLimb`.
- **Classification:** **Become obsolete** (as pose-side helpers).
- **Why:** these are pure solver delegations the pose should never call — IK is engine geometry (P2). Once
  `bakeIkLimb` splits and the `IkStage` owns solving, the pose has no reason to invoke the solver directly.
  The underlying `SkeletonMath` functions remain (engine helpers), but the pose-side wrappers are removed.

### 1.8 Posture / contact declaration

#### `declarePosture(pose, kind, tolerance, precedence)`
- **Today:** writes `pose.postureIntent` and `pose.contactPrecedence` (no node write).
- **Classification:** **Remains an engine helper / already intent (no change in kind).**
- **Why:** it is already declarative (P1); `postureIntent` + `contactPrecedence` are **live** carriers
  consumed by the Solver (M3/M4). Under P2 the Solver owns the resulting root. It stays as the posture
  declaration; only its surface may normalize into the Branch B API.

#### `overrideExtremityOrientation(pose, extremity)`
- **Today:** calls `pose.overrideExtremityOrientation(extremity)` → adds to `pose.extremityOverrides`.
  Writes no node. The Finalizer has a read path (`isExtremityAutomatic`) but **no pose populates it**
  (dormant — `RFC_INTENT_BUILDER_REWRITE.md` §2).
- **Classification:** **Remains an engine helper / already intent (no change in kind); its consumer must be
  made real.**
- **Why:** it is already a declaration (P1 → `extremityOverrides`). The bug is not its classification but
  that the engine consumer is dormant. Under Branch B the Finalizer honors `extremityOverrides` (skips
  derivation for declared extremities), making the dormant carrier live. The helper itself stays.

### 1.9 Motion / support helpers (non-transform)

#### `phase` / `downMotion` / `alternating` / `parabolicFootLift`
- **Today:** stateless motion-driver computations returning a scalar/progress; write no transform.
- **Classification:** **Remain engine helpers (unchanged).**
- **Why:** they are pure functions over `progress` (motion math), not authoring of body geometry. They
  belong to the engine/runtime math layer, not to pose→node writing. P1/P2/P3 do not reclassify them; the
  pose may keep calling them to compute *intent parameters* (e.g. how far through a rep), which is still
  intent, not geometry.

#### `leftFoot` / `rightFoot` / `bothFeet` / `leftKnee` / `rightKnee` / `hands`
- **Today:** return `SupportContact` / `Set<SupportContact>` constants; write no transform.
- **Classification:** **Remain engine helpers (unchanged).**
- **Why:** they are identifiers/metadata, not geometry. They may feed `declarePosture`'s precedence or a
  contact declaration. No transform is written, so P2 is unaffected.

---

## 2. Classification summary

| Helper | Bucket | Carrier / engine owner |
|--------|--------|------------------------|
| `buildSpineCurve` | Becomes intent | `spineIntent` → Finalizer |
| `buildLumbarFlexion` | Becomes intent | `spineIntent` (lumbar) → Finalizer |
| `buildChestTwist` | Becomes intent | `jointIntents` (CHEST) → Finalizer |
| `buildChestSideBend` | Becomes intent | `jointIntents` (CHEST) → Finalizer |
| `buildChestOrientation` | Becomes intent | `jointIntents` (CHEST) → Finalizer |
| `buildHipFlexion` | Becomes intent | `jointIntents` (HIP) → Finalizer |
| `buildHipAbduction` | Becomes intent | `jointIntents` (HIP) → Finalizer |
| `buildHipRotation` | Becomes intent | `jointIntents` (HIP) → Finalizer |
| `buildHipOrientation` | Becomes intent | `jointIntents` (HIP) → Finalizer |
| `buildClavicularRotation` | Becomes intent | `jointIntents` (CLAVICLE) → Finalizer |
| `buildWristArticulation` | Becomes intent | `jointIntents` (HAND) → Finalizer |
| `buildAnkleArticulation` | Becomes intent | `jointIntents` (ANKLE) → Finalizer |
| `buildRigidSegment` | Becomes intent | structural offset → Finalizer/IkStage |
| `buildTorso` | Becomes intent | structural offset → Finalizer |
| `buildPelvis` | Becomes intent | structural offset → Finalizer |
| `buildShoulders` | Becomes intent | structural offset → Finalizer |
| `buildHead` | Splits (engine + intent) | offset → Finalizer; direction → `headTarget` (already live) |
| `buildGaze` | Remains (already intent) | `headTarget` → Finalizer (live, Phase 7) |
| `bakeIkLimb` | Splits (engine + intent) | `limbTargets`+`contacts` (intent) / IK solve+write (engine `IkStage`) |
| `solveArmIK` / `solveLegIK` / `solveStraightArmIK` / `solveStraightLegIK` / `solveNearStraightLeg` | Becomes obsolete (pose-side wrappers) | solver stays in `SkeletonMath` (engine) |
| `declarePosture` | Remains (already intent) | `postureIntent`+`contactPrecedence` → Solver (live, M3/M4) |
| `overrideExtremityOrientation` | Remains (already intent; consumer dormant) | `extremityOverrides` → Finalizer (make live) |
| `phase` / `downMotion` / `alternating` / `parabolicFootLift` | Remain engine helpers | motion math (no transform) |
| `leftFoot` / `rightFoot` / `bothFeet` / `leftKnee` / `rightKnee` / `hands` | Remain engine helpers | `SupportContact` metadata (no transform) |

**Bucket counts:** Becomes intent = 16 · Splits = 2 (`buildHead`, `bakeIkLimb`) · Becomes obsolete = 5
(pose-side IK wrappers) · Remains (already intent / engine helper, unchanged in kind) = 8
(`buildGaze`, `declarePosture`, `overrideExtremityOrientation`, 4 motion helpers, 6 support helpers — the
last two groups own no transform and are engine/runtime math).

---

## 3. The semantic boundary, stated explicitly

The inventory resolves the boundary the document exists to define:

- **Intent (pose side, P1):** every *what* the body should do — spine curves, joint articulations
  (chest/hip/girdle/wrist/ankle), limb world targets, posture, contacts, head target, extremity overrides,
  and structural attachment offsets. These are **declarations**, not computations.
- **Geometry (engine side, P2):** every *how* — IK solve, `toLocalDirection` world→local conversion,
  matrix composition of multi-DOF rotations, FK flatten, root/posture seeding, chest-frame reconstruction,
  extremity derivation. These are written **only** by engine stages.
- **Observation (P3):** validation reads §1.2 + §1.1 ROM and writes nothing.

A helper that *writes a node transform* today is, by definition, crossing the boundary (it does engine
geometry on the pose side) and is therefore classified **Becomes intent** or **Splits** — never "remains an
engine helper" in its current form. The only helpers that "remain" unchanged in *kind* are those that
already declare intent (`buildGaze`, `declarePosture`, `overrideExtremityOrientation`) or that own no
transform at all (motion/support math). `bakeIkLimb` is the canonical **Split**: it is both the intent
declaration and the geometry solve in one call, and Branch B's entire purpose is to separate those two
halves. `buildHead` is the second Split: offset (intent) + direction (already `headTarget` intent).

This classification is the contract `RFC_DECLARATIVE_POSE_AUTHORING.md` must honour: every "Becomes intent"
helper maps to a carrier the engine consumes; every "Splits" helper separates into a declaration + an
engine stage; every "Becomes obsolete" wrapper is deleted once the engine owns the solve. No new API is
specified here — only the semantic fate of each existing helper.
