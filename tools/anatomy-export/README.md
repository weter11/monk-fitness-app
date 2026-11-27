# anatomy-export

Offline helper-bind export for path-rigged muscle layers. It runs the runtime's own
`AnatomySolver` against the X1 canonical A-pose and writes the bind frame of the four helper bones
that each muscle head carries — origin, belly proximal, belly distal, insertion, at 0/30/70/100 % of
the head's rest path by arc length.

Nothing here re-derives anatomy: the attachments, the wrap detours and the rest lengths come from
the same solver the app draws with, so an exported layer cannot bind to geometry the runtime does
not report.

## Run

From the repository root, in this order — the meshes bind to the manifest, so the manifest is written
first:

```bash
./gradlew :tools:anatomy-export:exportHelpers     # app/src/main/assets/anatomy/helpers_v1.json
./gradlew :tools:anatomy-export:exportMuscles     # muscles_sup.glb and muscles_deep.glb
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

`MuscleMeshExport` takes `--root`, `--anatomy`, `--rig`, `--helpers`, and one output per layer:
`--sup-out` defaulting to `<root>/app/src/main/assets/characters/muscles_sup.glb` (the Stage-3 name,
still reachable as `--out`) and `--deep-out` to
`<root>/app/src/main/assets/characters/muscles_deep.glb`.

## Output

`app/src/main/assets/anatomy/helpers_v1.json` is checked in, because it is the contract
`HelperBindTest` validates: every helper must sit on a fresh rest solve's path within `1e-3` m,
carry a rotation-only (unit, orthogonal, right-handed) basis whose `+Y` follows the path, land
inside `humanoid_v2.glb`'s own body envelope, and keep its layer's palette
(`rig joints + that layer's helpers`) inside the 255 the renderable accepts. **Regenerate it
whenever the solver, the anatomy asset or the rig changes** — the test fails on a stale manifest.

The manifest is **schema 2**: every helper is tagged with the `MuscleLayer` it skins, and a
`partitions` array records, per layer, its first helper, its helper count and its palette. The atlas
holds 288 helpers — 52 superficial head-side paths and 20 deep ones, four stations each — and
`34 + 288` is over the limit, so no single skin can bind the whole library. The partitions are what
make it exportable: `34 + 208 = 242` superficial and `34 + 80 = 114` deep. A schema-1 manifest (one
unlabelled list, one palette) still loads, as a single unlabelled partition.

Positions and bases are written in the **rig's asset space**, not the solver's engine units: every
point is `B⁻¹ · p / unitsPerMetre` and every basis axis is `B⁻¹ · a`, the same conversion
`PoseRetargeter` applies when it drives the 34-joint skeleton. A helper therefore sits in the same
metres and the same axes as the GLB's own joints, and a manifest left in engine units misses the
body by ~247× — which the metre bounds in `HelperBindTest` fail on rather than a skinning step
silently binding off the body.

The tool refuses to write a manifest it cannot place: a head the rest stance refused has no bind, a
non-finite rest length is refused before it can be serialised as invalid JSON, and a partition over
255 fails before the file is written.

## The muscle assets

`muscles_sup.glb` and `muscles_deep.glb` are each one skinned section per muscle and side — one
primitive each, named `<muscleId>_<side>` — over a skin of the rig's own 34 joints, whose inverse
binds are copied from `humanoid_v2.glb` byte for byte, followed by that layer's helpers in manifest
order. The superficial asset carries 46 sections over 242 joints; the deep asset 20 sections over
114. A vertex carries at most two influences; every ring between two stations is weighted between the
two helpers that own that arc fraction.

They are a deterministic stand-in, not art. The rings sit on the solver's rest path and their frames
are slerped between the two helpers' bind frames, which is the same blend linear-blend skinning
applies, so the surface is where the skin will put it. `HelperBindTest` gates both: inverse binds
equal the manifest within `1e-3 m`, the 34-joint body prefix is identical, uniform scale 1 on every
helper, at most four influences per vertex, finite unit normals and tangents, positive section
volume, no degenerate triangle, and no triangle facing into the surface. The one exception is
gluteus maximus, whose rest path doubles back through 125° between two stations: a tube of the
muscle's own radius cannot follow that without folding, so its fold is held to a measured area and
disappears when authored geometry replaces this mesh. Sections of different layers live in different
assets, so a superficial section may sit over a deep one; the interpenetration check is per asset.

## Why a JVM module

The exporter compiles the pure-Kotlin packages (`anatomy`, `animation`, `poses`, and the character
bind/rig readers) straight out of the app module — see `build.gradle.kts`. It does not depend on
`:app`, so the Android plugin and Filament stay out of the tool, and there is no second
implementation of the path maths to drift.
