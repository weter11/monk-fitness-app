#!/usr/bin/env bash
# §30 step 9 — the deliberate RED mutations of the Progress/History layer.
#
# Each block applies one mutation to a production source, runs the focused PR-9 suites, and records
# whether they caught it. A rule whose mutation does NOT fail a test is a rule the suite does not prove,
# so this file is the evidence that §21's measures, §12's exposure rules, §17's comparability boundary and
# §19's status semantics are pinned by tests rather than by prose.
#
# Usage: bash scripts/program-progress-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME) exported in the SAME shell.
#
# The script restores every mutated file from a backup and verifies the restoration by md5 at the end, so
# a run leaves the reviewed bytes exactly as they were.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()

CALCULATOR="app/src/main/java/com/monkfitness/app/domain/progress/ProgressCalculator.kt"
FACTS="app/src/main/java/com/monkfitness/app/domain/progress/ProgressFacts.kt"
CALENDAR="app/src/main/java/com/monkfitness/app/domain/progress/CalendarProgress.kt"
DURATION="app/src/main/java/com/monkfitness/app/domain/progress/SessionDuration.kt"
SERVICE="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramProgressService.kt"
SOURCES=("$CALCULATOR" "$FACTS" "$CALENDAR" "$DURATION" "$SERVICE")

# md5 of the reviewed bytes, so the restore is provable rather than assumed.
md5sum "${SOURCES[@]}" > "$TMP/before.md5"

# apply <label> <file> <old> <new> — the first substitution of a mutation, and its backup.
apply() {
  local label="$1" file="$2" old="$3" new="$4"
  cp "$file" "$TMP/$(echo "$label" | tr ' ' '_').bak"
  substitute "$file" "$old" "$new"
}

# substitute <file> <old> <new> — a further substitution of the mutation already in flight.
substitute() {
  local file="$1" old="$2" new="$3"
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path).read()
assert old in text, f"mutation anchor not found in {path}: {old[:70]}"
open(path, "w").write(text.replace(old, new, 1))
PY
}

restore() {
  local label="$1" file="$2"
  cp "$TMP/$(echo "$label" | tr ' ' '_').bak" "$file"
}

# run_one <label> <expect_failure: yes|no>
#
# It also reports *which* tests caught the mutation, read from the JUnit XML of that very run: a mutation
# is only evidence if the failure names what it measured, and a mutation that broke the compilation is
# reported as such rather than being credited with the previous run's failures.
run_one() {
  local label="$1" expect="$2"
  local out
  out="$(./gradlew --offline :app:testDebugUnitTest \
      --tests "com.monkfitness.app.domain.progress.ProgressCalculatorTest" \
      --tests "com.monkfitness.app.domain.progress.ProgressFoundationTest" \
      --tests "com.monkfitness.app.domain.progress.ProgressArchitectureTest" \
      --tests "com.monkfitness.app.domain.usecase.ProgramProgressServiceTest" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  local detail=""
  if echo "$out" | grep -qE "^e: "; then
    caught="yes"
    detail="compile_error"
  elif echo "$out" | grep -q "BUILD FAILED"; then
    caught="yes"
    detail="$(python3 - <<'PY'
import glob, re
failed = []
for path in sorted(glob.glob("app/build/test-results/testDebugUnitTest/**/TEST-*.xml", recursive=True)):
    text = open(path, encoding="utf-8").read()
    for block in re.split(r"(?=<testcase )", text):
        name = re.match(r'<testcase name="([^"]+)"', block)
        if name and "<failure" in block:
            failed.append(name.group(1))
print(f"{len(failed)} failing test(s): " + ", ".join(failed[:6]) + (" …" if len(failed) > 6 else ""))
PY
)"
  fi
  if [ "$caught" = "$expect" ]; then
    PASS=$((PASS + 1))
    printf 'OK   %-68s %s\n' "$label" "$detail"
  else
    FAIL=$((FAIL + 1))
    FAILED_MUTATIONS+=("$label")
    printf 'MISS %-68s expected_failure=%s caught=%s %s\n' "$label" "$expect" "$caught" "$detail"
  fi
}

echo "== §30 step 9 RED mutations =="
echo

# 1. A missed opportunity is counted as one that was taken: the run is extended and the count grows.
apply 'a-missed-opportunity-is-read-as-a-taken-one' "$CALCULATOR" \
'                  SlotStatus.MISSED -> {
                        running = 0
                        decided += 1
                    }' \
'                  SlotStatus.MISSED -> {
                        running += 1
                        taken += 1
                        decided += 1
                        if (running > longest) longest = running
                    }'
run_one 'a missed opportunity is read as a taken one (§12, §21)' yes
restore 'a-missed-opportunity-is-read-as-a-taken-one' "$CALCULATOR"

# 2. A missed opportunity is read as a zero-workout: it stops being reported as missed at all.
apply 'a-missed-opportunity-is-not-reported' "$CALENDAR" \
'    val missed: Int
        get() = count(SlotStatus.MISSED)' \
'    val missed: Int
        get() = 0'
run_one 'a missed opportunity is not reported as missed (§12, §21)' yes
restore 'a-missed-opportunity-is-not-reported' "$CALENDAR"

# 3. A cancelled attempt is counted as a completed workout.
apply 'a-cancelled-attempt-is-counted-as-a-workout' "$CALCULATOR" \
'        val completedInWindow = facts.sessions
            .filter { it.status.isCompleted }' \
'        val completedInWindow = facts.sessions
            .filter { it.status != com.monkfitness.app.domain.workout.SessionStatus.IN_PROGRESS }'
run_one 'a cancelled attempt is counted as a completed workout (§19)' yes
restore 'a-cancelled-attempt-is-counted-as-a-workout' "$CALCULATOR"

# 4. The partial exposure of a cancelled (or still running) attempt is dropped on the way in.
apply 'partial-exposure-is-dropped' "$CALCULATOR" \
'        val observations = facts.sessions.flatMap { session ->' \
'        val observations = facts.sessions.filter { it.status.isCompleted }.flatMap { session ->'
run_one 'a cancelled attempt keeps the exposure it recorded (§12)' yes
restore 'partial-exposure-is-dropped' "$CALCULATOR"

# 5. An attempt that has not finished is given a duration.
apply 'a-running-attempt-is-given-a-duration' "$DURATION" \
'    val finishedAt = session.finishedAt ?: return null' \
'    val finishedAt = session.finishedAt ?: session.startedAt'
run_one 'an attempt still running has no duration (§19)' yes
restore 'a-running-attempt-is-given-a-duration' "$DURATION"

# 6. The average is taken over every attempt instead of the completed workouts inside the window.
apply 'the-average-ignores-the-status-and-the-window' "$CALCULATOR" \
'        val durations = completedInWindow.mapNotNull { (session, _) -> durationOf(session) }' \
'        val durations = facts.sessions.mapNotNull { session -> durationOf(session) }'
run_one 'the average is over completed workouts inside the window (§19, §21)' yes
restore 'the-average-ignores-the-status-and-the-window' "$CALCULATOR"

# 7. An average of nothing becomes zero seconds.
apply 'an-average-of-nothing-is-zero' "$DURATION" \
'        get() = if (measuredSessions == 0) null else totalSeconds.toDouble() / measuredSessions' \
'        get() = totalSeconds.toDouble() / maxOf(measuredSessions, 1)'
run_one 'an average of nothing is no average, not zero (§21)' yes
restore 'an-average-of-nothing-is-zero' "$DURATION"

# 8. Every observation is put in a repetition context, whatever unit it was measured in.
apply 'the-unit-is-ignored-when-a-context-is-built' "$CALCULATOR" \
'                    val context = ComparableContext(occurrence.exerciseId, occurrence.prescription.dimension)' \
'                    val context = ComparableContext(
                        occurrence.exerciseId,
                        com.monkfitness.app.domain.prescription.PrescriptionDimension.REP_BASED
                    )'
run_one 'an observation belongs to the context of its own unit (§12, §17)' yes
restore 'the-unit-is-ignored-when-a-context-is-built' "$CALCULATOR"

# 9. The exercise is dropped from the comparable context: every exercise becomes one series.
apply 'every-exercise-becomes-one-context' "$CALCULATOR" \
'                    val context = ComparableContext(occurrence.exerciseId, occurrence.prescription.dimension)' \
'                    val context = ComparableContext("every-exercise", occurrence.prescription.dimension)'
run_one 'two exercises are never one comparable context (§12, §17)' yes
restore 'every-exercise-becomes-one-context' "$CALCULATOR"

# 10. A volume carries the other unit as well.
apply 'a-volume-carries-both-units' "$CALCULATOR" \
'        seconds = when (series.context.dimension) {
            PrescriptionDimension.TIME_BASED -> series.observations.sumOf { it.seconds }
            else -> null
        }' \
'        seconds = series.observations.sumOf { it.seconds }'
run_one 'a volume is never measured in two units at once (§17)' yes
restore 'a-volume-carries-both-units' "$CALCULATOR"

# 11. A superseded opportunity ends the run.
apply 'a-superseded-opportunity-breaks-the-streak' "$CALCULATOR" \
'                    SlotStatus.PLANNED, SlotStatus.SUPERSEDED -> Unit' \
'                    SlotStatus.SUPERSEDED -> {
                        running = 0
                    }

                    SlotStatus.PLANNED -> Unit'
run_one 'a replaced opportunity was never expected, so it does not break a run (§20, §21)' yes
restore 'a-superseded-opportunity-breaks-the-streak' "$CALCULATOR"

# 12. An opportunity that is still open extends the run.
apply 'an-open-opportunity-extends-the-streak' "$CALCULATOR" \
'                    SlotStatus.PLANNED, SlotStatus.SUPERSEDED -> Unit' \
'                    SlotStatus.PLANNED -> {
                        running += 1
                        if (running > longest) longest = running
                    }

                    SlotStatus.SUPERSEDED -> Unit'
run_one 'an opportunity that has not happened yet is not part of a streak (§21)' yes
restore 'an-open-opportunity-extends-the-streak' "$CALCULATOR"

# 13. The aggregate gets an identity of its own, so it stops being an aggregation of Programs.
apply 'the-aggregate-invents-its-own-program' "$CALCULATOR" \
'            ProgressScope.AllPrograms -> facts.slots.map { it.programId }.distinct().sortedBy { it.value }' \
'            ProgressScope.AllPrograms -> listOf(com.monkfitness.app.domain.common.ProgramId("all-programs"))'
run_one 'the aggregate is a sum of Program-scoped facts, not an entity (§21)' yes
restore 'the-aggregate-invents-its-own-program' "$CALCULATOR"

# 14. A Program scope accepts another Program's facts.
apply 'a-program-scope-accepts-another-programs-facts' "$FACTS" \
'        if (owning is ProgressScope.OfProgram) {' \
'        if (false) {'
run_one 'a Program scope cannot hold Program B'"'"'s rows (§21)' yes
restore 'a-program-scope-accepts-another-programs-facts' "$FACTS"

# 15. The service reads every Program's rows for a Program-scoped query.
apply 'the-service-reads-every-program-for-one-programs-scope' "$SERVICE" \
'            slots = scheduleRepository.slotsOfProgram(scope.programId),
            sessions = sessionRepository.sessionsOfProgram(scope.programId)' \
'            slots = programRepository.programs()
                .flatMap { program -> scheduleRepository.slotsOfProgram(program.programId) },
            sessions = builderSessionsFor(scope.programId)'
substitute "$SERVICE" \
'    private suspend fun factsFor(scope: ProgressScope): ProgressFacts = when (scope) {' \
'    private suspend fun builderSessionsFor(programId: com.monkfitness.app.domain.common.ProgramId) =
        programRepository.programs().flatMap { program ->
            sessionRepository.sessionsOfProgram(program.programId)
        }

    private suspend fun factsFor(scope: ProgressScope): ProgressFacts = when (scope) {'
run_one 'the service reads a Program scope from that Program alone (§21)' yes
restore 'the-service-reads-every-program-for-one-programs-scope' "$SERVICE"

# 16. The calendar re-classifies a still-open opportunity from its date.
apply 'the-calendar-guesses-missed-from-a-date' "$CALCULATOR" \
'                    status = slot.status,' \
'                    status = if (slot.status == SlotStatus.PLANNED) SlotStatus.MISSED else slot.status,'
run_one 'upcoming is the stored status, never a date comparison made in Progress (§20, §33)' yes
restore 'the-calendar-guesses-missed-from-a-date' "$CALCULATOR"

# 17. The window stops bounding the rate-like measures.
apply 'the-window-is-ignored-by-the-rate' "$CALCULATOR" \
'            .filter { (_, date) -> window.contains(date) }' \
'            .filter { (_, date) -> true || window.contains(date) }'
run_one 'a rate is computed over the window it was asked for (§21)' yes
restore 'the-window-is-ignored-by-the-rate' "$CALCULATOR"

# 18. A workout is counted on its instant's UTC date instead of the calculator's zone.
apply 'the-instant-is-read-in-the-wrong-calendar' "$CALCULATOR" \
'            .map { session -> session to session.startedAt.atZone(zone).toLocalDate() }' \
'            .map { session -> session to session.startedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate() }'
run_one 'the calendar a workout falls on is the one the calculator was given (§21)' yes
restore 'the-instant-is-read-in-the-wrong-calendar' "$CALCULATOR"

# 19. History is returned oldest first.
apply 'history-is-not-newest-first' "$SERVICE" \
'        val newestFirst = calculator.history(factsFor(scope)).asReversed()' \
'        val newestFirst = calculator.history(factsFor(scope))'
run_one 'history reads newest first (§21)' yes
restore 'history-is-not-newest-first' "$SERVICE"

# 20. The default window is taken from the device instead of the injected clock.
apply 'the-default-window-reads-the-device-clock' "$SERVICE" \
'        ProgressWindow.ofLastDays(DEFAULT_WINDOW_DAYS, clock.now().atZone(zone).toLocalDate())' \
'        ProgressWindow.ofLastDays(DEFAULT_WINDOW_DAYS, java.time.Instant.now().atZone(zone).toLocalDate())'
run_one 'today comes from the injected clock (§26)' yes
restore 'the-default-window-reads-the-device-clock' "$SERVICE"

# 21. The service takes a Program scope to mean "the first Program stored".
apply 'a-program-scope-reads-the-first-program' "$SERVICE" \
'        is ProgressScope.OfProgram -> ProgressFacts(
            scope = scope,
            slots = scheduleRepository.slotsOfProgram(scope.programId),
            sessions = sessionRepository.sessionsOfProgram(scope.programId)
        )' \
'        is ProgressScope.OfProgram -> ProgressFacts(
            scope = scope,
            slots = programRepository.programs()
                .flatMap { program -> scheduleRepository.slotsOfProgram(program.programId) }
                .let { emptyList() },
            sessions = emptyList()
        )'
run_one 'a Program scope reads its own facts, never a substitute (§21)' yes
restore 'a-program-scope-reads-the-first-program' "$SERVICE"

# 22. The deferred measures are reported as computed (an empty list is "nothing is deferred").
apply 'the-deferred-measures-disappear' "$CALCULATOR" \
'            deferred = DEFERRED_MEASURES' \
'            deferred = emptyList()'
run_one 'a measure the facts cannot answer is reported as deferred, not as zero (§21)' yes
restore 'the-deferred-measures-disappear' "$CALCULATOR"

# 23. The observations are left in the order the facts arrived in.
apply 'the-series-is-not-ordered' "$CALCULATOR" \
'                ExercisePerformanceSeries(context, own.sortedWith(PerformanceObservation.ORDER))' \
'                ExercisePerformanceSeries(
                    context,
                    own.sortedWith(compareBy({ it.setIndex }))
                )'
run_one 'a series is in the order it happened, whatever order the facts arrived in (§21)' yes
restore 'the-series-is-not-ordered' "$CALCULATOR"

# 24. Control: the un-mutated tree must stay GREEN (the suites are not failing for nothing).
run_one 'unmutated tree stays GREEN (control)' no

echo
echo "== restoring and verifying the reviewed bytes =="
md5sum -c "$TMP/before.md5"

echo
echo "== summary: $PASS caught, $FAIL missed =="
if [ "$FAIL" -gt 0 ]; then
  echo "MISSED: ${FAILED_MUTATIONS[*]}"
  exit 1
fi
echo "every mandated rule is proven by a failing test when broken."
echo
echo "TWO KINDS OF CATCH APPEAR IN THIS TABLE, and they are not the same evidence:"
echo "  * a BEHAVIOURAL catch is a measure that changed and a test that measured it (mutations 1–4, 6–9,"
echo "    11–22);"
echo "  * an INVARIANT catch is a mutant that cannot even be BUILT as a value — a volume carrying both"
echo "    units (10), a series in the wrong order (23), and an attempt that is still running yet has a"
echo "    duration (5, where the history value's own construction refuses it) — which is the design: the"
echo "    forbidden state is unrepresentable, so the failure is the constructor's refusal rather than a"
echo "    wrong number downstream;"
echo "  * and mutation 14 changes no number at all when it is not refused: what catches it is the fact"
echo "    that a Program-scoped fact set cannot hold another Program's rows."
