package com.monkfitness.app.domain.adaptive.scenario

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.PerformedSetAmount
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRequest
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveResult
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveWindow
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramGapFacts
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.integration.AdaptiveJudgementRule
import com.monkfitness.app.domain.adaptive.integration.AdaptiveWindowRule
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * One row of a simulated timeline: what a session was asked for, what happened, and what the adaptive
 * engine decided the next session gets.
 *
 * [action] is `null` when the pass stopped before the engine — a family with no declared ladder is
 * reported, never decided — which is a different fact from a `HOLD`.
 */
internal data class ScenarioRow(
    val session: Int,
    val date: LocalDate,
    val exerciseId: String,
    val planned: List<Int>,
    val actual: List<Int>,
    val nextExerciseId: String,
    val nextPlanned: List<Int>,
    val action: AdaptiveAction?,
    val reason: String
) {

    /** What the next session gets, as one cell of the table. */
    val next: String
        get() = "$nextExerciseId ${nextPlanned.joinToString("/")}"

    /** Whether the load the next session receives is heavier than the one just performed. */
    val rose: Boolean
        get() = nextPlanned.sum() > planned.sum() || nextExerciseId != exerciseId
}

/**
 * A multi-week timeline over the real engine: a session is presented, performed and recorded, and the
 * engine's decision for the **next** session is applied to it — the standing-adjustment shape production
 * uses, one opportunity ahead.
 *
 * It assembles exactly the inputs `ProgramAdaptiveIntegration` assembles — the window's observations,
 * the plan's own prescription as the baseline, what was performed as the recent side, the gap facts, and
 * the family's maintained counters — because a scenario that fed the engine invented facts would verify a
 * system nobody runs. What it does *not* do is read or write storage: the timeline is the history, so a
 * scenario is a value and a rerun is the same run.
 *
 * @param subjectExerciseId the exercise the plan presents first for the family — the one the user starts on.
 * @param availableExerciseIds the exercises the plan itself presents; a ladder step the plan does not
 *   present is unavailable, exactly as `availableExerciseIdsOf` states it in production.
 * @param relation the family's declared ladder, or `null` for a family this app declares none for.
 */
internal class AdaptiveLoadTimeline(
    private val familyId: String,
    private val dimension: PrescriptionDimension,
    private val rangeOf: (String) -> ExerciseRange?,
    private val relation: ProgramProgressionRelation?,
    private val availableExerciseIds: Set<String>,
    private val subjectExerciseId: String,
    private val subjectAmounts: List<Int>,
    private val ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC,
    private val policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1,
    private val windowRule: AdaptiveWindowRule = AdaptiveWindowRule.V1,
    private val zone: ZoneOffset = ZoneOffset.UTC
) {

    private data class Recorded(
        val index: Int,
        val date: LocalDate,
        val startedAt: Instant,
        val exerciseId: String,
        val planTargets: List<Int>,
        val presented: List<Int>,
        val performed: List<Int>
    )

    private val history = mutableListOf<Recorded>()

    private var presentedExerciseId: String = subjectExerciseId
    private var presentedAmounts: List<Int> = subjectAmounts
    private var level: Int? = relation?.declared(subjectExerciseId)?.level
    private var state: AdaptiveState = AdaptiveState.HOLD
    private var progressWindows = 0
    private var regressWindows = 0
    private var recoveryWindows = 0
    private var windowsSinceLastChange: Int? = null

    /** The amounts the next session will be asked for. */
    val nextPlanned: List<Int>
        get() = presentedAmounts
    /** The exercise the next session will present. */
    val nextExerciseId: String
        get() = presentedExerciseId

    /** The family's position, as the timeline maintains it. */
    val progressionLevel: Int?
        get() = level

    /**
     * Records history that predates the timeline's first decision — a user who has already been training.
     *
     * The sessions are performed exactly at the prescription, so the only fact this adds is *how long* the
     * history is, which is the difference between a novice and a veteran when the results are identical.
     */
    fun seedSession(date: LocalDate, performed: List<Int>) {
        history += Recorded(
            index = history.size + 1,
            date = date,
            startedAt = date.atTime(LocalTime.of(7, 30)).toInstant(zone),
            exerciseId = presentedExerciseId,
            planTargets = presentedAmounts,
            presented = presentedAmounts,
            performed = performed
        )
    }

    /**
     * Records a skipped opportunity: the plan asked for it, nothing was performed, and no adaptive pass
     * runs — a pass is the completion of an attempt, and a missed opportunity has none. The prescription
     * is left exactly as it was.
     */
    fun recordSkip(date: LocalDate) {
        history += Recorded(
            index = history.size + 1,
            date = date,
            startedAt = date.atTime(LocalTime.of(7, 30)).toInstant(zone),
            exerciseId = presentedExerciseId,
            planTargets = presentedAmounts,
            presented = presentedAmounts,
            performed = emptyList()
        )
    }

    /**
     * Performs one session on [date] and decides the next one.
     *
     * [performed] is what the user actually logged, set by set: an empty list is a skipped session, which
     * contributes an opportunity and no exposure. [planTargets] is the plan's own prescription for the
     * session, which is the baseline the recent side is compared with; it defaults to what was presented.
     */
    fun runSession(
        date: LocalDate,
        performed: List<Int>,
        planTargets: List<Int> = presentedAmounts
    ): ScenarioRow {
        val index = history.size + 1
        val startedAt = date.atTime(LocalTime.of(7, 30)).toInstant(zone)
        val presented = presentedAmounts
        val presentedExercise = presentedExerciseId
        history += Recorded(index, date, startedAt, presentedExercise, planTargets, presented, performed)

        val noLadder = relation == null
        val result = if (noLadder) null else decide(startedAt.plusSeconds(HOURS_TO_DECIDE))

        if (result != null && result.adjustment != null) {
            presentedExerciseId = result.adjustment.after.exerciseId
            presentedAmounts = result.adjustment.after.prescription.perSetTargets
        }
        if (result != null) advance(result)

        return ScenarioRow(
            session = index,
            date = date,
            exerciseId = presentedExercise,
            planned = presented,
            actual = performed,
            nextExerciseId = presentedExerciseId,
            nextPlanned = presentedAmounts,
            action = result?.decision?.action,
            reason = if (result == null) NO_DECLARED_LADDER else result.reason.name
        )
    }

    /** The engine's decision for the session that follows the history recorded so far. */
    private fun decide(capturedAt: Instant): ProgramAdaptiveResult {
        val windowStart = windowRule.windowStart(capturedAt)
        val inWindow = history.filter { it.startedAt >= windowStart && it.startedAt <= capturedAt }

        val observations = history
            .filter { it.performed.isNotEmpty() && it.startedAt >= windowStart }
            .map { recorded ->
                ExposureObservation(
                    exerciseId = recorded.exerciseId,
                    sessionId = SessionId("session-${recorded.index}"),
                    sessionExerciseId = SessionExerciseId("occurrence-${recorded.index}"),
                    level = if (recorded.performed.size >= recorded.prescribedSets()) {
                        ExposureLevel.FULL
                    } else {
                        ExposureLevel.PARTIAL
                    },
                    completedSets = recorded.performed.size,
                    prescribedSets = recorded.prescribedSets(),
                    performedAmounts = recorded.performed.map { amount ->
                        PerformedSetAmount(
                            completedReps = if (dimension == PrescriptionDimension.TIME_BASED) 0 else amount,
                            durationSeconds = if (dimension == PrescriptionDimension.TIME_BASED) amount else 0
                        )
                    },
                    startedAt = recorded.startedAt,
                    finishedAt = recorded.startedAt.plusSeconds(HOURS_TO_DECIDE)
                )
            }

        val baseline = planLoadOf(inWindow)
        val recent = performedLoadOf(inWindow, baseline.exposure)
        val familyOfExercise = availableExerciseIds.associateWith { familyId } +
            history.map { it.exerciseId }.distinct().associateWith { familyId }

        val judgement = AdaptiveJudgementRule.of(
            snapshot = snapshot(capturedAt, windowStart, observations, baseline, recent, ABSENT_JUDGEMENT),
            familyOfExercise = familyOfExercise,
            familyId = familyId,
            policy = policy
        )
        val snapshot = snapshot(
            capturedAt,
            windowStart,
            observations,
            baseline,
            recent,
            judgement as com.monkfitness.app.domain.adaptive.integration.AdaptiveJudgement
        )

        val element = ProgramAdaptiveElement(
            presentation = EffectiveExercise(
                programExerciseId = ProgramExerciseId(PLAN_ELEMENT),
                exerciseId = presentedExerciseId,
                prescription = prescriptionOf(presentedAmounts),
                focus = null
            ),
            familyId = familyId,
            ownership = ownership
        )

        return ProgramAdaptiveEngine.decide(
            ProgramAdaptiveRequest(
                decisionId = DecisionId("decision-${history.size}"),
                adjustmentId = AdjustmentId("adjustment-${history.size}"),
                decidedAt = capturedAt,
                snapshot = snapshot,
                element = element,
                relation = relation!!,
                window = ProgramAdaptiveWindow(
                    familyId = familyId,
                    level = level,
                    state = state,
                    precedingProgressQualifyingWindows = progressWindows,
                    precedingRegressQualifyingWindows = regressWindows,
                    precedingRecoveryQualifyingWindows = recoveryWindows,
                    qualifyingWindowsSinceLastChange = windowsSinceLastChange,
                    recoveryQualifyingWindows = recoveryWindows,
                    gap = gapFacts(capturedAt)
                ),
                availableExerciseIds = availableExerciseIds,
                familyOfExercise = familyOfExercise,
                range = rangeOf(presentedExerciseId),
                policy = policy
            )
        )
    }

    /** The family's bookkeeping after one window — the counts `ProgramAdaptiveIntegration` advances. */
    private fun advance(result: ProgramAdaptiveResult) {
        val verdict = result.verdict
        progressWindows = if (verdict.progressQualifying) progressWindows + 1 else 0
        regressWindows = if (verdict.regressQualifying) regressWindows + 1 else 0
        recoveryWindows = if (result.state == AdaptiveState.RECOVERY) recoveryWindows + 1 else 0

        val levelChange = result.spendsTheCooldown
        windowsSinceLastChange = when {
            levelChange -> 0
            windowsSinceLastChange == null -> null
            result.state == AdaptiveState.RECOVERY -> windowsSinceLastChange
            else -> windowsSinceLastChange!! + 1
        }
        state = result.state
        level = result.adjustment?.after?.exerciseId?.let { moved -> relation?.declared(moved)?.level } ?: level
    }

    private fun Recorded.prescribedSets(): Int = presented.size

    private fun prescriptionOf(amounts: List<Int>): Prescription =
        if (dimension == PrescriptionDimension.TIME_BASED) {
            TimePrescription(amounts)
        } else {
            RepPrescription(amounts)
        }

    private fun snapshot(
        capturedAt: Instant,
        windowStart: Instant,
        observations: List<ExposureObservation>,
        baseline: LoadProfile,
        recent: LoadProfile?,
        judgement: com.monkfitness.app.domain.adaptive.integration.AdaptiveJudgement
    ): AdaptiveInputSnapshot = AdaptiveInputSnapshot(
        programId = ProgramId(PROGRAM),
        revisionId = RevisionId(REVISION),
        slotId = SlotId(SLOT),
        windowStart = windowStart,
        capturedAt = capturedAt,
        exposures = observations,
        evidence = judgement.evidence,
        confidence = judgement.confidence,
        recovery = judgement.recovery,
        baselineLoad = baseline,
        recentLoad = recent
    )

    private fun planLoadOf(inWindow: List<Recorded>): LoadProfile {
        val worked = inWindow.count { it.performed.isNotEmpty() }
        return LoadProfile(
            scope = AdaptiveScope.FAMILY,
            volume = VolumeLoad(
                sets = inWindow.sumOf { it.planTargets.size },
                repetitions = inWindow
                    .filter { dimension == PrescriptionDimension.REP_BASED }
                    .sumOf { it.planTargets.sum() },

                durationSeconds = inWindow
                    .filter { dimension == PrescriptionDimension.TIME_BASED }
                    .sumOf { it.planTargets.sum() }
            ),
            intensity = IntensityLoad(
                levels = level?.let { listOf(IntensityEntry(familyId, it)) } ?: emptyList()
            ),
            density = DensityLoad(workingSeconds = 0, restSeconds = 0),
            exposure = ExposureLoad(opportunities = inWindow.size, completedOpportunities = worked)
        )
    }

    private fun performedLoadOf(inWindow: List<Recorded>, exposure: ExposureLoad): LoadProfile? {
        val worked = inWindow.filter { it.performed.isNotEmpty() }
        if (worked.isEmpty()) return null
        return LoadProfile(
            scope = AdaptiveScope.FAMILY,
            volume = VolumeLoad(
                sets = worked.sumOf { it.performed.size },
                repetitions = worked
                    .filter { dimension == PrescriptionDimension.REP_BASED }
                    .sumOf { it.performed.sum() },

                durationSeconds = worked
                    .filter { dimension == PrescriptionDimension.TIME_BASED }
                    .sumOf { it.performed.sum() }
            ),
            intensity = IntensityLoad(),
            density = DensityLoad(workingSeconds = 0, restSeconds = 0),
            exposure = exposure
        )
    }

    /**
     * The timing facts, from the history the timeline already holds: the stretch before this session and
     * how often the user usually trains, in the family's own sessions.
     */
    private fun gapFacts(capturedAt: Instant): ProgramGapFacts {
        val today = LocalDate.ofInstant(capturedAt, zone)
        // The completing session is excluded, as the integration excludes it: the stretch that led to this
        // session *is* the gap, and counting it would make every return measure as zero days.
        val worked = history.dropLast(1).filter { it.performed.isNotEmpty() }
        val last = worked.maxOfOrNull { it.date }
        val daysAway = last?.let { ChronoUnit.DAYS.between(it, today) }
        val starts = worked.map { it.startedAt }.distinct().sorted().takeLast(policy.usualIntervalSessions)
        val usualInterval = if (starts.size < policy.sessionsBeforeAnIntervalIsUsual) {
            null
        } else {
            val intervals = starts.zipWithNext { earlier, later -> Duration.between(earlier, later) }.sorted()
            val middle = intervals.size / 2
            if (intervals.size % 2 == 1) {
                intervals[middle]
            } else {
                Duration.ofSeconds((intervals[middle - 1].seconds + intervals[middle].seconds) / 2)
            }
        }
        return ProgramGapFacts(lastConfirmedSetOn = last, daysAway = daysAway, usualInterval = usualInterval)
    }

    companion object {

        const val PROGRAM = "scenario-program"
        const val REVISION = "scenario-revision"
        const val SLOT = "scenario-slot"
        const val PLAN_ELEMENT = "scenario-element"

        /** A family the app declares no ladder for: the pass reports it instead of deciding. */
        const val NO_DECLARED_LADDER = "NO_DECLARED_PROGRESSION_RELATION"

        /** The decision is taken an hour after the session starts, as a completed attempt's would be. */
        private const val HOURS_TO_DECIDE = 3_600L

        private val ABSENT_JUDGEMENT = com.monkfitness.app.domain.adaptive.integration.AdaptiveJudgement(
            evidence = EvidenceLevel.INSUFFICIENT,
            confidence = ConfidenceLevel.LOW,
            recovery = RecoveryContext.UNKNOWN
        )

        /** The plan's weekday rhythm the scenarios train on: Monday, Wednesday, Friday. */
        val SESSION_DATES: List<LocalDate> = scheduleFrom(LocalDate.parse("2026-01-05"), sessions = 60)

        /** [count] consecutive Monday/Wednesday/Friday sessions starting on or after [start]. */
        fun scheduleFrom(start: LocalDate, sessions: Int): List<LocalDate> {
            val dates = mutableListOf<LocalDate>()
            var date = start
            while (dates.size < sessions) {
                if (date.dayOfWeek.value in setOf(1, 3, 5)) dates += date
                date = date.plusDays(1)
            }
            return dates
        }
    }
}
