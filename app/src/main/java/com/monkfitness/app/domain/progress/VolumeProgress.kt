package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One attempt's volume in its own units; repetitions and seconds are two numbers, never one.
 */
data class SessionVolume(
    val sessionId: SessionId,
    val programId: ProgramId,
    val startedOn: LocalDate,
    val repetitions: Int,
    val seconds: Int,
    val performedSets: Int
) {

    init {
        require(repetitions >= 0) { "repetitions performed are not negative, was $repetitions" }
        require(seconds >= 0) { "seconds performed are not negative, was $seconds" }
        require(performedSets >= 1) {
            "a session volume exists because sets were performed; an attempt with none has no volume"
        }
    }
}

/**
 * One calendar week's volume, anchored on the Monday it started.
 */
data class WeekVolume(
    val weekStart: LocalDate,
    val repetitions: Int,
    val seconds: Int,
    val performedSets: Int
) {

    init {
        require(weekStart.dayOfWeek == DayOfWeek.MONDAY) {
            "a week is anchored on its Monday, was $weekStart (${weekStart.dayOfWeek})"
        }
        require(repetitions >= 0) { "repetitions performed are not negative, was $repetitions" }
        require(seconds >= 0) { "seconds performed are not negative, was $seconds" }
        require(performedSets >= 1) {
            "a week volume exists because sets were performed; a week with none is absent"
        }
    }
}

/**
 * One exercise's volume in the one unit it was performed in.
 */
data class ExerciseVolume(
    val exerciseId: String,
    val dimension: PrescriptionDimension,
    val repetitions: Int?,
    val seconds: Int?,
    val performedSets: Int
) {

    init {
        require(exerciseId.isNotBlank()) { "an exercise volume names the exercise it is about" }
        require(performedSets >= 1) {
            "an exercise volume exists because sets were performed; an exercise with none is absent"
        }
        when (dimension) {
            PrescriptionDimension.REP_BASED -> {
                require(repetitions != null && repetitions > 0) {
                    "a repetition exercise accumulates repetitions, was $repetitions"
                }
                require(seconds == null) {
                    "a repetition exercise carries no seconds: one number over two units is the " +
                        "invented conversion §17 forbids"
                }
            }

            PrescriptionDimension.TIME_BASED -> {
                require(seconds != null && seconds > 0) {
                    "a timed exercise accumulates seconds, was $seconds"
                }
                require(repetitions == null) {
                    "a timed exercise carries no repetitions: one number over two units is the " +
                        "invented conversion §17 forbids"
                }
            }

            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED -> {
                require(repetitions == null && seconds == null) {
                    "a dimension with no unit contract has no volume: $dimension"
                }
            }
        }
    }
}

/** The Monday of the calendar week [date] falls in. */
internal fun weekStartOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
