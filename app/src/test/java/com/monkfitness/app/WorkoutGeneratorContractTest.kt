package com.monkfitness.app

import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.WorkoutType
import com.monkfitness.app.domain.usecase.WorkoutGenerationException
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eight-week contract of [WorkoutGenerator], asserted over **all 56 days** rather than a sample.
 *
 * The behavioural tests in `WorkoutGeneratorTest` check individual days and individual rules. This file
 * pins the promises the README makes about the programme as a whole — the weekly shape, intensity that
 * rises every two weeks, a workout on every training day, and the same input producing the same workout —
 * plus the one thing that used to be silent: a day that *cannot* be built must say so.
 *
 * A rest day is legitimately empty; it is recognised by [WorkoutType.REST], never by an empty list.
 */
class WorkoutGeneratorContractTest {

    private val generator = WorkoutGenerator()

    /** Mon..Sun, as `getWorkoutType` promises and the README describes. */
    private val expectedTypesByWeekday = listOf(
        WorkoutType.STRENGTH_A,
        WorkoutType.MOBILITY,
        WorkoutType.STRENGTH_B,
        WorkoutType.REST,
        WorkoutType.FUNCTIONAL,
        WorkoutType.MOBILITY,
        WorkoutType.REST
    )

    /** Weeks 1-2 phase 1, 3-4 phase 2, 5-6 phase 3, 7-8 phase 4. */
    private fun phaseOf(day: Int): Int = ((((day - 1) / 7) + 1) - 1) / 2 + 1

    private fun trainingDays(): List<Int> = (1..56).filter { day ->
        expectedTypesByWeekday[(day - 1) % 7] != WorkoutType.REST
    }

    @Test
    fun everyDayOfTheEightWeeksHasItsExpectedType() {
        (1..56).forEach { day ->
            val workout = generator.generateWorkout(day)
            assertEquals("day $day has the wrong workout type", expectedTypesByWeekday[(day - 1) % 7], workout.type)
            assertEquals("day $day reports the wrong id", day, workout.id)
        }
    }

    @Test
    fun everyTrainingDayHasExercisesAndOnlyRestDaysAreEmpty() {
        (1..56).forEach { day ->
            val workout = generator.generateWorkout(day)
            if (workout.type == WorkoutType.REST) {
                assertTrue("rest day $day must be empty", workout.exercises.isEmpty())
            } else {
                assertTrue("training day $day (${workout.type}) generated no exercise", workout.exercises.isNotEmpty())
                assertTrue(
                    "day $day repeats an exercise: ${workout.exercises.map { it.id }}",
                    workout.exercises.map { it.id }.distinct().size == workout.exercises.size
                )
            }
        }
    }

    @Test
    fun intensityNeverFallsFromOnePhaseToTheNext() {
        // id -> phase -> the volume that phase prescribes. One phase must prescribe one volume: the
        // per-phase value depends on the phase alone, so two different values in the same phase is
        // itself a defect.
        val volumeByExercise = linkedMapOf<String, MutableMap<Int, Exercise>>()

        trainingDays().forEach { day ->
            val phase = phaseOf(day)
            generator.generateWorkout(day).exercises.forEach { exercise ->
                val byPhase = volumeByExercise.getOrPut(exercise.id) { linkedMapOf() }
                val already = byPhase.put(phase, exercise)
                if (already != null) {
                    assertEquals(
                        "exercise ${exercise.id} differs inside phase $phase " +
                            "(day $day): ${already.minReps}-${already.maxReps}/${already.durationSeconds}s " +
                            "vs ${exercise.minReps}-${exercise.maxReps}/${exercise.durationSeconds}s",
                        volume(already),
                        volume(exercise)
                    )
                }
            }
        }

        assertTrue("no exercise was generated at all", volumeByExercise.isNotEmpty())

        volumeByExercise.forEach { (id, byPhase) ->
            val ordered = byPhase.toSortedMap()
            ordered.entries.zipWithNext { (phase, earlier), (nextPhase, later) ->
                if (earlier.isTimerBased) {
                    assertTrue(
                        "exercise $id holds ${earlier.durationSeconds}s in phase $phase " +
                            "but only ${later.durationSeconds}s in phase $nextPhase",
                        later.durationSeconds >= earlier.durationSeconds
                    )
                } else {
                    assertTrue(
                        "exercise $id does ${earlier.minReps}-${earlier.maxReps} reps in phase $phase " +
                            "but only ${later.minReps}-${later.maxReps} in phase $nextPhase",
                        later.minReps >= earlier.minReps && later.maxReps >= earlier.maxReps
                    )
                }
                assertTrue(
                    "exercise $id keeps the same sets across phases ($phase, $nextPhase)",
                    later.sets == earlier.sets
                )
            }
            // Phase 4 is the peak: wherever an exercise appears in both ends, it must have grown.
            val first = ordered[1]
            val last = ordered[4]
            if (first != null && last != null) {
                val grew = if (first.isTimerBased) {
                    last.durationSeconds >= first.durationSeconds
                } else {
                    last.maxReps >= first.maxReps && last.minReps >= first.minReps
                }
                assertTrue("exercise $id regresses below its phase 1 volume in phase 4", grew)
            }
        }
    }

    @Test
    fun theSameDayGeneratesTheSameWorkoutEveryTime() {
        (1..56).forEach { day ->
            val first = generator.generateWorkout(day)
            val second = generator.generateWorkout(day)
            assertEquals("day $day is not deterministic", signature(first.exercises), signature(second.exercises))
        }
    }

    @Test
    fun aDayThatCannotBeBuiltNamesItsDayTypeAndCandidateCount() {
        val failure = runCatching {
            generator.generateWorkout(day = 5, allowedExerciseIds = emptySet())
        }.exceptionOrNull()

        assertTrue(
            "an empty candidate set must not be answered with an empty workout, got $failure",
            failure is WorkoutGenerationException
        )
        val named = failure as WorkoutGenerationException
        val message = named.message.orEmpty()

        assertEquals("the failure must name the day", 5, named.day)
        assertEquals("the failure must name the type", WorkoutType.FUNCTIONAL, named.type)
        assertEquals("the failure must count eligibility", 0, named.eligibleExerciseCount)
        assertTrue("message lacks the day: $message", message.contains("day 5"))
        assertTrue("message lacks the type: $message", message.contains("FUNCTIONAL"))
        assertTrue("message lacks the candidate count: $message", message.contains("eligible exercises: 0"))
    }

    @Test
    fun restDaysAreEmptyWithoutBeingFailures() {
        listOf(4, 7, 11, 56).filter { day -> expectedTypesByWeekday[(day - 1) % 7] == WorkoutType.REST }
            .forEach { day ->
                val workout = generator.generateWorkout(day, allowedExerciseIds = emptySet())
                assertEquals(WorkoutType.REST, workout.type)
                assertTrue("rest day $day must stay empty", workout.exercises.isEmpty())
            }
    }

    private fun volume(exercise: Exercise): String =
        "${exercise.minReps}-${exercise.maxReps}/${exercise.durationSeconds}s/${exercise.sets}"

    /** Everything the user would notice about a workout, in order. */
    private fun signature(exercises: List<Exercise>): List<String> = exercises.map { exercise ->
        "${exercise.id}:${exercise.sets}:${exercise.minReps}-${exercise.maxReps}" +
            ":${exercise.reps}:${exercise.durationSeconds}:${exercise.isTimerBased}"
    }
}
