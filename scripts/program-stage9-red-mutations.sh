#!/usr/bin/env bash
# Stage 9 RED mutations: every deliberate break must be caught and materializer restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetSlotMaterializer.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage9-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 9 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetSlotMaterializer.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

restore() { cp "$WORK/TargetSlotMaterializer.kt.orig" "$SOURCE"; }

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetSlotMaterializerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSlotMaterializerArchitectureTest' \
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

mutate 'wrong slotId' \
    'slotId = input.slotId' \
    'slotId = input.presentation.occurrence.occurrenceKey.let(::SlotId)'
mutate 'wrong ProgramId' \
    'programId = input.programId' \
    'programId = input.revisionId.let(::ProgramId)'
mutate 'wrong RevisionId' \
    'revisionId = input.revisionId' \
    'revisionId = input.programId.let(::RevisionId)'
mutate 'wrong ProgramDayId' \
    'programDayId = input.presentation.programDayId' \
    'programDayId = ProgramDayId(occurrence.occurrenceKey)'
mutate 'wrong plannedFor' \
    'plannedFor = occurrence.plannedFor' \
    'plannedFor = occurrence.plannedFor.plusDays(1)'
mutate 'target key rewritten' \
    'targetOccurrenceKey = occurrence.occurrenceKey' \
    'targetOccurrenceKey = occurrence.occurrenceKey.trim()'
mutate 'target key derived from date' \
    'targetOccurrenceKey = occurrence.occurrenceKey' \
    'targetOccurrenceKey = occurrence.plannedFor.toString()'
mutate 'target key derived from ProgramDayId' \
    'targetOccurrenceKey = occurrence.occurrenceKey' \
    'targetOccurrenceKey = input.presentation.programDayId.value'
mutate 'status changed away from PLANNED' \
    'status = SlotStatus.PLANNED' \
    'status = SlotStatus.MISSED'
mutate 'attempt injected' \
    'attempts = emptyList()' \
    'attempts = listOf(com.monkfitness.app.domain.common.SessionId("invented"))'
mutate 'completedAt injected' \
    'completedAt = null' \
    'completedAt = java.time.Instant.EPOCH'
mutate 'UUID or random introduced' \
    'object TargetSlotMaterializer {' \
    'object TargetSlotMaterializer { private val random = java.util.UUID.randomUUID()'
mutate 'scheduler reference introduced' \
    'object TargetSlotMaterializer {' \
    'object TargetSlotMaterializer { private val scheduler = ProgramScheduler.hashCode()'
mutate 'mutable global state introduced' \
    'object TargetSlotMaterializer {' \
    'object TargetSlotMaterializer { private var cache: List<WorkoutSlot> = emptyList()'

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
