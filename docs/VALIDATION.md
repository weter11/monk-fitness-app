# VALIDATION.md — Validation Poses and the Engineering Validation Subsystem

> Part of the project constitution. This document describes how validation poses
> are intended to work and how the Engineering Validation subsystem is used to
> verify the MonkEngine runtime. It describes intent and workflow, not a specific
> implementation.

---

## 1. What a Validation Pose Is (and Is Not)

A **validation pose is not an exercise.**

A validation pose is a *frozen reference configuration of anatomy* used to test
whether the MonkEngine runtime can reproduce a known-correct skeleton. It is a static
snapshot: it ignores animation progress, side, mirroring, and breathing, and
returns the same skeleton every time it is built.

- An **exercise** describes a movement the user performs and is part of the
  training product.
- A **validation pose** describes reference anatomy the MonkEngine runtime must be able to
  satisfy, and is a developer tool.

They are deliberately implemented as **parallel** systems. A validation pose is
built from the shared engine primitives (`SkeletonFactory`, `SkeletonMath`,
`SkeletonPose`) but does **not** depend on the exercise/workout/catalog systems
and does not reuse animation drivers, breathing, or loops.

---

## 2. The Contract: Validation Poses Are Diagnostic Instruments

> **Validation poses are no longer development targets. They are diagnostic
> instruments.**

A validation pose is an instrument you point at the MonkEngine runtime to **read its true
state**. Its reading must stay honest whether the MonkEngine runtime passes or fails.

The direction of responsibility that follows is fixed and must never be reversed:

> **A validation pose reports the MonkEngine's true state. You fix the MonkEngine runtime, or you
> record the reading — you never retune the instrument to make it read green.**

A development *target* is something the MonkEngine runtime is dragged toward until it goes
green. A diagnostic *instrument* is the opposite: you do not adjust the instrument
to change the reading. Retuning a pose (widening an IK target so a bent limb
resolves straight, raising a root so feet stop penetrating, softening an angle so a
ROM rule stops firing) to get a green result is **instrument tampering** — it is
adjusting the thermometer to lower the fever. It hides exactly the defect the pose
exists to expose.

If the MonkEngine runtime cannot reproduce a pose cleanly — the IK clamps, a bone stretches, a
limb flips, a support slides — the correct responses are:

1. **fix the root cause in the MonkEngine runtime**, or
2. **record the reading** (leave the pose as the faithful probe and track the MonkEngine runtime
   limitation it surfaces).

The one thing you must never do is move the instrument off the fault so the fault
stops registering.


---

## 3. Hard Isolation Rules

Validation poses must **never**:

- appear in workouts,
- affect statistics,
- affect progression,
- affect achievements,
- affect recommendations or any training logic,
- appear in the app at all **unless Engineering Validation is enabled**.

They live in their own subsystem with their own registry and their own
navigation. They are a diagnostic surface bolted alongside the product, never
woven into it.

---

## 4. The Engineering Validation Category

The **Engineering Validation** category is a hidden developer family that
surfaces validation poses inside the exercise library **only** when the
"Show Engineering Validation category" developer setting is enabled.

- When the setting is **off**, the category and every validation pose are
  completely invisible and inert. The product behaves as if they do not exist.
- When the setting is **on**, the category appears as a parallel section,
  clearly separated from real exercise families, and its poses can be opened in
  a viewer that renders them through the shared animation pipeline.

Visibility is governed by an explicit filter so the isolation cannot leak: the
category is never counted, scheduled, or aggregated with real families.

---

## 5. Purpose of Static Validation Poses

The current validation poses are **static reference postures**, each chosen to
stress a specific part of the MonkEngine runtime at a known-correct configuration — for
example: a full straight-arm dead hang, a deep overhead squat, a seated pike,
and a wide middle split. Collectively they exercise:

- IK reach and clamping at the extremes of range,
- bone-length invariance under demanding configurations,
- pole-vector stability for arms and legs,
- foot and hand completion geometry,
- support and balance in held positions.

A static pose is valuable precisely because it removes time: there is no motion
to blur a defect. If the skeleton is wrong in a single frozen frame, the geometry
is wrong.

---

## 6. Purpose of Future Validation Poses

The subsystem is designed to grow. Additional validation poses may be added to
cover configurations the MonkEngine runtime has trouble with, or to lock in behavior once a
defect is fixed (a regression guard). Any future validation pose must obey the
same contract as the current ones:

- it is a frozen reference of correct anatomy,
- it is fully isolated from the training product,
- it defines the target the MonkEngine runtime must meet.

This document does not enumerate specific future poses; it establishes that any
future pose inherits these rules.

---

## 7. Validation Workflow

When adding or reviewing a validation pose:

1. Enable the **Show Engineering Validation** developer setting.
2. Open the pose in the validation viewer.
3. Confirm the rendered skeleton matches the intended reference anatomy.
4. Confirm the pose reports clean against the MonkEngine's validation rules
   (finite coordinates, constant bone lengths, IK within limits, no ground
   penetration, no sliding supports, balanced support polygon, etc.).
5. Confirm the pose remains fully invisible with the setting **off**.

A validation pose is "done" when it is a **faithful instrument**: it renders the
configuration it claims to probe, and its reading against the MonkEngine's rules is
truthful — whether that reading is clean (engine reproduces it) or a recorded defect
(engine limitation the pose surfaces). A pose is *not* required to read green to be
done; it is required to read *honestly*.

---

## 8. Engine Investigation Workflow

When a validation pose does **not** reproduce cleanly:

1. **Read the failure as an engine measurement.** The pose is the instrument.
2. Identify which rule fails and at which joint (bone stretch, IK clamp/
   unreachable target, pole flip, support slide, etc.).
3. Trace the failure back through the pipeline: pose intent → IK solve → bake →
   FK traversal → finalization → projection.
4. Either **fix the root cause in the MonkEngine runtime** so the whole family of exercises
   benefits (not just this pose), **or record the reading** as a known engine
   limitation the instrument now guards.
5. If you fixed the MonkEngine runtime, re-run the pose to confirm it now reproduces cleanly and
   that no other pose regressed. If you recorded the reading, keep a test that
   asserts the instrument still detects the limitation.

Never resolve a validation failure by editing the target to dodge the problem — that
tampers with the instrument (see §2).

---

## 9. When to Modify a Pose vs. the MonkEngine runtime

Because a validation pose is a **diagnostic instrument**, the bar for touching the
pose is high and narrow.

**Modify a validation pose only when the instrument itself is miscalibrated** — i.e.
the probe is not measuring what it claims to measure. Examples: a typo'd axis so it
reads the wrong joint, a support attached to the wrong point, a coordinate sign that
makes the reading meaningless. In that case the instrument is lying about the MonkEngine runtime
and must be corrected so it reads truthfully again.

**Do not modify a pose to change what it reads about the MonkEngine runtime.** If the reference
configuration the probe requests is faithful to its stated purpose (e.g. "request a
straight limb at this target and show me what the MonkEngine runtime does"), and the MonkEngine runtime
produces a defect, the defect is the reading. Fix the MonkEngine runtime, or record the reading
— never retune the probe to green.

The test to apply:

> "Is the instrument measuring the MonkEngine runtime truthfully?"
> - **No (it's miscalibrated / measuring the wrong thing)** → fix the pose.
> - **Yes, and the reading is a defect** → fix the MonkEngine runtime (or record the reading).

Note that "the configuration is not a pose a human would hold" is **not** grounds to
retune it. A diagnostic probe is judged by whether its reading is faithful, not by
whether the geometry is anatomically photogenic. Never retune a faithful instrument
to make a broken engine read green.

---

## 10. Summary

- Validation poses are **diagnostic instruments**, not exercises and not
  development targets.
- A pose reports the MonkEngine's true state; you fix the MonkEngine runtime or record the reading,
  you never retune the instrument to read green.
- Validation poses are fully isolated from workouts, stats, progression, and
  achievements, and are invisible unless Engineering Validation is enabled.
- Failures are engine measurements — defects to fix or limitations to record — not
  targets to retune away.
- Change a pose only when the instrument is miscalibrated (measuring the wrong
  thing); otherwise fix the MonkEngine runtime or record the reading.

---

## 11. Quality Audit of the Four Engineering Validation Poses

> **Supersession note (diagnostic-instrument rule).** After this audit was first
> written, Middle Split was briefly retargeted to full reach so its straight limbs
> resolved green (`ENGINEERING_VALIDATION_AUDIT §1`, `PELVIC_HIP_COMPLEX_INVESTIGATION
> §P6`, both authored under the old "engine satisfies validation" contract). Under the
> **diagnostic-instrument rule** (§2) that retarget was instrument tampering and has
> been reverted — the pose is restored to the straight-intent probe described in §11.1
> below. Those two documents' "fix the pose" verdicts are **superseded**; the audit of
> the reversal is in `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`.

A visual / biomechanical audit was performed by rendering each frozen pose
through the real engine (`SkeletonPoseFinalizer` + `ConstraintSolver`) and
inspecting world-space joint positions as if reviewing a medical illustration.
Coordinates below use the MonkEngine runtime frame: **+Y up, +X forward, ±Z lateral**
(`-Z` = front/active side `F`/`A`, `+Z` = back/passive side `B`/`P`). Knee/elbow
angles are the interior joint angle (180° = perfectly straight).

### 11.1 Middle Split (`MiddleSplitPose`)

- **Remaining visual issues:** Knees resolve at y≈95 (above the hips at y≈14)
  and elbows at y≈200 (above the shoulders at y≈134). Each straight limb
  (`straight = true`) resolves as an upward "peak" — the knee/elbow is the
  highest point of the limb, not the outboard end. The limbs are still reported
  as nearly-straight by the angle metric (interior ≈ 149°) but are visually
  wrong for a split (knees should be the widest point, roughly level with or
  below the hip, pointing down/out).
- **Root cause:** Authoring. The foot targets are far closer to the hips than a
  straight leg allows (hip→foot distance ≈ 59 units vs. a 210-unit leg). A
  `straight = true` limb whose target sits *inside* the proximal-bone length
  cannot be honoured, so `ConstraintSolver` falls back to triangle IK, which
  picks an upward bend plane. This is exactly the **straight-intent-dropped**
  engine limitation.
- **Engine vs pose:** **Engine limitation, surfaced on purpose.** This pose is a
  deliberate diagnostic instrument: `ValidatorRomClusterTest.middleSplitSurfacesDroppedStraightIntent`
  *asserts* the straight limbs are detected as bent. Retuning the pose to look
  like a true split would tamper with the instrument and hide the limitation it
  exists to read (see §2).
- **Proposed fix:** **None (keep as-is).** Document it as the straight-intent
  reference. If a true-straight middle split is ever wanted, the MonkEngine runtime must
  first honour `straight = true` at in-proximal-radius targets (UNI-9 / straight
  limb reach), not the pose.
- **Complexity:** N/A (no change).

### 11.2 Pike Sit (`PikeSitPose`) — FIXED

- **Remaining visual issues (before fix):**
  1. **Inverted / floor-penetrating foot:** the toe sat at **y ≈ −23** (≈23
     units below the ground plane y=0), stabbing through the floor; the heel
     floated at y ≈ +10. The foot was tipped ~54° downward.
  2. **Floating pelvis:** the pelvis/hip joints sat at **y = 40** while the feet
     were on the floor — the seated "seat" hovered ~40 units above the ground,
     an impossible weight distribution for a seated pose.
- **Root cause:** Authoring (wrong ankle-rotation sign + wrong pelvis height).
  The folded pelvis (`localRotation = axisZ, −fold`, −0.95 rad) propagates its
  forward tilt **down the leg to the ankle** via FK, so the foot inherits the
  trunk's ~54° forward pitch. The original code then set `ankle = −fold`, which
  *doubled* the tilt (pelvis −0.95 + ankle −0.95) and drove the toe through the
  floor. The pelvis height of 40 was simply too high for a seated reference.
- **Engine vs pose:** **Pose (authoring) bug.** the MonkEngine runtime reproduced exactly
  what was authored; the authored reference anatomy was wrong. Per §9 this is a
  pose fix, not an engine fix.
- **Proposed fix (applied):** counter-rotate the ankle by **+fold** so it cancels
  the inherited pelvic fold and lays the foot flat (heel = toe = y = 0), and
  lower the pelvis from **40 → 14** so the seated reference rests near the
  floor. Legs remain straight (`straight = true` IK still resolves 180°), so
  `STRAIGHT_LIMB_INTENT` is preserved and hip ROM is unchanged.
- **Complexity:** Low. Two lines (pelvis height, ankle-rotation sign).
- **Verified:** post-fix dump shows ankle y=0, heel y=0.0, toe y=0.0, pelvis
  y=14, kneeAngle = 180° (straight). `goodReferencesStayCleanUnderEngineeringValidation`
  still passes for PikeSit (the DeepSquat assertion in that same test is a
  *pre-existing* failure unrelated to this change — see 11.3).

### 11.3 Deep Overhead Squat (`DeepOverheadSquatPose`)

- **Remaining visual issues:** None blocking. Feet are flat (heel y≈0, toe
  y≈−0.4, within `ankleHeight`). Knees are bent deep (interior ≈ 149°) with the
  hips **below** the knees (hip y≈35, knee y≈71) — a correct deep-squat
  silhouette. Arms reach overhead nearly straight (interior ≈ 157°). The pelvis
  is shifted forward (x≈−32) and leaned, with the torso counter-rotated so the
  chest stays roughly upright — anatomically plausible.
- **Root cause:** N/A.
- **Engine vs pose:** Clean. Pose is a correct reference; engine reproduces it.
- **Proposed fix:** **None.**
- **Complexity:** N/A.
- **Note:** `goodReferencesStayCleanUnderEngineeringValidation` flags this pose's
  **hip ROM** under `ENGINEERING_VALIDATION`. This is a **pre-existing** failure
  (the femur excursion / combined flexion+abduction sits at the validator's cap)
  and is **not** caused by this audit. It is a validator-tuning / hip-ROM item,
  separate from visual correctness, and out of scope here. Left for follow-up.

### 11.4 Dead Hang (`DeadHangPose`)

- **Remaining visual issues:** None. Legs hang straight down (knee/ankle collinear,
  interior 180°, knee y≈129, ankle y≈41, all x≈−18). Arms are straight overhead
  (interior 180°, hands exactly on the bar at y=500). Feet are relaxed/pointed
  away from the body (toe x=−42.85, heel x=−7.85) — a natural dead-hang hang.
  Overhand grip (`gripAngle = −π/2`) faces the palms away from the bar.
- **Root cause:** N/A.
- **Engine vs pose:** Clean. Pose is a correct reference; engine reproduces it.
- **Proposed fix:** **None.**
- **Complexity:** N/A.

### 11.5 Summary

| Pose | Status | Class | Change |
|------|--------|-------|--------|
| Middle Split | Knee/elbow "peak" | Engine limitation (intentional reference) | None (keep) |
| Pike Sit | Floor-penetrating foot + floating pelvis | Pose authoring bug | **Fixed** (ankle +fold, pelvis 40→14) |
| Deep Overhead Squat | Anatomically correct | Clean | None (hip-ROM validator flag pre-existing) |
| Dead Hang | Anatomically correct | Clean | None |

Only **Pike Sit** required a correction, and only because its authored anatomy
was wrong (§9). No engine code was modified.

### 11.6 Camera Framing Pass (pitch +20°)

A follow-up framing adjustment so overhead / hanging references (notably **Dead
Hang**, whose pull-up bar reaches world y ≈ 500) fit naturally inside the
viewport without changing any geometry.

- **Goal:** improve framing of the test poses only. Camera looks slightly higher
  toward the sky (pitch increased by ≈ 20°). World origin, ground plane,
  character, camera distance (zoom), viewport scaling, and FOV are all unchanged.
- **What changed:** only `defaultPitch` in each validation pose's
  `CameraDefinition`. Yaw and zoom untouched. `Camera.project` consumes pitch in
  radians (default 0.22 ≈ 12.6°), so +20° = +0.349066 rad.

| Pose | Previous pitch | New pitch | Δ |
|------|---------------|-----------|---|
| Middle Split | 0.22 rad (≈12.6°) | 0.569 rad (≈32.6°) | +20° |
| Pike Sit | 0.28 rad (≈16.0°) | 0.629 rad (≈36.0°) | +20° |
| Deep Overhead Squat | 0.22 rad (≈12.6°) | 0.569 rad (≈32.6°) | +20° |
| Dead Hang | 0.22 rad (≈12.6°) | 0.569 rad (≈32.6°) | +20° |

- **Files changed:** `MiddleSplitPose.kt`, `PikeSitPose.kt`,
  `DeepOverheadSquatPose.kt`, `DeadHangPose.kt` (camera `defaultPitch` only).
  No engine/renderer/ground code modified.
- **Screenshots:** not available in this environment (headless build sandbox, no
  render surface). The change is a pure camera-orientation constant; verify
  visually in the Engineering Validation viewer with the developer setting on.
- **Expected result:** raising the pitch shifts the projected skeleton upward in
  frame, giving overhead poses (Dead Hang bar, Deep Squat raised arms) more head
  room while the same world coordinates and camera distance are preserved.

### 11.7 Summary of changes in this PR

1. **Pike Sit** foot/pelvis authoring fix (§11.2).
2. **Audit report** for all four poses (§11.1–§11.5).
3. **Camera pitch +20°** framing pass for all four poses (§11.6).

No ground-plane, world-coordinate, character, FOV, or viewport changes.
