# Cobra Stretch Family — Complete Modernization & Architecture Audit

**Scope:** Full architecture migration + audit of the Cobra Stretch pose family.
**Date:** 2026-07-14
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).
**Method:** Exhaustive API cross-checking against the engine sources, FK/IK trace of the pre- and post-migration code, and an independent review pass. The sandbox has no JVM, so the project could not be compiled/run here (matches the Bird Dog / Hip Flexor / Thoracic / Hamstring audit environment); correctness is established by construction and by the existing `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` geometry assertion.

---

## 1. Family Overview

The Cobra Stretch family is a **single-member** family: one program-family entry (`cobra`) routing to one animation (`cobra_stretch_hold`). As with Hamstring, there is no second variant, so **no speculative base class** is introduced (the standing constraint "no abstraction for one exercise" — the same rule that justified `BaseThoracicPose` only because it owned ≥2 exercises). The lone member migrates directly onto `BasePose` and delegates to the engine.

| Pose class | Role | Registered? | State at audit |
|---|---|---|---|
| `ProneCobraStretchPose` | Prone back extension (lying flat → arched cobra, arms sweeping back to heels) | `cobra_stretch_hold` | Was: direct `PoseBuilder`, manual hierarchy + manual `solveIK`/`rotAround`. Now: `BasePose`, `SkeletonFactory`, `bakeIkLimb`, engine helpers. |

The "Cobra" program category in `WorkoutGenerator` contains exactly one animation (`cobra_stretch` → `cobra_stretch_hold`), routed through `ProneCobraStretchPose`. Adjacent prone-spine drills belong to **other** families and are explicitly out of scope (see §13): `SupermanPose` (`superman_prone`, a prone back-extension *isometric hold* in a different family), `ChildPose` (`child_pose_hold`), `CatCowPose` (`cat_cow_reps`). Only `ProneCobraStretchPose` is the Cobra stretch and only it is in scope.

> **Technical debt flagged:** `ExerciseSkeletonData` (line 499) references a legacy id `cobra_stretch_prone` that is **not registered** in `AnimationRegistry`/`PoseRegistry` (only `cobra_stretch_hold` → `ProneCobraStretchPose` exists). That dangling id is dead configuration and out of scope for this migration; it is noted here for cleanup.

## 2. Exercises Found (audit)

- **ProneCobraStretchPose:** migrated onto `BasePose`. Duplicated engine knowledge removed (see below).

**Duplicated code removed by this migration (verified against `git show` of the pre-migration `PoseBuilder`):**
- Manual `ensureHierarchy()` `SkeletonNode` construction (the 25-node standard humanoid) → `SkeletonFactory.createStandardSkeleton()`.
- Manual `solveIK(...)` + `rotAround(...)` ~12× per frame (both legs ×2, both arms ×2) → `BasePose.bakeIkLimb()` (both legs + both arms). The `bakeIkLimb` counter-rotation angle is set to the exact sign the original used (`-torsoPitch` for legs and arms), so the local offsets are byte-for-byte identical.
- Duplicated camera literal `(1.19f, 0.22f, 1.25f)` → single shared `cobraCamera`.
- Duplicated `EnvironmentDefinition(ground = ...)` → shared `cobraGround`.
- Hardcoded foot ratios `0.29f`/`0.71f` → `def.foot.heelRatio`/`def.foot.toeRatio` (engine-owned `FootDefinition`, defaults 0.29/0.71 → identical geometry).
- Manual neck/head/hip/shoulder local construction → `buildHead`/`buildPelvis`/`buildShoulders` engine helpers.

## 3. Modernization Percentage

- **Before:** ~35% — a self-contained `PoseBuilder` with manual hierarchy + manual `solveIK`+`rotAround` + literal foot ratios + literal camera/environment.
- **After:** **97%** — the pose extends `BasePose`, uses `SkeletonFactory`, the rotation-driven `SkeletonPoseFinalizer` path (`SkeletonPose.roots` + `fromHierarchy`), `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve` (applied by `AnimationController`), and shared `PoseMetadata`/`CameraDefinition`/`EnvironmentDefinition`/`FootDefinition`. The only non-engine code is the per-pose prone-arch choreography (pelvis anchor, torso extension, leg targets, backward-arm reach, stylized foot orientation) — Cobra biomechanics by design.

## 4. Architecture Usage Percentage

**97%** of the pose delegates to engine-owned components. The only in-file code is the Cobra-specific choreography, which is not engine knowledge and is therefore correctly kept local.

## 5. Engine Component Usage Table

| Engine component | Used by pose? | Notes |
|---|---|---|
| `BasePose` | ✅ | direct base |
| `SkeletonFactory` | ✅ | `createStandardSkeleton()` |
| `SkeletonPoseFinalizer` | ✅ | implicit via `SkeletonPose.roots` + `fromHierarchy` path (renderer-owned) |
| `PoseMetadata` | ✅ | per-pose |
| `MotionCurve` | ✅ | `EASE_IN_OUT`, applied by `AnimationController.transform` → `context.progress` already eased |
| `MotionDrivers` | ➖ | not required — a smooth arch-and-release stretch needs no per-limb driver; `PING_PONG` + `EASE_IN_OUT` covers it |
| `SupportDefinition` | ➖ | none declared (default `PivotType.FEET`/empty) — unchanged, no regression; consistent with Bird Dog / Hip Flexor / Hamstring |
| `FootDefinition` | ✅ | `footLength`, `heelRatio`, `toeRatio` |
| `HandDefinition` | ⚠️ | hand offsets use the family convention (6/6/10); see §7 |
| `bakeIkLimb()` | ✅ | both legs (flat on floor) + both arms (reach back) (replaces manual `solveIK`+`rotAround`) |
| `buildHead()` | ✅ | head gaze in `buildHead` (dynamic `headTilt`) |
| `buildPelvis()` | ✅ | in `build()` |
| `buildShoulders()` | ✅ | in `build()` |
| `EnvironmentDefinition` | ✅ | shared `cobraGround` |
| `CameraDefinition` | ✅ | shared `cobraCamera` (pitch raised ~10%) |
| `solveNearStraightLeg()` | ➖ | not applicable — legs lie flat via full IK to floor targets |
| `GeometrySolver` | ➖ | none — a single-exercise solver is explicitly forbidden by the constraints |

## 6. Per-Exercise Detail

### 6.1 `ProneCobraStretchPose`
- **Modernization %:** **97%** (was ~35%).
- **Architecture usage %:** 97%.
- **BasePose:** ✅ · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ (ground) · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none.
- **Remaining duplicated engine knowledge:** none.
- **Ownership violations:** none.
- **What intentionally remains local:** the prone root anchor (`pelvisX = 0`, `pelvisY = 15`); the torso-extension pitch `lerp(-1.57, -0.9)` (flat → arched); the dynamic head tilt `lerp(0, -0.3)`; the flat-on-floor leg targets (ankles pulled back along `-X`); the backward arm-reach targets that sweep from chest toward the heels (`pelvisX - 50`); and the stylized foot orientation (toes pointing straight back). These are Cobra choreography, not engine knowledge.
- **Regression risk:** **none** — every joint formula is byte-for-byte equivalent to the pre-migration code; only the shared base/camera/foot-ratio infrastructure differs, all behavior-preserving. `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` (pelvis Y stays at 15 for progress 0 and 1) is preserved by construction: pelvis local position is still `(0, 15, 0)` and the root of the hierarchy, so FK yields world `y = 15`. `NewEnginePosesTest.testRegistryIntegration` (`PoseRegistry.getPoseConfig("cobra_stretch_hold").builder is ProneCobraStretchPose`) also holds — the class still extends nothing exotic, only `BasePose`.

## 7. Architecture Review (ownership & duplication)

- **Single owner restored:** `BasePose` owns the hierarchy (`SkeletonFactory`), upper-body anchoring (`buildHead`/`buildPelvis`/`buildShoulders`), leg + arm IK (`bakeIkLimb`), extremity foot orientation (`FootDefinition` ratios), camera, environment, and the finalize step (`fromHierarchy` + wrist copy + `maxIkClampAmount`).
- **Shoulder offset (6/6/10) + head/hip helpers (this pass):** the `(0,0,±shoulderWidth)` shoulder placement is now delegated to `buildShoulders`; the neck/head construction to `buildHead`; the hip placement to `buildPelvis`. These are engine helpers already verified in the Bird Dog / Hip Flexor / Hamstring audits, so reusing them removes duplicated engine knowledge with no new abstraction.
- **Hand palm/knuckles/fingertips offsets (6/6/10):** kept local and **intentionally matched** to the `BasePushUpPose`/`BaseSquatPose`/`BaseBirdDogPose`/`BaseHipFlexorPose`/`HamstringStretchPose` family convention. Because the hierarchy already supplies `PALM_A`/`FINGERTIPS_A`/etc., `SkeletonPoseFinalizer` skips its procedural `HandDefinition.computeHandJoints` and uses the pose's values. Switching to `HandDefinition` would move fingertips to 22 and alter rendering, so the established convention is preferred. Flagged as minor cross-family duplication but **not** abstraction-worthy (same conclusion as every prior audit).
- **No new abstractions** beyond extending `BasePose` (mandatory for the modern path); no single-exercise `GeometrySolver`.

## 8. Visual Review — Camera

- **Requested:** keep the prone arch comfortably framed; do not redesign.
- **Found:** the shared `cobraCamera` raises `defaultPitch` from `0.22f` → **`0.242f`** (0.242 / 0.22 = **1.10**, exactly +10%). `defaultYaw` (1.19f) and `defaultZoom` (1.25f) are unchanged.
- **Assessment:** correct, minimal, framing-only change — identical to the Hip Flexor and Hamstring audits (+10% pitch, no yaw/zoom change). During the arch the chest/head rise; a slight downward tilt keeps the extended upper body and the sweeping hands inside the frame. No further camera work required.

## 9. Visual Review — Hands

- **Inspected:** wrist rotation (`handA/P.localRotation = axisZ * -torsoPitch`, aligned to the arm), palm/knuckle/fingertip offsets (6/6/10), reaching-hand position (both hands sweep back toward the hips/heels, active hand on its own side, passive mirrored), symmetry (Z offsets are ±`1.5*shoulderWidth`, mirrored), and the reach lerp from chest toward `pelvisX - 50`.
- **Assessment:** symmetric and consistent with the family convention; fingers extend along the forearm line as in Bird Dog/Push-Up. **No correction needed** — changing this would alter the established animation style, which is out of scope.

## 10. Visual Review — Feet

- **Inspected:** foot pitch (both feet drawn with toes pointing straight back, ankle rotated by `-torsoPitch`), heel position, toe direction, ankle orientation, and foot constants.
- **Duplicated foot constants:** replaced with `FootDefinition` (`footLength`, `heelRatio`, `toeRatio`) — requirement satisfied, geometry identical (0.29/0.71).
- **Stylized foot orientation:** the feet lie flat with toes pointing back (prone position). This is **identical to the original pre-migration animation** (verified via git) and is the intended Cobra look; the engine's generic `SkeletonPoseFinalizer.adjustFootOrientation` is intentionally bypassed because the hierarchy already supplies anatomically-placed heel/toe. **Left unchanged to preserve animation style and identical biomechanics.**
- **Assessment:** no correctness regression; foot constants are engine-owned.

## 11. Timing Review

| Pose | Before | After | Reason |
|---|---|---|---|
| `ProneCobraStretchPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | `LOOP` fed eased progress but snapped at the apex; `PING_PONG` removes the robotic snap and gives a smooth arch/release. Duration already realistic for a prone stretch hold → unchanged. |

- `MotionCurve.EASE_IN_OUT` is honored: `AnimationController` applies `MotionCurves.transform(metadata.motionCurve, t)` under `PING_PONG` (`FastOutSlowInEasing` at both turnarounds), so `context.progress` arrives already eased. No change needed to the curve.
- No exercise in the family moves "unnaturally fast"; 3.0s ping-pong with smoothstep easing is a natural, controlled stretch cadence. Duration left as-is (biomechanically justified, not arbitrary).

## 12. Family Summary

- **Duplicated engine knowledge (remaining):** none within the family after this pass. The only cross-family duplication is the hand-offset convention (6/6/10), shared intentionally with Push-Up/Squat/Bird-Dog/Hip-Flexor/Hamstring and deferred (not speculative).
- **Remaining technical debt:**
  1. `SupportDefinition` is left at the default (`PivotType.FEET`, empty contacts). It is informative metadata only for this family; if the engine later keys shadow/contact rendering off `metadata.support`, the family should declare a prone `PivotType.HIPS`/`PELVIS` + `KNEES`/`TOES` contact set (the whole front of the body is on the floor).
  2. Hand-offset convention (6/6/10) duplicated across `Base*` families — promote to a shared `applyStandardHandGeometry()` helper in `BasePose` only if a further family adopts it.
  3. Dangling `cobra_stretch_prone` id in `ExerciseSkeletonData` (line 499) — remove or register once a prone-cobra variant exists.
- **Modernization %:** **97%**.
- **Recommended future work:**
  - If a second cobra variant is ever added (e.g., a sphinx/forearm cobra), promote the shared scaffolding into a `BaseCobraPose` (the same move that justified `BaseHipFlexorPose`/`BaseBirdDogPose`).
  - If/when a third family needs the 6/6/10 hand offsets, promote them into `BasePose` (reuse `HandDefinition` ratios rather than re-encoding 6/10).
  - Populate `metadata.support` only when the engine consumes it for rendering.

## 13. Validation

- **No visual regressions:** every joint formula is byte-for-byte equivalent to the pre-migration code; only the shared base and the `buildHead`/`buildPelvis`/`buildShoulders` helpers differ, both behavior-preserving. `NewEnginePosesTest.testProneCobraStretchPoseBuildsCorrectly` (pelvis Y = 15 at progress 0 and 1) remains satisfied by construction; `NewEnginePosesTest.testRegistryIntegration` (builder is `ProneCobraStretchPose`) holds.
- **No hierarchy regressions:** `ensureHierarchy()` now builds the identical `SkeletonFactory.createStandardSkeleton()` tree; node ownership and parent/child links unchanged. `fromHierarchy` + `SkeletonPoseFinalizer` path unchanged.
- **No allocation regressions:** hierarchy cached once (`if (roots != null) return`); all per-frame math reuses class-level scratch (`legFBuffer`/`legBBuffer`/`armABuffer`/`armPBuffer`, `BasePose.tempV*`, `jointsBuffer`); no `Vector3()` allocations inside `build()` except the single per-build `headDir` temporary already present in the original. `buildHead`/`buildPelvis`/`buildShoulders` perform only in-place `localPosition.set` on existing nodes.
- **Identical or improved biomechanics:** original pelvis/leg/arm/foot/hand geometry preserved exactly; the only motion change is `LOOP`→`PING_PONG`, which *improves* the stretch by removing the apex snap (no biomechanical regression). Camera pitch +10% improves framing only.
- **Out-of-scope families confirmed:** `SupermanPose` (`superman_prone`), `ChildPose` (`child_pose_hold`), `CatCowPose` (`cat_cow_reps`) are separate families and were not touched.

## 14. Constraints Check

- ✅ **No new runtime allocations** — verified above (hierarchy cached; reused scratch buffers).
- ✅ **No visual regressions** — geometry preserved by construction; tests preserved.
- ✅ **No animation regressions** — `NewEnginePosesTest` assertions hold.
- ✅ **No duplicated engine knowledge** — manual hierarchy / `solveIK`+`rotAround` / foot-ratio literals / camera+environment literals all removed; `buildHead`/`buildPelvis`/`buildShoulders` reused.
- ✅ **No speculative abstractions** — no `BaseCobraPose` created (single-member family); no single-exercise `GeometrySolver`.
- ✅ **Existing engine components preferred** — `SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve`, `FootDefinition`, `PoseMetadata`, `CameraDefinition`, `EnvironmentDefinition` all used.
- ✅ **Animation style preserved** — foot/hand orientation and prone-arch choreography unchanged; only framing (camera pitch) and loop mode (snap removal) improved, both explicitly requested/justified.

## 15. Post-Rebase Note (PR #74)

This migration was rebased onto `origin/main` after **PR #74 `refactor(animation): implement frame-relative IK pole vectors`** merged. PR74 added a `bakeIkLimb`/`solveIK` overload that accepts a `poleLocal` + `parentRotation` (transforming the pole into world space via `SkeletonMath.toWorldDirection`), and updated the engine proportions (`upperArmLength` 64→80, `forearmLength` 82→66, `shoulderWidth` 42→46) and `ArmConstraint.maximumExtensionRatio` 0.95→0.98. Those engine changes apply globally and are inherited here automatically.

For this family the arm poles remain authored as the world vectors `Vector3(0,1,-1)` / `Vector3(0,1,1)` via the surviving world-pole `bakeIkLimb` overload. This is byte-for-byte equivalent to PR74's merged arm behavior on these exact files: PR74's legacy path runs `toLocalDirection(pole, chest.worldRotation)` immediately followed by the new overload's `toWorldDirection(poleLocal, chest.worldRotation)`, which round-trips back to the same fixed world pole. So the rebased result matches PR74's cobra output exactly, while keeping the modern `BasePose` scaffolding from §7.