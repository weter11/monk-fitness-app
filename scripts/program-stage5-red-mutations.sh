#!/usr/bin/env bash
# Stage 5 RED mutations: every deliberate break must be caught and the planner restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetPlanner.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage5-red"
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetPlanner.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceCompositionArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliationArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

restore() { cp "$WORK/TargetPlanner.kt.orig" "$SOURCE"; }

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

mutate 'resolver invocation' \
    'TargetScheduleResolver.resolve(schedules, window, sources)' \
    'TargetScheduleResolver.resolve(emptyList(), window, sources)'
mutate 'composer invocation' \
    'TargetOccurrenceComposer.compose(resolved, selection)' \
    'TargetOccurrenceComposer.compose(emptyList(), selection)'
mutate 'reconciler invocation' \
    'TargetOccurrenceReconciler.reconcile(existing, planned)' \
    'TargetOccurrenceReconciler.reconcile(existing, emptyList())'
mutate 'stage ordering' \
    'val planned = TargetOccurrenceComposer.compose(resolved, selection)' \
    $'val earlyReconciliation = TargetOccurrenceReconciler.reconcile(existing, emptyList())\n        val planned = TargetOccurrenceComposer.compose(resolved, selection)'
mutate 'existing-occurrence forwarding' \
    'TargetOccurrenceReconciler.reconcile(existing, planned)' \
    'TargetOccurrenceReconciler.reconcile(emptyList(), planned)'
mutate 'composition-selection forwarding' \
    'TargetOccurrenceComposer.compose(resolved, selection)' \
    'TargetOccurrenceComposer.compose(resolved, CompositionSelection())'
mutate 'source forwarding' \
    'TargetScheduleResolver.resolve(schedules, window, sources)' \
    'TargetScheduleResolver.resolve(schedules, window, emptyMap())'
mutate 'input-order determinism' \
    'return TargetPlan(planned, reconciliation)' \
    'return TargetPlan(if (schedules.first().ruleId == "mobility") planned.reversed() else planned, reconciliation)'
mutate 'ambient clock access' \
    'val resolved = TargetScheduleResolver.resolve(schedules, window, sources)' \
    'System.currentTimeMillis()\n        val resolved = TargetScheduleResolver.resolve(schedules, window, sources)'
mutate 'scheduler access' \
    'val resolved = TargetScheduleResolver.resolve(schedules, window, sources)' \
    'ProgramScheduler.hashCode()\n        val resolved = TargetScheduleResolver.resolve(schedules, window, sources)'
mutate 'mutable global state' \
    'object TargetPlanner {' \
    'object TargetPlanner {\n    private var ambientCache: List<PlannedOccurrence> = emptyList()'

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
