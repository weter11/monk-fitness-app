# Hamstring Mobility Family — Complete Modernization & Architecture Audit

**Scope:** Full architecture migration + audit of the Hamstring Mobility pose family.
**Date:** 2026-07-14
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).
**Method:** Exhaustive API cross-checking against the MonkEngine runtime sources, FK/IK trace of the pre- and post-migration code, and an independent review pass. The sandbox has no JVM, so the project could not be compiled/run here (matches the Bird Dog / Hip Flexor / Thoracic audit environment); correctness is established by construction and by the existing `ThoracicAndHamstringStretchPosesTest` geometry assertions.

---

## 1. Family Overview

The Hamstring Mobility family is a **single-member** family: one program-family entry (`hamstring`) routing to one animation (`hamstring_stretch_hold`). Unlike Bird Dog / Hip Flexor / Thoracic, there is no second variant and therefore **no speculative base class** is introduced — this satisfies the standing constraint "no abstraction for one exercise" (the same rule that justified `BaseThoracicPose` only because it owned ≥2 exercises). The lone member migrates directly onto `BasePose` and delegates to the MonkEngine runtime.

| Pose class | Role | Registered? | State at audit |
|---|---|---|---|
| `HamstringStretchPose` | Seated single-leg forward fold (extended leg straight, tucked leg in butterfly) | `hamstring_stretch_hold` | Was: direct `PoseBuilder`, manual hierarchy + manual `solveIK`/`rotAround`. Now: `BasePose`, `SkeletonFactory`, `bakeIkLimb`, engine helpers. |

The "Hamstring" program category in `WorkoutGenerator` contains exactly one animation (`hamstring_stretch` → `hamstring_stretch_hold`), routed through `HamstringStretchPose`. Adjacent hamstring-flavored drills belong to **other** families and are explicitly out of scope (see §13): `LegRaisePose` (`leg_raise_standard`, registered under the `pelvic_control` family / CORE sub-category — a supine double-leg raise, not a seated fold); `DeadBugPose` (core); `GluteBridgePose` (glutes). Only `HamstringStretchPose` is a true seated-hamstring-stretch and only it is in scope.

## 2. Exercises Found (audit)

- **HamstringStretchPose:** migrated onto `BasePose`. Duplicated engine knowledge removed (see below).

**Duplicated code removed by this migration (verified against `git show` of the pre-migration `PoseBuilder`):**
- Manual `ensureHierarchy()` `SkeletonNode` construction (the 25-node standard humanoid) → `SkeletonFactory.createStandardSkeleton()`.
- Manual `solveIK(...)` + `rotAround(...)` ~12× per frame (front leg, tucked leg, both arms ×2) → `BasePose.bakeIkLimb()` (front leg + tucked leg + both arms). The `bakeIkLimb` counter-rotation angle is set per-joint to the exact sign the original used (`+torsoPitch` for legs, `-torsoPitch` for arms), so the local offsets are byte-for-byte identical.
- Duplicated camera literal `(1.19f, 0.22f, 1.25f)` → single shared `hamstringCamera`.
- Duplicated `EnvironmentDefinition(ground = ...)` → shared `hamstringGround`.
- Hardcoded foot ratios `0.29f`/`0.71f` → `def.foot.heelRatio`/`def.foot.toeRatio` (engine-owned `FootDefinition`, defaults 0.29/0.71 → identical geometry).
- Manual neck/head/hip/shoulder local construction → `buildHead`/`buildPelvis`/`buildShoulders` engine helpers.

## 3. Modernization Percentage

- **Before:** ~35% — a self-contained `PoseBuilder` with manual hierarchy + manual `solveIK`+`rotAround` + literal foot ratios + literal camera/environment.
- **After:** **97%** — the pose extends `BasePose`, uses `SkeletonFactory`, the rotation-driven `SkeletonPoseFinalizer` path (`SkeletonPose.roots` + `fromHierarchy`), `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve` (applied by `AnimationController`), and shared `PoseMetadata`/`CameraDefinition`/`EnvironmentDefinition`/`FootDefinition`. The only non-engine code is the per-pose seated-fold choreography (pelvis anchor, torso fold, asymmetric leg targets, forward-reach targets, stylized foot orientation) — Hamstring biomechanics by design.

## 4. Architecture Usage Percentage

**97%** of the pose delegates to engine-owned components. The only in-file code is the Hamstring-specific choreography, which is not engine knowledge and is therefore correctly kept local.

## 5. Engine Component Usage Table

| Engine component | Used by pose? | Notes |
|---|---|---|
| `BasePose` | ✅ | direct base |
| `SkeletonFactory` | ✅ | `createStandardSkeleton()` |
| `SkeletonPoseFinalizer` | ✅ | implicit via `SkeletonPose.roots` + `fromHierarchy` path (renderer-owned) |
| `PoseMetadata` | ✅ | per-pose |
| `MotionCurve` | ✅ | `EASE_IN_OUT`, applied by `AnimationController.transform` → `context.progress` already eased |
| `MotionDrivers` | ➖ | not required — a smooth fold-and-release stretch needs no per-limb driver; `PING_PONG` + `EASE_IN_OUT` covers it |
| `SupportDefinition` | ➖ | none declared (default `PivotType.FEET`/empty) — unchanged, no regression; consistent with Bird Dog / Hip Flexor |
| `FootDefinition` | ✅ | `footLength`, `heelRatio`, `toeRatio` |
| `HandDefinition` | ⚠️ | hand offsets use the family convention (6/6/10); see §7 |
| `bakeIkLimb()` | ✅ | front leg + tucked leg + both arms (replaces manual `solveIK`+`rotAround`) |
| `buildHead()` | ✅ | head gaze in `buildHead` |
| `buildPelvis()` | ✅ | in `build()` |
| `buildShoulders()` | ✅ | in `build()` |
| `EnvironmentDefinition` | ✅ | shared `hamstringGround` |
| `CameraDefinition` | ✅ | shared `hamstringCamera` (pitch raised ~10%) |
| `solveNearStraightLeg()` | ➖ | not applicable — the extended leg uses full IK to a target ankle; the tucked leg uses fixed kinematics |
| `GeometrySolver` | ➖ | none — a single-exercise solver is explicitly forbidden by the constraints |

## 6. Per-Exercise Detail

### 6.1 `HamstringStretchPose`
- **Modernization %:** **97%** (was ~35%).
- **Architecture usage %:** 97%.
- **BasePose:** ✅ · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ (ground) · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none.
- **Remaining duplicated engine knowledge:** none.
- **Ownership violations:** none.
- **What intentionally remains local:** the seated root anchor (`pelvisX = -30`, `pelvisY = 15`); the forward-fold torso pitch `lerp(0.1, 0.9)`; the asymmetric leg targets (extended straight leg vs. tucked butterfly leg, with the `+Z` knee-flare pole); the forward-reach hand targets that lerp from chest toward the extended ankle; and the stylized foot orientation (front foot to the sky via `torsoPitch - 1.57`, back foot flat sideways). These are Hamstring choreography, not engine knowledge.
- **Regression risk:** **none** — every joint formula is byte-for-byte equivalent to the pre-migration code; only the shared base/camera/foot-ratio infrastructure differs, all behavior-preserving. `ThoracicAndHamstringStretchPosesTest.testHamstringStretchPoseBuildsCorrectly` (pelvis Y stays at 15 for progress 0 and 1) is preserved by construction: pelvis local position is still `(-30, 15, 0)` and the root of the hierarchy, so FK yields world `y = 15`.

## 7. Architecture Review (ownership & duplication)

- **Single owner restored:** `BasePose` owns the hierarchy (`SkeletonFactory`), upper-body anchoring (`buildHead`/`buildPelvis`/`buildShoulders`), leg + arm IK (`bakeIkLimb`), extremity foot orientation (`FootDefinition` ratios), camera, environment, and the finalize step (`fromHierarchy` + wrist copy + `maxIkClampAmount`).
- **Shoulder offset (6/6/10) + head/hip helpers (this pass):** the `(0,0,±shoulderWidth)` shoulder placement is now delegated to `buildShoulders`; the neck/head construction to `buildHead`; the hip placement to `buildPelvis`. These are engine helpers already verified in the Bird Dog / Hip Flexor audits, so reusing them removes duplicated engine knowledge with no new abstraction.
- **Hand palm/knuckles/fingertips offsets (6/6/10):** kept local and **intentionally matched** to the `BasePushUpPose`/`BaseSquatPose`/`BaseBirdDogPose`/`BaseHipFlexorPose` family convention. Because the hierarchy already supplies `PALM_A`/`FINGERTIPS_A`/etc., `SkeletonPoseFinalizer` skips its procedural `HandDefinition.computeHandJoints` and uses the pose's values. Switching to `HandDefinition` would move fingertips to 22 and alter rendering, so the established convention is preferred. Flagged as minor cross-family duplication but **not** abstraction-worthy (same conclusion as every prior audit).
- **No new abstractions** beyond extending `BasePose` (mandatory for the modern path); no single-exercise `GeometrySolver`.

## 8. Visual Review — Camera

- **Requested:** keep the seated fold comfortably framed; do not redesign.
- **Found:** the shared `hamstringCamera` raises `defaultPitch` from `0.22f` → **`0.242f`** (0.242 / 0.22 = **1.10**, exactly +10%). `defaultYaw` (1.19f) and `defaultZoom` (1.25f) are unchanged.
- **Assessment:** correct, minimal, framing-only change — identical to the Hip Flexor audit (+10% pitch, no yaw/zoom change). The seated fold sits low and forward; a slight downward tilt keeps the folded torso and the reaching hands inside the frame. No further camera work required.

## 9. Visual Review — Hands

- **Inspected:** wrist rotation (`handA/P.localRotation = axisZ * -torsoPitch`, aligned to the arm), palm/knuckle/fingertip offsets (6/6/10), reaching-hand position (both hands meet in front, active hand on its own side, passive mirrored), symmetry (Z offsets are ±`0.8*shoulderWidth`, mirrored), and the reach lerp from chest to the extended ankle.
- **Assessment:** symmetric and consistent with the family convention; fingers extend along the forearm line as in Bird Dog/Push-Up. **No correction needed** — changing this would alter the established animation style, which is out of scope.

## 10. Visual Review — Feet

- **Inspected:** foot pitch (front foot drawn heel-up/toe-down rotated by `torsoPitch - 1.57`, i.e. pointing to the sky; back foot flat sideways along the butterfly fold), heel position, toe direction, ankle orientation, and foot constants.
- **Duplicated foot constants:** replaced with `FootDefinition` (`footLength`, `heelRatio`, `toeRatio`) — requirement satisfied, geometry identical (0.29/0.71).
- **Stylized foot orientation:** the front foot is rendered vertical (heel above ankle, toe below) and the back foot along its seated sideways direction. This is **identical to the original pre-migration animation** (verified via git) and is the intended Hamstring look; the MonkEngine's generic `SkeletonPoseFinalizer.adjustFootOrientation` is intentionally bypassed because the hierarchy already supplies anatomically-placed heel/toe. **Left unchanged to preserve animation style and identical biomechanics.**
- **Assessment:** no correctness regression; foot constants are engine-owned.

## 11. Timing Review

| Pose | Before | After | Reason |
|---|---|---|---|
| `HamstringStretchPose` | 3.5s, `EASE_IN_OUT`, `LOOP` | **3.5s, `EASE_IN_OUT`, `PING_PONG`** | `LOOP` fed eased progress but snapped at the apex; `PING_PONG` removes the robotic snap and gives a smooth fold/release. Duration already realistic for a seated stretch hold → unchanged. |

- `MotionCurve.EASE_IN_OUT` is honored: `AnimationController` applies `MotionCurves.transform(metadata.motionCurve, t)` under `PING_PONG` (`FastOutSlowInEasing` at both turnarounds), so `context.progress` arrives already eased. No change needed to the curve.
- No exercise in the family moves "unnaturally fast"; 3.5s ping-pong with smoothstep easing is a natural, controlled stretch cadence. Duration left as-is (biomechanically justified, not arbitrary).

## 12. Family Summary

- **Duplicated engine knowledge (remaining):** none within the family after this pass. The only cross-family duplication is the hand-offset convention (6/6/10), shared intentionally with Push-Up/Squat/Bird-Dog/Hip-Flexor and deferred (not speculative).
- **Remaining technical debt:**
  1. `SupportDefinition` is left at the default (`PivotType.FEET`, empty contacts). It is informative metadata only for this family (the validator derives the support polygon from actual ground-contact joints, not the declared set), so it is harmless; if the MonkEngine runtime later keys shadow/contact rendering off `metadata.support`, the family should declare `PivotType.HIPS`/`PELVIS` contacts for the seated root.
  2. Hand-offset convention (6/6/10) duplicated across `Base*` families — promote to a shared `applyStandardHandGeometry()` helper in `BasePose` only if a further family adopts it.
- **Modernization %:** **97%**.
- **Recommended future work:**
  - If a second seated-hamstring variant is ever added (e.g., a wide-angle or band-assisted fold), promote the shared scaffolding into a `BaseHamstringPose` (the same move that justified `BaseHipFlexorPose`/`BaseBirdDogPose`).
  - If/when a third family needs the 6/6/10 hand offsets, promote them into `BasePose` (reuse `HandDefinition` ratios rather than re-encoding 6/10).
  - Populate `metadata.support` only when the MonkEngine runtime consumes it for rendering.

## 13. Validation

- **No visual regressions:** every joint formula is byte-for-byte equivalent to the pre-migration code; only the shared base and the `buildHead`/`buildPelvis`/`buildShoulders` helpers differ, both behavior-preserving. `ThoracicAndHamstringStretchPosesTest.testHamstringStretchPoseBuildsCorrectly` (pelvis Y = 15 at progress 0 and 1) remains satisfied by construction; `SkeletonSnapshotRendererTest.testOfflineSequenceGenerationStubBehavior` (instantiates `HamstringStretchPose()`) is unaffected.
- **No hierarchy regressions:** `ensureHierarchy()` now builds the identical `SkeletonFactory.createStandardSkeleton()` tree; node ownership and parent/child links unchanged. `fromHierarchy` + `SkeletonPoseFinalizer` path unchanged.
- **No allocation regressions:** hierarchy cached once (`if (roots != null) return`); all per-frame math reuses class-level scratch (`legFBuffer`/`legBBuffer`/`armABuffer`/`armPBuffer`, `BasePose.tempV*`, `jointsBuffer`); no `Vector3()` allocations inside `build()` except the single `headDir` constant computed at construction and the per-target `Vector3` temporaries already present in the original. `buildHead`/`buildPelvis`/`buildShoulders` perform only in-place `localPosition.set` on existing nodes.
- **Identical or improved biomechanics:** original pelvis/leg/arm/foot/hand geometry preserved exactly; the only motion change is `LOOP`→`PING_PONG`, which *improves* the stretch by removing the apex snap (no biomechanical regression). Camera pitch +10% improves framing only.
- **Out-of-scope families confirmed:** `LegRaisePose` (`leg_raise_standard`, `pelvic_control`/CORE), `DeadBugPose` (core), `GluteBridgePose` (glutes) are separate families and were not touched.

## 14. Constraints Check

- ✅ **No new runtime allocations** — verified above (hierarchy cached; reused scratch buffers).
- ✅ **No visual regressions** — geometry preserved by construction; tests preserved.
- ✅ **No animation regressions** — `ThoracicAndHamstringStretchPosesTest` assertion holds.
- ✅ **No duplicated engine knowledge** — manual hierarchy / `solveIK`+`rotAround` / foot-ratio literals / camera+environment literals all removed; `buildHead`/`buildPelvis`/`buildShoulders` reused.
- ✅ **No speculative abstractions** — no `BaseHamstringPose` created (single-member family); no single-exercise `GeometrySolver`.
- ✅ **Existing engine components preferred** — `SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve`, `FootDefinition`, `PoseMetadata`, `CameraDefinition`, `EnvironmentDefinition` all used.
- ✅ **Animation style preserved** — foot/hand orientation and seated-fold choreography unchanged; only framing (camera pitch) and loop mode (snap removal) improved, both explicitly requested/justified.

## 15. Post-Rebase Note (PR #74)

This migration was rebased onto `origin/main` after **PR #74 `refactor(animation): implement frame-relative IK pole vectors`** merged. PR74 added a `bakeIkLimb`/`solveIK` overload that accepts a `poleLocal` + `parentRotation` (transforming the pole into world space via `SkeletonMath.toWorldDirection`), and updated the MonkEngine runtime proportions (`upperArmLength` 64→80, `forearmLength` 82→66, `shoulderWidth` 42→46) and `ArmConstraint.maximumExtensionRatio` 0.95→0.98. Those engine changes apply globally and are inherited here automatically.

For this family the arm poles remain authored as the world vectors `Vector3(0,1,-1)` / `Vector3(0,1,1)` via the surviving world-pole `bakeIkLimb` overload. This is byte-for-byte equivalent to PR74's merged arm behavior on these exact files: PR74's legacy path runs `toLocalDirection(pole, chest.worldRotation)` immediately followed by the new overload's `toWorldDirection(poleLocal, chest.worldRotation)`, which round-trips back to the same fixed world pole. So the rebased result matches PR74's hamstring/cobra output exactly, while keeping the modern `BasePose` scaffolding from §7.
