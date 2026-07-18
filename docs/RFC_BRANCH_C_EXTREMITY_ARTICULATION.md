# RFC — Branch C: Extremity Articulation

> **Status:** ACCEPTED + IMPLEMENTED (architectural decision: hypothesis carrier `MutableMap<Extremity, JointRotation>` adopted).
> **Scope:** determine whether a *Branch C* should exist at all, what its scope is, and — critically —
> whether an `extremityArticulation` carrier is the correct architectural solution for wrist/ankle/hand/
> foot orientation. This RFC is an **investigation**, not a justification. The name `extremityArticulation`
> is a **working hypothesis** used for legibility; §6 and §10 explicitly weigh alternatives and may reject it.
> **Non-goals:** no new Pipeline stage. The carrier rides the existing Finalizer stage (§7).

---

## Implementation record (Branch C complete)

The decision in §6.3 resolved to the **hypothesis**: a `MutableMap<Extremity, JointRotation>` carrier
keyed by `Extremity` (HAND_A / HAND_P / FOOT_F / FOOT_B), value = the authored wrist/ankle rotation
*relative to the parent segment* (forearm for the wrist, shank for the ankle). Implementation:

- **§1.1 carrier** `extremityArticulations: MutableMap<Extremity, JointRotation>` added to
  `SkeletonPose`; handled in `copyFrom` (value-copy per entry, matching the `jointIntents` reference
  convention) and `IntentBuilder.reset()`.
- **Sole-mutator writer** `IntentBuilder.extremity(extremity, rotation)`; the `extremityOverrides`
  opt-out set and `overrideExtremity` are retained as the mode flag (§6.1 key-space compatibility).
- **Finalizer consumer** `SkeletonPoseFinalizer.articulationFor` reads the carrier when populated and
  falls back to the legacy node `relativeRotation` read when empty (mixed-mode, byte-identical during
  migration). The four `adjustHand/FootOrientation` calls are re-pointed through it; the `MANUAL_OVERRIDE`
  dispatch is unchanged (skipping derivation = preserving the authored endpoint nodes).
- **Authoring vocabulary** `buildWristArticulation` / `buildAnkleArticulation` (BasePose +
  BaseValidationPose) compose the 2-DOF rotation via `SkeletonMath.buildWristRotation` /
  `buildAnkleRotation`, write the node `localRotation` for build-time FK (mixed-mode), and record the
  carrier — the single anatomical vocabulary (§1.1.3).
- **Migration** all 17 bare `HAND_*`/`ANKLE_*` `localRotation.set` sites (PikePushUp, ThoracicExtension,
  JumpSquat, BaseVerticalPull ×3 grips, DynamicWorldsGreatestStretch, HamstringStretch, DeadHang) now
  route through the helpers; DeadHang's overhand grip moved from a `jointIntents(HAND_*)` ROM entry to
  the articulation carrier (correct §1.3 vs §1.1 boundary).
- **Tests** `ExtremityArticulationTest` pins (a) carrier populated, (b) carrier vs node-path
  byte-identity (maxDev 0), (c) MANUAL_OVERRIDE preserves authored endpoints, (d) 2-DOF composer exact.

Exit criteria (§12) met: single owner (Pose writes, Finalizer reads); opt-out real; one vocabulary;
Validation/IK/Contact transparent (AUTOMATIC path unchanged); no semantic mismatch (articulation never
in `jointIntents`).



## 0. Trigger and context

The Branch B re-audit (RFC_BRANCH_B_REPLAN) and the accepted Intent Taxonomy (RFC_INTENT_TAXONOMY)
established, via the six-class data model, that:

- **Extremity articulation** (wrist flexion/supination, ankle plantarflexion/dorsiflexion, hand/foot
  placement orientation) is **§1.3 Interaction / Articulation Intent** — *not* ROM Intent, *not* Shape
  Constraint (Taxonomy §1.3, §3, §7).
- It is therefore **explicitly out of Branch B scope** (Taxonomy §7.3, RFC_BRANCH_B_REPLAN §7.3).
- The taxonomy defers it to a **separate branch** ("Branch C") *only if* a carrier is pursued (Taxonomy §7, §6).
- Today it is authored as **direct `localRotation.set` on `HAND_*`/`ANKLE_*` (and a few `PALM_`/`KNEE_`-adjacent)
  nodes**, read back by the Finalizer's W1 derivation (`adjustHandOrientation` / `adjustFootOrientation`),
  and composed into palm/knuckle/fingertip/heel/toe geometry via `HandDefinition.computeHandJoints` /
  `FootDefinition.computeHeelToe`.

This RFC answers: does a Branch C earn its existence, and if so, is a dedicated carrier the right shape —
or should articulation stay as a recognized direct node write, or be represented some other way?

---

## 1. Why Branch C exists (or does not)

### 1.1 The case FOR a Branch C

Three independent problems justify *some* work, and they cluster on a single semantic:

1. **Declarativity / single-writer hygiene.** The taxonomy's single-writer rule (§3) says every data type
   has exactly one owner. Today articulation is *authored on the node* and *read back from the node* by the
   Finalizer. That is a Pose-write / Finalizer-read round-trip through `SkeletonNode` that is **not** a
   Shape Constraint (Shape is Pose→node→FK, never read back as "intent"). Articulation is the odd one out:
   it is intent that only the Finalizer consumes, yet it lives in the node, so it looks like Shape but
   behaves like Intent. A dedicated carrier makes the ownership explicit and removes the node as a
   pass-through mailbox.
2. **The `MANUAL_OVERRIDE` path is dead.** `ExtremityOrientationMode.MANUAL_OVERRIDE` and
   `overrideExtremity`/`extremityOverrides` exist and are wired through `IntentBuilder`, but the Finalizer
   only consults `isExtremityAutomatic` to decide *whether to derive*. **No code path preserves authored
   endpoint geometry when an extremity is opted out** — the dispatch never falls into a "keep the
   authored heel/toe/palm verbatim" branch. So the *only* way an author can currently style an extremity is
   the implicit one (engine derives), and the explicit opt-out is silently a no-op for geometry. Branch C
   is the natural home for *making opt-out real* or *formally retiring it*.
3. **The 22 raw writes are a maintainability hazard.** Wrist/ankle orientation is currently authored with
   bare `localRotation.set(axisZ, …)` at ~22 sites (Taxonomy §4). They are *recognized valid* today, but
   they (a) bypass the 2-DOF anatomical composers `SkeletonMath.buildWristRotation` / `buildAnkleRotation`
   at most sites (so combined DOFs silently drop one axis), and (b) duplicate axis/angle literals that the
   composer already encodes. A carrier + a `build*` composer gives one anatomical vocabulary.

### 1.2 The case AGAINST a Branch C

- The current system **renders correctly** for every production/validation pose (full suite 282/0, and
  the B4a ROM migration is byte-identical). The derivation path is mature (W1, Issue C, R1, support-plane
  projection all already landed). "It works" is a real argument for *not* disturbing it.
- The taxonomy explicitly says the carrier, *if* built, is "a refinement for declarativity, not a
  correctness fix" (Taxonomy §1.3). So Branch C is **not blocking any correctness defect**.
- A new carrier is a new §1.1 field, a new `IntentBuilder` surface, a new Finalizer consumer, and new
  migration churn across 22 sites — nonzero regression surface for a purely stylistic gain.

### 1.3 Finding

**Branch C earns its existence, but narrowly.** It should NOT be "migrate 22 sites to a carrier because
Branch B said so." It should be scoped to the *single concrete defect* — the dead `MANUAL_OVERRIDE` path
(§1.1.2) — plus the declarativity/2-DOF-composition cleanup (§1.1.1, §1.1.3) as the *mechanism* that fixes
it. If the investigation in §6 and §10 concludes the carrier is the wrong shape, Branch C shrinks to
"make opt-out real via the existing node path, retire the carrier idea" — still a Branch, still distinct
from B.

> **Verdict:** Branch C EXISTS, scoped to *endpoint-orientation ownership* (articulation + the opt-out
> contract), not to "carry everything that Branch B didn't want." It is intentionally separated from
> Branch B because it is a different data class (§1.3 vs ROM), has a different consumer (W1 derivation,
> not B2 re-FK), and is not blocking (§1.2).

---

## 2. Which semantics belong to Branch C (and why)

Branch C owns **§1.3 Interaction / Articulation Intent** and nothing else:

| Semantic | In Branch C? | Why |
|---|---|---|
| Wrist flexion / deviation (grip) | **Yes** | §1.3 endpoint styling consumed by W1. |
| Ankle dorsi/plantar-flexion / inversion (foot plant) | **Yes** | §1.3. |
| Hand/foot *placement* orientation (how the derived palm/heel points) | **Yes** | §1.3 — the authored `JointRotation` the Finalizer composes into geometry. |
| The `MANUAL_OVERRIDE` opt-out contract (preserve authored endpoint geometry) | **Yes** | It is the flip side of the same ownership; today dead (§1.1.2). |
| `extremityOverrides` (`ExtremityOrientationMode`) plumbing | **Yes** (re-shape) | It is the existing carrier-shaped shell for this exact data; Branch C either fills it or retires it. |

Explicitly **NOT** in Branch C:

| Semantic | Where it lives | Why excluded |
|---|---|---|
| Spine/neck/hip/girdle ROM | Branch B (DONE) | §1.1 ROM Intent — different carrier, different consumer. |
| Knee/segment shape rotations | Shape Constraint, direct node | §1.2 — never a carrier. |
| `localPosition` segment offsets (torso/pelvis/shoulders) | Shape Constraint | §1.2. |
| Head/gaze | Branch B Phase 7 (DONE) | §1.5 Finalizer, separate intent. |
| Contact specs / `limbTargets` | Solver/IK (B1, done) | §1.4/§1.7. |
| HIP_ROM / bilateral stamps | Validator-only (B5, done) | §1.7. |

---

## 3. Why Branch C is intentionally separated from Branch B

1. **Different data class.** ROM (§1.1) is measured by the validator and replayed by B2 re-FK.
   Articulation (§1.3) is *endpoint styling* composed into palm/heel geometry by W1 — never a ROM DOF,
   never B2-replayed. Conflating them pollutes `jointIntents` (Taxonomy §6 "Why `jointIntents` cannot be
   used").
2. **Different consumer.** ROM → `applyIntentCarriers` re-FK. Articulation → `adjustHandOrientation` /
   `adjustFootOrientation` derive endpoint geometry. Same node, two incompatible consumers.
3. **Different risk profile.** B is byte-identical, correctness-frozen, and complete. C is not blocking
   and is allowed to *change* the authoring surface. Mixing them would re-open a closed branch.
4. **Different completion definition.** B's exit is "no bare ROM write" (Taxonomy §8). C's exit is
   "endpoint orientation has a single, explicit owner and the opt-out contract is real" (§12). Coupling
   them would make B's exit depend on C's non-blocking work.

The dependency edge `articulation → B4` that the old plan assumed is **deleted**; the edge becomes
`articulation → C1` (this branch). B and C share no stage, no carrier, no test class.

---

## 4. Exact ownership model

Per the taxonomy single-writer rule, restated for §1.3 and tightened where the current code violates it.

### 4.1 Pose author
- **Sole writer** of the articulation intent.
- Today: writes `HAND_*`/`ANKLE_*` `localRotation` directly (and optionally calls `overrideExtremity`).
- Target: writes the articulation **carrier** (or, if §6 rejects the carrier, a documented
  `buildWristArticulation` / `buildAnkleArticulation` helper that records the same data and still writes
  the node for build-time FK — the Branch B mixed-mode pattern).
- **Must never** write `PALM_*`/`KNEE_`-derived endpoint positions as the source of truth (those are
  Finalizer Output, §1.5).

### 4.2 Finalizer (sole reader / consumer)
- **Sole consumer** of articulation. Reads the carrier (or, pre-migration, the node) and feeds
  `computeHandJoints` / `computeHeelToe`.
- **Owns** the derived `PALM_*`/`KNEE_*`/`HEEL_*`/`TOE_*` geometry (§1.5 Finalizer Output) — never
  re-read by anyone as intent.
- **Owns** the opt-out contract: if an extremity is opted out, the Finalizer must **preserve the authored
  endpoint geometry** (today missing — §1.1.2). This is the one behavioral change C may introduce.

### 4.3 SkeletonMath
- **Stateless composer only.** `buildWristRotation(flexion, deviation)`, `buildAnkleRotation(dorsi, inv)`
  already exist and are correct (compose 2-DOF exactly). C promotes them from "available" to "the only
  authored vocabulary" — every articulation site funnels through them. No new math required.
- `computeHandJoints` / `computeHeelToe` stay as the geometry projection (Finalizer Output); C does not
  move them.

### 4.4 Validator
- **No new dependency.** The validator already reads **endpoint positions** (heel/toe/palm), not the
  authored rotation, for `HAND_SLIDING` / `HAND_SHOULDER_ALIGNMENT` / bone-length / `ANGULAR_JOINT_LIMIT`
  (gated off by default). Those read the *derived* geometry, which is unchanged in value. So C is
  **validation-transparent** if the carrier is byte-identical to today's node (see §8).

---

## 5. Exact data model (working hypothesis)

```kotlin
// §1.1 INTENT carrier (hypothesis). Keyed by Extremity, value = authored
// wrist/ankle JointRotation in the limb's parent-segment frame.
val extremityArticulations: MutableMap<Extremity, JointRotation> = mutableMapOf()
```

- **Key:** `Extremity` (HAND_A, HAND_P, FOOT_F, FOOT_B) — the four endpoints, matching `extremityOverrides`.
- **Value:** the authored `JointRotation` (exactly what `getJointRotation(HAND_*)` / `getJointRotation(ANKLE_*)`
  returns today, and exactly what `relativeRotation(...)` already computes into the W1 call).
- **Frame:** *relative to the parent segment* (forearm for wrist, shank for ankle), **not world** — this
  is already how W1 consumes it (`relativeRotation` removes the ancestor chain so the grip doesn't
  double-count trunk tilt). The carrier must carry the same relative rotation; it must **not** carry a
  world rotation.

This is a **drop-in replacement** for the four `getJointRotation(HAND_*/ANKLE_*)` reads inside
`adjustHandOrientation` / `adjustFootOrientation`: same value, different source.

---

## 6. Is `extremityArticulation` the correct carrier? (explicit hypothesis-testing)

The taxonomy *assumes* this carrier (§6). This RFC must test that assumption, not inherit it.

### 6.1 Arguments FOR the map-by-Extremity carrier
- Matches the existing `extremityOverrides: MutableSet<Extremity>` shell 1:1 — the opt-out contract and
  the intent carrier share a key space, so they compose cleanly.
- A `JointRotation` is exactly what W1 consumes; no translation layer needed.
- `Extremity` (4 values) is a coarser, more ergonomic key than `Joint` (the 8 hand/ankle nodes), and the
  Finalizer already groups by `Extremity` in its dispatch (lines 556–578).

### 6.2 Arguments AGAINST (alternatives the RFC must weigh)
- **(A) Keep direct node writes + fix only the opt-out.** Articulation "is the legitimate, intended
  storage" *today* (Taxonomy §1.3 last line). If the only real defect is the dead `MANUAL_OVERRIDE` path,
  the minimal fix is: make the Finalizer *preserve* authored endpoint geometry when opted out, and route
  the 22 sites through `buildWristRotation`/`buildAnkleRotation` helpers. **No new carrier, no new §1.1
  field.** This satisfies declarativity *partially* (one anatomical vocabulary) without single-writer
  purity.
- **(B) Structured value instead of bare `JointRotation`.** A bare `JointRotation` hides *which* DOFs were
  intended (flexion? deviation? both?). A structured `ExtremityArticulation(val flexion, val deviation)` /
  `(val dorsiflexion, val inversion)` (or a `WristArticulation` / `AnkleArticulation` pair) carries the
  anatomical vocabulary explicitly, composes via `SkeletonMath`, and is self-documenting. Cost: two types
  instead of one map value; the Finalizer maps each to the composer.
- **(C) Per-joint carrier keyed by `Joint` (HAND_A/WRIST_A/ANKLE_F…).** Matches `jointIntents` shape but
  reintroduces the "which node is the articulation root" ambiguity and tempts B2 re-FK reuse (Taxonomy §6
  "Why `jointIntents` cannot be used"). **Rejected** for the reasons the taxonomy already gives.
- **(D) Fold into `extremityOverrides` as a richer type** (`MutableMap<Extremity, ExtremityIntent>` where
  `ExtremityIntent` carries either `AUTOMATIC` or an explicit `JointRotation`). Collapses the two parallel
  `Extremity` maps into one. Clean, but couples "mode" and "value" in a way the taxonomy keeps separate
  (§1.3 write-access vs §1.2-style opt-out).

### 6.3 Tentative recommendation (not a decision)
The map-by-`Extremity` carrier (**hypothesis**) is the *least-surprising* fit and the taxonomy's own
proposal. But **(B)** — a structured anatomical value — is arguably *more correct* because it makes the
2-DOF vocabulary explicit and prevents the "bare axis-angle silently drops a DOF" bug at the type level.
The decision in §10 should pick between (hypothesis) and (B); (A) is the fallback if the investigation
finds the carrier buys too little; (C)/(D) are rejected.

---

## 7. Lifecycle of articulation data through the pipeline

Today (and the target must remain byte-identical):

```
Pose.build()
  ├─ author writes HAND_*/ANKLE_* localRotation   (or, target: records carrier)
  ├─ FK propagates wrist/ankle world rotation
  ▼
SkeletonPipeline.runStages
  ├─ IK/Solver ............................ (ignores articulation; limb end = wrist/ankle joint)
  ├─ Finalizer.finalize
  │    ├─ for each Extremity:
  │    │    if AUTOMATIC:  adjustHand/FootOrientation( ..., relativeRotation(HAND/ANKLE, parent) )
  │    │                     -> computeHandJoints / computeHeelToe -> PALM/KNUCKLES/FINGERTIPS/HEEL/TOE
  │    │    if OVERRIDE:    (TARGET: preserve authored PALM/HEEL/TOE verbatim)   <- today MISSING
  │    └─ applyValidationStamps(...)
  ▼
Validator ................................ reads derived endpoint positions (unchanged values)
```

The carrier **rides the existing Finalizer stage** (Taxonomy §6); it does **not** add a Pipeline stage.
Its lifecycle is identical to today's node read, just sourced from `extremityArticulations[extremity]`
instead of `getJointRotation(HAND_*/ANKLE_*)`.

---

## 8. Which existing helpers migrate

| Helper / site | Migrates to Branch C? | Note |
|---|---|---|
| `SkeletonMath.buildWristRotation` / `buildAnkleRotation` | **Stay** (promoted to sole vocabulary) | Already correct; C makes every site use them. |
| `BasePose.buildWristArticulation` / `buildAnkleArticulation` | **Reintroduced or retained** as the authoring surface | Currently deleted (were misrouted into ROM in old B4). C gives them a correct home: they record the carrier + (mixed-mode) write the node. |
| `SkeletonPoseFinalizer.adjustHandOrientation` / `adjustFootOrientation` | **Stay**, small change | Replace `getJointRotation(HAND_*/ANKLE_*)` with carrier read; add the OVERRIDE-preserve branch. |
| `HandDefinition.computeHandJoints` / `FootDefinition.computeHeelToe` | **Stay** | Finalizer Output geometry; untouched. |
| `PoseDefinition.extremityOverrides` / `ExtremityOrientationMode` / `overrideExtremity` | **Stay / re-shape** | The existing shell for this exact data; C fills or retires it. |
| The ~22 raw `HAND_*`/`ANKLE_*` `localRotation.set` sites | **Migrate** to `buildWristArticulation` / `buildAnkleArticulation` | Removes duplicated axis/angle literals; enforces 2-DOF composition. |
| `SkeletonMath.composeRotations` (used by the composers) | **Stay** | Utility. |

---

## 9. Which helpers remain unchanged

- All Branch B ROM helpers (`buildSpineCurve`, `buildChestTwist`, `buildHipFlexion`, `buildHipRotation`,
  `declareJointIntent`, `declarePelvisTilt`) — **untouched**, different carrier/consumer.
- All Shape Constraint helpers (`buildTorso`, `buildPelvis`, `buildShoulders`, `buildRigidSegment`,
  knee/segment writes) — **untouched** (§1.2).
- `SkeletonPoseFinalizer.applyIntentCarriers` (B2 re-FK) — **untouched**; must NOT consume articulation.
- `SkeletonPoseFinalizer.resolveHeadTarget` (Phase 7) — **untouched**.
- `ConstraintSolver` / `IkStage` — **untouched** (articulation is below the wrist/ankle joint, which is
  the IK end-effector; see §10).

---

## 10. Impact analysis

### 10.1 Validation — **No impact (if byte-identical)**
The validator reads *derived endpoint positions*, not the authored rotation. W1 produces the same
`PALM_*`/`HEEL_*`/`TOE_*` values from the carrier as it does from the node (same `JointRotation` in, same
geometry out). No validator rule changes. If C instead *changes* the derivation (e.g. finally honoring
opt-out), the validator transparently sees the new derived geometry — that is a deliberate, contained
behavior change, not a regression.

### 10.2 IK — **No impact**
`bakeIkLimb` ends at the wrist/ankle joint (`HAND_*`/`ANKLE_*`). Articulation is *below* that joint
(palm/fingers, heel/toe), so IK never sees it. The `limbTargets` carrier and `IkStage` are unaffected.
The wrist/ankle `localRotation` is consumed only by W1, after IK has already placed the end joint.

### 10.3 Contact solving — **No impact (with one caveat)**
The ConstraintSolver uses `contacts` keyed by end joint (hand/foot *joint*, not the derived endpoint).
Articulation does not move the contact joint, so root/seeding is unaffected. **Caveat:** a foot opted into
`MANUAL_OVERRIDE` with an authored *toe* position that penetrates its support plane would now be preserved
(where today it is derived/flattened). That is the intended opt-out semantics; the existing
`supportPlaneNormalForFoot` projection lives in the AUTOMATIC branch and must stay out of the OVERRIDE
branch. This is a *feature* of C, not a solver change.

---

## 11. Migration strategy

1. **Land the opt-out fix first (highest value, lowest risk).** Make `adjustHand/FootOrientation` preserve
   authored endpoint geometry when the extremity is in `MANUAL_OVERRIDE`. This alone closes the dead-path
   defect (§1.1.2) and is valuable even if the carrier is later rejected.
2. **Introduce the carrier (hypothesis) or the structured value (B) on `SkeletonPose` §1.1.** Add an
   `IntentBuilder.extremity(...)` writer + `copyFrom`/`reset` handling. Keep `extremityOverrides` as the
   mode flag.
3. **Re-point the Finalizer** to read the carrier instead of `getJointRotation(HAND_*/ANKLE_*)`.
   Byte-identical to today for the AUTOMATIC path.
4. **Migrate the 22 sites** through `buildWristArticulation` / `buildAnkleArticulation` (reintroduced as
   the C authoring surface), removing duplicated axis/angle literals and enforcing 2-DOF composition.
5. **Add tests:** `ExtremityArticulationTest` pins (a) carrier -> derived geometry byte-identical to the
   node path; (b) the OVERRIDE path preserves authored endpoint geometry; (c) 2-DOF combines exactly.
6. **Mixed mode during migration:** like Branch B, keep the node write alongside the carrier until the
   Finalizer fully consumes the carrier, then drop the node write. This keeps build-time FK (e.g. any
   logic reading wrist/ankle world rotation) working mid-migration.

### Decision gate (resolve before implementation)
Pick the data shape from §6.3:
- **Hypothesis accepted** -> `MutableMap<Extremity, JointRotation>`.
- **Structured value (B) accepted** -> `MutableMap<Extremity, WristArticulation|AnkleArticulation>` with
  explicit DOFs; composers unchanged.
- **Carrier rejected (A)** -> no §1.1 field; fix opt-out via node path + route 22 sites through composers.
Branch C exists under all three; only its surface changes.

---

## 12. Exit criteria

Branch C is **complete if and only if**:

1. **Single owner.** Articulation intent has exactly one writer (Pose) and one reader (Finalizer). No
   component other than the Finalizer derives endpoint geometry from it.
2. **Opt-out is real.** An extremity opted into `MANUAL_OVERRIDE` has its authored endpoint geometry
   preserved verbatim by the Finalizer (the dead path is closed).
3. **One anatomical vocabulary.** Every wrist/ankle articulation funnels through `buildWristRotation` /
   `buildAnkleRotation` (or the structured equivalent); no bare 2-DOF-dropping `localRotation.set` remains
   at the 22 sites.
4. **Validation/IK/Contact transparent.** No validator rule, IK stage, or contact solve changes behavior
   except the deliberate, contained opt-out preservation. Suite remains green and byte-identical on the
   AUTOMATIC path.
5. **No semantic mismatch.** Articulation is never in `jointIntents`/ROM; ROM is never in the extremity
   carrier. The taxonomy's §1.3 vs §1.1 boundary is enforced by structure, not convention.

---

## 13. Open questions (for approval, not for this RFC to answer)

- Q1: Is a bare `JointRotation` (hypothesis) sufficient, or does the 2-DOF-dropping history justify the
  structured value (B)? *Recommend deciding via §6.3 before step 2 of §11.*
- Q2: Should `extremityOverrides` (mode) and the articulation value merge into one `ExtremityIntent` map
  (alternative D), or stay two parallel `Extremity`-keyed structures? *Coupling mode+value is convenient
  but the taxonomy keeps them conceptually separate; lean toward keeping them separate unless D proves
  cleaner in implementation.*
- Q3: Are there poses that *should* use `MANUAL_OVERRIDE` today but can't (because it's dead)? Survey
  before step 1 — if none, the opt-out fix is still correct hygiene but has zero immediate consumers.
