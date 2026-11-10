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
three existing exercises, with a shared rig, eight anatomically grounded muscles that follow the movement, and
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
| Registered pose builders | 67 (`AnimationRegistry.kt` — 67 `register(...)` calls) |
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
      → ConstraintSolver.solve(...)       animation/ConstraintSolver.kt:184   [contacts only; no-op otherwise]
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

Measured on the referenced artifact (external site, model-generated page; numbers from the page's own
`__SCENE_STATE__`): 92,610 triangles, 193 draw calls, ~57 fps at DPR 1 in headless Chrome; muscle geometry rebuilt from
scratch each frame costing 4.6 ms; 90 muscle definitions (53 superficial, 37 deep, 10 of them generated intercostals),
180 instances after mirroring, 30.9 k muscle triangles per frame; 33-joint rigid bone rig (82 bone meshes), plus a
separate 65-bone skinned skin and organ meshes.

Its transferable ideas, stripped of its implementation:

1. **Muscles are data, not animation.** Origin/insertion rows, intermediate waypoints, width/thickness, tendon fractions,
   layer, and obstacle wrapping are authored as a table; the mesh is derived.
2. **Length is measured along the solved path**, then used for a *defined* shape response, not an arbitrary pulse.
3. **Roles and phases drive colour**, from a per-exercise table of prime movers, synergists and stabilisers.
4. **One layer system** (skin, superficial, deep, skeleton, organs) with dissolve transitions, where hiding a layer never
   touches the motion.
5. **Labels are projected from anatomy**, so the annotation cannot drift from the body.

What it does not contribute, and this framework deliberately rejects:

- pose functions per exercise with hand-tuned numbers instead of a solver/validator separation;
- no joint limits, no contact model, no self-collision, no published-frame validation;
- balance as a centre-of-mass box test, presented as physical plausibility;
- a fixed 33-joint anatomical rig with per-rib and per-organ segments, which this engine's namespace does not have;
- geometry rebuilt on the CPU every frame for the full library, which is not affordable on a mid-range phone.

### 3.6 Gaps the framework must close

| Gap | Consequence today | Owner in the target |
| --- | --- | --- |
| No anatomical model | nothing knows what a muscle is; no attachment, length or role data exists | Anatomical Model (§7) |
| No muscle geometry | the character shows skin only; the 2D skeleton shows sticks | Anatomical + Character models (§7.5) |
| No activation semantics | exercise pages cannot explain participation | Activation model (§8) |
| No layer system | the Filament path renders one body; no anatomy view | Presentation (§9) |
| No rig/asset validation | a wrong bone map, bind pose or weight set fails visually, late | Validation L2–L6 (§12) |
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

- `animation/` importing `anatomy/`, `character/`, `com.google.android.filament.*`, or `androidx.compose.*`;
- `anatomy/` importing `character/`, Filament, or Android UI;
- `character/` calling IK, constraint solving, or pose mutation;
- any module other than the renderer importing `filament`/`gltfio`.

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
    /** Path in engine world units, sampled at `pointCount` points. */
    val path: FloatArray,
    val pointCount: Int,
    val length: Float,          // along the path, including wrap detours
    val restLength: Float,      // bind-pose length for this body profile
    val tendonLength: Float,    // origin + insertion tendon portions
    val strain: Float           // length / restLength - 1
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
    private val helperBones: Map<String, IntArray>,  // muscleId -> bone indices, origin→insertion order
    private val rest: HelperRestFrames
) {
    fun write(frame: AnatomyFrame, baseWorld: FloatArray, out: FloatArray)
}
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

- it would put mesh-scale data into the engine's carrier, violating the RFC's carrier contract and the engine's
  "no appearance" rule (`docs/RFC_RUNTIME_SKELETON_ARCHITECTURE.md` §5 R11);
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
| **Per-bone proportions** | segment lengths, therefore IK reach and joint placement | `SkeletonDefinition` (motion) | no — a different body plan is a different motion definition, not an appearance |

The PR #385 asset already carries the five regional morphs (`ShoulderWidth`, `ChestVolume`, `WaistWidth`,
`LimbThickness`, `HeadScale`) and four presets (`MorphologyPreset.kt:7-11`). What is missing is the validation that
makes them safe: region masks around joint pivots and a rest-invariant check per preset. Without a mask, a shoulder-width
morph drags the deltoid ring through the acromion and no joint-space rule can see it.

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

### 6.5 What is shared and what is per character

| Shared across all characters | Per character |
| --- | --- |
| 67 pose builders and their metadata | mesh topology and vertex count |
| `SkeletonDefinition` (v1) | materials, textures, hair, clothing |
| `AnatomyDefinition` (attachments, paths, wraps, roles) | morph target set and its weights |
| Activation tables | quality tier and LODs |
| Validation rules and thresholds | anatomy overrides (rare, reviewed) |
| Camera definitions | uniform scale |

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

/** A point pinned to a canonical segment, in metres, segment rest frame (+X sagittal, +Y up, +Z lateral). */
data class Attachment(val segment: Segment, val x: Float, val y: Float, val z: Float)

data class MuscleDefinition(
    val id: String,                       // "rectus_femoris"
    val name: String,                     // "Rectus femoris"
    val layer: MuscleLayer,
    val heads: List<MuscleHead>,          // one per head; biceps = 2, triceps = 3
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
    val segment: Segment,
    val centreMetres: Triple<Float, Float, Float>,
    val radiiMetres: Triple<Float, Float, Float>   // ellipsoid; a capsule is the degenerate case
)
```

A concrete entry, with the schema's intent made explicit:

```json
{
  "id": "rectus_femoris",
  "name": "Rectus femoris",
  "layer": "superficial",
  "heads": [{
    "origin":    { "segment": "PELVIS", "x": 0.050, "y": 0.105, "z": 0.030 },
    "waypoints": [{ "segment": "FEMUR",  "x": 0.055, "y": -0.180, "z": 0.025 }],
    "insertion": { "segment": "PATELLA_REF", "x": 0.000, "y": -0.010, "z": 0.000 }
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

Per muscle, per frame (`AnatomySolver.solve`), pure and allocation-light:

```
for each head:
  1  resolve attachments:  world = jointWorldPosition + R(jointWorldRotation) * (offsetMetres * unitsPerMetre)
  2  build the control polyline: origin, waypoints..., insertion
  3  resample the polyline at N points (N ∝ path length / 0.01 m, clamped 8..48)
  4  for each sample, for each wrap surface in declaration order:
        if the sample is inside the ellipsoid: push it to the surface along the ellipsoid normal
        (one pass per surface, two passes total, so nested wraps settle)
  5  smooth the path (Catmull-Rom, 0.25 blend) to remove the faceting a projection leaves
  6  length = Σ |p[i] − p[i−1]|          // along the solved path, NOT the straight chord
  7  tendonLength = originFraction * length + insertionFraction * length
  8  strain = length / restLength − 1
```

Design notes:

- **Length is arc length along the solved path.** A straight-line measure would report shortening whenever a muscle
  wraps a joint, which is the opposite of the truth. This is the single most important correctness rule in the model.
- **Two wrap passes, fixed order.** Order is declared per muscle so it is deterministic and reviewable; it is not a
  physical contact solve, and the doc says so (§11).
- **`restLength` comes from the same solver at the bind stance**, not from the definition's numbers, so the rest state
  and the runtime can never disagree (this is also what the exporter bakes; §10.4).
- **No joint motion is written.** The solver reads `pose` and writes only `AnatomyFrame`.

### 7.5 Geometry strategies

| Strategy | Anatomy quality | CPU per frame | Filament fit | Art cost | Verdict |
| --- | --- | --- | --- | --- | --- |
| **A. Procedural tube/sheet from the path** (AA-3D's approach) | low–medium: correct length, generic cross-section, no surface detail | high: full rebuild of every muscle every frame (AA-3D: 30.9 k tris, 4.6 ms desktop) | easy: one buffer, one dynamic mesh per layer | none | **phase A / debug / tier `reduced`** |
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

- 4 bones per muscle: `origin`, `belly_proximal`, `belly_distal`, `insertion`, placed at 0%, 30%, 70%, 100% of the rest
  path.
- Each helper's world transform at bind = the frame at its rest path point (origin at the point, +Y along the local
  tangent, +Z from the surface normal reference). The muscle mesh is weighted to these four bones by proximity along
  the path, with ≤ 4 influences per vertex (glTF standard).
- Per frame, `MuscleLayerSkinner` writes each helper as: translation = path point at the same fractional arc length;
  rotation = frame built from the local tangent and a stable up reference; scale = thickness response (§7.6).
- Because the helper's fractional position is preserved, the mesh's *shape* is authored, while its *length* is whatever
  the path solver measured. That is the whole trick, and it is why the mesh cannot drift from the skeleton.

### 7.6 Deformation model

Only two things move a muscle's geometry, and both are defined:

1. **Path following.** The helper bones move to the solved path. This is the primary deformation and it is exact within
   the path solver's approximation.
2. **Thickness response to length.** Modelled as an isovolumetric approximation of a uniform cylinder:

   ```
   r(L) = clamp( r0 * sqrt(L0 / L), 0.75 * r0, 1.25 * r0 )
   ```

   applied to the two belly helpers only (cross-section scale in X/Z), with tendons rigid and no scale on origin and
   insertion. Clamps bound the visible effect; outside them the approximation is simply wrong.

**What this model does not do, stated plainly:** real muscle is pinnate, anisotropic, wrapped in fascia, changes shape
with joint angle in ways a cylinder cannot express, and its cross-section is not constant along its length. There is no
muscle "firing" animation, no fibre-level deformation, and no claim that a thicker belly means a stronger contraction.
The thickness response exists so that a visibly shortening muscle does not stay visually identical; it is an
approximation with a stated formula, not a simulation.

**Forbidden, per §11:** any bulge keyed to activation, any geometry change without a length measurement behind it, any
per-muscle timeline, any scale not derived from `MuscleState`.

### 7.7 Layers, and what is deferred

| Layer | Contents | v1 | Notes |
| --- | --- | --- | --- |
| superficial muscles | 8 muscles for M1, ~35 for v2 | yes | quads, hamstrings, glutes, delts, pecs, lats, biceps, triceps, calves, erectors, abs |
| deep muscles | 8–12 for v2 | yes, after M1 | psoas, iliacus, rotator cuff, transverse abdominis, multifidus |
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

---

## 8. Muscle activation semantics

### 8.1 What the model is

An **authored educational classification**, at the granularity of "which muscles participate, in what role, in which
phase of which exercise". It is not EMG, not a physiological simulation, and not a claim of measured intensity.

```
Exercise → Muscle → Role → MovementPhase → Emphasis
```

- **Role**: `PRIME_MOVER`, `SYNERGIST`, `STABILISER`, `ANTAGONIST` (the last for the eccentric/control case).
- **MovementPhase**: `CONCENTRIC`, `ECCENTRIC`, `ISOMETRIC` — derived from the exercise's own phase, never invented per
  muscle frame.
- **Emphasis**: an ordinal tier (`PRIMARY`, `SECONDARY`, `SUPPORTING`), not a percentage. A numeric 0–1 value may exist
  internally for shading, but it is a *display* intensity derived from the tier and the phase, and the UI must not label
  it as activation percentage.

### 8.2 Data

```json
{
  "exercise": "back_squat",
  "source": "anatomy-review-2026-11",           // provenance, required
  "muscles": [
    { "id": "gluteus_maximus",  "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["CONCENTRIC", "ECCENTRIC"] },
    { "id": "vastus_lateralis", "role": "PRIME_MOVER", "emphasis": "PRIMARY",   "phases": ["CONCENTRIC", "ECCENTRIC"] },
    { "id": "biceps_femoris",   "role": "SYNERGIST",   "emphasis": "SECONDARY", "phases": ["CONCENTRIC"] },
    { "id": "erector_spinae",   "role": "STABILISER",  "emphasis": "PRIMARY",   "phases": ["ISOMETRIC"] }
  ]
}
```

Rules:

- **Every entry carries a source.** No source, no entry — the validator rejects it (§8.3).
- **Roles are per exercise, not per muscle.** The same muscle may be a prime mover in one exercise and a stabiliser in
  another; that is the educational content.
- **Phases must exist in the exercise's motion.** A table naming `ISOMETRIC` for a movement with no hold phase is a data
  error the validator can catch from the pose metadata (`LoopMode.HOLD`, `durationSeconds`).
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

The activation table is validated by `ActivationValidator` (schema, source, phase existence, exercise coverage). It can
fail while every motion rule passes, and vice versa. Neither report aggregates the other; the UI may show both, but a
green motion report never implies reviewed anatomy.

---

## 9. Anatomical visualization layers

### 9.1 Layer model

```kotlin
enum class CharacterLayer { SKIN, MUSCLES_SUPERFICIAL, MUSCLES_DEEP, SKELETON, SELECTED }
```

| Layer | Asset | Loaded when | Cost when visible |
| --- | --- | --- | --- |
| `SKIN` | `skin.glb` | character attaches | 1 skinned mesh, 1 draw call per material |
| `MUSCLES_SUPERFICIAL` | `muscles_sup.glb` | first shown, then cached | 1–4 skinned meshes (regional split if bone budget requires) |
| `MUSCLES_DEEP` | `muscles_deep.glb` | first shown | same, fewer meshes |
| `SKELETON` | `shared/skeleton_v1.glb` | first shown | rigid bone meshes, one draw call per material |
| `SELECTED` | derived | always | tint + panel; no geometry |

Rules:

- **Visibility is a renderable/material flag, never a rebuild.** Toggling a layer must not re-create the engine, reload
  the pose, or touch the pipeline.
- **A hidden layer is not loaded.** `docs/2026-09-14-reference-humanoid.md` (global constraints) already states that a
  disabled layer must not load or create its geometry; it becomes the general rule.
- **Layers compose.** Skin transparent at 15–30% over muscles, or hidden entirely; muscles ghosted over skeleton; the
  skeleton layer draws *inside* the body because it is a rigid overlay, not a second skin.
- **One character, one skeleton.** All layers share one bone buffer; a layer may add renderables to it but never a
  second skeleton.

### 9.2 Essential for the first useful version

1. Skin (the character).
2. Superficial muscles, the eight M1 muscles.
3. Skeleton overlay.

`SELECTED` exists from the start as state (highlight + panel) but its first content is the muscle name and role, not an
interactive dissection. `MUSCLES_DEEP` follows in v2; organs, tendons, labels and attachment markers are explicitly
later.

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
| muscles superficial | muscle meshes, helper bones, tendons as material regions | ~1.2–1.8 k tris per muscle at v2 (~35 muscles); M1's eight fit in 10–14 k | 4 helpers per muscle, ≤ 4 influences |
| muscles deep | muscle meshes, helper bones | ~0.8–1.2 k tris per muscle at v2 | same |
| skeleton overlay | rigid bone meshes | 8–12 k / 4–6 k | parented to mapped bones; no skinning |

Budgets are *ceilings to validate against*, not promises: the first asset that exceeds one fails the asset validator
with a report, and the budget is revised in this document rather than silently exceeded.

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
| Anatomical attachment points | **requires expert validation** | a plausible-looking point can still be wrong | L3 + anatomist sign-off |
| Path + ellipsoid wrapping | approximation | no fascia, no fat, no bone sliding, 2 fixed passes | L4 + visual review |
| Arc-length measurement along the path | established technique | depends on path sampling density | L4 |
| Isovolumetric thickness (`r ∝ L^-½`, clamped) | approximation | uniform cylinder; real muscle is pinnate, anisotropic | L4 (bounds) + visual |
| Morph-target morphology | visual approximation | surface only; joints can degrade without a joint rule | L6 |
| Corrective shapes | visual approximation | authored per rig; not anatomy | L6 + visual |
| Activation colouring | **educational model, not physiology** | no EMG, no measured intensity, no per-frame claim | §8 schema + expert review |
| Organ motion (future) | research required | no defensible mechanical driver at this stage | not planned |
| Skin/muscle self-intersection | unverified | no runtime collision; mesh checks are offline only | L5 |

**Hard rules.**

1. No muscle geometry moves for any reason other than the solved path and the defined thickness response.
2. No numeric activation value is displayed without a cited source; tiers are ordinal.
3. No balance, contact or ground model is introduced in the presentation path. The character consumes the published
   pose; it does not re-solve it.
4. No claim of anatomical correctness is derived from joint-space validation. L1 passing says nothing about a surface.
5. No mechanism may be described in UI copy as "simulated", "measured" or "real-time physiology".

---

## 12. Validation architecture

Seven levels, each with one owner, one report and an explicit automation status. They never merge into one score.

| Level | What it checks | Automated | Runs | Failure means |
| --- | --- | --- | --- | --- |
| **L1 Motion** | 18 existing rules over joint-space geometry (`ExerciseValidator.kt:47-90`) | yes, unchanged | JVM tests + debug tooling | the pose/engine is wrong; block |
| **L2 Rig** | bone coverage, rest invariant, inverse binds, side/handedness, unit scale, bone budget | yes | JVM (asset parse) + offline | the asset or map is wrong; block |
| **L3 Attachments** | every attachment's joint exists; offset inside a segment envelope; L/R mirror; head count; origin ≠ insertion; span ≥ 2 segments | yes + expert sign-off | JVM + offline tool | the anatomy data is wrong; block |
| **L4 Path** | arc length continuity, monotone strain within a phase, wrap satisfaction (no sample inside its obstacle), no path self-crossing, rest-length tolerance vs measured reference | yes, with fixtures | JVM + visual overlay | the solver or the data is wrong; block |
| **L5 Mesh** | self-intersection, degenerate triangles, weight sums, volume sanity per muscle, helper-bone bind match | partly (tool) | offline, artist review | the mesh is wrong; block release, not build |
| **L6 Morphology** | per-preset rest invariant, joint-ring preservation under morphs, delta bounds, anatomy still inside the body | yes | JVM + offline | a preset breaks the rig; block the preset |
| **L7 Visual** | anatomical plausibility: silhouette, attachment placement, wrap behaviour, tendon regions | no — human | review checklist against references | the model looks wrong; open an anatomy/art issue |

### 12.1 What is genuinely automatable

- L1 (already), L2 (fully), L3's mechanical half (existence, envelope, mirror), L4's numeric half, L6 (fully), and L5's
  geometric half. These are the parts a CI job can gate.
- **Not automatable:** whether an attachment is the *right* anatomical point; whether a wrap produces a believable
  contour; whether a muscle mesh reads as that muscle. Any claim otherwise is false confidence, and this document
  refuses to design a "score" that hides it.

### 12.2 New test inventory (names, not implementations)

| Test | Level | Asserts |
| --- | --- | --- |
| `RigAssetContractTest` | L2 | every required bone present, bind == rest, inverse binds finite, ≤ 4 influences |
| `PoseRetargeterRestInvariantTest` | L2 | neutral pose → identity on every bone (exists in PR #385; extended to v2 rig) |
| `BoneMapCoverageTest` | L2 | 23 mapped joints resolve; unmapped joints are declared, not accidental |
| `RigSideTest` | L2 | `Left*` bones on the same side as anatomy side A in the rest pose |
| `AnatomyDefinitionTest` | L3 | ids unique; attachments resolve; envelope bounds; L/R mirror; heads consistent |
| `MusclePathGeometryTest` | L4 | arc length ≥ chord; wrap keeps samples outside the ellipsoid; deterministic across runs |
| `MuscleLengthTrackingTest` | L4 | quadriceps shorten monotonically through a squat's concentric phase; a stabiliser's length varies less than a prime mover's across the same phase |
| `MuscleRestLengthTest` | L4 | solved rest length matches the calibrated reference within tolerance |
| `MorphPresetTest` | L6 | rest invariant per preset; joint rings unmoved beyond tolerance |
| `LayerToggleTest` | presentation | toggling a layer changes visibility only; no engine or pose rebuild |
| `CharacterDependencyTest` | architecture | `animation/` has no anatomy/character/Filament imports; `anatomy/` has no character/Android imports |
| `AssetBudgetTest` | L5 | triangle/texture/bone budgets per tier from the manifest |

All except `LayerToggleTest` are JVM tests (the character session itself requires a device). `LayerToggleTest` splits:
a pure state test on the controller in JVM, and a device smoke test in the existing diagnostics screen.

### 12.3 Device-only evidence (not claimed here)

Frame time, jank, thermal drift, surface recreation and context loss cannot be validated in this environment. They are
gates on stage 1 and are recorded in `docs/CHARACTER_DEVICE_VALIDATION.md` (PR #385) with backend, FPS, p95 frame time
and defects per device.

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

**Unresolved, with the smallest resolving experiment:**

1. **Bone budget per renderable** — load the 8-muscle layer with 32 helpers on one device; if it renders, double until
   failure. Resolves the split threshold.
2. **Strategy C authoring effort** — one muscle (rectus femoris) taken from attachment data to a path-rigged mesh end to
   end; measure artist hours. Decides whether the M1 subset is 8 or 4 muscles.
3. **Whether `anatomicalForward`'s semantics should be renamed** — not a blocker for anatomy (which uses segment rest
   frames), but it is a trap for every new author. A docs-only rename proposal belongs in a separate change.
4. **Deep-muscle value** — user research question, not an engineering one: does the education panel need deep muscles
   before organs?

---

## 14. Decisions

Each decision gives a recommendation, the reasoning, and — where the repository cannot settle it — the smallest
experiment.

**D1. One shared visual rig or multiple compatible rigs?**
*One rig (`humanoid_v2`), versioned.* Multiple rigs are allowed only if they preserve joint names, hierarchy, rest pose
and bind pose, which in practice means they are the same rig with different meshes. The whole value proposition — one
pose library, one anatomy library — collapses the moment two rigs diverge, because `BoneMap` would have to fork and the
rest invariant would have to be re-proved per rig. A second rig is a second product, not a second character.

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
*Morph targets for surface; uniform scale for size; per-bone proportions excluded from appearance.* Bone lengths are
baked into inverse binds and drive IK reach; changing them per character changes the motion solution, not just the
picture. If a future product wants per-character proportions, it needs a per-character `SkeletonDefinition` and a
re-validation of every pose — a motion project, not an appearance setting.

**D5. How should muscle geometry react to length changes?**
*Only through the path, plus the clamped isovolumetric thickness response on the belly.* This is a defined model with a
stated limitation, measurable (`r/r0` vs `L/L0`) and bounded. Activation never moves geometry; a muscle that is
"working" but not changing length looks exactly like a muscle that is not working, and the HUD explains why.

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
*One GLB per layer per character; anatomy and activation as versioned JSON shared across characters; lazy load, unload
when hidden.* The layer boundary is the asset boundary because that is what makes toggling free of engine work and what
keeps the muscle library out of the APK's always-resident set.

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
| **M1** | **one character, 3+ exercises, 8 muscles, 3 layers** | — | — | **demo + all gates** |
| 3 | Path-rigged authored muscle meshes | 4 | 6–8 muscle art | L5 + visual review |
| 4 | Activation + education panel (3 exercises) | 2 | 1 review week | §8 validator + sign-off |
| 5 | Morphology v2: four body families | 3 | 6 art | L6 green per preset |
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
  `AnatomyFrame`, `AnatomyDefinitionTest`, `MusclePathGeometryTest`, `MuscleLengthTrackingTest`, procedural tube
  generator.
- **Muscles (8):** rectus femoris, vastus lateralis, biceps femoris, gluteus maximus, erector spinae, deltoid anterior,
  pectoralis major, gastrocnemius medial.
- **Dependencies:** stage 1 (rest stance + definition), anatomy calibration session.
- **Expected files:** `app/src/main/java/com/monkfitness/app/anatomy/*.kt`,
  `app/src/main/assets/anatomy/anatomy_v1.json`, `app/src/test/.../anatomy/*`.
- **User-visible deliverable:** a dev-only muscle layer showing red tubes following the squat and push-up, with a length
  readout per muscle.
- **Automated acceptance:** arc length ≥ chord on every frame; no samples inside wrap ellipsoids; the quadriceps
  shorten monotonically through a squat's concentric phase; rest lengths within tolerance.
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
3. Layers: skin, superficial muscles (the 8), skeleton overlay; toggling does not rebuild the engine.
4. Muscles follow the movement within the path model and report lengths; no independent muscle animation exists.
5. A second morphology preset changes the body without changing any pose, muscle definition or animation id.
6. L1 and L2 green in CI; L3/L4 green on the eight-muscle fixture set; device numbers recorded for the character alone.

**Explicitly not in M1:** authored muscle meshes (stage 3), activation colouring (stage 4), deep muscles, organs,
labels, picking, a muscle library beyond eight, and any claim of surface accuracy.

### Stage 3 — Path-rigged authored muscle meshes

- **Objective:** replace tubes with authored meshes without changing the anatomy model.
- **Components:** helper-bone export tool (JVM, reuses `anatomy`), `MuscleLayerSkinner`, per-muscle GLB sections, asset
  validation for helper binds, L5 tooling.
- **Dependencies:** M1's path contract; muscle art pipeline.
- **Expected files:** `tools/anatomy-export/`, `character/MuscleLayerSkinner.kt`,
  `app/src/main/assets/characters/*/muscles_sup.glb`, `app/src/test/.../HelperBindTest.kt`.
- **User-visible deliverable:** the same eight muscles, now with surface anatomy, still following the path exactly.
- **Automated acceptance:** helper bind positions equal the solver's rest path within 1e-3; thickness response within
  the clamp bounds; frame cost of the muscle layer below the measured budget.
- **Manual acceptance:** visual review of the eight muscles in three poses against anatomical references.
- **Risks:** helper bone budget (R3); art time (R2).
- **Effort:** 4 engineering weeks + 6–8 muscle-art weeks.
- **Art/expertise:** yes, substantial.

### Stage 4 — Activation and education panel

- **Objective:** explain participation, honestly.
- **Components:** activation schema + validator, table for three exercises, colour mapping, panel copy, the "not EMG"
  disclaimer, selection state.
- **Dependencies:** stage 3 (visible muscles to colour).
- **Expected files:** `anatomy/ActivationTable.kt`, `app/src/main/assets/anatomy/activation_v1.json`,
  `app/src/test/.../activation/ActivationDefinitionTest.kt`, `ui/components/anatomy/MuscleRolePanel.kt`.
- **User-visible deliverable:** exercise detail shows which muscles work, in which role and phase, with a colour key.
- **Automated acceptance:** every entry has a source; phases exist in the motion; coverage per exercise.
- **Manual acceptance:** physiotherapist sign-off on three exercises.
- **Risks:** review bottleneck (R5); copy drifting into physiological claims.
- **Effort:** 2 engineering weeks + 1 review week.
- **Art/expertise:** anatomy review.

### Stage 5 — Morphology v2 (four body families)

- **Objective:** prove one anatomy library serves slim, heavy, muscular and female bodies.
- **Components:** morph targets with region masks, four presets, `MorphPresetTest`, per-preset device screenshots.
- **Dependencies:** stage 3 meshes (morphs are authored per body).
- **Expected files:** `app/src/main/assets/characters/*/skin.glb` (morph targets), `character/CharacterProfile.kt`,
  `app/src/test/.../character/MorphPresetTest.kt`.
- **User-visible deliverable:** a character selector that changes the body and keeps every exercise, muscle and
  animation.
- **Automated acceptance:** rest invariant per preset; joint rings unmoved; anatomy inside the body within tolerance.
- **Manual acceptance:** all four bodies pass the same visual checklist; muscle layers align on each.
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
4. Is the isovolumetric thickness response worth shipping at all, or should v1 show length-driven path motion with no
   thickness change and no risk of being read as a contraction claim?
5. Who signs the anatomy table, and how many muscles per review session is realistic?
