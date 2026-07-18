# RFC — Architecture v2 MonkEngine pipeline / Orchestrator

**Status:** Draft specification (no code).
**Author:** Principal Engine Architect.
**Addresses:** Capability Gap Report Gap 1 (MonkEngine pipeline / orchestrator, spec `ARCHITECTURE_V2.md` §3).
**Scope:** Design the missing Pose→Intent→IK→Solver→Finalizer→FK→Validator pipeline and the
orchestration object that owns it. Reconciliation only — no redesign of the motion/pose math,
no new anatomical behavior. The frozen constitution (`ARCHITECTURE_FREEZE.md`) is不变 (unchanged).

---

## 0. Problem statement (from the audit)

Today the boundary defined by the spec does not exist at runtime:

- `Pose.build(context)` is the de-facto pipeline. It constructs the `SkeletonNode` tree by hand,
  calls `bakeIkLimb` (which writes `middleNode.localPosition`/`endNode.localPosition` into the
  **live node tree**), and returns a `SkeletonPose` whose `roots` already carry a full geometry.
- `ConstraintSolver.solve` mutates that same live tree (it reads `pose.roots`, rewrites
  `pelvis.localPosition`/`.localRotation`).
- `SkeletonPoseFinalizer.finalize` snapshots + FK-flattens the tree into `outputPose` and applies
  a *fallback* chest-frame reconstruction — it is a **renderer-adjacent compatibility layer**, not
  a pipeline stage.
- `SkeletonEngine` is only a bone-rendering lookup table (`val bones = listOf(...)`); it has no
  execution logic.
- The §1.1 intent carriers (`spineIntent`, `limbTargets`, `jointIntents`, and even `postureIntent`
  outside validation poses) are never populated by production poses and never consumed by the
  engine.

**Required target (spec §3):**
```
Pose ──build()──▶ Intent (§1.1) ──▶ IK ──▶ ConstraintSolver ──▶ SkeletonPoseFinalizer ──▶ FK ──▶ SkeletonPose (§1.2) ──▶ Validator
```
The Pose must stop being a geometry producer. It must become an **intent author**. the MonkEngine runtime must
become the **sole geometry resolver**.

---

## 1. Exact execution order (who calls whom, ownership, I/O per stage)

The pipeline is a **linear, single-owner-per-stage DAG**. One orchestrator drives it. Stages pass a
single mutable `SkeletonPose` (the carrier) forward; each stage owns a disjoint slice of that
carrier's lifecycle.

| # | Stage | Caller | Owns (writes) | Reads | In | Out |
|---|-------|--------|---------------|-------|----|-----|
| 0 | **Pose** | orchestrator | §1.1 intent fields | exercise spec, `PoseContext` | `PoseContext` | `SkeletonPose` (§1.1 only; `roots` = empty/unbuilt) |
| 1 | **Intent normalization** | orchestrator | `jointIntents` canonical form, `spineIntent` from helper calls, `limbTargets` registry | §1.1 as authored | `SkeletonPose` (§1.1) | `SkeletonPose` (normalized §1.1) |
| 2 | **IK** (world-only) | orchestrator | `limbTargets`→joint `localPosition` on a *fresh* node tree; stamps `maxIkClampAmount`, `straightIntentDropped`, `boneLengthsVerified` | §1.1 `limbTargets`, `jointIntents`, `extremityOverrides` | `SkeletonPose`+`SkeletonDefinition` | node tree with limb joints solved; stamps |
| 3 | **ConstraintSolver** | orchestrator | root/pelvis transform; `rootTranslationDelta`/`rootRotationDelta`; re-bakes contact limbs; secondary stamps | §1.1 `contacts`, `contactPrecedence`, `postureIntent`, `extremityOverrides` | `SkeletonPose` (post-IK) | `SkeletonPose` (posture-settled) |
| 4 | **SkeletonPoseFinalizer** | orchestrator | `nodes` (§1.2): world↔local conversion, extremity derivation, `reconstructChestFrame`, flatten | solver-settled tree (must not move contacts) | `SkeletonPose` | `SkeletonPose` with §1.2 `nodes` fully written |
| 5 | **FK + Flatten** (terminal) | orchestrator (or finalizer terminal step) | propagates world transforms; `SkeletonPose.fromHierarchy` | §1.2 local transforms | `SkeletonPose` | `SkeletonPose` with final world + `isTransformsUpdated` |
| 6 | **Validator** (observer) | orchestrator (or consumer) | **nothing** (`SkeletonPose` is read-only here) | §1.2 + §1.1 ROM | `SkeletonPose` | `ValidationReport` (no mutation) |
| 7 | **Render** | consumer | nothing | finalized `SkeletonPose` | `SkeletonPose` | pixels |

**Invariant enforced by ordering:** Pose→IK boundary carries intent, no geometry. IK→Solver boundary
carries world-solved limbs, root not yet posture-settled. Solver→Finalizer carries a *final* root +
contacts; Finalizer must not undo them (F1). Finalizer→FK carries all local transforms; FK only
propagates. FK→Validator carries complete state; Validator is read-only (F-observer).

---

## 2. Class fate matrix

| Class | Fate | Rationale |
|-------|------|-----------|
| `PoseBuilder` (interface) | **Survives** | The Pose contract (`build(context): SkeletonPose`). Its `build()` must be redefined to emit intent only (returns a `SkeletonPose` whose `roots` is empty / a skeleton-definition-only stub, with §1.1 populated). Signature unchanged → zero caller churn. |
| `PoseContext` | **Survives** | Already correct (progress/side/def). No change. |
| `SkeletonPose` | **Survives, gains role clarity** | Already the single carrier (§1.1 + §1.2). The §1.1 fields stop being dead. `roots` becomes *owned by the pipeline*, not the pose. |
| `SkeletonDefinition` | **Survives** | Proportions input. Unchanged. |
| `SkeletonMath` | **Survives (facade of pure functions)** | `solveIK`, `deriveDefaultPole`, `bonesExact`, `toWorldDirection`/`toLocalDirection`, `buildHipRotation` — all stateless. Becomes the **IK + math facade** the IK stage calls. No ownership of state. |
| `BasePose` | **Becomes a facade/builder base** | Today it *does* the geometry (creates `SkeletonFactory` tree, calls `bakeIkLimb`, writes nodes). After migration it only (a) populates §1.1 via the existing intent helpers (`buildSpineCurve`, `buildHipFlexion`, `buildShoulders`, `bakeIkLimb` becomes an *intent declaration* that records a `limbTarget` rather than writing a node), and (b) provides the helpers. The node-tree construction moves OUT of `BasePose` into the pipeline's IK stage. |
| `SkeletonFactory` | **Survives, relocates** | Currently called *inside* `Pose.build()` (`ensureHierarchy`). Moves to the IK stage: the pipeline creates the canonical skeleton tree once per frame and hands node refs to IK. No logic change. |
| `bakeIkLimb` (in `BasePose`) | **Splits into two** | (a) `declareLimbTarget(...)` — records a `WorldTarget` into `§1.1.limbTargets` (pure intent, no node write). (b) The actual IK solve moves into the IK stage (`SkeletonMath.solveIK` + stamp writes), operating on the pipeline-owned tree. The legacy overload (frame-relative) is deleted (Gap 5). |
| `ConstraintSolver` (`object`) | **Survives, becomes a true stage** | Already implements seeding/CCD/smoothing. Today it is *optionally* called from the finalizer for contact poses. Becomes a mandatory, ordered stage called by the orchestrator. `SOLVER_OWNS_POSTURE` becomes the *default-on* posture authority; `declarePosture` becomes mandatory for contact/posed poses. |
| `SkeletonPoseFinalizer` | **Survives, becomes a true stage** | Already has `finalize()` (snapshot + FK flatten + chest-frame reconstruction + `preConvertPoles` hook). Becomes the exclusive §1.2 writer. `FINALIZER_OWNS_CONVERSION` becomes default-on. The renderer stops calling it directly; the orchestrator does. |
| `SkeletonEngine` | **Survives, but is demoted/re-purposed** | It is currently a *bone-drawing table* with no execution role. **It does NOT become the orchestrator** (see §3). It remains the render-side bone list (`bones`) used by `SkeletonProjector`. Its name is retained only for rendering; it must not gain pipeline logic. |
| `ExerciseValidator` | **Survives, cleanup (Gap 6)** | Already reads §1.2 stamps. Must drop `toLocalDirection`/`angleBetweenDegrees`/`atan2` geometry inference (Phase 8). Becomes the terminal observer stage. |
| `SkeletonRenderer` / `SkeletonSnapshotRenderer` | **Survive, slimmed** | Stop calling `finalizer.finalize(pose)` themselves. Instead they receive an **already-finalized** `SkeletonPose` from the orchestrator (or call the orchestrator and then project). The finalizer instance moves into the pipeline. |
| `ValidationPoseLauncher` | **Survives, re-pointed** | Its comment already says `PoseBuilder -> ... -> SkeletonPoseFinalizer (inside SkeletonRenderer)`. It is re-pointed to call the orchestrator's `produceFrame()` instead of `build()`+implicit finalize. |
| **NEW: `SkeletonPipeline`** (the orchestrator) | **Created** | See §3. |

**Disappears:** the *implicit* pipeline inside `Pose.build()`. No standalone class is deleted except
the deprecated frame-relative `bakeIkLimb` overload (Gap 5). `SkeletonEngine` is **not** deleted —
it is demoted to a render-resource holder and must lose any temptation to own execution.

---

## 3. The orchestration object — `SkeletonPipeline`

### Name selection (evaluation of candidates)

- **`SkeletonEngine`** — **rejected as orchestrator.** It already exists as a bone-rendering table
  and is referenced by `SkeletonRenderer`/`SkeletonProjector`. Promoting it to pipeline owner would
  collide with its render role and drag render concerns into the execution path. Name also implies
  "the whole engine" when it is only bones. Keep it render-only.
- **`SkeletonExecutor`** — viable but connotes "runs a task once" (async/command semantics). The
  pipeline is a *deterministic per-frame transform*, not a command executor. Slightly misleading.
- **`PoseRuntime`** — implies ownership of pose *state across time* (animation loop, playback). The
  pipeline is stateless per frame except the solver's inter-frame cache (F9), which is an internal
  detail. `PoseRuntime` over-implies a long-lived session object. Rejected.
- **`SkeletonPipeline`** — **chosen.** It names exactly what it is: the ordered stage chain
  (spec §3). It owns no anatomy, no rendering, no animation state. It is the composition root for
  the six stages. It is the natural home for the feature flags that flip each stage from
  legacy-bypass to architecture-v2-active.

### Responsibility of `SkeletonPipeline`

- Owns the **stage instances** (`SkeletonMath` facade is stateless, so it holds `ConstraintSolver`
  ref + one `SkeletonPoseFinalizer` instance + one `SkeletonFactory` invocation per frame + the
  `ExerciseValidator` ref).
- Owns the **per-frame contract**: given `(PoseBuilder, PoseContext, SkeletonDefinition)` → returns
  a finalized, validated `SkeletonPose` (+ optional `ValidationReport`).
- Owns the **feature flags** (`EngineFlags`): each stage can be toggled legacy-bypass vs active.
  This is what makes migration incremental (§6).
- Owns **ordering and re-entry** (F1): if the Finalizer cannot honor a chest-frame without moving a
  settled contact, it signals the pipeline, which triggers a **bounded Solver re-pass** (not silent
  Finalizer mutation).
- Forbidden from: computing geometry itself, holding render state, holding animation/playback state.

### Ownership, lifetime, and thread-safety of `SkeletonPipeline` (Issue 5)

**Instantiation scope — one instance per *character* (per `SkeletonDefinition`), not a global
singleton, not per-pose, not per-animation.** Rationale:
- *Not a singleton:* the pipeline holds stage instances (`ConstraintSolver` ref, one
  `SkeletonPoseFinalizer`, the `ExerciseValidator` ref) and — critically — the Solver's
  inter-frame smoothing cache (`lastSolvedRoot`, keyed by `SkeletonPose` identity). Two characters
  animated concurrently must not share smoothing state, so a process-wide singleton is wrong.
- *Not per-pose and not per-frame:* poses are pluggable inputs; creating a pipeline per `produceFrame`
  would rebuild stage instances every frame (wasteful) and would break the smoothing cache
  (keyed by pose identity, not by pipeline). The pipeline outlives individual pose evaluations.
- *Per character:* a `SkeletonDefinition` identifies one character's skeleton; the pipeline +
  its stages + its smoothing cache are bound to that skeleton for the character's lifetime.
  Multiple characters → multiple pipeline instances, one each.

**Thread-safety:** the pipeline is **not internally synchronized**; it is safe under a *single-thread-
per-character* contract — the same thread that owns a character drives that character's pipeline.
Cross-character parallelism is achieved by giving each character its own pipeline on its own thread
(no shared mutable stage state between pipelines). The only shared mutable state is `EngineFlags`
(flags), which are read-only at frame time (set at construction / startup), so they require no
locking. This matches the existing realtime animation model (one render/update loop per character or
a coordinated scheduler), and introduces no new concurrency primitive.

### Public surface (signatures only — §5 covers fully)

```
class SkeletonPipeline(
    definition: SkeletonDefinition,
    flags: EngineFlags = EngineFlags.DEFAULT,
    validator: ExerciseValidator? = null   // null = skip validation stage
) {
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult
    fun produceFrameValidated(pose: PoseBuilder, context: PoseContext, camera: Camera, env: EnvironmentDefinition): ValidatedFrame
}
data class PipelineResult(val pose: SkeletonPose, val report: ValidationReport?)
data class ValidatedFrame(val pose: SkeletonPose, val report: ValidationReport)
```

---

## 4. Full lifecycle of one frame (transition-by-transition)

```
[Consumer: workout loop / ValidationPoseViewer / snapshot generator]
   │  holds PoseBuilder + PoseContext(progress, side, definition)
   ▼
(1) Consumer ──calls──▶ SkeletonPipeline.produceFrame(pose, context)
   │
   ▼
(2) Pipeline ──invokes──▶ Pose.build(context)
   │   Pose writes ONLY §1.1 (intent). It does NOT construct SkeletonNode tree.
   │   It MAY call helpers that *record* intent:
   │     buildSpineCurve(lower, chest, lr, tr)  → sets pose.spineIntent
   │     buildHipFlexion(hip, f)                → appends pose.jointIntents
   │     declareLimbTarget(joint, worldPos)      → appends pose.limbTargets   (was bakeIkLimb)
   │     declarePosture(kind)                   → sets pose.postureIntent
   │     declareContacts(list)                  → sets pose.contacts + contactPrecedence
   │   Returns SkeletonPose{ §1.1 populated, roots = [] }
   ▼
(3) Pipeline ──invokes──▶ IntentNormalization (internal stage)
   │   Canonicalizes: resolves spineIntent→jointIntents if needed, validates extremityOverrides,
   │   ensures limbTargets reference valid Joints. No geometry.
   │   Out: SkeletonPose{ §1.1 normalized }
   ▼
(4) Pipeline ──invokes──▶ IK stage
   │   Pipeline first calls SkeletonFactory.createStandardSkeleton() → fresh node tree (roots).
   │   For each limbTarget: SkeletonMath.solveIK(worldRoot, worldTarget, L1, L2, worldPole, constraint)
   │     → writes middle/end node localPosition (in pipeline-owned tree, NOT pose-owned)
   │     → ANDs boneLengthsVerified, ORs maxIkClampAmount, sets straightIntentDropped
   │   For non-target joints: applies jointIntents (chest/hip/girdle/ankle/wrist relative rotations)
   │     via buildChestOrientation/buildHipOrientation/buildClavicularRotation etc. (already pure helpers)
   │   Out: SkeletonPose{ §1.1 + roots(tree with limbs solved), §1.2 stamps }
   ▼
(5) Pipeline ──invokes──▶ ConstraintSolver.solve(pose, definition)
   │   If pose.contacts empty → stage is a no-op (legacy-equivalent for non-contact poses).
   │   Else: seed root from postureIntent (F2), apply contactPrecedence (F7), CCD (UNI-1),
   │     inter-frame smoothing (F9), re-bake contact limbs, write rootTranslation/RotationDelta.
   │   Mutates the SAME pipeline-owned tree's pelvis + contact limbs.
   │   Out: SkeletonPose{ posture-settled tree, deltas stamped }
   ▼
(6) Pipeline ──invokes──▶ SkeletonPoseFinalizer.finalize(pose)
   │   preConvertPoles() (now active: owns all world↔local conversion).
   │   derive extremities unless extremityOverrides (W1).
   │   resolve relative rotations (tilt cancel).
   │   reconstructChestFrame() — GUARANTEE: snapshots Solver-settled contacts; if reconstruction
   │     would move a contact → roll back + flag rootTranslationDelta (F1/B5).
   │   flatten to nodes (§1.2).
   │   Out: SkeletonPose{ §1.2 nodes fully written, isTransformsUpdated = true }
   ▼
(7) Pipeline ──invokes──▶ FK + Flatten (terminal)  [may be folded into finalizer's last step]
   │   roots.updateWorldTransforms(); SkeletonPose.fromHierarchy(roots, jointsBuffer)
   │   Out: SkeletonPose{ final world transforms in §1.2 }
   ▼
(8) Pipeline ──invokes──▶ ExerciseValidator.validate(pose, camera, env)   [if validator != null]
   │   Reads §1.2 + §1.1 ROM only. Writes NOTHING to pose.
   │   Out: ValidationReport (issues list)
   ▼
(9) Pipeline ──returns──▶ PipelineResult(pose, report?) to Consumer
   │
   ▼
(10) Consumer ──passes finalized pose to──▶ SkeletonRenderer(pose, camera, engine, ...) → projector → pixels
```

**Re-entry (F1) drawn explicitly:**
```
(6a) Finalizer detects chest-frame would displace a Solver-settled contact
   │  sets a signal flag on pose (e.g. pose.rootTranslationDelta bumped / a PipelineSignal)
   ▼
(6b) Pipeline ──bounded re-invoke──▶ ConstraintSolver.solve(pose) (re-pass, max N iters)
   │  Solver re-settles root so the contact holds; Finalizer re-runs chest-frame on the new tree.
   ▼
(6c) If still conflicting after N → keep Solver result, Validation flags the residual (no silent drop).
```

---

## 5. Every public API (signatures, responsibility, ownership)

> No implementation. Signatures reflect intent. `SkeletonPose`, `PoseContext`, `SkeletonDefinition`,
> `Joint`, `Vector3`, `JointRotation`, `WorldTarget`, `ContactSpec`, `PostureIntent`,
> `Extremity`, `EngineFlags`, `ValidationReport`, `Camera`, `EnvironmentDefinition` already exist.

### 5.1 Orchestrator
```
class SkeletonPipeline(
    definition: SkeletonDefinition,
    flags: EngineFlags = EngineFlags.ARCHITECTURE_V2,   // default = all stages active
    validator: ExerciseValidator? = null
) {
    /** Single entry. Runs stages 0..7 in order. Returns finalized pose (+report if validator set). */
    fun produceFrame(pose: PoseBuilder, context: PoseContext): PipelineResult

    /** produceFrame + mandatory validation stage. */
    fun produceFrameValidated(
        pose: PoseBuilder, context: PoseContext,
        camera: Camera, env: EnvironmentDefinition
    ): ValidatedFrame

    /** Exposes the IK stage for unit testing / debug replay. */
    fun runIkOnly(pose: SkeletonPose): SkeletonPose

    /** Exposes the Solver stage for testing. */
    fun runSolverOnly(pose: SkeletonPose): SkeletonPose
}

data class PipelineResult(val pose: SkeletonPose, val report: ValidationReport?)
data class ValidatedFrame(val pose: SkeletonPose, val report: ValidationReport)
```

**Ownership:** `SkeletonPipeline` owns stage *instances* and *ordering*. It owns the per-frame
`SkeletonFactory` tree allocation (or a pooled tree). It does not own anatomy or render.

### 5.2 Pose contract (intent author — changed contract, same signature)
```
interface PoseBuilder {
    val metadata: PoseMetadata
    /** MUST populate §1.1 only. MUST NOT construct SkeletonNode tree or call IK. */
    fun build(context: PoseContext): SkeletonPose
}
```
New intent-declaration helpers on `BasePose` (replace geometry-producing ones):
```
protected fun declareLimbTarget(joint: Joint, worldTarget: Vector3, constraint: IKConstraint, contact: ContactConstraint? = null)
protected fun declarePosture(kind: PostureIntent.Kind, tolerance: Float = 0f)
protected fun declareContacts(specs: List<ContactSpec>, precedence: List<ContactId> = emptyList())
// buildSpineCurve / buildHipFlexion / buildHipOrientation / buildShoulders / buildChestOrientation
//    become §1.1 recorders (set spineIntent / jointIntents / limbTargets) — they already take
//    the same args; only their body changes from "write node" to "record intent".
```
**Ownership:** Pose owns §1.1 authorship. Pose owns **zero** of §1.2.

### 5.3 IK stage (facade over `SkeletonMath`, operates on pipeline-owned tree)
```
object IkStage {
    /** Solves every limbTarget into the pipeline-owned node tree; writes IK stamps. */
    fun solve(pose: SkeletonPose, tree: SkeletonHierarchy, def: SkeletonDefinition)
    /** Resolves non-target relative articulations (chest/hip/girdle/ankle/wrist) from jointIntents. */
    fun applyJointIntents(pose: SkeletonPose, tree: SkeletonHierarchy, def: SkeletonDefinition)
}
```
**Ownership:** IK owns world-space 2-bone solve, straight fallback, bone-length invariant, default
pole (F4/F5/F6). Writes `maxIkClampAmount`, `straightIntentDropped`, `boneLengthsVerified` (primary).
Must NOT translate root, honor contacts, cancel inherited tilt.

### 5.4 ConstraintSolver (stage — already exists, promoted)
```
object ConstraintSolver {
    fun solve(pose: SkeletonPose, definition: SkeletonDefinition)   // unchanged signature
    fun chainForEnd(end: Joint): ContactChain?
    // private: seedRootFromPostureIntent, postureSeedY, solvePosture (CCD), applyRootDelta
}
```
**Ownership:** sole mover of root/pelvis transform; contact honor; posture CCD; inter-frame
smoothing (F9); secondary stamp writer (`rootTranslationDelta`, `rootRotationDelta`,
`boneLengthsVerified` re-AND). Must NOT invent contacts or wipe deliberate lean.

### 5.5 SkeletonPoseFinalizer (stage — already exists, promoted)
```
class SkeletonPoseFinalizer(definition: SkeletonDefinition) {
    fun finalize(pose: SkeletonPose): SkeletonPose   // unchanged signature; now mandatory stage
    // private: preConvertPoles (active), reconstructChestFrame (F1/B5 no-move guard), extremity derivation
}
```
**Ownership:** exclusive writer of §1.2 `nodes`; world↔local conversion; extremity derivation
(unless override); relative tilt cancel; chest-frame reconstruction (read-only on settled); flatten.
Must NOT move Solver-settled contact end-effector (F1); must NOT translate root; must NOT accept
local-frame pole.

### 5.6 Validator (observer — already exists, cleaned up)
```
class ExerciseValidator {
    fun validate(pose: SkeletonPose, camera: Camera, env: EnvironmentDefinition): ValidationReport
    // Phase 8 (Gap 6): remove toLocalDirection/angleBetweenDegrees/atan2 geometry inference;
    // read stamps (rootTranslationDelta, maxIkClampAmount, boneLengthsVerified, straightIntentDropped) + §1.1 ROM.
}
```
**Ownership:** rule checks only. Writes **nothing** to `SkeletonPose`.

### 5.7 Feature flags (`EngineFlags`)
```
object EngineFlags {
    var PIPELINE_ACTIVE: Boolean            // master switch: false = legacy Pose.build()+finalizer path
    var SOLVER_OWNS_POSTURE: Boolean        // default TRUE under ARCHITECTURE_V2
    var FINALIZER_OWNS_CONVERSION: Boolean  // default TRUE under ARCHITECTURE_V2
    var IK_WORLD_ONLY: Boolean              // default TRUE; rejects frame-relative pole
    var VALIDATOR_STAMP_ONLY: Boolean       // default TRUE; rejects geometry inference (Gap 6)
    val ARCHITECTURE_V2 = EngineFlags(true, true, true, true, true)
    val LEGACY = EngineFlags(false, false, false, false, false)
}
```

---

## 6. Migration strategy

**Principle:** every stage has a legacy-bypass flag. A pose can be migrated independently. The
pipeline runs the same code paths as today when all flags are `LEGACY`; flipping a flag activates
the architecture-v2 behavior for that stage only. No big-bang rewrite.

### Phase M0 — Scaffold orchestrator (no behavior change) **[SHIPPED; superseded by M2]**
- Add `SkeletonPipeline` with `PIPELINE_ACTIVE = false`.
- `produceFrame` in legacy mode = exactly today: `pose.build()` (which still builds the tree) →
  `finalizer.finalize()` → optional validator. All consumers still call `pose.build()` directly;
  pipeline is unused. **Zero regression.**
- **M2 has since flipped `PIPELINE_ACTIVE` to `true`** and re-pointed the renderers; the legacy
  `false` branch remains reachable as a rollback path.

### Phase M1 — Remove deprecated `bakeIkLimb` overload (Gap 5, F4)
- **Scope (authoritative — see Issue 1 resolution):** M1 does ONLY one thing: delete the
  frame-relative `bakeIkLimb(rootWorldPos, targetWorldPos, L1, L2, parentRotation, poleLocal, …)`
  overload and migrate its **exactly 2** call sites (verified: `BaseLungePose.kt`,
  `BaseThoracicPose.kt`) to the world overload. The caller already computes the proximal parent's
  world rotation to derive `worldPole`, so it passes the pre-computed `worldPole` directly — a
  mechanical 1:1 replacement.
- Set `IK_WORLD_ONLY = true`. With the frame-relative overload gone, `preConvertPoles` (M4) owns
  100% of world↔local conversion.
- **`declareLimbTarget` is NOT introduced in M1.** It is introduced in M2 (Pose intent-only) as the
  intent-recording replacement for the geometry-writing `bakeIkLimb`. M1 is strictly the overload
  deletion; M2 is the intent migration. This ordering is correct because: (a) M1 is a pure
  mechanical refactor with zero behavioral change and zero new API, so it is trivially revertible
  and independently testable; (b) `declareLimbTarget` depends on the `IntentBuilder` + the pipeline
  consuming §1.1, both of which only exist after M0/M2 land — introducing it in M1 would create a
  dangling API with no consumer; (c) keeping M1 narrow keeps the rollback surface minimal (flip
  `IK_WORLD_ONLY`, restore one method).
- **Coexist:** legacy poses (still calling the world `bakeIkLimb`) work because `PIPELINE_ACTIVE=false`.

### Phase M2 — Pipeline owns the stage chain (the ordering cut) **[SHIPPED — narrowed scope]**
- **Shipped:** `PIPELINE_ACTIVE = true`. `produceFrame` now drives the ordered chain
  `pose.build()` → `ConstraintSolver.solve` (contacts only) → `finalizer.finalize` → FK → validator.
  The Finalizer's internal `ConstraintSolver.solve` call was **removed** (§8.1) so the pipeline is
  the **sole** caller of both, killing the Finalizer→Solver re-entrancy. `SkeletonRenderer` and
  `SkeletonSnapshotRenderer` were re-pointed to `produceFrame` (a built-pose overload serves the
  renderer path); they no longer own a `SkeletonPoseFinalizer`. Output is byte-identical to the
  pre-M2 baseline (`SkeletonPipelineM0Test`, full suite 250/0).
- **Deferred to a follow-up (the RFC's "Pose intent-only" prose below):** `BasePose.build()` still
  constructs the node tree; the `BasePose`→`IntentBuilder` forward + moving the tree build into
  `IkStage` require the §1.1 Intent Layer to be live (Phase M5) and are out of scope for the flip.
  The substantive M2 deliverable — single Solver/Finalizer owner, no re-entrancy — is what makes M3
  and M4 safe to land next.

<!-- Original design intent (superset, deferred):
- `PIPELINE_ACTIVE = true`. `produceFrame` now: `pose.build()` (intent only) → `IntentNormalization`
  → `IkStage.solve` (builds the tree, solves) → `ConstraintSolver.solve` → `finalizer.finalize` →
  FK → validator.
- `BasePose.build()` is refactored so its helpers record intent instead of writing nodes. A pose
  that still calls a tree-writing helper is caught at compile time (helper removed) — migration is
  mechanical, pose-by-pose.
- **Adapter needed:** `LegacyPoseAdapter` — wraps an old `PoseBuilder` whose `build()` still returns
  a full tree; the adapter extracts §1.1-equivalent from the tree (or the pose is simply migrated).
  Prefer direct migration; adapter is the fallback for the long tail.
-->

### Phase M3 — Activate Solver authority (Gap 3) **[SHIPPED]**
- **Shipped:** `SOLVER_OWNS_POSTURE = true`. The `ConstraintSolver` now seeds the pelvis from the
  pose's `PostureIntent` (F2), weights contact conflicts by `contactPrecedence` (F7), and applies
  inter-frame smoothing (F9) — all code that already existed behind the flag. Pure flag flip + test
  coverage.
- **Byte-identical for production:** the whole solver no-ops on contact-less poses, and **no
  production pose registers an engine `ContactSpec`** (none call `bakeIkLimb(... contact=)`), so every
  production pose is unchanged. Only the diagnostic validation instruments that register engine
  contacts are exercised: `DeepOverheadSquatPose`/`DeadHangPose` (declare posture → seed active),
  `MiddleSplitPose`/`PikeSitPose` (ground contacts, `CUSTOM` posture → seed skipped).
- `ConstraintSolverPhase2Test` re-pointed through `SkeletonPipeline` (post-M2 the Finalizer no longer
  calls the Solver, so a direct `finalize()` would skip the posture solve). Proves seated/hanging
  seeds, flag-on == flag-off within 1u, and determinism. Full suite green (251/0).

<!-- Original design intent (superset — "production poses migrated to declarePosture" is a no-op
     today since no production pose registers an engine contact; applies once the Pose intent-only
     follow-up introduces engine contacts):
- `SOLVER_OWNS_POSTURE = true`. Production poses migrated to `declarePosture(...)`. Contact poses
  get correct root-from-intent; non-contact poses unchanged (Solver no-ops on empty contacts).
- Validation poses already declare posture → unaffected.
-->

### Phase M4 — Activate Finalizer authority (Gap 4) **[SHIPPED]**
- **Shipped:** `FINALIZER_OWNS_CONVERSION = true`. The Finalizer is now the exclusive writer of every
  local transform; `preConvertPoles` (reserved no-op hook) and the `reconstructChestFrame` F1/B5
  read-only chest-frame guard are live. Pure flag flip + test coverage.
- **Byte-identical for production:** the guard only activates for poses that registered an engine
  `ContactSpec` (the 4 validation instruments); the chest reconstruction touches only the chest
  subtree (shoulders/arms/neck/head), so it never displaces a Solver-settled hand/foot contact and the
  guard is a no-op. Every production pose registers zero engine contacts, so the entire production set
  is unchanged. `FinalizerOwnsConversionM4Test` proves flag-on == flag-off (maxDev 0.0);
  `ChestFrameNoMoveTest` (re-pointed through the pipeline) confirms the guard holds for all contacts.
  Full suite green (255/0).

### Phase M5 — §1.1 carriers become live (Gap 2) — BLOCKED
- **RFC premise was incorrect.** The original text claimed "automatic once M2 lands" — that the pipeline
  flip would make `spineIntent`/`limbTargets`/`jointIntents` the live engine input. Verified by source
  audit (2026-07-17): only the posture/contact subset of §1.1 is consumed — `ConstraintSolver`
  reads `pose.contacts`, `pose.contactPrecedence`, `pose.postureIntent` (these are what M3/M4 exercise).
  `spineIntent`, `jointIntents`, `limbTargets` have **zero reads and zero writes** anywhere in `app/src`
  (only declaration + `copyFrom` in `PoseDefinition.kt`). They are dead, left from the deferred
  "Pose becomes intent-only" (`BasePose`→`IntentBuilder`) rewrite. The authoring helpers
  (`buildSpineCurve`, `buildHipFlexion`, `bakeIkLimb`) write **directly to node rotations**, not to
  these carriers.
- **M5 is NOT a flag flip.** Completing it requires the deferred `IntentBuilder` migration: helpers
  must populate `spineIntent`/`jointIntents`/`limbTargets` and the IK/Solver stages must consume them.
  Until that lands, the carriers stay dead; documenting them as "live" would be false. `M6` (validator
  stamp-only) is likewise gated on the same intent-only plumbing and cannot land as a pure flip either.
- **Live §1.1 carriers today:** `contacts`, `contactPrecedence`, `postureIntent` (consumed by
  `ConstraintSolver`; covered by M3/M4). `extremityOverrides` is consumed by the Finalizer.
- **Factual audit + required-work breakdown:** `RFC_INTENT_BUILDER_REWRITE.md` (current authoring model,
  carrier dead/dormant/obsolete table, files/helpers/stages affected, migration order, M5/M6/M7 classification).

### Phase M6 — Validator stamp-only (Gap 6 / Phase 8)
- `VALIDATOR_STAMP_ONLY = true`. Remove geometry inference from `ExerciseValidator`; add any missing
  stamps (e.g. a `postureResidual` stamp if needed). Validator reads §1.2 + §1.1 ROM only.

### Phase M7 — Gaze as target (Gap 7)
- Independent of M0–M6. Add `headTarget: WorldTarget?` to §1.1; add `buildGaze(targetWorld)` helper;
  Finalizer gains a neck/head resolver stage that derives neck/head from `headTarget` (reuses
  existing `buildHead` math but driven by a target, not a counter-rotated UP vector). Retrofit
  `BaseLungePose`/`BaseVerticalPullPose` gaze sites.

**Flags are removed only after the corresponding phase is green in CI for all poses.** Until then
each flag defaults per `ARCHITECTURE_V2` but can be flipped per-build for canary poses.

### Milestone exit criteria (explicit, every phase)

- **M0 complete when:** `SkeletonPipeline` exists with `PIPELINE_ACTIVE=false`; full CI suite green;
  zero consumers changed; zero poses touched.
- **M1 complete when:** frame-relative `bakeIkLimb` overload deleted; its **2** call sites migrated to
  the world overload; `IK_WORLD_ONLY=true`; `ValidatorRomClusterTest` + `ChestFrameIssueFTest` +
  `*PoseTest` green; grep proves no reference to the deleted overload (compile).
- **M2 complete when:** `PIPELINE_ACTIVE=true`; `produceFrame` runs the full ordered pipeline; the
  Finalizer's internal `ConstraintSolver.solve` call removed; `BasePose` helpers forward to
  `IntentBuilder`; every production pose compiles (no node-writing helper remains); `ValidatorRomClusterTest`
  matches the pre-M2 baseline (visual/geometry diff).
- **M3 complete when:** `SOLVER_OWNS_POSTURE=true`; every production contact/posed pose calls
  `declarePosture`; posture-seeded poses render with engine-derived pelvis; `PELVIS_INTENT` within
  tolerance; non-contact poses byte-identical (Solver no-op).
- **M4 complete when:** `FINALIZER_OWNS_CONVERSION=true`; `preConvertPoles` active; no pose writes a
  local transform after IK; `reconstructChestFrame` no-move guard verified on a synthetic conflict test.
- **M5 complete when (BLOCKED):** `spineIntent`/`limbTargets`/`jointIntents` are consumed by the
  engine (no dead carrier). NOT a flag flip — requires the deferred `BasePose`→`IntentBuilder`
  intent-only migration. Verified 2026-07-17 these three are currently unwritten AND unread, so the
  gate is unmet. The live §1.1 subset (`contacts`, `contactPrecedence`, `postureIntent`) IS consumed.
- **M6 complete when:** `VALIDATOR_STAMP_ONLY=true`; `ExerciseValidator` no longer imports
  `toLocalDirection`/`angleBetweenDegrees`/`atan2`; every Validator rule reads ≥1 stamp/intent; a
  build-time assertion fails the compile if geometry inference remains.
- **M7 complete when:** `headTarget` carrier + `buildGaze` helper present; `BaseLungePose`/
  `BaseVerticalPullPose` gaze sites migrated; `null` path byte-identical to legacy; gaze-direction
  tests green.
- **M8 (cleanup) complete when:** no `@Deprecated` on the migrated surface; `LegacyPoseAdapter`
  removed; `EngineFlags` booleans inlined/removed; legacy `else` branch in `finalize` removed;
  `PoseBuilder.evaluate` removed; grep proves zero `toLocalDirection`/`angleBetweenDegrees`/`atan2` in
  `ExerciseValidator`, zero `ConstraintSolver.solve` inside `finalize`, zero `EngineFlags.` boolean
  reads; `ARCHITECTURE_V2_ROADMAP.md` marks Phases 1/2/3/8 behaviorally complete.

---

## 7. Dependency graph (per-milestone)

### 7.1 Stage dependency graph (static, allowed/forbidden edges)
```
Consumer (workout loop / ValidationPoseViewer / snapshot)
        │ owns
        ▼
   SkeletonPipeline  ──creates/holds──▶ SkeletonFactory (tree), ConstraintSolver, SkeletonPoseFinalizer, ExerciseValidator
        │ calls (in order)
        ├──────────────▶ PoseBuilder.build()         [intent only]
        ├──────────────▶ IkStage.solve()             [over SkeletonMath]
        ├──────────────▶ ConstraintSolver.solve()
        ├──────────────▶ SkeletonPoseFinalizer.finalize()
        ├──────────────▶ ExerciseValidator.validate()
        │ uses
        ▼
   SkeletonMath (stateless facade: solveIK, deriveDefaultPole, bonesExact, toWorld/LocalDirection, buildHipRotation)
        │ used-by
        ├──────────────▶ IkStage, ConstraintSolver, SkeletonPoseFinalizer

SkeletonEngine  ──owned-by──▶ SkeletonRenderer / SkeletonProjector   [RENDER ONLY, no pipeline edge]
```
**Forbidden edges (architecture prevents these):**
- **Pose / BasePose may NOT call `SkeletonMath.solveIK` or `bakeIkLimb` that writes a node.** Pose
  writes §1.1 only. (Enforced by removing the node-writing helpers in M2; any remaining call is a
  compile error.)
- **Pose may NOT read or write §1.2 / `nodes` / stamps.** (Ownership rule §1; validator is the only
  other reader, and it is read-only.)
- **ConstraintSolver may NOT be called by a Pose or by the Finalizer ad hoc.** Only the pipeline
  calls it, in stage order. (Prevents re-entrant posture solving.)
- **SkeletonPoseFinalizer may NOT translate the root or move a Solver-settled contact.** (F1/B5
  guard inside `reconstructChestFrame`; if it would, it signals the pipeline for a bounded re-pass.)
- **SkeletonEngine may NOT call any pipeline stage.** It is render-only.
- **ExerciseValidator may NOT write `SkeletonPose`.** (Observer; returns `ValidationReport`.)
- **No stage may call its predecessor.** The DAG is acyclic by construction.

```
        Pose ──▶ Intent ──▶ IK ──▶ Solver ──▶ Finalizer ──▶ FK ──▶ Validator ──▶ Render
          │                                     ▲
          │                                     │ (bounded re-pass only, signal-driven)
          └─────────────────────────────────────┘
```

### 7.2 Milestone dependency graph (rollout order — Issue 3)

The audit correctly noted that M6 (Validator stamp-only) is **NOT independent**: the Validator can
only consume stamps once the MonkEngine runtime stages *actually produce* them. Under `PIPELINE_ACTIVE=false`
(M0), the legacy path may not write the ROM stamps M6 needs, so M6 is **blocked** until M2 (pipeline
active + stages writing stamps) and, for the new ROM stamps, until M3/M4 (the stages that set those
rotations). The dependency graph below makes this explicit. Each milestone lists its Required /
Optional / Independent / Blocked predecessors.

```
M0  scaffold pipeline
    Required:    (none)
    Optional:    (none)
    Independent: (all later phases)
    Blocked by:  (none)
        │
        ▼
M1  remove deprecated bakeIkLimb overload        [Gap 5]
    Required:    M0
    Optional:    (none)
    Independent: M3, M4, M5, M7
    Blocked by:  (none)
        │
        ▼
M2  Pose intent-only + pipeline drives stages     [Gap 1 + Gap 2 carrier activation]
    Required:    M1
    Optional:    (none)
    Independent: M7
    Blocked by:  (none)   ← engine now PRODUCES stamps (maxIkClampAmount, root*Delta, boneLengthsVerified)
        │
        ▼
M3  Solver authority (SOLVER_OWNS_POSTURE)        [Gap 3]
    Required:    M2
    Optional:    (none)
    Independent: M4, M5, M7
    Blocked by:  (none)   ← Solver now writes root*Delta + (new) hip/ROM stamps
        │
        ▼
M4  Finalizer authority (FINALIZER_OWNS_CONVERSION) [Gap 4]
    Required:    M2, M1
    Optional:    (none)
    Independent: M5, M7
    Blocked by:  (none)
        │
        ▼
M5  §1.1 carriers live (automatic after M2)       [Gap 2]
    Required:    M2
    Optional:    (none)
    Independent: M7
    Blocked by:  (none)
        │
        ▼
M6  Validator stamp-only (VALIDATOR_STAMP_ONLY)    [Gap 6 / Phase 8]
    Required:    M2   (engine must produce stamps)
    Optional:    M3, M4   (new ROM stamps authored by Solver/Finalizer; without them some rules
                            fall back to intent comparison, but the gate can ship with the
                            pre-existing stamps maxIkClampAmount/root*Delta/boneLengthsVerified)
    Independent: (none)
    Blocked by:  M0 with PIPELINE_ACTIVE=false (legacy path does not guarantee stamp production)
        │
        ▼
M7  headTarget gaze-as-target                     [Gap 7]
    Required:    M2   (Intent Layer carrier + Finalizer resolver)
    Optional:    (none)
    Independent: (none)
    Blocked by:  (none)
        │
        ▼
M8  deprecation purge + final cleanup
    Required:    M1, M2, M3, M4, M5, M6, M7   (all green)
    Optional:    (none)
    Independent: (none)
    Blocked by:  (none)
```

**Rollout order (linear, no interleaving):** M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8. The only
"independent" phases (M3/M4/M5/M7 relative to each other) MAY be merged in parallel PRs *after* their
required predecessor ships, but MUST NOT ship before it. M6 is explicitly **blocked** until M2 lands.

### 7.3 Performance gate (non-functional requirement — Issue 4)

Architecture v2 targets a **realtime MonkEngine**; allocation strategy is therefore an
explicit implementation gate, not an open question.

**Requirement (NFR-PERF-1):** After M2 lands, a steady-state frame MUST perform **zero per-frame
heap allocations** on the hot path (`produceFrame` → IK → Solver → Finalizer → FK).

- **Pooling:** the per-frame `SkeletonNode` tree is **pooled**, not allocated. The pipeline owns one
  reusable tree per character (see §3 ownership) and resets it at M2-stage-entry; node objects and
  their `localPosition`/`localRotation` are mutated in place, never re-created.
- **Stamp/scratch buffers:** `SkeletonMath` scratch vectors (`poleWorldScratch`, `ikResult`, etc.)
  are already reused; the pipeline guarantees no `Vector3()` / `JointRotation()` allocation inside the
  stage loop. `PipelineResult`/`ValidationReport` are allocated once per `produceFrame` call (one
  allocation per frame is acceptable and bounded); if even that is undesirable, the consumer supplies
  a result buffer.
- **`IntentBuilder`:** the per-pose `build()` may allocate (it runs once per pose authored shape, not
  per rendered frame) — this is outside the hot path and exempt.
- **Gate:** a CI allocation test (e.g. allocating-rate assertion via a debug allocator, or a
  documented budget) MUST pass before M2 is marked complete. This is a **hard gate**, not advisory.

This requirement does NOT change the architecture — it constrains the *implementation* of the already
specified stages (pool the tree, reuse scratch). No new component, no new ownership.

### 7.4 Cross-reference index (Issue 8 — all cited sections exist)
- `RFC_INTENT_LAYER.md`: §1 intent model, §2 ownership, §3 lifecycle, §4 serialization, §5 copy
  semantics, §6 validation, §7 immutability, §8 dependency, §9–11 Solver/Finalizer/Validator
  interaction, §12 migration.
- `RFC_EXECUTION_CONTRACT.md`: §1 stage contracts, §5 re-entrancy, §11 root guarantees, §13 bounded
  iterations, §14 feature flags.
- `RFC_GAP_CLOSURE.md`: §1 dependency graph, §2 migration graph, §3 rollout (M0–M8), §6 testing.
- `CAPABILITY_GAP_REPORT.md`: Gap 1–7.
All section numbers cited above are present in the referenced files (verified).

---

## 8. Failure modes and how the architecture prevents them

### 8.1 Re-entrancy
- **Risk:** Finalizer calls Solver, which calls Finalizer, which calls Solver… (today the Finalizer
  *does* call `ConstraintSolver.solve` internally — a latent re-entrancy).
- **Prevention:** Under `ARCHITECTURE_V2`, the Finalizer **never** calls the Solver. The pipeline is
  the sole caller of both, in fixed order. Re-entry for F1 is **signal-driven and bounded**: the
  Finalizer sets a `PipelineSignal` (e.g. `CHEST_FRAME_CONFLICTS_CONTACT`), returns; the pipeline
  decides whether to run a bounded Solver re-pass (max N iterations, N constant). Unbounded loops are
  impossible because the re-pass count is capped and the Validator surfaces any residual rather than
  retrying.

### 8.2 Circular dependency
- **Risk:** Pose→Pipeline→Pose, or IK→Solver→IK.
- **Prevention:** The DAG is acyclic by construction. `SkeletonPipeline` is the composition root and
  holds forward-only references. `PoseBuilder` is an interface the pipeline *uses*; the pipeline is
  never injected into a Pose. `SkeletonMath` is stateless and depends on nothing above it. Circular
  import is structurally impossible: leaves (`SkeletonMath`, `SkeletonPose`, `PoseContext`) depend on
  no stages; stages depend only on leaves + `SkeletonMath`.

### 8.3 Invalid ownership (a component writes a section it does not own)
- **Risk:** Pose writes §1.2; Finalizer moves the root; Validator mutates the pose.
- **Prevention:** Ownership is encoded three ways — (1) **structural**: in M2 the node-writing
  helpers are deleted from `BasePose`, so a Pose *cannot* write geometry (compile-time guarantee);
  (2) **runtime guard**: `reconstructChestFrame` snapshots Solver-settled contacts and rolls back +
  flags `rootTranslationDelta` if it would move them (F1/B5); (3) **contract**: `ExerciseValidator`
  returns a `ValidationReport` and takes `pose` as read-only (Kotlin `val`/no mutating API). The
  `PipelineResult` hands the consumer an immutable-by-convention finalized pose.

### 8.4 Partial execution (a stage is skipped, or the pipeline throws mid-way)
- **Risk:** IK runs but Solver doesn't; Finalizer runs on a non-posture-settled tree; consumer
  renders a half-built pose.
- **Prevention:** `produceFrame` is **all-or-nothing**. The pipeline either returns a fully finalized
  `PipelineResult` or throws (and the consumer gets no pose). There is no partial-output path. Each
  stage transitions the single `SkeletonPose` forward; if a stage throws, the exception propagates and
  no `PipelineResult` is constructed. The legacy path (`PIPELINE_ACTIVE=false`) is a separate,
  complete code path, never interleaved with the v2 path. Stage flags are *coarse* (whole-stage on/off),
  never *partial* within a stage, so a stage is either fully active or fully bypassed — no half-state.

### 8.5 Stale / shared mutable state across frames (F9 inter-frame cache)
- **Risk:** The Solver's `lastSolvedRoot` `WeakHashMap` keyed by `SkeletonPose` identity leaks or
  cross-contaminates frames.
- **Prevention:** Keyed by `SkeletonPose` identity with `WeakHashMap` (no leak on GC). Production
  poses reuse one `SkeletonPose` across frames *by design* (so smoothing carries forward); the
  pipeline ensures the same pose instance flows build→IK→Solver so the cache key is stable. If a
  consumer builds a fresh pose each frame, smoothing simply resets (strict per-frame solve) — correct,
  not corrupt.

### 8.6 Intent/geometry divergence (§1.1 says X, §1.2 shows Y)
- **Risk:** Pose declares a `limbTarget` the IK stage fails to honor (unreachable), silently.
- **Prevention:** IK writes `maxIkClampAmount` + `straightIntentDropped` stamps on every solve; the
  Validator reads them and emits `IK_UNREACHABLE` / `STRAIGHT_INTENT_DROPPED` issues. Divergence is
  *surfaced*, not hidden. The optimizer (F8 rejected) is not reintroduced.

### 8.7 Flag misconfiguration (partial v2 + partial legacy interleaved)
- **Risk:** `PIPELINE_ACTIVE=true` but `SOLVER_OWNS_POSTURE=false` → pipeline builds intent-only pose
  but Solver ignores posture → root never seeded → broken frame.
- **Prevention:** `EngineFlags.ARCHITECTURE_V2` is a single preset applied atomically; individual
  flags are test/escape hatches only. A startup invariant asserts: if `PIPELINE_ACTIVE` then
  `IK_WORLD_ONLY && FINALIZER_OWNS_CONVERSION` must be true (a pipeline without world-only IK or
  finalizer conversion is incoherent). Misconfig fails fast at construction.

---

## 9. Open questions for the maintainer (not blocking the RFC)
- Should `IntentNormalization` be a separate class or a private method of `SkeletonPipeline`? (RFC
  treats it as an internal stage; either is fine.)
- Pooling of the per-frame `SkeletonFactory` tree vs. fresh allocation — perf decision for M2;
  architecture is agnostic (pipeline owns the tree either way).
- `headTarget` resolver math (Gap 7) reuses `buildHead`; confirm neck IK vs. direct orientation.
