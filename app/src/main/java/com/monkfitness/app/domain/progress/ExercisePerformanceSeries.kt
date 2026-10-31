package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.workout.SessionStatus
import java.time.Instant

/**
 * Whether a prescription dimension has a unit contract the facts can be compared in: repetitions per
 * set or duration per set. The other three dimensions have no subtype yet, so a series in one of them
 * would have to invent its unit of comparison — hence no series, no volume, and a deferred measure
 * instead of a zero.
 */
val PrescriptionDimension.hasComparableUnit: Boolean
    get() = this == PrescriptionDimension.REP_BASED || this == PrescriptionDimension.TIME_BASED

/**
 * The context two observations must share to be comparable at all: the exercise and the dimension it
 * progresses in, and nothing else — that is the whole of what the facts record about a performed set.
 * A `SetLog` has no progression level, difficulty or family, and inventing one to make two exercises
 * comparable is exactly the invented conversion this model forbids.
 *
 * Consequences: two exercises are never one context, repetitions are never compared with seconds, and
 * cross-variant comparison is not attempted.
 */
data class ComparableContext(val exerciseId: String, val dimension: PrescriptionDimension) {

    init {
        require(exerciseId.isNotBlank()) { "a comparable context names the exercise it is about" }
        require(dimension.hasComparableUnit) {
            "a comparable context exists only in a dimension with a unit contract (§17); " +
                "$dimension has none"
        }
    }
}

/**
 * One confirmed set as a comparable observation.
 *
 * It carries its session, because an observation that cannot be attributed to the workout that produced
 * it cannot be read back, and because that workout's status is part of what the observation is: a set
 * confirmed in a later-cancelled attempt is real work, reported with `CANCELLED` beside it rather than
 * promoted or dropped. The program id is kept per observation so an aggregate series can still say
 * which Program each point came from.
 *
 * `repetitions` is `0` for a timed set and `seconds` is `0` for a repetition set.
 */
data class PerformanceObservation(
    val programId: ProgramId,
    val sessionId: SessionId,
    val sessionStatus: SessionStatus,
    val setIndex: Int,
    val performedAt: Instant,
    val repetitions: Int,
    val seconds: Int
) {

    init {
        require(setIndex >= 1) { "sets are numbered from 1, was $setIndex" }
        require(repetitions >= 0) { "repetitions performed are not negative, was $repetitions" }
        require(seconds >= 0) { "seconds performed are not negative, was $seconds" }
        require((repetitions > 0) != (seconds > 0)) {
            "an observation is measured in repetitions or in time, and a set that was not performed is " +
                "absent rather than observed as zero: reps=$repetitions seconds=$seconds"
        }
    }

    val isRepetitionObservation: Boolean
        get() = seconds == 0

    companion object {

        /**
         * When each happened, then the attempt and the set it belongs to. Performed-at alone is not
         * enough — a sweep of twenty sets can share a clock reading — so identity is the tiebreak and
         * the sequence is a function of the facts.
         */
        val ORDER: Comparator<PerformanceObservation> =
            compareBy({ it.performedAt }, { it.sessionId.value }, { it.setIndex })
    }
}

/**
 * Every observation of one comparable context, in the order it happened — measured in the context's own
 * unit, deterministically ordered. There is no aggregation across series and deliberately no field
 * readable as "how well did I do overall": one number over several exercises would be a universal score.
 *
 * [bestRepetitions] and [bestSeconds] are the largest single set observed here, named for what they
 * measure rather than "PR": the facts record no progression level or difficulty, so a record in any
 * stronger sense has no comparable context to be defined against.
 */
data class ExercisePerformanceSeries(
    val context: ComparableContext,
    val observations: List<PerformanceObservation>
) {

    init {
        require(observations.isNotEmpty()) {
            "a series exists because something was observed (§12); a context with no observation is " +
                "absent rather than present-and-empty"
        }
        require(observations == observations.sortedWith(PerformanceObservation.ORDER)) {
            "a series is in the order it happened: performed-at first, identity as the tiebreak. Got " +
                "${observations.map { "${it.performedAt}/${it.sessionId.value}#${it.setIndex}" }}"
        }
        require(observations.all { it.isRepetitionObservation == (context.dimension == PrescriptionDimension.REP_BASED) }) {
            "every observation of a context is measured in that context's own unit: " +
                "context=${context.exerciseId}/${context.dimension} " +
                "observations=${observations.map { "${it.repetitions} reps / ${it.seconds} s" }}"
        }
    }

    val performedSets: Int
        get() = observations.size

    /** The most repetitions observed in a single set of this context, or `null` in a timed context. */
    val bestRepetitions: Int?
        get() = observations.maxOfOrNull { it.repetitions }?.takeIf { it > 0 }

    /** The longest single set observed in this context, or `null` in a repetition context. */
    val bestSeconds: Int?
        get() = observations.maxOfOrNull { it.seconds }?.takeIf { it > 0 }

    val latest: PerformanceObservation
        get() = observations.last()
}
