# Reference Humanoid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable GPU-skinned, semi-realistic Reference Humanoid that consumes the existing canonical 33-node animation output without changing biomechanics.

**Architecture:** Add a typed humanoid presentation layer downstream of `SkeletonPipeline`: canonical frame → `HumanoidMapper` → immutable visual pose → prebuilt visual rig → GPU skinning. Keep `SkeletonRenderer` and the legacy illustration/keyframe path intact while integrating the new 3D presentation beside the current Compose surface. Optional skeleton and musculature remain independent layers controlled only by `AnatomyVisualizationOptions`.

**Tech Stack:** Kotlin, Android/Jetpack Compose, existing canonical animation pipeline, Android OpenGL ES 3.0 via `GLSurfaceView`/`AndroidView`, GPU skinning, JUnit 4 unit tests, packaged optimized humanoid mesh/material assets.

**Spec:** `docs/superpowers/specs/REFERENCE_HUMANOID_DESIGN.md`

## Global Constraints

- The existing canonical 33-node skeleton remains the sole source of movement intent and published pose output.
- `HumanoidMapper` is downstream-only and must not perform IK, physics, pose correction, constraint solving, or biomechanical reinterpretation.
- Mapping must not mutate `CanonicalSkeletonFrame`.
- `SCAPULA_L/R` are separate visual deformation elements and are not required to parent the arm chain.
- `LUMBAR` must support one-to-many visual mapping to `SPINE_LOWER` and `SPINE_UPPER`.
- `HumanoidDefinition` proportions are applied during rest-rig construction/binding, not recomputed per frame.
- Version 1 exposes exactly `showSkeleton` and `showMusculature`; both are independent booleans.
- Disabled optional layers must not require loading or creating their geometry.
- Body deformation uses GPU skinning; CPU-side per-vertex deformation is out of scope.
- Corrective blend shapes are visual only and must not change canonical biomechanics.
- Standard and Reduced quality tiers share one logical rig and mapper; tier selection never changes biomechanics.
- Standard is the main research tier: quality semi-realistic geometry with strict geometry/texture optimization.
- Reduced is the weaker-device fallback: same rig/mapping, simplified geometry/textures, optional/reduced musculature.
- Target stable sustained performance on target mobile hardware for 20–60 minute sessions.
- No runtime physics, tissue simulation, physiological muscle activation, cloth/hair simulation, facial animation, or per-muscle IK in v1.
- Legacy icon assets and the old keyframe/illustration engine are retained.
- Existing canonical exercise animations must remain byte/behavior compatible with the current biomechanics path.

## Implementation Tasks

### 1. Establish typed humanoid-domain models and pure mapping contract

**Files to add:**
- `app/src/main/java/com/monkfitness/app/animation/humanoid/AnatomyVisualizationOptions.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidNode.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidDefinition.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidPoseFrame.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidMapper.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidMapperTest.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidDefinitionTest.kt`

**Steps:**
- [ ] Define strongly typed visual node IDs for the approved rig, including separate scapula, palm, fingers, and thumb elements.
- [ ] Define `AnatomyVisualizationOptions(showSkeleton, showMusculature)` with no screen-specific state.
- [ ] Define `HumanoidDefinition` using the approved body-proportion fields and validate positive, finite values.
- [ ] Define immutable `HumanoidPoseFrame` containing mapped local/world transforms plus the source canonical-frame identity/version needed by tests and diagnostics.
- [ ] Implement `HumanoidMapper` as a pure mapper over typed canonical nodes; explicitly encode the one-to-many lumbar mapping and left/right mappings.
- [ ] Keep visual proportions out of per-frame transform derivation except for precomputed rest/bind information supplied by the rig.
- [ ] Add tests that verify every canonical node in the approved mapping table reaches the intended visual target, that lumbar reaches both spine visual segments, and that the input canonical frame is unchanged.
- [ ] Add proportion tests proving changed morphology changes visual rest lengths only, not the authored canonical rotations/intent.
- [ ] Run focused unit tests and commit this task independently.

### 2. Build the reusable visual rig and bind data

**Files to add:**
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidRig.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidBone.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidRigBuilder.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidRigBuilderTest.kt`

**Files to inspect/modify only as required by integration:**
- `app/src/main/java/com/monkfitness/app/animation/SkeletonDefinition.kt`

**Steps:**
- [ ] Represent the visual hierarchy as a static typed bone graph with precomputed parent indices, bind transforms, inverse bind transforms, and canonical target associations.
- [ ] Build the approved 30–35-element rig shape, keeping scapula independent of arm parenting.
- [ ] Include readable palm/finger/thumb geometry attachment points without adding finger runtime kinematics.
- [ ] Apply `HumanoidDefinition` once to construct the rest pose and bind transforms.
- [ ] Do not add humanoid-specific joint limits or a second solver.
- [ ] Add tests for hierarchy parentage, canonical target association, inverse bind correctness, and morphology-sensitive rest lengths.
- [ ] Verify the rig builder performs no frame-dependent work and commit independently.

### 3. Define the mesh/material asset contract and Standard/Reduced assets

**Files/directories to add:**
- `app/src/main/assets/reference_humanoid/standard/`
- `app/src/main/assets/reference_humanoid/reduced/`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidAssetManifest.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidAssetManifestTest.kt`

**Steps:**
- [ ] Choose one GPU-friendly asset representation already compatible with the app’s Android toolchain; prefer an offline-authored packed representation rather than a heavyweight runtime scene framework.
- [ ] Define a manifest containing mesh sections, material/texture references, skin-joint indices, inverse-bind data, and tier metadata.
- [ ] Ensure Standard and Reduced assets target exactly the same logical rig/bone names and bind-space contract.
- [ ] Produce the Standard body as a quality semi-realistic anatomical reference mesh with optimized topology and texture sizes; prioritize spine, pelvis, shoulder girdle, joints, hands, and feet.
- [ ] Produce a Reduced variant by simplifying geometry/textures only; do not alter rig semantics.
- [ ] Keep musculature in a separate asset section/file so it can be skipped entirely when disabled.
- [ ] Add asset-manifest tests that reject mismatched joint order, missing required visual nodes, invalid skin weights, or tier-specific biomechanical differences.
- [ ] Record provenance/license metadata for any third-party base mesh used; do not silently add an asset with unknown redistribution rights.
- [ ] Commit assets and manifest separately from renderer code so binary-review changes stay isolated.

### 4. Implement the GPU skinning backend

**Files to add:**
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidGlResources.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidGpuRenderer.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidRenderTarget.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/HumanoidShaderSources.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidSkinningMathTest.kt`

**Steps:**
- [ ] Use OpenGL ES 3.0 vertex attributes for position/normal/UV plus compact joint indices and weights.
- [ ] Upload skin matrices once per rendered frame; do not rebuild mesh buffers.
- [ ] Keep all skin deformation on the GPU.
- [ ] Separate opaque body draw data from optional musculature draw data.
- [ ] Reuse GPU buffers, shader programs, and uniform locations across frames.
- [ ] Implement a small lifecycle-safe renderer that can be attached/detached without leaking GL resources.
- [ ] Keep shader-side math deterministic and independent of canonical biomechanics.
- [ ] Add JVM tests around matrix/bind-space calculations; reserve device-level tests for actual GL execution.
- [ ] Validate that no per-frame CPU mesh rebuild or vertex traversal exists and commit independently.

### 5. Add independent skeleton and musculature overlays

**Files to add:**
- `app/src/main/java/com/monkfitness/app/animation/humanoid/SkeletonOverlayRenderer.kt`
- `app/src/main/java/com/monkfitness/app/animation/humanoid/MusculatureOverlayRenderer.kt`
- `app/src/test/java/com/monkfitness/app/animation/humanoid/AnatomyVisualizationOptionsTest.kt`

**Steps:**
- [ ] Make skeleton visualization a dedicated overlay built from mapped bone/joint data, not from the body mesh.
- [ ] Make musculature a dedicated optimized mesh layer driven by the same skin matrices.
- [ ] Ensure each renderer can be instantiated only when its corresponding option is enabled.
- [ ] Verify all four option combinations render without changing `HumanoidPoseFrame`.
- [ ] Add isolation tests proving toggling either layer changes only draw participation, never mapped transforms.
- [ ] Keep physiological activation out of the implementation.
- [ ] Commit independently.

### 6. Integrate canonical animation with the Reference Humanoid presentation

**Files to modify:**
- `app/src/main/java/com/monkfitness/app/animation/SkeletonPipeline.kt` only if a typed immutable frame handoff is needed without altering canonical stage ownership
- `app/src/main/java/com/monkfitness/app/ui/components/ExerciseAnimation.kt`
- `app/src/main/java/com/monkfitness/app/ui/screens/ExerciseScreen.kt`

**Files to add:**
- `app/src/main/java/com/monkfitness/app/animation/humanoid/ReferenceHumanoid.kt`
- `app/src/main/java/com/monkfitness/app/ui/components/ReferenceHumanoidView.kt`

**Steps:**
- [ ] Consume the existing `SkeletonPipeline.produceFrame()` output after the canonical pipeline is complete.
- [ ] Map the canonical frame with `HumanoidMapper` and pass the result to a retained `ReferenceHumanoid` instance.
- [ ] Embed the GL surface through Compose without replacing the current renderer contract or breaking existing animation screens.
- [ ] Preserve the existing legacy/2D path as a fallback while the new visualizer is introduced.
- [ ] Add developer-tool plumbing for exactly `showSkeleton` and `showMusculature`; do not add unrelated v1 switches.
- [ ] Keep camera/viewport concerns separate from biomechanical mapping and do not reintroduce exercise-specific pose fitting into the humanoid layer.
- [ ] Ensure a disabled anatomy layer does not allocate/load its assets.
- [ ] Add integration tests around frame handoff and option isolation; use an Android/instrumented smoke test for surface creation when practical.
- [ ] Commit integration separately from prior rendering tasks.

### 7. Add performance and thermal validation

**Files to add:**
- `app/src/test/java/com/monkfitness/app/animation/humanoid/HumanoidPerformanceContractTest.kt`
- `app/src/androidTest/java/com/monkfitness/app/animation/humanoid/ReferenceHumanoidRenderSmokeTest.kt`
- `docs/REFERENCE_HUMANOID_PERFORMANCE.md`

**Steps:**
- [ ] Add contract tests that assert no frame path invokes mesh/rigger reconstruction.
- [ ] Verify optional musculature resources are absent when `showMusculature=false`.
- [ ] Verify Standard/Reduced asset selection does not change `HumanoidMapper` output.
- [ ] Measure sustained rendering behavior on representative target mobile hardware for a long-running animation loop rather than relying only on cold-start FPS.
- [ ] Record draw-call count, skin-matrix upload count, CPU frame cost, GPU frame cost where available, and memory footprint for Standard vs Reduced.
- [ ] Establish a conservative sustained-performance acceptance threshold from observed target-device results; do not claim a device-independent FPS number without measurement.
- [ ] Commit validation documentation/tests separately.

### 8. Full regression, audit, and delivery

**Files to review:** all files touched by Tasks 1–7 plus existing animation tests.

**Steps:**
- [ ] Run the full JVM test suite.
- [ ] Run Android/instrumented smoke tests on at least one supported arm64 device/emulator configuration.
- [ ] Verify existing exercise motion outputs remain unchanged before/after the presentation layer is enabled.
- [ ] Run a representative catalog of existing poses, including trunk articulation, scapular articulation, planted support, and full-body motions.
- [ ] Confirm `SkeletonRenderer` and legacy illustration behavior remain intact unless the new view is explicitly selected.
- [ ] Search for forbidden responsibilities in the humanoid package (`solveIK`, physics/tissue code, CPU vertex loops, per-frame asset rebuilds) and fail the review if found.
- [ ] Review Standard/Reduced asset licensing and package size.
- [ ] Update the specification status from “implementation not yet started” only after the acceptance criteria are objectively verified.
- [ ] Produce a final implementation report with exact tests, device results, and any deliberately deferred visual-quality work.

## Suggested Commit Boundaries

1. `feat(animation): add typed humanoid mapping model`
2. `feat(animation): build reference humanoid rig`
3. `assets(animation): add standard and reduced humanoid assets`
4. `feat(rendering): add gpu-skinned humanoid backend`
5. `feat(rendering): add anatomy overlays`
6. `feat(ui): integrate reference humanoid presentation`
7. `test(perf): validate humanoid runtime contract`
8. `docs(animation): finalize reference humanoid verification`

## Verification Checklist

- [ ] Existing canonical pose output is unchanged when visualized.
- [ ] `HumanoidMapper` is pure and typed.
- [ ] Canonical lumbar maps one-to-many without inventing biomechanics.
- [ ] Scapula is visually independent from arm parenting.
- [ ] Standard and Reduced use the same logical rig/mapping.
- [ ] Skeleton/musculature toggles are independent.
- [ ] Disabled musculature does not load musculature assets.
- [ ] Body deformation is GPU-side.
- [ ] No runtime IK, physics, CPU vertex deformation, muscle simulation, or mesh rebuild exists in v1.
- [ ] Legacy 2D/keyframe path remains intact.
- [ ] Sustained-performance results are documented on representative hardware.
- [ ] Both spec acceptance criteria are demonstrated by tests and runtime evidence.
