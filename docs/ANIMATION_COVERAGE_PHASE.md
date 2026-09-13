# Animation Coverage Phase — 49/66 → 66/66

**Status:** ACTIVE. Brings every catalog exercise from an animated *illustration* to a real skeletal
animation driven by the engine.

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
