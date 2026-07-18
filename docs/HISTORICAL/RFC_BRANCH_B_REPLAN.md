# RFC_BRANCH_B_REPLAN

> **Status:** PROPOSED (architectural, normative). Supersedes the *scope* (not the landed work) of
> `RFC_BRANCH_B_IMPLEMENTATION.md` §1–§9 in light of `RFC_INTENT_TAXONOMY.md`.
> **Trigger:** approval of the Intent Taxonomy. The taxonomy introduced a six-class data model
> (ROM Intent / Shape Constraint / Articulation Intent / Solver Output / Finalizer Output / Metadata +
> Validation-only) that reclassifies a large fraction of the migration surface the original Branch B
> plan intended to move. This document re-plans Branch B accordingly.
> **Non-goals:** no code, no new carrier, no Pipeline rewrite. This is a re-scoping + dependency
> document only. It does not undo anything already landed (B1/B2/B3/B5 stay done); it re-scopes what
> *remains* and formally deletes work that the taxonomy proved unnecessary.
> **Companion RFCs:** `RFC_INTENT_TAXONOMY.md` (the taxonomy this replan honours),
> `RFC_BRANCH_B_IMPLEMENTATION.md` (the plan being re-scoped), `RFC_DECLARATIVE_AUTHORING.md`
> (the semantic inventory the taxonomy overrides for wrist/ankle/structural helpers),
> `RFC_DECLARATIVE_POSE_AUTHORING.md` (Branch B design), `RFC_PHASE_I_CLOSURE.md` (Branch A baseline).
> **Date:** 2026-07-18.

---

## 0. Why a replan (the one-paragraph answer)

The original Branch B plan (`RFC_BRANCH_B_IMPLEMENTATION.md`) was written against
`RFC_DECLARATIVE_AUTHORING.md`, whose semantic inventory classified **almost every node-writing helper
as "Becomes intent"** — including structural offsets (`buildTorso`/`buildPelvis`/`buildShoulders`/
`buildHead`-offset), wrist/ankle articulations (`buildWristArticulation`/`buildAnkleArticulation`), and
the knee/segment shape rotations authored inline in poses. The Intent Taxonomy contradicts that
inventory on three of its six classes:

- **Shape Constraints** (structural offsets, knee straightness, limb-planar/segment rotations) are
  *legitimate direct `SkeletonNode` storage* and **must not migrate** (Taxonomy §1.2, §5).
- **Articulation Intent** (hand/foot/wrist/ankle orientation) is a **distinct carrier family**
  (`extremityArticulation`), **not** `jointIntents`, and is deferred to its **own branch** (Taxonomy
  §1.3, §6, §7).
- **ROM Intent** is the *only* class B4 actually migrates (Taxonomy §7, §8).

The result: Branch B's remaining scope collapses from "migrate ~18 helpers + all node writes" to
"migrate the **5 remaining ROM-Intent writes**, then purge dead ROM helpers." Several planned stages are
now obsolete or merge, the dependency graph loses the articulation edge, and a whole category of code is
proven to **never migrate at all**.

---

## 1. Is the current Branch B plan still optimal? — **No.**

**Verdict: the current plan for Branch B is _not_ optimal after the Intent Taxonomy.** It is
*over-scoped* (schedules Shape-Constraint and Articulation migrations that the taxonomy forbids or
defers), it *mis-attributes carriers* (routes wrist/ankle into `jointIntents`), and its dependency graph
carries an **articulation edge that no longer belongs to Branch B at all**.

Concretely, the plan is non-optimal in four ways:

1. **It migrates code that must stay put.** B4/B6 schedule deletion of `buildTorso`/`buildPelvis`/
   `buildShoulders`/`buildHead`(offset) and conversion of every `localRotation.set`. The taxonomy
   classifies the structural offsets and the knee/segment writes as Shape Constraints — *intended*
   direct node storage (Taxonomy §1.2 "this is the legitimate, intended storage"; §5 "must NOT be
   artificially converted to carriers"). Migrating them would *invent semantics that do not exist* and
   risk byte-identity regressions for zero declarativity gain.
2. **It routes Articulation Intent through the wrong carrier.** B2/B4 fold `buildWristArticulation`/
   `buildAnkleArticulation` (and the 22 raw hand/ankle writes) into `jointIntents`. The taxonomy says
   wrist/ankle are **not ROM DOFs**, must not pollute the ROM carrier, and belong to a dedicated
   `extremityArticulation` carrier consumed by the W1 `adjustHand/FootOrientation` derivation, **not**
   the generic B2 `applyIntentCarriers` re-FK path (Taxonomy §6 "Why `jointIntents` cannot be used").
3. **It keeps articulation inside Branch B.** The taxonomy moves the `extremityArticulation` carrier to
   **its own branch** (Taxonomy §7 "becomes its own Branch (e.g. Branch C)"). The Branch B graph should
   therefore *drop* the extremity/articulation dependency edge entirely.
4. **Its exit criterion is wrong.** B4's exit is "**zero** pose writes a node (R1)". Under the taxonomy
   that is *unattainable and undesirable* — Shape Constraints and (until Branch C) Articulation Intent
   remain deliberate node writes. The correct exit is "zero **ROM-Intent** node writes" (Taxonomy §8).

Everything *already landed* (B1, B2, B3, B5, and the three B4 steps) remains valid — none of it migrated
a Shape Constraint or mis-routed an articulation into ROM (the B4 steps touched trunk/hip/pelvis-tilt =
ROM). The non-optimality is entirely in the **remaining** scope and the **graph shape**, not in the
shipped work.

---

## 2. Per-stage verdict

For each stage: **still valid · needs split · obsolete · merge with another phase**, with the taxonomy
reason. "Landed" = already shipped per `RFC_BRANCH_B_IMPLEMENTATION.md`.

| Stage | Landed? | Verdict | Reason (taxonomy) |
|-------|---------|---------|-------------------|
| **B1 — IkStage extraction** | DONE | **still valid** | `limbTargets` is a genuine engine stage (Solver Output / IK), untouched by the taxonomy's authoring reclassification. Limb *placement* is not Shape/ROM/Articulation authoring; it stays. |
| **B2 — Finalizer intent consumers** | DONE | **still valid, but scope-narrowed** | The `spineIntent`/`jointIntents` re-application path is correct for **ROM Intent**. The taxonomy *removes* wrist/ankle from its remit (those were never truly consumed as ROM by B2 anyway — `extremityOverrides`/W1 handled extremities). No rework of landed code; the *helper-deletion list* attributed to B2 must drop `buildWrist/AnkleArticulation` (they are Articulation, → Branch C). |
| **B3 — Posture universality** | DONE | **still valid** | Posture is Solver Output seeding, orthogonal to the Intent/Shape/Articulation split. Unaffected. |
| **B4 — Pose migration (family-by-family)** | IN PROGRESS | **needs split** | B4 conflated three data classes. Split into **B4a = ROM-Intent migration (the only real remaining work)** and **B4b = Shape-Constraint / Articulation recognition (documentation-only: mark them valid direct writes, migrate nothing)**. See §3. Its exit criterion is redefined per Taxonomy §8. |
| **B5 — Validator stamp-only** | DONE | **still valid** | Validation-only data (Taxonomy §1.7). Already done; the stamps it created are the correct class. Unaffected. |
| **B6 — Closure & purge** | not started | **merge with B4a + narrow** | The "remove mixed-mode; assert *all* carriers live; zero node writes" purge is now mostly vacuous: Shape/Articulation node writes *stay*, so "all carriers live / zero node writes" is redefined as "all **ROM/limb/posture** carriers live; **ROM** helpers purged." The residual purge (delete dead ROM helpers, retire obsolete `motion`/`camera`/`environment` fields, flip `Section11CarriersTest` for the ROM carriers) is small enough to **merge into the tail of B4a** rather than stand as its own phase. |
| **(new) Branch C — `extremityArticulation`** | n/a | **new, out of Branch B** | The 22 hand/ankle writes + `buildWrist/AnkleArticulation` move here (Taxonomy §6/§7). Not a Branch B stage. |

---

## 3. B4 split (the heart of the replan)

The original B4 is one monolith over "every family, every node write." The taxonomy shears it along the
data-class boundary:

### B4a — ROM-Intent migration (the entire remaining B4)
- **Contains:** migrate the **5 remaining bare ROM writes** to carriers (Taxonomy §4 + §7):
  1. `ThoracicExtensionPose.kt:59` (lumbar) → `buildSpineCurve`/`spineIntent`
  2. `ThoracicExtensionPose.kt:65` (chest) → `buildSpineCurve`/`spineIntent`
  3. `GluteBridgePose.kt:90` (neck) → `declareJointIntent(NECK)`
  4. `PelvicTiltPose.kt:89` (neck) → `declareJointIntent(NECK)`
  5. `BasePushUpPose.kt:118` (hipF) → `buildHipFlexion`/`jointIntents`
- **Then (merged-in B6 tail):** delete the now-unused **ROM** helpers only (`buildSpineCurve`,
  `buildChest*`, `buildHip*` once their last ROM caller converts); retire the obsolete
  `motion`/`camera`/`environment` fields; flip `Section11CarriersTest` for the ROM carriers.
- **Exit (redefined, Taxonomy §8):** every ROM-bearing joint (spine/neck/hip/girdle) is carrier-backed;
  **no bare ROM `localRotation.set` remains**; Shape Constraints untouched; Articulation recognized;
  byte-identical; suite green. **Not** "zero node writes."

### B4b — Shape / Articulation recognition (documentation-only, migrates nothing)
- **Contains:** formally record, per the taxonomy, that:
  - the **3 knee/segment Shape-Constraint writes** (`PikePushUpPose.kt:89`, `BasePushUpPose.kt:109`,
    `BasePushUpPose.kt:129`) **remain direct node writes by design** (Taxonomy §1.2/§5) — **no code**.
  - the **structural-offset helpers** (`buildTorso`, `buildPelvis`, `buildShoulders`, `buildHead`-offset,
    `buildRigidSegment`) are **Shape Constraints** and are **removed from every deletion list** — they
    stay as the legitimate node writers (Taxonomy §1.2). This *reverses* `RFC_DECLARATIVE_AUTHORING.md`
    §1.5 and the B4/B6 helper-purge rows.
  - the **22 hand/ankle Articulation writes** are **recognized valid** direct authored articulations
    until Branch C exists (Taxonomy §4 "recognized valid", §8 point 3).
- **Exit:** the semantic-mismatch count is zero (Taxonomy §8 point 4); no helper on a Shape/Articulation
  path is scheduled for deletion. This phase produces documentation + test-classification, not geometry.

> B4b is not "work" in the migration sense — it is the taxonomy's explicit instruction that a chunk of
> the old plan **must not be executed**. It is listed as a stage only so the plan has a place to *record
> the non-migration decision* and close the exit criterion honestly.

---

## 4. Unnecessary / merged stages (the "some steps merge, some vanish" answer)

The taxonomy causes exactly the kind of collapse the task anticipated. Old → new:

```
old                         new
───                         ───
B4  (monolith: all writes)  →  B4a (ROM only)  +  B4b (recognize Shape/Articulation, migrate nothing)
B6  (closure & purge)       →  merged into the tail of B4a (ROM-helper purge only)
(articulation, was in B4)   →  Branch C  (leaves Branch B entirely)
```

- **B6 becomes unnecessary as a standalone phase.** Its content was "remove mixed-mode, assert *all*
  carriers live, zero node writes." Post-taxonomy, "all carriers live / zero node writes" is
  *false-by-design* (Shape + Articulation writes stay). What survives of B6 is a small ROM-only purge
  that rides B4a's tail. **B6 is therefore deleted as an independent stage** (merge).
- **The articulation half of B4/B2 is unnecessary in Branch B.** It does not disappear — it *relocates*
  to Branch C. Within Branch B it is removed from every stage's scope, deletion list, and dependency.
- **B4b is a net-new "stage" that exists only to *cancel* planned work** — the clearest signal that the
  taxonomy shrank the branch rather than grew it.

Net stage count for Branch B: **B1, B2, B3, B4a, B4b, B5** (six), down from **B0–B6** (seven, with the
articulation surface additionally excised). B0 was the substrate and is subsumed as done. B6 is gone.

---

## 5. Recalculated dependencies

The original graph carried a limb→spine and a limb→articulation coupling into B4, and gated B5/B6 behind
a monolithic B4. Post-taxonomy the articulation edge leaves the branch and B4 splits, so several edges
change.

**Edge-level changes (before → now):**

| Edge (old) | Status now | Why |
|------------|------------|-----|
| `B1 (limbs) → B4` (needed for families using both limbs and trunk) | **weakened → B1 → B4a only for limb-adjacent ROM** | B4a is ROM-only; the only B1 dependence is where a ROM write sits under a solved limb. Most of the 5 ROM writes (neck/lumbar/chest/hip) do **not** need B1. |
| `B2 → B4` (Finalizer consumes spine/joint/**extremity**) | **narrowed → B2 → B4a** (spine/joint ROM only) | Extremity consumption leaves Branch B (Branch C). B2→B4a carries only ROM. |
| `B3 → B4` (root arithmetic) | **B3 → B4a** (unchanged in kind) | Pelvis-tilt ROM already landed via `declarePelvisTilt`; posture seeding still precedes ROM families that lean the root. |
| `articulation → B4` (hand/ankle into `jointIntents`) | **DELETED from Branch B** | Moves to `Branch C` as `extremityArticulation`. No Branch B stage depends on it. |
| `B4 → B5` | **B4a → B5** already satisfied (B5 done) | B5 shipped its own stamps; the dependency is historical. |
| `B4 → B6`, `B5 → B6` | **DELETED (B6 merged into B4a tail)** | No standalone B6. |

**Articulation carrier, moved (the task's example, answered):**

```
Articulation carrier
   before:  a dependency of  B4   (folded into jointIntents)
   now:     a dependency of  Branch C  (its own extremityArticulation carrier)
```

i.e. the articulation carrier is no longer a Branch B node at all — it is the founding node of Branch C.
(The task's "B4 → B7" placeholder maps here to "B4 → Branch C": the work relocated to a *later, separate*
branch, not a later Branch B stage.)

---

## 6. New Branch B dependency graph (linear, B-native — not M0–M8)

```
Branch B (post-taxonomy)

B1  IkStage extraction ............................. [DONE]
      │  (limbTargets live; Solver/IK output — untouched by taxonomy)
      ▼
B2  Finalizer ROM consumers ........................ [DONE]
      │  (spineIntent + jointIntents = ROM only; extremity dropped → Branch C)
      ▼
B3  Posture universality ........................... [DONE]
      │  (Solver Output root seeding; orthogonal to Intent/Shape split)
      ▼
B4a ROM-Intent migration + ROM-helper purge ....... [IN PROGRESS]
      │   • migrate the 5 remaining bare ROM writes → carriers
      │   • delete dead ROM helpers (buildSpineCurve/buildChest*/buildHip*)
      │   • retire obsolete motion/camera/environment fields
      │   • flip Section11CarriersTest for ROM carriers
      │   • (absorbs the surviving fragment of the old B6)
      ▼
B4b Shape / Articulation recognition (NO migration) [DOC-ONLY]
      │   • knee/segment Shape Constraints: stay direct (Taxonomy §1.2/§5)
      │   • structural-offset helpers: removed from all deletion lists
      │   • 22 hand/ankle writes: recognized valid until Branch C
      ▼
B5  Validator stamp-only ........................... [DONE]
          (Validation-only data; already shipped its own stamps)

   ── Branch B closes here. No B6. ──

────────────────────────────────────────────────────────
Branch C (NEW, separate — not Branch B)

C1  extremityArticulation carrier (Taxonomy §6)
      • MutableMap<Extremity, JointRotation> on SkeletonPose
      • Pose sole writer (IntentBuilder.extremity(...))
      • Finalizer sole reader (adjustHand/FootOrientation)
      • migrate the 22 hand/ankle writes + buildWrist/AnkleArticulation here
```

**Textual dependency form:**

```
B1 ──▶ B2 ──▶ B3 ──▶ B4a ──▶ B4b ──▶ B5(done)
                       │
                       └────────────▶ (Branch C, independent) C1
```

- B1→B2→B3 are done and remain a valid ordered chain.
- B4a is the sole remaining *implementation* stage; B4b is doc-only and rides behind it.
- B5 is done; it no longer *blocks on* B4a (it created its own stamps).
- **No B6.** Its ROM-only remnant lives in B4a's tail.
- **Branch C is off-graph for Branch B** — the articulation edge that used to enter B4 now enters C1.

---

## 7. Code that no longer needs to migrate at all (the most important finding)

The taxonomy proves that entire categories of code the old plan scheduled for migration should **never**
move. Verified against source (`BasePose.kt`, `BaseValidationPose.kt`, and the B4 audit in Taxonomy §4).

### 7.1 Shape Constraints — structural offset helpers (**do not migrate; keep as node writers**)

These write **only `localPosition` from fixed structural offsets** — the canonical Shape Constraint
(Taxonomy §1.2: "the legitimate, intended storage"). The old `RFC_DECLARATIVE_AUTHORING.md` §1.5 wrongly
tagged them "Becomes intent"; the taxonomy reverses this. **Removed from every deletion list.**

| Helper | Write (verified) | Old plan | New status |
|--------|------------------|----------|------------|
| `buildTorso` | `chest.localPosition.set(-torsoLength, 0, 0)` (BasePose.kt:39) | Becomes intent → delete (B4/B6) | **Shape Constraint — keep, never migrate** |
| `buildPelvis` | `hipF/hipB.localPosition.set(0,0,±hipWidth)` (BasePose.kt:78–79) | Becomes intent → delete | **Shape Constraint — keep, never migrate** |
| `buildShoulders` | `shoulderA/P.localPosition.set(0,0,±shoulderWidth)` (BasePose.kt:83–84) | Becomes intent → delete | **Shape Constraint — keep, never migrate** |
| `buildHead` (offset half) | `neck/head.localPosition.set(dir*len)` (BasePose.kt:43–44) | Splits (offset→intent) → delete offset | **offset half is Shape Constraint — keep; only the *direction* half is already `headTarget` (done)** |
| `buildRigidSegment` | `child.localPosition` from fixed offset | Becomes intent → delete | **Shape Constraint — keep** (already deleted only where it had 0 callers; where used, it stays) |

### 7.2 Shape Constraints — knee/segment inline writes (**do not migrate**)

Per Taxonomy §4, these are Shape Constraints and stay direct — **no B4 work**:
- `PikePushUpPose.kt:89` (kneeB), `BasePushUpPose.kt:109` (kneeF), `BasePushUpPose.kt:129` (kneeB).

### 7.3 Articulation Intent — hand/ankle writes (**do not migrate in Branch B**)

The 22 hand/ankle `localRotation.set` sites (Taxonomy §4: BaseVerticalPull ×10, JumpSquat ×4, Hamstring
×2, Thoracic ×2, DynamicWGS ×1, PikePushUp ×2, DeadHang ×1) and the helpers `buildWristArticulation` /
`buildAnkleArticulation` are **Articulation Intent**, not ROM. They **must not** go into `jointIntents`
and **must not** be deleted in Branch B. They relocate to **Branch C** (`extremityArticulation`) or stay
as recognized-valid direct writes until C exists.

### 7.4 Summary of "never migrates in Branch B"

```
Structural offsets      (buildTorso/Pelvis/Shoulders/Head-offset/RigidSegment)  → Shape Constraint, keep
Knee/segment rotations  (3 sites)                                               → Shape Constraint, keep
Hand/ankle orientation  (22 sites + buildWrist/AnkleArticulation)              → Articulation, → Branch C
```

Everything above was in-scope for deletion/migration under the *old* plan. The taxonomy removes all of
it from Branch B. What remains for Branch B is **only the 5 ROM writes + the ROM-helper purge**.

---

## 8. Net effect

- **Answer to "is the plan still optimal?"** — No. It is over-scoped and mis-routed; see §1.
- **Stages:** B1/B2/B3/B5 still valid (done); B4 splits into B4a (real) + B4b (doc-only); B6 merges into
  B4a; articulation leaves for Branch C. See §2–§4.
- **Dependencies:** the articulation edge exits Branch B; B4→B5→B6 chain collapses; B4a is the last real
  stage. See §5–§6.
- **Remaining Branch B implementation work:** migrate **5 ROM writes** + purge dead ROM helpers +
  retire 3 obsolete fields. That is the whole of it (Taxonomy §7/§8).
- **Code that never migrates:** all structural offsets, all knee/segment shape rotations, all hand/ankle
  articulations — three whole categories the old plan would have churned for zero gain and nonzero
  regression risk. See §7.

Branch B, post-taxonomy, is a **much smaller branch than planned**, and a new, separate **Branch C**
carries the only piece that genuinely deserves a new carrier.
