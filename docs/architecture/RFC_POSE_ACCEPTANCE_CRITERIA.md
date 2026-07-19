# RFC: Pose Acceptance Criteria (PAC)

> **Status:** Architecture RFC (documentation only — no code or pose changes).
>
> **Purpose.** Define objective completion criteria for every MonkEngine pose.
> The goal is to eliminate subjective reviews such as:
>
> - "looks good"
> - "looks wrong"
> - "feels better"
>
> Every pose must pass **measurable acceptance criteria** tied to the three
> canonical reference documents. A review is a checklist verdict, not a feeling.

---

## 1. Philosophy

A pose is accepted **only if it satisfies, in order:**

1. **BPS** — the Biomechanical Pose Specification for that exercise (the
   human-biomechanics target, with its explicit checkpoints and acceptance
   boxes).
2. **JOM** — the Joint Ownership Matrix (every joint built by exactly its
   assigned owner, no cross-owner mutation).
3. **VOM** — the Validation Ownership Matrix (every feature certified by exactly
   its assigned validation domain, in the deterministic order).

**No visual judgement alone is sufficient to accept or reject a pose.** Visual
impression is the *last* input, and it may only flag a possible reference
violation (see §6) — it never overrides a passing BPS/JOM/VOM verdict, and it
never introduces an arbitrary aesthetic change.

---

## 2. Acceptance Pipeline

Every pose review follows this fixed pipeline. Each stage must be recorded as
pass/fail before the next begins.

```
1. Biomechanical Specification (BPS)
        ↓
2. Joint Ownership validation (JOM)
        ↓
3. Validation Ownership (VOM)
        ↓
4. Engine integration
        ↓
5. Visual inspection
```

- **Stage 1 (BPS)** confirms the pose realizes the correct human biomechanics.
- **Stage 2 (JOM)** confirms each joint was built by its sole owner with no
  cross-owner mutation.
- **Stage 3 (VOM)** confirms each validation domain passes in the deterministic
  order (Balance → Contacts → Pelvis → Spine → Head → Shoulders → Arms →
  Hands → Hips → Knees → Feet → Symmetry).
- **Stage 4 (Engine integration)** confirms the engine performed all computation
  (IK, FK, contacts, balance, spine/head/foot/hand reconstruction) with no
  pose-side duplication.
- **Stage 5 (Visual inspection)** is **LAST**. It may only report possible
  BPS/JOM/VOM violations; it cannot accept or reject on aesthetics.

A pose cannot reach stage 5 until stages 1–4 are recorded as passing.

---

## 3. Required Checks

For **every** pose, the reviewer explicitly verifies each of the following and
records **pass** or **fail** (no omission, no "n/a" without justification):

| Check | Reference | Pass condition |
|---|---|---|
| **Head** | BPS §4, VOM Head | Gaze, cervical alignment, chin, head position match BPS; VOM Head domain passes. |
| **Spine** | BPS §5, VOM Spine | Lumbar/thoracic/rib-cage/pelvis posture match BPS; VOM Spine domain passes. |
| **Pelvis** | BPS §5/§7, VOM Pelvis | Pelvic tilt/list/rotation/level match BPS; VOM Pelvis domain passes. |
| **Shoulders** | BPS §6, VOM Shoulder | Glenohumeral centration, girdle carriage match BPS; VOM Shoulder passes. |
| **Elbows** | BPS §6, VOM Arm | Flexion/tuck/flare match BPS; VOM Arm domain passes. |
| **Wrists** | BPS §6, VOM Wrist/Hand | Wrist orientation neutral per BPS; VOM Wrist/Hand passes. |
| **Hands** | BPS §6/§8, VOM Wrist/Hand | Palm/footprint/contact match BPS; VOM Wrist/Hand passes. |
| **Hips** | BPS §7, VOM Hip | Femur orientation/ROM match BPS; VOM Hip domain passes. |
| **Knees** | BPS §7, VOM Knee | Tibia-vs-femur, tracking, extension match BPS; VOM Knee passes. |
| **Feet** | BPS §7/§8, VOM Foot | Ankle/foot placement/toe direction/contact match BPS; VOM Foot passes. |
| **Contacts** | BPS §8, VOM Contact | Each contact surface/pressure/stability matches BPS; VOM Contact passes. |
| **Balance** | BPS §3, VOM Balance | COM within base of support; VOM Balance passes. |
| **Symmetry** | BPS §3/§11, VOM Symmetry | Left/right mirror equivalence; VOM Symmetry passes. |

Every row must show an explicit **pass** or **fail**. A single fail blocks
acceptance until resolved through the correct owner (BPS/JOM/VOM), never through a
visual hack.

---

## 4. Failure Conditions

A pose is **immediately rejected** if any of the following hold:

- **Pose compensates for engine behavior** — the pose solves a deficit that
  belongs to the engine instead of reporting it.
- **Violates ownership** — a joint or feature is built/validated by a non-owner
  (contradicts JOM or VOM).
- **Duplicates IK** — the pose re-implements inverse kinematics.
- **Duplicates FK** — the pose re-implements forward kinematics / world
  propagation.
- **Violates BPS** — any BPS checkpoint or acceptance box is not satisfied.
- **Introduces magic constants** — unexplained numeric offsets whose only purpose
  is appearance rather than biomechanical intent.
- **Manual balance** — the pose positions the COM directly instead of declaring
  intent.
- **Manual contacts** — the pose places/controls contacts instead of declaring
  them.
- **Manual stabilization** — the pose forces pelvis/spine/wrist/foot stabilization
  with transforms instead of declaring intent.
- (any other forbidden logic from the Pose Responsibility Protocol §4).

Rejection is unconditional: a "looks fine otherwise" does not rescue a pose that
triggers any failure condition.

---

## 5. Completion Definition

A pose is **COMPLETE** only if **all** of the following are true:

- ✓ All **BPS** checkpoints satisfied (every §11 checkpoint and §13 acceptance
  box passes for that exercise).
- ✓ All **JOM** owners satisfied (every joint built by its sole owner; no
  cross-owner mutation; mount continuity holds).
- ✓ All **VOM** domains pass (in the deterministic order of §2 / VOM §3).
- ✓ **No forbidden logic** present (none of the §4 failure conditions; PRP §4
  clean).
- ✓ **Engine performs all computation** (IK, FK, contacts, balance, spine/head/
  foot/hand reconstruction, validation — none duplicated in the pose).
- ✓ **Visual review agrees** (stage 5 reports no open BPS/JOM/VOM violation).

Completeness is a boolean over these six conditions. If any is false, the pose is
not complete.

---

## 6. Visual Review

Visual inspection (stage 5) is permitted **only** to report:

- a **possible BPS violation** (a rendered feature disagrees with the BPS
  checkpoints),
- a **possible JOM violation** (a joint appears built outside its ownership),
- a **possible VOM violation** (a validation domain appears to have passed
  incorrectly).

A visual review **may NOT** request arbitrary aesthetic changes ("make it look
nicer", "tilt the head a bit", "raise the hips slightly") that are not traceable
to a specific BPS/JOM/VOM section. Every visual finding must cite the reference
section it believes is violated, at which point the finding re-enters the pipeline
at the correct stage — it is never a standalone acceptance lever.

---

## 7. Future Audits

Every future audit must end with exactly one of three verdicts, each referencing
specific sections of the reference documents:

- **Accepted** — all BPS checkpoints, all JOM owners, all VOM domains pass; no
  forbidden logic; engine performed all computation; visual review clean. Cite the
  BPS file + §sections, the JOM groups used, and the VOM domains passed.
- **Accepted with biomechanical issues** — the pose is structurally owned
  correctly (JOM/VOM clean, no forbidden logic, engine owns computation) but one
  or more BPS checkpoints are not yet satisfied. List the specific BPS §checkpoints
  failing and the owner responsible (JOM/VOM) for resolution. This verdict is only
  valid when the defect is in *realization* by the engine, never when the pose
  itself cheated.
- **Rejected** — any §4 failure condition triggered, or BPS/JOM/VOM cannot be
  satisfied. Cite the specific BPS §section, JOM owner, or VOM domain violated,
  and the forbidden-logic item if applicable.

An audit that ends without one of these three explicit verdicts, or without the
referenced sections, is itself incomplete and must be redone.

> Documentation only. No engine code, pose files, carriers, or other RFCs are
> modified by this document.
