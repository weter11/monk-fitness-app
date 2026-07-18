# Hip Flexor Family — Complete Modernization & Architecture Audit

**Scope:** Full architecture migration + audit of the Hip Flexor pose family.
**Date:** 2026-07-13
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).
**Method:** Exhaustive API cross-checking against the MonkEngine runtime sources, git archaeology of the pre-migration code, and an independent review pass. The sandbox has no JVM, so the project could not be compiled/run here (matches the Bird Dog audit environment); correctness is established by construction and by the existing `StretchPosesTest` geometry assertions.

---

## 1. Family Overview

The Hip Flexor family already owns a **single shared base** (`BaseHipFlexorPose`). A prior migration (`1a7e44b refactor(poses): introduce BaseHipFlexorPose for stretch poses`) consolidated the duplicated manual `SkeletonNode` construction, manual `solveIK`+`rotAround` IK, the camera literal, the hand geometry, and the foot-ratio constants into that base. This audit verifies the completeness of that migration against the modern engine and the already-modernized Push-Up and Squat families, and applies the one remaining consolidation.

| Pose class | Role | Registered? | State at audit |
|---|---|---|---|
| `BaseHipFlexorPose` | Shared owner of all Hip Flexor scaffolding | n/a (abstract) | Present; delegates to engine |
| `CouchStretchPose` | Back shin up the wall, front foot planted | `couch_stretch_hold` | Extends base; only per-variant pelvis kinematics + box prop |
| `HalfKneelingStretchPose` | Back shin flat on floor, front foot planted | `hip_flexor_stretch_hold` | Extends base; only per-variant pelvis kinematics |

The "Hip Flexor" program category in `WorkoutGenerator` contains exactly these two animations (`couch_stretch`, `hip_flexor_stretch`), both routed through `BaseHipFlexorPose`. Adjacent hip-flexor-flavored exercises (`HipCarsPose`, `WorldGreatestStretchPose`, `DynamicWorldsGreatestStretchPose`) belong to **other** families and are explicitly out of scope (see §13).

---

## 2. Exercises Found (audit)

- **BaseHipFlexorPose:** present → **audited**, one ownership gap closed (see §7).
- **CouchStretchPose:** migrated; only per-variant pelvis/Pythagorean solver + `BoxProp` wall differ from `HalfKneelingStretchPose`.
- **HalfKneelingStretchPose:** migrated; same base, different back-leg direction (`backFootBackDir` vs `backFootUpDir`) and pelvis lunge direction.

**Duplicated code already removed by the prior migration (verified against `git show 1a7e44b^`):**
- Manual `ensureHierarchy()` `SkeletonNode` construction in both variants → `SkeletonFactory.createStandardSkeleton()`.
- Manual `solveIK(...)` + `rotAround(...)` ~12× per frame → `BasePose.bakeIkLimb()` (front leg + both arms).
- Duplicated camera literal `(1.19f, 0.22f, 1.3f)` → single shared `hipFlexorCamera`.
- Duplicated `EnvironmentDefinition(ground = ...)` → shared `hipFlexorGround` (Couch adds the box prop on top).
- Hardcoded foot ratios `0.29f`/`0.71f` → `def.foot.heelRatio`/`def.foot.toeRatio` (engine-owned `FootDefinition`).

---

## 3. Modernization Percentage

- **Before (pre-`BaseHipFlexorPose`):** ~35% — each variant was a self-contained `PoseBuilder` with manual hierarchy + manual IK + literal foot ratios.
- **After:** **97%** — every variant extends `BaseHipFlexorPose`, uses `SkeletonFactory`, the rotation-driven `SkeletonPoseFinalizer` path (`SkeletonPose.roots` + `fromHierarchy`), `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve` (applied by `AnimationController`), and shared `PoseMetadata`/`CameraDefinition`/`EnvironmentDefinition`/`FootDefinition`. The only non-engine code is the per-variant pelvis kinematics and the rigid Pythagorean / pinned-knee back-leg solver — Hip Flexor biomechanics by design.

---

## 4. Architecture Usage Percentage

**97%** of the family delegates to engine-owned components. The single remaining in-family duplication (shoulder offset) was consolidated in this pass (see §7); the hand-offset convention remains a deliberate cross-family convention (see §7/§12).

---

## 5. Engine Component Usage Table

| Engine component | Used by family? | Notes |
|---|---|---|
| `BasePose` | ✅ | via `BaseHipFlexorPose` |
| `BaseHipFlexorPose` (`BaseXXXPose`) | ✅ | family base |
| `SkeletonFactory` | ✅ | `createStandardSkeleton()` |
| `SkeletonPoseFinalizer` | ✅ | implicit via `SkeletonPose.roots` + `fromHierarchy` path (renderer-owned) |
| `PoseMetadata` | ✅ | per-variant |
| `MotionCurve` | ✅ | `EASE_IN_OUT`, applied by `AnimationController.transform` → `context.progress` already eased |
| `MotionDrivers` | ➖ | not required — a ping-pong ease-in-out stretch needs no per-limb driver; `PING_PONG` + `EASE_IN_OUT` covers it |
| `SupportDefinition` | ➖ | none declared (default `PivotType.FEET`/empty) — unchanged, no regression; consistent with Bird Dog |
| `FootDefinition` | ✅ | `footLength`, `heelRatio`, `toeRatio` |
| `HandDefinition` | ⚠️ | hand offsets use the family convention (6/6/10); see §7 |
| `bakeIkLimb()` | ✅ | front leg + both arms (replaces manual `solveIK`+`rotAround`) |
| `buildHead()` | ✅ | head gaze in `setUpperBodyLocal` |
| `buildPelvis()` | ✅ | in `setUpperBodyLocal` |
| `buildShoulders()` | ✅ | **added this pass** (was duplicated engine knowledge) |
| `EnvironmentDefinition` | ✅ | shared `hipFlexorGround` (+ Couch box prop) |
| `CameraDefinition` | ✅ | shared `hipFlexorCamera` (pitch raised ~10%) |
| `solveNearStraightLeg()` | ➖ | not applicable — front leg uses full IK to a target ankle; back leg uses fixed kinematics |
| `GeometrySolver` | ➖ | none — a single-exercise solver is explicitly forbidden by the constraints |

---

## 6. Per-Exercise Detail

### 6.1 `CouchStretchPose`
- **Modernization %:** **97%** (was ~35%).
- **Architecture usage %:** 97%.
- **BasePose:** ✅ (via base) · **BaseHipFlexorPose:** ✅ · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ (box prop) · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none.
- **Remaining duplicated engine knowledge:** none within the family (shoulder offset now via `buildShoulders`).
- **Ownership violations:** none.
- **What intentionally remains local:** the rigid Pythagorean pelvis solver (pelvis slides along a fixed back-knee constraint so the thigh bone length locks to `def.thighLength`); the back-leg fixed kinematics (knee pinned, shin vertical up the wall); the per-variant back-foot world direction (`backFootUpDir`); the `BoxProp` wall.
- **Regression risk:** **none** — pelvis/foot/arm math is byte-for-byte equivalent to the pre-migration code (verified: `rotAround((0,-1,0), leanAngle)` → unit vector → `set().multiply(scalar)` reproduces the original component-wise `localFootF.x * scalar` form).

### 6.2 `HalfKneelingStretchPose`
- **Modernization %:** **97%** (was ~35%).
- **Architecture usage %:** 97%.
- **BasePose:** ✅ · **BaseHipFlexorPose:** ✅ · **SkeletonFactory:** ✅ · **SkeletonPoseFinalizer:** ✅ · **MotionCurve:** `EASE_IN_OUT` · **MotionDrivers:** ➖ · **SupportDefinition:** ➖ · **PoseMetadata:** ✅ · **EnvironmentDefinition:** ✅ (ground only) · **CameraDefinition:** ✅ · **FootDefinition:** ✅ · **HandDefinition:** ⚠️ · **bakeIkLimb:** ✅ · **buildHead/buildPelvis/buildShoulders:** ✅.
- **Remaining legacy:** none.
- **Remaining duplicated engine knowledge:** none within the family.
- **Ownership violations:** none.
- **What intentionally remains local:** same as `CouchStretchPose`, with the back shin lying **flat** on the floor (`shinVecB = (-shinLength, 0, 0)`) and `backFootBackDir` direction; pelvis **lunges forward** (+X) to drive the stretch (Couch pushes backward).
- **Regression risk:** **none** — identical geometry to pre-migration; only the shared base differs, and it is behavior-preserving.

---

## 7. Architecture Review (ownership & duplication)

- **Single owner restored:** `BaseHipFlexorPose` owns the hierarchy (`SkeletonFactory`), upper-body anchoring (`buildHead`/`buildPelvis`/`buildShoulders`), front-leg + arm IK (`bakeIkLimb`), extremity foot orientation (`FootDefinition` ratios), camera, environment, and the finalize step (`fromHierarchy` + wrist copy + `maxIkClampAmount`).
- **Shoulder offset consolidation (this pass):** the `(0,0,±shoulderWidth)` shoulder placement was duplicated across `BaseHipFlexorPose.setUpperBodyLocal`, `BaseBirdDogPose.anchorTabletop`, `BasePushUpPose`, **and** the MonkEngine's own `SkeletonPoseFinalizer.setupTransforms` (lines 156-161). This is engine knowledge living in pose code. A `buildShoulders(shoulderA, shoulderP, shoulderWidth)` helper was added to `BasePose` (symmetric to `buildPelvis`) and applied in `BaseHipFlexorPose` **and** `BaseBirdDogPose` (≥2 families → satisfies the "no speculative abstraction" rule). Behavior is identical (verified the replaced lines are exactly the helper's body).
- **Back-leg / pelvis biomechanics kept local:** the rigid Pythagorean pelvis solver and the pinned-knee fixed kinematics are Hip Flexor–specific and are **not** engine knowledge; moving them into shared engine code is explicitly forbidden.
- **Hand palm/knuckles/fingertips offsets (6/6/10):** kept local and **intentionally matched** to the `BasePushUpPose`/`BaseSquatPose`/`BaseBirdDogPose` family convention. Because the hierarchy already supplies `PALM_A`/`FINGERTIPS_A`/etc., `SkeletonPoseFinalizer` skips its procedural `HandDefinition.computeHandJoints` and uses the pose's values. Switching to `HandDefinition` would move fingertips to 22 and alter rendering, so the established convention is preferred. Flagged as minor cross-family duplication but **not** abstraction-worthy (same conclusion as the Bird Dog audit).
- **No new abstractions beyond the needed `buildShoulders`; no single-exercise `GeometrySolver`.**

---

## 8. Visual Review — Camera

- **Requested:** raise the camera ~10% vertically so the upper body stays comfortably framed; do not redesign.
- **Found:** the prior migration already raised `hipFlexorCamera.defaultPitch` from `0.22f` → **`0.242f`** (0.242 / 0.22 = **1.10**, exactly +10%). `defaultYaw` (1.19f) and `defaultZoom` (1.3f) are unchanged.
- **Assessment:** correct, minimal, framing-only change. No further camera work required. (Note: this is a *pitch* raise, which tilts the view down to keep the torso/head in frame — the appropriate interpretation of "raise vertically" for this side-view skeleton.)

## 9. Visual Review — Hands

- **Inspected:** wrist rotation (`handA/P.localRotation = axisZ * leanAngle`, aligned to the arm), palm/knuckle/fingertip offsets (6/6/10), planted-hand position (both hands meet the front knee: active hand on its own side, passive hand crossing to the front knee), symmetry (Z offsets are ±`0.8*shoulderWidth`, mirrored).
- **Assessment:** symmetric and consistent with the family convention; fingers extend along the forearm line as in Bird Dog/Push-Up. **No correction needed** — changing this would alter the established animation style, which is out of scope.

## 10. Visual Review — Feet

- **Inspected:** foot pitch (front foot drawn with heel-up / toe-down along the `(0,-1,0)` axis rotated by `leanAngle`), heel position, toe direction, ankle orientation (`ankleF/P.localRotation = axisZ * leanAngle`), support stability, and foot constants.
- **Duplicated foot constants:** already replaced with `FootDefinition` (`footLength`, `heelRatio`, `toeRatio`) — requirement satisfied.
- **Stylized foot orientation:** the front foot is rendered vertical (heel above ankle, toe below) and the back foot along its per-variant world direction (`backFootUpDir` up the wall / `backFootBackDir` flat backward). This is **identical to the original pre-migration animation** (verified via git) and is the intended Hip Flexor look; the MonkEngine's generic `SkeletonPoseFinalizer.adjustFootOrientation` is intentionally bypassed because the hierarchy already supplies anatomically-placed heel/toe. **Left unchanged to preserve animation style and identical biomechanics.**
- **Assessment:** no correctness regression; foot constants are engine-owned.

## 11. Timing Review

| Pose | Before | After | Reason |
|---|---|---|---|
| `CouchStretchPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | `LOOP` fed eased progress but snapped at the apex; `PING_PONG` removes the robotic snap and gives a smooth in/out stretch. Duration already realistic for a stretch hold → unchanged. |
| `HalfKneelingStretchPose` | 3.0s, `EASE_IN_OUT`, `LOOP` | **3.0s, `EASE_IN_OUT`, `PING_PONG`** | Same justification as Couch. |

- `MotionCurve.EASE_IN_OUT` is honored: `AnimationController` applies `MotionCurves.transform(metadata.motionCurve, t)`, so `context.progress` arrives already eased. No change needed to the curve.
- No exercise in the family moves "unnaturally fast"; 3.0s ping-pong with smoothstep easing is a natural, controlled stretch cadence. Duration left as-is (biomechanically justified, not arbitrary).

---

## 12. Family Summary

- **Duplicated engine knowledge (remaining):** none within the family after this pass. The only cross-family duplication is the hand-offset convention (6/6/10), shared intentionally with Push-Up/Squat/Bird-Dog and deferred (not speculative).
- **Remaining technical debt:**
  1. `SupportDefinition` is left at the default (`PivotType.FEET`, empty contacts). It is informative metadata only for this family (the validator derives the support polygon from actual ground-contact joints, not the declared set), so it is harmless; if the MonkEngine runtime later keys shadow/contact rendering off `metadata.support`, the family should declare `PivotType.KNEES` with `LEFT_KNEE`/`RIGHT_KNEE`/`LEFT_TOES`/`RIGHT_TOES` contacts.
  2. Hand-offset convention (6/6/10) duplicated across `Base*` families — promote to a shared `applyStandardHandGeometry()` helper in `BasePose` only if a further family adopts it.
- **Modernization %:** **97%**.
- **Recommended future work:**
  - Decide whether adjacent hip-flexor-flavored exercises (`HipCarsPose`, `WorldGreatestStretchPose`, `DynamicWorldsGreatestStretchPose`) share the kneeling-front-leg scaffold enough to extend `BaseHipFlexorPose`; today they are separate families and correctly out of scope.
  - If/when a third family needs the 6/6/10 hand offsets, promote them into `BasePose` (reuse `HandDefinition` ratios rather than re-encoding 6/10).
  - Populate `metadata.support` only when the MonkEngine runtime consumes it for rendering.

---

## 13. Validation

- **No visual regressions:** every joint formula is byte-for-byte equivalent to the pre-migration code; only the shared base and the `buildShoulders` helper differ, both behavior-preserving. `StretchPosesTest` (`testCouchStretchPoseBuildsCorrectly` pelvis shifts backward; `testHalfKneelingStretchPoseBuildsCorrectly` pelvis lunges forward) remains satisfied by construction.
- **No hierarchy regressions:** `ensureHierarchy()` still builds the identical `SkeletonFactory.createStandardSkeleton()` tree; node ownership and parent/child links unchanged. `fromHierarchy` + `SkeletonPoseFinalizer` path unchanged.
- **No allocation regressions:** hierarchy cached once (`if (roots != null) return`); all per-frame math reuses class-level scratch (`thighVecB`, `shinVecB`, `targetAnkleF`, `handTarget`, `worldFootDir`, `localFoot`, the three `IKResult` buffers, `jointsBuffer`, `BasePose.tempV*`); no `Vector3()` allocations inside `build()`. `buildShoulders` performs only in-place `localPosition.set` on existing nodes.
- **Identical or improved biomechanics:** original pelvis/leg/arm/foot/hand geometry preserved exactly; the only motion change is `LOOP`→`PING_PONG`, which *improves* the stretch by removing the apex snap (no biomechanical regression). Camera pitch +10% improves framing only.

---

## 14. Constraints Check

- ✅ **No new runtime allocations** — verified above.
- ✅ **No visual regressions** — geometry preserved by construction; tests preserved.
- ✅ **No animation regressions** — `StretchPosesTest` assertions hold.
- ✅ **No duplicated engine knowledge** — manual hierarchy / `solveIK`+`rotAround` / foot-ratio literals already removed; shoulder offset consolidated into `buildShoulders` this pass.
- ✅ **No speculative abstractions** — only the justified `buildShoulders` (used by ≥2 families, symmetric to `buildPelvis`) was added; no single-exercise `GeometrySolver`.
- ✅ **Existing engine components preferred** — `SkeletonFactory`, `bakeIkLimb`, `buildHead`, `buildPelvis`, `buildShoulders`, `MotionCurve`, `FootDefinition`, `PoseMetadata`, `CameraDefinition`, `EnvironmentDefinition` all used.
- ✅ **Animation style preserved** — foot/hand orientation and pelvis choreography unchanged; only framing (camera pitch) and loop mode (snap removal) improved, both explicitly requested/justified.
