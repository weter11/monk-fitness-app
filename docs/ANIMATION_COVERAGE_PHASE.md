# Animation Coverage Phase — 49/66 → 66/66

**Status:** COMPLETE — **`66/66`** after batch 5 (batch 1 landed `53/66`, batch 2 `57/66`, batch 3 `61/66`, batch 4 `63/66`).
Every catalog exercise now runs a real skeletal animation driven by the engine; the legacy illustration path is
empty and `AnimationCoverageTest.noCatalogExerciseIsLeftOnTheLegacyIllustrationPath` asserts it structurally.

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

### Batch 4 — the cervical-mobility family (2 exercises)

The two exercises this batch converts are the catalog's whole `neck_mobility` family, and they share the
one property no earlier batch met: **the movement lives entirely in the neck/head chain while the rest of
the body is a fixed, planted standing frame.** They are therefore authored on a new small family base,
**`BaseCervicalPose`** (the shared standing chassis: an upright still trunk, both feet planted on the
family's floor frame, the arms hanging relaxed at the sides, and the neutral cervical chain), which is
what makes each member's own file read as its driver alone. The rig's cervical chain is two rigid bones
(`CHEST → NECK_END → HEAD_POS`, `18 u` + `18 u`), so both members author it as **the chain's two bone
directions** — the same arithmetic the engine's own gaze resolver writes — and neither declares a world
`headTarget` (the resolver is the sole writer of those offsets and only runs for a pose that declared
one). `Base*` files are excluded from every corpus in the repository, so the base adds no membership.

| exercise | pose class | canonical family | tests | authored cycle | measured |
|---|---|---|---|---|---|
| `chin_tucks` | `ChinTuckPose` | `BaseCervicalPose` (canonical `SkeletonFactory` tree) | `ChinTuckPoseTest` (13) | entry → hold → release: the neck's bone turns posteriorly `24°` while the head rides it **level**, then releases | the head's base and the head both travel `7.3397 u` straight back at the hold with the head's own bone `0.0000°` off vertical at **every** phase (the neck carries `24.0642°`); the head never comes forward of neutral; `1.5644 u` of the `1.5644 u` geometric drop; the plateau flat to `0.0000 u` (`5` published frames of the `9`-sample sweep); every non-cervical joint travels `0.0000 u`; shoulders `0.0000 u` (no shrug); clamp `0.0000`; worst clearance `25.000 u` |
| `neck_circles` | `NeckCirclesPose` | `BaseCervicalPose` | `NeckCirclesPoseTest` (13) | one full turn per cycle: both cervical bones lie on one cone of `15°` whose azimuth advances `45°/sample` | the head's locus is a **horizontal circle** of radius `9.3175 u` (measured per-sample against the authored `(18 + 18) · sin 15°`), the neck's tip rolling on the inner circle at exactly half that (`4.6587 u`), both bones on the same axis at `15.0000°` at every phase, the head's height constant (`0.0000 u` travel), the azimuth closing a full turn in one direction, both bones `18 u`, every non-cervical joint `0.0000 u`, clamp `0.0000`, worst clearance `25.000 u` |

RED-before evidence (the same test files, same harness, with the ONE hunk that carries each exercise's own
identity reverted — the shape its illustration grouping implies on this exercise's declarations): **9 of the
26 tests RED, every message quoting the number it measured.** `ChinTuckPoseTest` `3/13` (the rhythm
collapses to `1` published frame of `9`, the neck reads `0.0000°` off vertical against the authored
`8.3411°`, the head travels `0.0000 u` — the retraction is simply not there); `NeckCirclesPoseTest` `6/13`
(the head sits `0.0000 u` from the circle's centre instead of `9.317507`, the neck's tip rides `0.0000 u`
instead of `4.6587534`, only `1` of `9` samples is distinct, the azimuth step is `0.0000°`). A second
counterfactual isolates the cervical pair's specific discriminator: with the *head's* bone tilting with the
neck instead of riding it level — the naive reading of "draw the chin back" on a two-bone chain, i.e. a
**nod** — `theHeadIsRetractedWithoutTipping` reports `the head's own axis tips 8.3412 deg off vertical at
p=0.1250 … that is a nod` and `theHeadNeverPokesForwardOrDropsTowardTheFloor` reports `the head drops
2.9136 u toward the floor`. Every production file was fingerprinted and restored (`md5sum -c` OK) before the
next run. The guard-style assertions (planted feet, flat feet, bone lengths, stillness of the rest of the
body, the declared support reaching the published frame) stay GREEN in both counterfactuals — they pin
behaviour that must not change.

**Flagged for the user (not resolved here):** the two upright standing poses' authored cameras (the standing
family's own `CameraDefinition`) are in the hero canvas's *authored-frame clip* inventory
(`ViewportFramingInvariantTest.CLIPPED_AT_HERO`, `35 → 37`), exactly like the other 35 standing/overhead
members. The pin is re-measured and recorded; **no camera or framing behaviour is changed by this batch.**

### Batch 5 — the phase's tail (3 exercises), and why it is three poses rather than a family

The last three uncovered exercises are the ones **no family grouping can carry**: a standing *single-arm*
shoulder mobility drill, a ballistic full-body jack and a floor-bound kneeling fold. They share no chassis, no
limb convention and no support base, so each is authored as its own pose class on the canonical
`SkeletonFactory` tree through [BasePose] — **no shared base class, no shared authoring helper, and no
implementation reused between them.** They are one PR (`feat/animation-coverage-05`) — and, deliberately, **not
one PR per exercise**, because everything the batch changes outside the three new files is *tree-level*: the
eight `UNAFFECTED_CORPUS_DIGEST` constants, the two registry-derived censuses and the coverage milestone are all
measured on ONE final tree. Three independently-merged PRs would each have re-baselined the same constants and
collided on the merge (the pattern the phase's earlier batches avoided by landing sequentially with a rebase
onto each new `main`), and splitting the branch's own commits per exercise would re-pin those same constants
three times without any of the three trees being the one the constants describe. The branch is therefore three
commits by *concern*: the poses and their tests, then the guards re-baselined from this batch's measured final
tree, then this record.

| exercise | pose class | canonical family | tests | authored cycle | measured |
|---|---|---|---|---|---|
| `shoulder_cars` | `ShoulderCarsPose` | `BasePose` + `SkeletonFactory` (the torso-quiet standing chassis) | `ShoulderCarsPoseTest` (13) | **one full revolution of ONE arm** per cycle — hanging → *straight in front* → *overhead* → *behind* → hanging — on a constant radius, with the azimuth schedule slowing through the posterior quadrant | radius **`137.600 u` constant**, elbow interior **`140.751°` constant**, hand travel `272.277 u` (elbow `153.097 u`), **every joint outside the working arm travels `0.000 u`** (the other arm, both girdles, the trunk, the neck/head and the planted stance), azimuth `0.000 → 6.283 rad` strictly increasing with per-sample steps `0.888 / 1.032 / 1.032 / 0.888 | 0.683 / 0.539 / 0.539 / 0.683`, clamp `0.00000`, worst clearance `25.000 u`, `9` of `9` distinct frames |
| `jumping_jacks` | `JumpingJacksPose` | `BasePose` + `SkeletonFactory` (the standing chassis with a ballistic root) | `JumpingJacksPoseTest` (13) | **two coordinated hops per cycle**: closed stance → flight 1 (opening) → open stance → flight 2 (closing) → closed stance, one `openness` signal driving the arms *and* the legs | closed stance `24.200 u` → open `105.600 u` (both symmetric about the mid-line); hands `(0, −136, 0)` at the hips → `(0, 0, ∓136)` level → `(0, +136, 0)` overhead at a constant `136.000 u` radius; ankles `25.000 → 45.000 u` through each flight; pelvis `226.000` (touch-down) → `240.000` (apex) → `212.000` (loaded); knee interior `146.716° → 128.848°`; feet flat (`0.000 u` toe-to-heel) at the closed stance and pointed (`15.2 u` toe under heel) at the apex; worst leg chord `203.3 u` of the chain's `205.80 u` cap; clamp `0.00000`; worst clearance `23.987 u`; `7` of `9` distinct frames (the drill's own symmetric rhythm) |
| `child_pose` | `ChildPose` | `BasePose` + `SkeletonFactory` (kneeling: the pinned knee is the drill's axis) | `ChildPoseTest` (16) | kneel → **sit the hips back and fold** (entry, `35 %` of the cycle) → **hold** (`45 %`) → **rise back** (`20 %`) | knees/ankles/toes **pinned** (`travel 0.000 u`) with the knee on the mat's own `15.000 u` layer; the hip rides the **femur's circle** (`112 u` about the pinned knee) travelling `92.0 u` back / `47.8 u` down; shins flat (`y 0.000`, `Δz 0.000`); trunk `109°` + thorax `26°` + cervical `15°`; head tip `8.999 u` (the corpus's mat layer); hands `139.810 u` from their shoulder at `y = 15.000` (elbow `146.353°`), ahead of the head; feet lie back along the shins; deepest hip→ankle chord `64.5 u` against the chain's `56.01 u` fold stop; the plateau flat to `0.0000 u`; clamp `0.00000`; `5` of `9` distinct frames |

RED-before evidence (the same test files and harness, with **each exercise's own legacy illustration grouping**
restored on that exercise's declarations — `shoulder_cars` → the `ArmCirclesPose` generic two-arm circle it is
grouped with, `jumping_jacks` → the illustration's own closed frame held, `child_pose` → the **prone** layout it
is grouped with): **17 of the 42 tests RED** — `ShoulderCarsPoseTest` 5/13, `JumpingJacksPoseTest` 7/13,
`ChildPoseTest` 5/16 — every message quoting the number it measured, e.g. `the shoulder→hand radius is
128.8686 u … against the authored 137.6000 u`, `at p=0.1250 the hand is 90.8491 u above its shoulder — the low
half of the arc is missing`, `the arc's slow-down is only 1.0000 of its fast part`, `the ankles are 25.0000 u
high at p=0.1250 against the flight's own 8.0784 u`, `the open shape's stance is 24.2000 u`, `1` distinct
published frame of `9` (the T-7 aliasing signature), `the trunk's own fold at the hold is 90.0000° … expected
108.99949`, `the hold's hip X moved expected:<6.2569656> but was:<98.0>`, and — from the child-pose
counterfactual, a real defect of the naive authoring — `HAND_A is -12.4918 u below the mat`. All three
production files were fingerprinted and restored (`md5sum -c` OK) before the next run. The guard-style
assertions (the pinned kneel, the planted stance, the declared support reaching the frame, the bone lengths,
the reach band) stay GREEN in every counterfactual — they pin behaviour that must not change.

**Flagged for the user (not resolved here):** all three poses' authored cameras are in the hero canvas's
*authored-frame clip* inventory (`ViewportFramingInvariantTest.CLIPPED_AT_HERO`, `37 → 40`) — the standing CAR
(whose arc is a full revolution about one shoulder), the ballistic jack (whose wide stance and overhead hands
both exceed the canvas) and the kneeling fold (whose body lies the mat's full width from the toes to the
reaching hands). The pin is re-measured and recorded; **no camera or framing behaviour is changed by this
batch.**

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


### Batch 4 gaps (recorded, not invented)

* `chin_tucks` — two records. (a) **The copy's supine option is not authored**: `steps` §1 says *"Stand tall
  **or lie on your back**"*, i.e. two positions for one exercise; the standing one is authored (the drill's
  catalog entry is a standing posture drill and the timer variant is performed standing) and the supine
  option is recorded rather than duplicated into a second pose class. (b) **"Train the deep neck flexors"
  and "Make the neck feel long at the top" have no representation**: the neck is a fixed-length rigid bone
  whose length the validator pins, and the rig carries no muscular or jaw channel — neither cue is faked.
  (c) The amplitude (`24°`), the hold fractions and the stance are **authored constants**; the copy states
  no number (only *"gently"* / *"small but precise"*), and the amplitude is bounded in the test from both
  ends (`≥ 5 u` of real retraction, `≤ 10 u` — a fraction of the `18 u` head bone).
* `neck_circles` — **no copy exists at all** (`R.string.ex_neck_circles` is the whole catalog entry: the
  title, in all three locales). The movement is the cervical circle those three titles name; the **cone's
  amplitude** (`15°`, i.e. a `9.32 u` head circle) and the **direction** of the turn are authored constants,
  and the direction is only asserted as *one* consistent direction rather than a claim about the viewer's
  clock. The drill's tempo/repetition count is the catalog's timer, not a kinematic property.
* Both members: **the "one arm at a time / arms relaxed" and the whole standing chassis** (stance width,
  depth and height, the rest-arm radius) are authored conventions — stated at `BaseCervicalPose` — because
  neither entry's copy says anything about the arms.

### Batch 5 gaps (recorded, not invented)

* `shoulder_cars` — four records. (a) **The working girdle does not carry the arm, and that is a measured
  engine residual rather than an oversight.** A real CAR includes the scapulohumeral rhythm; this rig's
  thorax is *unauthored* here, so `SkeletonPoseFinalizer.reconstructChestFrame` re-derives it from the
  shoulder line, which a one-sided clavicular elevation tilts. Measured with a driven elevation of `0.6`
  activation units on this pose: the working shoulder rises `13.8 u` and the thorax is read back into a
  **`5.4°` roll that the neck carries** — the same residual `DipsPose` records for its driven depression, and
  the same reason `BandPullApartPose` drives the girdle *symmetrically*. Publishing that roll would break the
  drill's own cue (*"Keep the torso quiet"* / *"Twisting through the spine"*), so the girdle is left neutral and
  the scapular component is recorded rather than faked; the pose's own test pins the consequence (both
  shoulders hold one height for the whole cycle). (b) **The drill is one arm at a time and the far side is not
  duplicated**: the catalog's `alternating` flag is `false` and the copy says *"then switch arms"* — the mirror
  is the same authored geometry, recorded here rather than authored twice (the same call the neck pair's supine
  option records). (c) **The rig carries no humeral axial-rotation channel**, so the humeral roll a real CAR
  also owns is not representable. (d) *"Brace your ribs down"* is honoured structurally (a vertical trunk that
  never extends); the rig has no separate rib channel. The radius (`0.96 × maxReach`, the corpus's own
  straight-arm convention), the **sticky-spot schedule** (`20°` of re-distributed azimuth, the direct
  structural reading of `mistakes`' *"Speeding through the sticky spots"*), the outboard plane offset and the
  cycle length are **authored constants** — the copy names no number, saying only "as far as you can control" /
  "smooth, not fast".
* `jumping_jacks` — **no copy exists at all** (`R.string.ex_jumping_jacks` is the whole entry, in all three
  locales), so the identity is the exercise's name across the three titles plus the one illustration that shows
  it: a two-frame open/close cycle whose *closed* frame has the hands at the hips with the toes together and
  whose *open* frame has the hands overhead (`0.19`, above the head at `0.20`) with the toes wide apart. Three
  records follow. (a) **The flight cannot be declared**: `metadata.support` is per-pose, so the declaration
  names the drill's base of support (both feet — the floor it leaves twice and lands on twice per cycle), which
  is exactly how the corpus's other jump (`JumpSquatPose`) declares it; the two airborne windows are stated at
  the pose instead. (b) **The wide stance's natural toe-out is not authored** — the rig's foot-heading channel
  exists, but the copy is silent, so both feet keep the standing family's forward heading all cycle. (c) **The
  hop's height is authored geometry**, not a physics result: the rig carries no ballistic or ground-reaction
  model, and the catalog states no amplitude. The arms' radius and the two stance factors are authored too,
  chosen so the arms stay long (no elbow cheat) and the widest stance remains inside the leg chain's reach band
  (`203.3 u` of `205.80 u`, measured).
* `child_pose` — five records. (a) **The declared contacts are the hold's**: `metadata.support` is per-pose, so
  the kneeling base (both knees + both hands, pivot `KNEES`) is the position the catalog's timer *is*; through
  the entry and the rise the hands are lifted (they hang at the sides at `p = 0`) — the reading `YTRaisesPose`
  records for its lifted hands and `NinetyNinetyHipsPose` for its picked-up feet. The **knees are on the mat at
  every phase**, so their half of the declaration is true throughout. (b) **The feet are declared on the
  canonical foot channel** (`LEFT_FOOT`/`RIGHT_FOOT`): in a kneel the foot's *dorsum* is the surface, which the
  rig's vocabulary has no separate point for, and the declaration is also what activates the foot's own
  heading/flattening derivation — without it the channel's neutral fallback lays the feet *forward* under the
  shins they are meant to continue (measured `TOE_F` at `+24.85`; with the declaration, `−24.85` and the heading
  is authored in the pelvis's own frame because the channel is root-relative). (c) **The forehead has no
  `SupportPoint`** (`SupportMath.jointsFor` carries no head entry), so the head's mat relationship is authored
  geometry (`8.999 u`) and is not declared — the vocabulary gap `YTRaisesPose` also records. (d) **The grip is
  not expressed**: `HandDefinition` is a single long axis, so the hands rest on the mat rather than curling into
  it. (e) **The `tech`'s conditional knee-widening** (*"Widen the knees if you need more space for the torso"*)
  is a variant for a lifter who needs it; the authored stance is the neutral one (the thighs in their own
  sagittal planes) and the wider variant is recorded rather than authored as a second exercise. *"Breathe into
  the ribcage"* has no channel at all. The fold angles, the hold's fraction of the cycle, the hands' placement
  and the cycle length are authored constants (the copy says only "gently" / "slowly"), and the **sit-back is
  bounded by the leg chain's own fold stop** — `55°` of femur leaves `8.5 u` of the chain's `56.01 u` stop, so
  the drill's `mistakes` line (*"forcing the hips to the heels when mobility is limited"*) holds structurally.

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

## 8. Verification (batch 4)

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `7fed307` (a fresh worktree, `/tmp/ac04-base`, `--rerun-tasks`) | `147` classes / `820` tests / `0F` / `0E` / `0S` |
| focused, fresh | branch on `7fed307` | `ChinTuckPoseTest` 13, `NeckCirclesPoseTest` 13, `AnimationCoverageTest` 3 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes removed) | branch on `7fed307` | `149` / `846` / `0F` / `0E` / `0S` = **exactly +2 classes / +26 tests** (the two new pose test classes), no collateral |
| release build | branch on `7fed307` | `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` **SUCCESS** (`app-debug.apk`, `11243385` bytes); `:app:assembleRelease` fails at `:app:lintVitalRelease` **only** — `app/src/main/res/values/themes.xml:2` `ResourceCycle` + `app/build.gradle.kts:25` `ExpiredTargetSdkVersion` — reproduced identically on the pristine `origin/main` worktree, and neither file is touched by this batch's diff (the failure is not this batch's) |
| RED-before | branch, each pose's own identity hunk restored in turn (plus the nod counterfactual) | `9` of the `26` tests RED, messages quoting the measured numbers (see §3 batch 4); every production file restored from its fingerprint (`md5sum -c` OK) |

**Coverage moved `61/66 → 63/66`**, asserted by `AnimationCoverageTest` (the two ids added to
`REQUIRED_SKELETAL_ANIMATION_IDS`, plus `REQUIRED_COVERAGE_MILESTONE = 63` measured from the app's own
`LibraryStats.animatedExercisesCount`). An **independent census** (a throwaway probe over the
`WorkoutGenerator` catalog + `PoseRegistry`, run on the branch and removed before the counted suite)
reports the same metric on the same tree:

```
CATALOG=66
REAL-ANIMATED=63
UNCOVERED=3
LIBRARY-STATS animatedExercisesCount=63 totalExercises=66
UNCOVERED-IDS=shoulder_cars(shoulder_cars_standard), jumping_jacks(jumping_jack_standard), child_pose(child_pose_hold)
CHIN-TUCK-PATH  skeletonAnimation=true poseConfig=true
NECK-CIRCLES-PATH skeletonAnimation=true poseConfig=true
```

— i.e. both batch-4 exercises satisfy `ExerciseHero`'s real condition (`Exercise.skeletonAnimation != null`
**and** `PoseRegistry.getPoseConfig(animationId) != null`), and the three exercises still uncovered are
exactly the ones batches 5+ owe.

**Unrelated poses unchanged — measured, on both trees.** The batch's own per-pose digest probe (the guards'
own hashing recipe: a fresh `SkeletonPipeline` per pose, the metadata-derived entry point, `progress ∈
{0, ¼, ½, ¾, 1}`, every joint, `hash * 31 + floatToIntBits`) run in a pristine `origin/main` worktree
(`7fed307`) and on this branch:

| base | pre-existing pose classes compared | differing | added | removed |
|---|---|---|---|---|
| `7fed307` | 63 | **0** | 2 (`ChinTuckPose`, `NeckCirclesPose`) | 0 |

So all nine guard REDs were **corpus membership, not geometry drift**: the eight `UNAFFECTED_CORPUS_DIGEST`
guards' corpora are "every production pose class except their own corrected pose", and the two new classes
join them. Each digest was re-baselined to its printed `measured=` value with the responsible change named at
the constant, and `ViewportFramingInvariantTest.CLIPPED_AT_HERO` was re-measured (`35 → 37`) — **no
camera/framing behaviour is changed by this batch.**

**Two censuses that did NOT move (checked, not assumed):**

* `ExtremityArticulationTest` — neither member authors `buildAnkleArticulation`/`buildWristArticulation`, so
  the registry-derived carrier corpus is unchanged (`13`), and neither pose enters the carrier-vs-node
  equivalence loop.
* The `PublishedBelowGroundInvariantTest` (T2) pin table and `EnvironmentPenetrationTest` (B-6) needed **no**
  new entries: every joint of every published frame of both poses clears the declared floor (worst clearance
  `25.000 u` each, the planted ankles) and no declared contact (the planted feet) sits below its surface.

**One shared test helper gained a field, additively:** `PoseFrameSweep.Frame` now also carries the
`supportedPoints` the published frame holds (R8/B-5's resolved Support Declaration), so a pose test can
assert that its declaration really reaches the frame the hero draws instead of comparing metadata to a
literal. It is a default-valued constructor parameter, so no existing call site or assertion changes.

**No part of the engine, the solver, the reach band, the camera layer or the legacy illustration map was
touched by this batch:** the diff is the family base, two pose classes, their two test classes, the two
registries, the coverage guard, the shared sweep helper's additive field and the re-baselined guards (plus
this record).


## 9. Verification (batch 5) — and the phase's completion

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `b0e4edd` (a fresh worktree, `/tmp/ac05-base`, `--rerun-tasks`) | `149` classes / `846` tests / `0F` / `0E` / `0S` |
| focused, fresh | branch on `b0e4edd` | `ShoulderCarsPoseTest` 13, `JumpingJacksPoseTest` 13, `ChildPoseTest` 16, `AnimationCoverageTest` 4 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes moved out of the tree) | branch on `b0e4edd` | `152` / `889` / `0F` / `0E` / `0S` = **exactly +3 classes / +43 tests** (the three new pose test classes and the coverage guard's new completion assertion), no collateral |
| release build | branch on `b0e4edd` | `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` **SUCCESS** (`app-debug.apk`, `11259769` bytes); `:app:assembleRelease` fails at `:app:lintVitalRelease` **only** — `app/src/main/res/values/themes.xml:2` `ResourceCycle` + `app/build.gradle.kts:25` `ExpiredTargetSdkVersion` — the repository's documented pre-existing pair, neither file in this batch's diff |
| RED-before | branch, each pose's own illustration-grouping counterfactual in turn | `17` of the `42` tests RED (`5/13`, `7/13`, `5/16`), messages quoting the measured numbers (see §3 batch 5); all three production files restored from their fingerprints (`md5sum -c` OK) |

**Coverage moved `63/66 → 66/66`**, asserted by `AnimationCoverageTest`: the three ids added to
`REQUIRED_SKELETAL_ANIMATION_IDS`, `REQUIRED_COVERAGE_MILESTONE = 66` measured from the app's own
`LibraryStats.animatedExercisesCount`, **and a new structural assertion** —
`noCatalogExerciseIsLeftOnTheLegacyIllustrationPath` — which checks the whole catalog rather than the phase's
own list: every exercise the app can show resolves to the engine, and the metric's denominator is read from the
catalog instead of assumed. A registry-level census corroborates it: the catalog's `66` exercise entries, the
`66` `AnimationRegistry` registrations and the `66` `PoseRegistry` configs match **one-to-one with no
unregistered id on either side**, and every id's builder is a `com.monkfitness.app.poses.*` engine pose.

**Unrelated poses unchanged — measured, on both trees.** The batch's own per-pose digest probe (the guards' own
hashing recipe: a fresh `SkeletonPipeline` per pose, the production entry point, `progress ∈ {0, ¼, ½, ¾, 1}`,
every joint, `hash * 31 + floatToIntBits`, one line per class) run in a pristine `origin/main` worktree
(`b0e4edd`) and on this branch:

| base | pre-existing pose classes compared | differing | added | removed |
|---|---|---|---|---|
| `b0e4edd` | 65 | **0** | 3 (`ShoulderCarsPose`, `JumpingJacksPose`, `ChildPose`) | 0 |

So all eleven guard REDs were **corpus membership, not geometry drift**: the eight `UNAFFECTED_CORPUS_DIGEST`
guards' corpora are "every production pose class except their own corrected pose", and the three new classes
join them. Each digest was re-baselined to its printed `measured=` value with the responsible change named at
the constant, and `ViewportFramingInvariantTest.CLIPPED_AT_HERO` was re-measured (`37 → 40`) — **no
camera/framing behaviour is changed by this batch.**

**Two further production censuses moved with the batch, and both were re-measured rather than relaxed:**

* `ExtremityArticulationTest` — `JumpingJacksPose` authors its feet through `buildAnkleArticulation` (the toes
  pointed through each flight, flat on each landing), so the registry-derived Branch-C carrier corpus gained
  exactly `jumping_jack_standard` (`13 → 14`), which also puts that pose under the carrier-vs-node equivalence
  guard.
* The `PublishedBelowGroundInvariantTest` (T2) pin table and `EnvironmentPenetrationTest` (B-6) needed **no**
  new entries, and B-6's *undeclaring* census is **unchanged** — all three poses declare their support on the
  one canonical channel (the first batch of the phase whose additions are all declaring), and every joint of
  every published frame clears its declared surface (worst clearances `25.000 u` / `23.987 u` / `8.999 u`, the
  last being `ChildPose`'s own head tip resting at the corpus's mat layer).

**No part of the engine, the solver, the reach band, the camera layer or the legacy illustration map was
touched by this batch:** the diff is three new pose classes, their three test classes, the two registries, the
coverage guard, the re-baselined guards and this record.

### The complete `66/66` inventory (the phase's final state)

Every catalog exercise, its animation id, the engine pose that now drives it, and the phase batch that
converted it (the 49 `pre-phase` rows were already engine-driven when the phase opened — its audit's baseline —
and the 17 batch rows are the exercises the phase converted, all listed in §1):

| # | exercise id | animationId | pose class | phase batch |
|---|---|---|---|---|
| 1 | `ankle_mobility` | `ankle_mobility_standard` | `AnkleMobilityPose` | batch 1 |
| 2 | `calf_stretch` | `calf_stretch_hold` | `CalfStretchPose` | batch 1 |
| 3 | `horse_stance` | `horse_stance_hold` | `HorseStancePose` | batch 1 |
| 4 | `wall_sit` | `wall_sit_hold` | `WallSitPose` | batch 1 |
| 5 | `band_pull_aparts` | `band_pull_aparts_standard` | `BandPullApartPose` | batch 2 |
| 6 | `dips` | `dip_parallel_bar` | `DipsPose` | batch 2 |
| 7 | `rows` | `row_standard` | `RowsPose` | batch 2 |
| 8 | `y_t_raises` | `yt_raises_standard` | `YTRaisesPose` | batch 2 |
| 9 | `hip_circles` | `hip_circles_hold` | `HipCirclesPose` | batch 3 |
| 10 | `leg_swings` | `leg_swings_hold` | `LegSwingsPose` | batch 3 |
| 11 | `ninety_ninety_hips` | `ninety_ninety_hips` | `NinetyNinetyHipsPose` | batch 3 |
| 12 | `piriformis_stretch` | `piriformis_stretch_hold` | `PiriformisStretchPose` | batch 3 |
| 13 | `chin_tucks` | `chin_tuck_standard` | `ChinTuckPose` | batch 4 |
| 14 | `neck_circles` | `neck_circles_hold` | `NeckCirclesPose` | batch 4 |
| 15 | `child_pose` | `child_pose_hold` | `ChildPose` | batch 5 |
| 16 | `jumping_jacks` | `jumping_jack_standard` | `JumpingJacksPose` | batch 5 |
| 17 | `shoulder_cars` | `shoulder_cars_standard` | `ShoulderCarsPose` | batch 5 |
| 18 | `arm_circles` | `arm_circles_hold` | `ArmCirclesPose` | pre-phase |
| 19 | `bird_dog` | `birddog_hold` | `StaticBirdDogHoldPose` | pre-phase |
| 20 | `bird_dog_reps` | `birddog_reps` | `AlternatingBirdDogPose` | pre-phase |
| 21 | `burpees` | `burpee_standard` | `BurpeePose` | pre-phase |
| 22 | `cat_cow` | `cat_cow_reps` | `CatCowPose` | pre-phase |
| 23 | `cobra_stretch` | `cobra_stretch_hold` | `ProneCobraStretchPose` | pre-phase |
| 24 | `cossack_squat` | `cossack_squat` | `CossackSquatPose` | pre-phase |
| 25 | `couch_stretch` | `couch_stretch_hold` | `CouchStretchPose` | pre-phase |
| 26 | `dead_bug` | `dead_bug_standard` | `DeadBugPose` | pre-phase |
| 27 | `decline_pushups` | `pushup_decline` | `DeclinePushUpPose` | pre-phase |
| 28 | `deep_squat` | `deep_squat_hold` | `DeepSquatHoldPose` | pre-phase |
| 29 | `diamond_pushups` | `pushup_diamond` | `DiamondPushUpPose` | pre-phase |
| 30 | `face_pull` | `face_pull_banded` | `FacePullPose` | pre-phase |
| 31 | `glute_bridge` | `glute_bridge_standard` | `GluteBridgePose` | pre-phase |
| 32 | `hamstring_stretch` | `hamstring_stretch_hold` | `HamstringStretchPose` | pre-phase |
| 33 | `hang` | `dead_hang` | `HangPose` | pre-phase |
| 34 | `hip_cars` | `hip_cars_standard` | `HipCarsPose` | pre-phase |
| 35 | `hip_flexor_stretch` | `hip_flexor_stretch_hold` | `HalfKneelingStretchPose` | pre-phase |
| 36 | `kettlebell_swing` | `kb_swing_backpack` | `KettlebellSwingPose` | pre-phase |
| 37 | `lat_stretch` | `lat_stretch_hold` | `LatStretchPose` | pre-phase |
| 38 | `leg_raises` | `leg_raise_standard` | `LegRaisePose` | pre-phase |
| 39 | `lunges` | `lunge_forward` | `AlternatingForwardLungesPose` | pre-phase |
| 40 | `lunges_reverse` | `lunge_reverse` | `AlternatingReverseLungesPose` | pre-phase |
| 41 | `lunges_side` | `lunge_side` | `AlternatingSideLungesPose` | pre-phase |
| 42 | `mountain_climbers` | `mountain_climber_standard` | `MountainClimberPose` | pre-phase |
| 43 | `pelvic_tilt` | `pelvic_tilt_standard` | `PelvicTiltPose` | pre-phase |
| 44 | `pike_pushups` | `pike_pushup_standard` | `PikePushUpPose` | pre-phase |
| 45 | `plank` | `plank_standard` | `StaticForearmPlankPose` | pre-phase |
| 46 | `pullups` | `pullup_standard` | `StandardPullUpPose` | pre-phase |
| 47 | `pullups_chin` | `chinup_standard` | `UnderhandChinUpPose` | pre-phase |
| 48 | `pullups_neutral` | `pullup_neutral` | `NeutralGripPullUpPose` | pre-phase |
| 49 | `pullups_wide` | `pullup_wide` | `WideGripPullUpPose` | pre-phase |
| 50 | `pushups` | `pushup_standard` | `StandardPushUpPose` | pre-phase |
| 51 | `pushups_knee` | `pushup_knee` | `KneePushUpPose` | pre-phase |
| 52 | `pushups_military` | `pushup_military` | `MilitaryPushUpPose` | pre-phase |
| 53 | `pushups_wide` | `pushup_wide` | `WidePushUpPose` | pre-phase |
| 54 | `reverse_snow_angels` | `reverse_snow_angel_prone` | `ReverseSnowAngelPose` | pre-phase |
| 55 | `scapular_pullups` | `scapular_pullup_deadhang` | `ScapularPullUpPose` | pre-phase |
| 56 | `scapular_retraction_hold` | `scapular_retraction_hold` | `ScapularRetractionPose` | pre-phase |
| 57 | `side_plank` | `side_plank_standard` | `IsometricSidePlankPose` | pre-phase |
| 58 | `squats` | `squat_standard` | `AirSquatPose` | pre-phase |
| 59 | `squats_jump` | `squat_jump` | `JumpSquatPose` | pre-phase |
| 60 | `squats_sumo` | `squat_sumo` | `SumoSquatPose` | pre-phase |
| 61 | `step_ups` | `step_up_standard` | `StepUpPose` | pre-phase |
| 62 | `superman` | `superman_prone` | `SupermanPose` | pre-phase |
| 63 | `thoracic_extension` | `thoracic_extension_reps` | `ThoracicExtensionPose` | pre-phase |
| 64 | `thoracic_rotations` | `thoracic_rotations_reps` | `QuadrupedThoracicRotationsPose` | pre-phase |
| 65 | `wall_slides` | `wall_slide_standard` | `WallSlidesPose` | pre-phase |
| 66 | `world_greatest_stretch` | `world_greatest_stretch` | `DynamicWorldsGreatestStretchPose` | pre-phase |

**Phase completion.** `66` of `66` exercises take the engine path in `ExerciseHero`
(`Exercise.skeletonAnimation != null` **and** `PoseRegistry.getPoseConfig(animationId) != null`); the legacy
keyframe illustration is no longer any exercise's hero representation. The measured final tree is
`152` classes / `889` tests / `0F` / `0E` / `0S`, and the release compilation path is green.
