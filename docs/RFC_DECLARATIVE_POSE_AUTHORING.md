# RFC_DECLARATIVE_POSE_AUTHORING

> **Branch:** Architecture v2 — **Branch B: Declarative Pose Authoring** (future).
> **Status:** Authoritative design document. **No implementation, no code, no production changes.**
> **Companion RFCs:** `RFC_PHASE_I_CLOSURE.md` (Branch A closure + Branch A/B split + Phase II entry
> criteria), `RFC_ENGINE_PIPELINE.md` (Runtime stage design), `RFC_INTENT_LAYER.md` (§1.1 intent model
> target), `RFC_INTENT_BUILDER_REWRITE.md` (current-state audit + required-work breakdown),
> `RFC_GAP_CLOSURE.md` (milestone gates; M5/M6 recorded as BLOCKED/deferred onto this branch).
> **Authoritative semantic inventory (per-helper classification):** `RFC_DECLARATIVE_AUTHORING.md` —
> the contract this design must honour (every "Becomes intent" helper → a consumed carrier; every
> "Splits" helper → declaration + engine stage; every "Becomes obsolete" wrapper → deleted).
> **Date:** 2026-07-17.
>
> This document defines the future authoring architecture **independently of the Runtime**. It is the
> canonical design reference for Declarative Pose Authoring. It does not modify, implement, or schedule
> code; it defines intent, ownership, consumption, migration, guarantees, risks, and a fresh phase plan.

---

## 1. Problem Statement

### 1.1 Why direct node authoring is the only remaining imperative subsystem

After Phase I (Branch A), every *engine* subsystem is architecture-v2 compliant:

- The **Runtime** (`SkeletonPipeline`) owns the ordered stage chain and is the sole caller of the Solver
  and Finalizer (`RFC_PHASE_I_CLOSURE.md` §2, §6).
- The **ConstraintSolver** owns root/posture for contact poses (M3, `SOLVER_OWNS_POSTURE=true`) and
  no-ops for contact-less production poses.
- The **Finalizer** is the exclusive writer of every local transform after IK (M4,
  `FINALIZER_OWNS_CONVERSION=true`), including the F1/B5 chest-frame no-move guard.
- **Validation** is strictly read-only.

The one subsystem that still computes geometry imperatively is **pose authoring** itself. Every
production/validation pose calls helpers in `BasePose.kt` that write `SkeletonNode` transforms directly:

- `buildSpineCurve`, `buildLumbarFlexion`, `buildChestTwist`, `buildChestSideBend`, `buildChestOrientation`
  write `chest`/`pelvis`/`lumbar.localRotation`.
- `buildHipFlexion`, `buildHipAbduction`, `buildHipRotation`, `buildHipOrientation` write `hip.localRotation`.
- `buildShoulders`, `buildPelvis`, `buildTorso`, `buildHead`, `buildRigidSegment` write `localPosition`.
- `buildClavicularRotation`, `buildWristArticulation`, `buildAnkleArticulation` write joint `localRotation`.
- `bakeIkLimb` runs `SkeletonMath.solveIK`/`solveStraightLimb` **inside the pose** and writes the solved
  `middleNode`/`endNode.localPosition` via `toLocalDirection`.

There are **16 direct `localRotation`/`localPosition` write sites** in `BasePose.kt` alone, plus the IK
solve embedded in `bakeIkLimb`. The pose, not the engine, builds the node tree; the Runtime then merely
*settles* and *converts* what the pose already constructed. Authoring is therefore the sole remaining
imperative subsystem: it is the only place where transforms are computed outside the engine's single-writer
model.

### 1.2 Why the Runtime is architecture-v2 compliant but authoring is not

The architecture-v2 ownership rule (per `RFC_ENGINE_PIPELINE.md` and `RFC_PHASE_I_CLOSURE.md` §2) is:
**each responsibility has exactly one writer, and that writer is an engine stage, not a pose.**

- The **Runtime satisfies this** because M2/M3/M4 moved ownership of Solver and Finalizer into engine
  stages behind flags, and verified (byte-identical) that production output is unchanged. The pose's
  role in the Runtime is only to *emit a built tree*; it does not own root/posture/conversion.
- **Authoring violates this** in two distinct ways:
  1. **It writes transforms it does not own.** Limb placement, trunk lean, hip orientation, extremities
     are all computed by the pose as concrete node rotations/positions. The engine's single-writer model
     is bypassed at authoring time.
  2. **Its declared intent is dead.** The §1.1 carriers meant to carry authoring intent —
     `spineIntent`, `jointIntents`, `limbTargets` — are **never written and never read**
     (`RFC_INTENT_BUILDER_REWRITE.md` §2; `Section11CarriersTest` pins this). The pose authors via nodes,
     and the engine reads nodes, so the intent layer is a no-op. The live §1.1 subset
     (`contacts`, `contactPrecedence`, `postureIntent`, `headTarget`) is the only part actually flowing
     pose→engine.

So the Runtime is compliant because it centralizes ownership in stages; authoring is not because it
pre-computes geometry in the pose and leaves the intent carriers empty. Branch B closes exactly this gap:
move authoring from "pose computes nodes" to "pose declares intent; engine computes nodes."

---

## 2. Design Goals

The declarative authoring model is defined by the following goals. They are the contract Branch B must
satisfy; each is checkable against the Runtime guarantees in §6.

| # | Goal | Meaning |
|---|------|---------|
| G1 | **Poses describe intent only** | A pose's `build()` populates §1.1 declarations (spine curve, joint articulations, limb targets, posture, contacts, head target, extremity overrides, motion/camera/environment hints). It never emits or mutates a `SkeletonNode` tree. |
| G2 | **Engine owns geometry** | The shape of the skeleton (segment offsets, extremity orientation) is derived by engine stages from intent, not hand-placed by the pose. |
| G3 | **Engine owns FK** | Forward kinematics is performed exclusively by the Runtime (Finalizer/FK stage) on the engine-built tree; the pose never propagates transforms. |
| G4 | **Engine owns IK** | Limb end-effector placement is solved by an engine IK stage from `limbTargets` on a pipeline-owned tree, not by `bakeIkLimb` inside the pose. |
| G5 | **Engine owns posture** | Root/pelvis seeding from `postureIntent` (already true for contact poses via M3) extends to all poses; the pose names posture, the Solver derives the root. |
| G6 | **Engine owns constraints** | Contact honoring, contact-conflict precedence, straight-limb intent, and the chest-frame no-move guarantee are enforced by engine stages (Solver / Finalizer), never approximated by pose-authored node arithmetic. |
| G7 | **Pose never computes transforms** | No `localRotation.set` / `localPosition.set` / `solveIK` call inside a pose. The pose's only writes are §1.1 declarations via the authoring API (§3). |

> G1–G7 collectively state the inversion: today the pose *is* the geometry computer and the engine *settles*
> it; after Branch B the pose is a *declaration* and the engine *is* the geometry computer.

---

## 3. Authoring API

The future authoring surface is a **declaration API**. The pose receives an `IntentBuilder` and calls only
declaration methods; it never touches nodes. The API below is illustrative (examples only — not
implementation). It is the authoring counterpart of the `RFC_INTENT_LAYER.md` §1.1 model and the
`RFC_INTENT_BUILDER_REWRITE.md` §3 helper inventory.

### 3.1 Carrier-backed declarations (map to live + currently-dead §1.1 carriers)

| Declaration (example) | §1.1 carrier written | Today's imperative equivalent (to be removed) |
|-----------------------|----------------------|-----------------------------------------------|
| `builder.spine(lumbarRad, thoracicRad, axis = axisZ)` | `spineIntent` | `buildSpineCurve(pelvis, chest, …)` |
| `builder.joint(joint, rotation: JointRotation)` | `jointIntents` (relative articulation) | `buildHipOrientation` / `buildChestOrientation` / `buildClavicularRotation` / `buildWristArticulation` / `buildAnkleArticulation` |
| `builder.limbTarget(joint, world, constraint, contact? = null)` | `limbTargets` (+ `contacts` when `contact` given) | `bakeIkLimb(rootW, targetW, …)` |
| `builder.posture(kind, tolerance = 0f)` | `postureIntent` | `declarePosture(pose, kind, …)` (already intent) |
| `builder.contact(spec, precedenceRank?)` | `contacts` + `contactPrecedence` | `bakeIkLimb(... contact=)` (already intent) |
| `builder.headTarget(world, upBias = UP)` | `headTarget` | `buildGaze(neck, head, …)` (already intent) |
| `builder.overrideExtremity(extremity)` | `extremityOverrides` | `overrideExtremityOrientation(pose, …)` (already intent API; currently never called) |
| `builder.motion(driver)` / `builder.camera(hint)` / `builder.environment(hint)` | `motion` / `camera` / `environment` | metadata hints (already separate from `SkeletonPose` tree) |

### 3.2 Structural declarations (segment geometry the engine builds)

| Declaration (example) | Meaning |
|-----------------------|---------|
| `builder.segment(parent, child, offset)` | declares a rigid offset between two joints; the engine writes `child.localPosition` from intent (replaces `buildRigidSegment` / `buildTorso` / `buildPelvis` / `buildShoulders` / `buildHead`). |
| `builder.clavicle(elevation, protraction, axialRotation, sideSign)` | declares girdle articulation (replaces `buildClavicularRotation`). |
| `builder.trunk(lowerJoint, axis, lowerRad, thoracicRad)` | unified trunk declaration consumed by the Finalizer's spine expansion (replaces `buildSpineCurve` + `buildLumbarFlexion`). |

### 3.3 Contract properties of the API

- **The pose never imports `SkeletonNode` for writing.** Its only dependency is `IntentBuilder` +
  `SkeletonDefinition` (proportions for hints).
- **§1.1 is frozen after `build()`** (per `RFC_INTENT_LAYER.md` lifecycle): the `IntentBuilder` is mutable
  inside `build()` and immutable afterwards; the engine reads it, never the pose.
- **Compile-time guard:** only `IntentBuilder` may mutate §1.1 (carriers become `val` /
  package-private-setter); a direct `pose.spineIntent = …` is rejected.

> The API above is **exactly** the helper inventory in `RFC_INTENT_BUILDER_REWRITE.md` §1, re-expressed as
> declarations. No new behavior is invented; the existing node-writing helpers become thin forwards to
> these declarations (§5 compatibility layer).

---

## 4. Engine Consumption

For every declaration, the table states: **which Runtime stage consumes it, in what order, and which stage
becomes the single writer** of the resulting transform. "Order" is relative to the authoritative pipeline
(`RFC_PHASE_I_CLOSURE.md` §6): `build (intent only)` → `Pipeline` → `Solver` → `Finalizer/FK` → `Validation`.
The IK stage referenced below is the Branch B `IkStage` extracted from `bakeIkLimb` (deferred per
`RFC_GAP_CLOSURE.md` M2 note).

| Declaration → carrier | Consuming stage | Order | Single writer of result |
|-----------------------|----------------|-------|-------------------------|
| `spine` → `spineIntent` | **Finalizer** (`reconstructChestFrame` expands `SpineCurve` into pelvis+chest rotations) | before FK, after Solver | Finalizer writes `pelvis`/`chest`/`lumbar.localRotation` |
| `joint` → `jointIntents` | **Finalizer** (applies relative articulations: chest/hip/girdle/ankle/wrist) | before FK | Finalizer writes the joint `localRotation` |
| `limbTarget` → `limbTargets` | **IkStage** (solves chain on pipeline-owned tree; writes `middle`/`end.localPosition`) | before Solver, after build | IkStage writes limb node `localPosition` |
| `contact`/`posture` → `contacts`/`postureIntent`/`contactPrecedence` | **Solver** (root seed F2, conflict weighting F7, smoothing F9, re-bake contact limbs) | after IkStage | Solver writes root/pelvis (+ contact limb `localPosition` on re-bake) |
| `headTarget` → `headTarget` | **Finalizer** (`resolveHeadTarget`, already live in Phase 7) | before FK | Finalizer writes neck/head `localPosition`/`localRotation` |
| `overrideExtremity` → `extremityOverrides` | **Finalizer** (extremity derivation skips overridden extremities) | before FK | Finalizer writes heel/toe/palm `localPosition` |
| `segment`/`clavicle`/`trunk` → structural intent | **Finalizer/IkStage** (writes `localPosition` from declared offsets) | before FK | Finalizer/IkStage write the segment `localPosition` |
| `motion`/`camera`/`environment` → hints | **Renderer / AnimationController** (not a transform writer) | n/a | consumer reads hint, writes nothing to pose |

**Single-writer summary after Branch B:**
- **IkStage** is the sole writer of limb `localPosition` (was: the pose via `bakeIkLimb`).
- **Finalizer** is the sole writer of all remaining `localRotation`/`localPosition` (spine, joints,
  extremities, head, segments) — extending M4's exclusive-conversion ownership to the formerly
  pose-authored joints.
- **Solver** remains the sole writer of root/posture for contact poses.
- **Validation** remains read-only.

This makes the pose's "never computes transforms" goal (G7) concrete: every transform now has an engine
stage as its single writer.

---

## 5. Migration Strategy

Branch B migrates ~58 production pose files + 4 validation pose files incrementally, pose family by pose
family, without a big-bang rewrite and without breaking the byte-identical invariant
(`RFC_PHASE_I_CLOSURE.md` §8 criterion 4).

### 5.1 Compatibility layer

- The `IntentBuilder` declaration methods are introduced first (§3). The existing `BasePose` node-writing
  helpers (`buildSpineCurve`, `buildHipOrientation`, `bakeIkLimb`, …) are rewritten as **thin forwards** to
  the corresponding declaration, so unchanged poses keep compiling and behaving identically during the
  transition. This is the "no pose touched" path used throughout Phase I, extended to authoring.
- The §1.1 carriers are made `val`/package-private with the `IntentBuilder` as the only mutator; poses
  still reach them *through* the builder, never directly.

### 5.2 Mixed mode

- **Mixed mode = some poses declare intent, others still write nodes, in the same running engine.** It is
  enabled by keeping the Runtime able to consume *both* an intent-populated `SkeletonPose` and a
  node-populated one during migration:
  - If a pose populated `limbTargets`/`spineIntent`/`jointIntents`, the IkStage/Finalizer consume them
    (Branch B path).
  - If a pose still wrote nodes directly (legacy helper forward not yet flipped, or unconverted pose), the
    Runtime consumes the node tree as today (Branch A path).
- Mixed mode is *temporary* and explicitly tracked: `Section11CarriersTest` is extended to assert, per
  carrier, that it transitions from dead → (written by pose AND read by engine) as each pose family is
  migrated, preventing a silent "done" without plumbing.

### 5.3 Removal strategy

- **Per-family:** as each pose family is converted to declarations, its use of the legacy node-writing
  helpers is deleted (the helper forward becomes unused for that family).
- **Helper purge:** once *no* pose calls a legacy helper, the helper (and its node-write body) is deleted.
- **Dead/obsolete carrier cleanup:** `spineIntent`/`jointIntents`/`limbTargets` are retired only *after*
  they are live (written + read); the obsolete `SkeletonPose.motion`/`camera`/`environment` fields (real
  data on `PoseBuilder.metadata`) are removed as cleanup once nothing references them.
- **Validator (the old M6):** after Branch B produces the §1.2 stamps (hip-rom, per-joint ROM,
  `limbTargets`-derived symmetry), the validator geometry-inference removal proceeds as a consumer of those
  stamps — it rides Branch B, it does not precede it.

### 5.4 Rollback strategy

- The **byte-identical invariant** is the rollback guarantee: every migrated pose must render identically
  to its node-authored predecessor. If a family regresses, revert that family's `build()` to the legacy
  helper forward (mixed mode still supports it) — no engine change required.
- The **intent carriers are additive**: populating `spineIntent` does not remove the pose's ability to
  fall back to node authoring until the helper is deleted. Deletion is the irreversible step and happens
  only after the carrier is proven live per family.
- Runtime flags (`PIPELINE_ACTIVE` etc.) remain `true`; Branch B introduces **no new global flag flip** for
  the migration itself — the switch is per-pose (declaration vs node), not global.

---

## 6. Runtime Guarantees

Invariants that must remain true after Branch B migration (superset of Phase I's; G7 added):

| # | Guarantee | Owner | Today (Branch A) | After Branch B |
|---|-----------|-------|------------------|----------------|
| R1 | **Pose never writes nodes** | IntentBuilder contract / compile guard | Violated (16+ node writes in `BasePose`) | Satisfied — only §1.1 declarations |
| R2 | **Solver owns posture** | ConstraintSolver | True for contact poses; CUSTOM for rest | True for all poses (pose declares posture; solver derives root) |
| R3 | **Finalizer owns locals** | SkeletonPoseFinalizer (`FINALIZER_OWNS_CONVERSION`) | True for post-IK conversion + chest guard | Extended to formerly pose-authored joints (spine/joints/extremities/head/segments) |
| R4 | **Validator observes only** | ExerciseValidator | True (read-only) | Unchanged |
| R5 | **Pipeline is sole stage caller** | SkeletonPipeline (M2) | True | Unchanged (IkStage added as a stage it calls) |
| R6 | **Single writer per transform** | engine stages | True post-IK | True for *all* transforms incl. limbs (IkStage) and trunk/joints (Finalizer) |
| R7 | **Byte-identical migration** | per-pose migration + `Section11CarriersTest` | n/a (no migration yet) | Each migrated pose renders identically to its predecessor |
| R8 | **No engine contact moved by finalize** | Finalizer F1/B5 guard (M4) | True | Unchanged |

---

## 7. Risks

| Risk | Description | Why it applies to Branch B |
|------|-------------|----------------------------|
| **Authoring ergonomics** | Declarative intent is more verbose and less immediately spatial than "place this joint here". Pose authors lose the direct mental model of node positions. Risk: slower pose authoring, accidental over-rotation, or under-specification (e.g. a `limbTarget` with a wrong pole). Mitigation lives in the API shape (fluent `IntentBuilder`, sane defaults) — design concern, not implementation here. | Inherent to inverting G1/G7. |
| **Debugging difficulty** | When the pose no longer writes nodes, a wrong skeleton is now the product of engine stages (IkStage/Finalizer/Solver) consuming intent, not a single visible `localRotation.set` in the pose. Stack traces point at stages, not author lines. Requires intent-aware debugging (inspect §1.1 + per-stage stamps). | New ownership model shifts the bug surface from pose to engine. |
| **Performance** | Introducing an `IkStage` that builds a pipeline-owned tree from `limbTargets` (instead of the pose mutating its own tree) may allocate per frame; the Phase I NFR-PERF-1 zero-allocation gate was explicitly deferred to this follow-up (`RFC_GAP_CLOSURE.md` M2 note). Pooling/reuse must be designed so Branch B does not regress hot-path allocation. | The tree ownership moves from Pose to pipeline; allocation discipline must be re-established. |
| **Backwards compatibility** | ~58 production poses + renderers/consumers assume `pose.build()` yields a populated node tree. Mixed mode (§5.2) is the mitigation, but any consumer that reads `pose.roots`/`pose.joints` directly (outside the pipeline) breaks the moment a pose stops emitting nodes. Requires auditing all `build()` consumers. | Largest migration surface; the reason for incremental, family-by-family rollout. |
| **Intent-coverage gaps** | Some node writes may not map cleanly to a carrier (e.g. stylized extremity geometry, asymmetric clavicle). `extremityOverrides` exists but is dormant; coverage must be proven per family or poses silently lose detail. | The dead/dormant carriers (`RFC_INTENT_BUILDER_REWRITE.md` §2) must become live, not skipped. |
| **Hidden regression** | As in Phase I's lesson (hidden compile failures distorted baselines), a partially-migrated pose that compiles but renders wrong could pass a loose test. `Section11CarriersTest` + byte-identical asserts are the guard. | Same class of risk the honest-audit discipline exists to prevent. |

---

## 8. Phase Plan (Branch B — fresh numbering)

A new roadmap for Declarative Pose Authoring. **M5/M6 are not reused** — they are recorded as BLOCKED/
deferred in `RFC_GAP_CLOSURE.md` and are subsumed here. Branch B uses **B0…Bn**.

### B0 — IntentBuilder substrate
- **Depends on:** Branch A complete (true today).
- **Deliverables:** the `IntentBuilder` type; §1.1 carriers made `val`/package-private with builder as sole
  mutator; compile-time guard rejecting direct carrier writes; the declaration API surface (§3) defined but
  not yet consumed by any stage. No pose changed yet.
- **Exit:** `IntentBuilder` compiles; direct `pose.spineIntent = …` fails to compile; existing poses
  untouched and green.

### B1 — IkStage extraction
- **Depends on:** B0.
- **Deliverables:** extract limb solving from `bakeIkLimb` into a pipeline-owned `IkStage` that consumes
  `limbTargets` and writes limb `localPosition` on an engine tree. `bakeIkLimb` becomes a forward to
  `builder.limbTarget(...)`.
- **Exit:** contact/limb poses render byte-identical via the IkStage; `limbTargets` transitions dead →
  live (written + read); `Section11CarriersTest` flipped for `limbTargets`.

### B2 — Finalizer intent consumers
- **Depends on:** B0 (and B1 for any limb-adjacent spine).
- **Deliverables:** Finalizer consumes `spineIntent` (expand into pelvis+chest in `reconstructChestFrame`,
  replacing `buildSpineCurve`'s node writes), `jointIntents` (chest/hip/girdle/ankle/wrist), and
  `extremityOverrides` (make the dormant consumer real). `buildSpineCurve`/`buildHipOrientation`/
  `buildChest*`/`buildClavicularRotation`/`buildWrist/AnkleArticulation` become forwards.
- **Exit:** `spineIntent`/`jointIntents` transition dead → live; `extremityOverrides` transitions dormant →
  live; trunk/hip poses byte-identical; `Section11CarriersTest` flipped for both.

### B3 — Posture universality
- **Depends on:** B1 (Solver already owns posture for contacts via M3).
- **Deliverables:** every production pose declares a `postureIntent` (STANDING/CUSTOM/…); Solver derives
  root for all poses, extending M3 from contact-only to universal. No pose hand-computes `pelvisY`/`pelvisX`.
- **Exit:** production poses no longer author root arithmetic; Solver owns posture universally;
  byte-identical.

### B4 — Pose migration (family-by-family)
- **Depends on:** B1 + B2 + B3 (all declarations consumable).
- **Deliverables:** migrate each production/validation pose family from node-writing helpers to
  declarations, in mixed mode; delete each legacy helper as its last caller is converted; retire obsolete
  `SkeletonPose.motion`/`camera`/`environment` fields.
- **Exit:** **zero** pose writes a node (`R1` satisfied); legacy node-writing helpers deleted; full suite
  green with byte-identical per-family asserts.

### B5 — Validator stamp-only (the old M6)
- **Depends on:** B4 (stamps produced by IkStage/Finalizer/Solver).
- **Deliverables:** `ExerciseValidator` reads §1.2 stamps + §1.1 intents only; removes `toLocalDirection`/
  `angleBetweenDegrees`/`atan2` geometry inference; build-time assertion fails compile if inference remains.
- **Exit:** validator is purely observational over stamped/intent state; `R4` holds by construction.

### B6 — Closure & purge
- **Depends on:** B4 + B5.
- **Deliverables:** remove mixed-mode fallback; confirm `Section11CarriersTest` asserts all carriers live;
  documentation closes Branch B; retrospective on ergonomics/debugging/perf risks (§7) resolved or accepted.
- **Exit:** Branch B complete; pose authoring is fully declarative; Runtime guarantees R1–R8 all hold.

### Dependency graph (text)

```
B0 ──┬──> B1 ──> B3 ──┐
    ├──> B2 ─────────┼──> B4 ──> B5 ──> B6
    └──(B1/B2 feed B4's consumability)
```

B0 is the prerequisite for everything. B1 and B2 are independent of each other (different stages) but both
must land before B4 can migrate poses that use limbs *and* spine/joints. B3 is lightweight (Solver already
owns contact posture) and can land in parallel with B1/B2. B4 is the large migration and gates B5/B6. B5
cannot start until B4 produces the stamps it consumes.

> This plan deliberately **does not reuse M5/M6**: those labels described flag-flip-style milestones that
> the audit proved false. Branch B is a separate architecture with its own B0–B6 phases, each with explicit
> dependencies and deliverables, consistent with `RFC_PHASE_I_CLOSURE.md` §4 (M5/M6 belong to Branch B) and
> §8 (Phase II entry criteria).
