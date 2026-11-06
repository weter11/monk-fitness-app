package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SessionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every other day" beside "Every day": the two named frequencies share one chip row, so what this
 * states is that the interval form saves, reopens and is judged on its own — and that the seven-day
 * warning never attaches to it.
 *
 * The reopen claim is the chip's own predicate: a chip is selected when the draft's schedule equals the
 * value it sets, so a draft that reopens as `EveryNDays(2)` reopens with that chip selected and no other.
 */
class ProgramsEveryOtherDayFrequencyTest {

    private val rig = ProgramsRig("everyotherday")

    private val everyOtherDay = ProgramSchedule.EveryNDays(2)

    @After
    fun tearDown() {
        rig.close()
    }

    @Test
    fun everyOtherDayIsSavedAndReopensAsTheIntervalForm() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        rig.controller.setDraftName("Every other day")
        rig.controller.setDraftSchedule(everyOtherDay)
        assertTrue("the pass produces a plan to apply", rig.controller.generateDraft())

        assertEquals(
            "four whole weeks at an interval of two is fourteen plan days",
            14,
            rig.state.draft?.days?.size
        )
        assertTrue("a generated draft with a plan is savable", rig.controller.saveDraft())

        val programId = rig.state.rows.single { row -> row.name == "Every other day" }.programId
        assertEquals(
            "the stored Revision holds the interval itself — not a week it was rounded into",
            everyOtherDay,
            rig.transfer.currentRevision(ProgramId(programId))?.schedule
        )

        rig.controller.openEditDraft(programId)

        assertEquals(
            "a reopened draft shows the interval the user chose, which is what selects its chip",
            everyOtherDay,
            rig.state.draft?.schedule
        )
    }

    @Test
    fun theSevenDayWarningNeverAttachesToTheIntervalForm() = runBlocking {
        listOf(
            "Mixed" to emptySet<SessionType>(),
            "Strength" to setOf(SessionType.STRENGTH),
            "Strength and mobility" to setOf(SessionType.STRENGTH, SessionType.MOBILITY)
        ).forEach { (name, selection) ->
            assertFalse(
                "Every other day with $name trains every second day, so it leaves rest days and earns " +
                    "no warning",
                sevenDayWeekLeavesNoRestDay(everyOtherDay, ProgramMode.GENERATED, selection)
            )
        }

        val reasons = previewOf(setOf(SessionType.STRENGTH)).map { limitation -> limitation.reasonRes }
        assertFalse(
            "and the Preview's limitations do not claim a missing rest day for it: $reasons",
            ProgramGenerationPreviewRes.NO_REST_DAY in reasons
        )
    }

    /** The Preview's limitations for an Every other day draft with [selection], through the controller. */
    private suspend fun previewOf(selection: Set<SessionType>): List<ProgramGenerationLimitationUi> {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        rig.controller.setDraftName("Every other day preview")
        rig.controller.setDraftSchedule(everyOtherDay)
        selection.forEach { type -> rig.controller.toggleDraftSessionType(type) }
        assertTrue("$selection: the pass produces a preview", rig.controller.previewDraft())
        return rig.state.generationPreview?.limitations.orEmpty()
    }
}
