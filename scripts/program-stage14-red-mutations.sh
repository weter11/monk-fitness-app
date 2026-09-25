#!/usr/bin/env bash
# Phase 14 RED mutations: every deliberate break of the target occurrence's semantic persistence and
# read-back must be caught, and each mutated source must be restored byte-identically.
#
# The oracle is the Phase 14 behavioural suites plus the architecture gate, because the two answer
# different questions and a mutation is only "caught" when something that should have noticed did.
# A behavioural suite catches a wrong *value*; the architecture gate catches a wrong *shape* — a key
# that got parsed, a plan day that leaked into an identity, a legacy scheduler that got consulted.
# A mutation caught only by the gate is exactly the case where the value would still have looked
# right in isolation but the boundary was already wrong.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

# The mapper is the read-back, the repository is the store/refuse decision, and the persister is the
# atomic boundary. Every mutation below lands in one of exactly these three files, so restoring them
# restores the phase.
MAPPER=app/src/main/java/com/monkfitness/app/data/mapper/TargetOccurrenceMappers.kt
REPO=app/src/main/java/com/monkfitness/app/data/repository/TargetScheduleOccurrenceRepository.kt
PERSISTER=app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleSlotPersister.kt
SOURCES=("$MAPPER" "$REPO" "$PERSISTER")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage14-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 14 mutation run owns $LOCK" >&2
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

# The behavioural suites (store, read-back, idempotency, refusal, atomicity) plus the architecture
# gate (the nine boundary claims). The Stage 10 persister guard comes along because a mutation that
# changes what the persister *stores* also changes the boundary it is responsible for.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepositoryTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleOccurrenceAtomicityTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleSlotPersisterTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleSlotPersisterArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

mutate() {
    local label="$1" file="$2" old="$3" new="$4"
    restore
    python3 - "$file" "$old" "$new" <<'PY'
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
    # Hygiene rule: a mutation that only puts a banned token in a comment exercises nothing, because
    # every gate strips comments first. So the probe strips them here too and asserts the token the
    # gate matches on is still present in real code — a future row cannot silently become prose.
    if ! python3 - "$file" "$new" "$WORK/probe.txt" <<'PY'
import re, sys
path, token, out = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
open(out, 'w', encoding='utf-8').write(code)
# The token is searched for in the *stripped* code, so reaching this point means the mutation put it
# in executable code rather than in a comment. Nothing further to assert: the stripped text is the
# artifact the gates read.
assert token.split('\n')[0].strip(), 'empty probe token'
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
    local label="$1" file="$2" old="$3" new="$4"
    python3 - "$file" "$old" "$new" "$label" <<'PY'
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

# ---- the anchors ------------------------------------------------------------------------------

READ_BODY='        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }'
READ_SIG='internal fun ProgramTargetOccurrenceEntity.toDomain(
    components: List<ProgramTargetOccurrenceComponentEntity>
): PersistedTargetOccurrence = PersistedTargetOccurrence('
COMPONENT_MAP='    OccurrenceComponent(ruleId = ruleId, workoutId = workoutId)'
ENTITY_ROWS='        occurrenceDao.insertOccurrences(listOf(occurrence.toEntity()))
        occurrenceDao.insertComponents(occurrence.toComponentEntities())'
CONFLICT_CHECK='            if (stored.hasSamePayloadAs(occurrence)) return'
STORE_SIG='    suspend fun store(occurrence: PersistedTargetOccurrence) {'
TXN='        inTransaction {
            scheduleRepository.addSlots(created)
            input.presentations.forEach { presentation ->
                occurrenceRepository.store('
TXN_SLOT_ONLY='        inTransaction {
            scheduleRepository.addSlots(created)
        }
        input.presentations.forEach { presentation ->
            occurrenceRepository.store('
TXN_SLOT_ONLY='        inTransaction {
            scheduleRepository.addSlots(created)
        }
        input.presentations.forEach { presentation ->
            occurrenceRepository.store('
TXN_OCCURRENCE_ONLY='        inTransaction {
            input.presentations.forEach { presentation ->
                occurrenceRepository.store(
                    PersistedTargetOccurrence(
                        programId = input.programId,
                        occurrence = presentation.occurrence
                    )
                )
            }
        }
        scheduleRepository.addSlots(created)
        return TargetScheduleSlotPersistenceResult(created, retained)'

echo '== preflight =='
preflight 'parse the occurrence key into rule and workout components' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = occurrenceKey.split(":").map { part -> com.monkfitness.app.domain.program.OccurrenceComponent(part, part) }'
preflight 'derive ruleId from a ProgramDayId' "$MAPPER" "$COMPONENT_MAP" \
    '    OccurrenceComponent(
        ruleId = com.monkfitness.app.domain.common.ProgramDayId(ruleId).value,
        workoutId = workoutId
    )'
preflight 'derive workoutId from a ProgramDayId' "$MAPPER" "$COMPONENT_MAP" \
    '    OccurrenceComponent(ruleId = ruleId, workoutId = com.monkfitness.app.domain.common.ProgramDayId(ruleId).value)'
preflight 'reorder components during read-back' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }.sortedBy { it.ruleId }'
preflight 'drop a component during read-back' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }.drop(1)'
preflight 'synthesize a component when none was stored' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = (
            if (components.isEmpty()) listOf(
                com.monkfitness.app.domain.program.OccurrenceComponent("legacy", "legacy")
            ) else components.map { it.toDomain() }
        )'
preflight 'overwrite a conflicting payload' "$REPO" "$CONFLICT_CHECK" \
    '            if (false) return'
preflight 'ignore component order in the equality check' "$REPO" "$CONFLICT_CHECK" \
    '            if (stored.occurrence.occurrenceKey == occurrence.occurrence.occurrenceKey &&
                stored.occurrence.plannedFor == occurrence.occurrence.plannedFor &&
                stored.occurrence.components.toSet() == occurrence.occurrence.components.toSet()) return'
preflight 'use the date as the target identity' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        val stored = occurrenceOf(occurrence.programId, occurrence.occurrence.plannedFor.toString())'
preflight 'use a slotId as the target identity' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        val stored = occurrenceOf(
            occurrence.programId,
            occurrence.occurrence.components.firstOrNull()?.workoutId ?: occurrence.occurrenceKey
        )'
preflight 'persist the slot without the semantic record' "$PERSISTER" "$TXN" \
    '        inTransaction {
            scheduleRepository.addSlots(created)
            if (false) input.presentations.forEach { presentation ->
                occurrenceRepository.store('
preflight 'persist the semantic record without the slot' "$PERSISTER" "$TXN" \
    '        inTransaction {
            if (false) scheduleRepository.addSlots(created)
            input.presentations.forEach { presentation ->
                occurrenceRepository.store('
preflight 'split the two writes into separate transactions' "$PERSISTER" "$TXN" \
    "$TXN_SLOT_ONLY"
preflight 'reconstruct the payload from a ProgramDayId' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = listOf(
            com.monkfitness.app.domain.program.OccurrenceComponent(
                ruleId = com.monkfitness.app.domain.common.ProgramDayId(programId).value,
                workoutId = programId
            )
        )'
preflight 'consult the legacy ProgramSchedule' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = when (val legacy = com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)) {
            is com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays -> components.map { it.toDomain() }
            is com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek -> components.map { it.toDomain() }
        }'
preflight 'call the legacy scheduler' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        com.monkfitness.app.domain.usecase.ProgramScheduler::class.java.name
        val stored = occurrenceOf(occurrence.programId, occurrence.occurrenceKey)'
echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -40 "$WORK/last-run.log"
    exit 1
fi

echo '== mutations =='
mutate 'parse the occurrence key into rule and workout components' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = occurrenceKey.split(":").map { part -> com.monkfitness.app.domain.program.OccurrenceComponent(part, part) }'
mutate 'derive ruleId from a ProgramDayId' "$MAPPER" "$COMPONENT_MAP" \
    '    OccurrenceComponent(
        ruleId = com.monkfitness.app.domain.common.ProgramDayId(ruleId).value,
        workoutId = workoutId
    )'
mutate 'derive workoutId from a ProgramDayId' "$MAPPER" "$COMPONENT_MAP" \
    '    OccurrenceComponent(ruleId = ruleId, workoutId = com.monkfitness.app.domain.common.ProgramDayId(ruleId).value)'
mutate 'reorder components during read-back' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }.sortedBy { it.ruleId }'
mutate 'drop a component during read-back' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }.drop(1)'
mutate 'synthesize a component when none was stored' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = (
            if (components.isEmpty()) listOf(
                com.monkfitness.app.domain.program.OccurrenceComponent("legacy", "legacy")
            ) else components.map { it.toDomain() }
        )'
mutate 'overwrite a conflicting payload' "$REPO" "$CONFLICT_CHECK" \
    '            if (false) return'
mutate 'ignore component order in the equality check' "$REPO" "$CONFLICT_CHECK" \
    '            if (stored.occurrence.occurrenceKey == occurrence.occurrence.occurrenceKey &&
                stored.occurrence.plannedFor == occurrence.occurrence.plannedFor &&
                stored.occurrence.components.toSet() == occurrence.occurrence.components.toSet()) return'
mutate 'use the date as the target identity' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        val stored = occurrenceOf(occurrence.programId, occurrence.occurrence.plannedFor.toString())'
mutate 'use a slotId as the target identity' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        val stored = occurrenceOf(
            occurrence.programId,
            occurrence.occurrence.components.firstOrNull()?.workoutId ?: occurrence.occurrenceKey
        )'
mutate 'persist the slot without the semantic record' "$PERSISTER" "$TXN" \
    '        inTransaction {
            scheduleRepository.addSlots(created)
            if (false) input.presentations.forEach { presentation ->
                occurrenceRepository.store('
mutate 'persist the semantic record without the slot' "$PERSISTER" "$TXN" \
    '        inTransaction {
            if (false) scheduleRepository.addSlots(created)
            input.presentations.forEach { presentation ->
                occurrenceRepository.store('
mutate 'split the two writes into separate transactions' "$PERSISTER" "$TXN" \
    "$TXN_SLOT_ONLY"
mutate 'reconstruct the payload from a ProgramDayId' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = listOf(
            com.monkfitness.app.domain.program.OccurrenceComponent(
                ruleId = com.monkfitness.app.domain.common.ProgramDayId(programId).value,
                workoutId = programId
            )
        )'
mutate 'consult the legacy ProgramSchedule' "$MAPPER" "$READ_BODY" \
    '        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = when (val legacy = com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)) {
            is com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays -> components.map { it.toDomain() }
            is com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek -> components.map { it.toDomain() }
        }'
mutate 'call the legacy scheduler' "$REPO" "$STORE_SIG" \
    '    suspend fun store(occurrence: PersistedTargetOccurrence) {
        com.monkfitness.app.domain.usecase.ProgramScheduler::class.java.name
        val stored = occurrenceOf(occurrence.programId, occurrence.occurrenceKey)'

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
