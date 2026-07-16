# RFC — Architecture v2 Intent Layer

**Status:** Draft specification (no code).
**Author:** Principal Engine Architect.
**Addresses:** Capability Gap Report Gap 2 (§1.1 intent carriers `spineIntent`, `limbTargets`,
`jointIntents`, `postureIntent` are dead) + the Intent Layer half of the architecture-v2 contract
(`ARCHITECTURE_V2.md` §1.1).
**Companion RFC:** `RFC_ENGINE_PIPELINE.md` (Gap 1) — this document designs the data the pipeline
consumes. The two are co-dependent: the pipeline cannot exist until the intent model is real, and
the intent model is meaningless until the pipeline reads it.
**Scope:** Design the complete intent model — ownership, lifecycle, serialization, copy semantics,
builder API, validation, immutability, dependency rules, and interaction with Solver / Finalizer /
Validator / migration. Reconciliation + design only; no new anatomical behavior; frozen
constitution (`ARCHITECTURE_FREEZE.md`) unchanged.

---

## 0. Current state (why the carriers are dead)

From `PoseDefinition.kt`, the §1.1 carriers already *exist* as data:

- `PostureIntent(kind, tolerance)` with `Kind { SEATED_NEAR_FLOOR, HANGING_UNDER_BAR, STANDING, CUSTOM }`
- `SpineCurve(lumbarRad, thoracicRad, axis)` — single declarative spine curve
- `RelativeArticulation(joint, rotation)` — one joint's parent-relative rotation
- `WorldTarget(joint, world)` — one joint's world-space pin
- `Extremity { FOOT_F, FOOT_B, HAND_A, HAND_P }` + `ExtremityOrientationMode { AUTOMATIC, MANUAL_OVERRIDE }`

On `SkeletonPose` they are stored as:
```
val jointIntents: MutableList<RelativeArticulation>
var spineIntent: SpineCurve
val limbTargets: MutableList<WorldTarget>
val contactPrecedence: MutableList<String>
var postureIntent: PostureIntent
val extremityOverrides: MutableSet<Extremity>
```
Plus `contacts: MutableList<ContactSpec>` and `motion/camera/environment` (typed `Any?` today).

**Why dead:** production `Pose.build()` never populates `spineIntent`/`limbTargets`/`jointIntents`
(it calls helpers that write the live node tree instead), and the engine never reads them — `copyFrom`
just copies them. Only `postureIntent`/`contactPrecedence` are written (via `declarePosture`) and read
(inside `ConstraintSolver.solve`), and only by *validation* poses; production poses set neither.

**Goal of this RFC:** make these carriers the **sole, authoritative, typed input** to the engine, with
a real builder API, real ownership, real validation, and a real migration from imperative
`Pose.build()`.

---

## 1. Complete intent model

### 1.1 Canonical carrier set (§1.1 — the intent contract)

The intent layer is a **typed, structured description of WHAT the pose wants**, expressed relative to
the skeleton definition, never as absolute node transforms.

| Carrier | Type | Meaning | Author | Reader |
|---|---|---|---|---|
| `spineIntent` | `SpineCurve` | single trunk lean (lumbar + thoracic, shared axis) | Pose | IK/Finalizer (resolves to pelvis+chest) |
| `jointIntents` | `List<RelativeArticulation>` | chest/hip/girdle/ankle/wrist parent-relative rotations | Pose | IK (applies to node tree) |
| `limbTargets` | `List<WorldTarget>` | hand/foot/knee/elbow/head world pins | Pose | IK (solves chain to pin) |
| `contacts` | `List<ContactSpec>` | fixed support contacts + which extremity | Pose | Solver |
| `contactPrecedence` | `List<ContactId>` | conflict resolution order | Pose | Solver |
| `postureIntent` | `PostureIntent` | coarse body arrangement (SEATED/HANGING/STANDING/CUSTOM) | Pose | Solver |
| `extremityOverrides` | `Set<Extremity>` | opt-out of engine extremity derivation | Pose | Finalizer |
| `headTarget` | `WorldTarget?` (NEW, see §1.2) | gaze-as-target (Gap 7) | Pose | Finalizer (neck/head resolver) |
| `motion` | `MotionDriver` (typed, see §1.3) | interpolation across frame | Pose | Pipeline (drives progress) |
| `camera` / `environment` | typed (see §1.3) | framing hints | Pose | Render |

### 1.2 New carrier: `headTarget`
Gap 7 (gaze-as-target) requires a world target for the head, not a counter-rotated UP vector. Add:
```
data class HeadTarget(val world: Vector3, val upBias: Vector3 = Vector3(0f,1f,0f))
```
held as `var headTarget: HeadTarget? = null` on §1.1. The Finalizer resolves neck/head from it
(reuses `buildHead` math, driven by target not direction). Until migrated, `null` = legacy
direction-based gaze (no behavior change).

### 1.3 Typing the loosely-typed fields
Today `motion: Any?`, `camera: Any?`, `environment: Any?`. The intent layer **types** them:
- `motion: MotionDriver` (sealed interface; the existing `PoseMetadata.motionCurve` + `durationSeconds`
  already imply this — promote to a real type instead of `Any?`).
- `camera: CameraHint` / `environment: EnvironmentHint` — thin typed wrappers (no behavior; just
  structured hints the render layer already consumes). This removes the `Any?` escape hatch that
  defeats compile-time intent checking.

### 1.4 Joint-intent coverage rule
`jointIntents` must cover exactly the **relative-articulation joints** the spec names: chest, hip
(girdle: shoulder/clavicle/scapula), ankle, wrist. Spine is covered by `spineIntent` (not a
`RelativeArticulation` on CHEST — that would double-declare). **Rule:** a pose MUST NOT put a
`RelativeArticulation(joint = CHEST, …)` AND a `spineIntent` simultaneously for the same axis; the
pipeline validates this (§6).

---

## 2. Ownership

**Single writer, single reader per carrier (spec §4.2):**

| Carrier | Writer | Reader | Forbidden |
|---|---|---|---|
| `spineIntent`, `jointIntents`, `limbTargets`, `contacts`, `contactPrecedence`, `postureIntent`, `extremityOverrides`, `headTarget`, `motion`, `camera`, `environment` | **Pose** (during `build()`) | **Engine** (IK→Solver→Finalizer) | Validator may READ (ROM), never write. Pose may never read back §1.2. |
| §1.2 (`nodes`, stamps) | **Engine** (IK/Solver/Finalizer) | **Validator + Render** | Pose may never write. |

**Ownership enforcement (three layers):**
1. **Compile-time:** the intent builder (§5) is the *only* API that mutates §1.1; direct `pose.spineIntent =`
   is `val`/package-private-setter only. After migration, poses receive an `IntentBuilder` and never
   touch `SkeletonPose` fields directly.
2. **Lifecycle (§3):** §1.1 is frozen the instant `build()` returns. The pipeline treats it as
   immutable input; any engine stage that needs to *adjust* a joint does so on the node tree (§1.2),
   never by rewriting §1.1.
3. **Validation (§6):** a `OWNERSHIP_VIOLATION` check asserts no engine stage wrote §1.1 and no pose
   wrote §1.2 (cheap referential check at pipeline boundaries).

---

## 3. Lifecycle

```
t0  Pose.build(context)                         ── Pose mutates IntentBuilder (§1.1 only)
t1  build() returns SkeletonPose (§1.1 frozen)  ── ownership transfers Pose → Engine
t2  Pipeline.IkStage reads §1.1, writes node tree (§1.2 begins)
t3  Solver reads §1.1 (contacts/precedence/posture), writes root + deltas (§1.2)
t4  Finalizer reads §1.1 (extremityOverrides, headTarget, spineIntent), writes §1.2 nodes
t5  Validator READS §1.1 (ROM vocabulary) + §1.2 (stamps) → ValidationReport
t6  Render reads §1.2 only
```
**Invariant:** §1.1 is **write-once** (Pose, t0). After t1 it is read-only for the rest of the frame.
The engine never writes §1.1; if an engine stage needs to "correct" intent (e.g. Solver relaxes a
posture), it writes §1.2 (the resolved geometry) + stamps (`rootTranslationDelta`), never the intent.
This keeps intent as a **permanent record of authorial will** — essential for debugging and for the
Validator's ROM checks.

**Cross-frame:** `SkeletonPose` may be reused across frames (F9 smoothing cache keyed by identity).
On reuse, `build()` is called again with a new `PoseContext`, producing a **fresh §1.1**; the engine
re-resolves. §1.1 is therefore per-`build`, not per-instance — the instance is a reusable carrier, the
§1.1 *content* is per-frame.

---

## 4. Serialization

**Why serialize intent:** (a) save/load a workout frame, (b) deterministic replay in tests,
(c) cross-process pose transfer (future), (d) the Validator's diagnostic dump.

**Design:**
- §1.1 is **fully serializable** because it is value data: `SpineCurve` (3 floats), `RelativeArticulation`
  (`Joint` enum + `JointRotation`), `WorldTarget` (`Joint` + `Vector3`), `PostureIntent` (enum + float),
  `ContactSpec`/`ContactId` (structured), `Extremity` set, `HeadTarget`. All are `data class` + enums →
  trivially `(de)serializable` to JSON / flatbuffer.
- §1.2 is **NOT serialized as intent** — it is derived state; only stamps + final nodes may be
  serialized for debugging, never fed back as input.
- **Intent manifest:** a single `IntentManifest` value object = the complete §1.1 snapshot. `SkeletonPose`
  gains `fun toIntentManifest(): IntentManifest` and `fun applyIntentManifest(m): Unit` (the latter only
  valid pre-pipeline, i.e. by a loader, not by the engine).
- **Versioning:** `IntentManifest.schemaVersion: Int` (bump on carrier shape change). The pipeline
  rejects manifests whose schema version it does not understand (fail-fast, not silent).

---

## 5. Copy semantics

`SkeletonPose.copyFrom(other)` already propagates both §1.1 and §1.2. Under the intent layer this is
**refined:**

- **§1.1 copy = structural deep copy of value data.** Lists (`jointIntents`, `limbTargets`,
  `contactPrecedence`) are copied element-by-element (not reference-shared) so the destination owns an
  independent intent. `SpineCurve`/`PostureIntent`/`WorldTarget` are `data class` → copy by value.
  `extremityOverrides` is a `Set` copy.
- **§1.2 copy = the existing stamp + node propagation** (unchanged).
- **`IntentBuilder` (the §1.1 authoring surface):**
  ```
  class IntentBuilder {
      fun spine(lumbarRad, thoracicRad, axis = axisZ): IntentBuilder
      fun joint(joint, rotation: JointRotation): IntentBuilder
      fun limbTarget(joint, world: Vector3, constraint, contact?): IntentBuilder
      fun posture(kind, tolerance = 0f): IntentBuilder
      fun contact(spec, precedenceRank?): IntentBuilder
      fun overrideExtremity(extremity): IntentBuilder
      fun headTarget(world, upBias = UP): IntentBuilder
      fun motion(driver): IntentBuilder
      fun camera(hint): IntentBuilder
      fun environment(hint): IntentBuilder
      fun build(): SkeletonPose   // freezes §1.1, leaves §1.2 empty
  }
  ```
  `BasePose` helpers (`buildSpineCurve`, `buildHipFlexion`, `buildHipOrientation`, `buildShoulders`,
  `declareLimbTarget`, `declarePosture`) become **thin forwards to `IntentBuilder`** — they keep their
  current signatures (so poses don't churn) but their bodies switch from "write node" to "record
  intent". This is the key migration lever: **same call sites, different sink.**

---

## 6. Validation (of the intent itself)

Two distinct validators:
1. **Intent-self-validation** (new, runs at t1→t2 boundary, before geometry): asserts the intent is
   *well-formed* and *internally consistent*.
2. **Geometry validation** (existing `ExerciseValidator`, runs at t5): asserts the *resolved* pose
   honors ROM / penetration / straight-intent — reads §1.2 + §1.1 ROM (Gap 6).

**Intent-self-validation rules:**
| ID | Rule | Failure |
|---|---|---|
| I1 | `spineIntent` axis is unit-length (or zero = identity) | `INTENT_SPINE_AXIS_INVALID` |
| I2 | no `RelativeArticulation(CHEST)` when `spineIntent` non-identity (double-declare, §1.4) | `INTENT_SPINE_CONFLICT` |
| I3 | every `limbTargets[joint]` joint is an end/intermediate joint (hand/foot/knee/elbow/head), not a root | `INTENT_TARGET_JOINT_INVALID` |
| I4 | every `WorldTarget.world` is finite (no NaN/Inf) | `INTENT_TARGET_NONFINITE` |
| I5 | `contacts` referenced by `contactPrecedence` all exist; precedence is a permutation, not a superset | `INTENT_PRECEDENCE_MISMATCH` |
| I6 | `postureIntent.kind` consistent with declared contacts (e.g. `HANGING_UNDER_BAR` implies a hand/bar contact) — soft, tolerance-gated | `INTENT_POSTURE_CONTACT_MISMATCH` |
| I7 | `extremityOverrides` only contains joints the pose also pins via a limbTarget (else override is meaningless) | `INTENT_ORPHAN_OVERRIDE` |
| I8 | `headTarget` (if set) finite + distinct from chest world pos | `INTENT_HEAD_TARGET_INVALID` |
| I9 | **Ownership:** §1.1 written by Pose only, §1.2 empty at t1 | `INTENT_OWNERSHIP_VIOLATION` |
| I10 | schema version known | `INTENT_SCHEMA_UNKNOWN` |

Intent-self-validation is **cheap and pure** (no geometry) → runs every frame in debug, sampled in
release. A failed intent aborts the frame (all-or-nothing, per `RFC_ENGINE_PIPELINE` §8.4).

---

## 7. Immutable vs mutable design

**Decision: §1.1 is immutably frozen post-`build()`; authored mutably inside `IntentBuilder`.**

- **Inside `build()`**: the `IntentBuilder` is mutable (fluent). The pose accumulates intent.
- **At `build()` return**: the builder produces a `SkeletonPose` whose §1.1 is presented as
  **read-only** to the engine. Setters are package-private / `val` from the engine's viewpoint.
- **Rationale:** intent is authorial will — it must not be silently mutated by the engine (that would
  hide the author's actual request from the Validator's ROM checks). Immutability also makes the
  `copyFrom`/manifest semantics trivially correct (no aliasing bugs across frames).
- **§1.2 is mutable by design** (it is derived state, written stage-by-stage). Only §1.1 is frozen.
- **`extremityOverrides`** is a `Set` presented as read-only after build (the pose adds during build,
  engine reads).
- **Exception:** `headTarget` may be `null`→set by a *loader* applying an `IntentManifest` (§4) — that
  is pre-pipeline input substitution, not engine mutation, and is allowed only on a fresh (§1.2-empty)
  pose.

This matches the spec's ownership table exactly: Pose writes §1.1 (mutable during authoring, frozen
after); Engine writes §1.2 (mutable during resolution, read-only after FK).

---

## 8. Dependency rules

```
Pose ──(IntentBuilder)──▶ SkeletonPose.§1.1   [Pose depends on IntentBuilder + carrier types]
Pipeline ──reads──▶ SkeletonPose.§1.1         [Engine depends on carrier types, not on Pose]
Solver/Finalizer ──read──▶ §1.1; write ──▶ §1.2
Validator ──read──▶ §1.1 (ROM) + §1.2
Render ──read──▶ §1.2 only
```
**Forbidden:**
- Engine stage depends on a *concrete Pose class* → violates "Pose is pluggable intent source". Engine
  depends only on `SkeletonPose` + carrier types. (A pose is any `PoseBuilder`.)
- Pose depends on `SkeletonMath`/IK/Finalizer → would re-introduce geometry into the pose. Pose depends
  only on `IntentBuilder` + `SkeletonDefinition` (for proportions, e.g. shoulder width hints).
- Validator writes §1.1 or §1.2 → observer only.
- §1.1 carrier type depends on a stage → carriers are leaf value types; they import nothing from
  IK/Solver/Finalizer. This keeps the intent layer **decoupled and unit-testable in isolation**.

---

## 9. Interaction with Solver

- Solver reads **only** `contacts`, `contactPrecedence`, `postureIntent`, `extremityOverrides`,
  `limbTargets` (to know which chains are contact-pinned). It does **not** read `spineIntent`/
  `jointIntents` (those are applied by IK into the node tree before the Solver runs).
- Solver **writes** §1.2: root/pelvis transform, `rootTranslationDelta`, `rootRotationDelta`,
  re-baked contact limbs. It NEVER writes §1.1.
- **Posture seed (F2):** `seedRootFromPostureIntent` interprets `postureIntent.kind` → a target pelvis
  Y (B1.1 formulas already implemented). Under architecture-v2 this is **mandatory for any pose that
  declares a non-CUSTOM posture** (Gap 3 fix): the pose no longer hand-computes `pelvisY`.
- **Conflict resolution (F7):** `contactPrecedence` orders which contact wins; the Solver applies
  `rootDelta` weighted by precedence. Intent must declare precedence (I5) or all contacts are equal.
- **Smoothing (F9):** Solver eases the solved root toward `lastSolvedRoot[pose]` (WeakHashMap).
  Because §1.1 is per-build and the pose instance is reused, the cache key is stable → smoothing works
  without §1.1 mutation.

---

## 10. Interaction with Finalizer

- Finalizer reads **only** `spineIntent`, `jointIntents`, `extremityOverrides`, `headTarget`,
  `limbTargets` (to know authored shapes). It does **not** re-read `postureIntent`/`contacts`
  (Solver already settled those).
- Finalizer **writes** §1.2 `nodes`: world↔local conversion (`preConvertPoles`, active), extremity
  derivation (unless `extremityOverrides`), relative tilt cancel, `reconstructChestFrame` (F1/B5
  no-move on Solver-settled contacts), flatten.
- **`spineIntent` resolution:** the Finalizer (or IK, by division of labor) expands `SpineCurve` into
  the pelvis+chest local rotations about the shared axis. This is the *real* consumption of
  `spineIntent` — replacing today's `buildSpineCurve(pelvis, chest, …)` which writes nodes directly.
  Under the intent layer, `buildSpineCurve` records `spineIntent`; the engine expands it. (One
  subtlety from Phase 5: the lower segment lands on PELVIS because hips attach there — the expansion
  logic must preserve that; documented in `RFC_ENGINE_PIPELINE` + Phase 5 retro.) 
- **`headTarget` resolution (Gap 7):** Finalizer derives neck/head from `headTarget` when non-null;
  when null, falls back to legacy direction-based `buildHead` (no behavior change until migrated).
- Finalizer **never** writes §1.1. If chest-frame reconstruction would move a settled contact, it
  signals the pipeline (bounded re-pass), it does NOT rewrite `spineIntent`.

---

## 11. Interaction with Validator

- Validator reads §1.1 **for ROM vocabulary only** (e.g. which joints the pose *intended* to move, so
  it can report "intended X but ROM exceeded"). It does **not** reconstruct geometry from §1.1 (Gap 6).
- Validator reads §1.2 **stamps** (`maxIkClampAmount`, `straightIntentDropped`, `boneLengthsVerified`,
  `rootTranslationDelta`) → these are the *outcome* of intent resolution.
- Validator may also run **intent-self-validation** (§6) if the pipeline didn't (defense in depth), but
  the authoritative intent check is at the t1→t2 boundary.
- Validator writes **nothing**. It returns `ValidationReport`.

---

## 12. Migration from imperative `Pose.build()`

Same flag-gated, per-pose strategy as `RFC_ENGINE_PIPELINE` §6. The Intent Layer migrations are the
**pose-side** half; the pipeline migrations are the **engine-side** half. They land together per pose.

### Step A — Introduce `IntentBuilder`, keep `build()` shape
- Add `IntentBuilder` + `SkeletonPose.toIntentManifest()`/`applyIntentManifest()`.
- `BasePose` helpers (`buildSpineCurve`, `buildHipFlexion`, `buildHipOrientation`, `buildShoulders`,
  `declareLimbTarget`, `declarePosture`, `declareContacts`) gain an `IntentBuilder` overload alongside
  the legacy node-writing one. No pose changes yet.

### Step B — Pose-by-pose: redirect helpers to `IntentBuilder`
- For each pose, change its `build()` to accumulate into an `IntentBuilder` and `return builder.build()`
  (which emits §1.1, empty §1.2). The legacy node-writing overloads are deleted once no pose references
  them (compile-time guarantee of complete migration).
- Poses that still call a deleted helper fail to compile → migration is mechanical and total.

### Step C — Typed fields
- Replace `motion: Any?` / `camera: Any?` / `environment: Any?` with typed `MotionDriver` /
  `CameraHint` / `EnvironmentHint`. Update poses + render consumers.

### Step D — Activate consumption
- Flip `PIPELINE_ACTIVE` + `IK_WORLD_ONLY` + `FINALIZER_OWNS_CONVERSION` (pipeline RFC M2–M4). The
  engine now reads the carriers the poses populate. `spineIntent`/`limbTargets`/`jointIntents` cease
  to be dead.
- `headTarget` (Gap 7) migrated last (Step E), behind its own flag.

### Step E — Gaze (Gap 7)
- Add `headTarget`; retrofit `BaseLungePose`/`BaseVerticalPullPose` gaze sites to `builder.headTarget(...)`.
  Legacy `buildHead(direction)` retained as fallback when `headTarget == null`.

### Coexistence / adapters
- **`LegacyPoseAdapter`**: wraps a `PoseBuilder` whose `build()` still returns a full node tree (the
  long tail). The adapter extracts §1.1-equivalent intent from the tree (best-effort) OR the pose is
  simply migrated. Prefer direct migration; adapter is fallback only.
- Until `PIPELINE_ACTIVE`, poses may still build trees; the pipeline's legacy path ignores §1.1. After
  the flip, any pose not yet migrated is caught at build time (helper deletion) — no silent
  half-state.

---

## 13. Open questions (non-blocking)
- Should `IntentManifest` be the *canonical* serialized form, or should `SkeletonPose` itself be
  serializable with a `includeState: Boolean` flag? (RFC prefers `IntentManifest` to keep §1.1/§1.2
  serialization concerns separate.)
- Should `jointIntents` be a `Map<Joint, RelativeArticulation>` (unique per joint, easier conflict
  detection) instead of a `List`? (RFC uses `List` for append-order fidelity with current code; a Map
  is a cleaner alternative — maintainer call.)
- `headTarget.upBias` default — confirm `Vector3(0,1,0)` is the right neutral gaze-up.
