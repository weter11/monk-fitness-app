package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.TrainingDomain
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.generated.GeneratedPlanner
import com.monkfitness.app.domain.program.generated.GenerationPolicy
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource
import com.monkfitness.app.domain.usecase.ProductionGenerationBoundary.ProductionGenerationCatalogue
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production generation boundary as a **function of the catalogue and the stated facts**, read
 * against the real shipped catalogue wherever a claim is about the real one. The boundary maps and
 * forwards only — it never defaults, filters or invents what the source did not state.
 */
class ProductionGenerationBoundaryTest {

    private val pushups = exercise(
        id = "pushups",
        familyId = "pushups",
        category = ExerciseCategory.STRENGTH,
        subCategory = ExerciseSubCategory.SHOULDERS,
        requiredEquipment = emptySet(),
        isTimerBased = false
    )

    private val pullups = exercise(
        id = "pullups",
        familyId = "pullups",
        category = ExerciseCategory.STRENGTH,
        subCategory = ExerciseSubCategory.SHOULDERS,
        requiredEquipment = setOf(Equipment.BAR),
        isTimerBased = false
    )

    private val hang = exercise(
        id = "hang",
        familyId = "pullups",
        category = ExerciseCategory.POSTURE,
        subCategory = ExerciseSubCategory.SHOULDERS,
        requiredEquipment = setOf(Equipment.BAR),
        isTimerBased = true
    )

    /** A source that classifies exactly what it is told to, and nothing else. */
    private fun sourceOf(vararg stated: Pair<String, Set<Focus>>): GenerationFocusSource {
        val statedIds = stated.toMap()
        return GenerationFocusSource { exerciseId -> statedIds[exerciseId] }
    }

    private fun exercise(
        id: String,
        familyId: String,
        category: ExerciseCategory,
        subCategory: ExerciseSubCategory,
        requiredEquipment: Set<Equipment>,
        isTimerBased: Boolean
    ) = Exercise(
        id = id,
        familyId = familyId,
        animationId = "anim_$id",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = 3,
        reps = 10,
        isTimerBased = isTimerBased,
        category = category,
        subCategory = subCategory,
        requiredEquipment = requiredEquipment
    )

    private val threeDaySchedule = ProgramSchedule.FixedWeekdays(
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    )

    @Test
    fun aProductionExerciseBecomesACandidateCarryingItsOwnIdentityAndMetadata() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups),
            sourceOf("pushups" to setOf(Focus.PUSH))
        )

        val candidate = catalogue.candidateFor("pushups")
        assertEquals("pushups", candidate!!.exerciseId)
        assertEquals("pushups", candidate.familyId)
        assertEquals(TrainingDomain.STRENGTH, candidate.metadata.trainingDomain)
        assertEquals(BodyRegion.SHOULDERS, candidate.metadata.bodyRegion)
        assertEquals(setOf(Focus.PUSH), candidate.focuses)
        assertEquals(PrescriptionDimension.REP_BASED, candidate.dimension)
        assertTrue(candidate.trains(Focus.PUSH))
        assertFalse(candidate.trains(Focus.PULL))
    }

    @Test
    fun theMetadataIsTheCataloguesOwnAndNotARestatement() {
        // The category→domain and sub-category→region mappings are the app's and are exhaustive: a
        // flexibility exercise lands in FLEXIBILITY, not in STRENGTH by accident.
        val catCamel = exercise(
            "cat_cow", "cat_cow", ExerciseCategory.MOBILITY, ExerciseSubCategory.SPINE,
            emptySet(), isTimerBased = true
        )

        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(catCamel),
            sourceOf("cat_cow" to setOf(Focus.MOBILITY))
        )
        val candidate = catalogue.candidateFor("cat_cow")!!

        assertEquals(TrainingDomain.FLEXIBILITY, candidate.metadata.trainingDomain)
        assertEquals(BodyRegion.SPINE, candidate.metadata.bodyRegion)
        assertEquals(PrescriptionDimension.TIME_BASED, candidate.dimension)
    }

    @Test
    fun thePrescriptionDimensionIsTheCataloguesOwnAndHasNoThirdCase() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, hang),
            sourceOf("pushups" to setOf(Focus.PUSH), "hang" to setOf(Focus.PULL))
        )

        // A timed exercise is prescribed in seconds and every other one in repetitions — the rule the
        // plan editor applies when a user adds an exercise by hand.
        assertEquals(PrescriptionDimension.TIME_BASED, catalogue.candidateFor("hang")!!.dimension)
        assertEquals(PrescriptionDimension.REP_BASED, catalogue.candidateFor("pushups")!!.dimension)
        // And the boundary never produces a dimension the domain has no prescription for.
        val produced = catalogue.candidates.map { it.dimension }.toSet()
        assertEquals(
            setOf(PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED),
            produced
        )
    }

    @Test
    fun requiredEquipmentIsPassedThroughVerbatimAndNeverIncludesNone() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH), "pullups" to setOf(Focus.PULL))
        )

        assertEquals(emptySet<Equipment>(), catalogue.candidateFor("pushups")!!.requiredEquipment)
        assertEquals(setOf(Equipment.BAR), catalogue.candidateFor("pullups")!!.requiredEquipment)
        assertTrue(
            "no candidate requires the app's \"nothing\" token",
            catalogue.candidates.none { Equipment.NONE in it.requiredEquipment }
        )
    }

    @Test
    fun focusMembershipIsStatedByTheCallerAndEveryFocusOfItReaches() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups),
            sourceOf("pushups" to setOf(Focus.PUSH, Focus.CORE, Focus.MOBILITY))
        )

        val candidate = catalogue.candidateFor("pushups")!!
        assertEquals(setOf(Focus.PUSH, Focus.CORE, Focus.MOBILITY), candidate.focuses)
        // Held in canonical order whichever order the caller stated them in.
        assertEquals(
            listOf(Focus.PUSH, Focus.CORE, Focus.MOBILITY),
            candidate.focusesInOrder
        )
        assertEquals(
            listOf(Focus.PUSH, Focus.CORE, Focus.MOBILITY),
            ProductionGenerationBoundary.catalogueOf(
                listOf(pushups),
                sourceOf("pushups" to setOf(Focus.MOBILITY, Focus.PUSH, Focus.CORE))
            ).candidateFor("pushups")!!.focusesInOrder
        )
    }

    @Test
    fun anExerciseTheSourceDoesNotStateIsReportedAsUnclassifiedAndIsNotACandidate() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH))
        )

        assertNull(catalogue.candidateFor("pullups"))
        assertEquals(listOf("pullups"), catalogue.unclassifiedExerciseIds)
        assertTrue(catalogue.contains("pullups"))
        assertEquals(1, catalogue.candidates.size)
    }

    @Test
    fun anEmptyFocusSetIsARefusalAndNotAnEveryFocusExercise() {
        // The boundary refuses to manufacture a candidate, and refuses to substitute the whole
        // vocabulary for the missing answer.
        val thrown = runCatching {
            ProductionGenerationBoundary.catalogueOf(listOf(pushups), sourceOf("pushups" to emptySet()))
        }.exceptionOrNull()

        assertTrue(
            "a candidate that states no focus must be refused, not admitted: $thrown",
            thrown is IllegalArgumentException
        )
        assertTrue(
            "and the refusal must name the exercise: $thrown",
            thrown!!.message.orEmpty().contains("pushups")
        )
    }

    @Test
    fun aSourceThatStatesNothingClassifiesNothingAndSaysSoForEveryExercise() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups, hang),
            GenerationFocusSource { null }
        )

        assertEquals(emptyList<Any>(), catalogue.candidates)
        assertEquals(listOf("pushups", "pullups", "hang"), catalogue.unclassifiedExerciseIds)
    }

    @Test
    fun theRequestIsTheCatalogueForwardedIntoThePureGenerationInput() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH), "pullups" to setOf(Focus.PULL))
        )

        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = catalogue,
            focus = FocusPlan.Focused(listOf(Focus.PUSH, Focus.PULL)),
            schedule = threeDaySchedule,
            duration = ProgramDuration.FixedDays(28),
            availableEquipment = setOf(Equipment.BAR)
        )

        assertEquals(catalogue.candidates, request.candidates)
        assertEquals(setOf(Equipment.BAR), request.availableEquipment)
        assertEquals(3, request.sessionsPerWeek)
        assertEquals(4, request.cycleWeeks)
        assertEquals(12, request.slotCount)
        // Canonical focus order is the vocabulary's own declaration order, not the caller's: PUSH is
        // declared before PULL.
        assertEquals(
            listOf(Focus.PUSH, Focus.PULL),
            request.plannableFocuses
        )
    }

    @Test
    fun availableEquipmentIsForwardedVerbatimAndTheLegacyEmptyMeansNothingRuleIsNotInherited() {
        // The legacy `isAccessibleWith` reads an empty available set as "no equipment configured, so
        // constrain nothing". Generation's reading is strict and the boundary does not soften it: a
        // user who declared nothing gets only the exercises that require nothing.
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH), "pullups" to setOf(Focus.PULL))
        )

        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = catalogue,
            focus = FocusPlan.Balanced,
            schedule = threeDaySchedule,
            duration = ProgramDuration.FixedDays(28),
            availableEquipment = emptySet()
        )

        assertEquals(emptySet<Equipment>(), request.availableEquipment)
        assertFalse("the bar exercise is not selectable", request.isUsable(catalogue.candidateFor("pullups")!!))
        assertTrue("the bodyweight exercise is", request.isUsable(catalogue.candidateFor("pushups")!!))
        assertFalse(request.isUsable(catalogue.candidateFor("pullups")!!))
    }

    @Test
    fun aCatalogueThatClassifiedNothingIsRefusedRatherThanTurnedIntoAnEmptyPlan() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(listOf(pushups), GenerationFocusSource { null })

        val thrown = runCatching {
            ProductionGenerationBoundary.generationRequest(
                catalogue = catalogue,
                focus = FocusPlan.Balanced,
                schedule = threeDaySchedule,
                duration = ProgramDuration.FixedDays(28),
                availableEquipment = emptySet()
            )
        }.exceptionOrNull()

        assertTrue("the gap is a refusal, not a plan of nothing: $thrown", thrown is IllegalArgumentException)
        assertTrue(
            "and the refusal must name the gap rather than the symptom: $thrown",
            thrown!!.message.orEmpty().contains("unclassified")
        )
    }

    @Test
    fun preferencesAndPolicyReachTheRequestUnchanged() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH), "pullups" to setOf(Focus.PULL))
        )
        val preferences = GenerationPreferences(userPreferredExerciseIds = listOf("pullups"))
        val policy = GenerationPolicy.DEFAULT

        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = catalogue,
            focus = FocusPlan.Balanced,
            schedule = threeDaySchedule,
            duration = ProgramDuration.FixedDays(28),
            availableEquipment = setOf(Equipment.BAR),
            preferences = preferences,
            policy = policy
        )

        assertEquals(preferences, request.preferences)
        assertEquals(policy, request.policy)
    }

    @Test
    fun theMappingIsDeterministicAndPreservesTheCataloguesOwnOrder() {
        val exercises = listOf(pushups, pullups, hang)
        val source = sourceOf(
            "pushups" to setOf(Focus.PUSH),
            "pullups" to setOf(Focus.PULL),
            "hang" to setOf(Focus.PULL, Focus.POSTURE)
        )

        val first = ProductionGenerationBoundary.catalogueOf(exercises, source)
        val second = ProductionGenerationBoundary.catalogueOf(exercises, source)

        assertEquals(first, second)
        assertEquals(listOf("pushups", "pullups", "hang"), first.candidates.map { it.exerciseId })
    }

    @Test
    fun theWholeChainReachesTheGeneratedPlannerWithTheProducedCandidates() {
        val catalogue = ProductionGenerationBoundary.catalogueOf(
            listOf(pushups, pullups),
            sourceOf("pushups" to setOf(Focus.PUSH), "pullups" to setOf(Focus.PULL))
        )

        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = catalogue,
            focus = FocusPlan.Focused(listOf(Focus.PUSH, Focus.PULL)),
            schedule = threeDaySchedule,
            duration = ProgramDuration.FixedDays(28),
            availableEquipment = setOf(Equipment.BAR)
        )
        val plan = GeneratedPlanner.plan(request)

        assertEquals(12, plan.slots.size)
        assertTrue(
            "every planned element names a catalogue exercise",
            plan.slots.flatMap { it.elements }.all { it.exerciseId in setOf("pushups", "pullups") }
        )
        // Same inputs, same plan — the boundary adds no state and no ambiguity to determinism.
        assertEquals(plan, GeneratedPlanner.plan(request))
    }

    @Test
    fun everyShippedExerciseSurvivesTheMappingOfTheRealCatalogue() {
        val catalogue = ProductionGenerationBoundary.catalogueOfShippedExercises(
            GenerationFocusSource { setOf(Focus.PUSH, Focus.MOBILITY) }
        )

        val shipped = WorkoutGenerator().getExerciseLibrary()
        assertEquals("every shipped exercise is readable", shipped.isNotEmpty(), true)
        assertEquals(
            "every shipped exercise becomes a candidate, none lost or merged",
            shipped.map { it.id },
            catalogue.candidates.map { it.exerciseId }
        )
        assertEquals(emptyList<String>(), catalogue.unclassifiedExerciseIds)
        assertTrue(
            "the real catalogue's required equipment never contains the app's \"nothing\" token",
            catalogue.candidates.none { Equipment.NONE in it.requiredEquipment }
        )
        assertTrue(
            "and it really does carry equipment requirements, so the case above is not vacuous",
            catalogue.candidates.any { it.requiredEquipment.isNotEmpty() }
        )
    }

    @Test
    fun theShippedCatalogueCarriesBothPrescriptionDimensionsAndNoThird() {
        val catalogue = ProductionGenerationBoundary.catalogueOfShippedExercises(
            GenerationFocusSource { setOf(Focus.MOBILITY) }
        )

        assertEquals(
            setOf(PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED),
            catalogue.candidates.map { it.dimension }.toSet()
        )
    }
}