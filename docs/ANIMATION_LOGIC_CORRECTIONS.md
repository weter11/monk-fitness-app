# Animation-Logic Corrections — batch 1 (spinal articulation)

**Status:** batch 1 landed. **Scope:** `CatCowPose`, `PelvicTiltPose`.
**Branch:** `fix/animation-logic-b1-spine-articulation`, off the coverage phase's merge `9cf4c32` (PR #262,
the `66/66` animation-coverage phase's tail).

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
| `flexion` (the pelvis→chest chord's world flexion, `+` = Cat) | `lerp(0.12, −0.0417, p)` | BPS §5/§9: "full flexion"/"full extension", comfortably — the corpus's own mobility amplitude scale |
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

Measured on the corrected tree (published frame):

| quantity | before | after |
|---|---|---|
| trunk chord's flexion, Cat end (p = 0) | `0.000°` (flat) | `+6.875°` (the chest end `14.3655` u above the pelvis) |
| trunk chord's flexion, Cow end (p = 1) | `−2.386°` (the chest `5.0043` u below the pelvis) | `−2.389°` (`5.0026` u below — the authored Cow end preserved) |
| the rep's chord swing | `2.386°` | `9.264°` (`0.1617` rad = `0.12 + 0.0417`, no added range) |
| `PELVIS`'s own tilt | `0.000°` (it carried the chord instead) | `+2.578°` posterior → `−2.578°` anterior |
| `LUMBAR`'s own articulation | `0.0000` rad (bit-identical to the pelvis) | `−9.454°` → `+4.968°` |
| `CHEST`'s own articulation | `0.0000` rad | `−1.719°` → `+0.597°` |
| `CHEST`/`SHOULDER_*` y travel | `9.9957` u | `24.3680` u |
| `NECK_END` y travel | `10.3412` u | `23.6840` u |
| hands | `y = 0.0043` (float residue), under the shoulders | `y = 0.0000`, under the shoulders, `maxIkClampAmount = 0.0` |
| the planted leg chain | — | `≤ 3.052e-05` u different (the bake's parent-frame re-association; the realized world is preserved) |
| the pose's worst published `y` (the T2 pin) | `HEEL_F = −2.4224029` | `HEEL_F = −2.4224029` (identical — the planted base is the pose's own) |

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
  `p=0.0 the LUMBAR's own articulation is 0.0000 rad (0.000°), the authored wave requires -0.1650 (-9.454°)`;
* `CatCowSpineWaveTest.theTrunkChordSweepsTheAuthoredCatAndCowArc` —
  `p=0.0 chord flexion measured -0.0000 rad (-0.000°), authored 0.1200`;
* `CatCowSpineWaveTest.theFlexionPropagatesThroughTheLumbarAndThoracicSegments` —
  `measured pelvis 2.386° vs lumbar 0.000°, chord 2.386°`;
* `CatCowSpineWaveTest.theAuthoredCyclePhaseTimingAndGazeArePreserved` —
  `the rep must START at the Cat extreme … (measured -0.0000)`;
* `PelvicTiltSpineArticulationTest.theTiltIsCarriedByTheLowBackAndNotByThePelvisAlone` —
  `p=0.1 the LUMBAR's own articulation is 0.0000 rad (0.000°); the authored low-back share of the arc is -0.0078 (-0.447°)`;
* `PelvicTiltSpineArticulationTest.thePelvisKeepsAFewDegreesAndTheLowBackCarriesTheLordosisRange` —
  `BPS §9: the pelvis's own rotation is 'only a few degrees' — measured 6.875°`.

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

* `CatCowPose` (`155` rows): the intended wave — `CHEST`/`SHOULDER_A`/`SHOULDER_P`/`CLAVICLE_A`/
  `CLAVICLE_P`/`SCAPULA_A`/`SCAPULA_P` (`14.3655` u at the Cat end), `NECK_END` (`13.2532`),
  `HEAD_POS` (`12.1410`), the elbows (`10.4731`), the derived hand chain (`0.8630` — the shoulder's own
  X shift) — and the planted leg chain by `≤ 3.052e-05` u (float re-association).
* `PelvicTiltPose` (`28` rows): **only** the leg chain (`HIP_*`/`KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`),
  `≤ 1.526e-05` u, at the phases where the arc is non-zero. Its trunk, shoulders, arms and head are
  **byte-identical**, so the split is provably a re-attribution and not a motion change.

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

**One assertion was re-pointed, not relaxed.** `CanonicalSkeletonFactoryPoseBatchTest.everyMigratedPose
PublishesTheCanonicalHierarchy` asserted that *every* migrated pose's `LUMBAR` carries the `PELVIS`'s
own rotation (`< 1e-5`) — the factory's pass-through default. `PelvicTiltPose` is the first member of
that batch to **author** the lower spine, which is Issue E's entire purpose ("authoring a lumbar
rotation gives the lower spine independent DOF"). The pass-through assertion is now scoped to the
members that leave the node to the factory (`authorsItsLumbar = {PelvicTiltPose}` excluded, measured
divergence `0.0780` rad there), and the excluded pose gets its own anti-vacuity witness: its divergence
must BE the authored articulation (`worstAuthoredLumbar > 0.01`). No tolerance was loosened.

## 6. Verification

| run | tree | result |
|---|---|---|
| baseline | pristine `origin/main` @ `9cf4c32` (fresh worktree `/tmp/al-b1-base`, `--rerun-tasks`) | `152` classes / `889` tests / `0F` / `0E` / `0S` |
| focused, fresh | branch | `CatCowSpineWaveTest` 7, `PelvicTiltSpineArticulationTest` 6 — all green |
| full, fresh (`--rerun-tasks`, results purged, throwaway probes moved out of the tree) | branch | `154` / `902` / `0F` / `0E` / `0S` = **exactly +2 classes / +13 tests** (the two new test classes), no collateral |
| release compilation | branch | `:app:compileReleaseKotlin` + `:app:compileReleaseJavaWithJavac` + `:app:assembleDebug` **SUCCESS** (`app-debug.apk`, `11259769` bytes; md5 differs from the same-sized base-tree APK, so the build is fresh: `a20f5be4…` vs `cbec513c…`). `:app:assembleRelease` stops at `:app:lintVitalRelease` — the repository's documented pre-existing pair (`themes.xml` `ResourceCycle`, `ExpiredTargetSdkVersion`), untouched by this batch |

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
  unchanged: the Cat end's extra `14.4` u of rise keeps `CatCowPose` inside the hero canvas as authored
  (no camera or framing behaviour is touched by this batch, and none needed to move).

### 6.1 Close-out re-measurement (independent of the batch's own gates)

The whole verification above was **re-run from scratch at batch close-out** — pristine `origin/main`
worktree vs this branch, `--rerun-tasks`, results directories purged — and reproduced identically
(`152 / 889` base, `154 / 902 / 0F / 0E / 0S` branch; focused gates RED `6`/`13` on the untouched tree
with the same six messages; corpus A/B `183` differing rows, `155` + `28`, the other `66` classes
byte-identical; leg-chain worst `3.052e-05` u (`CatCowPose`) and `1.526e-05` u (`PelvicTiltPose`)).

Two quantities the batch's gates do not carry were measured separately (throwaway probes, both trees,
`SkeletonPipeline.produceFrame`), because they are what "the same exercise" means here:

| quantity (published frame) | before | after |
|---|---|---|
| `CatCowPose` — the three segments' own articulations at the Cat end | `PELVIS 0.000°`, `LUMBAR 0.000°`, `CHEST 0.000°` (rigid) | `PELVIS +2.578°`, `LUMBAR −9.454°`, `CHEST −1.719°` |
| `CatCowPose` — the same at the Cow end | `PELVIS +2.386°`, `LUMBAR 0.000°`, `CHEST 0.000°` | `PELVIS −2.578°`, `LUMBAR +4.968°`, `CHEST +0.597°` |
| `CatCowPose` — segment swings (the distribution) | pelvis `2.386°` / lumbar `0.000°` / chest `0.000°` | pelvis `5.157°` / lumbar `14.421°` / chest `2.316°` |
| `CatCowPose` — elbow's interior angle (Cat end → Cow end) | `47.34° → 38.57°` (arm span `60.00 → 50.00` u of a `146` u maximum reach) | `60.34° → 38.57°` (span `74.37 → 50.00` u of `146`) |
| `CatCowPose` — the planted foot's lowest y (absolute) | `2.5776` (Cat) … `−2.422398` (Cow) | `2.5776` (Cat) … `−2.422403` (Cow) — the T2 residual, preserved |
| `PelvicTiltPose` — the segments' own articulations at `p = 1` | `PELVIS −6.875°`, `LUMBAR 0.000°` | `PELVIS −2.406°`, `LUMBAR −4.469°` (sum `−6.875°`, unchanged) |
| `PelvicTiltPose` — trunk/arm/head positions | — | identical to `%.9f` at every sampled phase |

The arm measurement is the "still the same exercise" witness for the Cat end's `14.4` u rise: the
quadruped's arm is *near its folded* end in the pre-correction layout (span `60` u of `146`, elbow
`47°`) and the correction moves it to span `74` u / elbow `60°` at the Cat end — further from full
extension, not toward it, with `maxIkClampAmount = 0.0000` at every phase on both trees (no solver
relocation, and the Cow end's arm is bit-unchanged). The pelvis's own placement (`(50, lerp(45, 40, p)`
`+ ankleHeight)`), the hands' mat contact (`y = 1.5e-05` max) and the lumbar node's pass-through offset
(`0.000000000` u from the pelvis at every phase) are identical on both trees.

The release/debug compilation claim was re-verified in the same close-out run: `:app:assembleDebug` +
`:app:compileReleaseKotlin` **SUCCESS** in one `--rerun-tasks` invocation alongside the suite (`62`
tasks executed), producing the same `11259769`-byte `app-debug.apk` (md5 `2ba337a7…` on that run — APK
md5s are not reproducible across builds; the byte-identical *size* plus the executed-task stamp are the
freshness evidence, not the hash). `:app:lintVitalRelease` remains the repository's documented
pre-existing failure and is untouched.

## 7. Recorded, not resolved (no invented behaviour)

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
