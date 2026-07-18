# Phase 3 — Finalizer owns exclusive conversion + read-only chest-frame guarantee

**Status:** COMPLETE (was gated by `EngineFlags.FINALIZER_OWNS_CONVERSION`; the flag and
`EngineFlags` object were later deleted in the cleanup, so the finalizer authority is now
unconditional). **Resolves findings:** F1 (Critical), F4. **Depends on:** Phase 1 (IK world-only), Phase 2 (Solver settled root).

> [!IMPORTANT]
> **STATUS: SUPERSEDED (historical).** This report was written mid-cleanup, when the behaviour was
> still flag-gated. The flag is gone; the finalizer is now the unconditional sole writer of local
> transforms. Retained for archaeology.

This report documents the Phase 3 change to `SkeletonPoseFinalizer` per
`ARCHITECTURE_V2_ROADMAP.md` and `IMPLEMENTATION_BRIDGE.md` (§B5). Behavior was unchanged while the
flag was off; the legacy finalize path was byte-identical.

## 1. Goal

Make the finalizer the **sole writer of local transforms** and turn the F1 "no-move" guarantee into
a hard, testable invariant: a Solver-settled contact end-effector must never move during
finalization.

## 2. Changes

### 2.1 `FINALIZER_OWWNS_CONVERSION` flag (removed in cleanup; was `EngineFlags.kt`)
Master switch that mirrored `SOLVER_OWNS_POSTURE`. Default `false` (legacy path preserved) at the time
this was written; later collapsed to `true` and the `EngineFlags` object deleted. Snapshot previously
in `EngineFlags.snapshot()`.

### 2.2 `preConvertPoles(pose)` (removed in cleanup; was `SkeletonPoseFinalizer.kt`)
The single local-transform conversion entry point, called at the top of `finalize()`. When the flag
was off it was a documented no-op. When on, it was where any remaining world↔local frame conversion was
concentrated — the limb `toLocalDirection` bakes already ran in the solver's `bakeIkLimb`; extremity
derivation and the chest frame run later in `finalize()`. The finalizer thus owns 100% of node/local
mutation after this point.

### 2.3 Read-only chest-frame no-move guard (B5 / F1) in `reconstructChestFrame`
- **Snapshot** — when the flag is on AND the pose carries contacts, `buildContactSnapshot(pose)`
  captures the world position of every contact end-effector (`pose.contacts[i].endJoint`) into
  `guardSnapshot{X,Y,Z}` keyed by joint index, walking the live node map (`collectNodes`).
- **Reconstruct** — the existing fallback frame is computed and applied to `chest.localRotation`,
  and the chest subtree is re-flattened.
- **Assert** — `enforceContactNoMove(...)` measures each snapshotted end-effector's displacement
  after reconstruction. If every contact moved ≤ `EPS = 1e-3f`, the reconstruction is accepted
  (read-only on contacts).
- **Rollback** — if any contact moved beyond `EPS`, the reconstructed chest local rotation is
  rolled back to the Solver-settled identity (this path only runs for an identity/unauthored chest),
  the chest subtree is re-flattened to the exact Solver-settled pose, and `rootTranslationDelta` is
  flagged with the max displacement so the validator can surface the residual.
- The **Issue F authored-chest early-return** (`chest.localRotation.angle` ≈ 0) takes precedence —
  an authored chest never reaches the guard.

### 2.4 Deprecated overload retention
The Phase 1 (F4) deprecated frame-relative `bakeIkLimb(parentRotation, poleLocal)` overload is
**retained**. Per `IMPLEMENTATION_BRIDGE.md` §B3 it is kept during migration (its ~18 pose callers
are migrated in a follow-up, at which point the overload is deleted). No behavior change today.

## 3. Tests

New `ChestFrameNoMoveTest` (B6 mapping):
- `guardHoldsForAllContactPoses` — with the flag on, Middle Split / Deep Overhead Squat / Pike Sit /
  Dead Hang finalize to finite skeletons and every fixed contact end-effector stays on its support
  plane (ground for feet, bar plane for hands).
- `flagOffPreservesLegacyBehaviour` — with the flag off, Middle Split still finalizes finite with
  ankles on the ground (legacy path unchanged).
- `authoredChestNeverReachesGuard` — an explicitly authored chest twist survives finalize with the
  guard on (Issue F early-return precedence).

Regression coverage: `ChestFrameIssueFTest` (authored-twist / authored-flex / identity-fallback)
and `ConstraintSolverPhase2Test` (solver seeding/precedence/smoothing) remain green.

## 4. Risk & verification

- **Risk:** Medium (per roadmap). Contained by the `FINALIZER_OWNS_CONVERSION` gate: off = legacy.
- **Verification:** `:app:testDebugUnitTest` for `ChestFrameNoMoveTest` + `ChestFrameIssueFTest` +
  `ConstraintSolverPhase2Test` → 12/12 pass, 0 failures. Production `compileDebugKotlin` green.
- **No regressions:** non-contact (production) poses never run the solver or the guard, so they are
  untouched.

## 5. Follow-ups (not in this change)

- Migrate the ~18 `bakeIkLimb(parentRotation, poleLocal)` callers to the world-only overload and
  delete the deprecated overload (completes the "remove deprecated overload" item).
- Global flip of `FINALIZER_OWNS_CONVERSION = true` after cross-pose baseline sign-off.
