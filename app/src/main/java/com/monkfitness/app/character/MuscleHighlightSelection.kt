package com.monkfitness.app.character

/** The only GPU operation a selection performs; geometry, skinning and renderable state are absent by design. */
internal interface MuscleHighlightTarget {
    fun setHighlight(section: Int, on: Boolean)
}

/**
 * The stage's selection decision, independent of Filament and testable on the JVM: it reports which
 * section gains the highlight and which one loses it, and never more than those two writes.
 */
internal class MuscleHighlightSelection(private val target: MuscleHighlightTarget) {
    private var selected = MuscleSectionState.NO_SECTION

    val section: Int get() = selected

    fun select(section: Int) {
        require(section == MuscleSectionState.NO_SECTION || section >= 0)
        if (selected == section) return
        if (selected != MuscleSectionState.NO_SECTION) target.setHighlight(selected, on = false)
        selected = section
        if (selected != MuscleSectionState.NO_SECTION) target.setHighlight(selected, on = true)
    }
}
