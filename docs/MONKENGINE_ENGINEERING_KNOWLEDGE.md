# MONKENGINE_ENGINEERING_KNOWLEDGE.md

> **This is not documentation.** This is a brain dump. It is what an engineer who spent a long
> time in this codebase carried in their head. It is written for a future engineer who knows
> *nothing* about MonkEngine. Do not treat it as authoritative spec — treat it as the war stories,
> intuitions, scars, and half-formed heuristics of someone leaving the project. Where it
> contradicts an ACTIVE RFC, the RFC wins. Where it explains *why* an RFC exists, that is the value.
>
> I am writing this on the way out. I am not optimizing it. I am not summarizing. I am emptying
> my head.

---

## 0. The one sentence that explains everything

MonkEngine is a system for describing human movement *truthfully* — and the entire project is a
decade-long argument with itself about who is allowed to compute what. Every RFC, every matrix,
every "ownership" rule, every validator, every "do not do this in a pose" prohibition is a
negotiation in that one argument: **the pose declares intent, the engine realizes it.** If you
remember nothing else, remember that sentence. Everything else is footnote.

---

## 1. Architecture decisions (and the intuition behind them)

### 1.1 The carrier/intent model is the whole point
Poses do not set joint angles. Poses declare *intent* — `jointIntents`, `spineIntent`,
`limbTargets`, `contacts`, `postureIntent`, `extremityArticulations` — and a pipeline
(`SkeletonPipeline.produceFrame`) turns that intent into a solved skeleton via Solver → Finalizer
→ FK. This was not a stylistic choice. It was the only way to stop poses from silently re-implementing
inverse kinematics, which they did, constantly, and which was the original sin of the codebase.

The intuition: a pose author thinks "the hand goes there" and "the pelvis tilts like this." They
must NEVER think "rotate the shoulder joint by 0.4 rad." The moment a pose computes a transform,
it has crossed the line (PRP §4). The line is not a guideline; it is the architecture.

### 1.2 The pipeline is a single owner, not a sequence you can shortcut
`SkeletonPipeline.produceFrame(pose).pose` is the ONLY correct entry point. It runs Solver, then
Finalizer, then FK, in that order, as one coherent stage. Tests used to call
`SkeletonPoseFinalizer(...).finalize(pose)` directly (the "Phase D" era). That bypassed the Solver,
so contact poses were never actually solved — they just looked solved. If you ever feel the urge to
call `finalize()` directly to "just check the pose," don't. Route through the pipeline. The direct
call is a fossil of a time when the Solver didn't exist yet in its current form.

### 1.3 The chest frame reconstruction is a loaded gun
`SkeletonPoseFinalizer.reconstructChestFrame` was, for a long time, the single most dangerous
function in the codebase. It recomputed the chest's world rotation from geometry (spine direction +
shoulder line) and **overwrote** whatever the pose author built via `buildChestTwist` /
`buildChestOrientation` / explicit `chest.localRotation.set(...)`. Worse, it derived the forward axis
purely from the shoulder line, forcing a symmetric-thorax assumption onto every authored pose.

The original bug: the single-arg `Vector3.cross(v)` allocates a NEW vector and never mutates the
scratch buffer, so `tempColX.set(lean).cross(shVec)` was a no-op on `tempColX` → degenerate matrix →
wrong chest world rotation even when neutral. A push-up plank got a ~30° off frame.

The fix was twofold: (1) early-return when `chest.localRotation.angle != 0` — authored thoracic
rotation is the single source of truth; FK already propagated it; (2) use the two-arg `cross(dst)`
overload so the orthonormal frame is actually written for the identity-chest fallback.

Intuition: any function that "re-derives" something an author already set is a trap. The moment you
see "reconstruct X from Y and Z," ask: did the author already decide X? If yes, you are clobbering
their decision. Early-return on "already authored" is almost always the right shape.

### 1.4 Two-segment spine (PELVIS → LUMBAR → CHEST) is recent and intentional
Before Issue E, the spine was a single segment. Now LUMBAR(32) exists as a real joint, `entries.size = 33`,
indices `0..32` contiguous. This was added so trunk-contact poses could have free CHEST/LUMBAR DOFs.
If you add a joint, you MUST keep the index space contiguous or the whole `SkeletonPose` buffer
layout breaks silently. The joints array is not a set; it is an indexing contract.

### 1.5 The solver is the realization owner; the finalizer is the conversion owner
After the M-series cleanup: SOLVER_OWNS_POSTURE, FINALIZER_OWNS_CONVERSION, PIPELINE_ACTIVE. The
Finalizer is the *exclusive* local-transform writer. The `reconstructChestFrame` F1/B5 no-move guard
means the finalizer never displaces hand/foot contacts. These gating flags (`FINALIZER_OWNS_CONVERSION`,
etc.) started as additives you could flip to prove byte-identical behavior, then were retired to
permanent-on because flipping them off changed nothing for production poses (the solver no-ops when
no contacts are declared).

Intuition: when you add a flag to "prove safety then delete it," the proof is the value, and the
deletion is the cleanup. Don't leave the flag "just in case" — that's a fossil (Design Principle:
don't preserve old constructions solely because they predate the architecture).

### 1.6 `preConvertPoles` is a reserved no-op hook, not a function
It exists in `ARCHITECTURE_V2.md` as `preConvertPoles()` in the finalizer phase. It is a no-op. If
you find it "active," someone misunderstood. It is a placeholder for a conversion step that was
decided to never happen. Leave it alone. Touching it is how you re-introduce a behavior the project
spent a year removing.

### 1.7 Validation poses are diagnostic instruments, not dev targets
The single most important cultural rule, and the one most often violated by instinct: a validation
pose (e.g. Middle Split) is a *probe* you point at the runtime to read its true state. If it reads
"straight limb intent flagged," that is a CORRECT reading even if the runtime failed. You fix the
runtime or record the reading. You NEVER retune the pose to make it green.

I cannot stress this enough because it is counter-intuitive and everyone's first instinct is the
opposite. When a validation pose fails, the failure is *information*. The Middle Split diagnostic
was briefly retargeted to full reach to make it pass — that was explicitly reverted and is now an
anti-pattern enshrined in an audit doc (`MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md`). The pose must READ
honestly whether the runtime passes or fails.

### 1.8 Frozen Architecture v2
`ARCHITECTURE_FREEZE.md` freezes the architecture. New work is written against it, not around it.
"Backward compatibility with previous authoring patterns is not a design goal" (BASELINE §5.4). This
is liberating and terrifying: you are allowed to delete old constructions. Most engineers are
terrified of deleting and so they preserve fossils. Don't.

---

## 2. Mistakes that were made (the real ones)

### 2.1 Four compile-broken test files suppressed the entire test module
`ConstraintSolverTest`, `IKLimbHelperTest`, `TrunkFrameTest`, `VerticalPullPosesTest` had compile
errors (missing `kotlin.math` imports, a 3-arg `max` that needed nesting). Because the module
didn't compile, **0 tests ran** and 31 real engine failures stayed invisible for a long time
(PR #134). The lesson that spawned the Compile-first policy: a non-compiling repo is not just broken,
it is *blind*. You cannot see the failures you are shipping. Fix compile errors immediately, always,
before anything else. A compile error is a blocking defect, never backlog.

### 2.2 Poses solved their own IK/FK/balance (the original sin)
For years poses wrote `localRotation.set(...)`, hand-laid `heel/toe` and `palm/knuckles/fingertips`
positions, cancelled inherited torso tilt with `-torsoPitch` style offsets, and positioned the COM
directly. Every one of these is the engine's job. This was cleaned up in W1 (orientation workarounds)
and Branch B (carrier migration). The pattern: a pose "knew about" engine internals. That is always
a defect, even when it looks right.

### 2.3 The chest-frame overwrite (see 1.3)
The single most damaging silent bug. It made authored thoracic rotation, plank flex, lunge pitch,
and validation poses all slightly or massively wrong, and it did so *quietly* — the pose still
"worked," it was just not the pose the author wrote.

### 2.4 Validation thresholds were loosened to pass instead of fixed
S2-era: `PELVIS_INTENT` and `CONTACT_PRESERVED` emitted WARNING (not ERROR), so `rule.isValid` stayed
true and undocumented validation cases stayed red. Loosening a check to make a number green is the
same sin as retuning a pose. Fixed by making them ERROR. The invariant: never lower a principle or a
check to pass.

### 2.5 Stale hard-coded expected positions in tests
Tests asserted pelvis hang at `230` when the actual authored value was `~240`, and step amplitudes
that didn't match authored values. These "failed" not because the engine was wrong but because the
test's expectation was a fossil of an older pose. When a test fails, ask "is the test wrong or is the
code wrong?" — here the test was wrong and the engine was right. Don't auto-fix the code to match a
stale test.

### 2.6 Redesigning from existing code instead of from BPS
The classic mistake: "I'll copy this pose's structure as a template for the new one." That carries
forward every quirk of the old pose. P15/P16 exist because of this. Always redesign from BPS → MSS →
MonkEngine. The code is a record of past compromises, not a source of truth.

---

## 3. Ideas that were abandoned (and why)

### 3.1 The "fix the pose to cover the engine" reflex
Abandoned because it hides engine defects and makes the pose a liar. Replaced by "fix the engine or
record the reading." This is the cultural pivot of the whole project.

### 3.2 Manual balance / manual contacts / manual pelvis stabilization in poses
All abandoned. The engine owns COM, contacts, and spine/pelvis/head/foot/hand reconstruction. Any
pose logic that "holds the pelvis level with a transform" is gone.

### 3.3 `EngineFlags` object and the `finalize` compatibility bridge
Abandoned during cleanup. They existed to let old code call the finalizer directly during migration.
Once the pipeline was the sole owner, they were dead weight. `EngineFlags.FINALIZER_CONSUMES_INTENT`
and `HEAD_TARGET_ENABLED` were removed.

### 3.4 The five deprecated members and the `ExerciseReview` pipeline
Removed. `ExerciseReview` was a validator-adjacent pipeline that authored motion; it violated P6.
Gone.

### 3.5 Branch B as a "migration with steps PR #155/PR #156"
The Branch B work was originally framed as a multi-step migration. It was completed (B0–B5, B4a)
and then the *concept* of "Branch B" was retired — it's now just "the carrier model." Don't go
looking for Branch B to do new work; it's done. The name survives only in historical narratives.

### 3.6 Variant 1–4 workflow (the old `Plan-Operating-the-Biomechanical-Specification-System.md`)
An early usage manual with four workflow variants and user-request examples. Superseded by the PDP
(workflow) + Execution Modes (levels/strictness). It is OBSOLETE. If you find a "Variant 3" reference,
it is dead.

### 3.7 The "diagnostic instrument retargeted to pass" idea
Middle Split was briefly changed from in-proximal-radius target to full reach to go green. Abandoned
and reverted. The pose is a probe; it must not be moved to change the reading.

---

## 4. Hidden assumptions (things nobody wrote down but everyone relied on)

- **The joints array index is a contract.** `0..32` contiguous, LUMBAR=32. Reordering or gaping
  breaks the `SkeletonPose` buffer. Assumed safe because "no one would do that." Someone will.
- **`SkeletonPipeline.produceFrame` is the only correct call site.** Assumed because tests were
  repointed. A new engineer writing `val f = SkeletonPoseFinalizer(def); f.finalize(pose)` will get
  silently-unsolved contact poses and not know why.
- **FK is run exactly once, at the end, by the engine.** Poses never propagate world transforms.
  Assumed; if a pose sets a child's `localRotation` expecting the parent to "already be correct,"
  that's fine *only* because FK runs after all local writes.
- **The reserved no-op hooks (`preConvertPoles`) stay no-ops.** Assumed safe to ignore. A future
  "optimization" that activates one reintroduces removed behavior.
- **The ACTIVE set in BASELINE §3 is the whole universe.** Anything not listed is HISTORICAL and
  non-authoritative. Assumed; a new engineer might open a HISTORICAL RFC and think it's current.
- **`:app:testDebugUnitTest` is the heartbeat.** 282/0 is the known-good baseline. If you see 30
  failures, that's the *old* snapshot from before the four compile-broken files were fixed — re-measure.
- **BPS files are the biomechanical truth.** If BPS and code disagree, BPS is right and code is wrong
  (P17, modulo "code wins over doc when doc conflicts with live code" — that specific rule is about
  doc-vs-code conflicts, not BPS-vs-code; BPS is spec, not just doc).
- **The registry is the only way a pose is reachable.** A new pose not registered in the live pose
  registry is invisible to the system even if it compiles.

---

## 5. Why some RFCs exist

- **Development System / Orchestrator / Lifecycle / Capability Levels / DoD / PDP / Execution Modes**
  exist because the project accumulated many correct-but-independent specs and had no single anchor,
  no entry point, no execution spine, and hidden dependencies. They were created to make the ecosystem
  *deterministic*: same task → same docs, same authority order, same execution order, same gate.
- **PRP (Pose Responsibility Protocol)** exists because poses kept solving biomechanics. It draws the
  line in one enforceable document with a review checklist (the four questions).
- **PAC** exists because acceptance was "looks good" / "feels better" — subjective. PAC makes it a
  measurable, ordered verdict (BPS→JOM→MOM→MSS→VOM→Engine→Visual).
- **BPS/MSS/MOM/JOM/VOM** exist to decompose "what is correct motion" into target / sequence /
  movement-ownership / joint-ownership / validation-ownership so no single document owns a contradiction.
- **STABILIZATION_AUDIT** exists as the single live forward-work tracker for production exercises
  (the 282/0 baseline came from per-family review of 60 registered exercises).
- **BASELINE** exists to name the ACTIVE set and classify everything else (ARCHIVE/OBSOLETE/MERGE/DELETE)
  so old plans stop contradicting live architecture.
- **MonkEngine Design Principles** exists as the constitution that outranks all — so no RFC or PR can
  suspend a principle for convenience. 35 principles, deliberately stable (P35).

---

## 6. Why some RFCs became obsolete

- **Branch B / Phase D/E/F / RFC_BRANCH_B_REPLAN / RFC_ENGINE_STABILIZATION / RFC_ENGINE_CLEANUP_PLAN**
  became HISTORICAL because the work they described is *shipped and described by the ACTIVE set*. Their
  decisions are reflected in `ARCHITECTURE_V2` / PRP / carrier model; the plan text no longer steers.
- **The Variant 1–4 manual** became OBSOLETE when PDP + Execution Modes replaced it.
- **Engine history / investigation archives** became HISTORICAL because they explain *why* a decision
  was made, not *what to do now*. They are context, not guidance.
- **`RFC_LEGACY_ENGINE_RETIREMENT`** and friends are HISTORICAL because the legacy engine is gone.
- The general rule (BASELINE §4): a doc is OBSOLETE when superseded, MERGE when its content should fold
  into an ACTIVE doc then be deleted, DELETE when done. HISTORICAL is never auto-loaded.

The trap: a HISTORICAL doc *looks* like an RFC. It is not. If you are reading something with "Branch B"
or "Phase D" in the title, it is a tombstone, not a manual.

---

## 7. What usually goes wrong

- A pose "looks wrong" → engineer adds a magic offset → pose now lies → engine defect stays hidden.
  (The offset is a fossil of an engine limitation.)
- A validation pose fails → engineer loosens the threshold → green, blind.
- A test fails → engineer "fixes" the code to match a stale expected value → regresses the real behavior.
- A new pose copies an old pose's structure → inherits its quirks → P15 violated.
- An engineer calls `finalize()` directly → contacts unsolved → "works on my machine" → fails in
  production pipeline.
- An engineer opens a HISTORICAL RFC for guidance → follows Branch B → reintroduces carrier migration
  thinking that the live architecture retired.
- Compile error appears → engineer works around it or defers it → module goes blind → 31 failures
  invisible.
- Chest rotation "doesn't show up" → engineer adds a manual `chest.localRotation` write → finalizer
  clobbers it (or, pre-fix, the finalizer clobbered the author's write) → confusion.

---

## 8. Common mistakes when modifying poses

1. **Writing `localRotation.set(...)` to "fix" a limb.** That's solving FK. Declare intent via carriers.
2. **Cancelling inherited parent tilt** (`-torsoPitch`, `-theta`, `invTorsoZ`). The engine cancels it
   now; your cancellation double-cancels. Delete it.
3. **Hand-laying `heel/toe` or `palm/knuckles/fingertips` positions.** Engine derives them. Remove.
4. **Manual balance / manual contacts / manual pelvis or spine stabilization.** Engine owns these.
5. **Counter-rotations** to cancel an unwanted rotation elsewhere. Fix the source joint, don't cancel.
6. **"Looks better" edits.** If it's not traceable to a BPS/JOM/VOM section, it's a visual hack (PAC §6).
7. **Bypassing the solver** with a hand-set transform "just for this arm." Duplicated IK (P25).
8. **Forgetting to register a new pose** in the live registry. Compiles, invisible.
9. **Editing a pose to make a validation pose pass.** The validator is the instrument; fix the engine.
10. **Re-deriving the chest frame or any authored rotation.** Early-return on "already authored."
11. **Using bare `localRotation` writes that should be `declareJointIntent` / `declarePelvisTilt`.**
   The carrier is the single source of truth; the node-write is allowed only as a Shape Constraint
   (recognized direct node write), everything else routes through carriers/helpers.
12. **Introducing a duplicate owner** of a joint or motion. One owner per responsibility (P9/P20/P23).

---

## 9. Common mistakes when modifying MonkEngine

1. **Breaking the contiguous joint index space.** Add joints at the end; keep `0..N` contiguous.
2. **Making `preConvertPoles` or another reserved hook active** "to handle an edge case." It's a no-op
   by design; activating it reintroduces removed behavior.
3. **Letting a pose compensate for an engine change.** If a refactor breaks a pose's realized shape,
   fix the engine or the contract (byte-identical where required), don't push the fix into the pose.
4. **Duplicating FK/IK/constraints/shared math** in a new component. One owner per mathematical
   responsibility (P21/P24/P27).
5. **Changing a shared subsystem without full pose regression.** Every affected pose must still satisfy
   PAC. A "small" solver change can silently break 60 poses.
6. **Bypassing the pipeline** with a new direct entry point. `produceFrame` is the owner.
7. **Lowering a validation check to pass** instead of fixing the engine. Same sin as pose retuning.
8. **Leaving a gating flag "just in case."** Prove byte-identical, then delete the flag (no fossils).
9. **Non-determinism** from map/set iteration order or allocation timing affecting computed motion
   (P8). The engine must be a pure function of inputs.
10. **Forgetting Compile-first.** A non-compiling engine change blinds the entire test module.

---

## 10. Advice for future engineers

- **Read the Development System graph first.** `DEVELOPMENT_SYSTEM.md` → Design Principles → BASELINE →
  Orchestrator → Capability Levels → Playbook → PDP → DoD → PRP → spec layer. In that order. It is the
  map. Everything else is a territory.
- **When a pose looks wrong, the bug is in the engine, not the pose.** This will feel wrong every
  single time. Trust the architecture anyway.
- **When a validator fails, the reading is the truth.** Fix the engine or record it. Never retune.
- **Compile before you think about anything else.** A green build is the floor.
- **Redesign from BPS, never from code.** Copy nothing from the old pose.
- **One owner per responsibility.** If two things both set the pelvis, one of them is wrong.
- **Route through `SkeletonPipeline.produceFrame`.** Never `finalize()` directly.
- **Don't preserve fossils.** If a construction exists only because the engine lacked a capability
  years ago, delete it and use the engine primitive.
- **The ACTIVE set in BASELINE §3 is the universe.** If it's not there, it's HISTORICAL. Don't cite
  HISTORICAL as guidance.
- **Ask "who owns this?" before writing any line.** Joint → JOM. Motion → MOM. Validation → VOM. Pose
  intent → the pose. Realization → the engine.
- **Determinism is not optional.** Same inputs, same outputs, always.
- **When in doubt, return to the preamble: represent human movement truthfully.** Everything else
  follows.

---

## 11. Things I would never do again

- Never call `finalize()` directly to "just check a pose."
- Never loosen a validation threshold to make a test green.
- Never add a magic offset to a pose to "make it look right."
- Never redesign a pose by copying an existing pose's structure.
- Never leave a compile error for "later."
- Never activate a reserved no-op hook "to handle an edge case."
- Never open a HISTORICAL RFC for current guidance.
- Never duplicate FK/IK/constraints in a new component "because it's simpler here."
- Never let a pose solve its own limb reach.
- Never treat a validation pose as a development target to be tuned.

---

## 12. Things I wish existed

- **A linter that fails the build if a pose calls `localRotation.set` outside a recognized Shape
  Constraint.** The PRP rules are prose; they should be enforced in CI.
- **A structural check that the joint index space is contiguous** and that `entries.size` matches the
  expected count, so adding a joint can't silently shift indices.
- **A "did you route through the pipeline?" test assertion** — fail any test that constructs a
  `SkeletonPoseFinalizer` directly instead of calling `produceFrame`.
- **A validator that detects when a pose compensates for the engine** (e.g. a counter-rotation that
  cancels a parent transform) — the forbidden-logic list as a static analysis, not just a review
  checklist.
- **A single `monkengine doctor` that sets JAVA_HOME/ANDROID_HOME/GRADLE opts from `/tmp/kilo`** so the
  toolchain dance (Cloudflare CA, custom-cacerts, gradle home) is not re-derived every session.
- **A "what owns this joint?" lookup** generated from JOM, so an engineer can type `shoulder` and see
  the single owner, instead of reading a matrix.
- **BPS as machine-checkable spec** (not just prose) so PAC could be partially auto-generated.
- **A changelog that is NOT in the always-injected AGENTS.md** — the session memory should not carry a
  170-line closed-history narrative that uses Branch B / Phase D terminology. It confuses new sessions.
- **A "governance drift" check** that flags any ACTIVE doc referencing a HISTORICAL RFC by name.
- **Determinism fuzzing** — run `produceFrame` twice on the same inputs and assert byte-identical.

---

## 13. Engineering heuristics (the gut rules)

- **If you're writing a transform in a pose, stop. You're doing the engine's job.**
- **If a test fails and you're tempted to change the code to match the test, check if the test is a
  fossil first.**
- **If a validator fails, the validator is right. The engine is wrong. Fix the engine.**
- **If a function "reconstructs" something an author set, early-return on "already authored."**
- **If you're adding a flag to prove safety, prove it, then delete the flag.**
- **If you're preserving an old construction "just in case," it's a fossil. Delete it.**
- **If two owners touch the same joint, one of them is wrong. Reassign, don't compromise.**
- **If it "looks better" but isn't traceable to BPS/JOM/VOM, it's a hack.**
- **Compile errors are blocking. Always. No exceptions.**
- **Route everything through the pipeline. There is no other door.**
- **When docs disagree, the higher one in the authority hierarchy wins; if it's code-vs-doc, code
  wins; if it's principle-vs-anything, principle wins.**
- **The simplest current-architecture primitive beats a clever pose-specific workaround.**
- **One source of truth per fact. Duplicate a constant and the copies will diverge.**

---

## 14. Undocumented conventions

- **`/tmp/kilo` is the pre-provisioned toolchain.** JDK 17, Android SDK 34, custom-cacerts with the
  Cloudflare intercept CA already imported. Set `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, and
  `GRADLE_OPTS` (trustStore) from there every session or nothing builds. The shipped `proxy-ca.pem`
  does NOT match the live CA — harvest `git-proxy-ca.pem` via openssl s_client if you need git over
  HTTPS.
- **The build baseline is 282 tests / 0 failures** for `:app:testDebugUnitTest`. The "168/30" number
  in older notes is pre-fix (before the four compile-broken files were repaired). Re-measure; don't
  trust old snapshots.
- **AGENTS.md is auto-injected every session** and contains a large closed-history narrative (Branch B
  / Phase D / EngineFlags). It explicitly says "read it as a changelog, not the current state." Trust
  the live RFCs over that narrative.
- **`docs/HISTORICAL/` is 44 docs** of tombstones. Never auto-loaded. If you cite one as guidance,
  you've made a category error.
- **The pose registry is the only discovery mechanism.** A pose not in it does not exist to the system.
- **"Shape Constraints" are the ONLY allowed direct node writes** in a pose (recognized structural
  offsets like `buildTorso`/`buildHead`-offset/`buildRigidSegment`/knee writes). Everything else is
  carrier-backed. If you're not sure whether your write is a Shape Constraint, it isn't — use a carrier.
- **`buildHead`/`buildTorso` are current helpers**, not the removed `buildHead` *branch*. Don't confuse
  the helper with the deleted branch.
- **Strictness defaults to Balanced** if `//Strictness:` is omitted; `Zero-Legacy` is the preferred
  default post-freeze.
- **The directive form is `Task: / Capability: Level X / Workflow: PDP-<Name>`**, not the old
  `//Protocol://Level://Strictness:` form.
- **PRP review four questions** are the accept/reject gate for any pose PR; Q4 ("any engine
  workaround?") must be NO or the PR is rejected.
- **Contacts are declared, never solved, by a pose.** The engine plants/holds/releases them.
- **Gaze is intent.** The engine resolves head/gaze from the neck-end frame. A pose never sets head
  orientation directly.

---

## 15. Lessons learned (the long version, because you asked)

- **The hardest bugs are the ones that look like they work.** The chest-frame overwrite produced
  plausible-looking poses that were wrong by 30°. "Looks fine" is not evidence. PAC exists because of
  this.
- **A non-compiling repo is worse than a broken repo** — it's an invisible broken repo. Compile-first
  is not bureaucracy; it's the only way to keep seeing reality.
- **Instruments must not be forged.** The moment a validator or a validation pose is tuned to pass,
  it stops being measurement and becomes authorship. The whole project's integrity rests on this.
- **Ownership is the only scalable defense against contradiction.** Every "two things both set the
  pelvis" bug is an ownership bug. Resolve by reassigning, never by averaging.
- **Governance rot is silent.** Independent correct specs drift apart until nothing agrees. The
  Development System was built because of this. Read it.
- **Migration thinking should die the moment migration ends.** "Branch B" should mean "done," not "a
  way to work." Carrying migration-era vocabulary into current docs is how future engineers get
  confused (see AGENTS.md).
- **Deleting is a feature.** The instinct to preserve "just in case" creates fossils that future
  engineers mistake for live code. Delete with confidence when the architecture supersedes it.
- **Biomechanics is the client.** The engine serves the body, not the other way around. A numerically
  stable but anatomically impossible config is worthless (P14).
- **Redesign from intent, not from implementation.** The code is a compromise with the past; BPS is
  the truth.
- **Determinism is a correctness property, not a nice-to-have.** Non-deterministic motion makes tests
  meaningless and bugs untraceable.
- **Documentation is authoritative** (P17) — but only the ACTIVE set, and code wins when a doc
  contradicts live code. Know which doc is which.
- **The smallest unit of truth is a single owner.** Duplicate a fact and the copies diverge. Duplicate
  a responsibility and the owners disagree.
- **The pipeline is a contract, not a suggestion.** Bypassing it doesn't save time; it loses the Solver
  and you don't notice until production.
- **Future-you is a different person who knows nothing.** Write the closed history out of the hot
  path (AGENTS.md), keep ACTIVE docs clean, and assume the next engineer will open a HISTORICAL RFC by
  mistake. Make that mistake expensive, not free.

---

## 16. The thing I most want you to feel

When you modify a pose and it "looks wrong," your hand will reach for an offset. Don't. The offset is
a lie the pose tells to cover for the engine. The engine is the only one allowed to compute. Your job
is to declare what the human should be doing — where the hand goes, how the pelvis tilts, what touches
the floor — and then trust the runtime to realize it. If the runtime can't, that's an engine bug, and
engine bugs are fixed in the engine, with the validator reading the truth the whole time.

That restraint — declaring instead of computing, measuring instead of tuning, deleting instead of
preserving — is the entire project. Everything in the RFCs is just that restraint, written down so you
don't have to relearn it the hard way.

I did. Now you don't have to.

---

*End of MONKENGINE_ENGINEERING_KNOWLEDGE.md — a brain dump, not documentation.*
