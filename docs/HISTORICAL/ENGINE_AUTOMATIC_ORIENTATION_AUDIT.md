# ENGINE AUTOMATIC ORIENTATION AUDIT

**Date:** 2026-07-16
**Baseline:** post-W1, after `MANUAL_OVERRIDE` removal from all production poses
(PR #121). Engine derivation is now `AUTOMATIC` for every extremity in every
production pose; the `overrideExtremityOrientation(...)` helper is no longer
called by any exercise.

**Scope of this audit:** trace what *actually* computes heel/toe/palm/knuckles/
fingertips today, decide whether any pose still compensates for torso/spine/
parent/lean/pitch, decide whether the override opt-out can be deleted, draw the
ownership pipeline, and judge whether a new developer can ignore extremities
entirely.

---

## 1. What is currently calculating each extremity (AUTOMATIC path)

All four extremities are resolved in `SkeletonPoseFinalizer.finalize()`
(`SkeletonPoseFinalizer.kt:311`). For a production pose (modern rotation-driven
path, `pose.roots.isNotEmpty()`), each extremity runs the derivation **iff**
`pose.isExtremityAutomatic(e)` is true — which is the default for all
(`PoseDefinition.kt:60` initialises the mode array to `AUTOMATIC`).

Pipeline per extremity (foot shown; hand is symmetric):

```
pose.build()                                            ← Pose author
  • authors limb target + ankle/wrist *articulation* (localRotation)
  • calls SkeletonPose.fromHierarchy(roots, jointsBuffer)
       → jointsBuffer carries ankle/hand world rotation + AUTO mode
AnimationController.transform(context) → context.progress
SkeletonPoseFinalizer.finalize(pose)
  • optional ConstraintSolver.solve (only if pose has contacts)
  • FK traversal: pose.roots[i].updateWorldTransforms + flatten(outputPose)
  • reconstructChestFrame (chest only if author left it identity)     [Issue F]
  • for each AUTO extremity:
        rel = relativeRotation(worldRotation(joint), worldRotation(parent), out)
              = transpose(parentMat) · worldMat   → rotation *relative to parent segment*
        adjustFootOrientation(...) / adjustHandOrientation(...)
```

### heel / toe  (`adjustFootOrientation`, `:454`)

1. `shank = normalize(ankle - knee)` — the leg's own direction.
2. `providedToe` is the pose's toe localPosition. If the pose *did* author a toe
   (`|providedToe - ankle| > 1e-3`) it is used as a **forward hint**; otherwise
   hint = `+X`.
3. `tempFootDir` = component of the hint **perpendicular to the shank** (remove
   the shank-parallel part). Falls back to `worldDown`-perpendicular if degenerate.
4. `ankleRotation = rel` (the ankle rotation *relative to the knee/shank frame*).
5. `foot.computeHeelToe(ankle, tempFootDir, ankleRotation, heelOut, toeOut)`:
   - `rotAround(tempFootDir, rel.axis, rel.angle, scratchDir)` — composes the
     authored ankle articulation with the neutral foot direction (dorsi/plantar/
     inversion honored).
   - `applyPitchClamp(scratchDir)` — safety bound `[minPitch, maxPitch]` = ±45°.
   - `writeHeelToe`: `heel = ankle - dir·footLength·heelRatio`,
     `toe = ankle + dir·footLength·toeRatio`
     (`heelRatio=0.29`, `toeRatio=0.71` from `FootDefinition`).

**So heel/toe are computed by `adjustFootOrientation` + `FootDefinition.computeHeelToe`
from the shank direction + the ankle's *relative* rotation + `FootDefinition` ratios.**

### palm / knuckles / fingertips  (`adjustHandOrientation`, `:422`)

1. `wrist.set(hand)` — promote wrist to the hand position.
2. `dir = normalize(hand - elbow)` — the forearm's own direction.
3. `wristRotation = rel` (hand rotation *relative to the elbow/forearm frame*).
4. `handDef.computeHandJoints(wrist, dir, wristRotation, buf)`:
   - `rotAround(dir, rel.axis, rel.angle, scratchDir)` — composes the authored
     wrist articulation (grip / pronation / flexion) with the forearm direction.
   - `writeJoints`: `palm = wrist + dir·palmLength·0.5`,
     `knuckles = wrist + dir·palmLength`,
     `fingertips = wrist + dir·(palmLength + fingerLength)`
     (`palmLength=12`, `fingerLength=10` from `HandDefinition`).
5. `pose.palm/knuckles/fingertips.set(buf.*)`.

**So palm/knuckles/fingertips are computed by `adjustHandOrientation` +
`HandDefinition.computeHandJoints` from the forearm direction + the wrist's
*relative* rotation + `HandDefinition` lengths.**

### The key canceller: `relativeRotation` (`:414`)

`relativeRotation(world, parent) = transpose(parentMat) · worldMat` — the joint's
rotation expressed **in the parent segment's frame**. Because `adjustFoot/
HandOrientation` then apply this *relative* rotation to a world-space direction
that is *already framed by the parent* (shank / forearm), the inherited
torso/spine/parent tilt is **never double-counted**. A neutral (identity) ankle
or wrist therefore lays the extremity flat along the limb — exactly the FK
frame for a neutral limb. This is the mechanism that made all the old
`-torsoPitch` / `-parentRotation.angle` counter-rotations obsolete.

---

## 2. Does any production pose still compensate for torso / spine / parent / lean / pitch?

**No.** After PR #121 there is no `overrideExtremityOrientation` call and no
hand-authored heel/toe/palm/knuckles/fingertips in any production pose. The
only remaining `ankle*/hand*.localRotation` writes are **genuine articulations**,
not cancellations:

| Pose | Rotation | What it is | Compensation? |
| --- | --- | --- | --- |
| `BaseVerticalPullPose` | `ankle = -plantarFlexion` | dorsi/plantar flexion of the hanging foot | No — foot articulation |
| `BaseVerticalPullPose` | `hand = grip angle` (over/underhand ±HALF_PI) | the grip itself | No — grip is intent |
| `HamstringStretchPose` | `ankleF = torsoPitch - 1.57f` | front foot points to sky | No — deliberate foot orientation |
| `HamstringStretchPose` | `ankleB = torsoPitch` | back foot flat | No — deliberate foot orientation |
| `PikePushUpPose` | `ankleF = legPitch` | pike leg articulation | No — leg intent |
| `PikePushUpPose` | `hand = -torsoGlobalPitch` | hand aligned to the piked trunk | No — net hand orientation w.r.t. limb |
| `JumpSquatPose` | `ankle = leanAngle - footPitch` | plantar flexion retained through the lean | No — foot articulation |
| `JumpSquatPose` | `hand = leanAngle + flightFactor*0.3` | wrist flick in flight | No — arm articulation |
| `DynamicWorldsGreatestStretch` | `ankleB = -footPitchB` | back-foot plantar flexion | No — foot articulation |
| `ThoracicExtensionPose` | `ankle = 0f` | neutral (no-op) | No — explicit neutrality |

Every one of these sets the **extremity's own** orientation (a grip, a pointed
toe, a flat foot) expressed as a *net* rotation the engine consumes via the
relative-rotation path. **None negates a parent frame to undo inheritance**
(the defining signature of the old compensation). The word `torsoPitch`/`leanAngle`
appears inside some of these only as the *target* the foot is meant to reach, not
as a `-parentRotation` cancel.

**Verdict: zero production poses compensate for torso/spine/parent/lean/pitch.**

---

## 3. Can `overrideExtremityOrientation()` be removed from any exercise without changing the result?

**Yes — it already can, because no exercise calls it anymore.**

The only two references in the tree are:
- `BasePose.kt:190` — the *definition* of the helper `overrideExtremityOrientation(pose, extremity)`.
- `BaseValidationPose.kt:169` — the *definition* of the mirror helper.

A grep for call sites (`overrideExtremityOrientation(` excluding the `fun`
definitions and doc comments) returns **nothing**. Every production pose now
leaves all four extremities in the default `AUTOMATIC` mode, so the finalizer
always derives them. Removing the helper and the `ExtremityOrientationMode` /
`overrideExtremityOrientation` plumbing from `PoseDefinition`/`SkeletonPose`
would not change a single rendered extremity.

**Why it is safe to delete:**
- The opt-out was a deliberate escape hatch for the *leaning-shank / side-rolled /
planted-push-up* poses whose flat foot the perpendicular-to-shank derivation
could not reproduce. PR #121 intentionally removed those overrides to *expose*
the engine limitation rather than hide it. With the overrides gone, the opt-out
has zero callers.
- The `ExtremityOrientationMode` array + `getExtremityOrientationMode` /
  `isExtremityAutomatic` / `overrideExtremityOrientation` / `copyFrom` propagation
  become dead machinery that exists only to serve a path no pose takes.

**Proposed cleanup (optional, no behavioural change):**
- Delete `enum class ExtremityOrientationMode`, the `extremityOrientation` array,
  `getExtremityOrientationMode`, `isExtremityAutomatic`, `overrideExtremityOrientation`
  from `PoseDefinition.kt` / `SkeletonPose`.
- In `SkeletonPoseFinalizer.finalize()`, delete the `if (isExtremityAutomatic(...))`
  guards and just call `adjustFootOrientation` / `adjustHandOrientation` directly.
- Delete the two `overrideExtremityOrientation` helpers in `BasePose` and
  `BaseValidationPose` (and the now-dangling `BaseValidationPose` doc comment).

This shrinks the surface and removes the "ownership is an explicit opt-out"
indirection that was only needed while overrides still existed.

---

## 4. Ownership diagram

```
POSE AUTHOR  (e.g. BasePushUpPose.build)
   │
   │  anatomy only:
   │   • limb IK target (hand/foot world pos)
   │   • pelvis / chest / hip / shoulder local rotations (trunk + segment intent)
   │   • ankle / wrist *articulation*  = the extremity's OWN net rotation
   │       (grip, dorsi/plantar, point-to-sky, flat)  — NOT a parent cancel
   │   • NO heel/toe/palm/knuckles/fingertips positions
   │   • NO -torsoPitch / -parentRotation counter-rotations
   ▼
SkeletonPose  (jointsBuffer: local positions + local rotations + AUTO mode)
   │
   │  SkeletonPose.fromHierarchy(roots, jointsBuffer)
   ▼
FK  (updateWorldTransforms → flatten)  → world positions + world rotations per joint
   │
   │  reconstructChestFrame  (chest only if author left it identity)   [Issue F]
   ▼
SkeletonPoseFinalizer.finalize()
   │
   │  for each extremity (AUTOMATIC by default):
   │    rel = relativeRotation(worldJoint, worldParent)   ← cancels inherited tilt, keeps net articulation
   │    adjustFootOrientation  → shank dir + rel + FootDefinition ratios
   │    adjustHandOrientation  → forearm dir + rel + HandDefinition lengths
   ▼
ENGINE-DERIVED EXTREMITY ORIENTATION
   │    heel, toe      ← FootDefinition.computeHeelToe
   │    palm, knuckles, fingertips  ← HandDefinition.computeHandJoints
   ▼
Renderer  (reads the completed SkeletonPose; no pose-side geometry left to honour)
```

**Ownership boundary (post-W1 + PR #121):** the pose owns *anatomy* (where
limbs go, what the trunk does, and the extremity's own articulation intent). The
engine owns *geometry* (every heel/toe/palm/knuckles/fingertips position) and
*cancels inherited parent tilt automatically* via the relative-rotation path.
There is no longer any node-existence inference or per-pose override in the
production set.

---

## 5. Can a new developer write an exercise without thinking about heel / toe / palm / fingertips / torso compensation / parent rotation?

**Yes — for the common case, and that is the whole point of W1 + PR #121.**

A new production pose now needs to express, per limb:
1. a world target for the hand/foot (via `bakeIkLimb` / `solveIK`),
2. the trunk/segment base rotations (pelvis, chest, hip, shoulder) as *anatomy*,
3. the extremity's **own** articulation as a *net* `ankle/hand.localRotation`
   only when the exercise genuinely wants a grip, a pointed toe, plantar flexion,
   etc. — never to undo the trunk.

The developer does **not** write heel/toe/palm/knuckles/fingertips positions, and
does **not** negate `torsoPitch` / `parentRotation.angle` / `leanAngle`. If they
set a neutral ankle/wrist (or set nothing beyond the IK target), the engine lays
the foot/hand flat along the limb with the inherited tilt already removed.

**Caveats a new developer must still know (the residual engine limits):**
- The derivation lays the foot **perpendicular to the shank**. On a *near-horizontal*
  shank (heavy forward lean, side-rolled frame) the foot will not be "flat on the
  floor" — it follows the limb. PR #121 deliberately left this exposed rather than
  re-introducing an override, so a developer should expect the foot to track the
  shin and not be surprised if a leaning pose's foot looks off. (This is the one
  place the engine is *not* yet smart enough to "do the right thing" automatically.)
- The foot direction is derived from the **shank**, the hand from the **forearm**.
  If a pose wants a stylized extremity the shank/forearm cannot express (e.g. a
  curled fist, a pointed ballet toe, the planted push-up palm), the *only* supported
  mechanism is `overrideExtremityOrientation` — which still exists in the engine
  (Q3 cleanup is optional). So a developer who needs a truly stylized extremity
  must learn that one escape hatch.
- Genuine articulations (grip, dorsi/plantar, point-to-sky) are still authored as
  the net `ankle/hand` rotation — that is intent, not compensation, and is expected.

**Bottom line:** a new developer can ignore heel/toe/palm/fingertips and all
torso/parent/lean/pitch compensation entirely for the vast majority of exercises.
They only need to think about extremities when they want a *specific grip or foot
orientation*, and even then they author the net articulation, not a parent cancel.
The single remaining sharp edge is the near-horizontal-shank foot, where the engine
derivation intentionally mirrors the limb rather than forcing a floor-flat foot.
