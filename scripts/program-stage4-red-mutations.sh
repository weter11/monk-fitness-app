#!/usr/bin/env bash
# Stage 4 RED mutations: every deliberate break must be caught by the focused pure suites.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetOccurrenceReconciler.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage4-red"
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetOccurrenceReconciler.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconcilerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliationArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceCompositionArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

restore() { cp "$WORK/TargetOccurrenceReconciler.kt.orig" "$SOURCE"; }

mutate() {
    local label="$1" old="$2" new="$3"
    restore
    python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + old)
open(path, 'w', encoding='utf-8').write(text.replace(old, new))
PY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label did not apply"
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
    echo 'control RED'
    tail -30 "$WORK/last-run.log"
    exit 1
fi

mutate 'planned existing occurrences incorrectly preserved when removed' \
    'it.execution == OccurrenceExecution.PLANNED &&' \
    'it.execution != OccurrenceExecution.PLANNED &&'
mutate 'started occurrences superseded' \
    'it.execution != OccurrenceExecution.PLANNED' \
    'it.execution != OccurrenceExecution.STARTED'
mutate 'completed occurrences superseded' \
    'it.execution != OccurrenceExecution.PLANNED' \
    'it.execution != OccurrenceExecution.COMPLETED'
mutate 'cancelled occurrences superseded' \
    'it.execution != OccurrenceExecution.PLANNED' \
    'it.execution != OccurrenceExecution.CANCELLED'
mutate 'new replacement occurrences ignored' \
    '.filter { it.occurrenceKey !in existingByKey }' \
    '.filter { false }'
mutate 'same-key replacement creates a duplicate' \
    '.filter { it.occurrenceKey !in existingByKey }' \
    '.filter { true }'
mutate 'old and new identities merged by date' \
    'it.occurrence.occurrenceKey !in replacementKeys' \
    'it.occurrence.plannedFor !in replacement.map { planned -> planned.plannedFor }'
mutate 'existing input order affects output' \
    '.thenBy { it.occurrence.occurrenceKey })' \
    '.thenBy { it.occurrence.occurrenceKey }).reversed()'
mutate 'replacement input order affects output' \
    '.thenBy { it.occurrenceKey })' \
    '.thenBy { it.occurrenceKey }).reversed()'
mutate 'duplicate existing keys silently deduplicated' \
    'val existingByKey = indexExisting(existing)' \
    'val existingByKey = indexExisting(existing.distinctBy { it.occurrence.occurrenceKey })'
mutate 'duplicate replacement keys silently deduplicated' \
    'val replacementByKey = indexReplacement(replacement)' \
    'val replacementByKey = indexReplacement(replacement.distinctBy { it.occurrenceKey })'
mutate 'conflicting same-key payload silently accepted' \
    'require(existingOccurrence == null || existingOccurrence.occurrence == occurrence)' \
    'require(existingOccurrence == null || true)'
mutate 'historical actuals reconstructed' \
    'val preserved = existing' \
    'val preserved = existing.filter { it.execution != OccurrenceExecution.PLANNED }\n                .map { it.copy(actuals = emptyList()) }\n                .let { emptyList() }'
mutate 'composer/resolver is called' \
    'replacementByKey.forEach { (key, occurrence) ->' \
    'TargetScheduleResolver.hashCode()\n        replacementByKey.forEach { (key, occurrence) ->'
mutate 'clock is read' \
    'replacementByKey.forEach { (key, occurrence) ->' \
    'System.currentTimeMillis()\n        replacementByKey.forEach { (key, occurrence) ->'
mutate 'random UUID identity is introduced' \
    '.filter { it.occurrenceKey !in existingByKey }' \
    '.filter { it.occurrenceKey + java.util.UUID.randomUUID() !in existingByKey }'

restore
echo '== restoration =='
if md5sum -c "$WORK/before.md5"; then
    echo 'source restored byte-identically'
else
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
fi

printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL"
[ "$FAIL" -eq 0 ]
