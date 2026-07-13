# Thoracic Mobility Family — Biomechanics-First Audit & Architecture Report

**Scope:** Biomechanics-first audit (primary) + full architecture audit of the Thoracic Mobility pose family.
**Date:** 2026-07-13
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture — same baseline as Push-Up, Squat, Bird Dog, Hip Flexor).
**Method:** Exhaustive source cross-checking against the engine (`SkeletonMath`, `SkeletonNode`, `SkeletonFactory`, `SkeletonPoseFinalizer`, `AnimationController`, `MotionCurves`, `MotionDrivers`, `PoseMetadata`, `CameraDefinition`, `SupportDefinition`, `FootDefinition`, `HandDefinition`), git archaeology of the modernized families, and a per-joint forward-kinematics trace of each pose. The sandbox has no JVM, so the project could not be compiled/run here; correctness is established by construction and by the existing geometry tests, which are preserved by the proposed changes.

> **Unlike the previous four families, the dominant defect here is movement quality, not duplicated code.** The four Thoracic poses are the *least-modernized* files in the entire codebase, and — more importantly — three of them animate the arms/shoulders while the rib cage, neck and head stay essentially static. That is not how thoracic motion works. This report therefore leads with biomechanics (Phase 1), then repair/rewrite (Phase 2), and only addresses architecture (Phase 3+) *after* the movement is judged.

---

## 1. Family Overview

The `ExerciseFamily("thoracic", …)` group contains three **active** exercises plus one **dead** (unregistered) legacy pose that was found in the same source tree.

| Pose class | Real-world exercise | Registered id | In code? | State at audit | Movement verdict |
|---|---|---|---|---|---|
| `QuadrupedThoracicRotationsPose` | Quadruped thoracic rotation (thread + reach to sky) | `thoracic_rotations_reps` | ✅ runs | Manual `ensureHierarchy` + manual `solveIK`/`rotAround`; no base | **Broken model** — rib cage/head do not rotate |
| `ThoracicExtensionPose` | Kneeling thoracic extension (hands behind head) | `thoracic_extension_reps` | ✅ runs | Same manual pattern; no base | **Broken lower body** — knees/shins/feet leave the floor |
| `DynamicWorldsGreatestStretchPose` | World's Greatest Stretch (runner's lunge + rotation) | `world_greatest_stretch` | ✅ runs | Same manual pattern; no base | **Hollow rotation** — torso does not actually turn |
| `WorldGreatestStretchPose` | Static World's Greatest Stretch | *(none — dead)* | ❌ unregistered | Fully position-driven, legacy bridge; better rotation axis than the dynamic twin | Dead code; biomechanical model actually superior to the running one |

None of the four extend a shared base. There is **no `BaseThoracicPose`**. All four hand-roll their own `SkeletonNode` hierarchy, their own IK baking, their own foot/heel/toe literals, and their own hand offsets — duplication that, unlike the other families, has *not* been consolidated.

---

## 2. Phase 1 — Biomechanics (highest priority)

Coordinate convention (verified from `HumanSkeletonDefinition`, `GroundDefinition(level=0f)`, and `SkeletonPoseFinalizer`): **+Y is up**, ground at `y=0`, knees at `y≈15`, ankle height `15`; **+X is forward** (sagittal), **+Z is lateral** (`A`/foreground = `−Z`, `P`/background = `+Z`). The camera default `yaw=1.19` is a near-side view.

### 2.1 Shared thoracic-mobility truth (what "correct" looks like)

For every thoracic drill:
- **Joints that should move:** the thoracic spine (T1–T12) — rotation, extension, or side-bending — and the shoulder girdle *as it rides on top of* that rotation. The cervical spine follows the thorax (the head looks toward where the thorax opens).
- **Joints that should stay relatively stable:** the lumbar spine (kept neutral / minimally involved), the pelvis (level, no rotation in pure thoracic rotation), and the support contacts (planted hand, planted shin, front foot).
- **Rib cage vs pelvis:** the rib cage rotates/extends *relative to* a stable pelvis. The pelvis is the anchor; the thorax is the mover.
- **Shoulders follow thoracic rotation:** both scapulae rotate with the thorax; the *reaching* shoulder travels with the spine while the *supporting* shoulder stays stacked over its planted hand/wrist (the planted hand is the pivot, not a moving target).
- **Head behavior:** the head is the last segment of the chain and rotates *with* the thorax — gaze tracks the opening/reaching arm.
- **Pelvis stability:** neutral and level in rotation drills; only translated/tilted in the kneeling-extension drill (and even then the shins stay flat).
- **Hands/feet:** planted contacts are fixed in world space; moving contacts follow the limb, not the other way around.

### 2.2 Cross-cutting biomechanical defect (all three active poses)

**The rib cage, neck, and head do not participate in the "thoracic" motion.** In `QuadrupedThoracicRotationsPose` and `DynamicWorldsGreatestStretchPose` the `twist` scalar is applied **only** to the shoulder offsets (`sARoll`/`sPRoll`), while `chest` is placed by a fixed `chest.localPosition = (0, def.torsoLength, 0)` and inherits only the pelvis rotation. The chest node's *own* orientation never changes with `twist`, so the rendered rib cage stays facing one direction while the shoulders orbit around it. The head and neck are children of `chest` and are likewise fixed.

Worse, the shoulder offset is rotated **twice**: first about the spine axis `(1,0,0)` by `twist`, then again about `Z` by the torso pitch (`torsoPitch` / `chest.worldRotation.angle`). A pure thoracic rotation is a *single* rotation about the spine's long axis. The extra Z-rotation converts the natural "shoulder lifts up-and-over toward the ceiling" arc into a "shoulder swings forward-and-around" arc — plausible-looking in isolation but it severs the kinematic link between thorax and arm.

Net effect: these animations read as **arms being moved by an external force while the torso watches.** That is not natural human movement, and it is the central finding of this audit.

> **Notable irony:** the *dead* `WorldGreatestStretchPose` rotates its shoulder offsets about the **spine (`lean`) axis** (`rotAround(Vector3(0,0,-1), lean, tw, …)`) and rotates the **head** with the twist — i.e., it uses the *correct* model the running `DynamicWorldsGreatestStretchPose` should have inherited. The live code is a regression from the dead code.

---

## 3. Phase 2 — Repair vs Rewrite (per exercise)

### 3.1 `QuadrupedThoracicRotationsPose` → **REWRITE**
The rib cage/head do not rotate, *both* shoulders (including the supposed planted support side) orbit with `twist`, and the double-axis rotation is anatomically wrong. Repairing would mean re-deriving the entire upper-spine chain as a rigid segment rotating about the spine axis with one hand pinned — which is a rewrite of the motion model, not a tweak. **Rewrite.**

### 3.2 `ThoracicExtensionPose` → **REWRITE (lower body)**
The upper-body extension is recognizable (kneeling, hands behind head, elbows flared, head tilts up). But the lower body is fundamentally wrong: the manual `rotAround(…, -torsoPitch)` leg math lifts the **ankles ~30–50 units off the floor** (knees at `y=15`, ankle world ≈ `y≈48–65` at full extension) and points the **toes toward the ceiling**. In kneeling thoracic extension the shins stay flat, the ankles/toes stay on the floor, and only the pelvis rises/tilts. A repair would have to replace the whole leg/foot scaffold. **Rewrite the lower-body anchoring** (keep the upper-body opener logic).

### 3.3 `DynamicWorldsGreatestStretchPose` → **REWRITE**
The lunge base, planted support hand, and the floor→sky arm sweep are all reasonable. But the thoracic rotation is hollow (see §2.2): the torso does not turn, the head does not follow the reaching hand, and the same double-axis error is present. The reach reads as WGS only because the *hand target* is lerped in world space — the spine is a passenger. Integrating a real about-spine-axis rotation (the dead twin's model) and letting the reaching arm + head follow the thorax is a rewrite of the rotation logic. **Rewrite.**

### 3.4 `WorldGreatestStretchPose` (dead) → **DELETE or SALVAGE**
Unregistered, so it never renders. Its rotation-about-spine-axis model is better than the live dynamic version. Recommendation: **delete the dead class**, but **salvage its rotation approach** when rewriting `DynamicWorldsGreatestStretchPose`.

---

## 4. Per-Exercise Detail

### 4.1 `QuadrupedThoracicRotationsPose` (`thoracic_rotations_reps`)

- **Modernization %:** **~25%** (on the modern `fromHierarchy` render path; sets `PoseMetadata`; but hardcodes `0.29f`/`0.71f` foot ratios instead of `def.foot.heelRatio`/`toeRatio`, and uses none of the `BaseXXXPose` helpers).
- **Architecture usage %:** ~15%.
- **Biomechanics assessment:** Tabletop root is correct (pelvis `(-20,127,0)`, `torsoPitch=-π/2` → horizontal spine along +X, chest ≈ `(100,127,0)`). Twist `lerp(-0.2, 1.4)` is a sensible thoracic-rotation range. **But** the rib cage, neck and head never rotate with `twist`; only the shoulder offsets orbit (double-rotated). The "support" shoulder `P` is also rotated by `twist` while its hand target is pinned to the floor, so the planted pillar wobbles. The moving hand is pinned to the back of the neck while the shoulder orbits, so the elbow sweeps but the hand stays put.
- **Naturalness assessment:** **Not natural.** Looks like the arms are being moved independently of a static torso/head. A clinician would not recognize this as a thoracic rotation.
- **Repair vs rewrite:** **REWRITE** (see §3.1).
- **Timing assessment:** `2.5s, LOOP, EASE_IN_OUT`. Critical finding: in `AnimationController`, non-alternating `LOOP` passes **raw** `progress` (`controller.progress = p`) and **ignores `motionCurve`** — so `EASE_IN_OUT` is currently a no-op and the motion is a linear reverse tween with a velocity kink at the apex (the "snap" the Hip Flexor audit also flagged). 2.5 s is also a touch fast for a controlled thoracic rotation. → recommend **3.0 s, `PING_PONG`** (smooth `FastOutSlowInEasing` at both turnarounds; `EASE_IN_OUT` becomes moot, matching the Hip Flexor/Bird-Dog pattern). The existing `DynamicStretchPosesTest` (`elbowAY1 > elbowAY0`) still holds at `progress=1`.
- **Camera assessment:** `defaultPitch=0.22`, `defaultZoom=1.2`. Tabletop keeps the body low (`y≈127`); pitch `0.22` is acceptable but raising to **~0.24 (+9%)** keeps the shoulders/neck clearly framed as they rotate. Leave zoom.
- **Remaining technical debt:** no `BaseThoracicPose`; duplicated `ensureHierarchy`; manual IK baking; hardcoded foot ratios; 6/6/10 hand offsets; dead `EASE_IN_OUT`.
- **Regression risk:** **High if left as-is** (movement is wrong). After a correct rewrite + `PING_PONG`, risk is low and the geometry tests remain satisfied.

### 4.2 `ThoracicExtensionPose` (`thoracic_extension_reps`)

- **Modernization %:** **~25%** (same profile as §4.1).
- **Architecture usage %:** ~15%.
- **Biomechanics assessment:** Tall-kneeling root is reasonable (thigh `pitch 0→0.2`, pelvis `(0,127)→(22,125)`). Thoracic **extension** direction is correct: `torsoPitch 0→-0.45` arches the chest up/back, head tilts further up (`headTilt 0→-0.3`), hands behind head with elbows flared by pole `(0,-1,∓2)` — a recognizable kneeling chest-opener. **Fatal lower-body error:** the manual `rotAround(…, -torsoPitch)` leg math floats the ankles ~30–50 units above the floor and points the toes skyward. Shins must stay flat; ankles/toes must stay on the floor. The pelvis-tilt also drives the *whole* spine (lumbar included) into extension, whereas a thoracic drill should keep the lumbar relatively neutral (posterior pelvic tilt to protect it) — here both extend together.
- **Naturalness assessment:** **Not natural** once the legs are seen — floating, sky-pointing feet in a "kneeling" pose. The torso alone would pass; the lower body fails.
- **Repair vs rewrite:** **REWRITE** (lower body; see §3.2).
- **Timing assessment:** `3.0s, LOOP, EASE_IN_OUT`. Same `LOOP`-ignores-`MotionCurve` issue. A slow controlled extension benefits from `PING_PONG` smoothing. → recommend **3.0–3.5 s, `PING_PONG`**. The `ThoracicAndHamstringStretchPosesTest` assertion (`pelvisX1 > pelvisX0`) still holds at `progress=1`.
- **Camera assessment:** `defaultPitch=0.22`, `defaultZoom=1.3`. The head rises to **`y≈267`** at full extension (chest ≈ `(-30,233)`, head ≈ `(-19,267)`). Pitch `0.22` will clip/crowd the head. Recommend **`defaultPitch 0.22 → ~0.26 (+18%)`** and a slight zoom-out to **`1.35`** so the head/neck/upper thorax stay framed. Not excessive.
- **Remaining technical debt:** broken kneeling-leg scaffold; hardcoded foot ratios; no base; 6/6/10 hand offsets.
- **Regression risk:** **High as-is** (feet off floor). Low after rewrite + `PING_PONG`, tests preserved.

### 4.3 `DynamicWorldsGreatestStretchPose` (`world_greatest_stretch`)

- **Modernization %:** **~25%**.
- **Architecture usage %:** ~15%.
- **Biomechanics assessment:** Runner's-lunge root is good: pelvis `(-10,55,0)`, `leanAngle=0.5` forward lean, front ankle flat (`(65,25,-hw)`), back foot on toes (`(-110,40,hw)`, `footPitchB=0.6`), support hand planted `(55,0,sw)`. The dynamic arm sweeps from low-instep `(50,15,-1.5sw)` to sky `(chestX+20, chestY+110, -2sw)` — recognizable WGS reach. **But the thoracic rotation is hollow** (§2.2): `twist lerp(-0.2,1.6)` is applied only to the double-rotated shoulder offsets; the chest/rib cage orientation never changes and the **head does not follow the reaching hand**. The visible "rotation" is an artifact of the world-space hand-target lerp, not of the spine.
- **Naturalness assessment:** **Borderline.** The arm sweep reads as WGS, but the body is not actually rotating — the head stays put while the hand goes to the sky, which a viewer will read as wrong.
- **Repair vs rewrite:** **REWRITE** (rotation logic; see §3.3). Salvage the dead twin's about-spine-axis rotation.
- **Timing assessment:** `3.5s, LOOP, EASE_IN_OUT`. Same `LOOP`/no-curve issue. The sweep is large and should be smooth and continuous. → recommend **3.5–4.0 s, `PING_PONG`**. `DynamicStretchPosesTest` (`handAY1 > handAY0`) still holds at `progress=1`.
- **Camera assessment:** `defaultPitch=0.22`, `defaultZoom=1.1`. `1.1` is the **most zoomed-in** of the three (higher `defaultZoom` = more zoomed-out per the Bird-Dog audit), and the lunge is the widest pose (`x` spans ≈ `-110` back foot to `≈+120` front/chest+hand). Risk of cropping extremities. Recommend **`defaultZoom 1.1 → ~1.25`** (zoom out) and pitch **`0.22 → ~0.24`**. Do not over-zoom.
- **Remaining technical debt:** hollow rotation; dead `EASE_IN_OUT`; hardcoded foot ratios; no base; duplicated scaffold.
- **Regression risk:** **Medium as-is** (recognizable but biomechanically hollow). Low after rewrite + `PING_PONG`, tests preserved.

### 4.4 `WorldGreatestStretchPose` (dead / unregistered)

- **Modernization %:** **~8%** (fully position-driven `jointsBuffer.setJoint(...)`; no hierarchy → takes the legacy bridge in `SkeletonPoseFinalizer`, which rebuilds the hierarchy from world joints and recomputes hand/foot detail — so only the *main* joints are preserved).
- **Architecture usage %:** ~5%.
- **Biomechanics assessment:** Despite being legacy, its rotation model is the **most correct in the family** — shoulder offsets rotate about the **spine (`lean`) axis** and the **head follows** the twist (`headDir` includes `u*s` twist terms). The leg/lunge geometry is hand-tuned with magic numbers (`140`, `170`, `−195`, …) and coarse, but the *rotation concept* is right.
- **Naturalness assessment:** N/A — never rendered.
- **Repair vs rewrite:** **DELETE the class**, but **reuse its rotation-about-spine-axis math** when rewriting `DynamicWorldsGreatestStretchPose`.
- **Timing assessment:** `6.0s, HOLD, EASE_IN_OUT` — irrelevant (dead).
- **Camera assessment:** `defaultPitch=0.22, defaultZoom=1.3` — irrelevant.
- **Remaining technical debt:** entire file is dead code competing with the live dynamic version.
- **Regression risk:** **None** (unregistered; removal cannot change any runtime path).

---

## 5. Phase 3 — Architecture

**The family has no `BaseXXXPose`.** All four poses duplicate: the `ensureHierarchy()` `SkeletonNode` construction, the manual `solveIK`+`rotAround` IK baking, the foot `0.29f`/`0.71f` literals, the `6f`/`6f`/`10f` hand offsets, and the camera literal `(1.19f, 0.22f, …)`.

### 5.1 Engine Component Usage Table

| Engine component | Quadruped | Extension | DynamicWGS | StaticWGS | Notes |
|---|---|---|---|---|---|
| `BasePose` | ❌ | ❌ | ❌ | ❌ | none extend it |
| `BaseXXXPose` (family base) | ❌ | ❌ | ❌ | ❌ | **missing — should be `BaseThoracicPose`** |
| `SkeletonFactory` | ❌ (manual) | ❌ (manual) | ❌ (manual) | ❌ (manual) | all build nodes by hand |
| `SkeletonPoseFinalizer` (modern path) | ✅ (roots set) | ✅ (roots set) | ✅ (roots set) | ➖ (legacy bridge) | active 3 set `roots` via `fromHierarchy` |
| `PoseMetadata` | ✅ | ✅ | ✅ | ✅ | but `motionCurve` is a no-op under `LOOP` |
| `MotionCurve` | ⚠️ | ⚠️ | ⚠️ | ⚠️ | **ignored** by non-alternating `LOOP` (raw progress) |
| `MotionDrivers` | ❌ | ❌ | ❌ | ❌ | no driver used anywhere in the family |
| `SupportDefinition` | ❌ | ❌ | ❌ | ❌ | none declared (default `FEET`) |
| `FootDefinition` | ⚠️ | ⚠️ | ⚠️ | ❌ | ratios **hardcoded as `0.29/0.71` literals**, not `def.foot.heelRatio`/`toeRatio` |
| `HandDefinition` | ⚠️ | ⚠️ | ⚠️ | ❌ | hand offsets `6/6/10` are local literals (family convention, same as other families) |
| `bakeIkLimb()` | ❌ | ❌ | ❌ | ❌ | every pose hand-rolls `solveIK`+`rotAround` baking |
| `buildHead()` | ❌ | ❌ | ❌ | ❌ | head math inlined |
| `buildPelvis()` | ❌ | ❌ | ❌ | ❌ | hip offsets inlined |
| `buildShoulders()` | ❌ | ❌ | ❌ | ❌ | shoulder offsets inlined |
| `EnvironmentDefinition` | ✅ (ground) | ✅ (ground) | ✅ (ground) | ❌ | only a ground plane; fine |
| `CameraDefinition` | ✅ (literal) | ✅ (literal) | ✅ (literal) | ✅ (literal) | **duplicated literal** across all four; should be shared |

### 5.2 Architecture recommendation (only after the movement is fixed)

Once the motion is rewritten per §3, introduce a single **`BaseThoracicPose`** (a `BaseXXXPose`, used by ≥3 exercises — satisfies the "no abstraction for one exercise" rule) that owns:
- the hierarchy via `SkeletonFactory.createStandardSkeleton()`,
- `buildHead` / `buildPelvis` / `buildShoulders`,
- limb IK via `bakeIkLimb()` (arms + legs),
- the shared `thoracicCamera` literal (raise pitch per §4),
- `FootDefinition.heelRatio`/`toeRatio` (never the `0.29/0.71` literals),
- the family `6/6/10` hand-offset convention (consistent with Push-Up/Squat/Bird-Dog/Hip-Flexor).

**Do NOT** build a `ThoracicGeometrySolver` — per-exercise choreography (quadruped thread, kneeling extension, lunge rotation) is thoracic biomechanics and stays local, exactly as Hip Flexor kept its Pythagorean pelvis solver local.

---

## 6. Phase 4 — Camera

| Pose | Current | Recommend | Why |
|---|---|---|---|
| `QuadrupedThoracicRotations` | pitch 0.22, zoom 1.2 | pitch **0.24** (+9%), zoom 1.2 | keep shoulders/neck framed during rotation; body is low |
| `ThoracicExtension` | pitch 0.22, zoom 1.3 | pitch **0.26** (+18%), zoom **1.35** | head rises to `y≈267`; must stay framed |
| `DynamicWGS` | pitch 0.22, zoom 1.1 | pitch **0.24**, zoom **1.25** | widest pose; `1.1` is most zoomed-in → risk cropping extremities |
| `StaticWGS` (dead) | — | n/a | delete |

All changes are framing-only (pitch tilt / zoom), no yaw change, no redesign — consistent with the Hip Flexor (+10% pitch) and Bird Dog (+10% zoom) audits.

## 7. Phase 5 — Timing

| Pose | Before | After | Biomechanical justification |
|---|---|---|---|
| `QuadrupedThoracicRotations` | 2.5 s, `LOOP`, `EASE_IN_OUT` | **3.0 s, `PING_PONG`** | `LOOP` ignores the curve and kinks at the apex; thoracic rotation must be slow/controlled; `PING_PONG` adds `FastOutSlowInEasing` at both turnarounds |
| `ThoracicExtension` | 3.0 s, `LOOP`, `EASE_IN_OUT` | **3.0–3.5 s, `PING_PONG`** | extension is the slowest drill; smooth in/out, no snap |
| `DynamicWGS` | 3.5 s, `LOOP`, `EASE_IN_OUT` | **3.5–4.0 s, `PING_PONG`** | large floor→sky sweep needs time and a smooth apex |
| `StaticWGS` (dead) | 6.0 s, `HOLD` | n/a | delete |

Note: `MotionCurve` is currently a **no-op** for all three active poses because `AnimationController`'s non-alternating `LOOP` branch assigns raw `progress` and never calls `MotionCurves.transform`. Switching to `PING_PONG` both removes the apex snap *and* makes the "ease in/out" intent actually take effect (`PING_PONG` uses `FastOutSlowInEasing`). No change is arbitrary — each is justified by the need for slow, continuous, controlled thoracic motion.

---

## 8. Family Summary

- **Modernization %:** **~25%** (active poses) / **~8%** (dead static). By far the least-modernized family in the codebase (Push-Up/Squat/Bird-Dog/Hip-Flexor all sit at ~97%).
- **Architecture %:** **~15%** — no family base, fully duplicated scaffolding, hardcoded foot ratios, dead `MotionCurve`.
- **Primary defect:** **biomechanical**, not architectural — the rib cage/neck/head do not rotate in the two rotation drills, and the kneeling pose floats its feet. The architecture gap is real but secondary.

**Remaining technical debt:**
1. No `BaseThoracicPose` (introduce after rewrite).
2. Hardcoded `0.29/0.71` foot ratios → use `def.foot.heelRatio`/`toeRatio`.
3. Dead `WorldGreatestStretchPose` competing with the live dynamic version — delete, salvage its rotation math.
4. `MotionCurve` is silently ignored under non-alternating `LOOP` — migrate to `PING_PONG` (done in this recommendation) so easing is real.
5. `SupportDefinition` left at default (`FEET`); if the engine later keys contact rendering off `metadata.support`, declare `KNEES`+`HANDS`/`TOES` contacts per pose.

**Recommended next work (in order):**
1. Rewrite `QuadrupedThoracicRotationsPose` and `DynamicWorldsGreatestStretchPose` with a real about-spine-axis thoracic rotation (rib cage + neck + head rotate as one chain; one hand pinned as pivot).
2. Rewrite `ThoracicExtensionPose` lower body so shins/ankles/toes stay on the floor.
3. Introduce `BaseThoracicPose`; migrate the three active poses onto it (`SkeletonFactory`, `bakeIkLimb`, `buildHead`/`buildPelvis`/`buildShoulders`, shared camera, `FootDefinition` ratios).
4. Delete `WorldGreatestStretchPose`.
5. Apply the §6/§7 camera + timing changes.

**Constraints preserved by the recommended changes:** no new runtime allocations (hierarchy built once, per-frame math reuses class-level scratch); no speculative abstractions (only the justified `BaseThoracicPose`, used by ≥3 exercises); no single-exercise solver; existing engine components preferred; existing geometry tests (`ThoracicAndHamstringStretchPosesTest`, `DynamicStretchPosesTest`) remain satisfied at `progress=0/1`.

---

## 9. Validation & Constraints Check

- ✅ **Biomechanics is the lead finding** — rib cage/head non-rotation (§2.2) and floating feet (§4.2) are the dominant defects, confirmed by forward-kinematics trace.
- ✅ **Repair vs rewrite decided per exercise** with rationale (§3) — all three active poses are **REWRITE** because the motion model is fundamentally wrong, not cosmetically off.
- ⚠️ **Architecture gap identified but explicitly secondary** to movement (Phase 3 only after Phase 1/2, per instruction).
- ✅ **Camera raised only as needed**, framing-only, no yaw/redesign (§6).
- ✅ **Timing changed only with biomechanical justification**, `LOOP`→`PING_PONG` to fix the ignored-curve apex snap (§7).
- ✅ **No new abstractions beyond the needed `BaseThoracicPose`**; per-exercise choreography kept local.
- ⚠️ **No compile/run in sandbox** (no JVM) — findings established by source cross-check + FK trace + preserved tests, matching the Bird-Dog/Hip-Flexor audit environment.

*(End of report)*
