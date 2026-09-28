#!/usr/bin/env bash
# Stage 18 RED mutations: every deliberate break of the persisted target schedule source must be
# caught, and the mutated production source must be restored byte-identically.
#
# The oracle is the Stage 18 behavioural suites (on a real SQLite engine) plus the Stage 18
# architecture gate, together with the schema, migration and composition-root suites whose closed
# lists this stage extended. The behavioural suites answer "did the stored semantics survive?";
# the architecture gate answers "is the boundary still one-way?" — and the source is only finished
# when both still hold.
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
#    signature and every field declaration count, so the test source set compiles and a real oracle
#    runs.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

MAIN=app/src/main/java/com/monkfitness/app
TARGET_DIR="$MAIN/domain/program/target"
USECASE_DIR="$MAIN/domain/usecase"
DATA_DIR="$MAIN/data"

VALUE="$USECASE_DIR/TargetScheduleSource.kt"
BRIDGE="$USECASE_DIR/TargetScheduleSourceBridge.kt"
REPOSITORY="$DATA_DIR/repository/TargetScheduleSourceRepository.kt"
MAPPER="$DATA_DIR/mapper/TargetScheduleSourceMappers.kt"
DAO="$DATA_DIR/local/ProgramTargetScheduleSourceDao.kt"
RULE_ENTITY="$DATA_DIR/model/ProgramTargetScheduleRuleEntity.kt"
BINDING_ENTITY="$DATA_DIR/model/ProgramTargetProgramDayBindingEntity.kt"

# Every file any row below touches. A file missing from this list keeps its mutation, and the next
# run's *control* then fails for a reason that has nothing to do with the code.
SOURCES=("$VALUE" "$BRIDGE" "$REPOSITORY" "$MAPPER" "$DAO" "$RULE_ENTITY" "$BINDING_ENTITY")

WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage18-red"
LOCK="$WORK.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ABORT: another Stage 18 mutation run owns $LOCK" >&2
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

# The Stage 18 gate, the Stage 18 behavioural suites, and the closed-list suites whose contracts this
# stage extended: the schema fixtures, the migration chain, the ownership cascade, the data-access
# layering gate and the composition-root gates.
run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceArchitectureTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceRepositoryTest' \
        --tests 'com.monkfitness.app.data.repository.TargetScheduleSourceIntegrationTest' \
        --tests 'com.monkfitness.app.data.repository.ProgramDataAccessArchitectureTest' \
        --tests 'com.monkfitness.app.data.local.ProgramSchemaTest' \
        --tests 'com.monkfitness.app.data.local.ProgramMigrationPreservationTest' \
        --tests 'com.monkfitness.app.data.local.ProgramOwnershipCascadeTest' \
        --tests 'com.monkfitness.app.data.local.AdaptivePersistenceSchemaTest' \
        --tests 'com.monkfitness.app.di.AppContainerTest' \
        --tests 'com.monkfitness.app.di.CompositionRootArchitectureTest' \
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

# 1. Revision scoping lost: the read is scoped by the rule identity instead of the revision.
#    Scoping by *nothing* would leave `:revisionId` unbound, which Room's KSP refuses — so the honest
#    version of this defect is the one a careless query edit actually produces: a predicate on the
#    wrong column. It is type-correct, it compiles, and two revisions that state the same rule
#    identity now collide instead of being kept apart.
DAO_SCOPE_ANCHOR='WHERE `revisionId` = :revisionId ORDER BY `ruleId` ASC")'
DAO_SCOPE_LOST='WHERE `ruleId` = :revisionId ORDER BY `ruleId` ASC")'

# 2./3. The rule's own two identities are read out of a shared constant instead of their columns. Both
#      are perfectly typed, so only the behavioural round trip can tell.
MAPPER_RULE_ANCHOR='        ruleId = ruleId,'
MAPPER_RULE_LOST='        ruleId = "rule-from-somewhere",'
MAPPER_WORKOUT_ANCHOR='        workoutId = workoutId,'
MAPPER_WORKOUT_LOST='        workoutId = "workout-from-somewhere",'

# 4./5. The cadence payloads. `EveryNDays(3)` read back as `EveryNDays(7)` keeps the *form* and loses
#      the *value*; `SessionsPerWeek(3)` read back as a three-weekday `FixedWeekdays` is the
#      normalization this phase exists to forbid, and both are ordinary typed expressions.
MAPPER_EVERY_ANCHOR='            requireNotNull(cadenceDays) {
                "a stored EVERY_N_DAYS cadence has no interval to read"
            }'
MAPPER_EVERY_LOST='            requireNotNull(cadenceDays) {
                "a stored EVERY_N_DAYS cadence has no interval to read"
            } + 4'
MAPPER_FREQ_ANCHOR='        ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK -> ScheduleCadence.SessionsPerWeek(
            requireNotNull(cadenceSessionsPerWeek) {
                "a stored SESSIONS_PER_WEEK cadence has no frequency to read"
            }
        )'
MAPPER_FREQ_LOST='        ProgramTargetScheduleRuleEntity.SESSIONS_PER_WEEK -> ScheduleCadence.FixedWeekdays(
            setOf(*DayOfWeek.entries.take(
                requireNotNull(cadenceSessionsPerWeek) {
                    "a stored SESSIONS_PER_WEEK cadence has no frequency to read"
                }
            ).toTypedArray())
        )'

# 6./7. FixedWeekdays and the derived rule's source. The first reads the weekday column back as a
#      single day; the second substitutes the row's own rule for the named source, which is exactly
#      the "reconstruct the identity from what is at hand" defect.
# The anchor contains literal single quotes, which a bash single-quoted string cannot carry. It is
# therefore assembled with printf, with %s standing in for each quote character, so the text here is
# byte-identical to the source's and no escaping can silently drift it.
MAPPER_WEEKDAY_ANCHOR=$(printf 'private fun storedWeekdays(stored: String): Set<DayOfWeek> = stored.split(",")\n    .map { name ->\n        DayOfWeek.entries.firstOrNull { day -> day.name == name.trim() }\n            ?: throw IllegalArgumentException(\n                "a stored cadenceWeekdays must name days of $DAY_NAMES, was %s$name%s"\n            )\n    }\n    .toSet()' "'" "'")
MAPPER_WEEKDAY_LOST='private fun storedWeekdays(stored: String): Set<DayOfWeek> = setOf(
    DayOfWeek.entries.first { day -> day.name == stored.substringBefore(",") }
)'
MAPPER_SOURCE_ANCHOR='            requireNotNull(cadenceSourceRuleId) {
                "a stored DERIVED_EXCLUDING cadence names no source rule to read"
            }'
MAPPER_SOURCE_LOST='            requireNotNull(ruleId) {
                "a stored DERIVED_EXCLUDING cadence names no source rule to read"
            }'

# 8. The anchor date is read as a constant instead of the stored column.
MAPPER_ANCHOR_ANCHOR='        anchorDate = storedDate("program_target_schedule_rule.anchorDate", anchorDate)'
MAPPER_ANCHOR_LOST='        anchorDate = LocalDate.of(1970, 1, 1)'

# 9. The stored binding is replaced by one inferred from the revision's first plan day. The repository
#    owns a day DAO, so the inference compiles — which is precisely why the gate has to refuse it.
REPO_BIND_ANCHOR='            if (binding.programDayId.value !in ownDays) {'
REPO_BIND_LOST='            val inferred = ownDays.firstOrNull() ?: binding.programDayId.value
            if (inferred !in ownDays) {'

# 10./11. Duplicate claims stop being refused. Both keep the write compiling and both let a
#       semantically impossible source land, which is what the primary key alone cannot catch because
#       the duplicate is refused *before* the database is reached.
REPO_DUP_RULE_ANCHOR='            if (!ruleIdentities.add(rule.ruleId)) {'
REPO_DUP_RULE_LOST='            if (false) {'
REPO_DUP_BIND_ANCHOR='            if (!boundWorkouts.add(binding.workoutId)) {'
REPO_DUP_BIND_LOST='            if (false) {'

# 12. A missing source becomes an empty valid source — the one collapse this phase forbids.
REPO_MISSING_ANCHOR='            return TargetScheduleSourceRead.Missing(revisionId)'
REPO_MISSING_LOST='            return TargetScheduleSourceRead.Source(
                TargetScheduleSource(revisionId, emptyList(), emptyList())
            )'

# 13. Cross-revision bindings stop being refused, so the membership check is removed entirely.
REPO_CROSSREV_ANCHOR='            if (binding.programDayId.value !in ownDays) {'
REPO_CROSSREV_LOST='            if (false) {'

# 14. The legacy schedule becomes the source of a target rule. A pure domain file cannot reach the
#     data layer, so the mutation lives in the mapper and reads the revision's legacy columns through
#     a typed reference — real, compiling code at the layer the gate forbids.
MAPPER_LEGACY_ANCHOR='internal fun ProgramTargetScheduleRuleEntity.toTargetScheduleRule(): TargetScheduleDefinition =
    TargetScheduleDefinition(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = storedCadence(),
        anchorDate = storedDate("program_target_schedule_rule.anchorDate", anchorDate)
    )'
MAPPER_LEGACY_LOST='internal fun ProgramTargetScheduleRuleEntity.toTargetScheduleRule(): TargetScheduleDefinition {
    val legacy: Class<*> = com.monkfitness.app.domain.program.ProgramSchedule::class.java
    val legacyWeekdays: Class<*> = com.monkfitness.app.domain.program.ProgramSchedule.FixedWeekdays::class.java
    return TargetScheduleDefinition(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = storedCadence(),
        anchorDate = storedDate("program_target_schedule_rule.anchorDate", anchorDate)
    )
}'

# 15. The bridge stops reading the persisted source and hands back an empty pair — a caller would then
#     schedule nothing while believing it had read something.
BRIDGE_ANCHOR='    suspend fun definitionsAndBindingsOf(revisionId: RevisionId): TargetScheduleSourceRead =
        sourceRepository.sourceOf(revisionId)'
BRIDGE_LOST='    suspend fun definitionsAndBindingsOf(revisionId: RevisionId): TargetScheduleSourceRead =
        TargetScheduleSourceRead.Source(TargetScheduleSource(revisionId, emptyList(), emptyList()))'

echo '== preflight =='

preflight 'read stops being scoped to one revision' "$DAO" "$DAO_SCOPE_ANCHOR" "$DAO_SCOPE_LOST"
preflight 'rule identity read from a constant' "$MAPPER" "$MAPPER_RULE_ANCHOR" "$MAPPER_RULE_LOST"
preflight 'workout identity read from a constant' "$MAPPER" "$MAPPER_WORKOUT_ANCHOR" "$MAPPER_WORKOUT_LOST"
preflight 'EveryNDays interval changed' "$MAPPER" "$MAPPER_EVERY_ANCHOR" "$MAPPER_EVERY_LOST"
preflight 'SessionsPerWeek normalized into a weekday set' "$MAPPER" "$MAPPER_FREQ_ANCHOR" "$MAPPER_FREQ_LOST"
preflight 'FixedWeekdays collapsed to one day' "$MAPPER" "$MAPPER_WEEKDAY_ANCHOR" "$MAPPER_WEEKDAY_LOST"
preflight 'DerivedExcluding source rule lost' "$MAPPER" "$MAPPER_SOURCE_ANCHOR" "$MAPPER_SOURCE_LOST"
preflight 'anchor date read from a constant' "$MAPPER" "$MAPPER_ANCHOR_ANCHOR" "$MAPPER_ANCHOR_LOST"
preflight 'stored binding replaced by an inferred plan day' "$REPOSITORY" "$REPO_BIND_ANCHOR" "$REPO_BIND_LOST"
preflight 'duplicate rule identity accepted' "$REPOSITORY" "$REPO_DUP_RULE_ANCHOR" "$REPO_DUP_RULE_LOST"
preflight 'duplicate workout binding accepted' "$REPOSITORY" "$REPO_DUP_BIND_ANCHOR" "$REPO_DUP_BIND_LOST"
preflight 'missing source converted to an empty source' "$REPOSITORY" "$REPO_MISSING_ANCHOR" "$REPO_MISSING_LOST"
preflight 'cross-revision program day binding accepted' "$REPOSITORY" "$REPO_CROSSREV_ANCHOR" "$REPO_CROSSREV_LOST"
preflight 'target source derived from the legacy ProgramSchedule' "$MAPPER" "$MAPPER_LEGACY_ANCHOR" "$MAPPER_LEGACY_LOST"
preflight 'bridge bypasses the persisted source' "$BRIDGE" "$BRIDGE_ANCHOR" "$BRIDGE_LOST"

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

mutate 'read stops being scoped to one revision' "$DAO" "$DAO_SCOPE_ANCHOR" "$DAO_SCOPE_LOST"
mutate 'rule identity read from a constant' "$MAPPER" "$MAPPER_RULE_ANCHOR" "$MAPPER_RULE_LOST"
mutate 'workout identity read from a constant' "$MAPPER" "$MAPPER_WORKOUT_ANCHOR" "$MAPPER_WORKOUT_LOST"
mutate 'EveryNDays interval changed' "$MAPPER" "$MAPPER_EVERY_ANCHOR" "$MAPPER_EVERY_LOST"
mutate 'SessionsPerWeek normalized into a weekday set' "$MAPPER" "$MAPPER_FREQ_ANCHOR" "$MAPPER_FREQ_LOST"
mutate 'FixedWeekdays collapsed to one day' "$MAPPER" "$MAPPER_WEEKDAY_ANCHOR" "$MAPPER_WEEKDAY_LOST"
mutate 'DerivedExcluding source rule lost' "$MAPPER" "$MAPPER_SOURCE_ANCHOR" "$MAPPER_SOURCE_LOST"
mutate 'anchor date read from a constant' "$MAPPER" "$MAPPER_ANCHOR_ANCHOR" "$MAPPER_ANCHOR_LOST"
mutate 'stored binding replaced by an inferred plan day' "$REPOSITORY" "$REPO_BIND_ANCHOR" "$REPO_BIND_LOST"
mutate 'duplicate rule identity accepted' "$REPOSITORY" "$REPO_DUP_RULE_ANCHOR" "$REPO_DUP_RULE_LOST"
mutate 'duplicate workout binding accepted' "$REPOSITORY" "$REPO_DUP_BIND_ANCHOR" "$REPO_DUP_BIND_LOST"
mutate 'missing source converted to an empty source' "$REPOSITORY" "$REPO_MISSING_ANCHOR" "$REPO_MISSING_LOST"
mutate 'cross-revision program day binding accepted' "$REPOSITORY" "$REPO_CROSSREV_ANCHOR" "$REPO_CROSSREV_LOST"
mutate 'target source derived from the legacy ProgramSchedule' "$MAPPER" "$MAPPER_LEGACY_ANCHOR" "$MAPPER_LEGACY_LOST"
mutate 'bridge bypasses the persisted source' "$BRIDGE" "$BRIDGE_ANCHOR" "$BRIDGE_LOST"

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
