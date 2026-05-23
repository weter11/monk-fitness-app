package com.monkfitness.app

import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.Exercise
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
    fun testGeneratedExercisesRespectRequestedWorkoutStructure() {
        val strengthA = generator.generateWorkout(1).exercises
        val strengthB = generator.generateWorkout(3).exercises
        val mobility = generator.generateWorkout(2).exercises
        val functional = generator.generateWorkout(5).exercises

        assertEquals(3, strengthA.size)
        assertEquals(listOf(ExerciseSubCategory.LEGS, ExerciseSubCategory.CORE, ExerciseSubCategory.FULL_BODY), strengthA.map { it.subCategory })
        assertEquals(3, strengthA.map { it.id }.distinct().size)

        assertEquals(3, strengthB.size)
        assertEquals(ExerciseSubCategory.SHOULDERS, strengthB[0].subCategory)
        assertEquals(ExerciseCategory.POSTURE, strengthB[1].category)
        assertEquals(ExerciseSubCategory.CORE, strengthB[2].subCategory)
        assertEquals(3, strengthB.map { it.id }.distinct().size)

        assertEquals(3, mobility.size)
        assertEquals(
            listOf(
                ExerciseSubCategory.SPINE,
                ExerciseSubCategory.SPINE,
                ExerciseSubCategory.HIPS
            ),
            mobility.map { it.subCategory }
        )
        assertEquals(3, mobility.map { it.id }.distinct().size)

        assertEquals(3, functional.size)
        assertTrue(functional.all { it.subCategory == ExerciseSubCategory.FULL_BODY })
        assertEquals(3, functional.map { it.id }.distinct().size)
    }

    @Test
    fun testPhaseLogicUsesExerciseLibraryBaseValues() {
        val libraryById = generator.getExerciseLibrary().associateBy { it.id }

        assertPhaseProgression(generator.generateWorkout(1).exercises, libraryById, expectedPhase = 1)
        assertPhaseProgression(generator.generateWorkout(15).exercises, libraryById, expectedPhase = 2)
        assertPhaseProgression(generator.generateWorkout(29).exercises, libraryById, expectedPhase = 3)
        assertPhaseProgression(generator.generateWorkout(43).exercises, libraryById, expectedPhase = 4)
        assertPhaseProgression(generator.generateWorkout(2).exercises, libraryById, expectedPhase = 1)
        assertPhaseProgression(generator.generateWorkout(16).exercises, libraryById, expectedPhase = 2)
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
            (1..56).forEach { day ->
                addAll(generator.generateWorkout(day).exercises.map { it.id })
            }
        }

        assertTrue(libraryIds.containsAll(usedIds))
    }

    @Test
    fun testExerciseMetadataIsAssigned() {
        val pushups = generator.getExerciseLibrary().first { it.id == "pushups" }
        val catCow = generator.getExerciseLibrary().first { it.id == "cat_cow" }

        assertEquals(ExerciseCategory.STRENGTH, pushups.category)
        assertEquals(ExerciseSubCategory.FULL_BODY, pushups.subCategory)
        assertEquals(ExerciseCategory.MOBILITY, catCow.category)
        assertEquals(ExerciseSubCategory.SPINE, catCow.subCategory)
    }

    @Test
    fun testWorkoutGenerationIsDeterministicForSameDay() {
        val first = generator.generateWorkout(1).exercises.map { it.id }
        val second = generator.generateWorkout(1).exercises.map { it.id }

        assertEquals(first, second)
    }

    private fun assertPhaseProgression(
        generatedExercises: List<Exercise>,
        libraryById: Map<String, Exercise>,
        expectedPhase: Int
    ) {
        generatedExercises.forEach { exercise ->
            val base = checkNotNull(libraryById[exercise.id])

            assertEquals(base.sets + (expectedPhase - 1), exercise.sets)

            if (base.isTimerBased) {
                assertTrue(exercise.isTimerBased)
                assertEquals(1, exercise.reps)
                assertEquals(base.durationSeconds + (expectedPhase - 1) * 15, exercise.durationSeconds)
            } else {
                assertEquals(base.reps + (expectedPhase - 1) * 2, exercise.reps)
                assertEquals(base.durationSeconds, exercise.durationSeconds)
            }
        }
    }
}
