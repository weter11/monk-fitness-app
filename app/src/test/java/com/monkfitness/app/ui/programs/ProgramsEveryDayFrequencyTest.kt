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
 * "Every day" as a frequency, measured through the **controller** on the real engine.
 *
 * Seven sessions a week is already the domain's own `FlexiblePerWeek(7)`: what this stage adds is the
 * chip that names it, the fact that a saved and reopened draft still shows it, and the one warning a
 * seven-day plan earns — it holds no rest day. The warning is information, so the tests measure where
 * it appears, never that it prevented anything.
 */
class ProgramsEveryDayFrequencyTest {

    private val rig = ProgramsRig("everyday")

    private val everyDay = ProgramSchedule.FlexiblePerWeek(7)

    @After
    fun tearDown() {
        rig.close()
    }

    @Test
    fun everyDayIsSavedAndReopensAsSevenSessionsAWeek() = runBlocking {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        rig.controller.setDraftName("Every day")
        rig.controller.setDraftSchedule(everyDay)
        assertTrue("the pass produces a plan to apply", rig.controller.generateDraft())

        assertEquals(
            "four whole weeks of seven days each is the plan the draft now holds",
            28,
            rig.state.draft?.days?.size
        )
        assertTrue("a generated draft with a plan is savable", rig.controller.saveDraft())

        val programId = rig.state.rows.single { row -> row.name == "Every day" }.programId
        assertEquals(
            "the stored Revision holds the frequency itself — not a rounded or capped one",
            everyDay,
            rig.transfer.currentRevision(ProgramId(programId))?.schedule
        )

        rig.controller.openEditDraft(programId)

        assertEquals(
            "a reopened draft shows the choice the user made, which is what selects the Every day chip",
            everyDay,
            rig.state.draft?.schedule
        )
    }

    @Test
    fun theWarningFollowsTheScheduleAndTheSelection() = runBlocking {
        val cases = listOf(
            Triple<String, Set<SessionType>, Boolean>("Mixed", emptySet(), true),
            Triple("Strength", setOf(SessionType.STRENGTH), true),
            Triple("Strength and mobility", setOf(SessionType.STRENGTH, SessionType.MOBILITY), true),
            Triple("Mobility", setOf(SessionType.MOBILITY), false),
            Triple("Posture", setOf(SessionType.POSTURE), false),
            Triple("Hyperlordosis", setOf(SessionType.HYPERLORDOSIS), false),
            Triple(
                "Posture and hyperlordosis",
                setOf(SessionType.POSTURE, SessionType.HYPERLORDOSIS),
                false
            )
        )

        cases.forEach { (name, selection, warned) ->
            assertEquals(
                "Every day with $name: a warning only where the plan can hold loaded work and no rest",
                warned,
                sevenDayWeekLeavesNoRestDay(everyDay, ProgramMode.GENERATED, selection)
            )
        }

        assertFalse(
            "six sessions a week leaves a rest day, so the same strength selection is not warned about",
            sevenDayWeekLeavesNoRestDay(
                ProgramSchedule.FlexiblePerWeek(6),
                ProgramMode.GENERATED,
                setOf(SessionType.STRENGTH)
            )
        )
        assertFalse(
            "and a manual draft has no session-type selection to judge, so it claims nothing",
            sevenDayWeekLeavesNoRestDay(everyDay, ProgramMode.MANUAL, emptySet())
        )
    }

    @Test
    fun thePreviewShowsTheWarningInItsLimitationsOnlyWhereItApplies() = runBlocking {
        val cases = listOf(
            Triple<String, Set<SessionType>, Boolean>("Mixed", emptySet(), true),
            Triple("Strength", setOf(SessionType.STRENGTH), true),
            Triple("Mobility", setOf(SessionType.MOBILITY), false),
            Triple("Posture", setOf(SessionType.POSTURE), false),
            Triple("Hyperlordosis", setOf(SessionType.HYPERLORDOSIS), false)
        )

        cases.forEach { (name, selection, warned) ->
            val reasons = previewOf(selection).map { limitation -> limitation.reasonRes }
            assertEquals(
                "Every day with $name: the Preview's limitations $name the missing rest day only " +
                    "when one is missing",
                warned,
                ProgramGenerationPreviewRes.NO_REST_DAY in reasons
            )
        }
    }

    /** The Preview's limitations for an Every day draft with [selection], read through the controller. */
    private suspend fun previewOf(
        selection: Set<SessionType>,
        schedule: ProgramSchedule = everyDay
    ): List<ProgramGenerationLimitationUi> {
        rig.controller.openCreateDraft(ProgramMode.GENERATED)
        rig.controller.setDraftName("Every day preview")
        rig.controller.setDraftSchedule(schedule)
        SessionType.entries.forEach { type ->
            if (type in selection) rig.controller.toggleDraftSessionType(type)
        }
        assertTrue("$selection: the pass produces a preview", rig.controller.previewDraft())
        return rig.state.generationPreview?.limitations.orEmpty()
    }
}
