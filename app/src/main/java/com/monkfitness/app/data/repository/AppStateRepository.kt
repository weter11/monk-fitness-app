package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.domain.program.AppState

/**
 * The application state's single row: global runtime state, not a Program's property, which is why it
 * has its own repository and no more operations than the table has facts. It never falls back to the
 * Standard Program, never auto-starts, never clears a selection and never creates the row on read:
 * [state] returns `null` for a database that has never written one — absence, not an invented default.
 */
class AppStateRepository(private val appStateDao: AppStateDao) {

    /** The stored application state, or `null` when none has been written. */
    suspend fun state(): AppState? = appStateDao.state(AppState.SINGLE_ROW_ID)?.toDomain()

    suspend fun save(state: AppState) {
        appStateDao.upsertState(state.toEntity())
    }
}
