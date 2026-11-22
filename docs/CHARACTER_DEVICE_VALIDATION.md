# Filament character — Stage 1 rig evidence and Stage 2 validation

Stage 1 — the generated `humanoid_v2` rig, the position-driven retarget and the device gate — is
merged, and §0 records what it measured. The procedure in §1–§6 is the **Stage 2** checklist: there
the presenter ships behind a disabled-by-default flag, and none of those checks have been executed.
There is no GPU device in the build environment, so everything in §1–§6 that needs pixels, a driver
or a lifecycle transition is for a physical device and must be recorded, not assumed. (§7.7 is the one
executed device run: the muscle-tube layer on an Adreno 735 handset.) The automated
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
| Declared attributes | exactly `Material.requiredAttributes` — planned for `POSITION, UV0, UV1, COLOR` with `TANGENTS` absent, so shading would be derivative-based (faceted). **Measured on the Adreno 735: the instance reports `TANGENTS`, so the layer refuses and draws nothing — §7.7** |
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

The rows below are the ones this layer adds to §3. They have now been run on hardware, on `main` at
`e6d7b2b` (PR #403); every row is either measured or marked with what blocked it.

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

`BUILD SUCCESSFUL in 6m 20s`, APK 22,814,418 bytes. Installed with the package-installer session API
(`cmd package install-create/-write/-commit` over `rish`; `pm install` is refused on this channel),
`versionCode` 989591 → 994075.

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

#### Results

| Row | Measured | Verdict |
| --- | --- | --- |
| Attributes | The instance the `UbershaderProvider` returns reports **`TANGENTS`** among its required attributes, so `TubeMesh.prepare` (`TubeMesh.kt:80`) hands a set the planner refuses (`TubeAttributePlan.kt:76-79`, `:98`). No `attrs` line is printed at all; the overlay prints `tubes: material requires TANGENTS, which a rigid tube does not supply` on every frame. No `FILAMENT_CHECK_PRECONDITION` line, no Adreno error, `logcat -b crash` empty. | **FAIL** — the layer never builds on this driver |
| Axis alignment / orientation | Not observable: the layer is refused, nothing is added to the scene. Triangle count rises to `13824` and no red pixel reaches the screen (see evidence), so neither the 90° axis question, the outward winding, nor any seam residual can be read here. | **BLOCKED** by the attribute refusal |
| Tint | Not observable for the same reason — there is no belly or end-ramp to compare. The readout half of the row **passes**: `relativePathLengthChange` changes per head across the squat rep, `0 refused` on every sample. | **Tint BLOCKED · readout PASS** |
| Frame time | p95 CPU frame time (`beginFrame`→`endFrame` wall clock, rolling 120 frames) with the layer on vs off, two rounds in opposite order: **+2.18 ms** and **+2.24 ms** median. Since the layer is refused, that delta is the solve + 13,824-triangle write + layer bookkeeping only — the upload returns early (`TubeMesh.kt:130`) and no draw is submitted. | **FAIL against the ≤ 1 ms bar**, and the draw half is still unmeasured |
| Tubes on | `18 heads · 0 refused · 16 drawn · 13824 triangles`; nothing appears over the body. | FAIL (row above) |
| Layer toggle | `superficial` → `16 drawn · 0 triangles`, tap again → `16 · 13824`. | PASS |
| Deep toggle | unchanged (`16 · 13824`) — the M1 library has no deep muscle. | PASS |
| Readout budget | `0 refused` on every sample, squat at rest and mid-rep. | PASS |
| Lifecycle | home → 6 s → return with tubes on: same session, `surface true · running true`, triangle count unchanged, no crash entry. | PASS |

#### Evidence

Overlay, verbatim, squat with the layer on (`16 drawn · 13824 triangles`):

```
18 heads · 0 refused · 16 drawn · 13824 triangles
rectus_abdominis A head0: modelled +0.0%
…
latissimus_dorsi A head0: modelled +7.6%
gluteus_maximus A head0: modelled +4.1%
biceps_femoris A short head: modelled +15.5%
rectus_femoris A head0: modelled -1.2%
tubes: material requires TANGENTS, which a rigid tube does not supply
```

and the same block later in the rep, showing the readout moving (`gluteus_maximus A +42.2%`,
`biceps_femoris A short head -17.9%`, `rectus_femoris A +22.7%`), with the refusal line unchanged.

Frame time, p95 ms per 1 s sample, squat rep cycle:

| State | Samples | Median | FPS range |
| --- | --- | --- | --- |
| tubes off, round 1 | 24.45, 24.37, 24.20, 25.72, 24.24, 24.20, 25.81, 25.28 | 24.41 | 44–50 |
| tubes on, round 1 | 26.59, 26.51, 28.33, 26.52, 26.59, 26.60, 26.71, 26.30 | 26.59 | 39–46 |
| tubes off, round 2 | median 24.61 (24.28–25.30) | 24.61 | 43–52 |
| tubes on, round 2 | median 26.85 (26.37–28.02) | 26.85 | 40–47 |

`logcat`, engine creation (the only Filament lines besides the frame-timestamp warning):

```
I Filament: FEngine (64 bits) created at 0x6f25e35ad0 (threading is enabled)
I Filament: FEngine resolved backend: OpenGL
I Filament: [Qualcomm], [Adreno (TM) 735], [OpenGL ES 3.2 V@0762.40 …], [OpenGL ES GLSL ES 3.20]
I Filament: Feature level: 2
W Filament: eglGetFrameTimestampsANDROID failed with error 12290. Disabling frame timestamps query.
```

Filament's precondition checks are compiled out of release builds, so an absent abort is not proof of
anything; the refusal happens before any GPU binding regardless.

Pixels: 1220 × 2712 screenshot, every second pixel sampled (827,160 samples). Strongly red samples
(`r > 110 ∧ r ≥ 1.9·g ∧ r ≥ 1.9·b`): **0** with the layer off and **0** with it on.

#### The two blockers

1. **`TANGENTS` is requested and not supplied.** §7.6 recorded the ubershader as declaring
   `POSITION, UV0, UV1, COLOR` with "`TANGENTS` not declared"; on this driver the instance reports it.
   The refusal is the designed behaviour (a missing attribute is a silent driver default, not a
   crash), but it means rows 2 and 3 cannot be answered until the layer supplies a tangent stream or
   takes a variant that does not ask for one.
2. **An exercise switch drops the character entirely.** Squat → Push-up leaves the host on
   `no character session` and does not recover while the chip stays on Push-up; switching back to
   Squat restores a session. `CharacterStage.kt:227` keys the attachment on the pose builder and
   `:229` acquires the single-owner slot inside that `remember`, while the old stage releases it in
   `DisposableEffect.onDispose` (`:110`) — the new composition acquires before the old effect is
   disposed, so the new attachment fails with `another view already owns the character`. Push-up was
   therefore never exercised; every push-up cell in this section is unmeasured, not passed.

#### What this run still cannot answer

- Alignment, orientation, winding in situ, tendon tint, and the GPU draw cost — all downstream of
  blocker 1.
- There is no `TAU_RESIDUAL_MM` (or any millimetre seam residual) in this repository; the layer's
  metres, bounds and winding are pinned by `TubeVertexLayoutTest` and `ProceduralTubeBudgetTest` on
  the JVM, and the device has no corresponding readout to compare against.
- The push-up half of every row, because of blocker 2.

Frame time and memory are the two rows a JVM harness cannot say anything about: the solve adds no
allocation of its own after construction — `AnatomySolveAllocationTest` measures the live
`AnatomySolver.solve` against an empty loop, while `SegmentFrames.derive` nominally builds five
scratch `Vector3`s (the JIT may scalar-replace them on the inlined path) — and the mesh writes into
pre-allocated arrays (`MuscleGeometryOwnershipTest`), but neither is a measurement of a phone. §7.7 now
carries the device p95 for the write path; PSS was not sampled, so memory is still unread.
