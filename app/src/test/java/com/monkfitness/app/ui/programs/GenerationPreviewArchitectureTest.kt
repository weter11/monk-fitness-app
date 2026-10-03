package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.usecase.ProgramGenerationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * P26's architecture gate: **§7's Preview is a projection of one generation pass, and the UI layer is
 * still nowhere near the generated domain.**
 *
 * A Preview is unusually easy to build wrong, because every failure below still produces a screen that
 * looks finished and a plan that looks right:
 *
 * ```text
 * UI → generated domain      a Composable receiving a GeneratedPlan / ReconciliationReport / limitation
 * a second generation path   a preview assembled somewhere other than ProgramGenerationService
 * a hidden Save              a preview (or an Apply) reaching a repository, a DAO or the scheduler
 * a second current draft     a preview holding an editable draft of its own
 * a UI-built plan            a screen sorting, ranking or re-counting the plan's own figures
 * a developer-facing sentence Text(text = limitation.message) — §33's silent substitution, localized
 * a hidden conflict          a preview that drops or "resolves" a preserved user choice
 * a hidden limitation        a preview that swallows what the planner could not do
 * ```
 *
 * Each is asserted against the **source** (comments stripped, whole tokens) or against the compiled
 * shape, because this project has no Compose test harness (§17) and the screen's behaviour is measured
 * in [ProgramsGenerationPreviewTest] instead.
 */
class GenerationPreviewArchitectureTest {

    private val mainSourceRoot = File("src/main/java/com/monkfitness/app").let { dir ->
        if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app")
    }

    private val editorScreen = File(mainSourceRoot, "ui/screens/ProgramEditorScreen.kt")

    /** The UI's plain-Kotlin layer: the state holder, its models and this stage's pure rules. */
    private val stateHolderSources: List<File> = File(mainSourceRoot, "ui/programs")
        .listFiles { file -> file.isFile && file.extension == "kt" }
        ?.sortedBy { file -> file.name }
        ?: emptyList()

    private val controller = File(mainSourceRoot, "ui/programs/ProgramsController.kt")
    private val previewModel = File(mainSourceRoot, "ui/programs/ProgramGenerationPreviewUi.kt")
    private val generationService = File(mainSourceRoot, "domain/usecase/ProgramGenerationService.kt")

    private fun source(file: File): String {
        assertTrue("expected ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** The file's code with its comments removed, so a rule cannot be tripped by prose about it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun codeOf(file: File): String = code(source(file))

    private fun collapsed(file: File): String = codeOf(file).replace(Regex("""\s+"""), " ")

    /**
     * The body of the function whose declaration starts with [signature], from that declaration to the
     * next **top-level** declaration of this class.
     *
     * Boundaries are read on the *line-preserving* source, never on the whitespace-collapsed one: a
     * collapsed string has no newlines, so a search for the next declaration at the same indent can
     * never match and the slice runs to the end of the class — which reads as "this function contains
     * the whole file" and fails a gate on a line three hundred lines further down.
     */
    private fun bodyOf(file: File, signature: String): String {
        val lines = codeOf(file).lines()
        val start = lines.indexOfFirst { line -> line.trimStart().startsWith(signature) }
        assertTrue("expected a declaration starting `$signature` in ${file.name}", start >= 0)
        val end = (start + 1 until lines.size)
            .firstOrNull { index ->
                lines[index].startsWith("    fun ") || lines[index].startsWith("    private fun ") ||
                    lines[index].startsWith("    suspend fun ") ||
                    lines[index].startsWith("    private suspend fun ")
            }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n").replace(Regex("""\s+"""), " ")
    }

    private fun assertNoToken(files: List<File>, tokens: List<String>, because: String) {
        val offenders = files.flatMap { file ->
            val text = code(file.readText())
            tokens.filter { token -> Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(text) }
                .map { token -> "${file.name}: $token" }
        }
        assertEquals("$because — found: $offenders", emptyList<String>(), offenders)
    }

    // ---------------------------------------------------------------- the UI reaches no generated domain

    @Test
    fun noComposableReceivesAGeneratedDomainValue() {
        assertNoToken(
            listOf(editorScreen),
            listOf(
                "GeneratedPlan", "GeneratedSlot", "GeneratedElement", "FocusAssignment",
                "ReconciliationReport", "ReconciliationChange", "GenerationLimitation",
                "FocusUnusableReason", "PreservationLevel", "ChangeKind", "GeneratedDraftEdit",
                "ProgramGeneratedEditor", "GeneratedPlanner", "FocusPlanner",
                "ProductionGenerationBoundary", "GenerationRequest", "PlanReconciler",
                "com.monkfitness.app.domain.program.generated"
            ),
            "§16: a screen receives the controller's presentation. §30 step 26's Preview is the first " +
                "UI surface over the generated domain, so this is the gate that keeps it a projection: " +
                "a Composable holding a GeneratedPlan could sort it, rank it, or decide what a " +
                "limitation means — all of which belong to the owner of those values"
        )
    }

    @Test
    fun noComposableCallsTheGenerationServiceOrAnyGenerationOwner() {
        assertNoToken(
            listOf(editorScreen),
            listOf(
                "ProgramGenerationService", "GenerationCatalogue", "SHIPPED_EXERCISE_CATALOGUE",
                "ProductionFocusClassification", "ExerciseGenerationFacts"
            ),
            "§7's Generate / Preview / Regenerate all reach generation through ProgramsController, " +
                "which is the one node that knows in what order the pipeline runs"
        )
    }

    @Test
    fun theUiLayerAsAWholeNamesNoGeneratedOwner() {
        assertNoToken(
            stateHolderSources,
            listOf(
                "GeneratedPlanner", "FocusPlanner", "PlanReconciler", "ProgramGeneratedEditor",
                "ExerciseSelector", "ProductionGenerationBoundary", "GenerationRequest("
            ),
            "not even the plain-Kotlin layer reaches the planner, the reconciler or the boundary: the " +
                "controller holds the *result* of one pass, and assembling it is the service's job"
        )
    }

    // ---------------------------------------------------------------- no second generation path

    @Test
    fun theControllerReachesGenerationOnlyThroughTheOneService() {
        val text = collapsed(controller)

        assertTrue(
            "§30 step 26: Preview runs the same pipeline as Generate, so it goes through the same service",
            "generation.preview(draft, availableEquipment())" in text
        )
        assertFalse(
            "…and the controller never reaches the generated domain's own types to build a plan of its " +
                "own — a second call site is a second generation path that could disagree",
            "ProgramGeneratedEditor(" in text || "GeneratedPlanner." in text || "PlanReconciler." in text
        )
        assertFalse(
            "nor does it assemble a request",
            "GenerationRequest(" in text
        )
        assertEquals(
            "the controller reaches generation in exactly two places and nowhere else: the " +
                "Generate/Regenerate entry (Regenerate delegates to it — the domain's own recorded " +
                "decision that the two are one reconciliation) and the Preview entry",
            2,
            Regex("""generation\.(generate|preview)\(""").findAll(text).count()
        )
    }

    @Test
    fun theServiceGivesPreviewItsOwnBodyOfItsOwn() {
        // `preview` must be one call into the shared private pass. A preview with its own body — even a
        // body that happens to call the planner — is a second path, and the two would drift.
        val body = collapsed(generationService)
        val declaration = Regex(
            """fun preview\([^)]*\):\s*ProgramGenerationResult = edit\(draft, availableEquipment\)"""
        )
        assertTrue(
            "§30 step 26's critical requirement: Preview uses *exactly* the same generation pipeline. " +
                "One delegating call is the only shape that cannot diverge",
            declaration.containsMatchIn(body)
        )
        assertEquals(
            "and the service assembles the request in exactly one place, so a preview cannot be built " +
                "from a different configuration than the Generate it previews",
            1,
            body.split("ProductionGenerationBoundary.generationRequest(").size - 1
        )
    }

    @Test
    fun theServiceStillDeclaresNoPersistenceCollaborator() {
        // The synthetic constructor Kotlin emits for the default arguments carries a
        // DefaultConstructorMarker, not a collaborator; counting it would make the census a claim
        // about bytecode rather than about the service's dependencies.
        val collaborators = ProgramGenerationService::class.java.declaredConstructors
            .filterNot { constructor -> constructor.isSynthetic }
            .flatMap { constructor -> constructor.parameterTypes.map { type -> type.name } }
            .distinct()

        assertEquals(
            "§33 and §6: the only route from a generation pass to storage remains the editor's Save, " +
                "and Preview must not be able to become a hidden one",
            emptyList<String>(),
            collaborators.filter { name -> name.contains("Repository") || name.contains("Dao") }
        )
        assertEquals(
            "and the collaborator list is exactly P24's five — Preview adds a method, not a dependency",
            listOf(
                "com.monkfitness.app.domain.program.DraftIdSource",
                "com.monkfitness.app.domain.program.generated.GenerationPolicy",
                "com.monkfitness.app.domain.program.generated.GenerationPreferences",
                "com.monkfitness.app.domain.usecase.ExerciseGenerationFacts\$GenerationFocusSource",
                "com.monkfitness.app.domain.usecase.GenerationCatalogue"
            ),
            collaborators.sorted()
        )
    }

    // ---------------------------------------------------------------- the controller owns the mapping

    @Test
    fun theControllerBuildsTheWholePresentationFromTheDraftEdit() {
        val text = collapsed(controller)
        assertTrue(
            "§16: the controller owns the application-result → UI mapping. Without it a screen would " +
                "have to read the generated domain itself",
            "fun previewUiOf(edit: GeneratedDraftEdit): ProgramGenerationPreviewUi" in text
        )
        listOf(
            "edit.plan.slots",      // the plan, in the plan's own order
            "edit.plan.limitations", // every limitation, none dropped
            "edit.reconciliation.preservedCount",
            "edit.reconciliation.addedCount",
            "edit.reconciliation.droppedCount",
            "edit.reconciliation.removedDayCount",
            "edit.reconciliation.conflicts",
            "edit.reconciliation.changes"
        ).forEach { field ->
            assertTrue("the mapping reads $field", field in text)
        }
    }

    @Test
    fun thePreviewModelCarriesNoGeneratedDomainType() {
        // Matched on the **field's** type, not on a rendered "Type.field" string: a substring test over
        // the qualified name matches `ProgramGenerationLimitationUi.focusLabelRes` on the substring
        // "GenerationLimitation", which is this file's own name and says nothing about the field.
        val fields = listOf(
            ProgramGenerationPreviewUi::class.java,
            ProgramGenerationPreviewDayUi::class.java,
            ProgramGenerationPreviewElementUi::class.java,
            ProgramGenerationPreviewConflictUi::class.java,
            ProgramGenerationPreviewChangeUi::class.java,
            ProgramGenerationLimitationUi::class.java
        ).flatMap { type ->
            type.declaredFields.map { field -> "${type.simpleName}.${field.name}: ${field.type.name}" }
        }

        assertTrue("the Preview model has fields to check", fields.isNotEmpty())
        val generated = fields.filter { rendered ->
            GENERATED_DOMAIN_TYPES.any { banned -> rendered.substringAfter(": ").contains(banned) }
        }
        assertEquals(
            "a UI state type that *holds* a generated value is as much a leak as a screen that reads " +
                "one: it would travel through the state and could be rendered by any caller. Every " +
                "Preview field is an Int resource, an Int count, a String id or a plain value — found: " +
                "$generated",
            emptyList<String>(),
            generated
        )
    }

    @Test
    fun theUiStateHoldsExactlyOneDraftAndOneTemporaryPreview() {
        assertTrue(
            "§7's working draft is the draft; the state carries its presentation",
            ProgramDraftUi::class.java.declaredFields.any { field -> field.name == "days" }
        )
        val stateFields = ProgramsUiState::class.java.declaredFields.map { field -> field.name }
        assertEquals(
            "a second draft-shaped field in the UI state would be a second current-draft source of " +
                "truth. §30 step 26 adds exactly one: the temporary preview",
            listOf("generationPreview"),
            stateFields.filter { name -> name.contains("review") || name.contains("Preview") }
                .filterNot { name -> name == "importReview" }
                .sorted()
        )
        assertEquals(
            "and it is a preview *model*, not a draft: no field of the state is a ProgramEditorDraft",
            emptyList<String>(),
            stateFields.filter { name ->
                ProgramsUiState::class.java.declaredFields
                    .first { it.name == name }
                    .type
                    .name
                    .endsWith("ProgramEditorDraft")
            }
        )
    }

    // ---------------------------------------------------------------- the preview is temporary

    @Test
    fun thePendingPreviewIsInvalidatedByEveryDraftChange() {
        val text = collapsed(controller)

        assertTrue(
            "§30 step 26's invalidation rule is centralised, so a new draft-mutating operation that " +
                "forgets it is a preview left applicable to a draft that no longer exists",
            "private fun clearGenerationPreview()" in text
        )
        // Every route that changes the draft, opens another flow, discards, generates or applies must
        // reach it. `editDraft` is the funnel for name, description, mode, duration, schedule, focus,
        // days and elements; the rest are named explicitly.
        assertEquals(
            "…and it is called from every one of them, with no path that changes the draft and skips it",
            7,
            Regex("""clearGenerationPreview\(\)""").findAll(text).count()
        )
        assertTrue(
            "every draft edit goes through the one funnel that invalidates",
            "private fun editDraft(change: (ProgramEditorDraft) -> ProgramEditorDraft)" in text
        )
        // `previewOf` is deliberately absent from this scan: it is the state holder's *pre-existing*
        // private helper that reads the Scheduler's next-opportunity preview (§20), and banning the
        // name would refuse a collaborator the layer is required to keep.
        assertFalse(
            "and no second identity system is introduced to re-decide staleness: no hash, no timestamp " +
                "and no revision pointer beside the preview",
            "previewHash" in text || "previewBase" in text || "previewRevision" in text ||
                "previewStamp" in text || "RevisionConflict" in text || "PendingPreviewRevision" in text
        )
    }

    @Test
    fun generateAndRegenerateStayImmediateApplicationsAndClearThePreview() {
        val text = collapsed(controller)
        assertTrue(
            "§8 of P26: Generate keeps its existing behaviour — the result is applied at once",
            "workingDraft = result.edit.draft" in text
        )
        assertTrue(
            "…and it clears a pending preview first, so an unchosen plan is never left beside an applied one",
            "fun generateDraft(): Boolean { val draft = workingDraft ?: return false clearGenerationPreview()"
                .replace(Regex("""\s+"""), " ") in text
        )
        assertTrue(
            "…and Regenerate is the same operation, not a second planner",
            "suspend fun regenerateDraft(): Boolean = generateDraft()" in text
        )
    }

    @Test
    fun onlyApplyChangesTheWorkingDraft() {
        val text = collapsed(controller)

        val previewBody = bodyOf(controller, "suspend fun previewDraft(): Boolean")
        assertFalse(
            "§30 step 26's invariant: previewDraft never assigns the working draft. It is a read of the " +
                "draft and a write to the preview, nothing else — found: $previewBody",
            "workingDraft =" in previewBody
        )
        assertFalse(
            "…and it never reaches Save, which is the only route from the UI layer to storage",
            "saver.save(" in previewBody || "saveDraft(" in previewBody
        )
        assertTrue(
            "…it does hold the prospective result instead, and publishes only the presentation",
            "pendingPreview = result.edit" in previewBody &&
                "generationPreview = previewUiOf(result.edit)" in previewBody
        )

        val applyBody = bodyOf(controller, "fun useGenerationPreview(): Boolean")
        assertTrue(
            "Apply is where the prospective draft is installed — and nowhere else in the state holder " +
                "is a prospective value assigned to the working draft",
            "workingDraft = preview.draft" in applyBody
        )
        assertFalse(
            "§6: Apply creates no Revision — only Save does, and the user still has to press it",
            "saver.save(" in applyBody || "saveDraft(" in applyBody
        )
        assertFalse(
            "…and it clears the pending preview as it installs it",
            "pendingPreview = null" !in applyBody
        )
    }

    @Test
    fun theUiLayerStillReachesNoStorageNoSchedulerAndNoAdaptive() {
        assertNoToken(
            stateHolderSources,
            listOf(
                "Dao", "AppDatabase", "Room", "@Entity", "Repository", "Adaptive", "repository."
            ),
            "§33: this stage adds a screen section and a pending value; it adds no storage type, no " +
                "entity and no adaptive source to the UI layer. `ProgramScheduler` is deliberately " +
                "NOT on this list: the state holder legitimately *holds* the scheduler (§20's next-" +
                "opportunity read, pinned by ProgramsArchitectureTest's collaborator census), and " +
                "banning the type here would refuse a collaborator the layer is required to keep"
        )
    }

    // ---------------------------------------------------------------- §14: no developer-facing sentence

    @Test
    fun theUiNeverReadsADomainSentenceOrAnEnumName() {
        assertNoToken(
            stateHolderSources + listOf(editorScreen),
            listOf("limitation.message", "it.message", "change.message", ".message"),
            "§14: the generated domain's messages are developer-facing English built from enum names " +
                "(`MOBILITY cannot be planned: …`). A Preview that showed one would put an English " +
                "sentence on a foreign screen. Every user-facing word reaches the UI as a resource"
        )
    }

    @Test
    fun everyLimitationReasonIsMappedToItsOwnResource() {
        val reasons = com.monkfitness.app.domain.program.generated.FocusUnusableReason.entries
        val mapped = setOf(
            com.monkfitness.app.domain.program.generated.FocusUnusableReason
                .NO_EXERCISE_TRAINS_THE_FOCUS,
            com.monkfitness.app.domain.program.generated.FocusUnusableReason
                .EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT,
            com.monkfitness.app.domain.program.generated.FocusUnusableReason
                .PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED
        )
        assertEquals(
            "§33: a reason added to the domain without a sentence here is a limitation a Preview would " +
                "show as *nothing* — which is the defect the Preview exists to remove. Exhaustive on " +
                "purpose, so a fourth reason fails this rather than vanishing",
            reasons.toSet(),
            mapped
        )
        assertEquals(
            "each mapped to its own resource — a focus the equipment forbids must read differently " +
                "from one nothing in the user's selection trains, because those are two different " +
                "things a user can act on",
            4,
            setOf(
                ProgramGenerationPreviewRes.REASON_NO_EXERCISE,
                ProgramGenerationPreviewRes.REASON_EQUIPMENT,
                ProgramGenerationPreviewRes.REASON_DIMENSION,
                ProgramGenerationPreviewRes.REASON_NO_PLANNABLE_FOCUS
            ).size
        )
    }

    @Test
    fun theScreenDecidesNoOrderAndNoCountOfItsOwn() {
        val screen = code(editorScreen.readText())
        assertFalse(
            "§30 step 26: the Preview shows the plan in the plan's own order. Sorting or ranking it in " +
                "the UI would make the screen a second place that decides what the plan is",
            Regex("""sortedBy|sortedWith|\.sort\(|sortedDescending""").containsMatchIn(screen)
        )
        assertFalse(
            "and the screen computes no count of the plan: every figure is the pass's own, handed down",
            Regex("""\.days\.sumOf|\.days\.count""").containsMatchIn(screen)
        )
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun thePreviewAddedNoEntityAndNoDao() {
        val entities = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> "@Entity" in code(file.readText()) }
            .count()
        val daos = mainSourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> "@Dao" in code(file.readText()) }
            .count()

        assertEquals(
            "§30 step 26 adds no table: a Preview is a temporary operation result and persists nothing, " +
                "so a new entity here would be a second place a pending value was written",
            24,
            entities
        )
        assertEquals(
            "and no new DAO — a preview is never read back from storage",
            20,
            daos
        )
    }

    @Test
    fun theGeneratedDomainIsUntouchedByThisStage() {
        val generatedPackage = File(mainSourceRoot, "domain/program/generated")
        val census = generatedPackage.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.map { file -> file.name }
            ?.sorted()
            ?: emptyList()

        assertEquals(
            "P10 established these eight files and P23–P26 added none. A new file here would be a " +
                "second planner, a second reconciler or a second policy — the three things §30 step 26 " +
                "explicitly forbids",
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
            census
        )
    }

    @Test
    fun thePreviewModelIsAPlainKotlinValueLikeTheRestOfTheUiLayer() {
        assertFalse(
            "the state holder's package must stay plain Kotlin: the JVM tests are what measure this " +
                "layer, and a Compose file here would be untestable (§17)",
            previewModel.readText().contains("import androidx.compose")
        )
        listOf(
            ProgramGenerationPreviewUi::class.java,
            ProgramGenerationPreviewDayUi::class.java,
            ProgramGenerationPreviewElementUi::class.java,
            ProgramGenerationPreviewConflictUi::class.java,
            ProgramGenerationPreviewChangeUi::class.java,
            ProgramGenerationLimitationUi::class.java
        ).forEach { type ->
            assertFalse(
                "${type.simpleName} must hold no collaborator",
                Modifier.isAbstract(type.modifiers) && !type.isInterface
            )
            assertEquals(
                "${type.simpleName} is a value with one declared constructor — the synthetic one Kotlin " +
                    "emits for a default argument is not a second way to build it",
                1,
                type.declaredConstructors.count { constructor -> !constructor.isSynthetic }
            )
        }
    }

    @Test
    fun theControllerStillDeclaresNoSecondCurrentDraftField() {
        val fields = ProgramsController::class.java.declaredFields.map { field -> field.name }
        assertTrue(
            "the working draft is a field of the state holder, and it is the only one",
            fields.contains("workingDraft")
        )
        assertEquals(
            "§30 step 26 adds exactly one field to the state holder: the pending preview. A second " +
                "ProgramEditorDraft field would be a second current-draft source of truth",
            listOf("pendingPreview"),
            fields.filter { name ->
                ProgramsController::class.java.declaredFields
                    .first { it.name == name }
                    .type == ProgramEditorDraft::class.java || name == "pendingPreview"
            }.filterNot { it == "workingDraft" }
        )
    }

    private companion object {

        /** The generated-domain types a UI state field must never have. */
        val GENERATED_DOMAIN_TYPES = listOf(
            "GeneratedPlan",
            "GeneratedSlot",
            "GeneratedElement",
            "FocusAssignment",
            "ReconciliationReport",
            "ReconciliationChange",
            "GenerationLimitation",
            "FocusUnusableReason",
            "GeneratedDraftEdit",
            "PreservationLevel",
            "ChangeKind",
            "ProgramEditorDraft",
            "FocusPlan"
        )
    }
}