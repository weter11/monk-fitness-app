package com.monkfitness.app.domain.adaptive.integration

/**
 * The result of one adaptive pass, in §28's classes.
 *
 * ```text
 * Success      an outcome was produced — adaptive or explicitly not (§20's four shapes)
 * InvalidData  stored facts that cannot be true: adaptive data the domain refuses (INVALID_DATA)
 * Failure      anything else that went wrong while reading (SYSTEM_FAILURE)
 * ```
 *
 * ### Expected results are values, and they live inside [Success]
 *
 * §28 is explicit that expected states are results rather than exceptions: *"the next opportunity
 * presents a family you did not train"* is [AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest] with
 * a gap, and a window the engine held on is [AdaptiveIntegrationOutcome.NothingToAdapt]. Both are
 * [Success] — the pass did its job and the job's answer was *"nothing changes"* — which is why a caller
 * cannot accidentally treat *"no future slot"* as a failure.
 *
 * ### Why `INVALID_DATA` is not folded into [Failure]
 *
 * §28 names the two separately and they ask different things of a caller: a `SYSTEM_FAILURE` is retried,
 * and an `INVALID_DATA` is a defect in what storage holds. The rule this integration applies is
 * mechanical rather than a guess: a **guard**. A `require`/`check` refusal raised by the domain's own
 * values (the engine's construction rules, a mapper's token check, `PersistedDecisionReason`) means the
 * stored facts violate a domain invariant, and it surfaces as [InvalidData]; anything else — a DAO, a
 * driver, a failure with no other explanation — surfaces as [Failure]. Both keep the cause, so nothing
 * is swallowed into an empty result (§28, §33).
 *
 * `CONFLICT` is deliberately absent: nothing in this pass can be in conflict. It writes nothing, it
 * creates no revision, it takes no opportunity and it holds no optimistic token — a completion whose
 * adaptive leg has become stale is refused by the runtime that writes it, which is where the conflict
 * belongs (§4, §19).
 */
sealed interface AdaptiveIntegrationResult {

    /** The pass produced an outcome. */
    data class Success(val outcome: AdaptiveIntegrationOutcome) : AdaptiveIntegrationResult

    /** Stored adaptive facts that cannot be true: the domain refused them, loudly (§28 `INVALID_DATA`). */
    data class InvalidData(val cause: Throwable) : AdaptiveIntegrationResult

    /** The pass could not read what it needed (§28 `SYSTEM_FAILURE`). */
    data class Failure(val cause: Throwable) : AdaptiveIntegrationResult
}
