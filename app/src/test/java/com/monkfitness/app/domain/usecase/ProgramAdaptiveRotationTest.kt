package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.workout.AdaptiveOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target rule on a **rotating** plan, where each family appears on its own days and "the plan's next
 * workout" is usually a day the family is absent from.
 *
 * The rig's rotation presents the other day on 09-22 and the push day again on 09-23, so the two answers
 * the rule can give are two different opportunities and the test can tell them apart.
 */
class ProgramAdaptiveRotationTest {

    private val rig = ProgramAdaptiveIntegrationRig.of()

    @After
    fun close() = rig.close()

    @Test
    fun aFamilyIsAdaptedAtItsOwnNextAppearanceNotAtThePlansNextWorkout() = runBlocking {
        rig.createRotationGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()

        assertEquals(
            "the scenario is what it says: the next workout is the day the family is absent from",
            listOf(
                ProgramDayId(ProgramAdaptiveIntegrationRig.dayId(2)),
                ProgramDayId(ProgramAdaptiveIntegrationRig.dayId(1))
            ),
            listOf(rig.storedSlot(rig.slot(5)).programDayId, rig.storedSlot(rig.slot(6)).programDayId)
        )

        val (outcome, completion) = rig.completeWithAdaptive(trigger.sessionId)

        assertTrue("the window earned a progression: $outcome", outcome is AdaptiveIntegrationOutcome.AdaptiveApplied)
        val applied = outcome as AdaptiveIntegrationOutcome.AdaptiveApplied

        assertEquals(
            "the decision is about the family's own next appearance, not the next workout",
            rig.slot(6),
            applied.decision.slotId
        )
        assertEquals(
            "so the change is stored on 09-23 while the next workout holds nothing",
            listOf(1, 0),
            listOf(rig.storedAdjustments(rig.slot(6)).size, rig.storedAdjustments(rig.slot(5)).size)
        )
        assertEquals(
            "and the completion accepted it: the runtime's future-opportunity check is the same rule",
            applied.decision.decisionId,
            (completion.adaptive as AdaptiveOutcome.Stored).decisionId
        )
    }

    @Test
    fun aFamilyTheRevisionNeverPresentsAgainKeepsItsWindowAndSaysThePlanEnded() = runBlocking {
        rig.createRotationGraph()
        rig.seedFamilyState(level = 1, precedingProgressQualifyingWindows = 1)
        val trigger = rig.seedHistoryAndTrigger()
        // The family's only remaining appearance is withdrawn. The plan itself still has one ahead.
        rig.database.exec(
            "UPDATE `program_workout_slot` SET `status` = 'SUPERSEDED' " +
                "WHERE `slotId` = '${rig.slot(6).value}'"
        )
        val before = rig.counts()

        val outcome = rig.outcomeOf(rig.adapt(trigger.sessionId))

        assertTrue(
            "the window was still read, which is a different answer from \"no window\": $outcome",
            outcome is AdaptiveIntegrationOutcome.WindowOnly
        )
        val window = outcome as AdaptiveIntegrationOutcome.WindowOnly
        assertEquals(
            "and it says the plan has no further appearance of this family, not that the next workout " +
                "does not present it",
            ProgramAdaptiveReason.NO_FURTHER_APPEARANCE,
            window.reason
        )
        assertEquals("the pass still names the family", ProgramAdaptiveIntegrationRig.FAMILY, window.familyId)
        assertNotNull("and advances its bookkeeping rather than dropping it", window.familyState)
        assertEquals(
            "nothing is written: no decision, no adjustment",
            listOf(0, 0),
            listOf(
                rig.database.count("program_adaptive_decision_record"),
                rig.database.count("adaptive_adjustment")
            )
        )
        assertEquals("and no other row changed", before, rig.counts())
    }
}
