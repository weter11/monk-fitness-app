package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * One row of the **My Programs** list: a saved Program as the list needs to see it, paired with the two
 * global facts the list cannot derive from the Program itself.
 *
 * The type exists because **selection is not a Program property**. A Program carries no `isSelected`
 * flag, so a list of `Program` values cannot tell the UI which row is current — and adding one would be
 * exactly the second source of truth that could silently disagree with `app_state`. The join happens
 * once, in the use-case layer. The same holds for the pause interval: "this program is paused right now"
 * is a fact about an open interval, not a column of `program`.
 *
 * The lifecycle and the archive stamp are carried separately because they answer different questions and
 * the UI must never conflate them: `lifecycleStatus` is where the Program is in its life, and
 * `isArchived` is whether the user filed it away while it keeps that lifecycle.
 *
 * This is a *projection*, not a third model: nothing is derived beyond the selection and the open-pause
 * fact, both joins rather than computations. Progress measures, frequency, volume and focus distribution
 * are absent on purpose — My Programs shows the Programs and their state, not an analytics summary.
 */
data class ProgramRow(
    val program: Program,
    val isSelected: Boolean,
    val hasOpenPause: Boolean
) {

    val programId: ProgramId
        get() = program.programId

    val name: String
        get() = program.name

    /** Where the Program came from — the copy-before-edit rule reads this. */
    val source: ProgramSource
        get() = program.source

    val lifecycleStatus: LifecycleStatus
        get() = program.lifecycleStatus

    /** Whether the user filed this Program away. Independent of [lifecycleStatus]. */
    val isArchived: Boolean
        get() = program.isArchived

    /** Whether the built-in Standard Program's restrictions apply. */
    val isBuiltIn: Boolean
        get() = source.isBuiltIn

    /** Whether the lifecycle has begun. Deliberately ignores the planned start date, which starts nothing. */
    val hasStarted: Boolean
        get() = program.hasStarted

    val isCompleted: Boolean
        get() = lifecycleStatus.isTerminal
}

/**
 * The whole My Programs view: the saved Programs, the global selection, and the one derived fact the
 * screen needs and cannot compute from the rows.
 *
 * Like [ProgramRow], it is a projection of [Program]s plus the single [AppState] row, made in one place so
 * that "only one Program is selected at a time" is a property of the value rather than a convention each
 * consumer re-derives. If the state points at a Program that is not in the list — a stale pointer after a
 * delete, or a Program filtered out — `selectionIsPresent` says so out loud instead of letting a row and
 * a pointer silently disagree. The fallback rule that follows from a stale selection lives in the use
 * case, not in this value.
 */
data class MyPrograms(
    val rows: List<ProgramRow>,
    val selectedProgramId: ProgramId?,
    val selectionIsPresent: Boolean,
    val hasNextProgram: Boolean
) {

    /** Convenience: the selected row, or `null` when none is selected or the selection is stale. */
    val selectedRow: ProgramRow?
        get() = rows.singleOrNull { it.isSelected }

    /** Every Program the user can still act on — archived ones are filed away, not gone. */
    val activeRows: List<ProgramRow>
        get() = rows.filterNot { it.isArchived }

    /** The built-in Standard Program's row, which the delete-selection fallback selects. */
    val standardRow: ProgramRow?
        get() = rows.singleOrNull { it.isBuiltIn }

    val programCount: Int
        get() = rows.size

    companion object {

        /**
         * Builds the view from the Programs and the global state, performing the one join the list needs.
         * The open-pause fact arrives as a set of ids because that is the shape the schedule repository
         * reads them in.
         */
        fun from(
            programs: List<Program>,
            state: AppState?,
            openPauseProgramIds: Set<ProgramId>
        ): MyPrograms {
            val selected = state?.selectedProgramId
            val rows = programs.map { program ->
                ProgramRow(
                    program = program,
                    isSelected = selected != null && program.programId == selected,
                    hasOpenPause = program.programId in openPauseProgramIds
                )
            }
            return MyPrograms(
                rows = rows,
                selectedProgramId = selected,
                selectionIsPresent = selected != null && rows.any { it.isSelected },
                hasNextProgram = state?.hasNextProgram == true
            )
        }
    }
}
