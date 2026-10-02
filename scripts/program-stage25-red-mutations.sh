#!/usr/bin/env bash
# Stage 25 RED mutations: every deliberate break of the claim that **the user states the goal and the
# focus, and that statement is what the draft, Generate and Save act on** — must be caught, and every
# mutated production source must be restored byte-identically.
#
# The oracle is the P25 behavioural suites (the pure authoring rules, and the controller measured on
# real storage) together with the P25 architecture gate, plus the gates whose claims P25 touches or
# depends on:
#   * `ProgramsArchitectureTest` — the Program UI's own collaborator census and its §16/§33 bans;
#   * `ProgramsLocalizationTest` — §14's resource rule for the seven locales this stage adds copy to;
#   * `FocusPlanTest` — §8's own invariants, which every row below either satisfies or must not break;
#   * `ProgramGenerationServiceTest` + `ProgramGenerationFlowArchitectureTest` — P24's chain, which
#     P25 must not change and whose behaviour the "focus not forwarded" rows are measured against.
#
# Nine harness rules, each enforced below, exactly as Stages 20–24 used them:
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
#  * **the rows are observably distinct.** Ten rows, ten claims.
#  * **each row is self-contained** — applied alone, so no row depends on a declaration another
#    introduces.
#  * **one runner per tree** (exclusive lock), because two runs on one tree restore each other's
#    mutations and every row then scores against unreviewed bytes.
#  * **no payload variable may share a name with a path variable** (the P24 harness's own latent bug).
#  * **a mutation aimed at a UI rule must reach real code at the layer the oracle reads.** Three rows
#    below rewrite the *screen*; a token added to a comment there would be invisible, so each one
#    changes a line that a Composable actually executes.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
RULES="$MAIN/ui/programs/GoalsFocusAuthoring.kt"
SCREEN="$MAIN/ui/screens/ProgramEditorScreen.kt"
CONTROLLER="$MAIN/ui/programs/ProgramsController.kt"
SERVICE="$MAIN/domain/usecase/ProgramGenerationService.kt"
STRUCTURE="$MAIN/domain/program/ProgramStructure.kt"

SOURCES=("$RULES" "$SCREEN" "$CONTROLLER" "$SERVICE" "$STRUCTURE")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage25-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 25 mutation run owns $LOCK" >&2
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
        --tests 'com.monkfitness.app.ui.programs.GoalsFocusAuthoringTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsGoalsFocusTest' \
        --tests 'com.monkfitness.app.ui.programs.GoalsFocusArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsControllerTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsArchitectureTest' \
        --tests 'com.monkfitness.app.ui.programs.ProgramsLocalizationTest' \
        --tests 'com.monkfitness.app.domain.program.FocusPlanTest' \
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

# 1. The draft's configuration is silently ignored: the controller accepts the user's choice and writes
#    back the draft's own default. This is the exact shape of "the screen works and nothing changes" —
#    no crash, no exception, and a plan generated for a goal the user did not state. Caught by the
#    controller suite (the presentation no longer carries the change) and by the gate (the controller
#    no longer publishes `draft.focus`).
CONTROLLER_ANCHOR='    fun setDraftFocus(focus: FocusPlan) =
        editDraft { draft -> editor.editor(draft).withFocus(focus).draft }'
CONTROLLER_IGNORED='    fun setDraftFocus(focus: FocusPlan) {
        editDraft { draft -> draft }
    }'

# 2. The user's configuration is replaced by §8's DEFAULT before it reaches the draft — the "no hidden
#    defaults" rule stated as code, at the one place a finished configuration is handed to the
#    controller. A Focused(PUSH, PULL) selection becomes BALANCED, which is the failure mode the whole
#    stage exists to prevent, and it is invisible on screen: the section renders whatever the draft
#    holds. It is the FOCUSED chooser's own confirmation line, so the mutation is real code a
#    Composable executes — not a token a comment-stripping gate could miss.
REPLACE_WITH_DEFAULT_ANCHOR='                onFocus(FocusPlan.focused(focuses))'
REPLACE_WITH_DEFAULT='                onFocus(FocusPlan.DEFAULT)'

# 3. A focus change stops being a structural change: the review and Save no longer see a difference,
#    so a user who re-plans their goal silently saves nothing. This is the ONE row that mutates the
#    domain's own structural comparison rather than a UI file, because that comparison is where §6's
#    rule lives; the row is in the snapshot set so it is restored with everything else.
STRUCTURAL_ANCHOR='    ProgramStructureAspect.FOCUS -> focus != base.focus'
STRUCTURAL_GONE='    ProgramStructureAspect.FOCUS -> false'

# 4. The CUSTOM sum validation is bypassed: the domain's own refusal is caught and the entry reports a
#    configuration anyway. The allocation the domain refused is then *repaired* by dividing what is left
#    over the focuses the user named — the exact "completes the user's percentages" defect. It is built
#    through `FocusPlan.custom`, so it type-checks and every downstream value is a legal FocusPlan.
TOTAL_BYPASS_ANCHOR='    fun toFocusPlan(): FocusPlan.Custom? = try {
        FocusPlan.custom(statedFocuses().map { focus -> FocusAllocation(focus, percentOf(focus)) })
    } catch (refused: IllegalArgumentException) {
        null
    }'
TOTAL_BYPASS='    fun toFocusPlan(): FocusPlan.Custom? {
        val stated = statedFocuses()
        val total = stated.sumOf { focus -> percentOf(focus) }
        val left = FocusPlan.FULL_ALLOCATION - total
        val shares = if (stated.isEmpty()) {
            listOf(FocusAllocation(Focus.entries.first(), FocusPlan.FULL_ALLOCATION))
        } else {
            stated.map { focus ->
                FocusAllocation(
                    focus,
                    if (left > 0 && focus == stated.last()) percentOf(focus) + left else percentOf(focus)
                )
            }
        }
        return FocusPlan.custom(shares)
    }'

# 5. The tap order becomes a hidden priority: the FOCUSED factory keeps the order the user happened to
#    assemble instead of the vocabulary's own. Two selections naming the same focuses would then be
#    *different configurations*, and a rank the user never stated would be stored and could be read back
#    as one. Caught by the canonical-ordering test and by the gate's no-ranking rule.
PRIORITY_ANCHOR='    return FocusPlan.focused(chosen)'
PRIORITY='    return FocusPlan.Focused(chosen.toList())'

# 6. The user's focus is not forwarded to generation: the service plans for the balanced
#    configuration whatever the draft says. This is the row the brief names — `Focused(PUSH, PULL)` must
#    not be able to become `Balanced` anywhere on the path — and it is caught on the row level (the plan
#    comes from exercises outside the user's selection) rather than by a value comparison.
NOT_FORWARDED_ANCHOR='            focus = draft.focus,
            schedule = draft.schedule,'
NOT_FORWARDED='            focus = FocusPlan.DEFAULT,
            schedule = draft.schedule,'

# 7. Generate writes persistence: the service takes a repository-shaped collaborator it does not have by
#    declaring an unused second declaration of the forbidden vocabulary — a *real* collaborator reference
#    in the file, not a comment, so the gate that reads real code sees it. It is a second declaration
#    rather than a retyped parameter precisely so that no caller's compilation is disturbed and a real
#    oracle runs.
PERSISTENCE_ANCHOR='    private val ids: DraftIdSource,'
PERSISTENCE='    private val ids: DraftIdSource,
    @Suppress("UNUSED_PARAMETER")
    private val programRepository: com.monkfitness.app.data.repository.ProgramRepository? = null,'

# 8. An invalid custom state is accepted: the entry a dialog starts from is no longer empty, but already
#    states the whole 100% for the vocabulary's first focus. A user who opened the CUSTOM editor and typed
#    nothing would be handed a complete, legal `Custom(PUSH=100%)` — a focus they never chose, from a
#    number they never typed, produced by the *default value* of the working state rather than by a
#    visible default the user could correct.
#    The first version of this row mutated `remainingPercent()` instead, and was correctly scored MISSED:
#    for an empty entry `FULL_ALLOCATION - 0` already equals `FULL_ALLOCATION`, so the mutant was
#    behaviourally IDENTICAL and proved nothing (hygiene rule: an equivalent mutant is not evidence).
INVALID_ACCEPTED_ANCHOR='    val percents: Map<Focus, Int> = emptyMap()'
INVALID_ACCEPTED='    val percents: Map<Focus, Int> = mapOf(Focus.PUSH to FocusPlan.FULL_ALLOCATION)'

# 9. The controller bypasses the draft editor: the draft's focus field is written directly, so the one
#    operation §7 makes responsible for the change is skipped. Everything else about the draft still
#    holds, which is what makes it read as a harmless shortcut in review.
BYPASS_EDITOR_ANCHOR='            focus = draft.focus,'
BYPASS_EDITOR='            focus = FocusPlan.DEFAULT,'

# 10. The screen edits a copy instead of the draft: the multi-select keeps its own selection state and
#     hands nothing to the controller, so the chips change on screen and the draft does not. The copy is
#     a real value in real code (the rule's own layer), which is what makes this row observable at all.
SCREEN_COPY_ANCHOR='    var choosingFocuses by remember { mutableStateOf(false) }'
SCREEN_COPY='    var choosingFocuses by remember { mutableStateOf(false) }
    var shadowFocus: FocusPlan? by remember { mutableStateOf(null) }'

# A payload variable that shares a name with a path variable silently overwrites the path, so a row is
# applied to (and preflighted against) the *mutation text* as a filename.
for path_var in MAIN RULES SCREEN CONTROLLER SERVICE STRUCTURE WORK LOCK; do
    if grep -qE "^${path_var}='" scripts/program-stage25-red-mutations.sh; then
        echo "ABORT: payload variable \$$path_var collides with a path variable of the same name" >&2
        exit 1
    fi
done

echo '== preflight =='

preflight 'the draft focus change is silently ignored' "$CONTROLLER" "$CONTROLLER_ANCHOR" "$CONTROLLER_IGNORED"
preflight 'the chosen configuration is replaced by FocusPlan.DEFAULT' "$SCREEN" "$REPLACE_WITH_DEFAULT_ANCHOR" "$REPLACE_WITH_DEFAULT"
preflight 'a focus change stops being structural' "$STRUCTURE" "$STRUCTURAL_ANCHOR" "$STRUCTURAL_GONE"
preflight 'the CUSTOM total validation is bypassed' "$RULES" "$TOTAL_BYPASS_ANCHOR" "$TOTAL_BYPASS"
preflight 'the tap order becomes a hidden priority' "$RULES" "$PRIORITY_ANCHOR" "$PRIORITY"
preflight 'the user focus is not forwarded to generation' "$SERVICE" "$NOT_FORWARDED_ANCHOR" "$NOT_FORWARDED"
preflight 'the generation service reaches persistence' "$SERVICE" "$PERSISTENCE_ANCHOR" "$PERSISTENCE"
preflight 'an invalid custom state is accepted' "$RULES" "$INVALID_ACCEPTED_ANCHOR" "$INVALID_ACCEPTED"
preflight 'the controller bypasses the draft editor' "$CONTROLLER" "$BYPASS_EDITOR_ANCHOR" "$BYPASS_EDITOR"
preflight 'the screen keeps its own copy of the configuration' "$SCREEN" "$SCREEN_COPY_ANCHOR" "$SCREEN_COPY"

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

mutate 'the draft focus change is silently ignored' "$CONTROLLER" "$CONTROLLER_ANCHOR" "$CONTROLLER_IGNORED"
mutate 'the chosen configuration is replaced by FocusPlan.DEFAULT' "$SCREEN" "$REPLACE_WITH_DEFAULT_ANCHOR" "$REPLACE_WITH_DEFAULT"
mutate 'a focus change stops being structural' "$STRUCTURE" "$STRUCTURAL_ANCHOR" "$STRUCTURAL_GONE"
mutate 'the CUSTOM total validation is bypassed' "$RULES" "$TOTAL_BYPASS_ANCHOR" "$TOTAL_BYPASS"
mutate 'the tap order becomes a hidden priority' "$RULES" "$PRIORITY_ANCHOR" "$PRIORITY"
mutate 'the user focus is not forwarded to generation' "$SERVICE" "$NOT_FORWARDED_ANCHOR" "$NOT_FORWARDED"
mutate 'the generation service reaches persistence' "$SERVICE" "$PERSISTENCE_ANCHOR" "$PERSISTENCE"
mutate 'an invalid custom state is accepted' "$RULES" "$INVALID_ACCEPTED_ANCHOR" "$INVALID_ACCEPTED"
mutate 'the controller bypasses the draft editor' "$CONTROLLER" "$BYPASS_EDITOR_ANCHOR" "$BYPASS_EDITOR"
mutate 'the screen keeps its own copy of the configuration' "$SCREEN" "$SCREEN_COPY_ANCHOR" "$SCREEN_COPY"

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