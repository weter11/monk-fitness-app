# Runtime Skeleton Architecture

**Status:** READY FOR ARCHITECTURE FREEZE — pre-freeze audits resolved (initial resolution A1–A24; clarification pass A25–A33). See §9 Findings Resolution Index.
**Scope:** Architecture only. This document defines ownership, responsibility, dependency, canonical source, lifetime, and subsystem boundaries. It deliberately does not define structures, field layouts, algorithms, execution detail beyond phase ordering, or validation algorithms.
**Provenance:** Produced from the audit cycle (`ARCHITECTURAL_AUDIT_SKELETON_MODEL.md`, `AUDIT_RUNTIME_SKELETON_MODEL.md`), design review (`DESIGN_REVIEW_RUNTIME_SKELETON.md`), dependency graph (`DEPENDENCY_GRAPH_RUNTIME_SKELETON.md`), semantic analysis (`SEMANTIC_ANALYSIS_RUNTIME_SKELETON.md`), and domain analysis (`DOMAIN_ANALYSIS_SKELETON.md`). Facts contradicted by source code were corrected against the code before freezing.

**Freeze rule:** Two independent architects reading this document must arrive at the same architecture. Every architectural object below has exactly one canonical name, one owner, one producer, defined consumers, and a defined lifetime. No object has two meanings; no information has two canonical sources.

---

## 1. Canonical Vocabulary

Each concept has exactly one canonical name. The synonyms in the right column are deleted from architectural usage; they must not appear in contracts, reviews, or new documents.

| Canonical name | Deleted synonyms |
|---|---|
| Author Intent | pose intent; pose intent declarations; declared intent; intent carriers (as a concept name); §1.1 (as a concept name) |
| Pose State | result state; output state; §1.2 (as a concept name) |
| Settled Geometry | solved limb transforms; limb solve results; intermediate geometry; posture settlement; posture resolution; local transform state; world transform state |
| Settlement Result | pose result state; posture adjustments; adjusted joint angles; posture-resolved joint angles; contact settlements |
| Contact Declaration | contact; contacts (as a concept name); contact spec |
| Contact Definition | contact capability; possible-contact knowledge; contactable body points |
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
| Environment Definition | environment; environment definition (as a concept name); world context |
| Frame Context | runtime context; injected context; per-frame context; environment context (as a region name) |
| Environment context | frame environment; resolved environment |
| Camera Definition | camera parameters; camera configuration; camera input |
| Motion Driver | motion drivers; choreography driver; motion driver definition; animation driver |
| Exercise Definition | exercise specification; exercise spec; workout definition |
| Production Metadata | pose metadata; exercise metadata |
| Two-Bone IK Solve | 2-bone analytical solve; IK 2-bone solve; analytic solve |
| Straight-Limb Fallback | straight fallback; straight-limb bend fallback; straight-intent fallback |
| Bone-Length Invariant | bone-length-exact invariant; exact-length rule |
| Default Pole | pole default; fallback pole |
| Pole | bend-direction vector; pole vector |
| Root Transform | final root transform; world root transform; local root transform; pelvis transform (all unqualified) |
| Limb Solve Result | IK Result; IK Result State; ikResult (as a concept name); solve outcome |
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
| Validation Stamp | stamp; state stamp; solver stamp; validation stamp production |
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
4. Rotations and the root transform must always be space-qualified in contracts: **declared** (parent-relative input in Author Intent / Extremity Articulations), **working** (Settled Geometry during Phases 0–3), or **published** (Published Pose State after Phase 4). Unqualified "rotation" or "root transform" is prohibited because each state category holds its own authoritative representation (§3 rotation-space rule).

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
| END EFFECTOR | The terminal point of a solved or derived chain. Shares the attachment mechanical nature (no rotational freedom; position always computed by the engine) but is a distinct classification, not an additional one: each identifier holds exactly one category, and the categories are mutually exclusive. What End Effectors share with Attachments is their mechanical nature, not their classification. Members: HAND_A, FINGERTIPS_A, HAND_P, FINGERTIPS_P, TOE_F, TOE_B. |
| ALIAS | An identifier that denotes the same body point as another identifier, retained for authoring compatibility; it has no tree node and never carries independent state. Members: WRIST_A (alias of HAND_A), WRIST_P (alias of HAND_P). |

Category counts: ROOT 1, SEGMENT 2, ARTICULATION 15, ATTACHMENT 7, END EFFECTOR 6, ALIAS 2 — total 33 identifiers.

### 2.2 Namespace facts (verified against source)

1. The transform hierarchy contains exactly **31 nodes**: every identifier above except the two ALIAS entries has exactly one node.
2. The arm chain is SHOULDER → ELBOW → HAND → PALM directly. There is no wrist node; WRIST identifiers alias HAND.
3. ANKLE_F/ANKLE_B are ARTICULATION nodes (declared dorsiflexion/plantar-flexion + inversion/eversion intent) whose children are the heel/toe attachments. They are not a separate category.
4. HEAD_POS is an ATTACHMENT (position derived from neck direction + SkeletonDefinition length) that additionally plays the Landmark role for observation (§4). It is not an articulation and not procedural-category.
5. Segments and articulations intentionally share this single namespace; each identifier's category is fixed by the table above. Proposals to split the namespace are out of scope for this freeze (roadmap concern).

---

## 3. State Categories

The architecture has exactly three state categories, hosted by the single SkeletonPose carrier (§5 R11). The carrier hosts them; it owns none of them. Each category defines only what information it owns — never storage or format.

### 3.1 Intent State

Intent State is the frame's complete input declaration. It contains no computed geometry and no Validation Stamps. It has exactly two constituencies, each with one writer and one freeze boundary:

**Author Intent** — what the pose declares:
- **Owns:** Contact Declarations, Contact Precedence, Limb Targets, Relative Articulations, Spine Intent, Posture Intent, Head Target, Extremity Overrides, Extremity Articulations, Headings.
- **Canonical source:** Pose Authoring, via the Intent Builder. Sole writer; nothing else may create or alter Author Intent.
- **Lifetime:** created during authoring; frozen when build returns; consumed read-only by all engine subsystems thereafter.

**Frame Context** — what the frame is supplied:
- **Owns:** the resolved Environment context and the Support Declaration for this frame.
- **Canonical source:** SkeletonPipeline, through the single Runtime Context Injection (§5 R8), resolving External definitions (Environment Definition, the Production Metadata support context) and Contact Declarations. The persistent definitions themselves never enter the carrier — only the resolved per-frame context does.
- **Lifetime:** written once at injection (before any engine stage); frozen immediately after; read-only for every subsystem including the SkeletonPipeline; discarded with the frame.

Camera Definition and Motion Driver are **not** part of Intent State: Camera Definition is an External render input consumed directly by the SkeletonProjector; Motion Driver is External persistent animation configuration consumed by the SkeletonPipeline at Phase 0. Neither ever enters the carrier.

### 3.2 Settled Geometry

- **Owns:** the hierarchy's working local transforms (and their derived world views) while stages run — the single working representation of body geometry between phases.
- **Canonical source:** exactly one writer at any point in the execution order (§6): Pose Authoring before build returns; ConstraintSolver during settlement; SkeletonPoseFinalizer during finalization. Writer authority transfers only at phase boundaries.
- **Settlement Result:** at Phase 2 exit, the ConstraintSolver's authorized write concludes by fixing the **Settlement Result** inside Settled Geometry — the world-space settlement outcome the SkeletonPoseFinalizer requires to continue: the settled root transform (world space), the settled-contact information, and the Contact Conflict Resolution outcome. The Settlement Result contains **no copy of final joint rotations** (local or world) and never competes with Published Pose State as a source of final transforms; it is superseded at publication.
- **Lifetime:** per frame; begins at authoring; ends when Flatten publishes Published Pose State.
- **Does not own:** Intent State, Validation Stamps, published outputs.

### 3.3 Published Pose State

- **Owns:** the final world transforms of all 31 nodes after Flatten, plus all Validation Stamps.
- **Canonical source:** SkeletonPoseFinalizer publishes transforms (Flatten); each Validation Stamp's canonical producer is listed in §4.4. Validation Stamps written by more than one producer follow the Stamp Merge Rule (§5 R6); the merged value in Published Pose State is the only canonical value.
- **Finalized Pose relationship:** the Finalized Pose is the per-frame carrier instance that carries Published Pose State to consumers. It is a delivery handle, not a second state category; it adds no state and holds nothing that Published Pose State does not.
- **Lifetime:** per frame; immutable after publish; consumed by ExerciseValidator and the Rendering layer.
- **Does not own:** Intent State, Settled Geometry.

Rotation-space rule (authoritative representation per phase): declared rotations in Author Intent and Extremity Articulations are parent-relative input only — they are never an alternative canonical source of final rotations. From Phase 0 until Phase 3 completes, the working local transforms in Settled Geometry are the sole authoritative representation of body geometry; their world transforms are derived views produced by FK Propagation. After Phase 4, the published world transforms in Published Pose State are the sole authoritative representation of the final pose and are immutable. Conversion between spaces is owned exclusively by Frame Conversion (SkeletonPoseFinalizer). No consumer may compare rotations across categories without going through Frame Conversion output. The root transform follows the same authority chain: declared/authored by Pose Authoring before build returns; settled by ConstraintSolver as the sole mover during settlement (R2); converted only within Finalization; ultimately canonical only as the published root transform in Published Pose State. No two subsystems own any representation of the root simultaneously.

Scratch-isolation rule: computation scratch belongs privately to the subsystem that creates it. Scratch never carries information across a subsystem boundary and never survives as canonical state. Cross-frame memory exists only as SkeletonPipeline-owned Frame History (§5 R10).

---

## 4. Architectural Object Register

Every named object: owner, producer, consumers, lifetime. Structure is intentionally not described.

**Permitted owner kinds** (no other owner values may appear):

1. **A subsystem** named in §8 (e.g., SkeletonPipeline, ConstraintSolver, Rendering layer);
2. **A state category** named in §3 (Intent State, Settled Geometry, Published Pose State) — for objects whose home is that category;
3. **The Active Limb Solver** — the single architectural role defined by R5; exactly one of its two implementations is instantiated per configuration, so ownership is never ambiguous;
4. **External** — caller/host-owned input or artifact originating outside this architecture; immutable to the engine.

Producers are subsystems, stages, the Active Limb Solver role, or External provisioning. Consumers are subsystems or External recipients. Owner, producer, and consumer are never mixed: the owner is accountable for the object's existence and lifecycle; the producer creates or populates it; consumers only read or receive it.

### 4.1 Input objects

**Group A — Author Intent** (all owned by Intent State, constituency Author Intent; frozen at build return):

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| Contact Declaration | Intent State | Pose Authoring (authoring bake) | ConstraintSolver (Contact Honor, Contact Re-Solve), ExerciseValidator (contact-preservation checks). The SkeletonPoseFinalizer receives settled-contact information via the Settlement Result, not from raw declarations | Per build |
| Contact Precedence | Intent State | Pose Authoring (via Intent Builder) | ConstraintSolver (Contact Conflict Resolution) | Per build |
| Limb Target | Intent State | Pose Authoring (authoring bake) | Active Limb Solver only (§5 R5) | Per build |
| Relative Articulation | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (intent application to non-settled chains) | Per build |
| Spine Intent | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (spine derivation on non-settled chains) | Per build |
| Posture Intent | Intent State | Pose Authoring (via Intent Builder) | ConstraintSolver (Root Placement) | Per build |
| Head Target | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (Head-Target Resolution) | Per build |
| Extremity Override | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (Extremity Derivation opt-out) | Per build |
| Extremity Articulation | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (Extremity Derivation) | Per build |
| Heading | Intent State | Pose Authoring (via Intent Builder) | SkeletonPoseFinalizer (extremity facing application) | Per build |
| Pole | Intent State (when declared) | Pose Authoring | Active Limb Solver | Per build |

**Group B — Frame Context** (owned by Intent State, constituency Frame Context; written once by Runtime Context Injection; frozen immediately after):

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| Environment context | Intent State (Frame Context) | SkeletonPipeline (Runtime Context Injection), resolving the External Environment Definition | ConstraintSolver, SkeletonPoseFinalizer (Environment-context ground checks in Extremity Derivation), ExerciseValidator, Rendering layer | Per frame; frozen at injection |
| Support Declaration | Intent State (Frame Context) | SkeletonPipeline (Runtime Context Injection), resolving the Production Metadata support context and Contact Declarations | ConstraintSolver (support checks), ExerciseValidator (support-polygon checks) | Per frame; frozen at injection |

**Group C — External inputs and persistent definitions** (owner External; immutable to the engine):

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| Camera Definition | External | Host render context | SkeletonProjector, renderer components (consumed directly; never enters the carrier) | Supplied per render invocation |
| Motion Driver | External | Exercise/configuration authoring | SkeletonPipeline (derives Frame Progress at Phase 0, §4.3) | Persistent per exercise |
| Exercise Definition | External | Exercise authoring | Pose Authoring, SkeletonPipeline (setup) | Persistent per exercise |
| Contact Definition | External | Skeleton/environment definition composition | Pose Authoring (declaration authoring), ConstraintSolver, ExerciseValidator | Permanent per definition |
| Environment Definition | External | Exercise/configuration authoring | Runtime Context Injection (resolution source for the Environment context) | Persistent per exercise |
| Production Metadata | External | Caller | SkeletonPipeline (context extraction for injection); playback timing fields are application concerns outside this architecture | Per build |
| SkeletonDefinition | External | Caller (per definition) | SkeletonFactory, all stages, ExerciseValidator | Permanent per engine |

Contact Definition vs Contact Declaration: Contact Definition is persistent knowledge about which body points and surface relationships are *possible*; it never changes per frame. Contact Declaration is per-frame author intent saying which contact is *desired*. Contact Conflict Resolution is the solver's outcome among competing declarations; Contact Re-Solve is the solver operation that enforces settlement. The four share no meaning.

### 4.2 Engine operations

Operations are performed by their owner and produce effects only inside state categories; operations themselves are not state.

| Object | Owner | Producer (= performer) | Consumers | Lifetime |
|---|---|---|---|---|
| Two-Bone IK Solve | Active Limb Solver | Active Limb Solver | Settled Geometry (chain placements); Validation Stamps (first readings) | Within its phase |
| Straight-Limb Fallback | Active Limb Solver | Active Limb Solver | Settled Geometry; surfaces via Straight-Intent-Dropped Flag | Within limb solving |
| Bone-Length Invariant | Active Limb Solver | Active Limb Solver (verification) | Bone-Lengths-Verified Flag | Within limb solving |
| Default Pole | Active Limb Solver | Active Limb Solver, when no Pole is declared | The limb operation being solved | Per limb solve |
| Root Placement | ConstraintSolver | ConstraintSolver | Settled Geometry (settled root transform) | Settlement phase |
| Contact Honor | ConstraintSolver | ConstraintSolver | Settled Geometry | Settlement phase |
| Contact Conflict Resolution | ConstraintSolver | ConstraintSolver, driven by Contact Precedence | Settlement Result (conflict outcome); Settled Geometry | Settlement phase |
| Contact Re-Solve | ConstraintSolver | ConstraintSolver | Settled Geometry (settled contact chains) | Settlement phase |
| Posture CCD | ConstraintSolver | ConstraintSolver, regularized toward declared intent | Settled Geometry (free-joint working transforms) | Settlement phase, bounded |
| Inter-Frame Smoothing | ConstraintSolver | ConstraintSolver, consuming Frame History | Settled Geometry (root motion continuity) | Settlement phase |
| Frame Conversion | SkeletonPoseFinalizer | SkeletonPoseFinalizer | All later readers of local/world transforms | Finalization phase |
| Tilt Cancel | SkeletonPoseFinalizer | SkeletonPoseFinalizer | Settled Geometry (inherited-tilt removal) | Finalization phase |
| Chest-Frame Reconstruction | SkeletonPoseFinalizer | SkeletonPoseFinalizer | Settled Geometry (chest chain), bounded by the Settled-Contact Guarantee | Finalization phase |
| Head-Target Resolution | SkeletonPoseFinalizer | SkeletonPoseFinalizer | Neck/head chain transforms | Finalization phase |
| Extremity Derivation | SkeletonPoseFinalizer | SkeletonPoseFinalizer | Terminal-chain attachment/end-effector transforms | Finalization phase |
| Flatten | SkeletonPoseFinalizer | SkeletonPoseFinalizer | Published Pose State (via the Finalized Pose) | Publish, once per frame |
| FK Propagation | The invoking stage (stateless operation with no independent existence; it writes only within the invoker's authorized category) | The invoking stage | The invoking stage | None (stateless) |
| Rule Checks | ExerciseValidator | ExerciseValidator | Validation Report | After publish |

### 4.3 Frame state objects

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| Frame Progress | SkeletonPipeline | SkeletonPipeline (derived from the Motion Driver and Exercise Definition at Phase 0) | Pose Authoring | Per frame; exists during Phase 0 only; never enters the carrier |
| Settlement Result | Settled Geometry | ConstraintSolver (fixed at Phase 2 exit) | SkeletonPoseFinalizer | Fixed at end of Phase 2; superseded at publication |
| Finalized Pose | Published Pose State (carrier instance) | SkeletonPoseFinalizer (Flatten), returned via SkeletonPipeline | ExerciseValidator, Rendering layer, External caller | Per frame; immutable after publish |

Settlement Result carries only the solver-level settlement outcome: the settled root transform in world space, the settled-contact information, and the Contact Conflict Resolution outcome. It contains no copy of final joint rotations — published world transforms and working local transforms remain canonically owned by their categories (§3). The Finalized Pose adds no state beyond the Published Pose State it carries.

Frame Progress is a transient pipeline-produced input, deliberately **outside** the three state categories: it never enters the carrier, exists only during Phase 0, and implies no additional state category and no animation-parameter state.

Limb Solve Result is **not an architectural object**: it is the private scratch outcome of one limb solve (clamp/straight/bone-length readings plus the placement decisions). Its durable effects are exactly the chain placements its producer writes into Settled Geometry while authorized (R1/R5) and the three solver-produced Validation Stamps in §4.4. Under the scratch-isolation rule (§3) it never crosses a subsystem boundary and is never an inter-subsystem state.

### 4.4 Validation Stamps (all owned by Published Pose State)

| Object | Producer(s) | Merge rule | Consumer |
|---|---|---|---|
| Clamp Stamp | Active Limb Solver (first write), ConstraintSolver (strengthen-only) | max | ExerciseValidator |
| Straight-Intent-Dropped Flag | Active Limb Solver (first write), ConstraintSolver (strengthen-only) | OR | ExerciseValidator |
| Bone-Lengths-Verified Flag | Active Limb Solver (first write), ConstraintSolver (strengthen-only) | AND | ExerciseValidator |
| Root Translation Delta / Root Rotation Delta | ConstraintSolver (sole producer) | overwrite (single writer) | ExerciseValidator |
| Hip ROM Stamp | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |
| Bilateral Symmetry Delta | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |
| Bilateral Opposite Bend | SkeletonPoseFinalizer (sole producer) | overwrite | ExerciseValidator |

### 4.5 Subsystems and infrastructure

| Object | Owner | Producer | Consumers | Lifetime |
|---|---|---|---|---|
| SkeletonPose (carrier) | Transfer chain, exactly one owner at any moment (R11): Pose Authoring (created at build) → SkeletonPipeline (execution window) → External caller (after return) | Pose Authoring (build); the returned Finalized Pose instance is produced by SkeletonPoseFinalizer (Flatten) | All stages; Rendering layer; ExerciseValidator | Per frame |
| Frame History | SkeletonPipeline | SkeletonPipeline (retained Finalized Poses) | ConstraintSolver (Inter-Frame Smoothing), ExerciseValidator (dynamics checks) | Rolling two frames |
| SkeletonNode (hierarchy node) | Settled Geometry (its transforms are the category's content) | SkeletonFactory | Pose Authoring, Active Limb Solver, ConstraintSolver, SkeletonPoseFinalizer, Flatten | Per-frame tree; node buffers reused |
| SkeletonFactory | External | Caller provisioning | SkeletonPipeline setup, Pose Authoring | Permanent per engine |
| SkeletonNodes container | Pose Authoring | SkeletonFactory | Pose authoring code | Build-time only |
| ContactChain mapping | ConstraintSolver | ConstraintSolver (fixed knowledge per contact joint) | Contact Re-Solve | Permanent per engine |
| ExerciseValidator | External (caller-owned); referenced and driven by SkeletonPipeline | Caller | Validation Report recipients via SkeletonPipeline | Long-lived |
| Validator Profile | External | Caller | ExerciseValidator | Long-lived |
| Validation Report | External (delivered to caller) | ExerciseValidator | Caller/application diagnostics | Per validate call |
| SkeletonPipeline | External (creator-owned; R14) | Creator (a renderer component or Snapshot Renderer instance) | Callers producing frames | Long-lived per creator instance |
| SkeletonProjector | Rendering layer | Renderer components | renderer components | Long-lived per renderer |
| Renderer component | Rendering layer | Caller/host composition | Host screen composition; creates its own SkeletonPipeline and SkeletonProjector instances | Long-lived per host || Snapshot Renderer | Rendering layer | Caller/host composition | Exercise Snapshot production; creates its own SkeletonPipeline instance (R14) | Long-lived per host |
| Projected Output | Rendering layer | SkeletonProjector | Host screen composition | Per frame |
| Bone | Rendering layer | Rendering-definition composition | SkeletonProjector, renderer components | Permanent per definition |
| Rendering Style | Rendering layer | Caller | renderer components | Permanent per renderer |
| Exercise Snapshot | External (exported artifact) | Snapshot Renderer | Caller/export | Per capture |
| Intent Builder | Pose Authoring (authoring surface) | Carrier construction | Pose authoring code | During authoring |

Landmark is a **role**, not a stored object: any attachment-family identifier (an Attachment or End Effector) may serve as an observation reference for Rule Checks (viewport, sliding, symmetry). The role confers no state and no ownership beyond the identifiers already registered.

---

## 5. Canonical Ownership Rules

These rules are constitutional. Any design or code that violates them is a defect regardless of intent.

- **R1 — One writer per state region, one freeze boundary.** Author Intent is written only by Pose Authoring and frozen at build return. Frame Context is written only by the SkeletonPipeline's single Runtime Context Injection and frozen at injection (R8). Settled Geometry has exactly one authorized writer at any moment, transferring only at phase boundaries (§6). Published Pose State is written only by Flatten and Validation Stamp producers.
- **R2 — ConstraintSolver owns the root after authoring.** From the end of build until publish, ConstraintSolver is the sole subsystem that translates or rotates the root. Pose Authoring may declare initial root-relative orientation as intent before build returns; after that, no other subsystem moves the root.
- **R3 — Settled-Contact Guarantee.** Once ConstraintSolver settles a contact end-effector, no later subsystem (including the SkeletonPoseFinalizer) may move it, and none may alter the Settlement Result. Chest-Frame Reconstruction, Head-Target Resolution, and Extremity Derivation are all bound by this guarantee. Where declared intent would move a settled contact, the contact wins; the intent application is skipped for that chain, and the skip is legitimate architecture, not data loss.
- **R4 — Single canonical source per Validation Stamp.** Each Validation Stamp has the producers listed in §4.4 and no others. Multi-producer Validation Stamps obey the merge rule; the merged value in Published Pose State is the only canonical reading.
- **R5 — Single active limb solver.** Exactly one limb solver is active per configuration: the authoring bake while the engine-side IK stage is disabled, or the engine-side IK stage (consuming Limb Targets) once enabled. The enabling flag is a rollout mechanism, not architecture: it selects between two implementations of the same frozen responsibility set (Two-Bone IK Solve, Straight-Limb Fallback, Bone-Length Invariant, Default Pole), never splits that responsibility. The Active Limb Solver is a single architectural owner regardless of which implementation is instantiated.
- **R6 — Strengthen-only restamping.** A secondary Validation Stamp producer may only strengthen (max/OR/AND per Validation Stamp); it may never weaken or erase a primary producer's reading.
- **R7 — SkeletonPoseFinalizer exclusivity.** World↔local Frame Conversion, Tilt Cancel, Chest-Frame Reconstruction, Head-Target Resolution, Extremity Derivation, and Flatten are performed exclusively by the SkeletonPoseFinalizer. Outside Head-Target Resolution's neck/head scope and the guarantees above, the SkeletonPoseFinalizer does not alter settled geometry or the Settlement Result.
- **R8 — Runtime Context Injection.** The Frame Context (resolved Environment context and Support Declaration) enters the carrier through exactly one SkeletonPipeline-performed injection at frame start, before any engine stage runs. Its sources are External definitions (Environment Definition, the Production Metadata support context) plus Contact Declarations; the persistent definitions themselves never enter the carrier. Injection is the sole post-build write into Intent State; afterwards the Frame Context is immutable for every subsystem including the SkeletonPipeline. Camera Definition and Motion Driver are never injected: Camera Definition is consumed directly by the SkeletonProjector from the host render context; Motion Driver is consumed by the SkeletonPipeline at Phase 0.
- **R9 — Validation observes.** ExerciseValidator reads Published Pose State, Author Intent ranges, and the Frame Context; it never writes the carrier, never derives geometry, and never drives execution.
- **R10 — No hidden cross-frame state.** Inter-Frame Smoothing consumes SkeletonPipeline-owned Frame History. ConstraintSolver keeps no cross-frame memory of its own; its behavior is a function of current-frame inputs plus supplied history, never of object identity.
- **R11 — Carrier unity and single-owner transfer.** All cross-subsystem handoff flows through the single SkeletonPose carrier; no subsystem introduces another shared mutable channel. Carrier ownership is a strict transfer chain — Pose Authoring → SkeletonPipeline → External caller — with exactly one owner at any moment; during execution the current owner delegates write authority phase-by-phase per R1/R5 but never shares it.
- **R12 — Bounded settlement.** Iterative settlement (Posture CCD, conflict passes) is bounded; bounds are tuning, not architecture. Architecture commits only to termination and to the phase exit condition "the Settlement Result is final."
- **R13 — Defaults are owned.** When the pose omits a Pole, Default Pole ownership lies with the active limb solver. The default axis of Spine Intent is defined by the Skeleton Definition's anatomical axes, not by call-site defaults; call-site defaults derive from the definition.
- **R14 — Pipeline lifetime.** Each SkeletonPipeline instance is owned by its creator (a renderer or Snapshot Renderer instance). There is no shared or global pipeline; differing creator lifecycles are legitimate and carry no architectural consequence.
- **R15 — Extension points.** Exactly two extension forms are architectural. (i) **Stage insertion:** a new stage may be inserted between existing phases only if it takes a single-writer window over one state region under R1, respects the phase-boundary contracts of §6, and introduces no second canonical source. (ii) **Observer addition:** additional read-only observers may consume Published Pose State (via the Finalized Pose) at any time without producing state and without new state categories. Both forms are legitimate architecture; neither requires nor permits redesigning existing writer authority or stamp ownership.

---

## 6. Execution Order (Architecture Level)

Phases and boundaries are architecture; everything inside a phase is implementation and lives elsewhere.

```
Phase 0 — AUTHORING    The SkeletonPipeline derives Frame Progress from the Motion Driver
                       and Exercise Definition (both External). Pose Authoring produces
                       Author Intent and initial Settled Geometry, performing limb solving
                       while it is the Active Limb Solver (R5).
                       Ends: build() returns; Author Intent frozen.
Phase 0.5 — INJECTION  SkeletonPipeline performs Runtime Context Injection (R8): writes the
                       Frame Context resolved from External definitions.
                       Ends: Frame Context frozen; entire Intent State read-only.
Phase 1 — LIMB         If the engine-side IK stage is enabled: as Active Limb Solver it
                       consumes Limb Targets, executes the IK responsibility set (R5),
                       writes first readings of solver-family Validation Stamps (R4/R6).
                       Skipped entirely otherwise.
Phase 2 — SETTLEMENT   ConstraintSolver: Root Placement → Contact Honor → Contact Conflict
                       Resolution (by Contact Precedence) → Posture CCD → Contact Re-Solve →
                       Inter-Frame Smoothing. Ends: Settlement Result FINAL (bounded, R12).
Phase 3 — FINALIZE     SkeletonPoseFinalizer, single pass, read-only on settled contacts and
                       the Settlement Result (R3/R7): Tilt Cancel → intent application on
                       non-settled chains → Chest-Frame Reconstruction → Head-Target
                       Resolution → Extremity Derivation.
Phase 4 — PUBLISH      Flatten publishes Published Pose State into the Finalized Pose;
                       SkeletonPoseFinalizer writes its Validation Stamps. The Settlement
                       Result is superseded.
Phase 5 — OBSERVE      ExerciseValidator (Rule Checks) and Rendering read the Finalized Pose
                       (Published Pose State); projection applies the caller-supplied Camera
                       Definition directly. Both observers are read-only (R9).
```

Phase-boundary contracts:

- Pose Authoring → SkeletonPipeline: Author Intent complete and frozen; geometry limited to authoring-time solves made under R5.
- Injection → Stages: Frame Context fixed for the frame.
- Limb/Settlement handoff: chain placements done; root not yet posture-final; Settlement Result not yet fixed.
- Settlement → Finalize: Settlement Result final — its contents are exactly the settled root transform (world space), the settled-contact information, and the Contact Conflict Resolution outcome (§4.3); it contains no joint rotations and no separate "posture" member — free-joint posture finality is Settled Geometry content. SkeletonPoseFinalizer bound by R3/R7.
- Finalize → Publish: all hierarchy transforms resolved; only publication remains.
- Publish → Observe: Published Pose State complete and immutable; observers consume.

Re-entry rule: if a declared intent cannot be honored without moving a settled contact, the architecture requires a bounded, explicit return to Phase 2 under ConstraintSolver authority. Silent mutation inside Phase 3 is prohibited.

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
8. ConstraintSolver-owned cross-frame memory (the identity-keyed last-solved-root cache) — replaced by R10 (SkeletonPipeline-owned Frame History).
9. Survival verdicts ("Replace", "decompose", "split") from the audit-era inventory — refactoring proposals are roadmap content and are excluded from this frozen architecture.
10. Storage-level descriptions (flat array layouts, field enumerations, scratch-buffer inventories, numeric constants) — replaced by the state-category responsibilities of §3 and R12.
11. Camera Definition and Motion Driver as members of Intent State — reclassified: Camera Definition is an External render input consumed directly by the SkeletonProjector; Motion Driver is External persistent animation configuration consumed by the SkeletonPipeline at Phase 0. Neither enters the carrier.
12. Environment as author-declared Intent State content — split into Environment Definition (External, persistent, never in the carrier) and the per-frame Frame Context (injected once by the SkeletonPipeline via Runtime Context Injection, then immutable).
13. The unqualified settlement term family ("posture adjustments", "adjusted joint angles", "posture-resolved joint angles", "contact settlements") and unqualified root-transform names — replaced by Settlement Result (§3.2, §4.3) and space-qualified Root Transform usage (naming rule 4).

Nothing else was removed. Every remaining named object appears in the register (§4) with full identity.

---

## 8. Subsystem Self-Sufficiency

Each subsystem answers the four questions without reference to other chapters.

**Pose Authoring (PoseBuilder/BasePose + Intent Builder).**
Owns: creation of all Author Intent (sole writer, frozen at build return); authoring-time limb solving while it is the Active Limb Solver under R5.
Consumes: SkeletonDefinition, Exercise Definition, Contact Definition (when declaring contacts), Frame Progress derived by the SkeletonPipeline (§4.3).
Produces: a built SkeletonPose carrier with frozen Author Intent and initial Settled Geometry.
Outside its responsibility: Frame Context injection, settlement, finalization, validation, rendering; computing anything after build returns.

**Engine-side IK stage (gated).**
Owns: nothing persistently; acts as the Active Limb Solver when enabled.
Consumes: Limb Targets, SkeletonDefinition, the node hierarchy.
Produces: chain placements in Settled Geometry; first readings of solver-family Validation Stamps.
Outside: root movement, contacts, tilt handling, local-frame decisions, Frame Context.

**ConstraintSolver.**
Owns: settlement responsibilities — Root Placement, Contact Honor, Contact Conflict Resolution, Contact Re-Solve, Posture CCD, Inter-Frame Smoothing — and the Settlement Result; strengthen-only restamping.
Consumes: Author Intent (Contact Declarations, Contact Precedence, Posture Intent), Frame Context (Support Declaration, Environment context), Settled Geometry, Frame History, Contact Definition.
Produces: the Settlement Result (settled root transform in world space, settled-contact information, conflict outcome) and settled geometry in Settled Geometry; its Validation Stamps.
Outside: authoring intent, Frame Context writes, finalization conversions, validation, rendering; it never invents contacts and never moves non-contact authored shape except through declared posture regularization.

**SkeletonPoseFinalizer.**
Owns: Frame Conversion, Tilt Cancel, Chest-Frame Reconstruction, Head-Target Resolution, Extremity Derivation, Flatten; finalizer-produced Validation Stamps.
Consumes: Settled Geometry including the Settlement Result, Author Intent (non-settled-chain intents, Extremity Overrides, Head Target, Headings, Extremity Articulations), Frame Context (Environment context for ground-aware Extremity Derivation checks), SkeletonDefinition.
Produces: the Finalized Pose carrying Published Pose State with all transforms and its Validation Stamps.
Outside: altering the Settlement Result or settled contact end-effectors (R3/R7), root translation, validation.

**SkeletonPipeline.**
Owns: orchestration order, the Runtime Context Injection point, Frame History, Frame Progress derivation, and references to the SkeletonPoseFinalizer and ExerciseValidator (not ExerciseValidator ownership).
Consumes: built poses from Pose Authoring, Frame Progress sources (Motion Driver, Exercise Definition), external Environment Definition and the Production Metadata support context for injection.
Produces: driven frames; the Finalized Pose handed to callers.
Outside: geometry decisions of any stage; Camera Definition handling (it bypasses the SkeletonPipeline entirely and reaches the SkeletonProjector from the host render context); it coordinates but never computes pose content.

**ExerciseValidator.**
Owns: Rule Checks and Validator Profiles.
Consumes: Finalized Pose (Published Pose State), Author Intent ranges, Frame Context, Contact Definition, SkeletonDefinition, Frame History (dynamics).
Produces: Validation Report.
Outside: any write to the carrier; geometry derivation; driving the pipeline.

**Rendering layer (SkeletonProjector, renderer components, Snapshot Renderer, Bone topology, Rendering Style).**
Owns: projection and screen composition; Bone topology and Rendering Style definitions; Exercise Snapshot production.
Consumes: Finalized Pose, Camera Definition (directly from the host render context, never via the carrier), Frame Context (Environment context for surface and support depiction), rendering definitions.
Produces: Projected Output, Exercise Snapshot.
Outside: pose semantics; it never writes the carrier.

---

## 9. Findings Resolution Index

Pre-freeze audit findings and their resolutions in this revision.

| ID | Finding | Resolution |
|---|---|---|
| A1 | Joint inventory missing HIP_B; category counts wrong (stated 14/6/4 vs listed 16/7/6); node count stated 33 vs actual 31 | Corrected table and counts (§2.1–2.2) |
| A2 | WRIST_A/P classified ARTICULATION + "attachment host", contradicting verified phantom status | New ALIAS category; wrists alias HAND (§2.1–2.2) |
| A3 | END EFFECTOR treated as disjoint from ATTACHMENT in §1 but "subset" in ideal model | Resolved as mutually exclusive classifications sharing one mechanical nature: each identifier holds exactly one category (§2.1) |
| A4 | Attachment-type examples contradicted category membership (toe/fingertip/knee/elbow) | Examples aligned with register (§2.1) |
| A5 | RIG_HELPER/PROCEDURAL zero-member placeholder categories | Deleted (§7) |
| A6 | ConstraintSolver called stateless yet held cross-frame identity-keyed memory | R10; memory replaced by Pipeline-owned Frame History |
| A7 | Pipeline described as validator owner while validator is caller-created | Ownership clarified: caller owns, SkeletonPipeline references/drives (§4.5) |
| A8 | Relative Articulations/Spine Intent silently unconsumed for contact poses | R3 makes consumption deterministic: settled contacts win; skips are sanctioned |
| A9 | Limb Targets populated but unconsumed under default flag | R5 single-active-limb-solver rule; flag demoted to rollout mechanism |
| A10 | Pipeline mutated input pose environment/support invisibly | R8 Runtime Context Injection: single, ordered, then read-only |
| A11 | Terminology duplicates across ~25 concepts (gaze/head target, re-bake, tilt cancel, smoothing, stamps, camera input, …) | §1 canonical vocabulary with deleted synonyms |
| A12 | Joint rotations appeared in multiple places without canonical-source ruling | Rotation-space rule in §3.3 note; conversion exclusive to Frame Conversion |
| A13 | Stamp dual-writer ambiguity (primary/secondary without merge semantics) | §4.4 producers + R4/R6 merge and strengthen-only rules |
| A14 | Contracts used undefined terms (Pole, Re-Bake, Finalized Pose, stamps) | All defined in §1/§4 |
| A15 | Storage/format leakage (field lists, arrays, constants, function names) | Removed; responsibilities only (§3, §7.10) |
| A16 | Shared scratch across traversals creating implicit ordering | Scratch-isolation rule (§3) |
| A17 | Conflicting renderer lifecycle semantics for pipeline | R14: creator-owned instances; no shared pipeline |
| A18 | ContactSpec mixing biomechanical and solver concerns without canonical ruling | Declared aggregation: declaration is authored intent; chain context serves the same lifecycle; one object, one owner (authoring), consumers listed (§4.1) |
| A19 | Hardcoded solver constants presented as architecture | R12: bounds are tuning; architecture commits to boundedness only |
| A20 | HEAD_POS "marker masquerading as joint"; wrist/ankle "dual-role" claims | §2.1–2.2: HEAD_POS is ATTACHMENT (+ Landmark role); ankles are ARTICULATION; wrists are ALIAS |
| A21 | Spine Intent default axis inconsistent between definition and call site | R13: default owned by Skeleton Definition; call sites derive |
| A22 | Ideal-model entities overlapped §1 categories with different names | Single vocabulary (§1); duplicates deleted (§7) |
| A23 | Metadata carried unread fields (camera/timing/loop) with unclear consumers | §4.1 Group C: Camera Definition consumed by the projection path; timing/loop declared application playback concerns outside this architecture |
| A24 | "33 nodes" factory claim vs alias reality | §2.2 fact 1: 31 nodes, 33 identifiers |
| A25 | Camera Definition triple-classified (Intent State member, render input, projection parameter) | Resolved: External render input owned by the host render context; removed from Intent State; never enters the carrier; consumed directly by the SkeletonProjector and renderer components (§3.1, §4.1 Group C, R8, §6 Phase 5) |
| A26 | Motion Driver classified as per-frame Intent State | Resolved: External persistent animation configuration owned by exercise/configuration authoring; SkeletonPipeline consumes it at Phase 0 to derive Frame Progress (§3.1, §4.1 Group C, §4.3, §6 Phase 0) |
| A27 | Posture-adjustment term family ("posture adjustments", "adjusted joint angles", "posture-resolved joint angles", "contact settlements") competing as solver-result representations | Resolved: canonical Settlement Result defined — world-space settled root transform, settled-contact information, conflict outcome; no copy of final rotations; synonyms deleted (§1, §3.2, §4.2, §4.3, §7 item 13) |
| A28 | Joint-rotation ownership ambiguous across states | Resolved: authority-per-phase rule — Author Intent declared rotations are input only; Settled Geometry working locals authoritative during Phases 0–3 (world views derived via FK Propagation); Published Pose State authoritative and immutable after Phase 4; space-qualifier naming rule 4 (§3 rotation-space rule) |
| A29 | Environment conflated definition, intent, and injection mechanism | Resolved: Environment Definition (External, persistent, never in carrier) vs Frame Context (per-frame region of Intent State, written once by Runtime Context Injection, immutable after) vs Runtime Context Injection (the sole write mechanism) (§3.1, §4.1 Groups B/C, R8) |
| A30 | Finalized Pose vs Published Pose State double meaning | Resolved: Published Pose State is the state category; Finalized Pose is the per-frame carrier instance that delivers it, adding no state (§3.3, §4.3) |
| A31 | Semantic roles used as owners ("Pose Intent", "Render input", "Caller", "Definition layer") | Resolved: owner-kind model in §4 preamble (subsystem / state category / Active Limb Solver role / External); entire register normalized |
| A32 | Contact Declaration vs persistent possibility knowledge undistinguished | Resolved: Contact Definition added (External, permanent per definition) distinct from per-frame Contact Declaration, solver-outcome Contact Conflict Resolution, and operation Contact Re-Solve (§4.1 Group C) |
| A33 | Unqualified "rotation"/"root transform" terms permitting double meaning | Resolved: mandatory space qualifiers (declared/working/published) in contracts (naming rule 4; Root Transform synonyms deleted) |

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
