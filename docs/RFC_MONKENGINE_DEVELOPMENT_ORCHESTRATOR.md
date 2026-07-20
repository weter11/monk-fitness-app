# RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md — The Development Orchestrator

**Status:** ACTIVE
**Position in graph:** the central controller of the MonkEngine Development System.
Extends `RFC_MONKENGINE_BASELINE.md`; pairs with `RFC_MONKENGINE_EXECUTION_MODES.md` and
`RFC_MONKENGINE_DEFINITION_OF_DONE.md`.
**Scope:** the brain of the system. Defines how every engineering task is received, classified,
planned, sequenced, executed, verified, and accepted. No engine, pose, or validator code is
described or modified by this document.

---

## 1. Purpose

MonkEngine is governed by documents, not by ad-hoc habit. This document is the controller that
turns an incoming request into a fully-specified, correctly-sequenced, verifiable unit of work.

It answers, for **any** task:

- What category is this?
- What capability does it require?
- Which RFCs and specifications must be loaded?
- Which experts must act, in what order, and which may run in parallel?
- What reports are produced, and where do they live?
- What is the Definition of Done, and how is it verified?

The orchestrator is the single authority that decides these. No expert, no document, and no
line of code decides them on its own.

> This document is the controller. The bar it enforces is `RFC_MONKENGINE_DEFINITION_OF_DONE.md`.
> The freedom it grants is `RFC_MONKENGINE_EXECUTION_MODES.md`. The truth it checks against is
> `RFC_MONKENGINE_BASELINE.md`. The law it must never violate is
> `RFC_MONKENGINE_DESIGN_PRINCIPLES.md`.

---

## 2. Guiding Invariant

**The Design Principles outrank this document, the execution modes, the definition of done, and
every line of code.** When any of them conflict, the principles win. The orchestrator's job is to
operate the system without ever suspending a principle for convenience.

---

## 3. The Complete Lifecycle of a Task

Every engineering task passes through the same phases, in this order. A phase may be skipped
only when its gating condition is provably absent (e.g. a read-only certification task skips
coding) — never because it is inconvenient.

```
[0] INTAKE         Receive the request. Normalize it into a task record.
[1] CLASSIFY       Decide task category + required capability (level/strictness).
[2] ASSEMBLE       Load the required RFCs + specifications (the source of truth).
[3] PLAN           Experts produce a plan: approach, ownership map, risk, DoD mapping.
[4] REVIEW         Expert review of the plan (may iterate [3]→[4]).
[5] EXECUTE        Experts implement, in the orchestrator-decided order (some in parallel).
[6] VERIFY         Mandatory final verification (compile + green baseline + objective checks).
[7] ACCEPT         Apply the Definition of Done; record acceptance with evidence.
[8] REPORT         Summarize what changed, what was retained, deviations, residual risk.
```

### 3.1 Phase detail

- **[0] INTAKE.** Capture the task, the requested protocol/level/strictness if provided, and any
  explicit constraints. If the request is ambiguous, resolve category before proceeding — do not
  guess silently.
- **[1] CLASSIFY.** Map the request to a task category and a required capability (see §4–§5). This
  is the orchestrator's decision, not the requester's assumption.
- **[2] ASSEMBLE.** Load exactly the governing documents the capability requires
  (`RFC_MONKENGINE_EXECUTION_MODES.md` §6). ARCHIVE documents are never auto-loaded.
- **[3] PLAN.** Experts draft the plan as transient working notes (§Artifact Lifecycle). The plan
  names the ownership map, the approach, the risks, and the DoD items it will satisfy.
- **[4] REVIEW.** The orchestrator routes the plan to the reviewers for the affected domains.
  Conflicts are resolved (§6.5). The plan is not executed until review passes.
- **[5] EXECUTE.** Coding begins only here (§7). Experts run in the orchestrator-decided order;
  parallelizable experts run together. All output is merged into the implementation.
- **[6] VERIFY.** Mandatory final verification (§8). No task skips this.
- **[7] ACCEPT.** The Definition of Done (`RFC_MONKENGINE_DEFINITION_OF_DONE.md`) is applied as the
  gate. A single failing item blocks acceptance.
- **[8] REPORT.** A final, durable summary is recorded. Transient expert reports are discarded
  (§Artifact Lifecycle).

---

## 4. Task Categories

The orchestrator classifies every incoming task into exactly one category. Categories are
mutually exclusive; a task that spans more than one is decomposed.

| Category | Examples | Typical capability |
| --- | --- | --- |
| **Discovery** | "What does this pose do?", "Explain this subsystem" | Level 0 |
| **Audit** | "Check this pose against BPS/JOM/VOM", "Find drift" | Level 1 |
| **Cleanup** | "Remove dead code", "Delete obsolete helper" | Level 2 |
| **Upgrade** | "Move this pose onto the current model", "Pass validation" | Level 3 |
| **Redesign** | "Rewrite this pose from first principles" | Level 4 |
| **New Artifact** | "Create a new pose", "Author a new validator check" | Level 5 |
| **Family Work** | "Make the whole Push-Up family consistent" | Level 6 |
| **Engine Integration** | "Stop duplicating IK in poses" | Level 7 |
| **Certification** | "Certify this pose PASS/FAIL" | Level 8 |
| **Documentation / Governance** | "Write an RFC", "Reclassify a doc" | doc-level |
| **Tooling / Operational** | "Provision the toolchain", "Fix the build" | operational |

A task may also carry a **strictness** (Conservative / Balanced / Aggressive / Zero-Legacy),
defaulting to Balanced when omitted (`RFC_MONKENGINE_EXECUTION_MODES.md` §4).

---

## 5. The Orchestrator Decision Function

For every incoming task the orchestrator must decide, and record, the following. This is the
contract of the controller.

### 5.1 task category
Derived in §4. Determines which other decisions apply.

### 5.2 required Capability
The execution level (0–8) and strictness that bound the freedom of the work
(`RFC_MONKENGINE_EXECUTION_MODES.md` §3–§4). The orchestrator chooses the minimal level that
safely satisfies the request; it does not over-reach.

### 5.3 required RFCs
The governing documents that must be loaded. Determined by the level
(`RFC_MONKENGINE_EXECUTION_MODES.md` §6) plus any document the specific task touches. Always
includes `RFC_MONKENGINE_DESIGN_PRINCIPLES.md`, `RFC_MONKENGINE_BASELINE.md`, and
`RFC_MONKENGINE_DEFINITION_OF_DONE.md`.

### 5.4 required specifications
The biomechanical / architectural references the task depends on: BPS (relevant spec), JOM,
VOM, MOM, MSS, PRP, PAC, and the relevant ACTIVE architecture documents
(`RFC_MONKENGINE_BASELINE.md` §3). The orchestrator loads only those relevant to the task —
not the entire corpus.

### 5.5 required experts
The domain specialists the task needs (see §6). Selected from the ownership matrices: a pose
task needs the biomechanics expert, the architecture/ownership expert, and the validation
expert; an engine task needs the mathematics/engine expert; a documentation task needs the
governance expert.

### 5.6 required reports
The transient working artifacts each expert produces during planning and execution (§Artifact
Lifecycle): audit notes, ownership maps, risk lists, review comments, verification logs. These
are **not** repository files.

### 5.7 Definition of Done
The binding acceptance bar: `RFC_MONKENGINE_DEFINITION_OF_DONE.md`, scoped by the task's
category (§3 there). The orchestrator records, per DoD item, the evidence that satisfies it.

### 5.8 acceptance procedure
The ordered check the orchestrator runs at [7] ACCEPT:
1. Confirm required documents were loaded.
2. Confirm planning + expert review completed before coding.
3. Run mandatory final verification (§8).
4. Walk Universal + Category-Specific DoD criteria; all must pass.
5. Confirm no temporary report became a repository file.
6. Record acceptance with objective evidence.

---

## 6. Expert Orchestration

An **expert** is a focused authority over one domain of the system — a single owner in the
sense of the Design Principles. The orchestrator composes experts; it is not itself an expert.

### 6.1 The expert roster (domains, not people)

| Expert | Owns | Consults |
| --- | --- | --- |
| Biomechanics Expert | biomechanical intent, BPS/MSS correctness | JOM, MOM, PRP |
| Architecture / Ownership Expert | structure, ownership boundaries, no duplicated ownership | ARCHITECTURE_V2, MIGRATION_RULES, API_CONTRACTS |
| Engine / Mathematics Expert | FK, IK, constraints, shared mathematics | ENGINE, ENGINE_ARCHITECTURE |
| Validation Expert | measurement against PAC/VOM; never authors | VALIDATION, VOM, PAC |
| Governance / Documentation Expert | document authority, classification, consistency | BASELINE, DESIGN_PRINCIPLES, all RFCs |
| Tooling / Operational Expert | build, toolchain, reproducible environment | TOOLCHAIN_PROVISIONING |

Each expert maps to exactly one responsibility, satisfying *Single owner per biomechanical
domain*, *Single owner per mathematical responsibility*, and *Single owner per validation
responsibility*.

### 6.2 which experts are required
Selected by task category and the ownership matrices:
- **Discovery / Audit / Certification** → Biomechanics + (Architecture or Validation as the
  audit domain requires).
- **Cleanup / Upgrade / Redesign / New Artifact** → Biomechanics + Architecture + Validation
  (Engine when mathematics is touched).
- **Family Work** → all of the above across every family member; consistency is the
  Architecture expert's responsibility.
- **Engine Integration** → Engine + Architecture + Validation.
- **Documentation / Governance** → Governance expert (with Biomechanics/Architecture consulted
  when content touches them).
- **Tooling / Operational** → Tooling expert.

### 6.3 execution order
A dependency-respecting order the orchestrator enforces:
1. **Governance / classification** first (the orchestrator's own decision).
2. **Biomechanics** before implementation (principle P10/P16) — intent is settled before code.
3. **Architecture / ownership** before code — ownership is mapped before anything is written.
4. **Engine / mathematics** before pose-specific code that would otherwise duplicate it
   (principle: prefer engine capabilities over pose-specific hacks).
5. **Implementation** (coding) only after 1–4 and review.
6. **Validation** last, measuring the realized result.

### 6.4 which experts may run in parallel
Independence permits parallelism; shared ownership forbids it.
- **Parallel-eligible:** Biomechanics audit and Architecture audit of *different* domains;
  Validation specification and Documentation drafting; independent family members in a Family
  task (each member still gets its own sequential expert chain).
- **Never parallel:** two experts writing the *same* responsibility; planning and its own review
  (review must follow the plan); implementation and the validation that certifies it (validation
  measures the finished work).

The orchestrator guarantees no two parallel experts write overlapping authority.

### 6.5 merge strategy
Each expert's output is merged into the single implementation / document, never kept as a
separate artifact:
- Expert conclusions become edits to the codebase or to ACTIVE documents.
- Conflicting conclusions are reconciled by the orchestrator **before** merge, using the
  authority hierarchy (§6.6), not by averaging.
- The merged result is one coherent change, not a stack of expert opinions.

### 6.6 conflict resolution
When experts disagree, the orchestrator resolves using this authority order (highest first):
1. `RFC_MONKENGINE_DESIGN_PRINCIPLES.md` — the constitution.
2. `RFC_MONKENGINE_BASELINE.md` — the source of truth and document classification.
3. The governing matrix for the disputed domain (JOM for joints, VOM for validation, MOM for
   movement, PRP for pose responsibility, PAC for acceptance).
4. `ARCHITECTURE_V2.md` and the ACTIVE architecture set.
5. Live code — when a document conflicts with working code, the code wins
   (`RFC_MONKENGINE_BASELINE.md` §5).
6. Historical / ARCHIVE documents — never override active governance (principle P18).

A conflict is never "resolved" by lowering a principle or a check. It is resolved by finding the
owner the hierarchy assigns.

---

## 7. Artifact Lifecycle

### 7.1 Principle
**Temporary reports produced by experts MUST NOT become repository files.** They exist only
during execution and are merged into the final implementation or discarded.

### 7.2 What is transient
- Audit notes, draft plans, ownership maps, risk lists.
- Expert review comments and iteration drafts.
- Verification logs and intermediate measurement output.
- Scratch working files produced to reason about the task.

These live in the session/working area, never in `docs/`, and are never committed.

### 7.3 What is durable
- The implementation or document the task produces.
- The final, merged change.
- The acceptance record (§3.1 [7]–[8]) and the final report (§3.1 [8]).

### 7.4 Disposal
At task end, the orchestrator discards all transient reports. If a "report" is genuinely needed
as a durable reference, it must be promoted to an ACTIVE document through proper governance
(BASELINE §3–§4) — not dumped into the repository as a raw expert note.

---

## 8. When Coding Is Allowed

**Coding begins only after planning and expert review are complete.**

1. The task is classified and the required documents are loaded (§5.3–§5.4).
2. Experts have produced a plan (§3 [3]).
3. The plan has passed expert review (§3 [4]); conflicts are resolved (§6.6).
4. The Definition of Done items the work targets are mapped (§5.7).

Until all four hold, no production code, no pose, no engine change, and no committed document is
written. Read-only levels (0, 1, 8) never reach the coding gate at all.

> Rationale (principle P19 — Consensus before implementation; P10/P16 — biomechanics/redesign
> from specification, never from code). Coding first and debating later produces rework and
> contradiction. The orchestrator enforces the order.

---

## 9. Mandatory Final Verification

No task is accepted without mandatory final verification, run by the orchestrator at [6] VERIFY:

1. **Compile.** The repository is left in a building state. A non-compiling change is a blocking
   defect, fixed immediately (Compile-first policy, `RFC_MONKENGINE_DEFINITION_OF_DONE.md` §4).
2. **Green baseline.** The relevant automated verification passes; no pre-existing green check was
   broken.
3. **Objective checks.** Every category-specific criterion
   (`RFC_MONKENGINE_DEFINITION_OF_DONE.md` §3) is confirmed with measurable evidence — not
   impression.
4. **Ownership audit.** No duplicated ownership was introduced; every change sits within a single
   owner's responsibility.
5. **Artifact audit.** No temporary expert report became a repository file (§7).
6. **Determinism.** The result is reproducible from the same inputs.

The orchestrator records the verification output as part of acceptance evidence.

---

## 10. Relationship to Other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md        (constitution — always true; outranks all)
        │
RFC_MONKENGINE_BASELINE.md                (governance source of truth)
        │
RFC_MONKENGINE_EXECUTION_MODES.md         (freedom: levels + strictness)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md   (THIS — the controller / brain)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md      (acceptance gate the controller enforces)
```

- **DESIGN_PRINCIPLES** is the law the orchestrator obeys above all.
- **BASELINE** supplies the ACTIVE set and classification the orchestrator checks against.
- **EXECUTION_MODES** supplies the capability (level/strictness) the orchestrator assigns.
- **DEFINITION_OF_DONE** is the bar the orchestrator applies at acceptance.
- This document is the **central controller**: it decides category, capability, RFCs, specs,
  experts, reports, DoD, and acceptance — and it sequences them all.

---

## 11. Terms

- **Orchestrator** — the controller described here; decides and sequences every task.
- **Expert** — a focused authority over one domain (single owner per responsibility).
- **Task category** — the mutually-exclusive classification of a request (§4).
- **Capability** — the execution level (0–8) + strictness bounding a task's freedom.
- **Transient report** — an expert working artifact that exists only during execution (§7).
- **Durable artifact** — the implementation/document/acceptance record that survives the task.
- **Mandatory final verification** — the non-skippable gate at §9.

---

## 12. Status

**ACTIVE.** The central controller of the MonkEngine Development System. Referenced by
`RFC_MONKENGINE_DEFINITION_OF_DONE.md`. It supersedes any informal "how to run a task" guidance
in prior-generation notes. It is the canonical orchestration specification.

---

*End of RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md.*
