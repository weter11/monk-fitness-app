package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.workout.AdaptiveCompletion

/**
 * What one adaptive pass produced — the whole result surface of §30 step 12 (§20).
 *
 * Four outcomes, and the distinctions between them are the reason this is a sealed hierarchy rather than
 * a nullable decision:
 *
 * ```text
 * AdaptiveApplied            a change was resolved and applied    → decision + adjustment + state leg
 * AdaptiveFiltered           a change was resolved and refused    → the NOT_APPLIED decision + state leg
 * NothingToAdapt             the engine held                      → the state leg alone
 * CannotBuildAdaptiveRequest no window could be evaluated         → nothing at all
 * ```
 *
 * [completion] is what the completion transaction writes: it is the value handed to
 * `SessionRuntime.finishSession`, and it is derived from the outcome rather than assembled by the
 * caller, so *"a decision that applied nothing"* and *"no window was evaluated"* cannot be confused at
 * the seam where §27's unit of work is opened.
 *
 * ### The one thing worth arguing about: why [NothingToAdapt] still writes the family's state
 *
 * A held window takes no decision row and no adjustment — nothing was decided — and it *does* advance
 * the family's own window bookkeeping ([FamilyProgressionState]'s five counters). That follows from
 * arithmetic the policy states: a direction is confirmed only when it holds in
 * `progressConfirmingWindows` **consecutive** windows, so the count of preceding qualifying windows has
 * to advance through the windows that did not change anything — if only applied and filtered windows
 * advanced it, the counter could never reach the confirmation count and the adaptive stage could never
 * progress a family at all. The state row is not a decision record: it is §23's one *current* row per
 * family per revision, whose own primary key says so, and a family that has been measured has a current
 * state whether or not anything was changed about it.
 *
 * [CannotBuildAdaptiveRequest] is the other side of that line: no window was evaluated, so nothing at
 * all is written — not even a state row. §12's *"no decision means nothing"* is honoured exactly there,
 * and the distinction is a tested one.
 */
sealed interface AdaptiveIntegrationOutcome {

    /** What §27's completion writes of the adaptive half. */
    val completion: AdaptiveCompletion

    /** Whether this pass produced a change the user will be presented with. */
    val isApplied: Boolean
        get() = this is AdaptiveApplied

    /**
     * A change was resolved **and applied**: the decision, the adjustment that carries it, and the
     * family's state after the window.
     *
     * @property decision the applied decision (§16).
     * @property adjustment the before/after of one element of the target opportunity (§16).
     * @property familyState the family's state after this window (§23).
     * @property reason the single rule that resolved it — `SUSTAINED_POSITIVE`, `SUSTAINED_NEGATIVE` or
     *   `VARIANT_REALIGNED`.
     */
    data class AdaptiveApplied(
        val decision: AdaptiveDecision,
        val adjustment: AdaptiveAdjustment,
        val familyState: FamilyProgressionState,
        val reason: ProgramAdaptiveReason
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason.isChange) {
                "an applied change is answered by one of the three change reasons: reason=$reason"
            }
            require(decision.reason == reason) {
                "the decision the completion records carries the reason that produced it: " +
                    "decision=${decision.reason} reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.Decided(decision, adjustment, familyState)
    }

    /**
     * A change was resolved and §18's aggregate load guard **refused** it.
     *
     * The decision is kept as `NOT_APPLIED` — §18 is explicit that a filtered-out decision remains
     * recorded rather than disappearing, and it is the only `NOT_APPLIED` shape this integration
     * persists — while no adjustment is written, because no change happened.
     *
     * @property decision the filtered decision, whose reason is `AGGREGATE_LOAD_GUARD`.
     * @property familyState the family's state after the window. A filtered change leaves the family
     *   where it was: only §14's recovery, which is a state and not a change, is reported through.
     * @property reason the refusal's own reason.
     */
    data class AdaptiveFiltered(
        val decision: AdaptiveDecision,
        val familyState: FamilyProgressionState,
        val reason: ProgramAdaptiveReason
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason == ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD) {
                "the only decision this integration keeps as NOT_APPLIED is one the aggregate load " +
                    "guard refused (§18): reason=$reason"
            }
            require(decision.reason == reason) {
                "the decision the completion records carries the reason that produced it: " +
                    "decision=${decision.reason} reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.Decided(decision, adjustment = null, familyState = familyState)
    }

    /**
     * The engine was asked and held: no change, and no decision record.
     *
     * [reason] is the engine's own rule token — `STABLE_PLATEAU`, `INSUFFICIENT_EVIDENCE`,
     * `AWAITING_CONFIRMATION`, `PROGRESSION_COOLDOWN`, `CEILING_REACHED`, `USER_AUTHORED_ELEMENT`,
     * `PROGRESSION_UNAVAILABLE` and the rest — so *"the program decided not to change anything, and
     * here is which rule decided that"* is a value. It is deliberately **not** persisted: a hold is a
     * reading of one window that the same window recomputes identically, and writing a row for every
     * completed workout would bury the two decision shapes §16 and §18 require in noise.
     *
     * [familyState] is absent exactly when the family has no position to store — neither a stored level
     * nor a declared one for the presented exercise — in which case there is nothing to record and the
     * completion writes nothing adaptive at all.
     */
    data class NothingToAdapt(
        val reason: ProgramAdaptiveReason,
        val familyState: FamilyProgressionState?
    ) : AdaptiveIntegrationOutcome {

        init {
            require(reason.isHold) {
                "a window that changed nothing is answered by one of the hold reasons: reason=$reason"
            }
        }

        override val completion: AdaptiveCompletion
            get() = familyState?.let { AdaptiveCompletion.WindowEvaluated(it) }
                ?: AdaptiveCompletion.NothingDecided
    }

    /**
     * No window could be evaluated, so nothing adaptive was decided and nothing is written.
     *
     * [gap] says exactly which fact was missing or ineligible ([AdaptiveInputGap]); the engine was never
     * asked, and the completion reports `AdaptiveOutcome.NothingDecided` rather than a fabricated
     * decision.
     */
    data class CannotBuildAdaptiveRequest(val gap: AdaptiveInputGap) : AdaptiveIntegrationOutcome {

        override val completion: AdaptiveCompletion
            get() = AdaptiveCompletion.NothingDecided
    }
}

/**
 * **Where the reason of a persisted decision is kept, and why it is not reconstructed** (§13).
 *
 * §23's decision row now stores the engine's reason token (`program_adaptive_decision_record.reason`,
 * added by the version-10 → version-11 migration), so the answer to *"why did the program decide this?"*
 * survives a process restart as a **stored fact** rather than as a re-derivation. The audit that produced
 * that choice is worth stating, because the alternative was genuinely available and is genuinely not
 * enough:
 *
 * ```text
 * APPLIED     + PROGRESS       → the row's action names exactly one change token: SUSTAINED_POSITIVE
 * APPLIED     + REGRESS        → ... and SUSTAINED_NEGATIVE
 * APPLIED     + CHANGE_VARIANT → ... and VARIANT_REALIGNED
 * NOT_APPLIED + HOLD           → ??? one of nineteen hold tokens, of which the row stores none
 * ```
 *
 * The first three lines *are* reconstructible from (outcome, action), and that is exactly why they are
 * not enough to answer the question the audit asks:
 *
 *  * **the filtered case has no answer in the row.** A guard-refused decision is stored as
 *    `NOT_APPLIED` + `HOLD` — the identical shape to `AWAITING_CONFIRMATION`, `STABLE_PLATEAU`,
 *    `CEILING_REACHED`, `USER_AUTHORED_ELEMENT` and the rest. Reading it back as `AGGREGATE_LOAD_GUARD`
 *    is a claim about **the write rule** (*"this integration writes a `NOT_APPLIED` row only when the
 *    guard refused a resolved change"*), not about the stored fact. That rule is real and tested, but a
 *    reconstruction that depends on today's write path silently changes meaning the day another
 *    `NOT_APPLIED` shape is persisted — and it would change *historical* meaning, which is the one thing
 *    an audit trail may not do;
 *  * **the other three lines rest on the current vocabulary.** *"Each change action names exactly one
 *    reason"* is a property of `ProgramAdaptiveReason` today, not an invariant of the type: nothing in
 *    the domain forbids a second token whose `action` is `PROGRESS`, and adding one would silently
 *    re-label every previously stored applied decision. A stored token cannot be re-labelled;
 *  * **the facts a reconstruction would want are deliberately not persisted.** The `requestedAction`
 *    (`"a PROGRESS was asked for and the guard said not yet"`, which is what separates a refusal from an
 *    ordinary hold) is not a column; the signals are not a column; and the guard's own verdict — which
 *    channel exceeded its tolerance, or that the recent context met the plan while owing an opportunity —
 *    is not a column either. Persisting the reason makes the request/refusal distinction readable
 *    *because* the reason `AGGREGATE_LOAD_GUARD` can only follow a resolved change, which is why the
 *    unpersisted `requestedAction` needs no column of its own.
 *
 * What is therefore **stored** is the decision's answer; what stays **unpersisted** is everything that a
 * window can recompute — the signals, the load comparison, the guard's per-channel detail, the requested
 * action — and that boundary is a decision with a reason of its own: those facts are functions of the
 * window the decision was taken on, and the window is reconstructible from the sessions the Program
 * already stores. The reason is not: it is the answer to *"which rule spoke?"*, and no window states it.
 *
 * ### What the outcomes require of the reason
 *
 * The three shapes above are enforced rather than documented: [AdaptiveIntegrationOutcome.AdaptiveApplied]
 * insists the decision carries a **change** reason, [AdaptiveIntegrationOutcome.AdaptiveFiltered] insists
 * it carries [ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD], and
 * [AdaptiveIntegrationOutcome.NothingToAdapt] insists it carries a **hold** reason. A decision whose
 * reason disagrees with its own outcome is therefore not producible by this integration at all, and the
 * `NOT_APPLIED` row that reaches storage always says `AGGREGATE_LOAD_GUARD`.
 */
