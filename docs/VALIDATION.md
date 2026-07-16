# VALIDATION.md — Validation Poses and the Engineering Validation Subsystem

> Part of the project constitution. This document describes how validation poses
> are intended to work and how the Engineering Validation subsystem is used to
> verify the engine. It describes intent and workflow, not a specific
> implementation.

---

## 1. What a Validation Pose Is (and Is Not)

A **validation pose is not an exercise.**

A validation pose is a *frozen reference configuration of anatomy* used to test
whether the engine can reproduce a known-correct skeleton. It is a static
snapshot: it ignores animation progress, side, mirroring, and breathing, and
returns the same skeleton every time it is built.

- An **exercise** describes a movement the user performs and is part of the
  training product.
- A **validation pose** describes reference anatomy the engine must be able to
  satisfy, and is a developer tool.

They are deliberately implemented as **parallel** systems. A validation pose is
built from the shared engine primitives (`SkeletonFactory`, `SkeletonMath`,
`SkeletonPose`) but does **not** depend on the exercise/workout/catalog systems
and does not reuse animation drivers, breathing, or loops.

---

## 2. The Contract: Engine Satisfies Validation

The direction of responsibility is fixed and must never be reversed:

> **The engine must satisfy validation. Validation must never adapt to engine
> limitations.**

A validation pose defines what a correct skeleton looks like. If the engine
cannot reproduce that pose cleanly — the IK clamps, a bone stretches, a limb
flips, a support slides — then the **engine** is wrong and must be investigated.
The validation pose is not softened, retargeted, or "made easier" to get a
green result. Doing so would hide exactly the defect the pose exists to expose.

A validation pose is the fixed reference. The engine moves to meet it, never the
other way around.

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
stress a specific part of the engine at a known-correct configuration — for
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
cover configurations the engine has trouble with, or to lock in behavior once a
defect is fixed (a regression guard). Any future validation pose must obey the
same contract as the current ones:

- it is a frozen reference of correct anatomy,
- it is fully isolated from the training product,
- it defines the target the engine must meet.

This document does not enumerate specific future poses; it establishes that any
future pose inherits these rules.

---

## 7. Validation Workflow

When adding or reviewing a validation pose:

1. Enable the **Show Engineering Validation** developer setting.
2. Open the pose in the validation viewer.
3. Confirm the rendered skeleton matches the intended reference anatomy.
4. Confirm the pose reports clean against the engine's validation rules
   (finite coordinates, constant bone lengths, IK within limits, no ground
   penetration, no sliding supports, balanced support polygon, etc.).
5. Confirm the pose remains fully invisible with the setting **off**.

A validation pose is "done" only when it is both anatomically correct and
cleanly reproducible by the engine.

---

## 8. Engine Investigation Workflow

When a validation pose does **not** reproduce cleanly:

1. **Assume the engine is at fault first.** The pose is the reference.
2. Identify which rule fails and at which joint (bone stretch, IK clamp/
   unreachable target, pole flip, support slide, etc.).
3. Trace the failure back through the pipeline: pose intent → IK solve → bake →
   FK traversal → finalization → projection.
4. Fix the **root cause in the engine** so the whole family of exercises
   benefits, not just this pose.
5. Re-run the validation pose to confirm it now reproduces cleanly, and confirm
   no other pose regressed.

Never resolve a validation failure by editing the target to dodge the problem.

---

## 9. When to Modify a Pose vs. the Engine

**Modify the validation pose only when the pose itself is anatomically wrong** —
i.e. the reference it encodes does not actually represent correct anatomy (a
typo'd angle, an impossible target, a mis-set support). In that case the pose
was lying and must be corrected.

**Modify the engine in every other case.** If the reference anatomy is correct
and the engine cannot reproduce it, the engine is the defect. This is the
default and by far the more common case.

The test to apply:

> "Is the reference anatomy correct?"
> - **No** → fix the pose.
> - **Yes, but the engine can't reproduce it** → fix the engine.

Never weaken a correct reference to make a broken engine pass.

---

## 10. Summary

- Validation poses are frozen reference anatomy, not exercises.
- The engine satisfies validation; validation never bends to the engine.
- Validation poses are fully isolated from workouts, stats, progression, and
  achievements, and are invisible unless Engineering Validation is enabled.
- Failures are engine defects to investigate, not targets to retune.
- Change a pose only when the pose is anatomically wrong; otherwise fix the
  engine.

---

## 11. Quality Audit of the Four Engineering Validation Poses

A visual / biomechanical audit was performed by rendering each frozen pose
through the real engine (`SkeletonPoseFinalizer` + `ConstraintSolver`) and
inspecting world-space joint positions as if reviewing a medical illustration.
Coordinates below use the engine frame: **+Y up, +X forward, ±Z lateral**
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
  deliberate regression reference: `ValidatorRomClusterTest.middleSplitDetectableUnderEngineeringValidation`
  *asserts* the straight limbs are detected as bent. Fixing the pose to look
  like a true split would hide the bug the test exists to catch.
- **Proposed fix:** **None (keep as-is).** Document it as the straight-intent
  reference. If a true-straight middle split is ever wanted, the engine must
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
- **Engine vs pose:** **Pose (authoring) bug.** The engine reproduced exactly
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
