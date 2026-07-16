# Engine Responsibility Audit (Rule №2)

> **Rule №2:** *Poses describe anatomy. The engine derives geometry. The engine has reached
> the stage where architecture matters more than individual poses.*
>
> **This document is an architecture investigation only. Nothing is implemented, fixed, or
> tuned.** In particular, no validation pose is retuned and no production pose is edited.
> The goal is a **complete responsibility audit** that moves every piece of anatomical
> *derivation* from pose authoring into the engine.
>
> Desired data flow:
>
> ```
> Pose ──(anatomical intent)──▶ Engine ──(derives geometry)──▶ Skeleton
> ```
>
> Pose authoring should describe **intent only** (where a joint should go, what articulation
> is wanted, what contacts are fixed). The engine owns all geometry *derivation*: foot/hand
> orientation, inherited-tilt compensation, pelvis compensation, limb completion, frame
> conversions, IK compensation.

---

## 0. Governing boundary (the target architecture)

| Concern | Owner (target) | Owner (today) | Gap |
| --- | --- | --- | --- |
| heel orientation | **engine** (derive) | pose (hand-set local pos) | bypass gate |
| toe orientation | **engine** (derive) | pose | bypass gate |
| palm orientation | **engine** (derive) | pose | bypass gate |
| fingertip orientation | **engine** (derive) | pose | bypass gate |
| inherited torso compensation | **engine** (relative rotation) | pose (counter-rotation) | pose re-implements |
| pelvis compensation | **engine** (solver tilt + CCD) | pose (hand-set pelvis height/lean, + solver) | partially engine, partially pose |
| limb orientation completion | **engine** (IK + FK) | engine | *already engine* |
| world/local frame conversions | **engine** (`bakeIkLimb`) | engine (with pose passing `parentRotation`) | mostly engine, pose still supplies frame |
| IK compensation (clamp/reach) | **engine** (`clampAmount`, ConstraintSolver) | engine (but re-bake clamp not surfaced) | engine, signal gap |

The remainder of this document enumerates **every workaround** found, with location / why it
exists / why it belongs in engine or pose / migration plan / risk. No code is changed.

---

## 1. Root-cause workaround: completion presence-gate (the structural bug)

### W1 — `SkeletonPoseFinalizer` skips foot/hand derivation because the factory always emits the endpoint nodes

- **Location**
  - `SkeletonPoseFinalizer.kt:166–169` — `cachedHasHeelToeF = containsJoint(roots, HEEL_F) && containsJoint(TOE_F)` (and the palm/hand equivalents).
  - `SkeletonPoseFinalizer.kt:374, 380, 386, 392` — `if (!cachedHasHeelToeF) adjustFootOrientation(...)` etc.
  - `SkeletonFactory.kt:79–102, 142–175` — `createStandardSkeleton()` **always** adds `HEEL_F/TOE_F/PALM_A/FINGERTIPS_A` as children of every ankle/hand.
- **Why it exists:** the gate was added as a *legacy compatibility bridge* — if a pose did
  not author endpoints, the finalizer would derive them from the limb. It made sense when
  some skeletons lacked those nodes.
- **Why it belongs in the engine:** it **is** in the engine — the bug is the gate is *always
  true*, so the engine's own derivation (`adjustFootOrientation`/`adjustHandOrientation`,
  `:442–513`) never runs. The factory unconditionally creating the nodes defeats the gate.
- **Migration plan:** change the gate semantics from "node present → skip" to "node *explicitly
  authored with non-default intent* → treat as override; otherwise always derive". Concretely:
  (a) have `bakeIkLimb`/`buildAnkleArticulation`/`buildWristArticulation` mark the limb as
  "engine-derived" and clear the hand-authored endpoint local positions, or (b) give
  `SkeletonPoseFinalizer` an explicit `deriveFoot(pose, side)` / `deriveHand(pose, side)` call
  that runs unconditionally after IK/FK, with author endpoints only applied when a pose opts
  in. The engine derivation already accepts an optional `ankleRotation`/`wristRotation`
  (relative to the parent) so intent is preserved.
- **Risk:** **High blast radius.** Flipping the gate changes every pose's rendered foot/hand
  at once. Mitigation: the engine derivation with *identity* ankle/wrist articulation is
  designed to equal the FK frame for neutral limbs (`computeHeelToe` doc, `FootDefinition.kt:37`,
  `adjustFootOrientation` `IDENTITY_ROTATION` path), so neutral poses should be unchanged; only
  poses that relied on hand-tuned endpoints will shift. Must be verified pose-by-pose against
  the validator baseline.

W1 is the **structural cause** of W2–W4 below: because derivation is skipped, poses must
author endpoints and cancel inherited tilt by hand.

---

## 2. Foot/hand endpoint workarounds (heel/toe/palm/fingertip orientation)

### W2 — explicit heel/toe authoring (hand-set foot endpoints)

- **Location (verified):** validation `MiddleSplitPose.kt:81–82`, `DeepOverheadSquatPose.kt:69–70`,
  `DeadHangPose.kt:128–129`, `PikeSitPose.kt:77–78`; production `BaseBirdDogPose.kt:123–124`,
  `ThoracicExtensionPose.kt:72–73`, `ProneCobraStretchPose.kt:106–107`, `BasePushUpPose.kt:109–112/137–140`
  (manually rotates foot dir by `-shinPitch/theta`), `PikePushUpPose.kt:75–78`, plus magic-ratio
  literals `0.29/0.71` in `ArmCirclesPose.kt:103–106`, `BurpeePose.kt:202–205`, `HipCarsPose.kt:115–118`,
  `KettlebellSwingPose.kt:88–89`, `LatStretchPose.kt:99–100`, `MountainClimberPose.kt:130–131`,
  `PelvicTiltPose.kt:113–116`, `ReverseSnowAngelPose.kt:86–87`, `ScapularRetractionPose.kt:103–106`,
  `WallSlidesPose.kt:103–106`, `FacePullPose.kt:103–106`, `GluteBridgePose.kt:117–120`.
- **Why it exists:** the engine derivation is gated off (W1), so the only way to give the foot a
  forward direction is to write `heelX/toeX.localPosition` directly.
- **Why it belongs in the engine:** heel/toe direction is pure anatomy (shank direction +
  ankle articulation + foot ratios owned by `FootDefinition`). The engine already derives it in
  `adjustFootOrientation` → `FootDefinition.computeHeelToe`. Poses should only set
  `buildAnkleArticulation(ankle, dorsiflexion, inversion)` (intent).
- **Migration plan:** delete the `heelX/toeX.localPosition.set(...)` lines; rely on W1's fixed
  gate; author foot orientation via `buildAnkleArticulation`. The `0.29/0.71` literals should be
  removed entirely (they duplicate `def.foot.heelRatio/toeRatio` and can diverge).
- **Risk:** Low–Medium per pose, **High aggregate** (≈40 sites). Each deletion must be checked
  that the engine-derived foot matches the intended look; stylized toes (pointed, curled) become
  the *only* legitimate use of explicit endpoints (override path).

### W3 — explicit palm/fingertips/knuckles authoring (hand-set hand endpoints)

- **Location (verified):** dominant `6/6/10` literal in `BaseBirdDogPose.kt:117–118`,
  `BaseThoracicPose.kt:150–151`, `ProneCobraStretchPose.kt:126–127`, `StaticForearmPlankPose.kt:155–156`,
  `BurpeePose.kt:220–221`, `HipCarsPose.kt:133–134`, `KettlebellSwingPose.kt:104–105`, `LatStretchPose.kt:116–117`,
  `WallSlidesPose.kt:128–129`, `PelvicTiltPose.kt`, `ScapularRetractionPose.kt`, `MountainClimberPose.kt:96–97`,
  `FacePullPose.kt`, `ReverseSnowAngelPose.kt`, `GluteBridgePose`; validation `MiddleSplitPose.kt:98`,
  `DeepOverheadSquatPose.kt:83`, `DeadHangPose.kt:110`, `PikeSitPose.kt:100–101`. `BasePushUpPose.kt:185–188`
  derives offsets from a unit `handDir` but still hand-authors.
- **Why it exists:** same as W2 — derivation gated off (W1).
- **Why it belongs in the engine:** palm/knuckles/fingertips are pure anatomy (forearm direction
  + wrist articulation + `HandDefinition` offsets). Derived in `adjustHandOrientation` →
  `HandDefinition.computeHandJoints`.
- **Migration plan:** delete the `palm/knuckles/fingertips.localPosition` writes; author via
  `buildWristArticulation(hand, flexion, deviation)`. Remove `6/6/10` literals (duplicate
  `HandDefinition`).
- **Risk:** Low–Medium per pose, **High aggregate** (≈30 sites). Same verification need as W2;
  grips (fist, curl) become the only legitimate override.

### W4 — inherited-torso compensation (counter-rotating ankle/wrist to "undo" parent tilt)

- **Location (verified):** `BaseLungePose.kt:82–105` (`bakeLeg`: `val localAngle =
  -parentRotation.angle; ankle.localRotation.set(axisZ, localAngle)` + manual heel/toe; `bakeArm`
  mirrors it `:111–128`); `StaticForearmPlankPose.kt:77–78` (`invChestZ = -chestWorldZ`,
  `invTorsoZ = -torsoPitch`, comments *"cancels"*); `PikeSitPose.kt:72–76` (the smoking gun:
  *"foot inherits trunk's forward tilt … counter-rotating by +fold cancels the inherited fold"*);
  `DeadHangPose.kt:124` (`invTorsoZ`); `IsometricSidePlankPose.kt:105–106,120–130`;
  `BaseBirdDogPose.kt:27,115–116,120–121` (`inverseTorsoPitch = -torsoPitch`);
  `ProneCobraStretchPose.kt:103–104,124–125`; `BaseThoracicPose.kt:148–149` (`-spinePitch`);
  `HamstringStretchPose.kt:103–104` (`torsoPitch - 1.57f`, magic π/2); `PikePushUpPose.kt:84,90,123–127`
  (`torsoGlobalPitch - legPitch`, `legPitch - torsoGlobalPitch`, `-torsoGlobalPitch`);
  `BaseVerticalPullPose.kt:232–233,240–249` (`invTorsoZ - plantarFlexion`, `invChestZ + HALF_PI`);
  squat family `BaseSquatPose.kt:86,97`, `JumpSquatPose.kt:91–92`, `BaseHipFlexorPose.kt:136,145`.
- **Why it exists:** FK propagates the parent (pelvis/chest) world rotation down to ankle/wrist;
  to keep the foot flat / hand aligned the author must *negate* the inherited tilt by hand. This
  duplicates the relative-rotation math the engine already performs in
  `SkeletonPoseFinalizer` (`relativeRotation(ankle, knee, relAnkle)`, `:377/383`, and the hand
  equivalents `:389/395`).
- **Why it belongs in the engine:** the engine's completion helpers already consume a
  **relative** rotation (articulation w.r.t. the parent segment), so inherited tilt is removed
  *automatically*. The author should set only the **net** ankle/wrist articulation (intent),
  never `-parentRotation.angle`.
- **Migration plan:** once W1's gate is fixed, these counter-rotations are **deleted** and
  replaced by `buildAnkleArticulation`/`buildWristArticulation` with the *net* desired
  articulation. The relative-rotation removal happens inside `adjustFootOrientation`/
  `adjustHandOrientation` (no pose code needed).
- **Risk:** **High** — this is the most safety-critical smell. A wrong sign in the engine's
  relative-rotation (e.g. the single-arg `cross` bug fixed in Issue F) would flip every foot/
  hand. Existing `ChestFrameIssueFTest` and limb-symmetry tests are the guardrails; run them per
  pose.

---

## 3. Pelvis / trunk compensation workarounds

### W5 — author hand-sets pelvis height / lean as a posture proxy

- **Location (verified, representative):** `MiddleSplitPose.kt:53` (`pelvisY = 14f`),
  `DeepOverheadSquatPose.kt` (`pelvis.localPosition.x = -25`, `lean`), `PikeSitPose.kt`
  (`pelvisY`, `fold`), `DeadHangPose.kt` (`pelvisY = 241 = 500−139−120` — manually computed to
  sit under the bar), `BaseSquatPose.kt`/`JumpSquatPose.kt`/`BaseHipFlexorPose.kt` (`leanAngle`),
  `BaseLungePose.kt` (`pelvis` offset), plus the global `ConstraintSolver` already does pelvis
  compensation (`ConstraintSolver.kt:46–67`, tilt + `TILT_GAIN`, and the UNI-1 CCD posture pass
  `:103–119`).
- **Why it exists:** to make a frozen pose land in a balanced, contact-respecting posture the
  author must pre-compute pelvis translation/tilt by hand (e.g. `DeadHang` pelvis height is
  derived from bar height minus arm length). The solver only *corrects* residual reach, it does
  not author the base posture.
- **Why it belongs in the engine vs pose:** **split.** The *base* posture intent (seated vs
  standing, how far the trunk leans) is **pose** (anatomical intent). But the *compensation*
  that reconciles fixed contacts (translate/tilt the root so feet/hands land exactly, distribute
  residual across the limb via CCD) is **engine** — and already partially implemented in
  `ConstraintSolver`. What leaks into pose authoring is the *manual pre-compensation* (exact
  pelvis Y math, opposed pelvis/chest rotations in `DeepOverheadSquat` §3.1 of
  `VALIDATION.md`).
- **Migration plan:** poses should declare **contacts + target reach**, not a solved pelvis
  transform. The engine's solver (which already supports translation + tilt + CCD posture pass)
  should own the reconciliation. Poses keep a *coarse* pelvis intent (e.g. "seated near floor")
  and the engine derives the exact compensated transform. Opposed pelvis/chest authoring should
  become a single `buildTrunkLean` intent the engine reconciles.
- **Risk:** **High.** Posture solving is the hardest part of the engine (see P1/P2/P4 in
  `PELVIC_HIP_COMPLEX_INVESTIGATION.md`: solver tilt uses the wrong axis for lateral imbalance;
  no true posture solve; pelvis-only compensation). Migrating pose pre-compensation into the
  engine requires maturing the solver first, or poses will regress.

---

## 4. Limb orientation completion (already engine-owned — confirm, do not change)

### W6 — none (informational)

- **Location:** `SkeletonMath.solveStraightLimb`/`solveIK`/`solveTriangleJoint`
  (`SkeletonMath.kt:598–683, 400–476`) and `bakeIkLimb` (`BasePose.kt:301–360`,
  `BaseValidationPose.kt:177–229`).
- **Why it exists:** analytical 2-bone IK + FK is the engine's core job; poses only pass a
  target + pole.
- **Why it belongs in the engine:** it **is** in the engine. **No migration needed.** This is the
  model the rest of the audit wants to extend to foot/hand/pelvis.
- **Risk:** none. Listed to confirm the audit's scope: limb *joint placement* is correctly
  engine-owned; only the *endpoint orientation* and *compensation* leak into poses.

---

## 5. World/local frame-conversion workarounds

### W7 — poses pass parent-frame `parentRotation` + local poles; engine re-derives world

- **Location:** `BasePose.bakeIkLimb` overload (`:274–291`) takes `parentRotation` +
  `poleLocal`, then `toWorldDirection(poleLocal, parentRot, ...)` (`:289`); `BaseValidationPose`
  same (`:246–248`). Production poses call these with `pelvis.worldRotation` /
  `chest.worldRotation` and a local-space pole (e.g. `BasePushUpPose` `legFPole` in chest frame).
- **Why it exists:** the analytical IK needs a **world-space** pole, but authoring a pole in the
  parent (chest/pelvis) local frame is more intuitive, so the engine converts it
  (`toWorldDirection`, `SkeletonMath.kt:691`). This conversion is **already engine-owned**.
- **Why it belongs in the engine:** it **is** in the engine. The remaining smell is that poses
  still *think* in `parentRotation` terms (they pass `pelvis!!.worldRotation`) — a small leak of
  engine frame knowledge into authoring. The cleaner intent API is "author the pole in the limb's
  own root frame; engine handles all conversion."
- **Migration plan (optional, low priority):** provide a `bakeIkLimb` overload that takes only
  the limb root node (not a separate `parentRotation`) and derives the frame internally, so poses
  never reference `worldRotation`. Reduces engine-knowledge leakage; not blocking.
- **Risk:** Low. Pure API simplification.

### W8 — `toLocalDirection` offset storage (engine-owned, verify it is complete)

- **Location:** `BasePose.kt:330–334` — `tempV1.set(ikResult.joint).subtract(rootWorldPos);
  SkeletonMath.toLocalDirection(tempV1, parentRot, middleNode.localPosition)` (and end node).
- **Why it exists:** stores the solved limb as *local* offsets so they survive the parent's full
  3D world rotation (the "no hand-fed inverse-Z scalar" note, `:328–329`).
- **Why it belongs in the engine:** it **is** in the engine. **No migration needed.**
- **Risk:** none. Listed for completeness — frame conversion of IK results is correctly engine-owned.

---

## 6. IK-compensation workarounds (engine-owned, but with signal gaps)

### W9 — `straight=true` silently bends; no bake-time intent signal; re-bake clamp not surfaced

- **Location:** `SkeletonMath.solveStraightLimb` UNI-9 `dist < L1` branch (`:660–668`);
  `ConstraintSolver.kt:283–314` (`canBeStraight` switch to `solveIK`); `BasePose.kt:322–326`
  propagates author-bake `clampAmount` to `pose.maxIkClampAmount`; the **global re-bake does not**
  write its clamp back; `ExerciseValidator.validateStraightLimbIntent` (`:709–726`,
  tolerance `175°`) is **post-hoc, contact-scoped**.
- **Why it exists:** the engine must not crash or collapse a bone when a straight limb is
  geometrically impossible; bending is the safe fallback. Detection was layered on as a validator
  rule later.
- **Why it belongs in the engine:** the *bend* is correctly engine behavior (and, under the
  diagnostic-instrument rule, the correct reading for a validation pose). But the **signal** that
  the intent was dropped is currently a validator concern, not an engine stamp. The engine should
  own the fact ("this `straight=true` limb could not be honored") at bake time.
- **Migration plan (architecture only):** add `ContactSpec.intentPreserved` (or a per-limb
  `straightIntentDropped` flag) set by both `solveStraightLimb` and `ConstraintSolver` at bake
  time; have `STRAIGHT_LIMB_INTENT` read that flag (+ geometry) instead of reverse-engineering
  joint angles; make the check **not** contact-scoped (any baked `straight` limb). No exception is
  thrown — bending remains the reading.
- **Risk:** Medium. Tightly coupled to the validator contract; must keep `STRAIGHT_LIMB_INTENT`
  a faithful meter (Middle Split still reads the drop). Coordinate with the validation-pose
  diagnostics work.

### W10 — clamp band is author-bake only; solver re-bake clamp invisible to `IK_TARGET_UNREACHABLE`

- **Location:** `BasePose.kt:322–326` (author bake → `maxIkClampAmount`); `ConstraintSolver.kt:300–322`
  (re-bake computes `ikResult` but never writes `pose.maxIkClampAmount`); `ExerciseValidator
  .validateIkTargetReachability` (`:622–633`, flags when `maxIkClampAmount > 0.1f`).
- **Why it exists:** the solver was added later (PR-04) and its re-bake path does not feed the
  shared `maxIkClampAmount` accumulator.
- **Why it belongs in the engine:** reachability accounting is engine state; the gap means a
  solver-induced bend is invisible to `IK_TARGET_UNREACHABLE`. Engine-internal fix.
- **Migration plan:** in `ConstraintSolver`, after each re-bake, fold `ikResult.clampAmount` into
  `pose.maxIkClampAmount` (and surface any straight-intent drop per W9). No pose change.
- **Risk:** Low. Validator-only behavior change; verify the baseline still passes.

---

## 7. Consolidated migration sequence (no implementation here)

1. **W1 (gate)** — make foot/hand derivation always run; author endpoints become opt-in override.
   This alone eliminates the *need* for W2/W3/W4. Highest leverage, highest risk → gate behind a
   flag and verify pose-by-pose against the validator baseline.
2. **W4 (counter-rotation)** — deleted as a side effect of W1; verify the engine's relative-
   rotation path with `ChestFrameIssueFTest` + symmetry tests.
3. **W2/W3 (endpoints)** — delete explicit `heel/toe/palm/knuckles/fingertips` writes; author via
   `buildAnkleArticulation`/`buildWristArticulation`. Remove magic `0.29/0.71` and `6/6/10` literals.
4. **W5 (pelvis)** — *after* the solver matures (P1/P2/P4 in `PELVIC_HIP_COMPLEX_INVESTIGATION.md`):
   poses declare contacts + coarse trunk intent; engine reconciles exact pelvis transform.
5. **W7 (frame API)** — optional `bakeIkLimb` overload dropping the explicit `parentRotation`
   argument; reduces engine-knowledge leakage. Low priority.
6. **W9/W10 (IK signal)** — engine stamps straight-intent drop + folds re-bake clamp into
   `maxIkClampAmount`; validator reads the stamp. Keeps the diagnostic-instrument contract intact.

---

## 8. Risk summary

| ID | Concern | Risk | Blast radius |
| --- | --- | --- | --- |
| W1 | completion gate flip | **High** | every pose's foot/hand |
| W4 | delete counter-rotations | **High** | every pose's foot/hand orientation |
| W2/W3 | delete endpoint writes | Med (agg High) | ~70 files |
| W5 | pelvis compensation → engine | **High** | posture of all standing/seated poses |
| W7 | frame API simplification | Low | authoring ergonomics |
| W9/W10 | IK intent/clamp signal | Med | validator contract |

**Cross-cutting:** the engine derivation with *identity* ankle/wrist articulation is designed to
equal the FK frame for neutral limbs, so neutral poses should be unchanged once W1 flips — but
this must be verified, not assumed. The existing test suite (`ValidatorRomClusterTest`,
`ChestFrameIssueFTest`, `*PoseTest` family, limb-symmetry assertions) is the guardrail.

---

## 9. Evidence index (file:line)

- `SkeletonPoseFinalizer.kt:166–169` — presence-gate disabling foot/hand derivation.
- `SkeletonPoseFinalizer.kt:374,380,386,392` — gated completion calls.
- `SkeletonPoseFinalizer.kt:442–513` — engine foot/hand derivation + relative rotation.
- `SkeletonFactory.kt:79–102,142–175` — factory always emits `HEEL_F/TOE_F/PALM_A/FINGERTIPS_A`.
- `BasePose.kt:167–179` — `buildWristArticulation`/`buildAnkleArticulation` (intent entry points).
- `BasePose.kt:274–360` — `bakeIkLimb` (frame conversion + clamp propagation + contact register).
- `SkeletonMath.kt:598–683` — `solveStraightLimb` incl. UNI-9 `dist < L1` bend (`:660`).
- `SkeletonMath.kt:691–702` — `toWorldDirection`/`toLocalDirection` (frame conversion, engine-owned).
- `ConstraintSolver.kt:46–119` — pelvis compensation (tilt + CCD posture pass); `:283–314` re-bake.
- `ExerciseValidator.kt:622–633,695,709–726` — `IK_TARGET_UNREACHABLE`, `STRAIGHT_LIMB_INTENT` (post-hoc, contact-scoped).
- `PikeSitPose.kt:72–76` — counter-rotation smoking gun.
- `BaseLungePose.kt:82–128` — `bakeLeg`/`bakeArm` `-parentRotation.angle` counter-rotation.
- `MiddleSplitPose.kt:53,81–100` — pelvis Y + explicit endpoints (diagnostic instrument, not retuned).
- `PELVIC_HIP_COMPLEX_INVESTIGATION.md` §P1/P2/P4 — solver pelvis-compensation limitations (prerequisite for W5).
