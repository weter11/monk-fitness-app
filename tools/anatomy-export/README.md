# anatomy-export

Offline helper-bind export for path-rigged muscle layers. It runs the runtime's own
`AnatomySolver` against the X1 canonical A-pose and writes the bind frame of the four helper bones
that each muscle head carries — origin, belly proximal, belly distal, insertion, at 0/30/70/100 % of
the head's rest path by arc length.

Nothing here re-derives anatomy: the attachments, the wrap detours and the rest lengths come from
the same solver the app draws with, so an exported layer cannot bind to geometry the runtime does
not report.

## Run

From the repository root:

```bash
./gradlew :tools:anatomy-export:run
```

or with explicit paths:

```bash
./gradlew :tools:anatomy-export:run --args="--root ../.. --out app/src/test/resources/anatomy/helpers_v1.json"
```

| Option | Default |
| --- | --- |
| `--root` | `../..` (repository root) |
| `--anatomy` | `<root>/app/src/main/assets/anatomy/anatomy_v1.json` |
| `--rig` | `<root>/app/src/main/assets/characters/humanoid_v2.glb` |
| `--out` | `<root>/app/src/test/resources/anatomy/helpers_v1.json` |

## Output

`app/src/test/resources/anatomy/helpers_v1.json` is checked in, because it is the contract
`HelperBindTest` validates: every helper must sit on a fresh rest solve's path within `1e-3` m,
carry a rotation-only (unit, orthogonal, right-handed) basis whose `+Y` follows the path, land
inside `humanoid_v2.glb`'s own body envelope, and keep the skin's palette (`rig joints + helpers`)
inside the 255 the renderable accepts. **Regenerate it whenever the solver, the anatomy asset or the
rig changes** — the test fails on a stale manifest.

Positions and bases are written in the **rig's asset space**, not the solver's engine units: every
point is `B⁻¹ · p / unitsPerMetre` and every basis axis is `B⁻¹ · a`, the same conversion
`PoseRetargeter` applies when it drives the 34-joint skeleton. A helper therefore sits in the same
metres and the same axes as the GLB's own joints, and a manifest left in engine units misses the
body by ~247× — which the metre bounds in `HelperBindTest` fail on rather than a skinning step
silently binding off the body.

The tool refuses to write a manifest it cannot place: a head the rest stance refused has no bind, a
non-finite rest length is refused before it can be serialised as invalid JSON, and a palette over
255 fails before the file is written.

## Why a JVM module

The exporter compiles the pure-Kotlin packages (`anatomy`, `animation`, `poses`, and the character
bind/rig readers) straight out of the app module — see `build.gradle.kts`. It does not depend on
`:app`, so the Android plugin and Filament stay out of the tool, and there is no second
implementation of the path maths to drift.
