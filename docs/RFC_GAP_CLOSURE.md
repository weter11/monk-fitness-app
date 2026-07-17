# RFC — Architecture v2 Gap Closure: Validator Migration · headTarget · bakeIkLimb Removal · Cleanup

**Status:** Draft specification (no code).
**Author:** Principal Engine Architect.
**Addresses:** the remaining Architecture v2 gaps from `CAPABILITY_GAP_REPORT.md`:
- **Gap 5** — deprecated frame-relative `bakeIkLimb` overload removal (spec F4).
- **Gap 6 / Phase 8** — Validator reads stamped state only; drops post-hoc angle inference.
- **Gap 3** — ConstraintSolver posture authority activation (`SOLVER_OWNS_POSTURE`).
- **Gap 4** — SkeletonPoseFinalizer exclusive conversion activation (`FINALIZER_OWNS_CONVERSION`).
- **Gap 7** — `headTarget` architecture (gaze-as-target, F8/W17).
- **State ownership, deprecation strategy, compatibility strategy, final cleanup.**
**Companion RFCs:** `RFC_ENGINE_PIPELINE.md` (Gap 1), `RFC_INTENT_LAYER.md` (Gap 2),
`RFC_EXECUTION_CONTRACT.md` (stage execution). This document is the **rollout + closure** plan that
sequences those designs into merged, flag-gated phases with rollback.
**Scope:** implementation specification only. No code. Maximum detail on dependency/migration graphs,
rollout, rollback, risk, testing, invariants.

---

## 0. Current-state anchor (verified, not assumed)

- **`bakeIkLimb` overloads** (`BasePose.kt:316` and `:343`):
  - World overload `bakeIkLimb(rootWorldPos, targetWorldPos, L1, L2, pole: Vector3 (world), constraint, parentRot, middle, end, ikBuffer, straight, contact)` — **kept**.
  - Frame-relative overload `bakeIkLimb(rootWorldPos, targetWorldPos, L1, L2, parentRotation, poleLocal, constraint, middle, end, ikBuffer, straight, contact)` — converts `poleLocal→worldPole` via `toWorldDirection(poleLocal, parentRot)` then delegates to the world overload (where `parentRot = middleNode.parent?.worldRotation ?: parentRotation`). **Deleted in PR #131 (commits refactor/m1-bakeiklimb-world-only, merged).** Real migration scope: **~16 call sites across 7 production pose files** — `BaseLungePose.kt` (2, literal `parentRotation`/`poleLocal` arg names) + `PikePushUpPose`, `BirdDogPose`, `BasePushUpPose`, `BaseVerticalPullPose`, `AlternatingBirdDogPose`, `StaticBirdDogHoldPose` (14 sites passing `chest!!.worldRotation`/`pelvis!!.worldRotation` as the 5th argument in the frame-relative argument order). The earlier RFC estimate of "exactly 2" was WRONG: it came from a grep that matched only the literal parameter names `parentRotation`/`poleLocal` and missed the ~14 sites that pass *variables* (e.g. `chest!!.worldRotation`, `armAPoleLocal`) in the same argument order. The roadmap's "~18" estimate is also incorrect. The `BaseValidationPose` copy of the overload had ZERO callers (orphan) and was deleted.
- **`ExerciseValidator`** already reads stamps (`maxIkClampAmount` L625, `rootTranslationDelta` L760,
  `rootRotationDelta` L768) **but also reconstructs geometry**: `validateHipRom` (L812) reads
  `pose.getJointRotation(Joint.PELVIS)` and `femoralTwistDegrees(pose.getJointRotation(hip))` (L896,
  `atan2` at L933); `limbMiddleAngleDegrees` (L937) and `validateAngularJointLimits` (L635) read node
  rotations; `validateBilateralSymmetry` (L536) reads node positions. These are the Gap-6 inference
  sites to replace with stamp/intent reads.
- **`headTarget`**: **zero** references in the codebase (confirmed) — fully absent; Gap 7 is greenfield.
- **Solver/Finalizer flags**: `SOLVER_OWNS_POSTURE=false`, `FINALIZER_OWNS_CONVERSION=false` (verified
  earlier) — Gaps 3/4 are code-complete but globally disabled.

---

## 1. Dependency graph (closure subsystems)

```
                 ┌─────────────────────────────────────────────────────┐
                 │              SkeletonPipeline (RFC_ENGINE_PIPELINE)  │
                 └─────────────────────────────────────────────────────┘
                    │ drives (ordered)        │ reads flags
        ┌───────────┼───────────────┬─────────┼───────────────┐
        ▼           ▼               ▼         ▼               ▼
   [IkStage]   [ConstraintSolver]  [Finalizer]   [Validator]     [IntentLayer]
   (Gap 5)     (Gap 3)             (Gap 4)      (Gap 6)         (Gap 2/7)
        │           │               │            │               │
        └───────────┴───────┬───────┴────────────┴───────────────┘
                            ▼
                   SkeletonPose (§1.1 frozen / §1.2 staged)
                            │
                            ▼
                   Render (reads §1.2 only)

Gap 7 (headTarget) attaches to [IntentLayer] (carrier) + [Finalizer] (resolver).
```
**Forbidden edges (enforced):**
- Validator → any stage (observer only; returns `ValidationReport`).
- Pose → `SkeletonMath.solveIK` / node writing (after M2; compile error on deletion).
- Finalizer → Solver (re-entrancy abolished, RFC_EXECUTION_CONTRACT §5).
- `headTarget` resolver in any stage other than Finalizer.
- `bakeIkLimb` frame-relative overload called from anywhere after its deletion (compile error).

**Subsystem dependencies for closure:**
- Gap 5 (bakeIkLimb removal) **precedes** Gap 4 (Finalizer conversion) — the deprecated overload is the
  last caller that converts its own pole; deleting it lets `preConvertPoles` own 100% of conversion.
- Gap 3 (Solver) + Gap 4 (Finalizer) **require** Gap 1 (pipeline) active — they are stages the pipeline
  calls; without the pipeline they are never invoked in order.
- Gap 6 (Validator) is **independent** of 1–5 (it already runs); it only changes *what* it reads.
- Gap 7 (headTarget) requires Gap 2 (Intent Layer) carrier + Finalizer resolver; can land after M2.
- Gaps 3/4/5 are **preconditions** for declaring Phases 2/3/1 "behaviorally complete" (the roadmap's
  "COMPLETE" was structural; this RFC makes them behavioral).

---

## 2. Migration graph (phases → gaps → flags)

```
M0  scaffold pipeline ............. [Gap 1] PIPELINE_ACTIVE=false      (no behavior change)  [DONE]
M1  IK extraction ................. [Gap 5] IK_WORLD_ONLY=true          (delete frame-relative overload)
M2  pipeline owns stages .......... [Gap 1] PIPELINE_ACTIVE=true        (Finalizer stops calling Solver; renderers re-pointed)  [DONE]
M3  Solver authority .............. [Gap 3] SOLVER_OWNS_POSTURE=true   (poses adopt declarePosture)
M4  Finalizer authority ........... [Gap 4] FINALIZER_OWNS_CONVERSION=true
M5  §1.1 carriers live ............ [Gap 2] (automatic once M2 lands)
M6  Validator stamp-only .......... [Gap 6] VALIDATOR_STAMP_ONLY=true  (remove geometry inference)
M7  headTarget .................... [Gap 7] (carrier + Finalizer resolver; own flag HEAD_TARGET_ENABLED)
M8  deprecation purge + cleanup ... [all]  remove @Deprecated, legacy bridges, flags→const true
```
> **M2 shipped scope (2026-07-17):** the flip + stage-chain ownership + renderer re-pointing cut
> described in §M2 STATUS. The RFC prose "Pose becomes intent-only / `BasePose`→`IntentBuilder`" is a
> larger superset deferred to the Pose intent-only follow-up (requires the §1.1 Intent Layer, M5).
> The ordering fix — single owner of Solver+Finalizer, no Finalizer→Solver re-entrancy — is the
> substantive M2 deliverable and is what unblocks M3/M4 safely.
**Per-gap → phase map:**
| Gap | Phase | Flag flipped | Prereq phases |
|---|---|---|---|
| 5 | M1 | `IK_WORLD_ONLY` | M0 |
| 1+2 | M2 | `PIPELINE_ACTIVE` | M0, M1 |
| 3 | M3 | `SOLVER_OWNS_POSTURE` | M2 |
| 4 | M4 | `FINALIZER_OWNS_CONVERSION` | M2, M1 |
| 6 | M6 | `VALIDATOR_STAMP_ONLY` | M2 (BLOCKED until engine produces stamps; M3/M4 optional for new ROM stamps) |
| 7 | M7 | `HEAD_TARGET_ENABLED` | M2 (Intent Layer) |
| cleanup | M8 | flags removed | M1–M7 green |

---

## 2b. Milestone dependency graph (per-milestone — Issue 3)

The audit correctly identified that M6 (Validator stamp-only) is **NOT independent**: the Validator
can only consume stamps once the engine stages *actually produce* them. Under `PIPELINE_ACTIVE=false`
(M0) the legacy path does not guarantee stamp production, so M6 is **BLOCKED** until M2. Each
milestone below lists Required / Optional / Independent / Blocked predecessors.

```
M0  scaffold pipeline
    Required:    (none)          Optional: (none)   Independent: M1–M8   Blocked by: (none)
        │
        ▼
M1  remove deprecated bakeIkLimb overload          [Gap 5]
    Required: M0     Optional: (none)   Independent: M3,M4,M5,M7   Blocked by: (none)
        │
        ▼
M2  Pose intent-only + pipeline drives stages       [Gap 1 + Gap 2]
    Required: M1     Optional: (none)   Independent: M7            Blocked by: (none)
        │  ← engine now PRODUCES stamps (maxIkClampAmount, root*Delta, boneLengthsVerified)
        ▼
M3  Solver authority (SOLVER_OWNS_POSTURE)          [Gap 3]
    Required: M2     Optional: (none)   Independent: M4,M5,M7      Blocked by: (none)
        │
        ▼
M4  Finalizer authority (FINALIZER_OWNS_CONVERSION) [Gap 4]
    Required: M2, M1 Optional: (none)   Independent: M5,M7         Blocked by: (none)
        │
        ▼
M5  §1.1 carriers live (automatic after M2)         [Gap 2]
    Required: M2     Optional: (none)   Independent: M7            Blocked by: (none)
        │
        ▼
M6  Validator stamp-only (VALIDATOR_STAMP_ONLY)     [Gap 6 / Phase 8]
    Required: M2     Optional: M3,M4   Independent: (none)
    Blocked by: M0 with PIPELINE_ACTIVE=false (legacy path does not guarantee stamp production)
        │
        ▼
M7  headTarget gaze-as-target                       [Gap 7]
    Required: M2     Optional: (none)   Independent: (none)        Blocked by: (none)
        │
        ▼
M8  deprecation purge + final cleanup
    Required: M1,M2,M3,M4,M5,M6,M7   Optional: (none)   Independent: (none)   Blocked by: (none)
```

**Rollout order (linear, no interleaving):** M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8. Phases marked
Independent of each other MAY ship as parallel PRs *after* their Required predecessor merges, but MUST
NOT ship before it. M6 is explicitly **BLOCKED** until M2 (engine produces stamps).

---

## 3. Rollout order (detailed, with exact steps per phase)

### M0 — Scaffold `SkeletonPipeline` (Gap 1, no behavior change)
1. Add `SkeletonPipeline` with `PIPELINE_ACTIVE=false`.
2. `produceFrame` in legacy mode = today's path (`pose.build()` → `finalizer.finalize()` → optional
   validator). All consumers unchanged.
3. **Gate:** CI green on full suite. No pose touched.

> **STATUS: COMPLETE.** `SkeletonPipeline` added (`animation/SkeletonPipeline.kt`). M0 shipped it
> behind `PIPELINE_ACTIVE=false`; **M2 subsequently flipped the default to `true`** and wired the
> stage chain (see M2 below). `produceFrame` runs `build()` → `ConstraintSolver.solve` (contacts
> only) → `finalizer.finalize` (FK) → optional validator, with `produceFrameValidated` carrying the
> previous/pre-previous history for the dynamics rules. The constructor asserts the coherence
> invariant (`FINALIZER_OWNS_CONVERSION ⇒ PIPELINE_ACTIVE`) fail-fast. **Zero consumers changed in
> M0**; renderers were re-pointed in M2.

### M1 — `bakeIkLimb` frame-relative overload removal (Gap 5, F4)  **[authoritative scope — identical to RFC_ENGINE_PIPELINE §6 M1]**
1. **Scope (resolved, no contradiction with RFC_ENGINE_PIPELINE):** M1 does ONLY one thing — delete the
   frame-relative `bakeIkLimb(rootWorldPos, targetWorldPos, L1, L2, parentRotation, poleLocal, …)`
   overload (`BasePose.kt:316`) and migrate its **~16** call sites across 7 production pose files (verified at implementation in PR #131: 2 in `BaseLungePose.kt` with literal arg names + 14 in `PikePushUpPose`/`BirdDogPose`/`BasePushUpPose`/`BaseVerticalPullPose`/`AlternatingBirdDogPose`/`StaticBirdDogHoldPose` passing `worldRotation` as the 5th argument in frame-relative order).
   `BaseThoracicPose.kt`) to the world overload. `declareLimbTarget` is **NOT** introduced in M1; it is
   introduced in M2 (Pose intent-only) as the intent-recording replacement for the geometry-writing
   `bakeIkLimb`. This ordering is correct because (a) M1 is a pure mechanical refactor with zero
   behavioral change and zero new API, trivially revertible; (b) `declareLimbTarget` depends on
   `IntentBuilder` + the pipeline consuming §1.1, both of which exist only after M0/M2; introducing it
   in M1 would be a dangling API with no consumer; (c) keeping M1 narrow minimizes rollback surface.
2. For each of the 2 callers, replace with the **world** overload: the caller already has the proximal
   parent's world rotation at call time (it passed `parentRotation` to derive `worldPole`), so pass the
   pre-computed `worldPole` directly. Mechanical, 1:1.
3. Delete the frame-relative overload (`BasePose.kt:316`).
4. Set `IK_WORLD_ONLY=true`. `preConvertPoles` now owns 100% of conversion (prereq for M4).
5. **Gate:** `ValidatorRomClusterTest` + `ChestFrameIssueFTest` + `*PoseTest` green; no pose references
   the deleted overload (compile).

### M2 — Pipeline owns the stage chain / renderers re-pointed (Gap 1)
> **STATUS: COMPLETE.** `PIPELINE_ACTIVE=true` (default). `SkeletonPipeline.produceFrame` now drives
> the full ordered stage chain: `pose.build()` → `ConstraintSolver.solve` (contacts only) →
> `SkeletonPoseFinalizer.finalize` (FK + flatten) → optional Validator. The Finalizer's internal
> `ConstraintSolver.solve` call was **removed** (RFC_ENGINE_PIPELINE §8.1) — the pipeline is now the
> **sole** caller of both the Solver and the Finalizer, in fixed order, eliminating the former
> Finalizer→Solver→Finalizer re-entrancy. `SkeletonRenderer` and `SkeletonSnapshotRenderer` were
> re-pointed to route through `SkeletonPipeline.produceFrame` (they no longer hold a `SkeletonPoseFinalizer`).
> The production output is **byte-identical** to the pre-M2 baseline: the Solver still no-ops on
> contact-less poses and runs the same code on contact poses; `SkeletonPipelineM0Test` proves both the
> builder entry point and the new built-pose (renderer) entry point are byte-identical to a manual
> `solve`+`finalize` (maxDeviation 0.0). `ValidatorRomClusterTest` fixtures now route through the
> pipeline so they stay faithful to production. Full suite green (250/0). `FINALIZER_OWNS_CONVERSION`
> stays `false` by design so the F1/B5 no-move guard remains a no-op and the frame is unchanged; M4
> flips it on.
1. `PIPELINE_ACTIVE=true`. `produceFrame` runs: `pose.build()` → `ConstraintSolver.solve` →
   `finalizer.finalize` (FK) → Validator. The Solver call is **removed from the Finalizer**; the
   pipeline owns the Solver↔Finalizer ordering.
2. Renderers re-pointed: `SkeletonRenderer` / `SkeletonSnapshotRenderer` call `produceFrame` (which
   owns the `SkeletonPoseFinalizer`) instead of `finalizer.finalize(pose)` directly. A built-pose
   overload `produceFrame(SkeletonPose)` serves the renderer path.
3. **Note (scope):** this M2 is the *stage-chain + re-pointing* cut, not the larger "Pose becomes
   intent-only" rewrite the RFC's prose describes. Poses still build their own node tree inside
   `build()`; the IK/`IkStage` tree-build extraction (RFC_ENGINE_PIPELINE §4 step 4) and the
   `BasePose`→`IntentBuilder` migration are deferred — they require the §1.1 Intent Layer to be live
   (M5) and are out of scope for the flip. The ordering fix (single owner of Solver+Finalizer) is the
   substantive M2 deliverable and is what makes the later phases safe to land.
4. **Gate:** `SkeletonPipelineM0Test` byte-identity (builder + renderer paths) + `ValidatorRomClusterTest`
   baseline match + full suite green.

### M3 — Solver authority (Gap 3)
1. `SOLVER_OWNS_POSTURE=true`. Production poses adopt `declarePosture(kind)`; `seedRootFromPostureIntent`
   becomes active (F2); `lastSolvedRoot` smoothing active (F9).
2. Non-contact poses: Solver no-ops on empty contacts → unchanged.
3. **Gate:** posture-seeded poses (squat/seated/pull-up) render with engine-derived pelvis; Validator
   `PELVIS_INTENT` within tolerance.

### M4 — Finalizer authority (Gap 4)
1. `FINALIZER_OWNS_CONVERSION=true`. `preConvertPoles` active; no pose writes local transform after IK.
2. `reconstructChestFrame` no-move guard live (F1/B5).
3. **Gate:** chest-frame tests green; contact poses preserve foot planting.

### M5 — §1.1 carriers live (Gap 2, automatic)
1. No code change beyond M2; documents that `spineIntent`/`limbTargets`/`jointIntents` are now consumed.
2. **Gate:** dead-code lint shows zero unused §1.1 writes.

### M6 — Validator stamp-only (Gap 6 / Phase 8)
1. `VALIDATOR_STAMP_ONLY=true`. Replace geometry-inference methods:
   - `validateHipRom`/`femoralTwistDegrees` (L812–933, `atan2`/node reads) → read a **new hip-rom
     stamp** written by `buildHipOrientation` (or by IK/Solver). Add stamp `hipRomExceeded: Boolean`
     + per-hip `femoralTwistRad` to §1.2 if not already present.
   - `validateAngularJointLimits` (L635) → read per-joint ROM stamps (add `jointRomExceeded` set) written
     by the stage that sets each `localRotation` (IK for limbs, Solver for root, Finalizer for chest).
   - `validateBilateralSymmetry` (L536) → read left/right target deltas from `limbTargets` intent
     (§1.1) rather than reconstructed node positions.
   - `validateStraightLimbIntent` (L709) → already stamp-backed (`straightIntentDropped`).
   - `validateContactPreservation` (L733) → already stamp/contact-backed.
2. Remove `toLocalDirection`/`angleBetweenDegrees`/`atan2` from the validator (Gap 6 proof).
3. **Gate:** validator unit tests green; diagnostic instruments still flag drops (stamps present).

### M7 — `headTarget` (Gap 7, F8/W17)
1. Add `HeadTarget` carrier to §1.1 (`RFC_INTENT_LAYER` §1.2). Add `HEAD_TARGET_ENABLED` flag.
2. `buildGaze(targetWorld)` helper on `IntentBuilder`; `BaseLungePose`/`BaseVerticalPullPose` gaze
   sites migrate from `rotAround(UP, axisZ, -lean)` to `builder.headTarget(...)`.
3. Finalizer gains a neck/head resolver (reuses `buildHead` math, driven by target not direction) when
   `headTarget != null`; `null` → legacy direction path (no behavior change until migrated).
4. **Gate:** gaze-direction tests green; the two former counter-rotation sites now declare a target.

### M8 — Deprecation purge + final cleanup
1. Remove `@Deprecated` `PoseBuilder.evaluate(progress, side, def)` if all callers migrated.
2. Remove `LegacyPoseAdapter` if no pose uses it.
3. Remove `EngineFlags` booleans; inline `ARCHITECTURE_V2` constants as the only path (flags become
   `const val = true`). `PIPELINE_ACTIVE` etc. cease to exist.
4. Remove legacy position-driven branch in `finalize` (the `else` branch) once no pose exercises it.
5. **Gate:** full suite + a "no-flag" compile assertion.

---

## 3b. Milestone exit criteria (explicit — Issue 7)

- **M0 complete when:** `SkeletonPipeline` exists with `PIPELINE_ACTIVE=false`; full CI suite green;
  zero consumers changed; zero poses touched.
- **M1 complete when:** frame-relative `bakeIkLimb` overload deleted; its **2** call sites migrated to
  the world overload; `IK_WORLD_ONLY=true`; `ValidatorRomClusterTest` + `ChestFrameIssueFTest` +
  `*PoseTest` green; grep proves no reference to the deleted overload (compile).
- **M2 complete when (shipped):** `PIPELINE_ACTIVE=true`; `produceFrame` runs the full ordered
  pipeline (`build` → `ConstraintSolver.solve` → `finalizer.finalize` → FK → optional Validator); the
  Finalizer's internal `ConstraintSolver.solve` call removed (pipeline is the sole caller); renderers
  re-pointed to `produceFrame`; `ValidatorRomClusterTest` matches the pre-M2 baseline (byte-identical
  — `SkeletonPipelineM0Test` confirms). **Deferred from this M2:** the larger "Pose becomes
  intent-only" `BasePose`→`IntentBuilder` forward + `IkStage` tree-build extraction (RFC_ENGINE_PIPELINE
  §4 step 4) — those require the §1.1 Intent Layer live (M5) and are tracked as a follow-up; the
  production frame is unchanged either way. **NFR-PERF-1 allocation gate:** the hot path already reuses
  a pooled `SkeletonPoseFinalizer` output buffer + scratch vectors; the per-frame tree is still
  allocated by `build()` (Pose-owned), so the strict zero-allocation gate is deferred to the Pose
  intent-only follow-up.
- **M3 complete when:** `SOLVER_OWNS_POSTURE=true`; every production contact/posed pose calls
  `declarePosture`; posture-seeded poses render with engine-derived pelvis; `PELVIS_INTENT` within
  tolerance; non-contact poses byte-identical (Solver no-op).
- **M4 complete when:** `FINALIZER_OWNS_CONVERSION=true`; `preConvertPoles` active; no pose writes a
  local transform after IK; `reconstructChestFrame` no-move guard verified on a synthetic conflict test.
- **M5 complete when:** `spineIntent`/`limbTargets`/`jointIntents` consumed by the engine (no dead
  carrier); lint proves zero unused §1.1 writes.
- **M6 complete when:** `VALIDATOR_STAMP_ONLY=true`; `ExerciseValidator` no longer imports
  `toLocalDirection`/`angleBetweenDegrees`/`atan2`; every Validator rule reads ≥1 stamp/intent; a
  build-time assertion fails the compile if geometry inference remains.
- **M7 complete when:** `headTarget` carrier + `buildGaze` helper present; `BaseLungePose`/
  `BaseVerticalPullPose` gaze sites migrated; `null` path byte-identical to legacy; gaze-direction
  tests green.
- **M8 complete when:** no `@Deprecated` on the migrated surface; `LegacyPoseAdapter` removed;
  `EngineFlags` booleans inlined/removed; legacy `else` branch in `finalize` removed; `PoseBuilder.evaluate`
  removed; grep proves zero `toLocalDirection`/`angleBetweenDegrees`/`atan2` in `ExerciseValidator`, zero
  `ConstraintSolver.solve` inside `finalize`, zero `EngineFlags.` boolean reads; `ARCHITECTURE_V2_ROADMAP.md`
  marks Phases 1/2/3/8 behaviorally complete.

---

## 4. Rollback strategy (per phase)

Each phase is **independently reversible** because every change is flag-gated and the legacy path
remains complete until M8.

| Phase | Rollback if regression | Cost |
|---|---|---|
| M0 | n/a (no behavior change) | — |
| M1 | flip `IK_WORLD_ONLY=false`; restore deleted overload from VCS | restore 1 method + revert callers |
| M2 | flip `PIPELINE_ACTIVE=false` → legacy `build()`+finalizer path resumes | zero pose change needed (helpers still write intent *and* can be re-pointed) |
| M3 | flip `SOLVER_OWNS_POSTURE=false` → Solver ignores postureIntent (legacy relaxation) | poses keep `declarePosture` (ignored) |
| M4 | flip `FINALIZER_OWNS_CONVERSION=false` → `preConvertPoles` no-op | legacy conversion paths resume |
| M6 | flip `VALIDATOR_STAMP_ONLY=false` → geometry inference re-enabled | validator keeps both code paths until M8 |
| M7 | flip `HEAD_TARGET_ENABLED=false` → `headTarget` ignored, legacy gaze | poses keep `buildGaze` (ignored) |
| M8 | **not reversible by flag** — requires `git revert` of the merge; M8 is the only destructive step, gated on all prior phases green for N days | full revert |

**Rollback invariant:** M0–M7 are **flag-flips** (seconds, no recompile of poses). Only M8 is
irreversible-by-flag; it is the last step and requires all M0–M7 green + a soak period. This is why
flags are retained through M7 and deleted only in M8 (never "flip then delete in same PR").

---

## 5. Risk assessment

| Risk | Phase | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| `bakeIkLimb` caller mis-migration (world-pole mismatch) | M1 | Med | High (limb break) | 1:1 overload replacement (caller already had `worldPole`); snapshot diff test per pose |
| Pose intent migration misses a helper → compile fail | M2 | Low (compile catches) | Low | compile error = total migration guarantee; `LegacyPoseAdapter` fallback |
| Solver posture seed overshoots for a pose with unusual anatomy | M3 | Med | Med (pelvis jump) | `PELVIS_INTENT` tolerance + `rootTranslationDelta` surfaced; per-pose tuning; CUSTOM kind = no seed |
| Finalizer conversion changes a subtle legacy pose | M4 | Med | Med | `reconstructChestFrame` no-move guard; visual diff suite |
| Validator stamp missing for a ROM check → false negative | M6 | Med | Med (silent under-validation) | add stamp + keep inference in parallel until M8; assert stamp coverage in tests |
| `headTarget` resolver drifts gaze vs legacy | M7 | Low | Low (cosmetic) | `null` = legacy path; A/B diff on the 2 gaze sites |
| M8 flag deletion breaks a forgotten consumer | M8 | Low | High | grep all flag reads before deletion; soak period before M8 |
| Cross-frame smoothing cache key collision | M3 | Low | Low | `WeakHashMap` keyed by pose identity; documented in RFC_EXECUTION_CONTRACT §11 |

**Aggregate risk:** Low–Medium. Every phase is independently flag-reversible; the only high-impact,
irreversible step (M8) is last and gated.

---

## 6. Testing plan

**Per-phase gates (all must be green before flag flip + merge):**

- **M1 (Gap 5):** `ValidatorRomClusterTest`, `ChestFrameIssueFTest`, all `*PoseTest` (baseline match);
  compile asserts zero references to deleted overload.
- **M2 (Gap 1/2):** per-pose visual/geometry diff vs pre-M2 baseline (scripted snapshot comparison);
  `ValidatorRomClusterTest` match; intent-self-validation (I1–I10) passes.
- **M3 (Gap 3):** posture-seeded poses render with engine-derived pelvis; `PELVIS_INTENT` within
  tolerance; non-contact poses byte-identical (Solver no-op).
- **M4 (Gap 4):** chest-frame tests; contact-foot-preservation tests; `reconstructChestFrame` no-move
  assertion fires correctly on a synthetic conflict.
- **M6 (Gap 6):** validator unit tests for each replaced method using **stamps only**; a test that
  **fails the build if `ExerciseValidator` imports `toLocalDirection`/`angleBetweenDegrees`/`atan2`**
  (mechanical proof of Gap 6 closure); diagnostic drops still flagged.
- **M7 (Gap 7):** gaze-direction assertion on `BaseLungePose`/`BaseVerticalPullPose`; `headTarget=null`
  path byte-identical to legacy.
- **M8 (cleanup):** full suite + a compile assertion that no `EngineFlags` boolean is referenced.

**Cross-cutting:**
- A **stamp-coverage test** (M6) asserts every validator rule reads ≥1 stamp/intent, none reads raw
  node geometry.
- A **flag-consistency test** (M0–M7) asserts `PIPELINE_ACTIVE ⇒ IK_WORLD_ONLY && FINALIZER_OWNS_CONVERSION`.
- **Architecture-v2 conformance test** (final): asserts Pose writes §1.1 only, Engine writes §1.2 only,
  Validator writes nothing (ownership table §4.2 enforced).

**Note (flagged honestly):** the authoring sandbox here has **no Android SDK / build-tools**, so these
tests are specified for CI execution on the user's side; they were **not** run by the agent. The user
compile-checks and merges per the established workflow.

---

## 7. Architecture invariants (must hold after closure)

1. **Single writer per §1.2 field** (spec §4.2): IK→limbs+3 IK stamps; Solver→root+`root*Delta`+secondary
   `boneLengthsVerified`; Finalizer→`nodes`; FK→world. (RFC_EXECUTION_CONTRACT §3.)
2. **§1.1 frozen at `build()` return**; no stage writes §1.1; Validator reads §1.1 ROM only. (Intent Layer §3/§7.)
3. **Solver called exactly once per frame**, by the pipeline, stage 3. Finalizer never calls Solver. (Exec Contract §5.)
4. **Exactly one terminal FK**, owned by the pipeline, after Finalizer, before Validator. (Exec Contract §1.4/§2.)
5. **No local-frame pole accepted** when `IK_WORLD_ONLY` (then default). Conversion centralized in
   `preConvertPoles`. (Gap 5/F4.)
6. **Validator writes nothing**; reads §1.2 stamps + §1.1 ROM only. (Gap 6/§2.6.)
7. **Gaze is a target** (`headTarget`), not a counter-rotated direction, when `HEAD_TARGET_ENABLED`. (Gap 7/F8.)
8. **Contact end-effector immovable after Solver settle** (F1/B5 no-move). (Exec Contract §9.)
9. **All iterations bounded** (Jacobi 16, CCD 12, F1 re-pass ≤2). Deterministic finiteness. (Exec Contract §13.)
10. **Flag coherence:** `PIPELINE_ACTIVE ⇒ IK_WORLD_ONLY && FINALIZER_OWNS_CONVERSION`. (Engine Pipeline §5.7 / Exec §14.)
11. **No deprecated API survives M8**: zero `@Deprecated` on the migrated surface; zero `EngineFlags`
    boolean in source. (M8.)
12. **Pose is pluggable:** engine depends on `SkeletonPose` + carriers, never on a concrete pose class.
    (Intent Layer §8 / Exec Contract dependency graph.)

---

## 8. State ownership (consolidated view)

| State | Owner | Mutability | Lifecycle |
|---|---|---|---|
| §1.1 (`spineIntent`,`limbTargets`,`jointIntents`,`contacts`,`contactPrecedence`,`postureIntent`,`extremityOverrides`,`headTarget`,`motion`,`camera`,`environment`) | Pose (during `build`) | frozen immutable post-`build` | per-`build`; reusable carrier instance |
| `SkeletonNode` tree (`pose.roots`) | Pipeline (per frame) | mutated in sequence IK→Solver→Finalizer | created stage 2, read by FK, pooled optionally |
| §1.2 `maxIkClampAmount`,`straightIntentDropped` | IK (primary) | OR/set | per build (re-armed) |
| §1.2 `boneLengthsVerified` | IK (primary) + Solver (secondary AND) | AND | per build |
| §1.2 `rootTranslationDelta`,`rootRotationDelta` | Solver | set | per solve |
| §1.2 `nodes` (local) | Finalizer (exclusive) | write | after Solver, before FK |
| §1.2 world transforms | FK (terminal) | propagate | after Finalizer |
| §1.2 `hipRomExceeded`/`jointRomExceeded` (new, M6) | stage that sets the rotation | set | per build |
| `IntentManifest` (serialized §1.1) | Loader (pre-pipeline) | immutable snapshot | cross-process/save |

---

## 9. Deprecation strategy

- **Every changed API is `@Deprecated` before removal**, with `ReplaceWith` pointing to the new API:
  - frame-relative `bakeIkLimb` → world `bakeIkLimb` (M1).
  - node-writing pose helpers → `IntentBuilder` forwards (M2).
  - `PoseBuilder.evaluate(progress, side, def)` → `build(PoseContext)` (M8).
- **Deprecation lifetime:** a deprecated API lives **at least one full release cycle** (or until all
  in-repo callers migrated, whichever is longer). The agent's migration removes in-repo callers
  immediately; external/plugin consumers get the deprecation window.
- **Deprecation is a compile warning, not an error**, until M8 where it is deleted. This lets the flag
  stay `false` while callers migrate at their own pace.
- **`EngineFlags` booleans are themselves deprecated once their `true` state is proven green** — they
  are removed in M8, not flipped back.

---

## 10. Compatibility strategy

- **Backward compatibility (consumers):** `SkeletonRenderer`/`SkeletonSnapshotRenderer` continue to
  accept a finalized `SkeletonPose`; they are pointed at `SkeletonPipeline.produceFrame` instead of
  calling `finalizer.finalize` themselves (RFC_ENGINE_PIPELINE §2). No render-API change.
- **Pose compatibility:** `PoseBuilder.build(context)` signature unchanged → zero caller churn at the
  interface level; only the *contract* (intent-only) changes, phased via flags so old poses still work
  under `PIPELINE_ACTIVE=false`.
- **Validator compatibility:** `validate(pose, camera, env)` signature unchanged; only internal reads
  change (M6). Downstream `ValidationReport` consumers unaffected.
- **Snapshot/export compatibility:** `IntentManifest` (Intent Layer §4) is additive; old snapshots
  (§1.2-only) remain loadable as long as `applyIntentManifest` guards schema version (I10).
- **Plugin poses:** any external `PoseBuilder` keeps working under `PIPELINE_ACTIVE=false`; migration
  is opt-in per pose. `LegacyPoseAdapter` bridges the long tail through M2→M8.

---

## 11. Final cleanup (M8 checklist)

- [ ] All `@Deprecated` on the migrated surface removed.
- [ ] `LegacyPoseAdapter` deleted (or emptied) if no consumer.
- [ ] `EngineFlags` booleans inlined as `const val = true` / removed; `ARCHITECTURE_V2` is the only path.
- [ ] Legacy position-driven `else` branch in `SkeletonPoseFinalizer.finalize` removed.
- [ ] `PoseBuilder.evaluate` removed.
- [ ] Frame-relative `bakeIkLimb` overload removed (done M1; confirmed gone).
- [ ] `SOLVER_OWNS_POSTURE`/`FINALIZER_OWNS_CONVERSION` references gone from source.
- [ ] Grep asserts: zero `toLocalDirection`/`angleBetweenDegrees`/`atan2` in `ExerciseValidator`; zero
  `ConstraintSolver.solve` call inside `SkeletonPoseFinalizer`; zero `EngineFlags.` boolean reads.
- [ ] Documentation: `ARCHITECTURE_V2_ROADMAP.md` updated so Phases 1/2/3/8 marked **behaviorally
  complete** (not just structurally); Gap Report marked resolved.

---

## 12. Open questions (non-blocking)
- Should new ROM stamps (`hipRomExceeded`, `jointRomExceeded`) be added in M3/M4 (when the stage sets
  the rotation) or centralized in M6? (RFC prefers stage-authoring for locality.)
- Soak period length before M8 (propose 1 full release cycle).
