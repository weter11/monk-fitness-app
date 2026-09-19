#!/usr/bin/env bash
# §30 step 8 — the deliberate RED mutations of the Session runtime.
#
# Each block applies one mutation to a production source, runs the focused PR-8 suites, and records
# whether they caught it. A rule whose mutation does NOT fail a test is a rule the suite does not prove,
# so this file is the evidence that §19's, §16's and §27's invariants about one attempt are pinned by
# tests rather than by prose.
#
# Usage: bash scripts/program-session-runtime-red-mutations.sh
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

RUNTIME="app/src/main/java/com/monkfitness/app/domain/usecase/SessionRuntime.kt"
REPOSITORY="app/src/main/java/com/monkfitness/app/data/repository/WorkoutSessionRepository.kt"
DAO="app/src/main/java/com/monkfitness/app/data/local/WorkoutSessionDao.kt"
SOURCES=("$RUNTIME" "$REPOSITORY" "$DAO")

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
      --tests "com.monkfitness.app.domain.usecase.SessionRuntimeTest" \
      --tests "com.monkfitness.app.domain.usecase.SessionRuntimeArchitectureTest" \
      --tests "com.monkfitness.app.domain.workout.SessionRuntimeResultTest" \
      --tests "com.monkfitness.app.domain.adaptive.decision.SlotPresentationTest" \
      --tests "com.monkfitness.app.data.repository.WorkoutSessionRepositoryTest" \
      --tests "com.monkfitness.app.data.repository.ProgramDataAccessArchitectureTest" \
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
  local ok="no"
  if [ "$expect" = "yes" ]; then
    [ "$caught" = "yes" ] && ok="yes"
  else
    [ "$caught" = "no" ] && ok="yes"
  fi
  if [ "$ok" = "yes" ]; then
    PASS=$((PASS + 1))
    echo "RED-OK   [$label] expected_failure=$expect caught=$caught ${detail:+| $detail}"
  else
    FAIL=$((FAIL + 1))
    FAILED_MUTATIONS+=("$label")
    echo "RED-MISS [$label] expected_failure=$expect caught=$caught"
  fi
}

echo "== RED mutations: each must FAIL the PR-8 suites (a rule is only proven if a break is caught) =="

# 1. THE SNAPSHOT IS THE TRUTH: a loaded session is rebuilt from its own rows, never re-composed from the
# live plan and the adjustments that stand *now* (§19, §16).
apply 'restore-recomposes-from-the-live-plan' "$RUNTIME" \
'    suspend fun restoreSession(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        success(
            sessionRepository.sessionById(sessionId)
                ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        )
    }' \
'    suspend fun restoreSession(sessionId: SessionId): SessionRuntimeResult<WorkoutSession> = sessionOutcome {
        val stored = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        val live = scheduleRepository.slotById(stored.slotId)!!
        val revision = planRepository.revisionById(live.revisionId)!!
        val day = revision.days.first { it.programDayId == live.programDayId }
        val adjustments = standingAdjustments(adaptiveRepository.adjustmentsOf(live.slotId))
        success(
            stored.copy(
                snapshot = stored.snapshot.copy(
                    workout = presentedWorkout(day, live, adjustments, stored.snapshot.capturedAt)
                )
            )
        )
    }'
run_one 'a session is re-explained by the current plan and the current adjustments (§19, §16)' yes
restore 'restore-recomposes-from-the-live-plan' "$RUNTIME"

# 2. BACK DOES NOT CANCEL: opening a workout is a read.
apply 'opening-a-workout-cancels-it' "$RUNTIME" \
'        success(
            sessionRepository.sessionById(sessionId)
                ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        )' \
'        val stored = sessionRepository.sessionById(sessionId)
            ?: return@sessionOutcome refused(SessionRefusal.SessionNotFound(sessionId))
        val abandoned = stored.copy(status = SessionStatus.CANCELLED, finishedAt = clock.now())
        inTransaction { sessionRepository.recordSessionOutcome(abandoned) }
        success(requireStored(sessionId))'
run_one 'leaving the workout screen cancels the attempt (§19: Back does not cancel)' yes
restore 'opening-a-workout-cancels-it' "$RUNTIME"

# 3. THE OCCUPANCY RULE IS THE STATEMENT'S PREDICATE: the conditional insert is what refuses the second
# attempt at one slot, inside the transaction that would have written it (§19, §27).
apply 'the-occupancy-predicate-is-dropped' "$DAO" \
'INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`, `finishedAt`) SELECT :sessionId, :slotId, :programId, :revisionId, :status, :startedAt, :finishedAt WHERE NOT EXISTS (SELECT 1 FROM `workout_session` WHERE `slotId` = :occupiedSlotId AND `status` = :occupiedStatus)' \
'INSERT INTO `workout_session` (`sessionId`, `slotId`, `programId`, `revisionId`, `status`, `startedAt`, `finishedAt`) SELECT :sessionId, :slotId, :programId, :revisionId, :status, :startedAt, :finishedAt WHERE :occupiedSlotId IS NOT NULL AND :occupiedStatus IS NOT NULL'
run_one 'two attempts on one slot are both in progress (§19: at most one)' yes
restore 'the-occupancy-predicate-is-dropped' "$DAO"

# 4. THE REFUSED WRITE IS REPORTED: a start that stored nothing must not be treated as a stored one.
apply 'the-refused-write-is-treated-as-stored' "$REPOSITORY" \
'            if (sessionDao.changedRowCount() == 0) throw SessionAlreadyInProgress(session.slotId)' \
'            sessionDao.changedRowCount()'
run_one 'a refused start is reported as one that happened (§19, §28)' yes
restore 'the-refused-write-is-treated-as-stored' "$REPOSITORY"

# 5. START IS ONE UNIT: the session, its complete snapshot and its occurrences land together or not at all.
apply 'start-is-not-one-unit' "$REPOSITORY" \
'        inTransaction {
            sessionDao.insertSessionIfSlotIsNotOccupied(' \
'        run {
            sessionDao.insertSessionIfSlotIsNotOccupied('
run_one 'a start that dies part-way leaves half a session behind (§27)' yes
restore 'start-is-not-one-unit' "$REPOSITORY"

# 6. CONFIRMED SETS AUTOSAVE (§27).
apply 'sets-are-not-autosaved' "$RUNTIME" \
'        sessionRepository.appendSet(
            sessionExerciseId = sessionExerciseId,
            set = SetResult(
                setLogId = SetLogId(idGenerator.newId()),
                setIndex = occurrence.completedSetCount + 1,
                completedReps = completedReps,
                durationSeconds = durationSeconds,
                performedAt = clock.now()
            )
        )
        success(requireStored(sessionId))' \
'        success(requireStored(sessionId))'
run_one 'a confirmed set is not stored (§27: SetLog)' yes
restore 'sets-are-not-autosaved' "$RUNTIME"

# 7. A SET IS LOGGED IN THE UNIT ITS PRESCRIPTION WAS WRITTEN IN, and a set that was not performed is not
# logged at all (§10, §12).
apply 'a-set-outside-its-prescription-is-logged-anyway' "$RUNTIME" \
'        if (!occurrence.isLoggedIn(completedReps, durationSeconds)) {
            return@sessionOutcome refused(
                SessionRefusal.SetIsNotInThePrescribedUnit(
                    sessionExerciseId = sessionExerciseId,
                    dimension = occurrence.prescription.dimension,
                    completedReps = completedReps,
                    durationSeconds = durationSeconds
                )
            )
        }
' \
''
run_one 'a set of zero work, or of the wrong unit, is logged (§10, §12)' yes
restore 'a-set-outside-its-prescription-is-logged-anyway' "$RUNTIME"

# 8. THE POSITION OF A SET IS THE STORED ROWS' + 1, so the stored sets are 1..n with no gap.
apply 'every-set-takes-the-first-position' "$RUNTIME" \
'                setIndex = occurrence.completedSetCount + 1,' \
'                setIndex = 1,'
run_one 'a confirmed set is not given the next position (§19: sets accumulate 1..n)' yes
restore 'every-set-takes-the-first-position' "$RUNTIME"

# 9. CANCELLED IS NOT COMPLETED (§19).
apply 'cancel-completes-the-attempt' "$RUNTIME" \
'        val cancelled = session.copy(status = SessionStatus.CANCELLED, finishedAt = clock.now())' \
'        val cancelled = session.copy(status = SessionStatus.COMPLETED, finishedAt = clock.now())'
run_one 'a cancelled attempt is recorded as a completed one (§19)' yes
restore 'cancel-completes-the-attempt' "$RUNTIME"

# 10. FINISH TAKES THE OPPORTUNITY: the completion writes the session *and* the slot (§27).
apply 'finish-leaves-the-opportunity-open' "$RUNTIME" \
'            sessionRepository.finishSession(completed, completedSlot)' \
'            sessionRepository.recordSessionOutcome(completed)'
run_one 'a completed workout leaves its opportunity open (§27, §20)' yes
restore 'finish-leaves-the-opportunity-open' "$RUNTIME"

# 11. COMPLETION IS ONE UNIT: the session, the slot and the adaptive half land together or not at all (§27).
apply 'the-completion-is-split' "$RUNTIME" \
'        inTransaction {
            sessionRepository.finishSession(completed, completedSlot)
            decided?.let { adaptiveRepository.persistDecision(it.decision, it.adjustment) }
        }' \
'        sessionRepository.finishSession(completed, completedSlot)
        decided?.let { adaptiveRepository.persistDecision(it.decision, it.adjustment) }'
run_one 'a completion that dies leaves a completed session and no adaptive record (§27)' yes
restore 'the-completion-is-split' "$RUNTIME"

# 12. THE SNAPSHOT CAPTURES THE ADJUSTMENTS THAT STAND WHEN IT IS TAKEN (§16).
apply 'the-start-captures-no-adjustment' "$RUNTIME" \
'        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(slotId))' \
'        val standing = emptyList<com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment>()'
run_one 'an adjustment in effect is not applied and not captured (§16)' yes
restore 'the-start-captures-no-adjustment' "$RUNTIME"

# 13. AN UNCOMPOSABLE ADJUSTMENT IS REFUSED, never dropped (§16: a presentation is the revision plus the
# changes that stand for it).
apply 'an-invalid-adjustment-is-silently-dropped' "$RUNTIME" \
'        val unusable = standing.firstOrNull { adjustment ->
            day.exercises.none { it.programExerciseId == adjustment.after.programExerciseId }
        }
        if (unusable != null) {
            return@sessionOutcome refused(
                SessionRefusal.StoredAdjustmentIsNotOfThisRevision(
                    slotId = slotId,
                    adjustmentId = unusable.adjustmentId,
                    programExerciseId = unusable.after.programExerciseId
                )
            )
        }' \
'        val usable = standing.filter { adjustment ->
            day.exercises.any { it.programExerciseId == adjustment.after.programExerciseId }
        }'
substitute "$RUNTIME" \
'        val workout = presentedWorkout(day, slot, standing, startedAt)' \
'        val workout = presentedWorkout(day, slot, usable, startedAt)'
run_one 'a stored change the revision cannot present is silently skipped (§16)' yes
restore 'an-invalid-adjustment-is-silently-dropped' "$RUNTIME"

# 14. A COMPLETED OR WITHDRAWN OPPORTUNITY IS NOT STARTED (§19, §20).
apply 'a-finished-or-withdrawn-opportunity-is-started' "$RUNTIME" \
'        SlotStatus.PLANNED, SlotStatus.MISSED -> null
        SlotStatus.COMPLETED -> SessionRefusal.SlotIsAlreadyCompleted(slotId)
        SlotStatus.SUPERSEDED -> SessionRefusal.SlotIsSuperseded(slotId)' \
'        SlotStatus.PLANNED, SlotStatus.MISSED, SlotStatus.COMPLETED, SlotStatus.SUPERSEDED -> null'
run_one 'an opportunity that was taken, or withdrawn, is attempted anyway (§19, §20)' yes
restore 'a-finished-or-withdrawn-opportunity-is-started' "$RUNTIME"

# 15. A REST DAY PRESENTS NOTHING, so there is no workout to start (§20).
apply 'a-rest-day-is-started-with-an-empty-presentation' "$RUNTIME" \
'        if (day.exercises.isEmpty()) {
            return@sessionOutcome refused(SessionRefusal.PlanDayPresentsNothing(slotId, day.programDayId))
        }
' \
''
run_one 'a rest day is started as a workout with nothing in it (§20)' yes
restore 'a-rest-day-is-started-with-an-empty-presentation' "$RUNTIME"

# 16. THE IDENTITY TRIPLE IS CHECKED: a slot's revision must be a revision of the slot's own Program.
apply 'the-revisions-program-is-not-checked' "$RUNTIME" \
'        if (revision.programId != slot.programId) {
            return@sessionOutcome refused(
                SessionRefusal.RevisionIsOfAnotherProgram(
                    revisionId = slot.revisionId,
                    programId = slot.programId,
                    ownerProgramId = revision.programId
                )
            )
        }
' \
''
run_one 'a slot whose revision belongs to another Program is started (§19)' yes
restore 'the-revisions-program-is-not-checked' "$RUNTIME"

# 17. A DECISION BELONGS TO THE COMPLETION IT IS STORED WITH (§16, §27).
apply 'a-decision-about-another-opportunity-is-accepted' "$RUNTIME" \
'            is AdaptiveCompletion.Decided -> {
                val decision = adaptive.decision
                if (
                    decision.programId != session.programId ||
                    decision.revisionId != session.revisionId ||
                    decision.slotId != session.slotId
                ) {
                    return@sessionOutcome refused(
                        SessionRefusal.AdaptiveDecisionIsOfAnotherOpportunity(
                            sessionId = sessionId,
                            programId = session.programId,
                            revisionId = session.revisionId,
                            slotId = session.slotId,
                            decisionProgramId = decision.programId,
                            decisionRevisionId = decision.revisionId,
                            decisionSlotId = decision.slotId
                        )
                    )
                }
                adaptive
            }' \
'            is AdaptiveCompletion.Decided -> adaptive'
run_one "a decision about another opportunity is stored as this completion's own (§16, §27)" yes
restore 'a-decision-about-another-opportunity-is-accepted' "$RUNTIME"

# 18. Control: the un-mutated tree must stay GREEN (the suites are not failing for nothing).
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
echo "TWO CLAIMS ARE EXPRESSED STRUCTURALLY AS WELL AS BEHAVIOURALLY, and it is worth saying so:"
echo "  * the occupancy rule's *mechanism* is pinned by ProgramDataAccessArchitectureTest, which asserts"
echo "    the conditional insert's table, columns, predicate and the SQLite count that reports its effect —"
echo "    mutation 3 therefore fails that suite and the repository suite as well as the runtime's."
echo "  * 'the runtime takes no live plan and no live adjustment into an existing session' is mutation 1,"
echo "    and it is caught by the domain-equivalence assertions rather than by a source scan: the restored"
echo "    value is compared to the snapshot the start captured, byte for byte."
