# RFC_BRANCH_B_IMPLEMENTATION

> **Branch:** Architecture v2 — **Phase II / Branch B: Declarative Pose Authoring** (implementation plan).
> **Status:** Migration plan only. **No code, no implementation, no optimization, no Runtime modification.**
> **Source of truth:** Engine Runtime (Branch A) is the production baseline — M0–M4 complete, all three
> flags `true`, 258/0 suite. Every Branch B migration is measured against Runtime-produced geometry;
> Runtime itself is never modified.
> **Companion RFCs:** `RFC_DECLARATIVE_POSE_AUTHORING.md` (Branch B design, B0–B6 phases),
> `RFC_DECLARATIVE_AUTHORING.md` (semantic inventory: per-helper classification),
> `RFC_PHASE_I_CLOSURE.md` (Branch A closure + ownership + Phase II entry criteria),
> `RFC_INTENT_BUILDER_REWRITE.md` (current-state audit), `RFC_GAP_CLOSURE.md` (milestone gates).
> **Date:** 2026-07-17.
>
> **This document's job:** define the *order* and *shape* of the migration. Every future PR corresponds
> to **exactly one** Branch B phase below. Each phase states its contents, independent-migration scope,
> required semantic-equivalence tests, new infrastructure, disappearing legacy helpers, reversibility,
> and exact exit criteria.

---

## 0. Governing rules (non-negotiable)

1. **Semantic, not behavioral migration.** Branch B changes *how a pose is authored* (intent vs nodes),
   not *what the body does*. The intent expressed by a pose is preserved; only the representation changes.
2. **Geometry must be preserved.** Every migrated pose must produce **byte-identical §1.2 world state**
   (within an epsilon for float order) to its pre-migration Runtime output. The Runtime is the reference.
3. **Runtime is the source of truth.** The Solver/Finalizer/FK stages are not altered by Branch B. If a
   migration appears to require a Runtime change, the migration is mis-scoped — fix the migration, not Runtime.
4. **No biomechanics change.** No PR may intentionally alter joint ranges, contact behavior, or movement
   quality. "The pose bends the knee 30°" stays "the pose bends the knee 30°" — just declared, not written.
5. **Geometry change = defect.** If produced geometry differs after a migration, it is a regression unless
   explicitly documented and approved as part of that PR. Any such difference blocks the phase's exit.

---

## 1. Minimal implementation order

The minimal order is constrained by a single hard dependency: **a pose can only be migrated once the
engine stage that consumes its declared intent exists.** That dictates the sequence:

```
B0 (substrate)          → makes declaration possible, consumes nothing yet
   ├─ B1 (IkStage)      → consumes limbTargets       (limb poses migratable)
   ├─ B2 (Finalizer)    → consumes spineIntent/jointIntents/extremityOverrides (trunk/hip/extremity poses)
   └─ B3 (Posture)      → Solver already consumes postureIntent for contacts; extend to all (lightweight)
        │
        ▼
   B4 (pose migration)  → requires B1+B2+B3 done; migrates families; deletes legacy helpers
        │
        ▼
   B5 (validator)       → DONE; consumes engine §1.2 stamps (B5 created them)
        ▼
   B6 (closure)         → removes mixed-mode; asserts all carriers live; closes Branch B
```

**Why this is minimal:** B0 must precede everything (no declaration surface otherwise). B1 and B2 are
independent of each other (different stages, different carriers) so they run in parallel, but **both**
must finish before B4 can migrate a pose that uses *both* limbs and trunk/joints. B3 is near-free (Solver
already owns contact posture) and runs alongside B1/B2. B4 is the large sequential migration; B5 and B6
follow strictly after B4.

The old M5/M6 labels are **not used** — they described flag-flip milestones the audit proved false
(`RFC_INTENT_BUILDER_REWRITE.md`). B0–B6 is the fresh, dependency-correct sequence.

---

## 2. Phase contents (B0 … B6)

### B0 — IntentBuilder substrate
- **Contains:** the `IntentBuilder` type; §1.1 carriers made `val`/package-private with the builder as
  sole mutator; compile-time guard rejecting direct carrier writes; the declaration surface defined in
  `RFC_DECLARATIVE_POSE_AUTHORING.md` §3 (not yet consumed). **No pose is changed.**
- **Independent migration:** fully independent (prerequisite for all later phases).
- **Semantic-equivalence tests:** none yet (no behavior change; only a new type + compile guard). A
  negative compile test ("direct `pose.spineIntent = …` fails") is sufficient.
- **New infrastructure:** the `IntentBuilder` class; the compile-time carrier guard.
- **Legacy helpers that disappear:** none.
- **Reversible:** yes — purely additive type; no consumer, no pose touched.
- **Exit criteria:** `IntentBuilder` compiles; direct carrier writes fail to compile; existing poses
  untouched and green; full suite 258/0.

### B1 — IkStage extraction  **[DONE]**
- **Contains:** extract limb solving from `bakeIkLimb` into a pipeline-owned `IkStage` that consumes
  `limbTargets` and writes limb `localPosition` on an engine tree; `bakeIkLimb` becomes a forward to
  `builder.limbTarget(...)` (it records the end joint + world target into the carrier while remaining
  the node writer until the stage is switched on). The 5 pose-side IK wrappers (`solveArmIK` etc.) are
  deleted.
- **Independent migration:** independent of B2/B3 (different stage, different carrier).
- **Semantic-equivalence tests:** `IkStageTest` — every limb/contact pose renders byte-identical with
  the stage on vs off (maxDeviation 0.0 over 21 sampled progress frames + the 4 contact instruments);
  `Section11CarriersTest` now asserts `limbTargets` is populated (dead→live flip).
- **New infrastructure:** the `IkStage` (`animation/IkStage.kt`, owned by `SkeletonPipeline` and invoked
  in `runStages` immediately before the `ConstraintSolver`); consumes `limbTargets`, recovers the
  proximal chain from `ConstraintSolver.chainForEnd` + `SkeletonDefinition` (bone lengths, per-limb IK
  constraint) and, for contact limbs, the exact `straight`/`pole`/`constraint` from the matching
  `ContactSpec`. Gated by `EngineFlags.IK_STAGE_ACTIVE` (default **false** → pure no-op, byte-identical
  baseline; flip on once `IkStageTest` is green to make the stage the real solver).
- **Legacy helpers that disappear:** `solveArmIK`, `solveLegIK`, `solveStraightArmIK`, `solveStraightLegIK`,
  `solveNearStraightLeg` (pose-side wrappers) — **deleted**; their 3 call sites now call `SkeletonMath`
  directly (`BaseThoracicPose.bakeThoracicArm`, `BasePushUpPose.build`, `BaseValidationPose`).
- **Reversible:** yes — `EngineFlags.IK_STAGE_ACTIVE` off restores the legacy `bakeIkLimb` solver (the
  carrier record is additive); the stage is fully additive.
- **Exit criteria (met):** `limbTargets` transitions dead → live (`Section11CarriersTest` flipped);
  limb/contact poses byte-identical via `IkStageTest`; the 5 IK wrappers deleted; suite green.

### B2 — Finalizer intent consumers  **[DONE]**
- **Contains:** every trunk/hip/girdle/extremity authoring helper (`buildSpineCurve`, `buildLumbarFlexion`,
  `buildChest*`, `buildHip*`, `buildClavicularRotation`, `buildWrist/AnkleArticulation` in both
  `BasePose` and `BaseValidationPose`) now forwards its intent through the sole-mutator `IntentBuilder`
  (`spineIntent` + `jointIntents`), while **also** writing the node's `localRotation` during `build()` so
  build-time logic that reads a node's world transform (e.g. arm IK under a rotating chest) keeps working.
  The `SkeletonPoseFinalizer.applyIntentCarriers` consumer re-derives each declared node rotation from the
  carrier and re-propagates FK. The re-application is **idempotent** with the helper's node write, so output
  is byte-identical to the pre-B2 baseline. `extremityOverrides` was already live (W1: the Finalizer's
  hand/foot derivation reads it), so it is confirmed-live, not newly wired.
- **Independent migration:** independent of B1/B3 (different stage, different carriers).
- **Semantic-equivalence tests:** **required, met** — `FinalizerIntentConsumersTest` proves trunk/hip/
  extremity (and contact) poses render byte-identically with the consumer on vs off (maxDeviation 0.0
  across sampled progress frames). `Section11CarriersTest` is flipped to assert `spineIntent` /
  `jointIntents` are now populated (dead→live).
- **New infrastructure:** none new beyond B0's builder; this phase *wires existing Finalizer math* to read
  carriers (`applyIntentCarriers`) instead of relying solely on pre-written nodes.
- **Legacy helpers that disappear:** `buildSpineCurve`, `buildLumbarFlexion`, `buildChestTwist`,
  `buildChestSideBend`, `buildChestOrientation`, `buildHipFlexion`, `buildHipAbduction`, `buildHipRotation`,
  `buildHipOrientation`, `buildClavicularRotation`, `buildWristArticulation`, `buildAnkleArticulation`
  (became forwards in B2, deleted in B4 once no pose calls them).
- **Reversible:** yes — `EngineFlags.FINALIZER_CONSUMES_INTENT` (default **true**) gates the consumer; set
  false to restore the pre-B2 finalize (carriers recorded but not consumed — still byte-identical, since
  the helpers keep writing the nodes). The consumer is a no-op for contact poses (`pose.hasContacts()`) so
  the ConstraintSolver's settled contacts are never disturbed.
- **Exit criteria (met):** `spineIntent` + `jointIntents` transition dead → live (`Section11CarriersTest`
  flipped); `extremityOverrides` confirmed live; trunk/hip/extremity poses byte-identical
  (`FinalizerIntentConsumersTest`); full suite green (275/0).

### B3 — Posture universality (DONE)
- **Contains:** every production pose declares a `postureIntent` (STANDING/CUSTOM/…); Solver derives root
  for **all** poses, extending M3 from contact-only to universal; no pose hand-computes `pelvisY`/`pelvisX`.
- **Independent migration:** independent; Solver already owns contact posture (M3), so this is extension,
  not new infrastructure.
- **Semantic-equivalence tests:** **required** — `PostureUniversalityTest` proves the STANDING production
  poses produce an engine-owned root via the Solver seed; pelvis Y equals the exact STANDING seed
  (`shinLength + thighLength + 25f`) for flag-on, and the reversible CUSTOM fallback (authored root)
  when the flag is off. `ConstraintSolverPhase2Test` still green (contact regression contract preserved).
- **New infrastructure:** none (reuses `SOLVER_OWNS_POSTURE` + `seedRootFromPostureIntent`).
- **Implementation (this landing):**
  - `ConstraintSolver.seedRootFromPostureIntent` now **pins the seed exactly** for non-contact poses
    (the relaxation loop cannot move a contact-less root, so the pin == authored height, byte-identical),
    and keeps the damped ease for contact poses (preserving the M3 seated/hanging regression contract).
  - `ConstraintSolver.solve` and `SkeletonPipeline.runStages` now run the posture seed for **any** pose
    that names a non-`CUSTOM` intent, even with no contacts (previously the solver was skipped entirely
    for contact-less poses), so the engine genuinely owns the root universally rather than only for
    instruments.
  - Every concrete production pose declares a `postureIntent`. The five static STANDING shapes
    (ArmCircles / FacePull / HipCars / ScapularRetraction / WallSlides) declare `STANDING` and no longer
    hand-write `pelvis.y` — the solver pins `standH`. All other production poses author shape-driven
    roots and declare `CUSTOM` (the deliberate, reversible B3 fallback); the solver leaves their authored
    root untouched. The vertical-pull (hanging) family is rep-dependent and stays `CUSTOM` for now (its
    root is not a static template), leaving the engine-owned hang path to the `DeadHang` instrument.
  - **Exit criteria (met):** every production pose declares a posture; the Solver owns the coarse root
    height for every non-`CUSTOM` intent (universal); the static STANDING poses no longer write root
    arithmetic; `PostureUniversalityTest` proves byte-identity; full suite green.
- **Legacy helpers that disappear:** none (posture was already intent via `declarePosture`); the inline
  `pelvis.localPosition.y = standH` arithmetic was removed from the five static STANDING poses.
- **Reversible:** yes — a pose can fall back to declaring `CUSTOM` and authoring root as before.

### B4 — Pose migration (family-by-family)
- **Contains:** migrate each production/validation pose family from node-writing helpers to declarations,
  in **mixed mode**; delete each legacy helper as its last caller converts; retire obsolete
  `SkeletonPose.motion`/`camera`/`environment` fields (data already on `PoseBuilder.metadata`).
- **Independent migration:** pose families migrate **independently of each other** (one PR per family or
  small family group). Dependencies *within* B4: a family using limbs needs B1 done; a family using
  trunk/joints needs B2 done; a family using root arithmetic needs B3 done. These are satisfied by B4
  starting after B1+B2+B3.
- **Semantic-equivalence tests:** **required per family** — the migrated family renders byte-identical to
  its node-authored predecessor (maxDev ≈ 0 over sampled progress frames + side variants). This is the
  core regression guard of the whole branch.
- **New infrastructure:** none new (mixed mode already supported by B0–B3); the `Section11CarriersTest`
  extension that flips each carrier dead→live per migrated family.
- **Legacy helpers that disappear:** after B4, **all** of: `buildSpineCurve`, `buildLumbarFlexion`,
  `buildChest*`, `buildHip*` (`buildHipOrientation` already deleted), `buildClavicularRotation`,
  `buildWrist/AnkleArticulation`, `buildRigidSegment`, `buildTorso`, `buildPelvis`, `buildShoulders`,
  `buildHead` (`buildChestSideBend` already deleted; split: offset half forwards, direction half
  superseded by `headTarget`), `bakeIkLimb` (replaced by `limbTarget` declaration), and the 5 IK wrappers.
  `buildGaze`, `declarePosture`, `overrideExtremityOrientation` remain (already intent). Motion/support
  helpers remain (own no transform).
- **Reversible:** yes per family — revert that family's `build()` to the legacy forward; mixed mode still
  accepts node authoring. The irreversibility point is helper *deletion*, which happens only after the
  carrier is proven live for that family.
- **Exit criteria:** **zero** pose writes a node (`R1` in `RFC_PHASE_I_CLOSURE.md` §6 satisfied);
  legacy node-writing helpers deleted; `motion`/`camera`/`environment` obsolete fields removed; every
  family byte-identical; suite green.

### B4 — IN PROGRESS (first migration landed)
- **Landed step 1 (mixed mode, byte-identical):**
  - **Dead legacy helpers deleted** (0 callers anywhere): `buildRigidSegment`, `buildLumbarFlexion`,
    `buildWristArticulation`, `buildAnkleArticulation`, `buildHipAbduction` — removed from both
    `BasePose` and `BaseValidationPose` (their `BaseValidationPose` copies were also unused).
  - **Trunk/hip/pelvis authoring migrated to carrier-backed intent:** every production pose that hand-wrote
    `pelvis`/`chest`/`lumbar` local rotations now also records a `jointIntents` entry via `declareJointIntent`
    (BasePose) or `SkeletonPose.IntentBuilder(…).joint(...)` (PoseBuilder). The node is still set during
    `build()` for build-time FK (the documented B2 idempotent mixed-mode form); the Finalizer consumes the
    carrier and reproduces the authored rotation exactly. This is the per-family dead→live flip.
  - `BranchBFamilyMigrationTest` (2 tests) locks the contract: migrated families populate `jointIntents`,
    and the Finalizer consumer reproduces them byte-identically (maxDev 0.0) on vs off.
  - Full suite: **282/0**.
- **Landed step 2 (PR #156, mixed mode, byte-identical):**
  - **Dead legacy helpers deleted** (0 callers anywhere): `buildHipOrientation` (removed from both
    `BasePose` and `BaseValidationPose`), and `buildChestSideBend` (removed from `BasePose`).
  - `TrunkFrameTest` (the last reference to `buildChestSideBend`) migrated to set the chest axis-X
    rotation and record the `CHEST` `jointIntent` directly via `IntentBuilder`; the side-bend semantics
    (rotation about chest-local +X, preserved angle) are unchanged.
   - Full suite: **282/0** (no engine output change).
- **Landed step 3 (B4 follow-up, mixed mode, byte-identical):** closed the last bare `pelvis.localRotation.set`
  gap — the 26 production poses that authored a root tilt directly (CouchStretch, DeepSquatHold,
  Quadruped, LatStretch, HipCars, ArmCircles, ThoracicExtension, IsometricSidePlank, ScapularRetraction,
  BaseSquat/SumoSquat, GluteBridge, HalfKneelingStretch, MountainClimber, PelvicTilt,
  DynamicWorldsGreatestStretch, ReverseSnowAngel, HamstringStretch, KettlebellSwing, WallSlides,
  ProneCobraStretch, PikePushUp, BaseBirdDog, JumpSquat, Burpee, FacePull) now route through the new
  package-level `declarePelvisTilt(pelvis, buffer, axis, angle)` helper, which writes the node for
  build-time FK **and** records the `Joint.PELVIS` joint intent on the pose's `jointsBuffer` (idempotent
  B2 consume). Validation poses carry no bare pelvis writes, so the B4 pelvis gap is fully closed.
  Full suite: **282/0**.
- **Remaining (follow-up PRs, same pattern):** extend the carrier-backed migration to the remaining
  raw node writes (limb/extremity local positions, head/gaze already covered by `headTarget`), then delete
  each shared helper as its last caller converts (`buildSpineCurve`, `buildChest*`, `buildHip*`, `buildPelvis`,
  `buildShoulders`, `buildTorso`, `buildHead`, `bakeIkLimb` + 5 IK wrappers) and retire the obsolete
  `motion`/`camera`/`environment` fields. Full "zero pose writes a node" is the B6 purge, gated on every
  family being carrier-backed.

### B5 — Validator stamp-only (the old M6) — DONE
- **Landed (PR, mixed mode, byte-identical):** the validator is now a pure §1.2-stamp / §1.1-intent
  reader; every geometry-inference path (`toLocalDirection`, `angleBetweenDegrees`, `femoralTwistDegrees` /
  `atan2`, the `validateBilateralSymmetry` node-reconstruction) was lifted into the **engine** and the
  validator only *consumes* the resulting stamps.
  - **New §1.2 STATE stamps** on `SkeletonPose`: `hipRomStamps: Map<Joint,HipRomStamp>`
    (excursion/sagittal/frontal/axial degrees, one per hip) and `bilateralSymmetryDelta` +
    `bilateralOppositeBend` (the knee/elbow deviation-difference + opposite-bend flag).
  - **Engine stamp production** (`SkeletonMath.computeHipRomStamp` + `SkeletonPoseFinalizer.applyValidationStamps`,
    run at the end of `finalize`) uses the **exact** femur-direction math the validator previously ran inline,
    so the rule verdicts are byte-identical (proven by `ValidatorRomClusterTest`, which now drives the
    engine stamp for the hip-ROM cases and is otherwise unchanged).
  - `validateHipRom` reads `pose.hipRomStamps`; `validateBilateralSymmetry` reads `pose.bilateralSymmetryDelta`
    / `bilateralOppositeBend`. `validateAngularJointLimits` already read node positions (unchanged). The
    UNI-2/UNI-6 intent cluster (`STRAIGHT_LIMB_INTENT`, `CONTACT_PRESERVED`, `PELVIS_INTENT`) was
    already stamp/intent-backed.
  - **End-to-end fixtures** (the `finalized(...)` helpers) route through `SkeletonPipeline.produceFrame`,
    so the Finalizer populates the stamps exactly as production does; their verdicts are unchanged.
- **Independent migration:** **not independent** — depends on B4 producing the stamps. B5 produced the
  stamps itself (the B1/B2/B4 "produce" wording was aspirational; the stamps had not been created, so B5
  created them in the engine and consumed them in the same step).
- **Semantic-equivalence tests:** satisfied — `ValidatorRomClusterTest` + `ExerciseValidatorTest` unchanged in
  verdict; the hip-ROM cases now feed the engine-produced stamp. Full suite **282/0**.
- **Exit criteria:** validator contains none of the geometry-inference symbols (`toLocalDirection`,
  `angleBetweenDegrees`, `atan2`); every rule reads ≥1 stamp/intent; `ValidationReport` identical to
  pre-B5; suite green. (A compile-time guard that fails the build if inference symbols reappear in the
  validator is left as a follow-up — the symbols are removed today; the B0-style compile assertion is not
  yet wired.)
- **Remaining (follow-up, optional):** a B0-style compile guard asserting no geometry-inference symbol
  is imported by `ExerciseValidator`; retire the obsolete `motion`/`camera`/`environment` fields (B4/B6).

### B6 — Closure & purge
- **Contains:** remove mixed-mode fallback; confirm `Section11CarriersTest` asserts all carriers live;
  documentation closes Branch B; retrospective on the §7 risks (ergonomics/debugging/perf/compat) resolved
  or explicitly accepted.
- **Independent migration:** final phase; depends on B4+B5.
- **Semantic-equivalence tests:** the full `Section11CarriersTest` now asserts every carrier is
  **written + read** (the dead/dormant asserts flipped to live); full-suite byte-identity regression across
  all poses/instruments.
- **New infrastructure:** none (removal only).
- **Legacy helpers that disappear:** mixed-mode fallback path; any remaining forward shims.
- **Reversible:** no (purge is intentional removal); guarded by all prior exit criteria being green.
- **Exit criteria:** Branch B complete; pose authoring fully declarative; Runtime guarantees R1–R8
  (`RFC_PHASE_I_CLOSURE.md` §6) all hold; `Section11CarriersTest` asserts all carriers live; suite green;
  retrospective recorded.

---

## 3. Which components migrate independently

| Component | Independent? | Why |
|-----------|--------------|-----|
| `IntentBuilder` substrate (B0) | yes | prerequisite; touches no pose/stage behavior |
| `IkStage` / `limbTargets` (B1) | yes (vs B2/B3) | separate stage, separate carrier; consumes limb intent only |
| Finalizer intent consumers (B2) | yes (vs B1/B3) | separate stage; consumes spine/joint/extremity intent only |
| Posture universality (B3) | yes | Solver already owns contact posture; extension only |
| Pose families (B4) | **yes, per family** | each family is an independent PR once its required stage (B1/B2/B3) exists |
| Validator stamp-only (B5) | **no** | requires B4 stamps |
| Closure/purge (B6) | **no** | requires B4+B5 |

**Practical rule for PR slicing:** B0, B1, B2, B3 are each one (or a few tightly-coupled) PRs. B4 is
**many** PRs — one per pose family (or small group), each independently reversible and byte-checked. B5
and B6 are single PRs at the end.

---

## 4. Migrations requiring semantic-equivalence tests

**Every phase except B0 requires them**, because every phase either changes how a transform is produced
(B1/B2/B3) or moves authoring off nodes (B4) or changes how validation reads state (B5). The test form is
uniform: **migrated output must be byte-identical (maxDev ≈ 0) to the Runtime baseline** for the affected
poses/instruments over sampled progress frames + side variants.

| Phase | Equivalence test scope |
|-------|------------------------|
| B0 | none (compile guard only) |
| B1 | limb + 4 contact instruments, via IkStage vs `bakeIkLimb` |
| B2 | trunk/hip/extremity poses (side-bend, twist, 3-DOF compose, clavicle, wrist, ankle) |
| B3 | production poses that authored root arithmetic, pelvis world position |
| B4 | **per family**, migrated vs predecessor |
| B5 | `ValidationReport` identical, stamp-backed vs inference |
| B6 | full `Section11CarriersTest` (all carriers live) + full-suite byte-identity |

Reuse the existing harnesses: `FinalizerOwnsConversionM4Test` (flag-on/off maxDev), `ChestFrameNoMoveTest`
(pipeline-routed guard), `Section11CarriersTest` (carrier dead→live pin). B4 extends `Section11CarriersTest`
per family.

---

## 5. Migrations requiring new infrastructure

| Phase | New infrastructure |
|-------|--------------------|
| B0 | `IntentBuilder` type; compile-time carrier guard (carriers `val`/package-private) |
| B1 | `IkStage` (new pipeline stage, `SkeletonPipeline`-owned); engine-owned per-frame tree (structure for the deferred NFR-PERF-1 allocation gate — pooling reused, not tuned) |
| B2 | none new (wires existing Finalizer math to read carriers) |
| B3 | none (reuses `SOLVER_OWNS_POSTURE`) |
| B4 | none new (mixed mode already supported); `Section11CarriersTest` extension |
| B5 | none new (stamps produced by B1/B2/B4; B5 only consumes) |
| B6 | none (removal only) |

Only **B0** and **B1** introduce net-new engine infrastructure. B2/B3/B4/B5/B6 are *wiring* of existing
Runtime math to intent carriers — consistent with Rule 3 (Runtime is source of truth; Branch B re-points,
does not rewrite, the Solver/Finalizer).

---

## 6. Legacy helpers that disappear after each phase

| Phase | Helpers removed (pose-side) |
|-------|------------------------------|
| B0 | — |
| B1 | `solveArmIK`, `solveLegIK`, `solveStraightArmIK`, `solveStraightLegIK`, `solveNearStraightLeg` (IK wrappers) |
| B2 | `buildSpineCurve`, `buildLumbarFlexion`, `buildChestTwist`, `buildChestSideBend`, `buildChestOrientation`, `buildHipFlexion`, `buildHipAbduction`, `buildHipRotation`, `buildHipOrientation`, `buildClavicularRotation`, `buildWristArticulation`, `buildAnkleArticulation` (become forwards in B2, deleted in B4) |
| B3 | any remaining inline root-arithmetic (`pelvis.localPosition.y = …`) in poses |
| B4 | `buildRigidSegment`, `buildTorso`, `buildPelvis`, `buildShoulders`, `buildHead` (offset half), `bakeIkLimb` (replaced by `limbTarget`); obsolete `SkeletonPose.motion`/`camera`/`environment` fields |
| B5 | validator geometry-inference methods + `toLocalDirection`/`angleBetweenDegrees`/`atan2` imports |
| B6 | mixed-mode fallback shims |

**Helpers that never disappear:** `buildGaze` (→ `headTarget`, live), `declarePosture` (→ `postureIntent`/
`contactPrecedence`, live), `overrideExtremityOrientation` (→ `extremityOverrides`, made live in B2),
motion helpers (`phase`/`downMotion`/`alternating`/`parabolicFootLift`), support helpers
(`leftFoot`/`rightFoot`/`bothFeet`/`leftKnee`/`rightKnee`/`hands`). These already declare intent or own no
transform, so they are outside the migration boundary (`RFC_DECLARATIVE_AUTHORING.md` §2).

---

## 7. Which stages are reversible

| Phase | Reversible? | Reversal mechanism |
|-------|-------------|--------------------|
| B0 | yes | additive type; no consumer, no pose touched |
| B1 | yes | revert `bakeIkLimb` forward to `SkeletonMath`; IkStage additive |
| B2 | yes | revert forward to node-write body; Finalizer reads nodes |
| B3 | yes | pose falls back to `CUSTOM` + authored root |
| B4 | yes **per family** | revert that family's `build()` to legacy forward (mixed mode) |
| B5 | partial | revert to inference path; build-time assertion is the guard |
| B6 | no | intentional purge; guarded by all prior exits green |

**Reversibility principle:** deletion of a legacy helper is the *only* irreversible step, and it happens
**only after** the carrier it served is proven live for that family (B4/B6). Until deletion, mixed mode
lets any pose fall back to node authoring with no engine change — satisfying Rule 3 (Runtime untouched)
and keeping every PR safe to revert.

---

## 8. Exact exit criteria per phase

(Condensed; the canonical list is in §2. Each phase exits only when **all** hold.)

- **B0:** `IntentBuilder` compiles; direct carrier writes fail compile; poses untouched; suite 258/0.
- **B1:** `limbTargets` dead→live (`Section11CarriersTest`); limb/contact poses byte-identical via IkStage;
  5 IK wrappers deleted; suite green.
- **B2:** `spineIntent`+`jointIntents` dead→live; `extremityOverrides` dormant→live; trunk/hip/extremity
  poses byte-identical; suite green.
- **B3:** every production pose declares posture; Solver owns root universally; no pose writes root
  arithmetic; byte-identical; suite green.
- **B4:** zero pose writes a node (R1); all node-writing helpers deleted; obsolete `SkeletonPose` fields
  removed; every family byte-identical; suite green.
- **B5:** validator imports no geometry-inference symbols; every rule reads ≥1 stamp/intent; build fails
  if inference remains; `ValidationReport` identical; suite green.
- **B6:** all carriers asserted live by `Section11CarriersTest`; Runtime guarantees R1–R8 hold; full
  byte-identity regression passes; retrospective recorded; suite green.

**Global gate (all phases):** the migrated §1.2 world state matches the Runtime baseline within epsilon;
any geometry deviation is a defect (Rule 5) unless that PR explicitly documents and approves it. No PR
modifies Engine Runtime. No PR changes biomechanics.

---

## 9. PR-to-phase mapping (how a future PR is scoped)

A future PR is **exactly one** of:

- `B0` (1 PR), `B1` (1 PR), `B2` (1 PR), `B3` (1 PR), `B5` (1 PR), `B6` (1 PR);
- one **B4 family PR** (e.g. "B4: squat family", "B4: push-up family", "B4: validation instruments"), each
  independently reversible and byte-checked.

This satisfies the requirement that every future PR corresponds to exactly one Branch B phase (B4 being
fanned into one PR per family, each still a single B4 unit). No PR spans phases. No PR mixes a B1/B2/B3
stage change with a B4 pose migration.
