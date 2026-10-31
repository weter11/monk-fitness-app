package com.monkfitness.app.domain.adaptive.integration

/**
 * The result of one adaptive pass.
 *
 * Expected states are values inside [Success], never exceptions: "the next opportunity presents a family
 * you did not train" is a gap and a held window is `NothingToAdapt`, both of which mean the pass did its
 * job — so a caller cannot accidentally treat "no future slot" as a failure.
 *
 * `INVALID_DATA` is not folded into [Failure] because the two ask different things: a system failure is
 * retried, an invalid one is a defect in what storage holds. The split is a guard — a `require`/`check`
 * refusal raised by the domain's own values means stored facts violate an invariant — and both keep the
 * cause. `CONFLICT` is absent because this pass writes nothing and holds no optimistic token.
 */
sealed interface AdaptiveIntegrationResult {

    data class Success(val outcome: AdaptiveIntegrationOutcome) : AdaptiveIntegrationResult

    data class InvalidData(val cause: Throwable) : AdaptiveIntegrationResult

    data class Failure(val cause: Throwable) : AdaptiveIntegrationResult
}
