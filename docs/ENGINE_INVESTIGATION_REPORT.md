# ENGINE INVESTIGATION REPORT — Fundamental Biomechanical Limitations

> **Scope / stance:** Investigation only. No code, constants, targets, or pose logic were
> modified. The four validation poses (Dead Hang, Deep Overhead Squat, Pike Sit, Middle Split)
> are treated as fixed anatomical references per `VALIDATION.md`. Numerical claims below were
> derived directly from the *current* source (`SkeletonDefinition.DEFAULT_ADULT`,
> `SkeletonMath`, `ConstraintSolver`, `SkeletonPoseFinalizer`, and the four pose files).

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

**Possible architectural solutions**
- Change `bakeIkLimb`/`solveArmIK` to accept the **IK root node** (`SHOULDER`) and derive the
  parent frame from `rootNode.parent.worldRotation` automatically, removing the caller's
  obligation to name the correct ancestor.
- Update `ConstraintSolver.chainForEnd` so the arm chain's `parentRotationJoint` is `SCAPULA_*`
  (or, better, look it up from the actual hierarchy rather than a hardcoded name).

**Estimated complexity:** Medium.

**Fix location:** Engine (API + `ConstraintSolver` mapping).

---

## Issue C — Wrist/ankle completion uses the node's *world* rotation, double-counting the parent frame

**Title:** The new wrist/ankle "articulation" rotates the (already world-space) forearm/shank
direction by the joint's *world* rotation, so when the trunk is tilted the grip/foot orientation
is wrong.

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
- **Pike Sit** folds the chest ≈ −0.57 rad and sets `handA.localRotation = −fold*0.6 ≈ −0.34`;
  the hand's *world* rotation is `chest(−0.57) ∘ grip(−0.34) ≈ −0.91`, so the completed hand is
  rotated ~0.57 rad more than the authored grip — the palm/fingertip orientation is wrong.
- **Deep Overhead Squat** leans the pelvis and chest; the same over-rotation affects the grip.
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

**Severity:** MEDIUM (visible orientation error in folded/leaning poses; the "articulation" is
only correct for neutral trunks).

**Possible architectural solutions**
- Build the hand/foot basis from the segment direction **plus a perpendicular derived from the
  parent frame**, then apply the joint's *local* rotation (relative to the segment) — not the
  world rotation.
- Store and consume the wrist/ankle rotation *relative to the forearm/shank*, e.g. derive it
  from `node.worldRotation ∘ inverse(parent.worldRotation)` before composing with the segment
  direction.

**Estimated complexity:** Medium.

**Fix location:** Engine (finalizer + `HandDefinition`/`FootDefinition` orientation overloads).

---

## Issue D — ConstraintSolver only knows how to pin `ANKLE_*` and `HAND_*` contacts

**Title:** The only end-joints the global solver can re-bake are ankles and hands; forearms,
knees, hips, head, etc. cannot be honored as fixed contacts.

**Description**
`ConstraintSolver.chainForEnd` returns `null` for every joint except `ANKLE_F/B` and `HAND_A/P`.
`bakeIkLimb` therefore silently skips contact registration for any other support point, and the
solver can never reposition the root to honor, e.g., a forearm plank, a kneeling thruster, or a
head-stand. Those contacts are declared in `PoseMetadata`/`SupportContact` but the engine has
no machinery to *enforce* them globally.

**Root cause**
The chain map was written for the four reference poses (which only plant feet/hands) and was
never generalized.

**Affected engine components**
`ConstraintSolver.chainForEnd`, `ConstraintSolver.solve` (early-out on null chain),
`bakeIkLimb` (contact registration guard).

**Affected exercises**
Plank / forearm plank, kneeling variations, head-stands, seated poses resting on the hips, any
pose whose support polygon includes a non-ankle/non-hand point.

**Affected validation poses**
None of the four (they only plant feet/hands), so this is a latent limitation for other families.

**Severity:** MEDIUM (latent; blocks an entire class of supported poses from using the global layer).

**Possible architectural solutions**
- Extend `chainForEnd` (or derive it from the skeleton topology generically) to cover knees,
  forearms, hips, head, and custom contacts, each with its correct proximal `rootJoint` and
  `parentRotationJoint`.

**Estimated complexity:** Medium.

**Fix location:** Engine.

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

**Possible architectural solutions**
- Add a `LUMBAR` (or `THORACIC`) intermediate joint between pelvis and chest so the spine has two
  real segments with independent DOF; let `reconstructChestFrame` compose both.

**Estimated complexity:** High (new joints + FK + integration into every trunk-authoring helper).

**Fix location:** Engine.

---

## Issue F — `reconstructChestFrame` overwrites the authored chest rotation and assumes a symmetric thorax

**Title:** The modern-path chest-frame reconstruction derives a single orthonormal frame from the
mean shoulder line and overwrites whatever chest rotation the pose authored; it cannot represent
an asymmetric (one-shoulder-dropped) thorax.

**Description**
`SkeletonPoseFinalizer.reconstructChestFrame` recomputes `chest.localRotation` from
`(pelvis→chest)` lean and `(shoulderA→shoulderP)` line, then re-runs FK for the chest subtree.
This **does** correctly *preserve* twist/side-bend that is already present in the world positions
of the shoulders (so old Issue 9 is genuinely resolved), but:

- It **discards any chest orientation authored independently of the shoulder line** (e.g. a chest
  rotation set directly that is not reflected in the shoulder positions).
- It derives a **single symmetric frame** from the *mean* shoulder line, so an asymmetric upper
  body (one shoulder depressed/rotated more than the other — common in pressing and in compensatory
  loading) cannot be represented; the reconstruction forces symmetry.

For the four symmetric references this is harmless, but it is a limitation for asymmetric
upper-body poses.

**Root cause**
The reconstruction builds one orthonormal basis from the global shoulder line rather than tracking
per-side scapular frames (which would also resolve Issue B's scapula-frame problem).

**Affected engine components**
`SkeletonPoseFinalizer.reconstructChestFrame`, `SkeletonFactory` (no per-side scapular frame
exposed to the chest derivation).

**Affected exercises**
Asymmetric presses, one-arm rows, any pose with unequal shoulder/scapular setting.

**Affected validation poses**
None of the four (all symmetric), so latent.

**Severity:** LOW–MEDIUM.

**Possible architectural solutions**
- Derive the chest frame from the *scapula* frames (which already carry per-side rotation via
  PR-05) rather than from the mean shoulder line; this also fixes Issue B's frame source.

**Estimated complexity:** Medium.

**Fix location:** Engine.

---

# Prioritized Roadmap (highest → lowest architectural impact)

1. **Issue A — Global solver is pelvis-translation-only (point-reach, not posture).**
   The deepest remaining limitation and the reason Middle Split still cannot be reproduced
   (floating pelvis). Fixing it also improves Deep Overhead Squat / Pike Sit fidelity.
   *(Engine, High.)*

2. **Issue B — Arm frame keyed to `CHEST` vs the `SCAPULA`-parented shoulder.**
   Latent correctness-breaking conflict between the new scapula feature and the baking/global-solve
   machinery; the modern form of the old Issue 1, now deeper due to the 3-level girdle.
   *(Engine, Medium.)*

3. **Issue C — Wrist/ankle completion uses world rotation (double-counts parent frame).**
   Grip/foot orientation is wrong whenever the trunk is tilted. *(Engine, Medium.)*

4. **Issue D — Solver only pins `ANKLE_*`/`HAND_*` contacts.**
   Whole classes of supported poses (forearm/knee/hip/head) cannot use the global layer.
   *(Engine, Medium.)*

5. **Issue E — Single rigid torso (no independent lumbar/thoracic, no separate pelvis-tilt).**
   Blocks hip-hinge / good-morning / bird-dog families the constitution calls out.
   *(Engine, High.)*

6. **Issue F — `reconstructChestFrame` overwrites authored chest rotation; symmetric-only.**
   Limits asymmetric upper-body poses. *(Engine, Medium.)*

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

Issues C–F are secondary but real: wrist/ankle orientation double-counts the parent frame when
the trunk is tilted (C), the solver cannot pin non-hand/foot contacts (D), the torso is still a
single rigid segment (E), and the chest-frame reconstruction is symmetric-only (F).

No code was changed. This report is the roadmap for the next phase of engine development.
