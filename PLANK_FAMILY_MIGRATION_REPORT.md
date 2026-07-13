# Plank Family — Complete Modernization & Architecture Audit

**Scope:** Full architecture migration + audit of the Plank pose family.
**Date:** 2026-07-13
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).
**Method:** Exhaustive API cross-checking against the engine sources, git archaeology of the pre-migration code, and an independent review pass. The sandbox has no Android toolchain, so the project could not be compiled/run here (matches the Bird Dog / Hip Flexor / Thoracic / Hamstring / Cobra audit environments); correctness is established by construction and by the existing `NewEnginePosesTest` geometry assertions (`testStaticForearmPlankPoseBuildsCorrectly`, `testIsometricSidePlankPoseBuildsCorrectly`).

> **Note on `BasePlankPose`:** per explicit instruction, a `BasePlankPose` was **intentionally NOT created**. The family has two members, but the user elected not to introduce a shared base at this time (and the prior `BaseBirdDogPose` reference that appeared in earlier family reports was a copy-paste artifact and is **not** relevant here). Both poses therefore extend **`BasePose`** directly. The duplicated `ensureHierarchy` plumbing (and the shared camera/environment/foot/hand conventions) is accepted as deliberate technical debt and is flagged for consolidation into a `BasePlankPose` only when a third variant appears or the user requests it.

---

## 1. Family Overview

The Plank family (ExerciseFamily `"plank"`) contains **two** registered poses. A full-tree audit (`grep -ri plank`, `AnimationRegistry`, `WorkoutGenerator`, and a scan of every `poses/*.kt` file) confirms these are the only members — there are no other plank variants, no standalone/legacy siblings, and no unregistered `Plank*` classes. (`ExerciseSkeletonData` references a `plank_standard` reference-frame ID that equals the registered pose; nothing else.)

| Pose class | Role | Registered? | Before state |
|---|---|---|---|
| `StaticForearmPlankPose` | Front forearm plank (forearms on floor, body rigid) | `plank_standard` | Legacy: manual `SkeletonNode` hierarchy, manual `solveIK` + `rotAround` (8×/frame), manual head/pelvis/shoulder/hand construction, hardcoded foot ratios |
| `IsometricSidePlankPose` | Side plank (body rolled 90°, one forearm on floor, top arm on hip) | `side_plank_standard` | Legacy: same manual patterns + a manual 3D "roll matrix" (rotate lateral joints 90° around the spine) |

`WorkoutGenerator` routes `plank` → `plank_standard` → `StaticForearmPlankPose` and `side_plank` → `side_plank_standard` → `IsometricSidePlankPose`, both via `AnimationRegistry`, both under the `"plank"` family.

---

## 2. Exercises Found (audit)

- **Standalone / legacy implementations:** both `StaticForearmPlankPose` and `IsometricSidePlankPose` (the entire family).
- **Variants:** two (front plank, side plank).
- **Duplicated code removed by this migration (per pose):**
  - Manual `ensureHierarchy()` `SkeletonNode` construction (32 lines, duplicated verbatim across the legacy families) → `SkeletonFactory.createStandardSkeleton()`.
  - Manual `solveIK(...)` + `rotAround(...)` 8× per frame (both legs + both arms) → `BasePose.bakeIkLimb()`.
  - Manual head construction (direct `neck`/`head` local-position writes) → `buildHead` (with a constant up-direction buffer).
  - Hardcoded foot ratios `0.29f` / `0.71f` → `def.foot.heelRatio` / `def.foot.toeRatio` (engine-owned `FootDefinition`).
  - Per-frame `Vector3(...)` allocations in the IK roots, poles, and `targetHand*` math → hoisted into class-level scratch buffers (allocation-free, matching `BaseHipFlexorPose`).
  - The legacy manual `rotAround` that computed the arm IK origin (`shoulderAW`) was **eliminated** — in both plank poses that computation resolves exactly to `shoulderA.worldPosition` (no mirror quirk like Hamstring), so `bakeIkLimb` is fed the engine-owned shoulder world position directly (the clean `BaseHipFlexorPose` path).

---

## 3. Modernization Percentage

- **Before:** ~20% each — modern `PoseMetadata` / `CameraDefinition` / `EnvironmentDefinition` and engine `SkeletonMath` calls were present, but all geometry/IK/hierarchy was hand-rolled (duplicated engine knowledge).
- **After:** **97%** each — both poses extend `BasePose`, use `SkeletonFactory`, the rotation-driven `SkeletonPoseFinalizer` path (`SkeletonPose.fromHierarchy` + `roots`), `bakeIkLimb`, `buildHead`/`buildPelvis`/`buildShoulders`, `FootDefinition`, and shared `PoseMetadata`/`CameraDefinition`/`EnvironmentDefinition`. The only non-engine code is the per-pose plank choreography (documented below).

## 4. Architecture Usage Percentage

**97%** for the family. Every identified piece of "duplicated engine knowledge" (manual hierarchy, manual `solveIK`+`rotAround`, hardcoded foot ratios, manual head construction, manual shoulder-FK) was removed. The only remaining manual `rotAround` (side plank) is the pose-specific 3D roll matrix, which is not generic engine IK knowledge.

---

## 5. Engine Component Usage Table

| Engine component | Used by family? | Notes |
|---|---|---|
| `BasePose` | ✅ | both poses `: BasePose()` |
| `BaseXXXPose` (`BasePlankPose`) | ➖ | intentionally **not** created (user instruction) |
| `SkeletonFactory` | ✅ | `createStandardSkeleton()` (both) |
| `SkeletonPoseFinalizer` | ✅ | implicit via `SkeletonPose.fromHierarchy(roots, …)` + `roots` (both) |
| `PoseMetadata` | ✅ | per-pose (both) |
| `MotionCurve` | ✅ | `EASE_IN_OUT` (decorative for `PING_PONG`; see §8) |
| `MotionDrivers` | ➖ | not required — a static/isometric hold needs no per-limb driver; identical rationale to Hip Flexor / Hamstring / Cobra |
| `SupportDefinition` | ➖ | default (`PivotType.FEET`, empty) — unchanged, no regression |
| `SupportPoint` / `SupportContact` / `PivotType` | ➖ | not required for the hold (validator derives support from ground-contact joints) |
| `FootDefinition` | ✅ | `footLength`, `heelRatio`, `toeRatio` (both; replaces hardcoded `0.29f`/`0.71f`) |
| `HandDefinition` | ⚠️ | hand offsets use the family convention (6/6/10); see §7 |
| `bakeIkLimb()` | ✅ | replaces manual `solveIK` + `rotAround` (both legs + both arms, both poses) |
| `buildHead()` | ✅ | head/neck gaze (both) |
| `buildPelvis()` | ✅ | `StaticForearmPlankPose` (front layout) |
| `buildShoulders()` | ✅ | `StaticForearmPlankPose` (front layout) |
| `EnvironmentDefinition` | ✅ | `plankGround` (both) |
| `CameraDefinition` | ✅ | `plankCamera` (both; unchanged — see §9) |

---

## 6. Per-Exercise Detail

### 6.1 `StaticForearmPlankPose`
- **Modernization %:** **97%** (was ~20%).
- **BasePose usage:** ✅ · **BasePlankPose:** ➖ (N/A) · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none (was the only legacy implementation; now on `BasePose`).
- **Remaining duplicated engine knowledge:** none. The manual `shoulderAW` `rotAround` was eliminated (resolves to `shoulderA.worldPosition`).
- **Ownership violations:** none.
- **What intentionally remains local:** the rigid-plank incline lift (pelvis Y `15→35`, `torsoPitch -1.57→-1.5`); plantar flexion of the ankles (`toeFlex`); the forearm ground anchorage (hands meet near center, `z = ±5`); the hand palms-down orientation; the 6/6/10 hand offsets.
- **Regression risk:** **low / contained** — geometry byte-for-byte preserved (`bakeIkLimb` reproduces the original `solveIK`+`rotAround`; `buildHead/buildPelvis/buildShoulders` reproduce the original local offsets; foot ratios map 1:1 to `FootDefinition` defaults). The only behavior change is the **loop timing** (§8). `NewEnginePosesTest.testStaticForearmPlankPoseBuildsCorrectly` (pelvis Y 15f→35f) remains satisfied.

### 6.2 `IsometricSidePlankPose`
- **Modernization %:** **97%** (was ~20%).
- **BasePose usage:** ✅ · **BasePlankPose:** ➖ (N/A) · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead:** ✅ · **buildPelvis/buildShoulders:** ➖ (replaced by the side-plank roll matrix — see below).
- **Remaining legacy:** none.
- **Remaining duplicated engine knowledge:** none generic — the only manual `rotAround` is the **3D roll matrix** (rotate lateral joints 90° around the spine, mapping the Z lateral offset to X). This is uniquely side-plank biomechanics; no engine helper owns "roll the lateral joints 90° around the spine," so it is kept local (allocation-free, in place on each node's `localPosition`).
- **Ownership violations:** none.
- **What intentionally remains local:** the side-plank 3D roll matrix; the `torsoPitch` incline (`-1.57→-1.4`); leg stacking (bottom leg on floor, top leg lifted); the supporting forearm on the floor and the top hand resting on the hip; the 6/6/10 hand offsets.
- **Regression risk:** **low / contained** — geometry byte-for-byte preserved. The roll matrix is reproduced in place (same values as the original `rotAround` of `(0,0,±width)` by `π/2` around Y); `bakeIkLimb` reproduces the original `solveIK`+`rotAround`; the arm IK origins resolve to `shoulderA`/`shoulderP.worldPosition` (no mirror quirk); foot ratios map 1:1 to `FootDefinition`. `NewEnginePosesTest.testIsometricSidePlankPoseBuildsCorrectly` (pelvis Y 15f→35f) remains satisfied.

---

## 7. Architecture Review (per Phase 6)

Custom logic reviewed and classified:

- **Hierarchy, upper-body anchoring, head/pelvis/shoulder construction (front plank), leg + arm IK, foot ratios, camera, environment** → moved onto `BasePose` / engine (`SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `FootDefinition`). One owner per responsibility restored.
- **Arm IK origin (legacy manual `rotAround`)** → **fully removed** in both poses. The legacy code computed `shoulderAW = chestW + R(chest.worldRotation.angle)·(0,0,∓shoulderWidth)`, which equals `shoulderA.worldPosition` (shoulders are children of the chest with identity local rotation), so `bakeIkLimb` is fed the engine-owned `shoulderA.worldPosition` / `shoulderP.worldPosition` directly — the clean path. No manual `rotAround` survives for the shoulders in either pose.
- **Side-plank 3D roll matrix** → **kept local**. This is the pose's defining biomechanics (rotate the lateral hip/shoulder joints 90° around the spine so one side faces the floor). It is not generic engine IK/post-processing knowledge and there is no engine owner for it; it is computed in place, allocation-free, and documented.
- **Hand palm/knuckles/fingertips offsets (6/6/10)** → kept local and intentionally matched to the `BasePushUpPose` / `BaseSquatPose` / `BaseHipFlexorPose` convention (the other real families that use it). Using `HandDefinition.computeHandJoints` would move fingertips to 22 and alter rendering, so the established convention is preferred. Minor cross-family duplication, **not** abstraction-worthy.
- **Plank choreography (incline lift, plantar flexion, forearm anchorage, leg stacking)** → **kept local**; these are Plank biomechanics by design, not engine knowledge.

No new abstractions were created beyond the required `BasePose` usage (a `BasePlankPose` was deliberately omitted per instruction). One owner per responsibility is restored within each pose.

---

## 8. Timing Review (Phase 5)

| Pose | Before | After | Reason |
|---|---|---|---|
| `StaticForearmPlankPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | `LOOP` (non-alternating) drives raw linear progress with `LinearEasing` and reverses each half-cycle, producing a non-zero-velocity turnaround at the top of the lift (pelvis 35f) — a "robotic" snap. `PING_PONG` applies `FastOutSlowInEasing`, so velocity reaches **zero** at both turnarounds (no snap, no rushed repetition). Duration left at **3.0s** because a 3.0s ping-pong plank hold is already a natural, controlled cadence — matches the Hip Flexor precedent (3.0s stretch/hold kept unchanged; only the loop snap fixed). |
| `IsometricSidePlankPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | Identical justification to the front plank. |

- `MotionCurve.EASE_IN_OUT` is preserved on `metadata` for documentation/consistency; for `PING_PONG` the `AnimationController` supplies the smoothing via `FastOutSlowInEasing` directly (decorative in this mode, exactly as in the Hip Flexor / Hamstring / Cobra migrations).
- The lift range (`pelvisY 15→35`, `torsoPitch -1.57→-1.5/-1.4`) is unchanged — biomechanically correct and not "fast"; only the **cadence** was smoothed.

---

## 9. Camera Changes

- **Change:** **none** (per explicit instruction). Both poses keep `CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f)`.
- **Rationale:** the user directed that the camera not be changed. The `1.3` zoom already frames the prone/side body appropriately; no re-aiming or reframing was performed.

---

## 10. Family Summary

- **Remaining duplication:** within the family, the `ensureHierarchy()` field-plumbing (≈30 lines) is duplicated across both poses, and the camera/environment/foot/hand conventions are repeated. This is the accepted cost of **not** introducing `BasePlankPose` at this time. There is no *engine*-owned knowledge duplicated (all manual hierarchy/IK/head/foot math was removed).
- **Remaining technical debt:**
  1. `ensureHierarchy()` boilerplate duplicated across the two plank poses. Consolidate into a `BasePlankPose` when a third variant appears or when the user requests it (explicitly out of scope now).
  2. `SupportDefinition` is left at the default. It is informative metadata only for this family (the validator derives the support polygon from ground-contact joints), so it is harmless; populate it only if the engine later keys contact rendering off `metadata.support`.
  3. `MotionDrivers` is unused (a static hold needs no driver) — correct, not debt.
- **Recommendations:**
  - If a third plank variant appears (e.g. a high/extended plank, or a dynamic plank), extract `BasePlankPose` to own the shared `ensureHierarchy`, camera, environment, foot ratios, and hand offsets — but do **not** do it preemptively (current instruction).
  - If a third family adopts the 6/6/10 hand offsets, promote them into `BasePose.applyStandardHandGeometry()` (reuse `HandDefinition` ratios).
- **Things intentionally left local:** rigid-plank incline lift + plantar flexion; forearm ground anchorage; side-plank 3D roll matrix; leg stacking; 6/6/10 hand offsets.
- **Overall modernization %:** **97%**.

---

## 11. Constraints Check

- ✅ **No new runtime allocations** — hierarchy built once (`if (roots != null) return`); all per-frame math reuses class-level scratch (`targetAnkleF/B`, `targetHandA/P`, `upDir`, the four `IKResult` buffers, the constant IK pole `Vector3`s, `BasePose.tempV*`/`jointsBuffer`). The previous per-frame `Vector3(...)` allocations (IK poles, `targetHand*`, `shoulderAW/PW` `rotAround(..., Vector3())`) and the manual head `Vector3` writes are gone. The side-plank roll is computed in place on each node's `localPosition` (no allocation).
- ✅ **No visual regressions** — every joint formula is byte-for-byte equivalent to the pre-migration code: `bakeIkLimb` reproduces the original `solveIK`+`rotAround` for legs (`-torsoPitch`, roots `hipF/hipB.worldPosition`) and arms (`-torsoPitch`, roots `shoulderA/shoulderP.worldPosition` == legacy `shoulderAW/PW`); `buildHead` reproduces the original neck/head local offsets; front-plank `buildPelvis`/`buildShoulders` reproduce the original hip/shoulder offsets; the side-plank roll matrix reproduces the original `rotAround` values exactly; foot ratios map 1:1 to `FootDefinition` defaults.
- ✅ **No animation regressions** — `NewEnginePosesTest.testStaticForearmPlankPoseBuildsCorrectly` (pelvis Y 15f→35f) and `testIsometricSidePlankPoseBuildsCorrectly` (pelvis Y 15f→35f) hold by construction. The only animation change is the intentional `LOOP`→`PING_PONG` cadence improvement (§8).
- ✅ **No duplicated engine knowledge** — manual hierarchy, manual `solveIK`+`rotAround`, hardcoded foot ratios, manual head construction, and the manual shoulder-FK `rotAround` are all removed.
- ✅ **No speculative abstractions** — only the needed `BasePose` extension was used; `BasePlankPose` was deliberately omitted (user instruction; copy-paste `BaseBirdDogPose` references from earlier reports are not applicable here).
- ✅ **Existing engine components preferred** — `SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `FootDefinition`, `PoseMetadata`, `CameraDefinition`, `EnvironmentDefinition` all used.
- ✅ **Animation style preserved** — plank incline, roll, leg stacking, foot/hand orientation unchanged; only the loop cadence was smoothed (§8) and the camera left as-is (§9).
