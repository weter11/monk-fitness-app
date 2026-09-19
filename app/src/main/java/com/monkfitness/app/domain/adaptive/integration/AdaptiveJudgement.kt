package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveSignalCalculator
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveSignals
import com.monkfitness.app.domain.adaptive.engine.ProgramConsistency
import com.monkfitness.app.domain.adaptive.engine.ProgramPerformanceTrend

/**
 * The three qualitative judgements the engine takes as **inputs** (§12, §14), kept apart.
 *
 * Evidence says how much comparable history exists; confidence says how far that history can be trusted
 * for this decision; recovery says what context the decision is being made in. §11 and §30 step 11 are
 * explicit that they are three inputs and never one composite score, and this type keeps that shape at
 * the integration boundary exactly as the engine keeps it at its own: three fields, three vocabularies,
 * no arithmetic between them.
 *
 * @property evidence how much comparable history the window holds.
 * @property confidence how far that history can be trusted for this decision.
 * @property recovery the recovery context the decision is made in.
 */
data class AdaptiveJudgement(
    val evidence: EvidenceLevel,
    val confidence: ConfidenceLevel,
    val recovery: RecoveryContext
)

/**
 * Where the three judgements come from when the caller is the target Program System.
 *
 * ### Why a rule is needed at all
 *
 * §30 step 11 takes the three levels as inputs and states that the layer which owns the history states
 * them. That layer is this one: the integration holds the frozen window, the history it was assembled
 * from and the policy's own thresholds, so it is the only place where a judgement can be *about* the
 * facts the decision will be made on.
 *
 * ### What it derives, and from what
 *
 * Every number below is a fact of the window and of the policy — nothing is a new measurement, a new
 * threshold, a score or a coefficient (§17 prohibits a universal load scalar, and §12 prohibits fake
 * precision). The rule reads the signals the *engine's own* signal layer derives, so the question
 * *"which of these observations are comparable?"* is answered by one implementation
 * ([ProgramAdaptiveSignalCalculator]) rather than two.
 *
 * ```text
 * evidence    comparable exposures below the trend minimum      → INSUFFICIENT  (nothing to measure)
 *             at or above it, below the progression minimum      → STABLE        (a picture, no direction)
 *             at or above the progression minimum               → STRONG        (a direction is measurable)
 *
 * confidence  fewer than the trend minimum                      → LOW           (nothing can be trusted yet)
 *             else, from a single session, or below what a
 *             change needs                                       → MODERATE
 *             else, at least what a change needs, from more
 *             than one session                                   → HIGH
 *
 * recovery    the window observed this family's work and
 *             neither the recent context is above the plan nor
 *             the plan's own opportunities went unattended      → FAVORABLE
 *             the recent context is above the plan, or the
 *             plan's opportunities went unattended (LOW)         → CAUTIOUS
 *             the window observed nothing for this family at
 *             all                                                → UNKNOWN
 * ```
 *
 * ### What it deliberately does not do
 *
 *  * **it never turns missing data into "OK".** An idle window — one in which the family was not
 *    observed — is `UNKNOWN`, never `FAVORABLE`, because inactivity is not favour (it is also not
 *    failure, which is why it is not `CAUTIOUS` either: §14's "reduced exposure" reading is only made
 *    about a window in which something was observed);
 *  * **it invents no fourth scale.** All three levels are the foundation's own vocabularies, and none
 *    of them is converted into a number;
 *  * **it names what is missing rather than faking it.** Two §13/§14 inputs have no target-side source
 *    and are therefore absent from the rule: the user's per-set difficulty feedback (`EASY` / `OK` /
 *    `HARD`, §13, which has no stored column) and an explicit user caution (§14, which has no UI and no
 *    storage). Both would sharpen `confidence` and `recovery`; neither is guessed at. The absence is
 *    recorded in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` rather than hidden behind a default;
 *  * **it states a redundant fact on purpose, and says so.** The evidence buckets are drawn at the
 *    policy's own two exposure minimums, so `STRONG` coincides with the count gate §7's progression
 *    conditions already apply. That redundancy is deliberate: §12's three levels *are* three readings of
 *    "how much comparable history is there", the counts are the only history fact the target tree
 *    stores, and a basis invented out of the facts that do exist (recency weighting, completeness
 *    ratios, a blended score) would be the fabricated precision the architecture rejects. A caller with
 *    better facts — a feedback channel, a stored difficulty rating — replaces this rule; the engine does
 *    not care where its inputs came from.
 */
object AdaptiveJudgementRule {

    /**
     * The judgement for one window.
     *
     * @param snapshot the frozen window, whose observations are the history the judgement is about.
     * @param familyOfExercise the caller's exercise-to-family classification, read exactly as the signal
     *   layer reads it so that "comparable" means one thing in this stage.
     * @param familyId the family the decision is about.
     * @param policy the policy whose two exposure minimums the buckets are drawn at. No threshold is
     *   restated here.
     */
    fun of(
        snapshot: AdaptiveInputSnapshot,
        familyOfExercise: Map<String, String>,
        familyId: String,
        policy: ProgramAdaptivePolicy
    ): AdaptiveJudgement {
        val signals = ProgramAdaptiveSignalCalculator.calculate(
            snapshot = snapshot,
            familyOfExercise = familyOfExercise,
            familyId = familyId,
            policy = policy
        )
        return AdaptiveJudgement(
            evidence = evidenceOf(signals, policy),
            confidence = confidenceOf(snapshot, familyOfExercise, familyId, signals, policy),
            recovery = recoveryOf(signals)
        )
    }

    /** §12's "how much comparable history exists", as the policy's own exposure buckets. */
    private fun evidenceOf(
        signals: ProgramAdaptiveSignals,
        policy: ProgramAdaptivePolicy
    ): EvidenceLevel {
        val exposures = signals.exposure.exposures
        return when {
            exposures < policy.trendMinimumExposures -> EvidenceLevel.INSUFFICIENT
            exposures < policy.progressMinimumExposures -> EvidenceLevel.STABLE
            else -> EvidenceLevel.STRONG
        }
    }

    /**
     * §12's "how far that history can be trusted for this decision".
     *
     * Two facts raise it and neither is a ratio: how many comparable exposures the window holds, and how
     * many distinct **sessions** they came from. The second is why this is not a relabelling of evidence
     * — three sets confirmed in one workout and three exposures spread across three workouts are the
     * same count and very different histories — and it is read from the observations' own session
     * identities, which is the only occurrence-level identity the target tree stores.
     */
    private fun confidenceOf(
        snapshot: AdaptiveInputSnapshot,
        familyOfExercise: Map<String, String>,
        familyId: String,
        signals: ProgramAdaptiveSignals,
        policy: ProgramAdaptivePolicy
    ): ConfidenceLevel {
        val exposures = signals.exposure.exposures
        if (exposures < policy.trendMinimumExposures) return ConfidenceLevel.LOW

        val comparableSessions = snapshot.exposures
            .filter { familyOf(it.exerciseId, familyOfExercise) == familyId }
            .map { it.sessionId }
            .distinct()

        return when {
            comparableSessions.size < MINIMUM_DISTINCT_SESSIONS -> ConfidenceLevel.MODERATE
            exposures < policy.progressMinimumExposures -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGH
        }
    }

    /**
     * §14's context, read from the window's own comparison against the plan.
     *
     * The two caution-bearing facts are §14's own inputs that the target tree stores: the recent past
     * sitting **above** what the plan itself prescribes (the load is already more than the plan asked
     * for), and the plan's own opportunities having gone **unattended** (the user's own week says
     * something about how much is being absorbed). Everything else the rule could read — performance
     * trend, shortfall, inactivity — is either already read by the policy's own recovery state machine
     * or an absence, and neither is turned into a favourable statement here.
     */
    private fun recoveryOf(signals: ProgramAdaptiveSignals): RecoveryContext = when {
        signals.isIdle -> RecoveryContext.UNKNOWN
        signals.recentIsAboveBaseline -> RecoveryContext.CAUTIOUS
        signals.consistency == ProgramConsistency.LOW -> RecoveryContext.CAUTIOUS
        signals.trend == ProgramPerformanceTrend.NEGATIVE -> RecoveryContext.CAUTIOUS
        else -> RecoveryContext.FAVORABLE
    }

    /** The family an exercise belongs to, by the caller's classification; an unknown one is its own. */
    private fun familyOf(exerciseId: String, familyOfExercise: Map<String, String>): String =
        familyOfExercise[exerciseId] ?: exerciseId

    /**
     * How many distinct sessions a comparable history needs before it is more than one occasion.
     *
     * One, not two, because this is a *count to exceed*: a history from a single session is one
     * occasion, and two occasions are the first thing that can be called a history rather than a result.
     * It is an integer count of occurrences, not a coefficient.
     */
    private const val MINIMUM_DISTINCT_SESSIONS = 2
}
