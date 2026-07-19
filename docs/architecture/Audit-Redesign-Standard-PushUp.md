# Audit + Redesign — Standard Push-Up

> **Method.** Variant 2 of the Operating Plan: Audit and redesign a pose using
> the MonkEngine methodology (BPS → MOM/MSS → JOM → PRP → VOM → PAC).
>
> **Pose.** `pushup_standard` → `StandardPushUpPose` (extends `BasePushUpPose`).
> **BPS anchor.** `docs/Biomechanical Pose Specification (BPS)/Push-Up.md`.
>
> **Verdict (before redesign): REJECTED** — PRP §4 forbidden logic present; VOM
> domains fail; BPS §13 not satisfied.

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

Walking PRP §4 against `BasePushUpPose.build()` (the feet-pivot branch that
`StandardPushUpPose` runs):

| PRP §4 item | Present? | Evidence |
|---|---|---|
| Manual spine stabilization | **YES** | `buildTorso(pelvis, chest, torsoLength)` writes `chest.localPosition` directly (BasePose.kt:27-29) — the pose hand-builds the trunk line instead of declaring spine/pelvis intent. |
| Manual pelvis stabilization | **YES** | `pelvis.localPosition.set(0f,0f,hipWidth)` plus `pelvisHeight`/`theta` geometry math authored in the pose (PushUpGeometrySolver + build()). |
| Duplicated FK | **YES** | `roots.updateWorldTransforms(zeroVector, identityRotation)` is a manual FK pass inside the pose; plus manual `shoulderAW/shoulderPW` world-rotation math (lines 167-169). |
| Manual wrist correction | **YES** | `jointsBuffer.getJoint(WRIST_A).set(HAND_A)` (line 199) — admitted workaround for an engine limitation. |
| Solver bypass / engine workaround | **YES** | The pose computes leg geometry, clamps IK targets (`clampTargetToReach`), and manually propagates worlds instead of declaring limb targets + ROM intent and letting the engine solve. |
| Pose compensation | **PARTIAL** | Comments explicitly state the flat foot / palm "is intentionally NOT hand-authored… any visual shortfall is an engine limitation left exposed" — compensation-by-omission. |

**Conclusion:** the pose contains multiple PRP §4 forbidden items. Per PAC §4 this
is an **immediate rejection**.

Note: the *knee-pivot* branch additionally contains a documented **counter-
rotation** (`buildHipFlexion(hipF, theta + shinPitch)` to undo a torso that
"stands nearly vertical") — but that branch is not executed by `StandardPushUpPose`,
so it is recorded as a family-level defect (see §7) and not charged to this pose.

---

## 5. VOM validation (deterministic order)

Walking the VOM order for the rendered standard push-up:

| VOM domain | Result | Note |
|---|---|---|
| Balance | FAIL* | COM depends on the hand-authored `pelvisHeight`/`ankleX`; if the pose geometry is off, COM leaves the hand–toe support polygon. |
| Contacts | PASS (declared) | Hands + toes correctly declared as contacts. |
| Pelvis | FAIL | Pelvis orientation hand-authored (manual stabilization, §4). |
| Spine | FAIL | Trunk line hand-built via `buildTorso` (manual spine stabilization). |
| Head | PASS | `buildGaze` uses the documented HeadTarget intent path (acceptable). |
| Shoulder girdle | PASS (intent) | `bakeIkLimb` consumes IK targets. |
| Arm (Elbow) | PASS (intent) | Elbow solved by IK from hand target. |
| Wrist/Hand | FAIL | `WRIST.set(HAND)` manual copy; palm/footprint not engine-resolved. |
| Hip | PASS (intent) | Hip placement from limb-target geometry. |
| Knee | PASS | Small fixed flexion (8°) per `TARGET_KNEE_FLEXION_DEGREES`. |
| Foot/Ankle | FAIL | Foot "intentionally NOT hand-authored" → engine limitation left exposed (no resolved foot). |
| Symmetry | PASS (declared) | Mirror parameters used. |

*Balance/foot/pelvis/spine/wrist fails cascade from the PRP violations — the pose
is doing the engine's job and doing it incompletely.

---

## 6. Redesign (intent-only)

Per PRP §2/§3 the pose may **declare**: ROM intent, limb targets, contacts,
pelvis orientation, gaze target, timing, support transitions. It must **not** build
transforms, run FK, or correct wrists/feet. The redesign therefore:

1. **Declares contacts** (hands + toes) — already correct in metadata.
2. **Declares gaze** via the existing `HeadTarget` intent (keep `buildGaze`).
3. **Declares pelvis orientation** as intent (neutral, level, square) through the
   documented carrier, instead of `pelvis.localPosition.set(...)`.
4. **Declares limb targets** for the hands (forward of the shoulders, at floor
   height, width = `shoulderWidth * gripWidthMultiplier`) and lets the engine solve
   the leg chain + IK, rather than hand-computing `ankleX`/`theta`/`pelvisHeight`
   and calling `updateWorldTransforms`.
5. **Removes** `buildTorso`, the manual `WRIST.set(HAND)` copy, the inline
   `shoulderAW/shoulderPW` world math, and `clampTargetToReach` (the engine owns
   reach clamping). The engine's spine-frame reconstruction and foot/ankle resolution
   own those joints.
6. **Keeps** only: contact declaration, gaze intent, pelvis-orientation intent,
   hand/foot limb-target declaration, timing/support metadata.

This moves every PRP §4 violation back to its owner (engine: spine reconstruction,
FK, foot/ankle, wrist articulation, balance). The pose becomes a pure declaration.

**Resulting `StandardPushUpPose` (redesigned, intent-only):**

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

All geometry, IK, FK, spine reconstruction, foot/ankle and wrist resolution are
the engine's responsibility (JOM groups A–D owners; VOM domains). The base class
`BasePushUpPose` must likewise be reduced to *declaring* pelvis orientation intent
+ hand/foot limb targets and delegating realization — its current `build()` body is
the source of the PRP violations and must be trimmed to intent declaration.

---

## 7. Family-level defect (out of scope for this pose, recorded)

The **knee-pivot** branch (used by `KneePushUpPose`) contains a documented
**counter-rotation** — `buildHipFlexion(hipF, theta + shinPitch)` exists solely
to undo a torso that "stands nearly vertical" because the knee rotation pitched the
chain. This is PRP §4 "counter-rotations / engine workarounds / manual
stabilization" and is a separate Variant-1 family redesign item. It is recorded
here so the family audit is complete, but `StandardPushUpPose` does not execute it.

---

## 8. PAC verdict (after redesign)

| PAC §5 condition | Status |
|---|---|
| All BPS checkpoints satisfied | TARGET — engine must realize spine/foot/wrist per BPS §11/§13 |
| All JOM owners satisfied | TARGET — joints realized by engine owners, not pose |
| All VOM domains pass | TARGET — engine resolves spine/balance/foot/wrist |
| No forbidden logic | **YES (after redesign)** — pose declares intent only |
| Engine performs all computation | **YES (after redesign)** — IK/FK/spine/foot/wrist owned by engine |
| Visual review agrees | PENDING engine realization + visual pass |

**Final verdict: ACCEPTED WITH BIOMECHANICAL ISSUES** (PAC §7) — the pose is now
structurally owned correctly (PRP clean, engine owns computation), but the *realization*
of spine reconstruction, foot/ankle, and wrist articulation must be completed by the
engine before all BPS checkpoints pass. The pose itself no longer cheats.

**Cited references:** BPS §2, §9, §11.25, §13; MOM (push-up driver = shoulder+
elbow, trunk = stabilizer); MSS §5 Push-Up; JOM groups A–D; PRP §2/§3/§4; VOM
§3 order; PAC §4/§5/§7.
