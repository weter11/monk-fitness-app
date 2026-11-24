# Anatomy review record v1 — Stage 3 (path-rigged authored muscle meshes)

The §12.4 record for the muscle asset Stage 3 ships. It states what was **measured**, on which exact
artefacts, and what was **not** judged — a limitation is not a failure, hiding one is (§12.4).

## 1. The tuple this record covers

A record is valid only for the tuple it names (§12.4, *Renewal*). Any change to one member re-opens the
criteria that depend on it.

| Member | Value |
| --- | --- |
| `anatomyVersion` | `anatomy_v1.json` — `sha256 c9689be3d456ffb05a760c192bcb47b4e4fa5eb0d213cd10359a5a7107cc8108` |
| bind manifest | `helpers_v1.json` — `sha256 e2eab6745c3250a9384894410b1283cc9b850611b306879f80b475069627d378` |
| `rigVersion` | `humanoid_v2.glb` — `sha256 a4b2c226b6de4c98e0942fd0f3b7086bf091c187228695edc6c4a34696ff3d8b` |
| `assetVersion` | `muscles_sup.glb` — `sha256 4a43567b6fd841181dd2284d0e0787fe3e794b73ac906810e936a5d9d8fe83c0` |
| `presetId` | `NEUTRAL` (the only preset M1 ships) |
| muscles | the 8 superficial muscles of `anatomy_v1.json`: rectus abdominis, external oblique, erector spinae, latissimus dorsi, gluteus maximus, gluteus medius, biceps femoris (2 heads), rectus femoris |
| sides | `A` and `P`, all 16 sections |
| poses | the five-frame M1 set of §12.4 RV4: `squat_standard` bottom and top, `pushup_standard` bottom, `birddog_hold`, `row_standard` mid-pull |
| layer configuration | authored muscle layer only, `showsMeshes`, per-section visibility and selection (§7.4) |
| stance the geometry is authored in | the X1 bind frame (§6.6) |

## 2. Reviewer and sign-off

**Reviewer of record: the project owner**, under the §15 convention for every gate in the roadmap until a
qualified reviewer is named. `test authorship is not review` (§12.4): nothing in this file is an
anatomist's judgement, and the owner is not presented as one.

**A qualified anatomist or physiotherapist has not signed this record.** That is limitation **L-1**, and it
is the reason RV4 below reads *not reviewed* rather than *pass*.

## 3. What the programmatic baseline is, and what it is not

Three of §12.4's six criteria have a mechanical half, and Stage 3 ships that half. Each cell in §4 cites
the measurement that produced it; every one is reproduced by

```bash
export JAVA_HOME=<jdk-21>  ANDROID_HOME=<android-sdk-37>
./gradlew :app:testDebugUnitTest --tests "com.monkfitness.app.anatomy.HelperBindTest"
```

The measured L5 block this record reads from, on the tuple above:

```
L5 degenerate triangles: none of 3744; smallest area 4.891638102522198E-7 m2 at gluteus_maximus_A triangle 38
L5 section volumes (m3): rectus_abdominis_A 2.960e-04 … smallest 5.879e-05 at gluteus_medius_P
L5 weight sum: worst residual 2.98e-08 at rectus_abdominis_A vertex 8
L5 self-intersection: 0 across sections; 32 inside a head on 1 section(s), all at a hairpin;
   444 between the heads of one muscle
L5 between-helper surface deviation = 0.020516 m (20.52 mm), budget 24.00 mm,
   at biceps_femoris_A head 0, stations 0.7..1.0, ring 11
worst helper bind residual in muscles_sup.glb = 1.25e-07 m
helper y bounds = 0.457..1.415 m, inside the rig's envelope
```

**The baseline is geometry, not anatomy.** It says the sections are closed, correctly wound, non-degenerate,
inside the body's envelope and disjoint from each other, and that the surface tracks the solved path to a
stated bound. It cannot say the belly is in the right compartment, that the shape reads as that muscle, or
that the attachment is the right point on the right bone.

## 4. The Stage 3 review matrix

`RV2` — the section sits in the body's compartment and inside its envelope. `RV3` — the section crosses no
other muscle's surface, in the stance it is authored in. `RV4` — the shape and path read as that muscle in
the five-pose set.

Cell values are the measured reading; `n/m` means *not measured on this asset*.

| Section | Volume (m³) | y span (m) | Inside rig envelope | RV2 | RV3 | RV4 |
| --- | --- | --- | --- | --- | --- | --- |
| rectus_abdominis_A | 2.960e-04 | 0.895–1.290 | yes | path-derived, envelope pass | 0 crossings of any other section | not read visually |
| rectus_abdominis_P | 2.960e-04 | 0.895–1.290 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| external_oblique_A | 2.129e-04 | 0.920–1.305 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| external_oblique_P | 2.129e-04 | 0.920–1.305 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| erector_spinae_A | 1.689e-04 | 0.920–1.315 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| erector_spinae_P | 1.689e-04 | 0.920–1.315 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| latissimus_dorsi_A | 1.591e-04 | 1.330–1.420 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| latissimus_dorsi_P | 1.591e-04 | 1.330–1.420 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| gluteus_maximus_A | 6.563e-05 | 0.871–0.925 | yes | path-derived, envelope pass | 0 across sections; **32 folds inside the head**, all inside the `0.0..0.3` hairpin (L-3) | not read visually |
| gluteus_maximus_P | 9.659e-05 | 0.870–0.930 | yes | path-derived, envelope pass | 0 across sections; 0 folds | not read visually |
| gluteus_medius_A | 5.879e-05 | 0.913–0.967 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| gluteus_medius_P | 5.879e-05 | 0.913–0.967 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| biceps_femoris_A | 8.583e-04 | 0.456–0.883 | yes | path-derived, envelope pass | 0 across sections; 0 folds; **197 crossings between its own two heads** (L-4) | not read visually |
| biceps_femoris_P | 8.358e-04 | 0.456–0.883 | yes | path-derived, envelope pass | 0 across sections; 0 folds; **247 crossings between its own two heads** (L-4) | not read visually |
| rectus_femoris_A | 5.726e-04 | 0.467–0.868 | yes | path-derived, envelope pass | 0 crossings | not read visually |
| rectus_femoris_P | 5.485e-04 | 0.467–0.868 | yes | path-derived, envelope pass | 0 crossings | not read visually |

**RV2 in full.** The criterion asks whether the belly sits in the correct compartment: not inside bone, not
outside skin, not on the wrong side of a joint. What is measured is narrower: every section's axis-aligned
bounds lie inside the rig's skinned-body bounds (`x ±0.244`, `y 0.055–1.653`, `z ±0.143` m), and every
helper bind sits inside that envelope within 1e-3 m. Bone clearance, skin clearance and compartment
correctness are **not measured** (L-2).

**RV3 in full.** Zero triangle crossings between any two of the 16 sections, by a plane-interval
triangle test over all 16·15/2 section pairs — no muscle occupies space another one occupies. The
within-section cases are declared, not hidden: a swept tube may fold only inside a station interval whose
path turns through a right angle, and two heads of one muscle may merge, because they share a tendon. Both
conditions are read from the manifest's own geometry rather than from a list of names. **Skin intersection
is not measured** (L-2), which is the half §12.5 excludes from M1 for exactly this reason.

**RV4 in full.** Not performed. The stand-in is a swept ring of 8 vertices around the solved path with a
declared radius taper: its silhouette is a cylinder by construction, so a visual read of it would report the
generator's parameters, not the anatomy. The five-pose set is recorded above so the sculptural pass reviews
the same frames, and §12.4 RV4's coverage argument is unchanged: `row_standard` mid-pull is the only frame
that carries `latissimus_dorsi` through a pull, and a review built from squat and push-up alone would leave
one of the eight unreviewed.

## 5. Unresolved limitations

| # | Limitation | Why it is not a pass | What closes it |
| --- | --- | --- | --- |
| L-1 | No qualified anatomist has reviewed the asset | RV4, and the anatomical half of RV2 and RV3, are human judgements (§12.1) | a named reviewer signs this file; the owner convention of §15 covers the interval, not the judgement |
| L-2 | No bone, skin or muscle-vs-skin intersection is measured | the tool §12.5 says M1 does not ship is still not shipped; "inside the envelope" is bounds, not surfaces | a mesh-intersection tool, then RV2/RV3 on surfaces |
| L-3 | `gluteus_maximus_A` folds through itself 32 times | the fold is inside the `0.0..0.3` hairpin the path declares (125.1° between stations), so it is the bend of the solved path, not a modelling error — but a folded surface is still folded | the authored mesh, or a path whose gluteal interval does not double back |
| L-4 | The two heads of `biceps_femoris` interpenetrate (197 A / 247 P) | the stand-in gives each belly the muscle's full radius around a path that runs deep to its neighbour, so the cylinders overlap near the short head's origin | the sculptural pass, where one belly is authored deep to the other |
| L-5 | The two sides are not mirror-equal | `gluteus_maximus` A is 6.56e-05 m³ against P at 9.66e-05 m³ (−32 %), `rectus_femoris` −4 %, `biceps_femoris` −2.6 %; an exact mirror would give equal volumes | either a mirrored export or a statement that the asymmetry is intentional — neither exists today |
| L-6 | Path validity is shown on the generic reference body only | §12.4's own entry (i): one `s`, one bind pose, no other body | stage 5's body families re-open it |
| L-7 | Sex-related pelvis-width and femoral-angle differences are not covered | §12.4's entry (ii): the set is authored and reviewed against one body | a reviewed per-family override (D10) |
| L-8 | Arm elevation above shoulder height is not covered | §12.4's entry (iii): no M1 pilot exercise raises the hands above the shoulders | a residual at a named overhead frame before the first such exercise ships |
| L-9 | The between-helper bound is a budget, not a fitted tolerance | 20.52 mm against a 24.00 mm budget derived from the widest authored radius; the reading is dominated by `biceps_femoris`' insertion span | an authored asset whose surface is authored between stations, not swept from them |

## 6. Programmatic baseline versus the sculptural art pass

Stage 3 ships the **pipeline** — helper binds from the solver's rest path, one skinned section per muscle
and side, per-section visibility and selection — and a deterministic stand-in surface that exercises it.
This record is the baseline for that stand-in.

It is **not** the review of the authored muscle meshes. By §12.4's renewal table, replacing the stand-in
with sculptural geometry re-opens **RV2, RV3 and RV4 on the new assets, per muscle, both sides**, and
requires a new record naming the new `assetVersion`. Nothing here transfers: a reviewed silhouette for a
cylinder is not a reviewed silhouette for a belly, and a passing L5 on `4a43567b…` says nothing about the
geometry that replaces it.

## 7. What this record does not do

It does not convert to a score, it does not make L7 automatable, and it does not retroactively validate
poses, presets or meshes it did not cover (§12.4). RV1 (attachments, authoring frame and sign), RV5
(preset consistency) and RV6 (provenance) are **not decided here**; this file records the three criteria
Stage 3 changes.
