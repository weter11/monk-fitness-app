package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.ProgramSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's Program card, over the production controllers — the P1 half of the creation remediation.
 *
 * The defect this suite exists for: `ProgramHomeController.load()` was complete and correct, and
 * **nothing in production called it** — Home rendered the state holder's initial value (no Program,
 * no next workout, no Start) no matter what had been created, selected and scheduled. The read path
 * itself is what is measured here:
 *
 * ```text
 * a created Program + the user's selection → Home loads the Program, its lifecycle and the
 * Scheduler's own next opportunity → Start becomes available
 * no selection                            → Home says so, inventing nothing
 * a schedule that cannot be read           → that is a failure, not an absence (§15, §33)
 * ```
 *
 * That *the screen invokes this path* is a source-level claim, pinned in
 * `ProgramsArchitectureTest.homeIsLoadedByALifecycleAwareEffectWhenTheScreenOpens` — this project
 * has no Compose harness, so the controller's behaviour and the screen's call site are the two
 * halves, each measured by the layer that can be measured.
 */
class ProgramHomeControllerTest {

    private val rig = ProgramsRig("home")

    @After
    fun tearDown() {
        rig.close()
    }

    private fun homeController() = ProgramHomeController(
        lifecycle = rig.transfer.lifecycleService,
        scheduler = rig.transfer.scheduler,
        progress = rig.progress
    )

    @Test
    fun aSelectedProgramWithSlotsShowsItsNextWorkout() = runBlocking {
        val created = rig.createProgramThroughTheUi("Morning plan")!!
        rig.controller.select(created)
        val home = homeController()

        home.load()

        val state = home.state.value
        assertFalse("the load completed", state.loading)
        assertTrue("Home carries the selected Program", state.hasProgram)
        assertEquals(created, state.programId)
        assertEquals("Morning plan", state.programName)
        assertEquals(LifecycleStatus.NOT_STARTED, state.lifecycleStatus)

        // The creation unit stored initial opportunities; the Scheduler names the next one — this is
        // what makes "Start becomes available" true without a manual scheduling step anywhere.
        assertFalse("the schedule was read, not guessed", state.scheduleUnreadable)
        assertNotNull("there is an opportunity to start", state.nextSlotId)
        assertNotNull("with a date", state.nextPlannedFor)
        assertTrue("so Start is available", state.canStartWorkout)
        assertFalse(
            "and this is not the *nothing planned* state",
            state.showsNoPlannedDate
        )
        assertNull("loading published no error", state.notice)

        // The next opportunity is the Scheduler's own answer, not a date the controller derived.
        val next = when (val result = rig.transfer.scheduler.nextOpportunity(
            com.monkfitness.app.domain.common.ProgramId(created)
        )) {
            is ProgramSchedulingResult.Success -> result.value
            else -> error("the Scheduler refused a Program it just planned")
        }
        assertEquals(next!!.slotId.value, state.nextSlotId)
        assertEquals(next.plannedFor, state.nextPlannedFor)
    }

    @Test
    fun withNoSelectionHomeOffersNothingRatherThanInventingIt() = runBlocking {
        rig.controller.load() // the list itself must be healthy for this to be about the selection
        val home = homeController()

        home.load()

        val state = home.state.value
        assertFalse("no selection means no Program, not an error", state.hasProgram)
        assertNull(state.programId)
        assertNull(state.nextSlotId)
        assertFalse(state.canStartWorkout)
        assertFalse("and nothing was unreadable either", state.scheduleUnreadable)
        assertNull(state.notice)
    }

    @Test
    fun aScheduleThatCannotBeReadIsAFailureAndNotAnAbsence() = runBlocking {
        val created = rig.createProgramThroughTheUi("Faulty plan")!!
        rig.controller.select(created)
        val home = homeController()
        home.load()
        assertTrue("healthy first: the card has an opportunity", home.state.value.canStartWorkout)

        rig.transfer.data.faults.failSlotRead = true
        try {
            home.load()
        } finally {
            rig.transfer.data.faults.failSlotRead = false
        }

        val state = home.state.value
        assertTrue("§15/§33: a failed read is carried as its own fact", state.scheduleUnreadable)
        assertEquals(ProgramNotice.STORAGE_FAILED, state.notice)
        assertFalse(
            "and it is never rendered as the ordinary *nothing planned* line",
            state.showsNoPlannedDate
        )
        assertEquals(
            "the Program's own facts are still shown — only the schedule leg failed",
            "Faulty plan",
            state.programName
        )
    }

    @Test
    fun theSelectionStaysUntouchedByReadingHome() = runBlocking {
        val created = rig.createProgramThroughTheUi("Untouched")!!
        rig.controller.select(created)

        homeController().load()
        homeController().load()

        assertEquals(
            "Home reads four facts and writes none — selection included (§3, §21)",
            com.monkfitness.app.domain.common.ProgramId(created),
            rig.selectedProgramId()
        )
        assertEquals(
            "and a read is not a create: the list holds exactly the Program the user made",
            1,
            rig.state.rows.count { row -> row.source == ProgramSource.USER }
        )
    }
}
