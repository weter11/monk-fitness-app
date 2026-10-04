#!/bin/bash
# RED mutation suite — P31 (production progression-relation persistence boundary).
#
# The contract every stage owes:
#   control run     must be GREEN  (nothing mutated)
#   every mutation  must be RED    (its owning suite fails)
#   every source    restored byte-identically, and the tree still clean afterwards
#
# P31's rules are held here by twelve mutations, and they are grouped by the claim each one breaks:
#
#   rows 1-6   the ladder is STORED AND READ WHOLE. A rung that is dropped, reordered, flattened,
#              collapsed, left with a gap in it, or duplicated is a ladder that no longer says what
#              it was authored to say — and every one of these is a plausible implementation rather
#              than a strawman, because each looks like a small simplification.
#   rows 7-8   a read answers for the family that was ASKED about, and only that family. Leaking
#              another family's rung, or fabricating one for an unknown family, are the two ways a
#              projection turns into an inference — and P31's whole claim is that it does neither.
#   row  9     the ladder is not `FamilyProgressionState`. A family's *current* exercise is one rung;
#              reading it as a hierarchy fabricates all the others.
#   row  10    the retired Stage-1 pilot's profiles stay unreachable.
#   row  11    the catalogue stays family-scoped. A Program or revision column here is the scope leak
#              the whole stage is built to avoid.
#   row  12    the provider is a projection, not a second source. A provider that constructs a
#              relation answers questions storage was supposed to answer.
#
# Four rules this script holds itself to, all learned the hard way in earlier stages:
#
#   * a **compile error is not a catch**. Every mutation below is a PLAUSIBLE implementation, so the
#     oracle has to discriminate on behaviour. `check_red` inspects the run log for a Kotlin error
#     before accepting a RED, and charges a non-compiling row as NOT-A-CATCH rather than as caught.
#
#   * a mutation that **does not apply** aborts the run rather than scoring a verdict. A no-op
#     mutation reads as a hole in the oracle and sends the next reader hunting for one that is not
#     there. `preflight` asserts BOTH that the anchor is present verbatim and that the replacement is
#     absent, before the control run is taken.
#
#   * every row is aimed at the layer that **DECIDES** the rule: the mapper for what a read
#     reconstructs, the repository for what a write assembles, the provider for what it answers.
#
#   * this suite does **not** repeat P30's mutations. Those proved the family is read rather than
#     inferred; these prove a stored ladder is stored whole and read back as itself.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/red-mutations-p31
rm -rf "$WORK" && mkdir -p "$WORK"

MAIN=app/src/main/java/com/monkfitness/app

MAPPER="$MAIN/data/mapper/ProgressionRelationMappers.kt"
REPOSITORY="$MAIN/data/repository/ProgressionRelationRepository.kt"
PROVIDER="$MAIN/domain/usecase/StoredProgressionRelationProvider.kt"
ENTITY="$MAIN/data/model/ProgressionRelationVariantEntity.kt"

# EVERY file any mutation touches. A file missing from this list keeps its mutation, and the NEXT run's
# control then fails with a real-looking compile error, so a contaminated baseline reads as a broken one.
FILES=(
    "$MAPPER"
    "$REPOSITORY"
    "$PROVIDER"
    "$ENTITY"
)

# The suites that OWN each rule. A mutation is only worth writing against the suite that owns the rule it
# breaks: the behavioural repository suite for a storage round trip, the catalogue suite for the
# empty-production proof, and the architecture gate for a structural claim.
REPOSITORY_SUITE="com.monkfitness.app.data.repository.ProgressionRelationRepositoryTest"
CATALOGUE_SUITE="com.monkfitness.app.data.local.ProgressionRelationCatalogueIntegrationTest"
GATE="com.monkfitness.app.domain.usecase.ProgressionRelationPersistenceArchitectureTest"

SUITES=("$REPOSITORY_SUITE" "$CATALOGUE_SUITE" "$GATE")

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

# mutate <file> <python-body> — the body receives the file text as `t` and must assign it back.
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
# replacement is NOT already there, BEFORE the control run.
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

# The anchors every storage mutation replaces. They are the load-bearing expressions of this stage:
#   STORE_READS  the read that returns one family's rows
#   ROWS_TO_VARS the map that turns those rows into the domain's variants
#   READ_RELATION the absence that must stay an absence
#   WRITE_MAP    the assembly of stored rows from a stored relation
# Each preflight's THIRD argument is the anchor the mutation replaces and the FOURTH is the marker the
# replacement would introduce. The marker is deliberately a bare, never-written token rather than the
# real replacement text: a marker that also occurs in the file's own KDoc would report a leftover
# mutation on a clean tree and abort the run for no reason.
READ_ROWS='        val rows = relationDao.variantsOfFamily(familyId)'
EMPTY_BRANCH='        if (rows.isEmpty()) return null'
ASSEMBLE='        return storedProgressionRelation(familyId, rows)'
# The multi-line anchors use bash ANSI-C quoting ($'...') because they contain a real newline: a
# single-quoted string cannot, and a one-line approximation would match text that is not there.
INJECTION_SITE=$'    private val inTransaction: suspend (suspend () -> Unit) -> Unit\n)'
ENTITY_KEY='@Entity(tableName = "progression_relation_variant", primaryKeys = ["familyId", "exerciseId"])'
ENTITY_TAIL=$'    val perSetTargets: List<Int>\n)'
TARGETS='    val targets = perSetTargets'
ENTITY_FIELD='    val familyId: String,'
READ_RELATION='        relations.relationOf(familyId)'

preflight "1 drop a variant" "$REPOSITORY" "$READ_ROWS" 'variantsOfFamily(familyId).drop(1)'
preflight "2 non-canonical order" "$REPOSITORY" "$READ_ROWS" 'variantsOfFamily(familyId).reversed()'
preflight "3 flatten prescription" "$MAPPER" "$TARGETS" 'perSetTargets.average()'
preflight "4 collapse same-level" "$REPOSITORY" "$ASSEMBLE" 'distinctBy { it.level }'
preflight "5 level gap" "$REPOSITORY" "$ASSEMBLE" 'renumbered'
preflight "6 duplicate exercise" "$ENTITY" "$ENTITY_KEY" 'primaryKeys = ["familyId", "exerciseId", "level"]'
preflight "7 another family" "$REPOSITORY" "$READ_ROWS" 'ifEmpty { relationDao'
preflight "8 fabricated default" "$REPOSITORY" "$EMPTY_BRANCH" 'prescription = com.monkfitness.app.domain.prescription.RepPrescription(listOf(10))'
preflight "9 family state as ladder" "$REPOSITORY" "$INJECTION_SITE" 'private val familyState: com.monkfitness.app.domain.adaptive.FamilyProgressionState? = null'
preflight "10 stage-1 pilot" "$REPOSITORY" "$INJECTION_SITE" 'private val PilotProgressionProfiles: Map<String, String>? = null'
preflight "11 program scope" "$ENTITY" "$ENTITY_TAIL" 'val revisionId: String = ""'
preflight "12 fabricate in provider" "$PROVIDER" "$READ_RELATION" '?: com.monkfitness.app.domain.adaptive.engine.'
echo "preflight OK"

# 1. One persisted variant is dropped from the read. A ladder missing one rung is still contiguous and
#    still canonical — it simply says something different, which is why a "looks plausible" read is the
#    exact defect here: the ceiling moves down and every step above the dropped rung disappears.
echo "=================== 1. one persisted variant is dropped ==================="
mutate "$REPOSITORY" '
old = "        val rows = relationDao.variantsOfFamily(familyId)"
new = "        val rows = relationDao.variantsOfFamily(familyId).drop(1)"
assert old in t
t = t.replace(old, new)
'
check_red "1 one persisted variant is dropped" "$REPOSITORY_SUITE"

# 2. The stored order is ignored and the rows are handed back in insertion order. The DAO's ORDER BY is
#    the canonical order the domain requires, so dropping it produces a value the domain then refuses —
#    which is the intended shape: canonicalisation belongs to one place, and this mutation removes it.
echo "=================== 2. the stored order is ignored ==================="
mutate "$REPOSITORY" '
old = "        val rows = relationDao.variantsOfFamily(familyId)"
new = "        val rows = relationDao.variantsOfFamily(familyId).reversed()"
assert old in t
t = t.replace(old, new)
'
check_red "2 the stored order is ignored and a non-canonical order is accepted" "$REPOSITORY_SUITE"

# 3. The per-set prescription is flattened to its mean. [12,10,8,6] and [10,10,10,10] share a set count
#    and, for the first pair, would even share nothing meaningful — so a scalar survives every count-based
#    check while losing the one fact §10 requires: what each set asks for.
echo "=================== 3. the per-set prescription is flattened to one number ==================="
mutate "$MAPPER" '
old = "    val targets = perSetTargets"
new = "    val targets = listOf(perSetTargets.average().toInt())"
assert old in t
t = t.replace(old, new)
'
check_red "3 the per-set prescription is flattened to one scalar" "$REPOSITORY_SUITE"

# 4. Two variants declared on one level are collapsed into a single rung. §15 says a same-level pair is
#    the only shape in which a *variant* change is a bounded change, so dropping one silently deletes
#    the family's ability to make that change at all.
echo "=================== 4. two same-level variants are collapsed ==================="
mutate "$REPOSITORY" '
old = "        return storedProgressionRelation(familyId, rows)"
new = ("        val collapsed = rows.distinctBy { it.level }\n"
       "        return storedProgressionRelation(familyId, collapsed)")
assert old in t
t = t.replace(old, new)
'
check_red "4 two same-level variants are collapsed into one" "$REPOSITORY_SUITE"

# 5. A hole in the levels is tolerated instead of refused. Contiguity is what makes "the next rung up"
#    either declared or genuinely at the ceiling; with a gap it is undefined in a way no policy could
#    honestly resolve.
echo "=================== 5. a level gap is accepted ==================="
mutate "$REPOSITORY" '
old = "        return storedProgressionRelation(familyId, rows)"
new = ("        val renumbered = rows.mapIndexed { index, row ->\n"
       "            com.monkfitness.app.data.model.ProgressionRelationVariantEntity(\n"
       "                familyId = row.familyId,\n"
       "                level = index,\n"
       "                exerciseId = row.exerciseId,\n"
       "                prescriptionDimension = row.prescriptionDimension,\n"
       "                perSetTargets = row.perSetTargets\n"
       "            )\n"
       "        }\n"
       "        return storedProgressionRelation(familyId, renumbered)")
assert old in t
t = t.replace(old, new)
'
check_red "5 a non-contiguous level gap is accepted and renumbered" "$REPOSITORY_SUITE"

# 6. The key is widened so the SAME exercise can be stored at two levels in one family. This is the
#    plausible way the duplicate rule gets lost: someone widens the key to let a family declare the
#    same exercise twice, and the table stops refusing what the domain still refuses. A mutation that
#    merely DE-DUPLICATED on read would be a no-op here, because every relation that can reach the read
#    has already passed the domain constructor — so the rule is genuinely lost only at the storage edge.
echo "=================== 6. a duplicate exercise in one family becomes storable ==================="
mutate "$ENTITY" '
old = "@Entity(tableName = \"progression_relation_variant\", primaryKeys = [\"familyId\", \"exerciseId\"])"
new = "@Entity(tableName = \"progression_relation_variant\", primaryKeys = [\"familyId\", \"exerciseId\", \"level\"])"
assert old in t
t = t.replace(old, new)
'
# Checked against the GATE, not the repository suite, and the reason is worth recording: a migrated
# database's primary key comes from the MIGRATION's DDL, not from the entity annotation, so widening the
# annotation does not actually let a duplicate row into this rig's engine. The rule is therefore lost at
# the point where it is declared — which is exactly what the architecture gate reads.
check_red "6 a duplicate exercise in one family becomes storable" "$GATE"

# 7. A read for one family answers with another family's ladder. Written as a fallback onto a constant
#    family id, because that is the shape such a leak takes: not a query bug but a default.
echo "=================== 7. a read leaks another family's relation ==================="
mutate "$REPOSITORY" '
old = "        val rows = relationDao.variantsOfFamily(familyId)"
new = ("        val rows = relationDao.variantsOfFamily(familyId)\n"
       "            .ifEmpty { relationDao.variantsOfFamily(NEIGHBOUR_FAMILY) }")
assert old in t
t = t.replace(old, new)
old2 = "class ProgressionRelationRepository("
new2 = "private const val NEIGHBOUR_FAMILY = \"squats\"\n\nclass ProgressionRelationRepository("
assert old2 in t
t = t.replace(old2, new2)
'
check_red "7 a read leaks another family's variants" "$REPOSITORY_SUITE"

# 8. An unknown family is answered with a fabricated single-rung ladder. This is the single most
#    dangerous defect the stage could ship: it converts "no ladder is declared" into "this family
#    declares one exercise", and production would then adapt against a rung nobody wrote.
echo "=================== 8. an unknown family gets a fabricated default relation ==================="
mutate "$REPOSITORY" '
old = "        if (rows.isEmpty()) return null"
new = ("        if (rows.isEmpty()) {\n"
       "            return ProgramProgressionRelation(\n"
       "                familyId = familyId,\n"
       "                variants = listOf(\n"
       "                    com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant(\n"
       "                        level = 0,\n"
       "                        exerciseId = familyId,\n"
       "                        prescription = com.monkfitness.app.domain.prescription.RepPrescription(listOf(10))\n"
       "                    )\n"
       "                )\n"
       "            )\n"
       "        }")
assert old in t
t = t.replace(old, new)
'
check_red "8 an unknown family is given a fabricated default relation" "$CATALOGUE_SUITE"

# 9. The family's CURRENT exercise is read as though it were a ladder. A current position is one rung;
#    treating it as a hierarchy fabricates every other rung, and it is the most tempting shortcut in the
#    whole codebase because the column exists and is already named after the family.
echo "=================== 9. FamilyProgressionState.currentExerciseId is read as ladder data ==================="
mutate "$REPOSITORY" '
old = "    private val inTransaction: suspend (suspend () -> Unit) -> Unit\n)"
new = ("    private val inTransaction: suspend (suspend () -> Unit) -> Unit,\n"
       "    private val familyState: com.monkfitness.app.domain.adaptive.FamilyProgressionState? = null\n)")
assert old in t
t = t.replace(old, new)
old2 = "        if (rows.isEmpty()) return null"
new2 = ("        val declaredCurrentExerciseId = familyState?.currentExerciseId\n"
        "        if (rows.isEmpty()) return null")
assert old2 in t
t = t.replace(old2, new2)
'
check_red "9 family state current exercise is read as ladder data" "$GATE"

# 10. The retired Stage-1 pilot's profiles become a fallback. That generation is retired, its ladders are
#     scoped to the legacy program's own axis, and reaching for them is explicitly forbidden — so the row
#     proves the ban holds in the *storage* layer and not only in the provider.
echo "=================== 10. the Stage-1 pilot source is consulted ==================="
mutate "$REPOSITORY" '
old = "    private val inTransaction: suspend (suspend () -> Unit) -> Unit\n)"
new = ("    private val inTransaction: suspend (suspend () -> Unit) -> Unit,\n"
       "    private val PilotProgressionProfiles: Map<String, String>? = null\n)")
assert old in t
t = t.replace(old, new)
old2 = "        if (rows.isEmpty()) return null"
new2 = ("        val pilotLadder = PilotProgressionProfiles?.get(familyId)\n"
        "        if (rows.isEmpty()) return null")
assert old2 in t
t = t.replace(old2, new2)
'
check_red "10 the Stage-1 pilot progression source is consulted" "$GATE"

# 11. The catalogue becomes Program-scoped. This is the scope leak the whole stage exists to prevent: with
#     a plan identity on the row, one family's ladder is restated per revision and two revisions can
#     disagree about what "level 2" means.
echo "=================== 11. the global catalogue gains Program/Revision ownership ==================="
mutate "$ENTITY" '
old = "    val perSetTargets: List<Int>\n)"
new = "    val perSetTargets: List<Int>,\n    val revisionId: String = \"\"\n)"
assert old in t
t = t.replace(old, new)
'
check_red "11 the global catalogue is scoped to a Program revision" "$GATE"

# 12. The provider stops being a projection and constructs the relation itself. Everything above this row
#     concerns what storage says; this row concerns who is allowed to answer at all.
echo "=================== 12. the provider fabricates a relation instead of reading one ==================="
mutate "$PROVIDER" '
old = "        relations.relationOf(familyId)"
new = ("relations.relationOf(familyId) ?: com.monkfitness.app.domain.adaptive.engine." +
       "ProgramProgressionRelation(familyId = familyId, variants = emptyList())")
assert old in t
t = t.replace(old, new)
'
check_red "12 the provider fabricates a relation instead of reading one" "$CATALOGUE_SUITE"

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
# second gate: this stage legitimately modifies tracked files and adds new ones, so a "tree is clean"
# assertion would fail for the right work and prove nothing about restoration. Every file any row touches
# is in FILES and therefore in the baseline, and no row creates a new file — a row that did would leave a
# stray source that the next compile reads, which is a louder failure than any git check.

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