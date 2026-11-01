package com.monkfitness.app.domain.adaptive.integration

/**
 * The result of one adaptive pass: one outcome per family the completed session exposed, in the order the
 * session presents them.
 *
 * Expected states are values inside [Success], never exceptions: "the next opportunity presents a family
 * you did not train" is a gap and a held window is `NothingToAdapt`, both of which mean the pass did its
 * job — so a caller cannot accidentally treat "no future slot" as a failure. A pass that cannot begin at
 * all answers one `CannotBuildAdaptiveRequest`, so the list is never empty.
 *
 * `INVALID_DATA` is not folded into [Failure] because the two ask different things: a system failure is
 * retried, an invalid one is a defect in what storage holds. The split is a guard — a `require`/`check`
 * refusal raised by the domain's own values means stored facts violate an invariant — and both keep the
 * cause. `CONFLICT` is absent because this pass writes nothing and holds no optimistic token.
 */
sealed interface AdaptiveIntegrationResult {

    data class Success(val outcomes: List<AdaptiveIntegrationOutcome>) : AdaptiveIntegrationResult {

        init {
            require(outcomes.isNotEmpty()) {
                "a pass that produced no outcome is a gap, not an empty success"
            }
        }
    }

    data class InvalidData(val cause: Throwable) : AdaptiveIntegrationResult

    data class Failure(val cause: Throwable) : AdaptiveIntegrationResult
}
