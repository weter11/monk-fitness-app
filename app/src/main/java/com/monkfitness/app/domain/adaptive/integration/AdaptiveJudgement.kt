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
 * The three qualitative judgements the engine takes as **inputs**: three fields, three vocabularies,
 * never one composite score and no arithmetic between them.
 */
data class AdaptiveJudgement(
    val evidence: EvidenceLevel,
    val confidence: ConfidenceLevel,
    val recovery: RecoveryContext
)

/**
 * Where the three judgements come from when the caller is the target Program System.
 *
 * This layer holds the frozen window, the history and the policy's thresholds, so it is the only place
 * where a judgement can be *about* the facts the decision is made on, and it reads the engine's own
 * signal layer so that "comparable" means one thing in both stages.
 *
 * An idle window is `UNKNOWN`: inactivity is not favour, and it is not failure either — the
 * reduced-exposure reading is only made about a window in which something was observed. The two
 * judgement inputs with no stored source (per-set difficulty feedback and an explicit user caution) are
 * absent rather than guessed at.
 */
object AdaptiveJudgementRule {

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

    /** How much comparable history exists, as the policy's own exposure buckets. */
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
     * How many comparable exposures, and how many distinct **sessions** they came from: three sets
     * confirmed in one workout and three exposures across three workouts are the same count and very
     * different histories, which is why this is not a relabelling of evidence.
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
            comparableSessions.size < policy.confidenceMinimumDistinctSessions -> ConfidenceLevel.MODERATE
            exposures < policy.progressMinimumExposures -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGH
        }
    }

    /**
     * Read only from the two caution-bearing facts the target tree stores: the recent past sitting above
     * what the plan prescribes, and the plan's own opportunities having gone unattended.
     */
    private fun recoveryOf(signals: ProgramAdaptiveSignals): RecoveryContext = when {
        signals.isIdle -> RecoveryContext.UNKNOWN
        signals.recentIsAboveBaseline -> RecoveryContext.CAUTIOUS
        signals.consistency == ProgramConsistency.LOW -> RecoveryContext.CAUTIOUS
        signals.trend == ProgramPerformanceTrend.NEGATIVE -> RecoveryContext.CAUTIOUS
        else -> RecoveryContext.FAVORABLE
    }

    private fun familyOf(exerciseId: String, familyOfExercise: Map<String, String>): String =
        familyOfExercise[exerciseId] ?: exerciseId

}
