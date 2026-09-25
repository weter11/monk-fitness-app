#!/usr/bin/env bash
# Stage 3 RED mutations: every deliberate break must be caught by the focused pure suites.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetOccurrenceComposer.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage3-red"
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetOccurrenceComposer.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceComposerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceCompositionArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

restore() { cp "$WORK/TargetOccurrenceComposer.kt.orig" "$SOURCE"; }

mutate() {
    local label="$1" old="$2" new="$3"
    restore
    python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + old)
open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
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

mutate 'selected rules left separate' \
    'val combinedComponents = resolvedOnDate
                .filter { it.ruleId in selectedIds }' \
    'val combinedComponents = resolvedOnDate
                .filter { false }'

mutate 'unselected rules combined' \
    '.filter { it.ruleId in selectedIds }
                .map { it.component() }' \
    '.filter { it.ruleId !in selectedIds }
                .map { it.component() }'

mutate 'different dates combined' \
    'val byDate = occurrences.groupBy { it.plannedDate }.toSortedMap()' \
    'val byDate = occurrences.groupBy { LocalDate.MIN }.toSortedMap()'

mutate 'composition depends on input order' \
    'val byDate = occurrences.groupBy { it.plannedDate }.toSortedMap()' \
    'val byDate = occurrences.groupBy { it.plannedDate }.toMap()'

mutate 'components depend on Set iteration order' \
    '.map { it.component() }
                .sortedBy { it.ruleId }' \
    '.map { it.component() }
                .shuffled()'

mutate 'combined key depends on input order' \
    'occurrenceKey = "combined:$date:" +
                            combinedComponents.joinToString("|") { component ->
                                "${component.ruleId.length}:${component.ruleId}"
                            },' \
    'occurrenceKey = "combined:$date:" +
                            resolvedOnDate.filter { it.ruleId in selectedIds }
                                .joinToString("|") { component -> "${component.ruleId.length}:${component.ruleId } }",'

mutate 'missing selected occurrence fabricated' \
    'val combined = if (combinedComponents.isEmpty()) {
                emptyList()
            } else {' \
    'val combined = if (combinedComponents.isEmpty()) {
                listOf(PlannedOccurrence("combined:$date:missing", date, listOf(OccurrenceComponent("missing", "Missing"))))
            } else {'

mutate 'duplicate occurrence silently deduplicated' \
    '): List<PlannedOccurrence> {
        validate(occurrences)
        val selectedIds = selection.combinedRuleIds
        val byDate = occurrences.groupBy { it.plannedDate }.toSortedMap()' \
    '): List<PlannedOccurrence> {
        val uniqueOccurrences = occurrences.distinctBy { it.ruleId to it.plannedDate }
        validate(uniqueOccurrences)
        val selectedIds = selection.combinedRuleIds
        val byDate = uniqueOccurrences.groupBy { it.plannedDate }.toSortedMap()'

mutate 'composer calls TargetScheduleResolver' \
    'val selectedIds = selection.combinedRuleIds' \
    'val selectedIds = selection.combinedRuleIds; TargetScheduleResolver.hashCode()'

mutate 'composer reads current time' \
    'val selectedIds = selection.combinedRuleIds' \
    'val selectedIds = selection.combinedRuleIds; System.currentTimeMillis()'

mutate 'composer uses random identity' \
    'occurrenceKey = "combined:$date:" +
                            combinedComponents.joinToString("|") { component ->
                                "${component.ruleId.length}:${component.ruleId}"
                            },' \
    'occurrenceKey = "combined:$date:" +
                            combinedComponents.joinToString("|") { component ->
                                "${component.ruleId.length}:${component.ruleId}" } +
                            ":" + java.util.UUID.randomUUID(),'

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
