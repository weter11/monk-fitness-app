#!/usr/bin/env bash
# Stage 7 RED mutations: every deliberate break must be caught and the presenter restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetOccurrencePresenter.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage7-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 7 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetOccurrencePresenter.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrencePresenterTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrencePresenterArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceCompositionArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliationArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

restore() { cp "$WORK/TargetOccurrencePresenter.kt.orig" "$SOURCE"; }

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

mutate 'first-component ProgramDay selection' \
    'val programDayIds = occurrence.components' \
    'val programDayIds = occurrence.components.take(1)'
mutate 'last-component ProgramDay selection' \
    'val programDayIds = occurrence.components' \
    'val programDayIds = occurrence.components.takeLast(1)'
mutate 'ignoring secondary components' \
    '.map { component ->' \
    '.take(1).map { component ->'
mutate 'missing binding accepted' \
    '?: throw TargetOccurrencePresentationException.MissingProgramDayBinding(' \
    '?: ProgramDayId("fallback-day")'
mutate 'conflicting binding accepted' \
    'if (existing != null) {' \
    'if (false) {'
mutate 'multi-day occurrence silently collapsed' \
    'val programDayIds = occurrence.components' \
    'val programDayIds = occurrence.components.map { it.workoutId }.take(1).map { workoutId -> byWorkout[workoutId] }'
mutate 'component order dependence' \
    'components = occurrence.components.sortedWith(
                    compareBy<com.monkfitness.app.domain.program.OccurrenceComponent> { it.ruleId }' \
    'components = occurrence.components.sortedWith(
                    compareByDescending<com.monkfitness.app.domain.program.OccurrenceComponent> { it.ruleId }'
mutate 'binding order dependence' \
    'byWorkout[component.workoutId]' \
    'byWorkout.values.first()'
mutate 'occurrence order dependence' \
    '.sortedWith(
                compareBy<TargetOccurrencePresentation> { it.occurrence.plannedFor }' \
    '.sortedWith(
                compareByDescending<TargetOccurrencePresentation> { it.occurrence.plannedFor }'
mutate 'ProgramDay string inference' \
    'val programDayIds = occurrence.components' \
    'val inferred = occurrence.components.first().workoutId.let { ProgramDayId(it) }
        val programDayIds = occurrence.components'
mutate 'occurrence key rewritten' \
    'occurrence.copy(
                components = occurrence.components.sortedWith(' \
    'occurrence.copy(
                occurrenceKey = programDayIds.single().value,
                components = occurrence.components.sortedWith('
mutate 'scheduler reference introduced' \
    'object TargetOccurrencePresenter {' \
    'object TargetOccurrencePresenter { private val scheduler = ProgramScheduler.hashCode()'
mutate 'mutable global state introduced' \
    'object TargetOccurrencePresenter {' \
    'object TargetOccurrencePresenter { private var cache: List<PlannedOccurrence> = emptyList()'

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
