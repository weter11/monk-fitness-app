package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A partition's sections share one vertex buffer and one index buffer, so their slices must tile
 * both without a gap — and a steady mask or selection must report no change, because the stage only
 * rewrites the streams a change names.
 */
class MuscleSectionLayoutTest {

    @Test
    fun theSlicesTileTheMergedBuffersWithoutAGap() {
        // A 106-vertex section is 208 triangles, so 624 indices; a two-head one doubles both.
        val layout = MuscleSectionLayout.ofCounts(listOf(106 to 624, 212 to 1248, 106 to 624))

        assertEquals("three slices", 3, layout.slices.size)
        assertEquals("vertices are concatenated", 424, layout.vertexCount)
        assertEquals("indices are concatenated", 2496, layout.indexCount)
        var nextVertex = 0
        var nextIndex = 0
        for (slice in layout.slices) {
            assertEquals("a slice starts where the last one ended", nextVertex, slice.vertexOffset)
            assertEquals("an index slice starts where the last one ended", nextIndex, slice.indexOffset)
            nextVertex += slice.vertexCount
            nextIndex += slice.indexCount
        }
        assertEquals("the last slice ends at the merged count", layout.vertexCount, nextVertex)
        assertEquals("the last index ends at the merged count", layout.indexCount, nextIndex)
    }

    @Test
    fun aSectionThatIsNotWholeTrianglesIsRefused() {
        val error = runCatching { MuscleSectionLayout.ofCounts(listOf(106 to 622)) }.exceptionOrNull()
        assertTrue("an open triangle run is refused", error is IllegalArgumentException)
    }

    @Test
    fun aSteadyMaskAndSelectionReportNoChange() {
        val state = MuscleSectionState(4)
        state.showAll()

        assertTrue("hiding one section moves the mask", state.show(1, false))
        assertFalse("re-hiding it rewrites nothing", state.show(1, false))
        assertTrue("showing it again moves the mask", state.show(1, true))
        assertFalse("re-showing it rewrites nothing", state.show(1, true))

        assertTrue("selecting a section moves the highlight", state.select(2))
        assertFalse("re-selecting it rewrites nothing", state.select(2))
        assertTrue("clearing the selection moves it", state.select(MuscleSectionState.NO_SECTION))
        assertFalse("clearing it twice rewrites nothing", state.select(MuscleSectionState.NO_SECTION))
    }

    @Test
    fun aSelectionWritesOneSectionPerChange() {
        var writes = 0
        val selection = MuscleHighlightSelection(object : MuscleHighlightTarget {
            override fun setHighlight(section: Int, on: Boolean) {
                writes++
            }
        })

        selection.select(1)
        assertEquals("the first selection writes one section", 1, writes)
        selection.select(1)
        assertEquals("re-selecting it writes nothing", 1, writes)
        selection.select(2)
        assertEquals("a move writes the section that lost and the one that won", 3, writes)
        selection.select(MuscleSectionState.NO_SECTION)
        assertEquals("clearing writes the last section only", 4, writes)
    }
}
