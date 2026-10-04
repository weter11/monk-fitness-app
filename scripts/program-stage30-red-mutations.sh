#!/bin/bash
# RED mutation suite — P30 (production exercise→family classification).
#
# The contract every stage owes:
#   control run     must be GREEN  (nothing mutated)
#   every mutation  must be RED    (its owning suite fails)
#   every source    restored byte-identically, and the tree still clean afterwards
#
# P30's rules are held here by ten mutations, and they are grouped by the claim each one breaks:
#
#   rows 1-4   the family is READ, not INFERRED. `Exercise.familyId` is a stored field, so every
#              way of computing a family from a different field is a plausible mistake and each is a
#              separate defect: a category, a subcategory, a training-style map, a constant. Three of
#              these four reproduce the *right answer for most of the catalogue*, because it was
#              authored with those fields broadly in agreement — which is exactly why they need a RED
#              run rather than a code review.
#   row  5     the answer is the exercise's OWN family — not a constant and not another entry's.
#   row  6     absence is a value: a known exercise is never answered `null`.
#   row  7     the Stage-1 pilot's own vocabulary is not consulted (§30 step 11).
#   row  8     the source holds no persistence collaborator.
#   row  9     production actually routes through this source.
#   row 10     the typed refusal survives: `NO_DECLARED_PROGRESSION_RELATION` is not quietly
#              swapped for a fabricated ladder or a different gap.
#
# Three rules this script holds itself to, all learned the hard way in earlier stages:
#
#   * a **compile error is not a catch**. Every mutation below is a PLAUSIBLE implementation — something a
#     well-meaning later session might actually write — so the oracle has to discriminate on behaviour. A
#     row that fails only because it stopped compiling proves nothing and is charged as a missed row, not a
#     caught one. That is why `check_red` inspects the run log for a Kotlin error before accepting a RED.
#
#   * a mutation that **does not apply** aborts the run rather than scoring a verdict. A no-op mutation
#     reads as a hole in the oracle and sends the next reader hunting for a hole that is not there.
#
#   * every row is aimed at the layer that **DECIDES** the rule — the classification source for a
#     fabrication, the container for a wiring, the integration for a refusal.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/red-mutations-p30
rm -rf "$WORK" && mkdir -p "$WORK"

MAIN=app/src/main/java/com/monkfitness/app

SOURCE="$MAIN/domain/usecase/CatalogExerciseFamilyClassification.kt"
CONTAINER="$MAIN/di/AppContainer.kt"
INTEGRATION="$MAIN/domain/usecase/ProgramAdaptiveIntegration.kt"

# EVERY file any mutation touches — including one that only gains an inserted line. A file missing from
# this list keeps its mutation, and the NEXT run's control then fails with a real-looking compile error, so a
# contaminated baseline reads as a broken one.
FILES=(
    "$SOURCE"
    "$CONTAINER"
    "$INTEGRATION"
)

# The suites that OWN each rule. A mutation is only worth writing against the suite that owns the rule it
# breaks: the stage's own behavioural suites for a semantic fabrication, the architecture gate for a
# structural one, and the integration suite for the refusal.
BEHAVIOUR="com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassificationTest"
GATE="com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassificationArchitectureTest"
INTEGRATION_SUITE="com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassificationIntegrationTest"

SUITES=("$BEHAVIOUR" "$GATE" "$INTEGRATION_SUITE")

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

# preflight <label> <file> <anchor> <replacement> — assert the anchor is present verbatim and the
# replacement is NOT already there, BEFORE the control run. `mutate`'s own assertion happens after the
# baseline is taken, so it cannot protect the baseline: a run on a tree already holding a leftover
# mutation "proves" the mutated bytes are the reviewed ones.
preflight() {
    local label="$1" file="$2" anchor="$3" replacement="$4"
    python3 - "$file" "$label" "$anchor" "$replacement" <<'PREFLIGHT_BODY'
import io, sys
path, label, anchor, replacement = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
text = io.open(path, encoding="utf-8").read()
problems = []
if anchor not in text:
    problems.append("ANCHOR MISSING")
if replacement in text:
    problems.append("REPLACEMENT ALREADY PRESENT (leftover mutation from a previous run?)")
if problems:
    sys.stderr.write("PREFLIGHT %s: %s\n" % (label, ", ".join(problems)))
    sys.exit(1)
PREFLIGHT_BODY
    if [ $? -ne 0 ]; then
        echo "  ABORT: preflight failed for $label"
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
MISSED=0
declare -a RESULTS

# ---- the control: the same suites, nothing mutated -----------------------------------------------
echo "=================== control ==================="
CONTROL_OK=1
for suite in "${SUITES[@]}"; do
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
        MISSED=$((MISSED + 1))
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

# The one line every inference mutation replaces. It is the projection, so a mutation of "which field is
# the family" has exactly one place to happen.
PROJECTION_OLD='.associate { exercise -> exercise.id to exercise.familyId }'

# The replacement tested for is the WHOLE replacement text, not a keyword: a bare `null` or a bare
# `"pushups"` also occurs in this file's KDoc, so a token-level containment check reports a leftover
# mutation on a clean tree and aborts the run for no reason.
preflight "1 category inference" "$SOURCE" "$PROJECTION_OLD" ".associate { exercise -> exercise.id to exercise.category.name }"
preflight "2 subcategory inference" "$SOURCE" "$PROJECTION_OLD" ".associate { exercise -> exercise.id to exercise.subCategory.name }"
preflight "3 training-style map" "$SOURCE" "$PROJECTION_OLD" "TRAINING_STYLE_FAMILY"
preflight "4 hard-coded fallback" "$SOURCE" "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]" "?: HARDCODED_FAMILY"
preflight "5 another exercises family" "$SOURCE" "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]" "familyByExerciseId[\"pushups\"]"
preflight "6 null for a known exercise" "$SOURCE" "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]" "override fun familyOf(exerciseId: String): String? = null"
preflight "7 stage-1 pilot" "$SOURCE" "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {" "private val pilot = PILOT_PROGRESSION_PROFILES"
preflight "8 persistence collaborator" "$SOURCE" "private val familyByExerciseId: Map<String, String> =" "= store.loadFamilies()"
preflight "9 bypass the production source" "$CONTAINER" "import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression" "import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification"
preflight "10 typed refusal replaced" "$INTEGRATION" "AdaptiveTargetElement.NoDeclaredRelation ->
                return gap(AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION)" "FABRICATED_LADDER"
echo "preflight OK"

# 1. the family is derived from the exercise CATEGORY. `ExerciseCategory` is a four-value axis that looks
#    like a family and is not, and the catalogue was authored with the two broadly in agreement — so this
#    mutation is the single most plausible wrong implementation of P30 and the hardest to see in review.
echo "=================== 1. the family is derived from the category ==================="
mutate "$SOURCE" '
old = ".associate { exercise -> exercise.id to exercise.familyId }"
new = ".associate { exercise -> exercise.id to exercise.category.name }"
assert old in t
t = t.replace(old, new)
'
check_red "1 the family is derived from the category" "$BEHAVIOUR"

# 2. the family is derived from the SUBCATEGORY. The seven-value subcategory axis is finer than a family
#    and again agrees with it for most entries.
echo "=================== 2. the family is derived from the subcategory ==================="
mutate "$SOURCE" '
old = ".associate { exercise -> exercise.id to exercise.familyId }"
new = ".associate { exercise -> exercise.id to exercise.subCategory.name }"
assert old in t
t = t.replace(old, new)
'
check_red "2 the family is derived from the subcategory" "$BEHAVIOUR"

# 3. the family is derived from the TRAINING-STYLE map — `exerciseToFamiliesMap`, the filter vocabulary
#    the Focus UI groups by. It is keyed by exercise id and holds filter tokens, not families, so reading a
#    family out of it is reading a UI filter as a movement family. Written with the real map so this is
#    the edit a later session could plausibly make rather than a strawman.
echo "=================== 3. the family is derived from the training-style map ==================="
mutate "$SOURCE" '
old = ".associate { exercise -> exercise.id to exercise.familyId }"
new = ".associate { exercise -> exercise.id to TRAINING_STYLE_FAMILY(exercise) }"
assert old in t
t = t.replace(old, new)
t = t.replace(
    "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {",
    """private fun TRAINING_STYLE_FAMILY(
    exercise: com.monkfitness.app.data.model.Exercise
): String =
    com.monkfitness.app.data.model.exerciseToFamiliesMap[exercise.id]
        ?.firstOrNull()
        ?.key
        ?: "pushups"

class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"""
)
'
check_red "3 the family is derived from the training-style map" "$BEHAVIOUR"

# 4. a hard-coded FALLBACK family: known ids answer correctly, unknown ids are given an invented bucket.
#    This is the mutation that converts the honest `NO_FAMILY_CLASSIFICATION` gap into a fabricated
#    ladder question, so it is the one that would make a pass adapt something nobody stated.
echo "=================== 4. a hard-coded fallback family ==================="
mutate "$SOURCE" '
old = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]"
new = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId] ?: HARDCODED_FAMILY"
assert old in t
t = t.replace(old, new)
t = t.replace(
    "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {",
    """private const val HARDCODED_FAMILY = "pushups"

class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"""
)
'
check_red "4 a hard-coded fallback family" "$BEHAVIOUR"

# 5. the answer is ANOTHER exercise's family — the lookup keyed on a constant instead of on the argument.
#    Plausible as an off-by-one in a map read, and invisible on any single-id check.
echo "=================== 5. another exercises family is returned ==================="
mutate "$SOURCE" '
old = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]"
new = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[\"pushups\"]"
assert old in t
t = t.replace(old, new)
'
check_red "5 another exercises family is returned" "$BEHAVIOUR"

# 6. `null` for a KNOWN exercise — the classification is present but answers nothing, which is the P29
#    state reached through new code. This is the row that keeps "wired in" distinct from "working".
echo "=================== 6. null is returned for a known exercise ==================="
mutate "$SOURCE" '
old = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]"
new = "override fun familyOf(exerciseId: String): String? = null"
assert old in t
t = t.replace(old, new)
'
check_red "6 null is returned for a known exercise" "$BEHAVIOUR"

# 7. a LEGACY progression/family source is consulted for the family. §30 step 11 forbids this generation
#    reaching for the Stage-1 pilot, and the pilot carried a family vocabulary — so borrowing it is the
#    tempting shortcut, and the one a later session would most plausibly write.
#
#    The pilot FILE no longer exists (§30 step 15 deleted the legacy generation), so the row cannot name
#    it or the row would die at `e: ` and be charged NOT-A-CATCH for a reason unrelated to its rule.
#    Instead the mutation reintroduces the pilot's SHAPE — a local `PILOT_PROGRESSION_PROFILES` table
#    keyed by exercise — which is exactly what "consult the legacy source" looks like when someone
#    reconstructs it rather than imports it. The gate bans the pilot vocabulary by token, so it catches
#    the shape without depending on a deleted file.
echo "=================== 7. a legacy pilot source is consulted ==================="
mutate "$SOURCE" '
# The pilot table is inserted BEFORE the class, so the field that reads it is declared after the
# projection: Kotlin initialises properties top to bottom, and a `val` read above its own declaration is
# a compile error rather than a catch.
old = "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"
new = "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {\n\n    private val pilot = PILOT_PROGRESSION_PROFILES\n"
assert old in t
t = t.replace(old, new)
old2 = "override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]"
new2 = "override fun familyOf(exerciseId: String): String? =\n        pilot[exerciseId]?.familyId ?: familyByExerciseId[exerciseId]"
assert old2 in t
t = t.replace(old2, new2)
old3 = "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"
new3 = """private class PilotProfile(val exerciseId: String, val familyId: String, val level: Int)

    private val PILOT_PROGRESSION_PROFILES: Map<String, PilotProfile> = mapOf(
        "pushups" to PilotProfile("pushups", "pushups", 1),
        "squats" to PilotProfile("squats", "squats", 1)
    )

    class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"""
assert old3 in t
t = t.replace(old3, new3, 1)
'
check_red "7 a legacy pilot source is consulted" "$GATE"

# 8. PERSISTENCE is injected into the classification source — the shape a source grows when someone wants
#    it to see exercises the shipped catalogue does not hold. The architecture gate forbids the dependency
#    outright, because the whole claim is that the fact is a compile-time field.
echo "=================== 8. persistence is injected into the source ==================="
mutate "$SOURCE" '
# The WHOLE three-line initializer is replaced, not just its first line: inserting the store read after
# the `=` orphans the `WorkoutGenerator()` continuation below it and dies at `Expecting member
# declaration`, which would be charged NOT-A-CATCH for a reason unrelated to the rule.
old = "class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {"
new = "class CatalogExerciseFamilyClassification(\n    private val store: ExerciseFamilyStore = ExerciseFamilyStore.default()\n) : ExerciseFamilyClassification {"
assert old in t
t = t.replace(old, new)
old2 = """    private val familyByExerciseId: Map<String, String> =
        WorkoutGenerator().getExerciseLibrary()
            .associate { exercise -> exercise.id to exercise.familyId }"""
new2 = "    private val familyByExerciseId: Map<String, String> = store.loadFamilies()"
assert old2 in t
t = t.replace(old2, new2)
t = t.replace(
    "import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification",
    """import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification

interface ExerciseFamilyStore {
    fun loadFamilies(): Map<String, String>
    companion object {
        fun default(): ExerciseFamilyStore = object : ExerciseFamilyStore {
            override fun loadFamilies(): Map<String, String> = emptyMap()
        }
    }
}"""
)
'
check_red "8 persistence is injected into the source" "$GATE"

# 9. production BYPASSES the source — the container hands the integration the empty classification again,
#    which is the pre-P30 wiring and would leave the whole stage inert while every source-level test
#    stayed green.
#
#    Aimed at the **architecture gate**, not the integration suite, and that re-aiming is the interesting
#    part. The integration suite builds its own `ProgramAdaptiveIntegration` with its own classification
#    through the rig — it never goes through `AppContainer` — so it cannot observe a container wiring at
#    all: with the mutation applied, every integration test still passes, because the rig is unaffected by
#    what the container says. Only the gate, which reads the container's own text, catches it. Writing
#    this row against the behavioural suite would have produced a MISSED verdict that indicted the wrong
#    oracle.
echo "=================== 9. production bypasses the production source ==================="
# The import comes WITH the edit, because that is what the real edit looks like: P30 removed
# `NoExerciseFamilyClassification` from the container's imports precisely because it stopped being wired,
# so a revert that names the value without re-importing it dies at `Unresolved reference` and is charged
# NOT-A-CATCH for a reason that has nothing to do with the rule. The row is therefore the complete
# plausible edit: swap the wiring and put the import back.
mutate "$CONTAINER" '
old = "classification = catalogExerciseFamilyClassification"
new = "classification = NoExerciseFamilyClassification"
assert old in t
t = t.replace(old, new)
old2 = "import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression"
new2 = "import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression\nimport com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification"
assert old2 in t
t = t.replace(old2, new2)
'
check_red "9 production bypasses the production source" "$GATE"

# 10. the typed refusal is replaced — the gap becomes a different gap, which is the shape "just report
#     something more useful" takes. Both gaps are real, so this mutation is a silent downgrade of the
#     diagnostic rather than a crash.
echo "=================== 10. the typed refusal is silently replaced ==================="
mutate "$INTEGRATION" '
old = """            AdaptiveTargetElement.NoDeclaredRelation ->
                return gap(AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION)"""
new = """            AdaptiveTargetElement.NoDeclaredRelation ->
                return gap(AdaptiveInputGap.NO_FAMILY_CLASSIFICATION)"""
assert old in t
t = t.replace(old, new)
'
check_red "10 the typed refusal is silently replaced" "$INTEGRATION_SUITE"

# ---- restoration: every source byte-identical, and the tree clean ---------------------------------
echo "=================== restoration ==================="
restore_all
if md5sum -c "$WORK/before.md5" > "$WORK/restore.log" 2>&1; then
    echo "every mutated production source restored byte-identically"
else
    echo "RESTORATION FAILED — a mutation survived:"
    cat "$WORK/restore.log"
    exit 1
fi

# md5 above is the complete restoration proof here, and `git status` is deliberately NOT used as a
# second gate: this stage legitimately modifies four tracked files and adds five new ones, so a
# "tree is clean" assertion would fail for the right work and prove nothing about restoration. Every
# file any row touches is in FILES and therefore in the baseline, and no row creates a new file — a row
# that did would leave a stray source that the next compile reads, which is a louder failure than any
# git check.

# ---- the score ------------------------------------------------------------------------------------
echo "=================== RED summary ==================="
for line in "${RESULTS[@]}"; do
    echo "  $line"
done
echo "----------------------------------------"
echo "control:        GREEN"
echo "caught:          $PASS"
echo "missed:          $MISSED"
echo "not a catch:     $NOTACATCH"
echo "total failures:  $FAIL"

if [ "$FAIL" -ne 0 ]; then
    echo "RED SUITE FAILED"
    exit 1
fi
echo "RED SUITE PASSED"