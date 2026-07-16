# Capability Gap Report — Architecture v2 vs. Roadmap vs. Engine

**Audit date:** 2026-07-16
**Scope:** Reconcile `ARCHITECTURE_V2.md` (spec), `ARCHITECTURE_V2_ROADMAP.md` (phases),
and the *actually implemented* engine code in `app/src/main/java/com/monkfitness/app/animation`.
**Method:** Static read of engine + pose source; no build executed. Reconciliation only —
no redesigns, no code.

## TL;DR

The spec (`ARCHITECTURE_V2.md` §3) defines a **declarative pipeline** in which the Pose only
populates the §1.1 intent carrier and the Engine (IK -> ConstraintSolver -> Finalizer -> FK)
resolves geometry. **That pipeline is not wired in.** Every production pose still *imperatively
hand-builds* the full `SkeletonNode` hierarchy inside `Pose.build()` and returns a complete pose.
The §1.1 intent fields (`spineIntent`, `limbTargets`, `jointIntents`) are **declared but never
written by poses and never read by the engine** (dead carriers). The spec's PHASE 2/3 behavior
exists as code inside `ConstraintSolver`/`SkeletonPoseFinalizer` but is **globally disabled**
(`EngineFlags.SOLVER_OWNS_POSTURE = false`, `FINALIZER_OWNS_CONVERSION = false`), so the legacy
path runs verbatim.

**Consequence:** the roadmap marks Phases 0–3 (and 4–7) "COMPLETE", but those marks describe
*structures and helpers that exist*, **not** an integrated architecture-v2 execution model. The
foundational integration (a Pose->Engine pipeline that consumes §1.1) does not exist. Phases 4–7
that *were* genuinely done are the **pose-side helper migrations** (counter-rotation deletion,
single spine-intent call, hip helper, girdle/IK already correct) — real, but pose refactors, not
evidence of the engine pipeline.

---

## Gap 1 — Engine pipeline / orchestrator (spec §3, PHASE 0–5)
**Status: BLOCKED (entire execution model missing).**

- **Existing engine support:**
  - `SkeletonPose` carries §1.1 (+§1.2 stamps) — data only.
  - `ConstraintSolver` (`object`) with `solve(pose, definition)` — implements posture seeding,
    contact-precedence resolution, CCD, inter-frame smoothing (but see Gap 3).
  - `SkeletonPoseFinalizer.finalize(pose)` — calls `ConstraintSolver.solve` for contact poses,
    then FK-flattens; `preConvertPoles()` hook present (see Gap 4).
  - `bakeIkLimb` in `BasePose` performs IK **during `Pose.build()`** (i.e. inside the pose, not in
    an engine stage).
  - `SkeletonEngine` — **only a bone-rendering lookup table**; no pipeline logic.
- **Missing APIs:** No `Engine` orchestrator that takes a `PoseBuilder.build()` intent result and
  runs IK -> Solver -> Finalizer -> FK in stages. `Pose.build()` is the de-facto pipeline and it does
  everything inline in the pose.
- **Missing execution phase:** All of spec §3. Poses bypass it by constructing `SkeletonNode`s
  directly and calling `SkeletonPose.fromHierarchy`.
- **Missing data structures:** None (carriers exist); the gap is *consumption*, not storage.
- **Required prerequisite phase:** None — this is the foundation. Everything else depends on it.
- **Estimated implementation scope:** **Large.** Requires poses to stop hand-building nodes and
  instead declare intents; a new orchestrator to run the §3 stages; rewiring of
  `SkeletonRenderer`/`SkeletonSnapshotRenderer`/`ValidationPoseLauncher` to invoke the pipeline
  instead of `pose.build()` + `finalizer.finalize()`.
- **Blocked / partial:** **Blocked.** No partial integration exists.

## Gap 2 — §1.1 intent carriers are dead (spec §1.1: `spineIntent`, `limbTargets`, `jointIntents`)
**Status: PARTIALLY BLOCKED (carriers exist; writers + readers missing).**

- **Existing engine support:** Fields declared on `SkeletonPose` (`spineIntent: SpineCurve`,
  `limbTargets: MutableList<WorldTarget>`, `jointIntents: MutableList<RelativeArticulation>`);
  `copyFrom` copies them. `postureIntent` + `contactPrecedence` ARE populated (via `declarePosture`)
  and read inside `ConstraintSolver.solve` (but see Gaps 1 & 3).
- **Missing APIs:** No pose-side authoring of `spineIntent`/`limbTargets`/`jointIntents` (poses call
  helpers like `buildSpineCurve(pelvis, chest, …)` directly, never touch `spineIntent`). No
  engine-side reader that consumes them to drive IK/FK.
- **Missing data structures:** None (declared).
- **Missing execution phase:** The intent->geometry binding step (would live in the Gap-1 pipeline).
- **Required prerequisite phase:** Gap 1.
- **Estimated implementation scope:** **Medium** once Gap 1 exists (populate carriers in poses;
  consume in IK/Solver/Finalizer).
- **Blocked / partial:** **Partially blocked** — structurally present, behaviorally inert.

## Gap 3 — PHASE 2 posture authority not active (`EngineFlags.SOLVER_OWNS_POSTURE = false`)
**Status: PARTIALLY BLOCKED (implementation exists, globally disabled, and uncalled by production poses).**

- **Existing engine support:** `ConstraintSolver.seedRootFromPostureIntent`,
  `postureSeedY(B1.1 formulas)`, `applyRootDelta(…, pose.contactPrecedence)` (F7), inter-frame
  smoothing cache (F9), `declarePosture` helper on `BasePose`/`BaseValidationPose`.
- **Missing APIs:** The seeding branch is gated by `SOLVER_OWNS_POSTURE`; with it `false` the solver
  does legacy relaxation and ignores `postureIntent`. To activate: flip flag + ensure production
  poses call `declarePosture` (currently **zero** production poses do — only validation poses).
- **Missing data structures:** None.
- **Missing execution phase:** None (code present); requires Gap 1 wiring so the solver is the
  *sole* root mover and poses stop hand-computing `pelvisY`/`pelvisX`.
- **Required prerequisite phase:** Gap 1 (poses must stop authoring root transform).
- **Estimated implementation scope:** **Small–Medium** (flag flip + migrate production poses to
  `declarePosture`; re-tune per pose).
- **Blocked / partial:** **Partially blocked** — code done, integration off.

## Gap 4 — PHASE 3 finalizer exclusive conversion not active (`FINALIZER_OWNS_CONVERSION = false`)
**Status: PARTIALLY BLOCKED (hook present, no-op; conversion still inline).**

- **Existing engine support:** `SkeletonPoseFinalizer.preConvertPoles()` (reserved hook, no-op when
  flag off), `reconstructChestFrame()` (real fallback chest-frame reconstruction, respects the
  read-only contact guard when flag on). Flag documented as the global flip.
- **Missing APIs:** `preConvertPoles` owns nothing today; the frame-relative `bakeIkLimb` overload
  still converts its own pole (see Gap 5), so conversion is *not* centralized in the finalizer.
- **Missing data structures:** None.
- **Missing execution phase:** The "single conversion entry point" guarantee is unmet while the
  legacy overload survives.
- **Required prerequisite phase:** Gap 5 (delete deprecated overload) + Gap 1.
- **Estimated implementation scope:** **Small** once Gap 5 done (flip flag; no behavior change by
  design).
- **Blocked / partial:** **Partially blocked.**

## Gap 5 — PHASE 1 deprecated frame-relative IK overload not removed (spec F4)
**Status: PARTIALLY BLOCKED (world-only path exists; legacy overload retained).**

- **Existing engine support:** `SkeletonMath.deriveDefaultPole`, `bonesExact` invariant,
  `bakeIkLimb` world-only overload (derives default pole, ANDs `boneLengthsVerified`).
- **Missing APIs:** Deletion of the frame-relative `bakeIkLimb(parentRotation, poleLocal)` overload
  and migration of its **~16 remaining pose callers (VERIFIED at implementation, across 7 files): `BaseLungePose.kt` (2, literal arg names) + `PikePushUpPose`/`BirdDogPose`/`BasePushUpPose`/`BaseVerticalPullPose`/`AlternatingBirdDogPose`/`StaticBirdDogHoldPose` (14, frame-relative argument order).
  `BaseThoracicPose.kt`** — these are the only poses that pass the `parentRotation`/`poleLocal`
  argument shape. The roadmap's "~18" estimate is incorrect; the 64 total `bakeIkLimb(` call sites
  across poses include the world overload and other variants.
- **Missing data structures:** None.
- **Missing execution phase:** None (F4 contract met by the world overload; cleanup pending).
- **Required prerequisite phase:** None (independent cleanup).
- **Estimated implementation scope:** **Small–Medium** (migrate ~16 verified callers across 7 production pose files to the world overload, preserving behavior via `toWorldDirection(poleLocal, parentRotation)`; see RFC v2 consistency changelog — the original "exactly 2" estimate was a grep artifact).
- **Blocked / partial:** **Partially blocked** — functional but not finalized.

## Gap 6 — PHASE 8 validator still reconstructs geometry (spec F-observer / §2.6)
**Status: PARTIALLY BLOCKED (reads stamps BUT also infers angles from nodes).**

- **Existing engine support:** Validator reads stamps `maxIkClampAmount`, `rootTranslationDelta`,
  `rootRotationDelta`, `boneLengthsVerified` (§1.2).
- **Missing APIs:** Removal of angle/geometry inference helpers. `ExerciseValidator` still calls
  `SkeletonMath.toLocalDirection` (line 829), `angleBetweenDegrees` (840), and reconstructs hip
  rotation via `atan2` (928–933). These are post-hoc geometry derivations the spec says Validation
  must not do.
- **Missing data structures:** None.
- **Missing execution phase:** None (validator exists); cleanup pending.
- **Required prerequisite phase:** None (independent of pipeline).
- **Estimated implementation scope:** **Medium** (replace inferred-angle checks with stamp-only
  checks; may need new stamps).
- **Blocked / partial:** **Partially blocked.**

## Gap 7 — Gaze as `headTarget` (spec §1.1 "Gaze = head target"; A7/F8)
**Status: BLOCKED (no field, no resolver).**

- **Existing engine support:** `buildHead(neck, head, neckLength, headDir)` consumes a *direction*
  vector authored by the pose (e.g. `BaseLungePose:165`, `BaseVerticalPullPose:168` counter-rotate
  the UP vector by the lean sum via `rotAround`). No target-resolution.
- **Missing APIs:** No `headTarget` field on `SkeletonPose`/`PoseContext`; no engine resolver that
  derives neck/head from a world target; `headTarget`/`buildGaze`/`EyeTarget` do not exist anywhere
  in the codebase (verified by exhaustive grep).
- **Missing data structures:** `headTarget: WorldTarget?` (or similar) on §1.1.
- **Missing execution phase:** Finalizer/engine step that resolves neck/head from `headTarget`
  (would live in the Gap-1 pipeline).
- **Required prerequisite phase:** Gap 1 (resolver needs the pipeline context) — though a standalone
  `buildGaze(targetWorld)` helper could be added first.
- **Estimated implementation scope:** **Medium** (add carrier + resolver + retrofit the 2 gaze
  counter-rotation sites).
- **Blocked / partial:** **Blocked** on engine resolver; the 2 pose sites are a known open leak
  (documented in `MIGRATION_RULES.md` A7).

---

## Resolved / NOT gaps (verified, do not re-open)

| Item | Evidence |
|---|---|
| Phase 4 — limb counter-rotation deletion (G1) | 60 `rotAround` lean-cancel calls deleted; tests baseline matched (commit history + grep). |
| Phase 5 — single spine-intent call (G4/G5) | `buildSpineCurve(pelvis, chest, …)` used in the 3 genuine dual-write sites (PR #127). NOTE: uses the helper directly, does **not** populate `spineIntent` (see Gap 2). |
| Phase 6 — hip helper (G7/A4) | `buildHipFlexion`/`buildHipOrientation` exist; zero raw `hip*.localRotation.set` remain (PR #128). |
| A6 — girdle/shoulders (G6) | Shoulders IK-rooted + `bakeIkLimb`'d; `buildShoulders` exists and used where applicable. No hand-computed chest-frame shoulder placement. **Already conformant.** |
| W1 — extremity derivation | `extremityOverrides`/`ExtremityOrientationMode` present; engine derives heel/toe/palm (AUTOMATIC), pose opts out via `overrideExtremityOrientation`. |
| F3 — stamp ownership | `SkeletonPose` single carrier; `copyFrom` propagates §1.1 + §1.2. |
| F5/F6 — bone-length + default pole | `SkeletonMath.bonesExact`/`deriveDefaultPole` present and used by `bakeIkLimb`. |

## Cross-cutting observation

The roadmap's per-phase "COMPLETE" labels are **structural**, not **behavioral**: they mark that a
helper, data structure, or flag-gated module corresponding to the phase *exists in the tree*. They
do **not** mean the architecture-v2 execution model is live. The single biggest reconciliation
finding is **Gap 1**: until a Pose->Engine pipeline consumes §1.1, the spec's foundational contract
("Pose = intent, Engine = geometry") is not realized, and Gaps 2–4 are direct consequences of it.

## Blocker summary (for planning)

| Gap | Blocked? | Blocks |
|---|---|---|
| 1 Pipeline/orchestrator | **Blocked** | 2, 3, 4, 7 |
| 2 Dead §1.1 carriers | Partial | — (consequence of 1) |
| 3 Solver inactive | Partial | — (needs 1) |
| 4 Finalizer inactive | Partial | — (needs 1 + 5) |
| 5 Deprecated IK overload | Partial | 4 |
| 6 Validator inference | Partial | — (independent) |
| 7 Gaze `headTarget` | **Blocked** | — (needs 1 or standalone helper) |
