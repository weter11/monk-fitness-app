package com.monkfitness.app.character

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterSlotTest {

    @Test
    fun onlyTheFirstOwnerGetsTheSlot() {
        val slot = CharacterSlot()
        val first = Any()
        val second = Any()

        assertTrue(slot.acquire(first))
        assertFalse(slot.acquire(second))
        assertTrue(slot.isHeldBy(first))
        assertTrue(slot.isOccupied)
    }

    @Test
    fun reacquiringWithTheSameTokenIsIdempotent() {
        val slot = CharacterSlot()
        val token = Any()

        assertTrue(slot.acquire(token))
        assertTrue(slot.acquire(token))
    }

    @Test
    fun aTokenCannotReleaseAnotherOwnersSlot() {
        val slot = CharacterSlot()
        val owner = Any()
        val stranger = Any()
        slot.acquire(owner)

        assertFalse(slot.release(stranger))
        assertTrue(slot.isHeldBy(owner))
    }

    @Test
    fun releasingFreesTheSlotForTheNextView() {
        val slot = CharacterSlot()
        val first = Any()
        val next = Any()
        slot.acquire(first)

        assertTrue(slot.release(first))
        assertFalse(slot.isOccupied)
        assertTrue(slot.acquire(next))
    }
}
