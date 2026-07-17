# RFC_INTENT_BUILDER_REWRITE

> **Scope of this document:** factual inventory only. This RFC answers four questions about the
> *current* authoring model and the work required to move from imperative `SkeletonNode` authoring to
> declarative §1.1 intent authoring. It does **not** propose a solution or change any code. The target
> architecture it would implement is specified separately in `RFC_INTENT_LAYER.md`; this document only
> records what exists today and what files/helpers/stages must change.
>
> **Companion status doc:** `RFC_GAP_CLOSURE.md` §M5 (marked BLOCKED) and `RFC_ENGINE_PIPELINE.md` §M5.
> **Roadmap status:** unchanged by this document (no flag flipped, no code changed).
> **Date:** 2026-07-17. **Verified by:** source audit of `app/src/main/java/com/monkfitness/app/`.

---

## 0. Determining the nature of the work (answers the framing question)

**Is this a deferred architecture rewrite, or a dormant feature?**

Evidence:

- The §1.1 carriers `spineIntent`, `jointIntents`, `limbTargets` exist on `SkeletonPose`
  (`PoseDefinition.kt`) but are **never written and never read** anywhere in `app/src` (only their
  declaration and `copyFrom` touch them — `Section11CarriersTest` pins this).
- The production authoring helpers (`buildSpineCurve`, `buildHipFlexion`, `buildHipOrientation`,
  `bakeIkLimb`, `buildShoulders`, `buildChestTwist`, …) write **directly into `SkeletonNode`
  transforms** (`localPosition` / `localRotation`), exactly as the pre-intent-layer design did.
- The target design — an `IntentBuilder` surface that populates §1.1 and engine stages that *consume*
  it — is fully specified in `RFC_INTENT_LAYER.md` but **no part of it is implemented**: there is no
  `IntentBuilder` class in the codebase, and no engine stage reads the three carriers.
- M2–M4 were pure **flag flips** over the *existing* imperative pipeline. They deliberately did not
  touch authoring. The `SkeletonPipeline` (M0/M2) drives `pose.build()` → `ConstraintSolver.solve()`
  → `SkeletonPoseFinalizer.finalize()` → FK, and `pose.build()` still emits a fully-built node tree,
  not intent.

**Conclusion:** this is a **deferred architecture rewrite**, not a dormant feature. A dormant feature
would have an implemented-but-disabled code path behind a flag. Here the consuming engine stages and
the intent-populating helpers do not exist; only the empty carrier fields remain. Bringing M5/M6/M7
to life requires implementing the `RFC_INTENT_LAYER.md` design (the "Pose becomes intent-only"
`BasePose`→`IntentBuilder` rewrite), which is a structural migration, not a flip.

---

## 1. Current authoring model

Every production helper below is defined in `app/src/main/java/com/monkfitness/app/animation/BasePose.kt`
(unless noted). "Writes nodes directly?" = the helper assigns `node.localPosition` / `node.localRotation`.
"Writes intent?" = the helper assigns a §1.1 carrier (`pose.spineIntent` / `jointIntents` /
`limbTargets` / `headTarget` / `contacts` / `postureIntent` / `contactPrecedence` / `extremityOverrides`).
"Consumed by engine?" = an engine stage (`ConstraintSolver` / `SkeletonPoseFinalizer` / IK) reads the
result during `produceFrame`.

| Helper | Signature (abbrev.) | Writes nodes directly? | Writes intent? | Consumed by engine? | Notes |
|--------|---------------------|------------------------|----------------|---------------------|-------|
| `buildTorso` | `(pelvis, chest, len)` | **Yes** (`chest.localPosition`) | No | FK only | rigid segment |
| `buildHead` | `(neck, head, len, dir)` | **Yes** (`neck`/`head.localPosition`) | No | FK only | geometry math, reused by resolver |
| `buildGaze` | `(neck, head, len, dir)` | No | **Yes** (`pose.headTarget`) | **Yes** — `Finalizer.resolveHeadTarget` (Phase 7 complete) | the only helper already on intent |
| `buildPelvis` | `(pelvis, hipF, hipB, w)` | **Yes** (`hipF`/`hipB.localPosition`) | No | FK only | |
| `buildShoulders` | `(shoulderA, shoulderP, w)` | **Yes** (`shoulderA/P.localPosition`) | No | FK only | |
| `buildChestTwist` | `(chest, rad)` | **Yes** (`chest.localRotation`) | No | FK only | thoracic twist |
| `buildChestSideBend` | `(chest, rad)` | **Yes** (`chest.localRotation`) | No | FK only | |
| `buildChestOrientation` | `(chest, lean, twist, bend)` | **Yes** (`chest.localRotation`) | No | FK only | 3-DOF compose |
| `buildRigidSegment` | `(parent, child, ox,oy,oz)` | **Yes** (`child.localPosition`) | No | FK only | |
| `buildClavicularRotation` | `(clavicle, e,p,r, sign)` | **Yes** (`clavicle.localRotation`) | No | FK only | UNI-7 clavicle |
| `buildLumbarFlexion` | `(lumbar, rad, axis)` | **Yes** (`lumbar.localRotation`) | No | FK only (carried into `reconstructChestFrame`) | Issue E |
| `buildSpineCurve` | `(lower, chest, lr, tr, axis)` | **Yes** (`lower`+`chest.localRotation`) | No | FK only | the documented trunk-lean author; migrates to `spineIntent` |
| `buildWristArticulation` | `(hand, f, d)` | **Yes** (`hand.localRotation`) | No | FK only | UNI-8 |
| `buildAnkleArticulation` | `(ankle, d, i)` | **Yes** (`ankle.localRotation`) | No | FK only | UNI-8 |
| `overrideExtremityOrientation` | `(pose, extremity)` | No | **Yes** (`pose.extremityOverrides`) | Read path exists in Finalizer (`isExtremityAutomatic`) but the set is **never populated** by any pose → always "automatic" | dormant |
| `buildHipFlexion` | `(hip, rad)` | **Yes** (`hip.localRotation`) | No | FK only | |
| `buildHipAbduction` | `(hip, rad, sign)` | **Yes** (`hip.localRotation`) | No | FK only | |
| `buildHipRotation` | `(hip, rad, sign)` | **Yes** (`hip.localRotation`) | No | FK only | |
| `buildHipOrientation` | `(hip, f, a, r, sign)` | **Yes** (`hip.localRotation`) | No | FK only | 3-DOF compose; migrates to `jointIntents` |
| `solveArmIK` / `solveLegIK` | `(world args…, result)` | No (delegates to `SkeletonMath.solveIK`) | No | IK result used by caller | pure solver wrap |
| `solveStraightArmIK` / `solveStraightLegIK` / `solveNearStraightLeg` | `(…)` | No | No | IK result used by caller | pure solver wrap |
| `bakeIkLimb` | `(rootW, targetW, len1, len2, pole, constraint, parentRot, mid, end, buf, straight, contact)` | **Yes** (`mid`+`end.localPosition` via `toLocalDirection`) | **Yes** (`pose.contacts` when `contact != null`) + stamps `maxIkClampAmount`/`boneLengthsVerified` | **Yes** — `ConstraintSolver` reads `pose.contacts` | the IK+contact path; `limbTargets` (the intent equivalent) is NOT used |
| `declarePosture` | `(pose, kind, tol, precedence)` | No | **Yes** (`pose.postureIntent`, `pose.contactPrecedence`) | **Yes** — `ConstraintSolver` (M3/M4) | live §1.1 subset |
| `buildShoulders`/`buildPelvis`/`buildTorso` | (above) | **Yes** | No | FK only | node geometry |

**Summary of the factual picture:**

- **One helper is already intent-based:** `buildGaze` → `headTarget` (consumed by the Finalizer;
  Phase 7 complete). This is the only proof that the intent path *can* work end-to-end.
- **One helper is dual:** `bakeIkLimb` writes both nodes *and* the `contacts` intent carrier (plus
  §1.2 stamps). It does **not** populate `limbTargets` — the intent equivalent of a limb pin is absent.
- **Everything else writes nodes directly** and is consumed only by FK. `spineIntent`, `jointIntents`,
  `limbTargets` carry none of this authoring.

---

## 2. Dead intent carriers

Audit of every §1.1 carrier declared on `SkeletonPose` (`PoseDefinition.kt`). "Written" = any
production/validation pose or helper assigns it (outside `copyFrom`/declaration). "Read" = any engine
stage (`ConstraintSolver`, `SkeletonPoseFinalizer`, IK) consumes it during `produceFrame`. "Current
owner" = the component that today is responsible for populating it.

| Carrier | Written? | Read? | Current owner | Classification |
|---------|----------|-------|---------------|----------------|
| `contacts` | **Yes** (`bakeIkLimb` when `contact != null`) | **Yes** (`ConstraintSolver.solve`) | `bakeIkLimb` (Pose side) | **live** (production/validation) |
| `contactPrecedence` | **Yes** (`declarePosture`) | **Yes** (`ConstraintSolver.applyRootDelta`, F7) | `declarePosture` | **live** |
| `postureIntent` | **Yes** (`declarePosture`) | **Yes** (`ConstraintSolver` seed, F2) | `declarePosture` | **live** |
| `headTarget` | **Yes** (`buildGaze`) | **Yes** (`Finalizer.resolveHeadTarget`) | `buildGaze` | **live** (Phase 7 complete) |
| `extremityOverrides` | **No** (write API `overrideExtremityOrientation` exists, but **no pose calls it**) | Read-path exists (`Finalizer.isExtremityAutomatic`) but always sees empty → constant `true` | intended: `overrideExtremityOrientation`; actual: none | **dormant** (consumer live, producer absent) |
| `spineIntent` | **No** (only declared + `copyFrom`) | **No** | none | **dead** |
| `jointIntents` | **No** (only declared + `copyFrom`) | **No** | none | **dead** |
| `limbTargets` | **No** (only declared + `copyFrom`) | **No** | none | **dead** |
| `motion` | **No** on `SkeletonPose` (written/read only on `PoseBuilder.metadata.motionCurve`, a different object) | — | `PoseBuilder.metadata` | **obsolete** on `SkeletonPose` (field exists, unused) |
| `camera` | **No** on `SkeletonPose` (`PoseBuilder.metadata.camera` is the real one) | **No** on `SkeletonPose` (renderer reads `metadata.camera`) | `PoseBuilder.metadata` | **obsolete** on `SkeletonPose` (field exists, unused) |
| `environment` | **No** on `SkeletonPose` (`PoseBuilder.metadata.environment` is the real one) | **No** on `SkeletonPose` (renderer reads `metadata.environment`) | `PoseBuilder.metadata` | **obsolete** on `SkeletonPose` (field exists, unused) |

**Classification definitions used above:**

- **Live production carrier** — written by a pose/helper and read by an engine stage today.
- **Dormant carrier** — a read path exists in the engine but no producer populates it, so the engine
  sees a constant; the feature is not reachable from current poses.
- **Dead carrier** — neither written nor read anywhere; pure leftover fields.
- **Obsolete carrier** — declared on `SkeletonPose` but the real data lives on `PoseBuilder.metadata`;
  the `SkeletonPose` field is unused.

**Net:** the live subset is exactly `{contacts, contactPrecedence, postureIntent, headTarget}`. The
three carriers M5 set out to make live (`spineIntent`, `jointIntents`, `limbTargets`) are **dead**.
`extremityOverrides` is **dormant**. `motion`/`camera`/`environment` on `SkeletonPose` are **obsolete**.

---

## 3. Required rewrite

This section describes *what architectural work must occur* to migrate production poses from
imperative `SkeletonNode` authoring to declarative §1.1 intent authoring. It lists only **files**,
**helpers**, **engine stages**, and a **migration order**. It proposes no specific solution.

### 3.1 Files involved

- `app/src/main/java/com/monkfitness/app/animation/BasePose.kt` — all node-writing helpers (§1 table)
  must be re-pointed from `node.localPosition`/`node.localRotation` to an `IntentBuilder`.
- `app/src/main/java/com/monkfitness/app/animation/PoseDefinition.kt` — §1.1 carrier declarations;
  likely needs `val`/package-private setters and the `IntentBuilder` holder (per `RFC_INTENT_LAYER.md`
  "compile-time" invariant that only `IntentBuilder` mutates §1.1).
- `app/src/main/java/com/monkfitness/app/animation/SkeletonPose.kt` — if a separate `IntentBuilder`
  type is introduced, the §1.1 section ownership shifts from the pose to the builder.
- `app/src/main/java/com/monkfitness/app/animation/SkeletonPoseFinalizer.kt` — must gain consumers for
  `spineIntent`, `jointIntents`, `limbTargets`, `extremityOverrides` (today it reads only `headTarget`
  and `extremityOverrides` via `isExtremityAutomatic`). Specifically `reconstructChestFrame` currently
  re-derives the chest from geometry; under intent it would expand `SpineCurve` into pelvis+chest
  rotations instead of the pose having written those rotations.
- `app/src/main/java/com/monkfitness/app/animation/ConstraintSolver.kt` — already consumes
  `contacts`/`contactPrecedence`/`postureIntent`; would additionally need to read `limbTargets` for
  any intent-driven limb pinning if `bakeIkLimb` is replaced by `declareLimbTarget`.
- `app/src/main/java/com/monkfitness/app/animation/SkeletonMath.kt` — IK solver (`solveIK` /
  `solveStraightLimb`) currently writes node `localPosition` via callers; under intent the IK stage
  must solve a *fresh* pipeline-owned tree from `limbTargets` rather than mutating pose-authored nodes.
- `app/src/main/java/com/monkfitness/app/animation/SkeletonPipeline.kt` — the stage chain
  (`build` → `solve` → `finalize` → FK) must own the node tree (per `RFC_ENGINE_PIPELINE.md` the tree
  becomes "owned by the pipeline, not the pose"). The IK stage that converts `limbTargets` → node
  `localPosition` is the new stage that does not exist today.
- `app/src/main/java/com/monkfitness/app/poses/*.kt` and
  `app/src/main/java/com/monkfitness/app/validation/poses/*.kt` — every call site of the §1 helpers
  (`buildSpineCurve`, `buildHipFlexion`, `buildHipOrientation`, `buildChest*`, `bakeIkLimb`,
  `buildShoulders`, `buildPelvis`, `buildTorso`, `buildClavicularRotation`, `buildLumbarFlexion`,
  `buildWristArticulation`, `buildAnkleArticulation`, `buildRigidSegment`) must be rewritten to emit
  intent. Per `RFC_INTENT_LAYER.md`, these helpers become thin forwards to `IntentBuilder`
  (`buildSpineCurve` → `builder.spine(...)`, `buildHipOrientation` → `builder.joint(HIP, ...)`,
  `bakeIkLimb`/`declareLimbTarget` → `builder.limbTarget(...)`).
- `app/src/main/java/com/monkfitness/app/animation/PoseBuilder.kt` / `PoseRegistry.kt` — `build()`
  contract changes from "emit a populated node tree" to "populate §1.1 only; `roots` empty/unbuilt"
  (per `RFC_ENGINE_PIPELINE.md` table row 0). Every consumer that calls `build()` and then reads
  `pose.roots`/`pose.joints` directly would be affected.

### 3.2 Helpers that must change

All node-writing helpers in §1 that are **not** already intent-based must be re-pointed:
`buildTorso`, `buildHead`, `buildPelvis`, `buildShoulders`, `buildChestTwist`, `buildChestSideBend`,
`buildChestOrientation`, `buildRigidSegment`, `buildClavicularRotation`, `buildLumbarFlexion`,
`buildSpineCurve`, `buildWristArticulation`, `buildAnkleArticulation`, `buildHipFlexion`,
`buildHipAbduction`, `buildHipRotation`, `buildHipOrientation`, `bakeIkLimb`, `overrideExtremityOrientation`.
`buildGaze` and `declarePosture` are already intent-based and need no authoring change (only the
underlying consumer must already exist — which for `headTarget` it does).

### 3.3 Engine stages that must change or be created

- **IK stage (new or expanded):** converts `limbTargets` into joint `localPosition` on a
  pipeline-owned tree; writes the §1.2 `maxIkClampAmount` / `boneLengthsVerified` / `straightIntentDropped`
  stamps. Today this logic is interleaved inside `bakeIkLimb` (called by poses), not a pipeline stage.
- **ConstraintSolver (expand):** already reads `contacts`/`contactPrecedence`/`postureIntent`;
  must additionally consume `limbTargets` if contact-less limb pins migrate to intent.
- **SkeletonPoseFinalizer (expand):** `reconstructChestFrame` must expand `spineIntent` (instead of
  the pose having written pelvis+chest rotations); must consume `jointIntents` for chest/hip/girdle/
  ankle/wrist relative articulations; must honor `extremityOverrides` (today the read returns a
  constant because nothing populates it). `preConvertPoles` (M4, reserved hook) is the documented
  landing point for the pole→world conversion that currently lives inside `bakeIkLimb`.
- **FK (unchanged):** remains the final propagation step; it already consumes `localPosition`/
  `localRotation`, which the IK/Finalizer stages will have produced from intent.

### 3.4 Migration order (dependency sequence, not a plan)

1. Introduce the `IntentBuilder` type and the compile-time invariant that only it mutates §1.1
   (`RFC_INTENT_LAYER.md` §"Compile-time"). This is a prerequisite for every later step.
2. Re-point `BasePose` helpers to forward into `IntentBuilder` (no behavior change yet, because the
   engine does not yet read the carriers — the builders would also still write nodes, or the carriers
   would be dead until step 3).
3. Implement the IK stage that consumes `limbTargets` on a pipeline-owned tree, and have
   `SkeletonPipeline` own the tree (`Pose.build()` stops emitting nodes).
4. Implement Finalizer consumers for `spineIntent` (replacing `buildSpineCurve`'s node writes in
   `reconstructChestFrame`), `jointIntents` (replacing `buildHipOrientation`/`buildChest*`), and
   `extremityOverrides` (making the dormant consumer real).
5. Migrate each production/validation pose off the node-writing helpers, one family at a time, behind
   the existing pipeline so output stays byte-identical (the M2/M3/M4 invariant: flag flips must not
   change production output).
6. Delete the now-unused node-writing paths in `bakeIkLimb` (the frame-relative/inline node writes)
   and the obsolete `SkeletonPose` fields `motion`/`camera`/`environment` if they remain unused.

---

## 4. Roadmap impact

Classification of **M5, M6, M7** per the framing "dormant implementation / deferred implementation /
new architecture branch," based solely on the facts in §1–§3.

### M5 — §1.1 carriers live (Gap 2)

- **Classification: deferred implementation (which is, in substance, a new architecture branch).**
- Reasoning: M5 requires `spineIntent`/`jointIntents`/`limbTargets` to be written by poses and read by
  engine stages. Today they are **dead** (§2). Bringing them alive requires §3.1–§3.4 (the
  `IntentBuilder` + IK stage + Finalizer consumers + per-pose migration). That is the `BasePose`→
  `IntentBuilder` rewrite already specified in `RFC_INTENT_LAYER.md` — a structural migration, not a
  flag flip. It is therefore **deferred** relative to the M0–M4 flag-flip track, and because it changes
  the authoring contract (poses stop emitting node trees; the pipeline owns the tree), it constitutes a
  **new architecture branch** off the current imperative pipeline. It cannot land as a flag flip, and
  the original RFC wording ("automatic once M2 lands") is factually incorrect (corrected in
  `RFC_GAP_CLOSURE.md` §M5).

### M6 — Validator stamp-only (Gap 6 / Phase 8)

- **Classification: deferred implementation, dependent on the same new architecture branch.**
- Reasoning: M6 removes geometry inference from `ExerciseValidator` and makes every rule read a §1.2
  stamp or §1.1 intent. Two of its sub-items depend directly on §3.3 work that does not exist yet:
  - `validateHipRom`/`femoralTwistDegrees` → read a hip-rom stamp written by `buildHipOrientation` /
    IK/Solver. Today hip orientation is written as a raw node rotation by `buildHipOrientation`; there
    is no stamp. The stamp requires the IK/Finalizer stage from §3.3.
  - `validateBilateralSymmetry` → read left/right target deltas from `limbTargets` intent rather than
    reconstructed node positions. `limbTargets` is **dead** (§2) until M5's branch lands.
  - `validateAngularJointLimits` → read per-joint ROM stamps written by the stage that sets each
    `localRotation`. Those stamps require the intent-driven IK/Finalizer stages (§3.3).
- Because M6's inputs (hip-rom stamp, per-joint ROM stamps, `limbTargets`) are produced only by the
  same `IntentBuilder`/IK/Finalizer work that M5 requires, M6 is **not independently landable** as a
  flag flip. It is deferred onto the same branch; it does not open its own separate branch because its
  dependencies are a strict subset of M5's.

### M7 — Gaze as target / `headTarget` (Gap 7 / F8 / W17)

- **Classification: dormant implementation (already complete), NOT part of the new branch.**
- Reasoning: `headTarget` is **live** (§2): `buildGaze` writes it and `Finalizer.resolveHeadTarget`
  consumes it; Phase 7 is marked complete in `RFC_ENGINE_PIPELINE.md` and `AGENTS.md` (byte-identical to
  the legacy direction path via `HeadTargetBaselineTest`). M7's stated remaining work (`HEAD_TARGET_ENABLED`
  flag, `buildGaze` helper, BaseLunge/BaseVerticalPull migration) is **already done** — the flag was
  removed after byte-identity was proven. So M7 is a **dormant/completed** item: the implementation
  exists and is active; there is no new branch and no deferred rewrite required. It is listed here only
  to record that, unlike M5/M6, it does not block on the `IntentBuilder` rewrite.

### Why M5/M6 form a new architecture branch (and M7 does not)

- M5 changes the **authoring contract**: poses cease to be node-tree emitters and become §1.1
  recorders; the pipeline becomes the tree owner. That contract change is the `IntentBuilder` rewrite
  (`RFC_INTENT_LAYER.md`), which did not exist when M0–M4 (flag flips over the imperative pipeline) were
  landed. It is "new" relative to the merged track.
- M6 is a **consumer** of M5's outputs (stamps + `limbTargets`); it adds no new contract of its own, so
  it rides the same branch rather than opening a second one.
- M7 touches only the already-live `headTarget` path and is complete; it requires nothing from the
  branch, so it is excluded from it.

**Roadmap status:** unchanged by this document. M5 remains BLOCKED and M6 remains deferred-on-M5 in
`RFC_GAP_CLOSURE.md`; M7 remains complete. No flag was flipped and no production code was modified.

**Authoritative Branch B design:** `RFC_DECLARATIVE_POSE_AUTHORING.md` — the future authoring architecture
defined independently of the Runtime (problem statement, design goals, authoring API, engine consumption,
migration strategy, runtime guarantees, risks, and the fresh B0–B6 phase plan). This document is the
current-state audit it builds on; that document is the target design.
