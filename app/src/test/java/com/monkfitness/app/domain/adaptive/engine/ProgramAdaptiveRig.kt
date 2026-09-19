package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant

/**
 * The fixtures the target adaptive stage's suites are written against.
 *
 * It carries no test method of its own: it is the shape of the inputs, written once, so that every suite
 * states its claim in terms of the same window, the same family and the same plan element. The numbers
 * below are the *ordinary* case — a family with two levels, an element the plan generated, full
 * attendance and a favourable context — and every suite that needs an unusual one says so by naming the
 * parameter it changes.
 *
 * The two ladders it builds are exercise-id references and prescriptions, which is exactly what a
 * progression relation is: data the caller supplies. Nothing here is read from the exercise library,
 * `PilotProgressionProfiles` or any other catalogue — the domain owns none, and a fixture that used one
 * would be asserting against a ladder the production code does not have.
 */
object ProgramAdaptiveRig {

    val WINDOW_START: Instant = Instant.parse("2026-03-01T00:00:00Z")
    val CAPTURED_AT: Instant = Instant.parse("2026-03-08T00:00:00Z")
    val DECIDED_AT: Instant = Instant.parse("2026-03-08T00:00:00Z")

    val PROGRAM_ID: ProgramId = ProgramId("program-1")
    val REVISION_ID: RevisionId = RevisionId("revision-1")
    val SLOT_ID: SlotId = SlotId("slot-1")
    val DECISION_ID: DecisionId = DecisionId("decision-1")
    val ADJUSTMENT_ID: AdjustmentId = AdjustmentId("adjustment-1")
    val EARLIER_ADJUSTMENT_ID: AdjustmentId = AdjustmentId("adjustment-0")

    const val PLAN_ELEMENT: String = "plan-element-1"
    const val FAMILY: String = "pushups"

    /** The exercise the ordinary element presents, and the one its family declares at the same level. */
    const val PRESENTED: String = "pushups"
    const val EASIER: String = "pushups_knee"
    const val HARDER: String = "pushups_wide"
    const val SAME_LEVEL_ALTERNATIVE: String = "pushups_military"

    val EVERY_EXERCISE: Set<String> = setOf(PRESENTED, EASIER, HARDER, SAME_LEVEL_ALTERNATIVE)

    // ------------------------------------------------------------------ the plan element

    /** A repetition-based presentation of one plan element. */
    fun presentation(
        exerciseId: String = PRESENTED,
        sets: Int = 3,
        reps: Int = 10
    ): EffectiveExercise = EffectiveExercise(
        programExerciseId = ProgramExerciseId(PLAN_ELEMENT),
        exerciseId = exerciseId,
        prescription = RepPrescription.uniform(sets, reps)
    )

    /** A duration-based presentation of one plan element. */
    fun timedPresentation(
        exerciseId: String = PRESENTED,
        sets: Int = 3,
        seconds: Int = 30
    ): EffectiveExercise = EffectiveExercise(
        programExerciseId = ProgramExerciseId(PLAN_ELEMENT),
        exerciseId = exerciseId,
        prescription = TimePrescription.uniform(sets, seconds)
    )

    /** The element the engine decides about. */
    fun element(
        exerciseId: String = PRESENTED,
        familyId: String = FAMILY,
        ownership: ProgramElementOwnership = ProgramElementOwnership.AUTOMATIC,
        prescription: Prescription = RepPrescription.uniform(3, 10)
    ): ProgramAdaptiveElement = ProgramAdaptiveElement(
        presentation = EffectiveExercise(ProgramExerciseId(PLAN_ELEMENT), exerciseId, prescription),
        familyId = familyId,
        ownership = ownership
    )

    // ------------------------------------------------------------------ the window's facts

    /**
     * One observed occurrence. [index] gives it its own occurrence identity and its own place in the
     * window, so a list of observations is chronological by construction.
     */
    fun observation(
        index: Int,
        prescribed: Int = 3,
        completed: Int = 3,
        exerciseId: String = PRESENTED
    ): ExposureObservation {
        val startedAt = WINDOW_START.plusSeconds((index + 1) * 3_600L)
        return ExposureObservation(
            exerciseId = exerciseId,
            sessionId = SessionId("session-$index"),
            sessionExerciseId = SessionExerciseId("occurrence-$index"),
            level = if (completed >= prescribed) ExposureLevel.FULL else ExposureLevel.PARTIAL,
            completedSets = completed,
            prescribedSets = prescribed,
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(600)
        )
    }

    /** Four comparable occurrences whose recent end is complete and whose older end was not. */
    fun improvement(): List<ExposureObservation> = listOf(
        observation(index = 0, prescribed = 4, completed = 1),
        observation(index = 1, prescribed = 4, completed = 2),
        observation(index = 2, prescribed = 3, completed = 3),
        observation(index = 3, prescribed = 4, completed = 4)
    )

    /** Four comparable occurrences that plateau at the prescription. */
    fun plateau(): List<ExposureObservation> = (0..3).map { observation(index = it) }

    /** Four comparable occurrences whose recent end fell short. */
    fun decline(): List<ExposureObservation> = listOf(
        observation(index = 0, prescribed = 3, completed = 3),
        observation(index = 1, prescribed = 3, completed = 3),
        observation(index = 2, prescribed = 3, completed = 1),
        observation(index = 3, prescribed = 3, completed = 2)
    )

    /** The frozen window. */
    fun snapshot(
        exposures: List<ExposureObservation> = emptyList(),
        evidence: EvidenceLevel = EvidenceLevel.STRONG,
        confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        recovery: RecoveryContext = RecoveryContext.FAVORABLE,
        baselineLoad: LoadProfile = profile(),
        recentLoad: LoadProfile? = null
    ): AdaptiveInputSnapshot = AdaptiveInputSnapshot(
        programId = PROGRAM_ID,
        revisionId = REVISION_ID,
        slotId = SLOT_ID,
        windowStart = WINDOW_START,
        capturedAt = CAPTURED_AT,
        exposures = exposures,
        evidence = evidence,
        confidence = confidence,
        recovery = recovery,
        baselineLoad = baselineLoad,
        recentLoad = recentLoad
    )

    /** A load profile of one scope, with only the channels a claim needs stated. */
    fun profile(
        scope: AdaptiveScope = AdaptiveScope.EXERCISE,
        sets: Int = 0,
        repetitions: Int = 0,
        seconds: Int = 0,
        familyId: String? = FAMILY,
        level: Int? = null,
        restSeconds: Int = 0,
        opportunities: Int = 0,
        completedOpportunities: Int = 0
    ): LoadProfile = LoadProfile(
        scope = scope,
        volume = VolumeLoad(sets = sets, repetitions = repetitions, durationSeconds = seconds),
        intensity = IntensityLoad(
            levels = level?.let { listOf(IntensityEntry(familyId ?: FAMILY, it)) } ?: emptyList()
        ),
        density = DensityLoad(workingSeconds = seconds, restSeconds = restSeconds),
        exposure = ExposureLoad(
            opportunities = opportunities,
            completedOpportunities = completedOpportunities
        )
    )

    // ------------------------------------------------------------------ the family's hierarchy

    /**
     * The family's declared ladder: `pushups_knee` 3×8, `pushups` 3×10, and a third level that is
     * `pushups_wide` at [topReps] repetitions — which is how a suite asks for a step the load guard
     * should refuse, without inventing a second relation.
     *
     * With [sameLevelAlternatives] the middle level declares two variants at the same position, which is
     * the only shape in which §15's *variant* change is bounded: a move that is neither harder nor
     * easier, and that the relation itself names.
     */
    fun relation(
        familyId: String = FAMILY,
        topReps: Int = 10,
        sameLevelAlternatives: Boolean = false
    ): ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = familyId,
        variants = listOf(
            ProgramProgressionVariant(1, EASIER, RepPrescription.uniform(3, 8)),
            ProgramProgressionVariant(2, PRESENTED, RepPrescription.uniform(3, 10))
        ) + listOfNotNull(
            if (sameLevelAlternatives) {
                ProgramProgressionVariant(2, SAME_LEVEL_ALTERNATIVE, RepPrescription.uniform(3, 10))
            } else {
                null
            }
        ) + ProgramProgressionVariant(3, HARDER, RepPrescription.uniform(3, topReps))
    )

    /** The family's maintained facts, with the ordinary case as the default. */
    fun window(
        familyId: String = FAMILY,
        level: Int? = 2,
        state: AdaptiveState = AdaptiveState.HOLD,
        precedingProgressQualifyingWindows: Int = 1,
        precedingRegressQualifyingWindows: Int = 1,
        precedingRecoveryQualifyingWindows: Int = 0,
        qualifyingWindowsSinceLastChange: Int? = null,
        recoveryQualifyingWindows: Int = 0,
        restChangeRequested: Boolean = false
    ): ProgramAdaptiveWindow = ProgramAdaptiveWindow(
        familyId = familyId,
        level = level,
        state = state,
        precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
        precedingRecoveryQualifyingWindows = precedingRecoveryQualifyingWindows,
        qualifyingWindowsSinceLastChange = qualifyingWindowsSinceLastChange,
        recoveryQualifyingWindows = recoveryQualifyingWindows,
        restChangeRequested = restChangeRequested
    )

    // ------------------------------------------------------------------ the request

    fun request(
        snapshot: AdaptiveInputSnapshot = snapshot(),
        element: ProgramAdaptiveElement = element(),
        relation: ProgramProgressionRelation = relation(),
        window: ProgramAdaptiveWindow = window(),
        availableExerciseIds: Set<String> = EVERY_EXERCISE,
        familyOfExercise: Map<String, String> = mapOf(
            EASIER to FAMILY,
            PRESENTED to FAMILY,
            HARDER to FAMILY,
            SAME_LEVEL_ALTERNATIVE to FAMILY
        ),
        supersedesAdjustmentId: AdjustmentId? = null,
        policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1,
        decisionId: DecisionId = DECISION_ID,
        adjustmentId: AdjustmentId = ADJUSTMENT_ID,
        decidedAt: Instant = DECIDED_AT
    ): ProgramAdaptiveRequest = ProgramAdaptiveRequest(
        decisionId = decisionId,
        adjustmentId = adjustmentId,
        decidedAt = decidedAt,
        snapshot = snapshot,
        element = element,
        relation = relation,
        window = window,
        availableExerciseIds = availableExerciseIds,
        familyOfExercise = familyOfExercise,
        supersedesAdjustmentId = supersedesAdjustmentId,
        policy = policy
    )

    // ------------------------------------------------------------------ signals and evidence

    /**
     * Signals built directly, for the policy and signal suites: the counts are stated rather than
     * derived, so a policy claim is never entangled with the calculator's own arithmetic.
     *
     * The two halves default to whatever the whole-window counts are, which is the "measured and steady"
     * case; a suite that needs a trend states both halves explicitly.
     */
    fun signals(
        exposures: ProgramComparableExposure,
        trend: ProgramPerformanceTrend? = null,
        olderHalf: ProgramComparableExposure? = if (trend == null) null else exposures,
        newerHalf: ProgramComparableExposure? = if (trend == null) null else exposures,
        consistency: ProgramConsistency? = null,
        recentContext: ProgramLoadComparison? = null,
        familyId: String = FAMILY
    ): ProgramAdaptiveSignals = ProgramAdaptiveSignals(
        familyId = familyId,
        exposure = exposures,
        trend = trend,
        olderHalf = olderHalf,
        newerHalf = newerHalf,
        consistency = consistency,
        recentContext = recentContext
    )

    /** A comparable-exposure reading. */
    fun exposure(
        exposures: Int,
        prescribedSets: Int,
        completedSets: Int,
        fullExposures: Int = exposures
    ): ProgramComparableExposure = ProgramComparableExposure(
        exposures = exposures,
        fullExposures = fullExposures,
        prescribedSets = prescribedSets,
        completedSets = completedSets
    )

    /** The policy's input for one window. */
    fun evidence(
        window: ProgramAdaptiveWindow = window(),
        signals: ProgramAdaptiveSignals = signals(
            exposures = exposure(exposures = 4, prescribedSets = 12, completedSets = 12),
            trend = ProgramPerformanceTrend.POSITIVE
        ),
        evidence: EvidenceLevel = EvidenceLevel.STRONG,
        confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        recovery: RecoveryContext = RecoveryContext.FAVORABLE
    ): ProgramAdaptiveEvidence = ProgramAdaptiveEvidence(
        window = window,
        signals = signals,
        evidence = evidence,
        confidence = confidence,
        recovery = recovery
    )
}
