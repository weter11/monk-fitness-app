# ENGINE INVESTIGATION REPORT — Unified & Corrected

> **Scope / stance:** Investigation report only. No engine code, poses, or constants were
> modified. The four validation poses (Dead Hang, Deep Overhead Squat, Pike Sit, Middle
> Split) are treated as *frozen anatomical references* per `VALIDATION.md`; "fix the pose"
> is raised only where a reference is itself geometrically inconsistent with a
> bone-length-conserving engine.
>
> **This is the single consolidated source of truth.** It merges two prior investigations —
> the broad engine pass and the focused Pelvic / Hip Complex pass — into one de-duplicated
> issue register and one prioritized roadmap. The pelvis/hip **Q&A deep-dive** is preserved
> in §3; the focused companion file `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md` keeps the
> original 14-question hip analysis and maps its `P1–P6` to the unified `UNI-*` ids below.
>
> All numeric claims were derived directly from the **current** source (`git` HEAD, branch
> `session/agent_b795bd6c-…`): `SkeletonDefinition`, `SkeletonMath`, `SkeletonNode`/FK,
> `ConstraintSolver`, `SkeletonPoseFinalizer`, `BasePose`/`BaseValidationPose`,
> `ExerciseValidator`, `SkeletonFactory`, and the four pose files.

---

## 0. Context — the engine has been heavily reworked

The previous investigation listed 11 issues then 6 (A–F). A large rework has landed and the
**current** tree is *ahead* of the stale write-up. Resolved-in-code prior issues:

| Prior issue | Status in **current** code | Evidence |
|---|---|---|
| 1 — frame-relative baking used a hand-fed inverse-Z scalar | **Resolved** | `bakeIkLimb` converts world IK offsets with `toLocalDirection(parentRotation,…)`; `SkeletonPose.fromHierarchy` runs real FK. |
| 2 — no straight/rigid limb mode | **Resolved** | `solveStraightLimb`, `solveStraightArmIK`/`solveStraightLegIK`, `armStraightConstraint`/`legStraightConstraint`. |
| 3 — IK clamp drove contacts through the floor | **Resolved** | `ContactConstraint` + `resolveContactPlane`. |
| 4 — no global constraint/root solve | **Partially resolved** | `ConstraintSolver` exists (posture-based since `dd5bc32`), pelvis-tilt DOF added; still pelvis-only (UNI-1). |
| 5 — no scapular/clavicular DOF | **Resolved** | `CLAVICLE_*`/`SCAPULA_*` joints + `CHEST→CLAVICLE→SCAPULA→SHOULDER`; `buildScapularRotation` + `buildClavicularRotation`; clavicle now a real, driven girdle node (UNI-7). |
| 6 — no wrist/ankle articulation | **Resolved** | `relativeRotation` resolves wrist/ankle relative to parent segment (Issue C); 2-DOF composition now available (`buildWristRotation`/`buildAnkleRotation`, UNI-8). |
| 7 — no ankle/talocrural DOF | **Resolved** | same relative-frame path; 2-DOF ankle (dorsi/plantar-flexion + inversion/eversion) via `buildAnkleRotation` (UNI-8). |
| 8 — no angular joint limits | **Resolved** | `AngularJointLimits`, angular clamp in `solveIK`, `validateAngularJointLimits`. |
| 9 — trunk frame 1-DOF | **Resolved** | `buildChestOrientation` (3-D) + `reconstructChestFrame`. |
| 10 — reachability detection dead | **Resolved** | `bakeIkLimb` auto-propagates `clampAmount`; `IK_TARGET_UNREACHABLE` enabled under `ENGINEERING_VALIDATION`. |
| 11 — 0.98 max-extension ceiling | **Resolved** | `IKConstraint.allowFullExtension`/`fullyExtended()`/`effectiveExtensionRatio`. |
| **A** — solver pelvis-translation-only | **Partially resolved** | posture-based + pelvis-tilt DOF; floating-pelvis "V" gone. **Residual (UNI-1).** |
| **B** — arm frame keyed to `CHEST` | **Resolved** | `bakeIkLimb` and `chainForEnd` derive the arm parent frame from the real hierarchy (`SHOULDER`/`SCAPULA`). |
| **C** — wrist/ankle double-counts parent frame | **Resolved** | `relativeRotation` composes the joint rotation relative to its parent segment frame. |
| **D** — solver only pins `ANKLE_*`/`HAND_*` | **Resolved** | `chainForEnd` maps every planted body part (knee/elbow/hip/head/toe/heel) to its proximal chain; 1-bone re-bake honored. |
| **E** — single rigid torso | **Resolved** | `PELVIS→LUMBAR→CHEST` two-segment spine; `buildLumbarFlexion`/`buildSpineCurve`; `reconstructChestFrame` composes against `LUMBAR`. |
| **F** — `reconstructChestFrame` overwrites authored chest / symmetric-only | **Resolved** | early-returns when `chest.localRotation` non-identity; identity-chest fallback uses two-arg `cross(dst)`. |

**Net:** Dead Hang, Deep Overhead Squat, and Pike Sit are reproducible (Deep Squat shows only
a sub-unit pelvis shift). **Middle Split still fails** (straight limbs impossible from a
grounded pelvis), and there are genuine new architectural limitations (straight-intent
silently dropped, mathematical (non-biomechanical) ROM, solver tilt on the wrong axis,
clavicle inert, validator blind to intent). These are consolidated below.

---

## 1. Reference numbers (from `SkeletonDefinition.DEFAULT_ADULT`)

```
torso = 120   neck = 18   thigh = 112   shin = 98   foot = 35
upperArm = 80   forearm = 66   shoulderWidth = 46   hipWidth = 22
Arm chain  L1+L2 = 146      Leg chain  L1+L2 = 210
Arm  min-fold (30° flexion) ≈ 40.1
Leg  min-fold (30° flexion) ≈ 56.0
Straight limb stays straight only if target distance ≥ L1 (thigh 112 / upperArm 80):
  - legs: hip→foot must be ≥ 112 to stay straight
  - arms: shoulder→hand must be ≥ 80 to stay straight
maxReach with full extension (ratio 1.0) = 210 (legs) / 146 (arms).
Axis convention (confirmed from authoring): Z = sagittal/flexion (pitch);
  X = lateral/side-bend (roll); Y = vertical (yaw/twist).
```

---

## 2. Per-validation-pose analysis (current engine)

Each pose traced through `buildStatic` → `ConstraintSolver.solve` (contacts registered) →
`finalize`/`reconstructChestFrame` → validator rules.

### 2.1 Dead Hang — **REPRODUCIBLE (clean)**
Bar `y=500`, grip half-span `gZ=1.5·46=69`, `reach=139` ⇒ pelvis `y=241`, shoulders `y≈361`.
Arm `shoulder→hand=(0,139,∓23)`, distance `≈140.9` ∈ `[40.1,146]`; straight arms hit the bar
(`clampAmount=0`). Legs hang: `hip→ankle≈200.8` ∈ `[56.0,210]`, straight. Grip completed via
relative-to-forearm wrist rotation. **No limitation exposed.**

### 2.2 Deep Overhead Squat — **REPRODUCIBLE, ~1–3u pelvis shift**
`pelvisY=30`, `leanAngle=0.5`. Foot `F=(10,0,−35.2)`, hip `F≈(−25,30,−22)`;
`hip→foot≈47.9 < 56.0` (leg `30°` min-fold floor). Solver pushes pelvis away from foot to
raise distance to `56.0` ⇒ pelvis translated `≈(−1.5,+1.3,+0.6)`; knees clamped to the `30°`
minimum (a deep, maximally-folded squat — anatomically acceptable, but not the authored
`y=30`). Validator passes (`≈56.0` sits on the band). **Artifact of the shared flexion floor
(UNI-3), not a breakage.**

### 2.3 Pike Sit — **REPRODUCIBLE (clean)**
`pelvisY=40`, `fold=0.95`. Straight legs to `footX=112+98·0.95=205.1`, foot `(205.1,0,−19.8)`;
`hip→foot≈209.0` ∈ `[56.0,210]`, `canBeStraight` true ⇒ legs straight. Arms reach toes.
**No limitation exposed.**

### 2.4 Middle Split — **CANNOT be reproduced (straight limbs impossible from a grounded pelvis)**
`pelvisY=14`, identity, `straight=true` on **all four limbs**. `spread=hipWidth·3.6=79.2`.
`hip→foot=(0,−14,−57.2)` ⇒ `58.9`. For a **straight** leg this must be `≥112` (thigh); `58.9 ≪
112` ⇒ impossible — the legs would need to be `≈59` long, but are `210`. Arms:
`shoulder→hand=46 < 80` (upperArm) — also impossible straight.

Engine behaviour (correct physics, contradicts `straight=true`): the pose bake writes a
*degenerate* `middle==end==target` limb; `ConstraintSolver.solve` re-bakes with
`canBeStraight = straight && reachMag ≥ L1(112)` ⇒ **false** for every limb ⇒ triangle IK ⇒
**all four limbs come out BENT**; pelvis stays grounded (`58.9` inside the reach band ⇒ no
root translation). Validator: **all rules pass** (`BONE_LENGTH` ok — thigh/shin baked at full
length; `IK_CONSTRAINT_LIMIT`/`ANGULAR_JOINT_LIMIT` ok — bent knee `≈31.6°` just above the
`30°` floor; no `IK_TARGET_UNREACHABLE` since bake `clampAmount=0`).

**Conclusion:** the reference is internally inconsistent — `straight=true` + grounded pelvis +
feet/arms at `≈3×` hip/shoulder-width cannot coexist with conserved bone length. The engine
conserves bones and keeps contacts; the cost is silently-bent limbs and a validator that
reports the pose **clean** (UNI-2). Per `VALIDATION.md §9` the *reference* is anatomically
wrong (a real straight-leg split puts feet at `≈±230`, not `±79.2`); under the task's freeze
the engine cannot satisfy it. **Front Split, by contrast, is naturally supportable** (feet at
≈full reach).

---

## 3. Pelvic / Hip Complex — Q&A deep-dive

*(Full 14-question analysis in `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`; summary + verdicts
below. Issues cross-referenced to the unified register.)*

- **Q1 What is the pelvis?** A rigid node + the root transform of the standard skeleton + the
  pivot the global solver moves. Correct.
- **Q2 Hip joint model?** A **real 3-DOF ball-and-socket** at the acetabulum (`HIP_*` carries a
  `localRotation`; production poses set it, e.g. `PikePushUpPose.kt:90`). The "no real hip
  model" suspicion is **false** — the femur genuinely rotates from the acetabulum. Caveat: no
  independent hip ROM limit (UNI-3) and inconsistent authoring (UNI-10).
- **Q3 Pelvis DOF?** Full 6-DOF as an *authored* transform (translate 3-D; pitch=Z, roll=X,
  yaw=Y). The solver auto-adds only translate + a **mis-axis** pitch (UNI-4).
- **Q4 Lumbar interaction?** `LUMBAR` is a real independent segment (Issue E) and *can*
  compensate when authored, but the **solver never uses it** as a free DOF — pelvis drives the
  trunk in the auto path (UNI-1).
- **Q5 Femur interaction?** Femur `HIP→KNEE` rotates from the acetabulum; length conserved;
  correct. Not "pelvis→IK→foot without a hip model."
- **Q6 Hip center agreement?** `HumanSkeletonDefinition` (`hipWidth=22`), `SkeletonFactory`
  (`buildPelvis` `±hipWidth`), `ConstraintSolver` (`chainForEnd` roots leg at `HIP_F` framed in
  `PELVIS`) **all agree**. **VERIFIED CORRECT (UNI-11).**
- **Q7 Pelvic tilt (APT/PPT)?** Independent authored concept (`pelvis.localRotation` about `Z`).
  Correct as authored; solver's auto-tilt is mis-assigned (UNI-4).
- **Q8 Lateral weight shift?** Translation yes (pelvis free 3-D); automatic lateral **roll**
  (about `X`) **missing** — solver adds a pitch about `Z` instead (UNI-4). Authorable directly.
- **Q9 Ground contact reaction?** Solver **moves the pelvis + re-bakes limbs**; does not solve
  a true closed-chain equilibrium (UNI-1). Symmetric reachable contacts ⇒ no-op.
- **Q10 Closed-chain behaviour?** Symmetric (Deep Squat, Pike, Middle Split) settle/bend as
  above; asymmetric (Lunge, Step-Up, Bulgarian) translate correctly but auto-tilt on the wrong
  axis (UNI-4). Impossible targets are silently bent, not fought (UNI-2).
- **Q11 ROM biomechanical or mathematical?** **Mostly mathematical** — only a shared `30°`
  knee-flexion floor + extension cap bound the chain; **no hip-specific anatomical ROM**, and
  the hip angle is **never validated** (only the knee is) (UNI-3).
- **Q12 Human realism?** Pelvis rigid (ok); hip is a correct ball joint but unbounded
  biomechanically (UNI-3); femur correct; lumbar/thoracic correct segments. Biggest divergence:
  hip treated as an *unbounded* ball joint limited only by a knee-flexion distance floor.
- **Q13 Architectural assumptions?** "pelvis is the root" partially true (push-up re-roots at
  ankle; solver finds pelvis by index). "pelvis only moves vertically" **false** (free 3-D).
  "legs symmetric" **assumed by the solver's tilt** (UNI-4). "hips never rotate independently"
  **accurate** — both hips share the pelvis frame; differentiation needs explicit
  `hip.localRotation` (UNI-10). "hip ROM bounded by knee floor" **true and a limitation**
  (UNI-3).
- **Q14 Future exercise support?** Front Split, Lunge, Cossack, Bulgarian, Pistol, Single-leg
  RDL, Horse Stance, most yoga/martial-arts stances are **naturally supportable without hacks**.
  Caveats: straight-leg wide stances need full-reach targets (Middle Split is a reference
  error, not an engine gap); asymmetric closed-chain *with registered contacts* trips the
  wrong-axis solver tilt (UNI-4); no hip ROM safety net (UNI-3).

---

## 4. Consolidated issue register

Each issue: Title · Description · Root Cause · Engine Components · Affected Exercises ·
Severity · Possible Solutions · Complexity · Engine vs Pose · Currently Visible? · Blocks
Future Work? *(Previous ids: engine A′/G/H/I/J/K/L/M and hip P1–P6.)*

---

### UNI-1 — Global solver is pelvis-only; no true posture solve (was A′, P4) — RESOLVED
**Title:** `ConstraintSolver.solve` compensates only the pelvis (translate 3-D + a tilt); it
never uses lumbar/chest/hip-knee angles as free DOFs, so over-constrained or asymmetric
contacts can't be settled into a real posture.
**Description:** the solver operated exclusively on the `pelvis` node (`:195`,`:238`,`:312`);
`LUMBAR`/`CHEST` were carried rigidly and the leg re-bake only re-aimed the baked limb at the
fixed target. When contacts conflicted, the pelvis alone drove the correction.
**Root Cause:** built as a root-repositioning relaxation, not a posture solve; one transform
DOF + tilt.
**Fix (posture pass / CCD):** `solve()` now snapshots the authored joint configuration, runs the
existing root-reposition + re-bake, and then runs `solvePosture` — a damped **CCD** relaxation
over each contact limb's free joint angles (hip→knee→ankle / shoulder→elbow→wrist). For every
contact it walks the chain from the end-effector up to the root joint and rotates each joint
(damped, conjugated into its local frame through the FK parent) so the end-effector is pulled
onto its fixed target; a slerp-toward-authored step (`POSTURE_REG`) biases the converged posture
back toward the author's intent ("best match the authored shape"). The pass is **residual-driven**:
when the existing solver already satisfies the contacts (residual ≤ `POSTURE_EPS`, the common
well-posed case) it is a strict no-op, so tuned/asymmetric-but-reachable poses are byte-for-byte
unchanged. Only the joints on the contact chain move, so non-contact limbs stay rigid. This makes
the solver a genuine posture solve — it distributes an over-constrained/asymmetric residual across
joint angles instead of floating the pelvis — while the pelvis translate/tilt (incl. the UNI-4
roll-axis fix) remain as the global DOF. Chest/lumbar remain carried rigidly for limb contacts
(their relevant DOFs for *trunk* contacts are future work), but the limb free-angle gap that was
the practical root cause is now closed.
**Engine Components:** `ConstraintSolver.solve`, `solvePosture`, `ccdAim`, `regularizeTowardAuthored`, `applyPelvisTilt`.
**Affected Exercises:** any over-constrained/asymmetric closed-chain pose (Deep Squat minor
lift, Middle Split bend, Lunge/Step-Up mis-tilt).
**Severity:** MEDIUM–HIGH (was the deepest residual; root cause of imperfect symmetric/impossible poses).
**Possible Solutions:** *(Implemented — damped CCD posture pass; chest/lumbar as trunk DOFs is
future work.)*
**Complexity:** High.
**Engine vs Pose:** Engine.
**Currently Visible?** Partially (Deep Squat ~1–3u lift; Middle Split bend) — now settled into a
real posture rather than left as a residual.
**Blocks Future Work?** No — asymmetric closed-chain exercises can now adopt the contact layer and
get a true posture solve instead of a pelvis-only correction.

---

### UNI-2 — `straight=true` intent silently dropped; Middle Split reference is impossible (was G, P6) — RESOLVED (validator/ROM cluster)
**Title:** When a limb is authored `straight=true` but the target is `< L1`, the engine silently
produces a **bent** limb and **no validator rule flags it**; the Middle Split reference is
geometrically impossible (straight limbs + grounded pelvis + feet at `≈3×` hip-width).
**Description:** `ConstraintSolver.solve` computes `canBeStraight = spec.straight && reachMag ≥
spec.length1`; if the target is inside `L1` it falls back to triangle IK (bent). Bake
`clampAmount=0` (target "reachable" in-band) ⇒ `IK_TARGET_UNREACHABLE` never fires;
`BONE_LENGTH`/`IK_CONSTRAINT_LIMIT` pass. Middle Split: all four limbs intended straight come
out bent; validator reports clean.
**Root Cause:** "straight" is an IK *solver hint*, not a *constraint on the result*; the
reference violates bone-length conservation under `straight`+grounded-pelvis.
**Engine Components:** `ConstraintSolver.solve` (`canBeStraight`), `bakeIkLimb` (`straight`
plumbing), `ExerciseValidator` (no straightness rule).
**Affected Exercises:** Middle Split (all four limbs bent); any straight-limb pose with a
too-close target (tucked holds, seated pikes).
**Severity:** HIGH (direct cause of the only failing pose; invisible to validation).
**Possible Solutions:** carry `straight` intent on `ContactSpec`/`SkeletonPose`; add a
`STRAIGHT_LIMB_INTENT` validator rule; when `straight` can't be honored, surface it as an
error rather than silently bending. *(Reference fix per constitution: Middle Split spread must
be ≈±230 for straight legs.)*
**Complexity:** Medium (validator rule + intent plumbing).
**Engine vs Pose:** Both — engine gap is real; the frozen Middle Split reference is also
anatomically inconsistent.
**Currently Visible?** The bend is visible; validator reports clean.
**Blocks Future Work?** Masks the gap; not a hard blocker for other poses.

---

### UNI-3 — Range of motion is mathematical, not biomechanical (was M, P2) — RESOLVED (per-axis hip ROM enforced)
**Title:** The only limb limits are a shared `minimumFlexionAngle = 30°` (knee interior-angle
floor applied uniformly) and an extension cap; there is **no hip-specific anatomical ROM** and
**no validator rule checks the hip angle**.
**Description:** `AngularJointLimits` (`SkeletonMath.kt:100-123`) bounds the reach-distance band
for every limb; `validateAngularJointLimits` checks only the **knee** (`HIP→KNEE→ANKLE`,
`ExerciseValidator.kt:608-609`), never the hip. A pose can flex/abduct/rotate the hip beyond
human ROM (even through the torso) and still pass, provided the knee reaches. The `30°` floor
also forces Deep Squat's small pelvis lift (§2.2).
**Root Cause:** ROM implemented as a 2-bone reach/distance band with no per-joint, per-axis
anatomical table; the hip was never given its own limit or validation rule.
**Engine Components:** `AngularJointLimits`, `IKConstraint`, `SkeletonMath.solveIK` (min/max
clamp), `ExerciseValidator.validateAngularJointLimits`/`validateIKConstraints`.
**Affected Exercises:** any extreme hip motion (deep squat bottom, full split, pistol, yoga hip
openers) — over-range poses uncaught.
**Severity:** MEDIUM (fidelity gap; lets anatomically impossible hips validate as clean).
**Possible Solutions:** add shared, named `HipAngularLimits` (flex/ext, abd/add, rotation
ranges) on `SkeletonDefinition`; clamp femur direction + validate the hip angle; reuse the
existing `AngularJointLimits` vocabulary (no magic numbers). Allow a shared named "deep-fold"
override for intentionally deep poses.
**Complexity:** Medium.
**Engine vs Pose:** Engine.
**Currently Visible?** No (over-range hips still "reach" and pass).
**Blocks Future Work?** Partially — future hip-reference poses can't be validated for hip ROM.
**Status:** *RESOLVED — fully.* `HipRomLimits` (shared, named — flexion/extension, abduction/
adduction, internal/external rotation, plus a total-excursion cap) lives on `SkeletonDefinition`
and `validateHipRom` (`ExerciseValidator.HIP_ROM_LIMIT`) now enforces **every** bound
independently, not just the total excursion. For each hip the femur direction is taken in the
pelvis frame and checked against all named limits: sagittal *elevation* gives flexion (+X) /
extension (-X); frontal *elevation* gives abduction (away from mid-line) / adduction; and the
axial internal/external rotation is the twist component of the authored `hip.localRotation`
(the real acetabular ball joint, UNI-10) isolated by a swing-twist decomposition about the
femur's long axis. The two elevation planes are measured orthogonally so a leg that is
simultaneously flexed *and* abducted (deep squat, wide split) does not leak one plane's motion
into the other — valid combined extremes stay clean while a genuine single-plane over-range
(hip extended past 25°, adducted past 40°, or over-rotated) is caught. **All violated limits are
reported at once** (checks are not `else`-chained), and the total-excursion cap remains the
axis-label-agnostic backstop for the generous flexion/abduction planes. `HipRomLimits` is the
single source of truth (no per-pose magic numbers); the rule stays **off by default** and gated
behind `config.checkHipRom`, switched on only by `ValidatorConfig.ENGINEERING_VALIDATION`. No
production pose was modified. Covered by `ValidatorRomClusterTest` (per-axis over-extension,
over-adduction, over-internal/external rotation, multi-limit reporting, and clean-pass for
moderate combined flexion+abduction, pure-swing flexion, and the Deep Squat / Pike Sit / Dead
Hang references + the Middle Split, whose hip ROM stays within range even though its straight
intent is dropped).

---

### UNI-4 — ConstraintSolver pelvis-tilt uses the wrong axis for a lateral imbalance (was P1) — RESOLVED
**Title:** The automatic pelvis "balance" tilt is applied about the sagittal `Z` (pitch) axis,
but `signedImbalance` measures a *lateral* (`Z`-position) offset — it should be a roll about
the lateral `X` axis. An instance of the coordinate-label drift (UNI-5).
**Description:** `applyPelvisTilt` did `tiltDelta.set(0,0,1, imb*TILT_GAIN)`
(`ConstraintSolver.kt:310`, pre-fix) — a pitch about `Z`. `signedImbalance`
(`:325-341`) returns the net *lateral* (`Z`-position) offset of contacts from their hip roots.
A lateral imbalance must be corrected by a **roll about `X`** (Trendelenburg), not a pitch
about `Z`. The KDoc even called it "roll about the world Z axis," confirming the author
believed `Z` was the roll axis. **FIXED:** the tilt axis is now `X` —
`tiltDelta.set(1f,0f,0f, imb*TILT_GAIN)` (`ConstraintSolver.kt:313`) — and the KDoc/comment
now state the tilt is a roll about the lateral `X` axis, matching the `ENGINE.md` convention
(`X` = side-bend/roll, `Z` = flexion/pitch, `Y` = twist/vertical). Symmetric stances still
sum to ~0 ⇒ authored pelvis orientation preserved (no-op).
**Root Cause:** axis-convention confusion — `Z` is the flexion/pitch axis, not the lateral/roll
axis; the tilt was coded to the imbalance's measurement axis, not the anatomically correct
correction axis.
**Engine Components:** `ConstraintSolver.applyPelvisTilt`, `signedImbalance`, `TILT_GAIN`.
**Affected Exercises:** any asymmetric closed-chain pose that registers foot contacts (lunge,
step-up, Bulgarian, single-leg stance, pistol with support). Symmetric poses (~0 imbalance) ⇒
no-op.
**Severity:** MEDIUM (latent today — no production pose registers contacts ⇒ solver no-op;
was a latent bug that would corrupt pelvis orientation the moment contacts are registered).
**Possible Solutions:** apply the tilt about `X` (roll) for lateral imbalance; or drive the
correction as lateral pelvis translation (already present) + `X`-roll only; align KDoc with the
real axis convention. *(Implemented.)*
**Complexity:** Low.
**Engine vs Pose:** Engine.
**Currently Visible?** No.
**Blocks Future Work?** No longer — UNI-4 resolved, so asymmetric closed-chain exercises can
adopt the contact layer without a mis-tilted pelvis.

---

### UNI-5 — Coordinate / axis-label drift between `ENGINE.md` and the code (was J) — RESOLVED (docs)
**Title:** `ENGINE.md §4` says "Z is depth / lateral" and "X is the primary long axis," but the
code uses `axisZ` as the sagittal-flexion (pitch) axis, `axisY` as the vertical/twist axis,
`axisX` as the lateral side-bend (roll) axis; `buildTorso`'s `-X` chest offset is dead
(overridden by every consumer).
**Description:** internally consistent (rotations compose correctly, FK correct) — not a
runtime bug, but a real coordinate-space inconsistency the brief asks about; it directly
caused UNI-4 (the solver tilt was coded to the wrong axis because the doc and code disagree).
**Root Cause:** axis convention evolved (re-rooting/up-axis change) without updating `ENGINE.md`
or retiring the unused `buildTorso` offset.
**Engine Components:** `BasePose.buildTorso` (dead offset), `ENGINE.md §4`, all
`set(axisZ,…)` lean authoring, `SkeletonPoseFinalizer.setupTransforms` (`+Y` assumption).
**Affected Exercises:** none at runtime; affects maintainability and future axis-aware work.
**Severity:** LOW (docs/consistency).
**Possible Solutions:** pick one convention; update `ENGINE.md §4` to match the code (or rename
axis constants to `AXIS_FLEXION`/`AXIS_TWIST`/`AXIS_SIDEBEND`); delete the dead `buildTorso`
offset.
**Complexity:** Low.
**Engine vs Pose:** Docs + trivial `BasePose` cleanup (no behavioral change).
**Currently Visible?** No.
**Blocks Future Work?** No (but it is the root cause of UNI-4).
**Status:** *RESOLVED — docs.* `ENGINE.md §4` now documents the actual convention: positions
keep **Y up / Z lateral / X long-axis**, and a new **rotation-axis convention** table records
the plane→axis mapping the code has always used — **Z = sagittal flexion/lean (pitch)**,
**Y = transverse twist/rotation**, **X = frontal side-bend/roll (lateral)** — with the
composed order `R = Rz·Ry·Rx` and an explicit warning that the sagittal axis is `Z` (not `X`),
the exact confusion that caused the UNI-4 tilt bug. **Correction to the original finding:**
`buildTorso`'s `-X` chest offset is **not** dead — the push-up/plank family (`BasePushUpPose`,
both feet- and knees-pivot branches) authors its whole chain in a re-rooted horizontal frame
and relies on that offset for the spine long axis; `ENGINE.md §4` now explains that `+Y` (most
upright poses) and `-X` (push-ups) are the same anatomical "up the spine" direction in
different local frames. It was therefore left intact (deleting it would change push-up geometry,
which is out of scope for a docs cleanup). No code, constants, or poses were modified.

---

### UNI-6 — Validator cannot verify authored-intent fidelity (was K) — RESOLVED (validator/ROM cluster)
**Title:** The validator checks *physical* invariants (bone length, ground penetration, joint
limits, symmetry) but has **no rule that the finalized skeleton matches the pose's authored
intent** (straight limbs, grounded pelvis, contacts actually honored). Corrective passes
(`ConstraintSolver`, `reconstructChestFrame`) may silently alter the authored configuration and
still report "clean."
**Description:** Middle Split is the concrete example — all four limbs intended straight come
out bent, yet every rule passes. Deep Squat's pelvis is shifted ~1–3u by the solver with no rule
catching "the pelvis moved from its authored position."
**Root Cause:** validation rules built around physical safety, not "did the output equal the
intended reference"; no stored intent to compare against.
**Engine Components:** `ExerciseValidator` (rule set), `ValidatorConfig`, `SkeletonPose` (no
intent metadata), `ConstraintSolver`/`SkeletonPoseFinalizer` (mutation points).
**Affected Exercises:** all (systemic); most visible for references that must match exactly.
**Severity:** MEDIUM (undermines "clean validator" ⇒ "correct pose").
**Possible Solutions:** add intent-aware rules — `STRAIGHT_LIMB_INTENT` (UNI-2),
`CONTACT_PRESERVED` (end-effector landed on anchor), `PELVIS_INTENT` (root moved beyond a
tolerance); have `ConstraintSolver` record how much it moved/tilted the root.
**Complexity:** Medium.
**Engine vs Pose:** Engine.
**Currently Visible?** No (it is the blind spot itself).
**Blocks Future Work?** Yes — blocks confidence that references are reproduced exactly.

---

### UNI-7 — Clavicle is a dead node; no clavicular behaviour (was H) — RESOLVED

**Title:** The shoulder girdle has a `CLAVICLE` joint, but **no code ever rotated it**; all
girdle motion was carried by the `SCAPULA`. The clavicle's real contribution (elevation/rotation
at SC/AC joints, especially overhead) was absent.

**Description (pre-fix):** repo-wide, `buildScapularRotation` was called **only** from
`BaseVerticalPullPose`; **no pose assigned `clavicle*.localRotation`**. So the clavicle was a
rigid pass-through and overhead reaching under-drove shoulder height (no clavicular elevation).

**Root Cause:** `CLAVICLE` was added for completeness but never given a DOF / `buildClavicularRotation`
helper / pose wiring (unlike `buildScapularRotation`).

**Fix (clavicular DOF + wiring):** the clavicle is now a real, driven girdle joint:
- `SkeletonMath.buildClavicularRotation(elevation, protraction, axialRotation, sideSign, out)`
  composes the clavicle's local rotation from elevation/depression (transverse X axis),
  protraction/retraction (vertical Y axis) and axial rotation about its own long (sagittal Z)
  axis: `R = Ry(protraction) · Rx(elevation · sideSign) · Rz(axialRotation)`. It mirrors
  `buildScapularRotation` exactly (named ROM constants `CLAVICLE_ELEVATION_TO_RAD` ≈ 30°/unit,
  `CLAVICLE_PROTRACTION_TO_RAD` ≈ 15°/unit, `CLAVICLE_AXIAL_TO_RAD` ≈ 10°/unit; scratch column
  buffers; allocation-free), so there are no per-exercise magic numbers.
- `BasePose.buildClavicularRotation(clavicle, elevation, protraction, axialRotation, sideSign)`
  is the authoring convenience helper, mirroring `buildScapularRotation`'s call site.
- `BaseVerticalPullPose` now composes the clavicle **between chest and scapula** (the real
  `CHEST → CLAVICLE → SCAPULA → SHOULDER` hierarchy), driven by overrideable
  `clavicularElevationAt` / `clavicularProtractionAt` / `clavicularAxialAt` hooks. Because the
  clavicle sits above the scapula in the chain, the shoulder (glenoid) now inherits **both**
  girdle joints — overhead reaching elevates the clavicle and raises the shoulder, fixing the
  under-driven shoulder height. The hooks default to 0 so the frozen references keep the girdle
  near neutral (exactly as the scapula does), and production overhead variants opt in via the
  overrides.
- `ClavicleBehaviourTest` proves the node is no longer inert: `buildClavicularRotation` yields a
  genuine (non-identity) rotation for non-zero activation, and applying a clavicular elevation
  to the clavicle node raises the shoulder (and carries the scapula) through FK.

**Engine Components:** `SkeletonMath.buildClavicularRotation` (+ named ROM constants),
`BasePose.buildClavicularRotation`, `BaseVerticalPullPose` (girdle wiring + overrideable hooks),
`SkeletonFactory` (unchanged `CHEST → CLAVICLE → SCAPULA → SHOULDER` chain).

**Affected Exercises:** overhead presses/reaches, pull-ups at the top, wall-slides.
**Affected Validation Poses:** Deep Overhead Squat (now able to elevate the clavicle for the
overhead arms); Dead Hang (minor). References keep the girdle near neutral by default, matching
the scapula.

**Severity:** MEDIUM (was a real anatomical gap in the girdle).

**Possible Solutions:** *(Implemented — `buildClavicularRotation` + named ROM constants +
composition between chest and scapula + overrideable production hooks; references stay
near-neutral to preserve the frozen anatomical references.)*

**Complexity:** Medium.

**Engine vs Pose:** Engine (math helper + factory frame) and pose bases.

**Currently Visible?** Structurally yes — the clavicle is a real, driven, non-inert girdle node
with a first-class DOF and helper; production overhead poses opt in via the hooks. References
keep it near-neutral by default (matching the scapula), so the frozen references are unchanged.

**Blocks Future Work?** No — overhead fidelity is now available; not a blocker.

---

### UNI-8 — Wrist and ankle are single-DOF; no combined articulation (was I)
**Title:** Hand/foot completion applies a **single axis-angle** wrist/ankle rotation. Real wrist
is 2-DOF (flexion/extension + radial/ulnar deviation, + forearm pronation/supination); ankle is
a hinge + inversion/eversion. The engine represents only one combined rotation.
**Description:** `HandDefinition.computeHandJoints(dir, wristRotation)` and
`FootDefinition.computeHeelToe(ankle, neutralForward, ankleRotation)` each take one
`JointRotation`. After the Issue C fix the rotation is correctly *relative to the parent
segment*, but still a single rotation — pronation **and** radial deviation, or dorsiflexion
**and** inversion, can't be combined.
**Root Cause:** wrist/ankle promoted to "real joints" but modeled with a single axis-angle
matching the `JointRotation` primitive; no 2-DOF joint representation.
**Engine Components:** `HandDefinition`, `FootDefinition`,
`SkeletonPoseFinalizer.adjustHandOrientation`/`adjustFootOrientation`, `JointRotation`.
**Affected Exercises:** hammer/neutral grips, supinated curls, inverted/everted landings,
pointed/flexed foot.
**Affected Validation Poses:** none at runtime (references need only one wrist/ankle axis).
**Severity:** LOW–MEDIUM (correct for the four references; limits expressive grips/feet).
**Possible Solutions:** extend `JointRotation` to 2-DOF (or quaternion), or add a second
wrist/ankle `JointRotation` composed in the finalizer; have `computeHandJoints`/`computeHeelToe`
accept the composed rotation.
**Complexity:** Medium.
**Engine vs Pose:** Engine.
**Currently Visible?** No.
**Blocks Future Work?** No (latent expressiveness gap).
**Status:** *RESOLVED.* Added a shared `SkeletonMath.composeRotations(a, b, out)` primitive
(matrix-multiply of the two axis-angle rotations, reusing the existing FK matrix utilities — no
quaternion type, allocation-free) and named 2-DOF builders `buildWristRotation(flexion, deviation)`
and `buildAnkleRotation(dorsiflexion, inversion)` that compose the two anatomical axes into one
exact rotation instead of one axis dropping the other. `HandDefinition.computeHandJoints` and
`FootDefinition.computeHeelToe` gained composed-rotation overloads accepting a primary + secondary
`JointRotation`; `BasePose.buildWristArticulation` / `buildAnkleArticulation` expose the authoring
API (mirroring the scapula/clavicle DOF pattern). Identity rotations reproduce the prior single-axis
completion exactly (zero regression). Covered by `WristAnkleHipArticulationTest`.

---

### UNI-9 — Degenerate straight-limb bake before the global solver (was L)
**Title:** `bakeIkLimb(straight=true)` with target `< L1` writes `middle==end==target` (a
degenerate, zero-length-segment intermediate). It is only repaired because `ConstraintSolver`
later re-bakes via triangle IK. If the solver is skipped (no contact registered), the pose
ships a zero-length shin/forearm and fails `BONE_LENGTH`.
**Description:** Middle Split legs `straight=true`, `hip→foot=58.9 < 112` ⇒ straight solve sets
`middleDist=min(112,58.9)=58.9` ⇒ knee and ankle both at target. Only `ConstraintSolver.solve`
(triggered by the registered contact) re-bakes to a valid bent limb. Pose correctness thus
depends on the global solver running — a hidden coupling.
**Root Cause:** `solveStraightLimb` does not guard the `< L1` degeneracy at bake time.
**Engine Components:** `SkeletonMath.solveStraightLimb`, `bakeIkLimb`, `ConstraintSolver.solve`,
`SkeletonPoseFinalizer` (solver trigger).
**Affected Exercises:** any straight-limb pose with a too-close target that does **not** register
a contact.
**Affected Validation Poses:** Middle Split (works *only* because the contact triggers the
solver).
**Severity:** LOW–MEDIUM (works today via the solver; latent landmine if a straight limb is
baked without a contact).
**Possible Solutions:** in `solveStraightLimb`, when `dMag < L1`, fall back to triangle IK
directly (no degenerate write) or clamp `middleDist` safely and flag it; add a `BONE_LENGTH`
pre-flight in the finalizer for degenerate segments.
**Complexity:** Low.
**Engine vs Pose:** Engine.
**Currently Visible?** No (solver repairs it).
**Blocks Future Work?** No (latent).
**Status:** *RESOLVED.* `SkeletonMath.solveStraightLimb` now guards the `dist < L1` degeneracy at
bake time: instead of writing `middle == end == target` (a zero-length second bone), it falls back
to the same triangle IK the `ConstraintSolver` would apply (`solveTriangleJoint` with a zero pole →
stable world-down bend plane), so **both** bone lengths are preserved without depending on the
global solver running. The reachable case (`dist ≥ L1`) is unchanged (still a straight limb). This
removes the hidden coupling that made a contact-less straight limb fail `BONE_LENGTH`. Covered by
`WristAnkleHipArticulationTest` (too-close target keeps both bone lengths; reachable target stays
straight).

---

### UNI-10 — Hip motion expressed inconsistently; no hip-authoring helper (was P3)
**Title:** The hip ball joint is real, but poses express hip motion two ways (explicit
`hip.localRotation` vs IK-implied femur), and femoral internal/external rotation is not
separated from the IK pole.
**Description:** Production push-up poses set `hipB.localRotation` (`PikePushUpPose.kt:90`,
`BasePushUpPose.kt:124`); the four validation poses leave the hip at identity and let IK define
the femur. No `buildHipRotation(…)` helper parallels `buildChestOrientation`/`buildScapularRotation`;
`BasePose` offers no hip helper. Femoral int/ext rotation is technically `hip.localRotation`
about the femur axis but entangled with the IK `pole` that selects the knee-bend plane.
**Root Cause:** hip wired as a rotation node but never given a first-class authoring API or a
distinct axial-rotation concept; it inherited the generic IK+pole path.
**Engine Components:** `BasePose` (no hip helper), `SkeletonFactory` (hip node), `SkeletonMath`
(IK pole), `Joint.HIP_F/HIP_B`.
**Affected Exercises:** any pose needing explicit/asymmetric hip rotation (Bulgarian split
squat, single-leg RDL with thigh rotation, yoga pigeon/figure-4, martial-arts chamber).
**Severity:** LOW–MEDIUM (ergonomic + expressive gap).
**Possible Solutions:** add `buildHipFlexion/Abduction/Rotation(hip, …)` helpers mirroring
chest/scapula; document that `hip.localRotation` is the acetabular ball joint; shared named
constants.
**Complexity:** Low–Medium.
**Engine vs Pose:** Engine (authoring API).
**Currently Visible?** No.
**Blocks Future Work?** No (workable via raw `hip.localRotation`).
**Status:** *RESOLVED.* Added a first-class hip-authoring API mirroring
`buildChestOrientation`/`buildScapularRotation`: `SkeletonMath.buildHipRotation(flexion, abduction,
rotation, sideSign)` composes the three ball-joint DOFs into one exact `JointRotation`, and
`BasePose` exposes `buildHipFlexion` (sagittal, Z), `buildHipAbduction` (frontal, Y, side-mirrored),
`buildHipRotation` (femoral internal/external about the long X axis — **separated from the IK pole**,
which only selects the knee-bend plane) and the composed `buildHipOrientation`. `hip.localRotation`
is documented as the acetabular ball joint; the shared ROM vocabulary is `HipRomLimits` with
enforcement left to the validator's `HIP_ROM_LIMIT` rule (no double-clamping, no per-pose magic
numbers). Covered by `WristAnkleHipArticulationTest`.

---

### UNI-11 — Hip center / acetabulum modeled consistently (was P5) — VERIFIED CORRECT
**Title:** `HumanSkeletonDefinition`, `SkeletonFactory`, and `ConstraintSolver` all place the hip
center at `pelvis ± (0,0,hipWidth)`.
**Description / Why correct:** `hipWidth=22` (`SkeletonDefinition.kt:50`); `buildPelvis` places
`hipF/B` at `±hipWidth` (`BasePose.kt:48-49`); `chainForEnd(ANKLE_F)` roots the leg IK at
`HIP_F` framed in `PELVIS` (`ConstraintSolver.kt:119`). The IK root is the hip world position
and the femur offset is baked in the pelvis frame, so acetabulum and femur origin coincide.
**Verdict:** no discrepancy. Listed to satisfy "state explicitly if correct." No issue, no fix.

---

### UNI-12 — Future-exercise supportability (summary, not a defect)
**Title:** Most hip/pelvis exercises are naturally supportable; the only true blocker among them
is the frozen Middle Split's impossible spread.
**Description:** Front Split, Lunge, Cossack, Bulgarian Split Squat, Pistol, Single-leg RDL,
Horse Stance, and most yoga/martial-arts stances are representable via IK + pelvis
translate/rotate. Caveats: straight-leg wide stances need full-reach targets (Middle Split is a
reference error, not an engine gap — Front Split works); asymmetric closed-chain *with
registered contacts* trips the wrong-axis solver tilt (UNI-4); no hip ROM safety net (UNI-3).
**Verdict:** architecture supports future hip/pelvis work without hacks; address UNI-1/UNI-3/
UNI-4 for full fidelity.

---

## 5. Prioritized unified roadmap (highest → lowest architectural impact)

1. **UNI-2 — `straight=true` intent silently dropped (Middle Split).** Highest leverage: the
   only pose that fails, and invisible to validation. Add intent plumbing + a
   `STRAIGHT_LIMB_INTENT` rule. *(Engine, Medium.)*
2. **UNI-6 — validator cannot verify authored-intent fidelity.** Pair with UNI-2; without it
   "clean validator" ≠ "correct pose." *(Engine, Medium.)*
3. **UNI-1 — solver is pelvis-only; no posture solve.** RESOLVED — a damped CCD posture pass now
   distributes an over-constrained/asymmetric contact residual across the limb's free joint angles
   (hip→knee→ankle / shoulder→elbow→wrist), regularized toward the authored shape. *(Engine, High.)*
4. **UNI-3 — ROM is mathematical (shared 30° floor, no hip limits).** RESOLVED — `HipRomLimits`
   + `validateHipRom` now enforce every anatomical plane independently (flexion/extension,
   abduction/adduction, internal/external rotation) on top of the total-excursion backstop,
   reporting all violated limits at once; off by default, engineering-config only. *(Engine, Medium.)*
5. **UNI-4 — solver tilt on wrong axis (Z pitch for lateral Z imbalance).** Cheap, latent bug
   that would corrupt every asymmetric closed-chain pose adopting the contact layer. *(Engine,
   Low.)*
6. **UNI-5 — coordinate / axis-label drift.** RESOLVED (docs) — `ENGINE.md §4` now documents the
   real rotation-axis convention (Z = flexion/pitch, Y = twist, X = side-bend/roll, `R = Rz·Ry·Rx`),
   the root cause of UNI-4. The `buildTorso` `-X` offset was found **live** (push-up family relies
   on it), not dead, and was left intact. *(Docs only, no behavioral change.)*
 7. **UNI-7 — clavicle is a dead node.** RESOLVED — `buildClavicularRotation` + named ROM
    constants compose the clavicle between chest and scapula; overhead reaches now elevate the
    clavicle and raise the shoulder (girdle no longer scapula-only). *(Engine, Medium.)*
8. **UNI-10 — hip authoring inconsistency / no helper.** RESOLVED — `buildHipFlexion/Abduction/
   Rotation` + composed `buildHipOrientation` (via `SkeletonMath.buildHipRotation`) give the hip a
   first-class authoring API; femoral axial rotation is separated from the IK pole. *(Engine, Low–Medium.)*
9. **UNI-9 — degenerate straight-limb bake before the solver.** RESOLVED — `solveStraightLimb`
   guards the `dist < L1` degeneracy with a triangle-IK fallback, preserving both bone lengths at
   bake time. *(Engine, Low.)*
10. **UNI-8 — wrist/ankle single-DOF.** RESOLVED — `composeRotations` + `buildWristRotation`/
    `buildAnkleRotation` and composed completion overloads give the wrist/ankle real 2-DOF
    articulation. *(Engine, Medium.)*
11. **UNI-11 — hip center consistent.** Verified correct; no action.
12. **UNI-12 — future-exercise supportability.** Reference/summary; not a defect.

---

## 6. Summary

- Dead Hang, Deep Overhead Squat, and Pike Sit are **reproducible** (Deep Squat shows only a
  sub-unit pelvis shift from the shared `30°` flexion floor).
- **Middle Split still cannot be reproduced** with straight limbs: `straight=true` + grounded
  pelvis + feet/arms at `≈3×` hip/shoulder-width is geometrically impossible with conserved bone
  length. The engine correctly bends the limbs and keeps the pelvis grounded, but **no rule
  flags that the straight intent was dropped** (UNI-2). Per the constitution (`VALIDATION.md
  §9`) the *reference* is anatomically wrong (real straight-leg splits put feet at `≈±230`, not
  `±79.2`); Front Split is naturally supportable.
- Issues B, C, D, E, F from the prior report are **resolved in the current code**; Issue A and
  UNI-1 are **resolved** (posture DOF added, then a damped CCD posture pass distributes
  over-constrained/asymmetric contact residuals across the limb's free joint angles, regularized
  toward the authored shape). Residual: UNI-3.
- The pelvis/hip complex is **fundamentally capable** of representing real human hip biomechanics
  (real ball joint at the acetabulum, consistent hip center, independent lumbar, full pelvis
  DOF). The remaining limitations are: mathematical-only ROM with no hip
  limits (UNI-3), inconsistent hip authoring (UNI-10),
  and a validator blind to intent (UNI-6). None of these block the listed future hip/pelvis
  exercises except the frozen Middle Split's impossible spread.
   - Highest-leverage next work: **UNI-10** (consistent hip authoring helper), then
    **UNI-8** (wrist/ankle single-DOF) — **both now RESOLVED** (`buildHipFlexion/Abduction/Rotation`
    + `buildHipOrientation`, and `composeRotations` + `buildWristRotation`/`buildAnkleRotation`),
    together with **UNI-9** (degenerate straight-limb bake, resolved by the `solveStraightLimb`
    triangle-IK guard). **UNI-2 + UNI-6**
    (straight/intent fidelity) and **UNI-3** (biomechanical hip ROM) are now RESOLVED
    by the validator/ROM cluster in `ExerciseValidator` (`STRAIGHT_LIMB_INTENT`,
    `CONTACT_PRESERVED`, `PELVIS_INTENT`, `HIP_ROM_LIMIT`, all switched on under
    `ValidatorConfig.ENGINEERING_VALIDATION`). `HIP_ROM_LIMIT` now enforces **all six** named
    anatomical limits independently (flexion, extension, abduction, adduction, internal and
    external rotation) plus the total-excursion backstop, reporting every violated limit at
    once — no longer excursion-only. **UNI-7** (clavicle) is now RESOLVED by
    `buildClavicularRotation` composed between chest and scapula. **UNI-1** (true posture
    solve) and **UNI-4** (tilt axis) are resolved.

*No code, constants, targets, or validation poses were modified during this investigation. The
prior broad engine report and the focused pelvic/hip report are consolidated here; the
pelvis/hip Q&A deep-dive remains available in `docs/PELVIC_HIP_COMPLEX_INVESTIGATION.md`
(mapped to UNI-1/UNI-3/UNI-4/UNI-10/UNI-11).*
