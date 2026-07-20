# RFC_MONKENGINE_CAPABILITY_LEVELS.md — Capability Maturity

**Status:** ACTIVE
**Position in graph:** `docs/RFC_MONKENGINE_CAPABILITY_LEVELS.md` — the Capability Levels node of
the MonkEngine Development System (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md`). Pairs with the
Development Orchestrator and the Definition of Done.
**Scope:** engineering governance only. Defines the capability maturity the Development
Orchestrator assigns to every task. No engine, pose, or validator code is described or modified
by this document.

---

## 1. Purpose

The Development Orchestrator must, for every incoming task, first determine the **required
capability level** before it decides documents, experts, validation, or the Definition of Done.
Capability maturity is the measure of how much of the system a task may touch and how much
engineering judgment it requires.

This document defines nine levels (0–8). Each level fixes, for the Orchestrator:

- the **purpose** of work at that level,
- the **expected engineering effort**,
- the **required experts**,
- the **required specifications**,
- the **required validation**,
- the **required Definition of Done** gate.

A task is assigned the **lowest level that safely covers it**. Over-assigning a level grants
freedom the task does not need and invites unnecessary change; under-assigning blocks the work.
The Orchestrator decides the level; the level then decides everything else.

> This document describes **governance**, not implementation. It never says *how* to build,
> solve, or code. It says *what maturity a task requires* and *what must be true before it is
> Done*.

---

## 2. Level 0 — Documentation

- **Purpose.** Author, revise, or reorganize system documentation that changes no engineering
  behavior and no specification content of substance — prose, formatting, cross-references,
  structural clarity.
- **Expected engineering effort.** Trivial. A single document or a small set of documents; no
  analysis of biomechanics or architecture.
- **Required experts.** Governance / Documentation expert only.
- **Required specifications.** The document(s) being touched and the Design Principles / Baseline
  they must remain consistent with.
- **Required validation.** Editorial review for terminology consistency and authority-hierarchy
  correctness; no code, no pose, no validator checks.
- **Required Definition of Done.** Documentation authoritative (§2 Universal + §3.4 of the
  Definition of Done): consistent with the ACTIVE set, no contradiction of a higher-authority
  document, no temporary report committed, compiles/build unaffected (no code changed).

---

## 3. Level 1 — Small Documentation Changes

- **Purpose.** Modify specification or governance content of limited scope — a corrected
  checkpoint, a clarified rule, a reclassified document — where the change has engineering
  consequence but is local and low-risk.
- **Expected engineering effort.** Small. Requires reading the affected spec and its dependents to
  avoid contradiction, but no redesign.
- **Required experts.** Governance / Documentation expert, with the domain expert for the affected
  specification consulted (Biomechanics expert for BPS, Architecture expert for JOM/VOM/API, etc.).
- **Required specifications.** The changed specification, its direct dependents in the Dependency
  Graph (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §6), and the Design Principles / Baseline.
- **Required validation.** Consistency audit: the change does not contradict any document higher
  in the authority chain; dependent specs remain coherent; no duplicated or conflicting rule
  introduced.
- **Required Definition of Done.** Documentation authoritative, plus: no duplicated rule removed or
  reconciled, ownership boundaries preserved (no document now owns what another owns), and the
  change is reflected wherever the spec is referenced.

---

## 4. Level 2 — Small Pose Edits

- **Purpose.** Limited, surgical change to an existing pose: a constant, a target, a carrier
  intent, a contact — without altering the pose's biomechanical identity or ownership.
- **Expected engineering effort.** Small. Local to one pose; bounded by the existing BPS for that
  exercise.
- **Required experts.** Biomechanics expert (intent), Architecture / Ownership expert (carriers,
  no duplicated ownership), Validation expert (measure the result).
- **Required specifications.** The pose's BPS, the relevant JOM/VOM domains, PRP (pose-vs-engine
  boundary), and MSS/MOM where the edited motion is described.
- **Required validation.** The pose passes its BPS checkpoints and the PAC pipeline for that
  exercise; the engine still owns all computation (no FK/IK/constraint duplication introduced); the
  movement ownership (MOM) and sequence (MSS) are unchanged in kind.
- **Required Definition of Done.** Universal criteria + category-specific §3.1 (movement/pose):
  declares intent through the current carrier/intent model, satisfies PAC, validation measures and
  does not author, green baseline preserved, no new duplicated ownership.

---

## 5. Level 3 — Biomechanical Redesign

- **Purpose.** Rewrite an existing pose from first biomechanical principles because its current
  form is not authoritative — the movement ownership, sequence, or joint ownership must be
  re-derived from BPS → MSS → MOM → JOM, not from the old code.
- **Expected engineering effort.** Medium–large. Full re-derivation of one pose against its
  specification set; review of every owned domain.
- **Required experts.** Biomechanics expert (source of truth), Architecture / Ownership expert,
  Engine / Mathematics expert (confirm engine capabilities are used, not duplicated), Validation
  expert (certify against PAC).
- **Required specifications.** BPS, MSS, MOM, JOM, VOM, PRP, PAC, and the relevant ACTIVE
  architecture documents. The full Level-1 set for the pose plus STABILIZATION_AUDIT when a
  registered production exercise is touched.
- **Required validation.** Full PAC pipeline (BPS → JOM → MOM → MSS → VOM → Engine integration →
  Visual) as one passing cycle; the redesign is justified against biomechanics, not against the
  prior implementation; no follower leads, no joint built by a non-owner.
- **Required Definition of Done.** Universal + §3.1 (movement/pose) + §3.5 (refactor, where motion
  output must be unchanged within tolerance if the exercise identity is preserved): all PAC stages
  green, engine owns realization, no duplicated ownership, code compiles, baseline green.

---

## 6. Level 4 — New Pose Family

- **Purpose.** Introduce a whole movement family (e.g. Push-Up, Lunge, Pull-Up) so that every
  member shares one coherent MOM/MSS/JOM story and passes PAC consistently.
- **Expected engineering effort.** Large. Multiple poses, cross-member consistency, shared
  ownership patterns, family-level validation.
- **Required experts.** Biomechanics expert (family MOM/MSS), Architecture / Ownership expert
  (consistent JOM across members), Engine / Mathematics expert (shared carriers/helpers), Validation
  expert (family-wide PAC).
- **Required specifications.** BPS per member, one family MSS, one family MOM, JOM, VOM, PRP, PAC,
  and the ACTIVE architecture set. The full Level-1 set plus STABILIZATION_AUDIT.
- **Required validation.** Every member passes the full PAC pipeline; the family demonstrates
  *consistency* — same driver/contributor/follower pattern, same sequence, same joint owners across
  all members; no member silently diverges in ownership.
- **Required Definition of Done.** Universal + §3.1 for every member: family consistency verified,
  every member satisfies PAC, engine owns realization, no duplicated ownership within or across
  members, compiles, baseline green.

---

## 7. Level 5 — New Exercise Implementation

- **Purpose.** Implement a single new exercise (pose) that has no prior form, created from BPS →
  MSS → MOM → JOM → MonkEngine — distinct from a family rollout (Level 4) in that it is one
  artifact, not a consistent set.
- **Expected engineering effort.** Medium–large. One full pose authored against the specification
  layer and the engine's carrier/intent model.
- **Required experts.** Biomechanics expert (intent from BPS), Architecture / Ownership expert
  (carrier model, no duplicated ownership), Engine / Mathematics expert (use engine capabilities),
  Validation expert (PAC).
- **Required specifications.** BPS (the new exercise spec, or its creation), MSS, MOM, JOM, VOM, PRP,
  PAC, ACTIVE architecture set, full Level-1 set.
- **Required validation.** The new pose passes the full PAC pipeline as one passing cycle; intent
  declared, engine realizes; correct driver (MOM), correct sequence (MSS), correct joint owners
  (JOM), correct validation domains (VOM).
- **Required Definition of Done.** Universal + §3.1 (movement/pose): BPS realized, PAC fully
  green, engine owns realization, no duplicated ownership, registered reachable through the live
  pose registry, compiles, baseline green.

---

## 8. Level 6 — Validator Development

- **Purpose.** Develop or extend a validation capability — a check, a domain, or a measurement —
  owned by exactly one VOM domain, that *measures* a feature and never authors the pose.
- **Expected engineering effort.** Medium–large. Requires defining the measurable rule, its owner
  domain, and its place in the VOM order, without overlapping a sibling domain.
- **Required experts.** Validation expert (domain owner), Biomechanics expert (what correct looks
  like), Architecture / Ownership expert (ownership boundary vs JOM), Engine / Mathematics expert
  (the measurement math, owned once).
- **Required specifications.** VOM (the domain being developed), BPS (the target it measures), JOM
  (the joints it reads), PRP (validation measures, never fixes), PAC (how the check feeds
  acceptance), Design Principles (validation never authors poses).
- **Required validation.** The new validator is itself validated: it measures correctly, it does
  not mutate the pose, it sits in the correct VOM order, and it does not duplicate a sibling
  domain's responsibility; existing poses it is applied to still pass or fail on true grounds.
- **Required Definition of Done.** Universal + §3.3 (validation): the validator measures and does
  not fix or retune the instrument to pass; acceptance criteria are objective; the new domain is
  single-owned in VOM; compiles; baseline green (no regression in existing validation).

---

## 9. Level 7 — Engine Architecture

- **Purpose.** Change the engine's architecture — a subsystem, a stage, an ownership boundary, or
  the pipeline — where the biomechanical intent of poses is preserved but the mechanism realizing
  it evolves.
- **Expected engineering effort.** Large. Touches shared machinery; must preserve every pose's
  realized behavior (byte-identical where the contract requires it).
- **Required experts.** Engine / Mathematics expert (the change), Architecture / Ownership expert
  (ownership boundaries, API contracts), Biomechanics expert (intent preserved), Validation expert
  (regression across poses).
- **Required specifications.** ARCHITECTURE_V2, ENGINE_ARCHITECTURE, API_CONTRACTS, MIGRATION_RULES,
  PRP, and the full specification layer (BPS/MSS/MOM/JOM/VOM/PAC) for regression scope.
- **Required validation.** Full regression: every affected pose still satisfies PAC; the engine
  still owns all computation; no pose compensates for the change; ownership boundaries intact;
  API contracts respected.
- **Required Definition of Done.** Universal + §3.2 (engine/mathematical): the mathematical
  responsibility lives in exactly one engine component, no other component reimplements it, behavior
  verified against biomechanical intent, full pose regression green, compiles, baseline green.

---

## 10. Level 8 — Core Engine Evolution

- **Purpose.** Evolve the core engine — a fundamental mathematical primitive, the solver, or the
  kinematic foundation — that underpins every pose and validator. The highest-maturity change;
  it may restructure how realization works while preserving the Design Principles and the
  specification layer's meaning.
- **Expected engineering effort.** Very large. System-wide blast radius; requires whole-system
  verification and explicit governance sign-off.
- **Required experts.** Engine / Mathematics expert (lead), Architecture / Ownership expert,
  Biomechanics expert, Validation expert, Governance / Documentation expert (the evolution must be
  documented as governance, not as a fossil).
- **Required specifications.** The complete ACTIVE set (Baseline §3), the full seven-document
  specification system (BPS/JOM/MOM/MSS/VOM/PRP/PAC), ARCHITECTURE_V2, API_CONTRACTS, MIGRATION_RULES,
  and the Design Principles (which must remain stable across the evolution).
- **Required validation.** Complete system verification: every pose passes PAC, every validator
  measures correctly, the engine owns all mathematics with no duplication, ownership is single
  everywhere, and the Design Principles are demonstrably preserved (the principles do not bend to
  the implementation).
- **Required Definition of Done.** Universal + §3.2 (engine/mathematical) at system scale: single
  ownership of every mathematical responsibility, no duplicated FK/IK/constraints/shared math, full
  pose + validator regression green, principles stable, compiles, baseline green, and the evolution
  is recorded in governance (no temporary report committed as a repo file).

---

## 11. Level Selection Rules (for the Orchestrator)

- Assign the **lowest** level that covers the task's true scope.
- A task that *only* edits prose is Level 0/1, never Level 2.
- A task that changes a pose's behavior is at least Level 2; if it re-derives the pose from
  biomechanics, Level 3.
- A task that adds one new exercise is Level 5; a coherent set is Level 4.
- A task that adds or changes a *check* is Level 6.
- A task that changes a *shared engine subsystem* is Level 7; a change to a *core primitive* is
  Level 8.
- When a task spans levels (e.g. a new pose plus its validator), the Orchestrator decomposes it
  into the constituent level tasks and sequences them per the Execution Hierarchy
  (`RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md` §4); each constituent is accepted at its own DoD before
  the whole is accepted.

---

## 12. Relationship to Other MonkEngine RFCs

```
RFC_MONKENGINE_DESIGN_PRINCIPLES.md          (constitution — outranks all)
        │
RFC_MONKENGINE_BASELINE.md                  (governance source of truth)
        │
RFC_MONKENGINE_DEVELOPMENT_SYSTEM.md        (the system map; this level set is the
        │                                      "Capability Levels" node)
        │
RFC_MONKENGINE_DEVELOPMENT_ORCHESTRATOR.md  (uses these levels to classify every task)
        │
RFC_MONKENGINE_DEFINITION_OF_DONE.md        (the gate each level's DoD column cites)
        │
RFC_MONKENGINE_POSE_DEVELOPMENT_PROTOCOL.md (workflow; the level selects which steps run)
```

- **DESIGN_PRINCIPLES** outranks every level's decisions.
- **BASELINE** names the ACTIVE set each level's "required specifications" draws from.
- **DEVELOPMENT_SYSTEM** places this document as the Capability Levels node.
- **ORCHESTRATOR** consumes these levels: it decides the level, then the level decides experts,
  specs, validation, and DoD.
- **DEFINITION_OF_DONE** is the bar every level's "Required Definition of Done" points to.
- **PLAYBOOK (PDP)** is the workflow the level's freedom is expressed through.

---

## 13. Terms

- **Capability level** — the maturity a task requires (0–8); assigned by the Orchestrator.
- **Required experts** — the single owners (per responsibility) a level must involve.
- **Required specifications** — the ACTIVE documents a level must load and obey.
- **Required validation** — the measurement a level must pass (never authorship).
- **Required Definition of Done** — the acceptance gate (Universal + category-specific) a level's
  task must clear.

---

## 14. Status

**ACTIVE.** The Capability Levels node of the MonkEngine Development System. Created without
modifying any existing RFC. It is the canonical maturity scale the Development Orchestrator applies
to every engineering task.

---

*End of RFC_MONKENGINE_CAPABILITY_LEVELS.md.*
