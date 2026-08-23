package com.monkfitness.app

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ad-hoc diagnostic sweep (2026-08-23): user-reported crash when starting a
 * Posture & Mobility session. Exercises every day x training type x focus-area
 * set x equipment set the UI can produce and asserts the two invariants the
 * session UI depends on: non-empty exercise list, unique exercise ids
 * (LazyColumn itemsIndexed key). Not a change-detector: invariants only.
 */
class PostureSessionExhaustiveSweepTest {
    private val generator = WorkoutGenerator()

    private val specificAreas = listOf(
        ExerciseSubCategory.SHOULDERS,
        ExerciseSubCategory.SPINE,
        ExerciseSubCategory.HIPS,
        ExerciseSubCategory.LEGS,
        ExerciseSubCategory.CORE,
        ExerciseSubCategory.HYPERLORDOSIS
    )

    private val trainingTypes = FlexibilityTrainingType.entries

    private fun <T> List<T>.powerset(): List<Set<T>> =
        (0 until (1 shl size)).map { mask -> filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }

    private val equipmentSets: List<Set<Equipment>> = listOf(
        setOf(Equipment.NONE),
        setOf(Equipment.NONE, Equipment.BAR),
        setOf(Equipment.NONE, Equipment.BANDS),
        setOf(Equipment.NONE, Equipment.BACKPACK),
        setOf(Equipment.NONE, Equipment.BAR, Equipment.BANDS, Equipment.BACKPACK)
    )

    @Test
    fun postureSessionsAreNonEmptyAndDuplicateFreeAcrossFullConfigSpace() {
        val focusSets: List<Set<ExerciseSubCategory>> =
            listOf(setOf(ExerciseSubCategory.FULL_BODY)) + specificAreas.powerset().filter { it.isNotEmpty() }
        var checked = 0
        val failures = mutableListOf<String>()

        for (day in 1..56) {
            for (type in trainingTypes) {
                for (focus in focusSets) {
                    for (equipment in equipmentSets) {
                        val label = "day=$day type=$type focus=$focus equip=$equipment"
                        try {
                            val workout = generator.generatePostureMobilityWorkout(
                                day = day,
                                flexibilityTrainingType = type,
                                focusAreas = focus,
                                availableEquipment = equipment
                            )
                            assertTrue("$label: empty exercise list", workout.exercises.isNotEmpty())
                            val ids = workout.exercises.map { it.id }
                            assertEquals("$label: duplicate exercise ids $ids", ids.size, ids.distinct().size)
                        } catch (t: Throwable) {
                            failures.add("$label -> ${t.javaClass.simpleName}: ${t.message}")
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue(
            "checked=$checked; ${failures.size} failures:\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty()
        )
    }
}
