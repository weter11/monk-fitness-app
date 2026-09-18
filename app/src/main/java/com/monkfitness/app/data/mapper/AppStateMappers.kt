package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.AppStateEntity
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.AppState

/**
 * The `app_state` row ⇄ the application state value.
 *
 * One row, one value, no defaults: the row's key is the single-row id, so the mapper writes that id
 * and refuses to treat a second one as a second state. `null` is mapped as `null` in both directions
 * — "no Program is selected" and "none is chosen to start next" are values, and they must not be
 * silently replaced with a fallback Program: which Program runs when nothing is selected is a
 * lifecycle decision (§3), not a value this layer may invent.
 *
 * The auto-start flag maps as stored, including its off default.
 */

/** The application state one row describes. */
internal fun AppStateEntity.toDomain(): AppState = AppState(
    selectedProgramId = selectedProgramId?.let { ProgramId(it) },
    nextProgramId = nextProgramId?.let { ProgramId(it) },
    nextProgramAutoStart = nextProgramAutoStart
)

/** The row one application state is stored as. The key is the table's single-row identity. */
internal fun AppState.toEntity(): AppStateEntity = AppStateEntity(
    id = AppState.SINGLE_ROW_ID,
    selectedProgramId = selectedProgramId?.value,
    nextProgramId = nextProgramId?.value,
    nextProgramAutoStart = nextProgramAutoStart
)
