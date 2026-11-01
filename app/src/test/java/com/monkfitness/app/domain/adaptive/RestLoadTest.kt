package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rest reading a window contributes, and what it contributes when it recorded none.
 *
 * The absence case is the one that matters: a window whose sets all predate the rest column must not be
 * read as *"the user rested for zero seconds"*, because the density channel would then compare a plan's
 * unrecorded rest against a measured zero and call it a difference.
 */
class RestLoadTest {

    @Test
    fun unknownRestsDoNotDragTheMedianTowardsZero() {
        assertEquals(
            "one measured rest is the median, whatever the unmeasured sets around it are",
            90,
            medianKnownRestSeconds(listOf(null, 90, null))
        )
        assertEquals(
            "the median is taken over the measured sets only: 80 and 120, not 0 in between",
            100,
            medianKnownRestSeconds(listOf(80, null, 120))
        )
        assertEquals(
            "an odd count answers its middle reading, unrounded",
            90,
            medianKnownRestSeconds(listOf(120, 75, 90))
        )
    }

    @Test
    fun aWindowThatRecordedNoRestAnswersAbsent() {
        assertNull(
            "no measured rest is an absence, not a rest of zero seconds",
            medianKnownRestSeconds(listOf(null, null))
        )
        assertNull("and an empty window has nothing to summarise", medianKnownRestSeconds(emptyList()))
    }

    @Test
    fun aMeasuredRestOfZeroIsAReadingAndStillCounts() {
        assertEquals(
            "a set the user went straight on from is a measurement: it is not the same fact as an " +
                "unrecorded rest",
            0,
            medianKnownRestSeconds(listOf(0, null))
        )
    }
}
