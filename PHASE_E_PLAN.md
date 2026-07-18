# Phase E — Compatibility bridge removal (L1): Implementation Plan

**RFC:** `RFC_ENGINE_CLEANUP_PLAN.md` §Phase E + `RFC_LEGACY_ENGINE_RETIREMENT.md` §324.
**Gate target:** delete the legacy `else` branch in `SkeletonPoseFinalizer.finalize` and its
supporting second hierarchy; make `pose.roots` non-empty an invariant.

---

## Pre-flight verification (done during audit)

- **B (flag collapse):** DONE. Only `IK_STAGE_ACTIVE` retained; the four retired flags are gone.
- **C (buildHead migration):** DONE. `buildHead` fallback removed; instruments use `headTarget`.
- **D (direct-finalize re-pointing):** DONE per `AGENTS.md` Phase D audit — 6 control files
  intentionally still call `finalizer.finalize(...)` directly. They remain valid because they
  supply non-empty `roots` (via `fromHierarchy` / built poses).
- **E1 (no empty-`roots` producer):** VERIFIED SAFE.
  - `BaseThoracicPose.finalizeThoracicPose()` (`:160`) copies a `jointsBuffer` already populated
    by `SkeletonPose.fromHierarchy(roots!!, jointsBuffer)` (`:152`), so `out.roots` is set.
  - `BaseValidationPose.jointsBuffer` (`:54`) is a scratch; `build()` re-populates it via
    `fromHierarchy` before any finalize (`:74`-style path).
  - Production `build()` entry points all set `roots = nodes.roots`.
  - Test-only empty `SkeletonPose()` ctors (`IntentBuilderSubstrateTest`, `SupportMathTest`,
    `ValidatorRomClusterTest`, `ExerciseValidatorTest`, `ExerciseReviewTest`, etc.) are
    carrier/validator-only and never reach `finalize`, so they do not block E.

---

## Execution steps

### E1 — Confirm non-empty-roots invariant (no code change needed, but add defensive assertion)
- In `SkeletonPoseFinalizer.finalize` (line 493), replace the branch test with an unconditional
  body plus `check(pose.roots.isNotEmpty()) { "finalize requires a populated pose.roots" }`.
- Do NOT yet delete the `else`; E2 handles that. This step just flips the invariant.

### E2 — Delete the legacy `else` branch
- File `SkeletonPoseFinalizer.kt:561-585` (`else { … ensureHierarchy(); setupTransforms();
  roots!!.forEach …; outputPose.roots = roots!! }`). Delete entirely.

### E3 — Delete supporting legacy hierarchy
- Remove `private var roots: List<SkeletonNode>? = null` (`:309`).
- Remove `private val nodesMap = Array<SkeletonNode?>(Joint.entries.size) { null }` (`:310`).
- Remove `private fun ensureHierarchy()` (`:330-338`).
- Remove `private fun setupTransforms(node, parentWorldRot, pose)` (`:385-471`).
- Keep `private val outputPose = SkeletonPose()` (`:17`) — it is the output carrier, not bridge.
- **Confirm** `nodesMap`/`roots` are referenced only inside those four members (grep before
  delete); no other reader exists.

### E4 — Make the modern path unconditional
- Collapse `if (pose.roots.isNotEmpty()) { … }` (`:493-560`) to a bare block guarded by the
  `check` from E1.
- Simplify now-unreachable early guards inside the modern path helpers that test
  `pose.roots.isEmpty()`: `resolveHeadTarget` (`:184` `if (pose.roots.isEmpty()) return`) and
  `reconstructChestFrame` (`:256` `if (pose.roots.isEmpty()) return`) — drop the guard (or leave
  as no-op; prefer clean removal).

### E5 — Docstring cleanup
- `SkeletonPoseFinalizer.kt:10-12, 476`: drop "compatibility layer" / "legacy position-driven"
  wording; state it is the sole finalizer over a populated `roots` hierarchy.
- `SkeletonPipeline.kt:73, 107`: remove residual "legacy bypass" phrasing (superseded by B/M2).
- `SkeletonPoseFinalizer.kt:562-565` (now deleted) comments gone with E2.

---

## Verification gate E

1. **Grep** `ensureHierarchy|setupTransforms|nodesMap` → zero references in `main`/`test`.
2. **Grep** `roots!!` in `SkeletonPoseFinalizer.kt` → zero.
3. `finalize` contains no `else`; asserts non-empty `roots`.
4. `./gradlew :app:testDebugUnitTest` → green and byte-identical (control files still pass; the
   6 direct-`finalize` tests must continue to pass because all their inputs have populated roots).

---

## Risks / rollback

- If any hidden caller finalizes an empty-`roots` pose, the E1 `check` fails fast at runtime
  (not silent corruption). Mitigation: pre-flight grep of all `finalize(`/production `build()`
  paths already confirmed populated roots — see Pre-flight.
- Deleting `nodesMap`/`roots` is safe only if grep confirms no external reader. Verify immediately
  before E3 delete; if a reader surfaces, stop and re-audit.

## Out of scope (later phases)

- Phase F (delete `EngineFlags` object) — separate; depends only on B.
- Phase G (ExerciseReview removal) — orthogonal.
