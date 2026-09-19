package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import java.time.LocalDate

/**
 * The outcome of a scheduling pass (§28's *"expected states are results, not exceptions"*).
 *
 * A pass either produced a [ScheduleOutcome] or was refused for a rule of §3, §20 or §29 — a completed
 * Program is not planned, an archived one is not planned, a Program with no date to plan *from* is not
 * planned. Each of those is an ordinary, explainable answer to a question the user is allowed to ask,
 * so it is a value the caller branches on rather than an exception the caller catches; a storage
 * failure is the third case, surfaced rather than absorbed (§33: *"catch `Exception` and return empty
 * result"* is prohibited, and an empty [ScheduleOutcome] would be exactly that).
 *
 * The envelope is this stage's own rather than [ProgramOperationResult]'s, for the same reason the
 * editor's is: the value a pass produces is a scheduling outcome, not a Program, and the refusals are
 * about scheduling a plan. The *sentences* are shared where a rule is shared — a program that is not
 * stored is the same fact for every layer, and [ProgramSchedulingRefusal.ProgramNotFound] quotes the
 * one the lifecycle layer already owns rather than inventing a second wording for it.
 */
sealed interface ProgramSchedulingResult<out T> {

    /** The pass produced [value]. Every non-refused case, including a pass that decided nothing. */
    data class Success<T>(val value: T) : ProgramSchedulingResult<T>

    /** The pass was refused by a rule that is not about storage. Nothing was decided and nothing written. */
    data class Refused(
        val programId: ProgramId,
        val reason: ProgramSchedulingRefusal
    ) : ProgramSchedulingResult<Nothing>

    /** The pass failed for a reason that is not one of the rules: a storage failure, or invalid data. */
    data class Failure(val cause: Throwable) : ProgramSchedulingResult<Nothing>
}

/**
 * The rules that can stop a scheduling pass (§3, §20, §29), each carrying the sentence that explains it.
 *
 * Typed rather than a message string, for the same reason the lifecycle layer's refusals are: a UI
 * wants the sentence, and a test wants to assert *which* rule fired without matching on prose.
 *
 * Note what is **not** here. §28's example vocabulary includes `RevisionConflict`, and this stage has
 * no such mechanism on purpose: a pass works from the revision it is given — the Program's stored
 * current revision — and there is no draft, no optimistic-concurrency token and no staleness check to
 * fail. `NoFutureSlot`, also from §28's list, is likewise not a refusal here: a revision with no date
 * left is a pass that created nothing and still reconciled what it found, so it is reported in the
 * outcome ([SlotPlan.hasNoFutureDate]) rather than refusing a call that has real work to do.
 */
sealed interface ProgramSchedulingRefusal {

    /** The user-facing sentence. */
    val message: String

    /**
     * The Program is not stored (§28 `INVALID_DATA`).
     *
     * The same fact the lifecycle layer reports, quoted from it: one rule, one sentence.
     */
    data class ProgramNotFound(val programId: ProgramId) : ProgramSchedulingRefusal {
        override val message: String = ProgramOperationRefusal.ProgramNotFound(programId).message
    }

    /**
     * The Program is archived, and §29's archive *"stops future planning"*.
     *
     * The refusal is about planning, not about the lifecycle: an archived Program keeps the lifecycle it
     * reached, and unarchiving it makes it plannable again. What archiving stops is exactly this — the
     * plan gaining opportunities the user has put away with the Program.
     */
    data class ProgramArchived(val programId: ProgramId) : ProgramSchedulingRefusal {
        override val message: String =
            "an archived Program is not scheduled: archiving retains its history and stops future " +
                "planning (§29); unarchive it to plan again"
    }

    /**
     * The Program is completed, and a completed Program is terminal (§3).
     *
     * It cannot be resumed directly, so it cannot silently acquire opportunities the user did not ask
     * for. A copy of it is, of course, its own Program and is planned normally.
     */
    data class ProgramCompleted(val programId: ProgramId) : ProgramSchedulingRefusal {
        override val message: String =
            "a completed Program is not scheduled: its lifecycle is terminal and it cannot be resumed " +
                "directly (§3); copy it to plan a new one"
    }

    /**
     * The Program has no date to plan from: it has neither actually started nor been planned to start.
     *
     * The Scheduler does not invent one. §3 is explicit that *"planned start date does not automatically
     * start a Program"*, and the same rule read the other way is that a Program with no start date at
     * all has no `day 1` — picking today would be the Scheduler deciding when the user's program begins,
     * which is the user's decision to make.
     */
    data class NoSchedulingAnchor(val programId: ProgramId) : ProgramSchedulingRefusal {
        override val message: String =
            "the Program has neither started nor been planned to start, so there is no date to " +
                "schedule from; start it or set a planned start date first (§3)"
    }

    /**
     * The revision the Program points at is not stored, so the plan to schedule cannot be read (§23).
     *
     * Reported rather than treated as "no plan": a Program always has a current revision, so a missing
     * one is invalid data, not an empty plan.
     */
    data class RevisionMissing(val programId: ProgramId, val revisionId: RevisionId) :
        ProgramSchedulingRefusal {
        override val message: String =
            "the revision '${revisionId.value}' the Program points at is not stored (§23)"
    }
}

/**
 * What one scheduling pass did, as a value a caller can present and a test can measure.
 *
 * The outcome reports **the decision and its inputs' dates** — the anchor the plan was read from, the
 * day the pass was made on, the window it covered and the dates it found — because every one of those
 * is a fact a user can be shown (debug mode shows the scheduler horizon, §22) and a reviewer can check
 * without re-deriving the pass. It carries the same three lists the [SlotPlan] decided, so a caller
 * never has to go back to storage to find out what happened.
 *
 * Note the two absences, both deliberate. There is no count of anything *performed* — no completions, no
 * volume, no score — because a pass has no access to performance and a scheduling outcome that reported
 * it would be inventing one (§12). And there is no leftover total: an indefinite program's horizon is
 * a window, not a fraction of a program, so nothing here can be read as `N / 30` progress (§21).
 *
 * @property programId the Program the pass was about.
 * @property revisionId the revision it was scheduled from — read, never written.
 * @property anchor the date the plan's `day 1` falls on, as the pass read it.
 * @property asOf the date the pass was made on.
 * @property window the dates the pass covered, or `null` when the revision had none left.
 * @property scheduledDates the revision's training dates inside the window.
 * @property created the opportunities that were stored, in date order.
 * @property superseded the existing opportunities that stopped being ones, each with its rule.
 * @property missed the existing opportunities whose date passed untrained and unpaused.
 */
data class ScheduleOutcome(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val anchor: LocalDate,
    val asOf: LocalDate,
    val window: ScheduleWindow?,
    val scheduledDates: List<LocalDate>,
    val created: List<WorkoutSlot>,
    val superseded: List<SupersededSlot>,
    val missed: List<SlotId>
) {

    /** How many opportunities the pass added. A number of slots, never a number of workouts. */
    val createdCount: Int
        get() = created.size

    /** How many existing opportunities the pass superseded. */
    val supersededCount: Int
        get() = superseded.size

    /** How many existing opportunities the pass marked missed. Neither a score nor a failure count. */
    val missedCount: Int
        get() = missed.size

    /** Whether the pass decided nothing: no new opportunity, no supersession and no miss. */
    val isNoOp: Boolean
        get() = created.isEmpty() && superseded.isEmpty() && missed.isEmpty()

    /** Whether the revision had no date left at or after [asOf] — a run that is already over. */
    val hasNoFutureDate: Boolean
        get() = window == null
}
