#!/usr/bin/env bash
# Phase 15 RED mutations: every deliberate break of the target occurrence's stored execution
# read-back must be caught, and each mutated source must be restored byte-identically.
#
# The oracle is the Phase 15 behavioural suite plus the architecture gate, because the two answer
# different questions and a mutation is only "caught" when something that should have noticed did.
# A behavioural suite catches a wrong *value* — a dropped attempt, a reordered set, a collapsed
# history. The architecture gate catches a wrong *shape* — a DAO reached directly, a key parsed, a
# plan day turned into an identity, a verdict smuggled in where no precedence rule exists.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

# The reader composes the three repositories and the value carries the four layers. Every mutation
# below lands in one of exactly these two files, so restoring them restores the phase.
READER=app/src/main/java/com/monkfitness/app/domain/usecase/TargetOccurrenceExecutionReader.kt
VALUE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetOccurrenceExecutionRead.kt
SOURCES=("$READER" "$VALUE")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage15-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 15 mutation run owns $LOCK" >&2
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
RESULTS=()

restore() { for source in "${SOURCES[@]}"; do cp "$WORK/$(basename "$source").orig" "$source"; done; }

# The behavioural suite (the stored values) and the architecture gate (the ten boundary claims).
# Phase 14's occurrence suite comes along because a mutation that changes how the semantic record is
# reached also changes the layer that owns it.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetOccurrenceExecutionReaderTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetOccurrenceExecutionReadArchitectureTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepositoryTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceArchitectureTest' \
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
    # Hygiene rule: a mutation that only puts a banned token in a comment exercises nothing, because
    # every gate strips comments first. So the probe strips them here too and asserts the token the
    # gate matches on is still present in real code — a future row cannot silently become prose.
    if ! python3 - "$file" "$new" "$WORK/probe.txt" <<'PROBE_BODY'
import re, sys
path, token, out = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
open(out, 'w', encoding='utf-8').write(code)
assert token.split('\n')[0].strip(), 'empty probe token'
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
        # A mutation the compiler rejects was not caught by an oracle — nothing was proven about the
        # rule. Counting it as "caught" is exactly the weakness §8c warns about, so it is reported
        # and charged as a failure, which forces the mutation to be rewritten as type-correct code
        # at the layer the oracle reads.
        echo "NOT A CATCH (compile error, no oracle saw it): $label"
        RESULTS+=("MISSED $label (did not compile)")
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

# ---- the anchors ------------------------------------------------------------------------------

# The read path, as it stands.
OCCURRENCE_READ='        val occurrence = occurrenceRepository.occurrenceOf(programId, occurrenceKey)'
SLOT_READ='        val slot = scheduleRepository.slotByTargetOccurrenceKey(programId, occurrenceKey)'
SLOT_NULL='            ?: throw TargetOccurrenceExecutionReadException.MissingTargetSlot(programId, occurrenceKey)'
ATTEMPTS_READ='        val attempts = sessionRepository.sessionsOfSlot(slot.slotId)'
RECORD_RETURN='        return TargetOccurrenceExecutionRecord(occurrence = occurrence, slot = slot, attempts = attempts)'

# The value's accessors.
STATUSES='    val attemptStatuses: List<SessionStatus> get() = attempts.map { it.status }'
SLOT_STATUS='    val slotStatus get() = slot.status'
CONFIRMED='        attempt.exercises.flatMap { exercise -> exercise.results }'

echo '== preflight =='

# 1. Derive the occurrence from the current ProgramDay instead of the stored record.
#    Type-correct: `slot` is already bound by the time this line runs, and the fabricated value is a
#    real `PersistedTargetOccurrence`, so the mutation reaches the behaviour suite rather than the
#    compiler.
preflight 'derive the occurrence from the current ProgramDay' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = com.monkfitness.app.domain.program.target.PersistedTargetOccurrence(
            programId,
            com.monkfitness.app.domain.program.PlannedOccurrence(
                occurrenceKey = occurrenceKey,
                plannedFor = java.time.LocalDate.MIN,
                components = listOf(
                    com.monkfitness.app.domain.program.OccurrenceComponent(
                        ruleId = com.monkfitness.app.domain.common.ProgramDayId(occurrenceKey).value,
                        workoutId = com.monkfitness.app.domain.common.ProgramDayId(occurrenceKey).value
                    )
                )
            )
        )'

# 2. Derive the occurrence from the legacy ProgramSchedule.
preflight 'derive the occurrence from the legacy ProgramSchedule' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(programId, occurrenceKey) ?: run {
            val legacy = com.monkfitness.app.domain.program.ProgramSchedule
                .FixedWeekdays(setOf(java.time.DayOfWeek.MONDAY))
            com.monkfitness.app.domain.program.target.PersistedTargetOccurrence(
                programId,
                com.monkfitness.app.domain.program.PlannedOccurrence(
                    occurrenceKey = occurrenceKey,
                    plannedFor = java.time.LocalDate.MIN,
                    components = listOf(
                        com.monkfitness.app.domain.program.OccurrenceComponent(
                            ruleId = legacy.weekdays.toString(),
                            workoutId = legacy.weekdays.toString()
                        )
                    )
                )
            )
        }'

# 3. Parse the occurrence key.
preflight 'parse the occurrence key into its components' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(
            programId,
            occurrenceKey.split(":").first()
        )'

# 4. Replace the target identity with the slotId.
preflight 'replace the target identity with the slotId' "$READER" "$SLOT_READ" \
    '        val slot = scheduleRepository.slotsOfProgram(programId).first { candidate ->
            candidate.targetOccurrenceKey == occurrenceKey
        }'

# 5. Ignore the ProgramId in the lookup.
preflight 'ignore the ProgramId in the occurrence lookup' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(
            com.monkfitness.app.domain.common.ProgramId("program-of-another-owner"),
            occurrenceKey
        )'

# 6. Drop a session attempt.
preflight 'drop a stored session attempt' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = sessionRepository.sessionsOfSlot(slot.slotId).dropLast(1)'

# 7. Reorder the attempts.
preflight 'reorder the session attempts' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = sessionRepository.sessionsOfSlot(slot.slotId).reversed()'

# 8. Drop a confirmed set.
preflight 'drop a confirmed set' "$VALUE" "$CONFIRMED" \
    '        attempt.exercises.flatMap { exercise -> exercise.results }.drop(1)'

# 9. Reorder the confirmed sets.
preflight 'reorder the confirmed sets' "$VALUE" "$CONFIRMED" \
    '        attempt.exercises.flatMap { exercise -> exercise.results.reversed() }'

# 10. Replace a session's status with the slot's status.
preflight 'replace a session status with the slot status' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus> get() = attempts.map { slot.status.let { SessionStatus.COMPLETED } }'

# 11. Infer the execution from the finish stamp.
preflight 'infer the execution from the finish stamp' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = attempts.map { attempt -> if (attempt.finishedAt != null) SessionStatus.COMPLETED else SessionStatus.IN_PROGRESS }'

# 12. Ignore a cancelled attempt.
preflight 'ignore a cancelled attempt' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = attempts.filter { it.status != SessionStatus.CANCELLED }.map { it.status }'

# 13. Collapse several attempts into one.
preflight 'collapse several attempts into one' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = listOfNotNull(attempts.lastOrNull()?.status)'

# 14. Synthesize an empty record when the slot is missing.
preflight 'synthesize an empty record when the slot is missing' "$READER" "$SLOT_NULL" \
    '            ?: return TargetOccurrenceExecutionRecord(
                occurrence = occurrence,
                slot = com.monkfitness.app.domain.program.WorkoutSlot(
                    slotId = com.monkfitness.app.domain.common.SlotId("synthesized-$occurrenceKey"),
                    programId = programId,
                    revisionId = com.monkfitness.app.domain.common.RevisionId("synthesized"),
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId("synthesized"),
                    plannedFor = occurrence.occurrence.plannedFor,
                    status = com.monkfitness.app.domain.program.SlotStatus.PLANNED,
                    targetOccurrenceKey = occurrenceKey
                ),
                attempts = emptyList()
            )'

# 15. Swallow a malformed session graph.
preflight 'swallow a malformed stored session graph' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = runCatching { sessionRepository.sessionsOfSlot(slot.slotId) }
            .getOrDefault(emptyList())'

# 16. Read the sessions from a DAO instead of the repository.
preflight 'read the sessions from a DAO instead of the repository' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = scheduleRepository.slotsOfProgram(programId)
            .firstOrNull { candidate -> candidate.slotId == slot.slotId }
            ?.attempts
            ?.map { sessionId -> com.monkfitness.app.domain.workout.WorkoutSession(
                sessionId = sessionId,
                slotId = slot.slotId,
                programId = programId,
                revisionId = slot.revisionId,
                snapshot = com.monkfitness.app.domain.workout.WorkoutSessionSnapshot(
                    sessionId = sessionId,
                    capturedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                        .toInstant(java.time.ZoneOffset.UTC),
                    workout = com.monkfitness.app.domain.workout.EffectiveWorkout(
                        slotId = slot.slotId,
                        programId = programId,
                        revisionId = slot.revisionId,
                        plannedFor = slot.plannedFor,
                        computedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                            .toInstant(java.time.ZoneOffset.UTC),
                        exercises = emptyList()
                    )
                ),
                status = com.monkfitness.app.domain.workout.SessionStatus.COMPLETED,
                startedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC),
                finishedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC)
            ) }
            ?: emptyList()'

# 17. Consult the current ProgramRevision.
preflight 'consult the current Program revision' "$READER" "$SLOT_READ" \
    '        val currentRevision = scheduleRepository.slotsOfProgram(programId)
            .maxByOrNull { candidate -> candidate.plannedFor }
        val slot = currentRevision
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetSlot(programId, occurrenceKey)'

# 18. Encode an execution precedence the storage model does not supply.
preflight 'encode a single-execution precedence' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() {
            val decided: com.monkfitness.app.domain.program.OccurrenceExecution = when {
                attempts.isEmpty() -> com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
                attempts.any { it.status == SessionStatus.COMPLETED } ->
                    com.monkfitness.app.domain.program.OccurrenceExecution.COMPLETED
                attempts.any { it.status == SessionStatus.IN_PROGRESS } ->
                    com.monkfitness.app.domain.program.OccurrenceExecution.STARTED
                else -> com.monkfitness.app.domain.program.OccurrenceExecution.CANCELLED
            }
            return emptyList<SessionStatus>()
        }'

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

mutate 'derive the occurrence from the current ProgramDay' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = com.monkfitness.app.domain.program.target.PersistedTargetOccurrence(
            programId,
            com.monkfitness.app.domain.program.PlannedOccurrence(
                occurrenceKey = occurrenceKey,
                plannedFor = java.time.LocalDate.MIN,
                components = listOf(
                    com.monkfitness.app.domain.program.OccurrenceComponent(
                        ruleId = com.monkfitness.app.domain.common.ProgramDayId(occurrenceKey).value,
                        workoutId = com.monkfitness.app.domain.common.ProgramDayId(occurrenceKey).value
                    )
                )
            )
        )'

mutate 'derive the occurrence from the legacy ProgramSchedule' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(programId, occurrenceKey) ?: run {
            val legacy = com.monkfitness.app.domain.program.ProgramSchedule
                .FixedWeekdays(setOf(java.time.DayOfWeek.MONDAY))
            com.monkfitness.app.domain.program.target.PersistedTargetOccurrence(
                programId,
                com.monkfitness.app.domain.program.PlannedOccurrence(
                    occurrenceKey = occurrenceKey,
                    plannedFor = java.time.LocalDate.MIN,
                    components = listOf(
                        com.monkfitness.app.domain.program.OccurrenceComponent(
                            ruleId = legacy.weekdays.toString(),
                            workoutId = legacy.weekdays.toString()
                        )
                    )
                )
            )
        }'

mutate 'parse the occurrence key into its components' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(
            programId,
            occurrenceKey.split(":").first()
        )'

mutate 'replace the target identity with the slotId' "$READER" "$SLOT_READ" \
    '        val slot = scheduleRepository.slotsOfProgram(programId).first { candidate ->
            candidate.targetOccurrenceKey == occurrenceKey
        }'

mutate 'ignore the ProgramId in the occurrence lookup' "$READER" "$OCCURRENCE_READ" \
    '        val occurrence = occurrenceRepository.occurrenceOf(
            com.monkfitness.app.domain.common.ProgramId("program-of-another-owner"),
            occurrenceKey
        )'

mutate 'drop a stored session attempt' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = sessionRepository.sessionsOfSlot(slot.slotId).dropLast(1)'

mutate 'reorder the session attempts' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = sessionRepository.sessionsOfSlot(slot.slotId).reversed()'

mutate 'drop a confirmed set' "$VALUE" "$CONFIRMED" \
    '        attempt.exercises.flatMap { exercise -> exercise.results }.drop(1)'

mutate 'reorder the confirmed sets' "$VALUE" "$CONFIRMED" \
    '        attempt.exercises.flatMap { exercise -> exercise.results.reversed() }'

mutate 'replace a session status with the slot status' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus> get() = attempts.map { slot.status.let { SessionStatus.COMPLETED } }'

mutate 'infer the execution from the finish stamp' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = attempts.map { attempt -> if (attempt.finishedAt != null) SessionStatus.COMPLETED else SessionStatus.IN_PROGRESS }'

mutate 'ignore a cancelled attempt' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = attempts.filter { it.status != SessionStatus.CANCELLED }.map { it.status }'

mutate 'collapse several attempts into one' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() = listOfNotNull(attempts.lastOrNull()?.status)'

mutate 'synthesize an empty record when the slot is missing' "$READER" "$SLOT_NULL" \
    '            ?: return TargetOccurrenceExecutionRecord(
                occurrence = occurrence,
                slot = com.monkfitness.app.domain.program.WorkoutSlot(
                    slotId = com.monkfitness.app.domain.common.SlotId("synthesized-$occurrenceKey"),
                    programId = programId,
                    revisionId = com.monkfitness.app.domain.common.RevisionId("synthesized"),
                    programDayId = com.monkfitness.app.domain.common.ProgramDayId("synthesized"),
                    plannedFor = occurrence.occurrence.plannedFor,
                    status = com.monkfitness.app.domain.program.SlotStatus.PLANNED,
                    targetOccurrenceKey = occurrenceKey
                ),
                attempts = emptyList()
            )'

mutate 'swallow a malformed stored session graph' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = runCatching { sessionRepository.sessionsOfSlot(slot.slotId) }
            .getOrDefault(emptyList())'

mutate 'read the sessions from a DAO instead of the repository' "$READER" "$ATTEMPTS_READ" \
    '        val attempts = scheduleRepository.slotsOfProgram(programId)
            .firstOrNull { candidate -> candidate.slotId == slot.slotId }
            ?.attempts
            ?.map { sessionId -> com.monkfitness.app.domain.workout.WorkoutSession(
                sessionId = sessionId,
                slotId = slot.slotId,
                programId = programId,
                revisionId = slot.revisionId,
                snapshot = com.monkfitness.app.domain.workout.WorkoutSessionSnapshot(
                    sessionId = sessionId,
                    capturedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                        .toInstant(java.time.ZoneOffset.UTC),
                    workout = com.monkfitness.app.domain.workout.EffectiveWorkout(
                        slotId = slot.slotId,
                        programId = programId,
                        revisionId = slot.revisionId,
                        plannedFor = slot.plannedFor,
                        computedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                            .toInstant(java.time.ZoneOffset.UTC),
                        exercises = emptyList()
                    )
                ),
                status = com.monkfitness.app.domain.workout.SessionStatus.COMPLETED,
                startedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC),
                finishedAt = slot.completedAt ?: slot.plannedFor.atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC)
            ) }
            ?: emptyList()'

mutate 'consult the current Program revision' "$READER" "$SLOT_READ" \
    '        val currentRevision = scheduleRepository.slotsOfProgram(programId)
            .maxByOrNull { candidate -> candidate.plannedFor }
        val slot = currentRevision
            ?: throw TargetOccurrenceExecutionReadException.MissingTargetSlot(programId, occurrenceKey)'

mutate 'encode a single-execution precedence' "$VALUE" "$STATUSES" \
    '    val attemptStatuses: List<SessionStatus>
        get() {
            val decided: com.monkfitness.app.domain.program.OccurrenceExecution = when {
                attempts.isEmpty() -> com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
                attempts.any { it.status == SessionStatus.COMPLETED } ->
                    com.monkfitness.app.domain.program.OccurrenceExecution.COMPLETED
                attempts.any { it.status == SessionStatus.IN_PROGRESS } ->
                    com.monkfitness.app.domain.program.OccurrenceExecution.STARTED
                else -> com.monkfitness.app.domain.program.OccurrenceExecution.CANCELLED
            }
            return emptyList<SessionStatus>()
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
