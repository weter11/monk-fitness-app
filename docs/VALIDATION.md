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
