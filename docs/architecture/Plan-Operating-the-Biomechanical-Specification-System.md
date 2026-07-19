# Plan: Operating the Biomechanical Specification System

> **Scope.** This plan describes *how to operate* the documentation system created
> this session. It is a usage manual, not a new RFC. It covers the variant
> commands an engineer or auditor may run against any pose:
>
> 1. Redesign the complete family
> 2. Audit and redesign a pose using the MonkEngine methodology
> 3. Audit only some domain
> 4. Run PAC / BPS / JOM / VOM / PRP / MOM / MSS on some pose
>
> All steps reference only the canonical documents:
> - **BPS** — Biomechanical Pose Specification (per-exercise target)
> - **JOM** — Joint Ownership Matrix
> - **VOM** — Validation Ownership Matrix
> - **PRP** — Pose Responsibility Protocol
> - **PAC** — Pose Acceptance Criteria
> - **MOM** — Movement Ownership Matrix
> - **MSS** — Movement Sequence Specification

---

## 0. Prerequisites

Before any variant, confirm the canonical documents exist and are the source of
truth:

- `docs/Biomechanical Pose Specification (BPS)/<Exercise>.md`
- `docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md` (JOM)
- `docs/architecture/Validation_Ownership_Matrix.md` (VOM)
- `docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md` (PRP)
- `docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md` (PAC)
- `docs/architecture/Movement Ownership Matrix.md` (MOM)
- `docs/architecture/Movement Sequence Specification.md` (MSS)

If a referenced document or BPS file is missing or ambiguous, resolve the document
first — never infer ownership from the pose code (PRP §5).

---

## Variant 1 — Redesign the complete family

**When to use.** A whole exercise family (e.g. all push-ups, all squats, all
pulls) is biomechanically wrong, inconsistent, or was authored before the
specification system existed.

**Steps.**

1. **BPS pass (per variant).** For every family member, ensure a correct BPS
   file exists (or rewrite it). Each variant must have distinct, accurate
   biomechanics (hand width, elbow tuck, contact set, ROM, checkpoints).
2. **JOM ownership audit.** For each variant, confirm every joint is built by its
   sole JOM owner; no cross-owner mutation across the family.
3. **MOM/MSS audit.** Confirm the movement driver and sequence are correct and
   consistent across the family (e.g. all push-up variants share the same driver:
   chest/trunk leading the rigid plank; only elbow tuck/width differ).
4. **PRP compliance sweep.** Scan every variant for forbidden logic (PRP §4):
   compensation, magic offsets, duplicated IK/FK, manual balance/contacts/
   stabilization. Reject any variant that contains them.
5. **Redesign (pose declares intent only).** Rewrite each pose to declare ROM
   intent, limb targets, contacts, pelvis orientation, gaze, timing, support
   transitions — nothing the engine owns (PRP §2/§3).
6. **VOM validation per variant.** Run the VOM deterministic order (Balance →
   Contacts → Pelvis → Spine → Head → Shoulders → Arms → Hands → Hips →
   Knees → Feet → Symmetry) for each.
7. **PAC gate.** Each variant must satisfy PAC §5 (all BPS checkpoints, all JOM
   owners, all VOM domains, no forbidden logic, engine owns computation, visual
   review agrees).
8. **Cross-variant consistency check.** Variants should differ only in the
   dimensions the BPS distinguishes (width, elevation, knee vs toe pivot); the
   underlying driver/sequence (MOM/MSS) must be identical unless the BPS says
   otherwise.

**Exit.** All family members pass PAC; family shares a coherent MOM/MSS story.

---

## Variant 2 — Audit and redesign a pose (full MonkEngine methodology)

**When to use.** A single pose is suspected wrong, or must be brought into
compliance from scratch.

**Steps (full pipeline).**

1. **BPS anchor.** Open the pose's BPS file. If absent or stale, write/repair
   it first (13-section structure). This is the target.
2. **MOM/MSS read.** Identify the pose's primary driver (MOM) and its sequence
   (MSS §5). Record the expected Preparation → Initiation → Propagation →
   Stabilization → Completion → Recovery.
3. **JOM read.** List which JOM groups build the joints the pose uses (Trunk /
   Girdle / Arm / Lower-Body).
4. **PRP self-check.** Confirm the pose declares intent only (PRP §2) and
   contains no forbidden logic (PRP §4).
5. **Audit (VOM order).** Walk the VOM domains in order; for each, mark
   pass/fail against the BPS checkpoints. A fail is recorded with the specific
   BPS §section, JOM owner, and VOM domain.
6. **Diagnose ownership.** For each fail, use JOM/MOM/MSS to name the *owner*
   responsible — never "the pose looks wrong." If ownership is unclear → reject
   per PRP §5.
7. **Redesign (intent only).** Fix the pose by correcting *declared intent*
   (ROM, target, contact, pelvis/gaze intent, timing, support transition). If
   the defect is in realization, the fix belongs to the engine, not the pose —
   route it, do not hack the pose.
8. **Re-validate.** Re-run VOM; re-run PAC §5.
9. **Verdict.** End with exactly one PAC §7 verdict: Accepted / Accepted with
   biomechanical issues / Rejected, citing BPS/JOM/VOM sections.

**Exit.** Single PAC verdict recorded; all references cited.

---

## Variant 3 — Audit only some domain

**When to use.** A targeted review: e.g. "check only the shoulder girdle," or
"only balance + contacts," or "only the knee."

**Steps.**

1. **Select domain(s).** Choose from the VOM domains: Balance, Contacts,
   Pelvis, Spine, Head/Neck, Shoulder girdle, Arm (Elbow), Wrist/Hand, Hip,
   Knee, Foot/Ankle, Symmetry.
2. **Read only the relevant BPS sections** for that domain (e.g. Knee → BPS §7
   Lower body + §11 checkpoints mentioning knee + §13 boxes).
3. **Confirm upstream validity (VOM dependency).** A domain may only be audited
   after its upstream dependencies are valid (VOM §4 / MSS §3). If Balance or
   Contacts are unvalidated and your domain depends on them, audit those first or
   flag the dependency as a precondition.
4. **Apply the owner rule.** Validate the domain using its sole VOM owner. Do
   not cross into a sibling domain (VOM matrix "Does NOT own").
5. **Record pass/fail** with specific BPS §section + JOM owner + VOM domain.
6. **Conflict rule.** If the finding implicates a sibling domain, route it to
   that owner; do not fix it in place (VOM §6).
7. **Partial verdict.** Report domain-level pass/fail. A full PAC verdict is
   deferred until all required domains are audited.

**Exit.** Domain-scoped report; upstream preconditions noted; no cross-domain
edits.

---

## Variant 4 — Run a single document check on some pose

**When to use.** A lightweight, single-lens review: "run BPS on push-up," or
"run JOM on squat," etc. Each document is a self-contained lens.

### 4.1 Run BPS on a pose
- Open `BPS/<Exercise>.md`.
- Walk §11 Visual Checkpoints and §13 Acceptance Criteria; mark each pass/fail.
- Output: a checklist verdict against the BPS only. No engine/JOM claim.

### 4.2 Run JOM on a pose
- Open JOM. Identify every joint the pose uses and its assigned owner/group.
- Verify: one owner per joint; no expert modified another's joint; mount
  continuity holds (JOM §5 invariants).
- Output: per-joint owner confirmation or an ownership violation (with the joint
  and the conflicting group).

### 4.3 Run VOM on a pose
- Open VOM. Select the relevant validation domain(s).
- Walk the deterministic order (VOM §3) for those domains; apply the matrix
  ("Owns" / "Does NOT own").
- Output: per-domain pass/fail; conflicts resolved by "owner wins" (VOM §6).

### 4.4 Run PRP on a pose
- Open PRP. Check §2 (pose responsibilities — is it intent-only?) and §4
  (forbidden logic scan).
- Output: PRP-compliant, or a list of forbidden items found (with PRP §4
  citation). Any hit → reject per PAC §4.

### 4.5 Run MOM on a pose
- Open MOM. Identify the primary driver, contributors, passive followers, and
  "must NOT initiate" for the pose's movements (MOM §3).
- Verify the driver actually leads and followers do not (MOM §4).
- Output: driver-ownership confirmation or an anti-pattern citation (MOM §5).

### 4.6 Run MSS on a pose
- Open MSS. Walk the pose's sequence (MSS §5) phase by phase: Preparation →
  Initiation → Propagation → Stabilization → Completion → Recovery.
- Verify proximal-before-distal, stable-base-before-reach, and the timing lags
  (MSS §3/§6). Flag any §7 failure pattern.
- Output: sequence-valid, or a listed out-of-order fault (MSS §7).

### 4.7 Combine lenses
- Any single-document run may be paired with PAC §3 (Required Checks) to
  produce a partial checklist, but a *full* PAC acceptance requires all lenses
  (Variant 2).

---

## Decision Flowchart

```
What do you want to do?
│
├─ Whole family wrong/inconsistent? ───────► Variant 1 (Redesign family)
│
├─ One pose, full compliance? ────────────► Variant 2 (Audit + redesign, full methodology)
│
├─ Only specific domain(s)? ──────────────► Variant 3 (Audit domain only)
│
└─ One lens on one pose? ────────────────► Variant 4 (Run BPS/JOM/VOM/PRP/MOM/MSS)
                                          │
                                          └─ Need full acceptance? ► escalate to Variant 2 + PAC
```

---

## Cross-Cutting Rules (apply to every variant)

1. **No code changes inside a pose to fix a realization defect.** Realization
   belongs to the engine (PRP §3). Route it; do not hack the pose.
2. **Ownership is exclusive.** Every correction has exactly one owner via
   BPS+JOM+MOM+VOM. Unclear ownership → reject (PRP §5, PAC §4).
3. **VOM order is mandatory.** Later domains assume earlier ones valid (VOM §3).
4. **Visual review is last and limited.** It may only report possible BPS/JOM/VOM
   violations, never arbitrary aesthetic changes (PAC §6).
5. **Every audit ends with a verdict + cited sections** (PAC §7).
6. **Documents are the source of truth.** If pose code disagrees with BPS/JOM/
   MOM/MSS/VOM, the documents win and the pose (or engine) is corrected.
