# ENGINE INVESTIGATION REPORT — Fundamental Biomechanical Limitations

> **Scope / stance:** Investigation report only. No code, constants, targets, or
> validation poses were changed. The four validation poses (Dead Hang, Deep
> Overhead Squat, Pike Sit, Middle Split) are treated as *frozen anatomical
> references* per the task brief and `VALIDATION.md`; "fix the pose" is only
> raised where the reference is itself geometrically inconsistent with a
> bone-length-conserving engine.
>
> **Code baseline:** this report reflects the **current** source tree
> (`git` HEAD, branch `session/agent_b795bd6c-…`), which is *ahead* of the
> prior investigation write-up. The global `ConstraintSolver` was reworked from
> translation-only to posture-based (`dd5bc32`, `9048393`, `4ef1376`),
> `bakeIkLimb` now derives its limb parent frame from the real hierarchy, the
> spine gained an independent `LUMBAR` segment (`bcbee92`), and the
> wrist/ankle-relative and chest-frame fixes landed (`987eec7`). All numeric
> claims below were derived directly from the current source
> (`SkeletonDefinition`, `SkeletonMath`, `ConstraintSolver`,
> `SkeletonPoseFinalizer`, `BasePose`/`BaseValidationPose`, and the four pose
> files) via hand-traced FK/IK arithmetic.

---

## 0. Context — the engine has been heavily reworked since the prior report

The previous investigation listed 11 issues and then 6 (A–F). A large rework has
landed. Re-reading the **current** source, the following prior issues are now
**resolved in code** and should be considered closed (the prior report's
"OPEN" status for A and B is stale against this tree):

| Prior issue | Status in **current** code | Evidence in current source |
|---|---|---|
| 1 — frame-relative baking used a hand-fed inverse-Z scalar | **Resolved (API)** | `bakeIkLimb` converts world IK offsets with `toLocalDirection(parentRotation,…)`; `SkeletonPose.fromHierarchy` runs real FK. |
| 2 — no straight/rigid limb mode | **Resolved** | `SkeletonMath.solveStraightLimb`, `solveStraightArmIK`/`solveStraightLegIK`, `armStraightConstraint`/`legStraightConstraint`. |
| 3 — IK clamp drove contacts through the floor | **Resolved** | `ContactConstraint` + `resolveContactPlane` inside `solveIK`/`solveStraightLimb`. |
| 4 — no global constraint/root solve | **Partially resolved** | `ConstraintSolver` exists and runs between IK and FK; now also has a pelvis-tilt DOF (see residual Issue A′). |
| 5 — no scapular/clavicular DOF | **Partially resolved** | `CLAVICLE_*`/`SCAPULA_*` joints exist; `CHEST→CLAVICLE→SCAPULA→SHOULDER` chain; `buildScapularRotation`. **Clavicle itself is inert (Issue H).** |
| 6 — no wrist/ankle articulation | **Partially resolved** | `adjustHandOrientation`/`adjustFootOrientation` resolve the joint rotation *relative to the parent segment* (Issue C resolved). **Still single-DOF (Issue I).** |
| 7 — no ankle/talocrural DOF | **Partially resolved** | Same relative-frame path; still single-DOF (Issue I). |
| 8 — no angular joint limits | **Resolved** | `AngularJointLimits`, angular clamp in `solveIK`, `validateAngularJointLimits`. |
| 9 — trunk frame 1-DOF | **Resolved** | `buildChestOrientation` (3-D) + `reconstructChestFrame`. |
| 10 — reachability detection dead | **Resolved** | `bakeIkLimb` auto-propagates `clampAmount` → `pose.maxIkClampAmount`; `ValidatorConfig.ENGINEERING_VALIDATION` enables `IK_TARGET_UNREACHABLE`. |
| 11 — 0.98 max-extension ceiling | **Resolved** | `IKConstraint.allowFullExtension`/`fullyExtended()`/`effectiveExtensionRatio`. |
| **A** — solver pelvis-translation-only | **Partially resolved** | `ConstraintSolver` now honors reachable contacts in place and adds a pelvis-**tilt** DOF; the floating-pelvis "V" is gone. **Residuals remain (Issue A′).** |
| **B** — arm frame keyed to `CHEST` | **Resolved** | Both `bakeIkLimb` and `ConstraintSolver.chainForEnd` now resolve the arm's parent frame from the **actual hierarchy** (`SHOULDER`/`SCAPULA`), not a hard-coded `CHEST` name. |
| **C** — wrist/ankle double-counts parent frame | **Resolved** | `relativeRotation` resolves the joint rotation relative to its parent segment frame before composing with the segment direction. |
| **D** — solver only pins `ANKLE_*`/`HAND_*` | **Resolved** | `chainForEnd` maps every planted body part (knee/elbow/hip/head/toe/heel) to its proximal chain; re-bake honors 1-bone limbs. |
| **E** — single rigid torso | **Resolved** | `PELVIS→LUMBAR→CHEST` two-segment spine; `buildLumbarFlexion`/`buildSpineCurve`; `reconstructChestFrame` composes against the `LUMBAR` parent. |
| **F** — `reconstructChestFrame` overwrites authored chest / symmetric-only | **Resolved** | Early-returns when `chest.localRotation` is non-identity; identity-chest fallback uses two-arg `cross(dst)` (non-degenerate). |

**Net:** Dead Hang, Deep Overhead Squat, and Pike Sit are now reproducible to
within a few engine units. **Middle Split still fails**, and there are genuine
*new* architectural limitations (straight-limb intent, girdle/clavicle, single-DOF
wrist/ankle, validation blind spots, coordinate-label drift). These are documented
below.

---

## Reference numbers (from `SkeletonDefinition.DEFAULT_ADULT`)

```
torso = 120   neck = 18   thigh = 112   shin = 98   foot = 35
upperArm = 80   forearm = 66   shoulderWidth = 46   hipWidth = 22
Arm chain  L1+L2 = 146      Leg chain  L1+L2 = 210
Arm  min-fold (30° flexion) ≈ 40.1
Leg  min-fold (30° flexion) ≈ 56.0
Straight-limb reach floor (triangle IK) = min-fold distance.
Straight limb becomes degenerate when target distance < L1 (thigh 112 / upperArm 80):
  - legs: target must be ≥ 112 from the hip to stay straight
  - arms: target must be ≥ 80  from the shoulder to stay straight
maxReach with full extension (ratio 1.0) = 210 (legs) / 146 (arms).
```

---

## 1. Per-validation-pose analysis (current engine)

Each pose was traced through `buildStatic` → `ConstraintSolver.solve` (contacts
registered) → `finalize`/`reconstructChestFrame` → validator rules.

### 1.1 Dead Hang  — **REPRODUCIBLE (clean)**
- Bar at `y=500`, grip half-span `gZ = 1.5·46 = 69`, `reach=139` ⇒ pelvis `y=241`, shoulders `y≈361`.
- Arm `shoulder→hand = (0,139,∓23)`, distance `≈140.9` ∈ `[40.1, 146]`. Reachable, `clampAmount=0`. Straight arms (full extension) hit the bar.
- Legs hang: `hip→ankle ≈ 200.8` ∈ `[56.0, 210]`. Reachable, straight.
- Grip completed via relative-to-forearm wrist rotation; trunk vertical so the old double-count is moot.
- **Verdict:** fully satisfiable. No engine limitation exposed.

### 1.2 Deep Overhead Squat — **REPRODUCIBLE with a minor (~1–3 unit) pelvis shift**
- `pelvisY=30`, `leanAngle=0.5`. Foot target `F=(10,0,−35.2)`. Hip `F` world `≈(−25,30,−22)`.
- `hip→foot` distance `≈47.9` **<** leg min-fold `56.0`. The solver must push the
  pelvis away from the foot to raise the distance to `56.0` (the `30°` minimum-flexion
  floor). Result: pelvis translated by `≈(−1.5, +1.3, +0.6)`; knees clamped to the
  `30°` minimum (a deep, maximally-folded squat — anatomically acceptable, but **not
  the authored `y=30` pelvis**).
- Validator: `BONE_LENGTH`, `IK_CONSTRAINT_LIMIT`, `ANGULAR_JOINT_LIMIT` all pass
  (actual `≈56.0` sits exactly on the band). No `IK_TARGET_UNREACHABLE` (clamp ≈ 0 in
  the bake because the *straight* path isn't used for legs here — `def.legIKConstraint`
  is the `0.98` band, and `dist` is already inside it).
- **Verdict:** essentially correct; the only artifact is the joint-limit floor forcing a
  small pelvis lift (Issue M / residual A′).

### 1.3 Pike Sit — **REPRODUCIBLE (clean)**
- `pelvisY=40`, `fold=0.95`. Straight legs to `footX = 112 + 98·0.95 = 205.1`, foot at `(205.1,0,−19.8)`.
- `hip→foot ≈ 209.0` ∈ `[56.0, 210]`. Reachable; `canBeStraight` true (`209 ≥ 112`); legs straight.
- Arms reach forward to the toes; torso folded but symmetric so `reconstructChestFrame` is a no-op or identity-equivalent.
- **Verdict:** fully satisfiable.

### 1.4 Middle Split — **CANNOT be reproduced (straight limbs impossible from a grounded pelvis)**
- `pelvisY=14`, identity rotation, `straight=true` on **all four limbs**.
- `spread = hipWidth·3.6 = 79.2`. Foot target `F=(0,0,−79.2)`. Hip `F` world `(0,14,−22)`.
- `hip→foot = √(0 + 14² + 57.2²) ≈ 58.9`. For a **straight** leg this must be `≥ 112`
  (thigh length). `58.9 ≪ 112` ⇒ a straight leg from a grounded hip to a foot at
  `z=∓79.2` is **geometrically impossible** — the legs would need to be `≈59` long,
  but they are `210`. Same for the arms: `shoulder→hand = 46 < 80` (upperArm).
- Engine behavior (correct, but contradicts `straight=true`):
  - In the pose `bakeIkLimb(straight=true)` the straight solve places `middle=end=target` (degenerate, knee==ankle).
  - `ConstraintSolver.solve` then re-bakes with `canBeStraight = straight && reachMag ≥ L1(112)` ⇒ **false** for every limb ⇒ falls back to triangle IK ⇒ **all four limbs come out BENT**, knees/elbows folded. The pelvis stays grounded (`58.9` is inside the reach band, so no root translation). This is the *right* physical response to an impossible target.
  - Validator: all rules **pass** (`BONE_LENGTH` ok because thigh/shin are baked at full length; `IK_CONSTRAINT_LIMIT`/`ANGULAR_JOINT_LIMIT` ok because the bent knee sits at `≈31.6°`, just above the `30°` floor; no `IK_TARGET_UNREACHABLE` because the bake `clampAmount=0`).
- **Verdict:** the reference is internally inconsistent — `straight=true` + grounded
  pelvis + feet/arms at `≈3×` hip/shoulder-width cannot coexist with conserved bone
  length. The engine conserves bones and keeps contacts; the cost is silently-bent
  limbs and a validator that reports the pose **clean** (Issues G, K). Per
  `VALIDATION.md §9` the reference itself is anatomically wrong (real straight-leg
  splits put the feet at `≈±230`, not `±79.2`); under the task's freeze the engine
  simply cannot satisfy it. This is the headline remaining gap.

---

## 2. Open engine issues (current code)

Each issue uses the required schema: Title, Description, Root cause, Affected engine
components, Affected exercises, Affected validation poses, Severity, Possible
architectural solutions, Estimated complexity, Engine-vs-pose location.

---

### Issue A′ (residual of A) — Posture solver still pelvis-only + joint-limit floor distorts authored posture

**Title:** Even after the posture-based rework, the global layer can *only* translate
and roll-tilt the pelvis; it cannot re-solve the posture across chest/hip/knee, and
the `30°` minimum-flexion floor reshapes reachable poses (Deep Squat pelvis lift).

**Description:**
`ConstraintSolver.solve` moves only `pelvis.localPosition` (reachability) and, for
ground contacts, composes a small **roll-about-Z** tilt (`applyPelvisTilt`,
`TILT_GAIN=0.01`). When a contact is reachable (Middle Split, Deep Squat) it is left
in place and the limb is re-baked — good — but the re-bake for a straight limb whose
target sits inside `L1` silently switches to triangle IK (bent). And for Deep Squat the
target distance (`47.9`) is *below* the `30°` min-flexion floor (`56.0`), so the solver
is forced to translate the pelvis to raise the distance, lifting the authored `y=30`
pelvis by ~1–3 units. There is no mechanism to instead "honor the contact as a surface
and absorb the error into the knee/hip/chest angles," nor to relax the floor for an
*intentionally* deep fold.

**Root cause:** the global layer models the problem as "move/tilt the root until each
contact is reachable," not "solve the free joints for the intended posture given fixed
contacts." A single translation + Z-roll DOF cannot redistribute error across the chain,
and the shared `minimumFlexionAngle = 30°` floor is applied uniformly even where a pose
wants a tighter fold.

**Affected engine components:** `ConstraintSolver` (`solve`, `applyPelvisTilt`,
`signedImbalance`), `IKConstraint.minimumFlexionAngle`, `AngularJointLimits`,
`bakeIkLimb` contact registration.

**Affected exercises:** every grounded/planted pose whose authored contact distance is
near or below the `30°` fold floor (deep squats, pistols, deep lunges, deep hinges,
splits, seated folds) and any multi-contact pose needing error absorbed by non-pelvic
joints.

**Affected validation poses:** **Middle Split** (limbs bent, see §1.4), **Deep Overhead
Squat** (minor pelvis lift).

**Severity:** MEDIUM (one pose fails outright; others get sub-unit drift).

**Possible architectural solutions:**
- Add a "posture solve" pass: given fixed contacts, solve the *free* joint angles
  (hip/knee/ankle/chest) by least-squares / CCD to best match the authored shape,
  instead of only translating the root.
- Allow a per-pose/per-limb override of `minimumFlexionAngle` (a deliberately deep fold
  is legitimate), sourced from shared named limits, not magic numbers.
- Make the global layer's contact model a true surface/anchor constraint for the end
  effector, deriving limb direction from the contact rather than forcing
  distance = limb length.

**Estimated complexity:** High.

**Fix location:** Engine (a new/different global layer; poses would only declare
contacts/limits).

---

### Issue G — `straight=true` intent is silently dropped; no enforcement or validation

**Title:** When a limb is authored `straight=true` but the target distance is `< L1`
(thigh/upperArm), the engine silently produces a **bent** limb, and **no validation rule
flags the discrepancy**.

**Description:**
`ConstraintSolver.solve` computes `canBeStraight = spec.straight && reachMag ≥
spec.length1`. If the target is inside `L1` (always true for Middle Split's
`58.9 < 112` legs and `46 < 80` arms), it calls `solveIK` (triangle), yielding bent
joints. The bake's `solveStraightLimb` had already written a *degenerate*
`middle==end==target` limb that the solver later repairs — but the **intent** (straight)
is simply gone, with `clampAmount=0` (the target was "reachable" in the band), so
`IK_TARGET_UNREACHABLE` never fires and `BONE_LENGTH`/`IK_CONSTRAINT_LIMIT` pass. The
output skeleton is bone-length-correct but **not** the authored straight-limb
configuration. There is no rule that asserts "this limb was requested straight and came
out straight."

**Root cause:** "straight" is treated as an IK *solver hint* (use `solveStraightLimb`),
not as a **constraint on the result**. The finalizer/solver have no obligation to
deliver straightness, and the validator has no rule keyed to the `straight` flag.

**Affected engine components:** `ConstraintSolver.solve` (`canBeStraight` gate),
`BasePose.bakeIkLimb`/`BaseValidationPose.bakeIkLimb` (`straight` flag plumbing),
`ExerciseValidator` (no straightness rule), `SkeletonPose` (no `straight` intent
metadata carried to the validator).

**Affected exercises:** any straight-limb pose whose target is closer than `L1` to the
root — e.g. tucked straight-leg holds, seated pikes with too-short reaches,
"superman"/hollow with arms/legs reaching inward, and any split/straddle authored with
sub-limb-length spread.

**Affected validation poses:** **Middle Split** (all four limbs intended straight, all
four come out bent). Latent for any production straight-limb pose with a too-close
target.

**Severity:** HIGH (it is the direct cause of the Middle Split failure and is invisible
to validation — a reference can be silently wrong and still pass).

**Possible architectural solutions:**
- Carry the `straight` intent on the `ContactSpec`/`SkeletonPose` and add a validator
  rule `STRAIGHT_LIMB_INTENT` that flags a limb whose solved middle joint deviates from
  collinearity beyond a tolerance.
- When `straight=true` cannot be honored (target `< L1`), surface it as an explicit
  `IK_TARGET_UNREACHABLE`/config error rather than silently bending.
- (Poses) ensure straight-limb targets are ≥ `L1` from the root — but that is a pose
  fix, not an engine one.

**Estimated complexity:** Medium (validator rule + intent plumbing; cheap, allocation-free).

**Fix location:** Engine (validator + solver intent signaling). Per the constitution the
*reference* Middle Split would also need correction, but the engine gap is real.

---

### Issue H — Clavicle is a dead node; no clavicular behaviour / scapulohumeral rhythm

**Title:** The shoulder girdle has a `CLAVICLE` joint in the hierarchy, but **no code
ever rotates it**; all girdle motion is carried by the `SCAPULA`. The clavicle's real
contribution (elevation/rotation at the sternoclavicular & acromioclavicular joints,
especially during overhead reach) is absent.

**Description:**
`SkeletonFactory` builds `CHEST→CLAVICLE→SCAPULA→SHOULDER` and the clavicle/scapula
nodes default to identity (coincident with the chest joint). A repo-wide search shows
`buildScapularRotation` is called **only** from `BaseVerticalPullPose`; **no pose ever
assigns `clavicle*.localRotation`**. So the clavicle is a rigid pass-through. During
overhead reaching (Deep Overhead Squat arms-up, pull-ups at the top) the real shoulder
rises partly because the clavicle elevates and rotates; the engine can only move the
shoulder via scapular depression/retraction, missing the clavicular component. The
whole "shoulder girdle" is effectively a single scapula rotation plus the arm IK.

**Root cause:** the `CLAVICLE` joint was added for anatomical completeness but was never
given a DOF, a `buildClavicularRotation` helper, or any pose wiring — unlike
`buildScapularRotation`.

**Affected engine components:** `SkeletonFactory` (girdle chain), `SkeletonMath`
(missing `buildClavicularRotation`), `BasePose`/`BaseValidationPose` (no helper),
`BaseVerticalPullPose` (only consumer of girdle DOF).

**Affected exercises:** overhead presses/reaches, pull-ups/chin-ups at the top,
wall-slides, any pose where the acromion should rise with the arm (currently the
shoulder height is under-driven).

**Affected validation poses:** **Deep Overhead Squat** (arms overhead — shoulder height
slightly under-driven), Dead Hang (minor, hang is mostly depression). Latent for the
references; visible in overhead production poses.

**Severity:** MEDIUM (a real anatomical gap in the girdle; masked today because the
references keep the scapula near neutral, but it limits overhead fidelity and is exactly
the "missing clavicle behaviour" the brief calls out).

**Possible architectural solutions:**
- Add `SkeletonMath.buildClavicularRotation(…)` (elevation about the sagittal/long axis,
  protraction about vertical, plus axial rotation) mirroring `buildScapularRotation`.
- Wire it into `BaseVerticalPullPose` and a new overhead base, composed **between** chest
  and scapula (`CLAVICLE` node) so the shoulder inherits clavicle∘scapula frames.
- Keep constants shared/named (clavicle ROM) — never per-exercise magic numbers.

**Estimated complexity:** Medium.

**Fix location:** Engine (math helper + factory frame) and pose base classes.

---

### Issue I — Wrist and ankle are single-DOF; no combined/2-DOF articulation

**Title:** The hand and foot completion apply a **single axis-angle** wrist/ankle
rotation. Real wrist motion is 2-DOF (flexion/extension **and** radial/ulnar deviation,
plus pronation/supination of the forearm) and the ankle is a hinge plus
inversion/eversion; the engine can represent only one combined rotation.

**Description:**
`HandDefinition.computeHandJoints(dir, wristRotation)` and
`FootDefinition.computeHeelToe(ankle, neutralForward, ankleRotation)` each take one
`JointRotation` (one axis, one angle). After the Issue C fix the rotation is correctly
*relative to the parent segment*, but it is still a **single** rotation. You cannot,
e.g., pronate **and** radially deviate the wrist, or dorsiflex **and** invert the foot,
simultaneously — the second DOF is dropped. `minPitch/maxPitch = ±45°` clamps the foot
result but does not add a second axis.

**Root cause:** the wrist/ankle were promoted to "real joints" but modeled with a single
axis-angle, matching the `JointRotation` primitive the engine uses everywhere. There is
no 2-DOF (e.g. Euler/quaternion) joint representation.

**Affected engine components:** `HandDefinition`, `FootDefinition`,
`SkeletonPoseFinalizer.adjustHandOrientation`/`adjustFootOrientation`,
`JointRotation` (single-axis primitive).

**Affected exercises:** any grip needing combined wrist motion (hammer/neutral grips,
supinated curls), any foot needing combined ankle motion (inverted/everted landings,
pointed/flexed ballet foot), pronation/supination within a grasp.

**Affected validation poses:** Dead Hang (overhand grip — single-axis, OK today),
Pike/Deep Squat (flat foot — OK today). **Latent**; the references happen to need only
one wrist/ankle axis.

**Severity:** LOW–MEDIUM (correct for the four references; a real limitation for
expressive grips/feet and for any future reference exercising combined wrist/ankle ROM).

**Possible architectural solutions:**
- Extend `JointRotation` to a 2-DOF (or quaternion) joint, or add a second wrist/ankle
  `JointRotation` composed in the finalizer.
- Have `computeHandJoints`/`computeHeelToe` accept the composed 2-DOF rotation.

**Estimated complexity:** Medium.

**Fix location:** Engine (`JointRotation`/`HandDefinition`/`FootDefinition`/finalizer).

---

### Issue J — Coordinate / axis-label drift between `ENGINE.md` and the code

**Title:** `ENGINE.md §4` states "Z is depth / lateral" and "X is the primary long axis
of the current frame," but the code uses **`axisZ` as the sagittal-flexion axis** (e.g.
`buildChestOrientation` lean is `set(axisZ,…)`, `applyPelvisTilt` rolls about Z),
**`axisY` as the vertical/twist axis**, and **`axisX` as the lateral side-bend axis**.
The poses also override `buildTorso`'s `-X` chest offset with a `+Y` chest offset.

**Description:**
The labeling in the constitution disagrees with the engine's actual convention. The
engine is **internally consistent** (rotations compose correctly, FK is correct), so
this is not a runtime bug — but it is a real *coordinate-space inconsistency* the brief
asks about, and it makes the code harder to reason about and to extend (e.g. a
maintainer trusting `ENGINE.md` will mis-read which axis a "lean" rotates about). The
dead `buildTorso` `-X` offset (overridden by every consumer) is the same smell.

**Root cause:** the axis convention evolved (likely a re-rooting/up-axis change) without
updating `ENGINE.md` or retiring the unused `buildTorso` offset; the doc and code
drifted.

**Affected engine components:** `BasePose.buildTorso` (dead/inconsistent offset),
`ENGINE.md §4`, all rotation-authoring calls (`set(axisZ,…)` for lean, `set(axisY,…)`
for twist), `SkeletonPoseFinalizer.setupTransforms` (legacy `+Y` assumption).

**Affected exercises:** none at runtime (internally consistent); affects maintainability
and any future axis-aware work.

**Affected validation poses:** none at runtime.

**Severity:** LOW (documentation/consistency, not a rendering defect).

**Possible architectural solutions:**
- Pick one convention, update `ENGINE.md §4` to match the code (or rename the axis
  constants to `AXIS_FLEXION`/`AXIS_TWIST`/`AXIS_SIDEBEND`), and delete the dead
  `buildTorso` offset or make it consistent.

**Estimated complexity:** Low.

**Fix location:** Docs + minor `BasePose` cleanup (no behavioral change).

---

### Issue K — Validator cannot verify authored-intent fidelity; corrective passes can silently mutate

**Title:** The validator checks *physical* invariants (bone length, ground penetration,
joint limits, symmetry) but has **no rule that the finalized skeleton matches the
pose's authored intent** (straight limbs, grounded pelvis, fixed contacts actually
honored). The engine's corrective passes (`ConstraintSolver`, `reconstructChestFrame`)
may therefore silently alter the authored configuration and still report "clean."

**Description:**
Middle Split is the concrete example: all four limbs intended `straight` come out bent,
yet every validator rule passes. Likewise the Deep Squat pelvis is shifted ~1–3 units by
the solver with no rule catching "the pelvis moved from its authored position." The
validator is a *sanity* checker, not a *fidelity* checker, so "the engine reproduced the
reference" is not actually verifiable for several dimensions. This blind spot is what
let the straight-limb gap (Issue G) go unnoticed.

**Root cause:** validation rules were built around physical safety (don't stretch bones,
don't penetrate, don't hyperextend), not around "did the output equal the intended
reference." There is no stored "expected" or "intent" to compare against.

**Affected engine components:** `ExerciseValidator` (rule set), `ValidatorConfig`,
`SkeletonPose` (no intent metadata), `ConstraintSolver`/`SkeletonPoseFinalizer`
(mutation points with no fidelity echo).

**Affected exercises:** all (the gap is systemic); most visible for reference/validation
poses that must match exactly.

**Affected validation poses:** **Middle Split** (silent bend), **Deep Overhead Squat**
(silent pelvis shift).

**Severity:** MEDIUM (undermines confidence that "clean validator" ⇒ "correct pose").

**Possible architectural solutions:**
- Add intent-aware rules: `STRAIGHT_LIMB_INTENT` (Issue G), `CONTACT_PRESERVED`
  (end-effector landed on its anchor), `PELVIS_INTENT` (root moved beyond a tolerance
  from authored).
- Have `ConstraintSolver` record how much it moved/tilted the root so validation can flag
  non-trivial corrections.

**Estimated complexity:** Medium.

**Fix location:** Engine (validator + intent metadata).

---

### Issue L — Degenerate straight-limb bake before the global solver (fragile coupling)

**Title:** `bakeIkLimb(straight=true)` with target `< L1` writes `middle==end==target`
(a degenerate, zero-length-segment intermediate). It is only repaired because
`ConstraintSolver.solve` later re-bakes via triangle IK. If the solver is ever skipped
(no contact registered, or a non-contact path), the pose ships a zero-length shin/forearm
and fails `BONE_LENGTH`.

**Description:**
In Middle Split the legs are `straight=true` with `hip→foot=58.9 < 112`; the straight
solve sets `middleDist = min(112, 58.9) = 58.9`, so knee and ankle both land at the
target. Only `ConstraintSolver.solve` (triggered because `contact` was registered and
`pose.hasContacts()`) re-bakes to a valid bent limb. The correctness of the *pose* thus
depends on the *global solver* running — a hidden coupling between the IK bake and the
posture layer.

**Root cause:** `solveStraightLimb` does not guard the `target < L1` degeneracy at bake
time; it assumes a later pass will fix it.

**Affected engine components:** `SkeletonMath.solveStraightLimb` (no `< L1` guard),
`bakeIkLimb`, `ConstraintSolver.solve` (implicit repair), `SkeletonPoseFinalizer`
(solver trigger).

**Affected exercises:** any straight-limb pose with a too-close target that does **not**
register a contact (so the solver never runs).

**Affected validation poses:** **Middle Split** (works *only* because the contact
triggers the solver). Latent for production straight-limb poses without contacts.

**Severity:** LOW–MEDIUM (works today via the solver; a latent landmine if a straight
limb is ever baked without a contact).

**Possible architectural solutions:**
- In `solveStraightLimb`, when `dMag < L1`, either fall back to triangle IK directly (no
  degenerate write) or clamp `middleDist` to a safe value and flag it.
- Add a `BONE_LENGTH` pre-flight in the finalizer for degenerate segments.

**Estimated complexity:** Low.

**Fix location:** Engine (`SkeletonMath.solveStraightLimb` + finalizer guard).

---

### Issue M — Uniform `30°` minimum-flexion floor prevents intentional max-fold

**Title:** `IKConstraint.minimumFlexionAngle = 30°` (shared `AngularJointLimits`) is
applied to every limb everywhere. A deliberately deep fold (tuck, deep squat bottom,
pike past parallel) wants the knee/elbow *tighter* than 30°, which the floor forbids,
forcing the solver to lift the root instead (Deep Squat §1.2) or bend at a non-authored
angle.

**Description:**
The `30°` floor exists to stop hyper-collapse, but it is a single global constant. The
result couples with Issue A′: Deep Squat's authored foot (`47.9` from hip) is below the
`56.0` floor, so the engine can't place the foot there without folding past 30°; it
chooses to lift the pelvis. Real deep squats fold the knee near 0°. The floor is also
why Middle Split's bent knees sit at `≈31.6°` (just above the limit) rather than the
tighter fold a real split would show.

**Root cause:** `minimumFlexionAngle` is a single shared value in `AngularJointLimits`;
there is no per-pose/per-limb "allow deeper fold" override expressed as shared named
limits.

**Affected engine components:** `AngularJointLimits`, `IKConstraint.minimumFlexionAngle`,
`SkeletonMath.solveIK` (min-dist clamp), `ConstraintSolver` (min-reach floor),
`validateAngularJointLimits`.

**Affected exercises:** deep squats, pistols, deep lunges, tucks, seated folds, any
max-fold pattern.

**Affected validation poses:** **Deep Overhead Squat** (pelvis lift), **Middle Split**
(knees clamped to ~31° instead of a tighter anatomical fold).

**Severity:** LOW–MEDIUM (interacts with A′/G; mostly a fidelity issue, not a breakage).

**Possible architectural solutions:**
- Introduce named, shared "deep-fold" limits and let poses opt into a lower floor via the
  `IKConstraint` they already pass to `bakeIkLimb` (no magic numbers).
- Separate the *solver* floor from the *validator* floor so a pose can author a deep fold
  the validator still checks against a sane anatomical max.

**Estimated complexity:** Low–Medium.

**Fix location:** Engine (`AngularJointLimits` vocabulary + pose constraint selection).

---

## 3. Prioritized roadmap (highest → lowest architectural impact)

1. **Issue G — `straight=true` intent silently dropped (no enforcement/validation).**
   Direct cause of the only pose that still fails (Middle Split), and invisible to the
   validator. Highest leverage: add intent plumbing + a `STRAIGHT_LIMB_INTENT` rule so
   the gap can never be silent again. *(Engine, Medium.)*

2. **Issue A′ — posture solver still pelvis-only + `30°` joint-limit floor distorts
   authored posture.** The deepest remaining architectural limitation: the global layer
   cannot re-solve posture across the chain, and the shared flexion floor reshapes
   reachable poses. Fixing it also improves Deep Squat / splits / hinges fidelity.
   *(Engine, High.)*

3. **Issue K — validator cannot verify authored-intent fidelity.** Without it, "clean
   validator" ≠ "correct pose," which is why G/A′ went unnoticed. Pair with G.
   *(Engine, Medium.)*

4. **Issue H — clavicle is a dead node (no clavicular behaviour / scapulohumeral
   rhythm).** The brief explicitly lists "missing clavicle behaviour"; limits overhead
   fidelity and is the most visible remaining *anatomical* gap in the girdle.
   *(Engine, Medium.)*

5. **Issue M — uniform `30°` minimum-flexion floor prevents intentional max-fold.**
   Interacts with A′; mostly a fidelity issue but cheap to relax via shared named limits.
   *(Engine, Low–Medium.)*

6. **Issue L — degenerate straight-limb bake before the global solver (fragile
   coupling).** Works today only because the contact triggers the solver; a latent
   landmine for contact-less straight limbs. *(Engine, Low.)*

7. **Issue I — wrist/ankle single-DOF (no combined articulation).** Real but latent for
   the four references; matters for expressive grips/feet. *(Engine, Medium.)*

8. **Issue J — coordinate / axis-label drift (docs vs code).** No runtime impact;
   maintainability and the brief's "coordinate-space inconsistency" item.
   *(Docs + trivial code cleanup, Low.)*

---

## 4. Summary

- Dead Hang, Deep Overhead Squat, and Pike Sit are now **reproducible** by the current
  engine (Deep Squat shows only a sub-unit pelvis shift from the `30°` fold floor).
- **Middle Split still cannot be reproduced** with straight limbs: `straight=true` plus a
  grounded pelvis plus feet/arms at `≈3×` hip/shoulder-width is geometrically impossible
  with conserved bone length. The engine correctly bends the limbs and keeps the pelvis
  grounded, but **no rule flags that the straight intent was dropped** (Issues G/K). Per
  the constitution (`VALIDATION.md §9`) the *reference* is anatomically wrong (a real
  straight-leg split puts the feet at `≈±230`, not `±79.2`); under the task's freeze the
  engine simply cannot satisfy it, and that is a fundamental limitation of a
  bone-length-conserving engine versus an impossible reference.
- Issues B, C, D, E, F from the prior report are **resolved in the current code**;
  Issue A is **partially resolved** (posture DOF added) with the residuals documented as
  A′/M.
- The highest-leverage next work is **G + K** (make straight-limb / intent fidelity
  observable and enforced), then **A′** (a true posture solve), then **H** (clavicle).

*No code, constants, targets, or validation poses were modified during this
investigation. The Middle Split reference, being internally inconsistent, is called out
as the one case where the constitution would direct a *pose* correction rather than an
engine fix.*
