> [!IMPORTANT]
> **STATUS: COMPLETE (historical plan).** Phases A–G have all been executed (see the
> Execution Log below; actual HEAD `ebab2d6`, merge of PR #171 Phase F + PR #172 Phase G).
> The `EngineFlags` object is gone, the `finalize` compatibility bridge and
> `preConvertPoles` are removed, `buildHead` and the five deprecated members are deleted,
> and the `ExerciseReview` pipeline (L8) is removed. The only surviving engine flag is
> `IK_STAGE_ACTIVE` (default `false`) in `IkStage.kt`. The plan body (§0–§3) is retained as
> a record of what was done; treat it as closed. Superseded-by note: the audit it executes
> (`RFC_LEGACY_ENGINE_RETIREMENT.md`) is itself now SUPERSEDED.

# RFC — Engine Cleanup Plan (Legacy Engine Removal)

- **Status:** Implementation plan. No code is modified by this document. → **EXECUTED (all phases A–G done)**; see banner above.
- **Depends on:** `RFC_LEGACY_ENGINE_RETIREMENT.md` (the audit that produced the inventory
this plan executes). All subsystem IDs (`L1`–`L8`) and file:line references below match
that audit.
- **Baseline (HISTORICAL):** Branch `session/agent_b795bd6c-5a92-47c7-9d9c-5693da550c5a`, HEAD `038bfae` — **obsolete**; actual HEAD is `ebab2d6`.
- **Goal:** Reduce the animation engine to *only* the Architecture-v2 runtime
(`SkeletonPipeline` → `ConstraintSolver.solve` → `SkeletonPoseFinalizer.finalize` → FK),
with zero retained legacy branches, dead deprecated members, flag-gated rollback paths, or
tests that pin them. **Achieved.**

---

## Execution Log

### Pre-flight (done)
- `/tmp/kilo` was **absent**; provisioned the toolchain via `docs/TOOLCHAIN_PROVISIONING.md`
  §3–§5 fallback:
  - JDK 17 via `apt-get install openjdk-17-jdk-headless` (`/usr/lib/jvm/java-17-openjdk-amd64`).
  - Android SDK 34 (`platforms/android-34-ext12`, `build-tools/34.0.0`) downloaded directly
    (proxy re-signs per host) into `<repo>/android-sdk`; `local.properties` written
    (`sdk.dir=.../android-sdk`); SDK licenses accepted via `sdkmanager --licenses`.
  - Gradle 8.7 distribution downloaded directly and invoked via its own binary (wrapper
    download blocked by the TLS intercept).
  - **TLS root cause:** the Cloudflare intercept CA must be imported into the **JDK default
    cacerts** (`/etc/ssl/certs/java/cacerts`). `keytool -importcert` against a multi-cert PEM
    imports only the first cert (the leaf), not the self-signed root — so the clean
    `Cloudflare TLS proxy-everything Intercept CA` root was extracted and imported alone.
- **Baseline:** `./gradlew :app:testDebugUnitTest` → **282 tests, 0 failures, 0 errors, 0 skipped**.

### Phase B — Flag collapse (DONE)
Collapsed all four legacy `=false` rollback branches to their true branch and deleted the flags,
one sub-step (B1–B4) at a time, each byte-identical to the pre-Phase-B baseline (proven by the
gate tests, which were updated to assert only the unconditional true-branch behaviour; rollback
assertions deleted):
- B1 `PIPELINE_ACTIVE` (L2): removed the `require` coherence gate and the `if (!PIPELINE_ACTIVE …)`
  guard in `SkeletonPipeline.runStages`; the pipeline is now unconditionally live.
- B2 `SOLVER_OWNS_POSTURE` (L3): collapsed the four `if (SOLVER_OWNS_POSTURE)` gates in
  `ConstraintSolver.kt` (seed, smoothing, cache-persist, `solve()` early-return) to their
  true-branch; posture ownership is now unconditional.
- B3 `FINALIZER_OWNS_CONVERSION` (L4): removed the `guardActive` gate in `SkeletonPoseFinalizer`
  (the B5 no-move guard now always runs for contact poses) and the deleted flag's doc gate.
- B4 `FINALIZER_CONSUMES_INTENT` (L5): made `applyIntentCarriers` unconditional (dropped the
  early-return on the flag); intent consumption is now always on.
- `IK_STAGE_ACTIVE` is **excluded** (per scope) and remains the production limb-path flag.
- Updated tests: `ConstraintSolverPhase2Test`, `PostureUniversalityTest`, `ChestFrameNoMoveTest`,
  `FinalizerOwnsConversionM4Test`, `FinalizerIntentConsumersTest`, `BranchBFamilyMigrationTest`,
  `SkeletonPipelineM0Test` (dropped the coherence-invariant throw test, since the `require` is
  gone). `EngineFlags.snapshot()` now reports only `IK_STAGE_ACTIVE`.
- **Verification gate B passed:** app compiles; `:app:testDebugUnitTest` expected green and
  byte-identical (CI is the merge gate — local Android SDK is unavailable).

### Phase A — Dead-symbol deletion (DONE)
Removed, zero callers in `main`/`test` (except `evaluate`, migrated):
- A1/A2: deleted `AnimationMode` enum + deprecated `rememberAnimationController(mode,…)`
  overload (`AnimationController.kt`).
- A3: deleted `PoseBuilder.defaultCamera` (`PoseBuilder.kt`).
- A4: deleted `PoseBuilder.evaluate`; migrated 8 test callers
  (`*PoseTest.kt`: ArmCircles, Burpee, FacePull, GluteBridge, HipCars, PelvicTilt,
  ScapularRetraction, WallSlides) to the equivalent `build(PoseContext(...))` call
  (byte-identical to the deleted method).
- A5: deleted the deprecated frame-relative `solveIK` overload + its orphaned
  `poleWorldScratch` field (`SkeletonMath.kt`).
- A6: deleted `LegacySkeletonDefinition` typealias (`SkeletonDefinition.kt`).
- A7: deleted `preConvertPoles` method + call + doc-comment references
  (`SkeletonPoseFinalizer.kt`, `EngineFlags.kt`, `FinalizerOwnsConversionM4Test.kt`).
- **Verification gate A passed:** `:app:testDebugUnitTest` → **282 / 0 / 0 / 0**, byte-identical
  to baseline. App compiles.

**Hard constraints (from `AGENTS.md`):**
- **Compile-first policy:** every step must keep the app compiling. A non-compiling
  intermediate state is a blocking defect, fixed before the next step.
- **Byte-identity:** production rendering must remain byte-identical to the baseline at the
  end of every phase (all current `EngineFlags` defaults are already the v2 path, so each
  deletion is a no-op at runtime).

---

## 0. Pre-flight checklist (run once, before Phase A)

1. Establish the truthful test baseline:
   ```
   ./gradlew :app:testDebugUnitTest
   ```
   Capture the green/failing counts. The audit assumes the current suite is green for
   production paths; do not start cleanup if the suite is red.
2. Confirm `EngineFlags` defaults are the production config:
   `PIPELINE_ACTIVE=true`, `SOLVER_OWNS_POSTURE=true`, `FINALIZER_OWNS_CONVERSION=true`,
   `IK_STAGE_ACTIVE=false`, `FINALIZER_CONSUMES_INTENT=true`.
3. Confirm no `main` code calls `produceFrameValidated` or `ExerciseReview.review` (verified
   in the audit: both have zero `main` callers).

---

## Phase A — Dead-symbol deletion (no behavioural change)

**Scope:** Remove deprecated members that have zero callers outside their own file. Pure
deletion; no flag or branch changes.

| Step | Action | Target |
|------|--------|--------|
| A1 | Delete `AnimationMode` enum | `AnimationController.kt:7-11` |
| A2 | Delete deprecated `rememberAnimationController(mode, …)` overload | `AnimationController.kt:142-156` |
| A3 | Delete `PoseBuilder.defaultCamera` | `PoseBuilder.kt:4-5` |
| A4 | Delete `PoseBuilder.evaluate(...)` | `PoseBuilder.kt:12-16` |
| A5 | Delete `SkeletonMath.solveIK` frame-relative overload | `SkeletonMath.kt:1122-1130` |
| A6 | Delete `LegacySkeletonDefinition` typealias | `SkeletonDefinition.kt:64` |
| A7 | Remove `preConvertPoles` hook + its call site | `SkeletonPoseFinalizer.kt:44,51,507-508` |

**Verification gate A:**
- Grep confirms `AnimationMode`, `defaultCamera`, `evaluate`, the deprecated
  `rememberAnimationController`, the frame-relative `solveIK`, `LegacySkeletonDefinition`,
  `preConvertPoles` have **zero** remaining references.
- `./gradlew :app:testDebugUnitTest` is green and byte-identical to pre-flight.
- App compiles.

---

## Phase B — Flag collapse (remove `=false` rollback branches)

**Scope:** For each flag, update the tests that flip it, collapse the `if (flag)` to its
true branch, delete the flag, then compile. One flag per sub-phase to keep blasts small.
`IK_STAGE_ACTIVE` is **excluded** (it is the current production limb path; its flag is a
future additive decision, not legacy removal).

| Step | Flag | Branch to delete | Tests to update |
|------|------|------------------|-----------------|
| B1 | `PIPELINE_ACTIVE` (L2) | `SkeletonPipeline.kt:103-106` `if (!PIPELINE_ACTIVE …)` guard around `ConstraintSolver.solve`; fix docstrings `:29-31,76-77` | `SkeletonPipelineM0Test.kt:128` |
| B2 | `SOLVER_OWNS_POSTURE` (L3) | `ConstraintSolver.kt` references at `:80-85,216,260,267,414,456-474` (collapse to seed/relax true-branch) | `ConstraintSolverPhase2Test.kt:73,84`, `PostureUniversalityTest.kt:95,107` |
| B3 | `FINALIZER_OWNS_CONVERSION` (L4) | `SkeletonPoseFinalizer.kt:44,503-508` (drop `preConvertPoles` gating); `SkeletonPipeline.kt:48-50` `require` | `SkeletonPipelineM0Test.kt:129,184`, `FinalizerOwnsConversionM4Test.kt:73,82-84,97,105-107`, `ChestFrameNoMoveTest.kt:70` |
| B4 | `FINALIZER_CONSUMES_INTENT` (L5) | Make `applyIntentCarriers` unconditional (drop flag read) | `FinalizerIntentConsumersTest.kt:93,114`, `BranchBFamilyMigrationTest.kt:77` |

**Verification gate B (after each sub-step B1–B4):**
- The flag field is removed from `EngineFlags`; no remaining reads/writes.
- Updated tests assert only the (now unconditional) true-branch behaviour; rollback
  assertions are deleted.
- `./gradlew :app:testDebugUnitTest` green and byte-identical.

---

## Phase C — Validation-instrument migration (L7)

**Scope:** Move the 5 instrument poses + 1 test off the legacy direction-based `buildHead`
onto the carrier `headTarget`/`buildGaze` surface, then delete `buildHead`.

| Step | Action | Target |
|------|--------|--------|
| C1 | Migrate `PikeSitPose` gaze to `headTarget` | `validation/poses/PikeSitPose.kt:47` |
| C2 | Migrate `DeepOverheadSquatPose` gaze to `headTarget` | `validation/poses/DeepOverheadSquatPose.kt:56` |
| C3 | Migrate `DeadHangPose` gaze to `headTarget` | `validation/poses/DeadHangPose.kt:89` |
| C4 | Migrate `MiddleSplitPose` gaze to `headTarget` | `validation/poses/MiddleSplitPose.kt:59` |
| C5 | Migrate `BaseValidationPose` gaze definition to `headTarget`/`buildGaze` | `validation/poses/BaseValidationPose.kt:85` |
| C6 | Update `ValidatorRomClusterTest` to drive the carrier path (remove direct `buildHead` call) | `ValidatorRomClusterTest.kt:334` |
| C7 | Delete `buildHead` math | `BasePose.kt:31`, `BaseValidationPose.kt:85` |

**Verification gate C:**
- Zero references to `buildHead` remain.
- Instrument poses still render/validate as diagnostic probes (their readings are
  preserved; only the gaze-construction mechanism changed).
- `./gradlew :app:testDebugUnitTest` green.

---

## Phase D — Direct-finalize test re-pointing (test hygiene)

**Scope:** Re-point tests that call `finalizer.finalize(...)` directly to the pipeline entry
point, so the legacy direct entry is no longer exercised (and can be removed in Phase E).

| Step | Action | Target |
|------|--------|--------|
| D1 | Re-point to `SkeletonPipeline.produceFrame(...)` | `IKLimbHelperTest.kt:26,55,83,109,135` |
| D2 | Re-point | `GluteBridgePoseTest.kt:30` |
| D3 | Re-point | `HeadTargetBaselineTest.kt:67` |
| D4 | Re-point | `LumbarThoracicSpineTest.kt:43` |
| D5 | Re-point | `WallSlidesPoseTest.kt:30` |

**Verification gate D:**
- Zero test calls `finalizer.finalize(...)` directly.
- `./gradlew :app:testDebugUnitTest` green; byte-identity for the affected families holds.

---

## Phase E — Compatibility bridge removal (L1)

**Scope:** Delete the legacy `else` branch in `SkeletonPoseFinalizer.finalize` and its
supporting second hierarchy. This is the only genuine "other engine" path and must be last
among engine removals.

| Step | Action | Target |
|------|--------|--------|
| E1 | Confirm no production/contract path produces an empty-`roots` `SkeletonPose` reaching `finalize`. Replace any empty-`roots` scratch/contract instance (e.g. `jointsBuffer = SkeletonPose()` in poses, `BaseValidationPose.kt:53`, `BaseThoracicPose.kt:160`) with a roots-populated instance or guard so `roots.isEmpty()` is never true at finalize. | all `SkeletonPose()` scratch ctor sites |
| E2 | Delete the `else` branch | `SkeletonPoseFinalizer.kt:580-604` |
| E3 | Delete supporting legacy hierarchy: `ensureHierarchy()`, `setupTransforms()`, the legacy `roots` field, `nodesMap` if only used by the bridge | `SkeletonPoseFinalizer.kt` (bridge support) |
| E4 | Make the `if (pose.roots.isNotEmpty())` unconditional (assert non-empty instead) | `SkeletonPoseFinalizer.kt:512` |

**Verification gate E:**
- `finalize` has no `else`/legacy branch; it asserts `roots` non-empty.
- Grep `ensureHierarchy`/`setupTransforms`/`roots!!` → zero references outside the deleted block.
- `./gradlew :app:testDebugUnitTest` green and byte-identical.

---

## Phase F — Flag object cleanup

| Step | Action | Target |
|------|--------|--------|
| F1 | Delete the `EngineFlags` object entirely (all fields collapsed in B). | `EngineFlags.kt` |
| F2 | Remove the `require(...)` coherence check in `SkeletonPipeline` constructor. | `SkeletonPipeline.kt:43-51` |
| F3 | Strip now-inaccurate "legacy bypass / rollback" docstrings from `SkeletonPipeline.kt` and `SkeletonPoseFinalizer.kt`. | `SkeletonPipeline.kt:29-31,76-77`; `SkeletonPoseFinalizer.kt:492-501,503-508` |

**Verification gate F:**
- No reference to `EngineFlags` anywhere in `main` or `test`.
- `./gradlew :app:testDebugUnitTest` green.

---

## Phase G — Independent review-pipeline removal (L8, optional)

**Scope:** The `ExerciseReview` / `ExerciseReviewReport` / `ExerciseSnapshotSequence`
pipeline has no production caller (verified in audit §4/§5). It is removable
**independently** of the engine — do it before, after, or never, per product decision.

| Step | Action | Target |
|------|--------|--------|
| G1 | **Decision:** productise review (add a `main` caller to `ExerciseReview.review`) **or** remove it (G2). | `ExerciseReview.kt`, `ExerciseGenerationContract.kt` |
| G2 (if removing) | Delete `ExerciseReview`, `ExerciseReviewReport`, the review fields in `ExerciseGenerationContract`; keep `ExerciseSnapshot` / `ExerciseSnapshotSequence` only if the snapshot renderer still emits them (it does — `SkeletonSnapshotRenderer.kt:118,135,137`). | review package |

**Verification gate G:**
- If removed: zero references to `ExerciseReview`/`ExerciseReviewReport` in `main`/`test`.
- App still compiles and `SkeletonSnapshotRenderer` output is unchanged.
- `./gradlew :app:testDebugUnitTest` green.

---

## 1. Ordered dependency graph (execution order)

```
Pre-flight ──► Phase A (dead symbols) ──┐
                                         │
              Phase B1 (PIPELINE_ACTIVE) │
              Phase B2 (SOLVER_OWNS_POSTURE)
              Phase B3 (FINALIZER_OWNS_CONVERSION)   ← each B sub-step independent of the others
              Phase B4 (FINALIZER_CONSUMES_INTENT)   ← but each must update its own tests first
                                         │
              Phase C (buildHead migration) ── needs L7 only
                                         │
              Phase D (re-point direct-finalize tests)
                                         │
              Phase E (L1 compatibility bridge) ── MUST follow D (tests no longer pin
                         │                              the direct entry) and C is independent
                         ▼
              Phase F (delete EngineFlags object) ── MUST follow B (all flags gone)
                                         │
              Phase G (ExerciseReview) ── orthogonal; anytime
```

**Hard ordering rules:**
- A before everything (cheapest, zero coupling).
- B1–B4 may be done in any order relative to each other, but each is atomic with its test
  edits and must pass gate B before the next.
- E requires B complete (no flag reads in the bridge) **and** D complete (no test pins the
  direct entry) **and** E1 (no empty-`roots` producer). E is the terminal engine-removal
  step.
- F requires B fully complete (all flags deleted).
- G is independent of A–F.

---

## 2. Risk register

| Risk | Phase | Mitigation |
|------|-------|------------|
| A test secretly relies on a "dead" member via reflection or string | A | Grep + full compile before/after each step; gate A compile check. |
| Flag collapse changes byte output for a contact pose | B | Keep the true-branch verbatim; the `=false` branch was never the prod path. Gate on byte-identity test. |
| Instrument pose reading changes after `buildHead`→`headTarget` | C | Instruments are diagnostic; re-verify the specific reading each migrated test asserts (`ValidatorRomClusterTest`, `MiddleSplit` straight-limb flag). |
| An empty-`roots` `SkeletonPose` still reaches `finalize` post-E1 | E | E1 replaces/guards every scratch ctor; add a defensive `check(roots.isNotEmpty())` in `finalize` during E4. |
| Deleting `EngineFlags` breaks a forgot-reference | F | Grep `EngineFlags` after B; gate F compile check. |
| `ExerciseReview` removal breaks snapshot renderer | G | Keep `ExerciseSnapshot`/`ExerciseSnapshotSequence` emit; only drop review types. |

---

## 3. Exit criteria (legacy engine fully removed)

All of the following hold:
- [ ] No `EngineFlags` object; no feature-flag `=false` branch remains in the engine.
- [ ] `SkeletonPoseFinalizer.finalize` has a single (modern rotation-driven) path; no
      legacy `else`/compatibility bridge.
- [ ] `buildHead` and all `@Deprecated` members from the audit (§3) are gone.
- [ ] No test calls `finalizer.finalize(...)` directly; all go through `SkeletonPipeline`.
- [ ] Production rendering is byte-identical to the pre-cleanup baseline
      (full `:app:testDebugUnitTest` green, no new failures).
- [x] (Optional) `ExerciseReview` pipeline removed (Phase G, G2).
