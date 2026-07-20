# RFC_MONKENGINE_DEFINITION_OF_DONE.md — Universal Acceptance Gate

**Status:** ACTIVE
**Position in graph:** acceptance gate of the MonkEngine Development System
(`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`). Pairs with the Development Orchestrator, Capability Levels,
and the Development Lifecycle. This document defines **only acceptance** — what must be true for a
task to be Done.
**Scope:** acceptance criteria only. No engine, pose, validator, or architecture code is described or
modified. The *how-to* of engineering work lives in `RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`; this
document does not duplicate it.

---

## 1. Purpose

A task is not finished because code exists. A task is finished only when it satisfies an objective,
measurable bar that any reviewer, on any machine, at any time, would evaluate the same way. This
document defines that bar as the **universal acceptance gate** the Development Orchestrator applies
before declaring a task accepted.

> This document is referenced by `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` as the binding
> acceptance gate, and by the Lifecycle at Stage 5 (Verification) and Stage 6 (Acceptance). It
> defines *what* must be true for Done. The *how* is the Playbook; the *what* is here.

---

## 2. How to use this gate

For any task, the Orchestrator's Acceptance plan (`DEVELOPMENT_ORCHESTRATOR.md` §2.7) selects the
applicable items:

1. **Universal requirements** (§3) — every task, regardless of category or level.
2. **Category requirements** (§4) — the one matching the task's nature (pose / validator / engine /
   architecture / documentation).
3. **Capability-Level additions** (§5) — the additions bound to the level the Orchestrator assigned
   (`CAPABILITY_LEVELS.md`).

A task is **Done only when every applicable item passes**. A single failing item blocks acceptance;
the failure is recorded or fixed, and the lifecycle re-enters at the correct stage — the gate is
never lowered to pass.

---

## 3. Universal requirements (every task)

Every task is Done only when **all** of the following hold:

1. **Grounded in principles.** The work obeys `RFC_MONKENGINE_DESIGN_PRINCIPLES.md`. No principle was
   suspended for convenience.
2. **Grounded in the source of truth.** The work conforms to the ACTIVE document set
   (`RFC_MONKENGINE_BASELINE.md` §3) and, where it touched the architecture, matches `ARCHITECTURE_V2.md`
   and the governing matrices.
3. **Compiles.** The repository is left in a building state. A non-compiling change is a blocking
   defect, never a backlog item (Compile-First policy, §7).
4. **Green baseline.** The relevant automated verification (`:app:testDebugUnitTest` and the
   engineering-validation suite) passes and no pre-existing green check was broken.
5. **Ownership respected.** Every change lives within a single owner's responsibility (biomechanical
   domain, mathematical responsibility, validation responsibility). No overlapping authority was
   created.
6. **No duplicated ownership.** The task did not introduce a second owner of a responsibility the
   engine or another component already owns (FK, IK, constraints, shared mathematics, validation
   domains).
7. **Intended, not faked.** Where the task concerns a movement, intent was declared; the engine
   realized it. No pose or component compensated for engine behavior by faking geometry.
8. **No temporary artifacts in the repository.** Expert reports, drafts, and intermediate working
   files produced during execution are not committed as repository documents
   (`DEVELOPMENT_ORCHESTRATOR.md` §5).
9. **Documentation authoritative.** Any document the task changed or added is consistent with the
   ACTIVE set and does not contradict a higher-authority document.
10. **Reproducible.** Given the same inputs, the result is deterministic.

---

## 4. Category requirements

Beyond the Universal requirements, the task's category adds mandatory gates. A task is accepted only
if its category block below is fully satisfied.

### 4.1 Pose requirements (movement / pose task)

- The movement is defined from biomechanics (BPS → MSS → MonkEngine), not from existing code.
- The pose declares intent through the current carrier/intent model (PRP §2); it does not solve
  FK/IK/constraints/balance.
- It satisfies the Pose Acceptance Criteria (PAC) for its family.
- Validation measures the realized motion and does not author it (PRP; `VALIDATION.md` §2).
- No forbidden pose logic is present (PRP §4): no compensation, magic offsets, visual hacks,
  counter-rotations, engine workarounds, duplicated IK/FK, manual balance/contacts/pelvis/spine/
  wrist/foot.

### 4.2 Validator requirements (validation task)

- The validator measures; it does not fix or retune the instrument to pass.
- Acceptance criteria are objective, stated as measurable ranges/tolerances.
- The new/changed domain is single-owned in the Validation Ownership Matrix (VOM); it does not
  duplicate a sibling domain's responsibility.
- The validator does not mutate the pose under test.
- Existing poses it is applied to still pass or fail on true grounds (no regression in the meaning
  of existing validation).

### 4.3 Engine requirements (engine / mathematical task)

- The mathematical responsibility is owned by exactly one engine component.
- No other component reimplements the same mathematics.
- Behavior is verified against the biomechanical intent it serves.
- Where a contract requires byte-identical output, the change is proven equivalent (flag-on == off /
  consumer-on == off within tolerance) and no pose compensates for the change.

### 4.4 Architecture requirements (architecture / refactor task)

- Motion output is unchanged within tolerance (byte-identical where the contract requires it).
- Dead, duplicate, and obsolete code is removed; nothing was preserved solely as a fossil
  (`BASELINE.md` §5).
- Ownership boundaries and API contracts (`API_CONTRACTS.md`) are respected and, if changed, updated
  in the ACTIVE architecture set.
- The change operates within the frozen Architecture v2 unless the task is the freeze change itself.

### 4.5 Documentation requirements (documentation / governance task)

- The document is positioned correctly in the authority hierarchy (`BASELINE.md` §3–§4): active
  documents stay active; historical documents are not given authority over active governance.
- The document derives from, not contradicts, the Design Principles.
- No duplicated rule is introduced; ownership boundaries between documents are preserved.
- A changed specification is reflected wherever it is referenced.

---

## 5. Capability-Level DoD additions

The Orchestrator assigns a level from `CAPABILITY_LEVELS.md`. Each level adds acceptance items on top
of §3 and the matching §4 category block. A task must clear its level's additions.

### Level 0 — Documentation
- §4.5 satisfied.
- Build/compile unaffected (no code changed).
- Editorial consistency verified (terminology + authority-hierarchy correctness).

### Level 1 — Small Documentation Change
- §4.5 satisfied, plus: the change does not contradict any document higher in the authority chain;
  dependent specs remain coherent; the change is reflected at every reference site.

### Level 2 — Small Pose Edit
- §4.1 satisfied.
- The pose passes its BPS checkpoints and the PAC pipeline for that exercise.
- The engine still owns all computation (no FK/IK/constraint duplication introduced).
- Movement ownership (MOM) and sequence (MSS) are unchanged in kind.
- No new duplicated ownership introduced.

### Level 3 — Biomechanical Redesign
- §4.1 satisfied, plus §4.4 (refactor tolerance) where the exercise identity is preserved.
- Full PAC pipeline (BPS → JOM → MOM → MSS → VOM → Engine integration → Visual) passes as one cycle.
- The redesign is justified against biomechanics, not against the prior implementation.
- No follower leads; no joint built by a non-owner.
- `STABILIZATION_AUDIT.md` updated where a registered production exercise was touched.

### Level 4 — New Pose Family
- §4.1 satisfied for **every** member.
- Family consistency verified: same driver/contributor/follower pattern, same sequence, same joint
  owners across all members; no member silently diverges in ownership.
- Every member satisfies PAC; engine owns realization for all; no duplicated ownership within or
  across members.

### Level 5 — New Exercise Implementation
- §4.1 satisfied.
- The new pose passes the full PAC pipeline as one passing cycle.
- Correct driver (MOM), sequence (MSS), joint owners (JOM), validation domains (VOM).
- Registered and reachable through the live pose registry.

### Level 6 — Validator Development
- §4.2 satisfied.
- The new validator is itself validated: it measures correctly, sits in the correct VOM order, and
  does not duplicate a sibling domain.
- Existing validation baseline remains green (no regression in existing validation meaning).

### Level 7 — Engine Architecture
- §4.3 satisfied, plus §4.4 where structure moved.
- Full regression: every affected pose still satisfies PAC; the engine still owns all computation;
  no pose compensates for the change; ownership boundaries intact; API contracts respected.

### Level 8 — Core Engine Evolution
- §4.3 satisfied at system scale, plus §4.4.
- Single ownership of *every* mathematical responsibility across the system: no duplicated FK/IK/
  constraints/shared math.
- Full pose + validator regression green.
- The Design Principles are demonstrably preserved (principles do not bend to the implementation).
- The evolution is recorded in governance (an ACTIVE document), not as a temporary report committed
  to the repo.

> When a task spans levels, the Orchestrator decomposes it into constituent level tasks; each is
> accepted at its own level's additions before the whole is accepted (`CAPABILITY_LEVELS.md` §11).

---

## 6. Acceptance procedure

The Orchestrator executes the following before marking a task Done:

1. Confirm the required documents for the task's level were loaded (`CAPABILITY_LEVELS.md` / the
   Playbook's mandatory-loading matrix).
2. Confirm Planning and Expert Review completed before any code was written (Lifecycle Stage 3 before
   Stage 4).
3. Run the mandatory final verification (Lifecycle Stage 5): compile, green baseline, objective
   checks, ownership audit, artifact audit, determinism.
4. Walk §3 (Universal), the matching §4 (Category), and §5 (Level additions); every item must pass.
5. Confirm no temporary report became a repository file.
6. Record the acceptance result with the objective evidence that justifies it.

A single failing item means the task is **not Done**. The failure is recorded or fixed; the gate is
never lowered to pass.

---

## 7. Compile-First policy

A branch may never intentionally leave the repository in a non-compiling state.

- Compilation errors are **blocking defects**, fixed immediately, never postponed or scheduled as
  debt.
- Until compilation is restored, the task is **incomplete**.
- Architecture decisions, RFCs, and roadmaps are produced only against a compiling codebase.

---

## 8. Relationship to other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md      (the constitution — what is always true)
        │
RFC_MONKENGINE_BASELINE.md              (governance source of truth — what is authoritative)
        │
RFC_MONKENGINE_TASK_EXECUTION.md       (the mandatory entry point — Acceptance is its §10 stage)
        │
RFC_MONKENGINE_EXECUTION_MODES.md       (freedom: levels + strictness)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md  (the controller: how a task is decided & sequenced)
        │
RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md   (the spine Acceptance/Verification live on)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md    (this document — the acceptance gate)
```

- **DESIGN_PRINCIPLES** outranks everything; this gate enforces it.
- **BASELINE** names the ACTIVE set the gate checks against.
- **DEVELOPMENT_SYSTEM** places this document as the acceptance-gate node.
- **ORCHESTRATOR** runs the procedure in §6 and references this document as the final bar.
- **CAPABILITY_LEVELS** supplies the level whose §5 additions the gate applies.
- **LIFECYCLE** owns the stages; this gate is the bar at Verification/Acceptance.
- **ENGINEERING_PLAYBOOK** owns the how-to; this document defines only acceptance and must not
  duplicate it.
- **PDP** owns the workflow steps; this document defines only the gate applied to them.

---

## 9. Status

**ACTIVE.** The universal acceptance gate of the MonkEngine Development System. Rewritten against the
Development System. It references — but does not duplicate — the Development Orchestrator, Capability
Levels, Development Lifecycle, the Engineering Playbook, and the Pose Development Protocol. It
supersedes any informal "done" definition in prior-generation notes.

---

*End of RFC_MONKENGINE_DEFINITION_OF_DONE.md.*
