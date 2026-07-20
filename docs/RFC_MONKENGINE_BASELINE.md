# RFC_MONKENGINE_BASELINE.md — MonkEngine Document Governance & Source of Truth

**Status:** ACTIVE
**Position in graph:** root — the governance source of truth all other MonkEngine RFCs extend.
**Scope:** documentation governance only. No engine, pose, or validator code is described or modified by this document.

---

## 1. Purpose

MonkEngine has a fixed architecture and a fixed set of governing documents. Over time a large
corpus of prior-generation design notes, audits, and plans accumulated. That corpus is now a
liability: it can contradict the live architecture and create duplicate or stale claims about what
is authoritative.

This document establishes a single, current **source of truth** and a clean classification of every
governing document so that future work is driven by the live architecture, not by superseded plans.

---

## 2. Scope and Boundaries

**This RFC defines:**
- the canonical ACTIVE document set (the source of truth),
- the classification of every other document (ARCHIVE / OBSOLETE / MERGE / DELETE),
- the standing governance principles that override any prior-generation note,
- the single forward-work list.

**This RFC does NOT define:**
- biomechanics — see `BIOMECHANICS.md` and the BPS specs,
- architecture — see `ARCHITECTURE_V2.md` and its companions,
- pose-development workflow — see `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`,
- execution strategy (levels, strictness) — see `RFC_MONKENGINE_EXECUTION_MODES.md`.

---

## 3. Active Document Set (Source of Truth)

Every other document is derived from, archived under, or removed by this set.

| Document | Role |
| --- | --- |
| `docs/ARCHITECTURE_V2.md` | Definitive implementation spec (pipeline, ownership, invariants). |
| `docs/ARCHITECTURE_FREEZE.md` | Constitutional freeze of the architecture. |
| `docs/ENGINE_ARCHITECTURE.md` | Entry-point map + reference (joints, stages, file map). |
| `docs/ENGINE.md` | Philosophy, four layers, coordinate/axis/IK conventions. |
| `docs/CODING_RULES.md` | Permanent standing rules for all development. |
| `docs/BIOMECHANICS.md` | Human-movement correctness principles. |
| `docs/VALIDATION.md` | Validation-pose contract + Engineering Validation subsystem. |
| `docs/API_CONTRACTS.md` | Per-component read/write/prohibited contracts. |
| `docs/MIGRATION_RULES.md` | Prohibited patterns + mandatory pose rules (the enforced pose coding standard). |
| `docs/STABILIZATION_AUDIT.md` | Live per-family production-exercise stabilization tracker (forward work). |
| `docs/TOOLCHAIN_PROVISIONING.md` | Build/CI toolchain recipe (operational, required each session). |
| `docs/architecture/Movement Ownership Matrix.md` | Biomechanical movement ownership (driver/contributor/follower). |
| `docs/architecture/Movement Sequence Specification.md` | Canonical movement ordering. |
| `docs/architecture/Validation_Ownership_Matrix.md` | Which subsystem certifies each feature. |
| `docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md` | Per-joint biomechanical ownership. |
| `docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md` | Measurable pose acceptance criteria (PAC). |
| `docs/RFC_MONKENGINE_BASELINE.md` | This document (governance source of truth). |
| `docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` | Pose-development workflow (PDP). |
| `docs/architecture/RFC_MONKENGINE_EXECUTION_MODES.md` | Execution strategy (levels, strictness). |
| `docs/RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md` | Practical engineering handbook (how-to for each task class). |

> **Biomechanical specification system (seven documents).** The pose-development RFCs above
> operate on a coherent seven-document set: **BPS** (Biomechanical Pose Specification — per-exercise
> files under `docs/Biomechanical Pose Specification (BPS)/`), **JOM** (Joint Ownership Matrix),
> **VOM** (Validation Ownership Matrix), **PRP** (Pose Responsibility Protocol), **PAC** (Pose
> Acceptance Criteria), **MOM** (Movement Ownership Matrix), **MSS** (Movement Sequence
> Specification). These are the canonical reference inputs for every pose task. |

---

## 4. Document Classification

Every document outside §3 is classified into exactly one of:

| Class | Meaning | Treatment |
| --- | --- | --- |
| **ARCHIVE** | Prior-generation record kept for context. Explains *why* a decision was made. | Read-only. May inform understanding; never constrains new design. |
| **OBSOLETE** | Superseded by the current architecture. | Do not cite as guidance. A construction it describes that still exists in code is a candidate for simplification. |
| **MERGE** | Content worth keeping should be folded into an ACTIVE document, then the original retired. | Merge, then delete. |
| **DELETE** | Completed prior-generation work or temporary planning with no forward value. | Remove from the active tree. |

> The ARCHIVE is never auto-loaded by agents (see `RFC_MONKENGINE_EXECUTION_MODES.md` §5). When an
> archived or obsolete document conflicts with live code, the code wins.

### 4.1 ARCHIVE (retained for context, not normative)
The prior-generation record set: the engine-history index, its inventory readme, and the
investigation reports, family rewrite records, implementation reports, and audit reports held
alongside them. Retained as retrievable evidence of past decisions; they do not govern future design.

### 4.2 OBSOLETE (superseded; do not influence design)
Prior-generation plans and redesign notes whose content is now shipped and described by the ACTIVE
set. Their decisions are reflected in the ACTIVE architecture; the plan text no longer steers.

- `docs/architecture/Plan-Operating-the-Biomechanical-Specification-System.md` — an early-stage
  usage manual with its own Variant 1–4 workflow and user-request examples. Superseded by
  `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` (workflow) and
  `RFC_MONKENGINE_EXECUTION_MODES.md` (levels, strictness, directive syntax, and user-request
  examples in §4.1). Its example prompts were folded into `RFC_MONKENGINE_EXECUTION_MODES.md` §4.1.

### 4.3 MERGE (fold into ACTIVE, then retire)
The pipeline-design note folds into `ARCHITECTURE_V2.md` §3; the execution-contract note folds into
`API_CONTRACTS.md`; the implementation-bridge note folds into `ARCHITECTURE_V2.md` / `API_CONTRACTS.md`;
the engine roadmap folds into §6 of this document; the engine-history index folds into §4.1 of this
document.

### 4.4 DELETE (completed prior-generation work)
One-shot plans, audits, and reports whose work is merged and whose content is captured elsewhere.
Remove from the active tree.

---

## 5. Standing Governance Principles

These principles override any prior-generation note, comment, or habit.

1. **Prior-generation decisions are not design constraints.** A choice made to land an earlier plan
   is evidence of what was done, never of what must be done next.
2. **Prefer the simpler solution on the current architecture.** Use the clean primitive the engine
   already provides; do not reproduce a more complex construction that only existed because the
   engine lacked the capability at an earlier time.
3. **Do not preserve old constructions solely because they predate the current architecture.** A
   pattern that survives only as a fossil of an earlier era is a candidate for simplification or
   removal.
4. **Backward compatibility with previous authoring patterns is not a design goal.** New poses and
   refactors are written against the current carrier/intent model.
5. **The current architecture is the only source of truth.** `ARCHITECTURE_V2.md` (and the ACTIVE
   set in §3) decides correctness. When any document conflicts with live code, the code wins.

---

## 6. Forward Work

The single live forward-looking list. Future design is driven by this, not by prior-generation plans.

| Id | Item | Layer |
| --- | --- | --- |
| UNI-9 | Surface (don't silently bend) a `straight=true` limb whose target sits inside proximal-bone length. | Engine + Validation |
| UNI-12 | Confirm natural-supportability claim for Front Split, Cossack, Bulgarian, Pistol, Single-leg RDL, Horse Stance, etc. | Pose / Validation |
| Trunk DOFs in solver | Make `CHEST`/`LUMBAR` free DOFs for trunk-contact poses. | Engine |
| Generalized contacts | Principled multi-contact topologies (seated hip + planted hands). | Engine |
| Deeper motion continuity | First-class shared motion/easing model. | Pose |
| P1/P2 stabilization | The open TODOs in `STABILIZATION_AUDIT.md` (wall geometry, step contact, cobra/superman lumbar, kettlebell hinge, burpee foot translation, stretch-family contacts, cleanup). | Pose |

---

## 7. Relationship to Other MonkEngine RFCs

```
ARCHITECTURE_FREEZE.md ── freezes ──► ARCHITECTURE_V2.md
        │
        ├──── CODING_RULES.md / MIGRATION_RULES.md / API_CONTRACTS.md
        │
        └──── RFC_MONKENGINE_BASELINE.md  (governance source of truth, document classification)  ◄ ROOT
                    │
                    ├── RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md  (workflow: what to do, in order)
                    └── RFC_MONKENGINE_EXECUTION_MODES.md            (execution strategy: how far to go)
```

- **BASELINE** defines *what is authoritative* and *how documents are classified*.
- **POSE_DEVELOPMENT_PROTOCOL** defines the *workflow* an agent follows; it loads the ACTIVE set
  named here and obeys the §5 principles.
- **EXECUTION_MODES** defines the *degree of freedom* (levels, strictness) available to a task; it
  references the workflow and the source of truth defined here.

All three reference each other; none duplicates the others' concepts (governance vs. workflow vs.
strategy).

---

## 8. Terms

- **ACTIVE set** — the documents in §3; the current source of truth.
- **ARCHIVE** — prior-generation records retained read-only for context (§4.1).
- **OBSOLETE / MERGE / DELETE** — classification outcomes for documents outside the ACTIVE set (§4).
- **Forward work** — the open-item list in §6; the only live tracker of future design.

(Terms specific to workflow are defined in `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`; terms
specific to execution strategy are defined in `RFC_MONKENGINE_EXECUTION_MODES.md`.)

---

## 9. Status

**ACTIVE.** Root of the MonkEngine documentation graph. All other MonkEngine RFCs extend this
document. It supersedes any informal "source of truth" or document-classification guidance found in
prior-generation notes. It is the canonical governance specification.

---

*End of RFC_MONKENGINE_BASELINE.md.*
