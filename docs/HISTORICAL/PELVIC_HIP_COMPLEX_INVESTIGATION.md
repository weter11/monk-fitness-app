# PELVIC / HIP COMPLEX INVESTIGATION — focused deep-dive

> **HISTORICAL ARCHIVE.** Retained as the original pelvis/hip source of record.
> Not kept in sync with the live code. Index and consolidated UNI-* status:
> `docs/ENGINE_HISTORY.md`. Live future work: `docs/ENGINE_ROADMAP.md`. When this
> file conflicts with the current code, the code wins.

> **Companion to the unified report.** This file is the **pelvis/hip-focused 14-question
> deep-dive**. The consolidated, de-duplicated issue register and the single prioritized
> roadmap now live in **`docs/HISTORICAL/ENGINE_INVESTIGATION_REPORT.md` (unified & corrected)**. For a
> future hip-specific PR, use this Q&A plus the mapped issues below; do not duplicate the
> roadmap here.
>
> **Mapping (this file's `P*` → unified `UNI-*`):**
> `P1` → UNI-4 (solver tilt wrong axis) · `P2` → UNI-3 (mathematical ROM / no hip limits) ·
> `P3` → UNI-10 (hip authoring inconsistency) · `P4` → UNI-1 (solver pelvis-only) ·
> `P5` → UNI-11 (hip center consistent — verified correct) · `P6` → UNI-2 (Middle Split is a
> reference error; engine correct).
>
> **Scope / stance:** pure architectural investigation of the Pelvic / Hip Complex.
> **No engine code, poses, or constants were modified.** Every claim below is derived
> from the **current** repository (`git` HEAD, branch `session/agent_b795bd6c-…`),
> reading `SkeletonFactory`, `SkeletonDefinition` (`HumanSkeletonDefinition`),
> `SkeletonMath`, `SkeletonNode`/FK, `ConstraintSolver`, `BasePose`/`BaseValidationPose`,
> `ExerciseValidator`, and the four validation poses. Where the model is correct, that is
> stated explicitly with the reason.

---

## Reference facts (constants, from `HumanSkeletonDefinition`)

```
hipWidth = 22      thigh = 112      shin = 98      foot = 35
pelvis is the ROOT of the standard skeleton; push-up skeleton re-roots at the ANKLE.
Spine = PELVIS → LUMBAR → CHEST → NECK_END → HEAD_POS  (two-segment spine, Issue E done).
Hip chain = PELVIS → HIP_F/B → KNEE_F/B → ANKLE_F/B → {HEEL, TOE}.
Axis convention (confirmed from pose authoring + BasePose.buildChestOrientation):
  Z = sagittal / flexion (pitch) axis      → anterior/posterior pelvic tilt
  X = lateral / side-bend (roll) axis      → weight-shift / Trendelenburg tilt
  Y = vertical (yaw / twist) axis
```

---

## Q1. What actually is the pelvis?

A **rigid node** that also acts as the **root transform** of the standard skeleton and as
the **pivot** the global solver moves.

- `SkeletonFactory.createStandardSkeleton()` makes `PELVIS` (index 0) the root and attaches
  `LUMBAR`, both `HIP_*`, and the chest chain to it (`SkeletonFactory.kt:59-102`).
- It carries a `localPosition` (3-D translation) **and** a `localRotation` (`JointRotation`,
  a single axis-angle that can represent any 3-D orientation).
- In `ConstraintSolver.solve` it is the single node the solver translates (`pelvis.localPosition.add(delta)`, `:231`) and the single node it re-rotates (`applyPelvisTilt`, `:305`).
- It is **not** a passive offset; it is the anchor the whole body hangs from.

**Verdict:** rigid node + root transform + solver pivot. Correct and appropriate.

---

## Q2. Hip joint model — what does the MonkEngine runtime believe a hip is?

The hip **is a real 3-DOF ball-and-socket joint**, realized by the `HIP_F`/`HIP_B`
`SkeletonNode` (a `JointRotation` local to the acetabulum) plus the femur length
(`thighLength`).

- The `HIP_*` nodes exist and **carry a `localRotation`**. Production poses use it
  explicitly: `PikePushUpPose.kt:90` sets `hipB!!.localRotation.set(axisZ, legPitch - torsoGlobalPitch)`;
  `BasePushUpPose.kt:124` sets `hipB!!.localRotation.set(axisZ, 0f)`. Rotating `HIP_*`
  rotates its subtree (`KNEE→ANKLE→FOOT`) about the acetabulum — anatomically correct.
- A single `JointRotation` (axis-angle) can encode **any** orientation, so flexion,
  extension, abduction, adduction, internal rotation, external rotation, and circumduction
  are all representable as `hip.localRotation`.
- In the **four validation poses** the hip rotation is left at identity and the femur
  direction is instead implied by the IK-solved `KNEE` position (`bakeIkLimb` bakes
  `knee/ankle` offsets relative to the pelvis frame). So in the references the femur is
  IK-driven, but the explicit ball joint is present and used in production.

**Verdict:** the MonkEngine runtime has a genuine hip ball joint. It is **NOT** "pelvis → leg IK →
foot without a real hip model." The femur genuinely rotates from the acetabulum. The only
caveat (see P3) is that hip motion is expressed inconsistently (some poses set
`hip.localRotation`, most let IK define the femur) and there is **no independent hip ROM
limit** (see P2).

---

## Q3. Pelvis DOF

| DOF | Supported? | Mechanism |
|---|---|---|
| Translate (3-D) | **Yes** | `pelvis.localPosition` is a free vector; pose sets it; solver moves it. |
| Pitch (APT/PPT, sagittal) | **Yes** | `pelvis.localRotation` about `Z` (e.g. `DeepOverheadSquatPose.kt:40` `set(axisZ,-leanAngle)`). |
| Roll (lateral tilt) | **Yes (authored)** | `pelvis.localRotation` about `X`. The solver can only *auto-add* a tilt about `Z` (wrong axis — P1), not `X`. |
| Yaw (twist) | **Yes (authored)** | `pelvis.localRotation` about `Y`. |

**Verdict:** the pelvis has full 6-DOF (3 translate + 3 rotate) as an authored transform.
The **automatic** compensation DOF added by `ConstraintSolver` is translate (3-D) **plus
a pitch about Z** — it cannot automatically add roll (`X`) or yaw (`Y`). So "can the pelvis
independently pitch/roll/yaw?" → yes when *authored*; only pitch is auto-added by the
solver, and even that is on the wrong axis for lateral imbalance (P1).

---

## Q4. Lumbar interaction

The `LUMBAR` is a **real, independent segment** between `PELVIS` and `CHEST`
(`SkeletonFactory.kt:65-66`). It defaults to a pass-through (identity) so single-bend poses
are unchanged, but it **can** be set independently via `buildLumbarFlexion` /
`buildSpineCurve` (`BasePose.kt:120-139`), and `reconstructChestFrame` composes the chest
frame against the `LUMBAR` parent (not the pelvis) so an authored lumbar rotation is
preserved (Issue E done).

However, during the **global solve**, `ConstraintSolver` moves/tilts **only the pelvis** —
it never touches `LUMBAR` or `CHEST`. So if a pose leaves the lumbar at identity, the
solver cannot use it as a compensation DOF; the pelvis alone drives the trunk correction.
When the pose *does* author a lumbar rotation, it is carried rigidly through the solve.

**Verdict:** lumbar **can** compensate independently (authored), but the **solver does not
use it** as a free DOF — so in the automatic closed-chain path the pelvis effectively drives
the trunk (P4).

---

## Q5. Femur interaction

The femur (`thigh`) is the bone `HIP_* → KNEE_*`. The hip node is at the acetabulum
(`pelvis + (0,0,±hipWidth)` via `buildPelvis`, `BasePose.kt:47-50`). Because `KNEE_*` is a
child of `HIP_*`, rotating `HIP_*` rotates the femur about the acetabulum — correct
anatomy. The femur length is conserved by FK (`validateBoneLengths` checks `HIP→KNEE ==
thighLength`).

The femur direction is set two ways:
1. **Explicitly** — `hip.localRotation` (production push-up poses).
2. **Implicitly** — IK places the knee; the femur direction is the baked `knee` offset
   (validation poses, and any `bakeIkLimb` call).

Either way the femur **truly rotates from the acetabulum**; it is not a fake
"pelvis→IK→foot" shortcut. The knee (`KNEE_*`) is the *middle* joint of the 2-bone chain
and, like the hip in the references, is usually left without an explicit rotation node —
its direction is IK-derived.

**Verdict:** real hip model, femur attached at the acetabulum, rotates correctly with the
pelvis and (when set) about its own ball joint. Correct.

---

## Q6. Hip center — do all subsystems agree?

- **`HumanSkeletonDefinition`**: `hipWidth = 22` (`SkeletonDefinition.kt:50`).
- **`SkeletonFactory`**: `HIP_F = pelvis.addChild(...)`; the *offset* `±hipWidth` is applied
  by `buildPelvis` → `hipF.localPosition.set(0,0,-hipWidth)` (`BasePose.kt:48`).
- **`ConstraintSolver`**: `chainForEnd(ANKLE_F) = ContactChain(HIP_F, PELVIS, KNEE_F)`
  (`:119`) — IK root = `HIP_F` (acetabulum), framed in `PELVIS`.

All three place the hip center at `pelvis ± (0,0,22)`. The solver's IK root is the hip
world position and the femur offset is baked in the pelvis frame, so the acetabulum and the
femur origin agree.

**Verdict:** **CORRECT and consistent** across definition, factory, and solver. No
disagreement found.

---

## Q7. Pelvic tilt (APT / PPT)

Anterior/posterior tilt is an **independent concept**: it is `pelvis.localRotation` about
the `Z` (sagittal/flexion) axis. It is authored by poses (e.g. `DeepOverheadSquatPose.kt:40`
`pelvis.localRotation.set(axisZ, -leanAngle)`). The `ConstraintSolver` also composes a
pelvis tilt, but about `Z` (`:310` `tiltDelta.set(0,0,1, …)`), which it intends as a
"balance" correction — see P1 for why that axis is wrong for a *lateral* imbalance.

**Verdict:** APT/PPT exists and is independent (authored). Correct as an authored DOF; the
*solver's* automatic tilt is mis-assigned (P1).

---

## Q8. Lateral weight shift (walk, lunge, pistol, step-up, Bulgarian, RDL)

- **Translation:** the pelvis can translate freely in `Z`, so laterally shifting the whole
  pelvis over a support leg is *possible* (the solver's `delta` includes `Z`, `:221`).
- **Lateral roll (Trendelenburg):** should be a pelvis rotation about `X` (roll axis). The
  solver does **not** add an `X` roll — it only adds a `Z` pitch (P1). So the automatic
  *roll* component of a weight shift is missing; only the translation is present.
- **COM logic:** `validateSupportPolygon` only checks the pelvis is inside the support
  bounding box; nothing *drives* a weight shift.

**Verdict:** lateral weight shift is **partially** supported — translation yes, automatic
lateral roll no (and the solver's tilt is wrongly a pitch). For single-leg poses the
*pose* can author the roll directly via `pelvis.localRotation` about `X`, so it is
achievable by authoring, not by the solver.

---

## Q9. Ground contact reaction (one or both feet fixed)

`ConstraintSolver.solve` runs only when contacts are registered (`finalize`,
`SkeletonPoseFinalizer.kt:347`). For each contact it:
1. translates the pelvis so the hip→target distance is *reachable* (`desired = dist.coerceIn(minReach,maxReach)`, `:213`);
2. for ground contacts, composes a small pelvis tilt (`applyPelvisTilt`);
3. re-bakes the limb from the (moved) hip root to the (fixed) target.

So it **moves the pelvis and re-bakes limbs**; it does **not** solve a true closed-chain
equilibrium (it never adjusts hip/knee/ankle angles or the chest/lumbar to "settle" — the
free limbs just follow rigidly). For symmetric, reachable contacts this is a no-op; for
asymmetric contacts it translates + mis-tilts (P1). **It does not "fight" impossible targets
so much as fall back** (e.g. Middle Split bends the limbs).

**Verdict:** contacts are honored by pelvis translation + limb re-bake, not by a posture
solve. Residual of Issue A′ (engine report).

---

## Q10. Closed-chain behaviour

| Pose | Behaviour | Note |
|---|---|---|
| Deep Squat | both feet fixed, symmetric ⇒ solver no-op; pelvis authored at `y=30`, knees fold to floor. Minor ~1–3u pelvis lift from the `30°` knee floor (P2/M). | Works. |
| Middle Split | both feet fixed, symmetric, `straight=true` impossible (hip→foot 58.9 ≪ thigh 112). Solver bends all limbs; pelvis stays grounded. | Fails straight intent (engine report G). Pose-side spread too small. |
| Pike Sit | feet fixed, legs straight reachable (209 ≈ 210). | Works. |
| Lunge | asymmetric feet. If contacts registered, solver translates pelvis between feet **and applies a wrong-axis (Z) pitch** (P1). Production lunges don't register contacts today ⇒ solver is a no-op for them. | Works when no contacts; solver mis-tilts if contacts added. |
| Step-Up | one foot on prop, one on ground (different heights). Same as Lunge — translation OK, automatic tilt wrong-axis. | Same. |

**Verdict:** symmetric closed-chains settle fine; asymmetric ones are handled by translation
but the automatic tilt is anatomically mis-assigned (P1). Impossible (over-constrained)
targets are silently bent, not solved (G).

---

## Q11. Range of motion — biomechanical or mathematical?

**Mostly mathematical.** The only limits are:
- `minimumFlexionAngle = 30°` (global `AngularJointLimits`, `SkeletonMath.kt:108`) — a
  **knee interior-angle** floor applied uniformly to every limb via the reach-distance band
  (`solveIK`, `:305-338`). It bounds how short `hip→foot` may get, **not** a hip ROM.
- `maximumExtensionRatio = 0.98` (or `1.0` for `straight`) — a reach cap, not a joint limit.
- `validateAngularJointLimits` checks the **knee** angle (`HIP→KNEE→ANKLE`, `:608-609`),
  **never the hip angle**.

Consequences:
- **Hip flexion/extension**: not independently bounded. You can flex the hip arbitrarily (femur
  through the torso) as long as the knee can still reach; only the knee floor + leg length
  constrain it.
- **Hip abduction/adduction**: bounded only by femur reach (≤ `210` from hip); a straight
  abducted leg needs the foot at full reach.
- **Hip internal/external rotation**: **no limit at all** (no femoral axial-rotation node
  distinct from the hip ball joint; the hip `localRotation` is uncapped).
- **No per-joint, per-axis anatomical ROM table** (e.g. hip flex 0–120°, abd 0–45°).

**Hardcoded mathematical assumptions:**
- `minimumFlexionAngle = 30°` shared across knees AND elbows.
- `maximumExtensionRatio = 0.98` (dynamic) / `1.0` (straight).
- `hipWidth = 22`, `thigh = 112`, `shin = 98` (proportions, not biomechanical limits).
- Knee is the only validated middle joint; hip ROM is implicit.

**Verdict:** ROM is **mathematical**, not biomechanical. Hip-specific limits are absent (P2).

---

## Q12. Human realism comparison

| Structure | Engine model | Divergence from anatomy |
|---|---|---|
| Pelvis | single rigid node, 6-DOF transform, solver pivot | OK (real pelvis is rigid). No SI joint / separate iliac blades — acceptable for skeletal animation. |
| Hip complex | ball joint (`HIP_*` localRotation) + femur + 2-bone IK | **Correct as a ball joint.** Gap: no independent hip ROM; int/ext rotation uncapped and conflated with the IK pole; hip motion expressed inconsistently across poses (P2/P3). |
| Femur | bone `HIP→KNEE`, rotates from acetabulum, length conserved | Correct. |
| Lumbar | real `LUMBAR` segment, independent DOF available | Correct (Issue E). Solver doesn't exploit it (P4). |
| Thoracic | real `CHEST` segment, 3-D rotation, couples to lumbar/pelvis via `reconstructChestFrame` | Correct. |

**Biggest real divergence:** the hip is biomechanically a *limited* ball-and-socket with
documented ROM per axis, but the MonkEngine runtime treats it as an *unbounded* ball joint limited only
by a knee-flexion distance floor. There is no hip-level anatomical constraint anywhere.

---

## Q13. Existing architectural assumptions (identified)

- **"pelvis is the root"** — *partially true*: true for the standard skeleton; the push-up
  skeleton re-roots at the ankle (`SkeletonFactory.kt:140-146`). The solver finds the pelvis
  by joint index and moves it regardless of root, so this holds in practice.
- **"pelvis only moves vertically"** — *false today*: `pelvis.localPosition` is free 3-D and
  the solver translates it in all axes; only the *old* solver was translation-only in any
  direction.
- **"legs are symmetric"** — *assumed by the solver's tilt*: `signedImbalance` sums to ~0 for
  symmetric stances and the tilt is skipped; asymmetric stances get a wrong-axis tilt (P1).
- **"hips never rotate independently"** — *accurate description of the architecture*: both
  hips are children of the pelvis with a shared pelvis frame; their *difference* comes only
  from distinct IK targets (or distinct `hip.localRotation` if the pose sets them). There is
  no independent, per-hip "rotation about its own axis" DOF separate from the pelvis frame —
  a pose must set `hipF.localRotation` / `hipB.localRotation` explicitly to differentiate
  them. This is a limitation for motions needing asymmetric hip rotation (e.g. one thigh
  internally rotated, the other neutral) without also rotating the pelvis.
- **"hip ROM is bounded by the knee floor"** — *true and a limitation* (P2).

---

## Q14. Future exercise support (natural, no hacks?)

| Exercise | Supported? | Why |
|---|---|---|
| Middle Split | **Pose issue, not engine** | Engine *can* render straight legs at a wide spread **if** the feet are at ≈full reach (`±230`), not `±79.2`. Current reference spread is too small → bent limbs. Fix the reference, engine is fine. |
| Front Split | **Yes** | One leg forward, one back, both straight, pelvis grounded ⇒ hip→foot ≈ `210` each (reachable). Naturally representable. |
| Lunge | **Yes** | Asymmetric foot targets handled by IK. Solver mis-tilts *only if contacts registered*; production lunges don't today. |
| Cossack Squat | **Yes** | One leg straight out (full reach), other bent — IK handles both. |
| Bulgarian Split Squat | **Yes** | Rear foot on prop (different height), front foot grounded — IK handles. Solver mis-tilt if contacts registered. |
| Pistol Squat | **Yes** | One foot contact, other leg extended forward (straight IK). Naturally representable. |
| Single-leg RDL | **Yes** | One foot planted, torso forward, other leg back — IK + authored pelvis roll. |
| Horse Stance | **Yes** | Wide stance, both knees bent — IK handles. |
| Yoga / martial-arts stances | **Mostly yes** | IK + pelvis translate/rotate cover most. Gaps: (a) independent femoral int/ext rotation per thigh (P3), (b) the solver's wrong-axis automatic tilt for asymmetric closed-chains (P1), (c) no hip ROM clamp (P2) means some "poses" could be anatomically impossible yet pass validation. |

**Verdict:** the architecture can naturally support essentially all listed hip/pelvis
exercises **without hacks**, with three caveats: (1) straight-leg wide stances must use
full-reach targets (Middle Split is a reference error, not an engine gap); (2) asymmetric
closed-chain *with registered contacts* trips the wrong-axis solver tilt (P1); (3) there is
no hip ROM safety net (P2).

---

## Discovered issues (pelvic / hip focused)

Each issue: Title · Description · Root Cause · Engine Components · Affected Exercises ·
Severity · Possible Solutions · Complexity · Engine vs Pose · Currently Visible? · Blocks
Future Work?

---

### P1 — ConstraintSolver pelvis-tilt uses the wrong axis for a lateral imbalance

**Title:** The automatic pelvis "balance" tilt is applied about the sagittal `Z` (pitch)
axis, but `signedImbalance` measures a *lateral* (`Z`-position) offset — it should be a roll
about the forward `X` axis.

**Description:** `applyPelvisTilt` does `tiltDelta.set(0f,0f,1f, imb*TILT_GAIN)`
(`ConstraintSolver.kt:310`) — a pitch. `signedImbalance` (`:325-341`) returns the net
lateral offset of contacts from their hip roots. A lateral imbalance should be corrected by a
**roll about `X`** (Trendelenburg), not a pitch about `Z`. So whenever the solver's tilt
fires for an asymmetric closed-chain pose, it tilts the pelvis forward/back instead of
side-to-side. The KDoc even calls it "Small roll about the world Z axis" — confirming the
author believed `Z` was the roll axis (the coordinate-label drift of engine report Issue J).

**Root Cause:** axis convention confusion — `Z` is the flexion/pitch axis in this MonkEngine runtime, not
the lateral/roll axis; the tilt was coded to the imbalance's measurement axis, not the
anatomically correct correction axis.

**Engine Components:** `ConstraintSolver.applyPelvisTilt`, `signedImbalance`, `TILT_GAIN`.

**Affected Exercises:** any asymmetric closed-chain pose that registers foot contacts
(lunge, step-up, Bulgarian split squat, single-leg stance with contacts, pistol with a
registered support). Symmetric poses (squat, split) have ~0 imbalance ⇒ no-op.

**Severity:** MEDIUM (latent today because production poses don't register contacts; would
produce visibly wrong pelvis orientation the moment they do).

**Possible Solutions:** apply the tilt about `X` (roll) instead of `Z` when correcting a
lateral imbalance; or drive the correction as a lateral pelvis *translation* (already
present) and add an `X`-roll only. Align the KDoc with the real axis convention.

**Complexity:** Low.

**Engine vs Pose:** Engine.

**Currently Visible?** No (no production pose registers contacts ⇒ solver is a no-op).

**Blocks Future Work?** Yes — any asymmetric closed-chain exercise that adopts the contact
layer would render a mis-tilted pelvis.

---

### P2 — Hip range of motion is mathematical, not biomechanical (no hip ROM limits)

**Title:** The hip is an unbounded ball joint bounded only by a shared `30°` knee-flexion
floor and an extension cap; there is no hip-specific anatomical ROM, and no validator rule
checks the hip angle.

**Description:** `AngularJointLimits` (`SkeletonMath.kt:100-123`) applies `minimumFlexionAngle
= 30°` uniformly to knees and elbows as a reach-distance band; `validateAngularJointLimits`
checks only the **knee** (`HIP→KNEE→ANKLE`, `ExerciseValidator.kt:608-609`), never the hip. A
pose can therefore flex/abduct/rotate the hip beyond human ROM (even passing the femur
through the torso) and still pass validation, as long as the knee can reach.

**Root Cause:** ROM was implemented as a 2-bone reach/distance band (a math constraint) with
no per-joint, per-axis anatomical table for the hip; the hip was never given its own limit or
validation rule.

**Engine Components:** `AngularJointLimits`, `IKConstraint`, `SkeletonMath.solveIK` (min/max
clamp), `ExerciseValidator.validateAngularJointLimits` / `validateIKConstraints`.

**Affected Exercises:** any extreme hip motion (deep squat bottom, full split, pistol,
high-knee march, yoga hip openers) — over-range poses are uncaught.

**Severity:** MEDIUM (correctness/fidelity gap; lets anatomically impossible hips validate
as clean).

**Possible Solutions:** add a shared, named `HipAngularLimits` (flex/ext, abd/add, rotation
ranges) carried by `SkeletonDefinition`; clamp the femur direction and validate the hip
angle in `validateAngularJointLimits`; reuse the existing AngularJointLimits vocabulary so no
magic numbers.

**Complexity:** Medium.

**Engine vs Pose:** Engine.

**Currently Visible?** No (over-range hips still "reach" and pass).

**Blocks Future Work?** Partially — not a hard blocker, but it means future hip-reference
poses can't be validated for anatomical hip ROM.

---

### P3 — Hip motion expressed inconsistently; no dedicated hip-authoring helper / int-ext rotation clarity

**Title:** The hip ball joint is real, but poses express hip motion two different ways
(explicit `hip.localRotation` vs IK-implied femur), and femoral internal/external rotation
is not separated from the IK pole.

**Description:** Production push-up poses set `hipB.localRotation` (`PikePushUpPose.kt:90`,
`BasePushUpPose.kt:124`); the four validation poses leave the hip at identity and let IK
define the femur. There is no `buildHipRotation(...)` helper parallel to
`buildChestOrientation`/`buildScapularRotation`, and `BasePose` offers no hip helper at all.
Femoral internal/external rotation is technically representable as `hip.localRotation` about
the femur axis, but in practice it is entangled with the IK `pole` that selects the knee-bend
plane, so authors have no clean, named way to express "internally rotate the thigh."

**Root Cause:** the hip was wired as a rotation node but never given a first-class authoring
API or a distinct axial-rotation concept; it inherited the generic IK+pole path.

**Engine Components:** `BasePose` (no hip helper), `SkeletonFactory` (hip node), `SkeletonMath`
(IK pole), `Joint.HIP_F/HIP_B`.

**Affected Exercises:** any pose needing explicit hip rotation / asymmetry (Bulgarian split
squat, single-leg RDL with thigh rotation, yoga pigeon/figure-4, martial-arts chamber).

**Severity:** LOW–MEDIUM (ergonomic + expressive gap; not a hard break).

**Possible Solutions:** add `buildHipFlexion/Abduction/Rotation(hip, …)` helpers mirroring the
chest/scapula helpers; document that `hip.localRotation` is the acetabular ball joint; keep
constants shared/named.

**Complexity:** Low–Medium.

**Engine vs Pose:** Engine (authoring API).

**Currently Visible?** No.

**Blocks Future Work?** No (workable today via raw `hip.localRotation`); eases future hip
expressive poses.

---

### P4 — Global solver compensates only the pelvis; lumbar/chest/hip-knee are not free DOFs

**Title:** During the closed-chain solve, only the pelvis translates + (mis-axis) tilts; the
lumbar, chest, and knee/ankle angles are never used to absorb error.

**Description:** `ConstraintSolver.solve` operates exclusively on the `pelvis` node
(`:172`, `:231`, `:305`). The `LUMBAR` and `CHEST` are carried rigidly; the leg re-bake only
re-aims the already-baked limb at the (fixed) target. So when contacts conflict, the pelvis
alone drives the correction — the trunk cannot "settle" by adjusting lumbar/chest, and the
hips/knees cannot redistribute the error (residual of engine report Issue A′).

**Root Cause:** the solver was built as a root-repositioning relaxation, not a posture solve;
it has one transform DOF (pelvis) + a tilt.

**Engine Components:** `ConstraintSolver.solve`, `applyPelvisTilt`.

**Affected Exercises:** any over-constrained or asymmetric closed-chain pose (Deep Squat
minor lift, Middle Split bend, lunge/step-up mis-tilt).

**Severity:** MEDIUM (the reason symmetric/impossible poses can't be solved exactly).

**Possible Solutions:** add a posture pass that, given fixed contacts, adjusts free joint
angles (hip/knee/ankle/chest/lumbar) by least-squares/CCD to best match the authored shape;
or expand the solver's DOF set beyond the pelvis.

**Complexity:** High.

**Engine vs Pose:** Engine.

**Currently Visible?** Partially (Deep Squat ~1–3u lift; Middle Split bend).

**Blocks Future Work?** Yes — blocks exact reproduction of any pose where contacts and
authored posture conflict.

---

### P5 — Hip center / acetabulum is modeled consistently (VERIFIED CORRECT)

**Title:** `HumanSkeletonDefinition`, `SkeletonFactory`, and `ConstraintSolver` all agree the
hip center is at `pelvis ± (0,0,hipWidth)`.

**Description / Why correct:** `hipWidth=22` (`SkeletonDefinition.kt:50`); `buildPelvis` places
`hipF/B` at `±hipWidth` (`BasePose.kt:48-49`); `chainForEnd(ANKLE_F)` roots the leg IK at
`HIP_F` framed in `PELVIS` (`ConstraintSolver.kt:119`). The IK root is the hip world position
and the femur offset is baked in the pelvis frame, so acetabulum and femur origin coincide.

**Verdict:** no discrepancy. Listed to satisfy the "state explicitly if correct" directive.
No issue, no fix needed.

---

### P6 — Middle Split straight-leg gap is a reference error, not an engine limitation

**Title:** A straight-legged middle split with feet at `±79.2` from a grounded pelvis is
geometrically impossible with `thigh+shin = 210`; the MonkEngine runtime correctly bends the limbs.

**Description:** `hip→foot = 58.9 ≪ 112` (thigh). Real straight-leg splits place the feet at
≈`±230` (full leg length). the MonkEngine runtime *can* render a straight-leg split if the target is at
full reach — the reference's `spread = hipWidth*3.6` is ~3× too small. Per `VALIDATION.md §9`
the reference is anatomically wrong; under the task's freeze the MonkEngine runtime simply cannot satisfy
it (engine report G/M).

**Engine Components:** `ConstraintSolver` (limb re-bake), `SkeletonMath.solveStraightLimb`
(`canBeStraight` gate), bone-length conservation.

**Affected Exercises:** Middle Split (reference). Front Split is naturally supported.

**Severity:** LOW (engine is correct; the frozen reference is inconsistent).

**Possible Solutions:** (pose-side, if the freeze is lifted) set the spread to full leg reach;
or (engine-side) surface `straight`-intent violation in validation (engine report G).

**Complexity:** Low (pose) / Medium (validator intent rule).

**Engine vs Pose:** Both — engine is correct; reference needs correction per the constitution.

**Currently Visible?** The bend is visible; the validator reports the pose clean (no
straight-intent rule).

**Blocks Future Work?** No — Front Split and other splits are supportable; only this specific
frozen spread is impossible.

---

## Prioritized roadmap (pelvic / hip)

1. **P1** — solver tilt on wrong axis (`Z` pitch for a lateral `Z` imbalance; should be `X`
   roll). Cheap, but would corrupt every asymmetric closed-chain pose that adopts the contact
   layer. *(Engine, Low.)*
2. **P2** — no biomechanical hip ROM; only a shared knee-flexion floor. Lets over-range hips
   validate as clean. *(Engine, Medium.)*
3. **P4** — solver compensates pelvis-only; no posture solve. Root cause of imperfect symmetric
   / impossible poses. *(Engine, High.)*
4. **P3** — inconsistent hip authoring; no hip helper / clear int-ext rotation. Expressive gap.
   *(Engine, Low–Medium.)*
5. **P6** — Middle Split is a reference error, engine is correct. *(Pose per constitution.)*
6. **P5** — hip center consistent (verified correct, no action).

---

## Summary

- The pelvis is a **correct rigid root/pivot** with full 6-DOF as an authored transform.
- The **hip is a real 3-DOF ball joint** at the acetabulum (femur rotates from it correctly);
  the "no real hip model" suspicion is **false**.
- The **hip center is modeled consistently** across definition, factory, and solver (verified).
- The genuine limitations are: (P1) the solver's automatic pelvis tilt uses the **wrong axis**
  for lateral imbalance; (P2) hip ROM is **mathematical only** (knee-floor + extension cap, no
  hip limits, no hip validation); (P4) the global solve is **pelvis-only**, not a posture
  solve; (P3) hip motion is expressed inconsistently with no first-class helper.
- The **lumbar is a real independent segment** (correct), but the solver does not use it as a
  compensation DOF.
- **Future hip/pelvis exercises** (front split, lunge, cossack, Bulgarian, pistol, RDL, horse
  stance, yoga/martial stances) are **naturally supportable without hacks**; the only true
  blocker among them is the frozen Middle Split's impossible spread (a reference error, not an
  engine gap), plus the latent P1 tilt bug if those poses register contacts.

*No engine code, poses, or constants were modified during this investigation.*
