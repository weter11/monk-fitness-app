# IMPLEMENTATION PLAN — Runtime Skeleton Architecture

**Implements:** `docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` (frozen; READY FOR ARCHITECTURE FREEZE, audits A1–A33 resolved).
**Status:** In execution. Phases 0–12 complete and merged (`main` @ `3f6733d`; P12 = PR #225, merge `914a6f6`); P11's whole-system audit is PR #226 (open). Each phase is audited against this plan before it starts. See Execution log below.
**Ground rules honored:** every phase cites exact RFC §/R-rules; all current-code claims were source-verified (file:line cited); phases ordered by dependency; each phase is a single-reviewable diff; every non-RFC-dictated choice is flagged as an IMPLEMENTATION DECISION.

---

## Execution log

| Phase | Result | Record |
|---|---|---|
| P0 Baseline & characterization harness | ✅ Complete | PR #207 (merged as `17e6455`): harness `RuntimeArchitectureBaselineTest` (`181c4f0`) + audit-blocker closure commit `36bc149` (KDoc-only: intent-timing caveat; golden-update policy for compromised stamp families) |
| P1 R8: Runtime Context Injection single-point | ✅ Complete | PR #211 (merged as `d5de6d9`): `1eacb68` extraction + debug-gated enforcement (`RuntimeContextSnapshot`, `BuildConfig.DEBUG` enablement, `RuntimeContextInjectionTest`); `90075c0` snapshot negative-path unit tests |

**Binding decisions from the #211 implementation review (apply to all later phases):**

- The frozen RFC (`docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md`) must remain byte-identical during implementation phases. Two clarification sentences drafted during P1 were **reverted** (`90075c0`) and remain **unratified proposals**, recorded here only:
  1. Frame Context may reference the externally owned Environment Definition by reference (ownership stays external; no subsystem may mutate the referenced instance).
  2. Debug-build invariant-enforcement snapshots inside the pipeline are observability instrumentation, not Frame Context consumption (R8(b) scoping).

**Architecture-owner debt ledger (carried forward; none blocks P2 start):**

- **V12** — second producer of Root Translation Delta (Finalizer strengthen site vs §4.4 sole-producer rule): adjudicate in **Phase 2** (owns stamp semantics). No provenance assigned yet.
- **Straight-intent-dropped flag**: architecture-declared (§4.4), currently vacuous in production (no writer/reader). Producers arrive in Phase 4 per plan Risk 2.
- **RFC internal inconsistency**: `Pose Result State` appears under both the Settlement Result row and the Pose State row of the terminology table — fix at amendment time.
- **Frame Progress / Motion Driver**: RFC §4.3 states the pipeline derives progress; production callers construct `PoseContext(progress = …)` directly. Reconcile before any phase depends on pipeline-derived progress.
- Baseline KDoc corrections (in `RuntimeArchitectureBaselineTest`, P0 closure): ArmCircles fixture is posture-driven (STANDING declared in-build; solver executes); golden-value existence ≠ proven semantic producer.

---

## Source-verification summary (performed before planning)

Every "Current state" below was grep/read-verified against `app/src/main/java/com/monkfitness/app/`. Register-vs-code discrepancies discovered (planned around, not assumed away):

| # | RFC says | Code reality | Verified at |
|---|---|---|---|
| V1 | Straight-Intent-Dropped Flag producers: Active Limb Solver + ConstraintSolver (§4.4) | **No producer exists anywhere.** Only declaration `PoseDefinition.kt:249` and copy `:330` | grep all `.kt` |
| V2 | Solver restamp is strengthen-only (R6) | `ConstraintSolver.kt:236` resets `boneLengthsVerified = true`, erasing a primary `false` from non-contact limbs before re-ANDing contact limbs only (`:370`) | read 223–240, 360–372 |
| V3 | Inter-Frame Smoothing consumes Pipeline-owned Frame History (R10) | Solver owns identity-keyed memory `lastSolvedRoot: WeakHashMap<SkeletonPose, Vector3>` (`ConstraintSolver.kt:95`, read `:267`, write `:414–418`). Pipeline history `previous/prePrevious` (`SkeletonPipeline.kt:55–56`) is maintained **only** in `produceFrameValidated` (`:162–163`), not the plain render path | read both files |
| V4 | Spine Intent default axis owned by SkeletonDefinition (R13) | Call-site default `axis: Vector3 = axisZ` at `BasePose.kt:175`; `axisZ` hardcoded (`BasePose.kt:17`) | read |
| V5 | Default Pole owned by Active Limb Solver when undeclared (§4.2) | Already true in both implementations: `BasePose.kt:273–278` and `IkStage.kt:87–90` | read |
| V6 | Rendering layer reads only | Zero pose-mutating lines in `SkeletonRenderer.kt` / `SkeletonSnapshotRenderer.kt` | grep |
| V7 | Scratch isolation (§3 rule) | Satisfied: private `SkeletonMath.IKResult()` per consumer (`ConstraintSolver.kt:104`, `IkStage.kt:43`), instance passed as `ikBuffer` param | grep |
| V8 | R14 creator-owned pipelines | `SkeletonSnapshotRenderer.kt:18`, `SkeletonRenderer.kt:37` (`remember(engine.definition)`) | grep |
| V9 | Finalizer stamp production | `applyValidationStamps` (`SkeletonPoseFinalizer.kt:431`) writes `hipRomStamps[:447]`, `bilateralSymmetryDelta[:467]`, `bilateralOppositeBend[:468]` after derivation | read |
| V10 | Settlement Result does not exist yet | Confirmed — solver settles implicitly; Finalizer re-reads carrier state; no settled-contact handoff object | grep |
| V11 | `SkeletonFactory` does NOT consume `SkeletonDefinition` | `createStandardSkeleton()` takes no definition (`SkeletonFactory.kt:59`); consumers verified: `SkeletonPipeline.kt:41,50,119,127,153` (holder/supplier), `IkStage.kt:53`, `ConstraintSolver.kt:214`, `SkeletonPoseFinalizer.kt:16`, `ExerciseValidator.kt:105+`, pose authoring via `PoseContext`/`PoseDefinition`, rendering via `SkeletonEngine.kt:4` | grep |

---

## Dependency table (single authoritative representation)

| Phase | Depends on | One-line reason |
|---|---|---|
| P0 Baseline harness | — | Golden fixtures every later phase asserts against |
| P1 R8 injection single-point | P0 | Fixtures detect behavioral drift from the extraction |
| P2 R4/R6 stamp merges | P0 | Helpers must exist before any new stamp-writing phase |
| P3 Settlement Result | P0 | Populated object needed by P7's reference set |
| P4 R5 limb-solver unification + straight-flag producer | P2 | Straight-flag writes go through P2's merge helpers |
| P5 R10 remove solver memory / pipeline history | P0 | Independent lane; finalizes solver signature |
| P6 R2 root-authority assertions | P1, P5 | Boundary instrumentation exists (P1); solver signature stable before instrumenting around it (P5) |
| P7 R3 settled-contact guarantee | P3, P6 | Reference set from P3; shared `PhaseBoundaryAsserts` utility introduced in P6 |
| P8 §6 Phase 4 publish ordering | P2, P3, P4 | Centralized stamp writes (P2); supersede point defined (P3); upstream limb-phase stamps exist (P4). **Explicitly NOT P7** — see below |
| P9 R9 observer lock-in | P8 | Observes the completed published-state condition |
| P10 R13 defaults ownership | P0 | Independent lane |
| P11 R11/R14 compliance | P1–P10, P12 | Verifies the assembled whole (the whole includes the activated configuration) |
| **P12 R5 Activation / Limb-Solver Ownership Transition** | P4, P5, P6, P7, P8 | Sole owner of the move from the runtime-validation state to the activated state in which `IK_STAGE_ACTIVE=true` is a valid production configuration (see Phase 12) |

**P7→P8 resolution: P8 does NOT require P7.** They guard disjoint windows with disjoint mechanisms: P7 asserts Settled Geometry invariants between Phase 2 exit and Phase 3 completion (contacts don't move during finalization); P8 structures Phase 4's internal write order and immutability onset. Neither consumes the other's outputs, and P8's tests are expressible without P7's assertions being live. Practical note (scheduling, not dependency): landing P7 before P8 avoids potential diff churn if a guarantee violation ever forces a Finalizer change.

**Parallel lanes after P0:** {P1, P2, P3, P5, P10} concurrent; then P4 (needs P2), P6 (needs P1+P5), P7 (needs P3+P6); P8 once P2+P3+P4 complete, independent of P6/P7 progress; P9 after P8; P12 after P4+P5+P6+P7+P8, parallel with P9/P10; P11 closes (after P12).

---

## Risk list

1. **V2 is a live R6 defect** (solver resetting `boneLengthsVerified=true`). The merge-once rewrite changes observable stamp outcomes when a non-contact limb failed bake verification — P0 goldens may need a deliberate, documented update at P2. Bug-fix surfacing, not drift; auditors should expect that fixture diff.
2. **V1 means P4 adds behavior** (flag starts being written). Tests asserting `straightIntentDropped == false` unconditionally were passing vacuously; expect P0 fixture updates here too.
3. **P5 semantic shift:** identity-keyed smoothing (per-pose-instance memory) becomes frame-chain history. Sequential same-pose playback preserved; interleaved multi-instance replay is not, and cannot be under R10. Any test relying on interleaved smoothing encodes the violated architecture and must be consciously rewritten — flagged for audit, not routed around.
4. **P10 depends on SkeletonDefinition exposing anatomical forward.** If no such property exists, the phase adds one (definition-level configuration — the R13-designated owner). If judged a definition-format change, stop-and-flag rather than proceed silently.
5. **Possible R-rule friction:** R3 vs Head-Target Resolution writing neck/head locals — mitigated: `SupportPoint` enum contains no head/neck member (`SupportPoint.kt:6–18+`), so a settled contact can never be the head. Residual risk only if a future support point is added; R15(i) amendment path covers that.
6. **P3 placement decision** (internal carrier section) resolved toward the carrier because R11 routes mutable inter-subsystem state through it; reviewers should audit that it never leaks into Published Pose State reads (clear-at-publish decision).
7. Open items pinned inline rather than guessed: exact bend-fallback branch locus in both `bakeIkLimb` bodies (P4); `BasePose`'s definition-access route (P10).

## Explicit non-goals

- **No performance tuning or profiling.** Deferred to a separate later pass, only after P0–P11 are complete and green — per the RFC's Scope line (architecture defines WHAT/WHO, never HOW-fast).
- No renaming/reordering of anything the RFC froze; comment-level vocabulary alignment only opportunistically within functions already touched.
- No new subsystems, state categories, solvers, or rendering paths; P3's internal carrier section represents an already-frozen architectural object, flagged as such.
- No algorithm changes inside operations the RFC owns conceptually (Two-Bone IK Solve math, CCD internals) — bounds stay tuning, per R12.

---

## Phase 0 — Baseline & characterization harness

- **RFC citations:** Enables every later phase. Process mandate traced to AGENTS.md *Current Compile/Test Policy* ("green baseline", "compile-first"); characterization protects frozen semantics named in §3 (state categories) and §6 (execution order) during implementation. Flagged honestly: this phase enforces no single R-rule; it is the safety net the ground rules require.
- **Dependency:** None. First phase.
- **Current state:** Test baseline runs via `./gradlew :app:testDebugUnitTest`. No test locks whole-frame transform output of the pipeline for representative pose families (contact pose, posture-driven pose, plain pose).
- **Target state:** New file `app/src/test/java/com/monkfitness/app/arch/RuntimeArchitectureBaselineTest.kt`: golden-transform fixtures for three frames driven through `SkeletonPipeline.produceFrame` (contact pose via `BasePushUpPose`, posture-driven via `BaseSquatPose`, contact-less custom), asserting pelvis/hand/foot world transforms and the eight stamp values byte-for-byte.
- **Enforcement mechanism:** The golden fixtures themselves — any later phase that silently changes frozen behavior fails here.
- **Test plan:** This phase is tests. Acceptance: suite green on unmodified source.
- **Open questions / implementation decisions:** Fixture serialization format (inline expected floats vs resource file) — IMPLEMENTATION DECISION, propose inline constants; RFC is silent (it defines no storage).

## Phase 1 — R8: Runtime Context Injection single-point

- **RFC citations:** §5 R8; §3.1 Frame Context constituency; §6 Phase 0.5.
- **Dependency:** P0. Nothing else may assert context immutability until this lands.
- **Current state:** Injection duplicated inline in two overloads: `SkeletonPipeline.produceFrame(builtPose, environment, supportedPoints)` writes `builtPose.environment`/`supportedPoints` (`SkeletonPipeline.kt:78–80`); builder overload writes from `pose.metadata.environment` / `pose.metadata.support.contacts` (`:97–103`). Both immediately precede `runStages`.
- **Target state:** Extract `private fun injectRuntimeContext(pose: SkeletonPose, environment: EnvironmentDefinition, supportedPoints: Set<SupportPoint>)` in `SkeletonPipeline.kt`; both overloads call it exactly once immediately before `runStages`. No behavior change.
- **Enforcement mechanism:** Debug builds (`BuildConfig.DEBUG`-gated `check()`): snapshot injected context after injection, compare after each stage inside `runStages`; throw `IllegalStateException("R8 violation: …")` on any post-injection write. Release skips the copy.
- **Test plan:** New `RuntimeContextInjectionTest`: drive a frame; assert context snapshot equality across `runStages` — fails if any stage ever writes.
- **Open questions / implementation decisions:** (1) Snapshot mechanics — field-copy comparison in debug only (RFC mandates one writer/freeze boundary, not the mechanism). (2) Support Declaration derivation from Contact Declarations (builder-path loop `:100–102`) counts as injection-time derivation — kept inside the extracted function per §4.1 Group B producer text.

## Phase 2 — R4/R6: Validation Stamp merge centralization + strengthen-only

- **RFC citations:** §5 R4, R6; §4.4 table (merge rules max/OR/AND).
- **Dependency:** P0. Must precede P4 and P8.
- **Current state:** Merge logic hand-rolled and duplicated: `BasePose.kt:288–289` & `:435–436` (clamp, max), `:295` & `:439` (verified, AND); `ConstraintSolver.kt:231–232` (deltas reset), `:236` **defect V2**: unconditional `boneLengthsVerified = true` can erase a primary `false` for non-contact limbs; `:370` ANDs only contact-limb re-bakes.
- **Target state:** Add `object ValidationStampMerge { clamp(old, reading)=max; verified(old, reading)=old && reading; dropped(old, dropped)=old || dropped }` (new file `animation/ValidationStampMerge.kt` — decision below). Replace raw assignments at the five sites. Rewrite solver logic merge-once: capture primary value before settlement into a local, AND contact-limb findings locally, assign merged result once at settlement end — eliminating the `= true` reset.
- **Enforcement mechanism:** Helpers carry debug-only `check(new strengthens old per rule)`; whitelist test asserts stamp fields are written only via merge sites.
- **Test plan:** New `ValidationStampMergeTest`: (a) primary `verified=false` + successful solver pass ⇒ stays `false` (**fails on current code** — defect test); (b) clamp monotonicity; (c) dropped OR monotonicity; (d) sole-producer overwrite unchanged for deltas.
- **Open questions / implementation decisions:** Helper location (new file vs `SkeletonMath`) — propose new file, keeping `SkeletonMath` algorithm-only. "Strengthen" for the AND flag (further-restricting) is fixed by §4.4; the merge-once local pattern is an implementation choice.

## Phase 3 — §3.2/§4.3: Settlement Result surfaced on Settled Geometry

- **RFC citations:** §3.2 (Settlement Result paragraph), §4.3 register row, §5 R3, §6 Phase 2 exit / boundary contract.
- **Dependency:** P0. Must precede P7 and P8.
- **Current state:** Does not exist yet (V10).
- **Target state:** Internal, unpublished section on the carrier: in `PoseDefinition.kt` add `internal var settlementResult: SettlementInfo?` with `SettlementInfo(settledRootWorld: Vector3, settledContactJoints: List<Joint>, conflictOutcomeJoint: Joint?)` (structure = IMPLEMENTATION DECISION; RFC defines membership, not layout). Populate at end of `ConstraintSolver.solve` (settled pelvis world position, honored contact joints, precedence winner from `applyRootDelta` — anchor `ConstraintSolver.kt:316–321`). `SkeletonPoseFinalizer.finalize` consumes read-only; excluded from publication (clear-at-publish — decision below).
- **Enforcement mechanism:** Debug `check()` at `finalize` entry: non-null iff solver ran (pipeline knows via `runStages`).
- **Test plan:** Extend baseline: contact-pose frame ⇒ `settlementResult.settledRootWorld` equals published pelvis world transform; null after contact-less CUSTOM solve skip.
- **Open questions / implementation decisions:** (1) Storage shape/location. (2) Non-leakage mechanism: propose clear-at-publish (keeps Published Pose State contents exactly transforms+stamps per §3.3). (3) Minimal conflict-outcome payload (winner-joint only).

## Phase 4 — R5: Single-active-limb-solver + Straight-Intent-Dropped producer

- **RFC citations:** §5 R5; §4.4 Straight-Intent-Dropped Flag row; §4.2 Straight-Limb Fallback; §6 Phase 1.
- **Dependency:** P0, P2. Before P8.
- **Current state:** Two implementations, parity-documented (`IkStage.kt:16–25`); gate `IK_STAGE_ACTIVE=false` (`IkStage.kt:38,54`); bake paths: member `BasePose.bakeIkLimb` (`BasePose.kt:233`) and package-level `bakeIkLimb` (`BasePose.kt:396`), both writing clamp/verified identically. **Defect V1:** flag has no writer anywhere. IkStage zero-pole default (`:87–90`) matches bake (`:273–278`) — V5 satisfied. **Superseded at P12 (WP-I, merged #225):** the deployed configuration is now state 3 (`IK_STAGE_ACTIVE=true`, engine stage = Active Limb Solver) and the authoring bake realizes only while the stage is disabled — see §12.0's deployed-state note and §12.7. The paragraph above is the phase-start record, kept as written.
- **Target state:** Write the flag at each fallback decision point: member `bakeIkLimb` and package-level `bakeIkLimb` where the straight path degenerates to bend (locus pinned by reading the `straight` branch, `BasePose.kt:247`, `:413` region), `IkStage.apply` equivalently, and OR-strengthen in `ConstraintSolver`'s contact re-bake if it performs a fallback — via `ValidationStampMerge.dropped`. Pipeline-level debug counter asserting exactly one limb-solver implementation executed per frame.
- **Enforcement mechanism:** Debug counter in `runStages`; `check(count == 1 || count == 0 && stage skipped)` per R5.
- **Test plan:** New `StraightIntentFallbackTest`: (a) straight intent unreachable-in-plane ⇒ flag `true` (**fails today** — always false); (b) reachable ⇒ `false`; (c) IkStage-enabled parity between implementations.
- **Open questions / implementation decisions:** Exact "dropped" decision locus per path (RFC fixes the semantic — fallback executed ⇒ dropped — not the code locus).

## Phase 5 — R10: Remove solver cross-frame memory; pipeline-supplied history

- **RFC citations:** §5 R10; §4.5 Frame History row; §4.2 Inter-Frame Smoothing; §3 scratch-isolation rule.
- **Dependency:** P0. Should precede P6.
- **Current state:** V3 — `lastSolvedRoot: WeakHashMap<SkeletonPose, Vector3>` (`ConstraintSolver.kt:95`), easing read `:267` under `SMOOTH_GAIN`, write `:414–418`. Pipeline history maintained only on validated path (`SkeletonPipeline.kt:162–163`).
- **Target state:** Delete `lastSolvedRoot`; `solve(pose, definition)` → `solve(pose, definition, previousRootWorld: Vector3?)`; easing uses the parameter. `SkeletonPipeline` maintains `previous/prePrevious` on both paths (move `:162–163` update into shared `commitHistory(finalized)` called by both entry points) and supplies `previous` root world position. `resetHistory()` unchanged.
- **Enforcement mechanism:** Compile-level absence of the map; debug `check` distinguishing "first frame" from "forgot to wire".
- **Test plan:** New `InterFrameSmoothingTest`: (a) sequential jittered frames ⇒ frame-2 root eased toward frame-1; (b) identity-independence: distinct `SkeletonPose` instances, identical inputs+histories ⇒ identical outputs (impossible under old cache). Golden-tolerance review per Risk 3.
- **Open questions / implementation decisions:** History-update location (RFC R10 mandates ownership, not update timing) — shared `commitHistory` proposal.

## Phase 6 — R2: Root-authority enforcement (full window)

- **RFC citations:** §5 R2 ("from the end of build" — includes Phase 1); §3 rotation-space rule root-authority sentence; §6 phase boundaries.
- **Dependency:** P1, P5.
- **Current state:** Compliance by convention only: `seedRootFromPostureIntent` preserves authored orientation (`ConstraintSolver.kt:226–229`); Finalizer never touches pelvis (grep: no pelvis writes in `SkeletonPoseFinalizer.kt`). No runtime guard. Prior revision of this plan covered only [post-injection → post-settlement] and [settlement → publish], leaving Phase 1 unguarded.
- **Target state:** In `SkeletonPipeline.runStages`, four capture points on the pelvis transform:
  1. **A — post-injection** (end of build; last lawful authoring write + context write done).
  2. **Check 1 — post-Phase-1** (`IkStage.apply` returns): pelvis bit-identical to A — covers the engine-side-limb-stage-active configuration.
  3. **B — post-Phase-2** (`ConstraintSolver.solve` returns): recorded as the settled root; Solver was the authorized mover; no equality claim vs A.
  4. **Check 2 — post-Finalize/post-publish**: pelvis bit-identical to B.
  Branch handling: when the solve is skipped (contact-less CUSTOM, `SkeletonPipeline.kt:129–131`), no authorized mover exists after build — Checks 1 and 2 then compare against **A** directly (A must survive to publish untouched). New debug util `arch/PhaseBoundaryAsserts.kt` introduced here (reused by P7).
- **Enforcement mechanism:** `BuildConfig.DEBUG`-gated `check()`; throw naming the violating phase window. Exact float comparison (transforms bit-copied unless mutated; legitimate movers are known).
- **Test plan:** `RootAuthorityTest`: (a) post-finalize pelvis perturbation ⇒ throws at Check 2; (b) clean frames pass; (c) with `IK_STAGE_ACTIVE=true`, injected pelvis perturbation inside the limb path ⇒ throws at Check 1; (d) solver-skip path asserts A→publish identity.
- **Open questions / implementation decisions:** Exact-match tolerance (proposed) — RFC dictates authority, not detection granularity.

## Phase 7 — R3: Settled-Contact Guarantee enforcement

- **RFC citations:** §5 R3, R7; §3.2 Settlement Result; §6 re-entry rule.
- **Dependency:** P3, P6 (shared util pattern).
- **Current state:** Guarantee holds by construction; nothing detects violation. Risk-reducing fact: `SupportPoint` enum has no head/neck member (`SupportPoint.kt:6–18+`), so Head-Target Resolution structurally cannot touch a declared support point.
- **Target state:** In `SkeletonPipeline.runStages` after `solve`: snapshot world positions of `settlementResult.settledContactJoints` end-effectors; after `finalize`: assert identical (debug). On violation throw naming the operation window.
- **Enforcement mechanism:** As above; loud in debug, free in release.
- **Test plan:** `SettledContactGuaranteeTest`: pose where a declared Head Target would displace a hand-planted chain if guarantees were ignored; assert hand world position bit-identical pre/post finalize, intent application skipped per R3's sanctioned-skip clause.
- **Open questions / implementation decisions:** Whole-phase check first (cheaper, satisfies R3's "no later subsystem"); per-operation wrapping only on triage. RFC doesn't dictate instrumentation granularity.

## Phase 8 — §6 Phase 4 / §3.3: Publish-ordering structuralization

- **RFC citations:** §6 Phase 4 (fixed internal order), §3.3 lifetime (immutability onset), §4.4 note (write phases vs readability).
- **Dependency:** P2, P3, P4. Explicitly not P7 (see dependency table).
- **Current state:** Order already correct in `finalize()`: derivation/flatten calls (`SkeletonPoseFinalizer.kt:156,204,242,300,358`) → `applyValidationStamps(pose)` (`:431`, near return `~:420–424`) → `return outputPose`. Nothing marks completion or forbids post-return mutation.
- **Target state:** Extract explicit tail `private fun publish(outputPose: SkeletonPose): SkeletonPose` containing final flatten-completion check + `applyValidationStamps`; internal debug marker `published=true` at its end; guard at public entries.
- **Enforcement mechanism:** Marker + guards make "stamp writes after publish" throw in debug. **IMPLEMENTATION DECISION (not dictated by §6 Phase 4's literal text):** the single-shot guard — second `finalize` call on the same finalizer+pose throws — goes beyond the frozen rule, which fixes internal write order and immutability onset but says nothing about re-entry. Rationale: `outputPose` is a reused private buffer (`SkeletonPoseFinalizer.kt:18`), so silent re-entry would corrupt Published Pose State contents, violating §3.3's immutable-after-publish semantics in practice if not in letter. The guard is the enforcement mechanism *for §3.3*, labeled here rather than passed off as Phase-4 text. Release builds unaffected.
- **Test plan:** `PublishOrderTest`: (a) all stamps present and merged on returned pose; (b) second `finalize` throws in debug; (c) golden fixtures unchanged (order refactor behavior-neutral).
- **Open questions / implementation decisions:** Marker storage — finalizer-local (outputPose must carry exactly Published Pose State contents per §3.3).

## Phase 9 — R9 (+R15 observer clause): Observer isolation lock-in

- **RFC citations:** §5 R9, R15(ii); §3.3 lifetime consumers; §8 Validator/Rendering blocks.
- **Dependency:** P8.
- **Current state:** Compliant by inspection: renderers contain zero mutating lines (V6); validator consumes via parameters (`SkeletonPipeline.kt:152–160`). No regression net.
- **Target state:** Test-only. New `ObserverIsolationTest`: deep-compare Finalized Pose (transforms + stamps — the entire category contents per §3.3) before vs after `validator.validate(...)` and before vs after projection; covers a second registered observer per R15(ii).
- **Enforcement mechanism:** The equality test (API shape already prevents writes).
- **Test plan:** As stated. Fails if any observer mutates.
- **Open questions / implementation decisions:** Deep-compare scope = transforms + stamps. None further.

## Phase 10 — R13: Defaults ownership

- **RFC citations:** §5 R13; §4.2 Default Pole; §1 Spine Intent row.
- **Dependency:** P0 only.
- **Current state:** Default Pole engine-owned in both implementations (V5) — needs only a parity lock-in test. **Spine Intent axis violates R13:** `buildSpineCurve(..., axis: Vector3 = axisZ)` (`BasePose.kt:170–175`), hardcoded `axisZ` (`:17`).
- **Target state:** Resolve the spine default from the authoring-available definition: `PoseContext` carries `definition` (`PoseContext.kt:3–7`); thread the anatomical forward axis from `SkeletonDefinition` into `buildSpineCurve`'s default. Keep parameter for explicit overrides; change only the default's origin.
- **Enforcement mechanism:** Unit test (defaults are values).
- **Test plan:** `SpineDefaultAxisTest`: definition with non-identity forward axis ⇒ axis-less authoring aligns to definition axis; explicit axis still wins. Golden fixtures validate no drift for standard definitions (verify forward equals current `axisZ`; if not byte-equal, reconcile per Risks 4).
- **Open questions / implementation decisions:** (1) `BasePose`'s definition-access route at curve-build time — read during implementation; flagged. (2) Which `SkeletonDefinition` property expresses anatomical forward — if none exists, this phase adds a definition-level accessor (configuration surface, the R13-mandated owner); flagged for audit attention.

## Phase 11 — R11/R14: Carrier transfer-chain & pipeline-lifetime compliance verification

- **RFC citations:** §5 R11 (transfer chain + External bypass), R14; §4.5 carrier/pipeline rows; §8 Pipeline block.
- **Dependency:** Last — verifies the assembled whole.
- **Current state:** Compliant by construction: pipeline sole caller of Solver/Finalizer (`SkeletonPoseFinalizer.kt:332–338` doc + `runStages`); creators own instances (`SkeletonRenderer.kt:37`, `SkeletonSnapshotRenderer.kt:18`) — V8. External definitions bypass the carrier (constructor/parameter paths verified).
- **Target state:** Test-only `CarrierTransferComplianceTest`: (a) returned Finalized Pose is a distinct instance from the input carrier (`outputPose` buffer, `SkeletonPoseFinalizer.kt:18,349`); (b) two renderers with separate pipelines produce independent frames; (c) External objects never appear in carrier copies (guards P3 non-leakage from the other side).
- **Enforcement mechanism:** The compliance tests; optional CI grep gate for forbidden patterns (`WeakHashMap<SkeletonPose`, global singletons) — decision below.
- **Test plan:** As stated.
- **Open questions / implementation decisions:** CI grep gate — propose yes; RFC silent on tooling.

## Phase 12 — R5 Activation / Limb-Solver Ownership Transition

**Status:** Ratified by the architecture owner 2026-09-06 (P12 adjudication). **IMPLEMENTED AND MERGED** — PR #225 (`phase12-r5-activation`, merge `914a6f6`, work packages WP-A … WP-I); §12.0 state 3 is the deployed production default, so the phase's objective is met. **The §12.7 flag-lifecycle verification is CLOSED** (2026-09-12, `arch.SingleActiveSolverLifecycleTest`; counterfactually RED before green — see §12.7). **One item is recorded OPEN and deliberately not decided here:** the §12.7 configuration-ownership question (R14 creator-owned knob vs the landed single declared surface), with options, in §12.7 and in the stabilization tracker. The frozen RFC is NOT amended by this phase and is NOT wrong: §5 R5 and §6 Phase 0/1 already define the activation end-state; the execution plan previously contained no phase owning the journey to it. P12 closes that plan gap.

### 12.0 Normative vocabulary — the three states this phase exists to separate

> **Deployed-state note (2026-09-12).** The three states below are the vocabulary P12 was written in. **State 3 is the deployed production default** since WP-I (merged #225): `IK_STAGE_ACTIVE=true` in `animation/IkStage.kt` (the declaration documented in §12.7), the engine stage is the sole Active Limb Solver, and `IK_STAGE_ACTIVE=false` is the supported *non-deployed* configuration R5 keeps selectable for differential work. States 1 and 2 are historical descriptions of the pre-activation codebase, kept because every P12 work package cites them.

1. **CURRENT PRODUCTION STATE** — `IK_STAGE_ACTIVE=false` (`IkStage.kt:38`; zero production writes to the flag — sweep-verified; sole production read `:54`). The authoring bake is the active production limb solver; `IkStage` is a dead no-op. R5's per-configuration letter holds *only* because this configuration is the deployed one.
2. **P4 RUNTIME VALIDATION STATE** — after P4 lands: the runtime-window counter + `check(count == 1 || (count == 0 && stage skipped))` proves no *second Phase-1 runtime solver* can enter `runStages`; the config/static audit proves the flag remains test-only. **Neither instrument proves authoring-vs-stage exclusivity** — flag-ON still double-solves (the authoring bake is ungated: `BasePose.kt:308`, `BaseValidationPose.kt:288`).
3. **FUTURE R5 ACTIVATION STATE** — `IK_STAGE_ACTIVE=true` is a *valid production configuration*: authoring no longer performs limb realization for any migrated path, all `limbTargets` are realized by `IkStage`, exactly one implementation owns limb realization, and the invariant is enforced in the strengthened mode (§12.7). **Only P12 may move the codebase from state 2 to state 3.**

### 12.1 RFC citations and contract position

§5 R5 (full activation of the frozen responsibility set), §4.2 rows (Two-Bone IK Solve / Straight-Limb Fallback / Bone-Length Invariant / Default Pole — all "Active Limb Solver"), §6 Phase 0 ("while it is the Active Limb Solver") and Phase 1 ("If the engine-side IK stage is enabled"), §4.1 owner-kind sentence ("exactly one of its two implementations is instantiated per configuration"), A9 (flag demoted to rollout mechanism).

### 12.2 Dependencies (mirrors the dependency table)

Hard-deps **P4, P5, P6, P7, P8**. Parallel with **P9/P10**. Prerequisite of **P11**.
- P4: producers, `IKResult` scratch, runtime-window counter and config audit are the substrate P12 strengthens (§12.7).
- P5: the solver signature must be final (`solve(pose, definition, previousRootWorld?)`) before the re-bake path is re-owned — avoids rebase collision on the contact re-bake loop.
- P6: the flag-ON pelvis window (Check 1; plan §P6 test (c), which already runs `IK_STAGE_ACTIVE=true` through the pipeline) must be green pre-flip and is a P12 acceptance gate.
- P7: the Settled-Contact Guarantee harness must certify contacts pre-flip so §12.9's equivalence proof can detect guarantee-breaking.
- P8: publication order fixed before the realization path swaps.

### 12.3 Exact current blockers (source-verified at `d1d8962`; all must clear before any flip)

- **B-1 Authoring-time consumers of solved results.** `BaseHipFlexorPose.solveFrontLeg` (`:105–110`) returns the bake `IKResult`; `CouchStretchPose:58–61` and `HalfKneelingStretchPose:52–55` derive arm targets from `legFIK.joint` **inside build**. Under activation these break (the solved knee does not exist yet at Phase 0).
- **B-2 Direct-`solveIK` bypass family** (10 files: `LatStretchPose:94–113`, `DeadBugPose:73–92`, `MountainClimberPose:91–125`, `LegRaisePose:59–69`, `ReverseSnowAngelPose:81–114`, `GluteBridgePose:111–139`, `PelvicTiltPose:109+`, `SupermanPose`, `CatCowPose:61–84`, `BaseThoracicPose:139`): limb solving outside every registered implementation, with no `limbTargets`/stamp/contact registration; some consume `result.joint/end` directly (`CatCowPose:81–84` `setJoint` writes).
- **B-3 Constraint/length recovery gap in `IkStage`.** `WorldTarget` (`PoseDefinition.kt:103–109`) carries no L1/L2/constraint; `IkStage.kt:69–73` recovers via an arm/leg heuristic reading `definition.armIKConstraint`/`legIKConstraint`. Authoring call sites pass **per-bake constraints** — the validation family uses opted-in full-extension variants (`BaseValidationPose.kt:239–243` `armStraightConstraint`/`legStraightConstraint`, e.g. `MiddleSplitPose:77–78`). Today contact limbs are masked by the `ConstraintSolver` re-bake (`ContactSpec` carries the authored constraint); non-contact straight limbs are masked only because targets happen to sit inside both bands. Parity that depends on coincidence is not an activation criterion.
- **B-4 Third bake implementation.** `BaseValidationPose.kt:~250–330` duplicates authoring solving; validation poses are the *only* production `straight = true` authors and their KDoc semantics (e.g. `MiddleSplitPose:23–31`: the probe expects the *authoring* fallback to produce the bent limb) are written against the current state. VALIDATION.md's probe contract (the pose says "I want a straight limb here, show me what the runtime does") must survive activation with the *activated runtime* as the observed solver.
- **B-5 `IkStage.kt:33` flip criterion** ("Flip it on after the `IkStageTest` byte-identity check is green") is under-conditioned (§12.10).

### 12.4 Intent/carrier changes required (design decisions P12 must resolve)

- **Extend the Limb Target** so `IkStage` can realize every limb losslessly: add `length1`, `length2`, and the per-limb `IKConstraint` reference (or a constraint-selector token) to `WorldTarget`; the stage then uses recovered values instead of the `isArm` heuristic (clears B-3). This is an Intent State format extension inside a carrier R5 already names — RFC §4.1 fixes the row's ownership/consumers, not its field list — but per this plan's amendment discipline (§Execution log binding decisions) it is raised as a **separate clarification proposal to weter11**, never bundled into an implementation PR.
- **Declare-order solve inputs:** for B-1/B-2, the intent model needs either (a) pose-authored targets expressed against *declared* joints (root-relative/heading primitives that already exist: `setHeading`, `jointIntents`) so no Phase-1 output is needed at authoring time, or (b) a sanctioned in-build "planning solve" that is **not** limb realization (no node writes; results consumed only to compose targets). Option (b) touches R5's semantics and requires the same clarification proposal. P12 picks the recipe per family and documents it.
- No new state categories; carrier fields stay outside `copyFrom` per the P3/P4 suppression pattern.

### 12.5 Migration of authoring-dependent poses (B-1 + capture list)

Per-family work items: the hip-flexor chain (`CouchStretchPose`, `HalfKneelingStretchPose` via `BaseHipFlexorPose`) — rewrite `solveArmsOnKnee`'s dependency to declared-intent terms (§12.4a) or the sanctioned planning solve (§12.4b); then the capture-only families: `BasePushUpPose:241–242` (documented bookkeeping-only — verify zero effect, then drop the capture) and the `reset()`-family poses that capture without downstream use (`ArmCirclesPose:100–118`, `WallSlidesPose:109–126`, `HipCarsPose:95–115`, `FacePullPose:96–123`, `KettlebellSwingPose:81–93`, `ScapularRetractionPose:96–113`, `BurpeePose:193–200`). Acceptance per family: while flag-OFF, node-write suppression is added **only when flag-ON** (the bake keeps running its registration/stamp effects — `limbTargets.add`, `contacts.add`, stamp merges — so contacts and stamp producers behave identically), proven by §12.9's harness.

### 12.6 Migration of the direct-`solveIK` bypass family

Route each of the 10 B-2 files through the package-level `bakeIkLimb` (exactly the gap `BasePose.kt:410–419` KDoc claims closed; the P4 ruling "no H2 migration in P4" deferred it — it is in-scope here because activation cannot proceed while these limbs exist in neither implementation). Sequence per file: migrate to bake (expected byte-identical for identity-parent cases; the helpers' clamp/verified stamps newly *populate* where they were silent — validator-visible diagnostics must be diffed, not assumed), add `limbTargets` coverage, then fold into §12.9's equivalence proof. `BaseThoracicPose:139` and CatCow-style `setJoint` consumers need the §12.4 planning-solve decision first.

### 12.7 Transition of `IK_STAGE_ACTIVE`; post-activation enforcement of the single-active-solver invariant

- **Flag lifecycle:** the declaration moves from "test-only rollout" to an engine-supplied configuration input (constructor/definition-level knob supplied by the creator per R14; still zero *silent* production writes). The P4 config audit is **retargeted, not deleted**: from "no production write exists" to "writes occur only through the declared configuration surface."
- **Strengthened mode (replaces P4's runtime-window claim):** (a) the bake performs its limb realization **only when the stage is disabled** (`IK_STAGE_ACTIVE` gates the node-realization effect at the bake, not the registration effects); (b) the P4 carrier counter increments at *both* Phase-1-realization sites (bake realization branch + stage), so the plan formula becomes exactly `check(count == 1)` in both configurations — the `0 && skipped` disjunct retires; (c) `runStages` keeps the post-chain check (stage window); (d) the static audit extends: no `middleNode`/`endNode` `localPosition` writes outside the two registered implementations. Enforcement honesty note for the record: this makes the invariant mechanically true in **both** configurations — precisely what §12.0 state 3 requires.
- **Delivered state machine (measured on `main` @ `3f6733d`, 2026-09-12; behaviour proven on the production path by `arch.SingleActiveSolverLifecycleTest` — counterfactually RED before green, see the evidence note at the end of this section).** One build cycle, one configuration, one realizing implementation:
  1. **Build window** (`pose.build(context)`): `IntentBuilder.reset()` opens the cycle (`buildCycleToken`), then each registered bake runs its REGISTRATION effects in BOTH configurations — the §1.1 Limb Target (with target, pole, straight intent, declared lengths and constraint) and the Contact Declaration — plus the F2 build-window bookkeeping (re-arm of `boneLengthsVerified` / `straightIntentDropped`, keyed on the frames-updated marker). Only the REALIZATION block (solve + stamp folds + limb node writes) sits behind the configuration gate. Consequence: the straight-intent reading always describes the build that produced it and never leaks forward from an earlier one.
  2. **Engine window** (`SkeletonPipeline.runStages` → `IkStage.apply`): with `IK_STAGE_ACTIVE=true` the stage instantiates its window once per frame past its gate, decodes each declared Limb Target losslessly, realizes each declared limb exactly once and registers per-execution evidence on the carrier; with the stage disabled it returns above the window, contributing no evidence and writing no limb geometry. The realized-limb set must equal the declared set — no more, no less.
  3. **Enforcement and reset** (same function, after the Finalizer): `check(limbSolverExecutions == 1 || (== 0 && !IK_STAGE_ACTIVE))` and `check(limbDuplicateRealizations == 0)`, then the per-frame evidence reset. Both checks read EXECUTION evidence; neither compares geometry, positions, floats or stamps.
  The authoring and engine windows are flag-mutually-exclusive, so the shapes that can produce two realizations of one limb in one cycle are (i) a realization site whose gate is missing and (ii) a second stage call; both are rejected — demonstrated by `SingleActiveSolverLifecycleTest.bakeRealizationAndStageRealizationInOneCycleIsRejected`, whose premise asserts that the two single-realization frames are raw-bit identical (a geometry-only test cannot see the violation) and whose RED gate is the disabled enforcement (`if (BuildConfig.DEBUG && false)`) — with the enforcement off, exactly that test fails ("no violation raised").
- **IMPLEMENTATION DECISION (§12.7b formula — recorded, not silently resolved):** the delivered check KEEPS the `count == 0 && !IK_STAGE_ACTIVE` disjunct instead of the literal `check(count == 1)` this plan predicted for both configurations. Reason: while the stage is disabled the counter is the *authoring* window, and a frame legitimately produced without a rebuild (a carrier re-produced by the renderer path — `SkeletonRenderer.kt:55`, `SkeletonSnapshotRenderer.kt:83`) runs no authoring realization at all, so `count == 0` is that frame's truthful reading rather than a violation. In the deployed configuration the disjunct is unreachable — the stage always counts its window past its gate — so the enforced formula IS exactly `count == 1`, as §12.7b requires. Retiring the disjunct outright would require counting an authoring window that no implementation opened (a fabricated reading), so it stays and is documented at the check site.
- **RECORDED, UNRESOLVED — configuration ownership (§12.7 first bullet vs the landed declaration).** The plan requires the declaration to "move from test-only rollout to an engine-supplied configuration input (constructor/definition-level knob supplied by the creator per R14)". What is implemented is a single file-level `var` with one declaration, zero production writes, reads confined to the four realization-decision files and no environment/system-property selector — statically and at runtime audited (`RuntimeSolverOwnershipAuditTest`, `ActivationGateTest.productionConfigurationIsStateThree`). The R14 property itself is NOT reached: a creator-owned knob has to reach the authoring bakes, which run inside pose-authored `build()` and today receive no engine configuration; supplying it means adding a configuration channel to the authoring path (definition-level property threaded through the bakes, or a `PoseContext` extension) — exactly the §12.4 intent/carrier class of change this plan requires be raised as a SEPARATE clarification proposal, never bundled into an implementation change. Options for the architecture owner: **(A)** raise the §12.4 proposal and implement the definition-level knob (largest diff: every registered authoring call site + the package-level bake signature); **(B)** accept the landed single declared surface as the engine-supplied configuration input, with the retargeted audit standing in for R14, and close the item; **(C)** carry it as an open debt-ledger entry. This plan records it OPEN and does not decide it.
- **Verification evidence (2026-09-12, ad-hoc local, `main` @ `3f6733d` + the new suite):** `SingleActiveSolverLifecycleTest` 7 tests / 0F, added to the 113-class / 518-test baseline; three counterfactual RED gates executed against the defective shapes before green was accepted — (1) §12.7a realization gate removed from the three registered bakes ⇒ 7/7 tests FAIL (the enforcement reports `windows executed this frame = 2`), (2) the pipeline's enforcement disabled ⇒ exactly the double-realization trap FAILS ("no violation raised"), (3) the F2 re-arm gated off (the WP-F defect shape) ⇒ `droppedReadingIsReArmedByTheNextBuild` + `bentOnlyBuildDoesNotInheritAPreviousDrop` FAIL. No production behaviour changed (the only production edit in this task is the KDoc correction in `IkStage.kt`).

### 12.8 Tests that become INVALID at activation (declared up front, not discovered late)

1. `IkStageTest.productionPosesByteIdenticalStageOnVsOff` / `contactPosesByteIdenticalStageOnVsOff` (`:88–125`): post-flip, flag-OFF *is* the legacy config and flag-ON *is* the realized config — the two runs differ only by authoring realization. Byte-equality there is tautological for migrated families and **vacuously green while B-2 limbs solve in neither config**. Retire/replace with §12.9's proof. **EXECUTED at WP-I:** both probes deleted (no `@Ignore`, no `assume`, no flag-conditional assertion) and replaced by §12.9's corpus + the §12.7 configuration-surface audit; `ActivationEquivalenceTest.retiredAndStateTwoOnlyProbesAreExplicitlyClassified` pins the retirement so a state-2 guarantee cannot return unnoticed.
2. `IkStageTest.flagDefaultsFalse` (`:69–72`): a state-2 guarantee; replaced by the §12.7 configuration-surface test. **EXECUTED at WP-I:** deleted; the deployed-state claim now lives in exactly two authoritative places — `RuntimeSolverOwnershipAuditTest` (the static declaration surface) and `ActivationGateTest.productionConfigurationIsStateThree` (the runtime value + the absence of any environment/system-property channel).
3. `RuntimeArchitectureBaselineTest` goldens: **must stay byte-identical across the flip** for the three fixtures (all in the migrated or bypass-migrated set; any diff = a realization change → stop-and-adjudicate with P12 named as the responsible phase per the golden-update policy — never a silent update).
4. P4's config audit (retargeted per §12.7) and P4's runtime-window KDoc (updated from "one runtime solver" to full R5 mode). **EXECUTED at WP-G/WP-I:** the retargeted audit is live in `RuntimeSolverOwnershipAuditTest`, and the strengthened-mode contract is documented at the check site in `SkeletonPipeline.runStages`.
5. P6 `RootAuthorityTest` (c): remains **valid** and becomes a production-path test; P6 (b)/(d) unchanged.

### 12.9 True-equivalence proof (or recorded deliberate deltas)

- **Cross-configuration golden harness (the heart):** freeze a corpus = {3 baseline fixtures × reps} ∪ {all migrated B-1 families} ∪ {all 10 B-2 files} ∪ {5 validation poses incl. all `straight = true` probes} × progress sweep. Capture every joint world transform + all 8 stamps in **state-2 flag-ON** (= today's double-solve net output) and **state-3 post-flip** (= single-solve). Assert exact equality per the characterization policy. A mismatch is *either* a defect *or* an adjudicated behavior change: adjudicated changes are listed per-case with RFC-rule citation (e.g. a straight probe whose bent-fallback should now surface via `straightIntentDropped=true` instead of being silently re-solved by the stage — the flag P4 wires is the diagnostic that makes this visible, closing the loop on why P4 precedes P12).
- **Counterfactual red-gates** (P2 discipline): every new enforcement test must be proven red on the pre-P12 tree (double-realization injectable → `count == 1` throws) before green means anything; anti-vacuity guards inside every stage-dependent test.
- **Full forced suite** at both configuration gates; CI run on the exact head SHA; no golden touched without a §12.9-adjudicated entry naming P12.

### 12.10 Disposition of `IkStage.kt`'s current flip criterion

`IkStage.kt:28–37` ("Flip it on after the `IkStageTest` byte-identity check is green") is **replaced** — it is a comment, not RFC text, so P12 edits it directly. New criterion, in the KDoc: activation requires ALL of — (i) B-1 consumers eliminated or planning-solve-sanctioned, (ii) B-2 family migrated to registered implementations, (iii) `WorldTarget` carries constraint/lengths (B-3 lossless recovery, no coincidence dependence), (iv) §12.7 strengthened-mode enforcement live, (v) §12.9 equivalence harness green-or-adjudicated, (vi) validation-probe semantics re-certified against the realized path (B-4). Each item names its P12 work-package so the criterion is checkable, not aspirational.

### 12.11 Explicit non-goals of P12

No RFC edits (the activation state is what the frozen R5/§6 already describe; the only carrier/clarification proposals are §12.4's, raised separately). No IK math changes. No validator rule semantics beyond what §12.9's stamp-surfacing exposes. No deletion of either implementation (adjudication B). No merge of anything into `main`.

---

## Audit trail

- Pre-freeze audits: A1–A24 (initial resolution), A25–A33 (clarification pass), freeze-blocker audit (BLK-1..10), post-approval narrow fixes (Frame Context immutability/consumer split; stamp write-timing reconciliation; aggregate-consumer elimination; SkeletonDefinition consumer list source-verified). All in `RFC_RUNTIME_SKELETON_ARCHITECTURE.md` §9 and history.
- This plan: audited and approved with amendments (single dependency table; P6 full-window coverage; P8 IMPLEMENTATION-DECISION label; R1 general-guard scope-narrowing accepted with escalation path via R15(i)).
