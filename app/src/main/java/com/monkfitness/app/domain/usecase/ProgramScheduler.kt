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
 * The Scheduler — §30 step 7: turning a saved Program's current revision into planned opportunities.
 *
 * ### The shape of the layer
 *
 * The blueprint's layering is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI` (§25), and this class is
 * the fourth of those. The *decision* is pure and lives in the domain
 * ([SlotPlanner]: a [ScheduleRequest] in, a [SlotPlan] out — no clock, no storage, no state); this class
 * is what makes that decision **about a stored Program**: it reads the Program, its current revision,
 * its slots and its pauses, supplies the two dates the decision needs, and writes what was decided in
 * one transaction.
 *
 * ```text
 * ProgramScheduler
 *     ├── ProgramRepository          ┐
 *     ├── ProgramPlanRepository      │ read-only: the plan is never written here (§6)
 *     ├── ProgramScheduleRepository  ┘ the slots and the pauses — the only rows this stage writes
 *     ├── clock: Clock               ← when the pass is made on (§26)
 *     ├── idGenerator: IdGenerator   ← the identity of every opportunity it adds (§26)
 *     ├── zone: ZoneId               ← the calendar the user reads a date in
 *     └── inTransaction              ← one unit per pass: every decision lands, or none does
 * ```
 *
 * ### What it owns, and what it never touches
 *
 * Everything the pass decides is §20's: the window, the dates inside it, which opportunities are
 * missing, which stopped being opportunities and which passed. Everything else the blueprint mentions
 * beside those is deliberately absent, and the absences are the guarantees:
 *
 *  * **no session runtime.** There is no `WorkoutSessionRepository` here and no session type anywhere in
 *    this file, so *"let the Scheduler create Session"* (§33) is not a rule this class obeys but a
 *    sentence it could not carry out. A slot is the opportunity; starting a workout on it is §30
 *    step 8's transaction.
 *  * **no adaptive engine and no policy.** The pass reads the plan, never performance, evidence or
 *    recovery: §20 puts Adaptive *after* the Focus Planner in the pipeline, and a Scheduler that
 *    consulted a decision would be making one.
 *  * **no generation and no exercise library.** A slot names an existing plan day of the revision; the
 *    plan's content is the generator's and the editor's (§30 steps 6, 10).
 *  * **no progress and no statistics.** Nothing counts completions, aggregates volume or derives a
 *    score; §30 step 9 owns those reads.
 *  * **no revision writes.** [ProgramPlanRepository] appears here for its two reads alone: a revision is
 *    immutable (§6) and this class exists partly to prove that a plan can be scheduled without being
 *    touched.
 *  * **no lifecycle writes.** The Program is read and never re-saved: a pass writes no column of
 *    `program`, so *"a planned start date does not itself start a Program"* (§3) cannot be broken here
 *    even in principle. Planning from a planned start date plans opportunities and starts nothing.
 *  * **no UI.** The outcome is a value; which screen shows it, and when, is not decided here.
 *
 * ### Planning, and reconciling
 *
 * One pass does both halves of §27's *"Save Editor → new Revision + future-slot reconciliation"* — the
 * reconciliation half, since the revision half is §30 step 6's and already landed. Because the rule is
 * stated on **data** rather than on an event, it does not need to be told that a revision changed: a
 * future opportunity whose date the current revision no longer presents is superseded, and after one
 * pass no such opportunity remains. Calling it twice therefore decides nothing the second time, and
 * calling it a week later simply extends an indefinite program's horizon by a week — the same
 * operation, the same rule, a later `asOf`.
 *
 * ```text
 * schedule(programId)   read → decide → write, in one transaction
 * preview(programId)    read → decide; writes nothing at all
 * ```
 *
 * `preview` exists for the two callers that must see a decision before it is stored: a Review screen,
 * and a test that wants to measure the decision without the write. It mints the identities it reports
 * (a created slot names itself) and writes nothing — no slot, no status, and no row anywhere (§26).
 *
 * ### The rules it is refused by
 *
 * A completed or archived Program is not planned (§3, §29) and neither is one with no date to plan
 * from; each refusal writes nothing at all. The refusals are values
 * ([ProgramSchedulingRefusal]) rather than exceptions, and a storage failure is surfaced as
 * [ProgramSchedulingResult.Failure] rather than absorbed into an empty outcome (§28, §33).
 *
 * @param programRepository the Program aggregate: read for the lifecycle, the archive stamp and the two
 *   start dates this pass needs. It is never written through here, so a pass moves no lifecycle.
 * @param planRepository the revision mechanism: read for the plan being scheduled and for the pointer
 *   that names it. A revision is read, never saved — this class calls only `currentRevision`.
 * @param scheduleRepository the slots and the pause intervals: read for what the Program already has,
 *   written for what the pass decides. It is the only write path this stage has, and it stores slots
 *   without deciding anything about them (§20).
 * @param clock the clock the pass date comes from. Read once per pass, as a *date* in [zone]: "today" is
 *   a fact about the user's calendar, and the layer that owns the clock owns it.
 * @param idGenerator the identity source every created opportunity is minted through (§26).
 * @param zone the calendar [clock]'s instant is read in. A pause is stored as instants (§3) and a slot is
 *   planned for a date, so the conversion needs a calendar; the Scheduler takes it as a value rather
 *   than assuming one, which is also what lets a test state the dates a pass works in.
 * @param inTransaction runs a block in one database transaction. One pass is one unit: the created
 *   opportunities and the statuses of the reconciled ones land together, so a failure part-way through
 *   leaves a Program whose schedule is exactly what it was.
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

    /** The identity source the created opportunities are minted through, from the injected generator. */
    private val slotIds: SlotIdSource = SlotIdSource { SlotId(idGenerator.newId()) }

    // ---------------------------------------------------------------- the operations (§20)

    /**
     * Plans [programId]'s current revision and stores what the pass decided.
     *
     * This is the entry point a caller invokes after a Save (to reconcile the slots a new revision
     * invalidates), when a Program starts, and whenever an indefinite Program's horizon needs extending.
     * All three are the same operation on the same rule, which is why there is one method rather than
     * three: the pass reads what is stored, decides, and writes — and running it again when nothing has
     * changed decides nothing.
     */
    suspend fun schedule(programId: ProgramId): ProgramSchedulingResult<ScheduleOutcome> =
        pass(programId, persist = true)

    /**
     * Decides the same pass as [schedule] and **writes nothing**.
     *
     * The call exists so a caller can show a decision before it is taken — which slots a Program would
     * gain, which future opportunities a revision change would supersede, how far the horizon reaches —
     * and so that the decision can be measured on its own. It is the same code path as [schedule] with
     * the write left out, so what it reports is what scheduling would do, not a second opinion about it.
     */
    suspend fun preview(programId: ProgramId): ProgramSchedulingResult<ScheduleOutcome> =
        pass(programId, persist = false)

    /**
     * The one opportunity the user can act on next, or `null` when the Program has none.
     *
     * ### Why this read lives here
     *
     * An opportunity is the Scheduler's own row, so *"which one is next"* is a question only it can
     * answer without a second copy of the rule. The answer is defined mechanically by the value the
     * opportunity already carries — the earliest `plannedFor` among the ones [WorkoutSlot.isStartable]
     * admits — so this method holds no threshold, no horizon, no window and no policy of its own: a
     * missed opportunity is offered because a missed opportunity is startable, and a completed or
     * superseded one is not offered because it is not.
     *
     * ### What it deliberately is not
     *
     * It is **not** a pass. It plans nothing, extends no horizon, supersedes nothing, marks nothing
     * missed, mints no id, opens no transaction and writes nothing at all — the three entry points
     * above remain the only ways anything is ever decided or stored. A caller that wants the plan
     * advanced wants [schedule]; a caller that wants to show the user what they can start right now
     * wants this, and running it cannot change what the next pass will do.
     *
     * The refusals are the pass's own and are raised for the same reasons, so a caller reads one
     * vocabulary: an archived Program is not planned (and has nothing to offer), a completed one is
     * terminal, and a Program that is not stored is a refusal rather than an empty answer.
     *
     * @return the earliest startable opportunity, or `null` when the Program has none — which is an
     *   answer, not a failure: a Program whose plan has not been scheduled yet legitimately has none.
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
     * The opportunities a Program **being created** receives — §27's creation unit, decided by this same
     * pass.
     *
     * This is the Scheduler's third entry point and it exists for exactly one caller, the import (§30 step
     * 13): §8 requires an accepted import to create
     * `Program + Revision + ProgramDays + ProgramExercises + initial slots` as **one atomic operation**, and
     * §27 names the same unit for `Create / Copy / Import`. A creation cannot go through [schedule],
     * because [schedule] plans a *stored* Program — it reads the Program row, its slots and its pauses —
     * and the whole point of the creation unit is that nothing is stored until everything can be.
     *
     * So the inputs arrive as values instead of being read, and everything else is unchanged:
     *
     * ```text
     * the decision          SlotPlanner.plan over the same request assembly — one implementation, not two
     * the anchor            read from the Program's own facts, exactly as a pass reads it
     *                       (actualStartDate ?: plannedStartDate), and a Program with neither is refused
     *                       with the same NoSchedulingAnchor: this method cannot be used to make the
     *                       Scheduler invent a date either (§3)
     * the slots and pauses  empty — and that is a fact about a Program being created, not an assumption:
     *                       a Program that does not exist yet has no opportunities and no pause intervals
     * the identities        minted from the injected generator, as in a pass, so the caller stores rows it
     *                       did not name
     * ```
     *
     * The second half of the ownership question is what this method **does not have**: no transaction
     * runner is used, no slot is written, and no repository appears beyond the two §26 ports and the
     * calendar. Deciding and persisting are separated here on purpose — the caller that owns the creation
     * unit (`ProgramRepository.createProgram`) is the caller that writes, and the Scheduler's answer is
     * what it writes. That is what keeps *"Scheduler owns timing/opportunities"* true while the importer
     * owns *"what Program is being created"* (§8).
     *
     * @return the opportunities the new revision receives, in date order. The existing-opportunity
     *   reconciliations of a pass are necessarily empty here: there are none to reconcile.
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

    // ---------------------------------------------------------------- the pass

    /**
     * One pass: read, decide, and — when [persist] — write.
     *
     * The read order is the order of the refusals, and it is deliberate. The Program comes first because
     * *"is this Program plannable at all?"* is a question about the Program; the revision second,
     * because a Program with no readable plan has nothing to schedule; the day and the anchor third,
     * because they are the inputs the decision cannot be made without.
     *
     * The anchor is read from the Program's own facts and nowhere else: the date it **actually** started,
     * or the date it is **planned** to start. Nothing else in the graph is consulted, nothing is derived
     * from the lifecycle, and a Program that has neither is refused rather than given today's date.
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

        // One read of the clock, as a date: the pass is made on a day, and every comparison it makes is
        // a comparison of dates (§26 — the clock is injected, never read inside the decision).
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
     * One decision, from one request — the Scheduler's single implementation of *"what does this revision
     * need, from these dates, that it does not have?"*.
     *
     * Both entry points that decide anything go through here: a pass over a stored Program (with its slots
     * and its pauses) and the initial opportunities of a Program being created (with none, because it has
     * none yet). The two differ only in their inputs, which is the point — a second copy of the request
     * assembly would be a second place the window, the anchor and the identity source are decided, and
     * those are exactly the values §20 gives the Scheduler alone.
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
     * A decision that decided nothing opens no transaction and writes no row: "nothing to do" is not a
     * no-op write, it is no write at all, which is what makes the second application of the same pass
     * measurably inert.
     *
     * The order inside the unit is creations first, then supersessions, then misses. The order is not
     * load-bearing (the three lists are disjoint — a created slot is new and the other two name existing
     * rows), and an existing opportunity is never deleted, moved or re-pointed: its status changes, its
     * identity, date and plan day stay, so §20's history survives intact and the change is auditable
     * rather than silent. A completed opportunity is not even superseded — its status is not `PLANNED`,
     * so it is not one of the opportunities a pass may rewrite (§19: a finished workout is history).
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

    /** One decision, as the value a caller receives: the inputs' dates and the three lists. */
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

// ---------------------------------------------------------------- the failures the pass throws

/**
 * The Program a pass names is not stored (§28 `INVALID_DATA`).
 *
 * Thrown inside the pass so the result boundary can map it to a
 * [ProgramSchedulingRefusal.ProgramNotFound] naming the id; it never reaches a caller as an exception.
 */
internal class SchedulingProgramMissing(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramNotFound(programId).message)

/** The Program is archived, and §29's archive stops future planning. */
internal class SchedulingProgramArchived(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramArchived(programId).message)

/** The Program's lifecycle is terminal, so it cannot acquire opportunities (§3). */
internal class SchedulingProgramCompleted(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.ProgramCompleted(programId).message)

/** The revision the Program points at is not stored, so there is no plan to schedule (§23). */
internal class SchedulingRevisionMissing(val programId: ProgramId, val revisionId: RevisionId) :
    RuntimeException(ProgramSchedulingRefusal.RevisionMissing(programId, revisionId).message)

/** The Program has neither started nor been planned to start, so it has no `day 1` to plan from (§3). */
internal class SchedulingNoAnchor(val programId: ProgramId) :
    RuntimeException(ProgramSchedulingRefusal.NoSchedulingAnchor(programId).message)

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramSchedulingResult.Failure] rather
 * than absorbing it (§28's `SYSTEM_FAILURE`, §33's prohibition on turning a failure into an empty
 * result). A refusal is *not* a failure, so the boundary below re-states the ones this class raises.
 */
internal inline fun <T> schedulingResult(block: () -> T): ProgramSchedulingResult<T> = try {
    ProgramSchedulingResult.Success(block())
} catch (failure: Throwable) {
    ProgramSchedulingResult.Failure(failure)
}

/** Maps a pass's internal failures onto the typed refusals the caller receives. */
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
