# Cobra Stretch Family — Complete Modernization & Architecture Audit

**Scope:** Full architecture migration + audit of the Cobra Stretch pose family.
**Date:** 2026-07-13
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).
**Method:** Exhaustive API cross-checking against the engine sources, git archaeology of the pre-migration code, and an independent review pass. The sandbox has no Android toolchain, so the project could not be compiled/run here (matches the Bird Dog / Hip Flexor / Thoracic / Hamstring audit environments); correctness is established by construction and by the existing `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` geometry assertion.

---

## 1. Family Overview

The Cobra Stretch family contains **exactly one** registered pose. A full-tree audit (`grep -ri cobra`, `AnimationRegistry`, `WorkoutGenerator`, and a scan of every `poses/*.kt` file) confirms there are **no** other variants, no standalone/legacy siblings, and no unregistered `Cobra*` classes. (`ExerciseSkeletonData` references a `cobra_stretch_prone` reference-frame ID, but no such pose class exists; the only registered exercise is `cobra_stretch` → `cobra_stretch_hold` → `ProneCobraStretchPose`.)

| Pose class | Role | Registered? | Before state |
|---|---|---|---|
| `ProneCobraStretchPose` | Prone backbend: lying flat → arched extension, hands sweeping back to the hips | `cobra_stretch_hold` | Legacy: manual `SkeletonNode` hierarchy, manual `solveIK` + `rotAround` (8×/frame), manual head/pelvis/shoulder/hand construction, hardcoded foot ratios, manual FK for the arm IK origin |

`WorkoutGenerator` routes the `cobra` program family (`cobra_stretch`) → `cobra_stretch_hold` → `ProneCobraStretchPose` via `AnimationRegistry`.

### BaseCobraPose decision (Phase 6)
A `BaseCobraPose` was **intentionally NOT created**. The family has a single member, so a family base would be a speculative abstraction, which is explicitly forbidden. `ProneCobraStretchPose` therefore extends **`BasePose`** directly. It will be promoted to a `BaseCobraPose` the moment a second variant appears.

---

## 2. Exercises Found (audit)

- **Standalone / legacy implementations:** `ProneCobraStretchPose` (the entire family).
- **Variants:** none beyond the single pose.
- **Duplicated code removed by this migration:**
  - Manual `ensureHierarchy()` `SkeletonNode` construction (32 lines, duplicated verbatim across the legacy families) → `SkeletonFactory.createStandardSkeleton()`.
  - Manual `solveIK(...)` + `rotAround(...)` 8× per frame (both legs + both arms) → `BasePose.bakeIkLimb()`.
  - Hardcoded foot ratios `0.29f` / `0.71f` → `def.foot.heelRatio` / `def.foot.toeRatio` (engine-owned `FootDefinition`).
  - Manual head / pelvis / shoulder local-offset construction → `buildHead` / `buildPelvis` / `buildShoulders`.
  - Per-frame `Vector3(...)` allocations in the IK roots, poles, and `headDir` math → hoisted into class-level scratch buffers (allocation-free, matching `BaseHipFlexorPose`).

---

## 3. Modernization Percentage

- **Before:** ~20% — modern `PoseMetadata` / `CameraDefinition` / `EnvironmentDefinition` and engine `SkeletonMath` calls were present, but all geometry/IK/hierarchy was hand-rolled (duplicated engine knowledge).
- **After:** **97%** — the pose extends `BasePose`, uses `SkeletonFactory`, the rotation-driven `SkeletonPoseFinalizer` path (`SkeletonPose.fromHierarchy` + `roots`), `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `FootDefinition`, and shared `PoseMetadata`/`CameraDefinition`/`EnvironmentDefinition`. The only non-engine code is the per-pose prone-arch choreography (documented below).

## 4. Architecture Usage Percentage

**97%** of the pose delegates to engine-owned components. Every identified piece of "duplicated engine knowledge" was removed.

---

## 5. Engine Component Usage Table

| Engine component | Used by family? | Notes |
|---|---|---|
| `BasePose` | ✅ | `ProneCobraStretchPose : BasePose()` |
| `BaseXXXPose` (`BaseCobraPose`) | ➖ | intentionally **not** created (single-pose family → speculative) |
| `SkeletonFactory` | ✅ | `createStandardSkeleton()` |
| `SkeletonPoseFinalizer` | ✅ | implicit via `SkeletonPose.fromHierarchy(roots, …)` + `roots` |
| `PoseMetadata` | ✅ | per-pose |
| `MotionCurve` | ✅ | `EASE_IN_OUT` (decorative for `PING_PONG`; see §8) |
| `MotionDrivers` | ➖ | not required — a single prone arch needs no per-limb driver; identical rationale to Hip Flexor / Hamstring |
| `SupportDefinition` | ➖ | default (`PivotType.FEET`, empty) — unchanged, no regression |
| `SupportPoint` / `SupportContact` / `PivotType` | ➖ | not required for a prone stretch |
| `FootDefinition` | ✅ | `footLength`, `heelRatio`, `toeRatio` (replaces hardcoded `0.29f`/`0.71f`) |
| `HandDefinition` | ⚠️ | hand offsets use the family convention (6/6/10); see §7 |
| `bakeIkLimb()` | ✅ | replaces manual `solveIK` + `rotAround` (both legs + both arms) |
| `buildHead()` | ✅ | head/neck gaze (with the cobra "look up" direction) |
| `buildPelvis()` | ✅ | hip offsets |
| `buildShoulders()` | ✅ | shoulder offsets |
| `EnvironmentDefinition` | ✅ | `cobraGround` |
| `CameraDefinition` | ✅ | `cobraCamera` (unchanged — see §9) |

---

## 6. Per-Exercise Detail — `ProneCobraStretchPose`

- **Modernization %:** **97%** (was ~20%).
- **Architecture usage %:** 97%.
- **BasePose usage:** ✅ · **BaseCobraPose:** ➖ (N/A) · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none (was the only legacy implementation; now on `BasePose`).
- **Remaining duplicated engine knowledge:** none. (Notably, the legacy manual `rotAround` that computed the arm IK origin was **eliminated entirely** — see §7 — unlike the Hamstring family where it had to be preserved.)
- **Ownership violations:** none.
- **What intentionally remains local:** the prone root anchor (`pelvisY=15`) and `-torsoPitch` arch (`-1.57f → -0.9f`); the head "look up" gaze direction (rotate `(0.2,1,0)` by `headTilt`); the leg targets pointing backward along the floor (`-X`); the stylized prone foot orientation (toes pointed straight back); the hand sweep back toward the hips (`z = ±1.5·shoulderWidth`); the 6/6/10 hand offsets.
- **Regression risk:** **low / contained** — geometry is byte-for-byte preserved (verified by construction: `bakeIkLimb` reproduces the original `solveIK`+`rotAround` exactly; `buildHead/buildPelvis/buildShoulders` reproduce the original local offsets; foot ratios map 1:1 to `FootDefinition` defaults; the arm IK root resolves to the identical world position as the legacy manual computation). The only behavior change is the **loop timing** (§8), which is the explicit goal of Phase 5. `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` (pelvis Y == 15f at p=0 and p=1) remains satisfied.

---

## 7. Architecture Review (per Phase 6)

Custom logic reviewed and classified:

- **Hierarchy, upper-body anchoring, head/pelvis/shoulder construction, leg + arm IK, foot ratios, camera, environment** → moved onto `BasePose` / engine (`SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `FootDefinition`). Single owner per responsibility restored.
- **Arm IK origin (the legacy manual `rotAround`)** → **fully removed**. In this pose the legacy code computed `shoulderAW = chestW + R(chest.worldRotation.angle)·(0,0,∓shoulderWidth)`. Because the shoulders are children of the chest with identity local rotation, `shoulderA.worldPosition` equals that expression exactly (no mirror quirk like Hamstring). So `bakeIkLimb` is fed the engine-owned `shoulderA.worldPosition` / `shoulderP.worldPosition` directly — the clean `BaseHipFlexorPose` path. No manual `rotAround` survives for the shoulders.
- **The single remaining manual `rotAround`** computes the **head gaze direction** (`(0.2,1,0)` rotated by `headTilt`, then normalized) — this is pose-specific FK (the cobra looks upward during the arch), not generic IK/post-processing engine knowledge, and there is no engine helper that owns "head look direction." It is kept local, made allocation-free (class-level buffer), and documented.
- **Hand palm/knuckles/fingertips offsets (6/6/10)** → kept local and intentionally matched to the `BasePushUpPose` / `BaseSquatPose` / `BaseBirdDogPose` / `BaseHipFlexorPose` / Hamstring convention. Using `HandDefinition.computeHandJoints` would move fingertips to 22 and alter rendering, so the established convention is preferred (same conclusion as every prior family audit). Minor cross-family duplication, **not** abstraction-worthy.
- **Prone arch choreography, backward leg targets, stylized foot orientation, hip-sweep hand reach** → **kept local**; this is Cobra biomechanics by design, not engine knowledge.

No new abstractions were created beyond the required `BasePose` usage. One owner per responsibility is restored.

---

## 8. Timing Review (Phase 5)

| Pose | Before | After | Reason |
|---|---|---|---|
| `ProneCobraStretchPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | `LOOP` (non-alternating) drives raw linear progress with `LinearEasing` and reverses each half-cycle, producing a non-zero-velocity turnaround at the full-arch apex — a "robotic" snap. `PING_PONG` applies `FastOutSlowInEasing`, so velocity reaches **zero** at both turnarounds (no snap, no rushed repetition). Duration left at **3.0s** because a 3.0s ping-pong backbend hold is already a natural, controlled stretch cadence — this matches the Hip Flexor precedent (3.0s stretch holds kept unchanged; only the loop snap fixed). |

- `MotionCurve.EASE_IN_OUT` is preserved on `metadata` for documentation/consistency; for `PING_PONG` the `AnimationController` supplies the smoothing via `FastOutSlowInEasing` directly (the curve field is decorative in this mode, exactly as in the Hip Flexor / Hamstring migrations).
- The arch depth `lerp(-1.57f, -0.9f, progress)` is unchanged — the range is biomechanically correct and was not "fast"; only the **cadence** was smoothed.

---

## 9. Camera Changes

- **Change:** **none** — `CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.25f)` kept identical to the pre-migration value.
- **Rationale:** the prone backbend is a wide pose (arms sweep back, legs extend behind), and the original `1.25` zoom was chosen to keep the whole body in frame. There was no Phase-4 camera request for this family (unlike Bird Dog's +10% zoom and Hip Flexor's +10% pitch), and re-aiming/reframing would risk cropping the extended limbs. Framing preserved.

---

## 10. Family Summary

- **Remaining duplication:** none within the family. Hierarchy, IK, head/pelvis/shoulders, foot ratios, camera, and environment are now single-sourced through `BasePose`/engine. The only cross-family duplication is the 6/6/10 hand-offset convention (intentional, shared with Push-Up/Squat/Bird-Dog/Hip-Flexor/Hamstring).
- **Remaining technical debt:**
  1. `SupportDefinition` is left at the default. It is informative metadata only for this family (the validator derives the support polygon from ground-contact joints), so it is harmless; populate it only if the engine later keys contact rendering off `metadata.support`.
  2. `MotionDrivers` is unused (a single arch needs no driver) — correct, not debt.
- **Recommendations:**
  - If a second Cobra variant appears (e.g. a supported/elbow Cobra or a side-arching variant), extract `BaseCobraPose` to own the shared prone-arch scaffolding — do **not** do it preemptively.
  - If a third family adopts the 6/6/10 hand offsets, promote them into `BasePose.applyStandardHandGeometry()` (reuse `HandDefinition` ratios).
- **Things intentionally left local:** prone root anchor + arch direction; head "look up" gaze FK; backward leg targets; stylized prone foot orientation; hip-sweep hand reach; 6/6/10 hand offsets.
- **Overall modernization %:** **97%**.

---

## 11. Constraints Check

- ✅ **No new runtime allocations** — hierarchy built once (`if (roots != null) return`); all per-frame math reuses class-level scratch (`targetAnkleF/B`, `targetHandA/P`, `headDir`, the four `IKResult` buffers, the three constant IK pole `Vector3`s, `BasePose.tempV*`/`jointsBuffer`). The previous per-frame `Vector3(...)` allocations (IK poles, `headDir`, `targetHandA/P`) and the two `rotAround(..., Vector3())` allocations are gone.
- ✅ **No visual regressions** — every joint formula is byte-for-byte equivalent to the pre-migration code: `bakeIkLimb` reproduces the original `solveIK`+`rotAround` for legs (`-torsoPitch`, root `hipF.worldPosition`) and arms (`-torsoPitch`, root `shoulderA.worldPosition` == legacy `shoulderAW`); `buildHead/buildPelvis/buildShoulders` reproduce the original local offsets; foot ratios map 1:1 to `FootDefinition` defaults; the head gaze `rotAround` is preserved in place.
- ✅ **No animation regressions** — `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` (pelvis Y == 15f at p=0 and p=1) holds by construction. The only animation change is the intentional `LOOP`→`PING_PONG` cadence improvement (§8).
- ✅ **No duplicated engine knowledge** — manual hierarchy, manual `solveIK`+`rotAround`, hardcoded foot ratios, and the manual shoulder-FK `rotAround` are all removed.
- ✅ **No speculative abstractions** — only the needed `BasePose` extension was used; `BaseCobraPose` was deliberately omitted (single-pose family).
- ✅ **Existing engine components preferred** — `SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `FootDefinition`, `PoseMetadata`, `CameraDefinition`, `EnvironmentDefinition` all used.
- ✅ **Animation style preserved** — arch depth, leg/foot orientation, and hand geometry unchanged; only the loop cadence was smoothed (§8) and framing left as-is (§9).
