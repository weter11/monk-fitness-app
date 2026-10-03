package com.monkfitness.app.ui.programs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 28's architecture gate: **the exercise preference is an authoring surface over one existing
 * value**, and every way this could go wrong is a way a *second source of truth* appears.
 *
 * A preference editor is unusually easy to build wrong, because each of the failures below still produces
 * a screen that looks finished:
 *
 * ```text
 * a second copy of the ranking     the UI holds an editable list of its own
 * a hidden ranking                the screen sorts, or picks a position, on the user's behalf
 * a business decision in a Composable  it decides what may be added or moved
 * UI → generated domain            the screen reaches the planner instead of the service
 * UI → storage / context          the screen reaches a repository, a DAO or the context source
 * a second validation             the UI decides what the domain's own constructor refuses
 * UI → persistence                a new entity or DAO for a value that has one
 * ```
 *
 * Each is asserted against the **source** (comments stripped, whole tokens) or the **compiled shape**,
 * because this project has no Compose test harness (§17) and the screen's behaviour is measured in
 * [ProgramsExercisePreferencesTest] instead.
 */
class ExercisePreferenceArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val editorScreen = File(mainSourceRoot, "ui/screens/ProgramEditorScreen.kt")
    private val controller = File(mainSourceRoot, "ui/programs/ProgramsController.kt")
    private val uiModels = File(mainSourceRoot, "ui/programs/ProgramUiModels.kt")

    /** The pure authoring rules the screen delegates to — plain Kotlin, no Compose. */
    private val authoringRules = File(mainSourceRoot, "ui/programs/ExercisePreferenceAuthoring.kt")

    private fun source(file: File): String {
        assertTrue("expected ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeOf(file: File): String = code(source(file))

    // ---------------------------------------------------------------- the UI reaches nothing

    @Test
    fun theScreenAndItsRulesReachNoRepositoryNoContextAndNoPlanner() {
        // The **screen** and the pure authoring rules only. `ProgramsController` is deliberately not in
        // this list: it legitimately holds `ProgramGenerationService` and calls `Generate` — that is the
        // whole point of the state holder, and P25's gate scopes its ban to the screen for the same
        // reason. What matters here is that the preference's own surface reaches neither.
        val banned = listOf(
            "Repository",
            "Dao",
            "AppDatabase",
            "GenerationContextSource",
            "GenerationPreferences",
            "GenerationRequest",
            "GeneratedPlanner",
            "ExerciseSelector",
            "PlanReconciler",
            "ProgramGeneratedEditor",
            "ProgramGenerationService"
        )
        val screenLevel = listOf(editorScreen, authoringRules)
        for (file in screenLevel) {
            val imports = codeOf(file).lines().filter { it.trimStart().startsWith("import ") }
            for (token in banned) {
                assertFalse(
                    "${file.name} reaches `$token` — the editor displays the draft's value and hands a new " +
                        "one back; it never reaches storage, the context port or the planner",
                    imports.any { line -> line.contains(token) }
                )
            }
        }
    }

    @Test
    fun theScreenReadsTheDraftsOwnValueAndHandsEveryChangeBackToTheController() {
        val screen = codeOf(editorScreen)

        assertTrue(
            "the section displays the draft's own `ExercisePreference` — the value the next generation " +
                "will be planned against, not a copy the screen could edit",
            screen.contains("preference = current.preferredExercises")
        )
        // **Each operation counted on its own**, rather than one regex over all four.
        //
        // A single alternation is the weaker oracle here and it fails for a reason that has nothing to do
        // with the contract: `prefer` is a prefix of nothing while `promote`/`demote` share a suffix, so a
        // pattern written loosely either misses one operation or matches a different pair. Worse, an
        // alternation that matched `promote` twice and `demote` not at all would still report the expected
        // total. Per-operation counting cannot express that failure, because each expected value is 1 and a
        // missing call site reads as 0.
        for (operation in listOf(
            "preferDraftExercise",
            "unpreferDraftExercise",
            "promoteDraftPreferredExercise",
            "demoteDraftPreferredExercise"
        )) {
            assertEquals(
                "the screen calls `controller.$operation(` exactly once — it is offered by exactly one " +
                    "control, so a second call site would be a second path by which the draft could be " +
                    "changed from this screen",
                1,
                Regex("""controller\.$operation\(""").findAll(screen).count()
            )
        }
    }

    // ---------------------------------------------------------------- no second copy, no hidden rank

    @Test
    fun theScreenHoldsNoSecondCopyOfTheRanking() {
        // The section's only state is the picker's open flag. A `remember`ed list, or any local `var`
        // holding exercise ids, would be a second editable copy of the one fact the screen is showing.
        val screen = codeOf(editorScreen)
        val section = screen.substringAfter("private fun PreferredExercisesSection(")
            .substringBefore("@Composable\nprivate fun localizedExerciseName")

        assertFalse(
            "the section must not hold a remembered or mutable list: the ranking it renders is the draft's, " +
                "and a local copy could be reordered without the next generation ever seeing it",
            // Matched on the **field**, not the type: the mistake this rule exists to catch is a screen
            // that remembers `preference.exerciseIds`, and a pattern requiring the type name would miss
            // exactly that while matching a declaration the screen could not make anyway.
            Regex("""remember\s*(\{|\()[^)\n]*exerciseIds""")
                .containsMatchIn(section)
        )
        assertEquals(
            "and the only remembered state in the section is the picker's open flag",
            1,
            Regex("""remember\s*\{\s*mutableStateOf""").findAll(section).count()
        )
        assertFalse(
            "no local variable in the section holds a list of exercise ids",
            Regex("""var\s+\w+\s*=\s*.*exerciseIds""").containsMatchIn(section)
        )
    }

    @Test
    fun nothingInTheScreenSortsOrRanksAnythingOnTheUsersBehalf() {
        // Shaped, not keyword-shaped: a check for "sortedBy" alone would pass for the wrong reason, so the
        // claim is that the screen contains **no ordering operation of any kind** on any collection.
        val screen = codeOf(editorScreen)
        for (operation in listOf("sortedBy", "sortedWith", ".sort(", ".sorted(", "maxBy", "minBy", "shuffled")) {
            assertFalse(
                "the screen never re-orders anything: §9's order is what the USER stated, and a screen " +
                    "that sorts it has replaced their ranking with its own (`$operation`)",
                screen.contains(operation)
            )
        }
        assertFalse(
            "and the screen never constructs a preference of its own — every value it shows came from " +
                "the draft",
            Regex("""ExercisePreference\(\s*exerciseIds""").containsMatchIn(screen)
        )
        assertFalse(
            "nor does it name a bare exercise-id list as state",
            Regex("""mutableStateOf\(listOf<String>\(\)""").containsMatchIn(screen)
        )
    }

    @Test
    fun theAuthoringRulesHoldNoConfigurationAndReachNoFramework() {
        val rules = codeOf(authoringRules)

        for (token in listOf("@Composable", "androidx.", "com.monkfitness.app.data.", "Repository", "Dao")) {
            assertFalse(
                "the authoring rules are plain Kotlin the JVM tests can decide: they name no `$token`",
                rules.contains(token)
            )
        }
        assertTrue(
            "they derive from the draft's own value rather than holding one — both rules take the " +
                "preference as an argument",
            rules.contains("preferred: ExercisePreference")
        )
        assertFalse(
            "and they never construct one, so a rule cannot invent a ranking",
            Regex("""ExercisePreference\(\s*(of|listOf|exerciseIds)""").containsMatchIn(rules)
        )
    }

    @Test
    fun thePresentationModelCarriesTheDraftsOwnValueRatherThanACopy() {
        assertTrue(
            "`ProgramDraftUi.preferredExercises` is the domain's own `ExercisePreference`, displayed as it " +
                "is and edited by handing a whole new value back — the same reason `focus` is carried as " +
                "itself",
            uiModels.readText()
                .contains("val preferredExercises: com.monkfitness.app.domain.program.ExercisePreference")
        )
        assertFalse(
            "and the UI model does not expose a mutable or derived copy of the order",
            uiModels.readText().contains("var preferredExercises")
        )
        assertTrue(
            "the controller publishes the draft's own value rather than rebuilding one",
            codeOf(controller).contains("preferredExercises = draft.preferredExercises")
        )
    }

    // ---------------------------------------------------------------- no second persistence, no second lifecycle

    @Test
    fun thePreferenceAddedNoEntityAndNoDaoAndNoRevisionMechanism() {
        // The value already had a home: `program_revision` owns Program configuration, and Goals & Focus
        // established that a second table for a handful of tokens belonging to one immutable revision
        // would model a relation that does not exist.
        // Scoped to *entities* — a file whose `@Entity(tableName = ...)` names a preference table would be
        // a second owner. Scanning file NAMES alone would fire on any file that mentions the word, which is
        // not the claim.
        val entityTables = File(mainSourceRoot, "data/model").listFiles { file -> file.extension == "kt" }
            ?.map { file -> Regex("""@Entity\(\s*tableName\s*=\s*"([^"]+)"""").find(file.readText())?.groupValues?.get(1) }
            ?.filterNotNull()
            .orEmpty()
        assertFalse(
            "no entity names a preference table: the value is a nullable column on the revision that owns " +
                "it, and a second table would model a relation that does not exist. Entities: $entityTables",
            entityTables.any { table -> table.contains("preference", ignoreCase = true) }
        )

        val schema = File(mainSourceRoot, "data/local/AppDatabase.kt").readText()
        assertEquals(
            "and the migration chain grew by exactly one step, the single ALTER TABLE that adds the column",
            1,
            Regex("MIGRATION_15_16 = object : Migration\\(15, 16\\)").findAll(schema).count()
        )
        assertFalse(
            "the step writes no row: an absent preference stays absent, and a backfill would state a " +
                "ranking no user chose",
            schema.contains("preferredExerciseIds TEXT DEFAULT")
        )
    }

    @Test
    fun thePreferenceIsStructuralAndSaysSoInTheDomainsOwnVocabulary() {
        // §6's rule is that *program-behavior* changes create a Revision. A preference-only Save must
        // therefore mint one, and the only place that can be decided is the domain's own aspect list.
        assertTrue(
            "the preference is a structural aspect of its own, so a preference-only change creates a " +
                "Revision through the existing contract rather than through new revision semantics",
            com.monkfitness.app.domain.program.ProgramStructureAspect.entries
                .any { it.name == "PREFERRED_EXERCISES" }
        )
        assertTrue(
            "and the difference rule reads the preference, not merely the declaration",
            codeOf(File(mainSourceRoot, "domain/program/ProgramStructure.kt"))
                .contains("ProgramStructureAspect.PREFERRED_EXERCISES -> preferredExercises != base.preferredExercises")
        )
    }

    @Test
    fun theGeneratedPackageIsUntouchedAndTheRequestGainedNoField() {
        // §30 step 28's headline constraint: the preference uses the field that already existed. The
        // generated package keeps holding no repository, no DAO, no clock and no Android type.
        val generatedDir = File(mainSourceRoot, "domain/program/generated")
        val files = generatedDir.listFiles { file -> file.extension == "kt" }?.map { it.name }?.sorted()
            .orEmpty()

        assertEquals(
            "no file added to or removed from the pure generated package",
            listOf(
                "ExerciseSelector.kt",
                "FocusPlanner.kt",
                "GeneratedPlan.kt",
                "GeneratedPlanner.kt",
                "GenerationPolicy.kt",
                "GenerationRequest.kt",
                "PlanReconciler.kt",
                "ProgramGeneratedEditor.kt"
            ),
            files
        )

        val request = File(generatedDir, "GenerationRequest.kt")
        // Read over the **compiled** request type, so the claim is about the shape a caller sees rather
        // than about the text: a new field would have to exist here to be constructible at all.
        val requestFields = com.monkfitness.app.domain.program.generated.GenerationRequest::class.java
            .declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(
            "and `GenerationRequest` gained no field for this stage — §9's input already had one, and a " +
                "second would be a second way to state the same fact (§30 step 28's headline constraint)",
            listOf(
                "duration",
                "schedule",
                "focus",
                "candidates",
                "availableEquipment",
                "preferences",
                "policy"
            ).sorted(),
            requestFields.sorted()
        )
        assertTrue(
            "while `GenerationPreferences.userPreferredExerciseIds` is still exactly the field this stage " +
                "fills, with its name and type unchanged",
            source(request).contains("val userPreferredExerciseIds: List<String> = emptyList()")
        )
    }
}