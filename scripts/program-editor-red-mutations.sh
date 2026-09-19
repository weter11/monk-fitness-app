#!/usr/bin/env bash
# §30 step 6 — the deliberate RED mutations of the Manual Editor.
#
# Each block applies one mutation to a production source, runs the focused editor suite, and records
# whether the suite caught it. A rule whose mutation does NOT fail a test is a rule the suite does not
# prove, so this file is the evidence that draft-first editing and the immutable-revision rule are
# pinned by tests rather than by prose.
#
# Usage: bash scripts/program-editor-red-mutations.sh
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

SERVICE="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramEditorService.kt"
STRUCTURE="app/src/main/java/com/monkfitness/app/domain/program/ProgramStructure.kt"
SOURCES=("$SERVICE" "$STRUCTURE")

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
      --tests "com.monkfitness.app.domain.program.ProgramEditorServiceTest" \
      --tests "com.monkfitness.app.domain.program.ProgramDraftEditorTest" \
      --tests "com.monkfitness.app.domain.program.ProgramDraftValidationTest" \
      --tests "com.monkfitness.app.domain.program.ProgramStructureTest" \
      --tests "com.monkfitness.app.domain.program.ProgramEditorArchitectureTest" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  # A mutation that does not compile is caught too: the suite cannot run against it.
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

echo "== RED mutations: each must FAIL the editor suite (a rule is only proven if a break is caught) =="

# 1. DRAFT-FIRST: opening a draft must not write to what is stored.
apply "edit-draft-writes-on-open" "$SERVICE" \
  "            draftOf(program, revision)
        }.rejecting()" \
  "            programRepository.updateProgram(program.copy(updatedAt = clock.now()))
            draftOf(program, revision)
        }.rejecting()"
run_one "opening an edit draft writes to the stored Program (§7 draft-first)" yes
restore "edit-draft-writes-on-open" "$SERVICE"

# 2. IMMUTABLE REVISION: a save creates a NEW revision, never re-identifying the old one.
apply "save-reuses-the-revision-identity" "$SERVICE" \
  "            revisionId = RevisionId(idGenerator.newId())," \
  "            revisionId = draft.baseRevisionId ?: RevisionId(idGenerator.newId()),"
run_one "a save reuses the revision identity it replaces (§6 immutability)" yes
restore "save-reuses-the-revision-identity" "$SERVICE"

# 3. ONE SAVE, AT MOST ONE REVISION: a second write of the same revision is not a second revision.
apply "save-writes-the-revision-twice" "$SERVICE" \
  "        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
        }" \
  "        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
            planRepository.saveNewRevision(revision, at)
        }"
run_one "one save writes more than one revision (§6: at most one)" yes
restore "save-writes-the-revision-twice" "$SERVICE"

# 4. DRAFT IDENTITY NEVER REACHES STORAGE: a save re-identifies every day and element.
apply "save-keeps-the-draft-plan-identities" "$SERVICE" \
  "                    programDayId = ProgramDayId(idGenerator.newId())," \
  "                    programDayId = day.programDayId,"
run_one "a new revision reuses the plan rows it replaces (§6, §23)" yes
restore "save-keeps-the-draft-plan-identities" "$SERVICE"

# 5. NO-OP SAVE CREATES NONE: a save whose plan did not change creates no revision.
apply "no-op-save-creates-a-revision" "$SERVICE" \
  "        if (draft.structure == current.structure) {" \
  "        if (false) {"
run_one "a no-op save creates a revision (§6: no-op Save creates none)" yes
restore "no-op-save-creates-a-revision" "$SERVICE"

# 6. RENAME CREATES NO REVISION: the Program's own facts are not structure (§6).
apply "rename-counts-as-a-structural-change" "$SERVICE" \
  "        if (draft.structure == current.structure) {" \
  "        if (draft.structure == current.structure && draft.name == program.name) {"
run_one "renaming saves a revision (§6: rename creates none)" yes
restore "rename-counts-as-a-structural-change" "$SERVICE"

# 7. A NO-OP SAVE WRITES NOTHING AT ALL: not even the updatedAt stamp.
apply "no-op-save-still-writes-the-row" "$SERVICE" \
  "            if (!factsChanged) {
                return ProgramSaveOutcome.NothingToChange(program, current.revisionId)
            }" \
  "            if (!factsChanged) {
                programRepository.updateProgram(program.copy(updatedAt = at))
                return ProgramSaveOutcome.NothingToChange(program, current.revisionId)
            }"
run_one "a save that changes nothing still writes the Program row (§6)" yes
restore "no-op-save-still-writes-the-row" "$SERVICE"

# 8. THE POINTER MOVES WITH THE REVISION: the facts write must not undo `saveNewRevision`.
apply "facts-write-reverts-the-pointer" "$SERVICE" \
  "        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
        }" \
  "        inTransaction {
            planRepository.saveNewRevision(revision, at)
            if (factsChanged) programRepository.updateProgram(facts)
        }"
run_one "the current-revision pointer ends up back on the superseded revision" yes
restore "facts-write-reverts-the-pointer" "$SERVICE"

# 9. FAILED SAVE IS ATOMIC: the rename must not land when the revision cannot be written.
apply "facts-write-outside-the-transaction" "$SERVICE" \
  "        inTransaction {
            if (factsChanged) programRepository.updateProgram(facts)
            planRepository.saveNewRevision(revision, at)
        }" \
  "        if (factsChanged) programRepository.updateProgram(facts)
        inTransaction {
            planRepository.saveNewRevision(revision, at)
        }"
run_one "a failed save leaves a renamed Program behind (§27 atomicity)" yes
restore "facts-write-outside-the-transaction" "$SERVICE"

# 10. INVALID DRAFTS ARE REJECTED BEFORE PERSISTENCE.
apply "validation-skipped-before-the-write" "$SERVICE" \
  "            if (!validation.isValid) throw DraftRejected(validation)" \
  "            if (false) throw DraftRejected(validation)"
run_one "an unfinished draft reaches the DAOs (§7 validate before Save)" yes
restore "validation-skipped-before-the-write" "$SERVICE"

# 11. STANDARD CANNOT BE EDITED IN PLACE (§4).
apply "standard-program-editable" "$SERVICE" \
  "        if (program.source.isBuiltIn) throw StandardProgramProtected(program.programId)" \
  "        if (false) throw StandardProgramProtected(program.programId)"
run_one "the built-in Program is editable in place (§4)" yes
restore "standard-program-editable" "$SERVICE"

# 12. A PRESCRIPTION CHANGE IS STRUCTURAL: §6 lists prescriptions.
apply "prescriptions-are-not-structure" "$STRUCTURE" \
  "    ProgramStructureAspect.PRESCRIPTIONS -> prescriptions != base.prescriptions" \
  "    ProgramStructureAspect.PRESCRIPTIONS -> false"
run_one "a prescription-only edit becomes a no-op (§6: prescriptions are structure)" yes
restore "prescriptions-are-not-structure" "$STRUCTURE"

# 13. Control: the un-mutated tree must stay GREEN (the suite is not failing for nothing).
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
echo "NOT EXPRESSIBLE AS A MUTATION, and therefore asserted structurally instead:"
echo "  * 'a save does not reconcile or modify scheduler slots' — the editor holds no schedule"
echo "    repository at all; ProgramEditorArchitectureTest reads its constructor's collaborators and"
echo "    the absence is the guarantee."
echo "  * 'the editor never mutates Exercise Library data' — the editor holds no library port and never"
echo "    resolves an exercise; the test drives a full create/edit/copy cycle and compares the app's own"
echo "    library through the generator's public API."
