# RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md — The Decision Engine

**Status:** ACTIVE
**Position in graph:** the Development Orchestrator node of the MonkEngine Development System
(`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`). It is the **decision engine** of the system, not a
workflow. Pairs with `RFC_MONKENGINE_CAPABILITY_LEVELS.md`,
`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`, `RFC_MONKENGINE_DEFINITION_OF_DONE.md`, and
`docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md` (PRP).
**Scope:** orchestration only. For every incoming task the Orchestrator *decides*; it does not
perform the work and does not restate the rules those other documents own. No engine, pose, or
validator code is described or modified by this document.

---

## 1. Purpose — The Orchestrator Is a Decision Engine, Not a Workflow

The MonkEngine Development System has one document that *does the work sequence* (the Development
Lifecycle) and one document that *owns the rules of acceptance* (the Definition of Done). The
Orchestrator is neither. It is the **decision engine** that, for every incoming task, decides what
the task is and what must happen to it — then delegates execution to the Lifecycle and judgement to
the Definition of Done.

The Orchestrator answers, for **any** task:

- **Task category** — what kind of work this is.
- **Capability level** — how much maturity the task requires (from `RFC_MONKENGINE_CAPABILITY_LEVELS.md`).
- **Required experts** — which single owners must act.
- **Required specifications** — which ACTIVE documents the task must load and obey.
- **Execution plan** — the stage order and parallel expert execution for the Lifecycle.
- **Review plan** — which experts review, and in what order, before execution.
- **Acceptance plan** — which Definition of Done items the task will be judged against.
- **Knowledge capture** — what durable record the task must leave, and what must be discarded.

No expert, no document, and no line of code decides these. The Orchestrator is the single
authority that does.

> The bar it enforces is `RFC_MONKENGINE_DEFINITION_OF_DONE.md`. The sequence it drives is
> `RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`. The maturity it assigns is
> `RFC_MONKENGINE_CAPABILITY_LEVELS.md`. The system it sits inside is
> `RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`. This document describes only the *decisions*; it does
> not repeat those documents' rules.

---

## 2. The Decision Function

For every incoming task the Orchestrator produces one decision record. Each field is decided, not
inherited from the requester's assumption.

### 2.1 Task category
Derived from the task's nature. Categories include (non-exhaustive): documentation / governance,
small documentation change, small pose edit, biomechanical redesign, new pose family, new exercise
implementation, validator development, engine architecture change, core engine evolution. The
category selects which other decisions apply.

### 2.2 Capability level
Assigned from `RFC_MONKENGINE_CAPABILITY_LEVELS.md` — the **lowest** level that safely covers the
task. The level then determines required experts, specifications, validation expectation, and the
DoD column the task targets. The Orchestrator does not grant a higher level than the task needs.

### 2.3 Required experts
The set of single owners the task touches. Each expert maps to exactly one responsibility
(biomechanical intent, architecture/ownership, engine/mathematics, validation, governance). The
Orchestrator lists only the experts the category and level require — no more, no fewer.

### 2.4 Required specifications
The ACTIVE documents the task must load and obey, drawn from the Baseline ACTIVE set and the level's
"required specifications". This includes the specification layer (BPS/MSS/MOM/JOM/VOM/PRP/PAC) where
the task touches movement, and the architecture/contract documents where it touches the engine.

### 2.5 Execution plan
The Orchestrator emits the stage order for `RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` and, within
the Implementation stage, the parallel expert execution and merge strategy (§4). It decides *what
runs and in what order*, not *how* the work is done.

### 2.6 Review plan
The Orchestrator decides which experts review the plan before execution, and in what order, so that
conflicts are resolved prior to any change (Lifecycle Stage 3). The review plan names the reviewers
and the conflict-resolution path (§6), not the review content.

### 2.7 Acceptance plan
The Orchestrator maps the task to the Definition of Done items it must clear — the Universal
criteria plus the category-specific criteria for its category. The acceptance plan is the check
list the Lifecycle's Verification and Acceptance stages will run; the Orchestrator does not redefine
the gate, it selects the applicable items.

### 2.8 Knowledge capture
The Orchestrator decides what durable record the task leaves (the final report and any governed
document promotion) and confirms that all transient expert reports are discarded (Lifecycle Stage 7
and the artifact rule in §5). It does not author the knowledge; it decides what is kept and what is
destroyed.

---

## 3. How the Orchestrator Drives the Lifecycle

The Orchestrator does not execute the Lifecycle; it *decides the parameters* the Lifecycle runs
with. The mapping:

| Orchestrator decision | Lifecycle stage it feeds |
| --- | --- |
| Task category + Capability level | Stage 1 Classification |
| Required specifications | Stage 2 Planning (inputs) |
| Required experts + Review plan | Stage 3 Expert Review |
| Execution plan | Stage 4 Implementation |
| Acceptance plan (as the gate) | Stage 5 Verification, Stage 6 Acceptance |
| Knowledge capture | Stage 7 Knowledge Capture |

The Lifecycle owns *what happens* at each stage; the Orchestrator owns *what the task is and what
must be true of it*. The two never overlap: if a statement is about stage mechanics, it lives in
the Lifecycle; if it is about a per-task decision, it lives here.

---

## 4. Parallel Expert Execution & Merge

Within the Lifecycle's Implementation stage, the Orchestrator decides execution shape.

### 4.1 Parallel expert execution
Experts whose responsibilities are disjoint may run in parallel. Independence permits parallelism;
shared ownership forbids it. The Orchestrator guarantees that no two parallel experts write
overlapping authority — each writes only its own owned responsibility, so their outputs cannot
collide.

### 4.2 Merge strategy
Each expert's output is merged into the single implementation, never retained as a separate
artifact. The Orchestrator enforces one coherent change:
- Expert conclusions become edits to the codebase or to ACTIVE documents.
- Conflicting conclusions are reconciled **before** merge, using the authority hierarchy
  (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §3), not by averaging.
- The merged result is one change owned by the system, not a stack of expert opinions.

---

## 5. Temporary Artifacts & Expert Reports

The Orchestrator governs where working knowledge lives during a task.

- **Expert reports** are the working notes each expert produces while planning and reviewing:
  audit notes, ownership maps, risk lists, review comments, conflict logs, verification logs.
  They exist only during execution.
- **Temporary artifacts** MUST NOT become repository files. They are merged into the implementation
  or discarded; they are never committed as standalone documents.
- At Knowledge Capture (Lifecycle Stage 7) the Orchestrator confirms all temporary artifacts are
  destroyed. If a finding is genuinely needed as durable reference, it is promoted to an ACTIVE
  document through proper governance (Baseline §3–§4) — not dumped raw into the repository.

The Orchestrator's own decision record is the durable trace of *why* the task was scoped as it was;
the expert reports are not.

---

## 6. Conflict Resolution

When experts disagree, the Orchestrator resolves using the authority hierarchy
(`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §3), applied at the Review stage before merge:

1. **Design Principles** — the constitution; outranks all.
2. **Baseline** — the source of truth and document classification.
3. The governing matrix for the disputed domain (JOM for joints, VOM for validation, MOM for
   movement, PRP for pose responsibility, PAC for acceptance).
4. The ACTIVE architecture set (ARCHITECTURE_V2 and companions).
5. Live code — when a document conflicts with working code, the code wins (Baseline §5).
6. Historical / ARCHIVE documents — never override active governance.

A conflict is never "resolved" by lowering a principle or a check. It is resolved by finding the
owner the hierarchy assigns, and that owner's verdict stands.

---

## 7. The Pose Responsibility Boundary

For any task touching a pose, the Orchestrator applies the boundary defined by
`docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md` (PRP) as a standing constraint on its
decisions: a pose declares intent; the engine realizes it. The Orchestrator's Execution plan and
Acceptance plan must therefore never assign the pose work that PRP reserves for the engine (FK, IK,
constraints, balance, spine/head/foot/hand reconstruction, validation). The Orchestrator does not
restate PRP's rules — it treats them as binding on every decision it makes about pose tasks.

---

## 8. Relationship to Other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md            (constitution — outranks the Orchestrator)
        │
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md          (the system map; this is the "Development
        │                                        Orchestrator" node)
        │
RFC_MONKENGINE_CAPABILITY_LEVELS.md           (the Orchestrator assigns the level from here)
        │
RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md        (the sequence the Orchestrator drives)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md          (the gate the Orchestrator's Acceptance plan targets)
        │
docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md  (PRP — the pose/engine boundary the
                                                        Orchestrator enforces on pose tasks)
```

- **DESIGN_PRINCIPLES** outranks every Orchestrator decision.
- **DEVELOPMENT_SYSTEM** places this document and defines the authority hierarchy used for
  conflicts.
- **CAPABILITY_LEVELS** is the source of the level the Orchestrator assigns in §2.2.
- **DEVELOPMENT_LIFECYCLE** is the workflow the Orchestrator parameterizes in §3; this document does
  not duplicate its stage mechanics.
- **DEFINITION_OF_DONE** is the acceptance gate; the Orchestrator's Acceptance plan (§2.7) only
  selects the applicable items, it does not redefine them.
- **PRP** is the pose/engine boundary enforced on pose-task decisions (§7).

---

## 9. What the Orchestrator Is NOT

To keep ownership clean:

- It is **not** a workflow. The Lifecycle owns stage mechanics.
- It is **not** an acceptance gate. The Definition of Done owns the bar.
- It is **not** a capability scale. Capability Levels owns the maturity definitions.
- It is **not** a specification. BPS/MSS/MOM/JOM/VOM/PRP/PAC own the biomechanical content.
- It is **not** an expert. It decides which experts act; it does not perform their work.

The Orchestrator decides; the system executes; the gate judges.

---

## 10. Terms

- **Decision engine** — the Orchestrator: it decides task category, capability level, experts,
  specifications, execution plan, review plan, acceptance plan, and knowledge capture.
- **Decision record** — the Orchestrator's per-task output: the eight decisions of §2.
- **Expert** — a focused authority over one responsibility (single owner per responsibility).
- **Temporary artifact / expert report** — a working note that exists only during execution and
  is merged or discarded, never committed.
- **Merge** — combining expert outputs into one coherent implementation without retaining separate
  artifacts.

---

## 11. Status

**ACTIVE.** The Development Orchestrator node of the MonkEngine Development System. Rewritten as
the system's decision engine. Created/updated without modifying any other RFC. It is the canonical
decision authority for every engineering task.

---

*End of RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md.*
