# Architecture Freeze

**Status:** FROZEN — effective immediately.
**Source of truth:** `docs/ARCHITECTURE_V2.md` (the definitive implementation specification produced from the W1 audit, the Responsibility Architecture, the Senior Review, and the Final Defect Review).

This document establishes that the animation-engine responsibility architecture is **no longer subject to change**. All future implementation, review, and audit MUST conform to Architecture v2. No new architectural ideas, no re-litigation of ownership, no alternative designs.

---

## 1. Why this is frozen

The architecture was derived through four sequential, adversarial reviews:

1. **W1 audit** — established that poses were doing the MonkEngine's job (extremity orientation).
2. **Responsibility Architecture** — defined the ideal owner for every concern from first principles.
3. **Senior Review** — challenged every assignment; found genuine inconsistencies (F1–F11).
4. **Final Defect Review** — classified findings: 1 Critical (F1), 4 Medium (F2/F3/F4/F10), 5 Minor (F5/F6/F7/F9 + rejected F8), 1 rejected as preference.

All accepted findings (F1–F10) were resolved in `ARCHITECTURE_V2.md` without introducing new ideas. The result is internally consistent and executable. Further change would only reintroduce the leaks the audits removed.

---

## 2. Invariants that may NEVER be violated

These are constitutional. Any code that breaks them is a defect, regardless of intent:

1. **Pose = intent.** A pose declares anatomy, contacts, motion. It never computes geometry.
2. **Engine = geometry.** IK, ConstraintSolver, Finalizer, FK derive every local transform.
3. **Validation = observer.** Validation reads stamped state; it never influences geometry.
4. **`SkeletonPose` is the sole shared mutable state.** All cross-component handoff flows through it.
5. **Solver is the only mover of the root/pelvis transform.**
6. **Finalizer never moves a Solver-settled contact end-effector.**
7. **IK is world-only; Finalizer is the only writer of local transforms.**
8. **Validation never writes `SkeletonPose`.**

---

## 3. Frozen ownership (summary — full detail in ARCHITECTURE_V2.md)

| Concern | Owner |
|---|---|
| Skeleton/proportion selection | Pose |
| Motion drivers (choreography) | Pose |
| Contacts + precedence | Pose (declare) / Solver (resolve) |
| PostureIntent (typed) | Pose (declare) / Solver (interpret) |
| Camera/environment | Pose |
| Spine intent (single call) | Pose |
| Chest/hip/girdle/ankle/wrist intent | Pose (relative) |
| Gaze = head target | Pose (target) |
| Limb endpoint targets | Pose (target) |
| IK 2-bone solve | IK (world-only) |
| Straight-limb fallback | IK |
| Bone-length invariant | IK |
| Default pole | IK |
| Root exact transform | Solver |
| Contact honor (translate/tilt) | Solver |
| Posture CCD | Solver |
| Inter-frame smoothing | Solver |
| Clamp / straight stamps (secondary) | Solver |
| World↔local conversion | Finalizer (exclusive) |
| Extremity derivation | Finalizer |
| Relative tilt cancel | Finalizer |
| Chest-frame reconstruction | Finalizer (read-only on settled) |
| Flatten to nodes | Finalizer |
| FK propagation | FK (stateless) |
| Rule checks | Validation (read stamps) |

---

## 4. What is NOT allowed

- No proposal to move a concern to a different owner than listed above.
- No new shared mutable state outside `SkeletonPose`.
- No re-opening of F1–F10. They are resolved; the resolution is final.
- No "better" alternative design. Preference-based objections are out of scope.

---

## 5. Amendment procedure (intentionally restrictive)

The freeze may only be lifted by a **new, complete audit cycle** that:
- demonstrates a concrete, implementable defect in Architecture v2 (not a preference),
- reproduces the four-review structure (audit → architecture → senior review → defect review),
- produces a new frozen revision that supersedes this document.

Until then, Architecture v2 is the law.

---

## 6. Companion documents (also frozen with this architecture)

- `docs/ARCHITECTURE_V2.md` — definitive implementation specification (phases, ownership tables, contracts, pipeline).
- `docs/API_CONTRACTS.md` — per-component inputs/outputs/read-write/prohibited.
- `docs/MIGRATION_RULES.md` — prohibited patterns + mandatory rules for new poses.
- `docs/ARCHITECTURE_V2_ROADMAP.md` — 9-phase implementation roadmap (Phases 0–8).

All four are version-locked to this freeze.
