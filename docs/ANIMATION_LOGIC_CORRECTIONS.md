# Animation-Logic Corrections — batch 1 (spinal articulation)

**Status:** batch 1 landed. **Scope:** `CatCowPose`, `PelvicTiltPose`.
**Branch:** `fix/animation-logic-b1-spine-articulation`, off the coverage phase's merge `9cf4c32` (PR #262,
the `66/66` animation-coverage phase's tail).
**Amplitude decision (owner, final):** `CatCowPose`'s Cat-side flexion is the **strict pre-fix motion
envelope** — `CAT_FLEXION = asin(5/120) ≈ 0.0417` rad, the pre-fix Cow end's own `5` u sag mirrored at the
Cat end. The first cut's `0.12` rad (`14.36` u) was rejected: this batch corrects the anatomical
*ownership* of the existing motion, it does not increase the exercise's range.

## 0. What this phase is (and why it follows the coverage phase)

The animation-coverage phase ended with **every catalog exercise driven by the engine** (`66/66`:
`Exercise.skeletonAnimation != null` **and** `PoseRegistry.getPoseConfig(animationId) != null`). That
phase's metric was *representation* — an exercise is either animated by the engine or it is not. It
deliberately does **not** measure whether the engine-driven motion *is* the exercise.

This phase is the second, orthogonal dimension: **the animation logic**. A pose can be fully
engine-driven, biomechanically valid, and still animate the wrong thing — the validator cannot see it
(`monk-pose-qa`: *"a pose can be biomechanically valid but visually static"*, and the dual case: a
pose can be fully animate and still be the wrong movement). The correction is pose-side authoring
only: no engine, solver, carrier, RFC, golden or camera change, and the corrected exercise is never
redesigned.

**The batch's method (repeated for every pose in the series, this is the contract):**

1. reproduce the published motion through `SkeletonPipeline.produceFrame` — the production entry
   point, sampled densely, every frame captured **by value** (the pipeline publishes a reused buffer);
2. establish the baseline measurements and identify the AUTHORED source of the motion (the node the
   articulation is written on, and what the published joints do with it);
3. write the focused motion tests **first** and prove them RED on the untouched tree, with the
   measured numbers quoted;
4. implement the smallest pose-side authoring change;
5. re-run the focused gates GREEN, the named regressions, the full suite, and the whole-corpus A/B;
6. re-baseline every guard whose scope digest contains the corrected poses, naming the responsible
   change at each constant;
7. record the measured final values here and in `docs/STABILIZATION_AUDIT.md` (the live tracker).

## 1. The audit — what was measured before any production edit

Both measurements are on `origin/main` @ `9cf4c32`, through
`SkeletonPipeline.produceFrame(pose, ctx)` at `progress ∈ {0, ⅛, …, 1}`, all joints read from the
published frame.

| pose | published motion | authored articulation | the defect |
|---|---|---|---|
| `CatCowPose` | the trunk is a **rigid** body: `PELVIS` y `60.0000 → 55.0000` and `CHEST` y `60.0000 → 50.0043` (a `5` u root bob and a `10` u chest drop), while the trunk chord's own flexion sweeps `0.000° → −2.386°` — **`2.386°` for the whole rep**; `CHEST`'s y travel is `9.9957` u and `HEAD_POS`'s `33.0844` u, almost all of it the authored gaze sweep | the entire motion is one `declarePelvisTilt(…, spineTilt)` on the **PELVIS** derived from the authored pelvis/chest heights (`spineTilt = atan2(torsoLength, dy)`, `dy = chestPos − pelvisPos ∈ [0, −5]`). The canonical two-segment spine publishes **no articulation at all**: `LUMBAR`'s world rotation is bit-identical to the `PELVIS`'s at every sampled phase (`0.0000` rad apart) and `CHEST.localRotation ≡ 0` | the exercise IS spinal flexion/extension (BPS `Cat-Cow (Reps)` §1/§5: *"it mobilizes the entire vertebral column, particularly the thoracic and lumbar regions"*, *"the motion is a sequential wave from the pelvis/coccyx through the lumbar, thoracic, and cervical segments"*). A rigid trunk cannot represent it, and `9` of the `33` canonical joints carry none of the motion |
| `PelvicTiltPose` | the rigid trunk chain swings `14.3655` u at `CHEST`/`SHOULDER_*`, `16.5203` u at `NECK_END`/`HEAD_POS` (the B4-corrected arc, out of the mat); the pelvis is static at its resting layer; the legs and the arms are quiet | the whole `0.12`-rad arc is one `declarePelvisTilt(…, 1.5708 − angleOffset)` on the **PELVIS**; `LUMBAR`'s world rotation is bit-identical to the `PELVIS`'s at every phase | BPS `Pelvic Tilt (Standard)` §9: *"Pelvic rotation: posterior tilt to anterior tilt, **a small arc (often only a few degrees of true pelvic rotation, with the lumbar spine moving through its lordosis range)**"* and §5 *"the motion is concentrated at the lumbopelvic junction"*. The drill's articulation is the LUMBAR's; the pose put the whole arc on the root (the M3/M4 "articulation authored on the root" class the prone family was corrected for) |

Both poses are `PoseBuilder`-direct implementations, so neither can call the base poses'
`buildSpineCurve`; the equivalent pose-side authoring (node writes **plus** the ONE intent channel:
`IntentBuilder.spine(...)` + `joint(LUMBAR)`) is what both corrections use — see `ThoracicExtensionPose`
for the canonical shape.

## 2. The corrections (pose-side authoring only)

### 2.1 `CatCowPose` — the wave is distributed over the canonical spine

The rep stays `Cat (p = 0) → Cow (p = 1)`, `LOOP`, `SINE`, `4.0 s`, sagittal only (about the pose's own
lateral Z axis). The authored motion is now the exercise's own wave, carried by three articulations:

| quantity | value | source |
|---|---|---|
| `flexion` (the pelvis→chest chord's world flexion, `+` = Cat) | `lerp(A, −0.0417, p)`, `A = asin(5/120) ≈ 0.0417` | the **strict pre-fix motion envelope** (owner decision): the pre-fix rep's own largest trunk angle was the Cow end's `5` u sag, and the Cat end mirrors that same `5` u as a rise — `torsoLength·sin(A) = 5.00` u at each end, so the chord's total excursion is the pre-fix `10.00` u of chest travel re-expressed as curvature |
| the **PELVIS**'s own pelvic tilt | `lerp(+0.045, −0.045, p)` (posterior in Cat, anterior in Cow) | BPS §3/§7/§9: *"the pelvis posteriorly tilts (tail tuck)"* / *"anteriorly tilts (tail lifts)"* — **reversing** with the wave, and only the share the exercise justifies |
| the **LUMBAR**'s own articulation | `−flexion − pelvicTilt` | the remainder: the visible flexion/extension propagates through the lower spine (BPS §5: "the motion is a sequential wave … through the lumbar") |
| the **CHEST**'s own articulation (thoracic) | `−0.25 · flexion` | the thoracic segment's share; the neck/head chain follows it through FK (BPS §4/§11: "Neck follows the spine") |

The pelvis's **placement** schedule (`lerp(45, 40, p) + ankleHeight`, X `= 50`) is *unchanged*: it is
what the leg chain's planted targets, the pose's floor relationship and the four-point base resolve
against. The leg targets/poles, the declared four-point base, the gaze sweep and the cycle/phase
timing are unchanged too. The only other authoring change is the arm target's form: the hands are
planted flat on the floor **directly under their shoulders** (BPS §6/§8/§11) as an explicit mat-level
target — the previous `shoulder.y − chestPos` expression was only equal to the floor in the rigid-trunk
layout it was written for and would have lifted the hands off the mat as soon as the spine articulated.

Measured on the corrected tree (published frame, strict envelope — one instrument on both trees):

| quantity | before | after |
|---|---|---|
| trunk chord's flexion, Cat end (p = 0) | `0.000°` (flat) | `+2.388°` (the chest end `5.0000` u above the pelvis) |
| trunk chord's flexion, Cow end (p = 1) | `−2.386°` (the chest `4.9957` u below the pelvis) | `−2.389°` (`5.0026` u below — the authored Cow end preserved, `0.0033°` / `0.0069` u apart) |
| the rep's chord swing | `2.386°` | `4.777°` (`0.083379` rad = `0.0416787 + 0.0417`) |
| `PELVIS`'s own tilt | `0.000°` (it carried the chord instead) | `+2.578°` posterior → `−2.578°` anterior |
| `LUMBAR`'s own articulation | `0.0000` rad (bit-identical to the pelvis) | `−4.966°` → `+4.968°` (swing `9.934°` — the largest of the three) |
| `CHEST`'s own articulation | `0.0000` rad | `−0.597°` → `+0.597°` (swing `1.194°`) |
| the chest's travel **over the pelvis** (the chord's excursion) | `4.9957` u | `10.0026` u — the pre-fix rep's own chest excursion (`9.9957` u), re-expressed as curvature |
| `CHEST`/`SHOULDER_*` absolute y travel | `9.9957` u | `15.0026` u (the authored `5` u pelvis descent + the now-symmetric chord: `travel = 5 + 2·120·sin A`) |
| `NECK_END` / `HEAD_POS` absolute y travel | `10.3412` / `11.1106` u | `15.0028` / `15.0030` u |
| hands | `y = 0.0043` (float residue), under the shoulders | `y ≤ 1.6e-05`, under the shoulders, `maxIkClampAmount = 0.0000` |
| the arm chain at the Cat end | span `60.0000` u of `146` max reach, elbow `47.34°` | span `65.0000` u, elbow `51.80°` (further from full extension, not toward it; the Cow end's arm is bit-unchanged) |
| the planted leg chain | — | `≤ 3.052e-05` u different (the bake's parent-frame re-association; the realized world is preserved) |
| the pose's worst published `y` (the T2 pin) | `HEEL_F = −2.422398` | `HEEL_F = −2.422403` (the planted base is the pose's own) |
| the Cat end's worst deviation from the pre-fix tree | — | **`5.0000` u** (at `CHEST`/`SHOULDER_*`/`CLAVICLE_*`/`SCAPULA_*`), then `NECK_END 4.5720`, `HEAD_POS 4.1441`, elbows `3.8103`, hands `0.1042` |

The last row is the envelope in one number: the Cat end's maximum departure from the pre-fix published
geometry is exactly the rep's own `5` u, because the pre-fix Cat end carried **no** chord curvature at
all (its chest sat level with the pelvis) — any Cat flexion moves the chest up, and this bounds it by the
figure the Cow end already used. In *angle* terms the Cat end stays inside the pre-fix rep's own extreme:
`2.3880°` vs the pre-fix maximum `2.3859°` (`+0.0021°`, `0.09 %`).

### 2.2 `PelvicTiltPose` — the same arc, carried by the pelvis **and** the low back

The authored total is unchanged (`torsoAngle = 1.5708 − 0.12·p`, `EASE_IN_OUT`, `3.0 s`, the pose's
static pelvis at its resting layer `14`, the B4-corrected direction out of the mat). It is now **split**:

* `PELVIS`: `1.5708 − 0.35 · 0.12 · p` — the pelvis's own few degrees (BPS §9);
* `LUMBAR`: `torsoAngle − pelvisRotation` — the remaining **65 %** of the arc, the low back's lordosis
  range (BPS §5/§9/§10).

`pelvisRotation + lumbarRotation == torsoAngle` at every phase by construction, so the split adds **no
range**: the published trunk chain, the rep's `0.12`-rad world arc (the B4 gate's own quantity), the
pelvis's static base, the legs' quietness and the floor/contact behaviour are the pose's own.

Measured on the corrected tree (published frame): the trunk, arms, hands and head are **byte-identical**
to the pre-correction tree at every sampled phase (the split is exactly geometry-neutral there); the
leg chain differs by `≤ 1.526e-05` u (the limb bake's parent-frame re-association — the realized world
is preserved). The articulation moved:

| quantity | before | after |
|---|---|---|
| `PELVIS`'s own tilt at p = 1 | `6.875°` (the whole arc) | `2.406°` |
| `LUMBAR`'s own articulation at p = 1 | `0.0000` rad | `−4.469°` (65 % of the arc) |
| the trunk chord's world arc | `0.12` rad | `0.12` rad (unchanged) |
| published trunk/arm/head positions | — | byte-identical |

## 3. RED-before evidence (fresh runs; results directory purged)

`CatCowSpineWaveTest` (7 tests) and `PelvicTiltSpineArticulationTest` (6 tests) were written first and
run against the untouched `origin/main` production bytes (the two pose files restored from `HEAD`,
fingerprinted and restored with `md5sum -c` afterwards). **6 of the 13 tests RED, every message quoting
the number it measured:**

* `CatCowSpineWaveTest.theSpinalWaveIsCarriedByTheCanonicalSpineSegmentsNotByThePelvis` —
  `p=0.0 the LUMBAR's own articulation is 0.0000 rad (0.000°), the authored wave requires -0.0867 (-4.966°)`;
* `CatCowSpineWaveTest.theTrunkChordSweepsTheAuthoredCatAndCowArc` —
  `p=0.0 chord flexion measured -0.0000 rad (-0.000°), authored 0.0417`;
* `CatCowSpineWaveTest.theFlexionPropagatesThroughTheLumbarAndThoracicSegments` —
  `measured pelvis 2.386° vs lumbar 0.000°, chord 2.386°`;
* `CatCowSpineWaveTest.theAuthoredCyclePhaseTimingAndGazeArePreserved` —
  `the rep must START at the Cat extreme — the authored chord flexion torsoLength·sin(0.041678734) at p = 0 (measured -0.0000)`;
* `PelvicTiltSpineArticulationTest.theTiltIsCarriedByTheLowBackAndNotByThePelvisAlone` —
  `p=0.1 the LUMBAR's own articulation is 0.0000 rad (0.000°); the authored low-back share of the arc is -0.0078 (-0.447°)`;
* `PelvicTiltSpineArticulationTest.thePelvisKeepsAFewDegreesAndTheLowBackCarriesTheLordosisRange` —
  `BPS §9: the pelvis's own rotation is 'only a few degrees' — measured 6.875°`.

Re-run at the envelope landing (`asin(5/120)`, i.e. the amplitude the batch ships with), against the same
pristine production bytes: **the same `6` of `13` RED**, with the same six gates — the first three messages
now quoting the envelope's own numbers (`-0.0867 (-4.966°)`, `authored 0.0417`), the `PelvicTiltPose` pair
unchanged to the digit (that pose's amplitude is not this decision's subject).

The other **7 tests are guards that must NOT change** and were GREEN in both runs: the four-point base
and the planted hand/leg geometry, the sagittal-only + bone-length invariants, the frame-condition
(cold vs playing) invariance, the sum property (`pelvis + lumbar == the authored total`), the
split-authorship model with the rigid-lumbar authoring as its control, the pose's placement/floor
behaviour and the neck's authored counter-articulation.

## 4. Blast radius — the whole-corpus A/B (measured, not asserted)

A throwaway probe (the guards' own recipe: every discovered production pose class, a fresh pipeline
each, `progress ∈ {0, ¼, ½, ¾, 1}`, every `Joint.entries` XYZ, `%.9f`) was run with `--rerun-tasks` in
a pristine `origin/main` worktree (`/tmp/al-b1-base` @ `9cf4c32`) and on this branch, dumping `11,220`
rows (`68` classes × `5` samples × `33` joints):

| tree | rows | differing rows | where |
|---|---|---|---|
| pristine `9cf4c32` vs branch | `11,220` | **`183`** | `155` in `CatCowPose`, `28` in `PelvicTiltPose`, **`0` in the other `66` classes** |

* `CatCowPose` (`155` rows): the intended wave, and — under the strict envelope — bounded by exactly the
  rep's own `5` u: `CHEST`/`SHOULDER_A`/`SHOULDER_P`/`CLAVICLE_A`/`CLAVICLE_P`/`SCAPULA_A`/`SCAPULA_P`
  (**`5.0000`** u worst, at the Cat end), `NECK_END` (`4.5720`), `HEAD_POS` (`4.1441`), the elbows
  (`3.8103`), the derived hand chain (`0.1042` — the shoulder's own X shift) — and the planted leg chain
  by `≤ 3.052e-05` u (float re-association; the leg authoring and its parent frame are untouched, so the
  figure is identical to the first cut's). The first cut's amplitude showed the same row set with a
  `14.3655` u worst; the envelope landing shrinks every CatCow row's magnitude and moves no row into or
  out of the census.
* `PelvicTiltPose` (`28` rows): **only** the leg chain (`HIP_*`/`KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`),
  `≤ 1.526e-05` u, at the phases where the arc is non-zero. Its trunk, shoulders, arms and head are
  **byte-identical**, so the split is provably a re-attribution and not a motion change — and the census
  is *identical* under both amplitudes, which is the measured proof that the envelope decision is
  `CatCowPose`-only.

## 5. Guards re-baselined (the corrected poses sit inside every corpus)

The batch contains no new pose class, so no guard moved by corpus **membership**; the constants moved
because the two corrected poses' published geometry did. Every one was re-measured from the live run in
one pass (`--rerun-tasks`, results purged), and each carries this batch's attribution at the constant:

| guard | previous | re-baselined |
|---|---|---|
| `CanonicalSkeletonFactoryPoseBatchTest.BATCH_SCOPE_DIGEST` | `5967077127684194150` | `-4873621514200274479` |
| `M1StepUpGeometryTest.UNAFFECTED_CORPUS_DIGEST` | `-6703469261057718571` | `-5730594616648658542` |
| `M3M5ProneTrunkGeometryTest.UNAFFECTED_CORPUS_DIGEST` | `8336975858828664756` | `4757165752247531121` |
| `M6M7SwingBurpeeGeometryTest.UNAFFECTED_CORPUS_DIGEST` | `8612073247363041346` | `-2353350476525624577` |
| `M8M9M10SupportDeclarationTest.UNAFFECTED_CORPUS_DIGEST` | `-8517828860436522591` | `7444094737458348374` |
| `M11M12LimbRealizationMigrationTest.UNAFFECTED_CORPUS_DIGEST` | `5316475324191770694` | `-5902184305628532914` |
| `M15WallSlidesWallGeometryTest.UNAFFECTED_CORPUS_DIGEST` | `2625259318375822676` | `4433733618875705328` |
| `HamstringForwardReachTest.UNAFFECTED_CORPUS_DIGEST` | `-7729117442052532845` | `-247797092231647152` |
| `PlankForearmSupportGeometryTest.UNAFFECTED_CORPUS_DIGEST` | `-1229470257816681828` | `-1474130064244243623` |

Note the `M11M12` guard's corpus **excludes** `CatCowPose` (it is one of that guard's own corrected
poses), so only the `PelvicTiltPose` half applies there and only because of the `1.5e-05`-scale float
re-association — a row-level `CHEST`-identical, `1.5e-05`-max diff is enough to move a raw-float-bits
hash, which is exactly what the A/B census says.

**The envelope landing moved seven of the nine again, and left two untouched — which is a result.** The
owner-decided amplitude change is `CatCowPose`-only (`PelvicTiltPose`'s published geometry is *identical*
under either amplitude), so the two guards whose corpora do not contain `CatCowPose` did **not** move:

| guard | corpus contains | first cut | after the envelope landing |
|---|---|---|---|
| `HamstringForwardReachTest` | `CatCowPose` | `-247797092231647152` | **`-2851489873960465040`** |
| `M15WallSlidesWallGeometryTest` | `CatCowPose` | `4433733618875705328` | **`-5018246371389958240`** |
| `M1StepUpGeometryTest` | `CatCowPose` | `-5730594616648658542` | **`-8334287398377476430`** |
| `M3M5ProneTrunkGeometryTest` | `CatCowPose` | `4757165752247531121` | **`-1109423721946295407`** |
| `M6M7SwingBurpeeGeometryTest` | `CatCowPose` | `-2353350476525624577` | **`-4957043258254442465`** |
| `M8M9M10SupportDeclarationTest` | `CatCowPose` | `7444094737458348374` | **`-6591002213556560266`** |
| `PlankForearmSupportGeometryTest` | `CatCowPose` | `-1474130064244243623` | **`-1362045000586141575`** |
| `M11M12LimbRealizationMigrationTest` | `PelvicTiltPose` only | `-5902184305628532914` | `-5902184305628532914` (unchanged) |
| `CanonicalSkeletonFactoryPoseBatchTest` | `PelvicTiltPose` only | `-4873621514200274479` | `-4873621514200274479` (unchanged) |

Each of the seven carries the re-baseline paragraph in its own KDoc, naming the owner decision, both
amplitudes and the pre-rebaseline measurement. The two unchanged digests are the measured proof that the
amplitude decision did not leak into `PelvicTiltPose` (and that the `PelvicTiltPose` half of the guard
corpora is amplitude-independent by construction — its split sums to the same authored total).

**One assertion was re-pointed, not relaxed.** `CanonicalSkeletonFactoryPoseBatchTest.everyMigratedPose
PublishesTheCanonicalHierarchy` asserted that *every* migrated pose's `LUMBAR` carries the `PELVIS`'s
own rotation (`< 1e-5`) — the factory's pass-through default. `PelvicTiltPose` is the first member of
that batch to **author** the lower spine, which is Issue E's entire purpose ("authoring a lumbar
rotation gives the lower spine independent DOF"). The pass-through assertion is now scoped to the
members that leave the node to the factory (`authorsItsLumbar = {PelvicTiltPose}` excluded, measured
divergence `0.0780` rad there), and the excluded pose gets its own anti-vacuity witness: its divergence
must BE the authored articulation (`worstAuthoredLumbar > 0.01`). No tolerance was loosened.

**Two gate floors were re-pointed to the measurement band, not to an amplitude.** Under the strict
envelope the thoracic share is `0.25 · asin(5/120) = 0.0104` rad per end (total swing `0.0208` rad), so
`CatCowSpineWaveTest`'s two absolute floors — the Cat-end anti-vacuity (`> 4 × angleBand = 0.02` rad) and
the thoracic-swing floor (`> 0.02` rad), both calibrated to the first cut's `0.12` rad — would have sat at
or within ~4 % of the measured value. They are now the comparison band itself (`> angleBand`, i.e. `0.005`
rad, measured `0.0104` and `0.0208` — `2.1 ×` and `4.2 ×` the floor) and the thoracic-swing message now
quotes the authored share. The *primary* assertions — each segment's own articulation EQUALS its authored
share, and the three segments are pairwise different — are unchanged and are the real contract.

## 6. Verification

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `9cf4c32` (fresh worktree `/tmp/al-b1-base`, `--rerun-tasks`) | `152` classes / `889` tests / `0F` / `0E` / `0S` |
| focused, fresh | branch | `CatCowSpineWaveTest` 7, `PelvicTiltSpineArticulationTest` 6 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes moved out of the tree) | branch | `154` / `902` / `0F` / `0E` / `0S` = **exactly +2 classes / +13 tests** (the two new test classes), no collateral |
| release compilation | branch | `:app:assembleDebug` + `:app:compileReleaseKotlin` **SUCCESS** in one `--rerun-tasks` invocation alongside the suite (`62` tasks executed), `app-debug.apk` `11259769` bytes (md5 `cade3e39…` on the final run; the size is byte-stable across runs, the hash is not). `:app:assembleRelease` stops at `:app:lintVitalRelease` — the repository's documented pre-existing pair (`themes.xml` `ResourceCycle`, `ExpiredTargetSdkVersion`), untouched by this batch |

Acceptance-named regressions, all computed from the branch's own JUnit XML (`0F/0E/0S` each):

| regression | class | tests |
|---|---|---|
| B1 | `DiamondPushUpElbowClearanceTest` | 7 |
| B2 | `WorldsGreatestStretchBackKneePlaneTest` | 5 |
| B3 | `IsometricSidePlankKneePlaneTest` | 5 |
| B4 (elbow + trunk) | `SupineArmElbowPlaneTest` 6, `PelvicTiltTrunkPlaneTest` 7 | 13 |
| C1 + C2 (framing) | `ViewportFramingInvariantTest` 7, `ExerciseFramingInvariantTest` 7 | 14 |
| reach-band | `SquatReachBandAuthoringTest` 7, `ReachBandBatch2AuthoringTest` 6, `ReachBandBatch3AuthoringTest` 10, `ReachBandBatch4AuthoringTest` 12 | 35 |
| `66/66` coverage | `AnimationCoverageTest` 4, `ExerciseAnimationProfileTest` 2 | 6 |
| T2 (below-ground table) | `PublishedBelowGroundInvariantTest` | 9 |
| B-6 (declaration-keyed floor) | `EnvironmentPenetrationTest` | 9 |
| M12 (leg reach + base) | `M11M12LimbRealizationMigrationTest` | 5 |
| motion floor | `MobilityMotionTest` (CatCow floor `25`, PelvicTilt floor `12`) | 1 |
| posture/validator | `PostureUniversalityTest` 4, `PostureSessionExhaustiveSweepTest` 1, `ExerciseValidatorTest` 9 | 14 |

**Two things did NOT need re-baselining, and that is a result in itself:**

* `PublishedBelowGroundInvariantTest` (T2) keeps `CatCowPose`'s pinned below-ground feet
  (`HEEL_* −2.4224`, `ANKLE_* −2.1292`, `TOE_* −1.4113`) — the correction deliberately leaves the
  pelvis's authored placement and the leg authoring alone, so the pose's recorded residual (the M12
  record's clause (c)) is unchanged rather than silently "fixed" by a spine change;
* `ViewportFramingInvariantTest.CLIPPED_AT_HERO` and `ExerciseFramingInvariantTest.THE_EXCEPTIONS` are
  unchanged: the Cat end's rise (now `5.0` u, `14.4` u under the rejected first cut) keeps `CatCowPose`
  inside the hero canvas as authored (no camera or framing behaviour is touched by this batch, and none
  needed to move).

### 6.1 Close-out re-measurement (independent of the batch's own gates)

The whole verification above was **re-run from scratch at batch close-out, after the envelope landing** —
pristine `origin/main` worktree vs this branch, `--rerun-tasks`, results directories purged — and
reproduced identically (`152 / 889` base, `154 / 902 / 0F / 0E / 0S` branch; focused gates RED `6`/`13`
on the untouched tree with the same six gates; corpus A/B `183` differing rows, `155` + `28`, the other
`66` classes byte-identical; leg-chain worst `3.052e-05` u (`CatCowPose`) and `1.526e-05` u
(`PelvicTiltPose`)).

Quantities the batch's gates do not carry were measured separately (one throwaway instrument run on both
trees through `SkeletonPipeline.produceFrame`), because they are what "the same exercise" means here:

| quantity (published frame) | before | after (strict envelope) |
|---|---|---|
| `CatCowPose` — the three segments' own articulations at the Cat end | `PELVIS 0.000°`, `LUMBAR 0.000°`, `CHEST 0.000°` (rigid) | `PELVIS +2.578°`, `LUMBAR −4.966°`, `CHEST −0.597°` |
| `CatCowPose` — the same at the Cow end | `PELVIS +2.386°`, `LUMBAR 0.000°`, `CHEST 0.000°` | `PELVIS −2.578°`, `LUMBAR +4.968°`, `CHEST +0.597°` |
| `CatCowPose` — segment swings (the distribution) | pelvis `2.386°` / lumbar `0.000°` / chest `0.000°` | pelvis `5.157°` / lumbar `9.934°` / chest `1.194°` |
| `CatCowPose` — elbow's interior angle (Cat end → Cow end) | `47.34° → 38.57°` (arm span `60.0000 → 50.0000` u of a `146` u maximum reach) | `51.80° → 38.57°` (span `65.0000 → 49.9974` u of `146`) |
| `CatCowPose` — the planted foot's lowest y | `2.5776` (Cat) … `−2.422398` (Cow) | `2.5776` (Cat) … `−2.422403` (Cow) — the T2 residual, preserved |
| `CatCowPose` — the chest's travel over the pelvis / absolute | `4.9957` u / `9.9957` u | `10.0026` u / `15.0026` u |
| `PelvicTiltPose` — the segments' own articulations at `p = 1` | `PELVIS −6.875°`, `LUMBAR 0.000°` | `PELVIS −2.406°`, `LUMBAR −4.469°` (sum `−6.875°`, unchanged) |
| `PelvicTiltPose` — trunk/arm/head positions, hand/floor behaviour | — | identical to `%.9f` at every sampled phase (`chest`, `neck end`, `head`, `hand`, elbow angle `70.63° → 71.31°`) |

The arm measurement is the "still the same exercise" witness for the Cat end's `5.0` u rise: the
quadruped's arm is *near its folded* end in the pre-correction layout (span `60.0000` u of `146`, elbow
`47.34°`) and the correction moves it to span `65.0000` u / elbow `51.80°` at the Cat end — further from
full extension, not toward it, with `maxIkClampAmount = 0.0000` at every phase on both trees (no solver
relocation, and the Cow end's arm is bit-unchanged). The pelvis's own placement (`(50, lerp(45, 40, p)`
`+ ankleHeight)`), the hands' mat contact (`y = 1.6e-05` max) and the lumbar node's pass-through offset
(`0.000000000` u from the pelvis at every phase) are identical on both trees.

The release/debug compilation claim was re-verified in the same close-out run: `:app:assembleDebug` +
`:app:compileReleaseKotlin` **SUCCESS** in one `--rerun-tasks` invocation alongside the suite (`62`
tasks executed), producing a `11259769`-byte `app-debug.apk` (APK md5s are not reproducible across
builds — the byte-stable *size* plus the executed-task stamp are the freshness evidence, not the hash).
`:app:lintVitalRelease` remains the repository's documented pre-existing failure and is untouched.

## 7. Recorded, not resolved (no invented behaviour)

* **`CatCowPose`'s Cat-side amplitude — DECIDED by the owner (not a residual).** The first cut authored
  `0.12` rad (`14.3655` u of chest rise, chord swing `2.386° → 9.264°`, chest travel `4.996 → 19.368` u)
  and was rejected: *"We are correcting the anatomical ownership of the existing motion, not increasing
  the exercise's ROM."* The batch now ships the **strict pre-fix envelope**, `CAT_FLEXION = asin(5/120)`
  — the same `5` u the pre-fix Cow end already sagged, mirrored at the Cat end. Recorded here because the
  arithmetic is worth carrying forward: with the pelvis's authored schedule left alone, the chest's
  **absolute** travel is `5 + 2·120·sin A` (= `15.00` u at this amplitude), so an absolute-travel figure
  of `10.0` u and a preserved Cow end (chest `5` u below the pelvis) cannot both hold while the Cat end
  carries any flexion at all — the pre-fix Cat end was dead flat, so the two readings of "chest travel"
  (`10.0026` u over the pelvis vs `15.0026` u absolute) are reported side by side in §2.1 rather than
  reconciled by moving the placement schedule, which the acceptance criteria forbid.
* **`CatCowPose`'s thoracic share (`0.25`) is smaller than the corpus's canonical `0.4`.** Left as the
  first cut authored it (this decision's subject was the amplitude, not the share). Measured consequence
  under the envelope: the `CHEST` carries `±0.597°` (swing `1.194°`) where the canonical share would give
  `±0.955°` (`1.909°`). It is a one-constant lever if the owner wants the thoracic segment to read more
  strongly at this amplitude — recorded, not changed.
* **`CatCowPose`'s leg geometry** (the audit's unassigned item): the authored pole `(-1, 0, ∓1)` splays
  the realized knee `69.3` u out of the hip line the BPS §7/§11 pins, and the reach-projected ankle
  sits up to `2.1292` u below the pose's own mat at the Cow end (the T2 pin). Both are recorded in the
  M11/M12 record and are **untouched** here: they are leg authoring, and this batch's subject is the
  spine.
* **The elbow's lateral bow** in the quadruped (the arms' pole `(0, 0, ∓1)` puts the elbow outboard of
  the shoulder line by ~`62` u because the arm chord is vertical). A shoulder-width quadruped arm is a
  plausible reading, but it is an arm-authoring decision (and the BPS's scapular half is explicitly out
  of scope for this batch: *"Do not touch scapular/clavicular logic"*).
* **`PelvicTiltPose`'s shown half is the arch half.** The B4 trunk record's "product reading" is
  unchanged by this batch: the drill's imprint (posterior) half is geometrically illegal at this pose's
  resting layer, and the corrected pose still shows the pelvis's superior axis tipping away from the
  mat. The *direction* is the B4-corrected one and was not re-decided here; whether the app should show
  a smaller-amplitude imprint-flavoured motion remains the owner's call.
* **The lumbar is a coincident node in this rig** (`PELVIS → LUMBAR → CHEST`, the lumbar's offset is
  zero by construction), so a lumbar articulation moves the whole thorax rather than a `L0–L5` segment.
  That is the canonical model (Issue E) and is what both corrections use; a lower-back *segment* with
  its own length would be an architecture change, out of this phase's scope.

## 8. Open items for the next batch (recorded, not started)

1. The audit's remaining unassigned CatCow items above (leg geometry; the quadruped arm plane).
2. The next poses the same class of defect is suspected in — to be established by an audit, not
   assumed: the tracker's `docs/STABILIZATION_AUDIT.md` §4 open list.
3. The `P2` cleanup item that touches both files (the duplicate `PELVIS` joint-intent line after
   `declarePelvisTilt`, the `WRIST_*` mirror lines) — deliberately left to the P2 pass, which owns it.
