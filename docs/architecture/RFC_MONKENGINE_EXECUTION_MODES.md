# RFC_MONKENGINE_EXECUTION_MODES.md — MonkEngine Pose Development Execution Modes

**Status:** ACTIVE
**Part of:** MonkEngine governance hierarchy (extends `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`).
**Scope:** execution strategy only. This document describes **HOW** an agent performs pose work.
It does **not** describe biomechanics (`BIOMECHANICS.md`, BPS) and does **not** describe
architecture (`ARCHITECTURE_V2.md`, `ENGINE_ARCHITECTURE.md`). It defines the degree of freedom
an agent is allowed for a given task.

---

## 1. Purpose

Execution modes exist because different tasks require different levels of freedom. A single
"fix the pose" instruction can mean very different things:

- Sometimes we only need to **understand** a pose without touching it.
- Sometimes we must **audit** it against every governing document.
- Sometimes we **clean up** dead code without altering motion.
- Sometimes we **upgrade** it onto the current carrier/intent model.
- Sometimes the existing implementation is wrong enough that we **redesign** from first principles.
- Sometimes there is **no** implementation and we **create** one.
- Sometimes the unit of work is a whole **family**, not one pose.
- Sometimes the goal is to **retire pose-side logic** that the engine already owns.
- Sometimes we only **certify** whether a pose passes.

The Pose Development Protocol (PDP) must describe this explicitly so that an agent does not, for
example, silently rewrite a pose when the user only asked for discovery, nor tiptoe around dead
code when the task is a full upgrade. Execution modes make the permitted freedom unambiguous.

---

## 2. Levels

Official execution levels. Each level is cumulative in capability but scoped by its stated intent.
An agent must not exceed the level granted by the protocol directive.

### Level 0 — Discovery
**Read-only.** Understand the pose. No code changes.

**Output:**
- Current implementation (what the pose does, how it is structured).
- Dependencies (base classes, helpers, carriers, BPS reference).
- Architecture usage (which engine stages / carriers / contracts it exercises).
- Improvement opportunities (listed, not acted on).

### Level 1 — Audit
**Read-only.** Use all governing documents. Produce four audits. No implementation.

**Output:**
- **Biomechanical audit** — against `BIOMECHANICS.md` and the relevant BPS spec.
- **Architecture audit** — against `ARCHITECTURE_V2.md`, `API_CONTRACTS.md`, `MIGRATION_RULES.md`.
- **Engine integration audit** — does the pose use engine primitives (IK/FK/Solver/Finalizer) or
  duplicate them? Any manual logic the engine now owns?
- **Validation audit** — does it pass `ExerciseValidator` / Engineering Validation? Why or why not?

### Level 2 — Cleanup
Remove: dead code, legacy code, duplicate code, obsolete hacks.
**Do NOT change biomechanics.** Motion output must be unchanged (within tolerance).

### Level 3 — Upgrade
**Default mode.** Workflow:

```
Audit
  ↓
Cleanup
  ↓
Rewrite (onto current carrier/intent model — Shape Constraints & Articulation recognized, not migrated)
  ↓
Validation (ExerciseValidator + Engineering Validation)
  ↓
Acceptance (per PAC / STABILIZATION_AUDIT criteria)
```

### Level 4 — Redesign
Ignore previous implementation whenever necessary. The existing pose is **no longer authoritative**.
Sources of truth become:
- **BPS** (Biomechanical Pose Specification)
- **MSS** (Movement Sequence Specification)
- **MonkEngine** (current architecture: `ARCHITECTURE_V2.md` + carriers)

The existing pose may be completely rewritten.

### Level 5 — New Pose
No existing implementation. Create from:

```
BPS
 ↓
MSS
 ↓
MonkEngine
```

### Level 6 — Family Upgrade
Work on an entire movement family (e.g. Push-Up, Lunge, Pull-Up, Plank, Squat, Bird Dog, Thoracic,
Vertical Pull, Hip Flexor, Cobra, Hamstring, Stretch). Guarantee **consistency** across all family
members: shared base class, shared helpers, uniform carrier usage, uniform validation behavior.

### Level 7 — Engine Integration
Inventory MonkEngine capabilities **first**. Then remove manual implementations that duplicate
engine functionality.

**Rule:** *Use the engine before creating pose-specific logic.* If `SkeletonMath`, `ConstraintSolver`,
`SkeletonPoseFinalizer`, or a `BasePose` helper already does it, the pose must call it — not re-implement it.

### Level 8 — Certification
**Read-only.** Return only **PASS / FAIL** with justification. No implementation changes.

---

## 3. Strictness

Execution strictness governs *how much* change a level is permitted to make.

| Strictness | Definition |
| --- | --- |
| **Conservative** | Minimal change. Touch only what the task names. Preserve everything else. |
| **Balanced** | Normal mode. Make the change correctly; clean obvious dead/duplicate code in the touched area. |
| **Aggressive** | Large refactors allowed. Restructure the pose/family for correctness and consistency. |
| **Zero-Legacy** | Anything existing solely because of the old engine must be removed. No migration fossils retained. **Expected to be the preferred mode after the Architecture-v2 migration** (see `RFC_MONKENGINE_BASELINE.md` §0.1). |

> **Zero-Legacy** is the governance-aligned default: historical migration constructions are not
> design constraints, and backward compatibility with previous authoring patterns is not a goal.
> Where the current MonkEngine provides a simpler primitive, it is used.

---

## 4. Protocol syntax

Official syntax for directing an agent. The protocol prefix is `PDP-` followed by the level name
(or `PDP-Family` for Level 6).

**Example A — single pose upgrade:**

```
//Task:
Standard Push-Up

//Protocol:
PDP-Upgrade

//Level:
3

//Strictness:
Zero-Legacy
```

**Example B — family work:**

```
//Task:
Push-Up Family

//Protocol:
PDP-Family

//Level:
6

//Strictness:
Aggressive
```

**Accepted protocol tokens:**

| Token | Level |
| --- | --- |
| `PDP-Discovery` | 0 |
| `PDP-Audit` | 1 |
| `PDP-Cleanup` | 2 |
| `PDP-Upgrade` | 3 |
| `PDP-Redesign` | 4 |
| `PDP-NewPose` | 5 |
| `PDP-Family` | 6 |
| `PDP-EngineIntegration` | 7 |
| `PDP-Certification` | 8 |

If `//Strictness:` is omitted, **Balanced** is assumed. If `//Level:` is omitted on a `PDP-` token,
the level is implied by the token.

---

## 5. Mandatory document loading

Agents must **automatically** load the governing documents for their level. The user should never
have to list them. Missing a governing doc is a protocol violation.

### Level 0 — Discovery
- BPS (relevant pose spec)
- MSS (Movement Sequence Specification)

### Level 1 — Audit
- BPS
- JOM (Joint Ownership Matrix)
- VOM (Validation Ownership Matrix)
- MOM (Movement Ownership Matrix)
- MSS
- PRP (Pose Responsibility Protocol)
- PAC (Pose Acceptance Criteria)

### Level 2 — Cleanup
- `MIGRATION_RULES.md` (prohibited patterns A1–A10)
- `CODING_RULES.md` §3 (Don't)
- Relevant pose file(s)

### Level 3+ — Upgrade and above
- **All active MonkEngine RFCs**, at minimum:
  - `ARCHITECTURE_V2.md`, `ARCHITECTURE_FREEZE.md`
  - `ENGINE_ARCHITECTURE.md`, `ENGINE.md`
  - `API_CONTRACTS.md`, `MIGRATION_RULES.md`, `CODING_RULES.md`
  - `BIOMECHANICS.md`, `VALIDATION.md`
  - `RFC_MONKENGINE_BASELINE.md` (governance source of truth)
  - `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` (workflow)
  - This document (`RFC_MONKENGINE_EXECUTION_MODES.md`)
- Plus the full Level 1 set (BPS, JOM, VOM, MOM, MSS, PRP, PAC) for the specific pose/family.
- `STABILIZATION_AUDIT.md` when the task touches a registered production exercise.

> The `docs/archive/` (formerly `HISTORICAL/`) folder is **never** auto-loaded. It is retrievable
> evidence only; reading it to justify a design choice violates `RFC_MONKENGINE_BASELINE.md` §0.1.

---

## 6. Relationship to PDP

- **PDP (Pose Development Protocol)** defines the *workflow* — the ordered steps an agent follows
  to take a pose from request to accepted result.
- **Execution Modes (this RFC)** define the *degree of freedom* — how much an agent is allowed to
  change, and whether the prior implementation is authoritative.

PDP says *what to do in what order*. Execution Modes say *how far you may go*. A protocol directive
combines both: e.g. `PDP-Upgrade` (workflow = audit→cleanup→rewrite→validation→acceptance) at
`Level 3` with `Strictness: Zero-Legacy` (freedom = remove all migration fossils).

This RFC extends `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`; together they form the complete
pose-development governance. Where this RFC and any migration-era note disagree, this RFC and the
current MonkEngine architecture win.

---

## 7. Status

**ACTIVE.** Part of the MonkEngine governance hierarchy.

This document supersedes any informal descriptions of "rewrite", "audit", "cleanup", or "redesign"
that previously lived in migration-era reports, AGENTS.md narrative, or ad-hoc agent instructions.
It is the **canonical execution specification for all future pose work.**

Governance hierarchy (current):

```
ARCHITECTURE_FREEZE.md  ── freezes ──►  ARCHITECTURE_V2.md
        │                                      │
        ├──── CODING_RULES.md (standing rules) ┤
        ├──── MIGRATION_RULES.md (pose do/don't) ┤
        ├──── API_CONTRACTS.md (component contracts) ┤
        │                                      │
        └──── RFC_MONKENGINE_BASELINE.md (governance source of truth)
                    │
                    ├── RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md  (workflow)
                    └── RFC_MONKENGINE_EXECUTION_MODES.md            (execution strategy)  ◄ ACTIVE
```

---

*End of RFC_MONKENGINE_EXECUTION_MODES.md.*
