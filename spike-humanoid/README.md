# Humanoid spike — Filament runtime against the real Monk motion engine

An isolated, installable second APK (`com.monkfitness.spike`) that renders a skinned 3D humanoid
driven by the app's own pose pipeline. It shares the engine **sources** (it compiles
`app/src/main/java/.../animation` and `.../poses` directly); :app is untouched, and neither the
robot renderer nor any production screen knows this module exists.

```
spike-humanoid/
  src/main/java/com/monkfitness/spike/humanoid/
    HumanoidScene.kt      Filament engine, view, sun + shadow map, swap chain, frame loop
    MonkMotionDriver.kt   exercise selection + progress → SkeletonPipeline → SkeletonPose
    PoseRetargeter.kt     SkeletonPose → bone matrices (rest-delta retarget)
    MonkJointResolver.kt  33 engine joints → glTF bone names
    GltfRig.kt            minimal GLB reader: nodes, joints, inverse binds, morphs
    Mat4.kt               column-major matrix / quaternion math
    SpikeActivity.kt      Compose host: TextureView + exercise/body-shape controls
  src/main/assets/humanoid_spike.glb   generated: 30,720 tris, 32 joints, 5 morph targets
  tools/generate_humanoid_glb.py       the generator for that asset
  ARCHITECTURE.md                      the character-framework proposal
```

## Build

```bash
export JAVA_HOME=<jdk-17> ANDROID_HOME=<android-sdk-34>
./gradlew :spike-humanoid:assembleDebug
./gradlew :spike-humanoid:testDebugUnitTest
```

Filament is pinned to **1.75.1**: 1.76.0+ declares `minCompileSdk=37`, and the app compiles against 34.

## What the JVM tests prove (no device needed)

`PoseRetargeterTest` (5 tests, all green):

- the generated asset is a skinned 30k-triangle humanoid with 32 joints and 5 morph targets;
- all 21 engine limb joints resolve onto the rig by name;
- **the neutral pose reproduces the identity on every bone** — the invariant that only holds when
  joint order, inverse binds, rest rotations and bind space all agree;
- a squat frame moves the hips down and rotates the knees;
- unit scale derived from the rig's rest height is plausible.

## Device test procedure (not run in this environment)

The audit host has no `adb` device and the Shizuku bridge reports its server is not running, so the
APK was built but never launched. To verify on a handset:

1. `adb install -r spike-humanoid/build/outputs/apk/debug/spike-humanoid-debug.apk`
2. Launch **Humanoid Spike**. Expect: a figure on a dark ground plane, lit from the upper left, with a
   contact shadow; the status line shows `rendering · surface ok · 32 bones · 5 morphs · N fps` and
   the device's GLES version and Vulkan level.
3. The figure performs a squat (default), continuously. Switch to **Push-up** and **Plank**: the pose
   must change within a frame, with no engine re-creation and no black frame.
4. Tap **Slim / Heavy / Muscular**: the silhouette changes (shoulder width, chest, waist, limb
   thickness) while the motion continues unchanged.
5. **Pause**, background the app, return: rendering must resume from the same pose, not restart, and
   the surface must be re-created without a crash (watch `logcat | grep -i filament`).
6. Rotate the screen: the viewport and projection must follow, the character must keep its pose.
7. Leave it running 20 minutes: watch for thermal throttling (fps line) and memory growth.

## What is verified, and what is not

Verified here: compiles; JVM contract tests; APK packaging (7.4 MB arm64 Filament native libs + 2 MB
asset); asset structure; retarget math.

Not verified: anything that needs a GPU — first-frame rendering, shadow quality, PBR appearance,
actual fps, surface recreation, context loss, thermals. Those are the device-procedure items above.
