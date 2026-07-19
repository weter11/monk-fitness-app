# Movement Ownership Matrix (MOM)

> **Status:** Architecture document (implementation-independent; documentation only).
>
> **Purpose.** This document defines *movement ownership*. Unlike the Joint
> Ownership Matrix (JOM), which assigns *joints* to owners, the MOM assigns
> *movements* to owners: it states which body segment is responsible for
> initiating and controlling each biomechanical movement.
>
> This document describes **only human biomechanics**. It must remain valid even
> if the entire rendering system behind it is rewritten from scratch. It reads
> like a biomechanics textbook, not software documentation.

---

## §1 Philosophy

The body does not move by every joint changing simultaneously. Real human motion
is *organized*: a small number of structures lead, and the rest respond. Every
movement has a clear internal structure:

- **One primary driver** — the segment that *initiates* and *owns* the movement.
  It moves first and sets the pattern.
- **Several contributors** — segments that actively assist the driver, adding
  force or range, but do not lead.
- **Several stabilizers** — segments that resist unwanted motion so the driver can
  act effectively (e.g. the trunk staying rigid while a limb moves).
- **Many passive followers** — segments carried along by the chain because they
  are distal to or mounted on the driver; they adapt, they do not decide.

**Only the driver owns the movement.** A follower that begins to lead is a
movement fault, even if the result superficially resembles the intended shape.
Identifying the true driver is the core skill this document supports.

---

## §2 Movement Hierarchy

Gross human movement flows through a consistent proximal-to-distal chain. The
general hierarchy is:

```
Whole body
   ↓
Pelvis
   ↓
Spine
   ↓
Scapula
   ↓
Shoulder
   ↓
Elbow
   ↓
Wrist
   ↓
Hand
```

and, for the lower body:

```
Pelvis
   ↓
Hip
   ↓
Knee
   ↓
Ankle
   ↓
Foot
```

**Why proximal-to-distal.** The pelvis and spine are the body's foundation; they
establish orientation, balance, and the frame every limb mounts on. The shoulder
girdle and hip are the next proximal links, generating and directing force. The
elbow/knee and wrist/ankle are distal refinements. Motion that *begins* proximally
is efficient, stable, and readable; motion that begins distally (a hand yanking, a
foot scrambling) is a compensation. Distal segments finish a movement that
proximal segments started.

---

## §3 Movement Ownership Matrix

For each major movement below: the **Primary owner** initiates and controls it; the
**Contributors** assist; the **Passive followers** are carried; **Must NOT
initiate** lists segments that must never lead that movement.

| Movement | Primary owner | Contributors | Passive followers | Must NOT initiate |
|---|---|---|---|---|
| **Hip hinge** | Hip (femur) | Pelvis (controlled tilt), trunk extensors | Spine (rigid), knees (slight), ankles | Lumbar spine, knees (excess), shoulders |
| **Squat** | Hip (descent) | Knee, ankle, trunk (lean), pelvis | Spine (rigid), feet | Lumbar, knee-alone, ankle-collapse |
| **Push** (horizontal) | Shoulder + Elbow (chest leads) | Scapula, trunk (rigid plank) | Spine (rigid), pelvis, hips, knees, ankles | Wrist, hand, head, lumbar |
| **Pull** (vertical) | Scapula (first), then Shoulder/Elbow | Lat/arm, trunk (rigid hang) | Spine (rigid), pelvis, hips, knees | Wrist, hand, head, lumbar |
| **Reach overhead** | Shoulder (glenohumeral) | Scapula (upward rotation), thoracic (slight ext) | Spine (follows), pelvis, ribs | Wrist, hand, lumbar (excess) |
| **Horizontal reach** | Shoulder | Scapula, thoracic (slight rotation) | Spine (follows), pelvis | Wrist, hand, lumbar (excess) |
| **Rotation** (axial) | Thoracic spine (or lumbar, by pattern) | Pelvis (counter), scapula, neck (follow) | Hips, shoulders, arms | Knee, ankle, wrist, head (leading) |
| **Lunge** | Hip (lead, descent) | Knee (lead), trunk (upright), pelvis | Spine (rigid), rear leg, ankle, foot | Lumbar, shoulder, wrist |
| **Step** | Hip (lead drive) + Hip (trail) | Knee, ankle, trunk (balance) | Spine (rigid), arms (counter-swing) | Lumbar, shoulder, wrist |
| **Bridge** | Hip (extension) | Thoracic (slight), trunk extensors | Spine (follows), knees, ankles, shoulders | Lumbar (excess arch), shoulders (shrug) |
| **Hang** | Shoulder (depression) + Scapula | Trunk (rigid), pelvis | Spine (rigid), hips, knees, ankles | Wrist, hand, head, lumbar |
| **Plank stabilization** | Trunk (rigid: spine + pelvis) | Shoulder girdle, hip extensors | Spine (rigid), shoulders, elbows, hips, knees, ankles | Any joint moving, wrist, head |
| **Walking** | Hip (alternating) + Pelvis (transfer) | Knee, ankle, trunk (balance) | Spine (rigid), arms (swing) | Lumbar, shoulder, wrist |
| **Running** | Hip (drive) + Knee (cycle) | Ankle (push-off), trunk | Spine (rigid), arms | Lumbar, shoulder, wrist |
| **Jump landing** | Hip + Knee (eccentric) | Ankle (absorb), trunk (brace) | Spine (rigid), pelvis | Lumbar, shoulder, wrist |
| **Bird Dog** | Trunk (rigid: spine + pelvis) | Reaching arm shoulder, reaching leg hip | Spine (rigid), opposite limbs (follow) | Any twisting, lumbar, shoulder (shrug) |
| **Dead Bug** | Trunk (rigid: spine + pelvis) | Reaching arm shoulder, reaching leg hip | Spine (rigid), opposite limbs | Lumbar (arch), shoulder (shrug) |
| **Scapular retraction** | Scapula | Thoracic (follow), shoulder (set) | Spine (rigid), arms (relaxed) | Wrist, elbow, lumbar |
| **Scapular elevation** | Scapula (elevators) | Shoulder (set) | Spine (rigid), neck (follow) | Wrist, elbow, lumbar |
| **Thoracic extension** | Thoracic spine | Scapula (depress), trunk extensors | Lumbar (stable), pelvis, shoulders | Lumbar (excess), head (crane) |
| **Thoracic rotation** | Thoracic spine | Scapula, pelvis (slight counter) | Lumbar (stable), neck (follow), shoulders | Lumbar (excess), knee, wrist |
| **Neck rotation** | Cervical spine (neck) | Thoracic (slight follow), scapula (set) | Shoulders, trunk (rigid) | Lumbar, shoulder, wrist |
| **Foot plant** | Foot/Ankle (contact) | Knee (absorb), hip (transfer) | Spine (rigid), pelvis | Lumbar, shoulder, wrist, head |
| **Grip** | Hand (fingers/palm) | Wrist (neutral), forearm | Arm (follows), shoulder | Shoulder, lumbar, pelvis |

---

## §4 Driver Rules

The ownership of a movement is strict and directional:

- **Only the owner starts movement.** The primary driver initiates; nothing distal
  or unrelated precedes it.
- **Contributors assist.** They add to the driver's action once it has begun;
  they do not lead.
- **Followers adapt.** Passive followers conform to the motion created by the
  driver and contributors; they are carried, not in command.
- **Never the opposite.** A follower that begins to lead — a wrist driving a
  shoulder movement, a foot scrambling to fix a pelvis problem — is a fault. The
  chain must run proximal-to-distal, driver-first.

If a movement "works" only because a follower is frantically compensating, the
movement is incorrectly owned, regardless of the final silhouette.

---

## §5 Common Anti-Patterns

Incorrect movement ownership produces recognizable faults:

- **Moving the head to fix balance.** The head (a follower) should stay a neutral
  continuation of the spine. Craning or tucking the head to "regain balance"
  masks a pelvis/trunk instability instead of fixing it.
- **Driving shoulder movement from the wrist.** The wrist is the distal endpoint.
  Reaching by flicking the wrist leads with the follower; the shoulder should
  initiate.
- **Moving the feet to compensate for pelvis instability.** In a plank or stance,
  the pelvis/trunk should be rigid. Shuffling the feet to stay up is a follower
  rescuing a failed driver.
- **Driving thoracic extension from lumbar extension.** The lumbar should stay
  stable while the thoracic spine extends (e.g. cobra, thoracic opener). Arching
  the low back instead is a proximal segment falsely driving a thoracic movement.
- **Driving a squat from knee flexion instead of hip descent.** The squat begins
  at the hips; the knees and ankles follow. Leading with the knees (knees diving
  forward, hips lagging) is a distal-led fault.
- **Driving push-up depth from elbow bend instead of chest descent.** In a push-up
  the rigid plank (trunk) lowers as a unit while the elbows flex; depth comes from
  the chest/trunk traveling toward the floor, not from the elbows collapsing
  independently. Bending only the elbows while the trunk stays high is a follower
  leading.
- **Compensating a pull with the wrists/hands.** A pull is led by the scapulae
  then the shoulder/elbow; yanking with the hands or bending the wrists to "finish"
  the pull is a follower leading.

Each anti-pattern shares one root cause: a **non-driver segment is initiating
motion that belongs to a more proximal owner.**

---

## §6 Exercise Examples

For each major family, the movement chain is given as:

```
Movement phase
   ↓
Primary owner
   ↓
Contributors
   ↓
Followers
```

### Push-Up
- **Phase: descent / press** — Primary: Shoulder + Elbow (chest leads the
  rigid plank down/up). Contributors: Scapula (track humerus), trunk extensors
  (rigid plank). Followers: Spine (rigid), Pelvis, Hip, Knee, Ankle, Foot (fixed
  supports), Head (neutral), Wrist/Hand (fixed plant).
- **Depth is owned by the chest/trunk traveling toward the floor, not by the
  elbows bending alone.**

### Pull-Up
- **Phase: initiation (scapular pull)** — Primary: Scapula (depress + retract,
  body rises with arms straight). Contributors: Thoracic (follow), trunk (rigid
  hang). Followers: Shoulder, Elbow (barely flex yet), Spine (rigid), Pelvis,
  Hips, Knees, Ankles (hanging).
- **Phase: completion** — Primary: Shoulder + Elbow (chest leads to bar).
  Contributors: Scapula (retract), Lat. Followers: Spine (rigid), pelvis, legs.

### Squat
- **Phase: descent** — Primary: Hip (femur rotates, pelvis hinges). Contributors:
  Knee (flexes), Ankle (dorsiflexes), trunk (leans to balance). Followers: Spine
  (rigid), Foot (planted), Head (neutral).
- **Phase: ascent** — Primary: Hip (drive extension). Contributors: Knee, Ankle,
  trunk extensors. Followers: Spine, foot, head.

### Lunge
- **Phase: step + descent** — Primary: Hip (lead leg descends; trail leg extends).
  Contributors: Knee (lead), Ankle (lead), trunk (upright), Pelvis (level).
  Followers: Spine (rigid), trail leg, trail foot, arms.
- **Phase: drive up** — Primary: Hip (lead drive). Contributors: Knee, Ankle.
  Followers: trunk, trail leg.

### Dead Hang
- **Phase: hang (static)** — Primary: Shoulder (depression, centration) + Scapula
  (depressed). Contributors: Trunk (rigid), Pelvis (stable). Followers: Spine
  (rigid), Hips, Knees, Ankles (hanging), Head (neutral), Wrist/Hand (fixed
  on bar).

### Bird Dog
- **Phase: reach** — Primary: Trunk (rigid: spine + pelvis held level). Contributors:
  reaching arm's Shoulder, reaching leg's Hip. Followers: Spine (rigid, no twist),
  opposite limbs, Head (neutral).

### Wall Slide
- **Phase: slide up/down** — Primary: Scapula (depression + retraction as arms
  slide). Contributors: Shoulder (glenohumeral, follows the wall), Thoracic
  (extension maintained). Followers: Spine (rigid against wall), Pelvis, Elbow,
  Wrist/Hand (on wall), Head (neutral).

### Hip CARs
- **Phase: controlled articular rotation** — Primary: Hip (femur traces full
  circle). Contributors: Trunk (rigid on stance leg), Pelvis (stable). Followers:
  Spine (rigid), stance Knee/Ankle, Head (neutral), reaching leg follows the hip.

### Face Pull
- **Phase: pull to face** — Primary: Scapula (retraction + depression) then
  Shoulder (external rotation). Contributors: Elbow (flexes, drives hands to
  temples), trunk (rigid stance). Followers: Spine (rigid), Wrist/Hand (follow
  the band), Head (neutral), Pelvis, Hips, Knees, Ankles (stance).

### Kettlebell Swing
- **Phase: hinge (load)** — Primary: Hip (posterior-chain hinge, femur rotates
  back). Contributors: Trunk (folds forward over hips), Knee (slight), Ankle.
  Followers: Spine (rigid, no round), Shoulders (hang the load), Arms (pendulum),
  Head (neutral).
- **Phase: drive (top)** — Primary: Hip (explosive extension). Contributors:
  Knee, Ankle, trunk extensors. Followers: Spine (tall), Shoulders (load to
  height), Arms, Head.

### Burpee
- **Phase: squat-thrust to plank** — Primary: Hip (descent) → then Trunk (rigid
  plank as feet extend). Contributors: Knee, Ankle, Shoulder + Elbow (support
  plank), trunk extensors. Followers: Spine (rigid), Hands/Feet (planted), Head.
- **Phase: jump up** — Primary: Hip + Knee + Ankle (triple extension). Contributors:
  trunk (brace), Shoulder (arms swing overhead). Followers: Spine, Head, Arms.

### Mountain Climber
- **Phase: knee drive (alternating)** — Primary: Hip (femur drives knee toward
  chest). Contributors: Trunk (rigid plank), Shoulder + Elbow (support),
  opposite Hip (extends). Followers: Spine (rigid, level), Hands/Feet (planted),
  Head, Knee (follows the hip).

---

## §7 Relationship to BPS, JOM, and VOM

These seven documents are complementary and non-overlapping:

- **BPS** (Biomechanical Pose Specification) defines *what correct posture looks
  like* — the human-biomechanics target for each exercise, with checkpoints.
- **JOM** (Joint Ownership Matrix) defines *who owns each joint* when building
  the pose — the static structural ownership.
- **MOM** (Movement Ownership Matrix) defines *who owns each movement* — the
  dynamic, proximal-to-distal driver of every motion.
- **VOM** (Validation Ownership Matrix) defines *who validates each movement and
  feature* — the certification domains and their order.

BPS says *what*; JOM says *who builds the joints*; MOM says *who drives the
motion*; VOM says *who checks it*. Together they decompose both authoring and
auditing into independent, deterministic domains with no shared ownership.

---

## Acceptance Criteria (document-level)

- The document is **completely implementation-independent**: it names no engine, no
  programming language, no data carriers, no joint-intent system, no pose builder,
  no code, and no software construct of any kind.
- It **remains valid even if the entire rendering system is rewritten from
  scratch**, because it describes only human biomechanics.
- It **reads like a biomechanics textbook** rather than software documentation:
  every statement is about joints, muscles, bones, segments, and movement
  organization in the human body.
