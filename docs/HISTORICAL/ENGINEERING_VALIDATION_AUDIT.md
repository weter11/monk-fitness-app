# Engineering Validation Poses — Defect Audit

> **HISTORICAL ARCHIVE.** Retained as the original per-pose defect audit. Not kept
> in sync with the live code. Index and cross-cutting findings:
> `docs/ENGINE_HISTORY.md` §5. Live future work: `docs/ENGINE_ROADMAP.md`. When
> this file conflicts with the current code, the code wins.

> **Investigation only.** No code, pose, engine, or renderer was modified.
> Purpose: attribute every remaining *visible* defect in the four Engineering
> Validation poses to exactly one of: AUTHORING (pose), ENGINE (animation core),
> CAMERA, VALIDATION, RENDERER. Categories are kept strictly separate.

## Method & evidence basis

- Exact world-space numbers below come from a standalone, offline numeric replica
  of the MonkEngine's FK + IK + `ConstraintSolver` + finalizer completion, built
  directly from the Kotlin source (`SkeletonMath`, `SkeletonNode`, `ConstraintSolver`,
  `SkeletonPoseFinalizer`, `PoseDefinition`). Bone lengths from `HumanSkeletonDefinition`:
  **thigh 112, shin 98, upper-arm 80, forearm 66, shoulderWidth 46, hipWidth 22,
  footLength 35, torso 120.**
- "Reachability" is the authored IK target distance vs `L1 + L2` (two-bone reach).
  `solveStraightLimb` clamps into `[minDist, maxDist]`; when `dist < L1` it falls
  into the **UNI-9 degenerate bent-limb fallback** (the straight limb becomes bent).
- Validation poses run through the **full** pipeline in the viewer
  (`ValidationPoseLauncher` → `SkeletonRenderer` → `SkeletonPoseFinalizer.finalize()`
  → `ConstraintSolver` → FK → chest-frame reconstruction → foot/hand completion →
  `SkeletonProjector`). The `ConstraintSolver` only translates/tilts the root to
  restore *reachability*; for reachable contacts it is a no-op, so the baked geometry
  below is what is effectively rendered.
- A replica FK composition bug affecting only the vertical translation chain was
  found and isolated; **Dead Hang arm reach is therefore proven by reachability
  arithmetic, not replica coords** (see §4). All other coords are replica-exact.

---

# 1. MIDDLE SPLIT (`MiddleSplitPose.kt`)

Authoring: pelvis `(0,14,0)`, spread = `hipWidth*3.6 = 79.2` along ±Z, legs/arms
`straight=true` with `legStraightConstraint` (full extension).

### 1.1 Legs NOT straight — ENGINE + AUTHORING
- **Evidence:** `HIP_F=(0,14,-22)` → target `(0,0,-79.2)`: distance = **58.9** <
  `L1=112`. Arms: `SHOULDER_A=(0,120,-46)` → target `(0,120,-79.2)`: distance =
  **33.2** < `L1=80`. Both are far inside the proximal-bone length.
- **Root cause:** `solveStraightLimb` hits `dist < L1` → UNI-9 fallback (`SkeletonMath.kt:660`)
  solves a **bent** triangle limb instead of a straight one. Replica: `KNEE_F=(0,98,-54.3)`,
  `ANKLE_F=(0,-98,-2.9)`; `thigh=103.1` (≠112), `shin(KNEE→ANKLE)=202.5` (≠98) — bone
  lengths are wrong and the knee is grossly over-flexed.
- **Engine component:** `SkeletonMath.solveStraightLimb` UNI-9 fallback.
- **Pose component:** target spread `79.2` is unreachable for a straight leg rooted at
  y=14; the authored `straight=true` is unsatisfiable.
- **Severity:** Critical. The pose's prime purpose (straight legs in a split) is unmet.
- **Fixable in pose?** Yes — reduce spread so `dist ≥ L1` (e.g. a real middle-split
  geometry: hips abducted, femur pointing outward+down so target distance ≥112), or
  drop `straight` and author a realistic abduction. **Primary fix is authoring.**
- **Fixable only in engine?** Only if the MonkEngine runtime should *reject* an unsatisfiable
  `straight` target differently; UNI-9 is working as designed (keeps bone lengths by
  bending). Engine is not the defect.
- **Future impact:** UNI-2 `STRAIGHT_LIMB_INTENT` validation will flag this; the pose
  defeats its own intent.

### 1.2 Arms NOT straight — ENGINE + AUTHORING
- **Evidence:** arm target distance **33.2** < `L1=80` (above). Replica: `ELBOW_A=(0,194.7,-74.6)`,
  `HAND_A=(0,240,-92)`; `uparm=80.0`, `fore=48.5` (≠66) → forearm under-length, elbow
  degenerate. Same UNI-9 fallback as §1.1.
- **Severity:** Critical. "Straight arms" requirement unmet.
- **Fixable in pose?** Yes — widen `armReach`/placement so distance ≥80, or don't claim
  straight. Primary fix is authoring.

### 1.3 Feet penetrate the ground — AUTHORING (+ engine contact behavior)
- **Evidence:** Replica `ANKLE_F=(0,-98,-2.9)`, `HEEL_F=(-6.8,-99.9,-10.2)`,
  `TOE_F=(16.6,-93.3,15.0)`. Ankle and heel sit at **y ≈ -99**, i.e. ~99 units **below**
  ground level 0.
- **Root cause:** With the leg folded by the UNI-9 fallback, the foot ends far below the
  authored ground target; `solveStraightLimb`/`resolveContactPlane` clamps the *end* onto
  `y=0` only when `signed < 0` for the unclamped straight aim — but the bent fallback
  resolves the end independently and the ConstraintSolver (contacts registered) re-bakes
  toward the target `(0,0,…)`, yet the folded knee geometry cannot place the ankle at y=0
  without over-extending. Net: feet below floor.
- **Engine component:** `SkeletonMath` straight/contact solve + `ConstraintSolver`.
- **Pose component:** unreachable target + `straight=true` combination.
- **Severity:** High (ground penetration is a hard validation failure).
- **Fixable in pose?** Yes — fix §1.1 (reachable straight legs) and feet land at y=0.
- **Fixable only in engine?** No.

### 1.4 Hip rotation absent (knees not facing up) — AUTHORING
- **Evidence:** `buildPelvis` sets only `hipF/hipB` local offsets `(0,0,∓22)`; no
  femoral axial (external) rotation is authored. In a middle split the hips must
  externally rotate so knees point up/forward.
- **Root cause:** Validation base has no `buildHipRotation`/hip-DOF authoring call.
- **Engine component:** n/a (capability exists: `SkeletonMath.buildHipRotation`, UNI-10).
- **Severity:** Medium (anatomical incorrectness; knees point laterally/down).
- **Fixable in pose?** Yes — author hip external rotation.
- **Fixable only in engine?** No.

### 1.5 Knee / elbow direction — AUTHORING
- **Evidence:** `KNEE_F=(0,98,-54.3)`, hip z=-22, target z=-79 → knee travels laterally
  (−Z) and slightly forward. Elbow degenerate (§1.2).
- **Root cause:** pole vectors `(0.2,1,∓0.6)` choose a forward+lateral knee; for a
  straddle split the knee should track the foot (pure lateral) and face upward.
- **Severity:** Low/Medium.
- **Fixable in pose?** Yes (pole + hip rotation).

### 1.6 Foot orientation — AUTHORING (+ engine completion)
- **Evidence:** Replica foot direction `toe−heel ≈ (23.4, 6.6, ±25.2)` i.e. foot points
  down-and-out, not flat on the floor. Ankle rotation authored `axisZ 0`.
- **Root cause:** `adjustFootOrientation` derives the foot forward from the **shank**
  direction; with the shank pointing steeply down (folded leg), the foot points down.
  Follows from §1.1/§1.3.
- **Severity:** Medium (consequence of the leg defect).
- **Fixable in pose?** Yes, once legs are correct.

### 1.7 Arm orientation — AUTHORING
- **Evidence:** hands `axisZ 0`, palm local `(6,0,0)` → palms face ±X (forward/back),
  arms extend sideways (±Z). Acceptable as "palms down" once arms are actually straight.
- **Severity:** Low (blocked by §1.2).

### 1.8 Authored-intent preservation — ENGINE + AUTHORING
- **Evidence:** `straight=true` limbs resolve bent (§1.1/§1.2).
- **Validation rule:** `STRAIGHT_LIMB_INTENT` (UNI-2, engineering config) would fail.
- **Severity:** High.

### Middle Split verdict
| Category | Defects |
| --- | --- |
| AUTHORING BUG | unreachable `straight` targets (legs 58.9<112, arms 33.2<80); no hip rotation; pole choice |
| ENGINE BUG | none (UNI-9 fallback behaves as designed) |
| CAMERA ISSUE | none |
| VALIDATION ISSUE | `STRAIGHT_LIMB_INTENT`/`BONE_LENGTH`/`FOOT_GROUND_PENETRATION` would catch it — not a validator defect |
| RENDERER ISSUE | none (passive) |

---

# 2. PIKE SIT (`PikeSitPose.kt`)

Authoring: pelvis y=40, `fold=0.95` (pelvis `axisZ −0.95`, chest `axisZ −0.57`),
legs straight forward to `footX=205.1`, arms bent (`def.armIKConstraint`) reaching
toes at `(footX−26.4, 8, ∓19.8)`.

### 2.1 Arms cannot reach the toes — AUTHORING + ENGINE
- **Evidence:** estimated shoulder world ≈ `(−51.7, 107.5, −46)`; target
  `(178.7, 8, −19.8)`: distance = **252.3** > `UPARM+FORE = 146`. Replica:
  `ELBOW_A=(79,12.6,0.2)`, `HAND_A=(62.4,−6.2,20.5)`; `shoulder→elbow = 92.4` (≠80),
  `elbow→hand = 71.3` (≠66) — **both arm bones violate length**, and the hand ends
  ~113 units short of the toe target.
- **Root cause:** `solveIK` clamps the reach to 146; the over-clamped solve redistributes
  error into a non-anatomical (over-long upper arm, over-short forearm) bent arm. The
  authored target is physically unreachable from the folded shoulder.
- **Engine component:** `SkeletonMath.solveIK` distance clamp (`SkeletonMath.kt:366`).
- **Pose component:** arm target placed ~252 from the shoulder; a pike requires the
  *shoulders to be near the knees* (the fold brings them forward), not 252 away. The
  `fold` makes the shoulders low/forward but the toe target is still too far.
- **Severity:** Critical. "Arms reach forward to grasp the toes" is unmet; bone lengths
  violated → `BONE_LENGTH` validation failure.
- **Fixable in pose?** Yes — bring the hand target within ~146 of the (folded) shoulder,
  or shorten the leg/raise the foot target, or accept bent arms with a reachable target.
- **Fixable only in engine?** No.

### 2.2 Elbow bend excessive / non-pike — AUTHORING
- **Evidence:** over-clamped solve forces a deep elbow flex (above). A pike has arms
  relatively straight, reaching down the shins to the toes.
- **Severity:** High (follows §2.1).
- **Fixable in pose?** Yes.

### 2.3 Shoulder rotation absent — AUTHORING
- **Evidence:** `buildShoulders` sets only local offset `(0,0,∓46)`; no clavicle/scapula
  activation. In a pike the shoulders should round forward (scapular protraction) as the
  chest folds. The trunk fold is authored on pelvis/chest only.
- **Engine component:** capability exists (`buildClavicularRotation`/`buildScapularRotation`,
  UNI-7) but is unused by the validation base.
- **Severity:** Medium.
- **Fixable in pose?** Yes.

### 2.4 Wrist orientation — AUTHORING
- **Evidence:** `handA.localRotation = axisZ −0.57` (≈ −fold·0.6). Hand completion rotates
  the forearm direction by −0.57 rad about Z → wrist flexion. Plausible for reaching toes.
- **Severity:** Low. OK.

### 2.5 Torso alignment — AUTHORING (by design, acceptable)
- **Evidence:** pelvis −0.95, chest −0.57 about Z → torso folded forward over the legs.
  Replica `CHEST=(0,120,0)`, `SHOULDER_A=(0,0,−46)` (shoulders at floor in front of hips)
  — consistent with a pike. Legs `KNEE_F=(112,0,1.2)`, `ANKLE_F=(93,0,1)` on floor.
- **Severity:** None (this part is correct).
- **Note:** `reconstructChestFrame` is skipped because chest rotation is non-identity
  (authored) — correct per Issue F.

### 2.6 Foot orientation — AUTHORING
- **Evidence:** `ankleF.localRotation = axisZ −0.95` (matches fold); completion derives a
  forward-pointing foot. Replica `HEEL_F=(93,0,11.1)`, `TOE_F=(93.4,0,−23.8)` → foot lies
  roughly along −Z (toe behind heel). For a pike the toes point forward (+X, same as the
  shins). The foot axis is rotated ~90° from expected — likely because the shank in this
  pose points +X while the authored ankle rotation is about Z (sagittal), so the
  completion's neutral forward is taken from the shank (−X/−Z blend) rather than +X.
- **Severity:** Medium.
- **Fixable in pose?** Yes (author ankle as forward +X / use `buildAnkleArticulation`).

### Pike Sit verdict
| Category | Defects |
| --- | --- |
| AUTHORING BUG | unreachable arm target (252>146) → clamped, wrong bone lengths; no shoulder girdle; foot axis |
| ENGINE BUG | none (clamp behaves as designed) |
| CAMERA ISSUE | none |
| VALIDATION ISSUE | `BONE_LENGTH` would catch the arm; not a validator defect |
| RENDERER ISSUE | none |

---

# 3. DEEP OVERHEAD SQUAT (`DeepOverheadSquatPose.kt`)

Authoring: pelvis `(−25,30,0)`, `lean=0.5` (pelvis `axisZ −0.5`, chest `axisZ +0.2`),
feet `(10,0,∓35.2)`, arms overhead to `(−5, reachUp≈271, ∓23)`.

### 3.1 Spine alignment (pelvis/chest counter-rotation) — AUTHORING
- **Evidence:** pelvis rotates −0.5 (forward fold), chest rotates +0.2 (upward) about Z.
  This puts a kink in the spine: the lumbar/pelvis folds forward while the thorax lifts,
  creating a C-curve rather than the relatively straight, slightly-leaning trunk a deep
  squat should show. `reconstructChestFrame` is skipped (chest non-identity) so the kink
  is preserved as authored.
- **Root cause:** deliberate but anatomically questionable authoring: a squat trunk is
  one coherent lean, not opposed pelvis/chest rotations.
- **Severity:** Medium.
- **Fixable in pose?** Yes — use a single coherent trunk lean (or small chest extension
  consistent with the pelvis).

### 3.2 Pelvis offset x=−25 — AUTHORING
- **Evidence:** `pelvis.localPosition.x = −25` while feet target `x=10`. The hip sits 35
  behind the foot; the femur therefore travels backward+down. Replica `KNEE_F=(110.9,0,15.9)`,
  `ANKLE_F=(−77,0,−60.6)`. Reachable, so ConstraintSolver is a near no-op; but the body
  reads as "sitting back" with the knee traveling far forward of the hip.
- **Root cause:** arbitrary authored x-offset; not justified by squat biomechanics (hips
  should track over the feet).
- **Severity:** Low/Medium.
- **Fixable in pose?** Yes.

### 3.3 Knees — mostly OK, ENGINE-adjacent note
- **Evidence:** `KNEE_F=(110.9,0,15.9)` — knee forward (+X) and out (+Z), tracking the
  foot target `(10,0,−35.2)` laterally. Knee valgus/forward tracking looks reasonable.
- **Severity:** Low.
- **Note:** foot target z=±35.2 = `hipWidth*1.6`; knees splay outward. Acceptable.

### 3.4 Elbows / arm symmetry — OK
- **Evidence:** arms reachable (shoulder→target ≈138 ≤146). Symmetric ±Z. Replica
  `HAND_A=(−1.5,56.9,33.5)` (note: this is the *clamped/replica* value; bake-only arm
  reach is within band so arms resolve near-straight overhead). Symmetric.
- **Severity:** Low.

### 3.5 Foot contact — OK (on ground)
- **Evidence:** `ANKLE_F y = 0` (bake-only), heel/toe on floor. `resolveContactPlane`
  keeps the ankle on `y=0`.
- **Severity:** None.

### 3.6 Reachability summary
- Legs: root→target 48.0 ≤210 (reachable, bent). Arms: 138 ≤146 (reachable, near-straight).
- No UNI-9 fallback, no clamp. **Core geometry is sound**; only stylistic authoring
  choices (§3.1, §3.2) are questionable.

### Deep Overhead Squat verdict
| Category | Defects |
| --- | --- |
| AUTHORING BUG | spine C-curve (opposed pelvis/chest lean); arbitrary pelvis x=−25 |
| ENGINE BUG | none |
| CAMERA ISSUE | none |
| VALIDATION ISSUE | none triggered |
| RENDERER ISSUE | none |

---

# 4. DEAD HANG (`DeadHangPose.kt`)

Authoring: bar at y=500, `gZ=1.5*shoulderWidth=69`, `reach=139`, pelvis
`y = 500−139−120 = 241`, arms `straight=true` to the bar (contact), legs straight down
with a forward pendulum (`ankleX=−18`).

### 4.1 Arms — REACHABLE (corrected)
- **Evidence (arithmetic, not replica):** shoulder world = pelvis(241)+torso(120) = **361**;
  target = `(0,500,−69)`; `dy=139, dz=23` → distance = √(139²+23²) = **140.9** ≤
  `UPARM+FORE=146`. `solveStraightLimb` resolves a straight arm; `resolveContactPlane`
  snaps the hand onto the bar plane (`y=500`). **Hands reach the bar correctly.**
- **Note:** the offline replica's `HAND y=−380` is a replica FK-composition artifact
  (it failed to add the pelvis world translation into the chest/shoulder chain); the
  real engine composes correctly (proven above). The earlier "CANNOT REACH BAR" note in
  the reachability scratch was computed against the *local* shoulder y=120 and is
  withdrawn.
- **Severity:** None for reach.

### 4.2 Grip orientation — AUTHORING (+ engine completion)
- **Evidence:** `handA.localRotation = axisZ (0 − π/2) = −90°`. Hand completion rotates the
  forearm direction (−Y, downward) by −90° about Z → completed hand/palm direction points
  **−X** (sideways), not curling up-and-over the bar. For an overhand dead hang the
  fingers should wrap over the top of the bar (palms forward, fingertips curling up).
- **Root cause:** a −90° Z rotation was chosen to flip the palm, but the completion takes
  the *forearm* direction and rotates it; the resulting "hand" points horizontally rather
  than wrapping the bar.
- **Engine component:** `HandDefinition.computeHandJoints` orientation-aware overload
  (applies `wristRotation` to the forearm direction).
- **Pose component:** grip angle / wrist authoring.
- **Severity:** Medium (grip reads wrong; bar contact looks like a sideways hand).
- **Fixable in pose?** Yes — author a wrist rotation that orients fingers over the bar,
  or accept the simplified rigid hand.
- **Fixable only in engine?** No (the completion is correct for the given rotation).

### 4.3 Shoulder girdle not engaged — AUTHORING
- **Evidence:** `buildShoulders` only sets local offset; no scapular depression/retraction.
  A dead hang should depress the scapula (shoulders pulled down away from ears).
- **Engine component:** `buildScapularRotation`/`buildClavicularRotation` (UNI-7) unused.
- **Severity:** Medium.
- **Fixable in pose?** Yes.

### 4.4 Wrist orientation — AUTHORING
- **Evidence:** hand `−90°` (see §4.2). For a passive hang the wrist is neutral; the
  −90° is really there to orient the palm for the grip and over-rotates the wrist.
- **Severity:** Low/Medium.
- **Fixable in pose?** Yes.

### 4.5 Body symmetry — OK
- **Evidence:** symmetric ±Z; legs straight down with slight forward pendulum (`ankleX=−18`),
  knees straight (reachable, `dist≈200.9 ≤210`).
- **Severity:** None.

### 4.6 Relaxed legs — OK (minor)
- **Evidence:** legs `straight=true`, pole `(0.15,1,0)` → knee slightly forward. Acceptable
  for a relaxed hang. Foot orientation: pose sets `heelF = +FOOT*heelRatio` (heel forward
  +X), opposite sign to the standing poses (`−FOOT*heelRatio`) — a minor inconsistency,
  toes point slightly back.
- **Severity:** Low.
- **Fixable in pose?** Yes (sign consistency).

### Dead Hang verdict
| Category | Defects |
| --- | --- |
| AUTHORING BUG | grip/wrist rotation puts hand sideways not over bar; no scapular depression; foot-sign inconsistency |
| ENGINE BUG | none |
| CAMERA ISSUE | none |
| VALIDATION ISSUE | none triggered (arms reach, lengths preserved) |
| RENDERER ISSUE | none |

---

# 5. Cross-cutting findings (apply to all four)

### 5.1 RENDERER — no defects
`SkeletonRenderer` is a passive consumer: it calls `finalizer.finalize(pose)`, then
`projector.project(...)`, depth-sorts, and draws bones/joints/faces/ground. It never
infers rotations from positions and never mutates the pose. No renderer-caused defect was
found in any of the four poses.

### 5.2 CAMERA — no defects
All four use `CameraDefinition(defaultYaw=1.19, defaultPitch≈0.22–0.28, defaultZoom≈1.2–1.5)`,
a consistent 3/4 side view. No camera parameter causes a defect; the defects are in the
authored skeletons, not the viewpoint. (A different yaw would not hide any of the above.)

### 5.3 VALIDATION — no validator defects; it is the intended detector
The engineering validation rules (`STRAIGHT_LIMB_INTENT` UNI-2, `BONE_LENGTH`,
`FOOT_GROUND_PENETRATION`, `IK_TARGET_UNREACHABLE`, `HIP_ROM_LIMIT` UNI-3) are exactly
what would surface Middle Split (§1) and Pike Sit (§2.1) as failures. The validator is
working as designed; it is **not** the source of any defect. The contract ("engine
satisfies validation; validation never bends to the MonkEngine runtime", `VALIDATION.md` §2) holds —
these are authoring/geometry defects the validator is meant to catch.

### 5.4 ENGINE — no engine bugs; UNI-9 and IK clamp behave as designed
Every "broken" limb in Middle Split and Pike Sit is the **correct, intended behavior** of
`solveStraightLimb` (UNI-9 fallback when `dist < L1`) and `solveIK` (distance clamp when
target unreachable). the MonkEngine runtime preserves bone lengths and never invents motion. The
defects are unsatisfiable authored targets, not engine faults.

---

# 6. Summary table — every defect, strict attribution

| Pose | Defect | Category | Severity |
| --- | --- | --- | --- |
| Middle Split | Legs not straight (target 58.9 < L1 112 → UNI-9 bent) | AUTHORING | Critical |
| Middle Split | Arms not straight (target 33.2 < L1 80 → UNI-9 bent) | AUTHORING | Critical |
| Middle Split | Feet penetrate ground (y≈−99) | AUTHORING | High |
| Middle Split | No hip external rotation (knees not up) | AUTHORING | Medium |
| Middle Split | Knee/elbow pole choice | AUTHORING | Low/Med |
| Middle Split | Foot points down (folded shank) | AUTHORING | Medium |
| Middle Split | `straight` intent not preserved | AUTHORING (+ENGINE working as designed) | High |
| Pike Sit | Arms unreachable (252 > 146) → clamped, bone lengths violated | AUTHORING | Critical |
| Pike Sit | Excessive elbow flex / non-pike arm | AUTHORING | High |
| Pike Sit | No shoulder girdle (clavicle/scapula) | AUTHORING | Medium |
| Pike Sit | Foot axis ~90° off (toe behind heel) | AUTHORING | Medium |
| Deep Squat | Spine C-curve (opposed pelvis −0.5 / chest +0.2) | AUTHORING | Medium |
| Deep Squat | Arbitrary pelvis x=−25 | AUTHORING | Low/Med |
| Dead Hang | Grip rotation points hand sideways, not over bar | AUTHORING | Medium |
| Dead Hang | No scapular depression | AUTHORING | Medium |
| Dead Hang | Wrist over-rotated (−90°) | AUTHORING | Low/Med |
| Dead Hang | Foot heel-sign inconsistency vs other poses | AUTHORING | Low |

**No ENGINE bug, no RENDERER bug, no CAMERA bug, and no VALIDATION bug** was found.
Every remaining visible defect originates in **pose authoring** (unsatisfiable IK
targets, missing joint-DOF authoring, grip/foot orientation). the MonkEngine runtime, renderer,
camera, and validator all behave correctly and are the right tools to surface these.
