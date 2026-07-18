# BIOMECHANICS.md — Biomechanical Philosophy

> Part of the project constitution. This is **not** a medical textbook. It is
> the engineering interpretation of human movement that this project uses to
> decide whether an animation is *correct*. It describes reasoning, not
> implementation. When a specific exercise is named, it is only to illustrate a
> general principle.

---

## 1. Guiding Principle: Honest Motion

Every animation must look the way the real movement *works*, not merely the way
it *looks from one angle*. The body is a chain of joints with real ranges and
real sequencing. We animate the cause, and the visible shape follows.

> **Never fake motion.** If a joint should move, move that joint. Do not
> translate the pelvis, slide a target, or add an offset to *approximate* a
> shape that a correct joint rotation would have produced naturally.

This single rule generates most of the others.

---

## 2. Motion Starts From the Correct Joint

Every movement has a *prime mover* — the joint where the motion originates. The
animation must begin there.

- A hinge (squat, deadlift-like patterns) starts at the **hips**, then knees,
  then ankles follow.
- A press starts at the **shoulder/scapula and elbow**, not by shoving the
  torso.
- A pull starts at the **scapula**, not by yanking the hands.

If the earliest visible motion is in the wrong joint, the animation is wrong
even if the end position looks acceptable.

---

## 3. Anatomical Priorities and Joint Sequencing

Joints act in sequence, not simultaneously. The order matters as much as the
endpoints.

- **Proximal before distal** for generating movement (spine/hip/scapula lead;
  hands/feet finish).
- **Distal fixed, proximal moves** when a limb is planted (the hand is fixed on
  the bar; the body travels toward it).
- Motion **eases** in and out — real joints accelerate and decelerate; they do
  not teleport between key positions. Discontinuities in position, velocity, or
  acceleration are treated as defects.

---

## 4. Scapula Drives Upper-Body Pulling

In any pulling pattern the **scapula moves first**. Before the elbows bend, the
shoulder blades depress and retract; the body rises slightly while the arms are
still essentially straight. Only as the reach shortens do the elbows flex and
the chest lead toward the hands.

> **Reasoning:** starting a pull with elbow flexion produces the classic "arm
> curl on a bar" error. Scapular initiation is what makes a pull-up read as a
> pull-up. the MonkEngine runtime expresses this by driving the pull from a shrinking
> shoulder-to-target reach rather than from an elbow angle.

---

## 5. Thoracic Follows the Shoulder Girdle

The thoracic spine and rib cage follow the shoulder girdle, they do not lead
it. When the shoulders retract or the arms travel overhead, the thorax responds
(slight extension/rotation) as a consequence, not as an independent driver.
Chest orientation is derived from the spine line and the shoulder line together
— it is a result of the surrounding structure, not an arbitrary rotation.

---

## 6. The Pelvis Stabilizes by Default

The pelvis is the anchor of the trunk. Unless the movement genuinely requires
pelvic motion (a hip hinge, an anterior/posterior tilt that is the point of the
exercise, a hip-driven lunge), the pelvis **stays put and stays level**.

> **Reasoning:** translating the pelvis is the most tempting way to fake almost
> any shape, because the whole body rides along with it. This is precisely why
> it is forbidden as a shortcut. Move the pelvis only when the *biomechanics*
> demand it — never to compensate for a joint that should have moved instead.

---

## 7. Center of Mass Shifts Naturally

The center of mass moves as a *consequence* of the joints doing their jobs, and
it must stay over the base of support in supported/static positions. We do not
place the COM directly; we let correct joint motion produce it, then verify it
stays within the support polygon. A body whose COM leaves its support without a
corresponding dynamic reason is falling, not posing.

---

## 8. Planted Parts Stay Planted

Feet and hands that are in contact with the ground, a bar, or a prop are
**fixed**. They do not slide, drift, or float while they are meant to bear
load or provide support.

- A planted foot is the root the rest of the body moves around.
- A gripping hand is a fixed point; the body travels relative to it.
- A support that slides is a defect (validation flags hand sliding and ground
  penetration).

This is why the MonkEngine runtime can re-root the skeleton at a fixed contact: the plant
is a real constraint, not a cosmetic one.

---

## 9. Natural Range of Motion

Joints move within believable limits. The IK solver enforces a minimum flexion
(joints never hyper-collapse) and a maximum extension below full lock (limbs
never snap perfectly straight). When a requested target lies outside a joint's
reachable range, the correct response is to respect the limit and treat the
unreachable request as a problem to investigate — **not** to stretch a bone,
break a length, or shove a neighboring joint to cover the gap.

---

## 10. Never Compensate in the Wrong Place

Compensation is the root of fake motion. The following are all violations:

- Moving the pelvis to simulate a joint that should have rotated.
- Sliding an IK target to hide an elbow/knee that solved awkwardly.
- Adding an ad-hoc offset to a hand or foot to force a silhouette.
- Bending the "other" limb the wrong way to match a shape.

If the result looks wrong, find the joint that is actually responsible and fix
the motion there.

---

## 11. Worked Reasoning by Movement Family

These illustrate the principles above; they are reasoning, not code.

- **Push-up.** Rigid trunk hinging around planted hands and feet. The plank
  line (shoulders–hips–ankles) stays straight; the elbows flex and extend to
  lower and raise the whole rigid body. The pelvis does not sag or pike to fake
  depth — depth comes from elbow flexion. Hands stay fixed.

- **Pull-up.** Scapula initiates (Principle 4). Hands are fixed on the bar; the
  body is pulled up toward them. Early motion is scapular with straight arms;
  elbow flexion and chest lead follow as reach shortens.

- **Squat.** Hip-led hinge, then knees, then ankles. Feet stay planted, COM
  stays over the feet, trunk angle follows the hip hinge. Depth comes from the
  hips and knees, not from dropping the pelvis straight down through the feet.

- **Lunge.** Weight transfers onto a planted front foot; the front hip and knee
  drive the descent while the rear leg follows. The planted foot does not
  slide; the pelvis stays square unless the pattern requires rotation.

- **Bird Dog.** A stability pattern: the trunk and pelvis stay rigid and level
  while one arm and the opposite leg extend. The *point* of the movement is
  that the pelvis does **not** move — any pelvic tilt or rotation is a failure,
  not a flourish.

- **Plank.** A static hold. Straight-line trunk over fixed forearm/hand and
  foot contacts. Nothing slides, nothing sags, COM stays inside the support
  polygon.

- **Stretching.** Slow approach to end range within natural ROM. The target
  lengthened position is real; we reach it by moving the correct joints toward
  their limits, never by breaking segment lengths to exaggerate the stretch.

- **Static holds.** A single frozen, correct configuration. Correctness is
  purely postural: joints within range, supports planted, COM balanced, body
  complete. There is no motion to fake, only anatomy to get right.

---

## 12. Summary

- Motion starts from the correct joint and sequences proximal-to-distal.
- Scapula drives pulling; thoracic follows the shoulder girdle.
- The pelvis stabilizes by default; the COM shifts as a consequence.
- Planted feet and hands stay planted.
- Respect natural ROM.
- **Never fake motion, and never compensate in the wrong place.**
