package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.generated.ProgramGeneratedEditor
import com.monkfitness.app.domain.program.generated.ReconciliationReport
import com.monkfitness.app.domain.usecase.ProgramGenerationResult.Generated
import com.monkfitness.app.domain.usecase.ProgramGenerationResult.Refused
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P24's production generation flow, pinned mechanically.
 *
 * Making generation production-callable is where the layering claims stop being about two files and
 * start being about the whole chain, and the interesting failures are all invisible to a behavioural
 * test because each still produces a plan:
 *
 *  * the **classification becoming a rule again** — a `when (category)` added beside the explicit
 *    table, or the table replaced by a lookup, and the app is back to fabricating a training fact
 *    while every planner assertion still passes;
 *  * the **orchestration being duplicated** in the UI layer: a controller that builds its own
 *    `GenerationRequest`, names the planner, or reconciles by hand. Then §7's preservation rules have
 *    two homes and they will diverge;
 *  * the **service acquiring a way to persist** — a repository, a DAO, a clock, an id generator of
 *    its own — which would make "Generate only alters a draft" a promise rather than an absence;
 *  * the **adaptive side leaking in**: a Stage-1 signal, a `PilotProgressionProfiles` read or an
 *    invented preference, none of which P24 integrates;
 *  * the **pure generated package changing** to accommodate the wiring. It must not.
 */
class ProgramGenerationFlowArchitectureTest {

    private val mainDir = File("src/main/java/com/monkfitness/app")
        .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }

    private val serviceFile = File(mainDir, "domain/usecase/ProgramGenerationService.kt")
    private val classificationFile = File(mainDir, "domain/usecase/ProductionFocusClassification.kt")
    private val serviceSources = listOf(serviceFile, classificationFile)
    private val generatedDir = File(mainDir, "domain/program/generated")

    /** This stage's own declaration site — the file that *is* the class, not a caller of it. */
    private val servicePath = "domain/usecase/ProgramGenerationService.kt"

    private fun codeLines(file: File): List<String> = code(file.readText())
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun code(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    // ------------------------------------------------------------------ the classification is data

    @Test
    fun theClassificationIsAStatedTableAndNotARuleOverTheCataloguesGroupings() {
        // Three vocabularies and three ways to read an exercise, plus the accessors that would reach
        // them. A table keyed by id needs none of them: if the classification is complete, there is
        // nothing left for a rule to decide, which is exactly why a fallback written "just in case"
        // is the whole failure mode this rule exists to catch.
        val identifiers = listOf(
            "ExerciseCategory", "ExerciseSubCategory", "exerciseToFamiliesMap",
            "ExerciseCategoryFilter", "postureFocusAreas", "stretchFocusAreas",
            "flexibilityFocusAreas", "familyId", "animationId", "WorkoutGenerator"
        )
        // The accessors are matched as substrings: a *rule* over the catalogue reads them, and a
        // `when (category)` needs no identifier named `ExerciseCategory` to be one.
        val accessors = listOf(".category", ".subCategory")
        val offenders = serviceSources.flatMap { file -> codeLines(file).mapNotNull { line ->
            identifiers.firstOrNull { token ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(line)
            }?.let { "${file.name}: $line" }
                ?: accessors.firstOrNull { accessor -> line.contains(accessor) }
                    ?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "membership is stated per exercise id; deriving it from a category, a body region, a " +
                "training style or even the family would be the fabrication P23 refused, and doing it " +
                "beside the table rather than instead of it would make the table decorative: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theClassificationNeverSubstitutesAFocusForAMissingOne() {
        // The four substitutions, and the two shapes they take in a *table*: an entry for an id that
        // does not exist (harmless-looking, a classification of nothing) and a lookup that falls back
        // (`?: setOf(...)`, `?: Focus.entries.toSet()`) rather than answering "unclassified".
        val forbidden = listOf(
            "?: setOf(", "?: Focus", "?: FocusPlan", "Focus.entries.toSet()", "orEmpty()",
            "getOrDefault", "getOrElse", "?: emptySet"
        )
        val offenders = codeLines(classificationFile).filter { line ->
            forbidden.any { token -> line.contains(token) }
        }

        assertTrue(
            "an exercise this table does not name is unclassified, never admitted with an invented " +
                "focus and never read as an empty set that means \"trains everything\" (§6, §8): " +
                "$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theClassificationIsReadThroughTheFocusSourcePortAndNotConstructed() {
        val code = code(serviceFile.readText())
        assertTrue(
            "the service reaches the classification through P23's port, so the port stays the seam a " +
                "user-facing Goals & Focus editor or a persisted map would fill",
            code.contains("private val focusSource: GenerationFocusSource")
        )
        assertTrue(
            "and it asks that source per exercise rather than building a candidate itself",
            code.contains("catalogue.catalogueOf(focusSource)")
        )
        assertTrue(
            "so the boundary — not this service — is what reads the catalogue",
            code.contains("ProductionGenerationBoundary.generationRequest(")
        )
    }

    @Test
    fun theClassificationHoldsNoCollaboratorAndReadsNoState() {
        listOf(ProductionFocusClassification::class.java).forEach { type ->
            assertEquals(
                "the table holds nothing but its own data",
                emptyList<String>(),
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                    .map { it.name }
            )
        }
        assertEquals(
            "and it is constructed with nothing",
            0,
            ProductionFocusClassification::class.java.declaredConstructors.single().parameterCount
        )
        assertTrue(
            "it really is the port P23 declared, not a look-alike",
            ExerciseGenerationFacts.GenerationFocusSource::class.java.isAssignableFrom(
                ProductionFocusClassification::class.java
            )
        )
    }

    // ------------------------------------------------------------------ the service orchestrates, and only that

    @Test
    fun theServiceRunsTheExistingDomainComponentsRatherThanRestatingThem() {
        val code = code(serviceFile.readText())
        assertTrue(
            "the pass reaches the generated editor, which is what runs the planner and the reconciler",
            code.contains("ProgramGeneratedEditor(draft = draft, ids = ids).generate(request)")
        )
        listOf("GeneratedPlanner", "PlanReconciler", "FocusPlanner", "ExerciseSelector").forEach { token ->
            val calls = Regex("(?<![A-Za-z0-9_])$token\\.").findAll(code).count()
            assertEquals(
                "the service names $token only where a test or a KDoc reference may, never as a " +
                    "call it makes itself: a second implementation of §7's reconciliation is a second " +
                    "set of preservation rules that will drift",
                0,
                calls
            )
        }
    }

    @Test
    fun theServiceHoldsNoRepositoryNoStorageAndNoClock() {
        val forbidden = listOf(
            "ProgramRepository", "ProgramPlanRepository", "ProgramScheduleRepository",
            "ProgramExerciseDao", "ProgramDao", "AppDatabase", "Dao", "Room", "@Entity",
            "ProgramEditorService", "ProgramSaveService", "ProgramScheduler", "SlotPlanner",
            "Clock", "LocalDate.now", "Instant.now", "System.currentTimeMillis", "Random",
            "WorkoutGenerator", "exerciseToFamiliesMap",
            "kotlinx.coroutines", "androidx", "import android", "com.monkfitness.app.ui",
            "com.monkfitness.app.viewmodel", "R.string"
        )
        val offenders = serviceSources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { token -> line.contains(token) }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "Generate and Regenerate only ever alter a draft (§7): the service holds nothing that " +
                "could persist one, schedule a slot, read a calendar or reach the UI. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceWritesNoRevisionAndCreatesNoProgram() {
        val code = code(serviceFile.readText())
        listOf(
            "ProgramRevision", "RevisionId", "ProgramId", "Program(", "save(", "commit", "transaction"
        ).forEach { token ->
            assertFalse(
                "a generation pass mints no identity and writes nothing — 'Save' remains the only " +
                    "route to a revision (§6, §7) — so the service must not name '$token'",
                code.contains(token)
            )
        }
    }

    @Test
    fun theServiceConsultsNoAdaptiveSource() {
        val forbidden = listOf(
            "PilotProgressionProfiles", "AdaptiveRepository", "adaptiveRepository",
            "NoExerciseFamilyClassification", "ProgramAdaptiveIntegration", "programAdaptive",
            "familyProgression", "SessionRuntime", "ProgressionPolicy"
        )
        val offenders = serviceSources.flatMap { file -> codeLines(file).mapNotNull { line ->
            forbidden.firstOrNull { token ->
                Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
                    .containsMatchIn(line)
            }?.let { "${file.name}: $line" }
        } }

        assertTrue(
            "P24 does not integrate the adaptive engine, so the request carries GenerationPreferences" +
                ".NONE and no history, no profile and no decision reaches it. A read here would be a " +
                "hidden adaptive rule: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theServiceStatesTheNeutralPreferencesRatherThanDefaultingThemQuietly() {
        val constructor = ProgramGenerationService::class.java.declaredConstructors
            .first { !it.isSynthetic }
        val declaration = code(serviceFile.readText())

        assertEquals(
            "the service takes exactly the five things the flow needs — a catalogue, a focus source, " +
                "an id source and the two stated values — and no more. A sixth collaborator would be " +
                "one more thing a generation pass could reach.",
            5,
            constructor.parameterCount
        )
        assertTrue(
            "preferences and policy are the only optional ones, because the generated domain " +
                "declares the neutral value for each and this stage has no production source for " +
                "them (§5). Everything the pass *must* be told is required, so a caller cannot " +
                "silently omit the catalogue or the id source: $declaration",
            declaration.contains("preferences: GenerationPreferences = GenerationPreferences.NONE") &&
                declaration.contains("policy: GenerationPolicy = GenerationPolicy.DEFAULT")
        )
    }

    // ------------------------------------------------------------------ the shape of the result

    @Test
    fun theResultIsTheThreeClassesTheUiMustTellApart() {
        assertEquals(
            "§28: done / refused / failed, and a boolean or an empty draft would collapse two of them",
            listOf("Failed", "Generated", "Refused"),
            ProgramGenerationResult::class.java.declaredClasses
                .filterNot { it.isInterface }
                .map { it.simpleName }
                .sorted()
        )
        assertEquals(
            "and the two refusals are absences of stated facts, each with its own message",
            listOf("NoExerciseStatesItsFocus", "NothingPlannable"),
            ProgramGenerationRefusal::class.java.declaredClasses
                .filterNot { it.isInterface }
                .map { it.simpleName }
                .sorted()
        )
        assertTrue(
            "a refusal carries the planner's own limitations rather than a copy of them",
            ProgramGenerationRefusal.NothingPlannable::class.java.declaredFields.any { it.name == "limitations" }
        )
    }

    // ------------------------------------------------------------------ the controller reaches the service only

    @Test
    fun theControllerReachesTheServiceAndNothingBelowIt() {
        val controller = code(File(mainDir, "ui/programs/ProgramsController.kt").readText())
        listOf(
            "ProductionGenerationBoundary", "ProductionFocusClassification", "WorkoutGenerator",
            "GeneratedPlanner", "PlanReconciler", "ProgramGeneratedEditor", "GenerationRequest",
            "GenerationCandidate", "GenerationPolicy", "FocusPlan.focused"
        ).forEach { token ->
            assertFalse(
                "the controller names no component below the application service ('$token'): the " +
                    "orchestration has one home, and a screen that could assemble its own would give " +
                    "§7's preservation rules a second",
                controller.contains(token)
            )
        }
        assertTrue(
            "and it does reach the service, handing it the working draft",
            controller.contains("generation.generate(draft, availableEquipment())")
        )
        assertTrue(
            "replacing the working draft with what came back, rather than mutating it",
            controller.contains("workingDraft = result.edit.draft")
        )
    }

    @Test
    fun theControllerConsultsNoRepositoryAndNoRoom() {
        val controller = code(File(mainDir, "ui/programs/ProgramsController.kt").readText())
        listOf("Dao", "AppDatabase", "Room", "Entity(", "ProgramRepository", "@Entity").forEach { token ->
            assertFalse(
                "a screen writes no DAO and holds no Room entity (§16, §33) — '$token'",
                controller.contains(token)
            )
        }
    }

    // ------------------------------------------------------------------ the composition root, and only it

    @Test
    fun theCompositionRootProvidesTheServiceAndNoOneElseBuildsOne() {
        val sites = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(mainDir).path.replace('\\', '/') == servicePath }
            .filter { file -> code(file.readText()).contains("ProgramGenerationService(") }
            .map { it.relativeTo(mainDir).path.replace('\\', '/') }
            .toList()

        assertEquals(
            "one construction site, in the composition root (§26): a controller or a screen building " +
                "its own would be a second generation path with its own rules",
            listOf("di/AppContainer.kt"),
            sites
        )
    }

    @Test
    fun theCompositionRootIsNotAServiceLocatorForThisNode() {
        val consumers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.relativeTo(mainDir).path.replace('\\', '/') != "di/AppContainer.kt"
            }
            .filter { file -> code(file.readText()).contains("programGenerationService") }
            .map { it.relativeTo(mainDir).path.replace('\\', '/') }
            .toList()

        assertEquals(
            "the node is handed to its consumer as an ordinary constructor argument, and nothing " +
                "reaches into the container for it (§26)",
            listOf("viewmodel/MainViewModel.kt"),
            consumers
        )
    }

    @Test
    fun theViewModelHandsTheServiceAndTheEquipmentOverAndBuildsNeither() {
        val viewModel = code(File(mainDir, "viewmodel/MainViewModel.kt").readText())

        assertTrue(
            "the service comes from the composition root",
            viewModel.contains("generation = programGraph.programGenerationService")
        )
        assertTrue(
            "and the user's declared equipment is read at the moment of the pass and forwarded " +
                "verbatim — no normalisation in the UI layer",
            viewModel.contains("availableEquipment = { availableEquipment.value }")
        )
        assertFalse(
            "a view model constructs no application service (§13, §26)",
            viewModel.contains("ProgramGenerationService(")
        )
    }

    // ------------------------------------------------------------------ the pure package is untouched

    @Test
    fun thePureGeneratedPackageGainedNoFileAndReachesNothingNew() {
        assertEquals(
            "the pure package is exactly the eight files Stage 10 established and the stages since " +
                "then have not added to — P24 wires the flow around it, so a new file here would " +
                "mean the domain had to know something it must not",
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
            generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
                .orEmpty().map { it.name }.sorted()
        )
        val imports = generatedDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty().flatMap { file -> codeLines(file) }.filter { it.startsWith("import ") }

        assertTrue(
            "and it imports neither the application layer nor the app's data model (§25, §30 step 10): $imports",
            imports.none { it.startsWith("import com.monkfitness.app.domain.usecase") } &&
                imports.none { it.startsWith("import com.monkfitness.app.data.") } &&
                imports.none { it.startsWith("import com.monkfitness.app.di") }
        )
    }

    @Test
    fun theGeneratedEditorIsReachedAndNotReimplemented() {
        // The service's own call, read from the compiled shape rather than the source, so a
        // hand-rolled second path could not satisfy the claim with a matching string.
        val called = ProgramGeneratedEditor::class.java.declaredMethods.map { it.name }
        assertTrue(
            "the domain keeps both entry points §7 names, and they are the same reconciliation: $called",
            called.containsAll(listOf("generate", "regenerate"))
        )
        assertEquals(
            "a reconciliation report is what a caller shows for 'conflicts with user choices are " +
                "shown explicitly' (§7), so the result must carry it",
            listOf("draft", "plan", "reconciliation"),
            com.monkfitness.app.domain.program.generated.GeneratedDraftEdit::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
                .sorted()
        )
        assertTrue(
            "and the report is the domain's own value, not a copy: it is what answers \"conflicts " +
                "with user choices are shown explicitly\" (§7)",
            ReconciliationReport::class.java.declaredMethods.any { it.name == "getConflicts" }
        )
    }

    @Test
    fun theServiceSpeaksInTheDomainsOwnDraftValues() {
        val methods = ProgramGenerationService::class.java.declaredMethods.associateBy { it.name }
        listOf("generate", "regenerate").forEach { name ->
            val parameters = methods.getValue(name).parameterTypes.toList()
            assertEquals(
                "§7's two entry points are (draft, availableEquipment) and nothing else: a " +
                    "generation pass takes the user's own draft, not a configuration someone else " +
                    "assembled for it",
                listOf(ProgramEditorDraft::class.java, Set::class.java),
                parameters
            )
        }
        assertEquals(
            "and both return the typed result, never a boolean (§28: the UI must tell three cases " +
                "apart, and a Boolean collapses two of them)",
            ProgramGenerationResult::class.java,
            methods.getValue("generate").returnType
        )
        assertEquals(
            "while the success case carries the editor's own three-part edit",
            Generated::class.java.declaredFields.single { it.name == "edit" }.type,
            com.monkfitness.app.domain.program.generated.GeneratedDraftEdit::class.java
        )
        assertTrue(
            "and the refusal case is present in the shape, not only in a branch",
            Refused::class.java.declaredFields.any { it.name == "reason" }
        )
    }
}
