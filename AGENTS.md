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

## Build toolchain (see `docs/TOOLCHAIN_PROVISIONING.md` for the full recipe)

- **JDK 17** at `/usr/lib/jvm/java-17-openjdk-amd64`.
- **Gradle 8.7** (wrapper-managed); `GRADLE_USER_HOME=<ws>/.gradle-home`.
- **Android SDK 34** in `<ws>/android-sdk` (build-tools 34.0.0, platforms;android-34).
- Build matrix: **AGP 8.5.0, Kotlin 2.0.0, compileSdk 34, minSdk 24, targetSdk 28**.
- Required env each session:
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export ANDROID_HOME="$(pwd)/android-sdk"
  export GRADLE_USER_HOME="$(pwd)/.gradle-home"
  export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$(pwd)/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  ```

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
  `GIT_SSL_CAINFO="$(pwd)/git-proxy-ca.pem" git push origin HEAD`. Re-harvest if it rotates.
- Git-ignored, never commit: `.gradle-home/ .gradle-dist/ android-sdk/ proxy-ca.pem custom-cacerts git-proxy-ca.pem`.

## Test baseline (see `docs/TEST_BASELINE.md`)

- `./gradlew :app:testDebugUnitTest` → **168 tests / 30 failures = GREEN-for-us**
  (165/30 without Issue E). The 30 are **pre-existing on `main`**, not regressions.
- Two failure families: (1) `BONE_LENGTH` frame-0 arm/hand validation;
  (2) stale hard-coded expected positions (e.g. pelvis hang `230` vs `~240`).
- **4 test files have pre-existing compile errors** (missing `kotlin.math` imports /
  3-arg `max`): `ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`,
  `VerticalPullPosesTest`. Do NOT "fix" them during unrelated tasks.

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

## Git

- Branch: `session/agent_b795bd6c-5a92-47c7-9d9c-5693da550c5a`.
- Chain: `8e099ff` origin/main → `3aa8aff` (.kilocodeignore + .gitignore) →
  `04b7be2` (refined .kilocodeignore) → `bcbee92` (Issue E, pushed).
- `.kilocodeignore` = precise context-boundary ignore (committed).

## Config note

- `kilo.jsonc` uses a strict schema that rejects unknown top-level keys, so durable memory
  lives HERE in `AGENTS.md` (auto-injected) rather than as a `memoryAnchor` key in
  `kilo.jsonc` (which would invalidate the whole config).
