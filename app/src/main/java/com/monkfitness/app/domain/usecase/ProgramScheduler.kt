package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.PausedInterval
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedulingRefusal
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.ScheduleOutcome
import com.monkfitness.app.domain.program.ScheduleRequest
import com.monkfitness.app.domain.program.ScheduleWindow
import com.monkfitness.app.domain.program.SlotIdSource
import com.monkfitness.app.domain.program.SlotPlan
import com.monkfitness.app.domain.program.SlotPlanner
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.pausedInterval
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Scheduler: turning a saved Program's current revision into planned opportunities.
 *
 * The decision itself is pure and lives in [SlotPlanner] (a [ScheduleRequest] in, a [SlotPlan] out, no clock
 * and no storage). This class makes that decision **about a stored Program**: it reads the Program, its
 * current revision, its slots and its pauses, supplies the dates the decision needs, and writes what was
 * decided in one transaction.
 *
 * The absences are the guarantees. There is no session runtime here, so "let the Scheduler create Session"
 * is not a rule this class obeys but a sentence it could not carry out; no adaptive engine and no policy,
 * so it never reads performance, evidence or recovery; no generation and no exercise library; no progress
 * and no statistics; no revision writes (a revision is immutable); and no lifecycle writes — the Program is
 * read and never re-saved, so "a planned start date does not itself start a Program" cannot break here even
 * in principle.
 *
 * Because the rule is stated on **data** rather than on an event, it needs no notification that a revision
 * changed: a future opportunity whose date the current revision no longer presents is superseded, and after
 * one pass none remains. A second call decides nothing, and a call a week later simply extends an
 * indefinite program's horizon.
 *
 * `preview` is this same pass with the write left out, for a Review screen and for measuring the decision
 * without storing it.
 *
 * A completed or archived Program is not planned, and neither is one with no date to plan from; each refusal
 * writes nothing at all. Refusals are values ([ProgramSchedulingRefusal]) and a storage failure surfaces as
 * [ProgramSchedulingResult.Failure] rather than being absorbed into an empty outcome.
 *
 * @param programRepository read for the lifecycle, the archive stamp and the two start dates; never written
 *   through here, so a pass moves no lifecycle.
 * @param planRepository read for the plan being scheduled and the pointer that names it — never saved.
 * @param scheduleRepository the slots and pause intervals: read for what the Program has, written for what
 *   the pass decides. The only write path this stage has.
 * @param clock read once per pass, as a *date* in [zone]: "today" is a fact about the user's calendar.
 * @param zone the calendar [clock]'s instant is read in. A pause is stored as instants and a slot is planned
 *   for a date, so the conversion needs a calendar taken as a value rather than assumed — which is also what
 *   lets a test state the dates a pass works in.
 * @param inTransaction one pass is one unit, so a failure part-way leaves a Program whose schedule is
 *   exactly what it was.
 */
class ProgramScheduler(
    private val programRepository: ProgramRepository,
    private val planRepository: ProgramPlanRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    private val slotIds: SlotIdSource = SlotIdSource { SlotId(idGenerator.newId()) }

    /**
     * Plans [programId]'s current revision and stores what the pass decided.
     *
     * The one entry point for three callers — after a Save, when a Program starts, and when an indefinite
     * Program's horizon needs extending — because all three are the same operation on the same rule, and
     * running it again when nothing changed decides nothing.
     */
    suspend fun schedule(programId: ProgramId): ProgramSchedulingResult<ScheduleOutcome> =
        pass(programId, persist = true)

    /**
     * Decides the same pass as [schedule] and **writes nothing**.
     *
     * The same code path with the write left out, so what it reports is what scheduling would do, not a
     * second opinion about it. It still mints the identities it reports, and stores no slot and no status.
     */
    suspend fun preview(programId: ProgramId): ProgramSchedulingResult<ScheduleOutcome> =
        pass(programId, persist = false)

    /**
     * The one opportunity the user can act on next, or `null` when the Program has none.
     *
     * An opportunity is this class's own row, so "which is next" is answerable here without a second copy of
     * the rule: the earliest `plannedFor` among those [WorkoutSlot.isStartable] admits. It holds no
     * threshold, horizon or policy, so a missed opportunity is offered because it is startable, and a
     * completed or superseded one is not.
     *
     * It is **not** a pass: it plans nothing, extends no horizon, mints no id, opens no transaction and
     * writes nothing, so running it cannot change what the next pass will do. The refusals are the pass's own
     * vocabulary.
     *
     * @return the earliest startable opportunity, or `null` — an answer, not a failure: a Program whose plan
     *   has not been scheduled yet legitimately has none.
     */
    suspend fun nextOpportunity(programId: ProgramId): ProgramSchedulingResult<WorkoutSlot?> =
        schedulingResult {
            val program = programRepository.programById(programId)
                ?: throw SchedulingProgramMissing(programId)
            if (program.isArchived) throw SchedulingProgramArchived(programId)
            if (program.lifecycleStatus.isTerminal) throw SchedulingProgramCompleted(programId)

            scheduleRepository.slotsOfProgram(programId)
                .filter { slot -> slot.isStartable }
                .minWithOrNull(compareBy({ slot -> slot.plannedFor }, { slot -> slot.slotId.value }))
        }.refusing(programId)

    /**
     * The opportunities a Program **being created** receives — the same decision, over values rather than
     * reads.
     *
     * A creation cannot go through [schedule], which plans a *stored* Program, and the creation unit exists
     * precisely so nothing is stored until everything can be. So the inputs arrive as values and everything
     * else is unchanged: the same [SlotPlanner] request assembly, the anchor read from the Program's own
     * facts (`actualStartDate ?: plannedStartDate`, refusing with the same `NoSchedulingAnchor`), and
     * identities minted from the injected generator.
     *
     * The slots and pauses passed in are empty — a fact about a Program being created, not an assumption. It
     * writes nothing and opens no transaction: the caller that owns the creation unit writes, and this
     * answer is what it writes, which keeps "Scheduler owns timing" true while the importer and the Save
     * orchestration own *what Program is being created*.
     *
     * @return the opportunities the new revision receives, in date order. Nothing is reconciled here: there
     *   are no existing opportunities to reconcile.
     */
    suspend fun initialSlotsFor(
        program: Program,
        revision: ProgramRevision
    ): ProgramSchedulingResult<List<WorkoutSlot>> = schedulingResult {
        require(revision.programId == program.programId) {
            "the revision must belong to the Program being created: program=" +
                "'${program.programId.value}' revision='${revision.revisionId.value}'"
        }
        val asOf = clock.now().atZone(zone).toLocalDate()
        val anchor = program.actualStartDate?.atZone(zone)?.toLocalDate()
            ?: program.plannedStartDate
            ?: throw SchedulingNoAnchor(program.programId)

        decide(
            revision = revision,
            anchor = anchor,
            asOf = asOf,
            slots = emptyList(),
            pauses = emptyList()
        ).create
    }.refusing(program.programId)

    /**
     * One pass: read, decide, and — when [persist] — write.
     *
     * The read order is the order of the refusals, and deliberately so. The Program first, because "is this
     * Program plannable at all?" is a question about it; the revision second, because a Program with no
     * readable plan has nothing to schedule; the day and the anchor third, because the decision cannot be
     * made without them.
     *
     * The anchor comes from the Program's own facts and nowhere else — the date it *actually* started, or
     * the date it is *planned* to start — and a Program with neither is refused rather than given today.
     */
    private suspend fun pass(
        programId: ProgramId,
        persist: Boolean
    ): ProgramSchedulingResult<ScheduleOutcome> = schedulingResult {
        val program = programRepository.programById(programId) ?: throw SchedulingProgramMissing(programId)
        if (program.isArchived) throw SchedulingProgramArchived(programId)
        if (program.lifecycleStatus.isTerminal) throw SchedulingProgramCompleted(programId)

        val revision = planRepository.currentRevision(programId)
            ?: throw SchedulingRevisionMissing(programId, program.currentRevisionId)

        // One read of the clock, as a date: every comparison the pass makes is a comparison of dates.
        val asOf = clock.now().atZone(zone).toLocalDate()
        val anchor = program.actualStartDate?.atZone(zone)?.toLocalDate()
            ?: program.plannedStartDate
            ?: throw SchedulingNoAnchor(programId)

        val slots = scheduleRepository.slotsOfProgram(programId)
        val pauses = scheduleRepository.pausesOfProgram(programId).map { pause -> pause.pausedInterval(zone) }

        val decision = decide(
            revision = revision,
            anchor = anchor,
            asOf = asOf,
            slots = slots,
            pauses = pauses
        )

        if (persist) store(decision)

        outcomeOf(programId, revision, anchor, asOf, decision)
    }.refusing(programId)

    /**
     * One decision, from one request — the Scheduler's single implementation of "what does this revision
     * need, from these dates, that it does not have?".
     *
     * Both deciding entry points go through here — a pass over a stored Program and the initial
     * opportunities of one being created — differing only in their inputs. A second copy of the request
     * assembly would be a second place the window, the anchor and the identity source are decided, and those
     * are exactly the values the Scheduler alone owns.
     */
    private fun decide(
        revision: ProgramRevision,
        anchor: LocalDate,
        asOf: LocalDate,
        slots: List<WorkoutSlot>,
        pauses: List<PausedInterval>
    ): SlotPlan = SlotPlanner.plan(
        ScheduleRequest(
            revision = revision,
            anchor = anchor,
            asOf = asOf,
            slots = slots,
            pauses = pauses,
            slotIds = slotIds
        )
    )

    /**
     * Stores one decision — the created opportunities and the two reconciliations — as **one** unit.
     *
     * A decision that decided nothing opens no transaction and writes no row, which is what makes a second
     * application of the same pass measurably inert.
     *
     * Order inside the unit is creations, supersessions, then misses; it is not load-bearing because the
     * three lists are disjoint. An existing opportunity is never deleted, moved or re-pointed — its status
     * changes and its identity, date and plan day stay, so the history survives and the change is auditable.
     * A completed opportunity is not even superseded: its status is not `PLANNED`, so it is not one a pass
     * may rewrite, because a finished workout is history.
     */
    private suspend fun store(decision: SlotPlan) {
        if (decision.isEmpty) return
        inTransaction {
            if (decision.create.isNotEmpty()) scheduleRepository.addSlots(decision.create)
            decision.supersede.forEach { superseded ->
                scheduleRepository.recordSlotOutcome(superseded.slotId, SlotStatus.SUPERSEDED, null)
            }
            decision.miss.forEach { slotId ->
                scheduleRepository.recordSlotOutcome(slotId, SlotStatus.MISSED, null)
            }
        }
    }

    private fun outcomeOf(
        programId: ProgramId,
        revision: ProgramRevision,
        anchor: LocalDate,
        asOf: java.time.LocalDate,
        decision: SlotPlan
    ): ScheduleOutcome = ScheduleOutcome(
        programId = programId,
        revisionId = revision.revisionId,
        anchor = anchor,
        asOf = asOf,
        window = decision.window,
        scheduledDates = decision.scheduledDates,
        created = decision.create,
        superseded = decision.supersede,
        missed = decision.miss
    )
}

/**
 * The Program a pass names is not stored.
 *
 * Thrown inside the pass so the result boundary can map it to a
 * [ProgramSchedulingRefusal.ProgramNotFound] naming the id; it never reaches a caller as an exception.
 */
internal class SchedulingProgramMissing(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramNotFound(programId).message)

/** The Program is archived, and the archive stops future planning. */
internal class SchedulingProgramArchived(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramArchived(programId).message)

/** The Program's lifecycle is terminal, so it cannot acquire opportunities. */
internal class SchedulingProgramCompleted(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramCompleted(programId).message)

/** The revision the Program points at is not stored, so there is no plan to schedule. */
internal class SchedulingRevisionMissing(val programId: ProgramId, val revisionId: RevisionId) :
    RuntimeException(ProgramSchedulingRefusal.RevisionMissing(programId, revisionId).message)

/** The Program has neither started nor been planned to start, so it has no `day 1` to plan from. */
internal class SchedulingNoAnchor(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.NoSchedulingAnchor(programId).message)

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramSchedulingResult.Failure] rather than
 * absorbing it. A refusal is *not* a failure, so the boundary below re-states the ones this class raises.
 */
internal inline fun <T> schedulingResult(block: () -> T): ProgramSchedulingResult<T> = try {
    ProgramSchedulingResult.Success(block())
} catch (failure: Throwable) {
    ProgramSchedulingResult.Failure(failure)
}

private fun <T> ProgramSchedulingResult<T>.refusing(programId: ProgramId): ProgramSchedulingResult<T> =
    when (this) {
        is ProgramSchedulingResult.Success -> this
        is ProgramSchedulingResult.Refused -> this
        is ProgramSchedulingResult.Failure -> when (val cause = cause) {
            is SchedulingProgramMissing ->
                ProgramSchedulingResult.Refused(programId, ProgramSchedulingRefusal.ProgramNotFound(programId))
            is SchedulingProgramArchived ->
                ProgramSchedulingResult.Refused(programId, ProgramSchedulingRefusal.ProgramArchived(programId))
            is SchedulingProgramCompleted ->
                ProgramSchedulingResult.Refused(programId, ProgramSchedulingRefusal.ProgramCompleted(programId))
            is SchedulingRevisionMissing -> ProgramSchedulingResult.Refused(
                programId,
                ProgramSchedulingRefusal.RevisionMissing(programId, cause.revisionId)
            )
            is SchedulingNoAnchor ->
                ProgramSchedulingResult.Refused(programId, ProgramSchedulingRefusal.NoSchedulingAnchor(programId))
            else -> this
        }
    }
