# RFC_MONKENGINE_ENGINEERING_TARGET_SPECIFICATION.md — Engineering Target Specification (ETS)

**Status:** ACTIVE
**Part of:** MonkEngine Development System (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`).
**Position in graph:** sits **between** Task Execution (`RFC_MONKENGINE_TASK_EXECUTION.md`) and
the Development Orchestrator (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`). A task's
free-form request is first admitted by Task Execution, then **transformed into an explicit
engineering target** by this document, and only then is the target handed to the Orchestrator for
classification, planning, and execution.
**Scope:** engineering intent only. Defines **WHAT the requested result actually is** — its objective,
scope, constraints, expected result, and definition of success. It does NOT describe how to build
it, the workflow, or the acceptance bar. No implementation, no Kotlin, no engine internals
appear here.

```
User Task
    ↓
Task Execution                (the mandatory entry point — receive → classify → plan → approve)
    ↓
Engineering Target Specification   (this document — transform the request into an explicit target)
    ↓
Development Orchestrator      (classify the target, decide capability, plan, sequence)
    ↓
Development Lifecycle          (Idea → Classification → Planning → Expert Review →
                            Implementation → Verification → Acceptance → Knowledge Capture)
    ↓
Implementation
```

---

## 1. Purpose

A user request almost never arrives as an engineering task. It arrives as a sentence: "fix the push-up",
"add a Cossack squat", "make the trunk free". Those sentences are **ambiguous, under-specified,
and loaded with hidden assumptions** about what "fixed", "added", or "free" means.

### 1.1 Why free-form prompts are insufficient

- **They hide scope.** "Fix the push-up" may mean a ROM correction, a JOM ownership fix,
  a validator update, or an engine change. The four are different tasks with different owners.
- **They hide constraints.** A request rarely states "do not touch the engine" or "must stay
  byte-identical for production poses". Without those stated, an agent over-reaches.
- **They hide the success bar.** "Make it better" has no measurable end. Work proceeds by feel and
  stops by exhaustion, not by achievement.
- **They invite re-decision.** Every reader re-derives what the task *is*, so ownership, maturity,
  and acceptance drift between people and between sessions.

### 1.2 Why every task must first become an explicit engineering target

Before any classification, planning, or code, the raw request must be **transformed into a written
engineering target** that any reader — engineer, agent, reviewer — would interpret identically. The
target is the shared contract of *what is being built*, independent of who builds it or how.

The Development System is deterministic only if the same kind of request always resolves to the same
target shape, the same document set, and the same acceptance bar. The engineering target is the
first deterministic step: it pins *what* before the system decides *how far* (Capability Levels),
*who* (Orchestrator), *in what order* (Lifecycle), and *by what steps* (Playbook / PDP).

> ETS defines **WHAT**. It is not the entry point (Task Execution owns that), not the decision
> engine (Orchestrator), not the workflow (Lifecycle / Playbook / PDP), and not the gate
> (Definition of Done). It is the explicit statement of the result a task is trying to produce.

---

## 2. Target Specification

Every task, before it reaches the Orchestrator, MUST be expressed as an engineering target containing
the mandatory fields below. Each field is filled from the request; none is guessed. If a field cannot
be filled, the ambiguity is resolved **before** the target is finalized (it is not papered over).

| Field | What it states |
| --- | --- |
| **Objective** | The single sentence that says *why* this task exists and *what outcome* it pursues. |
| **Scope** | Exactly what the task covers — which pose, family, validator, subsystem, or document. |
| **Out of scope** | Explicitly what the task MUST NOT touch, even if adjacent or tempting. |
| **Expected result** | The concrete, describable end state once the target is achieved. |
| **Constraints** | The hard limits the work must obey (no engine change, compile-first, byte-identical, …). |
| **References** | The governing documents the target loads and obeys (BPS, MSS, MOM, JOM, VOM, PRP, PAC, Design Principles, Definition of Done, …). |
| **Category** | The engineering target category (see §3). |
| **Capability Level** | The proposed maturity envelope (Level 0–8 + Strictness) the target implies. |
| **Affected subsystems** | The engine / pose / validator / doc areas the target touches. |
| **Expected deliverables** | The explicit outputs the target produces (new pose, doc, tests, validator update, …). |
| **Definition of success** | The pre-implementation statement: *"When this target is achieved, …"* |

> The target is a **table of declarations**, not prose narrative. It points at owning documents for
> content; it does not restate them. The Orchestrator consumes this target; it does not author it.

---

## 3. Categories

Standard engineering target categories. A target picks exactly one (a task that spans categories is
decomposed into one target per category before execution).

| Category | Meaning |
| --- | --- |
| **New Pose** | Author a pose that has no prior form, from BPS → MSS → MonkEngine. |
| **Pose Redesign** | Rewrite an existing pose from first biomechanical principles (current form not authoritative). |
| **Pose Upgrade** | Bring an existing pose onto the current carrier/intent model without re-deriving biomechanics. |
| **Pose Family** | A coherent set of poses sharing one MOM/MSS/JOM story (consistency required). |
| **Validator** | Add, extend, or fix a validation capability that *measures* a feature (never authors). |
| **Engine** | Change a shared engine subsystem, stage, or ownership boundary (behavior preserved). |
| **Architecture** | Restructure module boundaries, ownership layout, or retire a subsystem. |
| **Documentation** | Author or revise system documentation (no behavior change). |
| **Governance** | Change the governance set itself (baseline, classification, a system RFC). |
| **Tooling** | Build/CI/test-harness or operational recipe change (no product behavior). |
| **Research** | Read-only investigation that produces findings, not changes (Level 0/1). |
| **Certification** | Read-only PASS/FAIL verdict against stated criteria (Level 8). |

---

## 4. Scope Definition

Scope is **frozen** the moment the target is finalized. The target's `Scope` and `Out of scope`
fields are the boundary of the work.

- Everything **inside** `Scope` is fair game for the owners the Orchestrator assigns.
- Everything listed in `Out of scope` **MUST NOT be changed**, even if:
  - it is adjacent to the work,
  - it looks like an easy win,
  - a reviewer suggests it "while we're here".

> Scope creep is a defect, not diligence. A target that drifts past its frozen scope is returned
> to §1–§2 and re-frozen, not silently expanded. The single-owner-per-responsibility rule
> (Design Principles P9/P20–P23) is what makes a frozen scope enforceable: each touched
> responsibility has exactly one owner, so "did this change belong to this target?" is answerable.

---

## 5. Constraints

Constraints are the hard limits the work must obey regardless of convenience. Common constraints:

| Constraint | Meaning |
| --- | --- |
| **No engine modifications** | The engine subsystem is untouched; only pose/validator/doc change. |
| **No API changes** | No `API_CONTRACTS.md` surface is altered. |
| **No architecture changes** | `ARCHITECTURE_V2.md` and its companions are not revised by this target. |
| **No performance regressions** | The change must not slow or bloat the runtime beyond tolerance. |
| **Compile-first** | The repository is never left non-compiling; compile errors are blocking defects. |
| **Byte identical** | For the affected production poses, realized output is unchanged within tolerance. |
| **Biomechanics only** | Motion output is driven by biomechanical intent, never by visual hacks. |
| **No duplicated ownership** | No second owner of a responsibility the engine or another component owns. |
| **Validator measures only** | If a validator is touched, it measures; it never retunes the pose to pass. |

Constraints are recorded in the target's `Constraints` field and are binding on the execution that
follows. They are **not** acceptance criteria (Definition of Done owns those) and **not** the
execution order (Task Execution owns that).

---

## 6. References

The target MUST explicitly list the governing documents it loads and obeys. A target that does
not name its references is incomplete. Typical references by layer:

| Reference | When it applies |
| --- | --- |
| **BPS** | A pose/exercise target — the biomechanical target shape. |
| **MSS** | A pose target — the canonical movement sequence. |
| **MOM** | A pose target — movement ownership (driver/contributor/follower). |
| **JOM** | A pose target — which expert builds each joint. |
| **VOM** | A validator or pose target — which domain certifies each feature. |
| **PRP** | A pose target — the pose-declares-intent / engine-realizes boundary. |
| **PAC** | A pose target — the measurable acceptance gate over the specification. |
| **Design Principles** | Every target — the constitution that outranks all. |
| **Definition of Done** | Every target — the acceptance bar the work will be judged against. |
| **ARCHITECTURE_V2 / API_CONTRACTS / MIGRATION_RULES** | An engine/architecture/api target. |
| **STABILIZATION_AUDIT** | A registered production-exercise target. |

Listing a reference means the target is **bound by it**; it does not mean the target restates it.

---

## 7. Deliverables

The target MUST explicitly state its expected outputs. Examples:

| Deliverable | Example |
| --- | --- |
| **New pose** | A registered, PAC-passing pose for the exercise. |
| **Documentation** | A revised or new ACTIVE document, consistent with the Baseline. |
| **Tests** | New or updated engineering-validation checks that prove the result. |
| **Validator update** | A new or extended VOM-owned measurement domain. |
| **Architecture** | A changed module boundary or retired subsystem, reflected in `ARCHITECTURE_V2`. |
| **No documentation** | A pure code/pose change with no doc deliverable required. |

Deliverables are **what the target produces**, distinct from the acceptance evidence (Definition of
Done) and from the workflow that produces them (Lifecycle / Playbook / PDP).

---

## 8. Success Definition

Success is described **BEFORE implementation**, in the target's `Definition of success` field. The
shape is always:

> **"When this target is achieved, …"** — followed by the observable end state in one or two sentences.

This is **not** acceptance (Definition of Done owns the gate) and **not** the DoD (Universal +
category-specific criteria). It is the plain-language picture of the finished result, written so that a
reviewer can later ask "is the target achieved?" and get a yes/no without re-reading the request.

Examples:
- *"When this target is achieved, the Standard Push-Up pose declares its trunk intent through carriers and passes PAC without engine workarounds."*
- *"When this target is achieved, the hip-ROM validator stamps the realized excursion and the validator no longer infers geometry."*
- *"When this target is achieved, `ARCHITECTURE_V2` names the retired subsystem and no code references it."*

---

## 9. Relationship Graph

Where ETS belongs in the system. It transforms the admitted request into an explicit target, then
hands that target to the Orchestrator.

```
User Task
    ↓
Task Execution                  (mandatory entry point — no implementation before an approved plan)
    ↓
Engineering Target Specification  (THIS DOCUMENT — transform request → explicit target)
    ↓
Development Orchestrator       (classify the target; decide capability; plan; sequence)
    ↓
Capability Level              (assign the Level 0–8 + Strictness the target implies)
    ↓
Development Lifecycle         (Idea → Classification → Planning → Expert Review →
                              Implementation → Verification → Acceptance → Knowledge Capture)
    ↓
Engineering Playbook          (the how-to for the target's task class)
    ↓
PDP                          (the pose-workflow steps, where the target is a pose)
    ↓
Implementation
    ↓
Definition of Done            (the acceptance gate the target is judged against)
```

- **TASK_EXECUTION** admits the request and enforces the entry order; ETS runs *within* that
  contract, after admission and before the Orchestrator decides.
- **DEVELOPMENT_ORCHESTRATOR** consumes the finished target: it classifies the `Category`, adopts
  the proposed `Capability Level`, loads the `References`, and plans against `Scope` / `Out of scope`
  / `Constraints` / `Expected deliverables`.
- **CAPABILITY_LEVELS** supplies the maturity scale the target's proposed level is checked against.
- **DEVELOPMENT_LIFECYCLE** is the spine the target's execution walks; the target is the Stage-0
  Idea made explicit.
- **ENGINEERING_PLAYBOOK / PDP** supply the how-to and steps that realize the target.
- **DEFINITION_OF_DONE** is the gate the realized target is accepted against (distinct from ETS's own
  `Definition of success`).

---

## 10. Boundaries

To preserve single ownership, ETS owns exactly one thing: **the explicit statement of WHAT the
requested result is.** It does not own adjacent concerns.

### 10.1 ETS DOES

- define engineering intent (objective, expected result, definition of success),
- define the target (scope, out of scope, category, affected subsystems, deliverables),
- define constraints (hard limits the work must obey),
- define expected result (the describable end state).

### 10.2 ETS DOES NOT

- **implement** — building the result is the Lifecycle's Implementation stage.
- **validate** — measuring the result is Validation / the Definition of Done's verification.
- **accept** — the acceptance gate is `RFC_MONKENGINE_DEFINITION_OF_DONE.md`.
- **plan** — producing the Execution Plan is `RFC_MONKENGINE_TASK_EXECUTION.md` §6 + the Orchestrator.
- **schedule** — sequencing experts/parallelism is the Orchestrator's decision function.
- **review** — expert review is the Lifecycle's Expert Review stage, run by the Orchestrator.

Any sentence that belongs to one of the above lives in that document, not here.

---

## 11. Examples

### 11.1 Pose Redesign

```
Task (raw request):
  "Redesign the Standard Push-Up."

Engineering Target:
  Objective:        Improve the biomechanical fidelity of the Standard Push-Up.
  Scope:           Only the Standard Push-Up pose.
  Out of scope:    Engine; validator; other push-up-family members; documentation.
  Expected result: A push-up that realizes the BPS trunk/arm checkpoints via declared intent.
  Constraints:      No engine modifications; compile-first; no duplicated IK/FK.
  References:       BPS, MSS, MOM, JOM, VOM, PRP, PAC, Design Principles.
  Category:         Pose Redesign.
  Capability Level: Level 4 (Redesign).
  Affected subsystems: Pose (Standard Push-Up only).
  Expected deliverables: Updated pose; engineering-validation tests.
  Definition of success:
    "When this target is achieved, the Standard Push-Up declares its trunk and arm intent
     through carriers and passes PAC without any engine workaround."
```

### 11.2 Validator

```
Task (raw request):
  "Make the hip ROM check honest."

Engineering Target:
  Objective:        Measure hip ROM from the realized skeleton instead of inferring it.
  Scope:           The hip-ROM validation domain only.
  Out of scope:    Pose authors; engine solver; other validators.
  Expected result: A validator that stamps realized hip excursion and reads the stamp.
  Constraints:      Validator measures only (never retunes a pose to pass); no API changes.
  References:       VOM, PRP, Design Principles, Definition of Done.
  Category:         Validator.
  Capability Level: Level 6.
  Affected subsystems: Validation (hip-ROM domain).
  Expected deliverables: Validator update; tests asserting the stamp is read.
  Definition of success:
    "When this target is achieved, the hip-ROM check reads an engine-produced stamp and
     the validator contains no geometry-inference math."
```

### 11.3 Documentation

```
Task (raw request):
  "Clarify the ownership-audit section in CODING_RULES."

Engineering Target:
  Objective:        Make the ownership-audit guidance match the ACTIVE set.
  Scope:           The ownership-audit section of CODING_RULES.md.
  Out of scope:    Any code; any other document; migration rules themselves.
  Expected result: A revised section consistent with the Baseline ACTIVE set.
  Constraints:      No code change; compile unaffected; documentation authoritative.
  References:       CODING_RULES, BASELINE, Design Principles.
  Category:         Documentation.
  Capability Level: Level 1.
  Affected subsystems: Documentation (CODING_RULES.md).
  Expected deliverables: Documentation (revised section); no tests required.
  Definition of success:
    "When this target is achieved, the ownership-audit section names only owners that
     exist in the ACTIVE set and contradicts no higher-authority document."
```

---

## 12. Status

**ACTIVE.** The Engineering Target Specification node of the MonkEngine Development System. Created
without modifying any existing RFC and without touching implementation code or engine architecture. It is
the mandatory transformation of a free-form request into an explicit engineering target: **WHAT the
result is**, owned by no other document.

---

*End of RFC_MONKENGINE_ENGINEERING_TARGET_SPECIFICATION.md.*
