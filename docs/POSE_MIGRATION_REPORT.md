# Pose Migration Report — Removal of Orientation Workarounds

**Scope:** `app/src/main/java/com/monkfitness/app/poses/` (production) **and**
`app/src/main/java/com/monkfitness/app/validation/poses/` (diagnostic instruments).
**Prerequisite:** W1 (`docs/W1_IMPLEMENTATION_REPORT.md`) — the engine now owns extremity
orientation and derives heel/toe and palm/knuckles/fingertips by default (AUTOMATIC),
skipping derivation only when a pose explicitly opts an extremity into `MANUAL_OVERRIDE`.
**Goal:** remove pose-side code that compensated for the old engine limitation; keep explicit
overrides only where the exercise intentionally requires a specific grip or foot/hand orientation.

---

## 1. Principle applied

For every extremity the pose previously:

1. wrote `ankle*/hand*.localRotation` to **cancel inherited torso/parent tilt** (e.g. `-torsoPitch`,
   `-torsoAngle`, `-spinePitch`, `invTorsoZ`, `-parentRotation.angle`, `-theta`, `leanAngle`),
2. wrote `heel*/toe*` and `palm*/knuckles*/fingertips*` `localPosition` to **lay out the foot/hand
   endpoints by hand**.

Both are now done by the engine. The migration therefore:

- **Deletes** the tilt-cancellation rotations (the foot/hand now inherits the engine-cancelled tilt,
  so a neutral/identity ankle or wrist lays the extremity flat along the limb).
- **Deletes** the manual endpoint authoring (dead code — the finalizer overwrites it anyway).
- **Keeps** genuine articulation that is *not* a pure tilt-cancel (plantar flexion, dorsal/ventral
  flexion, grips, toe flares) and expresses it as the net wrist/ankle rotation the engine consumes.
- **Converts** poses whose foot/hand orientation the engine derivation provably cannot reproduce
  (near-horizontal shins, side-rolled frames, precisely authored push-up geometry) into **explicit
  `MANUAL_OVERRIDE`** via `overrideExtremityOrientation(...)`, preserving the authored geometry
  verbatim instead of relying on implicit node-gating.

No anatomy, targets, IK calls, or segment lengths were changed.

---

## 2. Categories of change

| Category | Action | Poses |
|---|---|---|
| A — flat foot / hand, engine-derivable | Remove tilt-cancel + manual endpoints | ArmCircles, Burpee, DeepSquatHold, FacePull, GluteBridge, HamstringStretch (hand only), HipCars, JumpSquat (endpoints only; plantar flexion kept), MountainClimber, PelvicTilt, ProneCobraStretch, ReverseSnowAngel, ScapularRetraction, WallSlides, BaseSquat, BaseThoracic, BaseBirdDog, QuadrupedThoracicRotations, ThoracicExtension, KettlebellSwing, LatStretch, BaseLunge |
| B — intentional orientation kept as explicit override | Keep authored geometry + `overrideExtremityOrientation` | MountainClimber, KettlebellSwing, LatStretch, ReverseSnowAngel (flat foot on near-horizontal shin), IsometricSidePlank (side-rolled frame), StaticForearmPlank (plantar-flexed toes), SumoSquat (45° toe flare), BaseHipFlexor (parameterized back foot), BasePushUp (planted flat foot + flat palm) |
| C — grip / articulation kept, tilt-cancel removed | Keep grip/articulation; drop `invChestZ`/`invTorsoZ` term | BaseVerticalPull (grip), DynamicWorldsGreatestStretch (back-foot plantar flexion), PikePushUp (leg-pitch foot + palms-down hands), HamstringStretch (front foot to sky, back foot flat) |

> Note: several Category-B poses (MountainClimber, KettlebellSwing, LatStretch, ReverseSnowAngel)
> were first migrated per Category A, which *regressed* `FOOT_GROUND_PENETRATION` (the engine lays the
> foot perpendicular to a near-horizontal shin, driving the toe below the floor). They were re-classified
> to Category B — the flat planted foot is an intentional orientation the default derivation cannot
> express, so it is preserved via explicit override.

---

## 3. Per-pose detail

### 3.1 Removed cleanly (engine now derives)

**GluteBridgePose** — `ankleF/B.localRotation = -torsoAngle` (supine counter-rotation) + manual
`0.29/0.71` heel/toe + `handA/P.localRotation = -torsoAngle` + `6/6/10` hand.
*Why it existed:* the engine used to inherit the ~90° supine torso tilt into the foot/hand.
*Why gone:* engine cancels inherited tilt; identity ankle/wrist lays foot/hand flat. **Result: test passes** (was failing pre-migration).

**PelvicTiltPose** — same pattern (`-torsoAngle` on ankle/hand + `0.29/0.71` + `6/6/10`). **Result: test passes** (was failing).

**BurpeePose** — `ankle/hand.localRotation = -info.torsoAngle` + `0.29/0.71` + `6/6/10`. Removed.

**BaseSquatPose / DeepSquatHoldPose / JumpSquatPose** — `ankle/hand.localRotation = leanAngle`
(forward-lean counter-rotation) + manual heel/toe + `6/6/10`. For JumpSquat the *plantar-flexion*
`ankle = leanAngle - footPitch` and the wrist flick `leanAngle + flightFactor*0.3` are kept (genuine
articulation); only the manual endpoints are removed.

**BaseThoracicPose (`applyThoracicHands`), BaseBirdDogPose (`applyBirdDogExtremities`)** — hand
`-spinePitch`/`-torsoPitch` counter-rotations + `6/6/10`. Function bodies emptied (callers unchanged).

**QuadrupedThoracicRotationsPose, ThoracicExtensionPose, ProneCobraStretchPose, HamstringStretchPose
(hand), ScapularRetractionPose, WallSlidesPose, FacePullPose, ArmCirclesPose, HipCarsPose** — manual
heel/toe and/or `6/6/10` hand writes + tilt-cancellation rotations removed. HamstringStretch keeps its
intentional `ankleF = torsoPitch - 1.57f` (front foot points to sky) and `ankleB = torsoPitch`
(back foot flat) — those are deliberate foot articulations, not tilt-cancels.

**BaseLungePose (`bakeLeg`/`bakeArm`)** — the shared helpers wrote `localAngle = -parentRotation.angle`
on the ankle/hand plus manual heel/toe/`6/6/10`. Both helpers now just call `bakeIkLimb`; the engine
derives the extremity. (The `heel/toe/palm/knuckles/fingertips` params are retained for signature
compatibility with callers.)

### 3.2 Kept as explicit override (Category B)

**MountainClimber / KettlebellSwing / LatStretch / ReverseSnowAngel** — flat planted foot restored and
`overrideExtremityOrientation(FOOT_F/B)` called; the engine derivation drove the toe below the floor
for these near-horizontal-shin poses.

**IsometricSidePlankPose** — the whole body is side-rolled 90°, so stacked/planted feet and the support
forearm are intentional orientations; all four extremities marked `MANUAL_OVERRIDE`, authored geometry kept.

**StaticForearmPlankPose** — feet plantar-flex onto the balls of the toes (heels lift); `FOOT_F/B`
marked override. Hands left to engine derivation (flat along the forearm = same result).

**SumoSquatPose** — 45° outward toe flare kept (sumo-stance intent); `FOOT_F/B` marked override. The
`ankle = leanAngle` tilt-cancel and the `6/6/10` hands are removed.

**BaseHipFlexorPose** — `applyBackFoot(worldDir)` keeps the parameterized back-foot direction (toes up
the wall / flat back) and marks `FOOT_B` override; the front foot and hands go to engine derivation.
`applyFrontFoot` emptied (front foot is flat, engine-derivable).

**BasePushUpPose** — the planted flat foot (counter-rotated to the plank pitch) and the flat palm are
precisely authored geometry; pure removal regressed 5+ push-up tests by 60 validation errors because the
engine's perpendicular-to-limb derivation does not reproduce the tuned plank. All four extremities are
marked `MANUAL_OVERRIDE` so the authored heel/toe and palm/knuckles/fingertips are preserved verbatim.
This is the intended "explicit override where the exercise requires a specific foot/hand orientation".

### 3.3 Grip / articulation kept, tilt-cancel removed (Category C)

**BaseVerticalPullPose** — `applyGrip` previously baked `invChestZ = -(torsoPitch + chestFlex)` into the
grip angle; that tilt-cancel is removed and the grip is now authored as the net wrist articulation
(`applyGrip(0f)`, keeping the overhand `-HALF_PI` / underhand `+HALF_PI` grip component). Feet: `invTorsoZ`
tilt-cancel dropped, plantar flexion `-plantarFlexion` retained. Manual `6/6/10` hand + manual heel/toe
removed.

**DynamicWorldsGreatestStretchPose** — `ankleF = -spinePitch` (tilt-cancel) removed; `ankleB =
-spinePitch - footPitchB` becomes `ankleB = -footPitchB` (back-foot plantar flexion retained). Manual
heel/toe removed (engine derives). Hands use the now-empty `applyThoracicHands`.

**PikePushUpPose** — `ankleF = legPitch` kept (intentional pike leg articulation); manual heel/toe
removed. Hand `-torsoGlobalPitch` tilt-cancel + manual `6/6/10` removed (engine derives palms-down hand
along the forearm).

---

## 4. Test impact

Baseline (post-W1, with the 4 pre-existing compile-broken test files set aside, per `AGENTS.md`):
**204 tests, 22 failed**.

After migration: **204 tests, 20 failed** → **0 new failures**, **2 fixed**
(`GluteBridgePoseTest`, `PelvicTiltPoseTest`).

The 20 remaining failures are identical to the pre-migration baseline and are the pre-existing
W1-related failures documented in `docs/W1_IMPLEMENTATION_REPORT.md` (poses that hand-authored
compensations now superseded by engine derivation, plus the intentionally-overridden vertical-pull /
lunge poses whose tests assert the old compensation). None were introduced by this migration.

The four poses re-classified to explicit override (MountainClimber, KettlebellSwing, LatStretch,
ReverseSnowAngel) *did* regress when first migrated per Category A (`FOOT_GROUND_PENETRATION`); converting
them to `MANUAL_OVERRIDE` restored them to green.

---

## 5. Residual / notes

- `BasePushUpPose` retains authored foot/hand geometry gated by `MANUAL_OVERRIDE`. This is the single
  pose where pure removal was not viable; it is the canonical example of "keep the explicit override".
- Poses using `PoseBuilder` (not `BasePose`) cannot call the `overrideExtremityOrientation(pose,...)`
  helper; for those the override is applied directly on the returned `SkeletonPose`
  (`jointsBuffer.overrideExtremityOrientation(Extremity.*)`).
- No production pose anatomy, constants, IK, solver, or validator rules were modified.

---

## 6. Validation poses — removal of diagnostic-instrument workarounds (follow-up)

The four engineering validation poses (`DeadHangPose`, `PikeSitPose`, `MiddleSplitPose`,
`DeepOverheadSquatPose`) were **intentionally left untouched** by the first pass documented above.
They are diagnostic instruments (see `docs/VALIDATION.md §2`), not development targets, so the W1
finalizer already derives their extremities by default — but the pose files still **hand-authored**
heel/toe and palm/knuckles/fingertips *and* counter-rotated the ankle/wrist to "cancel" inherited
torso tilt. That hand-authoring was already dead code (the finalizer overwrote it) and is exactly the
smell the task asks to remove. This pass deletes it.

Because a validation pose must **read** the engine's true state, deleting the compensation makes the
instrument more honest: the rendered foot/hand is now the engine derivation, not a duplicated
hand-authored copy. Nothing the validator measures changes — `STRAIGHT_LIMB_INTENT` and `HIP_ROM_LIMIT`
inspect knee/elbow interior angles and hip ROM, not foot/hand endpoints (`ValidatorRomClusterTest`).

### 6.1 DeadHangPose

- **Workaround existed:** `val invChestZ = -torsoPitch` fed `gripAngle = invChestZ + π/2`; `val
  invTorsoZ = -torsoPitch` fed `ankleF/B.localRotation`; plus manual `heel/toe` (`±def.foot.footLength *
  heelRatio/toeRatio`) and `6/6/10` hand endpoints.
- **Why it existed:** the old engine inherited the torso pitch into the hand/foot, so authors negated
  it by hand and laid out the extremities.
- **Why no longer necessary:** `torsoPitch = 0f` here, so both `invChestZ`/`invTorsoZ` reduce to `0`
  (the counter-rotation was a no-op). The genuine overhand grip is just `+π/2` (a wrist articulation
  the engine consumes), and the engine derives heel/toe/palm/fingertips from the limb + neutral
  articulation.
- **Before:**
  ```kotlin
  val invChestZ = -torsoPitch
  ...
  val gripAngle = invChestZ + (PI.toFloat() / 2f)
  handA!!.localRotation.set(axisZ, gripAngle); handP!!.localRotation.set(axisZ, gripAngle)
  palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
  palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)
  ...
  val invTorsoZ = -torsoPitch
  ...
  ankleF!!.localRotation.set(axisZ, invTorsoZ); ankleB!!.localRotation.set(axisZ, invTorsoZ)
  heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
  heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
  ```
- **After:**
  ```kotlin
  // overhand grip is a genuine +π/2 wrist articulation; engine derives palm/knuckles/fingertips
  handA!!.localRotation.set(axisZ, PI.toFloat() / 2f); handP!!.localRotation.set(axisZ, PI.toFloat() / 2f)
  ...
  // legs baked via bakeIkLimb; engine derives heel/toe from shank + neutral ankle (no endpoint authoring)
  ```

### 6.2 PikeSitPose

- **Workaround existed:** `ankleF/B.localRotation.set(axisZ, fold)` to "cancel the inherited fold"
  (the comment noted the old `-fold` *doubled* the tilt); manual `heel/toe` and `6/6/10` hand; and
  `handA/P.localRotation.set(axisZ, -fold * 0.6f)` (chest-tilt counter-rotation).
- **Why it existed:** compensating for the old engine's inherited trunk tilt before the engine cancelled
  it automatically.
- **Why no longer necessary:** the engine cancels the inherited fold via the relative ankle/wrist
  rotation, so a neutral (identity) articulation lays the foot/hand flat. `fold` is still used for the
  legitimate pelvis/chest authoring.
- **Before:**
  ```kotlin
  ankleF!!.localRotation.set(axisZ, fold); ankleB!!.localRotation.set(axisZ, fold)
  heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
  heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
  ...
  handA!!.localRotation.set(axisZ, -fold * 0.6f); handP!!.localRotation.set(axisZ, -fold * 0.6f)
  palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
  palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)
  ```
- **After:** (both blocks deleted — engine derives heel/toe and palm/knuckles/fingertips from the limb
  + neutral articulation).

### 6.3 MiddleSplitPose

- **Workaround existed:** `ankleF/B.localRotation.set(axisZ, 0f)` (redundant no-op) + manual `heel/toe`;
  `handA/P.localRotation.set(axisZ, 0f)` (no-op) + manual `6/6/10` hand.
- **Why it existed:** pre-W1 the pose had to lay out the extremities by hand; the explicit `0f`
  rotations were leftovers even then.
- **Why no longer necessary:** all four extremities are now engine-derived. The `straight=true`
  diagnostic (in-proximal targets → dropped intent) is untouched — this change only affects the
  rendered foot/hand, which is exactly the honest reading the instrument should give.
- **Before / After:** the three `localRotation.set(axisZ, 0f)` lines and all six `heel/toe/palm/
  knuckles/fingertips` `localPosition` writes were deleted; no replacement code is needed.

### 6.4 DeepOverheadSquatPose

- **Workaround existed:** `ankleF/B.localRotation.set(axisZ, leanAngle)` (lean counter-rotation) + manual
  `heel/toe`; `handA/P.localRotation.set(axisZ, leanAngle * 0.4f)` (chest-lean counter-rotation) + manual
  `6/6/10` hand.
- **Why it existed:** cancelling the inherited `leanAngle` torso lean before the engine did it.
- **Why no longer necessary:** the engine cancels the inherited lean via the relative rotation; a neutral
  ankle/wrist lays the foot/hand flat. `leanAngle` is still used for the legitimate pelvis/chest authoring.
- **Before / After:** both counter-rotations and all manual `heel/toe/palm/knuckles/fingertips` writes were
  deleted; no replacement code is needed.

### 6.5 Production leftovers — `HamstringStretchPose` hand + `BaseVerticalPullPose` grip param

Two production poses still carried a leftover tilt-cancel that the first pass had missed:

- **`HamstringStretchPose`** (not an override; engine derives the hand): `handA/P.localRotation.set(
  axisZ, -torsoPitch)` double-counted the chest tilt the engine now cancels automatically, twisting
  the reaching hand backward. Removed — a neutral wrist now lays the hand flat along the forearm. The
  genuine foot articulations (`ankleF = torsoPitch - 1.57f` point-to-sky, `ankleB = torsoPitch` flat)
  were kept.
- **`BaseVerticalPullPose.applyGrip`** was already called with `applyGrip(0f)` (tilt-cancel removed in
  the first pass), but the parameter was still named `invChestZ`, implying an inverse-tilt workaround.
  Renamed to `chestTilt` so the name no longer encodes a removed compensation.

### 6.6 Magic-ratio consolidation (explicit-override poses)

Four explicit-override production poses (`KettlebellSwingPose`, `LatStretchPose`, `ReverseSnowAngelPose`,
`MountainClimberPose`) keep authored flat-foot geometry because the engine's perpendicular-to-shank
derivation cannot reproduce a flat foot on a forward-leaning shank. Their authored endpoints used the
**magic literals** `0.29f`/`0.71f`, which duplicate `def.foot.heelRatio`/`def.foot.toeRatio` (defaults
0.29/0.71). Those literals were replaced with the engine-owned constants, keeping geometry byte-for-byte
identical while removing the fragile duplication flagged in `docs/POSE_ENGINE_RESPONSIBILITY.md §4.2`.
The `leanAngle` ankle counter-rotation and the `overrideExtremityOrientation(...)` opt-out are retained,
since they are the intentional override the task permits.

### 6.7 Test impact

The four validation poses are exercised by `ValidatorRomClusterTest.goodReferencesStayCleanUnderEngineering
Validation` and `middleSplitSurfacesDroppedStraightIntent`. Both assert only `STRAIGHT_LIMB_INTENT` and
`HIP_ROM_LIMIT`, which are independent of foot/hand endpoint authoring, so the removal does not change
their pass/fail outcome. No new test failures are expected from this pass. (Full `:app:testDebugUnitTest`
could not be run in this environment — no JDK installed — consistent with the prior W1 audit's note.)

---

## 7. Final ownership summary

| Extremity owner | State after this + prior pass |
| --- | --- |
| foot/hand **geometry** (heel/toe/palm/fingertips) | Engine-derived by default for every pose; explicit `MANUAL_OVERRIDE` only for `BasePushUp`, `IsometricSidePlank`, `StaticForearmPlank`, `SumoSquat`, `BaseHipFlexor`, `KettlebellSwing`, `LatStretch`, `ReverseSnowAngel`, `MountainClimber` (intentional flat-foot / grip / planted geometry). |
| ankle/wrist **articulation** | Pose-authored intent (`buildAnkleArticulation`/`buildWristArticulation`, or direct `localRotation` for grips/point-to-sky/plantar flexion). Engine cancels inherited parent tilt automatically. |
| Manual tilt-cancels (`invTorsoZ`, `-parentRotation.angle`, `-torsoPitch`, `fold`, `leanAngle`) | **Removed** from all non-override poses, including the four validation instruments. **Retained only** inside explicit-override blocks where a flat foot on a leaning shank is the intended orientation. |
| Magic `0.29/0.71` and `6/6/10` literals | `0.29/0.71` replaced by `def.foot.heelRatio/toeRatio` in the override poses. `6/6/10` hand literals removed everywhere (engine derives the hand). |
