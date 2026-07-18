# Pose Audit & Fix Playbook (Unified Plan)

> Single reference for auditing any production pose from two independent axes and fixing
> the defects found. Treat **MonkEngine as the reference implementation** — audit the
> *pose*, not the engine. Do not redesign the engine, invent features, migrate
> architecture, or discuss Branch B/C / RFC planning while using this playbook.

---

## 0. Golden rules

- **Two independent axes.** A pose can be `Integration: PASS` and `Biomechanics: 3/10`
  (and vice-versa). Never assume one implies the other.
- **Engine is correct unless proven otherwise** with objective evidence. A pose looking
  wrong is not, by itself, proof the engine is wrong.
- **Fix the pose, never retune the engine to make a pose read green.**
- **Minimal fixes only.** No redesign, no new engine APIs, no architectural migration.

---

## 1. Layer 1 — MonkEngine Integration Audit

Goal: verify the pose fully uses the **current** authoring model. Output is **PASS** or
**FAIL**; list *only* architectural problems.

### Audit checklist (per pose)

For each item, confirm the pose uses the documented helper and that no bypass exists:

| Area | Correct usage | Bypass to flag |
|---|---|---|
| PoseBuilder / BasePose helpers | extend `BasePose` / implement `PoseBuilder`; use `buildTorso`, `buildPelvis`, `buildShoulders`, `buildClavicularRotation` | hand-written `localPosition`/`localRotation` where a helper exists |
| IK | call `bakeIkLimb(...)` (registers `limbTargets`, `maxIkClampAmount`, `boneLengthsVerified`) | direct `SkeletonMath.solveIK` / `solveStraightLimb` |
| limbTargets | populated automatically by `bakeIkLimb` | missing carrier → unregistered limb (check `jointsBuffer.limbTargets`) |
| Contacts | pass `contact = ContactConstraint.ground(level)` to `bakeIkLimb` **and/or** add a `ContactSpec` for every declared support | metadata declares `support.contacts` but `hasContacts()` is false (no `ContactSpec` added) |
| Posture intent | `declarePosture(buffer, PostureIntent.Kind.*)` | hand-computed `pelvis.y`/`pelvis.x` instead of naming the posture |
| headTarget / gaze | `buildGaze(...)` (records `headTarget` carrier) | manual `head.localRotation`/`localPosition` write |
| Pelvis / spine / joint intent | `declarePelvisTilt`, `buildSpineCurve`, `buildHipFlexion`, `buildHipRotation`, `buildChestTwist`, `buildClavicularRotation` (all carrier-backed) | bare `pelvis.localRotation.set` / `chest.localRotation.set` as independent angles |
| Shape Constraint helpers | `buildTorso`/`buildPelvis`/`buildShoulders` retained as direct node writes | (these are *expected* direct writes — NOT a violation) |
| Articulation (Branch C) | `buildWristArticulation` / `buildAnkleArticulation` (carrier-backed) or `overrideExtremityOrientation` opt-out | manual copy of one joint into another (e.g. `WRIST.set(HAND)`), manual endpoint writes |
| Validation stamps | produced by `SkeletonPoseFinalizer.applyValidationStamps` and consumed by the validator — *no pose-side action needed* | pose hand-computing hip-ROM / symmetry stamps |
| Metadata | `PoseMetadata` with `camera`, `support`, `environment` | missing/empty support when the pose has contacts |
| Legacy APIs | none | `buildHead`, `preConvertPoles`, `EngineFlags`, direct `finalize(pose)` call |
| Duplicated author intent | one source of truth per DOF | same DOF written twice (e.g. node + intent that disagree) |
| Bypasses | route everything through the engine | silent `getJoint(X).set(getJoint(Y))` overrides |

### Recurring defect patterns (from past audits)

1. **Wrist = hand copy.** `WRIST_X.set(HAND_X)` discards the engine's wrist
   articulation. Fix: `buildWristArticulation(Extremity.HAND_X, flexion, deviation, handX)`.
2. **Declared-but-unregistered contacts.** Metadata lists supports but no `ContactSpec`
   exists → `hasContacts()` false → solver skips → floating/penetrating contacts.
   Fix: pass `contact = ContactConstraint.ground(0f)` into the `bakeIkLimb` for end
   joints, and for 1-bone foot ends (TOE_F/B) add an explicit `ContactSpec`
   (`ConstraintSolver.chainForEnd(toe.joint)`, `length1 = footLength`, `length2 = 0`,
   `targetWorld = toe world pos projected to y = level`, `contact = ground(level)`).
3. **Direct `SkeletonMath.solveIK`.** Re-route through `bakeIkLimb` so carriers populate.

### Compile-first gate

Every fix must **compile**. After editing, confirm the module compiles before pushing:
```
./gradlew :app:compileDebugKotlin
```
The audit sandbox often lacks the JDK/SDK; if so, state that compilation was **not**
verified locally and rely on CI. Never push a commit known to break compilation.

---

## 2. Layer 2 — Biomechanical Audit

Ignore implementation. Judge only the resulting human body, as it would render on a phone.
Score **every** section 0–10.

### Sections to score

- **Overall** — immediately reads as the intended exercise? coach-recognizable?
- **Head / Neck / Gaze / Cervical alignment**
- **Spine** — lumbar, thoracic, pelvic orientation, rib cage
- **Upper body** — shoulders, clavicles, elbows, wrists, hands
- **Lower body** — hips, knees, ankles, feet
- **Balance** — center of mass, support polygon, stability
- **Contacts** — wall/floor/bar/bench/props; are they believable?
- **Symmetry** — left/right consistency
- **Range of Motion** — every joint angle believable?
- **Muscle intent** — does the body communicate intended engagement?
- **Animation continuity** — believable interpolation between neighbouring frames?

### Output format

```
Critical issues   — breaks recognizability / unsafe / degenerate joint
Major issues     — noticeably wrong but readable
Minor issues     — cosmetic
Visual polish    — optional enhancements
```

---

## 3. Unified workflow (audit → fix → ship)

1. **Read the pose** + its base class + the geometry/validator it uses.
2. **Run Layer 1** checklist → `PASS` / `FAIL` + list of architectural problems.
3. **Run Layer 2** scoring → critical/major/minor + polish.
4. **Fix only Layer-1 FAIL items** (and any biomechanical criticals that are *authoring*
   errors, not engine bugs). Keep each fix minimal and routed through the documented API.
5. **Verify compile** (`compileDebugKotlin`). If the sandbox lacks the toolchain, say so.
6. **Commit + push PR**; in the PR body:
   - which Layer-1 defects were fixed and the minimal change,
   - the biomechanics score and any criticals left as known limitations,
   - the exact test command for CI/local,
   - explicit note if compilation was NOT run locally.
7. **Do not** redesign the engine, add new engine features, or migrate architecture.

## 4. Worked example — Standard Push-Up (audit `bd31bec`, fix in PR #177)

- **Layer 1: FAIL**
  - Wrist bypass: `WRIST_A.set(HAND_A)` discarded engine wrist articulation → fixed
    with `buildWristArticulation(HAND_A/HAND_P, 0.35f, 0f, hand)`.
  - Contacts unregistered: metadata declared 4 supports but `hasContacts()` was false →
    fixed by passing `contact = ContactConstraint.ground(0f)` to the hand `bakeIkLimb`
    calls and adding `ContactSpec`s for `TOE_F`/`TOE_B`.
- **Layer 2: ~6.5/10** — reads as a push-up; wrist/hand handling and unpinned
  contacts were the weak points (both addressed by the Layer-1 fixes).
- **CI compile fix:** `registerToeContact` originally read a nullable
  `contextDefinition.footLength` → changed to take `footLength` as a parameter.
