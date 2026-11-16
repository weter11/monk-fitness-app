# Universal Humanoid Character Framework — architecture proposal

Status: proposal produced by the Stage-1 Filament spike. Nothing here is implemented in the
production app; the spike module is the only code that exercises it.

---

## 1. Runtime: Filament, used directly (not through SceneView)

**Decision: `com.google.android.filament:filament-android` + `gltfio-android`, driven by our own
`UiHelper` + `TextureView` host.**

Reasons, in order of weight:

1. **We drive bones, not clips.** The character is posed from `SkeletonPose` every frame, not from a
   glTF animation. That needs `RenderableManager.setBonesAsMatrices`, the skin's joint order, the
   inverse bind matrices and `setMorphWeights` — Filament surfaces, not the model/node API SceneView
   exposes.
2. **One engine, many surfaces.** `Engine`, `Scene` and the material cache must outlive a single
   composable (preview → dialog → background). SceneView ties engine lifetime to composition.
3. **Fewer moving parts on the critical path.** SceneView is a fast-moving third-party layer
   (4.36 → 4.67 in the time this spike was written); it remains a good tool for a developer model
   browser, not for the production character contract.

Verified constraints from this spike:

| Fact | Value |
| --- | --- |
| Newest Filament usable on the app's toolchain | **1.75.1** — 1.76.0+ sets `minCompileSdk=37`, the app is on `compileSdk 34` / AGP 8.5 |
| Native payload (arm64, stripped by AGP) | 7.4 MB (`filament-jni` 3.15, `gltfio-jni` 2.84, `filament-utils-jni` 1.44) |
| Debug APK with one 2 MB test character | 18.8 MB |
| Backends | OpenGL ES 3.0+ and Vulkan 1.0 (Filament); Vulkan is **not** implied by API level |

**Backend policy.** Ship `Backend.DEFAULT` (Filament picks), with an explicit probe before ever
forcing Vulkan: `FEATURE_VULKAN_HARDWARE_VERSION` / `FEATURE_VULKAN_HARDWARE_LEVEL` (API 29+) and the
`reqGlEsVersion` fallback. The spike shows both values on screen; it does not force a backend,
because a Vulkan request on a device whose driver lacks the required extensions fails at engine
creation — the failure mode the Filament issue tracker documents for PowerVR-class drivers.

---

## 2. Integration seam

The engine keeps producing the same thing it produces today; the character is a second consumer of
it, downstream of the pose and upstream of any projection:

```
PoseBuilder.build(PoseContext)              // unchanged
  → SkeletonPipeline.produceFrame(...)      // unchanged (solver → finalizer)
    → SkeletonPose                          // unchanged
        ├── SkeletonRenderer      (2D vector, unchanged, stays as fallback + thumbnails)
        └── CharacterPresenter    (new)
              → PoseRetargeter → bone matrices → Filament renderable
```

The whole contract between engine and presentation is one function:

```kotlin
fun interface CharacterPoseSink {
    /** Called once per produced frame. The pose is a reused carrier: consume before the next frame. */
    fun onPose(pose: SkeletonPose)
}
```

No `ProjectedSkeleton`, `CameraFraming`, `ScreenSpaceCompensation` or `SkeletonStyle` on this path.
The 2D path is untouched and remains the fallback, the validation snapshot renderer and the
thumbnail renderer.

**Carrier-reuse constraint (found by this spike).** `SkeletonPipeline` publishes into a reused
`SkeletonPose`; two frames cannot be held at once. A 3D presenter must upload bone matrices inside
the same call that produced the frame — which is what the spike does.

---

## 3. Shared skeleton and the mapping contract

One canonical rig, `humanoid_v1`: ~32–40 deform bones, Mixamo-compatible naming.

| Layer | Bones | Driven by |
| --- | --- | --- |
| Core (33 engine joints) | hips, spine, chest, neck, head, clavicles, arms, forearms, hands, thighs, shins, feet, toes | `SkeletonPose` rotations |
| Visual-only | 5 fingers × 2–3 segments per hand, toe fan, twist bones (upper arm, forearm, thigh), jaw, eyes | parent motion + optional authored offsets |

Mapping is data, not code:

```kotlin
class BoneMap(
    val byJoint: Map<Joint, String>,          // resolved once per asset
    val distribution: Map<Joint, List<Pair<String, Float>>>, // e.g. LUMBAR → [Spine1 0.6, Spine2 0.4]
    val basis: Quat                            // source→target axis correction, identity for Y-up/+Z-forward
)
```

Rules:

- **Rest-delta retarget.** `R_target_world = R_source_world × R_target_rest_world`. The engine's rest
  convention is identity local rotation, so the source term is the published world rotation directly.
- **Bind pose = engine rest stance.** The character is bound with the arms at its sides (the
  engine's rest configuration), not in a T-pose. A T-pose bind leaves the figure in T-pose at every
  exercise's starting frame, because the retarget applies source deltas to the target's rest rather
  than solving an absolute orientation per bone.
- **Rotations only.** Bone lengths, rest offsets and bind pose come from the asset. Morphology never
  changes a rotation, so a 100° knee is 100° on every character.
- **One-to-many spine.** `LUMBAR` (and optionally `CHEST`) can be split across two visual segments by
  weight; the spike maps 1:1 and the contract leaves room for the split.
- **Unmapped is legal.** A joint with no matching bone leaves that bone at rest; a rig with no
  fingers still animates correctly.
- **Inverse binds are supplied by us.** gltfio does not expose them, so the presenter parses the GLB
  (`GltfRig`) and computes `inverse(meshWorld) × jointWorld × inverseBind` — the same product
  gltfio's `Animator` computes.

Validated in the spike by the rest invariant: feeding the neutral pose must reproduce the identity on
every bone. It does, on a 32-joint generated rig (5/5 JVM tests green).

---

## 4. Morphology

**Morph targets, not bone scaling.** Scaling bones stretches limb segments and breaks joint volume;
morph targets move surface vertices under an unchanged skeleton.

Parameter set, all expressed as morph weight vectors over a small set of regional targets:

| Parameter | Technique |
| --- | --- |
| Shoulder width | regional X displacement around the girdle (spike target 1) |
| Chest / torso volume | radial displacement of the rib-cage band (target 2) |
| Waist / abdomen | radial displacement of the lumbar band (target 3) |
| Arm / leg thickness | radial displacement around limb axes (target 4) |
| Height / limb length | **rig-level**: authored proportions in the bind pose, not a morph — limb lengths interact with IK and must be consistent between rig and mesh |
| Muscle definition | separate high-frequency targets (deltoid, pec, quad) layered on top |
| Head / face | head-scale morph + face blendshape set (ARKit-compatible), independent of body morphs |

What keeps joints acceptable:

1. **Region masks.** Deltas fall to zero inside a radius around shoulder, elbow, hip and knee pivots,
   so the silhouette changes while the joint's ring of vertices does not.
2. **Corrective targets.** A small set of pose-driven correctives (arm-raise, hip-flexion) added on
   top of the body morph, evaluated from the same `SkeletonPose` rotations.
3. **One rig, many bodies.** Every character shares the rig and bind pose; only the mesh, the morph
   set and the materials differ, so the motion library stays shared.

The spike demonstrates three presets (`slim`, `heavy`, `muscular`) from five targets on one rig.

---

## 5. Character configuration

```json
{
  "id": "male_heavy_v1",
  "rig": "humanoid_v1",
  "mesh": "characters/male_heavy/standard.glb",
  "morphology": { "shoulderWidth": 0.15, "chestVolume": 0.85, "waist": 1.0, "limbThickness": 0.8 },
  "face": { "mesh": "faces/male_02.glb", "blendshapes": "arkit_52" },
  "hair": "short_01",
  "materials": { "skin": "skin_03", "roughness": 0.55 },
  "clothing": ["tee_black", "shorts_grey"],
  "quality": "standard"
}
```

Adding a character is adding a configuration plus its assets. No animation data is duplicated: the 46
catalogue exercises stay procedural and drive every character through the same `SkeletonPose`.

---

## 6. Asset format and pipeline

- **Format:** GLB (glTF 2.0 binary), one file per character tier, morph targets included, textures
  ASTC-compressed inside the GLB.
- **Authoring:** one master rig (`humanoid_v1`), exported from the DCC tool; every character is
  skinned to that rig. Morph targets authored once per body family, not per exercise.
- **Offline step:** validate against the `BoneMap` (every required joint present, bind pose == rest
  pose, weight count ≤ 4, triangle and texture budgets per tier), bake LODs, write a manifest with
  provenance/licence.
- **Runtime:** `gltfio` `AssetLoader` + `ResourceLoader`; assets are memory-mapped and must stay
  uncompressed in the APK (`androidResources { noCompress += "glb" }` — the spike does this).
- **Tiers:** `standard` (~30–40k tris, 2K textures) and `reduced` (~12k tris, 1K). Same rig, same
  mapper, same animations.

---

## 7. Performance policy

- **30 fps** minimum tier, **60 fps** preferred target — both to be established on real devices, not
  promised here.
- One active character; a single shared `Engine`; thumbnails are **cached static bitmaps**, never
  additional animated GPU instances (the 2D list currently animates a skeleton per row — a 3D path
  cannot afford that).
- Shadow: one directional light, one shadow map. No post-processing beyond tone mapping in v1.
- No power-in-milliwatt acceptance criteria. Measure frame time, jank and thermal drift on devices.

---

## 8. Roadmap

| Stage | Work | Estimate |
| --- | --- | --- |
| 0 (done) | Filament spike, pose→bone contract, generated test rig | — |
| 1 | Promote the presenter into the app behind a flag; static thumbnails; shared engine | 2–3 wk |
| 2 | Production rig + 3 body families with morphology targets | 4–6 wk + art |
| 3 | Materials/lighting/quality tiers, device matrix, 30/60 fps evidence | 3–4 wk |
| 4 | Animation state layer (idle ↔ set ↔ rest, cross-fade between exercises) | 3–5 wk |
| 5 | Face/hair/accessories, character selection UI | 3–4 wk |

---

## 9. Open risks

1. **Volume plausibility.** The engine validates joints, not surfaces: limb interpenetration and
   bench/bar/wall contact are unmodelled and will need corrective targets or a mesh-level check.
2. **Vulkan driver variance.** Probe, never assume. Keep the GLES path as the default until a
   device matrix says otherwise.
3. **Context loss.** Filament recreates swap chains on surface changes; a full GL context loss still
   needs a documented recovery path (engine re-create + asset reload). Untested here — no device.
4. **Retarget calibration.** Basis and bone map are per-asset data. A wrong basis looks like a
   "broken rig" and is cheap to fix only if it is data, which is why it is not hard-coded.
5. **Toolchain.** Filament 1.76+ requires `compileSdk 37`; the app is on 34 / AGP 8.5. Upgrading is a
   separate, testable change.
