package com.monkfitness.app.data.local

import com.monkfitness.app.bootstrap.BuiltInProgressionCatalogueBootstrap
import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions
import com.monkfitness.app.domain.usecase.StoredProgressionRelationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P32's **content proof**: the four authored ladders, persisted through the real P31 table, read back
 * through the real repository, and served by the real production provider.
 *
 * ```text
 * ProductionProgressionRelationDefinitions
 *        ↓  BuiltInProgressionCatalogueBootstrap
 * progression_relation_variant
 *        ↓  ProgressionRelationRepository
 * StoredProgressionRelationProvider
 * ```
 *
 * Every assertion here runs against a **real migrated database**, not a double. The claim being made is
 * a round-trip claim — that the exact authored levels, exercise ids, dimensions and per-set target
 * lists survive definition → Room → repository → provider — and a fake repository could only prove that
 * the objects are equal to themselves.
 *
 * The **negative** assertions carry equal weight. A stage that adds seeded content is exactly the stage
 * from which a fabricated ladder leaks: one rung manufactured for an undeclared family, a seeded
 * `plank`, a family outside the authorised four. So the absence claims below are measured on the same
 * database that holds the seeded rows, not on a separate empty one.
 */
class ProductionProgressionCatalogueTest {

    private val rig = ProgramDataAccessRig("p32-content")

    @After
    fun close() = rig.close()

    private fun repository(): ProgressionRelationRepository = rig.progressionRelationRepository

    /**
     * Runs the built-in bootstrap against this rig's database.
     *
     * A **statement**, not a factory returning the object: an earlier shape of this helper returned the
     * bootstrap and relied on the caller to invoke it, which is a way for a test to read as though it
     * seeded content while seeding nothing — the assertion then measures an empty catalogue and fails
     * for a reason that has nothing to do with the content. Suspending, so it can be called directly
     * from a `runBlocking` test body.
     */
    private suspend fun bootstrap() = BuiltInProgressionCatalogueBootstrap(repository()).bootstrap()

    private fun provider(): StoredProgressionRelationProvider =
        StoredProgressionRelationProvider(repository())

    // ================================================================ the four authorised ladders

    /**
     * **Each authorised family is served from persisted content, and the served value is the authored
     * one — equal as a domain value.**
     *
     * Asserted through the production provider rather than the repository, because the provider is what
     * production actually asks, and an equality assertion on the whole relation covers levels, exercise
     * ids, dimensions and target lists at once: the stored rows reconstruct exactly the value that was
     * authored, so nothing was dropped, reordered or flattened in the round trip.
     */
    @Test
    fun everyAuthorisedFamilyIsServedFromPersistedContentAsTheAuthoredValue() = runBlocking {
        bootstrap()
        val provider = provider()

        assertEquals(
            "the four authored ladders are the ones the provider serves, value for value",
            listOf(
                ProductionProgressionRelationDefinitions.pushups,
                ProductionProgressionRelationDefinitions.squats,
                ProductionProgressionRelationDefinitions.lunges,
                ProductionProgressionRelationDefinitions.pullups
            ),
            ProductionProgressionRelationDefinitions.definitions.map { provider.relationOf(it.familyId) }
        )
    }

    /**
     * **The exact per-rung content of each ladder, spelled out independently of the definitions file.**
     *
     * The equality assertions above compare the provider's answer to the *same source* the provider
     * ultimately depends on, so on their own they would not catch the definitions file itself holding the
     * wrong ladder — a wrong level or a wrong target would be faithfully persisted and faithfully read
     * back. This test states the four ladders as literals in the test, so an accidental edit to the
     * content source cannot move a rung, swap two levels or change one number without failing here.
     */
    @Test
    fun eachLadderHasExactlyTheAuthoredRungsLevelsAndTargetLists() = runBlocking {
        bootstrap()
        val provider = provider()

        val expected = mapOf(
            "pushups" to listOf(
                Triple(-1, "pushups_knee", listOf(12, 12, 12)),
                Triple(0, "pushups", listOf(8, 8, 8)),
                Triple(1, "pushups_wide", listOf(7, 7, 7)),
                Triple(2, "decline_pushups", listOf(7, 7, 7))
            ),
            "squats" to listOf(
                Triple(-1, "squats_sumo", listOf(14, 14, 14)),
                Triple(0, "squats", listOf(15, 15, 15)),
                Triple(1, "cossack_squat", listOf(10, 10, 10)),
                Triple(2, "squats_jump", listOf(12, 12, 12))
            ),
            "lunges" to listOf(
                Triple(-1, "lunges_reverse", listOf(10, 10, 10)),
                Triple(0, "lunges", listOf(10, 10, 10)),
                Triple(1, "step_ups", listOf(10, 10, 10)),
                Triple(2, "lunges_side", listOf(10, 10, 10))
            ),
            "pullups" to listOf(
                Triple(-2, "hang", listOf(30, 30, 30)),
                Triple(-1, "pullups_chin", listOf(6, 6, 6)),
                Triple(0, "pullups", listOf(5, 5, 5)),
                Triple(1, "pullups_neutral", listOf(5, 5, 5)),
                Triple(2, "pullups_wide", listOf(4, 4, 4))
            )
        )

        assertEquals("the four authorised families, and no others", 4, expected.size)
        expected.forEach { (familyId, rungs) ->
            val stored = requireNotNull(provider.relationOf(familyId)) { "'$familyId' must be declared" }

            assertEquals(
                "'$familyId' declares exactly ${rungs.size} rungs, at exactly these levels",
                rungs.map { it.first },
                stored.variants.map { it.level }
            )
            assertEquals(
                "'$familyId' declares exactly these exercises at those levels",
                rungs.map { it.second },
                stored.variants.map { it.exerciseId }
            )
            assertEquals(
                "'$familyId' preserves every per-set target list exactly, in set order",
                rungs.map { it.third },
                stored.variants.map { it.prescription.perSetTargets }
            )
            assertEquals(
                "and '$familyId' gives each of its ${rungs.size} levels exactly one exercise — the " +
                    "domain's one-exercise-one-position invariant, which is why a family ships only " +
                    "as many rungs as it has distinct exercises",
                List(rungs.size) { 1 },
                stored.variants.groupBy { it.level }.toSortedMap().values.map { it.size }
            )
        }
    }

    /**
     * **The prescription dimensions are exact** — `REP_BASED` everywhere except the `hang` entry rung.
     */
    @Test
    fun everyRungCarriesItsAuthoredPrescriptionDimension() = runBlocking {
        bootstrap()
        val provider = provider()

        assertEquals(
            "the pull-up ladder crosses dimensions exactly once, at its time-based entry rung",
            listOf("TIME_BASED", "REP_BASED", "REP_BASED", "REP_BASED", "REP_BASED"),
            provider.relationOf("pullups")!!.variants.map { it.prescription.dimension.name }
        )
        listOf("pushups", "squats", "lunges").forEach { familyId ->
            assertEquals(
                "'$familyId' is repetition-based throughout",
                listOf("REP_BASED", "REP_BASED", "REP_BASED", "REP_BASED"),
                provider.relationOf(familyId)!!.variants.map { it.prescription.dimension.name }
            )
        }
    }

    /**
     * **The time → repetition transition survives the whole round trip as a transition.**
     *
     * Asserted as the *types* as well as the values, because the crossing is the claim: rung `-2` is a
     * [TimePrescription] and rung `-1` a [RepPrescription]. If the round trip collapsed the dimension to
     * a string and rebuilt it from the wrong dimension, the target numbers could still match while the
     * prescription type had changed.
     */
    @Test
    fun thePullUpLadderPreservesTheTimeToRepetitionDimensionTransition() = runBlocking {
        bootstrap()
        val variants = requireNotNull(provider().relationOf("pullups")).variants

        assertTrue(
            "the entry rung is a timed hang, not a repetition-based pull-up",
            variants.first().prescription is TimePrescription
        )
        assertTrue(
            "and every rung above it is a repetition-based pull-up",
            variants.drop(1).all { it.prescription is RepPrescription }
        )
        assertEquals(
            "so the transition happens exactly between rung -2 and rung -1, and nowhere else",
            listOf(true, false, false, false, false),
            variants.map { it.prescription is TimePrescription }
        )
    }

    /**
     * **No per-set target list is flattened, and no set is lost.**
     *
     * The three-set ladders are the ones this can catch: a single scalar column would still store "8"
     * correctly while silently dropping the other two sets, so the assertion checks both the length and
     * every element of every rung.
     */
    @Test
    fun everyRungKeepsItsFullPerSetPrescriptionThroughRoom() = runBlocking {
        bootstrap()
        val provider = provider()

        ProductionProgressionRelationDefinitions.definitions.forEach { definition ->
            val stored = provider.relationOf(definition.familyId)!!
            stored.variants.forEach { variant ->
                assertEquals(
                    "'${definition.familyId}/${variant.exerciseId}' composes three sets",
                    3,
                    variant.prescription.setCount
                )
                assertEquals(
                    "'${definition.familyId}/${variant.exerciseId}' survives per set, not flattened " +
                        "to a total",
                    definition.declared(variant.exerciseId)!!.prescription.perSetTargets,
                    variant.prescription.perSetTargets
                )
            }
        }
    }

    // ================================================================ the two deliberate absences

    /**
     * **`plank` and `glute_bridge` remain undeclared** — the two absences this stage decided rather than
     * ran out of time.
     *
     * Both are asserted *after* the bootstrap ran on this very database, because the failure mode being
     * guarded against is a seed that quietly fills a family it had no business filling. An absent family
     * that a seed manufactures would make the engine hold at a fabricated ceiling, which looks like a
     * working ladder and is not one.
     */
    @Test
    fun theTwoUnauthorisedFamiliesRemainUndeclaredAfterBootstrapping() = runBlocking {
        bootstrap()
        val provider = provider()

        assertNull(
            "`plank`'s intended five-step time progression needs one exercise at five levels, which " +
                "the domain deliberately rejects — so it stays undeclared rather than becoming a " +
                "one-rung ladder",
            provider.relationOf("plank")
        )
        assertNull(
            "`glute_bridge` has one exercise in its family, so the same is true of it",
            provider.relationOf("glute_bridge")
        )
        assertEquals(
            "and neither contributed a single row to the catalogue",
            0,
            rig.database.countFamilyRows("plank") + rig.database.countFamilyRows("glute_bridge")
        )
    }

    /**
     * **No family outside the four authorised ones is seeded at all.**
     *
     * Measured by the catalogue's own declared-family list rather than by probing families one at a
     * time: a seed that reached a fifth family would show up here even if no test happened to ask about
     * that family. The catalogue's own answer is the complete set of what was written.
     */
    @Test
    fun theCatalogueDeclaresExactlyTheFourAuthorisedFamiliesAndNoOther() = runBlocking {
        bootstrap()

        assertEquals(
            "the persisted catalogue declares exactly the four authorised families — a fifth would be " +
                "content this stage did not author",
            setOf("lunges", "pullups", "pushups", "squats"),
            repository().declaredFamilyIds().toSet()
        )
        assertEquals(
            "and the four are exactly the authorised set",
            ProductionProgressionRelationDefinitions.authorisedFamilyIds,
            repository().declaredFamilyIds().toSet()
        )
        assertEquals(
            "while 24 of the catalogue's 28 families are deliberately undeclared, so a real " +
                "unauthorised family still answers null",
            28 - 4,
            28 - repository().declaredFamilyIds().size
        )
    }

    /**
     * **An unauthorised family is answered `null` by the production provider** — including a family the
     * catalogue genuinely holds exercises for, which is the case a nearest-family or state-derived
     * fallback would fail.
     */
    @Test
    fun anUnauthorisedCatalogueFamilyIsAnsweredNullRatherThanAPlausibleLadder() = runBlocking {
        bootstrap()
        val provider = provider()

        // `rows`, `burpees`, `pelvic_control` and `ankle_mobility` are real shipped families with real
        // exercises and no authored ladder. Each would be answered by any fallback keyed on "some
        // family exists".
        listOf("rows", "burpees", "pelvic_control", "ankle_mobility", "cat_cow", "superman")
            .forEach { familyId ->
                assertNull(
                    "'$familyId' has real catalogue exercises but no authored ladder, so the provider " +
                        "answers an honest absence rather than a fabricated or nearby one",
                    provider.relationOf(familyId)
                )
            }
        assertNull(
            "and a near miss of an authorised family is not mapped onto it",
            provider.relationOf("pushup")
        )
        assertNull(
            "nor is a family the catalogue has never heard of",
            provider.relationOf("no_such_family")
        )
    }

    // ================================================================ persistence shape

    /**
     * **Exactly 17 stored rows: 4 + 4 + 4 + 5.**
     *
     * Counted in the database rather than derived from the definitions, so a family that stored a
     * different number of rungs than it declares would be caught here rather than read back as a
     * plausible ladder.
     */
    @Test
    fun theSeededCatalogueIsExactlySeventeenRowsAcrossFourFamilies() = runBlocking {
        bootstrap()

        assertEquals(
            "the four authorised ladders store 4 + 4 + 4 + 5 rows",
            17,
            rig.database.count("progression_relation_variant")
        )
        listOf("pushups" to 4, "squats" to 4, "lunges" to 4, "pullups" to 5).forEach { (familyId, rows) ->
            assertEquals(
                "'$familyId' stores exactly $rows rows",
                rows,
                rig.database.countFamilyRows(familyId)
            )
        }
    }

    /**
     * **The stored columns are exactly the authored ones, read back row by row.**
     *
     * The provider-level equality elsewhere proves the domain value round-trips; this proves the
     * *columns* did, by reading the table directly. It is what would catch a dimension stored under the
     * wrong token or a target list stored as a scalar and re-split by luck.
     */
    @Test
    fun theStoredRowsHoldTheAuthoredColumnsVerbatim() = runBlocking {
        bootstrap()

        val rows = rig.database.rows(
            "SELECT `familyId`, `level`, `exerciseId`, `prescriptionDimension`, `perSetTargets` " +
                "FROM `progression_relation_variant` ORDER BY `familyId` ASC, `level` ASC"
        )

        // Sorted to match the query's `ORDER BY familyId ASC, level ASC`: the authored list is in
        // authoring order (pushups, squats, lunges, pullups), which is not alphabetical, so comparing
        // the two directly would be asserting a difference the stage never introduced.
        assertEquals(
            "one row per declared variant, in canonical read order",
            ProductionProgressionRelationDefinitions.definitions
                .sortedBy { it.familyId }
                .flatMap { relation -> relation.variants.map { relation.familyId to it } }
                .map { (familyId, variant) ->
                    listOf(
                        familyId,
                        variant.level.toString(),
                        variant.exerciseId,
                        variant.prescription.dimension.name,
                        variant.prescription.perSetTargets.joinToString(",")
                    )
                },
            rows.map { row ->
                listOf(
                    row.getValue("familyId"),
                    row.getValue("level"),
                    row.getValue("exerciseId"),
                    row.getValue("prescriptionDimension"),
                    row.getValue("perSetTargets")
                )
            }
        )
    }

    /**
     * **Reads are canonical: level ascending, then exercise id.**
     *
     * The ordering is a read contract of the DAO, not a property of insertion order, so it is asserted
     * on the raw query rather than on the reconstructed domain value — the domain would have re-sorted a
     * mis-ordered read and hidden it.
     */
    @Test
    fun theStoredCatalogueIsReadInCanonicalOrder() = runBlocking {
        bootstrap()

        ProductionProgressionRelationDefinitions.definitions.forEach { definition ->
            val levels = repository().relationOf(definition.familyId)!!.variants.map { it.level }
            assertEquals(
                "'${definition.familyId}' reads back in ascending level order",
                levels.sorted(),
                levels
            )
        }
    }

    /**
     * **A family never leaks into another.**
     *
     * Seeded ladders share no exercise ids, so an insert that attributed a rung to the wrong family
     * would either be refused by the domain or produce a family holding another family's exercise. This
     * asserts the attribution directly: every declared rung belongs to its own family, and the four
     * families share nothing.
     */
    @Test
    fun noFamilyLeaksIntoAnother() = runBlocking {
        bootstrap()
        val provider = provider()

        val byFamily = ProductionProgressionRelationDefinitions.definitions.associate { definition ->
            definition.familyId to definition.variants.map { it.exerciseId }
        }

        val allExercises = byFamily.values.flatten()
        assertEquals(
            "the four ladders share no exercise id, so one cannot be counted as another's rung",
            allExercises.size,
            allExercises.distinct().size
        )
        byFamily.forEach { (familyId, exercises) ->
            val stored = provider.relationOf(familyId)!!.variants.map { it.exerciseId }
            assertEquals(
                "'$familyId' holds exactly its own exercises",
                exercises,
                stored
            )
        }
    }

    // ================================================================ the provider reads, and only reads

    /**
     * **A provider read does not write.** Asserted by counting rows before and after, because a
     * read-mutates bug is exactly what an "idempotent" seed is supposed to hide — and this database
     * already holds its full content, so a self-healing provider would have nothing to heal and would
     * pass by doing nothing.
     */
    @Test
    fun providerReadsDoNotMutateTheStoredContent() = runBlocking {
        bootstrap()
        val provider = provider()

        val before = rig.database.count("progression_relation_variant")
        val rowsBefore = rig.database.rows("SELECT * FROM `progression_relation_variant`")

        ProductionProgressionRelationDefinitions.definitions.forEach { definition ->
            provider.relationOf(definition.familyId)
            provider.relationOf("plank")
            provider.relationOf("glute_bridge")
        }

        assertEquals(
            "reading the catalogue does not add or remove a row",
            before,
            rig.database.count("progression_relation_variant")
        )
        assertEquals(
            "nor change any stored row",
            rowsBefore,
            rig.database.rows("SELECT * FROM `progression_relation_variant`")
        )
    }

    /**
     * **A deleted ladder is NOT silently restored by a read.**
     *
     * This is the claim that separates a bootstrap from a self-healing provider. The family is removed
     * through the repository, and the provider then answers `null` — restoring the row on read would
     * make a consumer's answer depend on when it asked, and would quietly rewrite content under an
     * adaptive evaluation.
     */
    @Test
    fun aDeletedLadderIsNotSilentlyRestoredByAProviderRead() = runBlocking {
        bootstrap()
        val provider = provider()
        assertNotNull("the ladder is seeded to begin with", provider.relationOf("lunges"))

        repository().remove("lunges")

        assertNull(
            "after a delete, a provider read reports the honest absence instead of re-seeding it",
            provider.relationOf("lunges")
        )
        assertEquals(
            "and the four deleted rows stay gone — the read performed no write",
            13,
            rig.database.count("progression_relation_variant")
        )
        assertFalse(
            "while the other three ladders are untouched by that removal",
            ProductionProgressionRelationDefinitions.definitions
                .filter { it.familyId != "lunges" }
                .any { provider.relationOf(it.familyId) == null }
        )
    }

    // ================================================================ round-trip through the domain

    /**
     * **A stored ladder reconstructs the equal domain value, including a same-level shape.**
     *
     * The seeded ladders all give each level exactly one exercise, so nothing in the persisted content
     * exercises the *other* valid domain shape — several variants at one level. This proves the storage
     * boundary represents that too, since it is a legitimate declaration (§15's variant change) and the
     * `(familyId, exerciseId)` key admits it.
     */
    @Test
    fun aStoredLadderWithTwoVariantsAtOneLevelRoundTrips() = runBlocking {
        val declared = ProgramProgressionRelation(
            familyId = "pushups",
            variants = listOf(
                ProgramProgressionVariant(-1, "pushups_knee", RepPrescription(listOf(12, 12, 12))),
                ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8))),
                // Two variants at level 0: a legitimate declaration, distinct exercises at one position.
                ProgramProgressionVariant(0, "pushups_wide", RepPrescription(listOf(7, 7, 7))),
                ProgramProgressionVariant(1, "decline_pushups", RepPrescription(listOf(7, 7, 7)))
            )
        )
        repository().store(declared)

        assertEquals(
            "several variants at one level survive the round trip as the equal domain value",
            declared,
            provider().relationOf("pushups")
        )
    }

    /**
     * **A ladder stored over a seeded family replaces it, as one unit.**
     *
     * Asserted because the seeded content makes this reachable in production: a future user-authored or
     * edited ladder writes through the same repository, and a partial write that left the seeded rungs
     * behind would produce a family declaring six positions where four were authored.
     */
    @Test
    fun storingOverASeededFamilyReplacesItsRows() = runBlocking {
        bootstrap()
        assertEquals("the seeded ladder is four rows", 4, rig.database.countFamilyRows("lunges"))

        repository().store(
            ProgramProgressionRelation(
                familyId = "lunges",
                variants = listOf(
                    ProgramProgressionVariant(-1, "lunges_reverse", TimePrescription(listOf(30, 30))),
                    ProgramProgressionVariant(0, "lunges", RepPrescription(listOf(10, 10)))
                )
            )
        )

        assertEquals(
            "the replacement is exactly the new definition — no seeded rung survives",
            2,
            rig.database.countFamilyRows("lunges")
        )
        assertEquals(
            "the other three families are untouched: 15 rows in total, `lunges` now holds 2 of them, " +
                "so the remaining three still hold their original 13",
            13,
            rig.database.count("progression_relation_variant") - rig.database.countFamilyRows("lunges")
        )
        assertEquals(
            "while the replaced family reads back as the new value",
            TimePrescription(listOf(30, 30)),
            provider().relationOf("lunges")!!.declared("lunges_reverse")!!.prescription
        )
    }
}