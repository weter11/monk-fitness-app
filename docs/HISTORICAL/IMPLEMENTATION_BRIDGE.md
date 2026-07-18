# Implementation Bridge — Architecture v2 to Existing Code

**Frozen with Architecture v2.** This document closes the gaps between the frozen specification
(`ARCHITECTURE_V2.md`, `API_CONTRACTS.md`, `ARCHITECTURE_V2_ROADMAP.md`) and the **existing**
engine code, so the roadmap is implementable without further architectural decisions. It defines
exact field shapes, maps them onto the W1-era `SkeletonPose`, specifies the flag-gating mechanism,
the `reconstructChestFrame` no-move guard, and the per-phase test mapping. No ownership changes —
every assignment here is a *type/bridge* decision within the frozen boundary.

Existing real types referenced below (verified in tree):
- `SkeletonPose` (`PoseDefinition.kt`): `joints: Array<Vector3>`, `rotations: Array<JointRotation>`,
  `roots`, `maxIkClampAmount: Float`, `rootTranslationDelta: Float`, `rootRotationDelta: Float`,
  `contacts: MutableList<ContactSpec>`, W1 `extremityOrientation: Array<ExtremityOrientationMode>`.
- `Extremity`, `ExtremityOrientationMode` (`PoseDefinition.kt:12,30`).
- `ContactSpec` (`ConstraintSolver.kt:20`): `endJoint, rootJoint, parentRotationJoint, middleJoint,
  targetWorld, pole, length1, length2, constraint, straight, contact`.
- `Joint` (`Joint.kt`): indexed enum; `entries.size` = 33 (includes `LUMBAR`, `CHEST`, etc.).

---

## B1. Exact field definitions for the 6 new types (Gap 1)

These are the concrete shapes. They extend (not replace) the existing `SkeletonPose`.

### B1.1 `PostureIntent` (Phase 2)
```kotlin
enum class PostureKind { SEATED_NEAR_FLOOR, HANGING_UNDER_BAR, STANDING, CUSTOM }
data class PostureIntent(
    val kind: PostureKind,
    val tolerance: Float = 20f   // world-unit leeway for Solver root placement
)
```
- `SEATED_NEAR_FLOOR` → Solver targets pelvis Y ≈ floor + seated height.
- `HANGING_UNDER_BAR` → Solver targets pelvis Y = barY − vertReach − torsoLength (the old
  `BaseVerticalPull` math, moved into Solver).
- `STANDING` → Solver targets pelvis Y = thigh+shin+standOffset.
- `CUSTOM` → Solver honors only contacts, ignores coarse height hint.

### B1.2 `SpineCurve` (Pose intent; Phase 5)
```kotlin
data class SpineCurve(
    val lumbarRad: Float,
    val thoracicRad: Float,
    val axis: Vector3 = Vector3(0f, 0f, 1f)   // sagittal Z by default; +X lateral, +Y axial
)
```
Replaces the dual `pelvis.localRotation.set` + `chest.localRotation.set` pattern. the MonkEngine runtime composes
lumbar (parent of chest) then thoracic; equal values reproduce a single overall bend.

### B1.3 `RelativeArticulation` (Pose intent; all joints)
```kotlin
data class RelativeArticulation(
    val axis: Vector3,
    val angle: Float
)
// Map<Joint, RelativeArticulation> — chest/hip/girdle/ankle/wrist entries only.
```
All angles are **relative to the parent segment** (the W1 relative-rotation contract, now explicit
for hip/girdle too). The existing `buildHipOrientation` / `buildChestOrientation` helpers already
emit a `JointRotation` in this form; they become the writers of this map.

### B1.4 `ContactId` (Phase 2 — conflict resolution)
```kotlin
// Stable identifier for a registered contact, for precedence ordering.
data class ContactId(val endJoint: Joint, val tag: String = "")
```
Pose builds `contactPrecedence: List<ContactId>` in priority order (index 0 = highest priority).

### B1.5 `WorldTarget` (Pose intent; limb + gaze targets)
```kotlin
data class WorldTarget(
    val position: Vector3,
    val isContact: Boolean,        // true → register ContactSpec; false → free target
    val constraint: ContactConstraint? = null
)
// Map<Joint(end), WorldTarget> — hands/feet/knees/elbows/HEAD (gaze) as end joints.
```
Gaze is expressed as `WorldTarget(HEAD, position = gazePoint, isContact = false)` — unifies gaze with
the limb-target model (F8 resolution). the MonkEngine runtime resolves neck/head articulation from this target
via the existing IK/constraint path (no new solver).

### B1.6 `Extremity` — REUSED, not redefined
The existing `Extremity` enum (FOOT_F, FOOT_B, HAND_A, HAND_P) is the frozen override channel.
`extremityOverrides: Set<Extremity>` in the spec == calling `overrideExtremityOrientation(e)` per
extremity (see B2). No new type.

---

## B2. Mapping to existing W1 `SkeletonPose` (Gap 2)

The spec's two-section model maps onto the **existing** `SkeletonPose` as follows. No field is
renamed; new fields are **added**.

| Spec section | Existing `SkeletonPose` field | Action |
|---|---|---|
| Intent: `skeleton` | (parameter of `build()`, not stored) | unchanged |
| Intent: `jointIntents` | not stored (helpers write `rotations[]` directly today) | **Phase 5**: helpers write into a new `val jointIntents: MutableMap<Joint, RelativeArticulation>` for auditability; `rotations[]` still the flattened output |
| Intent: `spineIntent` | none | **add** `var spineIntent: SpineCurve? = null` |
| Intent: `limbTargets` | none (pose passes targets directly to `bakeIkLimb` today) | **add** `val limbTargets: MutableMap<Joint, WorldTarget>` |
| Intent: `contacts` | `contacts: MutableList<ContactSpec>` | **reuse**; add `contactPrecedence: MutableList<ContactId>` |
| Intent: `postureIntent` | none | **add** `var postureIntent: PostureIntent? = null` |
| Intent: `extremityOverrides` | `extremityOrientation: Array<ExtremityOrientationMode>` (W1) | **reuse** — `extremityOverrides` in the spec IS this array; `overrideExtremityOrientation(e)` is the writer. Spec terminology unified to the existing API. |
| Intent: `motion` | n/a (driver is per-pose lambda) | unchanged |
| Intent: `camera`, `environment` | per-pose metadata | unchanged |
| State: `nodes` | `joints[]` + `rotations[]` | **reuse** — this IS the final local transform store |
| State: `maxIkClampAmount` | `maxIkClampAmount: Float` (exists) | **reuse** |
| State: `straightIntentDropped` | none | **add** `var straightIntentDropped: Boolean = false` |
| State: `rootTranslationDelta` | `rootTranslationDelta: Float` (exists) | **reuse** |
| State: `rootRotationDelta` | `rootRotationDelta: Float` (exists) | **reuse** |
| State: `boneLengthsVerified` | none | **add** `var boneLengthsVerified: Boolean = false` |

**Key resolution:** `extremityOverrides` (spec) and the W1 `extremityOrientation` array are the **same
state**. There is exactly one override channel. `copyFrom` already propagates it (PoseDefinition.kt:107);
no change needed.

---

## B3. Flag-gating mechanism (Gap 3)

All behavior-changing phases use a single global feature flag with **per-pose override** capability.

```kotlin
object EngineFlags {
    // Master switches — default FALSE (legacy behavior preserved) until a phase flips.
    var SOLVER_OWNS_POSTURE: Boolean = false      // Phase 2
    var FINALIZER_OWNS_CONVERSION: Boolean = false // Phase 3
    var ENGINE_OWNS_LIMB_FRAME: Boolean = false    // Phase 4 (gates deletion of rotAround)
}
```
- **Scope:** global boolean, read at pose-build time. No per-pose field needed because migration is
  batched per phase and verified against baseline before global flip.
- **Rollback:** set flag `false` → legacy code path (retained during migration) executes. Code paths
  are kept (not deleted) until the phase is verified, then legacy branches are removed in a follow-up.
- **Phase 4 nuance:** `rotAround` deletion is permanent once `ENGINE_OWNS_LIMB_FRAME=true` and baseline
  matches; the flag is removed in the same merge that deletes the last `rotAround` site.

---

## B4. Solver bridge for `PostureIntent` (Gap 4)

The existing `ConstraintSolver.solve` already repositions the root (translate + tilt + CCD). The bridge:

- **Today:** pose computes `pelvisY` then Solver *corrects* residual.
- **After Phase 2:** pose sets `postureIntent`; Solver **computes** the initial `pelvisY` from
  `postureIntent.kind` (B1.1 formulas) as the **seed** before the relaxation loop, replacing the
  pose's hand math. The existing relaxation/CCD loop is unchanged and operates on the seeded value.
- `contactPrecedence` feeds the existing averaging step (`delta.divide(contacts.size)` →
  weighted by precedence index instead of uniform average).
- No new solver algorithm is introduced; `PostureIntent` is a **seed + interpretation layer** over the
  existing machinery. This keeps Phase 2's risk contained to the seed computation.

---

## B5. `reconstructChestFrame` no-move guard (Gap 5 — the Critical F1 fix)

`SkeletonPoseFinalizer.reconstructChestFrame` (SkeletonPoseFinalizer.kt:62) is extended with a guard:

1. **Before** mutating `chest.localRotation`, snapshot the world positions of every contact
   end-effector: `val snapshot = contacts.map { it.endJoint to nodeMap[it.endJoint]!!.worldPosition }`.
2. After computing the new `chest.localRotation` and re-running FK for the chest subtree
   (`:137`), **assert** each snapshotted contact world position is unchanged within `EPS` (1e-3f):
   `for ((j,w) in snapshot) require(nodeMap[j]!!.worldPosition.distance(w) <= EPS)`.
3. If the assertion would fail, the chest frame is **not** applied to contacts: instead, the solver
   is signalled to perform a **bounded re-pass** (Phase 2 loop, max `MAX_ITERATIONS`), after which
   Finalizer retries once. If still failing, the chest frame is applied to the thorax only (contacts
   left at Solver-settled transforms) and `rootTranslationDelta` is flagged.
4. The existing **early-return when `chest.localRotation.angle != 0`** (authored chest, Issue F) is
   retained and takes precedence — an authored chest never reaches the guard.

This makes F1's "no-move guarantee" a hard, testable invariant rather than a convention.

**Implemented (Phase 3):** `SkeletonPoseFinalizer.reconstructChestFrame` snapshots every
Solver-settled contact end-effector (`buildContactSnapshot`) before mutating `chest.localRotation`,
reconstructs the frame, then calls `enforceContactNoMove`, which asserts each end-effector is
unchanged within `EPS = 1e-3f` (B5 step 2). If the reconstruction displaced a contact, the chest
frame is rolled back to the Solver-settled identity (the path only fires for identity chests) and
`rootTranslationDelta` is flagged (B5 step 3). The Issue F authored-chest early-return (step 4)
takes precedence. Both the guard and `preConvertPoles()` are gated by
`EngineFlags.FINALIZER_OWNS_CONVERSION` (default false), so the legacy finalize path is byte-
identical until the global flip. Covered by `ChestFrameNoMoveTest` (B6).

---

## B6. Per-phase test mapping + tolerances (Gap 6)

| Phase | Primary test class(es) | Assertion / tolerance |
|---|---|---|
| 0 | `:app:testDebugUnitTest` (full) | zero behavioral delta; new fields default-neutral |
| 1 | `BasePoseFrameworkTest`, limb-symmetry assertions | IK output unchanged vs baseline; `boneLengthsVerified==true`; default-pole unit test passes |
| 2 | `*PoseTest` for Squat/VerticalPull/Burpee/DeadBug families | `rootTranslationDelta < tolerance (≤5f)` when intent matches old `pelvisY`; `FOOT_GROUND_PENETRATION` stays at W1's 3 known (PelvicTilt/GluteBridge/Burpee) until Phase 4 |
| 3 | **new** `ChestFrameNoMoveTest` | guard assertion holds for all contact poses; `ValidatorRomClusterTest` green |
| 4 | `ChestFrameIssueFTest`, `ValidatorRomClusterTest`, per-pose geometry diff | limb orientation matches pre-deletion baseline within `1e-2` rad; near-horizontal-shin poses use `extremityOverrides` (no `FOOT_GROUND_PENETRATION` regression) |
| 5 | spine-angle assertions in `*PoseTest`; `ChestFrameIssueFTest` | trunk lean matches prior pelvis+chest sum within `1e-2` rad |
| 6 | hip-angle + limb-symmetry assertions | passive-leg pitch matches prior within `1e-2` rad |
| 7 | gaze-direction + shoulder-world assertions | eyes-forward when trunk leans; shoulder world positions match prior |
| 8 | `ExerciseValidator` unit tests | rules read stamps; diagnostic instruments (Middle Split etc.) still flag drops |

**Tolerance convention:** geometry diffs use `1e-2` rad / `1e-2` unit; validator-meter deltas use the
existing `TEST_BASELINE.md` thresholds. Any phase whose diff exceeds tolerance is **not** flipped
global until reconciled.

---

## B7. Implementation order within the freeze

1. Add the new `SkeletonPose` fields (B2) + new types (B1) — **Phase 0**, zero behavior change.
2. Add `EngineFlags` (B3) — **Phase 0**, default off.
3. Proceed Phase 1 → 8 per `ARCHITECTURE_V2_ROADMAP.md`; each phase flips its flag only after the
   B6 test mapping passes.
4. After a phase's global flip is verified, delete the legacy code branch in a follow-up commit
   (kept during migration for rollback per B3).

No architectural ownership changes. This bridge is type/bridge specification only.
