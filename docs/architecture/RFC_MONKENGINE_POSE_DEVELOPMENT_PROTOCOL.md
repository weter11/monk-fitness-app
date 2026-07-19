# RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md — Pose Development Protocol (PDP)

**Status:** ACTIVE
**Part of:** MonkEngine governance graph (extends `RFC_MONKENGINE_BASELINE.md`).
**Scope:** workflow only. Defines the ordered steps an agent follows to take a pose from request to
accepted result. Does not describe biomechanics (`BIOMECHANICS.md`, BPS) and does not describe
architecture (`ARCHITECTURE_V2.md`); those are the sources of truth the workflow consults. Execution
*freedom* (how far an agent may go) is defined separately by `RFC_MONKENGINE_EXECUTION_MODES.md`.

---

## 1. Purpose

Pose work must follow a repeatable path so that every task — audit, cleanup, upgrade, redesign, new
pose, family work, engine integration, or certification — reaches the same verifiable end state.
This document makes the workflow explicit. It is the **what-to-do-in-what-order**; execution freedom
is defined by `RFC_MONKENGINE_EXECUTION_MODES.md`.

---

## 2. Scope and Boundaries

**This RFC defines:** the canonical PDP pipeline and its steps, plus the step-to-level mapping.

**This RFC does NOT define:**
- biomechanics or architecture — see `RFC_MONKENGINE_BASELINE.md` §3,
- execution freedom (levels, strictness) — see `RFC_MONKENGINE_EXECUTION_MODES.md`,
- document classification — see `RFC_MONKENGINE_BASELINE.md` §4.

---

## 3. Core Workflow

```
[1] LOAD        Load governing docs per the execution-mode level (mandatory document loading).
[2] ORIENT      Identify the pose / family, its BPS spec, base class, carriers in use.
[3] AUDIT       Biomechanical + Architecture + Engine-integration + Validation audits.
[4] CLEANUP     Remove dead / duplicate / obsolete code; NO biomechanics change.
[5] TRANSFORM   Rewrite / redesign / create onto the current carrier-intent model.
[6] INTEGRATE   Replace pose-side logic that duplicates the engine with engine calls.
[7] VALIDATE    ExerciseValidator + Engineering Validation; fix root cause, never retune the probe.
[8] ACCEPT      Confirm against PAC + STABILIZATION_AUDIT criteria; record result.
[9] REPORT      Summarize changes, deviations, and residual risk.
```

### 3.1 Step-to-level mapping

| Level | Steps executed |
| --- | --- |
| 0 Discovery | LOAD, ORIENT (understanding only) |
| 1 Audit | LOAD, ORIENT, AUDIT |
| 2 Cleanup | LOAD, ORIENT, AUDIT, CLEANUP |
| 3 Upgrade | LOAD → ACCEPT (full chain) |
| 4 Redesign | LOAD, ORIENT (prior impl not authoritative), TRANSFORM, VALIDATE, ACCEPT |
| 5 New Pose | LOAD, ORIENT (from BPS→MSS→MonkEngine), TRANSFORM, VALIDATE, ACCEPT |
| 6 Family Upgrade | full chain, consistent across all family members |
| 7 Engine Integration | LOAD, ORIENT, AUDIT (engine inventory), INTEGRATE, VALIDATE, ACCEPT |
| 8 Certification | LOAD, ORIENT, VALIDATE → PASS/FAIL only |

(Levels and strictness are defined in `RFC_MONKENGINE_EXECUTION_MODES.md`.)

---

## 4. Step Definitions

- **[1] LOAD** — auto-load the documents mandated by the execution-mode level. Do not proceed
  without them; the user is not expected to list them.
- **[2] ORIENT** — pin the target pose(s), the BPS spec that defines correct motion, the base class
  and family, and which carriers/intents the pose uses or should use.
- **[3] AUDIT** — four audits: Biomechanical (vs `BIOMECHANICS.md` + BPS), Architecture (ownership
  per `ARCHITECTURE_V2.md` / `MIGRATION_RULES.md`), Engine-integration (engine primitives vs
  hand-duplicated logic), Validation (`ExerciseValidator` / Engineering Validation).
- **[4] CLEANUP** — delete dead/duplicate/obsolete code. Motion output unchanged (within tolerance).
- **[5] TRANSFORM** — rewrite/redesign/create onto the current carrier-intent model. Recognized
  direct node writes ("Shape Constraints") stay; everything else routes through carriers/helpers.
  Prefer the simpler current-architecture solution (`RFC_MONKENGINE_BASELINE.md` §5).
- **[6] INTEGRATE** — inventory engine capabilities, then remove manual pose logic that duplicates
  them. Rule: *use the engine before creating pose-specific logic.*
- **[7] VALIDATE** — run `ExerciseValidator` and Engineering Validation. If a validation pose fails,
  fix the engine or record the reading — never retune the instrument (`VALIDATION.md` §2). Keep
  `:app:testDebugUnitTest` green.
- **[8] ACCEPT** — verify against `PAC` and the open-item criteria in `STABILIZATION_AUDIT.md`.
- **[9] REPORT** — summarize changes, intentional retentions, deviations, residual risk.

---

## 5. Invariants

1. **Validation poses are instruments.** Never retune to pass.
2. **Engine owns geometry.** Pose declares intent; it does not compute transforms.
3. **No new architecture.** PDP operates within the frozen Architecture v2.
4. **Simpler over fossil.** Prefer the current engine primitive; do not preserve a construction
   merely because it predates the current architecture (`RFC_MONKENGINE_BASELINE.md` §5).
5. **Green baseline.** Any code change keeps `:app:testDebugUnitTest` green.

---

## 6. Relationship to Other MonkEngine RFCs

- **BASELINE** (`RFC_MONKENGINE_BASELINE.md`) is the root: it names the ACTIVE document set this
  workflow loads and the §5 principles this workflow obeys.
- **EXECUTION_MODES** (`RFC_MONKENGINE_EXECUTION_MODES.md`) defines the degree of freedom (Level 0–8
  + Strictness) that selects which steps in §3 run and how much may change.
- Together: BASELINE = authoritative set, PDP = workflow, EXECUTION_MODES = freedom. No duplicated
  concepts across the three.

---

## 7. Terms

- **PDP** — the Pose Development Protocol defined by this document (the workflow).
- **BPS** — Biomechanical Pose Specification (per-pose biomechanical target).
- **MSS** — Movement Sequence Specification (canonical movement ordering).
- **PAC** — Pose Acceptance Criteria (acceptance bar, `RFC_POSE_ACCEPTANCE_CRITERIA.md`).
- **Carrier / intent model** — the `SkeletonPose` intent section (`jointIntents`, `spineIntent`,
  `limbTargets`, `contacts`, `postureIntent`, `extremityArticulations`).

(Governance terms are defined in `RFC_MONKENGINE_BASELINE.md`; execution-strategy terms in
`RFC_MONKENGINE_EXECUTION_MODES.md`.)

---

## 8. Status

**ACTIVE.** Part of the MonkEngine governance graph, extending `RFC_MONKENGINE_BASELINE.md`. With
`RFC_MONKENGINE_EXECUTION_MODES.md` it supersedes any informal "how to work on a pose" guidance in
prior-generation notes. It is the canonical **workflow** specification for all future pose work.

---

*End of RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md.*
