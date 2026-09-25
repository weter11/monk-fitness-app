#!/usr/bin/env bash
# Phase 13 RED mutations: every deliberate break of the target input boundary must be caught, and the
# adapter source must be restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleInputAdapter.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage13-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 13 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetScheduleInputAdapter.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

restore() { cp "$WORK/TargetScheduleInputAdapter.kt.orig" "$SOURCE"; }

# The oracle is the behavioural suite plus the architecture gate. The Stage 2 guard comes along
# because a mutation that makes the adapter *call* the resolver also makes it a second consumer of a
# Stage 2 type, which that closed list forbids — the two guards then decide the same row, which is
# recorded rather than hidden.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleInputAdapterTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleInputAdapterArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

mutate() {
    local label="$1" old="$2" new="$3"
    restore
    python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + repr(old))
updated = text.replace(old, new, 1)
if updated == text:
    raise SystemExit('MUTATION DID NOT APPLY: ' + repr(old))
open(path, 'w', encoding='utf-8').write(updated)
PY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label did not apply" >&2
        restore
        exit 1
    fi
    # Hygiene rule 5: a mutation that only puts a banned token in a comment exercises nothing, because
    # the gates strip comments. Every replacement below is real code at the layer the oracle reads;
    # this asserts that for the token the gate matches on, so a future row cannot become prose.
    if ! python3 - "$SOURCE" "$WORK/probe.txt" <<'PY'
import re, sys
path, out = sys.argv[1:3]
text = open(path, encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
open(out, 'w', encoding='utf-8').write(code)
PY
    then
        echo "ABORT: could not strip comments from the mutated source" >&2
        restore
        exit 1
    fi
    if run_suites; then
        echo "MISSED: $label"
        RESULTS+=("MISSED $label")
        FAIL=$((FAIL + 1))
    else
        echo "caught: $label"
        RESULTS+=("caught $label")
        PASS=$((PASS + 1))
    fi
    restore
}

# Every anchor must exist before the baseline and its replacement must not: a drifted anchor reports
# MISSED, which is a claim about this harness rather than about the code. The check is in Python, not
# grep -F, because GNU grep splits a multi-line pattern into separate OR-patterns.
preflight() {
    local label="$1" old="$2" new="$3"
    python3 - "$SOURCE" "$old" "$new" "$label" <<'PY'
import sys
path, old, new, label = sys.argv[1:5]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('PREFLIGHT: anchor absent before baseline: ' + label)
if new in text:
    raise SystemExit('PREFLIGHT: mutation already present before baseline: ' + label)
PY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label anchor check failed" >&2
        exit 1
    fi
}

CONVERT_BLOCK='    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchorDate
    )'
REQUEST_BLOCK='        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )'
ADAPT_SIGNATURE='    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {'
ADAPT_LINE='        val schedules = targetSchedules(input.scheduleDefinitions)'

echo '== preflight =='
preflight 'derive workoutId from a ProgramDayId' "$ADAPT_LINE" \
    '        val schedules = targetSchedules(input.scheduleDefinitions).map { schedule ->
            schedule.copy(workoutId = input.programDayBindings.first().programDayId.value)
        }'
preflight 'derive ruleId from a ProgramDay position' "$CONVERT_BLOCK" \
    '    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = com.monkfitness.app.domain.program.ProgramDay(
            programDayId = com.monkfitness.app.domain.common.ProgramDayId(ruleId),
            position = 1,
            type = com.monkfitness.app.domain.program.ProgramDayType.TRAINING
        ).position.toString(),
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchorDate
    )'
preflight 'derive cadence from the legacy ProgramSchedule' "$CONVERT_BLOCK" \
    '    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = when (val legacy = com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)) {
            is com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays ->
                com.monkfitness.app.domain.program.ScheduleCadence.FixedWeekdays(legacy.weekdays)
            is com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek ->
                com.monkfitness.app.domain.program.ScheduleCadence.SessionsPerWeek(legacy.sessionsPerWeek)
        },
        anchorDate = anchorDate
    )'
preflight 'derive a ProgramDayId from the workoutId string' "$REQUEST_BLOCK" \
    '        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings.map { binding ->
                com.monkfitness.app.domain.program.target.TargetProgramDayBinding(
                    workoutId = binding.workoutId,
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId(binding.workoutId)
                )
            }
        )'
preflight 'parse an occurrenceKey into components' "$REQUEST_BLOCK" \
    '        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences.map { occurrence ->
                val parsed = occurrence.occurrence.occurrenceKey.split(":")
                occurrence.copy(
                    occurrence = occurrence.occurrence.copy(
                        components = listOf(
                            com.monkfitness.app.domain.program.OccurrenceComponent(
                                ruleId = parsed[0],
                                workoutId = parsed.getOrElse(1) { "legacy" }
                            )
                        )
                    )
                )
            },
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )'
preflight 'sort the caller-owned existing list' '            existing = input.existingOccurrences,' \
    '            existing = input.existingOccurrences.sortedByDescending { it.occurrence.plannedFor },'
preflight 'rebuild a ResolvedScheduleSource' '            sources = input.sources,' \
    '            sources = input.sources.mapValues { (_, source) ->
                com.monkfitness.app.domain.program.target.ResolvedScheduleSource(
                    sourceRuleId = source.sourceRuleId,
                    occurrences = emptyList()
                )
            },'
preflight 'filter the caller-owned pauses' '            pauses = input.pauses,' \
    '            pauses = input.pauses.filter { pause -> !pause.covers(input.asOf) },'
preflight 'choose the device date instead of the caller asOf' '            asOf = input.asOf,' \
    '            asOf = java.time.LocalDate.now(),'
preflight 'call the planner' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        com.monkfitness.app.domain.program.target.TargetPlanner.plan(
            schedules = targetSchedules(input.scheduleDefinitions),
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources
        )'
preflight 'call the temporal policy' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        com.monkfitness.app.domain.program.target.TargetSchedulePolicy.decide(
            targetPlan = com.monkfitness.app.domain.program.target.TargetPlan(
                planned = emptyList(),
                reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                    preserved = emptyList(),
                    superseded = emptyList(),
                    added = emptyList()
                )
            ),
            existing = input.existingOccurrences,
            asOf = input.asOf,
            pauses = input.pauses
        )'
preflight 'call the orchestrator' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        TargetScheduleOrchestrator::class.java.name'
preflight 'construct a fake ExistingOccurrence from a slot' "$REQUEST_BLOCK" \
    '        val rebuilt = input.existingOccurrences + com.monkfitness.app.domain.program.ExistingOccurrence(
            occurrence = com.monkfitness.app.domain.program.PlannedOccurrence(
                occurrenceKey = "legacy:" + input.programDayBindings.first().programDayId.value,
                plannedFor = input.asOf,
                components = listOf(
                    com.monkfitness.app.domain.program.OccurrenceComponent(
                        ruleId = "legacy",
                        workoutId = input.programDayBindings.first().programDayId.value
                    )
                )
            ),
            execution = com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED,
            actuals = emptyList()
        )
        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = rebuilt,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )'
preflight 'inject a repository' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        val directRepository: com.monkfitness.app.data.repository.ProgramScheduleRepository? = null
        directRepository?.slotsOfProgram(input.programId)'
preflight 'inject a Clock' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        val directClock: java.time.Clock? = java.time.Clock.systemDefaultZone()
        directClock?.millis()'
preflight 'inject an IdGenerator' 'class TargetScheduleInputAdapter {' \
    'class TargetScheduleInputAdapter(
    private val directGenerator: com.monkfitness.app.di.IdGenerator? = null
) {'
preflight 'widen the window to today' '            window = input.window,' \
    '            window = TargetScheduleWindow(
                from = minOf(input.window.from, input.asOf),
                through = maxOf(input.window.through, input.asOf.plusDays(30))
            ),'
preflight 'deduplicate definitions instead of refusing them' \
    '        return definitions.map { definition -> definition.toTargetSchedule() }' \
    '        return definitions
            .distinctBy { definition -> definition.ruleId }
            .map { definition -> definition.toTargetSchedule() }'
preflight 'sort the definitions' '        return definitions.map { definition -> definition.toTargetSchedule() }' \
    '        return definitions.sortedBy { definition -> definition.ruleId }
            .map { definition -> definition.toTargetSchedule() }'
preflight 'drop a refused definition into a default' \
    '            if (definition.ruleId.isBlank()) {
                throw TargetScheduleInputException.BlankTargetRuleIdentity(index)
            }' \
    '            if (definition.ruleId.isBlank()) {
                return@forEachIndexed
            }'
echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -40 "$WORK/last-run.log"
    exit 1
fi

mutate 'derive workoutId from a ProgramDayId' "$ADAPT_LINE" \
    '        val schedules = targetSchedules(input.scheduleDefinitions).map { schedule ->
            schedule.copy(workoutId = input.programDayBindings.first().programDayId.value)
        }'
mutate 'derive ruleId from a ProgramDay position' "$CONVERT_BLOCK" \
    '    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = com.monkfitness.app.domain.program.ProgramDay(
            programDayId = com.monkfitness.app.domain.common.ProgramDayId(ruleId),
            position = 1,
            type = com.monkfitness.app.domain.program.ProgramDayType.TRAINING
        ).position.toString(),
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchorDate
    )'
mutate 'derive cadence from the legacy ProgramSchedule' "$CONVERT_BLOCK" \
    '    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = when (val legacy = com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)) {
            is com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays ->
                com.monkfitness.app.domain.program.ScheduleCadence.FixedWeekdays(legacy.weekdays)
            is com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek ->
                com.monkfitness.app.domain.program.ScheduleCadence.SessionsPerWeek(legacy.sessionsPerWeek)
        },
        anchorDate = anchorDate
    )'
mutate 'derive a ProgramDayId from the workoutId string' "$REQUEST_BLOCK" \
    '        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings.map { binding ->
                com.monkfitness.app.domain.program.target.TargetProgramDayBinding(
                    workoutId = binding.workoutId,
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId(binding.workoutId)
                )
            }
        )'
mutate 'parse an occurrenceKey into components' "$REQUEST_BLOCK" \
    '        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences.map { occurrence ->
                val parsed = occurrence.occurrence.occurrenceKey.split(":")
                occurrence.copy(
                    occurrence = occurrence.occurrence.copy(
                        components = listOf(
                            com.monkfitness.app.domain.program.OccurrenceComponent(
                                ruleId = parsed[0],
                                workoutId = parsed.getOrElse(1) { "legacy" }
                            )
                        )
                    )
                )
            },
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )'
mutate 'sort the caller-owned existing list' '            existing = input.existingOccurrences,' \
    '            existing = input.existingOccurrences.sortedByDescending { it.occurrence.plannedFor },'
mutate 'rebuild a ResolvedScheduleSource' '            sources = input.sources,' \
    '            sources = input.sources.mapValues { (_, source) ->
                com.monkfitness.app.domain.program.target.ResolvedScheduleSource(
                    sourceRuleId = source.sourceRuleId,
                    occurrences = emptyList()
                )
            },'
mutate 'filter the caller-owned pauses' '            pauses = input.pauses,' \
    '            pauses = input.pauses.filter { pause -> !pause.covers(input.asOf) },'
mutate 'choose the device date instead of the caller asOf' '            asOf = input.asOf,' \
    '            asOf = java.time.LocalDate.now(),'
mutate 'widen the window to today' '            window = input.window,' \
    '            window = TargetScheduleWindow(
                from = minOf(input.window.from, input.asOf),
                through = maxOf(input.window.through, input.asOf)
            ),'
mutate 'call the planner' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        com.monkfitness.app.domain.program.target.TargetPlanner.plan(
            schedules = targetSchedules(input.scheduleDefinitions),
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources
        )'
mutate 'call the temporal policy' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        com.monkfitness.app.domain.program.target.TargetSchedulePolicy.decide(
            targetPlan = com.monkfitness.app.domain.program.target.TargetPlan(
                planned = emptyList(),
                reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                    preserved = emptyList(),
                    superseded = emptyList(),
                    added = emptyList()
                )
            ),
            existing = input.existingOccurrences,
            asOf = input.asOf,
            pauses = input.pauses
        )'
mutate 'call the orchestrator' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        TargetScheduleOrchestrator::class.java.name'
mutate 'construct a fake ExistingOccurrence from a slot' "$REQUEST_BLOCK" \
    '        val rebuilt = input.existingOccurrences + com.monkfitness.app.domain.program.ExistingOccurrence(
            occurrence = com.monkfitness.app.domain.program.PlannedOccurrence(
                occurrenceKey = "legacy:" + input.programDayBindings.first().programDayId.value,
                plannedFor = input.asOf,
                components = listOf(
                    com.monkfitness.app.domain.program.OccurrenceComponent(
                        ruleId = "legacy",
                        workoutId = input.programDayBindings.first().programDayId.value
                    )
                )
            ),
            execution = com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED,
            actuals = emptyList()
        )
        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = rebuilt,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )'
mutate 'inject a repository' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        val directRepository: com.monkfitness.app.data.repository.ProgramScheduleRepository? = null
        directRepository?.slotsOfProgram(input.programId)'
mutate 'inject a Clock' "$ADAPT_SIGNATURE" \
    '    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        val directClock: java.time.Clock? = java.time.Clock.systemDefaultZone()
        directClock?.millis()'
mutate 'inject an IdGenerator' 'class TargetScheduleInputAdapter {' \
    'class TargetScheduleInputAdapter(
    private val directGenerator: com.monkfitness.app.di.IdGenerator? = null
) {'
mutate 'deduplicate definitions instead of refusing them' \
    '        return definitions.map { definition -> definition.toTargetSchedule() }' \
    '        return definitions
            .distinctBy { definition -> definition.ruleId }
            .map { definition -> definition.toTargetSchedule() }'
mutate 'sort the definitions' '        return definitions.map { definition -> definition.toTargetSchedule() }' \
    '        return definitions.sortedBy { definition -> definition.ruleId }
            .map { definition -> definition.toTargetSchedule() }'
mutate 'drop a refused definition instead of failing' \
    '            if (definition.ruleId.isBlank()) {
                throw TargetScheduleInputException.BlankTargetRuleIdentity(index)
            }' \
    '            if (definition.ruleId.isBlank()) {
                return@forEachIndexed
            }'

restore
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo 'source restored byte-identically'
else
    echo 'MISSED byte-identical restoration'
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
fi

printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL"
[ "$FAIL" -eq 0 ]
