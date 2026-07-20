# RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md — The MonkEngine Development System

**Status:** ACTIVE
**Position in graph:** ROOT — the top of the MonkEngine Development System. Every other
document is a node under this one.
**Scope:** system architecture only. This document defines the complete engineering ecosystem
— its purpose, hierarchy, authority, execution order, responsibilities, dependency graph, and
reading order. It does not perform the work of any document it references; it organizes them.

> This document is created without modifying any existing RFC. It is the map; the territories
> it maps are owned by their own documents.

---

## 1. Purpose

### 1.1 Why the MonkEngine Development System exists

MonkEngine represents human movement truthfully. Doing that correctly, release after release,
across poses, validators, engine changes, and documentation, is too large for any single person
or any single document to hold in their head. Without an explicit system, every task re-decides
what is authoritative, which documents to load, whose responsibility a joint or movement is, and
what "done" means. That re-deciding is where contradiction, drift, and duplicated ownership enter.

The Development System exists to make the engineering ecosystem **deterministic**: the same kind
of task always resolves to the same set of documents, the same authority order, the same
execution order, and the same acceptance bar.

### 1.2 Why independent RFCs are no longer sufficient

The project accumulated many excellent, independent specifications — design principles, a
baseline, execution modes, a pose protocol, ownership matrices, acceptance criteria, an
orchestrator. Each is correct in isolation. But independence has a cost:

- **No single anchor.** A reader could not tell which document outranks which when two disagreed.
- **No entry point.** A new engineer did not know where to start or in what order to read.
- **No execution spine.** A task did not have one prescribed path from request to acceptance.
- **Hidden dependencies.** Which documents require which others was never stated, so cycles and
  gaps formed.

Independent RFCs describe parts of the system. The Development System document describes the
**system of the RFCs**: how they nest, who overrides whom, and the one order in which they are
applied. It is the root that turns a shelf of documents into one engineering system.

---

## 2. Complete Document Hierarchy

The Development System is a single tree. Every document below is a node; its indentation is its
level in the authority/reading tree.

```
MonkEngine Development System
│
├── Design Principles
│     docs/MonkEngine Design Principles.md
│
├── Baseline
│     docs/RFC_MONKENGINE_BASELINE.md
│
├── Engine Architecture
│     docs/ARCHITECTURE_V2.md
│     docs/ENGINE_ARCHITECTURE.md
│     docs/ENGINE.md
│     docs/BIOMECHANICS.md
│
├── API Contracts
│     docs/API_CONTRACTS.md
│
├── Coding Rules
│     docs/CODING_RULES.md
│     docs/MIGRATION_RULES.md            (Pose Rules & Patterns)
│
├── Capability Levels
│     docs/architecture/RFC_MONKENGINE_EXECUTION_MODES.md
│
├── Development Lifecycle
│     docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md
│
├── Pose Development Protocol (PDP)
│     docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md
│
├── Engineering Playbook
│     docs/RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md
│
├── Definition of Done
│     docs/RFC_MONKENGINE_DEFINITION_OF_DONE.md
│
├── Pose Responsibility Protocol (PRP)
│     docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md
│
├── Biomechanical Pose Specifications
│     docs/Biomechanical Pose Specification (BPS)/<Exercise>.md
│
├── Movement Sequence Specification
│     docs/architecture/Movement Sequence Specification.md    (MSS)
│
├── Movement Ownership Matrix
│     docs/architecture/Movement Ownership Matrix.md          (MOM)
│
├── Joint Ownership Matrix
│     docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md         (JOM)
│
├── Validation Ownership Matrix
│     docs/architecture/Validation_Ownership_Matrix.md        (VOM)
│
├── Pose Rules & Patterns
│     docs/MIGRATION_RULES.md
│     docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md   (PRP)
│
├── Pose Acceptance Criteria
│     docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md       (PAC)
│
└── Development Orchestrator
      docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md
```

**Conceptual-name to file mapping (for unambiguous reference):**

| Hierarchy name | File | Short |
| --- | --- | --- |
| Design Principles | `docs/MonkEngine Design Principles.md` | DP |
| Baseline | `docs/RFC_MONKENGINE_BASELINE.md` | BASE |
| Engine Architecture | `docs/ARCHITECTURE_V2.md`, `docs/ENGINE_ARCHITECTURE.md`, `docs/ENGINE.md`, `docs/BIOMECHANICS.md` | ARCH |
| API Contracts | `docs/API_CONTRACTS.md` | API |
| Coding Rules | `docs/CODING_RULES.md`, `docs/MIGRATION_RULES.md` | CR |
| Capability Levels | `docs/architecture/RFC_MONKENGINE_EXECUTION_MODES.md` | CL |
| Development Lifecycle | `docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` | LC |
| Pose Development Protocol (PDP) | `docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` | PDP |
| Engineering Playbook | `docs/RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md` | PB |
| Definition of Done | `docs/RFC_MONKENGINE_DEFINITION_OF_DONE.md` | DoD |
| Pose Responsibility Protocol (PRP) | `docs/architecture/RFC_POSE_RESPONSIBILITY_PROTOCOL.md` | PRP |
| Biomechanical Pose Specifications | `docs/Biomechanical Pose Specification (BPS)/<Exercise>.md` | BPS |
| Movement Sequence Specification | `docs/architecture/Movement Sequence Specification.md` | MSS |
| Movement Ownership Matrix | `docs/architecture/Movement Ownership Matrix.md` | MOM |
| Joint Ownership Matrix | `docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md` | JOM |
| Validation Ownership Matrix | `docs/architecture/Validation_Ownership_Matrix.md` | VOM |
| Pose Acceptance Criteria | `docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md` | PAC |
| Development Orchestrator | `docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` | ORCH |

> Note: "Development Lifecycle" and "Development Orchestrator" are the same document
> (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`); it is named twice in the tree because it plays
> both roles — it owns the lifecycle (§3 of that document) and it is the controller that executes
> it. "Pose Rules & Patterns" is satisfied by `MIGRATION_RULES.md` (enforced coding standard) and
> `RFC_POSE_RESPONSIBILITY_PROTOCOL.md` (responsibility boundary); both are listed so neither is
> missed. "Engineering Playbook" (`RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`) is the practical how-to
> handbook; "Pose Development Protocol (PDP)" (`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`) is the
> workflow it follows. The two are distinct and must not be conflated.

---

## 3. Authority Hierarchy

When two documents disagree, the higher one wins. This is the single override chain.

```
Design Principles
        ↓
Baseline
        ↓
Development Lifecycle        (the Orchestrator — defines how a task is decided & sequenced)
        ↓
Development Orchestrator     (same document; the controller that runs the task)
         ↓
Pose Development Protocol (PDP)   (the workflow — what to do, in order)
         ↓
Engineering Playbook         (the practical how-to handbook that follows the PDP)
         ↓
Definition of Done          (the acceptance gate the controller enforces)
         ↓
Pose Responsibility Protocol (PRP)   (responsibility boundary: pose vs engine)
         ↓
Pose Specifications          (BPS, MSS, MOM, JOM, VOM, PRP, PAC — the specification layer)
```

### 3.1 What overrides what, concretely

1. **Design Principles** outrank everything. No document, RFC, or line of code may suspend a
   principle for convenience (e.g. "engine owns mathematics", "one source of truth", "validation
   never authors poses").
2. **Baseline** is the governance source of truth: it names the ACTIVE document set and classifies
   every other document (ARCHIVE / OBSOLETE / MERGE / DELETE). When a document conflicts with the
   live code, the code wins (Baseline §5); when an ARCHIVE/OBSOLETE doc conflicts with an ACTIVE
   one, the ACTIVE one wins.
3. **Development Lifecycle / Orchestrator** decides and sequences every task. It applies the
   authority chain; it is not above the Principles or the Baseline, but it is the authority that
   *executes* the chain for a given task.
4. **Pose Development Protocol (PDP)** defines the workflow steps; the Orchestrator selects which run. The Engineering Playbook is the practical handbook that applies them.
5. **Definition of Done** is the bar the Orchestrator applies at acceptance. It does not override
   the Principles/Baseline, but every task must clear it.
6. **Pose Development Protocol (PDP)** defines the workflow steps the task follows; **Pose
   Responsibility Protocol (PRP)** fixes the pose-vs-engine responsibility boundary. Both are
   enforced inside the workflow and at acceptance.
7. **Pose Specifications (BPS/MSS/MOM/JOM/VOM/PRP/PAC)** are the specification layer judged at
   acceptance. They outrank implementation code, never the documents above them.

The specification-layer documents are peers among themselves, resolved by their own ownership
rules (MOM driver, JOM joint owner, VOM validation owner) and by the Execution Hierarchy order
below — not by a vertical authority between them.

---

## 4. Execution Hierarchy

The order in which a task is actually carried out, from request to shipped implementation.

```
User task
        ↓
Development Orchestrator        (classify, decide capability, pick docs/experts, sequence)
        ↓
Capability Levels               (assign the Level 0–8 + Strictness that bounds freedom)
         ↓
Pose Development Protocol (PDP)  (run the workflow steps selected by the level)
         ↓
Engineering Playbook            (the practical how-to handbook that follows the PDP)
         ↓
Definition of Done             (the gate checked at acceptance)
         ↓
Pose Responsibility Protocol (PRP)  (enforce pose-vs-engine responsibility during the work)
        ↓
BPS                             (what correct biomechanics looks like — the target)
        ↓
MSS                             (the order motion propagates — sequence)
        ↓
MOM                             (which segment drives each movement — ownership of motion)
        ↓
JOM                             (which expert builds each joint — ownership of structure)
        ↓
VOM                             (which domain certifies each feature — ownership of checking)
        ↓
PRP                             (the responsibility boundary the pose must respect)
        ↓
PAC                             (the acceptance gate over the whole specification)
        ↓
Implementation                 (code/pose/validator — written only after the above are satisfied)
```

### 4.1 Reading direction vs execution direction

- The **Authority Hierarchy** (§3) points *downward* as "who overrides whom" — Principles at top.
- The **Execution Hierarchy** (§4) points *downward* as "what happens first" — the user task enters
  at the top and implementation is produced at the bottom.
- These are two different orderings on the same tree. A document can be *authoritative over* another
  yet be *applied after* it: e.g. the Definition of Done overrides the Pose Development Protocol in
  authority, but it is *checked* after the protocol's work is done. Both orderings are intentional
  and must not be collapsed into one.

---

## 5. Responsibilities

Every document has exactly one job. Overlapping responsibility is a defect (Design Principle:
*No duplicated ownership*).

- **Design Principles** — the constitution. States the timeless engineering laws every other
  document must obey. Outranks all.
- **Baseline** — governance source of truth. Names the ACTIVE set and classifies every other
  document. The reference for "what is authoritative."
- **Engine Architecture** (`ARCHITECTURE_V2`, `ENGINE_ARCHITECTURE`, `ENGINE`, `BIOMECHANICS`) —
  the definitive implementation spec and biomechanical-philosophy reference: pipeline, ownership,
  invariants, coordinate/axis/IK conventions, and human-movement correctness.
- **API Contracts** — per-component read/write/prohibited contracts. The interface law between
  engine subsystems.
- **Coding Rules** (`CODING_RULES`, `MIGRATION_RULES`) — permanent standing rules and the enforced
  pose/engine coding standard (Pose Rules & Patterns). What code must never do.
- **Capability Levels** — execution strategy. Defines the Levels 0–8 and Strictness that bound how
  far a task may go.
- **Development Lifecycle / Orchestrator** — the controller. Classifies any task, decides required
  capability/RFCs/specs/experts/reports/DoD, sequences experts (which run, which in parallel, merge
  strategy, conflict resolution), and runs mandatory final verification. The brain of the system.
- **Pose Development Protocol (PDP)** — the workflow. The ordered steps (LOAD → ORIENT → AUDIT →
  CLEANUP → TRANSFORM → INTEGRATE → VALIDATE → ACCEPT → REPORT) an agent follows; the level selects
  which run.
- **Engineering Playbook** (`RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`) — the practical how-to handbook.
  Maps each recurring task class onto the PDP steps, the Capability Levels, the Lifecycle, the
  Definition of Done, and the PRP boundary.
- **Definition of Done** — the objective acceptance bar. Universal + category-specific criteria,
  Compile-First policy, and the acceptance procedure the Orchestrator runs.
- **Pose Responsibility Protocol (PRP)** — the responsibility boundary: a pose declares intent; the
  engine realizes it. Forbids the pose from solving FK/IK/constraints/balance.
- **BPS** — the biomechanical target per exercise: what correct posture and its checkpoints look
  like.
- **MSS** — the Movement Sequence Specification: the canonical order motion propagates
  (Preparation → Initiation → Propagation → Stabilization → Completion → Recovery).
- **MOM** — the Movement Ownership Matrix: which segment drives each movement (driver / contributors
  / followers / must-not-initiate).
- **JOM** — the Joint Ownership Matrix: which expert builds each joint (single owner per joint).
- **VOM** — the Validation Ownership Matrix: which domain certifies each feature, in deterministic
  order.
- **PAC** — the Pose Acceptance Criteria: the measurable gate that binds BPS/MSS/MOM/JOM/VOM/PRP
  into one verdict for a pose.

---

## 6. Dependency Graph

Each document may *read* (depend on) the documents listed under it. A document must never be
required to read a document that reads it back — the graph is acyclic.

```
Design Principles                (depends on: nothing — root law)
Baseline                        (depends on: Design Principles)
Engine Architecture             (depends on: Design Principles, Baseline)
API Contracts                   (depends on: Engine Architecture, Baseline)
Coding Rules                    (depends on: Design Principles, Baseline, Engine Architecture)
Capability Levels               (depends on: Baseline, Design Principles)
Development Orchestrator        (depends on: Design Principles, Baseline, Capability Levels,
                                   Definition of Done, Pose Development Protocol, Engineering Playbook)
Pose Development Protocol (PDP)  (depends on: Baseline, Capability Levels, Definition of Done)
Engineering Playbook            (depends on: Baseline, Capability Levels, Definition of Done,
                                   Pose Development Protocol)
Definition of Done             (depends on: Design Principles, Baseline, Capability Levels)
Pose Responsibility Protocol (PRP) (depends on: Design Principles, Baseline, Engine Architecture)
BPS                             (depends on: Design Principles, Baseline, BIOMECHANICS)
MSS                             (depends on: Design Principles, Baseline, BPS, MOM)
MOM                             (depends on: Design Principles, Baseline, BPS)
JOM                             (depends on: Design Principles, Baseline, Engine Architecture)
VOM                             (depends on: Design Principles, Baseline, JOM, BPS)
PAC                             (depends on: BPS, MSS, MOM, JOM, VOM, PRP, Definition of Done)
Implementation (code)          (depends on: ALL of the above that apply to its task)
```

### 6.1 Acyclicity

- The specification layer (BPS/MSS/MOM/JOM/VOM/PAC) forms a DAG: BPS is the target; MSS/MOM describe
  its dynamics; JOM/VOM describe ownership of building/checking; PAC binds them. PAC depends on all
  six; none depends on PAC. No cycle.
- The governance/control layer (Principles → Baseline → Capability Levels → Orchestrator → PDP →
  Engineering Playbook → DoD) is strictly top-down. The Orchestrator reads DoD, PDP, and the
  Engineering Playbook; none of those reads the Orchestrator. No cycle.
- Cross-layer edges only point *downward* (control reads specification; specification reads
  Principles/Baseline). No specification document depends on a control document.

---

## 7. Reading Order (onboarding a new engineer)

A new engineer reads the system once, top to bottom, to internalize the whole before doing any
task. This is the onboarding path; it is not the execution order (§4).

1. **Design Principles** — learn the constitution first. If nothing else is remembered, these win.
2. **Baseline** — learn what is authoritative and how documents are classified.
3. **Engine Architecture** (`ENGINE`, `BIOMECHANICS`, `ARCHITECTURE_V2`, `ENGINE_ARCHITECTURE`) —
   learn the system being built and the biomechanical philosophy.
4. **API Contracts** — learn the interface law between subsystems.
5. **Coding Rules** + **Pose Rules & Patterns** (`CODING_RULES`, `MIGRATION_RULES`) — learn what code
   must never do.
6. **Capability Levels** — learn the degrees of freedom (Levels 0–8, Strictness).
7. **Development Orchestrator** — learn how any task is classified, sequenced, and verified.
 8. **Pose Development Protocol (PDP)** — learn the workflow steps.
 8b. **Engineering Playbook** — learn the practical how-to for each task class.
 9. **Definition of Done** — learn the acceptance bar before writing anything.
10. **Pose Responsibility Protocol (PRP)** — learn the pose-vs-engine boundary.
11. **BPS** — read one or two exercise specs to see the biomechanical target shape.
12. **MSS** — learn the canonical movement sequence.
13. **MOM** — learn movement ownership (driver/contributors/followers).
14. **JOM** — learn joint ownership (who builds each joint).
15. **VOM** — learn validation ownership (who certifies each feature).
16. **PAC** — learn how the specification layer is accepted as one verdict.

After step 16 the engineer can take a task: the Orchestrator (step 7) will re-apply the Execution
Hierarchy (§4) for that specific task.

---

## 8. Relationship to This System's Own Nodes

This root document sits *above* the hierarchy it describes. It is the map, not a territory. It
does not redefine any document's content; it states how the documents relate. If this document
conflicts with a document it references, the referenced document's own authority position in §3
governs — and if the conflict is about the map itself, the Design Principles decide.

---

## 9. Status

**ACTIVE.** ROOT of the MonkEngine Development System. Created without modifying any existing RFC.
It supersedes any implicit or oral "how the docs fit together" understanding. Every future
engineering task is an instance of the system this document defines.

---

*End of RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md.*
