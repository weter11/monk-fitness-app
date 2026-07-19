# RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md — Pose Development Protocol (PDP)

**Status:** ACTIVE
**Part of:** MonkEngine governance hierarchy.
**Extended by:** `RFC_MONKENGINE_EXECUTION_MODES.md` (execution strategy / degree of freedom).
**Scope:** workflow only. This document defines **the ordered steps an agent follows** to take a
pose from request to accepted result. It does not describe biomechanics (`BIOMECHANICS.md`, BPS)
and does not describe architecture (`ARCHITECTURE_V2.md`); those are the sources of truth the
workflow consults. Execution *freedom* (how far an agent may go) is defined separately by
`RFC_MONKENGINE_EXECUTION_MODES.md`.

---

## 1. Purpose

Pose work is currently driven ad hoc: an agent may rewrite when it should only audit, or tiptoe
around dead code when it should upgrade. PDP makes the workflow explicit and repeatable so that
every pose task — audit, cleanup, upgrade, redesign, new pose, family work, engine integration, or
certification — follows the same disciplined path and reaches the same verifiable end state.

PDP is the **what-to-do-in-what-order**. Execution Modes are the **how-far-you-may-go**.

---

## 2. Governing inputs

Every PDP run is anchored to the current architecture and the biomechanical specs:

- **Architecture (authoritative):** `ARCHITECTURE_V2.md`, `ARCHITECTURE_FREEZE.md`,
  `API_CONTRACTS.md`, `MIGRATION_RULES.md`, `CODING_RULES.md`.
- **Biomechanics (correctness target):** `BIOMECHANICS.md`, and the relevant BPS spec.
- **Movement model:** `MSS` (Movement Sequence Specification), `MOM` (Movement Ownership Matrix).
- **Ownership:** `JOM` (Joint Ownership Matrix), `VOM` (Validation Ownership Matrix),
  `PRP` (Pose Responsibility Protocol).
- **Acceptance:** `PAC` (Pose Acceptance Criteria).
- **Governance:** `RFC_MONKENGINE_BASELINE.md`, and this document + `RFC_MONKENGINE_EXECUTION_MODES.md`.

> The `docs/archive/` (formerly `HISTORICAL/`) folder is **never** an input. Migration history is
> not a design constraint (`RFC_MONKENGINE_BASELINE.md` §0.1).

---

## 3. Core workflow

The canonical PDP pipeline. Levels 0–2 and 8 are subsets; Levels 3–7 include the full chain.

```
[1] LOAD        Load governing docs per the Execution Modes level (mandatory document loading).
[2] ORIENT      Identify the pose / family, its BPS spec, base class, carriers in use.
[3] AUDIT       Biomechanical + Architecture + Engine-integration + Validation audits (Level 1+).
[4] CLEANUP     Remove dead / legacy / duplicate / obsolete code; NO biomechanics change (Level 2+).
[5] TRANSFORM   Rewrite / redesign / create onto the current carrier-intent model (Level 3+).
[6] INTEGRATE   Replace pose-side logic that duplicates the engine with engine calls (Level 7).
[7] VALIDATE    ExerciseValidator + Engineering Validation; fix root cause, never retune the probe.
[8] ACCEPT      Confirm against PAC + STABILIZATION_AUDIT criteria; record result.
[9] REPORT      Summarize changes, deviations, and remaining risks.
```

Step mapping to Execution Mode levels:

| Level | Steps executed |
| --- | --- |
| 0 Discovery | LOAD, ORIENT (output: understanding only) |
| 1 Audit | LOAD, ORIENT, AUDIT |
| 2 Cleanup | LOAD, ORIENT, AUDIT, CLEANUP |
| 3 Upgrade | LOAD → ACCEPT (full chain) |
| 4 Redesign | LOAD, ORIENT (prior impl not authoritative), TRANSFORM, VALIDATE, ACCEPT |
| 5 New Pose | LOAD, ORIENT (from BPS→MSS→MonkEngine), TRANSFORM, VALIDATE, ACCEPT |
| 6 Family Upgrade | full chain, repeated/consistent across all family members |
| 7 Engine Integration | LOAD, ORIENT, AUDIT (engine inventory), INTEGRATE, VALIDATE, ACCEPT |
| 8 Certification | LOAD, ORIENT, VALIDATE → PASS/FAIL only |

---

## 4. Step definitions

### [1] LOAD
Auto-load the documents mandated by the Execution Mode level (`RFC_MONKENGINE_EXECUTION_MODES.md` §5).
Do not proceed without them. The user is not expected to list them.

### [2] ORIENT
Pin down: the target pose(s), the BPS spec that defines correct motion, the base class and family,
and which carriers/intents (`jointIntents`, `spineIntent`, `limbTargets`, `contacts`,
`postureIntent`, `extremityArticulations`) the pose currently uses or should use.

### [3] AUDIT
Produce the four audits (Level 1+):
- **Biomechanical** — does motion start at the correct joint, sequence proximal→distal, keep the
  pelvis stable, plant contacts, respect ROM? (vs `BIOMECHANICS.md` + BPS)
- **Architecture** — does it honor ownership (Pose=int intent, Engine=geometry, Validation=observer)?
  Any prohibited pattern from `MIGRATION_RULES.md` A1–A10?
- **Engine integration** — does it call `bakeIkLimb` / engine primitives, or duplicate IK/FK/
  finalizer logic by hand?
- **Validation** — does it pass `ExerciseValidator` / Engineering Validation, and why?

### [4] CLEANUP
Delete dead code, legacy code, duplicate code, obsolete hacks. **Motion output must be unchanged**
(within tolerance). This is where Zero-Legacy strictness removes migration fossils.

### [5] TRANSFORM
Rewrite / redesign / create onto the current carrier-intent model. Recognized valid direct node
writes ("Shape Constraints": `buildTorso`, `buildPelvis`, `buildShoulders`, `buildHead`,
`buildRigidSegment`, structural offsets, knee/segment writes) stay; everything else routes through
carriers/helpers. Prefer the simpler current-architecture solution (`RFC_MONKENGINE_BASELINE.md` §0.1).

### [6] INTEGRATE (Level 7)
First inventory MonkEngine capabilities (`SkeletonMath`, `ConstraintSolver`,
`SkeletonPoseFinalizer`, `BasePose` helpers). Then **remove** any manual pose logic that duplicates
them. Rule: *use the engine before creating pose-specific logic.*

### [7] VALIDATE
Run `ExerciseValidator` and (for diagnostic instruments) Engineering Validation. If a validation
pose fails, **fix the engine or record the reading** — never retune the instrument
(`VALIDATION.md` §2, `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`). After any change, confirm
`./gradlew :app:testDebugUnitTest` stays green (compile-first policy).

### [8] ACCEPT
Verify against `PAC` and the open-item criteria in `STABILIZATION_AUDIT.md`. The pose is accepted
only when it is biomechanically correct, architecture-compliant, engine-integrated, and validation-clean.

### [9] REPORT
Summarize: what changed, what was intentionally kept (and why), any deviation from BPS, and residual risk.

---

## 5. Invariants (non-negotiable)

1. **Validation poses are instruments.** Never retune to pass.
2. **Engine owns geometry.** Pose declares intent; it does not compute transforms.
3. **No new architecture.** PDP operates within the frozen Architecture v2. New carriers/APIs are
   out of scope for pose work.
4. **Simpler over fossil.** Prefer the current engine primitive; do not preserve a construction
   merely because it existed during migration.
5. **Green baseline.** Any code change keeps `:app:testDebugUnitTest` green.

---

## 6. Relationship to Execution Modes

- **PDP** = workflow (the steps above).
- **Execution Modes** = degree of freedom (Level 0–8 + Strictness).

A directive combines both:

```
//Protocol: PDP-Upgrade      ← workflow = full chain (this doc)
//Level:    3                ← freedom = audit→cleanup→rewrite→validate→accept
//Strictness: Zero-Legacy    ← how much = remove all migration fossils
```

This document is extended by `RFC_MONKENGINE_EXECUTION_MODES.md`. Together they are the complete
pose-development governance.

---

## 7. Status

**ACTIVE.** Part of the MonkEngine governance hierarchy.

This document, with `RFC_MONKENGINE_EXECUTION_MODES.md` and `RFC_MONKENGINE_BASELINE.md`, supersedes
any informal "how to work on a pose" guidance that previously lived in migration-era reports or
AGENTS.md narrative. It is the canonical **workflow** specification for all future pose work.

Governance hierarchy (current):

```
ARCHITECTURE_FREEZE.md  ── freezes ──►  ARCHITECTURE_V2.md
        │
        ├──── CODING_RULES.md / MIGRATION_RULES.md / API_CONTRACTS.md
        │
        └──── RFC_MONKENGINE_BASELINE.md (governance source of truth)
                    │
                    ├── RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md   (workflow)   ◄ ACTIVE
                    └── RFC_MONKENGINE_EXECUTION_MODES.md             (strategy)   ◄ ACTIVE
```

---

*End of RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md.*
