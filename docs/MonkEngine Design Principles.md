# MonkEngine Design Principles

> *The constitutional document of the MonkEngine Development System.*
>
> This is not an RFC describing an implementation. It is the project's founding law.
> Every future architecture, feature, pose, validator, review, and document must obey
> these principles. They are implementation-independent and must remain valid even if the
> engine is completely rewritten.

---

## Preamble

MonkEngine exists to represent human movement truthfully. It is a system for describing,
simulating, and validating biomechanically faithful motion — not for drawing figures, not
for satisfying a renderer, not for pleasing a test.

These principles are the highest authority in the repository. Every other document —
the engineering playbook, the architecture, the biomechanical pose specification, the joint
ownership matrix, the validation ownership matrix, the movement sequence specification, the
pose development protocol, the acceptance criteria, the definition of done, the capability
levels — derives from this one. Where any document conflicts with these principles, these
principles win.

A developer who has never seen the codebase should be able to read this document and know,
without ambiguity, what MonkEngine is for and how it must be built.

---

## Philosophy

### P1. MonkEngine exists to represent human movement truthfully.

**Rationale.** The system's only purpose is fidelity to how bodies actually move. Any feature
that sacrifices truth for convenience, performance, or appearance betrays that purpose.

**Practical consequence.** Every design decision is measured against one question: does this
make the represented movement more faithful to human biomechanics? If not, it does not belong.

**Common violation.** Adding a visual flourish, shortcut, or "good enough" approximation that
deviates from correct motion because it looks acceptable on screen.

### P2. Biomechanics is the source of truth.

**Rationale.** Human anatomy and kinesiology are fixed facts. They do not negotiate with the
codebase. The engine must conform to biomechanics; biomechanics must never be bent to fit the
engine.

**Practical consequence.** When code and biomechanics disagree, the code is wrong. The
anatomical fact is the reference, not the implementation.

**Common violation.** Adjusting a movement range, joint limit, or limb proportion because the
math is easier, rather than correcting the math to honor the body.

### P3. Engine mathematics and biomechanical intent are separated.

**Rationale.** *What* the body should do (intent) and *how* space and forces are computed
(mathematics) are different concerns with different owners and different rates of change.
Confusing them couples movement meaning to implementation detail.

**Practical consequence.** A description of a movement says what is intended; the engine
computes the resulting geometry. Intent is expressed in biomechanical terms, never in raw
coordinates handed to a solver.

**Common violation.** A movement definition that writes joint positions or angles directly
instead of declaring the biomechanical outcome it intends.

---

## Architecture

### P4. The engine owns mathematics.

**Rationale.** Forward kinematics, inverse kinematics, constraints, and all spatial
computation are engine responsibilities. Distributing them across features invites drift,
inconsistency, and silent error.

**Practical consequence.** Movement authors declare intent; the engine performs the
computation that realizes it. No feature reimplements the mathematics the engine already owns.

**Common violation.** A pose computing its own joint transforms or solving its own limb
reaching instead of relying on the engine.

### P5. Poses own intent.

**Rationale.** A pose is a statement of biomechanical intent — what the body is trying to do.
It is not a geometry generator. Keeping intent in the pose and mathematics in the engine
preserves both clarity and reuse.

**Practical consequence.** A pose describes desired motion in biomechanical language and
delegates all computation. It never hard-codes the transforms the engine should produce.

**Common violation.** A pose that bakes computed joint placements into itself to "make the
output look right," bypassing the engine's responsibility.

### P6. Validation never authors poses.

**Rationale.** A validator's job is to measure, not to create. If validation rewrites motion
to pass, it hides the truth it was meant to reveal. The instrument becomes the forger.

**Practical consequence.** Validation reports what it finds. When a pose fails, the failure is
recorded or the pose is fixed — validation is never edited to make the pose read green.

**Common violation.** Loosening a check, retuning a threshold, or mutating the pose inside the
validator so that an unfaithful movement passes.

### P7. One source of truth.

**Rationale.** Every fact — a joint limit, a body proportion, a movement definition — must
live in exactly one place. Duplicated facts diverge, and divergence produces contradictions
the system cannot resolve.

**Practical consequence.** Define each fact once, at the appropriate level of authority, and
reference it everywhere. Never copy a truth into a second home.

**Common violation.** Re-stating a constant, limit, or definition in a pose because it is
"easier to read locally," creating two copies that eventually disagree.

### P8. Deterministic execution.

**Rationale.** A given input must always produce the same output. Non-determinism makes
movement unreproducible, tests meaningless, and bugs untraceable.

**Practical consequence.** The engine is a pure function of its inputs and its configuration.
No hidden state, no unordered iteration over sets that affect results, no time-dependent
behavior in computation.

**Common violation.** Relying on map/set iteration order, allocation timing, or wall-clock
state to influence computed motion.

### P9. No duplicated ownership.

**Rationale.** When two components both own the same responsibility, neither is accountable,
and they will disagree. Ownership must be exclusive and unambiguous.

**Practical consequence.** Each responsibility is assigned to exactly one owner. Overlapping
authority is resolved by reassigning, not by compromise.

**Common violation.** Two subsystems both adjusting the pelvis, both solving the same limb, or
both deciding a contact — each convinced the other handles it.

---

## Biomechanics

### P10. Biomechanics before implementation.

**Rationale.** The correct movement is known from anatomy and kinesiology before any code is
written. Implementation serves the movement; it does not define it.

**Practical consequence.** Start every task from the biomechanical specification. The code is
the last step, not the first.

**Common violation.** Beginning with "how do we make the solver do this" instead of "what is
the correct movement."

### P11. Movement before coordinates.

**Rationale.** A motion is a sequence of bodily actions — reach, rotate, load, extend.
Coordinates are a downstream projection of that meaning. Thinking in coordinates first loses
the movement.

**Practical consequence.** Describe and reason about motion as movement. Translate to
coordinates only at the engine boundary.

**Common violation.** Specifying a pose as a list of joint positions and reverse-engineering a
narrative for it afterward.

### P12. Purpose before appearance.

**Rationale.** A movement is performed for a biomechanical reason — to load a muscle, to
stabilize, to transport the body. Appearance is a side effect. Optimizing appearance over
purpose produces motion that looks right and is wrong.

**Practical consequence.** Judge a pose by whether it achieves its biomechanical purpose, not
by whether it is visually pleasing.

**Common violation.** Tuning a limb angle so the silhouette "reads better" while breaking the
load or stability the movement requires.

### P13. Whole-body consistency.

**Rationale.** The body is one linked system. A change at one joint propagates through the
chain. A pose that is locally plausible but globally inconsistent is biomechanically false.

**Practical consequence.** Every pose is evaluated as a connected whole. Contacts, load paths,
and chains must agree across the entire body.

**Common violation.** Positioning an arm or leg correctly in isolation while the supporting
trunk, pelvis, or opposite limb contradicts it.

### P14. Human movement over numerical correctness.

**Rationale.** A solution can be mathematically clean and biomechanically impossible. Numbers
that satisfy equations but violate the body are worthless here.

**Practical consequence.** When numerical elegance conflicts with faithful movement, faithfulness
wins. The mathematics serves the human, not the other way around.

**Common violation.** Accepting a numerically stable but anatomically impossible configuration
because it "converges."

---

## Development

### P15. Never redesign from existing code.

**Rationale.** Code is a record of past compromises, not a source of truth. Redesigning from
code inherits its errors and freezes its assumptions.

**Practical consequence.** When reworking a feature, return to the biomechanical specification.
Treat the current code as one possible implementation, not as the definition.

**Common violation.** Copying an existing pose's structure as the template for a new one and
carrying forward its quirks.

### P16. Always redesign from biomechanics.

**Rationale.** The biomechanical intent is stable; the code is not. Designing from intent
yields a correct result independent of how the old code was written.

**Practical consequence.** Every redesign begins with "what should this movement do?" derived
from anatomy, then a clean implementation.

**Common violation.** Editing the old implementation in place because the new requirement is
"close enough" to what exists.

### P17. Documentation is authoritative.

**Rationale.** The written specification is the contract. Code that contradicts the
documentation is in error, regardless of how long it has run.

**Practical consequence.** When code and document disagree, the document defines correct
behavior and the code is corrected.

**Common violation.** Treating a long-standing implementation as "the real spec" and rewriting
the document to match a bug.

### P18. Historical documents never override active governance.

**Rationale.** Notes, post-mortems, and superseded RFCs capture context but lose authority once
a newer governing document exists. Letting them silently win reintroduces abandoned decisions.

**Practical consequence.** Active principles and current RFCs outrank historical text. A
historical document may inform, never dictate.

**Common violation.** Reviving a deleted approach because an old doc described it, ignoring the
RFC that retired it.

### P19. Consensus before implementation.

**Rationale.** A movement's biomechanical intent and the engine's ownership of it must be
agreed before code is written. Building first and debating later produces rework and
contradiction.

**Practical consequence.** Settle the specification and the ownership questions, then implement.
Ambiguity is resolved on paper, not in a pull request.

**Common violation.** Shipping a pose with an undefined intent or unclear owner, then resolving
it through review churn.

---

## Ownership

### P20. Single owner per biomechanical domain.

**Rationale.** A domain — a joint complex, a movement family, a body region — needs one
authoritative definition. Many authors produce many truths.

**Practical consequence.** Each biomechanical domain is specified and owned by exactly one
source. Changes to it go through that owner.

**Common violation.** Two poses each defining "their own" version of the same joint behavior.

### P21. Single owner per mathematical responsibility.

**Rationale.** Forward kinematics, inverse kinematics, constraint solving, and smoothing each
need one implementation. Fragmented mathematics cannot stay consistent.

**Practical consequence.** Each mathematical responsibility lives in exactly one engine
component. Features call it; they do not reimplement it.

**Common violation.** A pose solving its own limb reach because the shared solver "didn't quite
fit."

### P22. Single owner per validation responsibility.

**Rationale.** A check — bone length, joint limit, contact, smoothness — must be defined and
applied in one place. Scattered checks conflict and miss cases.

**Practical consequence.** Each validation rule is owned by exactly one validator. No feature
performs its own private validation of a shared rule.

**Common violation.** A pose asserting its own "validity" with an ad-hoc check that disagrees
with the system validator.

### P23. No overlapping authority.

**Rationale.** Overlap is the root of contradiction. Two owners of the same decision will
eventually decide differently, and the system cannot tell which is correct.

**Practical consequence.** Authority is partitioned exhaustively and exclusively. Any detected
overlap is resolved by reassigning ownership, never by letting both stand.

**Common violation.** The engine and a pose both deciding a contact point, each subtly
different.

---

## Code

### P24. Never duplicate forward kinematics.

**Rationale.** Forward kinematics is the single mapping from local to world space. A second
copy will drift from the first and produce two bodies in one skeleton.

**Practical consequence.** There is exactly one forward-kinematics path. Everything that needs
world transforms uses it.

**Common violation.** A feature computing world positions by hand instead of propagating
through the one kinematics step.

### P25. Never duplicate inverse kinematics.

**Rationale.** Limb solving must be uniform. Independent solvers produce inconsistent reaches,
different clamping, and unequal behavior across poses.

**Practical consequence.** One inverse-kinematics solver serves every limb. Poses supply intent
and targets; the solver does the rest.

**Common violation.** A pose writing a custom reach solver "just for this arm."

### P26. Never duplicate constraints.

**Rationale.** Joint limits and contact constraints are shared laws of the body. Duplicated
constraints apply differently in different places and let invalid motion slip through.

**Practical consequence.** Constraints live in one place and are applied uniformly. No feature
re-expresses a limit it thinks it understands.

**Common violation.** A pose clamping a joint "its own way" because the shared constraint was
inconvenient.

### P27. Never duplicate mathematics.

**Rationale.** Any reused formula — a distance, an angle, a projection — copied into multiple
homes will eventually diverge, and the divergence is a bug that passes every local test.

**Practical consequence.** Shared mathematics is implemented once and called. Repetition of a
formula is a defect, not a convenience.

**Common violation.** Re-typing a trigonometric or interpolation expression in a pose because
importing the shared helper was "one more line."

### P28. Prefer engine capabilities over pose-specific hacks.

**Rationale.** A hack local to one pose is a debt the whole system pays: it is untested by the
shared path, undocumented, and copy-pasted by the next author.

**Practical consequence.** Before writing pose-specific logic, ask whether the engine already
provides it. Extend the engine; do not workaround it.

**Common violation.** Bending a pose's output with a magic offset because the engine feature
that would do it correctly was not used.

---

## Validation

### P29. Validation measures.

**Rationale.** The value of validation is truthful measurement. A validator that cannot tell
you what is wrong is useless; one that tells you falsely is dangerous.

**Practical consequence.** Validation reports concrete, inspectable findings about the motion.
Its output is evidence, not a verdict without reason.

**Common violation.** A validator that returns "invalid" with no indication of which rule or
which joint failed.

### P30. Validation never fixes.

**Rationale.** If validation alters the pose to make it pass, it ceases to be measurement and
becomes authorship — and authors motion it was never asked to author (see P6).

**Practical consequence.** Validation reads and reports. Correction happens in the pose, by the
pose's owner, with full visibility.

**Common violation.** A validator that nudges a joint or relaxes a limit so the number turns
green.

### P31. Acceptance criteria are objective.

**Rationale.** "Looks fine" is not acceptability. A pose is done only when it meets criteria
that any reviewer, on any machine, at any time, would evaluate the same way.

**Practical consequence.** Every acceptance bar is defined in measurable terms — ranges,
tolerances, pass/fail rules — not in impression.

**Common violation.** Merging a pose because it "seems plausible" without checking it against
stated, numeric acceptance criteria.

---

## Future Evolution

### P32. The engine may evolve.

**Rationale.** Mathematics, solvers, and performance strategies will improve. The principles do
not depend on any particular engine design.

**Practical consequence.** Rewriting or replacing the engine is permitted and expected. The
principles travel with the rewrite; the implementation does not.

**Common violation.** Treating the current solver as permanent and refusing a better design
because "that's how it works now."

### P33. The architecture may evolve.

**Rationale.** Module boundaries, data flow, and ownership layout should improve as the system
grows. Rigid attachment to a frozen structure strangles the project.

**Practical consequence.** Restructure when structure is wrong. Evolve the architecture without
violating the principles it serves.

**Common violation.** Keeping a decomposed, tangled layout because changing it is effort, even
after it contradicted the principles.

### P34. The implementation may evolve.

**Rationale.** Languages, frameworks, and representations will change. None of them are the
point. Only the faithful representation of movement is the point.

**Practical consequence.** Reimplement freely. Port, rewrite, and replace implementation details
without fear, as long as the principles hold.

**Common violation.** Defending a specific code pattern as sacred because it is familiar rather
than because it serves the movement.

### P35. The principles must remain stable.

**Rationale.** If the constitution changes as often as the code, it is not a constitution. These
principles are the one fixed point in a system that must otherwise stay mutable.

**Practical consequence.** Amend these principles only through explicit, consensus-based
governance — rarely, deliberately, and never to accommodate a single feature. The codebase
bends to the principles; the principles do not bend to the codebase.

**Common violation.** Editing a principle to excuse a shortcut, rather than doing the work the
principle demands.

---

## Closing

These thirty-five principles are the law of MonkEngine. They outrank every RFC, every playbook,
every architecture, and every line of code. They are written so that a stranger to the project
can build correctly without being told, and so that the project can be rewritten from scratch
without losing its soul.

When in doubt, return to the preamble: *represent human movement truthfully.* Everything else
follows.
