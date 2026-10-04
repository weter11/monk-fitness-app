package com.monkfitness.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkfitness.app.data.model.ProgressionRelationVariantEntity
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.usecase.StoredProgressionRelationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * P31's explicit **empty-production proof**, plus the schema contract of the step that created the
 * storage.
 *
 * The stage's central risk is not that it does too little — it is that it quietly does *too much*. A
 * storage boundary plus a provider is exactly the shape from which a fabricated ladder leaks: a seeded
 * migration, a default rung, a family-state read dressed up as a ladder. So the claim under test is the
 * negative one, measured on a real database with the real repository and the real provider:
 *
 * ```text
 * empty persisted progression catalogue
 *   + real production provider
 *   ─────────────────────────────────
 *   null — the honest "no ladder is declared"
 * ```
 *
 * and, at the schema level, that the step which created the table wrote no row and altered nothing
 * else. Every assertion here is about what is *absent*.
 */
class ProgressionRelationCatalogueIntegrationTest {

    private val rig = ProgramDataAccessRig("p31-empty")

    @After
    fun close() = rig.close()

    // ================================================================ the empty catalogue

    /**
     * **The central assertion.** The production provider over an empty catalogue answers `null`, for
     * every family and for every near miss of one.
     *
     * The near misses are the load-bearing half. A provider that fell back to "the family that sounds
     * most like this one", or to a default bucket, would answer a real relation for `pushup` while
     * still answering `null` for a wholly unknown id — so asserting only the unknown case would pass a
     * provider that invents exactly the fallback §9 forbids.
     */
    @Test
    fun anEmptyCatalogueBackedProviderAnswersNullForEveryFamily() = runBlocking {
        val provider = StoredProgressionRelationProvider(rig.progressionRelationRepository)

        assertEquals(
            "P31 creates storage but no ladder content, so the catalogue is empty",
            0,
            rig.database.count("progression_relation_variant")
        )
        assertNull(
            "and the production provider therefore answers 'not declared' for a real catalogue family",
            provider.relationOf("pushups")
        )
        assertNull("including one the catalogue never heard of", provider.relationOf("pullups"))
        assertNull("and a near miss of a family id", provider.relationOf("pushup"))
        assertNull("and an empty family id", provider.relationOf(""))
    }

    /**
     * **A stored ladder is reachable through the same provider** — the positive control that keeps the
     * assertion above from being satisfied by a provider that is simply broken.
     *
     * Without it, "the provider always answers null" would pass every case in this file and the stage
     * would have proved only that it built nothing.
     */
    @Test
    fun theSameProviderServesAStoredLadderAsTheStoredValue() = runBlocking {
        val declared = ProgramProgressionRelation(
            familyId = "pushups",
            variants = listOf(
                ProgramProgressionVariant(1, "knee_pushups", RepPrescription(listOf(12, 10, 8, 6))),
                ProgramProgressionVariant(2, "pushups", RepPrescription(listOf(10, 8))),
                ProgramProgressionVariant(3, "decline_pushups", TimePrescription(listOf(30, 30, 45)))
            )
        )
        rig.progressionRelationRepository.store(declared)

        val provider = StoredProgressionRelationProvider(rig.progressionRelationRepository)

        assertEquals(
            "once a ladder IS declared, the provider serves it as the equal stored value — so the null " +
                "above was an absence and not a broken read",
            declared,
            provider.relationOf("pushups")
        )
        assertEquals(
            "and it is served losslessly, per set, in both dimensions",
            listOf(
                listOf(12, 10, 8, 6),
                listOf(10, 8),
                listOf(30, 30, 45)
            ),
            requireNotNull(provider.relationOf("pushups")).variants.map { it.prescription.perSetTargets }
        )
        assertNull(
            "while a family that was never declared is still refused, so serving one ladder did not " +
                "open the provider up to any family",
            provider.relationOf("squats")
        )
    }

    /**
     * **The provider holds nothing but the repository.**
     *
     * Asserted on the declared fields rather than by reading the source, because a collaborator list is
     * the only place a *default* could hide: a second field could be a hard-coded ladder, a fallback
     * family provider, a clock or an id source, and none of those would be visible in a behavioural test
     * over an empty catalogue.
     */
    @Test
    fun theProviderHoldsExactlyOneCollaboratorAndNoOtherState() {
        val fields = StoredProgressionRelationProvider::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }

        assertEquals(
            "the production provider holds exactly one collaborator and nothing else — a second field " +
                "is where a default ladder, a fallback family or a clock would live: " +
                fields.map { it.name },
            listOf("relations"),
            fields.map { it.name }
        )
        assertEquals(
            "and it holds the catalogue repository, not a DAO and not the adaptive repository",
            "com.monkfitness.app.data.repository.ProgressionRelationRepository",
            fields.single().type.name
        )
    }

    /**
     * **The catalogue is app-owned and survives everything that happens to a plan.**
     *
     * A ladder belongs to a family, not to a Program — so a Program being deleted, and a revision
     * being superseded, must both leave it untouched. This is the storage-level form of the scope claim,
     * and it is what a revision-scoped table would fail.
     */
    @Test
    fun aDeclaredLadderSurvivesADeleteOfEveryPlanInTheDatabase() = runBlocking {
        rig.progressionRelationRepository.store(
            ProgramProgressionRelation(
                familyId = "pushups",
                variants = listOf(
                    ProgramProgressionVariant(1, "knee_pushups", RepPrescription(listOf(12))),
                    ProgramProgressionVariant(2, "pushups", RepPrescription(listOf(8)))
                )
            )
        )
        rig.createGraph()

        assertTrue("the rig's graph really did write a Program", rig.database.count("program") > 0)

        rig.database.exec("DELETE FROM `program`")

        assertEquals(
            "deleting every Program leaves the declared ladder intact — it was never theirs",
            listOf("1:knee_pushups", "2:pushups"),
            requireNotNull(rig.progressionRelationRepository.relationOf("pushups"))
                .variants
                .map { it.identity }
        )
    }

    // ================================================================ the migration's own contract

    /**
     * **The step's table is shaped the way the entity declares it**, read off a real engine's own
     * `pragma_table_info` rather than off the migration's text.
     */
    @Test
    fun theStoredTableHasTheDeclaredColumnsKeyAndNoDefaults() {
        val upgraded = fullyMigrated()
        try {
            val table = "progression_relation_variant"
            val columns = upgraded.columnsOf(table)

            assertEquals(
                "the stored columns are the declared ones, in declaration order",
                listOf("familyId", "level", "exerciseId", "prescriptionDimension", "perSetTargets"),
                columns.map { it["name"] }
            )
            assertEquals(
                "none of them is nullable — every rung states all five facts",
                5,
                columns.count { it["notnull"] == "1" }
            )
            assertEquals(
                "and none of them carries a DEFAULT, so no row can invent a value",
                emptyList<String>(),
                columns.mapNotNull { it["dflt_value"] }
            )
            assertEquals(
                "the identity is (familyId, exerciseId) — one exercise is one position per family, and " +
                    "the key's order says which column leads a read",
                listOf("familyId", "exerciseId"),
                columns.filter { (it["pk"] ?: "0").toInt() > 0 }
                    .sortedBy { (it["pk"] ?: "0").toInt() }
                    .map { it["name"] }
            )
            assertEquals(
                "and no index is declared, because the key's leading column is the only read column",
                // Scoped to *this* table: the database legitimately declares many indices, and the
                // claim is that P31's table adds no *declared* one. `sqlite_master` cannot be read with
                // a substring test on the name, because it also holds the table's own row under it.
                //
                // What remains is SQLite's `sqlite_autoindex_*`, which it creates for a composite
                // PRIMARY KEY. That entry is the key itself rather than a declared index — the same one
                // every other composite-keyed table in this schema produces — so the claim excludes it
                // by prefix and says so, rather than asserting an empty list that would be false about
                // the engine.
                emptyList<String>(),
                upgraded.strings(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
                    table
                ).mapNotNull { it }
                    .filterNot { it.startsWith("sqlite_autoindex_") }
            )
            assertEquals(
                "and no foreign key at all: a ladder is nobody's child, which is the schema's own " +
                    "statement that it is family-scoped rather than revision-owned",
                emptyList<Int>(),
                upgraded.foreignKeyIdsOf(table)
            )
        } finally {
            upgraded.close()
        }
    }

    /** **The table is absent before the step and present after it** — on a real engine, both ways. */
    @Test
    fun theTableIsAbsentAtSeventeenAndPresentAtEighteen() {
        val atSeventeen = SqliteTestDatabase.inMemory()
        try {
            atSeventeen.execAll(LegacyV7Schema.TABLE_STATEMENTS)
            listOf(
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17
            ).forEach { atSeventeen.migrate(it) }

            assertTrue(
                "a version-17 database has no ladder table — the storage is what this stage adds",
                "progression_relation_variant" !in atSeventeen.tableNames()
            )

            atSeventeen.migrate(AppDatabase.MIGRATION_17_18)

            assertTrue(
                "and the very next step creates it",
                "progression_relation_variant" in atSeventeen.tableNames()
            )
        } finally {
            atSeventeen.close()
        }
    }

    /**
     * **The step creates the table and writes nothing.**
     *
     * Read through a recording proxy exactly as `ProgramSchemaTest` does, so the assertion is about the
     * statements the device is actually handed.
     */
    @Test
    fun theStepCreatesTheTableAndWritesNoRowAndTouchesNothingElse() {
        val statements = recordStatements(AppDatabase.MIGRATION_17_18)

        assertEquals(
            "the step executes exactly one statement, and it creates the ladder table",
            1,
            statements.size
        )
        assertTrue(
            "which is a CREATE TABLE and not an ALTER of something that already existed: $statements",
            statements.single().startsWith("CREATE TABLE IF NOT EXISTS `progression_relation_variant`")
        )
        assertTrue(
            "and it inserts no row: a seed here would be a ladder nobody authored",
            statements.none { it.contains("INSERT", ignoreCase = true) }
        )
        assertTrue(
            "and it neither updates nor deletes, so no existing Program or adaptive row is rewritten",
            statements.none { it.contains("UPDATE", ignoreCase = true) } &&
                statements.none { it.contains("DELETE", ignoreCase = true) }
        )
        assertTrue(
            "and it renames nothing, so a dropped-and-recreated table is not hiding in here",
            statements.none { it.contains("RENAME", ignoreCase = true) }
        )
        assertEquals(
            "and the fixture's contract for this step is that same single statement, token for token",
            listOf(ProgramSchemaFixture.normalized(statements.single())),
            ProgramSchemaFixture.EXPECTED_PROGRESSION_RELATION_STATEMENTS
                .map { ProgramSchemaFixture.normalized(it) }
        )
    }

    /**
     * **An upgraded database carries no ladder row** — the mechanical form of "P31 invented no ladder
     * content", measured on the engine rather than on the migration's text.
     */
    @Test
    fun aFullyUpgradedDatabaseHoldsNoLadderRow() {
        val upgraded = fullyMigrated()
        try {
            assertEquals(
                "P31 authors no ladder content, so a seeded rung would be an invented family claim",
                0,
                upgraded.count("progression_relation_variant")
            )
            assertEquals(
                "the ladder catalogue is empty even with the whole Program schema present beside it",
                emptyList<String>(),
                upgraded.strings("SELECT DISTINCT `familyId` FROM `progression_relation_variant`")
            )
        } finally {
            upgraded.close()
        }
    }

    /**
     * **The persisted row is exactly the entity's fields** — the same mechanical check the harness
     * applies to every other target entity, run here so P31's table is not exempt from it.
     */
    @Test
    fun theEntityDeclaresExactlyTheColumnsTheTableStores() {
        val declared = ProgressionRelationVariantEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }

        assertEquals(
            "the entity's fields are the table's columns, in order — the insert harness binds fields by " +
                "reflection and would drop or misplace one otherwise",
            listOf("familyId", "level", "exerciseId", "prescriptionDimension", "perSetTargets"),
            declared
        )
        assertEquals(
            "and that is the order the table itself stores, so a fresh install and an upgrade agree",
            declared,
            rig.database.columnNames("progression_relation_variant")
        )
    }

    // ================================================================ helpers

    /** A version-7 database with the whole deployed chain executed, ending at version 18. */
    private fun fullyMigrated(): SqliteTestDatabase =
        SqliteTestDatabase.inMemory().also { database ->
            database.execAll(LegacyV7Schema.TABLE_STATEMENTS)
            listOf(
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18
            ).forEach { database.migrate(it) }
        }

    /** Every statement [migration] executes, recorded through a proxy rather than read from its text. */
    private fun recordStatements(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") statements += (args!![0] as String).trim()
            null
        } as SupportSQLiteDatabase

        migration.migrate(database)
        return statements
    }

    /** One table's `pragma_table_info` rows. */
    private fun SqliteTestDatabase.columnsOf(table: String): List<Map<String, String?>> =
        rows("SELECT * FROM pragma_table_info('$table')")

    /**
     * The distinct `pragma_foreign_key_list` group ids for one table.
     *
     * Grouped by `id` rather than counted by row, because SQLite reports a composite key as N rows
     * sharing one id — counting rows would misreport a composite key as several independent keys, and
     * an ownership graph built on that misreading passes for the wrong reason.
     */
    private fun SqliteTestDatabase.foreignKeyIdsOf(table: String): List<Int> =
        rows("SELECT * FROM pragma_foreign_key_list('$table')")
            .map { (it["id"] ?: "0").toInt() }
            .distinct()
}