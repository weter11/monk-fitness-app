# RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md — The Practical Engineering Handbook

**Status:** ACTIVE
**Part of:** MonkEngine Development System (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`).
**Scope:** engineering workflow only. A *practical, task-oriented handbook* that shows an engineer
**how to do the work** for the recurring task classes in this codebase. It is the operational
companion to the governing RFCs and contains **no implementation, no algorithms, no code**.

---

## 0. How to read this handbook

Every engineering task in MonkEngine resolves through one governance graph. This handbook is the
*how-to* layer on top of that graph. When you pick up a task, follow this order:

1. **Identify the task class** (one of the six workflows below).
2. **Run it through the Development Orchestrator mentally** — it decides category, Capability Level,
   experts, required specs, execution plan, review plan, acceptance plan, knowledge capture.
3. **Walk the Development Lifecycle** (Idea → Classification → Planning → Expert Review →
   Implementation → Verification → Acceptance → Knowledge Capture).
4. **Clear the Definition of Done** (Universal + category-specific) as the binding gate.
5. **For pose tasks, obey the Pose Development Protocol (PDP)** and the Pose Responsibility Protocol
   (PRP) at every step.

The five documents you must always keep in view (this handbook references them on every page):

| Document | Role in this handbook |
| --- | --- |
| **Development Orchestrator** (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`) | The decision engine. Decides what the task *is* and what must happen to it. You do not perform its decisions; you execute them. |
| **Capability Levels** (`RFC_MONKENGINE_CAPABILITY_LEVELS.md`) | The maturity scale (0–8). Assigns the *lowest* level that safely covers the task; the level decides experts, specs, validation, and DoD column. |
| **Lifecycle** (`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`) | The nine-stage workflow spine every task passes through. |
| **Definition of Done** (`RFC_MONKENGINE_DEFINITION_OF_DONE.md`) | The objective acceptance gate (Universal + category-specific). One failing item blocks Done. |
| **Pose Development Protocol** (`docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`; PDP) | The ordered workflow steps (LOAD→ORIENT→AUDIT→CLEANUP→TRANSFORM→INTEGRATE→VALIDATE→ACCEPT→REPORT) for any pose/engine task. Pairs with the Capability Levels ("Execution Modes") which define freedom. |

> **Note on naming.** The system calls the PDP the "Engineering Playbook" in the hierarchy, but the
> task of *authoring the work* (intent vs realization) is governed by the **Pose Responsibility
> Protocol** (`docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md`; PRP). This handbook treats the
> **PDP** as the workflow to follow and the **PRP** as the boundary to respect. Both are loaded
> automatically by the levels below.

---

## 1. The shared decision frame (run this first, every time)

Before any workflow, answer the Orchestrator's eight decisions (`DEVELOPMENT_ORCHESTRATOR.md` §2).
Do not guess the requester's level — *decide* it from the task's true scope.

### 1.1 The eight decisions

| # | Decision | Where it comes from |
| --- | --- | --- |
| 1 | **Task category** | `DEVELOPMENT_ORCHESTRATOR.md` §2.1 (e.g. new pose family, biomechanical redesign, validator development, engine architecture change, core engine evolution, documentation). |
| 2 | **Capability level** | `CAPABILITY_LEVELS.md` — the **lowest** level (0–8) that safely covers the task. See the mapping table in §1.3. |
| 3 | **Required experts** | Each responsibility has exactly one owner (biomechanical intent, architecture/ownership, engine/mathematics, validation, governance). List only those the category+level require. |
| 4 | **Required specifications** | The ACTIVE set (`BASELINE.md` §3) plus the level's "required specifications". Include the specification layer (BPS/MSS/MOM/JOM/VOM/PRP/PAC) where movement is touched. |
| 5 | **Execution plan** | The stage order of the Lifecycle, and within Implementation the parallel-expert merge shape (`DEVELOPMENT_ORCHESTRATOR.md` §4). |
| 6 | **Review plan** | Which experts review before execution, in what order, and the conflict-resolution path (`DEVELOPMENT_ORCHESTRATOR.md` §6). |
| 7 | **Acceptance plan** | Which DoD items (Universal + category-specific) the task targets. |
| 8 | **Knowledge capture** | What durable record is kept; confirm all transient expert reports are destroyed (`DEVELOPMENT_ORCHESTRATOR.md` §5, Lifecycle Stage 7). |

### 1.2 The Lifecycle is the spine

Every workflow below is a re-telling of the Lifecycle for one task class. The stages are constant;
only the *content* of each stage changes per class.

```
Idea → Classification → Planning → Expert Review → Implementation
     → Verification → Acceptance → Knowledge Capture
```

- **Coding never precedes Planning + Expert Review** (`DEVELOPMENT_LIFECYCLE.md` §10).
- **Verification** (Stage 5) is mandatory before Acceptance: compile, green baseline, objective
  checks, ownership audit, artifact audit, determinism.
- **Knowledge Capture** (Stage 7) destroys transient reports; only governed knowledge is kept.

### 1.3 Capability-level quick map (decide the level here)

| If the task is… | Level | See workflow |
| --- | --- | --- |
| prose/format/docs only | 0–1 | §7 Documentation work |
| surgical edit to one pose (constant/target/carrier/contact) | 2 | §2 Redesigning an existing pose (surgical path) |
| rewrite a pose from first biomechanical principles | 3 | §2 Redesigning an existing pose (full path) |
| introduce a whole movement family | 4 | (family = Level 4 in CAPABILITY_LEVELS; reuse §2 + §3 discipline across members) |
| implement one new exercise from scratch | 5 | §3 Creating a new pose |
| add/extend a validation check or domain | 6 | §4 Validator improvements |
| change a shared engine subsystem / stage / boundary | 7 | §5 Engine improvements |
| evolve a core primitive / the solver / kinematic foundation | 8 | §6 Architecture work (system-scale) |
| pure documentation change | 0–1 | §7 Documentation work |

> When a task spans levels (e.g. a new pose **plus** its validator), decompose it into constituent
> level tasks and sequence them per the Execution Hierarchy; each is accepted at its own DoD before
> the whole is accepted (`CAPABILITY_LEVELS.md` §11).

### 1.4 The PRP boundary (non-negotiable on pose tasks)

Whatever the workflow, remember the Pose Responsibility Protocol:
- **A pose declares intent only.** ROM intent, limb targets, contacts, pelvis orientation, gaze,
  timing, support transitions.
- **The engine owns realization.** IK, FK, contacts, balance, spine/head/foot/hand reconstruction,
  articulation, validation.
- **Forbidden in any pose:** compensation, magic offsets, visual hacks, counter-rotations, engine
  workarounds, duplicated IK/FK, manual balance/contacts/pelvis/spine/wrist/foot.

If the rendered pose looks wrong, the fix lives in the **engine** (or in BPS/JOM/VOM clarification),
never in the pose faking a shape. A pose that "knows about" engine internals has crossed the line.

---

## 2. Workflow — Redesigning an existing pose

**Trigger:** an existing pose needs to change behavior, be rewritten from first principles, or be
cleaned up onto the current carrier/intent model.

**Orchestrator decisions:**
- **Category:** small pose edit (Level 2) *or* biomechanical redesign (Level 3). Decide by whether
  the current form is authoritative. If it is *not* authoritative → Level 3 (re-derive from
  BPS→MSS→MOM→JOM, not from old code).
- **Experts:** Biomechanics (intent), Architecture/Ownership (carriers, no duplicated ownership),
  Validation (measure), and — for Level 3 — Engine/Mathematics (confirm engine use, not duplication).
- **Specs:** BPS, JOM, VOM, PRP, MSS/MOM, ACTIVE architecture; `STABILIZATION_AUDIT.md` if it is a
  registered production exercise.
- **Acceptance:** Universal + `DEFINITION_OF_DONE.md` §3.1 (movement/pose) + §3.5 (refactor, when
  identity is preserved — motion unchanged within tolerance).

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | Capture the defect/gap. Flag ambiguity; do not guess. |
| Classification | Assign Level 2 (surgical) or Level 3 (redesign). Resolve ambiguity. |
| Planning | From BPS/MSS/MOM/JOM (not from old code): approach, ownership map (every touched responsibility → one owner), risk list, mapped DoD items. |
| Expert Review | Route to owning experts. Resolve conflicts via authority hierarchy **before** merge. |
| Implementation | Run the **PDP steps** for the level: `LOAD → ORIENT → AUDIT → CLEANUP → TRANSFORM → INTEGRATE → VALIDATE`. Surgical Level-2 edits stop at CLEANUP+small TRANSFORM; full redesign runs TRANSFORM+INTEGRATE. |
| Verification | Compile (Compile-First). Green `:app:testDebugUnitTest`. BPS checkpoints pass. Ownership audit: no duplicated FK/IK/constraints. Artifact audit: no temp report committed. Deterministic. |
| Acceptance | Walk Universal + §3.1 + §3.5. PRP review questions answered (see below). One fail blocks. |
| Knowledge Capture | Final report; destroy transient notes; promote only via governance. |

**PDP step notes for this workflow:**
- **ORIENT** — pin the pose, its BPS spec, base class, family, and current carriers/intents in use.
- **AUDIT** — four audits: Biomechanical (vs `BIOMECHANICS.md`+BPS), Architecture (ownership per
  `ARCHITECTURE_V2.md`/`MIGRATION_RULES.md`), Engine-integration (engine primitives vs hand-duplicated
  logic), Validation (`ExerciseValidator`/Engineering Validation).
- **CLEANUP** — delete dead/duplicate/obsolete code; motion output unchanged within tolerance.
- **TRANSFORM** — rewrite onto the current carrier-intent model. Recognized direct node writes
  ("Shape Constraints") stay; everything else routes through carriers/helpers. Prefer the simpler
  current-architecture solution (`BASELINE.md` §5).
- **INTEGRATE** — remove manual pose logic that duplicates the engine. Rule: *use the engine before
  creating pose-specific logic.*
- **VALIDATE** — run `ExerciseValidator` + Engineering Validation. If a validation pose fails, **fix
  the engine or record the reading — never retune the probe** (`VALIDATION.md` §2).

**Mandatory PRP review questions (answer in the change record; a "yes" to #4 = reject):**
1. Which BPS sections are implemented? (cite file + §sections)
2. Which JOM owners are used? (list the joint-building groups)
3. Which VOM domains validate it? (list the certifying domains)
4. Did the pose introduce any engine workaround? (must be **no**)

---

## 3. Workflow — Creating a new pose

**Trigger:** a new exercise that has no prior form, created from BPS→MSS→MOM→JOM→MonkEngine.

**Orchestrator decisions:**
- **Category:** new exercise implementation → **Level 5**. A coherent *set* of new poses → **Level 4**
  (New Pose Family); apply this workflow per member plus a cross-member consistency pass.
- **Experts:** Biomechanics (intent from BPS), Architecture/Ownership (carrier model),
  Engine/Mathematics (use engine capabilities), Validation (PAC).
- **Specs:** BPS (create or load), MSS, MOM, JOM, VOM, PRP, PAC, ACTIVE architecture set.
- **Acceptance:** Universal + `DEFINITION_OF_DONE.md` §3.1: BPS realized, PAC fully green, engine owns
  realization, no duplicated ownership, registered/reachable through the live pose registry, compiles,
  baseline green.

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | The exercise to add + constraints (family? registry name?). |
| Classification | Level 5 (single) or Level 4 (family). |
| Planning | Author/reference the BPS first (biomechanical target). Derive MSS/MOM/JOM ownership. Map DoD. |
| Expert Review | Biomechanics confirms intent; Architecture confirms carrier model + no duplicated ownership; Validation confirms PAC plan. |
| Implementation | **PDP steps** `LOAD → ORIENT → TRANSFORM → VALIDATE → ACCEPT`. Declare intent only (PRP §2). Route all motion through carriers/helpers; never solve FK/IK. Register the pose in the live registry. |
| Verification | Compile. Green baseline. New pose passes full PAC pipeline as one cycle: correct driver (MOM), correct sequence (MSS), correct joint owners (JOM), correct validation domains (VOM). |
| Acceptance | Universal + §3.1. Reachable via registry. One fail blocks. |
| Knowledge Capture | Final report; transient notes destroyed. |

**PDP step notes for this workflow:**
- **ORIENT** — from BPS→MSS→MonkEngine: identify base class, family, carriers/intents the pose
  should declare.
- **TRANSFORM** — create the pose declaring ROM intent, limb targets, contacts, pelvis/gaze intent,
  timing. No solver, no manual transforms.
- **VALIDATE** — full PAC pipeline. If a check fails, fix the engine/clarify BPS — never retune the
  validator to pass.

**Family consistency (Level 4):** every member must share one MOM/MSS/JOM story — same
driver/contributor/follower pattern, same sequence, same joint owners. No member silently diverges in
ownership.

---

## 4. Workflow — Validator improvements

**Trigger:** add, extend, or fix a validation capability — a check, a domain, or a measurement —
that *measures* a feature and never authors the pose.

**Orchestrator decisions:**
- **Category:** validator development → **Level 6**.
- **Experts:** Validation (domain owner), Biomechanics (what correct looks like), Architecture/
  Ownership (boundary vs JOM), Engine/Mathematics (the measurement math, owned once).
- **Specs:** VOM (the domain being developed), BPS (the target it measures), JOM (joints it reads),
  PRP (validation measures, never fixes), PAC (how the check feeds acceptance), Design Principles
  (validation never authors poses).
- **Acceptance:** Universal + `DEFINITION_OF_DONE.md` §3.3 (validation): the validator **measures**
  and does not fix or retune the instrument to pass; acceptance criteria are objective (ranges/
  tolerances); the new domain is single-owned in VOM; compiles; baseline green (no regression in
  existing validation).

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | The feature to measure + which VOM domain owns it. |
| Classification | Level 6. |
| Planning | Define the measurable rule, its single owner domain, and its place in the VOM order. Confirm it does not overlap a sibling domain. |
| Expert Review | Validation owns the domain; Biomechanics defines "correct"; Architecture confirms no JOM overlap; Engine confirms the math is owned once. |
| Implementation | Implement the measurement. It must read the solved skeleton and **stamp/measure** — never mutate the pose. |
| Verification | The validator measures correctly on known-good and known-bad inputs; it does not mutate the pose; it sits in correct VOM order; no sibling domain duplicated; existing poses still pass/fail on true grounds. |
| Acceptance | Universal + §3.3. Objective, stated-as-ranges criteria. One fail blocks. |
| Knowledge Capture | Final report; transient notes destroyed. |

**Critical invariant (from PDP §5 and AGENTS.md):** *validation poses are diagnostic instruments.*
A pose is a probe you point at the MonkEngine runtime to read its true state; its reading must stay
honest whether the runtime passes or fails. You **fix the engine or record the reading — you never
retune a pose to make it read green.** That is instrument tampering.

---

## 5. Workflow — Engine improvements

**Trigger:** change a shared engine subsystem, stage, or ownership boundary (Level 7), or evolve a
core primitive / the solver / the kinematic foundation (Level 8).

**Orchestrator decisions:**
- **Category:** engine architecture change (Level 7) or core engine evolution (Level 8).
- **Experts:** Engine/Mathematics (the change), Architecture/Ownership (boundaries, API contracts),
  Biomechanics (intent preserved), Validation (regression across poses). Level 8 also adds Governance/
  Documentation (the evolution is recorded as governance, not a fossil).
- **Specs:** `ARCHITECTURE_V2`, `ENGINE_ARCHITECTURE`, `API_CONTRACTS`, `MIGRATION_RULES`, PRP, and the
  full specification layer for regression scope. Level 8 adds the complete ACTIVE set + Design
  Principles (which must stay stable).
- **Acceptance:** Universal + `DEFINITION_OF_DONE.md` §3.2 (engine/mathematical): the mathematical
  responsibility lives in exactly one engine component; no other component reimplements it; behavior
  verified against biomechanical intent; full pose regression green; compiles; baseline green. Level 8
  adds: single ownership of *every* mathematical responsibility, no duplicated FK/IK/constraints/
  shared math, principles stable, evolution recorded in governance.

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | The subsystem/primitive to change + the behavior to preserve. |
| Classification | Level 7 (subsystem) or Level 8 (core primitive). |
| Planning | Scope the blast radius: which poses/validators depend on the changed machinery. Plan byte-identical contracts where required. Map DoD. |
| Expert Review | Engine proposes; Architecture confirms API/ownership boundaries; Biomechanics confirms intent preserved; Validation confirms regression plan. Resolve conflicts by authority hierarchy. |
| Implementation | Change the engine only; keep poses declaring intent (PRP). Where a contract requires byte-identical output, verify flag-on == flag-off / consumer-on == off (maxDev 0.0) patterns from prior migrations. |
| Verification | Full regression: every affected pose still satisfies PAC; engine still owns all computation; no pose compensates; ownership boundaries intact; API contracts respected; Compile-First; green baseline. |
| Acceptance | Universal + §3.2. Level 8 adds system-scale ownership + principle-stability checks. One fail blocks. |
| Knowledge Capture | Final report; for Level 8, promote the evolution into governance (ACTIVE doc), not a raw repo file. Destroy transient notes. |

**Engine discipline reminders:**
- One mathematical responsibility, one owner. No duplicated FK/IK/constraints/shared math.
- Preserve biomechanical intent; never shift realization burden onto poses.
- The pipeline (`SkeletonPipeline.produceFrame`) is the sole Solver→Finalizer owner; do not bypass
  it. Reserved hooks (e.g. `preConvertPoles`) stay no-ops unless their contract is activated.

---

## 6. Workflow — Architecture work

**Trigger:** restructure the architecture, introduce/retire a subsystem, or run a system-scale
evolution that preserves Design Principles and the specification layer's meaning.

**Orchestrator decisions:**
- **Category:** engine architecture change (Level 7) or core engine evolution (Level 8). Architecture
  work that is *documentation-only* (RFC/roadmap) is Level 0–1 and routes to §7.
- **Experts:** Architecture/Ownership (lead), Engine/Mathematics, Biomechanics, Validation,
  Governance/Documentation (for Level 8).
- **Specs:** `ARCHITECTURE_V2`, `ENGINE_ARCHITECTURE`, `API_CONTRACTS`, `MIGRATION_RULES`, PRP, and the
  full specification layer for regression. Level 8 adds the complete ACTIVE set + Design Principles.
- **Acceptance:** Universal + §3.2 at the relevant scale + (for refactors) §3.5 (motion output
  unchanged within tolerance, no fossils preserved).

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | The architectural change + the invariants it must preserve (frozen Architecture v2 unless this *is* the freeze change). |
| Classification | Level 7 or 8 (implementation) — or 0/1 if documentation-only. |
| Planning | Document the target architecture against `ARCHITECTURE_V2`. Map every changed ownership boundary to exactly one owner. Plan migration + regression. Map DoD. |
| Expert Review | Architecture owns the plan; Engine/Biomechanics/Validation confirm no intent/behavior regression; Governance confirms the doc story. |
| Implementation | Apply the change through the PDP where poses are touched (`LOAD→ORIENT→AUDIT→CLEANUP→TRANSFORM→INTEGRATE→VALIDATE`); engine changes per §5. Update `ARCHITECTURE_V2`/`API_CONTRACTS` as the source of truth. |
| Verification | Compile-First. Regression across affected poses/validators. Ownership audit (no duplicated authority). Artifact audit. Deterministic. |
| Acceptance | Universal + §3.2 + §3.5 where applicable. One fail blocks. |
| Knowledge Capture | Promote findings into ACTIVE docs through governance; retire OBSOLETE/MERGE-class docs per `BASELINE.md` §4. Destroy transient notes. |

**Invariants for architecture work:**
- Operate within the frozen Architecture v2 unless the task is the freeze change itself.
- Prefer the simpler current-architecture primitive; do not preserve constructions solely because
  they predate the current architecture (`BASELINE.md` §5).
- When a doc conflicts with live code, the code wins (`BASELINE.md` §5).

---

## 7. Workflow — Documentation work

**Trigger:** author, revise, reorganize, or reclassify system documentation — prose, formatting,
cross-references, a corrected checkpoint, a clarified rule, a promoted finding.

**Orchestrator decisions:**
- **Category:** documentation (Level 0) or small documentation change (Level 1).
- **Experts:** Governance/Documentation only (Level 0); add the domain expert for the affected spec at
  Level 1 (Biomechanics for BPS, Architecture for JOM/VOM/API, etc.).
- **Specs:** The documents touched + the Design Principles / Baseline they must stay consistent with.
  Level 1 adds the changed spec's direct dependents in the Dependency Graph
  (`DEVELOPMENT_SYSTEM.md` §6).
- **Acceptance:** Universal (esp. §2.8 no temp artifacts, §2.9 documentation authoritative) +
  `DEFINITION_OF_DONE.md` §3.4 (documentation/governance): positioned correctly in the authority
  hierarchy; historical docs not given authority over active governance; derives from, not contradicts,
  the Design Principles. Level 1 adds: no duplicated rule; ownership boundaries preserved; change
  reflected wherever the spec is referenced.

**Lifecycle execution:**

| Stage | What you do (workflow, not code) |
| --- | --- |
| Idea | The doc gap/correction. |
| Classification | Level 0 (prose only) or Level 1 (spec content of consequence). |
| Planning | Read the touched spec + dependents to avoid contradiction. Plan terminology consistency + authority-hierarchy correctness. |
| Expert Review | Governance reviews; at Level 1, the domain expert confirms no contradiction of higher-authority docs. |
| Implementation | Edit documents only. **No code changed** (Level 0/1 skip Implementation's code path and its code-facing verification; they still pass through Classification→Planning→Expert Review→Verification(editorial)→Acceptance→Knowledge Capture). |
| Verification (editorial) | Terminology consistent; authority-hierarchy correct; no duplicated/conflicting rule introduced; compiles/build unaffected (no code changed). |
| Acceptance | Universal + §3.4. One fail blocks. |
| Knowledge Capture | Final report; destroy transient notes; route any promoted finding through Baseline governance (§3–§4), not a raw repo dump. |

**Document governance rules (from `BASELINE.md`):**
- The ACTIVE set in `BASELINE.md` §3 is the source of truth. The ARCHIVE is never auto-loaded and
  never constrains new design.
- Promote a genuine finding into an ACTIVE document through proper governance; never commit a
  temporary report as a standalone repository file (`DEVELOPMENT_ORCHESTRATOR.md` §5).
- Classify anything outside the ACTIVE set as ARCHIVE / OBSOLETE / MERGE / DELETE (`BASELINE.md` §4).

---

## 8. Cross-cutting checklists (kept short)

**Before coding (every task):** Classification done → Planning done → Expert Review approved. Coding
never precedes these (`DEVELOPMENT_LIFECYCLE.md` §10).

**Verification gate (Stage 5) — all six must hold:**
1. **Compile** — building state (Compile-First policy; compile errors are blocking defects).
2. **Green baseline** — `:app:testDebugUnitTest` green; no pre-existing green broken.
3. **Objective checks** — every category criterion confirmed with measurable evidence.
4. **Ownership audit** — no duplicated ownership; each change within one owner's responsibility.
5. **Artifact audit** — no temporary expert report became a repo file.
6. **Determinism** — reproducible from the same inputs.

**Acceptance gate (Stage 6):** Universal (§2) + category-specific (§3) of the Definition of Done. A
single failing item blocks Done; the gate is never lowered.

**Pose-task guardrail (PRP):** intent declared, engine realizes; no forbidden pose logic (§4); the
four PRP review questions answered, #4 = no.

---

## 9. Relationship to other MonkEngine RFCs

```
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md          (ROOT — the system map; this handbook is a node under it)
        │
        ├── RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md   (the decision engine this handbook obeys)
        ├── RFC_MONKENGINE_CAPABILITY_LEVELS.md          (the level every workflow assigns)
        ├── RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md      (the nine-stage spine every workflow walks)
        ├── RFC_MONKENGINE_DEFINITION_OF_DONE.md         (the acceptance gate every workflow clears)
        ├── docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md  (PDP — the step workflow)
        └── docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md          (PRP — the pose/engine boundary)
```

- **DEVELOPMENT_SYSTEM** places this handbook as the practical companion to the governing RFCs.
- **ORCHESTRATOR** supplies the eight decisions each workflow opens with.
- **CAPABILITY_LEVELS** supplies the level each workflow maps to.
- **LIFECYCLE** supplies the stage table each workflow renders.
- **DEFINITION_OF_DONE** supplies the gate each workflow closes on.
- **PDP** supplies the step vocabulary (LOAD→…→REPORT).
- **PRP** supplies the boundary every pose workflow respects.

This handbook describes **only how to work** — no implementation, no algorithms, no code. It is the
operational layer that turns the governing RFCs into repeatable task execution.

---

## 10. Status

**ACTIVE.** The practical engineering handbook of the MonkEngine Development System. It references —
but does not modify — `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`,
`RFC_MONKENGINE_CAPABILITY_LEVELS.md`, `RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`,
`RFC_MONKENGINE_DEFINITION_OF_DONE.md`, and the Pose Development Protocol
(`docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`). It is the canonical *how-to* for
all recurring engineering task classes.

---

*End of RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md.*
