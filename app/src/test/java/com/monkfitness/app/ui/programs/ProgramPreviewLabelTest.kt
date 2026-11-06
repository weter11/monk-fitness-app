package com.monkfitness.app.ui.programs

import com.monkfitness.app.ui.screens.previewElementLabelText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview line joins a name and a prescription with a middle dot; a source escape that reaches the
 * screen is text the user cannot read.
 */
class ProgramPreviewLabelTest {

    @Test
    fun theSeparatorReachesTheLabelAsAMiddleDot() {
        val label = previewElementLabelText("Standard Burpees", "3 sets of 6 / 6 / 6 reps")

        assertEquals("Standard Burpees · 3 sets of 6 / 6 / 6 reps", label)
        assertTrue("expected a middle dot in $label", label.contains("·"))
        assertFalse("expected no raw unicode escape in $label", label.contains("\\u"))
    }
}
