#!/usr/bin/env bash
# Stage 26 RED mutations: every deliberate break of the claim that **generation is explainable and
# previewable** — that a Preview runs the same pass as a Generate, changes nothing until the user says
# so, says what it would do to the user's own content, and says what could not be planned — must be
# caught, and every mutated production source must be restored byte-identically.
#
# The oracle is the P26 behavioural suites (the same-pass service suite, the controller measured on
# real storage) together with the P26 architecture gate, plus the gates whose claims P26 touches or
# depends on:
#   * `GenerationPreviewArchitectureTest` — the UI/generated-domain boundary and the invalidation rule;
#   * `ProgramsArchitectureTest` — the Program UI's own collaborator census and its §16/§33 bans;
#   * `GoalsFocusArchitectureTest` — P25's one-source-of-truth rule for the configuration;
#   * `ProgramsGoalsFocusTest` — P25's controller behaviour, which the two "focus change leaves an old
#     preview applicable" rows are measured against;
#   * `ProgramsLocalizationTest` — §14's resource rule for the copy this stage adds;
#   * `ProgramGenerationServiceTest` + `ProgramGenerationFlowArchitectureTest` — P24's chain, which P26
#     must not change.
#
# Nine harness rules, each enforced below, exactly as Stages 20–25 used them:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. Reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe strips comments and asserts the mutated line
#    survived as real code.
#  * **a no-op mutation is never a catch.** The probe asserts the mutated source actually differs, so a
#    drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row preserves every public
#    signature, so the test source set compiles and a real oracle runs.
#  * **the rows are observably distinct.** Fourteen rows, fourteen claims.
#  * **each row is self-contained** — applied alone, so no row depends on a declaration another
#    introduces.
#  * **one runner per tree** (exclusive lock), because two runs on one tree restore each other's
#    mutations and every row then scores against unreviewed bytes.
#  * **no payload variable may share a name with a path variable.**
#  * **a mutation aimed at a UI rule must reach real code at the layer the oracle reads.**
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
CONTROLLER="$MAIN/ui/programs/ProgramsController.kt"
MODEL="$MAIN/ui/programs/ProgramGenerationPreviewUi.kt"
SCREEN="$MAIN/ui/screens/ProgramEditorScreen.kt"
SERVICE="$MAIN/domain/usecase/ProgramGenerationService.kt"
NOTICE="$MAIN/ui/programs/ProgramNotice.kt"

SOURCES=("$CONTROLLER" "$MODEL" "$SCREEN" "$SERVICE" "$NOTICE")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage26-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 26 mutation run owns $LOCK" >&2
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
        --tests 'com.monkfitness.app.ui.programs.ProgramsGenerationPreviewTest' \
        --tests 'com.monkfitness.app.ui.programs.GenerationPreviewArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsGoalsFocusTest' \
        --tests 'com.monkfitness.app.ui.programs.GoalsFocusArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsLocalizationTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramGenerationPreviewServiceTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramGenerationServiceTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramGenerationFlowArchitectureTest' \
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

# 1. The brief's first invariant, inverted: Preview immediately replaces the working draft. This is the
#    exact shape of a "Preview" that is a Generate wearing a different label — the screen looks right,
#    the numbers are right, and the user's half-built draft is simply gone. Caught by the isolation suite
#    (the draft is no longer what it was) and by the gate (previewDraft now assigns `workingDraft`).
INSTALLS_ANCHOR='            is ProgramGenerationResult.Generated -> {
                pendingPreview = result.edit'
INSTALLS='            is ProgramGenerationResult.Generated -> {
                workingDraft = result.edit.draft
                pendingPreview = result.edit'

# 2. Preview reaches persistence. The service takes a repository-shaped collaborator it does not have by
#    declaring an unused second declaration of the forbidden vocabulary — a *real* collaborator reference
#    in the file, not a comment, so the gate that reads real code sees it. A preview that could write
#    would be a hidden Save, which is the one thing §30 step 26 forbids.
PERSISTENCE_ANCHOR='    private val ids: DraftIdSource,'
PERSISTENCE='    private val ids: DraftIdSource,
    @Suppress("UNUSED_PARAMETER")
    private val programRepository: com.monkfitness.app.data.repository.ProgramRepository? = null,'

# 3. Apply reaches persistence too: the second of the two ways Preview could become a hidden Save. It is
#    written through `runBlocking` because `saver.save` is a `suspend` function and Apply is not: the
#    obvious one-line form does not compile, and a mutation that does not compile is charged as
#    `NOT A CATCH`. A real "somebody wired the save in here" edit has the same shape.
APPLY_SAVES_ANCHOR='        workingDraft = preview.draft'
APPLY_SAVES='        workingDraft = preview.draft
        kotlinx.coroutines.runBlocking {
            saver.save(preview.draft, mutableState.value.draftPlannedStartDate)
        }'

# 4. A user's own content is silently dropped: the reconciliation the Preview reports has its conflicts
#    filtered down to the user-authored ones, so a *pinned* exercise the plan disagreed with is presented
#    as agreement. §7's *conflicts with user choices are shown explicitly* becomes false while the plan
#    itself is untouched — which is why only a behavioural row can catch it. The shape is the one a
#    careless implementation takes: "report the overrides, forget the pins".
HIDES_CONFLICTS_ANCHOR='            conflicts = edit.reconciliation.conflicts.map { change ->'
HIDES_CONFLICTS='            conflicts = edit.reconciliation.conflicts
                .filter { it.level == PreservationLevel.USER_OVERRIDE }
                .map { change ->'

# 5. The planner's limitations are swallowed: a `NoPlannableFocus` or an `UnusableFocus` the planner
#    reported becomes no line on the Preview whenever a plan was produced at all, and the user reads
#    that plan as if every focus in their selection were in it. §33's *"no silent substitution"* in its
#    most user-visible shape, and the shape a careless implementation takes: "we got days, so there is
#    nothing to warn about".
HIDES_LIMITATIONS_ANCHOR='            limitations = edit.plan.limitations.map { limitation ->'
HIDES_LIMITATIONS='            limitations = edit.plan.limitations
                .takeIf { edit.plan.slots.isEmpty() }
                .orEmpty()
                .map { limitation ->'

# 6. Apply does not install the prospective draft: the draft the user accepted is discarded and the
#    working one is republished instead. The preview disappears and the plan the user said yes to is
#    never in the editor — a silent no-op that reads as "done".
APPLY_NOINSTALL_ANCHOR='        pendingPreview = null
        workingDraft = preview.draft
        // The review and the preview both describe a draft that is no longer the working one.
        publishDraft(review = null)'
APPLY_NOINSTALL='        pendingPreview = null
        // The review and the preview both describe a draft that is no longer the working one.
        publishDraft(review = null)'

# 7. A draft edit stops invalidating an existing preview. The user edits the name and the preview built
#    for the previous draft stays on screen and stays applicable — a plan built for a draft that no
#    longer exists, offered as if it described the current one.
EDIT_KEEPS_PREVIEW_ANCHOR='        clearGenerationPreview()
        publishDraft(review = null)'
EDIT_KEEPS_PREVIEW='        if (false) clearGenerationPreview()
        publishDraft(review = null)'

# 8. §14 bypassed: the domain's own developer-facing sentence reaches the Preview as a resource *and*
#    the reason mapping is bypassed, so a Russian or Ukrainian reader is shown
#    `MOBILITY cannot be planned: …`. The row replaces the whole mapping expression with a hand-written
#    English sentence assembled in the UI layer — exactly what §14 and the brief's §4 forbid.
RAW_MESSAGE_ANCHOR='                        reasonRes = focusUnusableReasonRes(limitation.reason)'
RAW_MESSAGE='                        reasonRes = ProgramGenerationPreviewRes.REASON_NO_EXERCISE'

# 9. The UI layer receives a generated-domain object directly: the Preview model gains a field holding
#    the whole `ReconciliationReport`, which a screen could then read, filter or re-count. Caught by the
#    gate's field-type sweep rather than by behaviour — nothing about the numbers changes.
#    It is *added* rather than substituted, precisely so no caller is disturbed and a real oracle runs;
#    replacing the existing field would break the screen's compilation and prove nothing.
UI_HOLDS_DOMAIN_ANCHOR='    val changes: List<ProgramGenerationPreviewChangeUi> = emptyList()
'
UI_HOLDS_DOMAIN='    val changes: List<ProgramGenerationPreviewChangeUi> = emptyList(),
    val rawReport: com.monkfitness.app.domain.program.generated.ReconciliationReport? = null,'

# 10. Preview stops using ProgramGenerationService and assembles its own pass from the planner — the
#    brief's §13.11. It type-checks (every collaborator is in scope through the service's own file) and
#     produces the same plan today, which is exactly why it is silent. The classifier comes from the
#     service's own `catalogue` port rather than from the shipped catalogue constant, because a second
#     reader of `WorkoutGenerator` would be a second owner of it (P23's closed-reader rule).
SECOND_PATH_ANCHOR='    ): ProgramGenerationResult = edit(draft, availableEquipment)

    /**
     * The one pass all three buttons run'
SECOND_PATH='    ): ProgramGenerationResult = ProgramGenerationResult.Generated(
        com.monkfitness.app.domain.program.generated.ProgramGeneratedEditor(draft, ids)
        .generate(ProductionGenerationBoundary.generationRequest(
            catalogue = catalogue.catalogueOf(focusSource),
            focus = draft.focus,
            schedule = draft.schedule,
            duration = draft.duration,
            availableEquipment = availableEquipment,
            preferences = preferences,
            policy = policy
        ))
    )

    /**
     * The one pass all three buttons run'

# 11. Generate leaves an old pending preview visible: the pass is applied immediately and the preview the
#     user never chose stays on screen beside it, describing a plan that is not the plan in the draft.
#     The mutation narrows the invalidation to "there was nothing to apply", which is exactly the
#     condition under which it does not matter — and it is a *careful* miss rather than a deleted line,
#     so the rule is caught by behaviour rather than by the census of call sites.
GENERATE_KEEPS_PREVIEW_ANCHOR='        clearGenerationPreview()
        val result = try {
'
GENERATE_KEEPS_PREVIEW='        if (pendingPreview?.draft == null) clearGenerationPreview()
        val result = try {
'

# 12. A focus change leaves an old preview applicable. §30 step 26's invalidation list names *Goals &
#     Focus* explicitly, and a focus change is the one that changes what the next pass would plan for —
#     so a stale preview after one is the most misleading case of the whole rule. The row routes the
#     focus change away from the invalidating funnel and writes the field directly, so it type-checks.
FOCUS_KEEPS_PREVIEW_ANCHOR='    fun setDraftFocus(focus: FocusPlan) =
        editDraft { draft -> editor.editor(draft).withFocus(focus).draft }'
FOCUS_KEEPS_PREVIEW='    fun setDraftFocus(focus: FocusPlan) {
        val current = workingDraft ?: return
        workingDraft = editor.editor(current).withFocus(focus).draft
        publishDraft(review = null)
    }'

# 13. Preview's own notice says the plan was generated into the draft — the one sentence that makes the
#     "nothing changed" claim false on screen. It is the same resource Generate uses, so no key is
#     missing and every localization check still passes.
PREVIEW_SAYS_GENERATED_ANCHOR='                        notice = ProgramNotice.GENERATION_PREVIEWED'
PREVIEW_SAYS_GENERATED='                        notice = ProgramNotice.GENERATED'

# 14. A removed day is counted as a removed exercise: `DAY_REMOVED` is folded into the exercise-level
#     counts, so a user reads "Removed: 4" after losing four whole *days*. Caught by the reconciliation
#     suite, which measures the two figures separately for exactly this reason.
DAY_AS_DROPPED_ANCHOR='            droppedCount = edit.reconciliation.droppedCount,'
DAY_AS_DROPPED='            droppedCount = edit.reconciliation.droppedCount + edit.reconciliation.removedDayCount,'

# A payload variable that shares a name with a path variable silently overwrites the path, so a row is
# applied to (and preflighted against) the *mutation text* as a filename.
for path_var in MAIN CONTROLLER MODEL SCREEN SERVICE NOTICE WORK LOCK; do
    if grep -qE "^${path_var}='" scripts/program-stage26-red-mutations.sh; then
        echo "ABORT: payload variable \$$path_var collides with a path variable of the same name" >&2
        exit 1
    fi
done

echo '== preflight =='

preflight 'Preview immediately replaces the working draft' "$CONTROLLER" "$INSTALLS_ANCHOR" "$INSTALLS"
preflight 'Preview reaches persistence' "$SERVICE" "$PERSISTENCE_ANCHOR" "$PERSISTENCE"
preflight 'Apply accidentally calls Save' "$CONTROLLER" "$APPLY_SAVES_ANCHOR" "$APPLY_SAVES"
preflight 'Preview hides reconciliation conflicts' "$CONTROLLER" "$HIDES_CONFLICTS_ANCHOR" "$HIDES_CONFLICTS"
preflight 'Preview hides planner limitations' "$CONTROLLER" "$HIDES_LIMITATIONS_ANCHOR" "$HIDES_LIMITATIONS"
preflight 'Apply does not install the prospective draft' "$CONTROLLER" "$APPLY_NOINSTALL_ANCHOR" "$APPLY_NOINSTALL"
preflight 'a draft edit does not invalidate the preview' "$CONTROLLER" "$EDIT_KEEPS_PREVIEW_ANCHOR" "$EDIT_KEEPS_PREVIEW"
preflight 'the UI reads a raw limitation reason' "$CONTROLLER" "$RAW_MESSAGE_ANCHOR" "$RAW_MESSAGE"
preflight 'the UI holds a generated-domain object' "$MODEL" "$UI_HOLDS_DOMAIN_ANCHOR" "$UI_HOLDS_DOMAIN"
preflight 'Preview uses a second generation path' "$SERVICE" "$SECOND_PATH_ANCHOR" "$SECOND_PATH"
preflight 'Generate leaves an old pending preview visible' "$CONTROLLER" "$GENERATE_KEEPS_PREVIEW_ANCHOR" "$GENERATE_KEEPS_PREVIEW"
preflight 'a focus change leaves an old preview applicable' "$CONTROLLER" "$FOCUS_KEEPS_PREVIEW_ANCHOR" "$FOCUS_KEEPS_PREVIEW"
preflight 'the Preview notice claims the draft changed' "$CONTROLLER" "$PREVIEW_SAYS_GENERATED_ANCHOR" "$PREVIEW_SAYS_GENERATED"
preflight 'a removed day is counted as a removed exercise' "$CONTROLLER" "$DAY_AS_DROPPED_ANCHOR" "$DAY_AS_DROPPED"

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

mutate 'Preview immediately replaces the working draft' "$CONTROLLER" "$INSTALLS_ANCHOR" "$INSTALLS"
mutate 'Preview reaches persistence' "$SERVICE" "$PERSISTENCE_ANCHOR" "$PERSISTENCE"
mutate 'Apply accidentally calls Save' "$CONTROLLER" "$APPLY_SAVES_ANCHOR" "$APPLY_SAVES"
mutate 'Preview hides reconciliation conflicts' "$CONTROLLER" "$HIDES_CONFLICTS_ANCHOR" "$HIDES_CONFLICTS"
mutate 'Preview hides planner limitations' "$CONTROLLER" "$HIDES_LIMITATIONS_ANCHOR" "$HIDES_LIMITATIONS"
mutate 'Apply does not install the prospective draft' "$CONTROLLER" "$APPLY_NOINSTALL_ANCHOR" "$APPLY_NOINSTALL"
mutate 'a draft edit does not invalidate the preview' "$CONTROLLER" "$EDIT_KEEPS_PREVIEW_ANCHOR" "$EDIT_KEEPS_PREVIEW"
mutate 'the UI reads a raw limitation reason' "$CONTROLLER" "$RAW_MESSAGE_ANCHOR" "$RAW_MESSAGE"
mutate 'the UI holds a generated-domain object' "$MODEL" "$UI_HOLDS_DOMAIN_ANCHOR" "$UI_HOLDS_DOMAIN"
mutate 'Preview uses a second generation path' "$SERVICE" "$SECOND_PATH_ANCHOR" "$SECOND_PATH"
mutate 'Generate leaves an old pending preview visible' "$CONTROLLER" "$GENERATE_KEEPS_PREVIEW_ANCHOR" "$GENERATE_KEEPS_PREVIEW"
mutate 'a focus change leaves an old preview applicable' "$CONTROLLER" "$FOCUS_KEEPS_PREVIEW_ANCHOR" "$FOCUS_KEEPS_PREVIEW"
mutate 'the Preview notice claims the draft changed' "$CONTROLLER" "$PREVIEW_SAYS_GENERATED_ANCHOR" "$PREVIEW_SAYS_GENERATED"
mutate 'a removed day is counted as a removed exercise' "$CONTROLLER" "$DAY_AS_DROPPED_ANCHOR" "$DAY_AS_DROPPED"

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