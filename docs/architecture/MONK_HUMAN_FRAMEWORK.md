# Monk Human Framework — human character and anatomical presentation

**Status:** architecture RFC, proposed for review. Nothing in this document is implemented by this change.
**Base for this document:** `main` @ `abc2483` (`abc248330d1a300111907343038035d177db4e3f`); the engine measurements
below were taken at `16e0487`, the base this document was written against, and `animation/` and `poses/` are byte-identical
between the two (§3.1).
**Companion documents:** `docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` (runtime ownership), `docs/ARCHITECTURE_V2.md`
(carrier contract), `docs/CHARACTER_FRAMEWORK.md` and `docs/CHARACTER_RENDERING.md` (Filament work; PR #385 is merged at
`abc2483`, PR #384 is still open), `docs/ENGINE_ARCHITECTURE.md` (engine map), `docs/VALIDATION.md` (validation subsystem).

This document is written to be reviewed: every claim about current code carries a `file:line` reference, and every
proposed mechanism carries an accuracy classification (§11). Where the repository does not settle a question, the
question is listed in §13 rather than answered by assertion.

**Why this location.** `docs/architecture/` holds the cross-subsystem RFCs and ownership matrices; `docs/CHARACTER_*.md`
are records of a specific implementation (Filament stage 1/2). This is a framework-level RFC that binds the engine, a new
anatomical subsystem and a future character pipeline, so it belongs beside the other architecture RFCs rather than in the
character implementation series.

---

## 1. Executive summary

Monk already has a motion engine that solves exercise geometry from intent. It has no anatomical model and no
production character: the shipped presentation is a 2D vector skeleton, and the Filament character exists only as an
open PR behind a disabled build flag.

This RFC defines three cooperating models with a single direction of dependency:

```
Motion Model  (exists, frozen)        animation/ + poses/
      │  publishes one SkeletonPose per frame (reused carrier)
      ▼
Anatomical Model  (new)               anatomy/  — rig-independent, character-independent
      │  publishes AnatomyFrame: muscle paths, lengths, landmarks
      ▼
Character Appearance Model  (partly exists, PR #385)   character/
      │  body mesh, muscle meshes, skeleton overlay, materials, morphs
      ▼
Presentation layers  (skin / superficial muscles / deep muscles / skeleton / selection)
```

The design rules that follow from that direction:

1. **The motion engine does not learn about anatomy or appearance.** No mesh, material, muscle or character type enters
   `animation/` or `poses/`. The character and anatomy are consumers of the published pose, never participants in
   producing it.
2. **Anatomy is derived, never animated.** A muscle's position, path and length are functions of the published joint
   transforms, the anatomical definition, and the wrap surfaces — not of an independent timeline. There is no code path
   in which a muscle moves while the skeleton does not.
3. **Anatomical data is rig-independent and character-independent.** Attachments are authored in the canonical segment
   frames of §7.2, in metres — the derived segment frame, never the node's axes and never the world bind axes — so one
   muscle library serves every character and every visual rig, exactly as one pose library serves every character.
4. **Geometry strategies are interchangeable behind a path contract.** The per-frame output of the anatomical model is a
   polyline path per muscle plus its length. Whether the visible muscle is a procedurally generated tube (phase A) or an
   authored mesh skinned to path-driven helper bones (target) is a renderer-side choice, not an anatomical one.
5. **Activation is authored educational metadata.** Roles and phases come from a reviewed table with a provenance field;
   they are never derived from joint angles and never presented as measured EMG or physiological activation.
6. **Validation is layered and separate.** The existing 18-rule `ExerciseValidator` is unchanged. Rig, attachment, path,
   mesh and morphology validation are new observers with their own reports; none of them can weaken motion validation.

The first useful deliverable (milestone **M1**, §15) is one character, driven by the real motion engine, playing the four
pilot exercises (`squat`, `standard push-up`, `bird-dog`, `row_standard`, X8), on one shared rig (a v1 scope constraint,
§6.1), with eight muscles that follow the movement
and whose attachments have passed a recorded anatomical review (§12.4), one morphology preset,
skin/superficial-muscle/skeleton layers, and **tap hints on the rig** — labels anchored to attachment landmarks that name
what is under the finger, with no deformation of their own. The full muscle library, the deep layer, organs, the full
label/picking system and education content are explicitly later.

---

## 2. Scope, method and evidence rules

**In scope:** architecture, data contracts, ownership, validation strategy, staged roadmap, and the explicit decisions
requested in the task.

**Out of scope:** implementation, production rendering changes, exercise-builder rewrites, workout/session logic, final
character art, a complete muscle library, physics or biomechanical simulation, merging PR #384 or PR #385.

**How the baseline was verified.** The repository was inspected at `main` (`16e0487`, the base this document's numbers
were measured against), not from earlier reports. The Filament work was read from the two PR heads (`fd549bd` for #384,
`23ef1c0` for #385) without changing either PR's state or merging anything. The humanoid test asset was parsed directly
(`app/src/main/assets/characters/humanoid_v1.glb` at `23ef1c0`, byte-identical on `main` at `abc2483`) to confirm joint
count, triangle count and morph targets rather than trusting the generator's printout; that parse is also where the
vertex count in §3.4 comes from. **#385 has since merged into `main` at `abc2483`**, whose `animation/` and `poses/` trees
are identical to `16e0487`'s, so every engine measurement below still stands and only the implementation status changed.

**Evidence rules used in this document:**

- A statement about current behaviour cites a source location on `main` unless it is explicitly attributed to a PR.
- Numbers are either measured here (asset parse, line counts, entry counts) or quoted from a document with its section.
- The AA-3D weightlifter viewer is treated as an **external reference implementation**: its published muscle-data design
  is used as inspiration, its measured cost is used as a scale reference, and none of its numbers are treated as a
  specification for this app.
- Unverified items are labelled. The agent's build environment has no Android device; a phone does exist now, and one
  spike run has been recorded (§3.4, §12.3). Anything requiring a GPU, a driver or sustained thermal behaviour is still
  a device-validation item: recorded from a run, never inferred here.

---

## 3. Verified baseline

### 3.1 Repository state at the time of writing

| Item | Value |
| --- | --- |
| Default branch | `main` |
| Base SHA | `abc248330d1a300111907343038035d177db4e3f` ("Merge pull request #385 …"), the current `main`; this document was written against `16e04874096989300a96f5c18416661337ed1eb4`, whose `animation/` and `poses/` trees are identical to `abc2483`'s (empty `git diff --stat` over both packages) |
| Working tree at branch creation | clean (`git status --porcelain` empty); this document is the only change on the branch |
| Documentation branch | `docs/human-framework`, created from `origin/main` |
| Engine sources on `main` | `app/src/main/java/com/monkfitness/app/animation` (8,144 lines in 47 files), `.../poses` (10,642 lines in 79 files) |
| Registered pose builders | 66 (`AnimationRegistry.kt` — 66 `register(...)` call sites; the 67th textual match is the `fun register` declaration) |
| Pose source files | 79 |
| Joint identifiers | 33 (`animation/Joint.kt:3`) |
| CI verifier | `.github/workflows/android.yml` — `:app:assembleDebug` then `:app:testDebugUnitTest` |
| Rest invariant as shipped | `PoseRetargeterTest.theNeutralPoseReproducesTheIdentityOnEveryBone` — **`PoseRetargeterRestInvariantTest` does not exist** (see below) |

**The rest invariant's test does not exist under the name the rest of this document uses.** The behaviour lives in
`PoseRetargeterTest.theNeutralPoseReproducesTheIdentityOnEveryBone`
(`app/src/test/java/com/monkfitness/app/character/PoseRetargeterTest.kt:72`); §6.1, §6.2, §12.2 and §15 name
`PoseRetargeterRestInvariantTest` as the stage-1 extraction, and stage-1 PR P4 (§15 stage 1) moves the existing case
into that class and re-points it at `humanoid_v2` rather than writing a second test for the same claim.

**Counting definition** (used in this table and in Appendix A, so the two cannot drift): a line count is every line of
every `.kt` file under the package and its subdirectories, blank lines and comments included, summed from
`git show <sha>:<path> | wc -l` over the file list; a file count is the number of `.kt` files in that tree. Both
figures above are that measurement at `16e0487`, and identical at `abc2483` because those two trees are unchanged
between the two commits.

**One PR carries the Filament work, and it is merged.** #385 landed in `main` at `abc2483`; #384 is still open and is
**not** assumed either way:

| PR | Branch / head | State | Content |
| --- | --- | --- | --- |
| #384 | `spike/filament-humanoid` @ `fd549bd` | open, not merged | standalone `spike-humanoid` app module: Filament scene, GLB reader, retargeter, generated 32-joint asset, 5 JVM tests |
| #385 | `feat/filament-character-stage2` @ `23ef1c0` | **merged** in `main` at `abc2483` | the spike promoted into `:app` as `com.monkfitness.app.character` behind `FILAMENT_CHARACTER_ENABLED`, backend diagnostics, asset + generator, morph presets, device-validation doc |

**The merge is what changed, not the code.** At the baseline `16e0487` the runtime was absent from `main`: no `filament`
or `gltfio` entry in `gradle/libs.versions.toml` or `app/build.gradle.kts`, no `character/` package (0 files), no `.glb`
asset, and an empty `git diff --stat 16e0487..23ef1c0` over `animation/` and `poses/`. At `abc2483` all three are present
on `main` — 12 files under `character/`, `app/src/main/assets/characters/humanoid_v1.glb` (sha256 `ef643438…e539`, the
same bytes as `23ef1c0`'s), `scripts/character/generate_humanoid_v1.py`, `docs/CHARACTER_DEVICE_VALIDATION.md`, and the
`filament = "1.75.1"` pin — while `animation/` and `poses/` are still byte-identical to `16e0487`'s. Filament 1.75.1, the
character runtime and `PoseRetargeter` are therefore a **baseline** now, not a prerequisite, and the stages that touch
them are no longer gated on a merge (§15 stage 1). Every remaining statement in this document about what `main`
contained refers to `16e0487` and is labelled with that commit.

### 3.2 The motion engine as built

The engine is a five-stage pipeline around one carrier (`docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` §6):

```
PoseBuilder.build(PoseContext)            poses/*.kt, BasePose.kt
  → SkeletonPipeline.produceFrame(...)    animation/SkeletonPipeline.kt:66 (built pose), :82 (builder)
      → ConstraintSolver.solve(...)       animation/ConstraintSolver.kt:184   [contacts, or any non-CUSTOM posture intent]
      → SkeletonPoseFinalizer.finalize    animation/SkeletonPoseFinalizer.kt:277
          → publish                       animation/SkeletonPoseFinalizer.kt:390
  → SkeletonPose (Published Pose State)   animation/PoseDefinition.kt:113
```

Load-bearing facts for a downstream consumer:

- **`SkeletonPose` is a reused carrier.** The pipeline publishes into buffers that are reused on the next frame
  (`SkeletonPipeline.kt:26-43`; documented as the carrier-reuse constraint in
  `docs/CHARACTER_FRAMEWORK.md` §2). A consumer that needs the frame later must copy it. The character presenter
  uploads bone matrices inside the producing call for exactly this reason (`CharacterSession.kt:310-318`, PR #385).
- **`pose.joints[j]` are world positions and `pose.rotations[j]` are world rotations** after finalization; the 2D
  projector reads `getJointRotation(CHEST)` as "the first-class source of truth" for torso orientation
  (`animation/SkeletonProjector.kt:81-87`).
- **The limb stage is live by default** (`animation/IkStage.kt:18`, `IK_STAGE_ACTIVE = true`) and is the sole active
  limb solver; the authoring bake remains only as a rollout/differential path (RFC §5 R5, §8).
- **Validation is a read-only observer of the published pose**, 18 rules (`animation/ExerciseValidator.kt:47-92`:
  `FINITE_COORDINATES`, `BONE_LENGTH`, `HEAD_VIEWPORT`, `FOOT_GROUND_PENETRATION`, `HAND_SLIDING`, `IK_CONSTRAINT_LIMIT`,
  `POSITION_DISCONTINUITY`, `VELOCITY_DISCONTINUITY`, `ACCELERATION_SPIKE`, `STATIC_SUPPORT_POLYGON`,
  `BILATERAL_SYMMETRY`, `HAND_SHOULDER_ALIGNMENT`, `IK_TARGET_UNREACHABLE`, `ANGULAR_JOINT_LIMIT`,
  `STRAIGHT_LIMB_INTENT`, `CONTACT_PRESERVED`, `PELVIS_INTENT`, `HIP_ROM_LIMIT`).
- **Pose metadata is the only per-exercise declaration surface** (`animation/PoseMetadata.kt:28-42`): camera, loop
  mode, duration, motion curve, breath fractions, environment (ground level, props, anchors) and one support declaration
  (pivot + contact points). A character layer consumes these; it must not invent its own camera, ground or support model.
- **Body proportions are a definition, not a constant** (`animation/SkeletonDefinition.kt:5-66`;
  `HumanSkeletonDefinition` torso 120, thigh 112, shin 98, upper arm 80, forearm 66, shoulder width 46, hip width 22).
  Units are engine units, not metres.

**Joint naming trap that the anatomical model must not inherit.** `Joint.HIP_F` is not the hip joint point; it is the
node whose rotation is the *thigh segment's* orientation. The engine hierarchy is
`PELVIS → HIP_F → KNEE_F → ANKLE_F → {HEEL_F, TOE_F}` (`animation/SkeletonFactory.kt:91-95`), and the same pattern holds
for `SHOULDER_A → ELBOW_A → HAND_A` (`:73-80`), `LUMBAR`, `CHEST`, `NECK_END`, `HEAD_POS`, `CLAVICLE_A → SCAPULA_A`.
An anatomist reading `HIP_F` as "the hip joint" would attach muscle origins to the wrong frame. §7.2 defines the segment
frame table that resolves this once.

### 3.3 The current 2D presentation

There are two shipped presentations, chosen per exercise:

1. **Engine-driven vector skeleton.** `ExerciseAnimatedVisual` (`ui/components/ExerciseAnimation.kt:52`) resolves the pose
   config, builds the pose, and calls `SkeletonRenderer` (`:136`), which is a Compose `Canvas` composable
   (`render/SkeletonRenderer.kt:47`). The renderer runs the pipeline itself (`:86`), projects the published joints
   (`animation/SkeletonProjector.kt:21`), then draws:
   - bones as thick stroked lines, joints as filled circles, torso as eight-point filled faces
     (`SkeletonRenderer.kt:112-189`),
   - painter's-algorithm depth sort by projected depth, with colour lerped by depth
     (`SkeletonRenderer.kt:132-144`, insertion sort over `RenderItem.depth`),
   - all sizes from `SkeletonStyle` (`animation/SkeletonStyle.kt`): head radius 18, joint radius 9, limb thicknesses
     12–21, outline 2.
2. **Legacy illustration path** for exercises with no registered procedural pose: `ui/components/animation/` has its own
   `Joint` enum (`ExerciseSkeletonData.kt:10`), its own interpolation (`poseAt(progress)`) and its own organic limb
   paths, and shares nothing with the engine except intent.

The application-level seam is `ExerciseVisualContent` (`ui/components/CommonComponents.kt:63`) → `ExerciseHeroMedia`
(`:110`), used by the exercise detail screen and the session screens. This is the place a character replaces the 2D
skeleton; the legacy path stays as the fallback for exercises without a procedural pose.

There is no lighting model, no surface, no muscle and no depth-correct volume in either path: depth is a colour ramp.

### 3.4 The Filament work (PR #384 and PR #385)

**PR #384 — stage-1 spike (`spike-humanoid`, 17 files, +2,166).** A second application module containing
`HumanoidScene`, `GltfRig`, `MonkJointResolver`, `PoseRetargeter`, `MonkMotionDriver`, a generated
`humanoid_spike.glb` and five JVM tests. It proved that a skinned glTF can be driven from `SkeletonPose` without
touching `:app`.

**PR #385 — stage 2 (30 files, +2,983/−11).** The runtime promoted into `:app` as `com.monkfitness.app.character`,
merged into `main` at `abc2483` (absent from `main` at the `16e0487` baseline):

- `PoseRetargeter` (`character/PoseRetargeter.kt:10`, `boneMatrices` at `:42`): rest-delta world retarget,
  `R_target = R_source × R_target_rest`, rotations only, emitted as `inverse(meshWorld) × jointWorld × inverseBind`
  (`:108-110`). **X3 replaces this algorithm** with a position-driven one (§6.2); the class, the call site and the
  buffer contract stay. The basis is a default parameter of `Quat.IDENTITY` (`PoseRetargeter.kt:14`) and
  `CharacterSession.kt:152` constructs it without one, so the shipped call path applies no basis at all. Only `PELVIS` translation is converted from engine units to asset units (`:78-83`); bone offsets stay in
  asset units because the inverse binds were authored against them (`:70-72`).
- `GltfRig` (`character/GltfRig.kt:11`, `parse` at `:99`): a minimal GLB reader for the data gltfio does not expose —
  node hierarchy, skin joint order, inverse bind matrices, triangle and morph counts, animation names.
- `MonkJointResolver` (`character/MonkJointResolver.kt:10`): 21 of the 33 engine joints have a bone to map on the shipped
  asset, by normalized name/aliases (`:12-34`; §6.2 lists the 23 the v2 map requires); unresolved joints stay at rest
  instead of failing. `restPelvisHeight` = `shin + thigh + 25` (`:59-60`).
- `CharacterCalibration.unitScale` (`character/CharacterCalibration.kt`): derives engine→asset scale from a standing
  frame (`HEAD_POS.y − min(TOE_F.y, TOE_B.y)`), using a throwaway pipeline so calibration cannot advance the render
  pipeline's history.
- `MorphologyPreset` (`character/MorphologyPreset.kt:7`): four presets over five morph weights (neutral, slim, heavy,
  muscular). Comment says "vestigial body-shape parameters carried by the test asset".
- `CharacterFeature` / `ActiveCharacter` (`character/CharacterFeature.kt:9,18`): the flag (`BuildConfig
  .FILAMENT_CHARACTER_ENABLED`, default false — `app/build.gradle.kts:31-32`, enabled only with
  `-Pmonk.filamentCharacter=true`) and a process-wide single-owner slot guard.
- `CharacterStage` (`character/CharacterStage.kt:29`): a Compose `AndroidView`/`TextureView` host that builds the pose
  from `PoseRegistry` + `rememberAnimationController` and feeds `CharacterPoseSource` (`character/CharacterPoseSource.kt:6`).

**Verified properties of the test asset** (`humanoid_v1.glb`, parsed for this document):

| Property | Value |
| --- | --- |
| File size | 2,060,808 bytes |
| Vertices | **16,675** body vertices (16,679 including the `Ground` quad) |
| Nodes / joints | 35 nodes, 32 skin joints (`Hips, Spine, Chest, Neck, Head, Left/RightShoulder, Arm, ForeArm, Hand, 5 fingers, UpLeg, Leg, Foot, ToeBase, HeadTop_End`) |
| Meshes / primitives | 2 meshes, 2 primitives (body + a `Ground` node inside the GLB) |
| Triangles | 30,722 (30,720 body + 2 `Ground`) — triangles, not vertices; the two counts differ by a factor of ~1.8 and are never interchangeable |
| Morph targets | 5: `ShoulderWidth`, `ChestVolume`, `WaistWidth`, `LimbThickness`, `HeadScale` |
| Materials / textures / animations | 2 materials, 0 images, 0 animation clips |
| Bind pose | arms at the sides and limbs straight — a straight-limbed A-pose in the X1 sense (§6.6); it is close to, but not identical with, the engine's standing frame, whose arms are held out for counterbalance |

`humanoid_spike.glb` (PR #384) and `humanoid_v1.glb` (PR #385) are **byte-identical**: 2,060,808 bytes, sha256
`ef643438b761de84504d45c87511c7d2ad5109996fa56ab77107fee1cde0e539`. A defect found in one is a defect in the other, and
the `Ground` node is in both — the two PRs ship one asset under two names.

Filament is pinned to **1.75.1** (`gradle/libs.versions.toml:15`, on `main` since `abc2483`): 1.76+ requires
`compileSdk 37` and the app is on 34. The documented native payload is 7.4 MB arm64 and
a debug APK with the asset is 18.8 MB (`docs/CHARACTER_FRAMEWORK.md` §1, merged with #385).

**One device run, informational.** The #384 spike was run on the owner's phone — Adreno 735, OpenGL ES 3.2, feature
level 2 — and held ~59 fps on the skinned 30,720-triangle asset. That is one run of one asset on one device; it is
recorded because it is the only device evidence the programme has, and it is not a budget, a floor for the reduced tier,
or evidence about a mid-range device. By the owner's decision formal FPS and thermal gating is **deferred** — result
first, optimisation later — so no stage gate depends on these numbers (§12.3). The three launch defects the run exposed
are listed as known traps in §13.

### 3.5 What the AA-3D viewer contributes — and what it does not

**Provenance, stated because it limits what these numbers can do.** The figures below are quoted from an external
reference implementation's own page state, as reported in the review that produced this section. They are
**owner-run, with no in-repo artefact** — no URL, no capture, no script, nothing this repository can re-execute — so the
numbers are **not reproducible from here and are not verified here**, exactly as the Adreno run of §3.4 is a single
recorded run rather than a measurement anyone can repeat. They are
used as a scale reference for what a browser-side implementation costs — never as a budget for this app, and never as
evidence about a phone. The only device numbers in this framework come from one run of the #384 spike (§3.4); they are
informational, and no stage gate depends on them (§12.3).

Measured on the referenced artifact (external site, model-generated page; numbers from the page's own
`__SCENE_STATE__`): 92,610 triangles, 193 draw calls, ~57 fps at DPR 1 in headless Chrome; muscle geometry rebuilt from
scratch each frame costing 4.6 ms; 90 muscle definitions (53 superficial, 37 deep, 10 of them generated intercostals),
180 instances after mirroring, 30.9 k muscle triangles per frame; 33-joint rigid bone rig (82 bone meshes), plus a
separate 65-bone skinned skin and organ meshes.

Its transferable ideas, stripped of its implementation:

1. **Muscles are data, not animation.** Origin/insertion rows, intermediate waypoints, width/thickness, tendon fractions,
   layer, and obstacle wrapping are authored as a table; the mesh is derived.
2. **Length is measured along the solved path**, not along the straight chord between attachments.
3. **Roles and phases drive colour**, from a per-exercise table of prime movers, synergists and stabilisers.
4. **One layer system** (skin, superficial, deep, skeleton, organs) with dissolve transitions, where hiding a layer never
   touches the motion.
5. **Labels are projected from anatomy**, so the annotation cannot drift from the body.

What it does not contribute, and this framework deliberately rejects:

- pose functions per exercise with hand-tuned numbers instead of a solver/validator separation;
- no joint limits, no contact model, no self-collision, no published-frame validation;
- balance as a centre-of-mass box test, presented as physical plausibility;
- a fixed 33-joint anatomical rig with per-rib and per-organ segments, which this engine's namespace does not have;
- geometry rebuilt on the CPU every frame for the full library, which this framework assumes is unaffordable on a
  mid-range phone — the single Adreno 735 run of §3.4 is the only evidence either way, and it is informational;
- its length-to-thickness shape response, which this framework does not adopt (§7.6).

### 3.6 Gaps the framework must close

| Gap | Consequence today | Owner in the target |
| --- | --- | --- |
| No anatomical model | nothing knows what a muscle is; no attachment, length or role data exists | Anatomical Model (§7) |
| No muscle geometry | the character shows skin only; the 2D skeleton shows sticks | Anatomical + Character models (§7.5) |
| No activation semantics | exercise pages cannot explain participation | Activation model (§8) |
| No layer system | the Filament path renders one body; no anatomy view | Presentation (§9) |
| No rig/asset validation | a wrong bone map, bind pose or weight set fails visually, late | Validation L2–L6 (§12) |
| No anatomical acceptance gate | nothing distinguishes "the joints are right" from "the anatomy is right"; a plausible model can pass every numeric rule | L7 review (§12.4) |
| No morph validation | morphology can silently break joints or anatomy | Validation L6 (§12) |
| Device evidence | one informational spike run (§3.4); an FPS budget, thermal drift and context loss are still unmeasured | stage 1 records a run; formal FPS/thermal gating is deferred by the owner (§12.3) |

---

## 4. Target architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ MOTION MODEL — animation/ + poses/            (frozen; this RFC adds nothing)│
│ PoseBuilder intent → IK stage → ConstraintSolver → Finalizer → SkeletonPose  │
│ Owns: joint transforms, contacts, posture, validation of joint-space geometry│
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │  SkeletonPose (published, reused; read-only)
        ┌───────────────────────┴─────────────────────────┐
        │                                                 │
        ▼                                                 ▼
┌───────────────────────────────┐         ┌───────────────────────────────────┐
│ ANATOMICAL MODEL — anatomy/   │         │ CHARACTER APPEARANCE — character/ │
│ (new, rig-independent)        │         │ (PR #385 baseline, extended)      │
│                               │         │                                   │
│ AnatomyDefinition (static)    │         │ CharacterProfile (static)         │
│  segments, landmarks,         │         │  rig id, meshes per layer,        │
│  muscle attachments/paths,    │         │  morph presets, materials, tier   │
│  wrap surfaces, roles         │         │                                   │
│                               │         │ CharacterRig (static)             │
│ AnatomySolver                 │         │  GltfRig + BoneMap + calibration  │
│  pose + definition + body     │         │                                   │
│      profile → AnatomyFrame   │         │ PoseRetargeter → base bone buffer │
└───────────────┬───────────────┘         └───────────────┬───────────────────┘
                │  AnatomyFrame: paths, lengths, landmarks │
                ▼                                         ▼
        ┌───────────────────────────────────────────────────────────────┐
        │ PRESENTATION — one Filament scene, N layers                   │
        │  skin · superficial muscles · deep muscles · skeleton · select │
        │  muscles: path-driven helper bones on authored muscle meshes   │
        │  skeleton: rigid bone meshes from the same bone buffer         │
        └───────────────────────────────────────────────────────────────┘
```

### 4.1 Motion Model (unchanged)

**Owns:** joint positions and rotations, exercise motion, IK, contacts and posture settlement, movement phases,
joint-space validation. **Never depends on:** Filament, meshes, materials, muscles, characters, anatomy.

**Contract to the rest of the system:** exactly one published artifact per frame, `SkeletonPose`, immutable to
consumers, valid until the next production call on the same pipeline. Nothing in this framework may require the engine
to retain a frame, add fields to the carrier, or expose internals (`roots`, `settlementResult`, authoring intent).

One clarification that matters for reviewers: the engine's 33 joints are **not** a deformation rig and **not** an
anatomical ontology. They are a solve topology. The framework adds the anatomical semantics alongside, not inside.

### 4.2 Anatomical Model (new)

Three distinct things share the word "anatomy" and are separated here:

| Concept | What it is | Lifetime | Where it lives |
| --- | --- | --- | --- |
| **Anatomical definition** | static knowledge: which muscles exist, where they attach, how thick they are, which obstacles they wrap, which roles they may take | versioned asset, shared by all characters | `anatomy/anatomy_v1.json` (text, diffable) |
| **Anatomical frame** | per-frame derived state: solved path points, `modelledPathLength`, `restLength`, `relativePathLengthChange`, `visualTendonPortionLength`, landmark positions | one frame, reused buffers | runtime `AnatomyFrame` |
| **Anatomical geometry** | the visible mesh of a muscle | renderable, cached | character asset (GLB) or generated |

The model **derives position from the Motion Model** and has no solver of its own: it never calls IK, never moves a
joint, never resolves a contact. If the anatomy and the skeleton disagree, the anatomy is wrong by definition.

The model is **rig-independent**: it references `Joint` identifiers and offsets in those segments' derived frames (§7.2),
in metres, never glTF bone names. A new character rig, a new species of mesh, or a different morph preset changes nothing
in the anatomy data.

### 4.3 Character Appearance Model

**Owns:** visual identity — mesh, materials, hair, clothing, morph presets, quality tier, rig asset. It consumes the
Motion Model directly (to pose the body) and the Anatomical Model (to place muscle layers).

Rules:

- A character is **configuration + assets**, not code (`docs/CHARACTER_FRAMEWORK.md` §5 pattern, kept).
- Adding a character adds no exercise, no pose, no muscle definition and no animation.
- The same `AnatomyDefinition` id is shared by every character; a character may declare **body-family overrides**
  (named, sparse, reviewed) where a shared attachment is measurably wrong for that family (§6.3, §7.3).
- Bone lengths are part of the **motion** identity, not the appearance identity. v1 characters share
  `SkeletonDefinition.DEFAULT_ADULT`; height is uniform scale in the asset, not per-bone proportions (§14, decision 4).
  That is a v1 boundary, not a claim that morphs and scale cover every morphology — a different body plan needs a
  versioned rig profile and a revalidation of poses, attachments and retargeting (§6.1).

### 4.4 Presentation

A presentation is a composition of layers over one Filament scene, each with its own asset and visibility state.
Layers are independent of exercises: the exercise selects motion, the user selects layers.

### 4.5 Dependency rule

Allowed edges only:

```
animation/ + poses/  ←  render/    ←  ui/
animation/ + poses/  ←  anatomy    ←  character  ←  ui/
animation/ + poses/  ←  character
character            →  (Filament only, at the renderer boundary)
```

The motion core is `animation/` + `poses/`, and it is pure Kotlin: **no file in either package imports `androidx.*`**
(Compose included), **`android.*`**, **`ui.*`** or **`render.*`**, and by review no Filament either. The first four are
enforced; the Filament fence is not. What `ArchitectureBoundaryTest` (added by #390) asserts over every `.kt` file in
the two packages is exactly:

| Test | What it rejects |
| --- | --- |
| `theMotionCoreImportsNoComposeOrFrameworkCode` | `androidx.compose.`, `android.`, `com.monkfitness.app.ui.`, `com.monkfitness.app.render.` |
| `theMotionCoreImportsNoOtherAndroidxCode` | any other `androidx.` artifact — a core that pulls in one androidx library has started depending on the platform |

There is no Compose allow-list inside `animation/` any more, because there is no Compose inside `animation/`. The 2D
presentation and the playback adapter moved to `render/` (#390: `render/SkeletonRenderer.kt`,
`render/ComposeAnimationController.kt`), which is where Compose code is allowed to live; `render/` depends on the motion
core, never the reverse. A new Compose import in `animation/` or `poses/` fails the test on the next run.

Forbidden edges:

- `animation/` or `poses/` importing `anatomy/`, `character/`, `ui/`, `render/`, or `com.google.android.filament.*` —
  `ui/` and `render/` are denied by `ArchitectureBoundaryTest` today; the rest are review-only obligations, and a
  Filament import is **not** caught by the test's `android.` prefix (see the scope note below);
- `anatomy/` importing `character/`, Filament, or Android UI;
- `character/` calling IK, constraint solving, or pose mutation;
- any module other than the renderer importing `filament`/`gltfio`.

**Scope of the enforcement: review-only, not enforced.** `ArchitectureBoundaryTest` walks `animation/` and `poses/`
only, and within them only the four prefixes above — there is **no `com.google.android.filament.` rule** in its forbidden
list, and `character/` legitimately imports Filament at its renderer boundary. The Filament fence is also *not* covered
sideways by the `android.` prefix: `com.google.android.filament.` does not start with `android.`, so the earlier claim
that the prefix rule catches it is wrong. The `anatomy/` and `character/` edges and the "only the renderer imports
`filament`/`gltfio`" rule are architecture decisions **reviewed by a human**, not assertions of a green test; when those
packages exist and are added to the test's source set, the same two-test shape extends to them. Every fence in this
section that is not in that forbidden list is labelled review-only, no roadmap gate may cite it as automated, and
adding the Filament prefix to the list is a one-line stage-1 change rather than a claim this document can make today.
That is a gap in enforcement, not a licence.

---

## 5. Ownership and data flow

### 5.1 Ownership matrix

| Object | Owner | Producer | Consumers | Lifetime |
| --- | --- | --- | --- | --- |
| `SkeletonPose` | Motion Model | pipeline (finalizer publish) | 2D renderer, character, anatomy, validator | one frame; reused |
| `PoseMetadata` | Motion Model (per pose) | pose builder | pipeline, UI (camera, loop mode, support declaration) | static per pose |
| `SkeletonDefinition` | Motion Model (external) | caller | engine, calibration, anatomy scale | static per app |
| `AnatomyDefinition` | Anatomical Model | authored asset | anatomy solver, validators, education UI | static, versioned |
| `BodyProfile` | Anatomical Model (input) | character profile → anatomy | anatomy solver | static per character |
| `AnatomyFrame` | Anatomical Model | `AnatomySolver` | muscle layer, skeleton overlay labels, validators | one frame; reused |
| `BoneMap` | Character Model | asset validation | `PoseRetargeter`, muscle helper mapping | static per rig asset |
| base bone buffer | Character Model | `PoseRetargeter` | Filament renderables | one frame; reused |
| helper bone buffer | Character Model | `MuscleLayerSkinner` (from `AnatomyFrame`) | muscle renderables | one frame; reused |
| `MuscleLayerState` (per muscle visibility/selection) | Presentation | UI | renderer, education panel | per session |
| `ActivationTable` | Anatomical Model (data) | authored asset | muscle layer colouring, education UI | static, versioned |

No object in this table is written by two subsystems. **"One bone buffer" is a design option, not a contract.** One
Filament skin has one joint order and one inverse-bind array, so appending helper bones to the body's skin only works if
the asset declares that joint order up front; the runtime cannot add joints to a live skin (§10.3). If a shared buffer
is used, all five of the following are required and each is an asset-validation check: one declared joint order covering
the base and helper ranges; a single owner per index (base range = retargeter, helper range = muscle skinner, never
both); an explicit remap from `MuscleHeadId` to helper indices; inverse binds authored against the same bind pose; and an
instance-to-skin map naming which renderable reads which skin. The alternative — one skin per regional renderable with
its own remapped palette — is equally supported here, and is what the 255-bone limit forces for v2 (§10.3).

### 5.2 Frame flow

```
1  engine: builder.build → pipeline.produceFrame → SkeletonPose (published)
2  character: PoseRetargeter.boneMatrices(pose, …) → base bones            [rotations from joints]
3  anatomy:   AnatomySolver.solve(pose, def, bodyProfile) → AnatomyFrame    [paths, lengths]
4  character: MuscleLayerSkinner.write(AnatomyFrame) → helper bones         [path-driven transforms]
5  renderer:  upload every skin's palette + the prefiltered dynamic geometry + morph weights; render visible layers
6  observers: RigValidator / AnatomyValidator / ExerciseValidator (read-only)
```

Step 3 must happen after step 1 and before step 4; steps 2 and 3 are independent of each other and may run in either
order (they both read only the published pose). This is the reason the anatomy model does not need the visual rig: it
works in engine space, and the rig only converts its output into skinning transforms.

### 5.3 Interfaces

```kotlin
// ---- Anatomical Model -------------------------------------------------------
package com.monkfitness.app.anatomy

/** Static, versioned anatomical knowledge. Shared by every character. */
class AnatomyDefinition(
    val id: String,                  // "anatomy_v1"
    val version: Int,
    val muscles: List<MuscleDefinition>,
    val segments: List<SegmentDefinition>,   // anatomical names for the canonical segment frames
    val wrapSurfaces: List<WrapSurface>,
    val landmarks: List<LandmarkDefinition>,
    val epsNumMetres: Float          // the numeric margin of §7.4.1: one value per version, never a per-call argument
)

/**
 * What the pass loop did for one head, kept apart from the geometric cause (`PathReason`, §7.4.1c). `CLEARED` means a
 * pass was accepted and an exhausted search is `SEARCH_EXHAUSTED` or `PASS_CAP`; `REJECTED_BEFORE_SEARCH` is a head a
 * guard refused before a pass was entered, and `REJECTED_IN_SEARCH` is a head an abort stopped inside one — the two are
 * different events with different reporting. `NOT_PROCESSED` is the **declared zero value**, so a fresh `IntArray` reads
 * correctly before `clear()` runs; every other value keeps its ordinal, with the appended one last.
 */
enum class SearchOutcome {
    NOT_PROCESSED, CLEARED, SEARCH_EXHAUSTED, PASS_CAP, REJECTED_BEFORE_SEARCH,
    REJECTED_IN_SEARCH
}

/**
 * One frame of derived anatomy. Every per-frame number lives in a preallocated parallel array indexed by head; the
 * buffers are reused, so a consumer must finish before the next solve.
 *
 * `headCount` is the number of *instances*: Σ over muscles of heads × sides. `pointCapacity` is the post-insertion
 * maximum, not the resampling `N` of §7.4 step 3, because step 4 inserts points.
 */
class AnatomyFrame(headCount: Int, pointCapacity: Int, landmarkCount: Int) {
    /** Identity per slot, in a fixed order: the only key a consumer may use. Backs `MuscleState.id`/`side`/`headIndex`. */
    val heads: List<MuscleHeadId>

    val modelledPathLengths = FloatArray(headCount)          // along this head's path, wrap detours included
    val restLengths = FloatArray(headCount)                  // same solver at the reference stance; finite and > 0
    val visualTendonPortionLengths = FloatArray(headCount)    // authored visual partition, not a measured tendon
    val relativePathLengthChanges = FloatArray(headCount)     // modelledPathLength / restLength - 1

    /**
     * PathStatus ordinal — VALID, INVALID or NOT_PROCESSED. `NOT_PROCESSED` is the declared zero value of `PathStatus`,
     * `PathReason` and `SearchOutcome` (§7.4.1c names the reason enum and its zero), so a zero-initialised array is
     * already correct; `clear()` re-asserts it. ("The solver never reached this head" and "the solver rejected this
     * head" are different facts.)
     */
    val statuses = IntArray(headCount)
    val reasonCodes = IntArray(headCount)        // PathReason ordinal; NO_REASON only where status is VALID
    val searchOutcomes = IntArray(headCount)     // SearchOutcome ordinal; what the loop did, never the geometric cause
    val failingSegments = IntArray(headCount)    // segment index, or NO_INDEX
    val failingObstacles = IntArray(headCount)   // index into the definition's obstacle list, or NO_INDEX
    val pointCounts = IntArray(headCount)

    /** One entry per instance, keyed (muscleId, side): the conjunction of that instance's head statuses (§7.4). */
    val renderable = BooleanArray(instanceCount)

    /** Path points, engine world units. Stride: `head * 3 * pointCapacity + point * 3 + {x, y, z}`. */
    val paths = FloatArray(headCount * 3 * pointCapacity)
    val landmarks = FloatArray(landmarkCount * 3)

    /** One view per head, built once at construction; reading it allocates nothing. */
    val muscleStates: List<MuscleState>

    /**
     * Per head, the ordered union of §7.4.2 as definition indices: `obstacleIndexMap[head][unionIndex]` is the index into
     * `AnatomyDefinition.wrapSurfaces`. Built once with the frame — the union does not depend on the pose or the solve, so
     * this is static per `(definition version, head)` and is the only sanctioned route from a union position to the
     * definition's file order. `failingObstacles` is a definition index; a consumer that needs a projection-order
     * position reads it here, and `NO_INDEX` is never read as `0`.
     */
    val obstacleIndexMap: List<IntArray>

    /**
     * Start of every solve: status, reason and outcome NOT_PROCESSED, counts 0, indices NO_INDEX, lengths and visibility
     * cleared. A head the solver never reaches keeps NOT_PROCESSED in all three, which no consumer may read as VALID, as
     * INVALID or as a result of the search. `obstacleIndexMap` is **not** cleared: it is definition data, not per-frame
     * state.
     */
    fun clear()
}

/**
 * Read-only view over one head's slot in an [AnatomyFrame]. Identity and metadata are backed by the immutable
 * definition; every per-frame value is a getter into the frame's arrays, so this type never owns a value.
 */
class MuscleState internal constructor(
    private val frame: AnatomyFrame,
    private val slot: Int,
    private val definition: MuscleDefinition   // id, name, side, layer: static, never per frame
) {
    val id: String get() = definition.id
    val name: String get() = definition.name
    val side: Side get() = frame.heads[slot].side
    val headIndex: Int get() = frame.heads[slot].headIndex   // 0 for single-head muscles (§7.5)
    val headCount: Int get() = definition.heads.size
    val headLabel: String get() = definition.heads[headIndex].label   // authored, never "head 0" (§9.2)
    val layer: MuscleLayer get() = definition.layer           // static definition data, never per frame
    val pointCount: Int get() = frame.pointCounts[slot]
    val modelledPathLength: Float get() = frame.modelledPathLengths[slot]
    val restLength: Float get() = frame.restLengths[slot]
    val visualTendonPortionLength: Float get() = frame.visualTendonPortionLengths[slot]
    val relativePathLengthChange: Float get() = frame.relativePathLengthChanges[slot]
    val pathStatus: PathStatus get() = PathStatus.entries[frame.statuses[slot]]
    val reason: PathReason get() = PathReason.entries[frame.reasonCodes[slot]]
    val searchOutcome: SearchOutcome get() = SearchOutcome.entries[frame.searchOutcomes[slot]]
    val failingSegment: Int get() = frame.failingSegments[slot]
    val failingObstacle: Int get() = frame.failingObstacles[slot]

    /** Reads `frame.paths` at this head's offset; allocates nothing. */
    fun pathPoint(index: Int, out: Vector3)

    /** Zero-allocation read-only view over this head's whole path; valid until the next solve. */
    val path: PathView
}

/**
 * The reviewed role for one muscle in one exercise, or `null` where no reviewed row exists.
 *
 * A role is a claim about a task, not a property of a muscle (§8.2), so the exercise is part of the key and is supplied by
 * the readout's current exercise. An `UNREVIEWED` or `UNKNOWN` row is returned as `null`, never as a value: a row that may
 * not be displayed must be indistinguishable from an absent one (§9.2).
 */
fun interface ReviewedRoleSource {
    fun roleFor(exerciseId: String, muscleId: String): MuscleRole?
}

/** Segment index and obstacle index have no value when the head is VALID; -1 is that no-value convention. */
const val NO_INDEX = -1

/** Pure: reads the published pose, writes only its own output buffer. */
class AnatomySolver(private val definition: AnatomyDefinition) {
    fun solve(pose: SkeletonPose, profile: BodyProfile, out: AnatomyFrame): AnatomyFrame
}
```

**One allocation, then none per frame.** An earlier sketch declared `length`, `restLength`, `tendonLength`, `strain` and
`pathStatus` as `val`s on `MuscleState` while saying the buffers were reused — with one state per head that is a fresh
object graph every frame, exactly the allocation the carrier discipline exists to avoid. The values now live in the
frame's parallel arrays and `MuscleState` is a read-only view over them: `frame.muscleStates` is built once, `solve`
writes only arrays, and a consumer that needs a value later copies the number, not the state.

**Nothing outside the frame reads `paths` directly.** A path is read either through `pathPoint(index, out)` — which
bounds-checks `index` against `pointCount` and returns the point in the caller's vector — or through `path`, a
zero-allocation view whose `get(index, out)` does the same and whose `size` is `pointCount`. Both address the slot with
the documented `head * 3 * pointCapacity + point * 3` stride, so no consumer re-derives an offset and no consumer can
hold a reference to the buffer past the next solve.

**The array layout is a contract, with overflow checks.** `paths` is `3 · headCount · pointCapacity` floats and every
write goes through the frame, which raises `BUFFER_CAPACITY` for that head (§7.4.1) rather than growing an array or
truncating a path. `pointCapacity` is the post-insertion maximum: a capacity sized for the resampling `N` alone
under-counts by exactly the points construction inserts, which is the defect this layout removes.

**A status is never inherited, and "never reached" is its own state.** `clear()` writes `NOT_PROCESSED` to every
slot's status, reason and outcome, `NO_INDEX` to both indices and `0` to `pointCount` before the solve touches
anything, so a head the solver never reaches — a definition error, an early return, a reused buffer — reads
`NOT_PROCESSED` rather than the last frame's `VALID`.
`NO_REASON` is reserved for `VALID` and is therefore never a value `clear()` writes: an earlier revision wrote
`INVALID + NO_REASON`, which the reason table itself forbids (NO_REASON is "only where the status is VALID"), so a
consumer could not tell a clean start from a failure with a missing reason. Three states, three meanings:

| Status | Meaning | Consumer |
| --- | --- | --- |
| `VALID` | the solver accepted this head this frame under the §7.4.1 predicate | draw it; `reason = NO_REASON` |
| `INVALID` | the solver rejected it, with a reason from §7.4.1c | do not draw it (§7.4 mask) |
| `NOT_PROCESSED` | the solve never reached it — an early return, a definition error, a fresh buffer | do not draw it, and report it: it is a **bug**, not a clean frame | Reason precedence is fixed and deterministic, and it is stated once
in the authoritative reason table (§7.4.1c): the first matching row in table order wins, so two implementations that hit
two conditions report the same reason.

```kotlin
// ---- Character Appearance Model --------------------------------------------
package com.monkfitness.app.character

/** Consumes anatomy output; owns only the helper-bone range of the skin. */
class MuscleLayerSkinner(
    private val rig: GltfRig,
    /**
     * One entry per head: helpers in origin→insertion order, shared by the muscle's single visual belly. Indices are in
     * the skin this skinner was built for; a shared body+helper buffer requires the joint order and remap of §5.1.
     */
    private val helperBones: Map<MuscleHeadId, IntArray>,
    private val rest: HelperRestFrames
) {
    fun write(frame: AnatomyFrame, baseWorld: FloatArray, out: FloatArray)
}

/** Per-instance identity. `side` is part of the key: a bilateral muscle is two instances with independent paths. */
data class MuscleHeadId(val muscleId: String, val side: Side, val headIndex: Int)
```

```kotlin
// ---- Presentation -----------------------------------------------------------
enum class CharacterLayer { SKIN, MUSCLES_SUPERFICIAL, MUSCLES_DEEP, SKELETON, SELECTED }

/**
 * Selection identity. `side` is part of it because a bilateral muscle is two instances with independent paths: a bare
 * `muscleId` cannot say which side the athlete tapped, and one side must stay unselected while the other is shown.
 * Heads are not part of it — the readout enumerates the selected instance's heads (§9.2).
 */
data class MuscleSelection(val muscleId: String, val side: Side)

interface CharacterLayerController {
    fun setVisible(layer: CharacterLayer, visible: Boolean)
    fun setSelected(selection: MuscleSelection?)   // selection is presentation state only
}
```

**Selection is `(muscleId, side)`, and the readout renders heads.** An earlier signature took a bare `muscleId`, which
cannot address a bilateral instance, and `MuscleState` exposed `id`, `name`, `side`, `headIndex`, `headCount` and the
per-head values but **not** `layer` — so the one component that has to show "name, layer, role, relative change" could not
read the layer and could not tell a two-head instance from a one-head one. Both are fixed at the interface rather than
worked around in the UI: `setSelected` takes `MuscleSelection`, and `MuscleState.layer` is a getter over the definition.

`CharacterPoseSource` (`character/CharacterPoseSource.kt:6`, PR #385) already expresses the pose handoff; this RFC
extends the seam downward, not sideways: the same call that produces the pose also produces the anatomy frame and the
bone upload, inside the carrier's valid window.

### 5.4 Why anatomy is outside the pipeline

A tempting alternative is to make anatomy a pipeline stage, so `SkeletonPose` carries muscle paths. Rejected:

- it would put mesh-scale data into the engine's carrier, violating the carrier contract — carrier unity and
  single-owner transfer (`docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` §5 R11) — and would make the engine depend on
  anatomy data, which this framework's dependency rule forbids in its own right;
- it would make anatomy validation part of motion validation, which §12 forbids;
- it would force every pose build (including headless snapshots and tests) to pay for anatomy;
- it would make the engine depend on anatomy data versioning.

The anatomy stage is therefore an **observer stage downstream of publication**, with its own frame and its own
validator. It is not part of the motion engine's correctness path.


---

## 6. Shared rig and character morphology

### 6.1 Rig contract `humanoid_v2`

**One rig, versioned, shared by every character.** A character is a mesh + materials + morph set bound to that rig;
it is not allowed to change the skeleton's topology, joint names, rest pose or bind pose. This is what makes one pose
library, one anatomy library and one animation library serve every body.

| Requirement | Rule | Why |
| --- | --- | --- |
| Joint names | `Hips, Spine, Chest, Neck, Head, {Left,Right}Shoulder, {Left,Right}Scapula, {Left,Right}Arm, {Left,Right}ForeArm, {Left,Right}Hand, {Left,Right}UpLeg, {Left,Right}Leg, {Left,Right}Foot, {Left,Right}ToeBase` plus visual-only bones | the resolver matches normalized names (`MonkJointResolver.kt:12-34`, PR #385), and its candidate map carries **no `SCAPULA` alias** — `SCAPULA_A/P` resolves only when the BoneMap PR adds the two entries, so the resolver change is part of that PR and not of this document's rig. Rigs are **first-party and built to `SkeletonDefinition`**: the Mixamo-compatible naming is a convention that keeps the map readable and reviewable, not a promise that a third-party rig can be dropped in — its bone lengths, bind pose and joint placement are calibrated to nothing in this engine (§6.6 A1/A2) |
| Hierarchy | the **23** mapped bones of §6.2 keep their parent chain (`humanoid_v1` carries 21 of them — it has no scapula); scapula sits between clavicle and arm | the engine's girdle DOF (`CHEST → CLAVICLE → SCAPULA → SHOULDER`, `SkeletonFactory.kt:71-80`) only reaches the body if the rig has a scapula bone |
| Rest pose | arms at the sides, matching the engine's rest stance; **not** a T-pose | rest-delta retarget multiplies the source delta onto the target rest (`PoseRetargeter.kt:58-68`); a T-pose bind leaves the figure in T-pose at every frame 0 |
| Bind pose | identical to rest pose **and to the X1 canonical A-pose** | the rig's bind frame is computed from `SkeletonDefinition` (§6.6 X1); `PoseRetargeterRestInvariantTest` proves identity on every bone at neutral (§12.2) — shipped today as `PoseRetargeterTest.theNeutralPoseReproducesTheIdentityOnEveryBone`, §3.1 |
| Units | asset metres; engine units converted only for the pelvis translation (**multiplied** by `CharacterCalibration.unitScale`, ≈ 0.004924 m/u; X3 replaces that with a division by `s = 247.37`, §6.2) and the uniform scale | `PoseRetargeter.kt:78-83`; offsets must stay in asset units because inverse binds were authored against them |
| Scale | one uniform scale per character; no per-bone scaling for appearance | non-uniform bone scale distorts joint volume and is not a morphology tool |
| Fingers | visual-only, 2 segments per finger or a single segment in tier `reduced` | the engine has no finger kinematics beyond `HAND_A`; the rig may add them as static/derived |

`humanoid_v2` target bone counts: **23 mapped bones + up to 30 visual-only bones (fingers, jaw, eyes, twist) + muscle
helper bones** (§7.5). The 30 is a budget for the parts the rig may add, and the arithmetic is checked against what M1
actually carries: 20 finger segments (5 fingers × 2 segments × 2 hands) + a jaw + two eyes + up to six twist bones (upper
arm, forearm and thigh per side) = **29 of 30**, so one bone of headroom. M1's rig keeps **10** of them — 5 single-segment
fingers per hand, which is what the PR #385 asset authors — and the remaining 19 (a jaw, two eyes, six twists and the ten
spare finger segments a two-segment-per-finger v2 rig would add) stay inside the same allowance: `10 + 9 + 10 = 29 ≤ 30`.
Visual-only means the engine has no joint for any of them; it does **not** mean unmapped-and-forgotten (§6.2, X2). The
current PR #385 asset has 32 joints, 5 single-segment fingers per hand, no scapula, no twist bones. The scapula addition
is the only rig change that materially affects anatomy quality.

**X2 — the ten finger bones stay, as visual-only bones.** *Reversed by owner: fingers kept.* The engine still has no
finger kinematics — it publishes `HAND_A` plus `WRIST`, `PALM`, `KNUCKLES` and `FINGERTIPS` in a straight chain — so a
finger bone is **visual-only**: it has no engine joint, it contributes **no A1 residual** and is **excluded from
`RigLandmarkAlignmentTest`** (listed as unmapped, not as a failure), and it is counted against the **visual-bone budget**
above (10 of the 30), never against the 23 mapped joints. Keeping them is a deliberate owner decision in favour of the
silhouette; the mechanics are unchanged, because the hand chain is rigid whatever the rig carries.

**Motion rule for the fingers in M1: rigid inheritance.** Each finger bone inherits its hand bone's frame
**parent-relative** — the rule already specified for visual-only bones (§6.2, "an unmapped bone is not a bone that holds
still"): the finger keeps its bind **local** rotation relative to `Left/RightHand` and rides it, so the fingers follow the
hand rigidly with no rotation of their own and no invented articulation. The generator lays them along the hand segment in
the X1 A-pose (§6.6) — continuing the `HAND → FINGERTIPS` direction from the hand head — so the bind silhouette reads as
an open, relaxed hand.

**Later option, not M1 scope: drive the fingers from the published hand direction.** The engine does publish a hand
direction — `HAND → FINGERTIPS`, 22 units in 65 of 66 builders and 0 in `pike_pushup_standard`, where the pose declares a
manual override and the inheritance rule above applies instead. Driving each finger's frame from that direction would let
the hand's authored articulation reach the fingers. It is deferred because the direction is **one vector for five
fingers**: it carries no per-finger information, so it can only re-orient the whole hand — which rigid inheritance already
does — and it would put a second consumer on a quantity whose one degenerate builder is a manual override.

Because the join is not one-to-one, both directions are stated once, measured from `MonkJointResolver.kt:12-34` against
the parsed asset:

| Engine joints with **no** asset bone (12) | Why |
| --- | --- |
| `SCAPULA_A`, `SCAPULA_P` | the asset has no scapula; the resolver maps `CLAVICLE_*` to `Left/RightShoulder` and stops there |
| `WRIST_A/P`, `PALM_A/P`, `KNUCKLES_A/P`, `FINGERTIPS_A/P` | the resolver maps none of the hand chain; the fingers stay visual-only and inherit the hand rigidly (X2) |
| `HEEL_F`, `HEEL_B` | the asset has `Foot` and `ToeBase` only |

| Asset skin joints with **no** engine joint (11) | Why |
| --- | --- |
| `HeadTop_End` | a crown node; the engine's last joint is `HEAD_POS` and it has no crown (§7.2) |
| `LeftHandThumb/Index/Middle/Ring/Pinky`, `RightHand…` (10) | **visual-only, and kept in M1** (X2, reversed by owner): no engine joint, no A1 row, counted in the 30-bone visual budget, riding the hand by §6.2 inheritance |

**Those are the old asset's counts, not the requirement.** On the shipped `humanoid_v1` asset, 32 skin joints − 11 = 21
mapped and 33 engine joints − 21 = 12 undrivable, which is the whole of *its* mismatch: a rig that adds the scapula pair
closes two of those twelve, and nothing closes the ten hand joints without finger kinematics the engine does not have.
**The map of the table above carries 23**, because it is five central joints plus nine bilateral pairs — `CLAVICLE`,
`SCAPULA`, `SHOULDER`, `ELBOW`, `HAND`, `HIP`, `KNEE`, `ANKLE`, `TOE` — so `5 + 9 × 2 = 23` mapped bones and `33 − 23 =
10` undrivable engine joints (`WRIST`, `PALM`, `KNUCKLES`, `FINGERTIPS` and `HEEL`, per side). The asset side goes from 32
skin joints (21 mapped + 11 visual-only) to **34** (23 mapped + the same 11), and the scapula pair is the whole
difference. A count that says 21/12 describes the asset this document replaces, which is why `BoneMapCoverageTest`
(§12.2) derives its numbers from the map instead of from that inventory.

**The 11 orphan asset bones are not an error**: they are visual-only bones that a v2 rig may legitimately need (a crown,
five fingers per hand here, more if a family adds a jaw or eyes), and the asset validator reports them as visual-only
rather than as unmapped-and-broken (§10.5).

**This is a v1 scope constraint, not an architectural law.** One rig is what makes M1 affordable: one `BoneMap`, one
rest invariant, one pose library, one anatomy library. It is not a claim that a single rig, surface morphs and uniform
scale can represent every human body. Segment-length proportions — a long femur, a short humerus, a different
torso-to-limb ratio — are a motion input, not a surface, and no morph target can produce them (§6.3).

A materially different body plan therefore needs a **separately versioned rig profile**: a new `SkeletonDefinition` id,
a `humanoid_vN` rig whose joint names, hierarchy, rest pose and bind pose still satisfy this section's contract, and a
revalidation of every pose, every attachment and every retarget path against it. That is a deliberate extension point
with a real cost — a motion project, not an appearance setting — and it is deliberately not taken in M1: M1 ships one
rig and one `SkeletonDefinition` (§15). Nothing in this document forbids the extension; it only refuses to pretend it is
free.

### 6.2 Mapping contract, 33 → rig

`BoneMap` is data, resolved once per asset and validated (§12 L2):

```kotlin
class BoneMap(
    val byJoint: Map<Joint, String>,                          // direct targets
    val distribution: Map<Joint, List<Pair<String, Float>>>,  // one-to-many, e.g. LUMBAR -> Spine 0.6, Spine1 0.4
    val basis: Quat                                           // asset -> engine axes: Ry(+90°) (X3)
)
```

**What a one-to-many split distributes.** It distributes a *rotation*, never quaternion components. An entry with weight
`w` gives its bone the fractional rotation about the engine joint's own axis:

```
R_w = quatFromAxisAngle(axis(R), angle(R) · w)      // slerp from identity, by w
```

The list is ordered **proximal → distal**, and each entry is applied on top of its parent's fraction, so the last bone in
the list carries the full rotation. With `Spine 0.6, Spine1 0.4` — both about the same axis — the composed rotation is
`angle(R) · (0.6 + 0.4) = angle(R)`: the weights "sum to 1" in exactly that sense, a sum of fractional rotations of one
axis, not a sum of independently meaningful numbers.

**Component weighting is forbidden — for an exact reason, not "it shears".** `q = normalize(Σ wᵢ qᵢ)` over quaternion
components is **not generally the specified fractional rotation**. It is that rotation only in the special case where
every term shares one axis; the normalised sum of two rotations about different axes is a rotation about a *third* axis,
which is neither the parent's fraction nor the child's, so the split distributes an amount the definition never asked
for. It fails silently because the result is still a unit quaternion. A split whose entries do not share the joint's
axis is a **map error reported at L2**, not something the runtime silently reinterprets.

**Multiplication order, bases, coordinate spaces and bind transforms, stated once** (all of §6.2). **X3 — the retarget is
position-driven.** *Confirmed by owner.* The engine publishes positions, and the segment orientation the rig needs is the
direction between two published positions — the same quantity §7.2 derives for anatomy. **Every rotation below is tagged
with the coordinate space it lives in.** That tagging is load-bearing, not pedantry: `D` is computed from published
engine positions while the rest rotation it multiplies is asset data, and a rotation matrix is not basis-free — a delta
expressed on engine axes and applied to an asset-space frame is the same numbers giving a different physical rotation.

```
d_bind_asset(u)    = normalize(pivot(child_u) − pivot(u))             // ASSET, the X1 bind pose (§6.6)
B                  = Ry(+90°)                                         // asset → engine axes; B⁻¹ = Ry(−90°)
d_bind_engine(u)   = B · d_bind_asset(u)                              // ENGINE
F_posed_engine(u)  = B · R_target_world(parent) · B⁻¹                  // ENGINE, this frame's parent, in engine axes
a(u)               = F_posed_engine(u) · d_bind_engine(u)              // ENGINE — the bind direction the POSED parent carries
d_seg_engine(u)    = normalize(p_child − p_parent)                     // ENGINE, published positions
D_engine(u)        = shortestRotation(a(u), d_seg_engine(u))           // ENGINE, §7.2's X9 rule and tie-break
R_target_world(u)  = B⁻¹ · D_engine(u) · B · R_target_world(parent)     // ASSET — X9's R = D · P, conjugated once
R_local(u)         = R_target_world(parent)⁻¹ · R_target_world(u)
translation        = B⁻¹ · (p_PELVIS − rest pelvis) / s                 // ASSET metres: basis first, then ÷ unitsPerMetre
```

**The scale the shipped code applies is not `s`, and the difference is not a rounding.** `CharacterSession.kt:175`
computes `CharacterCalibration.unitScale(definition, restHeight())` = `restHeight / (HEAD_POS.y − min(TOE_F.y,
TOE_B.y))` = `1.78 / 361.50` = **0.004924 m/u** (an implied **203.09** u/m) and `PoseRetargeter.kt:78-83` **multiplies**
the pelvis displacement by it. Dividing by `s = 247.3684` is a different operation: at the pelvis's own height, 235 units
move the hips **1.157 m** under the shipped scale and **0.950 m** under `s`, a ratio of **1.218**. The contract above
replaces the parameter with `s` and the multiplication with the division; the two are never interchangeable, and no
residual in this document was computed with `unitScale`.

**Three quantities are kept apart throughout this section, because a claim about one of them is not a claim about
another:**

- **frame coordinates** — a vector written on a segment's own axes, e.g. `d_bind(u) = [d]_rest(parent)`, the child's rest
  direction *in its parent segment's rest frame*. It is not a world vector and must never be transported as one;
- **absolute world rotations** — `R_world(S)`, the rotation taking frame coordinates of segment `S` to engine world (or, in
  the asset basis, to asset world);
- **bind-relative deltas** — `ΔR(S) = R_world(S; posed) · R_world(S; bind)⁻¹`, the only quantity the rest invariant is
  about.

`d_bind_engine(u) = B · d_bind_asset(u)` above is a *frame-coordinate* statement: the asset bind direction is read in the
parent's rest frame and carried through the basis. Read instead as an engine **world** vector and transported by the posed
parent, it is rotated twice at rest — which is the defect the next paragraph removes.

**The rest invariant is about deltas, and the anchor is the asset's rest, not the derived frame.** At the X1 bind pose
`ΔR(S) = identity` for every segment and `R_target_world(bone) = R_rest_world(bone)` for every bone;
`PoseRetargeterRestInvariantTest` asserts exactly that. It does **not** assert (and nothing may assume) that a world
rotation is identity at bind: the derived pelvis root frame of §7.2 is `diag(−1, 1, −1)` at X1 — a 180° rotation about `Y`
— and every segment inherits it, because `D = identity` at bind and each segment's frame is its parent's. A1's rest row is
the *node* rest orientation, which is identity (`SkeletonFactory`), so the two quantities must not be conflated: see the
root rule below and §15 stage 1 step 8.

**The correction is taken from the parent-transported bind direction, and that is the whole of X3.** The previous
revision computed `D` from the **untransported** `d_bind_engine` and then composed it *after* the parent, which applies
the parent's swing twice. With `B = Ry(+90°)` the asset bind direction `+Z` maps to engine `+X`; let the parent's posed
delta be `Rz(+90°)` and the published child direction be `+Y`. The untransported rule computes
`D = shortestRotation(+X, +Y) = Rz(+90°)`, applies it after the parent, and lands on engine **`−X`** — twice the
rotation. X9 first transports the bind direction by the parent (`a = Rz(+90°) · (+X) = +Y`), finds `a = b`, and takes
`D = identity`, landing on **`+Y`**. Both values are reproduced in the review script; this is not a tie-break
difference, it is a different rotation. **It is a transport-only control**: because the transported direction already
equals the published one, the child's correction is `identity` and the case cannot see the basis at all, which is why
`PoseRetargeterBasisTest` also carries a both-nonidentity case (§12.2) — parent `Rz(+45°)`, `D` a 64.76° rotation,
noncommuting basis — where each wrong composition misses the published direction by its own pinned amount: 3.421° for
the reversed order, 42.800° for the untransported correction, 45.000° for both wrong at once, and 86.318° for a dropped
basis, whose engine image is `B · D · B⁻¹` rather than `D` (§12.2).

Three properties follow, and each one corrects a construction that looked right. (i) *One declared space, one
conjugation.* `a` is the bind direction as the **posed** parent carries it, so `D` is only the rotation the segment still
has to make, and `R = D · P` — X9's rule — becomes `B⁻¹ · D · B · R_target_world(parent)` in asset space. The basis is
applied once, to the whole composition, so no factor is ever multiplied by a delta expressed on the wrong axes. (ii)
*Parent roll is carried, and it is now carried by the same factor that carries the swing.* The parent's posed rotation
enters through `a` **and** through the composition, so a parent rolled 90° about a child direction that did not change
hands the child that 90° of roll — a bare `D · R_rest` drops it, leaving two frames that agree on direction and disagree
by 90° on every attachment offset. (iii) *The rest invariant holds when the rig's bind agrees with the engine's bind, and
it is a statement about deltas.* At bind `R_target_world(parent) = R_rest_world(parent)`, so `a` is the bind direction the
parent's **rest** rotation carries, and `D = identity` exactly when the rig's bind head positions put that segment on the
engine's published bind direction. On `humanoid_v1`, which fails A1 by 350.2×, **neither this rule nor the one it
replaces** yields identity, so `PoseRetargeterRestInvariantTest` is expected red there and nothing is weakened to make it
green.

**Direction agreement is weaker than the frame agreement the invariant needs, and A1's `τ_rot` supplies neither — the two
conditions are stated apart because an earlier revision ran them together.** At bind the composition hands the child its
**parent's** rest rotation, so the invariant is `R_target_world(u) = R_rest_world(u)`, not `D = identity` on its own:

- **What the generated identity-rest rig guarantees.** `SkeletonFactory` creates every node with identity rotation
  (`SkeletonFactory.kt:60-101`, §7.2) and the stage-1 generator writes the root's rest rotation as `B⁻¹ · I · B = I`
  (§15 step 8), so `R_rest_world(u) = I` for every bone. At bind `D = identity` and the composition returns `I` for every
  bone: `R_target_world(u) = R_rest_world(u)` **exactly**, not within a tolerance, which is what
  `PoseRetargeterRestInvariantTest` asserts on that rig.
- **What A1's `τ_rot` alone permits.** A1 bounds each bone's rest orientation against the engine's identity rest
  **independently**, within 0.5° (§6.6), and says nothing about a child's rest rotation relative to its parent's. Take
  parent rest `I`, a child whose rest rotation is `Rx(0.25°)`, and matching bind and published directions `+X`: the
  direction agreement is exact, so `D = identity`, and the prescribed target is the parent's `I` rather than the child's
  `Rx(0.25°)`. The angular error is 0.25° — inside A1 — and the largest matrix-entry error is **0.0043633**, which is
  **43.6× the 1e-4** the stage-1 acceptance allows for the rest invariant (§15). A1 therefore does not imply the
  invariant, and a rig that passes A1 is not thereby a rig this composition can be asserted on.

**The condition the composition actually needs is a common rest rotation** — `R_rest_world(u) = R_rest_world(parent(u))`
for every mapped bone, so that "the parent's rest rotation" and "the child's rest rotation" are the same rotation. The
generated identity-rest rig satisfies it exactly. Any other rig must either satisfy it or preserve each child's
rest-relative rotation, which this document does not specify; a per-bone tolerance such as A1's is not a substitute for
either, and no tolerance on the rest-invariant test can stand in for it. All three properties above are asserted
through the real retarget construction, not against a matrix identity: a frame where **the parent swings and the child's
own direction changes at the same time** (the case that separates this rule from the previous one), a parent-roll case, a
non-axis-aligned direction change under a non-identity basis, and a wrong-basis control (`PoseRetargeterBasisTest`,
§12.2). The previous composition — `D` from the untransported bind direction, applied after the parent — is the negative
control and must fail.

`B = Ry(+90°)` is the one basis: **asset `+Z` forward and `+X` left → engine `+X` forward and `−Z` left**, checked on the
asset's own bind pose (`LeftToeBase` sits at `+Z`, `LeftUpLeg` at `+X`). The uniform scale is `s = 235/0.95 = 247.3684`
**engine units per metre** (§7.2), and the translation is **0 at the pelvis** — the asset's `Hips` already sits at the
engine's rest pelvis height, so the only translation the retarget applies is the joint's own displacement from that rest
pelvis. **The conversion divides, because the units demand it**: an engine-space displacement is in units and the
translation is in asset metres, so a 235-unit displacement is `235 / (235/0.95)` = **0.95 m**, while multiplying gives
`235 × 247.3684 ≈ 58,131.58` — a metre squared per unit, not a length. **The displacement is also taken into the asset
basis before it is applied**, because the result is an asset-space bone translation: for an engine displacement
`(235, 0, 0)` and `s = 235/0.95`, dividing alone gives asset `(0.95, 0, 0)`, and `B · (0.95, 0, 0)` is engine **`−Z`**, not
`+X`. The rule is `B⁻¹ · Δp / s`, whose engine image is `(0.95, 0, 0)` as it must be. A **vertical** displacement cannot
tell the two apart — `(0, 235, 0)` is on the rotation axis, so `B` leaves it unchanged — which is why the control is
non-vertical (§12.2). The **pelvis translation is the only place the unit conversion appears**, because every other
quantity in the block is a rotation and a rotation is dimensionless; there is no second scale anywhere in the retarget.
There is also no rest-delta **rotation** composition of the PR #385 kind and no per-bone accumulation of residual error:
each bone's world rotation is a deterministic function of its own
two endpoints, its parent's posed rotation and the fixed basis. Deterministic is not error-free, though, and the parent
factor is exactly where a rotation error propagates to every descendant — which is why A2 is measured on posed frames and
a bind-stance result is not evidence about them (§6.6).

Two degeneracies are inherited from §7.2 rather than decided here: a segment whose bind direction and published direction
agree gets `D = identity` and therefore the transported rest rotation (the rest invariant, `PoseRetargeterRestInvariantTest`),
and one whose published direction is antiparallel to its bind direction has no minimal rotation — the antipodal tie-break
of §7.2 decides, and continuity across the **branch boundary** `|a × b| = τ_rot` is **not** promised there or here. Both
quantities move: the roll flips by 180°, and the placed direction itself changes by up to `arcsin(τ_rot)` — `0.057295789°`
at `τ_rot = 0.001` — because inside the branch the construction places the direction within that bound of the published
one, while outside it places it exactly on it. The bound is a residual the tests assert; it is not continuity.

`R_target_rest_world` anchors the chain, and for every bone it is the asset's **bind rest world rotation**, compared by A1
against the engine rest orientation within `τ_rot` (§6.6) — which at X1 is the identity, because `SkeletonFactory` creates
every node with identity rotation. The root is the one bone whose rest rotation the stage-1 generator writes rather than
reads, and §15 step 8 states what it writes (`B⁻¹ · I · B = I`). `NonCommutingSplitTest`
(L2) drives a parent and a child about different axes and asserts the composed world rotation, so a reversed order or a
dropped basis fails it; `PoseRetargeterBasisTest` drives a **posed** frame through the real construction with a
non-identity basis and asserts the resulting world direction **and** roll, which neither the identity case nor the
bind-rest case can: both are satisfied by a retarget that ignores the basis entirely.

**The rig root, and the mapped joints whose bind segment has no length, in the same basis.** `PELVIS` has no parent
segment, so `F_posed_engine(PELVIS)` is the **derived root frame** of §7.2 — the hip line and `PELVIS → CHEST` — carried
through `B` like any other parent frame, and the root's correction is that frame's own change since bind:

```
F_root(posed)          = the derived frame on this frame's published positions        // ENGINE
F_root(bind)           = the derived frame at the X1 bind pose                        // ENGINE
ΔF_root                = F_root(posed) · F_root(bind)⁻¹                               // ENGINE, bind-relative
R_rest_world(PELVIS)   = B⁻¹ · I · B = I      // ASSET: the asset's bind rest, which A1 requires to equal the engine rest
R_target_world(PELVIS) = B⁻¹ · ΔF_root · B · R_rest_world(PELVIS)                      // ASSET, this frame
```

**`F_root(bind)` is not the identity, it is not the anchor, and the earlier block was wrong in both places.** At X1,
`Y = (0,1,0)`, `Z = (0,0,−1)` and `X = Y × Z = (−1,0,0)` (§7.2), so `F_root(bind) = diag(−1, 1, −1)` — determinant `+1`, a
**180° rotation about `Y`**, reproduced in the review script — and every segment inherits it at bind because `D = identity`
and a segment's frame is its parent's. The previous revision anchored the root on `R_rest_world(PELVIS) = B⁻¹·F_root(bind)·B`
and concluded the resulting world rotation "is identity at bind". Three things follow, and only the third is true:

- **Conjugation cannot turn that rotation into the identity.** For `B = Ry(+90°)`, `B⁻¹·F_root(bind)·B = F_root(bind)`,
  because `Y` is `B`'s own axis: the conjugated value is still 180°, so the sentence was false however it was read.
- **As an anchor it also breaks the rest invariant on a correct rig.** It makes `F_posed(bind) = B·R_rest·B⁻¹ =
  F_root(bind)`, so at bind `a = F_root(bind)·d_bind` while the rig publishes `b = d_bind`; `D ≠ identity` for every bone
  whose bind direction has a nonzero `X` or `Z` component. Measured by the review script on the X1 directions: the humerus
  is off by **141.4°**, the foot and the scapula by **180°** (the thigh and shin, both along `−Y`, are the only ones that
  survive, because `Y` is the rotation axis). So the invariant failed at bind, and A1's rest-orientation row — rig rest
  within `τ_rot` of the engine rest — failed by 180° at the root.
- **Anchoring on the delta removes the whole problem.** `ΔF_root` is identity at bind *whatever* `F_root(bind)` is, so
  `R_target_world(PELVIS) = R_rest_world(PELVIS)` at bind: the invariant holds, A1's rest row holds, and the absolute value
  of `F_root(bind)` never reaches the asset. The root is then a swing-only correction with no roll claim beyond the derived
  frame's, which is what the block was trying to say.

**Two different failures at the root are named separately, because one sentence used to cover both.** Invalid **bind or
reference data** — a rig with `hipWidth = 0`, a zero `PELVIS → CHEST` at bind, a non-finite bind position — is an **L3
definition error** and is refused before any frame exists: no pose can repair it. A **posed** frame whose derived reference
degenerates this frame — a zero `PELVIS → CHEST` length, or a non-zero hip line and torso that are parallel (`|Y × Z| <
τ_len`) — is **not** a definition error: the pose is legal, the frame falls back to the bind world frame and the segment is
marked `degenerate` (§7.2). A parallel pair leaves the frame's roll undetermined, and a normalised noise vector would hide
that, which is why it is a marked fallback and not a repair. Never a normalised zero vector, in any of these cases.

**Only one mapped joint has a zero-length bind segment, and it is still not a special case**: `CLAVICLE`
(`CLAVICLE → SCAPULA` measured `0.0000` in all 594 frames) has no bind direction to normalise, and the stage-1
generator emits the **node** but no bone segment and no inverse bind for it (§15 step 3), so the map still resolves. Its
rule is §7.2's `degenerate` row applied here: `D = identity`, so
`R_target_world = B⁻¹ · B · R_target_world(parent) = R_target_world(parent)` — the bone carries its parent's frame
rigidly, with no `normalize(0)`, no fallback frame and no dropped node. `PoseRetargeterBasisTest` runs the **actual
pelvis path** (squat bottom and bird-dog, the two poses that author pelvis tilt) and that zero-length joint, and
`PoseRetargeterPelvisTranslationTest` carries a **non-vertical** displacement control.

**`Spine` is a normal bone with a normal inverse bind, and `PELVIS → LUMBAR` is a builder convention, not a factory
invariant.** `LUMBAR`'s retarget segment is head → child head, `LUMBAR → CHEST` (`SkeletonDefinition.torsoLength`, 120
units), so it is neither zero-length nor a node without a segment, and it carries its own inverse bind like every other
mapped bone. What it does **not** carry is a correction: on this engine `D` for the lumbar segment is identically
identity, because `CHEST` is a rigid child of `PELVIS` and the derived root frame's `+Y` is built from `CHEST − PELVIS`
(§7.2), so the direction the segment is handed is the one the composition already carries. The segment is owned and
mapped; the composition makes **no claim to correct it**, and a test that expects a non-identity `D` there is asserting a
rotation the pose does not contain. The `PELVIS → LUMBAR` pair reads `0.0000` in all 594 frames because the lumbar offset
is never authored: `SkeletonNode.localPosition` defaults to zero, and five builders re-assert it explicitly —
`CatCowPose.kt:123`, `SupermanPose.kt:87`, `ReverseSnowAngelPose.kt:88`, `ProneCobraStretchPose.kt:117`,
`ThoracicExtensionPose.kt:53` — while the other 61 leave the default alone. Nothing in `SkeletonFactory` forbids a
non-zero offset, so this coincidence is a convention of the builders that exist today, not a structural guarantee a rig
may rely on: a rig test **measures** the pair at the frames it uses instead of assuming it.

**The optional `Spine1` split is deferred, and it is not lumbar curvature.** Two rigid segments cannot produce a curve
(§13 question 10). It is a visual distribution of one `LUMBAR` rotation across two bones and is authored only if the
shared v1 rig actually carries a `Spine1`; until it does, the map is one-to-one and no split exists to validate.

| Engine joint (segment role) | Rig bone | Notes |
| --- | --- | --- |
| `PELVIS` | `Hips` | translation carried; the only translated joint |
| `LUMBAR` | `Spine` (+ optional `Spine1` split) | one-to-many allowed, weights sum to 1 |
| `CHEST` | `Chest` (or `Spine2`) | thorax |
| `NECK_END` | `Neck` | |
| `HEAD_POS` | `Head` | attachment point, rotation = head orientation |
| `CLAVICLE_A/P` | `Left/RightShoulder` | |
| `SCAPULA_A/P` | `Left/RightScapula` | **required from v2**; absent in the PR #385 asset |
| `SHOULDER_A/P` | `Left/RightArm` | |
| `ELBOW_A/P` | `Left/RightForeArm` | |
| `HAND_A/P` | `Left/RightHand` | `PALM/KNUCKLES/FINGERTIPS` stay unmapped; the ten finger bones are visual-only children of the hand (X2) |
| `HIP_F/B` | `Left/RightUpLeg` | **`HIP_F` is the thigh segment, not the hip point** |
| `KNEE_F/B` | `Left/RightLeg` | shank |
| `ANKLE_F/B` | `Left/RightFoot` | |
| `TOE_F/B` | `Left/RightToeBase` | |

Unmapped joints are legal: a rig without fingers still animates (`docs/CHARACTER_FRAMEWORK.md` §3). The reverse —
a *required* bone missing — is an asset failure, not a silent fallback, once the asset validator exists.

**An unmapped bone is not a bone that holds still.** `PoseRetargeter.kt:67` sends every unmapped node to its **rest world
rotation**, and `:94` propagates that rotation to its children, so an unmapped chain does not follow its mapped parent at
all. In the PR #385 asset **11 of 32 skin joints** are unmapped — `HeadTop_End` and the ten finger bones — so a wrist
rotation leaves the fingers at their bind orientation while the hand turns under them. The contract required here is
**parent-relative bind-rest inheritance**: an unmapped bone keeps its bind *local* rotation relative to its nearest
mapped ancestor, so it rides its parent and no rotation of its own is invented. **This is the rule M1's fingers use**
(X2): the ten finger bones are visual-only and inherit `Left/RightHand` rigidly, which is exactly the behaviour this
paragraph specifies — an unmapped chain that follows its parent instead of holding its bind world rotation. Twist bones get a **named twist policy**
instead of inheriting by accident — `TWIST_NONE`, `TWIST_FRACTIONAL` (a declared fraction of the parent's twist) or
`TWIST_ANIMATED` (a pose drives it) — declared per bone in `BoneMap` and reported at L2.

`UnmappedBoneInheritanceTest` (L2) moves a mapped parent and asserts that the unmapped child's world rotation tracks the
parent's delta within tolerance while its local rotation is unchanged. **The code change is not in this RFC:**
`PoseRetargeter.kt:67` lives on PR #385 and the fix belongs there as a follow-up, not in a docs change and not as a
silent reinterpretation here.

### 6.3 Morphology

Morphology is split into two mechanisms with different owners, because they have different consequences:

| Mechanism | What changes | Owner | Allowed in v1 |
| --- | --- | --- | --- |
| **Surface morph targets** | vertices under an unchanged skeleton: shoulder width, chest volume, waist, limb thickness, head scale, muscle definition | Character asset | yes |
| **Uniform scale** | the whole character's size | Character profile + calibration | yes |
| **Per-bone proportions** | segment lengths, therefore IK reach and joint placement | `SkeletonDefinition` (motion) | no in v1 — deferred to a versioned rig profile with full revalidation (§6.1); not a permanent prohibition |

The PR #385 asset already carries the five regional morphs (`ShoulderWidth`, `ChestVolume`, `WaistWidth`,
`LimbThickness`, `HeadScale`) and four presets (`MorphologyPreset.kt:7-11`). Those are preset definitions carried by a
test asset, not four M1 deliverables: M1 ships one preset, and four body families are stage 5 (§15). What is missing for
either is the validation that makes presets safe: region masks around joint pivots and a rest-invariant check per preset.
Without a mask, a shoulder-width morph drags the deltoid ring through the acromion and no joint-space rule can see it.

**Morph targets deform soft tissue only.** A surface morph moves skin and muscle vertices. It never changes a bone's
dimensions, a joint position or a segment length: a `ShoulderWidth` morph widens the deltoid surface and **must not widen
a bone**, lengthen a clavicle or move the `SHOULDER_A` joint point, because that would change IK reach while the motion
model believes nothing moved. The check is L6, and it is a **mesh** check: the ring of vertices masked around each joint
pivot keeps its pivot-relative positions within `ε_morph` (proposed, fixed at stage 1). Testing that the *bone* landmarks
are unmoved is not that check — a vertex-only morph cannot move a bone, so asserting it is tautological and passes for
every asset, including a broken one. The two are not substitutes: the ring test can fail, the bone test cannot, and only
the ring test says anything about the surface a muscle has to sit under.

**A consequence that must be budgeted, not discovered:** a body morph target moves surface vertices, and muscle meshes
are separate renderables. A muscle layer therefore does **not** inherit the body's morphs; either the muscle asset
carries the matching morph targets, or the muscle layer is authored per body family. The anatomy *data* stays shared —
attachments are in joint space, unaffected by surface morphs — but the muscle *meshes* are per family. That is an art
cost per variant (§15 stage 5), and pretending otherwise would produce muscles floating inside a heavier body.

**Corrective shapes** (arm-raise, hip-flexion, elbow-bend) are visual-only, driven from the same published rotations,
and must be authored per rig; they are the accepted way to keep joints credible across poses without touching motion.
They are classified as an approximation (§11), not as anatomy.

### 6.4 Character profile

```json
{
  "id": "male_muscular_v1",
  "rig": "humanoid_v2",
  "anatomy": "anatomy_v1",
  "assets": {
    "skin": "characters/male_muscular/skin.glb",
    "muscles_superficial": "characters/male_muscular/muscles_sup.glb",
    "muscles_deep": "characters/male_muscular/muscles_deep.glb"
  },
  "skeletonOverlay": "shared/skeleton_v1.glb",
  "morphology": {
    "preset": "muscular",
    "rangesVersion": 1,
    "weights": {
      "ShoulderWidth": 1.0, "ChestVolume": 0.7, "WaistWidth": -0.35, "LimbThickness": 0.9, "HeadScale": 0.05
    },
    "ranges": {
      "ShoulderWidth": { "min": -0.55, "max": 1.0 },
      "ChestVolume":   { "min": -0.55, "max": 1.0 },
      "WaistWidth":    { "min": -0.55, "max": 1.0 },
      "LimbThickness": { "min": -0.55, "max": 1.0 },
      "HeadScale":     { "min": -0.55, "max": 1.0 }
    }
  },
  "materials": { "skin": "skin_03", "roughness": 0.55 },
  "heightMetres": 1.80,
  "quality": "standard",
  "anatomyOverrides": []
}
```

**Morph names are the asset's names, exactly.** The keys are the GLB's `extras.targetNames` — `ShoulderWidth`,
`ChestVolume`, `WaistWidth`, `LimbThickness`, `HeadScale`, case included. A camelCase key is **not** a synonym: the rule
below is that a name the asset does not carry is a profile error, so `shoulderWidth` is rejected rather than silently
ignored — and the earlier example in this section used exactly that spelling, which is how a documented example ends up
unable to load. If a profile ever needs a friendlier spelling, it needs an explicit, unique mapping table with its own
validation; an implicit case-insensitive match is not that table.

**Morph weights are named, not positional.** `weights` maps a morph target name to a weight. The preset supplies a
default for every target it declares; an explicit entry overrides that one name and leaves the rest at the preset's
values. A name the asset does not carry is a profile error, not a silent zero — neither rule is expressible in the
positional array this replaces, where a reordered morph set silently reinterprets the body.

**What range a weight may take — measured, not invented.** The GLB declares five position-delta targets
(`ShoulderWidth`, `ChestVolume`, `WaistWidth`, `LimbThickness`, `HeadScale`) and **no weight range at all**: glTF morph
weights are unbounded floats. The asset's only statement about magnitude is the delta authored at weight 1.0 — 55 mm,
50 mm, 55 mm and 22 mm of displacement, and 10 % of head radius for `HeadScale` (`morph_deltas` in
`spike-humanoid/tools/generate_humanoid_glb.py`, PR #384) — and the shipped presets span `−0.55 … 1.00`
(`MorphologyPreset.kt:8-11`), which is the only evidence that negative weights are intended and that 1.0 is the authored
extreme. The profile therefore **declares `min`/`max` per named morph in a versioned `ranges` block**
(`rangesVersion`), checked by `MorphPresetRangeTest` at L6; the numbers above are the asset's authored envelope, not a
guess, and a per-target range may narrow or widen independently as the asset is re-authored — which is why the block is
versioned rather than a constant. Nothing in the asset stops a weight of 3.0 from rendering: this document refuses to
ship a weight the asset was not authored for, and a name with no declared range is a profile error because it cannot be
validated.

**`heightMetres` is a declared landmark pair, not a free scale factor.** It is the `rigHeight` argument of
`CharacterCalibration.unitScale` (`character/CharacterCalibration.kt:14-26`, PR #385), whose scale is
`rigHeight / (HEAD_POS.y − min(TOE_F.y, TOE_B.y))`, evaluated on a throwaway `AirSquatPose` frame at progress 0 so
calibration cannot advance the render pipeline's frame history. The runtime passes `PoseRetargeter.restHeight()` — the
`max − min` of the Y of **all** rig nodes — as that `rigHeight`. The two ends of the ratio are therefore different
landmarks, and the result disagrees with §7.2's calibration:

| Path | Numerator | Denominator | Implied `unitsPerMetre` |
| --- | --- | --- | --- |
| §7.2 calibration | trochanter 0.95 m | `PELVIS.y` = 235 units | **247.37** |
| `heightMetres` = 1.80 | 1.80 m | `HEAD_POS.y − min(toe).y` = 361.50 units | **200.83** |
| `restHeight()` = 1.78 (crown to floor) | 1.78 m | the same 361.50 units | **203.09** |

The `heightMetres` path is 18.8 % below the calibration, and the calibration is 23.2 % above it — one rig, measured
twice, two answers. **Only the first row is the calibration.** The other two are **retired** measurements of the paths
that would have derived a scale from a height, kept because they are the reason a height cannot be the pair: the
`heightMetres` row is what a declared 1.80 m produces (**200.83**), and the `restHeight()` row (**203.09**) is the path
the PR #385 code applies today as `unitScale` = 0.004924 m/u (§6.2). A 1.75 m crown on the same head-centre-to-toe
denominator gives **206.6** (`361.50 / 1.75`) — the figure a rounded crown height produces, retired with the rest. The
cause of the disagreement is a name, not a rounding error: `HEAD_POS` is the head **centre** (radius 18 units,
§3.3), so the denominator is head-centre-to-floor while `restHeight()` is crown-to-floor, and the field silently means
both. **A field cannot be both a crown height and a head-centre height.**

The contract is a definition: `heightMetres` names its pair — **crown to floor**, the asset's `HeadTop_End` to the
lowest `ToeBase` — and `unitsPerMetre` is derived from the *same* pair on the engine side. That pair is
`HEAD_POS.y + headRadius` = 391 + 18 = **409.0 units** above the declared ground, because `AirSquatPose` declares
`ground.level = 0` and `min(toe).y` = 29.50 units is the toe **joint**, which sits above the floor rather than on it. So
409.0 / 1.80 = **227.2** u/m at the declared height, and 409.0 / 1.78 = **229.8** against the asset's actual crown. A
`CharacterCalibration` that cannot reproduce `unitsPerMetre` from the declared pair within a stated tolerance (proposed
1 % on the span) **fails the asset at L2** instead of rendering at a scale nobody chose.

Two estimates now exist — 247.37 from the pelvis pair and 229.8 from the crown pair, the first **7.7 %** above the second
— and the difference is a real disagreement about the engine's proportions, not a bug in either arithmetic. The earlier
revision reported 17 % here and called the stance short; both were wrong, and the cause was one arithmetic slip:
subtracting the toe *joint* from a crown height. The engine's head centre is **1.581 m** above the floor (391 units),
which is not short for a 1.75–1.80 m adult; what is short is the head. The engine's crown sits 18 units (**0.073 m**)
above the head centre against the asset's 0.180 m, and that — not the figure's height — is what pulls a crown-based
calibration down to 229.8. **That question is closed** (§13 question 12). `unitsPerMetre` =
**247.37** from the pelvis registration (`PELVIS.y` = 235 units against the 0.95 m trochanter height) is the **single**
runtime scale, and there is **no other runtime scale**: the `heightMetres` and `restHeight()` paths are retired, so
200.83, 203.09, 206.6 and the crown-pair 227.2 / 229.8 above are recorded measurements of paths this document no longer
uses, not competing calibrations to choose between. Every residual that depends on the constant is quoted at 247.37.

`anatomyOverrides` is the escape hatch for a body family whose *shared* attachment is measurably wrong (for example a
pelvis-width-dependent origin). It must be sparse, named, and reviewed; it is not a per-character copy of the library.
An override moves an attachment, never a rig bone or a joint — for why that leaves it useless against an alignment
residual, see the dedicated paragraph in §6.6.

`BodyProfile` and the overrides are **anatomy inputs only**. They never reach the engine, never change a joint, a pose or
a `SkeletonDefinition`, and cannot be used to move the body to meet a mesh (§6.6 A3). A profile field that would have to
influence motion belongs in the motion model, and is out of scope here.

### 6.5 What is shared and what is per character

| Shared across all characters | Per character |
| --- | --- |
| 66 pose builders and their metadata | mesh topology and vertex count |
| `SkeletonDefinition` (v1) | materials, textures, hair, clothing |
| `AnatomyDefinition` (attachments, paths, wraps, roles) | morph target set and its weights |
| Activation tables | quality tier and LODs |
| Validation rules and thresholds | anatomy overrides (rare, reviewed) |
| Camera definitions | uniform scale |

### 6.6 Alignment between the engine skeleton, the anatomy and the visual rig

The three models share one pose but not one space, and the RFC previously left the seam unstated. Two facts make it a
contract rather than a hope:

- **Anatomy is anchored to engine joints.** Every attachment resolves as `segmentOrigin + R_segment × (offsetMetres ×
  unitsPerMetre)`, where `R_segment` comes from published **positions** and the declared roll (§7.2, §7.4 step 1) — not
  from the published joint rotation. Anatomy never reads a glTF bone, a bind matrix or a skin weight.
- **The visual rig is posed by position-driven retarget.** Each mapped bone's rotation is the minimal rotation from its
  bind direction to the published segment direction, **conjugated into asset space and transported by the parent's posed
  rotation** — the same frame rule anatomy uses (X3, §6.2), so a parent's roll reaches its children exactly as it reaches
  an anatomy segment frame's. Only the pelvis translation is converted, and the rig's bone offsets stay in asset units. The
  rest-delta composition the PR #385 code performs (`PoseRetargeter.kt:58-83`) is the code X3 replaces, not the contract.

Nothing in either rule forces the rig's bone *heads* to sit where the engine's joints are. They coincide only if the asset
is calibrated to the engine skeleton, and they stay coincident only while the segment lengths agree. That is the
alignment invariant, and it belongs to the Character model — the owner of the asset — not to anatomy.

**A1 — bind-stance landmark coincidence.** For every mapped joint `j`, at the bind stance, the rig bone head converted to
engine space equals the engine joint position within `τ_bind` = **0.5 mm**, and the rig bone's rest orientation equals the
engine rest orientation within `τ_rot` = **0.5°** (both unchanged, fixed at stage 1). Checked by
`RigLandmarkAlignmentTest` (L2).

**On `humanoid_v1` the rest-orientation row is vacuous, so the position row is the whole of A1.** None of the asset's 35
nodes carries a `rotation` field, so all 32 skin joints read **0.0000°** against the engine rest and pass a 0.5° bound for
**any** rig whatsoever: the row cannot distinguish one rig from another, and quoting it as evidence that a rig is correct
is quoting a check that could not have failed. The A1 baseline recorded in this document — `CHEST` at 175.1 mm, 350.2×
the tolerance (§13 question 12) — is therefore entirely the **position** row. The orientation row can only ever **fail** a
rig that authors a non-identity rest rotation; measured against the X1 contract's identity rest it passes by default, so
it cannot confirm a rig that matches the map.

**A1 is a check of the asset and of the converter that measured it — not of the running retargeter.** It converts an asset
point with `s · Ry(+90°) · p_asset` in the test, and a static converter that does so passes while the production
`PoseRetargeter` still composes with `basis = Quat.IDENTITY` (`PoseRetargeter.kt:14`, and `CharacterSession.kt:152`
constructs it with no basis). A green bind-landmark test therefore proves that the converter and the asset agree; it says
nothing about which transform the runtime applies to a posed frame. That gap is closed by a **second** test through the
real construction and upload path — `PoseRetargeterBasisTest` (§12.2) — which drives nonzero, asymmetric posed landmarks
through `PoseRetargeter.boneMatrices` and the scene-frame upload and asserts the resulting world direction and roll, with a
**wrong-basis control** (the same frame at `basis = IDENTITY`) that must fail. The division of labour is the one A1/A2
already imply: **A1 is the bind-asset check, A2 is the sampled posed measurement, and the basis test is the runtime
path's own check** — three different claims, and no one of them substitutes for another.

**X1 — the bind stance is a canonical A-pose, and it is not an engine pose.** *Confirmed by owner.* The frame A1 measures
against is computed **in character-side code from `SkeletonDefinition` alone** — segment lengths and the engine's own
pelvic-height convention (`shin + thigh + 25`, `MonkJointResolver.restPelvisHeight`) — with every limb straightened. It is **not** a published pose: the calibration stance the asset was measured against
(`squat_standard` at progress 0) folds the arms out and forward for counterbalance, so its hand joint sits 565.6 mm from
the asset's wrist and measuring A1 there measures the pose, not the rig. The canonical A-pose needs no engine change, no
new joint, no new pose builder and no frame the frozen engine must retain — which is what makes it usable as a contract
rather than as a convenience.

The pose is fully determined by the definition (`HumanSkeletonDefinition`: torso 120, neck 18, thigh 112, shin 98, foot
35, upper arm 80, forearm 66, shoulder width 46, hip width 22, palm 12, finger 10, heel 0.29 / toe 0.71 of the foot), in
engine units with `+Y` up, `+X` forward and `+Z` lateral, side `A` on `−Z`:

| Joint | Position (engine units) | Why |
| --- | --- | --- |
| `PELVIS`, `LUMBAR` | `(0, 235, 0)` | `235 = shin + thigh + 25`; `LUMBAR` is coincident with `PELVIS` by construction |
| `CHEST` | `(0, 355, 0)` | `PELVIS + torso 120` |
| `NECK_END` | `(0, 373, 0)` | `CHEST + neck 18` |
| `HEAD_POS` | `(0, 391, 0)` | `NECK_END + neck 18` (the engine's last joint; the crown is not a joint) |
| `CLAVICLE_A/P`, `SCAPULA_A/P` | `(0, 355, 0)` | both girdle nodes are coincident with `CHEST`; the offset is carried by `SHOULDER` |
| `SHOULDER_A`, `SHOULDER_P` | `(0, 355, ∓46)` | shoulder width 46 lateral |
| `ELBOW_A/P` | `(0, 275, ∓46)` | `SHOULDER − upperArm 80`, arm straight down |
| `HAND_A/P`, `WRIST_A/P` | `(0, 209, ∓46)` | `ELBOW − forearm 66`, forearm continuous with the humerus |
| `PALM_A/P`, `KNUCKLES_A/P`, `FINGERTIPS_A/P` | `(0, 203/197/187, ∓46)` | hand along the same direction: palm 6 to its midpoint, then knuckles, then the 10-unit finger |
| `HIP_F`, `HIP_B` | `(0, 235, ∓22)` | hip width 22 lateral |
| `KNEE_F/B` | `(0, 123, ∓22)` | `HIP − thigh 112`, leg straight down |
| `ANKLE_F/B` | `(0, 25, ∓22)` | `KNEE − shin 98` |
| `HEEL_F/B`, `TOE_F/B` | `(∓10.15, 25, ∓22)`, `(+24.85, 25, ∓22)` | heel `−foot·0.29`, toe `+foot·0.71`, both level with the ankle |

Nothing in that table needs a published frame, a rig, or the engine's solver — it is arithmetic on the definition, which is
the property X1 is chosen for. The 23 mapped joints of §6.2 are the ones A1 covers — 21 of them on a `humanoid_v1` asset,
which has no scapula bone — and the other 10 engine joints have no asset bone to align (§6.2, X2).

**Keeping the fingers cannot move the hand's A1 row.** A1 compares each mapped bone's *head* with its engine joint, and the
generator places `Left/RightHand` at the X1 `HAND_A` position and parents the finger bones **to it, distally** — a child's
head does not locate its parent's, so the 10 finger bones add no A1 row and cannot shift the one the hand has. They are
listed as unmapped in `RigLandmarkAlignmentTest`'s report, which is an inventory, not a residual (§12.2).

**The shipped asset does not pass A1. That is a measured baseline, not a pass.** Re-measured for this revision: the asset
was parsed at `23ef1c0`, its bind bone heads converted to engine space by **`p_engine = s · Ry(+90°) · p_asset`** with
`s = 235/0.95 = 247.3684` (§7.2) and **translation 0 at the pelvis** (the asset's `Hips` and the engine's `PELVIS` both
land on `(0, 235, 0)`, so the registration has no offset to hide), and compared joint-by-joint with the engine's published
positions at the **standing frame** (`squat_standard` at progress 0 — the calibration stance, *not* the X1 bind frame).
`Ry(+90°)` is the basis of X3: asset `+Z` forward / `+X` left → engine `+X` forward / `−Z` left. All 21 mapped joints of
the shipped asset (the v2 map has 23; this asset has no scapula, so there is no row for one), in millimetres:

| Joint (asset bone) | ΔX | ΔY | ΔZ | 3D |
| --- | --- | --- | --- | --- |
| `PELVIS` (`Hips`) | +0.0 | +0.0 | +0.0 | **0.0** |
| `LUMBAR` (`Spine`) | +0.0 | −110.0 | +0.0 | **110.0** |
| `CHEST` (`Chest`) | +0.0 | +175.1 | +0.0 | **175.1** |
| `NECK_END` (`Neck`) | +0.0 | +7.9 | +0.0 | **7.9** |
| `HEAD_POS` (`Head`) | +0.0 | −19.4 | +0.0 | **19.4** |
| `CLAVICLE_A` (`LeftShoulder`) | +0.0 | −4.9 | +60.0 | **60.2** |
| `CLAVICLE_P` (`RightShoulder`) | +0.0 | −4.9 | −60.0 | **60.2** |
| `SHOULDER_A` (`LeftArm`) | +0.0 | +20.1 | −11.0 | **22.9** |
| `SHOULDER_P` (`RightArm`) | +0.0 | +20.1 | +11.0 | **22.9** |
| `ELBOW_A` (`LeftForeArm`) | −15.0 | +24.2 | −165.0 | **167.5** |
| `ELBOW_P` (`RightForeArm`) | −15.0 | +24.2 | +165.0 | **167.5** |
| `HAND_A` (`LeftHand`) | −30.0 | +550.1 | −128.2 | **565.6** |
| `HAND_P` (`RightHand`) | −30.0 | +550.1 | +128.2 | **565.6** |
| `HIP_F` (`LeftUpLeg`) | +0.0 | +30.0 | +11.1 | **32.0** |
| `HIP_B` (`RightUpLeg`) | +0.0 | +30.0 | −11.1 | **32.0** |
| `KNEE_F` (`LeftLeg`) | +82.8 | +6.7 | −28.7 | **87.9** |
| `KNEE_B` (`RightLeg`) | +82.8 | +6.7 | +28.7 | **87.9** |
| `ANKLE_F` (`LeftFoot`) | +0.0 | +39.3 | +32.5 | **50.9** |
| `ANKLE_B` (`RightFoot`) | +0.0 | +39.3 | −32.5 | **50.9** |
| `TOE_F` (`LeftToeBase`) | −19.5 | +89.3 | −32.7 | **97.0** |
| `TOE_B` (`RightToeBase`) | −19.5 | +89.3 | +32.7 | **97.0** |

The ΔY column reproduces the earlier six-value height table exactly (`+7.9`, `−19.4`, `+30.0`, `+6.7`, `+39.3`, `+89.3`)
and adds what that table omitted: **`CHEST` at 175.1 mm** and **`LUMBAR` at 110.0 mm** vertically, and every ΔX/ΔZ
component — the elbow 165.0 mm and the hand 128.2 mm out of plane, the knee 82.8 mm forward. **At the standing frame the
worst mapped joint is the hand at 565.6 mm 3D** (550.1 mm vertical); 20 of the 21 joints are outside the 0.5 mm tolerance
(only `PELVIS` coincides, which it must, because the registration is defined at the pelvis), and the median 3D residual
is 60.2 mm. **This table is the posed standing-frame comparison, not the A1 result**: A1 is measured at the X1 bind frame,
where the worst joint is `CHEST` at 175.1 mm (next paragraph), so A1 fails by **350.2×** the 0.5 mm tolerance and the
column above is quoted for what it measures — the pose. The residual is **affine** in `unitsPerMetre` — `E − s·A`
in engine units and `E/s − A` in metres, each with a non-zero intercept — not proportional to it, so a change of
calibration is **re-measured, never rescaled**; the two spaces disagree about which way the number moves, and the §13
question 12 worked example shows a naive rescale landing 111.7 mm away from the recomputed value.

**Measured at the X1 bind frame too, and it still fails.** The same registration against the canonical A-pose instead of
the standing frame puts the worst joint at **`CHEST`, 175.1 mm** (the asset's `Hips → Chest` span is 0.310 m against the
engine's 0.485 m — 36 % short), then `LUMBAR` at 110.0 mm, `TOE` at 74.4 mm and `HAND` at 60.6 mm; only `PELVIS` lands
inside 0.5 mm. So the failure is not an artifact of the folded-arm stance: the asset's bone heads are in the wrong places
for this skeleton at any frame, which is why stage 1's deliverable is a **re-authored rig**, not a re-measurement.
**Stage 1's deliverable is a rig that passes A1** (§15); until that rig exists, `RigLandmarkAlignmentTest` is expected to
be red on `humanoid_v1`, and A1 is not weakened to make the current asset green.

**The recorded baseline is the position half of A1, and the rest-orientation half has none.** Both tables above measure bone
*heads*. A1's second half — the rig bone's rest orientation against the engine's, within `τ_rot` = 0.5° — has **no recorded
baseline at all**: no parse of the shipped asset's rest rotations was made for this document, so nothing here says whether
`humanoid_v1` would pass or fail that row, and no number is quoted for it in either direction. An **asset parse at rest**
is therefore a **stage-1 deliverable** alongside the position parse that already exists: every bone's local rotation read
from the GLB, carried through the `Ry(+90°)` basis into engine space and compared with the engine rest the generator
writes — `B⁻¹ · I · B = I` (§15 stage 1, step 8). Until that parse is recorded, the rest-orientation row is a criterion
with no measurement behind it on any asset, the shipped one included, and a green position half says nothing about it.

**How an A1-passing rig is obtained: four options, decided.** The baseline above is not a rig anyone can fix in place,
so the route to A1 was chosen explicitly rather than assumed.

- **(a) Re-pivot the shipped asset** — move each bone head onto the engine joint it should sit on. **Rejected, and the
  pivot arithmetic is why.** A bone head *is* the skinning pivot: the inverse bind is authored against it and every vertex
  weighted to the bone is expressed relative to it. Translating a pivot by `e` therefore moves that bone's vertices by
  `e` and shears the joint against its neighbours, so the "correction" is a surface deformation of exactly the residuals
  measured at the **X1 bind frame** — **175.1 mm at the chest, 110.0 mm at the lumbar spine, 74.4 mm at the toe and
  60.6 mm at the hand**, the chest figure alone being **9.8 %** of a 1.78 m figure's height. No weight set survives that,
  and the fix would have to re-weight the mesh anyway, which is option (c) done worse.
- **(b) Re-author the asset in Blender from the engine skeleton** — write the joint table with Blender's Python and export
  a GLB. **Unavailable in this environment:** the host is `aarch64` and `bpy` publishes no wheel for it, so the tool cannot
  run here at all. It remains the right option for the *art* pipeline on a workstation, which is why the generator below is
  specified to produce a GLB that Blender can then take over.
- **(c) Generate the rig from `SkeletonDefinition` in character-side code** — **chosen.** It makes A1 true by
  construction: the generator writes the X1 A-pose positions itself, so the pivot, the inverse bind and the morph bands all
  come from one definition, and the output is an ordinary GLB that nothing downstream can tell from a hand-authored one.
  It is checked in, diffable and test-covered, and the existing `generate_humanoid_glb.py` (PR #384) is already the shape
  of the tool — the change is that its joint table is computed from the definition instead of hand-authored.
- **(d) Keep the shipped asset and treat A1 as a measurement frame** — **valid only as the measurement frame.** That is
  exactly how the table above was produced, and it is the reason the asset's defects are known numbers instead of
  impressions. It cannot be the deliverable: the rig it measures fails A1 by 350.2× the tolerance at the X1 bind frame.

**A2 — a measured residual on a named pose set. The global bound is withdrawn.** The earlier claim was that, with A1
true and identical rotation deltas, a child bone head moves only by the segment-length error accumulated along its
ancestor chain:

```
residual(j) ≤ Σ over ancestors i of |length_rig(i) − length_engine(i)| × scale        ← withdrawn, false
```

It is false, and a two-vector counter-case settles it: two **equal-length** vectors 100 mm long, 0.1° apart, displace a
bind head by `2 · 100 · sin(0.05°)` = **0.1745 mm** while the claimed sum of length differences is exactly **0**. The
error that formula ignores is directional, and a directional error is precisely what a retargeted rotation produces. No
sum of length differences bounds it.

**Equal bind lengths do not ensure posed alignment, and the correction is not a rig change.** A rig whose segment lengths
match the engine's to the millimetre still drifts in motion if its bones are rotated by a composition that accumulates
residual direction error — which is what a rest-delta retarget does and what X3's position-following rule removes. So
there is **no rig-only fix** for A2: the retarget is part of the alignment contract, and `RigAlignmentResidualTest` is
the measurement that decides, on posed frames, whether the pair (rig, retarget) holds the joints. A document that
promised that equal lengths were sufficient would be promising that a rotation error cancels, and it does not.

A2 is therefore a **measurement**: for each mapped joint, the distance between the rig bone head (converted to engine
space) and the engine joint, evaluated on a **named set of five frames drawn from four registered builders** — the five
frames of the §12.4 review set from the four pilot exercises (squat bottom, squat top, `standard push-up` bottom,
`bird-dog`, `row_standard` mid-pull) — and reported per joint and per frame against `τ_residual` (proposed 5 mm at the
most distal mapped joint).

**Pose coverage and builder coverage are different counts and are quoted separately.** Squat bottom and squat top are
two frames of *one* builder, so the five frames come from **four** of the 66 registered builders; "five poses" is not
"five builders". **A2 measures the same set RV4 reviews**: these five frames are exactly the §12.4 review set, drawn
from the four pilot exercises the owner fixed (X8), so a rig is never measured on a configuration the anatomical review
never sees — the asymmetry an earlier revision carried, where A2 measured a sixth frame outside the review set, is
closed by dropping that frame rather than by widening the review. What the set does not reach is stated rather than
claimed away: four of 66 builders is a sample, no builder outside the four is covered, and **neither A2 nor RV4 reviews
arm elevation above shoulder height**, a configuration no M1 pilot exercise needs. The check that would cover it —
candidate `wall_slide_standard` at progress 1.0 — is required before the first exercise that does need it, and the
§12.4 record carries it as a limitation. A residual above `τ_residual` on any frame in the set fails the asset, not the
anatomy.

The equal-length/different-direction pair is a **control case** in `RigAlignmentResidualTest`: an implementation that
reports a zero residual for it is still evaluating the withdrawn formula rather than measuring.

**A1 is per joint at bind; A2 is per joint per pose.** Every mapped head can sit inside 0.5 mm of its joint at bind
while a posed frame exceeds 5 mm at the hand or the toe, because A1 is measured at one stance and a rotation error at an
intermediate joint moves every descendant. The converse holds too: a chain can close within 5 mm at its distal joint in
all five poses while individual heads sit outside the 0.5 mm bind bound. Neither measurement substitutes for the other,
and neither is a pass on its own — **the measured residual at the named poses decides** which of the two a candidate
asset actually fails.

**A3 — the rig follows anatomy, never the reverse.** If the residual exceeds tolerance, the fix is a better rig (bone
heads and lengths recalibrated to the engine skeleton) or a correction in the calibration (§6.6 A1/A2, L2). An override
cannot stand in for that fix — see the next paragraph.

**`anatomyOverrides` cannot repair an alignment residual.** An override changes an *attachment offset* in the shared
anatomy data (§6.4); it does not move a rig bone head, change a segment length, or alter a rest orientation — the three
quantities A1 and A2 measure. A residual over `τ_bind` or `τ_residual` is therefore an asset failure (L2), and no
override entry, however well reviewed, can turn `RigLandmarkAlignmentTest` or `RigAlignmentResidualTest` green: the
override is not an input to either test. Conversely, a legitimate override (a body family whose *shared* attachment is
measurably wrong, e.g. a pelvis-width-dependent origin) must be reviewed against A1/A2 **after** the rig already passes
them, so that a proposed override is never mistaken for a remedy to a failing rig. An override whose stated purpose is
to hide an alignment failure is a review defect, and the fix belongs in the asset or its calibration. A1/A2 residuals
and attachment correctness are independent: a rig can satisfy A1/A2 while an attachment is wrong (an L3/L7 matter), and
an attachment can be correct while the rig fails A1/A2 (an L2 matter).

**What this does not claim.** A1–A3 align *joint landmarks*. They do not claim that the skin surface matches the muscle
belly, that the authored mesh follows the polyline between helpers (§7.5), or that a body with different proportions is
representable on this rig (§6.1). Surface plausibility remains the human review's job (§12.4, RV2/RV3).

**Why this is not a fourth model.** No new data and no new solver: A1/A2 are measurements of an asset against a published
pose, owned by the Character model and reported as L2. The anatomical model keeps its single input — the `SkeletonPose`.

---

## 7. Anatomical model

### 7.1 Four things named "muscle"

The design separates four objects that a naive implementation would fuse into one:

1. **`MuscleDefinition`** — static: name, side, layer, origin and insertion attachments, waypoints, width/thickness
   profile, tendon fractions, wrap surfaces, role memberships. Text, reviewable, character-independent.
2. **`MuscleState`** — per frame: solved path, `modelledPathLength`, `restLength`, `relativePathLengthChange`,
   `visualTendonPortionLength`. No geometry.
3. **Muscle geometry** — the visible mesh, produced by a renderable strategy (§7.5) from the path.
4. **Muscle activation** — exercise semantics (§8), never a property of the definition.

A definition change (better attachment) is a data review. A path bug is an algorithm review. A mesh change is an art
review. A role change is an anatomy review. Four reviewers, four diffs — that is the point.

### 7.2 Spaces, units and segment frames

**Coordinate conventions (verified):**

- `+Y` is up; limbs are placed on `±Z` (`animation/BasePose.kt:63-64`, `poses/BaseSquatPose.kt:42-45`), so `Z` is the
  lateral axis and `XY` is the sagittal plane. Pose authoring reaches forward along `+X` (air-squat counterbalance
  `BaseSquatPose.kt:42-43`).
- **Naming trap:** `SkeletonDefinition.anatomicalForward` is `(0,0,1)` — but it is used as the *rotation axis* for
  sagittal spine bend (`BasePose.kt:208-214`), not as the direction the athlete faces. Anatomy authors must read it as
  "the lateral bend axis", not "forward".
- **Node rest rotations are identity; the derived segment frame is a different object and is not.** The factory creates
  every node with identity rotation (`SkeletonFactory.kt:60-101`), so each node's published **local** rotation at rest is
  identity, and that identity is what A1's rest-orientation row compares a rig against within `τ_rot` (§6.6). The
  **segment frame** this section authors attachments and obstacles on is derived from positions (root rule below), it is
  not the node's rotation, and at the X1 bind pose it equals `F_root(bind)` for **every** segment — because `D = identity`
  at bind and each segment inherits its parent's frame — which is `diag(−1, 1, −1)`, a 180° rotation about `Y` (§6.2). Two
  consequences are stated rather than discovered: the attachment convention is the **segment frame's**, and its axes are
  named below rather than borrowed from the engine's world axes. **In a posed frame the segment frame must be derived from
  published positions**, because the published rotation does not describe the segment. Measured at `16e0487`:

  - `IkStage.kt:131-134` writes `middle.localPosition` and `end.localPosition` for a solved limb and no local rotation;
  - `SkeletonNode.kt:63-67` builds each joint's world rotation by concatenating local rotations, and `:75-77` publishes
    it, so a limb's middle joint carries its parent's world rotation, not its own segment direction;
  - `AirSquatPose` at progress 0.5: `KNEE_F` local rotation 0.00° and published 12.89° — the pelvis's world rotation —
    while the thigh–shank bend is 106.53°; `StandardPushUpPose` at 0.5: `ELBOW_A` local 0.00° and published 90.00° — the
    chest's world rotation — while the arm bend is 133.82°.

  Reading the published rotation as the segment's orientation mis-places an attachment by 26.9–56.4 mm at a 30 mm offset
  over the four limb segments and four poses measured. The engine contract is **not** changed by this document; the RFC
  contract is (§7.4 step 1).
- **Attachment offsets are authored in the derived segment frame — not in node axes, and not in world bind axes.** The
  three are different objects and the same three numbers mean different points in each. The node's own rest rotation is
  identity (`SkeletonFactory.kt:60-101`); the derived frame at bind is `diag(−1, 1, −1)`, the half-turn about `Y`, for
  every segment; the world bind axes are the space the engine publishes positions in. `+Y` is the one axis the half-turn
  leaves alone, so "up" is unambiguous — `+X` and `+Z` are not, and the names in this document are the frame's, with the
  sign the frame gives them: **`+Y` superior; `+X` and `+Z` the sagittal and lateral axes as the derived frame fixes
  them**. At X1 that resolves to an authored `+X` along world `−X`, i.e. toward the heel — the athlete faces world `+X`
  (`TOE` at `+24.85` against `HEEL` at `−10.15`, both level with the ankle) — and an authored `+Z` along world `−Z`, which
  is side `A`. Read as node axes the same offset is wrong by the half-turn in `X` and `Z`, and for a muscle with a
  symmetric offset that error changes no length, no clearance figure and no envelope test. That is why the frame is
  confirmed at the anatomical review per instance (§12.4 RV1) and why §7.3 records, per instance, that it is still
  unrecorded.
- **Units: one calibration, named landmarks.** The engine has no metres: `HumanSkeletonDefinition` uses 120/112/98/80/66
  (units) for torso/thigh/shin/upper arm/forearm (`SkeletonDefinition.kt:38-56`), and the rest pelvis height is
  `shin + thigh + 25 = 98 + 112 + 25 = 235` (`MonkJointResolver.kt:59-60`). Anatomy is authored in **metres** and
  converted with **one** constant, derived from one pair of corresponding landmarks:

  | Quantity | Value | Where it comes from | What it means |
  | --- | --- | --- | --- |
  | `PELVIS.y` at the rest stance | 235 engine units | measured (`AirSquatPose`, progress 0) | the engine's trochanter-height point |
  | trochanter height of the reference adult | 0.95 m | modelled constant (§11 approximation) | the physical landmark the engine's 235 stands for |
  | `unitsPerMetre` | 235 / 0.95 = **247.37** | derived | engine units per metre |
  | `metresPerUnit` | 0.95 / 235 = **0.004043** | derived, the same calibration inverted | metres per engine unit |
  | `HEAD_POS` | head **centre** joint at 391 units | measured (`AirSquatPose`, progress 0) | the engine skeleton's last joint — **there is no crown joint** |
  | crown offset | `headRadius` = 18 units above `HEAD_POS` | `SkeletonStyle` (§3.3), a **renderer** constant | not a joint and not a landmark: a crown figure is always computed, never read from the skeleton |
  | asset crown node | `HeadTop_End` at 1.78 m | asset parse, PR #385 | the asset has a top **joint**; the engine does not |
  | asset `Hips` | 0.9500 m | asset parse, PR #385 | the asset's counterpart of `PELVIS.y` |

  **Every length conversion in this document uses this one constant.** `unitsPerMetre` and `metresPerUnit` are one
  calibration read in two directions; a second factor anywhere — a per-layer scale, a per-attachment correction, a root
  translation scaled twice — is a defect, not a refinement. The asset's own 0.9500 m `Hips` against the engine's 235
  units is the cross-check: at 247.37 u/m the engine's pelvis is 0.950 m, so the two agree by construction, and that
  agreement is what `RigLandmarkAlignmentTest` measures rather than assumes (§6.6 A1).
- **Side mirroring.** Attachments are authored for side `A` and mirrored to `P` by negating `Z`. The rest-pose side
  check (§12 L2) proves that the rig's `Left*` bones sit on the same side as side `A`; if they do not, the asset or the
  bone map is wrong — a rendering-time flip would only hide it.

**Segment frame table.** Each row names the node the segment is carried on — the node's own rest rotation is identity,
which is a fact about the *node* and not a definition of the segment frame (above) — and the **published position pair**
the posed direction is taken from — `p_child − p_parent`, in the parent's frame, with the roll convention of X9 (§13
question 11). The roll is an approximation, and §7.2 states its exact form below.

**X9 as a total rule — defined for every segment, including the degenerate ones.** `P` is the parent segment's frame
(published positions, this table's rule applied to the parent), `restDir` the segment's direction in its own rest frame,
`a = P · restDir`, `b = normalize(child − parent)` in world space, `D = shortestRotation(a, b)`, `R = D · P`. Every
quantity here is in **engine world space**; §6.2's retarget is the same construction carried through the basis, which is
why it conjugates its delta and carries the parent's *posed* rotation — `R = D · P` already does both, by construction.
The rule is total because each of these is decided, not left to a caller:

| Case | Rule |
| --- | --- |
| order | `R = D · P`: the parent's frame is carried first, then the minimal rotation into the published direction. The reverse order rotates about the parent's axes and is wrong for every non-commuting pair |
| near-zero length | `\|child − parent\| < τ_len · max(1, \|restDir\| · s)` → the segment is **treated as degenerate**, not normalised. `τ_len` is scale-aware (`1e-6` of the segment's own rest length in engine units) so a millimetre-scale attachment and a metre-scale segment use the same rule |
| degenerate | inherit the parent's frame and the authored rest direction (no rotation), as the measured rows of this table require |
| root | `PELVIS` has no parent: `P` is the **derived root frame**, built by one construction in one order — `Z = normalize(HIP_F − HIP_B)`, then `Y = normalize((CHEST − PELVIS) − ((CHEST − PELVIS)·Z)·Z)` **orthogonalised against `Z`**, then `X = Y × Z` — the order stated below and used everywhere, not its mirror — carrying the authored pelvis rest direction, so an authored pelvis tilt is followed instead of discarded. The orthogonalisation is not optional: on the raw pair `X = Y × Z` has length `\|Y\| sin∠(Y,Z) < 1` and the result is not a rotation at all (for `Y = normalize(0.4, 1, 0)` and `Z = normalize(0, 0.6, 0.8)` it is `0.8305`, with the frame's `Y` **33.85°** off the torso — the summary line used the raw pair and the detailed rule orthogonalises, and they are not the same frame). Degeneracy splits in two and only one of them is a definition error: a **zero hip line in a posed frame** (the two hip joints coincide this frame) or a **non-zero hip line and torso that are parallel this frame** (`\|Y × Z\| < τ_len`) is a **degenerate frame** — fall back to the bind world frame and mark the segment `degenerate` — while a **rig whose `hipWidth` is zero**, or whose bind torso is zero, is **invalid bind data** and an **L3 definition error**, refused before any frame exists |
| near-parallel | `\|a × b\| < τ_rot` and `a · b > 0` → `D = identity`, no normalisation of a tiny cross product |
| near-antiparallel | `a · b < 0` and `\|a × b\| < τ_rot` → the **antipodal construction**: let `e` be the world axis least aligned with `a` (x → y → z on ties) and take `axis = normalize(a × e)`, which is perpendicular to `a` **by construction**; `D` is the half-turn by `π` about that axis. Rotating about an axis that is merely *least aligned* with `a` does not place `a` at `−a`: for `a = (1,1,1)/√3` and `b = −a` the tie-break picks `x`, and a half-turn about `x` lands at `acos(1/3) = 70.53°` from `b`. The axis is therefore projected out of `a` rather than chosen from the coordinate axes, and the two agree only when `a` happens to be a coordinate axis. **The construction places `a` at `−a` exactly, which is `b` only at the exact antipode**: throughout the branch the placed direction is off the published `b` by `π − arccos(a · b) < arcsin(τ_rot)`, so the branch is a bounded approximation and not an exact placement — for the representative `τ_rot = 0.001`, `a = (1,0,0)` and `b = (−cos 1e-4, sin 1e-4, 0)` it is `1e-4 rad = 0.0057296°`. `D` is a rotation by `π` — the quaternion `(0, axis)`, **not** `−identity`: the quaternion `−identity` is the identity rotation, whatever the sign convention |
| continuity | **not continuous at the branch boundary `\|a × b\| = τ_rot`: the roll flips by 180° and the placed direction changes by a bounded residual.** Inside the branch `D` is the half-turn about `a × e`, which is the same for both signs of the perpendicular component — `R(u, π) = 2uuᵀ − I` depends on `u` only through the line it spans, so `+Z` and `−Z` are the same rotation and the branch is *continuous in the placed direction* across the antipode. The crossing **out** of the branch is continuous in neither quantity: for `a = (1,0,0)` and `b = normalize(−1, 0, ±δ)`, `δ = 5e−4` takes the branch (half-turn about `+Z`, frame `Y` at `−Y`, placed direction `−X`, which is `δ = 5e−4 rad` from the published `b`) while `δ = 1.5e−3` takes the minimal rotation about `−Y` (frame `Y` at `+Y`, placed direction on `b`), so the direction residual is finite — `1.5e−3 rad` at that pair, and at the boundary itself it is bounded by the branch's own `arcsin(τ_rot)` = `0.0010000001666… rad` = `0.057295789°` for `τ_rot = 0.001`, reproduced as the measured jump with the two inputs `1 ulp` either side — beside the **180° roll flip**. The residual is bounded and stated, not absent, and not hidden behind a threshold that only moves the crossing |
| invalid input | a non-finite coordinate or a zero `restDir` → the segment is `INVALID` with `NON_FINITE_GEOMETRY` (§7.4.1c), never a silently identity frame |

Tests (`SegmentFrameDirectionTest`). **The antipodal branch has two different things to assert, and the fixture asserted
the wrong one.** Inside the branch there is no flip: for `a = (1,0,0)`, binary64 `τ_rot = 0.001` and binary64
`b = (−0.99999999500000003…, ±0.0000999999998333333…, 0)` — both signs of the perpendicular component — `a·b < 0` and
`|a × b| = 9.9999999833e−5 < τ_rot` hold on **both** sides, the tie-break picks `e = +Y` (x → y → z, on the tie between
`y` and `z`), `axis = normalize(a × e) = +Z`, and both sides produce the **same** `+Z` half-turn and the **same** placed
direction `−X`, each `1.000000e−4 rad` from its published `b`. A fixture that demands "the two half-turns, the flip
between them" across the antipode cannot be satisfied by this construction, and demanding it is how the row came to test
nothing. What the row asserts instead:

- **both sides inside the branch** → the identical half-turn, the identical placed direction `−X`, and the `1e−4 rad`
  residual **on each side** (inside `arcsin(τ_rot)`, which is the branch's own bound);
- **the branch boundary `|a × b| = τ_rot`** → the one crossing where continuity is claimed for neither quantity, exercised
  as a **roll flip with a bounded direction residual**: for `a = (1,0,0)` and `b = normalize(−1, 0, ±δ)`, `δ = 5e−4` takes
  the branch (half-turn about `+Z`, frame `Y` at `−Y`), `δ = 1.5e−3` takes the minimal rotation about `−Y` (frame `Y` at
  `+Y`), the frames differ by 180° of roll, and the placed directions differ by a residual the row **asserts a bound for**
  rather than asserting away: at the boundary the two inputs `1 ulp` either side of `|a × b| = τ_rot` place the direction
  `0.057295789°` apart, which is `arcsin(τ_rot)` at `τ_rot = 0.001`;
- **the exact antipode `b = −a`** → direction only (`a` is placed at `−a` exactly), with no continuity claim across it;
- `a = (1,1,1)/√3` with `b = −a` — a **non-axis-aligned antipode**, where the placed direction must equal `b` within
  `τ_rot` and a coordinate-axis half-turn misses it by 70.53°;

and the rest of the row is unchanged: near parallel (within `τ_rot`), zero length (degenerate → inherited frame), the root
(`PELVIS`, derived and orthogonalised), a **parent-roll** case (a parent rotated about a child direction that did not
change: the frame's roll follows the parent, so direction agreement alone does not pass), and a straightening limb whose
`b` rotates through `a` (frame continuous, no flip).

**The roll bound, with its units.** The placed direction is exact at the exact antipode, where the antipodal construction
places `a` at `−a`, and it is within `arcsin(τ_rot)` of the published direction throughout the near-antipodal branch that
construction also covers (§7.2); the roll about it is not bounded by either. An attachment offset of `d`
metres displaced by a roll error of `θ` radians moves by the chord **`2|d| sin(θ/2)`**, of which `|d| · θ` is the upper
bound — the two agree to within 0.03 % at 5° and diverge only for large `θ`. Units: `|d|` in metres (the authored
offset), `θ` in radians (per segment), the displacement in metres, all in the parent segment's frame. `θ` is
**measured per segment** and each attachment's offset is **reviewed against it** (§12.4); the document does not rank the
eight M1 muscles by roll sensitivity, because their coordinates are placeholders (§7.3) and a ranking computed from
placeholder numbers would be a ranking of the placeholders.

| Segment | Node | Direction from published positions | Anatomical content | Notes |
| --- | --- | --- | --- | --- |
| pelvis | `PELVIS` | `PELVIS → LUMBAR` — **coincident**, so the frame comes from the hip line and `PELVIS → CHEST` (stated below) | iliac crest, ASIS, ischial tuberosity, sacrum | also the root translation; the derived frame follows an authored pelvis tilt |
| lumbar spine | `LUMBAR` | `LUMBAR → CHEST` | L1–L5 | one rigid segment, not five vertebrae |
| thorax / ribcage | `CHEST` | `CHEST → NECK_END` | ribs 1–12, sternum, thoracic spine | **no per-rib segments** — a documented limitation vs the AA-3D reference, which models 12 rib pairs |
| neck | `NECK_END` | `NECK_END → HEAD_POS` | C1–C7 | one segment |
| skull | `HEAD_POS` | none — terminal joint, inherits the neck direction | cranium, mandible reference | attachment point, orientation = head |
| clavicle | `CLAVICLE_A/P` | `CLAVICLE_A → SCAPULA_A` — **zero length** | clavicle | degenerate, see below |
| scapula | `SCAPULA_A/P` | `SCAPULA_A → SHOULDER_A` (46 units lateral) | scapular blade, acromion, coracoid | carries the girdle DOF the engine animates |
| humerus | `SHOULDER_A/P` | `SHOULDER_A → ELBOW_A` | humerus | |
| forearm | `ELBOW_A/P` | `ELBOW_A → HAND_A` | radius + ulna as one frame | pronation is not a separate segment; a pronation-aware wrap is future work |
| hand | `HAND_A/P` | `HAND_A → FINGERTIPS_A` — **22 units in 65 of 66 builders**; 0 in `pike_pushup_standard` only (its manual override) | metacarpals, palm | fingers are visual-only; degenerate in that one builder, see below |
| femur | `HIP_F/B` | `HIP_F → KNEE_F` | femur, greater trochanter, linea aspera | **not the hip joint point** |
| tibia | `KNEE_F/B` | `KNEE_F → ANKLE_F` | tibia, fibula, tibial tuberosity | |
| foot | `ANKLE_F/B` | `ANKLE_F → TOE_F` — **24.85 units in every frame measured** | calcaneus, talus, metatarsals | never degenerate; the toe is derived at `footLength · 0.71` |
| toes | `TOE_F/B` | none — terminal joint, inherits the foot direction | phalanges | |

**Which segments cannot derive a direction from positions is measured, and the earlier "five coincident" count was
wrong.** Every registered builder was run and its published positions measured: **66 builders × 9 progress stations
(0, 0.125 … 0.875, 0.999) = 594 frames** at `16e0487`'s engine sources, distances in engine units.

| Published pair | Segment row | Measured across 594 frames |
| --- | --- | --- |
| `PELVIS → LUMBAR` | pelvis | **0.0000 in every frame** — every builder leaves the lumbar offset at zero, so the node is a pass-through child of the pelvis: a builder convention, not a factory invariant (§6.2) |
| `CHEST → CLAVICLE_A/P` | — | **0.0000 in every frame** — the clavicle node sits on the chest |
| `CLAVICLE_A/P → SCAPULA_A/P` | clavicle | **0.0000 in every frame** — the scapula node sits on the clavicle |
| `ANKLE_F/B → TOE_F/B` | foot | **24.8500 in every frame** — never degenerate: `SkeletonPoseFinalizer.adjustFootOrientation` derives the toe at `footLength · 0.71` |
| `ANKLE_F/B → HEEL_F/B` | — | 10.1500 in every frame (derived, `footLength · 0.29`) |
| `HAND_A/P → FINGERTIPS_A/P` | hand | **22.00 in 65 of 66 builders** (`palmLength + fingerLength`, derived); **0.0000 in `pike_pushup_standard` only**, where the pose declares `MANUAL_OVERRIDE` and authors `PALM = KNUCKLES = FINGERTIPS` at one point 60.6 units from the hand |
| `HAND_A/P → WRIST_A/P` | — | 0.0000 in 64 of 66; `60.6` in `pike_pushup_standard` and 11.3–138.0 in `row_standard` — the only two builders that declare a manual override |

So the four rows with **no** position-derived direction are: `pelvis` — the **derived root**, whose frame comes from the
hip line and `PELVIS → CHEST`, not from its own pair, which is coincident only by builder convention; `clavicle` — the
**one zero-length mapped segment**; and `skull` and `toes`, both **terminal** (`HEAD_POS` and `TOE_*` have no child
joint). That is **four rows of fourteen in every frame**, plus
`hand`, which is degenerate in **one** builder out of 66. The `foot` row is *not* degenerate: the toe is 24.85 units ahead
of the ankle in every frame measured, and an earlier revision's claim that `ANKLE → TOE` is coincident is false — it was
probably a reading of the factory's identity offsets rather than of published positions.

**The root frame is derived too, and the first construction proposed for it does not exist.** For the coincidence rows the
frame is the **parent segment's** frame with the authored rest direction, and an attachment authored on one of them is
expressed in the parent's frame: a clavicle offset is a thorax offset. `PELVIS` is the root, so it has no parent segment,
and a fixed bind-world frame is not good enough: the engine **authors pelvis rotation**, and a frozen frame throws it
away. Two poses at the pinned base do exactly that — `declarePelvisTilt` writes `pelvis.localRotation` and registers the
intent (`BasePose.kt:445-448`), `BaseSquatPose.kt:150-151` tilts by `-leanAngle` (`SquatPose.kt:9`: 0.40 rad at the
bottom) and `BaseBirdDogPose.kt:82-83` tilts by `torsoPitch` (`:15`: `-π/2`, the spine flat to the floor). The limb-IK
evidence of the bullet above says a limb joint's published rotation is its parent's, not its segment's; it does **not**
say the root's is unusable, and at the root it is not.

What the carrier publishes is enough to build the frame from positions alone, which is what keeps this table's rule
uniform. `SkeletonPose` carries a world position and a world rotation for all 33 joints, written by
`SkeletonNode.flatten` (`SkeletonNode.kt:73-77`; carrier at `PoseDefinition.kt:113`), and `PELVIS` is a root, so
`roots.forEach { updateWorldTransforms(zeroVector, identityRotation) }` makes its published rotation exactly the authored
tilt. The frame the pinned decision asked for — *hip line plus `PELVIS → LUMBAR`* — cannot be built as written: the
`PELVIS → LUMBAR` pair measures **0.0000 in all 594 frames** (§7.2 above), because `LUMBAR` is a pass-through child. The
available, non-degenerate substitute is measured the same way and is used instead:

```
+Z_engine = normalize(HIP_F − HIP_B)                       // the hip line: 2 × hipWidth = 44 units = 177.9 mm
+t_engine = CHEST − PELVIS                                  // the torso: 120 units = 485.1 mm
+Y_engine = normalize(t_engine − (t_engine·Z_engine)·Z_engine)   // orthogonalised against +Z, not used raw
+X_engine = Y × Z                                           // right-handed completion; unit because Y ⟂ Z
```

**The orthogonalisation is part of the construction, not a remark about it.** A summary line elsewhere in this document
wrote `Y = normalize(CHEST − PELVIS)` and `X = Y × Z` without the projection; on the raw pair `X` has length
`|Y| sin∠(Y,Z) < 1`, so the object returned is not a rotation and cannot be composed with `D`. The difference is not
cosmetic even when the two `X` directions coincide: with `Y = normalize(0.4, 1, 0)` and `Z = normalize(0, 0.6, 0.8)` the
raw construction's `X` has length **0.8305** and its `Y` sits **33.85°** off the torso. One construction, one order, one
place.

Both vectors are published positions, both are well conditioned (44 and 120 units against a 1e-6-unit degeneracy
threshold), and the frame follows a pelvis tilt of any size — which is the point. **The honest limit is that `+Y` is the
pelvis-plus-`LUMBAR` direction**: a `LUMBAR` bend would be absorbed into the pelvis frame rather than shown as a bend of
its own. That is 0 in the two poses that author pelvis motion (their rotation is declared on `PELVIS`, `LUMBAR` carries
identity), and it is bounded elsewhere by `2|d| sin(θ/2)` for the `LUMBAR` angle `θ` — the same expression the roll bound
uses. The fixed bind-world frame this replaces is not bounded by a bend at all: it is wrong by the whole authored tilt,
**22.92° in a squat bottom and 90° in bird-dog**, which displaces a 30 mm attachment by **11.92 mm** and **42.43 mm**
respectively. That is a missing swing, not an unknown axial roll, and no roll measurement can recover it.
Degeneracies are decided, not left to a normalise, and **the two kinds are different facts about different objects.** A
**rig** whose `hipWidth` is zero, or whose bind torso is zero, is **invalid bind data**: an L3 definition error, refused
before any frame exists, because no pose can supply a hip line the skeleton does not have. A **posed** frame whose
`PELVIS → CHEST` length is zero this frame, or whose non-zero hip line and torso are **parallel** this frame
(`|Y × Z| < τ_len`, so the right-handed completion cannot be normalised), is a **degenerate frame**: the pose is legal,
the frame falls back to the bind world frame and the segment is marked `degenerate`, per the `degenerate` row above,
because a parallel pair leaves the frame's roll undetermined and a normalised noise vector would hide that. Never a
normalised zero vector, in any of these cases.
Two poses and this one construction agree on the axis only if the order is stated once: measured on the X1 bind
positions, `HIP_F − HIP_B = (0, 0, −44)` and `CHEST − PELVIS = (0, 120, 0)`, so `Z = (0, 0, −1)`, `Y = (0, 1, 0)` and
`Y × Z = (−1, 0, 0)` — and no line of this document states `Z × Y` any more: the mirrored order gives `(+1, 0, 0)`, the
opposite axis, which is what two earlier revisions printed. The derivation above is the one that is implemented, and it
is now stated in exactly two places, both with the same orthogonalisation: the rule table above and this block. Stage 1
measures both frames, squat and bird-dog (§15), and `θ` for the pelvis segment is reported beside the other segments'
rather than assumed to be zero.

### 7.3 Definition schema

```kotlin
enum class MuscleLayer { SUPERFICIAL, DEEP }

/**
 * Categorical roles only — no intensity, no numeric activation (§8.1). `ANTAGONIST` is in the enum because §8.1 needs
 * the word; it is authored only where reviewed, movement-specific evidence supports it and is never inferred from an
 * eccentric phase. `UNKNOWN` exists so "no reviewed row" is representable, never guessed.
 */
enum class MuscleRole { PRIME_MOVER, SYNERGIST, STABILISER, ANTAGONIST, UNKNOWN }

/** The canonical segment frames of §7.2. One enum value per row of that table; no free-form strings. */
enum class Segment {
    PELVIS, LUMBAR, THORAX, NECK, SKULL,
    CLAVICLE, SCAPULA, HUMERUS, FOREARM, HAND,
    FEMUR, TIBIA, FOOT, TOES
}

/**
 * Where an attachment's numbers came from. The only acceptable recorded value is `DERIVED_SEGMENT`; `UNRECORDED` is the
 * declared default for data whose authoring frame nobody wrote down, and L3 reports it as **frame unverified** rather
 * than passing it (§7.3, §12.4 RV1).
 */
enum class AttachmentFrame { DERIVED_SEGMENT, UNRECORDED }

/**
 * A point pinned to a canonical segment, in metres, authored in that segment's **derived frame** (§7.2) — the frame that
 * gives the segment its direction, not the node's axes and not the world bind axes. Axes are `+Y` superior and `+X`/`+Z`
 * the sagittal and lateral axes with the sign the derived frame fixes, so at bind `+X` resolves along world `−X` and `+Z`
 * along world `−Z` (side `A`). Side `P` is the mirror: same numbers, `z` negated. `frame` records which convention
 * produced them, so the review gate reads a value instead of inferring one.
 */
data class Attachment(
    val segment: Segment,
    val x: Float, val y: Float, val z: Float,
    val frame: AttachmentFrame = AttachmentFrame.UNRECORDED   // which convention produced these three numbers
)

/**
 * A declared exemption from the clearance predicate: one obstacle, one head, one path interval (§7.4.1).
 *
 * Keyed per HEAD (`headIndex`, the slot in [AnatomyFrame.heads]) and never per instance: a head is the thing that has a
 * path. The renderability mask is keyed per `(muscleId, side)` and is the conjunction over that instance's heads (§7.4),
 * so the two keyings are different on purpose and neither substitutes for the other.
 */
data class ContactExemption(
    val obstacleId: String,
    val headIndex: Int,
    val fromStation: Float,       // fractional arc length, 0.0 at the origin
    val toStation: Float,         // reviewed bound on the EXEMPTED side (toStation for an origin, fromStation for an insertion)
    val envelopeSlackMetres: Float = 0f,  // σ: the envelope runs to the first station at g >= e + σ (§7.4.1c). 0 leaves it at
                                          // the first-margin station, which no bracket can certify — so a positive
                                          // exemption must declare one
    val source: String            // the L7 review row that justified it — no source, no exemption
)

data class MuscleDefinition(
    val id: String,                       // "rectus_femoris"
    val name: String,                     // "Rectus femoris"
    val layer: MuscleLayer,
    val heads: List<MuscleHead>,          // one per head; biceps femoris = 2, triceps = 3
    val widthMetres: Float,               // belly width at rest
    val thicknessMetres: Float,           // belly depth at rest
    val tendon: TendonSpec,               // origin/insertion fractions of the path
    val wraps: List<String>,              // wrap-surface ids; an id that does not resolve is an L3 error, never a skip
    val flat: Boolean = false,            // sheet muscles (pecs, lats, intercostals)
    val profile: Float = 0.5f,            // belly position along the path, 0..1
    val radiusKnots: List<Pair<Float, Float>>,   // (station, radiusMetres): the taper of §7.4.1, non-negative, finite
    val passCap: Int = 8,                        // K, the construction pass budget (§7.4 step 4)
    val corridorMetres: Float,                   // bounds the detour escalation d_k (§7.4.5)
    val contactExemptions: List<ContactExemption> = emptyList()
)

data class MuscleHead(
    val origin: Attachment,
    val insertion: Attachment,
    val waypoints: List<Attachment> = emptyList(),
    val label: String = ""      // authored, reviewed display label: "long head", "short head". Empty on a single-head
                                // muscle, and the readout omits it there rather than inventing or numbering one
)

data class WrapSurface(
    val id: String,
    val segment: Segment,                 // the obstacle follows this segment's frame every frame (§7.4.1)
    val centreMetres: Triple<Float, Float, Float>,
    val radiiMetres: Triple<Float, Float, Float>,  // ellipsoid only; a capsule is a different shape and a different test
    val global: Boolean = false           // in EVERY head's union, whether or not the muscle lists it (§7.4.2)
)
```

**Every parameter has exactly one owner.** `AnatomyDefinition.version` is the schema-and-data version, and
`epsNumMetres` belongs to it — one value per version, not a per-call argument, because changing it invalidates every
fixture signed against the old one (§7.4.1). `passCap`, `corridorMetres` and `radiusKnots` belong to the **muscle**,
because they describe that muscle's route and its tube. `global` belongs to the **obstacle**, because it says who must
see it. A parameter with no named owner is a parameter two subsystems will disagree about.

**Ids are resolved, not looked up hopefully.** Every `wraps` entry must resolve to a `WrapSurface.id` in the same
definition; an unknown id is a **definition error at L3**, not a silently ignored obstacle, because a skipped wrap is a
muscle routed through a bone that nobody is told about. A `global` obstacle is never named in a muscle's list — it is
unioned in (§7.4.2) — and naming it there is an L3 error too, since it makes the definition's meaning depend on whether
the id is also global. Duplicate obstacle ids, a duplicate id inside one list, and a `ContactExemption` naming an
obstacle the head never sees are the same class of error.

`Segment` is a closed enum precisely so an attachment cannot name a frame that does not exist. `PATELLA_REF` is **not** a
segment: the patella is a sesamoid in the tendon, not a canonical frame, so a muscle inserting "at the patella" names the
tibia (tibial tuberosity) and lets the tendon fraction cover the rest.

A `WrapSurface` is what §7.4 calls an **obstacle**: the same object, named after its effect on the path. The two words
are used interchangeably, and neither implies a physical contact model.

A concrete entry, with the schema's intent made explicit:

```json
{
  "id": "rectus_femoris",
  "name": "Rectus femoris",
  "layer": "superficial",
  "heads": [{
    "origin":    { "segment": "PELVIS", "x": 0.050, "y": 0.105, "z": 0.030 },
    "waypoints": [{ "segment": "FEMUR",  "x": 0.055, "y": -0.180, "z": 0.025 }],
    "insertion": { "segment": "TIBIA", "x": 0.000, "y": -0.010, "z": 0.000 }
  }],
  "widthMetres": 0.048,
  "thicknessMetres": 0.028,
  "tendon": { "originFraction": 0.10, "insertionFraction": 0.16 },
  "wraps": ["hip_capsule"],
  "profile": 0.45,
  "radiusKnots": [[0.0, 0.006], [0.10, 0.024], [0.84, 0.024], [1.0, 0.004]],
  "passCap": 8,
  "corridorMetres": 0.12,
  "contactExemptions": []
}
```

*All coordinates above are illustrative placeholders for the schema, not calibrated anatomy.* Calibration against the
engine's rest stance is a roadmap item (§15, stage 2), and its acceptance test follows §7.4.4's two strengths rather than
one: a **solver-derived rest length** — the same solver at the X1 bind frame, finite and strictly greater than zero — is
**mandatory for every head**, and a comparison against an **independent modelled segment length** is required **only
where the definition or §12.4 names one**, with the source, the reference stance and the uncertainty recorded. A head with
no named external reference records an **evidence gap**, which is neither a pass nor an M1 blocker. This document makes no
tape-measure claim: nothing here has been measured on a body.

**Provenance is carried per attachment, not per instance, and that is the field the review gate closes.** Every `Attachment`
— origin, insertion **and every waypoint** — declares `Attachment.frame`, so "which convention produced these numbers" is a
value on the record rather than a convention inferred from the KDoc. A head can have correctly reviewed endpoints and a
waypoint authored in the old node or world axes, and a per-instance check cannot see that mixed frame; RV1 (§12.4)
therefore closes each attachment separately. `frame` defaults to `UNRECORDED`, and `UNRECORDED` is reported as **frame
unverified**, never silently read as `DERIVED_SEGMENT`.

**Whether each placeholder was authored against the derived segment frame is not recorded, and that is stated per instance
rather than assumed for the set.** §7.7.1 fixes the M1 set at **18 head-side instances**; this section supplies one worked
example, in which the side is not stated and no authoring frame is recorded for the numbers. The other sixteen instances
have no coordinates at all. **The corrected `Attachment` KDoc above is a convention for what is authored from here on; it
is not provenance for numbers that already exist**, because those numbers were invented to demonstrate the schema. **No
sign is flipped here and no anatomy is invented**: an offset whose authoring frame is not recorded is **frame unverified**,
and it is unverified whether or not numbers exist for it, because "there is nothing to get wrong yet" and "nobody wrote
down which frame" are both answers a reviewer has to act on.

| # | Muscle | Instance | Placeholder coordinates in this document | Frame they were authored against, as recorded | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | rectus abdominis | side `A` | none | not recorded — no coordinates exist to record it for | **frame unverified** |
| 2 | rectus abdominis | side `P` | none | not recorded | **frame unverified** |
| 3 | external oblique | side `A` | none | not recorded | **frame unverified** |
| 4 | external oblique | side `P` | none | not recorded | **frame unverified** |
| 5 | erector spinae | side `A` | none | not recorded | **frame unverified** |
| 6 | erector spinae | side `P` | none | not recorded | **frame unverified** |
| 7 | latissimus dorsi | side `A` | none | not recorded | **frame unverified** |
| 8 | latissimus dorsi | side `P` | none | not recorded | **frame unverified** |
| 9 | gluteus maximus | side `A` | none | not recorded | **frame unverified** |
| 10 | gluteus maximus | side `P` | none | not recorded | **frame unverified** |
| 11 | gluteus medius | side `A` | none | not recorded | **frame unverified** |
| 12 | gluteus medius | side `P` | none | not recorded | **frame unverified** |
| 13 | biceps femoris | long head, side `A` | none | not recorded | **frame unverified** |
| 14 | biceps femoris | long head, side `P` | none | not recorded | **frame unverified** |
| 15 | biceps femoris | short head, side `A` | none | not recorded | **frame unverified** |
| 16 | biceps femoris | short head, side `P` | none | not recorded | **frame unverified** |
| 17 | rectus femoris | side `A` | the worked example above — **side not stated**, so the single set covers one side at most | not recorded: the example declares itself illustrative and names no frame, and the axis list that stood above it before this revision was unqualified, so nothing in the document says which of node axes, derived frame or world bind axes these three numbers were written in | **frame unverified** |
| 18 | rectus femoris | side `P` | the worked example above, if its `z` is read as side `A`'s | not recorded — a mirror is arithmetic (`z` negated) over an unverified source | **frame unverified** |

**Review gate — the frame and its sign are confirmed at §12.4 before M1 acceptance.** Every row above stays
**frame unverified** until a reviewer closes it, and M1 does not accept an unverified instance on the strength of a green
L3: the L3 envelope test is satisfied by a half-turned frame for every muscle whose offsets are symmetric, so it cannot
see this error at all (§12). The confirmation is per instance, against the reviewed landmark, and it is written so that it
can fail:

- **left/right** — the instance on side `A` and its mirror on side `P` land on opposite sides of the frame's `+Z`, checked
  on the landmark pair the review cites (a muscle whose two instances collapse onto one another is a mirror error);
- **anterior/posterior** — an origin the definition calls anterior lands on the anterior landmark the review cites, and
  one it calls posterior lands on the posterior one. A 180° error about `Y` inverts exactly this pair while leaving
  left/right intact, which is why both checks are required and neither substitutes for the other.

An attachment set can be internally mirrored, symmetric, inside every envelope and 180° wrong in `X` or `Z`; that set
passes L3 and both symmetry checks and fails this gate. The gate is recorded per instance in the review record (§12.4),
so "sixteen of eighteen confirmed, two deferred" is a state the document can express and a silent assumption is not.

### 7.4 Path solving

Per muscle, per frame (`AnatomySolver.solve`), pure and allocation-light. The steps below are the whole algorithm: what a
renderer or a validator needs is an output of it, and nothing downstream re-routes a path.

```
for each head:
  1  resolve attachments:  world = segmentOrigin + R_segment * (offsetMetres * unitsPerMetre)
       segmentOrigin = the segment node's published world position
       R_segment     = X9: the minimal rotation taking (parentFrame * restDirection) to the published
                       direction p_child - p_parent, applied in the parent's frame (§7.2)
       - the direction comes from POSITIONS only; the published joint rotation is NOT the segment
         orientation (measured: §7.2 — IK writes local positions, the hierarchy publishes rotation)
       - roll is an approximation: an offset d carries up to |d| * theta of error (theta per segment, §11)
       - degenerate segment (no position-derived direction): inherit the parent segment's frame and the
         authored rest direction — never normalise a zero vector (§7.2, §7.4.5)
  2  build the control polyline: origin, waypoints..., insertion
  3  resample the control polyline at N points (N ∝ path length / 0.01 m, clamped 8..48). N and
       the spacing are declared per definition version, so two runs resample identically.
  4  pass loop, at most K passes (K is a definition field, default 8). One pass, in this order:
        a  construct  — for each obstacle in the head's ordered union (§7.4.2), for each segment of
           the current polyline, replace exactly the crossed subsegments: split the segment at its
           entry and exit stations and replace the middle piece with two segments through ONE
           lateral detour waypoint (§7.4.5). Entry and exit points are temporary construction
           points — retained only if the final check accepts them, dropped when the waypoint
           supersedes them. The two endpoints are never moved.
        b  smooth     — one Catmull-Rom pass, 0.25 blend, with the parameterisation, sampling and
           endpoint policy pinned in §7.4.5, so the same input produces the same curve.
        c  project    — push every sample closer than r + e to a declared surface out to r + e along
           the outward normal, in declaration order. This is the LAST geometry-changing operation.
        d  check      — the full §7.4.1 predicate on the resulting polyline: every segment against
           every obstacle in the union, plus endpoints, tangents and curvature. It does not mutate
           the path, and it is the SAME predicate that decides acceptance — construction cannot
           accept a path that validation would reject.
        Accept only if every check in (d) passes. Otherwise the next pass starts from the current
        points, escalates the detour distance, and the loop ends at K with INVALID / PASS_CAP.
  5  modelledPathLength = Σ |p[i] − p[i−1]|    // along the solved path, NOT the straight chord
  6  visualTendonPortionLength = (originFraction + insertionFraction) * modelledPathLength
  7  relativePathLengthChange = modelledPathLength / restLength − 1   // reported; never a geometry input (§7.6)
```

Step 4 inserts points, so the path buffer is sized for the post-insertion maximum, `pointCapacity` (§5.3); exceeding it
is `INVALID` (`BUFFER_CAPACITY`), never a silent truncation. **Nothing is smoothed after step 4d.** A smoothing pass that
runs after the last check produces a curve the predicate never saw, which is the ordering defect this loop exists to
remove: smoothing is step 4b, the projection is step 4c, and the check is last.

`MuscleState` therefore carries a `PathStatus` — `VALID`, or `INVALID` with a reason and the failing segment and
obstacle indices. A consumer may not read a missing status as `VALID`, and no layer draws an `INVALID` path as if it
were anatomy.

**Renderability is per instance, and the carrier is declared rather than implied.** The frame publishes it as
`AnatomyFrame.renderable: BooleanArray`, **one entry per `(muscleId, side)` instance** in the frame's fixed instance
order (the same order as `heads`, grouped by muscle, ×`Side`), computed by `AnatomySolver.solve` as the **conjunction of
the statuses of every head required by that instance** — a two-head muscle's instance is renderable only if both heads
are `VALID`. `NOT_PROCESSED` is not renderable, for the same reason it is not `VALID`.

| Property | Rule |
| --- | --- |
| producer | `AnatomySolver.solve`, written in the same pass as the head statuses, so a mask cannot be a frame behind |
| lifetime | one frame, exactly like the rest of the frame; `clear()` sets every entry `false` |
| transport | a field of `AnatomyFrame`; `CharacterSceneFrame.geometry` (§10.2) is the **prefiltered geometry list** built from it, one `DynamicGeometrySlot` per drawn instance with a session-stable `slotId` — the renderer never re-derives the mask from statuses, and a slot that drops out of the list is cleared by id |
| key | `(muscleId, side)`, never a head index: a head index is not an instance and a two-head muscle has one silhouette. **The two keyings are deliberate and ordered**: an exemption and every path check key on the **head** (§7.4.1c), while the mask keys on the **instance** and is the **conjunction over that instance's heads** — so a two-head muscle with one exempted head and one violation is unrenderable, and the exemption stays a per-head fact |
| partial-head policy | **off by default.** A multi-head muscle is all-or-nothing; a per-head behaviour requires the definition to declare it explicitly and §12.4 to review the half-drawn silhouette as a separate criterion |

The renderer consumes the mask or the prefiltered list, and the rules are these:

- `INVALID` hides that instance **in the same frame** — no fade, no retry, no "last good path" — and its previously
  uploaded geometry is cleared, so a stale tube cannot be drawn along the last valid route while the solver reports a
  failure.
- A multi-head muscle is **all-or-nothing**: one invalid head hides the whole instance, because a muscle drawn with one
  of its heads missing is a shape no reviewer approved. A per-head behaviour is allowed only if the definition declares
  it explicitly and §12.4 reviews the half-drawn silhouette as a separate criterion.
- The reason is **diagnostic only**. It goes to the validator report and the debug overlay; it never reaches user-facing
  copy, and it never selects a colour that implies anatomy.
- **The skeleton is untouched.** A hidden muscle never freezes, hides or moves a joint — the path is hidden, the pose is
  not (§11, hard rule 1).

**The per-instance mask is exact only for generated geometry, and strategy C owes its own mechanism.** Dropping a
`DynamicGeometrySlot` hides exactly one generated tube, because that slot *is* the geometry. The same act does not hide
part of an already-loaded authored mesh: a shared skin, a regional renderable and a layer bit each hide neighbours too, and
`selectedInstance` indexes the dynamic list, so it has nothing to say about an authored instance. **M1 is unaffected** —
it ships strategy A, whose muscle layer is one dynamic mesh per layer with no skin and no helpers (§10.3) — and this
section's contract is therefore unchanged for M1. What strategy C must supply is stated as a **stage-3 gate** rather than
designed here:
per-instance visibility **and** selection of authored muscle meshes; a renderer-neutral carrier for both, keeping the
boundary rule of §10.2 intact (no muscle id, status or reason reaches the renderer); and an asset partition that makes one
instance addressable at all. The stage-3 exercise of the valid → invalid → valid sequence runs with neighbouring authored
instances visible, because the failure mode being guarded is a mask that hides more than the invalid instance.

`MuscleRenderabilityTest` (L4/presentation) drives valid → invalid → valid on one instance across carrier reuse and
asserts the mask, the cleared geometry and the restored path. A stale buffer that survives an invalid frame is exactly
the failure it exists to catch, and a test that only checks the first valid frame cannot see it.

#### 7.4.1 Obstacle validation contract

Three different checks are routinely conflated. This section keeps them apart, and only the last of them is the
criterion.

**(a) Sample check — necessary, not sufficient.** Every sample of the final polyline lies outside every declared
obstacle, **except inside that head's reviewed transition envelope**, where the exemption of §7.4.1c (X5) applies: the
check reads the same `(head, obstacle, arc-length interval)` mask as the endpoint check, construction, projection and the
final validation, so a sample that is legitimately inside an exempted obstacle is not a violation and a sample inside the
same obstacle outside the interval still is. This catches gross violations cheaply and bounds how far the projection step
still has to move. It proves
nothing about the segments between samples: two consecutive samples can both be outside an obstacle while the straight
segment joining them passes through it, and a 1 cm sample spacing cannot see an obstacle thinner than 1 cm at all.

**(b) Segment check — the criterion, in three stages.** Every segment `[p_i, p_{i+1}]` of the final polyline is tested
against every declared obstacle, in the **obstacle's own frame**, because the obstacle is declared in a segment frame and
moves with that segment every frame — a rotation the test cannot ignore:

```
s  = unitsPerMetre
cw = segmentOrigin + R_segment · (centreMetres · s)
Rw = the obstacle's world axes = R_segment
R  = radiiMetres · s          // semi-axes, engine units — NOT inflated
e  = eps_num · s              // the numeric margin, engine units
```

`Rw` is the **same `R_segment` the attachments use** (§7.4 step 1), not the published joint rotation: an obstacle and an
attachment declared in the same segment frame have to move together, and taking the node's published rotation for one and
the derived frame for the other would rotate the obstacle relative to the muscle it constrains. The X9 roll
approximation therefore applies to obstacles too, and the `|d| · θ` bound covers both.

**One exhaustive state machine, three stages, three outcomes per stage.** Every stage returns exactly one of `CLEAR`,
`INTERSECT` or `UNCERTAIN`, and the pair takes the first stage whose own arithmetic carries its claim. A stage may only
return `CLEAR` when it proves the *whole* criterion, which is why stage 1 can accept early and stage 2 never can; and a
stage may only return `INTERSECT` when it exhibits a **witness** that survives its own rounding — a station inside the
solid by more than the declared coefficient error — which is why stage 2 needs a witness rule and not only a band.

```
d(t) = Euclidean distance from p(t) to the closed SOLID ellipsoid, in engine units, obstacle frame
       d = 0 anywhere inside the surface — not the distance to the surface

1  bounding sphere — the only cheap CERTIFIED CLEAR.
   c = segment midpoint, h = |p_{i+1} − p_i| / 2, max_R = max(R), max_r = the largest modelled
   tube radius on this path.  CLEAR iff
       |c − cw| − h  >  max_R + max_r + e
   then stop, for this pair. Otherwise UNCERTAIN — never INTERSECT.
   A centreline miss alone may NOT accept: a tube of radius max_r reaches an obstacle that its
   centreline misses, so the sphere is expanded by the largest segment radius as well as by e.

2  quadratic — the cheap intersection test; it can NEVER accept, and its roots are DIAGNOSTICS.
       q0 = Rwᵀ (p_i − cw) / R,   qd = Rwᵀ (p_{i+1} − p_i) / R
       a = qd·qd,   b = 2 q0·qd,   c0 = q0·q0 − 1,   Δ = b² − 4 a c0
   Δ, t0 and t1 say whether the chord enters and where, which is what the failure report names.
   They are never the verdict, and Δ > Δ_tol is not an intersection.
   INTERSECT requires BOTH of:
     (i) a WITNESS STATION t_m — the midpoint of the root interval's overlap with [0, 1] —
         at which the same rounded coefficients give
             s(t_m) = q0·q0 + 2·t_m·(q0·qd) + t_m²·(qd·qd)   ≤   1 − κ_s,
             κ_s = κ_c · (s(t_m) + 1)
     (ii) a PROVED ENCLOSURE for the operations that formed q0, qd and s on THIS input:
          either κ_s is a forward-error bound derived for the represented inputs, or the
          input lies inside declared supported geometry (an axis-ratio and scale range) on
          which κ_s has been proved. κ_c = 1e-12 is NEITHER: it is not a universal bound
          over the permitted positive-axis domain (a false witness is reproducible below).
   Without (ii) a station that beats κ_s routes to stage 3 as UNCERTAIN — not INTERSECT.
   The enclosure condition is recorded per pair beside the verdict.
   INVALID, SEGMENT_INTERSECTS_OBSTACLE, d = 0 < r is reported only for a witnessed entry
   whose enclosure holds; an unproved witness is not an intersection verdict — it routes to
   stage 3, whose own result is authoritative for that pair.
   No witness → UNCERTAIN → stage 3, which decides on distance. A chord whose c0 is lost to
   the subtraction, or whose only contact with [0, 1] is an endpoint, has no witness.
   The root interval is still necessary: an empty overlap has no candidate station at all, and
   that is a segment that misses (fixture tangent_off_segment_clear). It is not sufficient —
   both Float chords below have a non-empty overlap and no entry.
   Δ < −Δ_tol also selects no witness; it still does not prove clearance by e, so it is UNCERTAIN.
   Stage 2 needs no real roots for Δ < 0: there are none, and none are reported.

3  distance — the general verdict, for every pair stages 1 and 2 did not certify.
       minimise g(t) = d(t) − r(t) over t ∈ [0, 1] in engine units, obstacle frame:
         d(t) = distance to the closed solid ellipsoid (§7.4.1b-1, point-to-ellipsoid solver below)
         r(t) = the modelled tube radius at the same station, piecewise-affine (below)
       CLEAR           iff lower(n) ≥ e
       VIOLATION       iff upper(n) < e   (CLEARANCE_VIOLATION)
       UNCERTAIN       otherwise → INVALID with UNCERTIFIED_MINIMUM and the bracket width reported
   Equality is CLEAR. "Lower" and "upper" are bounds only when B_eval is a bound: the bracket
   arithmetic below is proved for an exact g, and B_eval is an ESTIMATE until deliverable (4)
   of §7.4.1c supplies the point-to-ellipsoid bound — so a CLEAR obtained with an estimated
   B_eval is conditional and this document does not call it certified. Stage 3 is where a near
   miss becomes CLEAR or INVALID; stage 3 never runs for a pair stage 1 cleared.
```

**A stage-1 `CLEAR` is not subject to the fail-closed rule, and that is a proof rather than an exception.** The global
rule in §7.4.1c makes every pair with no bound on the point-to-ellipsoid distance `UNCERTAIN`; a stage-1 pair is not such
a pair, because its `CLEAR` rests on `|c − cw| − h > max_R + max_r + e` — one inequality over published positions, the
declared obstacle radii and the path's own tube radius, with no root solve and no distance evaluation anywhere in it. Such
a pair is **independently cleared by a proved sphere test** and never reaches the branch that reports
`UNCERTIFIED_MINIMUM`. Two limits keep this from becoming a loophole: condition (a)'s operand bound still applies to a
stage-1 operand, and a pair that *fails* stage 1 by any margin is `UNCERTAIN` rather than clear — failing a sufficient
condition proves nothing about the opposite, which is why stage 1 can accept early and can never reject.

**Why a witness, and the two `Float` chords that show an interval is not one.** `c0 = q0·q0 − 1` subtracts two numbers
that are both close to 1 exactly when the segment grazes the surface, so the excess it should carry can vanish below the
mantissa. **(i)** Unit sphere, two `Float`-representable endpoints `(1, v, 0)` and `(1, 2v, 0)` with
`v = 9.999999717180685e-10`: every point of the segment is strictly outside
(`Σ(x_i/R_i)² ≥ 1 + v² = 1 + 9.999999434361379e-19`), yet `q0·q0` rounds to exactly `1`, so `c0 = 0`,
`a = v² = 9.999999434361379e-19`, `b = 2v² = 1.9999998868722757e-18` — **not** `a = b`, an echo this revision corrects
— `Δ = b² = 3.999999547489116e-36 > Δ_tol`, and the roots are `[−2, 0]`, an interval whose only contact with `[0, 1]` is
the single station `t = 0`, where `s = 1` exactly. The band on `|c0|` does not refuse this chord (`c0 = 0` is *inside* the
band, so it routes onward), and the witness rule does: `s(0) = 1 > 1 − κ_s`, so no station is inside. **(ii)** A large
`c0` does not help either, because the excess can be lost at the **other** endpoint. Unit sphere, `Float` endpoints
`(2, 0, 0)` and `(1, 9.999999717180685e-10, 0)`: binary64 gives `a = 1`, `b = −4`, `c0 = 3`, `Δ = 4`,
`Δ_tol = κ·(b² + abs(4·a·c0)) = 2.8e-11` and roots `[1, 3]`, so the interval touches `[0, 1]` at exactly `t = 1` — and the
pair is strictly outside throughout, with exact squared excess `9.999999434361379e-19` at that endpoint and exact lower
root `1 + 5.0e-19`. `|c0| = 3 > κ_c·(q0·q0 + 1) = 5e-12`, so **the coefficient band passes it**: an interval test alone
certifies an intersection for a segment that never enters. The witness rule refuses it as well — `s(1) = 1` exactly —
so the pair goes to stage 3, where the verdict is `CLEARANCE_VIOLATION`: the right reason, not
`SEGMENT_INTERSECTS_OBSTACLE`. **The distance there is not `1e-9` and not `v`.** The nearest point of that segment has
exact binary32 coordinate `v = 9.9999997171806853657471947371959686279296875e−10` beyond the unit sphere, so the distance
to the **solid** is `√(1 + v²) − 1 = 4.999999717180689…e−19` — `v²/2` to first order, not `v`. `v` is the *coordinate*
excess and `v²` is the squared excess; both earlier figures (`min d ≈ 1e-9`, and the same value printed in the fixture
row) confused the three by nine orders of magnitude. A direct binary64 evaluation of `√(1 + v²) − 1` returns **`0`** by
cancellation — `1 + v²` rounds to `1` — so it is not a reference either, and no resolved non-zero distance may be inferred
from it. The verdict does not change: `4.999999717e−19 < e`, so the pair is a `CLEARANCE_VIOLATION` for the reason stated.

Neither chord is a false CLEAR; both are false *certificates* and false *reasons*, and a reason that names the wrong
failure sends the fix to the wrong place. `raw_near_surface_vanishing_excess` pins chord (i) and `raw_stage2_edge_touch`
pins chord (ii) (§12.2.1): both must report `UNCERTAIN` at stage 2 and let stage 3 decide on distance. The rule is
deliberately stated as a witness rather than as a tighter band, because (ii) has no cancellation in `c0` at all — no band
on `c0` or on `Δ` can see it.

**A witness is not a certificate, and the band that admits it is not a bound.** The two chords above show that a *root
interval* is not an intersection; a third case shows that a *witness* is not one either. The coefficients are formed from
represented operands, and the error of forming them is not bounded by `κ_c = 1e-12` over the permitted positive-axis
domain — the transform, the subtraction and the thin-axis division can each lose a term far larger than that. Represented
`Float` operands, with semi-axes `R = (1, 1.0000000168623835263871646450439811815158464014530181884765625e−16, 1)`, centre
`cw = (0, 1.0000000036274937255387218471014421083964407444000244140625e−15, 0)`, endpoints `(−100, −100, 0)` and
`(100, 100, 0)` and a 45° obstacle rotation whose binary64 entries are `±0.70710678118654757273731092936941422522068023681640625`:

| quantity | binary64 evaluation of the written formulas | 100-digit reference on the same represented inputs |
| --- | --- | --- |
| `q0` | `(−141.4213562373095, 0, 0)` | `(−141.42135623730951, −7.0710677182806743, 0)` |
| `a, b, c0` | `80000, −80000, 19999` | `80000.0000000000…, −80000.00000000…, 20048.999998676514` |
| `Δ` / `Δ_tol` | `320000` / `0.01279968` | `Δ < 0` — no real roots |
| `s(t_m)` at `t_m = 0.5` | **`0`** → witness, `s ≤ 1 − κ_s` | min normalised squared distance over `[0,1]` = **`49.999998676511057826257702406844`** |

The failure is not in `Δ` and it is not a band problem: the transform subtracts the `1e−15` centre displacement from a
coordinate of magnitude `100`, where binary64 spacing is `1.4e−14`, so the displacement is **gone before the thin-axis
division** and the segment appears to pass through the centre — `s(0.5) = 0` exactly. The exact arithmetic puts the closest
approach `7.0710677…` semi-axes away, comfortably outside the solid, so this is a **false intersection**: the wrong reason
on a pair that is clear, which sends the fix to the muscle's path instead of to the arithmetic. A second `Float` scale
(all coordinates and semi-axes divided by 100) returns the same witness, so it is not a one-off scale. **The revision under
review permits this**, and the consequence is the rule above: a witness without a proved enclosure is `UNCERTAIN`, so
`κ_c` is documented as an allowance that has to be **earned** by deliverable (2) or by a declared supported geometry, not
as a bound it already has. The fixture that would exercise the proven branch is deferred with that deliverable; until it
exists, every witnessed pair this document can construct also carries an unproved enclosure and fails closed.

**`κ_c` is an allowance, and the enclosure deliverable is what earns it.** It buys nothing today: a station that beats it
is `UNCERTAIN` unless it also carries a proved enclosure, so the only thing that can turn it into a usable threshold is
**deliverable (2) of §7.4.1c** — the forward-error analysis of `q0`, `qd` and `s(t)` over the permitted positive-axis
domain — or a declared supported geometry on which that analysis has been done. It is named in **exactly eight
locations**, so a rename is a mechanical pass over them and nothing else depends on the spelling:

1. the stage-2 block's conditions (i)–(ii), where `κ_s = κ_c · (s(t_m) + 1)`;
2. the coefficient test in the chord-(ii) discussion (`|c0| = 3 > κ_c·(q0·q0 + 1)`);
3. the paragraph withdrawing the bound ("the error of forming them is not bounded by `κ_c = 1e-12`");
4. the paragraph that calls it an allowance to be earned;
5. **this paragraph**, including the quoted names in this list;
6. the **Witness band** row of the numeric policy below;
7. reason table row 7, `SEGMENT_INTERSECTS_OBSTACLE`;
8. deliverable (2) of §7.4.1c.

**No other text references it** — no fixture row, no interface, no test name and no enum value does. A ninth site is the
signal that the rename moved a definition rather than a spelling.

**`d` is a solid distance, and the difference is not cosmetic.** The distance from a point to an ellipsoid *surface*
is not convex inside the body — for the unit sphere the surface distance at `x = −1, 0, 1` is `0, 1, 0` — so a
minimisation over it has an interior maximum and a golden-section search over `[0, 1]` can converge into the wrong
basin or return an infinite-looking minimum for a segment that is entirely interior. The distance to the **closed solid**
(`d = 0` inside) is convex, is `0` exactly where the tube is inside the obstacle, and makes `g(t) = d(t) − r(t)` a
convex function on each radius piece — which is what the minimiser's bracket assumes. An entirely-interior segment
is therefore `d = 0` and `g = −r(t) < 0`, not a special case. An entirely-interior **segment** never reaches stage 3,
though: the root interval of a straight segment whose two ends are both inside a closed ellipsoid always meets `[0, 1]`,
so stage 2 certifies the intersection first, and the direct coverage of the solid distance is the point fixtures
(`pe_interior`, `pe_centre`). `raw_interior_segment` therefore pins the **stage-2** reason, `raw_shallow_crossing` pins a
crossing well outside the fail-closed band, and each ships with a near-miss control that must stay `CLEAR` (§12.2.1).

**The radius is piecewise-affine, and it is part of the object being tested.** `r(t)` tapers along the path with
knots at the declared stations — by default `0`, `originFraction`, `1 − insertionFraction`, `1` — non-negative and
finite at every knot, affine between them; a negative or non-finite knot value is a **definition error at L3**, never a
runtime clamp. Tapering at both ends is what makes the rendered tube end in tendon rather than in a blunt disc, so a
guarantee that ignored the taper would certify a shape nobody draws.

**The minimiser runs per piece, because `g` is only piecewise-convex.** `d(t)` is the distance from a point moving
affinely to a convex set, so it is convex in `t`; `r(t)` is affine between knots; so `g` is convex **on each piece
between knots**, where a golden-section search is valid. `g` is not convex across a knot, and one search over `[0, 1]`
converges into the wrong basin. Recorded counterexample (`taper_knot_minimum`): radius knots
`(0, 0), (0.2, 1.21), (0.4, 1.215), (0.6, 1.55), (1, 0)` on a straight path `p(t) = (2 + t, 0, 0)`. Computed in double
precision, `g(0.2) = −0.010`, so the path violates the criterion on `[0.198020, 0.210255]`, while a single
golden-section run on `[0, 1]` returns `t = 0.6`, `g = +0.05`, and reports CLEAR. **The RFC had no such search at all
before this revision** — it had no distance predicate to minimise — so this counterexample constrains the design rather
than contradicting an earlier one: split at every knot, minimise on each piece, take the global minimum.

**The minimiser returns a bracket, not a number — and the bracket is a certificate only when its budget is a bound.**
The search is golden-section on a piece `[t_a, t_b]` with
`g(t) = d(t) − r(t)`, where `p(t) = p_0 + t·dp` and `r(t)` is affine on the piece with `dr = (r_b − r_a)/(t_b − t_a)`;
`d` is 1-Lipschitz in position, so on that piece `g` is Lipschitz with

```
L    = |dp| + |dr|                              // engine units per unit t
w_n  = w_0 · φ⁻ⁿ ,  φ = (1 + √5)/2              // the bracket width after n iterations, w_0 = t_b − t_a
n    ≥ ceil( log(w_0 / w_target) / log(φ) )     // the iteration count, fixed by the formula, never by "converged"
```

and the verdict is read off the bracket with `B_eval` the evaluation budget of one `g` — the point-to-ellipsoid error
**estimate** of §7.4.1b-1 plus the affine radius rounding, an estimate that deliverable (4) of §7.4.1c has to turn into a
bound:

```
lower(n) = ( min over the n evaluated points of g ) − L · w_n / 2 − B_eval
upper(n) = ( min over the n evaluated points of g ) + B_eval
CLEAR            iff lower(n) ≥ e            VIOLATION iff upper(n) < e            else UNCERTAIN
```

Both bounds are bounds **only when `B_eval` is**; while `B_eval` is the estimate §7.4.1b-1 reports, `lower(n)` is an
estimate of a lower bound and a `CLEAR` read from it is conditional. The bracket arithmetic itself — the `L · w_n / 2`
term and the fixed iteration count — is proved for an exact `g` and does not depend on that condition.

**Both endpoints and every knot are evaluated, always.** `t = 0` and `t = 1` are part of the candidate set (a segment's
closest approach is very often at an endpoint), and the knot stations split the pieces so a piece boundary is evaluated
twice rather than approximated. The iteration rule is the formula above, so two implementations return the same `t` and
the same bracket and the same verdict for the same input; there is no "iterate until converged" loop whose count
depends on rounding.

**Uncertainty fails closed, with its own reason.** A piece whose bracket cannot certify either side reports
`UNCERTIFIED_MINIMUM` — a distinct reason from `CLEARANCE_VIOLATION`, because "the arithmetic could not decide" and "the
path is 0.3 mm inside the margin" need different fixes. Fixtures `minimum_at_endpoint_low`, `minimum_at_endpoint_high`,
`minimum_at_a_knot` and `minimum_near_equality` pin the four cases the bracket has to handle: a minimum at each
endpoint, a minimum exactly on a knot, and a `min g` within one evaluation budget of `e`, which must report `UNCERTAIN`
rather than flip a coin.

**Equality policy, stated once and conservatively.** `g ≥ e` is CLEAR at the level of the *bracket*: a pair whose
`lower(n)` is exactly `e` passes, and one whose `upper(n)` is exactly `e` does not fail. The stricter policy the
construction follows is the retained-point rule of §7.4.5 — every retained point must be **strictly** beyond `r + e` —
so the two do not contradict: acceptance is inclusive, construction aims beyond the margin, and a path that only just
clears can be built but never counted as clear when the bracket cannot show it.

**`Δ_tol` is fail-closed, and its failure mode is a false INVALID.** `Δ_tol = κ·(b² + abs(4·a·c0))` with `κ = 1e-12` is a
relative bound on the two products whose difference forms `Δ`, so it scales with the geometry instead of being a fixed
absolute epsilon. `κ` is about 4,000× the double-precision noise floor for a `Δ` of order 1 (≈2.2e-16 relative), which is
what makes the band a band rather than a bit test — and it is deliberately loose, because the band's only effect here is
to route a pair to stage 3. A chord inside the band is never cleared by the quadratic. **No sampling census is offered for
the band and none is retained.** An earlier revision reported a count of near-tangent chords sampled across it as evidence
that the band's errors fell in the safe direction; that figure is withdrawn — there is no artefact behind it that anyone
could re-run, and the question it answered has moved. Under a discriminant-only verdict the band's failure mode was indeed
a **false INVALID**, routing a grazing chord to a stage that could not clear it; with `Δ` demoted to a diagnostic the band
cannot produce a verdict at all, so "how often is it wrong" no longer bears on anything, and its actual cost is one stage-3
evaluation per pair. This document therefore does not claim the quadratic is "exact for the stated arithmetic":
cancellation in `c0 = q0·q0 − 1` happens upstream of `Δ_tol`, so no tolerance on `Δ` can recover it. Interval arithmetic
is not required either, because the verdict no longer rests on the discriminant.

**Numeric policy.**

| Concern | Rule |
| --- | --- |
| Precision | `Double` throughout the test; the published pose is `Float`, so that quantisation is an input to the budget below, not a term the test can remove |
| Verdict | the finite-segment minimum predicate `min_t (d(t) − r(t)) ≥ e` — not a discriminant sign, not a root interval |
| Discriminant and roots | `Δ`, `t0`, `t1` are **diagnostics only**: they say whether the segment enters and where, which is what the failure report needs. They never produce CLEAR, and they never produce INTERSECT without a witness station |
| Witness band | `κ_c = 1e-12`, relative to `s(t_m) + 1` — an **allowance, not a bound**: it is not a valid forward-error bound for the coefficient formation over the permitted positive-axis domain, and the review script produces a false witness inside it (§7.4.1b). A station must beat it **and** carry a proved enclosure to be a witness; a pair with a station that beats only `κ_c` is `UNCERTAIN`, never `INTERSECT` |
| Fail-closed band | `abs(Δ) ≤ Δ_tol` → stage 3 (never CLEAR). `Δ < −Δ_tol` → stage 3 as well; it proves the segment cannot *enter*, not that it clears by `e` |
| Degenerate segment | `a = 0` (zero length): the segment is a point, and the point-to-ellipsoid distance decides; inside → `DEGENERATE_SEGMENT` |
| Non-finite intermediates | overflow, NaN or infinity in `cw`, `R`, `q0`, `qd`, `Δ` or `e` → `NON_FINITE_GEOMETRY`, not a verdict |
| Bad radius or margin | non-positive semi-axis → `DEGENERATE_OBSTACLE`; negative or non-finite `r`, `radiiMetres` or `eps_num` → `NON_FINITE_GEOMETRY`. Neither is clamped |
| Sign convention | `sign(0) = +1`, i.e. `sign(b) = +1` when `b ≥ 0`, else `−1`; without it a symmetric chord from the obstacle centre gives `b = 0`, `q = 0` and a division by zero in the diagnostic `t1 = c0/q` |

Ignoring `Rw` is not a rounding error, it is a wrong answer: for an obstacle with semi-axes (2, 1) rotated 90°, a short
world-space segment from `(−0.1, 1.5)` to `(0.1, 1.5)` lies inside the rotated obstacle (normalized squared length
0.5725) while the unrotated formula reports 2.2525 and calls it clear.

**(b-1) The point-to-ellipsoid routine, specified.** Stage 3 and the sample check both need `d(p)`, and it is the only
place a root solve enters the predicate, so its contract is stated rather than cited:

| Step | Rule |
| --- | --- |
| domain | `d(p)` is defined for a **positive** semi-axis triple and any finite `p`; the solver is the exterior solver and is only entered when `Σ (p_i/R_i)² > 1` |
| interior | `Σ (p_i/R_i)² ≤ 1` → `d = 0` exactly, no root solve (the solid distance of §7.4.1b) |
| scaling | **no scaling of the metric.** Non-uniform axis scaling does not preserve Euclidean distance and no single axis multiplier reverses it: for axes `(2, 1)`, the point `(3, 0)` has `d = 1` while its unit-sphere distance is `0.5` (an implied multiplier of 2), and `(0, 2)` has `d = 1` while its unit-sphere distance is also `1` (multiplier 1). The root and the surface point are therefore solved in the **original** coordinates by the two rows below; scaled coordinates are used only to bound the bracket's growth and to detect zero coordinates |
| root | bracket the unique non-negative root `λ* ≥ 0` of `Σ ( a_i x_i / (λ + a_i²) )² = 1`, monotone decreasing in `λ`, by bisection on a bracket grown from `λ = 0` until the sum is `< 1`; then bisect to `λ` with relative width `2⁻⁴⁰` |
| distance | `d = \|p − x(λ*)\|` with `x_i = p_i a_i² / (a_i² + λ*)`, in the original coordinates. The routine reports `η = \|Σ (x_i/a_i)² − 1\|` and `B_est = max(R) · η / 2`, from the radial gap `\|x\| · (1 − 1/√(1+η)) ≤ max(R) · η/2`. **`B_est` is a residual-based estimate, not a bound, and this revision stops calling it one.** Its stopping rule returns `λ` from an **uncertified bracket**, and `η` is computed from that same rounded `x`, so the error in the returned `d` exceeds `B_est` on a represented input: axes `(2, 1, 1)`, binary64 `p = (3.4796555007028274, 0.13810957790485368, 0)`, the original rule returns `d = 1.4844661191164141289533517920062877237796783447265625` against a 100-digit reference of `1.4844661191163856662673823209091660814690286931784…` — an absolute error of `2.8462685969471097e-14` against `B_est = 2.8421709430404007434844970703125e-14`, a ratio of **1.00144**. The `0.999158` an earlier revision quoted came from comparing decimal display strings rather than the returned binary64 value, and is withdrawn with the claim. `B ≤ 2⁻³⁶ · max(R)` is therefore **not** a proved contract; it is the target deliverable (4) below has to meet |
| zero coordinates | a zero coordinate contributes nothing to the sum and is never divided by; the guard is per component, not on `\|p\|` |
| three equal axes | the sphere case is solved directly (`d = \|p\| − R`) rather than through the root, because `λ` is then a difference of squares of nearly equal numbers. **Two** equal axes is not this case: with axes `(2, 1, 1)` and `p = (3, 1, 0)` the general routine returns **1.297184**, while a sphere formula returns `2.162278` at `R = 1` or `1.162278` at `R = 2` — both wrong, and wrong silently |
| non-positive axis | `a_i ≤ 0` → `DEGENERATE_OBSTACLE`, never a clamp and never a division |
| non-finite input, or no root found | `NON_FINITE_GEOMETRY` or `UNRESOLVED_ROOT` — a reportable reason, never `CLEAR` and never a silently truncated iteration |

Fixtures pin the routine directly: `pe_centre` (the centre → `d = 0`), `pe_principal_axis` (a point on each axis → the
analytic distance), `pe_repeated_axis` (two equal axes), `pe_tiny_axis` (a 0.5 mm axis against a 200 mm one — the
scaling test), `pe_interior` (`d = 0`), `pe_tangent` (`d = 0` at the surface) and `pe_near_boundary` (1e-6 units
outside, where the bracket must not lose the root).

The test is deterministic, allocation-free and **independent of sampling density**: it answers "how close does this
straight segment come to this ellipsoid" for any obstacle size, including one thinner than the sample spacing. The
failing segment, obstacle, entry/exit stations and the minimising `t` are all reported.

**Worked cases** (unit sphere, `q0`/`qd` in normalized coordinates, `κ = 1e-12`, `e` = the numeric margin; all values
computed in double precision):

| Case | `q0` | `qd` | `(a, b, c0)` | `Δ` | Verdict under this policy |
| --- | --- | --- | --- | --- | --- |
| Clear — line misses the ellipsoid | `(2, 0, 0)` | `(0, 1, 0)` | `(1, 0, 3)` | `−12` | CLEAR: minimum over `[0, 1]` is at `t = 0`, where `\|q0\| = 2`, so the surface distance is **1**, not 2 — the earlier value conflated `\|q0\|` with `\|q0\| + 1` |
| Clear — infinite line crosses, segment does not | `(2, 0, 0)` | `(1, 0, 0)` | `(1, 4, 3)` | `4` | CLEAR: roots `[−3, −1]` miss `[0, 1]`; minimum at `t = 0`, surface distance `√4 − 1 = 1` |
| Intersecting | `(−2, 0, 0)` | `(4, 0, 0)` | `(16, −16, 3)` | `64` | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`, `[0.25, 0.75] ∩ [0, 1] ≠ ∅` |
| Tangency on the segment | `(−2, 1, 0)` | `(4, 0, 0)` | `(16, −16, 4)` | `0` | `INVALID` — the tangency is at `t = 0.5`, so `min d = 0 < r`; the band sends it to stage 3 and stage 3 agrees |
| Tangency off the segment — **clear** | `(2, 1, 0)` | `(1, 0, 0)` | `(1, 4, 4)` | `0` | CLEAR: the tangency is at `t = −2`, outside `[0, 1]`; the minimum over `[0, 1]` is at `t = 0`, surface distance `√5 − 1 = 1.236`. **The earlier near-tangent branch rejected this segment**, contradicting its own interval rule |
| Clear control for the row above | `(3, 1, 0)` | `(1, 0, 0)` | `(1, 6, 9)` | `0` | CLEAR: tangency at `t = −3`; minimum surface distance `√10 − 1 = 2.162`. The pair exists so that a check which rejects every tangency cannot pass the set |
| Near-tangent, `Δ` lost to rounding | `(−2, 1+1e-13, 0)` | `(4, 0, 0)` | `(16, −16, 4)` | `−1.279e-11` (exact `−1.28e-11`) | `INVALID`: inside the band, routed to stage 3, where `min d ≈ 0 < r`. **`Δ` does not "round to −0"** — it is a small negative number, and the earlier text said otherwise |
| Symmetric chord from the centre — the `q = 0` trap | `(0, 0, 0)` | `(1, 0, 0)` | `(1, 0, −1)` | `4` | `INVALID` — `b = 0`; with `sign(0) = +1` the diagnostic `q = −1` and `[t0, t1] = [−1, 1]`; with the textbook `sign(0) = 0`, `t1 = c0/q` is non-finite |

The second clear row is what a discriminant-only verdict gets wrong: `Δ > 0` alone is not an intersection, the root
interval must meet `[0, 1]`. The tangency rows are what the earlier *near-tangent branch* got wrong in the other
direction: it treated every `abs(Δ) ≤ Δ_tol` as an intersection **without** the interval test that the same section
stated, so a segment whose tangency lies at `t = −2` was rejected while it was 1.236 radii clear.

**The two `INVALID` rows above are stated for the stage that must decide them.** They are intersections in fact, so the
verdict `INVALID` is right either way; the *reason* `SEGMENT_INTERSECTS_OBSTACLE` is reachable only with a proved
enclosure, so while deliverable (2) of §7.4.1c is open both pairs are **routed to stage 3, whose own result is
authoritative for that pair**: `CLEARANCE_VIOLATION` where its bracket decides (`d = 0` inside the solid, so
`g = −r < e`, reason row 8), `UNCERTIFIED_MINIMUM` where it cannot. The table is not wrong; it is conditional, and
§12.2.1 states the condition once for the fixture set that pins it.

**(c) `INVALID` — never assumed, and one authoritative reason table.** A path is accepted only when **every
segment–obstacle pair returns a CLEAR from whichever stage decided it** (stage 1 or stage 3 — a pair stage 1
accepted never reaches stage 3) **and** every sample test returns "outside". Anything else is `PathStatus.INVALID` with a reason from the
table below. **The table is the whole vocabulary**: a reason that is not in it is not reportable, and the *first matching
row in table order* decides, so two implementations that hit two conditions report the same one. `failingSegment` and
`failingObstacle` carry the location where the reason has one, and `NO_INDEX` where it does not — a consumer may never
read `NO_INDEX` as index 0.

| # | Reason | Condition | Segment | Obstacle |
| --- | --- | --- | --- | --- |
| 1 | `NON_FINITE_GEOMETRY` | any non-finite input, radius, margin or intermediate | `NO_INDEX` | `NO_INDEX` |
| 2 | `DEGENERATE_OBSTACLE` | a semi-axis that is zero, negative or non-finite | `NO_INDEX` | index |
| 3 | `REST_LENGTH_INVALID` | the reference-stance length is not finite and `> 0` (§7.4.4) | `NO_INDEX` | `NO_INDEX` |
| 4 | `BUFFER_CAPACITY` | inserted points exceed `pointCapacity` (§5.3) | last segment | `NO_INDEX` |
| 5 | `ENDPOINT_INSIDE_OBSTACLE` | a resolved attachment is inside a declared obstacle in this pose | 0 or last | index |
| 6 | `DEGENERATE_SEGMENT` | a zero-length segment whose **point is inside** an obstacle (§7.4.1b: `a = 0` is decided before the quadratic, because the quadratic divides by `a`) | index | index |
| 7 | `SEGMENT_INTERSECTS_OBSTACLE` | stage 2: the segment enters the ellipsoid — a **witness station** `t_m` with `s(t_m) ≤ 1 − κ_s`, a root interval whose overlap with `[0, 1]` contains it, **and a proved enclosure for `κ_s` on this input** (§7.4.1b). An interval that merely touches `[0, 1]` at an endpoint is not this row, and neither is a station that beats an unproved `κ_c` | index | index |
| 8 | `CLEARANCE_VIOLATION` | **decided at stage 3** and `min (d − r) < e`. The row is defined by *which stage decided* and by `g < e` — **not** by the claim that the segment misses the surface. The witness rule routes an unproved crossing to stage 3, where `d = 0` and `g = −r < e`, so a decided clearance failure may well be a true interior path: for a unit obstacle, centre `(1.0000000036…e−15, 0, 0)` and `Float` endpoints `(1, v, 0) → (1, 2v, 0)` with `v = 9.999999717180685…e−10`, both endpoints are strictly inside the exact solid, the rounded witness `s = 0.9999999999999980` does not beat `κ_s`, and the row that reports the failure is this one. An unproved intersection is this row; a proved near miss is also this row; the only thing that is not this row is a **witnessed** entry (row 7) | index | index |
| 9 | `TANGENT_DISCONTINUITY` | the entry/exit kink exceeds `θ_max` (§7.4.3) | index | index |
| 10 | `CURVATURE_VIOLATION` | an interior turn exceeds `θ_max` at the declared spacing (§7.4.3) | index | `NO_INDEX` |
| 11 | `NO_GEOMETRIC_CAUSE` | the **search** stopped before any per-pair test produced a cause — the corridor was refused before the first candidate, or the pass budget ran out with no pair tested. **It excludes every guard and resource reason**: a definition or input refusal is row 1, 2, 3, 15 or 17 under `REJECTED_BEFORE_SEARCH`, and a buffer cap — including one reached before any pair test — is row 4, `BUFFER_CAPACITY`, under `REJECTED_IN_SEARCH`. The table's precedence already ranks `BUFFER_CAPACITY` above this row; the prose used to contradict it by listing "the buffer cap was hit" here. The sentinel says "no geometric cause exists", never "a cause exists but the record has no slot for it" (§7.4.1c, X7) | `NO_INDEX` | `NO_INDEX` |
| 12 | `UNCERTIFIED_MINIMUM` | stage 3's certificate could not decide either side (`lower < e ≤ upper`) | index | index |
| 13 | `UNRESOLVED_ROOT` | the point-to-ellipsoid bracket found no root for a finite, positive-axis input | index | index |
| 14 | `NO_ROUTE` | **proved** impossible: no route exists between the fixed endpoints outside the declared set. **Disabled in M1** — see below | `NO_INDEX` | `NO_INDEX` |
| 15 | `UNSUPPORTED_GEOMETRY` | the muscle is a **sheet** (`flat = true`): a two-sided strip has no single radius, so no tube envelope exists to certify (§7.4.1 "flat sheet muscles") | `NO_INDEX` | `NO_INDEX` |
| 16 | `NOT_PROCESSED` | the solve never reached this head — the `clear()` state, never a verdict (§5.3) | `NO_INDEX` | `NO_INDEX` |
| 17 | `OPERAND_OUT_OF_BOUND` | a coordinate outside the declared `\|coordinate\| < 1024` bound (§7.4.1c deliverable 1). **Two phases, two outcomes, and the phase is part of the row**: a **published** coordinate outside the bound is a guard on the inputs and reports `REJECTED_BEFORE_SEARCH` with `NO_INDEX`/`NO_INDEX`; a **constructed** operand — a detour waypoint, or an escalated `d_k`, formed inside a pass — is discovered after the search started and reports `REJECTED_IN_SEARCH` with the index of the segment being constructed and obstacle `NO_INDEX`. A detour cannot be refused "before the search" when it exists only because a pass made it, and corridor escalation is exactly the future guard that produces this case | construction segment / `NO_INDEX` | `NO_INDEX` |
| — | `NO_REASON` | only where the status is `VALID` | `NO_INDEX` | `NO_INDEX` |

**The table's numbers are precedence, and the enum's order is not.** The row number is the *precedence* rank — the first
matching row wins — while the array stores the enum's own ordinal, so the two orders are declared separately and neither
is inferred from the other:

```kotlin
/** Declaration order is the stored ordinal; the numbered table above is the precedence order. NOT_PROCESSED is zero. */
enum class PathReason {
    NOT_PROCESSED,       // 0 — the clear() state and a fresh IntArray; never a verdict
    NO_REASON,           // 1 — only where PathStatus is VALID
    NON_FINITE_GEOMETRY, DEGENERATE_OBSTACLE, REST_LENGTH_INVALID, BUFFER_CAPACITY, ENDPOINT_INSIDE_OBSTACLE,
    DEGENERATE_SEGMENT, SEGMENT_INTERSECTS_OBSTACLE, CLEARANCE_VIOLATION, TANGENT_DISCONTINUITY,
    CURVATURE_VIOLATION, NO_GEOMETRIC_CAUSE, UNCERTIFIED_MINIMUM, UNRESOLVED_ROOT, NO_ROUTE,
    UNSUPPORTED_GEOMETRY, OPERAND_OUT_OF_BOUND
}
```

`NOT_PROCESSED` is the declared zero value, which is what makes a zero-initialised `reasonCodes` correct before
`clear()` runs; the numbered table is not that declaration and an implementation that used its row order as the stored
ordinal would read the wrong reason for every value. `OPERAND_OUT_OF_BOUND` is row 17 because rows 1–16 are cited by
number elsewhere in this document and inserting a row above them would silently repoint those citations; it is
nonetheless evaluated in the guard phase with rows 1–3, before any geometry runs.

**Search outcomes are not in this table, and that is the point of the split.** `SEARCH_EXHAUSTED` and `PASS_CAP` are
`SearchOutcome` values — what the loop did — and never reasons: a corridor refused at pass one has no final-predicate
cause at all, and listing the refusal as a reason would make "the search stopped" and "this segment is 0.3 mm too close"
the same column. **When the loop stopped before any per-pair test produced a cause, and no guard or resource reason
applies, the reason is `NO_GEOMETRIC_CAUSE`** — a statement that none exists rather than a cause. A guard's refusal
carries its own reason (rows 1, 2, 3, 15, 17 on a published operand) and a buffer cap reached before any pair test is
row 4, `BUFFER_CAPACITY`; neither is routed here, which is what the qualification in row 11's condition says.

**Guards run before geometry, and each phase has its own order.** A non-finite operand cannot be classified by any
geometric test, so rows 1 and 2 are evaluated **before** the state machine runs at all; a zero-length segment is decided
by a point test **before** the quadratic, because `a = 0` makes its roots a division by zero; and an intersecting segment
is reported as an intersection **before** any clearance reason, because a crossing is a stronger fact than a near miss.
A segment that is both degenerate and inside an obstacle reports `DEGENERATE_SEGMENT`, not
`SEGMENT_INTERSECTS_OBSTACLE` — the earlier table ordered them the other way, which made a zero-length path through an
obstacle report the stage-2 reason its own guard had already ruled out. A sheet muscle reports `UNSUPPORTED_GEOMETRY`
before any clearance test is attempted, rather than being certified by a predicate that does not describe it.

There is no "probably fine" outcome and no fall-through default. If the check cannot be completed, the path is invalid.

**X7 — `NO_ROUTE` is disabled until a proof procedure exists, and the two kinds of outcome are separate fields.**
*Confirmed by owner.* A reason is not a search outcome and a search outcome is not a geometric cause. So:

- the **frame** carries it: `AnatomyFrame.searchOutcomes` (§5.3) holds the outcome per head ∈ {`NOT_PROCESSED`, `CLEARED`,
  `SEARCH_EXHAUSTED`, `PASS_CAP`, `REJECTED_BEFORE_SEARCH`, `REJECTED_IN_SEARCH`} — what the construction did — while
  `reasonCodes` holds, separately, the **last geometric cause** it hit. Both are per-head arrays with a `clear()` state, so a
  reused buffer cannot report the previous frame's outcome, and `NOT_PROCESSED` means the head was never solved (§5.3).
  **The set is exhaustive and matches `SearchOutcome` one-for-one.** An earlier revision listed five of the six values here
  while the enum (§5.3) and the combination matrix below both carried `REJECTED_IN_SEARCH`, so a reader could not tell
  whether the sixth value was legal at all; it is, it is the one an abort inside a pass gets, and it is in the set.
  **`REJECTED_BEFORE_SEARCH` is the outcome a guard's refusal gets, and `REJECTED_IN_SEARCH` is the one an abort inside
  a pass gets** — the pair an earlier revision collapsed into one. A head refused by `REST_LENGTH_INVALID` before a pass
  existed has not passed and has not exhausted a search; a head whose pass ran out of buffer capacity, or whose
  intermediate overflowed, *was* searched and stopped, so reporting it as "before the search" is false about a different
  event — and it sends the fix to the input rather than to the buffer. `BUFFER_CAPACITY` in particular is decided
  **while a pass is constructing** (§7.4 step 4), and step 4's own text says insertion can exceed capacity, so it cannot
  be a pre-search refusal.
- **Which combinations are legal, stated once.** Everything outside this table is a bug in the producer, and a consumer
  may assert on it:

  | status | outcome | reason |
  | --- | --- | --- |
  | `VALID` | `CLEARED` | `NO_REASON` — an accepted pass, and the only place `NO_REASON` appears |
  | `INVALID` | `SEARCH_EXHAUSTED` | the last geometric cause, or `NO_GEOMETRIC_CAUSE` when the corridor was refused before any candidate existed |
  | `INVALID` | `PASS_CAP` | the last geometric cause (`CLEARANCE_VIOLATION` typically), or `NO_GEOMETRIC_CAUSE` |
  | `INVALID` | `REJECTED_BEFORE_SEARCH` | a **guard on the inputs or the definition**, refused before any pass: `NON_FINITE_GEOMETRY` on a published input, `OPERAND_OUT_OF_BOUND` on a **published** coordinate, `DEGENERATE_OBSTACLE`, `REST_LENGTH_INVALID`, `UNSUPPORTED_GEOMETRY` — indices `NO_INDEX`/`NO_INDEX` except a degenerate obstacle's own index |
  | `INVALID` | `REJECTED_IN_SEARCH` | an **abort inside a pass**, which is not a pre-search refusal: `BUFFER_CAPACITY` (inserted points over `pointCapacity`, reported at the last segment, obstacle `NO_INDEX`), `NON_FINITE_GEOMETRY` first appearing in an intermediate (`cw`, `R`, `q0`, `qd`), or `OPERAND_OUT_OF_BOUND` on a **constructed** operand (the segment being constructed, obstacle `NO_INDEX`); otherwise `NO_INDEX`/`NO_INDEX` |
  | `NOT_PROCESSED` | `NOT_PROCESSED` | `NOT_PROCESSED` — never solved |

  The two illegal shapes worth naming because they are the ones an implementation drifts into: `VALID` with any outcome
  but `CLEARED`, and `INVALID` with `CLEARED`. Both are rejected by `AnatomyFrameLayoutTest` (§12.2).
- **`CLEARED` is success and nothing else.** A head is `CLEARED` when a pass was accepted, which implies `status =
  VALID` and `reason = NO_REASON`; an exhausted search reports `PASS_CAP` or `SEARCH_EXHAUSTED` **with** the last
  geometric cause (`CLEARANCE_VIOLATION` at a named segment, typically) — two fields, one record. An earlier revision
  called `CLEARED` with a geometric cause a normal state, which left the outcome field unable to separate "the search
  stopped" from "the path was accepted" and made the two-field fixture below unreachable. A consumer that reads the
  cause as the outcome reports "geometry violation" for a search that simply stopped, and a consumer that reads the
  outcome as the cause reports "exhausted" for a path that had one bad segment.
- `NO_ROUTE` is **not produced in M1**: nothing in this design proves that no route exists between two fixed endpoints
  outside a set of ellipsoids, so a `NO_ROUTE` value would be a claim with no procedure behind it. The enum keeps the
  value, the reason table keeps the row, and the frame never emits it until a proof procedure is specified and reviewed.
  `SEARCH_EXHAUSTED` and `PASS_CAP` are the honest names for "the search gave up".
- **Corridor exhaustion is `SEARCH_EXHAUSTED`**, never `NO_ROUTE` — including when the corridor is refused at pass 1,
  which is a limit on the declared `corridorMetres`, not a theorem about the geometry.
- **Precedence is phase-specific, not one global order, and the phases do not overlap.** Definition- and guard-time
  reasons (1 on a published input, 2, 3, 15 and 17 on a published operand) are decided before a path exists, so the outcome
  is `REJECTED_BEFORE_SEARCH` and no geometric test is reported because none ran; capacity (4), row 17 on a constructed
  operand, and row 1 when the non-finite value first appears in an intermediate rather than in an input — is decided
  **inside** a pass, so it reports `REJECTED_IN_SEARCH` with its own reason and indices; a corridor refusal or an exhausted
  budget is decided by the loop and reports `SEARCH_EXHAUSTED` or `PASS_CAP` with `NO_GEOMETRIC_CAUSE` unless a per-pair
  test had already produced a cause; and per-pair reasons (5–10, 12, 13) are decided by the final predicate in table order,
  reporting the loop's own outcome. Two implementations that hit a definition error and a geometric one therefore report the
  definition-time row under the same outcome instead of depending on which check ran first — and a fixture that expects
  `PASS_CAP` **with** `CLEARANCE_VIOLATION` is now consistent with the table rather than an exception to it, because the
  pass cap is an outcome and the clearance is a reason. **Row 17 is the one reason that appears in both phases**, which is
  why its row names the outcome rather than leaving it to the reader: the same reason code with a different phase is a
  different record, and `NO_GEOMETRIC_CAUSE` is never the outcome of either.
- Fixture `final_clearance_and_budget_both_fail` pins the combination: a final polyline that fails the predicate at a
  named segment **and** exhausts the pass budget reports `searchOutcome = PASS_CAP` with `reason =
  CLEARANCE_VIOLATION` at that segment and obstacle — one record, two fields, neither overwriting the other.

**The numeric margin is derived, and the claim it supports is one-sided, conditional, and one of its terms is not yet a
bound.** `eps_num = 1e-5 m`, and the claim is **"the lower bound on the clearance, when every term of the budget is a
bound, is ≥ `eps_num` **minus** that budget and the representation term"** — not "the clearance is ≥ `eps_num`". An
estimate `g ≥ e` carrying error `B` proves only `g ≥ e − B`; a document that quotes the estimate as if it were the
guarantee is off by `B` in the optimistic direction, which is the one direction that matters here. The budget below is a
sum of terms for the *declared* inputs, **two of which are estimates rather than bounds** (§7.4.1b-1's point-to-ellipsoid
error and, through it, `B` itself), so the floor is stated conditionally on conditions that are enforceable checks, not an
argument — and not all of them hold yet.

*Rounding model:* IEEE-754 binary32 for every published input and binary64 for the evaluation, round-to-nearest — at most
**half** an ulp per operation — with operands bounded by `|coordinate| < 1024` engine units. The bound is a
power-of-two boundary chosen to cover every operand this document acknowledges, including the posed figures near 600 that
an earlier revision named in prose while bounding the budget at 500. In `[512, 1024)` one ulp is `2⁻¹⁴ = 6.1035e-5`
units, so one rounding costs at most `2⁻¹⁵ = 3.05e-5` units; at 500 one ulp is `2⁻¹⁵` and half an ulp `1.53e-5`, which is
why the old table's "3.05e-5 units = half an ulp at 500" was a factor of two off even where the number was right.

| Term | Value | How it was obtained |
| --- | --- | --- |
| published-coordinate quantisation | 3.0518e-5 units = 1.234e-7 m | half an ulp of a binary32 for `\|x\| < 1024`, per published coordinate of a point |
| transform accumulation (attachment resolve) | 33 nodes × 2 roundings = **66** × 2⁻¹⁵ units | additive along the chain with no amplification, because every transform in it is orthonormal: **2.014e-3 units = 8.142e-6 m** |
| radius knots | ≤ half an ulp of `r` = **1.234e-7 m** | authored constants, not sampled; the affine radius between knots is one rounding and is inside the operation count |
| point-to-ellipsoid root solve | **unproved estimate** — `2.9e-12 m` for a 200 mm axis | §7.4.1b-1 retracts `B ≤ 2⁻³⁶ · max(R)`: the stopping rule's bracket is uncertified and the residual is rounded, so this row is arithmetic on a target, not a bound. Deliverable (4) below must supply the bound before the term can be summed |
| minimisation | `L · w_n / 2` | the Lipschitz bracket of §7.4.1b — proved for an exact `g`, so it is a term of the budget and not a second estimate |
| `Float` rounding of the published path | included above | the polyline is read as published, so its quantisation is an input, not a term the solver can remove |
| **`B` — the declared budget *estimate*** | **8.27e-6 m** displayed; `8.2657214…e-6 m` unrounded | the sum of the three terms *as estimates*: 8.1423…e-6 + 1.2336893…e-7 + an unproved root term of 2.9e-12. One term is an estimate, so `B` is an estimate; the arithmetic below uses the unrounded sum and says which figure it used |
| **`eps_num`** | **1e-5 m** = 2.4737e-3 units | **1.21 × `B` on the estimate.** Arithmetic, not a margin claim: until (4) makes `B` a bound, this ratio says what the margin *would* cover, not what it covers |

**The margin factor is arithmetic on estimates, and the number it leaves is an illustration under named conditions — not
a claim, and not a floor this document asserts.** `eps_num / B = 1.21` *on the estimate*; subtracting the budget estimate
and the drawn-vertex term leaves `eps_num − B − q_vertex,3D = **1.5206e−6 m**` — not `1e−5 m`, and not `1.61e−6 m` either,
which was this paragraph's figure while the vertex term was a single coordinate's half-ulp. Reproducible as
`66 · 2⁻¹⁵ · (0.95/235) = 8.1423495…e−6`, plus `2⁻¹⁵ · (0.95/235) = 1.2336893…e−7` for the radius and an unproved
`2.9e−12` for the root solve — giving `B = 8.2657214…e−6` — less `√3 · 2⁻¹⁵ · (0.95/235) = 2.1368126e−7` under the old
vertex term, or `√3 · 2⁻¹⁴ · (0.95/235) = 4.2736252e−7` under the corrected one (the 1024-binade case below), which gives
**1.3069e−6 m**.

**Three things about that subtraction are stated so nobody reads it as a bound.** (i) The root term is a target, so `B` is
an estimate. (ii) The vertex term bounds the *representation* of a vertex, over output coordinates inside a declared
binade, not the tube the renderer builds. (iii) `1.5206e−6` uses the **unrounded** sum: the `8.27e−6` displayed in the
table gives `1e−5 − 8.27e−6 − 2.1368e−7 = **1.51632e−6**`, and quoting either figure as *the* floor would be quoting a
rounding difference as the arithmetic. The sensitivity is the point: **raise the budget term by `2e−6 m` and the remainder
is `−4.7940e−7 m`** — the number is positive only because this estimate is small, and a future valid bound larger by that
amount turns it negative. That is why condition (d) is open and why the document claims no numerical clearance floor:
`eps_num` is 1.21× an estimate rather than 1.21× a proved budget, and `1.5206e−6` is the arithmetic of that distinction
rather than a margin. The earlier revision reported `1.2×` and `2.4×` as two alternative factors for the same budget — the
arithmetic signature of counting one ulp and half an ulp against the same geometry — and said the floor "follows once (d)
is supplied", which was false: (a)–(c) are conditions too, and supplying *some* bound for (d) does not establish that the
bound is at most `8.265721…e−6`.

**The drawn tube is not automatically inside the object the predicate accepts, and the inscribed-polygon argument does
not make it so.**
The obvious argument is that the drawn surface is the modelled tube swept by the *inscribed* ring polygon, which sits inside
the circle by `r(1 − cos(π/8)) = 0.076 r` — 1.52 mm at a 20 mm radius. **That inset is a sagitta at an edge midpoint, not a
vertex margin**: the vertices of an inscribed polygon lie *on* the circle, so their inset is exactly zero and the only
thing between a vertex and the accepted surface is its representation. The renderer writes `Float` vertices, each
**coordinate** rounded by up to half an ulp — `3.0518e-5` units, `1.2337e-7 m` at `|x| < 1024` — in either direction, so a
drawn vertex can sit outside the accepted tube, and the amount is a **vector** rather than that scalar: three coordinates
each carry their own half-ulp error, and the vertex moves by their Euclidean combination.

**A half-ulp is per coordinate, and the coordinate's own binade decides which half-ulp.** Centre `(512, 512, 512)`, binary32
radius `5.999999848427251e-5`, radial direction `(1,1,1)/√3`: each ideal coordinate `512.0000346410153` rounds to
`512.0000610351562`, so the rounded vertex sits `4.571599318863259e-5` units outside the exact circle — `1.8481e-7 m`,
which is **1.50 × the scalar `1.2337e-7 m` term** and is therefore not covered by it. A tube tangent perpendicular to that
radial direction admits the example; it is a geometric probe of the rounding, not an observed renderer failure. For
comparison, the example an earlier revision quoted — centre `512`, radius `9.999999747378752e-5` (the binary32 value of
`1e-4`), the unrounded vertex `512.0000999999975` rounding to `512.0001220703125` — is `2.207e-5` units (`8.92e-8 m`, 0.36
of an ulp) outside, and it is covered by the scalar term **only because its radial direction is a single axis**.

The round-trip interval is the right object: an ideal coordinate lies within **half an ulp of the coordinate it rounded
to**, `[x_out − ulp(x_out)/2, x_out + ulp(x_out)/2]`, each of the three contributes independently, and therefore
`‖e‖ ≤ √3 · max_i ulp(x_i^out)/2`. **The binade is the output's, not the input's, and one crossing doubles the term** —
which is exactly what the operand guard does *not* bound. Take binary32 centre coordinates all exactly
`1023.99993896484375`, binary32 radius exactly `0.000230000005103647708892822265625` and radial direction `(1,1,1)/√3`:
every input coordinate and the radius are below 1024, but the ideal vertex coordinate is `1024.0000717554086102063…`,
which rounds to binary32 **`1024.0001220703125`** — binade `[1024, 2048)`, half-ulp `2⁻¹⁴`. The radial excess is
`8.7147969915e−5` units, **`3.5230030e−7 m`**: it **exceeds** `√3 · 2⁻¹⁵` (`2.1368126e−7 m`) by **1.649×** and is covered by
`√3 · 2⁻¹⁴` (`4.2736252e−7 m`) with **1.213×** headroom. The earlier `√3 · q` form was right *when the output coordinate
stayed in the lower binade* — the diagonal `512` example is that case — and wrong at the boundary, because a guard on the
**predicate operands** says nothing about the coordinate a generated vertex rounds to.

The vertex term is therefore `q_vertex,3D = √3 · ½ulp(x_out)` evaluated **per vertex from its own output coordinates**,
and the global form `√3 · 2⁻¹⁴ = 4.2736e−7 m` is quoted **only** under the extra condition that every generated output
coordinate is below 2048 engine units. That condition follows from `|coordinate| < 1024` plus a semi-axis below 1024
units — true for every M1 obstacle, since a 200 mm axis is 49.5 units, but stated rather than assumed because it is
exactly the kind of "obvious" step that the 1024 case above shows is not implied by the operand guard.

The vertex term is therefore **carried, not argued away**: it is not in `B` (which estimates the *published inputs*, not the
drawn output), and the conditional floor below is quoted with it subtracted. It bounds the **representation** of a vertex
the renderer is handed; it is not a bound on the tube the renderer builds from it. The term that remains outside the budget
entirely is unchanged: rig registration (§11).

| Term *not in* `eps_num` | Value | Where it comes from |
| --- | --- | --- |
| rig registration | up to **8.73 mm** at a 1 m lever | 0.5° of `τ_rot` (§6.6 A1) — a different error, outside this guarantee |

`e = eps_num · s` is computed once, in metres first and converted once with `s = unitsPerMetre` (§7.2). A margin applied
as if it were already in engine units is smaller by a factor of `s` (≈ 247), so a path that sits inside the margin would
be reported clear; `non_unit_scale` in §12.2.1 is the fixture that catches exactly that error, and it is paired with a
control that must stay CLEAR.

**The clearance floor is conditional, and it fails closed when its conditions do not hold.** The table above is an
arithmetic sum of terms, not a proof over every pose the solver accepts, and a sum whose inputs are unproved bounds is not
a certificate. The guarantee is therefore stated with its conditions attached:

> *When, and only when* — (a) every operand of the predicate is inside the declared bound `|coordinate| < 1024` engine
> units; (b) the path polyline and the obstacle are each covered by a one-sided error analysis of the operations that
> produced them, not only by the input-quantisation terms; (c) the minimiser's comparisons are bounded by the bracket of
> §7.4.1b rather than by a converged-looking number; and (d) the point-to-ellipsoid distance carries a **bound** rather
> than the residual estimate §7.4.1b-1 reports today, over the represented inputs the predicate actually sees — *then* the
> generated modelled tube (the final validated polyline swept by the declared piecewise-affine radius, with its end caps
> and joins) stays at least `eps_num − B − q_vertex,3D` from the surface of every declared model obstacle that applies to
> that head, except on the exemption intervals of X5. In every other case the pair is `UNCERTAIN` →
> `UNCERTIFIED_MINIMUM`, and **no floor is claimed for it**. Condition (d) is not met by this document.

**One class of pair is outside that sentence, and it is the class that was proved independently.** A pair cleared by
stage 1 is not "every other case": it never consults the point-to-ellipsoid distance, so (d) is not a precondition of its
`CLEAR` and it is not `UNCERTAIN`. The reconciliation is stated once here because the two rules used to read as a
contradiction — §7.4.1b says a stage-1 clear stops, and this paragraph used to say every pair lacking (d) fails closed —
and a reader could not tell which applied to a pair that both describes.

With `B = 8.2657214…e−6 m` (an estimate), `eps_num = 1e−5 m` and the drawn-vertex term under its corrected,
binade-aware form `q_vertex,3D = 4.2736e−7 m` (a bound on the representation, not on the rendered tube), the illustrative
remainder is **1.3069e−6 m**; under the old `√3 · 2⁻¹⁵` term it is `1.5206e−6 m`, and under the displayed `8.27e−6` it is
`1.51632e−6 m`. **None of the three is claimed as a clearance floor.** The document claims no numerical floor today,
because (d) is open and because a floor is only as good as the weakest term in the subtraction; what it claims is that
(a)–(d) are the conditions under which a positive remainder of this size would follow, that a differently-sized bound for
(d) moves the remainder — `+2e−6 m` makes it `−4.7940e−7 m` — and that no pair may pass as clear until the comparison that
decided it is a bound. That is the whole claim: a conditional arithmetic statement, in engine space, about a surrogate
shape and a surrogate obstacle set. It is **not** a statement about real bone, fascia or skin, and **not** a statement
about the swept volume between two frames (§7.4.3).

**What has to become true before the floor is unconditional — an implementation-stage deliverable, not this document's.**
Five items, each with the bound it needs, and no attempt at a forward-error proof here:

1. **An operand bound for every accepted pose and detour.** `|coordinate| < 1024` is asserted for the poses this document
   acknowledges; a pose builder that leaves it must be rejected at L3 with `NON_FINITE_GEOMETRY` or
   `OPERAND_OUT_OF_BOUND` under `REJECTED_BEFORE_SEARCH`, and a corridor escalation that leaves it must be rejected inside
   the pass with `OPERAND_OUT_OF_BOUND` under `REJECTED_IN_SEARCH` (§7.4.1c row 17) — never silently evaluated. Until that
   check exists the bound is a claim about the fixtures, not about the system.
2. **A per-operation bound for both moving geometries, including the coefficient formation.** The budget covers input
   quantisation and the transform chain, and the obstacle is evaluated in its own frame — but the analysis of the *relative*
   motion of path and obstacle (two independently rounded chains) is not written down anywhere in this document, and
   neither is the forward error of `q0`, `qd` and `s(t)` beyond the allowance `κ_c`. Until both are written, the sum is
   one-sided in name only and stage 2's witness has no enclosure to rest on (§7.4.1b), which is why a witnessed entry is
   `UNCERTAIN` today.
3. **Certified comparisons inside the minimiser.** Golden-section discards a bracket half on every iteration on the basis
   of two floating-point values; retaining the minimiser across a near-tie needs the comparison to be resolved with a
   bound, or the certificate's `min over the n evaluated points` can be the second-best point. The bracket width term
   `L · w_n / 2` covers the *interval*, not a wrong comparison.
4. **A bound on the point-to-ellipsoid distance for represented inputs — the open one.** What §7.4.1b-1 reports is a
   residual estimate, and it exceeds itself on a represented input (the `1.00144` case above). The bound needs, together:
   a **certified bracket** on `λ` whose width enters the error term rather than being assumed away by the `2⁻⁴⁰` stopping
   rule; a **directed-rounding** evaluation of both `d` and the residual, so `η` is not the same rounded quantity that
   hides the error; and an interior branch that uses `1 − η` rather than `1 + η`, because the returned point is inside the
   surface for an interior root. Until an implementation supplies that, `B` is an estimate, the root term in the budget
   table is a target, and no numerical clearance floor is claimed. The failure mode in the meantime is
   `UNCERTIFIED_MINIMUM`, which blocks L4 rather than clearing a path.
5. **The final representation.** A bound on the **vertex as a vector** from its own **output** binade:
   `q_vertex,3D = √3 · ½ulp(x_out)`, or the global `√3 · 2⁻¹⁴` only while every generated output coordinate is below 2048
   engine units. Each of the three coordinates carries its own half-ulp, the half-ulp is the output coordinate's (a value
   that crosses 1024 doubles it — `3.5230030e−7 m` on the boundary case above), and the projected polyline is consumed as
   published, so no downstream consumer may re-smooth it (§7.4.1, "what it does not cover").

With (1)–(5) in place the floor can be stated unconditionally; with the current text it is stated conditionally on (a)–(d)
and (d) is open, so the floor is a derived number with a named gap rather than a guarantee.

**Joins, caps and morphs are inside or outside the accepted object, explicitly.** Two consecutive frusta share the disc
at their common knot and `r` is single-valued there, so the union has no gap and a join is inside the tube. A flat end cap
is the disc at `t = 0` or `t = 1`, so it is inside the tube too. **Morphs are outside it**: a body or muscle morph moves
vertices, and no M1 muscle layer carries one (§6.3), so a morphed muscle re-opens the guarantee rather than inheriting it.
**Flat sheet muscles are outside it**: a sheet's rendered geometry is a two-sided strip, not a swept tube, and a strip has
no single radius, so this criterion cannot certify one. A sheet muscle is therefore clearance-`INVALID` by default until a
sheet envelope is specified (a declared half-thickness and a per-station width, with its own distance predicate), and M1
ships no sheet muscle (§15).

**X5 — an attachment-contact exemption is a single reviewed transition envelope.** *Confirmed by owner.* An origin or
insertion may legitimately sit inside an obstacle — a hip-flexor origin on the pelvis is the normal case, not a defect —
so the exemption is declared, not inferred:
`ContactExemption(obstacleId, headIndex, fromStation, toStation, source)`, with fractional arc-length stations. The pair
`(head, obstacle)` gets **one** envelope, the one that covers the exempted endpoint and ends where the path first carries
the ordinary margin from that obstacle; **any drop below the margin after that is a violation**, not a second exempted
interval. A second interval is not a narrower exemption, it is a different claim (that the muscle is inside the obstacle
twice, with nothing said about the route between), and it is what an open-ended exemption degenerates into.

**The envelope ends on clearance, not on the surface, and the earlier "first surface egress" rule could not be satisfied.**
A path that leaves a surface exits at `d = 0`, so the stations immediately after egress have `d → 0`, and the non-exempt
check demands `g = d − r ≥ e` from every one of them: with a zero tube radius and `e = 0.01 mm`, a station `0.005 mm`
outside fails, and the positive fixture could never be `VALID`. The reviewed rule is therefore the **transition
envelope**, which ends where the path first *passes* — by the declared `σ`, so the station it ends on is not one the
bracket could refuse:

```
bound    = toStation    for an origin exemption (fromStation = 0)
         = fromStation  for an insertion exemption (toStation = 1)     // the end on the EXEMPTED side, never the far end
scan     = outward from the exempted endpoint toward the far end
effectiveTo = the FIRST station reached by that scan at which  d(t) − r(t) ≥ e + σ
            = bound, if no such station exists before it         // narrower, fail-closed, and the divergence is reported
exempt(t)   = t between the exempted endpoint and effectiveTo, inclusive — and nothing beyond it
σ           = ContactExemption.envelopeSlackMetres (§7.3): the exemption's declared envelope width in metres,
              authored and reviewed per (head, obstacle) and 0 by default. It is NOT a clearance claim: a station
              inside [e, e + σ) is exempt rather than certified, and a station at e + σ is the first non-exempt one
```

`d(t)` and `r(t)` are the solid distance and the modelled radius, which do not depend on the mask, so the boundary is
computed from geometry alone and the mask cannot depend on its own result. **The envelope is a mask, not a certificate.**
It says which stations are exempt; it does not say the rest are clear, and it cannot. Two facts have to be stated
together, because the earlier text stated only the first and drew the wrong conclusion from it:

- **A station found at `d − r ≥ e` satisfies the inequality at that station only.** Every station after it is non-exempt
  and must be **checked** by the ordinary stage-3 bracket, whose `lower(n)` must itself be `≥ e`. The envelope cannot
  make a later station clear, and a path that reaches the margin, dips back inside it, and stays outside the solid is a
  `CLEARANCE_VIOLATION` with the ordinary reason.
- **A boundary at exact equality cannot be certified at all.** Unit sphere, zero tube radius, path `x: 0.5 → 1.5`,
  illustrative exactly representable margin `e = 0.0078125`: with `σ = 0` the first station with `d − r ≥ e` is
  `x = 1.0078125` (`t = 0.5078125`), so the clipped non-exempt piece has its minimum **at** `e`. Stage 1 cannot fire (`|c − cw| − h = 0.5`
  is not `> 1 + 0 + e`), and the specified 30-step bracket over the clipped piece returns `lower = 0.00781236772694730`
  against `upper = 0.0078125` — **`UNCERTAIN`**; `n = 40` is still `UNCERTAIN` (`lower = 0.00781249892…`), because
  subtracting a positive bracket remainder from an endpoint minimum exactly equal to `e` cannot prove `lower ≥ e`. The
  verdict is `UNCERTIFIED_MINIMUM`, which is the fail-closed answer and the correct one.

**So a positive fixture needs a boundary that is provably above the margin, and the boundary rule supplies one by ending the
envelope at `e + σ`, never at `e`.** No slack is claimed *at* the first-margin station: that station has `g = e` by
definition, the clipped piece has its minimum there, and the bracket subtracts `L·w_n/2 + B_eval` from that minimum, so
`lower(n) < e` however many iterations run. That case is `exempt_boundary_equality` and its verdict is `UNCERTAIN`. What
makes the two positive fixtures satisfiable is that their envelopes **end past** the first-margin station, at the first
station whose `g` reaches `e + σ`: there `g(boundary) ≥ e + σ`, and `σ ≥ L·w_n/2 + B_eval` is what turns that into
`lower(n) ≥ e`. For the pinned geometry above `L·w_30/2 = 1.322731e−7 m`, so the fixtures declare `σ = 1e−6 m` — 7.6× the
pinned half-width alone, 3.9× once the `1.2337e−7 m` radius-rounding term inside `B_eval` is added — and the same path with
`σ = 0` is the
equality fixture, not a second positive one.

**No analytic equality case is claimed**, and that is the conservative branch of the choice. An equality case would need a
separately proved `lower(n) = e` for a named geometry; this document has none, so equality at the boundary is `UNCERTAIN`
and fails closed. The alternative — clipping at equality and calling the result `VALID` — is exactly what the bracket
above refuses, and a fixture written that way could only pass an implementation whose bracket is not a bound.

**The scan direction and the clamp are both decided by which end is exempted.** An origin exemption is clamped by
`toStation` and scanned upward from station 0; an insertion exemption is clamped by `fromStation` and scanned **downward
from station 1**. An earlier revision called `toStation` "the reviewed bound" in both cases, which for an insertion
interval `[fromStation, 1]` clamps at the *far* end and exempts stations below the authored lower bound — an egress found
at a smaller station would extend the exemption past the reviewed interval. Both orientations are fixtures, not a
convention: `exempt_origin_success` and `exempt_insertion_success` (§12.2.1).

**One mask, five consumers — and the mask is the *effective* interval, not the declared one.** The same
`(head, obstacle, arc-length interval)` mask is applied identically by (a) the endpoint check, (b) the sample check, (c)
construction, (d) projection and (e) the final validation. A solver that projects against an unmasked obstacle and
validates against a masked one produces a path neither of them would accept, so the mask is a parameter of the predicate,
not a special case inside one caller.

An interval alone does not express X5's rule, and the effective mask is the one the five consumers use. The declared
`fromStation … toStation` covers **every** station in that range, including stations the path re-enters after leaving, so a
mask that is literally "everything before `toStation`" exempts exactly the violation it exists to forbid. The mask is
therefore the **effective** interval of the block above — `effectiveFrom` the exempted endpoint, `effectiveTo` the first
station on the outward scan at which `d(t) − r(t) ≥ e + σ` (the same `σ`-station the block above defines), clamped toward
that endpoint and bounded by the declared station on the exempted side — and this document defines it **once**. An earlier revision carried a second block in this section that
said "the **first EGRESS** station from that obstacle" with no clamp in its pseudocode; surface egress and margin
clearance are different stations (for a unit sphere, zero radius and `e > 0` they are `x = 1` and `x = 1 + e`), so
implementing the second block for any one consumer produced a mask different from the other four. It is deleted rather than
reconciled, and the definition above is the only one.

- **Computed, or validated, against the current path.** `effectiveTo` is the first station on the scan at which the
  margin plus the declared `σ` holds, and the declared station on the exempted side is a **reviewed bound** on it, not
  a substitute: if the computed boundary lies beyond the declared bound, the declared bound is used — narrower,
  fail-closed — and the divergence is reported; if it lies inside, the computed value is used. A path mutation (construction, smoothing,
  projection, insertion) recomputes both the stations and the boundary — an exemption is anchored to the path, not to
  point indices.
- **Re-entry is checked explicitly, on the same path.** After the envelope, any sample or segment with `g < e` for that
  obstacle is a violation with the ordinary `SEGMENT_INTERSECTS_OBSTACLE` / `CLEARANCE_VIOLATION` reason. That check is a
  first-margin test, not an interval-membership test, and it is the test `exempt_re_entry` pins.
- **Later stations are checked, not cleared by construction.** The boundary is the *first* station that clears, so every
  station after it is non-exempt and is tested by the same predicate; nothing in the mask promises that a later station
  still clears, and the example in the paragraph above (`1 + e/2`, outside the solid, inside the margin) is a violation.
  The boundary search's **own** uncertainty is fail-closed on the same terms: a boundary whose bracket cannot certify
  `g ≥ e + σ` leaves the pair `UNCERTAIN`, never an exemption.
- **The same effective mask, in all five consumers.** A consumer that recomputes the interval differently — from the
  declared endpoint instead of the computed boundary, or from a station list captured before a mutation — is the defect
  this rule removes, exactly as with the obstacle union of §7.4.2.

The consequence for the fixtures: `exempt_then_penetrate` and `exempt_re_entry` are **negative controls**, and an
implementation that passes them by treating the declared interval as the exemption is an implementation that fails them
for the wrong reason on the next path shape. `exempt_origin_success` and `exempt_insertion_success` are the positive
controls — one per orientation — and each path reaches the margin once and never drops below it again.
`exempt_re_entry_after_mutation` is the **mutation** control: the envelope is computed, the path is then mutated
(smoothed, projected, or a point inserted), and a consumer that reuses the pre-mutation mask accepts a station the
recomputed envelope excludes.

**Endpoint inclusion, overlap and mutation.** `fromStation = 0` or `toStation = 1` must be the endpoint the exemption is
for (a `0 … 1` interval is a definition error at L3, as is an exemption with no `source`). Overlapping exemptions on the
same obstacle are a definition error: the union of two intervals on one `(head, obstacle)` is exactly the case X5
forbids. The polyline is **split at every exemption boundary** before validation, so no test ever straddles an exempted
and a non-exempt station, and after any path mutation (construction, smoothing, projection, insertion) the stations are
**recomputed from the new arc length** — an exemption is anchored to the path, not to point indices, or smoothing moves
the mask off the geometry it was reviewed against.

Three things remain forbidden because each disables the check rather than narrowing it: **moving an endpoint** to a clear
position (the attachment is anatomy, not a free variable), **exempting a whole obstacle**, and **an exemption with no
`source`** (it is a review judgement and carries the L7 row that made it). The report counts exemptions per muscle, so a
muscle whose clearance rests mostly on exemptions is visible rather than silently green.

Fixtures: `exempt_origin_success` (a head whose origin sits inside a declared obstacle, with the transition envelope
declared, is `VALID` — satisfiable because the envelope ends at the first station that clears the margin **plus the declared
`σ`**, so the clipped piece starts above `e` rather than at it); `exempt_insertion_success`
(the same head with the exemption on the **insertion**, clamped by `fromStation` and scanned downward, also `VALID`);
`exempt_then_penetrate` (the same exemption, a route that leaves and then passes through a non-exempt station of the same
obstacle, is `INVALID`); and `exempt_re_entry` — the one that decides the rule. In one
dimension against a unit sphere of zero tube radius, path `0.5 → 1.2 → 0.9 → 1.5` with exemption `[0, 0.7]`: the
envelope boundary is just beyond `x = 1` (station `0.3125` plus a fraction of `e`), the path re-enters at `x = 1` on the
way back down (station `0.5625`) and leaves again at `x = 1` (station `0.6875`). **Under X5 the re-entry at stations
`0.5625 … 0.6875` is a violation** — the envelope closed at `0.3125`, long before it — while an exemption interpreted as
"everything before 0.7" accepts it.

**What this costs, stated rather than assumed.** Stage 1 accepts the overwhelming majority of segment–obstacle pairs with
two distance comparisons and no root solve. Stage 3 runs on the remainder, and each evaluation is an Eberly
point-to-ellipsoid distance — a sixth-order root solve, not a closed-form dot product — repeated once per radius piece.
That is the price of a distance predicate instead of an inflation test. The cost is measured and recorded at stage 2
(§15) as information, not as a gate — the same footing as the FPS and thermal readings (§12.3); if it proves
unaffordable, the answer is a coarser *certified* surrogate (a conservative bounding shape whose clearance implies the
tube's) or fewer obstacles — never a weaker criterion.

**Where sampling still matters.** Samples remain a construction device: they set the polyline's resolution for length and
rendering, they give the projection step points to push out, and they define where a tangent kink is measured. Their
density does not determine whether clearance is detected — the segment test does that — and this document makes no claim
that any sample spacing "proves" a distance to the obstacle.

**Scope.** All obstacles declared on the muscle plus any the definition marks as global (thorax, pelvis), over the whole
polyline including tendon portions — a tendon can cut a rib as easily as a belly can. The two sets are unioned once, in
declaration order, per head, and **the same union is what construction and validation both use** (§7.4.2).

**What it does not cover, stated so it is not mistaken for a guarantee.**

- **The validated object is the polyline plus the declared radius, not a re-smoothed curve.** Nothing downstream may
  re-evaluate the Catmull-Rom spline or smooth the polyline again before rendering: a renderer that re-smooths to build a
  tube introduces a curve that was never tested, and `eps_num` does not bound that deviation. Strategy A consumes the
  validated points as they are.
- **A single frame is not a trajectory.** The check is per frame. Motion between frames is a separate question, owned by
  the continuity observer (§7.4.3); a fast transition can still sweep a muscle through an obstacle between two
  individually valid frames, and nothing here bounds that.
- **The obstacle set is the model.** A tube clear of a declared ellipsoid is not thereby clear of bone, fascia or skin. A
  path can pass this test and still look wrong; that is what the L7 review (§12.4) is for.
- **Rig registration is outside it.** A1/A2 align bone *heads* to engine joints; they do not establish that a rendered
  visual bone sits within `eps_num` of the modelled ellipsoid. A 0.5° rest-orientation error at a 1 m lever is **8.73 mm**
  — 873× the numeric margin — so the guarantee is stated in engine space against declared obstacles, and whether the
  rendered surface and the modelled obstacle coincide is an L2/L7 question, not this test's.
- **Floating point.** Everything is evaluated in double precision; a chord inside the fail-closed band is measured rather
  than cleared. The conservative direction is always chosen.

#### 7.4.2 Overlapping, nested and closed routes

Obstacles overlap and nest in normal anatomy (a bone ellipsoid inside a muscle-group ellipsoid is the common case, not
an error). The rules make the outcome deterministic and reviewable:

- **Declaration order is part of the data.** Projection runs in declaration order, and that order is diffable. Two
  definitions with the same obstacles in a different order are different definitions with different expected fixtures.
- **Acceptance is by the union, and there is exactly one union.** Per head, the obstacle set is
  `union(muscle.wraps, globals)` — the obstacles declared on that muscle plus every `WrapSurface` whose `global` flag is
  set (§7.3) — resolved to ids, **deduplicated** (an id declared twice is one obstacle), and ordered by declaration with
  globals first. Construction and validation both consume **that one ordered list**; a solver that projects against one
  set and validates against another is the defect this rule removes, because the two sets can differ exactly when a
  global obstacle is added to a definition.
- **`failingObstacles` indexes the definition's obstacle list, not the union.** The reported index is into
  `AnatomyDefinition.wrapSurfaces` — the immutable, versioned list, in file order — because that is the list a reviewer
  can open and the list that does not change when another muscle's `global` flag changes. A consumer that needs the
  per-head union position (a projection-order diagnostic, for instance) reads `AnatomyFrame.obstacleIndexMap[head]` — the
  published mapping on the frame itself (§5.3), built once per definition version, never re-derived per solve — and it
  never assumes the two orders coincide, and never treats `NO_INDEX` as index `0`. Test
  `failingObstacleIndexOrderTest` (§12.2) reorders the declared obstacles and flips a `global` flag between runs and
  asserts the reported index still names the same obstacle, that the mapping's union position moves with it, and that an
  absent obstacle reports `NO_INDEX` rather than the first entry. Validation tests every segment against every obstacle
  individually, so overlap and nesting need no special case — the union falls out of the per-obstacle tests. Only
  projection order is affected.
- **Nested obstacles.** A sample inside a nested pair is pushed out of each in declaration order until it leaves both or
  the pass cap is reached. An "outer" surface that does not contain its declared inner obstacle is a **definition
  error** (L3), not a runtime condition to paper over.
- **An attachment inside an obstacle, at runtime.** L3 checks attachments against obstacles in the **bind stance**, which
  is a definition-time test. An attachment that is clear at bind can sit inside an obstacle that belongs to a *different*
  moving segment once the pose changes (a pectoral origin and a humerus capsule, for instance). The solver therefore also
  checks every resolved attachment against every obstacle **in the current pose** and returns `INVALID` with
  `ENDPOINT_INSIDE_OBSTACLE` when one is inside. That is a runtime condition, not evidence that the static data is wrong,
  and the two must be reported differently: L3 blocks the definition; the solver blocks the frame.
- **Failure is named by what actually happened, and the names are not interchangeable.** `PASS_CAP` means the pass
  budget `K` ran out and `SEARCH_EXHAUSTED` means the corridor limit was reached without a candidate; **both are search
  outcomes in `searchOutcomes`, not reasons** (X7), so the reason field carries the last geometric cause or
  `NO_GEOMETRIC_CAUSE`. `NO_ROUTE` means the impossibility was **proved** — no route exists between the fixed endpoints
  outside the declared set — and it is a reason the frame never emits in M1. `ENDPOINT_INSIDE_OBSTACLE` is a separate
  runtime condition (§7.4.1c). If the final polyline fails the check instead, the reason comes from the reason table.
  Either way it is reported, counted by the fixture, and blocks L4. A path through geometry is never accepted because it
  is the closest thing the solver found, and an exhausted search is never reported as a proof.
- **Ambiguity.** Where two local routes are both valid, declaration order and the fixed sample grid decide. The solver
  must return the same route on every repeat run **on the same device with the same configuration** and across buffer
  reuse. That is repeatability, not portability: nothing here claims the same route on a different device, and a
  document that did would be claiming bit-identical floating-point behaviour across GPUs and ABIs. "Either route is
  acceptable" is still not a contract, and a solver that alternates between two valid routes on one device has a bug,
  not a tie.

#### 7.4.3 Continuity and tangent transitions

Projection and smoothing both create transitions where the path leaves an obstacle. Three numeric checks with declared
bounds, plus one stateful observer that is not a bound at all:

1. **C0.** One polyline, no duplicated, reordered or dropped points. Structural, cheap, always on.
2. **Tangent continuity at entry and exit.** The angle between the segment entering an obstacle's influence region and
   the segment leaving it must not exceed `θ_max` (proposed 45°, fixed at stage 2). A larger kink reports
   `TANGENT_DISCONTINUITY`; in practice it means the obstacle radius is wrong for that attachment pair, not that the
   path needs more smoothing.
3. **Curvature bound.** No interior sample may turn more than `θ_max` relative to its neighbours at the declared
   sampling spacing. This is the discrete stand-in for "the muscle does not fold".
4. **Temporal continuity — a stateful diagnostic observer, and never a gate.** The solver stays **pure**:
   `solve(pose, profile, out)` takes no previous frame and no `dt`. The earlier proposal — a bound `|Δp(s)| ≤ v_max · Δt`
   with `v_max` = 1.2 m/s — is **withdrawn**, because it is a speed limit on an output that has no physical speed and no
   defensible constant.

   **X6 — the observer is a diagnostic heuristic, and it does not gate anything.** *Confirmed by owner.* It reports; it
   never blocks a frame, never fails L4, and never rate-limits a path. What L4 tests is the **observer's own
   correctness**, not the path's smoothness (§12.2): a rigid translation of the whole body must produce
   no alarm; a rotating or moving wrap must be attributed; an inserted sample must not shift a station; a deliberate
   branch switch must alarm; and the lifecycle cases below must behave as stated. A diagnostic whose arithmetic is
   untested produces plausible findings, which is worse than none.

   - **Inputs, snapshotted.** The frame timestamp; the previous frame **that was `VALID` for this instance**; the same
     instance identity (`MuscleHeadId`), definition version and body profile; and **the obstacle transforms of both
     frames**, because every wrap is declared on a segment and moves with it. The observer copies what it needs — both
     poses and both obstacle sets — rather than holding references into a buffer that the next solve overwrites
     (§3.2 carrier reuse).
   - **One common comparison frame.** Both frames' points and both obstacle centres are expressed in the **previous
     frame's instance frame** before differencing, so a rigid translation or rotation of the whole body is not a signal.
     A comparison in world space would report every step of a walk as a path jump.
   - **Interpolation.** Linear in time between the two timestamps, and used only to place the observation at a station —
     never to predict a path. A sample inserted between two stations therefore does not move the correspondence.
   - **Moving and rotating wrap contributions.** A station's legitimately expected displacement includes the wrap
     centre's translation, the rotation of the obstacle's segment frame about that centre, and the lever arm of the
     attachment offset (`2|d| sin(θ/2)`, §7.2). A stationary obstacle that starts moving because its segment rotated is
     the case that produces false alarms when this term is omitted.
   - **Station correspondence is a fraction of arc length, and that is not material correspondence.** Two frames' station
     `0.5` are the same fraction of two possibly different routes: resampling and inserted detour points change
     `pointCount`, so index `i` is not the same anatomical place twice, and a fraction is a place along a curve rather
     than a piece of tissue. The observer therefore reports a residual only where both frames are on the **same branch**;
     across a branch switch it reports the switch, not a displacement (§12.2).
   - **Timestamp ordering, and what is not an observation.** Timestamps must be strictly increasing. An equal or
     decreasing timestamp is a **reset**, not a negative `dt`; a frame with no timestamp, no pose or no previous `VALID`
     frame produces no observation at all. Missing inputs never become a zero residual.
   - **The gap rule is reset.** A gap — an `INVALID` frame, a seek, a pause, or an interval longer than the declared
     bound — **re-initialises** the observer and is recorded as a reset. The alternative (a separately labelled cross-gap
     diagnostic) cannot distinguish a genuine jump from a re-entry into a different part of the motion without the frames
     in between, so it would report a difference it cannot attribute. Reset is the honest choice of the two.
   - **An excess is a diagnostic, and no bound is claimed.** Near a route-branch switch no finite universal bound
     follows from bounded input speed, so the observer is a *heuristic*, not a conservative bound: it names the station,
     the frame and the branch, and it requires investigation. It never triggers **silent rate-limiting** either — a
     filter that slows a path to satisfy a threshold produces a path that no longer matches the pose or the clearance
     predicate, which is a worse defect than the jump it hides.
   - **Route hysteresis is removed from M1.** The solver re-decides its route every frame from declaration order and the
     frozen sample grid; a dwell or station margin that makes it *stick* to the previous branch is not part of M1, because
     it makes the route a function of history rather than of the pose and hides the very switch the observer is meant to
     report. A branch switch is reported, not damped.
   - **`δ_frame` keeps its name and changes its authority.** It is the station displacement *in excess of* the pose-driven
     prediction, in engine units, and it is a **reporting threshold**: exceeding it files a diagnostic row. It is not a
     gate, it is not in any acceptance criterion, and §12.2 tests the observer against it rather than the path.


#### 7.4.4 What this is, and is not

This is a **geometric approximation**: ellipsoids stand in for bones, tendon sheets and fascia; projection constrains
samples, it does not resolve contact; there is no friction, no sliding, no obstacle deformation and no force. It is
classified as an approximation in §11 and must be described that way wherever it surfaces, including review notes and UI
copy.

Two levels of approximation are worth separating, because they are constantly confused:

- **The verdict is a measurement with a stated error budget, not an exactness claim.** The predicate is the finite-segment
  minimum `min_t (d(t) − r(t)) ≥ eps_num` (§7.4.1b), evaluated in double precision with `eps_num = 1e-5 m` derived from
  the input quantisation and the transform chain. That budget — not a claim that the arithmetic is exact — is what the
  guarantee rests on, and it is why a chord inside the fail-closed discriminant band is measured instead of cleared.
- **The model is approximate.** An ellipsoid is not a bone, a tendon sheet or a fascia plane. Passing the test means
  the path misses the *ellipsoid*; it does not mean the path misses the anatomy.

"Exact test" is not "correct anatomy", and "approximate model" is not "sloppy test".

**Known limitations of the M1 numeric guarantee, stated rather than derived.** Three things this section computes are not
proved, and none of them is repaired here. The **stage-1 sphere comparison** (§7.4.1b) is a sufficient inequality in real
arithmetic, but its binary64 evaluation carries no rounding enclosure, so an early `CLEAR` is a decision this document has
not certified. Where a **stage-2 witness** survives without a proved enclosure, **stage 3's own result is authoritative** —
its lower/upper/uncertain bracket decides that pair — and the fixture verdicts whose expected reason belongs to the
enclosure deliverable are **provisional** until it lands. And the global vertex term `√3 · 2⁻¹⁴` is inferred from the operand
bound plus an obstacle semi-axis, **not** from the tube radius the renderer actually sweeps, so it does not bound every drawn
vertex. **No numerical clearance floor is claimed in M1**: anything not covered by a proved bound fails closed
(`UNCERTIFIED_MINIMUM` → `INVALID`), and the milestone's assurance therefore rests on the human review of §12.4 (RV1 and
RV4) and on tests run against real code, not on the arithmetic above.

Design notes:

- **Length is arc length along the solved path.** A straight-chord measure reports shortening wherever a muscle wraps a
  joint, which inverts the sign of the reported change. This is the single most important correctness rule in the model.
- **`restLength` comes from the same solver at the reference stance**, not from the definition's numbers, so the rest
  state and the runtime can never disagree (this is also what the exporter bakes; §10.4). The **reference stance** is one
  frame, named once: the **X1 bind frame** of §6.6 — the canonical straight-limbed A-pose computed from
  `SkeletonDefinition`, arms at the sides — supplied to the solver as a `SkeletonPose` value (§10.4). It is *not* a
  published pose and *not* `squat_standard` at progress 0: "the pose every builder starts from" was the wrong
  description, because two builders can start from the same intent and still publish different stances, and a reference
  stance that moved with a builder would make `restLength` a function of that builder. The frame's identity is named in
  the definition version. `restLength` must be finite and strictly greater than zero for every head; a head that cannot
  produce one is `INVALID` with `REST_LENGTH_INVALID` (§7.4.1c) rather than dividing by it. A relative change computed
  against a zero or a NaN rest length is not a small error, it is a meaningless number that reads as a large one.
- **A length comparison with literature is a record, not a number.** Where a measured reference exists, the comparison
  states: the source, the model or subject it came from, the pose or joint configuration, the normalisation used (muscle
  length over which span, at which reference), the tolerance, and the uncertainty. A row with no source is an **evidence
  gap** and is displayed as one — never as a pass, and never as a zero.
- **Which reference is mandatory, and whether a gap blocks.** Two requirements, deliberately not the same strength:
  a **solver-derived rest length** — finite and strictly greater than zero — is **mandatory for every head**, and a head
  without one is `INVALID` (`REST_LENGTH_INVALID`); a **sourced external comparison** is mandatory **only where §12.4 or
  a definition names one**, and where it is named the row must carry source, task, population and uncertainty. **An
  evidence gap does not block M1**: the readout is labelled `modelled` (X4) and the gap is recorded in the review file,
  because a model that refuses to ship until a literature comparison exists ships nothing, and a model that calls its own
  number "measured" is worse. The gap blocks a *claim*, not a merge.
- **No joint motion is written.** The solver reads `pose` and writes only `AnatomyFrame`.

#### 7.4.5 Route construction (bounded heuristic)

Validation is a test; construction is a heuristic, and the two were previously conflated. Radial projection alone cannot
produce a valid route: for a chord through the centre of a sphere, the samples, the inserted entry/exit points and their
outward normals all lie on the chord's axis, so projecting and smoothing preserves the axis and the path still passes
through the obstacle. The `single_obstacle` success case therefore needs a lateral rule.

**Frame and unit ledger (all of §7.4.5).** One frame, one scale, stated once:

| Symbol | Meaning | Frame | Unit |
| --- | --- | --- | --- |
| `p_i`, `p_{i+1}`, `m`, `cw` | polyline points and obstacle centre | world | engine units |
| `d̂`, `u`, `lateral` | directions (unit vectors, frame-independent magnitude) | world | dimensionless |
| `Rw` | obstacle axes (§7.4.1b) | local → world rotation | dimensionless |
| `R` | semi-axes `radiiMetres · s`, uninflated | obstacle-local axes | engine units |
| `e = eps_num · s` | the numeric margin, on its own | — | engine units |
| `d_1`, `d_k`, `h(l̂_d)` | detour distances | world length | engine units |

Every distance added to a world point is in engine units; `eps_num` is **metres** and never enters a sum until it has
been multiplied by `s = unitsPerMetre` (§7.2). A direction fed into `h` is first mapped to the obstacle's local frame
with `Rwᵀ`, because `R` lives on those axes. Mixing these — a metre-valued `eps_num` added to engine-unit `R`, or a
world-space direction used as if it were local — is the defect class the fixtures below catch.

**Lateral detour rule.** For each obstacle a segment intersects, in the head's ordered union (§7.4.2):

```
d̂ = normalize(p_{i+1} − p_i)                  // chord direction, world
m = (p_i + p_{i+1}) / 2                        // chord midpoint, world
u = (m − cw) − ((m − cw) · d̂) d̂               // lateral component of the chord offset, world

d̂_local = Rwᵀ · d̂                              // the chord direction in OBSTACLE-LOCAL axes
lateral = normalize(u)                         if |u| > u_min (proposed 1e-6 m → 1e-6 · s engine units)
        = Rw · (local axis least aligned with d̂_local, x → y → z on ties)   otherwise
        // fallback is chosen in the local frame and rotated back to world; it is a direction, so no scale applies
        // the SAME rule supplies the outward normal when a sample sits exactly at the obstacle centre and
        // the normal is the zero vector — a zero vector is never normalised, and the fallback is not a guess
        // about anatomy: it is the deterministic tie-break (x → y → z on equal alignment)

l̂_d = Rwᵀ · lateral                           // the chosen world direction, back in local axes
d_1 = h(l̂_d) + e                              // h = support function, √Σ (R_i · l̂_d_i)²
d_k = 1.5 · d_{k−1}                            // escalate on each further pass, capped by the corridor limit
```

`h(l̂_d) = √Σ (R_i · l̂_d_i)²` is the support function of the **ellipsoid itself** (`R` is the authored radius, not
inflated): it is the distance from `cw` to the tangent plane in the `l̂_d` direction, in engine units. `d_1` adds `e` on
top, so the waypoint sits one numeric margin outside the surface and `eps_num` is converted exactly once. The fallback
picks the local axis least aligned with `d̂_local = Rwᵀ d̂`; choosing among world axes assumes `Rw = I` and is wrong for a
rotated obstacle, and the chosen axis is rotated back with `Rw` before it is used as a world direction.

For a sphere (`R = (R, R, R)`, `R = radiiMetres · s`), `h` is `R` for any `l̂_d`, so `d_1 = R + e`, which in metres is
`radiiMetres + eps_num`. The rule therefore never adds the margin twice and never mixes frames.

Insert **one** waypoint at `cw + lateral · d_k` — one side only. Two waypoints on opposite sides would make the path
cross the obstacle between them, which is the failure this rule exists to avoid. The side is chosen by the rule above
and stays fixed within a pass; if the path still fails, the next pass escalates the distance (1.5× per pass, bounded by
the declared corridor limit) rather than flipping sides, so the outcome stays deterministic. Exhausting the corridor
limit is `SEARCH_EXHAUSTED` (§7.4.1c, X7) — a limit on the declared corridor, never `NO_ROUTE`, which M1 does not emit.

Escalation is not decoration, and the numbers below are re-computed against the **final** polyline rather than against
the original chord. Sphere `R = 20 mm` centred on a 290 mm chord sampled every 10 mm, `e = eps_num · s` = 0.01 mm. **The
grid decides which points are retained, so it is part of the fixture:** `N = 30` endpoint-inclusive over 290 mm is 29
intervals of exactly 10 mm, the samples are `0, 10, … 290`, the centre at 145 mm is **not** a sample, the samples at
`130, 140, 150, 160` lie inside (|Δ| = 15, 5, 5, 15 mm < 20), and the nearest **retained** neighbours are therefore at
`±25 mm` — `120` and `170`. An earlier revision computed this table for ±30 mm neighbours, which this grid cannot
produce:

| Placement | Retained neighbours (±25 mm, outside) | Entry/exit points (on the surface) |
| --- | --- | --- |
| `d_1 = R + e` = 20.01 mm | **rejected**: the connecting segment passes 15.62 mm from the centre | **rejected** |
| `d_2 = 1.5·d_1` = 30.015 mm | **rejected**: 19.21 mm — still inside the 20 mm sphere | **still rejected** |
| `d_3 = 1.5·d_2` = 45.0225 mm | **clear by 1.86 mm** (21.86 mm centreline, ≥ e) | **still rejected** |

So the connecting segments are rejected on the first two passes and clear on the third — but only because they are anchored
on the **retained samples outside the obstacle**. **These are static measurements of one connecting segment per pass, not
the fixture's verdict.** Each figure is the distance from a retained neighbour to that pass's waypoint on that pass's
*unsmoothed* polyline, before smoothing, projection and re-testing (§7.4 step 4) — so it establishes that pass 1 and pass
2 cannot be accepted as constructed, and it does **not** establish that pass 3 is accepted. The accepting pass index is a
property of the whole loop, and `single_obstacle` asserts it by running the loop; quoting 21.86 mm as "the accepted path's
clearance" would report a construction intermediate as a result. If the entry/exit construction points stay in the polyline, the closest
approach is `R·D/√(R² + D²)` for a waypoint at distance `D`, which is **below `R` for every finite `D`** — 18.28 mm at
`D = 45.0225` and still 19.90 mm at `D = 200` — so no escalation ever clears it. That is the reason the retained-point
rule below is not bookkeeping: an entry point that sits on the surface is a zero-clearance point, the predicate rejects it
at every pass, and a construction that keeps it turns a clearable configuration into a `PASS_CAP` failure. First-pass
rejection here, later successful escalation, and a terminal failure for the retained-entry variant are three different
outcomes of one geometry, and the fixture table states which one each fixture expects: `single_obstacle` is the
sample-anchored variant — passes 1 and 2 are rejected **statically**, so the accepting index is `≥ 3` and the loop
reports which one it was — and `retained_entry_never_clears` is the same geometry with the entry/exit points kept
(terminal `PASS_CAP`), so a reader who sees only one of the two rows should not conclude that the other is wrong. The
static figures bound the accepting index from below; they do not name it, and no row in either table calls pass 3
accepted.

**Worked frame check — `rotated_anisotropic`.** One obstacle with local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z
(local X → world +Y), so its **world** semi-axes are `(10, 40, 20)` mm; `eps_num` = 1e-5 m, chord
`(−145, 0, 0) → (145, 0, 0)` mm in world X through `cw`. Here `d̂ = (1, 0, 0)`, `u = 0` (midpoint at the centre), and
`Rwᵀ d̂ = (0, −1, 0)`: the local axes least aligned with it are X and Z (both dot 0), so the tie order picks local X,
rotated back to world `(0, 1, 0)`. Correctly, `l̂_d = (1, 0, 0)` and `h = R_x` = 40 mm → `d_1` = 40.01 mm, which puts the
waypoint 0.01 mm outside the *surface tip* on that axis.

**The first pass is rejected, and the "24.21 mm" this paragraph used to quote could not have come from this geometry.**
One margin outside the support plane is not one margin outside the surface: the retained neighbour on that side is the
sample at `(15, 0, 0)` mm — the pinned `N = 30` grid is 10 mm spacing, so the crossing samples at `±5 mm` are inside and
the nearest retained ones are at `±15` — and the straight segment from it to the waypoint `(0, 40.01, 0)` **enters the
ellipsoid**. In normalized coordinates `q0 = (1.5, 0, 0)`, `qd = (−1.5, 1.00025, 0)`, so `a = 3.2505`, `b = −4.5`,
`c0 = 1.25` and **`Δ = 3.9975`**, with roots `[0.3847, 0.9997] ⊂ [0, 1]`. Escalation to `d_2 = 1.5·d_1 = 60.015 mm` puts
the same segment clear: `Δ = −2.2556` (no real roots) and its closest approach to the solid ellipsoid is **0.834 mm**;
the tube radius is 0, so `min g = d − r = +0.834 mm` and the margin-adjusted excess `g − e` is **+0.824 mm**. (The two
numbers are not interchangeable: `0.824` is `g − e`, and quoting it as `g` is a 0.01 mm error in the reported quantity,
harmless for the verdict and wrong for the report.)

The segment from an entry point that stays on the surface clears by 0 by construction and is rejected at every distance,
as in the sphere case above. Choosing the fallback in world axes instead (world +Y fed straight into `h` as if local)
gives `h = R_y` = 10 mm, a waypoint 10.01 mm from the centre — still inside the obstacle — so the predicate rejects it.
**"Nothing rescues it" is not what the static segment says, and the difference decides the fixture.** Escalation multiplies
that same wrong `h` by `1.5` per pass, so `d_k = 10.01 · 1.5ᵏ⁻¹` mm: at pass 6 the waypoint sits at **76.0134 mm** on world
`+Y`, and the same retained neighbour's segment then comes **2.217 mm** clear of the ellipsoid — past `e`. On this static
geometry the wrong-fallback implementation is rejected for five passes and clears at the sixth; whether the **full** pass
loop reaches that pass depends on smoothing, projection, the worklist and the corridor, none of which the static
computation models. The fixture therefore asserts the static facts (passes 1–5 rejected) **and** the loop's final outcome,
measured by running the loop — never a quoted distance standing in for an accepting pass index. The `rotated_anisotropic`
fixtures in §12.2.1 pin both halves: the validator must honour `Rw`, and the constructor must transform the fallback and the
support direction.

**The pass loop, pinned — one pass is a pure function of its input polyline.**

| Item | Rule |
| --- | --- |
| Catmull-Rom parameterisation | uniform, one span per input segment, `P(0) = P1`, `P(1) = P2`, tangents `(P2 − P0)/2` at `P1` and `(P3 − P1)/2` at `P2`, blend `0.25` toward the smoothed point; no centripetal or chordal variant |
| sampling | uniform in the Catmull-Rom parameter, count recomputed from arc length at the start of each pass, clamped to `8 … pointCapacity`; never uniform in arc length |
| endpoint policy | the two attachments are never moved, never resampled and never replaced; they are the same objects in every pass |
| survivors | a pass's output polyline replaces its input entirely. A waypoint survives until a later pass replaces its subsegment; a temporary entry/exit point survives **only** if the final predicate accepted a polyline containing it — and since such a point sits on the surface, it never does (§7.4.5 escalation table) |
| worklist | the `(segment, obstacle)` pairs to act on are frozen at the start of a pass and do not grow as construction inserts points; segments created inside a pass are first tested in the **next** pass, so a pass cannot chase its own output |
| escalation | **any** failed final predicate escalates: a centreline-clear but tube-invalid candidate (`min g < e`, no intersection, stage 3) escalates exactly like an intersecting one, with the same `1.5×` and the same corridor bound. The earlier rule escalated only on centreline crossings and left a tube-invalid candidate looping without the one operation that could rescue it |
| bounds | `K ≥ 1`, `corridorMetres > 0` and `pointCapacity ≥ 2` are validated at L3 as finite and positive — a NaN corridor makes every comparison false and a zero cap makes every insertion fail — and `K` is an integer, never a float compared with a rounded pass counter |
| failure order | inside a pass, `BUFFER_CAPACITY` is decided before corridor exhaustion, and corridor exhaustion before `PASS_CAP`; the arithmetic that compares `d_k` with `corridorMetres` is done in `Double` with the stored `d_k`, never re-derived from `1.5ᵏ` |
| projection | ordered projection is a **pass, not a solver**: no convergence claim, no iteration to a tolerance. Two overlapping obstacles can alternate forever — fixture `two_obstacle_projection_cycle`: spheres centred at `0` and `2` with radius `1.5`, a sample at `0.5`; projection in declaration order moves it to `1.5`, then to `0.5`, then to `1.5` — so its output is only ever an input to the final check. This document never describes ordered projection as convergent |

**Which points survive a pass.** The two endpoints are fixed — they are the attachments and no construction step moves
them. Every **retained** intermediate point must be strictly beyond `r + e` from every surface in the union; a point that
is not is projected in step 4c or dropped before the next pass, and a temporary entry/exit point is kept only if the
final check accepted the polyline that contains it. Construction therefore cannot leave behind a boundary point that the
final predicate then rejects: the predicate runs on exactly the points that would be kept.

**What it guarantees, and what it does not.** The rule is deterministic and bounded: at most one detour waypoint per
crossed segment per pass, plus its entry/exit samples, with the pass cap `K` and a corridor limit that bounds the
escalation `1.5ᵏ·d₁`. It is **not a complete planner**, and this document no longer claims sufficiency even for a single
convex obstacle — the sphere example above shows the first placement failing the check, and two obstacles can require a
side choice a single-waypoint rule cannot express (fixture `two_obstacle_oscillation`). Configurations exist in which a
valid route exists and this rule fails to find it; failure is `INVALID` with `PASS_CAP` or `SEARCH_EXHAUSTED`, never an
accepted penetrating path, and `NO_ROUTE` is never claimed without a proof. The fixtures in §12.2.1 state which
configurations must succeed **and which must fail**; a configuration outside that set is a research question, not a
regression.

### 7.5 Geometry strategies

| Strategy | Anatomy quality | CPU per frame | Filament fit | Art cost | Verdict |
| --- | --- | --- | --- | --- | --- |
| **A. Procedural tube/sheet from the path** (AA-3D's approach) | low–medium: correct length, generic constant cross-section (§7.6), no surface detail | high: full rebuild of every muscle every frame (AA-3D: 30.9 k tris, 4.6 ms desktop) | easy: one buffer, one dynamic mesh per layer | none | **phase A / debug / tier `reduced`** |
| **B. Pre-authored muscle mesh skinned to the body rig** | high surface, but the mesh follows bones, not the path; length is implicit and cannot be measured or trusted | ~0 | easy | high | rejected as the primary strategy |
| **C. Hybrid: authored mesh skinned to path-driven helper bones** | high surface *and* path-correct length | low: ~4 helper transforms per muscle | good: one skin, standard skinning | medium–high (mesh + helper bind) | **target** |
| **D. GPU deformation (compute/vertex shader from path uniforms)** | same as C, potentially finer | lowest CPU | needs a custom material; Filament material authoring, no simple glTF path | high | research; not v1 |

**Recommendation: C, with A as the first implementation and the fallback.**

The contract that makes them interchangeable is the path, not the mesh:

```
MuscleState.pathPoint(i, out)  ──►  A: tube generator  ──► dynamic vertex buffer
                  └─►  C: helper bone transforms ──► skinned mesh (bones authored at the rest path)
```

Helper-bone specification (strategy C):

- **4 bones per head** — `origin`, `belly_proximal`, `belly_distal`, `insertion` — placed at 0%, 30%, 70%, 100% of that
  head's rest path. A two-head muscle therefore carries 8 helpers and one visual belly; the helper map is keyed by
  `MuscleHeadId` (§5.3), not by muscle id, because a head's path is independent and its length is reported separately.
- Each helper's world transform at bind = the frame at its rest path point (origin at the point, +Y along the local
  tangent, +Z from the surface normal reference). The muscle mesh is weighted to these bones by proximity along the path,
  with ≤ 4 influences per vertex (glTF standard). Vertices near a head boundary blend between that head's helpers and the
  shared belly region; the blend is authored, not solved.
- Per frame, `MuscleLayerSkinner` writes each helper as: translation = path point at the same fractional arc length;
  rotation = frame built from the local tangent and a stable up reference; scale = 1. M1 applies no cross-section
  response (§7.6), so the helper transform carries position and orientation only.
- **What the helpers do and do not guarantee.** The helpers' positions are exactly the solved path points at their
  stations, so the mesh's control points cannot drift from the path. Between two helpers, linear-blend skinning
  interpolates, so the surface between stations deviates from the polyline — more where the path bends sharply. Stage 3
  therefore measures that deviation at authoring time (maximum distance from sampled mesh medial points to the polyline)
  and reports it against a declared tolerance in L5. "Follows the path" means the control points and a measured bound,
  not a claim that every vertex lies on the curve.

### 7.6 Deformation model

M1 has exactly one deformation driver:

**Path following.** The helper bones (strategy C) or the generated vertices (strategy A) move to the solved path. A
muscle's visible length and position change because its attachments moved; its cross-section does not change. Scale is
1 on every helper bone and constant on every generated ring.

That is the whole model. **The modelled path becomes shorter; the muscle is not said to contract.** Nothing gets thicker,
and nothing bulges because the exercise says it is working.

**Who may move a muscle vertex, and why.** "No geometry moves except along the path" was too blunt: the character layer is
allowed surface morphs and corrective shapes, and both move vertices while the path is unchanged. The rule is therefore
about *ownership and evidence*, not about a single writer:

| Vertex motion | Owner | Encodes | Allowed in M1 | Contract |
| --- | --- | --- | --- | --- |
| Path following | Anatomical Model | anatomy — where the muscle is | yes | the only motion derived from `AnatomyFrame` |
| Surface morphs (body and muscle meshes) | Character Appearance | body-family shape | yes, body only in M1 | declared in the profile; never reads `AnatomyFrame`; never changes a path, length or landmark |
| Corrective shapes (joint creases, arm-raise, elbow-bend) | Character Appearance | visual joint credibility | later stages | authored per rig; driven from published rotations; never reads `AnatomyFrame` |
| Cross-section response to length | — | a contraction claim | **no** | research hypothesis only (§7.6 above) |
| Activation-driven deformation | — | physiology | **no** | forbidden outright |
| Per-muscle animation curve or timeline | — | nothing defensible | **no** | forbidden outright |

The testable consequence, and the reason this is a contract rather than a slogan: for a fixed `SkeletonPose`, toggling
morphs or correctives may change vertices, but must leave every `MuscleState` — path, `pointCount`,
`modelledPathLength`, `restLength`, `relativePathLengthChange`, `PathStatus` — bit-identical.
`MuscleGeometryOwnershipTest` (L4/presentation boundary) asserts exactly that, and also asserts the converse: nothing in
the character layer writes `AnatomyFrame`.

One honest caveat about strategy C: as helpers separate along the path, linear-blend skinning stretches the mesh between
them, so the rendered silhouette is not perfectly constant. That is a skinning artifact of moving the geometry to the
solved path — it is not a modelled response, it is not calibrated to length, and it must not be described as one.

**Why the isovolumetric formula is not in M1.** The obvious candidate is the isovolumetric approximation of a uniform
cylinder:

```
r(L) = clamp( r0 * sqrt(L0 / L), 0.75 * r0, 1.25 * r0 )
```

It is a uniform-cylinder volume argument, not a validated model of individual muscle deformation. Real muscle is
pinnate, anisotropic, wrapped in fascia, varies in cross-section along its length, and changes shape with joint angle in
ways a cylinder cannot express. Applied automatically it produces a bulge that a reader will read as a contraction claim
the model cannot support (§11). An approximation that looks plausible is not thereby validated, and a formula that is
cheap to evaluate is not thereby evidence.

**Optional future research hypothesis — explicitly not a commitment.** If a cross-section response is ever wanted, the
formula above is the cheapest candidate to test, but it is a hypothesis to be validated before it is a mechanism to be
shipped:

- it must be checked against measured cross-section behaviour of specific muscles through specific ranges (imaging or
  ultrasound), not against visual plausibility;
- it must be per-muscle or per-muscle-group, because the cylinder law fails worst exactly where it is most visible —
  the pennate quadriceps and the multi-head biceps;
- it must be opt-in per muscle in the definition, off by default, and classified in §11 as a visual approximation;
- no roadmap stage commits to it. Appendix C item 4 asks whether it should exist at all.

**Forbidden, per §11:** any bulge keyed to activation; any per-muscle animation curve; any cross-section change not
derived from a measured length (M1 has none); any substitute heuristic ("thicker when the exercise is harder", "pulse as the squat rises"). `relativePathLengthChange` is reported for the length readout and the education panel; it is
never a geometry input.

### 7.7 Layers, and what is deferred

| Layer | Contents | v1 | Notes |
| --- | --- | --- | --- |
| superficial muscles | 8 muscles for M1, ~35 for v2 | yes — the canonical eight of §7.7.1 | the eight are named once, in §7.7.1; no other section restates them |
| deep muscles | 8–12 for v2 | no — deferred beyond M1 | psoas, iliacus, rotator cuff, transverse abdominis, multifidus |
| tendons / ligaments | authored bands from the same attachment data | later | needs its own geometry contract; not a muscle with different colour |
| organs | meshes rigged to the thorax/abdomen | later | explicitly deprioritised by the task; the reference implementation's organs are a separate asset with its own animation, which this framework would have to justify mechanically |
| attachment markers | spheres/labels at attachment points | later | marker *geometry* is deferred; the M1 tap hint of §15 item 9 reads an attachment landmark that already exists in the data and draws no marker, so the two are not in conflict |

#### 7.7.1 The canonical M1 muscle set

One list, in one place. §15's stage 2 and M1's definition of done cite it; no other section restates it, because an
earlier revision carried three different lists — this table, the roadmap and a family example — and a reviewer had no way
to tell which one was the deliverable.

| Muscle | M1 instances | Path structure in the source model | Via points and wraps M1 must reproduce | Caveat M1 carries |
| --- | --- | --- | --- | --- |
| rectus abdominis | 2 (one per side) | one path per side | one pelvic attachment, one costal attachment | coarse trunk surrogate |
| external oblique | 2 | one path per side | one iliac attachment, one costal attachment | coarse trunk surrogate |
| erector spinae | 2 | one path per side | one sacral/pelvic attachment, one costal attachment | coarse trunk surrogate |
| latissimus dorsi | 2 | one path per side | humeral insertion, thoracolumbar origin | coarse trunk surrogate |
| gluteus maximus | 2 (source: **3 paths per side**) | three right-side paths, each with pelvic wraps | one pelvic wrap per path; the collapse to one path per side is recorded as an approximation | the two dropped paths are a review item, not a silent simplification |
| gluteus medius | 2 (source: **3 paths per side**) | three paths per side, **no wrap** in the source model | no wrap — and **the source's absence of a wrap does not guarantee clearance in our rig**: our obstacle set and segment frames differ, so the path must pass §7.4 here | as above |
| biceps femoris | 4 (two heads × two sides) | long head and short head are separate paths | **long head: one intermediate fixed point, no wrap. Short head: one wrap** | two heads, one visual belly (§7.5) |
| rectus femoris | 2 | one path per side | **routes over a femoral wrap and over patella-body points**; there is no patella frame (§7.3) | the patella approximation and the supported knee range are stated in the definition and reviewed per side |

**Eight names, eighteen instances.** The M1 set is eight muscles, sixteen sides and two extra biceps-femoris heads:
**18 tubes** at stage 2, which §10.3 budgets at 13,536 triangles at the initial resampling cap (`N = 48`, `R = 8`) and
13,824 with two fan caps per tube — a floor, since construction inserts points up to `pointCapacity`. A count of
"eight" that means eight tubes under-budgets by more than half and loses the second head.

**The trunk muscles are coarse visual surrogates, and the label is part of the data.** `rectus abdominis`,
`external oblique`, `erector spinae` and `latissimus dorsi` attach to a **single `LUMBAR` and a single `CHEST` joint**
(§7.2), so the engine has one lumbar segment and one thorax segment: no segmental spinal anatomy, no vertebra-specific
motion, no curvature claim (§13 question 10), no per-rib or per-vertebra attachment. They are labelled *coarse trunk
surrogates* wherever they appear — definition, review record and any user-facing copy. **No clinical anatomist has
reviewed this set**: the §12.4 review is the gate, and until it is signed the eight are a proposal with a mechanical
contract, not accepted anatomy.

**Rectus femoris: the patella is an approximation and the range is stated.** With no patella frame the path runs through
authored patella-body via points instead of around a sesamoid, and the error grows with knee flexion. The definition
therefore declares the **supported knee range** over which the approximation was reviewed, and the review record names
it; a pose outside that range is outside the review, not inside a guarantee.

#### 7.7.2 Source rights, frames, and what is missing

The attachment table is meant to be derived from published musculoskeletal models, so their provenance is part of the
design rather than an appendix. What was verified for this document, and what was not:

| Source | Access | Rights evidence as found | Status |
| --- | --- | --- | --- |
| Rajagopal et al. full-body model (SimTK release) | public download | the release popover shows "Copyright (c) 2015, Stanford University. Permission is hereby granted…" — an MIT-style grant, **truncated in the page source**; `README.txt` and the `.osim` file carry **no licence text at all** | permissive per the popover, unconfirmed by the files |
| Christophy et al. lumbar-spine model | **login-gated** | not read | **UNVERIFIED** — attachment coordinates, frames and any embedded notice are unread |
| OpenSim project (SimTK project page) | public | popover shows MIT, "Copyright (c) 2010, University of California at Berkeley" | says something about the *project*, nothing about a specific model file |

**No rights conclusion is drawn here, and none may be drawn from this table.** Public access is not redistribution
permission; a permissive project licence is not a permissive asset licence; and a truncated popover is not a licence
file. Two rules follow, and neither is negotiable: **no model data enters this repository** (attachments are authored in
metres against the engine's rest stance and reviewed, not copied), and a source may be cited only once its licence text
has been read in full and recorded with the release, the hash, the notices and the citation the authors request.

**Frames are a mapping problem, not an axis permutation.** Every source body and offset frame must be mapped to a Monk
segment explicitly — origin, handedness, side convention, units, reference stance and scaling, per row. A single axis
permutation is not a mapping: two models can differ in frame origin, in which end of a bone the origin sits, and in
whether "right" is +Z or −Z, and a permutation that happens to fit one row is the most expensive kind of wrong.
Patella points, individual vertebrae, conditional or moving points and non-ellipsoid wraps are **missing degrees of
freedom**, not missing numbers: each is either approximated with the approximation recorded, or excluded from M1.

### 7.8 Missing research and art requirements (explicit)

| Need | Why it blocks | Smallest resolution |
| --- | --- | --- |
| Attachment library for the eight M1 muscles, in engine metres | the schema is useless without calibrated coordinates | an anatomist/physio reviews a table against the rest stance; one 2-hour session plus one revision pass |
| Wrap-surface parameters (hip capsule, ribcage, shoulder girdle) | wrong radii make paths cut through the body or float | tune against 3 poses (squat, overhead reach, hip hinge) and freeze per definition version |
| Muscle mesh authoring pipeline | strategy C needs meshes bound to helper bones | one Blender-based pipeline producing a GLB with helper bones + 4 weights; validate with the asset tool |
| Left/right and origin/insertion conventions per muscle | mirroring errors are silent | the side check (§12 L2) plus a per-muscle review checklist |
| Pennation / multi-head handling | biceps and triceps need two/three heads sharing one visual belly | model heads as separate paths with a shared mesh region; validate that head lengths differ plausibly |
| Whether a single skin can hold all helper bones | the limit is **known**: 255 bones per renderable documented (`RenderableManager.h`, Filament 1.75.1), 256 in the implementation, and `gltfio` passes the skin's full joint count to the renderable, so splitting a mesh into primitives does not reduce it (§10.3). The binding quantity is `palette(skin) = base + visual joints + 4 × (head-side instances on that skin)`, not the hypothetical 313 | the regional split is mandatory for v2, not a contingency; M1 needs none because strategy A has no helper bones in M1 |
| Per-muscle cross-section behaviour, if it is ever wanted | no validated model exists; the cylinder law is a plausibility argument, not evidence (§7.6) | an imaging-based study of the specific muscles through their working ranges, with a stated tolerance — a research task, not an engineering one |

---

## 8. Muscle activation semantics

### 8.1 What the model is

An **authored educational classification**, at the granularity of "which muscles participate, in what role, in which
phase of which exercise". It is not EMG, not a physiological simulation, and not a claim of measured intensity.

```
Exercise → Muscle → Role → MovementPhase → Emphasis
```

- **Role**: `PRIME_MOVER`, `SYNERGIST`, `STABILISER`, `ANTAGONIST`, `UNKNOWN` — a **categorical, sourced** statement
  that a muscle takes that role in that movement, carrying a source, a task, a population and a review status (§8.2). It
  is not a magnitude and not an ordering: **no numeric activation value, no EMG-derived output, and no medical or
  therapeutic claim anywhere in M1.** `UNKNOWN` is the value for "no reviewed row exists", so absence is representable
  instead of being guessed at. `ANTAGONIST` is used **only where the reviewed movement-specific evidence supports it** —
  never as the default name for a phase, because a phase belongs to the exercise and a role belongs to the muscle.
- **MovementPhase**: `ASCENT`, `DESCENT`, `HOLD` — the **exercise's** neutral stage names, read from its reviewed
  `PhaseTimeline`, never inferred per muscle frame and never derived from joint angles. **A phase is a property of the
  exercise; a role is a property of the muscle in that exercise**, and keeping the two apart is what stops a stage name
  from turning into an implicit contraction assertion about a named muscle. Contraction mode is **not** in this
  vocabulary: `CONCENTRIC`, `ECCENTRIC` and `ISOMETRIC` are muscle-specific claims about a specific muscle under load,
  they are not recoverable from a stage name, and a claim of that kind is a **separately reviewed, sourced item outside
  M1** — it is not a schema field, it is not displayed, and no role or phase value implies it.
- **Emphasis**: an ordinal tier (`PRIMARY`, `SECONDARY`, `SUPPORTING`), not a percentage — and **no numeric 0–1 value is
  derived from it**. The earlier text allowed an internal 0–1 "display intensity derived from the tier and the phase";
  that is withdrawn, because a number computed from a tier and a phase is a number no source supports, and shading a
  figure with it makes it look measured. Role colour is a **reviewed educational emphasis**: it encodes the reviewed
  tier, it carries its source and its limits in the legend and the panel, and it is never labelled, ordered or
  interpolated as an activation percentage.

### 8.2 Data

```json
{
  "exercise": "back_squat",
  "phaseTimeline": "back_squat@v1",
  "source": "anatomy-review-2026-11",
  "task": "barbell back squat, full depth, controlled tempo",
  "population": "recreationally trained adults",
  "reviewStatus": "REVIEWED",
  "muscles": [
    { "id": "gluteus_maximus",  "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["ASCENT", "DESCENT"] },
    { "id": "vastus_lateralis", "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["ASCENT", "DESCENT"] },
    { "id": "biceps_femoris",   "role": "SYNERGIST",   "emphasis": "SECONDARY", "phases": ["ASCENT"] },
    { "id": "erector_spinae",   "role": "STABILISER",  "emphasis": "PRIMARY",   "phases": ["ASCENT", "DESCENT"] }
  ]
}
```

**Every row carries five required keys, and the schema is the whole vocabulary.** `source` is the provenance id of the
review that signed the row; `task` names the movement variant the row is about (a role is a claim about a task, not about
a word); `population` names who the evidence covers; `reviewStatus` is `REVIEWED` or `UNREVIEWED` — a **schema field**,
which is what an entry with no signed review carries, and `UNREVIEWED` rows may not be displayed; `phaseTimeline` names
the reviewed timeline the `phases` values are checked against. There is no `phaseSource` field and no per-muscle
contraction mode: an earlier revision used `phaseSource: UNREVIEWED`, which was a value the schema did not carry, and
`phases` entries of `CONCENTRIC`/`ECCENTRIC`/`ISOMETRIC`, which asserted a contraction mode per muscle. The example above
is schema-shaped, not reviewed content.

Rules:

- **Every entry carries a source.** No source, no entry — the validator rejects it (§8.3).
- **Roles are per exercise, not per muscle.** The same muscle may be a prime mover in one exercise and a stabiliser in
  another; that is the educational content.
- **Phases come from a reviewed timeline, not from joint motion.** The engine publishes no stage timeline: `PoseMetadata`
  carries `durationSeconds`, `loopMode`, `motionCurve` and breath fractions, and `LoopMode.HOLD` describes a loop
  behaviour, not a muscle's role. A stabiliser does not stop working while the joints move, so phase membership cannot be
  inferred from motion at all. Each exercise therefore needs an authored, reviewed `PhaseTimeline` (phase name, start/end
  fraction of the cycle, source), and the validator checks the table against it. An entry with no timeline behind it is
  marked `reviewStatus: UNREVIEWED` and may not be displayed.
- **No contraction mode is stored.** A row says which muscle takes which role in which exercise stage; it never says the
  muscle was contracting concentrically, eccentrically or isometrically, because that is a claim about one muscle under
  one load and needs its own source (§8.1). A stabiliser listed for a squat's `ASCENT` and `DESCENT` is a statement about
  the exercise's stages, not a claim that the muscle held a constant length through them.
- **No intensity numbers without a source.** If a reviewer wants graded emphasis, it is ordinal; measured intensity is
  out of scope until there is a citable measurement protocol.

### 8.3 Authoring and review

1. Author drafts from standard anatomy references, one exercise at a time.
2. Review by a physiotherapist or anatomist: role correctness first, phase mapping second, emphasis last.
3. Reviewer signs the entry with a `source` id; that id is the audit trail.
4. Re-review when a pose's motion changes materially (a phase added or removed).

The workflow is deliberately closer to editorial review than to software validation: correctness of *content* is a
professional judgement, and the architecture should not pretend a unit test can make it.

### 8.4 Rendering and limits

- Colour by role and stage (ascent/warm, descent/cool, hold/neutral), driven by the same tier that the panel shows.
- A selected muscle gets a distinct tint; selection is presentation state (`CharacterLayerController.setSelected`).
- Activation never changes geometry (§7.6) and never changes motion.

### 8.5 Independence from motion validation

The activation table is validated by `ActivationValidator` (schema, required source, every phase present in the named
reviewed timeline, exercise coverage). It can fail while every motion rule passes, and vice versa. Neither report
aggregates the other; the UI may show both, but a green motion report never implies reviewed anatomy.

---

## 9. Anatomical visualization layers

### 9.1 Layer model

```kotlin
enum class CharacterLayer { SKIN, MUSCLES_SUPERFICIAL, MUSCLES_DEEP, SKELETON, SELECTED }
```

| Layer | Asset | Loaded when | Unloaded when | Cost when visible |
| --- | --- | --- | --- | --- |
| `SKIN` | `skin.glb` | character attaches | character detaches | 1 skinned mesh, 1 draw call per material |
| `MUSCLES_SUPERFICIAL` | `muscles_sup.glb` | first shown | character detaches, or evicted under memory pressure | 1–4 skinned meshes (regional split if bone budget requires) |
| `MUSCLES_DEEP` | `muscles_deep.glb` | first shown | character detaches, or evicted under memory pressure | same, fewer meshes |
| `SKELETON` | `shared/skeleton_v1.glb` | first shown | character detaches, or evicted under memory pressure | rigid bone meshes, one draw call per material |
| `SELECTED` | derived | always | — | tint + panel; no geometry |

`MUSCLES_DEEP` exists in the enum from the start as a reserved value; in M1 it has no asset and no renderable, and it is
not counted in M1's layers (§7.7, §9.2).

Rules:

- **Visibility is a renderable/material flag, never a rebuild.** Toggling a layer must not re-create the engine, reload
  the pose, or touch the pipeline.
- **Lifetime is one rule, not three.** A layer that has never been shown is not loaded. Once shown, its asset stays
  resident until the character detaches or the layer is evicted by the memory policy — hiding a layer frees no memory and
  showing it again costs no load. This replaces the earlier, contradictory mix of "first shown, then cached", "a hidden
  layer is not loaded" and "unload when hidden": the only thing that unloads is the character teardown or an explicit
  eviction.
- **Eviction is explicit and observable.** When the resident set exceeds the declared memory cap, the least-recently-shown
  non-skin layer is unloaded and the fact is recorded in the diagnostics report. Re-showing it reloads. The cap is a
  proposed value fixed at stage 1 on measured device memory, not a guess.
- **Layers compose.** Skin transparent at 15–30% over muscles, or hidden entirely; muscles ghosted over skeleton; the
  skeleton layer draws *inside* the body because it is a rigid overlay, not a second skin.
- **One character, one pose authority — and as many skin palettes as the limit requires.** Every layer is posed from the
  same published pose through the same retargeter, and no layer may introduce a second skeleton or a second pose. That is
  **one pose authority, not one bone buffer**: a skin has its own joint order and inverse binds, so several renderables may
  each read an explicitly owned, remapped palette (`SkinPalette`, §10.2) as long as every index has one owner and every
  palette is derived from the same pose. The earlier wording — "all layers share one bone buffer" — contradicted both the ownership
  table and the per-skin option the 255-bone limit forces.

### 9.2 Essential for the first useful version

1. Skin (the character).
2. Superficial muscles, the eight M1 muscles.
3. Skeleton overlay.

`SELECTED` exists from the start as state. **M1 ships a selected-muscle readout, not the education panel**, and the two
are named separately so a checklist cannot mean both at once. The **readout** carries **one compact row per head** of the
selected instance — muscle name, the head's authored `label` where the instance has more than one, the layer, the
categorical role where a reviewed row exists, and `relativePathLengthChange` with its "modelled" label. Biceps femoris has two heads per
side, each with its own solved path and its own length; the readout therefore shows two rows for that one selection and
**no aggregated instance value exists** — not an average, not a maximum, not "the first head". A single number for a
two-head instance would be a number no source defines and no reviewer approved, which is the failure mode §11 exists to
prevent, and the heads' lengths are genuinely independent (a long-head and short-head path differ in both attachments and
wrap). The **education panel** is stage 4: role colour mapping, per-phase prose, the role legend and the "not EMG"
disclaimer. M1's readout carries no colour mapping and no prose; the panel carries no number the readout does not already
have.

**X4 — M1 shows the relative change only; absolute metres are withheld from the UI.** *Confirmed by owner.*
`relativePathLengthChange` is dimensionless, so it does not need the calibration pair that absolute metres do — but it is
not calibration-free either (§13 question 12), and the readout therefore labels it for what it is: a **modelled**
relative change of a **modelled** path, between two frames of the same solve. `modelledPathLength` stays in the data and in
the debug overlay, where a developer can see it next to the `unitsPerMetre` it was computed with; it is **not displayed to
a user** until a calibration pair is named and an evidence-backed uncertainty exists for it. Showing metres now would
display a number whose value depends on an unsettled constant, in a panel that reads as a measurement.

**Roles are in M1, and only behind their own gate.** M1 ships categorical roles — the §7.3 `MuscleRole` enum, the §8.2
schema (source, task, population, review status), a provenance/review validator, and a display gate. A role is displayed
only for an exercise that has a **reviewed source**; every other exercise shows **no role** rather than a guess, and an
`UNKNOWN` or `UNREVIEWED` row renders as "no reviewed role". The pilot exercise list is **owner-approved and fixed for M1**:
`squat`, `standard push-up`, `bird-dog` and `row_standard` (X8). M1's definition of done requires all four in item 2 (the
animation) and all four in item 10 (the roles), so the two lists agree; the earlier "at least three" animation
requirement is withdrawn, because it allowed an M1 that could not have satisfied RV4 — the latissimus review exists only
in `row_standard`'s mid-pull pose.
What stays out of M1 is unchanged and absolute: no numeric activation value, no EMG-derived output, no per-muscle
contraction mode, no medical or therapeutic claim (§8.1, §11 hard rule 2).

### 9.3 Interaction

Projection-based picking (the AA-3D reference does this with a raycaster) is optional for M1: Filament supports picking
via renderable queries, but the value of picking without labels is low. The M1 acceptance is layer toggling; picking is
a **stage-7** item (§15 — stage 5 is morphology v2, stage 7 is labels, picking and explanations). The M1 tap hints of
§15 item 9 read an attachment landmark that already exists in the data and need no picking system.


---

## 10. Renderer and asset architecture

### 10.1 Filament, used directly — constraints as verified

| Constraint | Value | Consequence for this framework |
| --- | --- | --- |
| Filament version | 1.75.1 (`gradle/libs.versions.toml:15`, on `main` since `abc2483`) | 1.76+ needs `compileSdk 37`; the app is on 34. Upgrade is a separate change, not a prerequisite |
| Native payload | 7.4 MB arm64 stripped (`docs/CHARACTER_FRAMEWORK.md` §1) | character is opt-in packaging; a dynamic feature or flavor split is packaging work, not architecture |
| Backend | `DEFAULT`; Vulkan attempted only on explicit request, GLES fallback (`docs/CHARACTER_RENDERING.md` §5) | never assume Vulkan; the reported backend is `Engine.getBackend()` |
| Engine lifetime | one engine, one active character, `ActiveCharacter` slot (`CharacterFeature.kt:18-24`) | layers are renderables in that one scene; a second live character is out of scope |
| Pose handoff | `SkeletonPose` is reused; bone upload happens inside the producing call (`CharacterSession.kt:310-318`) | anatomy must run in the same window; no frame queue |
| Surface | `TextureView` + `UiHelper`; `Choreographer` loop started on resume | surface gaps and context loss need the documented recovery path (`docs/CHARACTER_RENDERING.md` §4) |

The renderer is **not** allowed to know about exercises, poses, muscles, activation or anatomy. It receives joint
palettes, a prefiltered list of dynamic meshes, morph weights and visible layers — no names, no statuses, no reasons.
Everything semantic is resolved before it.

### 10.2 Scene contract

```kotlin
/** Everything the renderer needs for one frame; no exercise, anatomy or muscle types. */
class CharacterSceneFrame(
    val palettes: Array<SkinPalette>,          // one per skin; each owns its joint order and remap
    val geometry: Array<DynamicGeometrySlot>,  // the prefiltered list of §7.4, one slot per drawn instance
    val morphWeights: Array<MorphWeightSet>,   // one per renderable, in the asset's own target-name order
    val visibleLayers: Int,                    // bitmask of CharacterLayer
    val selectedInstance: Int,                 // -1 for none; index into this frame's `geometry`, never a muscle id
    val selectionRenderable: Int               // -1 for none; which renderable the tint applies to
)

/** One skin's palette: column-major 4x4 per joint, in THAT skin's joint order. Never one shared joint order. */
class SkinPalette(val skinId: Int, val matrices: FloatArray, val jointCount: Int)

/** One generated mesh, owned by the anatomy stage for exactly one frame and identified by a session-stable id. */
class DynamicGeometrySlot(
    val slotId: Int,          // stable for the session: the renderer keys its buffer on this, never on array position
    val vertices: FloatArray, // 3 per vertex, engine units, ring-major
    val indices: IntArray,
    val vertexCount: Int,
    val indexCount: Int,
    val visible: Boolean      // false ⇒ clear this slot's buffer this frame (§7.4 renderability)
)

/** One renderable's morph weights, in the asset's `extras.targetNames` order (§6.4). */
class MorphWeightSet(val renderableId: Int, val weights: FloatArray)
```

**The contract carries what the layers *are*, not what they mean.** Two things the earlier four-field version could not
express and the later stages require: **per-skin palettes** (§9.1, §10.3) and the **per-instance filter** of §7.4.

`palettes` is a list keyed by `skinId`, not one array with an appended helper range. One pose authority, one palette per
skin: a skin has its own joint order, inverse binds and remap, so `MuscleLayerSkinner.write` writes into the palette of
the skin it was built for (§5.3) and each renderable names the palette it reads. That is what makes the v2 regional
split expressible — three skins carry three palettes with independent helper ranges and index orders, and no consumer
assumes the orders coincide. A single shared buffer remains an option (§5.1), not the contract, and no field of
`CharacterSceneFrame` presumes it.

`geometry` is the prefiltered list of §7.4: `CharacterSession` builds it from `AnatomyFrame.renderable` and
`AnatomyFrame.paths`, one slot per drawn instance, and the renderer never receives a `MuscleState`, a `PathStatus` or a
muscle id. Each slot's `slotId` is **stable for the session** so the renderer keys its GPU buffer on identity rather
than on position in the array — which is what lets one invalid muscle disappear while its neighbours keep drawing. A
layer bit cannot do that: it hides a whole layer.

| Handoff fact | Rule |
| --- | --- |
| producer | `CharacterSession`, in the same call that solves anatomy and uploads the palettes (§5.2 steps 4–5), inside the carrier's valid window |
| lifetime | one frame. The renderer copies vertices and matrices into its own buffers during that call and holds no reference afterwards; the frame's arrays are the anatomy stage's reusable buffers and are overwritten by the next solve |
| clearing | a `slotId` in the renderer's map that is **absent** from this frame's `geometry`, or present with `visible = false`, is cleared before the frame is presented — no fade, no last-good tube (§7.4) |
| prefiltering | `geometry` contains only instances whose `renderable` entry is `true`. That list is the only mask the renderer consumes, and it never re-derives one from a status array it cannot see |
| selection | `selectedInstance` indexes the list the renderer just received; `-1` means none. Selection is a material parameter and moves no vertex (§7.6) |
| anatomy semantics | none cross this boundary: no exercise, pose, muscle, role, reason or status |

If a future renderer (or a thumbnail renderer, or a test double) can consume `CharacterSceneFrame`, the framework is
correct. If a caller needs to pass a `SkeletonPose` into the renderer, the layering has been violated.

### 10.3 Asset contracts per layer

| Layer | Contents | Budget (standard / reduced) | Required bindings |
| --- | --- | --- | --- |
| skin | body mesh, morph targets, materials/textures | 30–40 k tris / 12–18 k; 2K / 1K textures | full rig, ≤ 4 influences |
| muscles superficial | muscle meshes, helper bones, tendons as material regions | ~1.2–1.8 k tris per muscle at v2 (~35 muscles); the eight authored meshes at stage 3 fit in 10–14 k | 4 helpers per head, ≤ 4 influences |
| muscles deep | muscle meshes, helper bones | ~0.8–1.2 k tris per muscle at v2 | same |
| skeleton overlay | rigid bone meshes | 8–12 k / 4–6 k | parented to mapped bones; no skinning |

Budgets are *ceilings to validate against*, not promises: the first asset that exceeds one fails the asset validator
with a report, and the budget is revised in this document rather than silently exceeded.

**The M1 procedural layer's budget is a formula over instances, not a sample count.** A tube is generated from the
polyline, so its cost is set by longitudinal samples, ring tessellation and the number of **instances** — muscles ×
sides × heads, not muscles:

```
triangles per tube = 2 × (N_final − 1) × R                     // N_final ≤ pointCapacity, R = ring sides
cap                = R triangles per capped end                 // a fan from the R ring vertices to one centre vertex
caps per tube      = 2 × R                                     // two ends
M1 total           = Σ over EVERY instance (both sides, every head) of that, plus its caps
```

The previous revision counted eight *heads* where there are sixteen *instances*: eight bilateral, single-head muscles at
`N = 48`, `R = 8` is `16 × 2 × 47 × 8 = 16 × 752 = ` **12,032** triangles, and **12,288** with a cap at each end of every
tube — not 6.0 k, which is half the object. The canonical M1 set (§7.7.1) is **18** instances rather than 16, because
biceps femoris has two heads, so its uncapped cost is `18 × 752 = ` **13,536** and **13,824** with caps. A two-head muscle
adds a tube, not a second muscle.

**The cap topology is part of the number.** A fan cap is `R` triangles per end, so two caps add `2R = 16` per tube:
`12,288` for 16 instances and `13,824` for 18. A rule that added `2R` **per end** (four times `R` per tube) would give
`12,544` and `14,112` instead — 256 and 288 triangles more — so the topology has to be pinned before the total means
anything, and it is: one fan per end, no inner cap, no duplicated ring. A flat sheet uses a
two-sided strip instead of a ring and is covered by neither this formula nor the clearance guarantee (§7.4.1): its cost
is `2 × (N_final − 1) × (W − 1)` quads for a `W`-station width, and its own envelope must be declared before it can be
budgeted or certified. Both `N` and `R` are declared in the quality tier, so the number is bounded before any device run.

**48 is the initial resampling cap, not a ceiling on the final polyline.** §7.4 step 3 resamples the control polyline at
`N ≤ 48`, and step 4 then **inserts** detour points, so `N_final > N` is normal. The buffer is sized by `pointCapacity`,
which is declared **per quality tier** and must be `≥ N_final`; the budget is therefore

```
M1 total = Σ over instances of [ 2 · (N_final − 1) · R + 2R ]      with N_final ≤ pointCapacity(tier)
```

and a tier that raises `pointCapacity` raises the total linearly. Budgeting at `N = 48` while the buffer admits 60 points
under-counts by exactly the inserted points, which is the defect the `pointCapacity` field exists to remove (§5.3).
It is arithmetic, not a measurement: stage 1 records frame time and memory as information, not as a gate (§12.3), and a
measured number replaces the estimate when one exists.

**Bone budget: the number is known, and it makes the v2 split mandatory.** Filament's documented limit is **255 bones per
renderable** (`filament/include/filament/RenderableManager.h:419-450`, "up to 255 bones"; the Java builder is
`@IntRange(from = 0, to = 255)`), while the implementation constant is **256** (`EngineEnums.h:166-168`, enforced by a
`FILAMENT_CHECK_PRECONDITION` at `components.cpp:1037-1038`). Plan with **255** and record the discrepancy rather than
relying on 256. `gltfio` passes the skin's **full joint count** to the renderable (`AssetLoader.cpp:926-928`), so
splitting a mesh into several primitives does **not** reduce a shared skin's joint count: separate skins with their own
remapped palettes are what reduce it, and those palettes are what `CharacterSceneFrame.palettes` carries (§10.2).

**313 is a hypothetical, and the quantity that actually binds is the per-skin palette.** The often-quoted
`35 × 2 × 4 + 33 = 280 + 33 = 313` assumes 35 bilateral single-head muscles, four helpers per head on both sides, and one
skin carrying all 33 base joints. It is a scale reference, not a budget: the number a renderable is checked against is

```
palette(skin) = base + visual joints on that skin
              + 4 × (head-side instances assigned to that skin)
```

which is a property of the **regional split**, not of the library size. A split that assigns a third of the instances to
each of three regional skins carries roughly a third of the helpers each, and the split therefore has to be designed
against the palette number rather than derived from 313. The 255 limit is what makes the split mandatory for v2 — not the
specific total.

**M1 has no helper bones at all.** Strategy A generates the tube geometry directly (§7.5, §7.6), so an M1 muscle layer is
one dynamic mesh per layer and its skin carries **zero** helpers; the palette is the 33 base joints plus whatever visual
bones the body rig adds. That is why M1 needs no split, and it is also why the later-stage experiment below is a
*v2* measurement rather than an M1 one. **The two statements are consistent by stage, not in conflict**: "one dynamic
mesh per layer" is the M1 (strategy A) case with no helpers, and "regional renderables with their own remapped palettes"
is the v2 (strategy C) case where helper bones push the palette past 255 — no stage has both.
FPS and thermal numbers remain informational (§12.3) and no stage gate depends on them.

### 10.4 Loading, caching, export and thumbnails

- **One GLB per layer.** Loaded with gltfio `AssetLoader`/`ResourceLoader`, memory-mapped, uncompressed
  (`noCompress += "glb"`, `app/build.gradle.kts:71`). Versioned by `assetVersion` in the manifest.
- **Offline export uses the runtime's own maths.** The tool that places helper bones and validates muscle bind poses is a
  JVM CLI module that depends on the pure-Kotlin `anatomy` package and the **X1 bind frame** (§6.6). A Python
  re-implementation would be a second source of truth for attachment and path maths and is explicitly rejected.
- **There is one bind frame, and it is X1.** The exporter builds the canonical A-pose from `SkeletonDefinition` in
  character-side code — the same builder the runtime uses — and asks the anatomy solver for `restLength` and helper
  positions at that frame; the GLB's inverse binds are computed from those positions. This is the only way the rest
  invariant (bone matrices = identity at rest) can hold for muscles as well as for the body, and it is why §10.5 checks
  the exported bind against X1 and not against a published pose.
- **How the synthetic frame reaches the solver without an engine change.** X1 is a `SkeletonPose` *value*: the builder
  fills one from the definition (positions, identity rotations, `jointCount` entries) and `AnatomySolver.solve` is called
  with it exactly as it is called with a published pose. The solver reads positions and never asks where a pose came
  from, so no engine type, factory or pipeline stage changes: nothing publishes X1, no builder is modified, and the
  frozen engine retains no frame for it. The earlier text called the exporter's input "a rest-stance pose" and "the
  engine rest stance", which named neither the frame nor its provenance and left the bind check of §10.5 comparing
  against a frame the exporter was never told to use.
- **Thumbnails stay 2D** until a static-render pipeline exists: the proposal in `docs/CHARACTER_RENDERING.md` §7
  (one offscreen engine, one frame per key, LRU cache, 2D fallback) remains the plan. No animated 3D thumbnails.
- **Surface lifecycle** is the existing `CharacterSession.destroy()` contract; layers add nothing to it except unloading
  their assets first.
- **Quality tiers** select assets, not code paths: `standard` and `reduced` share rig, bone map, anatomy and motion.
  `reduced` may use strategy A (procedural tubes) for muscles where C's asset cost is unaffordable.

### 10.5 Asset validation requirements

Each GLB is validated before it can be referenced by a profile (offline, plus a JVM test that parses the checked-in
assets). **The obligations are conditional on what a layer is**, because a rigid overlay has no skin: applying "inverse
binds, weights, ≤ 4 influences" to `skeleton_v1.glb` either rejects a valid layer or leaves its real checks unstated.
The `Applies to` column is the contract, and a check whose subject a layer does not have is not run against it — not
quietly passed either, which is why the column names the layer rather than the check's name being assumed.

| Check | Applies to | Failure mode it prevents |
| --- | --- | --- |
| Required bones present and mapped (`BoneMap`) | every skinned layer (skin, muscles at v2); for the overlay, its parented nodes resolve to mapped bones | silent rest pose for a limb |
| Bind pose == the X1 canonical A-pose (§6.6), rest invariant, all bones ≈ identity | **skinned layers only** — the overlay is parented, not bound, and has no bind frame to compare | figure in T-pose / sheared at frame 0 |
| Inverse bind count == joint count, column-major float, no NaN | **skinned layers only** | garbage skinning on some drivers |
| Node hierarchy acyclic, one skeleton root | skinned layers (one root); acyclic only for the overlay | gltfio skin failure |
| Morph targets: same count on every primitive of a mesh; names match the manifest; deltas finite | **the skin layer**; a muscle layer carries matching targets only from stage 3 (§6.3), and M1's is generated, not authored | morph crash / wrong body |
| Weights: sum ≈ 1, ≤ 4 influences, no zero-weight vertices | **skinned layers only** | collapsing vertices |
| Triangle and texture budgets per tier | every layer, against its own §10.3 row | unaffordable asset |
| Bone budget per renderable (measured, not assumed) | **skinned layers**, on `palette(skin)` (§10.3) — the overlay has no palette | runtime skin failure |
| No embedded animation clips (the character is pose-driven) | every layer | two sources of motion |
| No ground/helper nodes that the presenter must exclude by name | every layer | the PR #385 asset's `Ground` node is excluded by `skinnedNodes`, which is the right rule — extend it to all layers |
| Anatomy attachment validity for every muscle in the layer (§12 L3) | layers that carry muscle definitions (v2 authored layers) | a muscle attached to a joint that does not exist |
| `assetVersion` / `anatomyVersion` / `rigVersion` recorded and matched | every layer | mixed-version skinning |

**M1's muscle layer is not a GLB and is not validated as one.** Strategy A generates the tube mesh per frame (§7.4,
§7.5), so the M1 muscle layer has no skin, no inverse binds, no morph targets and no bind frame; its structural checks
are the L5 M1 subset of §12.5 — finite coordinates, no zero-area ring, triangle count equal to the §10.3 formula, one
fan cap per end — run by `ProceduralTubeBudgetTest`. The skin and the skeleton overlay are GLBs and take the rows above
that name them. An earlier revision applied every row to every layer, which is why the two statements looked
contradictory: the generated layer satisfied no row because it has no asset, and the rigid overlay satisfied none
because it has no skin.

### 10.6 Materials, lighting, shadows

- **Materials:** glTF PBR metallic-roughness, three material families per character (skin, muscle, bone). Muscle and
  bone share the chart-like treatment the 2D renderer implies: matte base colour, depth-darkened edges, no gloss.
- **Lighting:** one directional key light plus a low hemisphere fill, one shadow map (`docs/CHARACTER_FRAMEWORK.md` §7).
  No post-processing beyond tone mapping in v1; no screen-space effects, because they would have to be re-validated on
  every device tier.
- **Skin** is the hard case: Filament's standard material has no subsurface scattering, so skin will read as plastic
  unless a custom material is written. v1 accepts that limitation and states it in the accuracy table; a custom skin
  material is a later, separate change with its own device evidence.
- **Muscle "chart" look** (fibre striping, tendon bands, activation tint) is a material feature, not geometry. It must
  read from per-vertex or per-material data that the anatomical model supplies, never from a second animation.

---

## 11. Accuracy and anti-fake-mechanism policy

Every mechanism in this framework is classified. The classification is part of the contract: a reviewer may reject a
change that quietly promotes an approximation to a claim.

| Mechanism | Classification | Stated limitation | Validated by |
| --- | --- | --- | --- |
| Analytic two-bone IK | established technique | no soft-tissue, no joint-surface contact | existing validator (L1) |
| Contact settlement / posture CCD | established, bounded | iterative, not a physics solve | L1 + RFC R12 |
| Position-driven retarget (X3) | established technique | rotations derived from two published positions plus a fixed `Ry(+90°)` basis; translation only at the pelvis, **taken into the asset basis and divided** by `unitsPerMetre` (§6.2); the roll about the segment direction is the same approximation §7.2 carries, and continuity at an antiparallel bind direction is not promised | L2, and **not by the rest invariant alone**: `PoseRetargeterRestInvariantTest` proves rest identity, which `basis = IDENTITY` also satisfies, so it validates nothing about the basis; `RigLandmarkAlignmentTest` (A1) catches a wrong or missing basis **in the static converter only** — it performs `s · Ry(+90°) · p_asset` itself (§6.6), so a correct converter passes whatever transform the runtime applies — `RigSideTest` catches a side flip, `PoseRetargeterPelvisTranslationTest` catches a reversed conversion **or a displacement left in engine axes**, and A2 is **measured** at the five §12.4 RV4 frames drawn from four registered builders — five frames of 66 builders, a sample and not a statement about the other 62 (§6.6). The runtime basis is asserted where it actually runs: `PoseRetargeterBasisTest` drives a posed frame through the real construction and upload path with a non-identity basis, a parent swing simultaneous with a child direction change, the pelvis root and the zero-length mapped joint, and a wrong-basis control |
| Segment frame from published positions + declared roll | approximation | the roll about the segment direction is arbitrary, so an attachment offset of `d` metres carries up to `d · θ` of error (chord `2\|d\| sin(θ/2)`). **`θ` is not yet measured and adequacy is not claimed**: stage 1 measures it per segment against the pole-vector comparator, with `τ_roll` set there (§13 question 11). **Four of fourteen** segment rows take their parent's frame in every frame measured (`pelvis` as the derived root, `clavicle` the only zero-length segment, `skull` and `toes` terminal), the `hand` row in one builder of 66, and the `pelvis` root uses the derived hip-line frame so an authored pelvis tilt is followed (§7.2) | `SegmentFrameDirectionTest` (L2) |
| Bone-name resolution with aliases | established technique | alias tables can map the wrong bone | L2 coverage + manual rig review |
| Anatomical attachment points | **requires expert validation** | a plausible-looking point can still be wrong | L3 mechanical + L7 review (§12.4) |
| Path + ellipsoid obstacle wrapping | approximation — geometric, not contact | ellipsoids stand in for bone and fascia; the clearance predicate is measured with a stated error budget (`eps_num`), and the approximation is the obstacle model, not the arithmetic | L4 fixtures + visual review |
| Euclidean segment-to-ellipsoid clearance | established technique (distance), surrogate object | the guarantee is the **generated modelled tube** against the **declared model obstacles**, with declared attachment-contact exemptions — and it is **conditional**: on the declared operand bound, on a per-operation error analysis of both moving geometries, on bounded minimiser comparisons, and on a **bound** for the point-to-ellipsoid solve, which is today a residual estimate that exceeds itself on a represented input (§7.4.1b-1). Failing closed to `UNCERTIFIED_MINIMUM` when any condition does not hold means **no numerical clearance floor is claimed in this revision**; the number that would follow is stated as one condition (d) owes (§7.4.1). No claim about real bone, fascia or skin, no claim about the swept volume between frames, and no claim about a morphed or sheet muscle | L4 fixtures + `CLEARANCE_VIOLATION` rows |
| Arc-length measurement along the path | established technique | depends on path sampling density | L4 |
| Muscle cross-section response | **not implemented — research hypothesis only** (§7.6) | no validated per-muscle deformation model; the cylinder law is a plausibility argument. Linear-blend skinning still stretches the mesh between separating helpers — a skinning artifact, not a modelled response | none — not a validation target |
| Morph-target morphology | visual approximation | surface only; the vertex ring around a joint pivot can degrade and no joint-space rule sees it, which is what the L6 ring test measures | L6 |
| Corrective shapes | visual approximation | authored per rig; not anatomy | L6 + visual |
| Activation colouring | **educational model, not physiology** | no EMG, no measured intensity, no per-frame claim | §8 schema + expert review |
| Organ motion (future) | research required | no defensible mechanical driver at this stage | not planned |
| Skin/muscle self-intersection | unverified | no runtime collision; mesh checks are offline only, and M1 ships no mesh-intersection test | L5 from stage 3; L7 judgement in M1 |
| Anatomical review of attachments and placement | **required human gate** (§12.4) | professional judgement against named references; recorded, never scored | L7 record |

**Hard rules.**

1. No muscle geometry moves for a reason that is not in the ownership table of §7.6. Path following is the only
   anatomy-derived motion; morphs and correctives are character-owned, must not touch `AnatomyFrame`, and are not anatomy;
   an `INVALID` path is reported, never drawn as if it were valid.
2. No numeric activation value exists in M1, cited or not — roles are categorical and tiers are ordinal, and each entry
   carries its source (§8.1). A number that looks measured is a claim, and this framework makes none.
3. No balance, contact or ground model is introduced in the presentation path. The character consumes the published
   pose; it does not re-solve it.
4. No claim of anatomical correctness is derived from joint-space validation. L1 passing says nothing about a surface.
5. No mechanism may be described in UI copy as "simulated", "measured" or "real-time physiology".
6. No milestone passes its anatomical gate on automated results alone. The automated levels its applicability row
   requires (§12.5) are a precondition for the §12.4 review, not a substitute for it.

---

## 12. Validation architecture

Seven levels, each with one owner, one report and an explicit automation status. They never merge into one score. The
**M1** column says what the milestone actually gates; §12.5 defines the subsets.

| Level | What it checks | Automated | In M1 | Failure means |
| --- | --- | --- | --- | --- |
| **L1 Motion** | 18 existing rules over joint-space geometry (`ExerciseValidator.kt:47-92`) | yes, unchanged | all | the pose/engine is wrong; block |
| **L2 Rig** | bone coverage, rest invariant, inverse binds, side/handedness, unit scale, bone budget, landmark alignment (§6.6) | yes | all | the asset or map is wrong; block |
| **L3 Attachments** | every attachment's joint exists; the offset is **authored in the segment's derived frame (§7.2)** and every attachment — origin, insertion **and waypoint** — carries `Attachment.frame` naming that convention rather than leaving it to be inferred; offset inside a segment envelope; L/R mirror; head count; origin ≠ insertion; span ≥ 2 segments. The frame's **sign** is a review fact, not an L3 fact: L3 can check that a declared frame is the one the resolver applies, and an attachment whose authoring frame is `UNRECORDED` puts its whole head-side instance in **frame unverified** rather than passed (§7.3, §12.4 RV1) | yes | all | the anatomy data is wrong; block |
| **L4 Path** | every segment of the final polyline tested against every obstacle in the head's ordered union, in one exhaustive three-stage state machine — bounding-sphere clear, a quadratic **intersection test that requires a witness station** (a chord whose entry cannot beat the coefficient error, or cannot carry a proved enclosure for it, routes onward and is never called an intersection), then the finite-segment minimum of `d(t) − r(t)` (**solid** ellipsoid distance) with a Lipschitz bracket, the radius taper split at every knot, and `UNCERTAIN` failing closed — plus the sample-outside check; unverifiable geometry reported `INVALID` with a reason from the one table; deterministic outcome for overlapping, nested and closed routes; tangent and curvature continuity at entry/exit (gated); a **solver-derived rest length that is finite, strictly positive and reproducible** for every head (§7.4.4), and — **only where the definition or the review names one** — a comparison against a sourced external reference recorded with source, task, population and uncertainty, where a missing reference is an evidence gap and neither a pass nor a blocker. The continuity observer is **diagnostic only** and is not part of this gate (X6). The clearance floor itself is **conditional** on the input bounds, the error analysis of §7.4.1 and a bound for the point-to-ellipsoid solve — which §7.4.1b-1 does not yet have, so **no numerical floor is claimed until it does** | yes, with adversarial fixtures | all | the solver or the data is wrong; block |
| **L5 Mesh** | self-intersection, degenerate triangles, weight sums, volume sanity per muscle, helper-bone bind match | partly (tool) | **subset** (§12.5) | the mesh is wrong; block release, not build |
| **L6 Morphology** | declared ranges respected per target; **mesh** ring/mask vertices relative to joint pivots within `ε_morph`; delta bounds | **yes — the morph and ring checks only** | **subset** (§12.5) | a preset breaks the surface the muscles sit under; block the preset. A bone-landmark rest invariant is **not** an L6 assertion: a vertex-only morph cannot move a bone, so it is tautological. **"Anatomy still inside the body" is not automated and is not L6**: it needs a mesh-intersection tool M1 does not ship, so it is an L7 RV2/RV3 judgement (§12.5, §13 question 9) |
| **L7 Anatomical review** | RV1–RV6 in §12.4: attachment locations, position vs bone and body surface, visible intersections, shape/path plausibility across poses, preset consistency, provenance | no — human, **required for M1** | all | the model is not accepted; M1 does not ship |

### 12.1 What is genuinely automatable

- L1 (already), L2 (fully), L3's mechanical half (existence, envelope, mirror), L4's numeric half, L6 (fully), and L5's
  geometric half. These are the parts a CI job can gate.
- L4's numeric half is a **measurement with a stated error budget** (`eps_num`, §7.4.1), not an exactness claim and not a
  sample census. What it still cannot certify is the anatomical surface the ellipsoids stand for, the frames it was not
  run on, a morphed or sheet muscle, or a rendered curve that is not the validated polyline.
- **Not automatable:** whether an attachment is the *right* anatomical point; whether a wrap produces a believable
  contour; whether a muscle mesh reads as that muscle; whether a path looks plausible in a pose nobody posed a fixture
  for. Any claim otherwise is false confidence, and this document refuses to design a "score" that hides it. §12.4 is
  where those judgements are recorded.

### 12.2 New test inventory (names, not implementations)

| Test | Level | Asserts |
| --- | --- | --- |
| `RigAssetContractTest` | L2 | every required bone present, bind == rest, inverse binds finite, ≤ 4 influences |
| `PoseRetargeterRestInvariantTest` | L2 | neutral pose → identity on **every** bone — mapped and visual-only alike, so the 10 finger bones are asserted at identity too (they inherit the hand, and at bind the hand is at rest); **the class does not exist yet** — the behaviour is the existing `PoseRetargeterTest.theNeutralPoseReproducesTheIdentityOnEveryBone`, and stage-1 PR P4 (§15 stage 1) extracts it under this name and re-points it at `humanoid_v2` (§3.1). **It cannot see the basis**: identity composed with identity is identity for any basis, which is why the next two rows exist |
| `PoseRetargeterBasisTest` | L2 | §6.2 X3 through the **real** construction and upload path (`PoseRetargeter.boneMatrices`, then the upload that actually follows it — `boneBuffer.put(boneMatrices)` and `renderableManager.setBonesAsMatrices(renderable, boneBuffer, jointCount, 0)`, `CharacterSession.kt:317-325`; the scene-frame handoff of `SceneFrameHandoffTest` is a **stage-2** test of the geometry layer and this row does not wait on it): a **nonzero, asymmetric** posed frame with `basis = Ry(+90°)` yields the published segment direction **and** the parent's posed roll; a **transport-only** control (`B = Ry(+90°)`, parent `Rz(+90°)`, bind `+X`, published child `+Y`: the transported bind direction is already `+Y`, so the correction is **identity** and the frame lands on `+Y`, whereas the untransported composition lands on `−X`) — this control isolates the parent **transport** and cannot see the basis, which is exactly why the next control exists; a **both-nonidentity** control in a basis that does not commute with the parent swing (`B = Ry(+90°)`, parent posed `Rz(+45°)` in asset space, asset bind direction `+X` so `d_bind_engine = −Z`, published child `normalize(0.3, 0.9, 0.3)`): `a = (0, 0.70710678, −0.70710678)`, `D` is a **64.7606°** rotation about `normalize(0.94280904, −0.23570226, −0.23570226)`, the composed rotation places the bind direction on the published one exactly, and each wrong composition is pinned by its own operands and multiplication order, with `P` the transported asset parent `Rz(+45°)`, `D₁` the correction computed from `P · d_bind`, and `D₀` the wrong correction computed from the untransported `d_bind` alone: the **reversed order** `P · D₁` misses by **3.421085408°**; **`P · D₀`** — untransported correction *and* wrong order — by **42.799698458°**; **`D₀ · P`** — both wrong — by **45.000000000°**; and **dropping the asset-space conjugation**, i.e. using `D₁ · P` as the asset rotation, by **86.318091591°**, whose engine image is `B · D₁ · B⁻¹` and **not** `D₁`, so an engine-space assertion through the handoff distinguishes it as an asset-space one does; the untransported control also displaces the frame's roll by **13.276°** — every figure reproduced in the review script; a parent rolled about an unchanged child direction moves the child's frame; the **pelvis root** publishes the **bind-relative delta** on the actual pelvis path (squat bottom, bird-dog): `R_target_world(PELVIS) = B⁻¹ · ΔF_root · B · R_rest_world(PELVIS)` with `ΔF_root = F_root(posed) · F_root(bind)⁻¹`, so a posed root `F_posed = Rz(90°) · F_bind` must place the root's own axes at `B⁻¹ · Rz(90°) · B` and **not** at `B⁻¹ · F_posed · B` — the two differ by **180°**, because `ΔF_root = I` at bind while `F_root(bind) = diag(−1, 1, −1)` — and `R_target_world(PELVIS)` equals `R_rest_world(PELVIS)` at bind, which an assertion on the absolute derived frame cannot also satisfy; and the **zero-length mapped joint** (`CLAVICLE`) carries its parent's frame without a normalisation, while `LUMBAR`'s `D` is asserted **identity by construction** — its segment is `LUMBAR → CHEST` and a rigid child direction the root frame already carries (§6.2, §7.2). Identity and bind-rest cases cannot pass this row |
| `PoseRetargeterPelvisTranslationTest` | L2 | §6.2: a pose whose `PELVIS` sits **235 units** above rest translates `Hips` by **0.95 m** (`235 / (235/0.95)`), not by `235 · s ≈ 58,131.58`; the fixture uses a **non-zero** translation, because a test at zero displacement cannot see a reversed conversion. **A vertical displacement alone cannot see a missing basis**, because `(0, 235, 0)` lies on `B`'s axis, so the fixture carries a **non-vertical control**: an engine displacement of `(235, 0, 0)` must move `Hips` along **engine `+X`**, which the raw `/ s` value does not (it lands on engine `−Z`), so dividing without `B⁻¹` fails the control while a reversed multiply still fails the vertical case |
| `RigLandmarkAlignmentTest` | L2 | §6.6 A1: every **mapped** bone head coincides with its engine joint at the X1 bind frame within `τ_bind`; rest orientations within `τ_rot`. The 10 finger bones are **excluded from the comparison and listed as unmapped** (X2) — their report entry is inventory, not a residual, and keeping them cannot move the hand's own row; expected **red** on `humanoid_v1` against the recorded baseline (§6.6) |
| `RigAlignmentResidualTest` | L2 | §6.6 A2: the **measured** residual per joint at the five named **frames from four registered builders** — squat bottom and top (`squat_standard`), push-up bottom (`pushup_standard`), `birddog_hold`, `row_standard` mid-pull — within `τ_residual`; the equal-length/different-direction control case must report a non-zero residual, no length-sum bound is computed, and the report states both counts (five frames, four builders) rather than calling the set five builders |
| `SegmentFrameDirectionTest` | L2 | §7.2/§7.4 step 1: a derived segment direction equals the published direction `p_child − p_parent` within `τ_dir` (proposed, fixed at stage 1); the roll convention is declared per segment and reproduced; run on real baseline poses over both hierarchies, both sides of every bilateral limb, one deliberately bent IK case, the measured degenerate rows — `clavicle`, `skull` and `toes` **inherit** their parent's frame, and `pelvis` is the **derived root**, not an inherited row — with no zero-vector normalisation, and the manually overridden hand of `pike_pushup_standard`; plus near-parallel, near-antiparallel from both sides **with its `arcsin(τ_rot)` direction residual and the identical half-turn both sides must produce** — the row asserts **no flip inside the branch**, and asserts the branch-boundary crossing `\|a × b\| = τ_rot` separately as a **roll** flip **with a bounded direction residual**: the placed direction changes by `arcsin(τ_rot)` there (`0.057295789°` at `τ_rot = 0.001`), which the row asserts instead of continuity — the **non-axis-aligned antipode** (`a = (1,1,1)/√3`, `b = −a`), **parent roll**, zero length, the derived root frame (orthogonalised construction) and straightening per X9 |
| `SegmentFrameRollComparatorTest` | L2 | §13 question 11's comparator policy, not a value of `θ`: the pole is re-seeded from the frame's **own** bend plane on every well-conditioned frame, so a limb rotating rigidly between two valid frames (bend normal `+Z` → `−Y`) is measured against its new plane and a held pole fails the row by reporting a 90° error that does not exist; a **direct seek** or replay to a well-conditioned frame resets the pole, reports `POLE_UNINITIALIZED` for the frame that observes it and measures from the next, never comparing across the jump; a **collapsed leg** reports `POLE_LEG_DEGENERATE`, never a division and never `POLE_HELD`; and where the policy withholds `θ` the report carries `UNAVAILABLE` with its reason and the precedence order of §13 question 11, never `0` |
| `NonCommutingSplitTest` | L2 | §6.2: a parent and child about different axes compose to the stated world rotation — a reversed `parent⁻¹ · child` order or a one-sided `B` fails |
| `UnmappedBoneInheritanceTest` | L2 | §6.2: moving a mapped parent carries an unmapped child's world rotation within tolerance while the child's local rotation is unchanged; the twist policy is the declared one |
| `BoneMapCoverageTest` | L2 | **23** mapped joints resolve and the **10** undrivable engine joints are declared, not accidental (X2) — derived from the required map of §6.2, not from the shipped asset's 21/12 inventory. The scapula pair is asserted by name, so a `BoneMap` that quietly omits `SCAPULA_A/P`, or a rig that carries no scapula bone, fails here rather than at A1 |
| `RigSideTest` | L2 | `Left*` bones on the same side as anatomy side A in the rest pose |
| `AnatomyDefinitionTest` | L3 | ids unique; attachments resolve; envelope bounds; L/R mirror; heads consistent |
| `ActivationDefinitionTest` | L3 | §8.2/§9.2: every displayed entry carries `source`, `task`, `population` and `reviewStatus`; every phase an entry names exists in the `PhaseTimeline` it cites; a `role` of `UNKNOWN` or a `reviewStatus` of `UNREVIEWED` is refused to the display path rather than rendered; an exercise with no reviewed row yields no role; and — the half that makes the gate real — an entry that is *structurally* valid but names an exercise outside the four X8 pilots is not displayed as reviewed. It asserts the schema and the gate, never a physiological value (§8.1) |
| `MusclePathGeometryTest` | L4 | arc length ≥ chord; every segment of the final polyline is CLEAR of every obstacle in the head's union under the three-stage state machine of §7.4.1, with the solid-distance predicate and the Lipschitz bracket — reported as conditional while the point-to-ellipsoid error is an estimate (§7.4.1b-1); every **non-exempt** sample lies outside the surface by at least `e`, read through the same `(head, obstacle, arc-length interval)` transition-envelope mask that construction, projection and the endpoint check use (§7.4.1a), so both `exempt_origin_success` and `exempt_insertion_success` pass while a re-entry after the envelope, or after a path mutation, still fails; repeatable across runs and buffer reuse on one device and configuration |
| `MusclePathObstacleFixtureTest` | L4 | the validator fixtures (§12.2.1) return the expected verdict, and the solver fixtures return the expected final path or the expected failure reason — never silently |
| `failingObstacleIndexOrderTest` | L4 | §7.4.2: `failingObstacles` is a **definition** index — reordering `wrapSurfaces` and flipping another muscle's `global` flag between runs leaves the reported index naming the same obstacle; `AnatomyFrame.obstacleIndexMap[head]` maps the same union position to it and moves when the order moves; an obstacle absent from the head's union reports `NO_INDEX`, never `0`; and the map is **not** rewritten by `clear()` or by a solve, because it is definition data |
| `MusclePathContinuityTest` | L4 | C0, tangent bound at entry/exit, curvature bound (gated); and, for the **observer's own correctness** (diagnostic, X6): a rigid translation of the whole body produces no alarm; a rotating or moving wrap is attributed and produces no alarm; an inserted sample does not shift a station; a deliberate branch switch alarms; the first frame produces no verdict; pause, resume, seek, backward time and a non-monotonic timestamp each reset rather than express a negative displacement; an `INVALID` frame resets the comparison and is recorded as a reset; `valid → invalid → valid` restores the path and does not compare across the gap; and no path is ever rate-limited |
| `MuscleLengthDeterminismTest` | L4 | the same pose produces the same length **bit-identically** across runs, buffer reuse and definition reload. **No temporal-continuity bound is asserted here**: a length or path change across a sampled motion is a diagnostic row from the continuity observer (§7.4.3), not a verdict, because X6 keeps the observer out of every gate and a route-branch switch can change the path discontinuously with no defensible universal bound |
| `MuscleLengthReferenceTest` | L4 | length matches expert-reviewed reference data **where such data is named**, within the stated tolerance; where it is not named the row reports an evidence gap, which is **not** a pass and **not** an M1 blocker (§7.4.4) |
| `MuscleLengthScaleTest` | L4 | §13 question 12: a common similarity transform of the body, the attachments and **every** dimensional solver parameter leaves `relativePathLengthChange` unchanged (`scale_common_control`), while a recalibration at a different `unitsPerMetre` changes it (`recalibration_counterexample`) — the two together are what stop "dimensionless" being read as "calibration-free". It also **exercises the threshold branch**, not only the ratio: a common transform that keeps `\|restDir\| · s > 1` leaves the degeneracy verdict unchanged, and one that crosses the fixed floor in `τ_len · max(1, \|restDir\| · s)` must report the change (D4) |
| `MuscleDirectionalExpectationTest` | L4 | only exercise- and muscle-specific expectations, each carrying a documented justification and source |
| `MuscleRestLengthTest` | L4 | §7.4.4's **mandatory** half: the solver-derived rest length is finite, strictly positive and **reproducible** for every head — the same value across runs, buffer reuse and definition reload, computed at the X1 bind frame (§6.6). A zero, negative or NaN value reports `REST_LENGTH_INVALID` rather than dividing by it. The **conditional** half — a comparison against a named, sourced reference — is `MuscleLengthReferenceTest`, not this row: requiring a measured reference for every M1 head would contradict the evidence-gap rule of §7.4.4 |
| `MuscleRenderabilityTest` | L4 / presentation | §7.4: an `INVALID` instance is masked and its geometry cleared in the same frame; **one invalid biceps-femoris head hides the whole instance and both sides keep independent masks**; the mask is the conjunction of the heads' statuses and `NOT_PROCESSED` is not renderable; an early return leaves every instance unrenderable rather than stale; valid → invalid → valid restores the path and the mask across carrier reuse; and a two-head instance with one origin exempted and one head in violation is unrenderable, because the exemption is a per-head fact and the mask is per instance (§7.4) |
| `AnatomyFrameLayoutTest` | L4 | §5.3: the `3 · headCount · pointCapacity` stride addresses every point of every head; `pointCapacity` is the post-insertion maximum; an over-capacity head reports `BUFFER_CAPACITY` instead of growing or truncating; `clear()` leaves every slot `NOT_PROCESSED` in **status, reason and `searchOutcome`** and every `renderable` entry `false`; a **fresh, never-cleared** buffer reads `NOT_PROCESSED` in all three arrays, because `NOT_PROCESSED` is the declared zero value of all three (`PathStatus`, `PathReason`, `SearchOutcome` — §5.3, §7.4.1c) and not an accident of one enum's ordering; a reason array read through `PathReason.entries` returns the reason the producer stored, **not** the reason's row number in the precedence table; a solve that returns early leaves the untouched heads `NOT_PROCESSED` rather than `VALID`, `INVALID` or a stale outcome; a reused buffer that is not cleared cannot show the previous frame's status **or outcome**; an accepted head reads `CLEARED` with `reason = NO_REASON` while an exhausted one reads `PASS_CAP` or `SEARCH_EXHAUSTED` with its own last geometric cause, so the two fields are asserted separately (X7); **each guard has its own case** — `NON_FINITE_GEOMETRY` on an input, `OPERAND_OUT_OF_BOUND` on a **published** operand, `DEGENERATE_OBSTACLE`, `REST_LENGTH_INVALID` and `UNSUPPORTED_GEOMETRY` each yield `INVALID` + `REJECTED_BEFORE_SEARCH` + their own reason, never `CLEARED` and never `NO_GEOMETRIC_CAUSE`; `BUFFER_CAPACITY`, a `NON_FINITE_GEOMETRY` first raised in an intermediate, and an `OPERAND_OUT_OF_BOUND` on a **constructed** detour each yield `INVALID` + **`REJECTED_IN_SEARCH`** + their own reason (the constructed-operand case carries the segment being constructed and obstacle `NO_INDEX`), which is the case that separates the two refusal outcomes; a corridor refused before the first candidate yields `INVALID` + `SEARCH_EXHAUSTED` + `NO_GEOMETRIC_CAUSE`; the two illegal combinations (`VALID` with a non-`CLEARED` outcome, `INVALID` with `CLEARED`) are asserted to be absent, and a head refused before a pass is never reported `REJECTED_IN_SEARCH` while one aborted inside a pass is never reported `REJECTED_BEFORE_SEARCH`; and a head that is simultaneously degenerate and inside an obstacle reports `DEGENERATE_SEGMENT` |
| `MuscleGeometryOwnershipTest` | L4 / presentation | §7.6: toggling morphs or correctives leaves every `MuscleState` bit-identical, and nothing in `character/` writes `AnatomyFrame` |
| `SceneFrameHandoffTest` | presentation (stage 2) | §10.2: `geometry` carries only instances whose `renderable` entry is `true`, one slot per instance, keyed by a `slotId` that is stable across a solve sequence; a slot absent from the next frame (its instance became `INVALID`) is cleared by id while its neighbours keep drawing, and a slot present with `visible = false` is cleared too; each `SkinPalette` is written in its own skin's joint order and `MuscleLayerSkinner` writes only the skin it was built for; and the renderer receives no muscle id, status or reason |
| `MuscleSelectionReadoutTest` | presentation | §5.3/§9.2/§15 stage 2b: `setSelected` keys the instance by `(muscleId, side)` — selecting `biceps_femoris` side A leaves side P unselected, and selecting side P changes the row — and the readout renders **one row per head** (two for biceps femoris on one side) with **no aggregated instance value**; each row carries the muscle name, `MuscleState.headLabel` where the instance has more than one, `MuscleState.layer`, the role returned by `ReviewedRoleSource.roleFor(exerciseId, muscleId)` where one exists and "no reviewed role" otherwise, and `relativePathLengthChange` labelled modelled — and nothing else; the role comes from that second input and not from `MuscleState`, so changing `exerciseId` changes the role text with no re-solve and an `UNREVIEWED` row renders as "no reviewed role" exactly as an absent one does; no row shows an absolute metre value (X4); an instance whose `renderable` entry is `false` renders no row at all rather than a stale one; and no string is drawn from `PathReason`, a status or a debug label. It is separate from stage 2's dev readout, which it does not replace |
| `ProceduralTubeBudgetTest` | L5 (M1 subset) | generated triangles match the §10.3 formula for the declared `N` and `R`; no zero-area ring; all coordinates finite |
| `MorphPresetRangeTest` | L6 | every declared weight lies inside its target's declared `min`/`max`; every preset declares a value for every target; a name the asset does not carry fails rather than defaulting to zero |
| `MorphRingPreservationTest` | L6 | the mesh ring masked around each joint pivot keeps its pivot-relative positions within `ε_morph` under every preset — the test that can fail, and the one that says anything about the surface a muscle sits under |
| `LayerToggleTest` | presentation | toggling a layer changes visibility only; no engine or pose rebuild; an evicted layer reloads and re-shows |
| `ArchitectureBoundaryTest` | architecture | §4.5: every file in `animation/` and `poses/` imports none of the **four** prefixes its forbidden list actually contains — `androidx.compose.`, `android.`, `com.monkfitness.app.ui.`, `com.monkfitness.app.render.` — via `theMotionCoreImportsNoComposeOrFrameworkCode` and `theMotionCoreImportsNoOtherAndroidxCode`. **There is no `com.google.android.filament.` rule**, and the `anatomy/`, `character/` and Filament fences are **review-only**, not enforced by this test |
| `AssetBudgetTest` | L5 | triangle/texture/bone budgets per tier from the manifest |

All except `LayerToggleTest` are JVM tests (the character session itself requires a device). `LayerToggleTest` splits:
a pure state test on the controller in JVM, and a device smoke test in the existing diagnostics screen.

None of these tests establishes anatomical correctness, and none of them replaces §12.4.

**No universal monotonicity test.** A tempting requirement is that the quadriceps shorten monotonically through a
squat's ascent. It is not safe as a universal rule. Rectus femoris crosses both the hip and the knee, so its
length is a function of two joint angles and can lengthen while the vasti shorten; a bi-articular muscle can also hold
length through a phase in which the joint moves. A test that forced the solver to produce a predetermined curve would
make the anatomy satisfy the test instead of the test describing the anatomy — precisely the inversion §11 exists to
prevent. The inventory therefore checks what is mechanically defensible: attachment and path correctness, determinism,
**tangent and curvature** continuity at entry/exit — never temporal continuity, which X6 leaves diagnostic — and
agreement with reviewed reference data where such data exists. Directional expectations are allowed only
per muscle and per exercise, with the justification and its source recorded next to the test.

#### 12.2.1 Adversarial path fixtures

Two kinds of fixture are needed, and conflating them was a defect in an earlier revision: a **validator fixture** hands a
finished polyline to the predicate and states the verdict it must return; a **solver fixture** hands an obstacle
configuration to the constructor and states the *final* path or failure it must produce. A raw crossing polyline must be
rejected; a solver run on the same obstacle may legitimately return `VALID` after routing around it. Both facts are true
at once, and only a split table can say so. **Every success fixture has a failure fixture beside it**, so neither table
can be passed by an implementation that always accepts or always rejects.

**The table is the category, not a prefix.** An earlier revision said validator fixtures are "named `raw_*`" while most
of its own rows were not, so a reader could not tell which table a fixture belonged to. The rule is now: the two tables
below are the two categories, every fixture name is unique **across both tables**, and the `raw_` prefix marks exactly
the validator rows whose geometry is quoted as a finished polyline in normalized coordinates. Absence of the prefix is
not a category — `rotated_anisotropic` appears in both tables as **two different geometries**, so the solver row is named
`solve_rotated_anisotropic` (chord `(−145, 0, 0) → (145, 0, 0)` mm through the centre) and the validator row
`rotated_anisotropic` keeps its own chord (`(−100, 15, 0) → (100, 15, 0)` mm). They test different things: the validator
row tests that the predicate honours `Rw`, the solver row tests the fallback axis and the support function.

**Which predicate a validator fixture calls, stated per row.** A row whose expectation is an *intersection* reason
(`SEGMENT_INTERSECTS_OBSTACLE`, `DEGENERATE_SEGMENT`, `DEGENERATE_OBSTACLE`, `NON_FINITE_GEOMETRY`) exercises the
**segment-only predicate** on the quoted polyline; a row whose expectation is an *endpoint* reason
(`ENDPOINT_INSIDE_OBSTACLE`) exercises the **full head validator**, because the endpoint check lives there. A fixture that
changes which predicate it calls changes its primary reason, so every row pins both:

**Every `SEGMENT_INTERSECTS_OBSTACLE` reason below is conditional on the enclosure deliverable, and that condition is
stated once here rather than repeated in ten rows.** Stage 2 reports that reason only when the witness survives a **proved**
forward-error enclosure (§7.4.1b). No enclosure is derived in this document, so while deliverable (2) of §7.4.1c is open
the same geometry is **routed to stage 3, whose own result is authoritative for that pair** — `CLEARANCE_VIOLATION`
where its bracket decides the clearance failure, `UNCERTIFIED_MINIMUM` where it cannot: the **verdict is unchanged**
(`INVALID`, which is what every one of those rows is really distinguishing), the **reason is not**. The rows keep
their intended reason because it is what the implementation must produce once the enclosure exists, and because a
row pinned to one of stage 3's two outcomes would stop testing which stage decided. The control rows (`VALID`) are
unaffected — they do not depend on stage 2 at all — so each pair still separates an implementation that accepts
everything from one that rejects everything.

| Row | Predicate | Primary reason | Expected secondary |
| --- | --- | --- | --- |
| `raw_intersecting`, `raw_crossing_thick`, `outside_endpoints_crossing_obstacle`, `rotated_obstacle`, `rotated_anisotropic`, `raw_shallow_crossing` | segment-only | `SEGMENT_INTERSECTS_OBSTACLE` | stage 2 witnessed an entry (no stage-3 evaluation) |
| `clearance_margin`, `non_unit_scale`, `taper_knot_minimum`, `raw_shallow_crossing_control`, `raw_near_surface_vanishing_excess`, `raw_stage2_edge_touch` | segment-only | `CLEARANCE_VIOLATION` (`VALID` for the two controls) | stage 3 decided it, after stage 2 declined to witness an entry — the last two are the chords an interval test alone would have called intersections |
| `endpoint_inside` | full head validator | `ENDPOINT_INSIDE_OBSTACLE` | the segment check is not reported, because the endpoint test runs first |
| `degenerate_segment_inside` | segment-only | `DEGENERATE_SEGMENT` | not `SEGMENT_INTERSECTS_OBSTACLE` — the `a = 0` guard precedes the quadratic |
| `non_finite`, `degenerate_obstacle`, `rest_length_invalid` | segment-only (guard phase) | the guard reason | no geometric test is reported, because none ran |
| `sheet_muscle` | full head validator | `UNSUPPORTED_GEOMETRY` | no clearance verdict is reported for a shape the predicate does not describe |

**Validator (`raw_*`) fixtures — a finished polyline in, a verdict out.**

| Fixture | Configuration | Expected verdict |
| --- | --- | --- |
| `raw_clear` | a polyline that clears every obstacle | `VALID` |
| `raw_clear_negative_discriminant` | unit-sphere case `(q0, qd) = ((2,0,0), (0,1,0))`: `Δ = −12 ≪ −Δ_tol` | `VALID` — minimum over `[0, 1]` at `t = 0`, where `\|q0\| = 2` and the surface distance is **1** (two occurrences of the wrong value "2" were in this document); a test that maps a large negative `Δ` into the fail-closed band fails here |
| `raw_clear_roots_outside_segment` | unit-sphere case `((2,0,0), (1,0,0))`: `Δ = 4 > Δ_tol`, roots `[−3, −1] ∩ [0, 1] = ∅` | `VALID` — the infinite line crosses the ellipsoid, the finite segment does not; a `Δ ≥ 0`-only verdict fails here |
| `raw_intersecting` | unit-sphere case `((−2,0,0), (4,0,0))`: `Δ = 64`, roots `[0.25, 0.75]` | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` |
| `tangent_on_segment_invalid` | unit-sphere case `((−2,1,0), (4,0,0))`: `Δ = 0`, tangency at `t = 0.5` | `INVALID` — `min d = 0 < r`; the band routes it to stage 3 and stage 3 agrees |
| `tangent_off_segment_clear` | unit-sphere case `((2,1,0), (1,0,0))`: `Δ = 0`, tangency at `t = −2` | `VALID` — minimum over `[0, 1]` at `t = 0`, surface distance `√5 − 1 = 1.236`. **The earlier near-tangent branch rejected this segment** by treating every `abs(Δ) ≤ Δ_tol` as an intersection, contradicting the interval rule stated in the same section |
| `clear_control` | unit-sphere case `((3,1,0), (1,0,0))`: `Δ = 0`, tangency at `t = −3` | `VALID` — surface distance `√10 − 1 = 2.162`; the pair exists so a check that rejects every tangency cannot pass the set |
| `near_tangent_rounding` | `q0 = (−2, 1+1e-13, 0)`, `qd = (4,0,0)`: `Δ = −1.279e-11` (exact `−1.28e-11`), `Δ_tol = 5.12e-10` | `INVALID` — inside the band, routed to stage 3, where `min d ≈ 0`. **`Δ` does not "round to −0"**: it is a small negative number, and a fixture premise that says otherwise is wrong even though the verdict is unchanged |
| `q_zero_centre_start` | unit-sphere case `((0,0,0), (1,0,0))`: `b = 0`, `c0 = −1`, `Δ = 4` | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`. With `sign(0) = +1` the diagnostic `q = −1` and roots `[−1, 1] ∩ [0, 1] ≠ ∅`; a textbook `sign(0) = 0` gives `q = 0` and a non-finite `t1`, so the fixture fails that implementation |
| `outside_endpoints_crossing_obstacle` | thin ellipsoid on the chord, both segment endpoints outside; **N pinned at 30 samples over the 0.29 m chord** (29 intervals of 0.01 m, endpoints included) | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`; a sample-only check wrongly accepts it |
| `raw_crossing_thick` | chord through a thick obstacle, samples inside | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` |
| `rotated_obstacle` | an obstacle rotated with its segment, segment clear only in the unrotated frame | `INVALID` — a test that ignores `Rw` misses it |
| `rotated_anisotropic` | local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z (local X → world +Y); straight chord `(−100, 15, 0) → (100, 15, 0)` mm | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` (segment-only predicate) with the correct `Rw`: `q0 = (0.375, 10, 0)`, `qd = (0, −20, 0)`, `a = 400`, `b = −400`, `c0 = 99.140625`, so **`Δ = 1375 > Δ_tol`**; a test that drops `Rw` (`q0 = (−2.5, 1.5, 0)`, `qd = (5, 0, 0)`) gets **`Δ = −125`** and reports clear. Both discriminants were mis-quoted as ≈1349 and ≈−120 in an earlier revision; they are recomputed here from the quoted chord. Exercises the frame transform on a non-spherical obstacle, not just a rotation |
| `clearance_margin` | a chord that misses the surface but by less than `eps_num` | `INVALID` — **`CLEARANCE_VIOLATION`**, not `SEGMENT_INTERSECTS_OBSTACLE`. This is the row that separates the two reasons, and a predicate that only reports intersections reports `VALID` here |
| `non_unit_scale` | sphere `radiiMetres` = 0.020 m; chord `(−100, 20.005, 0) → (100, 20.005, 0)` mm (0.005 mm outside the surface); `s = 235/0.95 ≈ 247.37`, `eps_num` = 1e-5 m = 2.47e-3 units | `INVALID` — `CLEARANCE_VIOLATION`: the gap is 1.24e-3 units, below the margin. Applying `eps_num` as if it were already engine units gives `e = 1e-5`, a **false CLEAR**. Paired control: chord at `y = 20.05 mm` (0.05 mm outside, 1.24e-2 units) → `VALID`, so the fixture cannot pass by rejecting everything |
| `taper_knot_minimum` | radius knots `(0, 0), (0.2, 1.21), (0.4, 1.215), (0.6, 1.55), (1, 0)` on the straight path `p(t) = (2 + t, 0, 0)` | `INVALID` — `g(0.2) = −0.010`, violating on `[0.198020, 0.210255]`. A single golden-section run on `[0, 1]` returns `t = 0.6`, `g = +0.05` and reports clear, so the fixture fails a minimiser that ignores the knots |
| `endpoint_inside` | an attachment resolved inside a declared obstacle | `INVALID` — `ENDPOINT_INSIDE_OBSTACLE` |
| `non_finite` | a NaN coordinate or radius | `INVALID` — `NON_FINITE_GEOMETRY` |
| `degenerate_obstacle` | a zero or negative semi-axis | `INVALID` — `DEGENERATE_OBSTACLE` |
| `rest_length_invalid` | a head whose reference-stance length is zero | `INVALID` — `REST_LENGTH_INVALID`, never a division |
| `degenerate_segment_inside` | a zero-length segment whose point lies inside an obstacle | `INVALID` — `DEGENERATE_SEGMENT` (segment-only predicate), never `SEGMENT_INTERSECTS_OBSTACLE`: the `a = 0` point test precedes the quadratic |
| `sheet_muscle` | a definition with `flat = true` | `INVALID` — `UNSUPPORTED_GEOMETRY`, full head validator; no clearance verdict is reported for a strip shape the tube predicate does not describe |
| `raw_interior_segment` | unit sphere, a segment lying entirely inside it: `q0 = (0, 0, 0)`, `qd = (0.4, 0, 0)`, tube radius 0.1 | `INVALID` — **`SEGMENT_INTERSECTS_OBSTACLE`**, decided at **stage 2**: `a = 0.16`, `b = 0`, `c0 = −1`, `Δ = 0.64`, roots `[−2.5, 2.5]`, so `[t0, t1] ∩ [0, 1] ≠ ∅`. Stage 3 does not run and no `min g` is reported. The earlier row expected `CLEARANCE_VIOLATION` with `min g = −0.1`, which contradicts the state machine it belongs to: a straight segment with both ends inside a closed ellipsoid always has its root interval meeting `[0, 1]` |
| `raw_interior_control` | the same geometry with the segment outside: `q0 = (1.3,0,0)`, `qd = (0.4,0,0)` | `VALID` — the near-miss control, so the pair cannot pass by rejecting every interior-ish chord |
| `raw_shallow_crossing` | unit sphere; `q0 = (−2, 0.998, 0)`, `qd = (4, 0, 0)`, tube radius 0 — a chord whose closest approach is 0.002 units inside the surface | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`, decided at **stage 2**: `a = 16`, `b = −16`, `c0 = 3.996004`, `Δ = 0.255744` against `Δ_tol = 5.117e-10`, roots `[0.48420, 0.51580] ⊂ [0, 1]`. Not "inside the `Δ_tol` band" — the band here is nine orders of magnitude narrower than `Δ` — and no `min g` is computed, because a shallow crossing is still a crossing |
| `raw_shallow_crossing_control` | the same chord offset outward to `q0 = (−2, 1.002, 0)`, closest approach 0.002 units outside; `Δ = −0.256256` | `VALID` — stage 3 at `t = 0.5`, `min d = 0.002` and `min g = +0.002 ≥ e`; the near-miss control, so the pair cannot pass by rejecting every chord that comes close |
| `raw_near_surface_vanishing_excess` | unit sphere; `Float`-representable endpoints `(1, v, 0)` and `(1, 2v, 0)` with `v = 9.999999717180685e−10`; every point strictly outside, squared excess `= v² = 9.999999434361379e−19` | `INVALID` — **`CLEARANCE_VIOLATION`**, decided at **stage 3**, and **never** `SEGMENT_INTERSECTS_OBSTACLE`. In binary64 `q0·q0` rounds to `1`, so `c0 = 0`, `a = v² = 9.999999434361379e−19`, **`b = 2v² = 1.9999998868722757e−18`** (`a = b = v²` was this row's earlier error), `Δ = b² = 3.999999547489116e−36 > Δ_tol` and the roots are `[−2, 0]`, an interval whose only contact with `[0, 1]` is `t = 0`, where `s = 1`; the witness rule refuses the stage-2 verdict and the distance decides: the nearest point's exact binary32 coordinate excess is `v = 9.9999997171806853657471947371959686279296875e−10`, so the **solid** distance is `√(1 + v²) − 1 = 4.999999717180689…e−19`, far below `e` — **not** `v`, and **not** `1e-9`, which is what this row printed before the arithmetic was done. A direct binary64 evaluation returns `0` by cancellation and is not a reference. An implementation with neither the band nor a witness reports an intersection for a segment that never enters the obstacle |
| `raw_stage2_edge_touch` | unit sphere; `Float` endpoints `(2, 0, 0)` and `(1, 9.999999717180685e−10, 0)` — strictly outside throughout, exact squared excess `9.999999434361379e−19` at the second endpoint and exact lower root `1 + 5.0e-19` | `INVALID` — **`CLEARANCE_VIOLATION`** at **stage 3**, never `SEGMENT_INTERSECTS_OBSTACLE`. Binary64 gives `a = 1`, `b = −4`, `c0 = 3`, `Δ = 4`, `Δ_tol = 2.8e-11`, roots `[1, 3]`; `\|c0\| = 3` clears the coefficient band, so only the witness rule can refuse it (`s(1) = 1`). The row exists because a **large** `c0` looks safe and is not: the lost excess is at the far endpoint, and no band on `c0` or `Δ` can see it |
| `minimum_at_endpoint_low` | the closest approach at `t = 0`, just above the margin | `VALID` — the endpoint is always evaluated, so a search that starts inside the bracket cannot miss it |
| `minimum_at_endpoint_high` | the closest approach at `t = 1`, just below the margin | `INVALID` — `CLEARANCE_VIOLATION` |
| `minimum_at_a_knot` | unit sphere; straight path `p(t) = (−1 + 2t, 1.10, 0)`; radius knots `(0, 0), (0.5, 0.30), (1, 0)`; `e = 1e-5`. Predictor: §7.4.1b **stage 3** | `INVALID` — `CLEARANCE_VIOLATION` at the knot: `min g = −0.20` at `t = 0.5`, where `d = 1.10 − 1 = 0.10` and `r = 0.30`; `g(0.4) = g(0.6) = −0.122`, so the minimum is on the knot and each adjacent piece evaluates it, giving the same answer with the pieces split coarsely and finely. Stage 1 cannot accept (`\|c − cw\| − h = 0.10` is not `> 1 + 0.30 + e`) and stage 2 finds no root (`Δ < 0`), so the row reaches stage 3 — and it is the only row whose minimum is a piece boundary |
| `minimum_near_equality` | `min g` within one evaluation budget of `e` | `INVALID` — `UNCERTIFIED_MINIMUM`, never a coin flip on the last bit |
| `exempt_origin_success` | a head whose origin sits inside a declared obstacle, with the reviewed transition envelope declared (`fromStation = 0`, `toStation` the reviewed bound) and the path reaching `d − r ≥ e + σ` before that bound, with the slack **declared as part of the fixture**: `σ = 1e-6 m`, which exceeds the widest bracket half-width the pinned search produces (`L·w_30/2 = 1.322731e-7 m`, 7.6×) | `VALID` — X5's positive case. The slack is what makes it satisfiable, and it is the **`σ`-envelope** that supplies it: the boundary is the first station whose `g` reaches `e + σ`, so the clipped non-exempt piece starts at `g = e + σ = 0.0078135` rather than at `e = 0.0078125`, and the pinned 30-step bracket returns `lower(30) = 0.007813367727216046` (binary64) against `e = 0.0078125` — `VALID`. The same path with `σ = 0` is `exempt_boundary_equality` and returns `UNCERTAIN`: no slack exists at the first-margin station itself |
| `exempt_boundary_equality` | the same geometry with the boundary landing **exactly** at `d − r = e` (`σ = 0`): unit sphere, zero tube radius, path `x: 0.5 → 1.5`, `e = 0.0078125`, boundary station `x = 1.0078125` | `INVALID` — **`UNCERTIFIED_MINIMUM`**, never `VALID`. The pinned 30-step bracket gives `lower = 0.00781236772694730 < e = upper`, and `n = 40` is still short (`0.00781249892…`): subtracting a positive bracket remainder from a minimum exactly equal to `e` cannot prove `lower ≥ e`. The row exists so that "clip at equality and accept" fails a fixture rather than passing one. **`σ = 0` puts the boundary on the first-margin station, where `g` equals `e` exactly, and that is the station at which no slack exists** — which is why this row cannot be turned into a positive one, and why the two `..._success` rows declare `σ = 1e-6 m` (§7.4.1c) |
| `exempt_insertion_success` | the same head with the exemption on the **insertion** (`toStation = 1`, `fromStation` the reviewed bound), scanned **downward** from station 1, with the same declared slack `σ = 1e-6 m` at the boundary | `VALID` — the mirrored positive case. An implementation that clamps at `toStation = 1` and scans upward leaves the authored lower bound unenforced and accepts a path that clears the margin below `fromStation`; the pair exists because one orientation cannot test the other, and the slack exists because the bracket cannot certify a first-margin boundary in either orientation — the envelope must end on `e + σ`, not on `e`, in both scan directions |
| `exempt_then_penetrate` | the same exemption, a route that leaves and then passes through the same obstacle at a non-exempt station | `INVALID` — `CLEARANCE_VIOLATION`, full head validator |
| `exempt_re_entry` | 1-D unit sphere, zero tube radius, path `0.5 → 1.2 → 0.9 → 1.5`, exemption `[0, 0.7]` | `INVALID` — re-entry at fractional stations `0.5625 … 0.6875` is a violation under X5, although every one of those stations is inside the declared interval; the envelope closed at `0.3125` (just past `x = 1`) and everything after it is non-exempt |
| `exempt_re_entry_after_mutation` | the `exempt_re_entry` geometry, with the envelope computed on the pre-mutation path and the path then **mutated** (one Catmull-Rom smoothing pass and one inserted waypoint, both from §7.4 step 4) before the sample and segment checks run | `INVALID` — the same re-entry, still a violation. It exists because the mask is anchored to **stations on the current path**: a consumer that computes the envelope once and reuses it after a mutation accepts a station the recomputed envelope excludes, and no fixture that never mutates can see that. The row also asserts the mutation moved at least one station, so it cannot pass by mutating nothing |
| `pe_centre` | point at the obstacle centre | `d = 0`; the interior branch, no root solve |
| `pe_principal_axis` | a point on each principal axis | the analytic distance per axis, `\|p_i\| − R_i` |
| `pe_repeated_axis` | two equal semi-axes, `(2, 1, 1)`, `p = (3, 1, 0)` | the **general** ellipsoid distance, `d = 1.297184`; the sphere shortcut is reserved for three equal axes, because an oblate or prolate spheroid is not a sphere and the sphere formulas give `2.162278` (`R = 1`) or `1.162278` (`R = 2`) here |
| `pe_tiny_axis` | axes `(0.5, 200, 200)` mm — a 400:1 ratio | **two independent checks.** (a) The **distance**: `p = (2, 0, 0)` returns `1.5` and `p = (0, 250, 0)` returns `50`, both analytic on an axis (`\|p_i\| − R_i`), and the off-axis point `p = (2, 50, 0)` returns `1.515877` against the reference value. (b) The **error, measured not asserted**: `\|d − d_ref\|` against the 100-digit reference is recorded beside the reported residual `η`, and the row passes on the **distance** only; it does **not** pass by quoting `B ≤ 2⁻³⁶ · max(R)`, because §7.4.1b-1 no longer claims that bound — the recorded error is what deliverable (4) has to beat, and a routine that meets (a) by rounding `d` onto the reference fails it |
| `pe_interior` | a point strictly inside | `d = 0` |
| `pe_tangent` | a point on the surface, `Σ(pᵢ/Rᵢ)² = 1` | `d = 0` from the **interior/surface branch**: `≤ 1` includes equality, so no root solve is entered and there is no bracket to return. The row that exercises the bracket near the surface is `pe_near_boundary` |
| `pe_near_boundary` | a point 1e-6 units outside | **an observed comparison, not a bound check.** The row records the returned `d`, its 100-digit reference and the absolute error beside the reported residual `η`, and requires only that `d` is non-zero and lies between `0` and `2e-6`. It does **not** assert that `d` is "within the bound": `B_est` is a residual-based estimate that exceeds itself on a represented input (§7.4.1b-1), so a fixture that treated it as a bound would turn a measured error into an apparent contract — which is what the earlier version of this row did |

**Solver fixtures — obstacle configuration in, final path or failure out.**

| Fixture | Configuration | Expected result |
| --- | --- | --- |
| `single_obstacle` | **pinned inputs:** attachments at `(0, 0, 0)` and `(290, 0, 0)` mm; one sphere `R = 20 mm` centred at `(145, 0, 0)` mm; tube radius 0; `N = 30`; `e = 0.01 mm` | `VALID`, **and not before the third pass.** `N = 30` over 290 mm is 29 intervals of exactly 10 mm, so the samples are `0, 10, … 290`, the centre is not a sample, and the nearest retained neighbours are at `±25 mm` (`120`, `170`). `d_1 = 20.01 mm` → the connecting segment passes **15.62 mm** from the centre, rejected; `d_2 = 30.015 mm` → **19.21 mm**, still rejected; `d_3 = 45.0225 mm` → **21.86 mm**. Those three are **static per-pass measurements on the unsmoothed polyline**, so they establish that passes 1 and 2 cannot be accepted and leave the accepting index to the loop itself: the fixture runs the full loop and asserts the index it produces, with every segment of the accepted polyline tested, not just the waypoint. The earlier row's `1.22 mm at pass 2` was computed from ±30 mm neighbours, which this grid cannot contain: with endpoint-inclusive 10 mm spacing over 290 mm no sample sits at 145 mm |
| `solve_rotated_anisotropic` | local semi-axes 40 × 10 × 20 mm (`Rw` = +90° about Z, so world semi-axes `(10, 40, 20)`), chord `(−145, 0, 0) → (145, 0, 0)` mm through the centre, `N = 30` (10 mm spacing, no sample at the centre), tube radius 0, `e = 0.01 mm`. **Not the same geometry as the validator row of the same family** (that one is offset to `y = 15` mm) | `VALID`, and **not at the first pass**: the fallback picks local X (tie X/Z) and rotates it to world +Y, giving `h = R_x = 40 mm` and `d_1 = 40.01 mm`, but the segment from the retained sample at `(15, 0, 0)` to that waypoint enters the ellipsoid (`Δ = 3.9975`, roots `[0.3847, 0.9997]`), so pass 1 is rejected; at `d_2 = 60.015 mm` the same segment has `Δ = −2.2556` and its closest approach is `0.833977 mm`, so `min g = 0.833977 mm` and the margin-adjusted excess `g − e` is `0.824 mm` — the two are different quantities and only the first is `g`. The fixture asserts the accepting pass index, the waypoint's world direction, and that the accepted polyline's own certificate holds on every segment — it does **not** quote a post-smoothing clearance, because the smoothed-and-projected polyline is the pass loop's output, not this construction's. Choosing the fallback among world axes yields `h = R_y = 10 mm` and a waypoint 10.01 mm from the centre, still inside the obstacle, so passes 1–5 are rejected on this segment; escalation then reaches 76.0134 mm at pass 6, where the same segment is 2.217 mm clear, so the fixture must run the loop and report its final outcome rather than asserting a pass index from the static distance (§7.4.5) |
| `multiple_obstacles` | two obstacles in series along the route | `VALID`; every segment clear of both after smoothing |
| `overlapping_obstacles` | two ellipsoids that intersect each other | `VALID` outside the union; identical result for the same declaration order |
| `nested_obstacles` | a small ellipsoid fully inside a large one | `VALID` outside the outer surface |
| `near_tangent` | **pinned inputs:** sphere `R = 20 mm` at the origin, attachments at `(−100, 20, 0)` and `(100, 20, 0)` mm (a chord exactly tangent), tube radius 0, `N = 30`, `e = 0.01 mm` | `VALID`; the solver routes around it so the **final** polyline clears by `eps_num` (not merely "is not intersecting"); no oscillation; tangent check within `θ_max`. The solver half of the split: the raw tangent chord is rejected as `tangent_on_segment_invalid`, and a detour that clears is a legitimate `VALID` |
| `after_smoothing_regression` | a configuration whose pre-smoothing polyline clears but whose 0.25-blend smoothed polyline does not | `VALID`, and the accepted polyline is the smoothed-then-projected one. A solver that validates **before** smoothing accepts a curve the predicate never saw; the fixture fails it. The pass loop's own step 4d is what catches this case and forces the retry |
| `two_obstacle_oscillation` | two obstacles for which the detour side that clears one puts the path inside the other, alternating per pass | `INVALID` — `searchOutcome = PASS_CAP` with the last per-pair reason the final pass produced (an intersection or a clearance violation, depending on the side), never `NO_GEOMETRIC_CAUSE`, because a per-pair test ran. Deterministic and terminating: the same input produces the same failure at the same pass, not a hang and not a different reason on a second run |
| `search_exhausted` | an obstacle whose required detour distance exceeds the declared corridor limit | `INVALID` — `searchOutcome = SEARCH_EXHAUSTED`, never `NO_ROUTE`: the search gave up and no impossibility was proved. The reason is whichever geometric cause the last per-pair test produced, or `NO_GEOMETRIC_CAUSE` if the corridor bound stopped it before one |
| `closed_corridor_exhausted` | **pinned inputs:** attachments at `(0, 0, 0)` and `(290, 0, 0)` mm; one ellipsoid centred at `(145, 0, 0)` mm with semi-axes `(120, 40, 40)` mm, both attachments outside it (`Σ(pᵢ/Rᵢ)² = 1.4601`); tube radius 0; `corridorMetres = 0.030 m` (30 mm); `e = 0.01 mm` | `INVALID` — `searchOutcome = SEARCH_EXHAUSTED` with `reason = NO_GEOMETRIC_CAUSE`, because the search stopped before any per-pair test produced a cause, and the frame emits **no** `NO_ROUTE` value (X7). The corridor is refused at pass 1 and that is arithmetic, not a theorem: `h(l̂_d) ≥ min R = 40 mm` for every unit direction (`h² = Σ(Rᵢ l̂ᵢ)² ≥ min R²`), so `d_1 = h + e ≥ 40.01 mm > 30 mm` and no pass can place a waypoint inside the declared corridor. This replaces the former `no_valid_route` row, which asserted `NO_ROUTE` for a geometry its own fixture claimed to prove and §7.4.1c forbids M1 from emitting; the replacement pins the **declared limit** that stopped the search, never an impossibility claim |
| `pass_cap` | a configuration that exhausts the pass budget `K` without a candidate | `INVALID` — `searchOutcome = PASS_CAP`, with the last per-pair reason it saw or `NO_GEOMETRIC_CAUSE` if it never saw one; deterministic and terminating |
| `two_obstacle_projection_cycle` | two spheres centred at `0` and `2`, radius `1.5`, a sample at `0.5` | `searchOutcome = PASS_CAP`, and the record shows the sample alternating `1.5 → 0.5 → 1.5` under ordered projection; an implementation that treats projection as convergent (iterating to a tolerance, or reporting the first projected position as final) cannot reproduce the sequence |
| `final_clearance_and_budget_both_fail` | a final polyline that fails the predicate at one named segment **and** exhausts `K` at the same pass | one record: `searchOutcome = PASS_CAP` **and** `reason = CLEARANCE_VIOLATION` with the segment and obstacle index — the two fields are separate (X7), so neither overwrites the other |
| `retained_entry_never_clears` | the `single_obstacle` geometry with the entry/exit construction points retained | `INVALID` — `searchOutcome = PASS_CAP` with **`reason = SEGMENT_INTERSECTS_OBSTACLE`** on the segment that leaves the retained surface point, decided at **stage 2**. The segment from the retained point `(20, 0)` to the pass-3 waypoint `(0, 45.0225)` in normalized coordinates has `q0 = (0, 2.251125)`, `qd = (1, −2.251125)`, `a = 6.067563765625`, `b = −10.13512753125`, `c0 = 4.067563765625`, `Δ = 4` and roots `[0.6703784, 1]`, so it **enters** the sphere: `CLEARANCE_VIOLATION` was this row's earlier expectation and contradicts the state machine, because a certified crossing precedes any clearance reason. The margin arithmetic is why it never clears (`R·D/√(R²+D²) < R` for every finite `D`), but the reason reported is the intersection the predicate actually found |

**`outside_endpoints_crossing_obstacle`, constructed so it cannot pass by accident.** One ellipsoid with semi-axes
4 mm × 2 mm × 2 mm centred on the straight chord between two attachments 0.29 m apart, with the polyline running along
the ellipsoid's long axis through its centre. **The sample count is part of the fixture: N is pinned at 30 samples** —
29 intervals of 0.01 m, both endpoints included — which puts the nearest samples at ±5 mm from the centre, where
`|q| = 5/4 ≈ 1.25 > 1`. With 29 samples the grid lands one sample *exactly* on the centre (28 intervals of 0.010357 m,
and `14 × 0.010357 = 0.145`), the sample check alone rejects it, and the fixture stops testing the segment predicate — so
29 and 30 are not interchangeable, and a fixture that does not pin N is not reproducible.

At that grid, **no sample is inside the obstacle and no segment has one endpoint inside**, so the sample check and a
boundary-straddle refinement rule both report "clear" while the path passes through the centre. Stage 2, on the segment
from `−5 mm` to `+5 mm` in normalized coordinates (`q0 = (−1.25, 0, 0)`, `qd = (2.5, 0, 0)`), gives `a = 6.25`,
`b = −6.25`, `c0 = 0.5625`, so **`Δ = 25`** and the roots are **`[0.1, 0.9] ∩ [0, 1] ≠ ∅`** → `SEGMENT_INTERSECTS_OBSTACLE`.
`Δ ≈ 23.8` with roots `[0.09, 0.91]` was quoted in an earlier revision; it is not what this chord computes.

The obstacle is deliberately thinner than the sample spacing, because the check must not depend on an obstacle being
fatter than the polyline's resolution. Its paired control is the same geometry with the chord offset 5 mm laterally,
which must stay `VALID` — so the fixture cannot pass by rejecting everything, and a solver that always returns `INVALID`
fails the control just as a sample-only solver fails the fixture.

A fixture whose expected outcome a broken implementation also produces is not a fixture. This one is defined by the
property that the two checks disagree, and the L4 report records which check produced each verdict. A fixture that
produces an `INVALID` result where the table expects `VALID` fails L4; a fixture that produces a path through geometry
fails L4 twice — once for the geometry and once for accepting it.

### 12.3 Device evidence

A device exists. One run of the #384 spike on the owner's phone — Adreno 735, OpenGL ES 3.2, feature level 2 — held
~59 fps on the skinned 30,720-triangle asset, and the launch defects that run exposed are recorded as known traps in §13.
That is the whole of the device evidence: one device, one asset, one run; no p95, no thermal soak, no context-loss test,
no second device.

Frame time, jank, thermal drift, surface recreation and context loss therefore remain unmeasured as budgets.
`docs/CHARACTER_DEVICE_VALIDATION.md` (PR #385) is the procedure that records backend, FPS, p95 frame time and defects
per device; it has not been filled in by a machine run, and filling it in is device work, not agent work.

**Formal FPS and thermal gating is deferred by the owner's decision.** The priority is a correct result first and
optimisation later, so nothing in this document gates on a frame-rate or thermal number; those numbers are recorded as
information. Stage 1's gate is that the character renders on a device and that Monk motion retargets to the rig
correctly (§15).

### 12.4 Anatomical review gate (M1 acceptance)

M1 does not pass because the automated levels are green. Those levels prove the data is well-formed and that the
geometry obeys its own rules. They cannot prove that an attachment is the right point on the right bone, that a belly
sits in the right compartment, or that a silhouette reads as the named muscle. The gate is a recorded human review with
named criteria, a named reviewer and named references.

**Criteria.** Reviewed per muscle, per side, in the poses the milestone actually ships. They are numbered **RV1–RV6**
(from "review") precisely because **A1 and A2 already mean the rig-alignment measurements** of §6.6, and one document
using `A2` for both "the belly sits outside the bone" and "the bind head coincides within 0.5 mm" is how a reviewer ends up
approving the wrong one:

| # | Criterion | What "pass" means |
| --- | --- | --- |
| RV1 | Attachment locations, authoring frame and sign | each origin, insertion **and every waypoint** lands on the landmark named in the definition, within the tolerance stated in the review record; **and the frame each of them was authored in is confirmed per attachment, not assumed and not inferred from its neighbours.** The record names, for each of the 18 M1 head-side instances of §7.3, the instance's status **and the status of every attachment it carries — origins, insertions and waypoints — because a head with reviewed endpoints and an unreviewed waypoint passes any per-instance check while mixing two frames**, and closes a **frame unverified** row only with a **left/right** check (the attachment and its mirror land on opposite sides of the derived frame's `+Z`, on the cited landmark pair) **and** an **anterior/posterior** check (an attachment the definition calls anterior lands on the anterior landmark the review cites), applied to each attachment of the instance. A 180° error about `Y` leaves every symmetry check and every L3 envelope test satisfied, so an attachment with neither check is unverified and M1 does not accept it |
| RV2 | Position relative to bones and body surface | the belly sits in the correct compartment: not inside bone, not outside skin, not on the wrong side of a joint |
| RV3 | Visible intersections | no intersection with skin or with an adjacent muscle in any reviewed pose, at the layer visibility the milestone ships |
| RV4 | Plausible shape and path across poses | path and silhouette read as that muscle in every pose of the M1 review set — five poses from the four pilot exercises of X8 — plus any pose the reviewer asks for. The set is chosen so that **every M1 muscle is posed for review at least once**: `squat` bottom and top (gluteus maximus, gluteus medius, biceps femoris on both heads, rectus femoris, erector spinae, rectus abdominis), `standard push-up` bottom (the abdominal wall in a second configuration: rectus abdominis, external oblique, erector spinae), `bird-dog` (erector spinae, external oblique, gluteus maximus and gluteus medius in a hip-extension configuration) and `row_standard` mid-pull (**latissimus dorsi**, whose humeral insertion no squat, push-up or bird-dog pose carries through a pull). **Latissimus dorsi is the muscle the set exists for**: a review built only from squat and push-up poses would leave one of the eight unreviewed and still look complete. A pose outside the set is outside the review, not inside a guarantee. **Pose coverage is not builder coverage, and this set is A2's whole set**: squat top and bottom are two frames of one builder, so these five frames come from four registered builders, and A2 measures the same five from the same four (§6.6) — the asymmetry an earlier revision carried, where A2 measured a sixth frame this criterion never reviewed, is closed by dropping that frame. What neither criterion reaches is **arm elevation above shoulder height**, which no M1 pilot exercise needs and which the record below carries as a limitation |
| RV5 | Consistency across presets | every morphology preset shipped in the milestone is reviewed; a preset added later re-opens the review for the muscles it moves. M1 ships one preset, so this criterion is satisfied by reviewing that one and is not evidence about any other |
| RV6 | Provenance | every judgement in the record points at an identifiable reference, not at "looks right" |

**Reviewer.** A qualified anatomist or physiotherapist, named in the record, independent of the person who authored the
attachment table. Test authorship is not review.

**References.** Identifiable and citable: a named atlas with its edition, a peer-reviewed source, or an imaging dataset
with its access note. This document deliberately names none it has not verified; the review record carries the citation
keys and the provenance note, and inventing a reference is a review defect of the same severity as a wrong attachment.

**Record.** One versioned file per anatomy version (proposed `docs/anatomy/anatomy_review_v1.md`; not created by this
change) containing: reviewer, date and qualifications; the reference list; per-muscle findings against RV1–RV6; and an
explicit **unresolved limitations** list. A limitation is not a failure — hiding one is.

**Three entries that list must carry.** (i) **Path validity is shown on the generic reference body only.** The clearance
and path results of §7.4 are measured on the one reference body M1 ships, at its one `s` and its one bind pose; they are
not evidence for any other body, and stage 5's families re-open the question rather than inheriting the result.
(ii) **Sex-related differences in pelvis width and femoral angle are not covered.** The shared attachment set is authored
and reviewed against one body, with no sex-specific adjustment made or implied. A reviewer who believes a subset of
attachments needs one records it as a limitation; the fix is a body-family override reviewed per family (D10), not a row
added here. This document deliberately ships **no sex-specific data**, and a record that invented some would be recording
a review nobody performed.

(iii) **Retarget correctness for arm elevation above shoulder height is not covered.** Neither A2 nor RV4 exercises a
frame in which a hand target rises above the shoulder, because none of the four M1 pilot exercises (X8) does: the A2
frame set and the RV4 review set are the same five frames (§6.6). No M1 pilot exercise needs that configuration, so this
is not a gap in the milestone — it is a gap in what these two criteria prove about the rig and the retarget. Before the
first exercise that raises the arms above the shoulder ships, a **check on such a pose is required**. Stage 1's manual
acceptance already asks that joints hold across an overhead reach on a device (§15), but that is a visual check, not a
residual against `τ_residual` at a named frame, so it does not substitute for it. Candidate:
`wall_slide_standard` at progress 1.0 (`WallSlidesPose.kt`), whose hand targets rise from `shoulder − 10` to
`shoulder + 60` units as `progress` goes 0 → 1 (`WallSlidesPose.kt:117-120`), so progress 1.0 is the overhead reach and
the documented fingertip apex is `y ≈ 428` units (`WallSlidesPose.kt:222-226`). The candidate must name a registered
builder: `AnimationRegistry.kt` registers no `overhead_reach` id at the pinned base, which is why an earlier revision's
frame could not be reproduced. Until that check runs, A2 and RV4 make no claim about arm elevation above shoulder
height.

**What the record does not do.** It does not convert to a score, it does not make L7 automatable, and it does not
retroactively validate poses, presets or meshes it did not cover. A review of `anatomy_v1` does not cover `anatomy_v1.1`
or a new morphology preset.

**Renewal — the record is bound to what was reviewed.** A record names the tuple it covers: `anatomyVersion`,
`rigVersion`, `assetVersion`, `presetId`, the muscle list, the sides, the poses and the layer configuration. It is valid
only for that tuple. Three events force a new or amended record, and none of them can be waived by a passing automated
level:

| Event | Why it re-opens the gate | What the amendment must cover |
| --- | --- | --- |
| Stage 3 replaces procedural tubes with authored muscle meshes | the reviewed silhouette and intersections no longer exist | RV2, RV3, RV4 on the new assets, per muscle, both sides |
| Stage 5 adds a morphology preset or a **body family** | morphs move the surface the muscles must sit under, and a new family may also move the landmarks an attachment was approved against | **RV1 per muscle, per side, on every new body family** — the re-approval D10 requires before a shared attachment point is reused for a body it was not reviewed on — plus RV5 across presets and RV2/RV3 on each new preset. A new **preset inside an already reviewed family** does not repeat RV1: its review scope is narrower, and the record states which of the two it is |
| Any attachment, wrap-surface or definition change | the reviewed geometry is not the shipped geometry | the changed muscles, at minimum |

A record that does not name the asset version it covers is not a record of anything.

**Enforcement.** The M1 checklist (§15) cites the record. A missing record is a failed gate, exactly as a red L4 is.

### 12.5 M1 applicability (what the milestone actually gates)

M1 ships procedural tubes on one preset, so two levels apply only in part. This table is binding: the roadmap, the hard
rule and the M1 checklist all cite it, and none of them may require a check the other two do not.

| Level | In M1 | Explicitly not in M1 | Why |
| --- | --- | --- | --- |
| L1 | all 18 rules | — | unchanged engine contract |
| L2 | rig contract, rest invariant, bone map, side check, budgets, **§6.6 alignment A1/A2** | — | the rig must be shown to follow the engine skeleton |
| L3 | all mechanical checks, evaluated in the bind stance | — | definition-time data check |
| L4 | all path checks, validator fixtures and solver fixtures | — | the geometry contract |
| L5 | body asset checks (degenerate triangles, weight sums, budgets) and **generated tube structure**: finite coordinates, no zero-area ring, triangle count equal to the §10.3 formula | muscle-vs-skin intersection; authored-mesh helper binds | M1 has no authored muscle mesh; no mesh intersection test ships in M1 |
| L6 | one preset: declared ranges respected, mesh ring preservation around joint pivots, delta bounds | cross-preset coverage; "anatomy still inside the body" as an automated pass; the bone-landmark rest invariant, which a vertex-only morph cannot affect and which therefore proves nothing | one preset exists; body-anatomy intersection needs a tool M1 does not ship, so it is an L7 RV2/RV3 judgement |
| L7 | RV1–RV6 with a valid record (§12.4) | — | the human gate |
| §9.2/X4 readout | the **selected-muscle readout** stage 2b delivers displays `relativePathLengthChange` only, labelled modelled, with no absolute metres, and nothing for an unrenderable instance | any absolute length in the UI before a named calibration pair and an evidence-backed uncertainty exist | a dimensionless ratio and a metre value are not the same claim, and only one of them is available today |
| §8 roles | the categorical role entries for the four pilot exercises of X8 only — `squat`, `standard push-up`, `bird-dog`, `row_standard` — each with `source`, `task`, `population`, `reviewStatus`, validated against its reviewed `PhaseTimeline`; unreviewed or `UNKNOWN` rows are not displayed | numeric activation, EMG-derived output, per-muscle contraction mode, medical claims, and roles for any exercise without a reviewed source | roles are cheap to validate and dishonest to guess; a display gate is what makes a partial table acceptable (X8) |

**M1 gate, stated once.** M1 passes when: L1–L4 green; the L5 and L6 subsets above green; **a device run recorded** for
the M1 configuration (skin + muscle layer + skeleton overlay) — evidence that it renders, not a frame-rate or thermal
budget, because those are informational by the owner's decision (§12.3); the §8 role table for those four pilot
exercises validates (every displayed row carries a reviewed source, and every exercise without one shows no role); and
the §12.4 record exists for the exact `(anatomyVersion, rigVersion, assetVersion, presetId)` shipped. Any other
combination is not an M1 pass, whatever the individual reports say.

---

## 13. Risks and unresolved decisions

| # | Risk | Likelihood | Impact | Mitigation | Owner |
| --- | --- | --- | --- | --- | --- |
| R1 | Filament toolchain pin (1.75.1, compileSdk 34) blocks needed features | medium | medium | keep the pin; treat the compileSdk upgrade as a separate change with its own CI | engineering |
| R2 | Muscle authoring cost is much larger than estimated | high | high | M1 uses 8 muscles; strategy A proves the pipeline before art is commissioned | product + art |
| R3 | Per-renderable bone budget too small for the library | medium | high | regional renderable split (§10.3); measure at stage 1 | engineering |
| R4 | Device evidence is one run on one phone | certain | medium | record each run (§12.3); generalise no number beyond the device it came from; FPS/thermal gating deferred by the owner | engineering |
| R5 | Anatomy review capacity (physio/anatomist) is the bottleneck | high | high | batch reviews per layer; L3/L7 checklists reduce the time per muscle. **No review capacity is budgeted for stages 5 and 6**: those effort lines carry art and engineering only, and stage 6's ~47 muscles (~35 superficial, ~12 deep) are unreviewed at this mitigation's rate until a reviewer is named — the owner convention of §15 is what signs those gates meanwhile | product |
| R6 | Morphs degrade the surface around a joint silently | medium | medium | L6 declared ranges plus **mesh ring preservation** around each joint pivot; the bone-landmark rest invariant is not the check, because a vertex-only morph cannot move a bone | engineering |
| R7 | Attachment data drifts when `SkeletonDefinition` changes | medium | high | one `unitsPerMetre`, rest-length test, definition version pin | engineering |
| R8 | **Closed:** PR #385 merged into `main` at `abc2483` without changing a contract this document cites. What remains is a later rewrite of those contracts | low | medium | this RFC depends on its contracts, not its files; the stage-1 rebase plan (§15) is unchanged by the merge | engineering |
| R9 | Deep muscles / organs expand scope beyond appetite | high | low | §16 non-goals and the layer table's v1 column | product |
| R10 | Left/right handedness mismatch between anatomy and rig | medium | medium | `RigSideTest`; asset fix, never a render-time flip | engineering |
| R11 | L4 proves the polyline misses the ellipsoids, not that the rendered muscle or the anatomical surface does | medium | low | strategy A consumes the validated points unchanged and re-smoothing is forbidden (§7.4.1); the ellipsoid model is classified as an approximation (§11); L7 review covers what geometry cannot (§12.4) | engineering |
| R12 | The §7.4.5 detour heuristic fails on a configuration where a valid route exists, producing `INVALID` frames | medium | medium | failure is explicit and blocks L4 rather than shipping a penetrating path; the fixture set states which configurations must succeed; new cases are added as fixtures, not fixed by loosening the check | engineering |
| R13 | A new rig asset passes bind alignment but drifts in motion because segment lengths differ, or because the retarget composition accumulates direction error | medium | medium | §6.6 A2 **measures** the residual at the five named frames (four registered builders) — the length-sum bound is withdrawn as false — and `RigAlignmentResidualTest` is what reports it. **The fix is the rig/retarget pair, never an anatomy nudge**: A2's equal-length counter-case shows that equal lengths still drift under a rest-delta composition, so the correction is in the asset or in the retarget that drives it, and A2 measures the result. Moving an attachment to hide the drift is forbidden — an override is not an input to either test (§6.6) | engineering + art |

**Unresolved, with the smallest resolving experiment:**

1. **Bone budget per renderable — two measurements, and only one of them is M1's.** *(1a) Strategy C, stage 3:* load an
   eight-muscle layer with **72 helpers** on one device (18 head-side instances × 4 helpers — correct arithmetic for that
   hypothetical layer; an earlier revision said 32, which is two helpers per instance and a figure the strategy-C spec
   cannot produce). Two limits on what it decides: M1 has **zero** helpers (strategy A generates tubes, §10.3), so this
   measures nothing about M1's layer; and doubling from 72 goes 144 → 288, crossing the documented 255 limit in one step,
   so it establishes *that* a split is needed rather than *where*. The split is designed against `palette(skin)` (§10.3)
   measured per regional skin, not against the library total. *(1b) M1, stage 2:* for each of the 18 instances record the
   **final** `N_final` the solver produced (not the `N = 48` cap), the triangle count against the §10.3 formula and the
   frame cost of the generated layer, beside the FPS and thermal readings that are informational (§12.3).
   `pointCapacity` is what the budget must hold against, and this is the measurement that shows how far past 48 a tier's
   inserted points go. Neither half gates anything; together they replace two estimates with numbers.
2. **Strategy C authoring effort** — one muscle (rectus femoris) taken from attachment data to a path-rigged mesh end to
   end; measure artist hours. This decides the stage-3 art budget. M1 is defined as eight muscles (§15); if this
   experiment shows eight are unaffordable, the M1 definition changes in this document first — the experiment is a gate
   on the definition, not a licence to ship a different deliverable.
3. **Whether `anatomicalForward`'s semantics should be renamed** — not a blocker for anatomy (which uses segment rest
   frames), but it is a trap for every new author. A docs-only rename proposal belongs in a separate change.
4. **Deep-muscle value** — user research question, not an engineering one: does the education panel need deep muscles
   before organs?
5. **Which reference set the anatomical review cites** — §12.4 requires identifiable references but deliberately names
   none. Which atlas or dataset a reviewing clinician will accept is their professional call. Smallest resolution: ask
   the reviewer for two candidate sources and record the choice, with its edition, in the review file.
6. **Whether any cross-section response is ever justified** — M1 ships none (§7.6). The smallest resolving experiment is
   not an implementation spike but a literature question: does measured cross-section behaviour of the eight M1 muscles
   support a per-muscle model at all?
7. ~~Whether a Euclidean clearance guarantee is ever required~~ — **resolved by the owner's decision: it is, and §7.4.1
   is that guarantee.** The question assumed an intersection verdict was sufficient and priced a distance test as a
   separate contract; the owner required the distance, so §7.4.1 now states the swept-tube clearance in `eps_num` terms
   over a segment-to-ellipsoid distance predicate. Kept as the record of why the contract is distance-based, not as an
   open decision.
8. ~~Whether to move the Compose-dependent files out of `animation/`~~ — **resolved by #390**: the renderer and the
   playback adapter live in `render/`, `animation/` and `poses/` carry no Compose or Android imports, and
   `ArchitectureBoundaryTest` enforces it with no allow-list (§4.5). The question is kept as the record of why the move
   happened, not as an open decision.
9. **Which tool measures muscle-versus-skin intersection** — L6's "anatomy inside the body" and L5's self-intersection
   are not automated for M1 (§12.5). Building a mesh-intersection check is a tooling decision, not an architecture one;
   until it exists those checks are L7 judgements.
10. **Lumbar curvature** — the engine has one `LUMBAR` and one `CHEST` joint, so the spine is two rigid segments. M1 can
   show pelvic tilt and a whole-thorax rotation, but **not lumbar curvature (lordosis)**: a muscle whose path depends on
   a lumbar curve is authored against a straight spine, and the limitation belongs in the review record (§12.4) rather
   than in a pose. Known limit, not a blocker: an extra lumbar joint would change `SkeletonDefinition`, revalidate every
   pose and recalibrate every rig (§6.1), and no stage in §15 budgets for that.
11. **Roll convention for a position-derived segment frame (X9) — CLOSED: minimal rotation is the default roll
   convention, confirmed by the owner, and its anatomical adequacy is not claimed.** The segment frame is the minimal
   rotation taking `parentFrame · restDirection` to the published direction `p_child − p_parent`, applied in the parent's
   frame (§7.2, §7.4 step 1). It is chosen as a **default** because it needs no new authored data and is deterministic —
   not because a deterministic answer is an adequate one. The roll about that direction is arbitrary, an attachment offset
   `d` carries up to `|d| · θ` of error, and **`θ` has never been measured**; every statement about it in this document is
   a bound on an unmeasured quantity. Closing the question settles the convention, not the evidence, so three things are
   now specified rather than deferred:

   - **A named comparator, so `θ` has a reference at all — with an explicit `UNAVAILABLE` result.** A **pole-vector frame**
     derived from published joint positions — the segment's bend plane, from `p_parent → p_mid → p_child` — is computed
     beside the minimal-rotation frame on every measured frame, and the angle between the two rolls is the candidate `θ`.
     It needs no authored data either. `θ` is **`UNAVAILABLE`**, and never a number, in every case the comparator cannot
     serve: (a) the segment is the **root** (`PELVIS` has no `p_parent`); (b) it is **coincident or terminal** — `LUMBAR`,
     `CLAVICLE`, `SKULL`, `TOES` — so the three joints are not distinct and no bend plane exists; (c) the pole is
     **uninitialized** for this segment (see the policy below); (d) the frame is **held** — its bend is below the threshold
     and it is not a transition frame; (e) the frame is a **transition** frame, the first one after a hold that clears the
     threshold, because the reference changed between frames and comparing across that change would measure the change
     rather than the roll; (f) either bend-plane **leg has collapsed** in this posed frame — `sin θ_bend` divides by both
     leg lengths, so a legal pose in which `p_mid` reaches `p_parent`, or `p_child` reaches `p_mid`, has no bend plane at
     all (below). `UNAVAILABLE` is a result, not a zero: it is reported per segment per pose with its reason
     (`POLE_UNINITIALIZED`, `POLE_HELD`, `POLE_TRANSITION`, `POLE_LEG_DEGENERATE`, or the structural reason for (a)/(b)),
     it **does not pass** an
     acceptance row that requires `θ` on that segment, and an M1 review that cannot obtain `θ` for a segment records it as
     an unresolved limitation rather than assuming the segment quiet. A frame with an initialized pole, no transition, and
     `sin θ_bend ≥ sinBendMin` is measured normally (below). The comparator does **not** recover anatomical axial twist: it
     is a second geometric direction, not a source of truth about rotation, and agreement with it is evidence about
     determinism and plausibility, never about anatomy.
   - **The pole's initialization, update, hold, transition and reset, stated once.** The pole is a unit vector in engine
     world, `pole(S)`, per segment, and the policy is four rules and a declared reset list — the earlier text referred to
     "the policy below" and then never stated one, while the summary table called it declared:
     - **Initialization.** The first frame of a run — or the first frame after a reset — whose
       `sin θ_bend = |(p_mid − p_parent) × (p_child − p_mid)| / (|p_mid − p_parent| · |p_child − p_mid|)` is at least
       `sinBendMin` sets `pole = normalize(bend normal)` and reports `θ` for that frame. Every frame before it reports
       `UNAVAILABLE` with `POLE_UNINITIALIZED`; there is no guess and no bind-plane default.
     - **Update — every well-conditioned frame re-seeds the pole.** A frame that is well conditioned (initialized, not a
       transition, `sin θ_bend ≥ sinBendMin`) sets `pole = normalize(bend normal)` from **its own** positions before `θ`
       is read, because the comparator's reference is the current frame's bend plane — the criterion below builds both
       frames "from the same published positions" — and not a temporal average. Without this rule a limb that rotates
       rigidly between two well-conditioned frames — a hip rotating the thigh's bend normal from `+Z` to `−Y` — would be
       measured against a pole 90° from its own plane, and the reported `θ` would be the limb's rotation misread as a roll
       error. Hold is therefore the *only* case that keeps the previous pole, and that is what the hold is for.
     - **Hold.** While `sin θ_bend < sinBendMin` the frame keeps the last valid pole **without updating it**, reports
       `UNAVAILABLE` with `POLE_HELD`, and does not compute `θ`. The hold is the reason a near-straight limb does not
       manufacture a roll: the bend normal of a straight limb is noise, and holding the last real one is the only
       defensible reference.
     - **Transition.** The first frame after a hold whose `sin θ_bend` is at least `sinBendMin` **re-seeds** the pole from
       that frame's own bend plane and reports `UNAVAILABLE` with `POLE_TRANSITION` for that frame; the next frame
       resumes measurement. The transition is marked so that it can never be read as a measurement against the old pole.
     - **Reset — an explicit event list, not only an invalid frame.** `pole(S)` is cleared back to uninitialized on:
       (i) the first frame of a run; (ii) a frame that is `INVALID` or `NOT_PROCESSED` for the segment, where there are no
       published positions to build a plane from; and (iii) every **timeline discontinuity** — a seek, a replay, a
       pause/resume across a gap, a state restore, and a backward or non-monotonic timestamp — **including a seek or
       replay whose target frame is perfectly well conditioned**. The third group is the one an earlier revision missed:
       those events are discontinuities of the timeline, not of the pose, so no per-segment status reports them and the
       pole would otherwise be carried across the jump and compared against a frame it never followed. The frame that
       observes the event reports `UNAVAILABLE` with `POLE_UNINITIALIZED`, and the next well-conditioned frame re-seeds
       under **Initialization**. Reporting a reset costs one frame of `θ`, which is why the count of reset frames is part
       of the measurement record rather than invisible.
     - **A collapsed leg is its own reason, and the reasons have an order.** `sin θ_bend` divides by both leg lengths, so
       a legal posed frame in which either leg is below `τ_len · max(1, |restDir| · s)` (§7.2's own degeneracy rule for
       this segment) reports `UNAVAILABLE` with **`POLE_LEG_DEGENERATE`** and no bend plane is built; it is not a hold and
       not a roll error, because the frame carries no direction to compare. When more than one condition applies the
       **first match in this order** decides, so two implementations report the same reason: the structural reasons of
       (a)/(b), then `POLE_LEG_DEGENERATE` (f), then `POLE_UNINITIALIZED` (c), then `POLE_TRANSITION` (e), then
       `POLE_HELD` (d).
   - **Three sequences the comparator's own test must run.** `SegmentFrameRollComparatorTest` (§12.2) pins the policy
     where each rule is least obvious, because each of the three was undefined in an earlier revision: a **valid → valid
     rigid rotation**, where the bend normal moves from `+Z` to `−Y` between two well-conditioned frames and the second
     must be measured against its own plane (a held pole reports a 90° error that is not there); a **direct seek** to a
     different well-conditioned frame, which must report `POLE_UNINITIALIZED` for the frame that observes it and then
     measure, rather than comparing across the jump; and a **collapsed leg**, where `p_mid` reaches `p_parent` (or
     `p_child` reaches `p_mid`), which must report `POLE_LEG_DEGENERATE` rather than divide by zero. The row asserts the
     policy; it asserts no value of `θ` and no value of `τ_roll`, both of which stage 1 sets.
   - **The experiment covers all four pilots, not three named poses.** `squat` (bottom and top), `standard push-up`
     (bottom), `bird-dog` and `row_standard` (mid-pull) — the four exercises of X8 and the five poses of §12.4 RV4 —
     because two of them are exactly where a bend-plane comparator is least stable (bird-dog's extended limbs, row's
     authored elbow-pole intent, `RowsPose.kt:21-30` at the pinned base) and where the M1 eight muscles are loaded
     differently. A three-pose set of squat, push-up and overhead reach would have measured the easy cases. The four
     degenerate segment rows and the root report `UNAVAILABLE` on all five frames by construction — that is a property of
     the comparator, not a measurement gap, and it is the reason the criterion is stated over the rows that *can* be
     measured rather than over all fourteen.
   - **The criterion, its reference, its uncertainty and its threshold.** `θ` is measured per segment as the roll angle
     between the minimal-rotation frame and the pole-vector frame, both built from the same published positions, and the
     reference roll is the **pole-vector frame**. The uncertainty is **not** a function of segment length alone: it
     depends on the **magnitudes of the coordinates** that were rounded and on the **conditioning of the bend plane**.
     The withdrawn form `2 · ulp(L) / L` understates it by two orders of magnitude on a represented input: real
     endpoints `(599.9999697876, 600)` and `(600.0000302124, 601)` round to `Float` `(600, 600)` and `(600, 601)`, so
     the published direction loses `6.04248e-5 rad` while that formula, at `L ≈ 1`, reports `2.38419e-7 rad` — a factor
     of **253**. What is reported instead is a **labelled heuristic**:

     ```
     δθ  ≈  4 · q / (L · sin θ_bend)      // HEURISTIC. Not an upper bound; no ≤ is claimed and nothing is gated on it.
                                          // q = the half-ulp of the largest published coordinate magnitude,
                                          // L = the shorter of the two bend-plane legs, θ_bend the bend angle
     ```

     **It bounds nothing, and the earlier revision wrote an inequality that a represented input violates.** Exact
     binary32 published points `P0 = (600, 600, 600)`, `P1 = (601, 599, 600)`,
     `P2 = (602.010009765625, 598.010009765625, 599.97998046875)`; binary64 pre-rounding points `P0 + (h,h,h)`,
     `P1 − (h,h,h)`, `P2 + (h,h,h)` with exactly `h = 0.0000302124022937277914024889469146728515625`, each of which rounds
     back to the `Float` point listed. With `q = 2⁻¹⁵ = 0.000030517578125`, `L = √2` and `sin θ_bend ≈ 0.017334817526`,
     the form returns **0.00497938588731411 rad**, while the 100-digit cross-product reference gives a bend-normal angular
     error of **0.008537134229444975 rad** — **1.7145×** the alleged bound. Subtracting the first leg's own swing
     (`0.00007400496938822332 rad`) still leaves **0.00846312926 rad**, above it. So the form is reported beside every
     `θ` as an order-of-magnitude indicator of conditioning, it never sets availability, it never gates a row, and no
     `τ_roll` comparison consumes it. A validated 3-D error bound may replace it at stage 1; until one exists, `θ` is
     reported with a heuristic and with `UNAVAILABLE` wherever the threshold rule below applies.

     **Availability is decided by the threshold, not by the heuristic.** The one threshold is a **sine**:
     `sinBendMin`, a stage-1 field, compared against `sin θ_bend` — never against `θ_bend` in radians and never against
     both. The angle form is not a second rule: `arcsin(sinBendMin)` is quoted in the review file for a reader's
     convenience and is used nowhere in code. One threshold, because the two forms disagree on legal inputs: for the
     exact points `(0,0,0) → (1,0,0) → (2, 0.125, 0)` the bend angle is `0.124354994546761438 rad` and its sine is
     `0.124034734589208454`; with an illustrative binary32 threshold `0.1242000013589859` the **angle** rule measures the
     frame and the **sine** rule reports `UNAVAILABLE`, for the same triple. The sine is the quantity the conditioning
     needs and the one the heuristic is written in, so the sine is the threshold. The pass criterion remains
     `|d| · θ ≤ τ_roll` for the widest attachment offset `d` in the M1 library (0.03 m today, from the placeholder
     coordinates of §7.3); **`τ_roll` is set at stage 1**, when the first real attachments and the first measured `θ`
     exist, and it is recorded in the review file beside the `UNAVAILABLE` rows. Fixing it now would be fixing a
     threshold before knowing either side of the comparison.

   The alternatives that were considered and are not taken: an authored roll reference per segment (a stable up-vector —
   more authoring, and it still has to be reviewed against anatomy) and a rig bone-axis alignment (couples anatomy to one
   asset). They stay available as comparators; neither is the default. Worst case the measured `θ` is large and
   `τ_roll` cannot be met on some segment: that is a finding about the convention, recorded per segment, and the answer is
   an authored roll reference for that segment — not a weaker threshold.
12. **The A1 baseline, the calibration pair, and what an absolute length means (X10) — CLOSED.** The shipped
   `humanoid_v1` asset does not pass A1, and the baseline is recorded as a **measured fact of the X1 bind frame**: the
   worst mapped joint is `CHEST` at **175.1 mm**, **350.2×** the 0.5 mm tolerance, with `LUMBAR` at 110.0 mm and only
   `PELVIS` inside it (§6.6). The **565.6 mm** hand figure quoted in earlier revisions is a *different* measurement — the
   posed standing frame, where the arms fold out for counterbalance — so it is kept as a **labelled posed comparison**
   and no longer carries the A1 claim; a stage-1 gate that cited it was gating on the pose, not on the rig. What is
   closed:
   - **The baseline is recorded, not argued.** A1 is not weakened to fit the asset; a rig that passes A1 is the stage-1
     deliverable (§15), and the failing numbers above are the evidence that the deliverable is real.
   - **Metres stay out of the UI** (X4): the panels show `relativePathLengthChange` only, and `modelledPathLength` stays in
     the data and the debug overlay.
   - **`s` and the bind pose are per-body parameters tied to named landmarks, never to height.** `s` is
     `PELVIS.y / trochanter height` (§7.2) and the bind pose is the X1 A-pose computed from `SkeletonDefinition`; a body
     with a different `s` is a different calibration of the same rule, and no field of the document derives either from a
     stance height, a crown height or a `heightMetres` value.
   - **M1 is one generic reference body with a fixed `s`** = `235 / 0.95 = 247.3684` u/m. One body, one calibration, no
     per-character scale until a rig profile revalidates it (§6.1); the four body families are stage 5.

   **Recommended default, and it is a decision about what M1 displays, not only about a constant (X4).** For M1 the only
   length readout in the UI is `relativePathLengthChange`; `modelledPathLength` in metres stays in the data and the debug
   overlay until a calibration pair is named **and** an evidence-backed uncertainty exists for it. The reason is in §9.2,
   and it is not that a ratio is free of the calibration — see the next paragraph.

   **"Dimensionless" is not "calibration-free", and the difference is measurable.** An attachment resolves as
   `jointPosition + R_segment · (offsetMetres · s)` (§6.6). Offsets are **transformed vectors**, and a path length is the
   sum of the solved polyline's segment lengths — **not** `chord + s · Σ|offsets|`, which is false in both directions: two
   10-unit offsets pointing *inward* along a 100-unit chord give an 80-unit path where that formula promises 120, and a
   wrap detour lengthens the path by an amount no sum of offset lengths describes. The ratio is therefore stated for
   **one constructed geometry**, not as a law: both attachment offsets lie along the chord and point away from each other,
   so the resolved attachments are exactly `s · Σ|offsets|` further apart than the joint chord and the solved path is the
   straight segment between them. Only in that case is the length `chord + s · Σ|offsets|`, and only there does

   ```
   (posed_chord + s·Σ|offsets|) / (rest_chord + s·Σ|offsets|) − 1
   ```

   hold. With rest `100 + 0.1 s`, posed `200 + 0.1 s` and offsets totalling 0.1 m — **0.801688 at `s = 247.3684`** and
   **0.814848 at `s = 227.2222`**: same muscle, same pose, two calibrations, and the displayed number moves by **1.32
   percentage points**. The argument only needs the ratio's *dependence* on `s`, which is why the constructed special case
   is enough here and no general path-length formula is claimed.

   **The residual is affine, not proportional, and every calibration is recomputed rather than rescaled.** For a fixed
   engine coordinate `E` and asset coordinate `A` of the same landmark, the signed residual is

   ```
   offset_metres = E / s − A          // affine in 1/s, intercept −A
   offset_units  = E − s · A          // affine in s,   intercept  E
   ```

   so neither is proportional to the constant, and a table scaled "linearly with `s`" is wrong in one of the two
   directions whichever one it picks. Recomputed at the chest with `E = 355` units and `A = 1.26 m`: **175.106 mm** at
   `s = 235/0.95` and **302.347 mm** at `s = 409/1.80`; naively rescaling the first by `s₁/s₂` gives **190.632 mm**,
   which is **111.7 mm** away from the recomputed value. Rescaling a residual table is therefore never done in this
   document: each row is recomputed at the calibration it is quoted against.

   **Scale invariance has exact preconditions.** The relative change is invariant only under a **common similarity
   transform of every geometric input and every dimensional solver parameter** — the attachment offsets, the obstacle
   radii, `eps_num`, `corridorMetres` and the 0.01 m resampling spacing — applied with the same route and the same
   discretisation (`N`). Scale the body and leave `eps_num` or the resampling spacing alone and the route changes, so the
   length changes: the invariance is a property of the whole solve, not of the ratio. A **fixed additive tendon length in
   metres** would break it for the same reason (it would not scale with the body); `visualTendonPortionLength` is a
   *fraction* of the path (0.10 + 0.16 = 0.26), so it does not, and the schema has no absolute tendon length.
   Two fixtures pin this: `scale_common_control` (a body, its anatomy and **every** solver parameter scaled by `k` — the
   relative change must be identical) and `recalibration_counterexample` (the same rig recalibrated at a different
   `unitsPerMetre` — the relative change must differ, and a test that expects it to be invariant fails).
   Honest absolute metres need a rig that passes A1 — and a rig that passes A1 is the *engine's* figure, whose crown is
   1.653 m and whose head radius is 72.8 mm (§6.6, §15 stage 1), not an independently verified human — so even then the
   number is a modelled length, not a measured one. That rig is the stage-1 deliverable, and
   until it exists an absolute length is a plausible number, not a measured one.

   **The evidence, measured at `16e0487` with the asset parsed at rest** (engine units at 247.37 u/m against the asset's
   own metres): the pelvis is 0.950 m on both sides, so the pelvis pair is consistent by construction; pelvis → head
   centre is 0.6306 m engine against 0.650 m asset (−19.4 mm, −3.0 %); pelvis → ankle 0.8307 m against 0.870 m
   (−39.3 mm); pelvis → toe joint 0.8307 m against 0.920 m (−89.3 mm); the head centre is 1.581 m above the floor
   against 1.600 m; the crown (`HEAD_POS.y + headRadius` = 409.0 units) is 1.653 m against 1.780 m. **The mismatch is not
   in the torso** — the torso span is the smallest of the three at −19.4 mm, the leg span is −39.3 mm to the ankle and
   −89.3 mm to the toe base, and the largest single difference is the head model: the engine's crown sits 0.073 m above
   the head centre against the asset's 0.180 m, which is what pulls a crown-based calibration down to 229.8.

   Smallest resolving experiment: build the stage-1 rig, measure both spans on it, and keep the pair whose landmarks
   agree on that rig — then **re-measure** the §6.6 baseline on that rig rather than rescaling it. That is the rule the
   affine form above forces: a residual is recomputed at the calibration it is quoted against, in either space, and a
   table carried across a calibration change by scaling is wrong even when its direction of movement looks plausible.

**Owner decisions of record (X1–X10).** Each is written above as the chosen design and repeated here as a decision of
record, with the owner's status: **X1 and X3–X8 confirmed by owner**, **X2 reversed by owner (fingers kept)**, and **X9 and
X10 closed by the owner as well**. "Closed" settles the question; it does not supply the evidence, so both closed rows
carry a measurement obligation into stage 1 and question 11 and question 12 above state exactly what it is. X9 keeps minimal
rotation as the default roll convention and **does not claim anatomical adequacy**; X10 records the A1 baseline as a
measured fact at the X1 bind frame, keeps metres out of the UI, ties `s` and the bind pose to named landmarks rather than
to a height, and fixes M1 to one generic reference body.
The smallest experiment is kept against each row because a confirmed design still has to be measured, not assumed.

| # | Default | Where it is applied | Status | Smallest resolving experiment |
| --- | --- | --- | --- | --- |
| **X1** | A1's bind frame is a **canonical straight-limbed A-pose** computed in character-side code from `SkeletonDefinition` segment lengths, not an engine pose and not the folded-arm standing frame | §6.6 (the pose and its 23 joint positions), §15 stage 1 | **confirmed by owner** | generate the rig from the definition and run `RigLandmarkAlignmentTest` against that frame; if the frame's limb directions disagree with the asset's bind, the generator is wrong, not the frame |
| **X2** | **The ten finger bones stay**, as visual-only bones: no engine joint, no A1 row, excluded from `RigLandmarkAlignmentTest` and listed as unmapped, counted in the 30-bone visual budget (10 of 30), and inheriting their hand bone's frame parent-relative so the fingers follow the hand rigidly; the generator lays them along the X1 hand direction | §6.1, §6.2, §15 stage 1 | **reversed by owner: fingers kept** | render the hand at the smallest shipped size and check that the ten rigid finger bones read as a hand; if the silhouette fails, the fix is hand geometry, not finger articulation the engine does not have |
| **X3** | **Position-driven retarget**: each mapped bone's rotation is the minimal rotation from the bind direction **the posed parent carries** (`a = B · R_target_world(parent) · B⁻¹ · d_bind_engine`) to the published segment direction, composed as `B⁻¹ · D · B · R_target_world(parent)` — **conjugated once, transported by the parent's posed rotation**, same frame rule as X9; the rig root uses the derived pelvis frame and the zero-length mapped joint inherits its parent's; `basis = Ry(+90°)`, `s = 235/0.95`, translation `B⁻¹ · Δp / s` (0 at the pelvis) | §6.2 (the rule), §11 (classification), §15 stage 1 | **confirmed by owner** | land the change behind the existing `PoseRetargeter` signature and compare A2 residuals before and after on the five named frames, with `PoseRetargeterBasisTest`'s parent-swing and wrong-basis controls red when the untransported composition is restored |
| **X4** | **M1's UI shows `relativePathLengthChange` only**; absolute metres are withheld until a named calibration pair and an evidence-backed uncertainty exist, and `modelledPathLength` stays in the data and debug | §9.2, §12.5, §13 question 12, §15 stage 2 and M1 item 4 | **confirmed by owner** | name the pair, measure both spans on the stage-1 rig, and quote the uncertainty; only then is a metre value displayable |
| **X5** | An attachment-contact **exemption is one reviewed transition envelope**, and the mask every consumer applies is the **effective** one — the declared interval bounded, **on the exempted endpoint's side**, by the first station on the current path where `d − r ≥ e`; any drop below the margin after that is a violation, and the same effective mask applies to endpoint, sample, construction, projection and final validation | §7.3 (`ContactExemption`), §7.4.1, §12.2.1 (`exempt_re_entry`, `exempt_re_entry_after_mutation`) | **confirmed by owner** | run the 1-D re-entry fixture: the path re-enters at stations 0.5625–0.6875, which lie **inside** the declared `[0, 0.7]`, so an implementation that treats the interval as the exemption **accepts** them. Acceptance is the failure; the envelope check must reject the re-entry, and a fixed interval cannot |
| **X6** | The **continuity observer is diagnostic only** and never a gate; **route hysteresis is removed from M1** | §7.4.3, §12.2 (`MusclePathContinuityTest`) | **confirmed by owner** | the observer-correctness fixtures: a rigid translation must not alarm, a branch switch must |
| **X7** | **`NO_ROUTE` is disabled** until a proof procedure exists; the frame exposes a **terminal search outcome** (`SEARCH_EXHAUSTED` / `PASS_CAP`) separately from the **last geometric cause** | §7.4.1c, §7.4.5, §12.2.1 | **confirmed by owner** | `final_clearance_and_budget_both_fail`: one record, two fields, neither overwriting the other |
| **X8** | **Categorical roles ship in M1**: schema, provenance/review validator and display gate, for exercises with a reviewed source only; the **pilot exercise list is owner-approved and fixed**: `squat`, `standard push-up`, `bird-dog`, `row_standard` | §7.3 (`MuscleRole.UNKNOWN`), §8, §9.2, §12.4 (RV4 poses), §12.5, §15 M1 item 10 | **confirmed by owner** | the validator runs on those four and every other exercise must display no role; the §12.4 pose set is drawn from them so that every M1 muscle is reviewed |
| **X9** | **Minimal rotation stays the default roll convention**, confirmed as a **default** and **not** as evidence of anatomical adequacy; a **pole-vector frame** from published positions is the named comparator, with the declared initialization/hold/transition policy for near-straight limbs above; the `pelvis` root takes a derived frame so an authored tilt is followed | §7.2 (the rule, the root frame, the roll bound), §11, §13 question 11, §15 stage 1 | **closed by owner — adequacy not claimed** | measure `θ` per segment on **all four pilots** (squat, standard push-up, bird-dog, `row_standard`) against the pole-vector comparator, report the conditioning **heuristic** beside it (labelled as such — it is not a bound), report `UNAVAILABLE` with its reason wherever the single `sinBendMin` threshold withholds `θ`, and **set `τ_roll` on `\|d\| · θ` at stage 1** |
| **X10** | **A1's baseline is the measured X1 bind-frame result** (`CHEST` 175.1 mm, 350.2×); 565.6 mm stays as a labelled posed comparison. Metres stay out of the UI (X4); `s` and the bind pose are per-body parameters tied to named landmarks, never to height; M1 is one generic reference body at a fixed `s = 247.3684` | §6.6, §9.2, §13 question 12, §15 stage 2 | **closed by owner** | build the stage-1 rig, measure both spans on it, keep the pair whose landmarks agree — and **recompute every residual at that calibration**, never rescale the old table |

**Known traps from the device run (spike #384).** Each cost a debugging cycle on real hardware, and each is a property of
the current code, not of the model. PR #385 at `bcb3658` contained none of the first three fixes; its last head before
merge, `23ef1c0` ("fix(character): load gltfio JNI, attach the surface late, bind the scene"), carries all three, so they
are recorded as traps of the spike and as facts about the code `main` now builds on, not as open work items.

- **`Gltfio.init()` must accompany `Filament.init()`.** `libgltfio-jni.so` is packaged but not loaded by `Filament.init()`,
  so the first `UbershaderProvider` call throws `UnsatisfiedLinkError`.
- **Attach the `TextureView` to `UiHelper` only after `onViewAttachedToWindow`.** Attaching it from the Compose factory
  hands `UiHelper` a view whose `SurfaceTexture` does not exist yet.
- **`view.scene` must be assigned.** Without it nothing is drawn — not the character and not even the clear colour, so
  the viewport keeps the surface's default black and the swap chain is never touched.
- **`Renderer.readPixels` never fired its callback in the spike**, so image checks read `TextureView.getBitmap()` from
  the view's own `SurfaceTexture` instead of the framebuffer.
- **The fixed camera crops floor-level poses.** `lookAt(0.0, 1.05, 3.4)` with `setLensProjection(45.0, …)` frames a
  standing figure; floor-level poses such as the plank fell outside the frame, so any image-based check must refit the
  camera per pose.

Two of the five are silent: a missing `view.scene` and a stale texture attachment both present as a black rectangle,
which reads like a shader or asset defect rather than a wiring one — the reason they are recorded here instead of
rediscovered on every device.

---

## 14. Decisions

Each decision gives a recommendation, the reasoning, and — where the repository cannot settle it — the smallest
experiment.

**D1. One shared visual rig or multiple compatible rigs?**
*One rig for v1 (`humanoid_v2`); further rigs only as versioned profiles with their own revalidation.* The v1 constraint
is a scope decision: one `BoneMap`, one rest invariant, one pose library, one anatomy library, one thing to prove before
M1 ships. It is not a permanent prohibition. Multiple rigs are allowed when they preserve joint names, hierarchy, rest
pose and bind pose — which in practice means the same rig contract with different meshes and, if needed, different
segment proportions. The value proposition holds only while the rigs share those contracts; the moment two rigs diverge,
`BoneMap` forks and the rest invariant is re-proved per rig. That is why a second rig is a versioned decision with a
revalidation cost — a motion project, not a second character — and why §6.1 defines the extension path instead of
forbidding it.

**D2. Shared anatomical skeleton versus visual deformation skeleton?**
*Both, with strict roles and no shared identity.* The 33-joint canonical skeleton is the only anatomical authority; the
visual rig is a deformation device that may add fingers, scapulae, twist bones and helper bones but may never be
referenced by anatomy data. Anatomy attaches to `Joint` identifiers and segment rest frames, which are the same on every
character. The visual rig's extra bones follow from it; they do not define it.

**D3. Procedural muscle geometry, pre-authored meshes, or hybrid?**
*Hybrid: authored meshes skinned to path-driven helper bones; procedural tubes first and as the reduced tier.*
Pre-authored meshes alone fail the mechanical test (length becomes implicit and untrustworthy); procedural tubes alone
fail the quality test and cost CPU every frame. The hybrid keeps authored surface and measured length, and the path is
the contract that lets the first implementation (tubes) be replaced without touching anatomy or motion.

**D4. Morph targets versus parameterized mesh generation?**
*Morph targets for surface, uniform scale for size, in v1; per-bone proportions deferred behind a versioned
`SkeletonDefinition`.* Bone lengths are baked into inverse binds and drive IK reach; changing them per character changes
the motion solution, not just the picture. Morphs and uniform scale cover surface shape and overall size — they do not
cover segment proportions, because a long-femur body is a different motion definition, not a different morph weight.
Supporting one means a new `SkeletonDefinition` id, a rig profile that still satisfies §6.1, and revalidation of every
pose, attachment and retarget path. Deferred, not forbidden.

**Scaling the visual asset is not the transform X10's invariance is about.** A profile's uniform scale changes the drawn
size and the asset's metres; it does not scale the anatomy's attachment offsets, the obstacle radii, `eps_num`,
`corridorMetres` or the 0.01 m resampling spacing, and it does not re-run the solve. The invariance §13 question 12 states
holds only for a **common similarity transform of every geometric input and every dimensional solver parameter, applied
with the same route and the same discretisation `N`** — so "uniform scale for size" here is a presentation change and says
nothing about `relativePathLengthChange`. This decision is therefore about appearance; whether a size change touches the
motion or anatomy solve is the separate question §6.1 answers (it does not, in v1).

The fixed floor in `τ_len · max(1, |restDir| · s)` (§7.2) is the worked counterexample to reading invariance off the ratio
alone: `max(1, ·)` is not homogeneous, so a body scaled down until `|restDir| · s < 1` keeps the same absolute threshold
while every length around it shrinks, and a segment's degeneracy verdict then depends on the scale. `MuscleLengthScaleTest`
consequently exercises the **branch**, not only the ratio: a common transform that keeps `|restDir| · s > 1` must leave the
verdict unchanged, and one that crosses the floor must report the change — a test that only compares the ratio formula
would pass while the solver's own threshold moved underneath it.

**D5. How should muscle geometry react to length changes?**
*Through the path only. M1 applies no cross-section response.* A muscle's length and position follow from its solved
path; its cross-section is constant. This is the honest option: **the modelled path becomes shorter and that is all the
picture shows** — nothing in it implies a contraction magnitude the data cannot support. The isovolumetric cylinder law
survives in §7.6 only as an optional research hypothesis with validation preconditions attached — not as a v1 mechanism —
and activation never moves geometry in either case.

**D6. How should scapula and shoulder-girdle motion influence muscle paths?**
*Through attachments pinned to `SCAPULA_A/P` and `CLAVICLE_A/P`, plus one or two thorax wrap surfaces.* The engine
already animates the girdle (`SkeletonFactory.kt:71-80`; scapular motion is authored and solved), so the paths follow for
free. What must not happen is a second shoulder model in the character layer (a look-at or an IK-driven scapula): that
would be a competing solver and would desynchronise muscles from the mesh. **The rig's scapula bone is driven by the same
position rule as every other mapped bone** — the minimal rotation from its bind direction to the published
`SCAPULA → SHOULDER` direction, carried through the parent's posed rotation (§6.2, X3) — and not by an "engine scapula
rotation". There is no such rotation to consume: the engine publishes positions, the published rotation of a girdle node
is its parent's (§7.2), and requiring a rotation source that does not exist is what produced the earlier contradiction
between this decision and the confirmed X3 rule. Direction and parent roll are what the rig needs, and both are functions
of published positions.

**D7. Authored activation per exercise, or derived from reusable rules?**
*Authored per exercise, with reusable phase rules and a required source.* Deriving activation from joint angles or
muscle length would manufacture physiology from geometry — precisely the fake mechanism §11 forbids. A rule set
("prime mover in a knee-extension-dominant exercise") may generate a *draft* table, but a human
signs it and the source field records why.

**D8. How should anatomical layers be stored and loaded?**
*One GLB per layer per character; anatomy and activation as versioned JSON shared across characters; load on first show,
stay resident until the character detaches or an explicit eviction.* The layer boundary is the asset boundary because that
is what makes toggling free of engine work and what keeps the muscle library out of the APK's always-resident set. Hiding
a layer is a visibility flag; it is not an unload, and the RFC no longer claims otherwise (§9.1). **M1's muscle layer is
the procedural exception**: strategy A generates its tube mesh per frame, so `MUSCLES_SUPERFICIAL` has no GLB, no skin and
no bind frame in M1 at all (§10.5), and the asset-validation rows that name a skinned layer do not apply to it. The rule
binds the skin and the skeleton overlay in M1, and the muscle layer from stage 3, when strategy C replaces the generated
mesh with an authored one. That is a procedural exception to this decision, not a second storage scheme.

**D9. What belongs in GLB assets and what in runtime data?**
*GLB: meshes, skin weights, inverse binds, morph targets, materials, textures, helper-bone bind data. Runtime JSON:
rig mapping, attachments, wrap surfaces, roles, activation, labels.* Semantics in a binary is a review defect: anatomy
must be diffable, reviewable by a non-engineer, and shared across characters. Geometry in JSON would be the opposite
mistake.

**D10. How to support future female and male variants without duplicating anatomy or exercise logic?**
*One rig, one anatomy library, one activation table; variants are meshes + morph sets + materials, with sparse, named
body-family overrides in anatomy data.* The claim that most muscle attachments hold across adult body types is an
**unverified hypothesis, not a finding**: no attachment in this document has been measured on any body (§7.3, §7.7.2), so
a shared point is a default that a **per-body-family review must re-approve before it is reused** for a new family —
never an assumption that transfers for free. Where a review finds a point measurably wrong (pelvic width, shoulder
breadth), the override is a reviewed data line, not a forked file. The acceptance test is mechanical: adding a variant
must add zero pose builders, zero `MuscleDefinition`s and zero activation rows.

---

## 15. Implementation roadmap

Stages are outcomes, not PR-sized steps. Each stage ends with something demonstrable and with its own evidence. Effort
is engineering-weeks for one experienced engineer, ±50%, excluding art; art is listed separately because it is the
schedule risk, not the engineering one.

**Who signs a gate until a reviewer is named.** Every review gate in this roadmap — RV1–RV6 of §12.4, the stage-0
walkthrough and the stage-2b timelines — is signed by the **project owner** until a qualified reviewer is named in
the record. Naming one later amends the record; no gate in this section waits on an expert this document has not
named.

**Summary**

| Stage | Outcome | Eng. | Art / expertise | Gate |
| --- | --- | --- | --- | --- |
| 0 | This RFC reviewed and accepted | 0 | — | reviewer sign-off |
| 1 | Rig v2 + asset validation + device evidence | 3–4 | 2–3 rig-art | L2 green including A1/A2, and a device run recorded |
| 2 | Anatomy kernel + 8 muscles as procedural tubes | 4–5 | 1 anatomist week | L3/L4 green on fixtures |
| 2b | M1 role schema, four timelines, display gate, landmark taps | 1–2 | 1 review week | §8 validator green on the four pilots; tap-hint demo |
| **M1** | **one character, 1 preset, the four pilot exercises, 8 muscles, 3 layers** | — | — | **demo + §12.5 gate + §12.4 record** |
| 3 | Path-rigged authored muscle meshes | 4 | 6–8 muscle art | L5 + visual review |
| 4 | Role tables beyond the pilot + phase timelines + education panel | 2 | 1 review week | §8 validator + sign-off |
| 5 | Morphology v2: four body families (the first stage with more than one preset) | 3 | 6 art | L6 green per preset |
| 6 | Library expansion: ~35 superficial, ~12 deep | 6–8 | 12–16 art | L3/L4/L5 at scale |
| 7 | Labels, picking, explanations | 3 | 2 | visual review |

### Stage 0 — Ratify this document

- **Objective:** agree the model boundaries, the non-goals and the accuracy policy before code exists.
- **Components:** this document; review notes.
- **Dependencies:** PR #385 **merged** into `main` at `abc2483`, recorded as a decision input (§3.1).
- **Expected files:** `docs/architecture/MONK_HUMAN_FRAMEWORK.md` only.
- **User-visible deliverable:** none.
- **Automated acceptance:** document builds no code; CI unaffected. That criterion is the whole of the automated one —
  it proves the file parses and nothing else.
- **Manual acceptance, and this is the real gate:** a reviewer can state the ownership of any object in §5.1 without
  reading the code; the walkthrough is signed by the project owner until a reviewer is named (§15).
- **Risks:** scope creep into "design the art".
- **Art/expertise:** none.

### Stage 1 — Rig v2, asset validation, device evidence

- **Objective:** replace the generated 32-joint test rig with a rig that passes A1 **by construction**, prove the contract
  mechanically, and measure the presentation on at least one real device. The rig is produced by option (c) of §6.6 — a
  generator that writes the X1 canonical A-pose from `SkeletonDefinition` — not by re-pivoting the shipped asset.
- **Components:** the rig generator and its output asset (23 mapped bones including the scapula pair, plus the **10
  visual-only finger bones**, X2 reversed by owner), `BoneMap` manifest with `basis = Ry(+90°)`, the position-driven
  `PoseRetargeter` (X3), `RigAssetContractTest`,
  `PoseRetargeterRestInvariantTest`, `RigSideTest`, `RigLandmarkAlignmentTest`, `RigAlignmentResidualTest`,
  `SegmentFrameDirectionTest`, `SegmentFrameRollComparatorTest`, `UnmappedBoneInheritanceTest`, `PoseRetargeterBasisTest`,
  budget checks, device measurements,
  and two measurements this stage owns rather than inherits: the **derived pelvis root frame** compared against the
  authored tilt on `squat_standard` and `bird-dog` (§7.2), and **`θ` per segment** from the pole-vector comparator of
  §13 question 11, with `τ_roll` set here and recorded in the review file.

**The stage-1 PR sequence.** The stage is one outcome, not one change: seven PRs, each independently green, and each owes
the §12.2 rows named in its row. P4 must **not** be split from P3 — switching the composition before the A1-passing rig
exists makes the currently-green neutral-pose test fail on `humanoid_v1`, which is correct behaviour and a forbidden
regression at the same time, so the rest-invariant extraction ships in the same PR that changes the composition.

| PR | Purpose | Tests (§12.2) | Depends on |
| --- | --- | --- | --- |
| **P1** `feat(animation): derive segment frames from published positions` | §7.2's frame derivation — the derived root frame, the X9 rule, the degenerate rows — plus the pole-vector comparator, and the two engine-side measurements this stage owns (`θ` per segment, the derived pelvis frame against the authored tilt) | `SegmentFrameDirectionTest`, `SegmentFrameRollComparatorTest` | nothing: no character code, and it runs on `main` |
| **P2** `feat(character): BoneMap as data with a coverage test` | the §6.2 map as the one declared source for the 23, including the scapula aliases the resolver lacks; a behaviour-preserving refactor of the existing alias table | `BoneMapCoverageTest` | nothing for the file itself (`character/` is on `main` since `abc2483`) |
| **P3** `feat(character): generate humanoid_v2 from SkeletonDefinition` | the rig that passes A1 **by construction** (§6.6 option c): generator, asset, validator | `RigAssetContractTest`, `RigLandmarkAlignmentTest`, `RigSideTest` | P2 — the 23 are the map's |
| **P4** `fix(character): position-driven retarget with a real basis` | X3 replaces the rest-delta composition, `basis = Ry(+90°)` is supplied at the construction site, the pelvis translation becomes `B⁻¹ · Δp / s`, and the rest invariant is **extracted** from `PoseRetargeterTest` and re-pointed at `humanoid_v2` | `PoseRetargeterBasisTest`, `PoseRetargeterPelvisTranslationTest`, `PoseRetargeterRestInvariantTest`, `NonCommutingSplitTest` | P2 (`basis`), P3 — **not splittable from P3** |
| **P5** `fix(character): unmapped bones inherit their mapped parent` | §6.2's parent-relative bind-rest inheritance, so a visual-only bone rides its mapped parent instead of freezing at bind | `UnmappedBoneInheritanceTest` | P3, P4 |
| **P6** `test(character): measure the A2 residual at the five named frames` | A2 as a **measurement**: per mapped joint, per frame, at the five frames drawn from four builders | `RigAlignmentResidualTest` | P3, P4 |
| **P7** `docs(character): record the stage-1 rig evidence` | the device run, the manual acceptance, and the residual, `θ` and `τ_roll` tables in the review file | none new — P1–P6 are the automated part | P3–P6 |

- **Ten steps of the generator, in order.** Each is a checkable artefact, and steps 3–7 are the ones a hand-authored rig
  has historically got wrong:
  1. read `SkeletonDefinition` and compute the X1 A-pose joint positions (the table in §6.6) — no engine frame is read.
     The v1 generator's own header calls its joint table a **"T-pose"** (`scripts/character/generate_humanoid_v1.py:28`)
     while the coordinates it writes are **arms-down**; the v2 generator must not repeat that comment — the stance it
     writes is the X1 A-pose, and the header has to say so;
  2. emit one node per **mapped** joint (23) at its A-pose position, in metres (`position / s`), parented per
     `SkeletonFactory`'s chain, with `PELVIS` as the skin root;
  3. **skip the one zero-length tube**: `CLAVICLE` and `SCAPULA` are coincident with `CHEST` (§7.2) — emit the **nodes**
     (so the chain and the retarget stay total) but no bone segment between them, and no zero-length inverse bind. This
     applies to the **girdle only**: `LUMBAR` is a normal bone whose segment is `LUMBAR → CHEST` (120 units) with its own
     inverse bind, and `PELVIS → LUMBAR` coincidence is a builder convention the rig does not encode (§6.2), so nothing
     about it reaches the asset. The rig is **23 mapped bones inside 34 skin joints** (23 mapped + 11 visual-only, §6.1);
  4. emit the visual-only nodes the asset needs and the engine does not have — a crown, toe tips and the **10 finger
     bones** (X2) — flagged so the asset validator excludes them from the 23. **Finger layout:** each finger head is
     placed from its hand head along the X1 hand direction (`HAND → FINGERTIPS`, `(0, −1, 0)`, §6.6), parented to
     `Left/RightHand` with the hand's bind local rotation, so the fingers inherit the hand rigidly (§6.2) and the bind
     silhouette is an open hand;
  5. author or re-weight the body mesh against those pivots;
  6. compute every inverse bind from the same positions, in the same pass, so a bind cannot drift from a pivot;
  7. **re-band the morph targets** to the new segments: the five existing targets (`ShoulderWidth`, `ChestVolume`,
     `WaistWidth`, `LimbThickness`, `HeadScale`) carry deltas authored at 55/50/55/22 mm and 10 % of head radius against
     the old proportions, so each target's region mask and its declared `min`/`max` are re-authored against the new
     segment lengths rather than inherited;
  8. write `BoneMap` with `basis = Ry(+90°)`, uniform scale `s = 235/0.95`, translation `B⁻¹ · Δp / s` (0 at the
     pelvis), and author the **root's rest world rotation** as the engine rest orientation carried through the basis,
     `B⁻¹ · I · B = I` — the same identity every other bone's rest is compared against in A1. **It is not
     `B⁻¹ · F_root(bind) · B`**: at the X1 A-pose that value is a 180° rotation about `B`'s own axis (§6.2), so writing it
     as the rig's rest would put the asset 180° from the engine at the root and fail A1's rest-orientation row by exactly
     that amount. The derived pelvis frame reaches the asset **only through `ΔF_root`**, which is identity at bind, so the
     generator never needs `F_root(bind)`'s absolute value;
  9. validate: `RigLandmarkAlignmentTest` against the **X1 bind frame** (§6.6), `RigAssetContractTest`,
     `PoseRetargeterRestInvariantTest`, `RigSideTest`;
  10. record the residual table and the device run.
- **The retarget fix is a runtime change, not a doc change.** X3 replaces the rest-delta composition in
  `PoseRetargeter.boneMatrices` and supplies the basis at the construction site (`CharacterSession.kt:152` currently
  passes none, so `PoseRetargeter.kt:14`'s `Quat.IDENTITY` default applies). **The existing `PoseRetargeterTest` cannot
  detect that error**: it asserts identity at the neutral pose, and `basis = IDENTITY` satisfies a rest invariant
  trivially — identity composed with identity is identity for every basis. The basis that *runs* is therefore covered by
  `PoseRetargeterBasisTest` and by nothing else: `RigLandmarkAlignmentTest` performs its own `s · Ry(+90°) · p_asset`
  conversion and never observes the runtime transform, `RigSideTest` observes handedness only, and the rest invariant
  observes neither (§6.6). **And the wrong-basis control is visible in the engine image**, contrary to what this
  paragraph said before: dropping the asset-space conjugation inside `B⁻¹ · D · B` replaces the engine correction `D`
  with `B · D · B⁻¹`, which differs from `D` for every basis that does not commute with it. On the §12.2 fixture the
  dropped basis misses the published direction by **86.318091591°**, against **3.421085408°** for the reversed order and
  **0°** for the correct composition, so an engine-space assertion through the real handoff separates it exactly as an
  asset-space one does. The test therefore drives `PoseRetargeter.boneMatrices` and the scene-frame handoff and asserts
  **both** spaces, which is stronger than the asset-space assertion alone and does not weaken the runtime test (§12.2).
- **Dependencies:** the character runtime from PR #385, **merged into `main` at `abc2483`** — the runtime,
  `PoseRetargeter` and Filament 1.75.1 are on `main` (§3.1), so no stage-1 PR waits on a merge; art for the base body,
  which only P3 and P7 need.
- **Expected files:** `app/src/main/assets/characters/*/skin.glb`, the generator (`tools/` — the shape of
  `spike-humanoid/tools/generate_humanoid_glb.py`, PR #384, with its joint table taken from the definition rather than
  hand-authored), `character/BoneMap.kt`, `character/RigAssetValidator.kt`, `app/src/test/.../RigAssetContractTest.kt`,
  and an **extended** `docs/CHARACTER_DEVICE_VALIDATION.md` — the procedure already exists (141 lines, PR #385), so this
  stage **extends** it with the v2 rig's run rather than creating a second device-validation document.
- **User-visible deliverable:** a dev build showing the engine's figure performing the current exercises, with the
  skeleton layer overlaid. **It is the engine's figure and not an independently verified human**: a rig that passes A1 is
  by definition the one whose bone heads sit on the engine's joints, whose crown is `HEAD_POS.y + headRadius` =
  `409.0` units = **1.653 m** and whose head radius is `18` units = **72.8 mm** — and `headRadius = 18` is a *renderer*
  constant (`SkeletonStyle`, §3.3), not a measurement. No wording in this document may imply that the result has been
  checked against human proportions; it has been checked against `SkeletonDefinition`.
- **Automated acceptance:** rest invariant identity within 1e-4 on **every** bone, the 10 visual-only finger bones
  included; **23/23 mapped joints** resolve (the scapula pair among them — 21/21 was the shipped asset's count,
  which had no scapula), the **10 undrivable engine joints are declared** (X2) and the **11 orphan
  asset bones are reported as visual-only**, not as failures; the visual-bone count is inside the 30-bone allowance
  (10 used, §6.1); side check passes; budgets reported; **A1 green on every mapped joint (≤ 0.5 mm, ≤ 0.5°)** with the
  finger bones excluded from the comparison and listed as unmapped; A2 **measured** at the five named frames (5 mm), which is a measurement the
  shipped asset has not had; and `PoseRetargeterBasisTest` green through the real construction and upload path, with its
  wrong-basis control red. The **derived pelvis root frame** reproduces the authored tilt within `τ_dir` on
  `squat_standard` at the bottom and on `bird-dog`, and **`θ`** is reported per segment across the RV4 poses with
  `τ_roll` recorded — the two measurements §13 questions 11 and 12 hand to this stage. The `humanoid_v1` baseline fails A1 at **350.2×** the tolerance at the X1 bind frame
  (`CHEST`, 175.1 mm; §6.6 — the 565.6 mm hand figure is the posed standing-frame comparison, not the A1 result) and
  **has no A2 measurement at all**, so neither number is a formality: A1 is a construction property of the new rig, and A2
  is the first measurement anything in this programme has made on posed frames.
- **Manual acceptance:** no visible shear, no T-pose at frame 0, joints hold across a squat, a hinge and an overhead
  reach.
- **Gate:** the character renders on a device, **A1 is green on the new rig**, **A2 is green at the five named frames**, and
  **`PoseRetargeterBasisTest` is green with its wrong-basis control red**. A1 alone does not carry this gate: it converts
  an asset point in the test with `s · Ry(+90°) · p_asset`, so a converter that does that passes while the production
  retargeter still composes with `basis = IDENTITY` — the gate would go green on an unanimated figure, measured at bind,
  then driven by a rest-delta path with no basis. The runtime path is what `PoseRetargeterBasisTest` asserts, through the
  real construction and upload path with nonzero asymmetric landmarks. FPS and thermal numbers are recorded as
  information, not as a gate — the owner's decision is result first, optimisation later (§12.3).
- **Risks:** rig art quality; scapula bone whose motion reaches it only through positions (mitigated by D6 and the
  derived segment frames of §7.2); toolchain pin; a generator whose morph re-banding silently changes the body's
  silhouette (mitigated by step 7 and L6).
- **Effort:** 3–4 engineering weeks + 2–3 rig-art weeks.
- **Art/expertise:** yes, rigging.

### Stage 2 — Anatomy kernel and eight muscles (procedural)

- **Objective:** make anatomy real, in engine space, with no art dependency: definitions, solver, frame, validation, and
  a debug muscle layer.
- **Components:** `anatomy/` package (pure Kotlin, no Android), `AnatomyDefinition` + loader, `AnatomySolver`,
  `AnatomyFrame`, `AnatomyDefinitionTest`, `MusclePathGeometryTest`, `MusclePathObstacleFixtureTest`,
  `MusclePathContinuityTest`, `MuscleLengthDeterminismTest`, `MuscleLengthReferenceTest`, procedural tube generator.
- **Muscles:** the canonical M1 set, **cited from §7.7.1 and not restated here** — eight names, 18 head-side instances,
  with the per-muscle caveats that table carries. A second list in the roadmap is how the three-list disagreement it
  records happened the first time.
- **Dependencies:** stage 1 (the X1 bind frame + definition), anatomy calibration session.
- **Expected files:** `app/src/main/java/com/monkfitness/app/anatomy/*.kt`,
  `app/src/main/assets/anatomy/anatomy_v1.json`, `app/src/test/.../anatomy/*`.
- **User-visible deliverable:** a dev-only muscle layer showing red tubes following the squat and push-up, with a
  **`relativePathLengthChange`** debug readout **per head** (dimensionless, labelled modelled, X4); `modelledPathLength` is
  a debug-overlay value only (§9.2). **This is the dev readout, not the production one**: it is driven by the debug
  overlay, has no selection state, no role and no copy discipline, and it is not the component stage 2b ships. Keeping the
  two separate is what stops stage 2's placeholder from being mistaken for the user-facing row — and it is why the dev
  readout may name a head by index where the production one must show the head's authored `label`.
- **Automated acceptance:** arc length ≥ chord on every frame; **every validator fixture in §12.2.1 returns its expected
  verdict** — including `outside_endpoints_crossing_obstacle`, which must be rejected — and **every solver fixture
  produces its expected final path or failure**; lengths are **deterministic under identical inputs** (bit-identical
  across runs, buffer reuse and definition reload) and the **continuity observer's own correctness** is asserted per
  `MusclePathContinuityTest`; a path or length discontinuity across a sampled motion is a diagnostic row that fails
  nothing (X6, §7.4.3); every rest length is finite, positive and reproducible (§7.4.4); no fixture outcome is reported
  without naming which check produced it.
- **Manual acceptance:** anatomist review of the eight attachment sets and of the wrap behaviour on three poses. **This
  review does not close RV1.** RV1 closes per instance **and per attachment** — origin, insertion and every waypoint —
  in the M1 record of §12.4, so stage 2's session is an input to that record, not a substitute for it.
- **Recorded information, not a gate:** the clearance cost of §7.4.1 — the per-frame segment-to-ellipsoid distance
  evaluations on the pairs that fail the two-comparison accept — is measured here and recorded with the frame, the
  obstacle count and the pose it was measured on. It sits beside the FPS and thermal readings (§12.3): a poor number
  commissions a coarser certified surrogate or fewer obstacles, never a weaker criterion, and fails nothing.
- **Risks:** calibration drift (R7); wrap parameters; the tube look invites quality complaints — state clearly that
  stage 2 is a mechanism demo, not final visuals.
- **Effort:** 4–5 engineering weeks + ~1 anatomist week.
- **Art/expertise:** anatomy, not art.

### Stage 2b — M1 role and interaction package (pre-M1)

- **Objective:** deliver the M1 checklist items that neither stage 1 nor stage 2 produces and that stage 4 assumes already
  exist — the role schema and its validator, the four sourced phase timelines, the display gate, the attachment-landmark
  tap hints, and the **production selected-muscle readout** (§9.2). It is a work package **of** M1, not a new capability:
  nothing here adds a muscle, a preset, an exercise or a renderer feature.
- **Components:** `anatomy/ActivationTable.kt` (the §8.2 schema), `ActivationValidator` (required source, task, population,
  review status, and phase membership checked against the named timeline), one reviewed `PhaseTimeline` per pilot exercise
  (`phases_v1.json`, four entries), the display gate of §9.2 (no reviewed source → no role; `UNKNOWN`/`UNREVIEWED`
  renders as "no reviewed role"), `ui/components/anatomy/AttachmentHint.kt` — the tap that reads an attachment landmark
  from `AnatomyFrame.landmarks` and names the muscle and its layer, with no picking system, no marker geometry and no
  geometry keyed to selection — and `ui/components/anatomy/SelectedMuscleReadout.kt`, the **production** readout: the
  `SELECTED` selection state (`CharacterLayerController.setSelected(MuscleSelection?)`, §5.3) plus the content of §9.2's
  compact row **for every head of the selected `(muscleId, side)` instance** — name, `MuscleState.headLabel`,
  `MuscleState.layer`, the categorical role where a reviewed row exists, and `relativePathLengthChange` with its
  "modelled" label. **The row has exactly two inputs, and neither can be derived from the other**: the name, head label,
  layer and length change are read through `MuscleState`, and the role is read through
  `ReviewedRoleSource.roleFor(exerciseId, muscleId)` (§5.3) — a separate lookup, because a role is a claim about a task
  and lives in the reviewed exercise data (§8.2), not on the muscle, and the readout therefore carries the current
  `exerciseId` as well (with no current exercise there is no role, which renders as "no reviewed role"). Four interface
  facts this package depends on, stated here because the previous text asked for data the interface could not supply:
  **selection is keyed `(muscleId, side)`**, never a bare `muscleId`, because a bilateral muscle's sides have independent
  paths; **`MuscleState.layer` is a public getter** — static definition data, no per-frame value, and until an earlier
  revision the readout had no way to read it; **`MuscleState` is per head** (`headIndex`, `headCount`), so the readout
  iterates the selected instance's heads — two rows for biceps femoris per side — and shows **no aggregated instance value
  at all**; and **the head label is authored, not indexed** — `MuscleHead.label` carries "long head" and "short head",
  exposed as `MuscleState.headLabel`, so a production row never prints a head number. Stage 2's dev readout may name a head
  by index because it is a debug overlay, which is one reason the two components are kept apart.
- **Dependencies:** stage 2 (the frame, its landmark array and the solved paths); the four timelines are an **anatomy
  review** deliverable and sit on the §12.4 reviewer's queue, not the engineering one.
- **Expected files:** `app/src/main/assets/anatomy/phases_v1.json` (four entries),
  `app/src/main/assets/anatomy/activation_v1.json` (four exercises), `app/src/test/.../activation/ActivationDefinitionTest.kt`,
  `ui/components/anatomy/AttachmentHint.kt`, `ui/components/anatomy/SelectedMuscleReadout.kt`.
- **User-visible deliverable:** tapping a landmark names the muscle attached there and the layer it belongs to; an exercise
  with a reviewed role table shows categorical roles, and every other exercise shows none; and **selecting an instance
  shows one readout row per head** — name, head label, layer, reviewed role if any, and the modelled relative length
  change. This is the **production** readout; stage 2's own per-head debug number (§15 stage 2) is a different component
  with a different lifetime and is not replaced by it.
- **Automated acceptance:** the §8 validator is green on the four pilots; `ActivationDefinitionTest` (§12.2) asserts the
  schema and the display gate — every displayed row carries `source`, `task`, `population` and `reviewStatus`; every phase
  an entry names exists in the `PhaseTimeline` it cites; and an `UNREVIEWED` or `UNKNOWN` row cannot be displayed. The tap
  returns the muscle whose attachment landmark is nearest within the declared radius, `-1` outside it — never a guessed
  muscle. **The readout has its own content acceptance, because it is user-facing copy**: for the selected
  `(muscleId, side)` it renders **one row per head**, each carrying the §9.2 fields plus that head's label and nothing
  else; it shows `relativePathLengthChange` labelled modelled and **never** an absolute metre value (X4); it shows a role
  only for an exercise with a reviewed source and the "no reviewed role" text otherwise; it shows nothing — no stale row
  — for an instance whose `renderable` entry is `false`, because a hidden tube with a live readout is a number about
  geometry that is not on screen; it shows **no aggregated instance value**, so a two-head muscle cannot be reduced to one
  number the definition never defined; and no string it emits is drawn from `PathReason`, a status or a debug label (§7.4).
- **Explicitly not in this package:** role colouring, the education panel, phase prose and the "not EMG" legend (stage 4),
  and any picking, labelling or explanation surface (stage 7). M1 ships the role *text* and the readout, not the panel
  (§9.2): the panel's scope is stage 4's, and stage 4 consumes this readout rather than replacing it.
- **Risks:** four timelines are four review slots (R5); copy drifting into physiological claims (§11 hard rule 2).
- **Effort:** 1–2 engineering weeks + 1 review week.
- **Art/expertise:** anatomy review, not art.

### Milestone M1 — first genuinely useful character

**Definition of done:**

1. One character (`male_neutral_v1`) on `humanoid_v2`, driven by the real pipeline through `CharacterStage`.
2. **All four pilot exercises** animate correctly with the character — air squat, `standard push-up`, bird-dog and
   `row_standard` (§9.2, X8) — and no exercise builder is changed. The four are the animation requirement *and* the role
   list: an earlier revision asked for "at least three" here while §12.4 RV4 required all four reviewed poses and the
   latissimus review exists only in `row_standard`, so a milestone that satisfied item 2 could not have satisfied RV4.
3. Layers: skin, superficial muscles (the 8), skeleton overlay; toggling does not rebuild the engine. `MUSCLES_DEEP`
   exists in the enum as a reserved value but has no asset and no renderable (§7.7, §9.1).
4. Muscles follow the movement within the path model and report **relative** length change (X4: `relativePathLengthChange`
   in the selected-muscle readout of §9.2, `modelledPathLength` in the data and debug overlay only); no independent muscle
   animation exists and no cross-section response is applied (§7.6).
5. One morphology preset (`neutral`). M1 demonstrates that the preset changes nothing in the pose, muscle or animation
   data; it does not demonstrate body-family coverage — four families are stage 5 (§6.3, §15).
6. The §12.5 gate is green: L1–L4, the L5/L6 M1 subsets (tube structure, one-preset invariants), and **a device run
   recorded** for the M1 configuration (skin + muscle layer + skeleton overlay), not for the skin alone — a run, not a
   frame-rate gate (§12.3).
7. The anatomical review gate (§12.4) is passed and recorded for the exact shipped tuple: named reviewer, reference list,
   per-muscle findings against **RV1–RV6**, and unresolved limitations. A missing record fails M1 exactly as a red L4
   does. The criteria are RV1–RV6 because A1–A3 already name the rig-alignment measurements of §6.6, and A4–A6 name
   nothing: a checklist that cited `A1–A6` asked for three criteria this document does not define.
8. Every claim in the demo's copy survives §11: no "simulated", no measured activation, no implied contraction
   magnitude.
9. Tap hints on the rig: tapping an attachment landmark names the muscle attached there and the layer it belongs to. The
   label is anchored to the landmark and the tap moves nothing — no deformation, no geometry keyed to selection. The full
   picking and explanation system remains stage 7.
10. Categorical roles for the four pilot exercises — `squat`, `standard push-up`, `bird-dog`, `row_standard` (§8, X8):
    every displayed row carries a reviewed `source`, `task`, `population` and `reviewStatus`, is checked against its
    reviewed `PhaseTimeline`, and an exercise without a reviewed source shows **no role**. No numeric activation, no EMG
    output, no per-muscle contraction mode.

**Explicitly not in M1:** authored muscle meshes (stage 3), **role colouring and the education panel** (stage 4 — M1
ships the role *text* in the compact readout of §9.2, not the colour mapping, the prose or the panel itself), roles for
exercises outside those four, deep
muscles, a second morphology preset (stage 5), organs, the stage-7 label and picking system (M1 has only the
attachment-landmark hints of item 9), a muscle library beyond eight, and any claim of surface accuracy.

### Stage 3 — Path-rigged authored muscle meshes

- **Objective:** replace tubes with authored meshes without changing the anatomy model.
- **Components:** helper-bone export tool (JVM, reuses `anatomy`), `MuscleLayerSkinner`, per-muscle GLB sections, asset
  validation for helper binds, L5 tooling.
- **Dependencies:** M1's path contract; muscle art pipeline.
- **Expected files:** `tools/anatomy-export/`, `character/MuscleLayerSkinner.kt`,
  `app/src/main/assets/characters/*/muscles_sup.glb`, `app/src/test/.../HelperBindTest.kt`.
- **User-visible deliverable:** the same eight muscles, now with surface anatomy, with their control points on the path
  and a measured deviation bound rather than an "exactly follows" claim.
- **Automated acceptance:** helper bind positions equal the solver's rest path within 1e-3; helper scale is uniform, with
  no cross-section response (§7.6); the measured between-helper deviation is within the declared tolerance and recorded
  in L5; frame cost of the muscle layer **recorded as information, not gated** — the owner's decision is result first,
  optimisation later, so no stage gate depends on a frame-rate number (§12.3).
- **Gate: per-instance visibility and selection of authored muscle meshes (§7.4).** The property, not the mechanism: one
  authored instance can be hidden **and** selected while its neighbours keep drawing — per instance, never per layer and
  never per regional renderable; the carrier that expresses both is renderer-neutral (no muscle id, status or reason
  crosses into the renderer, §10.2); and the asset partition makes an instance addressable at all, with a documented
  mapping from the instance identity to the mesh range or renderable that draws it. The mechanism is deliberately not
  designed in this document — what is fixed is what the stage has to demonstrate, exercised valid → invalid → valid with
  neighbouring instances visible, because a mask that hides more than the invalid instance passes every weaker check.
- **Manual acceptance:** visual review of the eight muscles in three poses against anatomical references, and a
  **renewed §12.4 record** for the new `assetVersion` — the M1 record covered tubes, not these meshes.
- **Risks:** helper bone budget (R3); art time (R2).
- **Effort:** 4 engineering weeks + 6–8 muscle-art weeks.
- **Art/expertise:** yes, substantial.

### Stage 4 — Roles beyond the pilot, phase timelines and the education panel

- **Objective:** extend the reviewed role table from M1's pilot exercises to the shipped set, and explain participation
  honestly. The schema, validator, display gate **and the selected-muscle readout** ship in M1 (§9.2, X8; stage 2b); this
  stage adds coverage and presentation on top of that readout rather than a second one.
- **Components:** reviewed `PhaseTimeline` per exercise (`ASCENT`/`DESCENT`/`HOLD`), role rows for every exercise the app
  ships, colour mapping, panel copy, the "not EMG" disclaimer. Selection state is **consumed, not shipped here**:
  stage 2b's selected-muscle readout (§9.2) owns it, and this stage extends that readout rather than defining a
  second one.
- **Dependencies:** stage 3 (visible muscles to colour); M1's schema and gate.
- **Expected files:** `anatomy/ActivationTable.kt`, `app/src/main/assets/anatomy/activation_v1.json`,
  `app/src/main/assets/anatomy/phases_v1.json`, `app/src/test/.../activation/ActivationDefinitionTest.kt`,
  `ui/components/anatomy/MuscleRolePanel.kt`.
- **User-visible deliverable:** exercise detail shows which muscles work, in which role and stage, with a colour key.
- **Automated acceptance:** every entry has a source, task, population and review status; every phase named by an entry
  exists in the `PhaseTimeline` it cites; coverage per exercise; an entry with `reviewStatus: UNREVIEWED` or role
  `UNKNOWN` cannot be displayed.
- **Manual acceptance:** physiotherapist sign-off on the shipped exercises.
- **Risks:** review bottleneck (R5); copy drifting into physiological claims.
- **Effort:** 2 engineering weeks + 1 review week.
- **Art/expertise:** anatomy review.

### Stage 5 — Morphology v2 (four body families)

- **Objective:** prove one anatomy library serves slim, heavy, muscular and female bodies. This is the first stage that
  ships more than one morphology preset; M1 ships one (§15, M1 item 5).
- **Components:** morph targets with region masks, four presets, `MorphPresetRangeTest` and `MorphRingPreservationTest`,
  per-preset device screenshots.
- **Dependencies:** stage 3 meshes (morphs are authored per body).
- **Expected files:** `app/src/main/assets/characters/*/skin.glb` (morph targets), `character/CharacterProfile.kt`,
  `app/src/test/.../character/MorphPresetRangeTest.kt`, `.../MorphRingPreservationTest.kt`.
- **User-visible deliverable:** a character selector that changes the body and keeps every exercise, muscle and
  animation.
- **Automated acceptance:** declared `min`/`max` respected per target; **mesh ring preservation** around each joint pivot
  within `ε_morph`, per preset. **Muscle-versus-body intersection is not automated at stage 5**: it remains an L7 RV2/RV3
  human judgement until a mesh-intersection tool exists (§13 question 9), so "anatomy inside the body" is a human gate
  here, not an automated pass — and the bone-landmark rest invariant is not that check, because a vertex-only morph
  cannot move a bone.
- **Manual acceptance:** all four bodies pass the same visual checklist; muscle layers align on each; and a **renewed
  §12.4 record per preset**, whose scope depends on what the preset is — **RV5 and RV2/RV3** for a new preset inside an
  already reviewed family, and **RV1 per muscle, per side, plus RV2, RV3 and RV5** for a new body family (D10, §12.4
  renewal), because a new family can move the landmarks the attachments were approved against. **RV2/RV3 are in both
  cases**: a preset moves the surface the muscles must sit under even when the family and its landmarks are unchanged, so
  a preset reviewed only for consistency (§12.4's renewal row requires RV2/RV3 on each new preset) would accept
  intersections and compartment errors this stage exists to catch. The record names which scope it covers.
- **Risks:** morph authoring cost; muscle meshes must be re-authored per family (§6.3); joint degradation.
- **Effort:** 3 engineering weeks + ~6 art weeks.
- **Art/expertise:** yes.

### Stage 6 — Library expansion

- **Objective:** a credible muscle library, not a demo.
- **Components:** ~35 superficial and ~12 deep definitions, meshes, L3/L4/L5 at scale, per-layer budgets.
- **Dependencies:** stages 3–5.
- **Expected files:** expanded `app/src/main/assets/anatomy/anatomy_v1.json`, per-character muscle assets,
  `app/src/test/.../anatomy/` fixture coverage per region.
- **User-visible deliverable:** deep-muscle layer; the anatomy view becomes an education surface.
- **Automated acceptance:** the full library passes L3/L4; asset budgets held; frame budget measured.
- **Manual acceptance:** anatomical review per region, in batches.
- **Risks:** art scale (R2); accuracy drift across a large library; frame budget on mid-range devices.
- **Effort:** 6–8 engineering weeks + 12–16 art weeks.
- **Art/expertise:** yes, the dominant cost.

### Stage 7 — Labels, picking and explanations

- **Objective:** connect the visual to the words.
- **Components:** projected labels from `AnatomyFrame` anchors, Filament picking, exercise-specific explanations,
  accessibility copy.
- **Dependencies:** stage 6.
- **Expected files:** `ui/components/anatomy/AnatomyLabels.kt`, `character/AnatomyPicking.kt`,
  `app/src/test/.../AnatomyLabelLayoutTest.kt`.
- **User-visible deliverable:** tappable muscle labels with a short, sourced explanation.
- **Automated acceptance:** every label anchors to a visible muscle; picking returns a muscle id, never null for a hit.
- **Manual acceptance:** labels do not overlap or occlude the figure at any of the four body presets; copy reviewed.
- **Risks:** label clutter on a phone screen; picking cost per frame.
- **Effort:** ~3 engineering weeks.

---

## 16. Non-goals

- No production implementation in this change; no renderer, asset or Gradle change.
- No rewrite of any pose builder, no change to `SkeletonPose`, `SkeletonPipeline`, `ConstraintSolver`,
  `SkeletonPoseFinalizer` or `ExerciseValidator`.
- No change to the workout/session/program systems.
- No final character models, no final muscle meshes, no complete muscle library.
- No physics, no tissue simulation, no cloth/hair simulation, no facial animation, no per-muscle IK.
- No EMG, no measured activation, no physiological claims.
- No merge of PR #384 or #385; no change to their state.
- No competing motion solver anywhere downstream of `SkeletonPose`.
- No custom Vulkan or GLES renderer.
- No unattended `git` operations beyond the documentation branch and its PR.

### Known M1 limitations

Each line is a bound on what M1's evidence establishes, and names the section that states it. None is repaired in this
document; the milestone's assurance rests on the human gate of §12.4 and on tests run against real code.

- **Arm elevation above shoulder height** is exercised by neither A2 nor RV4, so neither makes a claim about it (§12.4).
- **No numerical clearance floor is claimed**; anything not covered by a proved bound fails closed (§7.4.4).
- **Sex-related pelvis-width and femoral-angle differences** are not covered — the attachment set is reviewed against one
  body, with no sex-specific adjustment made or implied (§12.4).
- **No numeric activation value exists** — categorical roles and ordinal tiers only, never a measured percentage (§8.1,
  Appendix B).
- **Stage 2's `SEGMENT_INTERSECTS_OBSTACLE` is unreachable until the enclosure deliverable lands**; an unproved witness
  routes to stage 3, whose own result decides the pair (§7.4.1c, §12.2.1).
- **All 18 attachment instances are "frame unverified"** until RV1 closes each origin, insertion and waypoint
  individually (§12.4).
- **Lumbar curvature is not representable** — one `LUMBAR` and one `CHEST` joint, so the spine is two rigid segments
  (§13, question 10).
- **Muscle-versus-skin and self-intersection are not automated** in M1; they are L7 / RV2–RV3 judgements (§12.5).
- **Device evidence is one device, one asset, one run** (§12.3).
- **A2 and RV4 cover 4 of 66 registered builders**; a five-frame set is not a statement about the builders it does not
  visit (§12.4).
- **M1 ships one morphology preset** — RV5 is evidence about one body, not about any preset added later (§12.4).

**Decisions these non-goals imply, stated so they are not read as omissions.** Each is closed in the X-table of §13; the
section named is where it is applied.

- **X4 — metres stay out of the M1 UI**: `relativePathLengthChange` only, labelled modelled (§9.2).
- **X7 — `NO_ROUTE` is disabled** until a proof procedure exists, and the search outcome is reported separately from the
  last geometric cause (§7.4.1c).
- **X9 — the roll convention is minimal rotation, as a default only**, with no anatomical-adequacy claim (§7.2, §13
  question 11).
- **X10 — one generic reference body**, with `s` and the bind pose tied to named landmarks rather than to a height (§6.6).
- **Fingers are rigid, visual-only bones** — no engine joint, no A1 row, inheriting the hand (§6.1, §6.2).
- **Frame rate is not a gate** — FPS and thermal readings are informational (§12.3).

### Engine debt (not M1 scope)

Properties of the engine as built, established elsewhere in this document and **not repaired here**. The engine is **not
changed in stage 1**: no joint, no builder, no `SkeletonDefinition` and no calibration path moves in P1–P7, so each item
below is a limit the rig inherits, not a defect the rig introduces. The list is **revisited after P6's A2 residual table**
(§15 stage 1), the first posed-frame measurement that could say whether any of them matters for M1's eight muscles.

- **`PELVIS → LUMBAR` coincidence is a builder convention, not an invariant** — the factory's zero default plus the five
  builders that re-assert `lumbar.localPosition = 0`; nothing in `SkeletonFactory` forbids a non-zero offset (§6.2, §7.2).
- **The engine has no finger joints** — the hand chain ends at `HAND_A/P`, so the 10 finger bones exist only in the rig
  (§6.1, X2).
- **The scapula is coincident with the clavicle in every frame** (§7.2), so raising the arms above the shoulders is not
  covered by anything this document measures.
- **10 engine joints have no rig bone** — `WRIST`, `PALM`, `KNUCKLES`, `FINGERTIPS` and `HEEL`, per side (§6.1).
- **The shipped scale path is replaced, not kept**: `CharacterCalibration.unitScale` (203.09 u/m implied) is what
  `CharacterSession.kt:175` applies today, and the retarget PR replaces it with `unitsPerMetre = 247.37` (§6.2, §6.4).

No engine fix is proposed in this document: M1 is evaluated on the engine as built, and an engine change would be its own
proposal with its own evidence.

---

## Appendix A — module map

```
app/src/main/java/com/monkfitness/app/
├── animation/        Motion Model (unchanged)           8,144 lines / 47 files
├── poses/            exercise intent (unchanged)       10,642 lines / 79 files
├── anatomy/          Anatomical Model (new)            pure Kotlin, no Android
│   ├── AnatomyDefinition.kt      schema + loader
│   ├── AnatomySolver.kt          pose -> paths/lengths
│   ├── AnatomyFrame.kt           reused per-frame buffers
│   └── ActivationTable.kt        exercise semantics
├── character/        Character Appearance Model (PR #385 + this RFC)
│   ├── CharacterProfile.kt       config + manifest
│   ├── BoneMap.kt                joint -> bone contract
│   ├── MuscleLayerSkinner.kt     anatomy -> helper bones
│   ├── CharacterSceneFrame.kt    renderer contract
│   └── (existing) PoseRetargeter, GltfRig, MonkJointResolver, CharacterSession, CharacterStage
├── ui/components/    presentation seam (ExerciseVisualContent, ExerciseAnimatedVisual)
└── validation/       developer validation launcher (unchanged)
app/src/main/assets/
├── anatomy/anatomy_v1.json       shared definitions
├── anatomy/activation_v1.json    shared semantics
├── anatomy/phases_v1.json        reviewed phase timelines (four pilots at stage 2b; wider coverage at stage 4)
├── characters/<id>/              per character: skin, muscles, materials
└── shared/skeleton_v1.glb        skeleton overlay
tools/anatomy-export/             offline helper-bind + asset validation (JVM)
```

## Appendix B — glossary

| Term | Meaning here |
| --- | --- |
| Canonical segment frame | the **derived** frame of §7.2 — built from published positions, not the `Joint` node's rotation (which is identity at rest) and not the world bind axes; `+Y` superior, `+X`/`+Z` sagittal and lateral with the frame's own sign. The authoring frame for attachment offsets |
| Attachment | a muscle endpoint or waypoint pinned to a canonical segment, in metres |
| Muscle path | the solved polyline from origin to insertion, including wrap detours |
| Arc length | length along the solved path; the only length the model trusts |
| Rest length | arc length at the bind stance, computed by the same solver |
| Helper bone | a bone placed on the path that skins the muscle mesh; not part of the body rig |
| Path-rigged muscle | an authored mesh skinned to helper bones driven by the solved path |
| Activation tier | ordinal emphasis (primary/secondary/supporting); never a measured percentage |
| Layer | a renderable set (skin, muscles superficial, muscles deep, skeleton) with independent visibility |
| Body profile | per-character inputs to anatomy: unitsPerMetre, uniform scale, sparse overrides |

## Appendix C — questions this RFC asks reviewers to answer

1. Is "anatomy never enters the engine" the right line, or should `SkeletonPose` carry a minimal muscle anchor set to
   avoid recomputing attachments? (This RFC says no; the cost is one attachment transform per waypoint plus a
   short path solve per muscle per frame, which the stage-2 measurements will quantify.)
2. Is eight muscles the right M1 subset for the four pilot exercises of X8 — `squat`, `standard push-up`, `bird-dog`,
   `row_standard` — or would a smaller set done well be the better outcome of the stage-3 authoring experiment (§13,
   question 2)?
3. Should the skeleton overlay be a shared asset across characters (this RFC's assumption) or per character, given
   uniform scale differences?
4. Is any cross-section response ever justified? This RFC ships none in M1 (§7.6). If a reviewer believes a visibly
   shortening muscle must change shape, the burden is a validated per-muscle model with measured references; the
   cylinder law on its own is a plausibility argument, not evidence.
5. Who signs the anatomy table, and how many muscles per review session is realistic?
