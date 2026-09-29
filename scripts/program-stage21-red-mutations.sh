#!/usr/bin/env bash
# Stage 21 RED mutations: every deliberate break of the first *controlled production invocation* of
# the target scheduling contour must be caught, and every mutated production source must be restored
# byte-identically.
#
# The oracle is the Stage 21 behavioural suite (a real SQLite engine, real repositories, real target
# persistence, real lifecycle service) together with the Stage 21 architecture gate, plus the three
# gates whose *closed caller lists* name this stage's file — so a mutation that changed who calls the
# consumer, or what it is allowed to name, is seen by the pins that count it and not only by the
# pins that read it. The UI suite is in the list because the composed operation's two halves are
# reported as two different notices, and a mutation that collapses them is visible there.
#
# Six harness rules, each enforced below:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. It is reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe below strips comments and asserts the mutated
#    line survived as real code.
#  * **a no-op mutation is never a catch.** The probe also asserts the mutated source actually differs
#    from the original, so a drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row below preserves every public
#    signature, so the test source set compiles and a real oracle runs. Where the rule is about a
#    *dependency* — a legacy scheduler, a legacy slot, a slot status — the row is a second
#    declaration in the forbidden vocabulary rather than a retyped signature, which is the shape a
#    careless caller would actually have and the shape the gate has to refuse.
#  * **the rows are observably distinct.** 2, 3 and 15 all concern "the pass ran when it must not",
#    and they are written so that a different oracle sees each: row 2 by the *first-reach* ordering
#    assertion, row 3 by target persistence changing on a refused start, row 15 by the result type
#    the caller is handed. A harness that scored them by the same oracle would be measuring one rule
#    three times.
#  * **rows 13 and 14 keep the target result's own distinction.** Neither is scored on a notice; one
#    is scored on the refusal propagating, the other on the result case itself.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
START="$MAIN/domain/usecase/ProgramStartService.kt"
ADAPTER="$MAIN/domain/usecase/TargetSchedulePauseAdapter.kt"

SOURCES=("$START" "$ADAPTER")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage21-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 21 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
for source in "${SOURCES[@]}"; do
    cp "$source" "$WORK/$(basename "$source").orig"
done
md5sum "${SOURCES[@]}" > "$WORK/before.md5"
PASS=0
FAIL=0
NOTACATCH=0
RESULTS=()

restore() { for source in "${SOURCES[@]}"; do cp "$WORK/$(basename "$source").orig" "$source"; done; }

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleControlledInvocationIntegrationTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleControlledInvocationArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsControllerTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

mutate() {
    local label="$1" file="$2" old="$3" new="$4"
    restore
    python3 - "$file" "$old" "$new" <<'MUTATE_BODY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + repr(old))
updated = text.replace(old, new, 1)
if updated == text:
    raise SystemExit('MUTATION DID NOT APPLY: ' + repr(old))
open(path, 'w', encoding='utf-8').write(updated)
MUTATE_BODY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label did not apply" >&2
        restore
        exit 1
    fi
    if ! python3 - "$file" "$new" "$WORK/probe.txt" <<'PROBE_BODY'
import re, sys
path, token, out = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
first = token.split('\n')[0].strip()
assert first, 'empty probe token'
stripped = '\n'.join(line.strip() for line in code.splitlines() if line.strip())
assert first in stripped, 'the mutated line did not survive comment stripping:\n' + first
assert token.strip() in text, 'the mutation text is absent from the file'
open(out, 'w', encoding='utf-8').write(code)
PROBE_BODY
    then
        echo "ABORT: could not strip comments from the mutated source" >&2
        restore
        exit 1
    fi
    if run_suites; then
        echo "MISSED: $label"
        RESULTS+=("MISSED $label")
        FAIL=$((FAIL + 1))
    elif grep -qE '^e: ' "$WORK/last-run.log"; then
        echo "NOT A CATCH (compile error, no oracle saw it): $label"
        RESULTS+=("NOT A CATCH $label")
        NOTACATCH=$((NOTACATCH + 1))
        FAIL=$((FAIL + 1))
    else
        echo "caught: $label"
        RESULTS+=("caught $label")
        PASS=$((PASS + 1))
    fi
    restore
}

# The anchor check is in Python, not grep -F, because GNU grep splits a multi-line pattern into
# separate OR-patterns and a "match" there would prove nothing about the anchor.
preflight() {
    local label="$1" file="$2" old="$3" new="$4"
    python3 - "$file" "$old" "$new" "$label" <<'PREFLIGHT_BODY'
import sys
path, old, new, label = sys.argv[1:5]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('PREFLIGHT: anchor absent before baseline: ' + label)
if new in text:
    raise SystemExit('PREFLIGHT: mutation already present before baseline: ' + label)
PREFLIGHT_BODY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label anchor check failed" >&2
        exit 1
    fi
}

# ---- the anchors -------------------------------------------------------------------------------

# 1. The target pass is never invoked, so a controlled Start reports a refusal it never received.
RUN_ANCHOR='        return when (val run = consumer.run(programId, context)) {'
RUN_LOST='        val run: TargetScheduleRunResult = TargetScheduleRunResult.SourceMissing(
            com.monkfitness.app.domain.common.RevisionId("mutated")
        )
        return when (run) {'

# 2. The pass is composed *above* the lifecycle transition, so it runs for a Program that has not
#    started. Every later index in the file is still in order, which is why the gate asserts on the
#    FIRST reach of the consumer and on there being exactly one.
FUN_ANCHOR='    suspend fun start(programId: ProgramId): ProgramStartResult {'
FUN_LOST='    suspend fun start(programId: ProgramId): ProgramStartResult {
        consumer.run(
            programId,
            TargetScheduleRunContext(
                window = TargetScheduleWindow(java.time.LocalDate.MIN, java.time.LocalDate.MIN),
                selection = CompositionSelection(),
                sources = emptyMap(),
                asOf = java.time.LocalDate.MIN,
                pauses = emptyList()
            )
        )'

# 3. A refused start still runs the pass, over a real context built from the stored Program — so the
#    refusal is not merely mis-reported, it *writes rows* the lifecycle never authorised.
REFUSED_ANCHOR='            is ProgramOperationResult.Refused ->
                return ProgramStartResult.StartRefused(outcome.programId, outcome.reason)'
REFUSED_LOST='            is ProgramOperationResult.Refused -> {
                val stored =
                    (lifecycle.program(outcome.programId) as ProgramOperationResult.Success).value
                val refusedAsOf = stored.actualStartDate?.atZone(zone)?.toLocalDate()
                    ?: java.time.LocalDate.MIN
                consumer.run(
                    outcome.programId,
                    TargetScheduleRunContext(
                        window = TargetScheduleWindow(refusedAsOf, refusedAsOf.plusDays(WINDOW_DAYS - 1)),
                        selection = CompositionSelection(),
                        sources = emptyMap(),
                        asOf = refusedAsOf,
                        pauses = emptyList()
                    )
                )
                return ProgramStartResult.StartRefused(outcome.programId, outcome.reason)
            }'

# 15. The lifecycle refusal is reported as a *target* outcome, so the caller can no longer tell that
#     the lifecycle refused at all. Same anchor as row 3, deliberately: the two rows are the same
#     place in the source and different claims about it.
REFUSAL_VOCAB_LOST='            is ProgramOperationResult.Refused ->
                return ProgramStartResult.TargetSchedulingRefused(
                    (lifecycle.program(outcome.programId) as ProgramOperationResult.Success).value,
                    consumer.run(
                        outcome.programId,
                        TargetScheduleRunContext(
                            window = TargetScheduleWindow(java.time.LocalDate.MIN, java.time.LocalDate.MIN),
                            selection = CompositionSelection(),
                            sources = emptyMap(),
                            asOf = java.time.LocalDate.MIN,
                            pauses = emptyList()
                        )
                    )
                )'

# 4. The as-of date is read off the device instead of taken from the moment the transition recorded.
ASOF_ANCHOR='        val asOf = started.actualStartDate?.atZone(zone)?.toLocalDate()'
ASOF_LOST='        val asOf = java.time.LocalDate.now(zone)'

# 5. The bounded target window is shortened — the horizon is a named policy, not a convenient number.
WINDOW_ANCHOR='            window = TargetScheduleWindow(asOf, asOf.plusDays(WINDOW_DAYS - 1)),'
WINDOW_LOST='            window = TargetScheduleWindow(asOf, asOf.plusDays(6)),'

# 6. The window is derived from the legacy planning horizon, which would make the target window a
#    compatibility alias for another generation's constant. Note that the constant happens to hold
#    30 as well, so **no behavioural oracle can see this row** — the two windows are identical and
#    only the gate's whole-token ban can. That is the reason the ban exists, and it is why this row
#    is written as the real reference rather than as an equivalent literal.
WINDOW_LEGACY_LOST='            window = TargetScheduleWindow(
                asOf,
                asOf.plusDays(com.monkfitness.app.domain.program.PLANNING_HORIZON_DAYS.toLong() - 1)
            ),'

# 7. A composition is inferred, so rules are combined without anything having stated it.
SELECTION_ANCHOR='            selection = CompositionSelection(),'
SELECTION_LOST='            selection = CompositionSelection.combine("rule-strength"),'

# 8./9. The resolved-source map is filled in from the authored rules, which are a different fact.
SOURCES_ANCHOR='            sources = emptyMap(),'
SOURCES_FROM_RULES_LOST='            sources = listOf(
                TargetScheduleDefinition(
                    ruleId = "rule-strength",
                    workoutId = "workout-strength",
                    cadence = com.monkfitness.app.domain.program.ScheduleCadence.Daily,
                    anchorDate = asOf
                )
            ).associate { definition ->
                definition.ruleId to
                    com.monkfitness.app.domain.program.target.ResolvedScheduleSource(
                        sourceRuleId = definition.ruleId,
                        occurrences = emptyList()
                    )
            },'
SOURCES_DERIVED_LOST='            sources = mapOf(
                "rule-strength" to com.monkfitness.app.domain.program.target.ResolvedScheduleSource(
                    sourceRuleId = "rule-strength",
                    occurrences = emptyList()
                )
            ),'

# 10./17. The persisted pauses are dropped, and — the other half of the same line — the legacy slots
#     are rewritten during the invocation.
PAUSES_ANCHOR='            pauses = TargetSchedulePauseAdapter.windowsOf(
                scheduleRepository.pausesOfProgram(programId),
                zone
            )'
PAUSES_LOST='            pauses = emptyList(),'
SLOTS_MUTATED_LOST='            pauses = TargetSchedulePauseAdapter.windowsOf(
                scheduleRepository.pausesOfProgram(programId),
                zone
            ).also { windows ->
                scheduleRepository.slotsOfProgram(programId).forEach { slot ->
                    scheduleRepository.recordSlotOutcome(
                        slotId = slot.slotId,
                        status = com.monkfitness.app.domain.program.SlotStatus.COMPLETED,
                        completedAt = java.time.Instant.EPOCH
                    )
                }
            }'

# 13. A thrown target refusal is caught here and re-reported as a different typed absence, so the
#     stage that owns the rule never gets to say which rule refused.
SWALLOW_ANCHOR='        return when (val run = consumer.run(programId, context)) {'
SWALLOW_LOST='        val run: TargetScheduleRunResult = try {
            consumer.run(programId, context)
        } catch (failure: Throwable) {
            TargetScheduleRunResult.SourceMalformed(
                com.monkfitness.app.domain.common.RevisionId("swallowed"),
                failure.message ?: "swallowed"
            )
        }
        return when (run) {'

# 14. Every typed refusal is answered with a success. The success case is typed on the consumer's
#     *success* arm, so the row has to fabricate one — which is exactly the second translation policy
#     this stage forbids, made concrete.
REFUSAL_TO_SUCCESS_ANCHOR='            is TargetScheduleRunResult.ProgramNotFound,
            is TargetScheduleRunResult.CurrentRevisionMissing,
            is TargetScheduleRunResult.SourceMissing,
            is TargetScheduleRunResult.SourceMalformed ->
                ProgramStartResult.TargetSchedulingRefused(started, run)'
REFUSAL_TO_SUCCESS_LOST='            is TargetScheduleRunResult.ProgramNotFound,
            is TargetScheduleRunResult.CurrentRevisionMissing,
            is TargetScheduleRunResult.SourceMissing,
            is TargetScheduleRunResult.SourceMalformed -> {
                val mutatedPlan = com.monkfitness.app.domain.program.target.TargetPlan(
                    planned = emptyList(),
                    reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                        preserved = emptyList(),
                        superseded = emptyList(),
                        added = emptyList()
                    )
                )
                val mutatedDecision = com.monkfitness.app.domain.program.target.TargetScheduleDecision(
                    targetPlan = mutatedPlan,
                    preserved = emptyList(),
                    retained = emptyList(),
                    created = emptyList(),
                    superseded = emptyList(),
                    missed = emptyList()
                )
                val mutatedInput = TargetScheduleInput(
                    programId = programId,
                    revisionId = com.monkfitness.app.domain.common.RevisionId("mutated"),
                    scheduleDefinitions = emptyList(),
                    window = TargetScheduleWindow(asOf, asOf),
                    selection = CompositionSelection(),
                    existingOccurrences = emptyList(),
                    sources = emptyMap(),
                    asOf = asOf,
                    pauses = emptyList(),
                    programDayBindings = emptyList()
                )
                val mutatedRequest = TargetScheduleOrchestrationRequest(
                    programId = programId,
                    revisionId = com.monkfitness.app.domain.common.RevisionId("mutated"),
                    schedules = emptyList(),
                    window = TargetScheduleWindow(asOf, asOf),
                    selection = CompositionSelection(),
                    existing = emptyList(),
                    sources = emptyMap(),
                    asOf = asOf,
                    pauses = emptyList(),
                    programDayBindings = emptyList()
                )
                ProgramStartResult.Started(
                    started,
                    TargetScheduleRunResult.Scheduled(
                        com.monkfitness.app.domain.common.RevisionId("mutated"),
                        mutatedInput,
                        TargetScheduleOrchestrationResult(
                            request = mutatedRequest,
                            targetPlan = mutatedPlan,
                            decision = mutatedDecision,
                            applicationResult = TargetScheduleApplicationResult(
                                decision = mutatedDecision,
                                presentations = emptyList(),
                                persistenceResult = TargetScheduleSlotPersistenceResult(
                                    emptyList(),
                                    emptyList()
                                )
                            )
                        )
                    )
                )
            }'

# 16./18. Second declarations in the forbidden vocabularies. A retyped signature would stop the test
#     source set compiling and charge the row for the wrong reason; these are the shapes a careless
#     caller would actually have.
CLASS_ANCHOR='class ProgramStartService('
LEGACY_SCHEDULER_LOST='private fun legacySchedulingOwner(): Class<*> =
    com.monkfitness.app.domain.usecase.ProgramScheduler::class.java

class ProgramStartService('
SLOT_STATUS_LOST='private fun executionFromSlotStatus(
    status: com.monkfitness.app.domain.program.SlotStatus
): String = status.name

class ProgramStartService('

# 11. An open pause interval is given an invented last date. The successful-start route cannot reach
#     this branch — PAUSE is legal only from RUNNING and START only from NOT_STARTED — so the gate
#     pins the refusal in the source and the behavioural suite reaches it by planting the interval.
OPEN_ANCHOR='            val endedAt = pause.endedAt
            require(endedAt != null) {
                "an open pause interval cannot become a target pause window: it has no last date, " +
                    "and inventing one would suppress occurrences the Program was never paused for"
            }'
OPEN_INVENTED_LOST='            val endedAt = pause.endedAt ?: java.time.LocalDate.MAX
                .atStartOfDay(zone)
                .toInstant()'

# 12. The persisted instants are read in a calendar the operation was not given.
ZONE_ANCHOR='            ProgramPauseWindow(
                firstDate = pause.startedAt.atZone(zone).toLocalDate(),
                lastDate = endedAt.atZone(zone).toLocalDate()
            )'
ZONE_LOST='            ProgramPauseWindow(
                firstDate = pause.startedAt.atZone(java.time.ZoneId.of("America/Los_Angeles")).toLocalDate(),
                lastDate = endedAt.atZone(java.time.ZoneId.of("America/Los_Angeles")).toLocalDate()
            )'

echo '== preflight =='

preflight 'the target pass is never invoked' "$START" "$RUN_ANCHOR" "$RUN_LOST"
preflight 'the pass runs before the lifecycle start' "$START" "$FUN_ANCHOR" "$FUN_LOST"
preflight 'the pass runs after a refused start' "$START" "$REFUSED_ANCHOR" "$REFUSED_LOST"
preflight 'a lifecycle refusal is reported as a target outcome' "$START" "$REFUSED_ANCHOR" "$REFUSAL_VOCAB_LOST"
preflight 'the as-of date is read off the device' "$START" "$ASOF_ANCHOR" "$ASOF_LOST"
preflight 'the target window is replaced' "$START" "$WINDOW_ANCHOR" "$WINDOW_LOST"
preflight 'the window comes from the legacy planning horizon' "$START" "$WINDOW_ANCHOR" "$WINDOW_LEGACY_LOST"
preflight 'a composition is inferred' "$START" "$SELECTION_ANCHOR" "$SELECTION_LOST"
preflight 'resolved sources are synthesised from the authored rules' "$START" "$SOURCES_ANCHOR" "$SOURCES_FROM_RULES_LOST"
preflight 'a source is fabricated for the derived rule' "$START" "$SOURCES_ANCHOR" "$SOURCES_DERIVED_LOST"
preflight 'the persisted pauses are dropped' "$START" "$PAUSES_ANCHOR" "$PAUSES_LOST"
preflight 'the legacy slots are rewritten' "$START" "$PAUSES_ANCHOR" "$SLOTS_MUTATED_LOST"
preflight 'a thrown target refusal is swallowed' "$START" "$SWALLOW_ANCHOR" "$SWALLOW_LOST"
preflight 'every target refusal becomes a success' "$START" "$REFUSAL_TO_SUCCESS_ANCHOR" "$REFUSAL_TO_SUCCESS_LOST"
preflight 'the legacy scheduler is reached' "$START" "$CLASS_ANCHOR" "$LEGACY_SCHEDULER_LOST"
preflight 'target execution is rebuilt from a slot status' "$START" "$CLASS_ANCHOR" "$SLOT_STATUS_LOST"
preflight 'an open pause is given an invented end' "$ADAPTER" "$OPEN_ANCHOR" "$OPEN_INVENTED_LOST"
preflight 'the pauses are read in the wrong calendar' "$ADAPTER" "$ZONE_ANCHOR" "$ZONE_LOST"

echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -60 "$WORK/last-run.log"
    exit 1
fi

echo '== mutations =='

mutate 'the target pass is never invoked' "$START" "$RUN_ANCHOR" "$RUN_LOST"
mutate 'the pass runs before the lifecycle start' "$START" "$FUN_ANCHOR" "$FUN_LOST"
mutate 'the pass runs after a refused start' "$START" "$REFUSED_ANCHOR" "$REFUSED_LOST"
mutate 'the as-of date is read off the device' "$START" "$ASOF_ANCHOR" "$ASOF_LOST"
mutate 'the target window is replaced' "$START" "$WINDOW_ANCHOR" "$WINDOW_LOST"
mutate 'the window comes from the legacy planning horizon' "$START" "$WINDOW_ANCHOR" "$WINDOW_LEGACY_LOST"
mutate 'a composition is inferred' "$START" "$SELECTION_ANCHOR" "$SELECTION_LOST"
mutate 'resolved sources are synthesised from the authored rules' "$START" "$SOURCES_ANCHOR" "$SOURCES_FROM_RULES_LOST"
mutate 'a source is fabricated for the derived rule' "$START" "$SOURCES_ANCHOR" "$SOURCES_DERIVED_LOST"
mutate 'the persisted pauses are dropped' "$START" "$PAUSES_ANCHOR" "$PAUSES_LOST"
mutate 'the legacy slots are rewritten' "$START" "$PAUSES_ANCHOR" "$SLOTS_MUTATED_LOST"
mutate 'a thrown target refusal is swallowed' "$START" "$SWALLOW_ANCHOR" "$SWALLOW_LOST"
mutate 'every target refusal becomes a success' "$START" "$REFUSAL_TO_SUCCESS_ANCHOR" "$REFUSAL_TO_SUCCESS_LOST"
mutate 'a lifecycle refusal is reported as a target outcome' "$START" "$REFUSED_ANCHOR" "$REFUSAL_VOCAB_LOST"
mutate 'the legacy scheduler is reached' "$START" "$CLASS_ANCHOR" "$LEGACY_SCHEDULER_LOST"
mutate 'target execution is rebuilt from a slot status' "$START" "$CLASS_ANCHOR" "$SLOT_STATUS_LOST"
mutate 'an open pause is given an invented end' "$ADAPTER" "$OPEN_ANCHOR" "$OPEN_INVENTED_LOST"
mutate 'the pauses are read in the wrong calendar' "$ADAPTER" "$ZONE_ANCHOR" "$ZONE_LOST"

restore
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo 'every mutated source restored byte-identically'
else
    echo 'MISSED byte-identical restoration'
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
    cat "$WORK/md5check.log"
fi

printf '%s\n' "${RESULTS[@]}"
echo "control GREEN"
echo "caught: $PASS"
echo "missed: $FAIL"
echo "not-a-catch: $NOTACATCH"
[ "$FAIL" -eq 0 ]
