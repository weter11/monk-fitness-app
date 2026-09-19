#!/usr/bin/env bash
# §30 step 11 — the deliberate RED mutations of the target Adaptive Engine, its Policy and the
# Aggregate Load Guard.
#
# Each block applies one mutation to a *production* source, runs the focused adaptive-engine suite, and
# records whether the suite caught it. A rule whose mutation does NOT fail a test is a rule the suite
# does not prove, so this file is the evidence that confirmation, cooldown, recovery priority and exit,
# the progression and regression thresholds, HOLD semantics, scope, guard filtering, user-authored
# protection, adjustment identity, deterministic ordering and unit separation are pinned by tests rather
# than by prose.
#
# Usage: bash scripts/program-adaptive-engine-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME) exported in the SAME shell.
# A mis-env'd run fakes BOTH a pass and a miss, so export first:
#   export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
#   export ANDROID_HOME=/home/wer/devis/android-sdk
#   export GRADLE_USER_HOME="$(pwd)/.gradle-home"
# Confirm the daemons point at <repo>/.gradle-home with
#   ps -eo pid,cmd | grep -E 'GradleDaemon|KotlinCompileDaemon'
#
# The script restores every mutated file from a backup and verifies the restoration by md5 at the end,
# so a run leaves the reviewed bytes exactly as they were.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()

ENGINE="app/src/main/java/com/monkfitness/app/domain/adaptive/engine"

SOURCES=(
  "$ENGINE/ProgramAdaptivePolicy.kt"
  "$ENGINE/ProgramAdaptiveEngine.kt"
  "$ENGINE/ProgramAggregateLoadGuard.kt"
  "$ENGINE/ProgramLoadComparison.kt"
  "$ENGINE/ProgramAdaptiveSignals.kt"
  "$ENGINE/ProgramProgressionRelation.kt"
  "$ENGINE/ProgramAdaptiveReason.kt"
  "$ENGINE/ProgramAdaptiveElement.kt"
)

# md5 of the reviewed bytes, so the restore is provable rather than assumed.
md5sum "${SOURCES[@]}" > "$TMP/before.md5"

# apply <label> <file> <old> <new>
apply() {
  local label="$1" file="$2" old="$3" new="$4"
  cp "$file" "$TMP/$(echo "$label" | tr ' ' '_').bak"
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
assert old in text, f"mutation anchor not found in {path}: {old[:70]}"
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
}

restore() {
  local label="$1" file="$2"
  cp "$TMP/$(echo "$label" | tr ' ' '_').bak" "$file"
}

# run_one <label> <expect_failure: yes|no>
run_one() {
  local label="$1" expect="$2"
  local out
  out="$(./gradlew --offline :app:testDebugUnitTest \
      --tests "com.monkfitness.app.domain.adaptive.engine.*" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  # A mutation that does not compile is caught too: the suite cannot run against it.
  echo "$out" | grep -qE "BUILD FAILED|^e: " && caught="yes"
  local ok="no"
  if [ "$expect" = "yes" ]; then
    [ "$caught" = "yes" ] && ok="yes"
  else
    [ "$caught" = "no" ] && ok="yes"
  fi
  if [ "$ok" = "yes" ]; then
    PASS=$((PASS + 1))
    echo "RED-OK   [$label] expected_failure=$expect caught=$caught"
  else
    FAIL=$((FAIL + 1))
    FAILED_MUTATIONS+=("$label")
    echo "RED-MISS [$label] expected_failure=$expect caught=$caught"
  fi
}

echo "== RED mutations: each must FAIL the adaptive-engine suite (a rule is only proven if a break is caught) =="

# 1. CONFIRMATION: a direction earns a change in its first window.
apply "confirmation-windows-ignored" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "            progressHolds && window.precedingProgressQualifyingWindows + 1 >= progressConfirmingWindows" \
  "            progressHolds"
run_one "an unconfirmed direction changes the family (§15: progression requires sufficient evidence)" yes
restore "confirmation-windows-ignored" "$ENGINE/ProgramAdaptivePolicy.kt"

# 2. COOLDOWN: the progression cooldown stops bounding a change.
apply "cooldown-always-elapsed" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "        val cooldownElapsed = window.qualifyingWindowsSinceLastChange == null ||
            window.qualifyingWindowsSinceLastChange >= progressionCooldownWindows" \
  "        val cooldownElapsed = true"
run_one "a change the cooldown was holding back goes through (§15: the cooldown bounds a change)" yes
restore "cooldown-always-elapsed" "$ENGINE/ProgramAdaptivePolicy.kt"

# 3. RECOVERY PRIORITY: a family in the recovery state is evaluated as if it were not.
apply "recovery-gating-removed" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "        if (window.state == AdaptiveState.RECOVERY) {" \
  "        if (false) {"
run_one "recovery stops outranking a progression (§14: recovery is the safety path)" yes
restore "recovery-gating-removed" "$ENGINE/ProgramAdaptivePolicy.kt"

# 4. RECOVERY ENTRY: unabsorbed load no longer enters the safety state.
apply "recovery-entry-removed" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "        val unabsorbedLoad = evidence.signals.recentIsAboveBaseline && (deteriorating || reducedExposure)" \
  "        val unabsorbedLoad = false"
run_one "a window of unabsorbed load never enters recovery (§14)" yes
restore "recovery-entry-removed" "$ENGINE/ProgramAdaptivePolicy.kt"

# 5. RECOVERY EXIT: the exit gate can never be met, so recovery never ends.
apply "recovery-exit-never-met" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "            val exitMet = window.recoveryQualifyingWindows >= recoveryExitQualifyingWindows" \
  "            val exitMet = false"
run_one "recovery never exits (§14: recovery requires its own explicit exit condition)" yes
restore "recovery-exit-never-met" "$ENGINE/ProgramAdaptivePolicy.kt"

# 6. RECOVERY EXIT: leaving recovery jumps straight into a progression.
apply "recovery-exit-progresses-directly" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "                holding(AdaptiveState.HOLD, ProgramAdaptiveReason.RECOVERY_EXITED)" \
  "                changing(AdaptiveState.PROGRESS, ProgramAdaptiveReason.SUSTAINED_POSITIVE)"
run_one "recovery exits into a progression instead of a hold (§14)" yes
restore "recovery-exit-progresses-directly" "$ENGINE/ProgramAdaptivePolicy.kt"

# 7. REGRESSION THRESHOLD: a negative trend regresses without a measured shortfall.
apply "regression-shortfall-threshold-removed" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "            (evidence.signals.newerHalf?.shortfallSets ?: 0) >= regressMinimumShortfallSets" \
  "            true"
run_one "a family regresses on a trend alone (§15: one bad result does not regress)" yes
restore "regression-shortfall-threshold-removed" "$ENGINE/ProgramAdaptivePolicy.kt"

# 8. ATTENDANCE GATE: opportunities that went untaken stop mattering.
apply "attendance-gate-removed" "$ENGINE/ProgramAdaptivePolicy.kt" \
  "            evidence.signals.consistency != ProgramConsistency.LOW &&" \
  ""
run_one "a window whose opportunities went untaken still progresses (§7: adequate adherence)" yes
restore "attendance-gate-removed" "$ENGINE/ProgramAdaptivePolicy.kt"

# 9. HOLD SEMANTICS: a hold reports an action it is not.
apply "hold-reports-a-progression" "$ENGINE/ProgramAdaptiveEngine.kt" \
  "            action = change?.action ?: AdaptiveAction.HOLD," \
  "            action = change?.action ?: AdaptiveAction.PROGRESS,"
run_one "a hold is recorded as a change (§15: HOLD is a real first-class result)" yes
restore "hold-reports-a-progression" "$ENGINE/ProgramAdaptiveEngine.kt"

# 10. USER-AUTHORED PROTECTION: the engine adapts an element the user owns.
apply "user-authored-element-adapted" "$ENGINE/ProgramAdaptiveEngine.kt" \
  "        val resolution = if (!request.element.isAdaptable) {" \
  "        val resolution = if (false) {"
run_one "a pinned or user-authored element is adapted (§15, §18)" yes
restore "user-authored-element-adapted" "$ENGINE/ProgramAdaptiveEngine.kt"

# 11. ADJUSTMENT IDENTITY: the change is expressed against a different occurrence.
apply "adjustment-identity-broken" "$ENGINE/ProgramAdaptiveEngine.kt" \
  "        programExerciseId = presentation.programExerciseId," \
  "        programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId(\"another-element\"),"
run_one "an adjustment changes a different plan element than the one it was decided for (§16)" yes
restore "adjustment-identity-broken" "$ENGINE/ProgramAdaptiveEngine.kt"

# 12. CEILING AND FLOOR: the family's own ends stop being ends.
apply "ceiling-and-floor-not-boundaries" "$ENGINE/ProgramAdaptiveEngine.kt" \
  "        val atTheBoundary = if (up) level >= relation.highestLevel else level <= relation.lowestLevel" \
  "        val atTheBoundary = false"
run_one "the ceiling stops a progression without saying so (§15)" yes
restore "ceiling-and-floor-not-boundaries" "$ENGINE/ProgramAdaptiveEngine.kt"

# 13. SCOPE: two profiles at different granularities get compared anyway.
apply "scope-mismatch-compared" "$ENGINE/ProgramLoadComparison.kt" \
  "            if (baseline.scope != candidate.scope) {" \
  "            if (false) {"
run_one "an exercise-scope profile is compared against a session-scope one (§18: compatible scopes)" yes
restore "scope-mismatch-compared" "$ENGINE/ProgramLoadComparison.kt"

# 14. UNIT SEPARATION: a repetition count is compared against a duration.
apply "unit-separation-removed" "$ENGINE/ProgramLoadComparison.kt" \
  "                (baselineUsesThisUnit && candidateOtherUnit > 0) ||
                    (candidateUsesThisUnit && baselineOtherUnit > 0) ->" \
  "                false ->"
run_one "repetitions are compared against seconds (§17: no cross-unit conversion)" yes
restore "unit-separation-removed" "$ENGINE/ProgramLoadComparison.kt"

# 15. DETERMINISM: the relation keeps whatever order it was handed.
apply "relation-order-not-canonical" "$ENGINE/ProgramProgressionRelation.kt" \
  "        fun canonical(variants: List<ProgramProgressionVariant>): List<ProgramProgressionVariant> =
            variants.sortedWith(compareBy({ it.level }, { it.exerciseId }))" \
  "        fun canonical(variants: List<ProgramProgressionVariant>): List<ProgramProgressionVariant> =
            variants"
run_one "a relation's variants decide the ordering (§18: all tie-breaking is deterministic)" yes
restore "relation-order-not-canonical" "$ENGINE/ProgramProgressionRelation.kt"

# 16. GUARD FILTERING: the guard stops refusing an excessive automatic increase.
apply "guard-filtering-removed" "$ENGINE/ProgramAggregateLoadGuard.kt" \
  "        if (exceeded.isNotEmpty()) {" \
  "        if (false) {"
run_one "an automatic change past its tolerance is applied anyway (§18)" yes
restore "guard-filtering-removed" "$ENGINE/ProgramAggregateLoadGuard.kt"

# 17. GUARD SCOPE: the guard rules on a change that takes load away.
apply "guard-rules-on-a-regression" "$ENGINE/ProgramAggregateLoadGuard.kt" \
  "        if (action == AdaptiveAction.HOLD || action == AdaptiveAction.REGRESS ||
            action == AdaptiveAction.CHANGE_REST
        ) {" \
  "        if (action == AdaptiveAction.HOLD ||
            action == AdaptiveAction.CHANGE_REST
        ) {"
run_one "the guard stops declaring a regression not its business (§18: it never invents a regression)" yes
restore "guard-rules-on-a-regression" "$ENGINE/ProgramAggregateLoadGuard.kt"

# 18. RECENT CONTEXT: the guard stops asking whether the load is owed.
apply "recent-context-rule-loosened" "$ENGINE/ProgramAggregateLoadGuard.kt" \
  "            recentLoad.exposure.opportunities > recentLoad.exposure.completedOpportunities" \
  "            true"
run_one "a recent context that owes nothing still blocks a change (§18: baseline + delta + context)" yes
restore "recent-context-rule-loosened" "$ENGINE/ProgramAggregateLoadGuard.kt"

# 19. Control: the un-mutated tree must stay GREEN (the suite is not failing for nothing).
run_one "unmutated tree stays GREEN (control)" no

echo
echo "== restoring and verifying the reviewed bytes =="
md5sum -c "$TMP/before.md5"

echo
echo "== summary: $PASS caught, $FAIL missed =="
if [ "$FAIL" -gt 0 ]; then
  echo "MISSED: ${FAILED_MUTATIONS[*]}"
  exit 1
fi
echo "every mandated rule is proven by a failing test when broken."
echo
echo "BEHAVIOURAL, NOT SEMANTIC, mutations: three claims are deliberately absent from the list above."
echo "  * 'the engine reads no clock and no random source' is a property of the whole package rather than"
echo "    of one line: ProgramAdaptiveArchitectureTest scans every engine source for Random, shuffled,"
echo "    currentTimeMillis, Instant.now, Clock, UUID, hashCode() and System., so any of them appearing"
echo "    anywhere fails there."
echo "  * 'the engine persists nothing and creates no revision' is not expressible as a mutation: the"
echo "    engine holds no repository, no DAO, no transaction and no id source, so there is no line to"
echo "    break. It is asserted structurally (the engine object declares no field at all, and the result's"
echo "    own field set is pinned)."
echo "  * 'a REST adaptation is not faked' has no production line to mutate: the policy reports it"
echo "    unsupported and the resolution has no target to produce, which is asserted behaviourally in"
echo "    ProgramAdaptiveEngineTest.aRestAdaptationIsUnsupportedAndStaysUnapplied."
