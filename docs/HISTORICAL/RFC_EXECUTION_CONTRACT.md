# RFC — Execution Contract: IK · ConstraintSolver · SkeletonPoseFinalizer · FK

> [!IMPORTANT]
> **STATUS: SUPERSEDED (historical).** The execution contract it specifies is now live and the
> *de facto* runtime path. The feature-flag table in §14 references `EngineFlags` members
> (`PIPELINE_ACTIVE`, `SOLVER_OWNS_POSTURE`, etc.) that were **deleted in the cleanup** (Phases
> A–G of `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md`); all those flags were collapsed to their `on` (architecture-v2)
> branch, so the "Off (legacy)" column is obsolete. Retained for archaeology.

**Status:** Draft specification (no code). → **SUPERSEDED / contract now live**; see banner above.
**Author:** Principal Engine Architect.
**Addresses:** the execution contract between the four geometry stages of Architecture v2
(`ARCHITECTURE_V2.md` §2.2–§2.5, §3) — IK, ConstraintSolver, SkeletonPoseFinalizer, FK.
**Companion RFCs:** `RFC_ENGINE_PIPELINE.md` (Gap 1, orchestration) and `RFC_INTENT_LAYER.md`
(Gap 2, carriers). This document specifies the *internal* contract of the stages the pipeline drives.
**Ownership is fixed by the spec — NOT redesigned here.** This RFC specifies *exactly how* the fixed
ownership is executed: contracts, inputs, outputs, immutability, state transitions, re-entrancy,
convergence, data ownership, world/local conversions, contact/chest/root invariants, failure
recovery, bounded iterations, and migration flags.

---

## 0. Reality anchor (what exists today, and the seams this contract governs)

Read from source (not invented):

- **IK entry** — `bakeIkLimb(rootWorldPos, targetWorldPos, pole, constraint, middleNode, endNode, …)`
  (world-only overload in `BasePose`). It calls `SkeletonMath.solveIK`/`solveStraightLimb`, writes
  `middleNode.localPosition` + `endNode.localPosition` (in the **live node tree**) via
  `toLocalDirection`, ANDs `boneLengthsVerified`, ORs `maxIkClampAmount`, sets `straightIntentDropped`.
  The world pole is derived via `deriveDefaultPole` when the pose passes a zero pole (F6).
- **ConstraintSolver.solve(pose, definition)** — mutates `pose.roots` (the `SkeletonNode` tree) + §1.2
  stamps. Operates only when `pose.contacts` non-empty AND `pose.roots` non-empty. Snapshots authored
  pelvis rot/pos; re-arms `boneLengthsVerified`; seeds root from `PostureIntent` when
  `SOLVER_OWNS_POSTURE`; eases toward `lastSolvedRoot[pose]` (F9); runs a damped Jacobi
  root-reposition loop (max `MAX_ITERATIONS=16`); applies pelvis-tilt DOF on ground contacts (Issue A);
  re-bakes each contact limb in the parent's true local frame; runs a true CCD posture pass
  (`solvePosture`); records `rootTranslationDelta`/`rootRotationDelta`; then calls
  `SkeletonPose.fromHierarchy(roots, pose)` (FK + flatten) at the **end of solve**.
- **SkeletonPoseFinalizer.finalize(pose)** — **internally calls `ConstraintSolver.solve` again** when
  `pose.roots.isNotEmpty() && pose.hasContacts()` (the latent re-entrancy). Then `preConvertPoles`
  (no-op unless `FINALIZER_OWNS_CONVERSION`), copies into `outputPose`, runs FK flatten (guarded by
  `isTransformsUpdated`), `reconstructChestFrame` (only when chest rotation is identity), and the W1
  extremity derivation (`adjustFootOrientation`/`adjustHandOrientation` unless `extremityOverrides`).
- **FK/flatten** — `SkeletonPose.fromHierarchy(roots, targetPose)` = `updateWorldTransforms` +
  `flatten` per root, sets `isTransformsUpdated = true`. `SkeletonNode.updateWorldTransforms` /
  `flatten` are the stateless propagation primitives.

**Critical seams this contract must fix:**
1. **Double Solver call** — Finalizer calls Solver. Under architecture-v2 the pipeline calls Solver
   exactly once (stage 3). The Finalizer MUST NOT call it (re-entrancy abolished, see §8).
2. **Double FK/flatten** — Solver flattens at its end; Finalizer flattens again. Under architecture-v2
   there is exactly ONE terminal FK, owned by the pipeline (§6, §9).
3. **Solver writes `isTransformsUpdated`/flattens into `pose`** — after migration the Solver writes the
   node tree only; the pipeline owns the single terminal FK+flatten.

---

## 1. Stage contracts (fixed ownership, exact I/O)

### 1.1 IK stage (`IkStage` facade over `SkeletonMath` + `bakeIkLimb`)
- **Input:** `SkeletonPose` §1.1 (`limbTargets`, `jointIntents`, `extremityOverrides`) + a
  **pipeline-owned** `SkeletonNode` tree (fresh from `SkeletonFactory`, roots attached) +
  `SkeletonDefinition`.
- **Output:** the same tree with every `limbTargets[joint]` solved into `middle/end.localPosition`
  (world↔local via `toLocalDirection` against the proximal parent's world rotation); `jointIntents`
  applied as local rotations; §1.2 stamps `maxIkClampAmount` (OR-accumulated), `straightIntentDropped`
  (set when a straight limb was re-baked bent), `boneLengthsVerified` (AND across all limbs, re-armed
  optimistic at build start).
- **Immutable guarantee:** IK never reads §1.2; IK never moves the root/pelvis (only limb joints
  distal to their root); IK is **world-only** (no parent-frame pole accepted when
  `IK_WORLD_ONLY=true`; a zero pole triggers `deriveDefaultPole`).
- **World/local conversion:** IK *consumes* world targets and *writes* local offsets (the only place
  limb world→local happens). It does NOT convert chest-frame or pole (those belong to Finalizer /
  Solver respectively).
- **Ownership:** sole writer of limb joint `localPosition`/`localRotation` for non-contact limbs and
  of the IK stamps. Does **not** own root, contacts, chest-frame.

### 1.2 ConstraintSolver (`solve`, called ONCE by pipeline, stage 3)
- **Input:** `SkeletonPose` §1.1 (`contacts`, `contactPrecedence`, `postureIntent`,
  `extremityOverrides`) + the IK-solved tree (post-stage-2). NO freshly-built tree; it operates on
  the pipeline-owned tree.
- **Output:** the same tree with root/pelvis transform repositioned/tilted; contact limbs re-baked in
  their (now moved) parent frame; §1.2 stamps `rootTranslationDelta`, `rootRotationDelta`
  (recomputed from authored pelvis), `boneLengthsVerified` (re-AND across re-baked contact limbs).
- **Immutable guarantee:** Solver is **the sole mover of root/pelvis**. It never writes limb targets
  it wasn't given; never invents contacts; never wipes a deliberate authored lean (preserves
  `authoredPelvisRot`/`authoredPelvisPos` as the no-op baseline). Solver does NOT call the Finalizer
  and does NOT call itself.
- **World/local conversion:** Solver works in **world space** for reachability (compares
  `parent.worldPosition` to `spec.targetWorld`) but writes limb offsets back via `toLocalDirection`
  against the proximal parent's **current** world rotation (correct because FK runs at the top of each
  iteration). It does not perform chest-frame or pole conversion.
- **Ownership:** owns root/pelvis; secondary writer of `boneLengthsVerified`; writer of the two
  `root*Delta` stamps.

### 1.3 SkeletonPoseFinalizer (`finalize`, stage 4 — called ONCE by pipeline)
- **Input:** the Solver-settled tree + §1.1 (`spineIntent`, `jointIntents`, `extremityOverrides`,
  `headTarget`).
- **Output:** §1.2 `nodes` fully written: all world↔local conversion concentrated here
  (`preConvertPoles` active), extremity derivation (unless override), relative tilt-cancel,
  chest-frame reconstruction (F1/B5), flatten into `outputPose`. **Finalizer does NOT call Solver.**
- **Immutable guarantee:** Finalizer is the **exclusive writer of every local transform after this
  point**. It MUST NOT translate the root or move a Solver-settled contact end-effector (F1). If
  chest-frame reconstruction would displace a settled contact, it rolls back and signals the pipeline
  (bounded re-pass, §8) — it never silently mutates.
- **World/local conversion:** the **single** conversion owner. `preConvertPoles` asserts all pole→world
  work lives here; the legacy frame-relative `bakeIkLimb` overload is deleted (Gap 5) so no other
  component converts.
- **Ownership:** exclusive §1.2 `nodes` writer; owns chest-frame + extremity derivation.

### 1.4 FK + Flatten (terminal, stage 5 — owned by PIPELINE, not by any stage)
- **Input:** the Finalizer's §1.2 `nodes` (local transforms complete).
- **Output:** final world transforms in `SkeletonPose` (`fromHierarchy` sets `isTransformsUpdated`).
- **Immutable guarantee:** FK is **stateless propagation only**; it decides NO local rotations. Runs
  exactly once, after Finalizer, before Validator.
- **Ownership:** FK owns world-transform propagation; writes nothing but `isTransformsUpdated` + the
  world fields. It is the terminal step; Validator + Render read only.

---

## 2. Inputs / outputs summary table

| Stage | Reads (§) | Writes | Mutates tree? | Flattens? |
|---|---|---|---|---|
| IK | §1.1 `limbTargets`,`jointIntents`,`extremityOverrides` | limb `localPosition`/`localRotation`; `maxIkClampAmount`,`straightIntentDropped`,`boneLengthsVerified` | yes (limbs only) | no |
| Solver | §1.1 `contacts`,`contactPrecedence`,`postureIntent` | root/pelvis; re-baked contact limbs; `root*Delta` | yes (root + contact limbs) | **no** (pipeline owns FK) |
| Finalizer | §1.1 `spineIntent`,`jointIntents`,`extremityOverrides`,`headTarget` | §1.2 `nodes` (all) | yes (writes nodes) | **no** (pipeline owns FK) |
| FK | §1.2 `nodes` | world transforms; `isTransformsUpdated` | no (propagates) | yes (terminal) |

> **Change from today:** Solver and Finalizer each currently call `fromHierarchy` (FK+flatten)
> internally. Under this contract **neither does** — the pipeline performs the single terminal FK
> after Finalizer. This removes the double-flatten and makes FK's "runs once" guarantee enforceable.

---

## 3. Immutable guarantees (cross-stage)

- **§1.1 is frozen at `build()` return** (Intent Layer RFC §3/§7). No stage reads §1.1 as mutable; no
  stage writes §1.1. The Solver's "correction" is expressed as §1.2 (`root*Delta` + re-baked tree),
  never as a rewritten intent.
- **Single writer per §1.2 field** (spec §4.2): IK owns limb local + 3 IK stamps; Solver owns
  root/pelvis + `root*Delta` + secondary `boneLengthsVerified`; Finalizer owns `nodes`; FK owns world
  propagation. Overlap is resolved by **stage order**, not by shared mutation: a later stage may
  overwrite a tree node's local transform (that is its job) but only within its owned slice.
- **No stage reads another stage's in-progress §1.2 to *decide* geometry** except the pipeline-ordered
  chain (IK→Solver→Finalizer→FK), where each consumes the prior's output as its input. There is no
  back-edge.
- **Reachability/contact honors are visible to Validator only via stamps**, not via re-reading §1.1.

---

## 4. State transitions (per frame, on the single `SkeletonPose` carrier)

```
[build] §1.1 frozen, §1.2 empty, tree = null/empty
   │
   ▼  pipeline creates tree (SkeletonFactory), attaches roots
[IK]     tree.limbNodes solved; IK stamps written      (§1.2 partial: limbs)
   ▼
[Solver] root/pelvis moved; contact limbs re-baked; root*Delta written   (§1.2 partial: root)
   ▼
[Finalizer] all §1.2 nodes written (conversion + chest + extremities)    (§1.2 complete: nodes)
   ▼
[FK]     world transforms propagated; isTransformsUpdated = true         (§1.2 complete: world)
   ▼
[Validator] reads §1.2 + §1.1 ROM → report (no mutation)
```
**Transition invariants:**
- Each arrow is **total**: the pipeline either completes all four stages and returns a `PipelineResult`,
  or throws (no partial `SkeletonPose` escapes). (All-or-nothing, `RFC_ENGINE_PIPELINE` §8.4.)
- A stage may assume its input invariants hold (validated at the prior boundary or by Intent-self-
  validation). It must establish its output invariants before returning.
- `isTransformsUpdated` is set **only by the terminal FK** (pipeline), not by Solver/Intermediate.

---

## 5. Re-entrancy (abolished)

- **Today:** `SkeletonPoseFinalizer.finalize` calls `ConstraintSolver.solve` internally → if anything
  (renderer, snapshot) calls `finalize` twice, Solver runs twice on a mutated tree.
- **Contract:** `ConstraintSolver.solve` is called **exactly once per frame**, by the pipeline, in
  stage 3. `SkeletonPoseFinalizer.finalize` **MUST NOT** call `solve`. The internal `if
  (pose.roots.isNotEmpty() && pose.hasContacts()) ConstraintSolver.solve(pose, definition)` block is
  **deleted**. Contact solving happens in stage 3 only.
- **Guard (defense in depth):** `ConstraintSolver` keeps an `AtomicBoolean solving` (or a thread-local
  re-entrancy token supplied by the pipeline); a second concurrent/recursive `solve` throws
  `IllegalStateException("Solver re-entrant")`. The pipeline is single-threaded per pose instance, so
  this is a hard invariant, not a race guard.
- **No recursion:** Finalizer→Solver edge removed; Solver→Finalizer edge never existed; Solver→Solver
  edge removed (the internal loop is iterative, not recursive). The DAG is strictly forward.

---

## 6. Convergence rules (Solver iteration)

- **Outer root-reposition loop:** damped Jacobi, `MAX_ITERATIONS = 16` (constant), `RELAX = 0.5f`,
  `EPS = 1e-3f`. **Terminates early** when no contact required a root move (`!moved → break`). This is
  the primary convergence: well-posed poses (residual ~0) exit at iteration 0–1.
- **Posture CCD pass (`solvePosture`):** `POSTURE_MAX_ITERS = 12`, `POSTURE_EPS = 1e-3f`,
  `POSTURE_DAMP = 0.5f`, regularized toward `authoredRotBuf` (UNI-1). Converges the residual across
  free joint angles; for well-posed poses it is a strict no-op (authored shape preserved).
- **Monotonicity:** each Jacobi step moves the root *toward* the contact reach-band (contraction), so
  the sequence is Cauchy-bounded; it cannot oscillate because corrections shrink by `RELAX` and stop
  below `EPS`.
- **Bounded by construction:** both loops have hard caps (16, 12). No infinite loop is possible even
  for degenerate/over-constrained input — it simply stops at the cap and surfaces the residual via
  `rootTranslationDelta` + Validator.
- **Idempotence:** re-running `solve` on an already-settled tree is a no-op (`!moved → break`
  immediately), so the deleted Finalizer-solve call was redundant anyway.

---

## 7. Data ownership (tree vs carrier)

- **`SkeletonNode` tree (`pose.roots`):** owned by the **pipeline** for the frame's duration. It is
  created by the pipeline (stage 2 entry), mutated in sequence by IK→Solver→Finalizer, and read by FK.
  No stage *retains* the tree after the frame; the pipeline may pool/reuse it across frames (perf) but
  always resets it at stage 2 entry.
- **`SkeletonPose` §1.1:** owned by Pose (frozen). Stages read only.
- **`SkeletonPose` §1.2:** owned by the stages in sequence (IK limbs → Solver root → Finalizer nodes →
  FK world). The pipeline transfers "write custody" implicitly by call order; the Finalizer is the last
  writer before FK.
- **Stamps:** each stamp has one primary writer (IK: `maxIkClampAmount`/`straightIntentDropped`/
  `boneLengthsVerified` re-arm; Solver: `root*Delta` + `boneLengthsVerified` re-AND). The re-AND/OR
  discipline means a later stage may *refine* a stamp set by an earlier one, but only in the documented
  direction (AND for `boneLengthsVerified`, OR for `maxIkClampAmount`).
- **`outputPose` (inside Finalizer):** a private buffer the Finalizer copies into and returns; it is
  the pipeline's finalized output. Render/Validator receive it read-only.

---

## 8. World/local conversions (single owner)

- **World→local (limb):** IK only. `toLocalDirection(worldOffset, proximalParentWorldRot,
  node.localPosition)`. The proximal parent's world rotation is the *current* one at IK time (tree not
  yet posture-settled, which is correct — limbs are solved in the IK-frame).
- **World→local (contact re-bake):** Solver only, and only for contact limbs, using the *Solver-current*
  proximal parent world rotation (FK runs at the top of each Solver iteration, so this is consistent).
- **Local→world (all):** FK only (`updateWorldTransforms`), stateless, terminal.
- **Pole:** derived world-space by `deriveDefaultPole` inside IK (F6) when the pose passes a zero pole;
  Solver computes the contact world pole from `spec.pole` (already world). **No stage accepts a
  local-frame pole** when `IK_WORLD_ONLY=true` (the deprecated frame-relative `bakeIkLimb` overload is
  deleted, Gap 5).
- **`preConvertPoles` (Finalizer):** the *residual* conversion owner — any deferred frame work
  (chest-frame, extremity) is expressed here. With `FINALIZER_OWNS_CONVERSION=true` this is the single
  conversion entry; every other converter is deleted or routed through it.

---

## 9. Contact invariants (F1-contact, F7)

- **A contact end-effector is pinned** at `spec.targetWorld` (on its support plane) by the Solver.
  After Solver, the contact node's world position equals (within `EPS`) `spec.targetWorld`.
- **No later stage may move a Solver-settled contact.** The Finalizer's `reconstructChestFrame` takes a
  **snapshot** of every contact end-effector world position *before* reconstruction and **asserts**
  them unchanged *after*; if reconstruction would displace one, it rolls the chest frame back to the
  Solver-settled value and bumps `rootTranslationDelta` (so Validator surfaces the residual). This is
  the F1/B5 no-move guarantee, made enforceable because Solver runs once (§5) and its result is the
  bounded input to Finalizer.
- **Precedence (F7):** `contactPrecedence` orders conflict resolution; the Solver's `applyRootDelta`
  weights the per-contact correction by precedence rank. Precedence is a permutation of the active
  contacts (validated I5 in Intent Layer); empty = uniform mean.
- **Contact limb re-bake preserves bone lengths** (F5): every re-bake ANDs `boneLengthsVerified`; a
  violated limb flips it `false` → Validator flags `BONE_LENGTH_VIOLATION`.

---

## 10. Chest-frame guarantees (F1, Issue F)

- **Authored chest rotation wins:** if `chest.localRotation.angle > 1e-4`, `reconstructChestFrame`
  returns early (Issue F). The author's thoracic twist/side-bend/flex propagates to shoulders/arms/
  neck/head via FK and is never overwritten.
- **Fallback reconstruction (identity chest):** when the chest rotation is identity (e.g. a trunk
  oriented only by pelvis/legs, push-up plank), the chest frame is re-derived from the spine
  (`pelvis→chest`/`lumbar`) and shoulder line (`shoulderA→shoulderP`), giving an orthonormal basis.
  For a symmetric thorax this equals the FK frame (neutral trunk unchanged).
- **Composed against the actual parent** (Issue E): the reconstruction composes against `chest.parent`
  (LUMBAR for the two-segment spine, PELVIS for single-segment). An authored lower-spine rotation is
  combined with the thoracic frame instead of being discarded.
- **No-move on settled contacts** (F1/B5): as in §9, snapshotted + asserted; rollback + `delta` bump on
  conflict.
- **Single execution:** runs exactly once, in Finalizer, after Solver, before FK.

---

## 11. Root guarantees (F2, UNI-1, UNI-6)

- **Sole mover:** only the Solver writes `pelvis.localPosition`/`pelvis.localRotation` (during
  root-reposition + tilt DOF). IK and Finalizer never translate the root.
- **Preserves authored lean:** `authoredPelvisRot`/`authoredPelvisPos` are snapshotted; the Solver only
  *adds* a correction (translation toward contact reach-band, tilt DOF on ground contacts). For
  symmetric/well-posed poses the correction is ~0 → authored pelvis verbatim (no-op).
- **Posture seed (F2):** when `SOLVER_OWNS_POSTURE`, the root is seeded from `PostureIntent` (B1.1
  formulas: `SEATED_NEAR_FLOOR → def.shinLength*0.35`, `STANDING → def.shinLength+def.thighLength+25`,
  `HANGING_UNDER_BAR → bar height`, `CUSTOM → 0`). This replaces pose hand-computed `pelvisY`.
- **Delta recording (UNI-6):** `rootTranslationDelta` = ‖pelvis.pos − authoredPelvisPos‖;
  `rootRotationDelta` = angle of `R_authored⁻¹·R_current`. Both feed the Validator's `PELVIS_INTENT`
  rule so unexpected root motion is surfaced, never hidden.
- **Inter-frame smoothing (F9):** `lastSolvedRoot[pose]` (WeakHashMap keyed by pose identity) eases the
  current root toward the previous frame's solved root (gain `SMOOTH_GAIN=0.25`). Disabled (gain 0) =
  exact per-frame solve. Smoothing reads/writes only the cache + pelvis position; it never touches §1.1.

---

## 12. Failure recovery

| Failure | Detection | Recovery |
|---|---|---|
| IK unreachable target | `ikResult.clampAmount > 0` → `maxIkClampAmount` stamped | Validator emits `IK_UNREACHABLE`; pose renders clamped (intent dropped, surfaced) |
| Straight intent impossible | `solveStraightLimb` falls back to triangle IK | `straightIntentDropped = true`; Validator emits `STRAIGHT_INTENT_DROPPED` |
| Bone-length violated (F5) | `bonesExact` false on a limb | `boneLengthsVerified = false`; Validator emits `BONE_LENGTH_VIOLATION` |
| Over-constrained contacts (residual remains after caps) | root loop hits `MAX_ITERATIONS` / CCD hits `POSTURE_MAX_ITERS` with residual | Stop at cap; `rootTranslationDelta` reflects residual; Validator emits `CONTACT_RESIDUAL` |
| Chest-frame would move settled contact (F1/B5) | snapshot≠after in `reconstructChestFrame` | Roll back chest frame to Solver-settled; bump `rootTranslationDelta`; Validator surfaces |
| Intent malformed (I1–I10) | Intent-self-validation at t1→t2 | Abort frame (all-or-nothing); no `PipelineResult` returned |
| Solver re-entrant call | `solving` guard | Throw `IllegalStateException` (defense in depth) |
| Flag misconfig (`PIPELINE_ACTIVE` but `IK_WORLD_ONLY=false`) | startup invariant in `SkeletonPipeline` ctor | Fail fast at construction, not at frame time |

**No silent failure:** every deviation is encoded as a stamp or a Validator issue. The architecture
prefers a *visible, surfaced* degraded pose over a *silent* correct-looking one.

---

## 13. Bounded iterations (constants)

| Loop | Cap | Early-exit | Constant name |
|---|---|---|---|
| Root-reposition (Jacobi) | 16 | `!moved` | `ConstraintSolver.MAX_ITERATIONS` |
| Posture CCD | 12 | residual < `POSTURE_EPS` | `POSTURE_MAX_ITERS` |
| IK solve (per limb) | analytic (2-bone) | n/a | `SkeletonMath.solveIK` (closed-form) |
| Finalizer chest-frame | 1 (single pass) | n/a | — |
| Pipeline F1 re-pass | `F1_REPASS_CAP` (proposed = 2) | conflict resolved / capped | new constant |

**F1 re-pass cap:** if Finalizer signals a chest-frame/contact conflict, the pipeline may run **at most
`F1_REPASS_CAP`** additional Solver passes (each fully bounded by the caps above). After the cap, the
Solver result stands and Validator flags the residual. This bounds total work to `MAX_ITERATIONS ×
(1 + F1_REPASS_CAP)` per frame — deterministically finite.

---

## 14. Feature flags during migration (HISTORICAL — flags deleted in cleanup)

Flags lived in `EngineFlags` (`RFC_ENGINE_PIPELINE` §5.7); the object was deleted in the cleanup,
so every flag below is now permanently in its architecture-v2 (`on`) state. Their historical effect
on the *execution contract*:

| Flag (removed) | Off (legacy, obsolete) | On (architecture-v2, current) | Execution effect |
|---|---|---|---|
| `PIPELINE_ACTIVE` | `Pose.build()`+implicit finalizer (today's path) | pipeline drives all 4 stages in order | switches the *caller*, not the stages |
| `IK_WORLD_ONLY` | frame-relative pole accepted | only world pole; zero→`deriveDefaultPole` | IK rejects local-frame pole (Gap 5) |
| `SOLVER_OWNS_POSTURE` | Solver does legacy relaxation, ignores `postureIntent` | seeds root from `PostureIntent`, eases via `lastSolvedRoot` | activates F2/F9 authority (Gap 3) |
| `FINALIZER_OWNS_CONVERSION` | `preConvertPoles` no-op; conversion scattered | single conversion entry | activates F1/F4 exclusivity (Gap 4) |
| `VALIDATOR_STAMP_ONLY` | Validator infers angles from nodes | Validator reads stamps + §1.1 ROM only | Gap 6 / Phase 8 |

**Migration sequencing (per `RFC_ENGINE_PIPELINE` §6):**
- M0: `PIPELINE_ACTIVE=false`; legacy path unchanged. Stages exist but unused by the pipeline.
- M1: `IK_WORLD_ONLY=true`; delete deprecated frame-relative `bakeIkLimb` overload; migrate its **exactly 2** call sites (VERIFIED: `BaseLungePose.kt`, `BaseThoracicPose.kt`) to the world overload (Gap 5).
- M2: `PIPELINE_ACTIVE=true`; Finalizer's internal `solve` call **deleted** (re-entrancy gone);
  pipeline calls Solver once. Double-flatten removed (Solver no longer calls `fromHierarchy`).
- M3: `SOLVER_OWNS_POSTURE=true`; production poses adopt `declarePosture`.
- M4: `FINALIZER_OWNS_CONVERSION=true`; conversion centralized.
- M6: `VALIDATOR_STAMP_ONLY=true`; geometry inference removed.

**Invariant at construction:** if `PIPELINE_ACTIVE` then `IK_WORLD_ONLY && FINALIZER_OWNS_CONVERSION`
must be true (a pipeline without world-only IK or finalizer conversion is incoherent). `SkeletonPipeline`
asserts this in its constructor and fails fast.

---

## 15. Open questions (non-blocking)
- Should `F1_REPASS_CAP` be 1 or 2? (RFC proposes 2 for safety margin; benchmarking may show 1.)
- Should the Solver stop writing `boneLengthsVerified` re-AND and instead let IK own it exclusively?
  (RFC keeps the secondary-write discipline from today's code; revisit if stamps prove redundant.)
- The `outputPose` buffer inside Finalizer — under the pipeline, should the pipeline own the output
  buffer (Finalizer writes into a pipeline-supplied `SkeletonPose`) to avoid the internal copy? (RFC
  leaves Finalizer's buffer; can be lifted later.)
