#!/usr/bin/env bash
# Phase 16 RED mutations: every deliberate break of the occurrence-execution *precedence* must be
# caught, and the mutated source must be restored byte-identically.
#
# The oracle is the Phase 16 behavioural suite plus the Phase 16 architecture gate, because the two
# answer different questions. The behavioural suite catches a wrong *value* — an empty record read as
# cancelled, a completed occurrence demoted to started, a slot's status standing in for the
# execution. The architecture gate catches a wrong *shape* — a filter or a `lastOrNull` that quietly
# picks a representative attempt, a `finishedAt` read, a plan day consulted, a second precedence
# appearing somewhere else.
#
# A mutation that only changes a comment exercises nothing, so every probe strips comments first and
# asserts the code still says what the row claims. A mutation the compiler rejects is charged as a
# failure, never as a catch: nothing was proven about the rule if no oracle ever ran.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

# The policy is the phase's whole production surface: one value, one precedence, one owner. Every
# mutation below lands in this file, so restoring it restores the phase.
POLICY=app/src/main/java/com/monkfitness/app/domain/program/target/TargetOccurrenceExecutionPolicy.kt
SOURCES=("$POLICY")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage16-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 16 mutation run owns $LOCK" >&2
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

# The behavioural suite (the precedence values) and the architecture gate (the ten boundary claims).
# Phase 15's read-back suite comes along because the record this policy consumes is its contract,
# and Phase 15's own gate comes along because it asserts the record still holds no verdict — a
# policy that leaked one would show up there first.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionPolicyTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetOccurrenceExecutionPolicyArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetOccurrenceExecutionReadArchitectureTest' \
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
    # Hygiene rule, same as Phase 15's: a mutation that only puts a banned token in a comment
    # exercises nothing, because every gate strips comments first. So the probe strips them here too
    # and asserts the mutated line survives as real code — a future row cannot silently become prose.
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
        # A mutation the compiler rejected was not caught by an oracle — nothing was proven about the
        # rule. Counting that as a catch is exactly the weakness the Phase 15 harness warns about, so
        # it is reported and charged as a failure, which forces the mutation to be rewritten as
        # type-correct code at the layer the oracle reads.
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

# The precedence, branch by branch, exactly as the policy writes it.
EMPTY='            statuses.isEmpty() -> OccurrenceExecution.PLANNED'
ALL_CANCELLED='            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.CANCELLED'
ANY_COMPLETED='            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.COMPLETED'
ANY_PROGRESS='            statuses.any { it == SessionStatus.IN_PROGRESS } -> OccurrenceExecution.STARTED'

# The two lines every mutation below is expressed against.
STATUSES='        val statuses = record.attemptStatuses'
DECISION_RETURN='        return TargetOccurrenceExecutionDecision('

echo '== preflight =='

# 1./2. An occurrence with no attempt at all is planned, not cancelled and not started.
preflight 'read an empty record as cancelled' "$POLICY" "$EMPTY" \
    '            statuses.isEmpty() -> OccurrenceExecution.CANCELLED'
preflight 'read an empty record as started' "$POLICY" "$EMPTY" \
    '            statuses.isEmpty() -> OccurrenceExecution.STARTED'

# 3./4. A wholly cancelled occurrence is cancelled, not started and not completed.
preflight 'read a cancelled occurrence as started' "$POLICY" "$ALL_CANCELLED" \
    '            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.STARTED'
preflight 'read a cancelled occurrence as completed' "$POLICY" "$ALL_CANCELLED" \
    '            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.COMPLETED'

# 5./6./7. A started and a completed occurrence are neither promoted nor demoted.
preflight 'read a started occurrence as completed' "$POLICY" "$ANY_PROGRESS" \
    '            statuses.any { it == SessionStatus.IN_PROGRESS } -> OccurrenceExecution.COMPLETED'
preflight 'read a completed occurrence as started' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.STARTED'
preflight 'read a completed occurrence as cancelled' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.CANCELLED'

# 8. Read the slot's *opportunity* status as the occurrence's *execution*. Type-correct because
#    `SlotStatus.PLANNED` and `OccurrenceExecution.PLANNED` are separate enums, so the mistake is
#    made through an explicit reading of the slot's own token rather than a cast. The policy already
#    reads `record.slotStatus` legitimately — to report it as its own field — so no architecture gate
#    can see this one: the verdict silently becomes a function of the opportunity, and only the
#    behavioural suite notices that a PLANNED slot with a completed attempt is no longer completed.
preflight 'read the slot status as the occurrence execution' "$POLICY" "$DECISION_RETURN" \
    '        return if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.PLANNED) {
            TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.PLANNED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        } else TargetOccurrenceExecutionDecision('

# 9. Read the finish stamp instead of the stored status. A cancelled attempt carries a finish stamp
#    too, so this does not merely "look plausible" — it disagrees with the stored status on exactly
#    the rows the phase is about.
preflight 'derive the execution from the finish stamp' "$POLICY" "$STATUSES" \
    '        val statuses = record.attempts.map { attempt ->
            if (attempt.finishedAt != null) SessionStatus.COMPLETED else SessionStatus.IN_PROGRESS
        }'

# 10./11. Drop an attempt status before deciding.
preflight 'ignore the cancelled attempts' "$POLICY" "$STATUSES" \
    '        val statuses = record.attemptStatuses.filter { it != SessionStatus.CANCELLED }'
preflight 'ignore the in-progress attempts' "$POLICY" "$STATUSES" \
    '        val statuses = record.attemptStatuses.filter { it != SessionStatus.IN_PROGRESS }'

# 12./13. Choose one attempt by position. Type-correct and, for both, wrong in opposite directions:
#        the latest attempt of a COMPLETED → CANCELLED history is cancelled, and the earliest attempt
#        of a CANCELLED → IN_PROGRESS history is cancelled.
preflight 'choose only the last attempt' "$POLICY" "$STATUSES" \
    '        val statuses = listOfNotNull(record.attemptStatuses.lastOrNull())'
preflight 'choose only the first attempt' "$POLICY" "$STATUSES" \
    '        val statuses = listOfNotNull(record.attemptStatuses.firstOrNull())'

# 14. Require every attempt to be completed — a unanimity rule where the phase states a maximum.
preflight 'require every attempt to be completed' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.all { it == SessionStatus.COMPLETED } -> OccurrenceExecution.COMPLETED'

# 15./16. Read a slot's opportunity outcome as a cancellation. The occurrence's own attempts are
#        ignored, so a MISSED slot with a completed attempt stops being COMPLETED.
preflight 'treat a missed slot as cancelled' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.MISSED) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('
preflight 'treat a superseded slot as cancelled' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.SUPERSEDED) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

# 17. Derive the state from the planned date. The comparison is against a literal rather than a clock
#     read, so the mutation stays type-correct and deterministic; the point is the *shape* — a date
#     standing in for an execution state — and the suite's occurrences are planned for that day, so
#     the mistake actually changes every verdict instead of being an inert branch.
preflight 'derive the state from the planned date' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slot.plannedFor == java.time.LocalDate.parse("2026-10-05")) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

# 18. Derive the state from the current ProgramRevision. The record holds no "current revision", so
#     the mutation reads one off the slot's own stored revision and treats a *match* as a
#     cancellation — "this occurrence belongs to the live revision, so it was never worked" — which
#     is the mistake this gate exists to catch, made type-correct rather than merely plausible.
preflight 'derive the state from the current Program revision' "$POLICY" "$DECISION_RETURN" \
    '        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        if (record.slot.revisionId == currentRevision) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

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

mutate 'read an empty record as cancelled' "$POLICY" "$EMPTY" \
    '            statuses.isEmpty() -> OccurrenceExecution.CANCELLED'

mutate 'read an empty record as started' "$POLICY" "$EMPTY" \
    '            statuses.isEmpty() -> OccurrenceExecution.STARTED'

mutate 'read a cancelled occurrence as started' "$POLICY" "$ALL_CANCELLED" \
    '            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.STARTED'

mutate 'read a cancelled occurrence as completed' "$POLICY" "$ALL_CANCELLED" \
    '            statuses.all { it == SessionStatus.CANCELLED } -> OccurrenceExecution.COMPLETED'

mutate 'read a started occurrence as completed' "$POLICY" "$ANY_PROGRESS" \
    '            statuses.any { it == SessionStatus.IN_PROGRESS } -> OccurrenceExecution.COMPLETED'

mutate 'read a completed occurrence as started' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.STARTED'

mutate 'read a completed occurrence as cancelled' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.any { it == SessionStatus.COMPLETED } -> OccurrenceExecution.CANCELLED'

mutate 'read the slot status as the occurrence execution' "$POLICY" "$DECISION_RETURN" \
    '        return if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.PLANNED) {
            TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.PLANNED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        } else TargetOccurrenceExecutionDecision('

mutate 'derive the execution from the finish stamp' "$POLICY" "$STATUSES" \
    '        val statuses = record.attempts.map { attempt ->
            if (attempt.finishedAt != null) SessionStatus.COMPLETED else SessionStatus.IN_PROGRESS
        }'

mutate 'ignore the cancelled attempts' "$POLICY" "$STATUSES" \
    '        val statuses = record.attemptStatuses.filter { it != SessionStatus.CANCELLED }'

mutate 'ignore the in-progress attempts' "$POLICY" "$STATUSES" \
    '        val statuses = record.attemptStatuses.filter { it != SessionStatus.IN_PROGRESS }'

mutate 'choose only the last attempt' "$POLICY" "$STATUSES" \
    '        val statuses = listOfNotNull(record.attemptStatuses.lastOrNull())'

mutate 'choose only the first attempt' "$POLICY" "$STATUSES" \
    '        val statuses = listOfNotNull(record.attemptStatuses.firstOrNull())'

mutate 'require every attempt to be completed' "$POLICY" "$ANY_COMPLETED" \
    '            statuses.all { it == SessionStatus.COMPLETED } -> OccurrenceExecution.COMPLETED'

mutate 'treat a missed slot as cancelled' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.MISSED) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

mutate 'treat a superseded slot as cancelled' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slotStatus == com.monkfitness.app.domain.program.SlotStatus.SUPERSEDED) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

mutate 'derive the state from the planned date' "$POLICY" "$DECISION_RETURN" \
    '        if (record.slot.plannedFor == java.time.LocalDate.parse("2026-10-05")) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

mutate 'derive the state from the current Program revision' "$POLICY" "$DECISION_RETURN" \
    '        val currentRevision = com.monkfitness.app.domain.common.RevisionId("revision-1")
        if (record.slot.revisionId == currentRevision) {
            return TargetOccurrenceExecutionDecision(
                execution = OccurrenceExecution.CANCELLED,
                slotStatus = record.slotStatus,
                attemptCount = statuses.size
            )
        }
        return TargetOccurrenceExecutionDecision('

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
