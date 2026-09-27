#!/usr/bin/env bash
# Phase 17 RED mutations: every deliberate break of the target / legacy occurrence boundary must be
# caught, and the mutated source must be restored byte-identically.
#
# The oracle is the Phase 17 architecture gate plus the Phase 17 behavioural suites, together with
# the target suites whose pinned shapes the migration touched. The architecture gate answers "is the
# dependency direction still one-way?"; the behavioural suites answer "did the scheduling semantics
# survive?" — and a boundary refactor is only finished when both still hold.
#
# Three harness rules, each enforced below:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. It is reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe below strips comments and asserts the mutated
#    line survived as real code.
#  * **a mutation must be type-correct in the *whole* tree.** Changing a public signature breaks
#    every caller, so the test source set fails to compile and no oracle runs. Rows 1–4 therefore add
#    a *second declaration* in the legacy vocabulary (an unused private helper, a distinctly named
#    private function, an extra property) rather than editing the one the tests call. That is the
#    shape a careless new caller would actually have, and it is what the gate must refuse. (A Kotlin
#    *overload* is not a workaround: it erases to the same JVM signature and fails with
#    `Platform declaration clash`.)
#
# Rows 19–23 break a narrower thing: *which payload* the existing occurrence carries. Each keeps the
# execution correct and changes only the stored payload the value holds, so a bridge that forwarded
# the caller's occurrence — losing a real payload conflict — cannot pass.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

TARGET_DIR=app/src/main/java/com/monkfitness/app/domain/program/target
USECASE_DIR=app/src/main/java/com/monkfitness/app/domain/usecase

VALUE="$TARGET_DIR/TargetExistingOccurrence.kt"
RECONCILER="$TARGET_DIR/TargetOccurrenceReconciler.kt"
PLANNER="$TARGET_DIR/TargetPlanner.kt"
POLICY="$TARGET_DIR/TargetSchedulePolicy.kt"
ORCHESTRATOR="$USECASE_DIR/TargetScheduleOrchestrator.kt"
BRIDGE="$USECASE_DIR/TargetExistingOccurrenceReader.kt"

# Every file any row below touches. A file missing from this list keeps its mutation, and the next
# run's *control* then fails for a reason that has nothing to do with the code.
SOURCES=("$VALUE" "$RECONCILER" "$PLANNER" "$POLICY" "$ORCHESTRATOR" "$BRIDGE")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage17-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 17 mutation run owns $LOCK" >&2
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

# The Phase 17 gate (the twelve boundary claims), the Phase 17 behavioural suites (the value and the
# bridge), and the target suites whose contracts the migration rewrote — the reconciler, the planner,
# the policy and the two orchestration boundaries. The legacy-parity case in the reconciler suite
# comes along because it is the one place the legacy type must still be spoken.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetExistingOccurrenceArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetExistingOccurrenceBoundaryTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetExistingOccurrenceReaderTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconcilerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceReconciliationArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetPlannerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceCompositionArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrencePresenterArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetSlotMaterializerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleOrchestratorTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleInputAdapterTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleInputAdapterArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleOrchestratorArchitectureTest' \
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
    if ! python3 - "$file" "$new" "$WORK/probe.txt" <<'PROBE_BODY'
import re, sys
path, token, out = sys.argv[1:4]
text = open(path, encoding='utf-8').read()
code = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
first = token.split('\n')[0].strip()
assert first, 'empty probe token'
stripped = '\n'.join(line.strip() for line in code.splitlines() if line.strip())
assert first in stripped, 'the mutated line did not survive comment stripping:\n' + first
open(out, 'w', encoding='utf-8').write(code)
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

# ---- the anchors -------------------------------------------------------------------------------

# 1. The reconciler names the legacy type again — an unused private helper, which compiles.
RECONCILE_ANCHOR='    private fun indexReplacement(replacement: List<PlannedOccurrence>): Map<String, PlannedOccurrence> {'
RECONCILE_LEGACY='    private fun legacyKey(value: com.monkfitness.app.domain.program.ExistingOccurrence): String =
        value.occurrence.occurrenceKey

    private fun indexReplacement(replacement: List<PlannedOccurrence>): Map<String, PlannedOccurrence> {'

# 2./3./4. The planner, the policy and the orchestration file each gain a private function in the
#      legacy vocabulary. A Kotlin *overload* would erase to the same JVM signature and fail to
#      compile (`Platform declaration clash`), and a changed public signature would break every
#      caller so the test source set would not compile either. A distinctly named private function
#      is the shape that survives: real type-correct code at the layer the gate reads.
PLAN_ANCHOR='        val resolved = TargetScheduleResolver.resolve(schedules, window, sources)'
PLAN_LEGACY='        val legacyExisting: List<com.monkfitness.app.domain.program.ExistingOccurrence> = emptyList()
        val resolved = TargetScheduleResolver.resolve(schedules, window, sources)'
POLICY_ANCHOR='        val plannedKeys = targetPlan.planned.mapTo(HashSet()) { it.occurrenceKey }'
POLICY_LEGACY='        val legacyInput: List<com.monkfitness.app.domain.program.ExistingOccurrence> = emptyList()
        val plannedKeys = targetPlan.planned.mapTo(HashSet()) { it.occurrenceKey }'

# 4./17. The orchestration file gains a private reader of the legacy value, and re-imports the
#       legacy type. An unused import compiles, so that row is about the gate's import sweep.
REQUEST_ANCHOR='    val applicationResult: TargetScheduleApplicationResult
)'
REQUEST_LEGACY='    val applicationResult: TargetScheduleApplicationResult,
    val legacyExisting: List<com.monkfitness.app.domain.program.ExistingOccurrence> = emptyList()
)'
REQUEST_IMPORT='import com.monkfitness.app.domain.program.target.TargetExistingOccurrence'

# 5.–8./14./15./16. The value's declared shape: two facts, and nothing else may join them.
VALUE_SHAPE='data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution
)'

# 9./18. The bridge's single call into the Phase 16 policy, and the single construction site that
#       follows it. `BRIDGE_BUILD` is the line that decides *whose payload* the value carries, which
#       is what rows 19–23 break.
BRIDGE_DECIDE='        val decision = TargetOccurrenceExecutionPolicy.decide(record)'
BRIDGE_BUILD='        return TargetExistingOccurrence(record.occurrence.occurrence, decision)'

# 10. The reconciler's membership key.
RECONCILE_KEY='            val key = occurrence.occurrence.occurrenceKey'

# The declaration that makes row 13's *use* well-formed: a value typed as a RevisionId, read from
# outside, standing in for "the live revision". Both halves land together.
RECONCILE_REVISION_DECL='        val replacementKeys = replacementByKey.keys'

# 11. The policy's execution classification partition.
POLICY_PLANNED='        val plannedExisting = existing.filter { it.execution == OccurrenceExecution.PLANNED }'

# 12. The planner's second stage call, for a legacy-scheduler reference beside it. A *distinct*
#     anchor from row 2's, because two rows on one line would make one of them a no-op the other
#     had already applied.
PLAN_RESOLVE='        val planned = TargetOccurrenceComposer.compose(resolved, selection)'

# 13. The reconciler's preserved-set filter, and the "current revision" it is made to consult.
RECONCILE_SIG='        existing: List<TargetExistingOccurrence>,'
RECONCILE_PRESERVED='        val replacementKeys = replacementByKey.keys
        val preserved = existing
            .filter { it.execution != OccurrenceExecution.PLANNED }
            .canonicalExistingOrder()'

echo '== preflight =='

# 1./2./3./4./17. The four target components name the legacy type again.
preflight 'reconciler names ExistingOccurrence' "$RECONCILER" "$RECONCILE_ANCHOR" "$RECONCILE_LEGACY"
preflight 'planner accepts ExistingOccurrence' "$PLANNER" "$PLAN_ANCHOR" "$PLAN_LEGACY"
preflight 'policy accepts ExistingOccurrence' "$POLICY" "$POLICY_ANCHOR" "$POLICY_LEGACY"
preflight 'orchestration request accepts ExistingOccurrence' "$ORCHESTRATOR" "$REQUEST_ANCHOR" "$REQUEST_LEGACY"
preflight 'orchestration re-imports the legacy type' "$ORCHESTRATOR" "$REQUEST_IMPORT" \
    'import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence'

# 5.–8. The target value gains a state it must never hold. Each is a real, typed property on a
#      fully-qualified type, so the mutation compiles and the gate has to be the thing that refuses.
preflight 'target value gains ActualResult' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val actuals: List<com.monkfitness.app.domain.program.ActualResult> = emptyList()
)'
preflight 'target value gains WorkoutSession' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val session: com.monkfitness.app.domain.workout.WorkoutSession? = null
)'
preflight 'target value gains WorkoutSlot' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val slot: com.monkfitness.app.domain.program.WorkoutSlot? = null
)'
preflight 'target value stores PerformedWork' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val performed: List<com.monkfitness.app.domain.program.PerformedWork> = emptyList()
)'

# 14./15./16. And a repository, a clock, and an identity generator.
preflight 'target value gains a repository dependency' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val repository: com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository? = null
)'
preflight 'target value gains a clock' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val today: () -> java.time.LocalDate = { java.time.LocalDate.now() }
)'
preflight 'target value gains an IdGenerator' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val ids: com.monkfitness.app.di.IdGenerator = com.monkfitness.app.di.IdGenerator { "x" }
)'

# 9. The bridge re-derives the execution instead of asking the Phase 16 policy. Type-correct: the
#    statuses are read off the record and reduced by a `when` of its own, which is exactly the
#    second precedence the gate exists to refuse — and a completed attempt now reads STARTED.
preflight 'bridge re-derives the execution' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        val decision = com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionDecision(
            execution = if (record.attemptStatuses.isEmpty()) {
                com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
            } else {
                com.monkfitness.app.domain.program.OccurrenceExecution.STARTED
            },
            slotStatus = record.slotStatus,
            attemptCount = record.attemptStatuses.size
        )'

# 18. The target path constructs an ActualResult. The construction is real and type-correct; what
#     makes it the defect is that it happens on the scheduling path at all.
preflight 'target path constructs an ActualResult' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        val actuals = listOf(
            com.monkfitness.app.domain.program.ActualResult(
                "work-1",
                com.monkfitness.app.domain.program.PerformedWork.reps(12)
            )
        )
        val decision = TargetOccurrenceExecutionPolicy.decide(record)'

# 10. The reconciler keys membership on something other than the occurrence key. The date is a
#     legitimate read elsewhere, so the mutation stays type-correct and deterministically wrong: two
#     occurrences on one date now collide and the second is refused as a duplicate.
preflight 'reconciler keys membership on the date' "$RECONCILER" "$RECONCILE_KEY" \
    '            val key = occurrence.occurrence.plannedFor.toString()'

# 11. The policy changes the STARTED/COMPLETED/CANCELLED classification by admitting cancelled
#     occurrences into the partition, so retained/missed/superseded stop partitioning on PLANNED.
preflight 'policy admits cancelled occurrences' "$POLICY" "$POLICY_PLANNED" \
    '        val plannedExisting = existing.filter { it.execution != OccurrenceExecution.CANCELLED }'

# 12. The target path consults the legacy scheduler. A pure package cannot construct a use-case
#     collaborator, so the mutation reads the legacy scheduler's declared type instead — which is
#     still the dependency the gate forbids, and still compiles.
preflight 'target path consults the legacy ProgramScheduler' "$PLANNER" "$PLAN_RESOLVE" \
    '        val legacy: Class<*> = com.monkfitness.app.domain.usecase.ProgramScheduler::class.java
        val planned = TargetOccurrenceComposer.compose(resolved, selection)'

# 13. The target path reads a current ProgramRevision to decide membership. Type-correct and wrong
#     in exactly the way the blueprint forbids: reconciliation now needs the caller to name the live
#     revision, which is a second identity for an occurrence's membership.
preflight 'target path reads the current ProgramRevision' "$RECONCILER" "$RECONCILE_PRESERVED" \
    '        val replacementKeys = replacementByKey.keys
        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        val preserved = existing
            .filter { it.occurrence.occurrenceKey.contains(currentRevision.value) }
            .filter { it.execution != OccurrenceExecution.PLANNED }
            .canonicalExistingOrder()'

# 19.–23. The payload the existing occurrence carries. Every row below keeps the execution
#        correct and breaks only *which payload* the value holds, so the behavioural regression and
#        the architecture gate have to be what refuses them.
preflight 'bridge substitutes the caller payload' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(occurrence, decision)'
preflight 'bridge rebuilds the payload from the caller components' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(
            occurrence.copy(components = occurrence.components),
            decision
        )'
preflight 'bridge keeps only the stored key' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(
            record.occurrence.occurrence.copy(components = emptyList()),
            decision
        )'
preflight 'bridge validates the caller then returns it' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        if (record.occurrence.occurrence != occurrence) {
            require(record.occurrence.occurrence == occurrence) {
                "caller payload conflicts with the stored payload"
            }
        }
        val decision = TargetOccurrenceExecutionPolicy.decide(record)'
preflight 'bridge derives the payload from the current revision' "$BRIDGE" "$BRIDGE_BUILD" \
    '        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        return TargetExistingOccurrence(
            record.occurrence.occurrence.copy(plannedFor = currentRevision.value.length.let { record.occurrence.occurrence.plannedFor }),
            decision
        )'

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

mutate 'reconciler names ExistingOccurrence' "$RECONCILER" "$RECONCILE_ANCHOR" "$RECONCILE_LEGACY"

mutate 'planner accepts ExistingOccurrence' "$PLANNER" "$PLAN_ANCHOR" "$PLAN_LEGACY"

mutate 'policy accepts ExistingOccurrence' "$POLICY" "$POLICY_ANCHOR" "$POLICY_LEGACY"

mutate 'orchestration request accepts ExistingOccurrence' "$ORCHESTRATOR" "$REQUEST_ANCHOR" "$REQUEST_LEGACY"

mutate 'target value gains ActualResult' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val actuals: List<com.monkfitness.app.domain.program.ActualResult> = emptyList()
)'

mutate 'target value gains WorkoutSession' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val session: com.monkfitness.app.domain.workout.WorkoutSession? = null
)'

mutate 'target value gains WorkoutSlot' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val slot: com.monkfitness.app.domain.program.WorkoutSlot? = null
)'

mutate 'target value stores PerformedWork' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val performed: List<com.monkfitness.app.domain.program.PerformedWork> = emptyList()
)'

mutate 'bridge re-derives the execution' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        val decision = com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionDecision(
            execution = if (record.attemptStatuses.isEmpty()) {
                com.monkfitness.app.domain.program.OccurrenceExecution.PLANNED
            } else {
                com.monkfitness.app.domain.program.OccurrenceExecution.STARTED
            },
            slotStatus = record.slotStatus,
            attemptCount = record.attemptStatuses.size
        )'

mutate 'reconciler keys membership on the date' "$RECONCILER" "$RECONCILE_KEY" \
    '            val key = occurrence.occurrence.plannedFor.toString()'

mutate 'policy admits cancelled occurrences' "$POLICY" "$POLICY_PLANNED" \
    '        val plannedExisting = existing.filter { it.execution != OccurrenceExecution.CANCELLED }'

mutate 'target path consults the legacy ProgramScheduler' "$PLANNER" "$PLAN_RESOLVE" \
    '        val legacy: Class<*> = com.monkfitness.app.domain.usecase.ProgramScheduler::class.java
        val planned = TargetOccurrenceComposer.compose(resolved, selection)'

mutate 'target path reads the current ProgramRevision' "$RECONCILER" "$RECONCILE_PRESERVED" \
    '        val replacementKeys = replacementByKey.keys
        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        val preserved = existing
            .filter { it.occurrence.occurrenceKey.contains(currentRevision.value) }
            .filter { it.execution != OccurrenceExecution.PLANNED }
            .canonicalExistingOrder()'

mutate 'target value gains a repository dependency' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val repository: com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository? = null
)'

mutate 'target value gains a clock' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val today: () -> java.time.LocalDate = { java.time.LocalDate.now() }
)'

mutate 'target value gains an IdGenerator' "$VALUE" "$VALUE_SHAPE" \
    'data class TargetExistingOccurrence(
    val occurrence: PlannedOccurrence,
    val execution: OccurrenceExecution,
    val ids: com.monkfitness.app.di.IdGenerator = com.monkfitness.app.di.IdGenerator { "x" }
)'

mutate 'orchestration re-imports the legacy type' "$ORCHESTRATOR" "$REQUEST_IMPORT" \
    'import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence'

mutate 'target path constructs an ActualResult' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        val actuals = listOf(
            com.monkfitness.app.domain.program.ActualResult(
                "work-1",
                com.monkfitness.app.domain.program.PerformedWork.reps(12)
            )
        )
        val decision = TargetOccurrenceExecutionPolicy.decide(record)'

mutate 'bridge substitutes the caller payload' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(occurrence, decision)'

mutate 'bridge rebuilds the payload from the caller components' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(
            occurrence.copy(components = occurrence.components),
            decision
        )'

mutate 'bridge keeps only the stored key' "$BRIDGE" "$BRIDGE_BUILD" \
    '        return TargetExistingOccurrence(
            record.occurrence.occurrence.copy(components = emptyList()),
            decision
        )'

mutate 'bridge validates the caller then returns it' "$BRIDGE" "$BRIDGE_DECIDE" \
    '        if (record.occurrence.occurrence != occurrence) {
            require(record.occurrence.occurrence == occurrence) {
                "caller payload conflicts with the stored payload"
            }
        }
        val decision = TargetOccurrenceExecutionPolicy.decide(record)'

mutate 'bridge derives the payload from the current revision' "$BRIDGE" "$BRIDGE_BUILD" \
    '        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        return TargetExistingOccurrence(
            record.occurrence.occurrence.copy(plannedFor = currentRevision.value.length.let { record.occurrence.occurrence.plannedFor }),
            decision
        )'

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
