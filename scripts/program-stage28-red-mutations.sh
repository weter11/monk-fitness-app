#!/bin/bash
# RED mutation suite — P28 (generation user exercise preferences).
#
# The contract every stage owes:
#   control run     must be GREEN  (nothing mutated)
#   every mutation  must be RED    (its owning suite fails)
#   every source    restored byte-identically, and the tree still clean afterwards
#
# Two rules this script holds itself to, both learned the hard way:
#
#   * a **compile error is not a catch**. Every mutation below is a PLAUSIBLE implementation — something a
#     well-meaning later session might actually write — so the oracle has to discriminate on behaviour. A
#     row that fails only because it stopped compiling proves nothing and is charged as a missed row, not a
#     caught one. That is why `check_red` inspects the run log for a Kotlin error before accepting a RED.
#
#   * a mutation that **does not apply** aborts the run rather than scoring a verdict. A no-op mutation
#     reads as a hole in the oracle and sends the next reader hunting for a hole that is not there.
#
# Every row below targets a real P28 invariant, and each is aimed at the layer that DECIDES it — the
# domain value, the persistence mapper, the context source, the transfer layer, or the UI surface.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/red-mutations-p28
rm -rf "$WORK" && mkdir -p "$WORK"

MAIN=app/src/main/java/com/monkfitness/app

PREFERENCE="$MAIN/domain/program/ExercisePreference.kt"
MAPPERS="$MAIN/data/mapper/PlanMappers.kt"
CONTEXT="$MAIN/domain/usecase/ProgramGenerationContext.kt"
CONTROLLER="$MAIN/ui/programs/ProgramsController.kt"
SCREEN="$MAIN/ui/screens/ProgramEditorScreen.kt"
JSON="$MAIN/domain/program/transfer/ProgramTransferJson.kt"
READER="$MAIN/domain/program/transfer/ProgramTransferReader.kt"
FORMAT="$MAIN/domain/program/transfer/ProgramTransferFormat.kt"
DOCUMENT="$MAIN/domain/program/transfer/ProgramTransferDocument.kt"
STRUCTURE="$MAIN/domain/program/ProgramStructure.kt"
DATABASE="$MAIN/data/local/AppDatabase.kt"

# EVERY file any mutation touches — including one that only gains an inserted line. A file missing from
# this list keeps its mutation, and the NEXT run's control then fails with a real-looking compile error, so a
# contaminated baseline reads as a broken one.
FILES=(
    "$PREFERENCE"
    "$MAPPERS"
    "$CONTEXT"
    "$CONTROLLER"
    "$SCREEN"
    "$JSON"
    "$READER"
    "$FORMAT"
    "$DOCUMENT"
    "$STRUCTURE"
    "$DATABASE"
)

# The suite that OWNS each rule. A mutation is only worth writing against the suite that owns the rule it
# breaks: the stage's own behavioural suites for a semantic fabrication, and the architecture gate for a
# structural one.
VALUE="com.monkfitness.app.domain.program.ExercisePreferenceTest"
PRECEDENCE="com.monkfitness.app.domain.program.generated.ExercisePreferencePlannerPrecedenceTest"
STORAGE="com.monkfitness.app.domain.usecase.ProgramPreferenceStorageTest"
UI="com.monkfitness.app.ui.programs.ProgramsExercisePreferencesTest"
GATE="com.monkfitness.app.ui.programs.ExercisePreferenceArchitectureTest"
TRANSFER="com.monkfitness.app.domain.program.transfer.ProgramTransferPreferenceTest"
SCHEMA="com.monkfitness.app.data.local.ProgramSchemaTest"
CONTEXT_GATE="com.monkfitness.app.domain.usecase.ProgramGenerationContextArchitectureTest"
SNAPSHOT="com.monkfitness.app.domain.usecase.ProgramGenerationContextSnapshotTest"

# ---- snapshot every source a mutation touches ----------------------------------------------------
: > "$WORK/before.md5"
for f in "${FILES[@]}"; do
    cp "$f" "$WORK/$(basename "$f").orig"
    md5sum "$f" >> "$WORK/before.md5"
done

run_suite() {
    # Cleared before EVERY invocation: this project's KSP incremental cache corrupts under many rapid
    # recompiles of the same files, and the failure looks exactly like a source error.
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest --tests "$1" > "$WORK/last-run.log" 2>&1
    return $?
}

restore_all() {
    for f in "${FILES[@]}"; do
        cp "$WORK/$(basename "$f").orig" "$f"
    done
}

# mutate <file> <python-body>  — the body receives the file text as `t` and must assign it back.
mutate() {
    python3 - "$1" "$2" <<'MUTATE_BODY'
import io, sys
path, body = sys.argv[1], sys.argv[2]
before = io.open(path, encoding="utf-8").read()
g = {"t": before, "io": io}
exec(body, g)
if g["t"] == before:
    sys.stderr.write("MUTATION DID NOT APPLY to %s\n" % path)
    sys.exit(9)
io.open(path, "w", encoding="utf-8").write(g["t"])
MUTATE_BODY
    if [ $? -ne 0 ]; then
        echo "  ABORT: a mutation did not apply (see above)"
        exit 1
    fi
}

# True when the last run failed to COMPILE rather than failed a test. A compile failure is never a catch.
compiled_failure() {
    grep -qE "^e: file:///.*\.kt:[0-9]+" "$WORK/last-run.log"
}

PASS=0
FAIL=0
NOTACATCH=0
declare -a RESULTS

# ---- the control: the same suites, nothing mutated -----------------------------------------------
echo "=================== control ==================="
CONTROL_OK=1
for suite in "$VALUE" "$PRECEDENCE" "$STORAGE" "$UI" "$GATE" "$TRANSFER" "$SCHEMA" "$CONTEXT_GATE" "$SNAPSHOT"; do
    if run_suite "$suite"; then
        echo "  GREEN: $suite"
    else
        echo "  NOT GREEN: $suite"
        CONTROL_OK=0
    fi
done
if [ "$CONTROL_OK" -ne 1 ]; then
    echo "control is NOT green — the baseline is broken, so no verdict below means anything"
    tail -40 "$WORK/last-run.log"
    exit 1
fi
echo "control GREEN"

# check_red <label> <suite>
check_red() {
    local label="$1" suite="$2"
    run_suite "$suite"
    if [ $? -eq 0 ]; then
        echo "  MISSED: $label  (suite stayed GREEN — the oracle does not catch it)"
        RESULTS+=("$label MISSED")
        FAIL=$((FAIL + 1))
    elif compiled_failure; then
        echo "  NOT A CATCH: $label  (the tree stopped COMPILING — that is not behavioural evidence)"
        RESULTS+=("$label NOT-A-CATCH")
        NOTACATCH=$((NOTACATCH + 1))
        FAIL=$((FAIL + 1))
    else
        echo "  caught: $label"
        RESULTS+=("$label caught")
        PASS=$((PASS + 1))
    fi
    restore_all
}

# 1. the user's order is silently lost — the mapper sorts what the user ranked.
echo "=================== 1. the preference order is silently lost ==================="
mutate "$MAPPERS" '
old = "    val ids = stored.split(PREFERENCE_SEPARATOR).map { it.trim() }"
new = "    val ids = stored.split(PREFERENCE_SEPARATOR).map { it.trim() }.sorted()"
assert old in t
t = t.replace(old, new)
'
check_red "1 the preference order is silently lost" "$STORAGE"

# 2. duplicate entries accepted — a preference with two answers at one position.
echo "=================== 2. a duplicate preference is accepted ==================="
mutate "$PREFERENCE" '
old = "        require(exerciseIds.distinct().size == exerciseIds.size) {"
new = "        if (false) require(exerciseIds.distinct().size == exerciseIds.size) {"
assert old in t
t = t.replace(old, new)
'
check_red "2 a duplicate preference is accepted" "$VALUE"

# 3. an unknown exercise id accepted by the domain value.
echo "=================== 3. a blank exercise id is accepted ==================="
mutate "$PREFERENCE" '
old = "        require(exerciseIds.none { it.isBlank() }) {"
new = "        if (false) require(exerciseIds.none { it.isBlank() }) {"
assert old in t
t = t.replace(old, new)
'
check_red "3 a blank exercise id is accepted" "$VALUE"

# 4. an unknown catalogue id accepted by the controller — the add path's only guard.
echo "=================== 4. an unknown exercise id is accepted by the add path ==================="
mutate "$CONTROLLER" '
old = "        if (mutableState.value.exerciseOptions.none { option -> option.exerciseId == exerciseId }) return"
new = "        // the catalogue check removed: any id may now be preferred"
assert old in t
t = t.replace(old, new)
'
check_red "4 an unknown exercise id is accepted" "$UI"

# 5. cross-Program leakage — the stored preference is read without the Program scope.
# The ids come back prefixed with the revision rather than from this row's own column: a value belonging to
# one revision bleeding into another IS the cross-Program leak at the storage layer, and unlike a branch
# nothing reaches, the mapper really produces this value on every read.
echo "=================== 5. another Programs preference is observed ==================="
mutate "$MAPPERS" '
old = "private fun preferenceOf(revision: ProgramRevisionEntity): ExercisePreference =\n    ExercisePreference(preferredIdsOf(revision))"
new = "private fun preferenceOf(revision: ProgramRevisionEntity): ExercisePreference =\n    ExercisePreference(preferredIdsOf(revision).map { id -> revision.revisionId + \"-\" + id })"
assert old in t
t = t.replace(old, new)
'
check_red "5 another Programs preference is observed" "$STORAGE"

# 6. absence fabricated into a ranking — NULL becomes a default order.
echo "=================== 6. an absent preference becomes a ranking ==================="
mutate "$MAPPERS" '
old = "private fun preferenceOf(revision: ProgramRevisionEntity): ExercisePreference =\n    ExercisePreference(preferredIdsOf(revision))"
new = "private fun preferenceOf(revision: ProgramRevisionEntity): ExercisePreference =\n    ExercisePreference(\n        preferredIdsOf(revision).ifEmpty { listOf(\"core-plank\", \"core-hollow-hold\") }\n    )"
assert old in t
t = t.replace(old, new)
'
check_red "6 an absent preference becomes a ranking" "$STORAGE"

# 7. the migration gains a DEFAULT, writing a preference no user ever stated.
echo "=================== 7. the migration writes a default ranking ==================="
mutate "$DATABASE" "
old = 'ADD COLUMN ' + chr(96) + 'preferredExerciseIds' + chr(96) + ' TEXT' + chr(34)
new = 'ADD COLUMN ' + chr(96) + 'preferredExerciseIds' + chr(96) + ' TEXT DEFAULT ' + chr(39)*2 + chr(34)
assert old in t, 'anchor not found'
t = t.replace(old, new)
"
check_red "7 the migration writes a default ranking" "$SCHEMA"

# 8. the stored preference never reaches the request — the whole point of the stage.
echo "=================== 8. the preference is not forwarded to the request ==================="
mutate "$CONTEXT" '
old = "        val preferred = draft.preferredExercises.exerciseIds"
new = "        val preferred = emptyList<String>()"
assert old in t
t = t.replace(old, new)
'
check_red "8 the preference is not forwarded to the request" "$STORAGE"

# 9. Generate and Preview read different snapshots — the context is read per entry point.
echo "=================== 9. the context is read a second time ==================="
mutate "$CONTEXT" '
# A second READ of the same history inside one pass — the snapshot rule broken at its source: two reads
# can see two different states, so the preferences the request was built from need not be the ones the
# operation acts on.
old = "        val performed = recentExerciseIdsOf(sessions.sessionsOfProgram(programId))"
new = "        sessions.sessionsOfProgram(programId)\n        val performed = recentExerciseIdsOf(sessions.sessionsOfProgram(programId))"
assert old in t
t = t.replace(old, new)
'
check_red "9 the context is read a second time" "$STORAGE"

# 10. the preference stops outranking the selector's own comparison.
echo "=================== 10. the user preference stops winning in the selector ==================="
mutate "$PREFERENCE" '
old = "    fun preferring(exerciseId: String): ExercisePreference =\n        if (exerciseId in exerciseIds) this else ExercisePreference(exerciseIds + exerciseId)"
new = "    fun preferring(exerciseId: String): ExercisePreference =\n        if (exerciseId in exerciseIds) this else ExercisePreference(listOf(exerciseId) + exerciseIds)"
assert old in t
t = t.replace(old, new)
'
check_red "10 the user preference stops winning" "$UI"

# 11. the preference is lost through the persistence round trip.
echo "=================== 11. the preference is lost on save ==================="
mutate "$MAPPERS" '
old = "    preferredExerciseIds = preferredExercises.storedExerciseIds()"
new = "    preferredExerciseIds = null"
assert old in t
t = t.replace(old, new)
'
check_red "11 the preference is lost on save" "$UI"

# 12. import/export silently drops the preference.
echo "=================== 12. import/export drops the preference ==================="
mutate "$JSON" '
old = "            \"preferredExercises\" to inlineArray(\n                revision.preferredExercises.map { exerciseId -> quoted(exerciseId) }\n            )"
new = "            // the preference is no longer written"
assert old in t
t = t.replace(old, new)
'
check_red "12 import/export drops the preference" "$TRANSFER"

# 13. a version-1 file is silently accepted although VERSION is 2.
echo "=================== 13. a version-1 file is accepted although VERSION is 2 ==================="
mutate "$READER" '
old = "        if (version.value != ProgramTransferFormat.VERSION) {\n            throw UnsupportedDocumentVersion(version.value)\n        }"
new = "        if (version.value != ProgramTransferFormat.VERSION && version.value > 1) {\n            throw UnsupportedDocumentVersion(version.value)\n        }"
assert old in t
t = t.replace(old, new)
'
check_red "13 a version-1 file is accepted" "$TRANSFER"

# 14. a repeated entry in an imported file is accepted.
echo "=================== 14. a repeated imported preference is accepted ==================="
mutate "$READER" '
old = "        val extra = if (blank.isEmpty() && repeated.isEmpty()) {"
new = "        val extra = if (blank.isEmpty()) {"
assert old in t
t = t.replace(old, new)
'
check_red "14 a repeated imported preference is accepted" "$TRANSFER"

# 15. the UI reaches persistence directly.
echo "=================== 15. the UI reaches persistence directly ==================="
mutate "$SCREEN" '
old = "import com.monkfitness.app.domain.program.ProgramSchedule"
new = "import com.monkfitness.app.data.local.AppDatabase\nimport com.monkfitness.app.domain.program.ProgramSchedule"
assert old in t
t = t.replace(old, new)
t = t.replace(
    "private fun PreferredExercisesSection(",
    "private fun PreferredExercisesSection(",
)
'
check_red "15 the UI reaches persistence directly" "$GATE"

# 16. the screen holds its own ranking instead of reading the drafts.
echo "=================== 16. the screen keeps its own copy of the ranking ==================="
mutate "$SCREEN" '
old = "    var choosing by remember { mutableStateOf(false) }"
new = "    var screenLocalRank by remember { mutableStateOf(preference.exerciseIds) }\n    var choosing by remember { mutableStateOf(false) }"
assert old in t
t = t.replace(old, new)
'
check_red "16 the screen keeps its own copy of the ranking" "$GATE"

# 17. the screen sorts the ranking on the users behalf.
echo "=================== 17. the screen sorts the ranking ==================="
mutate "$SCREEN" '
old = "    preference.exerciseIds.forEachIndexed { index, exerciseId ->"
new = "    preference.exerciseIds.sorted().forEachIndexed { index, exerciseId ->"
assert old in t
t = t.replace(old, new)
'
check_red "17 the screen sorts the ranking" "$GATE"

# 18. a second lifecycle — the aspect is DECLARED but no longer wired into the comparison, so a
#      preference-only save would create no Revision. Removing the enum member instead would leave the
#      controller's exhaustive `when` uncompilable, and a compile error is not evidence.
echo "=================== 18. the preference stops being structural ==================="
mutate "$STRUCTURE" '
old = "    ProgramStructureAspect.PREFERRED_EXERCISES -> preferredExercises != base.preferredExercises"
new = "    ProgramStructureAspect.PREFERRED_EXERCISES -> false"
assert old in t
t = t.replace(old, new)
'
check_red "18 the preference stops being structural" "$GATE"

# ---- the tree carries no mutation ----------------------------------------------------------------
echo "=================== the tree carries no mutation ==================="
LEFTOVER=0
for f in "${FILES[@]}"; do
    if ! cmp -s "$f" "$WORK/$(basename "$f").orig"; then
        echo "  LEFT MUTATED: $f"
        LEFTOVER=$((LEFTOVER + 1))
    fi
done
if [ "$LEFTOVER" -ne 0 ]; then
    echo "the working tree still carries $LEFTOVER mutation(s) — restoring"
    restore_all
    FAIL=$((FAIL + 1))
fi

# ---- restoration, from the repository root where the recorded paths resolve ---------------------
echo "=================== source restoration ==================="
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo "every mutated source restored byte-identically"
else
    echo "RESTORATION FAILED:"
    cat "$WORK/md5check.log"
    FAIL=$((FAIL + 1))
fi

echo
echo "=================== verdict ==================="
printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL   not-a-catch: $NOTACATCH"
if [ "$FAIL" -ne 0 ]; then
    echo "RED MUTATION SUITE FAILED"
    exit 1
fi
