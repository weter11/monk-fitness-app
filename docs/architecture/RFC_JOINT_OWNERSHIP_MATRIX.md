# RFC: Joint Ownership Matrix

> **Status:** Architecture document (active — no engine code or pose changes are made by
> this document).
>
> **Goal.** Define biomechanical ownership of every MonkEngine joint so that
> reasoning complexity is reduced: each joint is assigned to exactly one
> biomechanical expert who is solely responsible for it.
>
> **Purpose.** Eliminate cross-expert interference. One owner per joint, no
> shared ownership, no expert modifies another expert's joints. Integration of
> the independently solved sub-systems occurs only after every expert has
> finished its pass.

---

## 1. Authoritative Joint List

The following joints are defined by the engine's `Joint` enumeration and are the
scope of this matrix:

| Joint | Index | Side |
|---|---|---|
| PELVIS | 0 | core |
| HIP_F | 1 | front |
| HIP_B | 2 | back |
| KNEE_F | 3 | front |
| ANKLE_F | 4 | front |
| HEEL_F | 5 | front |
| TOE_F | 6 | front |
| KNEE_B | 7 | back |
| ANKLE_B | 8 | back |
| HEEL_B | 9 | back |
| TOE_B | 10 | back |
| CHEST | 11 | core |
| SHOULDER_A | 12 | anterior/left |
| SHOULDER_P | 13 | posterior/right |
| ELBOW_A | 14 | anterior/left |
| HAND_A | 15 | anterior/left |
| WRIST_A | 16 | anterior/left |
| PALM_A | 17 | anterior/left |
| KNUCKLES_A | 18 | anterior/left |
| FINGERTIPS_A | 19 | anterior/left |
| ELBOW_P | 20 | posterior/right |
| HAND_P | 21 | posterior/right |
| WRIST_P | 22 | posterior/right |
| PALM_P | 23 | posterior/right |
| KNUCKLES_P | 24 | posterior/right |
| FINGERTIPS_P | 25 | posterior/right |
| NECK_END | 26 | core |
| HEAD_POS | 27 | core |
| CLAVICLE_A | 28 | anterior/left |
| SCAPULA_A | 29 | anterior/left |
| CLAVICLE_P | 30 | posterior/right |
| SCAPULA_P | 31 | posterior/right |
| LUMBAR | 32 | core |

Joint naming convention used below: `A` = anterior-side limb (left in the
default model), `P` = posterior-side limb (right). `F` = front leg, `B` = back
leg. The expert assignment is symmetric: both sides of a paired joint belong to
the same expert type.

---

## 2. Per-Joint Ownership

### 2.1 PELVIS (0)
- **Owner:** Group A — Trunk Expert
- **Primary responsibilities:** Pelvic tilt (anterior/posterior), pelvic list
  (lateral), pelvic rotation; anchoring the trunk; providing the root frame for
  the lumbar/chest chain and the hip sockets.
- **Secondary constraints:** Must stay level/square unless the exercise intends
  pelvic motion; must not translate to fake a joint that should have moved.
- **Inputs:** Posture intent, contact constraints, lumbar/chest intent, hip
  targets.
- **Outputs:** Pelvis world transform (position + orientation), hip-socket
  origins.
- **Allowed modifications:** Pelvis local rotation (tilt/list/rotation); root
  position only as permitted by the posture seed.
- **Forbidden modifications:** Moving any non-pelvic joint; translating the
  pelvis to compensate for hip/knee/shoulder deficits; altering limb segment
  lengths.
- **Acceptance criteria:** Pelvis orientation matches the intended tilt/list/
  rotation; pelvis level and square for neutral exercises; no spurious
  translation; hip sockets coherent with both HIP joints.

### 2.2 LUMBAR (32)
- **Owner:** Group A — Trunk Expert
- **Primary responsibilities:** Lumbar flexion/extension, lateral flexion,
  rotation; the PELVIS→LUMBAR→CHEST two-segment spine relationship.
- **Secondary constraints:** Independent of thoracic motion (a hinge can occur
  at lumbar while chest stays neutral, and vice versa).
- **Inputs:** Pelvis frame, chest intent, trunk posture intent.
- **Outputs:** Lumbar local rotation; the frame handed to CHEST.
- **Allowed modifications:** Lumbar local rotation only.
- **Forbidden modifications:** Moving CHEST/PELVIS directly; altering segment
  lengths; coupling lumbar to thoracic involuntarily.
- **Acceptance criteria:** Lumbar angle matches intent (neutral/flexed/extended/
  rotated); the chest frame it produces is continuous with pelvis and chest.

### 2.3 CHEST (11)
- **Owner:** Group A — Trunk Expert
- **Primary responsibilities:** Thoracic orientation; rib-cage carriage; the
  trunk endpoint that the shoulder girdle mounts on.
- **Secondary constraints:** Thoracic follows the spine line + shoulder girdle
  consequence, not an independent driver; must remain coherent with LUMBAR.
- **Inputs:** Lumbar frame, thoracic intent, clavicle/scapula consequence.
- **Outputs:** Chest world transform; shoulder-girdle mount frame.
- **Allowed modifications:** Chest local rotation only.
- **Forbidden modifications:** Moving scapula/clavicle/shoulder directly;
  translating the chest to fake shoulder motion; altering torso length.
- **Acceptance criteria:** Chest orientation matches intent (neutral/extended/
  flexed/rotated/side-bent); continuous with lumbar; shoulder mounts coherent.

### 2.4 NECK_END (26)
- **Owner:** Group A — Trunk Expert
- **Primary responsibilities:** Cervical orientation; the neck endpoint carrying
  the head.
- **Secondary constraints:** Neutral continuation of the thoracic line unless the
  exercise intends cervical motion; no crane or turtle tuck.
- **Inputs:** Chest frame, cervical intent, head gaze.
- **Outputs:** Neck-end frame; head mount.
- **Allowed modifications:** Neck-end local rotation only.
- **Forbidden modifications:** Moving HEAD_POS directly; altering neck length.
- **Acceptance criteria:** Cervical alignment matches intent; head sits as a
  neutral continuation of the spine.

### 2.5 HEAD_POS (27)
- **Owner:** Group A — Trunk Expert
- **Primary responsibilities:** Head position and gaze direction; final endpoint
  of the trunk chain.
- **Secondary constraints:** Gaze follows the exercise (down/forward for prone,
  up for supine, etc.); no excessive flexion/extension.
- **Inputs:** Neck-end frame, gaze intent.
- **Outputs:** Head world transform.
- **Allowed modifications:** Head local rotation (gaze) only.
- **Forbidden modifications:** Altering neck/cervical chain; translating the
  head independently of the neck.
- **Acceptance criteria:** Head/gaze matches intent; continuous with neck; no
  crane or tuck breaking the spinal line.

### 2.6 CLAVICLE_A (28) / CLAVICLE_P (30)
- **Owner:** Group B — Shoulder Girdle Expert
- **Primary responsibilities:** Clavicular orientation; medial shoulder-girdle
  carriage; level, symmetric collarbone line.
- **Secondary constraints:** Must not ride up toward the neck (trap-dominant
  shrug).
- **Inputs:** Chest frame, scapula state, shoulder intent.
- **Outputs:** Clavicle frames; acromion mounts for SHOULDER.
- **Allowed modifications:** Clavicle local rotation only.
- **Forbidden modifications:** Moving CHEST/SCAPULA/SHOULDER directly; altering
  clavicle length.
- **Acceptance criteria:** Clavicles level and symmetric; coherent with chest
  and scapula; no shrug.

### 2.7 SCAPULA_A (29) / SCAPULA_P (31)
- **Owner:** Group B — Shoulder Girdle Expert
- **Primary responsibilities:** Scapular depression/elevation, protraction/
  retraction, upward/downward rotation; the prime driver of pulling and of
  shoulder stability.
- **Secondary constraints:** Flat on the rib cage — no winging; tracks the
  humerus during press/pull.
- **Inputs:** Chest frame, clavicle state, humeral position, pull/press intent.
- **Outputs:** Scapula frames; glenoid mounts for SHOULDER.
- **Allowed modifications:** Scapula local rotation/translation on the rib cage
  only.
- **Forbidden modifications:** Moving CHEST/CLAVICLE/SHOULDER directly; altering
  scapula geometry.
- **Acceptance criteria:** Scapulae flat (no winging), correctly depressed/
  retracted/protracted per intent; coherent glenoid mounts; symmetric.

### 2.8 SHOULDER_A (12) / SHOULDER_P (13)
- **Owner:** Group B — Shoulder Girdle Expert
- **Primary responsibilities:** Glenohumeral orientation; humeral-head
  centration; the proximal origin of the arm chain.
- **Secondary constraints:** Centered in the socket (no anterior glide, no
  shrug); follows the scapula.
- **Inputs:** Scapula frame, clavicle frame, elbow/hand targets.
- **Outputs:** Shoulder frames; humerus proximal ends.
- **Allowed modifications:** Shoulder local rotation only.
- **Forbidden modifications:** Moving ELBOW/HAND directly; altering humerus
  length; translating the shoulder off the scapula.
- **Acceptance criteria:** Shoulder orientation matches intent; humeral head
  centered; continuous with scapula and elbow.

### 2.9 ELBOW_A (14) / ELBOW_P (20)
- **Owner:** Group C — Arm Expert
- **Primary responsibilities:** Elbow flexion/extension; the mid-arm hinge that
  produces press/depth; tuck/flare angle relative to the torso.
- **Secondary constraints:** Symmetric between sides; angle within natural ROM.
- **Inputs:** Shoulder frame, hand/wrist targets, press intent.
- **Outputs:** Elbow frames; forearm proximal ends.
- **Allowed modifications:** Elbow local rotation only.
- **Forbidden modifications:** Moving SHOULDER/HAND/WRIST directly; altering
  forearm length; translating the elbow off the humerus line.
- **Acceptance criteria:** Elbow flexion matches intent (e.g. push-up top vs
  bottom); tuck/flare correct; symmetric; within ROM.

### 2.10 HAND_A (15) / HAND_P (21)
- **Owner:** Group C — Arm Expert
- **Primary responsibilities:** Hand orientation at the wrist; the distal arm
  endpoint that meets the contact.
- **Secondary constraints:** Palm placement under the intended joint; flat when
  in contact.
- **Inputs:** Elbow frame, wrist state, contact target.
- **Outputs:** Hand frames.
- **Allowed modifications:** Hand local rotation only.
- **Forbidden modifications:** Moving WRIST/PALM/fingers directly; altering hand
  geometry.
- **Acceptance criteria:** Hand orientation matches intent; coherent with wrist
  and elbow; correct placement relative to the contact.

### 2.11 WRIST_A (16) / WRIST_P (22)
- **Owner:** Group C — Arm Expert
- **Primary responsibilities:** Wrist flexion/extension; neutral wrist under
  load.
- **Secondary constraints:** Not bent sharply back or forward under load.
- **Inputs:** Hand frame, forearm state, contact surface.
- **Outputs:** Wrist frames; palm mounts.
- **Allowed modifications:** Wrist local rotation only.
- **Forbidden modifications:** Moving HAND/PALM directly; altering segment
  lengths.
- **Acceptance criteria:** Wrist neutral per intent; coherent with hand and
  palm; load distributed as intended.

### 2.12 PALM_A (17) / PALM_P (23), KNUCKLES_A (18) / KNUCKLES_P (24), FINGERTIPS_A (19) / FINGERTIPS_P (25)
- **Owner:** Group C — Arm Expert
- **Primary responsibilities:** Palm/knuckle/fingertip orientation and the
  contact footprint; finger spread for stability.
- **Secondary constraints:** Flat palm, fingers fanned in contact poses; no
  claw/curl/lift.
- **Inputs:** Wrist frame, contact surface geometry.
- **Outputs:** Distal contact frames.
- **Allowed modifications:** Local rotations of palm/knuckles/fingertips only.
- **Forbidden modifications:** Moving WRIST/HAND; altering segment lengths.
- **Acceptance criteria:** Contact footprint matches intent (flat, fanned,
  fixed); coherent with wrist; no lift/drift.

### 2.13 HIP_F (1) / HIP_B (2)
- **Owner:** Group D — Lower-Body Expert
- **Primary responsibilities:** Hip flexion/extension, abduction/adduction,
  rotation; the proximal origin of each leg chain; the prime mover of hinges,
  squats, lunges.
- **Secondary constraints:** Symmetric when bilateral; tracks the pelvis sockets.
- **Inputs:** Pelvis frame, knee/foot targets, squat/lunge/hinge intent.
- **Outputs:** Hip frames; femur proximal ends.
- **Allowed modifications:** Hip local rotation only.
- **Forbidden modifications:** Moving PELVIS/KNEE/FOOT directly; altering femur
  length; translating the hip off the pelvis socket.
- **Acceptance criteria:** Hip orientation matches intent; continuous with
  pelvis and knee; symmetric when required.

### 2.14 KNEE_F (3) / KNEE_B (7)
- **Owner:** Group D — Lower-Body Expert
- **Primary responsibilities:** Knee flexion/extension; the leg hinge; tracking
  over the foot (no valgus/varus).
- **Secondary constraints:** Symmetric; full extension at lockout (not
  hyperextended); no medial/lateral collapse.
- **Inputs:** Hip frame, ankle/foot targets, squat intent.
- **Outputs:** Knee frames; shank proximal ends.
- **Allowed modifications:** Knee local rotation only.
- **Forbidden modifications:** Moving HIP/ANKLE directly; altering shank length;
  translating the knee off the femur line.
- **Acceptance criteria:** Knee angle matches intent (e.g. squat depth); tracks
  over toes; symmetric; within ROM.

### 2.15 ANKLE_F (4) / ANKLE_B (8)
- **Owner:** Group D — Lower-Body Expert
- **Primary responsibilities:** Ankle dorsiflexion/plantarflexion; the distal
  leg joint that meets the ground.
- **Secondary constraints:** Neutral-to-slightly-plantarflexed under load; no
  sharp bend.
- **Inputs:** Knee frame, foot/heel/toe targets, ground contact.
- **Outputs:** Ankle frames; foot mounts.
- **Allowed modifications:** Ankle local rotation only.
- **Forbidden modifications:** Moving KNEE/FOOT directly; altering segment
  lengths.
- **Acceptance criteria:** Ankle angle matches intent; coherent with knee and
  foot; correct under load.

### 2.16 HEEL_F (5) / HEEL_B (9), TOE_F (6) / TOE_B (10)
- **Owner:** Group D — Lower-Body Expert
- **Primary responsibilities:** Heel/toe orientation and the ground contact
  footprint; toe direction (straight back / parallel).
- **Secondary constraints:** Fixed when planted; correct weight distribution
  (toe/ball vs heel); no slide/lift while loaded.
- **Inputs:** Ankle frame, ground contact geometry, support definition.
- **Outputs:** Distal foot contact frames.
- **Allowed modifications:** Local rotations of heel/toe only.
- **Forbidden modifications:** Moving ANKLE; altering segment lengths.
- **Acceptance criteria:** Foot contact matches intent (toes back/parallel,
  fixed); coherent with ankle; correct weight distribution; no drift.

### 2.17 Global joints — COM / Symmetry / Contacts
- **Owner:** Group E — Global Balance Expert
- **Primary responsibilities:** Whole-body center of mass verification, support-
  polygon / balance checks, left-right symmetry audit, contact stability
  (fixed-plant enforcement), integration sanity.
- **Secondary constraints:** Group E does NOT mutate joint transforms — it only
  *reads* the outputs of Groups A–D and either accepts or rejects/flags.
- **Inputs:** All Group A–D joint world transforms; support definition; posture
  intent.
- **Outputs:** Global validation verdict; COM/symmetry/contact reports.
- **Allowed modifications:** None to joint transforms. May adjust only at the
  integration stage via the agreed reconciliation protocol (§4), and only by
  returning corrections to the owning expert.
- **Forbidden modifications:** Directly writing any joint's local or world
  transform; bypassing the owning expert.
- **Acceptance criteria:** COM within support polygon; planted contacts fixed
  and stable; left/right symmetric; no joint outside natural ROM; integration
  consistent.

---

## 3. Ownership Groups

Each group is a single biomechanical expert owning a disjoint set of joints.

### Group A — Trunk Expert
- **Joints:** PELVIS, LUMBAR, CHEST, NECK_END, HEAD_POS
- **Scope:** The entire spinal column and head — root of the body, pelvic
  anchoring, spine curvature, cervical alignment, gaze.
- **Interfaces:** Provides hip sockets (to Group D) and shoulder-girdle mount
  (to Group B). Consumes posture intent and contact constraints.

### Group B — Shoulder Girdle Expert
- **Joints:** CLAVICLE_A/P, SCAPULA_A/P, SHOULDER_A/P
- **Scope:** Clavicles, scapulae, glenohumeral joints — the mount and prime
  driver for the arms; scapular depression/protraction/retraction; humeral
  centration.
- **Interfaces:** Mounts on CHEST (from Group A). Provides shoulder frames to
  Group C. Drives pulling; follows/carries the thorax consequence.

### Group C — Arm Expert
- **Joints:** ELBOW_A/P, HAND_A/P, WRIST_A/P, PALM_A/P, KNUCKLES_A/P,
  FINGERTIPS_A/P
- **Scope:** The entire arm distal to the shoulder — elbow hinge, hand/wrist
  orientation, distal contact footprint.
- **Interfaces:** Mounts on SHOULDER (from Group B). Produces the hand/footprint
  that Group E checks against the contact.

### Group D — Lower-Body Expert
- **Joints:** HIP_F/B, KNEE_F/B, ANKLE_F/B, HEEL_F/B, TOE_F/B
- **Scope:** Both legs entirely — hip hinge, knee, ankle, foot/ground contact.
- **Interfaces:** Mounts on PELVIS (from Group A). Produces the foot/heel/toe
  contacts that Group E checks.

### Group E — Global Balance Expert
- **Joints:** None (read-only over all joints)
- **Scope:** COM, support polygon/balance, symmetry, contact stability,
  integration reconciliation, final validation.
- **Interfaces:** Reads all Group A–D world transforms. Does not own joints;
  returns correction requests to the owning expert at the integration stage.

---

## 4. Reasoning Order

The experts solve in the following sequence; each stage consumes the frozen
outputs of the prior stage and never reaches back to modify an earlier expert's
joints.

1. **Trunk (Group A).** Solve PELVIS → LUMBAR → CHEST → NECK_END → HEAD_POS.
   Establish the root frame, spine curvature, pelvic anchoring, and gaze. This is
   the foundation everything else mounts on.
2. **Lower body (Group D).** Solve HIP → KNEE → ANKLE → HEEL/TOE, mounting on
   the pelvis from stage 1. Produces the feet/ground contacts.
3. **Shoulder girdle (Group B).** Solve CLAVICLE → SCAPULA → SHOULDER, mounting
   on the chest from stage 1. Establishes the scapular driver and humeral
   mounts.
4. **Arms (Group C).** Solve ELBOW → HAND → WRIST → PALM/KNUCKLES/FINGERTIPS,
   mounting on the shoulders from stage 3. Produces the hand/footprint contacts.
5. **Integration (Group E).** With all Group A–D transforms frozen, Group E
   reads the full skeleton: verifies COM within the support polygon, confirms
   planted contacts are fixed and stable, audits left/right symmetry, and checks
   every joint against natural ROM. Any defect is returned as a correction
   request to the owning expert, who re-solves *only its own joints*; Group E
   re-verifies. This loop terminates when all criteria pass.
6. **Validation.** Final acceptance against the canonical Biomechanical Pose
   Specification (BPS) checkpoints for the exercise. The pose is accepted only
   if every BPS criterion and every Group E global check is satisfied.

---

## 5. Invariants (enforced by the matrix)

- **One owner per joint.** No joint appears in two groups.
- **No shared ownership.** A joint has exactly one expert; cross-joint effects
  are communicated only through the mount frames passed between stages.
- **No expert modifies another's joints.** Group B never writes CHEST; Group C
  never writes SHOULDER; Group D never writes PELVIS; Group E never writes any
  joint directly.
- **Integration only after all experts finish.** Group E runs strictly after
  stages 1–4 complete; its only mutating path is the reconciliation loop that
  returns corrections to the owning expert.
- **Mount continuity.** Each downstream expert receives a frozen parent frame and
  must produce a child frame continuous with it (no gaps, no segment-length
  changes).

---

## 6. Resolved Design Decisions

The ownership model is implemented and current. The following were settled as part of adoption:

- Group E's reconciliation loop is **bounded** (max N iterations) and falls back to a
  flagged-invalid pose when it cannot converge.
- Paired-side symmetry (A vs P) is enforced in Group E, not inside Groups B/C/D.
- Mount-frame hand-off between stages uses the existing skeleton hierarchy; this document
  makes ownership explicit without changing the hand-off format.

> This document is documentation only. No engine code, joint definitions, or pose
> files are modified by it.
