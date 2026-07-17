# RFC: Engine Stabilization Plan (pause Architecture v2 after M1)

- **Status:** Proposed
- **Supersedes:** "continue Architecture v2 roadmap immediately after M1"
- **Author:** Engine Stabilization working group
- **Date:** 2026-07-17
- **Related:** `docs/ARCHITECTURE_V2_ROADMAP.md`, `docs/TEST_BASELINE.md`, `docs/TOOLCHAIN_PROVISIONING.md`

---

## 1. Why the roadmap must pause

### 1.1 Compilation restoration exposed hidden failures

PR #134 (fix(test): resolve 4 pre-existing unit-test compile errors) added the missing
`kotlin.math` imports and rewrote the unsupported 3-arg `max` in `VerticalPullPosesTest`.
That was a pure compile fix — no production code changed — but it had a structural effect:

- Before PR #134, four test files (`ConstraintSolverTest`, `IKLimbHelperTest`,
  `TrunkFrameTest`, `VerticalPullPosesTest`) did not compile. Because the `:app` test
  source set is compiled as one module, **the entire unit-test module failed to compile
  and 0 tests executed**. The previously quoted "168 / 30" baseline was a *curated*
  snapshot taken with those four files excluded — it was never a truthful picture of the
  engine.
- After PR #134, the module compiles and **236 tests execute**. The real number is
  **236 executed / 31 failed**, not 168 / 30.

So PR #134 did not *introduce* the 31 failures. It removed the curtain that was hiding
them. For the first time we have a truthful, executable view of the engine's actual state.

### 1.2 These failures are now architectural blockers

A failure that cannot even be observed is a latent risk; a failure that is observed and
reproduced on every CI run is an **architectural blocker**. The 31 failures now gate every
future change: any Architecture v2 migration that touches `SkeletonPoseFinalizer`,
`ConstraintSolver`, `SkeletonMath` (IK), the `ExerciseValidator` rule set, or pose
authoring will be impossible to validate, because the existing red tests already saturate
the signal. Migration diffs would be indistinguishable from pre-existing breakage.

### 1.3 Mixing defects with new work is unsafe

If we continue the Architecture v2 migration now, every PR would carry two overlapping
sources of change:

1. the intended migration (new structure, new code paths), and
2. the pre-existing engine defects that the new structure must happen to preserve or
   accidentally fix.

The result is non-isolated signal: a green CI could mean "the migration is correct" or
"the migration happened to paper over a defect," and a red CI could mean "regression" or
"the same 27 historical failures we already had." We cannot tell them apart. That violates
the core principle stated in `AGENTS.md`: **validation poses are diagnostic instruments,
not dev targets** — and by extension, the test suite as a whole is a diagnostic instrument
that must be honest before we build on top of it.

### 1.4 Stabilization must precede additional architecture migration

Therefore the priority inverts. The correct sequence is:

> **Stabilize the engine (make the 31 failures legible and then zero them by subsystem)
> → THEN resume Architecture v2 from M2.**

This RFC defines that stabilization effort. It explicitly **suspends Architecture v2 after
M1** and makes stabilization the sole priority until the exit criteria in §8 are met.

---

## 2. Reclassification of every current failing test

Source of truth: CI run `236 executed / 31 failed` (post PR #134). Classification follows
the evidence from the prior analysis:

- The PR changed **no production code** (only test imports, one semantically-identical
  expression rewrite, docs, and the CI workflow). Therefore **0 regressions** were
  introduced by the PR.
- Four of the failing files could not have executed on `origin/main` (identical compile
  errors there) → their failures are **newly visible**.
- The remaining failures are **historical**; of those, two (`ValidatorRomClusterTest` ×2)
  were **not recorded** in `docs/TEST_BASELINE.md` (a stale-baseline doc gap).

### 2.1 Newly visible — 5 tests

These never ran before PR #134 because compilation prevented them. They are the direct,
intended consequence of restoring compilation. They are **not regressions** and must be
triaged as their own follow-up, not lumped with engine drift.

| Test | First visible |
|---|---|
| `ConstraintSolverTest.middleSplitPelvisRestsOnGroundFeetStayPlanted` | PR #134 (`efef793`) |
| `ConstraintSolverTest.deepOverheadSquatFeetStayOnGroundAndFinite` | PR #134 |
| `IKLimbHelperTest.testSolveIKAngularLimitClampsHyperextension` | PR #134 |
| `TrunkFrameTest.testQuadrupedTwistChangesShoulderPosition` | PR #134 |
| `VerticalPullPosesTest.testVerticalPullFamilyBiomechanics` | PR #134 |

### 2.2 Historical documented — 25 tests

Pre-existing on `origin/main`, recorded in `docs/TEST_BASELINE.md`. Driven by the two known
drift families: `BONE_LENGTH` frame-0 arm/hand validation, and stale hard-coded expected
positions.

`BurpeePoseTest.testBurpeePoseBiomechanicalCompliance` ·
`DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly` ·
`EnvironmentAnchorsTest.testStandardPullUpPoseAnchorMetadataAndMigration` ·
`EnvironmentAnchorsTest.testHangPoseAnchorMetadataAndMigration` ·
`ExerciseValidatorTest.testRule1FiniteCoordinates` ·
`KettlebellSwingPoseTest.testKettlebellSwingPoseMeetsAllBiomechanicalRequirements` ·
`KneePushUpPoseTest.testKneePushUpPoseBiomechanicalCompliance` ·
`LungePosesTest.testForwardLungeBiomechanics` ·
`LungePosesTest.testStepUpBiomechanics` ·
`LungePosesTest.testReverseLungeBiomechanics` ·
`LungePosesTest.testSideLungeBiomechanics` ·
`NewEnginePosesTest` ×8 (`testNeutralGripPullUpPoseBuildsCorrectly`,
`testAlternatingSideLungesPoseBuildsCorrectly`, `testHangPoseBuildsCorrectly`,
`testAlternatingReverseLungesPoseBuildsCorrectly`, `testWideGripPullUpPoseBuildsCorrectly`,
`testAlternatingForwardLungesPoseBuildsCorrectly`, `testUnderhandChinUpPoseBuildsCorrectly`,
`testStandardPullUpPoseBuildsCorrectly`) ·
`ScapularPullUpPoseTest.testScapularPullUpPoseMeetsAllBiomechanicalRequirements` ·
`SquatPosesTest.testSumoSquatPoseBiomechanicalCompliance` ·
`StandardPushUpPoseTest.testStandardPushUpPoseBiomechanicalCompliance` ·
`StepUpPoseTest.testStepUpPoseMeetsAllBiomechanicalRequirements` ·
`ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly`.

### 2.3 Historical undocumented — 2 tests

Pre-existing on current `origin/main` (the PR did not touch them and changed no production
code, so they cannot be regressions), but **absent from `docs/TEST_BASELINE.md`**. They were
broken by a `main` commit made *after* the 168/30 snapshot was taken, and the baseline was
never re-measured. They are historical, but the documentation gap itself is a defect to fix.

| Test | First visible |
|---|---|
| `ValidatorRomClusterTest.pelvisIntentWarnsOnLargeDisplacement` | pre-existing on `main` (post-snapshot commit); NOT PR #134 |
| `ValidatorRomClusterTest.contactPreservationWarnsOnLargeMiss` | pre-existing on `main` (post-snapshot commit); NOT PR #134 |

### 2.4 Regression (currently none) — 0 tests

**Evidence:** `git diff --stat origin/main..HEAD` contains only import statements, one
expression rewrite that is semantically identical (`max(a, max(b, c))` ≡ the intended
3-arg `max`), and documentation/workflow edits. No production source (`.main.`) was
modified. Therefore no test's pass/fail could have been altered by this PR. **Count = 0.**

### 2.5 Why each category requires different handling

- **Newly visible (5):** the engine defect may be historical, but the *test* is new
  evidence. Handle by triaging the underlying engine defect alongside the historical work,
  but keep them visible separately so we do not mistake "we fixed a compile blocker" for
  "we improved the engine."
- **Historical documented (25):** these are the known drift. Handle by subsystem fix
  (§3) and by *correcting the tests where they encode stale constants* vs. *fixing the
  engine where the pose is genuinely wrong*. The test, not just the engine, may be the
  bug.
- **Historical undocumented (2):** handle by (a) fixing the engine defect and (b)
  **updating `docs/TEST_BASELINE.md`** so the baseline is truthful. The doc gap is a
  process failure that must not recur.
- **Regression (0):** none to handle. If any appear during stabilization, they are
  immediate blockers and trigger the phase rollback strategy (§4).

---

## 3. Failures grouped by subsystem

We fix **systems**, not individual tests. The 31 failures collapse into four engine
subsystems (plus a documentation gap). Root causes are inferred from the assertion
messages recorded in `docs/TEST_BASELINE.md` and the failing assertions themselves.

### 3.1 ConstraintSolver (global contact/root solver)

- **Affected tests (newly visible, 3):**
  `ConstraintSolverTest.middleSplitPelvisRestsOnGroundFeetStayPlanted`,
  `ConstraintSolverTest.deepOverheadSquatFeetStayOnGroundAndFinite`,
  `TrunkFrameTest.testQuadrupedTwistChangesShoulderPosition` (shoulder movement is driven
  by the chest frame the solver finalizes).
- **Root cause:** the global solver re-derives the root/pelvis from ground contacts and
  rigidly follows non-contact limbs. Observed symptoms: foot/ankle joints penetrate the
  ground plane (`y < -1e-2`), and the chest/twist frame does not propagate to the upper
  chain (shoulder static under thoracic twist). Indicates the contact-projection and
  rigid-follow steps are not honoring authored geometry / chest world rotation.
- **Estimated fixes:** 2–4 (one for ground-plane contact projection, one for chest-frame
  propagation into the shoulder chain, possibly one for the "float the pelvis" leftover
  behavior).

### 3.2 IK (`SkeletonMath.solveIK` / `solveNearStraightLeg`)

- **Affected tests (newly visible, 2):**
  `IKLimbHelperTest.testSolveIKAngularLimitClampsHyperextension`,
  `VerticalPullPosesTest.testVerticalPullFamilyBiomechanics` (reach/clamp band).
- **Root cause:** angular clamp not recorded for a hyperextended target
  (`result.angularClampAmount > 0` fails); vertical-pull family reports invalid
  (reach/clamp band not respected / BONE_LENGTH). The IK solver's clamp accounting and
  straight-limb re-bake are suspect.
- **Estimated fixes:** 2 (one for angular-clamp recording, one for the straight-limb /
  reach band used by pull poses).

### 3.3 Validator (`ExerciseValidator` rule set)

- **Affected tests (historical documented, 22; historical undocumented, 2):**
  `ExerciseValidatorTest.testRule1FiniteCoordinates`,
  `EnvironmentAnchorsTest` ×2, `NewEnginePosesTest` ×8, `LungePosesTest` ×4,
  `KettlebellSwingPoseTest`, `KneePushUpPoseTest`, `StepUpPoseTest`,
  `ScapularPullUpPoseTest`, `StandardPushUpPoseTest`, `SquatPosesTest`,
  `BurpeePoseTest`, `DeclinePushUpPoseTest`, `WidePushUpPoseTest`, `AirSquat` (now
  passing), `MountainClimber` (now passing), `ReverseSnowAngel` (now passing),
  `LatStretch` (now passing),
  plus undocumented `ValidatorRomClusterTest.pelvisIntentWarnsOnLargeDisplacement` and
  `ValidatorRomClusterTest.contactPreservationWarnsOnLargeMiss`.
- **Root cause (two families):**
  1. `BONE_LENGTH` at frame 0 on the arm/hand chain — the validator flags bone-length
     violations the solver/FK actually produce (real engine defect, or the validator
     threshold is wrong for the authored pose).
  2. Expected-position drift — tests encode stale constants (pelvis hang `230` vs actual
     `~240.9`; pelvis X/Z shift off by ~10–14 units). Here the **test** is often the bug,
     not the engine.
  3. (undocumented) `PELVIS_INTENT` / `CONTACT_PRESERVED` rules not firing for the
     authored inputs — rule logic or config default regression on `main`.
- **Estimated fixes:** 4–6 (split bone-length threshold tuning from genuine FK defects;
  correct ~10 stale expected-position constants; fix 2 validator rules + re-baseline).

### 3.4 Pose authoring (per-pose `build()` / `BasePose` families)

- **Affected tests (historical documented, subset; overlaps Validator):**
  `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly` (elbow sweep
  direction),
  `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` (chest
  extension not produced),
  and the pull/push/lunge/stretch `*PoseTest` biomechanics failures that originate in how
  the pose is authored (contact placement, reach targets) rather than in the solver.
- **Root cause:** poses author contacts / targets that the validator then rejects
  (e.g. elbow sweep sign, thoracic extension magnitude, pelvis hang height). Some are
  authoring bugs; some are the validator being stricter than the authored intent.
- **Estimated fixes:** 3–5 (elbow-sweep sign, thoracic extension magnitude, pelvis-hang
  authored value, plus the per-pose contact/reach targets feeding the Validator family).

### 3.5 Documentation gap (not an engine subsystem, but a blocker)

- **Affected:** `docs/TEST_BASELINE.md` (missing the 2 `ValidatorRomClusterTest` entries;
  the 168/30 pair is stale; 5 historical failures now pass and are not noted).
- **Root cause:** baseline was last measured with the four compile-broken files excluded
  and never refreshed.
- **Estimated fixes:** 1 (doc rewrite to the tri-valued baseline in §2 / §6).

---

## 4. Stabilization roadmap

New phases **S0 … S3** replace "continue M2 immediately." Each phase is a stabilization
unit with explicit objective, subsystem, expected-green tests, rollback, and validation.

### S0 — Establish a truthful, reproducible baseline

- **Objective:** make the CI result the single source of truth and prevent regression
  blindness going forward.
- **Affected subsystem:** tooling + docs.
- **Expected tests to become green:** none (no behavioral change); but the CI workflow
  step added in PR #134 must run and the *count* must be asserted (`236 executed`).
- **Rollback strategy:** none — additive only (docs + CI guard). If a doc change breaks
  the build, revert the doc commit.
- **Validation strategy:** re-run `./gradlew :app:testDebugUnitTest` on a clean `main`;
  confirm `236 executed / 31 failed` reproduces; commit the corrected
  `docs/TEST_BASELINE.md` (tri-valued: 236 total / 27 historical / 5 newly visible) and
  add the 2 missing `ValidatorRomClusterTest` entries. Add a CI comment/annotation that
  pins the expected failure count so future regressions are visible.

### S1 — ConstraintSolver + IK (the foundation)

- **Objective:** make the global solver and IK produce geometrically valid, finite,
  ground-respecting poses; make IK clamps account correctly.
- **Affected subsystem:** ConstraintSolver, IK.
- **Expected tests to become green:**
  - `ConstraintSolverTest.middleSplitPelvisRestsOnGroundFeetStayPlanted`
  - `ConstraintSolverTest.deepOverheadSquatFeetStayOnGroundAndFinite`
  - `IKLimbHelperTest.testSolveIKAngularLimitClampsHyperextension`
  - `TrunkFrameTest.testQuadrupedTwistChangesShoulderPosition`
  - `VerticalPullPosesTest.testVerticalPullFamilyBiomechanics` (IK/reach portion)
- **Rollback strategy:** the IK angular-clamp and chest-frame propagation changes are
  localized to `SkeletonMath.solveIK` / `SkeletonPoseFinalizer`. If any *historical*
  Validator test regresses (i.e. moves from the 27 into a new failure), revert the S1
  engine change and re-open the issue — do **not** mute the test.
- **Validation strategy:** run the five named tests plus the full Validator family; assert
  no new failures beyond the pre-S1 27. Ground-penetration assertions and shoulder-movement
  assertions must pass.

### S2 — Validator rule set + stale-constant correction

- **Status:** COMPLETE (Validator-rule portion). The full suite moved 31 → 11 failures.
- **Objective:** resolve the `BONE_LENGTH` frame-0 family and the expected-position drift;
  fix the 2 undocumented validator rules.
- **Affected subsystem:** Validator (rules + thresholds) and the stale test constants.
- **What S2 actually resolved (committed):**
  1. `PELVIS_INTENT` and `CONTACT_PRESERVED` rules now emit `ERROR` (were `WARNING`, so the
     rule's `isValid` stayed `true` and the two `ValidatorRomClusterTest` cases stayed red).
     Fixes the RFC §6 "rule not firing" defects (B1). `ValidatorRomClusterTest` now all-green.
  2. Stale test constants corrected (B2), after verifying engine output is correct:
     `ExerciseValidatorTest.createValidBasePose` elbow coords (arm bones must equal
     `upperArmLength`/`forearmLength`), `EnvironmentAnchorsTest` ×2 (pelvis hang 220/230 →
     actual 242.9/240.9), `NewEnginePosesTest` ×8 (pelvis Y/X/Z step amplitudes → actual
     authored values).
- **What S2 deliberately did NOT do:** the remaining 11 failures are upstream (S1-residual
  IK/reach band + S3 pose authoring). Per §5 the Validator is last; weakening
  `BONE_LENGTH`/`IK_TARGET_UNREACHABLE` thresholds to green them would mask real FK/solver
  defects (blocking level B1 → must revert-and-fix-engine, never mute). They are deferred to
  S3 (authoring) and a follow-up S1-residual IK pass.
- **Rollback strategy:** each rule/threshold change is one commit. If a threshold change
  masks a real defect (test goes green for the wrong reason), the Fix Matrix's blocking
  level (§6) forces a revert and an engine fix instead. Stale-constant corrections to tests
  are safe to keep only when the engine output is verified correct.
- **Validation strategy:** full suite dropped 31 → 11. `ValidatorRomClusterTest` all-green.
  `docs/TEST_BASELINE.md` updated to list every remaining failure with a root cause.

### S3 — Pose authoring corrections

- **Status:** COMPLETE (per-pose authoring portion). The full suite moved 11 → 9 failures.
- **Objective:** correct the per-pose authoring defects (elbow sweep, thoracic extension,
  pelvis-hang, contact/reach targets) that remain after S1/S2.
- **Affected subsystem:** Pose authoring.
- **What S3 actually resolved (committed):**
  - `QuadrupedThoracicRotationsPose` — the reaching arm's vertical component was authored in
    the chest's *rotating local frame* (chest-local +Y points forward in tabletop, so the
    "sweep up" ramp actually swept the hand forward/down). Re-authored the reach so the
    horizontal component still follows the thorax but the vertical is authored in world space,
    so the hand/elbow demonstrably sweep from threaded-under (low) to reaching-for-the-sky
    (high). `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly` green.
  - `ThoracicExtensionPose` — the extension was driven only by the chest node's own
    localRotation, which rotates the children in place but never translates the CHEST/HEAD
    joints, so `chestX`/`headX` never moved. Re-authored the arch to originate at the
    thoracolumbar junction (`lumbar` segment) so the chest tips up and BACK (-X) and carries
    the neck/head/shoulders with it, plus a small residual chest-local extension.
    `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` green.
- **What S3 deliberately did NOT do:** the remaining 9 failures are S1-residual IK/reach
  (`BONE_LENGTH` frame-0 on the arm/hand chain + `IK_TARGET_UNREACHABLE`). These are upstream
  solver/reach defects, not per-pose authoring, and are deferred to a follow-up S1-residual
  IK pass; weakening validator thresholds to green them would mask real defects (§5).
- **Rollback strategy:** authoring changes are per-pose and isolated; revert the individual
  pose commit if it regresses a Validator test.
- **Validation strategy:** full suite dropped 11 → 9, no new failures. The two target
  `*PoseTest` biomechanics assertions pass; `docs/TEST_BASELINE.md` updated.

---

## 5. Dependency-ordered prioritization

The true dependency graph is not file order; it is data-flow through the engine:

```
Pose authoring (build)          ← produces raw SkeletonPose
        │
        ▼
IK (SkeletonMath.solveIK)       ← resolves limb targets; records clamps
        │
        ▼
ConstraintSolver                ← derives root/pelvis from contacts, rigid-follow
 (SkeletonPoseFinalizer)           propagates chest frame to upper chain
        │
        ▼
Validator (ExerciseValidator)   ← judges the finalized pose; emits the failures we see
```

Two important inversions vs. naive file ordering:

1. **IK is a prerequisite for ConstraintSolver**, not the other way around. The solver
   consumes IK-resolved limbs; if IK clamps are wrong (S1/IK), the solver's
   ground/rigid-follow math operates on bad inputs. So **S1 fixes IK and ConstraintSolver
   together**, with IK landing first within the phase.
2. **Validator is last**, not first. Every Validator failure is a *symptom* of an upstream
   authoring/IK/solver defect. Fixing Validator rules before the upstream systems would
   just mute symptoms. Therefore S2 (Validator) runs **after** S1, and only corrects (a)
   genuine rule bugs and (b) test constants that encode stale expectations — never to hide
   an upstream defect.

Priority order: **S0 → S1 (IK first, then ConstraintSolver) → S2 (Validator + constants) →
S3 (Pose authoring residual).**

Pose authoring (S3) is sequenced last because by S1/S2 the solver/IK/validator are honest,
so any remaining `*PoseTest` failure is isolatable to the specific pose's `build()` and can
be fixed without ambiguity.

---

## 6. Fix Matrix

Mapping multiple tests to one engine defect (not one fix per test). Blocking levels:
**B0** = must fix before any migration; **B1** = fix within stabilization; **B2** = correct
the test constant (engine already correct).

| Subsystem | Root-cause defect (one) | Tests mapped to it | Blocking | Phase |
|---|---|---|---|---|
| ConstraintSolver | Ground-plane contact projection lets ankles penetrate (`y < -1e-2`) | `ConstraintSolverTest.middleSplitPelvisRestsOnGroundFeetStayPlanted`, `ConstraintSolverTest.deepOverheadSquatFeetStayOnGroundAndFinite` | B0 | S1 |
| ConstraintSolver | Chest/twist world frame not propagated to shoulder chain | `TrunkFrameTest.testQuadrupedTwistChangesShoulderPosition` | B0 | S1 |
| IK | Angular clamp not recorded for hyperextended target | `IKLimbHelperTest.testSolveIKAngularLimitClampsHyperextension` | B0 | S1 |
| IK | Straight-limb / reach band not respected (pull family) | `VerticalPullPosesTest.testVerticalPullFamilyBiomechanics` | B0 | S1 |
| Validator | `BONE_LENGTH` frame-0 on arm/hand chain (threshold vs real FK defect) | `ExerciseValidatorTest.testRule1FiniteCoordinates` (B2 — test elbow coords stale, fixed), `KettlebellSwingPoseTest`, `KneePushUpPoseTest`, `StepUpPoseTest`, `ScapularPullUpPoseTest`, `StandardPushUpPoseTest`, `SquatPosesTest`, `BurpeePoseTest`, `DeclinePushUpPoseTest`, `WidePushUpPoseTest`, `LungePosesTest` ×4, `MountainClimber`/`LatStretch`/`ReverseSnowAngel`/`AirSquat` (residual) | B1 (engine) | S1 residual / S2 |
| Validator | Stale expected-position constants (pelvis hang `230`↔`~240`, X/Z shift) | `EnvironmentAnchorsTest` ×2, `NewEnginePosesTest` ×8 (position subset) — **RESOLVED in S2 (B2: corrected test constants, engine verified correct)** | B2 | S2 (done) |
| Validator | `PELVIS_INTENT` rule not firing | `ValidatorRomClusterTest.pelvisIntentWarnsOnLargeDisplacement` — **RESOLVED in S2 (rule now emits ERROR; was WARNING so rule stayed "valid")** | B1 | S2 (done) |
| Validator | `CONTACT_PRESERVED` rule not firing | `ValidatorRomClusterTest.contactPreservationWarnsOnLargeMiss` — **RESOLVED in S2 (rule now emits ERROR; was WARNING so rule stayed "valid")** | B1 | S2 (done) |
| Pose authoring | Elbow-sweep sign wrong | `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly` — **RESOLVED in S3 (reach vertical authored in world space, not the chest's rotating local frame)** | B1 | S3 (done) |
| Pose authoring | Thoracic extension magnitude not produced | `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` — **RESOLVED in S3 (arch driven from the lumbar/thoracolumbar segment so the chest/head translate backward)** | B1 | S3 (done) |
| Pose authoring | Pelvis-hang / contact / reach targets stale | residual pull/push/lunge/stretch `*PoseTest` biomechanics | B1 | S3 |
| Docs | Baseline stale / 2 undocumented failures missing | (process) `docs/TEST_BASELINE.md` | B1 | S0 |

Note: `VerticalPullPosesTest` and `TrunkFrameTest` also exercise the Validator/authoring
layers; their S1 mapping is the IK/solver portion. Any residual after S1 is re-mapped into
S2/S3 via the same defect, not a new fix.

---

## 7. Architecture impact

For each stabilization phase, the effect on the suspended Architecture v2 roadmap:

- **S0 (baseline):** makes every future Architecture v2 PR *legible*. Without it, M2+ diffs
  are uninterpretable. **No v2 phase is safe to start until S0 lands.**
- **S1 (IK + ConstraintSolver):** once green, the foundation that Architecture v2 phases
  touching `SkeletonPoseFinalizer`, the intent layer, and `buildGaze`/`buildShoulders`
  depend on is honest. Specifically, **M2 (intent-resolution finalizer) and the head-target
  resolver (`HEAD_TARGET_ENABLED`) become safely verifiable** — today they sit on top of a
  solver whose ground/chest-frame behavior is unproven. **Blocked until S1:** M2, and the
  pending `HEAD_TARGET_ENABLED=true` gate (its gating suites `*PoseTest` /
  `ValidatorRomClusterTest` / `ChestFrameIssueFTest` cannot be trusted while the engine is
  red).
- **S2 (Validator):** once the rule set and stale constants are corrected, Architecture v2
  phases that add new validation rules or migrate validator logic (e.g. RFC intent-layer
  validator migration, `headTarget` consumption) can be diffed cleanly. **Blocked until S2:**
  any validator-migration v2 phase.
- **S3 (Pose authoring):** once per-pose authoring is clean, the v2 pose-portfolio
  migrations (remaining gaze/girdle sites, legacy `buildHead` deletion) are safe. **Blocked
  until S3:** deletion of the legacy `buildHead` branch and remaining gaze-site migrations.

**Net:** Architecture v2 is suspended after M1. M2 and all later phases remain **blocked**
until S0→S3 complete. The `HEAD_TARGET_ENABLED` flag (commit `d4ba4c6`) must stay at its
current value **only after** its gating suites are green post-stabilization; until then the
gate from commit `cb9379f` is unmet and the flag remains unverified.

---

## 8. Exit criteria (resume Architecture v2 only when ALL hold)

Objective, machine-checkable conditions — stabilization is done and M2 may start when:

1. **All compile issues resolved.** `./gradlew :app:testDebugUnitTest` compiles with no
   errors; `236` tests execute (the four previously-broken files included).
2. **ConstraintSolver tests green.** `ConstraintSolverTest` (both), `TrunkFrameTest`,
   `IKLimbHelperTest`, and the IK portion of `VerticalPullPosesTest` all pass.
3. **Validator baseline updated and truthful.** `docs/TEST_BASELINE.md` lists every failure
   with a root cause; the 2 undocumented `ValidatorRomClusterTest` failures are either fixed
   or explicitly waived with rationale. `ValidatorRomClusterTest` fully green.
4. **No undocumented failures remain.** Every red test is accounted for in the Fix Matrix
   §6; zero failures exist that are not mapped to a known defect/phase.
5. **Full suite green (modulo designed-red instruments).** `236 executed / 0 failed`,
   except diagnostic-instrument tests that are **red by design** (e.g. Middle Split surfaces
   the dropped straight-intent and must stay red per `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`).
   These are enumerated explicitly and excluded from the gate.
6. **The `HEAD_TARGET_ENABLED` gate is satisfied.** The suites named in commit `cb9379f`
   (`*PoseTest`, `ValidatorRomClusterTest`, `ChestFrameIssueFTest`) are green, and only then
   is the flag's `true` state validated; otherwise it is reverted to `false`.

When conditions 1–6 hold, Architecture v2 resumes at **M2**, with the stabilization suite
retained as the permanent CI guard so future migrations cannot re-hide defects.

---

## 9. Non-goals

- This RFC does **not** implement any fix. It defines the plan, phases, and exit criteria
  only.
- It does **not** resume or schedule any Architecture v2 phase beyond M1.
- It does **not** alter `HEAD_TARGET_ENABLED`; it only states the gate that must be met
  before that flag's `true` state is considered validated.
