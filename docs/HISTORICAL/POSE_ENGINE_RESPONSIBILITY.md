# Pose ↔ Engine Responsibility Boundary — Architectural Investigation

> **Scope:** investigation only. No code, engine, validator, or solver was modified.
> **Trigger:** the Middle Split audit (`MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`) exposed that a
> pose author manually re-authors heel/toe/palm/fingertips and counter-rotates ankle/wrist to
> "undo" engine-driven inherited orientation. That is a responsibility smell: content is
> compensating for engine behavior. This document determines where the boundary *should* be.

---

## 0. TL;DR verdict

the MonkEngine runtime **already contains** the correct anatomy-solving machinery
(`adjustFootOrientation`, `adjustHandOrientation`, `buildAnkleArticulation`,
`buildWristArticulation`, the relative-rotation math). It is **dead for every IK-authored
pose** because a presence-gate in `SkeletonPoseFinalizer` (`cachedHasHeelToeF =
containsJoint(roots, HEEL_F) && containsJoint(TOE_F)`, line 166) is *always true* — the
skeleton factory unconditionally builds `HEEL_F/TOE_F/PALM_A/FINGERTIPS_A` nodes
(`SkeletonFactory.kt:79–102, 142–175`). So the finalizer skips completion and uses the
pose's hand-authored local positions, forcing ~70 production/validation poses to author
foot/hand endpoints and to negate inherited torso tilt by hand.

**The responsibility boundary has already drifted from the MonkEngine runtime to the content.** This is
the central finding. The fix direction (not implemented here) is: poses should author only
*intent* (ankle/wrist articulation, limb target, `straight` flag); the finalizer should
*always* derive heel/toe/palm/fingertips from that, treating author-written endpoints as an
optional override, not a bypass.

For the **straight-limb contract**, the current behavior ("`straight=true` silently bends")
is an engine limitation that the validator surfaces post-hoc (`STRAIGHT_LIMB_INTENT`). Under
the diagnostic-instrument rule that silent bend is *correct engine behavior* — but the
engine gives the author **no bake-time signal**, and `STRAIGHT_LIMB_INTENT` is contact-scoped
and reads only the baked result, so the dropped intent is invisible except to the validator.

---

## 1. Limb-orientation responsibility: explicit authoring vs. engine derivation

**Question:** should a pose author specify heel/toe/palm/knuckles/fingertips explicitly, or
should the MonkEngine runtime derive them from limb orientation, wrist/ankle local rotations, and
anatomical defaults?

### What the MonkEngine runtime is *already* designed to do

Two completion methods exist in `SkeletonPoseFinalizer`:

- `adjustFootOrientation(pose, knee, ankle, heel, toe, ankleRotation)` — derives the foot's
  forward from the **shank (knee→ankle)** direction and an optional ankle articulation, then
  calls `FootDefinition.computeHeelToe(ankle, forward, ankleRotation, heel, toe)`
  (`SkeletonPoseFinalizer.kt:474–513`). This is *exactly* the anatomy solve.
- `adjustHandOrientation(pose, elbow, hand, wrist, palm, knuckles, fingertips, wristRotation)`
  — derives the hand basis from the **forearm (elbow→hand)** direction and an optional wrist
  articulation, then `HandDefinition.computeHandJoints(...)`
  (`SkeletonPoseFinalizer.kt:442–472`).

Both are fed a **relative** rotation (`relativeRotation(ankle, knee, relAnkle)`,
`relativeRotation(hand, elbow, relWrist)`, lines 377/383/389/395) — i.e. they already
isolate the author's intended wrist/ankle articulation from the inherited parent (torso)
orientation. **This is the right design.** It means a pose author should only set
`ankle.localRotation`/`hand.localRotation` (via `buildAnkleArticulation`/
`buildWristArticulation`) and let the MonkEngine runtime lay the foot/hand flat against the limb.

### What actually happens (the bypass)

`finalize()` gates these calls behind presence checks:

```
cachedHasHeelToeF = containsJoint(roots, HEEL_F) && containsJoint(TOE_F)   // :166
...
if (!cachedHasHeelToeF) { adjustFootOrientation(...) }                      // :374
if (!cachedHasHandDetailA) { adjustHandOrientation(...) }                  // :386
```

Because `SkeletonFactory.createStandardSkeleton()` **always** attaches `HEEL_F/TOE_F` and
`PALM_A/FINGERTIPS_A` to every skeleton (`:79–102` and the two-segment `:142–175`), the gate
is **always true** for every pose. The completion is **never run**; the pose's hand-authored
`heel/toe/palm/fingertips.localPosition` are used verbatim.

### Verdict for §1

| Endpoint | Who should own it | Current reality | Missing in engine? |
| --- | --- | --- | --- |
| heel position | **engine** (derive from shank + ankle articulation) | author (hand-set local pos) | No — `adjustFootOrientation` exists but is bypassed |
| toe position | **engine** | author | No |
| palm position | **engine** (derive from forearm + wrist articulation) | author | No — `adjustHandOrientation` exists but is bypassed |
| knuckles | **engine** | author | No |
| fingertips | **engine** | author | No |
| ankle orientation | **author** (intent: dorsi/plantar + inversion) | author (but often a *counter-rotation*) | No |
| wrist orientation | **author** (intent: flex + deviation) | author (but often a *counter-rotation*) | No |

**Explicit authoring of heel/toe/palm/fingertips is NOT required and should not be the
default.** It is required *only* as an optional override for stylized cases (e.g. a pointed
toe, a curled grip) that the default derivation cannot express. the MonkEngine runtime already has the
derivation; it is just gated off by a factory always emitting the nodes.

The `6/6/10` and `0.29/0.71` literals are duplicated copies of `HandDefinition` /
`FootDefinition` ratios owned by the MonkEngine runtime — a fragility smell.

---

## 2. Straight-limb contract: is the current behavior the correct architecture?

**Current contract:**

```
straight = true
   ↓  target inside proximal bone (dist < L1)
   ↓  solver cannot satisfy
   ↓  solver SILENTLY bends the limb   (UNI-9 fallback / ConstraintSolver re-bake)
```

### Trace (verified)

1. Author's bake: `SkeletonMath.solveStraightLimb` clamps reach to `[minDist, maxDist]`
   (`:617`); when the clamped reach is `< L1` it takes the UNI-9 branch and returns a bent
   triangle limb via `solveTriangleJoint` (`:660–668`). Comment at `:651–659` confirms this
   is deliberate: "a straight limb is geometrically impossible … fall back to a valid bent
   limb … so both bone lengths are preserved".
2. Global re-bake: `ConstraintSolver.solve` recomputes `canBeStraight = spec.straight &&
   reachMag >= spec.length1 - 1e-3f` (`:300`); if false it calls `solveIK` (bent) instead of
   `solveStraightLimb` (`:307–313`). Same silent bend, "the reachable compromise" (`:283–285`).
3. **No bake-time signal.** Neither path raises an error/warning. The only trace is
   `IKResult.clampAmount` → `pose.maxIkClampAmount` at the *author* bake (BasePose.kt:324),
   but the **global ConstraintSolver re-bake does NOT propagate its clamp back into
   `maxIkClampAmount`**, so solver-induced bending is invisible to `IK_TARGET_UNREACHABLE`.
4. Detection is **post-hoc, validator-only**: `ExerciseValidator.validateStraightLimbIntent`
   iterates `pose.contacts`, reads `spec.straight`, and measures the resolved middle-joint
   interior angle; if `< STRAIGHT_LIMB_TOLERANCE_DEGREES (175°)` it emits a
   `STRAIGHT_LIMB_INTENT` issue (ERROR). The solver has no knowledge the intent was dropped.
5. **Scope gap:** `validateStraightLimbIntent` only inspects `pose.contacts`. A
   `straight=true` limb baked **without** a contact is never checked.

### Is this the correct contract?

**For the *silent bend itself* — yes, and it is consistent with the diagnostic-instrument
rule.** When geometry makes a straight limb impossible, the MonkEngine runtime must not crash or collapse
a bone to zero; returning a valid bent limb is the right fallback. Bending is the *reading*,
and `STRAIGHT_LIMB_INTENT` is the *meter*. That is exactly what Middle Split is for.

**But there are three genuine contract defects:**

- **D1 — No bake-time signal.** The author gets no feedback that their `straight=true` was
  dropped; only a later validator run (and only for contact-bearing limbs) reveals it. The
  engine should record the drop on the `ContactSpec` (e.g. `intentPreserved = false`) at bake
  time, so it is visible without a separate validator pass and independent of contact scope.
- **D2 — Re-bake clamp is lost.** The ConstraintSolver does not write its `clampAmount`
  back into `pose.maxIkClampAmount`, so `IK_TARGET_UNREACHABLE` can miss solver-induced
  bending. The author cannot tell the author-bake clamp from the solver-bake clamp.
- **D3 — Contact-scoped only.** A contact-less `straight=true` limb is never validated.
  Straight intent should be detectable for any baked `straight` limb, not only contact limbs.

**Correct contract (recommendation, no implementation):**

> the MonkEngine runtime guarantees a valid limb and *preserves bone lengths* either way. When
> `straight=true` cannot be honored geometrically, the MonkEngine runtime **bends** (correct) **and
> stamps the drop onto the limb's spec at bake time** (new: introspectable, not just
> validator-post-hoc). The validator's `STRAIGHT_LIMB_INTENT` remains the *meter* but reads
> the MonkEngine's own `intentPreserved` flag (plus a geometry check) rather than reverse-
> engineering it from joint angles, and is **not** limited to contacts. No exception is
> thrown — a validation pose is a diagnostic, and bending is a legitimate reading.

This preserves the diagnostic-instrument model (the instrument still reads the fault) while
fixing the asymmetry that the MonkEngine runtime hides the drop from the author.

---

## 3. Pose vs. Engine responsibility table (per anatomical feature)

`A` = pose author · `B` = engine. "Intent" = author declares what they want; engine solves
the kinematics. "Derive" = engine computes endpoints from intent + anatomy.

| Anatomical feature | Owner | Rationale / evidence |
| --- | --- | --- |
| **Wrist orientation** | A (intent: flex+deviation via `buildWristArticulation`) | `BasePose.kt:167`; engine derives hand from it |
| **Ankle orientation** | A (intent: dorsi/plantar + inversion via `buildAnkleArticulation`) | `BasePose.kt:177`; engine derives foot from it |
| **Foot direction (heel→toe)** | **B (derive)** from shank + ankle articulation | `adjustFootOrientation`/`computeHeelToe` exist; currently bypassed |
| **Palm direction** | **B (derive)** from forearm + wrist articulation | `adjustHandOrientation`/`computeHandJoints` exist; currently bypassed |
| **Toe direction** | **B (derive)** (part of foot direction) | same as foot |
| **Heel direction** | **B (derive)** (part of foot direction) | same as foot |
| **Elbow pointing** | **B (derive)** from IK pole + limb solve | author supplies *pole*, engine solves joint placement |
| **Knee pointing** | **B (derive)** from IK pole + limb solve | author supplies *pole*, engine solves joint placement |
| **Clavicle behavior** | A (intent: elev/prot/axial via `buildClavicularRotation`) | `SkeletonMath.kt:789`; real DOF, author-driven |
| **Scapular behavior** | A (intent: retract/depression via `buildScapularRotation`) | `SkeletonMath.kt:731`; real DOF, author-driven |
| **Pelvis compensation** | **B (derive)** — global solve should reconcile support | today author hand-sets pelvis; `ConstraintSolver` only tilts on wrong axis (P1) |
| **Chest compensation** | **B (derive)** — trunk should be a coherent pose, not opposed pelvis/chest rotations | `SkeletonPoseFinalizer.reconstructChestFrame` is a fallback only; author still hand-rotates both |
| **Hip flexion/abduction/rotation** | A (intent via `buildHipOrientation`) | `BasePose.kt:216`; correct as-is |
| **Limb root target (hand/foot/elbow/knee pos)** | A (intent: where the end should go) | IK input |
| **`straight` flag** | A (intent) | engine honors or drops + stamps |
| **Bone lengths** | **B (invariant)** | solver preserves; validator `BONE_LENGTH` checks |
| **Ground contact / penetration** | **B (derive)** | `resolveContactPlane`, `ContactConstraint.ground` |
| **L/R mirror symmetry** | A (intent: sideSign) | engine propagates via FK |

**The two cells that are wrong today** (author doing MonkEngine's job): **Foot direction** and
**Palm direction** — both should be `B (derive)` but are currently `A` because the
completion path is gated off.

---

## 4. Detected workarounds (current repo)

All findings below are verified by grep + read. They are **authoring compensations for
engine behavior** — the architectural smell this investigation targets.

### 4.1 Root cause — presence-gated bypass of engine completion
- `SkeletonPoseFinalizer.kt:166–169` — `cachedHasHeelToe* = containsJoint(roots, HEEL_*) &&
  containsJoint(roots, TOE_*)`; gated call at `:374` / `:386`.
- `SkeletonFactory.kt:79–102, 142–175` — factory **always** creates `HEEL_F/TOE_F/
  PALM_A/FINGERTIPS_A`, so the gate is always true → completion never runs.
- **Belongs in engine:** yes — the gate should treat author-written endpoints as an *override*
  and otherwise always derive. This single gate is the structural cause of ~all foot/hand
  workaround below.

### 4.2 Explicit heel/toe authoring (hand-set foot endpoints)
- Validation: `MiddleSplitPose.kt:81–82`, `DeepOverheadSquatPose.kt:69–70`,
  `DeadHangPose.kt:128–129`, `PikeSitPose.kt:77–78` — `heelX/t.getX.localPosition.set(
  ±def.foot.footLength * heelRatio/toeRatio, 0, 0)` style.
- Production, ratio style: `BaseBirdDogPose.kt:123–124`, `ThoracicExtensionPose.kt:72–73`,
  `ProneCobraStretchPose.kt:106–107`, `BasePushUpPose.kt:109–112/137–140` (manually rotates
  foot dir by `-shinPitch/theta`), `PikePushUpPose.kt:75–78`.
- Production, **magic 0.29/0.71 literals duplicating `def.foot.heelRatio/toeRatio`**:
  `ArmCirclesPose.kt:103–106`, `BurpeePose.kt:202–205`, `HipCarsPose.kt:115–118`,
  `KettlebellSwingPose.kt:88–89`, `LatStretchPose.kt:99–100`, `MountainClimberPose.kt:130–131`,
  `PelvicTiltPose.kt:113–116`, `ReverseSnowAngelPose.kt:86–87`, `ScapularRetractionPose.kt:103–106`,
  `WallSlidesPose.kt:103–106`, `FacePullPose.kt:103–106`, `GluteBridgePose.kt:117–120`.
- **Belongs in engine:** yes (derivation). Pose authoring is unnecessary + fragile (magic
  ratios can diverge from `FootDefinition`).

### 4.3 Explicit palm/fingertips/knuckles authoring (hand-set hand endpoints)
- Dominant **`6/6/10` open-hand literal** (>30 sites): `BaseBirdDogPose.kt:117–118`,
  `BaseThoracicPose.kt:150–151`, `ProneCobraStretchPose.kt:126–127`,
  `StaticForearmPlankPose.kt:155–156`, `BurpeePose.kt:220–221`, `HipCarsPose.kt:133–134`,
  `KettlebellSwingPose.kt:104–105`, `LatStretchPose.kt:116–117`, `WallSlidesPose.kt:128–129`,
  `GluteBridgePose`, `ReverseSnowAngelPose`, `FacePullPose`, `PelvicTiltPose`,
  `ScapularRetractionPose`, `MountainClimberPose.kt:96–97`, and validation
  `MiddleSplitPose.kt:98`, `DeepOverheadSquatPose.kt:83`, `DeadHangPose.kt:110`,
  `PikeSitPose.kt:100–101`.
- `BasePushUpPose.kt:185–188` — direction-derived hand offsets (still hand-authored, just via
  a unit vector).
- **Belongs in engine:** yes (derivation via `adjustHandOrientation`). `6/6/10` duplicates
  `HandDefinition` constants.

### 4.4 Inverse-tilt compensation (counter-rotating ankle/wrist to "undo" inherited torso tilt)
This is the clearest smell: authors negate the MonkEngine's FK-inherited parent orientation by
hand, duplicating `SkeletonPoseFinalizer.relativeRotation()` math.

- `BaseLungePose.kt:82–105` — `bakeLeg`: `val localAngle = -parentRotation.angle; ...
  ankle.localRotation.set(axisZ, localAngle)` + manual heel/toe. Comment: *"counter-rotated by
  the pelvis lean so the planted foot stays level"*. `bakeArm` mirrors it (`:111–128`,
  `hand.localRotation.set(axisZ, -parentRotation.angle)`).
- `StaticForearmPlankPose.kt:77–78` — `val invChestZ = -chestWorldZ; val invTorsoZ =
  -torsoPitch` (comments literally say "cancels chest frame" / "cancels pelvis frame"); applied
  at `:123–124,153–154`.
- `PikeSitPose.kt:72–76` — the smoking gun: *"the folded pelvis propagates down the leg to the
  ankle, so the foot inherits the trunk's forward tilt … Counter-rotating by +fold cancels the
  inherited fold, laying the foot flat."* → `ankleF!!.localRotation.set(axisZ, fold)`.
- `DeadHangPose.kt:124` — `ankleF.localRotation.set(axisZ, invTorsoZ)`.
- `IsometricSidePlankPose.kt:105–106,120–130` — `ankle/hand.localRotation.set(axisZ, invTorsoZ)`.
- `BaseBirdDogPose.kt:27,115–116,120–121` — `inverseTorsoPitch = -torsoPitch`; applied to
  hand/ankle.
- `ProneCobraStretchPose.kt:103–104,124–125`, `BaseThoracicPose.kt:148–149` — `-torsoPitch`/
  `-spinePitch` cancels.
- `HamstringStretchPose.kt:103–104` — `ankleF.localRotation.set(axisZ, torsoPitch - 1.57f)`
  (magic `≈π/2` to point foot at sky) and `torsoPitch` for the flat back foot.
- `PikePushUpPose.kt:84,90,123–127` — `pelvis.set(axisZ, torsoGlobalPitch - legPitch)`,
  `hipB.set(axisZ, legPitch - torsoGlobalPitch)`, `hand.set(axisZ, -torsoGlobalPitch)`.
- `BaseVerticalPullPose.kt:232–233,240–249` — `ankle.set(axisZ, invTorsoZ - plantarFlexion)`,
  `hand.set(axisZ, invChestZ + HALF_PI)`.
- Squat family — `BaseSquatPose.kt:86,97` (`leanAngle`), `JumpSquatPose.kt:91–92`
  (`leanAngle - footPitch`), `BaseHipFlexorPose.kt:136,145` (`leanAngle`).
- **Belongs in engine:** yes. `adjustFootOrientation`/`adjustHandOrientation` already take the
  *relative* rotation precisely so this negation is automatic. The author should set only the
  *net* ankle/wrist articulation; the MonkEngine runtime removes the inherited parent tilt.

### 4.5 Designed escape hatches unused by IK poses
- `BasePose.buildAnkleArticulation` / `buildWristArticulation` (`BasePose.kt:167/177`) exist
  as the intended "author the ankle/wrist, engine finishes the foot/hand" entry points.
- They are **effectively dead for IK poses** because the finalizer's presence-gate skips the
  completion that would consume them. Production poses overwhelmingly ignore them and instead
  hand-write `heelX/toeX/palmX/fingertipsX` + a counter-rotated `ankle/hand.localRotation`.

### 4.6 Straight-intent workarounds (the Middle Split class)
- Middle Split was **green-tuned** (spread `79.2 → 232`, pelvis `14 → 0`, hip rotation added)
  to make `straight=true` limbs resolve straight — this *is* a pose-side workaround for an
  engine limitation, and under the diagnostic-instrument rule it was correctly **reverted**
  (`MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`). The honest instrument now keeps the in-proximal targets
  and lets `STRAIGHT_LIMB_INTENT` read the drop.
- The `BrokenStraightLimbPose` fixture inside `ValidatorRomClusterTest` is complementary
  coverage for the same engine limitation.

---

## 5. Long-term architecture: does the current model scale?

**No.** The current model does not scale to hundreds of exercises (yoga, martial arts,
gymnastics, rehab, stretching).

### Why it fails at scale

1. **Every new pose re-implements orientation math.** A future author must know *why* the foot
   inherits the torso tilt and *how* to negate it (`invTorsoZ = -torsoPitch`, the `0.29/0.71`
   ratios, the `6/6/10` hand offsets, the `1.57f` magic π/2). That is engine-internal
   knowledge leaking into every content file. The investigation turned up **~40 heel/toe sites
   and ~30 palm/fingertips sites across ~70 files** doing exactly this.
2. **Fragility.** Magic literals (`0.29/0.71`, `6/6/10`) duplicate `FootDefinition` /
   `HandDefinition` constants. Change the definition's ratios and dozens of poses silently
   diverge from the MonkEngine runtime truth.
3. **Duplicated logic.** The inverse-tilt negation re-implements `relativeRotation()` by hand
   in ~15 places. Any refinement to the MonkEngine's relative-rotation convention must be copied
   into every pose.
4. **The straight-intent gap is invisible at author time.** With no bake-time signal and a
   contact-scoped, post-hoc validator, an author of a new stretch (often contact-less) can ship
   a silently-bent "straight" limb and never notice until a validator run.

### What the future-proof model looks like

A future pose author should describe **anatomical intent only**, never engine internals:

```
// intent, not engine-compensation:
buildHipOrientation(hipF, flexion = 1.2f, abduction = 0.9f, rotation = 0.8f, sideSign = -1)
buildAnkleArticulation(ankleF, dorsiflexion = 0f, inversion = 0f)   // flat foot, engine lays it
bakeIkLimb(hipF, target = footTarget, straight = true)              // engine honors or stamps drop
// NO heel/toe/palm/fingertips.localPosition writes
// NO invTorsoZ / -parentRotation.angle counter-rotations
```

the MonkEngine runtime then:
- derives heel/toe/palm/fingertips from limb orientation + wrist/ankle articulation
  (always, treating author endpoints as optional override);
- removes inherited parent tilt automatically via the relative-rotation path;
- stamps `straight` intent drop on the limb at bake time (introspectable, not
  contact-scoped);
- reconciles pelvis/chest as a coherent trunk (solver compensation, not author hand-tilt).

This keeps content declarative and engine-agnostic, scales to hundreds of exercises, and
preserves the diagnostic-instrument role (the validator still reads engine-stamped drops).

---

## 6. Recommended responsibility boundary (summary)

1. **Foot/palm/toe/heel/fingertips direction → ENGINE (derive).** Author endpoints become an
   *optional override*, not the default path. Fix the presence-gate in `SkeletonPoseFinalizer`
   so completion always runs unless the author explicitly overrides.
2. **Ankle/wrist orientation → AUTHOR (intent)** via `buildAnkleArticulation` /
   `buildWristArticulation`; engine derives the extremity and cancels inherited parent tilt.
3. **Elbow/knee pointing → ENGINE (derive)** from IK pole + limb solve.
4. **Pelvis/chest compensation → ENGINE (derive)** via a real posture solve, not author hand-
   rotation of opposed pelvis/chest. Today the solver only tilts on a wrong axis (P1 in
   `PELVIC_HIP_COMPLEX_INVESTIGATION.md`).
5. **Straight-limb contract → ENGINE bends (correct) + stamps the drop at bake time**;
   validator `STRAIGHT_LIMB_INTENT` reads the MonkEngine runtime stamp + geometry, not contact-scoped
   angle reverse-engineering. No exception thrown (diagnostic model preserved).
6. **Hip/clavicle/scapular → AUTHOR (intent)** via named DOF helpers (already correct).

### Concrete examples from this codebase (what "right" looks like)

- `PikeSitPose.kt:72–76` should become: `buildAnkleArticulation(ankleF, dorsiflexion = 0f,
  inversion = 0f)` and **delete** the `ankle.localRotation.set(axisZ, fold)` counter-rotation
  and the `heel/toe.localPosition` writes — the MonkEngine runtime lays the foot flat.
- `BaseLungePose.bakeLeg` (`:82–105`) should drop `localAngle = -parentRotation.angle` and the
  `heel/toe` writes; the relative-rotation completion does it.
- `MiddleSplitPose.kt:81–100` should drop the explicit `heel/toe/palm/knuckles/fingertips`
  local positions; the MonkEngine runtime derives them from the (bent, by design) limb.

---

## 7. Evidence index (file:line)

- `SkeletonPoseFinalizer.kt:166–169` — presence-gate that disables foot/hand completion.
- `SkeletonPoseFinalizer.kt:374, 386` — gated calls to `adjustFootOrientation`/`adjustHandOrientation`.
- `SkeletonPoseFinalizer.kt:442–513` — the (bypassed) engine derivation logic + relative rotation.
- `SkeletonFactory.kt:79–102, 142–175` — factory always emits `HEEL_F/TOE_F/PALM_A/FINGERTIPS_A`.
- `BasePose.kt:167–179` — `buildWristArticulation` / `buildAnkleArticulation` (unused by IK poses).
- `SkeletonMath.kt:598–683` — `solveStraightLimb` incl. UNI-9 `dist < L1` bent fallback (`:660`).
- `ConstraintSolver.kt:283–314` — `canBeStraight` re-bake switch (silent bend).
- `ExerciseValidator.kt:695, 709–726` — `STRAIGHT_LIMB_INTENT` post-hoc, contact-scoped meter.
- `PikeSitPose.kt:72–76`, `BaseLungePose.kt:82–105`, `StaticForearmPlankPose.kt:77–78` — counter-rotation smell.
- `MiddleSplitPose.kt:81–100` — explicit extremity authoring (reverted diagnostic instrument).
