# Capability Gap Report — Architecture v2 vs. Roadmap vs. Engine

**Audit date:** 2026-07-16
**Scope:** Reconcile `ARCHITECTURE_V2.md` (spec), `ARCHITECTURE_V2_ROADMAP.md` (phases),
and the *actually implemented* engine code in `app/src/main/java/com/monkfitness/app/animation`.
**Method:** Static read of engine + pose source; no build executed. Reconciliation only —
no redesigns, no code.

> [!IMPORTANT]
> **STATUS: SUPERSEDED (historical).** This 2026-07-16 audit described a mid-cleanup state in
> which the §1.1 carriers were dead and PHASE 2/3 behaviour was flag-disabled. All of that has
> since been resolved: the carriers are live (Branch B), the posture/finalizer authority is
> unconditional, and the `EngineFlags` object was deleted in the cleanup (Phases A–G of
> `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md`). Retained for archaeology only.

## TL;DR

The spec (`ARCHITECTURE_V2.md` §3) defines a **declarative pipeline** in which the Pose only
populates the §1.1 intent carrier and the MonkEngine runtime (IK -> ConstraintSolver -> Finalizer -> FK)
resolves geometry. **That pipeline was not fully wired at audit time.** Every production pose still
*imperatively hand-builds* the full `SkeletonNode` hierarchy inside `Pose.build()` and returns a
complete pose. At audit time the §1.1 intent fields (`spineIntent`, `limbTargets`, `jointIntents`)
were **declared but never written by poses and never read by the MonkEngine runtime** (dead carriers). The
spec's PHASE 2/3 behavior existed as code inside `ConstraintSolver`/`SkeletonPoseFinalizer` but was
**globally disabled** (`EngineFlags.SOLVER_OWNS_POSTURE = false`, `FINALIZER_OWNS_CONVERSION = false`
at the time), so the legacy path ran verbatim. *(All of this is now resolved — see banner above.)*

**Consequence (HISTORICAL):** the roadmap marked Phases 0–3 (and 4–7) "COMPLETE", but those marks
described *structures and helpers that exist*, **not** an integrated architecture-v2 execution
model. The
foundational integration (a Pose->MonkEngine pipeline that consumes §1.1) does not exist. Phases 4–7
that *were* genuinely done are the **pose-side helper migrations** (counter-rotation deletion,
single spine-intent call, hip helper, girdle/IK already correct) — real, but pose refactors, not
evidence of the MonkEngine pipeline.

---

## Gap 1 — MonkEngine pipeline / orchestrator (spec §3, PHASE 0–5)
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

## Gap 3 — PHASE 2 posture authority not active (HISTORICAL — now resolved)
**Status (at audit time): PARTIALLY BLOCKED (implementation existed, was flag-disabled, and uncalled by production poses).** *Resolved: the posture seed is now unconditional; the `EngineFlags` object was deleted in the cleanup.*

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
**Status: RESOLVED (M1/Gap5 — deprecated `bakeIkLimb(parentRotation, poleLocal)` overload deleted).**

- **Existing engine support:** `SkeletonMath.deriveDefaultPole`, `bonesExact` invariant,
  `bakeIkLimb` world-only overload (derives default pole, ANDs `boneLengthsVerified`).
- **Completed work:** The frame-relative `bakeIkLimb(parentRotation, poleLocal)` overload was
  removed from `BasePose.kt`. Its **actual** callers (the report's "exactly 2" count was stale —
  the `parentRotation`/`poleLocal` argument shape also appears in `solveArmIK`, which is a
  *different* overload) were migrated to the world-only overload by converting the authored
  local pole with `SkeletonMath.toWorldDirection(poleLocal, middleNode.parent!!.worldRotation,
  tempPoleWorld)` before the call. Migrated files: `BasePushUpPose`, `PikePushUpPose`,
  `StaticBirdDogHoldPose`, `BirdDogPose`, `AlternatingBirdDogPose`, `BaseVerticalPullPose`,
  `BaseLungePose` (`bakeLeg`/`bakeArm`). The migration is behavior-preserving: the deleted
  overload converted the pole with `middleNode.parent?.worldRotation ?: parentRotation`, which is
  exactly what the call sites now do explicitly (the caller-passed `parentRotation` was only a
  fallback that the overload ignored whenever `middleNode.parent` was non-null).
- **Remaining deprecated frame-relative overloads (separate, out of scope for M1/Gap5):** the
  `solveArmIK(shoulderW, targetHand, …, poleLocal, parentRotation, …)` and `solveLegIK(…)`
  overloads are still retained and still called from `BaseThoracicPose.kt:138`
  (`solveArmIK(…, poleLocal, chest!!.worldRotation, …)`). Deleting those is a distinct cleanup.
- **Missing data structures:** None.
- **Missing execution phase:** None (F4 contract met by the world overload).
- **Required prerequisite phase:** None (independent cleanup).
- **Estimated implementation scope:** **Small** (done).
- **Blocked / partial:** **Resolved.**

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
**Status: IN PROGRESS (carrier + resolver + all gaze sites migrated; flag-default verification + CI baseline diff remaining).**

- **Existing engine support:** `buildHead(neck, head, neckLength, headDir)` consumed by the pose;
  now wrapped by `buildGaze(neck, head, neckLength, gazeDir)` which records a synthetic `HeadTarget`
   (neck world pos + gazeDir·100) and still called `buildHead` while the `HEAD_TARGET_ENABLED` flag
   (since removed) was false. The Finalizer's `resolveHeadTarget` consumes `headTarget` (reusing the
   head-orientation math) when the flag was on. *(Resolved: the flag and `buildHead` branch were
   deleted in the cleanup; `resolveHeadTarget` is now the sole head/neck writer.)*
- **Missing APIs:** none (added `HeadTarget` carrier + `headTarget` on `SkeletonPose` §1.1 +
  `copyFrom` propagation; `HeadTarget_ENABLED` flag; `BasePose.buildGaze`; `SkeletonPoseFinalizer.resolveHeadTarget`).
- **Missing data structures:** none.
- **Missing execution phase:** the Finalizer resolver stage is present and consumes `headTarget` when
  the flag is on; the flag defaults to **false** (legacy direction path authoritative) until the
  resolver is diffed against the baseline in CI.
- **Required prerequisite phase:** Gap 1 (resolver needs the pipeline context) — though a standalone
  `buildGaze` helper + Finalizer resolver were added first, gated by the flag.
- **Estimated implementation scope:** **Small** (remaining: flip flag, run baseline diff, delete legacy branch).
- **Blocked / partial:** no longer blocked on carrier/resolver; remaining work is verification-only.

---

## Resolved / NOT gaps (verified, do not re-open)

| Item | Evidence |
|---|---|
| Phase 4 — limb counter-rotation deletion (G1) | 60 `rotAround` lean-cancel calls deleted; tests baseline matched (commit history + grep). |
| Phase 5 — single spine-intent call (G4/G5) | `buildSpineCurve(pelvis, chest, …)` used in the 3 genuine dual-write sites (PR #127). NOTE: uses the helper directly, does **not** populate `spineIntent` (see Gap 2). |
| Phase 6 — hip helper (G7/A4) | `buildHipFlexion`/`buildHipOrientation` exist; zero raw `hip*.localRotation.set` remain (PR #128). |
| A6 — girdle/shoulders (G6) | PikePushUp now routes shoulders through `buildShoulders`+FK and feeds the FK-derived `shoulderA/shoulderP.worldPosition` to `bakeIkLimb` (no hand-computed `rotAround` chest-frame shoulder placement in PikePushUp). `BasePushUpPose` (shared push-up base) still has a `rotAround` shoulder-computation at its IK-root setup — that is shared push-up logic, not the Phase-7-named PikePushUp G6 item, and is deferred pending a per-variant baseline diff. |
| W1 — extremity derivation | `extremityOverrides`/`ExtremityOrientationMode` present; engine derives heel/toe/palm (AUTOMATIC), pose opts out via `overrideExtremityOrientation`. |
| F3 — stamp ownership | `SkeletonPose` single carrier; `copyFrom` propagates §1.1 + §1.2. |
| F5/F6 — bone-length + default pole | `SkeletonMath.bonesExact`/`deriveDefaultPole` present and used by `bakeIkLimb`. |

## Cross-cutting observation

The roadmap's per-phase "COMPLETE" labels are **structural**, not **behavioral**: they mark that a
helper, data structure, or flag-gated module corresponding to the phase *exists in the tree*. They
do **not** mean the architecture-v2 execution model is live. The single biggest reconciliation
finding is **Gap 1**: until a Pose->MonkEngine pipeline consumes §1.1, the spec's foundational contract
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
| 7 Gaze `headTarget` | **IN PROGRESS** (carrier + resolver + all sites migrated; flag flip + CI baseline diff remaining) | — |
