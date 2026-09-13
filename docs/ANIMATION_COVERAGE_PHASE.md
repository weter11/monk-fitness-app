# Animation Coverage Phase — 49/66 → 66/66

**Status:** ACTIVE — **`61/66`** after batch 3 (batch 1 landed `53/66`, batch 2 `57/66`). Brings every catalog
exercise from an animated *illustration* to a real skeletal animation driven by the engine.

**Metric (kept honest):** `animated` = **a real skeletal animation exists AND `ExerciseHero` uses it**
— i.e. `Exercise.skeletonAnimation != null` (the hero takes the animated branch) **and**
`PoseRegistry.getPoseConfig(exercise.animationId) != null` (the branch then draws the
`SkeletonFactory`/`SkeletonPipeline` skeleton instead of the legacy keyframe illustration). The
distinction matters: all 66 exercises always had the illustration, so
`ExerciseAnimationProfileTest.hasAnimatedVariant()` stayed green while 17 exercises never touched the
engine. The metric is now asserted by `AnimationCoverageTest`.

## 1. Audit (measured, not assumed)

Census on `origin/main` @ `4a32d84` (fresh `:app:testDebugUnitTest` in a pristine worktree:
**133 classes / 670 tests / 0F / 0E**), reported `49/66`:

| # | exercise id | animationId | catalog family | pre-phase representation |
|---|---|---|---|---|
| 1 | `rows` | `row_standard` | `rows` | illustration only |
| 2 | `band_pull_aparts` | `band_pull_aparts_standard` | `face_pull` | illustration only |
| 3 | `y_t_raises` | `yt_raises_standard` | `face_pull` | illustration only |
| 4 | `shoulder_cars` | `shoulder_cars_standard` | `shoulder_mobility` | illustration only |
| 5 | `ninety_ninety_hips` | `ninety_ninety_hips` | `hip_mobility` | illustration only |
| 6 | `piriformis_stretch` | `piriformis_stretch_hold` | `hip_mobility` | illustration only |
| 7 | `ankle_mobility` | `ankle_mobility_standard` | `ankle_mobility` | illustration only |
| 8 | `calf_stretch` | `calf_stretch_hold` | `ankle_mobility` | illustration only |
| 9 | `dips` | `dip_parallel_bar` | `dips` | illustration only |
| 10 | `wall_sit` | `wall_sit_hold` | `wall_sit` | illustration only |
| 11 | `chin_tucks` | `chin_tuck_standard` | `neck_mobility` | illustration only |
| 12 | `neck_circles` | `neck_circles_hold` | `neck_mobility` | illustration only |
| 13 | `jumping_jacks` | `jumping_jack_standard` | `jumping_jacks` | illustration only |
| 14 | `horse_stance` | `horse_stance_hold` | `horse_stance` | illustration only |
| 15 | `child_pose` | `child_pose_hold` | `child_pose` | illustration only |
| 16 | `hip_circles` | `hip_circles_hold` | `hip_mobility` | illustration only |
| 17 | `leg_swings` | `leg_swings_hold` | `hip_mobility` | illustration only |

The 17 are exactly the catalog's `animationId`s that are absent from both `PoseRegistry` and
`AnimationRegistry`; the other 49 ids resolve to engine poses and are **not** revisited by this phase
(the canonical `SkeletonFactory` pose migration is a separate, completed body of work).

## 2. Specification state (recorded before inventing behaviour)

* **No BPS exists for any of the 17.** `docs/Biomechanical Pose Specification (BPS)/` carries 53 files
  covering the 53 exercises of the pre-existing set; every one of the 17 was authored without one.
  The only in-repo specification is the exercise's own copy: `name/desc/tech/steps/mistakes`
  (`app/src/main/res/values*/strings.xml`), which each pose below quotes and each test asserts.
* **Four exercises have no copy at all** beyond the name — `neck_circles`, `jumping_jacks`,
  `hip_circles`, `leg_swings` pass `R.string.ex_<id>` as *every* field (name/description/technique/
  steps), so their catalog entry carries no description, technique, phase list or mistakes. Their
  motion below is authored from the exercise's convention and the sibling families, and is flagged as
  authored-by-catalogue-name, not specification.
* **Two catalog entries contradict themselves and are flagged, not silently resolved:**
  `rows` is titled "Inverted Bodyweight Row" (equipment `BAR`, `imageRes = pull_up`) while
  `ex_rows_steps` describes a *bent-over weight row* ("Hinge at the hips… Pull the weight toward your
  lower ribs"); the phase authors the **inverted row** the id/title/equipment declare and records the
  copy as stale.
* **Toe-out is not expressible as an ankle articulation.** `buildAnkleArticulation` composes
  dorsiflexion (Z) + inversion (X) only, and the foot's long axis has no yaw DOF; the sanctioned
  channel for a foot heading is the exercise-intent channel (`setHeading` on the extremity, resolved
  by `SkeletonPoseFinalizer.adjustFootOrientation` against the foot's declared support). Poses that
  specify a foot heading declare it there.
* **Wall contacts stay undeclared** (`WallSlidesPose` / `LatStretchPose` precedent): a declared contact
  whose centroid falls inside a `WallProp` footprint is re-oriented onto the wall's face (the M15
  residual), so wall relationships are authored geometry with a measured standoff. The wall's contact
  plane is the face **toward the athlete** — `center.x + width/2` when the wall is behind the body
  (M15's convention) and `center.x - width/2` when it is in front.

## 3. Batches

Each pose: the canonical `SkeletonFactory` tree through `BaseSquatPose`/`BasePose`, the family's
declared-pelvis-tilt + registered-package-bake limb convention, declared support on the one channel
(`metadata.support`), published-frame tests (`PoseFrameSweep`, which enters the pipeline exactly as
`SkeletonRenderer` does), and a RED run against the icon-equivalent authoring before the implementation.

### Batch 1 — standing lower-body: foot-planted holds and drills (4 exercises)

| exercise | pose class | canonical family | tests | authored cycle | measured |
|---|---|---|---|---|---|
| `horse_stance` | `HorseStancePose` | `BaseSquatPose` | `HorseStancePoseTest` (11) | sink from a tall stance into the hold and back | pelvis `200 → 150` (50 u), knee interior `125.2° → 95.6°`, stance `92` u (double shoulder width), toe-out `15°`, ankle/heel/toe travel `0.00`, clamp `0` |
| `wall_sit` | `WallSitPose` | `BaseSquatPose` | `WallSitPoseTest` (12) | slide down the wall plane into the 90° seat and back | pelvis travels `72` u **at a pinned X** (`−42`, drift `≤ 1.5`), knee interior `152.2° → 90.3°`, shin `1.3°` off vertical at the hold, feet travel `0.00`, clamp `0` |
| `ankle_mobility` | `AnkleMobilityPose` | `BaseSquatPose` | `AnkleMobilityPoseTest` (10) | drive the front knee toward the wall, ease back | split `125` u, pelvis forward `10` u, front knee forward `18.9` u (`64.9 → 83.8`), back leg `152.1°` interior, both ankles travel `0.00`, clamp `0` |
| `calf_stretch` | `CalfStretchPose` | `BaseSquatPose` | `CalfStretchPoseTest` (9) | shift forward into the stretch, hands on the wall | body forward `10` u, front knee `123.1° → 109.3°`, back leg `147.3° → 149.8°`, hands fixed on the wall plane (`x = 62`, travel `0.00`), back ankle/heel travel `0.00`, clamp `0` |

RED-before evidence (same test files, same harness, naive icon-equivalent authoring restored — the
`BaseSquatPose` family clone each exercise looked like before): `HorseStancePoseTest` 6/11 RED
(`stance width 65.53 u`, `ANKLE_F slides 10.85 u`, `clamp 30.93`, `foot yaw 0.1°`, `trunk 25.1° off
vertical`, `knee interior 30.0°`), `WallSitPoseTest` 6/12, `AnkleMobilityPoseTest` 6/10,
`CalfStretchPoseTest` 6/9 — every message quoting the measured number.

**Flagged for the user (not resolved here):** these four exercises' authored cameras
(`CameraDefinition` the family default) are in the hero canvas's *authored-frame clip* inventory
(`ViewportFramingInvariantTest.CLIPPED_AT_HERO`), like 26 of the 49 pre-existing poses. The pin is
re-measured and recorded; no framing/camera behaviour is changed by this phase.

### Batch 2 — the upper-body pull / bar-support family (4 exercises)

The batch's two bar-supported members share a new family base, **`BaseBarSupportPose`**: the bar contract (a
**FIXED** grip the body is realized *against*, the flat-in-the-bar's-plane grip derivation, the scapular
girdle drive on the one canonical channel, IK baking through `bakeIkLimb`, and the shared finalization).
Each member discloses its own bar layout, reach schedule, body placement and second contact, because those
are exercise biomechanics and not engine knowledge — the same split `BaseVerticalPullPose` draws.

| exercise | pose class | canonical family | tests | authored cycle | measured |
|---|---|---|---|---|---|
| `rows` | `RowsPose` | `BaseBarSupportPose` (canonical `SkeletonFactory` tree) | `RowsPoseTest` (13) | the body rises from the arms' near-full extension to the elbows-folded top of an inverted row, the heels planted throughout | reach `138 → 76` authored (realized `138.000 → 73.548`), incline `22.4° → 36.4°` (`BOTTOM_INCLINE`/`TOP_INCLINE`, both asserted), hand chain `22 u` lying `0.0000 u` off the bar's plane at all five phases, floor clamp `0.0000` |
| `dips` | `DipsPose` | `BaseBarSupportPose` | `DipsPoseTest` (16) | lockout → the copy's own 90°-elbow bottom → lockout, on two parallel bars | lockout `138.000` → the 90° reach `103.711` (`sqrt(upperArm² + forearm²)` derived from the definition, so `mistakes`' "below 90 degrees" is unreachable by construction), descent `34.289 u`, two bars at `±50.6` (one per hand, its own anchor), trunk lean `10°`, feet clear the floor (`64.821 u` at the lockout), clamp `0.0000` |
| `band_pull_aparts` | `BandPullApartPose` | `posture` (standing; `FacePullPose`'s sibling) | `BandPullApartPoseTest` (11) | a straight-armed sweep from in front of the shoulders out to the sides, the blades retracting as the hands widen | hand radius `137.600 u` constant (arm interior `140.75°`), sweep `15° → 72°` (`START_SWEEP`/`END_SWEEP`), shoulder retracts posteriorly `0 → −12.709 u` with no shrug, feet travel `0.00`, reachability clamp `0.047` (the validator's own flag is `0.1`) |
| `y_t_raises` | `YTRaisesPose` | `posture` (prone; `ReverseSnowAngelPose`'s sibling) | `YTRaisesPoseTest` (12) | two raises per cycle — the Y (hands overhead), then the T (out at the sides) — with a controlled lowering between them | prone body on the mat (`PRONE_MAT_Y = 10`), Y peak hand `(243.29, 30.55, −103.54)` vs T peak `(120.00, 30.61, −182.05)`, hand travel `260.89 u` in X and `136.05 u` in Z, `5` distinct published frames from the `9`-point sweep (the symmetric rep: `p = ⅛ ≡ ⅜`, `⅝ ≡ ⅞`, and the rest seam `0 ≡ ½ ≡ 1`), clamp `0.0000` |

RED-before evidence (the same test files and harness, with the pre-phase shape each exercise's *own*
illustration grouping implies restored on this exercise's declarations — `rows` → `HangPose`, `dips` →
`PikePushUpPose`, `band_pull_aparts` → `FacePullPose`, `y_t_raises` → `ReverseSnowAngelPose`): **28 of the
52 tests RED** — `RowsPoseTest` 9/13, `DipsPoseTest` 10/16, `BandPullApartPoseTest` 3/11,
`YTRaisesPoseTest` 6/12 — every message quoting the number it measured, e.g. `HAND_A is 215.000 u off the
bar's top plane at p=0.000`, `the hip is 7.98 u off the heel→chest line at p=0.000 — the body must be one
line`, `the bottom of the rep must BE the copy's 90-degree elbow (measured 38.87 deg)`, `the arm is at 0.38
of the chain's 143.08 extension at p=0.000`, `the Y raise must take the hands overhead (hand X 146.66 vs
head 170.52)`, `CHEST left the mat's plane at p=0.000 expected:<10.0> but was:<18.49>`.

**Flagged for the user (not resolved here):** three of the four exercises' authored cameras
(`BandPullApartPose`, `DipsPose`, `RowsPose` — the standing and bar-supported families' own
`CameraDefinition`s) are in the hero canvas's *authored-frame clip* inventory
(`ViewportFramingInvariantTest.CLIPPED_AT_HERO`, `30 → 33`); the fourth, the prone `YTRaisesPose`, fits the
hero canvas as authored. The pin is re-measured and recorded; **no camera or framing behaviour is changed
by this batch**.


### Batch 3 — the hip-mobility / hip-rotation family (4 exercises)

The four exercises this batch converts are the catalog's hip work, and **three of them share a property no
earlier batch met: there is no exercise copy at all.** `hip_circles` and `leg_swings` pass
`R.string.ex_<id>` as *every* field, so their catalog entry is the title and nothing else; the two that do
carry copy (`ninety_ninety_hips`, `piriformis_stretch`) carry a full `steps`/`tech`/`mistakes` set that each
pose quotes line by line. For the two title-only exercises the identity is therefore **derived from two
in-repo channels and recorded as an authored decision** (§2's rule, applied twice):

* `hip_circles` — `values-ru` `ex_hip_circles` = *"Круговые движения тазом"* ("circular movements of the
  **pelvis**") and `values-uk` = *"Обертання тазом"* — two independent localizations agree that the subject of
  the circling is the pelvis, and the catalog's *other* hip-circle drill (`hip_cars_standard`, whose copy says
  *"A slow hip circle … **not a swing**"*) already owns the leg-circle. So `HipCirclesPose` authors the
  **standing pelvic circle**, not a second leg circle.
* `leg_swings` — `values-ru` *"Динамические махи ногами"*, `values-uk` *"Махи ногами"* = **dynamic leg
  swings**; with the circle owned by the two siblings above, this pose authors the **pendulum**.

| exercise | pose class | canonical family | tests | authored cycle | measured |
|---|---|---|---|---|---|
| `hip_circles` | `HipCirclesPose` | standing (`BasePose` + `SkeletonFactory`; the pelvis needs both horizontal axes, which `BaseSquatPose`'s root surface does not author) | `HipCirclesPoseTest` (12) | one full turn: the pelvis's world `(x, z)` traces a circle, both feet planted, the trunk upright | circle radius `24.000` at every sample with the phase advancing `45.0°`/sample and closing at the seam; pelvis height constant (`226`); **both feet travel `0.000`** with heel/toe flat at `25.000000`; trunk `0.0°` off vertical; the knees absorb `148.10–150.25°` (knees travel `22.70 u`); clamp `0.0000`; worst clearance `25.000 u` |
| `leg_swings` | `LegSwingsPose` | `BaseSquatPose` (the standing family, like batch 1) | `LegSwingsPoseTest` (10) | a pendulum of constant radius about its own hip, from `+40°` (hip flexion) through the vertical to `−25°` (hip extension) | ankle X travel `218.142 u` with **Z travel `0.000 u`** (one sagittal plane); extremes measured `40.00°` / `−25.00°` (the authored amplitudes); knee `154.265°` **constant** (range `0.00°`) at radius `204.750 u`; stance foot travel `0.000`, flat; pelvis travel `0.000`; trunk `1.719°`; the swinging foot never below `23.002 u`; `8` distinct frames for `9` samples (the pendulum's own seam); clamp `0.0000` |
| `ninety_ninety_hips` | `NinetyNinetyHipsPose` | seated (`BasePose` + `SkeletonFactory`) | `NinetyNinetyHipsPoseTest` (15) | the 90/90 configuration rotates about the vertical as a rigid pair (thigh azimuths −20°/+70° → −90°/0°), the lower legs lifting over and setting down | **both knees exactly `90.000°` at every phase**; the two thighs exactly `90°` apart throughout; the hips flexed `87.2°/97.4°` at the entry and `100.4°/83.2°` at the switch end; both shins in the floor plane (`14.000`) at the named configurations; pelvis still (`0.000`) with the hips' level line at `44.000`; trunk `6.876°` (the authored lean); mid-switch lift knee `37.3 u` / ankle `133.1 u`; the two legs never closer than `44.0 u`; the published knee is the authored knee within `0.5 u`; clamp `0.0000` |
| `piriformis_stretch` | `PiriformisStretchPose` | supine (`BasePose` + `SkeletonFactory`) | `PiriformisStretchPoseTest` (13) | the figure-4: the crossed ankle rests on the opposite thigh while the hands pull that thigh through a `78° → 100°` hip flexion, held on a plateau, then released | ventral basis `(0, 1, 0)` ⇒ SUPINE; the crossed ankle rides `15.9–16.1 u` from the pulled thigh's axis and has crossed the midline (`z = +27.7` against its own hip's `−22`); the limbs never closer than `15.1 u`; the pull measured `78° → 100°` exactly; the hold plateau flat to `0.0000 u` (`5` distinct published frames); the hands hold the thigh (`10.9–11.1 u`) and travel `8.46 u` with it; the crossed foot's realized flexion `87.00°` at the entry (`90.04°` at the hold) against `90.00°` everywhere with the articulation removed; clamp `0.0000`; worst clearance `12.000 u` |

RED-before evidence (the same test files and harness, with the ONE hunk that removes the exercise's own
identity restored — the shape its illustration grouping implies on this exercise's declarations): **13 of the
50 tests RED, every message quoting the number it measured.** `HipCirclesPoseTest` `3/12` (the pelvis is
`16.97 u` from its circle's centre instead of `24.0`, the phase step is `0.0°`, the sweep collapses);
`LegSwingsPoseTest` `5/10` (the swing travels `0.000 u`, the forward extreme measures `0.000°` instead of
`40.0`, `1` distinct frame for `9` samples, the arc's radius is `202.73` instead of `204.75`);
`NinetyNinetyHipsPoseTest` `2/15` (the F hip does not change role: `87.2 → 87.2°`; `KNEE_F` travels only
`23.29 u` — the knees lift in place instead of switching sides); `PiriformisStretchPoseTest` `3/13` (the
crossed ankle lands at `z = −70.00` — it never crosses its own hip at `−22.00`; the crossed shin's Z travel
is `36.32 u` instead of crossing inboard; the crossed foot's own angle is `97.63°` — not flexed). The
counterfactual hunks are the identity itself (`circleZ → 0`, the swing target → the planted foot, the switch
sweep → `0`, the crossed leg's target → a generic lifted point), and each file was fingerprinted and restored
with `md5sum -c` before the next run.

**Flagged for the user (not resolved here):** the two standing members' authored cameras (`HipCirclesPose`,
`LegSwingsPose` — the standing family's own `CameraDefinition`) are in the hero canvas's *authored-frame clip*
inventory (`ViewportFramingInvariantTest.CLIPPED_AT_HERO`, `33 → 35`), while the batch's two floor poses (the
seated 90/90 and the supine stretch) fit the hero canvas as authored. The pin is re-measured and recorded;
**no camera or framing behaviour is changed by this batch.**

## 4. Gaps recorded per pose (no invented behaviour)

* `horse_stance`: "Tuck your pelvis slightly to avoid overarching the lower back" is not authored —
  the authored posture has no anterior pelvic tilt, and this rig expresses a pelvic tilt as the
  whole-body root rotation, so the cue's target (no overarching) holds without a representation the
  cue does not ask for.
* `wall_sit`: the back-on-wall contact is authored geometry (20 u body standoff), not a declared
  support contact (see §2); the wall is declared as a real `WallProp` and its plane is the reference
  the test measures.
* `ankle_mobility`: the forward travel is bounded by the back leg's own reach band (`202.66` u of
  `205.80`), i.e. the drill stops where the back leg does; the wall is placed just beyond the knee's
  end-of-drive position.
* `calf_stretch`: the arm chain's band bounds the shift (`121.53` / `101.37` u of `143.00`); the
  hands are authored on the wall's plane and re-solved as the body travels (the elbows bend).

### Batch 2 gaps (recorded, not invented)

* `rows`: the catalog's `steps`/`tech`/`mistakes` copy describes a *bent-over weighted row* while the id,
  the title, the equipment and the illustration all declare the *inverted* one — the stale-copy call §2
  already recorded, implemented and re-stated in the pose's KDoc; the foot plant is the rig's declared flat
  foot (the copy is silent on how the feet take the load, and the "heels only, toes up" variant needs an
  out-of-plane ankle DOF the declared-foot derivation deliberately flattens); "the chest touches the bar" is
  realized as the shoulder arriving within `40 u` of the bar's X plane (`BAR_ARRIVAL_X`, measured `34.2 u`)
  and is **not** asserted as a thorax surface contact — the rig carries no thorax-depth constant.
* `dips`: the foot posture is authored by convention (the copy says nothing about the legs/feet); the bar
  height is a product decision, recorded as such rather than presented as biomechanics; `steps` §3's torso
  angle is a copy-stated **choice** and the upright (triceps) variant is recorded, not authored; the girdle
  is left neutral — a driven depression is expressible but `reconstructChestFrame`'s unauthored-thorax
  fallback reads it back into the thorax's roll (measured `1.5 u` of shoulder→grip error at `1.5` activation
  units, which would put the copy's own "90-degree" bottom at `88.3°`).
* `band_pull_aparts`: **the band is not modeled** — the rig's environment vocabulary has no band primitive
  and the family's own banded member (`FacePullPose`) declares none either, so the band is implied by the
  name and by the hand travel (a `BandProp` is an engine/vocabulary change, outside this phase); the band's
  tension/stiffness is not simulated; the start width is an authored constant, not specification.
* `y_t_raises`: "the forehead lightly supported" has no representation (`SupportPoint` carries no head
  entry), so the head's floor relationship is authored geometry and is not declared; "keep the thumbs
  pointing up" is not expressible (the hand is one long axis with no thumb or roll DOF); the lift height is
  an authored fraction of the arm length; and the girdle is left neutral — in the prone layout its rotation
  is a vertical shoulder displacement that the unauthored-chest fallback carries twice (measured: `4`
  activation units publish `±12.7 u` of asymmetric shoulder travel, with the passive shoulder below the
  mat), recorded as a residual.

### Batch 3 gaps (recorded, not invented)

* `hip_circles` — **no copy exists** (`R.string.ex_hip_circles` is the whole entry). The movement is the
  pelvic circle the two localizations name (see §3 batch 3); the *stance depth*, the *stance width* and the
  circle's *radius* are therefore authored constants, chosen so the circle is realizable without clamping
  (measured worst hip→ankle chord `203.02 u` of the leg chain's `205.80 u` cap) and so the moving pelvis stays
  inside the base of support on both axes. Applying the drill's own *"hands on the hips"* convention is a
  convention too, and is stated as such at the declaration.
* `leg_swings` — **no copy exists.** Two authored choices are recorded rather than presented as
  specification: (a) the drill is authored **freestanding with the hands on the hips** — the common braced
  variant holds a wall, and the copy says nothing, so a wall relationship would be invented geometry (with a
  measured standoff, `WallSlidesPose`/`LatStretchPose` precedent) for a prop the exercise does not name;
  (b) **the single-leg stance's lateral weight shift is not expressible**: a real one-leg stance carries the
  pelvis laterally over its supporting foot (≈ half a hip width here), and the standing family's root surface
  is `(x, y)` only (`BaseSquatPose` authors `pelvis.z = 0` for every member), so the pelvis stays on the rig's
  mid-line and the stance ankle is placed under its own hip exactly as `HipCarsPose` places it. The swing's
  amplitudes and the stance depth are authored constants.
* `ninety_ninety_hips` — the **feet are not declared** as support: the drill's whole point is that they are
  picked up and set down again on the other side, and the declaration channel is per-pose, not per-frame (a
  foot contact would be a false statement for the transition — the same reasoning `YTRaisesPose` records for
  its lifted hands). The seat height, the lean and the knee lift are authored constants (the rig carries no
  pelvis-thickness constant; the `14` layer is the corpus's own seated/supine height). The copy's *"forcing the
  knees down"* mistake is honoured structurally (the knee angle is fixed at `90°` by construction and the
  shins rest in the floor plane at the two named configurations) — the rig has no "knee pressure" channel to
  drive.
* `piriformis_stretch` — two records. (a) **The clasp is not expressed**: the hands hold the thigh by being
  placed on its line, because the rig has no grip/hand-closure DOF (`HandDefinition` is a single long axis).
  (b) **The arms bound the grip, and that bound is measured, not asserted**: the shoulders sit a full torso
  length (`120 u`) from the pelvis plus a hip width laterally, against a `146 u` arm (`40.13 … 143.08`
  reachable), so the near-side hand can clasp the pulled thigh only near the hip — at the authored grip
  (`0.2` of the thigh) the chord measures `131 … 140 u` across the cycle, where a grip at the thigh's middle
  would demand `~147 u` and be relocated by the solver. The pull is consequently the `22°` the copy's own
  *"only until the hip stretches"* asks for. (c) **The crossed foot's flexion is only partly realized**: the
  authored `20°` dorsiflexion reaches the published foot as `87.00°` (entry) / `90.04°` (hold) against
  `90.00°` with the articulation removed, because the engine's extremity derivation builds the foot from the
  shank and the authored hint and composes the articulation after it — an engine-side residual, recorded and
  pinned rather than tuned away.


## 5. Verification (batch 1)

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `4a32d84` | `133` classes / `670` tests / `0F` / `0E` |
| focused, fresh | branch on `4a32d84` | `HorseStancePoseTest` 11, `WallSitPoseTest` 12, `AnkleMobilityPoseTest` 10, `CalfStretchPoseTest` 9 — all green |
| full, fresh (`--rerun-tasks`, results purged) | branch on `4a32d84` | `138` / `715` / `0F` / `0E` = **exactly +5 classes / +45 tests** |
| baseline | pristine `origin/main` @ `7df32c0` (after the canonical-SkeletonFactory migration merge, PR #257) | `134` classes / `673` tests / `0F` / `0E` |
| full, fresh (`--rerun-tasks`, results purged) | **this branch, rebased onto `7df32c0`** | `139` classes / `718` tests / `0F` / `0E` / `0S` = **exactly +5 classes / +45 tests**, no collateral |

The rebase onto `7df32c0` was required: the canonical-SkeletonFactory migration landed on `main` while
this batch was being authored, and seven of the eight corpus-digest guards are the same files that
migration re-baselined. The batch's digest values were therefore **re-measured on the rebased tree**
(the rebase resolved those seven files in favour of `main`'s version first, and the guards were then
re-baselined from a live run — each failure message printed its `measured=` value).

**Unrelated poses unchanged — measured, on both bases.** A throwaway per-pose digest probe (the
guards' own hashing recipe: a fresh `SkeletonPipeline` per pose, the metadata-derived entry point,
`progress ∈ {0, ¼, ½, ¾, 1}`, every joint, `hash * 31 + floatToIntBits`) was run in a pristine
`origin/main` worktree and on this branch:

| base | pre-existing pose classes compared | differing | added | removed |
|---|---|---|---|---|
| `4a32d84` | 51 | **0** | 4 (`HorseStancePose`, `WallSitPose`, `AnkleMobilityPose`, `CalfStretchPose`) | 0 |
| `7df32c0` (rebased) | 51 | **0** | 4 (same) | 0 |

So the eight `UNAFFECTED_CORPUS_DIGEST` REDs were **corpus membership**, not geometry drift — the
guards' corpora are "every production pose class except their own corrected pose", and the four new
classes join them. `M8M9M10SupportDeclarationTest` needed no re-baseline at all after the rebase
(its measured value is tree-independent here, which independently corroborates the migration's own
byte-identical claim). `ViewportFramingInvariantTest.CLIPPED_AT_HERO` is re-measured with the four
new poses — **no camera/framing behaviour is changed by this batch**.

## 6. Verification (batch 2)

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `6887738` (a fresh worktree, `--rerun-tasks`) | `139` classes / `718` tests / `0F` / `0E` / `0S` |
| focused, fresh | branch on `6887738` | `RowsPoseTest` 13, `DipsPoseTest` 16, `BandPullApartPoseTest` 11, `YTRaisesPoseTest` 12, `AnimationCoverageTest` 3 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes removed) | branch on `6887738` | `143` / `770` / `0F` / `0E` / `0S` = **exactly +4 classes / +52 tests** (the four new pose test classes), no collateral |
| release build | branch on `6887738` | `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` **SUCCESS** (`app-debug.apk` produced); `:app:assembleRelease` stops at `:app:lintVitalRelease`, which fails with **2 pre-existing errors** — `app/src/main/res/values/themes.xml:2` (`ResourceCycle`) and `app/build.gradle.kts:25` (`ExpiredTargetSdkVersion`) — reproduced identically on the pristine base tree, i.e. not this batch's |

**Coverage moved `53/66 → 57/66`**, asserted by `AnimationCoverageTest` (the four ids added to
`REQUIRED_SKELETAL_ANIMATION_IDS`, plus `REQUIRED_COVERAGE_MILESTONE = 57` measured from the app's own
`LibraryStats.animatedExercisesCount`).

**Unrelated poses unchanged — measured, on both trees.** The batch's own per-pose digest probe (the guards'
own hashing recipe: a fresh `SkeletonPipeline` per pose, the metadata-derived entry point, `progress ∈
{0, ¼, ½, ¾, 1}`, every joint, `hash * 31 + floatToIntBits`) run in a pristine `origin/main` worktree
(`6887738`) and on this branch:

| base | pre-existing pose classes compared | differing | added | removed |
|---|---|---|---|---|
| `6887738` | 55 | **0** | 4 (`RowsPose`, `DipsPose`, `BandPullApartPose`, `YTRaisesPose`) | 0 |

So all nine guard REDs were **corpus membership, not geometry drift**: the eight `UNAFFECTED_CORPUS_DIGEST`
guards' corpora are "every production pose class except their own corrected pose", and the four new classes
join them. The digest values were re-measured from the live run (each failure message printed its
`measured=`), and `ViewportFramingInvariantTest.CLIPPED_AT_HERO` is re-measured with the new poses
(`30 → 33`) — **no camera/framing behaviour is changed by this batch**.

**Two further production censuses moved with the batch, and both were re-measured rather than relaxed:**

* `ExtremityArticulationTest` — `DipsPose` authors its hanging feet through `buildAnkleArticulation` (the
  Branch-C carrier), so the registry-derived migrated corpus gained exactly `dip_parallel_bar`
  (`11 → 12`), which also puts that pose under the carrier-vs-node equivalence guard.
* `RuntimeSolverOwnershipAuditTest.limbRealizationWritesStayInsideTheRegisteredImplementations` — the new
  family base is the corpus's **only pose-side writer** of the realization write idiom
  (`toLocalDirection(… .localPosition)`), for the *distal* hand chain of a `MANUAL_OVERRIDE` hand. The
  census now carries it as a classified, structurally anchored entry (the write lives inside `setSegment`;
  all three call sites are handed palm/knuckles/fingertips nodes; the two flat-grip calls are on each
  hand's distal chain and never on an IK middle/end node). Why the pose must author that geometry at all:
  `SkeletonPoseFinalizer` completes a hand's distal joints **only while the extremity is `AUTOMATIC`**
  (`adjustHandOrientation` is gated by `isExtremityAutomatic`), so a `MANUAL_OVERRIDE` hand is the pose's
  own — measured on the row's published frames: the authored chain is `22 u` long and lies `0.000 u` off
  the bar's plane at all five phases; with the authoring helper neutered it collapses to a zero-length
  chain at the wrist; and under the engine's `AUTOMATIC` derivation (override dropped) the same chain tilts
  up to `20.927 u` out of that plane. **Flagged (recorded, not resolved):** whether a pose should be able
  to state a `MANUAL_OVERRIDE` extremity through the offset idiom at all — versus the wrist-articulation
  channel `PikePushUpPose` uses — is an ownership decision for the architecture owner.

### What the batch's own verification forced (recorded, not silent)

1. **`YTRaisesPoseTest` did not compile.** The batch's last edit renamed the mat constant
   (`PRONY_MAT_PLANE` → `PRONE_MAT_Y`) and the pose kept the old spelling at 10 of its 11 sites, so the
   tree was mid-rename and non-compiling. The rename was completed **in the test**; production untouched.
2. **A real geometry defect — the YT raises' elbow went through the mat.** The arm pole was `(0, 0, ∓1)`,
   nearly parallel to the T raise's own chord (the arm lies along ±Z there), so the solver's residual
   perpendicular pointed DOWN: `ELBOW_A` published at `y = -2.584` at the T peak — `2.584 u` through the
   mat, caught by that class's own floor invariant. The pole is now the prone family's own `(0, 1, ∓1)`
   (`ReverseSnowAngelPose`/`SupermanPose`/`ProneCobraStretchPose` author the same), which puts the same
   elbow `35.423 u` above the shoulder line. Measured A/B of elbow-above-shoulder: rest `0.000 → 17.167`,
   Y peak `9.715 → 28.537`, T peak `-12.584 → 35.423`.
3. **Two test-side defects.** (a) the dips bar-position assertion compared an exact `Float` set against a
   value it had already rounded to `0.1 u` (`expected:<[-50.600002, 50.600002]> but was:<[-50.6, 50.6]>`);
   it now derives the expectation from the pose's published factor and compares with a tolerance. (b) the
   YT raises' `theSweepSamplesDistinctPublishedFrames` asserted "as many distinct frames as samples",
   which the drill's own **symmetric** rhythm cannot satisfy by construction (a raise and its controlled
   lowering pass through the same arm position); it now asserts the rhythm's own `5` frames and
   additionally proves the rest/Y/T frames differ — it reads `7` on the pre-phase shape, so the assertion
   is not vacuous.
4. **A batch-1 record repaired while here.** `M8M9M10SupportDeclarationTest`'s batch-1 KDoc paragraph
   recorded its pre-rebaseline measurement as a literal `%d` placeholder; the value
   (`7499664576150638435`) was recovered from the pre-batch-1 tree (`7df32c0`) and written in.

## 7. Verification (batch 3)

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `139daf9` (a fresh worktree, `/tmp/cov03-base`, `--rerun-tasks`) | `143` classes / `770` tests / `0F` / `0E` |
| focused, fresh | branch on `139daf9` | `HipCirclesPoseTest` 12, `LegSwingsPoseTest` 10, `NinetyNinetyHipsPoseTest` 15, `PiriformisStretchPoseTest` 13, `AnimationCoverageTest` 3 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes removed) | branch on `139daf9` | `147` / `820` / `0F` / `0E` / `0S` = **exactly +4 classes / +50 tests** (the four new pose test classes), no collateral |
| release build | branch on `139daf9` | `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` **SUCCESS** (`app-debug.apk` produced); like batch 2, `:app:assembleRelease` stops at the pre-existing `:app:lintVitalRelease` failure (`themes.xml` `ResourceCycle`, `ExpiredTargetSdkVersion`), which this batch does not touch |
| RED-before | branch, each pose's own identity hunk restored in turn | `13` of the `50` tests RED, messages quoting the measured numbers (see §3 batch 3); every production file restored from its fingerprint (`md5sum -c` OK) |

**Coverage moved `57/66 → 61/66`**, asserted by `AnimationCoverageTest` (the four ids added to
`REQUIRED_SKELETAL_ANIMATION_IDS`, plus `REQUIRED_COVERAGE_MILESTONE = 61` measured from the app's own
`LibraryStats.animatedExercisesCount`).

**Unrelated poses unchanged — measured, on both trees.** The batch's own per-pose digest probe (the guards'
own hashing recipe: a fresh `SkeletonPipeline` per pose, the metadata-derived entry point, `progress ∈
{0, ¼, ½, ¾, 1}`, every joint, `hash * 31 + floatToIntBits`) run in a pristine `origin/main` worktree
(`139daf9`) and on this branch:

| base | pre-existing pose classes compared | differing | added | removed |
|---|---|---|---|---|
| `139daf9` | 59 | **0** | 4 (`HipCirclesPose`, `LegSwingsPose`, `NinetyNinetyHipsPose`, `PiriformisStretchPose`) | 0 |

So all eleven guard REDs were **corpus membership, not geometry drift**: the eight `UNAFFECTED_CORPUS_DIGEST`
guards' corpora are "every production pose class except their own corrected pose", and the four new classes
join them. Each digest was re-baselined to its printed `measured=` value with the responsible change named at
the constant, and `ViewportFramingInvariantTest.CLIPPED_AT_HERO` was re-measured (`33 → 35`) — **no
camera/framing behaviour is changed by this batch.**

**Two further production censuses moved with the batch, and both were re-measured rather than relaxed:**

* `ExtremityArticulationTest` — `PiriformisStretchPose` authors its crossed foot through
  `buildAnkleArticulation` (the copy's own *"Flex the crossed foot to protect the knee"*), so the
  registry-derived migrated corpus gained exactly `piriformis_stretch_hold` (`12 → 13`), which also puts that
  pose under the carrier-vs-node equivalence guard. The batch's other three poses author no articulation.
* The `PublishedBelowGroundInvariantTest` (T2) pin table and `EnvironmentPenetrationTest` (B-6) needed **no**
  new entries: every joint of every published frame of all four new poses clears the declared floor
  (worst clearance `25.000` / `23.002` / `10.000` / `12.000 u` respectively), and no declared contact
  (the planted feet, the sit bones, the seated hands) sits below the surface it declares.

**No part of the engine, the solver, the reach band, the camera layer or the legacy illustration map was
touched by this batch:** the diff is four new pose classes, their four test classes, the two registries, the
coverage guard and the re-baselined guards (plus this record).
