# W1 — Restore Engine Ownership of Extremity Orientation

> Scope: **W1 only** from `docs/ENGINE_RESPONSIBILITY_AUDIT_NEW.md` (the "Engine Workarounds
> Audit"). The goal is architectural — restore the ownership boundary *"pose authors describe
> anatomy, the engine derives extremity geometry"* — **not** to improve any pose.
>
> No validation pose was modified. No production pose was modified. No constants were tuned. IK,
> the solver, and the validator rules are untouched.

---

## 1. Previous ownership model (the bug)

The engine already contained the correct anatomy-solving machinery in
`SkeletonPoseFinalizer`:

- `adjustFootOrientation(...)` — derives heel/toe from the shank (knee→ankle) direction, the
  ankle articulation *relative to the shank* (so inherited torso tilt is removed automatically),
  and `FootDefinition` ratios.
- `adjustHandOrientation(...)` — derives palm/knuckles/fingertips from the forearm (elbow→hand)
  direction, the wrist articulation *relative to the forearm*, and `HandDefinition` offsets.

But these were gated on **node existence**:

```kotlin
// SkeletonPoseFinalizer (old)
cachedHasHeelToeF = containsJoint(roots, HEEL_F) && containsJoint(roots, TOE_F)   // ...palm/hand too
...
if (!cachedHasHeelToeF) { adjustFootOrientation(...) }     // derive ONLY if node missing
if (!cachedHasHandDetailA) { adjustHandOrientation(...) }  // derive ONLY if node missing
```

`SkeletonFactory.createStandardSkeleton()` / `createPushUpSkeleton()` **always** create
`HEEL_F/TOE_F/PALM_A/KNUCKLES_A/FINGERTIPS_A` (and the mirror side) as children of every
ankle/hand. So the presence check was **always true** and the engine's derivation **never ran**
for any real pose.

**Ownership was therefore inferred from node existence — a signal that is always "present",
so ownership silently and permanently defaulted to the pose.** Every pose had to author the
endpoint local positions by hand and cancel inherited torso tilt by hand (the W2/W3/W4 smells:
magic `0.29/0.71` heel/toe ratios, `6/6/10` hand offsets, `-parentRotation.angle` / `-torsoPitch`
counter-rotations). The engine owned the *code* but never the *behaviour*.

```
Pose ──(anatomy + heel/toe + palm/fingertips + counter-rotations)──▶ Engine (derivation skipped) ──▶ Skeleton
        └──────────────── pose does the engine's job ─────────────┘
```

---

## 2. New ownership model (explicit, not inferred)

Ownership of each extremity's heel/toe or palm/fingertip geometry is now an **explicit property
of the pose**, defaulting to *engine-derived*.

### Data model — `PoseDefinition.kt`

```kotlin
enum class Extremity { FOOT_F, FOOT_B, HAND_A, HAND_P }

enum class ExtremityOrientationMode {
    AUTOMATIC,        // engine derives geometry from the limb + ankle/wrist articulation (DEFAULT)
    MANUAL_OVERRIDE   // pose authored the endpoints; engine preserves them verbatim
}
```

`SkeletonPose` carries a per-extremity mode array, **defaulting every extremity to
`AUTOMATIC`**, plus an explicit opt-in and read API:

```kotlin
fun getExtremityOrientationMode(e): ExtremityOrientationMode      // default AUTOMATIC
fun isExtremityAutomatic(e): Boolean                              // true unless overridden
fun overrideExtremityOrientation(e)                              // pose opts a single extremity out
```

`copyFrom` propagates the modes so the finalizer's working buffer honours an authored override.

### Derivation gate — `SkeletonPoseFinalizer.kt`

The node-existence machinery (`containsJoint`, `containsJointNode`,
`refreshJointPresenceCache`, and the four `cachedHas*` fields) was **deleted**. The gate now
reads the explicit ownership declaration:

```kotlin
if (pose.isExtremityAutomatic(Extremity.FOOT_F)) { adjustFootOrientation(...) }
if (pose.isExtremityAutomatic(Extremity.FOOT_B)) { adjustFootOrientation(...) }
if (pose.isExtremityAutomatic(Extremity.HAND_A)) { adjustHandOrientation(...) }
if (pose.isExtremityAutomatic(Extremity.HAND_P)) { adjustHandOrientation(...) }
```

The relative ankle/wrist rotation is still passed in, so inherited limb/torso tilt is removed
inside the engine; identity articulation lays the foot/hand flat along the limb.

### Authoring surface — `BasePose.kt` / `BaseValidationPose.kt`

A single documented opt-in helper is added to both pose base classes (mirroring the existing
`buildAnkleArticulation`/`buildWristArticulation` intent helpers):

```kotlin
protected fun overrideExtremityOrientation(pose: SkeletonPose, extremity: Extremity)
```

It is **not called by any existing pose** — validation and production poses are left untouched,
so they use the default automatic derivation. It exists so a *future* stylized pose (pointed toe,
curled grip) can explicitly opt out.

```
Pose ──(anatomy: limb target + ankle/wrist articulation)──▶ Engine (derives heel/toe/palm/fingertip) ──▶ Skeleton
     └─ (optional) overrideExtremityOrientation(e) ─▶ Engine preserves authored endpoints
```

---

## 3. Why Rule #2 is now satisfied

> **Rule #2:** *Poses describe anatomy. The engine derives geometry.*

- **Extremity geometry is now derived by the engine by default.** The four
  `adjustFootOrientation`/`adjustHandOrientation` calls run for every pose unless it explicitly
  declares `MANUAL_OVERRIDE`. The engine's own machinery is finally the live code path, not dead
  code behind an always-true gate.
- **Ownership is explicit, never inferred from structure.** The bug was that node existence — a
  structural artefact the factory always produces — was used as the ownership signal. Ownership
  is now a first-class, intentional declaration on the pose (`ExtremityOrientationMode`), so the
  engine can never again be silently switched off by the factory building a node.
- **Inherited torso tilt is removed by the engine.** Because derivation consumes the ankle/wrist
  rotation *relative to the parent segment* (`relativeRotation(...)`), the engine — not the pose —
  performs the tilt compensation that poses previously re-implemented by hand (`-torsoPitch`,
  `-parentRotation.angle`). The relative-rotation path was already correct; W1 makes it actually
  execute.
- **The pose→engine boundary is the default, the override is the exception.** This is the exact
  inversion the audit's §6 recommendation asked for: *"Author endpoints become an optional
  override, not the default path."*

---

## 4. Why future poses no longer need manual heel/toe or palm/fingertip compensation

A new pose now describes only anatomical **intent**:

```kotlin
buildHipOrientation(hipF, flexion, abduction, rotation, sideSign)   // where the leg points
buildAnkleArticulation(ankleF, dorsiflexion = 0f, inversion = 0f)   // net ankle intent (flat)
bakeIkLimb(hipF, target = footTarget, ...)                          // where the foot goes
// NO heelF/toeF.localPosition writes
// NO palmA/knucklesA/fingertipsA.localPosition writes
// NO ankleF.localRotation.set(axisZ, -torsoPitch) counter-rotation
```

The engine then, unconditionally:

1. **Derives heel/toe** from the shank direction + the *net* ankle articulation + `FootDefinition`
   ratios (`adjustFootOrientation` → `FootDefinition.computeHeelToe`). No pose needs the magic
   `0.29/0.71` literals — they live in `FootDefinition`.
2. **Derives palm/knuckles/fingertips** from the forearm direction + the *net* wrist articulation
   + `HandDefinition` offsets (`adjustHandOrientation` → `HandDefinition.computeHandJoints`). No
   pose needs the `6/6/10` literals.
3. **Cancels inherited parent tilt automatically** via the relative-rotation resolution, so the
   author sets only the *net* desired articulation and never `-parentRotation.angle` /
   `-torsoPitch`.

Because the ratios/offsets and the tilt math are owned by the engine, future poses cannot drift
from the anatomical truth by copying a magic literal, and the engine-internal knowledge
(*"why does the foot inherit the torso tilt and how do I undo it"*) no longer leaks into content.
This is what makes the model scale to hundreds of exercises (audit §5).

A genuinely stylized extremity (pointed toe, curled fist) that the default derivation cannot
express is the *only* legitimate reason to author endpoints, and it now does so **explicitly** via
`overrideExtremityOrientation(pose, extremity)` — an intentional, greppable opt-out instead of an
accidental, universal bypass.

---

## 5. Migration impact

**Engine (this change):**

| File | Change |
| --- | --- |
| `PoseDefinition.kt` | + `Extremity`, `ExtremityOrientationMode` enums; per-extremity mode array on `SkeletonPose` (default `AUTOMATIC`); `getExtremityOrientationMode`/`isExtremityAutomatic`/`overrideExtremityOrientation`; `copyFrom` propagates modes. |
| `SkeletonPoseFinalizer.kt` | − node-existence machinery (`containsJoint`, `containsJointNode`, `refreshJointPresenceCache`, `cachedHas*`); gate now reads `pose.isExtremityAutomatic(...)`. Derivation logic itself unchanged. |
| `BasePose.kt` | + `overrideExtremityOrientation(pose, extremity)` opt-in helper. |
| `BaseValidationPose.kt` | + matching `overrideExtremityOrientation(pose, extremity)` helper. |

**Poses:** none modified. Every existing pose now uses the default (`AUTOMATIC`) path, so the
engine derives their extremities. This is the intended restoration of ownership; the audit
flagged it as a *"High blast radius: flipping the gate changes every pose's rendered foot/hand
at once."*

**Follow-on migrations (out of scope for W1, tracked by the audit):** the now-redundant
per-pose compensations (W2 heel/toe writes, W3 palm/fingertip writes, W4 counter-rotations,
magic `0.29/0.71` and `6/6/10` literals) can be *deleted* from ~70 pose files, since the engine
now produces the geometry. W1 deliberately does not touch them (the task forbids modifying poses),
so they currently sit as dead/duplicate authoring that the engine overrides.

---

## 6. Regression risk

**Verification performed** (JDK 17, Gradle 8.7, Android SDK 34, `:app:testDebugUnitTest`):

- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL** (only pre-existing deprecation warnings).
- Unit tests — baseline vs. W1 measured in the same configuration. Four test files
  (`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest`) have
  **pre-existing compile errors documented in `AGENTS.md`** and were temporarily set aside for
  both runs so the module could compile; they were then restored and are **not modified**.

| Run | Tests | Failures |
| --- | --- | --- |
| Baseline (W1 reverted, same config) | 204 | 19 |
| With W1 | 204 | **22** |

**Net: +3 regressions, 0 tests fixed, no new failures outside the expected class.**

The 3 new failures are all the **same class** and all the **direct, expected consequence** of
restoring engine ownership:

| Test | Rule | Detail |
| --- | --- | --- |
| `PelvicTiltPoseTest` | `FOOT_GROUND_PENETRATION` | `TOE_F/TOE_B` y = **−2.33** |
| `GluteBridgePoseTest` | `FOOT_GROUND_PENETRATION` | `TOE_F/TOE_B` y = **−2.33** |
| `BurpeePoseTest` | `FOOT_GROUND_PENETRATION` | `TOE_F/TOE_B` y = **−2.57** |

**Root cause of the 3 regressions.** These are floor-contact poses that previously laid the foot
flat on the ground by **manually compensating**: e.g. `PelvicTiltPose` sets
`ankleF.localRotation = -torsoAngle` (a W4 counter-rotation) *and* hand-authors
`heelF/toeF.localPosition` with the magic `0.29/0.71` ratios (W2). Now that the engine derives the
foot from the shank + the *relative* ankle articulation, the pose's leftover manual compensation
double-counts, and the derived toe dips ~2.3–2.6 units below the ground plane, tripping the
validator's ground check.

This is exactly the coupling W1 exposes: the poses were doing the engine's job and cancelling a
tilt the engine now cancels itself. The correct resolution is the **W2/W3/W4 pose migration**
(delete the manual endpoints and counter-rotations), which is **out of scope for W1** and
explicitly forbidden by the task ("do not modify production poses"). Until that migration runs,
these three poses read a genuine, small ground-penetration — an honest reading of the residual
manual compensation, not an engine defect.

**Risk assessment:**

- **Contained and predictable.** All new failures are one rule (`FOOT_GROUND_PENETRATION`) on
  floor-contact poses that hand-compensated the foot. No arm/hand, IK, solver, validator-rule, or
  frame-continuity regressions appeared.
- **Neutral limbs unchanged.** For a neutral limb with identity articulation the derivation is
  designed to equal the FK frame, which is why the overwhelming majority of poses (201 of 204
  test cases, and every validation instrument) are unaffected.
- **Reversible / migratable.** Each affected pose can either (a) be migrated per W2/W3/W4 (delete
  the manual compensation — the intended end state), or (b) explicitly opt into
  `overrideExtremityOrientation(...)` if a stylized authored foot is genuinely wanted. Both are
  one-line, per-pose, and greppable.
- **No validation pose affected.** The diagnostic instruments (`MiddleSplit`, `DeepOverheadSquat`,
  `DeadHang`, `PikeSit`) continue to read their engine state faithfully and were not retuned.
