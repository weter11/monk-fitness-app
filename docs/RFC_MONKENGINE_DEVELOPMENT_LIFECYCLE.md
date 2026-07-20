# RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md — The Engineering Lifecycle

**Status:** ACTIVE
**Position in graph:** `docs/RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` — the Development Lifecycle
node of the MonkEngine Development System (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`). Realized by the
Development Orchestrator.
**Scope:** engineering workflow only. Describes the complete lifecycle of an engineering task from
idea to captured knowledge. No engine, pose, or validator code is described or modified by this
document.

---

## 1. Purpose of This Document

Every MonkEngine engineering task moves through one lifecycle. The Development Orchestrator is the
authority that runs it; this document defines what each stage *is* — its purpose, inputs, outputs,
exit criteria, and required artifacts — so that any task, of any capability level, is executed the
same way.

```
Idea
    ↓
Classification
    ↓
Planning
    ↓
Expert Review
    ↓
Implementation
    ↓
Verification
    ↓
Acceptance
    ↓
Knowledge Capture
```

This is the *workflow* spine. It is distinct from the **Authority Hierarchy** (who overrides whom)
and the **Execution Hierarchy** (request → implementation order) defined in
`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`; all three are consistent and none contradicts the others.

> This document describes **engineering workflow only**. It never mentions implementation — no
> code, no solver, no pose syntax. It says what must happen at each stage and what must exist for
> the stage to be complete.

---

## 2. Stage 0 — Idea

- **Purpose.** Capture a need or opportunity: a new exercise, a redesign, a validator, a
  documentation gap, an engine evolution. The Idea is the raw signal before any decision is made.
- **Inputs.** A user request, an observed defect, a forward-work item (e.g.
  `STABILIZATION_AUDIT.md`), or an internal improvement proposal.
- **Outputs.** A normalized task record: the stated intent and any explicit constraints (protocol,
  level, strictness) the requester provided.
- **Exit criteria.** The Idea is recorded as a task with a clear statement of *what* is wanted. If
  the statement is ambiguous, the ambiguity is flagged for resolution at Classification — it is not
  guessed.
- **Required artifacts.** The task record (transient working note; not a repository document).

---

## 3. Stage 1 — Classification

- **Purpose.** Decide what the task *is*. The Orchestrator determines the task category and the
  required capability level (`RFC_MONKENGINE_CAPABILITY_LEVELS.md`), and resolves any ambiguity from
  the Idea stage.
- **Inputs.** The task record from Stage 0; the Design Principles and Baseline (to know what is
  authoritative); the Capability Levels scale (to pick the level).
- **Outputs.** A classification decision: task category, required capability level (0–8), and the
  implied strictness. The set of documents and experts the task will require is enumerated but not
  yet loaded.
- **Exit criteria.** The task has exactly one category and one capability level assigned, and the
  ambiguity (if any) from the Idea stage is resolved. The Orchestrator's decision is recorded.
- **Required artifacts.** The classification decision (transient working note).

---

## 4. Stage 2 — Planning

- **Purpose.** Before any change, experts produce a plan: the approach, the ownership map (who owns
  each responsibility the task touches), the risks, and the Definition of Done items the work will
  satisfy. Planning is done *from biomechanics and specifications*, never from existing code.
- **Inputs.** The classification decision; the required documents for the level
  (`RFC_MONKENGINE_CAPABILITY_LEVELS.md`); the relevant specifications (BPS/MSS/MOM/JOM/VOM/PRP/PAC).
- **Outputs.** A plan that names: the approach, the ownership map, the risk list, and the mapped
  DoD items. The plan is the contract the Implementation stage will execute.
- **Exit criteria.** A plan exists that covers the task's scope, assigns every touched
  responsibility to exactly one owner, and maps each to a DoD item. The plan is ready for review.
- **Required artifacts.** The plan (transient working note; merged into the implementation later,
  never committed as a standalone repository file).

---

## 5. Stage 3 — Expert Review

- **Purpose.** The Orchestrator routes the plan to the reviewers for the affected domains. Review
  confirms the plan is biomechanically correct, architecturally sound, and within the assigned
  capability level. Conflicts between experts are resolved before any execution.
- **Inputs.** The plan; the owning experts for the touched domains; the authority chain
  (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §3) for conflict resolution.
- **Outputs.** A review verdict (approve, or return-with-corrections) and a resolved conflict log
  (if any). The plan may iterate: Review → Planning → Review until approved.
- **Exit criteria.** The plan is approved by the required experts, all conflicts are resolved by
  the authority hierarchy (never by averaging or by lowering a principle), and no expert's
  ownership was overridden by a non-owner.
- **Required artifacts.** The review comments and the conflict-resolution log (transient working
  notes).

---

## 6. Stage 4 — Implementation

- **Purpose.** Execute the approved plan. Coding begins **only** here, and only after Planning and
  Expert Review are complete. Experts implement in the Orchestrator-decided order; parallelizable
  experts run together; all output is merged into the single implementation.
- **Inputs.** The approved plan; the loaded required documents; the resolved conflict log.
- **Outputs.** The merged implementation — the change to code, pose, validator, or document that
  the task produces. Every responsibility is exercised by its single owner.
- **Exit criteria.** The plan is executed; the implementation realizes the biomechanical intent
  through the engine (no pose-side duplication of FK/IK/constraints); ownership boundaries are
  respected; the result is coherent and merged (not a stack of expert opinions).
- **Required artifacts.** The implementation (the durable change). No transient expert report
  becomes a repository file (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §7).

---

## 7. Stage 5 — Verification

- **Purpose.** Mandatory final verification, run by the Orchestrator before acceptance. It proves
  the implementation is real, not assumed.
- **Inputs.** The implementation; the required specifications; the Definition of Done.
- **Outputs.** A verification record: compile state, baseline result, objective checks per
  category, ownership audit, artifact audit, determinism check.
- **Exit criteria.** All of the following hold:
  1. **Compile** — the repository is left in a building state (Compile-First policy).
  2. **Green baseline** — the relevant automated verification passes; no pre-existing green check
     was broken.
  3. **Objective checks** — every category-specific criterion is confirmed with measurable
     evidence, not impression.
  4. **Ownership audit** — no duplicated ownership was introduced; every change sits within a
     single owner's responsibility.
  5. **Artifact audit** — no temporary expert report became a repository file.
  6. **Determinism** — the result is reproducible from the same inputs.
- **Required artifacts.** The verification record (transient working note; its conclusion is
  carried into Acceptance).

---

## 8. Stage 6 — Acceptance

- **Purpose.** Apply the Definition of Done as the binding gate. The task is accepted only if every
  DoD item passes.
- **Inputs.** The verification record; the Definition of Done (`RFC_MONKENGINE_DEFINITION_OF_DONE.md`)
  — Universal criteria plus the category-specific criteria for the task's category.
- **Outputs.** An acceptance verdict with objective evidence: each DoD item marked pass, with the
  evidence that justifies it. A single failing item blocks acceptance.
- **Exit criteria.** Every Universal and category-specific DoD criterion is satisfied and recorded
  with evidence. The task is marked Accepted (or the failure is recorded/fixed and the lifecycle
  re-enters at the correct stage — never by lowering the gate).
- **Required artifacts.** The acceptance record (durable summary of what was verified and how).

---

## 9. Stage 7 — Knowledge Capture

- **Purpose.** Record what was learned so the system improves without creating duplicated or stale
  documents. Transient expert reports from earlier stages are discarded; only durable, governed
  knowledge is kept.
- **Inputs.** The accepted implementation; the acceptance record; any genuine forward-work or
  governance finding.
- **Outputs.** A final report (what changed, what was retained, deviations, residual risk) and, if
  warranted, a promotion of a finding into an ACTIVE document through proper governance
  (Baseline §3–§4). Transient reports are destroyed.
- **Exit criteria.** The task's transient artifacts are gone; the durable report exists; any
  needed document change has been routed through governance (not dumped raw into the repo). The
  task is closed.
- **Required artifacts.** The final report (durable). All Stage 0–5 transient notes are discarded.

---

## 10. Stage Transition Rules

- A stage may be **skipped** only when its gating condition is provably absent — never for
  convenience:
  - Read-only levels (e.g. Documentation / Small doc changes at the lowest capability levels) skip
    Implementation and its code-facing verification, but still pass through Classification,
    Planning, Expert Review, Verification (editorial), Acceptance, and Knowledge Capture.
  - Certification-style tasks skip Implementation entirely (they measure, they do not change).
- A stage may **iterate** within the lifecycle: Planning ↔ Expert Review is the normal correction
  loop; a Verification or Acceptance failure re-enters at Planning or Implementation as the failure
  dictates.
- No stage may run before its predecessor except the documented skips above. Coding never precedes
  Planning + Expert Review.

---

## 11. Relationship to Other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md           (constitution — outranks the lifecycle)
        │
RFC_MONKENGINE_BASELINE.md                   (source of truth; document classification)
        │
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md         (system map; this is the "Development Lifecycle" node)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md   (the authority that runs this lifecycle per task)
        │
RFC_MONKENGINE_CAPABILITY_LEVELS.md          (Classification picks the level here)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md         (the gate applied at Acceptance / Verification)
        │
RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md   (the workflow steps realized inside the stages)
```

- **DESIGN_PRINCIPLES** binds every stage; no stage may suspend a principle.
- **BASELINE** supplies the ACTIVE set and the classification of documents.
- **DEVELOPMENT_SYSTEM** places this document as the Development Lifecycle node.
- **ORCHESTRATOR** owns running the lifecycle and deciding stage order/parallelism.
- **CAPABILITY_LEVELS** is what Stage 1 (Classification) decides.
- **DEFINITION_OF_DONE** is the bar at Stage 5 (Verification) and Stage 6 (Acceptance).
- **PLAYBOOK (PDP)** is the workflow content executed inside the stages.

---

## 12. Terms

- **Lifecycle** — the nine stages every task passes through (Idea → … → Knowledge Capture).
- **Exit criterion** — the objective condition that closes a stage.
- **Required artifact** — what the stage must produce (durable change, or transient note that is
  later merged/discarded).
- **Transient artifact** — an expert working note that exists only during execution and is never
  committed as a repository document.

---

## 13. Status

**ACTIVE.** The Development Lifecycle node of the MonkEngine Development System. Created without
modifying any existing RFC. It is the canonical workflow spine the Development Orchestrator applies
to every engineering task.

---

*End of RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md.*
