#!/usr/bin/env bash
# §30 step 7 — the deliberate RED mutations of the Scheduler.
#
# Each block applies one mutation to a production source, runs the focused scheduler suites, and records
# whether they caught it. A rule whose mutation does NOT fail a test is a rule the suite does not prove,
# so this file is the evidence that §20's invariants are pinned by tests rather than by prose.
#
# Usage: bash scripts/program-scheduler-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME) exported in the SAME shell.
#
# The script restores every mutated file from a backup and verifies the restoration by md5 at the end,
# so a run leaves the reviewed bytes exactly as they were.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()

PLANNER="app/src/main/java/com/monkfitness/app/domain/program/SlotPlanner.kt"
CALENDAR="app/src/main/java/com/monkfitness/app/domain/program/ScheduleCalendar.kt"
HORIZON="app/src/main/java/com/monkfitness/app/domain/program/ScheduleHorizon.kt"
SCHEDULER="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramScheduler.kt"
SOURCES=("$PLANNER" "$CALENDAR" "$HORIZON" "$SCHEDULER")

# md5 of the reviewed bytes, so the restore is provable rather than assumed.
md5sum "${SOURCES[@]}" > "$TMP/before.md5"

# apply <label> <file> <old> <new>
apply() {
  local label="$1" file="$2" old="$3" new="$4"
  cp "$file" "$TMP/$(echo "$label" | tr ' ' '_').bak"
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
run_one() {
  local label="$1" expect="$2"
  local out
  out="$(./gradlew --offline :app:testDebugUnitTest \
      --tests "com.monkfitness.app.domain.program.SlotPlannerTest" \
      --tests "com.monkfitness.app.domain.program.ProgramSchedulerTest" \
      --tests "com.monkfitness.app.domain.program.ProgramSchedulerArchitectureTest" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  # A mutation that does not compile is caught too: the suites cannot run against it.
  echo "$out" | grep -qE "BUILD FAILED|^e: " && caught="yes"
  local ok="no"
  if [ "$expect" = "yes" ]; then
    [ "$caught" = "yes" ] && ok="yes"
  else
    [ "$caught" = "no" ] && ok="yes"
  fi
  if [ "$ok" = "yes" ]; then
    PASS=$((PASS + 1))
    echo "RED-OK   [$label] expected_failure=$expect caught=$caught"
  else
    FAIL=$((FAIL + 1))
    FAILED_MUTATIONS+=("$label")
    echo "RED-MISS [$label] expected_failure=$expect caught=$caught"
  fi
}

echo "== RED mutations: each must FAIL the scheduler suites (a rule is only proven if a break is caught) =="

# 1. NO SLIDING AFTER A MISS: a missed opportunity must not move the dates that come after it.
apply "slide-every-date-after-a-miss" "$PLANNER" \
  "                        plannedFor = date," \
  "                        plannedFor = date.plusDays(request.slots.count { it.status == SlotStatus.MISSED }.toLong()),"
run_one "a miss slides every later date (§20: no whole-schedule sliding)" yes
restore "slide-every-date-after-a-miss" "$PLANNER"

# 2. DETERMINISM: the flexible rhythm must be a function of the stated count, not of chance.
apply "flexible-rhythm-is-random" "$CALENDAR" \
  "        .map { position -> DayOfWeek.of(position * 7 / sessionsPerWeek + 1) }" \
  "        .map { position -> DayOfWeek.of((1..7).shuffled()[position]) }"
run_one "the flexible rhythm is chosen at random (§26, §33: deterministic generation)" yes
restore "flexible-rhythm-is-random" "$CALENDAR"

# 3. THE FREQUENCY IS AUTHORITATIVE: it is read from the revision, never counted from the slots.
apply "frequency-derived-from-the-slots" "$PLANNER" \
  "        val weekdays = revision.schedule.scheduledWeekdays()" \
  "        val weekdays = if (revision.schedule is ProgramSchedule.FlexiblePerWeek) {
            flexibleSpread(request.slots.count { it.status == SlotStatus.PLANNED }.coerceIn(1, 7))
        } else {
            revision.schedule.scheduledWeekdays()
        }"
run_one "the flexible frequency is derived from the slots that already exist (§20)" yes
restore "frequency-derived-from-the-slots" "$PLANNER"

# 4. NO SESSION RUNTIME: the Scheduler must not hold the session repository it would need to make one.
apply "scheduler-acquires-the-session-repository" "$SCHEDULER" \
  "    private val clock: Clock," \
  "    private val sessions: com.monkfitness.app.data.repository.WorkoutSessionRepository? = null,
    private val clock: Clock,"
run_one "the Scheduler acquires a session repository (§33: never let the Scheduler create Session)" yes
restore "scheduler-acquires-the-session-repository" "$SCHEDULER"

# 5. AN EXISTING OPPORTUNITY IS NEVER OVERWRITTEN: a date holds one slot, once.
apply "an-occupied-date-is-planned-again" "$PLANNER" \
  "                if (!window.contains(date) || coveredBy(request.pauses, date) || date in occupied) {" \
  "                if (!window.contains(date) || coveredBy(request.pauses, date)) {"
run_one "a date that already holds an opportunity is planned a second time (§20)" yes
restore "an-occupied-date-is-planned-again" "$PLANNER"

# 6. PAUSE STATE IS HONOURED: a paused date is not a date an opportunity is created on.
apply "paused-dates-are-planned-anyway" "$PLANNER" \
  "                if (!window.contains(date) || coveredBy(request.pauses, date) || date in occupied) {" \
  "                if (!window.contains(date) || date in occupied) {"
run_one "a paused date is planned as if the program were running (§3, §20)" yes
restore "paused-dates-are-planned-anyway" "$PLANNER"

# 7. PAUSE STATE IS HONOURED IN MISSED DETECTION: a paused opportunity is not a missed one.
apply "missed-detection-ignores-pauses" "$PLANNER" \
  "                slot.plannedFor.isBefore(request.asOf) &&
                    !coveredBy(request.pauses, slot.plannedFor)" \
  "                slot.plannedFor.isBefore(request.asOf)"
run_one "an opportunity that passed inside a pause is reported as missed (§3, §20)" yes
restore "missed-detection-ignores-pauses" "$PLANNER"

# 8. MISSED IS NOT A MIDNIGHT EVENT: an opportunity is missed once its day is over, not during it.
apply "todays-opportunity-is-already-missed" "$PLANNER" \
  "                slot.plannedFor.isBefore(request.asOf) &&
                    !coveredBy(request.pauses, slot.plannedFor)" \
  "                !slot.plannedFor.isAfter(request.asOf)"
run_one "the opportunity on the day of the pass is reported as missed (§20)" yes
restore "todays-opportunity-is-already-missed" "$PLANNER"

# 9. SUPERSESSION IS PRECISE: only the dates the revision cannot present are superseded.
apply "every-future-opportunity-is-superseded" "$PLANNER" \
  "                !slot.plannedFor.isBefore(request.asOf) &&
                    !presents(revision, slot.plannedFor, anchor, weekdays) ->" \
  "                !slot.plannedFor.isBefore(request.asOf) ->"
run_one "every future opportunity is superseded by any revision change (§20)" yes
restore "every-future-opportunity-is-superseded" "$PLANNER"

# 10. THE HORIZON IS THIRTY DAYS: an indefinite Program keeps exactly that much future planning.
apply "indefinite-horizon-is-not-thirty-days" "$HORIZON" \
  "const val PLANNING_HORIZON_DAYS: Int = 30" \
  "const val PLANNING_HORIZON_DAYS: Int = 14"
run_one "an indefinite Program keeps a horizon other than thirty days (§20)" yes
restore "indefinite-horizon-is-not-thirty-days" "$HORIZON"

# 11. THE PLAN CYCLES: the plan day a date presents follows the plan's order, not one fixed day.
apply "every-date-presents-the-first-plan-day" "$PLANNER" \
  "                        programDayId = revision.days[ordinal % revision.days.size].programDayId," \
  "                        programDayId = revision.days[0].programDayId,"
run_one "every opportunity presents the first plan day (§20: the plan's own days, in order)" yes
restore "every-date-presents-the-first-plan-day" "$PLANNER"

# 12. THE SCHEDULER DOES NOT REWRITE THE PLAN: a pass may not mint a revision.
apply "scheduler-writes-a-new-revision" "$SCHEDULER" \
  "        if (persist) store(decision)" \
  "        if (persist) {
            planRepository.saveNewRevision(
                revision.copy(
                    revisionId = RevisionId(idGenerator.newId()),
                    revisionNumber = revision.revisionNumber + 1
                ),
                clock.now()
            )
            store(decision)
        }"
run_one "the Scheduler writes a revision of the plan it was given (§6 immutability)" yes
restore "scheduler-writes-a-new-revision" "$SCHEDULER"

# 13. A COMPLETED PROGRAM IS NOT PLANNED (§3).
apply "completed-programs-are-planned" "$SCHEDULER" \
  "        if (program.lifecycleStatus.isTerminal) throw SchedulingProgramCompleted(programId)" \
  "        if (false) throw SchedulingProgramCompleted(programId)"
run_one "a completed Program silently receives future slots (§3)" yes
restore "completed-programs-are-planned" "$SCHEDULER"

# 14. AN ARCHIVED PROGRAM IS NOT PLANNED (§29).
apply "archived-programs-are-planned" "$SCHEDULER" \
  "        if (program.isArchived) throw SchedulingProgramArchived(programId)" \
  "        if (false) throw SchedulingProgramArchived(programId)"
run_one "an archived Program silently receives future slots (§29)" yes
restore "archived-programs-are-planned" "$SCHEDULER"

# 15. A PLANNED START DATE STILL DOES NOT START A PROGRAM (§3).
apply "planning-starts-the-program" "$SCHEDULER" \
  "        if (persist) store(decision)" \
  "        if (persist) {
            programRepository.updateProgram(
                program.copy(
                    lifecycleStatus = com.monkfitness.app.domain.program.LifecycleStatus.RUNNING,
                    actualStartDate = program.actualStartDate ?: clock.now()
                )
            )
            store(decision)
        }"
run_one "planning from a planned start date starts the Program (§3)" yes
restore "planning-starts-the-program" "$SCHEDULER"

# 16. A PAUSE RENUMBERS NOTHING: the plan's days are counted along the calendar, not along a walk that
# skips the paused dates. (This mutation restores exactly the defect the §30 step 7 audit found: with it,
# the dates after a pause belong to a different cycle than the slots already persisted before it.)
apply "the-plan-is-counted-along-a-pause-aware-walk" "$PLANNER" \
  "            planDates(anchor, window.lastDate, weekdays)" \
  "            planDates(anchor, window.lastDate, weekdays).filterNot { date -> coveredBy(request.pauses, date) }"
run_one "paused dates are excluded from the plan's own sequence (§3, §20: a pause renumbers nothing)" yes
restore "the-plan-is-counted-along-a-pause-aware-walk" "$PLANNER"

# 17. A FIXED RUN IS A NUMBER OF CALENDAR DAYS: a pause does not move its end.
apply "a-fixed-run-is-extended-by-the-paused-days" "$PLANNER" \
  "            is ProgramDuration.FixedDays -> anchor.plusDays((duration.days - 1).toLong())" \
  "            is ProgramDuration.FixedDays -> anchor.plusDays((duration.days - 1).toLong() + request.pauses.size.toLong())"
run_one "a FixedDays run is measured in active days rather than calendar days (§3, §20)" yes
restore "a-fixed-run-is-extended-by-the-paused-days" "$PLANNER"

# 18. Control: the un-mutated tree must stay GREEN (the suites are not failing for nothing).
run_one "unmutated tree stays GREEN (control)" no

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
echo "EXPRESSED STRUCTURALLY RATHER THAN BEHAVIOURALLY, and said so out loud:"
echo "  * 'the Scheduler creates no Session' — mutation 4 gives the Scheduler the session repository it"
echo "    would need, and the architecture suite fails on the collaborator list. A Scheduler that holds no"
echo "    session runtime cannot write one, which is why the claim is pinned by the constructor and by a"
echo "    whole-database row census (aPassCreatesNoSessionSnapshotOccurrenceOrSet) rather than by a"
echo "    mutated session write."
