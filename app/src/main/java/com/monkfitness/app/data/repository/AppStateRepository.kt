package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AppStateDao
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.domain.program.AppState

/**
 * The application state's persistence: one row, read and written, and nothing decided.
 *
 * The state is global runtime state, not a Program's property (§21), which is why it has its own
 * repository: putting it inside a Program repository would make a global fact look Program-owned, and
 * a Program that claimed to be selected could then contradict it. §24's repository list does not name
 * this one; it is the smallest thing that can hold the table, and it deliberately has no more
 * operations than the table has facts.
 *
 * What it does not do is as important as what it does: there is no fallback to the Standard Program
 * when nothing is selected, no auto-start evaluation, no clearing of a selection, and no creation of a
 * state row on read. [state] returns `null` for a database that has never written one — absence is
 * absence, not a default the data layer invented (§3, §29).
 */
class AppStateRepository(private val appStateDao: AppStateDao) {

    /** The stored application state, or `null` when none has been written. */
    suspend fun state(): AppState? = appStateDao.state(AppState.SINGLE_ROW_ID)?.toDomain()

    /** Stores the application state, replacing the single row. */
    suspend fun save(state: AppState) {
        appStateDao.upsertState(state.toEntity())
    }
}
