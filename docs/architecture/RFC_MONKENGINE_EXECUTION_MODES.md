# RFC_MONKENGINE_EXECUTION_MODES.md — MonkEngine Pose Development Execution Modes

**Status:** ACTIVE
**Part of:** MonkEngine governance graph (extends `RFC_MONKENGINE_BASELINE.md`; pairs with
`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`).
**Scope:** execution strategy only. Defines **HOW** an agent performs pose work — the degree of
freedom a task allows. Does not describe biomechanics (`BIOMECHANICS.md`, BPS) and does not describe
architecture (`ARCHITECTURE_V2.md`). The workflow those freedoms operate on is defined by
`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`.

---

## 1. Purpose

Different tasks require different levels of freedom. A single "fix the pose" instruction can mean
very different things: only understand it, audit it, clean it up, upgrade it, redesign it from
first principles, create it fresh, work a whole family, retire duplicated logic, or merely certify
it. Execution modes make the permitted freedom unambiguous so an agent neither over-reaches nor
under-reaches.

---

## 2. Scope and Boundaries

**This RFC defines:** the execution levels (0–8), the strictness settings, the protocol syntax, and
the mandatory document-loading matrix.

**This RFC does NOT define:**
- biomechanics or architecture — see `RFC_MONKENGINE_BASELINE.md` §3,
- the workflow (ordered steps) — see `RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`,
- document classification — see `RFC_MONKENGINE_BASELINE.md` §4.

---

## 3. Levels

| Level | Name | Freedom |
| --- | --- | --- |
| 0 | Discovery | Read-only. Understand the pose; no code changes. |
| 1 | Audit | Read-only. Produce biomechanical, architecture, engine-integration, validation audits. |
| 2 | Cleanup | Remove dead/duplicate/obsolete code. No biomechanics change. |
| 3 | Upgrade | Default. Audit → Cleanup → Transform → Validate → Accept. |
| 4 | Redesign | Prior implementation not authoritative. Rewrite from BPS + MSS + MonkEngine. |
| 5 | New Pose | No existing implementation. Create from BPS → MSS → MonkEngine. |
| 6 | Family Upgrade | Whole movement family; guarantee cross-member consistency. |
| 7 | Engine Integration | Inventory engine capabilities; remove pose-side logic that duplicates the engine. |
| 8 | Certification | Read-only. Return PASS/FAIL with justification. |

Level detail:

- **Level 0 — Discovery.** Output: current implementation, dependencies, architecture usage,
  improvement opportunities (listed, not acted on).
- **Level 1 — Audit.** Output: Biomechanical audit, Architecture audit, Engine-integration audit,
  Validation audit. No implementation.
- **Level 2 — Cleanup.** Remove dead/legacy/duplicate/obsolete code. Motion output unchanged.
- **Level 3 — Upgrade (default).** Full PDP chain (`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` §3).
- **Level 4 — Redesign.** Sources of truth become BPS, MSS, MonkEngine. Existing pose may be fully
  rewritten.
- **Level 5 — New Pose.** Created from BPS → MSS → MonkEngine.
- **Level 6 — Family Upgrade.** Examples: Push-Up, Lunge, Pull-Up, Plank, Squat, Bird Dog, Thoracic,
  Vertical Pull, Hip Flexor, Cobra, Hamstring, Stretch. Consistency across all members.
- **Level 7 — Engine Integration.** Rule: *use the engine before creating pose-specific logic.*
- **Level 8 — Certification.** Read-only PASS/FAIL with justification.

---

## 4. Strictness

| Strictness | Definition |
| --- | --- |
| **Conservative** | Minimal change. Touch only what the task names. |
| **Balanced** | Normal mode. Make the change correctly; clean obvious dead/duplicate code in the touched area. |
| **Aggressive** | Large refactors allowed. Restructure for correctness and consistency. |
| **Zero-Legacy** | Anything existing solely because of an earlier era must be removed. **Preferred default after the
  architecture reached its current fixed state** (`RFC_MONKENGINE_BASELINE.md` §5). |

If `//Strictness:` is omitted, **Balanced** is assumed.

---

## 5. Protocol Syntax

Official directive syntax. A directive names the task, its capability level, and the PDP
workflow it runs. The Development Orchestrator resolves a natural request into this form.

```
Task:
Standard Push-Up

Capability:
Level 3

Workflow:
PDP-Upgrade
```

```
Task:
Push-Up Family

Capability:
Level 6

Workflow:
PDP-Family
```

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

### 4.1 User request examples

These show how a user phrases a task and the directive the **Development Orchestrator**
resolves it into. The user need not know the token names — any clear request is classified,
assigned a **Capability Level**, and mapped to a **PDP** workflow. Every example below is then
judged by the **Definition of Done** and, for pose work, executed through the **Engineering
Playbook** against the **Pose Development Protocol (PDP)**. Equivalent natural-language requests
are accepted.

**Biomechanical audit of a single pose (read-only).**
```
Task:
Standard Push-Up

Capability:
Level 1

Workflow:
PDP-Audit
```
> "Audit the Standard Push-Up pose against BPS/JOM/MOM/MSS/VOM/PAC and report findings —
> do not change anything."

**Redesign a single pose from first principles.**
```
Task:
Standard Push-Up

Capability:
Level 4

Workflow:
PDP-Redesign
```
> "Rewrite Standard Push-Up from BPS + MSS + MonkEngine; the current code is not authoritative."

**Upgrade a pose onto the current carrier/intent model (default).**
```
Task:
Standard Push-Up

Capability:
Level 3

Workflow:
PDP-Upgrade
```
> "Upgrade Standard Push-Up: clean it up, rewrite onto the intent carriers, and pass validation."

**Create a new exercise.**
```
Task:
Cossack Squat

Capability:
Level 5

Workflow:
PDP-NewPose
```
> "Add Cossack Squat as a new exercise authored from BPS → MSS → MonkEngine and registered in
> the pose registry."

**Redesign a whole family (consistency required).**
```
Task:
Push-Up Family

Capability:
Level 6

Workflow:
PDP-Family
```
> "Redesign the entire Push-Up family so every member shares one MOM/MSS story and passes PAC."

**Improve a validator.**
```
Task:
Hip range-of-motion stamp

Capability:
Level 6

Workflow:
PDP-EngineIntegration
```
> "Add a VOM-owned hip ROM measurement that stamps the realized excursion; the validator
> measures, it does not retune the pose to pass."

**Engine feature / architecture change.**
```
Task:
Trunk-contact DOFs in the solver

Capability:
Level 7

Workflow:
PDP-EngineIntegration
```
> "Make CHEST/LUMBAR free DOFs for trunk-contact poses; preserve every pose's realized behavior
> and keep the engine as the sole owner of realization."

**Documentation update.**
```
Task:
Clarify the ownership-audit section in CODING_RULES

Capability:
Level 1

Workflow:
PDP-Audit
```
> "Revise the ownership-audit guidance in CODING_RULES so it matches the ACTIVE set; no code
> change."

**Certify a pose (pass/fail only).**
```
Task:
Dead Hang

Capability:
Level 8

Workflow:
PDP-Certification
```
> "Certify Dead Hang: return PASS/FAIL with justification, no changes."

---

## 6. Mandatory Document Loading

Agents auto-load the governing documents for their level; the user never lists them. Missing a
governing doc is a protocol violation. The ARCHIVE (`RFC_MONKENGINE_BASELINE.md` §4.1) is **never**
auto-loaded.

| Level | Documents loaded |
| --- | --- |
| 0 Discovery | BPS (relevant spec), MSS |
| 1 Audit | BPS, JOM, VOM, MOM, MSS, PRP, PAC |
| 2 Cleanup | `MIGRATION_RULES.md` (prohibited patterns), `CODING_RULES.md` §3, relevant pose file(s) |
| 3+ | All ACTIVE MonkEngine RFCs (`RFC_MONKENGINE_BASELINE.md` §3) **plus** the full Level-1 set for the
  specific pose/family, **plus** `STABILIZATION_AUDIT.md` when a registered production exercise is touched |

---

## 7. Relationship to Other MonkEngine RFCs

- **TASK_EXECUTION** (`RFC_MONKENGINE_TASK_EXECUTION.md`) is the mandatory entry point: the
  Orchestrator resolves a natural request into a directive only within its contract, and implementation
  is forbidden until the Execution Plan it produces is approved.
- **BASELINE** (`RFC_MONKENGINE_BASELINE.md`) is the root: it names the ACTIVE set loaded in §6 and
  the §5 principles (including the Zero-Legacy preference) this RFC expresses as strictness.
- **POSE_DEVELOPMENT_PROTOCOL** (`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`) defines the *workflow*
  (steps). This RFC defines the *degree of freedom* (levels, strictness) that selects which steps run.
- A directive combines both: `PDP-Upgrade` (workflow) at `Level 3` with `Strictness: Zero-Legacy`
  (freedom). The three RFCs form one coherent graph with no duplicated concepts.

---

## 8. Terms

- **Execution mode** — the combination of a Level (0–8) and a Strictness that bounds an agent's
  freedom on a task.
- **Strictness** — how much change a level is permitted to make (Conservative / Balanced / Aggressive
  / Zero-Legacy).
- **PDP token** — the `PDP-<LevelName>` directive that selects a workflow level.

(Governance terms are defined in `RFC_MONKENGINE_BASELINE.md`; workflow terms in
`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`.)

---

## 9. Status

**ACTIVE.** Part of the MonkEngine governance graph, extending `RFC_MONKENGINE_BASELINE.md`. With
`RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` it supersedes any informal descriptions of
"rewrite", "audit", "cleanup", or "redesign" in prior-generation notes. It is the canonical
**execution-strategy** specification for all future pose work.

---

*End of RFC_MONKENGINE_EXECUTION_MODES.md.*
