package com.monkfitness.app.ui.programs

import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import org.junit.Assert.assertEquals
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
            "ProgramLifecycleService(", "ProgramEditorService(", "ProgramImportService(",
            "ProgramExportService(", "ProgramProgressService(", "ProgramScheduler(",
            "ProgramsController("
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
            "§24/§26: the six application services, the two UI ports, and the two ports \"today\" is read " +
                "from — and nothing else. A repository, a DAO, a clock of its own or a platform type in " +
                "this list would be a layer reached around",
            listOf(
                ProgramLifecycleService::class.java.name,
                ProgramEditorService::class.java.name,
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
}
