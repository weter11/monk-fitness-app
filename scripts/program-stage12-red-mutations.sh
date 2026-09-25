#!/usr/bin/env bash
# Phase 12 RED mutations: every deliberate break of the orchestration boundary must be
# caught, and the orchestrator source must be restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/usecase/TargetScheduleOrchestrator.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage12-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 12 mutation run owns $LOCK" >&2
    exit 1
fi
trap 'restore 2>/dev/null || true; rmdir "$LOCK" 2>/dev/null || true' EXIT

rm -rf "$WORK"
mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetScheduleOrchestrator.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

restore() { cp "$WORK/TargetScheduleOrchestrator.kt.orig" "$SOURCE"; }

# The oracle is the behavioural suite plus the architecture gate: a rule may be decided by
# either, and the suites that own Stage 2/5/6's inverted caller pins come along because a
# second consumer of those stages is exactly what those pins forbid.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleOrchestratorTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleOrchestratorArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyArchitectureTest' \
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

# Every anchor must exist before the baseline and its mutation must not: a drifted anchor
# reports MISSED, which is a claim about this harness rather than about the code.
#
# The check is done in Python, not with grep -F: GNU grep splits a multi-line pattern into
# separate OR-patterns, so a multi-line anchor would "match" any bare "        )" line in the
# file and the guard would pass for the wrong reason.
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

PLANNER_BLOCK='        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )'
POLICY_BLOCK='        val decision = TargetSchedulePolicy.decide(
            targetPlan = targetPlan,
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )'
APPLY_BLOCK='        val applicationResult = applicationService.apply(
            programId = request.programId,
            revisionId = request.revisionId,
            targetScheduleDecision = decision,
            programDayBindings = request.programDayBindings
        )'
EMPTY_PLAN='com.monkfitness.app.domain.program.target.TargetPlan(
            planned = emptyList(),
            reconciliation = com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliation(
                preserved = emptyList(),
                superseded = emptyList(),
                added = emptyList()
            )
        )'

echo '== preflight =='
preflight 'skip TargetPlanner' "$PLANNER_BLOCK" "        val targetPlan = $EMPTY_PLAN"
preflight 'skip TargetSchedulePolicy' "$POLICY_BLOCK" "        val decision = com.monkfitness.app.domain.program.target.TargetScheduleDecision(
            targetPlan = targetPlan,
            preserved = emptyList(),
            retained = emptyList(),
            created = targetPlan.planned,
            superseded = emptyList(),
            missed = emptyList()
        )"
preflight 'apply targetPlan.planned instead of decision.created' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(created = decision.targetPlan.planned),'
preflight 'bypass TargetScheduleApplicationService' "$APPLY_BLOCK" \
    "        val applicationResult = TargetScheduleApplicationResult(
            decision = decision,
            presentations = emptyList(),
            persistenceResult = TargetScheduleSlotPersistenceResult(
                created = emptyList(),
                retained = emptyList()
            )
        )"
preflight 'call ProgramScheduleRepository directly' \
    '    suspend fun apply(' \
    '    private val directRepository: com.monkfitness.app.data.repository.ProgramScheduleRepository? = null

    suspend fun directAdd(slots: List<com.monkfitness.app.domain.program.WorkoutSlot>) {
        directRepository?.addSlots(slots)
    }

    suspend fun apply('
preflight 'call the slot persister directly' \
    '    suspend fun apply(' \
    '    private val directPersister: TargetScheduleSlotPersister? = null

    suspend fun directPersist(input: TargetScheduleSlotPersistenceInput) {
        directPersister?.persist(input)
    }

    suspend fun apply('
preflight 'call TargetOccurrencePresenter manually' "$APPLY_BLOCK" \
    "        val manualPresentation = com.monkfitness.app.domain.program.target.TargetOccurrencePresenter.present(
            decision.created,
            request.programDayBindings
        )
$APPLY_BLOCK"
preflight 'construct TargetOccurrencePresentation manually' "$APPLY_BLOCK" \
    "        val manualPresentation = listOf(
            com.monkfitness.app.domain.program.target.TargetOccurrencePresentation(
                decision.created.first(),
                com.monkfitness.app.domain.common.ProgramDayId(request.programDayBindings.first().programDayId.value)
            )
        )
$APPLY_BLOCK"
preflight 'construct ProgramDayId manually' "$APPLY_BLOCK" \
    "        val manualDay = com.monkfitness.app.domain.common.ProgramDayId(\"derived-day\")
$APPLY_BLOCK"
preflight 'filter dates outside TargetSchedulePolicy' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(
                created = decision.created.filter { !it.plannedFor.isBefore(request.asOf) }
            ),'
preflight 'filter pauses outside TargetSchedulePolicy' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(
                created = decision.created.filter { candidate ->
                    request.pauses.none { pause -> pause.covers(candidate.plannedFor) }
                }
            ),'
preflight 'call the legacy ProgramScheduler' "$APPLY_BLOCK" \
    "        ProgramScheduler::class.java.name
$APPLY_BLOCK"
preflight 'TargetPlanner called after the policy' \
    "$PLANNER_BLOCK
$POLICY_BLOCK" \
    "        val decision = TargetSchedulePolicy.decide(
            targetPlan = TargetPlanner.plan(
                schedules = request.schedules,
                window = request.window,
                selection = request.selection,
                existing = request.existing,
                sources = request.sources
            ),
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )"
preflight 'TargetSchedulePolicy called before the planner' \
    "$PLANNER_BLOCK
$POLICY_BLOCK" \
    "        val decision = TargetSchedulePolicy.decide(
            targetPlan = $EMPTY_PLAN,
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )"
preflight 'discard started and completed existing occurrences' \
    '            existing = request.existing,
            asOf = request.asOf,' \
    '            existing = request.existing.filter {
                it.execution == com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
            },
            asOf = request.asOf,'
preflight 'mutate the caller request list in place' \
    '            programDayBindings = request.programDayBindings' \
    '            programDayBindings = request.programDayBindings.also {
                (it as MutableList).clear()
            }'
preflight 'catch a typed failure into an empty result' "$APPLY_BLOCK" \
    "        val applicationResult = try {
            applicationService.apply(
                programId = request.programId,
                revisionId = request.revisionId,
                targetScheduleDecision = decision,
                programDayBindings = request.programDayBindings
            )
        } catch (_: com.monkfitness.app.domain.program.target.TargetOccurrencePresentationException) {
            TargetScheduleApplicationResult(
                decision = decision,
                presentations = emptyList(),
                persistenceResult = TargetScheduleSlotPersistenceResult(
                    created = emptyList(),
                    retained = emptyList()
                )
            )
        }"
echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -30 "$WORK/last-run.log"
    exit 1
fi

mutate 'skip TargetPlanner' "$PLANNER_BLOCK" \
    "        val targetPlan = $EMPTY_PLAN"
mutate 'skip TargetSchedulePolicy' "$POLICY_BLOCK" \
    "        val decision = com.monkfitness.app.domain.program.target.TargetScheduleDecision(
            targetPlan = targetPlan,
            preserved = emptyList(),
            retained = emptyList(),
            created = targetPlan.planned,
            superseded = emptyList(),
            missed = emptyList()
        )"
mutate 'apply targetPlan.planned instead of decision.created' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(created = decision.targetPlan.planned),'
mutate 'bypass TargetScheduleApplicationService' "$APPLY_BLOCK" \
    "        val applicationResult = TargetScheduleApplicationResult(
            decision = decision,
            presentations = emptyList(),
            persistenceResult = TargetScheduleSlotPersistenceResult(
                created = emptyList(),
                retained = emptyList()
            )
        )"
mutate 'call ProgramScheduleRepository directly' \
    '    suspend fun apply(' \
    '    private val directRepository: com.monkfitness.app.data.repository.ProgramScheduleRepository? = null

    suspend fun directAdd(slots: List<com.monkfitness.app.domain.program.WorkoutSlot>) {
        directRepository?.addSlots(slots)
    }

    suspend fun apply('
mutate 'call the slot persister directly' \
    '    suspend fun apply(' \
    '    private val directPersister: TargetScheduleSlotPersister? = null

    suspend fun directPersist(input: TargetScheduleSlotPersistenceInput) {
        directPersister?.persist(input)
    }

    suspend fun apply('
mutate 'call TargetOccurrencePresenter manually' "$APPLY_BLOCK" \
    "        val manualPresentation = com.monkfitness.app.domain.program.target.TargetOccurrencePresenter.present(
            decision.created,
            request.programDayBindings
        )
$APPLY_BLOCK"
mutate 'construct TargetOccurrencePresentation manually' "$APPLY_BLOCK" \
    "        val manualPresentation = listOf(
            com.monkfitness.app.domain.program.target.TargetOccurrencePresentation(
                decision.created.first(),
                com.monkfitness.app.domain.common.ProgramDayId(request.programDayBindings.first().programDayId.value)
            )
        )
$APPLY_BLOCK"
mutate 'construct ProgramDayId manually' "$APPLY_BLOCK" \
    "        val manualDay = com.monkfitness.app.domain.common.ProgramDayId(\"derived-day\")
$APPLY_BLOCK"
mutate 'filter dates outside TargetSchedulePolicy' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(
                created = decision.created.filter { !it.plannedFor.isBefore(request.asOf) }
            ),'
mutate 'filter pauses outside TargetSchedulePolicy' \
    '            targetScheduleDecision = decision,' \
    '            targetScheduleDecision = decision.copy(
                created = decision.created.filter { candidate ->
                    request.pauses.none { pause -> pause.covers(candidate.plannedFor) }
                }
            ),'
mutate 'call the legacy ProgramScheduler' "$APPLY_BLOCK" \
    "        ProgramScheduler::class.java.name
$APPLY_BLOCK"
mutate 'TargetPlanner called after the policy' \
    "$PLANNER_BLOCK
$POLICY_BLOCK" \
    "        val decision = TargetSchedulePolicy.decide(
            targetPlan = TargetPlanner.plan(
                schedules = request.schedules,
                window = request.window,
                selection = request.selection,
                existing = request.existing,
                sources = request.sources
            ),
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )"
mutate 'TargetSchedulePolicy called before the planner' \
    "$PLANNER_BLOCK
$POLICY_BLOCK" \
    "        val decision = TargetSchedulePolicy.decide(
            targetPlan = $EMPTY_PLAN,
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )"
mutate 'discard started and completed existing occurrences' \
    '            existing = request.existing,
            asOf = request.asOf,' \
    '            existing = request.existing.filter {
                it.execution == com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
            },
            asOf = request.asOf,'
mutate 'mutate the caller request list in place' \
    '            programDayBindings = request.programDayBindings' \
    '            programDayBindings = request.programDayBindings.also {
                (it as MutableList).clear()
            }'
mutate 'catch a typed failure into an empty result' "$APPLY_BLOCK" \
    "        val applicationResult = try {
            applicationService.apply(
                programId = request.programId,
                revisionId = request.revisionId,
                targetScheduleDecision = decision,
                programDayBindings = request.programDayBindings
            )
        } catch (_: com.monkfitness.app.domain.program.target.TargetOccurrencePresentationException) {
            TargetScheduleApplicationResult(
                decision = decision,
                presentations = emptyList(),
                persistenceResult = TargetScheduleSlotPersistenceResult(
                    created = emptyList(),
                    retained = emptyList()
                )
            )
        }"

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
