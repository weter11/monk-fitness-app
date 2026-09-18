package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.program.ProgramTransition.ARCHIVE
import com.monkfitness.app.domain.program.ProgramTransition.UNARCHIVE
import java.time.Instant

/**
 * The transition table of `NOT_STARTED → RUNNING ↕ PAUSED → COMPLETED` (§3), as a pure decision over
 * two values: the lifecycle a Program is in, and the one it is being asked to enter.
 *
 * This object holds no state, talks to nothing and decides nothing but the arrow. It exists because
 * the transition rule is a *domain* fact rather than a persistence fact, and the two are easy to
 * confuse: a repository could store any status, and a DAO has no opinion, so "which transitions are
 * legal" has to live above both or it lives nowhere. The layer that *acts* on the decision
 * ([ProgramLifecycleService]) owns the consequences — the stamps, the pause interval, the
 * selection — and this object owns only which consequence there is one of.
 *
 * Exactly four transitions are legal, and each one's guard is the rule that makes it legal:
 *
 *  * `START` — only from `NOT_STARTED`. This is where the blueprint's two start rules both bite: a
 *    planned start date is not a start, because nothing but this transition moves a Program out of
 *    `NOT_STARTED`; and `actualStartDate` is a fact, because the transition does not carry a date —
 *    the caller supplies the moment the user started it, and the stamp is what the transition
 *    *produces* rather than something it reads.
 *  * `PAUSE` — only from `RUNNING`, and only back to `RUNNING` by `RESUME`. A pause is an interval
 *    with two ends, so resuming while nothing is paused is as meaningless as pausing while already
 *    paused. `PAUSED` freezes active program time and missed-opportunity logic (§3), which is why the
 *    pair is an interval on storage rather than a flag: the interval is what "frozen" *means*.
 *  * `COMPLETE` — from `RUNNING` or `PAUSED`, and to nothing. A completed Program is terminal and
 *    cannot be resumed directly (§3): what the user does next is pick another Program, and that is
 *    the *selection* rule, not a lifecycle one. Completing from `PAUSED` closes the open interval
 *    rather than leaving a pause that never ended.
 *  * `ARCHIVE` is not here at all. It is a separate stamp (`archivedAt`) and is not a lifecycle
 *    state (§3, §29), so it neither participates in this table nor blocks any transition: an
 *    archived Program keeps the lifecycle it reached. [ProgramTransition.ARCHIVE] exists in the
 *    vocabulary only because the archive operation needs a name in the same result type the UI
 *    consumes; its guard is not a lifecycle question, and it is answered by
 *    [archiveDecision] rather than by [decision].
 *
 * Everything else is refused, and a refusal is a *result* rather than an exception (§28: expected
 * states are results, not exceptions). An illegal transition is the ordinary, explainable outcome of
 * a user tapping an action the Program's state does not support — resuming a completed Program,
 * pausing one that never started — so the caller receives [ProgramTransitionResult.Refused] naming
 * the transition and the current status, and decides whether that is a disabled button, a toast or
 * an ignored gesture. Nothing in this layer throws.
 */
internal object ProgramLifecyclePolicy {

    /**
     * Whether [transition] is legal from [from].
     *
     * Keyed on the **operation**, not on the target status, because two operations reach the same
     * status for different reasons and the reason is the rule (§3): `START` and `RESUME` both end in
     * `RUNNING`, but only `START` may set `actualStartDate` and only from `NOT_STARTED`, while only
     * `RESUME` closes a pause interval and only from `PAUSED`. A table of status pairs could not tell
     * them apart, and would let a `START` fire from `PAUSED` — re-stamping the factual start.
     */
    fun isLegalTransition(from: LifecycleStatus, transition: ProgramTransition): Boolean = when (transition) {
        ProgramTransition.START -> from == LifecycleStatus.NOT_STARTED
        ProgramTransition.PAUSE -> from == LifecycleStatus.RUNNING
        ProgramTransition.RESUME -> from == LifecycleStatus.PAUSED
        ProgramTransition.COMPLETE -> from == LifecycleStatus.RUNNING || from == LifecycleStatus.PAUSED
        ProgramTransition.ARCHIVE, ProgramTransition.UNARCHIVE -> true
    }

    /**
     * The decision for a lifecycle operation, given the Program's current state.
     *
     * @param current the lifecycle the Program is in now.
     * @param openPause whether the Program currently has a pause interval in effect. `START` and
     *   `COMPLETE` do not consult it; `PAUSE` and `RESUME` are meaningless without it, because a
     *   pause is an interval and the interval's state is what the transition guards on.
     * @param archived whether the Program is archived. Not a lifecycle guard (§3) — recorded so the
     *   caller can distinguish "refused because of the lifecycle" from "the Program is archived",
     *   which the UI presents differently.
     */
    fun decision(
        current: LifecycleStatus,
        transition: ProgramTransition,
        openPause: Boolean,
        archived: Boolean
    ): ProgramTransitionResult {
        if (transition == ARCHIVE) return archiveDecision(current, archived)
        if (transition == UNARCHIVE) return unarchiveDecision(current, archived)
        if (current == transition.toStatus) {
            return ProgramTransitionResult.AlreadyThere(transition, current, archived)
        }
        // The status guard comes first: a COMPLETED Program is terminal, and reporting "no pause is
        // in effect" for a resume of one would explain the wrong rule (§3).
        if (!isLegalTransition(current, transition)) {
            return ProgramTransitionResult.Refused(
                transition, current, archived,
                "the transition ${transition.name} is not legal from $current (§3)"
            )
        }
        when (transition) {
            ProgramTransition.PAUSE -> if (openPause) {
                return ProgramTransitionResult.Refused(
                    transition, current, archived,
                    "a Program with a pause already in effect cannot be paused again (§3)"
                )
            }
            ProgramTransition.RESUME -> if (!openPause) {
                return ProgramTransitionResult.Refused(
                    transition, current, archived,
                    "a Program with no pause in effect cannot be resumed (§3)"
                )
            }
            else -> Unit
        }
        return ProgramTransitionResult.Allowed(transition, current, archived)
    }

    /**
     * The unarchive decision, the mirror of [archiveDecision]: the only guard is the idempotency of
     * the stamp — unarchiving a Program that is not archived is a no-op rather than an error, and
     * never moves a lifecycle (§3).
     */
    fun unarchiveDecision(current: LifecycleStatus, archived: Boolean): ProgramTransitionResult =
        if (!archived) {
            ProgramTransitionResult.AlreadyThere(UNARCHIVE, current, archived)
        } else {
            ProgramTransitionResult.Allowed(UNARCHIVE, current, archived)
        }

    /**
     * The archive decision, which is not a lifecycle decision (§3, §29).
     *
     * Archiving retains all history and stops future planning; it is a stamp, and the only guard it
     * carries is the idempotency of the stamp itself. A Program already archived is *not* refused —
     * `archivedAt` is not a state machine, so there is nothing to refuse — but the caller is told
     * nothing changed, because a second archive must not move the stamp and rewrite history.
     */
    fun archiveDecision(current: LifecycleStatus, archived: Boolean): ProgramTransitionResult =
        if (archived) {
            ProgramTransitionResult.AlreadyThere(ProgramTransition.ARCHIVE, current, archived)
        } else {
            ProgramTransitionResult.Allowed(ProgramTransition.ARCHIVE, current, archived)
        }
}

/**
 * One lifecycle operation a caller can request (§3, §4).
 *
 * The vocabulary is the operation rather than the target status, because two operations reach the
 * same status for different reasons and the reason is the rule: `RESUME` and `START` both end in
 * `RUNNING`, but only `RESUME` closes an interval and only `START` sets `actualStartDate`. An
 * archive is present because the operation exists (§4), and its [toStatus] is a sentinel that names
 * the fact that no lifecycle movement happens.
 */
enum class ProgramTransition {
    START,
    PAUSE,
    RESUME,
    COMPLETE,
    ARCHIVE,
    UNARCHIVE;

    /** The lifecycle status this transition moves a Program to, if it moves it at all. */
    val toStatus: LifecycleStatus
        get() = when (this) {
            START -> LifecycleStatus.RUNNING
            PAUSE -> LifecycleStatus.PAUSED
            RESUME -> LifecycleStatus.RUNNING
            COMPLETE -> LifecycleStatus.COMPLETED
            ARCHIVE -> LifecycleStatus.NOT_STARTED
            UNARCHIVE -> LifecycleStatus.NOT_STARTED
        }
}

/**
 * The outcome of a lifecycle operation, for both the legal and the illegal cases (§28).
 *
 * Every case carries the transition that was asked for, the lifecycle the Program was already in, and
 * whether the Program was archived, because those three are what a caller needs to explain the
 * outcome without re-reading the Program. [Allowed] and [AlreadyThere] are the two successes —
 * [Allowed] is a change, [AlreadyThere] is a no-op the caller may treat as one — and [Refused] is the
 * illegal case, with the reason as a sentence rather than a code.
 */
sealed interface ProgramTransitionResult {

    /** The transition this decision is about. */
    val transition: ProgramTransition

    /** The lifecycle the Program was in when the decision was made. */
    val fromStatus: LifecycleStatus

    /** Whether the Program was archived when the decision was made. Archiving is not a guard (§3). */
    val archived: Boolean

    /**
     * The transition is legal and changes something.
     *
     * @property timestamp the moment the caller asked for, which the service stamps the Program with.
     *   It is a fact carried in the result rather than read from a clock here: this is a pure
     *   decision, so the moment belongs to the layer that owns the clock (§26).
     */
    data class Allowed(
        override val transition: ProgramTransition,
        override val fromStatus: LifecycleStatus,
        override val archived: Boolean,
        val timestamp: Instant? = null
    ) : ProgramTransitionResult

    /**
     * The Program is already where the transition would put it, so nothing changes. Not an error:
     * `archivedAt` is a stamp rather than a state machine, and a lifecycle already at the target is
     * a no-op rather than a refusal.
     */
    data class AlreadyThere(
        override val transition: ProgramTransition,
        override val fromStatus: LifecycleStatus,
        override val archived: Boolean
    ) : ProgramTransitionResult

    /**
     * The transition is illegal from this state (§3), or the operation's precondition does not hold.
     *
     * @property reason the rule, as a sentence naming the transition and the state.
     */
    data class Refused(
        override val transition: ProgramTransition,
        override val fromStatus: LifecycleStatus,
        override val archived: Boolean,
        val reason: String
    ) : ProgramTransitionResult {

        /** `COMPLETE` from `COMPLETED` is the terminal rule; the UI presents it as a hard no. */
        val isTerminalRefusal: Boolean
            get() = fromStatus == LifecycleStatus.COMPLETED && transition == ProgramTransition.RESUME
    }
}

/**
 * Where a [Program] ends up after a transition was allowed.
 *
 * The domain value carries its own invariants ([Program]'s `init`), so this helper exists to perform
 * the one edit a transition makes *and no more*: it changes the lifecycle, and it sets or clears the
 * stamps that the lifecycle requires. A structural fact it never touches is [Program.currentRevisionId]
 * — a lifecycle change creates no revision (§6) — and neither the archive stamp nor the planned start
 * date, which are independent of the lifecycle (§3).
 *
 * The planned start date is never consulted here. That is the whole content of "a planned start date
 * does not automatically start a Program" (§3): no function in this layer converts a `LocalDate` into
 * a lifecycle, so the only thing that can is an explicit `START` call from the user.
 */
internal fun Program.applying(transition: ProgramTransition, at: Instant): Program = when (transition) {
    ProgramTransition.START -> copy(lifecycleStatus = LifecycleStatus.RUNNING, actualStartDate = at, updatedAt = at)
    ProgramTransition.PAUSE -> copy(lifecycleStatus = LifecycleStatus.PAUSED, updatedAt = at)
    ProgramTransition.RESUME -> copy(lifecycleStatus = LifecycleStatus.RUNNING, updatedAt = at)
    ProgramTransition.COMPLETE -> copy(lifecycleStatus = LifecycleStatus.COMPLETED, updatedAt = at)
    ProgramTransition.ARCHIVE -> copy(archivedAt = at, updatedAt = at)
    ProgramTransition.UNARCHIVE -> copy(archivedAt = null, updatedAt = at)
}

