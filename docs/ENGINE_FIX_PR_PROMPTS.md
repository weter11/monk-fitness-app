# Engine Fix — 11 PR Prompts (one per investigation issue)

> **HISTORICAL ARCHIVE.** Retained as the original implementation-prompt set. Many
> prompts are now obsolete because their target issues were resolved. The live,
> de-duplicated pickup list is `docs/ENGINE_ROADMAP.md`; the consolidated issue
> index is `docs/ENGINE_HISTORY.md`. When this file conflicts with the current
> code, the code wins.

> These are **implementation prompts** derived from `docs/ENGINE_INVESTIGATION_REPORT.md`.
> Each PR fixes an *engine* limitation. All work must obey the project constitution:
> `ENGINE.md`, `BIOMECHANICS.md`, `VALIDATION.md`, `CODING_RULES.md`.
>
> Standing rules for every PR:
> - Reuse existing engine primitives (`SkeletonMath`, `SkeletonFactory`, `bakeIkLimb`); do **not** duplicate IK/FK/rotation math.
> - Stay allocation-free on hot paths (write into caller-supplied buffers).
> - Do **not** add magic constants; any new limit belongs to a general rule.
> - Do **not** change `SkeletonDefinition` bone lengths to fit one pose, do **not** move IK targets, do **not** compensate inside validation poses.
> - Verify against the four validation poses (Dead Hang, Deep Overhead Squat, Pike Sit, Middle Split) plus the existing exercise test suite. No regression in other poses.
> - Keep the four layers separated (Engine solves motion; Pose describes; Exercise is metadata; Validation verifies).

Order below follows the investigation prioritization. Each prompt lists **Depends on** so PRs can be sequenced.

---

## PR-01 — Fix frame-relative limb baking (remove the hand-fed inverse-Z scalar)

**Issue**: #1 (HIGH). `bakeIkLimb` rotates the IK world offset by a caller-supplied
`localRotationAngle` about a fixed `axisZ`, but the stored `localPosition` must equal the
**negative of the parent's true world rotation**. Callers must keep this scalar in sync by
hand, which is wrong whenever the trunk is leant or twisted. Pike Sit currently lands its
arms ~120° off; Deep Overhead Squat arms ~5.7° off.

**Goal**: Make limb baking derive the local frame from the *actual* parent world transform
(full 3D axis-angle), eliminating the fragile scalar.

**Files to modify**
- `animation/BasePose.kt` — `bakeIkLimb` overloads (lines ~103–151).
- `validation/poses/BaseValidationPose.kt` — `bakeIkLimb` overloads (lines ~90–124).
- `animation/SkeletonMath.kt` — add a `worldToLocal` helper (inverse of `rotAround` /
  reuse `toLocalDirection`); consider a `bakeIkLimb` that takes the parent `SkeletonNode`.

**Implementation steps**
1. Add `SkeletonMath.worldOffsetToLocal(worldOffset: Vector3, parentWorld: ...)` that
   rotates a vector by the inverse of a `JointRotation` (allocate-free, reuse `rotAround`
   with negated angle). This already exists as `toLocalDirection(worldDir, rotation, out)`
   — reuse it.
2. Change `bakeIkLimb` so the `localRotationAngle` parameter is **removed** and replaced by
   the parent node (or its `worldRotation`). Compute:
   `middleNode.localPosition = toLocalDirection(ik.joint - rootWorldPos, parentWorldRotation)`
   `endNode.localPosition    = toLocalDirection(ik.end   - ik.joint,    parentWorldRotation)`
3. Update all call sites (production `BasePose` family bases + the four validation poses)
   to pass the parent `SkeletonNode`/`worldRotation` instead of `leanAngle`/`-fold*0.6`/etc.
4. Keep the pole transform as-is (the frame-relative overload already calls
   `toWorldDirection(poleLocal, parentRotation)`); just stop double-applying a manual Z angle.

**Acceptance criteria**
- Pike Sit arms land at the authored reach targets (no ~120° error). Deep Overhead Squat arms
  correct to sub-degree. Dead Hang and Middle Split unchanged (they were correct only by
  coincidence).
- All existing `BasePose` family tests (squats, pull-ups, planks, lunges, etc.) still pass.
- No per-frame allocations.

**Constraints**: Do not alter `JointRotation` semantics; do not introduce a new scalar.
**Out of scope**: global solve (PR-04), straight-limb mode (PR-02).
**Depends on**: none.

---

## PR-02 — Add a first-class straight / rigid-segment limb mode

**Issue**: #2 (HIGH). The only limb primitive is 2-bone IK, which always bends the middle
joint. When a target is inside the reachable sphere the knee/elbow folds. Middle Split's
limbs are forced into near-max fold (hip→ankle 58.9, shoulder→hand 46.0); Pike Sit legs
over-clamp (209.0 > 205.8); Dead Hang limbs are a few % bent. `solveNearStraightLimb`
exists but is not wired into `bakeIkLimb`.

**Goal**: Provide a deliberate "straight limb" solve so a limb can be pinned collinear to an
arbitrary reachable target (respecting a true straight extension when valid).

**Files to modify**
- `animation/SkeletonMath.kt` — promote `solveNearStraightLimb` into a shared straight-limb
  solver usable by `bakeIkLimb`; keep it analytical and allocation-free.
- `animation/BasePose.kt` & `validation/poses/BaseValidationPose.kt` — `bakeIkLimb` gains a
  `straight: Boolean` (or `extensionMode`) flag; when set, emit a collinear two-segment chain
  aimed at the target and bake it through the (PR-01 fixed) local-frame path.
- `animation/IKConstraint.kt`-equivalent (`IKConstraint` in `SkeletonMath.kt`) — keep the
  0.98 default but allow an explicit straight mode (see PR-11).

**Implementation steps**
1. Define a straight-limb result: given `root`, `target`, `L1`, `L2`, compute the single
   direction `d = normalize(target - root)` and place middle at `root + d·L1`, end at
   `root + d·(L1+L2)` (clamped to reachable length). Reuse `solveNearStraightLimb`'s math
   for the slight offset needed to stay inside `[minDist, maxDist]` when truly straight is
   outside the 0.98 band — but prefer honoring a real straight mode from PR-11.
2. In `bakeIkLimb`, branch on the straight flag: if straight, use the straight solve; else
   the existing triangle IK. Both must share the PR-01 local-frame bake.
3. Expose a `solveStraightArmIK`/`solveStraightLegIK` wrapper in `BasePose` for reuse.
4. Let validation poses opt into straight mode for limbs that should be rigid (Middle Split
   legs+arms, Pike Sit legs, Dead Hang arms+legs).

**Acceptance criteria**
- Middle Split renders straight legs and straight arms at the authored targets.
- Pike Sit legs straight (within the allowed extension policy).
- Dead Hang arms/legs visibly straight.
- No regression in bent-limb exercises (squats, pull-ups).

**Constraints**: Straightness is a deliberate mode, never a magic nudge. Reuse `bakeIkLimb`.
**Out of scope**: contact-aware solve (PR-04).
**Depends on**: PR-01 (local-frame bake must be correct first).

---

## PR-03 — Make the IK clamp contact/ground aware (no penetration)

**Issue**: #3 (HIGH). `solveIK` clamps distance into `[minDist, maxDist]` along the original
direction. When under-clamped, the end lands *closer to root* along that direction — which
can be below ground. Deep Overhead Squat's ankle ends at y ≈ −5 (below level 0).

**Goal**: When the end joint is a fixed support contact, clamp **in the plane of the support
surface** (project onto ground/prop) instead of along the free direction, so contacts stay
put and above ground.

**Files to modify**
- `animation/SkeletonMath.kt` — `solveIK` gains an optional `supportPlane` (normal + point)
  or a `ContactConstraint`.
- `animation/IKConstraint.kt` (in `SkeletonMath.kt`) — carry the contact constraint.
- `animation/BasePose.kt` / `validation/poses/BaseValidationPose.kt` — pass the support plane
  for planted hands/feet.
- `animation/ExerciseValidator.kt` — keep `FOOT_GROUND_PENETRATION` as a safety net.

**Implementation steps**
1. Add a `ContactConstraint(normal: Vector3, point: Vector3)` (e.g. ground normal `(0,1,0)`,
   point `(0, level, 0)`; or a prop top plane).
2. In `solveIK`, after computing `dist`, if a contact constraint is present and the clamped
   end would cross the plane, instead solve for the end on the plane at the nearest reachable
   distance (project the target onto the plane, then clamp distance). This keeps the contact
   on the surface.
3. Wire `SupportContact`/`SupportDefinition` from `PoseMetadata` into the bake calls for
   planted contacts (hands on bar, feet on ground).
4. Validation poses: mark feet as ground contacts so the clamp respects y ≥ level.

**Acceptance criteria**
- Deep Overhead Squat feet rest on the ground (penetration gone); IK clamp recorded.
- No foot/ankle below `ground.level` in any validation pose.
- Hands on the bar stay on the bar.

**Constraints**: Do not move the authored target to hide the clamp — fix the solve. Reuse
`solveIK`. **Out of scope**: full root repositioning (PR-04).
**Depends on**: PR-01 (correct bake), PR-02 (straight mode). Best landed together with PR-04.

---

## PR-04 — Add a global contact-constraint / root-repositioning layer

**Issue**: #4 (HIGH, root cause of #2/#3). The engine is purely local (per-limb 2-bone IK +
FK). It cannot satisfy "fixed contacts + posture" simultaneously, so inconsistent authored
geometry produces penetration/folded limbs/floating contacts instead of redistributing the
error upstream.

**Goal**: Introduce a deterministic, allocation-free constraint layer between IK and FK that
preserves fixed `SupportContact` points and repositions the root/pelvis (and dependent
chains) so all contacts hold.

**Files to modify**
- New: `animation/ConstraintSolver.kt` (or extend `SkeletonPoseFinalizer` pre-pass).
- `animation/SkeletonFactory.kt` — keep topologies; the solver works on the existing tree.
- `animation/BasePose.kt` / `validation/poses/BaseValidationPose.kt` — collect fixed
  contacts from `PoseMetadata.support` and hand them to the solver.
- `animation/SkeletonPoseFinalizer.kt` — run the constraint pass before FK flatten.

**Implementation steps**
1. Gather fixed contacts from `PoseMetadata.support.contacts` (e.g. `LEFT_FOOT`,
   `RIGHT_FOOT`, `LEFT_HAND` w/ anchor). For each, the world target is known (ground point,
   prop top, or bar anchor).
2. Solve the **proximal chain** (pelvis/root + hip/knee or shoulder/elbow) so the contact is
   reached exactly, then derive the remaining free DOF (e.g. pelvis height/tilt) from the
   constraint rather than a fixed authored value.
3. Keep it deterministic and bounded: a fixed small number of relaxation iterations over
   {root transform, limb IK} per frame; reuse scratch buffers.
4. Ensure non-contact limbs still use the existing IK (PR-02 straight mode, PR-03 contact
   clamp) for non-fixed joints.

**Acceptance criteria**
- Deep Overhead Squat: feet planted at targets, legs straight-ish, pelvis positioned to honor
  both (no penetration, no fold).
- Middle Split: legs straight at targets, pelvis centered.
- Pike Sit: legs straight, feet on ground.
- No flicker/popping between frames (deterministic, smooth).

**Constraints**: Engine-only; poses declare contacts, they don't micro-manage the root.
Allocation-free. **Out of scope**: scapula (PR-05), wrist (PR-06), ankle (PR-07).
**Depends on**: PR-01, PR-02, PR-03 (the per-limb primitives it composes).

---

## PR-05 — Model the shoulder girdle: add clavicle + scapula degrees of freedom

**Issue**: #5 (MEDIUM–HIGH). The shoulder is a single point on the chest; no clavicle/scapula
and no girdle DOF. "Scapula drives pulling" (`BIOMECHANICS.md` §4) is currently faked by
translating the shoulder (`BaseVerticalPullPose` drops/retracts the shoulder), which the
constitution forbids.

**Goal**: Add real girdle anatomy so scapular motion is actual joint rotation, not
compensation.

**Files to modify**
- `animation/Joint.kt` — add `CLAVICLE_A/P`, `SCAPULA_A/P` (and matching indices).
- `animation/SkeletonFactory.kt` — insert `CHEST → CLAVICLE → SCAPULA → SHOULDER` chain;
  keep `SHOULDER` as the IK root.
- `animation/SkeletonMath.kt` — add girdle DOF helpers (elevation/depression,
  protraction/retraction, upward/downward rotation) as constraints/limits.
- `animation/BasePose.kt` / `BaseVerticalPullPose.kt` — drive the pull from scapular
  retraction/depression first (per `BIOMECHANICS.md` §4), remove shoulder translation.
- `animation/SkeletonPoseFinalizer.kt` — ensure new nodes get world transforms/completion.

**Implementation steps**
1. Extend the `Joint` enum and `SkeletonFactory` tree; keep backward-compatible aliases.
2. Give the scapula its own `localRotation` DOF; derive shoulder position from it (no raw
   translation of `SHOULDER`).
3. Update pulling-family base poses to set scapular rotation from progress (scapular
   initiation), then shoulder/elbow.
4. Remove the `-(depression)` / `halfW = shoulderWidth - retraction` compensation in
   `BaseVerticalPullPose`; replace with scapular DOF.

**Acceptance criteria**
- Dead Hang shows scapular depression/retraction as real rotation.
- Pull-up family reads as "scapula first" without shoulder translation.
- Existing pull-up/chin-up/row tests pass; no regression.

**Constraints**: Real joints, not translation. Reuse FK. **Out of scope**: wrist (PR-06).
**Depends on**: PR-01 (so girdle-relative baking is correct).

---

## PR-06 — Promote the wrist to a real joint (honor hand rotation)

**Issue**: #6 (MEDIUM). `SkeletonPoseFinalizer.adjustHandOrientation` builds
palm/knuckle/fingertip from a single forearm direction and **ignores `hand.localRotation`**,
so grips (e.g. Dead Hang overhand `gripAngle`) are dropped.

**Goal**: Make the completed hand honor an actual wrist orientation frame.

**Files to modify**
- `animation/HandDefinition.kt` — `computeHandJoints(wrist, frame: ..., result)` consumes an
  orientation (basis or `JointRotation`), not just a direction.
- `animation/SkeletonPoseFinalizer.kt` — `adjustHandOrientation` composes `hand.localRotation`
  with the forearm direction to build the hand basis, then calls `computeHandJoints`.
- `animation/BasePose.kt` / validation poses — set `hand.localRotation` for grips.

**Implementation steps**
1. Add an orientation-aware overload to `HandDefinition.computeHandJoints` (keep the
   direction-only one for legacy callers).
2. In `adjustHandOrientation`, build a hand basis = `rotAround(forearmDir, hand.localRotation)`
   (or compose via the existing matrix utils), pass it through.
3. Validation poses: keep authoring `hand.localRotation` (Dead Hang overhand) — it will now
   take effect.

**Acceptance criteria**
- Dead Hang overhand grip rendered (palms face away from bar).
- No change to non-grip hand rendering.
- Allocation-free.

**Constraints**: Use engine matrix/rotation utils; no magic constants. **Out of scope**:
fingers articulation beyond current segments.
**Depends on**: none (independent of PR-01, but compose cleanly with it).

---

## PR-07 — Model the ankle as a real joint (honor ankle rotation)

**Issue**: #7 (MEDIUM). `adjustFootOrientation` derives heel/toe from the shank direction
only and ignores `ankle.localRotation`; dorsi/plantar-flexion and inversion/eversion aren't
independently expressible.

**Goal**: Compose foot orientation from an actual ankle joint rotation over the shank
direction.

**Files to modify**
- `animation/FootDefinition.kt` — `computeHeelToe(ankle, frame, outHeel, outToe)` consumes an
  orientation rather than a single forward direction.
- `animation/SkeletonPoseFinalizer.kt` — `adjustFootOrientation` builds the foot basis from
  `ankle.localRotation` composed with the shank direction.
- `animation/BasePose.kt` / validation poses — author `ankle.localRotation` for mobility.

**Implementation steps**
1. Add an orientation-aware overload to `computeHeelToe` (keep direction-only for legacy).
2. In `adjustFootOrientation`, build foot basis from `ankle.localRotation` (with the existing
   pitch clamp applied to the *result*, not as the only DOF).
3. Validation poses: Deep Overhead Squat / Pike Sit / Middle Split keep authoring ankle
   rotation; it now affects the foot.

**Acceptance criteria**
- Ankle mobility poses (Deep Overhead Squat especially) show dorsi/plantar-flexion.
- Feet still clamp to `foot.minPitch/maxPitch` as a safety bound.
- No regression in flat-foot poses.

**Constraints**: Reuse matrix utils; pitch clamp remains a bound, not the sole DOF.
**Out of scope**: subtalar multi-axis beyond a reasonable bound.
**Depends on**: none.

---

## PR-08 — Add angular joint limits (beyond distance clamps)

**Issue**: #8 (MEDIUM). The only joint limit is the IK distance band. Targets inside
`[minDist, maxDist]` are accepted even when they imply impossible angles (hyper-rotated hip,
200° elbow, over-flexed spine). The validator has no angular rule.

**Goal**: Add per-joint angular limits consumed by the solver and a new validator rule.

**Files to modify**
- `animation/IKConstraint.kt` (in `SkeletonMath.kt`) — extend with per-joint angular limit
  vocabulary (flexion/extension/abduction/rotation cones) as general data, not constants.
- `animation/SkeletonMath.kt` — `solveIK` clamps/records angle-limit violations.
- `animation/SkeletonDefinition.kt` — carry limit metadata per joint (shared, general).
- `animation/ExerciseValidator.kt` — add an `ANGULAR_JOINT_LIMIT` rule.

**Implementation steps**
1. Define a limit data structure (e.g. min/max per rotation axis or a cone half-angle) in
   `IKConstraint`/definition. No magic numbers — named, shared.
2. In `solveIK`, after solving, check the implied joint angles; if outside limits, clamp the
   middle joint toward the limit and record `clampAmount` (feeds PR-10 detection).
3. Add the validator rule mirroring the solver limits; off by default except validation runs.

**Acceptance criteria**
- Impossible-angle targets are clamped and flagged.
- Normal poses unaffected (their angles are within limits).
- No allocation on hot path.

**Constraints**: Limits are general/shared, never per-exercise constants. **Out of scope**:
changing bone lengths.
**Depends on**: PR-10 (detection plumbing) is complementary; can land independently.

---

## PR-09 — Full 3-DOF trunk frame (twist + side-bend) on the modern path

**Issue**: #9 (MEDIUM). On the modern rotation-driven path the chest is authored with a single
`axisZ` rotation (sagittal lean only). Thoracic twist (Y) / side-bend (X) are absent; the
cross-product chest-frame derivation exists only in the legacy `setupTransforms` path.

**Goal**: Represent the chest as a full 3-D orientation on both paths.

**Files to modify**
- `animation/SkeletonPoseFinalizer.kt` — port the legacy cross-product chest-frame
  reconstruction (`lean × shoulderLine → normal`) into the modern `finalize` branch.
- `animation/BasePose.kt` — add helpers to author chest twist/side-bend (full
  `JointRotation`, not just `axisZ`).
- `animation/SkeletonFactory.kt` — no structural change needed; ensure chest can carry a 3-D
  `localRotation`.

**Implementation steps**
1. In the modern `finalize` path, after FK, reconstruct the chest world frame from
   pelvis→chest and shoulderA→shoulderP lines (as the legacy path does) so twist/side-bend
   are captured and propagated to shoulders/arms.
2. Add `BasePose` helpers (e.g. `buildChestTwist(angle)`, `buildChestSideBend(angle)`) that
   set the chest `localRotation` as a real 3-D rotation.
3. Update twist/rotation-family poses to use them.

**Acceptance criteria**
- Thoracic rotation / side-bend poses render correct 3-D thorax orientation.
- The four validation poses (neutral trunk) unchanged.
- No regression.

**Constraints**: Reuse the existing cross-product math; no duplication. **Out of scope**:
scapula (PR-05).
**Depends on**: PR-01 (so shoulder/arm baking respects the 3-D chest frame).

---

## PR-10 — Make IK reachability detection work (propagate clamp automatically)

**Issue**: #10 (LOW–MEDIUM). `validateIkTargetReachability` only fires when
`pose.maxIkClampAmount > 0.1`, but validation poses never set it; production sets it by
hand. So unreachable targets (e.g. Deep Overhead Squat under-clamp 8.1) are never surfaced
by this rule.

**Goal**: Automatically propagate the solver's clamp amount into the pose, and enable the
rule for validation runs.

**Files to modify**
- `animation/SkeletonMath.kt` — `solveIK` / `bakeIkLimb` write `clampAmount` into the pose
  (or return it via the existing `IKResult` and have `bakeIkLimb` accumulate into
  `SkeletonPose.maxIkClampAmount`).
- `animation/PoseDefinition.kt` — `SkeletonPose.maxIkClampAmount` already exists; ensure it's
  the single source.
- `animation/BasePose.kt` & `validation/poses/BaseValidationPose.kt` — remove manual
  `maxIkClampAmount = maxOf(...)` bookkeeping; rely on automatic propagation.
- `animation/ExerciseValidator.kt` — enable `checkIkTargetReachability` for validation runs
  (keep off for normal product validation to avoid noise).

**Implementation steps**
1. In `bakeIkLimb`, after `solveIK`, do `pose.maxIkClampAmount = max(pose.maxIkClampAmount,
   ikResult.clampAmount)` (or pass the pose in). Do this once per pose build.
2. Delete the per-pose manual `maxIkClampAmount = maxOf(...)` lines now that it's automatic.
3. Turn the reachability check on for `EngineeringValidation` builds.

**Acceptance criteria**
- Deep Overhead Squat / Pike Sit / Middle Split report `IK_TARGET_UNREACHABLE` when their
  targets are clamped.
- Production exercises still validate cleanly (their targets are reachable).
- No per-frame allocation.

**Constraints**: Single source of truth; no duplicate bookkeeping. **Out of scope**: solving
the unreachability (that's PR-03/PR-04).
**Depends on**: none (complements PR-03/04).

---

## PR-11 — Allow a true straight limb (opt-in extension ratio 1.0)

**Issue**: #11 (LOW). `IKConstraint` caps extension at `0.98·(L1+L2)` by design, so no limb is
ever perfectly straight — every "straight" reference is a few % bent (Dead Hang arms ≈ 96.5%).

**Goal**: Permit a genuine straight segment for poses that opt in, without removing the 0.98
default safety cap for dynamic motion.

**Files to modify**
- `animation/IKConstraint.kt` (in `SkeletonMath.kt`) — add an `allowFullExtension: Boolean`
  (or a per-solve `maxExtensionRatio` override).
- `animation/SkeletonMath.kt` — `solveIK` uses `1.0` when full extension is allowed.
- `animation/BasePose.kt` / validation poses — opt straight limbs into full extension (works
  with PR-02's straight mode).

**Implementation steps**
1. Add the opt-in flag to `IKConstraint` (default `false` → keep 0.98).
2. In `solveIK`, when the flag is set and the requested distance ≥ `L1+L2 − ε`, solve
   collinear (or clamp to exactly `L1+L2`).
3. Validation poses set the flag for their straight limbs (Dead Hang arms/legs, Pike Sit
   legs, Middle Split limbs).

**Acceptance criteria**
- Straight-limb references are reproduced with true straightness.
- Dynamic exercises keep the 0.98 safety cap (no behavior change unless opted in).

**Constraints**: Opt-in only; default unchanged. **Out of scope**: bone-length changes.
**Depends on**: PR-02 (straight mode consumes this).

---

## Suggested PR sequencing

```
PR-01  (baking)            ──▶ baseline correctness for every limb
PR-02  (straight mode)     ──▶ depends on PR-01
PR-03  (contact clamp)     ──▶ depends on PR-01, PR-02
PR-04  (global solve)      ──▶ depends on PR-01, PR-02, PR-03   [highest leverage]
PR-10  (reachability)      ──▶ independent, cheap, enables verification of PR-03/04
PR-11  (full extension)    ──▶ depends on PR-02
PR-05  (scapula)           ──▶ depends on PR-01
PR-09  (trunk 3-DOF)       ──▶ depends on PR-01
PR-06  (wrist)             ──▶ independent
PR-07  (ankle)             ──▶ independent
PR-08  (angular limits)    ──▶ independent (complements PR-10)
```

Landing PR-01 → PR-02 → PR-03 → PR-04 first resolves the four references' hardest
failures (Deep Overhead Squat penetration, Middle Split fold, Pike Sit mis-bake/over-clamp).
PR-05..PR-09 raise anatomical fidelity; PR-10/PR-11 are verification/policy polish.
