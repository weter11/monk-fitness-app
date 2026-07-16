# Architecture v2 — Definitive Implementation Specification

**Status:** FROZEN (see `ARCHITECTURE_FREEZE.md`). This document is the single source of truth for engine responsibility. Philosophy: **pose = intent, engine = geometry, validation = observer.**

---

## 1. `SkeletonPose` — Single Intent + State Carrier (resolves F2, F3, F10)

The one and only carrier of both pose intent and derived state.

### 1.1 Intent section (written by Pose, read by Engine)
- `skeleton: SkeletonDefinition`
- `jointIntents: Map<Joint, RelativeArticulation>` (chest/hip/girdle/ankle/wrist — relative to parent)
- `spineIntent: SpineCurve(lumbarRad, thoracicRad, axis)` — single spine declaration
- `limbTargets: Map<Joint(end), WorldTarget>` (hand/foot/knee/elbow/head)
- `contacts: List<ContactSpec>` + `contactPrecedence: List<ContactId>`
- `postureIntent: PostureIntent(kind, tolerance)` — typed coarse intent (SEATED_NEAR_FLOOR / HANGING_UNDER_BAR / STANDING / CUSTOM)
- `extremityOverrides: Set<Extremity>` — explicit opt-out of derivation (stylized only)
- `motion: MotionDriver`
- `camera`, `environment`

### 1.2 State section (written by Engine, read by Validation)
- `nodes: Map<Joint, LocalTransform>` — final local transforms
- `maxIkClampAmount: Float`
- `straightIntentDropped: Boolean`
- `rootTranslationDelta: Float`, `rootRotationDelta: Float`
- `boneLengthsVerified: Boolean`

**Ownership rule:** Pose writes §1.1; Engine writes §1.2; Validation reads §1.2. No component edits another's section.

---

## 2. Component Contracts

### 2.1 Pose (intent only)
- **In:** exercise spec, frame progress. **Out:** populates §1.1.
- **Must NOT:** compute pelvis transform, `rotAround` limbs, write endpoints, dual-write pelvis+chest, hand-place girdle, write `nodes`/§1.2.

### 2.2 IK (world-space limb solver)
- **In:** root world pos, target world pos, lengths, **world-space pole**, constraint, contact.
- **Owns:** 2-bone analytical solve; straight-limb bend fallback; **bone-length-exact invariant**; **default pole** when Pose omits.
- **Out (writes State):** `maxIkClampAmount`, `straightIntentDropped` (primary), `boneLengthsVerified`.
- **Must NOT:** translate root, honor contacts, cancel inherited tilt. **World-only.**

### 2.3 ConstraintSolver (posture authority)
- **In:** `SkeletonPose` intents + contacts + `postureIntent` + `contactPrecedence`.
- **Owns:** root exact transform (from contacts + `postureIntent`); root translate/tilt to honor contacts; true posture CCD (residual → free joints, regularized to intent); contact conflict resolution via `contactPrecedence`; inter-frame temporal relaxation; re-bake contact limbs; secondary writer of stamps; `rootTranslationDelta`/`rootRotationDelta`.
- **Must NOT:** invent contacts; move non-contact authored shape; wipe deliberate lean. **Sole mover of root/pelvis.**

### 2.4 SkeletonPoseFinalizer (per-node geometry resolver)
- **Owns (exclusive):** world↔local frame conversion; extremity derivation (unless override); relative-rotation resolution (tilt cancel); chest-frame reconstruction (read-only on settled); flatten to `nodes`.
- **Must NOT:** move Solver-settled contact end-effector (F1 guarantee); translate root; re-solve posture; accept local-frame pole.

### 2.5 FK (stateless propagation primitive)
- Computes world transforms from local; propagates intent; re-flattens subtree. Owned by none; shared utility. **Must NOT** decide local rotations.

### 2.6 Validation (observer)
- **In:** State stamps + Intent (ROM read). **Owns:** rule checks (penetration/ROM/straight) — read stamps only. **Must NOT** derive geometry or drive system.

---

## 3. Execution Pipeline (resolves F1)

```
PHASE 0 — Pose: build(progress) → populates §1.1; MotionDriver interpolates.
PHASE 1 — IK (world-only): solve each limb target → world joint/end; writes stamps (primary); derives default pole.
PHASE 2 — ConstraintSolver (posture, iterates): FK → reposition root from contacts+PostureIntent → resolve conflicts via precedence → CCD → re-bake; writes deltas; inter-frame smoothing. EXIT on residual≤eps or max iters.
PHASE 3 — Finalizer (geometry, single pass, read-only on settled): preConvertPoles(); derive extremities; resolve relative rotations; reconstructChestFrame() (GUARANTEE: no contact-settled end-effector moves; early-return if chest intent authored); write nodes.
PHASE 4 — FK + Flatten (terminal): FK(root, nodes) → final world; fromHierarchy → §1.2.
PHASE 5 — Validation (observer): reads §1.2 + §1.1 ROM → reports.
```

### Phase boundaries
- Pose → IK: intent available, no geometry.
- IK → Solver: world limb solves done, root not yet posture-settled.
- Solver → Finalizer: root + contacts + posture **final**; Finalizer must not undo them.
- Finalizer → FK: all local transforms written; FK only propagates.
- FK → Validation: state complete; Validation read-only.

### Re-entry rule (F1)
Finalizer is strictly single-pass and read-only on Solver-settled contacts. If a pose requires chest-frame to move a contact, the architecture mandates a **bounded Solver re-pass**, not silent Finalizer mutation.

---

## 4. Definitive Ownership Tables

### 4.1 Concern → Owner
| Concern | Owner |
|---|---|
| Skeleton/proportion selection | Pose |
| Motion drivers | Pose |
| Contacts + precedence | Pose (declare) / Solver (resolve) |
| PostureIntent (typed) | Pose (declare) / Solver (interpret) |
| Camera/environment | Pose |
| Spine intent (1 call) | Pose |
| Chest/hip/girdle/ankle/wrist intent | Pose (relative) |
| Gaze = head target | Pose (target) |
| Limb endpoint targets | Pose (target) |
| IK 2-bone solve | IK (world-only) |
| Straight fallback | IK |
| Bone-length invariant | IK |
| Default pole | IK |
| Root exact transform | Solver |
| Contact honor | Solver |
| Posture CCD | Solver |
| Inter-frame smoothing | Solver |
| Clamp/straight stamps (secondary) | Solver |
| World↔local conversion | Finalizer |
| Extremity derivation | Finalizer |
| Relative tilt cancel | Finalizer |
| Chest-frame reconstruction | Finalizer (read-only on settled) |
| Flatten to nodes | Finalizer |
| FK propagation | FK (stateless) |
| Rule checks | Validation (read stamps) |

### 4.2 `SkeletonPose` field → Writer/Reader
| Field | Writer | Reader |
|---|---|---|
| §1.1 intent block | Pose | Engine |
| `maxIkClampAmount` | IK (primary), Solver (secondary) | Validation |
| `straightIntentDropped` | IK (primary), Solver (secondary) | Validation |
| `rootTranslationDelta/RotationDelta` | Solver | Validation |
| `boneLengthsVerified` | IK | Validation |
| `nodes` (local) | Finalizer | Validation, render |

### 4.3 Stamp ownership (resolves F3)
- **Storage:** `SkeletonPose` §1.2 (single carrier, F10).
- **Writers:** IK (author-bake), Solver (re-bake). Merge = max/OR. `SkeletonPose` is the owner.

---

## 5. Resolved Findings Index
| ID | Resolution |
|---|---|
| F1 (Critical) | §3 pipeline + PHASE 3 no-move guarantee + re-entry rule. |
| F2 (Medium) | §1.1 `PostureIntent` typed; Solver interprets. |
| F3 (Medium) | §4.2/§4.3 — `SkeletonPose` is stamp owner; IK/Solver writers. |
| F4 (Medium) | §2.4 Finalizer exclusive frame conversion; IK world-only. |
| F5 (Minor) | §2.2 IK bone-length contract explicit. |
| F6 (Minor) | §2.2 IK default-pole. |
| F7 (Minor) | §1.1 `contactPrecedence`; Solver resolves. |
| F9 (Minor) | §2.3 Solver inter-frame relaxation. |
| F10 (Medium) | §1 `SkeletonPose` named single carrier; §4.2 ownership. |
| F8 | Rejected (preference) — gaze unified as target in §1.1. |

---

## 6. Invariants (implementation must enforce)
1. Pose never writes `nodes` or §1.2.
2. Solver is the only mover of the root/pelvis transform.
3. Finalizer never moves a Solver-settled end-effector.
4. IK is world-only; Finalizer is the only local-transform writer.
5. Validation never writes `SkeletonPose`.
6. `SkeletonPose` is the sole shared mutable state.

**This is the implementation specification. No new architectural ideas; original philosophy preserved.**
