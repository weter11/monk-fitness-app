# Engine Defect Remediation Plan

**Status:** ACTIVE — temporary stabilization plan. Supersedes normal roadmap cadence
until its Exit Criteria are met, after which work returns to **Architecture v2 / M2**.
**Owner:** engine stabilization.
**Baseline commit:** merge of `origin/main` into the stabilization branch (`236 tests, 9 failed`).
**Related docs:** `ARCHITECTURE_V2_ROADMAP.md`, `RFC_ENGINE_STABILIZATION.md`,
`ENGINE_AUTOMATIC_ORIENTATION_AUDIT.md`, `TEST_BASELINE.md`.

---

## 1. Purpose

Architecture v2 answers *"what should the MonkEngine runtime look like?"*. This document answers a
different, narrower question: *"what must be fixed in the **existing** engine before the
migration can safely continue?"*

The v2 roadmap (M0–M8) is therefore **not** extended with these items. This plan is a
temporary detour: fix the legacy defects that the stabilization audit exposed, reach a
stable green baseline, then resume M2.

**Why the roadmap is temporarily suspended.** Continuing the migration on top of a red
baseline means every future phase inherits ambiguous test signal: a newly-red test can no
longer be attributed cleanly to "the phase I just landed" versus "a pre-existing legacy
defect." The v2 flag doctrine (`IMPLEMENTATION_BRIDGE.md` §B6) requires each phase to be
verified against a known-good baseline before its flag is flipped. Nine unexplained
failures make that verification unsound.

**Why fixing legacy defects first reduces migration risk.**
- These are **real engine output defects** (short foot bones, ground penetration,
  unreachable IK targets, off-viewport head), not stale test expectations. Migrating
  around them would carry the defects forward into v2 code and re-discover them later, more
  expensively, entangled with new architecture.
- A green baseline restores the Validator as a **trustworthy regression instrument** for
  the remaining phases. A red baseline pressures future work to weaken thresholds — the
  exact failure mode RFC_ENGINE_STABILIZATION §5 forbids.
- The defects live in code that v2 keeps essentially unchanged (Finalizer extremity
  derivation, per-pose target/camera authoring). Fixing them now is *not* throwaway work —
  it is work v2 would have required anyway.

---

## 2. Current State

- **236 tests executed, 9 failing, 0 errors.** (Full suite; `./gradlew :app:testDebugUnitTest`.)
- The 9 failures span 4 distinct engine defects (see §3), affecting 8 test classes.
- **Control experiment (decisive):** the Architecture-v2 flags were temporarily enabled
  (`SOLVER_OWNS_POSTURE = true`, `FINALIZER_OWNS_CONVERSION = true`; `HEAD_TARGET_ENABLED`
  already true) and the full suite re-run. **Result: the identical 9 tests failed — no
  fixes, no new regressions.**
- **None of the 9 failing poses declare `PostureIntent`/contacts** (verified by search:
  0 references in `BurpeePose`, `KettlebellSwingPose`, `KneePushUpPose`, `BaseLungePose`,
  `BaseSquatPose`, `SumoSquatPose`, `BasePushUpPose`, `StandardPushUpPose`,
  `BaseVerticalPullPose`). The Phase-2 solver has no intent to consume for them.

**Conclusion:** the causes are **not** related to the Intent Pipeline. Enabling M2 changes
nothing because (a) the defects live in code M2 does not replace, and (b) the affected
poses never feed the intent pipeline. These are **legacy implementation bugs**, remediable
in place.

---

## 3. Defect Inventory

Grouped by **root-cause defect**, not by test. Four real defects (R1–R4) explain all 9
failures.

### R1 — Foot extremity derivation
**Affects:** BurpeePoseTest, KettlebellSwingPoseTest, KneePushUpPoseTest (foot bones).

The Finalizer derives heel/toe from the shank direction + ankle articulation via
`FootDefinition.computeHeelToe` (`ENGINE_AUTOMATIC_ORIENTATION_AUDIT` §1). The derived
foot is wrong in two observable ways:
- **Short bones (KneePushUp):** `BONE_LENGTH ANKLE_B→HEEL_B` actual `7.18` vs expected
  `10.15` — a **29.29 %** shortfall. Note `7.18 / 10.15 ≈ 0.707 = cos(45°)`, and the
  pitch clamp bound is exactly ±45° (`FootDefinition.minPitch/maxPitch`). The strong
  signal is that the derivation collapses the foot vector against the 45° clamp (or feeds a
  non-unit direction into `writeHeelToe`), scaling the bone by the horizontal projection.
- **Ground penetration (Burpee, Kettlebell):** `FOOT_GROUND_PENETRATION TOE_F y=-2.57`
  (Burpee, frame 0, static) and `y=-0.456` (Kettlebell, frame 31, dynamic) — the derived
  toe dips below `ground.level = 0`.

These poses deliberately do **not** hand-author the flat foot (see the in-code comments in
`BasePushUpPose`: *"the planted flat foot is intentionally NOT hand-authored here; any
visual shortfall is an engine limitation left exposed."*). So this is squarely an engine
derivation defect.

### R2 — Reach target authoring
**Affects:** StandardPushUpPoseTest, SquatPosesTest (Sumo), KneePushUpPoseTest (hand
alignment + reach).

The push-up/squat arm target is authored as
`handZ = ±shoulderWidth × gripWidthMultiplier` (`BasePushUpPose:160-161`) with the shoulder
at `Z = ±shoulderWidth`. Arm reach is `upperArmLength(80) + forearmLength(66) = 146`.
When the authored hand target's distance from the shoulder exceeds 146, IK clamps it and
the Validator reports `IK_TARGET_UNREACHABLE` (60 issues = every frame) plus, for the wide
knee variant, `HAND_SHOULDER_ALIGNMENT offset 38.9` and a clamp of **175 units**.

**Important nuance — the fix is NOT "shrink all grips":** `WidePushUpPose`
(`gripWidthMultiplier = 1.9`, wider than KneePushUp's `1.8`) and `DeclinePushUpPose`
(`1.5`, equal to Standard's `1.5`) **pass**. The discriminator is the *combined* target
geometry (grip × `handAnchorX` × vertical drop) exceeding reach for specific poses, not the
multiplier alone. The remediation must make the authored target **reachable-by-construction**
(clamp/derive the target to lie within the reach band, preserving the intended visual
stance), not blanket-reduce grip width.

### R3 — Lunge support anchoring
**Affects:** LungePosesTest::Forward, ::Reverse, ::Side.

`Support foot drift 88.0 / 86.0 / 122.6` — the test asserts the planted foot stays anchored
(`|ankle.x| < 0.5`, `ankle.z ≈ ±hipWidth×1.15`) throughout the plant phase, but the lunge
poses translate the whole body so the "planted" ankle slides by 86–123 units.
`BaseLungePose` authors body position directly and declares **no contact/precedence**, so
nothing pins the support foot. This is the only defect with an architectural flavour (a
declared contact staying fixed while the root moves is exactly Phase-2 precedence), but it
is fixable in the legacy engine by authoring the support foot as a fixed anchor — M2 is a
future nicety, not a prerequisite.

### R4 — Camera framing
**Affects:** VerticalPullPosesTest.

`standard frame 71 failed: [HEAD_VIEWPORT]` — at the top of the pull-up the body rises but
`BaseVerticalPullPose.verticalPullCamera` (`defaultPitch = 0.22`, `defaultZoom = 1.5`,
fixed) no longer frames `HEAD_POS`, which projects outside the 1000×1000 viewport
(`ExerciseValidator.validateHeadViewport`). Purely a per-pose camera-authoring defect.

---

## 4. Implementation Plan

### R1 — Foot extremity derivation
- **Root cause:** `FootDefinition.computeHeelToe` (orientation-aware overload) produces a
  foot direction whose effective magnitude / pitch collapses toward `cos(45°)` at the clamp
  bound, yielding short heel/toe bones and, for prone/dynamic feet, sub-ground toes. The
  neutral-forward composition + `applyPitchClamp` interaction must be corrected so the
  written bone always has full `footLength × ratio` magnitude and never projects below
  `ground.level` for a settled contact foot.
- **Affected files:** `animation/FootDefinition.kt` (derivation + clamp),
  `animation/SkeletonPoseFinalizer.kt` (`adjustFootOrientation` call site, ~`:454`), and
  possibly the ground-projection step in `ConstraintSolver` for contact feet.
- **Implementation:** (1) guarantee `writeHeelToe` receives a unit direction and scales by
  the full ratio (bone length is clamp-independent); the clamp may bound *elevation* but
  must not shrink *length*. (2) For settled contact feet, project the derived toe/heel onto
  or above `ground.level` (reuse the Phase-4-era support-plane projection). Add a focused
  unit test asserting `|ANKLE→HEEL| == footLength×heelRatio` and `toe.y ≥ ground.level` for
  neutral and pitched ankles.
- **Expected green tests:** `KneePushUpPoseTest` (BONE_LENGTH portion), `BurpeePoseTest`,
  `KettlebellSwingPoseTest`.
- **Regression risks:** heel/toe derivation feeds **every** pose with feet — highest blast
  radius. Watch all `*PoseTest` foot geometry, `ConstraintSolverTest`,
  `ValidatorRomClusterTest`.
- **Rollback:** the change is localized to `FootDefinition`/finalizer foot step; revert the
  two commits. Consider gating behind a temporary derivation flag if the blast radius
  proves large.

### R2 — Reach target authoring
- **Root cause:** authored hand target can exceed the `146` arm-reach band for specific
  grip×anchor combinations.
- **Affected files:** `poses/BasePushUpPose.kt` (`targetHandA/P` construction, `:160-161`),
  `poses/BaseSquatPose.kt` / `SumoSquatPose.kt` (arm target), possibly a shared reach
  helper in `SkeletonMath` / `BasePose`.
- **Implementation:** clamp/derive the authored hand target to the reachable sphere
  (`shoulder + min(‖target−shoulder‖, upperArm+forearm−ε) · dir`) so the target is
  reachable-by-construction while preserving stance direction. Do **not** reduce grip
  multipliers of currently-passing variants (`WidePushUp` 1.9, `Decline`/`Standard` 1.5
  must remain byte-identical). Add a unit assertion that authored targets satisfy
  `‖target−shoulder‖ ≤ upperArm+forearm` for the full push-up/squat family.
- **Expected green tests:** `StandardPushUpPoseTest`, `SquatPosesTest` (Sumo),
  `KneePushUpPoseTest` (IK_TARGET_UNREACHABLE + HAND_SHOULDER_ALIGNMENT portion).
- **Regression risks:** the reach clamp touches the whole push-up/squat family; verify
  `WidePushUpPoseTest`, `DeclinePushUpPoseTest`, `AirSquatPoseTest`,
  `PushUpGeometrySolverTest` stay green and visually unchanged.
- **Rollback:** revert the target-clamp commit; per-family and independent of R1.

### R3 — Lunge support anchoring
- **Root cause:** `BaseLungePose` moves the body without pinning the support foot; no
  contact declared.
- **Affected files:** `poses/BaseLungePose.kt` (and the 3 concrete lunge poses if they
  override stance).
- **Implementation (legacy):** author the support ankle at the fixed anchor
  (`x≈0, z=±hipWidth×1.15`) for the whole plant phase and move the *rest* of the body
  relative to it, so the planted foot does not slide. (The future M2 approach — declare the
  support foot as a contact with `contactPrecedence` and let the solver hold it — is noted
  but explicitly deferred to post-resumption.)
- **Expected green tests:** `LungePosesTest::testForwardLungeBiomechanics`,
  `::testReverseLungeBiomechanics`, `::testSideLungeBiomechanics`.
- **Regression risks:** low/localized to lunges; verify `StepUpBiomechanics` (same test
  class, currently green) does not regress.
- **Rollback:** revert the lunge authoring commit; isolated to lunge poses.

### R4 — Camera framing
- **Root cause:** fixed pull-up camera does not frame full vertical travel.
- **Affected files:** `poses/BaseVerticalPullPose.kt` (`verticalPullCamera`).
- **Implementation:** widen `defaultZoom` / adjust `defaultPitch` (or make the camera track
  the vertical range) so `HEAD_POS` stays within the viewport across all frames, including
  the top of the pull.
- **Expected green tests:** `VerticalPullPosesTest`.
- **Regression risks:** minimal — camera-only, single pose family; confirm other
  vertical-pull frames still frame correctly.
- **Rollback:** revert the camera-definition commit; fully isolated.

> **Execution note (R4 expanded to R4a + R4b).** R4 was authored as a camera-only defect
> because that was the only *visible* failure — the `HEAD_VIEWPORT` error fires early in the
> per-frame loop and short-circuits the test before its post-loop assertions run. Fixing it
> uncovered a second, previously-masked defect in the same test:
>
> - **R4a — camera framing (as planned).** `defaultZoom 1.5 → 1.1`; the head projected off the
>   *top* of the viewport near the top of the rep. Yaw/pitch unchanged (vertical travel, not
>   view angle). Verified by a projected-Y probe + zoom sweep across all six grip variants.
> - **R4b — hanging-leg reach (newly surfaced, R2-class).** With framing fixed, the loop
>   reached the `maxClamp < 0.1` assertion and exposed that the authored pendulum-leg ankle
>   target drifts past the leg reachable radius (`maxReach = 205.8`) as the body rises, so the
>   solver clamped (`maxIkClampAmount ≈ 4.45`). Fixed with the same
>   `SkeletonMath.clampTargetToReach` used in R2 — reachable-by-construction, clamp signal
>   stays honest (no threshold weakened).
>
> **Lesson:** an early-exiting per-frame ERROR can mask later assertions in the same test, so
> the pre-remediation failure inventory can undercount sub-defects. The Exit Criteria
> ("whole suite green", not "N specific tests flip") already guarded against this — the full
> re-run after R4 is what confirmed `243 / 0`.

---

## 5. Execution Order

```
R1  Foot extremity derivation
 ↓
R2  Reach target authoring
 ↓
R3  Lunge support anchoring
 ↓
R4  Camera framing
```

**Why this order yields maximum effect:**
1. **R1 first — highest leverage, deepest layer.** It is a single engine-derivation fix
   that clears the most tests (Burpee, Kettlebell, and KneePushUp's foot bones) and touches
   the layer everything else sits on. Fixing it first prevents R2/R3 work from being
   validated against still-broken feet.
2. **R2 second — same failing poses, next layer up.** KneePushUp and the squat/push-up
   family need both R1 (feet) and R2 (arm reach) to go fully green; doing R1 then R2 lets
   KneePushUp flip in a clean two-step and isolates any interaction.
3. **R3 third — independent, medium isolation.** Lunges are unrelated to R1/R2 but share the
   "contact should stay fixed" theme; sequencing after the MonkEngine runtime-level fixes keeps the
   diff attributable.
4. **R4 last — smallest, fully isolated, zero coupling.** Camera-only; trivially verifiable
   once the geometry beneath it is correct (fixing R1–R3 could shift head height, so framing
   is validated against final geometry).

Each R-step is an independently mergeable, independently rollback-able commit, re-running
the **full** suite after each to confirm monotonic progress and zero regressions.

---

## 6. Exit Criteria

Stabilization is complete — and M2 resumes — when **all** of the following hold:

> **STATUS (2026-07-17): ALL EXIT CRITERIA MET — stabilization complete.** Full suite green
> at **243 tests / 0 failures / 0 errors** (verified with `--rerun-tasks`). R1–R4 all fixed
> at root cause; no validator threshold weakened, no rule downgraded, no `config.allow*` flag
> changed; `TEST_BASELINE.md` updated. Work returns to Architecture v2 (Phase 7 → M2).

- All known legacy engine defects R1–R4 are fixed (root cause, not symptom).
- **All production `*PoseTest` classes pass** (the whole pose suite is green).
- The Validator hides **no** errors: no threshold was weakened, no rule was downgraded from
  ERROR, no failure was suppressed (`config.allow*` flags unchanged). The Validator remains
  a truthful regression instrument.
- The full suite is green and stable across repeated runs (no flakiness introduced).
- `TEST_BASELINE.md` is updated to the new truthful baseline.

> Framed as capability, not a raw number: the goal is *"every real defect the suite can see
> is fixed and the Validator is trustworthy,"* which in practice means the suite is green.

---

## 7. Relationship to Architecture v2

This document **does not replace** Architecture v2 and is **not** a new milestone in
M0–M8. It temporarily pauses migration work until the legacy engine reaches a stable,
green baseline. The v2 phase flags remain default-`false` (except the already-verified
`HEAD_TARGET_ENABLED`) throughout this plan; no v2 flag is flipped as part of R1–R4.

Once the Exit Criteria are met, work returns to **Phase 7 completion and the subsequent M2
flag-flip** on top of a clean baseline — the safe condition the v2 flag doctrine requires.
The remediation work is forward-compatible: R1 corrects the same Finalizer derivation v2
keeps, and R3's manual anchor is the legacy stand-in for the contact/precedence mechanism
v2 will later own.
