# anatomy-export

Offline helper-bind export for path-rigged muscle layers. It runs the runtime's own
`AnatomySolver` against the X1 canonical A-pose and writes the bind frame of the four helper bones
that each muscle head carries — origin, belly proximal, belly distal, insertion, at 0/30/70/100 % of
the head's rest path by arc length.

Nothing here re-derives anatomy: the attachments, the wrap detours and the rest lengths come from
the same solver the app draws with, so an exported layer cannot bind to geometry the runtime does
not report.

## Run

From the repository root, in this order — the mesh binds to the manifest, so the manifest is written
first:

```bash
./gradlew :tools:anatomy-export:exportHelpers     # app/src/main/assets/anatomy/helpers_v1.json
./gradlew :tools:anatomy-export:exportMuscles     # app/src/main/assets/characters/muscles_sup.glb
```

or with explicit paths:

```bash
./gradlew :tools:anatomy-export:run --args="--root ../.. --out app/src/main/assets/anatomy/helpers_v1.json"
```

| Option | Default |
| --- | --- |
| `--root` | `../..` (repository root) |
| `--anatomy` | `<root>/app/src/main/assets/anatomy/anatomy_v1.json` |
| `--rig` | `<root>/app/src/main/assets/characters/humanoid_v2.glb` |
| `--out` | `<root>/app/src/main/assets/anatomy/helpers_v1.json` |

`MuscleMeshExport` takes `--root`, `--anatomy`, `--rig`, `--helpers` and `--out`
(`<root>/app/src/main/assets/characters/muscles_sup.glb`).

## Output

`app/src/main/assets/anatomy/helpers_v1.json` is checked in, because it is the contract
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

## The muscle asset

`muscles_sup.glb` is one skinned section per muscle and side — sixteen meshes named
`<muscleId>_<side>`, one primitive each — over a 106-joint skin: the rig's own 34 joints, whose
inverse binds are copied from `humanoid_v2.glb`, followed by the 72 helpers in manifest order. A
vertex carries at most two influences; every ring between two stations is weighted between the two
helpers that own that arc fraction.

It is a deterministic stand-in, not art. The rings sit on the solver's rest path and their frames are
slerped between the two helpers' bind frames, which is the same blend linear-blend skinning applies,
so the surface is where the skin will put it. `HelperBindTest` gates it: inverse binds equal the
manifest within `1e-3 m`, uniform scale 1 on every helper, at most four influences per vertex,
finite unit normals and tangents, and no triangle facing into the surface. The one exception is
gluteus maximus, whose rest path doubles back through 125° between two stations: a tube of the
muscle's own radius cannot follow that without folding, so its fold is held to a measured area and
disappears when authored geometry replaces this mesh.

## Why a JVM module

The exporter compiles the pure-Kotlin packages (`anatomy`, `animation`, `poses`, and the character
bind/rig readers) straight out of the app module — see `build.gradle.kts`. It does not depend on
`:app`, so the Android plugin and Filament stay out of the tool, and there is no second
implementation of the path maths to drift.
