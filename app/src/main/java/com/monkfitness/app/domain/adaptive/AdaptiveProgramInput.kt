package com.monkfitness.app.domain.adaptive

/**
 * Which of the app's two program identities a decision window belongs to: [STANDARD] is the program
 * as first started, [REVISED] one restarted through the C3 "Start Revised Program" action.
 *
 * Context only, and deliberately so. The 56-day calendar and the lifecycle that moves a user between
 * these two identities stay where they already live; nothing in this package reads a calendar rule
 * from this value or changes it. It is carried into the input and echoed on the decision so a stored
 * decision can say which program it was taken under.
 */
enum class ProgramType {
    STANDARD,
    REVISED
}

/**
 * What the caller already knows about one exercise family's adaptation state, already normalized for
 * this layer: the adaptation state it currently holds, the confirmation and recovery counts it has
 * accumulated, and the position of its progression cooldown.
 *
 * The engine reads this and nothing else — it never infers a qualifying-window count, a recovery
 * session count or a cooldown position from the session history it was handed. Those counts are how
 * the policy's hysteresis is carried *between* decision windows, and only the caller (the layer that
 * stores and updates them) knows them.
 *
 * [progressionLevel] is the family's abstract level on its own progression axis. It travels with the
 * state because it is part of what the caller knows, but the engine neither reads nor writes it: the
 * level is changed by the progression layer that maps a family's profile onto a valid next step, and
 * an adaptation decision here says only whether a change is warranted. A RECOVERY decision in
 * particular leaves the stored level alone — recovery orders the recovery load, it does not undo
 * progression the user already earned.
 *
 * [adaptationState] is the *only* state the policy reads back, because RECOVERY's exit gate is
 * counted in qualifying sessions rather than decided from one window's signals. PROGRESS and REGRESS
 * are orders for the window they were produced in and carry no memory.
 */
data class FamilyAdaptationState(
    val familyId: String,

    /** Abstract progression level of the family, `-2..+2`. Carried, never interpreted here. */
    val progressionLevel: Int = 0,

    /** The adaptation state the caller currently holds for this family. */
    val adaptationState: AdaptiveState = AdaptiveState.HOLD,

    /** Consecutive decision windows *before* this one in which the PROGRESS conditions held. */
    val precedingProgressQualifyingWindows: Int = 0,

    /** Consecutive decision windows *before* this one in which the REGRESS conditions held. */
    val precedingRegressQualifyingWindows: Int = 0,

    /** Consecutive decision windows *before* this one that qualified as high risk. */
    val precedingHighRiskWindows: Int = 0,

    /** Qualifying sessions completed while in RECOVERY: the count its exit gate is measured in. */
    val recoveryQualifyingSessions: Int = 0,

    /**
     * Eligible sessions since the family's last progression-level change, or `null` when it has never
     * had one. Deliberately has no default, for the same reason the policy evidence has none:
     * "no change recorded yet" must never be assumed silently, because a wrong cooldown position
     * would let the program expand faster than the user has earned.
     */
    val eligibleSessionsSinceLastProgressionChange: Int?
) {

    init {
        require(familyId.isNotBlank()) { "familyId must not be blank" }
        require(progressionLevel in MIN_LEVEL..MAX_LEVEL) {
            "progressionLevel must be within $MIN_LEVEL..$MAX_LEVEL, was $progressionLevel"
        }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingHighRiskWindows >= 0) {
            "precedingHighRiskWindows must be >= 0, was $precedingHighRiskWindows"
        }
        require(recoveryQualifyingSessions >= 0) {
            "recoveryQualifyingSessions must be >= 0, was $recoveryQualifyingSessions"
        }
        require(
            eligibleSessionsSinceLastProgressionChange == null ||
                eligibleSessionsSinceLastProgressionChange >= 0
        ) {
            "eligibleSessionsSinceLastProgressionChange must be null or >= 0, was " +
                "$eligibleSessionsSinceLastProgressionChange"
        }
    }

    companion object {
        /** The documented bounds of a family's abstract progression level. */
        const val MIN_LEVEL: Int = -2
        const val MAX_LEVEL: Int = 2

        /**
         * The state of a family the caller has no recorded adaptation state for: HOLD, no qualifying
         * windows, no recovery sessions, and no recorded level change to cool down from.
         *
         * It is conservative by construction rather than by claim. A family in this state reports no
         * preceding windows, and both confirmed transitions need them, so it cannot PROGRESS or
         * REGRESS in the window it first appears in — the decision is HOLD / INSUFFICIENT_EVIDENCE.
         * The one path it can still take is RECOVERY, which is exactly the path that must not wait
         * for bookkeeping: high recent load with deterioration is a safety signal about the window,
         * not about the family's recorded history.
         */
        fun notYetTracked(familyId: String): FamilyAdaptationState = FamilyAdaptationState(
            familyId = familyId,
            eligibleSessionsSinceLastProgressionChange = null
        )
    }
}

/**
 * One decision window's input: already-normalized domain data the engine is allowed to look at, with
 * nothing left for it to look up. Every value here is a fact the caller establishes — the calendar
 * context, which exercises are enabled, the observed session history, the per-family adaptation state
 * the caller maintains, and the exercise-to-family mapping that decides the granularity of a family.
 *
 * What the engine deliberately cannot see:
 *
 *  * no repository, DAO or preference store — the history arrives as [recentSessions];
 *  * no exercise library — a family is a caller-supplied id, and an exercise the map does not know is
 *    its own family, exactly as the signal layer groups it;
 *  * no clock and no calendar arithmetic beyond carrying [programDay], [programCycle] and
 *    [programType] into the decision;
 *  * no custom-configuration behavior: [enabledExerciseIds] is context, not a selection to satisfy.
 *    The engine never re-enables a family the caller left out and never picks a concrete exercise.
 *
 * [currentProgressionStates] may cover fewer families than [enabledExerciseIds] resolves to; a family
 * with no state entry is evaluated as [FamilyAdaptationState.notYetTracked].
 */
data class AdaptiveProgramInput(
    /** Program day within the cycle (1-based), as the existing calendar defines it. */
    val programDay: Int,

    /** Program cycle number (1-based), as the existing calendar defines it. */
    val programCycle: Int,

    /** Which program identity this window belongs to. Context only. */
    val programType: ProgramType = ProgramType.STANDARD,

    /** The exercise ids the caller's configuration currently enables, in no particular order. */
    val enabledExerciseIds: List<String>,

    /** The observed planned opportunities, in any order; the signal layer orders them. */
    val recentSessions: List<SessionObservation>,

    /** The caller's adaptation state per family, at most one entry per family. */
    val currentProgressionStates: List<FamilyAdaptationState> = emptyList(),

    /**
     * Exercise id to family id, for callers that know the exercise library. An exercise the map does
     * not contain is its own family — the same rule the signal layer groups trends by, so a family id
     * here always matches a key of `AdaptiveSignals.performanceTrends`.
     */
    val familyOfExercise: Map<String, String> = emptyMap(),

    /** The single source of every window, threshold and confirmation count the decision uses. */
    val policy: AdaptivePolicy = AdaptivePolicy.V1
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(enabledExerciseIds.none { it.isBlank() }) {
            "enabledExerciseIds must not contain a blank id, were $enabledExerciseIds"
        }
        val families = currentProgressionStates.map { it.familyId }
        require(families.toSet().size == families.size) {
            "currentProgressionStates must carry at most one state per family, were $families"
        }
    }
}
