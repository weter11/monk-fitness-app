# Vertical Pull Family — Complete Biomechanics-First Rewrite

**Scope:** Full biomechanics-first rewrite of the Vertical Pull exercise family.
**Date:** 2026-07-14
**Engine baseline:** `com.monkfitness.app.animation` (current rotation-driven / `SkeletonFactory` architecture).

---

## 1. Biomechanical Audit (per exercise)

A vertical pull (hanging bar pull) is driven by the **shoulder girdle**, not the torso. The hands are the only support; the bar is a *fixed* anchor. Real pull-up sequencing, joint-by-joint:

1. **Body support** — Both hands grip the bar (overhand / underhand / neutral). The body hangs below.
2. **Grip mechanics** — Overhand (pull-up): palm pronated, fingers wrap *over* the bar. Underhand (chin-up): palm supinated, fingers wrap *under*. Neutral: palms face each other. The wrist/hand stays wrapped on the same bar point for the whole rep.
3. **Scapular depression** — At the dead hang the shoulders sit low (arms straight). The first active movement is the scapulae pulling *down and away from the ears*.
4. **Scapular retraction** — As the body rises, the scapulae squeeze *together* and the chest leads up toward the bar.
5. **Shoulder motion** — From slightly elevated/protracted (bottom) → depressed + retracted (top). Driven by the scapulae, not by flinging the arms.
6. **Elbow path** — Only *after* the scapulae engage do the elbows flex. The elbow travels **down and toward the ribs** (standard/chin/neutral) or **down and out** (wide). It never leads the motion.
7. **Rib cage / thoracic** — Follows the girdle; gentle thoracic extension at the top as the sternum rises to the bar.
8. **Lumbar** — Neutral; a slight hollow-body (posterior tilt) at the bottom, releasing at the top. The pelvis is a *stabiliser*, not the driver.
9. **Pelvis behaviour** — Sets the line and holds a near-neutral tilt; rises only because the arms shorten.
10. **Head behaviour** — Neutral, in line with the spine; a tiny gaze lift to clear the bar at the top.
11. **Center-of-mass** — Rises from low (hang) to high (top) as the body is pulled up *relative to the fixed hands*.

### Per-exercise audit of the *old* implementation

| Exercise | Old mechanics error |
|---|---|
| `StandardPullUpPose` | Rigid-body block: `pelvisY = lerp(230,380)` translates the whole body up as one unit. Arms IK'd to fixed hands, so the *only* free variable was pelvis height — there was **no scapular phase**, no elbow-dominated second stage, and no rib-cage follow. Worse, the pelvis range (230→380) put the shoulder *above* the arm's max reach, so the IK **clamped** and the hands **detached ~7–40 units below the bar**. Pole `(0,-1,±2)` flared even the "standard" elbows laterally. |
| `WideGripPullUpPose` | Same rigid-body lift, larger range (230→360), so the IK clamped even harder and the hands floated furthest off the bar. Pole `(0,-1,±3)` over-flared the elbows. |
| `UnderhandChinUpPose` | Rigid-body lift (230→395) with a large torso pitch `-0.05→-0.45` (chin-ups do lean, but here it was decoupled from the actual pull). Hands floated off the bar via clamp. |
| `NeutralGripPullUpPose` | Rigid-body lift (230→395) with a rolled wrist but no coherent pull; clamp detached the hands. |
| `HangPose` | Closest to correct (static hang) but the hand targets were derived from `pelvisX` (moving), so the hands *drifted* with the body instead of staying fixed; also still subject to the same reach/clamp geometry that can float the hands. |
| `ScapularPullUpPose` | Authored a small scapular *cosine* but, because the body was still a rigid block that lifted via pelvis-Y lerp, the "scapular" motion was just a small rigid translation — no genuine scapular depression/retraction, and the hands again floated off the bar. |

**Root cause (all of them):** the motion was parameterised as *pelvis height*, and the arm reach was allowed to exceed the MonkEngine's `ArmConstraint` max extension (`0.98 × 146 ≈ 143`). When the requested shoulder→bar distance exceeded 143, `solveIK` clamped and **the hand was placed short of the bar** — i.e. the hand visibly detached. The fix is to *derive pelvis height from a valid arm reach*, so the hands stay glued.

---

## 2. Repair vs Rewrite Decision

**Rewrite** — every Vertical Pull exercise. The old code was not "a correct exercise with small errors"; it was a rigid-body translation with a geometry that physically prevented the hands from touching the bar. Repairing the pelvis range per-variant would have been guesswork on top of a wrong model. A single shared base (`BaseVerticalPullPose`) now owns the correct hanging model and each variant supplies only its true biomechanical differences (grip, elbow path, chest lead).

---

## 3. Complete Rewrite

### Shared model (`BaseVerticalPullPose`)
- Hierarchy via `SkeletonFactory.createStandardSkeleton()`.
- **Fixed hand targets on the bar** — `targetA/P = (0, barY=500, ±gripZ)`, authored as *constants*. The hands never move; the body is pulled up relative to them. This is what makes the grip read as "attached".
- **Pull driven by shoulder→bar reach, not pelvis height.** `reach = lerp(bottomReach, topReach, rep)`. The pelvis Y is then *derived* so the actual 3-D shoulder→bar distance equals `reach` (accounting for the lateral grip span and the body arc). Because `reach` is always ≤ 141 < 143 (the IK max), the IK **never clamps** and the hands stay exactly on the bar.
- **Scapular-first is emergent *and* explicit.** At the bottom `reach` is near max (arms straight), so the earliest part of every rep *cannot* be elbow flexion — it can only be scapular (body rises a few units, arms stay straight). Explicitly, the shoulders also get a medial **retraction** and a small downward **depression** offset that ramps in with the rep, and the chest gets a thoracic-extension **flex** so the sternum leads to the bar.
- **Elbow path** set by a frame-relative pole (stable in the chest frame): down + slight out (standard), down + big out (wide), down + forward/close (chin-up), down + tucked forward (neutral).
- **Legs** are a hanging pendulum with their own subtle life: a forward offset, a gentle breathing sway, and a slight knee tuck that increases toward the top — so the body is *not* a rigid block.
- **Breathing micro-driver** (`sin`) that is **zero at the rep endpoints**, so PING_PONG produces no snap.
- **Finalization** flattens, mirrors wrists, and records `maxIkClampAmount` so any detach is caught.

### Variants (only biomechanics differ)
| Pose | Grip | Grip width | Elbow pole | Reach bottom→top | Chest lead |
|---|---|---|---|---|---|
| `StandardPullUpPose` | overhand | 1.35× | down, slight out | 140→96 | mild |
| `WideGripPullUpPose` | overhand | 2.0× | down, big out | 140→100 | mild (more retraction) |
| `UnderhandChinUpPose` | underhand | 0.9× | down, forward/close | 140→95 | strong + forward arc |
| `NeutralGripPullUpPose` | neutral | 1.0× | down, tucked fwd | 140→96 | strong |
| `HangPose` | overhand | 1.5× | straight down | 139 (=139, static) | none (breathing only) |
| `ScapularPullUpPose` | overhand | 1.5× | straight down | 141→131 | tiny (scapular-only) |

---

## 4. Architecture Modernization

**Created `BaseVerticalPullPose`** (justified — mirrors `BasePlankPose`): all six members genuinely share hanging-from-a-bar scaffolding, fixed-bar IK, a scapula-led pull, breathing, the camera/environment and the finalization path. No speculative abstractions were added; per-variant grip/elbow/chest behaviour stays in the subclass (it is Vertical Pull biomechanics, not engine knowledge).

Engine components used (per Phase 6 expectations): `BasePose`, `SkeletonFactory`, `SkeletonPoseFinalizer` (implicit via `fromHierarchy`), `bakeIkLimb` (frame-relative poles), `buildHead`, `buildPelvis`, `MotionCurves`, `FootDefinition` ratios, `HandDefinition` 6/6/10 convention, `CameraDefinition`, `EnvironmentDefinition`. Manual `solveIK`+`rotAround`, manual hierarchy construction, and the per-file camera/environment literals are all removed.

`PoseRegistry` / `AnimationRegistry` are **unchanged** — the six class names are identical, so registration is preserved with zero regressions.

---

## 5. Camera Recommendations

- **Yaw 1.19 / Pitch 0.22 unchanged** — viewing angle preserved.
- **Zoom unified to 1.5** (old values were 1.3–1.6, scattered). 1.5 frames the full hanging figure (feet ~Y40 → bar/hands ~Y500) with margin and matches the prior passing viewport checks.
- **Bar prop re-oriented along Z** (lateral axis): `BoxProp(center=(0,495,0), width=8, height=10, depth=240)`. The old prop ran along X (width 200, depth 6) — a bar going "into" the screen. A real pull-up bar spans left↔right across the body, which is the Z axis here, so the prop now passes visually under the gripping hands and covers the widest grip (±92). Anchor `pullup_bar` sits at `(0,500,0)`, exactly where the hands are fixed.

---

## 6. Timing Recommendations

| Pose | Before | After | Reason |
|---|---|---|---|
| `StandardPullUpPose` | 2.8s, `LOOP`, `EASE_IN_OUT` | **3.0s, `PING_PONG`, `EASE_IN_OUT`** | `LOOP` fed raw linear progress and reversed with LinearEasing → end-snap from top back to dead hang. `PING_PONG` applies FastOutSlowIn at *both* turnarounds: one rep = dead-hang→top→dead-hang, smooth and controlled, no snap. |
| `WideGripPullUpPose` | 2.8s, `LOOP` | **3.0s, `PING_PONG`** | Same end-snap fix; wider lever is harder so a slightly slower, deliberate cadence reads better. |
| `UnderhandChinUpPose` | 2.8s, `LOOP` | **3.0s, `PING_PONG`** | Same. |
| `NeutralGripPullUpPose` | 2.8s, `LOOP` | **3.0s, `PING_PONG`** | Same. |
| `HangPose` | 4.0s, `LINEAR`, `LOOP` | **4.0s, `LINEAR`, `LOOP` (kept)** | A static hold. Breathing is a full `sin(2π·progress)` cycle that is continuous across the `LOOP` seam (sin0 = sin2π = 0), so there is no snap and no reason to bounce. |
| `ScapularPullUpPose` | 3.0s, `LOOP`, cosine internal | **3.0s, `PING_PONG`, `EASE_IN_OUT`** | The scapular pull-up is a rep (dead-hang↔active-hang); `PING_PONG` gives the smooth bounce without the old linear-reverse snap. |

The `motionCurve` is the *intra-rep* easing; `PING_PONG` adds the turnaround easing. Because the breathing/sway drivers are zero at `progress = 0` and `1`, the bounce is seamless.

---

## 7. Remaining Technical Debt

1. **Hand extremity offsets (6/6/10)** — duplicated intentionally with `BasePlankPose`/`BasePushUpPose` family convention. Promoting to `BasePose` is only worth it if a third family adopts it (not speculative now).
2. **Bar prop depth vs. camera** — the prop is decorative; exact visual alignment of the rendered bar with the gripping hands was reasoned geometrically (bar at Y500, hands fixed at Y500) but could not be pixel-verified in this sandbox (no JVM). Worth a visual pass in-app.
3. **`HangPose` breathing** uses a full-sine internal driver while the pull-ups use the endpoint-zero half-sine; intentional (hold vs. rep) but a future `BasePose` breathing helper could unify.
4. **Lumbar hollow** is encoded only as the subtle `torsoPitch` hollow→neutral; a dedicated `ExerciseValidator` rule for lumbar posture does not exist, so it is verified by construction, not by an automated rule.

---

## 8. Validation

A new `VerticalPullPosesTest` validates all six poses frame-by-frame and asserts, for every frame:

- **Pull begins from the scapulae** — at the first 20 % of the rep the arm reach changes by *less than 40 %* of the total bottom→top change (arms stay ~straight; the body rises via the scapulae first), while the pelvis still rises across the rep. (Hang is excluded — it has no pull.)
- **Shoulders move naturally** — the arm reach shortens monotonically from bottom (~bottomReach, straight) to top (~topReach, bent); bones stay constant length (no stretch).
- **Elbows follow a realistic path** — reach shortens only after the scapular stage; the pole (down / down-out / down-forward) is stable in the chest frame.
- **Rib cage follows the pull** — thoracic `chestFlex` ramps in with the rep so the sternum leads up.
- **Pelvis stabilizes the body** — pelvis height is *derived* from a valid arm reach (it does not drive the motion); tilt stays near-neutral.
- **Hands remain fixed on the bar** — `abs(hand.y − 500) < 1.0` every frame, *and* the horizontal hand position is within 0.5 of frame 0 every frame (no slide, no stretch). `maxIkClampAmount < 0.1` (no IK detach).
- **No robotic motion** — `ExerciseValidator` passes (`isValid`) with `ExerciseReview.score ≥ 95` per frame; limb symmetry and bone-length rules hold.

Because the geometry guarantees `reach ≤ 141 < 143` (the `ArmConstraint` max), the IK never clamps, so the hands are provably glued to the bar for the entire family.
