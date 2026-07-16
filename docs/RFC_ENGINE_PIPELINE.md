# RFC — Architecture v2 Engine Pipeline / Orchestrator

**Status:** Draft specification (no code).
**Author:** Principal Engine Architect.
**Addresses:** Capability Gap Report Gap 1 (Engine pipeline / orchestrator, spec `ARCHITECTURE_V2.md` §3).
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
The Pose must stop being a geometry producer. It must become an **intent author**. The engine must
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

### Phase M0 — Scaffold orchestrator (no behavior change)
- Add `SkeletonPipeline` with `PIPELINE_ACTIVE = false`.
- `produceFrame` in legacy mode = exactly today: `pose.build()` (which still builds the tree) →
  `finalizer.finalize()` → optional validator. All consumers still call `pose.build()` directly;
  pipeline is unused. **Zero regression.**

### Phase M1 — IK extraction (Gap 5 + foundation for Gap 1)
- Introduce `declareLimbTarget(...)` alongside `bakeIkLimb`. Poses adopt `declareLimbTarget` one
  limb at a time; `bakeIkLimb` becomes a thin recorder (writes intent AND, for backward compat,
  still solves into the tree via the IK stage when `PIPELINE_ACTIVE`).
- Delete the deprecated frame-relative `bakeIkLimb` overload; migrate its ~18 callers to
  world-target declaration. `IK_WORLD_ONLY = true`.
- **Coexist:** legacy poses (still calling tree-building `bakeIkLimb`) work because `PIPELINE_ACTIVE=false`.

### Phase M2 — Pose becomes intent-only (the core cut)
- `PIPELINE_ACTIVE = true`. `produceFrame` now: `pose.build()` (intent only) → `IntentNormalization`
  → `IkStage.solve` (builds the tree, solves) → `ConstraintSolver.solve` → `finalizer.finalize` →
  FK → validator.
- `BasePose.build()` is refactored so its helpers record intent instead of writing nodes. A pose
  that still calls a tree-writing helper is caught at compile time (helper removed) — migration is
  mechanical, pose-by-pose.
- **Adapter needed:** `LegacyPoseAdapter` — wraps an old `PoseBuilder` whose `build()` still returns
  a full tree; the adapter extracts §1.1-equivalent from the tree (or the pose is simply migrated).
  Prefer direct migration; adapter is the fallback for the long tail.

### Phase M3 — Activate Solver authority (Gap 3)
- `SOLVER_OWNS_POSTURE = true`. Production poses migrated to `declarePosture(...)`. Contact poses
  get correct root-from-intent; non-contact poses unchanged (Solver no-ops on empty contacts).
- Validation poses already declare posture → unaffected.

### Phase M4 — Activate Finalizer authority (Gap 4)
- `FINALIZER_OWNS_CONVERSION = true`. `preConvertPoles` owns all conversion; no pose writes a local
  transform after IK. Chests reconstructed read-only on settled contacts (F1/B5 live).

### Phase M5 — §1.1 carriers become live (Gap 2)
- With Pose intent-only and pipeline consuming it, `spineIntent`/`limbTargets`/`jointIntents` are
  now the real input. The helpers already feed them. No dead carriers remain.

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

---

## 7. Dependency graph

### Allowed edges (who may call whom)
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

### Forbidden edges (architecture prevents these)
- **Pose / BasePose may NOT call `SkeletonMath.solveIK` or `bakeIkLimb` that writes a node.** Pose
  writes §1.1 only. (Enforced by removing the node-writing helpers in M2; any remaining call is a
  compile error.)
- **Pose may NOT read or write §1.2 / `nodes` / stamps.** (Ownership rule §1; validator is the only
  other reader, and it is read-only.)
- **ConstraintSolver may NOT be called by a Pose or by the Finalizer ad hoc.** Only the pipeline
  calls it, in stage order. (Prevents re-entrant posture solving.)
- **SkeletonPoseFinalizer may NOT translate the root or move a Solver-settled contact.** (F1/B5
  guard inside `reconstructChestFrame`; if it would, it signals the pipeline for a bounded re-pass.)
- **SkeletonEngine may NOT call any pipeline stage.** It is render-only. (Name retained; logic
  barred by code review + the fact it holds no stage instances.)
- **ExerciseValidator may NOT write `SkeletonPose`.** (Observer; returns `ValidationReport`.)
- **No stage may call its predecessor** (e.g. Finalizer calling Pose). The DAG is acyclic by
  construction; the pipeline holds the only references and calls forward only.

```
        Pose ──▶ Intent ──▶ IK ──▶ Solver ──▶ Finalizer ──▶ FK ──▶ Validator ──▶ Render
          │                                     ▲
          │                                     │ (bounded re-pass only, signal-driven)
          └─────────────────────────────────────┘
   (Render/SkeletonEngine is OFF this chain — render consumes the final pose only.)
```

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
