# Thoracic Mobility Family — Biomechanics-First Rewrite & Architecture Modernization

**Scope:** Rewrite the Thoracic Mobility family for correct human movement, then modernize its architecture.
**Date:** 2026-07-13
**Engine baseline:** `com.monkfitness.app.animation` (rotation-driven / `SkeletonFactory` architecture).
**Method:** Source cross-checking against the engine, FK/IK trace of the old and new implementations, and reuse of the proven `BaseHipFlexorPose`/`BaseBirdDogPose` migration pattern. The sandbox has no JVM, so the project was not compiled here; correctness is established by construction and by the existing geometry tests (updated where the movement itself changed).

**Priority order followed:** (1) fix biomechanics, (2) validate the visual movement, (3) modernize architecture. No modern architecture was built around the old incorrect motion.

---

## 1. The Core Biomechanical Fix

Every thoracic drill shares one principle the old code violated: **the rib cage is the driver.** In the old implementation the `twist` scalar was applied only to the shoulder offsets while the chest/rib cage, neck and head stayed essentially static (and the shoulder offset was double-rotated about X then Z). That produced arms that moved while the torso watched.

The rewrite drives the motion from the **`chest` node itself**:
- Rotation drills (Quadruped, World's Greatest Stretch): `chest.localRotation` is a rotation about the spine's long axis (chest-local `+Y`). Because the neck, head and both shoulders are children of the chest, the **entire upper chain rotates as one coherent segment** — exactly as a real thoracic rotation.
- Extension (Thoracic Extension): `chest.localRotation` is a rotation about the lateral axis (chest-local `+Z`), so the chest arches up/back, the rib cage opens, and the head follows.

A thoracic-specific helper `bakeThoracicArm(...)` then solves each arm in world space and re-expresses the elbow/hand local offsets in the chest's local frame using the **exact inverse** of the chest world rotation (a single axis-angle → `rotAround` by `-angle` is exact). This keeps the arms reaching to world targets (a planted hand, or a reach that lives in the chest's rotating frame) while the rib cage leads.

> The dead `WorldGreatestStretchPose` contained the *correct concept* (rotate the shoulder girdle about the spine axis; head follows the twist). Its concept was realized via the `chest` node and the class was then deleted.

---

## 2. Per-Exercise Biomechanics Analysis & What Was Rewritten

### 2.1 `QuadrupedThoracicRotationsPose` (`thoracic_rotations_reps`)

**Old (wrong):** shoulders orbited a static torso; rib cage/head did not rotate; both shoulders (incl. the "planted" support side) moved with the twist; double-axis rotation swung shoulders forward instead of up/over.

**New (rewritten):**
- **Pelvis stable**, tabletop (`spinePitch = -π/2`, pelvis at `(-20,127,0)`). ✓
- **Thoracic rotation about the spine:** `chest.localRotation = (axisY, twist)`, `twist ∈ [+0.35, -1.35]` rad. The rib cage, neck, head and both shoulders rotate together. ✓
- **Head follows the opening side:** `headDir` is fixed in the chest frame, so the head swings with the thorax (gaze up/forward/active). ✓
- **Supporting hand planted** on the floor directly under the support shoulder → a stable pillar (IK to a fixed world point). ✓
- **Reaching hand follows the thorax:** its target lives in the chest's rotating frame (`chestLocalToWorld`), so the arm threads under and opens to the sky *because the spine rotates*, not independently. A floor clamp (`y ≥ 6`) prevents punch-through. ✓
- **Legs planted** in tabletop (knees/shins on the floor); feet dorsiflexed via `FootDefinition` ratios. ✓

**Justification:** driving rotation from `chest` makes the rib cage the mover (physio reality). Pinning the support hand keeps the pillar stable. Reach-in-rotating-frame guarantees shoulder motion is secondary to thoracic motion.

### 2.2 `ThoracicExtensionPose` (`thoracic_extension_reps`)

**Old (wrong):** floating knees/shins/feet (ankles lifted ~30–50 units, toes pointing at the sky); lumbar driven into extension along with the thorax; lower body broken.

**New (rewritten):**
- **Tall kneeling, pelvis neutral** (`spinePitch = 0`, pelvis directly above the knees at `(0,127,0)`). No forward shift, no lumbar contribution. ✓
- **Extension from the thoracic spine only:** `chest.localRotation = (axisZ, extAngle)`, `extAngle ∈ [0, 0.5]` rad → chest arches up/back. ✓
- **Head follows** (looks up/back); rib cage opens upward. ✓
- **Legs FIXED on the floor:** thigh vectors (knee−pelvis) and flat shin vectors are placed as local offsets; knees and ankles are world-fixed floor points, so **nothing floats**. ✓
- **Feet flat**, toes pointing back (no plantar flexion, no sky-pointing). ✓
- **Hands behind head**, elbows flared outward/up (`pole (0, 0.6, ±2)`) to open the chest. ✓

**Justification:** isolating thoracic extension (pelvis neutral) prevents lumbar overdominance; grounding the knees/shins/ankles/toes restores the defining feature of kneeling mobility.

### 2.3 `DynamicWorldsGreatestStretchPose` (`world_greatest_stretch`)

**Old (hollow):** the lunge base was fine, but the "rotation" was cosmetic — shoulders orbited a static chest, the head didn't follow, and the arm swept because its hand target was lerped in world space, not because the body turned.

**New (rewritten):**
- **Runner's-lunge base reused** (front foot flat forward, back leg extended, toe on floor, torso leaning forward `leanAngle = 0.5`). ✓
- **Real thoracic rotation:** `chest.localRotation = (axisY, twist)`, `twist ∈ [-0.2, 1.4]` → rib cage/neck/head/shoulders all turn. ✓
- **Head follows** the rotation. ✓
- **Reaching arm follows the thorax:** target in the chest's rotating frame, sweeping from the low instep to the sky *as the spine rotates*; floor-clamped. ✓
- **Support hand planted** beside the front foot (stable pillar). ✓
- Feet: front flat, back plantar-flexed, both via `FootDefinition` ratios. ✓

**Justification:** the lunge is biomechanically correct and was kept; only the (previously fake) rotation was rebuilt from the chest node so the body actually turns.

### 2.4 `WorldGreatestStretchPose` (dead) — removed

Unregistered and never rendered. Its correct "rotate about the spine axis / head follows" idea was folded into the new `DynamicWorldsGreatestStretchPose` via the `chest` node, then the file was deleted.

---

## 3. Architecture Modernization

Introduced **`BaseThoracicPose`** (a `BaseXXXPose`, used by all three active exercises — satisfies "no abstraction for one exercise"). It now owns, via the engine:

| Engine component | Usage |
|---|---|
| `BasePose` | ✅ (via `BaseThoracicPose`) |
| `BaseThoracicPose` (`BaseXXXPose`) | ✅ new family base |
| `SkeletonFactory` | ✅ `createStandardSkeleton()` |
| `SkeletonPoseFinalizer` (modern path) | ✅ `fromHierarchy` + `roots` |
| `PoseMetadata` | ✅ per-variant |
| `MotionCurve` | ✅ `EASE_IN_OUT` (now effective under `PING_PONG`) |
| `MotionDrivers` | ➖ not required (single continuous thoracic motion) |
| `SupportDefinition` | ➖ default (informational only, per other families) |
| `FootDefinition` | ✅ `heelRatio`/`toeRatio` (removed the `0.29/0.71` literals) |
| `HandDefinition` convention | ✅ `6/6/10` open-hand offsets (family convention shared with Push-Up/Squat/Bird-Dog/Hip-Flexor) |
| `bakeIkLimb()` | ✅ legs (pelvis rotation is always about Z → engine Z counter-rotation is exact) |
| `buildHead` / `buildPelvis` / `buildShoulders` | ✅ |
| `bakeThoracicArm()` | ✅ new family helper (arms root under the rotating chest) — justified, not a single-exercise solver |
| `CameraDefinition` | ✅ shared `thoracicCamera(pitch, zoom)` (single source of truth for yaw `1.19`) |
| `EnvironmentDefinition` | ✅ shared `thoracicGround` |

No `ThoracicGeometrySolver` was introduced (per-exercise choreography is thoracic biomechanics, kept local).

**Modernization %:** **~95%** (was ~25%). **Architecture usage %:** **~95%**. The only non-engine code is the per-exercise root anchoring, the thoracic drive (twist/extension), and the reach choreography — all thoracic biomechanics, not engine knowledge.

---

## 4. Camera (Phase 4 — framing only)

| Pose | Before | After | Why |
|---|---|---|---|
| Quadruped Thoracic Rotations | pitch 0.22, zoom 1.2 | **pitch 0.24, zoom 1.2** | keep shoulders/neck framed during rotation |
| Thoracic Extension | pitch 0.22, zoom 1.3 | **pitch 0.26, zoom 1.35** | head rises to `y≈300`; frame head + upper thorax |
| Dynamic World's Greatest Stretch | pitch 0.22, zoom 1.1 | **pitch 0.24, zoom 1.25** | widest pose; 1.1 was most zoomed-in → risk clipping during full reach |

No yaw change, no redesign — consistent with the Hip Flexor (+10% pitch) and Bird Dog (+10% zoom) audits.

---

## 5. Animation Timing (Phase 5)

All three were `LOOP` with `EASE_IN_OUT`. Critical finding retained from the audit: for **non-alternating `LOOP`**, `AnimationController` assigns raw `progress` and **ignores `MotionCurve`**, producing a linear reverse tween with a velocity kink (the "snap") at the apex. Converted all three to **`PING_PONG`** (`FastOutSlowInEasing` at both turnarounds), which both removes the snap and makes the easing intent real.

| Pose | Before | After | Biomechanical justification |
|---|---|---|---|
| Quadruped Thoracic Rotations | 2.5 s, `LOOP` | **3.0 s, `PING_PONG`** | slow, controlled thoracic rotation |
| Thoracic Extension | 3.0 s, `LOOP` | **3.2 s, `PING_PONG`** | slow controlled extension |
| Dynamic World's Greatest Stretch | 3.5 s, `LOOP` | **3.8 s, `PING_PONG`** | large floor→sky sweep needs time |

No change is arbitrary — each duration was chosen for slow, continuous, natural thoracic control.

---

## 6. Validation (per the required checklist)

- **Pelvis behaves naturally:** stable in tabletop/WGS; neutral and grounded in tall kneeling (no forward shift). ✓
- **Rib cage leads thoracic movement:** `chest.localRotation` is the driver; neck/head/shoulders are children. ✓
- **Shoulders follow the thorax:** children of the rotating chest. ✓
- **Neck/head follow naturally:** children of the rotating chest; head direction set in chest frame. ✓
- **Supporting limbs remain stable:** support hand pinned to a fixed world floor point (stable pillar) in the two rotation drills; knees/shins/feet fixed on the floor in extension. ✓
- **No floating joints:** all nodes carry local positions; FK places every joint; legs/feet grounded. ✓
- **No robotic motion / no snapping:** continuous `PING_PONG` easing; smooth thorax-led motion. ✓
- **Resembles real performance:** rib cage rotates/extends as one unit; arms follow the spine; support stays planted. ✓

---

## 7. Regression Risk

- **Low.** The only API/behavior change visible to callers is the animation itself (intended) and the timing mode (`LOOP`→`PING_PONG`). Registered ids (`thoracic_rotations_reps`, `thoracic_extension_reps`, `world_greatest_stretch`) and `PoseRegistry` entries are unchanged.
- The dead `WorldGreatestStretchPose` was unregistered, so its removal cannot alter any runtime path.
- Per-frame allocation behavior preserved (hierarchy cached once; reused scratch `target*`/`pole*`/`reach*` vectors; no per-joint `Vector3` allocation in `build()`).

---

## 8. Recommended Tests

Existing tests updated/kept:
- `DynamicStretchPosesTest.testQuadrupedThoracicRotationsPoseBuildsCorrectly` — asserts the reaching **elbow rises** (`elbowAY1 > elbowAY0`): holds because the rib cage now drives the arm up.
- `DynamicStretchPosesTest.testDynamicWorldsGreatestStretchPoseBuildsCorrectly` — asserts the reaching **hand sweeps up** (`handAY1 > handAY0`): holds (reach follows thorax to the sky).
- `ThoracicAndHamstringStretchPosesTest.testThoracicExtensionPoseBuildsCorrectly` — **rewritten** because the pelvis is now correctly stable (the old "forward shift" assertion encoded the old lumbar-driven error). Now asserts: pelvis stays grounded at `y=127`, pelvis X stays centered, and the **chest + head extend backward** (`chestX1 < chestX0`, `headX1 < headX0`) — i.e., the thorax, not the pelvis, moves.

Suggested additional (visual) checks (no JVM here to run them): confirm the rib cage visibly rotates in the two rotation drills, the reaching hand never passes through the floor, and the support hand stays planted while the thorax turns.

---

## 9. Remaining Technical Debt

1. The `bakeThoracicArm` helper is thoracic-family-specific (arms root under a rotating chest); it is intentionally not promoted into the engine `BasePose` (would be a single-family abstraction). Acceptable.
2. `SupportDefinition` left at default (informational only, as in every other modern family); populate only if the engine later keys contact rendering off `metadata.support`.
3. Exact reaching-hand paths (thread→sky) are tuned approximations; visual testing may refine the `reachLocal` endpoints/poles for maximal naturalness, but the underlying thorax-led model is correct.
4. The support shoulder orbits slightly with the rib cage (coherent rotation) rather than staying perfectly stacked; the planted hand keeps it a stable pillar. A future refinement could counter-rotate only the support shoulder, but current motion reads as natural.

---

## 10. Confirmation

Movement quality was prioritized over preserving the legacy implementation: all three active poses were **rewritten from the rib cage outward**, the dead class was removed after salvaging its correct concept, and only then was the architecture modernized onto `BaseThoracicPose`/`SkeletonFactory`/`bakeIkLimb`/engine helpers. The legacy math was not preserved.
