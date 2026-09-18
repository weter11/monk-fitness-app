package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * One row of the **My Programs** list (§21, §22): a saved Program as the list needs to see it, paired
 * with the two global facts the list cannot derive from the Program itself.
 *
 * Why the type exists, rather than the UI reading a [Program] and an [AppState] and joining them
 * itself: **selection is not a Program property** (§3, §21). A Program carries no `isSelected` flag,
 * so a list of `Program` values cannot tell the UI which row is the current one — and any attempt to
 * add such a flag is exactly the second source of truth that could silently disagree with `app_state`.
 * The join therefore happens once, in the use-case layer, and the result is a value the UI renders
 * without needing to know where selection lives. The same holds for the pause interval: "this program
 * is paused right now" is a fact about an open interval, not a column of `program`, and the list shows
 * it because the interval is what *frozen* means (§3).
 *
 * The lifecycle and the archive stamp are carried separately even though both come from [program],
 * because they answer different questions and the UI must never conflate them: `lifecycleStatus` is
 * where the Program is in its life, and `isArchived` is whether the user filed it away while it keeps
 * that lifecycle (§3 — archive is not a lifecycle state, and an archived program keeps reporting the
 * lifecycle it reached).
 *
 * This is a *projection*, not a third model: nothing here is derived beyond the selection and the
 * open-pause fact, both of which are joins rather than computations. Progress measures, frequency,
 * volume and focus distribution are §30 step 9's, and are absent on purpose — My Programs shows the
 * Programs and their state, not an analytics summary (§21's "All Programs is an aggregation view, not
 * an entity" read in the opposite direction).
 *
 * @property program the saved Program. Its identity, name, source, lifecycle and stamps are read off it.
 * @property isSelected whether this Program is the one global `AppState.selectedProgramId` names.
 *   Mutually exclusive across the list, because the state is one row with one pointer.
 * @property hasOpenPause whether this Program has a pause interval currently in effect.
 */
data class ProgramRow(
    val program: Program,
    val isSelected: Boolean,
    val hasOpenPause: Boolean
) {

    /** The Program's stable identity. */
    val programId: ProgramId
        get() = program.programId

    /** The user-facing name. */
    val name: String
        get() = program.name

    /** Where the Program came from — the copy-before-edit rule reads this (§4). */
    val source: ProgramSource
        get() = program.source

    /** Where the Program is in `NOT_STARTED / RUNNING / PAUSED / COMPLETED`. */
    val lifecycleStatus: LifecycleStatus
        get() = program.lifecycleStatus

    /** Whether the user filed this Program away. Independent of [lifecycleStatus] (§3, §29). */
    val isArchived: Boolean
        get() = program.isArchived

    /** Whether the built-in Standard Program's restrictions apply (§4). */
    val isBuiltIn: Boolean
        get() = source.isBuiltIn

    /**
     * Whether the lifecycle has begun. Deliberately ignores the planned start date, which starts
     * nothing (§3).
     */
    val hasStarted: Boolean
        get() = program.hasStarted

    /** Whether the lifecycle is over and the Program cannot be resumed directly (§3). */
    val isCompleted: Boolean
        get() = lifecycleStatus.isTerminal
}

/**
 * The whole My Programs view: the saved Programs, the global selection, and the one derived fact the
 * screen needs and cannot compute from the rows.
 *
 * Like [ProgramRow], this is a projection of [Program]s plus the single [AppState] row, made in one
 * place so that "only one Program is selected at a time" is a property of the value rather than a
 * convention each consumer re-derives: [selectedProgramId] is the one id the rows mark selected, and
 * if the state points at a Program that is not in the list — a stale pointer after a delete, or a
 * Program filtered out — [selectionIsPresent] is the fact that says so out loud instead of letting a
 * row and a pointer silently disagree.
 *
 * @property rows every saved Program, as the list renders it.
 * @property selectedProgramId the one global selection, or `null` before any Program is selected.
 * @property selectionIsPresent whether the selection names a Program the rows contain. `false` when
 *   the state is stale; the fallback rule that follows from it (delete the selected Program → the
 *   Standard Program becomes the selection) lives in the use case, not in this value.
 * @property hasNextProgram whether the user chose a Program to start next. Carried so the list can
 *   show it; the auto-start flag is a separate fact and defaults to off (§3, §23).
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

    /** Every Program the user can still act on — archived ones are filed away, not gone (§29). */
    val activeRows: List<ProgramRow>
        get() = rows.filterNot { it.isArchived }

    /** The built-in Standard Program's row, which the delete-selection fallback selects (§3). */
    val standardRow: ProgramRow?
        get() = rows.singleOrNull { it.isBuiltIn }

    /** How many Programs are saved, archived included. */
    val programCount: Int
        get() = rows.size

    companion object {

        /**
         * Builds the view from the Programs and the global state, performing the one join the list
         * needs. The open-pause fact is supplied per Program as a set of the ids that have an
         * interval in effect, because that is the shape the schedule repository reads them in.
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
