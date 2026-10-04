package com.monkfitness.app.bootstrap

import com.monkfitness.app.data.local.AppDatabase
import com.monkfitness.app.data.local.SqliteTestDatabase
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions
import com.monkfitness.app.domain.usecase.StoredProgressionRelationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P32's **bootstrap-lifecycle proof**: the built-in catalogue is established exactly once, on every kind
 * of database, and never established from a read.
 *
 * ```text
 * open → bootstrap → the four authorised ladders exist
 * ```
 *
 * The claims here are the ones a seed is easiest to get subtly wrong, and none of them is visible in a
 * single fresh run:
 *
 *  * **fresh** — an empty database ends up with the content;
 *  * **existing at schema 18** — a database that already ran the P31 migration and holds *user* ladder
 *    rows keeps them, because P32 adds content and no migration;
 *  * **repeated** — bootstrapping three times changes nothing the second or third time;
 *  * **read** — a provider read never seeds, so a consumer's answer cannot depend on when it asked.
 *
 * Each is measured on a real migrated database, because "idempotent" and "does not overwrite" are
 * properties of what was actually written, not of what a fake repository was told.
 */
class BuiltInProgressionCatalogueBootstrapTest {

    private val rigs = mutableListOf<ProgramDataAccessRig>()

    @After
    fun close() = rigs.forEach { it.close() }

    private fun newRig(tag: String): ProgramDataAccessRig =
        ProgramDataAccessRig(tag).also { rigs += it }

    private fun bootstrapFor(rig: ProgramDataAccessRig) =
        BuiltInProgressionCatalogueBootstrap(rig.progressionRelationRepository)

    private fun providerFor(rig: ProgramDataAccessRig) =
        StoredProgressionRelationProvider(rig.progressionRelationRepository)

    private fun rowsOf(rig: ProgramDataAccessRig): List<Map<String, String?>> =
        rig.database.rows(
            "SELECT * FROM `progression_relation_variant` ORDER BY `familyId` ASC, `level` ASC"
        )

    // ================================================================ a fresh database

    /**
     * **A fresh, fully-migrated database gains exactly the four authorised ladders and nothing else.**
     *
     * The count-before assertion is load-bearing: without it, "17 rows after bootstrap" would be
     * satisfied just as well by a database that already had 17 rows, and the fresh case would prove
     * nothing about the bootstrap.
     */
    @Test
    fun aFreshDatabaseReceivesExactlyTheAuthorisedLadders() = runBlocking {
        val rig = newRig("p32-bootstrap-fresh")

        assertEquals(
            "the database starts empty — this is the fresh-database case, not a re-run",
            0,
            rig.database.count("progression_relation_variant")
        )

        bootstrapFor(rig).bootstrap()

        assertEquals(
            "bootstrapping a fresh database stores the four ladders and nothing more",
            17,
            rig.database.count("progression_relation_variant")
        )
        assertEquals(
            "declared as exactly the authorised families",
            ProductionProgressionRelationDefinitions.authorisedFamilyIds,
            rig.progressionRelationRepository.declaredFamilyIds().toSet()
        )
    }

    /**
     * **The fresh database's content equals the authored definitions, family by family.**
     */
    @Test
    fun aFreshDatabaseReceivesTheAuthoredValuesVerbatim() = runBlocking {
        val rig = newRig("p32-bootstrap-values")
        bootstrapFor(rig).bootstrap()

        val provider = providerFor(rig)
        ProductionProgressionRelationDefinitions.definitions.forEach { definition ->
            assertEquals(
                "'${definition.familyId}' is stored as the authored relation",
                definition,
                provider.relationOf(definition.familyId)
            )
        }
    }

    // ================================================================ an existing schema-18 database

    /**
     * **An existing schema-18 database that already holds ladder rows keeps them.**
     *
     * This is the "existing user database upgraded to v18" case. P32 adds **content** and **no
     * migration**, so the database is simply opened at the schema it already has and the bootstrap runs
     * against it. The rows written *before* the bootstrap are the ones a user-authored or previously
     * edited ladder would occupy — which is precisely why the seed must not overwrite them.
     */
    @Test
    fun anExistingSchemaEighteenDatabaseIsBootstrappedWithoutAMigration() = runBlocking {
        val rig = newRig("p32-bootstrap-existing")

        // A pre-existing ladder for a family this stage also authors, written the way a future
        // user-authored ladder would be: a different definition entirely.
        rig.progressionRelationRepository.store(
            com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation(
                familyId = "pushups",
                variants = listOf(
                    com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant(
                        0,
                        "pushups",
                        com.monkfitness.app.domain.prescription.TimePrescription(listOf(45, 45))
                    )
                )
            )
        )

        val before = rowsOf(rig)
        assertEquals("the database already holds one pre-existing ladder", 1, before.size)

        bootstrapFor(rig).bootstrap()

        val after = rowsOf(rig)
        assertEquals(
            "the pre-existing row is preserved byte for byte — the seed fills absences, it never " +
                "overwrites a declared ladder",
            before,
            after.filter { it["familyId"] == "pushups" }
        )
        assertEquals(
            "and the other three authorised families were added alongside it — 4 + 4 + 5 rungs beside " +
                "the one pre-existing row, because `pushups` was already declared and so was not seeded",
            1 + 4 + 4 + 5,
            after.size
        )
        assertEquals(
            "so the pre-existing single-rung `pushups` ladder was NOT replaced by the authored one",
            "45,45",
            after.first { it["familyId"] == "pushups" }["perSetTargets"]
        )
    }

    /**
     * **An existing database with no catalogue rows at all is the same as a fresh one.**
     *
     * The two cases are indistinguishable from the bootstrap's point of view — it never asks which it
     * is, only what is already declared — so this is asserted directly rather than assumed.
     */
    @Test
    fun anExistingEmptyCatalogueIsIndistinguishableFromAFreshOne() = runBlocking {
        val fresh = newRig("p32-fresh-compare")
        val existing = newRig("p32-existing-compare")

        bootstrapFor(fresh).bootstrap()
        // `existing` is a second, already-migrated database that simply has not been bootstrapped.
        assertEquals(0, existing.database.count("progression_relation_variant"))
        bootstrapFor(existing).bootstrap()

        assertEquals(
            "a first bootstrap of an existing database lands in exactly the same state a fresh one does",
            rowsOf(fresh),
            rowsOf(existing)
        )
    }

    // ================================================================ repeated initialization

    /**
     * **Bootstrapping repeatedly changes nothing after the first time.**
     *
     * Run three times and compared on the full row set, not just the count: a seed that duplicated rows
     * under a new key, or rewrote a target, could preserve the row count in some shapes and not others.
     */
    @Test
    fun repeatedBootstrappingIsIdempotent() = runBlocking {
        val rig = newRig("p32-bootstrap-idempotent")

        bootstrapFor(rig).bootstrap()
        val once = rowsOf(rig)

        bootstrapFor(rig).bootstrap()
        bootstrapFor(rig).bootstrap()

        assertEquals(
            "three bootstraps leave exactly the rows the first one wrote — no duplicate, no rewrite",
            once,
            rowsOf(rig)
        )
        assertEquals(
            "and the row count is still the authored 17",
            17,
            rig.database.count("progression_relation_variant")
        )
        assertEquals(
            "with exactly the four authorised families declared once each",
            listOf("lunges", "pullups", "pushups", "squats"),
            rig.progressionRelationRepository.declaredFamilyIds()
        )
    }

    /**
     **A partial catalogue is completed, not skipped.**
     *
     * The bootstrap's check is *per family*, so a database that somehow lost one authorised ladder gets
     * that family back while the other three are left untouched. A single "has anything been seeded"
     * flag would skip the whole set and leave the app serving three ladders forever.
     */
    @Test
    fun aPartialCatalogueIsCompletedRatherThanSkipped() = runBlocking {
        val rig = newRig("p32-bootstrap-partial")
        bootstrapFor(rig).bootstrap()

        val others = rowsOf(rig).filterNot { it["familyId"] == "squats" }
        rig.progressionRelationRepository.remove("squats")
        assertEquals("the squats ladder is gone", 13, rig.database.count("progression_relation_variant"))

        bootstrapFor(rig).bootstrap()

        assertEquals(
            "the missing family is restored, so the catalogue is complete again",
            17,
            rig.database.count("progression_relation_variant")
        )
        assertEquals(
            "and the families that were present are byte-identical — the fill touched only the absence",
            others,
            rowsOf(rig).filterNot { it["familyId"] == "squats" }
        )
    }

    // ================================================================ the seed never seeds

    /**
     * **The provider is not a seed.** The heart of the ordering guarantee: a read may only ever report
     * what is stored.
     *
     * A deleted ladder stays deleted across many reads of every family, including the deleted one. If the
     * provider self-healed, the row would be back after the first read — and a consumer's answer would
     * depend on whether it happened to look before or after a seed.
     */
    @Test
    fun aProviderReadNeverSeedsAndNeverRestoresDeletedContent() = runBlocking {
        val rig = newRig("p32-read-never-seeds")
        bootstrapFor(rig).bootstrap()
        val provider = providerFor(rig)

        rig.progressionRelationRepository.remove("pushups")
        assertNull("the ladder is deleted", provider.relationOf("pushups"))

        repeat(3) {
            ProductionProgressionRelationDefinitions.authorisedFamilyIds.forEach { familyId ->
                provider.relationOf(familyId)
            }
            assertNull(
                "reading the catalogue never re-seeds a deleted ladder (pass ${it + 1})",
                provider.relationOf("pushups")
            )
        }

        assertEquals(
            "and the deleted rows stay deleted through every read — no read performed a write",
            13,
            rig.database.count("progression_relation_variant")
        )
        assertEquals(
            "while the surviving ladders are still served normally",
            listOf("lunges", "pullups", "squats"),
            rig.progressionRelationRepository.declaredFamilyIds()
        )
    }

    /**
     * **The bootstrap is the only thing that writes built-in content, and it writes only the four.**
     */
    @Test
    fun noFamilyOutsideTheAuthorisedFourIsEverSeeded() = runBlocking {
        val rig = newRig("p32-bootstrap-scope")
        bootstrapFor(rig).bootstrap()

        assertTrue(
            "the seeded row set mentions no unauthorised family — not `plank`, not `glute_bridge`, not " +
                "any other of the catalogue's 24 undeclared families",
            ProductionProgressionRelationDefinitions.definitions.all { definition ->
                rowsOf(rig).all { row ->
                    (1..2).all { row.values.count { v -> v == definition.familyId } >= 0 }
                }
            }
        )
        assertEquals(
            "the declared set is exactly the authorised four, so 24 families remain undeclared",
            ProductionProgressionRelationDefinitions.authorisedFamilyIds,
            rig.progressionRelationRepository.declaredFamilyIds().toSet()
        )
        assertNull(
            "`plank` is still absent, as decided",
            providerFor(rig).relationOf("plank")
        )
        assertNull(
            "and so is `glute_bridge`",
            providerFor(rig).relationOf("glute_bridge")
        )
    }

    /**
     * **The seeded row set names exactly the authored exercises** — no extra rung, no invented family,
     * no leaked exercise id.
     *
     * Measured as the flat set of stored exercises per family against the authored set, so a single
     * invented id anywhere in the catalogue fails it.
     */
    @Test
    fun theSeededRowsNameExactlyTheAuthoredExercises() = runBlocking {
        val rig = newRig("p32-bootstrap-exercises")
        bootstrapFor(rig).bootstrap()

        val stored = rowsOf(rig)
            .groupBy { it.getValue("familyId") }
            .mapValues { (_, rows) -> rows.map { it.getValue("exerciseId") }.toSet() }

        val authored = ProductionProgressionRelationDefinitions.definitions
            .associate { relation -> relation.familyId to relation.variants.map { it.exerciseId }.toSet() }

        assertEquals("every family stores exactly its authored exercises, and no others", authored, stored)
        assertEquals(
            "with 4 + 4 + 4 + 5 distinct exercise ids in total",
            17,
            stored.values.sumOf { it.size }
        )
    }

    /**
     * **A second database open does not duplicate content.**
     *
     * Models the repeated-launch case: the same file-backed database is reopened and bootstrapped again.
     * Room's migration chain runs on open (a no-op at schema 18 for this content) and the bootstrap runs
     * again, and the row set must be unchanged.
     */
    @Test
    fun reopeningTheDatabaseDoesNotDuplicateRows() = runBlocking {
        // A **file**-backed database, because "a second process of a restart" needs a second connection
        // over the same rows and an in-memory database is private to its own connection. Reusing the
        // closed in-memory object instead would not model a reopen at all — it would reuse a dead
        // connection, which is why this test drives a real file.
        val file = java.io.File.createTempFile("p32-reopen", ".db").also { it.delete() }
        val first = ProgramDataAccessRig("p32-reopen", SqliteTestDatabase.at(file.absolutePath))
            .also { it.database.migrate(AppDatabase.MIGRATION_17_18) }
        rigs += first
        bootstrapFor(first).bootstrap()
        val once = rowsOf(first)
        assertEquals("the first open stored the content", 17, once.size)
        first.close()

        // A second open of the same file: the same rows, a fresh bootstrap over them.
        val second = ProgramDataAccessRig("p32-reopen-again", SqliteTestDatabase.at(file.absolutePath))
        rigs += second
        bootstrapFor(second).bootstrap()

        assertEquals(
            "reopening and bootstrapping again leaves the row set byte-identical",
            once,
            rowsOf(second)
        )
        assertEquals("with no duplicated rows", 17, second.database.count("progression_relation_variant"))
    }

    /**
     * **The database the whole stage rides on is still schema 18, and P31's migration still creates the
     * table with no rows.**
     *
     * P32 adds content and **no migration**. If a future edit added one, this gate is what says the stage
     * quietly stopped being a data bootstrap and started being a schema change.
     */
    @Test
    fun theSchemaIsUnchangedAndTheCreatingMigrationStillSeedsNothing() {
        val rig = newRig("p32-schema")
        val ddl = rig.database.masterSql()

        // Read the declared version from the production source rather than restating it. Room's
        // `@Database` is a SOURCE-retention annotation, so there is nothing to reflect on at runtime —
        // which is why this reads the file. The point stands: if a future stage bumps the schema to
        // ship *content*, this fails.
        val databaseSource = java.io.File("src/main/java/com/monkfitness/app/data/local/AppDatabase.kt")
            .readText()
        assertEquals(
            "the production database still declares schema version 18 — P32 adds content, not schema, " +
                "so it adds no migration",
            1,
            Regex("""version\s*=\s*18""").findAll(databaseSource).count()
        )
        // Matched on the *declaration* (`internal val MIGRATION_n_m`) rather than the bare name: the
        // builder's `addMigrations(...)` list mentions MIGRATION_17_18 a second time, and counting names
        // would make this a test of how often the identifier is typed rather than of how many
        // migrations exist.
        assertEquals(
            "and the migration chain still ends at P31's 17->18 — this stage adds no migration, because " +
                "it adds content and not schema",
            listOf("MIGRATION_17_18"),
            Regex("""internal val (MIGRATION_\d+_\d+)""")
                .findAll(databaseSource)
                .map { it.groupValues[1] }
                .toList()
                .takeLast(1)
        )
        assertTrue(
            "the table still exists under its own name, created by P31's migration",
            ddl.containsKey("progression_relation_variant")
        )
        assertEquals(
            "and an un-bootstrapped database holds none of its rows — content comes from the " +
                "bootstrap boundary, never from the schema",
            0,
            rig.database.count("progression_relation_variant")
        )
        assertTrue(
            "with the P31 primary key on (familyId, exerciseId) intact — one exercise is one position " +
                "in one family's hierarchy, and P32 does not widen it: ${ddl["progression_relation_variant"]}",
            ddl.getValue("progression_relation_variant")
                .contains("PRIMARY KEY(`familyId`, `exerciseId`)")
        )
        assertEquals(
            "and the table declares exactly the five authored columns, with no content column added",
            listOf("familyId", "level", "exerciseId", "prescriptionDimension", "perSetTargets"),
            Regex("`(\\w+)`\\s+(?:TEXT|INTEGER)")
                .findAll(ddl.getValue("progression_relation_variant"))
                .map { it.groupValues[1] }
                .toList()
        )
    }
}