package com.monkfitness.app

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.applyDifficultyAdjustment
import com.monkfitness.app.data.model.hasAnimatedVariant
import com.monkfitness.app.data.model.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val mobility = generator.generateWorkout(2, focusAreas = setOf(ExerciseSubCategory.HIPS)).exercises
        val functional = generator.generateWorkout(5).exercises

        assertEquals(4, strengthA.size)
        assertEquals(listOf(ExerciseSubCategory.LEGS, ExerciseSubCategory.CORE, ExerciseSubCategory.FULL_BODY), strengthA.take(3).map { it.subCategory })
        assertTrue(strengthA.last().category == ExerciseCategory.POSTURE || strengthA.last().subCategory == ExerciseSubCategory.SPINE)
        assertEquals(4, strengthA.map { it.id }.distinct().size)

        assertEquals(3, strengthB.size)
        assertEquals(ExerciseSubCategory.SHOULDERS, strengthB[0].subCategory)
        assertTrue(strengthB[1].category == ExerciseCategory.POSTURE || strengthB[1].subCategory == ExerciseSubCategory.SPINE)
        assertEquals(ExerciseSubCategory.CORE, strengthB[2].subCategory)
        assertEquals(3, strengthB.map { it.id }.distinct().size)

        assertEquals(3, mobility.size)
        assertEquals(listOf(ExerciseSubCategory.HIPS, ExerciseSubCategory.HIPS), mobility.take(2).map { it.subCategory })
        assertEquals(3, mobility.map { it.id }.distinct().size)

        assertEquals(3, functional.size)
        assertTrue(functional.all { it.subCategory == ExerciseSubCategory.FULL_BODY })
        assertEquals(3, functional.map { it.id }.distinct().size)
    }

    @Test
    fun testOptionalPostureMobilityWorkoutPrioritizesSelectedFocusArea() {
        val workout = generator.generatePostureMobilityWorkout(1, focusAreas = setOf(ExerciseSubCategory.SHOULDERS))

        assertEquals(WorkoutType.POSTURE_MOBILITY, workout.type)
        assertEquals(4, workout.exercises.size)
        assertEquals(
            listOf(
                ExerciseSubCategory.SHOULDERS,
                ExerciseSubCategory.SHOULDERS,
                ExerciseSubCategory.SHOULDERS
            ),
            workout.exercises.take(3).map { it.subCategory }
        )
    }

    @Test
    fun testMobilityDaysPrioritizeSelectedStretchFocusArea() {
        val shouldersMobility = generator.generateWorkout(2, focusAreas = setOf(ExerciseSubCategory.SHOULDERS)).exercises
        val legsMobility = generator.generateWorkout(2, focusAreas = setOf(ExerciseSubCategory.LEGS)).exercises

        assertEquals(listOf(ExerciseSubCategory.SHOULDERS, ExerciseSubCategory.SHOULDERS), shouldersMobility.take(2).map { it.subCategory })
        assertEquals(listOf(ExerciseSubCategory.LEGS, ExerciseSubCategory.LEGS), legsMobility.take(2).map { it.subCategory })
    }

    @Test
    fun testFullBodyFlexibilitySelectionDistributesAcrossAreas() {
        val workout = generator.generatePostureMobilityWorkout(
            day = 1,
            focusAreas = setOf(ExerciseSubCategory.FULL_BODY)
        )

        assertEquals(4, workout.exercises.size)
        assertEquals(4, workout.exercises.map { it.subCategory }.distinct().size)
    }

    @Test
    fun testFlexibilityTrainingTypeFiltersCategories() {
        val postureOnly = generator.generatePostureMobilityWorkout(
            day = 1,
            flexibilityTrainingType = FlexibilityTrainingType.POSTURE,
            focusAreas = setOf(ExerciseSubCategory.FULL_BODY)
        ).exercises

        val stretchingOnly = generator.generateWorkout(
            day = 2,
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            focusAreas = setOf(ExerciseSubCategory.FULL_BODY)
        ).exercises

        assertTrue(postureOnly.all { it.category == ExerciseCategory.POSTURE })
        assertTrue(stretchingOnly.all { it.category == ExerciseCategory.STRETCHING || it.category == ExerciseCategory.MOBILITY })
    }

    @Test
    fun testPhaseLogicUsesExerciseLibraryBaseValues() {
        val libraryById = generator.getExerciseLibrary().associateBy { it.id }

        listOf(1, 8).forEach { day ->
            assertPhaseProgression(generator.generateWorkout(day).exercises, libraryById, expectedPhase = 1)
        }
        listOf(15, 22).forEach { day ->
            assertPhaseProgression(generator.generateWorkout(day).exercises, libraryById, expectedPhase = 2)
        }
        listOf(29, 36).forEach { day ->
            assertPhaseProgression(generator.generateWorkout(day).exercises, libraryById, expectedPhase = 3)
        }
        listOf(43, 50).forEach { day ->
            assertPhaseProgression(generator.generateWorkout(day).exercises, libraryById, expectedPhase = 4)
        }
    }

    @Test
    fun testAllGeneratedExercisesStayWithinPhaseTargets() {
        val libraryById = generator.getExerciseLibrary().associateBy { it.id }

        (1..56).forEach { day ->
            val expectedPhase = (((((day - 1) / 7) + 1) - 1) / 2) + 1
            assertPhaseProgression(generator.generateWorkout(day).exercises, libraryById, expectedPhase)
        }
    }

    @Test
    fun testPerExerciseScalingUsesExerciseSpecificRanges() {
        val pushupsPhase1 = findGeneratedExercise("pushups", 1..14)
        val squatsPhase1 = findGeneratedExercise("squats", 1..14)
        val pushupsPhase4 = findGeneratedExercise("pushups", 43..56)
        val squatsPhase4 = findGeneratedExercise("squats", 43..56)

        assertEquals(6, pushupsPhase1.minReps)
        assertEquals(8, pushupsPhase1.maxReps)
        assertEquals(12, squatsPhase1.minReps)
        assertEquals(15, squatsPhase1.maxReps)

        assertEquals(10, pushupsPhase4.minReps)
        assertEquals(15, pushupsPhase4.maxReps)
        assertEquals(18, squatsPhase4.minReps)
        assertEquals(25, squatsPhase4.maxReps)
    }

    @Test
    fun testDifficultyAdjustmentAppliesToRepAndTimerExercises() {
        val pushups = generator.getExerciseLibrary().first { it.id == "pushups" }
        val plank = generator.getExerciseLibrary().first { it.id == "plank" }

        val harderPushups = pushups.applyDifficultyAdjustment(2)
        val easierPlank = plank.applyDifficultyAdjustment(-2)

        assertEquals(pushups.minReps + 4, harderPushups.minReps)
        assertEquals(pushups.maxReps + 4, harderPushups.maxReps)
        assertEquals(pushups.maxReps + 4, harderPushups.reps)

        assertEquals(plank.durationSeconds - 10, easierPlank.durationSeconds)
        assertEquals(1, easierPlank.reps)
    }

    @Test
    fun testExerciseLibraryUsesFixedCuratedList() {
        val library = generator.getExerciseLibrary()
        val ids = library.map { it.id }

        assertEquals(49, library.size)
        assertEquals(49, ids.distinct().size)
        assertTrue(
            ids.containsAll(
                listOf(
                    "decline_pushups",
                    "diamond_pushups",
                    "step_ups",
                    "wall_sit",
                    "shoulder_cars",
                    "hip_cars",
                    "ankle_mobility",
                    "chin_tucks",
                    "y_t_raises",
                    "scapular_retraction_hold",
                    "calf_stretch",
                    "lat_stretch",
                    "piriformis_stretch",
                    "hip_flexor_stretch",
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
            ExerciseSubCategory.entries
                .filter { it in listOf(ExerciseSubCategory.SHOULDERS, ExerciseSubCategory.SPINE, ExerciseSubCategory.HIPS) }
                .forEach { focusArea ->
                    addAll(generator.generatePostureMobilityWorkout(1, focusAreas = setOf(focusArea)).exercises.map { it.id })
                }
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
        val chinTucks = generator.getExerciseLibrary().first { it.id == "chin_tucks" }
        val calfStretch = generator.getExerciseLibrary().first { it.id == "calf_stretch" }

        assertEquals(ExerciseCategory.STRENGTH, pushups.category)
        assertEquals(ExerciseSubCategory.FULL_BODY, pushups.subCategory)
        assertEquals(ExerciseCategory.MOBILITY, catCow.category)
        assertEquals(ExerciseSubCategory.SPINE, catCow.subCategory)
        assertEquals(ExerciseCategory.POSTURE, chinTucks.category)
        assertEquals(ExerciseSubCategory.SPINE, chinTucks.subCategory)
        assertEquals(ExerciseCategory.STRETCHING, calfStretch.category)
        assertEquals(ExerciseSubCategory.LEGS, calfStretch.subCategory)
    }

    @Test
    fun testRequestedExercisesIncludeFullInstructionResources() {
        val requestedExercises = listOf(
            "decline_pushups",
            "diamond_pushups",
            "step_ups",
            "wall_sit",
            "shoulder_cars",
            "hip_cars",
            "ankle_mobility",
            "chin_tucks",
            "y_t_raises",
            "scapular_retraction_hold",
            "calf_stretch",
            "lat_stretch",
            "piriformis_stretch",
            "hip_flexor_stretch"
        ).map { id -> generator.getExerciseLibrary().first { it.id == id } }

        requestedExercises.forEach { exercise ->
            assertTrue(exercise.descriptionRes != 0)
            assertTrue(exercise.stepsRes != 0)
            assertTrue(exercise.techniqueRes != 0)
            assertTrue(exercise.mistakesRes != 0)
        }
    }

    @Test
    fun testBodyweightFallbackKeepsPostureWorkoutNonEmptyWithoutEquipment() {
        val workout = generator.generatePostureMobilityWorkout(
            day = 1,
            flexibilityTrainingType = FlexibilityTrainingType.POSTURE,
            focusAreas = setOf(ExerciseSubCategory.SHOULDERS),
            availableEquipment = setOf(Equipment.NONE)
        )

        assertEquals(4, workout.exercises.size)
        assertTrue(workout.exercises.all { it.requiredEquipment.isEmpty() })
    }

    @Test
    fun testExerciseLibraryCanBeFilteredByAvailableEquipment() {
        val bodyweightOnly = generator.getExerciseLibrary(availableEquipment = setOf(Equipment.NONE))

        assertTrue(bodyweightOnly.isNotEmpty())
        assertTrue(bodyweightOnly.all { it.requiredEquipment.isEmpty() })
        assertTrue(bodyweightOnly.size < generator.getExerciseLibrary().size)
    }

    @Test
    fun testExerciseLibraryShowsAllExercisesWhenEquipmentFilterIsCleared() {
        assertEquals(
            generator.getExerciseLibrary().map { it.id }.toSet(),
            generator.getExerciseLibrary(availableEquipment = emptySet()).map { it.id }.toSet()
        )
    }

    @Test
    fun testExerciseAvailabilityRequiresAllSelectedEquipment() {
        val exercise = Exercise(
            id = "combo",
            nameRes = R.string.ex_pushups,
            descriptionRes = R.string.ex_pushups_desc,
            techniqueRes = R.string.ex_pushups_tech,
            imageRes = null,
            sets = 3,
            reps = 8,
            requiredEquipment = setOf(Equipment.BAR, Equipment.BANDS)
        )

        assertFalse(generator.run { exercise.isAccessibleWith(setOf(Equipment.NONE, Equipment.BAR)) })
        assertTrue(generator.run { exercise.isAccessibleWith(setOf(Equipment.NONE, Equipment.BAR, Equipment.BANDS)) })
    }

    @Test
    fun testWarmupExercisesExposeAnimatedVariants() {
        val animatedWarmups = generator.getWarmupExercises().filter { it.hasAnimatedVariant() }

        assertTrue(animatedWarmups.isNotEmpty())
        assertTrue(animatedWarmups.all { it.imageRes != null })
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

            assertEquals(base.sets, exercise.sets)

            if (base.isTimerBased) {
                assertTrue(exercise.isTimerBased)
                assertEquals(1, exercise.reps)
                assertEquals(interpolate(base.baseDurationSeconds, base.phase4DurationSeconds, expectedPhase), exercise.durationSeconds)
                assertEquals(0, exercise.minReps)
                assertEquals(0, exercise.maxReps)
            } else {
                val expectedMin = interpolate(base.baseMinReps, base.phase4MinReps, expectedPhase)
                val expectedMax = interpolate(base.baseMaxReps, base.phase4MaxReps, expectedPhase)
                assertEquals(expectedMin, exercise.minReps)
                assertEquals(expectedMax, exercise.maxReps)
                assertEquals(expectedMax, exercise.reps)
                assertEquals(base.durationSeconds, exercise.durationSeconds)
            }
        }
    }

    private fun interpolate(start: Int, end: Int, phase: Int): Int {
        if (phase <= 1) return start
        if (phase >= 4) return end
        return kotlin.math.round(start + (end - start) * ((phase - 1) / 3.0)).toInt()
    }

    private fun findGeneratedExercise(id: String, days: IntRange): Exercise {
        return checkNotNull(
            days
                .asSequence()
                .map { generator.generateWorkout(it).exercises.firstOrNull { exercise -> exercise.id == id } }
                .firstOrNull { it != null }
        ) { "Exercise $id was not generated in days $days" }
    }
}
