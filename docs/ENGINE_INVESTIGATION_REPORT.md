# ENGINE INVESTIGATION REPORT — Fundamental Biomechanical Limitations

> Investigation only. No code, no constants, no targets, no pose logic were modified.
> The four validation poses (Dead Hang, Deep Overhead Squat, Pike Sit, Middle Split)
> are treated as fixed anatomical references per `VALIDATION.md`. Where a concrete
> numerical claim is made, it was derived from the actual constants in
> `SkeletonDefinition` (`HumanSkeletonDefinition`) and the four pose files.

## Reference numbers (from `SkeletonDefinition.DEFAULT_ADULT`)

```
torso = 120   neck = 18   thigh = 112   shin = 98   foot = 35
upperArm = 80 forearm = 66 shoulderWidth = 46 hipWidth = 22
IKConstraint: minFlexion = 30°  maxExtensionRatio = 0.98
```

Arm chain: `L1+L2 = 146` → reachable `[40.1 .. 143.1]` (min/max from the solver).
Leg chain: `L1+L2 = 210` → reachable `[56.0 .. 205.8]`.

Measured hip→ankle / shoulder→hand distances (engine units):

| Pose | Arm A | Arm P | Leg F | Leg B | Verdict |
|------|-------|-------|-------|-------|---------|
| Dead Hang | – | – | 200.8 | 200.8 | reachable (torso angle 0) |
| Deep Overhead Squat | 143.0 | 143.0 | **48.0** ⚠ (under-clamp 8.1 → foot y ≈ −5) | **48.0** ⚠ | legs unreachable + ground penetration |
| Pike Sit | 132.8 | 132.8 | **209.0** ⚠ (over-clamp 3.2) | **209.0** ⚠ | legs not perfectly straight; arms mis-baked (~120°) |
| Middle Split | **46.0** | **46.0** | **58.9** | **58.9** | both limbs forced into near-max fold, not extended |

---

## Issue 1 — Frame-relative limb baking trusts a hand-fed inverse-Z scalar instead of the real parent world transform

**Description**
`bakeIkLimb` (in `SkeletonMath`/`BasePose`/`BaseValidationPose`) reconstructs the
middle/end nodes' `localPosition` by rotating the world-space IK offset by a caller
supplied `localRotationAngle` about the fixed `axisZ` (0,0,1). For the stored
`localPosition` to survive the subsequent FK pass (`SkeletonNode.updateWorldTransforms`
rotates every `localPosition` by the **parent's world rotation**), `localRotationAngle`
must equal *the negative of the parent's true world-rotation angle*. The value is never
read from the parent node; it is a manual scalar the caller must keep in sync by hand.

This only works when (a) the parent's world rotation is a pure Z rotation and (b) the
caller passes exactly its negative. The production `BaseVerticalPullPose` does the
summation by hand (`invChestZ = -(torsoPitch + chestFlex)`), but the validation poses
do not:

- **Dead Hang / Middle Split**: torso angle = 0 ⇒ correct only by coincidence.
- **Deep Overhead Squat**: arms pass `leanAngle*0.4` (chest *local* rotation) while the
  chest world angle is `-leanAngle + leanAngle*0.4 = -0.6·leanAngle`; correct inverse is
  `+0.6·leanAngle`. Error ≈ `0.2·leanAngle ≈ 0.10 rad (5.7°)` of arm mis-placement.
- **Pike Sit**: arms pass `-fold*0.6 = -0.57`, but the chest world angle is
  `-fold - fold*0.6 = -1.52`; correct inverse is `+1.52`. The reconstructed arm is
  rotated by `R_parent · localRot = rotZ(-1.52)·rotZ(-0.57) = rotZ(-2.09)` instead of
  `rotZ(0)` — **≈120° of error**, i.e. the reaching arms are placed wildly wrong.

**Root cause**
The limb-baking API conflates "parent-local rotation" with "parent-world rotation" and
exposes a fragile scalar. The correct inverse transform is available (the parent
node's `worldRotation`, a full 3D axis-angle) but is not used. The mechanism also
silently breaks for any non-Z parent rotation (twist about Y, side-bend about X).

**Affected engine components**
`SkeletonMath.solveIK` (rotation-driven overloads), `BasePose.bakeIkLimb`,
`BaseValidationPose.bakeIkLimb`, `SkeletonNode.updateWorldTransforms` (FK assumes the
stored `localPosition` is already in the parent frame).

**Affected exercises**
All IK-baked limbs; most visible wherever the trunk is leant (squats, hinges, pikes,
good-mornings, overhead work, deadlifts).

**Affected validation poses**
Deep Overhead Squat (arm error ~5.7°), Pike Sit (arm error ~120° — catastrophic),
Dead Hang & Middle Split (correct only because torso angle happens to be 0).

**Severity**: HIGH (catastrophic for Pike Sit; latent everywhere a trunk lean exists).

**Possible architectural solutions**
- Make `bakeIkLimb` accept the parent `SkeletonNode` (or its `worldRotation`) and derive
  the inverse transform from the *actual* world rotation (full 3D, not Z-only). Remove
  the scalar entirely.
- Better: after solving in world space, write the middle/end nodes' world positions and
  let a single `worldToLocal` pass (using the real parent world transform) compute
  `localPosition`. This removes the caller's obligation to track frame angles.

**Estimated complexity**: Medium.

**Fix location**: Engine (the scalar is engine API; correcting it fixes every caller,
including the validation poses, automatically).

---

## Issue 2 — No straight / rigid-segment limb mode: 2-bone IK folds the joint whenever the end-effector is inside the reachable sphere

**Description**
The only limb primitive is the analytical 2-bone IK (`solveIK`), which always solves a
*triangle* and therefore always bends the middle joint. When the authored target is
closer to the root than `L1+L2` (anything but a fully straight limb), the knee/elbow
bends. A "straight limb" is only the boundary case `dist = (L1+L2)·ratio`; the engine
has no primitive that pins a limb straight to an arbitrary reachable target.

Consequences in the references:
- **Middle Split**: hip→ankle = 58.9 and shoulder→hand = 46.0, both just above the
  fold minima (56.0 / 40.1). The engine produces *maximally folded* legs and arms — the
  opposite of the intended spread-eagle straight split.
- **Pike Sit**: leg target 209.0 > 205.8 ⇒ over-clamped 3.2 ⇒ legs cannot be perfectly
  straight (forced ~2% bend).
- **Dead Hang**: arms 140.9 (96.5% extension) and legs 200.8 (95.6%) ⇒ always a few %
  bent even though the reference is a dead-straight hang.
- **Deep Overhead Squat**: arms 143.0 ≈ max 143.1 ⇒ at the clamp boundary.

`SkeletonMath.solveNearStraightLimb` exists but is not wired into the baking path used
by either the validation poses or (`bakeIkLimb`), so the straight case is never reliably
reached.

**Root cause**
The limb model is "triangle IK only." There is no notion of a rigid, fully-extended
segment aimed at a target; extension is expressed indirectly through the distance clamp.

**Affected engine components**
`SkeletonMath.solveIK`, `IKConstraint` (maxExtensionRatio is the only straightness knob),
`bakeIkLimb` / `solveNearStraightLeg` (under-used), `SkeletonPoseFinalizer` (no
straight-limb completion).

**Affected exercises**
Every straight-limb configuration: dead hangs, planks, pikes, splits, straight-leg
raises, supports, hollow holds, superman.

**Affected validation poses**
Middle Split (legs + arms folded), Pike Sit (legs over-clamped), Dead Hang (limbs not
straight), Deep Overhead Squat (arms at clamp).

**Severity**: HIGH (Middle Split literally cannot be reproduced as a straight split).

**Possible architectural solutions**
- Add a first-class "straight limb" solve: given root, target, total length, and plane
  hint, emit a collinear two-segment chain (respecting a true 1.0 extension when
  anatomically valid) and route it through `bakeIkLimb`.
- Generalize `solveNearStraightLimb` into the shared limb path so straightness is a
  deliberate mode, not an accidental boundary.

**Estimated complexity**: Medium.

**Fix location**: Engine.

---

## Issue 3 — IK clamp is blind to fixed contacts / ground: clamped end-effector can penetrate the floor

**Description**
`solveIK` clamps the solved distance into `[minDist, maxDist]` and places the end joint
along the *original direction vector*. When the target is inside the reachable sphere
(under-clamp), the clamped point lands *closer to the root than requested*, i.e. further
along the same direction — which can be **below ground** or inside a wall/prop.

Concrete failure in **Deep Overhead Squat**: hip→ankle requested distance 48.0 <
minDist 56.0 ⇒ clamped to 56.0 along direction `(0.729, −0.625, −0.275)`. Clamped
ankle = `hip + 56·dir ≈ (15.8, −5.0, −37.4)` ⇒ **y = −5 < ground level 0** ⇒ foot
penetrates the floor. The validator only *detects* this after the fact
(`FOOT_GROUND_PENETRATION`); the engine never prevented it.

**Root cause**
The clamp is a local 1-D distance projection with no knowledge of the support
environment. "Keep this contact on the ground/bar" is a constraint the solver does not
see.

**Affected engine components**
`SkeletonMath.solveIK` (clamp block), `IKConstraint`, `ExerciseValidator`
(`validateFeetGroundPenetration`, `validateSupportPolygon` — read-only), `BasePose` /
`BaseValidationPose` (call sites).

**Affected exercises**
Any deep/compact posture where a planted foot or hand target is nearer the joint than
the fold minimum: deep squats, deep lunges, pistol squats, deep pikes, compact sits.

**Affected validation poses**
Deep Overhead Squat (foot below ground), and indirectly any split/pike whose target is
inside the sphere.

**Severity**: HIGH (produces an anatomically impossible, validator-failing result).

**Possible architectural solutions**
- Treat a fixed support contact as a hard constraint: if the end joint is a support
  contact, clamp in the plane of the support surface (project onto ground/prop) rather
  than along the free direction.
- Add a "pin to contact" post-step that re-solves the proximal chain (see Issue 4) so
  the contact stays put while the rest of the body accommodates.

**Estimated complexity**: High (requires the contact-aware solve from Issue 4).

**Fix location**: Engine.

---

## Issue 4 — No global constraint solver / no root (pelvis) repositioning to satisfy fixed contacts + posture simultaneously

**Description**
The engine is *purely local*: for each limb it runs 2-bone IK from a root to a target,
then runs FK up the tree. There is no stage that adjusts the pelvis/root (or other
upstream joints) so that several *coupled* requirements hold at once — e.g. "both feet
planted at their authored targets AND the legs straight AND the pelvis at a believable
height." When the authored geometry is inconsistent, the local solver just clamps and
produces a defect (penetration, folded limbs, floating contacts) rather than redistributing
the error upstream.

This is the unifying root cause behind Issues 2 and 3 in the references:
- Deep Overhead Squat: feet cannot be both at their targets and reachable → foot sinks.
- Middle Split: legs cannot be both straight and at their targets → legs fold.
- Pike Sit: legs cannot be both straight and at their targets → over-clamp bend.

**Root cause**
Architectural: the solve pipeline has no global/relaxation or contact-constraint layer.
`SkeletonFactory` offers alternate *topologies* (re-rooting) but no *constraint
propagation*.

**Affected engine components**
Whole solve pipeline: `SkeletonFactory`, `BasePose`/`BaseValidationPose` orchestration,
`SkeletonMath` (local only), `SkeletonPoseFinalizer` (FK only), `ExerciseValidator`
(can only report, never solve).

**Affected exercises**
Everything that couples a fixed support with a posture: squats, lunges, pistols,
splits, pikes, planks, bridges, good-mornings, single-leg stands.

**Affected validation poses**
Deep Overhead Squat, Middle Split, Pike Sit (all three couple planted feet with a limb
posture the local solver cannot honor).

**Severity**: HIGH (this is the deepest limitation; it is why clean reproduction of the
references is currently impossible).

**Possible architectural solutions**
- Introduce a contact-constraint layer between IK and FK: gather fixed `SupportContact`
  points, solve the proximal chain(s) so contacts are preserved, and only then derive the
  dependent joints. A small relaxation/iterative pass (still deterministic, allocation-free)
  over root position + limb solves would resolve most inconsistencies.
- Optionally expose an explicit "solve root to satisfy contacts" primitive so poses
  declare *what must stay fixed* and the engine decides *where the pelvis goes*.

**Estimated complexity**: High.

**Fix location**: Engine (conceptually a new layer; poses would only declare contacts,
not micro-manage the root).

---

## Issue 5 — No scapular / clavicular degrees of freedom: the shoulder is a single point

**Description**
The shoulder girdle is modeled as a single `SHOULDER_*` point rigidly offset from the
chest. There is no clavicle and no scapula node, and no scapular DOF. Consequently
"scapula drives pulling" (`BIOMECHANICS.md` §4) and "thoracic follows the shoulder
girdle" (§5) cannot be expressed as real joint motion.

Today the only way to suggest scapular movement is to **translate the shoulder point**
(e.g. `BaseVerticalPullPose` drops/retracts the shoulder by `-depression` and
`halfW = shoulderWidth − retraction`). Per `BIOMECHANICS.md` §10 / `CODING_RULES.md` §3
("Do not compensate for engine bugs", "never translate the pelvis/shoulder to fake a
shape"), this is exactly the kind of compensation the architecture forbids — but the
engine offers no legitimate alternative.

**Root cause**
`SkeletonFactory` joint set has no `CLAVICLE`/`SCAPULA`; `SkeletonMath` has no girdle DOF;
FK treats the shoulder as a leaf whose position is a fixed offset from the chest.

**Affected engine components**
`SkeletonFactory.createStandardSkeleton` (missing joints), `Joint` enum (no girdle
members), `SkeletonMath`, `BasePose`/`BaseVerticalPullPose` (compensatory translation).

**Affected exercises**
All pulling (pull-up, chin-up, scapular pull-up, dead hang, face pull, lat stretch,
rows) and any press where scapular setting matters.

**Affected validation poses**
Dead Hang (shoulder girdle / scapular depression-retraction absent — the reference
explicitly lists "shoulder girdle" as validated).

**Severity**: MEDIUM–HIGH (blocks a core biomechanical principle; currently masked by a
compensation that the rules forbid).

**Possible architectural solutions**
- Add `CLAVICLE_*` and `SCAPULA_*` nodes between chest and shoulder with their own DOF
  (elevation/depression, protraction/retraction, upward/downward rotation).
- Drive the pull from scapular retraction/depression first, then shoulder, then elbow —
  matching `BIOMECHANICS.md` §4.

**Estimated complexity**: High (new joints + DOF + FK + integration into every upper-body
pose family).

**Fix location**: Engine (the girdle must exist as real anatomy before poses can stop
translating the shoulder).

---

## Issue 6 — No wrist articulation: hand/palm/fingers are derived purely from forearm direction

**Description**
`SkeletonPoseFinalizer.adjustHandOrientation` computes the wrist/palm/knuckle/fingertip
positions from a *single direction* = `normalize(hand − elbow)` via
`HandDefinition.computeHandJoints`. It never reads `hand.localRotation`. Any wrist
rotation the pose authors (e.g. Dead Hang's overhand `gripAngle = invChestZ − π/2`) is
**silently ignored** for the completed hand; only the forearm line is honored.

**Root cause**
The finalizer treats the hand as a rigid extension of the forearm with zero independent
DOF; `HandDefinition.computeHandJoints` takes only `(wrist, direction)`.

**Affected engine components**
`SkeletonPoseFinalizer.adjustHandOrientation`, `HandDefinition.computeHandJoints`,
`WRIST_*` node handling.

**Affected exercises**
All grips and wrist positions: dead hang (overhand/underhand), pull-ups, rows, planks
(forearm vs palm), face pulls, any pronation/supination.

**Affected validation poses**
Dead Hang (overhand grip not represented in the completed hand).

**Severity**: MEDIUM.

**Possible architectural solutions**
- Promote the wrist to a real joint: derive palm/knuckle/fingertip orientation from
  `hand.localRotation` (composed with the forearm direction), so grip/pronation/supination
  are honored.
- `HandDefinition.computeHandJoints` should accept an orientation frame, not a single
  direction.

**Estimated complexity**: Medium.

**Fix location**: Engine.

---

## Issue 7 — No independent ankle / talocrural / subtalar DOF: foot orientation derived solely from shank direction

**Description**
`SkeletonPoseFinalizer.adjustFootOrientation` builds heel/toe from a single forward
direction derived from the shank (`ankle − knee`), then applies only a *pitch clamp*.
`ankle.localRotation` (authored by poses, e.g. Dead Hang / Pike Sit set it) is ignored.
The foot therefore cannot deviate from the shank line except through the pitch limit;
dorsi/plantar-flexion, inversion/eversion, and toe/heel lift are not independently
expressible.

**Root cause**
Same pattern as the wrist: the foot is treated as a rigid continuation of the shank
with no joint of its own in the completion stage.

**Affected engine components**
`SkeletonPoseFinalizer.adjustFootOrientation`, `FootDefinition.computeHeelToe`.

**Affected exercises**
Squats, lunges, pistols, calf raises, dorsiflexion-demanding poses, any foot that must
stay flat while the shank is angled.

**Affected validation poses**
Deep Overhead Squat (ankle mobility is the whole point), Pike Sit, Middle Split, Dead
Hang (legs).

**Severity**: MEDIUM.

**Possible architectural solutions**
- Model the ankle as a real joint whose rotation composes with the shank direction to
  produce the foot frame; allow dorsi/plantar-flexion and inversion/eversion within
  anatomical limits.
- `FootDefinition.computeHeelToe` should consume an orientation frame.

**Estimated complexity**: Medium.

**Fix location**: Engine.

---

## Issue 8 — No angular joint limits; only distance-based IK clamps exist

**Description**
The engine's only notion of a joint limit is the IK distance clamp
(`minDist`/`maxDist` from `minimumFlexionAngle`/`maximumExtensionRatio`). There are **no
angular limits** for shoulder, hip, elbow, knee, spine, or wrist. Any target whose
distance falls inside `[minDist, maxDist]` is accepted, even if it implies an
anatomically impossible *angle* — e.g. a hyper-rotated hip, a 200° elbow, or a spine
flexed far past end range. The validator has no angular rule to catch this
(`validateIKConstraints` only re-checks the same distance band).

**Root cause**
Joint limits were equated with the 2-bone reach band; per-axis rotation limits were
never modeled.

**Affected engine components**
`SkeletonMath.solveIK`, `IKConstraint`, `ExerciseValidator.validateIKConstraints`,
`SkeletonFactory` (no limit metadata).

**Affected exercises**
Any extreme range: deep splits, extreme rotations, dislocation-like configurations the
distance clamp still permits.

**Affected validation poses**
Latent in all four (none currently trip it, but the capability to author an impossible
angle without detection exists).

**Severity**: MEDIUM.

**Possible architectural solutions**
- Add per-joint angular limits (flexion/extension/abduction/rotation cones) consumed by
  `solveIK` and by a new validator rule.
- Encode limits in `SkeletonDefinition`/`IKConstraint` as general rules, not
  one-off constants.

**Estimated complexity**: High (defines a limit vocabulary + solver enforcement +
validator rule).

**Fix location**: Engine.

---

## Issue 9 — Trunk frame is 1-DOF (forward/back lean only) on the modern rotation-driven path

**Description**
On the modern path the chest is authored with a single `axisZ` (lateral-axis) local
rotation, capturing only sagittal lean. There is no thoracic **twist** (about Y) or
**side-bend** (about X). The cross-product chest-frame derivation that *does* capture a
full 3D orientation (`lean × shoulderLine → normal`) exists **only in the legacy
`setupTransforms` path**, not in the modern `finalize` path.

**Root cause**
The modern path stores whatever `localRotation` the pose sets (always `axisZ`), and the
finalizer does not reconstruct a 3D chest frame for it.

**Affected engine components**
`SkeletonFactory` (chest authored on Z only), `SkeletonPoseFinalizer.finalize` (modern
branch skips chest-frame reconstruction), `BasePose` (no twist/side-bend helpers).

**Affected exercises**
Rotation/twist patterns (Russian twist, woodchop, thoracic rotations, cable twists),
side-bends, any asymmetric thorax.

**Affected validation poses**
None of the four (they are all sagittal/neutral), so this is a latent limitation rather
than a current reproduction failure.

**Severity**: MEDIUM.

**Possible architectural solutions**
- Author/derive the chest as a full 3D orientation (axis-angle or matrix) on both paths.
- Port the legacy cross-product chest-frame reconstruction into the modern finalizer so
  thoracic twist/side-bend is representable.

**Estimated complexity**: Medium.

**Fix location**: Engine.

---

## Issue 10 — Reachability / clamp detection is effectively dead for validation poses

**Description**
`ExerciseValidator.validateIkTargetReachability` only fires when
`pose.maxIkClampAmount > 0.1`, and that field is **never written** by the validation
poses (`BaseValidationPose.finalizePose` copies nothing into it). Even in production it
is set by manual bookkeeping in each pose. So an unreachable target (e.g. the Deep
Overhead Squat under-clamp of 8.1) is never surfaced by this rule, and the only signal
is the downstream ground-penetration / bone-length failure.

**Root cause**
The clamp amount computed inside `SkeletonMath.solveIK` (`IKResult.clampAmount`) is not
automatically propagated into `SkeletonPose.maxIkClampAmount`; it relies on each caller
to remember.

**Affected engine components**
`SkeletonPose.maxIkClampAmount`, `ExerciseValidator.validateIkTargetReachability`,
`BaseValidationPose.finalizePose`, all `bakeIkLimb` call sites.

**Affected exercises**
All (detection gap, not a visual defect).

**Affected validation poses**
All four (the reachability rule cannot catch their clamps).

**Severity**: LOW–MEDIUM (detection/observability gap that hides Issues 2–4).

**Possible architectural solutions**
- Have `bakeIkLimb`/`solveIK` write `clampAmount` into the pose automatically, so
  reachability is always observable.
- Make `checkIkTargetReachability` on by default for validation runs.

**Estimated complexity**: Low.

**Fix location**: Engine.

---

## Issue 11 — maxExtensionRatio = 0.98 means no limb can ever be perfectly straight

**Description**
`IKConstraint.ArmConstraint`/`LegConstraint` cap extension at `0.98·(L1+L2)`. By design
no limb reaches full length, so every reference that demands a dead-straight limb is
reproduced with a few % residual bend (Dead Hang arms ≈ 96.5% extension; legs ≈ 95.6%).
This is intentional per `ENGINE.md` §7, but it directly prevents the four static
references from being *exactly* reproduced.

**Root cause**
A global "never lock straight" policy with no escape for poses that legitimately want a
true straight segment.

**Affected engine components**
`IKConstraint`, `SkeletonMath.solveIK` (clamp to maxDist).

**Affected exercises**
All straight-limb holds (hangs, planks, pikes, splits, straight-leg raises).

**Affected validation poses**
Dead Hang, Pike Sit, Middle Split, Deep Overhead Squat (every "straight" limb).

**Severity**: LOW (by design, but it is a hard ceiling on static-reference fidelity).

**Possible architectural solutions**
- Allow `maxExtensionRatio = 1.0` (or a dedicated straight mode) for poses that opt in,
  while keeping the 0.98 default for dynamic safety.

**Estimated complexity**: Low.

**Fix location**: Engine (policy, not a per-pose hack).

---

# Prioritized Roadmap (highest → lowest architectural impact)

1. **Issue 4 — No global constraint / root-repositioning layer.**
   The deepest limitation. Until the engine can satisfy "fixed contacts + posture"
   together, references like Deep Overhead Squat, Middle Split, and Pike Sit cannot be
   reproduced. Fixing this also resolves most of Issues 2 and 3. *(Engine, High.)*

2. **Issue 1 — Fragile frame-relative limb baking (hand-fed inverse-Z scalar).**
   Core limb path; catastrophic (≈120°) mis-placement in Pike Sit, silent error
   elsewhere. Should be derived from the real parent world transform. *(Engine, Medium.)*

3. **Issue 2 — No straight/rigid-segment limb mode (IK always folds).**
   Directly prevents Middle Split from being straight and bends every "straight" reference.
   *(Engine, Medium.)*

4. **Issue 3 — IK clamp ignores ground/contacts → penetration.**
   Concrete, validator-failing failure in Deep Overhead Squat (foot below ground).
   Subsumed by Issue 4's contact layer but worth its own track. *(Engine, High.)*

5. **Issue 5 — No scapular/clavicular DOF (shoulder is a point).**
   Blocks the "scapula drives pulling" principle; currently masked by a forbidden
   shoulder-translation compensation. *(Engine, High.)*

6. **Issue 9 — Trunk frame is 1-DOF (no twist/side-bend) on the modern path.**
   Limits thoracic expression; latent for the four references but blocks rotation
   families. *(Engine, Medium.)*

7. **Issue 8 — No angular joint limits (distance clamps only).**
   Engine can author impossible angles undetected. *(Engine, High.)*

8. **Issue 6 — No wrist articulation.**
   Grip/pronation/supination (e.g. Dead Hang overhand) not represented. *(Engine, Medium.)*

9. **Issue 7 — No independent ankle/talocrural DOF.**
   Foot cannot deviate from shank line except via pitch clamp. *(Engine, Medium.)*

10. **Issue 10 — Reachability detection dead for validation poses.**
    Clamp amount not propagated; hides Issues 2–4. *(Engine, Low.)*

11. **Issue 11 — 0.98 max extension ⇒ no perfectly straight limb.**
    Hard ceiling on static-reference fidelity. *(Engine, Low.)*

---

## Summary

The engine cannot cleanly reproduce the four reference poses because of **architectural**
gaps, not pose errors:

- **Deep Overhead Squat** fails hardest: its feet are unreachable for the authored leg
  length, the IK clamp drives the ankle below ground, and its arms are mis-baked by the
  fragile frame-angle scalar.
- **Pike Sit** fails catastrophically on arm placement (≈120° baking error) and its legs
  over-clamp (cannot be perfectly straight).
- **Middle Split** cannot be straight at all: both limbs are forced into near-max fold
  because the authored targets sit just outside the fold minima.
- **Dead Hang** is the closest to reproducible, but its limbs are still a few % bent and
  its overhand grip is dropped by the wrist-completion stage.

The single highest-leverage fix is a **contact-aware global solve layer (Issue 4)** that
repositions the root/pelvis and honors fixed supports, supported by a **correct
frame-relative baking path (Issue 1)** and a **first-class straight-limb mode (Issue 2)**.
Shoulder girdle (5), wrist (6), ankle (7), trunk 3-DOF (9), and angular limits (8) are
the next tier of anatomical fidelity, all engine-side.
