# RFC_PHASE_I_CLOSURE

> **Purpose:** canonical reference for the completed **Architecture v2 — Phase I (Engine Runtime)**
> before any Intent Authoring work begins. It closes the documentation loop for M0–M4 and formally
> splits Architecture v2 into two branches: **Branch A (Engine Runtime, done)** and **Branch B
> (Intent Authoring Rewrite, future)**.
>
> **Companion RFCs:** `RFC_ENGINE_PIPELINE.md` (stage design), `RFC_GAP_CLOSURE.md` (milestone gates),
> `RFC_INTENT_LAYER.md` (Branch B target design), `RFC_INTENT_BUILDER_REWRITE.md` (Branch B current-state
> audit + required work). **Date:** 2026-07-17. **Verified by:** source audit of
> `app/src/main/java/com/monkfitness/app/` + `app/src/test/java/com/monkfitness/app/` + existing RFCs.
>
> **Constraints honoured:** no production code modified; no tests modified; roadmap status unchanged
> (M5 remains BLOCKED, M6/M7 unchanged in `RFC_GAP_CLOSURE.md`). This is a documentation-only milestone
> closure. Final suite state referenced: **258 tests / 0 failures / 0 errors** (M4 merge).

---

## 1. Phase I Scope

**Architecture v2 Phase I = the Engine Runtime.** Its objective was to establish a single, ordered,
flag-governed execution pipeline that owns the Solver↔Finalizer call order and makes engine ownership
of root posture and final conversion explicit — **without changing any production pose's rendered
output**. Every Phase I milestone was a *flag flip* or *re-pointing* over the existing imperative
authoring model; none touched pose authoring. The deferred "Pose becomes intent-only" rewrite was
explicitly out of Phase I scope (recorded in `RFC_GAP_CLOSURE.md` M2 note and `RFC_INTENT_BUILDER_REWRITE.md`).

### Completed milestones

#### M0 — Pipeline scaffold (Gap 1)
- **Original objective:** introduce `SkeletonPipeline` orchestrating `build → finalize → optional
  validate`, default-off (`PIPELINE_ACTIVE=false`), zero behavior change, zero consumers/poses touched.
- **Implemented components:** `SkeletonPipeline` class (`produceFrame`, `produceFrameValidated`,
  per-frame previous/pre-previous history for dynamics rules); renderers still call pose/finalizer
  directly in legacy mode.
- **Verification performed:** `SkeletonPipelineM0Test` proves legacy-bypass path is byte-identical to
  the direct finalizer path; full suite green.
- **Final status:** **DONE** — `PIPELINE_ACTIVE=false` default; no consumer changed.

#### M1 — IK extraction (Gap 5)
- **Original objective:** delete the frame-relative `bakeIkLimb` overload; migrate its ~2 call sites to
  the world overload; `IK_WORLD_ONLY=true`; `ValidatorRomClusterTest` + `ChestFrameIssueFTest` + `*PoseTest` green.
- **Implemented components:** today only a **single world-space `bakeIkLimb`** overload exists
  (`BasePose.kt:339` and `BaseValidationPose.kt:189`) — the frame-relative overload is gone. There is
  **no `IK_WORLD_ONLY` flag** in the codebase; the world overload is the only one, so the capability the
  flag would have gated is already the sole path. (The flag was not separately introduced; the deletion
  it guarded is complete.)
- **Verification performed:** no reference to a deleted overload remains (compile-clean);
  `ValidatorRomClusterTest` + `ChestFrameIssueFTest` green.
- **Final status:** **DONE** — frame-relative overload removed; world-only IK is the sole path. (Naming
  note: the `IK_WORLD_ONLY` flag named in the gate does not exist as a symbol; its intent — world-only
  IK — is satisfied by the absence of any other overload.)

#### M2 — Pipeline owns stages (Gap 1)
- **Original objective:** `PIPELINE_ACTIVE=true`; `produceFrame` drives the full ordered chain
  (`build → ConstraintSolver.solve → finalizer.finalize → FK → optional Validator`); remove the
  Finalizer's internal `ConstraintSolver.solve` call so the pipeline is the **sole** caller; re-point
  renderers to `produceFrame`; byte-identical to pre-M2 baseline.
- **Implemented components:** `SkeletonPipeline.runStages` is the single invocation site of both Solver
  and Finalizer in fixed order (removes latent Finalizer→Solver→Finalizer re-entrancy); `SkeletonRenderer`
  + `SkeletonSnapshotRenderer` re-pointed to `produceFrame`; coherence invariant added. **Deferred from
  this M2** (explicitly): the `IkStage` tree-build extraction and the `BasePose`→`IntentBuilder` forward
  — both require the §1.1 Intent Layer live (Branch B).
- **Verification performed:** `SkeletonPipelineM0Test` confirms byte-identical to pre-M2; `ValidatorRomClusterTest`
  matches baseline; full suite green.
- **Final status:** **DONE (shipped)** — `PIPELINE_ACTIVE=true`; production frame unchanged.

#### M3 — Solver authority (Gap 3)
- **Original objective:** `SOLVER_OWNS_POSTURE=true`; ConstraintSolver seeds pelvis from `PostureIntent`
  (F2) + weights contact conflicts by `contactPrecedence` (F7) + inter-frame smoothing (F9).
- **Implemented components:** flag flip activating posture-seed / precedence / smoothing already present
  behind `SOLVER_OWNS_POSTURE`. The solver already no-ops on contact-less poses, and **no production pose
  registers an engine `ContactSpec`** (verified: the only `bakeIkLimb(... contact=)` call sites are the 4
  validation instruments), so the flip is byte-identical for all production poses.
- **Verification performed:** `ConstraintSolverPhase2Test` (seated/hanging seeds, flag-on==flag-off within
  1u, determinism, default-on); `ValidatorRomClusterTest` baseline match.
- **Final status:** **DONE (shipped)** — `SOLVER_OWNS_POSTURE=true`; production poses byte-identical.

#### M4 — Finalizer authority (Gap 4)
- **Original objective:** `FINALIZER_OWNS_CONVERSION=true`; Finalizer is exclusive local-transform writer;
  `reconstructChestFrame` F1/B5 read-only chest-frame no-move guard live.
- **Implemented components:** flag flip activating `preConvertPoles` (reserved no-op hook today) and the
  F1/B5 guard. The guard only fires for contact poses and never displaces hand/foot contacts (the chest
  reconstruction touches only the chest subtree), so it is a no-op for production.
- **Verification performed:** `FinalizerOwnsConversionM4Test` (flag-on==flag-off, maxDev 0.0, for 10
  production poses + 4 contact instruments); `ChestFrameNoMoveTest` re-pointed through the pipeline so the
  Solver actually runs and the guard sees solver-settled contacts; full suite green.
- **Final status:** **DONE (shipped)** — `FINALIZER_OWNS_CONVERSION=true`; production poses byte-identical.

> **Phase I excluded M5–M8.** M5/M6 require Branch B (Intent Layer) and were never part of the flag-flip
> track; M7 (`headTarget`) is already complete (Phase 7) and M8 is cleanup. See §3–§4.

---

## 2. Engine ownership (post-M4)

Final ownership model after M4. Each responsibility has exactly **one writer**. "Single writer" means
no other subsystem mutates that state after the owner writes it in a frame.

| Component | Owner | Single-writer responsibility | Notes |
|-----------|-------|------------------------------|-------|
| **IK (limb solving)** | `Pose.build()` via `bakeIkLimb` → `SkeletonMath.solveIK`/`solveStraightLimb` | writes intermediate/end `node.localPosition` from world targets + pole | IK currently runs **inside** `pose.build()`, not as a separate pipeline stage. Per-frame ownership of the tree still belongs to the Pose (deferred to Branch B `IkStage` extraction). |
| **ConstraintSolver** | `SkeletonPipeline.runStages` (sole caller) | resolves root/pelvis for **contact** poses only: seeds from `PostureIntent` (F2), weights conflicts by `contactPrecedence` (F7), inter-frame smoothing (F9); re-bakes contact limbs; writes `rootTranslationDelta`/`rootRotationDelta` | **No-op for contact-less poses** (all production poses) → those are untouched. Reads `pose.contacts`/`contactPrecedence`/`postureIntent`. |
| **Finalizer** (`SkeletonPoseFinalizer`) | `SkeletonPipeline.runStages` (sole caller) | **exclusive** writer of every local transform after IK: world↔local conversion (`preConvertPoles`), `toLocalDirection` bakes, extremity derivation, `reconstructChestFrame` (F1/B5 no-move guard on solver-settled contacts), FK flatten | With `FINALIZER_OWNS_CONVERSION=true` (M4) it is the sole local-transform writer; the F1/B5 guard protects contacts. `headTarget` resolver is the sole head/neck writer (Phase 7). |
| **SkeletonPipeline** | engine/consumer (long-lived, per-definition) | **owns the stage order** (`build → solve → finalize → validate`) and the per-frame pose history; sole caller of Solver + Finalizer; enforces coherence invariant | Not thread-safe (one per render loop). Owns stage *instances*, not pose geometry. |
| **Pose authoring** | `BasePose` + concrete poses | writes `SkeletonNode` transforms (local rotations/positions) + declares live §1.1 (`contacts`, `postureIntent`, `contactPrecedence`, `headTarget`) | Imperative today (Branch B migrates this to declarative intent). Must not mutate §1.2 after build. |
| **Validation** (`ExerciseValidator`) | `SkeletonPipeline.produceFrameValidated` (observer) | reads §1.2 + §1.1 ROM; writes **nothing** to the pose; emits `ValidationReport` | Strictly read-only (Gap 6 pending M6 makes every rule stamp/intent-backed). |

**Single-writer invariants established by Phase I:**
- The pipeline is the **sole** caller of both Solver and Finalizer (M2) — eliminates Finalizer→Solver
  re-entrancy.
- The Finalizer is the **sole** local-transform writer after IK (M4).
- The Solver **only** touches the root for contact poses (M3) — production frames are invariant.
- Validation is **read-only** (never mutates the pose).

---

## 3. Deferred work

| Item | Reason deferred |
|------|-----------------|
| `spineIntent` | Dead carrier — never written or read anywhere (`RFC_INTENT_BUILDER_REWRITE.md` §2). Making it live requires pose helpers to populate it and the Finalizer to consume it; that is the `IntentBuilder` rewrite, not a flag flip. |
| `limbTargets` | Dead carrier — `bakeIkLimb` writes nodes + `contacts` but never `limbTargets`. Consuming it requires an IK stage that solves a pipeline-owned tree from intent (Branch B `IkStage`), which does not exist. |
| `jointIntents` | Dead carrier — `buildHipOrientation`/`buildChest*` write node rotations directly, never `jointIntents`. Consuming it requires the Finalizer to expand relative articulations from intent. |
| `IntentBuilder` rewrite (`BasePose`→`IntentBuilder`) | The target design exists (`RFC_INTENT_LAYER.md`) but no `IntentBuilder` class is implemented; poses still emit node trees. This is a structural contract change (poses stop being tree-emitters; the pipeline owns the tree), not a toggle. Explicitly deferred out of M2 (`RFC_GAP_CLOSURE.md` M2 note). |
| Declarative pose authoring | Superset of the above: migrating every pose off node-writing helpers onto intent declaration. Requires `IntentBuilder` + IK `IkStage` + Finalizer intent consumers all landed first. |

**Why these belong to a future architecture branch, not Phase I:** Phase I was scoped to flag flips and
re-pointing over the *existing* imperative authoring model, with the invariant that production output
must stay byte-identical. The five items above each require *adding new consuming engine stages and
changing the pose authoring contract* — work that changes what a pose is and how the pipeline builds the
tree. That is a different kind of change (new architecture) than a flag flip, so it was split out as
**Branch B** (§4) rather than smuggled into Phase I.

---

## 4. Architecture split

Architecture v2 is officially divided into two branches.

### Branch A — Engine Runtime (Phase I, COMPLETE)
- **Responsibilities:** the ordered execution pipeline; explicit engine ownership of Solver (root/
  posture) and Finalizer (local-transform conversion + chest-frame guard); contact settling; FK flatten;
  read-only validation; renderer re-pointing. All delivered as M0–M4.
- **Definition of done:** `PIPELINE_ACTIVE=true`, `SOLVER_OWNS_POSTURE=true`, `FINALIZER_OWNS_CONVERSION=true`;
  single writer per responsibility; production frames byte-identical; 258/0 suite.
- **Status:** shipped. This RFC is its canonical closure reference.

### Branch B — Intent Authoring Rewrite (future)
- **Responsibilities:** migrate poses from imperative `SkeletonNode` authoring to declarative §1.1 intent
  (`IntentBuilder`); extract the IK `IkStage` that solves a pipeline-owned tree from `limbTargets`; make
  the Finalizer consume `spineIntent`/`jointIntents`/`extremityOverrides`; retire dead/obsolete carriers;
  enable M6 (validator stamp-only) on the resulting stamps.
- **Authoritative design:** `RFC_DECLARATIVE_POSE_AUTHORING.md` (problem statement, design goals, authoring
  API, engine consumption, migration strategy, runtime guarantees, risks, and the fresh B0–B6 phase plan).
- **Definition of ready:** see §8 (entry criteria). Not started.
- **Status:** not begun. Target design in `RFC_INTENT_LAYER.md`; current-state audit + required-work in
  `RFC_INTENT_BUILDER_REWRITE.md`.

**M5–M6 belong to Branch B.** M5 requires `spineIntent`/`jointIntents`/`limbTargets` to be written and
consumed — only possible after the `IntentBuilder` rewrite. M6 removes validator geometry inference in
favour of §1.2 stamps / §1.1 intents whose producers (hip-rom stamp, per-joint ROM stamps, `limbTargets`)
are created by Branch B work; M6 has no independent inputs of its own, so it rides Branch B rather than
opening a third branch. M7 (`headTarget`) is **excluded** — it is already complete (Phase 7) on Branch A.

---

## 5. Final inventory (§1.1 carriers)

Authoritative four-group audit of every §1.1 carrier on `SkeletonPose` (`PoseDefinition.kt`). "Written"
= a pose/helper assigns it (outside declaration/`copyFrom`); "Read" = an engine stage consumes it during
`produceFrame`. Source: `RFC_INTENT_BUILDER_REWRITE.md` §2 + `Section11CarriersTest`.

### Live (written by a pose/helper AND read by an engine stage today)
| Carrier | Writer | Reader | Why live |
|---------|--------|--------|----------|
| `contacts` | `bakeIkLimb` (when `contact != null`) | `ConstraintSolver.solve` | the contact-bearing validation instruments register engine contacts; solver settles root to honour them. |
| `contactPrecedence` | `declarePosture` | `ConstraintSolver.applyRootDelta` (F7) | orders contact-conflict resolution. |
| `postureIntent` | `declarePosture` | `ConstraintSolver` seed (F2) | coarse posture seed for contact poses (M3). |
| `headTarget` | `buildGaze` | `Finalizer.resolveHeadTarget` | gaze-as-target (Phase 7 complete); byte-identical to legacy. |

### Dormant (engine read-path exists, but no producer populates it → engine sees a constant)
| Carrier | Writer | Reader | Why dormant |
|---------|--------|--------|-------------|
| `extremityOverrides` | `overrideExtremityOrientation` exists but **no pose calls it** | `Finalizer.isExtremityAutomatic` (always `true`) | the consumer runs every frame but the set is never populated, so the branch that would skip derivation is unreachable from current poses. |

### Dead (never written AND never read anywhere)
| Carrier | Why dead |
|---------|----------|
| `spineIntent` | declared + `copyFrom` only; `buildSpineCurve` writes node rotations instead. No consumer. |
| `jointIntents` | declared + `copyFrom` only; `buildHipOrientation`/`buildChest*` write node rotations instead. No consumer. |
| `limbTargets` | declared + `copyFrom` only; `bakeIkLimb` writes nodes + `contacts`, never `limbTargets`. No consumer. |

### Obsolete (field exists on `SkeletonPose` but real data lives elsewhere / unused)
| Carrier | Why obsolete |
|---------|--------------|
| `motion` | real data is `PoseBuilder.metadata.motionCurve`; the `SkeletonPose.motion` field is never written or read. |
| `camera` | real data is `PoseBuilder.metadata.camera`; renderer reads `metadata.camera`, not `pose.camera`. |
| `environment` | real data is `PoseBuilder.metadata.environment`; renderer reads `metadata.environment`, not `pose.environment`. |

---

## 6. Current architecture diagram (authoritative pipeline after M4)

```
Pose.build(context)                // IK happens HERE (bakeIkLimb → SkeletonMath.solveIK)
   │  emits a fully-built SkeletonNode tree + live §1.1 (contacts/postureIntent/headTarget)
   ↓
SkeletonPipeline.produceFrame      // sole orchestrator; owns stage order + per-frame history
   │
   ↓  (Stage: ConstraintSolver.solve)   — contacts ONLY; NO-OP for contact-less production poses
   │     seeds root from postureIntent (F2), weights by contactPrecedence (F7),
   │     inter-frame smoothing (F9), re-bakes contact limbs, writes rootTranslation/RotationDelta
   ↓
SkeletonPoseFinalizer.finalize     // exclusive local-transform writer (M4)
   │     preConvertPoles (reserved hook) · toLocalDirection bakes · extremity derivation ·
   │     reconstructChestFrame (F1/B5 no-move guard on solver-settled contacts) · FK flatten
   ↓
SkeletonPose (§1.2 world state)    // single source of truth for rendering + validation
   │
   ↓  (optional, validated entry point only) ExerciseValidator.validate  — READ-ONLY
        emits ValidationReport (writes nothing to the pose)
```

### Stage descriptions
- **Pose.build** — authoritative authoring stage. Constructs the `SkeletonNode` tree and runs IK
  (`bakeIkLimb` → `SkeletonMath.solveIK`/`solveStraightLimb`) to place limb joints from world targets;
  declares the live §1.1 carriers. Imperative today (Branch B migrates this to intent).
- **SkeletonPipeline** — the orchestrator. Runs the ordered stages, owns the previous/pre-previous pose
  history for dynamics rules, and is the **sole caller** of the Solver and Finalizer (M2). Enforces the
  coherence invariant (`FINALIZER_OWNS_CONVERSION` requires `PIPELINE_ACTIVE`).
- **ConstraintSolver** — contact-only root/posture settlement (M3). Skipped entirely for contact-less
  poses, which is every production pose, so production output is invariant under M3.
- **Finalizer** — exclusive local-transform writer (M4): world↔local conversion, extremity derivation,
  chest-frame reconstruction with the F1/B5 read-only guard on solver-settled contacts, and FK flatten.
- **FK flatten** — produces the §1.2 world `joints`/`rotations` from the final local transforms; the
  single source of truth for rendering and validation.
- **Validation** — observer only (Gap 6 pending M6). Reads §1.2 + §1.1 ROM; emits a `ValidationReport`;
  never mutates the pose.

> **Note on the user-supplied diagram:** it lists `IK` as a stage *between* Pose and Pipeline. In the
> shipped code IK is **inside** `Pose.build()`, not a separate pipeline stage. The `IkStage` extraction
> that would make IK a first-class pipeline stage is deferred to Branch B (`RFC_GAP_CLOSURE.md` M2 note).
> The diagram above reflects the *actual* authoritative pipeline after M4.

---

## 7. Lessons learned

- **Dormant flags are different from deferred architecture.** A dormant flag (e.g. a feature behind a
  `false` default that flips on with no behavior change) is cheap and reversible. A deferred *architecture*
  (the `IntentBuilder` rewrite) is not a flag at all — its consuming stages and intent-populating helpers
  do not exist. Treating "M5 is automatic once M2 lands" as a flag flip was the core error: M2 only
  re-pointed the pipeline over the existing imperative authoring; it did not create the intent consumers.
  Distinguishing the two prevents fake-closing a milestone.
- **Production stabilization preceded migration.** M0–M4 were landed only after the engine was stabilized
  and the legacy engine remediated (S0–S3, R1–R4), with a green/byte-identical baseline. Migrating pose
  authoring onto an unstable engine would have hidden regressions behind intent-plumbing churn. The
  "byte-identical" invariant (every flag flip must not change any production pose) is what made each Phase
  I milestone safe to ship.
- **Hidden compile failures distorted previous baselines.** Four test files (`ConstraintSolverTest`,
  `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`) had compile errors that suppressed the
  entire `:app` test module, so 0 tests ran and 31 real engine failures stayed invisible. The
  compile-first policy (a branch must never knowingly stay non-compiling) exists because a red build hides
  runtime/validation failures and gives invalid feedback — exactly the distortion that masked defects
  before the stabilization pass.
- **Honest architectural audits prevented false milestone completion.** When the M5 premise ("automatic")
  was checked against the source rather than trusted, the three intent carriers were shown dead
  (`Section11CarriersTest` pins it). Marking M5 `[DONE]` by "documenting them as consumed" would have been
  instrument tampering — a validation pose retuned to read green. Recording M5 as BLOCKED and writing
  `RFC_INTENT_BUILDER_REWRITE.md` kept the milestone honest and made the Branch B scope explicit instead.

---

## 8. Entry criteria for Phase II (Intent Authoring Rewrite / Branch B)

Readiness conditions that must hold **before** Branch B implementation begins. No implementation is
proposed here — only the gate.

1. **Phase I closed and stable:** M0–M4 shipped, `PIPELINE_ACTIVE`/`SOLVER_OWNS_POSTURE`/
   `FINALIZER_OWNS_CONVERSION` all `true`, production frames byte-identical, full suite green (current
   258/0) with no known engine defects. Branch A is the frozen baseline Branch B builds on.
2. **Single audit source agrees:** `RFC_INTENT_BUILDER_REWRITE.md` (current state) and `RFC_INTENT_LAYER.md`
   (target) are reconciled; the carrier inventory (live/dormant/dead/obsolete) is accepted as the starting
   inventory and the target ownership model is accepted as the end state.
3. **No producerless consumers / no consumerless producers assumed:** the migration order in
   `RFC_INTENT_BUILDER_REWRITE.md` §3.4 is adopted as the sequence — `IntentBuilder` type exists before any
   helper is re-pointed; the `IkStage` exists before poses stop emitting trees; Finalizer intent consumers
   exist before poses stop writing the corresponding node rotations.
4. **Byte-identical migration contract agreed:** every per-pose migration step must keep the rendered frame
   identical (the M2–M4 invariant extended to Branch B) — i.e. the pipeline can render intent-authored
   poses to the same §1.2 as the equivalent node-authored pose.
5. **Dead/obsolete carriers retired, not left:** the plan removes `spineIntent`/`jointIntents`/`limbTargets`
   only after they are live, and removes the obsolete `SkeletonPose.motion`/`camera`/`environment` fields
   (data already on `PoseBuilder.metadata`) as part of cleanup, not left as lingering empty fields.
6. **Validation readiness:** the §1.2 stamps M6 needs (hip-rom, per-joint ROM, `limbTargets`-derived
   symmetry deltas) are specified as outputs of the Branch B IK/Finalizer stages before M6 work starts, so
   M6 can be landed as a pure consumer once they exist.
7. **CI gate for regression:** `Section11CarriersTest` (dead-carrier pin) is extended to assert, per
   migrated carrier, that it is now *written by a pose and read by an engine stage* — flipping the dead
   assert into a live one as each carrier comes online, preventing a future silent "M5 done" without
   plumbing.

> Branch B does **not** begin until criteria 1–2 are met (1 is met today; 2 is met by this RFC's companion
> docs). Criteria 3–7 are the standing contract for the work itself.
