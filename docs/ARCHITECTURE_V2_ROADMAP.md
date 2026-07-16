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
- **STATUS: COMPLETE** (commit `0426c76` — `ConstraintSolver` seeds root from `PostureIntent` (F2), weights the root step by `contactPrecedence` (F7), adds inter-frame temporal smoothing of the solved root (F9) behind `EngineFlags.SOLVER_OWNS_POSTURE`; `declarePosture` helper on `BasePose`/`BaseValidationPose`; validation poses declare SEATED/HANGING intent).

## Phase 3 — Finalizer owns exclusive conversion + read-only chest-frame guarantee
- **Goal:** Finalizer only writer of local transforms; `reconstructChestFrame` provably read-only on settled contacts.
- **Rationale:** F1 (Critical), F4.
- **Prereq:** Phase 1 (IK world-only), Phase 2 (Solver settled root). **Files:** `SkeletonPoseFinalizer.kt`, `BasePose.bakeIkLimb`. **APIs:** `preConvertPoles()`; chest-frame no-move guard; remove deprecated overload.
- **Validation:** new test asserting `reconstructChestFrame` leaves contact world positions unchanged. **Risk:** Medium. **Complete when:** Finalizer holds all `toLocalDirection`; guard active; Solver→Finalizer order enforced.
- **STATUS: COMPLETE** (this change — `SkeletonPoseFinalizer.preConvertPoles()` established as the single local-transform conversion entry; `reconstructChestFrame` gains the B5/F1 read-only chest-frame no-move guard: snapshots Solver-settled contact end-effectors, asserts them unchanged, and rolls the frame back (flagging `rootTranslationDelta`) if it would displace a contact; gated by `EngineFlags.FINALIZER_OWNS_CONVERSION`. The deprecated frame-relative `bakeIkLimb` overload is retained until its ~18 pose callers are migrated in a follow-up, per B3's keep-legacy-during-migration rule).

## Phase 4 — Delete limb counter-rotation (`rotAround` lean-cancel, W11/G1)
- **Goal:** remove the `rotAround(..., ±leanAngle/±torsoAngle)` cancels that undo an inherited trunk tilt so a limb stays flat; engine keeps limbs flat.
- **Rationale:** G1 — largest residual leak.
- **Prereq:** Phase 2 + Phase 3. **Files:** the 9 pose files that still drove IK with manual `solveIK` + a Z-lean-cancel `rotAround` (Burpee, GluteBridge, PelvicTilt, KettlebellSwing, LatStretch, MountainClimber, ReverseSnowAngel, CouchStretch, HalfKneelingStretch). **APIs:** none (deletion). The other roadmap-named files (BaseThoracic, BaseLunge, BaseVerticalPull, StaticForearmPlank, ProneCobraStretch, BasePushUp, PikePushUp, IsometricSidePlank) had already migrated to engine `bakeIkLimb` / legitimate frame conversions (W1) — they contain no W11/G1 lean-cancel and were left intact. Flat-foot-on-horizontal-shin retained via `extremityOverrides`.
- **Validation:** `ChestFrameIssueFTest`, `ValidatorRomClusterTest`, per-pose diff; watch `FOOT_GROUND_PENETRATION`. **Risk:** **High** (sign-sensitive). **Complete when:** zero `rotAround` lean-cancel sites; geometry matches baseline or stamped override.
- **STATUS: COMPLETE** (this change — 60 `rotAround(..., ±leanAngle/±torsoAngle)` lean-cancel calls deleted across 9 pose files; each IK offset is now written directly to the joint local position and the limb stays flat via the engine. `ValidatorRomClusterTest` (18/20, the 2 failures are pre-existing on `main` and unrelated) and `ChestFrameIssueFTest` (green) match baseline).

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

## Phase 8 — Validation reads stamped state only (W9/W10 closure)
- **Goal:** Validator consumes stamps directly; drops post-hoc angle inference.
- **Rationale:** completes observer boundary.
- **Prereq:** Phase 0 + Phase 1–2. **Files:** `ExerciseValidator.kt`. **APIs:** rule bodies read §1.2; remove angle-inference helpers.
- **Validation:** validator unit tests; diagnostic instruments still flag drops. **Risk:** Low. **Complete when:** no validator rule reconstructs geometry.

## Dependency chain
```
Phase 0 ─┬─ Phase 1
         ├─ Phase 2 ──┬─ Phase 3 ──┬─ Phase 4
         │            │            ├─ Phase 5
         │            │            ├─ Phase 6
         │            │            └─ Phase 7
         └─ Phase 8 (any time after 0+1+2)
```
Phases 4–7 merge independently once 0–3 land. Phase 8 independent of 4–7.

## Continuous-correctness guarantees
- Phases 0–3: engine-internal only; zero pose regression.
- Phases 4–7: delete pose math only after engine owns equivalent behavior (flag-gated, per-pose baseline diff).
- Every phase ships green against `ValidatorRomClusterTest` + `ChestFrameIssueFTest` + `*PoseTest`.
- No phase modifies validation poses or retunes constants.
