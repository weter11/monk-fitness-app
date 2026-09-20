package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * The Home screen's Program state: **which Program is being worked on, and what is next in it.**
 *
 * ### Why it is its own state holder
 *
 * Home asks a different question from the Programs hub and from the Detail screen: *"what, if anything,
 * can I do right now?"* That is one Program's next startable opportunity plus the calendar's own counts,
 * and it is read on every visit. Putting it in [ProgramsController] would make the hub's state — a list,
 * a draft, an import review, a detail — carry a screen's worth of unrelated facts.
 *
 * ### What it reads, and from whom
 *
 * ```text
 * the selected Program      ProgramLifecycleService.myPrograms()   (selection is not a Program property, §3)
 * the next opportunity      ProgramScheduler.nextOpportunity(id)   (the Scheduler owns opportunities, §20)
 * the calendar's counts     ProgramProgressService.calendarProgress / trainingProgress (§21, §30 step 9)
 * ```
 *
 * No repository, no DAO and no storage type appears here, and nothing is written: Home is a read of four
 * facts other layers own, and a start is a *navigation* to the session route with the opportunity's id.
 *
 * ### A failure is not an absence
 *
 * `nextSlotId == null` means *"there is no opportunity to start"*, which is an ordinary answer: a Program
 * that has not been scheduled yet, or whose plan is over. A **failure to read** the schedule is a
 * different fact and is carried separately ([ProgramHomeUiState.scheduleUnreadable]), so the screen says
 * the schedule could not be read instead of showing "nothing planned" (§15, §28, §33).
 *
 * @param lifecycle the selection and the Program's own name and lifecycle (§3).
 * @param scheduler the next startable opportunity (§20). Asked for a read, never for a pass.
 * @param progress §21's counts and the Program's own streak.
 */
class ProgramHomeController(
    private val lifecycle: ProgramLifecycleService,
    private val scheduler: ProgramScheduler,
    private val progress: ProgramProgressService
) {

    private val mutableState = MutableStateFlow(ProgramHomeUiState())

    /** The screen's state. */
    val state: StateFlow<ProgramHomeUiState> = mutableState.asStateFlow()

    /**
     * Reads the selected Program, its next opportunity and its calendar.
     *
     * A failure anywhere is reported and leaves the rest of the state honest rather than blank: the name
     * and lifecycle are still shown when only the schedule could not be read, and the counts stay at zero
     * while saying so out loud.
     */
    suspend fun load() {
        mutableState.update { state -> state.copy(loading = true, notice = null) }

        val selection = try {
            lifecycle.myPrograms()
        } catch (failure: Throwable) {
            return failed()
        }
        val selected = when (selection) {
            is ProgramOperationResult.Success -> selection.value.selectedRow
            is ProgramOperationResult.Refused -> return empty()
            is ProgramOperationResult.Failure -> return failed()
        } ?: return empty()

        val programId = ProgramId(selected.programId.value)
        var state = mutableState.value.copy(
            loading = false,
            hasProgram = true,
            programId = programId.value,
            programName = selected.name,
            lifecycleStatus = selected.lifecycleStatus,
            nextSlotId = null,
            nextPlannedFor = null,
            scheduleUnreadable = false
        )

        when (val next = try {
            scheduler.nextOpportunity(programId)
        } catch (failure: Throwable) {
            null
        }) {
            null -> state = state.copy(scheduleUnreadable = true, notice = ProgramNotice.STORAGE_FAILED)

            is ProgramSchedulingResult.Success -> state = state.copy(
                nextSlotId = next.value?.slotId?.value,
                nextPlannedFor = next.value?.plannedFor
            )

            is ProgramSchedulingResult.Refused -> state = state.copy(
                nextSlotId = null,
                nextPlannedFor = null
            )

            is ProgramSchedulingResult.Failure ->
                state = state.copy(scheduleUnreadable = true, notice = ProgramNotice.STORAGE_FAILED)
        }

        val scope = ProgressScope.OfProgram(programId)
        try {
            val calendar = progress.calendarProgress(scope)
            val training = progress.trainingProgress(scope)
            state = state.copy(
                completed = calendar.completed,
                missed = calendar.missed,
                upcoming = calendar.upcoming,
                streak = training.streaks.firstOrNull { streak -> streak.programId == programId }?.current ?: 0
            )
        } catch (failure: Throwable) {
            state = state.copy(
                completed = 0,
                missed = 0,
                upcoming = 0,
                streak = 0,
                notice = ProgramNotice.STORAGE_FAILED
            )
        }

        mutableState.value = state
    }

    /** Clears the sentence the screen is showing. */
    fun dismissNotice() {
        mutableState.update { state -> state.copy(notice = null) }
    }

    private fun empty() {
        mutableState.value = ProgramHomeUiState(loading = false, hasProgram = false)
    }

    private fun failed() {
        mutableState.value = ProgramHomeUiState(
            loading = false,
            hasProgram = false,
            scheduleUnreadable = true,
            notice = ProgramNotice.STORAGE_FAILED
        )
    }
}

/**
 * What Home shows about the Program being worked on.
 *
 * @property loading whether the read is still running.
 * @property hasProgram whether a Program is selected at all. §12 makes the Standard Program the
 *   technical fallback, so a `false` here is a read that found no selection rather than "no programs
 *   exist" — the screen offers the Programs hub in both cases.
 * @property nextSlotId the opportunity a workout can be started on, or `null` when there is none.
 * @property nextPlannedFor the date that opportunity is planned for.
 * @property scheduleUnreadable whether the schedule could not be read at all. Kept apart from
 *   *"there is no opportunity"*, which is [showsNoPlannedDate].
 */
data class ProgramHomeUiState(
    val loading: Boolean = true,
    val hasProgram: Boolean = false,
    val programId: String? = null,
    val programName: String? = null,
    val lifecycleStatus: LifecycleStatus? = null,
    val nextSlotId: String? = null,
    val nextPlannedFor: LocalDate? = null,
    val completed: Int = 0,
    val missed: Int = 0,
    val upcoming: Int = 0,
    val streak: Int = 0,
    val scheduleUnreadable: Boolean = false,
    val notice: ProgramNotice? = null
) {

    /** Whether there is a workout to start right now. */
    val canStartWorkout: Boolean
        get() = nextSlotId != null

    /**
     * Whether the screen shows the ordinary *"nothing is planned yet"* line. False whenever the schedule
     * could not be read, because a failure is not an absence.
     */
    val showsNoPlannedDate: Boolean
        get() = !scheduleUnreadable && nextSlotId == null

    /** The calendar's own total, or `0` before anything was planned. */
    val opportunityCount: Int
        get() = completed + missed + upcoming

    /** Whether any opportunity was decided or is still ahead — what a progress bar may be drawn from. */
    val hasAnyOpportunity: Boolean
        get() = opportunityCount > 0
}
