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
> Every pose must pass **measurable acceptance criteria** tied to the canonical
> reference documents. A review is a checklist verdict, not a feeling.
>
> **Reference set (seven documents).** A pose is judged against all of:
> - **BPS** — Biomechanical Pose Specification (per-exercise target)
> - **JOM** — Joint Ownership Matrix
> - **MOM** — Movement Ownership Matrix
> - **MSS** — Movement Sequence Specification
> - **VOM** — Validation Ownership Matrix
> - **PRP** — Pose Responsibility Protocol
> - **PAC** — Pose Acceptance Criteria (this document; the acceptance gate)
>
> The seven documents (BPS, JOM, VOM, PRP, PAC, MOM, MSS) form one coherent
> specification system. PAC is the acceptance gate, not a separate reference input.

---

## 1. Philosophy

A pose is accepted **only if it satisfies, in order:**

1. **BPS** — the Biomechanical Pose Specification for that exercise (the
   human-biomechanics target, with its explicit checkpoints and acceptance
   boxes).
2. **JOM** — the Joint Ownership Matrix (every joint built by exactly its
   assigned owner, no cross-owner mutation).
3. **MOM** — the Movement Ownership Matrix (every movement's primary driver,
   contributors, and passive followers are correct; followers do not lead).
4. **MSS** — the Movement Sequence Specification (the Preparation → Initiation
   → Propagation → Stabilization → Completion → Recovery order is correct and
   no §7 failure pattern occurs).
5. **VOM** — the Validation Ownership Matrix (every feature certified by
   exactly its assigned validation domain, in the deterministic order).
6. **Engine integration** — the engine performed all computation (IK, FK,
   contacts, balance, spine/head/foot/hand reconstruction) with no pose-side
   duplication.
7. **Visual inspection** — the last input; it may only flag a possible
   reference violation, never accept or reject on aesthetics.

**No visual judgement alone is sufficient to accept or reject a pose.** Visual
impression is the *last* input, and it may only flag a possible reference
violation (see §6) — it never overrides a passing BPS/JOM/MOM/MSS/VOM
verdict, and it never introduces an arbitrary aesthetic change.

---

## 2. Acceptance Pipeline

Every pose review follows this fixed pipeline. Each stage must be recorded as
pass/fail before the next begins.

```
1. Biomechanical Specification (BPS)
        ↓
2. Joint Ownership validation (JOM)
        ↓
3. Movement Ownership validation (MOM)
        ↓
4. Movement Sequence validation (MSS)
        ↓
5. Validation Ownership (VOM)
        ↓
6. Engine integration
        ↓
7. Visual inspection
```

- **Stage 1 (BPS)** confirms the pose realizes the correct human biomechanics.
- **Stage 2 (JOM)** confirms each joint was built by its sole owner with no
  cross-owner mutation across the family.
- **Stage 3 (MOM)** confirms the movement driver, contributors, and passive
  followers are correct and a stabilizer is not leading (MOM §4).
- **Stage 4 (MSS)** confirms the Preparation → Initiation → Propagation →
  Stabilization → Completion → Recovery order is correct and no §7 failure
  pattern occurs (proximal-before-distal, stable-base-before-reach).
- **Stage 5 (VOM)** confirms each validation domain passes in the deterministic
  order (Balance → Contacts → Pelvis → Spine → Head → Shoulders → Arms →
  Hands → Hips → Knees → Feet → Symmetry).
- **Stage 6 (Engine integration)** confirms the engine performed all computation
  (IK, FK, contacts, balance, spine/head/foot/hand reconstruction) with no
  pose-side duplication.
- **Stage 7 (Visual inspection)** is **LAST**. It may only report possible
  BPS/JOM/MOM/MSS/VOM violations; it cannot accept or reject on aesthetics.

A pose cannot reach stage 5 until stages 1–4 are recorded as passing.

---

## 3. Required Checks

For **every** pose, the reviewer explicitly verifies each of the following and
records **pass** or **fail** (no omission, no "n/a" without justification):

| Check | Reference | Pass condition |
|---|---|---|
| **Head** | BPS §4, MOM, MSS, VOM Head | Gaze, cervical alignment, chin, head position match BPS; MOM driver not the head; MSS sequence correct; VOM Head domain passes. |
| **Spine** | BPS §5, MOM, MSS, VOM Spine | Lumbar/thoracic/rib-cage/pelvis posture match BPS; MOM: spine is stabilizer not initiator; MSS sequence correct; VOM Spine passes. |
| **Pelvis** | BPS §5/§7, MOM, MSS, VOM Pelvis | Pelvic tilt/list/rotation/level match BPS; MOM: pelvis stabilizer; MSS: stabilizes before limb; VOM Pelvis passes. |
| **Shoulders** | BPS §6, MOM, MSS, VOM Shoulder | Glenohumeral centration, girdle carriage match BPS; MOM driver/contributors correct; MSS sequence correct; VOM Shoulder passes. |
| **Elbows** | BPS §6, MOM, MSS, VOM Arm | Flexion/tuck/flare match BPS; MOM: elbow is follower of shoulder; MSS: lags shoulder; VOM Arm passes. |
| **Wrists** | BPS §6, MOM, MSS, VOM Wrist/Hand | Wrist orientation neutral per BPS; MOM: wrist last link; VOM Wrist/Hand passes. |
| **Hands** | BPS §6/§8, MOM, MSS, VOM Wrist/Hand | Palm/footprint/contact match BPS; MOM: hand follower; VOM Wrist/Hand passes. |
| **Hips** | BPS §7, MOM, MSS, VOM Hip | Femur orientation/ROM match BPS; MOM: hip driver for leg; MSS: hip leads leg; VOM Hip passes. |
| **Knees** | BPS §7, MOM, MSS, VOM Knee | Tibia-vs-femur, tracking, extension match BPS; MOM: knee follower; MSS: lags hip; VOM Knee passes. |
| **Feet** | BPS §7/§8, MOM, MSS, VOM Foot | Ankle/foot placement/toe direction/contact match BPS; MOM: foot last; MSS: foot stabilizes before hip extension; VOM Foot passes. |
| **Contacts** | BPS §8, MOM, MSS, VOM Contact | Each contact surface/pressure/stability matches BPS; MOM: contact is anchor; VOM Contact passes. |
| **Balance** | BPS §3, MOM, MSS, VOM Balance | COM within base of support; MOM/MSS: balance before reach; VOM Balance passes. |
| **Symmetry** | BPS §3/§11, MOM, MSS, VOM Symmetry | Left/right mirror equivalence; VOM Symmetry passes. |
| **Motion** | BPS (motion/rep), PDP §5 [7], this §4 | For any pose specified to move, a motion-range test passes: the body actually travels through the rep (e.g. chest/torso Y delta across `progress` 0→1 exceeds a meaningful floor-to-top threshold). If no motion test exists for the family, it MUST be created first; validator green alone does not satisfy Motion. |

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
- **Static or motion-untested moving pose** — a pose specified to move either animates nothing
  (chest/torso travel through the rep is ~0) or has no motion-range test. Validator green is not
  sufficient: the `ExerciseValidator` asserts per-frame validity, not motion (PDP §5 [7]; see PR #195).
  Accept only with a passing motion-range test.

Rejection is unconditional: a "looks fine otherwise" does not rescue a pose that
triggers any failure condition.

---

## 5. Completion Definition

A pose is **COMPLETE** only if **all** of the following are true:

- ✓ All **BPS** checkpoints satisfied (every §11 checkpoint and §13 acceptance
  box passes for that exercise).
- ✓ All **JOM** owners satisfied (every joint built by its sole owner; no
  cross-owner mutation; mount continuity holds).
- ✓ All **MOM** movement owners correct (the primary driver leads; contributors
  assist; passive followers and stabilizers do not initiate — MOM §4).
- ✓ All **MSS** sequences correct (Preparation → Initiation → Propagation →
  Stabilization → Completion → Recovery in proximal-to-distal order; no §7
  failure pattern; timing lags present — MSS §3/§6).
- ✓ All **VOM** domains pass (in the deterministic order of §2 / VOM §3).
- ✓ **No forbidden logic** present (none of the §4 failure conditions; PRP §4
  clean).
- ✓ **Engine performs all computation** (IK, FK, contacts, balance, spine/
  head/foot/hand reconstruction, validation — none duplicated in the pose).
- ✓ **Visual review agrees** (stage 7 reports no open BPS/JOM/MOM/MSS/VOM
  violation).

Completeness is a boolean over all of the above conditions. If any is false, the pose is
not complete.

---

## 6. Visual Review

Visual inspection (stage 7) is permitted **only** to report:

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

- **Accepted** — all BPS checkpoints, all JOM owners, all MOM movement
  owners, all MSS sequences, all VOM domains pass; no forbidden logic;
  engine performed all computation; visual review clean. Cite the BPS file +
  §sections, the JOM groups used, the MOM driver(s), the MSS sequence,
  and the VOM domains passed.
- **Accepted with biomechanical issues** — the pose is structurally owned
  correctly (JOM/MOM/MSS/VOM clean, no forbidden logic, engine owns
  computation) but one or more BPS checkpoints are not yet satisfied. List
  the specific BPS §checkpoints failing and the owner responsible (JOM/MOM/
  MSS/VOM) for resolution. This verdict is only valid when the defect is
  in *realization* by the engine, never when the pose itself cheated.
- **Rejected** — any §4 failure condition triggered, or BPS/JOM/MOM/MSS/
  VOM cannot be satisfied. Cite the specific BPS §section, JOM owner,
  MOM driver, MSS phase, or VOM domain violated, and the forbidden-logic
  item if applicable.

An audit that ends without one of these three explicit verdicts, or without the
referenced sections, is itself incomplete and must be redone.

---

## 8. Cyclic Acceptance

A pose or family is **Accepted only if every verification passes at least once
through a full cycle** of the §2 pipeline (BPS → JOM → MOM → MSS → VOM → Engine
integration → Visual). A single partial run is not sufficient; the complete
verification set must be exercised end-to-end and reach a passing verdict.

**Procedure.**
1. Run the full §2 pipeline; record every stage pass/fail.
2. If every stage passes, the pose has completed **one full passing cycle** →
   **Accepted**.
3. If any stage fails, resolve it through the correct owner (BPS/JOM/MOM/MSS/VOM
   or the engine) and re-run the full pipeline. Re-acceptance requires another
   full passing cycle, not a patched single stage.
4. The acceptance verdict is issued only after a complete cycle is green.

Oscillation across re-runs (a fix in one domain re-breaking another) is a
§4 failure condition: the pose is **Rejected**, never accepted with issues used
to paper over instability.

This rule binds both the single-pose and family workflows defined by
`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`: acceptance always requires one
full passing cycle, never a single green stage.

---

## 9. Reference Document Set

PAC judges a pose against the **complete** canonical set. No document may be
applied in isolation:

- **BPS** (`docs/Biomechanical Pose Specification (BPS)/<Exercise>.md`)
  — the human-biomechanics target.
- **JOM** (`docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md`) — joint
  ownership.
- **MOM** (`docs/architecture/Movement Ownership Matrix.md`) — movement
  ownership (driver / contributors / followers).
- **MSS** (`docs/architecture/Movement Sequence Specification.md`) — movement
  sequence (phase order / timing).
- **VOM** (`docs/architecture/Validation_Ownership_Matrix.md`) — validation
  domain ownership and order.
- **PRP** (`docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md`) — pose
  vs engine responsibility boundary (forbidden logic, §4).
- **PAC** (this document) — the acceptance gate that binds them.

If any document in this set is missing, ambiguous, or disagrees with the
pose code, the document is resolved **first** (PRP §5) before the pose is
judged. A pose that satisfies the set in part but contradicts any single
document is **Rejected**, not partially accepted.

> Documentation only. No engine code, pose files, carriers, or other RFCs are
> modified by this document.
