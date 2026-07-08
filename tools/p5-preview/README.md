# p5.js Animation Engine Preview

A standalone, browser-based test harness for the exercise skeleton animation
engine used by the app (`app/src/main/java/com/monkfitness/app/animation`).
It's a straight JavaScript port of the Kotlin math/rendering pipeline
(`Vector3`, 2-bone IK solver, `Camera` projection, `FootDefinition` /
`HandDefinition`, `PerspectiveCompensation`, bone list, easing) built on top
of [p5.js](https://p5js.org/), so new `PoseBuilder` animations can be
prototyped and visually verified in a browser before being ported into
Kotlin.

## Running it

No build step or server is strictly required — just open `index.html`
directly in a browser. If your browser blocks local script loading via
`file://`, serve the folder instead, e.g.:

```bash
cd tools/p5-preview
python3 -m http.server 8000
```

Then visit `http://localhost:8000`.

## What's included

- `js/engine.js` — port of the core engine primitives: `Vector3`,
  `solveIK`/`IKConstraint`, easing helpers, `Camera`, `FootDefinition`,
  `HandDefinition`, `SkeletonDefinition`, `SkeletonStyle`, the `Joint` id
  list, the bone list, and `PerspectiveCompensation` (foot/hand/thickness/
  head perspective correction).
- `js/poses/pushup.js` — port of `PushUpPose.kt`: the example animation for a
  standard push-up, built from a single `progress` value (0 = up/plank,
  1 = down/chest-to-floor).
- `js/sketch.js` — the p5.js `setup()`/`draw()` loop that drives the
  animation (looping progress 0 → 1 → 0, matching `AnimationMode.LOOP`),
  projects the pose with the camera, and renders bones/head/hands/torso/
  ground exactly like `SkeletonRenderer.kt`.
- `index.html` — loads p5.js from a CDN and wires up the scripts above.

## Controls

- **Drag** — orbit the camera (yaw/pitch)
- **Mouse wheel** — zoom in/out
- **Space** — pause/resume the animation

## Adding a new exercise animation

1. Create a new file under `js/poses/your_exercise.js` that exports a
   `buildYourExercisePose(context)` function returning a joints object
   (see `js/poses/pushup.js` for the pattern), where `context` is
   `{ progress, definition }`.
2. Include the script in `index.html` and swap the call in `sketch.js`
   (`MonkPoses.buildPushUpPose(context)`) for your new builder.
3. Once verified visually, port the pose builder logic into a Kotlin
   `PoseBuilder` implementation under
   `app/src/main/java/com/monkfitness/app/poses/` and register it in
   `AnimationRegistry.kt`.
