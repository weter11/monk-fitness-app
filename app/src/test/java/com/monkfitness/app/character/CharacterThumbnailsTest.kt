package com.monkfitness.app.character

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterThumbnailsTest {

    private val squat = CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL)

    @Test
    fun anUnchangedRequestHitsTheSameEntry() {
        val same = CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL)
        assertTrue(squat == same)
    }

    @Test
    fun exerciseMorphologyTierAndFrameAllInvalidateTheEntry() {
        assertNotEquals(squat, CharacterThumbnails.key("pushup_standard", MorphologyPreset.NEUTRAL))
        assertNotEquals(squat, CharacterThumbnails.key("squat_standard", MorphologyPreset.HEAVY))
        assertNotEquals(squat, CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL, tier = "reduced"))
        assertNotEquals(squat, CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL, progress = 0.9f))
        assertNotEquals(
            squat,
            squat.copy(assetVersion = squat.assetVersion + "-next")
        )
    }

    @Test
    fun progressIsQuantisedSoLiveMotionDoesNotFloodTheCache() {
        val a = CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL, progress = 0.5f)
        val b = CharacterThumbnails.key("squat_standard", MorphologyPreset.NEUTRAL, progress = 0.504f)
        assertTrue(a == b)
    }

    @Test
    fun theBudgetCapsBothEntriesAndBytes() {
        assertTrue(CharacterThumbnails.fitsBudget(0))
        assertTrue(CharacterThumbnails.fitsBudget(CharacterThumbnails.MAX_ENTRIES))
        assertFalse(CharacterThumbnails.fitsBudget(CharacterThumbnails.MAX_ENTRIES + 1))
        assertTrue(
            "a 192px RGBA thumbnail must stay small enough for the budget",
            CharacterThumbnails.bitmapBytes() * CharacterThumbnails.MAX_ENTRIES <= CharacterThumbnails.MAX_CACHE_BYTES
        )
    }
}
