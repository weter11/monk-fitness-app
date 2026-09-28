#!/usr/bin/env bash
# Stage 19 RED mutations: every deliberate break of the explicit target schedule authoring path must be
# caught, and the mutated production source must be restored byte-identically.
#
# The oracle is the Stage 19 behavioural suite (on a real SQLite engine) plus the Stage 19 architecture
# gate, and — for the one row that mutates a Stage 18 primitive rather than a Stage 19 seam — the
# Stage 18 repository suite that *owns* that primitive. A mutation suite's focused oracle must include
# the suite that owns a mutated primitive: the importing stage's own tests can keep passing, correctly,
# while the primitive they merely use is broken.
#
# Four harness rules, each enforced below:
#
#  * **a compile error is never a catch.** A mutation that does not compile was seen by no oracle, so
#    nothing was proven about the rule. It is reported as `NOT A CATCH` and charged as a failure.
#  * **a comment-only mutation proves nothing.** Every gate strips comments first, so a banned token
#    inside a comment is invisible to it. The probe below strips comments and asserts the mutated
#    line survived as real code.
#  * **a no-op mutation is never a catch.** The probe also asserts the mutated source actually differs
#    from the original, so a drifted anchor cannot be mistaken for a mutation that applied.
#  * **a mutation must be type-correct in the *whole* tree.** Every row below preserves every public
#    signature, so the test source set compiles and a real oracle runs.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
USECASE_DIR="$MAIN/domain/usecase"
DATA_DIR="$MAIN/data"

AUTHORING="$USECASE_DIR/TargetScheduleAuthoring.kt"
SAVE="$USECASE_DIR/ProgramSaveService.kt"
SOURCE_REPOSITORY="$DATA_DIR/repository/TargetScheduleSourceRepository.kt"

# Every file any row below touches. A file missing from this list keeps its mutation, and the next
# run's *control* then fails for a reason that has nothing to do with the code.
SOURCES=("$AUTHORING" "$SAVE" "$SOURCE_REPOSITORY")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage19-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 19 mutation run owns $LOCK" >&2
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
RESULTS=()

restore() { for source in "${SOURCES[@]}"; do cp "$WORK/$(basename "$source").orig" "$source"; done; }

# The Stage 19 gate and the Stage 19 behavioural suite, the Save's own suite (which owns the
# creation-unit claim), and the Stage 18 suites that own the repository this stage applies the stated
# source through.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleAuthoringTest' \
        --tests 'com.monkfitness.app.domain.usecase.TargetScheduleAuthoringArchitectureTest' \
        --tests 'com.monkfitness.app.domain.usecase.ProgramSaveServiceTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceRepositoryTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceIntegrationTest' \
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
    # The probe is what makes a verdict mean something: it proves the mutation (a) changed the file and
    # (b) left real, comment-stripped code behind. A mutation that survives only in a comment, or that
    # failed to change anything, would otherwise be scored as a catch on no evidence.
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
        RESULTS+=("MISSED $label (did not compile)")
        FAIL=$((FAIL + 1))
    else
        echo "caught: $label"
        RESULTS+=("caught $label")
        PASS=$((PASS + 1))
    fi
    restore
}

# Every anchor must exist before the baseline and its replacement must not: a drifted anchor reports
# MISSED, which is a claim about this harness rather than about the code. The check is in Python, not
# grep -F, because GNU grep splits a multi-line pattern into separate OR-patterns.
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

# 1. The stated source is dropped on the way to storage. `null` is the whole of the absent case, so
#    removing the guard is exactly the defect: a caller that stated a source would be told the save
#    succeeded while the revision stored nothing.
SAVE_DROP_ANCHOR='        if (targetSchedule == null) return'
SAVE_DROP_LOST='        if (targetSchedule == null) {
            targetSourceRepository.store(
                TargetScheduleSource(revisionId, emptyList(), emptyList())
            )
            return
        }'

# 2./3./4./5. The four facts a rule is made of, each replaced by a value this stage must never
#      manufacture. All four are ordinary typed expressions on a data class, so only the read-back
#      round trip can tell them from the truth.
RULES_ANCHOR='        return TargetScheduleSource(revisionId = revisionId, rules = rules, programDayBindings = bindings)'
RULES_ANCHOR_LOST='        return TargetScheduleSource(
            revisionId = revisionId,
            rules = rules.map { it.copy(anchorDate = it.anchorDate.plusDays(1)) },
            programDayBindings = bindings
        )'
RULES_CADENCE_LOST='        return TargetScheduleSource(
            revisionId = revisionId,
            rules = rules.map { it.copy(cadence = com.monkfitness.app.domain.program.ScheduleCadence.Daily) },
            programDayBindings = bindings
        )'
RULES_RULEID_LOST='        return TargetScheduleSource(
            revisionId = revisionId,
            rules = rules.map { it.copy(ruleId = "rule-from-somewhere") },
            programDayBindings = bindings
        )'
RULES_WORKOUT_LOST='        return TargetScheduleSource(
            revisionId = revisionId,
            rules = rules.map { it.copy(workoutId = "workout-from-somewhere") },
            programDayBindings = bindings
        )'
RULES_BINDINGS_LOST='        return TargetScheduleSource(revisionId = revisionId, rules = rules, programDayBindings = emptyList())'

# 6. A binding is re-pointed at whatever day the saved revision happens to carry first. The map is
#    non-empty by construction here, so this compiles — which is precisely why the boundary has to
#    refuse a day the caller did not name.
BIND_LOOKUP_ANCHOR='            val minted = mintedProgramDays[binding.draftedProgramDayId]'
BIND_LOOKUP_LOST='            val minted = mintedProgramDays.values.first()'

# 7. The edit leg stores the stated source against the revision the save *replaced*. A saved revision is
#    immutable (§6), so this is either a refusal or a silent rewrite of another revision's semantics.
SAVE_OLDREV_ANCHOR='                    stateTargetSourceFor(
                        revisionId = saved.revision.revisionId,'
SAVE_OLDREV_LOST='                    stateTargetSourceFor(
                        revisionId = draft.baseRevisionId ?: saved.revision.revisionId,'

# 8. The typed absence is answered with a source instead of with nothing. A revision with no rules
#    cannot produce an occurrence, so "this revision states that it has no target semantics" is not a
#    weaker claim than the truth — it is a different and unrepresentable one.
SAVE_EMPTY_ANCHOR='        if (targetSchedule == null) return
        targetSourceRepository.store('
SAVE_EMPTY_LOST='        if (targetSchedule == null) {
            targetSourceRepository.store(TargetScheduleSource(revisionId, emptyList(), emptyList()))
            return
        }
        targetSourceRepository.store('

# 8b. The authoring'"'"'s own refusal of a rule-less statement is removed, so a caller that states no
#     rule is silently answered with "this revision states no target semantics" instead of a refusal.
AUTHORING_NORULES_ANCHOR='        if (rules.isEmpty()) {
            throw TargetScheduleAuthoringException.NoRulesStated
        }'
AUTHORING_NORULES_LOST='        if (rules.isEmpty() && mintedProgramDays.isEmpty()) {
            throw TargetScheduleAuthoringException.NoRulesStated
        }'

# 9. The legacy schedule becomes a source of target facts. The mutation is a *second declaration* in the
#    legacy vocabulary rather than a retyped parameter, so the whole tree still compiles and a real
#    architecture oracle has to be the one that refuses it.
AUTHORING_LEGACY_ANCHOR='class TargetScheduleAuthoring('
AUTHORING_LEGACY_LOST='private fun legacySourceFrom(
    legacy: com.monkfitness.app.domain.program.ProgramSchedule
): List<TargetScheduleDefinition> = listOf(
    TargetScheduleDefinition(
        ruleId = "rule-from-a-legacy-schedule",
        workoutId = "workout-from-a-legacy-schedule",
        cadence = com.monkfitness.app.domain.program.ScheduleCadence.Daily,
        anchorDate = java.time.LocalDate.of(1970, 1, 1)
    )
)

class TargetScheduleAuthoring('

# 10. The source write leaves the transaction that owns the revision, so a failure part-way through the
#     source leaves a saved revision with no source and possibly an orphaned one.
SAVE_ATOMIC_ANCHOR='            inTransaction {
                programRepository.createProgram(creation.program, creation.revision, slots)
                stateTargetSourceFor(
                    revisionId = creation.revision.revisionId,
                    mintedProgramDays = creation.mintedProgramDays,
                    targetSchedule = targetSchedule
                )
            }'
SAVE_ATOMIC_LOST='            inTransaction {
                programRepository.createProgram(creation.program, creation.revision, slots)
            }
            stateTargetSourceFor(
                revisionId = creation.revision.revisionId,
                mintedProgramDays = creation.mintedProgramDays,
                targetSchedule = targetSchedule
            )'

# 11. A revision'"'"'s immutability check stops being enforced, so a second source could land on a
#     revision that already has one. This mutates a Stage 18 primitive rather than a Stage 19 seam, so
#     its catch is owed by the Stage 18 repository suite — which is in the oracle list for that reason.
REPO_IMMUTABLE_ANCHOR='                if (stored.source == source) return else
                    throw TargetScheduleSourceException.ConflictingStoredSource(
                        revisionId = source.revisionId,
                        storedSource = stored.source,
                        requestedSource = source
                    )'
REPO_IMMUTABLE_LOST='                if (stored.source.revisionId == source.revisionId) return else
                    throw TargetScheduleSourceException.ConflictingStoredSource(
                        revisionId = source.revisionId,
                        storedSource = stored.source,
                        requestedSource = source
                    )'

echo '== preflight =='

preflight 'the stated target source is dropped' "$SAVE" "$SAVE_DROP_ANCHOR" "$SAVE_DROP_LOST"
preflight 'the stated anchor dates are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_ANCHOR_LOST"
preflight 'the stated cadence is replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_CADENCE_LOST"
preflight 'the stated rule identities are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_RULEID_LOST"
preflight 'the stated workout identities are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_WORKOUT_LOST"
preflight 'the stated bindings are dropped' "$AUTHORING" "$RULES_ANCHOR" "$RULES_BINDINGS_LOST"
preflight 'a binding is re-pointed at another plan day' "$AUTHORING" "$BIND_LOOKUP_ANCHOR" "$BIND_LOOKUP_LOST"
preflight 'the source is stored against the old revision' "$SAVE" "$SAVE_OLDREV_ANCHOR" "$SAVE_OLDREV_LOST"
preflight 'a missing source becomes an empty source' "$SAVE" "$SAVE_EMPTY_ANCHOR" "$SAVE_EMPTY_LOST"
preflight 'a rule-less authoring is accepted instead of refused' "$AUTHORING" "$AUTHORING_NORULES_ANCHOR" "$AUTHORING_NORULES_LOST"
preflight 'a legacy schedule is read as a target source' "$AUTHORING" "$AUTHORING_LEGACY_ANCHOR" "$AUTHORING_LEGACY_LOST"
preflight 'the source write leaves the revision transaction' "$SAVE" "$SAVE_ATOMIC_ANCHOR" "$SAVE_ATOMIC_LOST"
preflight "a saved revision's source may be written again" "$SOURCE_REPOSITORY" "$REPO_IMMUTABLE_ANCHOR" "$REPO_IMMUTABLE_LOST"

echo 'all mutation anchors verified'

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED; mutation verdicts are not meaningful'
    tail -60 "$WORK/last-run.log"
    exit 1
fi

echo '== mutations =='

mutate 'the stated target source is dropped' "$SAVE" "$SAVE_DROP_ANCHOR" "$SAVE_DROP_LOST"
mutate 'the stated anchor dates are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_ANCHOR_LOST"
mutate 'the stated cadence is replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_CADENCE_LOST"
mutate 'the stated rule identities are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_RULEID_LOST"
mutate 'the stated workout identities are replaced' "$AUTHORING" "$RULES_ANCHOR" "$RULES_WORKOUT_LOST"
mutate 'the stated bindings are dropped' "$AUTHORING" "$RULES_ANCHOR" "$RULES_BINDINGS_LOST"
mutate 'a binding is re-pointed at another plan day' "$AUTHORING" "$BIND_LOOKUP_ANCHOR" "$BIND_LOOKUP_LOST"
mutate 'the source is stored against the old revision' "$SAVE" "$SAVE_OLDREV_ANCHOR" "$SAVE_OLDREV_LOST"
mutate 'a missing source becomes an empty source' "$SAVE" "$SAVE_EMPTY_ANCHOR" "$SAVE_EMPTY_LOST"
mutate 'a rule-less authoring is accepted instead of refused' "$AUTHORING" "$AUTHORING_NORULES_ANCHOR" "$AUTHORING_NORULES_LOST"
mutate 'a legacy schedule is read as a target source' "$AUTHORING" "$AUTHORING_LEGACY_ANCHOR" "$AUTHORING_LEGACY_LOST"
mutate 'the source write leaves the revision transaction' "$SAVE" "$SAVE_ATOMIC_ANCHOR" "$SAVE_ATOMIC_LOST"
mutate "a saved revision's source may be written again" "$SOURCE_REPOSITORY" "$REPO_IMMUTABLE_ANCHOR" "$REPO_IMMUTABLE_LOST"

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
echo "caught: $PASS"
echo "missed: $FAIL"
[ "$FAIL" -eq 0 ]
