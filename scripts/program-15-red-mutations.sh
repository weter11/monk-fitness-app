#!/bin/bash
# §30 step 15 — the RED mutation suite.
#
# One mutation per P15 rule that the architecture gate, the schema suite and the acceptance test are
# supposed to catch. A mutation that is NOT caught is a hole in the oracle, and this script fails on it.
#
#   control run            must be GREEN  (nothing mutated)
#   every mutation         must be RED    (its focused suite fails)
#   every source           must be restored byte-identically (md5sum -c)
#
# The focus is deliberately the *owning* suite of each rule: the gate for a reintroduced retired
# reference, the schema/migration suites for a schema change, the acceptance test for the runtime path.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

WORK=/tmp/p15-red
rm -rf "$WORK" && mkdir -p "$WORK"


MAIN=app/src/main/java/com/monkfitness/app
GATE="com.monkfitness.app.architecture.ProgramLegacyRemovalGateTest"
SCHEMA="com.monkfitness.app.data.local.ProgramSchemaTest"
MIGRATION="com.monkfitness.app.data.local.ProgramMigrationPreservationTest"
ACCEPT="com.monkfitness.app.architecture.ProgramTargetAcceptanceTest"
NAV="com.monkfitness.app.ui.programs.ProgramsNavigationTest"

FILES=(
    "$MAIN/di/AppContainer.kt"
    "$MAIN/data/local/AppDatabase.kt"
    "$MAIN/data/local/MaintenanceDao.kt"
    "$MAIN/ui/programs/ProgramSessionController.kt"
    "$MAIN/data/local/ProgramSetLogDao.kt"
    "$MAIN/viewmodel/MainViewModel.kt"
    "$MAIN/data/repository/MaintenanceRepository.kt"
    "$MAIN/ui/screens/ProgramSessionScreen.kt"
    "$MAIN/MainActivity.kt"
)

# ---- snapshot every source the mutations touch ------------------------------------------------
: > "$WORK/before.md5"
for f in "${FILES[@]}"; do
    cp "$f" "$WORK/$(basename "$f").orig"
    md5sum "$f" >> "$WORK/before.md5"
done

run_suite() {
    # Cleared before EVERY invocation, not once at the start: this project's KSP incremental cache fails
    # intermittently under many rapid recompiles of the same files (`CompressedAppendableFile` cannot read
    # its own persistent map back), and the failure looks like a source error. A dozen recompiles in one
    # run is exactly the pattern that triggers it.
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest --tests "$1" > "$WORK/last-run.log" 2>&1
    return $?
}

restore_all() {
    for f in "${FILES[@]}"; do
        cp "$WORK/$(basename "$f").orig" "$f"
    done
}

# mutate <file> <python-body>  — the body gets the file text as `t` and must assign it back
mutate() {
    python3 - "$1" "$2" <<'MUTATE_BODY'
import io, sys
path, body = sys.argv[1], sys.argv[2]
before = io.open(path, encoding="utf-8").read()
g = {"t": before, "io": io}
exec(body, g)
if g["t"] == before:
    # A mutation that does not change the source is not a mutation: the suite would report it as "missed"
    # and send a reader hunting for a hole in the oracle that is not there.
    sys.stderr.write("MUTATION DID NOT APPLY to %s\n" % path)
    sys.exit(9)
io.open(path, "w", encoding="utf-8").write(g["t"])
MUTATE_BODY
    if [ $? -ne 0 ]; then
        echo "  ABORT: a mutation did not apply (see above)"
        exit 1
    fi
}

PASS=0
FAIL=0
declare -a RESULTS

echo "=================== control: nothing mutated ==================="
if run_suite "$GATE" && run_suite "$MIGRATION" && run_suite "$ACCEPT" && run_suite "$SCHEMA"; then
    echo "control GREEN"
    RESULTS+=("control                          GREEN   ok")
else
    echo "control is NOT green — the baseline is broken, so no verdict below means anything"
    tail -20 "$WORK/last-run.log"
    exit 1
fi

check_red() {
    local label="$1" suite="$2"
    if run_suite "$suite"; then
        echo "  MISSED: $label  (suite stayed GREEN — the oracle does not catch this)"
        RESULTS+=("$label MISSED")
        FAIL=$((FAIL + 1))
    else
        echo "  caught: $label"
        RESULTS+=("$label caught")
        PASS=$((PASS + 1))
    fi
    restore_all
}

# ---- 1. reintroduce a legacy DAO dependency in production --------------------------------------
echo "=================== 1. a legacy DAO dependency ==================="
mutate "$MAIN/di/AppContainer.kt" '
t = t.replace(
    "    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(",
    "    private val legacyFamilyStates = database.familyProgressionStateDao()\n\n" +
    "    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(",
)
'
check_red "1 legacy DAO dependency in the graph" "$GATE"

# ---- 2. put a retired Entity back into the database declaration --------------------------------
echo "=================== 2. a retired entity in AppDatabase ==================="
mutate "$MAIN/data/local/AppDatabase.kt" '
t = t.replace(
    "        PostureSessionProgress::class,",
    "        UserProgress::class,\n        PostureSessionProgress::class,",
)
'
check_red "2 retired entity back in the declaration" "$SCHEMA"

# ---- 3. reintroduce a Stage-1 adaptive repository construction ---------------------------------
echo "=================== 3. a Stage-1 adaptive repository construction ==================="
mutate "$MAIN/di/AppContainer.kt" '
t = t.replace(
    "    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(",
    "    val adaptiveRepository = AdaptiveRepository(daos.legacyFamilyState, daos.legacyDecisionHistory)\n\n" +
    "    val maintenanceRepository: MaintenanceRepository = MaintenanceRepository(",
)
'
check_red "3 Stage-1 adaptive repository constructed" "$GATE"

# ---- 4. make MainViewModel carry legacy Program state -----------------------------------------
echo "=================== 4. legacy Program state in MainViewModel ==================="
mutate "$MAIN/viewmodel/MainViewModel.kt" '
t = t.replace(
    "    /** The anchor the retained daily tracks keep their 56-day rhythm from, parsed once. */",
    "    /** The retired program cycle, reintroduced under the name the retired runtime used. */\n" +
    "    private val cycleNumber = MutableStateFlow(1)\n\n" +
    "    /** The anchor the retained daily tracks keep their 56-day rhythm from, parsed once. */",
)
'
check_red "4 MainViewModel carries a cycleNumber" "$GATE"

# ---- 5. make the workout route identity a day instead of an opportunity ------------------------
echo "=================== 5. the workout route by day ==================="
mutate "$MAIN/MainActivity.kt" '
# The route argument name is declared where the destination is registered, so that is where the identity
# regresses from an opportunity to a day.
t = t.replace(
    "navArgument(" + chr(34) + "slotId" + chr(34) + ") { type = NavType.StringType }",
    "navArgument(" + chr(34) + "day" + chr(34) + ") { type = NavType.IntType }",
)
'
check_red "5 the workout route carries a day" "$NAV"

# ---- 6. bypass SessionRuntime: the screen counts sets instead of the runtime recording them -----
echo "=================== 6. bypassing SessionRuntime ==================="
mutate "$MAIN/ui/programs/ProgramSessionController.kt" '
t = t.replace(
    """        when (
            val confirmed = runtime.confirmSet(""",
    """        if (true) return

        when (
            val confirmed = runtime.confirmSet(""",
)
'
check_red "6 a set is confirmed without the runtime recording it" "$ACCEPT"

# ---- 7. make the migration preserve a retired table -------------------------------------------
echo "=================== 7. the migration keeps a retired table ==================="
mutate "$MAIN/data/local/AppDatabase.kt" '
t = t.replace("DROP TABLE IF EXISTS `set_log`", "DROP TABLE IF EXISTS `set_log_kept`")
'
check_red "7 a retired table survives the migration" "$MIGRATION"

# ---- 8. make the migration drop a target table ------------------------------------------------
echo "=================== 8. the migration drops a target table ==================="
mutate "$MAIN/data/local/AppDatabase.kt" '
t = t.replace(
    "DROP TABLE IF EXISTS `user_progress`",
    "DROP TABLE IF EXISTS `user_progress`" + chr(34) + ");" +
    chr(10) + "                database.execSQL(" + chr(34) + "DROP TABLE IF EXISTS `program_set_log`",
)
'
check_red "8 the migration drops a target table" "$MIGRATION"

# ---- 9. lose the track rows in the rename -----------------------------------------------------
echo "=================== 9. the track rows are not copied ==================="
mutate "$MAIN/data/local/AppDatabase.kt" '
# The copy is emptied rather than removed: the statement stays, and every row it was supposed to carry
# is gone — which is exactly the drop-and-recreate defect the brief singles out.
t = t.replace(
    "FROM `posture_session_progress`",
    "FROM `posture_session_progress` WHERE 0",
)
'
check_red "9 the track rows are dropped instead of copied" "$MIGRATION"

# ---- 10. the target set log reaches the retired table -----------------------------------------
echo "=================== 10. a target DAO names a retired table ==================="
mutate "$MAIN/data/local/ProgramSetLogDao.kt" '
t = t.replace(
    "SELECT * FROM `program_set_log`",
    "SELECT * FROM `program_set_log` UNION ALL SELECT 1 FROM `set_log`",
)
'
check_red "10 a target DAO reads the retired set log" "$GATE"

echo
echo "=================== source restoration ==================="
if md5sum -c "$WORK/before.md5" > "$WORK/md5check.log" 2>&1; then
    echo "every mutated source restored byte-identically"
else
    echo "RESTORATION FAILED:"
    cat "$WORK/md5check.log"
    FAIL=$((FAIL + 1))
fi

echo
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

echo
echo "=================== verdict ==================="
printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL"
if [ "$FAIL" -ne 0 ]; then
    echo "RED MUTATION SUITE FAILED"
    exit 1
fi
echo "RED MUTATION SUITE PASSED: every mutation was caught and every source restored"
