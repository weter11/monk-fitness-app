# Filament character — Stage 1 rig evidence and Stage 2 validation

Stage 1 — the generated `humanoid_v2` rig, the position-driven retarget and the device gate — is
merged, and §0 records what it measured. The procedure in §1–§6 is the **Stage 2** checklist: there
the presenter ships behind a disabled-by-default flag, and none of those checks have been executed.
There is no GPU device in the build environment, so everything in §1–§6 that needs pixels, a driver
or a lifecycle transition is for a physical device and must be recorded, not assumed. (§7.7–§7.8 are the
executed device runs: the muscle-tube layer and the authored layer's per-instance path on an Adreno 735
handset.) The automated
tests cover pose retargeting, backend fallback policy, single-owner lifecycle and the flag default.

## 0. Stage 1 evidence (P1–P7)

Every number below is a measurement. The automated rows were re-run on the post-PR #396 tree
(`b221ec3`); the device row is the run against build `b38972f` recorded on PR #394. Nothing here is a
target, an estimate or a projection.

### 0.1 Shipped rig and coordinate contract (P2–P4)

| Property | Value |
| --- | --- |
| Asset | `app/src/main/assets/characters/humanoid_v2.glb` (`humanoid_v1.glb` deleted by P4) |
| `sha256` | `a4b2c226b6de4c98e0942fd0f3b7086bf091c187228695edc6c4a34696ff3d8b` |
| Size | 2,138,188 bytes (2.04 MB) |
| Skin joints | **34** = **23 mapped** + **11 visual-only leaves** (crown + five fingers per hand) |
| Unmapped engine joints | **10**, declared in `BoneMap.UNMAPPED`: wrist, palm, knuckles and fingertips per side, heel per foot |
| Rest rotations | **0 rotation keys**; identity on all 34 joints, TRS nodes only, no `matrix` node |
| Torso ↔ arm clearance | **17.96 mm** at bind (chest half-width 0.110 m; upper-arm inner surface 0.1280 m) |
| Basis | `BoneMap.basis = Ry(+90°)` — asset `+Z` forward / `+X` left becomes engine `+X` forward / `−Z` left |
| Scale | `s = 235 / 0.95 = 247.368423 u/m`; `1/s = 0.00404255 m/u` (≈4.0426 mm per engine unit) |
| Bone composition | `R_world = B⁻¹ · shortestRotation(a, d_seg) · B · R_world(parent)`, with `a = B · R_world(parent) · d_bind` |
| Pelvis root translation | `B⁻¹ · Δp / s`, with `Δp = p − p_bind` |

The 23 mapped joints include the scapula pair, which `humanoid_v1` did not carry. The 11 visual-only
bones ride their mapped parent rigidly under the declared `TWIST_NONE` policy; the 10 undrivable
joints and the 11 visual leaves are disjoint, so 23 + 10 = 33 engine joints and 23 + 11 = 34 skin
joints both partition.

### 0.2 A1 — bind alignment (P3)

`RigLandmarkAlignmentTest` reconstructs each mapped bone head from the parsed node chain, converts it
with the contract's own `p_engine = s · Ry(+90°) · p_asset`, and compares it with
`X1Bind.bindJoints()` at the X1 A-pose. Tolerance `TAU_BIND_MM = 0.5 mm` = 0.12368 engine units.

| Measurement | Result |
| --- | --- |
| Mapped joints inside tolerance | **23 / 23** |
| Worst bind residual | `ELBOW_A` **0.000126 mm** (3.12e-5 u), ≈3,960× inside tolerance |
| Generator `--check` line | `A1 worst position 0.000000 m  (tolerance 0.000500 m)` |
| Rest orientation | 0.0000° on every joint, by construction (no rotation keys) |

`0.00000 mm` and `0.000126 mm` are the same measurement at different precisions: the generator prints
metres to six decimals, the test table prints millimetres. The residual is float32 quantisation of the
parsed chain, not a placement error — on the GLB's own JSON doubles it is 3.6e-15 units.

The orientation row is vacuous on this rig, and the test says so instead of implying otherwise. What
discriminates is the position row with its **wrong-basis control** (the same rig converted without
`Ry(+90°)` misses by more than 100·τ) and `IBM · rest_world = I` over all 34 joints (≤ 4.4e-8).

### 0.3 A2 — posed alignment residual (P6)

`RigAlignmentResidualTest` reconstructs each mapped bone head from its skinning matrix,
`M_k · [v_rest_k, 1]`, and compares it with the engine joint that should sit on it. Five frames drawn
from four registered builders give **23 joints × 5 frames = 115 rows**. The frame set:

| Frame | Builder | Progress |
| --- | --- | --- |
| `squat_standard/bottom` | `squat_standard` | 1.0 |
| `squat_standard/top` | `squat_standard` | 0.0 |
| `pushup_standard/bottom` | `pushup_standard` | 0.5 |
| `birddog_hold/hold` | `birddog_hold` | 1.0 |
| `row_standard/mid-pull` | `row_standard` | 0.5 |

Run and transcribe with:

```bash
./gradlew :app:testDebugUnitTest --tests "*RigAlignmentResidualTest*" --info
```

The table below is that stdout, worst-first, unedited:

| Joint | Frame | Engine units | mm |
| --- | --- | ---: | ---: |
| `HAND_A` | `row_standard/mid-pull` | 0.000326 | 0.001316 |
| `HAND_P` | `row_standard/mid-pull` | 0.000247 | 0.000997 |
| `HAND_P` | `squat_standard/bottom` | 0.000243 | 0.000982 |
| `HAND_P` | `birddog_hold/hold` | 0.000218 | 0.000880 |
| `HAND_A` | `squat_standard/top` | 0.000198 | 0.000802 |
| `HAND_P` | `squat_standard/top` | 0.000198 | 0.000802 |
| `HAND_A` | `squat_standard/bottom` | 0.000195 | 0.000788 |
| `HAND_A` | `birddog_hold/hold` | 0.000169 | 0.000682 |
| `TOE_F` | `birddog_hold/hold` | 0.000165 | 0.000667 |
| `TOE_B` | `squat_standard/bottom` | 0.000161 | 0.000651 |
| `ELBOW_A` | `row_standard/mid-pull` | 0.000152 | 0.000614 |
| `ANKLE_F` | `birddog_hold/hold` | 0.000146 | 0.000589 |
| `ANKLE_B` | `squat_standard/bottom` | 0.000139 | 0.000563 |
| `ELBOW_A` | `squat_standard/bottom` | 0.000135 | 0.000544 |
| `TOE_F` | `squat_standard/bottom` | 0.000132 | 0.000534 |
| `ANKLE_F` | `pushup_standard/bottom` | 0.000131 | 0.000528 |
| `ANKLE_B` | `pushup_standard/bottom` | 0.000130 | 0.000524 |
| `TOE_F` | `pushup_standard/bottom` | 0.000128 | 0.000518 |
| `TOE_B` | `pushup_standard/bottom` | 0.000127 | 0.000512 |
| `HEAD_POS` | `pushup_standard/bottom` | 0.000125 | 0.000506 |
| `ELBOW_P` | `squat_standard/bottom` | 0.000124 | 0.000499 |
| `HAND_P` | `pushup_standard/bottom` | 0.000123 | 0.000495 |
| `ELBOW_P` | `birddog_hold/hold` | 0.000120 | 0.000484 |
| `TOE_B` | `birddog_hold/hold` | 0.000118 | 0.000478 |
| `ANKLE_F` | `squat_standard/bottom` | 0.000115 | 0.000465 |
| `HAND_A` | `pushup_standard/bottom` | 0.000109 | 0.000442 |
| `ELBOW_P` | `pushup_standard/bottom` | 0.000105 | 0.000423 |
| `NECK_END` | `birddog_hold/hold` | 0.000105 | 0.000423 |
| `HEAD_POS` | `birddog_hold/hold` | 0.000104 | 0.000422 |
| `ANKLE_B` | `birddog_hold/hold` | 0.000103 | 0.000418 |
| `HEAD_POS` | `row_standard/mid-pull` | 0.000103 | 0.000416 |
| `SHOULDER_P` | `row_standard/mid-pull` | 0.000102 | 0.000414 |
| `ELBOW_A` | `birddog_hold/hold` | 0.000102 | 0.000411 |
| `SCAPULA_A` | `birddog_hold/hold` | 0.000100 | 0.000406 |
| `SCAPULA_P` | `birddog_hold/hold` | 0.000100 | 0.000406 |
| `KNEE_B` | `squat_standard/bottom` | 0.000099 | 0.000400 |
| `KNEE_F` | `pushup_standard/bottom` | 0.000093 | 0.000376 |
| `KNEE_F` | `squat_standard/bottom` | 0.000093 | 0.000376 |
| `KNEE_B` | `pushup_standard/bottom` | 0.000092 | 0.000373 |
| `SHOULDER_A` | `birddog_hold/hold` | 0.000089 | 0.000360 |
| `ELBOW_A` | `pushup_standard/bottom` | 0.000087 | 0.000352 |
| `NECK_END` | `pushup_standard/bottom` | 0.000085 | 0.000342 |
| `ELBOW_P` | `row_standard/mid-pull` | 0.000084 | 0.000338 |
| `SHOULDER_P` | `pushup_standard/bottom` | 0.000082 | 0.000333 |
| `ELBOW_A` | `squat_standard/top` | 0.000082 | 0.000331 |
| `ELBOW_P` | `squat_standard/top` | 0.000082 | 0.000331 |
| `SHOULDER_P` | `birddog_hold/hold` | 0.000081 | 0.000329 |
| `NECK_END` | `row_standard/mid-pull` | 0.000077 | 0.000312 |
| `CHEST` | `birddog_hold/hold` | 0.000077 | 0.000311 |
| `CLAVICLE_A` | `birddog_hold/hold` | 0.000077 | 0.000311 |
| `CLAVICLE_P` | `birddog_hold/hold` | 0.000077 | 0.000311 |
| `SHOULDER_A` | `pushup_standard/bottom` | 0.000076 | 0.000306 |
| `CHEST` | `pushup_standard/bottom` | 0.000072 | 0.000290 |
| `CLAVICLE_A` | `pushup_standard/bottom` | 0.000072 | 0.000290 |
| `CLAVICLE_P` | `pushup_standard/bottom` | 0.000072 | 0.000290 |
| `SCAPULA_A` | `pushup_standard/bottom` | 0.000072 | 0.000290 |
| `SCAPULA_P` | `pushup_standard/bottom` | 0.000072 | 0.000290 |
| `SHOULDER_A` | `row_standard/mid-pull` | 0.000071 | 0.000286 |
| `CHEST` | `row_standard/mid-pull` | 0.000069 | 0.000281 |
| `CLAVICLE_A` | `row_standard/mid-pull` | 0.000069 | 0.000281 |
| `CLAVICLE_P` | `row_standard/mid-pull` | 0.000069 | 0.000281 |
| `SCAPULA_A` | `row_standard/mid-pull` | 0.000069 | 0.000280 |
| `SCAPULA_P` | `row_standard/mid-pull` | 0.000069 | 0.000280 |
| `KNEE_F` | `birddog_hold/hold` | 0.000063 | 0.000254 |
| `TOE_F` | `row_standard/mid-pull` | 0.000063 | 0.000253 |
| `ANKLE_F` | `row_standard/mid-pull` | 0.000061 | 0.000245 |
| `TOE_B` | `row_standard/mid-pull` | 0.000059 | 0.000240 |
| `ANKLE_B` | `row_standard/mid-pull` | 0.000059 | 0.000238 |
| `HEAD_POS` | `squat_standard/bottom` | 0.000053 | 0.000212 |
| `NECK_END` | `squat_standard/bottom` | 0.000039 | 0.000157 |
| `CHEST` | `squat_standard/bottom` | 0.000039 | 0.000156 |
| `CLAVICLE_A` | `squat_standard/bottom` | 0.000039 | 0.000156 |
| `CLAVICLE_P` | `squat_standard/bottom` | 0.000039 | 0.000156 |
| `SHOULDER_P` | `squat_standard/bottom` | 0.000036 | 0.000147 |
| `HIP_F` | `row_standard/mid-pull` | 0.000031 | 0.000124 |
| `HIP_B` | `row_standard/mid-pull` | 0.000031 | 0.000124 |
| `PELVIS` | `row_standard/mid-pull` | 0.000031 | 0.000123 |
| `LUMBAR` | `row_standard/mid-pull` | 0.000031 | 0.000123 |
| `SHOULDER_A` | `squat_standard/bottom` | 0.000025 | 0.000101 |
| `HIP_B` | `pushup_standard/bottom` | 0.000025 | 0.000099 |
| `SCAPULA_A` | `squat_standard/bottom` | 0.000024 | 0.000096 |
| `SCAPULA_P` | `squat_standard/bottom` | 0.000024 | 0.000096 |
| `KNEE_B` | `birddog_hold/hold` | 0.000024 | 0.000095 |
| `PELVIS` | `pushup_standard/bottom` | 0.000022 | 0.000087 |
| `HIP_F` | `pushup_standard/bottom` | 0.000019 | 0.000077 |
| `KNEE_F` | `row_standard/mid-pull` | 0.000017 | 0.000069 |
| `KNEE_F` | `squat_standard/top` | 0.000016 | 0.000066 |
| `KNEE_B` | `squat_standard/top` | 0.000016 | 0.000066 |
| `ANKLE_B` | `squat_standard/top` | 0.000015 | 0.000062 |
| `LUMBAR` | `squat_standard/bottom` | 0.000015 | 0.000062 |
| `LUMBAR` | `pushup_standard/bottom` | 0.000015 | 0.000062 |
| `KNEE_B` | `row_standard/mid-pull` | 0.000015 | 0.000060 |
| `ANKLE_F` | `squat_standard/top` | 0.000014 | 0.000057 |
| `TOE_F` | `squat_standard/top` | 0.000014 | 0.000056 |
| `HIP_F` | `birddog_hold/hold` | 0.000013 | 0.000052 |
| `TOE_B` | `squat_standard/top` | 0.000012 | 0.000049 |
| `HIP_F` | `squat_standard/bottom` | 0.000010 | 0.000042 |
| `PELVIS` | `squat_standard/bottom` | 0.000008 | 0.000031 |
| `HIP_B` | `squat_standard/bottom` | 0.000006 | 0.000024 |
| `PELVIS` | `birddog_hold/hold` | 0.000006 | 0.000023 |
| `LUMBAR` | `birddog_hold/hold` | 0.000006 | 0.000023 |
| `SHOULDER_A` | `squat_standard/top` | 0.000005 | 0.000022 |
| `SHOULDER_P` | `squat_standard/top` | 0.000005 | 0.000022 |
| `HIP_B` | `birddog_hold/hold` | 0.000004 | 0.000017 |
| `HIP_F` | `squat_standard/top` | 0.000003 | 0.000013 |
| `HIP_B` | `squat_standard/top` | 0.000003 | 0.000013 |
| `PELVIS` | `squat_standard/top` | 0.000000 | 0.000000 |
| `LUMBAR` | `squat_standard/top` | 0.000000 | 0.000000 |
| `CHEST` | `squat_standard/top` | 0.000000 | 0.000000 |
| `NECK_END` | `squat_standard/top` | 0.000000 | 0.000000 |
| `HEAD_POS` | `squat_standard/top` | 0.000000 | 0.000000 |
| `CLAVICLE_A` | `squat_standard/top` | 0.000000 | 0.000000 |
| `CLAVICLE_P` | `squat_standard/top` | 0.000000 | 0.000000 |
| `SCAPULA_A` | `squat_standard/top` | 0.000000 | 0.000000 |
| `SCAPULA_P` | `squat_standard/top` | 0.000000 | 0.000000 |

```
frames=5 builders=4 worst=HAND_A@row_standard/mid-pull 0.0013mm
```

Tolerance `TAU_RESIDUAL_MM = 5.0 mm` (1.23684 u). The worst row is three orders of magnitude inside
it: the rig is generated from the same `SkeletonDefinition` the poses are built from, so every
segment length the retarget re-uses is the published one. The row's power is in its controls, which
are also part of P6:

| Control | Claim | Measured |
| --- | --- | --- |
| R2 | the frames are posed, not the bind stance | heads moved > 10 mm: 23/23, 10/23, 23/23, 23/23, 23/23; worst displacement 612.1–1529.4 mm; `PELVIS` rotation ≠ 0 on squat bottom and bird-dog |
| R3 | the bind frame measures zero | every mapped head < 1e-3 mm; `M · [v_rest, 1]` reproduces the rest head on all 34 skin joints |
| R4 | equal length with a different direction is a non-zero residual | length-sum difference **0.0000 mm**; measured head residual **0.1745 mm** on a 100 mm segment turned 0.1° |
| R5 | a dropped basis moves heads by metres | worst `HAND_P` **1.504 m**, `ANKLE_F` 1.325 m, `TOE_F` 1.224 m; `PELVIS`/`LUMBAR` stay at 0.1715 m, under worst/4 |
| R6 | the table is reproducible | two full passes — rebuilt poses, fresh retargeters — agree to 1e-6 mm |
| R7 | 23 is the map, not the skin | 23 mapped / 34 skin / 11 visual-only / 10 undrivable, partitions disjoint |

`squat_standard/top` is the one frame that returns the trunk and hips onto X1 (`PELVIS`…`CHEST` at
0.0 mm), so only 10 of 23 heads move > 10 mm there; the row asserts the measured floor of 10 and a
worst displacement above 50 mm, which an exact-bind frame cannot satisfy. Fully verified at PR #396:
396 suites, 3,720 tests, 0 failures.

### 0.4 P1 — segment-frame roll baseline

The comparator derives a frame per segment and measures θ, the angle between the rest-roll reference
and the bend-plane normal, folded to `[0°, 90°]`. The sweep is 66 registered builders × 9 stations =
**594 frames**, 23 instances each; 6,231 of 13,662 instances are `POLE_MEASURED`, 9 per frame are
structurally `POLE_UNAVAILABLE`, and no unmeasured row ever reports an angle (NaN, never 0).

**Anchored** rows are those whose bind chain states a plane (hip, shoulder, ankle): θ is 0 at bind by
construction and everything after it is drift. The other rows are straight at bind, so the A-pose
declares no roll for them and their numbers are reported for completeness, not as roll errors.

| Segment | n | median θ | max θ | Anchored |
| --- | ---: | ---: | ---: | --- |
| `THORAX` | 149 | 0.00° | 90.00° | no |
| `NECK` | 11 | 0.00° | 0.00° | no |
| `HUMERUS_A` | 586 | 8.03° | 89.89° | **yes** |
| `HUMERUS_P` | 586 | 6.62° | 89.89° | **yes** |
| `FOREARM_A` | 594 | 61.05° | 90.00° | no |
| `FOREARM_P` | 594 | 61.25° | 90.00° | no |
| `HAND_A` | 186 | 30.91° | 89.77° | no |
| `HAND_P` | 197 | 43.75° | 88.99° | no |
| `FEMUR_F` | 592 | 0.30° | 89.82° | **yes** |
| `FEMUR_B` | 594 | 0.27° | 89.82° | **yes** |
| `TIBIA_F` | 531 | 11.19° | 90.00° | no |
| `TIBIA_B` | 531 | 11.14° | 90.00° | no |
| `FOOT_F` | 542 | 0.07° | 55.68° | **yes** |
| `FOOT_B` | 538 | 0.07° | 72.29° | **yes** |

Per pilot, with `|d| · θ` at a 30 mm attachment offset:

| Pilot | Frames | Measured | Unavailable | Worst θ | Segment | `d·θ` at 30 mm |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| `squat_standard` | 9 | 90 | 81 | 90.00° | `FOREARM_A` | 0.0471 m |
| `pushup_standard` | 9 | 95 | 81 | 89.89° | `HUMERUS_A` | 0.0471 m |
| `birddog_hold` | 9 | 99 | 81 | 80.09° | `HUMERUS_P` | 0.0419 m |
| `row_standard` | 9 | 106 | 81 | 81.93° | `HAND_P` | 0.0429 m |

The baseline, stated plainly: the anchored lower body tracks its bind plane closely — `femur` median
**0.30°**, `foot` median **0.07°** — while the arm chain does not. The anchored `humerus` reaches
**89.89°** on `pushup_standard` (a real roll reading at the shoulder), and the unanchored forearm and
hand rows read medians of 61.05°/61.25° and 30.91°/43.75°, with maxima at the 90° fold ceiling. That
ceiling is `0.03 · π/2` exactly, so the worst `|d| · θ` is the fold's own limit rather than a fitted
number; `τ_roll = 0.048 m` follows from it and is **reported, not gated** — no production code reads
it.

This is a Stage-1 measurement baseline, not a defect that was masked. No ad-hoc twist correction was
applied: `BoneMap.twistPolicy` is `TWIST_NONE`, `anatomy/` has no production consumer
(`SegmentFramePurityTest`), and the RFC's answer to the arm-chain drift is an authored per-segment
roll reference, deferred as a separate change.

The frame also reproduces the authored pelvis tilt instead of freezing it: `squat_standard` @ 0.999
derived-vs-authored **0.03°** (authored 25.76°; the RFC's 22.92° is `SquatPose`, while
`squat_standard` is registered to `AirSquatPose` at 0.45 rad), and `birddog_hold` @ 0 **0.00°**
(authored 90.00°).

### 0.5 On-device smoke validation (P4 build `b38972f`, PR #394)

| | |
| --- | --- |
| Device | `motorola edge 50 ultra` |
| GPU | Adreno 735 |
| OS | Android 16 |
| Display | 1220 × 2712 @ 450 dpi |
| Build | `b38972f`, debug APK, `-Pmonk.filamentCharacter=true` |
| Method | each exercise opened from the Exercise Library and run 18 s with the live Filament `CharacterStage`; `dumpsys gfxinfo` reset per run; screenshots ≈1 s; crash/ANR buffers checked |

| Exercise | `animationId` | Reps in 18 s | fps | 95th | Modern jank |
| --- | --- | --- | ---: | ---: | ---: |
| Bodyweight Air Squat | `squat_standard` | 7 (2.5 s) | ≈59.8 | 14 ms | 0.84 % |
| Standard Push-up | `pushup_standard` | 7 (2.5 s) | ≈60 | 14 ms | 1.12 % |
| Inverted Bodyweight Row | `row_standard` | 3 (6 s, `PING_PONG`) | ≈60 | 14 ms | 0.65 % |
| Alternating Bird Dog Reps | `birddog_reps` | 4 side reps (4 s/side) | ≈60 | 14 ms | 0.84 % |
| Jumping Jacks | `jumping_jack_standard` | 12 (1.4 s) | ≈60 | 14 ms | 0.56 % |

Across the five runs: 50th percentile **9–13 ms**, 95th percentile **14 ms**, modern jank
**0.56–1.12 %**, and **0 missed vsyncs**. All five rendered the smooth-shaded v2 GLB as a 3D mesh —
none fell back to 2D, none reported `CharacterSmoke unavailable` — with no crash and no ANR. Phase
recurrence in the screenshot sequence matches the declared periods (row `f00` vs `f06`: delta 0.150,
0.06 % of pixels moved).

Two operational observations, neither a retargeting defect:

1. **Floor-pose framing.** The fixed camera `lookAt(0, 1.05, 3.4)` crops the lower body on
   `pushup_standard`, `row_standard` and `birddog_reps` inside the 225 dp hero viewport; squat and
   jumping jacks are fully framed. The camera does not fit a body lying near the floor. This is a
   Stage-2 framing change, not a rig or retarget failure.
2. **Incremental-build trap.** A Gradle incremental build that toggles
   `-Pmonk.filamentCharacter=true` keeps the previously compiled `CharacterFeature` flag, so the
   first installed APK silently renders 2D. Recompiling `CharacterFeature.kt` (or a clean build) is
   required for a device build; tooling should force that class to rebuild.

### 0.6 Stage-1 gate summary (P1–P7)

| Row | PR | Evidence | Gate |
| --- | --- | --- | --- |
| P1 | #392 | 14 segment types / 23 instances; 594-frame sweep; roll baseline §0.4 | met — measurement recorded, `τ_roll` reported not gated |
| P2 | #391 | `BoneMap` as data; 23 mapped / 10 undrivable / 11 visual-only, coverage test green | met |
| P3 | #393 | generated `humanoid_v2`; A1 23/23, worst 0.000126 mm; asset contract and side test green | met |
| P4 | #394 | X3 position-driven retarget with mandatory `Ry(+90°)` basis; device smoke §0.5; full suite green | met |
| P5 | #395 | unmapped-bone inheritance pinned on all 11 visual leaves, with a discriminating pre-P4 control | met |
| P6 | #396 | A2 115 rows, worst 0.0013 mm vs 5.0 mm, with R2–R7 controls | met |
| P7 | this PR | this record; no code, tests, build script or asset touched | met |

Stage 1's acceptance is satisfied: **A1 is green on the new rig**, **A2 is green at the measured
frames**, **`PoseRetargeterBasisTest` is green with its wrong-basis control red**, and the character
**renders five exercises on a real device** at ≈60 fps with no crash, ANR or fallback. No Stage-1
automated or device gate remains open.

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
  policy, single-owner slot, thumbnail cache key, engine/renderer boundary.
- Build: a default build generates `FILAMENT_CHARACTER_ENABLED = false`; no test asserts it, because a
  unit test that fails on an intentional flag build is a trap.

Not covered by an automated test: the Compose/Android glue itself (`CharacterStage`, `AndroidView`,
`UiHelper` surface callbacks). This repository has no instrumentation source set, so that layer is
exercised only by the device procedure below. The fallback *policy* it depends on is unit-tested;
the view wiring is not.

Requires a physical device (not executed, not claimed):

- anything in the checklist above: rendering, driver behaviour, backend actually selected per
  device, surface/lifecycle recovery, frame time, memory, thermal behaviour.

## 7. Stage 2 — anatomy kernel and integration (S2-1 … S2-9)

Stage 2 adds a second consumer of the published pose: a pure-Kotlin anatomy kernel that solves each
muscle's path in the pose, and a procedural tube layer that turns those paths into geometry. Neither
touches the skinned body: `CharacterFrameProducer` and `PoseRetargeter` are unchanged, and the solve
sits between `frameProducer.next()` and `mapper.boneMatrices(...)` inside `CharacterSession.render()`
behind `CharacterFeature.isEnabled`.

### 7.1 What landed

| Unit | Delivered | Evidence |
| --- | --- | --- |
| S2-1 | `anatomy_v1.json`, `AnatomyDefinition`, `AnatomyDefinitionLoader`, L3 validation | `AnatomyDefinitionLoaderTest`, `AnatomyDefinitionTest` |
| S2-2 | `AnatomyFrame` carrier and layout contract | `AnatomyFrameLayoutTest`, `AnatomySolveAllocationTest` |
| S2-3 | attachment resolution and the control polyline | `MuscleAttachmentTest` |
| S2-4 | obstacle predicate and clearance state machine | `MusclePathObstacleFixtureTest`, `MusclePathSolverFixturesTest` |
| S2-5 | pass loop, smoothing, termination | `MusclePathSolverTest`, `MusclePathGeometryTest`, `MusclePathDeterminismTest` |
| S2-6 | rest lengths, reference lengths, renderability mask | `MuscleRestLengthTest`, `MuscleRenderabilityTest` |
| S2-7 | `CharacterSceneFrame`, `DynamicGeometrySlot`, `MorphWeightSet`, `CharacterLayer`, `MuscleSelection`, `CharacterLayerController`, `ProceduralMuscleMesh` | `ProceduralTubeBudgetTest`, `SceneFrameHandoffTest`, `MuscleGeometryOwnershipTest` |
| S2-8 | the solve wired into the render loop, the debug overlay, diagnostics fields, the tuned placeholders, the §7.4.2 L3 check | `LayerToggleTest`, `AnatomyDefinitionCheckBindTest` |
| S2-9 | this record | — |

The shipped library is 8 muscles, 18 head-slots per side pair and 16 renderable instances, all
`SUPERFICIAL`. The `DEEP` layer is declared and empty: its chip in the debug overlay is real and
removing it costs no geometry. That is a fact about the M1 library, not a broken toggle.

### 7.2 Rulings on record

**7.2.1 Curvature bound stays one waypoint per run, at 2.6 rad.** The Stage-2 default
`thetaMaxRadians` is `2.6` rad (~150°) in `AnatomyDefinition`, in the loader's fallback and in the
shipped `anatomy_v1.json`. The reason is measured, not stylistic: a single-waypoint detour around an
ellipsoid folds sharply at its apex — 59.4° between two samples one declared spacing apart in the
`single_obstacle` fixture — so a 45° bound refuses every such run (`CURVATURE_VIOLATION`, `PASS_CAP`)
instead of catching a foldback. Multi-waypoint arcs were rejected for Stage 2 because they would move
the budget and the fixture rows before the shallow layer is on screen at all.
`MusclePathSolverFixturesTest.theOneWaypointApexIsRefusedAtFortyFiveDegrees` keeps measuring the
refusal at 45° explicitly, so the decision is a bound this version chose rather than a behaviour it
lost.

**7.2.2 `REST_LENGTH_INVALID` was expected, and is now closed by geometry.** Before tuning, the bind
stance solved 2 of 18 heads (`gluteus_maximus P`, `rectus_femoris P`); 16 were `REST_LENGTH_INVALID`
because their reference path could not be built at all. Two distinct causes, both in the placeholder
data:

- Eight heads were refused *before the first pass*: `smallestDetour` is the largest radius of the
  nearest obstacle in that head's union — the global `ribcage` at 140 mm — and every muscle's
  `corridorMetres` was 100–120 mm, so `firstDetour > corridor` stopped the search every time.
- Six heads were refused inside the search against a surface that already contained one of their own
  attachments. `rectus_abdominis` sat 0.42–0.67 of the way to the `ribcage` centre, `gluteus_maximus
  A` 0.31 to `hip_capsule`, `biceps_femoris` 1.13 to `knee_capsule`. A point that starts inside an
  obstacle cannot be cleared by any detour, so no corridor was ever going to fix them.

### 7.3 Fixture ledger — the placeholder tuning

Only the wrap surfaces and three corridors moved. Every attachment is still `UNRECORDED`, and the
`frameUnverified` list is unchanged at 20 rows.

| Parameter | Before | After | Why |
| --- | --- | --- | --- |
| `hip_capsule.radiiMetres` | 0.10, 0.09, 0.07 | 0.016, 0.014, 0.012 | its own attachments sat inside it; 24 mm max radius still forces a detour |
| `ribcage.radiiMetres` | 0.12, 0.14, 0.10 | 0.040, 0.045, 0.032 | the thoracic insertions were inside it; the surface now sits between the chord and them |
| `knee_capsule.radiiMetres` | 0.07, 0.06, 0.05 | 0.063, 0.054, 0.045 | `biceps_femoris` grazed it at 1.13; 63 mm max radius keeps the detour and the clearance |
| `biceps_femoris.corridorMetres` | 0.12 | 0.18 | the detour needs one escalation past the obstacle's own radius |
| `gluteus_maximus.corridorMetres` | 0.12 | 0.18 | same, plus an 18 mm path against a 24 mm tube |
| `rectus_femoris.corridorMetres` | 0.12 | 0.18 | same |
| `thetaMaxRadians` | absent (45° default) | 2.6 | §7.2.1 |
| `shoulder_girdle` | 0.06, 0.05, 0.05 | unchanged | declared, named by no muscle, not global — it is in no union |

The tuned values are a corridor and a capsule, not anatomy. The `gluteus_maximus` A head is the one to
watch: its two placeholder attachments are 18 mm apart, so a detour around a 24 mm-radius surface has
an apex close to the curvature bound. Measured over the sweep it holds, and the neighbouring radii
that fail are recorded in §7.4.

### 7.4 Verification executed in this environment

Not the full suite — the targeted anatomy and character tests, plus a JVM harness that drives the real
definition through the real solver. Both were compiled with `kotlinc` against a domain slice
(`anatomy/`, `animation/`, `poses/`, the rig-free `character/` files) because this sandbox has no
Android SDK or Gradle; CI remains the verifier for the Gradle build.

```
$ java -cp <slice> com.monkfitness.app.anatomy.AnatomyProbe src/main/assets/anatomy/anatomy_v1.json
== definition check ==
  clean=true issues=[]
  frameUnverified=20 [...]
  thetaMaxRadians=2.6 heads=18 instances=16
== bind stance ==
  heads valid=18 invalid=0  instances renderable=16/16
== squat_standard @ 0.5 ==
  heads valid=18 invalid=0  instances renderable=16/16
== pushup_standard @ 0.5 ==
  heads valid=18 invalid=0  instances renderable=16/16

$ java -cp <slice> com.monkfitness.app.anatomy.SweepProbe src/main/assets/anatomy/anatomy_v1.json
swept 36 frames, 648 head-solves, 0 distinct refusals

$ java -cp <slice> org.junit.runner.JUnitCore <22 anatomy test classes>
OK (190 tests)

$ java -cp <slice> org.junit.runner.JUnitCore \
    ProceduralTubeBudgetTest SceneFrameHandoffTest MuscleGeometryOwnershipTest LayerToggleTest
OK (24 tests)
```

The sweep is nine stations (`0, 0.125 … 0.875, 0.999`), both sides, `squat_standard` and
`pushup_standard`. Rest lengths at the reference stance, in engine units converted to millimetres:

| Head | Rest length | Head | Rest length |
| --- | --- | --- | --- |
| `rectus_abdominis` A/P | 97.8 mm | `biceps_femoris` long A/P | 114.4 mm |
| `external_oblique` A/P | 95.3 mm | `biceps_femoris` short A/P | 80.9 mm |
| `erector_spinae` A/P | 97.8 mm | `rectus_femoris` A/P | 104.3 mm |
| `latissimus_dorsi` A/P | 42.3 mm | `gluteus_medius` A/P | 20.8 mm |
| `gluteus_maximus` A | 24.1 mm | `gluteus_maximus` P | 17.9 mm |

The capsule window is narrow and is recorded as such: with the knee and corridors as shipped,
`hip_capsule` first radii of 0.014/0.018/0.021/0.022 m each produced a refusal at
`squat_standard` (a `CURVATURE_VIOLATION` at one station, `REST_LENGTH_INVALID` at 0.022), while
0.015–0.017 and 0.019–0.020 solved the whole sweep. 0.016 is mid-window. A real attachment set is
expected to widen it; a placeholder one should not be read as a physical limit.

Test changes forced by the tune, both recorded rather than worked around:

- `MuscleRestLengthTest.theShippedDefinitionRefusesMostHeadsAtTheReferenceStanceAndSaysWhy` pinned the
  pre-tuning behaviour. It is now
  `theShippedDefinitionPublishesAReferenceLengthForEveryHead`, which asserts the opposite property —
  every head solves, its rest length is finite and positive, and the modelled length equals it at the
  reference stance.
- `SegmentFramePurityTest.noProductionConsumerReadsTheSegmentFramesYet` asserted that *no* production
  file consumed the derivation. S2-8 wires exactly one consumer, on purpose, so the guard was deleted
  rather than widened; the reverse fence (`AnatomyPurityTest`) still holds anatomy to pure Kotlin.

### 7.5 §7.4.2 L3 — the bind-stance obstacle check

`AnatomyDefinitionCheck.bindStance(definition, skeleton, bindPose, attachmentScale)` reports one row
per `(muscle, head, side, obstacle)` whose authored attachment polyline comes within an ellipsoid-
normalized distance of 1.0 — i.e. an attachment inside a surface that head must clear. It is a
*definition* row, not a per-frame refusal: the fix is the surface or the attachment, and reporting it
60 times a second would hide that.

| Property | Value |
| --- | --- |
| Rows on the shipped definition | 0 |
| Rows on the pre-tuning definition | 6 heads × 1 row |
| Rows on a definition with a sphere centred on one attachment | one per head whose union contains it |
| `AnatomyDefinitionCheck.check(definition)` | unchanged: structural rows only |

### 7.6 The tubes are drawn: the GPU binding

`ProceduralMuscleMesh` fills its `DynamicGeometrySlot` with 13,824 triangles every frame the solve
runs; `TubeMesh` now draws them. No `.filamat` and no `matc` are involved: the layer takes an instance
from the `UbershaderProvider` the session already creates for `AssetLoader`, so the material is the one
the GLB was loaded with.

| Property | Value |
| --- | --- |
| Material | `UbershaderProvider.createMaterialInstance` with `hasVertexColors`, `doubleSided`, `alphaMode = OPAQUE`, tinted from `SkinPalette.DEFAULT`; falls back to `MaterialInstance.duplicate` of the asset's `Ground` instance, and refuses the layer with a diagnostics line if neither exists |
| Declared attributes | exactly `Material.requiredAttributes`, filled from the first candidate the planner can satisfy: the unlit permutation the layer requests reports `POSITION,UV0,UV1,COLOR` on the Adreno 735 (§7.7), where the instance the provider returned before it reported `TANGENTS` and was refused by name |
| `UV1` | the `0xFF` fill gltfio itself uses for a missing second UV set (`USHORT2`, normalized) |
| `COLOR` | per-vertex tendon ramp written by the mesh (`UBYTE4`, normalized) |
| Indices | `UINT`, 41,472 capacity, uploaded only when the drawn-head set changes |
| Bounds | exact min/max over the written asset-space vertices, `fallback()` when nothing was written; that non-empty box is what satisfies `RenderableManager`'s `culling(true)` precondition, and non-finite components are dropped so one bad vertex cannot pin the box at `NaN`. `castShadows(false)`, `receiveShadows(false)` because the overlay neither casts nor receives |
| Upload | a 3-deep ring of direct buffers, so two frames in flight cannot collide with the slot being filled; one position upload per frame (81 KiB), colour/UV/index only on a shape change |
| Axes | `(x, y, z) → (−z, y, x)` applied once in `TubeVertexLayout.writePositions`, with no second scale; the mesh hands it metres, converting the centre line and the radius by `unitsPerMetre` alike |
| Winding | fixed: the band was wound inside-out (`a, c, b` / `b, c, d`) and is now `a, b, c` / `b, d, c`, so back-face culling keeps the walls the asset's single-sided materials would have culled |

Evidence that is a JVM test, not a device claim: `ProceduralTubeBudgetTest.theTubeWindingMatchesItsOutwardNormals`
(reverting the winding makes 13,536 of 13,824 triangles read as back-facing), and `TubeVertexLayoutTest`
— asset conversion against `X1Bind.toAssetDirection` with a wrong-basis control, `theSolvedMeshUploadsInHumanMetres`
solving the real definition through `AnatomySolver` and pinning the uploaded AABB to a body's metres
(`y` 0.456–1.421 m, half extents ≤ 0.5 m — a centre line left in engine units reads 113–350 m and fails),
byte strides, attribute planning including a `POSITION`-only plan, index-count bounds, AABB containment,
a `NaN`-vertex bounds guard, and an allocation gate over the whole upload fill.

The centre line was the one value that had escaped both the counts and the winding: it left
`ProceduralMuscleMesh` in engine units while the radius was converted, so the tubes sat ~247x out, past
the camera's 100 m far plane, and drew nothing. It is converted with the radius now, and the metre bound
above is asserted end to end rather than assumed.

What is still a device claim: that the material the provider returns reports exactly those attributes on
a given driver. `CharacterDiagnostics.muscleAttributes` records what the material actually required and
`muscleMaterialError` records a refusal, so the first device run answers it instead of implying it.

Launch a flagged build and open the debug overlay:

```bash
./gradlew :app:assembleDebug -Pmonk.filamentCharacter=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.monkfitness.app/.MainActivity   # then: exercise → hero → `tubes` chip
```

`adb` needs a host; on this handset the equivalent is `/data/data/com.termux/files/home/bin/rish -c`
with Shizuku running, e.g. `rish -c "pm list packages | grep monkfitness"` to confirm the install.

### 7.7 Device run — muscle tubes

The rows below are the ones this layer adds to §3. They were run on hardware at `main` `9aecc9fe`
(PR #405), which supersedes the run at `e6d7b2b` (PR #403) and the two blocker rows PR #404 recorded.
One of those blockers is gone: the instance the provider returns now reports
`POSITION,UV0,UV1,COLOR`, so the attribute refusal no longer happens. The other is replaced by a
harder one: with the tube renderable in the scene the process dies inside Filament's render pass
0.55 s after the engine is created, with the layer **off**, so every row that needs a frame is
blocked by the crash and not by the material. A second session, recorded under "The minimal
triangle" below, reduces that blocker to one call — an index-buffer upload on a renderable the scene
already references — and finds it in Filament's OpenGL backend rather than in this layer.

| Row | What to look for | Expected |
| --- | --- | --- |
| Debug overlay present | `-Pmonk.filamentCharacter=true` + debug activity: `tubes` and layer chips at top-left | overlay visible, `tubes` off by default |
| Solve built | overlay header | `18 heads · 0 refused · 16 drawn · 0 triangles` with tubes off |
| Tubes on | tap `tubes` | 18 red tubes appear over the body, inside it rather than beside it; triangle count `13824` |
| Attributes | overlay `attrs` line | `POSITION,UV0,UV1,COLOR` — anything else means the provider returned another variant, and a refusal line names what was missing |
| Tint | the belly against the two ends of a tube | belly muscle red, both ends shifted toward the tendon tone |
| Axis alignment | squat, side view | tubes run along the limbs they belong to and do not sit 90° across them |
| Layer toggle | tap `superficial` | `0 drawn · 0 triangles`, tubes disappear; tap again restores `16 · 13824` |
| Deep toggle | tap `deep` | unchanged — the M1 library has no deep muscle |
| Readout at rest | squat at the start of the rep | every head within ±0.1 % of the rest stance |
| Readout mid-rep | squat at the bottom | `rectus_femoris` and `biceps_femoris` positive; `rectus_abdominis` near zero |
| Refusal budget | squat and push-up, whole rep | `0 refused` on every frame |
| Frame time | p95 before and after the solve | no more than 1 ms added on a mid-range device |
| Lifecycle | background / foreground with tubes on | geometry and toggles survive; no crash |

#### The run

Built on the handset itself (aarch64, no host machine): JDK 21, Gradle 8.7, AGP 8.5, SDK 34, and
build-tools 34.0.0 `aapt2` executed through `qemu-x86_64` because the shipped binary is x86-64:

```bash
./gradlew :app:assembleDebug -Pmonk.filamentCharacter=true \
  -Pandroid.aapt2FromMavenOverride=<shim>/aapt2
```

`BUILD SUCCESSFUL in 5m 14s`, APK 22,830,802 bytes,
`sha256 93eac491f495ab09b7ea53c8c7497509e845f7132378eb78927d7c42f5b17e7f`,
`FILAMENT_CHARACTER_ENABLED = true` in the generated `BuildConfig`. Installed with the
package-installer session API over `rish`, `versionCode` 994075 → 994231; the final reinstall
(`install-create -r -d`, because the probe builds below carried a higher code) reports
`lastUpdateTime=2026-11-22 14:27:50`.

| Property | Value |
| --- | --- |
| Device | motorola edge 50 ultra (`ctwo_geuw`), `motorola/ctwo_geuw/ctwo:16/W1UVS36H.61-25-5/7fd1bb-f97e9:user/release-keys` |
| Android / API | 16 / 36 |
| SoC + GPU | Qualcomm (QTI) · Adreno 735 |
| GLES driver | `OpenGL ES 3.2 V@0762.40 (GIT@8924aaec70 …)`, shader compiler E031.45.02.26, build 07/16/25 |
| Vulkan | 1.3.0, level 1 advertised (`device API 36 · GLES 3.2 · Vulkan 1.3.0, level 1`) |
| Backend | requested Auto → active **OPENGL** |
| Display | 1220 × 2712, density 450 (override 502) |
| Launch | `am start -n com.monkfitness.app/.character.CharacterDiagnosticsActivity` |
| Overlay label | the `tubes` chip renders **localized** (`трубки`); select it by resource, not by the English word |

Four further APKs were built from the same tree and installed one at a time to attribute the crash.
Each one changes a single thing and is not part of the shipped build:

| Build | What it does differently | Result |
| --- | --- | --- |
| `e6d7b2b` (PR #403, control) | nothing — the pre-#405 tree | runs; overlay is the #404 record (`tubes: material requires TANGENTS…`, `0 triangles`); 474 frames, 42 fps, p95 24.72 ms |
| `72f08a59` | the unlit permutation alone (first commit of #405) | SIGSEGV, same fault address and same `pc` offsets as #405 |
| probe A | `TubeMesh.prepare()` returns after taking the material instance: no vertex buffer, no index buffer, no renderable | runs, no crash |
| probe B | vertex/index buffers and the upload rings are built, `buildRenderable()` is skipped | runs, no crash; overlay prints `attrs POSITION,UV0,UV1,COLOR`; 512 frames, 43 fps, p95 24.85 ms |
| probe C | renderable built and visible, but `setGeometryAt`/`setBuffer` skipped so the geometry keeps its builder's zero index count | SIGSEGV, same signature |
| probe D | renderable built, `setVisible(false)` forced so its layer mask stays 0 | SIGSEGV, same signature |

#### Results

| Row | Measured | Verdict |
| --- | --- | --- |
| Attributes | Probe B, same build, overlay verbatim: `attrs POSITION,UV0,UV1,COLOR`. No `TANGENTS`, no `tubes:` refusal line, and `logcat` holds zero `FILAMENT_CHECK_PRECONDITION`, zero Adreno/Filament error lines in probe B's log — its only Filament warning is the known `eglGetFrameTimestampsANDROID failed with error 12290`, and the shipped artifact's own log has no Filament warning at all. | **PASS** — the row #404 recorded as FAIL |
| Tubes on | Not observable on the shipped artifact: the process dies 0.55 s after `FEngine created`, before the 500 ms overlay poll writes anything, three launches out of three. Probe B prints `0 triangles` because tubes are off by default there, and the toggle cannot be reached. `T4`, the probe build that stops redefining its geometry, does draw: the overlay reads `18 heads · 0 refused · 16 drawn · 13824 triangles` with `attrs POSITION,UV0,UV1,COLOR · candidate 0` and 486 frames at 48 fps — the count the row expects, from a tree that is not this one. | **FAIL (crash)** on the shipped artifact; the draw itself is reachable |
| Axis alignment / winding | No frame survives, so no pixel and no seam reading exists. `TAU_RESIDUAL_MM` is the §0.3 bind/posed residual (5.0 mm over the A2 rows), not a tube-to-limb seam, and the layer has no device readout for one. | **BLOCKED** by the crash |
| Tint | No belly and no end ramp to compare. | **BLOCKED** by the crash |
| Layer toggle | Unreachable. The crash needs neither the toggle nor the layer: it happens with `tubes` off, which is the default. | **BLOCKED** by the crash |
| Deep toggle | Same. | **BLOCKED** by the crash |
| Readout at rest | Not observed: the earliest dump this run could take (53 frames, ~1.3 s after launch) already reads `gluteus_maximus P +60.8 %`, and the lowest hip value across thirteen samples is `+13.0 %`. There is no rest window on this build to compare against. | **UNMEASURED** |
| Readout mid-rep | Probe B, ten dumps across one squat rep, per head: `rectus_femoris A -0.6 … +17.8 %`, `biceps_femoris A long head +6.6 … +19.6 %`, `biceps_femoris A short head -12.0 … +16.2 %`, `gluteus_maximus A +0.0 … +44.7 %`, `latissimus_dorsi +7.2 … +9.9 %`, while `rectus_abdominis`, both `external_oblique` heads and both `erector_spinae` heads read ±0.0 % throughout. | PASS (probe) |
| Refusal budget | `0 refused` on every sample of every run, the control included. | PASS |
| Frame time | Full-frame p95 (the session's `beginFrame`→`endFrame` clock, 120 rolling samples) is 24.72 ms on the control and 24.85 ms on probe B — the layer adds nothing to it in either state, because in both the renderable never draws. The layer-on reading that the ≤ 1 ms budget is about does not exist on this build. | **BLOCKED** by the crash |
| Lifecycle | Unreachable: there is no live session to background. | **BLOCKED** by the crash |
| Exercise switch | Unreachable on device. The handoff itself is covered on the JVM by `CharacterStageHandoffTest`; the device cell stays unmeasured. | **BLOCKED** by the crash |

#### Evidence

The shipped artifact, fourteen seconds after launch — the app is gone and the launcher is in front:

```
I Filament: FEngine (64 bits) created at 0x6f25e2b420 (threading is enabled)
I Filament: FEngine resolved backend: OpenGL
I Filament: [Qualcomm], [Adreno (TM) 735], [OpenGL ES 3.2 V@0762.40 …], [OpenGL ES GLSL ES 3.20]
I Filament: Feature level: 2
I Filament: Backend feature level: 2
I Filament: FEngine feature level: 1
F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x4 in tid 15553 (monkfitness.app)
```

`0.546 s` separate those two lines. The tombstone, trimmed to the frames that name code:

```
F DEBUG   : Cmdline: com.monkfitness.app
F DEBUG   : Cause: null pointer dereference
F DEBUG   :       #00 pc 00000000001eb8f4  …/base.apk!libfilament-jni.so (offset 0xc08000)
F DEBUG   :       #01 pc 00000000001d9424  …/base.apk!libfilament-jni.so (offset 0xc08000)
F DEBUG   :       #02 pc 00000000001d8ba4  …/base.apk!libfilament-jni.so (offset 0xc08000)
F DEBUG   :       #03 pc 0000000000161670  …/base.apk!libfilament-jni.so (offset 0xc08000)
F DEBUG   :       #04 pc 000000000015b904  …/base.apk!libfilament-jni.so (Java_com_google_android_filament_Renderer_nRender+24)
F DEBUG   :       #10 pc 0000000000278c14  (com.google.android.filament.Renderer.render+0)
F DEBUG   :       #14 pc 00000000000170b4  (com.monkfitness.app.character.CharacterSession.render+0)
F DEBUG   :       #18 pc 000000000001683c  (com.monkfitness.app.character.CharacterSession.access$render+0)
F DEBUG   :       #22 pc 00000000000158d8  (com.monkfitness.app.character.CharacterSession$frameCallback$1.doFrame+0)
```

The identical `pc` offsets, fault address `0x4` and `BuildId 9f781035e69554b12b04f4317229be349165c219`
recur on all three `#405` launches and on `72f08a59`; the control build on the same driver, same
session, produces no crash and no tombstone. Filament's own native preconditions are compiled out of
the release library, and nothing here is a driver error: the fault is inside Filament's frame, before
any draw it could attribute to Adreno.

The control build, `e6d7b2b`, squat with the layer off — this is the #404 reading reproduced, and it
is what licenses the comparison:

```
18 heads · 0 refused · 16 drawn · 0 triangles
rectus_abdominis A head0: modelled -0.0%
gluteus_maximus A head0: modelled +36.8%
biceps_femoris A short head: modelled +12.7%
rectus_femoris A head0: modelled +2.8%
tubes: material requires TANGENTS, which a rigid tube does not supply
```

Probe B, the same build as the shipped artifact with only `buildRenderable()` suppressed:

```
18 heads · 0 refused · 16 drawn · 0 triangles
…
rectus_femoris A head0: modelled +11.4%
attrs POSITION,UV0,UV1,COLOR
requested Auto → active OPENGL
device API 36 · GLES 3.2 · Vulkan 1.3.0, level 1
34 bones · 5 morphs · neutral
512 frames · 43 fps · p95 24.85 ms · surface true · running true
```

#### The blocker

The attribute refusal is resolved and the exercise switch is fixed in composition, but the layer
cannot draw: Filament's render pass dereferences a null four bytes past its base as soon as the tube
renderable is in the scene. The probes narrow it to the renderable itself rather than to anything
that feeds it:

- the material instance alone (probe A) is harmless, so the unlit permutation and the
  `createMaterialInstance` call are not the fault;
- the vertex buffer, the index buffer and the upload rings without a renderable (probe B) are
  harmless, so the attribute declaration and the buffer sizes are not the fault;
- the renderable with zero indices (probe C) crashes, so it is not the draw or the index count;
- the renderable hidden by a zero layer mask (probe D) crashes, so `setVisible` is not the trigger
  and no toggle can avoid it.

A second session then replaced the layer's own construction with Filament's `hellotriangle` and
found the call underneath it; that run is below, and it changes the conclusion from "the layer's
renderable" to "an index upload on a live renderable".

#### The minimal triangle — second device run

A temporary control stood in for the layer: three `FLOAT3` positions, three `USHORT` indices, one
provider instance whose material requires `POSITION,COLOR,UV0,UV1`, `culling(false)`, a fixed
`2 m` box, and no `TubeMesh` code on the path at all. Every run below is the same build of the app
with one line of that control changed, on the same handset and driver as §7.7.

| Run | What the renderable does | Result |
| --- | --- | --- |
| `T1` | triangle in the scene beside the asset; every declared stream uploaded **once, before** the renderable exists, then never again | runs: `attrs PROBE POSITION,COLOR,UV0,UV1`, 466 frames, 42 fps, p95 24.73 ms, no tombstone |
| `T2` | `T1` plus one vertex-stream upload per frame (`VertexBuffer.setBufferAt`, all four streams) | runs: 486 frames at 48 fps, no tombstone |
| `T3` | `T1` plus one **index-buffer** upload per frame (`IndexBuffer.setBuffer`, three `USHORT`) | SIGSEGV in `libfilament-jni.so` `pc 0x1eb8f4`, fault addr `0x4`, after 661 uploads / 23 s |
| `T4` | this app's layer, with the renderable built once after the first frame's streams are committed and no geometry redefinition afterwards | draws `18 heads · 0 refused · 16 drawn · 13824 triangles`, 486 frames at 48 fps, no tombstone in that window; the shape-change path was not exercised |
| `T5` | this app's layer, full-capacity geometry built before the first frame, index upload on every shape change | SIGSEGV at `0x1eb8f4` 0.55 s after `FEngine created`, and again the first time `superficial` was toggled |

`T3` is the one that decides it. Its primitive is a single six-byte index buffer and three
positions; it has no tube geometry, no `BufferObject`, no `setGeometryAt`, and no attribute the
ubershader could not resolve. It survives indefinitely while it only uploads vertex streams (`T2`)
and dies at `0x1eb8f4` once the index buffer is re-uploaded on a renderable that is already in the
scene.

The rule the five runs agree on, and the one the tombstone has shown every time:

- a **vertex-stream** upload after the renderable exists is harmless (`T2`, `T4`);
- an **index-buffer** upload after the renderable exists faults the process inside Filament's cull
  pass (`T3`, `T5`, and the shipped artifact, whose first solved frame uploads indices);
- a primitive built with the geometry already in place is safe (`T1`, `T4`).

So the fault is neither the material, the attribute set, the vertex types, `culling`, the layer mask,
the shadow flags, the transform or `BufferObject` — each of those was varied on the layer itself and
reproduced the same `pc` and fault address — nor the tube geometry, which a three-vertex triangle
replaces without consequence. It is Filament 1.75.1's OpenGL backend on the Adreno 735
(`BuildId 9f781035e69554b12b04f4317229be349165c219`) mishandling an index-buffer upload on a
primitive that a scene already references. Filament's native preconditions are compiled out of the
release binary, so the null is not a refused call arriving late; nothing in this layer can decline
the call, because the solved set of heads is what sizes its indices.

This is recorded as an engine defect, to be closed by moving the engine rather than by reshaping the
tube layer. Avoiding the call from this side would mean never uploading indices after the primitive
exists, which the layer cannot promise while its geometry is the solved head set; `T4` survives
precisely because it stops changing geometry, and stops being the layer.

That conclusion did not survive the engine move — see *The 1.81.0 run* below.

#### What this run still cannot answer

- Alignment, orientation, winding in situ, tendon tint and the GPU draw cost: all downstream of a
  frame, and there is none.
- The push-up half of every row, and the exercise switch on device. `CharacterStageHandoffTest` pins
  the claim-side handoff on the JVM; this run did not see either exercise render with tubes.
- The ≤ 1 ms write-path budget. The two p95 readings above are full frames with nothing drawn, so
  they bound neither the layer's CPU cost nor its GPU cost; the question of whether the UV/colour
  uploads should move into the `topologyRevision` pass cannot be answered from them.
- PSS: memory was not sampled.
- Every row above, until the layer stops redefining its geometry. `T4` shows the geometry, the material
  and the counts are right and that the draw itself costs 48 fps alongside the asset; it cannot show
  alignment, tint or a seam because it is not this layer. Those readings wait on a layer whose
  primitive is given its geometry once — not on a Filament version, as the 1.81.0 run below shows.

#### The 1.81.0 run — the engine move does not close it

The same PR that raised `compileSdk` to 37 also moved Filament to 1.81.0. That build was installed
over the previous one and run twice on the same handset and driver.

| Run | Backend | Result | `BuildId` |
| --- | --- | --- | --- |
| default intent | requested Auto → active **OPENGL** | `Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000004`, process uptime **3 s** | `831f673f590c30fce635baf49cd029339febce3c` |
| `--es backend VULKAN` | **VULKAN** — `Selected physical device 'Adreno (TM) 735' … api 1.3`, `Backend feature level: 3` | the same fault, **0.5 s** after `FEngine created` | `831f673f590c30fce635baf49cd029339febce3c` |

Both tombstones carry the same chain:

```
Cause: null pointer dereference
#04 pc … libfilament-jni.so (Java_com_google_android_filament_Renderer_nRender+24)
#10 … (com.google.android.filament.Renderer.render+0)
#14 … (com.monkfitness.app.character.CharacterSession.render+0)
```

Only the offsets inside the library move (`0x1d78a4` where 1.75.1 had `0x1eb8f4`); the fault
address, the frame it lands in and the Java caller do not.

Three readings follow, and they replace the engine-defect conclusion above:

- the fault is **not backend-specific**: Vulkan reaches it with its own driver, so the Adreno OpenGL
  driver mishandling an index upload is excluded;
- the fault is **not version-specific**: 1.81.0's library fails where 1.75.1's did, so neither the
  engine version nor `compileSdk` is the lever;
- what both runs share is this layer's primitive — a renderable built before the first solved frame
  and then redefined (`IndexBuffer.setBuffer` + `RenderableManager.setGeometryAt`) on the frames
  after. `T1` and `T4` survived by never doing that, on the same engine, driver and material.

What is left to change is therefore the renderable's geometry lifecycle, not the engine: give the
primitive its geometry once, per shape revision, and rebuild rather than rewrite it. That is a change
to this layer and needs its own run; every row above stays unmeasured until it has one.

#### The primitive-per-shape run — the crash is gone

The layer now selects the material and the attribute plan in `prepare`, and builds the primitive —
vertex buffer, its `BufferObject` streams, index buffer, renderable — on the first solved frame that
has geometry, replacing it whole when the shape changes. Nothing calls `IndexBuffer.setBuffer` or
`RenderableManager.setGeometryAt` on a live renderable, and a steady frame costs one position stream.
The entity is created once for the layer and only its components are rebuilt: an intermediate build
that created and destroyed an entity per primitive made the *body's* own upload fail with
`setBones: Disable skinning buffer mode to use this API`, which stopped as soon as the id outlived
the shape.

Same handset, same driver, requested Auto → active OPENGL:

| Row | Measured | Verdict |
| --- | --- | --- |
| Crash gate | 60 s with the layer on: process alive, `logcat -b crash` empty, 3763 frames | **PASS** — the `nRender+24` tombstone is gone |
| Solve built | `18 heads · 0 refused · 16 drawn · 0 triangles` with tubes off | PASS |
| Tubes on | `16 drawn · 13824 triangles`, `attrs POSITION,UV0,UV1,COLOR · candidate 0` | PASS |
| Axis alignment | squat: the tubes run along the thighs and the torso, longitudinally rather than across | PASS |
| Tint | deep red at the belly, lighter toward both ends of the same tube | PASS |
| Layer toggle | `superficial` → `16 drawn · 0 triangles`, back → `16 · 13824` | PASS |
| Deep toggle | unchanged (`16 · 13824`) | PASS |
| Exercise switch | Squat → Push-up → Squat: a new session each time (`18 heads · 16 drawn · 13824`), never `no character session` | PASS |
| Lifecycle | home → 7 s → return: same process, `16 drawn · 13824`, `surface true · running true`, no tombstone | PASS |
| Refusal budget | 20 samples over a rep: 16 × `0 refused`, 1 × `1 refused` (`gluteus_maximus A head0`), 3 × `2 refused` (`biceps_femoris` long and short heads) | **FAIL** as written ("0 refused on every frame") |
| Frame time | p95 with the layer off 27.20 ms, on 29.73 ms, same session 15 s apart: **+2.53 ms** | **FAIL** against the ≤ 1 ms bar |
| Readout at rest | a looping rep has no rest window; the mid-rep readings move as §7.7 records | UNMEASURED |

The two failures are measurements of the solve and of the draw — refusals come from `AnatomyFrame`
and the frame time from the 13,824-triangle submission — not of the geometry lifecycle, and neither
is a crash. They are recorded as they read rather than retuned.

#### The Stage 2b run — the topology stops moving, and the solve stops refusing

The primitive-per-shape run left two rows failing and one unmeasured. Both failures turned out to
share a cause with the layer above them, and closing them changed what this layer does on a frame
that refuses a head.

**The topology is fixed.** A refused head used to change how many heads the mesh wrote, so the layer
above tore its vertex buffer, its `BufferObject` streams, its index buffer and its renderable down
and built them again — four frames in twenty. Every head of the definition now owns its quota on
every frame while the tubes are on: a head the selection hides or the solver refuses is written
collapsed onto one point, which makes its triangles zero-area without changing a single count.
`TubeMesh` builds its primitive once, at capacity, and afterwards uploads positions and nothing else.

**The fold is repaired.** `project` pushes every sample that sits inside its tube radially outward,
and a sample pushed while its neighbours stay put is a one-point spike: the curvature row reads it as
a fold, and the segments either side of it cut into the very obstacle it was clearing. On the squat
rep that cost four samples in twenty — `biceps_femoris` long and short heads and `gluteus_maximus A`.
`MusclePathSolver.unfold` now relaxes a window of samples around the apex onto their own chords and
pushes them back out, so the spike becomes the arc the one-waypoint detour was approximating. A fold
the repair cannot remove puts the polyline back unchanged, so a refusal that belongs to the geometry
is still refused.

Same handset, same driver, requested Auto → active OPENGL, `main` `e1923f1e` plus this branch,
`versionCode` 996008, APK 43,865,357 bytes, `FILAMENT_CHARACTER_ENABLED = true`:

| Row | Measured | Verdict |
| --- | --- | --- |
| Crash gate | One session of 6104 frames with the layer on, plus four more launches across the run: process alive, `logcat -b crash` empty | **PASS** — no `nRender+24` tombstone |
| Solve built | tubes off: `18 heads · 0 refused · 16 drawn · 0 triangles` | PASS |
| Tubes on | `18 heads · 0 refused · 16 drawn · 13824 triangles`, `attrs POSITION,UV0,UV1,COLOR · candidate 0` | PASS |
| Primitive builds | `1 primitive build(s)` after 6104 frames of a looping rep | **PASS** — the count the fixed topology exists for |
| Attributes | `POSITION,UV0,UV1,COLOR`, the provider's own permutation, no refusal line | PASS |
| Axis alignment | screenshot: the tubes run longitudinally along the torso and the visible limb, never across it | PASS |
| Tint | darker red at mid-length, lighter toward both ends of the same tube | PASS |
| Layer toggle | tap `superficial`: the tubes disappear while the counts stay `16 drawn · 13824 triangles` — a toggle now collapses heads instead of shrinking the draw | PASS |
| Deep toggle | unchanged (`16 · 13824`) — the M1 library has no deep muscle | PASS |
| Exercise switch | tap `Squat` while on Squat: a new session, a full readout on its first published frame | PASS |
| Refusal budget | 20 samples over the rep with the layer off and 20 with it on: `0 refused` in all 40 | **PASS** — the row the previous run failed at 4 in 20 |
| Frame time, cool SoC | 44.9 °C when the reading started: p95 **30.33 ms** with the layer off, **30.40 ms** with it on, same session 30 s apart: **+0.07 ms** | **PASS** against the ≤ 1 ms bar |
| Frame time, warm SoC | same session, ~66 °C: p95 31.28 ms off → 34.00 ms on: **+2.72 ms** | recorded, not a pass |
| Readout at rest | The overlay's 500 ms poll cannot sample progress 0: its first published frame already reads `gluteus_maximus P +25.7 %`. Measured on the same solver at progress 0 instead: ten of twelve heads within ±0.1 %, but `latissimus_dorsi` **+8.1 %** and `biceps_femoris` head1 **+15.5 %**, both sides | **FAIL as written** — the squat's opening pose is not the anatomy's reference stance |
| Lifecycle | home → 7 s → return: `surface true · running true · 13824 triangles` | PASS |

Three readings follow, and one of them corrects the record above.

- The frame-time failure was **thermal, not allocation.** With the allocation spikes actually gone —
  one primitive build in 6104 frames where there used to be a rebuild on each of the 4-in-20 frames
  that refused — a cool SoC reads **+0.07 ms**, and the same session read again at 66 °C reads
  **+2.72 ms**. The earlier `+2.53 ms` was measured on a handset that had been building for forty
  minutes, so the bar it failed was being read through the throttle.
- **The refusal budget is closed by the solver, not by the mesh.** The collapsed-head geometry would
  have made a refusal cheap; `unfold` is what stops the refusal happening. Both are needed: the first
  keeps the layer from rebuilding, the second keeps it from having nothing to draw.
- **The readout-at-rest row fails for a content reason.** The row presumes the rep starts at the
  reference stance; the squat's opening pose is a standing stance whose shoulder girdle and hamstring
  attachments already differ from the bind pose, so `latissimus_dorsi` and the second
  `biceps_femoris` head read a real length change at progress 0. That is a pose/content question, not
  a solver defect, and it is recorded as the row reads rather than retuned.

**Stage 2b sign-off.** Every §7.7 row is now a reading rather than a blocker: the crash gate holds,
the tubes draw the 13,824 triangles the primitive was built for, the layer builds exactly one
primitive, the solve refuses nothing across a rep, and the frame-time bar passes on a cool SoC. The
one remaining failure is the `readout at rest` row, and it is a mismatch between the row's expectation
and the squat's opening pose rather than a defect in this layer.

#### The Stage 3 run — the authored layer never uploads, and the frame loop stops

PR #419 made the authored muscle asset addressable per instance. This run tried to measure that layer
on the same handset and failed before it drew a section, so **no `showsMeshes` frame breakdown exists to
record**; what follows is what the device actually read.

Wireless debugging was reconnected inside the container (`adb connect 127.0.0.1:36027`, a listener on
uid 2000 — the stored `~/.android/adbkey` still paired, so no pairing code was needed). The debug APK was
built on the handset, `FILAMENT_CHARACTER_ENABLED = true` confirmed in the generated `BuildConfig`, and
installed with `adb install -r -d`:

| Property | Value |
| --- | --- |
| Device | motorola edge 50 ultra (`ctwo_geuw`), API 36, GLES 3.2, Vulkan 1.3.0, requested Auto → active **OPENGL** |
| APK | 44,167,512 bytes, `sha256 23631109079da13ddbbf09cbdfc277b147854e905a527629e9837d58b3b9a8b6`, `versionCode` 997863 |
| Launch | `am start -n com.monkfitness.app/.character.CharacterDiagnosticsActivity` |
| SoC | battery **41 %, 31.0 °C** before and after the window — cool |
| Crash | `logcat -b crash` empty; the activity stayed `topResumedActivity` |

| Row | Measured | Verdict |
| --- | --- | --- |
| Readout reaches the layer switch | tapping `трубки` (tubes) gives `18 heads · 0 refused · 16 drawn · 13824 triangles`; tapping `сетки` (meshes) renders the per-instance chip row (`0 rectus_abdominis:A`, `1 rectus_abdominis:P`, …) | PASS — the control is live, not unreachable |
| Authored layer draws | `mesh 0/16 sections · 0 palette uploads · not drawn 16`, `muscleSkin 0.00/0.00` in every sample; the counters do not move for the whole window | **FAIL — nothing drew** |
| Per-instance hide and highlight | not reachable: a chip cannot hide or tint a section that never drew | **BLOCKED** by the row above |
| Frame breakdown, `showsMeshes` | the readout's frame counter froze at **714 frames** partway through the window and reported the identical sample at every later read, so there is no 20 s window to average | **NOT RECORDED** |
| Frame breakdown, last live sample | `pose 0.46/0.66 · anatomy 7.66/9.99 · anatomyFrames 0.09/0.13 · anatomyPaths 7.53/9.88 · tube 2.47/2.86 · retarget 0.19/0.28 · muscleSkin 0.00/0.00 · beginFrame 0.03/0.05 · render 0.12/0.17` ms; `714 frames · 59 fps · p95 13.39 ms`; `passes (cap 8): 1:2718 2:238 3:264 4:388 5:10` | recorded, **not** the mesh-layer reading |

The overlay, verbatim, in the state the run ended in:

```
18 heads · 0 refused · 16 drawn · 13824 triangles
… 18 modelled-length rows …
mesh 0/16 sections · 0 palette uploads · not drawn 16
attrs POSITION,UV0,UV1,COLOR · candidate 0
34 bones · 5 morphs · neutral
714 frames · 59 fps · p95 13.39 ms · surface true · running true
stage p50/p95 ms: pose 0.46/0.66 anatomy 7.66/9.99 anatomyFrames 0.09/0.13 anatomyPaths 7.53/9.88
  tube 2.47/2.86 retarget 0.19/0.28 muscleSkin 0.00/0.00 beginFrame 0.03/0.05 render 0.12/0.17
```

**What is established, and what is not.** Established: the layer's asset loads — `16` sections are
reported by the stage, so `MuscleLayerStage.prepare()` succeeded and its renderables exist — the switch
reaches the UI, and the solver runs (`0 refused`, `16 drawn` on the tube layer, `13,824` triangles).
Not established: why `muscleSkin` never records a sample, which is the same frame on which the counter
stops. Two observations narrow it without proving it — the `muscleSkin` stage is recorded only after the
upload path's guard, and the frame loop stops posting in the same window — but this run does not
separate "the upload guard refuses every frame" from "the surface detaches and the session stops". A
build with one of the two instrumented would.

**This row is not a regression.** It is the first time the authored layer has been asked to draw on a
device: the Stage 2b run above measured the procedural tube layer only, and §12.5 records that the
authored-mesh half of L5 was never exercised on hardware. Nothing in this run contradicts the #415
numbers; it simply leaves that configuration unmeasured.

**Consequences for the Stage 3 gate.** Stage 3's automated acceptance does not include a frame-rate
number (§15: it is recorded as information, never gated), so this run blocks no acceptance criterion.
It does mean the closing PR's device evidence is the tube layer's, and the per-instance visibility and
highlight demonstration on real hardware is still owed — the JVM tests cover the mask and the tint, and
a device run is what would show them on a surface.

### 7.8 Device run — the authored layer's per-instance path

§7.7 ended with one demonstration still owed: per-instance visibility and a per-instance highlight on
real hardware. This section records that run, and the render-path change that makes the highlight safe
to ask for.

#### What changed on the render path

| Before | After |
| --- | --- |
| A selection called `RenderableManager.setMaterialInstanceAt` while frames were being drawn | A selection writes `baseColorFactor` and `emissiveFactor` on that section's own instance — a uniform write; no renderable state is touched |
| Per-section instances came from `MaterialInstance.duplicate()` on gltfio's instances | Each instance is built in `MuscleLayerStage.prepare()` with `Material.createInstance()`, given `muscles_sup.glb`'s authored `baseColorFactor`/`metallicFactor`/`roughnessFactor`, and assigned to its renderable once, before the entity enters the scene |
| Teardown freed the instances as soon as the frame loop stopped | `Engine.flushAndWait()` drains the render thread, `destroyAsset()` runs, and only then does `releaseMaterials()` free the instances |

The debug host drives the layer without dumping the UI: `am start --activity-single-top -n
com.monkfitness.app/.character.CharacterDiagnosticsActivity --es action <verb> --es instance
<muscle:side>` (`meshes`, `hide`, `show`, `select`, `exercise`, `pause`, `resume`, `step`, `overlay`),
and one `CharacterDevice` log line per 500 ms carries the frame count, the smoothed fps, the frame p95,
every stage's p50/p95 and the layer's own state. No `uiautomator dump` is involved.

#### Build and device

| Property | Value |
| --- | --- |
| Build | `assembleDebug -Pmonk.filamentCharacter=true`, `BuildConfig.FILAMENT_CHARACTER_ENABLED = true` |
| APK | 44,200,280 bytes, `sha256 8606439f1dc97b8e2ae453dfea0a8725183459b83e7b61e613c11594460f72d7` |
| Install | `pm install -r -d` over `rish` (Shizuku): `Success` |
| Device | motorola edge 50 ultra (`ctwo_geuw`), Android 16 / API 36 |
| SoC + GPU | Qualcomm (QTI) · Adreno 735 |
| Backend | requested Auto → active **OpenGL** |
| Display | 1220 × 2712 |

#### The 20-second window

Squat, meshes on, one sequential run. The readings are the 40 telemetry lines inside the window (one
per 500 ms), read back with `logcat -d -v time -s CharacterDevice:I`.

| Reading | Value |
| --- | --- |
| Window | 19.7 s, 40 samples |
| Sections drawn | **16 / 16 on every sample** |
| Frames | 863 → 2,029: **1,166 frames, 59.3 fps** over the window |
| Smoothed fps (session counter) | median **59**, min 54, max 59 |
| Frame p95 | median **11.79 ms** (10.68 … 13.53) |
| `anatomyFrames` p50/p95 | **0.04 / 0.06 ms** (max 0.10 / 0.13) |
| `anatomyPaths` p50/p95 | **8.14 / 10.59 ms** (max 8.76 / 12.65) |
| `muscleSkin` p50/p95 | **0.17 / 0.23 ms** (max 0.27 / 0.40) |
| PID | 24821 at launch, **24821** at the end |
| `logcat -b crash` | **0 bytes** |

The smoothed counter and the frame delta agree here (59 against 59.3 fps). An earlier run of this code,
taken while the handset was warm from an hour of 59 fps sessions, measured 53.5 fps average and a
smoothed median of 56 with the same 16/16; both readings are recorded rather than only the cool one.

#### Per-instance hide, show and select

Verbatim from the run, in order (`…` elides the head of each line):

```
… hide    frames=2392 fps=59 p95=11.26 mesh=15/16 solved=true selected=- highlight=false hidden=rectus_abdominis:A
… show    frames=2781 fps=59 p95=12.13 mesh=16/16 solved=true selected=- highlight=false hidden=-
… select  frames=3138 fps=59 p95=11.09 mesh=16/16 solved=true selected=rectus_abdominis:A highlight=true hidden=-
```

Hiding one instance drops the drawn count to 15 and names it; showing it restores 16/16; selecting it
reports the selected instance and `highlight=true`. The PID does not change across the three and the
crash buffer stays empty.

#### What this run does not establish

The tint is confirmed at the material level, not as pixels. With all sixteen instances carrying
`emissiveFactor (1, 0.4, 0.05)` at `emissiveStrength 3` in a probe build, a frame whose telemetry reads
`mesh=16/16` contains **no** pixel with R > 80 and R − B > 30; hiding all sixteen sections moved 1.5 %
of the sampled cells (3,135 of 206,790 — the order the debug text moves), while toggling meshes moves
11.6 % only because the overlay grows a line when the mesh row appears. The sections do draw: Filament
names all sixteen `muscleSection` instances in its own sampler warnings. The likeliest reading is that
the authored sections sit inside the opaque body mesh, so no per-instance colour reaches the frame. A
pixel-level highlight needs the body hidden or the sections drawn over it; that is a separate change,
and this run does not claim it.

One native crash was observed in this session: a switch from squat to plank with the layer live died
with SIGSEGV in `FEngine::loop` (`libfilament-jni`, fault address `0xb4000078d067ceaf`, 50 s into the
process). Four further switches on the same build did not reproduce it, and the recorded run above does
not switch exercises. `Engine.flushAndWait()` was added at teardown after that observation, so the
render thread is drained before the asset and its instances are freed; one clean run is not proof that
it is what keeps the switch safe.

## 8. Stage 4 — multi-layer skinned rendering (both partitions)

### 8.1 What landed

The atlas is exported as two skins — 46 superficial sections over 242 joints and 20 deep sections
over 114, 288 helpers between them — and the runtime draws both from one solved frame.

| Before | After |
| --- | --- |
| `MuscleLayerSkinner` bound every head of the frame to one manifest, so a partition's helper count had to equal the whole frame's | The skinner maps the frame's heads onto *its* partition and skips the heads another skin binds (`groupBySlot` carries `-1`), so one 72-head frame feeds a 242-joint palette and a 114-joint one |
| `MuscleLayerStage` loaded `muscles_sup.glb` and refused the whole layer when a section of the other partition was missing | The stage loads, validates, skins and tears down one asset per partition, transactionally: a refusal is reported per partition and leaves the other drawing |
| Section addressing covered one asset | Section addressing is global over all 66 sections; each section names its asset and its renderable inside it, and each asset keeps its own palette buffer |
| Toggling a layer off turned the mesh view off | The partitions toggle independently; only the last one out drops the view flag, because a scene with no muscle has nothing to solve |
| The stage's selection tint lived on one asset's instances | One `Material.createInstance()` per section across both assets, assigned before `scene.addEntities`; a selection is still two uniform writes |

Unit suite over the change: `classes=437 tests=4018 failed=0 errors=0 skipped=0`
(`:app:testDebugUnitTest`, JUnit XML summed). The archive's frozen seams are untouched:
`animation/**`, `poses/**`, `CharacterFrameProducer.kt`, `ExerciseCameraFramer3D.kt` and the nine
`UNAFFECTED_CORPUS_DIGEST` guards do not appear in the diff.

### 8.2 The device run — build and device

| Property | Value |
| --- | --- |
| Build | `assembleDebug -Pmonk.filamentCharacter=true`, `FILAMENT_CHARACTER_ENABLED = true` |
| APK | 44,870,508 bytes, `sha256 735ae1e37ea4298754e06408d10118e02b05a17e1f7ba3baaa024889361d6382` |
| Install | `pm install -r -d` over `rish` (Shizuku): `Success`, `versionCode` 1001346, `lastUpdateTime 2026-11-27 11:00:30` |
| Device | motorola edge 50 ultra (`ctwo_geuw`), Android 16 / API 36 |
| SoC + GPU | Qualcomm (QTI) · Adreno 735 |
| Filament | 1.81.0, `libfilament-jni.so` BuildId `831f673f590c30fce635baf49cd029339febce3c` |
| Display | 1220 × 2712, `presDeadline 16666666` (60 Hz) |
| Backend | requested Auto |

### 8.3 What the layer does when it draws

The two skins do load, validate and draw together. Verbatim from the run, the reveal and two
samples while both partitions are live:

```
… action=meshes instance=- applied=true
11-27 11:06:55.909 I/CharacterDevice(19874): frames=283 fps=55 p95=2.64 mesh=66/66 layers=superficial,deep solved=true …
11-27 11:20:31.055 I/CharacterDevice(29071): frames=3111 fps=59 p95=4.31 mesh=66/66 layers=superficial,deep solved=true selected=rectus_abdominis:A highlight=true …
```

`mesh=66/66` with `layers=superficial,deep` is the whole atlas drawing — 46 superficial plus 20 deep
sections, both palettes written from the same frame. Independent toggling is visible in the same
stream: `layers=superficial` with `mesh=46/66`, then `layers=deep` with `mesh=20/66`, then back to
`layers=superficial,deep` with `mesh=66/66`.

Short windows (12 s) at `66/66` were obtained repeatedly — six consecutive runs with an unchanged
PID at 55–59 fps and a frame p95 of 2.5–4.7 ms — and one 20 s steady window at `66/66` was measured
at `frames 1 → 306` before the app died (see 8.4).

### 8.4 The blocker: an intermittent SIGSEGV in `FEngine::loop` at the reveal

**The 20-second acceptance in the brief is not met.** The full window — 20 s with both partitions
live and the layer toggles, per-section hide and select inside it — could not be completed with an
unchanged PID and an empty crash buffer. Every attempt that died did so the same way:

```
Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xb4000077fb8a48cf in tid … (FEngine::loop), pid … (monkfitness.app)
#00 pc 0000000000210b80  base.apk!libfilament-jni.so (BuildId: 831f673f590c30fce635baf49cd029339febce3c)
#01 pc 00000000001f86a8  …   #05 pc 00000000001a18ac
```

The fault occurs 0.08–0.3 s after `action=meshes applied=true`, on the frame the skinned sections are
revealed; `logcat -b crash` stays **0 bytes** (the tombstone goes to DropBox, and
`dumpsys activity exit-info` records `reason=5 (APP CRASH(NATIVE)) status=11`).

Attempt ledger, one build, one device, in order:

| Sequence | Result |
| --- | --- |
| 3 × "meshes after settle" (A/B) | survived, `66/66`, PID unchanged |
| 6 × full 20 s window with toggles | **6 of 6 died** at the reveal |
| 3 × 12 s window, tubes first | survived |
| 2 × 12 s window with a continuous `logcat -f` watcher | survived |
| 3 × 12 s window, polling dumps only | survived |
| 1 × 20 s steady window, 6 s settle | **died** at the reveal |
| 1 × 20 s window with toggles | **died** mid-window (PID 9278 → 9947) |

What the bisect established:

| Probe | Result |
| --- | --- |
| `tubes` alone, 22 s | clean (`anatomyPaths` p50/p95 7.4–9.7 ms — the tessellation cost the skinned layer is meant to replace) |
| mesh layer, 1 section drawn | clean for 12 s |
| mesh layer, 4 / 8 / 16 / 24 / 32 / 40 / 46 sections drawn (5 s windows, probe build) | clean in every window |
| superficial asset alone (46 sections, 242 joints) | died at the reveal |
| deep asset alone (20 sections, 114 joints) | died at the reveal |
| main @ `19f880e8` (single asset, same export) | clean, but `mesh=0/0`: the shipped stage refuses the 66-section partition because its keys name sections `muscles_sup.glb` does not carry — the defect this change fixes |
| bones not uploaded at all (probe) | died at the reveal |

So the crash is not the dual wiring, not one partition's palette (114 and 242 both die), and not the
bone upload itself: drawing this exported skin at all, for long enough, kills the render thread
intermittently. A 16-section/106-joint export of the same material and geometry drew for 20 s in the
Stage 3 run recorded above, so the trigger scales with the export, not with the new stage code.
`libfilament-jni` has no symbols for these frames, and the same signature was already recorded once
in Stage 3 (`FEngine::loop`, unexplained) — this run reproduces it at will and adds the scale
evidence, but does not explain it.

### 8.5 The isolation run — A and B are both disproven

Three probes, each one build, run on the same handset with the crash live:

| Probe | Change under test | Result |
| --- | --- | --- |
| **A** | `Material.createInstance()` and `setMaterialInstanceAt()` removed entirely: every section keeps the `gltfio` instance the asset shipped, and the selection writes no tint | **still died at the reveal**, 3 of 3 runs (`action=meshes` at 15:52:59.800, process replaced 19 s later, `reason=5`) |
| **B** | the authored layer live from the first frame (`showsMeshes = true`), so nothing flips 66 masks at the reveal | **died at the first drawn frame**, 3 of 3 (`frames=1`, `p95 76.98 ms`, then the process is gone) |
| **C** | sections cast no shadow (`setCastShadows(false)`) before the mask write | still died at the reveal, 2 of 2 |
| **D** | half the sections' palettes uploaded per frame (`local % 2 == parity`), i.e. half the per-frame bone traffic | no change in the crash; the 20 s background dwell stayed clean as before |

So neither candidate is the trigger on its own: the render thread dies with no material cloning, with
no mask flip at all, and with the shadow pass excluded. What the runs do separate is the *surface*:

| Configuration | Result |
| --- | --- |
| 66/66 live, app **in the background** (no visible surface of its own), 20–25 s dwell, no interaction | clean, `0` crashes, `mesh=66/66`, smoothed 59, p95 2.3–2.4 ms |
| 66/66 live, app **in the foreground**, revealed by intent | dies 0.03–0.6 s after the first 66/66 telemetry line (`frames=423 … mesh=66/66` then no further sample from that PID) |
| 66/66 live, foreground, seven separate `am start` toggles across the window | died at the reveal, 2 of 2 |
| 16 sections drawn (limit probe), foreground, 30 s | clean |
| 8 / 24 / 32 / 40 / 46 sections (limit probe), 30 s | clean in this pass; the same 46-section code died at the reveal in an earlier pass, so this is state, not threshold |

A 20-second foreground window that reveals the layer, toggles both partitions, hides, shows and selects
one section therefore **was not obtained**: it ends in the same `FEngine::loop` fault within a second
of the reveal, on every attempt in which the app owns the visible surface. A drain of the render thread
on the frame a mask moves (`Engine.flushAndWait()` after a mask change) was built and tested as a
mitigation and did not help; it is not in the tree.

The `sequence` debug action (`--es action sequence --es instance <muscle:side>`) was added by this run:
one intent drives both layer toggles, the hide, the show and the select from inside the app, so a
window can contain the whole interaction with a single foreground transition. With the layer in the
background it runs all seven steps; with the layer drawn on the visible surface the process is gone
before the second step.

### 8.6 What the draw actually needs — and what it does not

The probes were extended to separate the *frame path* from the *draw*, and the palette from the
asset. Each line is one build on the handset:

| Probe | Change | Result |
| --- | --- | --- |
| **E** | the partition is loaded, validated and skinned, masks are written, but `scene.addEntities` is skipped — nothing of it is drawn | **clean**, 22 s, same PID, 0 crashes, 1,342 frames (~61 fps) |
| **G** | entities in the scene, and the palette is overwritten with a valid rigid identity before upload, so no skinner value reaches the GPU | **crashes at frame 1** (`frames=1`, p95 70.47 ms) |
| **A** | no `Material.createInstance` / `setMaterialInstanceAt` | crashes at frame 1 |
| **C** | sections cast no shadow | crashes at the reveal |
| **D** | half the palettes uploaded per frame | no change |
| **B** | 66 sections live from frame 1 | crashes at frame 1 |
| deep-only | 20 sections, 114 joints, live from frame 1 | crashed at frame 1 (1 of 1) |
| sup-only, 8 / 16 / 20 / 24 sections drawn, 242 joints, app resumed | 0 crashes each, frames advancing (`502 → 3,554`, `530 → 4,167`) |
| sup-only, 40 / 46 sections drawn | inconclusive: the session kept resetting (final `mesh=0/46`), so nothing is claimed |

So the frame path is innocent: with the same solve, skinner, mask writes and tube-slot handling
running at 61 fps for 22 s and no muscle entity in the scene, it is clean. The draw is what faults,
and it faults with an identity palette, without app-owned materials, without shadows, and with half
the bone traffic. The asset itself is byte-for-byte the same *shape* as the one PR #420 rendered:
same primitive/accessor layout, `JOINTS_0` u16, weights normalised, no misaligned accessor, no joint
index out of range, and the same node hierarchy (root `Muscles`, joints `1..N`, mesh nodes as its
children, one skin, `skeleton: 0`) — only the counts differ (16 sections / 106 joints → 46 / 242 and
20 / 114).

That leaves no single structural variable the renderer controls. The crash reproduces at 66 sections
in the dual build and at 20 in the deep-only build, and not at 46 in the sup-only build, and the
handset's focus churn (the chat app re-takes the resumed activity between `am start` calls) makes the
drawn-section counts unreliable above ~24: a window that looks "clean" can be a window in which the
loop was paused and nothing was drawn. Any further isolation needs a harness that holds the activity
resumed without starting it, and an export sweep (fewer heads, hence fewer joints and sections) rather
than more probe builds on the same two assets.

What remains unexplained, and unchanged from 8.4: the fault is inside `libfilament-jni` (BuildId
`831f673f590c30fce635baf49cd029339febce3c`) with no symbols, appears only when these exported skins are
drawn on a visible surface at 20–66 sections, and does not reproduce with the 16-section/106-joint
export of the same material that Stage 3 ran for 20 s.

### 8.7 The drawn-renderable sweep (deep asset, one build, focus held)

A probe build gated `scene.addEntities` on `muscles_deep.glb` to its first `K` sections, with the
layer live from frame 1, and each run re-asserted the task as the resumed activity (`am start
--activity-single-top` with no extras) so the loop was demonstrably drawing: every clean row below
advanced frame counts into the thousands.

| `K` drawn (of 20) | crashes | frames | note |
| --- | --- | --- | --- |
| 1 | 0 | 137 → 2,954 | resumed = our activity |
| 2 | 0 | 139 → 3,308 | |
| 4 | 0 | 137 → 3,346 | |
| 8 | 0 | 125 → 3,292 | also 141 → 642 in a second run |
| 10 | 0 | 123 → 3,340 | |
| 12 | 0 | 135 → 3,396 | also 128 → 3,890 |
| 13 | 0 | 127 → 4,009 | and 142 → 3,882 (two runs) |
| **14** | **6** | 1 → 1 | p95 67.15 ms; diag shows `frames=1` from the first sample |
| **16** | **7** | 2 → 2 | p95 36.63 ms |
| **20** | **6** | 1 → 1 | p95 69.32 ms |

The boundary is exactly 13 → 14, and it reproduces. But it is not a section and not a count the
renderer can act on: all twenty deep sections are structurally identical (106 vertices, 208
triangles, `u32` indices, one primitive, six attributes each), and both skins' inverse bind
matrices are well-formed (|det| between 1.0 and 1.0000004, no non-finite entry), so there is no
"bad section" to point at. The same sweep on `muscles_sup.glb` (242 joints per renderable) draws
24 sections cleanly — three runs, 0 crashes, `frames 518 → 3,258` — while 14 sections of the
deep asset (114 joints each) do not. Drawn count, joints per renderable, total bones, total
vertices and total triangles therefore all fail to explain the pair: 24 × 242 is far larger than
14 × 114 by every one of those measures, and it survives.

What the sweep does establish is the shape of the bug: it arrives with the **first** drawn frame
(`frames=1`, nothing after), it needs entities in the scene, and for `muscles_deep.glb` the number
of drawn sections crosses it between 13 and 14. It is not a steady-state limit, so no amount of
capture-time mitigation reaches it.

### 8.8 What this run does not establish

**The crashing function.** `dumpsys dropbox --print data_app_native_crash` yields 73 entries, all
`signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)`, all with the same nine frames and the same fault
address shape (`0xb40000784655be4f`), in `libfilament-jni.so` BuildId
`831f673f590c30fce635baf49cd029339febce3c` at `#00 pc 0x210b80`, then `0x1f86a8`, `0x1ea770`,
`0x1e9fd4`, `0x1a5530`, `0x1a18ac`, `0x1a868c`. The shipped `.so` is stripped: it carries
`.dynsym` only (1,913 exported symbols) and no `.symtab`, so nearest-symbol lookup over those
offsets lands hundreds of kilobytes inside unrelated exported functions and names nothing. Naming
those frames needs a Filament 1.81.0 build with symbols, not more probes.

- **A 20 s window with `66/66`, the toggles and an unchanged PID.** Short windows are clean and the
  readings above are real, but the window the brief asks for ends in the native fault more often than
  not. Nothing here should be read as "the full-atlas pipeline is stable on this hardware".
- **~59 fps as a sustained figure for the skinned path.** `fps=59` appears in the surviving samples
  and in the smoothed counter, while `frames` advanced at 50 fps over the same 6 s window, so the
  counter and the frame count disagree by ~15 % here exactly as they did in the Stage 3 note. The
  honest reading is "the layer draws at the display rate while the solve is ~8 ms", not "59 fps".
- **The selection tint as pixels.** Unchanged from 8.4 of the previous section: the sections sit
  inside the opaque body, and this run did not capture pixels.
- **Any of the numbers under a clean build.** Every reading above comes from the APK whose hash is in
  8.2; the probe builds used to bisect the crash (sup-only, deep-only, 1-section, limits) are not
  part of the shipped tree.
