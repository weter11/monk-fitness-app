# P11 — Whole-System Correctness Audit of the Assembled Runtime

**Status:** AUDIT COMPLETE (findings recorded, one P11 deliverable landed). **P11 is NOT complete.**
**Audited revision:** `main` @ `914a6f6` (P12 / PR #225 merge; second parent `3783e15`).
**Audit branch:** `p11-whole-system-audit`.
**Suite at audit time:** 105 classes / 462 tests / 0 failures / 0 errors / 0 skipped — forced fresh
(`--rerun-tasks`, results dir wiped, every XML stamped `2026-09-11T17:48:53Z`). Baseline before the
audit's only production-visible additions was 104 classes / 454 tests — i.e. the delta is exactly
+1 class / +8 tests and zero collateral.

**B-1 status (recorded after this audit was written).** B-1 is fixed and proposed in its own change,
**PR #227** / branch `fix/b1-knee-pushup-plank-geometry` @ `5c49aef`, on top of `origin/main` @ `914a6f6`.
That branch is 2 files (1 production KNEES branch + 1 new 7-test class) and carries **no** other P11 work:
B-2…B-7 stay open here. Fresh numbers on it: focused class 7/7 RED on the pre-fix source → 7/7 GREEN on the
fix; full suite 103 classes / 450 tests (`origin/main` source) → **104 / 457**, 0F/0E/0S, both runs forced
fresh on this host; the six FEET-pivot push-ups are byte-identical (996-line joint dump, sha256
`78a9ed40…7e7d8b` both sides).

**Method.** Every claim below is a measurement of a produced frame or a source-site census, never a
reading of prose. The corpus probe drove all 49 registered production animations (plus the 4
validation poses) through `SkeletonPipeline` and dumped 33 joints × 5 progress values, the derived
body basis, ground penetration, per-joint world Y, declared support channels and metadata.
Raw evidence: `/home/wer/devis/p11-audit/corpus-builder-path.tsv`, `p11-knee-trace.txt`,
`p11-support-trace.txt`.

---

## 1. What the repository says P11 is

`docs/IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md` §"Phase 11 — R11/R14: Carrier transfer-chain &
pipeline-lifetime compliance verification":

| plan item | status |
| --- | --- |
| `CarrierTransferComplianceTest` (a) published pose ≠ input carrier instance | **DONE** (8 tests, this audit) |
| (b) two renderers with separate pipelines produce independent frames | **DONE** |
| (c) External objects never appear in carrier copies | **DONE** (+ the §3.3 internal-section non-leakage check) |
| optional CI grep gate for forbidden patterns | **DONE** (`WeakHashMap<SkeletonPose`, `lastSolvedRoot`, `object/var sharedPipeline`), counterfactual RED proven |
| "compliant by construction" prose → executed evidence | **DONE** |

So P11's *own* declared deliverable is satisfied. P11 is nevertheless **not complete**, because the
plan's own definition of the phase ("Verifies the assembled whole — the whole includes the activated
configuration") is a whole-system claim, and the whole system is not correct: §3–§5 below record
confirmed defects the green suite does not see.

---

## 2. Confirmed production defects

### B-1 — Knee push-up: the declared floor contacts are not on the floor (Pose + dead solver output)

* **File / symbol:** `poses/KneePushUpPose.kt` → `poses/BasePushUpPose.kt` `onBuild`, KNEES branch
  (`kneeF!!.localPosition.set(-def.shinLength, 0f, 0f)`); `animation/PushUpPlank.kt` `solve` KNEES branch.
* **Symptom (measured, `pushup_knee`, published frames):**

  | progress | KNEE_F y | KNEE_B y | ANKLE_F y | PELVIS y | CHEST y | HAND_A y | \|shoulder→hand\| |
  |---|---|---|---|---|---|---|---|
  | 0.00 | 84.30 | 188.64 | 84.30 | 188.64 | 188.64 | 135.48 | 140.2 |
  | 0.25 | 84.30 | 210.97 | 84.30 | 187.62 | 162.61 | 154.11 | — |
  | 0.50 | 84.30 | **232.22** | 84.30 | 186.55 | 137.62 | 1.96 | 140.2 |
  | 0.75 | 84.30 | 208.79 | 84.30 | 185.44 | 160.42 | 31.60 | — |
  | 1.00 | 84.30 | 184.27 | 84.30 | 184.27 | 184.27 | 143.31 | — |

  Ground level = 0. Declared contacts = `LEFT_HAND, RIGHT_HAND, LEFT_KNEE, RIGHT_KNEE`; measured max
  deviation of a declared contact from its ground plane = **232.22 units**. The front knee — the
  exercise's *pivot and support* per BPS §7/§8/§13 — sits **84.30 units in the air and never moves**.
  The mirror leg is a rigid chain pointing up into space, reaching y = 330.22.
  Measured shin elevation off horizontal = **0.0°** while the code's own constant is
  `SHIN_PITCH_ANGLE = 45°`; measured femur elevation = 65.9°.
  `|shoulderA→handA|` is 140.2 of a 146.0 maximum at *both* p=0.0 and p=0.5 → **the elbow never
  flexes**, i.e. there is no press in the rep. Only the hands ever reach the floor, and only at
  mid-rep, because `SkeletonMath.clampTargetToReach` silently relocates the out-of-reach target.
* **Root cause, first incorrect point:** `PushUpPlank.solve(KNEES)` correctly computes
  `kneeHeight = BASE_KNEE_HEIGHT = 15`, `kneeX = 164.6`, `pelvisHeight = 55`. `BasePushUpPose.onBuild`
  then **ignores all three** and builds the chain as
  `ankleF.localPosition = (ankleX, ankleHeight, −hipWidth)` (root) followed by
  `kneeF.localPosition = (−shinLength, 0, 0)` — a pure X offset, so the knee inherits `ankleHeight`
  (= `kneeHeight + shinL·sin 45°` = 15 + 69.3 = **84.30**) instead of being placed at `kneeHeight`.
  The shin is therefore horizontal in mid-air rather than pitched; and the femur rotation is
  `−(theta + shinPitch)` where the solver's model needs `−theta` (that extra `shinPitch` is the
  65.9° − 20.9° = 45° over-rotation). The correct 3-line form is derivable *exactly* from the
  solver's own constants and reproduces them bit-for-bit:
  `kneeF.localPosition = (−shinL·cos(shinPitch), −shinL·sin(shinPitch), 0)` →
  kneeY = 15 = `kneeHeight`, kneeX = 164.6 = `kneeX`; and `pelvisY` → 55 = `pelvisHeight`.
* **Dead computation:** `PushUpPlankResult.pelvisHeight`, `.kneeHeight`, `.kneeX` have **zero
  production readers** (census: only `PushUpPlankTest` reads them). `PushUpPlankTest`
  `assertEquals(15f, resultTop.kneeHeight, 0.2f)` therefore certifies "the knee is on the floor"
  about a value that never reaches the skeleton.
* **Class:** CONFIRMED BUG (A pose authoring + dead engine output).
* **Status: FIXED — PR #227** (`fix/b1-knee-pushup-plank-geometry` @ `5c49aef`, not merged). The KNEES
  branch now consumes the solver: `kneeF.localPosition = (kneeX − ankleX, kneeHeight − ankleHeight, 0)`,
  `kneeF.localRotation = −theta`, depth authored as hip flexion via `buildHipFlexion` (0.42 rad amplitude
  unchanged, pelvis left neutral per BPS §5), the mirror leg laid out from the solver's own bone-exact
  knee→hip / ankle→knee deltas, and the knee pivot feeds the solver the rep's depth phase (raised cosine)
  instead of the linear animation phase so the LOOP seam closes. Post-fix produced frame: `KNEE_F.y` = 15.00,
  `PELVIS.y` = the solver's `pelvisHeight` (exact to 4 decimals at the top of the rep), both shins at the
  declared 45°, hands on the floor inside the reach band, elbow interior angle travelling ≥ 15°, p=0 and p=1
  within 1u on 7 joints. Regression gate: `app/src/test/java/com/monkfitness/app/KneePushUpPlankGeometryTest.kt`
  (7 tests, published-frame assertions only; RED 7/7 on the pre-fix source). This audit's original
  measurements above are retained unedited as the pre-fix record.
* **What test would have caught it yesterday:** an assertion on the **produced frame**, e.g.
  `every declared SupportContact joint lies within tolerance of its support surface`
  (see §4, T-1). `EnvironmentPenetrationTest` *does* cover `KneePushUpPose` (its `support.contacts`
  is non-empty) and still stayed green, because it asserts penetration only and explicitly
  disclaims float (`EnvironmentPenetrationTest.kt:63–68`).

### B-2 — `PoseMetadata.supportContacts` is a write-only channel; two poses lose their whole support model

* **Files:** `animation/PoseMetadata.kt:31` (declaration), 15 pose classes (writers),
  `animation/SkeletonPipeline.kt:142` (the only engine reader, of the *other* channel).
* **Symptom:** `PoseMetadata` carries **two** parallel support declarations —
  `support: SupportDefinition` (with `contacts`) and `supportContacts: Set<SupportContact>`.
  `supportContacts` has **zero readers anywhere in `app/src/main`** outside the pose files that
  assign it; the engine and the UI both read `metadata.support.contacts`. Measured corpus census:

  | declaration shape | poses |
  | --- | --- |
  | both channels populated (redundant) | 13 (push-up family, pull-up family, side plank) |
  | only `support.contacts` | 13 (squat×4, lunge×3, cossack, step-up, 4 validation poses) |
  | **only the dead `supportContacts`** | **2 — `StaticForearmPlankPose`, `IsometricSidePlankPose`** |

  For those two, `supportedPoints` on the published frame is **empty** → the Finalizer's support-plane
  derivation is entirely off. Measured consequence on the published frame:
  `plank_standard` `ELBOW_A = ELBOW_P = −44.75` (44.75 units **below** its own floor level 0);
  `side_plank_standard` `KNEE_B = −38.04`, `ELBOW_P = −37.86`, `HIP_B = −7.00`.
  `metadata.pivotType` is a third copy of the same fact and diverges too
  (`StaticForearmPlankPose`: `pivotType = ELBOWS`, `support.pivot = FEET`).
* **Second-order effect — it disables the invariant that would have caught it:** the two divergent
  poses are exactly the two entries in `EnvironmentPenetrationTest`'s `variants` list whose
  `support.contacts` is empty, so the test `continue`s at line 48 and **asserts nothing** for them.
  The vacuity hides the defect (see §4, T-2).
* **Class:** CONFIRMED BUG (E metadata inconsistency + D missing invariant), and a
  MIGRATION-adjacent ownership issue (two owners of one fact).
* **Not fixed:** repairing it is a *geometry* change (it turns on forearm/toe flattening in two
  production poses) and must be adjudicated together with B-3 below, which is the reason those
  flattening paths would still not fire.

### B-3 — `*_TOES` / `*_FOREARM` support contacts are never consulted by extremity derivation (feet float)

* **Files:** `animation/SkeletonPoseFinalizer.kt:860–862` (`footSupportPointFor`), `:725`
  (`adjustHandOrientation` handPoint selection).
* **Symptom:** `adjustFootOrientation` resolves the support point as
  `footSupportPointFor(ankleId) ∈ {LEFT_FOOT, RIGHT_FOOT}` and only then asks
  `pose.isSupported(point)`. Every pose that declares its foot support as **TOES** — the entire
  push-up family and `StaticForearmPlankPose` — therefore fails that check and gets
  `supportNormal = null`, i.e. **no** foot support plane, so the feet are never planted onto their
  surface. `adjustHandOrientation` has the same shape and no `*_FOREARM` branch.
* **Measured (published frames, ground level 0):** `pushup_standard` and its whole family:
  `ANKLE_F = 25.00`, `HEEL_F = 17.82`, `TOE_F = 42.57` — the toes, which the pose *declares* as
  floor contacts, float 42.57 units above the floor and point upward, while only the hands
  (declared `*_HAND`, so the hand path *does* fire) reach the floor at y = 0.00/1.96.
  `EnvironmentPenetrationTest` explicitly defers this half of its own contract
  (`:63–68`) and `ExerciseValidator` has no support-contact-plane rule, so nothing is red.
* **Class:** CONFIRMED BUG (A pose declaration vs engine consumer + D missing invariant). Known and
  previously documented as an engine gap; this audit supplies the corpus-wide measurement.
* **Not fixed:** teaching the extremity paths about TOES/FOREARM changes published geometry for
  ~8 production poses. Recorded as a decision item (§8) rather than landed silently.

### B-4 — Three mutually contradictory `SupportPoint ↔ Joint` side mappings

* **Files:** `animation/SupportMath.kt:18–44` (`jointsForContactMap`),
  `animation/SkeletonPoseFinalizer.kt:926–930` (`footSupportPointFor`),
  `animation/SkeletonPoseFinalizer.kt:944–955` (`contactJointsFor`),
  `animation/SkeletonPoseFinalizer.kt:725` (hand point selection),
  `poses/BirdDogPose.kt:42` (the only explicit statement of the convention in the tree).

  | support point | `SupportMath` | `footSupportPointFor` | `contactJointsFor` | `BirdDogPose:42` (authority) |
  |---|---|---|---|---|
  | `LEFT_FOOT` | `ANKLE_B` | — | `ANKLE_F` | F = **left** leg → `ANKLE_F` |
  | `RIGHT_FOOT` | `ANKLE_F` | — | `ANKLE_B` | B = **right** leg → `ANKLE_B` |
  | `ANKLE_F` | — | `RIGHT_FOOT` | — | `LEFT_FOOT` |
  | `LEFT_HAND` | `HAND_P` | — | `HAND_A` | A = **left** arm → `HAND_A` |
  | `RIGHT_HAND` | `HAND_A` | — | `HAND_P` | P = **right** arm → `HAND_P` |
  | `LEFT_FOREARM` / `RIGHT_FOREARM` | `{ELBOW_P, HAND_P}` / `{ELBOW_A, HAND_A}` | — | `{ELBOW_A, ELBOW_P}` (side collapsed) | side must be preserved |

  `BirdDogPose.kt:42` states the convention authoritatively:
  *"RIGHT side -> left arm (A) + right leg (B) extend; LEFT side -> right arm (P) + left leg (F)."*
  On that authority, `contactJointsFor` is **correct**, and `SupportMath` **and**
  `footSupportPointFor` are **inverted** — including against each other inside one file
  (`footSupportPointFor(ANKLE_F) = RIGHT_FOOT` vs `contactJointsFor(LEFT_FOOT) ∋ ANKLE_F`).
  This is the same defect class as `docs/STABILIZATION_AUDIT.md` **M2**, which records it for the
  side plank, but it is in fact systemic.
* **Measured blast radius:** inert for ground-only poses (`supportPlaneNormalFor` returns +Y for a
  ground-only environment regardless of which joints it averages). **Effective** whenever a prop is
  present or a foot set is asymmetric: `DeclinePushUpPose` (BoxProp), `StepUpPose` (StepProp),
  `WallSlidesPose` (WallProp) — the F foot's plane is computed from the B foot's centroid and vice
  versa. `SupportMath`'s inverted map feeds the support centroid / lever model, so any *single-sided*
  support declaration (the side plank declares only `RIGHT_FOOT`) computes its centroid from the
  opposite limb.
* **Class:** NEEDS ARCHITECTURAL DECISION — the FROZEN spec set (`ENGINE.md` axis conventions,
  `RFC_JOINT_OWNERSHIP_MATRIX.md`) does not define the `A/P/F/B ↔ left/right` mapping; the only
  statement of it is a code comment inside one pose. Fixing one of the three maps against a
  comment would be picking a side on an open convention, so it is recorded, not resolved.

### B-5 — Renderer entry points silently drop the Runtime Context (empty support model)

* **Files:** `animation/SkeletonPipeline.kt:102–107` (renderer overload:
  `supportedPoints: Set<SupportPoint> = emptySet()`), `SkeletonSnapshotRenderer.kt:75`
  (`pipeline.produceFrame(pose)` — no arguments at all), `validation/ValidationPoseLauncher.kt:55`
  (`SkeletonRenderer(...)` without `supportedPoints`).
* **Symptom:** on the renderer path the §1.1 runtime context is **caller-supplied and defaulted to
  empty**, so the support model silently disappears — the exact "fallback/default behaviour that
  masks a missing declaration" shape. Measured: `produceFrame(built, environment, ∅)` publishes
  `supportedPoints = ∅`; the builder path publishes 4 for the same pose (`StandardPushUpPose`).
  `SkeletonSnapshotRenderer.renderPose` **takes an `environment` parameter, uses it to draw, and
  does not forward it to `produceFrame`**, so the snapshot path finalizes against a default flat
  ground while drawing the real one. `SkeletonPose` carries no `metadata`, so the pipeline cannot
  recover the declaration on this path by construction.
* **Class:** NEEDS ARCHITECTURAL DECISION (the renderer path needs a declaration source — carry
  metadata/support on `SkeletonPose`, or make the parameter non-defaulted so omission is a compile
  error). One third of this (forwarding `environment` in `renderPose`) is a trivially correct
  one-line change, but it is measurably **inert** until the support set is supplied (the Finalizer
  reads `pose.environment` only inside `supportPlaneNormalFor`, which is gated on `isSupported`),
  so it ships with the decision, not before it.

### B-6 — `SkeletonPipeline.resetHistory()` clears the dynamics chain but not the smoothing history

* **File:** `animation/SkeletonPipeline.kt:446–449`.
* **Symptom:** `resetHistory()` nulls `previous`/`prePrevious` but leaves `previousSmoothingRoot`
  and `lastFrameArmedSmoothingCapture` — which are now **pipeline-owned Frame History** (RFC §4.5,
  §5 R10) — so a declared restart/seek still eases the first post-reset frame from a root captured
  before the reset. The KDoc at `:413` tells callers to call it "when the animation restarts/seeks".
  The debug trip-wire that exists for exactly this class of omission cannot fire, because its
  `previous == null ||` disjunct short-circuits.
* **Counter-evidence (why this is NOT reported as a silent bug):** the behaviour is **intentional and
  pinned**: `arch/InterFrameSmoothingTest.resetHistoryKeepsPreP5Semantics` asserts the smoothing
  input *survives* the reset, citing plan §P5 `"resetHistory() unchanged"`. The plan line was written
  when `previous/prePrevious` were the only history and the smoothing input was to be derived from
  `previous`; the landed implementation introduced a separate field, so "unchanged" now has an effect
  the plan text did not anticipate. `resetHistory()` also has **zero production callers** (census:
  only `RootAuthorityTest` and `InterFrameSmoothingTest`).
* **Class:** NEEDS ARCHITECTURAL DECISION + DEAD API (zero production consumers). Options:
  (α) clear the smoothing history too and retarget the pinning test with the plan rationale recorded;
  (β) keep it and narrow the KDoc so it no longer promises a "restart/seek" reset it does not perform.

### B-7 — Cold-pipeline first frames publish a misplaced head (one-build-stale `neck.worldPosition` read)

* **File / symbol:** `animation/BasePose.kt` `buildGaze` (`:44–60`, the read is `val nw = neck.worldPosition`),
  called from every gaze pose — for this measurement `poses/BasePushUpPose.kt:219`
  (`buildGaze(neck!!, head!!, def.neckLength, pushUpHeadDirection)`), which runs **before** the pose's own
  authoring-FK pass (`BasePushUpPose.kt:226–228`).
* **Symptom (measured, `pushup_knee` and `pushup_standard`, cold `SkeletonPipeline`, real frame cadence
  `dt = 0.0166 s`, 150 frames over `cycleDuration = 2500 ms`):** the first published frame is not continuous
  with the rest of the animation. `HEAD_POS` frame 1 vs frame 0 = 42.73 units (knee) / 42.17 (standard);
  `NECK_END` = 21.37 / 21.09. `ExerciseValidator` reports `POSITION_DISCONTINUITY` on frame 1
  (`HEAD_POS 42.728924 > 15`, plus 14 joints at 84.8–180.2 in the same frame) and `VELOCITY_DISCONTINUITY`
  on frame 2 (`HEAD_POS 2571.874 > 150`), then nothing for the remaining 148 frames.
* **Root cause, first incorrect state transition:** on the **first build of a pose instance** `buildGaze`
  reads `neck.worldPosition` before any FK has been propagated for that build, so the neck's world position is
  still the node's initial (zero) state: the authored synthetic target becomes `(0,0,0) + gazeDir·100` —
  measured **exactly `(−98.058, 19.612, 0.000)` for both `KneePushUp` and `StandardPushUp`, at both p=0 and
  p=0.5** (identical target ⇒ a shared-helper read, not pose math). The Finalizer resolves the head direction
  as `normalize(target − neck.worldPosition)`, so the frame-0 head points 51.5° off its settled direction while
  the bone lengths are preserved (neck 18.0): published frame 0 `dir = (0.6126, −0.7904)` vs the converged
  `dir = (−0.5736, −0.8191)`; by frame 2 the resolver sees `target − neck = (−98.025, 19.701) ≈ gazeDir·100`.
  The same stale read is what makes a residual `HEAD_POS` discontinuity survive a warm-up: warming the pipeline
  at p=0.3 and then producing p=0 leaves the first two frames with a 16.79-unit `HEAD_POS` step. The artifact is
  therefore a *one-build-behind* read whose magnitude equals the trunk's progress-to-progress displacement — not
  a warm-up requirement (error counts: 3 for every warm-up count 2…20, 32 for a true cold start).
* **Not a warm-up bug, not a reused-buffer alias:** the first frame is **published** wrong and the pipeline state
  is what lets the next frame correct it. `KneePushUp` and `StandardPushUp` share the artifact to within 0.6
  units even though one is the KNEES pivot and the other the FEET pivot, and it reproduces identically on the
  **pre-fix** `origin/main` source (frame-0 target `(−98.058, 19.612, 0.000)` there too) — so it predates P12
  activation and B-1 alike. It is **not** introduced by B-1 and is **not** fixed by it.
* **Class:** CONFIRMED PRODUCTION BUG (first-frame transient; visual severity one frame, validator severity
  ERROR). Deliberately **not** fixed here: the repair is an ordering/ownership change in a shared authoring
  helper (`buildGaze` must read the neck's post-FK world position, or the resolver must stop re-deriving the
  direction from a pre-FK target), it touches every gaze pose family, and it needs its own bounded change plus
  a regression test. Recorded here so the next independent change starts from the measured mechanism.

---

## 3. Biomechanical pose-defect inventory (measured)

Corpus: 49 registered animations + 4 validation poses, 5 progress samples each.

* **Superman (`SupermanPose`) — status: FIXED, on this branch, uncommitted.** The previous session's
  P5 correction is carried in the working tree (`SupermanPose.kt`, +112/−29) with
  `SupermanPoseProneInvariantTest` (4 tests). Measured on the `main`-equivalent baseline the pose
  rendered on the **supine** basis with the head 24.5 units below its own floor; the corrected build
  measures `facing = (0, −1, 0)` (**prone**), `spine = (+1, 0, 0)`, and **zero joints below ground at
  any sampled progress** (`minY` = 10.00 at p=0.5 vs the pre-fix −24.48). M4's second half
  (extension articulated at the spine, pelvis staying neutral) is implemented via the chest node +
  §1.1 carrier. Suite is green with it in place.
* **Knee push-up — status: CONFIRMED DEFECT, see B-1.** This is the highest-severity pose finding.
* **Other measured floor violations** (worst joint below `environment.ground.level`, published frame
  sweep): `world_greatest_stretch` `KNEE_B −46.11`; `plank_standard` `ELBOW_A/P −44.75`;
  `side_plank_standard` `KNEE_B −38.04`, `ELBOW_P −37.86`; `pelvic_tilt_standard`
  `ELBOW_A/P −33.35`, `HEAD_POS −2.54`, `NECK_END −2.53`; `glute_bridge_standard`
  `ELBOW_A/P −30.69`; `burpee_standard` 12 joints (worst `−27.85`); `pushup_diamond`
  `ELBOW_A/P −19.91`; `thoracic_rotations_reps` `FINGERTIPS_P −19.14`; `birddog_hold/_reps`
  `FINGERTIPS ±18.48`; `cobra_stretch_hold −6.99`; `cat_cow_reps −4.32`; `PikeSitPose −9.96`.
  Two distinct causes are visible in the data and must not be conflated:
  (i) the **B-3** TOES/hand-derivation gap (fingertips/palm below the plane in every prone pose);
  (ii) joints the engine has **no support-plane handling for at all** — `LEFT_KNEE/RIGHT_KNEE`,
  `HIPS/PELVIS/BACK`, `LEFT_ELBOW/RIGHT_ELBOW` are names in `SupportPoint` and are mapped by
  `SupportMath`, but no extremity-derivation path consumes them (`contactJointsFor` returns
  `{ELBOW_A, ELBOW_P}` / `emptyList()` for the elbow points). Declaring them is currently decorative.
* **Metadata corpus census:** `exerciseFamily` empty for 33/53, `bodyOrientation` empty for 37/53,
  `supportContacts` empty for 38/53, `environment.props` present for only 3 poses
  (`wall_slide_standard`, `scapular_pullup_deadhang`, `lat_stretch_hold`) — i.e. the
  `StepUpPose`/`DeclinePushUpPose` props do exist but most prop-bearing props are absent, and the
  `H1` (WallSlides wall) class from `docs/STABILIZATION_AUDIT.md` remains only partially addressed.
* **Posture declarations:** 46/53 publish `CUSTOM`, so the `ConstraintSolver`'s coarse-root machinery
  is exercised by only 6 poses. Not a defect; relevant when reasoning about which solver paths the
  corpus actually covers.
* **Motion-curve drift check:** `Superman` is `LINEAR` while its family siblings are `EASE_IN_OUT`;
  recorded here as the previously flagged open tuning question, still unresolved
  (no evidence of accidental change during migration — the curve is identical on both sides of the
  P12 diff).

---

## 4. Test / invariant gaps

* **T-1 — No test asserts a declared support contact actually lands on its support surface.**
  `EnvironmentPenetrationTest` asserts penetration only and *explicitly* disclaims float at `:63–68`;
  `ExerciseValidator` has no support-contact-plane rule. This is the single invariant whose absence
  lets B-1, B-3 and the plank penetrations all survive. It is the "what would have caught it
  yesterday" answer for every finding in §2 and §3.
* **T-2 — `EnvironmentPenetrationTest` names 25 poses and asserts nothing for 8 of them.**
  `if (contacts.isEmpty()) continue` (`:48`) skips every pose whose `metadata.support.contacts` is
  empty: `GluteBridgePose`, `StaticForearmPlankPose`, `DeadBugPose`, `CatCowPose`, `SupermanPose`,
  `LegRaisePose`, `HipCarsPose` (and `BirdDogPose`/`SquatPose` by the same channel). The two poses
  whose declarations live only in the dead `supportContacts` channel (B-2) are skipped for exactly
  this reason — the vacuity is what hides the defect. Its own KDoc claims it applies "to EVERY pose".
* **T-3 — `PushUpPlankTest` certifies dead values.** `assertEquals(15f, resultTop.kneeHeight, 0.2f)`,
  `pelvisHeight`, `kneeX` are asserted against `PushUpPlankResult` fields with **zero production
  readers** (§B-1). The test is green while the produced frame has the knee at 84.30. This is the
  "verifies an implementation detail instead of the actual produced result" class.
* **T-4 — `arch/ActivationEquivalenceTest` cannot see pose defects.** It compares flag-OFF vs flag-ON
  *within the same tree*, so an authoring-frame error cancels on both sides. It is an ownership
  oracle, not a pose oracle — worth stating explicitly in its KDoc so a green equivalence run is never
  cited as pose correctness.
* **T-5 — Motion tests assert travel only.** `MotionProbe.maxVerticalTravel` / `maxTravel3D` cannot
  distinguish "the right exercise" from "the wrong exercise that still travels"; the knee push-up
  passes them while being a floating plank (B-1).
* **T-6 — `EnvironmentPenetrationTest.supportJoints` is a fourth private copy of the side mapping**
  (`:80+`), and it agrees with `contactJointsFor` (A = left). Any future fix to B-4 must find all four.
* **T-7 — `KneePushUpPoseTest.testKneePushUpPoseBiomechanicalCompliance` aliases the reused frame buffer,
  so its 100-frame temporal sweep validates one frame 100 times.** The test stores
  `poses.add(pipeline.produceFrame(rawPose).pose)` (`KneePushUpPoseTest.kt:46`) and then feeds
  `previousPose`/`prePreviousPose` from that list. `SkeletonPipeline.produceFrame` returns the Finalizer's
  **reused** output buffer (`SkeletonPoseFinalizer.outputPose`), so all 100 entries are the same object:
  measured `System.identityHashCode(...pose)` identical across 5 successive frames (1 distinct identity),
  stored-ref `CHEST.y` spread = 0.0 with 1 distinct value while value-snapshots of the same frames give 10
  distinct values, and `previousPose === currentPose` for **every** step. Consequences: (i) the pose is
  effectively validated once, at the last produced progress — never as a moving sequence; (ii) every
  temporal rule the config enables is structurally inert (`HAND_SLIDING` computes a zero displacement,
  `POSITION_DISCONTINUITY`/`VELOCITY_DISCONTINUITY` compare a frame against itself), so this class cannot
  see a hand slide, a pop or any inter-frame defect, and it stays green while B-1 and B-7 both ship; and
  (iii) the `expectedSupportJoints`/symmetry assertions silently lose their sweep dimension.
  `MotionProbe` documents the same trap in its own KDoc ("never store `.pose` references") — the pose test
  simply does not follow it. Class: **TEST / INVARIANT GAP (test adequacy defect), not a production bug.**
  Deliberately **not** rewritten as part of B-1: fixing it changes what an existing green test can observe
  and would immediately surface the B-7 first-frame errors, so it needs its own change with its own
  before/after evidence. The new B-1 gate (`KneePushUpPlankGeometryTest`) snapshots every frame via
  `copyFrom` for exactly this reason.

---

## 5. Migration / legacy inconsistencies

* `PoseMetadata.supportContacts` (15 writers / 0 readers) — B-2. A second declaration channel for a
  fact the engine already owns. Whoever repairs B-2 must delete the channel or make it the single one;
  leaving both is what produced the two broken planks.
* `PoseMetadata.pivotType` vs `PoseMetadata.support.pivot` — same shape, same inversion
  (`StaticForearmPlankPose`, `IsometricSidePlankPose`, `DeadHangPose` validation).
* `IK_STAGE_ACTIVE` is still declared as a **top-level mutable `var`** (`animation/IkStage.kt:89`),
  while plan §12.7 — the ratified P12 contract — requires the declaration to "move from test-only
  rollout to an engine-supplied configuration input (constructor/definition-level knob supplied by
  the creator per R14; still zero silent production writes)". What landed is a process-global mutable
  flag that production never writes but that ~6 test classes flip **globally** (in-memory, with
  `try/finally` restore). It is the observed configuration surface, it is statically audited, and the
  §12.7 "retargeted, not deleted" config audit exists — but the R14 ownership property the plan
  required (creator-owned, lifetime-scoped) is **not** implemented. Class: MIGRATION VIOLATION /
  plan non-compliance, low live risk (no production write path exists).
* `PushUpPlankResult`'s three unused outputs — B-1 dead computation.
* `SkeletonPipeline.resetHistory()` — no production callers (B-6).
* Every B-2 file (`bakeIkLimb`, both overloads) still returns `ikBuffer` unchanged when the stage is
  active (early `return` above the solve), so any *future* caller that reads a bake's return value
  under state 3 receives an unsolved scratch buffer. No production caller reads it today (WP-C
  dropped the captures) — recorded as a latent shape, not a live defect, because the early return
  sits **below** the registration effects (§12.5) and above nothing else.
* No other producer/consumer gaps found: the static sweeps for `solveIK(` call sites
  (8, all inside the 5 registered implementation files), `WeakHashMap`, `lastSolvedRoot` and
  `supportContacts` are clean apart from the items above.

---

## 6. Intentional legacy / tuning (documented, not bugs)

| item | why it is not a bug |
| --- | --- |
| `IK_STAGE_ACTIVE=false` still selectable | R5: the flag is a rollout mechanism selecting between two implementations of the same frozen responsibility set; the §12.7b counter proves single ownership in **both** configurations. |
| `metadata.supportContacts` kept on the push-up/pull-up families | deliberate redundant declaration (audit `DONE — Push-Up Family` records adding it "alongside the existing `support`"); harmless while both agree. |
| `shinPitch = SHIN_PITCH_ANGLE = 45°` in `PushUpPlank.solve` | an explicit, documented engine decision ("prevents ground foot penetration in knee-pivot"); whether the shins should lie *along* the floor per BPS §7 is a tuning question, separate from B-1 (where the produced frame does not even reach 45°). |
| `SupermanPose.motionCurve = LINEAR` | pre-existing authored tuning; unchanged across the P12 diff — recorded as an open tuning question, not migration residue. |
| `regression counters / debug trip-wires` compiled out of release | by design (`BuildConfig.DEBUG` gating), consistent with every P4–P12 instrument. |
| `object SkeletonFactory / SupportMath / IkStage / PushUpPlank` | stateless function namespaces, not "global singletons" in the R11/R14 sense. The new P11 gate is scoped to forbid only the shared *mutable* shapes the plan names, to avoid false-firing on them. |
| `SkeletonPoseFinalizer.contactJointsFor` collapsing both forearms to `{ELBOW_A, ELBOW_P}` | not intentional — folded into B-4 (a defect), listed here only because `contactJointsFor` is otherwise correct. |

---

## 7. Documentation drift

* `docs/STABILIZATION_AUDIT.md:5–6` — "Baseline: **282 tests**" while the suite is now **462**; and
  §"TODO — P1" still lists H2/M9/M10/M11/M12 as outstanding although the H2 migrations landed in P12.
  M4 is listed as open and is in fact fixed on this branch. `:127–130` still says the Push-Up pass
  "could not be compiled/validated" (stale).
* `docs/IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md` §Phase 11 `Current state:` still claims R11/R14
  compliance is "Compliant by construction" with a cite of `SkeletonPoseFinalizer.kt:332–338` — the
  file has moved (finalize is at `:343`, runStages at `SkeletonPipeline.kt:178`), and the claim is now
  backed by executed evidence instead of prose. §Phase 12 (`:220`) still reads
  **"Status: … NOT STARTED"** for a phase that merged as #225. §Phase 4 (`:236`) still describes
  `IK_STAGE_ACTIVE=false` as the current production state, contradicting `IkStage.kt:89`.
* `docs/STABILIZATION_AUDIT.md` §2 "Cross-cutting theme" describes the pre-P12 state ("~14 poses
  … call `SkeletonMath.solveIK` directly") — no longer true (the B-2 family was migrated at WP-D).
* `animation/PoseDefinition.kt:184` KDoc: "Actual consumption (if any) belongs to the later phase
  assigned to the Settled-Contact Guarantee; it is not part of Phase 3" — the Settled-Contact
  Guarantee landed at P7; the sentence is now stale.
* `animation/SkeletonPoseFinalizer.kt` KDoc at `:918–924` documents a doc-comment for
  `footSupportPointFor` but the KDoc block is attached above the *mapping* helper and describes the
  normal lookup; and `:935–936` describes the environment/support model as if `metadata.support.contacts`
  were the sole channel. Both need retelling once B-2/B-4 are adjudicated.
* `animation/IkStage.kt:33`'s "flip it on after the `IkStageTest` byte-identity check is green" B-5
  under-conditioned flip criterion is gone (correctly), but `IkStage.kt:86–87` still says the flag
  "lives beside its sole reader … rather than in a global flag object" — it *is* a top-level global
  (`var IK_STAGE_ACTIVE`), so the sentence contradicts the code it sits above.

---

## 8. Remaining blockers / disposition

**Must fix now —** nothing landing-blocking. P11 is an audit phase; no defect found here breaks a
currently-shipped behaviour in a way that is worse than the pre-P12 baseline (the Superman defect it
inherited is fixed on this branch).

**Recommended next, in this order** (each needs its own bounded change + regression test):
1. ~~**B-1 knee push-up**~~ — **DONE, PR #227** (`fix/b1-knee-pushup-plank-geometry` @ `5c49aef`, not merged).
   7-test produced-frame gate; RED 7/7 pre-fix → GREEN post-fix; full suite 104 classes / 457 tests.
2. **B-7 cold-start head placement** — new, recorded in §2. One-frame transient, shared `buildGaze` path,
   affects every gaze pose family; fix belongs with T-7, because a correct temporal sweep is what proves it.
3. **T-1 support-contact-on-surface invariant** — write it *with* the B-3 fix so it can be green;
   it is the invariant that would have caught §2 in one run.
4. **T-7 aliased-buffer test repair** — turn `KneePushUpPoseTest` (and any sibling that stores
   `produceFrame(...).pose`) into a real frame-snapshot sweep before touching B-7.
5. **B-3 / B-2 plank + push-up foot planting** — one change, because fixing either alone leaves the
   corpus inconsistent; must be landed with golden review (it moves ~8 published poses).
6. **B-5 renderer context** — make omission a compile error or carry the declaration on the carrier.

**Requires architectural decision (recorded, deliberately not resolved):**
* **B-4** the `A/P/F/B ↔ left/right` convention and its three (four) contradictory maps — needs an
  authoritative line in the FROZEN spec set, not a comment.
* **B-5** where the renderer path gets its Runtime Context.
* **B-6** `resetHistory()` scope vs the plan §P5 "unchanged" pin.
* **§12.7 flag lifecycle** — creator-owned configuration input vs the landed global.

**Can be deferred:** documentation drift (§7); `IK_STAGE_ACTIVE` global (no live risk while zero production
writers). The `PushUpPlankResult` dead-output item is **closed**: all three previously unread fields
(`kneeX`, `kneeHeight`, `pelvisHeight`) plus `theta` are now consumed by the KNEES branch in PR #227.

**False positives checked and rejected:**
* `IK_STAGE_ACTIVE` read sites — `BasePose.kt:366/616`, `BaseValidationPose.kt:320`,
  `IkStage.kt:105`, `SkeletonPipeline.kt:354`: each verified to gate only the realization block,
  with registration/window-bookkeeping above it (§12.5/WP-D/WP-F) — correct.
* The WP-G counters: window count vs per-execution mask verified consistent across rebuild,
  reuse-without-rebuild and stage/authoring configurations — no counter reset hole found.
* `produceFrame(pose, ctx)` publishing `supportedPoints` only from the second frame onward is not a
  bug (the first call warms a reused buffer; the builder path re-injects every frame).
* `SkeletonPoseFinalizer` re-entry / publish-order guards, `PhaseBoundaryAsserts` arming, and the
  settlement non-leakage from `copyFrom` all verified behaviourally — no bypass found.

---

## 9. Verification evidence (this audit)

| check | command | result |
| --- | --- | --- |
| baseline full suite (pre-audit tree) | `./gradlew :app:testDebugUnitTest --rerun-tasks` | 104 classes / **454 tests** / 0F / 0E / 0S, XML mtime `2026-09-11T17:37:43Z` |
| new P11 gate class | `--tests "com.monkfitness.app.arch.CarrierTransferComplianceTest" --rerun-tasks` | 8 tests / 0F |
| gate counterfactual RED | inject `WeakHashMap<SkeletonPose, Float>` into `SkeletonStyle.kt`, run gate class | **1 of 8 FAILED** (`noForbiddenSharedMutableChannelOrGlobalPipelineState`); file reverted, `git diff` empty |
| final full suite (probes removed) | `rm -rf app/build/test-results/testDebugUnitTest` then `./gradlew :app:testDebugUnitTest --rerun-tasks` | **105 classes / 462 tests / 0F / 0E / 0S**, every XML stamped `2026-09-11T17:48:53Z` (forced fresh) |
| release compile | `./gradlew :app:compileReleaseKotlin` | see §"Release compile" in the session report |
| corpus measurement | throwaway `ZzP11CorpusProbeTest` (deleted before the final run) | 53 poses × 5 progress × 33 joints → `p11-audit/corpus-builder-path.tsv` |
| knee push-up trace | throwaway `ZzP11KneePushUpProbeTest` (deleted) | `p11-audit/p11-knee-trace.txt` |
| support-channel trace | throwaway `ZzP11SupportTraceTest` (deleted) | `p11-audit/p11-support-trace.txt` |

All three probe classes were deleted before the final suite run, so the reported 462-test figure
contains no audit scaffolding.

**B-1 / B-7 / T-7 follow-up measurements (2026-09-11, after this audit was written; probes deleted before
the reported suite runs — `git status` clean, `git log --all -- app/src/test/java/.../Zz*` empty).**

| check | command | result |
| --- | --- | --- |
| B-1 focused gate, pre-fix source | `git checkout origin/main -- BasePushUpPose.kt` then `--tests "…KneePushUpPlankGeometryTest" --rerun-tasks` | **7 tests / 7 FAILED** (assertion text quoted in PR #227) |
| B-1 focused gate, post-fix | same command on `fix/b1-knee-pushup-plank-geometry` | **7 tests / 0 FAILED**, XML ts `2026-09-11T19:12:26Z` |
| baseline full suite, pristine `origin/main` source (B-1 class withheld) | `:app:cleanTest :app:testDebugUnitTest --rerun-tasks` | **103 classes / 450 tests** / 0F / 0E / 0S, XML ts `19:14:00Z…19:14:04Z` |
| B-1 branch full suite | same, branch tree, no probes | **104 classes / 457 tests** / 0F / 0E / 0S, XML ts `19:12:38Z…19:12:43Z`; structural counts agree (`104` files with `@Test`, `457` annotations) |
| six FEET-pivot variants, pre-fix vs post-fix | throwaway `ZzDumpProbeTest` (`Joint.values()` × 5 progress × 6 variants) | 996 lines each side, `diff` = 0, sha256 `78a9ed407afc2ea290af4f9153ab82bbbd4442036cae8b96aaf401afa72e7d8b` both |
| B-7 cold-start sweep | throwaway probes (cold + warm-up 0/1/2/3/5/10/20, 150 frames @ `dt=0.0166`, 2× loop) | 32 ERRORs (frames 1–2) on a cold pipeline, 3 on every warm-up ≥ 2, **0 in the second loop**; `HEAD_POS` step 42.73 (knee) / 42.17 (standard) |
| B-7 mechanism | authored `headTarget` per frame (`buildHead`/`buildGaze` output) | frame 0 target `(−98.058, 19.612, 0.000)` for **both** poses at p=0 **and** p=0.5; by frame 2 `target − neck = (−98.025, 19.701)` |
| B-7 reproduction on pre-fix source | same probe with `origin/main`'s `BasePushUpPose.kt` | identical frame-0 target → predates B-1/P12 |
| T-7 aliasing | throwaway probe over the `KneePushUpPoseTest` storage pattern | `identityHashCode` identical across 5 frames (1 distinct); stored-ref `CHEST.y` spread 0.0 (1 distinct) vs 10 distinct for value snapshots; `previousPose === currentPose` = true |
| release gate | `./gradlew :app:assembleRelease` | FAILS at `:app:lintVitalRelease` with the same 2 pre-existing errors (`themes.xml:2` ResourceCycle, `build.gradle.kts:25` ExpiredTargetSdkVersion); APK still produced (7.8 MB); `compileReleaseKotlin` + `assembleDebug` green |

**B-1 CI (authoritative for the proposed fix).** PR #227 Android CI run `34637839631` → **success**
(1m52s) on head SHA `5c49aef8598e75d672ba586a6a3d311856a3f65e` = the branch tip = the PR head = local
`HEAD`. The PR is **not merged**; no merge decision is implied.

**Execution environment disclosure.** The suite runs above were **ad-hoc local Gradle** on the audit
host. GitHub CI was additionally executed on the exact committed bytes: Android CI run
`34629937098` → **success** (1m46s) on head SHA
`5e92f3f356339b008cd4d8b09fb93e7899005e03` (= the branch tip = the PR #226 head = local `HEAD`).
The PR was opened by this audit and is **not merged**; no merge decision is implied.

---

## 10. P11 conclusion

P11's declared deliverable — the R11/R14 carrier-transfer and pipeline-lifetime compliance suite plus
its CI grep gate — now exists, is green, and has been shown able to fail (counterfactual RED on the
grep gate). The three properties the plan asked for are thereby converted from "compliant by
construction" into executed evidence.

**P11 is nonetheless NOT complete, and must not be closed on the strength of a green suite.** The
assembled and activated production system contains at least five confirmed correctness defects
(B-1 knee push-up geometry + dead solver outputs; B-2 the write-only support channel that leaves two
production planks without any support model; B-3 toes/forearm contacts never consumed by extremity
derivation; B-4 three contradictory side mappings; **B-7** cold-pipeline first frames publishing a
misplaced head from a one-build-stale `neck.worldPosition` read), one R14-ownership gap in the P12 flag
lifecycle, and two missing invariants that are the common reason all of them stayed invisible: **T-1**
(no declared support contact is ever asserted to land on its support surface) and **T-7** (the knee
push-up's 100-frame temporal sweep validates one reused buffer 100 times, so no inter-frame defect could
ever be seen). Four further items are recorded as needing an architectural decision and were deliberately
**not** resolved here. **B-1 is now fixed** and proposed separately in PR #227; every other finding above
remains open. The methodological precedent holds: the suite is green, and the system is not correct.
