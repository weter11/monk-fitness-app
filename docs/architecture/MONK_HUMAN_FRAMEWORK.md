# Monk Human Framework — human character and anatomical presentation

**Status:** architecture RFC, proposed for review. Nothing in this document is implemented by this change.
**Base for this document:** `main` @ `40f8017` (`40f80179f7515c52e1719cde9ea6e4ba8c13094e`).
**Companion documents:** `docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` (runtime ownership), `docs/ARCHITECTURE_V2.md`
(carrier contract), `docs/CHARACTER_FRAMEWORK.md` and `docs/CHARACTER_RENDERING.md` (Filament work, both currently
only on open PR #385), `docs/ENGINE_ARCHITECTURE.md` (engine map), `docs/VALIDATION.md` (validation subsystem).

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
3. **Anatomical data is rig-independent and character-independent.** Attachments are authored in canonical joint-local
   space in metres, so one muscle library serves every character and every visual rig, exactly as one pose library serves
   every character.
4. **Geometry strategies are interchangeable behind a path contract.** The per-frame output of the anatomical model is a
   polyline path per muscle plus its length. Whether the visible muscle is a procedurally generated tube (phase A) or an
   authored mesh skinned to path-driven helper bones (target) is a renderer-side choice, not an anatomical one.
5. **Activation is authored educational metadata.** Roles and phases come from a reviewed table with a provenance field;
   they are never derived from joint angles and never presented as measured EMG or physiological activation.
6. **Validation is layered and separate.** The existing 18-rule `ExerciseValidator` is unchanged. Rig, attachment, path,
   mesh and morphology validation are new observers with their own reports; none of them can weaken motion validation.

The first useful deliverable (milestone **M1**, §15) is one character, driven by the real motion engine, playing at least
three existing exercises, on one shared rig (a v1 scope constraint, §6.1), with eight muscles that follow the movement
and whose attachments have passed a recorded anatomical review (§12.4), one morphology preset, and
skin/superficial-muscle/skeleton layers. The full muscle library, the deep layer, organs, labels and education content
are explicitly later.

---

## 2. Scope, method and evidence rules

**In scope:** architecture, data contracts, ownership, validation strategy, staged roadmap, and the explicit decisions
requested in the task.

**Out of scope:** implementation, production rendering changes, exercise-builder rewrites, workout/session logic, final
character art, a complete muscle library, physics or biomechanical simulation, merging PR #384 or PR #385.

**How the baseline was verified.** The repository was inspected at `main` (`40f8017`), not from earlier reports. The
Filament work was read from the two open PR heads (`fd549bd` for #384, `bcb3658` for #385) without changing either PR's
state or merging anything. The humanoid test asset was parsed directly (`app/src/main/assets/characters/humanoid_v1.glb`
on PR #385) to confirm joint count, triangle count and morph targets rather than trusting the generator's printout.

**Evidence rules used in this document:**

- A statement about current behaviour cites a source location on `main` unless it is explicitly attributed to a PR.
- Numbers are either measured here (asset parse, line counts, entry counts) or quoted from a document with its section.
- The AA-3D weightlifter viewer is treated as an **external reference implementation**: its published muscle-data design
  is used as inspiration, its measured cost is used as a scale reference, and none of its numbers are treated as a
  specification for this app.
- Unverified items are labelled. The build environment has no Android device; anything requiring a GPU, a driver or
  sustained thermal behaviour is a device-validation item, not a claim.

---

## 3. Verified baseline

### 3.1 Repository state at the time of writing

| Item | Value |
| --- | --- |
| Default branch | `main` |
| Base SHA | `40f80179f7515c52e1719cde9ea6e4ba8c13094e` ("Merge pull request #386 from weter11/feat/set-load-kg") |
| Working tree at branch creation | clean (`git status --porcelain` empty); this document is the only change on the branch |
| Documentation branch | `docs/human-framework`, created from `origin/main` |
| Engine sources on `main` | `app/src/main/java/com/monkfitness/app/animation` (8,904 lines), `.../poses` (10,639 lines) |
| Registered pose builders | 66 (`AnimationRegistry.kt` — 66 `register(...)` call sites; the 67th textual match is the `fun register` declaration) |
| Pose source files | 79 |
| Joint identifiers | 33 (`animation/Joint.kt:3`) |
| CI verifier | `.github/workflows/android.yml` — `:app:assembleDebug` then `:app:testDebugUnitTest` |

Two open PRs carry the Filament work. Neither is merged, and neither is assumed to be:

| PR | Branch / head | State | Content |
| --- | --- | --- | --- |
| #384 | `spike/filament-humanoid` @ `fd549bd` | open, not merged | standalone `spike-humanoid` app module: Filament scene, GLB reader, retargeter, generated 32-joint asset, 5 JVM tests |
| #385 | `feat/filament-character-stage2` @ `bcb3658` | open, not merged | the spike promoted into `:app` as `com.monkfitness.app.character` behind `FILAMENT_CHARACTER_ENABLED`, backend diagnostics, asset + generator, morph presets, device-validation doc |

`main` contains **none** of that code: `git ls-tree -r origin/main` has no `character/` package and no
`docs/CHARACTER_*.md`. The framework therefore describes a target that consumes #385's contracts, not a merged baseline.
If #385 is rejected, the Character Appearance Model below still stands — only the current implementation status changes.

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
- **The limb stage is live by default** (`animation/IkStage.kt:20`, `IK_STAGE_ACTIVE = true`) and is the sole active
  limb solver; the authoring bake remains only as a rollout/differential path (RFC §5 R5, §8).
- **Validation is a read-only observer of the published pose**, 18 rules (`animation/ExerciseValidator.kt:47-90`:
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
   config, builds the pose, and calls `SkeletonRenderer` (`:124`), which is a Compose `Canvas` composable
   (`animation/SkeletonRenderer.kt:23`). The renderer runs the pipeline itself (`:63`), projects the published joints
   (`animation/SkeletonProjector.kt:21`), then draws:
   - bones as thick stroked lines, joints as filled circles, torso as eight-point filled faces
     (`SkeletonRenderer.kt` draw loop, ~:90-170),
   - painter's-algorithm depth sort by projected depth, with colour lerped by depth
     (`SkeletonRenderer.kt`, insertion sort over `RenderItem.depth`),
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

**PR #385 — stage 2 (30 files, +2,983/−11).** The runtime promoted into `:app` as `com.monkfitness.app.character`:

- `PoseRetargeter` (`character/PoseRetargeter.kt:10`, `boneMatrices` at `:42`): rest-delta world retarget,
  `R_target = R_source × R_target_rest`, rotations only, emitted as `inverse(meshWorld) × jointWorld × inverseBind`
  (`:108-110`). Only `PELVIS` translation is converted from engine units to asset units (`:78-83`); bone offsets stay in
  asset units because the inverse binds were authored against them (`:70-72`).
- `GltfRig` (`character/GltfRig.kt:11`, `parse` at `:99`): a minimal GLB reader for the data gltfio does not expose —
  node hierarchy, skin joint order, inverse bind matrices, triangle and morph counts, animation names.
- `MonkJointResolver` (`character/MonkJointResolver.kt:10`): 21 of the 33 joints map by normalized name/aliases
  (`:12-34`); unresolved joints stay at rest instead of failing. `restPelvisHeight` = `shin + thigh + 25` (`:59-60`).
- `CharacterCalibration.unitScale` (`character/CharacterCalibration.kt`): derives engine→asset scale from a standing
  frame (`HEAD_POS.y − min(TOE_F.y, TOE_B.y)`), using a throwaway pipeline so calibration cannot advance the render
  pipeline's history.
- `MorphologyPreset` (`character/MorphologyPreset.kt:7`): four presets over five morph weights (neutral, slim, heavy,
  muscular). Comment says "vestigial body-shape parameters carried by the test asset".
- `CharacterFeature` / `ActiveCharacter` (`character/CharacterFeature.kt:9,24`): the flag (`BuildConfig
  .FILAMENT_CHARACTER_ENABLED`, default false — `app/build.gradle.kts:31-32`, enabled only with
  `-Pmonk.filamentCharacter=true`) and a process-wide single-owner slot guard.
- `CharacterStage` (`character/CharacterStage.kt:29`): a Compose `AndroidView`/`TextureView` host that builds the pose
  from `PoseRegistry` + `rememberAnimationController` and feeds `CharacterPoseSource` (`character/CharacterPoseSource.kt:6`).

**Verified properties of the test asset** (`humanoid_v1.glb`, parsed for this document):

| Property | Value |
| --- | --- |
| File size | 2,060,808 bytes |
| Nodes / joints | 35 nodes, 32 skin joints (`Hips, Spine, Chest, Neck, Head, Left/RightShoulder, Arm, ForeArm, Hand, 5 fingers, UpLeg, Leg, Foot, ToeBase, HeadTop_End`) |
| Meshes / primitives | 2 meshes, 2 primitives (body + a `Ground` node inside the GLB) |
| Triangles | 30,722 |
| Morph targets | 5: `ShoulderWidth`, `ChestVolume`, `WaistWidth`, `LimbThickness`, `HeadScale` |
| Materials / textures / animations | 2 materials, 0 images, 0 animation clips |
| Bind pose | arms at the sides, matching the engine rest stance (required by rest-delta retarget — `docs/CHARACTER_FRAMEWORK.md` §3) |

Filament is pinned to **1.75.1** (`gradle/libs.versions.toml:15`): 1.76+ requires `compileSdk 37` and the app is on 34.
The documented native payload is 7.4 MB arm64 and a debug APK with the asset is 18.8 MB (`docs/CHARACTER_FRAMEWORK.md` §1).

### 3.5 What the AA-3D viewer contributes — and what it does not

**Provenance, stated because it limits what these numbers can do.** The figures below are quoted from an external
reference implementation's own page state, as reported in the review that produced this section. This document contains
no URL for the artifact, so the numbers are **not reproducible from this repository and are not verified here**. They are
used as a scale reference for what a browser-side implementation costs — never as a budget for this app, and never as
evidence about a phone. Every device number in this framework comes from the stage-1 gate or does not exist.

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
  mid-range phone — an assumption to be measured at stage 1, not a measurement;
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
| No device evidence | Filament FPS, thermal drift and context loss are unmeasured | Roadmap stage 1 gate (§15) |

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
| **Anatomical frame** | per-frame derived state: solved path points, arc length, tendon lengths, strain, landmark positions | one frame, reused buffers | runtime `AnatomyFrame` |
| **Anatomical geometry** | the visible mesh of a muscle | renderable, cached | character asset (GLB) or generated |

The model **derives position from the Motion Model** and has no solver of its own: it never calls IK, never moves a
joint, never resolves a contact. If the anatomy and the skeleton disagree, the anatomy is wrong by definition.

The model is **rig-independent**: it references `Joint` identifiers and joint-local offsets in metres, never glTF bone
names. A new character rig, a new species of mesh, or a different morph preset changes nothing in the anatomy data.

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
animation (engine)  ←  anatomy  ←  character  ←  ui
animation (engine)  ←  character
character           →  (Filament only, at the renderer boundary)
```

Forbidden edges, enforced by an architecture test (§12):

- `animation/` importing `anatomy/`, `character/`, or `com.google.android.filament.*`;
- `anatomy/` importing `character/`, Filament, or Android UI;
- `character/` calling IK, constraint solving, or pose mutation;
- any module other than the renderer importing `filament`/`gltfio`.

**Compose in `animation/` is a named, frozen exception, not a free pass.** The motion core is pure Kotlin, but the package
is not: the base already imports Compose in `AnimationController.kt`, `SkeletonRenderer.kt` and `SkeletonStyle.kt` — the
playback controller and the 2D presentation, both of which live in `animation/` today. A blanket "no Compose in
`animation/`" fence would therefore fail against the frozen baseline on the day it was written, and a rule that cannot
hold is not a rule.

The contract is an allow-list, and it is closed:

| Rule | Enforcement |
| --- | --- |
| Compose imports in `animation/` are permitted only in `AnimationController.kt`, `SkeletonRenderer.kt`, `SkeletonStyle.kt` | `CharacterDependencyTest` compares the importing-file set against this list; a new file importing Compose fails |
| No file in `animation/` may import `anatomy/`, `character/`, Filament, or any `ui/` package | same test, deny-list by package prefix |
| The motion core — `SkeletonPipeline`, `ConstraintSolver`, `SkeletonPoseFinalizer`, `IkStage`, `SkeletonFactory`, `PoseDefinition`, `ExerciseValidator` — must stay free of Compose and Android UI | same test, explicit core file list |

The clean fix is a package move (`ui/animation/` for the renderer and controller, leaving a pure `animation/` core), and
it is deliberately **not** part of this RFC: it would touch production code and the 2D renderer's imports, which a
documentation change must not do. The allow-list is the honest interim: it freezes today's exception, forbids growth, and
keeps the core provably clean. §12.2 names the test.

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

No object in this table is written by two subsystems. The split *inside* one bone buffer is explicit: a bone index
belongs to either the base range (retargeter) or the helper range (muscle skinner), never both (§5.3).

### 5.2 Frame flow

```
1  engine: builder.build → pipeline.produceFrame → SkeletonPose (published)
2  character: PoseRetargeter.boneMatrices(pose, …) → base bones            [rotations from joints]
3  anatomy:   AnatomySolver.solve(pose, def, bodyProfile) → AnatomyFrame    [paths, lengths]
4  character: MuscleLayerSkinner.write(AnatomyFrame) → helper bones         [path-driven transforms]
5  renderer:  upload both bone ranges + morph weights; render visible layers
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
    val landmarks: List<LandmarkDefinition>
)

/** One frame of derived anatomy. Buffers are reused; consume before the next solve. */
class AnatomyFrame(val muscleStates: Array<MuscleState>, val landmarks: FloatArray)

class MuscleState(
    val id: String,
    val side: Side,
    /** Head index within the definition; 0 for single-head muscles. Path and length are per head (§7.5). */
    val headIndex: Int,
    val headCount: Int,
    /** Path in engine world units, sampled at `pointCount` points; capacity includes the insertion allowance (§7.4.1). */
    val path: FloatArray,
    val pointCount: Int,
    val length: Float,          // along this head's path, including wrap detours
    val restLength: Float,      // bind-pose length for this head and body profile
    val tendonLength: Float,    // origin + insertion tendon portions
    val strain: Float,          // length / restLength - 1; reported, not a geometry input
    /** VALID, or INVALID with a reason and the failing sample indices (§7.4.2). */
    val pathStatus: PathStatus
)

/** Pure: reads the published pose, writes only its own output buffer. */
class AnatomySolver(private val definition: AnatomyDefinition) {
    fun solve(pose: SkeletonPose, profile: BodyProfile, out: AnatomyFrame): AnatomyFrame
}
```

```kotlin
// ---- Character Appearance Model --------------------------------------------
package com.monkfitness.app.character

/** Consumes anatomy output; owns only the helper-bone range of the skin. */
class MuscleLayerSkinner(
    private val rig: GltfRig,
    /** One entry per head: helpers in origin→insertion order, shared by the muscle's single visual belly. */
    private val helperBones: Map<MuscleHeadId, IntArray>,
    private val rest: HelperRestFrames
) {
    fun write(frame: AnatomyFrame, baseWorld: FloatArray, out: FloatArray)
}

data class MuscleHeadId(val muscleId: String, val headIndex: Int)
```

```kotlin
// ---- Presentation -----------------------------------------------------------
enum class CharacterLayer { SKIN, MUSCLES_SUPERFICIAL, MUSCLES_DEEP, SKELETON, SELECTED }

interface CharacterLayerController {
    fun setVisible(layer: CharacterLayer, visible: Boolean)
    fun setSelected(muscleId: String?)      // selection is presentation state only
}
```

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
| Joint names | `Hips, Spine, Chest, Neck, Head, {Left,Right}Shoulder, {Left,Right}Scapula, {Left,Right}Arm, {Left,Right}ForeArm, {Left,Right}Hand, {Left,Right}UpLeg, {Left,Right}Leg, {Left,Right}Foot, {Left,Right}ToeBase` plus visual-only bones | the resolver matches normalized names (`MonkJointResolver.kt:12-34`, PR #385); keeping Mixamo-compatible names keeps third-party rigs usable |
| Hierarchy | the 23 mapped bones keep their parent chain; scapula sits between clavicle and arm | the engine's girdle DOF (`CHEST → CLAVICLE → SCAPULA → SHOULDER`, `SkeletonFactory.kt:71-80`) only reaches the body if the rig has a scapula bone |
| Rest pose | arms at the sides, matching the engine's rest stance; **not** a T-pose | rest-delta retarget multiplies the source delta onto the target rest (`PoseRetargeter.kt:58-68`); a T-pose bind leaves the figure in T-pose at every frame 0 |
| Bind pose | identical to rest pose | the rest invariant test proves `identity` on every bone at neutral (`PoseRetargeterTest`, PR #385) |
| Units | asset metres; engine units converted only for the pelvis translation and the uniform scale | `PoseRetargeter.kt:70-83`; offsets must stay in asset units because inverse binds were authored against them |
| Scale | one uniform scale per character; no per-bone scaling for appearance | non-uniform bone scale distorts joint volume and is not a morphology tool |
| Fingers | visual-only, 2 segments per finger or a single segment in tier `reduced` | the engine has no finger kinematics beyond `HAND_A`; the rig may add them as static/derived |

`humanoid_v2` target bone counts: **23 mapped bones + up to 20 visual-only bones (fingers, jaw, eyes, twist) + muscle
helper bones** (§7.5). The current PR #385 asset has 32 joints, 5 single-segment fingers per hand, no scapula, no twist
bones. The scapula addition is the only rig change that materially affects anatomy quality.

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
    val basis: Quat                                           // source -> target axis correction
)
```

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
| `HAND_A/P` | `Left/RightHand` | `PALM/KNUCKLES/FINGERTIPS` map to visual finger bones or stay unmapped |
| `HIP_F/B` | `Left/RightUpLeg` | **`HIP_F` is the thigh segment, not the hip point** |
| `KNEE_F/B` | `Left/RightLeg` | shank |
| `ANKLE_F/B` | `Left/RightFoot` | |
| `TOE_F/B` | `Left/RightToeBase` | |

Unmapped joints are legal: a rig without fingers still animates (`docs/CHARACTER_FRAMEWORK.md` §3). The reverse —
a *required* bone missing — is an asset failure, not a silent fallback, once the asset validator exists.

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
  "morphology": { "preset": "muscular", "weights": [1.0, 0.7, -0.35, 0.9, 0.05] },
  "materials": { "skin": "skin_03", "roughness": 0.55 },
  "heightMetres": 1.80,
  "quality": "standard",
  "anatomyOverrides": []
}
```

`anatomyOverrides` is the escape hatch for a body family whose *shared* attachment is measurably wrong (for example a
pelvis-width-dependent origin). It must be sparse, named, and reviewed; it is not a per-character copy of the library.
An override moves an attachment, never a rig bone or a joint: it cannot repair a rig-to-engine alignment residual
(§6.6 A1/A2), which is an asset failure fixed in the rig or its calibration.

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

- **Anatomy is anchored to engine joints.** Every attachment resolves as `jointWorldPosition + R(jointWorldRotation) ×
  (offsetMetres × unitsPerMetre)` (§7.4 step 1). Anatomy never reads a glTF bone, a bind matrix or a skin weight.
- **The visual rig is posed by rest-delta retarget.** Rotations come from the engine; only the pelvis translation is
  converted, and the rig's bone offsets stay in asset units (`PoseRetargeter.kt:58-83`, PR #385).

Nothing in either rule forces the rig's bone *heads* to sit where the engine's joints are. They coincide only if the asset
is calibrated to the engine skeleton, and they stay coincident only while the segment lengths agree. That is the
alignment invariant, and it belongs to the Character model — the owner of the asset — not to anatomy.

**A1 — bind-stance landmark coincidence.** For every mapped joint `j`, at the bind stance, the rig bone head converted to
engine space equals the engine joint position within `τ_bind`, and the rig bone's rest orientation equals the engine rest
orientation within `τ_rot` (both proposed: 2 mm and 0.5°, fixed at stage 1 against the debug overlay). Checked by
`RigLandmarkAlignmentTest` (L2).

**A2 — motion residual bound.** With A1 true and identical rotation deltas, a child bone head is displaced only by the
segment-length error accumulated along its ancestor chain:

```
residual(j) ≤ Σ over ancestors i of |length_rig(i) − length_engine(i)| × scale
```

so the worst-case residual is bounded and computable before any frame is rendered, without depending on the pose. The
bound is reported per joint by `RigAlignmentResidualTest` (L2) together with the measured residual at three
representative poses (squat bottom, push-up bottom, overhead reach). A measured residual above `τ_residual` (proposed
5 mm at the most distal mapped joint) fails the asset, not the anatomy.

**A3 — the rig follows anatomy, never the reverse.** If the residual exceeds tolerance, the fix is a better rig (bone
heads and lengths recalibrated to the engine skeleton) or a correction in the calibration (§6.6 A1/A2, L2). Anatomy is
never nudged to fit a mesh, and a character is never allowed to move a joint to meet a muscle.

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
representable on this rig (§6.1). Surface plausibility remains the human review's job (§12.4, A2–A3).

**Why this is not a fourth model.** No new data and no new solver: A1/A2 are measurements of an asset against a published
pose, owned by the Character model and reported as L2. The anatomical model keeps its single input — the `SkeletonPose`.

---

## 7. Anatomical model

### 7.1 Four things named "muscle"

The design separates four objects that a naive implementation would fuse into one:

1. **`MuscleDefinition`** — static: name, side, layer, origin and insertion attachments, waypoints, width/thickness
   profile, tendon fractions, wrap surfaces, role memberships. Text, reviewable, character-independent.
2. **`MuscleState`** — per frame: solved path, arc length, rest length, strain, tendon lengths. No geometry.
3. **Muscle geometry** — the visible mesh, produced by a renderable strategy (§7.5) from the path.
4. **Muscle activation** — exercise semantics (§8), never a property of the definition.

A definition change (better attachment) is a data review. A path bug is an algorithm review. A mesh change is an art
review. A role change is an anatomy review. Four reviewers, four diffs — that is the point.

### 7.2 Spaces, units and segment frames

**Coordinate conventions (verified):**

- `+Y` is up; limbs are placed on `±Z` (`animation/BasePose.kt:63-64`, `poses/BaseSquatPose.kt:148-149`), so `Z` is the
  lateral axis and `XY` is the sagittal plane. Pose authoring reaches forward along `+X` (air-squat counterbalance
  `BaseSquatPose.kt:58`).
- **Naming trap:** `SkeletonDefinition.anatomicalForward` is `(0,0,1)` — but it is used as the *rotation axis* for
  sagittal spine bend (`BasePose.kt:208-214`), not as the direction the athlete faces. Anatomy authors must read it as
  "the lateral bend axis", not "forward".
- **Rest frames are identity.** Every pose sets local rotations explicitly and the factory creates nodes with identity
  rotation (`SkeletonFactory.kt:60-101`); at the rest stance every segment's local frame equals the world frame. An
  attachment offset can therefore be authored in one simple frame per segment: `+X` sagittal, `+Y` up, `+Z` lateral.
- **Units.** The engine has no metres: `HumanSkeletonDefinition` uses 120/112/98/80/66 (units) for torso/thigh/shin/
  upper arm/forearm (`SkeletonDefinition.kt:38-56`), and the rest pelvis height is `shin + thigh + 25 = 235`
  (`MonkJointResolver.kt:59-60`). Anatomy is authored in **metres** and converted with one constant:

  ```
  unitsPerMetre = restPelvisHeight / PELVIS_METRES
                = 235 / 0.95 ≈ 247.4        // PELVIS_METRES: trochanter height of a 1.75–1.80 m adult
  ```

  `PELVIS_METRES` is a modelled constant, classified as an approximation (§11). It is carried by `BodyProfile`, so a
  definition change rescales anatomy in one place instead of editing every attachment.
- **Side mirroring.** Attachments are authored for side `A` and mirrored to `P` by negating `Z`. The rest-pose side
  check (§12 L2) proves that the rig's `Left*` bones sit on the same side as side `A`; if they do not, the asset or the
  bone map is wrong — a rendering-time flip would only hide it.

**Segment frame table** (the node whose world rotation orients the segment):

| Segment | Enclosing node | Anatomical content | Notes |
| --- | --- | --- | --- |
| pelvis | `PELVIS` | iliac crest, ASIS, ischial tuberosity, sacrum | also the root translation |
| lumbar spine | `LUMBAR` | L1–L5 | one rigid segment, not five vertebrae |
| thorax / ribcage | `CHEST` | ribs 1–12, sternum, thoracic spine | **no per-rib segments** — a documented limitation vs the AA-3D reference, which models 12 rib pairs |
| neck | `NECK_END` | C1–C7 | one segment |
| skull | `HEAD_POS` | cranium, mandible reference | attachment point, orientation = head |
| clavicle | `CLAVICLE_A/P` | clavicle | |
| scapula | `SCAPULA_A/P` | scapular blade, acromion, coracoid | carries the girdle DOF the engine animates |
| humerus | `SHOULDER_A/P` | humerus | |
| forearm | `ELBOW_A/P` | radius + ulna as one frame | pronation is not a separate segment; a pronation-aware wrap is future work |
| hand | `HAND_A/P` | metacarpals, palm | fingers are visual-only |
| femur | `HIP_F/B` | femur, greater trochanter, linea aspera | **not the hip joint point** |
| tibia | `KNEE_F/B` | tibia, fibula, tibial tuberosity | |
| foot | `ANKLE_F/B` | calcaneus, talus, metatarsals | |
| toes | `TOE_F/B` | phalanges | |

### 7.3 Definition schema

```kotlin
enum class MuscleLayer { SUPERFICIAL, DEEP }
enum class MuscleRole { PRIME_MOVER, SYNERGIST, STABILISER }

/** The canonical segment frames of §7.2. One enum value per row of that table; no free-form strings. */
enum class Segment {
    PELVIS, LUMBAR, THORAX, NECK, SKULL,
    CLAVICLE, SCAPULA, HUMERUS, FOREARM, HAND,
    FEMUR, TIBIA, FOOT, TOES
}

/** A point pinned to a canonical segment, in metres, segment rest frame (+X sagittal, +Y up, +Z lateral). */
data class Attachment(val segment: Segment, val x: Float, val y: Float, val z: Float)

data class MuscleDefinition(
    val id: String,                       // "rectus_femoris"
    val name: String,                     // "Rectus femoris"
    val layer: MuscleLayer,
    val heads: List<MuscleHead>,          // one per head; biceps femoris = 2, triceps = 3
    val widthMetres: Float,               // belly width at rest
    val thicknessMetres: Float,           // belly depth at rest
    val tendon: TendonSpec,               // origin/insertion fractions of the path
    val wraps: List<String>,              // wrap-surface ids this path is projected outside of
    val flat: Boolean = false,            // sheet muscles (pecs, lats, intercostals)
    val profile: Float = 0.5f             // belly position along the path, 0..1
)

data class MuscleHead(val origin: Attachment, val insertion: Attachment, val waypoints: List<Attachment> = emptyList())

data class WrapSurface(
    val id: String,
    val segment: Segment,                 // the obstacle follows this segment's frame every frame (§7.4.1)
    val centreMetres: Triple<Float, Float, Float>,
    val radiiMetres: Triple<Float, Float, Float>   // ellipsoid only; a capsule is a different shape and a different test
)
```

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
  "profile": 0.45
}
```

*All coordinates above are illustrative placeholders for the schema, not calibrated anatomy.* Calibration against the
engine's rest stance is a roadmap item (§15, stage 2) with its own acceptance test: a muscle's rest length must match
the distance a tape measure gives on the reference body within a stated tolerance (§12 L4).

### 7.4 Path solving

Per muscle, per frame (`AnatomySolver.solve`), pure and allocation-light. The steps below are the whole algorithm: what a
renderer or a validator needs is an output of it, and nothing downstream re-routes a path.

```
for each head:
  1  resolve attachments:  world = jointWorldPosition + R(jointWorldRotation) * (offsetMetres * unitsPerMetre)
  2  build the control polyline: origin, waypoints..., insertion
  3  resample the polyline at N points (N ∝ path length / 0.01 m, clamped 8..48)
  4  obstacle-aware construction (heuristic, not proof — §7.4.5):
        repeat up to K passes (K declared per definition, default 8):
            for each obstacle in declaration order:
                for each segment of the current polyline:
                    if the segment intersects the obstacle (§7.4.1b):
                        insert entry/exit points and one lateral detour waypoint (§7.4.5)
                for each sample inside the inflated obstacle (rw, §7.4.1):
                    push the sample to the inflated surface along the outward normal
            stop when no sample lies inside any inflated obstacle
  5  smooth the path (Catmull-Rom, 0.25 blend) to remove the faceting projection leaves
  6  VALIDATE the final polyline, segment by segment, against every declared obstacle (§7.4.1):
        smoothing is a geometric operation like every other one and can push the path back inside,
        so the check runs on the final points, not on the pre-smoothing ones;
        every segment disjoint from every (inflated) obstacle, every sample outside the inflated surface by the
        ε_pen numerical margin;
        any failure, or any result the check cannot establish, → PathStatus.INVALID, stop.
  7  length = Σ |p[i] − p[i−1]|          // along the solved path, NOT the straight chord
  8  tendonLength = originFraction * length + insertionFraction * length
  9  strain = length / restLength − 1     // reported; never a geometry input (§7.6)
```

Step 4 inserts points, so the path buffer is sized for `N_max` plus a declared insertion allowance; exceeding it is
`INVALID` (`BUFFER_CAPACITY`), never a silent truncation.

`MuscleState` therefore carries a `PathStatus` — `VALID`, or `INVALID` with a reason and the failing segment and
obstacle indices. A consumer may not read a missing status as `VALID`, and no layer draws an `INVALID` path as if it
were anatomy.

#### 7.4.1 Obstacle validation contract

Three different checks are routinely conflated. This section keeps them apart, because only the second one is a test
for intersection.

**(a) Sample check — necessary, not sufficient.** Every sample of the final polyline lies outside every declared
obstacle. This catches gross violations cheaply and bounds how far the projection step still has to move. It proves
nothing about the segments between samples: two consecutive samples can both be outside an obstacle while the straight
segment joining them passes through it, and a 1 cm sample spacing cannot see an obstacle thinner than 1 cm at all.

**(b) Segment check — required, and the one that decides.** Every segment `[p_i, p_{i+1}]` of the final polyline is
tested against every declared obstacle, analytically. The test runs in the **obstacle's own frame**, because the obstacle
is declared in a segment frame and moves with that segment every frame — a rotation the test cannot ignore:

```
s  = unitsPerMetre
cw = jointWorldPosition(segment) + R(jointWorldRotation(segment)) · (centreMetres · s)
Rw = jointWorldRotation(segment)                 // obstacle axes, world space
rw = (radiiMetres + ε_pen) · s                   // inflated semi-axes, engine units

e  = ε_pen · s                                 // same numerical margin, in engine units
q0 = Rwᵀ (p_i     − cw) / rw                     // componentwise divide
qd = Rwᵀ (p_{i+1} − p_i) / rw

a  = qd · qd
b  = 2 (q0 · qd)
c0 = q0 · q0 − 1

Δ  = b² − 4 a c0
```

`rw` is `(radiiMetres + ε_pen) · s` (above): the margin is added in **metres, before** the single `· s` conversion,
and the same `e = ε_pen · s` is reused wherever the margin is needed alone. Adding `ε_pen` as if it were already in
engine units under-inflates by a factor of `s` (≈ 247), so a path 0.05 mm inside the correctly inflated surface would
be reported clear. `cw`, `p_i`, `p_{i+1}` and `q0`/`qd` are all in one frame — world positions, world-space axes `Rw`,
engine-unit semi-axes — so no mixed-frame comparison remains.

Intersection is `Δ ≥ 0` and the root interval meeting `[0, 1]`; a segment entirely inside the obstacle also intersects,
because its interval contains part or all of `[0, 1]`. The tolerance policy below applies to `Δ`, not to the roots.

Ignoring `Rw` is not a rounding error, it is a wrong answer: for an obstacle with semi-axes (2, 1) rotated 90°, a short
world-space segment from `(−0.1, 1.5)` to `(0.1, 1.5)` lies inside the rotated obstacle (normalized squared length
0.5725) while the unrotated formula reports 2.2525 and calls it clear.

The test is closed-form, deterministic, allocation-free and **independent of sampling density**: it answers "does this
straight segment enter this ellipsoid" for any obstacle size, including one thinner than the sample spacing. `t0` and
`t1` are reported, so a failure names the segment, the obstacle and where along the segment the path enters.

**Numeric policy.** The test is specified, not left to the implementation's mood. Precedence matters: the degeneracy,
non-finite and tolerance checks below run **before** the discriminant is classified, so a zero-length segment or a bad
`ε_pen` never reaches the quadratic. In the table `Δ_tol = κ·(b² + abs(4·a·c0))` with `κ = 1e-12` — a relative bound on
the two products whose difference forms `Δ`, so it scales with the geometry instead of being a fixed absolute epsilon.
`sign(0) = +1`, i.e. `sign(b) = +1` when `b ≥ 0`, else `−1`; without this convention a symmetric chord starting at the
obstacle centre gives `b = 0`, `q = 0` and a division by zero in `t1 = c0/q`.

| Concern | Rule |
| --- | --- |
| Precision | `Double` throughout; no `Float` intermediates |
| Stable roots | `q = −(b + sign(b)·√Δ)/2`, `t0 = q/a`, `t1 = c0/q` — avoids the cancellation of the textbook form. `q` is the larger-magnitude root of the restructured quadratic (`q = a·t_large`), so it is nonzero whenever `Δ > Δ_tol` and `a > 0`; the `sign(0) = +1` convention above makes it nonzero for `b = 0` too. Only these two roots are needed: the verdict is decided by `Δ` and the interval `[t0, t1] ∩ [0, 1]`. |
| Discriminant classification | `Δ > Δ_tol` → compute `t0`, `t1` and intersect `[0, 1]` (the **clear** and **intersecting** cases); `abs(Δ) ≤ Δ_tol` → **near-tangent**, reported as an intersection without computing roots (a discriminant lost to rounding fails closed); `Δ < −Δ_tol` → **clear**, disjoint. The three cases are exhaustive and mutually exclusive, so a negative `Δ` with a large magnitude is never swept into the near-tangent band. |
| Degenerate segment | `a = 0` (zero length): handled first by the sample test — inside → `DEGENERATE_SEGMENT`, outside → clear. No `Δ` classification runs, so `a = 0` never divides the root formula. |
| Non-finite intermediates | overflow, NaN or infinity anywhere in `cw`, `rw`, `q0`, `qd`, `Δ` or `Δ_tol` → `NON_FINITE_GEOMETRY`, not a verdict |
| Bad tolerance | `ε_pen` negative or non-finite → `NON_FINITE_GEOMETRY`; a negative margin is never clamped to zero. `Δ_tol` is derived from `κ`, not from `ε_pen`; the two tolerances are unrelated. |

**Worked cases** (unit sphere, `q0`/`qd` in normalized coordinates, `κ = 1e-12`; all values computed in double precision):

| Case | `q0` | `qd` | `(a, b, c0)` | `Δ` | `Δ_tol` | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| Clear — line misses the ellipsoid | `(2, 0, 0)` | `(0, 1, 0)` | `(1, 0, 3)` | `−12` | `1.2e-11` | `Δ < −Δ_tol` → clear |
| Clear — infinite line crosses, segment does not | `(2, 0, 0)` | `(1, 0, 0)` | `(1, 4, 3)` | `4` | `2.8e-11` | `Δ > Δ_tol`, but `[t0, t1] = [−3, −1] ∩ [0, 1] = ∅` → clear |
| Intersecting | `(−2, 0, 0)` | `(4, 0, 0)` | `(16, −16, 3)` | `64` | `4.48e-10` | `Δ > Δ_tol`, `q = 12`, `[t0, t1] = [0.25, 0.75] ∩ [0, 1] ≠ ∅` → intersection |
| Near-tangent — exact | `(−2, 1, 0)` | `(4, 0, 0)` | `(16, −16, 4)` | `0` | `5.12e-10` | `abs(Δ) ≤ Δ_tol` → intersection (fail closed); tangency at `t = −b/2a = 0.5` |
| Near-tangent — Δ lost to rounding | `(−2, 1+1e-13, 0)` | `(4, 0, 0)` | `(16, −16, 4)` | `≈ −0` (rounds to 0) | `5.12e-10` | same band → intersection, not a miss |
| Symmetric chord from the centre — the `q = 0` trap | `(0, 0, 0)` | `(1, 0, 0)` | `(1, 0, −1)` | `4` | `4e-12` | `b = 0`; with `sign(0) = +1`, `q = −1`, `[t0, t1] = [−1, 1] ∩ [0, 1] ≠ ∅` → intersection. With the textbook `sign(0) = 0`, `q = 0` and `t1 = c0/q` is non-finite — which is why the convention is part of this policy. |

The second clear row is the one a discriminant-only test gets wrong: `Δ > 0` alone is not an intersection, the root
interval must meet `[0, 1]`. The centre-start row is the reason `sign(0)` is pinned: `c0 = −1` (endpoint inside) with
`b = 0` is otherwise a silent `NON_FINITE_GEOMETRY` from a division by zero rather than the intersection it is.

A verdict produced by an implementation that does not follow this policy is not the verdict this document specifies.

**(c) Unverifiable — `INVALID`, never assumed.** A path is accepted only when every segment test returns "no
intersection" **and** every sample test returns "outside". Anything else is `PathStatus.INVALID` with a reason:

| Condition | Status reason |
| --- | --- |
| a segment enters an obstacle (after inflation by `ε_pen`) | `SEGMENT_INTERSECTS_OBSTACLE` |
| non-finite coordinate, radius or tolerance | `NON_FINITE_GEOMETRY` |
| obstacle with a non-positive semi-axis | `DEGENERATE_OBSTACLE` |
| zero-length segment whose sample is inside an obstacle | `DEGENERATE_SEGMENT` |
| inserted points exceed the path buffer's declared capacity | `BUFFER_CAPACITY` |

There is no "probably fine" outcome and no fall-through default. If the check cannot be completed, the path is invalid.

**What the inflation guarantees — and what it does not.** `ε_pen` (proposed `1e-4 m`, fixed at stage 2 against the debug
overlay) inflates each semi-axis before the test. The inflation is in **engine units**: `rw = (radiiMetres + ε_pen) · s`,
i.e. the metre-valued margin is added in metres and the whole sum is converted once with `s = unitsPerMetre` (§7.2). A
margin applied as if it were already in engine units is smaller by a factor of `s`, so the check would accept a path that
enters the correctly inflated surface; `non_unit_scale` in §12.2.1 is the fixture that catches exactly that error. Two
claims must be kept apart:

- **True:** if a segment misses the inflated ellipsoid, it misses the original ellipsoid, because the original is
  contained in the inflated one. This is what makes the check safe: it cannot call a penetrating path clear.
- **False, and previously implied:** that a passing path is at least `ε_pen` from the original surface in Euclidean
  distance. Inflating semi-axes is not the Euclidean offset of an ellipsoid; near regions of high curvature the true
  Euclidean distance from the path to the original surface can be slightly less than `ε_pen`. `ε_pen` is therefore a
  **numerical margin** — a guard against floating-point and sampling uncertainty — not a physical clearance
  specification. No copy, test name, fixture name or review note may describe it as "clearance", "distance from the
  obstacle", "the declared clearance" or "guaranteed separation"; say "outside the inflated ellipsoid by the numerical
  margin" instead. The word "clearance" in this document is reserved for the genuinely distance-based test below, which
  v1 does not implement.

A genuine Euclidean clearance guarantee would need a distance-based test (segment-to-ellipsoid distance ≥ ε), which is a
different and harder computation. It is not in v1; if a later stage needs it, it is a new contract, not a reinterpretation
of this one. `ε_pen` is a property of the definition version, not a per-call argument; changing it invalidates the
fixtures signed off against the old value.

**Where sampling still matters.** Samples remain a construction device: they set the polyline's resolution for length
and rendering, they give the projection step points to push out, and they define where a tangent kink can be measured.
Their density no longer determines whether an intersection is detected — the segment test does that — and this document
makes no claim that any sample spacing "proves" a Euclidean distance to the obstacle.

**Scope.** All obstacles declared on the muscle plus any the definition marks as global (thorax, pelvis), over the whole
polyline including tendon portions — a tendon can cut a rib as easily as a belly can.

**What a pass means.** A pass means: every segment of this polyline is disjoint from every declared inflated ellipsoid,
and every sample lies outside that inflated surface by the `ε_pen` numerical margin. That is a statement about the
polyline and the ellipsoid model, and nothing more. It is not a statement about the anatomical surface, which the
ellipsoids only approximate (§7.4.4), it is not a contact simulation (§11), and it is not a Euclidean clearance
guarantee (§7.4.1).

**What it does not cover, stated so it is not mistaken for a guarantee.**

- **The validated object is the polyline.** Nothing downstream may re-evaluate the Catmull-Rom spline or smooth the
  polyline again before rendering: a renderer that re-smooths to build a tube reintroduces a curve that was never
  tested, and the `ε_pen` margin does not bound that deviation. Strategy A consumes the validated points as they are.
- **A single frame is not a trajectory.** The check is per frame. Motion between frames is bounded by the temporal
  continuity check (§7.4.3), not by this test, so a fast transition can still sweep a muscle through an obstacle
  between two individually valid frames.
- **The obstacle set is the model.** A segment disjoint from an inflated ellipsoid is not thereby disjoint from bone,
  fascia or skin. A path can pass this test and still look wrong; that is what the L7 review (§12.4) is for.
- **Floating point.** The quadratic is evaluated in double precision; a discriminant within rounding of zero is treated
  as an intersection, not as a miss. The conservative direction is always chosen.

#### 7.4.2 Overlapping, nested and impossible routes

Obstacles overlap and nest in normal anatomy (a bone ellipsoid inside a muscle-group ellipsoid is the common case, not
an error). The rules make the outcome deterministic and reviewable:

- **Declaration order is part of the data.** Projection runs in declaration order, and that order is diffable. Two
  definitions with the same obstacles in a different order are different definitions with different expected fixtures.
- **Acceptance is by the union.** A path is valid when it is outside the union of the obstacles. Validation tests every
  segment against every obstacle individually, so overlap and nesting need no special case there — the union falls out
  of the per-obstacle tests. Only projection order is affected.
- **Nested obstacles.** A sample inside a nested pair is pushed out of each in declaration order until it leaves both or
  the pass cap is reached. An "outer" surface that does not contain its declared inner obstacle is a **definition
  error** (L3), not a runtime condition to paper over.
- **An attachment inside an obstacle, at runtime.** L3 checks attachments against obstacles in the **bind stance**, which
  is a definition-time test. An attachment that is clear at bind can sit inside an obstacle that belongs to a *different*
  moving segment once the pose changes (a pectoral origin and a humerus capsule, for instance). The solver therefore also
  checks every resolved attachment against every obstacle **in the current pose** and returns `INVALID` with
  `ENDPOINT_INSIDE_OBSTACLE` when one is inside. That is a runtime condition, not evidence that the static data is wrong,
  and the two must be reported differently: L3 blocks the definition; the solver blocks the frame.
- **No valid route.** If the pass cap is reached with a violation, or the only obstacle-free route would leave the
  segment envelope, the result is `INVALID` with a reason — `NO_ROUTE`, `PASS_CAP`, `ENDPOINT_INSIDE_OBSTACLE`. If the
  final polyline fails validation instead, the reason comes from §7.4.1 (`SEGMENT_INTERSECTS_OBSTACLE` and its
  neighbours). Either way it is reported, counted by the fixture, and blocks L4. A path through geometry is never
  accepted because it is the closest thing the solver found.
- **Ambiguity.** Where two local routes are both valid, declaration order and the fixed sample grid decide. The solver
  must return the same route on every run, every device and every buffer reuse. "Either route is acceptable" is not a
  contract, and a solver that alternates between two valid routes has a bug, not a tie.

#### 7.4.3 Continuity and tangent transitions

Projection and smoothing both create transitions where the path leaves an obstacle. Four numeric checks, each with a
declared bound:

1. **C0.** One polyline, no duplicated, reordered or dropped points. Structural, cheap, always on.
2. **Tangent continuity at entry and exit.** The angle between the segment entering an obstacle's influence region and
   the segment leaving it must not exceed `θ_max` (proposed 45°, fixed at stage 2). A larger kink reports
   `TANGENT_DISCONTINUITY`; in practice it means the obstacle radius is wrong for that attachment pair, not that the
   path needs more smoothing.
3. **Curvature bound.** No interior sample may turn more than `θ_max` relative to its neighbours at the declared
   sampling spacing. This is the discrete stand-in for "the muscle does not fold".
4. **Temporal continuity.** Path points are compared at **matched fractional arc-length stations**, not by sample index:
   resampling and inserted detour points change `pointCount` between frames, so index `i` is not the same anatomical
   place twice. At station `s`, the displacement between consecutive frames must satisfy `|Δp(s)| ≤ v_max · Δt`, with
   `v_max` a proposed bound (1.2 m/s) fixed at stage 2 and `Δt` the frame interval — the threshold is therefore
   time-normalised rather than "2 cm at 60 Hz". A violation means the solver jumped between two local routes; a pose that
   itself moved discontinuously is already failed by L1.

#### 7.4.4 What this is, and is not

This is a **geometric approximation**: ellipsoids stand in for bones, tendon sheets and fascia; projection constrains
samples, it does not resolve contact; there is no friction, no sliding, no obstacle deformation and no force. It is
classified as an approximation in §11 and must be described that way wherever it surfaces, including review notes and UI
copy.

Two levels of approximation are worth separating, because they are constantly confused:

- **The test is exact for the stated arithmetic.** Segment-versus-ellipsoid intersection is solved in closed form under
  the numeric policy of §7.4.1b. Within that policy there is no sampling error in the verdict; the policy, not a claim of
  mathematical exactness, is what makes a near-tangent case fail closed.
- **The model is approximate.** An ellipsoid is not a bone, a tendon sheet or a fascia plane. Passing the test means
  the path misses the *ellipsoid*; it does not mean the path misses the anatomy.

"Exact test" is not "correct anatomy", and "approximate model" is not "sloppy test".

Design notes:

- **Length is arc length along the solved path.** A straight-chord measure reports shortening wherever a muscle wraps a
  joint, which inverts the sign of the reported change. This is the single most important correctness rule in the model.
- **`restLength` comes from the same solver at the bind stance**, not from the definition's numbers, so the rest state
  and the runtime can never disagree (this is also what the exporter bakes; §10.4).
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
| `rw` | inflated semi-axes `(radiiMetres + ε_pen) · s` | obstacle-local axes | engine units |
| `e = ε_pen · s` | the same numerical margin, on its own | — | engine units |
| `d_1`, `d_k`, `h(l̂_d)` | detour distances | world length | engine units |

Every distance added to a world point is in engine units; `ε_pen` is **metres** and never enters a sum until it has been
multiplied by `s = unitsPerMetre` (§7.2). A direction fed into `h` is first mapped to the obstacle's local frame with
`Rwᵀ`, because `rw` lives on those axes. Mixing these — a metre-valued `ε_pen` added to engine-unit `rw`, or a
world-space direction used as if it were local — is the defect class the fixtures below catch.

**Lateral detour rule.** For each obstacle a segment intersects:

```
d̂ = normalize(p_{i+1} − p_i)                  // chord direction, world
m = (p_i + p_{i+1}) / 2                        // chord midpoint, world
u = (m − cw) − ((m − cw) · d̂) d̂               // lateral component of the chord offset, world

d̂_local = Rwᵀ · d̂                              // the chord direction in OBSTACLE-LOCAL axes
lateral = normalize(u)                         if |u| > u_min (proposed 1e-6 m → 1e-6 · s engine units)
        = Rw · (local axis least aligned with d̂_local, x → y → z on ties)   otherwise
        // fallback is chosen in the local frame and rotated back to world; it is a direction, so no scale applies

l̂_d = Rwᵀ · lateral                           // the chosen world direction, back in local axes
d_1 = h(l̂_d) + e                              // h = support function, √Σ (rw_i · l̂_d_i)²
d_k = 1.5 · d_{k−1}                            // escalate on each further pass, capped by the corridor limit
```

`h(l̂_d) = √Σ (rw_i · l̂_d_i)²` is the support function of the **inflated** ellipsoid (`rw` already contains `e`): it is
the distance from `cw` to the tangent plane in the `l̂_d` direction, in engine units. `d_1` adds `e` on top, so every
distance in the rule is engine-unit and `ε_pen` is converted exactly once. The fallback picks the local axis least
aligned with `d̂_local = Rwᵀ d̂`; choosing among world axes assumes `Rw = I` and is wrong for a rotated obstacle, and the
chosen axis is rotated back with `Rw` before it is used as a world direction.

For a sphere (`rw = (R + e, R + e, R + e)`, `R = radiiMetres · s`), `h` is `R + e` for any `l̂_d`, so `d_1 = R + 2e`,
which in metres is `radiiMetres + 2·ε_pen`. The rule therefore never inflates twice by accident and never mixes frames.

Insert **one** waypoint at `cw + lateral · d_k` — one side only. Two waypoints on opposite sides would make the path
cross the obstacle between them, which is the failure this rule exists to avoid. The side is chosen by the rule above
and stays fixed within a pass; if the path still fails, the next pass escalates the distance (1.5× per pass, bounded by
the declared corridor limit) rather than flipping sides, so the outcome stays deterministic. Exhausting the corridor
limit is `NO_ROUTE`.

Escalation is not decoration: a waypoint placed exactly at the support distance leaves the two connecting segments
grazing the surface, because they approach obliquely. For a 20 mm sphere on a 0.29 m chord, `d_1 = R + 2e` = 20.2 mm
gives a closest segment approach of 20.0068 mm — still inside the 20.1 mm inflated radius — while the first escalated
placement at `1.5·d_1` = 30.3 mm leaves both connecting segments disjoint from the inflated sphere (closest approach
29.6594 mm > 20.1 mm). The rule therefore treats `d_1` as a starting guess and lets the validator, not the guess,
decide. (The anisotropic case below needs no escalation; this sphere example is the one that does.)

**Worked frame check — `rotated_anisotropic`.** One obstacle with local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z
(local X → world +Y), `ε_pen` = 0.1 mm, chord `(−145, 0, 0) → (145, 0, 0)` mm in world X through `cw`. Here
`d̂ = (1, 0, 0)`, `u = 0` (midpoint at the centre), and `Rwᵀ d̂ = (0, −1, 0)`: the local axes least aligned with it are
X and Z (both dot 0), so the tie order picks local X, rotated back to world `(0, 1, 0)`. Correctly, `l̂_d = (1, 0, 0)`
and `h = rw_x` = 40.1 mm → `d_1` = 40.2 mm, at which both connecting segments are already disjoint (`Δ ≈ −0.10`,
`Δ_tol ≈ 3.4e-7`); `d_2` = 60.3 mm is a wider margin (`Δ ≈ −1030.8`). Choosing the fallback in world axes instead
(world +Y fed straight into `h` as if local) gives `h = rw_y` = 10.1 mm, a waypoint 10.2 mm from the centre — still
inside the 40.1 mm inflated boundary — so the validator rejects it (`Δ ≈ 771` > `Δ_tol`). The `rotated_anisotropic`
fixtures in §12.2.1 pin both halves: the validator must honour `Rw`, and the constructor must transform the fallback and
the support direction.

**What it guarantees, and what it does not.** The rule is deterministic, bounded (≤ 1 detour waypoint per hit per pass,
plus its entry/exit samples, with the pass cap `K`), and sufficient for a single convex obstacle and for short sequences
applied in declaration order. It is **not a complete planner**: configurations exist in which a valid route exists and
this rule fails to find it. Failure is `INVALID` (`NO_ROUTE` or `PASS_CAP`), never an accepted penetrating path, and
completeness is not claimed. The fixtures in §12.2.1 state which configurations must succeed; a configuration outside
that set is a research question, not a regression.

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
MuscleState.path  ──►  A: tube generator  ──► dynamic vertex buffer
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

That is the whole model. A muscle that shortens looks shorter. It does not automatically get thicker, and it does not
bulge because the exercise says it is working.

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
morphs or correctives may change vertices, but must leave every `MuscleState` — path, `pointCount`, length, rest length,
strain, `PathStatus` — bit-identical. `MuscleGeometryOwnershipTest` (L4/presentation boundary) asserts exactly that, and
also asserts the converse: nothing in the character layer writes `AnatomyFrame`.

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
derived from a measured length (M1 has none); any substitute heuristic ("thicker when the exercise is harder", "pulse on
the concentric phase"). `strain` is reported for the length readout and the education panel; it is never a geometry
input.

### 7.7 Layers, and what is deferred

| Layer | Contents | v1 | Notes |
| --- | --- | --- | --- |
| superficial muscles | 8 muscles for M1, ~35 for v2 | yes — the 8 | quads, hamstrings, glutes, delts, pecs, lats, biceps, triceps, calves, erectors, abs |
| deep muscles | 8–12 for v2 | no — deferred beyond M1 | psoas, iliacus, rotator cuff, transverse abdominis, multifidus |
| tendons / ligaments | authored bands from the same attachment data | later | needs its own geometry contract; not a muscle with different colour |
| organs | meshes rigged to the thorax/abdomen | later | explicitly deprioritised by the task; the reference implementation's organs are a separate asset with its own animation, which this framework would have to justify mechanically |
| attachment markers | spheres/labels at attachment points | later | useful for authoring and education, cheap once data exists |

### 7.8 Missing research and art requirements (explicit)

| Need | Why it blocks | Smallest resolution |
| --- | --- | --- |
| Attachment library for the eight M1 muscles, in engine metres | the schema is useless without calibrated coordinates | an anatomist/physio reviews a table against the rest stance; one 2-hour session plus one revision pass |
| Wrap-surface parameters (hip capsule, ribcage, shoulder girdle) | wrong radii make paths cut through the body or float | tune against 3 poses (squat, overhead reach, hip hinge) and freeze per definition version |
| Muscle mesh authoring pipeline | strategy C needs meshes bound to helper bones | one Blender-based pipeline producing a GLB with helper bones + 4 weights; validate with the asset tool |
| Left/right and origin/insertion conventions per muscle | mirroring errors are silent | the side check (§12 L2) plus a per-muscle review checklist |
| Pennation / multi-head handling | biceps and triceps need two/three heads sharing one visual belly | model heads as separate paths with a shared mesh region; validate that head lengths differ plausibly |
| Whether a single skin can hold all helper bones | Filament's per-renderable bone budget is engine- and driver-dependent and **unmeasured here** | measure on one device; if the budget is too small, split the muscle layer into regional renderables (§10.3) |
| Per-muscle cross-section behaviour, if it is ever wanted | no validated model exists; the cylinder law is a plausibility argument, not evidence (§7.6) | an imaging-based study of the specific muscles through their working ranges, with a stated tolerance — a research task, not an engineering one |

---

## 8. Muscle activation semantics

### 8.1 What the model is

An **authored educational classification**, at the granularity of "which muscles participate, in what role, in which
phase of which exercise". It is not EMG, not a physiological simulation, and not a claim of measured intensity.

```
Exercise → Muscle → Role → MovementPhase → Emphasis
```

- **Role**: `PRIME_MOVER`, `SYNERGIST`, `STABILISER`, `ANTAGONIST` (the last for the eccentric/control case).
- **MovementPhase**: `CONCENTRIC`, `ECCENTRIC`, `ISOMETRIC` — read from the exercise's reviewed `PhaseTimeline`, never
  inferred per muscle frame and never derived from joint angles.
- **Emphasis**: an ordinal tier (`PRIMARY`, `SECONDARY`, `SUPPORTING`), not a percentage. A numeric 0–1 value may exist
  internally for shading, but it is a *display* intensity derived from the tier and the phase, and the UI must not label
  it as activation percentage.

### 8.2 Data

```json
{
  "exercise": "back_squat",
  "phaseTimeline": "back_squat@v1",
  "source": "anatomy-review-2026-11",
  "muscles": [
    { "id": "gluteus_maximus",  "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["CONCENTRIC", "ECCENTRIC"] },
    { "id": "vastus_lateralis", "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["CONCENTRIC", "ECCENTRIC"] },
    { "id": "biceps_femoris",   "role": "SYNERGIST",   "emphasis": "SECONDARY", "phases": ["CONCENTRIC"] },
    { "id": "erector_spinae",   "role": "STABILISER",  "emphasis": "PRIMARY",   "phases": ["ISOMETRIC"] }
  ]
}
```

`source` is the provenance id of the review that signed the row, and `phaseTimeline` names the reviewed timeline the
`phases` values are checked against. Both are required keys; the example is schema-shaped, not reviewed content.

Rules:

- **Every entry carries a source.** No source, no entry — the validator rejects it (§8.3).
- **Roles are per exercise, not per muscle.** The same muscle may be a prime mover in one exercise and a stabiliser in
  another; that is the educational content.
- **Phases come from a reviewed timeline, not from joint motion.** The engine publishes no concentric/eccentric/isometric
  timeline: `PoseMetadata` carries `durationSeconds`, `loopMode`, `motionCurve` and breath fractions, and `LoopMode.HOLD`
  describes a loop behaviour, not a muscle's role. A stabiliser can be isometric through an exercise whose joints are
  moving, so phase membership cannot be inferred from motion at all. Each exercise therefore needs an authored,
  reviewed `PhaseTimeline` (stage 4 deliverable: phase name, start/end fraction of the cycle, source), and the validator
  checks the table against it. An entry with no timeline behind it is marked `phaseSource: UNREVIEWED` and may not be
  displayed.
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

- Colour by role and phase (concentric/warm, eccentric/cool, isometric/neutral), driven by the same tier that the panel
  shows.
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
- **One character, one skeleton.** All layers share one bone buffer; a layer may add renderables to it but never a
  second skeleton.

### 9.2 Essential for the first useful version

1. Skin (the character).
2. Superficial muscles, the eight M1 muscles.
3. Skeleton overlay.

`SELECTED` exists from the start as state (highlight + panel). In M1 its content is the muscle **name and layer**, plus
the reported length; it does **not** show a role, because roles come from the activation table and that is stage 4 (§8,
§15). Showing a role in M1 would either invent one or ship an unreviewed claim. `MUSCLES_DEEP` follows in v2; organs,
tendons, labels and attachment markers are explicitly later.

### 9.3 Interaction

Projection-based picking (the AA-3D reference does this with a raycaster) is optional for M1: Filament supports picking
via renderable queries, but the value of picking without labels is low. The M1 acceptance is layer toggling; picking is
a stage-5 item.


---

## 10. Renderer and asset architecture

### 10.1 Filament, used directly — constraints as verified

| Constraint | Value | Consequence for this framework |
| --- | --- | --- |
| Filament version | 1.75.1 (`gradle/libs.versions.toml:15`) | 1.76+ needs `compileSdk 37`; the app is on 34. Upgrade is a separate change, not a prerequisite |
| Native payload | 7.4 MB arm64 stripped (`docs/CHARACTER_FRAMEWORK.md` §1) | character is opt-in packaging; a dynamic feature or flavor split is packaging work, not architecture |
| Backend | `DEFAULT`; Vulkan attempted only on explicit request, GLES fallback (`docs/CHARACTER_RENDERING.md` §5) | never assume Vulkan; the reported backend is `Engine.getBackend()` |
| Engine lifetime | one engine, one active character, `ActiveCharacter` slot (`CharacterFeature.kt:24-30`) | layers are renderables in that one scene; a second live character is out of scope |
| Pose handoff | `SkeletonPose` is reused; bone upload happens inside the producing call (`CharacterSession.kt:310-318`) | anatomy must run in the same window; no frame queue |
| Surface | `TextureView` + `UiHelper`; `Choreographer` loop started on resume | surface gaps and context loss need the documented recovery path (`docs/CHARACTER_RENDERING.md` §4) |

The renderer is **not** allowed to know about exercises, poses, muscles, activation or anatomy. It receives a bone
buffer, a morph weight vector, and visible renderables. Everything semantic is resolved before it.

### 10.2 Scene contract

```kotlin
/** Everything the renderer needs for one frame; no exercise or anatomy types. */
class CharacterSceneFrame(
    val baseBones: FloatArray,        // column-major 4x4 per bone, skin order
    val helperBones: FloatArray,      // appended range, same layout
    val morphWeights: FloatArray,     // per renderable target set
    val visibleLayers: Int,           // bitmask of CharacterLayer
    val selectedMuscle: Int           // -1 for none; material parameter only
)
```

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

**The M1 procedural layer's budget is a formula, not a sample count.** A tube is generated from the polyline, so its cost
is set by both longitudinal samples and ring tessellation, and by the number of heads:

```
triangles per head ≈ 2 × (N − 1) × R          // N = path samples, R = ring sides
M1 total          ≈ Σ over heads of that, plus 2 × R per capped end
```

At the declared defaults (N ≤ 48, R = 8) and eight muscles with one head each, that is ≈ 6.0 k triangles; a two-head
muscle adds a second tube, and a flat sheet muscle uses a two-sided strip instead of a ring. Both `N` and `R` are
declared in the quality tier, so the number is bounded before any device run. It is arithmetic, not a measurement: the
device gate in stage 1 measures frame time and memory, and the measured number replaces the estimate in this table.

**Bone-budget risk.** A single skin holding every muscle's helper bones may exceed what Filament accepts per renderable
on a given driver. That number is **unmeasured here**. The design therefore allows the muscle layer to be split into
regional renderables (trunk, left arm, right arm, left leg, right leg, head), each with its own skin and helper range.
The split is an asset decision recorded in the manifest, not a runtime guess.

### 10.4 Loading, caching, export and thumbnails

- **One GLB per layer.** Loaded with gltfio `AssetLoader`/`ResourceLoader`, memory-mapped, uncompressed
  (`noCompress += "glb"`, `app/build.gradle.kts:71`). Versioned by `assetVersion` in the manifest.
- **Offline export uses the runtime's own maths.** The tool that places helper bones and validates muscle bind poses is a
  JVM CLI module that depends on the pure-Kotlin `anatomy` package and a rest-stance pose. A Python re-implementation
  would be a second source of truth for attachment and path maths and is explicitly rejected.
- **Rest bind is the engine rest stance.** The exporter asks the anatomy solver for `restLength` and helper positions at
  the bind stance; the GLB's inverse binds are computed from those positions. This is the only way the rest invariant
  (bone matrices = identity at rest) can hold for muscles as well as for the body.
- **Thumbnails stay 2D** until a static-render pipeline exists: the proposal in `docs/CHARACTER_RENDERING.md` §7
  (one offscreen engine, one frame per key, LRU cache, 2D fallback) remains the plan. No animated 3D thumbnails.
- **Surface lifecycle** is the existing `CharacterSession.destroy()` contract; layers add nothing to it except unloading
  their assets first.
- **Quality tiers** select assets, not code paths: `standard` and `reduced` share rig, bone map, anatomy and motion.
  `reduced` may use strategy A (procedural tubes) for muscles where C's asset cost is unaffordable.

### 10.5 Asset validation requirements

Each GLB is validated before it can be referenced by a profile (offline, plus a JVM test that parses the checked-in
assets):

| Check | Failure mode it prevents |
| --- | --- |
| Required bones present and mapped (`BoneMap`) | silent rest pose for a limb |
| Bind pose == engine rest stance (rest invariant, all bones ≈ identity) | figure in T-pose / sheared at frame 0 |
| Inverse bind count == joint count, column-major float, no NaN | garbage skinning on some drivers |
| Node hierarchy acyclic, one skeleton root | gltfio skin failure |
| Morph targets: same count on every primitive of a mesh; names match the manifest; deltas finite | morph crash / wrong body |
| Weights: sum ≈ 1, ≤ 4 influences, no zero-weight vertices | collapsing vertices |
| Triangle and texture budgets per tier | unaffordable asset |
| Bone budget per renderable (measured, not assumed) | runtime skin failure |
| No embedded animation clips (the character is pose-driven) | two sources of motion |
| No ground/helper nodes that the presenter must exclude by name | the PR #385 asset's `Ground` node is excluded by `skinnedNodes`, which is the right rule — extend it to all layers |
| Anatomy attachment validity for every muscle in the layer (§12 L3) | a muscle attached to a joint that does not exist |
| `assetVersion` / `anatomyVersion` / `rigVersion` recorded and matched | mixed-version skinning |

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
| Rest-delta retarget | established technique | rotations only; translation only at the pelvis | L2 rest invariant |
| Bone-name resolution with aliases | established technique | alias tables can map the wrong bone | L2 coverage + manual rig review |
| Anatomical attachment points | **requires expert validation** | a plausible-looking point can still be wrong | L3 mechanical + L7 review (§12.4) |
| Path + ellipsoid obstacle wrapping | approximation — geometric, not contact | ellipsoids stand in for bone and fascia; the segment test is exact for the polyline, the approximation is the obstacle model | L4 fixtures + visual review |
| Arc-length measurement along the path | established technique | depends on path sampling density | L4 |
| Muscle cross-section response | **not implemented — research hypothesis only** (§7.6) | no validated per-muscle deformation model; the cylinder law is a plausibility argument. Linear-blend skinning still stretches the mesh between separating helpers — a skinning artifact, not a modelled response | none — not a validation target |
| Morph-target morphology | visual approximation | surface only; joints can degrade without a joint rule | L6 |
| Corrective shapes | visual approximation | authored per rig; not anatomy | L6 + visual |
| Activation colouring | **educational model, not physiology** | no EMG, no measured intensity, no per-frame claim | §8 schema + expert review |
| Organ motion (future) | research required | no defensible mechanical driver at this stage | not planned |
| Skin/muscle self-intersection | unverified | no runtime collision; mesh checks are offline only, and M1 ships no mesh-intersection test | L5 from stage 3; L7 judgement in M1 |
| Anatomical review of attachments and placement | **required human gate** (§12.4) | professional judgement against named references; recorded, never scored | L7 record |

**Hard rules.**

1. No muscle geometry moves for a reason that is not in the ownership table of §7.6. Path following is the only
   anatomy-derived motion; morphs and correctives are character-owned, must not touch `AnatomyFrame`, and are not anatomy;
   an `INVALID` path is reported, never drawn as if it were valid.
2. No numeric activation value is displayed without a cited source; tiers are ordinal.
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
| **L1 Motion** | 18 existing rules over joint-space geometry (`ExerciseValidator.kt:47-90`) | yes, unchanged | all | the pose/engine is wrong; block |
| **L2 Rig** | bone coverage, rest invariant, inverse binds, side/handedness, unit scale, bone budget, landmark alignment (§6.6) | yes | all | the asset or map is wrong; block |
| **L3 Attachments** | every attachment's joint exists; offset inside a segment envelope; L/R mirror; head count; origin ≠ insertion; span ≥ 2 segments | yes | all | the anatomy data is wrong; block |
| **L4 Path** | every segment of the final polyline tested analytically against every obstacle (closed-form segment–ellipsoid), plus a sample-outside-inflated-surface check using the `ε_pen` numerical margin; unverifiable geometry reported `INVALID`; deterministic outcome for overlapping, nested and impossible routes; tangent and curvature continuity at entry/exit; temporal continuity; rest-length tolerance vs measured reference | yes, with adversarial fixtures | all | the solver or the data is wrong; block |
| **L5 Mesh** | self-intersection, degenerate triangles, weight sums, volume sanity per muscle, helper-bone bind match | partly (tool) | **subset** (§12.5) | the mesh is wrong; block release, not build |
| **L6 Morphology** | per-preset rest invariant, joint-ring preservation under morphs, delta bounds, anatomy still inside the body | yes | **subset** (§12.5) | a preset breaks the rig; block the preset |
| **L7 Anatomical review** | A1–A6 in §12.4: attachment locations, position vs bone and body surface, visible intersections, shape/path plausibility across poses, preset consistency, provenance | no — human, **required for M1** | all | the model is not accepted; M1 does not ship |

### 12.1 What is genuinely automatable

- L1 (already), L2 (fully), L3's mechanical half (existence, envelope, mirror), L4's numeric half, L6 (fully), and L5's
  geometric half. These are the parts a CI job can gate.
- L4's numeric half is exact for the polyline it is handed — closed-form segment–ellipsoid tests, not a sample census
  (§7.4.1). What it still cannot certify is the anatomical surface the ellipsoids stand for, the frames it was not run
  on, or a rendered curve that is not the validated polyline.
- **Not automatable:** whether an attachment is the *right* anatomical point; whether a wrap produces a believable
  contour; whether a muscle mesh reads as that muscle; whether a path looks plausible in a pose nobody posed a fixture
  for. Any claim otherwise is false confidence, and this document refuses to design a "score" that hides it. §12.4 is
  where those judgements are recorded.

### 12.2 New test inventory (names, not implementations)

| Test | Level | Asserts |
| --- | --- | --- |
| `RigAssetContractTest` | L2 | every required bone present, bind == rest, inverse binds finite, ≤ 4 influences |
| `PoseRetargeterRestInvariantTest` | L2 | neutral pose → identity on every bone (exists in PR #385; extended to v2 rig) |
| `RigLandmarkAlignmentTest` | L2 | §6.6 A1: every mapped bone head coincides with its engine joint at bind within `τ_bind`; rest orientations within `τ_rot` |
| `RigAlignmentResidualTest` | L2 | §6.6 A2: the propagated length-error bound per joint, and the measured residual at three poses, both within `τ_residual` |
| `BoneMapCoverageTest` | L2 | 23 mapped joints resolve; unmapped joints are declared, not accidental |
| `RigSideTest` | L2 | `Left*` bones on the same side as anatomy side A in the rest pose |
| `AnatomyDefinitionTest` | L3 | ids unique; attachments resolve; envelope bounds; L/R mirror; heads consistent |
| `MusclePathGeometryTest` | L4 | arc length ≥ chord; every segment of the smoothed polyline is disjoint from every inflated obstacle, and every sample lies outside the inflated surface by the `ε_pen` numerical margin; deterministic across runs and buffer reuse |
| `MusclePathObstacleFixtureTest` | L4 | the validator fixtures (§12.2.1) return the expected verdict, and the solver fixtures return the expected final path or the expected failure reason — never silently |
| `MusclePathContinuityTest` | L4 | C0, tangent bound at entry/exit, curvature bound, and frame-to-frame displacement at matched arc-length stations within `δ_frame` |
| `MuscleLengthDeterminismTest` | L4 | the same pose produces the same length across runs, buffer reuse and definition reload; no discontinuity beyond tolerance across a sampled motion |
| `MuscleLengthReferenceTest` | L4 | length matches expert-reviewed reference data where it exists, within the stated tolerance; absent reference data is reported as a gap, not a pass |
| `MuscleDirectionalExpectationTest` | L4 | only exercise- and muscle-specific expectations, each carrying a documented justification and source |
| `MuscleRestLengthTest` | L4 | solved rest length matches the calibrated reference within tolerance |
| `MuscleGeometryOwnershipTest` | L4 / presentation | §7.6: toggling morphs or correctives leaves every `MuscleState` bit-identical, and nothing in `character/` writes `AnatomyFrame` |
| `ProceduralTubeBudgetTest` | L5 (M1 subset) | generated triangles match the §10.3 formula for the declared `N` and `R`; no zero-area ring; all coordinates finite |
| `MorphPresetTest` | L6 | rest invariant per preset; joint rings unmoved beyond tolerance |
| `LayerToggleTest` | presentation | toggling a layer changes visibility only; no engine or pose rebuild; an evicted layer reloads and re-shows |
| `CharacterDependencyTest` | architecture | Compose in `animation/` only in the three allow-listed files (§4.5); motion core free of Compose; `animation/` has no anatomy/character/Filament imports; `anatomy/` has no character/Android imports |
| `AssetBudgetTest` | L5 | triangle/texture/bone budgets per tier from the manifest |

All except `LayerToggleTest` are JVM tests (the character session itself requires a device). `LayerToggleTest` splits:
a pure state test on the controller in JVM, and a device smoke test in the existing diagnostics screen.

None of these tests establishes anatomical correctness, and none of them replaces §12.4.

**No universal monotonicity test.** A tempting requirement is that the quadriceps shorten monotonically through a
squat's concentric phase. It is not safe as a universal rule. Rectus femoris crosses both the hip and the knee, so its
length is a function of two joint angles and can lengthen while the vasti shorten; a bi-articular muscle can also hold
length through a phase in which the joint moves. A test that forced the solver to produce a predetermined curve would
make the anatomy satisfy the test instead of the test describing the anatomy — precisely the inversion §11 exists to
prevent. The inventory therefore checks what is mechanically defensible: attachment and path correctness, determinism,
continuity, and agreement with reviewed reference data where such data exists. Directional expectations are allowed only
per muscle and per exercise, with the justification and its source recorded next to the test.

#### 12.2.1 Adversarial path fixtures

Two kinds of fixture are needed, and conflating them was a defect in an earlier revision: a **validator fixture** hands a
finished polyline to the validator and states the verdict it must return; a **solver fixture** hands an obstacle
configuration to the constructor and states the *final* path or failure it must produce. A raw crossing polyline must be
rejected; a solver run on the same obstacle may legitimately return `VALID` after routing around it. Both facts are true
at once, and only a split table can say so.

**Validator fixtures — raw polyline in, verdict out.**

| Fixture | Configuration | Expected verdict |
| --- | --- | --- |
| `raw_clear` | a polyline that clears every obstacle | `VALID` |
| `clear_negative_discriminant` | unit-sphere case `(q0, qd) = ((2,0,0), (0,1,0))`: `Δ = −12 ≪ −Δ_tol` | `VALID` — disjoint; a test that maps a large negative `Δ` into the near-tangent band fails here |
| `clear_roots_outside_segment` | unit-sphere case `((2,0,0), (1,0,0))`: `Δ = 4` > `Δ_tol` but roots `[−3, −1] ∩ [0, 1] = ∅` | `VALID` — the infinite line crosses the ellipsoid but the finite segment does not; a `Δ ≥ 0`-only verdict fails here |
| `intersecting` | unit-sphere case `((−2,0,0), (4,0,0))`: `Δ = 64`, roots `[0.25, 0.75]` | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` |
| `near_tangent_exact` | unit-sphere case `((−2,1,0), (4,0,0))`: `Δ = 0`, `Δ_tol = 5.12e-10` | `INVALID` — `abs(Δ) ≤ Δ_tol` fails closed; tangency is rejected, not accepted (§7.4.1, §7.4.4) |
| `near_tangent_rounding` | same with `q0 = (−2, 1+1e-13, 0)`: `Δ` rounds to `≈ −0` | `INVALID` — still inside the near-tangent band; a test that treats a slightly negative `Δ` as clear fails here |
| `q_zero_centre_start` | unit-sphere case `((0,0,0), (1,0,0))`: `b = 0`, `c0 = −1`, `Δ = 4` | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`. With `sign(0) = +1`, `q = −1` and roots `[−1, 1] ∩ [0, 1] ≠ ∅`; a textbook `sign(0) = 0` gives `q = 0` and a non-finite `t1`, so the fixture fails that implementation (§7.4.1) |
| `outside_endpoints_crossing_obstacle` | thin ellipsoid on the chord, both segment endpoints outside | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`; a sample-only check wrongly accepts it |
| `raw_crossing_thick` | chord through a thick obstacle, samples inside | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` |
| `rotated_obstacle` | an obstacle rotated with its segment, segment clear only in the unrotated frame | `INVALID` — a test that ignores `Rw` misses it (§7.4.1b) |
| `rotated_anisotropic` | local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z (local X → world +Y); straight chord `(−100, 15, 0) → (100, 15, 0)` mm | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` with the correct `Rw` (`Δ ≈ 1349` > `Δ_tol`); a test that drops `Rw` reports clear (`Δ ≈ −120`). Exercises the frame transform on a non-spherical obstacle, not just a rotation |
| `non_unit_scale` | sphere `radiiMetres` = 0.020 m, chord `(−100, 20.05, 0) → (100, 20.05, 0)` mm; `s = 235/0.95 ≈ 247.4`, `ε_pen` = 0.1 mm | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE`. Correct inflation `rw = (0.020 + ε_pen) · s` = 20.1 mm gives `Δ ≈ 1.97` > `Δ_tol`; applying `ε_pen` as if it were engine units (omitting `· s`) gives 20.0004 mm and `Δ ≈ −1.99`, a false clear. Paired control: move the chord to `y = 20.15 mm` (0.05 mm beyond the inflated surface) → `VALID`, so the fixture cannot pass by rejecting everything |
| `endpoint_inside` | an attachment resolved inside a declared obstacle | `INVALID` — `ENDPOINT_INSIDE_OBSTACLE` |
| `non_finite` | a NaN coordinate or radius | `INVALID` — `NON_FINITE_GEOMETRY` |
| `degenerate_obstacle` | a zero or negative semi-axis | `INVALID` — `DEGENERATE_OBSTACLE` |

**Solver fixtures — obstacle configuration in, final path out.**

| Fixture | Configuration | Expected result |
| --- | --- | --- |
| `single_obstacle` | one ellipsoid between two attachments, chord through its centre | `VALID`; the §7.4.5 lateral rule routes around it; every segment disjoint |
| `rotated_anisotropic` | the `rotated_anisotropic` validator geometry as a solver run: local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z, chord `(−145, 0, 0) → (145, 0, 0)` mm through the centre | `VALID`; the §7.4.5 fallback picks local X (tie X/Z), rotates it to world +Y, and `h(l̂_d)` = `rw_x` = 40.1 mm gives `d_1` = 40.2 mm with both segments disjoint (`Δ ≈ −0.10`). Choosing the fallback among world axes, or using the world direction directly as the support argument, yields `h` = 10.1 mm and a waypoint still inside the obstacle → `INVALID`; the fixture fails that implementation |
| `multiple_obstacles` | two obstacles in series along the route | `VALID`; every segment disjoint from both after smoothing |
| `overlapping_obstacles` | two ellipsoids that intersect each other | `VALID` outside the union; identical result for the same declaration order |
| `nested_obstacles` | a small ellipsoid fully inside a large one | `VALID` outside the outer surface |
| `near_tangent` | an obstacle whose surface is tangent to the straight chord | `VALID`; the solver routes around it so the **final** polyline is strictly disjoint (`Δ < −Δ_tol`, not merely `abs(Δ) ≤ Δ_tol`); no oscillation; tangent check within `θ_max`. This is the solver half of the split: the raw tangent chord is rejected by the validator (§12.2.1 `near_tangent_exact`), but a detour that clears the near-tangent band is a legitimate `VALID` result |
| `no_valid_route` | obstacles that close the corridor between the attachments | `INVALID` — `NO_ROUTE`; the frame reports it and nothing is drawn along it |
| `pass_cap` | a configuration that oscillates between two surfaces | `INVALID` — `PASS_CAP`; deterministic and terminating, not a hang |

**`outside_endpoints_crossing_obstacle`, constructed so it cannot pass by accident.** One ellipsoid with semi-axes
4 mm × 2 mm × 2 mm centred on the straight chord between two attachments 0.29 m apart, with the polyline running along
the ellipsoid's long axis through its centre. With `ε_pen` = 0.1 mm the inflated long semi-axis is 4.1 mm (§7.4.1). At
the 0.01 m base spacing the samples land at ±5 mm from the centre, where `|q| = 5/4.1 ≈ 1.22 > 1`: **no sample is
inside the inflated obstacle, and no segment has one endpoint inside**, so the sample check and a boundary-straddle
refinement rule both report "clear" while the path passes through the obstacle's centre. The segment test returns
`Δ ≈ 23.8` > `Δ_tol` with roots `[0.09, 0.91] ∩ [0, 1] ≠ ∅` and reports `SEGMENT_INTERSECTS_OBSTACLE`.

The obstacle is deliberately thinner than the sample spacing, because the check must not depend on an obstacle being
fatter than the polyline's resolution. Its paired control is the same geometry with the chord offset 5 mm laterally,
which must stay `VALID` — so the fixture cannot pass by rejecting everything, and a solver that always returns
`INVALID` fails the control just as a sample-only solver fails the fixture.

A fixture whose expected outcome a broken implementation also produces is not a fixture. This one is defined by the
property that the two checks disagree, and the L4 report records which check produced each verdict. A fixture that
produces an `INVALID` result where the table expects `VALID` fails L4; a fixture that produces a path through geometry
fails L4 twice — once for the geometry and once for accepting it.

### 12.3 Device-only evidence (not claimed here)

Frame time, jank, thermal drift, surface recreation and context loss cannot be validated in this environment. They are
gates on stage 1 and are recorded in `docs/CHARACTER_DEVICE_VALIDATION.md` (PR #385) with backend, FPS, p95 frame time
and defects per device.

### 12.4 Anatomical review gate (M1 acceptance)

M1 does not pass because the automated levels are green. Those levels prove the data is well-formed and that the
geometry obeys its own rules. They cannot prove that an attachment is the right point on the right bone, that a belly
sits in the right compartment, or that a silhouette reads as the named muscle. The gate is a recorded human review with
named criteria, a named reviewer and named references.

**Criteria.** Reviewed per muscle, per side, in the poses the milestone actually ships:

| # | Criterion | What "pass" means |
| --- | --- | --- |
| A1 | Attachment locations | each origin and insertion lands on the landmark named in the definition, within the tolerance stated in the review record |
| A2 | Position relative to bones and body surface | the belly sits in the correct compartment: not inside bone, not outside skin, not on the wrong side of a joint |
| A3 | Visible intersections | no intersection with skin or with an adjacent muscle in any reviewed pose, at the layer visibility the milestone ships |
| A4 | Plausible shape and path across poses | path and silhouette read as that muscle in at least three distinct poses — for M1, squat bottom, squat top and push-up bottom — plus any pose the reviewer asks for |
| A5 | Consistency across presets | every morphology preset shipped in the milestone is reviewed; a preset added later re-opens the review for the muscles it moves. M1 ships one preset, so this criterion is satisfied by reviewing that one and is not evidence about any other |
| A6 | Provenance | every judgement in the record points at an identifiable reference, not at "looks right" |

**Reviewer.** A qualified anatomist or physiotherapist, named in the record, independent of the person who authored the
attachment table. Test authorship is not review.

**References.** Identifiable and citable: a named atlas with its edition, a peer-reviewed source, or an imaging dataset
with its access note. This document deliberately names none it has not verified; the review record carries the citation
keys and the provenance note, and inventing a reference is a review defect of the same severity as a wrong attachment.

**Record.** One versioned file per anatomy version (proposed `docs/anatomy/anatomy_review_v1.md`; not created by this
change) containing: reviewer, date and qualifications; the reference list; per-muscle findings against A1–A6; and an
explicit **unresolved limitations** list. A limitation is not a failure — hiding one is.

**What the record does not do.** It does not convert to a score, it does not make L7 automatable, and it does not
retroactively validate poses, presets or meshes it did not cover. A review of `anatomy_v1` does not cover `anatomy_v1.1`
or a new morphology preset.

**Renewal — the record is bound to what was reviewed.** A record names the tuple it covers: `anatomyVersion`,
`rigVersion`, `assetVersion`, `presetId`, the muscle list, the sides, the poses and the layer configuration. It is valid
only for that tuple. Three events force a new or amended record, and none of them can be waived by a passing automated
level:

| Event | Why it re-opens the gate | What the amendment must cover |
| --- | --- | --- |
| Stage 3 replaces procedural tubes with authored muscle meshes | the reviewed silhouette and intersections no longer exist | A2, A3, A4 on the new assets, per muscle, both sides |
| Stage 5 adds a morphology preset or body family | morphs move the surface the muscles must sit under | A5 across presets, plus A2/A3 on each new preset |
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
| L6 | one preset: rest invariant, joint-ring preservation, delta bounds | cross-preset coverage; "anatomy still inside the body" as an automated pass | one preset exists; body-anatomy intersection needs a tool M1 does not ship, so it is an L7 A2/A3 judgement |
| L7 | A1–A6 with a valid record (§12.4) | — | the human gate |

**M1 gate, stated once.** M1 passes when: L1–L4 green; the L5 and L6 subsets above green; device numbers recorded for the
M1 configuration (skin + muscle layer + skeleton overlay); and the §12.4 record exists for the exact
`(anatomyVersion, rigVersion, assetVersion, presetId)` shipped. Any other combination is not an M1 pass, whatever the
individual reports say.

---

## 13. Risks and unresolved decisions

| # | Risk | Likelihood | Impact | Mitigation | Owner |
| --- | --- | --- | --- | --- | --- |
| R1 | Filament toolchain pin (1.75.1, compileSdk 34) blocks needed features | medium | medium | keep the pin; treat the compileSdk upgrade as a separate change with its own CI | engineering |
| R2 | Muscle authoring cost is much larger than estimated | high | high | M1 uses 8 muscles; strategy A proves the pipeline before art is commissioned | product + art |
| R3 | Per-renderable bone budget too small for the library | medium | high | regional renderable split (§10.3); measure at stage 1 | engineering |
| R4 | No device in the build environment | certain | medium | stage 1 device gate before any expansion; claims never stated without it | engineering |
| R5 | Anatomy review capacity (physio/anatomist) is the bottleneck | high | high | batch reviews per layer; L3/L7 checklists reduce the time per muscle | product |
| R6 | Morphs degrade joints silently | medium | medium | L6 rest invariant + joint-ring test | engineering |
| R7 | Attachment data drifts when `SkeletonDefinition` changes | medium | high | one `unitsPerMetre`, rest-length test, definition version pin | engineering |
| R8 | PR #385 changes before merge, or is rejected | medium | medium | this RFC depends on its contracts, not its files; rebase plan in stage 1 | engineering |
| R9 | Deep muscles / organs expand scope beyond appetite | high | low | §16 non-goals and the layer table's v1 column | product |
| R10 | Left/right handedness mismatch between anatomy and rig | medium | medium | `RigSideTest`; asset fix, never a render-time flip | engineering |
| R11 | L4 proves the polyline misses the ellipsoids, not that the rendered muscle or the anatomical surface does | medium | low | strategy A consumes the validated points unchanged and re-smoothing is forbidden (§7.4.1); the ellipsoid model is classified as an approximation (§11); L7 review covers what geometry cannot (§12.4) | engineering |
| R12 | The §7.4.5 detour heuristic fails on a configuration where a valid route exists, producing `INVALID` frames | medium | medium | failure is explicit and blocks L4 rather than shipping a penetrating path; the fixture set states which configurations must succeed; new cases are added as fixtures, not fixed by loosening the check | engineering |
| R13 | A new rig asset passes bind alignment but drifts in motion because segment lengths differ | medium | medium | §6.6 A2 bounds the residual before rendering; `RigAlignmentResidualTest` measures it at three poses; the fix is the asset, never an anatomy nudge | engineering + art |

**Unresolved, with the smallest resolving experiment:**

1. **Bone budget per renderable** — load the 8-muscle layer with 32 helpers on one device; if it renders, double until
   failure. Resolves the split threshold.
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
7. **Whether a Euclidean clearance guarantee is ever required** — the current test guarantees only "no entry into the
   declared ellipsoid" (§7.4.1). A distance-based test (segment-to-ellipsoid distance ≥ ε) is a different computation and
   a different contract. Smallest resolution: state whether any consumer — rendering, review, or a future collision check
   — actually needs a distance, rather than an intersection verdict.
8. **Whether to move the Compose-dependent files out of `animation/`** — §4.5 freezes today's three-file exception and
   forbids growth. A package move would make the fence absolute and is a production-code change, so it needs the
   maintainer's approval as its own PR; until then the allow-list is the contract.
9. **Which tool measures muscle-versus-skin intersection** — L6's "anatomy inside the body" and L5's self-intersection
   are not automated for M1 (§12.5). Building a mesh-intersection check is a tooling decision, not an architecture one;
   until it exists those checks are L7 judgements.

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

**D5. How should muscle geometry react to length changes?**
*Through the path only. M1 applies no cross-section response.* A muscle's length and position follow from its solved
path; its cross-section is constant. This is the honest option: a muscle that shortens looks shorter, and nothing in the
picture implies a contraction magnitude the data cannot support. The isovolumetric cylinder law survives in §7.6 only as
an optional research hypothesis with validation preconditions attached — not as a v1 mechanism — and activation never
moves geometry in either case.

**D6. How should scapula and shoulder-girdle motion influence muscle paths?**
*Through attachments pinned to `SCAPULA_A/P` and `CLAVICLE_A/P`, plus one or two thorax wrap surfaces.* The engine
already animates the girdle (`SkeletonFactory.kt:71-80`; scapular rotation is authored and solved), so the paths follow
for free. What must not happen is a second shoulder model in the character layer (a look-at or an IK-driven scapula):
that would be a competing solver and would desynchronise muscles from the mesh. The rig's scapula bone must consume the
engine's scapula rotation, not a positional approximation.

**D7. Authored activation per exercise, or derived from reusable rules?**
*Authored per exercise, with reusable phase rules and a required source.* Deriving activation from joint angles or
muscle length would manufacture physiology from geometry — precisely the fake mechanism §11 forbids. A rule set
("prime mover in the concentric phase of a knee-extension-dominant exercise") may generate a *draft* table, but a human
signs it and the source field records why.

**D8. How should anatomical layers be stored and loaded?**
*One GLB per layer per character; anatomy and activation as versioned JSON shared across characters; load on first show,
stay resident until the character detaches or an explicit eviction.* The layer boundary is the asset boundary because that
is what makes toggling free of engine work and what keeps the muscle library out of the APK's always-resident set. Hiding
a layer is a visibility flag; it is not an unload, and the RFC no longer claims otherwise (§9.1).

**D9. What belongs in GLB assets and what in runtime data?**
*GLB: meshes, skin weights, inverse binds, morph targets, materials, textures, helper-bone bind data. Runtime JSON:
rig mapping, attachments, wrap surfaces, roles, activation, labels.* Semantics in a binary is a review defect: anatomy
must be diffable, reviewable by a non-engineer, and shared across characters. Geometry in JSON would be the opposite
mistake.

**D10. How to support future female and male variants without duplicating anatomy or exercise logic?**
*One rig, one anatomy library, one activation table; variants are meshes + morph sets + materials, with sparse, named
body-family overrides in anatomy data.* Most muscle attachments are valid across adult body types at this level of
fidelity; where a shared point is measurably wrong (pelvic width, shoulder breadth), the override is a reviewed data
line, not a forked file. The acceptance test is mechanical: adding a variant must add zero pose builders, zero
`MuscleDefinition`s and zero activation rows.

---

## 15. Implementation roadmap

Stages are outcomes, not PR-sized steps. Each stage ends with something demonstrable and with its own evidence. Effort
is engineering-weeks for one experienced engineer, ±50%, excluding art; art is listed separately because it is the
schedule risk, not the engineering one.

**Summary**

| Stage | Outcome | Eng. | Art / expertise | Gate |
| --- | --- | --- | --- | --- |
| 0 | This RFC reviewed and accepted | 0 | — | reviewer sign-off |
| 1 | Rig v2 + asset validation + device evidence | 3–4 | 2–3 rig-art | L2 green, device numbers recorded |
| 2 | Anatomy kernel + 8 muscles as procedural tubes | 4–5 | 1 anatomist week | L3/L4 green on fixtures |
| **M1** | **one character, 1 preset, 3+ exercises, 8 muscles, 3 layers** | — | — | **demo + §12.5 gate + §12.4 record** |
| 3 | Path-rigged authored muscle meshes | 4 | 6–8 muscle art | L5 + visual review |
| 4 | Activation + education panel (3 exercises) | 2 | 1 review week | §8 validator + sign-off |
| 5 | Morphology v2: four body families (the first stage with more than one preset) | 3 | 6 art | L6 green per preset |
| 6 | Library expansion: ~35 superficial, ~12 deep | 6–8 | 12–16 art | L3/L4/L5 at scale |
| 7 | Labels, picking, explanations | 3 | 2 | visual review |

### Stage 0 — Ratify this document

- **Objective:** agree the model boundaries, the non-goals and the accuracy policy before code exists.
- **Components:** this document; review notes.
- **Dependencies:** PR #385 state (merged, rebased or rejected) recorded as a decision input.
- **Expected files:** `docs/architecture/MONK_HUMAN_FRAMEWORK.md` only.
- **User-visible deliverable:** none.
- **Automated acceptance:** document builds no code; CI unaffected.
- **Manual acceptance:** a reviewer can state the ownership of any object in §5.1 without reading the code.
- **Risks:** scope creep into "design the art".
- **Art/expertise:** none.

### Stage 1 — Rig v2, asset validation, device evidence

- **Objective:** replace the generated 32-joint test rig with a production-quality `humanoid_v2` body, prove the
  contract mechanically, and measure the presentation on at least one real device.
- **Components:** rig asset (23 mapped bones including the scapula pair, plus fingers), `BoneMap` manifest, `RigAssetContractTest`,
  `PoseRetargeterRestInvariantTest`, `RigSideTest`, budget checks, device measurements.
- **Dependencies:** the character runtime from PR #385 (merge or rebase); art for the base body.
- **Expected files:** `app/src/main/assets/characters/*/skin.glb`, `character/BoneMap.kt`,
  `character/RigAssetValidator.kt`, `app/src/test/.../RigAssetContractTest.kt`, `docs/CHARACTER_DEVICE_VALIDATION.md`.
- **User-visible deliverable:** a dev build showing a correctly proportioned human performing the current exercises,
  with the skeleton layer overlaid.
- **Automated acceptance:** rest invariant identity within 1e-4 on every bone; 23/23 mapped joints resolve; side check
  passes; budgets reported.
- **Manual acceptance:** no visible shear, no T-pose at frame 0, joints hold across a squat, a hinge and an overhead
  reach.
- **Risks:** rig art quality; scapula bone without a matching engine rotation would desync (mitigated by D6);
  toolchain pin.
- **Effort:** 3–4 engineering weeks + 2–3 rig-art weeks.
- **Art/expertise:** yes, rigging.

### Stage 2 — Anatomy kernel and eight muscles (procedural)

- **Objective:** make anatomy real, in engine space, with no art dependency: definitions, solver, frame, validation, and
  a debug muscle layer.
- **Components:** `anatomy/` package (pure Kotlin, no Android), `AnatomyDefinition` + loader, `AnatomySolver`,
  `AnatomyFrame`, `AnatomyDefinitionTest`, `MusclePathGeometryTest`, `MusclePathObstacleFixtureTest`,
  `MusclePathContinuityTest`, `MuscleLengthDeterminismTest`, `MuscleLengthReferenceTest`, procedural tube generator.
- **Muscles (8):** rectus femoris, vastus lateralis, biceps femoris, gluteus maximus, erector spinae, deltoid anterior,
  pectoralis major, gastrocnemius medial.
- **Dependencies:** stage 1 (rest stance + definition), anatomy calibration session.
- **Expected files:** `app/src/main/java/com/monkfitness/app/anatomy/*.kt`,
  `app/src/main/assets/anatomy/anatomy_v1.json`, `app/src/test/.../anatomy/*`.
- **User-visible deliverable:** a dev-only muscle layer showing red tubes following the squat and push-up, with a length
  readout per muscle.
- **Automated acceptance:** arc length ≥ chord on every frame; **every validator fixture in §12.2.1 returns its expected
  verdict** — including `outside_endpoints_crossing_obstacle`, which must be rejected — and **every solver fixture
  produces its expected final path or failure**; lengths are deterministic and continuous across a sampled motion; rest
  lengths within tolerance; no fixture outcome is reported without naming which check produced it.
- **Manual acceptance:** anatomist review of the eight attachment sets and of the wrap behaviour on three poses.
- **Risks:** calibration drift (R7); wrap parameters; the tube look invites quality complaints — state clearly that
  stage 2 is a mechanism demo, not final visuals.
- **Effort:** 4–5 engineering weeks + ~1 anatomist week.
- **Art/expertise:** anatomy, not art.

### Milestone M1 — first genuinely useful character

**Definition of done:**

1. One character (`male_neutral_v1`) on `humanoid_v2`, driven by the real pipeline through `CharacterStage`.
2. At least three existing procedural exercises (air squat, push-up, bird dog or plank) animate correctly with the
   character; no exercise builder changed.
3. Layers: skin, superficial muscles (the 8), skeleton overlay; toggling does not rebuild the engine. `MUSCLES_DEEP`
   exists in the enum as a reserved value but has no asset and no renderable (§7.7, §9.1).
4. Muscles follow the movement within the path model and report lengths; no independent muscle animation exists and no
   cross-section response is applied (§7.6).
5. One morphology preset (`neutral`). M1 demonstrates that the preset changes nothing in the pose, muscle or animation
   data; it does not demonstrate body-family coverage — four families are stage 5 (§6.3, §15).
6. The §12.5 gate is green: L1–L4, the L5/L6 M1 subsets (tube structure, one-preset invariants), and device numbers
   recorded for the M1 configuration (skin + muscle layer + skeleton overlay), not for the skin alone.
7. The anatomical review gate (§12.4) is passed and recorded for the exact shipped tuple: named reviewer, reference list,
   per-muscle findings against A1–A6, and unresolved limitations. A missing record fails M1 exactly as a red L4 does.
8. Every claim in the demo's copy survives §11: no "simulated", no measured activation, no implied contraction
   magnitude.

**Explicitly not in M1:** authored muscle meshes (stage 3), activation colouring (stage 4), deep muscles, a second
morphology preset (stage 5), organs, labels, picking, a muscle library beyond eight, and any claim of surface accuracy.

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
  in L5; frame cost of the muscle layer below the measured budget.
- **Manual acceptance:** visual review of the eight muscles in three poses against anatomical references, and a
  **renewed §12.4 record** for the new `assetVersion` — the M1 record covered tubes, not these meshes.
- **Risks:** helper bone budget (R3); art time (R2).
- **Effort:** 4 engineering weeks + 6–8 muscle-art weeks.
- **Art/expertise:** yes, substantial.

### Stage 4 — Activation and education panel

- **Objective:** explain participation, honestly.
- **Components:** activation schema + validator, reviewed `PhaseTimeline` per exercise, table for three exercises, colour
  mapping, panel copy, the "not EMG" disclaimer, selection state.
- **Dependencies:** stage 3 (visible muscles to colour).
- **Expected files:** `anatomy/ActivationTable.kt`, `app/src/main/assets/anatomy/activation_v1.json`,
  `app/src/main/assets/anatomy/phases_v1.json`, `app/src/test/.../activation/ActivationDefinitionTest.kt`,
  `ui/components/anatomy/MuscleRolePanel.kt`.
- **User-visible deliverable:** exercise detail shows which muscles work, in which role and phase, with a colour key.
- **Automated acceptance:** every entry has a source; every phase named by an entry exists in the `PhaseTimeline` it
  cites; coverage per exercise; an entry with no timeline is flagged `UNREVIEWED` and cannot be displayed.
- **Manual acceptance:** physiotherapist sign-off on three exercises.
- **Risks:** review bottleneck (R5); copy drifting into physiological claims.
- **Effort:** 2 engineering weeks + 1 review week.
- **Art/expertise:** anatomy review.

### Stage 5 — Morphology v2 (four body families)

- **Objective:** prove one anatomy library serves slim, heavy, muscular and female bodies. This is the first stage that
  ships more than one morphology preset; M1 ships one (§15, M1 item 5).
- **Components:** morph targets with region masks, four presets, `MorphPresetTest`, per-preset device screenshots.
- **Dependencies:** stage 3 meshes (morphs are authored per body).
- **Expected files:** `app/src/main/assets/characters/*/skin.glb` (morph targets), `character/CharacterProfile.kt`,
  `app/src/test/.../character/MorphPresetTest.kt`.
- **User-visible deliverable:** a character selector that changes the body and keeps every exercise, muscle and
  animation.
- **Automated acceptance:** rest invariant per preset; joint rings unmoved; anatomy inside the body within tolerance.
- **Manual acceptance:** all four bodies pass the same visual checklist; muscle layers align on each; and a **renewed
  §12.4 record per preset** (A5), because each preset moves the surface the muscles must sit under.
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

---

## Appendix A — module map

```
app/src/main/java/com/monkfitness/app/
├── animation/        Motion Model (unchanged)           8,904 lines
├── poses/            exercise intent (unchanged)       10,639 lines
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
├── anatomy/phases_v1.json        reviewed phase timelines (stage 4)
├── characters/<id>/              per character: skin, muscles, materials
└── shared/skeleton_v1.glb        skeleton overlay
tools/anatomy-export/             offline helper-bind + asset validation (JVM)
```

## Appendix B — glossary

| Term | Meaning here |
| --- | --- |
| Canonical segment frame | the rest frame of a `Joint` node; +X sagittal, +Y up, +Z lateral, identity at rest |
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
2. Is eight muscles the right M1 subset for the first three exercises, or should M1 be four muscles done well?
3. Should the skeleton overlay be a shared asset across characters (this RFC's assumption) or per character, given
   uniform scale differences?
4. Is any cross-section response ever justified? This RFC ships none in M1 (§7.6). If a reviewer believes a visibly
   shortening muscle must change shape, the burden is a validated per-muscle model with measured references; the
   cylinder law on its own is a plausibility argument, not evidence.
5. Who signs the anatomy table, and how many muscles per review session is realistic?
