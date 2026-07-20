# RFC_MONKENGINE_DEFINITION_OF_DONE.md — Definition of Done

**Status:** ACTIVE
**Position in graph:** acceptance gate. Extends `RFC_MONKENGINE_BASELINE.md`; pairs with
`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` and `RFC_MONKENGINE_EXECUTION_MODES.md`.
**Scope:** the objective, checkable bar every engineering task must clear before it is
considered complete. No engine, pose, or validator code is described or modified by this
document.

---

## 1. Purpose

A task is not finished because code exists. A task is finished only when it satisfies an
objective, measurable bar that any reviewer, on any machine, at any time, would evaluate the
same way. This document defines that bar. It is the final gate the Development Orchestrator
applies before declaring a task accepted.

> This document is referenced by `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` as the binding
> acceptance gate. Where the orchestrator describes *how* a task is decided and sequenced, this
> document describes *what* must be true for it to be Done.

---

## 2. Universal Criteria (apply to every task)

Every task, regardless of category or level, is Done only when **all** of the following hold:

1. **Grounded in principles.** The work obeys `RFC_MONKENGINE_DESIGN_PRINCIPLES.md`. No principle
   was suspended for convenience.
2. **Grounded in the source of truth.** The work conforms to the ACTIVE document set
   (`RFC_MONKENGINE_BASELINE.md` §3). Where the work touched the architecture, it matches
   `ARCHITECTURE_V2.md` and the governing matrices.
3. **Compiles.** The repository is left in a building state. A non-compiling change is a blocking
   defect, never a backlog item (see the Compile-first policy below).
4. **Green baseline.** The relevant automated verification (unit/engineering validation) passes
   and no pre-existing green test was broken.
5. **Ownership respected.** Every change lives within a single owner's responsibility
   (biomechanical domain, mathematical responsibility, validation responsibility). No overlapping
   authority was created.
6. **No duplicated ownership.** The task did not introduce a second owner of a responsibility the
   engine or another component already owns (FK, IK, constraints, shared mathematics).
7. **Intended, not faked.** Where the task concerns a movement, intent was declared; the engine
   realized it. No pose or component compensated for engine behavior by faking geometry.
8. **No temporary artifacts in the repository.** Reports, drafts, and intermediate working files
   produced during execution are not committed as repository documents (see
   `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §Artifact Lifecycle).
9. **Documentation authoritative.** Any document the task changed or added is consistent with the
   ACTIVE set and does not contradict a higher-authority document.
10. **Reproducible.** Given the same inputs, the result is deterministic.

---

## 3. Category-Specific Criteria

Beyond the universal criteria, the task's category adds mandatory gates.

### 3.1 Movement / pose task
- The movement is defined from biomechanics (BPS → MSS → MonkEngine), not from existing code.
- The pose declares intent through the current carrier/intent model; it does not solve FK/IK.
- It satisfies the Pose Acceptance Criteria (PAC) for its family.
- Validation measures the realized motion and does not author it.

### 3.2 Engine / mathematical task
- The mathematical responsibility is owned by exactly one engine component.
- No other component reimplements the same mathematics.
- Behavior is verified against the biomechanical intent it serves.

### 3.3 Validation task
- The validator measures; it does not fix or retune the instrument to pass.
- Acceptance criteria are objective and stated as measurable ranges/tolerances.

### 3.4 Documentation / governance task
- The document is positioned correctly in the authority hierarchy
  (`RFC_MONKENGINE_BASELINE.md` §3–§4).
- Historical documents are not given authority over active governance.
- The document derives from, not contradicts, the Design Principles.

### 3.5 Refactor / cleanup task
- Motion output is unchanged within tolerance (byte-identical where the contract requires it).
- Dead, duplicate, and obsolete code is removed; nothing was preserved solely as a fossil.

---

## 4. Compile-First Policy

A branch may never intentionally leave the repository in a non-compiling state.

- Compilation errors are **blocking defects**, fixed immediately, never postponed or scheduled as
  debt.
- Until compilation is restored, the task is **incomplete**.
- Architecture decisions, RFCs, and roadmaps are produced only against a compiling codebase.

---

## 5. Acceptance Procedure

The orchestrator executes the following before marking a task Done:

1. Confirm the required documents for the task's level were loaded (`RFC_MONKENGINE_EXECUTION_MODES.md` §6).
2. Confirm planning and expert review completed before any code was written
   (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §When Coding Is Allowed).
3. Run the mandatory final verification (the orchestrator's Mandatory Final Verification step).
4. Walk the Universal Criteria (§2) and the Category-Specific Criteria (§3); every item must pass.
5. Confirm no temporary report became a repository file.
6. Record the acceptance result with the objective evidence that justifies it.

A single failing item means the task is **not Done**. The failure is recorded or fixed; the gate
is never lowered to pass.

---

## 6. Relationship to Other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md      (the constitution — what is always true)
        │
RFC_MONKENGINE_BASELINE.md              (governance source of truth — what is authoritative)
        │
RFC_MONKENGINE_EXECUTION_MODES.md       (freedom: levels + strictness)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md  (the controller: how a task is decided & sequenced)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md    (this document — the acceptance gate)
```

- **DESIGN_PRINCIPLES** outranks everything; the DoD enforces it.
- **BASELINE** names the ACTIVE set the DoD checks against.
- **EXECUTION_MODES** supplies the level/strictness the DoD scopes.
- **ORCHESTRATOR** runs the procedure in §5 and references this document as the final bar.

---

## 7. Status

**ACTIVE.** The acceptance gate of the MonkEngine Development System. Referenced by
`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`. It supersedes any informal "done" definition in
prior-generation notes.

---

*End of RFC_MONKENGINE_DEFINITION_OF_DONE.md.*
