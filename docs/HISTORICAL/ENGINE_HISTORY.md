# ENGINE_HISTORY.md — Historical Investigation Archive

> **Historical archive.** This file is an *index* to the MonkEngine's investigation,
> audit, and fix-prompt records. It is **not** needed for day-to-day development
> and is **not** kept in sync with the live code. Read it only when you need the
> original reasoning behind a past decision, the full UNI-* issue narratives, or
> the per-pose defect audit.
>
> - The detailed original records live in **`docs/HISTORICAL/`** (see
>   `docs/HISTORICAL/README.md` for the inventory).
> - **Live future work** (what is left to do) lives in `docs/ENGINE_ROADMAP.md`.
> - **Current architecture** (what the MonkEngine runtime is now) lives in
>   `docs/ENGINE_ARCHITECTURE.md` and `docs/ENGINE.md`.
> - The detailed original records are retained as the source of record and are
>   linked below. Each is now banner-labeled **HISTORICAL**.

---

## 1. What this archive contains

During the "Phase 4 — Biomechanical subsystem audits" era (see
`docs/ENGINE_ARCHITECTURE.md` §8), the team ran a set of deep investigations of
weak engine areas. The output was four detailed documents:

| Original file | What it holds | Status |
| --- | --- | --- |
| `docs/HISTORICAL/ENGINE_INVESTIGATION_REPORT.md` | The **unified** issue register (UNI-1…UNI-12), per-validation-pose analysis, and the consolidated prioritized roadmap. | HISTORICAL |
| `docs/HISTORICAL/PELVIC_HIP_COMPLEX_INVESTIGATION.md` | 14-question pelvic/hip deep-dive (Q1–Q14) and the pelvis/hip-focused issue list (P1–P6). | HISTORICAL |
| `docs/HISTORICAL/ENGINEERING_VALIDATION_AUDIT.md` | Per-pose defect audit of the four Engineering Validation poses, with strict AUTHORING/ENGINE/CAMERA/VALIDATION/RENDERER attribution. | HISTORICAL |
| `docs/HISTORICAL/ENGINE_FIX_PR_PROMPTS.md` | Eleven implementation prompts (PR-01…PR-11), one per investigation issue. | HISTORICAL |

These are referenced from the architecture doc's documentation map
(`docs/ENGINE_ARCHITECTURE.md` §7) as the "evidence" tier. They are **evidence,
not source of truth** — when they contradict the current code, the code wins.

---

## 2. UNI issue register — consolidated status

Status strings are taken from the headers of `docs/HISTORICAL/ENGINE_INVESTIGATION_REPORT.md`
§4. **Caveat:** the same report's §6 summary claims several issues resolved by a
later merged PR ("improve pose accuracy and anatomical DOF support"). Where the
register and summary disagree, **verify against the current code before starting
work** — the live, de-duplicated list is `docs/ENGINE_ROADMAP.md`.

| Id | Issue (short) | Register status | Maps to (hip doc) |
| --- | --- | --- | --- |
| UNI-1 | Global solver is pelvis-only; no true posture solve | RESOLVED (damped CCD posture pass) | P4, A′ |
| UNI-2 | `straight=true` intent silently dropped; Middle Split impossible | RESOLVED (validator/ROM cluster) | P6, G |
| UNI-3 | ROM is mathematical, not biomechanical (no hip limits) | RESOLVED (`HipRomLimits` + `validateHipRom`) | P2, M |
| UNI-4 | ConstraintSolver pelvis-tilt uses wrong axis for lateral imbalance | RESOLVED (roll about X) | P1 |
| UNI-5 | Coordinate / axis-label drift between docs and code | RESOLVED (docs) | J |
| UNI-6 | Validator cannot verify authored-intent fidelity | RESOLVED (validator/ROM cluster) | K |
| UNI-7 | Clavicle is a dead node; no clavicular behaviour | RESOLVED (`buildClavicularRotation`) | H |
| UNI-8 | Wrist and ankle are single-DOF; no combined articulation | **OPEN** (register) / RESOLVED (§6 summary) | I |
| UNI-9 | Degenerate straight-limb bake before the global solver | **OPEN** (register) / RESOLVED (§6 summary) | L |
| UNI-10 | Hip motion expressed inconsistently; no hip-authoring helper | **OPEN** (register) / RESOLVED (§6 summary) | P3 |
| UNI-11 | Hip center / acetabulum modeled consistently | VERIFIED CORRECT | P5 |
| UNI-12 | Future-exercise supportability (summary, not a defect) | REFERENCE | — |

---

## 3. Per-validation-pose verdicts (from the unified report §2)

- **Dead Hang** — REPRODUCIBLE (clean). No limitation exposed.
- **Deep Overhead Squat** — REPRODUCIBLE, with a ~1–3u pelvis shift caused by the
  shared `30°` flexion floor (UNI-3 artifact, not a breakage).
- **Pike Sit** — REPRODUCIBLE (clean).
- **Middle Split** — CANNOT be reproduced with straight limbs: `straight=true` +
  grounded pelvis + feet/arms at ≈3× hip/shoulder-width is geometrically impossible
  with conserved bone length. the MonkEngine runtime correctly bends the limbs and keeps the
  pelvis grounded; no rule flags the dropped straight intent (UNI-2). Per
  `VALIDATION.md §9` the *reference* is anatomically wrong (a real straight-leg
  split puts feet at ≈±230, not ±79.2). Front Split is naturally supportable.

Full per-pose numeric detail: `docs/HISTORICAL/ENGINE_INVESTIGATION_REPORT.md` §2 and
`docs/HISTORICAL/ENGINEERING_VALIDATION_AUDIT.md` §1–§4.

---

## 4. Pelvic / Hip Complex — Q&A verdicts (from `PELVIC_HIP_COMPLEX_INVESTIGATION.md`)

- Pelvis is a rigid root node + the pivot the solver moves (correct).
- Hip is a **real 3-DOF ball-and-socket** at the acetabulum (the "no real hip
  model" suspicion is false); caveats: no independent hip ROM limit (UNI-3) and
  inconsistent authoring (UNI-10).
- `LUMBAR` is a real independent segment (Issue E) but the solver never uses it as
  a free DOF (UNI-1).
- Hip center is modeled consistently across `HumanSkeletonDefinition`,
  `SkeletonFactory`, and `ConstraintSolver` — **VERIFIED CORRECT (UNI-11)**.
- Lateral **roll** (about X) auto-tilt is missing; the solver adds a pitch about Z
  instead (UNI-4).
- ROM is **mostly mathematical** (shared `30°` knee-flexion floor, no hip-specific
  anatomical ROM; hip angle never validated) — UNI-3.
- Future exercises (Front Split, Lunge, Cossack, Bulgarian, Pistol, Single-leg RDL,
  Horse Stance, most yoga/martial-arts stances) are **naturally supportable
  without hacks**, with the caveats noted under UNI-3/UNI-4.

Full 14-question analysis: `docs/HISTORICAL/PELVIC_HIP_COMPLEX_INVESTIGATION.md` Q1–Q14.

---

## 5. Engineering Validation audit — cross-cutting findings

From `docs/HISTORICAL/ENGINEERING_VALIDATION_AUDIT.md` §5:

- **RENDERER** — no defects.
- **CAMERA** — no defects (the only camera change was a +20° pitch framing pass for
  the four test poses; see `docs/VALIDATION.md` §11.6). Camera *ownership* is
  documented in `docs/ENGINE.md` §10.
- **VALIDATION** — no validator defects; it is the intended detector.
- **ENGINE** — no engine bugs; UNI-9 and the IK clamp behave as designed.

Full per-defect attribution table: `docs/HISTORICAL/ENGINEERING_VALIDATION_AUDIT.md` §6.

---

## 6. Fix-prompt mapping (from `ENGINE_FIX_PR_PROMPTS.md`)

| PR | Target issue | Topic |
| --- | --- | --- |
| PR-01 | frame-relative limb baking | remove hand-fed inverse-Z scalar |
| PR-02 | straight / rigid-segment limb mode | first-class straight limb |
| PR-03 | IK clamp contact/ground aware | no penetration |
| PR-04 | global contact-constraint / root-repositioning | posture layer |
| PR-05 | shoulder girdle DOF | clavicle + scapula (UNI-7) |
| PR-06 | wrist as real joint | hand rotation (UNI-8) |
| PR-07 | ankle as real joint | ankle rotation (UNI-8) |
| PR-08 | angular joint limits | beyond distance clamps |
| PR-09 | full 3-DOF trunk frame | twist + side-bend (UNI-10) |
| PR-10 | IK reachability detection | propagate clamp automatically |
| PR-11 | true straight limb | opt-in extension ratio 1.0 (UNI-9) |

Suggested sequencing and full prompts: `docs/HISTORICAL/ENGINE_FIX_PR_PROMPTS.md`.

> Many of these prompts are now obsolete because their target issues were resolved
> (see §2). The **live** subset is tracked in `docs/ENGINE_ROADMAP.md`.
