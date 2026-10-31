package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * The application's global runtime state, as far as the Program System owns it.
 *
 * Two facts, both *global* rather than Program-owned: which Program the user is currently in, and which
 * one the user chose to start next (plus whether that start is automatic). Keeping them here is what
 * stops a Program from carrying a "selected" flag that could contradict the truth, and why deleting a
 * Program is a decision the *database* can refuse while this state still points at it.
 *
 * The type is a value with no behaviour: no fallback rule, no auto-start evaluation, no lifecycle
 * transition and no history. Those are the lifecycle layer's decisions; this value only records the
 * outcome. `nextProgramAutoStart` is a separate fact from the selection and defaults to off, exactly as
 * the schema stores it.
 */
data class AppState(
    val selectedProgramId: ProgramId? = null,
    val nextProgramId: ProgramId? = null,
    val nextProgramAutoStart: Boolean = false
) {

    /** Whether a Program is currently selected. */
    val hasSelection: Boolean
        get() = selectedProgramId != null

    /** Whether a Program has been chosen to start next. */
    val hasNextProgram: Boolean
        get() = nextProgramId != null

    companion object {

        /**
         * The identity of the single state row. The table has exactly one row, so a second one is not
         * representable — there is no "which row is the state" question anywhere in the app.
         */
        const val SINGLE_ROW_ID: Int = 1
    }
}
