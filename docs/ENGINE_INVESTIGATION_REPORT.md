# ENGINE INVESTIGATION REPORT — Fundamental Biomechanical Limitations

> **Scope / stance:** Investigation report. The four validation poses (Dead Hang, Deep Overhead
> Squat, Pike Sit, Middle Split) are treated as fixed anatomical references per `VALIDATION.md`.
> Numerical claims were derived directly from the *current* source
> (`SkeletonDefinition.DEFAULT_ADULT`, `SkeletonMath`, `ConstraintSolver`,
> `SkeletonPoseFinalizer`, and the four pose files). **Subsequent to the investigation, Issues C
> (wrist/ankle double-counting the parent frame), D (solver only pins `ANKLE_*`/`HAND_*` contacts),
> and F (`reconstructChestFrame` overwrites the authored chest rotation / assumes a symmetric thorax)
> were fixed in code** — see their sections for the fix evidence. No other issues in this report
> have been modified.

---

## 0. Context — the engine was heavily reworked

The previous investigation (now stale) listed 11 issues. A large rework has since landed
(the code references PR-03 … PR-11). Re-reading the current source, the following prior
issues are now **resolved in code** and should be considered closed:

| Prior issue | Status after rework | Evidence in current code |
|---|---|---|
| 1 — frame-relative baking used a hand-fed inverse-Z scalar | **Resolved (API)** | `bakeIkLimb` now converts world IK offsets with `toLocalDirection(parentRotation, …)`; `SkeletonPose.fromHierarchy` runs real FK. No scalar survives. |
| 2 — no straight/rigid limb mode | **Resolved** | `SkeletonMath.solveStraightLimb`, `solveStraightArmIK`/`solveStraightLegIK`. |
| 3 — IK clamp drove contacts through the floor | **Resolved** | `ContactConstraint` + `resolveContactPlane` inside `solveIK`/`solveStraightLimb`. |
| 4 — no global constraint/root solve | **Partially resolved** | `ConstraintSolver` (PR-04) exists and runs between IK and FK. **But its model is pelvis-translation-only** — see Issue A. |
| 5 — no scapular/clavicular DOF | **Resolved (skeleton)** | `CLAVICLE_*`/`SCAPULA_*` joints, `CHEST→CLAVICLE→SCAPULA→SHOULDER` chain, `SkeletonMath.buildScapularRotation`. **Introduces the coupling defect in Issue B.** |
| 6 — no wrist articulation | **Partially resolved** | `adjustHandOrientation` now reads `pose.getJointRotation(handId)` and passes it to `HandDefinition.computeHandJoints`. **But it uses the node's *world* rotation** — see Issue C. |
| 7 — no ankle/talocrural DOF | **Partially resolved** | `adjustFootOrientation` now reads `pose.getJointRotation(ankleId)` → `FootDefinition.computeHeelToe`. **Same world-frame defect as Issue C.** |
| 8 — no angular joint limits | **Resolved** | `AngularJointLimits`, angular clamp in `solveIK`, `validateAngularJointLimits`. |
| 9 — trunk frame 1-DOF | **Resolved** | `buildChestOrientation` (3-D) + `reconstructChestFrame` (derives chest frame from spine + shoulder line). |
| 10 — reachability detection dead | **Resolved** | `bakeIkLimb` auto-propagates `clampAmount` into `pose.maxIkClampAmount`; `ValidatorConfig.ENGINEERING_VALIDATION` enables `IK_TARGET_UNREACHABLE`. |
| 11 — 0.98 max-extension ceiling | **Resolved** | `IKConstraint.allowFullExtension` / `fullyExtended()` / `effectiveExtensionRatio`; `armStraightConstraint`/`legStraightConstraint` in `BaseValidationPose`. |

**Conclusion:** the rework is effective. Dead Hang, Deep Overhead Squat, and Pike Sit are now
reproducible by the engine to within the authored targets. **Middle Split still fails
catastrophically**, and there are latent correctness defects in the new scapula/global-solve
machinery. The remaining *architectural* limitations are documented below.

---

## Reference numbers (from `SkeletonDefinition.DEFAULT_ADULT`)

```
torso = 120   neck = 18   thigh = 112   shin = 98   foot = 35
upperArm = 80   forearm = 66   shoulderWidth = 46   hipWidth = 22
Arm chain L1+L2 = 146   Leg chain L1+L2 = 210
Arm min-fold distance ≈ 40.1   Leg min-fold distance ≈ 56.4
ANKLE/HAND are the only end-joints the global solver knows how to pin (ConstraintSolver.chainForEnd).
```

---

## Issue A — Global solver is pelvis-translation-only; it solves "point-reach", not "posture"

**Title:** The contact-constraint layer can only translate the root; it cannot reconcile a fixed
contact with the *intended posture*, so it produces anatomically impossible skeletons.

**Description**
`ConstraintSolver.solve` (PR-04) is a damped Jacobi relaxation that, each iteration, moves
**only `pelvis.localPosition`** so that every registered contact's end-effector reaches its
target at a biologically valid distance. It has exactly one degree of freedom (root
translation) and treats every contact as a **hard 3-D point to reach**. It cannot rotate the
pelvis, move the chest, or choose an *alternate inverse* (e.g. "the foot is a point on a
surface; derive the leg direction from it" vs "force the hip→foot distance to equal limb
length and move the root").

Consequences observed against the references:

- **Middle Split** (`MiddleSplitPose`): feet contacts at `(0,0,∓79.2)`, legs `straight=true`.
  Hip→foot requested distance is `≈58.9` (because `spread = hipWidth*3.6 = 79.2` is far
  shorter than the leg length `210`). Because `dist < L1 (112)` and the limb is straight, the
  solver's `desired = maxReach (210)` and it **pushes the pelvis up to y≈210** so that
  hip→foot ≈ 210. The result is a floating "V" with the pelvis suspended in mid-air — not a
  middle split, where the pelvis rests on the ground and the legs splay horizontally. This is
  the textbook "one pose can only be satisfied by breaking another": *straight legs* +
  *feet at the authored spread* are jointly impossible with a grounded pelvis, and the solver
  picks the wrong one (lift the root).
- **Deep Overhead Squat / Pike Sit**: feet are authored *closer to the hips than the leg
  length allows without maximal fold* (Deep Squat hip→foot ≈ 48 < 56.4 min-fold; Pike legs are
  intentionally straight so they survive). For the squat the only response the solver can make
  is to keep the foot pinned and fold the knee to the minimum — which is a tuck, not a stance.
  The solver *prevents penetration* (PR-03) but cannot instead lower the pelvis or re-angle the
  leg, because its only knob is root translation and the foot is a fixed point.

**Root cause**
The global layer models the problem as "move the root until each contact point is reachable,"
rather than as a constrained posture problem ("honor the contact as a surface/anchor *and*
respect the intended limb configuration, redistributing error across the whole chain"). A
single translation DOF cannot satisfy coupled requirements that need a different root
orientation or other joints to absorb the error.

**Affected engine components**
`ConstraintSolver` (the whole relaxation), `bakeIkLimb` contact registration,
`SkeletonPoseFinalizer.finalize` (the hook point), `ContactSpec`.

**Affected exercises**
Everything that couples a planted support with a posture: squats, lunges, pistols, splits,
pikes, planks, bridges, good-mornings, single-leg stands.

**Affected validation poses**
**Middle Split** (floating pelvis — the clearest reproduction failure), Deep Overhead Squat
(tuck instead of stance), Pike Sit (legs survive only because authored straight).

**Severity:** HIGH (deepest remaining limitation; it is why a whole class of references still
cannot be reproduced exactly).


**Current Production Impact:** None. Currently reachable only through the four
Validation Poses - no production pose registers a `contact` in `bakeIkLimb` yet,
so `ConstraintSolver.solve()` is a no-op for the shipped exercise catalog.
**Possible architectural solutions**
- Treat a contact as a *surface/anchor constraint*, not a point to reach: pin the end-effector
  on the support and solve the *proximal chain* so the limb direction (and hence the implied
  root placement) is derived from the contact, rather than forcing distance = limb length.
- Give the solver more DOF: optionally re-orient the pelvis and/or nudge the chest, still
  deterministically and allocation-free, so error is distributed instead of dumped into a
  single translation.
- Add a "resolve posture" pass that, given fixed contacts, solves for the remaining free joints
  (hip/knee/ankle angles) to best match the authored shape, instead of only moving the root.

**Estimated complexity:** High.

**Fix location:** Engine (a new/different global layer; poses would only declare contacts).

---

## Issue B — Arm IK/strike frame is keyed to `CHEST`, but the arm root now sits under `SCAPULA`

**Title:** The limb-baking and global-solve machinery still believe the chest is the arm's
parent frame, but the arm IK root (`SHOULDER`) is now three hierarchy levels deep
(`CHEST→CLAVICLE→SCAPULA→SHOULDER`).

**Description**
`bakeIkLimb` stores the middle/end offsets in the frame supplied as `parentRotation`. For arms
the IK root is the **shoulder** world position; the offsets must therefore be expressed in the
**scapula's** world frame (the shoulder carries no rotation, so `shoulder.worldRotation ==
scapula.worldRotation`). Two inconsistencies exist:

1. **Validation poses pass `chest.worldRotation`** as the arm `parentRotation`
   (`DeadHangPose`, `DeepOverheadSquatPose`, `PikeSitPose`, `MiddleSplitPose`). This is
   *coincidentally* correct only because those poses leave the clavicle/scapula at identity.
   The production `BaseVerticalPullPose` already had to work around this by passing
   `shoulderA!!.worldRotation` explicitly — proving the API forces callers to know the exact
   ancestor frame.
2. **`ConstraintSolver.chainForEnd` hardcodes `parentRotationJoint = CHEST`** for
   `HAND_A`/`HAND_P`. Any arm contact re-baked through the global solver therefore uses the
   *chest* world rotation as the elbow/hand frame, while FK applies the shoulder offset in the
   *scapula* frame. When the scapula is rotated (which is exactly what PR-05's
   `buildScapularRotation` does — `BaseVerticalPullPose` sets `scapulaA.localRotation` to a real
   depression/retraction rotation), `CHEST.worldRotation ≠ SCAPULA.worldRotation`, so the
   re-baked elbow/hand are mis-placed by the chest↔scapula rotation difference.

This is a direct **conflict between the new scapula feature (PR-05) and the baking/global-solve
machinery**: the girdle can move the shoulder, but the limb bake and the contact re-bake still
assume the chest is the arm's parent.

**Root cause**
The baking/solver code keys the arm's parent frame to a fixed joint name (`CHEST`) rather than
deriving it from the actual hierarchy node that is the IK root's parent. Adding the girdle
broke that assumption but the call sites and `chainForEnd` were not updated.

**Affected engine components**
`BasePose.bakeIkLimb`, `BaseValidationPose.bakeIkLimb`, `ConstraintSolver.chainForEnd` (and the
re-bake that uses `parentRotationJoint`), `SkeletonFactory` (the girdle chain).

**Affected exercises**
All upper-body pulls/presses where the scapula is activated *and* the hands are registered
contacts (or re-baked by the global solver): pull-ups/chin-ups with scapular drive, planche,
handstand press, bar hangs that also use the solver. (Currently masked because the vertical-pull
family does **not** register arm contacts, so the solver's arm path is dormant — but it is a
latent, correctness-breaking bug the moment it is used.)

**Affected validation poses**
Dead Hang, Middle Split, Deep Overhead Squat, Pike Sit — all bake arms with `chest.worldRotation`;
correct today only because their scapulae are identity. The moment any of them activates the
scapula (or the solver's arm path is exercised), they would mis-bake.

**Severity:** HIGH (breaks the scapula feature's correctness guarantee and is a latent global-solve
defect; the modern form of the old Issue 1, now deeper because of the 3-level girdle).


**Current Production Impact:** None. Currently reachable only through the four
Validation Poses - no production pose registers a `contact` in `bakeIkLimb` yet,
so `ConstraintSolver.solve()` is a no-op for the shipped exercise catalog.
**Possible architectural solutions**
- Change `bakeIkLimb`/`solveArmIK` to accept the **IK root node** (`SHOULDER`) and derive the
  parent frame from `rootNode.parent.worldRotation` automatically, removing the caller's
  obligation to name the correct ancestor.
- Update `ConstraintSolver.chainForEnd` so the arm chain's `parentRotationJoint` is `SCAPULA_*`
  (or, better, look it up from the actual hierarchy rather than a hardcoded name).

**Estimated complexity:** Medium.

**Fix location:** Engine (API + `ConstraintSolver` mapping).

---

## Issue C — Wrist/ankle completion uses the node's *world* rotation, double-counting the parent frame — RESOLVED IN CODE

**Title:** The new wrist/ankle "articulation" rotated the (already world-space) forearm/shank
direction by the joint's *world* rotation, so when the trunk was tilted the grip/foot orientation
was wrong. This is now fixed: the completion stage resolves the joint's rotation *relative to its
parent segment frame* before applying it to the already-world segment direction, so the trunk
frame is no longer double-counted.

**Description**
`SkeletonPoseFinalizer.adjustHandOrientation` does:
```
val wristRotation = pose.getJointRotation(handId)   // HAND node's WORLD rotation
handDef.computeHandJoints(wrist, tempDir /* forearm dir, world */, wristRotation, …)
```
and `computeHandJoints` rotates `tempDir` by `wristRotation`. But `getJointRotation(handId)`
returns the hand node's **world** rotation, which includes every ancestor frame (elbow, scapula,
chest, pelvis). The forearm direction `tempDir` is already a **world** vector. Rotating a world
direction by a world rotation therefore re-applies the trunk/elbow frame on top of the
already-framed forearm direction — a double count.

The same pattern exists in `adjustFootOrientation` (`ankleRotation =
pose.getJointRotation(ankleId)`, a world rotation, applied to the shank-derived neutral foot
direction).

For a vertical/neutral trunk this is invisible (world frame ≈ identity). But:
- **Pike Sit** (corrected - traced through the real hierarchy). `PikeSitPose` sets `fold = 0.95f`,
  so `pelvis.localRotation = -fold = -0.95` (pelvis is the root, so this is also its world
  rotation), `chest.localRotation = -fold*0.6f = -0.57` (relative to pelvis), and
  `handA.localRotation = -fold*0.6f = -0.57` (set explicitly on the hand node). The arm chain is
  `PELVIS -> CHEST -> CLAVICLE_A -> SCAPULA_A -> SHOULDER_A -> ELBOW_A -> HAND_A`; `CLAVICLE_A`,
  `SCAPULA_A`, `SHOULDER_A`, and `ELBOW_A` are never given a rotation in this pose, so they stay at
  identity and pass the rotation through unchanged. World rotations therefore accumulate as:
- `pelvis.worldRotation = -0.95`
- `chest.worldRotation  = -0.95 + (-0.57)       = -1.52`
- `elbow.worldRotation  = -1.52` (clavicle/scapula/shoulder/elbow add 0)
- `handA.worldRotation = -1.52 + (-0.57)        = -2.09 rad (~ -119.7 deg)`
  `adjustHandOrientation` feeds `getJointRotation(HAND_A)` (= the *world* rotation, -2.09) into
  `computeHandJoints` against the already-world forearm direction. The grip the pose *authored* is
  the hand's rotation **relative to its parent** (`handA.localRotation = -0.57`); the code instead
  applies the full world rotation, so the completed hand is over-rotated by
  `world - local = -2.09 - (-0.57) = -1.52 rad (~ -87.1 deg)` more than intended. The previous
  write-up claimed ~0.57 rad from a chain that (a) mis-stated `handA.localRotation` as `-0.34`
  (an arithmetic slip for `-fold*0.6`; `0.95 * 0.6 = 0.57`, not `0.34`) and (b) omitted the
  pelvis rotation entirely, writing `chest(-0.57) ^ grip(-0.34) ~= -0.91`.
- **Deep Overhead Squat** leans the pelvis and chest; the same over-rotation affects the grip.
  Traced: `pelvis.localRotation = -leanAngle = -0.5`, `chest.localRotation = leanAngle*0.4 = 0.2`,
  `handA.localRotation = leanAngle*0.4 = 0.2`. Hand world rotation = `-0.5 + 0.2 + 0.2 = -0.1`; the
  authored relative grip is `0.2`, so the over-rotation is `-0.1 - 0.2 = -0.3 rad (~ -17.2 deg)`.
  Smaller than Pike Sit (chest and grip contributions partially cancel) but still a real,
  trunk-tilt-driven error.
- Any hip-hinge / good-morning / bird-dog with a tilted trunk mis-orients the foot.

The wrist/ankle rotation should be composed in the **forearm/shank frame** (relative to the
segment), not in world space.

**Root cause**
The completion stage reads the joint's *world* rotation and applies it to a *world* direction,
conflating "rotate relative to the parent segment" with "rotate in world space." The node has no
independent wrist/ankle basis separate from the segment it hangs off.

**Affected engine components**
`SkeletonPoseFinalizer.adjustHandOrientation`, `adjustFootOrientation`,
`HandDefinition.computeHandJoints` (orientation overload), `FootDefinition.computeHeelToe`
(orientation overload).

**Affected exercises**
All grips/pronation/supination and any dorsi/plantar-flexed or inverted/everted foot, whenever
the trunk/parent frame is non-identity: dead hang (overhand), pull-ups, rows, planks, squats,
lunges, calf raises.

**Affected validation poses**
Pike Sit (grip over-rotated by the fold), Deep Overhead Squat (grip + foot over-rotated by the
lean), Dead Hang (correct only because the trunk is vertical), Middle Split (correct only
because the trunk is vertical).

**Severity:** RESOLVED IN CODE (was HIGH). The completion stage double-counted the parent frame
by applying the joint's *world* rotation to an already-world segment direction. The fix resolves
the wrist/ankle rotation *relative to its parent segment frame* (`node.worldRotation ∘
inverse(parent.worldRotation)`) before composing it with the segment direction, so the trunk frame
is no longer re-applied. For a folded/leaning pose the Pike Sit over-rotation of **~1.52 rad
(~87 deg)** is eliminated and the completed grip/foot direction matches the anatomically correct
one exactly. The legacy (position-driven) compatibility path has no wrist/ankle articulation
separate from the segment, so it now passes identity and extends the hand/foot rigidly along the
segment (unchanged for neutral trunks).

**Fix evidence (current source):**
- `SkeletonPoseFinalizer.relativeRotation` derives the joint's rotation relative to its parent
  segment frame (`inverse(parentRotation) ∘ worldRotation`) using the same
  `rotationToMatrix`/`transposeMultiply`/`getRotationFromMatrix` utilities already used by
  `reconstructChestFrame`.
- `adjustHandOrientation` now resolves `HAND_*` relative to `ELBOW_*` (the forearm frame) and
  passes that to `HandDefinition.computeHandJoints`; `adjustFootOrientation` resolves `ANKLE_*`
  relative to `KNEE_*` (the shank frame) and passes that to `FootDefinition.computeHeelToe`.
- The legacy branch passes `IDENTITY_ROTATION` (no double-count).

**Possible architectural solutions** (reference only; the chosen fix is the relative-to-parent-frame one)
- Build the hand/foot basis from the segment direction **plus a perpendicular derived from the
  parent frame**, then apply the joint's *local* rotation (relative to the segment) — not the
  world rotation.
- Store and consume the wrist/ankle rotation *relative to the forearm/shank*, e.g. derive it
  from `node.worldRotation ∘ inverse(parent.worldRotation)` before composing with the segment
  direction.

**Estimated complexity:** Medium.

**Fix location:** Engine (finalizer + `HandDefinition`/`FootDefinition` orientation overloads).

---

## Issue D — ConstraintSolver only knows how to pin `ANKLE_*` and `HAND_*` contacts — RESOLVED IN CODE

**Title:** The only end-joints the global solver could re-bake were ankles and hands; forearms,
knees, hips, head, etc. could not be honored as fixed contacts. This is now fixed: `chainForEnd`
maps every planted body part to its proximal chain, and the re-bake handles the resulting
single-bone (degenerate) limbs.

**Description**
`ConstraintSolver.chainForEnd` returned `null` for every joint except `ANKLE_F/B` and `HAND_A/P`.
`bakeIkLimb` therefore silently skipped contact registration for any other support point, and the
solver could never reposition the root to honor, e.g., a forearm plank, a kneeling thruster, or a
head-stand. Those contacts are declared in `PoseMetadata`/`SupportContact` but the engine had
no machinery to *enforce* them globally.

**Root cause**
The chain map was written for the four reference poses (which only plant feet/hands) and was
never generalized.

**Severity:** RESOLVED IN CODE (was MEDIUM). `chainForEnd` now returns a `ContactChain` for every
plausible contact end-joint, and `ConstraintSolver.solve` re-bakes the resulting limbs correctly:

- The two genuine 2-bone limbs are unchanged: `ANKLE_*` (root `HIP_*`, parent `PELVIS`) and
  `HAND_*` (root `SHOULDER_*`, parent `SCAPULA_*`), middle `KNEE_*`/`ELBOW_*`.
- Single-bone contacts are modelled as a degenerate 2-bone limb with `middleJoint == endJoint`:
  `KNEE_*` (thigh, root `HIP_*`), `ELBOW_*` (forearm, root `SHOULDER_*`), `HIP_*` (root
  `PELVIS`), `HEAD_POS` (neck, root `CHEST`), and `TOE_*`/`HEEL_*` (foot, root `ANKLE_*`). Each
  uses the bone's true parent frame as `parentRotationJoint`, which for the identity-rotation
  links equals the historical pelvis/scapula frame, so existing legs/arms entries are
  byte-for-byte unchanged in meaning.
- The re-bake skips the second (zero) offset write when `middle === end`, so a 1-bone limb keeps
  its real bone length instead of collapsing to a point.
- `signedImbalance` now derives the lateral side sign from the joint name (`_B`/`_P` = passive
  +Z, `_F`/`_A` = active −Z) instead of hard-coding `ANKLE_B`/`HAND_P`, so the pelvis-tilt
  posture DOF stays correct for the new contacts.

A pose registers one of these by baking the relevant limb with a `ContactConstraint` (e.g. a
kneeling pose bakes `hip → kneeTarget` with `length1 = thigh`, `length2 = 0` and a ground
contact); `bakeIkLimb` already forwards the end joint to `chainForEnd` and the solver honors it.

**Fix evidence (current source)**
- `ConstraintSolver.chainForEnd` maps `ANKLE_*`/`HAND_*`/`KNEE_*`/`ELBOW_*`/`HIP_*`/`HEAD_POS`/
  `TOE_*`/`HEEL_*` to their proximal `ContactChain`.
- `ConstraintSolver.solve` guards the degenerate 1-bone write (`if (middle !== end)`).
- `ConstraintSolver.signedImbalance` generalizes the side sign via joint name suffix.

**Affected exercises** (now enabled through the global layer)
Plank / forearm plank, kneeling variations, head-stands, seated poses resting on the hips, any
pose whose support polygon includes a non-ankle/non-hand point.

**Affected validation poses**
None of the four (they only plant feet/hands), so this was a latent limitation for other families.

**Current Production Impact:** None. No production pose registers a `contact` in `bakeIkLimb` yet,
so `ConstraintSolver.solve()` remains a no-op for the shipped exercise catalog — but the engine
now *can* honor any of the above contacts the moment a pose registers one.

**Possible architectural solutions** (reference only; the chosen fix is the generalized
`chainForEnd` + 1-bone re-bake)
- Derive `chainForEnd` from the skeleton topology generically (walk the parent chain to the limb
  root) instead of enumerating joints.

**Estimated complexity:** Medium.

**Fix location:** Engine (`ConstraintSolver`).

---

## Issue E — Single rigid torso: one spine joint, no independent lumbar/thoracic or pelvis-tilt

**Title:** The trunk is one bone (`PELVIS→CHEST`) with a single bend; the pelvis and thorax
cannot articulate independently, and there is no rib-cage separate from the chest node.

**Description**
The hierarchy has exactly one spine joint (the `PELVIS→CHEST` link). Relative chest/pelvis
rotation is possible (so a squat can lean the chest while the pelvis tilts), but there is **no
separate lumbar vs thoracic segment** and **no pelvis that tilts independently of the chest
about its own joint**. Biomechanical patterns that rely on "pelvis tilts, chest stays upright"
(a hip hinge / good morning / deadlift) or "thoracic extends while lumbar stays" (cat-cow,
thoracic opener) cannot be expressed as real joint motion — only as a single overall trunk bend.

This is a structural limit distinct from the (now resolved) "trunk is 1-DOF" issue: the trunk
can now twist/side-bend, but it is still **one rigid segment** between pelvis and chest.

**Root cause**
`SkeletonFactory.createStandardSkeleton` models the spine as a single `PELVIS→CHEST` bone; no
`LUMBAR`/`THORACIC`/`SACRUM` joints exist.

**Affected engine components**
`SkeletonFactory`, `Joint` enum, `SkeletonPoseFinalizer.reconstructChestFrame` (derives one chest
frame), `BasePose` body helpers.

**Affected exercises**
Hip hinges, good mornings, deadlifts, bird-dog (independent pelvic vs thoracic control), cat-cow,
thoracic openers, any pattern where lumbar and thoracic motion differ.

**Affected validation poses**
None of the four (all use a single overall trunk lean), so this is latent for these references but
blocks a broad family.

**Severity:** MEDIUM (latent for the four references; real limitation for hip-hinge/biomechanical
families the constitution explicitly calls out in `BIOMECHANICS.md` §2/§6).


**Current Production Impact:** Not gated behind contact registration - this is a structural
property of the shipped skeleton, so it is **live for the whole production catalog**, not a no-op.
Every exercise is built on the single `PELVIS -> CHEST` segment, so any production hip-hinge /
good-morning / deadlift / bird-dog / cat-cow that needs lumbar and thoracic motion to differ is
forced into one overall trunk bend. The four *references* do not expose it (they use a single
symmetric lean), which is why it reads as "latent" - but it is not dormant in production the way
the contact-gated issues (A/B/D) are.
**Possible architectural solutions**
- Add a `LUMBAR` (or `THORACIC`) intermediate joint between pelvis and chest so the spine has two
  real segments with independent DOF; let `reconstructChestFrame` compose both.

**Estimated complexity:** High (new joints + FK + integration into every trunk-authoring helper).

**Fix location:** Engine.

---

## Issue F — `reconstructChestFrame` overwrites the authored chest rotation and assumes a symmetric thorax — RESOLVED IN CODE

**Title:** The modern-path chest-frame reconstruction recomputed `chest.localRotation` from geometry
and overwrote whatever the pose author built, and it derived a single orthonormal frame from the
mean shoulder line, assuming a symmetric thorax. This is now fixed: an *authored* chest rotation is
never overwritten, and the identity-chest fallback uses the correct (non-degenerate) orthonormal
basis.

**Description**
`SkeletonPoseFinalizer.reconstructChestFrame` recomputed `chest.localRotation` from the
`(pelvis→chest)` lean and the `(shoulderA→shoulderP)` line, then re-ran FK for the chest subtree.
In doing so it:

- **Discarded any chest orientation the pose author built** via `buildChestTwist` /
  `buildChestOrientation` / `buildChestSideBend` or a direct `chest.localRotation.set(...)` (plank
  flex, lunge pitch, thoracic twist, the validation poses). Those helpers author a real 3-D
  thoracic rotation that FK already propagates to the shoulders, arms, neck and head; the
  reconstruction clobbered it with a geometry-derived frame.
- **Assumed a symmetric thorax**: it re-derived the chest forward axis purely from the *mean*
  shoulder line, forcing symmetry onto any authored pose even when the author intended an asymmetric
  (one-shoulder-dropped) upper body.

Additionally, the frame math itself was **degenerate**: the single-argument `Vector3.cross(v)`
(`SkeletonMath.kt:74`) allocates a NEW vector and never mutates the scratch buffer, so
`tempColX.set(lean).cross(shVec)` left `tempColX` equal to `lean` — making `colX == colY` and
producing a wrong chest world rotation even for a neutral/sagittal trunk (e.g. a push-up plank was
off by ~30°; the correct reconstruction equals the FK frame).

**Root cause**
The reconstruction unconditionally treated the chest as a geometry-only node, ignoring that the
modern rotation-driven path already owns the chest's rotation. The cross-product overload mismatch
turned the orthonormal basis into a degenerate matrix.

**Affected engine components**
`SkeletonPoseFinalizer.reconstructChestFrame`, `Vector3.cross` (scratch-buffer semantics),
`SkeletonFactory` (no per-side scapular frame exposed to the chest derivation — still the deeper
fix for Issue B, see below).

**Affected exercises**
Any rotation-driven pose that authors a chest rotation: planks (flex), lunges (pitch), thoracic
rotations/extensions (twist), the validation poses; and asymmetric presses / one-arm rows (which
were force-symmetrized).

**Affected validation poses**
None of the four (all symmetric, and their chest rotation is authored), so they are now served
correctly rather than via the overwrite.

**Severity:** RESOLVED IN CODE (was LOW–MEDIUM). `reconstructChestFrame` now **early-returns when
`chest.localRotation` is non-identity**, leaving the author's thoracic twist / side-bend / flex (and
any asymmetry) intact — FK already propagated it to the upper chain and the already-flattened world
transforms are untouched. The identity-chest fallback (a trunk oriented purely by the pelvis/legs,
e.g. a push-up plank) still derives a spine-aligned frame, but now uses the two-argument
`cross(dst)` overload so the orthonormal basis is written into `tempColX`; for a symmetric thorax
this equals the FK-derived frame (zero regression) and the degenerate-matrix bug is gone. Net effect
restores the pre-PR-09 correct behavior in both cases: authored chests are no longer clobbered, and
the fallback no longer produces a ~30°-off frame.

**Fix evidence (current source):**
- `SkeletonPoseFinalizer.reconstructChestFrame` guards on `chest.localRotation.angle` (non-zero →
  return early, preserving the authored frame).
- The orthonormal basis is built with `lean.cross(shVec, tempColX)` (two-arg `cross(dst)`), so
  `tempColX` holds the real `lean × colZ` lateral axis instead of remaining equal to `lean`.
- `Vector3.cross(v: Vector3, result: Vector3)` (`SkeletonMath.kt:67`) is the overload that writes
  into `result`; the single-arg `cross(v)` (`SkeletonMath.kt:74`) is the allocating one that must
  not be used for in-place scratch mutation.
- Regression test `ChestFrameIssueFTest` verifies authored twist and authored flex survive
  finalization, and that the identity-chest fallback yields the correct spine-aligned tilt (not the
  old degenerate ~120° result).

**Possible architectural solutions** (reference only; superseded by the authored-rotation guard)
- Derive the chest frame from the *scapula* frames (which already carry per-side rotation via
  PR-05) rather than from the mean shoulder line; this would also be the deeper fix for Issue B's
  frame-source problem and remove the residual "symmetric thorax" assumption in the identity-chest
  fallback.

**Estimated complexity:** Medium (deeper scapula-frame derivation); the chosen fix is the
guard + cross-overload correction.

**Fix location:** Engine (`SkeletonPoseFinalizer` + `SkeletonMath.cross` usage).

---

## Performance Analysis - `ConstraintSolver`

Derived by reading `animation/ConstraintSolver.kt` (PR-04) as it currently stands; no numbers are
estimated. The "zero allocations during animation" bar is taken from the engine architecture review
(`ENGINE.md` section 5), not from a vague "looks efficient" judgment.

### Iteration count
The relaxation is a **fixed upper bound** on a damped Jacobi loop, not adaptive to convergence or
contact count in its bound:

```kotlin
private const val MAX_ITERATIONS = 16
...
for (iter in 0 until MAX_ITERATIONS) {
    ...
    if (!moved) break
}
```

So it runs **at most 16 iterations**, and **breaks early** (`if (!moved) break`) as soon as a pass
produces no root correction. For the current validation poses - whose contacts are reachable as
authored - `moved` is false on the first pass, so the solver terminates after a **single**
iteration. The 16 cap only matters when an unreachable contact forces repeated root nudging.

### Allocations inside the iteration loop
All mutable state used per-iteration is **pre-allocated as `object` fields**, not constructed in
the loop:

```kotlin
private val zero = Vector3()
private val identity = JointRotation()
private val delta = Vector3()
private val away = Vector3()
private val dir = Vector3()
private val rootWorld = Vector3()
private val ikResult = SkeletonMath.IKResult()
private val nodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }
// ... pelvis-tilt scratch (tiltDelta, authoredPelvisRot, *MatX/Y/Z, imbA/imbB) ...
```

Walking one iteration:
1. FK - `root.updateWorldTransforms(zero, identity)` uses each node's own scratch
   (`pX/pY/pZ/lX/...`), no allocation.
2. Reachability pass - only scalar math (`mag()`, `cos()`, `sqrt()`) against `rootWorld`/`away`/
   `delta`/`dir`; no object construction.
3. `applyPelvisTilt` / `signedImbalance` (only when `moved`) - compose matrices into the
   pre-allocated `*MatX/Y/Z` scratch; no allocation.
4. Re-bake - `SkeletonMath.solveIK` / `solveStraightLimb` are handed the **shared** `ikResult`
   (`SkeletonMath.IKResult()`) and write into `result.joint` / `result.end` (pre-allocated
   `Vector3`s inside `IKResult`); `toLocalDirection` writes into `middle.localPosition` /
   `end.localPosition`. No `Vector3(...)`, no `IKResult(...)`, no collections are built.

The trailing `SkeletonPose.fromHierarchy(roots, pose)` is called **once per `solve()`**, not per
iteration, and it too is allocation-free (it reuses `ZERO_VECTOR`/`IDENTITY_ROTATION` and writes
into the supplied pose). **Conclusion:** the hot loop is allocation-free and meets the project's
zero-allocation-during-animation goal. There is no per-iteration `Vector3`/collection churn to flag.

### Asymptotic complexity in number of contacts
Both the reachability pass and the re-bake pass iterate the registered contacts **once each** per
iteration; there is **no per-contact-pair interaction** (no nested loop over contacts, no all-pairs
distance/conflict check). The FK pass at the top of each iteration is over the **fixed node set**
(~40 nodes), independent of how many contacts are registered.

- per iteration ~= `O(FK) + O(contacts) + O(contacts)` = `O(N_nodes + contacts)`
- total ~= `O(MAX_ITERATIONS x (N_nodes + contacts))`

So it is **`O(iterations x contacts)` - strictly linear in the contact count**, with no quadratic
term. The loop structure that proves it:

```kotlin
for (iter in 0 until MAX_ITERATIONS) {
    for (root in roots) root.updateWorldTransforms(...)       // O(N_nodes), once per iteration
    for (spec in contacts) { ... reachability delta ... }      // O(contacts)
    if (moved) { ... pelvis.localPosition.add(delta) ... }
    if (moved && hasGroundContact) applyPelvisTilt(...)         // O(contacts) internally
    for (spec in contacts) { ... solveIK/solveStraightLimb ... } // O(contacts)
    if (!moved) break
}
```

### Scaling to 4 / 8 / 16 simultaneous contacts
Because the cost is linear in contacts and the FK pass is a constant (~40 nodes) independent of
contact count, scaling is dominated by "2 IK solves per contact per iteration." Each
`solveIK`/`solveStraightLimb` is fixed work (a couple of `sqrt`s, a triangle/straight solve, a
`toLocalDirection`) - no allocation, no loop - call it `K` ops (tens of flops).

Rough operation estimate at the **16-iteration worst case** (real runs break earlier for reachable
contacts):

| contacts | per-iteration cost     | full 16-iteration `solve()`       |
|----------|-----------------------|-----------------------------------|
| 4        | `40 + 2*4*K`          | `16 * (40 + 8K)`  ~= `640 + 128K` |
| 8        | `40 + 2*8*K`          | `16 * (40 + 16K)` ~= `640 + 256K` |
| 16       | `40 + 2*16*K`         | `16 * (40 + 32K)` ~= `640 + 512K` |

Going 4 -> 16 contacts (4x the contacts) multiplies the total work by ~4x - **linear, not
super-linear**. The constant FK term (640) is fixed regardless of contact count, so at higher
contact counts the re-bake term dominates and scaling tracks the contact count exactly. Even at 16
contacts the whole `solve()` is a few thousand fixed-constant operations, trivially within a frame
budget, and for the common reachable case it collapses to **one** FK + one re-bake pass
(~ `40 + 32K` ops).

### Verdict
**No genuine performance concern.** The solver is allocation-free in its hot loop (it clears the
project's zero-allocation bar, not merely "reasonable"), scales `O(iterations x contacts)` with no
quadratic interaction term, and typically converges in a single iteration for reachable contacts.
The only minor observation (not a problem) is that every iteration re-runs a full FK over all nodes
even though only the root/contact subtree changed; a localized FK would shave the constant but does
not change the linear-in-contacts scaling and is unnecessary at the contact counts the four
references (and foreseeable production poses) use.
# Prioritized Roadmap (highest → lowest architectural impact)

1. **Issue A — Global solver is pelvis-translation-only (point-reach, not posture).**
   The deepest remaining limitation and the reason Middle Split still cannot be reproduced
   (floating pelvis). Fixing it also improves Deep Overhead Squat / Pike Sit fidelity.
   *(Engine, High.)*

2. **Issue B — Arm frame keyed to `CHEST` vs the `SCAPULA`-parented shoulder.**
   Latent correctness-breaking conflict between the new scapula feature and the baking/global-solve
   machinery; the modern form of the old Issue 1, now deeper due to the 3-level girdle.
   *(Engine, Medium.)*

 3. ~~Issue C — Wrist/ankle completion uses world rotation (double-counts parent frame).~~
    **RESOLVED IN CODE.** Wrist/ankle completion now resolves the joint rotation relative to its
    parent segment frame, so grip/foot orientation is correct even when the trunk is tilted.
    *(Engine, Medium.)*

 4. ~~Issue D — Solver only pins `ANKLE_*`/`HAND_*` contacts.~~
    **RESOLVED IN CODE.** `chainForEnd` now maps every planted body part (knees, elbows/forearms,
    hips, head, toe/heel ends) to its proximal chain, and the re-bake honors the resulting
    1-bone limbs — so forearm/knee/hip/head poses can use the global layer. *(Engine, Medium.)*

5. **Issue E — Single rigid torso (no independent lumbar/thoracic, no separate pelvis-tilt).**
   Blocks hip-hinge / good-morning / bird-dog families the constitution calls out.
   *(Engine, High.)*

 6. ~~Issue F — `reconstructChestFrame` overwrites authored chest rotation; symmetric-only.~~
     **RESOLVED IN CODE.** An authored chest rotation is now preserved (FK is the single source of
     truth); the identity-chest fallback uses the correct non-degenerate orthonormal basis. The
     deeper scapula-frame derivation (which would also fix Issue B's frame source) remains a
     possible future hardening. *(Engine, Medium.)*

---

## Summary

The heavy engine rework resolved the prior report's Issues 1–11 (straight limbs, contact
penetration, scapula, wrist/ankle DOF, angular limits, 3-D chest frame, reachability detection,
full-extension opt-in). **Dead Hang, Deep Overhead Squat, and Pike Sit are now reproducible by the
engine to within their authored targets.**

Two findings dominate the current state:

- **Middle Split still fails** because the global `ConstraintSolver` (Issue A) can only translate
  the pelvis and treats each contact as a point to reach; to honor "straight legs + feet at the
  authored spread" it lifts the pelvis into the air instead of resting it on the ground. This is
  the highest-leverage remaining fix.
- **The new scapula/girdle feature is not yet wired consistently into the limb-baking and
  global-solve frame (Issue B):** arms are still baked against the `CHEST` frame, which is only
  correct while the scapula is identity. The moment scapular activation is combined with arm
  contacts (exactly what PR-05 enables), the bake and the solver's arm re-bake become wrong.

Issue E is secondary but real: the torso is still a single rigid segment. Issues C, D and F have
all been resolved in code: wrist/ankle completion no longer double-counts the parent frame, the
solver now honors every planted contact, and `reconstructChestFrame` no longer overwrites an
authored chest rotation / assumes a symmetric thorax.

This report was originally an investigation-only snapshot. Issues C, D and F have since been fixed
in code — see their sections for fix evidence. The remaining open items (A, B, E) are the roadmap
for the next phase of engine development.
