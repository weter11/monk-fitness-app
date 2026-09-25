#!/usr/bin/env bash
# Phase 11 RED mutations: every deliberate break must be caught and the application service restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleApplicationService.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage11-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 11 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetScheduleApplicationService.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

restore() { cp "$WORK/TargetScheduleApplicationService.kt.orig" "$SOURCE"; }

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleApplicationServiceTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleApplicationServiceArchitectureTest' \
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

preflight() {
    local label="$1" old="$2" new="$3"
    if ! grep -Fq "$old" "$SOURCE"; then
        echo "ABORT: $label anchor is absent before baseline" >&2
        exit 1
    fi
    if grep -Fq "$new" "$SOURCE"; then
        echo "ABORT: $label mutation is already present before baseline" >&2
        exit 1
    fi
}

echo '== preflight =='
preflight 'use targetPlan.planned instead of decision.created' \
    'targetScheduleDecision.created,' 'targetScheduleDecision.targetPlan.planned,'
preflight 'skip TargetOccurrencePresenter' \
    'val presentations = TargetOccurrencePresenter.present(' 'val presentations = targetScheduleDecision.created.map { it }'
preflight 'construct TargetOccurrencePresentation manually' \
    'val presentations = TargetOccurrencePresenter.present(' 'val presentations = targetScheduleDecision.created.map { it }'
preflight 'call ProgramScheduleRepository directly' \
    'slotPersister: TargetScheduleSlotPersister' 'slotPersister: ProgramScheduleRepository'
preflight 'construct WorkoutSlot directly' \
    'val persistenceResult = slotPersister.persist(' 'val persistenceResult = WorkoutSlot('
preflight 'generate SlotId in application service' \
    'val presentations = TargetOccurrencePresenter.present(' 'val generatedSlotIdentity = SlotId("generated")'
preflight 'rewrite occurrenceKey' \
    'targetScheduleDecision.created,' 'targetScheduleDecision.created.map { it.copy(occurrenceKey = "rewritten") },'
preflight 'derive ProgramDayId from workout or date' \
    'programDayBindings' 'listOf(ProgramDayId("derived"))'
preflight 'call TargetPlanner' \
    'val presentations = TargetOccurrencePresenter.present(' 'TargetPlanner.hashCode()'
preflight 'call TargetSchedulePolicy' \
    'val presentations = TargetOccurrencePresenter.present(' 'TargetSchedulePolicy.hashCode()'
preflight 'call ProgramScheduler' \
    'val presentations = TargetOccurrencePresenter.present(' 'ProgramScheduler.hashCode()'
preflight 'collapse same-date occurrences' \
    'targetScheduleDecision.created,' 'targetScheduleDecision.created.distinctBy { it.plannedFor },'
preflight 'convert typed presentation failure into empty result' \
    'val presentations = TargetOccurrencePresenter.present(' 'val presentations = try {'
preflight 'mutate decision.created' \
    'val persistenceResult = slotPersister.persist(' 'targetScheduleDecision.created.clear()'
preflight 'mutate bindings' \
    'val persistenceResult = slotPersister.persist(' 'programDayBindings.clear()'
echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    exit 1
fi

mutate 'use targetPlan.planned instead of decision.created' \
    'targetScheduleDecision.created,' \
    'targetScheduleDecision.targetPlan.planned,'
mutate 'skip TargetOccurrencePresenter' \
    'val presentations = TargetOccurrencePresenter.present(
            targetScheduleDecision.created,
            programDayBindings
        )' \
    'val presentations = targetScheduleDecision.created.map { it }
'
mutate 'construct TargetOccurrencePresentation manually' \
    'val presentations = TargetOccurrencePresenter.present(
            targetScheduleDecision.created,
            programDayBindings
        )' \
    'val presentations = targetScheduleDecision.created.map { occurrence ->
            com.monkfitness.app.domain.program.target.TargetOccurrencePresentation(
                occurrence,
                programDayBindings.first().programDayId
            )
        }'
mutate 'call ProgramScheduleRepository directly' \
    'slotPersister: TargetScheduleSlotPersister' \
    'slotPersister: com.monkfitness.app.data.repository.ProgramScheduleRepository'
mutate 'construct WorkoutSlot directly' \
    'val persistenceResult = slotPersister.persist(' \
    'val persistenceResult = slotPersister.persist( // direct construction mutation
        com.monkfitness.app.domain.program.WorkoutSlot('
mutate 'generate SlotId in application service' \
    'val persistenceResult = slotPersister.persist(' \
    'val persistenceResult = slotPersister.persist(
            // identity minted where it does not belong
            require(com.monkfitness.app.domain.common.SlotId(UUID.randomUUID().toString()).value.isNotBlank())'
mutate 'rewrite occurrenceKey' \
    'targetScheduleDecision.created,' \
    'targetScheduleDecision.created.map { it.copy(occurrenceKey = "rewritten") },'
mutate 'derive ProgramDayId from workout or date' \
    'programDayBindings' \
    'listOf(com.monkfitness.app.domain.common.ProgramDayId("derived-${targetScheduleDecision.created.first().plannedFor}"))'
mutate 'call TargetPlanner' \
    'val presentations = TargetOccurrencePresenter.present(' \
    'com.monkfitness.app.domain.program.target.TargetPlanner.hashCode()
        val presentations = TargetOccurrencePresenter.present('
mutate 'call TargetSchedulePolicy' \
    'val presentations = TargetOccurrencePresenter.present(' \
    'com.monkfitness.app.domain.program.target.TargetSchedulePolicy.hashCode()
        val presentations = TargetOccurrencePresenter.present('
mutate 'call ProgramScheduler' \
    'val presentations = TargetOccurrencePresenter.present(' \
    'ProgramScheduler.hashCode()
        val presentations = TargetOccurrencePresenter.present('
mutate 'collapse same-date occurrences' \
    'targetScheduleDecision.created,' \
    'targetScheduleDecision.created.distinctBy { it.plannedFor },'
mutate 'convert typed presentation failure into empty result' \
    'val presentations = TargetOccurrencePresenter.present(
            targetScheduleDecision.created,
            programDayBindings
        )' \
    'val presentations = try {
            TargetOccurrencePresenter.present(targetScheduleDecision.created, programDayBindings)
        } catch (_: com.monkfitness.app.domain.program.target.TargetOccurrencePresentationException) {
            emptyList()
        }'
mutate 'mutate decision.created' \
    'val persistenceResult = slotPersister.persist(' \
    'targetScheduleDecision.created.clear()
        val persistenceResult = slotPersister.persist('
mutate 'mutate bindings' \
    'val persistenceResult = slotPersister.persist(' \
    'programDayBindings.clear()
        val persistenceResult = slotPersister.persist('

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
