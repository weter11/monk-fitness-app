# Changelog — RFC v2 Consistency Corrections

**Purpose:** Resolve the eight consistency issues raised against the Architecture v2 RFC set
(`RFC_ENGINE_PIPELINE`, `RFC_INTENT_LAYER`, `RFC_EXECUTION_CONTRACT`, `RFC_GAP_CLOSURE`) and the
`CAPABILITY_GAP_REPORT`. No architecture was redesigned; only internal/external contradictions were
removed and explicit ownership/dependencies/gates were added.

---

## Issue 1 — M1 contradiction (scope mismatch between two RFCs)
- **Before:** `RFC_ENGINE_PIPELINE` M1 included "introduce `declareLimbTarget` + migrate poses one limb
  at a time"; `RFC_GAP_CLOSURE` M1 only removed the deprecated `bakeIkLimb` overload. The two described
  different scopes for the same milestone.
- **Resolution:** Chose the **narrower** M1 (overload removal ONLY). `declareLimbTarget` is moved to M2
  (Pose intent-only), where the `IntentBuilder` + pipeline consumption of §1.1 exist. Both RFCs now
  state the identical M1 scope and the same rationale (M1 = pure mechanical refactor, zero new API,
  trivially revertible; `declareLimbTarget` would be a dangling API in M1).
- **Files:** `RFC_ENGINE_PIPELINE.md` §6 M1; `RFC_GAP_CLOSURE.md` §3 M1.

## Issue 2 — `bakeIkLimb` caller-count contradiction
- **Before:** `RFC_GAP_CLOSURE` promised an "exact caller count" in one place but said "the count still
  needs verification" in another; cited the roadmap's "~18".
- **Resolution:** **Verified the real count = exactly 2** call sites (`BaseLungePose.kt`,
  `BaseThoracicPose.kt`) via grep for the `parentRotation`/`poleLocal` argument shape (the 64 total
  `bakeIkLimb(` sites include the world overload + other variants). Removed the open-question promise
  ("Exact caller count … verify against the 64 total") from §12. All "~18" assertion-level wording
  replaced with "exactly 2 (VERIFIED)" in `RFC_GAP_CLOSURE`, `RFC_EXECUTION_CONTRACT` (M1 step), and
  `CAPABILITY_GAP_REPORT` (Gap 5). The roadmap's "~18" is now explicitly noted as *incorrect* (not
  asserted) in the deliverables.
- **Files:** `RFC_GAP_CLOSURE.md` §0, §3 M1, §12; `RFC_EXECUTION_CONTRACT.md` §14 M1;
  `CAPABILITY_GAP_REPORT.md` Gap 5.

## Issue 3 — Dependency graph (M6 is not independent)
- **Before:** `RFC_GAP_CLOSURE` stated "Gap 6 (Validator) is independent of 1–5" and its per-gap table
  listed M6 as "M0 (independent)". The audit correctly noted the Validator cannot consume stamps until
  the engine produces them.
- **Resolution:** Added explicit per-milestone dependency graphs to **both** `RFC_ENGINE_PIPELINE` §7.2
  and `RFC_GAP_CLOSURE` §2b. Each milestone lists Required / Optional / Independent / Blocked
  predecessors. M6 is now explicitly **BLOCKED until M2** (engine produces stamps); M3/M4 are optional
  predecessors (new ROM stamps). Rollout order stated as linear M0→M1→M2→M3→M4→M5→M6→M7→M8. Fixed the
  per-gap table row for M6.
- **Files:** `RFC_ENGINE_PIPELINE.md` §7.2; `RFC_GAP_CLOSURE.md` §1 (subsystem deps), §2 (table row),
  §2b (milestone graph).

## Issue 4 — Performance gate (allocation strategy was an open question)
- **Before:** `RFC_ENGINE_PIPELINE` left allocation as an "open question".
- **Resolution:** Added **NFR-PERF-1** (§7.3): after M2, steady-state frames MUST perform **zero
  per-frame heap allocations** on the hot path. Specifies **pooling** (per-character reusable tree,
  mutated in place; `SkeletonMath` scratch reuse; `IntentBuilder` exempt as off-hot-path). Documented
  as a **hard CI gate** before M2 completes. No architecture change — constrains implementation only.
- **Files:** `RFC_ENGINE_PIPELINE.md` §7.3 (+ referenced in §6 M2 exit criteria).

## Issue 5 — `SkeletonPipeline` ownership
- **Before:** No RFC stated who owns the pipeline instance or its thread-safety.
- **Resolution:** Added explicit ownership to `RFC_ENGINE_PIPELINE` §3: **one instance per character
  (per `SkeletonDefinition`)** — not a singleton, not per-pose, not per-animation. Rationale: the
  Solver's inter-frame smoothing cache is keyed by pose identity, so two characters need separate
  pipelines; per-pose/per-frame would rebuild stages + break the cache. **Thread-safety:** NOT
  internally synchronized; safe under single-thread-per-character; cross-character parallelism via
  separate pipeline instances (no shared mutable stage state). `EngineFlags` are read-only at frame
  time → no locking.
- **Files:** `RFC_ENGINE_PIPELINE.md` §3 (new subsection "Ownership, lifetime, and thread-safety").

## Issue 6 — Gap-report consistency
- **Before:** Gap 5 in the report cited "~18 callers" while the RFCs now say 2.
- **Resolution:** Aligned `CAPABILITY_GAP_REPORT` Gap 5 to the verified count (2) and marked the
  roadmap's "~18" as incorrect. Verified the gap-status table (Blocked/Partial/independent) is
  consistent with the RFCs' dependency graphs (Gap 1 & 7 Blocked; 2/3/4/5/6 Partial; Gap 6 activation
  blocked until M2 but not blocking other phases). No gap is "resolved" in one doc and "partial" in
  another without explanation.
- **Files:** `CAPABILITY_GAP_REPORT.md` Gap 5.

## Issue 7 — Milestone completion criteria
- **Before:** Milestones ended with a "Gate:" line but no consolidated explicit exit checklist.
- **Resolution:** Added explicit **exit-criteria checklists** to both `RFC_ENGINE_PIPELINE` §6 (M0–M8)
  and `RFC_GAP_CLOSURE` §3b (M0–M8), each with concrete ✓ conditions (compile proofs, test greens,
  grep assertions, roadmap-update requirement at M8).
- **Files:** `RFC_ENGINE_PIPELINE.md` §6; `RFC_GAP_CLOSURE.md` §3b.

## Issue 8 — Cross-reference audit
- **Before:** Risk of dangling section references between RFCs.
- **Resolution:** Added `RFC_ENGINE_PIPELINE` §7.4 cross-reference index listing every cited section in
  `RFC_INTENT_LAYER`, `RFC_EXECUTION_CONTRACT`, `RFC_GAP_CLOSURE`, `CAPABILITY_GAP_REPORT` and asserting
  (verified) that all cited section numbers exist. No dangling references, no missing tables, no
  promised-but-absent sections found.
- **Files:** `RFC_ENGINE_PIPELINE.md` §7.4.

---

## Verification
- No remaining assertion-level "~18" in any deliverable (only explanatory notes that the roadmap's
  "~18" is incorrect).
- `declareLimbTarget` appears in M1 of neither RFC (only M2); both RFCs' M1 scopes are byte-equivalent
  in meaning.
- M6 marked BLOCKED-by-M2 in both dependency graphs.
- All cross-referenced section numbers verified present.
