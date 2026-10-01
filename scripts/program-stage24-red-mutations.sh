#!/usr/bin/env bash
# Stage 24 RED mutations: every deliberate break of the claim that **generation is production
# callable** — real catalogue + explicit focus classification → GenerationRequest → GeneratedPlanner
# → ProgramGeneratedEditor → PlanReconciler → a new working Draft, and nothing else — must be caught,
# and every mutated production source must be restored byte-identically.
#
# The oracle is the Stage 24 behavioural suites (the REAL shipped catalogue through the real
# classification into the real planner, plus the controller measured on storage) together with the
# Stage 24 architecture gate, plus the two gates whose claims P24 *inverts* or touches:
#   * PR 10's `ProgramGeneratedArchitectureTest` — the generated domain's own purity, whose "nothing
#     consumes generation" pins P24 deliberately reversed;
#   * PR 23's `ProductionGenerationBoundaryArchitectureTest` — the boundary's closed consumer list and
#     the three vocabularies the classification must not be inferred from;
#   * `ProgramsArchitectureTest` — the state holder's own collaborator census and its §16/§33 bans.
#
# Seven harness rules, each enforced below, exactly as Stages 20–23 used them:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. Reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe strips comments and asserts the mutated line
#    survived as real code.
#  * **a no-op mutation is never a catch.** The probe asserts the mutated source actually differs from
#    the original, so a drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row preserves every public
#    signature, so the test source set compiles and a real oracle runs.
#  * **the rows are observably distinct.** Eleven rows, eleven claims.
#  * **each row is self-contained** — applied alone, so no row depends on a declaration another
#    introduces.
#  * **one runner per tree** (exclusive lock), because two runs on one tree restore each other's
#    mutations and every row then scores against unreviewed bytes.
#  * **no payload variable may share a name with a path variable.** A payload called `$RECONCILER`
#    silently overwrote the `PlanReconciler.kt` path, so row 9 preflighted the *payload* as a file and
#    the run aborted with a `FileNotFoundError` whose text was the mutation itself. Payloads here are
#    named for the DEFECT, paths for the FILE.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
CLASSIFICATION="$MAIN/domain/usecase/ProductionFocusClassification.kt"
SERVICE="$MAIN/domain/usecase/ProgramGenerationService.kt"
BOUNDARY="$MAIN/domain/usecase/ProductionGenerationBoundary.kt"
CONTROLLER="$MAIN/ui/programs/ProgramsController.kt"
RECONCILER="$MAIN/domain/program/generated/PlanReconciler.kt"

SOURCES=("$CLASSIFICATION" "$SERVICE" "$BOUNDARY" "$CONTROLLER" "$RECONCILER")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage24-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 24 mutation run owns $LOCK" >&2
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
    # KSP's incremental cache corrupts under many rapid recompiles of the same files, so the caches
    # are cleared before EVERY invocation, not once at the start of the run.
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.ProductionFocusClassificationTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramGenerationServiceTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramGenerationFlowArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProductionGenerationBoundaryArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProductionGenerationBoundaryTest' \
        --tests 'com.monkfitness.app.domain.program.generated.ProgramGeneratedArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsControllerTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsArchitectureTest' \
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
    # Four quoted slots into FOUR argv entries. Passing the payload variables while the heredoc reads
    # a different count pairs a *file* variable with a *payload* variable, and the row then reports
    # ANCHOR MISSING against a perfectly good file (the P23 harness's own latent bug; hygiene 13).
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

# 1. A missing classification is admitted as training the whole focus vocabulary — the substitution
#    P23 refused and P24 replaced with data. The gap disappears from the result and every exercise
#    becomes selectable for every focus, so a plan is still produced and no planner assertion sees it.
ALL_FOCUS_ANCHOR='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }'
ALL_FOCUS='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }
            ?: Focus.entries.toSet()'

# 2. A missing classification is silently dropped instead of reported: the table stops stating
#    anything the caller cannot see, and "nothing to generate from" becomes "one exercise fewer",
#    which reads as a smaller program rather than as a missing fact.
DROPPED_ANCHOR='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }'
DROPPED='        CLASSIFICATION.getValue(exerciseId).let { focuses -> FocusPlan.canonical(focuses).toSet() }'

# 3. Membership is inferred from the catalogue's own category. The classification table is bypassed
#    whenever an exercise is not in it, so a category the app added later silently decides a focus.
#    `STRENGTH -> PUSH` is deliberately wrong for half the catalogue, which is what makes the
#    fabrication visible in a plan rather than plausible-looking.
CATEGORY_ANCHOR='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }'
CATEGORY='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }
            ?: com.monkfitness.app.data.model.ExerciseCategory.STRENGTH.let {
                setOf(com.monkfitness.app.domain.program.Focus.PUSH)
            }'

# 4. Membership is inferred from the body region — a *second* vocabulary that overlaps Focus in
#    exactly two names (LEGS, CORE), which is what makes this read as a reasonable simplification.
SUBCATEGORY_ANCHOR='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }'
SUBCATEGORY='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }
            ?: com.monkfitness.app.data.model.ExerciseSubCategory.CORE.let {
                setOf(com.monkfitness.app.domain.program.Focus.CORE)
            }'

# 5. Membership is inferred from the training-style map — the third vocabulary, overlapping in one
#    name (MOBILITY) and sharing none of the other six, so a lookup recovers nothing.
FAMILYMAP_ANCHOR='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }'
FAMILYMAP='        CLASSIFICATION[exerciseId]?.let { focuses -> FocusPlan.canonical(focuses).toSet() }
            ?: com.monkfitness.app.data.model.exerciseToFamiliesMap.getValue(exerciseId).let {
                setOf(com.monkfitness.app.domain.program.Focus.MOBILITY)
            }'

# 6. Available equipment is normalised the legacy way — an empty set widened to every piece of
#    equipment the catalogue mentions — so a user who declared nothing is offered bar and band work.
EQUIPMENT_ANCHOR='            availableEquipment = availableEquipment,'
EQUIPMENT='            availableEquipment = if (availableEquipment.isEmpty()) {
                com.monkfitness.app.data.model.Equipment.entries.toSet()
            } else {
                availableEquipment
            },'

# 7. The production pass stops at the planner: it builds the GeneratedDraftEdit by hand and keeps the
#    previous draft's days, so `ProgramGeneratedEditor` — and with it every preservation rule — is
#    never reached. Type-correct, so a real oracle runs and the chain is measurably broken.
EDITOR_ANCHOR='        val edit = ProgramGeneratedEditor(draft = draft, ids = ids).generate(request)'
EDITOR='        val plan = com.monkfitness.app.domain.program.generated.GeneratedPlanner.plan(request)
        val edit = com.monkfitness.app.domain.program.generated.GeneratedDraftEdit(
            draft = draft,
            plan = plan,
            reconciliation = com.monkfitness.app.domain.program.generated.ReconciliationReport(
                emptyList()
            )
        )'

# 8. The pass reaches the editor, and then DISCARDS what the reconciler produced: the days handed back
#    are the previous draft's own, so the plan is computed and thrown away. §7's four authority levels
#    are never applied. A first version of this row called the editor twice and kept the second
#    result - behaviourally IDENTICAL, and correctly scored MISSED (hygiene rule 4: an equivalent
#    mutant is not evidence). The second version introduced a block the original did not have and died
#    on `Expecting member declaration`; the row is now a single-line rewrite, which is brace-neutral by
#    construction and observable on every fixture, because the reconciler's own days are what is
#    thrown away.
BYPASS_RECONCILER_ANCHOR='        val edit = ProgramGeneratedEditor(draft = draft, ids = ids).generate(request)'
BYPASS_RECONCILER='        val edit = ProgramGeneratedEditor(draft = draft, ids = ids).generate(request).copy(draft = draft)'

# 9. Reconciliation stops preserving what the user owns: no element is read as user-owned at all, so
#    a regeneration replaces the pinned, user-authored element the fixture holds. §7's *pinned: never
#    automatically change* has no exception for a regenerated plan. (The first version of this row
#    narrowed `||` to `&&`, which differs only for a PINNED *generated* element - a shape no fixture
#    has - and was correctly scored MISSED: an equivalent mutant is not evidence.)
OWNERSHIP_ANCHOR='        get() = isPinned || origin == ProgramExerciseOrigin.USER_AUTHORED'
OWNERSHIP='        get() = false'

# 10. The controller is back to the P23 placeholder: it short-circuits into GENERATION_UNAVAILABLE
#     and never asks the service, which is the exact state P24 exists to end.
BYPASS_CONTROLLER_ANCHOR='        val result = try {
            generation.generate(draft, availableEquipment())
        } catch (failure: Throwable) {
            ProgramGenerationResult.Failed(failure)
        }'
BYPASS_CONTROLLER='        mutableState.update { it.copy(notice = ProgramNotice.GENERATION_UNAVAILABLE) }
        val result = ProgramGenerationResult.Refused(
            ProgramGenerationRefusal.NoExerciseStatesItsFocus(0)
        )'

# 11. The production generation consults the Stage-1 adaptive classification — a collaborator P24 has
#     no source for, and §30 step 11 forbids this generation to reach for. A real call, not a token in
#     a comment: the returned preference is fed into the request, so the plan changes.
ADAPTIVE_ANCHOR='            preferences = preferences,'
ADAPTIVE='            preferences = preferences.copy(
                adaptivePreferredExerciseIds = com.monkfitness.app.domain.adaptive.integration
                    .NoExerciseFamilyClassification.familyOf("pushups")?.let { listOf(it) }
                    ?: emptyList()
            ),'

# A payload variable that shares a name with a path variable silently overwrites the path, so a row
# is applied to (and preflighted against) the *mutation text* as a filename. That happened twice in
# this stage's own drafting, and the symptom — a FileNotFoundError whose message is the mutation —
# is unreadable. Refuse to start rather than discover it 20 minutes in.
for path_var in MAIN CLASSIFICATION SERVICE BOUNDARY CONTROLLER RECONCILER WORK LOCK LOG; do
    if grep -qE "^${path_var}='" scripts/program-stage24-red-mutations.sh; then
        echo "ABORT: payload variable \$$path_var collides with a path variable of the same name" >&2
        exit 1
    fi
done

echo '== preflight =='

preflight 'a missing classification is admitted as all focuses' "$CLASSIFICATION" "$ALL_FOCUS_ANCHOR" "$ALL_FOCUS"
preflight 'a missing classification is silently dropped' "$CLASSIFICATION" "$DROPPED_ANCHOR" "$DROPPED"
preflight 'membership is inferred from the category' "$CLASSIFICATION" "$CATEGORY_ANCHOR" "$CATEGORY"
preflight 'membership is inferred from the body region' "$CLASSIFICATION" "$SUBCATEGORY_ANCHOR" "$SUBCATEGORY"
preflight 'membership is inferred from the training-style map' "$CLASSIFICATION" "$FAMILYMAP_ANCHOR" "$FAMILYMAP"
preflight 'available equipment is widened the legacy way' "$SERVICE" "$EQUIPMENT_ANCHOR" "$EQUIPMENT"
preflight 'the production pass bypasses ProgramGeneratedEditor' "$SERVICE" "$EDITOR_ANCHOR" "$EDITOR"
preflight 'the production pass bypasses PlanReconciler' "$SERVICE" "$BYPASS_RECONCILER_ANCHOR" "$BYPASS_RECONCILER"
preflight 'reconciliation stops preserving user content' "$RECONCILER" "$OWNERSHIP_ANCHOR" "$OWNERSHIP"
preflight 'the controller still reports GENERATION_UNAVAILABLE' "$CONTROLLER" "$BYPASS_CONTROLLER_ANCHOR" "$BYPASS_CONTROLLER"
preflight 'the production pass consults the Stage-1 adaptive source' "$SERVICE" "$ADAPTIVE_ANCHOR" "$ADAPTIVE"

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

mutate 'a missing classification is admitted as all focuses' "$CLASSIFICATION" "$ALL_FOCUS_ANCHOR" "$ALL_FOCUS"
mutate 'a missing classification is silently dropped' "$CLASSIFICATION" "$DROPPED_ANCHOR" "$DROPPED"
mutate 'membership is inferred from the category' "$CLASSIFICATION" "$CATEGORY_ANCHOR" "$CATEGORY"
mutate 'membership is inferred from the body region' "$CLASSIFICATION" "$SUBCATEGORY_ANCHOR" "$SUBCATEGORY"
mutate 'membership is inferred from the training-style map' "$CLASSIFICATION" "$FAMILYMAP_ANCHOR" "$FAMILYMAP"
mutate 'available equipment is widened the legacy way' "$SERVICE" "$EQUIPMENT_ANCHOR" "$EQUIPMENT"
mutate 'the production pass bypasses ProgramGeneratedEditor' "$SERVICE" "$EDITOR_ANCHOR" "$EDITOR"
mutate 'the production pass bypasses PlanReconciler' "$SERVICE" "$BYPASS_RECONCILER_ANCHOR" "$BYPASS_RECONCILER"
mutate 'reconciliation stops preserving user content' "$RECONCILER" "$OWNERSHIP_ANCHOR" "$OWNERSHIP"
mutate 'the controller still reports GENERATION_UNAVAILABLE' "$CONTROLLER" "$BYPASS_CONTROLLER_ANCHOR" "$BYPASS_CONTROLLER"
mutate 'the production pass consults the Stage-1 adaptive source' "$SERVICE" "$ADAPTIVE_ANCHOR" "$ADAPTIVE"

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
