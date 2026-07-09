# p5.js Push-Up Animation Preview

`push-up.js` is a self-contained JavaScript port of the app's animation
engine (`app/src/main/java/com/monkfitness/app/animation/*.kt`) merged with
the push-up pose builder (`app/src/main/java/com/monkfitness/app/poses/PushUpPose.kt`),
plus a small p5.js `setup()`/`draw()` sketch, so you can preview and iterate
on the push-up exercise animation directly in the browser without running
the Android app.

## How to use

1. Open https://editor.p5js.org/
2. Create a new sketch.
3. Replace the contents of `sketch.js` with the full contents of
   `push-up.js` from this folder.
4. Run the sketch. You should see a looping push-up animation rendered with
   the same joint hierarchy, IK solver, and camera projection math used by
   the app.

## Notes

* This file intentionally avoids p5.js reserved global identifiers (e.g.
  `width`, `height`, `color`, `focus`, `frameCount`, `key`, `mouseX`/`mouseY`)
  in its own local variable/parameter names — for example `Camera.project`
  takes `canvasW`/`canvasH` instead of `width`/`height`, and colors are
  produced via a `makeColor()`/`getZColor()` helper instead of a variable
  named `color`.
* The animation loop mirrors `rememberAnimationController`'s non-alternating
  `LoopMode.LOOP` behavior: progress eases 0 → 1 → 0 over
  `PushUpPose.metadata.durationSeconds` using an ease-in-out curve.
* This is a preview/testing tool only — it is not wired into the Android
  app build and has no effect on production code paths.
