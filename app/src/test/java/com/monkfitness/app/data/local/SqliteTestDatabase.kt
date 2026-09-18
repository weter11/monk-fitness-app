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
 * The migration and ownership claims of the Program System schema are statements about SQLite
 * itself — which tables exist, which foreign keys the engine records, what a `DELETE` actually
 * removes, what a `SET NULL` actually nulls. A string comparison over the DDL the migration carries
 * cannot decide any of them: it only restates the input. This class therefore runs the statements
 * and the queries on a genuine SQLite engine (`org.xerial:sqlite-jdbc`, test-scope only — the app
 * itself never loads it), which is the same engine Room's own `RoomOpenHelper` opens on a device.
 *
 * `androidx.room.migration.Migration.migrate` takes an **interface** (`SupportSQLiteDatabase`), so
 * the production migration object is executed unchanged, through a proxy that forwards `execSQL` to
 * this connection. Nothing about the migration is re-implemented or simulated here: the bytes the
 * device would execute are the bytes that run.
 *
 * Foreign keys are enforced (`PRAGMA foreign_keys = ON`) because that is what Room opens a database
 * with on Android, and the cascade/`SET NULL` behaviour under test only exists when the pragma is on.
 */
internal class SqliteTestDatabase private constructor(private val connection: Connection) {

    /** Executes one statement, failing the test if SQLite rejects it. */
    fun exec(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    /** Executes a batch of statements in order. */
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

    /** Every row of one column, in the order the query returns them. */
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

    /** The single value of a query, or `null` when the query returns no row. */
    fun scalar(sql: String, vararg args: Any?): String? = strings(sql, *args).firstOrNull()

    /** The number of rows a query returns. */
    fun count(table: String): Int = scalar("SELECT COUNT(*) FROM `$table`")!!.toInt()

    /** `sqlite_master`'s DDL for every table and index, keyed by name. */
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

    /** Every table (not view, not index) the schema contains, sorted. */
    fun tableNames(): List<String> =
        strings("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
            .map { it!! }

    /** The declared columns of one table, in declaration order. */
    fun columnNames(table: String): List<String> =
        pragmaRows(table).map { it["name"]!! }

    /** The declared column type of one column, as SQLite records it. */
    fun columnType(table: String, column: String): String? =
        query("SELECT type FROM pragma_table_info('$table') WHERE name = ?", column)
            .firstOrNull()
            ?.get("type")

    /** The primary-key columns of one table, in key order. */
    fun primaryKey(table: String): List<String> =
        pragmaRows(table)
            .filter { (it["pk"] ?: "0") != "0" }
            .sortedBy { it["pk"]!!.toInt() }
            .map { it["name"]!! }

    /**
     * The foreign keys SQLite records for one table: child column, parent table, parent column and
     * the delete action — read from the engine's own metadata, not from the DDL text.
     */
    fun foreignKeys(table: String): List<ForeignKey> {
        val keys = mutableListOf<ForeignKey>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM pragma_foreign_key_list('$table')").use { rows ->
                val meta = rows.metaData
                while (rows.next()) {
                    val row = (1..meta.columnCount).associate { index ->
                        meta.getColumnName(index) to (rows.getString(index) ?: "")
                    }
                    keys += ForeignKey(
                        childColumn = row["from"]!!,
                        parentTable = row["table"]!!,
                        parentColumn = row["to"]!!,
                        onDelete = row["on_delete"]!!,
                        onUpdate = row["on_update"]!!
                    )
                }
            }
        }
        return keys
    }

    /**
     * The unique indexes of one table: index name to its column list, in index order.
     *
     * SQLite's own automatic index for a `TEXT` primary key (`sqlite_autoindex_*`) is excluded: it is
     * the engine's implementation of the key, not a declared index, and the contract is about what this
     * schema declares.
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

    /** The columns of one index, in index order. */
    fun indexColumns(index: String): List<String> {
        val columns = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM pragma_index_info('$index')").use { rows ->
                while (rows.next()) columns += rows.getString(1) ?: "<expr>"
            }
        }
        return columns
    }

    /** Rows of one table as maps, for the preservation assertions. */
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

    /** One foreign key as the engine records it. */
    data class ForeignKey(
        val childColumn: String,
        val parentTable: String,
        val parentColumn: String,
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
    }
}
