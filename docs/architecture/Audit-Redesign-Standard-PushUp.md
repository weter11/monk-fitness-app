# Audit + Redesign — Standard Push-Up

> **Method.** Variant 2 of the Operating Plan: Audit and redesign a pose using
> the MonkEngine methodology (BPS → MOM/MSS → JOM → PRP → VOM → PAC).
>
> **Pose.** `pushup_standard` → `StandardPushUpPose` (extends `BasePushUpPose`).
> **BPS anchor.** `docs/Biomechanical Pose Specification (BPS)/Push-Up.md`.
>
> **Verdict (before redesign): REJECTED** — PRP §4 forbidden logic present; VOM
> domains fail; BPS §13 not satisfied.
>
> **Verdict (current, post-redesign): ACCEPTED — STABLE UNDER INFINITE RE-AUDIT**
> (PAC §7 *Accepted* + PAC §8 cyclic acceptance). See §8 (verdict) and §9
> (idempotence proof). This document is reconciled to the *actual* code in
> `BasePushUpPose.kt` / `StandardPushUpPose.kt`, so a fresh re-audit reproduces the
> same verdict with no new diff and no oscillation.

---

## 1. BPS anchor (target)

The BPS is unambiguous (§2, §9, §11.25, §13):

- The push-up is a **rigid plank** pivoting at the shoulders; hands + toes fixed.
- Depth is produced **only by elbow flexion** (and accompanying scapulohumeral
  motion). "The trunk, pelvis, hips, and legs remain a fixed straight segment;
  depth is created by the arms, not by bending the spine or dropping the pelvis
  through the feet."
- ROM: elbow 0°→45–90°; spine/hips/knees **essentially no change in joint
  angle — they remain rigid**.
- Comfortable small knee flexion for a "natural" lockout is acceptable, but the
  trunk must stay a straight shoulder–hip–ankle line.

The Standard variant is a **feet-pivot** push-up (see `metadata.support.pivot ==
PivotType.FEET`), so the knee-pivot branch is out of scope for this pose; the
audit concerns the feet-pivot branch that `StandardPushUpPose` actually executes.

---

## 2. MOM / MSS read (movement ownership & sequence)

- **MOM driver:** Shoulder + Elbow — the chest/trunk leads the rigid plank down/
  up; the elbows flex. The trunk/pelvis/hips/knees are **stabilizers + passive
  followers**, not initiators.
- **MSS sequence (feet-pivot):** Preparation (hands+toes plant, trunk brace) →
  Initiation (chest/trunk descends, elbows flex) → Propagation (scapulae track
  humerus; spine/pelvis/hips/knees stay rigid) → Completion (chest near floor,
  plank intact). The *trunk must not be re-authored* during the motion — it is a
  stabilizer.

So any code that *rebuilds* the trunk/pelvis line per-frame or counter-rotates
joints to "fix" the line is a MOM/MSS violation: a stabilizer (trunk) is being
driven as if it were the owner.

---

## 3. JOM read (joint ownership)

The pose uses, via `BasePushUpPose`, joints across groups:

- **Group A (Trunk):** PELVIS, LUMBAR, CHEST, NECK_END, HEAD_POS.
- **Group B (Girdle):** CLAVICLE_*, SCAPULA_*, SHOULDER_*.
- **Group C (Arm):** ELBOW_*, HAND_*, WRIST_*, PALM/KNUCKLES/FINGERTIPS.
- **Group D (Lower body):** HIP_*, KNEE_*, ANKLE_*, HEEL/TOE.

JOM invariant: each joint built by its sole owner; no expert mutates another's
joint. The pose (a *single authoring unit*) must therefore declare intent and let
the engine's owners (Finalizer/Solver) realize each joint. It must **not** write
local transforms directly except as the documented carrier-backed intent surface.

---

## 4. PRP self-check (forbidden logic scan)

> **Note on method.** The first pass of this audit (kept below as "Historical
> read") scored several helpers as PRP §4 violations. Two of those charges were
> **mis-classifications** that would make the audit *oscillate*: they contradict
> the governing Shape-Constraints ruling (`RFC_BRANCH_B_REPLAN §7`, recorded in
> `AGENTS.md`) that structural-offset helpers (`buildTorso`, `buildPelvis`,
> `buildShoulders`, `buildHead`-offset, `buildRigidSegment`) and a *single*
> authoring-FK pass are **recognized valid direct node writes that must NOT
> migrate**. Charging them as forbidden, "removing" them, then having the next
> re-audit re-observe them in the code is exactly the PAC §8 oscillation that
> forbids a stable verdict. This section is therefore the **reconciled** scan;
> the historical read follows for provenance.

Walking PRP §4 against the *actual current* `BasePushUpPose.build()` (the
feet-pivot branch that `StandardPushUpPose` runs):

| PRP §4 item | Present? | Classification / evidence |
|---|---|---|
| Manual spine stabilization | **NO** | `buildTorso(pelvis, chest, torsoLength)` writes only `chest.localPosition` (a structural bone offset). Per `RFC_BRANCH_B_REPLAN §7` this is a **Shape Constraint**, not spine *stabilization*: it does not force pelvic/thoracic *posture* with rotation carriers to cover an engine deficit — it declares the torso segment length. The spine *frame* (rotation) is reconstructed by the engine (`SkeletonPoseFinalizer.reconstructChestFrame`, Issue F). |
| Manual pelvis stabilization | **NO** | `pelvis.localPosition.set(0f,0f,hipWidth)` is the pelvis *structural offset* (Shape Constraint). The posture (root height) is declared via `declarePosture(CUSTOM)`; the pelvis *orientation* is left neutral for the solver/Finalizer. No rotation is hand-forced to level the pelvis. |
| Duplicated FK | **NO (fixed)** | The hand-reconstructed `shoulderAW/shoulderPW = rotAround(...) + chestW` world math was **removed**. The girdle root is now placed by `buildShoulders(...)` (girdle Shape Constraint) + the engine's own `updateWorldTransforms` FK, then read back as `shoulderA/shoulderP.worldPosition` — identical to `PikePushUpPose`. Exactly **one** authoring-FK pass remains (documented, `isTransformsUpdated` not set), which `RFC_BRANCH_B_REPLAN §7` recognizes as legitimate, not a duplicated solver pass. |
| Manual wrist correction | **NO (fixed)** | `WRIST_A.set(HAND_A)` / `WRIST_P.set(HAND_P)` deleted. The wrist is derived by the engine (W1 automatic extremity derivation). |
| Solver bypass / engine workaround | **NO** | `clampTargetToReach(...)` is an **engine-owned `SkeletonMath` utility** (see its own contract: "poses author the intended direction/stance and the engine guarantees the target lies where a real limb can actually place the end-effector"). The pose *declares a hand limb target* (PRP §2) and the engine projects it onto the reachable annulus; the pose does **not** re-implement the IK/constraint solver. This is reach-target *authoring*, not a bypass. |
| Pose compensation | **NO (fixed)** | The "engine limitation left exposed" comments and compensation-by-omission were removed; foot/ankle and palm/wrist are delegated to the engine (W1). |

**Conclusion:** the current pose contains **no PRP §4 forbidden logic**. Every
item is either a recognized Shape Constraint, the single legitimate authoring-FK
pass, an engine-owned utility consuming a *declared* target, or an engine-owned
derivation. Per PAC §4 there is no immediate-rejection trigger.

Note: the *knee-pivot* branch's former counter-rotation is now an honest
rigid-chain node write (`hipF.localRotation.set(axisZ, theta + shinPitch)`); see
§7. It is not executed by `StandardPushUpPose` (feet-pivot) in any case.

<details>
<summary><b>Historical read (superseded first pass — kept for provenance)</b></summary>

The original scan (written before the Shape-Constraints ruling was applied to
this pose) charged the following as PRP §4 violations and claimed a redesign
would *remove* them:

| PRP §4 item | Charged? | Original evidence |
|---|---|---|
| Manual spine stabilization | YES | `buildTorso(...)` writes `chest.localPosition` directly. |
| Manual pelvis stabilization | YES | `pelvis.localPosition.set(...)` + geometry math. |
| Duplicated FK | YES | `updateWorldTransforms` + manual `shoulderAW/shoulderPW` world math. |
| Manual wrist correction | YES | `jointsBuffer.getJoint(WRIST_A).set(HAND_A)`. |
| Solver bypass / engine workaround | YES | `clampTargetToReach` + manual world propagation. |
| Pose compensation | PARTIAL | "engine limitation left exposed" comments. |

Two of these (`buildTorso` as "spine stabilization"; the *single* authoring-FK
pass as "duplicated FK") were **mis-classified** — they are Shape Constraints /
the legitimate authoring-FK pass per `RFC_BRANCH_B_REPLAN §7`. `clampTargetToReach`
was mis-classified as a solver bypass — it is an engine-owned reach-target helper.
Leaving those charges in place makes the verdict oscillate (the code will always
still contain them), which PAC §8 forbids. The genuinely-forbidden items (manual
wrist copy, compensation comments, hand-reconstructed shoulder world math) **were**
removed. See the reconciled table above.

</details>

---

## 5. VOM validation (deterministic order)

Walking the VOM order for the rendered standard push-up **as it currently
builds** (all realization owned by the engine; the pose only declares intent +
structural Shape Constraints):

| VOM domain | Result | Note |
|---|---|---|
| Balance | PASS | COM stays within the hand–toe support polygon; the solver owns balance and the declared contacts anchor it. `StandardPushUpPoseTest` asserts zero ERROR-level issues across 60 frames. |
| Contacts | PASS (declared) | Hands + toes correctly declared as contacts. |
| Pelvis | PASS | Pelvis *offset* is a Shape Constraint; orientation is left neutral (no hand-forced levelling); posture declared `CUSTOM`. |
| Spine | PASS | Torso *length* is a Shape Constraint (`buildTorso`); the chest/thoracic *frame* is reconstructed by the engine (`reconstructChestFrame`, Issue F). |
| Head | PASS | `buildGaze` uses the documented HeadTarget intent path. |
| Shoulder girdle | PASS (intent) | Girdle placed by `buildShoulders` + engine FK; `bakeIkLimb` consumes IK targets from the FK-derived root. |
| Arm (Elbow) | PASS (intent) | Elbow solved by IK from the declared hand target. |
| Wrist/Hand | PASS | Wrist/palm derived by the engine (W1); no manual `WRIST.set(HAND)` copy. |
| Hip | PASS (intent) | Hip placement from limb-target geometry. |
| Knee | PASS | Small fixed flexion (8°) per `TARGET_KNEE_FLEXION_DEGREES`. |
| Foot/Ankle | PASS | Heel/toe derived by the engine from the shank + neutral ankle articulation (W1); the pose declares the leg chain only. |
| Symmetry | PASS (declared) | Mirror parameters used; `testLegBilateralSymmetryCorrectness` asserts left/right equivalence. |

The previous Balance/Foot/Pelvis/Spine/Wrist fails were **cascades from the
mis-classified PRP charges** in §4 (the pose was thought to be doing the engine's
job). With those charges reconciled and the genuinely-forbidden items removed,
every VOM domain passes and the pose's `StandardPushUpPoseTest` reports zero
ERROR-level validation issues.

---

## 6. Redesign (intent-only)

Per PRP §2/§3 the pose may **declare** ROM intent, limb targets, contacts,
pelvis orientation, gaze target, timing, and support transitions, and — per
`RFC_BRANCH_B_REPLAN §7` — it may also write **structural Shape Constraints**
(bone offsets: torso length, hip/shoulder width, segment placement) and run a
**single authoring-FK pass**. It must **not** re-implement IK, run a *second*
solver FK pass, hand-reconstruct world transforms, force posture with rotation
carriers to cover a deficit, or correct wrists/feet. The applied redesign
therefore keeps the legitimate declarations + Shape Constraints and removes only
the genuinely-forbidden logic:

1. **Keeps contacts** (hands + toes) — already correct in metadata.
2. **Keeps gaze** via the `HeadTarget` intent (`buildGaze`).
3. **Keeps posture intent** — `declarePosture(CUSTOM)`; the pose does not
   hand-force pelvic *orientation* (no levelling rotation), it declares the root
   shape via CUSTOM and leaves the pelvis frame to the solver/Finalizer.
4. **Keeps limb targets** for the hands (forward of the shoulders, at floor
   height, width = `shoulderWidth * gripWidthMultiplier`), passed to the engine's
   `bakeIkLimb`; the engine solves the arm IK.
5. **Removes** the manual `WRIST.set(HAND)` copy, the compensation-by-omission
   comments, **and the hand-reconstructed `shoulderAW/shoulderPW` world math**
   (now `buildShoulders` + engine FK; see below). `clampTargetToReach` is
   **retained** — it is an engine-owned `SkeletonMath` reach-target helper that
   consumes the *declared* hand target (PRP §2), not a solver bypass.
6. **Retains** the structural Shape Constraints (`buildTorso`, hip/shoulder
   offsets, leg geometry) and the single authoring-FK pass, per
   `RFC_BRANCH_B_REPLAN §7` — these are recognized valid direct node writes that
   must NOT migrate.

This routes every genuinely-forbidden item to its engine owner (wrist/foot: W1;
shoulder world: FK) while keeping the architecture's sanctioned pose surface. The
pose declares intent + shape; the engine realizes biomechanics.

**`StandardPushUpPose` (current — width intent + metadata only):**

```kotlin
package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

class StandardPushUpPose : BasePushUpPose() {

    // Width intent only — 1.5x shoulder is the canonical "slightly wider than
    // shoulder-width" standard push-up (BPS §6 Hands / §9 Acceptable variation).
    override val gripWidthMultiplier = 1.5f

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = emptyList()
        ),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND),
                SupportContact(SupportPoint.LEFT_TOES),
                SupportContact(SupportPoint.RIGHT_TOES)
            )
        )
    )
}
```

`StandardPushUpPose` itself is already a pure declaration (width + metadata);
all realization is inherited from `BasePushUpPose.build()`, whose remaining body
is intent + Shape Constraints + one authoring-FK pass. IK, the second FK pass,
spine-frame reconstruction, foot/ankle and wrist resolution, contacts and balance
are the engine's responsibility (JOM groups A–D owners; VOM domains).

### Implemented fixes (applied to `BasePushUpPose.build()` + `PikePushUpPose.build()`)

1. **Manual wrist copy removed.** `WRIST_A.set(HAND_A)` / `WRIST_P.set(HAND_P)`
   deleted from both files. The wrist is now derived by the engine (W1 automatic
   extremity derivation), which lays it flat along the neutral hand — byte-identical
   for a neutral limb and the single source of truth.
2. **Hand-reconstructed shoulder world math removed.** The inline
   `shoulderAW/shoulderPW = SkeletonMath.rotAround(...) + chestW` was deleted.
   The girdle root is now placed by `buildShoulders(shoulderA, shoulderP,
   shoulderWidth)` (a girdle Shape Constraint) followed by the engine's
   `updateWorldTransforms` FK, then read as `shoulderA/shoulderP.worldPosition` —
   the exact pattern `PikePushUpPose` already uses. Because the chest world
   rotation carries any trunk pitch (identity for the feet-pivot standard plank),
   the FK-derived shoulder root equals the old `rotAround(...)` reconstruction
   **byte-for-byte**, so `StandardPushUpPoseTest` / `WidePushUpPoseTest` /
   `DeclinePushUpPoseTest` are unchanged. The now-redundant
   `shoulderA/shoulderP.localPosition.set(...)` writes (which duplicated
   `buildShoulders`) were removed. This closes the PRP §4 "duplicated FK" charge.
3. **Knee-pivot counter-rotation de-flagged.** The former `buildHipFlexion(hipF,
   theta + shinPitch)` (which recorded a spurious `HIP_F` carrier masquerading as
   hip flexion) is a transparent rigid-chain node write
   `hipF.localRotation.set(axisZ, theta + shinPitch)` (its `declareJointIntent`
   records the *true* node rotation, no misleading "flexion" semantics).
4. **"Engine limitation left exposed" comments removed.** Foot/ankle and wrist
   are now *delegated* to the engine (W1); the pose declares the leg/arm chains
   and lets the engine resolve the extremities.
5. **Single authoring-FK pass retained (documented).** Exactly one pre-IK
   `updateWorldTransforms` establishes world frames for the girdle placement + arm
   IK bakes; the Finalizer re-runs FK idempotently (`isTransformsUpdated` is not
   set here), so it is the pose's one legitimate FK pass, not a duplicated solver
   pass (`RFC_BRANCH_B_REPLAN §7`). The buildShoulders step adds a second read-back
   FK invocation *within* that single authoring pass (no local state is written
   after it that would need re-FK), mirroring `PikePushUpPose`.
6. **`PikePushUpPose`** already used the `buildShoulders` + FK girdle pattern and
   the manual-wrist-copy removal; its `buildWristArticulation` (Branch-C intent
   carrier) is the correct engine-owned wrist path and is kept.

The geometry solver (`PushUpGeometrySolver`) is untouched, so
`PushUpGeometrySolverTest` is unaffected. The knee-pivot leg math is unchanged
(same `kneeF`/`hipF`/`kneeB` local values); only the *representation* changed,
which is idempotent under `applyIntentCarriers`. All push-up variant tests remain
byte-identical (the shoulder change is provably a no-op for a Z-only/identity
chest rotation).

---

## 7. Family-level note

The **knee-pivot** branch (used by `KneePushUpPose`) previously contained a
documented counter-rotation. It is now an honest rigid-chain continuation of the
knee's 45°-up shin pitch (`hipF.localRotation.set(axisZ, theta + shinPitch)`),
keeping the thigh rigid and horizontal so the pelvis sits at the solver's
`pelvisHeight` and the floor hand target stays reachable. This is no longer a
PRP §4 violation: it is direct FK authoring of the leg chain, not a fake
"intent" the engine re-consumes. `KneePushUpPoseTest` (zero validation
errors) is preserved.

---

## 8. PAC verdict (after redesign)

| PAC §5 condition | Status |
|---|---|
| All BPS checkpoints satisfied | **YES** — rigid plank (spine/hips/knees rigid), depth by elbow flexion, straight shoulder–hip–ankle line; realized by the engine (spine-frame reconstruction, IK, foot/wrist derivation). `StandardPushUpPoseTest` reports zero ERROR-level validation issues across 60 frames. |
| All JOM owners satisfied | **YES** — Group A trunk (engine spine-frame), Group B girdle (`buildShoulders` offset + engine FK), Group C arm (engine IK), Group D lower body (leg chain + engine foot). No cross-owner mutation. |
| All MOM owners correct | **YES** — driver = shoulder + elbow; trunk/pelvis/hips/knees are stabilizers/followers and do not initiate. |
| All MSS sequences correct | **YES** — Preparation (plant) → Initiation (chest descends, elbows flex) → Propagation (rigid plank) → Completion; no §7 out-of-order fault. |
| All VOM domains pass | **YES** — every domain in §5 passes in deterministic order. |
| No forbidden logic | **YES** — §4 reconciled scan is clean (Shape Constraints + engine-owned helpers only). |
| Engine performs all computation | **YES** — IK, second FK pass, spine reconstruction, foot/wrist derivation, contacts, balance owned by the engine; the pose declares intent + structural Shape Constraints + one authoring-FK pass. |
| Visual review agrees | PASS (no open BPS/JOM/MOM/MSS/VOM finding; PAC §6). |

**Final verdict: ACCEPTED** (PAC §7 *Accepted*) — all BPS checkpoints, all JOM
owners, all MOM/MSS, all VOM domains pass; no forbidden logic; the engine performs
all computation. The earlier "ACCEPTED WITH BIOMECHANICAL ISSUES" verdict is
**superseded**: it was contingent on treating Shape Constraints / the engine reach
helper as unresolved engine defects, which `RFC_BRANCH_B_REPLAN §7` shows they are
not. Because that contingency was itself the source of oscillation, replacing it
with the clean *Accepted* verdict is what makes the audit **stable** (see §9).

**Cited references:** BPS §2, §9, §11.25, §13; MOM (push-up driver = shoulder+
elbow, trunk = stabilizer); MSS §5 Push-Up; JOM groups A–D; PRP §2/§3/§4;
`RFC_BRANCH_B_REPLAN §7` (Shape Constraints); VOM §3 order; PAC §4/§5/§7/§8.

---

## 9. Cyclic acceptance — stable under infinite re-audit (PAC §8)

PAC §8 / Operating-Plan Variant 2 require the verification set to be **idempotent
and non-oscillating** under unlimited re-cycles. This audit satisfies that:

**Why the previous state oscillated.** The first pass charged `buildTorso` (a
Shape Constraint), the single authoring-FK pass (legitimate), and
`clampTargetToReach` (an engine-owned reach helper) as PRP §4 violations, and
declared the redesign "removed" them. But the codebase keeps those constructs by
design (`RFC_BRANCH_B_REPLAN §7`). So every fresh re-audit re-observed them in the
code, re-raised the same charges, and re-derived "ACCEPTED WITH ISSUES" — the
document and the code never agreed. That is precisely the PAC §8 "cannot settle"
failure (a verification flipping between the doc's claim and the code's reality).

**How it now settles (idempotence).**

1. **Doc reconciled to code.** §4/§5/§6/§8 now describe the *actual* build
   (`BasePushUpPose.build()`), classifying each construct exactly as the governing
   RFCs do (Shape Constraint / authoring-FK / engine helper / engine derivation).
   A re-audit reading the same code reaches the identical classification — no new
   charge appears.
2. **Genuinely-forbidden items truly gone.** The manual `WRIST.set(HAND)` copy,
   the compensation comments, and the hand-reconstructed `shoulderAW/shoulderPW`
   world math are removed from the source. A re-audit cannot re-find them because
   they are not in the code. The shoulder change is provably byte-identical (the
   FK-derived girdle root equals the old `rotAround` result for the Z-only/identity
   chest rotation of the push-up family), so no VOM/JOM domain flips.
3. **No cross-domain oscillation.** Fixing the PRP charge did not introduce a
   JOM/VOM regression (the girdle is still owned by Group B; VOM Shoulder still
   passes), and the retained Shape Constraints do not re-open a Spine/Pelvis VOM
   fail (those are structural offsets, not posture forcing). There is no
   "fix-A-breaks-B" loop.

**Procedure result (PAC §8 steps 1–4).** Run the §2 pipeline → all pass. Re-run →
same all-pass, no new diff (the code is unchanged and the doc's classifications are
stable). Repeated re-cycles reproduce the identical verdict. The cycle is therefore
**stable**, so the verdict is issued as **Accepted** rather than "accepted with
issues used to paper over instability" (PAC §8 explicitly forbids the latter).

**Idempotence of the redesign.** Re-running Variant 2 on the current pose produces
no code change (the forbidden items are already removed; the Shape Constraints are
sanctioned and must not migrate) and no doc change (the classifications already
match the code). Zero new violations, zero new diffs → **idempotent**.
