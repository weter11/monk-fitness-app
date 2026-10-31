package com.monkfitness.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The architecture gate**: the retired 56-day program and the Stage-1 adaptive generation must not come
 * back.
 *
 * Deleting the files is the easy half; the half that rots is *reintroduction* — a new screen reaching for
 * `user_progress`, a mapper that quietly reads `cycleNumber`, an adapter that re-creates a Stage-1 engine
 * because it looked convenient. This test is the thing that says no, about production source only:
 *
 *  * `app/src/test` is not scanned — this gate's own deny-list lives there, by necessity;
 *  * `docs/` is not scanned — the retirement is *documented* there, and a document may name what it removed;
 *  * `strings.xml` is not scanned — a resource key is not a reference to a concept;
 *  * comments are stripped before matching, so a KDoc that names a retired type to explain its absence —
 *    which is what the cutover's own sources do — does not trip its own gate.
 *
 * Tokens match on word boundaries because `ProgramAdaptiveRepository` *contains* `AdaptiveRepository` and
 * `ProgramAdaptiveDecisionRecordEntity` contains `AdaptiveDecisionRecord`: a substring match would fire on
 * the architecture that replaced them. `_` counts as a word character, which keeps `` `set_log` `` from
 * matching `` `program_set_log` ``.
 */
class ProgramLegacyRemovalGateTest {

    private val appRoot = File("src/main/java/com/monkfitness/app").let {
        if (it.isDirectory) it else File("app/src/main/java/com/monkfitness/app")
    }

    /** Every production Kotlin source, with comments removed. */
    private fun productionSources(): List<Pair<String, String>> =
        appRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                file.relativeTo(appRoot).path.replace('\\', '/') to withoutComments(file.readText())
            }
            .toList()

    private fun withoutComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""^\s*\*.*$""", RegexOption.MULTILINE), "")

    /** [`token`] as a whole identifier: `AdaptiveRepository` matches neither `ProgramAdaptiveRepository` nor `MyAdaptiveRepository`. */
    private fun wholeToken(token: String): Regex = Regex("""(?<![\w.])${Regex.escape(token)}(?![\w])""")

    private fun offenders(tokens: List<String>, exempt: Set<String> = emptySet()): List<String> =
        productionSources()
            .filterNot { (path, _) -> path in exempt }
            .flatMap { (path, text) ->
                tokens.filter { token -> wholeToken(token).containsMatchIn(text) }
                    .map { token -> "$path: $token" }
            }.sorted()

    /**
     * `AppDatabase.kt` is the **one** production file allowed to name a retired table or the retired
     * `cycleNumber` column — and only inside its **migration chain**.
     *
     * That is inherent to what a migration chain is: `MIGRATION_1_2` created `user_progress`,
     * `MIGRATION_5_6` reshaped `program_day_state`, and `MIGRATION_11_12` drops them all. A gate that
     * forbade the ledger would forbid both the history and the retirement; what must not name them is the
     * *current* schema: the entity list, the version, the DAO accessors, and every other file.
     *
     * So the exemption is a *location*, not a list: this returns the file's source with the whole chain
     * removed, and the scans re-run against what is left. A retired name in an entity list or a DAO
     * accessor is caught, and the exemption cannot grow into a hiding place.
     */
    private fun databaseSourceOutsideTheMigrationChain(): String {
        val text = withoutComments(File(appRoot, "data/local/AppDatabase.kt").readText())
        val start = text.indexOf("val MIGRATION_")
        assertTrue("expected the migration chain in AppDatabase.kt", start >= 0)
        val end = text.indexOf("fun getDatabase(", start)
        assertTrue("expected the builder after the migration chain", end > start)
        return (text.substring(0, start) + text.substring(end))
            .replace(Regex("""^\s*\*.*$""", RegexOption.MULTILINE), "")
    }

    private fun unexemptOffenders(tokens: List<Regex>): List<String> {
        val text = databaseSourceOutsideTheMigrationChain()
        return tokens.filter { token -> token.containsMatchIn(text) }
            .map { token -> "data/local/AppDatabase.kt (outside the migration chain): $token" }
    }

    @Test
    fun noProductionSourceNamesARetiredType() {
        val retiredTypes = listOf(
            "UserProgress",
            "ProgramDayState",
            "SetLog",
            "SetLogRow",
            "WorkoutRepository",
            "ProgramMaintenance",
            "WorkoutScreen",
            "ActiveWorkoutConfiguration",
            "WorkoutSessionContext",
            "SetLogObservation",
            "HomeUiState",
            "WorkoutSessionUiState",
            "WorkoutStep",
            "SessionMode",
            "ProgramCalendar",
            "resolveCycleAndDay",
            "calculateProgramDay",
            "synchronizeProgramStates",
            "shouldOfferCycleCompletion",
            "TOTAL_PROGRAM_DAYS",
            "progressDao",
            "AdaptivePolicy",
            "AdaptiveProgramEngine",
            "AdaptiveSignalCalculator",
            "AdaptiveSignals",
            "ProgressionResolver",
            "ProgressionProfile",
            "PilotProgressionProfiles",
            "AdaptiveProgressionPlan",
            "AdaptiveProgramInput",
            "AdaptiveEvidence",
            "AdaptiveReasonCode",
            "ExerciseResult",
            "SessionObservation",
            "SessionObservationMapper",
            "SessionSetLog",
            "SessionOutcome",
            "PlannedExercise",
            "AdaptiveRepository",
            "AdaptiveSessionDecisionRecorder",
            "SessionAdaptivePlanReader",
            "AdaptiveSessionPlan",
            "SessionAdaptiveInputs",
            "AdaptiveWorkoutIntegration",
            "AdaptiveWorkoutGenerationRequest",
            "familyProgressionStateDao",
            "adaptiveDecisionHistoryDao"
        )

        assertTrue("expected the production tree to scan", productionSources().size > 100)
        assertEquals(
            "§30 step 15 retired these; a production source naming one is the architecture coming back. " +
                "A comment naming one is fine (they are stripped); a *type* or *call* is not",
            emptyList<String>(),
            offenders(retiredTypes)
        )
    }

    @Test
    fun noProductionSourceCarriesAProgramCycleOrDayIdentity() {
        val retiredConcepts = listOf(
            // A cycle number and a program-day number were the shipped program's identity; the target's
            // is a revision, an opportunity and a session, and the retained daily tracks' own calendar is
            // `trackCycle`/`trackDay` — deliberately not these.
            "cycleNumber",
            "programStartDateFlow",
            "ensureProgramStartDate",
            "startRevisedProgram",
            "programCycleNumberFlow",
            "programRevisionFlow",
            "revisionNumber_legacy"
        )

        assertEquals(
            "no production source may carry the retired program's cycle/day vocabulary: the target " +
                "Program's identity is `programId` → `revisionId` → `programDayId` → `slotId` → " +
                "`sessionId`, and the retained track's is `trackCycle`/`trackDay`. The one exemption is " +
                "the column read that renames the track's identity, and it is checked below",
            emptyList<String>(),
            offenders(retiredConcepts, exempt = setOf("data/local/AppDatabase.kt"))
        )
        assertEquals(
            "and the retired column name appears nowhere in the current schema — only in the migration " +
                "steps that created and renamed it",
            emptyList<String>(),
            unexemptOffenders(retiredConcepts.map { token -> wholeToken(token) })
        )
    }

    @Test
    fun noProductionSourceNamesARetiredTable() {
        val retiredTables = listOf(
            "`user_progress`",
            "`program_day_state`",
            "`set_log`",
            "`family_progression_state`",
            "`adaptive_decision_record`"
        )

        assertEquals(
            "no production SQL may name a table `MIGRATION_11_12` drops. `` `set_log` `` is spelled with " +
                "its backticks on purpose: `` `program_set_log` `` is the target table and must not be " +
                "caught by a scan for the retired one. `AppDatabase.kt` is exempt *inside* the " +
                "retirement migration only — asserted separately below",
            emptyList<String>(),
            offenders(retiredTables, exempt = setOf("data/local/AppDatabase.kt"))
        )
        assertEquals(
            "and outside the migration chain the schema names none of them either: the current " +
                "database's entity list, version and DAO accessors reach no retired table. Only the " +
                "ledger of past migrations may — which is what `MIGRATION_11_12` is doing there",
            emptyList<String>(),
            unexemptOffenders(retiredTables.map { token -> wholeToken(token) })
        )
    }

    @Test
    fun theDomainAndTheProgramUiDependOnNoRoomAndNoAndroid() {
        val uiAndDomain = productionSources()
            .filter { (path, _) -> path.startsWith("domain/") || path.startsWith("ui/programs/") }

        assertTrue("expected the domain and the Program UI to scan", uiAndDomain.size > 30)
        val offenders = uiAndDomain.flatMap { (path, text) ->
            listOf("androidx.room", "androidx.sqlite", "import android.", "AppDatabase")
                .filter { token -> text.contains(token) }
                .map { token -> "$path: $token" }
        }
        assertEquals(
            "§16's chain is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI`: the domain and the Program " +
                "UI state holder are plain Kotlin, and a Room or Android import in either is a layer " +
                "reached around",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun theRetiredSourcesAndTablesAreActuallyGone() {
        for (retired in listOf(
            "domain/usecase/ProgramCalendar.kt",
            "domain/adaptive/AdaptivePolicy.kt",
            "domain/adaptive/AdaptiveProgramEngine.kt",
            "domain/adaptive/ProgressionResolver.kt",
            "domain/adaptive/PilotProgressionProfiles.kt",
            "domain/usecase/AdaptiveWorkoutIntegration.kt",
            "data/repository/AdaptiveRepository.kt",
            "data/repository/AdaptiveSessionDecisionRecorder.kt",
            "data/repository/SessionAdaptivePlanReader.kt",
            "data/repository/SessionHistoryAdapter.kt",
            "data/repository/ProgramMaintenance.kt",
            "data/repository/WorkoutRepository.kt",
            "data/local/ProgressDao.kt",
            "data/local/FamilyProgressionStateDao.kt",
            "data/local/AdaptiveDecisionHistoryDao.kt",
            "data/model/UserProgress.kt",
            "data/model/ProgramDayState.kt",
            "data/model/SetLog.kt",
            "ui/screens/WorkoutScreen.kt",
            "viewmodel/ActiveWorkoutConfiguration.kt"
        )) {
            assertFalse(
                "$retired was retired by §30 step 15 and must not come back",
                File(appRoot, retired).exists()
            )
        }
    }

    @Test
    fun theTargetWorkoutRuntimeIsWiredAndReachable() {
        val container = File(appRoot, "di/AppContainer.kt").readText()
        val sessionSource = File(appRoot, "ui/programs/ProgramSessionController.kt")

        assertTrue(
            "the composition root builds the runtime",
            withoutComments(container).contains("SessionRuntime(")
        )
        assertTrue(
            "and something above it actually uses one: a runtime wired and unused is the state §30 " +
                "step 15 found the tree in, and the cutover is what ended it",
            sessionSource.isFile && withoutComments(sessionSource.readText()).contains("runtime.")
        )
    }
}
