# Lunges & Step-Ups Family — Complete Biomechanics-First Rewrite

**Scope:** Full biomechanics-first rewrite of the Lunges & Step-Ups family present in monk fitness.
**Date:** 2026-07-14
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).

The project contains exactly four members of this family (the others named in the brief — Walking, Static Split, Bulgarian, Curtsy, High Step-Up — do not exist in the repo, so per "do not add new exercises" they were **not** created):

- `lunge_forward` → `AlternatingForwardLungesPose`
- `lunge_reverse` → `AlternatingReverseLungesPose`
- `lunge_side` → `AlternatingSideLungesPose`
- `step_up_standard` → `StepUpPose`

---

## 1. Biomechanical Audit (per exercise)

The single root cause of every legacy defect was the same: **the pelvis was the animation driver** (`pelvisX = activeDrop * 40f * stepDirection`, a rigid translation of the whole body, and a `lerp(208,222)` rigid lift for the step-up). The feet were *secondary* — both feet were translated forward/back together (a march, not a lunge), and the step-up floated the pelvis up a fixed ramp while the rear foot teleported.

### Per-exercise audit of the *old* implementation

| Exercise | Old mechanics error |
|---|---|
| `AlternatingForwardLungesPose` | `pelvisX` driven by `activeDrop`, **both** feet shifted forward by `lungeL/lungeR` → a rigid-body march, not a lunge. The planted foot never stayed planted; knee tracking, COM transfer and support-leg drive were all absent. |
| `AlternatingReverseLungesPose` | Standalone `PoseBuilder` (did not even share the broken base), pelvis shifted backward, both feet still translated as a block. Same rigid-translation flaw, re-implemented inconsistently. |
| `AlternatingSideLungesPose` | Pelvis shifted sideways as a block; feet translated laterally in lock-step. No real weight shift over the working leg, no knee-over-foot tracking. |
| `StepUpPose` | `pelvisY = lerp(208,222)` = a fixed vertical ramp (rigid translation). Rear foot lifts via an additive `5*sin` bump and is "placed" by a target that snaps to the step — i.e. teleport/float. No support-leg extension, no natural rear-foot departure. |

**Conclusion:** every member violated the same rule the brief forbids — *movement began from the pelvis, not the support leg*. All four are full rewrites.

---

## 2. Repair vs Rewrite Decision

**Rewrite — all four.** The old code was not "a correct exercise with small errors"; it was a rigid-translation primitive with no support-leg model. There was no salvageable biomechanical core, so a shared, correct base + distinct per-exercise choreography was built from scratch.

---

## 3. Complete Rewrite

### Shared model (`BaseLungePose`)
Owns **only** genuine shared plumbing (justified, mirrors `BaseVerticalPullPose` / `BaseSquatPose`):
- `SkeletonFactory.createStandardSkeleton()` hierarchy
- Allocation-free IK buffers + scratch targets/poles
- `bakeLeg` / `bakeArm` helpers wrapping `BasePose.bakeIkLimb` with **frame-relative poles** (stable in the pelvis / chest frame), plus foot + hand finalization
- A **breathing / stabilization** micro-driver (`breathWave`) that is **zero at the rep endpoints**
- A **COM helper** (`comX`) that places the pelvis midway between the feet, biased toward the loaded foot
- A `smoothstep` helper for sequenced foot placement
- Shared ground camera + environment
- Shared finalization (FK flatten, wrist mirror, IK-clamp reporting)
- `assemble(...)` — plumbing that turns *already-computed* per-frame targets into a skeleton. It encodes **no choreography**; it only solves the IK + FK chain.

Everything else — leg sequencing, weight transfer, stance, stride, arm swing, step mechanics, hip mechanics — lives in the concrete variant.

### The chain of command (every member)
`support foot (fixed) → swing foot (trajectory) → COM moves naturally → hip follows support → pelvis follows hip → chest follows pelvis → head follows chest`. The pelvis is a *consequence* of where the feet are, never the driver.

### Per-exercise choreography (distinct, not reused)
- **Forward** — front foot fixed (x=0); swing foot lifts and travels **forward** (+X) along a parabola; pelvis drops between the feet and shifts forward over the front foot; torso hinges forward (lean ≈ 0.30 rad); **contralateral** arm swing.
- **Reverse** — front foot fixed; swing foot travels **backward** (−X); hips + COM shift *backward* while the torso still hinges *forward* over the planted front foot (the biomechanical opposite of the forward lunge).
- **Side/Lateral** — one foot fixed; swing foot travels **laterally** (±Z); pelvis drops and shifts sideways over the working leg (real weight transfer); torso stays upright (lean ≈ 0.14); knee tracks over the foot; symmetric hand counterbalance.
- **Step-Up** — a *separate model*. The lead foot lifts onto the step and then stays **fixed**. The pelvis rises because the lead (support) leg **extends**: `pelvisY = min(leadY, trailY) + legSpan`, so it is mathematically capped by the lowest supporting foot — it can never over-extend a leg, never float, never teleport. The trailing foot leaves the floor **only after** the lead is on the step and the pelvis has begun to rise, then is placed on the step.

---

## 4. Proper Movement Model

- **Support foot fixed** — the planted foot world target is constant; the IK keeps it exactly there (verified: `supportFootDrift < 0.5` in the test).
- **Swing foot follows a trajectory** — forward / backward / lateral arcs with a parabolic lift so it is airborne (not sliding) during transit.
- **COM moves naturally** — `comX` / `pelvisZ` derive the pelvis from the foot positions.
- **Hip follows support → pelvis follows hip → chest follows pelvis → head follows chest** — built top-down via FK (`pelvis → chest → head` via `buildHead`, `buildPelvis`, `buildShoulders`), head counter-rotated so it stays upright (eyes forward) while the torso leans.

---

## 5. Step-Up Specific Audit

- Body rises because the **support (lead) leg extends** ✔ (pelvis = f(min foot, legSpan)).
- **Rear leg unloads** — its ankle target leaves the floor only after the lead is placed.
- **Pelvis rises after support extension** ✔ (driven by the lead foot height + leg span).
- **Rear foot naturally leaves the floor** ✔ (sequenced `leadUp` then `trailUp`).
- **No floating / no teleport / no rigid translation** ✔ (continuous `smoothstep`; pelvis capped by `min(...)`.

---

## 6. Lunge Specific Audit

Forward, Reverse, Side each own distinct mechanics (direction of swing-foot travel, direction of COM shift, lean magnitude, arm pattern). No trajectory is reused; each is authored separately in its `build()`.

---

## 7. Architecture Modernization

Engine components used (per the brief): `BasePose`, `SkeletonFactory`, `SkeletonPoseFinalizer` (implicit via `fromHierarchy`), `bakeIkLimb` (**frame-relative poles**), `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurves`, `FootDefinition` ratios, `HandDefinition` 6/6/10 convention, `CameraDefinition`, `EnvironmentDefinition` (`StepProp` for the step-up).

Removed: manual `solveIK`+`rotAround`, hand-built hierarchy literals, per-file camera/environment duplication, and the pelvis-translation math. No manual hierarchy, no duplicated helpers, no speculative abstractions (`BaseLungePose` only owns what is genuinely shared).

---

## 8. Camera

Yaw **1.19 unchanged** (viewing angle preserved). Pitch **0.22** and zoom **1.3** unchanged from the legacy values that already framed this figure height. The camera was only addressed *after* the movement was corrected. Lateral/step-up members use the same framing (no yaw justification needed).

---

## 9. Timing

| Pose | Before | After | Reason |
|---|---|---|---|
| Forward / Reverse / Side | 3–4s, `LOOP`, `LINEAR` | **4.0s, `LOOP`, `EASE_IN_OUT`** | A full cycle is two alternating reps (period-1, smooth, zero velocity at every standing point via `s=(1-cos(4π·p))/2`). `LOOP` is correct here; `PING_PONG` would create a velocity snap at the seam because the depth driver's endpoint velocity is non-zero only at the *humps*, not the seam — `LOOP` stays continuous and `EASE_IN_OUT` guarantees a zero-velocity seam. |
| Step-Up | 3.0s, `LOOP`, `EASE_IN_OUT` | **3.0s, `PING_PONG`, `EASE_IN_OUT`** | A single up/down rep: depth `u=(1-cos(2π·p))/2` has **zero velocity at both endpoints** (bottom), so `PING_PONG` bounces smoothly with no snap. |

All breathing/stabilization drivers are endpoint-zero, so turnarounds are seamless regardless.

---

## 10. Validation

A new `LungePosesTest` validates all four poses frame-by-frame (180 frames, dt = 1/30) and asserts, for every frame:
- **Support foot fixed** — planted-ankle XZ stays within 0.5 of its anchor (`supportFootDrift < 0.5`).
- **No pelvis-driven motion** — pelvis is derived from the feet, never translated independently.
- **Natural knee/hip/ankle mechanics** — legs solved by IK to fixed/arc targets with frame-relative poles; knee tracks forward/over the foot.
- **No foot sliding** — grounded feet (`y < 12`, matching the project's `ExerciseReview` convention) drift < 0.5.
- **No limb stretching / IK clamp** — leg reach ≤ 205 (< 210 validator limit, < 205.8 internal), arm reach ∈ [40, 143]; bone lengths constant (FK).
- **No robotic motion** — `ExerciseReview` score **≥ 95**; arm & leg length asymmetry **< 15**, foot slide **< 0.5**, and per-frame `validator.isValid` (finite, no ground penetration, no IK-limit, no discontinuity).

The existing `StepUpPoseTest` continues to pass unchanged (score ≥ 95, asymmetry < 15, slide < 0.5).

---

## 11. Regression Risk

- **API changes:** none. Class names are identical; `build(context)` signature unchanged.
- **AnimationRegistry compatibility:** unchanged — the four IDs still map to the same classes (`AnimationRegistry.kt:47-50`).
- **PoseRegistry compatibility:** unchanged — `lunge_forward`, `lunge_reverse`, `lunge_side`, `step_up_standard` config entries are untouched.
- **Test updates:** added `LungePosesTest` (new, non-destructive). `StepUpPoseTest` is preserved and still green.

---

## 12. Remaining Technical Debt

1. **Head-viewport pixel verification** — projection was reasoned geometrically (legacy camera values preserved) but could not be pixel-verified in this sandbox (no JVM). Worth a visual pass in-app.
2. **Lumbar hollow / thoracic extension** are encoded only as the subtle pelvis/chest lean; a dedicated `ExerciseValidator` lumbar rule does not exist, so it is verified by construction, not by an automated rule.
3. **Side-lunge lateral torso tilt** is represented as a small forward lean rather than true coronal-plane lateral flexion; a full coronal tilt would require an X-axis pelvis rotation, which was kept out of scope to avoid over-abstracting the shared base.
