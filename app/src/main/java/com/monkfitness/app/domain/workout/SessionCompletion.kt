package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * What the adaptive stage decided about one completion, as a value the caller hands in — the seam between
 * the runtime (session and opportunity) and the adaptive stage (state, decisions, adjustments), so the
 * runtime never has to invent one.
 *
 * A session trains more than one family, so the adaptive half of a completion is one leg **per family**:
 * [Completions]. The single-family shapes remain what a leg is made of, and a completion that evaluated
 * one family is that leg rather than a list of one.
 *
 * The shapes differ where it matters: [NothingDecided] writes nothing at all, because no window was
 * evaluated; [WindowEvaluated] advances the family's own bookkeeping even when the engine held; [Decided]
 * stores a decision filtered out by the aggregate load guard as `NOT_APPLIED` rather than dropping it; and
 * [NothingToAdapt] is a family whose window was read with no position to advance, so it writes nothing
 * while still appearing in the report.
 *
 * The family's state travels with the completion and is written in the same transaction as the session.
 * An `APPLIED` decision always carries it: an applied change moved the family to a variant, and a change
 * whose family position was not written could not be explained afterwards.
 */
sealed interface AdaptiveCompletion {

    /** The family this leg is about; `null` only for a completion that is not about one family. */
    val familyId: String?

    /**
     * No window was evaluated: no family state, no decision and no adjustment is written, and the
     * completion reports that explicitly rather than fabricating a `NOT_APPLIED` decision with its target,
     * action, evidence and confidence — a decision records something that was decided, and nothing was.
     */
    data object NothingDecided : AdaptiveCompletion {

        override val familyId: String?
            get() = null
    }

    /**
     * One window was evaluated and the engine held: the family's own state after it is written, and nothing
     * else. [familyState] is the single current state of one family in one revision, carrying the
     * confirmation counts, cooldown position and recovery exit count this window advanced — not a decision
     * record, which is why no decision row is written with it.
     */
    data class WindowEvaluated(
        val familyState: FamilyProgressionState
    ) : AdaptiveCompletion {

        override val familyId: String
            get() = familyState.familyId
    }

    /**
     * One decision, and its adjustment when it applied one. [adjustment] is present exactly when the
     * decision is `APPLIED`, and absent for one that was filtered out. The decision must be about a future
     * opportunity of this completion's Program and revision: the target is where the adjustment is
     * consumed, and filing a change against the just-completed opportunity would file it under a history
     * nothing can apply it to.
     */
    data class Decided(
        val decision: AdaptiveDecision,
        val adjustment: AdaptiveAdjustment? = null,
        val familyState: FamilyProgressionState? = null
    ) : AdaptiveCompletion {

        init {
            require((decision.outcome == DecisionOutcome.APPLIED) == (adjustment != null)) {
                "an applied decision produced exactly one adjustment and a not-applied one produced " +
                    "none: outcome=${decision.outcome} adjustment=${adjustment?.adjustmentId?.value}"
            }
            require(decision.outcome != DecisionOutcome.APPLIED || familyState != null) {
                "an applied change travels with the family's state after the window (§27's 'adaptive " +
                    "state' leg): decision='${decision.decisionId.value}'"
            }
            require(adjustment == null || adjustment.decisionId == decision.decisionId) {
                "an adjustment belongs to the decision that produced it"
            }
            require(familyState == null || familyState.familyId.isNotBlank()) {
                "the state leg names the family it is about"
            }
        }

        override val familyId: String?
            get() = familyState?.familyId
                ?: (decision.target as? AdaptiveTarget.Family)?.familyId
    }

    /**
     * A family's window was read and there is no position to advance, so nothing is written for it. The
     * family still appears in the completion's report: "read, nothing to write" is a different fact from
     * "not evaluated at all".
     */
    data class NothingToAdapt(val family: String) : AdaptiveCompletion {

        init {
            require(family.isNotBlank()) { "a leg names the family it is about" }
        }

        override val familyId: String
            get() = family
    }

    /**
     * One leg per family, in the order the completion decided them. The families are distinct — a session
     * completes one window per family, and the target opportunity is one — so a family cannot appear twice.
     */
    data class Completions(val legs: List<AdaptiveCompletion>) : AdaptiveCompletion {

        init {
            require(legs.isNotEmpty()) { "a completion with no leg is NothingDecided, not an empty list" }
            require(legs.none { it is Completions || it is NothingDecided }) {
                "a completion's legs are per-family shapes: they do not nest and none of them is empty"
            }
            val families = legs.map { it.familyId }
            require(families.none { it == null } && families.distinct().size == legs.size) {
                "one leg per family, and every leg names its family: $families"
            }
        }

        override val familyId: String?
            get() = null
    }

    companion object {

        /**
         * The smallest shape for [legs]: nothing decided, one leg as itself, several as [Completions]. A leg
         * that writes nothing — a family whose window produced no row — is not a leg of the completion.
         */
        fun of(legs: List<AdaptiveCompletion>): AdaptiveCompletion {
            val writing = legs.filterNot { leg -> leg is NothingDecided }
            return when (writing.size) {
                0 -> NothingDecided
                1 -> writing.single()
                else -> Completions(writing)
            }
        }
    }
}

/** What one completion wrote of the adaptive half, as a value a caller can present. */
sealed interface AdaptiveOutcome {

    /** Nothing adaptive was written, because no window was evaluated. */
    data object NothingDecided : AdaptiveOutcome

    /**
     * One decision was stored — with the adjustment it produced, or alone when it was filtered out
     * ([adjustmentId] is `null` then).
     */
    data class Stored(val decisionId: DecisionId, val adjustmentId: AdjustmentId?) : AdaptiveOutcome

    /**
     * One entry per family the completion carried, in its own order, whether or not that family stored a
     * decision: a window that only advanced bookkeeping is still a result about that family.
     */
    data class PerFamily(val families: List<Family>) : AdaptiveOutcome {

        init {
            require(families.isNotEmpty()) { "a completion that carried no family is NothingDecided" }
        }
    }

    /** One family's share of a completion: the decision it stored and the adjustment that came with it. */
    data class Family(
        val familyId: String,
        val decisionId: DecisionId?,
        val adjustmentId: AdjustmentId?
    ) {

        init {
            require(familyId.isNotBlank()) { "a result names the family it is about" }
            require(adjustmentId == null || decisionId != null) {
                "an adjustment belongs to the decision that produced it"
            }
        }
    }
}

/**
 * One finished workout: the session as it now stands, the opportunity it took, and what the completion
 * wrote of the adaptive half.
 *
 * [session] and [slot] are read back from storage after the unit of work, not the values the caller handed
 * in, so what is reported is what a process restart would load. [adaptive] says explicitly whether that
 * unit included an adaptive row, which is what makes "a completion that applied nothing"
 * distinguishable from "a completion whose adaptive write was lost".
 */
data class SessionCompletion(
    val session: WorkoutSession,
    val slot: WorkoutSlot,
    val adaptive: AdaptiveOutcome
) {

    val recordedAnAdaptiveDecision: Boolean
        get() = when (val reported = adaptive) {
            AdaptiveOutcome.NothingDecided -> false
            is AdaptiveOutcome.Stored -> true
            is AdaptiveOutcome.PerFamily -> reported.families.any { family -> family.decisionId != null }
        }
}
