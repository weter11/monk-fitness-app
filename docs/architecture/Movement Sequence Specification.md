# Movement Sequence Specification (MSS)

> **Status:** Architecture document (implementation-independent; documentation only).
>
> **Purpose.** This document defines the canonical *sequence* in which human
> movement propagates through the body.
>
> Unlike:
> - **BPS** (which describes the final posture),
> - **JOM** (which defines joint ownership),
> - **MOM** (which defines movement ownership),
>
> the MSS defines the **ORDER OF MOVEMENT**. It specifies which body regions move
> first, which react, which stabilize, and which finish the motion.
>
> This document describes **only biomechanics**. It must remain valid even if the
> entire rendering system is rewritten. It reads like a biomechanics textbook, not
> software documentation.

---

## §1 Philosophy

Human movement is **sequential**. It is never the case that all joints change
simultaneously — that would be a teleport, not a motion. Instead, movement
*propagates*: one region leads, neighboring regions react, distant regions
stabilize, and the chain settles into the end posture.

Every movement, regardless of type, decomposes into the same six phases:

1. **Preparation** — the body sets its support and braces; stabilizers engage.
2. **Initiation** — the primary driver begins to move (see MOM).
3. **Propagation** — the motion travels along the kinetic chain (proximal →
   distal, driver → followers).
4. **Stabilization** — non-moving regions hold so the moving region can act.
5. **Completion** — the end posture / target is reached.
6. **Recovery** — the return to the start (or to the next rep), in reverse or
   re-initiated order.

**Why this minimizes energy and looks natural.** A sequential chain lets the
large, proximal masses do the work first; distal segments fine-tune with far less
effort. Simultaneous motion wastes energy (small muscles fighting large inertias)
and reads as unnatural because real bodies never do it. Sequencing is what makes
a movement *look* like a movement rather than a pose swap.

---

## §2 Universal Movement Phases

### Preparation
- **Purpose:** establish a stable base and pre-tension the stabilizers.
- **Typical behaviour:** feet/hands plant; pelvis and trunk brace; breath sets.
- **Dominant structures:** supporting contacts (feet/hands), pelvis, trunk
  extensors, scapular stabilizers.

### Initiation
- **Purpose:** begin the movement at the true driver (MOM primary owner).
- **Typical behaviour:** the driver segment rotates/translates first; everything
  else is still.
- **Dominant structures:** the single primary driver (e.g. hip in a hinge,
  scapula in a pull, chest in a push).

### Propagation
- **Purpose:** carry the motion through the kinetic chain to the distal endpoint.
- **Typical behaviour:** each successive proximal→distal segment joins the motion
  with a small lag; contributors assist.
- **Dominant structures:** contributors and passive followers in chain order.

### Stabilization
- **Purpose:** keep non-moving regions fixed so the moving chain is effective.
- **Typical behaviour:** trunk/pelvis/scapula resist unwanted motion; balance is
  held.
- **Dominant structures:** trunk, pelvis, scapula, and the planted base.

### Completion
- **Purpose:** arrive at the target end posture.
- **Typical behaviour:** the distal endpoint reaches its target; the chain settles.
- **Dominant structures:** the distal endpoint (hand/foot/head) plus the still-
  stabilizing base.

### Recovery
- **Purpose:** return to the start or transition to the next repetition.
- **Typical behaviour:** the sequence reverses or re-initiates from the driver;
  the base stays controlled.
- **Dominant structures:** same chain, reversed or re-led by the driver.

---

## §3 Canonical Propagation Rules

The general propagation hierarchy (base → distal) is:

```
Ground
   ↓
Feet
   ↓
Ankles
   ↓
Knees
   ↓
Hips
   ↓
Pelvis
   ↓
Spine
   ↓
Scapula
   ↓
Shoulders
   ↓
Elbows
   ↓
Wrists
   ↓
Hands
```

**Guiding rules:**

- **Proximal before distal.** The hip moves before the knee, the shoulder before
  the elbow, the elbow before the wrist. Distal segments finish what proximal
  segments start.
- **Large mass before small mass.** The pelvis and trunk (large inertia) lead;
  hands and feet (small inertia) refine.
- **Stable base before moving limb.** The supporting contacts and trunk must be set
  before a limb is thrown.
- **Balance before reach.** The body must be balanced (COM over base) before a
  distal segment reaches away from it.

---

## §4 Stabilization Rules

A region must be stabilized *before* the region it supports begins to move.
Concrete cases:

- **Pelvis stabilizes before the arm reaches.** The trunk anchor is set, then the
  shoulder/elbow/hand move.
- **Scapula stabilizes before shoulder elevation.** The girdle is set (depressed/
  retracted as needed), then the humerus elevates.
- **Core stabilizes before leg lift.** The trunk/pelvis brace, then the hip drives
  the leg.
- **Foot stabilizes before hip extension.** The foot/ankle plants and braces, then
  the hip extends (e.g. a step-up or a swing).
- **Trunk stabilizes before neck rotation.** The spine is held, then the cervical
  segment rotates (neck rotation does not drag the thorax).
- **Supporting hand/foot stabilizes before the contralateral limb moves.** In
  bird-dog / mountain-climber, the planted contacts and trunk hold before the
  reaching limb travels.

Stabilization is not "no movement" — it is *controlled, purposeful* stillness in
the regions that must not move, so the moving regions can.

---

## §5 Exercise Movement Sequences

For each family, the complete order is given as:

```
Preparation
   ↓
Initiation
   ↓
Primary propagation
   ↓
Secondary propagation
   ↓
End posture
   ↓
Return sequence
```

### Push-Up
- **Preparation:** hands + toes plant; trunk braces (plank).
- **Initiation:** chest/trunk begins to descend (driver: shoulder + elbow, chest
  leads the rigid plank).
- **Primary propagation:** elbows flex; scapulae track the humerus.
- **Secondary propagation:** spine/pelvis/hips/knees/ankles stay rigid; head
  stays neutral; wrists/hands stay planted.
- **End posture:** chest near floor, elbows ~45–90°, plank intact.
- **Return sequence:** chest drives up (elbows extend) → lockout plank.

### Pull-Up
- **Preparation:** hands fix on bar; shoulders depress; trunk braces (hang).
- **Initiation:** scapulae depress + retract (body rises, arms straight).
- **Primary propagation:** as reach shortens, elbows flex; chest leads to bar.
- **Secondary propagation:** spine/pelvis/hips hang rigid; head neutral.
- **End posture:** chin over bar, scapulae retracted.
- **Return sequence:** elbows extend → scapulae ease → dead hang.

### Dead Hang
- **Preparation:** hands fix on bar; shoulders depress.
- **Initiation:** none (static) — maintain scapular depression + trunk brace.
- **Primary propagation:** gentle breathing sway in trunk/legs.
- **Secondary propagation:** arms stay extended; pelvis/hips hang.
- **End posture:** stable extended hang.
- **Return sequence:** held (no rep) or eased out.

### Plank
- **Preparation:** forearms/hands + toes plant; trunk braces.
- **Initiation:** none (static) — establish rigid shoulder–hip–ankle line.
- **Primary propagation:** maintain trunk/pelvis rigidity; scapulae set.
- **Secondary propagation:** hips/knees/ankles extend; head neutral; hands
  planted.
- **End posture:** straight rigid plank.
- **Return sequence:** held (no rep).

### Bird Dog
- **Preparation:** hands + knees plant; trunk/pelvis brace (level).
- **Initiation:** one arm's shoulder + opposite leg's hip begin to reach.
- **Primary propagation:** reaching arm extends forward; reaching leg extends back.
- **Secondary propagation:** spine/pelvis stay level (NO twist); supporting
  limbs stay planted; head neutral.
- **End posture:** contralateral arm/leg extended, trunk rigid.
- **Return sequence:** limbs return to quadruped → other side.

### Dead Bug
- **Preparation:** back + pelvis on floor; trunk braces (low back flat).
- **Initiation:** one arm's shoulder + opposite leg's hip begin to lower.
- **Primary propagation:** arm reaches overhead; leg lowers toward floor.
- **Secondary propagation:** low back stays pressed; pelvis stays posterior-tilted;
  opposite limbs stay up.
- **End posture:** contralateral limb pair lowered, core braced.
- **Return sequence:** limbs return to start → other side.

### Squat
- **Preparation:** feet plant; trunk braces; pelvis set.
- **Initiation:** hips begin to descend (femur rotates back).
- **Primary propagation:** knees flex; ankles dorsiflex; trunk leans to balance.
- **Secondary propagation:** spine stays rigid; head neutral; feet stay planted.
- **End posture:** thighs at/below parallel, chest up.
- **Return sequence:** hips drive extension → knees/ankles follow → stand.

### Jump Squat
- **Preparation / Initiation / Primary:** same as squat to depth.
- **Secondary propagation:** trunk braces hard at depth.
- **End posture (bottom):** full squat, coiled.
- **Return sequence:** explosive hip + knee + ankle extension (triple extension) →
  leave ground → land softly into squat.

### Lunge
- **Preparation:** stance foot plants; trunk braces; pelvis level.
- **Initiation:** lead hip begins to step/descend; trail hip extends.
- **Primary propagation:** lead knee flexes; lead ankle dorsiflexes; trunk stays
  upright.
- **Secondary propagation:** spine rigid; trail leg extends; arms counter.
- **End posture:** deep lunge, front thigh loaded.
- **Return sequence:** lead hip drives up → feet together.

### Step-Up
- **Preparation:** both feet on ground; trunk braces.
- **Initiation:** lead hip drives onto the box (foot plants on box).
- **Primary propagation:** lead knee/ankle extend; trail hip flexes to follow.
- **Secondary propagation:** trunk balances; trail foot lifts.
- **End posture:** standing on box, both feet up.
- **Return sequence:** trail foot steps down → lead follows.

### Hip Hinge
- **Preparation:** feet plant; trunk braces; pelvis set.
- **Initiation:** hips begin to rotate back (femur travels).
- **Primary propagation:** trunk folds forward over the hips; knees slight bend.
- **Secondary propagation:** spine stays rigid (no round); head neutral; feet
  planted.
- **End posture:** hinged forward, torso angled, back flat.
- **Return sequence:** hips drive extension → trunk rises.

### Glute Bridge
- **Preparation:** upper back + feet plant; pelvis set.
- **Initiation:** hips begin to extend upward.
- **Primary propagation:** pelvis lifts; thoracic follows slightly; knees/ankles
  stay planted.
- **Secondary propagation:** trunk extensors hold; shoulders stay down.
- **End posture:** shoulders–hip–knee straight line, hips extended.
- **Return sequence:** hips lower to floor.

### Wall Slide
- **Preparation:** back against wall; feet planted; trunk braces.
- **Initiation:** scapulae depress + retract as arms begin to slide.
- **Primary propagation:** shoulders elevate along wall; elbows/wrists follow.
- **Secondary propagation:** low back stays on wall; pelvis neutral; head neutral.
- **End posture:** arms raised overhead on wall.
- **Return sequence:** arms slide back down.

### Face Pull
- **Preparation:** stance feet plant; trunk braces; scapulae set.
- **Initiation:** scapulae retract + depress; shoulders begin external rotation.
- **Primary propagation:** elbows flex, drawing hands toward the face.
- **Secondary propagation:** trunk rigid; head neutral; wrists/hands follow the
  band.
- **End posture:** hands near temples, elbows high/wide.
- **Return sequence:** elbows extend → return to start.

### Scapular Retraction
- **Preparation:** stance set; trunk braces.
- **Initiation:** scapulae retract + depress.
- **Primary propagation:** shoulders set; thoracic follows slightly.
- **Secondary propagation:** arms relax; spine rigid; head neutral.
- **End posture:** blades squeezed, chest open.
- **Return sequence:** scapulae ease forward.

### Thoracic Extension
- **Preparation:** support set (roller/floor); trunk/pelvis brace.
- **Initiation:** thoracic spine begins to extend.
- **Primary propagation:** chest lifts; scapulae depress.
- **Secondary propagation:** lumbar stays neutral; pelvis stable; head follows
  gently.
- **End posture:** upper back arched, low back flat.
- **Return sequence:** thorax eases to neutral.

### Thoracic Rotation
- **Preparation:** base set (quadruped or seated); trunk braces.
- **Initiation:** thoracic spine begins to rotate.
- **Primary propagation:** scapula + one arm open; pelvis counter-slightly.
- **Secondary propagation:** lumbar stable; neck follows; opposite shoulder
  stabilizes.
- **End posture:** thorax rotated, arm reached.
- **Return sequence:** thorax rotates back → other side.

### Mountain Climber
- **Preparation:** hands + toes plant; trunk braces (plank).
- **Initiation:** one hip drives the knee toward the chest.
- **Primary propagation:** knee flexes; foot lifts; opposite leg stays extended.
- **Secondary propagation:** trunk stays rigid/level; shoulders/elbows hold;
  head neutral.
- **End posture:** one knee driven up.
- **Return sequence:** leg returns → other side drives.

### Burpee
- **Preparation:** stance set; trunk braces.
- **Initiation:** hips descend into squat; hands reach to floor.
- **Primary propagation:** feet extend back (plank); chest/trunk hold; (push-up
  optional).
- **Secondary propagation:** trunk rigid; hands planted; head neutral.
- **End posture (bottom):** plank / squat-thrust.
- **Return sequence:** feet return forward → explosive hip+knee+ankle extension →
  jump, arms overhead.

### Kettlebell Swing
- **Preparation:** feet plant; trunk braces; hinge set.
- **Initiation:** hips hinge back (load between legs).
- **Primary propagation:** trunk folds; shoulders hang the load; arms pendulum.
- **Secondary propagation:** spine rigid; head neutral; knees slight.
- **End posture (bottom):** loaded hinge.
- **Return sequence:** explosive hip extension → load swings to chest height →
  hinge back.

### Hip CARs
- **Preparation:** stance foot plants; trunk/pelvis brace.
- **Initiation:** hip begins the circle (flexion).
- **Primary propagation:** femur travels flexion → abduction → extension →
  adduction.
- **Secondary propagation:** trunk rigid on stance leg; head neutral; reaching
  leg follows the hip.
- **End posture:** full circumduction completed.
- **Return sequence:** repeat circle.

### Arm Circles
- **Preparation:** stance set; trunk braces; scapulae set.
- **Initiation:** shoulders begin to circumduct.
- **Primary propagation:** arms sweep in the circle; scapulae stabilize.
- **Secondary propagation:** trunk rigid; head neutral; elbows extend.
- **End posture:** circle completed.
- **Return sequence:** reverse or repeat.

### Leg Raise
- **Preparation:** back + pelvis on floor; trunk braces (low back flat).
- **Initiation:** hips begin to flex (thighs toward ceiling).
- **Primary propagation:** legs lift as a unit; knees extend (or hold slight
  bend).
- **Secondary propagation:** low back stays pressed; pelvis posterior-tilted;
  head neutral.
- **End posture:** legs raised ~90°, trunk flat.
- **Return sequence:** legs lower without low back lifting.

### Lat Stretch
- **Preparation:** hang/anchor set; trunk braces.
- **Initiation:** shoulders begin to elevate/reach overhead.
- **Primary propagation:** arms reach; latissimus lengthens down the side.
- **Secondary propagation:** spine follows gently; pelvis stable; head neutral.
- **End posture:** stretched lat, arms overhead.
- **Return sequence:** ease out of stretch.

### Hamstring Stretch
- **Preparation:** seated/standing base set; trunk braces.
- **Initiation:** hip begins to hinge forward.
- **Primary propagation:** torso folds over the extended (knee-straight) leg.
- **Secondary propagation:** spine hinges from hip (no round); head neutral;
  foot/ankle stable.
- **End posture:** chest toward straight leg, hamstring lengthened.
- **Return sequence:** torso rises to neutral.

---

## §6 Timing Principles

Not every joint starts at the same instant. Real movement contains small, lawful
delays:

- **Lead.** The driver moves first (Initiation). It is the earliest visible
  motion.
- **Lag.** Each successive segment in the chain begins a fraction later. The knee
  lags the hip; the elbow lags the shoulder; the wrist lags the elbow. This lag is
  what makes the chain *flow* rather than snap.
- **Follow.** Passive followers move only because they are mounted distal to an
  active segment; they have no independent start time.
- **Settling.** At Completion, segments with larger inertia (trunk, pelvis) arrive
  and settle slightly after or with the distal endpoint, giving a natural "arrival"
  rather than a rigid stop.

These micro-delays are not errors — they are the signature of natural human
motion. A sequence in which every joint moves on the same frame is mechanically
and visually wrong.

---

## §7 Failure Patterns

Common sequencing mistakes (each is a violation of §3–§4):

- **Elbows flex before the chest descends.** In a push-up the plank (chest) must
  lead; bending the elbows first is a distal segment leading.
- **Head moves before the spine.** The cervical segment should follow the thoracic;
  a head that cranes first drags the neck out of order.
- **Hands reach before the scapula rotates.** In a pull/reach the scapula must lead
  (MOM); reaching with the hand first is a follower initiating.
- **Knees initiate a squat before the hips.** The squat begins at the hip; knees
  diving first is a distal-led fault.
- **Foot rotates before the pelvis shifts.** In a step/lunge the pelvis/hip leads
  the weight transfer; the foot scrambling first is out of order.
- **Shoulders elevate before thoracic extension.** In a cobra/thoracic opener the
  thoracic spine extends first; shrugging the shoulders first is a girdle leading a
  spinal movement.
- **Lumbar bends before the pelvis tilts.** In a hinge the pelvis/hip leads; the
  low back rounding first is a follower initiating and usually a compensation.
- **Wrist bends before the elbow/hand path is set.** The wrist is the last link;
  flexing it early to "help" a reach is out of sequence.

Each failure shares one cause: a segment moving *earlier* than its place in the
proximal-to-distal, driver-first order.

---

## §8 Acceptance Rules

A movement sequence is valid **only if** every phase occurs in biomechanically
correct order.

- **Correct posture alone is insufficient.** A pose can reach the right end shape
  through the wrong sequence (e.g. elbow-first push-up that ends in the right
  plank) and still be wrong.
- **Correct ownership alone is insufficient.** Knowing *who* owns the movement
  (MOM) does not guarantee it *propagated in the right order* (MSS).
- **Correct sequencing is mandatory.** The Preparation → Initiation →
  Propagation → Stabilization → Completion → Recovery order, with the lawful lags of
  §6 and the prohibitions of §7, must hold.

A sequence that violates ordering is rejected even if its endpoints match BPS.

---

## §9 Relationship to the Architecture

The complete biomechanical specification is formed by five complementary documents:

- **BPS** defines the *destination* — what the correct final posture looks like.
- **JOM** defines *joint ownership* — who builds each joint.
- **MOM** defines *movement ownership* — which segment drives each movement.
- **MSS** defines *movement sequence* — the order in which motion propagates.
- **VOM** validates *the sequence and features* — who certifies each domain.
- **PAC** accepts *the implementation* — the measurable completion criteria.

Together these documents form the complete, implementation-independent biomechanical
specification of every pose. BPS is the target, JOM/MOM/MSS are the
structural/dynamic contracts, VOM is the validation, and PAC is the acceptance
gate.

---

## Document Acceptance (this file)

- Fully implementation-independent: names no engine, language, data carrier, joint-
  intent system, pose builder, or code construct.
- Remains valid if the rendering system is rewritten from scratch.
- Reads as a biomechanics textbook: every statement is about human segments,
  kinetic chains, phases, and timing — not software.
