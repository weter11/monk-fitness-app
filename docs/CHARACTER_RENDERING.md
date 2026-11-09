# Filament character presentation — Stage 2

Status: production integration behind a disabled-by-default flag, plus device-validation tooling.
No final character art, no complete humanoid framework, no change to workout/session/program logic.

Baseline: `main` at `566865e` (the base of PR #384, whose head `fd549bd` carried the Stage-1 spike).

---

## 1. What was promoted, and what was not

The Stage-1 spike lived in a second application module (`spike-humanoid`) that compiled the engine
by pointing `sourceSets` at `app/src/main/java/.../animation` and `.../poses`. That module is not
part of this branch; its runtime code was promoted into `:app` instead, so the repository has one
application module, one Filament dependency and one copy of every engine source.

| Stage-1 spike | Stage 2 |
| --- | --- |
| `spike-humanoid` app module, `com.monkfitness.spike` | `:app`, package `com.monkfitness.app.character` |
| engine sources re-compiled via `srcDir` | engine consumed as ordinary `:app` sources |
| `HumanoidScene` (one screen, no ownership rules) | `CharacterSession` + `ActiveCharacter` slot |
| `MonkMotionDriver` (its own clock) | the 2D `AnimationController` and pose builders (one clock) |
| no backend choice | `BackendRequest` + real attempt/fallback + diagnostics screen |
| asset `humanoid_spike.glb` | `app/src/main/assets/characters/humanoid_v1.glb` |
| generator in the spike module | `scripts/character/generate_humanoid_v1.py` |

Nothing under `animation/`, `poses/`, `domain/`, `data/`, `viewmodel/` or the existing 2D
`SkeletonRenderer` changed. The character package is the only new consumer of Filament.

Four spike shortcuts did not survive promotion:

- `MonkMotionDriver` had its own clock and its own loop-mode interpretation; the presenter reads the
  app's `AnimationController` instead, so the character cannot drift from the 2D demo.
- `HumanoidScene` excluded the ground mesh by name; the presenter reads the glTF node's `skin`
  reference (`GltfRig.skinnedNodes`) and never depends on an authored name.
- Unit scale was calibrated from the displayed exercise's first frame; it now comes from a standing
  reference frame, so a plank is not scaled up like a squat.
- The spike printed capability flags; the presenter attempts the backend it was asked for, records
  the failure, falls back, and reports `Engine.getBackend()`.

## 2. Integration seam

```
PoseBuilder.build(PoseContext)                  unchanged
  → SkeletonPipeline.produceFrame(...)          unchanged (solver → finalizer)
    → SkeletonPose                              unchanged, reused carrier
        ├── SkeletonRenderer (2D)               unchanged, still the default and the fallback
        └── CharacterStage → CharacterSession    new
              → PoseRetargeter → bone matrices → RenderableManager.setBonesAsMatrices
```

`ExerciseVisualContent` in `ui/components/CommonComponents.kt` stays the application-level seam. It
gains one parameter, `liveCharacter`, which defaults to `false`; `ExerciseHeroMedia` forwards it.
Only two call sites pass `true`: the exercise detail hero and the expanded session demo. Exercise
lists, thumbnails and the inline session preview keep the 2D renderer.

The character consumes `SkeletonPose` before any 2D projection: there is no `ProjectedSkeleton`,
`CameraFraming`, `ScreenSpaceCompensation` or `SkeletonStyle` on this path. The pose is produced by
the same `rememberAnimationController` and pose builder the 2D renderer reads, so the two
presentations cannot drift apart and the 3D path owns no clock of its own.

## 3. Feature flag

`BuildConfig.FILAMENT_CHARACTER_ENABLED` is a Boolean build-config field, `false` unless the build
passes the Gradle property:

```bash
./gradlew :app:assembleDebug -Pmonk.filamentCharacter=true
```

The flag gates only the presentation choice. With it off (every checked-in configuration and CI),
`ExerciseVisualContent` takes the original branch and no Filament class is loaded, no engine is
created and no asset is read. `CharacterFeatureTest` asserts the default.

Shipping the native libraries and the 2.0 MB test asset in `:app` does change APK size (see §9);
splitting them into a dynamic feature or product flavor is a later, separate change.

## 4. Ownership and lifecycle

- `CharacterSession` owns everything it creates: `Engine`, `Renderer`, `Scene`, `View`, `Camera`,
  `SwapChain` (through `UiHelper`), `AssetLoader`, the asset and the light. `destroy()` is
  idempotent and is the only teardown path.
- `ActiveCharacter` / `CharacterSlot` is a process-wide single-owner guard. A second view that asks
  for the character is refused and renders 2D; releasing is identity-checked, so a disposed view
  cannot release a slot another view now holds.
- The frame loop is a `Choreographer` callback started on `ON_RESUME` and stopped on
  `ON_PAUSE`/`ON_STOP`, and `UiHelper.onDetachedFromSurface` also stops it: a `beginFrame` on a dead
  swap chain is a crash, not a dropped frame.
- Engine and asset are created once per composition, destroyed in `onDispose`. Activity recreation
  (rotation, backend change, the diagnostics "Recreate activity" button) is therefore a full engine
  shutdown and recreation, which is one of the validation cases.
- Engine creation is synchronous on the main thread for this stage. That costs a frame or two on a
  cold screen entry; moving it to a worker with a 2D placeholder is a later change, not a bug.

## 5. Backend selection

`BackendRequest` has three values, used by the diagnostics screen and by tests:

| Request | Attempts, in order |
| --- | --- |
| `AUTO` (production) | `DEFAULT` (Filament chooses) |
| `VULKAN` | `VULKAN`, then `OPENGL` |
| `OPENGL` | `OPENGL`, then `DEFAULT` |

Rules:

- Capability probes (`FEATURE_VULKAN_HARDWARE_VERSION`, `FEATURE_VULKAN_HARDWARE_LEVEL` on API 29+,
  `reqGlEsVersion`) are reported, never trusted to decide. An API level does not prove a Vulkan
  driver works.
- An explicit Vulkan request is always attempted. A thrown initialisation (missing JNI, rejected
  driver) is recorded verbatim and the next attempt runs; that is the GLES fallback.
- The backend reported as active is `Engine.getBackend()` — Filament's own answer for the engine it
  built — not the request. A device that advertises Vulkan but falls back will show the fallback.
- Vulkan is never mandatory. `AUTO` is the production default.

`CharacterBackendTest` proves the attempt order, the fallback, and that only a real Vulkan engine is
reported as Vulkan; a physical device is still required to know what a given driver does.

## 6. Morphology

`MorphologyPreset` (neutral/slim/heavy/muscular) is a vector of morph weights applied with
`RenderableManager.setMorphWeights` to each skinned renderable. It never rebuilds the engine, never
touches bone matrices and never changes the rig: a 100° knee is 100° on every preset. The renderable
target count is read from gltfio, so a preset with more weights than the asset has cannot overrun.

Joint-level correctness is proven on the JVM (rest invariant, squat movement, joint mapping).
Shoulder/elbow/hip/knee *surface* deformation, penetration and anatomical plausibility are **not**
proven by those tests and require the device checklist.

## 7. Thumbnails

Implemented now: lists and thumbnails keep the existing 2D renderer. No Filament engine, asset or
bitmap is created for a row.

Proposed for the next stage (`CharacterThumbnails` carries the key and budget):

- One process-wide, non-animated engine renders a single frame per key offscreen
  (`Engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE)` + `Renderer.readPixels`) on a
  worker thread, once, then the bitmap goes into an LRU cache.
- Key: `characterId`, `morphology`, `animationId`, `qualityTier`, quantised `poseProgress`, and
  `assetVersion`. Changing any of them is a miss; bumping `ASSET_VERSION` invalidates the set.
- Budget: 192 px RGBA (147 KB) × 24 entries ≈ 3.5 MB, under a 4 MB cap.
- Any failure falls back to the 2D thumbnail. No per-row engine, no animated thumbnails, no
  multi-character scheduler.

## 8. Performance policy

Measured on device, through the diagnostics overlay: successful frames, frame count, smoothed FPS,
p95 CPU frame time (last 120 frames), and whether a surface is attached. No milliwatt or battery
criteria. 30 FPS is the floor and 60 FPS the preference, but this document claims neither: there is
no device in the build environment.

## 9. Known costs and risks

1. **APK size.** Filament JNI adds ~6.0 MB of arm64 native code and the test GLB 2.0 MB. Disabled by
   default but still packaged. A dynamic feature module or flavor split is the next packaging step.
2. **Two heroes, one slot.** The expanded session demo and the session preview are never live at
   the same time by construction (only the dialog passes `liveCharacter = true`), but a future screen
   that mounts two heroes would leave the second on 2D. That is the intended degradation.
3. **Per-frame allocation.** `PoseRetargeter` allocates its world arrays per frame (~32 quaternions
   and vectors). Acceptable at this stage; the `out` buffer for matrices is already reused.
4. **Vulkan driver variance.** Unverified. The probe reports, the attempt decides, GLES is the
   fallback, and `AUTO` never forces Vulkan.
5. **Volume plausibility.** Interpenetration and contact with benches/bars/walls are not modelled.
   Morph targets use region masks in the test asset; real art needs corrective targets.
6. **Asset calibration.** The engine→asset scale is derived from a standing frame (not the displayed
   exercise). A production rig must keep the bind pose equal to the engine's rest stance, or the
   first frame of every exercise will be visibly wrong.
7. **Toolchain.** Filament 1.76+ requires `compileSdk 37`; the app is on 34 / AGP 8.5, so the
   dependency is pinned to 1.75.1. Upgrading is a separate change.

## 10. Next stage (proposed)

1. Device matrix: record backend, FPS, p95 frame time, memory and defects on at least one GLES and
   one Vulkan device; then decide whether `AUTO` stays GLES-first.
2. Static thumbnails as described in §7, measured against the 2D fallback.
3. Production rig and three body families with corrected morph masks; mesh-level joint inspection.
4. Only then: animation state (idle ↔ set ↔ rest, cross-fade), quality tiers and packaging split.
