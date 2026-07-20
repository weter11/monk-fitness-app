# AGENTS.md — MonkEngine Development System (Entry Point)

> This file is auto-injected into every Kilo session. It is the **entry point** to the
> MonkEngine Development System: it tells you what the system is, what is authoritative, how to
> read it, and the current workflow / compile-test / governance policy. All detailed rules live in
> the referenced documents. Session-specific history is preserved in the **CLOSED HISTORY** appendix
> at the end — it is not engineering guidance.

---

## Purpose

MonkEngine represents human movement truthfully. The MonkEngine Development System is the deterministic
engineering ecosystem that keeps that representation correct release after release. This file is the
single on-ramp: read it first, then follow the Reading Order into the authoritative documents.

It answers four questions for any engineer or agent:

1. **What is the system?** — the Development System map and its authority/execution hierarchies.
2. **What is authoritative right now?** — the ACTIVE document set (the source of truth).
3. **What do I do with a task?** — the current workflow (Lifecycle → Orchestrator → Playbook).
4. **What are the standing policies?** — compile-first, test baseline, governance classification.

---

## Reading Order

Onboarding path (top to bottom, once):

1. **`docs/MonkEngine Design Principles.md`** — the constitution; outranks all.
2. **`docs/RFC_MONKENGINE_BASELINE.md`** — governance source of truth; the ACTIVE set and document classification.
3. **`docs/RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`** — the system map (authority + execution hierarchies, responsibilities, dependency graph).
4. **`docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md`** — the decision engine (classify, decide capability, pick docs/experts, sequence, verify).
5. **`docs/RFC_MONKENGINE_CAPABILITY_LEVELS.md`** — the maturity scale (Levels 0–8) bounding a task.
6. **`docs/RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`** — the nine-stage spine every task walks.
7. **`docs/RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md`** — the practical how-to for each task class.
8. **`docs/RFC_MONKENGINE_DEFINITION_OF_DONE.md`** — the acceptance gate (Universal + category-specific).
9. **`docs/RFC_MONKENGINE_TASK_EXECUTION.md`** — the execution contract (no implementation before an approved Execution Plan).
10. Architecture & specs: `ARCHITECTURE_V2.md`, `ENGINE_ARCHITECTURE.md`, `ENGINE.md`, `BIOMECHANICS.md`, `API_CONTRACTS.md`, `CODING_RULES.md`, `MIGRATION_RULES.md`, and the specification layer under `docs/architecture/` (MOM/MSS/JOM/VOM/PRP/PAC) plus `docs/Biomechanical Pose Specification (BPS)/`.

For any task, apply the Execution Hierarchy from `RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §4:
**user task → Orchestrator → Capability Levels → Playbook → Definition of Done → specification layer → implementation.**

---

## Development System Overview

The system is a single tree rooted at `RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`. Two orderings matter:

- **Authority hierarchy** (who overrides whom): Design Principles → Baseline → Lifecycle/Orchestrator →
  Engineering Playbook → Definition of Done → Pose Development Protocol → Specifications.
- **Execution hierarchy** (what happens first): user request enters at the Orchestrator, is classified
  and planned, then flows through Capability Levels → Playbook → Definition of Done → specification
  layer → implementation only after an approved Execution Plan (`RFC_MONKENGINE_TASK_EXECUTION.md`).

The **Development Orchestrator** is the sole receiver of every task. It decides category, capability
level, required experts, required specifications, execution plan, review plan, acceptance plan, and
knowledge capture. It does not perform the work and does not restate the rules those other documents
own.

---

## Current ACTIVE Documents

The authoritative source of truth is `docs/RFC_MONKENGINE_BASELINE.md` §3. The ACTIVE set:

| Document | Role |
| --- | --- |
| `docs/ARCHITECTURE_V2.md` | Definitive implementation spec (pipeline, ownership, invariants). |
| `docs/ARCHITECTURE_FREEZE.md` | Constitutional freeze of the architecture. |
| `docs/ENGINE_ARCHITECTURE.md` | Entry-point map + reference (joints, stages, file map). |
| `docs/ENGINE.md` | Philosophy, four layers, coordinate/axis/IK conventions. |
| `docs/CODING_RULES.md` | Permanent standing rules for all development. |
| `docs/BIOMECHANICS.md` | Human-movement correctness principles. |
| `docs/VALIDATION.md` | Validation-pose contract + Engineering Validation subsystem. |
| `docs/API_CONTRACTS.md` | Per-component read/write/prohibited contracts. |
| `docs/MIGRATION_RULES.md` | Prohibited patterns + mandatory pose rules (the enforced pose coding standard). |
| `docs/STABILIZATION_AUDIT.md` | Live per-family production-exercise stabilization tracker (forward work). |
| `docs/TOOLCHAIN_PROVISIONING.md` | Build/CI toolchain recipe (operational, required each session). |
| `docs/architecture/Movement Ownership Matrix.md` | Biomechanical movement ownership (driver/contributor/follower). |
| `docs/architecture/Movement Sequence Specification.md` | Canonical movement ordering. |
| `docs/architecture/Validation_Ownership_Matrix.md` | Which subsystem certifies each feature. |
| `docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md` | Per-joint biomechanical ownership. |
| `docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md` | Measurable pose acceptance criteria (PAC). |
| `docs/RFC_MONKENGINE_BASELINE.md` | Governance source of truth (this table's owner). |
| `docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md` | Pose-development workflow (PDP). |
| `docs/architecture/RFC_MONKENGINE_EXECUTION_MODES.md` | Execution strategy (levels, strictness). |
| `docs/RFC_MONKENGINE_ENGINEERING_PLAYBOOK.md` | Practical engineering handbook (how-to for each task class). |
| `docs/MonkEngine Design Principles.md` | The constitution (outranks all). |
| `docs/RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` | The system map. |
| `docs/RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` | The decision engine. |
| `docs/RFC_MONKENGINE_CAPABILITY_LEVELS.md` | Capability maturity (Levels 0–8). |
| `docs/RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` | The nine-stage task spine. |
| `docs/RFC_MONKENGINE_DEFINITION_OF_DONE.md` | The acceptance gate. |
| `docs/RFC_MONKENGINE_TASK_EXECUTION.md` | The execution contract. |
| `docs/RFC_MONKENGINE_ENGINEERING_TARGET_SPECIFICATION.md` | Engineering Target Specification (ETS) — transforms a request into an explicit target. |

The **seven-document biomechanical specification system** — BPS, JOM, VOM, PRP, PAC, MOM, MSS — is the
canonical reference input for every pose task.

Everything outside this set is classified by `RFC_MONKENGINE_BASELINE.md` §4 as ARCHIVE / OBSOLETE /
MERGE / DELETE and lives under `docs/HISTORICAL/` or is retired. The ARCHIVE is never auto-loaded and
never constrains new design; when an archived/obsolete doc conflicts with live code, the code wins.

---

## Current Workflow

Every task walks the Lifecycle (`RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md`):

```
Idea → Classification → Planning → Expert Review → Implementation
     → Verification → Acceptance → Knowledge Capture
```

Operational rules:

- The **Development Orchestrator** receives the task and decides its category, capability level,
  required experts, required specifications, execution plan, review plan, acceptance plan, and
  knowledge capture (`RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md` §2).
- **No implementation before an approved Execution Plan.** Coding begins only at the Implementation
  stage, and only after Planning and Expert Review are complete (`RFC_MONKENGINE_TASK_EXECUTION.md`,
  `RFC_MONKENGINE_DEVELOPMENT_LIFECYCLE.md` §10).
- The **Engineering Playbook** maps each task class (redesign, new pose, validator, engine,
  architecture, documentation) onto the PDP step vocabulary
  (`docs/architecture/RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md`).
- The **Definition of Done** is the binding gate: Universal + category-specific criteria; a single
  failing item blocks Done (`RFC_MONKENGINE_DEFINITION_OF_DONE.md`).
- **Pose/engine boundary (PRP):** a pose declares intent only; the engine owns realization
  (IK/FK/contacts/balance/reconstruction/validation). Validation poses are *diagnostic instruments* —
  fix the engine or record the reading; never retune a pose to read green.

---

## Current Compile / Test Policy

- **Compile-first (broken-windows for compilation).** A branch may never intentionally leave the
  repository in a non-compiling state. Compilation errors are **blocking defects**, fixed
  immediately, never postponed or scheduled as debt. Until compilation is restored the task is
  **incomplete**. Architecture decisions, RFCs, and roadmaps are produced only against a compiling
  codebase.
- **Green baseline.** The relevant automated verification (`./gradlew :app:testDebugUnitTest`) passes
  and no pre-existing green test is broken. The live test baseline and forward-work items are tracked
  in `docs/STABILIZATION_AUDIT.md` and `docs/HISTORICAL/TEST_BASELINE.md`.
- **Reproducible.** Given the same inputs, the result is deterministic.
- **Build toolchain (operational, per session).** The toolchain is pre-provisioned under `/tmp/kilo`
  (JDK 17, Android SDK 34, custom-cacerts, Gradle 8.7). Full recipe in
  `docs/TOOLCHAIN_PROVISIONING.md`. Required env each session:
  ```bash
  export JAVA_HOME=/tmp/kilo/jdk-17.0.19+10
  export ANDROID_HOME=/tmp/kilo/android-sdk
  export GRADLE_USER_HOME="$(pwd)/.gradle-home"
  export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/tmp/kilo/custom-cacerts -Djavax.net.ssl.trustStorePassword=changeit"
  ```
- **TLS intercept (operational note).** All HTTPS is re-signed by the Cloudflare intercept CA. For
  git pushes use `GIT_SSL_CAINFO=/tmp/kilo/github.com.pem git push origin HEAD` (re-harvest the live
  chain if it rotates). Git-ignored, never commit: `.gradle-home/ android-sdk/ proxy-ca.pem
  custom-cacerts git-proxy-ca.pem`.

---

## Current Governance

- **Authority order:** Design Principles → Baseline → Lifecycle/Orchestrator → Playbook → Definition
  of Done → PDP → Specifications (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §3).
- **Source of truth:** `RFC_MONKENGINE_BASELINE.md` §3 (the ACTIVE set above). The current
  architecture (`ARCHITECTURE_V2.md` + ACTIVE set) decides correctness; when any document conflicts
  with live code, the code wins.
- **Document classification:** every document outside ACTIVE is ARCHIVE / OBSOLETE / MERGE / DELETE
  (`RFC_MONKENGINE_BASELINE.md` §4). ARCHIVE is read-only context; OBSOLETE must not influence design;
  MERGE folds into ACTIVE then retires; DELETE is removed from the active tree.
- **Standing principles (override any prior-generation note):** prior-generation decisions are not
  design constraints; prefer the simpler solution on the current architecture; do not preserve old
  constructions solely because they predate the current architecture; backward compatibility with
  previous authoring patterns is not a goal.
- **Forward work:** the single live tracker is `docs/STABILIZATION_AUDIT.md` (and the Forward Work
  table in `RFC_MONKENGINE_BASELINE.md` §6). Historical investigations/audits live in
  `docs/HISTORICAL/` and are evidence, not guidance.

---

## CLOSED HISTORY (reference only — not engineering guidance)

> The following is a closed record of prior stabilization/migration work. It is preserved for
> traceability. It does **not** steer current engineering decisions; the ACTIVE set above and live
> code are authoritative. Detailed records live in `docs/HISTORICAL/`.

- **Engine stabilization / legacy cleanup (Architecture v2 M0–M8, Phases A–G).** Completed: pipeline
  ownership (`SkeletonPipeline.produceFrame`), solver-owns-posture, finalizer-owns-conversion,
  validator stamp-only, head-target resolver, intent carriers (`contacts`/`contactPrecedence`/
  `postureIntent`/`limbTargets`/`spineIntent`/`jointIntents`/`extremityOverridess`/
  `extremityArticulations`), Branch B/C pose migration, and direct-finalize test re-pointing. The
  surviving additive flag is `IK_STAGE_ACTIVE` (default `false`). Authoritative record:
  `docs/HISTORICAL/RFC_ENGINE_STABILIZATION.md`, `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md`,
  `docs/HISTORICAL/ARCHITECTURE_V2_ROADMAP.md`, `docs/HISTORICAL/RFC_GAP_CLOSURE.md`.
- **Issue E (two-segment spine).** `PELVIS → LUMBAR → CHEST` with independent lumbar/thoracic DOF,
  pass-through by default, zero regression. `Joint.LUMBAR(32)` added; indices `0..32` contiguous.
- **Issue F (chest-frame reconstruction).** `SkeletonPoseFinalizer.reconstructChestFrame` no longer
  overwrites authored thoracic rotation and no longer forces a symmetric-thorax assumption; uses the
  two-arg `cross(dst)` overload so the fallback frame is orthonormal. Covered by
  `ChestFrameIssueFTest`.
- **Two-segment spine / chest-frame / straight-limb fallback fixes.** Straight-limb `articulationFor`
  fallback now reads the authored node's *local* rotation (not world-relative inverse) so straight
  limbs keep their authored articulation.
- **Middle Split diagnostic instrument.** Canonical probe for the `straight=true` dropped-intent
  limitation; retarget-to-full-reach was reverted as instrument tampering. Audit:
  `docs/HISTORICAL/MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`.
- **Engine defects R1–R4** (`docs/HISTORICAL/ENGINE_DEFECT_REMEDIATION_PLAN.md`) cleared; the verified
  historical test baseline was **282/0** (see `docs/HISTORICAL/TEST_BASELINE.md`).
- **Git:** current chain and branch are reported by `git log` / `git rev-parse HEAD`; trust the live
  history over any static chain written here. `.kilocodeignore` is a committed precise context-boundary
  ignore.

---

*End of AGENTS.md.*
