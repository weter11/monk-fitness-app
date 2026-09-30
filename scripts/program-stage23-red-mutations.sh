#!/usr/bin/env bash
# Stage 23 RED mutations: every deliberate break of the claim that **there is a production generation
# boundary** — production catalogue → GenerationCandidate<E> → GeneratedPlanner — must be caught, and
# every mutated production source must be restored byte-identically.
#
# The oracle is the Stage 23 behavioural suite (the real shipped catalogue, the real conversion, the
# real assembled request driven through the real planner) together with the Stage 23 architecture gate,
# plus the two gates whose *claims* P23 touches: Stage 10's `ProgramGeneratedArchitectureTest` (the
# generated domain must still reach neither the catalogue nor this boundary) and `SessionRuntimeArchitectureTest`
# (the application layer's own per-layer forbidden-token list) — so a mutation that changed the layering
# or the purity of the boundary is seen by the pins that own those claims and not only by the new ones.
#
# Six harness rules, each enforced below, exactly as Stages 20–22 used them:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. It is reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe strips comments and asserts the mutated line
#    survived as real code.
#  * **a no-op mutation is never a catch.** The probe also asserts the mutated source actually differs
#    from the original, so a drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row below preserves every public
#    signature, so the test source set compiles and a real oracle runs.
#  * **the rows are observably distinct.** Fourteen rows, fourteen claims, and each pair that reads
#    alike is written so a *different* assertion sees it — the candidate's identity, its metadata, its
#    focus membership, its equipment, its dimension, the unclassified half, the refusal, the order, the
#    forwarded equipment, the reader census, the import list, the collaborator list, the purity scan, or
#    the generated-domain direction.
#  * **each row is self-contained.** Rows are applied one at a time, so no row may depend on a
#    declaration another row introduces — every payload declares everything it uses.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
BOUNDARY="$MAIN/domain/usecase/ProductionGenerationBoundary.kt"
FACTS="$MAIN/domain/usecase/ExerciseGenerationFacts.kt"
GENERATED="$MAIN/domain/program/generated/GenerationRequest.kt"

SOURCES=("$BOUNDARY" "$FACTS" "$GENERATED")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage23-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 23 mutation run owns $LOCK" >&2
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
NOTACATCH=0
RESULTS=()

restore() { for source in "${SOURCES[@]}"; do cp "$WORK/$(basename "$source").orig" "$source"; done; }

run_suites() {
    # KSP's incremental cache corrupts under many rapid recompiles of the same files (an
    # ArrayIndexOutOfBoundsException from CompressedAppendableFile that reads exactly like a source
    # error), so the caches are cleared before EVERY invocation, not once at the start of the run.
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.ProductionGenerationBoundaryTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProductionGenerationBoundaryArchitectureTest' \
        --tests 'com.monkfitness.app.domain.program.generated.ProgramGeneratedArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.SessionRuntimeArchitectureTest' \
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
assert token.strip() in text, 'the mutation text is absent from the file'
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
        echo "NOT A CATCH (compile error, no oracle saw it): $label"
        RESULTS+=("NOT A CATCH $label")
        NOTACATCH=$((NOTACATCH + 1))
        FAIL=$((FAIL + 1))
    else
        echo "caught: $label"
        RESULTS+=("caught $label")
        PASS=$((PASS + 1))
    fi
    restore
}

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

# 1. An unclassified exercise is quietly admitted as training everything. The `null` arm is widened
#    into the *classified* one with the whole focus vocabulary, so the gap disappears from the result
#    and every unclassified exercise becomes selectable for every focus. A plan is still produced —
#    a plausible one — which is why no planner assertion can see this and the gate must.
GAP_ADMITTED_ANCHOR='            when (val focuses = focusSource.focusesOf(exercise.id)) {
                null -> unclassified += exercise.id
                else -> candidates += exercise.asCandidate(focuses)
            }'
GAP_ADMITTED_LOST='            when (val focuses = focusSource.focusesOf(exercise.id)) {
                null -> candidates += exercise.asCandidate(com.monkfitness.app.domain.program.Focus.entries.toSet())
                else -> candidates += exercise.asCandidate(focuses)
            }'

# 2. An unclassified exercise is dropped instead. The catalogue silently loses it, and the caller is
#    told nothing — "nothing to generate from" becomes "one exercise fewer", which reads as a smaller
#    program rather than as a missing classification.
GAP_DROPPED_ANCHOR='                null -> unclassified += exercise.id'
GAP_DROPPED_LOST='                null -> Unit'

# 3. Focus membership is inferred from the catalogue's own category — the fabrication the stage's gap
#    record is about. Every exercise is classified by whether its category is STRENGTH, so the focus
#    source is never asked and the `null` arm becomes unreachable: the boundary now appears to have a
#    focus classification it does not have, and the gap stops being visible.
GAP_INFERRED_ANCHOR='            when (val focuses = focusSource.focusesOf(exercise.id)) {'
GAP_INFERRED_LOST='            val focuses = if (exercise.category ==
                com.monkfitness.app.data.model.ExerciseCategory.STRENGTH
            ) {
                setOf(com.monkfitness.app.domain.program.Focus.PUSH)
            } else {
                setOf(com.monkfitness.app.domain.program.Focus.MOBILITY)
            }
            when (focuses) {'

# 4. The candidate's identity is re-minted from the family, so two exercises of one family collapse to
#    the same id and the duplicate guard fires on a catalogue that is perfectly valid.
IDENTITY_LOST_ANCHOR='        metadata = toConfigurationMetadata(),'
IDENTITY_LOST='        metadata = toConfigurationMetadata().copy(id = familyId),'

# 5. The candidate's canonical metadata is restated rather than derived: the body region is replaced
#    with a constant, so a spine exercise is stated to train the core and the mapping the app already
#    owns is quietly discarded.
METADATA_RESTATED_ANCHOR='        metadata = toConfigurationMetadata(),'
METADATA_RESTATED_LOST='        metadata = toConfigurationMetadata().copy(
            bodyRegion = com.monkfitness.app.domain.adaptive.BodyRegion.CORE
        ),'

# 6. Required equipment is widened so that no exercise is ever unusable: the hard execution constraint
#    becomes decorative and `GenerationRequest.isUsable` can never report an equipment limitation.
EQUIPMENT_WIDENED_ANCHOR='        metadata = toConfigurationMetadata(),'
EQUIPMENT_WIDENED_LOST='            metadata = toConfigurationMetadata().copy(requiredEquipment = emptySet()),'

# 7. The prescription dimension is guessed rather than read. A timed exercise becomes rep-based, so a
#    generated plan prescribes seconds as repetitions — and the hand-authoring rule it was copied from
#    now disagrees with generation about the same catalogue entry.
DIMENSION_GUESSED_ANCHOR='fun DimensionOf(isTimerBased: Boolean): PrescriptionDimension = when (isTimerBased) {
        true -> PrescriptionDimension.TIME_BASED
        false -> PrescriptionDimension.REP_BASED
    }'
DIMENSION_GUESSED_LOST='fun DimensionOf(isTimerBased: Boolean): PrescriptionDimension = when (isTimerBased) {
        true -> PrescriptionDimension.REP_BASED
        false -> PrescriptionDimension.TIME_BASED
    }'

# 8. The empty-catalogue refusal is removed, so "the app states no focus classification" becomes a plan
#    with no exercises — a success that reads as a program with nothing in it (§33).
REFUSAL_REMOVED_ANCHOR='        require(catalogue.candidates.isNotEmpty()) {
            "no exercise in the catalogue states which focuses it trains, so there is nothing to " +
                "generate from: ${catalogue.unclassifiedExerciseIds.size} exercise(s) are " +
                "unclassified. Generation classifies nothing rather than guessing (§6, §8)"
        }'
REFUSAL_REMOVED_LOST='        if (catalogue.candidates.isEmpty()) {
            // mutated: the gap is no longer a refusal
        }'

# 9. The catalogue's order is replaced by a sorted one, so the candidate list no longer reflects the
#    catalogue. Harmless today (the planner re-ranks) and wrong the moment a caller reads it as a
#    statement about the library — which is exactly what the result value claims to be.
ORDER_REWRITTEN_ANCHOR='        exercises.forEach { exercise ->'
ORDER_REWRITTEN_LOST='        exercises.sortedBy { it.id }.forEach { exercise ->'

# 10. Available equipment is normalised the legacy way — an empty set meaning "constrain nothing" — so a
#     user who declared no equipment is offered every bar and band exercise.
EQUIPMENT_NORMALIZED_ANCHOR='            availableEquipment = availableEquipment,'
EQUIPMENT_NORMALIZED_LOST='            availableEquipment = if (availableEquipment.isEmpty()) {
                com.monkfitness.app.domain.usecase.WorkoutGenerator()
                    .getExerciseLibrary()
                    .flatMap { it.requiredEquipment }
                    .toSet()
            } else {
                availableEquipment
            },'

# 11. The catalogue is filtered by equipment before it is stated, so an exercise the user cannot perform
#     is never reported as unusable — the planner's `EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT`
#     limitation becomes unreachable from the production catalogue.
CATALOGUE_FILTERED_ANCHOR='        catalogueOf(WorkoutGenerator().getExerciseLibrary(), focusSource)'
CATALOGUE_FILTERED_LOST='        catalogueOf(
            WorkoutGenerator().getExerciseLibrary().filter { it.requiredEquipment.isEmpty() },
            focusSource
        )'

# 12. The generated domain reaches back for the catalogue. It is an unused declaration on purpose: the
#     first one has callers whose compilation is the thing the mutation must not disturb.
DOMAIN_REACHES_BACK_ANCHOR='    /** Whether [candidate] may be selected at all: equipment available, dimension prescribable. */'
DOMAIN_REACHES_BACK_LOST='    private fun reachesForTheCatalogue(): com.monkfitness.app.domain.usecase.ProductionGenerationBoundary? =
        null

    /** Whether [candidate] may be selected at all: equipment available, dimension prescribable. */'

# 13. The boundary acquires a collaborator and a clock — the two things a value-mapping function must
#     never hold — as an unused declaration so every existing call site still compiles.
COLLABORATOR_ACQUIRED_ANCHOR='object ProductionGenerationBoundary {'
COLLABORATOR_ACQUIRED_LOST='object ProductionGenerationBoundary {

    private val clock: com.monkfitness.app.di.Clock = com.monkfitness.app.di.Clock.system()

    private val repository: com.monkfitness.app.data.repository.ProgramRepository? = null'

# 14. One representative per family: the catalogue stops being a library and becomes its first member
#     of each family, so exercises sharing a family silently vanish from generation. Distinct from
#     row 9 on purpose — that one changes the order, this one changes the set.
FAMILY_GROUPED_ANCHOR='        exercises.forEach { exercise ->'
FAMILY_GROUPED_LOST='        exercises.distinctBy { it.familyId }.forEach { exercise ->'

echo '== preflight =='

preflight 'an unclassified exercise is admitted as training everything' "$BOUNDARY" "$GAP_ADMITTED_ANCHOR" "$GAP_ADMITTED_LOST"
preflight 'an unclassified exercise is dropped from the catalogue' "$BOUNDARY" "$GAP_DROPPED_ANCHOR" "$GAP_DROPPED_LOST"
preflight 'focus membership is inferred from the catalogue category' "$BOUNDARY" "$GAP_INFERRED_ANCHOR" "$GAP_INFERRED_LOST"
preflight 'the candidate identity is re-minted from the family' "$BOUNDARY" "$IDENTITY_LOST_ANCHOR" "$IDENTITY_LOST"
preflight 'the canonical metadata is restated as a constant' "$BOUNDARY" "$METADATA_RESTATED_ANCHOR" "$METADATA_RESTATED_LOST"
preflight 'required equipment is widened to nothing' "$BOUNDARY" "$EQUIPMENT_WIDENED_ANCHOR" "$EQUIPMENT_WIDENED_LOST"
preflight 'the prescription dimension is guessed' "$FACTS" "$DIMENSION_GUESSED_ANCHOR" "$DIMENSION_GUESSED_LOST"
preflight 'the empty-catalogue refusal is removed' "$BOUNDARY" "$REFUSAL_REMOVED_ANCHOR" "$REFUSAL_REMOVED_LOST"
preflight 'the catalogue order is rewritten as sorted' "$BOUNDARY" "$ORDER_REWRITTEN_ANCHOR" "$ORDER_REWRITTEN_LOST"
preflight 'available equipment is normalised the legacy way' "$BOUNDARY" "$EQUIPMENT_NORMALIZED_ANCHOR" "$EQUIPMENT_NORMALIZED_LOST"
preflight 'the catalogue is filtered by equipment' "$BOUNDARY" "$CATALOGUE_FILTERED_ANCHOR" "$CATALOGUE_FILTERED_LOST"
preflight 'the generated domain reaches back for the catalogue' "$GENERATED" "$DOMAIN_REACHES_BACK_ANCHOR" "$DOMAIN_REACHES_BACK_LOST"
preflight 'the boundary acquires a collaborator and a clock' "$BOUNDARY" "$COLLABORATOR_ACQUIRED_ANCHOR" "$COLLABORATOR_ACQUIRED_LOST"
preflight 'the catalogue is collapsed to one exercise per family' "$BOUNDARY" "$FAMILY_GROUPED_ANCHOR" "$FAMILY_GROUPED_LOST"

echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -80 "$WORK/last-run.log"
    exit 1
fi

echo '== mutations =='

mutate 'an unclassified exercise is admitted as training everything' "$BOUNDARY" "$GAP_ADMITTED_ANCHOR" "$GAP_ADMITTED_LOST"
mutate 'an unclassified exercise is dropped from the catalogue' "$BOUNDARY" "$GAP_DROPPED_ANCHOR" "$GAP_DROPPED_LOST"
mutate 'focus membership is inferred from the catalogue category' "$BOUNDARY" "$GAP_INFERRED_ANCHOR" "$GAP_INFERRED_LOST"
mutate 'the candidate identity is re-minted from the family' "$BOUNDARY" "$IDENTITY_LOST_ANCHOR" "$IDENTITY_LOST"
mutate 'the canonical metadata is restated as a constant' "$BOUNDARY" "$METADATA_RESTATED_ANCHOR" "$METADATA_RESTATED_LOST"
mutate 'required equipment is widened to nothing' "$BOUNDARY" "$EQUIPMENT_WIDENED_ANCHOR" "$EQUIPMENT_WIDENED_LOST"
mutate 'the prescription dimension is guessed' "$FACTS" "$DIMENSION_GUESSED_ANCHOR" "$DIMENSION_GUESSED_LOST"
mutate 'the empty-catalogue refusal is removed' "$BOUNDARY" "$REFUSAL_REMOVED_ANCHOR" "$REFUSAL_REMOVED_LOST"
mutate 'the catalogue order is rewritten as sorted' "$BOUNDARY" "$ORDER_REWRITTEN_ANCHOR" "$ORDER_REWRITTEN_LOST"
mutate 'available equipment is normalised the legacy way' "$BOUNDARY" "$EQUIPMENT_NORMALIZED_ANCHOR" "$EQUIPMENT_NORMALIZED_LOST"
mutate 'the catalogue is filtered by equipment' "$BOUNDARY" "$CATALOGUE_FILTERED_ANCHOR" "$CATALOGUE_FILTERED_LOST"
mutate 'the generated domain reaches back for the catalogue' "$GENERATED" "$DOMAIN_REACHES_BACK_ANCHOR" "$DOMAIN_REACHES_BACK_LOST"
mutate 'the boundary acquires a collaborator and a clock' "$BOUNDARY" "$COLLABORATOR_ACQUIRED_ANCHOR" "$COLLABORATOR_ACQUIRED_LOST"
mutate 'the catalogue is collapsed to one exercise per family' "$BOUNDARY" "$FAMILY_GROUPED_ANCHOR" "$FAMILY_GROUPED_LOST"

restore
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo 'every mutated source restored byte-identically'
else
    echo 'MISSED byte-identical restoration'
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
    cat "$WORK/md5check.log"
fi

printf '%s\n' "${RESULTS[@]}"
echo "control GREEN"
echo "caught: $PASS"
echo "missed: $FAIL"
echo "not-a-catch: $NOTACATCH"
[ "$FAIL" -eq 0 ]