# Validation Ownership Matrix (VOM)

> **Status:** Architectural specification (documentation only — no code changes,
> no RFC updates).
>
> This document is the **validation companion** to the Joint Ownership Matrix
> (JOM). Where the JOM assigns *biomechanical ownership of joints* to experts who
> *build* the pose, the VOM assigns *ownership of biomechanical correctness* to
> validators who *check* the pose. It defines which subsystem is responsible for
> validating each anatomical feature, so future audits can be decomposed into
> independent validation domains instead of reasoning about the whole skeleton at
> once.

---

## 1. Validation Philosophy

Validation of a full-body pose is too large to reason about coherently in a
single pass. The human body has interlocked subsystems; a reviewer who tries to
judge the gaze, the scapula, the knee tracking, and the center of mass
simultaneously will miss defects or invent false conflicts.

The solution is the same as for authoring: **divide validation into independent
domains**, each with a **single owner**. A domain owner is responsible for judging
the correctness of its anatomical features and for nothing else. This keeps each
reviewer's reasoning small, deterministic, and free of cross-domain interference.

The canonical validation domains are:

- **Head / Neck** — gaze, cervical alignment, chin, head position.
- **Spine** — lumbar, thoracic, rib cage, overall spinal curvature.
- **Shoulder girdle** — clavicles, scapulae, humerus, glenohumeral centration.
- **Arm** — elbow flexion/extension and tuck/flare.
- **Wrist / Hand** — wrist orientation, palm, knuckles, fingertips, contact
  footprint.
- **Pelvis** — pelvic tilt, list, rotation, level/square anchoring.
- **Hip** — femur orientation, hip flexion/extension, abduction, rotation.
- **Knee** — tibial relative to femoral angle, tracking, extension.
- **Foot / Ankle** — ankle angle, foot placement, toe direction, heel/toe
  contact.
- **Whole-body balance** — center of mass relative to the base of support.
- **Contact** — support constraints, planted-contact stability, pressure,
  fixed-plant enforcement.
- **Symmetry** — left/right mirror equivalence of paired structures.

Each domain has exactly one owner. No domain validates outside its ownership.

---

## 2. Validation Ownership Matrix

| Domain | Owns | Does NOT own |
|---|---|---|
| Head / Neck | gaze, cervical alignment, chin, head position | thoracic posture, shoulder rotation |
| Spine | spinal curvature (lumbar/thoracic), rib cage, trunk posture | shoulder rotation, pelvic tilt |
| Shoulder girdle | scapula, clavicle, humerus centration | elbow flexion, cervical alignment |
| Arm (Elbow) | elbow flexion/extension, tuck/flare angle | wrist orientation, shoulder centration |
| Wrist / Hand | wrist orientation, palm/knuckle/fingertip footprint | finger-independent pose, shoulder |
| Pelvis | pelvis orientation, level, square, anchoring | lumbar curvature, hip flexion |
| Hip | femur orientation, hip flexion/extension/rotation | pelvis orientation, knee tracking |
| Knee | tibia relative to femur, knee tracking, extension | ankle placement, hip orientation |
| Foot / Ankle | foot placement, ankle angle, toe direction, heel/toe contact | knee tracking, COM |
| Contact | support constraints, fixed-plant stability, pressure | local joint ROM, symmetry |
| Balance | COM over base of support, global stability | local joint angles, contact pressure |
| Symmetry | left/right mirror equivalence of paired joints | absolute correctness of either side |

The "Does NOT own" column is the core of the matrix: it forbids a domain from
overruling a sibling domain. A defect reported in a domain's non-owned column
must be routed to the correct owner, not "fixed" in place.

---

## 3. Validation Order

Validation runs in a **deterministic order**. Each later domain may *assume the
earlier domains are already valid* — it validates its own features against an
already-correct foundation, and raises a domain-local defect rather than
re-litigating the base.

```
Balance
   ↓
Contacts
   ↓
Pelvis
   ↓
Spine
   ↓
Head / Neck
   ↓
Shoulder girdle
   ↓
Arm (Elbow)
   ↓
Wrist / Hand
   ↓
Hip
   ↓
Knee
   ↓
Foot / Ankle
   ↓
Symmetry   (final cross-check over all paired domains)
```

Rationale for the ordering:

- **Balance** first, because if the COM leaves the base of support the pose is
  falling, not posing — everything else is moot.
- **Contacts** next, because the planted supports define the fixed frame the rest
  of the body is judged against.
- **Pelvis → Spine → Head** ascend the trunk from root to crown; each step builds
  on the one below.
- **Shoulder girdle → Arm → Wrist/Hand** descend the arm chain from mount to
  contact; each step builds on the one above.
- **Hip → Knee → Foot/Ankle** descend the leg chain from pelvis to ground.
- **Symmetry** last, as a global mirror check that reads the outputs of every
  paired domain without re-owning any of them.

A domain that detects a defect *outside* its ownership (e.g. the Knee validator
notices a foot that has drifted) does **not** correct it; it defers the finding
to the owning domain (Foot/Ankle) and records a cross-reference, not a local
verdict.

---

## 4. Cross-Domain Dependencies

Only **legitimate, acyclic** dependencies are permitted. A domain may *read* the
outputs of an earlier domain as its foundation; it must never *own* or *redefine*
them.

- **Spine depends on Pelvis.** The spine is judged relative to the already-valid
  pelvic frame; the spine owner does not re-decide pelvic tilt.
- **Head / Neck depends on Spine.** Cervical alignment is judged as a continuation
  of the validated thoracic line; the head owner does not re-decide thoracic
  posture.
- **Shoulder girdle depends on Spine.** Scapular/thoracic relationship is judged
  against the validated chest frame; the girdle owner does not re-decide trunk
  posture.
- **Arm (Elbow) depends on Shoulder girdle.** Elbow tuck/flare is judged
  relative to the validated humerus; the arm owner does not re-decide humeral
  centration.
- **Wrist / Hand depends on Arm.** Wrist/hand footprint is judged relative to
  the validated elbow; the hand owner does not re-decide elbow flexion.
- **Hip depends on Pelvis.** Femur orientation is judged relative to the validated
  pelvic socket; the hip owner does not re-decide pelvic orientation.
- **Knee depends on Hip.** Tibia-relative-to-femur is judged against the
  validated hip; the knee owner does not re-decide hip flexion.
- **Foot / Ankle depends on Hip + Knee.** Foot placement is judged against the
  validated leg chain; the foot owner does not re-decide knee tracking.
- **Balance depends on Contacts** (and all planted supports).
- **Symmetry depends on every paired domain** but owns none of them.

The dependency graph is a **directed acyclic graph (DAG)**. There are no cycles:
no domain depends on a later domain, and no domain re-owns an upstream feature.

---

## 5. Independent Experts

A future full-body audit should **not** be performed by one agent reasoning over
all 33 joints simultaneously. Instead, the work is split across independent
validators, each owning exactly its domain from §2. Because domains do not
overlap and dependencies are acyclic (§4), large parts of the audit can run in
parallel.

A recommended parallelization:

- **Expert A** — Balance, Contacts
- **Expert B** — Pelvis, Spine
- **Expert C** — Head, Neck
- **Expert D** — Shoulder girdle
- **Expert E** — Arm (Elbow)
- **Expert F** — Wrist / Hand
- **Expert G** — Hip
- **Expert H** — Knee
- **Expert I** — Foot / Ankle

Each expert validates **only its ownership domain**, using the canonical
Biomechanical Pose Specification (BPS) checkpoints for the exercise as its
acceptance standard. Expert A (Balance/Contacts) and Expert B (Pelvis/Spine)
feed the foundation that Experts C–I build on, so those two finish first; the
arm chain (D→E→F) and leg chain (G→H→I) then validate in parallel against that
foundation. A final Symmetry pass (readable by any expert or a dedicated
reviewer) cross-checks the paired outputs.

Because the ownership is disjoint and the dependency graph is acyclic, no expert
blocks another except along the documented foundation edges. This decomposition
turns one intractable 33-joint review into nine small, independent, auditable
reviews.

---

## 6. Conflict Resolution

When two experts disagree about a feature, the rule is unambiguous: **the owner
wins.**

- The **Hand** expert cannot change the **Shoulder**. If the hand validator
  believes the shoulder is wrong, it raises the finding to the Shoulder girdle
  owner; it does not alter the shoulder judgment.
- The **Shoulder girdle** expert cannot change the **Wrist**. If the girdle
  validator believes the wrist is wrong, it routes the finding to the Wrist/Hand
  owner.
- The **Hip** expert cannot change the **Pelvis**. If the hip validator believes
  the pelvis is mis-tilted, it routes the finding to the Pelvis owner.
- The **Knee** expert cannot change the **Hip** or **Foot**; the **Spine**
  expert cannot change the **Pelvis**; the **Head** expert cannot change the
  **Spine**.

Only the owner may redefine its domain. A non-owner may *report* a defect in a
sibling domain, but the verdict and any correction belong exclusively to the
owner. This prevents the classic failure mode where every reviewer "helps" by
editing neighboring domains and the pose oscillates between contradictory fixes.

If two *owners* legitimately conflict (e.g. the Spine owner and the Shoulder
girdle owner disagree on the chest frame they share), the conflict is resolved by
the validation order in §3: the earlier (upstream) owner's judgment is treated as
foundational, and the later (downstream) owner adapts within its own domain
without redefining the upstream feature.

---

## 7. Future Use

All future biomechanical pose audits must **first split the work according to this
Validation Ownership Matrix** before making any recommendations. The matrix is the
canonical decomposition; an audit that ignores it is not a valid audit.

**No single reviewer should attempt to validate the whole body at once.** The body
is decomposed into the twelve domains of §1, assigned to independent experts per
§5, validated in the order of §3, with dependencies per §4 and conflicts
resolved per §6. Each expert measures its domain against the exercise's BPS
checkpoints and the JOM ownership of the underlying joints.

This document is complementary to the JOM: the JOM says *who builds each joint*;
the VOM says *who certifies each feature's correctness*. Together they make both
authoring and auditing decomposable, deterministic, and free of cross-ownership
interference.

> Documentation only. No engine code, carriers, or RFC files are modified by
> this document.
