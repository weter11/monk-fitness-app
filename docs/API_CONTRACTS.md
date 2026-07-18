# API Contracts

**Frozen with Architecture v2.** For each component: inputs, outputs, what may be read/written, and what is prohibited. All handoff flows through `SkeletonPose` (see `ARCHITECTURE_V2.md` §1).

Legend: **R** = read, **W** = write. "Intent" = `SkeletonPose` §1.1; "State" = `SkeletonPose` §1.2; "Nodes" = `SkeletonPose.nodes`.

---

## 1. Pose

**Role:** declare anatomical intent + environment. Never geometry.

**Inputs:**
- Exercise specification, frame `progress`.
- `SkeletonDefinition` (proportions).

**Outputs (writes to `SkeletonPose` Intent):**
- `skeleton`
- `jointIntents: Map<Joint, RelativeArticulation>` (chest/hip/girdle/ankle/wrist — all relative to parent)
- `spineIntent: SpineCurve(lumbarRad, thoracicRad, axis)`
- `limbTargets: Map<Joint(end), WorldTarget>` (hands/feet/knees/elbows/head)
- `contacts: List<ContactSpec>` + `contactPrecedence: List<ContactId>`
- `postureIntent: PostureIntent(kind, tolerance)`
- `extremityOverrides: Set<Extremity>`
- `motion: MotionDriver`
- `camera`, `environment`

**May READ:** nothing from `SkeletonPose` (writes only its own intent).
**May WRITE:** Intent section only.
**PROHIBITED:**
- Compute pelvis transform / `pelvisY`/`pelvisX` arithmetic.
- `rotAround` / manual joint rotation to cancel inherited tilt.
- Dual pelvis+chest hand-writes.
- Write heel/toe/palm/knuckles/fingertips local positions.
- Raw `hip*.localRotation` writes (use `buildHipOrientation`).
- Hand-computed shoulder world positions / chest-frame re-derivation.
- Head-gaze counter-rotation sums (declare `headTarget` instead).
- Magic ratios (`0.29/0.71`, `6/6/10`).
- Write Nodes or State sections.

---

## 2. IK (SkeletonMath)

**Role:** world-space limb solver. Stateless math.

**Inputs:**
- `rootWorld: Vector3` (from FK)
- `targetWorld: Vector3` (from Pose `limbTargets`)
- `lengths` (from `SkeletonDefinition`)
- `poleWorld: Vector3` (converted by Finalizer from Pose's limb-root-frame pole)
- `constraint: IKConstraint`
- `contact: ContactConstraint?`

**Outputs:**
- World `joint` + `end` positions.
- **W** `SkeletonPose.boneLengthsVerified = true` (always, by construction).
- **W** `maxIkClampAmount` (primary writer).
- **W** `straightIntentDropped` (primary writer).
- Default pole derived when Pose omits pole.

**May READ:** `SkeletonDefinition` lengths, Pose `limbTargets`.
**May WRITE:** State stamps (primary).
**PROHIBITED:**
- Translate root / honor contacts (Solver owns).
- Cancel inherited tilt (Finalizer owns).
- Operate in anything but world space (no `parentRotation`, no local-frame pole).
- Write Nodes or any non-stamp State field.

---

## 3. ConstraintSolver

**Role:** posture authority. Reconciles intent with fixed contacts globally.

**Inputs (READ):**
- `SkeletonPose` Intent: `contacts`, `contactPrecedence`, `postureIntent`, `limbTargets`, `jointIntents`.
- Current node world states (via FK).

**Outputs (WRITE):**
- **Sole writer of the root/pelvis transform.**
- Root translate + tilt to honor contacts (surface/anchor semantics).
- Posture CCD: distribute residual across free joint angles, regularized to authored shape.
- Contact conflict resolution via `contactPrecedence`.
- Inter-frame temporal relaxation of solved root.
- Re-bake contact limbs.
- **W** `maxIkClampAmount`, `straightIntentDropped` (secondary writer, fused).
- **W** `rootTranslationDelta`, `rootRotationDelta`.

**May READ:** Intent + current world states (FK).
**May WRITE:** root transform, contact-limb local offsets (via Finalizer bake), State stamps.
**PROHIBITED:**
- Invent contacts.
- Move a non-contact limb's authored shape.
- Wipe a deliberate lean (compose tilt with authored rotation).
- Write Nodes directly without Finalizer local-frame bake.

---

## 4. SkeletonPoseFinalizer

**Role:** per-node geometry resolver. Runs after Solver; single pass.

**Inputs (READ):**
- Solved world geometry (Solver output).
- `SkeletonPose` Intent (`jointIntents`, `extremityOverrides`, `spineIntent`).

**Outputs (WRITE):**
- **Exclusive writer of local transforms** (`toLocalDirection` / pole-to-world conversion).
- **W** `SkeletonPose.nodes` (final local positions/rotations).
- Extremity derivation (heel/toe/palm/fingertips) unless override set.
- Relative-rotation resolution (inherited-tilt cancel at ankle/wrist).
- `reconstructChestFrame`: computes chest local rotation from pelvis/lumbar world (already settled by Solver).

**May READ:** Solver-settled world states, Intent.
**May WRITE:** Nodes (local transforms), via exclusive frame conversion.
**PROHIBITED (F1 guarantee):**
- Move any Solver-settled contact end-effector. `reconstructChestFrame` MUST assert no contact world position changes vs. Solver output.
- Translate root / re-solve posture.
- Mutate Solver-settled contacts.
- Accept a local-frame pole (IK receives world pole only).

---

## 5. FK (SkeletonNode / SkeletonMath)

**Role:** stateless propagation primitive. Owned by none; shared utility.

**Inputs:**
- Root world transform + node local transforms.

**Outputs:**
- All node world transforms.
- Propagated intent rotations.
- Subtree re-flatten.

**May READ:** local transforms.
**May WRITE:** world transforms (transient, not `SkeletonPose` state).
**PROHIBITED:**
- Decide what local rotations *should be* (that is intent or Solver).
- Persist any `SkeletonPose` state.

---

## 6. Validation (ExerciseValidator)

**Role:** observer. Reads stamped state; never influences geometry.

**Inputs (READ):**
- `SkeletonPose` State: `maxIkClampAmount`, `straightIntentDropped`, `rootTranslationDelta`, `rootRotationDelta`, `boneLengthsVerified`, `hipRomStamps`, `bilateralSymmetryDelta`, `bilateralOppositeBend`.
- `SkeletonPose` Intent: `jointIntents` (ROM read).

**Outputs:**
- Rule verdicts: `FOOT_GROUND_PENETRATION`, `IK_TARGET_UNREACHABLE`, `STRAIGHT_LIMB_INTENT`, `HIP_ROM_LIMIT`.

**May READ:** State + Intent (ROM only).
**May WRITE:** nothing on `SkeletonPose`.
**PROHIBITED:**
- Derive geometry or compensate.
- Reverse-engineer joint angles (read stamps directly).
- Drive the system / retune poses.

---

## 7. SkeletonPose (carrier — not a component, the contract object)

**Intent section (Pose W / Engine R):**
- `skeleton`, `jointIntents`, `spineIntent`, `limbTargets`, `contacts`, `contactPrecedence`, `postureIntent`, `extremityOverrides`, `motion`, `camera`, `environment`.

**State section (Engine W / Validation R):**
- `nodes` — **W: Finalizer only.**
- `maxIkClampAmount` — **W: IK (primary), Solver (secondary). R: Validation.**
- `straightIntentDropped` — **W: IK (primary), Solver (secondary). R: Validation.**
- `rootTranslationDelta`, `rootRotationDelta` — **W: Solver. R: Validation.**
- `boneLengthsVerified` — **W: IK. R: Validation.**
- `hipRomStamps` — **W: Finalizer (computeHipRomStamp). R: Validation.**
- `bilateralSymmetryDelta`, `bilateralOppositeBend` — **W: Finalizer. R: Validation.**

**Ownership rule:** Pose writes Intent; Engine writes State; Validation reads State. No component edits another's section. This single carrier resolves F2/F3/F10.
