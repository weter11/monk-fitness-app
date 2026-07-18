# RFC — Branch C Feasibility (Extremity Articulation)

> **Status:** PROPOSED (architectural audit, normative).
> **Purpose:** determine whether Branch C *deserves to exist as an independent architectural branch*,
> and whether a dedicated `extremityArticulation` carrier is architecturally justified. This is a
> **feasibility audit**, not a design proposal. No solutions are proposed before the necessity is proven.
> Every conclusion below cites production code.
> **Non-goals:** no implementation, no new carrier, no Pipeline change.

---

## 0. Method

The audit traces, with `grep`/`read` evidence from `app/src/main`, the actual lifecycle of every
authored wrist/ankle/hand value: where it is written, how it reaches the consumer, and whether a
dedicated carrier would change that path. The companion `RFC_BRANCH_C_EXTREMITY_ARTICULATION.md` assumes
a carrier; this document tests whether one is *needed*.

---

## 1. What problem would Branch C actually solve?

The only concrete problems identifiable in the code are:

1. **An unreachable opt-out contract (the only behavioral defect).** `ExtremityOrientationMode.MANUAL_OVERRIDE`
   and `overrideExtremity`/`extremityOverrides` exist and are wired through `IntentBuilder` and
   `PoseDefinition` (`PoseDefinition.kt:238`, `:249`, `:360`). But:
   - **Zero** production or validation poses call `overrideExtremity*` (grep over `app/src/main` returns
     only the *definitions* in `BasePose.kt:163`, `BaseValidationPose.kt:168`, `PoseDefinition.kt:249/360`
     — never an actual call site). The `MANUAL_OVERRIDE` branch in the Finalizer is therefore **dead code**.
   - The Finalizer's dispatch (`SkeletonPoseFinalizer.kt:556`) decides *whether to derive* via
     `isExtremityAutomatic`, but **no branch ever preserves authored endpoint geometry** when an extremity
     is opted out. So even if a pose opted out, the authored heel/toe/palm would still be overwritten by
     derivation. The "preserve verbatim" semantic documented at `SkeletonPoseFinalizer.kt:547-549` is
     **not implemented**.
   - *This is a real but tiny defect: a missing `else` branch. It does not require a new carrier.*

2. **A 2-DOF-composition hazard that is currently theoretical.** `SkeletonMath.buildWristRotation`
   (`SkeletonMath.kt:930`) and `buildAnkleRotation` (`:942`) compose flexion+deviation / dorsi+inversion
   exactly. But every one of the **22** authored articulation writes uses a **single axis**
   (all `axisZ`, except `BaseVerticalPullPose.kt:273/274` which use a computed single `neutralAxisA/P`):
   - `HamstringStretchPose.kt:107/108`, `ThoracicExtensionPose.kt:84/85`, `JumpSquatPose.kt:95/96/112/113`,
     `BaseVerticalPullPose.kt:254/255/265/266/269/270/273/274`, `DynamicWorldsGreatestStretchPose.kt:73`,
     `PikePushUpPose.kt:73/128`, `DeadHangPose.kt:118`.
   - None of them currently composes two DOFs into one `JointRotation`. The "combined DOF silently drops
     one axis" risk (claimed in the design RFC) **does not manifest in any production pose today**. The
   composers exist and are correct; they are simply not the authoring path.

3. **Declarativity / single-writer hygiene (architectural, not behavioral).** Articulation is authored on
   the node and read back from the node by the Finalizer. This is the *only* data class that round-trips
   through a `SkeletonNode` as if it were Shape but is consumed as intent. That is a purity wart, not a bug.

**Finding:** Branch C, if it exists, solves exactly **one real defect** (the dead opt-out branch) plus
two *hygiene/consistency* concerns. There is **no correctness regression** that a carrier fixes.

---

## 2. Which remaining authored values belong to articulation (not ROM) semantics?

The audit enumerates every authored `localRotation` on a hand/ankle node (the §1.3 Articulation class) and
confirms each is **not** a ROM-bearing joint:

- ROM joints per the taxonomy/code are spine (LUMBAR/CHEST), neck (NECK_END), hip (HIP_F/HIP_B), shoulder,
  girdle (CLAVICLE*) — all already carrier-backed in Branch B (consumed by `applyIntentCarriers`,
  `SkeletonPoseFinalizer.kt:240-259`).
- The 22 hand/ankle writes are on HAND_A/HAND_P (wrist) and ANKLE_F/ANKLE_B. These are **endpoint
  articulations**, consumed only by `adjustHandOrientation`/`adjustFootOrientation` to derive palm/heel
  geometry. They are **never** read by `applyIntentCarriers` (the ROM consumer) and **never** measured by
  `validateHipRom`/`validateAngularJointLimit`'s ROM path (the latter reads joint *positions*, not the
  authored wrist/ankle rotation — `ExerciseValidator.kt:582-585`, `:346-349`).
- The wrist's child `WRIST_*` node carries **no independent authored rotation**: `adjustHandOrientation`
  does `wrist.set(hand)` (`SkeletonPoseFinalizer.kt:698`), i.e. the wrist is collocated with the hand and
  inherits the hand's world position. So the only authored articulation value per extremity is the single
  `HAND_*`/`ANKLE_*` `localRotation`.

**Conclusion:** the 22 values are unambiguously §1.3 Articulation, not ROM. The taxonomy's classification
is correct and the code confirms it — but this only *reinforces* that they are already handled by the
existing W1 derivation path; it does not, by itself, justify a new carrier.

---

## 3. Are those values consumed directly by SkeletonPoseFinalizer, or merely propagated through FK?

**They are consumed directly by the Finalizer — but they reach it via FK propagation of the node.** This is
the pivotal evidence:

- The pose writes `hand.localRotation.set(...)` / `ankle.localRotation.set(...)` during `build()`.
- `finalize` does `outputPose.copyFrom(pose)` (`SkeletonPoseFinalizer.kt:510`), then FK-flattens the roots
  into `outputPose` (`SkeletonPoseFinalizer.kt:516-521`). So `outputPose.getJointRotation(Joint.HAND_*)`
  is the **FK-propagated world rotation** of the hand/ankle node.
- The Finalizer reads it back and strips the parent frame:
  - `SkeletonPoseFinalizer.kt:559` `relativeRotation(outputPose.getJointRotation(Joint.ANKLE_F), outputPose.getJointRotation(Joint.KNEE_F), relAnkle)`
  - `:571` `relativeRotation(outputPose.getJointRotation(Joint.HAND_A), outputPose.getJointRotation(Joint.ELBOW_A), relWrist)`
  (and the B-side mirrors).
- `relativeRotation` (`:676-682`) computes `inverse(parent) ∘ world` → the **articulation relative to the
  parent segment**. This is passed as `wristRotation`/`ankleRotation` into `computeHandJoints` /
  `computeHeelToe`.
- `HandDefinition.computeHandJoints(wrist, dir, wristRotation, result)` (`HandDefinition.kt:41-50`) calls
  `SkeletonMath.rotAround(direction, wristRotation.axis, wristRotation.angle, scratchDir)` — i.e. the
  authored rotation **actually bends the hand/foot direction** that drives palm/fingertip/heel/toe geometry.

So: **the authored value is consumed directly by the Finalizer** (it is the sole reader — grep over
`app/src/main` finds no other reader of `getJointRotation(Joint.HAND_*)`/`ANKLE_*`), and it reaches the
Finalizer **through FK of the node**, not through a separate carrier. The node is the *de facto* carrier
today; FK is the transport.

---

## 4. Is a dedicated `extremityArticulation` carrier architecturally justified?

**No — not on correctness or necessity grounds.** The evidence:

1. **The data already has a single writer (Pose) and single reader (Finalizer).** The taxonomy's
   single-writer rule (RFC_INTENT_TAXONOMY §3) is *already satisfied* by the node: Pose writes
   `HAND_*`/`ANKLE_*` `localRotation`; the Finalizer is the only consumer. Introducing a carrier would
   **duplicate** this ownership into a parallel §1.1 field that carries the *same* `JointRotation` the node
   already carries, sourced from the *same* `FK-propagated world rotation` the Finalizer already reads
   (`SkeletonPoseFinalizer.kt:559/571`). The carrier would be a re-packaging of data that is already
   flowing correctly.

2. **The only consumer needs the FK-propagated world rotation, not a stored intent.** `relativeRotation`
   requires `outputPose.getJointRotation(Joint.HAND_*)` — the *world* rotation after FK. A §1.1 carrier
   stores the authored *local* rotation; to feed `relativeRotation` the Finalizer would still FK-propagate
   it (or the carrier would have to store the already-relative rotation, reproduced by the same
   `relativeRotation` math). Either way the carrier adds a storage hop with **identical** downstream math.
   No computation is eliminated; one read site (`getJointRotation`) is replaced by another
   (`extremityArticulations[extremity]`).

3. **The taxonomy itself says the carrier is "a refinement for declarativity, not a correctness fix"
   (§1.3).** This audit confirms that: the current path renders correctly (full suite 282/0; B4a
   byte-identical). A carrier would not change any rendered pixel on the AUTOMATIC path.

4. **The only real defect (dead opt-out) does not need a carrier.** The missing behavior is a `MANUAL_OVERRIDE`
   branch in `adjustHand/ FootOrientation` that *preserves the already-derived/stored endpoint geometry*.
   That branch reads the same node/carrier value; it is orthogonal to whether the value lives on the node
   or in a map. Fixing it is a ~10-line change in the Finalizer, independent of any carrier.

5. **Theoretical 2-DOF hazard is better fixed at the authoring call site, not via a new carrier.** Routing
   the 22 sites through `buildWristRotation`/`buildAnkleRotation` (already present, already tested by
   `WristAnkleHipArticulationTest.kt`) enforces 2-DOF composition. That refactor touches only pose files;
   it does not require a `SkeletonPose` field. If the value is kept on the node, the Finalizer reads it
   exactly as today.

**Therefore:** a dedicated `extremityArticulation` carrier is **not architecturally justified by
necessity**. It is a *stylistic* re-packaging. The existing runtime semantics (Pose → node → FK →
Finalizer reads world rotation → strips parent frame → derives geometry) are already sufficient and
single-owner.

---

## 5. Which engine components would have to change if Branch C (carrier) existed?

Enumerating the change surface, to weigh cost against the absent necessity:

- **`PoseDefinition.kt`** — add `val extremityArticulations: MutableMap<Extremity, JointRotation>`, plus
  `copyFrom`/`reset` handling and an `IntentBuilder.extremity(...)` writer (mirroring the now-retired
  `motion`/`camera`/`environment` setters removed in B4a — i.e. re-introducing the exact §1.1-field pattern
  B4a just purged).
- **`SkeletonPoseFinalizer.kt`** — change the four reads at `:559/565/571/577` from
  `getJointRotation(Joint.HAND_*/ANKLE_*)` to the carrier; add the OVERRIDE-preserve branch.
- **`BasePose.kt` / `BaseValidationPose.kt`** — reintroduce `buildWristArticulation` /
  `buildAnkleArticulation` (deleted in B4 step-2 as ROM-misrouted) to record the carrier + write the node
  (mixed mode).
- **22 pose files** — migrate the bare `localRotation.set` sites to the helpers.
- **`Section11CarriersTest.kt`** — add a carrier-live assertion.
- **No change** to `SkeletonMath` (composers stay), `HandDefinition`/`FootDefinition` (geometry stays),
  `ConstraintSolver`/`IkStage` (articulation is below the wrist/ankle IK end joint), `ExerciseValidator`
  (reads derived endpoint *positions*, not the rotation — `ExerciseValidator.kt:221-234`, `:331`, `:556`).

The change surface is **moderate** (a new §1.1 field + Finalizer re-point + 22-site migration) for a
**zero behavioral gain** on the AUTOMATIC path, and a fixable-by-10-lines opt-out defect.

---

## 6. Which existing tests would validate Branch C?

The branch would be covered by tests that already exist and already pass:

- **`WristAnkleHipArticulationTest.kt`** — validates `buildWristRotation`/`buildAnkleRotation` 2-DOF
  composition (`wristCombinesFlexionAndDeviation` :62, `ankleCombinesBothDofExactly` :107). Directly
  relevant to the articulation vocabulary; passes today.
- **`FootDerivationTest.kt`** — validates `computeHeelToe` invariants (R1 bone lengths, pitch clamp,
  `horizontalFootHasExactBoneLengths` :38). The carrier feeds the same `computeHeelToe`; this test would
  still pin correctness byte-for-byte.
- **`FinalizerIntentConsumersTest.kt`** — pins byte-identical output with the Finalizer consumer on/off
  (`carriersPopulatedByTrunkHipPoses` :72, maxDev 0.0). The articulation change must keep this green.
- **`Section11CarriersTest.kt`** — the §1.1 carrier-audit suite; the natural home for a
  `extremityCarriersLive` assertion (parallel to `romCarriersLiveAfterB4a`).
- **`IntentBuilderSubstrateTest.kt`** — already exercises `overrideExtremity` (`:80/:93`); would need the
  `extremity(...)` writer if reintroduced.

Note: **none of these tests currently fail.** They validate the *existing* node-based path. A carrier
implementation would have to keep all of them green while adding only the opt-out behavior — proving the
carrier changes nothing observable except that one (currently dead) branch.

---

## 7. Can Branch C be implemented independently from Branch B?

**Yes — completely independent.** Evidence:

- Branch B is **DONE** (ROM carriers live; full suite 282/0; B4a closed the last 5 ROM writes). The ROM
  consumer `applyIntentCarriers` (`SkeletonPoseFinalizer.kt:240-259`) reads `jointIntents`/`spineIntent`
  and does **not** touch HAND_*/ANKLE_*. Grep confirms no shared code path between ROM intent and
  articulation derivation.
- The articulation path (`adjustHand/ FootOrientation`, `computeHandJoints`/`computeHeelToe`,
  `buildWrist/AnkleRotation`) shares **no carrier, no stage, no test class** with Branch B.
- A Branch C change (new §1.1 field + Finalizer re-point + 22-site migration) would compile and test in
  isolation; it neither blocks on nor is blocked by any B artifact. The dependency edge `articulation -> B4`
  assumed by the old plan does not exist in the current codebase.

Independence is therefore not a reason *for* Branch C — it simply means C could be done anytime without
disturbing B.

---

## 8. Verdict: should Branch C be Mandatory / Optional / Future research / Not recommended?

### Assessment against the necessity bar

| Criterion | Finding |
|---|---|
| Fixes a correctness regression? | **No.** Suite is 282/0; B4a byte-identical. |
| Fixes a behavioral defect? | Only the **dead opt-out branch** — fixable in ~10 lines in the Finalizer, *without* a carrier (§1, §4.4). |
| Required by the taxonomy? | Taxonomy §1.3 says the carrier is "a refinement for declarativity, not a correctness fix"; §6 says
  "if a new carrier is needed." It is **not needed** for correctness. |
| Improves single-writer purity? | Marginal — the node already has a single writer (Pose) and single reader (Finalizer). A carrier
  duplicates ownership into a parallel field. |
| Eliminates computation? | **No.** The Finalizer still runs the identical `relativeRotation` + `computeHand/ Foot` math. |
| Adds regression surface? | **Yes** — new §1.1 field, Finalizer re-point, 22-site migration, re-introducing the exact field
  pattern B4a just retired. |

### Verdict

**Branch C as a dedicated `extremityArticulation` carrier is NOT RECOMMENDED.**

The architecture already satisfies the taxonomy's single-writer rule for articulation: Pose writes the
node, FK propagates it, the Finalizer is the sole reader and derives endpoint geometry. A carrier would
re-package data that already flows correctly, add a §1.1 field the B4a cleanup just removed, and introduce
migration churn — for zero rendered-output change on the AUTOMATIC path.

**What IS recommended (and does not require a branch or a carrier):**

1. **Fix the dead `MANUAL_OVERRIDE` branch** in `adjustHandOrientation`/`adjustFootOrientation`
   (`SkeletonPoseFinalizer.kt:556-579`): when an extremity is opted out, preserve the authored
   PALM/HEEL/TOE geometry instead of re-deriving it. This is the only real defect (§1.1) and is a small,
   contained Finalizer change. (If no pose ever opts out — currently true — this is pure hygiene; it can
   ship as a minor fix, not a branch.)
2. **Enforce 2-DOF composition at the authoring call sites** by routing the 22 writes through the existing
   `buildWristRotation`/`buildAnkleRotation` composers (`SkeletonMath.kt:930/942`, already tested by
   `WristAnkleHipArticulationTest.kt`). No new field; Finalizer unchanged.

If, after (1)/(2), a future requirement appears (e.g. the validator needs to *measure* authored grip/foot
intent, or a pose genuinely needs opt-out and the node path proves insufficient), then a **structured
articulation value** (flexion+deviation / dorsi+inversion, not a bare `JointRotation`) could be
reconsidered as **Future research** — but only with a concrete consumer that the node path cannot serve.

### Classification

- **Mandatory:** No.
- **Optional:** The underlying *fixes* (opt-out branch + composer adoption) are worthwhile minor
  improvements, but they are ordinary maintenance, not an architectural branch.
- **Future research:** A carrier only becomes interesting if a new *consumer* (ROM-like measurement of
  grip/foot intent, or a validator rule) emerges that the node-as-carrier cannot serve.
- **Not recommended (as proposed):** The `extremityArticulation` carrier as a standalone architectural
  branch is not justified by the current code.

---

## 9. Evidence index (production code cited)

- `SkeletonPoseFinalizer.kt:510` — `outputPose.copyFrom(pose)` then FK-flatten `:516-521`.
- `SkeletonPoseFinalizer.kt:556-579` — extremity dispatch; reads `getJointRotation(Joint.HAND_*/ANKLE_*)`
  (`:559/565/571/577`); `MANUAL_OVERRIDE` branch never preserves geometry.
- `SkeletonPoseFinalizer.kt:676-682` — `relativeRotation` strips parent frame -> articulation relative to segment.
- `SkeletonPoseFinalizer.kt:698` — `wrist.set(hand)` (wrist carries no independent rotation).
- `SkeletonPoseFinalizer.kt:684-714` / `:716-807` — `adjustHandOrientation`/`adjustFootOrientation` derive
  palm/heel via `computeHandJoints`/`computeHeelToe`.
- `HandDefinition.kt:41-50` — `computeHandJoints` uses `SkeletonMath.rotAround(dir, rot.axis, rot.angle)`.
- `FootDefinition.kt:42-55` — `computeHeelToe` uses `rotAround(neutralForward, ankleRotation...)`.
- `SkeletonMath.kt:930/942` — `buildWristRotation`/`buildAnkleRotation` 2-DOF composers (present, tested).
- `SkeletonMath.kt` `rotAround` (Rodrigues) confirms the authored rotation bends the direction.
- `PoseDefinition.kt:185/238/249/360` — `extremityOverrides`/`overrideExtremity` plumbing; no pose caller.
- `BasePose.kt:163`, `BaseValidationPose.kt:168` — `overrideExtremityOrientation` defined, never called.
- 22 authored articulation writes: `HamstringStretchPose.kt:107/108`, `ThoracicExtensionPose.kt:84/85`,
  `JumpSquatPose.kt:95/96/112/113`, `BaseVerticalPullPose.kt:254/255/265/266/269/270/273/274`,
  `DynamicWorldsGreatestStretchPose.kt:73`, `PikePushUpPose.kt:73/128`, `DeadHangPose.kt:118` — all
  single-axis `localRotation.set`.
- `ExerciseValidator.kt:221-234/331/346-349/556/582-585` — validator reads derived endpoint *positions*
  and joint *positions*, never the authored wrist/ankle rotation.
- `ConstraintSolver.kt:164-191` — contacts keyed on HAND_*/ANKLE_* joint (the IK end joint); articulation
  is below it and does not affect solving.
