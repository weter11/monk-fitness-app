#!/bin/bash
# RED mutation suite — P29 (adaptive generation history attribution).
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
# P29 has a third rule, and it is the one this stage exists to enforce. A mutation that *widens* the one
# approved exception to the generated package's freeze must still be a catch — and so must a mutation that
# changes any OTHER file in that package. Rows 17 and 18 exist only for that: without them the freeze could
# be widened and every remaining row would still pass.
#
# Every row below targets a real P29 invariant, and each is aimed at the layer that DECIDES it — the
# reconciler that preserves the fact, the mappers that persist it, the session boundary that freezes it, or
# the context source that reads it.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/red-mutations-p29
rm -rf "$WORK" && mkdir -p "$WORK"

MAIN=app/src/main/java/com/monkfitness/app

RECONCILER="$MAIN/domain/program/generated/PlanReconciler.kt"
PLANNER="$MAIN/domain/program/generated/GeneratedPlanner.kt"
SELECTOR="$MAIN/domain/program/generated/ExerciseSelector.kt"
POLICY="$MAIN/domain/program/generated/GenerationPolicy.kt"
REQUEST="$MAIN/domain/program/generated/GenerationRequest.kt"
PLAN="$MAIN/domain/program/generated/GeneratedPlan.kt"
FOCUS_PLANNER="$MAIN/domain/program/generated/FocusPlanner.kt"
EDITOR="$MAIN/domain/program/generated/ProgramGeneratedEditor.kt"
PROGRAM_EXERCISE="$MAIN/domain/program/ProgramExercise.kt"
EFFECTIVE="$MAIN/domain/workout/EffectiveWorkout.kt"
PRESENTATION="$MAIN/domain/adaptive/decision/SlotPresentation.kt"
ENGINE="$MAIN/domain/adaptive/engine/ProgramAdaptiveEngine.kt"
PLAN_MAPPERS="$MAIN/data/mapper/PlanMappers.kt"
SESSION_MAPPERS="$MAIN/data/mapper/SessionMappers.kt"
SNAPSHOT_ENTITY="$MAIN/data/model/SessionSnapshotExerciseEntity.kt"
SNAPSHOT_DAO="$MAIN/data/local/SessionSnapshotExerciseDao.kt"
DATABASE="$MAIN/data/local/AppDatabase.kt"
CONTEXT="$MAIN/domain/usecase/ProgramGenerationContext.kt"
SERVICE="$MAIN/domain/usecase/ProgramGenerationService.kt"

# EVERY file any mutation touches — including one that only gains an inserted line. A file missing from
# this list keeps its mutation, and the NEXT run's control then fails with a real-looking compile error, so a
# contaminated baseline reads as a broken one.
FILES=(
    "$RECONCILER"
    "$PLANNER"
    "$SELECTOR"
    "$POLICY"
    "$REQUEST"
    "$PLAN"
    "$FOCUS_PLANNER"
    "$EDITOR"
    "$PROGRAM_EXERCISE"
    "$EFFECTIVE"
    "$PRESENTATION"
    "$ENGINE"
    "$PLAN_MAPPERS"
    "$SESSION_MAPPERS"
    "$SNAPSHOT_ENTITY"
    "$SNAPSHOT_DAO"
    "$DATABASE"
    "$CONTEXT"
    "$SERVICE"
)

# The suite that OWNS each rule. A mutation is only worth writing against the suite that owns the rule it
# breaks: the stage's own behavioural suites for a semantic fabrication, and the architecture gate for a
# structural one.
FOCUS="com.monkfitness.app.domain.program.generated.AdaptiveFocusAttributionTest"
HISTORY="com.monkfitness.app.domain.usecase.AdaptiveHistoryContextTest"
STORAGE="com.monkfitness.app.domain.usecase.AdaptiveHistoryStorageTest"
GATE="com.monkfitness.app.domain.usecase.AdaptiveHistoryArchitectureTest"
SCHEMA="com.monkfitness.app.data.local.ProgramSchemaTest"
P27_GATE="com.monkfitness.app.domain.usecase.ProgramGenerationContextArchitectureTest"
SNAPSHOT="com.monkfitness.app.domain.usecase.ProgramGenerationContextSnapshotTest"
PLANNER_SUITE="com.monkfitness.app.domain.program.generated.ProgramGeneratedEditorTest"
RUNTIME="com.monkfitness.app.domain.usecase.SessionRuntimeTest"

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
for suite in "$FOCUS" "$HISTORY" "$STORAGE" "$GATE" "$SCHEMA" "$P27_GATE" "$SNAPSHOT" "$PLANNER_SUITE" "$RUNTIME"; do
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

# 1. the discarded fact is discarded again — the one line P29 added, removed. This is the stage's whole
#      existence: without it the focus exists only transiently and every downstream signal is empty.
echo "=================== 1. the reconciler stops preserving the focus ==================="
mutate "$RECONCILER" '
old = "        isPinned = false,\n        focus = focus\n    )"
new = "        isPinned = false\n    )"
assert old in t
t = t.replace(old, new)
'
check_red "1 the reconciler stops preserving the focus" "$FOCUS"

# 2. a secondary focus is recorded as the primary — the "one element per assigned focus" contract
#      collapses into "everything is the primary", which is plausible and wrong.
echo "=================== 2. a secondary focus is recorded as the primary ==================="
mutate "$RECONCILER" '
old = "        isPinned = false,\n        focus = focus\n    )"
new = "        isPinned = false,\n        focus = GeneratedSlotUnit.primaryOf(focus)\n    )"
assert old in t
# a plausible stand-in that does not need a new type: map every focus onto PUSH via the vocabulary order
t = t.replace(old, new)
t = t.replace("object PlanReconciler {", "object PlanReconciler {\n\n    private object GeneratedSlotUnit {\n        fun primaryOf(any: com.monkfitness.app.domain.program.Focus): com.monkfitness.app.domain.program.Focus =\n            com.monkfitness.app.domain.program.Focus.entries.first()\n    }\n")
'
check_red "2 a secondary focus is recorded as the primary" "$FOCUS"

# 3. the plan element's focus is never persisted — the column exists and is always NULL.
echo "=================== 3. the plan elements focus is not persisted ==================="
mutate "$PLAN_MAPPERS" '
old = "        isPinned = isPinned,\n        focus = focus?.name\n    )"
new = "        isPinned = isPinned\n    )"
assert old in t
t = t.replace(old, new)
'
check_red "3 the plan elements focus is not persisted" "$STORAGE"

# 4. the snapshot's focus is not persisted — the plan carries it but §19 freezes nothing, so history has
#      no focus to read.
echo "=================== 4. the snapshots focus is not persisted ==================="
mutate "$SESSION_MAPPERS" '
old = "            perSetTargets = element.prescription.perSetTargets,\n            focus = element.focus?.name\n        )"
new = "            perSetTargets = element.prescription.perSetTargets\n        )"
assert old in t
t = t.replace(old, new)
'
check_red "4 the snapshots focus is not persisted" "$STORAGE"

# 5. the current revision is consulted instead of the historical snapshot. Modelled on the real shape: the
#      context reads the live plan's focus for every occurrence rather than the one the session froze.
echo "=================== 5. the current revision is read instead of the snapshot ==================="
mutate "$CONTEXT" '
old = """        val focusByElement = session.snapshot.workout.exercises
            .mapNotNull { element -> element.focus?.let { focus -> element.programExerciseId to focus } }
            .toMap()"""
new = """        val focusByElement = session.snapshot.workout.exercises
            .mapNotNull { element ->
                CURRENT_REVISION_FOCUS[element.exerciseId]
                    ?.let { focus -> element.programExerciseId to focus }
            }
            .toMap()"""
assert old in t
t = t.replace(old, new)
# the fabricated "current revision" table: one focus per exercise, i.e. exactly the reconstruction
t = t.replace("class ProgramHistoryGenerationContext(", """private val CURRENT_REVISION_FOCUS: Map<String, com.monkfitness.app.domain.program.Focus> = mapOf(
    "pushups" to com.monkfitness.app.domain.program.Focus.PUSH,
    "pullups" to com.monkfitness.app.domain.program.Focus.PULL,
    "squats" to com.monkfitness.app.domain.program.Focus.LEGS
)

class ProgramHistoryGenerationContext(""")
'
check_red "5 the current revision is read instead of the snapshot" "$HISTORY"

# 6. the focus is reconstructed from the exercise catalogue — the algorithm P27 refused and P29 made
#      unnecessary. Uses the real classifier, so this is a mutation a later session could plausibly write.
echo "=================== 6. the focus is reconstructed from the catalogue ==================="
mutate "$CONTEXT" '
old = "                focusByElement[occurrence.programExerciseId]\n                    ?.let { focus -> focus to occurrence.results.size }"
new = """                val reconstructed = ProductionFocusClassification.focusesOf(occurrence.exerciseId)
                    ?.sortedBy { focus -> focus.ordinal }
                    ?.firstOrNull()
                (focusByElement[occurrence.programExerciseId] ?: reconstructed)
                    ?.let { focus -> focus to occurrence.results.size }"""
assert old in t
t = t.replace(old, new)
'
check_red "6 the focus is reconstructed from the catalogue" "$HISTORY"

# 7. workouts are counted instead of focus assignments — one count per session, not per occurrence.
echo "=================== 7. workouts are counted instead of assignments ==================="
mutate "$CONTEXT" '
old = """            .groupingBy { entry -> entry.first }
            .eachCount()"""
new = """            .groupingBy { entry -> entry.first }
            .eachCount()
            .mapValues { (_, count) -> (count + 1) / 2 }"""
assert old in t
t = t.replace(old, new)
'
check_red "7 workouts are counted instead of assignments" "$HISTORY"

# 8. repetitions are counted instead of confirmed sets — a cross-dimension conversion.
echo "=================== 8. repetitions are counted instead of sets ==================="
mutate "$CONTEXT" '
old = "                    ?.let { focus -> focus to occurrence.results.size }"
new = "                    ?.let { focus -> focus to occurrence.results.sumOf { set -> set.completedReps } }"
assert old in t
t = t.replace(old, new)
'
check_red "8 repetitions are counted instead of sets" "$HISTORY"

# 9. seconds are counted as though they were sets — the other dimension.
echo "=================== 9. seconds are counted as though they were sets ==================="
mutate "$CONTEXT" '
old = "                    ?.let { focus -> focus to occurrence.results.size }"
new = "                    ?.let { focus -> focus to occurrence.results.sumOf { set -> set.durationSeconds } }"
assert old in t
t = t.replace(old, new)
'
check_red "9 seconds are counted as though they were sets" "$HISTORY"

# 10. an absent focus becomes a zero — the fabrication §12 forbids, in its exact form: every focus of the
#      vocabulary gets an entry, and the unrecorded ones get 0.
echo "=================== 10. an absent focus becomes a zero ==================="
mutate "$CONTEXT" '
old = """            .groupingBy { entry -> entry.first }
            .fold(0) { total, entry -> total + entry.second }"""
new = """            .groupingBy { entry -> entry.first }
            .fold(0) { total, entry -> total + entry.second }
            .toMutableMap()
            .also { counts ->
                com.monkfitness.app.domain.program.Focus.entries.forEach { focus ->
                    if (!counts.containsKey(focus)) counts[focus] = 0
                }
            }"""
assert old in t
t = t.replace(old, new)
'
check_red "10 an absent focus becomes a zero" "$HISTORY"

# 11. another Program's history enters the context — the Program scope is dropped from the read.
echo "=================== 11. another Programs history enters the context ==================="
mutate "$CONTEXT" '
old = "        val history = sessions.sessionsOfProgram(programId)"
new = """        val history = sessions.sessionsOfProgram(programId) +
            sessions.sessionsOfProgram(com.monkfitness.app.domain.common.ProgramId("program-somebody-else"))"""
assert old in t
t = t.replace(old, new)
'
check_red "11 another Programs history enters the context" "$HISTORY"

# 12. the context is read twice in one generation pass — P27's one-snapshot invariant, eroded rather than
#      removed, so no single suite would obviously notice.
echo "=================== 12. the context is re-read during one generation pass ==================="
mutate "$SERVICE" '
old = "planned(draft, availableEquipment, classified, context.preferencesFor(draft))"
new = """planned(
            draft,
            availableEquipment,
            classified,
            context.preferencesFor(draft).copy(
                recentLoadByFocus = context.preferencesFor(draft).recentLoadByFocus
            )
        )"""
assert old in t
t = t.replace(old, new)
'
check_red "12 the context is re-read during one generation pass" "$SNAPSHOT"

# 13. a repository is injected straight into the service — the shortcut P27's stage exists to refuse.
echo "=================== 13. a repository is injected into the generation service ==================="
mutate "$SERVICE" '
# The parameter is DEFAULTED on purpose: without a default, every construction site in the test tree stops
# compiling and the row would be charged as a compile failure rather than as behavioural evidence. A
# defaulted persistence collaborator is exactly the shape this gate forbids — storage on the pass, never
# reached — so it still compiles and still trips the ban.
old = "    private val context: GenerationContextSource"
new = "    private val workoutSessionRepository: com.monkfitness.app.data.repository.WorkoutSessionRepository? = null,\n    private val context: GenerationContextSource"
assert old in t
t = t.replace(old, new)
'
check_red "13 a repository is injected into the generation service" "$P27_GATE"

# 14. an adaptive preference is fabricated from the family's current exercise — the temptation P29 creates
#      by having filled two of the six signals.
echo "=================== 14. an adaptive preference is fabricated from family state ==================="
mutate "$CONTEXT" '
old = """            recentExposureByFocus = exposure,
            recentLoadByFocus = load
        )"""
new = """            recentExposureByFocus = exposure,
            recentLoadByFocus = load,
            adaptivePreferredExerciseIds = performed
                .flatMap { exerciseId -> FAMILIES_CURRENT_EXERCISE[exerciseId].orEmpty() }
                .distinct()
        )"""
assert old in t
t = t.replace(old, new)
t = t.replace("class ProgramHistoryGenerationContext(", """private val FAMILIES_CURRENT_EXERCISE: Map<String, List<String>> = mapOf(
    "pushups" to listOf("pushups", "dips")
)

class ProgramHistoryGenerationContext(""")
'
# This row is caught by the ARCHITECTURE gate, not by the behavioural suite, and the distinction is
# worth stating: `AdaptiveHistoryContextTest` answers `emptyList()` on these fixtures because the
# fabricated table it draws on is keyed by exercise ids those fixtures do not use, so a behavioural
# oracle would report "neutral" and pass. The gate bans the *write*, which is the claim that matters —
# §9's precedence must not be invented at all, not merely happen to come out empty here.
check_red "14 an adaptive preference is fabricated from family state" "$GATE"

# 15. recovery is fabricated from elapsed time — §14's documented absence replaced by a guess, and the
#      guess is the most plausible of all: "the user trained recently".
echo "=================== 15. recovery is fabricated from elapsed time ==================="
mutate "$CONTEXT" '
old = """            recentExposureByFocus = exposure,
            recentLoadByFocus = load
        )"""
new = """            recentExposureByFocus = exposure,
            recentLoadByFocus = load,
            recovery = if (history.isNotEmpty()) {
                com.monkfitness.app.domain.adaptive.RecoveryContext.FAVORABLE
            } else {
                com.monkfitness.app.domain.adaptive.RecoveryContext.CAUTIOUS
            }
        )"""
assert old in t
t = t.replace(old, new)
'
check_red "15 recovery is fabricated from elapsed time" "$HISTORY"

# 16. the migration gains a DEFAULT — the fabrication at the storage layer, which would stamp a focus onto
#      every pre-existing row.
echo "=================== 16. the focus column is given a default ==================="
mutate "$DATABASE" '
old = "\"ALTER TABLE `program_exercise` ADD COLUMN `focus` TEXT\""
new = "\"ALTER TABLE `program_exercise` ADD COLUMN `focus` TEXT DEFAULT (\\\"PUSH\\\")\""
assert old in t
t = t.replace(old, new)
'
check_red "16 the focus column is given a default" "$SCHEMA"

# 17. THE FREEZE WIDENS: the approved line gains a second semantic responsibility — the reconciler now
#       *infers* a focus for elements that recorded none. This is the mutation the whole stage's architecture
#       gate exists to catch, and it is the reason that gate is not a blanket "the package may change".
echo "=================== 17. the reconciler also infers a focus ==================="
mutate "$RECONCILER" '
old = "        isPinned = false,\n        focus = focus\n    )"
new = """        isPinned = false,
        focus = focus ?: com.monkfitness.app.domain.program.Focus.entries.first()
    )"""
assert old in t
t = t.replace(old, new)
'
check_red "17 the reconciler also infers a focus" "$GATE"

# 18. THE FREEZE MOVES: a DIFFERENT file in the generated package changes. Removing a `require` from
#       `GeneratedSlot` is the smallest real edit to another file, and the architecture gate must notice it
#       even though no behavioural suite would.
echo "=================== 18. another generated-package file is changed ==================="
mutate "$PLAN" '
old = "        require(elements.map { it.focus } == assignment.focuses) {"
new = "        if (false) require(elements.map { it.focus } == assignment.focuses) {"
assert old in t
t = t.replace(old, new)
'
check_red "18 another generated-package file is changed" "$GATE"

# 19. an adjustment erases the recorded focus — the hazard the presentation boundary guards, and one that
#       only a suite can see because it is invisible in the type.
echo "=================== 19. a standing adjustment erases the recorded focus ==================="
mutate "$PRESENTATION" '
old = "            focus = element.focus\n        )"
new = "            focus = adjusted?.focus\n        )"
assert old in t
t = t.replace(old, new)
'
check_red "19 a standing adjustment erases the recorded focus" "$FOCUS"

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