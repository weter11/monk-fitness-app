# Monk Human Framework — human character and anatomical presentation

**Status:** architecture RFC, proposed for review. Nothing in this document is implemented by this change.
**Base for this document:** `main` @ `16e0487` (`16e04874096989300a96f5c18416661337ed1eb4`).
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

**How the baseline was verified.** The repository was inspected at `main` (`16e0487`), not from earlier reports. The
Filament work was read from the two open PR heads (`fd549bd` for #384, `bcb3658` for #385) without changing either PR's
state or merging anything. The humanoid test asset was parsed directly (`app/src/main/assets/characters/humanoid_v1.glb`
on PR #385) to confirm joint count, triangle count and morph targets rather than trusting the generator's printout.

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
| Base SHA | `16e04874096989300a96f5c18416661337ed1eb4` ("Merge pull request #390 from weter11/refactor/motion-core-ui-free") |
| Working tree at branch creation | clean (`git status --porcelain` empty); this document is the only change on the branch |
| Documentation branch | `docs/human-framework`, created from `origin/main` |
| Engine sources on `main` | `app/src/main/java/com/monkfitness/app/animation` (8,144 lines in 47 files), `.../poses` (10,642 lines in 79 files) |
| Registered pose builders | 66 (`AnimationRegistry.kt` — 66 `register(...)` call sites; the 67th textual match is the `fun register` declaration) |
| Pose source files | 79 |
| Joint identifiers | 33 (`animation/Joint.kt:3`) |
| CI verifier | `.github/workflows/android.yml` — `:app:assembleDebug` then `:app:testDebugUnitTest` |

**Counting definition** (used in this table and in Appendix A, so the two cannot drift): a line count is every line of
every `.kt` file under the package and its subdirectories, blank lines and comments included, summed from
`git show <sha>:<path> | wc -l` over the file list; a file count is the number of `.kt` files in that tree. Both
figures above are that measurement at `16e0487`.

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
- `CharacterFeature` / `ActiveCharacter` (`character/CharacterFeature.kt:9,18`): the flag (`BuildConfig
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
| Triangles | 30,722 (30,720 body + 2 `Ground`) |
| Morph targets | 5: `ShoulderWidth`, `ChestVolume`, `WaistWidth`, `LimbThickness`, `HeadScale` |
| Materials / textures / animations | 2 materials, 0 images, 0 animation clips |
| Bind pose | arms at the sides, matching the engine rest stance (required by rest-delta retarget — `docs/CHARACTER_FRAMEWORK.md` §3) |

`humanoid_spike.glb` (PR #384) and `humanoid_v1.glb` (PR #385) are **byte-identical**: 2,060,808 bytes, sha256
`ef643438b761de84504d45c87511c7d2ad5109996fa56ab77107fee1cde0e539`. A defect found in one is a defect in the other, and
the `Ground` node is in both — the two PRs ship one asset under two names.

Filament is pinned to **1.75.1** (`gradle/libs.versions.toml:15`): 1.76+ requires `compileSdk 37` and the app is on 34.
The documented native payload is 7.4 MB arm64 and a debug APK with the asset is 18.8 MB (`docs/CHARACTER_FRAMEWORK.md` §1).

**One device run, informational.** The #384 spike was run on the owner's phone — Adreno 735, OpenGL ES 3.2, feature
level 2 — and held ~59 fps on the skinned 30,720-triangle asset. That is one run of one asset on one device; it is
recorded because it is the only device evidence the programme has, and it is not a budget, a floor for the reduced tier,
or evidence about a mid-range device. By the owner's decision formal FPS and thermal gating is **deferred** — result
first, optimisation later — so no stage gate depends on these numbers (§12.3). The three launch defects the run exposed
are listed as known traps in §13.

### 3.5 What the AA-3D viewer contributes — and what it does not

**Provenance, stated because it limits what these numbers can do.** The figures below are quoted from an external
reference implementation's own page state, as reported in the review that produced this section. This document contains
no URL for the artifact, so the numbers are **not reproducible from this repository and are not verified here**. They are
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

    /** PathStatus ordinal. `clear()` writes INVALID to every slot; a status is never left over from the last frame. */
    val statuses = IntArray(headCount)
    val reasonCodes = IntArray(headCount)        // PathReason ordinal; NO_REASON only where status is VALID
    val failingSegments = IntArray(headCount)    // segment index, or NO_INDEX
    val failingObstacles = IntArray(headCount)   // index into the definition's obstacle list, or NO_INDEX
    val pointCounts = IntArray(headCount)

    /** Path points, engine world units. Stride: `head * 3 * pointCapacity + point * 3 + {x, y, z}`. */
    val paths = FloatArray(headCount * 3 * pointCapacity)
    val landmarks = FloatArray(landmarkCount * 3)

    /** One view per head, built once at construction; reading it allocates nothing. */
    val muscleStates: List<MuscleState>

    /** Start of every solve: statuses INVALID, reasons NO_REASON, counts 0, indices NO_INDEX, lengths cleared. */
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
    val pointCount: Int get() = frame.pointCounts[slot]
    val modelledPathLength: Float get() = frame.modelledPathLengths[slot]
    val restLength: Float get() = frame.restLengths[slot]
    val visualTendonPortionLength: Float get() = frame.visualTendonPortionLengths[slot]
    val relativePathLengthChange: Float get() = frame.relativePathLengthChanges[slot]
    val pathStatus: PathStatus get() = PathStatus.entries[frame.statuses[slot]]
    val reason: PathReason get() = PathReason.entries[frame.reasonCodes[slot]]
    val failingSegment: Int get() = frame.failingSegments[slot]
    val failingObstacle: Int get() = frame.failingObstacles[slot]

    /** Reads `frame.paths` at this head's offset; allocates nothing. */
    fun pathPoint(index: Int, out: Vector3)

    /** Zero-allocation read-only view over this head's whole path; valid until the next solve. */
    val path: PathView
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

**A status is never inherited.** `clear()` sets every slot `INVALID`, `NO_REASON`, `NO_INDEX`, `pointCount = 0` before
the solve touches anything, so a head the solver never reaches — a definition error, an early return, a reused buffer —
reads `INVALID` rather than the last frame's `VALID`. Reason precedence is fixed and deterministic, and it is stated once
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
| Joint names | `Hips, Spine, Chest, Neck, Head, {Left,Right}Shoulder, {Left,Right}Scapula, {Left,Right}Arm, {Left,Right}ForeArm, {Left,Right}Hand, {Left,Right}UpLeg, {Left,Right}Leg, {Left,Right}Foot, {Left,Right}ToeBase` plus visual-only bones | the resolver matches normalized names (`MonkJointResolver.kt:12-34`, PR #385). Rigs are **first-party and built to `SkeletonDefinition`**: the Mixamo-compatible naming is a convention that keeps the map readable and reviewable, not a promise that a third-party rig can be dropped in — its bone lengths, bind pose and joint placement are calibrated to nothing in this engine (§6.6 A1/A2) |
| Hierarchy | the 23 mapped bones keep their parent chain; scapula sits between clavicle and arm | the engine's girdle DOF (`CHEST → CLAVICLE → SCAPULA → SHOULDER`, `SkeletonFactory.kt:71-80`) only reaches the body if the rig has a scapula bone |
| Rest pose | arms at the sides, matching the engine's rest stance; **not** a T-pose | rest-delta retarget multiplies the source delta onto the target rest (`PoseRetargeter.kt:58-68`); a T-pose bind leaves the figure in T-pose at every frame 0 |
| Bind pose | identical to rest pose | the rest invariant test proves `identity` on every bone at neutral (`PoseRetargeterTest`, PR #385) |
| Units | asset metres; engine units converted only for the pelvis translation and the uniform scale | `PoseRetargeter.kt:70-83`; offsets must stay in asset units because inverse binds were authored against them |
| Scale | one uniform scale per character; no per-bone scaling for appearance | non-uniform bone scale distorts joint volume and is not a morphology tool |
| Fingers | visual-only, 2 segments per finger or a single segment in tier `reduced` | the engine has no finger kinematics beyond `HAND_A`; the rig may add them as static/derived |

`humanoid_v2` target bone counts: **23 mapped bones + up to 30 visual-only bones (fingers, jaw, eyes, twist) + muscle
helper bones** (§7.5). The 30 is a budget for the parts the rig may add: 20 finger segments (5 fingers × 2 segments ×
2 hands), a jaw, two eyes and up to six twist bones (upper arm, forearm and thigh per side) reach 29 — visual only, in
the sense that the engine has no joint for any of them. The current
PR #385 asset has 32 joints, 5 single-segment fingers per hand, no scapula, no twist bones. The scapula addition is the
only rig change that materially affects anatomy quality.

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

**Multiplication order, bases and bind transforms, stated once** (all of §6.2):

```
R_target_world(bone) = B · R_engine_world(joint) · B⁻¹ · R_target_rest_world(bone)
R_local(bone)        = R_target_world(parent)⁻¹ · R_target_world(bone)
```

`B` is the source→target basis correction (`BoneMap.basis`), `R_engine_world` the **published** world rotation of the
engine joint, `R_target_rest_world` the bone's rest world rotation from the asset's bind pose. Each of the three
conventions is a defect class when omitted: `B` is applied on the left **and** its conjugate on the right (a one-sided
`B` rotates the delta about the wrong axis); the rest rotation is post-multiplied in target space (pre-multiplying
applies it in source space); and the local rotation is `parent⁻¹ · child` in that order (the reverse differs for every
non-commuting pair, which is most of them). `NonCommutingSplitTest` (L2) drives a parent and a child about different
axes and asserts the composed world rotation, so a reversed order or a dropped conjugate fails it.

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
| `HAND_A/P` | `Left/RightHand` | `PALM/KNUCKLES/FINGERTIPS` map to visual finger bones or stay unmapped |
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
mapped ancestor, so it rides its parent and no rotation of its own is invented. Twist bones get a **named twist policy**
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
model believes nothing moved. The check is L6: **joint landmark positions are unchanged under every morph, within
`ε_morph` (proposed, fixed at stage 1)**, measured on the same landmarks `RigLandmarkAlignmentTest` uses, so a morph that
shifts a joint fails the preset instead of surfacing as a visual surprise.

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
    "weights": { "shoulderWidth": 1.0, "chestVolume": 0.7, "waistWidth": -0.35, "limbThickness": 0.9, "headScale": 0.05 }
  },
  "materials": { "skin": "skin_03", "roughness": 0.55 },
  "heightMetres": 1.80,
  "quality": "standard",
  "anatomyOverrides": []
}
```

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
extreme. The profile therefore **declares `min`/`max` per named morph**, checked by `MorphPresetTest` at L6; the numbers
above are the asset's authored envelope, not a guess. Nothing in the asset stops a weight of 3.0 from rendering — this
document refuses to ship a weight the asset was not authored for, and a name with no declared range is a profile error
because it cannot be validated.

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
twice, two answers. The cause is a name, not a rounding error: `HEAD_POS` is the head **centre** (radius 18 units,
§3.3), so the denominator is head-centre-to-floor while `restHeight()` is crown-to-floor, and the field silently means
both. **A field cannot be both a crown height and a head-centre height.**

The contract is a definition: `heightMetres` names its pair — **crown to floor**, the asset's `HeadTop_End` to the
lowest `ToeBase` — and `unitsPerMetre` is derived from the *same* pair on the engine side, `HEAD_POS.y + headRadius −
min(toe).y` = 379.50 units at the rest stance, giving 379.50 / 1.80 = **210.83** u/m. A `CharacterCalibration` that
cannot reproduce `unitsPerMetre` from the declared pair within a stated tolerance (proposed 1 % on the span) **fails
the asset at L2** instead of rendering at a scale nobody chose. Two estimates now exist — 247.37 from the pelvis pair and
210.83 from the crown pair, the first 17.3 % above the second — and the difference is a real disagreement about the
engine's proportions, not a bug in either arithmetic: at 247.37 u/m the engine's rest stance is 1.461 m from head centre to floor, which is short
for a 1.75–1.80 m adult. Which pair the project adopts is §13 question 12. Until it is settled, every residual in this
document that depends on the constant says which one it used.

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
- **The visual rig is posed by rest-delta retarget.** Rotations come from the engine; only the pelvis translation is
  converted, and the rig's bone offsets stay in asset units (`PoseRetargeter.kt:58-83`, PR #385).

Nothing in either rule forces the rig's bone *heads* to sit where the engine's joints are. They coincide only if the asset
is calibrated to the engine skeleton, and they stay coincident only while the segment lengths agree. That is the
alignment invariant, and it belongs to the Character model — the owner of the asset — not to anatomy.

**A1 — bind-stance landmark coincidence.** For every mapped joint `j`, at the bind stance, the rig bone head converted to
engine space equals the engine joint position within `τ_bind` = **0.5 mm**, and the rig bone's rest orientation equals the
engine rest orientation within `τ_rot` = **0.5°** (both unchanged, fixed at stage 1). Checked by
`RigLandmarkAlignmentTest` (L2).

**The shipped asset does not pass A1. That is a measured baseline, not a pass.** At 247.37 u/m the PR #385 asset's
mapped bone heads sit at these distances from their engine joints at the bind stance:

| Joint (asset bone) | Residual | Joint (asset bone) | Residual |
| --- | --- | --- | --- |
| `NECK_END` (`Neck`) | +7.9 mm | `KNEE_F` (`Leg`) | +6.7 mm |
| `HEAD_POS` (`Head`) | −19.4 mm | `ANKLE_F` (`Foot`) | +39.3 mm |
| `HIP_F` (`UpLeg`) | +30.0 mm | `TOE_F` (`ToeBase`) | +89.3 mm |

Signs are as recorded (A1 itself uses the distance). `τ_bind` is 0.5 mm, so the worst mapped joint is 179× the
tolerance, and the residual is linear in `unitsPerMetre` (§7.2) — the table is quoted at 247.37 and has to be
re-measured if the constant changes. **Stage 1's deliverable is a rig that passes A1** (§15); until that rig exists,
`RigLandmarkAlignmentTest` is expected to be red on `humanoid_v1`, and A1 is not weakened to make the current asset
green.

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

A2 is therefore a **measurement**: for each mapped joint, the distance between the rig bone head (converted to engine
space) and the engine joint, evaluated on a **named pose set** — the three poses `RigAlignmentResidualTest` runs, squat
bottom, push-up bottom and overhead reach — and reported per joint and per pose against `τ_residual` (proposed 5 mm at
the most distal mapped joint). Its coverage limits are part of the report: three of 66 pose builders is not a statement
about the other 63, and no pose outside the set is covered. A residual above `τ_residual` on any pose in the set fails
the asset, not the anatomy.

The equal-length/different-direction pair is a **control case** in `RigAlignmentResidualTest`: an implementation that
reports a zero residual for it is still evaluating the withdrawn formula rather than measuring.

**A1 is per joint at bind; A2 is per joint per pose.** Every mapped head can sit inside 0.5 mm of its joint at bind
while a posed frame exceeds 5 mm at the hand or the toe, because A1 is measured at one stance and a rotation error at an
intermediate joint moves every descendant. The converse holds too: a chain can close within 5 mm at its distal joint in
all three poses while individual heads sit outside the 0.5 mm bind bound. Neither measurement substitutes for the other,
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
representable on this rig (§6.1). Surface plausibility remains the human review's job (§12.4, A2–A3).

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
- **Rest frames are identity; a posed frame is derived from positions.** The factory creates every node with identity
  rotation (`SkeletonFactory.kt:60-101`), so at the rest stance each segment's local frame equals the world frame and an
  attachment offset can be authored in one frame per segment: `+X` sagittal, `+Y` up, `+Z` lateral. In a posed frame the
  segment frame must be **derived from published positions**, because the published rotation does not describe the
  segment. Measured at `16e0487`:

  - `IkStage.kt:131-134` writes `middle.localPosition` and `end.localPosition` for a solved limb and no local rotation;
  - `SkeletonNode.kt:63-67` builds each joint's world rotation by concatenating local rotations, and `:75-77` publishes
    it, so a limb's middle joint carries its parent's world rotation, not its own segment direction;
  - `AirSquatPose` at progress 0.5: `KNEE_F` local rotation 0.00° and published 12.89° — the pelvis's world rotation —
    while the thigh–shank bend is 106.53°; `StandardPushUpPose` at 0.5: `ELBOW_A` local 0.00° and published 90.00° — the
    chest's world rotation — while the arm bend is 133.82°.

  Reading the published rotation as the segment's orientation mis-places an attachment by 26.9–56.4 mm at a 30 mm offset
  over the four limb segments and four poses measured. The engine contract is **not** changed by this document; the RFC
  contract is (§7.4 step 1).
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
  | `HEAD_POS` | head **centre**, head radius 18 units | `SkeletonStyle` (§3.3) | **not** the crown |
  | asset crown node | `HeadTop_End` at 1.78 m | asset parse, PR #385 | the asset's top landmark |
  | asset `Hips` | 0.9500 m | asset parse, PR #385 | the asset's counterpart of `PELVIS.y` |

  **Every length conversion in this document uses this one constant.** `unitsPerMetre` and `metresPerUnit` are one
  calibration read in two directions; a second factor anywhere — a per-layer scale, a per-attachment correction, a root
  translation scaled twice — is a defect, not a refinement. The asset's own 0.9500 m `Hips` against the engine's 235
  units is the cross-check: at 247.37 u/m the engine's pelvis is 0.950 m, so the two agree by construction, and that
  agreement is what `RigLandmarkAlignmentTest` measures rather than assumes (§6.6 A1).
- **Side mirroring.** Attachments are authored for side `A` and mirrored to `P` by negating `Z`. The rest-pose side
  check (§12 L2) proves that the rig's `Left*` bones sit on the same side as side `A`; if they do not, the asset or the
  bone map is wrong — a rendering-time flip would only hide it.

**Segment frame table.** Each row names the node whose rest frame *is* the segment's frame (identity at rest) and the
**published position pair** the posed direction is taken from — `p_child − p_parent`, in the parent's frame, with the
roll convention of D1 (§13 question 11). The roll is an approximation: an attachment offset `d` carries up to `|d| · θ`
of error, where `θ` is that segment's residual roll error (§11).

| Segment | Node | Direction from published positions | Anatomical content | Notes |
| --- | --- | --- | --- | --- |
| pelvis | `PELVIS` | `PELVIS → LUMBAR` | iliac crest, ASIS, ischial tuberosity, sacrum | also the root translation |
| lumbar spine | `LUMBAR` | `LUMBAR → CHEST` | L1–L5 | one rigid segment, not five vertebrae |
| thorax / ribcage | `CHEST` | `CHEST → NECK_END` | ribs 1–12, sternum, thoracic spine | **no per-rib segments** — a documented limitation vs the AA-3D reference, which models 12 rib pairs |
| neck | `NECK_END` | `NECK_END → HEAD_POS` | C1–C7 | one segment |
| skull | `HEAD_POS` | none — terminal joint, inherits the neck direction | cranium, mandible reference | attachment point, orientation = head |
| clavicle | `CLAVICLE_A/P` | `CLAVICLE_A → SCAPULA_A` — **zero length** | clavicle | degenerate, see below |
| scapula | `SCAPULA_A/P` | `SCAPULA_A → SHOULDER_A` (46 units lateral) | scapular blade, acromion, coracoid | carries the girdle DOF the engine animates |
| humerus | `SHOULDER_A/P` | `SHOULDER_A → ELBOW_A` | humerus | |
| forearm | `ELBOW_A/P` | `ELBOW_A → HAND_A` | radius + ulna as one frame | pronation is not a separate segment; a pronation-aware wrap is future work |
| hand | `HAND_A/P` | `HAND_A → FINGERTIPS_A` — **zero length** | metacarpals, palm | fingers are visual-only; degenerate, see below |
| femur | `HIP_F/B` | `HIP_F → KNEE_F` | femur, greater trochanter, linea aspera | **not the hip joint point** |
| tibia | `KNEE_F/B` | `KNEE_F → ANKLE_F` | tibia, fibula, tibial tuberosity | |
| foot | `ANKLE_F/B` | `ANKLE_F → TOE_F` — **zero length** | calcaneus, talus, metatarsals | degenerate, see below |
| toes | `TOE_F/B` | none — terminal joint, inherits the foot direction | phalanges | |

**Five of these fourteen segments cannot derive a direction from positions, and that is measured, not a corner case.**
At `16e0487` the node pairs `CLAVICLE_A → SCAPULA_A`, `ANKLE_F → TOE_F` (with `HEEL_F`) and `HAND_A → FINGERTIPS_A`
(with `PALM_A`, `KNUCKLES_A`) are **coincident** — both joints of each pair sit at exactly the same world position in
every pose sampled — and `HEAD_POS` and `TOE_F` are terminal, with no child joint to point at. So the `clavicle`, `hand`,
`foot`, `skull` and `toes` segments have no position-derived direction at all. Their frame is the **parent segment's**
frame with the authored rest direction, and an attachment authored on one of them is therefore expressed in the parent's
frame: a foot offset is a shank offset, and a clavicle offset is a thorax offset. That is a property of the engine's
solve topology (§4.1), not a defect this framework can fix, and it must not be hidden by a normalisation of a zero
vector.

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
  1  resolve attachments:  world = segmentOrigin + R_segment * (offsetMetres * unitsPerMetre)
       segmentOrigin = the segment node's published world position
       R_segment     = D1: the minimal rotation taking (parentFrame * restDirection) to the published
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

**Renderability is per instance, and it is a mask rather than a hint.** The frame publishes a per-instance renderability
bit; the character layer consumes either that bit or a prefiltered dynamic-geometry list built from it, and the rules are
these:

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

`MuscleRenderabilityTest` (L4/presentation) drives valid → invalid → valid on one instance across carrier reuse and
asserts the mask, the cleared geometry and the restored path. A stale buffer that survives an invalid frame is exactly
the failure it exists to catch, and a test that only checks the first valid frame cannot see it.

#### 7.4.1 Obstacle validation contract

Three different checks are routinely conflated. This section keeps them apart, and only the last of them is the
criterion.

**(a) Sample check — necessary, not sufficient.** Every sample of the final polyline lies outside every declared
obstacle. This catches gross violations cheaply and bounds how far the projection step still has to move. It proves
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
the derived frame for the other would rotate the obstacle relative to the muscle it constrains. The D1 roll
approximation therefore applies to obstacles too, and the `|d| · θ` bound covers both.

Three stages, cheapest first. **Stage 1 and stage 3 decide; stage 2 only rejects and locates.**

```
1  bounding sphere — the cheap ACCEPT.
   c = segment midpoint, h = |p_{i+1} − p_i| / 2, max_R = max(R), max_r = the largest modelled
   tube radius on this path. The pair is CLEAR only if
       |c − cw| − h  >  max_R + max_r + e
   A centreline miss alone may NOT accept: a tube of radius max_r reaches an obstacle that its
   centreline misses, so the sphere must be expanded by the largest segment radius as well as by e.

2  quadratic — rejects and locates; it never accepts.
       q0 = Rwᵀ (p_i − cw) / R,   qd = Rwᵀ (p_{i+1} − p_i) / R
       a = qd·qd,   b = 2 q0·qd,   c0 = q0·q0 − 1,   Δ = b² − 4 a c0
   The segment enters the ellipsoid when Δ ≥ −Δ_tol AND the root interval [t0, t1] meets [0, 1]:
   INVALID, SEGMENT_INTERSECTS_OBSTACLE, distance 0 < r. The interval test is not optional — a
   tangency outside [0, 1] is a segment that misses (fixture tangent_off_segment_clear). Otherwise
   the pair goes to stage 3; Δ < −Δ_tol means only that it cannot enter, never that it clears.

3  distance — the verdict, and the only place a near miss becomes CLEAR or INVALID.
       minimise g(t) = d(t) − r(t) over t ∈ [0, 1], in engine units, in the obstacle's frame:
         d(t) = Euclidean distance from p(t) to the ellipsoid SURFACE (Eberly point-to-ellipsoid)
         r(t) = the modelled tube radius at the same station, piecewise-affine (below)
       CLEAR iff  min over t of g(t)  ≥  e.   Equality is CLEAR.
```

**The radius is piecewise-affine, and it is part of the object being certified.** `r(t)` tapers along the path with
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

**Termination and equality.** Each piece's minimiser runs a fixed iteration count derived from the bracket width and a
stated absolute precision (`1e-6` engine units ≈ 4 nm at 247.37 u/m), so two implementations return the same `t` for the
same input; there is no "iterate until converged" loop whose count depends on rounding. The comparison is inclusive —
`g ≥ e` is CLEAR — because `e` already carries the arithmetic budget and an exclusive test would make the boundary a
coin flip on the last bit.

**`Δ_tol` is fail-closed, and its failure mode is a false INVALID.** `Δ_tol = κ·(b² + abs(4·a·c0))` with `κ = 1e-12` is a
relative bound on the two products whose difference forms `Δ`, so it scales with the geometry instead of being a fixed
absolute epsilon. `κ` is about 4,000× the double-precision noise floor for a `Δ` of order 1 (≈2.2e-16 relative), which is
what makes the band a band rather than a bit test — and it is deliberately loose, because the band's only effect here is
to route a pair to stage 3. A chord inside the band is never cleared by the quadratic. Measured under a
discriminant-only verdict (the earlier design), the band's failure mode is a **false INVALID**: 200,000 near-tangent
chords sampled across it produced **0 unsafe CLEAR verdicts**, every error falling in the safe direction. This document
therefore does not claim the quadratic is "exact for the stated arithmetic": cancellation in `c0 = q0·q0 − 1` happens
upstream of `Δ_tol`, so no tolerance on `Δ` can recover it. Interval arithmetic is not required either, because the
verdict no longer rests on the discriminant.

**Numeric policy.**

| Concern | Rule |
| --- | --- |
| Precision | `Double` throughout the test; the published pose is `Float`, so that quantisation is an input to the budget below, not a term the test can remove |
| Verdict | the finite-segment minimum predicate `min_t (d(t) − r(t)) ≥ e` — not a discriminant sign, not a root interval |
| Discriminant and roots | `Δ`, `t0`, `t1` are **diagnostics only**: they say whether the segment enters and where, which is what the failure report needs. They never produce CLEAR |
| Fail-closed band | `abs(Δ) ≤ Δ_tol` → stage 3 (never CLEAR). `Δ < −Δ_tol` → stage 3 as well; it proves the segment cannot *enter*, not that it clears by `e` |
| Degenerate segment | `a = 0` (zero length): the segment is a point, and the point-to-ellipsoid distance decides; inside → `DEGENERATE_SEGMENT` |
| Non-finite intermediates | overflow, NaN or infinity in `cw`, `R`, `q0`, `qd`, `Δ` or `e` → `NON_FINITE_GEOMETRY`, not a verdict |
| Bad radius or margin | non-positive semi-axis → `DEGENERATE_OBSTACLE`; negative or non-finite `r`, `radiiMetres` or `eps_num` → `NON_FINITE_GEOMETRY`. Neither is clamped |
| Sign convention | `sign(0) = +1`, i.e. `sign(b) = +1` when `b ≥ 0`, else `−1`; without it a symmetric chord from the obstacle centre gives `b = 0`, `q = 0` and a division by zero in the diagnostic `t1 = c0/q` |

Ignoring `Rw` is not a rounding error, it is a wrong answer: for an obstacle with semi-axes (2, 1) rotated 90°, a short
world-space segment from `(−0.1, 1.5)` to `(0.1, 1.5)` lies inside the rotated obstacle (normalized squared length
0.5725) while the unrotated formula reports 2.2525 and calls it clear.

The test is deterministic, allocation-free and **independent of sampling density**: it answers "how close does this
straight segment come to this ellipsoid" for any obstacle size, including one thinner than the sample spacing. The
failing segment, obstacle, entry/exit stations and the minimising `t` are all reported.

**Worked cases** (unit sphere, `q0`/`qd` in normalized coordinates, `κ = 1e-12`, `e` = the numeric margin; all values
computed in double precision):

| Case | `q0` | `qd` | `(a, b, c0)` | `Δ` | Verdict under this policy |
| --- | --- | --- | --- | --- | --- |
| Clear — line misses the ellipsoid | `(2, 0, 0)` | `(0, 1, 0)` | `(1, 0, 3)` | `−12` | CLEAR: minimum over `[0, 1]` is at `t = 0`, surface distance `√9 − 1 = 2` |
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

**(c) `INVALID` — never assumed, and one authoritative reason table.** A path is accepted only when every stage-3 test
returns CLEAR **and** every sample test returns "outside". Anything else is `PathStatus.INVALID` with a reason from the
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
| 6 | `SEGMENT_INTERSECTS_OBSTACLE` | stage 2: the segment enters the ellipsoid (`[t0, t1] ∩ [0, 1] ≠ ∅`) | index | index |
| 7 | `CLEARANCE_VIOLATION` | stage 3: the segment misses the surface but `min (d − r) < e` | index | index |
| 8 | `DEGENERATE_SEGMENT` | a zero-length segment whose point is inside an obstacle | index | index |
| 9 | `TANGENT_DISCONTINUITY` | the entry/exit kink exceeds `θ_max` (§7.4.3) | index | index |
| 10 | `CURVATURE_VIOLATION` | an interior turn exceeds `θ_max` at the declared spacing (§7.4.3) | index | `NO_INDEX` |
| 11 | `SEARCH_EXHAUSTED` | the corridor limit was reached without a candidate (§7.4.5) | `NO_INDEX` | index |
| 12 | `PASS_CAP` | the pass budget `K` was reached without a candidate (§7.4.5) | `NO_INDEX` | `NO_INDEX` |
| 13 | `NO_ROUTE` | **proved** impossible: no route exists between the fixed endpoints outside the declared set | `NO_INDEX` | `NO_INDEX` |
| — | `NO_REASON` | only where the status is `VALID` | `NO_INDEX` | `NO_INDEX` |

There is no "probably fine" outcome and no fall-through default. If the check cannot be completed, the path is invalid.
`SEARCH_EXHAUSTED` and `PASS_CAP` are heuristic exhaustion and say nothing about whether a route exists; **`NO_ROUTE` is
reserved for a proven impossibility** and is never the name for "the search gave up" (§7.4.5).

**The numeric margin is derived, not judged.** `eps_num = 1e-5 m`, from an error budget rather than from how the debug
overlay looks:

| Term | Value | Where it comes from |
| --- | --- | --- |
| published-coordinate quantisation | 3.05e-5 units ≈ 1.2e-7 m | one ulp of a `Float` at 500 engine units (`2⁻¹⁵`) |
| transform accumulation through the chain | ~1e-3 units ≈ 4e-6 m | 33 nodes, ~2 operations each, worst case additive |
| radius knots | 0 | authored constants, not sampled |
| **`eps_num`** | **1e-5 m** (2.47e-3 units) | ≈2.5× the arithmetic total, rounded up to a decade |
| *not in `eps_num`*: rig registration | up to **8.73 mm** at a 1 m lever | 0.5° of `τ_rot` (§6.6 A1) — a different error, outside this guarantee |

`e = eps_num · s` is computed once, in metres first and converted once with `s = unitsPerMetre` (§7.2). A margin applied
as if it were already in engine units is smaller by a factor of `s` (≈ 247), so a path that sits inside the margin would
be reported clear; `non_unit_scale` in §12.2.1 is the fixture that catches exactly that error, and it is paired with a
control that must stay CLEAR.

**The guarantee, stated exactly.** *The generated modelled tube — the final validated polyline swept by the declared
piecewise-affine radius, with its end caps and joins — stays at least `eps_num` from the surface of every declared model
obstacle that applies to that head, except on the path intervals where the definition declares an attachment-contact
exemption.* That is the whole of it. It is a statement in engine space about a surrogate shape and a surrogate obstacle
set, and it is **not** a statement about real bone, fascia or skin, and **not** a statement about the swept volume between
two frames (§7.4.3).

**Joins, caps and morphs are inside or outside the certified object, explicitly.** Two consecutive frusta share the disc
at their common knot and `r` is single-valued there, so the union has no gap and a join is inside the tube. A flat end cap
is the disc at `t = 0` or `t = 1`, so it is inside the tube too. **Morphs are outside it**: a body or muscle morph moves
vertices, and no M1 muscle layer carries one (§6.3), so a morphed muscle re-opens the guarantee rather than inheriting it.
**Flat sheet muscles are outside it**: a sheet's rendered geometry is a two-sided strip, not a swept tube, and a strip has
no single radius, so this criterion cannot certify one. A sheet muscle is therefore clearance-`INVALID` by default until a
sheet envelope is specified (a declared half-thickness and a per-station width, with its own distance predicate), and M1
ships no sheet muscle (§15).

**Attachment-contact exemptions are explicit, narrow and per interval.** An origin or insertion may legitimately sit
inside an obstacle — a hip-flexor origin on the pelvis is the normal case, not a defect — so the exemption is declared,
not inferred: `ContactExemption(obstacleId, headIndex, fromStation, toStation, source)`, where the stations are fractional
arc lengths and the interval must contain the endpoint it exempts (`fromStation = 0` or `toStation = 1`). The criterion is
not applied on that interval and **is** applied everywhere else on the same obstacle. Three things are forbidden because
each disables the check rather than narrowing it: **moving an endpoint** to a clear position (the attachment is anatomy,
not a free variable), **exempting a whole obstacle** (a `0 … 1` interval is a definition error at L3), and **an exemption
with no `source`** (it is a review judgement and carries the L7 row that made it). The report counts exemptions per
muscle, so a muscle whose clearance rests mostly on exemptions is visible rather than silently green.

**What this costs, stated rather than assumed.** Stage 1 accepts the overwhelming majority of segment–obstacle pairs with
two distance comparisons and no root solve. Stage 3 runs on the remainder, and each evaluation is an Eberly
point-to-ellipsoid distance — a sixth-order root solve, not a closed-form dot product — repeated once per radius piece.
That is the price of a distance predicate instead of an inflation test. It is measured at stage 2; if it proves
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

#### 7.4.2 Overlapping, nested and impossible routes

Obstacles overlap and nest in normal anatomy (a bone ellipsoid inside a muscle-group ellipsoid is the common case, not
an error). The rules make the outcome deterministic and reviewable:

- **Declaration order is part of the data.** Projection runs in declaration order, and that order is diffable. Two
  definitions with the same obstacles in a different order are different definitions with different expected fixtures.
- **Acceptance is by the union, and there is exactly one union.** Per head, the obstacle set is
  `union(muscle.wraps, globals)` — the obstacles declared on that muscle plus every `WrapSurface` whose `global` flag is
  set (§7.3) — resolved to ids, **deduplicated** (an id declared twice is one obstacle), and ordered by declaration with
  globals first. Construction and validation both consume **that one ordered list**; a solver that projects against one
  set and validates against another is the defect this rule removes, because the two sets can differ exactly when a
  global obstacle is added to a definition. Validation tests every segment against every obstacle individually, so
  overlap and nesting need no special case — the union falls out of the per-obstacle tests. Only projection order is
  affected.
- **Nested obstacles.** A sample inside a nested pair is pushed out of each in declaration order until it leaves both or
  the pass cap is reached. An "outer" surface that does not contain its declared inner obstacle is a **definition
  error** (L3), not a runtime condition to paper over.
- **An attachment inside an obstacle, at runtime.** L3 checks attachments against obstacles in the **bind stance**, which
  is a definition-time test. An attachment that is clear at bind can sit inside an obstacle that belongs to a *different*
  moving segment once the pose changes (a pectoral origin and a humerus capsule, for instance). The solver therefore also
  checks every resolved attachment against every obstacle **in the current pose** and returns `INVALID` with
  `ENDPOINT_INSIDE_OBSTACLE` when one is inside. That is a runtime condition, not evidence that the static data is wrong,
  and the two must be reported differently: L3 blocks the definition; the solver blocks the frame.
- **Failure is named by what actually happened, and the three names are not interchangeable.** `PASS_CAP` means the pass
  budget `K` ran out; `SEARCH_EXHAUSTED` means the corridor limit was reached without a candidate; `NO_ROUTE` means the
  impossibility was **proved** — no route exists between the fixed endpoints outside the declared set — and it is
  reserved for that, never used as the name for "the search gave up" (§7.4.5). `ENDPOINT_INSIDE_OBSTACLE` is a separate
  runtime condition (§7.4.1c). If the final polyline fails the check instead, the reason comes from the reason table.
  Either way it is reported, counted by the fixture, and blocks L4. A path through geometry is never accepted because it
  is the closest thing the solver found, and an exhausted search is never reported as a proof.
- **Ambiguity.** Where two local routes are both valid, declaration order and the fixed sample grid decide. The solver
  must return the same route on every run, every device and every buffer reuse. "Either route is acceptable" is not a
  contract, and a solver that alternates between two valid routes has a bug, not a tie.

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
4. **Temporal continuity — a separate stateful observer, and no `v_max`.** The solver stays **pure**:
   `solve(pose, profile, out)` takes no previous frame and no `dt`. The earlier proposal — a bound `|Δp(s)| ≤ v_max · Δt`
   with `v_max` = 1.2 m/s — is **withdrawn**, because it is a speed limit on an output that has no physical speed and no
   defensible constant. Continuity becomes an **observer** with its own state:

   - **Inputs.** The frame timestamp; the previous frame **that was `VALID` for this instance**; and the same instance
     identity (`MuscleHeadId`), definition version and body profile. A frame from a different definition, profile or
     instance is not comparable: it resets the observer instead of being differenced.
   - **State and lifecycle.** Last timestamp, the last accepted stations and positions, and the reference frame they were
     recorded in. Initialisation has no previous frame and produces no verdict; **pause/resume, seek and reset** all
     re-initialise, because a seek is a discontinuity in the source timeline and reporting it as a path jump would be a
     false positive. An `INVALID` frame in between breaks the comparison rather than being skipped silently: the next
     valid frame compares against the last valid one **and** reports the gap.
   - **Stations.** Matched fractional arc-length stations, not sample indices — resampling and inserted detour points
     change `pointCount` between frames, so index `i` is not the same anatomical place twice.
   - **The comparison is against a conservative pose-driven prediction.** The observer computes the displacement the
     *pose alone* can produce at that station — the moved attachments, every moving wrap centre, the angular velocities of
     the enclosing segments, and the lever arms of any attachment offset — and compares the measured displacement with
     that prediction. A station cannot legitimately move faster than its own geometry allows, so the residual is the
     signal and the raw speed is not.
   - **Near a route-branch switch, no finite universal bound follows from bounded input speed.** When the solver selects
     a different branch, the path may move further than any pose-driven prediction, and no constant covers it. That is
     stated, not papered over.
   - **An excess is a diagnostic, never a verdict.** It requires investigation — the report names the station, the frame
     and the branch — and it is not proof of a routing bug. It also never triggers **silent rate-limiting**: a filter
     that slows a path to satisfy a threshold produces a path that no longer matches the pose or the clearance predicate,
     which is a worse defect than the jump it hides.
   - **Hysteresis is explicit and resettable.** If route-branch hysteresis is used, it is declared (a station margin and a
     dwell), it can be reset, and the observer reports each switch.

   The threshold is named **once** — `δ_frame`, the maximum permitted station displacement *in excess of* the pose-driven
   prediction, in engine units — and the same name is used in §12.2. The earlier text called the same quantity `v_max · Δt`
   here and `δ_frame` in the test inventory, which is how one threshold ends up applied twice and differently.

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

Design notes:

- **Length is arc length along the solved path.** A straight-chord measure reports shortening wherever a muscle wraps a
  joint, which inverts the sign of the reported change. This is the single most important correctness rule in the model.
- **`restLength` comes from the same solver at the reference stance**, not from the definition's numbers, so the rest
  state and the runtime can never disagree (this is also what the exporter bakes; §10.4). The **reference stance** is the
  engine's rest stance of §6.1 — arms at the sides, the pose every builder starts from — and it is named in the
  definition version. `restLength` must be finite and strictly greater than zero for every head; a head that cannot
  produce one is `INVALID` with `REST_LENGTH_INVALID` (§7.4.1c) rather than dividing by it. A relative change computed
  against a zero or a NaN rest length is not a small error, it is a meaningless number that reads as a large one.
- **A length comparison with literature is a record, not a number.** Where a measured reference exists, the comparison
  states: the source, the model or subject it came from, the pose or joint configuration, the normalisation used (muscle
  length over which span, at which reference), the tolerance, and the uncertainty. A row with no source is an **evidence
  gap** and is displayed as one — never as a pass, and never as a zero.
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
limit is `NO_ROUTE`.

Escalation is not decoration: a waypoint placed exactly at the support distance leaves the two connecting segments
grazing the surface, because they approach obliquely. For a 20 mm sphere on a 0.29 m chord, `d_1 = R + e` = 20.01 mm
gives a closest segment approach of 19.822 mm — inside the surface plus margin — while the first escalated placement at
`1.5·d_1` = 30.015 mm leaves both connecting segments clear (closest approach 29.393 mm > 20.01 mm). The rule therefore
treats `d_1` as a starting guess and lets the predicate, not the guess, decide. (The anisotropic case below needs no
escalation; this sphere example is the one that does.)

**Worked frame check — `rotated_anisotropic`.** One obstacle with local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z
(local X → world +Y), `eps_num` = 1e-5 m, chord `(−145, 0, 0) → (145, 0, 0)` mm in world X through `cw`. Here
`d̂ = (1, 0, 0)`, `u = 0` (midpoint at the centre), and `Rwᵀ d̂ = (0, −1, 0)`: the local axes least aligned with it are
X and Z (both dot 0), so the tie order picks local X, rotated back to world `(0, 1, 0)`. Correctly, `l̂_d = (1, 0, 0)`
and `h = R_x` = 40 mm → `d_1` = 40.01 mm, at which both connecting segments are already clear of the surface by more
than `e`; `d_2 = 1.5·d_1` is a wider margin. Choosing the fallback in world axes instead (world +Y fed straight into
`h` as if local) gives `h = R_y` = 10 mm, a waypoint 10.01 mm from the centre — still inside the 40 mm boundary — so the
predicate rejects it. The `rotated_anisotropic` fixtures in §12.2.1 pin both halves: the validator must honour `Rw`, and
the constructor must transform the fallback and the support direction.

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
derived from a measured length (M1 has none); any substitute heuristic ("thicker when the exercise is harder", "pulse on
the concentric phase"). `relativePathLengthChange` is reported for the length readout and the education panel; it is
never a geometry input.

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
| Whether a single skin can hold all helper bones | Filament's per-renderable bone budget is engine- and driver-dependent and **unmeasured on the phone** — the #384 run reports a frame rate, not the bone limit | measure on the target device; if the budget is too small, split the muscle layer into regional renderables (§10.3) |
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
| Engine lifetime | one engine, one active character, `ActiveCharacter` slot (`CharacterFeature.kt:18-24`) | layers are renderables in that one scene; a second live character is out of scope |
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

**The M1 procedural layer's budget is a formula over instances, not a sample count.** A tube is generated from the
polyline, so its cost is set by longitudinal samples, ring tessellation and the number of **instances** — muscles ×
sides × heads, not muscles:

```
triangles per tube = 2 × (N_final − 1) × R                     // N_final ≤ pointCapacity, R = ring sides
caps               = 2 × R per capped end
M1 total           = Σ over EVERY instance (both sides, every head) of that, plus its caps
```

The previous revision counted eight *heads* where there are sixteen *instances*: eight bilateral, single-head muscles at
`N = 48`, `R = 8` is `16 × 2 × 47 × 8 = 16 × 752 = ` **12,032** triangles, and **12,288** with a cap at each end of every
tube — not 6.0 k, which is half the object. A two-head muscle adds a third tube, not a second muscle. A flat sheet uses a
two-sided strip instead of a ring and is covered by neither this formula nor the clearance guarantee (§7.4.1): its cost
is `2 × (N_final − 1) × (W − 1)` quads for a `W`-station width, and its own envelope must be declared before it can be
budgeted or certified. Both `N` and `R` are declared in the quality tier, so the number is bounded before any device run.
It is arithmetic, not a measurement: stage 1 records frame time and memory as information, not as a gate (§12.3), and a
measured number replaces the estimate when one exists.

**Bone-budget risk.** A single skin holding every muscle's helper bones may exceed what Filament accepts per renderable
on a given driver. That number is **unmeasured on the phone**. The design therefore allows the muscle layer to be split into
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
| Segment frame from published positions + declared roll | approximation | the roll about the segment direction is arbitrary, so an attachment offset of `d` metres carries up to `d · θ` of error; five segments inherit their parent's frame because the engine's nodes are coincident or terminal | `SegmentFrameDirectionTest` (L2) |
| Bone-name resolution with aliases | established technique | alias tables can map the wrong bone | L2 coverage + manual rig review |
| Anatomical attachment points | **requires expert validation** | a plausible-looking point can still be wrong | L3 mechanical + L7 review (§12.4) |
| Path + ellipsoid obstacle wrapping | approximation — geometric, not contact | ellipsoids stand in for bone and fascia; the clearance predicate is measured with a stated error budget (`eps_num`), and the approximation is the obstacle model, not the arithmetic | L4 fixtures + visual review |
| Euclidean segment-to-ellipsoid clearance | established technique (distance), surrogate object | the guarantee is the **generated modelled tube** against the **declared model obstacles**, with declared attachment-contact exemptions: no claim about real bone, fascia or skin, no claim about the swept volume between frames, and no claim about a morphed or sheet muscle (§7.4.1) | L4 fixtures + `CLEARANCE_VIOLATION` rows |
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
| **L1 Motion** | 18 existing rules over joint-space geometry (`ExerciseValidator.kt:47-92`) | yes, unchanged | all | the pose/engine is wrong; block |
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
| `RigLandmarkAlignmentTest` | L2 | §6.6 A1: every mapped bone head coincides with its engine joint at bind within `τ_bind`; rest orientations within `τ_rot` — expected **red** on `humanoid_v1` against the recorded baseline (§6.6) |
| `RigAlignmentResidualTest` | L2 | §6.6 A2: the **measured** residual per joint at the three named poses, within `τ_residual`; the equal-length/different-direction control case must report a non-zero residual, and no length-sum bound is computed |
| `SegmentFrameDirectionTest` | L2 | §7.2/§7.4 step 1: a derived segment direction equals the published direction `p_child − p_parent` within `τ_dir` (proposed, fixed at stage 1); the roll convention is declared per segment and reproduced; run on real baseline poses over both hierarchies, both sides of every bilateral limb, one deliberately bent IK case, and the five degenerate segments (inherited frame, no zero-vector normalisation) |
| `NonCommutingSplitTest` | L2 | §6.2: a parent and child about different axes compose to the stated world rotation — a reversed `parent⁻¹ · child` order or a one-sided `B` fails |
| `UnmappedBoneInheritanceTest` | L2 | §6.2: moving a mapped parent carries an unmapped child's world rotation within tolerance while the child's local rotation is unchanged; the twist policy is the declared one |
| `BoneMapCoverageTest` | L2 | 23 mapped joints resolve; unmapped joints are declared, not accidental |
| `RigSideTest` | L2 | `Left*` bones on the same side as anatomy side A in the rest pose |
| `AnatomyDefinitionTest` | L3 | ids unique; attachments resolve; envelope bounds; L/R mirror; heads consistent |
| `MusclePathGeometryTest` | L4 | arc length ≥ chord; every segment of the smoothed polyline is disjoint from every inflated obstacle, and every sample lies outside the inflated surface by the `ε_pen` numerical margin; deterministic across runs and buffer reuse |
| `MusclePathObstacleFixtureTest` | L4 | the validator fixtures (§12.2.1) return the expected verdict, and the solver fixtures return the expected final path or the expected failure reason — never silently |
| `MusclePathContinuityTest` | L4 | C0, tangent bound at entry/exit, curvature bound; and, for the observer: station displacement in excess of the pose-driven prediction within `δ_frame` at matched arc-length stations, with no verdict on the first frame, a re-initialisation on pause/resume/seek/reset, a reported gap across an `INVALID` frame, and no rate-limiting of the path |
| `MuscleLengthDeterminismTest` | L4 | the same pose produces the same length across runs, buffer reuse and definition reload; no discontinuity beyond tolerance across a sampled motion |
| `MuscleLengthReferenceTest` | L4 | length matches expert-reviewed reference data where it exists, within the stated tolerance; absent reference data is reported as a gap, not a pass |
| `MuscleDirectionalExpectationTest` | L4 | only exercise- and muscle-specific expectations, each carrying a documented justification and source |
| `MuscleRestLengthTest` | L4 | solved rest length matches the calibrated reference within tolerance |
| `MuscleRenderabilityTest` | L4 / presentation | §7.4: an `INVALID` instance is masked and its geometry cleared in the same frame; multi-head is all-or-nothing; valid → invalid → valid restores the path across carrier reuse |
| `AnatomyFrameLayoutTest` | L4 | §5.3: the `3 · headCount · pointCapacity` stride addresses every point of every head; `pointCapacity` is the post-insertion maximum; an over-capacity head reports `BUFFER_CAPACITY` instead of growing or truncating; `clear()` leaves every slot `INVALID` |
| `MuscleGeometryOwnershipTest` | L4 / presentation | §7.6: toggling morphs or correctives leaves every `MuscleState` bit-identical, and nothing in `character/` writes `AnatomyFrame` |
| `ProceduralTubeBudgetTest` | L5 (M1 subset) | generated triangles match the §10.3 formula for the declared `N` and `R`; no zero-area ring; all coordinates finite |
| `MorphPresetTest` | L6 | rest invariant per preset; joint rings unmoved beyond tolerance |
| `LayerToggleTest` | presentation | toggling a layer changes visibility only; no engine or pose rebuild; an evicted layer reloads and re-shows |
| `ArchitectureBoundaryTest` | architecture | §4.5: every file in `animation/` and `poses/` imports no `androidx.*` (Compose included), `android.*`, `ui.*`, `render.*` or Filament — `theMotionCoreImportsNoComposeOrFrameworkCode` and `theMotionCoreImportsNoOtherAndroidxCode`; the `anatomy/` and `character/` edges are outside its source set, so they remain review obligations |
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

Two kinds of fixture are needed, and conflating them was a defect in an earlier revision: a **validator fixture**
(named `raw_*`) hands a finished polyline to the predicate and states the verdict it must return; a **solver fixture**
hands an obstacle configuration to the constructor and states the *final* path or failure it must produce. A raw crossing
polyline must be rejected; a solver run on the same obstacle may legitimately return `VALID` after routing around it.
Both facts are true at once, and only a split table can say so. **Every success fixture has a failure fixture beside it**,
so neither table can be passed by an implementation that always accepts or always rejects.

**Validator (`raw_*`) fixtures — a finished polyline in, a verdict out.**

| Fixture | Configuration | Expected verdict |
| --- | --- | --- |
| `raw_clear` | a polyline that clears every obstacle | `VALID` |
| `raw_clear_negative_discriminant` | unit-sphere case `(q0, qd) = ((2,0,0), (0,1,0))`: `Δ = −12 ≪ −Δ_tol` | `VALID` — minimum over `[0, 1]` at `t = 0`, surface distance 2; a test that maps a large negative `Δ` into the fail-closed band fails here |
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
| `rotated_anisotropic` | local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z (local X → world +Y); straight chord `(−100, 15, 0) → (100, 15, 0)` mm | `INVALID` — `SEGMENT_INTERSECTS_OBSTACLE` with the correct `Rw` (`Δ ≈ 1349 > Δ_tol`); a test that drops `Rw` reports clear (`Δ ≈ −120`). Exercises the frame transform on a non-spherical obstacle, not just a rotation |
| `clearance_margin` | a chord that misses the surface but by less than `eps_num` | `INVALID` — **`CLEARANCE_VIOLATION`**, not `SEGMENT_INTERSECTS_OBSTACLE`. This is the row that separates the two reasons, and a predicate that only reports intersections reports `VALID` here |
| `non_unit_scale` | sphere `radiiMetres` = 0.020 m; chord `(−100, 20.005, 0) → (100, 20.005, 0)` mm (0.005 mm outside the surface); `s = 235/0.95 ≈ 247.37`, `eps_num` = 1e-5 m = 2.47e-3 units | `INVALID` — `CLEARANCE_VIOLATION`: the gap is 1.24e-3 units, below the margin. Applying `eps_num` as if it were already engine units gives `e = 1e-5`, a **false CLEAR**. Paired control: chord at `y = 20.05 mm` (0.05 mm outside, 1.24e-2 units) → `VALID`, so the fixture cannot pass by rejecting everything |
| `taper_knot_minimum` | radius knots `(0, 0), (0.2, 1.21), (0.4, 1.215), (0.6, 1.55), (1, 0)` on the straight path `p(t) = (2 + t, 0, 0)` | `INVALID` — `g(0.2) = −0.010`, violating on `[0.198020, 0.210255]`. A single golden-section run on `[0, 1]` returns `t = 0.6`, `g = +0.05` and reports clear, so the fixture fails a minimiser that ignores the knots |
| `endpoint_inside` | an attachment resolved inside a declared obstacle | `INVALID` — `ENDPOINT_INSIDE_OBSTACLE` |
| `non_finite` | a NaN coordinate or radius | `INVALID` — `NON_FINITE_GEOMETRY` |
| `degenerate_obstacle` | a zero or negative semi-axis | `INVALID` — `DEGENERATE_OBSTACLE` |
| `rest_length_invalid` | a head whose reference-stance length is zero | `INVALID` — `REST_LENGTH_INVALID`, never a division |

**Solver fixtures — obstacle configuration in, final path or failure out.**

| Fixture | Configuration | Expected result |
| --- | --- | --- |
| `single_obstacle` | one ellipsoid between two attachments, chord through its centre | `VALID`; the §7.4.5 lateral rule routes around it; every segment of the **final** polyline passes the stage-3 predicate |
| `rotated_anisotropic` | the `rotated_anisotropic` validator geometry as a solver run: local semi-axes 40 × 10 × 20 mm, `Rw` = +90° about Z, chord `(−145, 0, 0) → (145, 0, 0)` mm through the centre | `VALID`; the fallback picks local X (tie X/Z), rotates it to world +Y, and `h(l̂_d) = R_x = 40 mm` gives `d_1 = 40.01 mm` with both connecting segments clear by more than `e`. Choosing the fallback among world axes, or using the world direction directly as the support argument, yields `h = 10.1 mm` and a waypoint still inside the obstacle → `INVALID`; the fixture fails that implementation |
| `multiple_obstacles` | two obstacles in series along the route | `VALID`; every segment clear of both after smoothing |
| `overlapping_obstacles` | two ellipsoids that intersect each other | `VALID` outside the union; identical result for the same declaration order |
| `nested_obstacles` | a small ellipsoid fully inside a large one | `VALID` outside the outer surface |
| `near_tangent` | an obstacle whose surface is tangent to the straight chord | `VALID`; the solver routes around it so the **final** polyline clears by `eps_num` (not merely "is not intersecting"); no oscillation; tangent check within `θ_max`. The solver half of the split: the raw tangent chord is rejected as `tangent_on_segment_invalid`, and a detour that clears is a legitimate `VALID` |
| `after_smoothing_regression` | a configuration whose pre-smoothing polyline clears but whose 0.25-blend smoothed polyline does not | `VALID`, and the accepted polyline is the smoothed-then-projected one. A solver that validates **before** smoothing accepts a curve the predicate never saw; the fixture fails it. The pass loop's own step 4d is what catches this case and forces the retry |
| `two_obstacle_oscillation` | two obstacles for which the detour side that clears one puts the path inside the other, alternating per pass | `INVALID` — `PASS_CAP`. Deterministic and terminating: the same input produces the same failure at the same pass, not a hang and not a different reason on a second run |
| `search_exhausted` | an obstacle whose required detour distance exceeds the declared corridor limit | `INVALID` — `SEARCH_EXHAUSTED`, not `NO_ROUTE`: the search gave up, no impossibility was proved |
| `no_valid_route` | obstacles that provably close the corridor between the attachments, with the proof recorded in the fixture | `INVALID` — `NO_ROUTE`; the frame reports it and nothing is drawn along it |
| `pass_cap` | a configuration that exhausts the pass budget `K` without a candidate | `INVALID` — `PASS_CAP`; deterministic and terminating |

**`outside_endpoints_crossing_obstacle`, constructed so it cannot pass by accident.** One ellipsoid with semi-axes
4 mm × 2 mm × 2 mm centred on the straight chord between two attachments 0.29 m apart, with the polyline running along
the ellipsoid's long axis through its centre. **The sample count is part of the fixture: N is pinned at 30 samples** —
29 intervals of 0.01 m, both endpoints included — which puts the nearest samples at ±5 mm from the centre, where
`|q| = 5/4 ≈ 1.25 > 1`. With 29 samples the grid lands one sample *exactly* on the centre (28 intervals of 0.010357 m,
and `14 × 0.010357 = 0.145`), the sample check alone rejects it, and the fixture stops testing the segment predicate — so
29 and 30 are not interchangeable, and a fixture that does not pin N is not reproducible.

At that grid, **no sample is inside the obstacle and no segment has one endpoint inside**, so the sample check and a
boundary-straddle refinement rule both report "clear" while the path passes through the centre. Stage 2 returns
`Δ ≈ 23.8 > Δ_tol` with roots `[0.09, 0.91] ∩ [0, 1] ≠ ∅` and reports `SEGMENT_INTERSECTS_OBSTACLE`.

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
| R4 | Device evidence is one run on one phone | certain | medium | record each run (§12.3); generalise no number beyond the device it came from; FPS/thermal gating deferred by the owner | engineering |
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
11. **Roll convention for a position-derived segment frame (D1) — default chosen, owner confirmation pending.** The
   segment frame is the minimal rotation taking `parentFrame · restDirection` to the published direction
   `p_child − p_parent`, applied in the parent's frame (§7.2, §7.4 step 1). The roll about that direction is therefore
   arbitrary, and an attachment offset `d` carries up to `|d| · θ` of error, where `θ` is the residual roll error for
   that segment — declared per segment and measured at stage 1. Alternatives: an authored roll reference per segment (a
   stable up-vector) or a rig bone-axis alignment. Default chosen because it needs no new authored data and is
   deterministic; the cost is the stated error bound, which is why §11 classifies it as an approximation. Smallest
   resolving experiment: measure `θ` for the eight M1 segments on the three named poses and decide whether the bound
   holds for the widest attachment offset in the library.
12. **The A1 baseline and the calibration landmark pair (D2) — default chosen, owner confirmation pending.** The shipped
   `humanoid_v1` asset does not pass A1: its worst mapped joint is +89.3 mm against a 0.5 mm tolerance (§6.6). The
   default is to record that as a known baseline and make *a rig that passes A1* a stage-1 deliverable, rather than to
   weaken A1 — a tolerance chosen to fit the current asset measures nothing. The same question decides the calibration:
   the pelvis pair gives 247.37 u/m and the crown pair 210.83 u/m, the first 17.3 % above the second (§7.2, §6.4). Default chosen:
   crown-to-floor as the pair `heightMetres` declares, because it is the pair the asset exposes as `HeadTop_End`, with
   the pelvis estimate kept as a cross-check that fails the asset beyond 1 %. Smallest resolving experiment: build the
   stage-1 rig, measure both spans on it, and keep the pair whose landmarks agree on that rig.

**Known traps from the device run (spike #384).** Each cost a debugging cycle on real hardware, and each is a property of
the current code, not of the model. PR #385 at `bcb3658` contains none of the first three fixes; a preparatory change is
bringing them into the stage-2 runtime before the framework builds on it.

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
- **Components:** rig asset (23 mapped bones including the scapula pair, plus fingers), `BoneMap` manifest,
  `RigAssetContractTest`, `PoseRetargeterRestInvariantTest`, `RigSideTest`, `RigLandmarkAlignmentTest`,
  `RigAlignmentResidualTest`, `SegmentFrameDirectionTest`, `UnmappedBoneInheritanceTest`, budget checks, device
  measurements.
- **Dependencies:** the character runtime from PR #385 (merge or rebase); art for the base body.
- **Expected files:** `app/src/main/assets/characters/*/skin.glb`, `character/BoneMap.kt`,
  `character/RigAssetValidator.kt`, `app/src/test/.../RigAssetContractTest.kt`, `docs/CHARACTER_DEVICE_VALIDATION.md`.
- **User-visible deliverable:** a dev build showing a correctly proportioned human performing the current exercises,
  with the skeleton layer overlaid.
- **Automated acceptance:** rest invariant identity within 1e-4 on every bone; 23/23 mapped joints resolve; side check
  passes; budgets reported; **A1 green on every mapped joint (≤ 0.5 mm, ≤ 0.5°) and the A2 residual within 5 mm at the
  three named poses**. The shipped `humanoid_v1` fails both — its recorded baseline is 179× the A1 tolerance at the worst
  joint (§6.6) — so a passing rig is this stage's deliverable, not a formality.
- **Manual acceptance:** no visible shear, no T-pose at frame 0, joints hold across a squat, a hinge and an overhead
  reach.
- **Gate:** the character renders on a device, Monk motion retargets to the rig correctly, and **the rig passes A1 and
  A2** (the recorded `humanoid_v1` baseline does not, §6.6). FPS and thermal numbers are recorded as information, not as
  a gate — the owner's decision is result first, optimisation later (§12.3).
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
9. Tap hints on the rig: tapping an attachment landmark names the muscle attached there and the layer it belongs to. The
   label is anchored to the landmark and the tap moves nothing — no deformation, no geometry keyed to selection. The full
   picking and explanation system remains stage 7.

**Explicitly not in M1:** authored muscle meshes (stage 3), activation colouring (stage 4), deep muscles, a second
morphology preset (stage 5), organs, the stage-7 label and picking system (M1 has only the attachment-landmark hints of
item 9), a muscle library beyond eight, and any claim of surface accuracy.

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
