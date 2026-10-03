#!/bin/bash
# RED mutation suite — P27 (production generation context).
#
# The contract every stage owes:
#   control run     must be GREEN  (nothing mutated)
#   every mutation  must be RED    (its owning suite fails)
#   every source    restored byte-identically, and the tree still clean afterwards
#
# The machinery below is the part that has been got wrong before; the mutations are the part that was
# written for this stage. Every row aims at a fabrication the brief names, and every replacement is a
# PLAUSIBLE implementation — something a well-meaning later session might actually write — so that the
# oracle is forced to discriminate rather than to fail on a compile error.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/red-mutations-p27
rm -rf "$WORK" && mkdir -p "$WORK"

MAIN=app/src/main/java/com/monkfitness/app

CONTEXT="$MAIN/domain/usecase/ProgramGenerationContext.kt"
SERVICE="$MAIN/domain/usecase/ProgramGenerationService.kt"
CONTAINER="$MAIN/di/AppContainer.kt"
GENERATED="$MAIN/domain/program/generated/GenerationRequest.kt"

# EVERY file any mutation touches — including any file that only gains an inserted line. A file missing
# from this list keeps its mutation, and the NEXT run's control then fails with a real-looking compile
# error, so a contaminated baseline reads as a broken one.
FILES=(
    "$CONTEXT"
    "$SERVICE"
    "$CONTAINER"
    "$GENERATED"
)

# The suite that owns each rule. A mutation is only worth writing against the suite that OWNS the rule it
# breaks: the stage's own behavioural suite for a semantic fabrication, and the architecture gate for a
# structural one.
BEHAVIOUR="com.monkfitness.app.domain.usecase.ProgramGenerationContextTest"
STORAGE="com.monkfitness.app.domain.usecase.ProgramGenerationContextStorageTest"
SNAPSHOT="com.monkfitness.app.domain.usecase.ProgramGenerationContextSnapshotTest"
GATE="com.monkfitness.app.domain.usecase.ProgramGenerationContextArchitectureTest"
PREVIEW="com.monkfitness.app.domain.usecase.ProgramGenerationPreviewServiceTest"

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
# A body whose replacement does not match changes nothing, and that is a hard failure here rather than a
# "missed" verdict: a no-op mutation reads as a hole in the oracle and sends the next reader hunting for
# one that is not there.
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

PASS=0
FAIL=0
declare -a RESULTS

# ---- the control: the same suites, nothing mutated -----------------------------------------------
echo "=================== control ==================="
CONTROL_OK=1
for suite in "$BEHAVIOUR" "$STORAGE" "$SNAPSHOT" "$GATE" "$PREVIEW"; do
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

# A mutation is only worth writing against the suite that OWNS the rule it breaks. The importing stage's
# own tests can keep passing, correctly, while a primitive it merely uses is mutated.
check_red() {
    local label="$1" suite="$2"
    if run_suite "$suite"; then
        echo "  MISSED: $label  (suite stayed GREEN — either the oracle does not catch it, or the mutation"
        echo "          is aimed at a layer the oracle does not read)"
        RESULTS+=("$label MISSED")
        FAIL=$((FAIL + 1))
    else
        echo "  caught: $label"
        RESULTS+=("$label caught")
        PASS=$((PASS + 1))
    fi
    restore_all
}

# ---- one mutation per rule named in the brief ----------------------------------------------------
# Aim each at the layer that DECLARES the fact the oracle reads.

# 1. remove Program scoping — the read is issued for a Program that is not the draft's own.
echo "=================== 1. remove Program scoping ==================="
mutate "$CONTEXT" '
old = "        val performed = recentExerciseIdsOf(sessions.sessionsOfProgram(programId))"
new = "        val performed = recentExerciseIdsOf(sessions.sessionsOfProgram(ProgramId(\"all-programs\")))"
assert old in t
t = t.replace(old, new)
'
check_red "1 remove Program scoping" "$BEHAVIOUR"

# 2. substitute another Program's history — the scoping exists but names the wrong Program.
echo "=================== 2. substitute another Program history ==================="
mutate "$CONTEXT" '
old = "sessions.sessionsOfProgram(programId))"
new = "sessions.sessionsOfProgram(programId).filter { it.programId != programId })"
assert old in t
t = t.replace(old, new, 1)
'
check_red "2 substitute another Program history" "$BEHAVIOUR"

# 3. turn missing into zero — an empty history becomes an explicit zero per focus.
echo "=================== 3. turn missing into zero ==================="
mutate "$CONTEXT" '
old = "        return if (performed.isEmpty()) {\n            GenerationPreferences.NONE\n        } else {"
new = "        return if (performed.isEmpty()) {\n            GenerationPreferences(\n                recentExposureByFocus = Focus.entries.associateWith { 0 },\n                recentLoadByFocus = Focus.entries.associateWith { 0 }\n            )\n        } else {"
assert old in t
t = t.replace(old, new)
t = t.replace(
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
    "import com.monkfitness.app.domain.program.Focus\nimport com.monkfitness.app.domain.program.ProgramEditorDraft",
)
'
check_red "3 turn missing into zero" "$GATE"

# 4. derive exposure from the workout count — the forbidden §8 substitution.
echo "=================== 4. derive exposure from the workout count ==================="
mutate "$CONTEXT" '
old = "            GenerationPreferences(recentExerciseIds = performed)"
new = ("            GenerationPreferences(\n"
       "                recentExerciseIds = performed,\n"
       "                recentExposureByFocus = Focus.entries.associateWith { sessions.size }\n"
       "            )")
assert old in t
t = t.replace(old, new)
t = t.replace(
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
    "import com.monkfitness.app.domain.program.Focus\nimport com.monkfitness.app.domain.program.ProgramEditorDraft",
)
'
check_red "4 derive exposure from the workout count" "$BEHAVIOUR"

# 5. derive load from repetitions — the cross-dimension conversion.
echo "=================== 5. derive load from repetitions ==================="
mutate "$CONTEXT" '
old = "            GenerationPreferences(recentExerciseIds = performed)"
new = ("            GenerationPreferences(\n"
       "                recentExerciseIds = performed,\n"
       "                recentLoadByFocus = Focus.entries.associateWith { focus ->\n"
       "                    performed.sumOf { sessions.count { s -> s.programId == focus } } * 10\n"
       "                }\n"
       "            )")
assert old in t
t = t.replace(old, new)
t = t.replace(
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
    "import com.monkfitness.app.domain.program.Focus\nimport com.monkfitness.app.domain.program.ProgramEditorDraft",
)
'
check_red "5 derive load from repetitions" "$BEHAVIOUR"

# 6. fabricate an adaptive preference out of the family's current exercise.
echo "=================== 6. fabricate an adaptive preference ==================="
mutate "$CONTEXT" '
old = "            GenerationPreferences(recentExerciseIds = performed)"
new = ("            GenerationPreferences(\n"
       "                recentExerciseIds = performed,\n"
       "                adaptivePreferredExerciseIds = familyStatesOfCurrentRevision(draft).mapNotNull {\n"
       "                    it.currentExerciseId\n"
       "                }\n"
       "            )")
assert old in t
t = t.replace(old, new)
t = t.replace(
    "    private fun recentExerciseIdsOf(",
    "    private fun familyStatesOfCurrentRevision(draft: ProgramEditorDraft): List<FamilyProgressionState> =\n"
    "        adaptiveRepository.familyStates(draft.baseRevisionId ?: RevisionId(\"none\"))\n\n"
    "    private fun recentExerciseIdsOf(",
)
t = t.replace(
    "import com.monkfitness.app.domain.common.ProgramId",
    "import com.monkfitness.app.domain.common.ProgramId\n"
    "import com.monkfitness.app.domain.common.RevisionId\n"
    "import com.monkfitness.app.data.repository.ProgramAdaptiveRepository\n"
    "import com.monkfitness.app.domain.adaptive.FamilyProgressionState",
)
t = t.replace(
    "class ProgramHistoryGenerationContext(\n    private val sessions: GenerationSessionHistory\n)",
    "class ProgramHistoryGenerationContext(\n"
    "    private val sessions: GenerationSessionHistory,\n"
    "    private val adaptiveRepository: ProgramAdaptiveRepository\n"
    ")",
)
'
check_red "6 fabricate an adaptive preference" "$GATE"

# 7. fabricate recovery from elapsed time — the heuristic the brief forbids.
echo "=================== 7. fabricate recovery from elapsed time ==================="
mutate "$CONTEXT" '
old = "            GenerationPreferences(recentExerciseIds = performed)"
new = ("            GenerationPreferences(\n"
       "                recentExerciseIds = performed,\n"
       "                recovery = hoursSinceLastSession(sessions)\n"
       "            )")
assert old in t
t = t.replace(old, new)
t = t.replace(
    "    private fun recentExerciseIdsOf(",
    "    private fun hoursSinceLastSession(sessions: List<WorkoutSession>): RecoveryContext {\n"
    "        val latest = sessions.maxOfOrNull { it.startedAt } ?: return RecoveryContext.UNKNOWN\n"
    "        val hours = java.time.Duration.between(latest, Instant.now()).toHours()\n"
    "        return if (hours >= 48) RecoveryContext.CAUTIOUS else RecoveryContext.FAVORABLE\n"
    "    }\n\n"
    "    private fun recentExerciseIdsOf(",
)
t = t.replace(
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
    "import com.monkfitness.app.domain.adaptive.RecoveryContext\n"
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
)
t = t.replace(
    "import com.monkfitness.app.domain.workout.WorkoutSession",
    "import com.monkfitness.app.domain.workout.WorkoutSession\nimport java.time.Instant",
)
'
check_red "7 fabricate recovery from elapsed time" "$GATE"

# 8. make Preview and Generate use different context — the second read inside the pass.
echo "=================== 8. Preview and Generate read the context differently ==================="
mutate "$SERVICE" '
old = "            planned(draft, availableEquipment, classified, context.preferencesFor(draft))"
new = ("            val first = context.preferencesFor(draft)\n"
       "            val second = context.preferencesFor(draft)\n"
       "            planned(draft, availableEquipment, classified, second)")
assert old in t
t = t.replace(old, new)
'
check_red "8 Preview and Generate read the context differently" "$SNAPSHOT"

# 9. add a repository/DAO dependency to the context source — a second path to the same facts.
echo "=================== 9. a repository dependency in the context source ==================="
mutate "$CONTEXT" '
old = "    private fun recentExerciseIdsOf(sessions: List<WorkoutSession>): List<String> = sessions"
new = ("    private fun recentExerciseIdsOf(sessions: List<WorkoutSession>): List<String> =\n"
       "        repository.overrideWithDirectDaoRead(sessions)")
assert old in t
t = t.replace(old, new)
t = t.replace(
    "class ProgramHistoryGenerationContext(\n    private val sessions: GenerationSessionHistory\n)",
    "class ProgramHistoryGenerationContext(\n"
    "    private val sessions: GenerationSessionHistory,\n"
    "    private val repository: com.monkfitness.app.data.local.WorkoutSessionDao\n"
    ")",
)
'
check_red "9 a repository dependency in the context source" "$GATE"

# 10. make the context source write — the persistence this stage explicitly does not add.
echo "=================== 10. the context source writes ==================="
mutate "$CONTEXT" '
old = "            GenerationPreferences(recentExerciseIds = performed)"
new = ("            GenerationPreferences(recentExerciseIds = performed).also {\n"
       "                recordedContexts += it\n"
       "            }")
assert old in t
t = t.replace(old, new)
t = t.replace(
    ") : GenerationContextSource {\n",
    ") : GenerationContextSource {\n\n"
    "    /** A generation pass leaves no trace; this counter would be one. */\n"
    "    private val recordedContexts = mutableListOf<GenerationPreferences>()\n",
)
'
check_red "10 the context source writes" "$GATE"

# 11. silently replace UNKNOWN with FAVORABLE — the absence turned into a claim.
echo "=================== 11. silently replace UNKNOWN with FAVORABLE ==================="
mutate "$CONTEXT" '
old = "        val programId = draft.programId ?: return GenerationPreferences.NONE"
new = "        val programId = draft.programId ?: return GenerationPreferences(recovery = RecoveryContext.FAVORABLE)"
assert old in t
t = t.replace(old, new)
t = t.replace(
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
    "import com.monkfitness.app.domain.adaptive.RecoveryContext\n"
    "import com.monkfitness.app.domain.program.ProgramEditorDraft",
)
'
check_red "11 silently replace UNKNOWN with FAVORABLE" "$BEHAVIOUR"

# 12. touch the pure generated package — the boundary §30 step 10 draws.
echo "=================== 12. the pure generated package changes ==================="
mutate "$GENERATED" '
old = "data class GenerationPreferences("
new = "data class GenerationPreferences(\n    val readContextFromStorage: Boolean = false,"
assert old in t
t = t.replace(old, new, 1)
'
check_red "12 the pure generated package changes" "$GATE"

# 13. add a second request-assembly path — how Preview and Generate come to disagree.
echo "=================== 13. a second request-assembly path ==================="
mutate "$SERVICE" '
old = "        val request = ProductionGenerationBoundary.generationRequest("
new = ("            if (draft.mode == com.monkfitness.app.domain.program.ProgramMode.GENERATED) {\n"
       "                ProductionGenerationBoundary.generationRequest(\n"
       "                    catalogue = classified,\n"
       "                    focus = draft.focus,\n"
       "                    schedule = draft.schedule,\n"
       "                    duration = draft.duration,\n"
       "                    availableEquipment = availableEquipment,\n"
       "                    preferences = GenerationPreferences.NONE\n"
       "                )\n"
       "            }\n"
       "            val request = ProductionGenerationBoundary.generationRequest(")
assert old in t
t = t.replace(old, new, 1)
'
check_red "13 a second request-assembly path" "$PREVIEW"

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

# ---- restoration, from the repository root where the recorded paths resolve ----------------------
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
echo "caught: $PASS   missed: $FAIL"
if [ "$FAIL" -ne 0 ]; then
    echo "RED MUTATION SUITE FAILED"
    exit 1
fi
echo "RED MUTATION SUITE PASSED: every mutation was caught and every source restored"