# RFC — Intent Taxonomy

**Status:** PROPOSED (architectural, normative)
**Scope:** defines every class of *Author Intent* and related produced data in the animation engine, and the ownership / carrier rules that govern them.
**Blocks:** further Branch B implementation (B4 re-audit and continuation) until approved.
**Non-goals of this RFC:** no code change, no new carrier implementation, no Pipeline rewrite. This is a purely architectural specification.

---

## 1. Full Intent Taxonomy

The engine distinguishes six data classes. Three are **Author Intent** (authored by the pose); three are **engine-produced** (written by Solver/Finalizer and read by Validation/rendering).

### 1.1 ROM Intent (joint motion)

- **Meaning:** an authored anatomical rotation of a ROM-bearing joint — spine (lumbar/chest), neck, hip, shoulder, girdle — expressed as a relative `JointRotation` about the parent segment. This is the joint motion the validator's ROM rules measure.
- **Owner:** Pose.
- **Write access:** Pose only (via `IntentBuilder.joint(...)` / `IntentBuilder.spine(...)`).
- **Read access:** Finalizer (B2 consumer, re-applies to node), Solver (posture/shape reconciliation), Validator (ROM read).
- **Pipeline:** yes — Pose → carriers on `SkeletonPose` → consumed by Finalizer/Solver in `SkeletonPipeline.runStages`.
- **Storage in a `SkeletonNode` allowed?** Yes, but **only** as the Finalizer's re-derived node write (idempotent mixed mode). The *source of truth* is the carrier, never the node.

### 1.2 Shape Constraint (pose geometry)

- **Meaning:** an authored local transform that fixes the *shape* of the pose — segment offsets (`localPosition`), and frame/limb rotations with no independent articulation or ROM semantics (e.g. knee straightness, limb-planar alignment). It is geometry, not intent-in-the-ROM-sense.
- **Owner:** Pose.
- **Write access:** Pose only.
- **Read access:** FK (propagates to descendants), Finalizer (via FK).
- **Pipeline:** no dedicated carrier stage; written directly on the node during `build()` and consumed by FK.
- **Storage in a `SkeletonNode` allowed?** **Yes — this is the legitimate, intended storage.** Shape Constraints are NOT part of the Intent Layer (see §7).

### 1.3 Interaction / Articulation Intent (grip, foot, wrist, ankle, hand orientation)

- **Meaning:** an authored endpoint orientation — wrist flexion/supination (grip), ankle plantarflexion/dorsiflexion (foot plant), hand/foot placement orientation. Consumed by the W1 engine to derive palm/knuckle/fingertip/heel/toe geometry.
- **Owner:** Pose.
- **Write access:** Pose only (today: direct `localRotation.set` on HAND_*/ANKLE_*; future: `extremityArticulation` carrier).
- **Read access:** Finalizer (`adjustHandOrientation` / `adjustFootOrientation`, which read `HAND_*`/`ANKLE_*` rotations as articulation).
- **Pipeline:** today — Pose writes node, Finalizer reads node. Future — Pose writes `extremityArticulation` carrier, Finalizer consumes it (see §6).
- **Storage in a `SkeletonNode` allowed?** Yes, today (the Finalizer reads it). The future carrier is a refinement for declarativity, not a correctness fix.

### 1.4 Solver Output

- **Meaning:** root/pelvis transform, contact-limb local offsets, and stamped deltas produced by the ConstraintSolver to honour fixed contacts and posture intent.
- **Owner:** ConstraintSolver.
- **Write access:** Solver only.
- **Read access:** Finalizer (bakes offsets), Validator (stamps), rendering.
- **Pipeline:** yes — Solver stage in `SkeletonPipeline.runStages`.
- **Storage in a `SkeletonNode` allowed?** Yes — the Solver owns the node transform during its stage; no other component writes it.

### 1.5 Finalizer Output

- **Meaning:** per-node local transforms derived by the Finalizer (chest-frame reconstruction, head/gaze resolution, extremity orientation derivation, heel/toe/palm geometry).
- **Owner:** SkeletonPoseFinalizer.
- **Write access:** Finalizer only.
- **Read access:** Validator (stamps), rendering.
- **Pipeline:** yes — Finalizer stage in `SkeletonPipeline.runStages`.
- **Storage in a `SkeletonNode` allowed?** Yes — the Finalizer is the exclusive writer of local transforms (F1 guarantee).

### 1.6 Metadata

- **Meaning:** camera, environment, motion-curve, support, duration — non-geometric authoring that configures rendering/playback, not skeleton shape.
- **Owner:** Pose.
- **Write access:** Pose (via `IntentBuilder` / `PoseMetadata`).
- **Read access:** renderer, ExerciseAnimation, ValidationRule environment.
- **Pipeline:** not part of the geometry Pipeline; consumed by rendering/validation directly.
- **Storage in a `SkeletonNode` allowed?** No — lives on `PoseMetadata` / `SkeletonPose` §1.1 fields, never on a node.

### 1.7 Validation-only data

- **Meaning:** §1.2 STATE stamps (`maxIkClampAmount`, `straightIntentDropped`, `rootTranslationDelta`, `rootRotationDelta`, `boneLengthsVerified`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`) produced by the engine and read by the validator. Also contact specs / `limbTargets` consumed by the Solver.
- **Owner:** Engine (IK / Solver / Finalizer produce; Pose may declare `contacts`/`limbTargets`).
- **Write access:** engine stages (and Pose for `contacts`/`limbTargets` declarations).
- **Read access:** Validator, Solver.
- **Pipeline:** yes — produced/consumed within `SkeletonPipeline.runStages`.
- **Storage in a `SkeletonNode` allowed?** No — lives on `SkeletonPose` carriers/stamps, not on a node.

---

## 2. Carrier Matrix

| Semantic | Carrier | Owner | Stage | Status |
|---|---|---|---|---|
| ROM (trunk/neck/hip/girdle) | `jointIntents` / `spineIntent` | Pose → carrier; Finalizer → node | Pose → Finalizer (B2) | LIVE |
| ROM (per-hip femur) | `hipRomStamps` (§1.2 stamp) | Finalizer (read by Validator) | Finalizer → Validator | LIVE (B5) |
| Shape | direct `SkeletonNode` (no carrier) | Pose → node | Pose → FK | INTENDED (no carrier) |
| Articulation (hand/foot/wrist/ankle) | `extremityArticulation` | Pose → carrier; Finalizer → node | Pose → Finalizer | FUTURE (today: direct node) |
| Solver Output | `contacts` + node transform | Solver | Solver | LIVE |
| Finalizer Output | node transform + heel/toe/palm | Finalizer | Finalizer | LIVE |
| Metadata | `PoseMetadata` / §1.1 fields | Pose | n/a (render/validate) | LIVE |
| Validation-only | §1.2 stamps / `limbTargets` | Engine / Pose | Pipeline | LIVE (B5) |

---

## 3. Single Writer Rules

Each data type has exactly one owner/writer. No dual writes.

- **ROM** — Pose → Carrier; Finalizer → Node (idempotent re-application only; never originates).
- **Shape** — Pose → Node (only writer; FK only reads).
- **Articulation** — Pose → Carrier (future); Finalizer → Node (reads carrier, derives geometry). Today: Pose → Node directly (reads by Finalizer).
- **Solver Output** — Solver only.
- **Finalizer Output** — Finalizer only.
- **Metadata** — Pose only.
- **Validation-only** — Engine stage that produces it (single writer per stamp).

No component other than the listed owner may write a given data type. In mixed mode the Finalizer's node re-write of a ROM carrier is a *derivation* of the carrier, not an independent author — it must reproduce the carrier exactly (byte-identical).

---

## 4. Migration Matrix (remaining B4 records)

Read-only classification of every remaining bare `localRotation.set` (from the B4 semantic audit). No code; table only.

| file | semantic | carrier | migration | phase |
|---|---|---|---|---|
| ThoracicExtensionPose.kt:59 (lumbar) | ROM Intent | `spineIntent` / `jointIntents` | migrate to `buildSpineCurve` | B4 |
| ThoracicExtensionPose.kt:65 (chest) | ROM Intent | `spineIntent` / `jointIntents` | migrate to `buildSpineCurve` | B4 |
| ThoracicExtensionPose.kt:86 (ankleF) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| ThoracicExtensionPose.kt:87 (ankleB) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| GluteBridgePose.kt:90 (neck) | ROM Intent | `jointIntents` | migrate to `declareJointIntent(NECK)` | B4 |
| PelvicTiltPose.kt:89 (neck) | ROM Intent | `jointIntents` | migrate to `declareJointIntent(NECK)` | B4 |
| DynamicWorldsGreatestStretchPose.kt:73 (ankleB) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:254 (ankleF) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:255 (ankleB) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:265 (handA) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:266 (handP) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:269 (handA) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:270 (handP) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:273 (handA) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseVerticalPullPose.kt:274 (handP) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| HamstringStretchPose.kt:107 (ankleF) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| HamstringStretchPose.kt:108 (ankleB) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| PikePushUpPose.kt:73 (ankleF) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| PikePushUpPose.kt:89 (kneeB) | Shape Constraint | direct `SkeletonNode` | remain direct (no carrier) | B4 (done as-is) |
| PikePushUpPose.kt:128 (handA) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BasePushUpPose.kt:109 (kneeF) | Shape Constraint | direct `SkeletonNode` | remain direct (no carrier) | B4 (done as-is) |
| BasePushUpPose.kt:118 (hipF) | ROM Intent | `jointIntents` (`buildHipFlexion`) | migrate to `buildHipFlexion` | B4 |
| BasePushUpPose.kt:129 (kneeB) | Shape Constraint | direct `SkeletonNode` | remain direct (no carrier) | B4 (done as-is) |
| JumpSquatPose.kt:95 (ankleF) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| JumpSquatPose.kt:96 (ankleB) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| JumpSquatPose.kt:112 (handA) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| JumpSquatPose.kt:113 (handP) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| DeadHangPose.kt:118 (handA/P) | Interaction/Articulation | `extremityArticulation` (future) | keep direct until carrier exists | B4 (recognized valid) |
| BaseValidationPose.kt:112 (lower) | ROM Intent | `spineIntent` / `jointIntents` | already carrier (`buildSpineCurve`) | DONE |
| BaseValidationPose.kt:113 (chest) | ROM Intent | `spineIntent` / `jointIntents` | already carrier (`buildSpineCurve`) | DONE |
| BaseValidationPose.kt:151 (hip) | ROM Intent | `jointIntents` | already carrier (`buildHipRotation`) | DONE |

---

## 5. Non-goals

- **Shape Constraints are NOT part of the Intent Layer.** They are authored geometry stored directly on `SkeletonNode`. They must **not** be artificially converted to carriers. Forcing a `pelvis.localPosition` or a straight-knee `localRotation` into a carrier would invent semantics that do not exist and would risk byte-identity regressions for no declarativity gain.
- This RFC does **not** propose implementing `extremityArticulation`; it only specifies the taxonomy and the rule that such a carrier, if built, is a distinct category.
- No Pipeline rewrite, no new `SkeletonPose` fields, no changes to existing carriers.

---

## 6. Future Carrier Proposal — `extremityArticulation`

**If** a new carrier is needed for hand/foot/wrist/ankle orientation:

- **Structure:** `val extremityArticulations: MutableMap<Extremity, JointRotation>` on `SkeletonPose` §1.1, keyed by `Extremity` (HAND_A, HAND_P, FOOT_F, FOOT_B), value = the authored wrist/ankle `JointRotation`.
- **Ownership:** Pose is the sole writer (via `IntentBuilder.extremity(...)`); Finalizer is the sole reader/consumer (replaces the current `getJointRotation(HAND_*)/getJointRotation(ANKLE_*)` reads inside `adjustHandOrientation`/`adjustFootOrientation`).
- **Pipeline:** Pose writes carrier during `build()`; Finalizer consumes it in its extremity-derivation stage (exactly where it reads the node rotation today). No new Pipeline stage; it rides the existing Finalizer stage.
- **Why a separate category (not `jointIntents`):** `jointIntents` is the ROM carrier — it feeds the validator's ROM measurement and the B2 Finalizer re-application for spine/neck/hip/girdle. Wrist/ankle orientation is **not** a ROM DOF (it is not measured by `HIP_ROM_LIMIT` / `ANGULAR_JOINT_LIMIT` on the hand, and the knee/elbow interior-angle rule already covers limb bend). Conflating grip/foot-plant with ROM would (a) pollute the ROM carrier with non-ROM data, (b) mislead the validator, and (c) require the Finalizer to treat hand/ankle as ROM-replay joints, which they are not. `extremityArticulation` is an *endpoint styling* carrier, semantically distinct from joint ROM.
- **Why `jointIntents` cannot be used:** it is keyed by `Joint` and consumed by the generic B2 `applyIntentCarriers` re-FK path; endpoint orientation is instead consumed by the specialized W1 `adjustHand/ FootOrientation` derivation, which composes the rotation into palm/heel geometry. Reusing `jointIntents` would either double-apply (B2 re-FK *and* W1 derive) or require Special-casing the hand/ankle joints out of B2 — both are worse than a dedicated, single-owner carrier.

No implementation in this RFC.

---

## 7. Impact on Branch B

After taxonomy approval, **recalculate all of B4**:

- **B4 completion (recalculated):** B4 covers migration of **ROM Intents** to carriers only. Shape Constraints and Interaction/Articulation Intents are explicitly **out of B4 scope** per this taxonomy.
- **Moved to B5:** nothing — B5 (validator stamp-only) is already DONE.
- **Moved to a separate branch:** the `extremityArticulation` carrier (if pursued) becomes its **own Branch** (e.g. Branch C / "extremity articulation carrier"), distinct from B4, because it is a new carrier family, not a B4 ROM migration.
- **Recalculated B4 work items:**
  - Migrate the 5 ROM-Intent writes (lumbar, chest, neck×2, hipF) to `jointIntents`/`spineIntent`/`buildHipFlexion`. **This is the entire remaining B4.**
  - The 3 knee Shape-Constraint writes: **no B4 work** — they stay direct node writes (recognized valid, not a semantic mismatch).
  - The 22 hand/ankle Interaction/Articulation writes: **no B4 work** — stay direct until `extremityArticulation` exists (separate branch).

---

## 8. Exit Criteria — new definition of B4 completion

After the new classification, **B4 is considered complete if and only if**:

1. **All ROM Intents use carriers** — every `localRotation.set` on a ROM-bearing joint (spine/neck/hip/girdle) is expressed via `jointIntents` / `spineIntent` (or a `build*` helper that records them), with no bare ROM rotation write remaining.
2. **Shape Constraints remain direct node writes** — knee/segment shape rotations stay on `SkeletonNode` by design; they are not carriers and are not required to migrate.
3. **Interaction Intents** (hand/foot/wrist/ankle) **either use `extremityArticulation` or are officially recognized as valid direct authored articulations** until that carrier is introduced — they are not forced into `jointIntents`.
4. **There are no semantic mismatches** — no Shape Constraint is mislabeled as ROM, no Interaction Intent is mislabeled as ROM, and no ROM Intent remains as a bare node write.

Under this definition, B4's remaining implementation is the migration of the **5 ROM-Intent writes** only; the knee shape writes and the hand/ankle articulation writes are already compliant (recognized valid direct writes), and the `extremityArticulation` carrier is explicitly deferred to a separate branch.
