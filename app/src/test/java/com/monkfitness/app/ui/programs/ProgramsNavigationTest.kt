package com.monkfitness.app.ui.programs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The navigation rules §3 and §16 state, checked against the sources rather than trusted.
 *
 * The app has **one** navigation graph, one `NavController` and one place where destinations are
 * declared, and every rule below is a way this stage could quietly break that:
 *
 * ```text
 * a second NavHost or NavController      §3's "keep the single-navigation-graph architecture"
 * an unreachable Program destination     the acceptance flow walks Settings → Programs → My Programs → …
 * a domain object in a route argument    §16: a stable identifier or a simple value, never an object
 * the legacy route as the primary path   §7: the target Program System is the user-facing authority
 * ```
 */
class ProgramsNavigationTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private fun source(relativePath: String): String {
        val file = File(mainSourceRoot, relativePath)
        assertTrue("expected $relativePath at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private val activity get() = code(source("MainActivity.kt"))

    private val viewModel get() = code(source("viewmodel/MainViewModel.kt"))

    // ---- one graph -----------------------------------------------------------------------------

    @Test
    fun theAppStillHasExactlyOneNavigationGraph() {
        assertEquals("exactly one NavHost", 1, Regex("""NavHost\(""").findAll(activity).count())
        assertEquals(
            "exactly one nav controller",
            1,
            Regex("""rememberNavController\(\)""").findAll(activity).count()
        )

        val graphs = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> code(file.readText()).contains("NavHost(") }
            .map { file -> file.relativeTo(mainSourceRoot).path.replace('\\', '/') }
            .sorted()
            .toList()

        assertEquals(
            "§3: the Program screens are destinations of the existing graph, not a graph of their own",
            listOf("MainActivity.kt"),
            graphs
        )
    }

    // ---- the destinations are reachable ----------------------------------------------------------

    @Test
    fun everyProgramDestinationIsDeclaredInThatOneGraph() {
        listOf(
            "ProgramsScreen(",
            "ProgramCreateChoiceScreen(",
            "MyProgramsScreen(",
            "ProgramDetailScreen(",
            "ProgramEditorScreen(",
            "ProgramImportScreen("
        ).forEach { screen ->
            assertTrue("the graph must declare $screen", activity.contains(screen))
        }
    }

    @Test
    fun theRoutesAreDeclaredWithTheAppsOtherRoutes() {
        listOf(
            "ROUTE_PROGRAMS",
            "ROUTE_MY_PROGRAMS",
            "ROUTE_PROGRAM_DETAIL",
            "ROUTE_PROGRAM_CREATE",
            "ROUTE_PROGRAM_EDITOR_CREATE",
            "ROUTE_PROGRAM_EDITOR_EDIT",
            "ROUTE_PROGRAM_EDITOR_COPY",
            "ROUTE_PROGRAM_IMPORT"
        ).forEach { route ->
            assertTrue(
                "$route must be declared with the app's other routes",
                Regex("""const val $route = """).containsMatchIn(viewModel)
            )
            assertTrue(
                "and the graph must use $route",
                activity.contains("MainViewModel.$route")
            )
        }
    }

    @Test
    fun everyProgramRouteReachesTheAcceptanceFlowsScreens() {
        // The final acceptance scenario walks Settings → Programs → My Programs → Detail, the two
        // creation entry paths, the editor, and Import. Each hop below is a hop that scenario takes.
        assertTrue("Programs → My Programs", activity.contains("MainViewModel.ROUTE_MY_PROGRAMS"))
        assertTrue("Programs → Create Program", activity.contains("MainViewModel.ROUTE_PROGRAM_CREATE"))
        assertTrue("Programs → Import Program", activity.contains("MainViewModel.ROUTE_PROGRAM_IMPORT"))
        assertTrue("a row → Program Detail", activity.contains("MainViewModel.programDetailRoute("))
        assertTrue("Detail → Edit", activity.contains("MainViewModel.programEditorEditRoute("))
        assertTrue("Detail → Copy", activity.contains("MainViewModel.programEditorCopyRoute("))
        assertTrue("Create → MANUAL", activity.contains("ProgramMode.MANUAL.name"))
        assertTrue("Create → GENERATED", activity.contains("ProgramMode.GENERATED.name"))
    }

    // ---- what a route may carry ------------------------------------------------------------------

    @Test
    fun navigationArgumentsAreStableIdentifiersAndSimpleValues() {
        val arguments = Regex("""navArgument\("([^"]+)"\)""").findAll(activity)
            .map { match -> match.groupValues[1] }
            .toSet()

        assertEquals(
            "§16: a route carries a Program's id, one of §2's mode names, an opportunity id, an " +
                "exercise id and a pose id — identifiers and simple values, never a domain object. " +
                "§30 step 15 removed the day-based workout arguments: a day number was the retired " +
                "56-day grid's position, not an identity, and the one workout route now carries the " +
                "**opportunity** it is for",
            setOf("programId", "mode", "slotId", "exerciseId", "poseId"),
            arguments
        )
        listOf("programId", "mode").forEach { argument ->
            assertTrue(
                "$argument is read back as a string and only as a string",
                activity.contains("""getString("$argument")""")
            )
        }
    }

    // ---- the legacy path is no longer the entry point (§7) ---------------------------------------

    @Test
    fun theProgramSystemsEntryPointIsTheSettingsProgramsSection() {
        val settings = code(source("ui/screens/SettingsScreen.kt"))

        assertTrue(
            "Settings offers the Program System's entry point",
            settings.contains("onOpenPrograms")
        )
        assertTrue(
            "§12: the section is labelled with the Program System's own title resource",
            settings.contains("R.string.programs_title")
        )
        assertTrue(
            "and it is given its own description, so it does not read as a second program manager",
            settings.contains("R.string.programs_desc")
        )
        assertTrue(
            "the graph navigates from Settings to the Programs destination",
            activity.contains("MainViewModel.ROUTE_PROGRAMS")
        )
    }

    @Test
    fun theLegacyCustomProgramRouteIsGoneAndTheProgramSystemIsTheOnlyEntryPoint() {
        // §30 step 15 inverted this claim. Until then the legacy route was *retained* — pinned as
        // "reached from exactly one place" — because its editor still configured the shipped generator's
        // exercise selection. The final audit showed that selection had no reader left (its only consumer
        // was the legacy session's configuration capture, which this stage deleted), so it became a
        // setting that could affect nothing and the whole surface was retired with its route.
        assertFalse(
            "the legacy editor's screen is gone, not merely unreached",
            activity.contains("CustomProgramScreen(")
        )
        assertFalse(
            "and so is the destination it was reached by",
            activity.contains("ROUTE_CUSTOM_PROGRAM")
        )
        assertTrue(
            "the Program System is reached from its own Settings callback, which navigates to its " +
                "own destination",
            activity.contains("onOpenPrograms = {")
        )
    }
}
