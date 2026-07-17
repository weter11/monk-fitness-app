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
- **Remaining 11 failures (truthful, see `docs/TEST_BASELINE.md`):** S1-residual IK/reach
  (`StandardPushUpPoseTest`, `KettlebellSwingPoseTest`, `BurpeePoseTest`, `KneePushUpPoseTest`,
  `SquatPosesTest`, `LungePosesTest` ×3, `VerticalPullPosesTest`) + S3 authoring
  (`DynamicStretchPosesTest`, `ThoracicAndHamstringStretchPosesTest`).
- `docs/TEST_BASELINE.md` rewritten to the truthful **236 / 11** baseline.

## Git

- Branch: `session/agent_b795bd6c-5a92-47c7-9d9c-5693da550c5a`.
- Chain: `8e099ff` origin/main → `3aa8aff` (.kilocodeignore + .gitignore) →
  `04b7be2` (refined .kilocodeignore) → `bcbee92` (Issue E, pushed).
- `.kilocodeignore` = precise context-boundary ignore (committed).

## Config note

- `kilo.jsonc` uses a strict schema that rejects unknown top-level keys, so durable memory
  lives HERE in `AGENTS.md` (auto-injected) rather than as a `memoryAnchor` key in
  `kilo.jsonc` (which would invalidate the whole config).
