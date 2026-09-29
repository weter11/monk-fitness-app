#!/usr/bin/env bash
# Stage 20 RED mutations: every deliberate break of the production target scheduling consumer must be
# caught, and the mutated production source must be restored byte-identically.
#
# The oracle is the Stage 20 behavioural suite (a real SQLite engine, real repositories, real target
# persistence) together with the Stage 20 architecture gate, plus the two architecture gates whose
# *closed consumer lists* name this stage's file — so a mutation that changed what the consumer is
# would be seen by the pins that count it, not only by the pins that read it.
#
# Four harness rules, each enforced below:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. It is reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe below strips comments and asserts the mutated
#    line survived as real code.
#  * **a no-op mutation is never a catch.** The probe also asserts the mutated source actually differs
#    from the original, so a drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row below preserves every public
#    signature, so the test source set compiles and a real oracle runs. Where the rule is about a
#    *dependency* — a legacy schedule, a second scheduler, a DAO — the row is a second declaration in
#    the forbidden vocabulary rather than a retyped signature, which is the shape a careless caller
#    would actually have and the shape the gate has to refuse.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
CONSUMER="$MAIN/domain/usecase/TargetScheduleProductionConsumer.kt"

SOURCES=("$CONSUMER")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage20-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 20 mutation run owns $LOCK" >&2
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

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumerIntegrationTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumerArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleInputAdapterArchitectureTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceArchitectureTest' \
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
assert token.strip() in text, 'the mutation text is absent from the file'
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

# The anchor check is in Python, not grep -F, because GNU grep splits a multi-line pattern into
# separate OR-patterns and a "match" there would prove nothing about the anchor.
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

# 1. The run reads the *oldest* revision of the Program instead of the one its pointer names. §23 makes
#    the current revision the one the pointer names, so this plans a saved revision's plan and reads a
#    source the Program is no longer presenting.
OLDREV_ANCHOR='        val currentRevision = planRepository.currentRevision(programId)'
OLDREV_LOST='        val currentRevision = planRepository.revisionsOf(programId).firstOrNull()'

# 2. The persisted rules are dropped, so the pass runs against a Program that stated nothing and
#    plans nothing — while reporting a successful schedule.
RULES_ANCHOR='            scheduleDefinitions = source.rules,'
RULES_LOST='            scheduleDefinitions = emptyList(),'

# 3. A legacy schedule is read as a target source. A *second declaration* in the legacy vocabulary
#    rather than a retyped signature, so the tree still compiles and the architecture gate is the
#    oracle that has to refuse it.
CLASS_ANCHOR='class TargetScheduleProductionConsumer('
LEGACY_LOST='private fun legacyRulesFrom(
    legacy: com.monkfitness.app.domain.program.ProgramSchedule
): List<TargetScheduleDefinition> = emptyList()

class TargetScheduleProductionConsumer('

# 4. The persisted bindings are dropped, so every presented occurrence loses the plan day it was told
#    to present — the mapping the source states and nothing else can supply.
BINDINGS_ANCHOR='            programDayBindings = source.programDayBindings'
BINDINGS_LOST='            programDayBindings = emptyList()'

# 5. The stored execution is replaced with *empty* execution, so every stored occurrence reaches the
#    reconciler looking untouched whatever actually happened to it.
EXECUTION_ANCHOR='            existingOccurrences = existingOccurrenceReader.existingOccurrencesOf(
                programId,
                storedOccurrences.map { it.occurrence }
            ),'
EXECUTION_LOST='            existingOccurrences = emptyList<
                com.monkfitness.app.domain.program.target.TargetExistingOccurrence
            >(),'

# 6. Execution is rebuilt from a slot'"'"'s status. A second declaration in that vocabulary, because the
#    rule is that the consumer must not be able to reach that fact at all.
SLOTSTATUS_LOST='private fun executionFromSlotStatus(
    status: com.monkfitness.app.domain.program.SlotStatus
): String = status.name

class TargetScheduleProductionConsumer('

# 7. The stored occurrences are never read, so the pass cannot see what it already planned.
STORED_ANCHOR='        val storedOccurrences = occurrenceRepository.occurrencesOfProgram(programId)'
STORED_LOST='        val storedOccurrences = emptyList<com.monkfitness.app.domain.program.target.PersistedTargetOccurrence>()'

# 8.–12. The five values the caller owns, each replaced by a value this stage must never decide.
WINDOW_ANCHOR='            window = context.window,'
WINDOW_LOST='            window = TargetScheduleWindow(context.window.from, context.window.from),'
SELECTION_ANCHOR='            selection = context.selection,'
SELECTION_LOST='            selection = CompositionSelection(),'
SOURCES_ANCHOR='            sources = context.sources,'
SOURCES_LOST='            sources = emptyMap(),'
ASOF_ANCHOR='            asOf = context.asOf,'
ASOF_LOST='            asOf = context.window.from,'
PAUSES_ANCHOR='            pauses = context.pauses,'
PAUSES_LOST='            pauses = emptyList(),'

# 13. The typed absence is answered with an empty source. A revision with no rules cannot produce an
#     occurrence, so "this revision states nothing" is not a weaker claim than the truth — it is a
#     different and unrepresentable one. Split into two oracles: the refusal half is the behavioural
#     suite, the shape half is the gate's assertion that the branch is a `return`.
MISSING_ANCHOR='            is TargetScheduleSourceRead.Missing -> return TargetScheduleRunResult.SourceMissing(read.revisionId)'
MISSING_LOST='            is TargetScheduleSourceRead.Missing -> TargetScheduleSource(revisionId, emptyList(), emptyList())'

# 14. The input adapter is bypassed and the request is built by hand, so the adapter'"'"'s own identity
#     and shape refusals no longer stand between a caller and a pass.
APPLY_ANCHOR='            result = orchestrator.apply(inputAdapter.adapt(input))'
BYPASS_ADAPTER_LOST='            result = orchestrator.apply(
                TargetScheduleOrchestrationRequest(
                    programId = input.programId,
                    revisionId = input.revisionId,
                    schedules = input.scheduleDefinitions.map { it.toTargetSchedule() },
                    window = input.window,
                    selection = input.selection,
                    existing = input.existingOccurrences,
                    sources = input.sources,
                    asOf = input.asOf,
                    pauses = input.pauses,
                    programDayBindings = input.programDayBindings
                )
            )'

# 15. The orchestrator is bypassed and the pass is recomposed here — plan, classify, "persist" — which
#     is a second composition of the same pass and a second place for the same rule to be decided.
#     The two target-package types are written **fully qualified** on purpose: the consumer does not
#     import them, so an unqualified name dies at `e:` and the row would be charged as a compile error
#     rather than a catch — which is exactly how this row first ran, and the harness was right to
#     refuse to score it. Qualification keeps the row type-correct in the whole tree, so the
#     architecture gate's "names no target stage" assertion and the behavioural suite's "a target
#     occurrence was persisted" assertions are the oracles that see it.
BYPASS_ORCHESTRATOR_LOST='            result = TargetScheduleOrchestrationResult(
                request = inputAdapter.adapt(input),
                targetPlan = com.monkfitness.app.domain.program.target.TargetPlanner.plan(
                    schedules = input.scheduleDefinitions.map { it.toTargetSchedule() },
                    window = input.window,
                    selection = input.selection,
                    existing = input.existingOccurrences,
                    sources = input.sources
                ),
                decision = com.monkfitness.app.domain.program.target.TargetSchedulePolicy.decide(
                    targetPlan = com.monkfitness.app.domain.program.target.TargetPlanner.plan(
                        schedules = input.scheduleDefinitions.map { it.toTargetSchedule() },
                        window = input.window,
                        selection = input.selection,
                        existing = input.existingOccurrences,
                        sources = input.sources
                    ),
                    existing = input.existingOccurrences,
                    asOf = input.asOf,
                    pauses = input.pauses
                ),
                applicationResult = TargetScheduleApplicationResult(
                    decision = com.monkfitness.app.domain.program.target.TargetSchedulePolicy.decide(
                        targetPlan = com.monkfitness.app.domain.program.target.TargetPlanner.plan(
                            schedules = input.scheduleDefinitions.map { it.toTargetSchedule() },
                            window = input.window,
                            selection = input.selection,
                            existing = input.existingOccurrences,
                            sources = input.sources
                        ),
                        existing = input.existingOccurrences,
                        asOf = input.asOf,
                        pauses = input.pauses
                    ),
                    presentations = emptyList(),
                    persistenceResult = TargetScheduleSlotPersistenceResult(emptyList(), emptyList())
                )
            )'

# 16. The legacy scheduler is reached from the new consumer, which would make the target path depend
#     on the generation it exists beside rather than replace.
LEGACY_SCHEDULER_LOST='private fun legacySchedulingOwner(): Class<*> =
    com.monkfitness.app.domain.usecase.ProgramScheduler::class.java

class TargetScheduleProductionConsumer('

# 17. A DAO is acquired directly, so the consumer bypasses the repository boundary it was given.
DIRECT_DAO_LOST='private fun storageHandle(): Class<*> =
    com.monkfitness.app.data.local.ProgramTargetScheduleSourceDao::class.java

class TargetScheduleProductionConsumer('

# 18. The pass runs against a source that is not the stored revision'"'"'s — the read is aimed at an
#     identity no revision states, which can only ever answer "missing" or "malformed".
SOURCE_READ_ANCHOR='        val source = when (val read = sourceBridge.definitionsAndBindingsOf(revisionId)) {'
SOURCE_READ_LOST='        val source = when (val read = sourceBridge.definitionsAndBindingsOf(RevisionId(revisionId.value + "-unrelated"))) {'

echo '== preflight =='

preflight 'the run uses the oldest revision instead of the current one' "$CONSUMER" "$OLDREV_ANCHOR" "$OLDREV_LOST"
preflight 'the persisted target rules are dropped' "$CONSUMER" "$RULES_ANCHOR" "$RULES_LOST"
preflight 'a legacy schedule is read as a target source' "$CONSUMER" "$CLASS_ANCHOR" "$LEGACY_LOST"
preflight 'the persisted plan-day bindings are dropped' "$CONSUMER" "$BINDINGS_ANCHOR" "$BINDINGS_LOST"
preflight 'stored execution is replaced with empty execution' "$CONSUMER" "$EXECUTION_ANCHOR" "$EXECUTION_LOST"
preflight 'execution is rebuilt from a slot status' "$CONSUMER" "$CLASS_ANCHOR" "$SLOTSTATUS_LOST"
preflight 'the stored occurrences are never read' "$CONSUMER" "$STORED_ANCHOR" "$STORED_LOST"
preflight "the caller's window is replaced" "$CONSUMER" "$WINDOW_ANCHOR" "$WINDOW_LOST"
preflight "the caller's selection is replaced" "$CONSUMER" "$SELECTION_ANCHOR" "$SELECTION_LOST"
preflight "the caller's resolved sources are replaced" "$CONSUMER" "$SOURCES_ANCHOR" "$SOURCES_LOST"
preflight "the caller's as-of date is replaced" "$CONSUMER" "$ASOF_ANCHOR" "$ASOF_LOST"
preflight "the caller's pause windows are replaced" "$CONSUMER" "$PAUSES_ANCHOR" "$PAUSES_LOST"
preflight 'a missing source becomes an empty source' "$CONSUMER" "$MISSING_ANCHOR" "$MISSING_LOST"
preflight 'the input adapter is bypassed' "$CONSUMER" "$APPLY_ANCHOR" "$BYPASS_ADAPTER_LOST"
preflight 'the orchestrator is bypassed' "$CONSUMER" "$APPLY_ANCHOR" "$BYPASS_ORCHESTRATOR_LOST"
preflight 'the legacy scheduler is reached' "$CONSUMER" "$CLASS_ANCHOR" "$LEGACY_SCHEDULER_LOST"
preflight 'a DAO is acquired directly' "$CONSUMER" "$CLASS_ANCHOR" "$DIRECT_DAO_LOST"
preflight 'the pass runs without the stored revision source' "$CONSUMER" "$SOURCE_READ_ANCHOR" "$SOURCE_READ_LOST"

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

mutate 'the run uses the oldest revision instead of the current one' "$CONSUMER" "$OLDREV_ANCHOR" "$OLDREV_LOST"
mutate 'the persisted target rules are dropped' "$CONSUMER" "$RULES_ANCHOR" "$RULES_LOST"
mutate 'a legacy schedule is read as a target source' "$CONSUMER" "$CLASS_ANCHOR" "$LEGACY_LOST"
mutate 'the persisted plan-day bindings are dropped' "$CONSUMER" "$BINDINGS_ANCHOR" "$BINDINGS_LOST"
mutate 'stored execution is replaced with empty execution' "$CONSUMER" "$EXECUTION_ANCHOR" "$EXECUTION_LOST"
mutate 'execution is rebuilt from a slot status' "$CONSUMER" "$CLASS_ANCHOR" "$SLOTSTATUS_LOST"
mutate 'the stored occurrences are never read' "$CONSUMER" "$STORED_ANCHOR" "$STORED_LOST"
mutate "the caller's window is replaced" "$CONSUMER" "$WINDOW_ANCHOR" "$WINDOW_LOST"
mutate "the caller's selection is replaced" "$CONSUMER" "$SELECTION_ANCHOR" "$SELECTION_LOST"
mutate "the caller's resolved sources are replaced" "$CONSUMER" "$SOURCES_ANCHOR" "$SOURCES_LOST"
mutate "the caller's as-of date is replaced" "$CONSUMER" "$ASOF_ANCHOR" "$ASOF_LOST"
mutate "the caller's pause windows are replaced" "$CONSUMER" "$PAUSES_ANCHOR" "$PAUSES_LOST"
mutate 'a missing source becomes an empty source' "$CONSUMER" "$MISSING_ANCHOR" "$MISSING_LOST"
mutate 'the input adapter is bypassed' "$CONSUMER" "$APPLY_ANCHOR" "$BYPASS_ADAPTER_LOST"
mutate 'the orchestrator is bypassed' "$CONSUMER" "$APPLY_ANCHOR" "$BYPASS_ORCHESTRATOR_LOST"
mutate 'the legacy scheduler is reached' "$CONSUMER" "$CLASS_ANCHOR" "$LEGACY_SCHEDULER_LOST"
mutate 'a DAO is acquired directly' "$CONSUMER" "$CLASS_ANCHOR" "$DIRECT_DAO_LOST"
mutate 'the pass runs without the stored revision source' "$CONSUMER" "$SOURCE_READ_ANCHOR" "$SOURCE_READ_LOST"

restore
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo 'every mutated source restored byte-identically'
else
    echo 'MISSED byte-identical restoration'
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
    cat "$WORK/md5check.log"
fi

printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS"
echo "missed: $FAIL"
[ "$FAIL" -eq 0 ]
