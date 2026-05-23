package com.monkfitness.app

import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutGeneratorTest {
    private val generator = WorkoutGenerator()

    @Test
    fun testWeeklyStructure() {
        // Mon: Strength A, Tue: Mobility, Wed: Strength B, Thu: Rest, Fri: Functional, Sat: Mobility, Sun: Rest
        assertEquals(WorkoutType.STRENGTH_A, generator.generateWorkout(1).type)
        assertEquals(WorkoutType.MOBILITY, generator.generateWorkout(2).type)
        assertEquals(WorkoutType.STRENGTH_B, generator.generateWorkout(3).type)
        assertEquals(WorkoutType.REST, generator.generateWorkout(4).type)
        assertEquals(WorkoutType.FUNCTIONAL, generator.generateWorkout(5).type)
        assertEquals(WorkoutType.MOBILITY, generator.generateWorkout(6).type)
        assertEquals(WorkoutType.REST, generator.generateWorkout(7).type)
    }

    @Test
    fun testPhaseLogic() {
        val p1Exercise = generator.generateWorkout(1).exercises[0] // Pushups P1
        val p2Exercise = generator.generateWorkout(15).exercises[0] // Pushups P2 (Week 3)
        val p3Exercise = generator.generateWorkout(29).exercises[0] // Pushups P3 (Week 5)
        val p4Exercise = generator.generateWorkout(43).exercises[0] // Pushups P4 (Week 7)

        // Base sets 3, increase 1 per phase
        assertEquals(3, p1Exercise.sets)
        assertEquals(4, p2Exercise.sets)
        assertEquals(5, p3Exercise.sets)
        assertEquals(6, p4Exercise.sets)
    }

    @Test
    fun testExerciseLibraryUsesFixedCuratedList() {
        val library = generator.getExerciseLibrary()
        val ids = library.map { it.id }

        assertEquals(36, library.size)
        assertEquals(36, ids.distinct().size)
        assertTrue(
            ids.containsAll(
                listOf(
                    "face_pull",
                    "pushups",
                    "burpees",
                    "hang",
                    "horse_stance",
                    "jumping_jacks",
                    "neck_circles",
                    "arm_circles",
                    "hip_circles",
                    "leg_swings",
                    "bird_dog",
                    "world_greatest_stretch"
                )
            )
        )
    }

    @Test
    fun testLibraryContainsAllWorkoutAndSupplementalExercises() {
        val libraryIds = generator.getExerciseLibrary().map { it.id }.toSet()
        val usedIds = buildSet {
            addAll(generator.getWarmupExercises().map { it.id })
            addAll(generator.getPostureExercises().map { it.id })
            addAll(generator.generateWorkout(1).exercises.map { it.id })
            addAll(generator.generateWorkout(2).exercises.map { it.id })
            addAll(generator.generateWorkout(3).exercises.map { it.id })
            addAll(generator.generateWorkout(5).exercises.map { it.id })
        }

        assertTrue(libraryIds.containsAll(usedIds))
    }

    @Test
    fun testExerciseMetadataIsAssigned() {
        val pushups = generator.generateWorkout(1).exercises.first { it.id == "pushups" }
        val catCow = generator.getExerciseLibrary().first { it.id == "cat_cow" }

        assertEquals(ExerciseCategory.STRENGTH, pushups.category)
        assertEquals(ExerciseSubCategory.FULL_BODY, pushups.subCategory)
        assertEquals(ExerciseCategory.MOBILITY, catCow.category)
        assertEquals(ExerciseSubCategory.SPINE, catCow.subCategory)
    }
}
