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
 * Home asks a different question from the Programs hub and the Detail screen: *"what, if anything, can I
 * do right now?"* — one Program's next startable opportunity plus the calendar's own counts, read on every
 * visit. It is its own holder so the hub's state does not carry a screen's worth of unrelated facts.
 *
 * It reads the selected Program from [ProgramLifecycleService.myPrograms] (selection is not a Program
 * property), the next opportunity from [ProgramScheduler.nextOpportunity] (the scheduler owns
 * opportunities) and the counts from [ProgramProgressService]. No repository, no DAO and no storage type
 * appears here, and nothing is written: a start is a *navigation* to the session route with the
 * opportunity's id.
 *
 * `nextSlotId == null` means *"there is no opportunity to start"*, which is an ordinary answer: a Program
 * not yet scheduled, or whose plan is over. A **failure to read** the schedule is a different fact and is
 * carried separately ([ProgramHomeUiState.scheduleUnreadable]), so the screen says the schedule could not
 * be read instead of showing "nothing planned".
 */
class ProgramHomeController(
    private val lifecycle: ProgramLifecycleService,
    private val scheduler: ProgramScheduler,
    private val progress: ProgramProgressService
) {

    private val mutableState = MutableStateFlow(ProgramHomeUiState())

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
 * [hasProgram] being `false` is a read that found no selection rather than "no programs exist", because
 * the Standard Program is the technical fallback — the screen offers the Programs hub in both cases.
 * [scheduleUnreadable] is kept apart from *"there is no opportunity"*, which is [showsNoPlannedDate].
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

    val canStartWorkout: Boolean
        get() = nextSlotId != null

    /**
     * Whether the screen shows the ordinary *"nothing is planned yet"* line. False whenever the schedule
     * could not be read, because a failure is not an absence.
     */
    val showsNoPlannedDate: Boolean
        get() = !scheduleUnreadable && nextSlotId == null

    val opportunityCount: Int
        get() = completed + missed + upcoming

    /** Whether any opportunity was decided or is still ahead — what a progress bar may be drawn from. */
    val hasAnyOpportunity: Boolean
        get() = opportunityCount > 0
}
