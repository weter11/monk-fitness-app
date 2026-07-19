# RFC: Pose Responsibility Protocol (PRP)

> **Status:** Architecture RFC (documentation only — no code or pose changes).
>
> **Purpose.** Define exactly how future pose development must be performed inside
> MonkEngine. This document governs *responsibilities and ownership boundaries*.
> It is **not** about implementation. It draws the line between what a pose is
> allowed to do and what only the engine is allowed to do, and it binds both to
> the three canonical reference documents:
>
> - **BPS** — Biomechanical Pose Specification (the human-biomechanics target).
> - **JOM** — Joint Ownership Matrix (who builds each joint).
> - **VOM** — Validation Ownership Matrix (who certifies each feature).
>
> Together these define a single, decomposable contract for pose development.

---

## 1. Philosophy

A pose is **not allowed to solve biomechanics itself**.

The pose is a *declaration of intent*, not a solver. It states what the human
should be doing — where the limbs should reach, how the joints should be oriented,
what is in contact with the floor, what the gaze is. It does **not** compute the
joint angles, world transforms, or balance that realize that intent.

All biomechanical *realization* belongs to **MonkEngine**. MonkEngine owns:

- **IK** — turning declared limb targets into joint angles.
- **FK** — propagating local rotations into world transforms.
- **Contacts** — resolving which body parts touch the support and where.
- **Balance** — keeping the center of mass over the base of support.
- **Spine reconstruction** — deriving the chest/thoracic frame from the spine
  chain (lumbar → chest) and the shoulder girdle.
- **Head target resolution** — resolving head/gaze from the neck-end frame and
  gaze intent.
- **Articulation** — wrist/ankle and extremity orientation from intent.
- **Validation** — certifying the realized pose against the BPS via the VOM.

Because realization is the engine's job, **the pose never compensates for engine
behavior**. If the rendered pose looks wrong, the fix lives in the engine (or in
the BPS/VOM/JOM ownership), never in the pose faking a shape to cover a deficit.
A pose that "knows about" engine internals has crossed the boundary and is, by
definition, incorrect.

---

## 2. Pose Responsibilities

A pose is allowed to **declare intent only**. The complete, exhaustive list of
what a pose may do:

- ✓ **Declare ROM intent** — the range of motion a joint should traverse (e.g.
  elbow from extension to ~70° flexion), expressed as intent, not as solved
  angles.
- ✓ **Declare limb targets** — where an end-effector (hand, foot) should be in
  world space, as a target for IK, not as a forced joint solution.
- ✓ **Declare contacts** — which body parts are in contact with the support and
  the support surface (hand on floor, toes on box, back on ground).
- ✓ **Declare pelvis orientation** — the intended pelvic tilt/list/rotation as
  intent (neutral, anterior tilt, posterior tilt), not as a manual transform
  hack.
- ✓ **Declare gaze target** — the intended head/gaze direction (down/forward,
  up, neutral) as intent.
- ✓ **Timing** — the duration, loop mode, motion curve, and rep progression of
  the movement (when phases occur, not how joints are computed).
- ✓ **Support transitions** — which contacts engage/disengage across phases
  (e.g. feet step back, hands plant), declared as contact intent.

**Nothing else.** Any pose behavior outside this list is a violation of the PRP.

---

## 3. Engine Responsibilities

Everything below may be performed **only by MonkEngine**. The pose must never
reproduce or bypass any of these:

- **IK solving** — converting limb targets and ROM intent into joint angles.
- **Joint reconstruction** — building each joint's local rotation from intent
  carriers.
- **Constraint solving** — enforcing segment lengths, joint limits, and support
  constraints.
- **Support handling** — planting, holding, and releasing contacts; fixed-plant
  enforcement.
- **Balance** — center-of-mass management over the base of support.
- **Head stabilization** — resolving and anchoring the head/gaze from the neck
  frame.
- **Foot orientation** — deriving ankle/heel/toe placement from the leg chain and
  ground contact.
- **Hand orientation** — deriving wrist/palm/knuckle/fingertip footprint from
  the arm chain and contact.
- **Spine frame reconstruction** — deriving the lumbar and chest frames from the
  spine chain, pelvis, and shoulder girdle.
- **FK propagation** — turning local rotations into world transforms down every
  chain.
- **World transforms** — the final global placement of every joint.
- **Articulation** — wrist/ankle and extremity 2-DOF orientation from intent.
- **Validation** — certifying the realized pose against the BPS via the VOM
  domains.

The engine is the sole owner of realization. The pose supplies intent; the engine
supplies biomechanics.

---

## 4. Forbidden Pose Logic

The following are **explicitly forbidden** inside any pose. They are all
variations of the same error: the pose solving biomechanics that belong to the
engine, or hiding an engine deficit instead of reporting it.

- **Pose compensation** — adjusting one joint to cover for another joint the
  engine should have solved.
- **Magic offsets** — adding ad-hoc positional/rotational offsets to force a
  silhouette.
- **Visual hacks** — tweaks whose only purpose is appearance, with no
  biomechanical intent behind them.
- **Counter-rotations** — rotating a joint to cancel an unwanted rotation
  elsewhere, instead of fixing the source.
- **Engine workarounds** — pose code that exists only to dodge engine behavior.
- **"Looks better" fixes** — any change justified by appearance rather than by
  BPS intent.
- **Solver bypasses** — short-circuiting the IK/constraint solver with a
  hand-set transform.
- **Duplicated IK** — the pose re-implementing inverse kinematics.
- **Duplicated FK** — the pose re-implementing forward kinematics / world
  propagation.
- **Manual balance** — the pose positioning the COM directly instead of
  declaring intent and letting the engine balance.
- **Manual contact resolution** — the pose placing/controlling contacts instead
  of declaring them.
- **Manual pelvis stabilization** — the pose forcing pelvic levelness with
  transforms instead of declaring pelvis intent.
- **Manual spine stabilization** — the pose forcing chest/lumbar posture with
  transforms instead of declaring spine intent.
- **Manual wrist correction** — the pose setting wrist orientation instead of
  declaring articulation intent.
- **Manual foot correction** — the pose setting ankle/foot orientation instead of
  declaring foot intent.

Any occurrence of the above is a defect, regardless of how the rendered pose
looks.

---

## 5. Ownership Rule

Every biomechanical correction belongs to **exactly one owner**.

The three reference documents resolve all ownership disputes:

- **BPS** says what the human should look like (the target).
- **JOM** says which expert builds each joint (who realizes it).
- **VOM** says which domain certifies each feature (who validates it).

When a pose, an audit, or a review needs a biomechanical correction, the owner is
determined by JOM (for building) and VOM (for validating). No correction may be
claimed by two owners, and no owner may edit another owner's domain.

**If ownership is unclear, the implementation is rejected.** An ambiguity in who
owns a joint or feature is not a license to let the pose solve it — it is a
signal that the BPS/JOM/VOM trio is incomplete and must be clarified *before* the
pose proceeds. A pose that cannot name its owner for a given correction is, by
this rule, invalid.

---

## 6. Future Reviews

Every future pose pull request must explicitly answer the following four
questions in its description. A PR that does not answer them is not reviewable and
must be returned.

1. **Which BPS sections are implemented?**
   Cite the specific BPS file (e.g. `Push-Up.md`) and the §sections the pose
   realizes (overview, alignment, contacts, ROM, checkpoints, etc.).

2. **Which JOM owners are used?**
   List the JOM groups/experts whose joints the pose drives (e.g. Group A Trunk
   for pelvis/spine/head; Group D Lower Body for hip/knee/foot; Group B Girdle
   for shoulder; Group C Arm for elbow/hand).

3. **Which VOM domains validate it?**
   List the VOM validation domains that certify the result (Balance, Contacts,
   Pelvis, Spine, Head, Shoulder girdle, Arm, Wrist/Hand, Hip, Knee, Foot,
   Symmetry).

4. **Did the pose introduce any engine workaround?**
   Explicitly confirm the pose contains none of the forbidden logic in §4 (no
   compensation, offsets, hacks, bypasses, duplicated IK/FK, manual
   balance/contacts/pelvis/spine/wrist/foot).

**If the answer to question 4 is yes → Reject.** An engine workaround in a pose
is never acceptable; the deficiency belongs to the engine (or to a BPS/JOM/VOM
clarification), and the pose must be revised to declare intent only.

---

## 7. Non-Goals

The Pose Responsibility Protocol deliberately does **not** describe:

- **Algorithms** — no solving procedure is specified here.
- **IK** — the IK method is out of scope; only the ownership boundary is in
  scope.
- **FK** — forward-kinematics method is out of scope.
- **Implementation** — no code, class, function, or data structure is described.

This RFC defines **only responsibilities and ownership boundaries**. Its job is to
make the line between "pose declares intent" and "engine realizes biomechanics"
unambiguous, enforceable at review time, and decomposable via BPS + JOM + VOM.

> Documentation only. No engine code, pose files, carriers, or other RFCs are
> modified by this document.
