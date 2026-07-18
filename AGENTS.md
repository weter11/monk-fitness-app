# AGENTS.md — Durable Session Memory (memoryAnchor)

> This file is auto-injected into every Kilo session. It is the **memory anchor** that
> keeps critical context alive across token drops / context compaction. Re-read it first
> at the start of a session and update it when a durable fact changes.

## Governing rule — validation poses are DIAGNOSTIC INSTRUMENTS (not dev targets)

- **Validation poses are no longer development targets. They are diagnostic instruments.**
  A pose is a probe you point at the engine to read its true state; its reading must stay
  honest whether the engine passes or fails. You **fix the engine** or **record the reading** —
  you never retune a pose to make it read green (that is instrument tampering). This reverses the
  old `VALIDATION.md §2` "engine satisfies validation" wording; §2/§8/§9/§10/§11 are updated.
- **Middle Split** is the canonical instrument for the `straight=true` dropped-intent limitation:
  it requests straight limbs at in-proximal-radius targets (hip→foot ≈58.9 < L1 112) so
  `solveStraightLimb` returns a bent limb; the pose must READ that, not hide it. It was briefly
  retargeted to full reach (spread 79.2→232, pelvis→0) to go green — that was reverted this
  session. Test: `ValidatorRomClusterTest.middleSplitSurfacesDroppedStraightIntent` (asserts
  STRAIGHT_LIMB_INTENT is flagged). Audit: `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`. The "fix the pose"
  verdicts in `ENGINEERING_VALIDATION_AUDIT §1` and `PELVIC_HIP_COMPLEX_INVESTIGATION §P6` are
  SUPERSEDED (they were written under the old target model).

## Governing rule — Compile-first Policy (broken-windows for compilation)

- **A branch may never intentionally leave the repository in a non-compiling state.**
  The project must always remain in a buildable state. If any change introduces compilation
  errors they become the **highest-priority blocking defect**, must be fixed immediately, and
  the current task is **incomplete until compilation is restored**.
- **Compilation errors are not backlog items.** They are blocking defects — never postponed,
  never scheduled as debt. Technical debt may be scheduled; broken compilation may not.
- **Rationale:** a non-compiling repo gives invalid engineering feedback — it hides runtime
  failures, validation failures and architectural defects (exactly what PR #134 exposed: four
  compile-broken test files suppressed the whole `:app` test module, so 0 tests ran and 31
  real engine failures stayed invisible). Compile errors must never be postponed.
- **Every discovered compile error is fixed before additional feature work resumes.** The
  project never knowingly accumulates broken states (broken-windows policy).
- **Architecture decisions, RFCs and roadmaps must only be produced against a compiling
  codebase.** Work from a truthful, executable baseline — never from a red build.

## Build toolchain (see `docs/TOOLCHAIN_PROVISIONING.md` for the full recipe)

- **In this environment the whole toolchain is pre-provisioned under `/tmp/kilo`** (NOT
  the `/usr/lib/jvm` + workspace download flow the older docs assume). Use these paths —
  they are verified working:
  - **JDK 17** at `/tmp/kilo/jdk-17.0.19+10`.
  - **Android SDK 34** at `/tmp/kilo/android-sdk` (build-tools 34.0.0, platforms;android-34).
  - **Java truststore** (Cloudflare intercept CA already imported) at `/tmp/kilo/custom-cacerts`.
  - Gradle 8.7 is in `/tmp/kilo/gradle-8.7`; the wrapper downloads into `.gradle-home`.
- Build matrix: **AGP 8.5.0, Kotlin 2.0.0, compileSdk 34, minSdk 24, targetSdk 28**.
- Required env each session:
  ```bash
  export JAVA_HOME=/tmp/kilo/jdk-17.0.19+10
  export ANDROID_HOME=/tmp/kilo/android-sdk
  export GRADLE_USER_HOME="$(pwd)/.gradle-home"
  export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/tmp/kilo/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  ```
- Fallback only if `/tmp/kilo` is absent: the `/usr/lib/jvm` + workspace `android-sdk` +
  harvested `custom-cacerts` recipe in `docs/TOOLCHAIN_PROVISIONING.md` §3–§5.

## TLS intercept (the #1 time-sink — do not re-investigate)

- Everything HTTPS is re-signed by `CN = Cloudflare TLS proxy-everything Intercept CA`.
- **The shipped `proxy-ca.pem` does NOT match the live CA** → `signature check failed`.
  Harvest the LIVE chain instead:
  ```bash
  echo | openssl s_client -connect github.com:443 -servername github.com -showcerts \
    | awk '/BEGIN CERT/,/END CERT/' > git-proxy-ca.pem
  ```
- **Gradle (Java):** `custom-cacerts` = JDK cacerts + imported `cf-intercept` alias
  (storepass `changeit`, ~122 entries). Wired via `GRADLE_OPTS` above.
- **git (OpenSSL, separate!):** push with
  `GIT_SSL_CAINFO=/tmp/kilo/github.com.pem git push origin HEAD`. Re-harvest if it rotates.
- Git-ignored, never commit: `.gradle-home/ .gradle-dist/ android-sdk/ proxy-ca.pem custom-cacerts git-proxy-ca.pem`.

## Test baseline (see `docs/TEST_BASELINE.md`)

- `./gradlew :app:testDebugUnitTest` → baseline was **168 tests / 30 failures = GREEN-for-us**
  (165/30 without Issue E), measured with the four compile-broken files excluded. The 30
  are **pre-existing on `main`**, not regressions. Re-measure after the four files were
  fixed (they now compile and count).
- Two failure families: (1) `BONE_LENGTH` frame-0 arm/hand validation;
  (2) stale hard-coded expected positions (e.g. pelvis hang `230` vs `~240`).
- **4 test files previously had compile errors** (missing `kotlin.math` imports /
  3-arg `max`): `ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`,
  `VerticalPullPosesTest`. These have now been **fixed** (added the missing
  `kotlin.math.*` imports; rewrote the 3-arg `max` as a nested 2-arg `max`). They now
  compile and count toward the totals, so the "168 / 30" snapshot should be re-measured.

## Issue E — two-segment spine (DONE, committed `bcbee92`, pushed)

- Scope: `PELVIS → LUMBAR → CHEST` with independent lumbar/thoracic DOF, pass-through by
  default, **zero regression**.
- `Joint.kt` added `LUMBAR(32)`; `entries.size = 33`; indices `0..32` contiguous.
- Touched: `SkeletonFactory.kt` (`SkeletonNodes.lumbar`), `SkeletonPoseFinalizer.reconstructChestFrame`
  (compose vs `chest.parent` = lumbar), `BasePose.kt` (`buildLumbarFlexion`/`buildSpineCurve`),
  `BaseThoracicPose.kt` (`lumbar`), tests `SkeletonFactoryTest`,
  `ProceduralAnimationPerformanceRefactorTest`, new `LumbarThoracicSpineTest` (3 pos-based, green).
- Touched the same `reconstructChestFrame` (later fully fixed by Issue F — see below).

## Issue F — chest-frame reconstruction overwrites authored rotation + symmetric-thorax assumption (DONE)

- Root cause: `SkeletonPoseFinalizer.reconstructChestFrame` recomputed `chest.localRotation` from
  geometry (spine dir + shoulder line) and **overwrote** whatever the pose author built via
  `buildChestTwist` / `buildChestOrientation` / explicit `chest.localRotation.set(...)` (plank
  flex, lunge pitch, thoracic twist, validation poses). It also derived the forward axis purely
  from the shoulder line, forcing a **symmetric-thorax** assumption onto authored poses.
- The single-arg `Vector3.cross(v)` (SkeletonMath.kt:74) allocates a NEW vector and never mutates
  the scratch buffer, so `tempColX.set(lean).cross(shVec)` was a no-op on `tempColX` → degenerate
  matrix (colX == colY) → wrong chest world rotation even when neutral (e.g. push-up plank got a
  ~30° off frame; correct reconstruction == the FK frame).
- Fix (in `SkeletonPoseFinalizer.reconstructChestFrame`):
  - **Early-return when `chest.localRotation.angle != 0`** — authored thoracic rotation is the
    single source of truth; FK already propagated it to shoulders/arms/neck/head, and the
    already-flattened world transforms are left intact. This removes the overwrite + the
    symmetric-thorax assumption for any authored pose.
  - **Identity-chest fallback** (push-up-style trunk oriented by pelvis/legs): keep the
    geometry reconstruction but use the two-arg `cross(dst)` overload so the orthonormal frame is
    written into `tempColX`. For a symmetric thorax this equals the FK frame (zero regression);
    the degenerate-matrix bug is gone.
- Net effect restores pre-PR-09 correct behavior in both cases; authored chests are no longer
  clobbered. New `ChestFrameIssueFTest.kt` covers twist-preserved / flex-preserved / fallback-aligns.

## Engine stabilization (RFC_ENGINE_STABILIZATION)

- Roadmap: S0 baseline → S1 IK+ConstraintSolver → S2 Validator+stale-constants → S3
  pose authoring. Architecture v2 suspended after M1 until S0–S3 done.
- **STATUS (current):** stabilization + legacy engine remediation **complete** — full suite
  **251/0**. S0–S3 done; engine defects R1–R4 (`ENGINE_DEFECT_REMEDIATION_PLAN.md`) all fixed;
  Architecture-v2 **Phase 7 complete** (headTarget resolver is the sole gaze writer, legacy
  `buildHead` branch + `HEAD_TARGET_ENABLED` flag removed), **M0 complete** (`SkeletonPipeline`
  scaffold), **M2 complete** (`PIPELINE_ACTIVE=true`; `SkeletonPipeline.produceFrame` drives the
  ordered `build` → `ConstraintSolver.solve` → `SkeletonPoseFinalizer.finalize` → FK stage chain; the
  Finalizer's internal Solver call removed so the pipeline is the sole Solver/Finalizer owner;
  `SkeletonRenderer` + `SkeletonSnapshotRenderer` re-pointed to `produceFrame`), and **M3 complete**
  (`SOLVER_OWNS_POSTURE=true`; solver seeds the pelvis from `PostureIntent` (F2) + `contactPrecedence`
  weighting (F7) + inter-frame smoothing (F9) — pure flag flip, byte-identical for all production
  poses since none register engine contacts so the solver no-ops; only contact-bearing validation
  instruments are exercised; `ConstraintSolverPhase2Test` re-pointed through the pipeline proves
  seated/hanging seeds + flag-on==flag-off within 1u + determinism), and **M4 complete**
  (`FINALIZER_OWNS_CONVERSION=true`; the Finalizer is the exclusive local-transform writer and the
  `reconstructChestFrame` F1/B5 no-move guard is live — `preConvertPoles` active as a reserved no-op
  hook; the guard only fires for contact poses and never displaces hand/foot contacts, so output is
  byte-identical for all production poses; `FinalizerOwnsConversionM4Test` proves flag-on==flag-off
   (maxDev 0.0) and `ChestFrameNoMoveTest` was re-pointed through the pipeline so it genuinely runs
    the Solver+guard). **B1 (Branch B IkStage extraction) DONE** — `limbTargets` is now live: every
    `bakeIkLimb` forwards its end joint + world target into the carrier, and the pipeline-owned
    `IkStage` (`animation/IkStage.kt`, invoked in `SkeletonPipeline.runStages` before the
    ConstraintSolver) consumes it. Gated by `EngineFlags.IK_STAGE_ACTIVE` (default **false** →
    `bakeIkLimb` remains the sole solver, byte-identical baseline). The 5 pose-side IK wrappers
    (`solveArmIK`/`solveLegIK`/`solveStraightArmIK`/`solveStraightLegIK`/`solveNearStraightLeg`) are
    deleted; their 3 call sites now call `SkeletonMath` directly. `IkStageTest` proves stage-on == off
     (maxDev 0.0) across 13 production + 4 contact poses; `Section11CarriersTest` flipped to assert
     `limbTargets` populated. **B2 (Branch B Finalizer intent consumers) DONE** — `spineIntent` +
     `jointIntents` are now live: every trunk/hip/girdle/extremity authoring helper in `BasePose` and
     `BaseValidationPose` forwards its intent through the sole-mutator `IntentBuilder`, and
     `SkeletonPoseFinalizer.applyIntentCarriers` re-derives the node rotations from the carriers and
     re-propagates FK. The re-application is idempotent with the helper's node write (the helper keeps
     writing the node so build-time logic that reads a node's world transform keeps working), so output
     is byte-identical to the pre-B2 baseline. Gated by `EngineFlags.FINALIZER_CONSUMES_INTENT` (default
     **true**); a no-op for contact poses so the ConstraintSolver's settled contacts are never disturbed.
      `FinalizerIntentConsumersTest` proves consumer-on == off (maxDev 0.0); `Section11CarriersTest`
      flipped to assert `spineIntent`/`jointIntents` populated. **M5 is UNBLOCKED** — the RFC's "automatic
      once M2 lands" premise was false, but the deferred intent-only migration is no longer required to
      make the carriers live: B2 (Finalizer) completed the spine/joint carrier dead→live flip, and
      `extremityOverrides` was already live from W1. All of §1.1 (`contacts`/`contactPrecedence`/
      `postureIntent`/`limbTargets`/`spineIntent`/`jointIntents`/`extremityOverrides`) is now written and
      read by the engine. **B3 (Branch B Posture universality) DONE** — every concrete production pose now
      declares a `postureIntent`; the `ConstraintSolver` (and `SkeletonPipeline.runStages`) runs the
      posture seed for **any** pose naming a non-`CUSTOM` intent even with no contacts, and
      `seedRootFromPostureIntent` pins the seed exactly for contact-less poses (the relaxation loop is a
      no-op for them, so output is byte-identical to the authored height). The five static STANDING
      shapes (ArmCircles / FacePull / HipCars / ScapularRetraction / WallSlides) declare `STANDING` and no
      longer hand-write `pelvis.y` — the solver pins `standH` (`shinLength + thighLength + 25f`). All other
      production poses declare `CUSTOM` (shape-driven roots; the solver leaves the authored root
      untouched, the reversible B3 fallback). Contact poses keep the M3 damped-ease seed, so the seated/
      hanging regression contract (`ConstraintSolverPhase2Test`) is preserved. `PostureUniversalityTest`
       proves the B3 contract (STANDING poses solver-owned byte-identically; flag-off reverts to authored
       root). **Next safely-landable:** M6 (Validator stamp-only) or M7 (headTarget) or M8 cleanup
       (B4/B4a and Phase D are done).
- **B4 (Branch B Pose migration) IN PROGRESS** — step 1 (PR #155) + step 2 (PR #156) landed, both
  mixed-mode byte-identical (full suite **282/0** throughout):
  - Step 1: dead legacy helpers deleted (`buildRigidSegment`, `buildLumbarFlexion`, `buildWristArticulation`,
    `buildAnkleArticulation`, `buildHipAbduction` — from both `BasePose` and `BaseValidationPose`); every
    production pose that hand-wrote `pelvis`/`chest`/`lumbar` rotations now records a `jointIntents` entry
    via `declareJointIntent` (BasePose) or `SkeletonPose.IntentBuilder(...).joint(...)` (PoseBuilder), so the
    Finalizer (B2) consumes the carrier and reproduces the authored rotation idempotently. `BranchBFamilyMigrationTest`
    proves the migrated families populate `jointIntents` and render byte-identically (maxDev 0.0) with the
    consumer on vs off.
  - Step 2: more dead helper carriers deleted — `buildHipOrientation` (both bases, 0 callers) and
    `buildChestSideBend` (BasePose, 0 production callers); `TrunkFrameTest` re-points to `IntentBuilder`
    `declareJointIntent(CHEST, …)` directly, preserving the side-bend semantics.
   - Step 3 (B4 follow-up, byte-identical): the 26 production poses with a bare `pelvis.localRotation.set`
     now route through the package-level `declarePelvisTilt(pelvis, buffer, axis, angle)` helper, which
     writes the node for build-time FK and records the `Joint.PELVIS` joint intent on the pose buffer
     (idempotent B2 consume). The B4 pelvis gap is fully closed; full suite **282/0**.
   - **B4a (ROM-Intent migration, DONE)** — post-`RFC_BRANCH_B_REPLAN`, the last 5 bare ROM writes are
     now carrier-backed (mixed mode, byte-identical, full suite **282/0**): `ThoracicExtension`
     lumbar+chest → `buildSpineCurve`/`spineIntent`; `GluteBridge` + `PelvicTilt` neck →
     `IntentBuilder.joint(Joint.NECK_END, …)`; `BasePushUp` hipF → `buildHipFlexion`. Dead ROM helper
     `buildChestOrientation` deleted from both `BasePose` (also removed its now-unused chest-orientation
     scratch buffers) and `BaseValidationPose`; `TrunkFrameTest` inlines its lean+twist+side-bend
     composition. The 3 obsolete §1.1 fields `motion`/`camera`/`environment` are RETIRED from
     `SkeletonPose` (declaration, `copyFrom`, `IntentBuilder` setters/`reset`, `IntentBuilderSubstrateTest`
     assertion) — they were never read by any engine stage. `Section11CarriersTest.romCarriersLiveAfterB4a`
     pins the ROM carriers are live for the migrated families. Per RFC_BRANCH_B_REPLAN §3, B4a is the sole
     remaining implementation stage; `buildSpineCurve`/`buildChestTwist`/`buildHipFlexion`/`buildHipRotation`
     remain as the carrier-backed authoring surface (their node-writes stay per the mixed-mode contract),
     and B4b is doc-only (Shape Constraints / Articulation recognized, not migrated).
   - Remaining (post-B4a, per replan §7): all structural-offset helpers (`buildTorso`, `buildPelvis`,
      `buildShoulders`, `buildHead`-offset, `buildRigidSegment`) and knee/segment writes are
      **Shape Constraints**, recognized valid direct node writes that must NOT migrate.
    - **Branch C (Extremity Articulation) DONE** — §1.3 Interaction/Articulation Intent now carried in
       `SkeletonPose.extremityArticulations` (`MutableMap<Extremity, JointRotation>`, the RFC hypothesis
       carrier). The Finalizer's `articulationFor` reads it (falling back to the authored node's **local**
       rotation while empty — see below); `buildWristArticulation`/`buildAnkleArticulation` (BasePose +
       BaseValidationPose) are the sole 2-DOF authoring vocabulary (composers
       `buildWristRotation`/`buildAnkleRotation`); all 17 bare `HAND_*`/`ANKLE_*` `localRotation.set`
       sites + DeadHang's overhand grip migrated off `jointIntents` onto the carrier. `MANUAL_OVERRIDE`
       opt-out preserved (skip derivation = preserve authored endpoints). `ExtremityArticulationTest` pins
       carrier live + byte-identity (maxDev 0) + opt-out + 2-DOF combine. Validation/IK/Contact
       transparent (AUTOMATIC path byte-identical).
       - **Straight-limb fallback fix (post-DONE):** the original `articulationFor` derived the empty-carrier
         fallback via the world-relative `inverse(parentWorld) ∘ nodeWorld`. That collapses to the identity
         rotation for a *straight* limb (where the wrist/ankle world rotation equals its parent's, so the
         ancestor chain cancels and the authored local articulation is silently dropped — e.g. PikePushUp's
         `legPitch` foot orientation vanished). The fallback now reads the node's **local** rotation from the
         authored hierarchy, which equals the carrier exactly and recovers the authored articulation for
         straight limbs. Full suite **283/0**.
- **Phase D — direct-finalize test re-pointing (DONE):** tests no longer call
  `SkeletonPoseFinalizer(...).finalize(pose)` directly; they route through
  `SkeletonPipeline.produceFrame(pose).pose` so the real Solver→Finalizer stage chain runs
  (the finalizer's own doc warns direct callers skip the Solver for contact poses;
  `ConstraintSolverPhase2Test` already relied on this). Audit of all 57 `finalize(` call sites:
  - **6 control files left untouched** (they intentionally test the finalizer / direct path):
    `SkeletonPipelineM0Test` (byte-identity comparison), `ChestFrameNoMoveTest` (control block),
    `ChestFrameIssueFTest` (chest-frame reconstruction in isolation), `ConstraintSolverTest`
    (Solver+Finalizer in isolation), `LumbarThoracicSpineTest` (finalizer FK unit),
    `HeadTargetBaselineTest` (legacy direction-path A/B reference).
  - **27 production/pose-rendering test files re-pointed** (48 call sites): the `finalizer` field
    became `val pipeline = SkeletonPipeline(def)` and `finalizer.finalize(x)` →
    `pipeline.produceFrame(x).pose`; one inline `SkeletonPoseFinalizer(DEFAULT_ADULT)` in
    `ProceduralAnimationPerformanceRefactorTest` got a `pipeline` field. Byte-identical for every
    non-contact production pose (M3/M4 proven Solver no-op), so output is unchanged.
  - Audit + classification in `docs/PHASE_D_DIRECT_FINALIZE_REPOINTING.md`. Full suite **283/0**,
    compile-clean. This is pure test hygiene — no production-code change.
 - **B5 (Branch B Validator stamp-only) DONE** — the validator is now a pure §1.2-stamp / §1.1-intent
  reader; every geometry-inference path was lifted into the **engine** and the validator only consumes the
  resulting stamps (full suite **282/0**, byte-identical):
  - New §1.2 STATE stamps on `SkeletonPose`: `hipRomStamps` (per-hip `HipRomStamp` with
    excursion/sagittal/frontal/axial degrees) and `bilateralSymmetryDelta` + `bilateralOppositeBend`.
  - Engine production: `SkeletonMath.computeHipRomStamp` (the EXACT femur-direction math the old
    `validateHipRom` ran inline) + `SkeletonPoseFinalizer.applyValidationStamps` (run at end of
    `finalize`) populates them from the solved skeleton.
  - `validateHipRom` now reads `pose.hipRomStamps`; `validateBilateralSymmetry` reads the symmetry
    stamp; the removed inference helpers (`femoralTwistDegrees`, `getSignedPerpendicularDeviation2D`,
    `toLocalDirection`/`angleBetweenDegrees`/`atan2` usage) are gone from the validator. `ValidatorRomClusterTest`
    drives the engine stamp for the hip-ROM cases; end-to-end fixtures still route through the pipeline.
  - A B0-style compile guard asserting no inference symbols reappear in the validator is left as a
    follow-up (the symbols are removed today; see RFC_BRANCH_B_IMPLEMENTATION §B5).
- **S1 (DONE):** IK angular-clamp recording, chest-frame → shoulder propagation,
  ground-contact projection, foot support-plane. `ConstraintSolverTest`×2, `IKLimbHelperTest`,
  `TrunkFrameTest` green (7 tests).
- **S2 (DONE, Validator-rule portion):** suite moved **31 → 11 failures**.
  - Fixed RFC §6 "rule not firing" defects: `PELVIS_INTENT` + `CONTACT_PRESERVED` now
    emit `ERROR` (were `WARNING`, so `rule.isValid` stayed `true` and the two
    `ValidatorRomClusterTest` undocumented cases stayed red). `ValidatorRomClusterTest` all-green.
  - Corrected stale test constants (B2, engine output verified correct first):
    `ExerciseValidatorTest.createValidBasePose` elbow coords (arm bones must equal
    `upperArmLength`/`forearmLength`), `EnvironmentAnchorsTest` ×2 (pelvis hang
    220/230 → actual 242.9/240.9), `NewEnginePosesTest` ×8 (pelvis Y/X/Z step
    amplitudes → actual authored values).
  - Did NOT weaken `BONE_LENGTH`/`IK_TARGET_UNREACHABLE` thresholds — the remaining
    11 failures are upstream (S1-residual IK/reach + S3 pose authoring), per RFC §5 the
    Validator is last and must not mask them.
- **S3 (DONE, pose-authoring portion):** suite moved **11 → 9 failures**.
  - `QuadrupedThoracicRotationsPose`: reach's vertical component was authored in the chest's
    *rotating local frame* (chest-local +Y is forward in tabletop), so the "sweep up" ramp
    swept the hand forward/down. Re-authored the vertical component in world space (horizontal
    still follows the thorax) → elbow now sweeps up. `DynamicStretchPosesTest` green.
  - `ThoracicExtensionPose`: extension was driven by the chest node's own localRotation, which
    rotates children in place but never translates CHEST/HEAD → `chestX`/`headX` never moved.
    Re-authored the arch to originate at the `lumbar` (thoracolumbar) segment so the chest tips
    up and BACK (-X) carrying neck/head/shoulders. `ThoracicAndHamstringStretchPosesTest` green.
- **Remaining 9 failures (truthful, see `docs/TEST_BASELINE.md`):** all S1-residual IK/reach
  (`StandardPushUpPoseTest`, `KettlebellSwingPoseTest`, `BurpeePoseTest`, `KneePushUpPoseTest`,
  `SquatPosesTest`, `LungePosesTest` ×3, `VerticalPullPosesTest`). `BONE_LENGTH` frame-0 on
  arm/hand chain + `IK_TARGET_UNREACHABLE`; deferred to a follow-up S1-residual IK pass.
- `docs/TEST_BASELINE.md` rewritten to the truthful **236 / 9** baseline.

## Git

- Branch: `session/agent_b795bd6c-5a92-47c7-9d9c-5693da550c5a`.
- Chain: `8e099ff` origin/main → `3aa8aff` (.kilocodeignore + .gitignore) →
  `04b7be2` (refined .kilocodeignore) → `bcbee92` (Issue E, pushed).
- `.kilocodeignore` = precise context-boundary ignore (committed).

## Config note

- `kilo.jsonc` uses a strict schema that rejects unknown top-level keys, so durable memory
  lives HERE in `AGENTS.md` (auto-injected) rather than as a `memoryAnchor` key in
  `kilo.jsonc` (which would invalidate the whole config).
