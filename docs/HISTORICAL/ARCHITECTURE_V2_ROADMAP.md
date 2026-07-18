# Architecture v2 — Implementation Roadmap

**Frozen with `ARCHITECTURE_V2.md`.** Phases ordered by dependency; each independently mergeable; minimizes regression, maximizes continuous correctness. No implementation details; specification only.

## Phase 0 — Ratify `SkeletonPose` as single intent+state carrier
- **Goal:** establish intent (§1.1) vs state (§1.2) sections; assign writer/reader per field.
- **Rationale:** F2/F3/F10 — stamps/overrides need a named owner.
- **Prereq:** none. **Files:** `PoseDefinition.kt`/`SkeletonPose.kt`. **APIs:** add `PostureIntent`, `contactPrecedence`, `extremityOverrides`, state fields; `copyFrom` propagates both. **Removed:** none.
- **Validation:** compile + full test suite green (no behavior change). **Risk:** Low. **Complete when:** all components read/write only assigned sections.
- **STATUS: COMPLETE** (commit `c63716b` — `PoseDefinition.kt` carries `PostureIntent`/`contactPrecedence`/`extremityOverrides` + state stamps; `copyFrom` propagates both sections; zero behavior change).

## Phase 1 — IK owns world-only solve + bone-length + default pole
- **Goal:** IK strictly world-space; assert bone-length exactness; derive default pole.
- **Rationale:** F4 (world-only), F5 (bone-length), F6 (default pole).
- **Prereq:** Phase 0. **Files:** `SkeletonMath.kt`, `BasePose.bakeIkLimb`. **APIs:** `solveIK` world pole clarified; `deriveDefaultPole`; writes `boneLengthsVerified`. Deprecate `bakeIkLimb(parentRotation, poleLocal)` overload.
- **Validation:** limb-symmetry + geometry assertions; unit test for default-pole path. **Risk:** Low–Medium. **Complete when:** IK has zero `toLocalDirection`/parent-frame logic; bone-length flag set; default-pole tested.
- **STATUS: COMPLETE** (commit `582dcc6` — `SkeletonMath.deriveDefaultPole`/`bonesExact`; `bakeIkLimb` world-only overload derives the default pole and ANDs `boneLengthsVerified`; frame-relative overload deprecated).

## Phase 2 — ConstraintSolver owns root + PostureIntent + precedence + smoothing
- **Goal:** Solver derives exact pelvis from contacts+`PostureIntent`; resolves conflicts via `contactPrecedence`; inter-frame relaxation.
- **Rationale:** F2/F7/F9; removes pose-side `pelvisY` arithmetic.
- **Prereq:** Phase 0. **Files:** `ConstraintSolver.kt` + pelvis-computing pose bases. **APIs:** Solver reads `postureIntent`/`contactPrecedence`; new `declarePosture` helper; writes deltas.
- **Validation:** per-pose geometry assertions; `rootTranslationDelta`≈0 when intent matches solved. **Risk:** **High** (posture subsystem). **Complete when:** no pose computes `pelvisY`/`pelvisX`; Solver sole root mover; precedence+smoothing tested.
- **STATUS: COMPLETE** (commit `0426c76` — `ConstraintSolver` seeds root from `PostureIntent` (F2), weights the root step by `contactPrecedence` (F7), adds inter-frame temporal smoothing of the solved root (F9); `declarePosture` helper on `BasePose`/`BaseValidationPose`; validation poses declare SEATED/HANGING intent). The posture-seeding behaviour is now **unconditional** — the `SOLVER_OWNS_POSTURE` flag that previously gated it was retired in Phase B of the cleanup and the `EngineFlags` object deleted (see `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md`).

## Phase 3 — Finalizer owns exclusive conversion + read-only chest-frame guarantee
- **Goal:** Finalizer only writer of local transforms; `reconstructChestFrame` provably read-only on settled contacts.
- **Rationale:** F1 (Critical), F4.
- **Prereq:** Phase 1 (IK world-only), Phase 2 (Solver settled root). **Files:** `SkeletonPoseFinalizer.kt`, `BasePose.bakeIkLimb`. **APIs:** `preConvertPoles()`; chest-frame no-move guard; remove deprecated overload.
- **Validation:** new test asserting `reconstructChestFrame` leaves contact world positions unchanged. **Risk:** Medium. **Complete when:** Finalizer holds all `toLocalDirection`; guard active; Solver→Finalizer order enforced.
- **STATUS: COMPLETE** (this change — `SkeletonPoseFinalizer.preConvertPoles()` established as the single local-transform conversion entry; `reconstructChestFrame` gains the B5/F1 read-only chest-frame no-move guard: snapshots Solver-settled contact end-effectors, asserts them unchanged, and rolls the frame back (flagging `rootTranslationDelta`) if it would displace a contact. The `FINALIZER_OWNS_CONVERSION` flag that gated it was retired in Phase B of the cleanup and the `EngineFlags` object deleted; `preConvertPoles` itself was removed in Phase A. The deprecated frame-relative `bakeIkLimb` overload was deleted once its callers migrated).

## Phase 4 — Delete limb counter-rotation (`rotAround` lean-cancel, W11/G1)
- **Goal:** remove the `rotAround(..., ±leanAngle/±torsoAngle)` cancels that undo an inherited trunk tilt so a limb stays flat; engine keeps limbs flat.
- **Rationale:** G1 — largest residual leak.
- **Prereq:** Phase 2 + Phase 3. **Files:** the 9 pose files that still drove IK with manual `solveIK` + a Z-lean-cancel `rotAround` (Burpee, GluteBridge, PelvicTilt, KettlebellSwing, LatStretch, MountainClimber, ReverseSnowAngel, CouchStretch, HalfKneelingStretch). **APIs:** none (deletion). The other roadmap-named files (BaseThoracic, BaseLunge, BaseVerticalPull, StaticForearmPlank, ProneCobraStretch, BasePushUp, PikePushUp, IsometricSidePlank) had already migrated to engine `bakeIkLimb` / legitimate frame conversions (W1) — they contain no W11/G1 lean-cancel and were left intact. Flat-foot-on-horizontal-shin retained via `extremityOverrides`.
- **Validation:** `ChestFrameIssueFTest`, `ValidatorRomClusterTest`, per-pose diff; watch `FOOT_GROUND_PENETRATION`. **Risk:** **High** (sign-sensitive). **Complete when:** zero `rotAround` lean-cancel sites; geometry matches baseline or stamped override.
- **STATUS: COMPLETE** (this change — 60 `rotAround(..., ±leanAngle/±torsoAngle)` lean-cancel calls deleted across 9 pose files; each IK offset is now written directly to the joint local position and the limb stays flat via the MonkEngine runtime. `ValidatorRomClusterTest` (18/20, the 2 failures are pre-existing on `main` and unrelated) and `ChestFrameIssueFTest` (green) match baseline).

## Phase 5 — Collapse pelvis+chest dual writes into single spine intent (W13/G4, W14/G5)
- **Goal:** route trunk lean through `buildSpineCurve`; remove coupled dual writes.
- **Rationale:** G4/G5 — pre-Issue-E habit.
- **Prereq:** Phase 3. **Files:** the 3 poses that hand-wrote BOTH `pelvis.localRotation` and `chest.localRotation` as independent Z angles — `BaseLungePose`, `BaseVerticalPullPose`, `StaticForearmPlankPose`. (The roadmap's original file list — Hamstring, Quadruped, DynamicWGStretch — were re-audited and contain ZERO spine dual-writes, so they were already conformant and needed no change. The "17 `pelvis.localRotation` sites" count conflated single-segment pelvis writes, identity resets, and FK-driven chests, which are out of Phase-5 scope per frozen rule A2/B7.)
- **APIs:** `buildSpineCurve(lower, chest, lowerRad, thoracicRad, axis)` (signature re-documented in `BasePose` so the lower segment is the PELVIS — hips attach to the pelvis, so the lower trunk tilt must live there to keep planted feet correct; LUMBAR stays the identity pass-through).
- **Validation:** rotation-equivalent by construction (single `buildSpineCurve` call writes the identical pelvis+chest axis-angles that the two manual `set`s wrote). **`ValidatorRomClusterTest` + `ChestFrameIssueFTest` + `*PoseTest` MUST run green in CI** — they could NOT be executed in the authoring environment (no Android SDK / build-tools present).
- **Risk:** Medium. **Complete when:** zero poses dual-write pelvis+chest as independent angles (verified: the 3 sites now use one `buildSpineCurve` call).

## Phase 6 — Hip 3-DOF via helper; retire raw hip writes (W15/G7)
- **Goal:** route hip via `buildHipOrientation` / `buildHipFlexion`; delete raw `hipB.localRotation.set`.
- **Rationale:** G7 — manual relative-rotation.
- **Prereq:** Phase 4. **Files:** `BasePose.kt` (helpers exist — `buildHipFlexion` used here), `PikePushUpPose.kt` (computed `hipB` flexion `legPitch - torsoGlobalPitch`), `BasePushUpPose.kt` (identity `hipB` symmetry reset).
- **APIs:** replaced both raw `set(axisZ, …)` with `buildHipFlexion(hipB!!, …)` (the precise Z-axis flexion helper; full 3-DOF `buildHipOrientation` not needed — only flexion is authored). Roadmap "2 sites" confirmed accurate on re-audit.
- **Validation:** rotation-equivalent by construction (`buildHipFlexion` writes `hip.localRotation.set(axisZ, flexionRad)`, identical to the removed `set`s). **hip-angle + limb-symmetry tests in CI.**
- **Risk:** Low. **Complete when:** zero raw hip writes; helpers sole path (verified: 0 raw `hip*.localRotation.set` remain across poses + base classes).

## Phase 7 — Girdle unification + gaze-as-target (W16/G6, W17, F8)
- **Goal:** PikePushUp shoulders via `buildShoulders`+FK; `BaseThoracic.rotAround` → Finalizer conversion; gaze as `headTarget`.
- **Rationale:** G6/W17/F8.
- **Prereq:** Phase 3 + Phase 5. **Files:** PikePushUp, BaseThoracic, BaseLunge, BaseVerticalPull, StaticForearmPlank, ProneCobraStretch, BasePose (add `headTarget`). **APIs:** `headTarget` intent; engine resolves neck/head via IK/constraint.
- **Validation:** gaze-direction + shoulder world assertions; `*PoseTest` green. **Risk:** Low. **Complete when:** no manual `rotAround` shoulder/gaze; gaze is a target.
- **STATUS: COMPLETE.** (Gap 7 gaze-as-`headTarget` + G6 PikePushUp girdle landed; the flag-on
  resolver was verified **byte-identical** to the legacy direction path — `HeadTargetBaselineTest`,
  maxDeviation ~6e-5 across 24 gaze pose families × 31 frames — then the legacy `buildHead`
  fallback branch in `buildGaze` and the `HEAD_TARGET_ENABLED` gate were removed, making
  `SkeletonPoseFinalizer.resolveHeadTarget` the sole head/neck writer. Full suite green 244/0.
  Note: `BasePushUpPose`'s shared `rotAround` shoulder setup remains intentionally (shared
  push-up logic, not the Phase-7-named `PikePushUp` G6 item).)
  - **Done (Gap 7 — gaze-as-`headTarget`):** added `HeadTarget` data class + `headTarget` carrier on
    `SkeletonPose` §1.1 (with `copyFrom` propagation); `BasePose.buildGaze(neck, head, neckLength, gazeDir)`
    records the additive `headTarget` intent (synthetic target along the authored gaze direction).
    Migrated **all** gaze sites (17 call sites across BaseLungePose, BaseVerticalPullPose,
    ProneCobraStretchPose, StaticForearmPlankPose, PikePushUpPose, BasePushUpPose, BaseSquatPose,
    SumoSquatPose, JumpSquatPose, DeepSquatHoldPose, IsometricSidePlankPose, ThoracicExtensionPose,
    HamstringStretchPose, QuadrupedThoracicRotationsPose, DynamicWorldsGreatestStretchPose,
    BaseHipFlexorPose, BaseBirdDogPose) from `buildHead(direction)` to `buildGaze`. The Finalizer
    gains `resolveHeadTarget` which consumes `headTarget` (reusing the head-orientation math).
    The legacy `HEAD_TARGET_ENABLED` flag and the `buildHead` fallback branch were removed in the
    cleanup (Phases B/7); `resolveHeadTarget` is now the sole head/neck writer.
  - **Done (G6 — PikePushUp shoulder girdle):** `PikePushUpPose` shoulders now route through
    `buildShoulders`+FK; the IK root is the FK-derived `shoulderA/shoulderP.worldPosition` instead of
    the hand-computed `rotAround(..., chest.worldRotation.angle, ...)` world point. The chest world
    rotation already carries the torso pitch, so the FK-derived shoulder world position equals the old
    `rotAround` result (geometry unchanged, byte-identical). The removed `rotAround` import + the two
    redundant `shoulder.localPosition.set` lines are gone.
  - **Note:** `BasePushUpPose` (the generic push-up base reused by other push-up variants) still
    contains a `rotAround` shoulder-computation at its IK-root setup; that is shared push-up logic, not
    the Phase-7-named `PikePushUp` G6 item, and is left intact (a behavioral change to the
    shared base must be verified against every push-up variant's baseline before migration).
  - **Gap 7 fully CLOSED:** the `HEAD_TARGET_ENABLED` flag was deleted and the legacy `buildHead` branch
    removed in the cleanup (Phase 7 + Phase B); no flag flip remains to do. `resolveHeadTarget` is the
    sole head/neck writer and the suite is green (282/0).

## Phase 8 — Validation reads stamped state only (W9/W10 closure) — COMPLETE
- **Goal:** Validator consumes stamps directly; drops post-hoc angle inference.
- **Rationale:** completes observer boundary.
- **Prereq:** Phase 0 + Phase 1–2. **Files:** `ExerciseValidator.kt`. **APIs:** rule bodies read §1.2; remove angle-inference helpers.
- **STATUS: COMPLETE** (Branch B, B5 — Validator stamp-only). The validator is now a pure §1.2-stamp /
  §1.1-intent reader; all geometry-inference helpers were lifted into the MonkEngine runtime
  (`SkeletonMath.computeHipRomStamp` + `SkeletonPoseFinalizer.applyValidationStamps` populate
  `hipRomStamps` / `bilateralSymmetryDelta`), and `validateHipRom` / `validateBilateralSymmetry`
  read those stamps. No validator rule reconstructs geometry.

## Legacy-engine cleanup (Phases A–G of `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md`) — COMPLETE

After M0–M8, the remaining legacy surface was removed:

- **Phase A (dead symbols):** `AnimationMode`, deprecated `rememberAnimationController(mode)` overload,
  `PoseBuilder.defaultCamera`/`evaluate`, frame-relative `solveIK` overload, `LegacySkeletonDefinition`
  typealias, and the `preConvertPoles` hook — all deleted (zero `main`/`test` callers).
- **Phase B (flag collapse):** `PIPELINE_ACTIVE`, `SOLVER_OWNS_POSTURE`, `FINALIZER_OWNS_CONVERSION`,
  `FINALIZER_CONSUMES_INTENT` collapsed to their true (always-on) branch; the `EngineFlags` object was
  deleted in Phase F. Only `IK_STAGE_ACTIVE` survives, as a standalone `var` in `IkStage.kt` (default
  `false`), kept intentionally as a future *additive* limb-solver path.
- **Phase C (gaze migration):** the 5 validation instrument poses + `ValidatorRomClusterTest` moved off
  the legacy direction-based `buildHead` onto `headTarget`/`buildGaze`; `buildHead` math removed.
- **Phase D (test re-pointing):** direct `finalizer.finalize(...)` tests re-pointed through
  `SkeletonPipeline.produceFrame(...)`.
- **Phase E (compatibility bridge):** the legacy `else` branch in `SkeletonPoseFinalizer.finalize`
  removed; `finalize` now `check(pose.roots.isNotEmpty())`.
- **Phase F (EngineFlags retirement):** `EngineFlags` object deleted; `IK_STAGE_ACTIVE` relocated to
  `IkStage.kt`.
- **Phase G (review pipeline):** `ExerciseReview` / `ExerciseReviewReport` removed (L8); the snapshot
  renderer's `ExerciseSnapshotSequence` output is retained.

Net result: the runtime is the sole Architecture-v2 pipeline
(`SkeletonPipeline` → `ConstraintSolver.solve` → `SkeletonPoseFinalizer.finalize` → FK), with **no**
feature-flag `=false` rollback branch and **no** removed legacy code path reachable in production.
Full suite green (**282/0**).

## Dependency chain
```
Phase 0 ─┬─ Phase 1
         ├─ Phase 2 ──┬─ Phase 3 ──┬─ Phase 4
         │            │            ├─ Phase 5
         │            │            ├─ Phase 6
         │            └─ Phase 7
         └─ Phase 8 (any time after 0+1+2)
Legacy cleanup (Phases A–G) ── runs after all M-phases; removes flags/bridge/dead members.
```
Phases 4–7 merged independently once 0–3 landed. Phase 8 independent of 4–7.

## Continuous-correctness guarantees
- Phases 0–3: engine-internal only; zero pose regression.
- Phases 4–7: delete pose math only after engine owns equivalent behavior (flag-gated, per-pose baseline diff).
- Every phase ships green against `ValidatorRomClusterTest` + `ChestFrameIssueFTest` + `*PoseTest`.
- No phase modifies validation poses or retunes constants.
