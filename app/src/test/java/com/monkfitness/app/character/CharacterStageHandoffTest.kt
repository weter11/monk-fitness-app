package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage's claim on the single-owner character slot, in the order a keyed exercise switch runs it.
 *
 * A `DisposableEffect` disposes its previous block before it runs the block for a new key, which is
 * what makes a claim after the commit safe: an exercise switch releases the slot before it takes it.
 * A claim made during composition instead arrives while the outgoing stage is still alive, is
 * refused, and is never retried — which is how a `Squat → Push-up` switch used to lose the session.
 */
class CharacterStageHandoffTest {

    @Test
    fun anExerciseSwitchReleasesBeforeItClaimsAndKeepsTheSlot() {
        val slot = CharacterSlot()

        val squat = slot.claim { "squat session" }
        assertTrue("the first stage must get the slot", squat.slotWasFree)
        assertEquals("squat session", squat.session)
        assertTrue(slot.isHeldBy(squat.token))

        // Squat → Push-up, in the order the stage now runs it: the outgoing dispose, then the claim.
        assertTrue(squat.release())
        val pushup = slot.claim { "push-up session" }
        assertTrue("the incoming stage must find the slot free", pushup.slotWasFree)
        assertEquals("push-up session", pushup.session)

        // …and back again.
        assertTrue(pushup.release())
        val squatAgain = slot.claim { "squat session" }
        assertTrue(squatAgain.slotWasFree)
        assertEquals("squat session", squatAgain.session)
        assertTrue("exactly one stage owns the slot at the end", slot.isHeldBy(squatAgain.token))
        assertTrue("every claim is its own token", squatAgain.token !== squat.token)
    }

    @Test
    fun aClaimMadeWhileTheOutgoingStageStillHoldsTheSlotIsRefusedAndNotRetried() {
        // The ordering the stage must not use: the new attachment is built during composition, before
        // the outgoing stage's effect is disposed.
        val slot = CharacterSlot()
        val squat = slot.claim { "squat session" }

        val pushup = slot.claim { "push-up session" }
        assertFalse("the incoming claim must not displace a live stage", pushup.slotWasFree)
        assertNull("nothing is built for a refused claim", pushup.session)
        assertTrue("the outgoing stage keeps the slot", slot.isHeldBy(squat.token))

        assertTrue(squat.release())
        assertNull("a refused claim does not pick the slot up when it frees", pushup.session)
        assertFalse(slot.isOccupied)
    }

    @Test
    fun aStageThatCannotStartLeavesTheSlotFree() {
        val slot = CharacterSlot()

        val failed = slot.claim<String> { null }

        assertTrue("the slot was free, the build simply produced nothing", failed.slotWasFree)
        assertNull(failed.session)
        assertFalse("a stage with no session must not hold the character", slot.isOccupied)
    }

    @Test
    fun aBuildThatThrowsLeavesTheSlotFree() {
        val slot = CharacterSlot()

        assertThrows(IllegalStateException::class.java) {
            slot.claim<String> { error("the asset did not load") }
        }

        assertFalse("a failed load must not leave the slot claimed", slot.isOccupied)
    }

    @Test
    fun aReplacedStagesReleaseCannotFreeTheIncomingStagesSlot() {
        val slot = CharacterSlot()
        val squat = slot.claim { "squat session" }
        squat.release()
        val pushup = slot.claim { "push-up session" }

        assertFalse("an outgoing release must not free its successor", squat.release())
        assertTrue(slot.isHeldBy(pushup.token))
        assertTrue(pushup.release())
        assertFalse(slot.isOccupied)
    }
}
