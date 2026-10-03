package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * §30 step 26's **Preview** at the application boundary — the claim that a preview is the *same*
 * pass as a generate, not a second planner path that happens to agree with it.
 *
 * The controller-level isolation (`workingDraft` unchanged, no Revision, no row written) is measured
 * in `ProgramsGenerationPreviewTest` over the real engine. What belongs here is narrower and is the
 * one thing the controller cannot prove about itself: that [ProgramGenerationService.preview] reaches
 * exactly the same boundary, request, planner, editor and reconciler that [ProgramGenerationService.generate]
 * reaches, and that it hands back the same three values rather than a reduced, filtered or
 * re-planned subset of them.
 *
 * A preview that answered with *only* a plan would make the Preview screen the second place that
 * decides what "the generated plan" means, and a preview that ran its own pipeline could disagree
 * with the Generate the user is being shown a preview of. Both are silent failures: nothing crashes,
 * and the numbers simply differ.
 */
class ProgramGenerationPreviewServiceTest {

    private val ids = SequentialIds()

    private fun serviceOver(
        exercises: List<com.monkfitness.app.data.model.Exercise>,
        focusSource: ExerciseGenerationFacts.GenerationFocusSource = ProductionFocusClassification
    ): ProgramGenerationService = ProgramGenerationService(
        catalogue = GenerationCatalogue { source ->
            ProductionGenerationBoundary.catalogueOf(exercises, source)
        },
        focusSource = focusSource,
        ids = DraftIdSource { ids.newId() },
        context = GenerationContextSource { GenerationPreferences.NONE }
    )

    /**
     * The service as the composition root wires it.
     *
     * Each call gets its **own** [SequentialIds]: a reconciliation carries the draft identities the
     * reconciler minted, so two passes sharing one counter would differ in identity alone and the
     * "same pass" comparison below would report a disagreement that is really just the counter.
     */
    private fun realService(): ProgramGenerationService {
        val counter = SequentialIds()
        return ProgramGenerationService(
            catalogue = SHIPPED_EXERCISE_CATALOGUE,
            focusSource = ProductionFocusClassification,
            ids = DraftIdSource { counter.newId() },
            context = GenerationContextSource { GenerationPreferences.NONE }
        )
    }

    private fun draft(focus: FocusPlan = FocusPlan.Balanced) = ProgramEditorDraft(
        name = "Generated",
        mode = ProgramMode.GENERATED,
        duration = ProgramDuration.Indefinite,
        schedule = ProgramSchedule.FlexiblePerWeek(3),
        focus = focus
    )

    // ---------------------------------------------------------------- the same pass, not a second one

    @Test
    fun aPreviewIsTheGeneratePassOverTheSameInput() = runBlocking {
            val start = draft(focus = FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)))

            val generated = realService().generate(start, emptySet()) as ProgramGenerationResult.Generated
            val previewed = realService().preview(start, emptySet()) as ProgramGenerationResult.Generated

            assertEquals(
                "the same request reaches the planner from both entries, so the plan a user is shown a " +
                    "preview of is the plan Generate would produce",
                generated.edit.plan,
                previewed.edit.plan
            )
            assertEquals(
                "and the reconciliation is the same too — a preview cannot report a different set of " +
                    "changes than the pass it previews",
                generated.edit.reconciliation,
                previewed.edit.reconciliation
            )
            assertEquals(
                "and the prospective draft is the same value, down to the identities the reconciler minted",
                generated.edit.draft,
                previewed.edit.draft
            )
    }

    @Test
    fun aPreviewCarriesAllThreeResultsAndNotJustTheDraft() = runBlocking {
            // This is the whole stage: P24 already produced `plan` and `reconciliation` and the controller
            // used only `draft`. A preview that dropped either would leave nothing to show, which is the
            // defect the Preview exists to remove.
            val edit = (realService().preview(draft(), emptySet())
                as ProgramGenerationResult.Generated).edit

            assertTrue("the plan reaches the caller", edit.plan.slots.isNotEmpty())
            assertTrue(
                "the reconciliation reaches the caller — an empty draft has nothing to reconcile, so this " +
                    "is the figure that proves the pass really reconciled rather than returning a draft",
                edit.reconciliation.changes.isNotEmpty()
            )
            assertEquals(
                "every planned day produced its own reconciliation entry, so the counts a Preview shows " +
                    "account for the whole plan",
                edit.plan.slots.sumOf { slot -> slot.elements.size },
                edit.reconciliation.changes.count {
                    it.kind == com.monkfitness.app.domain.program.generated.ChangeKind.ADDED
                } + edit.reconciliation.preservedCount
            )
    }

    @Test
    fun aPreviewPreservesTheUsersOwnContentExactlyAsGenerateDoes() = runBlocking {
            val pinned = ProgramExercise(
                programExerciseId = ProgramExerciseId("element-pinned"),
                exerciseId = "dead_bug",
                prescription = RepPrescription.uniform(3, 12),
                origin = ProgramExerciseOrigin.USER_AUTHORED,
                isPinned = true
            )
            val start = draft().copy(
                days = listOf(
                    ProgramDay(
                        programDayId = ProgramDayId("day-1"),
                        position = 1,
                        type = ProgramDayType.TRAINING,
                        exercises = listOf(pinned)
                    )
                )
            )

            val edit = (realService().preview(start, emptySet())
                as ProgramGenerationResult.Generated).edit

            val firstDay = edit.draft.days.first { day -> day.position == 1 }
            assertTrue(
                "§7's precedence applies to a preview exactly as it does to a generate: the pin is kept",
                firstDay.exercises.any { it.programExerciseId == pinned.programExerciseId }
            )
            assertTrue(
                "…and the disagreement is *reported* rather than acted on, which is what makes the " +
                    "conflicts list non-empty for a screen to show",
                edit.reconciliation.conflicts.isNotEmpty()
            )
    }

    @Test
    fun aPreviewMutatesNothingItWasHanded() = runBlocking {
            val start = draft(focus = FocusPlan.focused(listOf(Focus.PUSH)))
            val snapshot = start.copy(days = start.days)

            val edit = (realService().preview(start, emptySet())
                as ProgramGenerationResult.Generated).edit

            assertEquals(
                "the draft the caller passed in is unchanged — Preview hands back a prospective value " +
                    "and never installs it",
                snapshot,
                start
            )
            assertNotSame(
                "…and what came back is a different draft, not the same one echoed",
                start,
                edit.draft
            )
            assertEquals(
                "which is a Generated draft holding the plan's days, so the pass really planned something",
                ProgramMode.GENERATED,
                edit.draft.mode
            )
            assertTrue(edit.draft.days.isNotEmpty())
    }

    // ---------------------------------------------------------------- the same refusals and failures

    @Test
    fun aPreviewThatStatesNothingIsRefusedRatherThanPreviewedAsEmpty() = runBlocking {
            val result = serviceOver(
                exercises = WorkoutGenerator().getExerciseLibrary(),
                focusSource = { null }
            ).preview(draft(), emptySet())

            assertTrue(
                "§33: the absence of a plan is a typed refusal, never an empty preview a user could read " +
                    "as \"this is what your plan would be\"",
                result is ProgramGenerationResult.Refused
            )
            assertTrue(
                "and it is the same refusal Generate gives",
                (result as ProgramGenerationResult.Refused).reason is
                    ProgramGenerationRefusal.NoExerciseStatesItsFocus
            )
    }

    @Test
    fun aConfigurationNothingCanServeIsRefusedAndTheLimitationsAreCarried() = runBlocking {
            val pullOnly = listOf(
                com.monkfitness.app.data.model.Exercise(
                    id = "pullup_standard",
                    familyId = "family_pullup_standard",
                    animationId = "anim_pullup_standard",
                    nameRes = 0,
                    descriptionRes = 0,
                    techniqueRes = 0,
                    imageRes = null,
                    sets = 3,
                    reps = 10,
                    category = com.monkfitness.app.data.model.ExerciseCategory.STRENGTH,
                    subCategory = com.monkfitness.app.data.model.ExerciseSubCategory.FULL_BODY,
                    requiredEquipment = setOf(com.monkfitness.app.data.model.Equipment.BAR)
                )
            )
            val service = serviceOver(pullOnly, focusSource = { id -> setOf(Focus.PULL) })

            val result = service.preview(
                draft(focus = FocusPlan.focused(listOf(Focus.PULL))),
                emptySet()
            )

            assertTrue("nothing is performable, so there is nothing to preview", result is ProgramGenerationResult.Refused)
            val refusal = (result as ProgramGenerationResult.Refused).reason
            assertTrue(
                "and the refusal carries the planner's own reasons, so a Preview screen is given the fact " +
                    "it must show rather than a bare \"unavailable\": $refusal",
                refusal is ProgramGenerationRefusal.NothingPlannable &&
                    refusal.limitations.isNotEmpty()
            )
    }

    @Test
    fun aFailureIsSurfacedAndNeverBecomesAPreview() = runBlocking {
            val exploding = ProgramGenerationService(
                catalogue = GenerationCatalogue { throw IllegalStateException("planted: the catalogue could not be read") },
                focusSource = ProductionFocusClassification,
                ids = DraftIdSource { ids.newId() },
                context = GenerationContextSource { GenerationPreferences.NONE }
            )

            val result = exploding.preview(draft(), emptySet())

            assertTrue(
                "§33: a failure is never turned into an empty preview",
                result is ProgramGenerationResult.Failed
            )
            assertTrue(
                "and the cause is carried, not swallowed",
                (result as ProgramGenerationResult.Failed).cause.message!!.contains("planted")
            )
    }

    // ---------------------------------------------------------------- no second planner path

    @Test
    fun previewIsOneCallIntoTheSamePrivatePassRatherThanAPipelineOfItsOwn() = runBlocking {
            // The mechanical half of "no second generation path": the public surface of the service has
            // exactly three entry points and all three reach the same private `edit`. A preview that
            // assembled its own request, or called the planner directly, would have to be a fourth method
            // with the boundary's or the planner's name in it.
            // Public only: the shared private `edit` is deliberately an implementation detail, and a gate
            // that counted it would break the next time it is renamed without any rule having changed.
            //
            // P27: the three are `suspend`, so each carries a trailing `Continuation` in its JVM
            // signature and the parameter count is 3, not 2. The filter below selects on the CALLER's
            // arguments — the continuation is excluded, not the count loosened — so a fourth entry point
            // taking a different pair of arguments still cannot slip through this census.
            val continuation = kotlin.coroutines.Continuation::class.java
            val entryPoints = ProgramGenerationService::class.java.declaredMethods
                .filter { method -> java.lang.reflect.Modifier.isPublic(method.modifiers) }
                .filter { method ->
                    method.parameterTypes.firstOrNull() == ProgramEditorDraft::class.java
                }

            assertEquals(
                "the service offers Generate, Regenerate and Preview and nothing else — no `previewPlan`, " +
                    "no `planOnly`, no alternative caller of the planner",
                listOf("generate", "preview", "regenerate"),
                entryPoints.map { method -> method.name }.sorted()
            )
            assertEquals(
                "and each is a SUSPENDING pass taking the draft and the equipment: the context read is a " +
                    "storage read, so an entry point that did not own a coroutine could only have got " +
                    "its facts from somewhere other than the context source. The continuation is the " +
                    "compiler's, and the CALLER's arguments are exactly the draft and the equipment.",
                3,
                entryPoints.count { method ->
                    method.parameterTypes.toList() ==
                        listOf(
                            ProgramEditorDraft::class.java,
                            Set::class.java,
                            continuation
                        )
                }
            )
            assertEquals(
                "and each hands straight to the shared private pass — three delegations, no branch",
                3,
                Regex("""suspend fun (generate|regenerate|preview)\(\s*draft: ProgramEditorDraft,""" +
                    """\s*availableEquipment: Set<Equipment>\s*\): ProgramGenerationResult = """ +
                    """edit\(draft, availableEquipment\)""")
                    .findAll(
                        File("src/main/java/com/monkfitness/app/domain/usecase/ProgramGenerationService.kt")
                            .let { file -> if (file.isFile) file else File("app/$file") }
                            .readText()
                            .replace(Regex("""\s+"""), " ")
                    )
                    .count()
            )
            // The source half of the same claim: the context is read in the one shared pass, and the
            // request is assembled in one place. A Preview that re-read the context, or assembled its
            // own request, would show up as a second `preferencesFor` or a second `generationRequest`.
            val serviceCode = File("src/main/java/com/monkfitness/app/domain/usecase/ProgramGenerationService.kt")
                .let { file -> if (file.isFile) file else File("app/$file") }
                .readText()
            assertEquals(
                "the context is read exactly once in the whole file, so Generate and Preview over the " +
                    "same input state cannot see different facts",
                1,
                Regex("context\\.preferencesFor\\(").findAll(serviceCode).count()
            )
            assertEquals(
                "and the request is assembled in exactly one place, still the boundary's",
                1,
                Regex("ProductionGenerationBoundary\\.generationRequest\\(").findAll(serviceCode).count()
            )
    }

    @Test
    fun theServiceStillDeclaresNoPersistenceCollaborator() = runBlocking {
            // §33 and §6: the only route from a generation pass to storage is `ProgramEditorService.save`.
            // A Preview that quietly reached a repository would be a hidden Save, which is the one thing
            // §30 step 26's invariant forbids.
            // Every declared constructor, because Kotlin emits a synthetic one for the default arguments
            // as well as the primary: a gate reading only `.single()` would pass for the wrong reason the
            // day a second real constructor appeared beside it.
            val collaborators = ProgramGenerationService::class.java.declaredConstructors
                .filterNot { constructor -> constructor.isSynthetic }
                .flatMap { constructor -> constructor.parameterTypes.map { type -> type.name } }
                .distinct()

            assertEquals(
                "the same collaborators P27 declares: the catalogue, the focus source, the id source, " +
                    "the context port and the policy",
                listOf(
                    GenerationCatalogue::class.java.name,
                    ExerciseGenerationFacts.GenerationFocusSource::class.java.name,
                    DraftIdSource::class.java.name,
                    GenerationContextSource::class.java.name,
                    com.monkfitness.app.domain.program.generated.GenerationPolicy::class.java.name
                ).sorted(),
                collaborators.sorted()
            )
            assertEquals(
                "and no repository type is reachable from the service at all — Preview must not be able " +
                    "to become a hidden Save. P27's context is a PORT: the service can learn what a " +
                    "source chose to state about history and can learn nothing else, so it holds no " +
                    "store to write through either.",
                emptyList<String>(),
                collaborators.filter { name ->
                    name.contains("Repository") ||
                        name.contains("Dao") ||
                        name.contains("AppDatabase") ||
                        name.contains("Entity") ||
                        name.contains("Room")
                }
            )
            assertFalse(
                "and the narrow history port itself is not a service collaborator — it belongs to the " +
                    "composition root and to the context source, so a pass cannot reach a session read " +
                    "directly even to read one",
                collaborators.contains(GenerationSessionHistory::class.java.name)
            )
    }
}