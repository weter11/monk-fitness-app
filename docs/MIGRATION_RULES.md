# Migration Rules / Coding Constitution

**Frozen with Architecture v2.** This is the enforced coding standard for all pose and engine code. Violations are defects, not style preferences.

---

## Part A — Prohibited patterns (NEVER in production or validation poses)

These are the legacy engine-compensation leaks removed by the W1 audit and the trunk/girdle/hip audit. They must not reappear.

### A1. Manual pelvis positioning math
- `pelvisY = f(targets)`, `pelvisX = ...`, `pelvis.localPosition.set(...computed from reach/bar/ankle...)`.
- Any arithmetic deriving root translation from limb targets.
- **Owner instead:** Pose declares `postureIntent` (typed); Solver derives exact transform.

### A2. Pelvis/chest lean as compensation
- `pelvis.localRotation.set(axisZ, ±leanAngle)` where `leanAngle` is then cancelled in limbs.
- Dual independent `pelvis.localRotation.set` + `chest.localRotation.set` hand-tuned pairs.
- **Owner instead:** single `buildSpineCurve(lower, chest, lowerRad, thoracicRad, axis)` — one call, same Z axis. `lower` is the PELVIS (hips attach to the pelvis, so the lower tilt must live there to keep planted feet correct).

### A3. `rotAround` limb counter-rotation (W11/G1 — CLOSED in Phase 4)
- `rotAround(..., ±leanAngle)`, `rotAround(..., -torsoAngle)`, `rotAround(..., ±spinePitch)` on knee/ankle/elbow/hand to undo inherited trunk tilt.
- **Status:** all 60 remaining W11/G1 lean-cancel sites were deleted in Phase 4; limbs now stay flat via the engine (Solver residual distribution + Finalizer relative-rotation). Any new `rotAround` that re-cancels an inherited trunk tilt on a limb is a regression of this rule.
- **Owner instead:** Solver residual distribution + Finalizer relative-rotation. Limb stays flat automatically.

### A4. Raw hip writes (W15/G7)
- `hipF.localRotation.set(...)`, `hipB.localRotation.set(...)` with computed angles.
- **Owner instead:** `buildHipOrientation(flexion, abduction, rotation, sideSign)` for full 3-DOF, or `buildHipFlexion(hip, flexionRad)` when only Z-axis flexion is authored (Phase 6 / W15/G7).

### A5. Manual extremity endpoint authoring (W2/W3 — resolved by W1)
- `heel*/toe*.localPosition.set(...)`, `palm*/knuckles*/fingertips*.localPosition.set(...)`.
- Magic ratios `0.29/0.71`, `6/6/10`.
- **Owner instead:** Finalizer derives from limb + `FootDefinition`/`HandDefinition`. Override only via `extremityOverrides` for stylized geometry.

### A6. Hand-computed shoulder / chest-frame re-derivation (W16/G6)
- `rotAround(shoulderOffset, chest.worldRotation.angle, ...)` to place shoulders.
- Manual `shoulderA/P.localPosition.set(...)` bypassing `buildShoulders`.
- **Owner instead:** `buildShoulders` + FK.

### A7. Head-gaze counter-rotation (W17)
- `rotAround(UP, axisZ, -(pelvisAngle+chestPitch))` or any sum-of-leans gaze.
- **Owner instead:** declare `headTarget` (world gaze vector); engine resolves neck/head.

### A8. Frame-conversion in poses (W7)
- Passing `parentRotation` / local-frame pole to IK; `toLocalDirection`/`toWorldDirection` calls in pose code.
- **Owner instead:** Finalizer owns all world↔local conversion; IK is world-only.

### A9. Any new shared mutable state outside `SkeletonPose`
- Module-level buffers shared across components for handoff.
- **Owner instead:** `SkeletonPose` is the sole carrier (F10).

### A10. Validation influencing geometry
- Validator computing joint angles, compensating, or retuning poses.
- **Owner instead:** Validation reads stamped State only.

---

## Part B — Mandatory rules for new poses

### B1. Declare, never compute
A new pose describes **intent**: joint articulations (relative to parent), limb world targets, contacts, `postureIntent`, `contactPrecedence`, motion driver. It never computes a transform.

### B2. Use the intent helpers
- Spine: `buildSpineCurve` / `buildLumbarFlexion`.
- Chest: `buildChestTwist` / `buildChestSideBend` / `buildChestOrientation`.
- Hip: `buildHipOrientation` / `buildHipFlexion` / `buildHipAbduction` / `buildHipRotation`.
- Ankle: `buildAnkleArticulation`. Wrist: `buildWristArticulation`.
- Girdle: `buildClavicularRotation` / `buildScapularRotation`.
- Shoulders: `buildShoulders`. Pelvis offsets: `buildPelvis`. Head: `buildHead`.
- Gaze: declare `headTarget`, do not counter-rotate.

### B3. Specify contacts + precedence
Every fixed support (ground/bar/wall) is a `ContactSpec` with an entry in `contactPrecedence` when conflicts are possible.

### B4. Declare posture intent
Every pose sets a typed `postureIntent` (SEATED_NEAR_FLOOR / HANGING_UNDER_BAR / STANDING / CUSTOM) so the Solver can derive the root — never hand-place the pelvis.

### B5. Override only for stylized geometry
`extremityOverrides` is permitted **only** where the default derivation provably cannot express the exercise (pointed toe, sumo flare, side-plank roll, flat foot on near-horizontal shin). Every override is a greppable, deliberate opt-out.

### B6. Pass poles in the limb-root frame
Poles are authored in the limb's own root frame; the Finalizer converts them to world. Poses never call `toWorldDirection`/`toLocalDirection`.

### B7. No dual spine writes
Never write both `pelvis.localRotation` and `chest.localRotation` as independent angles. One `buildSpineCurve(lower, chest, lowerRad, thoracicRad, axis)` call expresses the whole trunk lean (Phase 5 / W13/G4, W14/G5). `lower` = PELVIS so the hips inherit the bend.

### B8. Leave geometry to the engine
After declaring intent + targets + contacts, a pose calls the bake/finalize pipeline and returns `SkeletonPose`. It does not compute knee/ankle/elbow/hand/shoulder/heel/toe transforms.

---

## Part C — Enforcement

- **Reviews:** any diff containing A1–A10 patterns is rejected outright.
- **New poses** must pass B1–B8 by construction.
- **Engine changes** must preserve the Frozen invariants (ARCHITECTURE_FREEZE.md §2) and the per-component contracts (API_CONTRACTS.md).
- The validator's diagnostic instruments (Middle Split, Deep Overhead Squat, Dead Hang, Pike Sit) remain honest meters; never retuned to hide an engine defect.

---

## Part D — Legacy references (historical, do not imitate)

The following documents describe the *old* leaky patterns and why they were removed. They are evidence, not guidance:
- `docs/ENGINE_RESPONSIBILITY_AUDIT_NEW.md` (W1–W10 workarounds)
- `docs/POSE_MIGRATION_REPORT.md` (extremity workaround removal)
- `docs/ENGINE_AUTOMATIC_ORIENTATION_AUDIT.md`
- `docs/HISTORICAL/` (UNI-* investigation)
