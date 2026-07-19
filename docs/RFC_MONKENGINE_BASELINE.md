# RFC_MONKENGINE_BASELINE.md — MonkEngine Governance Reset & Architectural Source of Truth

**Status:** PROPOSED baseline (governance reset).
**Author:** governance audit of the migration-era documentation.
**Scope:** documentation governance only. No engine, pose, or validator code is modified by this document.

---

## 0. Purpose

MonkEngine has completed its architecture migration. Architecture v2 is frozen and its
legacy-cleanup (Phases A–G) and Branch A/B/C work are merged and green (baseline **282 / 0**).
The migration produced a large body of RFCs, audits, migration reports, roadmaps, and temporary
rules. That corpus is now a liability: it constrains future design with historical migration
assumptions and duplicate/contradictory source-of-truth claims.

This document resets governance. It:

1. Names a single, current **source of truth** for architecture.
2. Classifies every migration-era document into **ACTIVE / HISTORICAL / OBSOLETE / MERGE / DELETE**.
3. For each, states **why**.
4. Retires the migration framing so future work is driven by the current architecture.

### 0.1 Governing Principles (override any migration-era assumption)

These principles are the lens for every classification below and for all future design. They take
precedence over any historical document, comment, or "we've always done it this way" argument.

1. **Historical migration decisions are not design constraints.** A choice made to land a migration
   step (a Branch/Phase workaround, a flag-gated path, a temporary bridge) is evidence of *what was
   done*, never of *what must be done next*. Do not cite migration history to justify a future design.
2. **Prefer the simpler solution on the current architecture.** Whenever the current MonkEngine
   (Architecture v2) already provides a clean primitive, use it. Do not reproduce a more complex
   construction that only existed because the engine lacked the capability during migration.
3. **Do not preserve old constructions solely because they existed during migration.** A pattern that
   survives only as a fossil of the migration (e.g. a manual override kept "because the pose used it
   mid-migration") is a candidate for simplification or removal, not preservation.
4. **Backward compatibility with previous authoring patterns is not a design goal.** New poses and
   refactors are written against the current carrier/intent model. Do not retain legacy authoring
   shapes to avoid touching existing poses unless a concrete, current-architecture reason requires it.
5. **The current MonkEngine architecture is the only source of truth.** `ARCHITECTURE_V2.md` (and the
   ACTIVE set in §1) decides correctness. When any document — including this one's HISTORICAL/OBSOLETE
   lists — conflicts with the live code, the code wins.

> Consequence for this audit: documents are classified by what they *mean for future design*, not by
> sentiment. Anything kept is kept only because it adds value under the current architecture; anything
> migration-only is MERGED or DELETED without sentiment.

---

## 1. Single Source of Truth (ACTIVE)

The following documents are the canonical, current architecture/working-set. Everything else is
derived, historical, or removed.

| Document | Role | Why ACTIVE |
| --- | --- | --- |
| `docs/ARCHITECTURE_V2.md` | Definitive implementation spec (pipeline, ownership, invariants). | Frozen by `ARCHITECTURE_FREEZE.md`; the live engine implements it. |
| `docs/ARCHITECTURE_FREEZE.md` | Constitutional freeze of the architecture. | Binds all future work to v2; no re-litigation allowed. |
| `docs/ENGINE_ARCHITECTURE.md` | Entry-point map + reference (joints, stages, file map). | Current orientation doc; points to the ACTIVE set. **Needs a small accuracy pass** (see §5). |
| `docs/ENGINE.md` | Philosophy, four layers, coordinate/axis/IK conventions. | Current architectural constitution. |
| `docs/CODING_RULES.md` | Permanent standing rules for all development. | Stable defaults; superseded only by explicit update. |
| `docs/BIOMECHANICS.md` | Human-movement correctness principles. | Engine-agnostic; remains valid. |
| `docs/VALIDATION.md` | Validation-pose contract + Engineering Validation subsystem. | Current correctness contract. |
| `docs/API_CONTRACTS.md` | Per-component R/W/prohibited contracts. | Frozen with v2; the live contract object. |
| `docs/MIGRATION_RULES.md` | Prohibited patterns (A1–A10) + mandatory pose rules (B1–B8). | Frozen with v2; enforced coding standard. Still useful as the live "do/don't" for poses. |
| `docs/STABILIZATION_AUDIT.md` | Live per-family production-exercise stabilization tracker. | ACTIVE, ongoing (P1/P2 TODOs remain). The one forward-looking work tracker post-migration. |
| `docs/TOOLCHAIN_PROVISIONING.md` | Build/CI toolchain recipe. | Operational, not architectural; still required for every session. |
| `docs/architecture/Movement Ownership Matrix.md` | Biomechanical *movement* ownership (driver/contributor/follower). | Engine-agnostic biomechanics; valid even if engine rewritten. |
| `docs/architecture/Movement Sequence Specification.md` | Canonical movement ordering (prep→init→propagate→…). | Engine-agnostic biomechanics. |
| `docs/architecture/Validation_Ownership_Matrix.md` | Which subsystem certifies each feature. | Engine-agnostic validation domain; consistent with current validator. |
| `docs/architecture/RFC_JOINT_OWNERSHIP_MATRIX.md` | Per-joint biomechanical ownership. | Engine-agnostic; still accurate (33-joint set). |
| `docs/architecture/RFC_POSE_ACCEPTANCE_CRITERIA.md` | Measurable pose acceptance criteria (PAC). | Target acceptance process; consistent with intent-carrier model. |
| `docs/architecture/Plan-Operating-the-Biomechanical-Specification-System.md` | Usage manual for the BPS/JOM/VOM/PRP/PAC system. | Operational methodology; consistent with current ownership model. |
| `docs/HISTORICAL/ARCHITECTURE_V2_ROADMAP.md` | The 9-phase implementation roadmap (Phases 0–8). | The roadmap is **complete** (282/0) and is the narrative of how v2 was built; retained as the canonical build record. |

> **Note on AGENTS.md:** `AGENTS.md` is the session memory anchor (auto-injected), not a docs/
> governance file. Its migration-era narrative (Engine stabilization, Branch A/B/C, Issues E/F)
> is now superseded by this reset. **Action:** trim AGENTS.md to point at `RFC_MONKENGINE_BASELINE.md`
> as the governance source of truth and drop the per-RFC migration narrative (see §6).

---

## 2. HISTORICAL — retained for context, no longer normative

Useful for understanding *why* a decision was made. May be read, but must **not** constrain new
design unless explicitly referenced. These are evidence/archive, not source of truth.

> Under §0.1, retention here is **only** because the document explains a current-architecture
> decision (e.g. the diagnostic-instrument rule, the explicit-override rationale). A HISTORICAL
> document is **not** a license to keep a complex construction alive. Where a simpler current-architecture
> solution exists, prefer it; do not preserve an old pattern just because a HISTORICAL report describes it.

| Document | Why HISTORICAL |
| --- | --- |
| `docs/ENGINE_HISTORY.md` | Investigation-archive index. Self-labeled HISTORICAL; evidence only. |
| `docs/HISTORICAL/README.md` | Inventory of the archive. Self-labeled HISTORICAL. |
| `docs/HISTORICAL/ENGINE_INVESTIGATION_REPORT.md` | UNI-1…12 register. All resolved; retained as source-of-record. |
| `docs/HISTORICAL/ENGINEERING_VALIDATION_AUDIT.md` | Per-pose defect audit; verdicts now match code. Evidence. |
| `docs/HISTORICAL/ENGINE_RESPONSIBILITY_AUDIT_NEW.md` | W1–W10 workaround inventory. Superseded by W1/W4. Evidence. |
| `docs/HISTORICAL/ENGINE_AUTOMATIC_ORIENTATION_AUDIT.md` | Traces W1 automatic derivation. Superseded. Evidence. |
| `docs/HISTORICAL/PELVIC_HIP_COMPLEX_INVESTIGATION.md` | Q1–Q14 pelvis/hip deep-dive. Resolved; retained as reasoning. |
| `docs/HISTORICAL/MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md` | Applies the diagnostic-instrument rule; reverts green-tuning. Retained — carries a *live governing rule* that still applies to validation poses. |
| `docs/HISTORICAL/W1_IMPLEMENTATION_REPORT.md` | W1 report. Merged. Record. |
| `docs/HISTORICAL/TEST_BASELINE.md` | Test-baseline progression snapshot. Useful as a regression reference; not normative. |
| `docs/HISTORICAL/POSE_ENGINE_RESPONSIBILITY.md` | Pre-W1 pose↔engine boundary investigation. Resolved by W1. Evidence. |
| `docs/HISTORICAL/CAPABILITY_GAP_REPORT.md` | Gaps 1–7 reconciliation. All closed. Record. |
| `docs/HISTORICAL/RFC_INTENT_LAYER.md` | §1.1 intent model design. Carriers now live. Record. |
| `docs/HISTORICAL/RFC_INTENT_TAXONOMY.md` | ROM/Shape/Articulation taxonomy. Adopted. Record. |
| `docs/HISTORICAL/RFC_INTENT_BUILDER_REWRITE.md` | Current-state audit proving Branch B is a rewrite. Done. Record. |
| `docs/HISTORICAL/RFC_DECLARATIVE_AUTHORING.md` | `BasePose` helper inventory (intent/obsolete). Done. Record. |
| `docs/HISTORICAL/RFC_DECLARATIVE_POSE_AUTHORING.md` | Branch B design (G1–G7, B0–B6). Done. Record. |
| `docs/HISTORICAL/RFC_EXECUTION_CONTRACT.md` | Execution contract for IK/Solver/Finalizer/FK. Live but subsumed by `API_CONTRACTS.md`. Record. |
| `docs/HISTORICAL/RFC_CONSISTENCY_CHANGELOG.md` | Resolved 8 contradictions across v2 RFCs. Done. Record. |
| `docs/HISTORICAL/RFC_LEGACY_ENGINE_RETIREMENT.md` | Legacy blocker inventory (L1–L8). All removed. Record. |
| `docs/HISTORICAL/RFC_PHASE_I_CLOSURE.md` | Closed Branch A; split Branch B. Done. Record. |
| `docs/HISTORICAL/RFC_ENGINE_PIPELINE.md` | `SkeletonPipeline` design. Merged; `ARCHITECTURE_V2.md` is the live spec. Record. |
| `docs/HISTORICAL/RFC_ENGINE_STABILIZATION.md` | Stabilization plan S0–S3. All phases done; redundant with v2. Record. |
| `docs/HISTORICAL/ENGINE_DEFECT_REMEDIATION_PLAN.md` | R1–R4 remediation. Exit met (282/0). Record. |
| `docs/HISTORICAL/IMPLEMENTATION_BRIDGE.md` | Type/field bridge for W1 `SkeletonPose`. Merged; detail superseded by v2. Record. |
| `docs/HISTORICAL/PHASE_D_DIRECT_FINALIZE_REPOINTING.md` | Test re-pointing audit. Merged (283/0). Record. |
| `docs/HISTORICAL/PHASE3_FINALIZER_MIGRATION_REPORT.md` | Finalizer-conversion report. Merged. Record. |
| `docs/HISTORICAL/PHASE_E_PLAN.md` | L1 bridge deletion plan. Merged. Record. |
| `docs/HISTORICAL/PHASE_F_FLAG_RETIREMENT_AUDIT.md` | Flag-retirement audit. Matches current code. Record. |
| `docs/HISTORICAL/ENGINE_FIX_PR_PROMPTS.md` | 11 early PR prompts. Mostly implemented; retained as history. |
| `docs/HISTORICAL/THORACIC_FAMILY_MIGRATION_REPORT.md` | Thoracic-family rewrite record. Concrete per-pose record; not normative. |
| `docs/HISTORICAL/VERTICAL_PULL_FAMILY_MIGRATION_REPORT.md` | Vertical-pull rewrite record. Concrete record. |
| `docs/HISTORICAL/HIP_FLEXOR_FAMILY_MIGRATION_REPORT.md` | Hip-flexor migration record. Concrete record. |
| `docs/HISTORICAL/LUNGES_STEPUPS_FAMILY_MIGRATION_REPORT.md` | Lunge/step-up rewrite record. Concrete record. |
| `docs/HISTORICAL/HAMSTRING_FAMILY_MIGRATION_REPORT.md` | Hamstring migration record. Concrete record. |
| `docs/HISTORICAL/BIRD_DOG_FAMILY_MIGRATION_REPORT.md` | Bird-dog migration record. Concrete record. |
| `docs/HISTORICAL/COBRA_STRETCH_FAMILY_MIGRATION_REPORT.md` | Cobra migration record. Concrete record. |
| `docs/HISTORICAL/MANUAL_OVERRIDE_REMOVAL_REPORT.md` | `MANUAL_OVERRIDE` removal record. Concrete record. |
| `docs/POSE_MIGRATION_REPORT.md` | Orientation-workaround removal report (W1-class). Merged; retained as the canonical "explicit override" rationale. |

---

## 3. OBSOLETE — superseded; should no longer influence design

Superseded by newer architecture. Reading them to justify a future decision is an error. They may
be deleted or moved to a frozen archive; they must not be cited as guidance.

> Under §0.1 principles 2–4, anything an OBSOLETE document describes that still exists in code is a
> candidate for **simplification or removal** — not preservation. Do not retain a migration-era
> construction for backward-compatibility or "because it was there." If the current architecture
> offers a simpler path, take it.

| Document | Why OBSOLETE |
| --- | --- |
| `docs/HISTORICAL/RFC_BRANCH_B_IMPLEMENTATION.md` | Branch B plan (B0–B6). Superseded in full by `RFC_BRANCH_B_REPLAN.md`, which is itself done. No new guidance. |
| `docs/HISTORICAL/RFC_BRANCH_B_REPLAN.md` | Re-scoped Branch B. **Completed and merged**; its re-scoping is now the shipped behavior in `ARCHITECTURE_V2.md`. The plan text no longer constrains anything. |
| `docs/HISTORICAL/RFC_BRANCH_C_EXTREMITY_ARTICULATION.md` | Branch C design. **Completed and merged** (`extremityArticulations` live, 283/0). Superseded by `ARCHITECTURE_V2.md` / `API_CONTRACTS.md`. |
| `docs/HISTORICAL/RFC_ENGINE_CLEANUP_PLAN.md` | Legacy-engine deletion plan (Phases A–G). **Completed and merged** (HEAD ebab2d6). The legacy is gone; the plan is spent. |
| `docs/HISTORICAL/RFC_GAP_CLOSURE.md` | Gap 1–7 → M0–M8 rollout. **All milestones done; flags deleted.** Spent. |
| `docs/HISTORICAL/RFC_LEGACY_ENGINE_RETIREMENT.md` | (also listed under HISTORICAL above by type) — legacy blockers all removed; the live engine has none. Treating as OBSOLETE for governance clarity. |
| `docs/architecture/Audit-Redesign-Standard-PushUp.md` | Mid-migration variant-2 audit/redesign of `StandardPushUpPose`. Written before Branch B/C and v2 M0–M8 landed; its "accepted-with-issues" verdict reflects pre-finalization pose code. The helpers it flags (`buildTorso`, bare `WRIST.set`) were later reclassified as legitimate **Shape Constraints** / carrier-backed. Effectively superseded by the completed v2 work. |

> The Branch A/B/C plans, `RFC_BRANCH_B_REPLAN`, `RFC_ENGINE_STABILIZATION`, and all migration RFCs
> are the documents the reset targets most directly. They were the migration's steering docs; the
> migration is over, so they no longer steer.

---

## 4. MERGE — fold into a newer RFC, then retire the original

Content worth keeping should be merged into the canonical source; the standalone original is then
deleted (or moved to HISTORICAL).

| Document | Merge target | Why MERGE |
| --- | --- | --- |
| `docs/HISTORICAL/RFC_ENGINE_PIPELINE.md` | `docs/ARCHITECTURE_V2.md` §3 (pipeline) | Its `SkeletonPipeline` design is already the live §3 pipeline. Any nuance not in v2 should be folded in; then retire. |
| `docs/HISTORICAL/RFC_EXECUTION_CONTRACT.md` | `docs/API_CONTRACTS.md` | Per-stage contract is already the live `API_CONTRACTS.md`. Fold any missing clause, then retire. |
| `docs/HISTORICAL/IMPLEMENTATION_BRIDGE.md` | `docs/ARCHITECTURE_V2.md` §1 / `API_CONTRACTS.md` | The field/flag mapping it bridged is now the shipped shape. Fold remaining notes, then retire. |
| `docs/HISTORICAL/ENGINE_FIX_PR_PROMPTS.md` | `docs/ENGINE_ROADMAP.md` (or this baseline's appendix) | The still-relevant subset (UNI-9 surfacing, future-exercise confirmation) already lives in `ENGINE_ROADMAP.md`; merge the residual and retire. |
| `docs/ENGINE_ROADMAP.md` | this baseline (§7 "Future Work") | The live roadmap's open items (UNI-9, UNI-12, trunk DOFs, generalized contacts, motion continuity) should be carried forward into `RFC_MONKENGINE_BASELINE.md` as the single forward-work list, and `ENGINE_ROADMAP.md` retired to avoid two "live" trackers. |
| `docs/ENGINE_HISTORY.md` | this baseline (§2 index) | Its archive index duplicates this document's HISTORICAL list. Fold the narrative into §2, then retire. |

---

## 5. DELETE — completed migration / temporary planning only

Contains only finished migration work or temporary planning. No forward-looking design value.
Safe to remove from the active tree (optionally gzip into a `docs/archive-raw/` tombstone if a
paper trail is legally desired — but they should not appear in the docs navigation or searches).

| Document | Why DELETE |
| --- | --- |
| `docs/HISTORICAL/RFC_BRANCH_B_IMPLEMENTATION.md` | (alternative to OBSOLETE) Pure migration plan, fully done; no residual design content. Delete. |
| `docs/HISTORICAL/PHASE_E_PLAN.md` | One-shot flag-deletion plan; executed. No residual value. |
| `docs/HISTORICAL/PHASE_F_FLAG_RETIREMENT_AUDIT.md` | One-shot audit; matches current code. Delete. |
| `docs/HISTORICAL/PHASE3_FINALIZER_MIGRATION_REPORT.md` | One-shot migration report; merged. Delete. |
| `docs/HISTORICAL/PHASE_D_DIRECT_FINALIZE_REPOINTING.md` | One-shot test-hygiene audit; merged (283/0). Delete. |
| `docs/HISTORICAL/RFC_INTENT_BUILDER_REWRITE.md` | Proved carriers were dead → triggered Branch B. Branch B done; proof spent. Delete. |
| `docs/HISTORICAL/RFC_CONSISTENCY_CHANGELOG.md` | Resolved contradictions in now-retired RFCs. Spent. Delete. |
| `docs/HISTORICAL/RFC_PHASE_I_CLOSURE.md` | Administrative closure of Branch A. Spent. Delete. |
| `docs/HISTORICAL/CAPABILITY_GAP_REPORT.md` | Gap closure record; all closed. Delete (or fold summary into HISTORICAL note). |
| `docs/HISTORICAL/ENGINE_FIX_PR_PROMPTS.md` | (if not merged) Eleven prompts, all implemented. Delete after merge. |
| `docs/HISTORICAL/TEST_BASELINE.md` | (optional) A moving baseline snapshot; the truth is `./gradlew :app:testDebugUnitTest`. If kept, demote to HISTORICAL. Recommend DELETE to prevent stale "282/0" claims from being treated as normative. |

> **Recommendation:** DELETE the above 11. MERGE-then-retire the §4 set. Move the entire rest of
> `docs/HISTORICAL/` plus `docs/POSE_MIGRATION_REPORT.md` into a single `docs/archive/` folder that
> is excluded from the documentation map and from AGENTS.md references, so they remain retrievable
> but never "live."
>
> **Simplification mandate (§0.1):** deleting the migration docs is the documentation half. The same
> principle applies to *code and poses*: once the migration framing is gone, any pose/engine
> construction that survived only as a migration fossil (manual overrides, flag-gated paths, bridge
> shims, dual-write shapes) should be simplified to the current-architecture equivalent or removed.
> Prefer the simpler solution; backward compatibility with previous authoring patterns is not a goal.

---

## 6. AGENTS.md governance reset

`AGENTS.md` currently carries a long migration narrative (Engine stabilization, Branch A/B/C,
Issues E/F, Compile-first policy, Toolchain, Test baseline). After this reset:

- **Keep:** Compile-first policy (still valid), Toolchain provisioning pointer, Git/branch notes.
- **Replace:** the entire "Engine stabilization (RFC_ENGINE_STABILIZATION)" block and the
  Issue E/F detail with a single pointer:
  > *Architecture is frozen (ARCHITECTURE_FREEZE.md / ARCHITECTURE_V2.md). Full migration history
  > is archived under `docs/archive/`; the governing source of truth is `docs/RFC_MONKENGINE_BASELINE.md`.*
- **Remove:** references to `RFC_BRANCH_B_REPLAN`, `RFC_ENGINE_STABILIZATION`, and the per-RFC
  migration status as if they were live steering docs. State plainly that migration history is not a
  design constraint (§0.1) and that new work is written against the current architecture only.

---

## 7. Future Work (carried from ENGINE_ROADMAP.md)

The only live forward-looking list. Future design is driven by this, not by migration RFCs.

| Id | Item | Layer |
| --- | --- | --- |
| UNI-9 | Surface (don't silently bend) a `straight=true` limb whose target sits inside proximal-bone length. | Engine + Validation |
| UNI-12 | Confirm natural-supportability claim for Front Split, Cossack, Bulgarian, Pistol, Single-leg RDL, Horse Stance, etc. | Pose / Validation |
| Trunk DOFs in solver | Make `CHEST`/`LUMBAR` free DOFs for trunk-contact poses. | Engine |
| Generalized contacts | Principled multi-contact topologies (seated hip + planted hands). | Engine |
| Deeper motion continuity | First-class shared motion/easing model. | Pose |
| P1/P2 stabilization | The open TODOs in `STABILIZATION_AUDIT.md` (wall geometry, step contact, cobra/superman lumbar, kettlebell hinge, burpee foot translation, stretch-family contacts, cleanup). | Pose |

---

## 8. Resolved, do-not-re-litigate

These were closed during the migration and must not be re-opened unless regressed:

- UNI-1 (pelvis-only solver → posture pass), UNI-2/UNI-6 (straight/intent fidelity rules),
  UNI-3 (hip ROM), UNI-4 (solver tilt axis), UNI-5 (axis-label drift), UNI-7 (clavicle/scapula),
  UNI-11 (hip-center consistency), Issue E (two-segment spine), Issue F (chest-frame no-overwrite),
  Branch A/B/C (all carriers live), legacy-engine cleanup (Phases A–G), R1–R4 (engine defects).

---

## 9. Rules for the new baseline

1. **One source of truth.** `ARCHITECTURE_V2.md` (+ `API_CONTRACTS.md`, `MIGRATION_RULES.md`,
   `CODING_RULES.md`) governs architecture. No migration RFC may override it.
2. **Archive is read-only memory.** `docs/archive/` (formerly `HISTORICAL/` + migration reports)
   is evidence. When it conflicts with code, code wins.
3. **No migration framing.** Future RFCs are written against the current architecture, never as
   "Branch X" or "Phase Y" migration steps.
4. **Validation poses stay instruments.** `VALIDATION.md` §2 and `MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`
   govern: fix the engine, never retune the probe.
5. **Single forward tracker.** `STABILIZATION_AUDIT.md` (live pose work) + §7 of this document
   (architecture work) are the only open-work lists.
6. **Migration history is not a constraint (§0.1).** A past migration decision justifies nothing
   about future design. Do not preserve a construction just because it existed during migration.
7. **Prefer the simpler current-architecture solution.** Where the current MonkEngine already provides
   a clean primitive, use it; do not reproduce a more complex migration-era pattern.
8. **No backward-compatibility goal.** New and refactored poses are written against the current
   carrier/intent model. Legacy authoring shapes are simplified or dropped, not retained to avoid churn.

---

*End of RFC_MONKENGINE_BASELINE.md.*
