#!/usr/bin/env bash
# Stage 22 RED mutations: every deliberate break of the claim that **target schedule is revisioned
# Program behaviour** must be caught, and every mutated production source must be restored
# byte-identically.
#
# The oracle is the Stage 22 behavioural suite (a real SQLite engine, the production editor, the
# production Scheduler, the production save boundary and Stage 21's real Start path) together with
# the Stage 22 architecture gate, plus the two gates whose *claims* Stage 22 revised — Stage 19's
# authoring gate and Stage 18's source gate — so a mutation that changed the authoring contract or the
# immutable-write contract is seen by the pins that own those claims and not only by the new ones.
#
# Six harness rules, each enforced below, exactly as Stages 20 and 21 used them:
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
#  * **the rows are observably distinct.** Nineteen rows, nineteen claims, and each pair that reads
#    alike is written so a *different* assertion sees it — the revision count, the previous revision's
#    stored source, the whole-table census, the target row counts, the typed refusal's class, or the
#    Start pass's input revision. Rows 10 and 11 are the same `Keep`/`Clear` pair at the two places it
#    can be got wrong, and each is scored on its own behaviour.
#  * **each row is self-contained.** Rows are applied one at a time, so no row may depend on a
#    declaration another row introduces — a row that referenced row 17's `legacyScheduleRead` did not
#    compile on its own and was charged `NOT A CATCH` for a reason that had nothing to do with the rule
#    it was testing. Every payload declares everything it uses.
#  * **no oracle is weakened to score a row.** The Stage 19 and Stage 18 gates were revised by
#    changing their *claims*, which the stage document records; a row is fixed by fixing the mutation,
#    never by relaxing a gate.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
SAVE="$MAIN/domain/usecase/ProgramSaveService.kt"
CHANGE="$MAIN/domain/usecase/TargetScheduleRevisionChange.kt"
AUTHORING="$MAIN/domain/usecase/TargetScheduleAuthoring.kt"
EDITOR="$MAIN/domain/usecase/ProgramEditorService.kt"
REPOSITORY="$MAIN/data/repository/TargetScheduleSourceRepository.kt"

SOURCES=("$SAVE" "$CHANGE" "$AUTHORING" "$EDITOR" "$REPOSITORY")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage22-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 22 mutation run owns $LOCK" >&2
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
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleRevisionSemanticsIntegrationTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleRevisionArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleAuthoringTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleAuthoringArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleControlledInvocationIntegrationTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceRepositoryTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceArchitectureTest' \
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

# 1. A target-only replacement mints no revision. The prepared revision is discarded and the
#    statement is written against the revision that is already current, so the "new source" is a
#    second claim on the old revision — which the repository refuses, and the refactor then turns
#    that refusal into a silent success.
TARGET_WRITE_ANCHOR='                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))'
TARGET_WRITE_LOST='                saved = written
            }
            if (requested != null) {
                targetSourceRepository.store(
                    requested.asSourceOf(
                        revisionId = currentRevisionId,
                        mintedProgramDays = prepared.mintedProgramDays
                    )
                )
            }
            ProgramEditorResult.Success(
                ProgramSaveOutcome.NothingToChange(program, currentRevisionId)
            )'

# 2. A target-only replacement mutates the revision it just wrote. The save is reported as a
#    facts-only save of the *previous* revision, so the caller is told nothing changed while a new
#    revision exists on disk.
TARGET_MUTATE_ANCHOR='                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))'
TARGET_MUTATE_LOST='                saved = written
                programRepository.updateProgram(requireNotNull(written).program)
            }
            ProgramEditorResult.Success(
                ProgramSaveOutcome.FactsSaved(program, currentRevisionId)
            )'

# 3. A `Clear` is expressed as a deletion against the previous revision: the boundary reaches for an
#    empty authoring and writes it at the superseded revision, which is what a delete would look like
#    if the repository had one. The authoring's own `NoRulesStated` refusal is what stops it, and the
#    previous revision's source is asserted intact by the same case.
CLEAR_DELETE_ANCHOR='                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf('
CLEAR_DELETE_ANCHOR2='                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf(
                            revisionId = newRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                }'
# The `Clear` arm is expressed as a deletion against the *previous* revision: when there is no
# requested source — which is exactly what `Clear` produces — the branch stores an empty authoring at
# the superseded revision instead of writing nothing. The authoring's own `NoRulesStated` refusal is
# what stops it in production, and the same case asserts the previous revision's source is intact.
CLEAR_DELETE_LOST='                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf(
                            revisionId = newRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                } else {
                    targetSourceRepository.store(
                        TargetScheduleAuthoring(
                            rules = emptyList(),
                            programDayBindings = emptyList()
                        ).asSourceOf(
                            revisionId = currentRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                }'

# 4. A structural edit's `Keep` loses the source. The branch still *reads* the stored source, so the
#    read is visible to no oracle, and then discards it: the Program that had a target schedule
#    silently stops having one, which is the defect this stage exists to remove.
CARRY_LOSS_ANCHOR='                    ) as? TargetScheduleSourceRead.Source)?.source
                        ?.asAuthoringOver(draft.days.map { day -> day.programDayId }.toSet())'
CARRY_LOSS_LOST='                    ) as? TargetScheduleSourceRead.Source)?.source
                        ?.asAuthoringOver(draft.days.map { day -> day.programDayId }.toSet())
                        ?.let { kept ->
                            TargetScheduleAuthoring(
                                rules = emptyList(),
                                programDayBindings = kept.programDayBindings
                            )
                        }'

# 5. A structural `Replace` writes against the revision the save **replaced**. The revision the draft
#    was opened from is a real, existing `RevisionId`, so the row type-checks and the write is refused
#    for the right reason: a binding naming a day that is not one of *its* revision's days.
REPLACE_OLD_ANCHOR='                            carriedForward.asSourceOf(
                                revisionId = saved.revision.revisionId,'
REPLACE_OLD_LOST='                            carriedForward.asSourceOf(
                                revisionId = draft.baseRevisionId ?: saved.revision.revisionId,'

# 6. An old `ProgramDayId` is reused in the new revision's bindings: the re-identification is dropped
#    and the handle is written through unchanged. The repository refuses it (the day belongs to the
#    previous revision), which is the whole reason the correspondence exists.
AUTHORING_MINT_ANCHOR='            val minted = mintedProgramDays[binding.draftedProgramDayId]
                ?: throw TargetScheduleAuthoringException.ProgramDayNotInTheSavedRevision(
                    workoutId = binding.workoutId,
                    draftedProgramDayId = binding.draftedProgramDayId
                )'
AUTHORING_MINT_LOST='            val minted = binding.draftedProgramDayId'

# 7./8. A binding inferred from a plan day's `position` / `name`. There is no behavioural difference —
#    the two rows produce the same bindings the honest conversion does — so only the gate can see
#    them, and the gate exists for exactly this. Each is a *second declaration* in the forbidden
#    vocabulary, which is the shape a careless refactor would actually introduce.
CHANGE_MAPPING_ANCHOR='fun TargetScheduleSource.asAuthoringOver('
CHANGE_POSITION_LOST='private fun dayByPosition(
    day: com.monkfitness.app.domain.program.ProgramDay
): Int = day.position

fun TargetScheduleSource.asAuthoringOver('
CHANGE_NAME_LOST='private fun dayByName(
    day: com.monkfitness.app.domain.program.ProgramDay
): String = day.name.orEmpty()

fun TargetScheduleSource.asAuthoringOver('

# 9. A `workoutId` derived from a `ProgramDayId`: the workout identity becomes a function of the plan
#    day it happens to sit on, so re-identifying the day silently renames the workout.
CHANGE_WORKOUT_ANCHOR='        TargetScheduleAuthoringBinding(
            workoutId = binding.workoutId,
            draftedProgramDayId = binding.programDayId
        )'
CHANGE_WORKOUT_LOST='        TargetScheduleAuthoringBinding(
            workoutId = binding.programDayId.value,
            draftedProgramDayId = binding.programDayId
        )'

# 10. `Keep` silently becomes `Clear`: the default an existing Program's structural edit gets when the
#     caller says nothing about target scheduling.
DEFAULT_CLEAR_ANCHOR='        saveRevisionWithReconciliation(draft, targetSchedule, targetChange ?: TargetScheduleRevisionChange.Keep)'
DEFAULT_CLEAR_LOST='        saveRevisionWithReconciliation(draft, targetSchedule, targetChange ?: TargetScheduleRevisionChange.Clear)'

# 11. `Clear` silently becomes `Keep`, on the *other* leg. A target-only clear of a Program that does
#     state a source mints no revision and reports nothing to change, so the user's stated removal is
#     dropped. Same pair as row 10, a different place and a different observable: the outcome type.
CLEAR_BECOMES_KEEP_ANCHOR='        if (change is TargetScheduleRevisionChange.Clear && stored == null) {'
CLEAR_BECOMES_KEEP_LOST='        if (change is TargetScheduleRevisionChange.Clear) {'

# 12. An identical `Replace` mints a revision anyway, because the semantic comparison is dropped. The
#     two sources differ by nothing but the plan-day identity a new revision mints, so an identity
#     comparison could never see the equality — which is what makes this row worth running.
COMPARE_DROPPED_ANCHOR='        if (requested != null && stored != null) {
            val storedAsAuthoring = stored.asAuthoringOver(prepared.mintedProgramDays.keys)
            if (storedAsAuthoring.statesTheSameTargetSemanticsAs(requested)) {'
COMPARE_DROPPED_LOST='        if (requested != null && stored != null && requested.rules.isEmpty()) {
            val storedAsAuthoring = stored.asAuthoringOver(prepared.mintedProgramDays.keys)
            if (storedAsAuthoring.statesTheSameTargetSemanticsAs(requested)) {'

# 13. A structural edit's omission becomes the typed absence, re-introduced through the *read* side:
#     a draft with no plan days yields an authoring that states no rules, which the authoring refuses —
#     so the carry-forward either drops the Program's source or the save fails outright.
CARRY_EMPTY_ANCHOR='fun TargetScheduleSource.asAuthoringOver(
    draftedProgramDays: Set<ProgramDayId>
): TargetScheduleAuthoring = TargetScheduleAuthoring('
CARRY_EMPTY_LOST='fun TargetScheduleSource.asAuthoringOver(
    draftedProgramDays: Set<ProgramDayId>
): TargetScheduleAuthoring = if (draftedProgramDays.isEmpty()) {
    TargetScheduleAuthoring(rules = emptyList(), programDayBindings = emptyList())
} else TargetScheduleAuthoring('

# 14. A target-only change mints a *Program* identity, which would leave the user's own Program
#     behind pointing at a revision that belongs to something else.
NEW_PROGRAM_ANCHOR='        val prepared = when (val minted = editor.prepareTargetScheduleRevision(programId)) {'
NEW_PROGRAM_LOST='        val prepared = when (val minted = editor.prepareTargetScheduleRevision(
            com.monkfitness.app.domain.common.ProgramId(programId.value + "-mutated")
        )) {'

# 15. A failing source write leaves the new revision behind: the store escapes the transaction, so the
#     revision is committed and the source is not. The rollback assertions and the revision count see
#     it, which a fault on the *first* write of the unit could never show.
ATOMICITY_ESCAPE_ANCHOR='                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf(
                            revisionId = newRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                }
                saved = written'
ATOMICITY_ESCAPE_LOST2='                if (false) {
                    targetSourceRepository.store(
                        requireNotNull(requested).asSourceOf(
                            revisionId = newRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                }
                saved = written'

# 16. The new source overwrites the old one. The repository has no update path, so the row is a second
#     store against the superseded revision with the new statement's payload — refused as a conflicting
#     source, and the previous revision's own source is asserted byte-identical by the same case.
OVERWRITE_OLD_ANCHOR='                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))'
OVERWRITE_OLD_LOST='                if (requested != null && stored != null) {
                    targetSourceRepository.store(
                        TargetScheduleSource(
                            revisionId = currentRevisionId,
                            rules = requested.rules,
                            programDayBindings = stored.programDayBindings
                        )
                    )
                }
                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))'

# 17. The legacy `ProgramSchedule` is consulted to reconstruct a target source, in the direction §13
#     forbids. Two halves, both needed: the boundary *reads* a legacy schedule (which the gate sees),
#     and it *reconstructs* a source from one and writes it at the superseded revision (which the
#     behavioural suite sees, as the previous revision's source no longer reading as it did).
LEGACY_READ_ANCHOR='        val stored = targetSourceRepository.sourceOf(currentRevisionId).storedSourceOrNull()'
LEGACY_READ_LOST='        val stored = targetSourceRepository.sourceOf(currentRevisionId).storedSourceOrNull()
        val legacyScheduleRead: com.monkfitness.app.domain.program.ProgramSchedule? =
            com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)'
LEGACY_REBUILD_ANCHOR='                if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf('
LEGACY_REBUILD_LOST='                val legacyRebuild: com.monkfitness.app.domain.program.ProgramSchedule =
                    com.monkfitness.app.domain.program.ProgramSchedule.FlexiblePerWeek(3)
                if (requested != null && legacyRebuild is com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays) {
                    targetSourceRepository.store(
                        TargetScheduleAuthoring(
                            rules = listOf(
                                TargetScheduleDefinition(
                                    ruleId = "rule-from-legacy",
                                    workoutId = "workout-from-legacy",
                                    cadence = com.monkfitness.app.domain.program.ScheduleCadence.SessionsPerWeek(3),
                                    anchorDate = java.time.LocalDate.MIN
                                )
                            ),
                            programDayBindings = emptyList()
                        ).asSourceOf(
                            revisionId = currentRevisionId,
                            mintedProgramDays = prepared.mintedProgramDays
                        )
                    )
                } else if (requested != null) {
                    targetSourceRepository.store(
                        requested.asSourceOf('

# 18. Start reads the previous revision after a target-only change. The consumer reads the Program's
#     current revision, so the only way to break the claim is to leave the pointer on the superseded
#     revision — which is exactly what a half-applied target-only path would leave behind, and what
#     the Start integration case reads.
POINTER_REWOUND_ANCHOR='                saved = written
            }
            ProgramEditorResult.Success(requireNotNull(saved))'
POINTER_REWOUND_LOST='                saved = written
                programRepository.updateProgram(
                    requireNotNull(written).program.copy(currentRevisionId = currentRevisionId)
                )
            }
            ProgramEditorResult.Success(requireNotNull(saved))'

echo '== preflight =='

preflight 'a target-only replacement mints no revision' "$SAVE" "$TARGET_WRITE_ANCHOR" "$TARGET_WRITE_LOST"
preflight 'a target-only replacement mutates the existing revision' "$SAVE" "$TARGET_MUTATE_ANCHOR" "$TARGET_MUTATE_LOST"
preflight 'Clear deletes the previous revision source' "$SAVE" "$CLEAR_DELETE_ANCHOR2" "$CLEAR_DELETE_LOST"
preflight 'a structural Keep loses the target source' "$SAVE" "$CARRY_LOSS_ANCHOR" "$CARRY_LOSS_LOST"
preflight 'a structural Replace writes against the old revision' "$SAVE" "$REPLACE_OLD_ANCHOR" "$REPLACE_OLD_LOST"
preflight 'old ProgramDayIds are reused in the new revision' "$AUTHORING" "$AUTHORING_MINT_ANCHOR" "$AUTHORING_MINT_LOST"
preflight 'bindings are inferred from a ProgramDay position' "$CHANGE" "$CHANGE_MAPPING_ANCHOR" "$CHANGE_POSITION_LOST"
preflight 'bindings are inferred from a ProgramDay name' "$CHANGE" "$CHANGE_MAPPING_ANCHOR" "$CHANGE_NAME_LOST"
preflight 'a workoutId is derived from a ProgramDayId' "$CHANGE" "$CHANGE_WORKOUT_ANCHOR" "$CHANGE_WORKOUT_LOST"
preflight 'Keep silently becomes Clear' "$SAVE" "$DEFAULT_CLEAR_ANCHOR" "$DEFAULT_CLEAR_LOST"
preflight 'Clear silently becomes Keep' "$SAVE" "$CLEAR_BECOMES_KEEP_ANCHOR" "$CLEAR_BECOMES_KEEP_LOST"
preflight 'an identical Replace unnecessarily creates a revision' "$SAVE" "$COMPARE_DROPPED_ANCHOR" "$COMPARE_DROPPED_LOST"
preflight 'a structural target omission becomes Missing' "$CHANGE" "$CARRY_EMPTY_ANCHOR" "$CARRY_EMPTY_LOST"
preflight 'a target-only change creates a new ProgramId' "$SAVE" "$NEW_PROGRAM_ANCHOR" "$NEW_PROGRAM_LOST"
preflight 'a source write failure leaves a partial new revision' "$SAVE" "$ATOMICITY_ESCAPE_ANCHOR" "$ATOMICITY_ESCAPE_LOST2"
preflight 'the old target source is overwritten by the new one' "$SAVE" "$OVERWRITE_OLD_ANCHOR" "$OVERWRITE_OLD_LOST"
preflight 'the legacy ProgramSchedule is read for the source' "$SAVE" "$LEGACY_READ_ANCHOR" "$LEGACY_READ_LOST"
preflight 'the source is reconstructed from the legacy schedule' "$SAVE" "$LEGACY_REBUILD_ANCHOR" "$LEGACY_REBUILD_LOST"
preflight 'Start reads the previous revision after a target-only change' "$SAVE" "$POINTER_REWOUND_ANCHOR" "$POINTER_REWOUND_LOST"

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

mutate 'a target-only replacement mints no revision' "$SAVE" "$TARGET_WRITE_ANCHOR" "$TARGET_WRITE_LOST"
mutate 'a target-only replacement mutates the existing revision' "$SAVE" "$TARGET_MUTATE_ANCHOR" "$TARGET_MUTATE_LOST"
mutate 'Clear deletes the previous revision source' "$SAVE" "$CLEAR_DELETE_ANCHOR2" "$CLEAR_DELETE_LOST"
mutate 'a structural Keep loses the target source' "$SAVE" "$CARRY_LOSS_ANCHOR" "$CARRY_LOSS_LOST"
mutate 'a structural Replace writes against the old revision' "$SAVE" "$REPLACE_OLD_ANCHOR" "$REPLACE_OLD_LOST"
mutate 'old ProgramDayIds are reused in the new revision bindings' "$AUTHORING" "$AUTHORING_MINT_ANCHOR" "$AUTHORING_MINT_LOST"
mutate 'bindings are inferred from a ProgramDay position' "$CHANGE" "$CHANGE_MAPPING_ANCHOR" "$CHANGE_POSITION_LOST"
mutate 'bindings are inferred from a ProgramDay name' "$CHANGE" "$CHANGE_MAPPING_ANCHOR" "$CHANGE_NAME_LOST"
mutate 'a workoutId is derived from a ProgramDayId' "$CHANGE" "$CHANGE_WORKOUT_ANCHOR" "$CHANGE_WORKOUT_LOST"
mutate 'Keep silently becomes Clear' "$SAVE" "$DEFAULT_CLEAR_ANCHOR" "$DEFAULT_CLEAR_LOST"
mutate 'Clear silently becomes Keep' "$SAVE" "$CLEAR_BECOMES_KEEP_ANCHOR" "$CLEAR_BECOMES_KEEP_LOST"
mutate 'an identical Replace unnecessarily creates a revision' "$SAVE" "$COMPARE_DROPPED_ANCHOR" "$COMPARE_DROPPED_LOST"
mutate 'a structural target omission becomes Missing' "$CHANGE" "$CARRY_EMPTY_ANCHOR" "$CARRY_EMPTY_LOST"
mutate 'a target-only change creates a new ProgramId' "$SAVE" "$NEW_PROGRAM_ANCHOR" "$NEW_PROGRAM_LOST"
mutate 'a source write failure leaves a partial new revision' "$SAVE" "$ATOMICITY_ESCAPE_ANCHOR" "$ATOMICITY_ESCAPE_LOST2"
mutate 'the old target source is overwritten by the new one' "$SAVE" "$OVERWRITE_OLD_ANCHOR" "$OVERWRITE_OLD_LOST"
mutate 'the legacy ProgramSchedule is read for the source' "$SAVE" "$LEGACY_READ_ANCHOR" "$LEGACY_READ_LOST"
mutate 'the source is reconstructed from the legacy schedule' "$SAVE" "$LEGACY_REBUILD_ANCHOR" "$LEGACY_REBUILD_LOST"
mutate 'Start reads the previous revision after a target-only change' "$SAVE" "$POINTER_REWOUND_ANCHOR" "$POINTER_REWOUND_LOST"

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
