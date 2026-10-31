package com.monkfitness.app.data.local

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy

/**
 * A **real** SQLite database the unit tests can drive.
 *
 * The schema claims are statements about SQLite itself — which tables exist, which foreign keys the
 * engine records, what a `DELETE` removes, what a `SET NULL` nulls. A string comparison over the DDL
 * cannot decide any of them: it only restates the input. So the statements run on a genuine SQLite
 * engine (`org.xerial:sqlite-jdbc`, test-scope only — the app never loads it), the same engine Room's
 * `RoomOpenHelper` opens on a device.
 *
 * `Migration.migrate` takes an **interface** (`SupportSQLiteDatabase`), so the production migration
 * object runs unchanged through a proxy that forwards `execSQL` here: the bytes the device would
 * execute are the bytes that run.
 *
 * Foreign keys are enforced (`PRAGMA foreign_keys = ON`) because that is how Room opens a database on
 * Android, and the cascade/`SET NULL` behaviour under test only exists when the pragma is on.
 */
internal class SqliteTestDatabase private constructor(private val connection: Connection) {

    fun exec(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    /**
     * Executes one statement with bound arguments.
     *
     * The parameterized form exists for the repository suites, which drive the production DAO SQL —
     * queries with named parameters — on this engine. Binding rather than interpolating keeps the
     * executed statement byte-identical to the one the DAO carries.
     */
    fun exec(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.execute()
        }
    }

    /**
     * Runs [block] inside one SQLite transaction: committed when it returns, rolled back when it
     * throws — the same all-or-nothing unit Room's `withTransaction` provides on a device. The runner
     * performs a real `COMMIT`/`ROLLBACK`, so atomicity is the engine's rather than the test's
     * bookkeeping and a failing insert mid-creation leaves no half-written graph behind.
     *
     * **Nesting is supported**, because the production runner nests: `withTransaction` opens an inner
     * unit inside an outer one (a Program's facts and its revision are written in one unit while the
     * revision's own write opens a unit of its own). A rig that refused the second call — or committed
     * the first behind the outer's back — would decide the composition differently from the device. An
     * inner unit is therefore a `SAVEPOINT`: it commits into the enclosing unit and rolls back to its
     * own boundary when it throws, leaving the outer unit's outcome to the outer unit.
     */
    suspend fun <T> transaction(block: suspend () -> T): T {
        if (depth > 0) return nestedTransaction(block)
        connection.autoCommit = false
        depth = 1
        return try {
            val value = block()
            connection.commit()
            value
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
            depth = 0
        }
    }

    /** How many transaction units are currently open; `0` means the next one is the outermost. */
    private var depth: Int = 0

    private suspend fun <T> nestedTransaction(block: suspend () -> T): T {
        val savepoint = "nested_unit_$depth"
        depth += 1
        try {
            exec("SAVEPOINT $savepoint")
            val value = block()
            exec("RELEASE SAVEPOINT $savepoint")
            return value
        } catch (failure: Throwable) {
            exec("ROLLBACK TO SAVEPOINT $savepoint")
            exec("RELEASE SAVEPOINT $savepoint")
            throw failure
        } finally {
            depth -= 1
        }
    }

    fun execAll(statements: List<String>) {
        statements.forEach { exec(it) }
    }

    /**
     * Runs the production migration against this database. `bindArgs` are only passed by statements
     * this migration does not contain, so a bound statement is a defect rather than a case to support.
     */
    fun migrate(migration: Migration) {
        migration.migrate(asSupportDatabase())
    }

    private fun asSupportDatabase(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args!![0] as String
                    require(args.size == 1) { "the migration must not bind arguments: $sql" }
                    exec(sql)
                    null
                }
                "isOpen" -> true
                "close" -> null
                "toString" -> "SqliteTestDatabase"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw UnsupportedOperationException(
                    "the migration called ${method.name}, which this database does not forward"
                )
            }
        } as SupportSQLiteDatabase

    fun strings(sql: String, vararg args: Any?): List<String?> {
        val rows = mutableListOf<String?>()
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.executeQuery().use { result ->
                while (result.next()) rows += result.getString(1)
            }
        }
        return rows
    }

    fun scalar(sql: String, vararg args: Any?): String? = strings(sql, *args).firstOrNull()

    fun count(table: String): Int = scalar("SELECT COUNT(*) FROM `$table`")!!.toInt()

    /**
     * The number of rows of [table] whose row text [contains] [needle].
     *
     * For the claims that are about which rows exist rather than what they say — "the seeded catalogue
     * holds four `lunges` rows and thirteen others", "no row mentions `plank`". Those are assertions
     * over a *set* of rows, and inline they would each need a bespoke `SELECT ... WHERE`, a place for a
     * subtly wrong predicate to hide.
     *
     * It deliberately matches the **rendered row text** rather than a named column: "no row mentions
     * this family anywhere" is stronger than "the `familyId` column does not equal it", and it is what
     * makes a leaked exercise id in the wrong family visible.
     */
    fun count(table: String, contains: (String) -> Boolean): Int =
        rows("SELECT * FROM `$table`")
            .count { row -> row.values.any { it != null && contains(it) } }

    /**
     * The number of `progression_relation_variant` rows belonging to [familyId].
     *
     * Scoped to the **`familyId` column**, because row-text matching cannot answer "how many rows does
     * this family hold": an exercise id can contain a family id as a substring, and `pullups` is both a
     * family and a member of `pullups`, so a text match conflates the family with its own rung.
     */
    fun countFamilyRows(familyId: String): Int =
        rows("SELECT `familyId` FROM `progression_relation_variant`")
            .count { it["familyId"] == familyId }

    fun masterSql(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL")
                .use { rows ->
                    while (rows.next()) result[rows.getString(1)] = rows.getString(2)
                }
        }
        return result
    }

    fun tableNames(): List<String> =
        strings("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .map { it!! }

    fun columnNames(table: String): List<String> =
        pragmaRows(table).map { it["name"]!! }

    fun columnType(table: String, column: String): String? =
        query("SELECT type FROM pragma_table_info('$table') WHERE name = ?", column)
            .firstOrNull()
            ?.get("type")

    fun primaryKey(table: String): List<String> =
        pragmaRows(table)
            .filter { (it["pk"] ?: "0") != "0" }
            .sortedBy { it["pk"]!!.toInt() }
            .map { it["name"]!! }

    /**
     * The foreign keys SQLite records for one table: child columns, parent table, parent columns and
     * the delete action — read from the engine's own metadata, not from the DDL text.
     *
     * `pragma_foreign_key_list` reports **one row per column**, with one key's columns sharing an `id`
     * and ordered by `seq`. A composite key — a target occurrence's components reference the occurrence
     * by the whole `(programId, occurrenceKey)` pair — is therefore several rows, and reading them as
     * separate keys would misreport that table's ownership graph and make a two-column key
     * indistinguishable from two independent one-column ones. Rows are grouped by `id` so one declared
     * key is one recorded key.
     */
    fun foreignKeys(table: String): List<ForeignKey> {
        val grouped = LinkedHashMap<Int, MutableList<Map<String, String?>>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM pragma_foreign_key_list('$table')").use { rows ->
                val meta = rows.metaData
                while (rows.next()) {
                    val row = (1..meta.columnCount).associate { index ->
                        meta.getColumnName(index) to (rows.getString(index) ?: "")
                    }
                    grouped.getOrPut(row["id"]!!.toInt()) { mutableListOf() }.add(row)
                }
            }
        }
        return grouped.values.map { group ->
            val ordered = group.sortedBy { it["seq"]!!.toInt() }
            ForeignKey(
                childColumns = ordered.map { it["from"]!! },
                parentTable = ordered.first()["table"]!!,
                parentColumns = ordered.map { it["to"]!! },
                onDelete = ordered.first()["on_delete"]!!,
                onUpdate = ordered.first()["on_update"]!!
            )
        }
    }

    /**
     * The unique indexes of one table: index name to its column list, in index order.
     *
     * SQLite's automatic index for a composite primary key (`sqlite_autoindex_*`) is excluded: it is the
     * engine's implementation of the key, not a declared index.
     */
    fun uniqueIndexes(table: String): Map<String, List<String>> {
        val unique = mutableMapOf<String, List<String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM pragma_index_list('$table') " +
                    "WHERE \"unique\" = 1 AND name NOT LIKE 'sqlite_autoindex%'"
            ).use { rows ->
                while (rows.next()) {
                    val name = rows.getString(1)
                    unique[name] = indexColumns(name)
                }
            }
        }
        return unique
    }

    fun indexColumns(index: String): List<String> {
        val columns = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM pragma_index_info('$index')").use { rows ->
                while (rows.next()) columns += rows.getString(1) ?: "<expr>"
            }
        }
        return columns
    }

    fun rows(sql: String, vararg args: Any?): List<Map<String, String?>> =
        query(sql, *args)

    private fun query(sql: String, vararg args: Any?): List<Map<String, String?>> {
        val result = mutableListOf<Map<String, String?>>()
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.executeQuery().use { rows ->
                val meta = rows.metaData
                while (rows.next()) {
                    result += (1..meta.columnCount).associate { index ->
                        meta.getColumnName(index) to rows.getString(index)
                    }
                }
            }
        }
        return result
    }

    private fun pragmaRows(table: String): List<Map<String, String?>> {
        val result = mutableListOf<Map<String, String?>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM pragma_table_info('$table')").use { rows ->
                val meta = rows.metaData
                while (rows.next()) {
                    result += (1..meta.columnCount).associate { index ->
                        meta.getColumnName(index) to rows.getString(index)
                    }
                }
            }
        }
        return result
    }

    /** The SQLite error message of an operation that must be refused, or a failed assertion. */
    fun expectError(statement: String): String =
        try {
            exec(statement)
            throw AssertionError("SQLite accepted a statement the schema must refuse: $statement")
        } catch (failure: SQLException) {
            failure.message ?: failure.toString()
        }

    fun close() {
        connection.close()
    }

    /**
     * One foreign key as the engine records it.
     *
     * The columns are lists because the engine's metadata is per-column: one declared composite key is
     * several `pragma_foreign_key_list` rows, grouped back into one key by [foreignKeys].
     */
    data class ForeignKey(
        val childColumns: List<String>,
        val parentTable: String,
        val parentColumns: List<String>,
        val onDelete: String,
        val onUpdate: String
    )

    companion object {

        /** A private in-memory database with foreign keys enforced, as Room opens one. */
        fun inMemory(): SqliteTestDatabase {
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.createStatement().use { statement: Statement ->
                statement.execute("PRAGMA foreign_keys = ON")
            }
            return SqliteTestDatabase(connection)
        }

        /**
         * A database backed by a **file**, so more than one connection can be opened over it.
         *
         * An in-memory database is private to its connection, so a second view of the same rows — the
         * second process of a restart test, or the second racer of a concurrency test — needs a file.
         * Everything else matches [inMemory]: same engine, same `PRAGMA foreign_keys = ON`, no schema of
         * its own — one connection runs the deployed chain and every further connection sees what it
         * wrote.
         *
         * Sessions are **not** shared and no locking mode is set: two connections over one file is
         * exactly how two writers contend, which makes "the second write cannot both succeed"
         * measurable rather than argued.
         */
        fun at(path: String): SqliteTestDatabase {
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:$path")
            connection.createStatement().use { statement: Statement ->
                statement.execute("PRAGMA foreign_keys = ON")
            }
            return SqliteTestDatabase(connection)
        }
    }
}
