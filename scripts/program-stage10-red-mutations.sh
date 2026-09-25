#!/usr/bin/env bash
# Phase 10 RED mutations: every deliberate break must be caught and the persister restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleSlotPersister.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage10-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 10 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetScheduleSlotPersister.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

restore() { cp "$WORK/TargetScheduleSlotPersister.kt.orig" "$SOURCE"; }

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleSlotPersisterTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleSlotPersisterArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

mutate() {
    local label="$1" old="$2" new="$3"
    restore
    python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + old)
updated = text.replace(old, new, 1)
if updated == text:
    raise SystemExit('MUTATION DID NOT APPLY: ' + old)
open(path, 'w', encoding='utf-8').write(updated)
PY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label did not apply" >&2
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

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    exit 1
fi

mutate 'lookup by plannedFor instead of target key' \
    'val existing = scheduleRepository.slotByTargetOccurrenceKey(input.programId, key)' \
    'val existing = scheduleRepository.slotsOfProgram(input.programId).firstOrNull { it.plannedFor == presentation.occurrence.plannedFor }'
mutate 'generate a new id instead of retaining existing storage identity' \
    'if (existing == null) {' \
    'if (true) {'
mutate 'overwrite existing execution state' \
    'retained += existing' \
    'retained += existing.copy(status = com.monkfitness.app.domain.program.SlotStatus.PLANNED, attempts = emptyList(), completedAt = null)'
mutate 'target key rewritten' \
    'presentation = presentation' \
    'presentation = presentation.copy(occurrence = presentation.occurrence.copy(occurrenceKey = "rewritten"))'
mutate 'date-derived target identity' \
    'presentation = presentation' \
    'presentation = presentation.copy(occurrence = presentation.occurrence.copy(occurrenceKey = presentation.occurrence.plannedFor.toString()))'
mutate 'ProgramDay-derived target identity' \
    'presentation = presentation' \
    'presentation = presentation.copy(occurrence = presentation.occurrence.copy(occurrenceKey = presentation.programDayId.value))'
mutate 'duplicate insertion on repeated run' \
    'scheduleRepository.addSlots(created)' \
    'scheduleRepository.addSlots(created + created)'
mutate 'same-date target collapse' \
    'slotByTargetOccurrenceKey(input.programId, key)' \
    'slotsOfProgram(input.programId).firstOrNull { it.plannedFor == presentation.occurrence.plannedFor }'
mutate 'direct Room or DAO access' \
    'import com.monkfitness.app.data.repository.ProgramScheduleRepository' \
    'import androidx.room.Dao
import com.monkfitness.app.data.repository.ProgramScheduleRepository'
mutate 'repeat mapping instead of Stage 9 materializer' \
    'TargetSlotMaterializer.materialize(' \
    'WorkoutSlot('
mutate 'silent conflict acceptance' \
    'requireSemanticMatch(input, presentation, existing)' \
    'Unit'
mutate 'mutate existing slot in place' \
    'retained += existing' \
    'retained += existing.copy(revisionId = input.revisionId)'

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
