package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.program.ProgramTransition.ARCHIVE
import com.monkfitness.app.domain.program.ProgramTransition.UNARCHIVE
import java.time.Instant

/**
 * The transition table of `NOT_STARTED → RUNNING ↕ PAUSED → COMPLETED`, as a pure decision over two
 * values: the lifecycle a Program is in, and the one it is being asked to enter.
 *
 * This object holds no state and decides nothing but the arrow. The transition rule is a *domain* fact
 * rather than a persistence fact — a repository could store any status and a DAO has no opinion, so
 * "which transitions are legal" has to live above both. The layer that *acts* on the decision
 * ([ProgramLifecycleService]) owns the consequences: the stamps, the pause interval, the selection.
 *
 * Exactly four transitions are legal, and each one's guard is the rule that makes it legal:
 *
 *  * `START` — only from `NOT_STARTED`. A planned start date is not a start, because nothing but this
 *    transition moves a Program out of `NOT_STARTED`, and `actualStartDate` is a fact: the transition
 *    carries no date, the caller supplies the moment, and the stamp is what it *produces*.
 *  * `PAUSE` — only from `RUNNING`, and back to `RUNNING` only by `RESUME`. A pause is an interval with
 *    two ends, so resuming while nothing is paused is as meaningless as pausing twice; the interval is
 *    what "frozen active program time" *means*.
 *  * `COMPLETE` — from `RUNNING` or `PAUSED`, and to nothing. A completed Program is terminal and cannot
 *    be resumed directly: what the user does next is the *selection* rule, not a lifecycle one.
 *    Completing from `PAUSED` closes the open interval rather than leaving a pause that never ended.
 *  * `ARCHIVE` is not a lifecycle state at all. It is a separate stamp (`archivedAt`), so it neither
 *    participates in this table nor blocks any transition: an archived Program keeps the lifecycle it
 *    reached. Its guard is answered by [archiveDecision], not by [decision].
 *
 * Everything else is refused, and a refusal is a *result* rather than an exception: an illegal
 * transition is the ordinary outcome of tapping an action the state does not support, so the caller
 * receives [ProgramTransitionResult.Refused] and decides whether that is a disabled button or a toast.
 * Nothing in this layer throws.
 */
internal object ProgramLifecyclePolicy {

    /**
     * Whether [transition] is legal from [from].
     *
     * Keyed on the **operation**, not on the target status, because two operations reach the same status
     * for different reasons and the reason is the rule: `START` and `RESUME` both end in `RUNNING`, but
     * only `START` may set `actualStartDate` and only from `NOT_STARTED`, while only `RESUME` closes a
     * pause interval and only from `PAUSED`. A table of status pairs would let a `START` fire from
     * `PAUSED`, re-stamping the factual start.
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
     * `openPause` is consulted only by `PAUSE` and `RESUME`, because a pause is an interval and the
     * interval's state is what those transitions guard on. `archived` is not a lifecycle guard: it is
     * recorded so the caller can tell "refused because of the lifecycle" from "the Program is archived",
     * which the UI presents differently.
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
        // in effect" for a resume of one would explain the wrong rule.
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
     * The unarchive decision, the mirror of [archiveDecision]: the only guard is the idempotency of the
     * stamp — unarchiving a Program that is not archived is a no-op rather than an error, and never
     * moves a lifecycle.
     */
    fun unarchiveDecision(current: LifecycleStatus, archived: Boolean): ProgramTransitionResult =
        if (!archived) {
            ProgramTransitionResult.AlreadyThere(UNARCHIVE, current, archived)
        } else {
            ProgramTransitionResult.Allowed(UNARCHIVE, current, archived)
        }

    /**
     * The archive decision, which is not a lifecycle decision.
     *
     * Archiving retains all history and stops future planning; it is a stamp, and the only guard it
     * carries is the idempotency of the stamp itself. A Program already archived is *not* refused —
     * `archivedAt` is not a state machine — but the caller is told nothing changed, because a second
     * archive must not move the stamp and rewrite history.
     */
    fun archiveDecision(current: LifecycleStatus, archived: Boolean): ProgramTransitionResult =
        if (archived) {
            ProgramTransitionResult.AlreadyThere(ProgramTransition.ARCHIVE, current, archived)
        } else {
            ProgramTransitionResult.Allowed(ProgramTransition.ARCHIVE, current, archived)
        }
}

/**
 * One lifecycle operation a caller can request.
 *
 * The vocabulary is the operation rather than the target status, because two operations reach the same
 * status for different reasons and the reason is the rule: `RESUME` and `START` both end in `RUNNING`,
 * but only `RESUME` closes an interval and only `START` sets `actualStartDate`. An archive is present
 * because the operation exists, and its [toStatus] is a sentinel naming the fact that no lifecycle
 * movement happens.
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
 * The outcome of a lifecycle operation, for both the legal and the illegal cases.
 *
 * Every case carries the transition asked for, the lifecycle the Program was already in, and whether it
 * was archived, because those three are what a caller needs to explain the outcome without re-reading
 * the Program. [Allowed] is a change, [AlreadyThere] a no-op the caller may treat as one, and [Refused]
 * the illegal case, with the reason as a sentence rather than a code.
 */
sealed interface ProgramTransitionResult {

    val transition: ProgramTransition

    val fromStatus: LifecycleStatus

    /** Whether the Program was archived when the decision was made. Archiving is not a guard. */
    val archived: Boolean

    /**
     * The transition is legal and changes something.
     *
     * `timestamp` is the moment the caller asked for, which the service stamps the Program with. It is
     * carried in the result rather than read from a clock here: this is a pure decision, so the moment
     * belongs to the layer that owns the clock.
     */
    data class Allowed(
        override val transition: ProgramTransition,
        override val fromStatus: LifecycleStatus,
        override val archived: Boolean,
        val timestamp: Instant? = null
    ) : ProgramTransitionResult

    /**
     * The Program is already where the transition would put it, so nothing changes. Not an error:
     * `archivedAt` is a stamp rather than a state machine, and a lifecycle already at the target is a
     * no-op rather than a refusal.
     */
    data class AlreadyThere(
        override val transition: ProgramTransition,
        override val fromStatus: LifecycleStatus,
        override val archived: Boolean
    ) : ProgramTransitionResult

    /**
     * The transition is illegal from this state, or the operation's precondition does not hold.
     *
     * `reason` is the rule, as a sentence naming the transition and the state.
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
 * The domain value carries its own invariants, so this helper performs the one edit a transition makes
 * *and no more*: it changes the lifecycle and sets or clears the stamps the lifecycle requires. It never
 * touches [Program.currentRevisionId] — a lifecycle change creates no revision — nor the archive stamp
 * or the planned start date, which are independent of the lifecycle.
 *
 * The planned start date is never consulted here: that is the whole content of "a planned start date
 * does not automatically start a Program", since no function in this layer converts a `LocalDate` into a
 * lifecycle.
 */
internal fun Program.applying(transition: ProgramTransition, at: Instant): Program = when (transition) {
    ProgramTransition.START -> copy(lifecycleStatus = LifecycleStatus.RUNNING, actualStartDate = at, updatedAt = at)
    ProgramTransition.PAUSE -> copy(lifecycleStatus = LifecycleStatus.PAUSED, updatedAt = at)
    ProgramTransition.RESUME -> copy(lifecycleStatus = LifecycleStatus.RUNNING, updatedAt = at)
    ProgramTransition.COMPLETE -> copy(lifecycleStatus = LifecycleStatus.COMPLETED, updatedAt = at)
    ProgramTransition.ARCHIVE -> copy(archivedAt = at, updatedAt = at)
    ProgramTransition.UNARCHIVE -> copy(archivedAt = null, updatedAt = at)
}

