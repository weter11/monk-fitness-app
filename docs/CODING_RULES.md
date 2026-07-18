# CODING_RULES.md — Permanent Engineering Rules

> Part of the project constitution. These are the standing rules for all future
> development on the MonkEngine, poses, and validation. Pull requests are
> expected to comply with this document and may reference it instead of
> restating its contents. Rules here are stable defaults; they are superseded
> only by an explicit, documented decision that updates this file.

---

## 1. Philosophy in One Line

Solve problems in the **engine**, keep responsibilities **separated**, keep
poses **data-driven**, and **never fake motion**.

---

## 2. Do

### Reuse existing engine systems
Before writing new math or geometry, look for an existing primitive. the MonkEngine runtime
already provides vector/rotation math, an analytical IK solver, FK traversal,
foot/hand completion, and shared pose scaffolding. Extend and reuse these rather
than re-deriving them locally.

### Keep the architecture simple
Prefer the smallest change that solves the real problem. Fewer moving parts,
clearer boundaries, less state. Simplicity is a feature.

### Prefer engine solutions over pose hacks
If a shape is hard to achieve, the fix almost always belongs in the MonkEngine runtime (a
better solve, a correct pole, a proper root) so the whole family of exercises
benefits. A local hack in one pose fixes one thing and rots everything around
it.

### Keep responsibilities separated
Respect the four layers: **Engine solves motion, Pose describes biomechanics,
Exercise describes metadata, Validation verifies correctness.** Do not push
exercise knowledge into the math core, motion math into metadata, or mutations
into the validator.

### Keep poses data-driven
A concrete exercise should mostly be *configuration*: lengths, angles, poles,
grip, camera, environment, support, and a few overrides on a shared base.
Family base classes hold shared behavior; concrete poses supply the numbers.

### Use `SkeletonFactory`
Build skeleton topology through `SkeletonFactory`. If a new rooting or topology
is needed, add it there once and reuse it. Do not hand-assemble node
hierarchies inside individual poses.

### Use `bakeIkLimb`
Drive limbs through the shared IK helpers and bake results into local offsets
with `bakeIkLimb` (or the provided `solveArmIK`/`solveLegIK`/near-straight
helpers). This keeps every limb flowing through the same, tested path.

### Use frame-relative pole vectors
Author elbow/knee poles in the limb root's local frame (chest/pelvis) and let
the MonkEngine runtime transform them to world space. This keeps joints anatomically stable
as parent frames rotate.

### Keep allocations minimal
Hot paths must not allocate per frame. Reuse the provided scratch buffers and
result objects; write into caller-supplied outputs instead of returning new
vectors.

### Document architectural decisions
When you make a structural choice — a new topology, a new engine primitive, a
deviation from a default — record the reasoning (in the PR and, where lasting,
in these docs). Future contributors must be able to understand *why*.

---

## 3. Don't

### Do not add magic constants
No unexplained numbers dropped in to make one pose look right. Constants must be
justified, named where meaningful, and belong to a general rule — not a
one-off patch.

### Do not compensate for engine bugs
If the MonkEngine runtime produces a wrong result, fix the MonkEngine runtime. Never paper over a solver
or FK defect in a pose. A compensation is a hidden bug that will resurface
everywhere else.

### Do not move targets to hide IK problems
IK targets are honest biomechanical positions (a grip, a plant). Never nudge a
target to dodge a clamp, a flip, or an awkward solve. If the target is
unreachable, that is information — investigate it.

### Do not bypass the validator
Validation is the correctness authority. Do not disable it, special-case around
it, or ship poses that only pass by turning checks off. Fix the pose or the
engine until it passes honestly.

### Do not duplicate engine functionality
No local re-implementations of IK, FK, rotation math, or foot/hand geometry.
Duplication drifts out of sync and multiplies bugs. Call the shared systems.

### Do not introduce speculative abstractions
Build for the problem in front of you, not for imagined future ones. Do not add
layers, interfaces, or configuration "just in case." Abstraction is earned by
real, repeated need.

### Do not modify `SkeletonDefinition` to fit one exercise
The skeleton definition describes shared body proportions and constraints. Do
not bend it to accommodate a single pose; that breaks every other pose that
depends on it. If one exercise needs something special, express it in that
pose, not in the shared definition.

### Do not optimize one pose at the expense of others
Changes to shared code (engine, base classes, definitions) must not regress
other poses. A "win" for one exercise that degrades the rest is a net loss.
Verify shared changes against the broader set.

---

## 4. Decision Guide

When you hit a hard case, ask in order:

1. **Which layer owns this?** Motion → engine. Biomechanics → pose. Naming/
   presentation → metadata. Correctness → validation.
2. **Does a primitive already exist?** If yes, use it. If no, does it belong in
   the MonkEngine runtime so others can reuse it?
3. **Am I about to add a constant, offset, or target nudge?** If yes, stop —
   find the real joint or the real engine cause.
4. **Will this shared change affect other poses?** If yes, verify them.
5. **Is a validation pose failing?** Assume the MonkEngine runtime is wrong first (see
   VALIDATION.md).

---

## 5. Companion Documents

- **ENGINE.md** — how the MonkEngine runtime is architected and what belongs where.
- **BIOMECHANICS.md** — the movement principles poses must honor.
- **VALIDATION.md** — how validation poses and the Engineering Validation
  subsystem work.

Treat all four as the source of truth unless a change explicitly supersedes
them here.
