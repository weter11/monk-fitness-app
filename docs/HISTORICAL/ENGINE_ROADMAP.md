# ENGINE_ROADMAP.md — Live Future Work

> **Live roadmap.** This is the only place to look for *what is left to do* on the
> engine. It is derived from the historical investigation archive
> (`docs/ENGINE_HISTORY.md`) and is kept intentionally small.
>
> Historical reasoning, per-pose audits, and the full UNI-* narratives live in
> `docs/ENGINE_HISTORY.md`. The current architecture lives in
> `docs/ENGINE_ARCHITECTURE.md` and `docs/ENGINE.md`.

---

## 1. How to read this

the MonkEngine runtime went through a four-phase evolution (see `docs/ENGINE_ARCHITECTURE.md`
§8). The big architectural gaps found during the investigation era have largely
been closed (ConstraintSolver posture pass, hip ROM, validator authored-intent
rules, clavicle/scapula DOF, two-segment spine). This document tracks only the
**remaining** work an engineer should pick up.

**Status caveat.** The historical issue register (`docs/ENGINE_HISTORY.md` §2) was
written *before* a later merged PR ("improve pose accuracy and anatomical DOF
support") that the source report's own summary claims resolved UNI-8, UNI-9, and
UNI-10. **Verify each item against the current code before starting** — do not
trust the register header alone. An item below is listed as OPEN only if it was
still OPEN in the register *and* has not been clearly superseded.

---

## 2. Open / remaining items

| Id | Item | Layer | Notes |
| --- | --- | --- | --- |
| UNI-9 | True straight-limb reach when the target sits inside the proximal-bone length (`dist < L1`). The degenerate bent-limb fallback is correct behaviour, but a `straight=true` limb that cannot be honored should be **surfaced**, not silently bent. | Engine + Validation | The Middle Split validation pose deliberately keeps this as a regression reference (`docs/VALIDATION.md` §11.1). Fix the MonkEngine runtime, not the pose. |
| UNI-12 | Future-exercise supportability. Confirm the natural-supportability claim for Front Split, Cossack, Bulgarian, Pistol, Single-leg RDL, Horse Stance, etc. (per `docs/ENGINE_HISTORY.md` §4) by exercising them through the pipeline. | Pose / Validation | Not a defect; a coverage/confirmation task. |
| Trunk DOFs in solver | `ConstraintSolver` distributes residual across *limb* free angles only; `CHEST`/`LUMBAR` are carried rigidly for limb contacts. Making the trunk itself a free DOF for trunk-contact poses is explicitly future work (UNI-1 fix notes). | Engine | Lower priority; only matters for poses where the trunk is a contact. |
| Generalized contacts | Contacts today map to 2-bone (or degenerate 1-bone) chains. A principled way to express more complex support topologies (e.g. seated hip + planted hands) would broaden native multi-contact reconciliation. | Engine | Architectural; see `docs/ENGINE_ARCHITECTURE.md` §R7. |
| Deeper motion continuity | Centralize easing into a first-class, shared motion model rather than per-pose curve choices. | Pose | See `docs/ENGINE_ARCHITECTURE.md` §R7. |

---

## 3. Items resolved (do not re-open unless regressed)

These were closed during or after the investigation era. Listed so an agent does
not re-litigate them:

- **UNI-1** — pelvis-only solver → damped CCD posture pass (`solvePosture`,
  `regularizeTowardAuthored`).
- **UNI-2 / UNI-6** — straight-intent + authored-intent fidelity →
  `STRAIGHT_LIMB_INTENT`, `CONTACT_PRESERVED`, `PELVIS_INTENT` under
  `ValidatorConfig.ENGINEERING_VALIDATION`.
- **UNI-3** — mathematical-only ROM → `HipRomLimits` + `validateHipRom`
  (per-plane, engineering-config only).
- **UNI-4** — solver tilt on wrong axis → lateral roll about X.
- **UNI-5** — coordinate/axis-label drift → `docs/ENGINE.md` §4 corrected.
- **UNI-7** — clavicle dead node → `buildClavicularRotation`.
- **UNI-11** — hip center consistency → verified correct.
- **Issue E** — two-segment spine (`PELVIS → LUMBAR → CHEST`) shipped.
- **Issue F** — chest-frame reconstruction no longer overwrites authored rotation.

> If a merged PR also closed UNI-8 (wrist/ankle 2-DOF) and UNI-10 (hip authoring
> helper), they belong in this resolved list too — confirm in code.

---

## 4. Suggested pickup order

1. **UNI-9** — highest leverage: the only pose that fails and is invisible to
   validation; small, contained change (intent plumbing + a validator rule).
2. **UNI-12** — cheap confirmation task that de-risks the "naturally supportable"
   claim for a whole class of future exercises.
3. **Trunk DOFs in solver** / **Generalized contacts** — only if a concrete
   exercise family needs them; otherwise defer.

---

## 5. Rules when working from this roadmap

- Treat the four-layer boundary (`docs/CODING_RULES.md`) as fixed: math →
  `SkeletonMath`, topology → `SkeletonFactory`, authoring → `BasePose`,
  correctness → `ExerciseValidator`.
- the MonkEngine runtime satisfies validation; never soften a validation pose to pass
  (`docs/VALIDATION.md` §2, §9).
- Prefer an engine fix that benefits the whole family over a per-pose hack
  (`docs/CODING_RULES.md` §2–3).
- Before claiming an item resolved, run `./gradlew :app:testDebugUnitTest` and
  confirm no regression against `docs/TEST_BASELINE.md`.
