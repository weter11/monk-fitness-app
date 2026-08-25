# IMPLEMENTATION PLAN — Runtime Skeleton Architecture

**Implements:** `docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` (frozen; READY FOR ARCHITECTURE FREEZE, audits A1–A33 resolved).
**Status:** In execution. Phases 0–1 complete and merged (PRs #207, #211); each subsequent phase is audited against this plan before it starts. See Execution log below.
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
| P11 R11/R14 compliance | P1–P10 | Verifies the assembled whole |

**P7→P8 resolution: P8 does NOT require P7.** They guard disjoint windows with disjoint mechanisms: P7 asserts Settled Geometry invariants between Phase 2 exit and Phase 3 completion (contacts don't move during finalization); P8 structures Phase 4's internal write order and immutability onset. Neither consumes the other's outputs, and P8's tests are expressible without P7's assertions being live. Practical note (scheduling, not dependency): landing P7 before P8 avoids potential diff churn if a guarantee violation ever forces a Finalizer change.

**Parallel lanes after P0:** {P1, P2, P3, P5, P10} concurrent; then P4 (needs P2), P6 (needs P1+P5), P7 (needs P3+P6); P8 once P2+P3+P4 complete, independent of P6/P7 progress; P9 after P8; P11 closes.

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
- **Current state:** Two implementations, parity-documented (`IkStage.kt:16–25`); gate `IK_STAGE_ACTIVE=false` (`IkStage.kt:38,54`); bake paths: member `BasePose.bakeIkLimb` (`BasePose.kt:233`) and package-level `bakeIkLimb` (`BasePose.kt:396`), both writing clamp/verified identically. **Defect V1:** flag has no writer anywhere. IkStage zero-pole default (`:87–90`) matches bake (`:273–278`) — V5 satisfied.
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

---

## Audit trail

- Pre-freeze audits: A1–A24 (initial resolution), A25–A33 (clarification pass), freeze-blocker audit (BLK-1..10), post-approval narrow fixes (Frame Context immutability/consumer split; stamp write-timing reconciliation; aggregate-consumer elimination; SkeletonDefinition consumer list source-verified). All in `RFC_RUNTIME_SKELETON_ARCHITECTURE.md` §9 and history.
- This plan: audited and approved with amendments (single dependency table; P6 full-window coverage; P8 IMPLEMENTATION-DECISION label; R1 general-guard scope-narrowing accepted with escalation path via R15(i)).
