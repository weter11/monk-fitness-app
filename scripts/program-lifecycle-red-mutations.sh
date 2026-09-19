#!/usr/bin/env bash
# §30 step 5 — the deliberate RED mutations.
#
# Each block applies one mutation to a production file, runs the focused lifecycle suite, and records
# whether the suite caught it. A rule whose mutation does NOT fail a test is a rule the suite does not
# prove, so this file is the evidence that the mandated rules are pinned by tests rather than by prose.
#
# Usage: bash scripts/program-lifecycle-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME) already exported.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()

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
  out="$(./gradlew --offline :app:testDebugUnitTest --tests "com.monkfitness.app.domain.program.*" --rerun-tasks 2>&1 | tail -6)"
  local caught="no"
  echo "$out" | grep -q "BUILD FAILED" && caught="yes"
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

POLICY="app/src/main/java/com/monkfitness/app/domain/program/ProgramLifecyclePolicy.kt"
SERVICE="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramLifecycleService.kt"

echo "== RED mutations: each must FAIL the suite (a rule is only proven if a break is caught) =="

# 1. A planned start date must never start a Program. START must be legal only from NOT_STARTED.
apply "START-allowed-from-any-status" "$POLICY" \
  "ProgramTransition.START -> from == LifecycleStatus.NOT_STARTED" \
  "ProgramTransition.START -> true"
run_one "START legal from any status (planned date could start a Program)" yes
restore "START-allowed-from-any-status" "$POLICY"

# 2. COMPLETED is terminal. Allow RESUME from COMPLETED.
apply "RESUME-from-COMPLETED" "$POLICY" \
  "ProgramTransition.RESUME -> from == LifecycleStatus.PAUSED" \
  "ProgramTransition.RESUME -> from == LifecycleStatus.PAUSED || from == LifecycleStatus.COMPLETED"
run_one "RESUME legal from COMPLETED (terminal broken)" yes
restore "RESUME-from-COMPLETED" "$POLICY"

# 3. actualStartDate is set only on Start. Let PAUSE stamp it too.
apply "PAUSE-stamps-actualStart" "$POLICY" \
  "ProgramTransition.PAUSE -> copy(lifecycleStatus = LifecycleStatus.PAUSED, updatedAt = at)" \
  "ProgramTransition.PAUSE -> copy(lifecycleStatus = LifecycleStatus.PAUSED, updatedAt = at, actualStartDate = at)"
run_one "PAUSE overwrites actualStartDate (factual start broken)" yes
restore "PAUSE-stamps-actualStart" "$POLICY"

# 4. Archive is not a lifecycle state: archiving must not move the lifecycle.
apply "archive-moves-lifecycle" "$POLICY" \
  "ProgramTransition.ARCHIVE -> copy(archivedAt = at, updatedAt = at)" \
  "ProgramTransition.ARCHIVE -> copy(archivedAt = at, updatedAt = at, lifecycleStatus = LifecycleStatus.COMPLETED)"
run_one "archiving moves the lifecycle (archive is not a state, §3)" yes
restore "archive-moves-lifecycle" "$POLICY"

# 5. Only one selected Program at a time. Keep the previous selection instead of replacing it.
apply "selection-not-mutually-exclusive" "$SERVICE" \
  "        val state = appStateRepository.state() ?: AppState()
        val updated = state.copy(selectedProgramId = programId)
        appStateRepository.save(updated)
        updated" \
  "        val state = appStateRepository.state() ?: AppState()
        val updated = state.copy(selectedProgramId = state.selectedProgramId ?: programId)
        appStateRepository.save(updated)
        updated"
run_one "select keeps the old selection (mutual exclusivity broken)" yes
restore "selection-not-mutually-exclusive" "$SERVICE"

# 6. Deleting the selected Program must select the Standard Program, never clear the selection.
apply "delete-clears-selection" "$SERVICE" \
  "                    moveSelectionToStandard(programId)" \
  "                    appStateRepository.save(state!!.copy(selectedProgramId = null))"
run_one "delete clears the selection instead of the Standard fallback" yes
restore "delete-clears-selection" "$SERVICE"

# 7. A Program with an IN_PROGRESS session must not be deletable.
apply "delete-ignores-in-progress-session" "$SERVICE" \
  ".any { it.status == SessionStatus.IN_PROGRESS }" \
  ".any { false }"
run_one "delete allowed with an IN_PROGRESS session" yes
restore "delete-ignores-in-progress-session" "$SERVICE"

# 8. The Standard Program must not be deletable.
apply "standard-program-deletable" "$SERVICE" \
  "        if (programId == standardProgramId) {
            return ProgramOperationResult.Refused(programId, StandardProgramCannotBeDeleted)
        }" \
  "        if (false) {
            return ProgramOperationResult.Refused(programId, StandardProgramCannotBeDeleted)
        }"
run_one "the built-in Program is deletable" yes
restore "standard-program-deletable" "$SERVICE"

# 9. The Standard Program must not be editable directly.
apply "standard-program-editable" "$SERVICE" \
  "        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)" \
  "        if (false) throw StandardProgramProtected(program.programId)"
run_one "the built-in Program is directly editable" yes
restore "standard-program-editable" "$SERVICE"

# 10. No revision for lifecycle changes. Make a pause save a new revision.
apply "pause-creates-revision" "$SERVICE" \
  "                programRepository.updateProgram(updated)
                updated" \
  "                val r = programRepository.programWithCurrentRevision(programId)!!.currentRevision
                planRepository.saveNewRevision(
                    r.copy(revisionId = com.monkfitness.app.domain.common.RevisionId(idGenerator.newId()),
                           revisionNumber = r.revisionNumber + 1),
                    at
                )
                programRepository.updateProgram(updated)
                updated"
run_one "a pause creates a revision (§6 broken)" yes
restore "pause-creates-revision" "$SERVICE"

# 11. A copy must be a user Program, not a built-in one.
apply "copy-inherits-built-in-source" "$SERVICE" \
  "            source = ProgramSource.USER," \
  "            source = source.program.source,"
run_one "a copy keeps the built-in source (§4 broken)" yes
restore "copy-inherits-built-in-source" "$SERVICE"

# 12. A copy must not share the source's plan rows: it needs fresh day ids.
apply "copy-shares-plan-rows" "$SERVICE" \
  "                    programDayId = com.monkfitness.app.domain.common.ProgramDayId(idGenerator.newId())," \
  "                    programDayId = day.programDayId,"
run_one "a copy reuses the source's day ids (§6 plan sharing)" yes
restore "copy-shares-plan-rows" "$SERVICE"

# 13. Control: the un-mutated tree must stay GREEN (the suite is not failing for nothing).
run_one "unmutated tree stays GREEN (control)" no

echo
echo "== summary: $PASS caught, $FAIL missed =="
if [ "$FAIL" -gt 0 ]; then
  echo "MISSED: ${FAILED_MUTATIONS[*]}"
  exit 1
fi
echo "every mandated rule is proven by a failing test when broken."
