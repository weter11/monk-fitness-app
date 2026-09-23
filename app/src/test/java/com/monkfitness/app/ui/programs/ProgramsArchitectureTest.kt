package com.monkfitness.app.ui.programs

import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.time.ZoneId

/**
 * §16's chain, §13's ViewModel boundary and §6's prohibitions, pinned mechanically.
 *
 * The Program UI is the first layer of this system that a user can *press*, so the ways it can go wrong
 * are the ways §25 and §33 name: a screen reaching a DAO, a state object carrying a Room entity, a view
 * model building its own repositories, a second navigation graph, a share built by hand instead of handed
 * to the platform boundary. Each of those is asserted against the sources and against the compiled shape.
 */
class ProgramsArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    /** The state holder and its models: plain Kotlin, no Compose, no Android, no Room, no data layer. */
    private val stateHolderSources: List<File> = File(mainSourceRoot, "ui/programs")
        .listFiles { file -> file.isFile && file.extension == "kt" }
        ?.sortedBy { file -> file.name }
        ?: emptyList()

    /** The Compose screens this stage adds. */
    private val screenSources: List<File> = listOf(
        "ui/screens/ProgramsScreen.kt",
        "ui/screens/MyProgramsScreen.kt",
        "ui/screens/ProgramDetailScreen.kt",
        "ui/screens/ProgramEditorScreen.kt",
        "ui/screens/ProgramImportScreen.kt"
    ).map { path -> File(mainSourceRoot, path) }

    private val featureSources: List<File> = stateHolderSources + screenSources

    private fun relative(file: File): String = file.relativeTo(mainSourceRoot).path.replace('\\', '/')

    private fun source(relativePath: String): String {
        val file = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeOf(relativePath: String): String = code(source(relativePath))

    private fun assertNoToken(files: List<File>, tokens: List<String>, because: String) {
        val offenders = files.flatMap { file ->
            val text = code(file.readText())
            tokens.filter { token -> token in text }.map { token -> "${file.name}: $token" }
        }
        assertEquals("$because — found: $offenders", emptyList<String>(), offenders)
    }

    // ---- the feature's shape ---------------------------------------------------------------------

    @Test
    fun theProgramUiIsAPlainStateHolderPlusComposeScreens() {
        assertTrue("expected the state holder's sources", stateHolderSources.size >= 3)
        assertTrue(
            "the state holder must be plain Kotlin",
            stateHolderSources.any { file -> file.name == "ProgramsController.kt" }
        )
        screenSources.forEach { file ->
            assertTrue("expected the screen ${file.name}", file.isFile)
        }
    }

    // ---- §16: no Composable reaches the data layer ------------------------------------------------

    @Test
    fun theStateHolderReachesNoComposeNoAndroidAndNoDataLayer() = assertNoToken(
        stateHolderSources,
        listOf(
            "import androidx.compose", "import android.", "import android.content", "import androidx.room",
            "Dao", "AppDatabase", "AppContainer", "Entity", "ProgramRepository", "ProgramPlanRepository",
            "ProgramScheduleRepository", "WorkoutSessionRepository", "ProgramProgressRepository"
        ),
        "the state holder calls application services and renders plain values (§16, §25)"
    )

    @Test
    fun noProgramScreenNamesADaoAEntityOrARepository() = assertNoToken(
        screenSources,
        listOf(
            "Dao", "AppDatabase", "AppContainer", "Room", "Entity(", "@Entity",
            "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
            "WorkoutSessionRepository", "ProgramProgressRepository", "AppStateRepository"
        ),
        "§33: a screen writes no DAO and holds no Room entity"
    )

    @Test
    fun noProgramScreenConstructsAnApplicationService() = assertNoToken(
        screenSources,
        listOf(
            "ProgramLifecycleService(", "ProgramEditorService(", "ProgramSaveService(",
            "ProgramImportService(", "ProgramExportService(", "ProgramProgressService(",
            "ProgramScheduler(", "ProgramsController("
        ),
        "a screen receives its state holder; it does not build one, and it builds no service"
    )

    // ---- §13: the ViewModel hands over, it does not build ------------------------------------------

    @Test
    fun theViewModelConstructsNoProgramServiceRepositoryOrDatabase() = assertNoToken(
        listOf(File(mainSourceRoot, "viewmodel/MainViewModel.kt")),
        listOf(
            "ProgramLifecycleService(", "ProgramEditorService(", "ProgramImportService(",
            "ProgramExportService(", "ProgramProgressService(", "ProgramScheduler(",
            "ProgramRepository(", "ProgramPlanRepository(", "ProgramScheduleRepository(",
            "WorkoutSessionRepository(", "AppStateRepository(", "ProgramExerciseLibrary(",
            "Room.databaseBuilder", "AppDatabase.getDatabase"
        ),
        "§13 and §26: the composition root constructs the graph, and a view model receives it"
    )

    @Test
    fun theViewModelTakesTheServicesFromTheCompositionRootAndHandsThemToTheStateHolder() {
        val viewModel = codeOf("viewmodel/MainViewModel.kt")

        assertTrue(
            "the services come from the container the application owns",
            viewModel.contains("(application as MonkFitnessApplication).container")
        )
        listOf(
            "programGraph.programLifecycleService",
            "programGraph.programEditorService",
            "programGraph.programSaveService",
            "programGraph.programImportService",
            "programGraph.programExportService",
            "programGraph.programProgressService",
            "programGraph.programScheduler",
            "programGraph.clock",
            "programGraph.zone"
        ).forEach { dependency ->
            assertTrue("the container's $dependency is handed over", viewModel.contains(dependency))
        }
        assertEquals(
            "and the state holder is constructed exactly once, as a graph node",
            1,
            Regex("""val programs = ProgramsController\(""").findAll(viewModel).count()
        )
    }

    @Test
    fun theStateHolderIsConstructedInExactlyOnePlace() {
        val constructionSites = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> code(file.readText()).contains("ProgramsController(") }
            .map { file -> relative(file) }
            .sorted()
            .toList()

        assertEquals(
            "one construction, in the view model that renders the screens it feeds",
            listOf("ui/programs/ProgramsController.kt", "viewmodel/MainViewModel.kt"),
            constructionSites
        )
    }

    // ---- the collaborators, as a census -------------------------------------------------------------

    @Test
    fun theStateHoldersCollaboratorsAreExactlyTheOwnersOfWhatItDoes() {
        val constructor = ProgramsController::class.java.declaredConstructors.single()
        val collaborators = constructor.parameterTypes.map { type -> type.name }

        assertEquals(
            "§24/§26: the seven application services, the two UI ports, and the two ports \"today\" is read " +
                "from — and nothing else. A repository, a DAO, a clock of its own or a platform type in " +
                "this list would be a layer reached around. Revised (not relaxed) by the creation " +
                "remediation: `ProgramSaveService` joins as §27's Save — the controller hands it the " +
                "draft and the chosen date precisely so the controller itself never builds a slot, " +
                "never chooses a date and never runs a scheduling pass",
            listOf(
                ProgramLifecycleService::class.java.name,
                ProgramEditorService::class.java.name,
                ProgramSaveService::class.java.name,
                ProgramImportService::class.java.name,
                ProgramExportService::class.java.name,
                ProgramProgressService::class.java.name,
                ProgramScheduler::class.java.name,
                ExerciseCatalogue::class.java.name,
                ProgramShareTarget::class.java.name,
                Clock::class.java.name,
                ZoneId::class.java.name
            ),
            collaborators
        )
        assertEquals(
            "one constructor, so there is no second way to build the UI's state holder",
            1,
            ProgramsController::class.java.declaredConstructors.size
        )
        assertTrue(
            "and it is a class the tests can build without a device",
            !Modifier.isAbstract(ProgramsController::class.java.modifiers)
        )
    }

    // ---- the platform boundary ---------------------------------------------------------------------

    @Test
    fun thePlatformObjectsAppearOnlyAtTheTwoCallSitesAndNeverInTheStateHolder() {
        assertNoToken(
            stateHolderSources,
            listOf("Intent", "Uri", "contentResolver", "FileProvider", "ContentResolver", "Context"),
            "§11 and §12 keep the Android boundary out of the layer that decides what is shared"
        )

        val offenders = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> relative(file) != "platform/ProgramShareSheet.kt" }
            .filter { file -> relative(file) != "platform/ProgramDocumentImport.kt" }
            .filter { file ->
                val text = code(file.readText())
                text.contains("ProgramShareSheet.") || text.contains("ProgramDocumentImport.")
            }
            .map { file -> relative(file) }
            .sorted()
            .toList()

        assertEquals(
            "and exactly two callers use them: the state holder's view-model wiring for Share, and the " +
                "Import screen for the document picker — a third would be a second boundary",
            listOf("ui/screens/ProgramImportScreen.kt", "MainViewModel.kt"),
            offenders.map { path -> path.removePrefix("viewmodel/") }
        )
    }

    // ---- Compose stays where Compose belongs -------------------------------------------------------

    @Test
    fun theDetailScreenLeavesTheScreenOnlyWhenTheDeleteCompleted() {
        val screen = codeOf("ui/screens/ProgramDetailScreen.kt").replace(Regex("\\s+"), " ")

        assertTrue(
            "this project has no Compose test harness (§17), so the screen's half of §15's contract is " +
                "asserted against the source: the delete's outcome decides whether anything navigates",
            screen.contains(
                "val outcome = controller.delete(current.row.programId) " +
                    "if (outcome is ProgramNotice.Done) { controller.closeDetail() onBack() }"
            )
        )
        assertFalse(
            "an unguarded close would leave the Detail on a refused delete — which is exactly the defect " +
                "this contract exists to prevent",
            screen.contains("controller.delete(current.row.programId) controller.closeDetail()")
        )
    }

    @Test
    fun onlyTheScreensAreCompose() {
        stateHolderSources.forEach { file ->
            assertTrue(
                "${file.name} must not be a Compose file: the state holder is what the JVM tests exercise",
                !file.readText().contains("import androidx.compose")
            )
        }
        screenSources.forEach { file ->
            assertTrue(
                "${file.name} must be a Compose file",
                file.readText().contains("import androidx.compose")
            )
        }
    }

    // ---- the creation remediation's source-level contracts -------------------------------------------
    // These screens have no Compose harness (§17), so the wiring half of each fix is pinned against
    // the source; the behaviour half is measured through the controllers in `ProgramsControllerTest`,
    // `ProgramHomeControllerTest` and `ProgramSaveServiceTest`.

    @Test
    fun homeIsLoadedByALifecycleAwareEffectWhenTheScreenOpens() {
        val home = codeOf("ui/screens/HomeScreen.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "P1: Home triggers its Program read through the existing refresh mechanism — without " +
                "this call ProgramHomeController.load() had no production caller and the card " +
                "rendered the state holder's initial value forever",
            home.contains("LaunchedEffect(viewModel) { viewModel.refreshHomeProgram() }")
        )
        val viewModel = codeOf("viewmodel/MainViewModel.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "and that refresh reaches the controller's own load — the screen touches no service",
            viewModel.contains("viewModelScope.launch { homeProgram.load() }")
        )
    }

    @Test
    fun theNoticeHostAwaitsTheSnackbarBeforeItClearsTheNotice() {
        val host = codeOf("ui/screens/ProgramsScreen.kt").replace(Regex("""\s+"""), " ")
        val body = host.substringAfter("LaunchedEffect(notice) {")
        val show = body.indexOf("showSnackbar(")
        val clear = body.indexOf("onShown()")
        assertTrue("the effect still shows the notice", show >= 0)
        assertTrue("and still clears it afterwards", clear >= 0)
        assertTrue(
            "P2 UI-01: `showSnackbar` is awaited BEFORE `onShown()` — clearing the notice " +
                "cancelled this very coroutine at its first suspension point, so refusals, " +
                "GENERATION_UNAVAILABLE and storage failures could pass by unread",
            show < clear
        )
        assertEquals(
            "exactly one clear, after the await — not a second one on another path",
            1,
            Regex("""onShown\(\)""").findAll(body).count()
        )
    }

    @Test
    fun everyScheduleOptionIsRenderedInAWrappingLayout() {
        val editor = codeOf("ui/screens/ProgramEditorScreen.kt")
        assertTrue(
            "P2 UI-02: the five supported weekly frequencies remain declared — none is dropped " +
                "to make the row fit",
            editor.contains("SCHEDULE_OPTIONS = listOf(2, 3, 4, 5, 6)")
        )
        val collapsed = editor.replace(Regex("""\s+"""), " ")
        val flow = collapsed.indexOf("FlowRow(")
        val options = collapsed.indexOf("(SCHEDULE_OPTIONS).forEach")
        assertTrue("the editor renders a wrapping layout at all", flow >= 0)
        assertTrue("…and renders the schedule chips inside it", options >= 0)
        assertTrue(
            "so chips wrap onto further rows on portrait 360dp instead of clipping 5 and 6",
            flow < options
        )
    }

    @Test
    fun thePickerSearchesLocalizedNameAndIdAndExplainsAnEmptyResult() {
        val editor = codeOf("ui/screens/ProgramEditorScreen.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "P3 UI-04: the filter is the shared pure rule, fed the resolved display name and the " +
                "stable id — a search on the id alone could never find the localized name",
            editor.contains("matchesExerciseQuery(query, option.exerciseId, displayName)")
        )
        assertTrue(
            "and a search with no results shows an explained message instead of a blank list",
            editor.contains("if (filtered.isEmpty())")
        )
        assertTrue(
            "the empty state is this feature's own string resource (§14)",
            editor.contains("stringResource(R.string.programs_editor_search_no_results)")
        )
    }

    @Test
    fun theStateHolderSavesThroughTheSaveOrchestrationAndRunsNoPassItself() {
        val holder = codeOf("ui/programs/ProgramsController.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "§27's Save is the one production path: the draft and the chosen date are handed down",
            holder.contains("saver.save(draft, mutableState.value.draftPlannedStartDate)")
        )
        assertFalse(
            "and the controller never calls the editor's structure-level save directly — that " +
                "entry writes no slots and is not the creation unit",
            holder.contains("editor.save(")
        )
        assertFalse(
            "nor does it ever run a scheduling pass itself: only the Save service decides when a " +
                "pass runs, and only the Scheduler decides what it does",
            holder.contains("scheduler.schedule(")
        )
    }

    @Test
    fun theEditorSeedGoesThroughTheControllersFlowGuard() {
        val editor = codeOf("ui/screens/ProgramEditorScreen.kt").replace(Regex("""\s+"""), " ")
        assertTrue(
            "P2 UI-03: the screen's seed runs through the guard that tells a re-appearing flow " +
                "apart from a new one — a bare `seed()` would wipe the draft on every rotation",
            editor.contains("LaunchedEffect(seedKey) { controller.seedEditor(seedKey) { seed() } }")
        )
    }
}
