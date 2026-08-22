# Runtime Skeleton Architecture

**Status:** READY FOR ARCHITECTURE FREEZE — pre-freeze architecture audit resolved (see §9 Findings Resolution Index).
**Scope:** Architecture only. This document defines ownership, responsibility, dependency, canonical source, lifetime, and subsystem boundaries. It deliberately does not define structures, field layouts, algorithms, execution detail beyond phase ordering, or validation algorithms.
**Provenance:** Produced from the audit cycle (`ARCHITECTURAL_AUDIT_SKELETON_MODEL.md`, `AUDIT_RUNTIME_SKELETON_MODEL.md`), design review (`DESIGN_REVIEW_RUNTIME_SKELETON.md`), dependency graph (`DEPENDENCY_GRAPH_RUNTIME_SKELETON.md`), semantic analysis (`SEMANTIC_ANALYSIS_RUNTIME_SKELETON.md`), and domain analysis (`DOMAIN_ANALYSIS_SKELETON.md`). Facts contradicted by source code were corrected against the code before freezing.

**Freeze rule:** Two independent architects reading this document must arrive at the same architecture. Every architectural object below has exactly one canonical name, one owner, one producer, defined consumers, and a defined lifetime. No object has two meanings; no information has two canonical sources.

---

## 1. Canonical Vocabulary

Each concept has exactly one canonical name. The synonyms in the right column are deleted from architectural usage; they must not appear in contracts, reviews, or new documents.

| Canonical name | Deleted synonyms |
|---|---|
| Pose Intent | pose intent declarations; intent carriers (as a concept name); §1.1 (as a concept name) |
| Pose State | IK Result State; result state; output state; §1.2 (as a concept name) |
| Settled Geometry | solved limb transforms; limb solve results; intermediate geometry; posture settlement; posture adjustments; posture resolution; local transform state; world transform state |
| Contact Declaration | contact; contacts (as a concept name); contact spec |
| Contact Precedence | precedence list; priority order |
| Contact Conflict Resolution | conflict resolution; precedence resolution |
| Contact Re-Solve | re-bake; re-baking; contact limb re-bake |
| Limb Target | limb endpoint target; world target (as a concept name); endpoint declaration |
| Relative Articulation | joint intent; jointIntents (as a concept name); per-joint rotation intent |
| Spine Intent | spine curve (as a concept name); spineIntent (as a concept name); single spine declaration |
| Posture Intent | coarse posture; posture kind; posture family |
| Head Target | gaze target; gaze; headTarget (as a concept name); head target intent |
| Extremity Override | manual override; extremity opt-out; orientation mode (as a concept name) |
| Extremity Articulation | wrist articulation; ankle articulation (as concept names); extremity rotation intent |
| Heading | extremity heading; facing direction |
| Support Declaration | supported points; support contacts; support configuration (as a concept name) |
| Support Point | support body point; body contact point |
| Environment | environment definition (as a concept name); world context |
| Camera Definition | camera parameters; camera configuration; camera input |
| Motion Driver | motion drivers; choreography driver |
| Production Metadata | pose metadata; exercise metadata |
| Two-Bone IK Solve | 2-bone analytical solve; IK 2-bone solve; analytic solve |
| Straight-Limb Fallback | straight fallback; straight-limb bend fallback; straight-intent fallback |
| Bone-Length Invariant | bone-length-exact invariant; exact-length rule |
| Default Pole | pole default; fallback pole |
| Pole | bend-direction vector; pole vector |
| Limb Solve Result | IK result; ikResult (as a concept name); solve outcome |
| Root Placement | root exact transform; pelvis seeding; posture seeding |
| Contact Honor | contact translate/tilt; honoring contacts |
| Posture CCD | true posture CCD; posture pass; residual pass |
| Inter-Frame Smoothing | inter-frame temporal relaxation; temporal smoothing; frame relaxation |
| Frame Conversion | world↔local conversion; world↔local frame conversion; frame conversion step |
| Tilt Cancel | relative tilt cancel; relative-rotation resolution; tilt cancellation |
| Chest-Frame Reconstruction | chest reconstruction; thorax frame rebuild |
| Head-Target Resolution | head-target resolution; resolveHeadTarget (as a concept name); gaze resolution |
| Extremity Derivation | extremity derivation; derived extremity orientations; extremity orientation derivation; foot/hand derivation |
| Flatten | flatten to nodes; flattening; publish step |
| FK Propagation | forward kinematics traversal; FK chain walk |
| Rule Checks | validation rules; biomechanical checks |
| Validation Stamp | stamp; state stamp; validation stamp production |
| Clamp Stamp | maxIkClampAmount (as a concept name); clamp amount state |
| Straight-Intent-Dropped Flag | dropped-straight flag; straight loss marker |
| Bone-Lengths-Verified Flag | verified-bone-lengths state |
| Root Translation Delta / Root Rotation Delta | solver displacement stamps; root deltas |
| Hip ROM Stamp | hipRomStamps (as a concept name); ROM decomposition stamp |
| Bilateral Symmetry Delta | symmetry deviation stamp |
| Bilateral Opposite Bend | opposite-bend stamp |
| Finalized Pose | outputPose buffer; finalized skeleton; produced frame |
| Frame History | previous/prePrevious copies; pose history; dynamics history |
| Runtime Context Injection | environment stamping; context mutation; pipeline side effect |
| Intent Builder | authoring mutator; sole-mutator surface; heading builder (folded into Intent Builder) |

Naming rules:

1. A carrier and its value share one concept name. "Contact Declaration" names the concept; `ContactSpec`, `contacts`, `limbTargets`, etc. are existing runtime identifiers, not competing concept names.
2. A verb form of a concept (e.g., "Head-Target Resolution") refers to the same object as the noun form; both are listed in the register with one identity.
3. No contract may use a term that is absent from this vocabulary or from the object register (§4).

---

## 2. Identifier Namespace

There is exactly **one identifier namespace for body structure**: the `Joint` enumeration (33 identifiers, indices 0–32). It is a semantic label namespace, not a type system. Exactly one structural category applies to each identifier. The `Extremity` enumeration (four values) identifies extremities for intent and derivation purposes; it is a second, separate namespace and never indexes body structure.

### 2.1 Structural categories

| Category | Definition (architectural) |
|---|---|
| ROOT | The single world-anchored body node whose placement positions the entire skeleton. Sole member: PELVIS. |
| SEGMENT | A rigid-body link without independent rotational freedom; carries its parent-relative orientation only as authored segment orientation. Members: LUMBAR, CHEST. |
| ARTICULATION | Anatomical joint with declared rotational freedom. Members: NECK_END, CLAVICLE_A, SCAPULA_A, SHOULDER_A, ELBOW_A, CLAVICLE_P, SCAPULA_P, SHOULDER_P, ELBOW_P, HIP_F, KNEE_F, ANKLE_F, HIP_B, KNEE_B, ANKLE_B. |
| ATTACHMENT | A derived point on a body chain with no rotational freedom; its position is always computed by the engine, never authored. Members: HEAD_POS, PALM_A, KNUCKLES_A, PALM_P, KNUCKLES_P, HEEL_F, HEEL_B. |
| END EFFECTOR | The terminal attachment of a solved or derived chain: an attachment that additionally plays the solver/derivation terminal role. Structural subset of ATTACHMENT (every End Effector is an Attachment; the categories are not disjoint). Members: HAND_A, FINGERTIPS_A, HAND_P, FINGERTIPS_P, TOE_F, TOE_B. |
| ALIAS | An identifier that denotes the same body point as another identifier, retained for authoring compatibility; it has no tree node and never carries independent state. Members: WRIST_A (alias of HAND_A), WRIST_P (alias of HAND_P). |

Category counts: ROOT 1, SEGMENT 2, ARTICULATION 15, ATTACHMENT 7, END EFFECTOR 6, ALIAS 2 — total 33 identifiers.

### 2.2 Namespace facts (verified against source)

1. The transform hierarchy contains exactly **31 nodes**: every identifier above except the two ALIAS entries has exactly one node.
2. The arm chain is SHOULDER → ELBOW → HAND → PALM directly. There is no wrist node; WRIST identifiers alias HAND.
3. ANKLE_F/ANKLE_B are ARTICULATION nodes (declared dorsiflexion/plantar-flexion + inversion/eversion intent) whose children are the heel/toe attachments. They are not a separate category.
4. HEAD_POS is an ATTACHMENT (position derived from neck direction + definition length) that additionally plays the Landmark role for observation (§4). It is not an articulation and not procedural-category.
5. Segments and articulations intentionally share this single namespace; each identifier's category is fixed by the table above. Proposals to split the namespace are out of scope for this freeze (roadmap concern).

---

## 3. State Categories

The architecture has exactly three state categories. Each category defines only what information it owns — never storage or format.

### 3.1 Intent State

- **Owns:** everything the pose declares: Contact Declarations, Contact Precedence, Limb Targets, Relative Articulations, Spine Intent, Posture Intent, Head Target, Extremity Overrides, Extremity Articulations, Headings, Support Declaration, Environment, Camera Definition, Motion Driver.
- **Canonical source:** pose authoring. Nothing else may create or alter Intent State content, with exactly one exception: Runtime Context Injection (§5 R8).
- **Lifetime:** created during authoring; frozen at end of build; consumed read-only by all engine subsystems; discarded with the frame.
- **Does not own:** any computed geometry or any Validation Stamp.

### 3.2 Settled Geometry

- **Owns:** the hierarchy's local and world transforms while stages run — the single working representation of body geometry between phases.
- **Canonical source:** exactly one writer at any point in the execution order (§6): Authoring before build returns; Solver during settlement; Finalizer during finalization. Writer authority transfers only at phase boundaries.
- **Lifetime:** per frame; begins at authoring, ends when Flatten publishes Pose State.
- **Does not own:** Intent State, Validation Stamps, published outputs.

### 3.3 Published Pose State

- **Owns:** the final world transforms of all 31 nodes after Flatten, plus all Validation Stamps.
- **Canonical source:** Finalizer publishes transforms (Flatten); each stamp's canonical producer is listed in §4. Stamps written by more than one producer follow the Stamp Merge Rule (§5 R6); the merged value in Published Pose State is the only canonical value.
- **Lifetime:** per frame; immutable after publish; consumed by Validation and Rendering.
- **Does not own:** Intent State, Settled Geometry.

Rotation-space rule: declared rotations in Intent State and Extremity Articulations are parent-relative; rotations in Settled Geometry are local to the hierarchy; rotations in Published Pose State are world-space. Conversion between these spaces is owned exclusively by Frame Conversion (Finalizer). No consumer may compare rotations across categories without going through Frame Conversion output.

Scratch-isolation rule: computation scratch belongs privately to the subsystem that creates it. Scratch never carries information across a subsystem boundary and never survives as canonical state. Cross-frame memory exists only as Pipeline-owned Frame History (§5 R10).

---

## 4. Architectural Object Register

Every named object: owner (accountable subsystem), producer (creates/populates), consumers, lifetime. Structure is intentionally not described.

### 4.1 Intent objects

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| Contact Declaration | Pose Intent | Authoring bake | ConstraintSolver (Contact Honor, Re-Solve), Finalizer (Settled-Contact Guarantee scope), Validator (contact-preservation checks) | Per build; frozen post-build |
| Contact Precedence | Pose Intent | Authoring via Intent Builder | ConstraintSolver (Contact Conflict Resolution) | Per build |
| Limb Target | Pose Intent | Authoring bake | Active limb solver only (§5 R5) | Per build |
| Relative Articulation | Pose Intent | Authoring via Intent Builder | Finalizer (intent application to non-settled chains) | Per build |
| Spine Intent | Pose Intent | Authoring via Intent Builder | Finalizer (spine derivation on non-settled chains) | Per build |
| Posture Intent | Pose Intent | Authoring via Intent Builder | ConstraintSolver (Root Placement) | Per build |
| Head Target | Pose Intent | Authoring via Intent Builder | Finalizer (Head-Target Resolution) | Per build |
| Extremity Override | Pose Intent | Authoring via Intent Builder | Finalizer (Extremity Derivation opt-out) | Per build |
| Extremity Articulation | Pose Intent | Authoring via Intent Builder | Finalizer (Extremity Derivation) | Per build |
| Heading | Pose Intent | Authoring via Intent Builder | Finalizer (extremity facing application) | Per build |
| Support Declaration | Pose Intent | Caller + Runtime Context Injection | ConstraintSolver (support checks), Validator (support-polygon checks) | Per frame; injected at frame start |
| Environment | Pose Intent (context) | Caller + Runtime Context Injection | ConstraintSolver, Validator, Renderer | Per frame; injected at frame start |
| Camera Definition | Render input | Caller | Projector, Renderers | Per render call |
| Motion Driver | Pose Intent | Authoring | Pipeline (frame interpolation at Phase 0) | Per build |

### 4.2 Engine operations

| Object | Owner | Producer (= performer) | Consumers | Lifetime |
|---|---|---|---|---|
| Two-Bone IK Solve | IK responsibility | Active limb solver | Settled Geometry (chain transforms) | Performed within its phase |
| Straight-Limb Fallback | IK responsibility | Active limb solver | Settled Geometry; surfaces via Straight-Intent-Dropped Flag | Within limb solving |
| Bone-Length Invariant | IK responsibility | Active limb solver (verification) | Bone-Lengths-Verified Flag | Within limb solving |
| Default Pole | IK responsibility | Active limb solver, when Pose omits a Pole | That solver's limb operation | Per limb solve |
| Pole | Pose Intent (when declared) | Authoring | Limb solver | Per build |
| Root Placement | Solver responsibility | ConstraintSolver | Settled Geometry (root transform) | Settlement phase |
| Contact Honor | Solver responsibility | ConstraintSolver | Settled Geometry | Settlement phase |
| Contact Conflict Resolution | Solver responsibility | ConstraintSolver, driven by Contact Precedence | Settled Geometry | Settlement phase |
| Contact Re-Solve | Solver responsibility | ConstraintSolver | Settled Geometry (settled contact chains) | Settlement phase |
| Posture CCD | Solver responsibility | ConstraintSolver, regularized toward declared intent | Settled Geometry (free-joint transforms) | Settlement phase, bounded |
| Inter-Frame Smoothing | Solver responsibility | ConstraintSolver, consuming Frame History | Settled Geometry (root motion continuity) | Settlement phase |
| Frame Conversion | Finalizer responsibility | SkeletonPoseFinalizer | All later readers of local/world transforms | Finalization phase |
| Tilt Cancel | Finalizer responsibility | SkeletonPoseFinalizer | Settled Geometry (inherited-tilt removal) | Finalization phase |
| Chest-Frame Reconstruction | Finalizer responsibility | SkeletonPoseFinalizer | Settled Geometry (chest chain), bounded by Settled-Contact Guarantee | Finalization phase |
| Head-Target Resolution | Finalizer responsibility | SkeletonPoseFinalizer | Neck/head chain transforms | Finalization phase |
| Extremity Derivation | Finalizer responsibility | SkeletonPoseFinalizer | Terminal-chain attachment/end-effector transforms | Finalization phase |
| Flatten | Finalizer responsibility | SkeletonPoseFinalizer | Published Pose State | Publish, once per frame |
| FK Propagation | Shared primitive | Any authorized stage | Calling stage | Stateless utility; no lifetime |
| Rule Checks | Validator responsibility | ExerciseValidator | Validation Report | After publish |

### 4.3 Stamps (all live in Published Pose State)

| Object | Producer(s) | Merge rule | Consumer |
|---|---|---|---|
| Clamp Stamp | Active limb solver (first write), ConstraintSolver (strengthen-only) | max | ExerciseValidator |
| Straight-Intent-Dropped Flag | Active limb solver (first write), ConstraintSolver (strengthen-only) | OR | ExerciseValidator |
| Bone-Lengths-Verified Flag | Active limb solver (first write), ConstraintSolver (strengthen-only) | AND | ExerciseValidator |
| Root Translation Delta / Root Rotation Delta | ConstraintSolver (sole producer) | overwrite (single writer) | ExerciseValidator |
| Hip ROM Stamp | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |
| Bilateral Symmetry Delta | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |
| Bilateral Opposite Bend | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |

Limb Solve Result is the transient outcome of one limb solve (chain placement plus clamp/straight/bone-length readings). Its durable surfacing is exclusively the three solver-produced stamps above; the result itself is private scratch of its producer and never crosses a subsystem boundary.

### 4.4 Subsystems and infrastructure

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| SkeletonPose (carrier) | Pipeline during execution; caller before/after | PoseBuilder.build() | All stages; Renderer; Validator | Per frame |
| Finalized Pose | Pipeline (returned to caller) | SkeletonPoseFinalizer via Flatten | Renderer, Validator | Per frame |
| Frame History | Pipeline | Pipeline (retained Finalized Poses) | ConstraintSolver (Inter-Frame Smoothing), Validator (dynamics checks) | Rolling two frames |
| SkeletonNode (hierarchy node) | Pose carrier (tree) | SkeletonFactory | Authoring, active limb solver, ConstraintSolver, Finalizer, Flatten | Per frame tree; node buffers reused |
| SkeletonDefinition | Engine instance | Caller (per definition) | Factory, all stages, Validator | Permanent per engine |
| SkeletonFactory | Definition layer | Caller | Pipeline setup | Permanent per engine |
| SkeletonNodes container | Authoring convenience | SkeletonFactory | Pose authoring | Build-time only |
| ContactChain mapping | Solver responsibility | ConstraintSolver (fixed per contact joint) | Contact Re-Solve | Static knowledge |
| ExerciseValidator | Caller (composition root); referenced by Pipeline | Caller | Pipeline drives; report returned to caller | Long-lived |
| Validator Profile | Validator configuration | Caller | ExerciseValidator | Long-lived |
| Validation Report | Caller | ExerciseValidator | Application diagnostics | Per validate call |
| SkeletonPipeline | Creator (renderer/snapshot renderer) | Creator | Callers producing frames | Long-lived per creator instance |
| SkeletonProjector | Rendering layer | Renderer instances | Renderers | Long-lived per renderer |
| Projected Output | Renderer | SkeletonProjector | Screen composition | Per frame |
| Bone | Rendering definition | Rendering definition holder | Projector, Renderers | Permanent per definition |
| Rendering Style | Rendering definition | Caller | Renderers | Permanent per renderer |
| Exercise Snapshot | Snapshot Renderer | SkeletonSnapshotRenderer | Export/application | Per capture |
| Production Metadata | Pose builder API | Caller | Pipeline reads Environment/Support context for injection; playback timing is an application concern outside this architecture | Per build |
| Intent Builder | Authoring surface | SkeletonPose | Pose authoring code | During authoring |

Landmark is a **role**, not a stored object: any attachment may serve as an observation reference for Rule Checks (viewport, sliding, symmetry). The role confers no state and no ownership beyond the attachments already registered.

---

## 5. Canonical Ownership Rules

These rules are constitutional. Any design or code that violates them is a defect regardless of intent.

- **R1 — One writer per state category per phase.** Intent State is written only by authoring (exception R8). Settled Geometry has exactly one authorized writer at any moment, transferring only at phase boundaries (§6). Published Pose State is written only by Flatten and stamp producers.
- **R2 — Solver owns the root after authoring.** From the end of build until publish, ConstraintSolver is the sole subsystem that translates or rotates the root. Authoring may declare initial root-relative orientation as intent before build returns; after that, no other subsystem moves the root.
- **R3 — Settled-Contact Guarantee.** Once ConstraintSolver settles a contact end-effector, no later subsystem (including the Finalizer) may move it. Chest-Frame Reconstruction, Head-Target Resolution, and Extremity Derivation are all bound by this guarantee. Where declared intent would move a settled contact, the contact wins; the intent application is skipped for that chain, and the skip is legitimate architecture, not data loss.
- **R4 — Single canonical source per stamp.** Each stamp has the producers listed in §4.3 and no others. Multi-producer stamps obey the merge rule; the merged value in Published Pose State is the only canonical reading.
- **R5 — Single active limb solver.** Exactly one limb solver is active per configuration: the authoring bake while the engine-side IK stage is disabled, or the engine-side IK stage (consuming Limb Targets) once enabled. The enabling flag is a rollout mechanism, not architecture: it selects between two implementations of the same frozen responsibility set (Two-Bone IK Solve, Straight-Limb Fallback, Bone-Length Invariant, Default Pole), never splits that responsibility.
- **R6 — Strengthen-only restamping.** A secondary stamp producer may only strengthen (max/OR/AND per stamp); it may never weaken or erase a primary producer's reading.
- **R7 — Finalizer exclusivity.** World↔local Frame Conversion, Tilt Cancel, Chest-Frame Reconstruction, Head-Target Resolution, Extremity Derivation, and Flatten are performed exclusively by the Finalizer. Outside Head-Target Resolution's neck/head scope and the guarantees above, the Finalizer does not alter settled geometry.
- **R8 — Runtime Context Injection.** Environment and Support Declaration enter the frame through exactly one pipeline-performed injection at frame start, before any engine stage runs. Injection is the sole post-build write permitted into Intent State; afterwards the context is read-only for every subsystem including the Pipeline.
- **R9 — Validation observes.** Validation reads Published Pose State and Intent State ranges; it never writes the carrier, never derives geometry, and never drives execution.
- **R10 — No hidden cross-frame state.** Inter-Frame Smoothing consumes Pipeline-owned Frame History. The solver keeps no cross-frame memory of its own; solver behavior is a function of current-frame inputs plus supplied history, never of object identity.
- **R11 — Carrier unity.** All cross-subsystem handoff flows through the single SkeletonPose carrier. No subsystem introduces another shared mutable channel.
- **R12 — Bounded settlement.** Iterative settlement (Posture CCD, conflict passes) is bounded; bounds are tuning, not architecture. Architecture commits only to termination and to the phase exit condition "root and contacts final."
- **R13 — Defaults are owned.** When the pose omits a Pole, Default Pole ownership lies with the active limb solver. The default axis of Spine Intent is defined by the Skeleton Definition's anatomical axes, not by call-site defaults; call-site defaults derive from the definition.
- **R14 — Pipeline lifetime.** Each Pipeline instance is owned by its creator (renderer or snapshot renderer). There is no shared or global pipeline; differing creator lifecycles are legitimate and carry no architectural consequence.

---

## 6. Execution Order (Architecture Level)

Phases and boundaries are architecture; everything inside a phase is implementation and lives elsewhere.

```
Phase 0 — AUTHORING   Producer of Intent State; performs limb solving while it is
                      the active solver (R5). Ends: build() returns; Intent frozen.
Phase 0.5 — INJECTION Pipeline performs Runtime Context Injection (R8). Ends: context read-only.
Phase 1 — LIMB        If the engine-side IK stage is enabled: it consumes Limb Targets,
                      executes the IK responsibility set (R5), writes first-readings of its
                      stamps (R4/R6). Skipped entirely otherwise.
Phase 2 — SETTLEMENT  ConstraintSolver: Root Placement → Contact Honor → Contact Conflict
                      Resolution (by Contact Precedence) → Posture CCD → Contact Re-Solve →
                      Inter-Frame Smoothing. Ends: root + contacts + posture FINAL (R12).
Phase 3 — FINALIZE    SkeletonPoseFinalizer, single pass, read-only on settled contacts (R3):
                      Tilt Cancel → intent application to non-settled chains → Chest-Frame
                      Reconstruction → Head-Target Resolution → Extremity Derivation.
Phase 4 — PUBLISH     Flatten produces Published Pose State; Finalizer writes its stamps.
Phase 5 — OBSERVE     Validation (Rule Checks) and Rendering read Published Pose State;
                      both are read-only (R9).
```

Phase-boundary contracts:

- Authoring → Pipeline: Intent State complete and frozen except R8; no geometry commitment exists yet beyond authoring-time solves made under R5.
- Injection → Stages: context fixed for the frame.
- Limb/Settlement handoff: chain placements done; root not yet posture-final.
- Settlement → Finalize: root, contacts, posture final; Finalizer bound by R3.
- Finalize → Publish: all hierarchy transforms resolved; only publication remains.
- Publish → Observe: state complete and immutable; observers consume.

Re-entry rule: if a declared intent cannot be honored without moving a settled contact, the architecture requires a bounded, explicit return to Phase 2 under Solver authority. Silent mutation inside Phase 3 is prohibited.

---

## 7. Deleted Architecture

Removed by this revision because no subsystem consumes them, they duplicated another object, or they were placeholders:

1. Categories RIG_HELPER, PROCEDURAL, UNKNOWN (zero members; their claimed members were misclassifications corrected in §2).
2. RigHelper as a concept (wrist/ankle "helper" role) — wrists are aliases; ankles are articulations.
3. FrameNode as a proposed rename of SkeletonNode — renaming proposals are roadmap, not architecture; SkeletonNode is canonical.
4. Transform as a standalone ideal entity — superseded by the transform responsibilities of Settled Geometry and Published Pose State (§3).
5. Snapshot as a standalone ideal entity — superseded by the SkeletonPose carrier with its three state categories.
6. "Gaze" as a competing name for Head Target.
7. "Inter-frame temporal relaxation", "re-bake", "preConvertPoles()", "outputPose buffer", "residual ≤ eps" as unregistered terms — replaced by registered names (Inter-Frame Smoothing, Contact Re-Solve, folded into Frame Conversion scope, Finalized Pose, R12).
8. Solver-owned cross-frame memory (the identity-keyed last-solved-root cache) — replaced by R10 (Pipeline-owned Frame History).
9. Survival verdicts ("Replace", "decompose", "split") from the audit-era inventory — refactoring proposals are roadmap content and are excluded from this frozen architecture.
10. Storage-level descriptions (flat array layouts, field enumerations, scratch-buffer inventories, numeric constants) — replaced by the state-category responsibilities of §3 and R12.

Nothing else was removed. Every remaining named object appears in the register (§4) with full identity.

---

## 8. Subsystem Self-Sufficiency

Each subsystem answers the four questions without reference to other chapters.

**Pose Authoring (PoseBuilder/BasePose + Intent Builder).**
Owns: creation of all Intent State; authoring-time limb solving while active under R5.
Consumes: SkeletonDefinition, exercise specification, frame progress.
Produces: a built SkeletonPose with frozen Intent State and initial Settled Geometry.
Outside its responsibility: settlement, finalization, validation, rendering; computing anything after build returns.

**Engine-side IK stage (gated).**
Owns: nothing persistently.
Consumes: Limb Targets, definition, hierarchy.
Produces: chain placements in Settled Geometry; first readings of solver-family stamps.
Outside: root movement, contacts, tilt handling, local-frame decisions.

**ConstraintSolver.**
Owns: settlement responsibilities — Root Placement, Contact Honor, Contact Conflict Resolution, Contact Re-Solve, Posture CCD, Inter-Frame Smoothing; strengthen-only restamping.
Consumes: Intent State (contacts, precedence, posture intent, support), Settled Geometry, Frame History.
Produces: final root/contact/posture geometry in Settled Geometry; its stamps.
Outside: authoring intent, finalization conversions, validation, rendering; it never invents contacts and never moves non-contact authored shape except through declared posture regularization.

**SkeletonPoseFinalizer.**
Owns: Frame Conversion, Tilt Cancel, Chest-Frame Reconstruction, Head-Target Resolution, Extremity Derivation, Flatten; finalizer-produced stamps.
Consumes: settled Settled Geometry, Intent State (non-settled-chain intents, overrides, head target, headings, extremity articulations), definition.
Produces: Finalized Pose (Published Pose State) with all transforms and its stamps.
Outside: settlement changes, root translation, contact end-effector movement (R3), validation.

**SkeletonPipeline.**
Owns: orchestration order, Runtime Context Injection point, Frame History, Finalizer reference, validator reference (not validator ownership).
Consumes: built poses, caller-supplied environment/support/camera context.
Produces: driven frames; Finalized Pose handed to callers.
Outside: geometry decisions of any stage; it coordinates but never computes pose content.

**ExerciseValidator.**
Owns: Rule Checks and Validator Profiles.
Consumes: Published Pose State, Intent State ranges, definition, Frame History (dynamics).
Produces: Validation Report.
Outside: any write to the carrier; geometry derivation; driving the pipeline.

**Rendering layer (Projector, Renderers, Snapshot Renderer, rendering definitions).**
Owns: projection and screen composition; rendering topology/style definitions; Exercise Snapshot production.
Consumes: Finalized Pose, Camera Definition, rendering definitions.
Produces: Projected Output, Exercise Snapshot.
Outside: pose semantics; it never writes the carrier.

---

## 9. Findings Resolution Index

Pre-freeze audit findings and their resolutions in this revision.

| ID | Finding | Resolution |
|---|---|---|
| A1 | Joint inventory missing HIP_B; category counts wrong (stated 14/6/4 vs listed 16/7/6); node count stated 33 vs actual 31 | Corrected table and counts (§2.1–2.2) |
| A2 | WRIST_A/P classified ARTICULATION + "attachment host", contradicting verified phantom status | New ALIAS category; wrists alias HAND (§2.1–2.2) |
| A3 | END EFFECTOR treated as disjoint from ATTACHMENT in §1 but "subset" in ideal model | Declared structural subset relation once (§2.1) |
| A4 | Attachment-type examples contradicted category membership (toe/fingertip/knee/elbow) | Examples aligned with register (§2.1) |
| A5 | RIG_HELPER/PROCEDURAL zero-member placeholder categories | Deleted (§7) |
| A6 | ConstraintSolver called stateless yet held cross-frame identity-keyed memory | R10; memory replaced by Pipeline-owned Frame History |
| A7 | Pipeline described as validator owner while validator is caller-created | Ownership clarified: caller owns, pipeline references/drives (§4.4) |
| A8 | Relative Articulations/Spine Intent silently unconsumed for contact poses | R3 makes consumption deterministic: settled contacts win; skips are sanctioned |
| A9 | Limb Targets populated but unconsumed under default flag | R5 single-active-limb-solver rule; flag demoted to rollout mechanism |
| A10 | Pipeline mutated input pose environment/support invisibly | R8 Runtime Context Injection: single, ordered, then read-only |
| A11 | Terminology duplicates across ~25 concepts (gaze/head target, re-bake, tilt cancel, smoothing, stamps, camera input, …) | §1 canonical vocabulary with deleted synonyms |
| A12 | Joint rotations appeared in multiple places without canonical-source ruling | Rotation-space rule in §3.3 note; conversion exclusive to Frame Conversion |
| A13 | Stamp dual-writer ambiguity (primary/secondary without merge semantics) | §4.3 producers + R4/R6 merge and strengthen-only rules |
| A14 | Contracts used undefined terms (Pole, Re-Bake, Finalized Pose, stamps) | All defined in §1/§4 |
| A15 | Storage/format leakage (field lists, arrays, constants, function names) | Removed; responsibilities only (§3, §7.10) |
| A16 | Shared scratch across traversals creating implicit ordering | Scratch-isolation rule (§3) |
| A17 | Conflicting renderer lifecycle semantics for pipeline | R14: creator-owned instances; no shared pipeline |
| A18 | ContactSpec mixing biomechanical and solver concerns without canonical ruling | Declared aggregation: declaration is authored intent; chain context serves the same lifecycle; one object, one owner (authoring), consumers listed (§4.1) |
| A19 | Hardcoded solver constants presented as architecture | R12: bounds are tuning; architecture commits to boundedness only |
| A20 | HEAD_POS "marker masquerading as joint"; wrist/ankle "dual-role" claims | §2.1–2.2: HEAD_POS is ATTACHMENT (+ Landmark role); ankles are ARTICULATION; wrists are ALIAS |
| A21 | Spine Intent default axis inconsistent between definition and call site | R13: default owned by Skeleton Definition; call sites derive |
| A22 | Ideal-model entities overlapped §1 categories with different names | Single vocabulary (§1); duplicates deleted (§7) |
| A23 | Metadata carried unread fields (camera/timing/loop) with unclear consumers | §4.4: Camera consumed by projection path; timing/loop declared application playback concerns outside this architecture |
| A24 | "33 nodes" factory claim vs alias reality | §2.2 fact 1: 31 nodes, 33 identifiers |

---

## Appendix: Document Reference

| Document | Purpose |
|---|---|
| `ARCHITECTURAL_AUDIT_SKELETON_MODEL.md` / `AUDIT_RUNTIME_SKELETON_MODEL.md` | The audits that motivated this architecture |
| `DESIGN_REVIEW_RUNTIME_SKELETON.md` | Source-verified review of the audits (authority for corrected facts) |
| `DEPENDENCY_GRAPH_RUNTIME_SKELETON.md` | Pre-resolution dependency evidence (historical) |
| `SEMANTIC_ANALYSIS_RUNTIME_SKELETON.md` | Semantic analysis underlying the vocabulary |
| `DOMAIN_ANALYSIS_SKELETON.md` | Domain ontology grounding the categories |
| `docs/ARCHITECTURE_V2.md` | Prior-generation engine architecture (superseded by this document for the runtime skeleton model upon freeze) |
