package com.monkfitness.app.viewmodel

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * `WorkoutSessionContext` is a value type, so the generation settings it carries must be one too.
 *
 * A settings holder built from reference equality makes two sessions that captured byte-identical
 * settings compare unequal — and the two places that compare a session do it by value: the holder's own
 * re-entry check and every `WorkoutSessionContext`/`ActiveWorkoutSession` equality. These tests pin the
 * equality and the defensive copy, and nothing else about the session boundary.
 */
class WorkoutSessionGenerationValueTest {

    @Test
    fun twoIndependentlyCreatedGenerationsWithTheSameSettingsAreEqual() {
        val first = generation()
        val second = generation()

        assertEquals("equal contents must be equal values", first, second)
        assertEquals("and therefore hash alike", first.hashCode(), second.hashCode())
        assertEquals("a copy of the same value is the value", first, first.copy())
    }

    @Test
    fun twoContextsCarryingEquivalentGenerationsAreEqual() {
        val date = LocalDate.of(2026, 9, 1)
        val first = WorkoutSessionContext(2, 1, date, generation())
        val second = WorkoutSessionContext(2, 1, date, generation())

        assertEquals("a context is its facts, the captured settings included", first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals("a different capture is a different context", first,
            WorkoutSessionContext(2, 1, date, generation().copy(disabledFamilies = setOf("pushups"))))
    }

    @Test
    fun mutatingTheSourceCollectionsAfterConstructionDoesNotChangeTheCapturedGeneration() {
        val equipment = mutableSetOf(Equipment.BANDS)
        val adjustments = mutableMapOf("pushups" to 1)
        val focusAreas = mutableSetOf(ExerciseSubCategory.FULL_BODY)
        val disabledFamilies = mutableSetOf("pullups")
        val generation = WorkoutSessionGeneration(
            availableEquipment = equipment,
            difficultyAdjustments = adjustments,
            focusAreas = focusAreas,
            disabledFamilies = disabledFamilies
        )

        equipment += Equipment.BAR
        adjustments["pushups"] = 2
        focusAreas += ExerciseSubCategory.SHOULDERS
        disabledFamilies += "squats"

        assertEquals("the capture is the state at its start", setOf(Equipment.BANDS), generation.availableEquipment)
        assertEquals(mapOf("pushups" to 1), generation.difficultyAdjustments)
        assertEquals(setOf(ExerciseSubCategory.FULL_BODY), generation.focusAreas)
        assertEquals(setOf("pullups"), generation.disabledFamilies)
        assertEquals(
            "and it still equals an independently built capture of the settings it was given",
            WorkoutSessionGeneration(
                availableEquipment = setOf(Equipment.BANDS),
                difficultyAdjustments = mapOf("pushups" to 1),
                focusAreas = setOf(ExerciseSubCategory.FULL_BODY),
                disabledFamilies = setOf("pullups")
            ),
            generation
        )
    }

    /** The same settings, built twice from freshly allocated collections. */
    private fun generation() = WorkoutSessionGeneration(
        availableEquipment = setOf(Equipment.BANDS, Equipment.NONE),
        difficultyAdjustments = mapOf("pushups" to 1, "squats" to -1),
        trainingType = FlexibilityTrainingType.POSTURE,
        focusAreas = setOf(ExerciseSubCategory.FULL_BODY, ExerciseSubCategory.SHOULDERS),
        disabledFamilies = setOf("pullups", "lunges")
    )
}
