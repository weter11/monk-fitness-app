package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.local.ProgressDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Real maintenance operation; only the Room transaction and DAO storage are simulated on JVM. */
class FullResetAtomicityInvariantTest {
    @Test fun everyDeleteFailureRollsBackAllSevenTablesAndSuccessfulResetKeepsNutrition() = runBlocking {
        val deletes = linkedMapOf(
            "clearUserProgress" to "user_progress",
            "clearPostureProgress" to "posture_session_progress",
            "clearProgramDayStates" to "program_day_state",
            "clearSetLogs" to "set_log",
            "clearBodyWeightEntries" to "body_weight_log",
            "clearFamilyStates" to "family_progression_state",
            "clearDecisionHistory" to "adaptive_decision_record"
        )
        // A sentinel is enough to model DAO table-wide deletes; no reset algorithm in this fixture.
        val initial = (deletes.values + listOf("meal_cycles", "meals", "shopping_items"))
            .associateWith { listOf("retained row in $it") }
        for (failure in deletes.keys + "no failure") {
            val tables = initial.toMutableMap()
            var insideTransaction = false
            var transactions = 0
            val calls = mutableListOf<String>()
            fun <T> dao(type: Class<T>): T = type.cast(Proxy.newProxyInstance(
                type.classLoader, arrayOf(type)
            ) { _, method, _ ->
                check(insideTransaction) { "${method.name} escaped the transaction" }
                val table = deletes[method.name] ?: error("unexpected DAO operation ${method.name}")
                calls += method.name
                if (method.name == failure) throw IllegalStateException("injected $failure")
                tables[table] = emptyList()
                Unit
            })
            val runTransaction: suspend (suspend () -> Unit) -> Unit = { block ->
                transactions++
                val before = tables.toMap()
                insideTransaction = true
                try { block() } catch (e: Exception) {
                    tables.clear(); tables.putAll(before); throw e
                } finally { insideTransaction = false }
            }
            val result = runCatching {
                ProgramMaintenance.clearAllProgressData(dao(ProgressDao::class.java),
                    dao(FamilyProgressionStateDao::class.java), dao(AdaptiveDecisionHistoryDao::class.java),
                    runTransaction)
            }
            assertEquals("$failure transaction count", 1, transactions)
            if (failure == "no failure") {
                result.getOrThrow()
                assertEquals(deletes.keys.toList(), calls)
                for (table in deletes.values) assertEquals("$table survives reset", emptyList<String>(), tables[table])
                for (table in listOf("meal_cycles", "meals", "shopping_items"))
                    assertEquals("nutrition $table", initial[table], tables[table])
            } else {
                assertEquals("injected $failure", result.exceptionOrNull()?.message)
                assertEquals("$failure must roll back program AND adaptive rows", initial, tables)
                assertEquals(deletes.keys.takeWhile { it != failure } + failure, calls)
            }
        }
    }
}
