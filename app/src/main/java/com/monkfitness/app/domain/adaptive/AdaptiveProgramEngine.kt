package com.monkfitness.app.domain.adaptive

/**
 * One decision window's auditable result: a family-scoped [AdaptiveDecision] per evaluated family,
 * plus the program context the window belonged to and the policy version the decisions were taken
 * under.
 *
 * Every field is a value, so the same input always yields the same program decision, and a stored
 * decision is reproducible from its own contents. [families] is ordered by ascending family id — the
 * one stable order this layer has — so two runs over the same window are comparable line by line.
 *
 * There are no user-facing strings here and no persistence identity: the reason codes are stable
 * domain identifiers the UI layer localizes, and storing a decision is a later stage's concern.
 */
data class AdaptiveProgramDecision(
    val programDay: Int,
    val programCycle: Int,
    val programType: ProgramType,

    /** One decision per evaluated family, ordered by ascending family id. */
    val families: List<AdaptiveDecision>,

    val policyVersion: Int
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(policyVersion >= 1) { "policyVersion must be >= 1, was $policyVersion" }
        val familyIds = families.map { it.familyId }
        require(familyIds.all { !it.isNullOrBlank() }) {
            "every family decision must carry its family id, were $familyIds"
        }
        require(familyIds.toSet().size == familyIds.size) {
            "a family is a single decision in a window, were $familyIds"
        }
        require(familyIds.filterNotNull() == familyIds.filterNotNull().sorted()) {
            "family decisions must be ordered by ascending family id, were $familyIds"
        }
    }
}

/**
 * The Stage 1 orchestration layer: a pure function from one window's input to one family-scoped
 * decision per evaluated family.
 *
 * ```
 * recentSessions  →  AdaptiveSignalCalculator  →  AdaptiveSignals
 *                 →  AdaptivePolicy            →  AdaptiveDecision (per family)
 * ```
 *
 * It orchestrates those two and nothing else. In particular it holds no threshold, window,
 * confirmation count or state-machine condition of its own: the numbers live in [AdaptivePolicy] and
 * are read from the policy the caller supplied, because a second copy of a rule is a second rule that
 * can disagree. Everything the policy needs beyond the signals — the family's current adaptation
 * state, its accumulated qualifying-window counts, its recovery session count and its cooldown
 * position — is supplied by the caller per family and passed through unchanged.
 *
 * ## Family scope
 *
 * Adaptation is decided per exercise family, not once for the whole program: one window may progress
 * the family the user is handling while holding another, and the families the window evaluates are
 * derived deterministically from the caller's own data — the families of the enabled exercises, by
 * the caller's exercise-to-family map, deduplicated and sorted. An exercise the map does not know is
 * its own family, exactly as the signal layer groups its trend. A family no enabled exercise maps to
 * is not evaluated at all: the engine never re-enables an exercise the caller's configuration left
 * out, and never substitutes a different exercise for one it cannot adapt.
 *
 * ## The two defaults that matter
 *
 *  * **No measured family trend.** The signals report a trend per family only when that family has
 *    enough exposures to establish one. Where no trend was measured, the family is judged with
 *    [PerformanceTrend.STABLE]: an unmeasured trend is never reported as POSITIVE and never as
 *    NEGATIVE, so it cannot be the reason a family regresses, and it cannot be invented for a family
 *    the history did not measure. The engine does not fall back to the whole-workout trend — that
 *    trend describes the window, not this family.
 *  * **No supplied family state.** A family the caller has no state for is evaluated as
 *    [FamilyAdaptationState.notYetTracked], whose documented composition can only hold or take the
 *    recovery safety path. The engine invents no qualifying-window count, no cooldown position and
 *    no recovery session count for it.
 *
 * ## What it does not do
 *
 * It reads no clock, no random source, no mutable global state and no storage, and it resolves no
 * progression level: the decision orders a stimulus change and names no exercise, variation or
 * duration. Which concrete step an order maps to is the progression layer's decision, and turning a
 * workout into concrete exercises stays with the workout generator.
 *
 * ## Recovery semantics it preserves
 *
 * Recovery outranks normal progression, is never cooldown-gated, is not a progression or regression
 * of the level, exits to HOLD and never straight to PROGRESS, and leaves the stored progression level
 * untouched. The engine keeps all of that intact by not interpreting it: the signal layer's
 * `recoveryRisk.pattern` and flag readings are deliberately *not* turned into policy evidence — a
 * pattern is a per-window reading, and the confirmation of a RECOVERY transition stays with
 * [AdaptivePolicy.evaluate], which re-derives the condition from the window and counts the qualifying
 * windows the caller reported.
 */
object AdaptiveProgramEngine {

    /**
     * The trend a family is judged on when the signal layer measured none for it. [PerformanceTrend.STABLE]
     * is the neutral bucket, so an unmeasured family is never the subject of a fabricated POSITIVE or
     * NEGATIVE claim — it simply carries no direction of its own.
     */
    private val UNMEASURED_FAMILY_TREND = PerformanceTrend.STABLE

    /** The deterministic program decision for one decision window. */
    fun evaluate(input: AdaptiveProgramInput): AdaptiveProgramDecision {
        val policy = input.policy
        val signals = AdaptiveSignalCalculator.calculate(
            history = input.recentSessions,
            policy = policy,
            familyOfExercise = input.familyOfExercise
        )
        val knownStates = input.currentProgressionStates.associateBy { it.familyId }

        val decisions = evaluatedFamilies(input).map { familyId ->
            val state = knownStates[familyId] ?: FamilyAdaptationState.notYetTracked(familyId)
            policy.evaluate(evidenceOf(familyId, state, signals)).copy(familyId = familyId)
        }

        return AdaptiveProgramDecision(
            programDay = input.programDay,
            programCycle = input.programCycle,
            programType = input.programType,
            families = decisions,
            policyVersion = policy.version
        )
    }

    /**
     * The families this window evaluates: the enabled exercises mapped to their family, deduplicated
     * and sorted ascending. Deterministic for any input ordering, so the decision's family rows and
     * their order never depend on how the caller assembled its list.
     */
    private fun evaluatedFamilies(input: AdaptiveProgramInput): List<String> =
        input.enabledExerciseIds
            .map { exerciseId -> input.familyOfExercise[exerciseId] ?: exerciseId }
            .distinct()
            .sorted()

    /**
     * The policy evidence for one family: the window's shared signals, that family's own measured
     * trend where there is one, and the state the caller supplied for it.
     *
     * The window-level values are shared on purpose — exposure, adherence, recent load and the
     * recovery-risk inputs describe what the observed window shows, and the signal layer publishes one
     * such reading per window. What is family-specific is the performance trend, which the signal
     * layer measured from that family's own exposures.
     *
     * The attendance bucket and the recovery-risk reading are deliberately not passed on: the policy's
     * v1 evidence contract does not take them, and a risk pattern in particular is the per-window
     * reading whose confirmation the policy owns — handing it over as state would let the window
     * confirm itself.
     */
    private fun evidenceOf(
        familyId: String,
        state: FamilyAdaptationState,
        signals: AdaptiveSignals
    ): AdaptiveEvidence = AdaptiveEvidence(
        currentState = state.adaptationState,
        eligibleSessionCount = signals.eligibleSessionCount,
        exposureScore = signals.exposureScore,
        adherence = signals.adherence,
        performanceTrend = signals.performanceTrends[familyId] ?: UNMEASURED_FAMILY_TREND,
        recentLoadBucket = signals.recentLoadBucket,
        precedingProgressQualifyingWindows = state.precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = state.precedingRegressQualifyingWindows,
        precedingHighRiskWindows = state.precedingHighRiskWindows,
        recoveryQualifyingSessions = state.recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = state.eligibleSessionsSinceLastProgressionChange
    )
}
