package com.monkfitness.app.ui.customprogram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fences around the editor, checked against the sources themselves rather than trusted.
 *
 * Task 11 is a *consumer* task: it presents the persisted configuration, the exercise library, the
 * equipment state and the validator's findings, and it may not quietly grow a second copy of any of
 * them. Each rule below is a way that could happen without anyone noticing:
 *
 *  * a version counter incremented in the UI or a `ProgramConfiguration` assembled by hand, instead of
 *    the repository's own no-op/`DEFAULT`/`CUSTOM` semantics;
 *  * a second exercise catalogue (`exerciseToFamiliesMap`, a hardcoded exercise list, a
 *    `WorkoutGenerator` call) next to the library the app already owns;
 *  * a second validator, or a hard error quietly reclassified as a warning;
 *  * a store of its own, or a Room entity for the editor;
 *  * a second navigation mechanism, or business rules leaking into `MainActivity`;
 *  * the state holder reaching into Compose, which would also take the editor's only JVM-testable
 *    surface away;
 *  * any adaptive progression/history access at all — that boundary belongs to the lifecycle tasks
 *    that come after this one.
 */
class CustomProgramArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val editorSources: List<File> = listOf(
        File(mainSourceRoot, "ui/customprogram"),
        File(mainSourceRoot, "ui/screens/CustomProgramScreen.kt")
    ).flatMap { file ->
        if (file.isDirectory) {
            file.listFiles { candidate -> candidate.isFile && candidate.extension == "kt" }
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            listOf(file)
        }
    }.filter { it.isFile }

    private fun source(relativePath: String): File {
        val candidate = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${candidate.absolutePath}", candidate.isFile)
        return candidate
    }

    private fun offendingTokens(files: List<File>, tokens: List<String>): List<String> = files
        .flatMap { file ->
            val text = file.readText()
            tokens.filter { token -> token in text }.map { token -> "${file.name}: $token" }
        }

    private fun assertNoToken(files: List<File>, tokens: List<String>, because: String) {
        val offenders = offendingTokens(files, tokens)
        assertEquals("$because — found: $offenders", emptyList<String>(), offenders)
    }

    // ---- the feature's sources exist -----------------------------------------------------------

    @Test
    fun theFeatureIsSplitIntoAStateHolderAPresentationAndAScreen() {
        assertTrue(
            "the editor state holder, its presentation mapping and its screen must all exist: " +
                editorSources.map { it.name },
            editorSources.size >= 4
        )
        assertTrue(
            "the state holder must live outside the screens package",
            editorSources.any { it.name == "CustomProgramEditor.kt" }
        )
        assertTrue(
            "the screen must exist",
            source("ui/screens/CustomProgramScreen.kt").isFile
        )
    }

    // ---- configuration identity belongs to the repository --------------------------------------

    @Test
    fun theEditorOwnsNoConfigurationVersionArithmetic() = assertNoToken(
        editorSources,
        listOf("configurationVersion", "INITIAL_VERSION", ".copy(version", "ProgramConfiguration(", "ProgramConfiguration.custom", "ProgramConfiguration.default"),
        "the version and the source of a configuration are the repository's business"
    )

    @Test
    fun theEditorPersistsOnlyThroughTheConfigurationRepository() = assertNoToken(
        editorSources,
        listOf("DataStore", "preferencesDataStore", "@Entity", "Dao", "Room", "WorkoutRepository", "AdaptiveRepository"),
        "the configuration repository is the only persistence the editor may use"
    )

    @Test
    fun theEditorAppliesAndResetsThroughTheRepositoryApi() {
        val text = source("ui/customprogram/CustomProgramEditor.kt").readText()

        assertTrue("Apply must go through the repository", "repository.apply(" in text)
        assertTrue("Reset must go through the repository", "repository.resetToDefault()" in text)
        assertTrue("Opening must read the persisted configuration", "repository.load()" in text)
        assertNoToken(
            listOf(source("ui/customprogram/CustomProgramEditor.kt")),
            listOf("writeIfNotStale", "resetToDefault(defaultEnabledExerciseIds)"),
            "the editor may not reach around the repository's own API"
        )
    }

    // ---- one exercise catalogue -----------------------------------------------------------------

    @Test
    fun theEditorAddsNoSecondExerciseCatalogue() = assertNoToken(
        editorSources,
        listOf("exerciseToFamiliesMap", "exerciseCategoryGroups", "WorkoutGenerator", "ExerciseCategoryFilter"),
        "the exercise library and its families come from the app's own catalogue, supplied to the editor"
    )

    @Test
    fun theEditorLeavesWorkoutGenerationAndDifficultyAlone() = assertNoToken(
        editorSources,
        listOf("generateWorkout", "getPostureMobilityWorkout", "applyDifficultyAdjustment", "getWarmupExercises"),
        "generation and difficulty are not part of the configuration editor"
    )

    // ---- the validator stays the validator -----------------------------------------------------

    @Test
    fun theEditorDoesNotReimplementValidation() = assertNoToken(
        editorSources,
        listOf("ProgramConfigurationError(", "ProgramConfigurationWarning(", "fun validate", "class ProgramConfigurationValidator"),
        "validation is a domain concern; the editor presents findings, it does not produce them"
    )

    @Test
    fun theEditorConsumesTheDomainValidatorsFindings() {
        val text = source("ui/customprogram/CustomProgramEditor.kt").readText()

        assertTrue(
            "the editor must validate through the domain validator",
            "ProgramConfigurationValidator.validate(" in text
        )
    }

    @Test
    fun theScreenEncodesNoDomainRules() = assertNoToken(
        listOf(source("ui/screens/CustomProgramScreen.kt")),
        listOf("TrainingDomain", "BodyRegion", "ProgramConfigurationErrorCode", "ProgramConfigurationWarningCode", "ProgramConfigurationValidator", "ProgramConfigurationRepository"),
        "the screen renders what the state holder prepared; domain vocabulary stays out of Compose"
    )

    // ---- no adaptive progression, no lifecycle --------------------------------------------------

    @Test
    fun theEditorTouchesNoAdaptiveProgressionOrHistory() = assertNoToken(
        editorSources,
        listOf("AdaptiveRepository", "FamilyProgressionState", "AdaptiveDecisionRecord", "ProgressionResolver", "AdaptiveProgramEngine", "AdaptivePolicy", "AdaptiveSignalCalculator", "AdaptiveSignals"),
        "adaptive state, decision history and session snapshots belong to the lifecycle tasks, not to the editor"
    )

    @Test
    fun theEditorsViewModelBridgeTouchesNoAdaptiveProgressionOrHistory() = assertNoToken(
        listOf(source("viewmodel/MainViewModel.kt")),
        listOf("AdaptiveRepository", "FamilyProgressionState", "AdaptiveDecisionRecord", "ProgressionResolver", "AdaptiveProgramEngine"),
        "wiring the editor must not reach into the adaptive engine"
    )

    @Test
    fun theEditorsViewModelBridgeIsWiredToTheEditor() {
        val text = source("viewmodel/MainViewModel.kt").readText()

        assertTrue("the bridge must construct the editor", "CustomProgramEditor(" in text)
        assertTrue("the bridge must expose the editor state", "customProgramEditor.state" in text)
        assertTrue("the bridge must open the editor", "openCustomProgramEditor" in text)
        assertTrue("the bridge must apply the draft", "customProgramEditor.apply()" in text)
        assertTrue("the bridge must reset through the editor", "customProgramEditor.confirmResetToDefault()" in text)
    }

    // ---- the state holder stays a state holder --------------------------------------------------

    @Test
    fun theStateHolderIsPlainKotlinSoItCanBeTestedOnTheJvm() {
        val stateSources = File(mainSourceRoot, "ui/customprogram")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertTrue("expected the editor's own sources", stateSources.size >= 3)
        assertNoToken(
            stateSources,
            listOf("import androidx.compose", "import android.", "import android.content"),
            "the editor state holder and its presentation must not depend on Compose or Android"
        )
        assertTrue(
            "the screen itself is Compose, and lives outside the state holder's package",
            "import androidx.compose" in source("ui/screens/CustomProgramScreen.kt").readText()
        )
    }

    // ---- existing navigation -------------------------------------------------------------------

    @Test
    fun theEditorUsesTheExistingNavigationInsteadOfANewOne() {
        val activity = source("MainActivity.kt").readText()

        assertEquals("exactly one NavHost", 1, Regex("""NavHost\(""").findAll(activity).count())
        assertEquals("exactly one nav controller", 1, Regex("""rememberNavController\(\)""").findAll(activity).count())
        assertTrue("the editor is a destination of that NavHost", "CustomProgramScreen(" in activity)
        assertTrue("the settings entry navigates to it", "ROUTE_CUSTOM_PROGRAM" in activity)
        assertTrue("the settings screen is given the entry callback", "onOpenCustomProgram" in activity)
    }

    @Test
    fun theRouteIsDeclaredWithTheAppsOtherRoutes() {
        val viewModel = source("viewmodel/MainViewModel.kt").readText()

        assertTrue(
            "the route constant lives with the app's other routes",
            Regex("""const val ROUTE_CUSTOM_PROGRAM = "custom-program"""").containsMatchIn(viewModel)
        )
    }

    @Test
    fun noSecondAdaptiveEditorOrConfigurationScreenExists() {
        val screens = File(mainSourceRoot, "ui/screens")
            .listFiles { file -> file.isFile && file.extension == "kt" }
            ?.map { it.name }
            ?: emptyList()

        assertEquals(
            "one editor screen, not two",
            listOf("CustomProgramScreen.kt"),
            screens.filter { it.contains("CustomProgram") || it.contains("Configuration") }
        )
    }
}
