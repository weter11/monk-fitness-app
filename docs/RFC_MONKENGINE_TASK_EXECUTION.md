# RFC_MONKENGINE_TASK_EXECUTION.md — The Execution Contract

**Status:** ACTIVE
**Position in graph:** execution-order contract. Sits **under** `RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`
and is enforced *through* the Development Orchestrator (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`).
It is the **mandatory ordering** between a user request and its implementation.
**Scope:** execution order only. This document defines *what MUST happen, in what order, before any
implementation begins*. It does NOT restate the content of any document it references — it sequences
them. No implementation details, no Kotlin, no engine internals appear here.

---

## 0. What This Document Is and Is Not

This RFC is **not** another Playbook, Lifecycle, or Orchestrator. It is the **execution contract**
that binds a submitted task to the point at which implementation is legally permitted.

It defines **only execution order**. It does not:

- describe how to classify a task (→ `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2),
- define capability maturity (→ `RFC_MONKENGINE_CAPABILITY_LEVELS.md`),
- specify workflow steps (→ `RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`, and the PDP step vocabulary in
  `docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`),
- set the acceptance bar (→ `RFC_MONKENGINE_DEFINITION_OF_DONE.md`),
- define the development system map (→ `RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`).

Where this contract says "do the classification", the referenced document owns *how*. This contract
only fixes *when* and *that it is mandatory before implementation*.

---

## 1. How a Task Enters the System

A task enters the MonkEngine Development System the moment a user submits a request.

Entry is unconditional and unclassified at arrival. From the instant of entry, the task is **not yet
a unit of work** — it is a raw request (the Lifecycle's Stage 0, Idea) awaiting intake. Intake is
performed exclusively by the Development Orchestrator
(`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §1).

A task does **not** enter the system by being started, coded, or planned. It enters by being received.

---

## 2. Who Receives It

The **Development Orchestrator** is the sole receiver of an entered task.

No expert, agent, workflow, or subsystem receives a task directly from the user. Every task is first
held by the Orchestrator, which becomes its owner until the task is accepted and closed. The
Orchestrator's receipt of the task is the precondition for every subsequent step in this contract.

(Who the Orchestrator then delegates to — experts, workflow — is the Orchestrator's own decision
function: `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2. This contract only fixes that the
Orchestrator receives first.)

---

## 3. What MUST Happen Before ANY Implementation Begins

Before a single line of implementation is permitted, the following MUST occur, in this order:

1. **Intake by the Orchestrator** (§2) — the task is received and owned (Lifecycle Stage 0 → handoff).
2. **Classification** of the task — category + capability level (Lifecycle Stage 1;
   `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2.1–§2.2; maturity from
   `RFC_MONKENGINE_CAPABILITY_LEVELS.md`).
3. **Specification of required inputs** — which ACTIVE documents, specs, experts, and validation the
   level demands (Lifecycle Stage 2 / Orchestrator §2.3–§2.4;
   `RFC_MONKENGINE_CAPABILITY_LEVELS.md` per-level "Required specifications").
4. **Production of the Execution Plan** (§6) — the plan enumerating classification, capability,
   workflow, documents, specifications, experts, validation, Definition of Done, and verification.
5. **Approval of the Execution Plan** (§8) — the plan is complete and approved (Lifecycle Stage 3
   Expert Review verdict).

Implementation is **forbidden** until step 5 is satisfied (see §5).

---

## 4. When Implementation Is Allowed

Implementation is allowed **only after** all of §3 is satisfied and the Execution Plan is approved
(§8). Once approved, implementation proceeds by executing the plan:

- the **Required Workflow** runs as the work sequence
  (`RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`, selecting the PDP step vocabulary
  `docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` appropriate to the level),
- under the **Required Documents / Specifications** as the source of truth,
- by the **Required Experts** as single owners of their responsibilities,
- measured by the **Required Validation**,
- judged against the **Required Definition of Done** and **Required Verification**.

Allowing implementation before plan approval is a violation of this contract, not a shortcut. Coding
begins **only** at the Lifecycle's Implementation stage, and only after Planning and Expert Review are
complete (`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §6, §10).

---

## 5. When Implementation Is Forbidden

Implementation is forbidden:

1. **Before intake** by the Orchestrator (§2).
2. **Before classification** (§3.2).
3. **Before required inputs are specified** (§3.3).
4. **Before the Execution Plan exists** (§6) — *Implementation is forbidden until the Execution Plan
   exists.*
5. **Before the Execution Plan is approved** (§8).
6. **If the approved plan is later found incomplete** — implementation must pause until the plan is
   completed and re-approved (§8).

A task may not be "partially implemented to discover the plan". The plan precedes the code.

---

## 6. Mandatory Execution Plan

**Implementation is forbidden until the Execution Plan exists.**

The Execution Plan is the single artifact that converts a classified task into an executable contract.
It is produced by the Orchestrator as part of intake (§3.4) and is the gate to implementation (§4–§5).
In the Lifecycle it is the Planning-stage output reviewed at Expert Review
(`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §4–§5).

### 6.1 The Execution Plan MUST contain

- **Task classification** — the category and the assigned capability level
  (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2.1–§2.2;
  `RFC_MONKENGINE_CAPABILITY_LEVELS.md`).
- **Capability** — the maturity envelope (Level 0–8 + Strictness) that bounds how far the task may go.
- **Required Workflow** — the `RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md` task class, realized through the
  PDP step vocabulary (`docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`) the level
  selects.
- **Required documents** — the ACTIVE documents the task must load and obey.
- **Required specifications** — the specification layer (BPS/MSS/MOM/JOM/VOM/PRP/PAC) the task touches.
- **Required experts** — the single owners (per responsibility) who must act.
- **Required validation** — the measurement the task must pass (never authorship).
- **Required Definition of Done** — the Universal + category-specific gate the task targets.
- **Required verification** — the mandatory final verification that proves the DoD was met.

Each item above is **defined by its owning document**; this contract only requires that the plan
enumerate all of them. The plan is a table of pointers to those documents, not a restatement of their
content.

---

## 7. Execution Plan Approval

The Execution Plan must be **complete** — all items of §6.1 present and resolved — before approval.

Only after the plan is complete may implementation begin (§4). An incomplete plan is not
approvable: if any item of §6.1 is missing or undecided, the task cannot be approved and
implementation remains forbidden (§5).

Approval is the Orchestrator's Expert-Review verdict that the plan is sufficient to bound the work
(`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §5). It is the single release point for implementation.

---

## 8. Implementation

Implementation executes the approved plan (§4, §6–§7) at the Lifecycle's Implementation stage:

1. Run the **Required Workflow** as the ordered work sequence.
2. Obey the **Required Documents / Specifications** as the source of truth.
3. Act through the **Required Experts** as single owners of their responsibilities; parallel experts
   write only their own owned responsibility (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §4).
4. Preserve the pose/engine responsibility boundary (PRP) on any pose task
   (`docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md`;
   `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §7).

Implementation may not expand beyond the approved plan's capability envelope. A discovered need that
exceeds the plan returns the task to §3 (re-classify → re-plan → re-approve).

---

## 9. Verification

After implementation, the **Required Verification** (§6.1) is executed at the Lifecycle's Verification
stage: the mandatory final verification that proves the work meets its acceptance bar
(`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §7).

Verification is measurement against the **Required Definition of Done** and the **Required
Validation**, not a re-decision of the plan. It is the evidence-gathering step that feeds Acceptance
(§10). A task that has not been verified cannot be accepted.

---

## 10. Acceptance

Acceptance is the gate owned by `RFC_MONKENGINE_DEFINITION_OF_DONE.md`, executed at the Lifecycle's
Acceptance stage (`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §8). The task is accepted only when:

- the Execution Plan was complete and approved before implementation (§7),
- implementation stayed within the approved capability envelope (§8),
- the Required Verification passed (§9), and
- every Universal + category-specific DoD criterion is satisfied
  (`RFC_MONKENGINE_DEFINITION_OF_DONE.md` §2–§3).

A single failing item means the task is **not accepted**; the gate is never lowered to pass
(`RFC_MONKENGINE_DEFINITION_OF_DONE.md` §5).

---

## 11. Knowledge Capture

After acceptance, the **Required knowledge capture** is performed at the Lifecycle's Knowledge
Capture stage (`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §9): the durable record the task must leave
is promoted through governance, and all temporary expert artifacts are destroyed
(`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §5).

Knowledge capture closes the task. What is kept is the governed record of *what was decided and why*
(the Orchestrator's decision trace); what is destroyed is every transient working note. No temporary
report becomes a repository file.

---

## 12. Integration With the MonkEngine Development System

This contract sequences the documents it depends on; it does not duplicate them.

```
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md          (the system map; this contract is a node under it)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md    (receives the task; produces & approves the plan — §2, §3, §6, §7)
        │
RFC_MONKENGINE_CAPABILITY_LEVELS.md           (classifies the task; bounds the plan — §3, §6)
        │
RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md       (the nine-stage spine this contract maps onto — §3, §4, §8, §9, §10, §11)
        │
RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md        (the Required Workflow the plan selects — §6, §8)
        │   └ (PDP steps: docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md          (the Required DoD + Verification the plan targets — §6, §9, §10)
```

- **DEVELOPMENT_SYSTEM** — this contract is an execution-order node within the system it maps.
- **DEVELOPMENT_ORCHESTRATOR** — the sole receiver (§2); the producer and approver of the Execution
  Plan (§6–§7). This contract adds no decision content; it makes the Orchestrator's plan *mandatory
  before implementation*.
- **CAPABILITY_LEVELS** — supplies the classification and capability the plan must contain (§6.1).
- **DEVELOPMENT_LIFECYCLE** — the nine-stage spine (Idea → Classification → Planning → Expert Review →
  Implementation → Verification → Acceptance → Knowledge Capture) this contract maps the order onto.
- **ENGINEERING_PLAYBOOK** — supplies the Required Workflow the approved plan executes (§8).
- **DEFINITION_OF_DONE** — supplies the Required DoD and Verification the plan targets and Acceptance
  enforces (§9–§10).

### 12.1 The single contract rule

For any task, the order is invariant:

> **Enter → Orchestrator receives → Classify → Specify inputs → Plan exists → Plan approved →
> Implement → Verify → Accept → Capture.**

Skipping any step before "Plan approved" is forbidden (§5). This is the only rule this document adds;
everything it points at is owned by the documents above.

---

## 13. Status

**ACTIVE.** The execution-order contract of the MonkEngine Development System. Created without
modifying any existing RFC. It is the canonical ordering between a user request and its
implementation: no implementation before an approved Execution Plan.

---

*End of RFC_MONKENGINE_TASK_EXECUTION.md.*
