# RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md — Pose Development Protocol (PDP)

**Status:** ACTIVE
**Part of:** MonkEngine Development System (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`). The workflow
node for pose development.
**Scope:** pose development workflow only. Defines the ordered steps an agent follows to take a pose
from request to accepted result, expressed entirely in pose terms. It does not define engineering
governance, capability levels, strictness, or acceptance criteria — those live in the documents it
consumes (see §7).

---

## 1. Purpose

Pose work must follow one repeatable path so that every pose task — audit, cleanup, redesign, new
pose, or family work — reaches the same verifiable end state. This document makes that path explicit
as a **pose-specific workflow**: what to do, in what order, in the language of poses.

The PDP is the workflow the **Development Orchestrator** selects steps from, the **Engineering
Playbook** applies to each task class, and the **Definition of Done** judges at the end. This document
only describes the pose work itself.

---

## 2. Scope and Boundaries

**This document defines:** the canonical PDP pipeline and its pose-specific steps, and how each step
consumes the specification layer (BPS/MSS/MOM/JOM/VOM/PRP/PAC).

**This document does NOT define (owned elsewhere):**
- capability levels, strictness, and execution freedom — `RFC_MONKENGINE_EXECUTION_MODES.md`
  (consumed by the Orchestrator, not by this workflow),
- classification, decision, review, and acceptance sequencing — `RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`
  and `RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`,
- the acceptance bar — `RFC_MONKENGINE_DEFINITION_OF_DONE.md` and `RFC_POSE_ACCEPTANCE_CRITERIA.md` (PAC),
- the pose-vs-engine responsibility boundary and its forbidden-logic list — `RFC_POSE_RESPONSIBILITY_PROTOCOL.md` (PRP),
- biomechanics and architecture as sources of truth — `BIOMECHANICS.md`, `ARCHITECTURE_V2.md`, `BPS`.

No governance, level, or acceptance rule is restated here; each is referenced to its owner.

---

## 3. The Specification Layer the PDP Consumes

Every PDP step reads from, and every pose result is judged against, one coherent specification system.
The PDP consumes these documents; it does not redefine them.

| Doc | Role in the pose workflow |
| --- | --- |
| **BPS** | Biomechanical Pose Specification — the per-exercise human-biomechanics target (checkpoints, acceptance boxes). The "what correct looks like" for the pose. |
| **MSS** | Movement Sequence Specification — the canonical order motion propagates (Preparation → Initiation → Propagation → Stabilization → Completion → Recovery). |
| **MOM** | Movement Ownership Matrix — which segment drives each movement (driver / contributors / followers / must-not-initiate). |
| **JOM** | Joint Ownership Matrix — which expert builds each joint (single owner per joint). |
| **VOM** | Validation Ownership Matrix — which domain certifies each feature, in deterministic order. |
| **PRP** | Pose Responsibility Protocol — the boundary: a pose declares intent only; the engine realizes it. The forbidden-logic list lives here. |
| **PAC** | Pose Acceptance Criteria — the measurable gate that binds BPS/MSS/MOM/JOM/VOM/PRP into one verdict for a pose. |

Supporting system documents the PDP is executed through (not redefined here):

| Doc | Role |
| --- | --- |
| **Definition of Done** | The universal + category-specific acceptance gate the workflow is judged against at the end. |
| **Development Orchestrator** | Decides the task category, capability level, required specs, and which PDP steps run for this task. |
| **Engineering Playbook** | The practical handbook that maps each pose task class onto these PDP steps (with the Orchestrator/DoD/PRP framing). |

---

## 4. Core Workflow

```
[1] LOAD        Load the pose's BPS + the MSS/MOM/JOM/VOM/PRP/PAC set for the exercise.
[2] ORIENT      Identify the pose / family, its BPS spec, base class, and carriers/intents in use.
[3] AUDIT       Check the pose against BPS, MOM, JOM, MSS, VOM, and PRP (four-way pose audit).
[4] CLEANUP     Remove dead / duplicate / obsolete pose code; NO biomechanics change.
[5] TRANSFORM   Rewrite / redesign / create the pose onto the current carrier-intent model.
[6] INTEGRATE   Replace pose-side logic that duplicates the engine with engine calls (per PRP).
[7] VALIDATE    Run the ExerciseValidator + Engineering Validation (VOM domains).
[8] ACCEPT      Confirm the pose against PAC and the Definition of Done; record the verdict.
[9] REPORT      Summarize changes, deviations, and residual risk.
```

The Orchestrator decides which steps a given task runs (Discovery may stop at ORIENT; Certification
runs LOAD→VALIDATE only). The step *definitions* below are pose-specific; the *selection* of steps is
the Orchestrator's decision (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2).

---

## 5. Pose-Specific Step Definitions

- **[1] LOAD** — load the exercise's BPS and the full specification set (MSS, MOM, JOM, VOM, PRP, PAC)
  for that pose. Do not proceed without the BPS; the spec is the target, not the existing code.

- **[2] ORIENT** — pin the target pose(s), the BPS spec that defines correct motion, the base class
  and family, and which carriers/intents the pose currently declares or should declare (the
  `SkeletonPose` intent section: `jointIntents`, `spineIntent`, `limbTargets`, `contacts`,
  `postureIntent`, `extremityArticulations`).

- **[3] AUDIT** — four pose-side audits, each against its owner document:
  - *Biomechanical* — does the pose realize the BPS checkpoints and acceptance boxes?
  - *Movement* — is the MOM driver/contributor/follower pattern correct and MSS sequence order correct?
  - *Joint* — is each joint built only through its JOM owner (no cross-owner mutation)?
  - *Responsibility* — does the pose violate PRP (forbidden pose logic: compensation, magic offsets,
    duplicated IK/FK, manual balance/contacts/pelvis/spine/wrist/foot)?

- **[4] CLEANUP** — delete dead/duplicate/obsolete pose code. Motion output unchanged within tolerance.
  No biomechanical re-derivation happens here.

- **[5] TRANSFORM** — rewrite/redesign/create the pose onto the current carrier-intent model. Declare
  intent only (ROM intent, limb targets, contacts, pelvis/gaze intent, timing, support transitions).
  Recognized direct node writes ("Shape Constraints") stay; everything else routes through
  carriers/helpers. Derive the pose from BPS → MSS → MonkEngine, never from the old code.

- **[6] INTEGRATE** — inventory engine capabilities, then remove manual pose logic that duplicates the
  engine. Rule (from PRP): *use the engine before creating pose-specific logic.* The pose must not
  solve IK/FK/constraints/balance — those are the engine's.

- **[7] VALIDATE** — run the `ExerciseValidator` and Engineering Validation. These exercise the VOM
  domains. If a validation pose fails, the reading is recorded or the engine is fixed — the pose is
  never retuned to pass (PRP §4; PAC §4).

- **[8] ACCEPT** — walk the pose through the PAC pipeline (BPS → JOM → MOM → MSS → VOM → Engine
  integration → Visual) as one full passing cycle, then confirm against the Definition of Done. A
  single failing stage blocks acceptance; the gate is never lowered.

- **[9] REPORT** — summarize which BPS sections are realized, which JOM owners are used, which VOM
  domains certified the pose, and confirm no PRP forbidden logic is present; note deviations and
  residual risk.

---

## 6. Pose Acceptance (consumes PAC + Definition of Done)

Acceptance of a pose is owned by PAC and the Definition of Done; the PDP only routes the pose into
that gate. The pose is accepted only when:

- it passes the full PAC cycle (BPS → JOM → MOM → MSS → VOM → Engine integration → Visual) — one
  complete passing cycle, never a single green stage (PAC §2, §8),
- it satisfies the Definition of Done universal + pose requirements (intent declared, engine realizes,
  no forbidden PRP logic, no duplicated ownership),
- the four PRP review questions are answered and the "any engine workaround?" answer is **no** (PRP §6).

The PDP does not redefine these bars; it delivers the pose to them.

---

## 7. Relationship to the Documents It Consumes

```
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md        (system map; this is the Pose Development Protocol node)
        │
        ├── RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md   (decides category/level; selects which steps run)
        ├── RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md        (maps each task class onto these steps)
        ├── RFC_MONKENGINE_DEFINITION_OF_DONE.md          (the acceptance gate this workflow is judged against)
        │
        └── Specification layer (consumed by every step):
              BPS  (target)   MSS (sequence)   MOM (movement owners)
              JOM  (joint owners)   VOM (validation owners)   PRP (intent/realization boundary)
              PAC  (the pose acceptance gate that binds them)
```

- **ORCHESTRATOR** selects the steps and experts for a task; the PDP supplies the step vocabulary.
- **ENGINEERING_PLAYBOOK** is the how-to that applies these steps per task class (new pose, redesign,
  etc.); the PDP is the workflow it follows.
- **DEFINITION_OF_DONE** is the bar at the end; the PDP routes the pose into it (Step [8]).
- **BPS / MSS / MOM / JOM / VOM / PRP / PAC** are the specification system every step reads and the pose
  is judged against. The PDP consumes them; it does not duplicate their rules.

---

## 8. Terms

- **PDP** — the Pose Development Protocol defined by this document (the pose workflow).
- **Carrier / intent model** — the `SkeletonPose` intent section (`jointIntents`, `spineIntent`,
  `limbTargets`, `contacts`, `postureIntent`, `extremityArticulations`).
- Specification-layer terms (BPS, MSS, MOM, JOM, VOM, PRP, PAC) are defined in their own documents.
- Governance/acceptance terms (Orchestrator, Definition of Done, Engineering Playbook, capability
  levels) are defined in the documents listed in §7.

---

## 9. Status

**ACTIVE.** The Pose Development Protocol node of the MonkEngine Development System. Rewritten to be
pose-development-only: it consumes BPS, MSS, MOM, JOM, VOM, PRP, PAC, the Definition of Done, the
Development Orchestrator, and the Engineering Playbook, and contains no engineering governance of its
own. It supersedes any prior-generation "how to work on a pose" guidance.

---

*End of RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md.*
