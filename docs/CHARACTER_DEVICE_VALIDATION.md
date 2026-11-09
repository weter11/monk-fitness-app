# Filament character — device validation procedure

Stage 2 ships the presenter behind a flag. There is no GPU device in the build environment, so
**none of the checks below have been executed by the agent**. The automated tests cover pose
retargeting, backend fallback policy, single-owner lifecycle and the flag default; everything that
needs pixels, a driver or a lifecycle transition is for a physical device and must be recorded, not
assumed.

## 1. Build the test APK

From the repository root, with the platform toolchain on `PATH` (see `AGENTS.md`):

```bash
./gradlew :app:assembleDebug -Pmonk.filamentCharacter=true
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The flag build is the only one that shows the character. Without
`-Pmonk.filamentCharacter=true` the same APK is the unchanged 2D application, which is the
production default and the control for "nothing else moved".

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK contains a second launcher entry, **Character diagnostics**, and the activity

```
com.monkfitness.app/.character.CharacterDiagnosticsActivity
```

```bash
adb shell am start -n com.monkfitness.app/.character.CharacterDiagnosticsActivity
adb logcat -c && adb logcat | grep -iE "filament|monkfitness|AndroidRuntime"
```

## 2. What the diagnostics screen shows

- `requested <Auto|Vulkan|OpenGL ES> → active <backend>` — `active` is
  `Engine.getBackend()`, i.e. what Filament actually built, not what was asked for.
- `fallback: <backend> failed: <reason>` when an explicit request failed and GLES took over.
- `device API n · GLES x.y · Vulkan <version, level | not advertised | not probeable below API 29>`.
- `N bones · M morphs · <preset>`, frame count, smoothed FPS, p95 CPU frame time, surface/running.
- Chips: backend (Auto / Vulkan / OpenGL ES), exercise (Squat / Push-up / Plank), morphology
  (neutral / slim / heavy / muscular). Changing the backend restarts the activity, because a
  Filament backend is fixed for the life of an engine.
- Buttons: **Recreate activity** (full lifecycle + engine recreation) and **Rebuild engine**
  (restart with the same request).

Record the whole status block by long-pressing to select and copy, or photograph it.

## 3. Checklist

Run in order on each device. "Pass" means the stated observation was actually seen.

| # | Case | Steps | Pass looks like | Record |
| --- | --- | --- | --- | --- |
| 1 | Cold start | Force-stop the app (`adb shell am force-stop com.monkfitness.app`), launch diagnostics | Screen reaches `surface true · running true` and a character is visible; no crash | time to first frame, any logcat exception |
| 2 | First character render | Stay on Squat, Auto backend | Humanoid visible, material lit (not black), shadow present, no mesh at origin/T-pose | screenshot, defects |
| 3 | Squat | Squat chip | Hips descend, knees bend, feet stay planted; motion matches the 2D demo in the app | joint defects (knee/hip collapse, penetration) |
| 4 | Push-up | Push-up chip | Body lowers/raises around the hands, elbows bend, no arm/floor slicing | defects |
| 5 | Plank | Plank chip | Static hold with small breathing motion; forearms on the floor | defects, any floating/sinking |
| 6 | Slim | Morphology slim | Silhouette narrows; shoulders/elbows/hips/knees keep volume; no engine restart (frame count keeps rising) | screenshot, deformation defects |
| 7 | Heavy | Morphology heavy | Torso/waist widen; no limb interpenetration at joints | screenshot, penetration |
| 8 | Muscular | Morphology muscular | Shoulder/limb volume rises; neutral pose still upright when switching back to neutral | screenshot |
| 9 | Background / foreground | Home, wait 5 s, return | Rendering pauses in background (no frames, no crash), resumes on return without a black view | resume time, defects |
| 10 | Screen rotation | Rotate device with auto-rotate on | Activity recreates, character returns upright, correct aspect, no stretched view | rotation count survived, defects |
| 11 | Surface recreation | Enter split-screen and resize, or turn screen off/on | Character returns after the surface is recreated; `surface` goes false then true; no crash | logcat around detach/attach |
| 12 | Repeated exercise switching | Tap Squat → Push-up → Plank → Squat ×10, 1 s apart | Every switch shows the new motion; frame count keeps increasing; memory does not grow monotonically | start/end `dumpsys meminfo com.monkfitness.app` |
| 13 | Renderer shutdown / recreation | **Recreate activity** ×5, then **Rebuild engine** ×5 | Every recreation renders again; process stays alive; no native crash | `adb shell pidof com.monkfitness.app` stable, logcat |
| 14 | Explicit Vulkan | Backend chip Vulkan | Either `active VULKAN` and a correct render, or a recorded fallback reason and `active OPENGL` with a correct render | exact status line |
| 15 | Explicit OpenGL ES | Backend chip OpenGL ES | `active OPENGL`, correct render | exact status line |
| 16 | Short session | Run Squat continuously 5 minutes | No crash, no thermal shutdown; note FPS drift and jank | p95 ms at 0/2/5 min, battery/thermal observation (qualitative) |

Also open the normal app (Exercise detail hero, and the expanded demo from a program session) with
the flag build, and confirm the same character appears there while exercise lists stay 2D. Then
install a build without the flag property and confirm the app looks exactly like the 2D baseline.

## 4. Record sheet

Copy this per device:

```
Device model:
Build fingerprint:            adb shell getprop ro.build.fingerprint
Android version / API:
SoC / GPU:                    adb shell getprop ro.board.platform
                              adb shell dumpsys SurfaceFlinger | grep -iE "GLES|Vulkan"
Filament backend requested:
Filament backend active:
Fallback reason (if any):
Engine + asset initialised:   yes / no
Squat / Push-up / Plank:      pass / fail + notes
Slim / Heavy / Muscular:      pass / fail + notes
Background / foreground:      pass / fail + notes
Rotation:                     pass / fail + notes
Surface recreation:           pass / fail + notes
Repeated switching:           pass / fail + notes
Shutdown / recreation:        pass / fail + notes
Visual defects:               joints, silhouette, penetration, shadow, colour
Crashes / recovery:           stack trace, whether the 2D fallback appeared
Frame time / FPS (p95 ms):    at 0 / 2 / 5 minutes
Memory (PSS start → end):
```

## 5. Interpreting a failure

- **No character, 2D animation instead.** Check logcat for `gltfio`, `Engine.create` or
  `FILAMENT_CHARACTER_ENABLED`. The presenter deliberately falls back to 2D on any creation or load
  failure; the diagnostics screen prints the reason in `unavailable: …`.
- **Character black.** Indirect light or material issue, not a pose issue. Record the device/backend.
- **Character in T-pose or at the origin.** Bind-pose or joint-order mismatch; the JVM rest
  invariant should catch it before the device does.
- **Native crash during rotation or backgrounding.** Capture the full tombstone; the swap-chain
  detach path is the first suspect.
- **Vulkan requested, `active OPENGL`, with a reason.** That is the fallback working. Record the
  reason; do not report Vulkan as active.

## 6. What the agent could and could not run

Executed in the build environment (see PR body for the exact commands and output):

- `:app:testDebugUnitTest` — full unit suite, including the new character tests.
- `:app:assembleDebug` with the flag off (CI-equivalent) and on (device APK).
- JVM: pose retargeting on the real GLB, rest invariant, joint mapping, backend attempt/fallback
  policy, single-owner slot, thumbnail cache key, flag default.

Requires a physical device (not executed, not claimed):

- anything in the checklist above: rendering, driver behaviour, backend actually selected per
  device, surface/lifecycle recovery, frame time, memory, thermal behaviour.
