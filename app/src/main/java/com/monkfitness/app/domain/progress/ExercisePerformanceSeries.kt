package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.workout.SessionStatus
import java.time.Instant

/**
 * Whether a prescription dimension has a **unit contract** — one which the facts can be compared in.
 *
 * Two dimensions have one (§10): repetitions per set and duration per set. A `SetResult` is measured in
 * exactly one of those two units, and `SessionExercise` checks a set against the prescription it was
 * performed under.
 *
 * The other three dimensions (`SET_BASED`, `DIFFICULTY_BASED`, `REST_BASED`) are named by the model and
 * deliberately have no subtype yet (§10: *"their algorithms, their coefficients and their units are not
 * decided here and must not be guessed here"*), and `SessionExercise.isLoggedInThePrescribedUnit`
 * accordingly claims nothing about them. A comparable series in one of those dimensions would therefore
 * have to invent the unit of comparison — so a plan element prescribed in one of them produces **no**
 * series and **no** volume, and is reported as a deferred measure instead of as a zero (§17: only values
 * with an explicitly defined comparable context are permissible).
 */
val PrescriptionDimension.hasComparableUnit: Boolean
    get() = this == PrescriptionDimension.REP_BASED || this == PrescriptionDimension.TIME_BASED

/**
 * The context two observations must share to be comparable at all (§12, §17).
 *
 * §12 states the rule in one line — *"comparable observations require compatible exercise/progression
 * context"* — and §17 says what volume is meaningful in: *"comparable exercise/family contexts"*. The
 * context this layer defines is the **exercise and the dimension it progresses in**, and nothing else,
 * because that is the whole of what the target facts record about a performed set: which exercise it was,
 * which unit it was measured in, and what was actually done. There is no progression level, no difficulty
 * and no family in a `SetLog`, and inventing any of them to make two different exercises comparable is
 * exactly the invented conversion §17 forbids (*"no invented conversion such as 1 push-up = X load
 * units"*).
 *
 * The consequence is stated plainly: **two exercises are never one context**, and repetitions are never
 * compared with seconds. A comparison across variants (*"different exercise variants are not
 * automatically directly comparable"*, §12) is not attempted here either.
 *
 * @property exerciseId the library key every observation in this context was performed under.
 * @property dimension the unit they were measured in.
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
 * One confirmed set as a comparable observation (§12's *Exposure Observation*, one unit of it).
 *
 * The two carry the session it belongs to, because an observation that cannot be attributed to the
 * workout that produced it cannot be read back, and because the *status* of that workout is part of what
 * the observation is: a set confirmed in an attempt that was later cancelled is a real observation of
 * real work (§12), and it is reported with `CANCELLED` beside it rather than promoted to a completed
 * workout's set or dropped as if it had not happened.
 *
 * @property programId the Program the attempt belongs to — kept per observation so an aggregate series
 *   can still say which Program each point came from.
 * @property sessionId the attempt that performed it.
 * @property sessionStatus how that attempt ended, or that it is still running.
 * @property setIndex 1-based position of the set inside its occurrence.
 * @property performedAt when the set was confirmed.
 * @property repetitions repetitions actually performed; `0` for a timed set.
 * @property seconds seconds actually performed; `0` for a repetition set.
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

    /** Whether this observation was measured in repetitions. */
    val isRepetitionObservation: Boolean
        get() = seconds == 0

    companion object {

        /**
         * The deterministic order of one context's observations: when each happened, then the attempt
         * and the set they belong to. Performed-at alone is not enough — a sweep of twenty sets can share
         * a clock reading — so the identity is the tiebreak and the sequence is a function of the facts.
         */
        val ORDER: Comparator<PerformanceObservation> =
            compareBy({ it.performedAt }, { it.sessionId.value }, { it.setIndex })
    }
}

/**
 * §21's **comparable performance history for one exercise**: every observation of one comparable context,
 * in the order it happened.
 *
 * *"Comparable"* is the whole content of this type. The series is per context ([ComparableContext]) and
 * its invariants hold that reading: every observation is measured in the context's own unit, and the
 * sequence is ordered deterministically. There is no aggregation across series here, and there is
 * deliberately no field that could be read as "how well did I do overall" — a single number over several
 * exercises would be the universal score §17 forbids.
 *
 * [bestRepetitions] and [bestSeconds] are the **largest single set observed in this context**. They are
 * named for what they measure rather than as "PR" on purpose: see [ProgressMeasure.PROGRAM_PR] and the
 * reason recorded with it — the target facts record no progression level or difficulty per plan element,
 * so a personal record in any stronger sense than *"the most repetitions of this exercise in one set"*
 * has no comparable context to be defined against, and inventing one is what the blueprint forbids. What
 * is reported here needs no such invention: it is one set, of one exercise, in one unit.
 *
 * @property context the exercise and the unit.
 * @property observations every confirmed set of that context, oldest first.
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

    /** How many sets were observed in this context. */
    val performedSets: Int
        get() = observations.size

    /** The most repetitions observed in a single set of this context, or `null` in a timed context. */
    val bestRepetitions: Int?
        get() = observations.maxOfOrNull { it.repetitions }?.takeIf { it > 0 }

    /** The longest single set observed in this context, or `null` in a repetition context. */
    val bestSeconds: Int?
        get() = observations.maxOfOrNull { it.seconds }?.takeIf { it > 0 }

    /** The most recent observation of this context. */
    val latest: PerformanceObservation
        get() = observations.last()
}
