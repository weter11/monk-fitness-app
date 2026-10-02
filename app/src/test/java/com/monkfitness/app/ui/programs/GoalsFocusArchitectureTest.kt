package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramEditorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P25's architecture gate: **§7's Goals & Focus is an authoring surface over one existing value**, and
 * the ways that could go wrong are all ways a *second source of truth* appears.
 *
 * A focus editor is unusually easy to build wrong, because every failure below still produces a screen
 * that looks finished:
 *
 * ```text
 * a second copy of the configuration   the UI holds an editable FocusPlan of its own
 * business calculation in the screen   percentages summed, shared or completed in a Composable
 * a hidden default                     choosing a focus or a share the user never stated
 * a hidden priority                    the tap order surviving as a rank
 * a second validation                  the UI deciding what §8's constructor refuses
 * UI → generated domain                the screen reaching the planner instead of the service
 * UI → storage                         the screen reaching a DAO, a repository or the scheduler
 * new persistence                      a new entity or DAO for a value that already has one
 * ```
 *
 * Each is asserted against the **source** (comments stripped, whole tokens) or against the compiled
 * shape, because this project has no Compose test harness (§17) and the screen's behaviour is measured
 * in [ProgramsGoalsFocusTest] instead.
 */
class GoalsFocusArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val editorScreen = File(mainSourceRoot, "ui/screens/ProgramEditorScreen.kt")

    /** The UI's plain-Kotlin layer: the state holder, its models and this stage's pure authoring rules. */
    private val stateHolderSources: List<File> = File(mainSourceRoot, "ui/programs")
        .listFiles { file -> file.isFile && file.extension == "kt" }
        ?.sortedBy { file -> file.name }
        ?: emptyList()

    /** The screen, and the pure rules it delegates to. */
    private val authoringRules = File(mainSourceRoot, "ui/programs/GoalsFocusAuthoring.kt")

    private fun source(file: File): String {
        assertTrue("expected ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeOf(file: File): String = code(source(file)).replace(Regex("""\s+"""), " ")

    private fun assertNoToken(files: List<File>, tokens: List<String>, because: String) {
        val offenders = files.flatMap { file ->
            val text = code(file.readText())
            tokens.filter { token -> token in text }.map { token -> "${file.name}: $token" }
        }
        assertEquals("$because — found: $offenders", emptyList<String>(), offenders)
    }

    // ---------------------------------------------------------------- one source of truth

    @Test
    fun theDraftsConfigurationIsTheOnlyOneTheUiHolds() {
        val holders = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> file.toString().contains("/ui/") }
            .map { file -> relative(file) to code(file.readText()) }
            .filter { (_, text) -> "FocusPlan" in text }
            .map { (path, _) -> path }
            .sorted()
            .toList()

        assertTrue("expected the UI layer to know the configuration at all", holders.isNotEmpty())
        assertEquals(
            "§7's Goals & Focus reaches the type in exactly four places, and each has one job: the pure " +
                "rules build a finished value, the presentation hands the draft's value over, the " +
                "controller is what writes that value to the draft, and the screen displays it and hands " +
                "a new one back. A fifth would be a copy that could be edited and disagree with the " +
                "draft the next Generate reads — found: $holders",
            listOf(
                "ui/programs/GoalsFocusAuthoring.kt",
                "ui/programs/ProgramUiModels.kt",
                "ui/programs/ProgramsController.kt",
                "ui/screens/ProgramEditorScreen.kt"
            ),
            holders
        )

        // The screen may not *hold* one: it receives the draft's value and hands a new one back.
        val screen = code(editorScreen.readText())
        // A whole-token boundary on the type: without it the scan matches `toFocusPlan()`, which is a
        // method NAME containing the word, not a declaration holding a configuration.
        assertFalse(
            "a Composable that remembers a FocusPlan is a second copy of the draft's configuration",
            Regex("""(var|val)\s+\w+\s*(by\s+\w+\([^)]*\))?\s*:\s*[^\n]*?(?<![A-Za-z0-9_])FocusPlan(?![A-Za-z0-9_])""")
                .containsMatchIn(screen)
        )
        assertFalse(
            "…and none may be inferred into one either",
            Regex("""=\s*FocusPlan\.(focused|custom|Balanced)\(""").containsMatchIn(
                screen
            ) || Regex("""=\s*[^\n]*as\s+FocusPlan""").containsMatchIn(screen)
        )
        assertFalse(
            "nor may it keep one in a collection of its own",
            "MutableState<List<Focus>>" in screen || "mutableStateOf(FocusPlan" in screen
        )
    }

    @Test
    fun theConfigurationIsCarriedOnTheDraftAndOnItsPresentationAndNowhereElse() {
        assertTrue(
            "the draft's own field is where a configuration lives",
            ProgramEditorDraft::class.java.declaredFields.any { field -> field.name == "focus" }
        )
        assertTrue(
            "and the presentation hands that exact value to the screen rather than rebuilding one",
            ProgramDraftUi::class.java.declaredFields.any { field -> field.name == "focus" }
        )
        val controller = codeOf(File(mainSourceRoot, "ui/programs/ProgramsController.kt"))
        assertTrue(
            "the controller publishes the draft's own value",
            controller.contains("focus = draft.focus")
        )
    }

    // ---------------------------------------------------------------- no business calculation in the screen

    @Test
    fun theScreenComputesNoSharesAndDecidesNoConfiguration() {
        assertNoToken(
            listOf(editorScreen),
            listOf(
                "FocusAllocation(",             // building an allocation is the domain's constructor
                "FocusPlan.Custom(",            // constructing the value directly rather than through `custom`
                "FocusPlan.Focused(",           // …and the same for FOCUSED's own invariants
                "sumOf",                        // adding percentages up
                "percent +", "percent -", "percent *", "/ 100",   // a share derived or adjusted
                "FULL_ALLOCATION *",            // a split the user did not ask for
                "DEFAULT"                       // §8's default substituted for a choice the user made
            ),
            "the screen renders §7's configuration and hands back a finished FocusPlan; every rule about " +
                "what one may be lives in the domain"
        )
    }

    @Test
    fun theScreensOnlyConfigurationWritesAreTheOnesThatHandAValueToTheController() {
        val screen = codeOf(editorScreen)
        val writes = Regex("""setDraftFocus\(""").findAll(screen).count()
        assertEquals(
            "the section edits the draft through the one existing controller operation, so there is no " +
                "second path by which a configuration could reach the draft",
            1,
            writes
        )
    }

    @Test
    fun thePureRulesAreWhereTheArithmeticLivesAndTheyDelegateValidationToTheDomain() {
        val rules = code(authoringRules.readText())

        assertTrue(
            "the dialog's working shares are a value of their own — an unfinished allocation cannot be a " +
                "FocusPlan, so it is deliberately not one",
            "data class FocusPercentEntry" in rules
        )
        assertTrue(
            "and the finished configuration is built by the domain's own canonical constructor",
            rules.contains("FocusPlan.custom(")
        )
        assertTrue(
            "§8's FOCUSED goes through the domain's own factory, so the tap order cannot survive as a rank",
            rules.contains("FocusPlan.focused(")
        )
        assertFalse(
            "the rules never repair an allocation: an invalid one is reported as no configuration",
            "return FocusPercentEntry()" in rules
        )
    }

    // ---------------------------------------------------------------- no hidden defaults

    @Test
    fun noFocusIsChosenForTheUserWhenTheGoalIsSwitched() {
        val screen = codeOf(editorScreen)
        assertFalse(
            "switching the goal to FOCUSED must not name a focus the user did not tap — the first tap is " +
                "their choice, and anything else is a focus the user never asked to train",
            Regex("""FocusPlan\.focused\(listOf\(Focus\.\w+\)\)""").containsMatchIn(screen)
        )
        assertTrue(
            "and it opens the chooser instead, confirming only what the user picked",
            screen.contains("onFocus(FocusPlan.focused(focuses))")
        )
        assertTrue(
            "CUSTOM's shares likewise start from the draft's own or from nothing at all — `?: 0` is the " +
                "absence of a share, never a default one",
            "sharesFrom(focus)" in screen && "?.percent ?: 0" in screen
        )
    }

    @Test
    fun theSevenFocusesAreOfferedAndNothingIsRanked() {
        val screen = code(editorScreen.readText())
        assertTrue(
            "§8's whole vocabulary is offered, in its own order",
            "Focus.entries.forEach" in screen
        )
        assertFalse(
            "and the UI never sorts, ranks or weights a selection — §8 states no priority between focuses",
            Regex("""sortedBy|sortedWith|\.sort\(""").containsMatchIn(screen)
        )
        assertFalse(
            "nor does it hold one focus ahead of another in a list it built",
            "priorit" in code(editorScreen.readText()).lowercase()
        )
    }

    // ---------------------------------------------------------------- the layer the screen may reach

    @Test
    fun theScreenReachesNoGeneratedDomainDirectly() {
        assertNoToken(
            listOf(editorScreen),
            listOf(
                "GeneratedPlanner", "FocusPlanner", "ProductionGenerationBoundary", "GenerationRequest",
                "PlanReconciler", "ProgramGeneratedEditor", "com.monkfitness.app.domain.program.generated"
            ),
            "§16: a screen reaches generation through the controller, which reaches the application " +
                "service; the generated domain is not a UI collaborator"
        )
    }

    @Test
    fun theScreenReachesNoStorageNoSchedulerAndNoAdaptive() {
        assertNoToken(
            listOf(editorScreen),
            listOf(
                "Dao", "AppDatabase", "Room", "@Entity", "Repository", "ProgramScheduler",
                "Adaptive", "scheduler.", "repository."
            ),
            "§33: a screen writes no DAO, holds no entity and consults no repository, scheduler or " +
                "adaptive source — the focus configuration is a draft field and reaches storage only " +
                "through Save"
        )
    }

    @Test
    fun theAuthoringRulesReachNoServiceNoStorageAndNoGeneratedDomainEither() {
        assertNoToken(
            listOf(authoringRules),
            listOf(
                "import androidx.compose", "import android.", "Dao", "Repository", "AppDatabase",
                "GeneratedPlanner", "ProgramGenerationService", "Scheduler", "Adaptive",
                "FocusPercentEntry(var ", "MutableState"
            ),
            "the pure rules are plain Kotlin arithmetic over the user's own numbers: no framework, no " +
                "storage, no planner, and no mutable state that could outlive the dialog"
        )
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun theFocusConfigurationAddedNoEntityAndNoDao() {
        val entities = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> "@Entity" in code(file.readText()) }
            .map { file -> relative(file) }
            .sorted()
            .toList()
        val daos = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> "@Dao" in code(file.readText()) }
            .map { file -> relative(file) }
            .sorted()
            .toList()

        assertEquals(
            "§6 already stores the configuration on the Program Revision, so P25 adds no table of its " +
                "own — a new entity here would be a second place the same fact is written",
            24,
            entities.size
        )
        assertEquals(
            "and no new DAO: the configuration is reached through the revision's own reader",
            20,
            daos.size
        )
    }

    @Test
    fun theConfigurationIsPartOfTheStructureAndSoOfARevision() {
        val structure = codeOf(File(mainSourceRoot, "domain/program/ProgramStructure.kt"))
        assertTrue(
            "§6 lists goals/focus among the structural aspects, and that is what makes a focus-only change " +
                "create a Revision without this stage adding any mechanism",
            structure.contains("FOCUS")
        )
        assertTrue(
            "and the comparison that decides it is the structure's own",
            structure.contains("ProgramStructureAspect.FOCUS -> focus != base.focus")
        )
    }

    private fun relative(file: File): String = file.relativeTo(mainSourceRoot).path.replace('\\', '/')
}