package com.monkfitness.app.data.repository

import com.monkfitness.app.data.model.ProgressionRelationVariantEntity
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P31's persistence claim: a family's declared ladder is stored and read back **value for value**,
 * and every rule the domain owns stays the domain's rule rather than becoming a storage convention.
 *
 * The assertions are ordered by how easily each could pass for the wrong reason — the round trip
 * first (it is vacuous if the fixture stored nothing), then the *absence* cases, then the refusals,
 * then the isolation cases. Each refusal is asserted through the *domain's own* constructor rather
 * than through a repository check, because the stage's claim is that the persistence layer contributes
 * no rules of its own.
 */
class ProgressionRelationRepositoryTest {

    private val rig = ProgramDataAccessRig("p31-relations")

    private val repository get() = rig.progressionRelationRepository

    @After
    fun close() = rig.close()

    // ================================================================ the round trip

    /**
     * **The central case.** A three-rung ladder comes back as the equal value, with every rung's
     * exercise, level, dimension and full per-set target list intact.
     *
     * Compared by equality rather than field-by-field: `ProgramProgressionRelation` is a `data class`,
     * so an equal value *is* the claim, and a per-field comparison could pass while some field the
     * domain does not compare had been mangled.
     */
    @Test
    fun aRelationComesBackAsTheEqualValueFieldForField() = runBlocking {
        val declared = ladder(
            "pushups",
            rung(1, "knee_pushups", RepPrescription(listOf(12, 10, 8, 6))),
            rung(2, "pushups", RepPrescription(listOf(10, 8, 6))),
            rung(3, "decline_pushups", RepPrescription(listOf(8, 6, 4)))
        )

        repository.store(declared)

        assertEquals(
            "a stored ladder reads back as the same value, not an approximation of it",
            declared,
            repository.relationOf("pushups")
        )
        assertEquals(
            "and it reads the same way through a second repository over the same file, which is what "
                + "rules out a value that only ever existed in the object that was written",
            declared,
            rig.freshProgressionRelationRepository().relationOf("pushups")
        )
    }

    /**
     * **The prescription survives whole.** Both dimensions round-trip with their exact per-set lists,
     * and the two lists are asserted to differ from a same-length uniform one.
     *
     * The `[12,10,8,6]` vs `[10,10,10,10]` comparison is the point of the case: a persistence layer
     * that collapsed a prescription to a set count, a mean or any single scalar would still return
     * *four* sets and pass a set-count assertion. Comparing the two lists directly is what makes a
     * scalar collapse observable rather than plausible.
     */
    @Test
    fun theFullPerSetPrescriptionSurvivesInBothDimensions() = runBlocking {
        val tapering = RepPrescription(listOf(12, 10, 8, 6))
        val uniform = RepPrescription(listOf(10, 10, 10, 10))
        val timed = TimePrescription(listOf(30, 30, 45))

        assertNotNull(
            "the two rep prescriptions are genuinely different values, so the round trip below is a " +
                "real claim rather than a comparison of two identical lists",
            tapering
        )
        assertTrue(
            "and they differ as lists even though they agree on set count and mean — which is exactly " +
                "the pair a scalar-collapsing column would confuse",
            tapering.perSetTargets != uniform.perSetTargets &&
                tapering.perSetTargets.size == uniform.perSetTargets.size
        )

        repository.store(
            ladder(
                "timed-family",
                rung(1, "dead_bug", tapering),
                rung(2, "side_plank", uniform),
                rung(3, "hollow_hold", timed)
            )
        )

        val read = requireNotNull(repository.relationOf("timed-family"))
        val byExercise = read.variants.associate { it.exerciseId to it.prescription }

        assertEquals(
            "a tapering rep prescription survives in its own order, element for element",
            listOf(12, 10, 8, 6),
            byExercise.getValue("dead_bug").perSetTargets
        )
        assertEquals(
            "and the uniform one beside it is still uniform — they did not collapse into each other",
            listOf(10, 10, 10, 10),
            byExercise.getValue("side_plank").perSetTargets
        )
        assertEquals(
            "a time prescription keeps its own dimension rather than reading back as repetitions",
            PrescriptionDimension.TIME_BASED,
            byExercise.getValue("hollow_hold").dimension
        )
        assertEquals(
            "and its own seconds, in set order",
            listOf(30, 30, 45),
            byExercise.getValue("hollow_hold").perSetTargets
        )
        assertEquals(
            "and the rep rungs kept theirs",
            listOf(
                PrescriptionDimension.REP_BASED,
                PrescriptionDimension.REP_BASED
            ),
            listOf(byExercise.getValue("dead_bug").dimension, byExercise.getValue("side_plank").dimension)
        )
    }

    /**
     * **The stored column is the list, not a scalar.** The raw row is read straight out of SQLite, so
     * this asserts the *bytes* rather than trusting the mapper to have been honest.
     */
    @Test
    fun theStoredColumnHoldsEveryPerSetTargetAndTheWholeDimensionToken() = runBlocking {
        repository.store(
            ladder("pushups", rung(1, "knee_pushups", RepPrescription(listOf(12, 10, 8, 6))))
        )

        val stored = rig.database.rows(
            "SELECT * FROM `progression_relation_variant` WHERE `exerciseId` = ?",
            "knee_pushups"
        ).single()

        assertEquals(
            "the column holds all four targets in set order, not a count and not an average",
            "12,10,8,6",
            stored["perSetTargets"]
        )
        assertEquals(
            "and the dimension is stored as its own name, never as an ordinal",
            "REP_BASED",
            stored["prescriptionDimension"]
        )
        assertEquals(
            "the row carries the family's own level as a number, because contiguity is arithmetic",
            "1",
            stored["level"]
        )
        assertNull(
            "and no Program or revision column exists to scope a ladder to a plan",
            stored["programId"] ?: stored["revisionId"]
        )
    }

    // ================================================================ canonical order and same-level

    /**
     * **Canonical order is the domain's, and it is what storage returns.**
     *
     * The fixture stores the variants in a deliberately scrambled order — a higher level first, and
     * within one level a reverse-alphabetical exercise id — and the read must come back in
     * `(level, exerciseId)` order. That is what makes the assembled value acceptable to the domain's
     * own constructor rather than needing a repair pass that could hide a bad write.
     */
    @Test
    fun variantsAreHeldInCanonicalLevelThenExerciseIdOrder() = runBlocking {
        // Written straight into the table in a deliberately scrambled order — highest level first, and
        // reverse-alphabetical exercise ids within the shared level. It has to be raw SQL rather than a
        // stored relation, because `ProgramProgressionRelation`'s own constructor refuses an unordered
        // list: the claim under test is that the *read* imposes the canonical order, so a fixture that
        // could not even hold a disorderly ladder would prove nothing about it.
        rig.database.exec(
            "INSERT INTO `progression_relation_variant` " +
                "(`familyId`, `level`, `exerciseId`, `prescriptionDimension`, `perSetTargets`) VALUES " +
                "('pushups', 3, 'decline_pushups', 'REP_BASED', '5'), " +
                "('pushups', 1, 'pushups_wide', 'REP_BASED', '12'), " +
                "('pushups', 1, 'knee_pushups', 'REP_BASED', '12'), " +
                "('pushups', 2, 'pseudo_pushups', 'REP_BASED', '8')"
        )

        assertEquals(
            "storage returns level-then-exercise-id order regardless of the order it was written in",
            listOf(
                "1:knee_pushups",
                "1:pushups_wide",
                "2:pseudo_pushups",
                "3:decline_pushups"
            ),
            requireNotNull(repository.relationOf("pushups")).variants.map { it.identity }
        )
    }

    /**
     * **Two variants on one level survive** — §15's same-level shape, which is the only case in which
     * a *variant* change is a bounded change rather than a difficulty change.
     *
     * This case exists partly as the counterweight to the previous one: a canonicalisation that
     * collapsed same-level variants would satisfy "canonical order" while losing a declaration.
     */
    @Test
    fun severalVariantsOnOneLevelAreStoredAndReadBackAsSeparateDeclarations() = runBlocking {
        repository.store(
            ladder(
                "pushups",
                rung(1, "knee_pushups", RepPrescription(listOf(12))),
                rung(1, "pushups_wide", RepPrescription(listOf(10))),
                rung(2, "pseudo_pushups", RepPrescription(listOf(8)))
            )
        )

        val read = requireNotNull(repository.relationOf("pushups"))

        assertEquals(
            "both variants of the shared level are declared, not merged into one",
            listOf("knee_pushups", "pushups_wide"),
            read.variantsAt(1).map { it.exerciseId }
        )
        assertEquals(
            "each kept its own prescription",
            listOf(listOf(12), listOf(10)),
            read.variantsAt(1).map { it.prescription.perSetTargets }
        )
        assertEquals(
            "and the relation still knows which are the same-level peers",
            listOf("pushups_wide"),
            read.sameLevelVariants(read.declared("knee_pushups")!!).map { it.exerciseId }
        )
        assertEquals(
            "a step up from a level whose successor declares exactly one variant is answered — the " +
                "ambiguity the relation refuses is about the level *above* declaring several, not about " +
                "this one doing so",
            "2:pseudo_pushups",
            read.stepUp(1)?.identity
        )
        assertEquals(
            "a step up from the top level is the ceiling and yields no answer",
            null,
            read.stepUp(2)
        )
    }

    // ================================================================ several families, no leaking

    /**
     * **Several families coexist and none leaks into another.**
     *
     * The two ladders are deliberately built from *overlapping level numbers and similar shapes*, so a
     * read that filtered by anything other than `familyId` — or that answered "the first family" — would
     * produce a plausible wrong value rather than an obvious one.
     */
    @Test
    fun severalFamiliesCoexistAndOneNeverLeaksIntoAnother() = runBlocking {
        val pushups = ladder(
            "pushups",
            rung(1, "knee_pushups", RepPrescription(listOf(12))),
            rung(2, "pushups", RepPrescription(listOf(8)))
        )
        val squats = ladder(
            "squats",
            rung(1, "box_squats", RepPrescription(listOf(15))),
            rung(2, "squats", RepPrescription(listOf(10))),
            rung(3, "assisted_squats", RepPrescription(listOf(8)))
        )
        val rows = ladder(
            "rows",
            rung(1, "band_rows", RepPrescription(listOf(20, 20))),
            rung(2, "rows", RepPrescription(listOf(15, 12)))
        )

        repository.store(pushups)
        repository.store(squats)
        repository.store(rows)

        assertEquals("each family reads back as its own value", pushups, repository.relationOf("pushups"))
        assertEquals("including the one with three rungs", squats, repository.relationOf("squats"))
        assertEquals("and the third", rows, repository.relationOf("rows"))
        assertEquals(
            "the catalogue reports exactly the three families it declares, each once",
            listOf("pushups", "rows", "squats"),
            repository.declaredFamilyIds()
        )
        assertEquals(
            "and no family's exercises appear in another's ladder",
            listOf("knee_pushups", "pushups"),
            requireNotNull(repository.relationOf("pushups")).variants.map { it.exerciseId }
        )
        assertEquals(
            "nor in the third family's",
            listOf("band_rows", "rows"),
            requireNotNull(repository.relationOf("rows")).variants.map { it.exerciseId }
        )
    }

    /**
     * **Replacing one family's ladder leaves every other family byte-identical.**
     *
     * This is the atomicity-adjacent case that matters for a per-family catalogue: the delete is
     * scoped to one `familyId`, and a table-wide delete would pass every assertion above while
     * silently destroying the other families' ladders.
     */
    @Test
    fun replacingOneFamilyLeavesEveryOtherFamilyExactlyAsItWas() = runBlocking {
        repository.store(ladder("pushups", rung(1, "knee_pushups", RepPrescription(listOf(12)))))
        val squatsBefore = ladder(
            "squats",
            rung(1, "box_squats", RepPrescription(listOf(15))),
            rung(2, "squats", RepPrescription(listOf(10)))
        )
        repository.store(squatsBefore)

        repository.replace(
            ladder(
                "pushups",
                rung(1, "knee_pushups", RepPrescription(listOf(12))),
                rung(2, "pushups", RepPrescription(listOf(8))),
                rung(3, "decline_pushups", RepPrescription(listOf(6)))
            )
        )

        assertEquals(
            "the replaced family now has the new definition in full",
            3,
            requireNotNull(repository.relationOf("pushups")).variants.size
        )
        assertEquals(
            "and the other family's ladder is untouched, value for value",
            squatsBefore,
            repository.relationOf("squats")
        )
        assertEquals(
            "the old rung was dropped rather than accumulated beside the new ones",
            5,
            rig.database.count("progression_relation_variant")
        )
    }

    // ================================================================ absence

    /**
     * **An unknown family is `null`** — not an empty relation, not a fabricated one.
     *
     * The negative half matters as much as the positive: `ProgramProgressionRelation` refuses an empty
     * variant list, so a repository that answered with an empty relation could not construct one at
     * all. The claim is therefore that the *absence* is represented, not that the ladder is empty.
     */
    @Test
    fun anUnknownFamilyIsAnsweredNullAndNeverAFabricatedRelation() = runBlocking {
        repository.store(ladder("pushups", rung(1, "knee_pushups", RepPrescription(listOf(12)))))

        assertNull(
            "a family this catalogue never declared is 'not declared', not an empty ladder",
            repository.relationOf("pullups")
        )
        assertNull(
            "and neither is a near miss of a family it did declare — there is no fallback family",
            repository.relationOf("pushup")
        )
        assertNull(
            "nor the empty-string family, which is not a family anyone declared",
            repository.relationOf("")
        )
        assertNotNull(
            "while the declared family still reads, so the null above is an absence and not a broken " +
                "read",
            repository.relationOf("pushups")
        )
    }

    /** **An empty database answers `null`** — the state production is actually in after this stage. */
    @Test
    fun anEmptyCatalogueAnswersNullForEveryFamily() = runBlocking {
        assertEquals("nothing was stored", 0, rig.database.count("progression_relation_variant"))
        assertNull("so every family is undeclared", repository.relationOf("pushups"))
        assertNull("including a second one", repository.relationOf("squats"))
        assertEquals("and the catalogue names no families at all", emptyList<String>(), repository.declaredFamilyIds())
    }

    // ================================================================ the domain's own refusals

    /**
     * **A duplicate exercise in one family is refused, by the domain's own rule.**
     *
     * Asserted through the domain constructor, because the stage's claim is that the persistence layer
     * adds no rules: the refusal message must come from `ProgramProgressionRelation`, not from a check
     * the repository invented.
     */
    @Test
    fun oneExerciseCannotBeDeclaredTwiceInOneFamily() {
        val failure = thrownBy {
            ladder(
                "pushups",
                rung(1, "knee_pushups", RepPrescription(listOf(12))),
                rung(2, "knee_pushups", RepPrescription(listOf(8)))
            )
        }

        assertTrue(
            "the refusal is the domain's duplicate-exercise rule, not a storage convention: $failure",
            failure.message.orEmpty().contains("found twice")
        )
        assertTrue(
            "and it names the exercise that was declared twice",
            failure.message.orEmpty().contains("knee_pushups")
        )
    }

    /**
     * **The database refuses a duplicate pair outright**, which is the stronger half of the same rule:
     * even a caller that skipped the domain cannot store one exercise at two positions.
     */
    @Test
    fun thePrimaryKeyItselfRefusesADuplicateFamilyExercisePair() = runBlocking {
        rig.database.insertRow(
            ProgressionRelationVariantEntity("pushups", 1, "knee_pushups", "REP_BASED", listOf(12))
        )
        val second = thrownBySuspending {
            rig.database.insertRow(
                ProgressionRelationVariantEntity("pushups", 2, "knee_pushups", "REP_BASED", listOf(8))
            )
        }

        assertTrue(
            "the table's own identity constraint refuses the second row: $second",
            second.message.orEmpty().contains("UNIQUE", ignoreCase = true) ||
                second.message.orEmpty().contains("PRIMARY KEY", ignoreCase = true)
        )
        assertEquals(
            "and the first row is the one that survives, unchanged",
            "1",
            rig.database.scalar("SELECT `level` FROM `progression_relation_variant`")
        )
    }

    /**
     * **A level gap is refused.** A ladder that declared levels 1 and 3 would leave "the next rung up"
     * undefined in a way no policy could honestly resolve.
     */
    @Test
    fun nonContiguousLevelsAreRefused() {
        val failure = thrownBy {
            ladder(
                "pushups",
                rung(1, "knee_pushups", RepPrescription(listOf(12))),
                rung(3, "pushups", RepPrescription(listOf(8)))
            )
        }

        assertTrue(
            "the refusal is the domain's contiguity rule: $failure",
            failure.message.orEmpty().contains("contiguous")
        )
    }

    /** **An empty family is refused** — a family with no declared variant has no hierarchy at all. */
    @Test
    fun aFamilyWithNoDeclaredVariantHasNoRelationAtAll() {
        val failure = thrownBy { ProgramProgressionRelation(familyId = "pushups", variants = emptyList()) }

        assertTrue(
            "an empty ladder is not a ladder the domain will hold: $failure",
            failure.message.orEmpty().contains("no progression hierarchy")
        )
    }

    /**
     * **A stored dimension with no prescription subtype is refused on read**, not defaulted.
     *
     * The entity deliberately lets the token through (`SET_BASED` is a real vocabulary member), so the
     * read is the place where "there is no algorithm for this yet" has to be said. Substituting the
     * nearest implemented dimension would restate the rung as something its author never claimed.
     */
    @Test
    fun aStoredDimensionWithNoPrescriptionSubtypeIsRefusedRatherThanDefaulted() = runBlocking {
        // Written through raw SQL because the entity's own guard is not what is under test here.
        rig.database.exec(
            "INSERT INTO `progression_relation_variant` " +
                "(`familyId`, `level`, `exerciseId`, `prescriptionDimension`, `perSetTargets`) " +
                "VALUES ('pushups', 1, 'knee_pushups', 'SET_BASED', '12,10')"
        )

        val failure = thrownBySuspending { repository.relationOf("pushups") }

        assertTrue(
            "the read refuses a dimension it has no prescription for, naming it: $failure",
            failure.message.orEmpty().contains("SET_BASED")
        )
        assertTrue(
            "and the message says why rather than silently substituting another dimension",
            failure.message.orEmpty().contains("no prescription subtype")
        )
    }

    /** **An unknown dimension token is refused** rather than read as the nearest known one. */
    @Test
    fun anUnknownDimensionTokenIsRefusedLoudly() = runBlocking {
        rig.database.exec(
            "INSERT INTO `progression_relation_variant` " +
                "(`familyId`, `level`, `exerciseId`, `prescriptionDimension`, `perSetTargets`) " +
                "VALUES ('pushups', 1, 'knee_pushups', 'REPETITIONS', '12,10')"
        )

        val failure = thrownBySuspending { repository.relationOf("pushups") }

        assertTrue(
            "a token outside the vocabulary is refused: $failure",
            failure.message.orEmpty().contains("REPETITIONS")
        )
    }

    // ================================================================ the storage boundary adds no rules

    /**
     * **No heuristic lives in the repository.**
     *
     * A read that renumbered levels, collapsed a prescription or defaulted a dimension would be *adding*
     * a ladder-construction policy below the domain. The suite measures that by asserting the exact
     * stored rows behind a read: what goes in is what comes out, row for row.
     */
    @Test
    fun theReadChangesNothingAboutWhatWasStored() = runBlocking {
        val declared = ladder(
            "pushups",
            rung(1, "knee_pushups", RepPrescription(listOf(12, 10, 8, 6))),
            rung(2, "pushups_wide", TimePrescription(listOf(30, 45)))
        )
        repository.store(declared)

        val rows = rig.database.rows("SELECT * FROM `progression_relation_variant` ORDER BY `level`")

        assertEquals("the stored row count is the declared variant count", 2, rows.size)
        assertEquals(
            "the levels were stored as authored, not renumbered",
            listOf("1", "2"),
            rows.map { it["level"] }
        )
        assertEquals(
            "the dimensions were stored as their own names",
            listOf("REP_BASED", "TIME_BASED"),
            rows.map { it["prescriptionDimension"] }
        )
        assertEquals(
            "and the target lists were stored element for element",
            listOf("12,10,8,6", "30,45"),
            rows.map { it["perSetTargets"] }
        )
        assertEquals(
            "and reading twice returns the same value both times",
            repository.relationOf("pushups"),
            repository.relationOf("pushups")
        )
    }

    /**
     * **Removing one family's ladder is a real, scoped operation** — and it leaves the rest of the
     * catalogue alone. It is the one delete path, so its blast radius is asserted rather than assumed.
     */
    @Test
    fun removingOneFamilyLeavesTheOthersDeclared() = runBlocking {
        repository.store(ladder("pushups", rung(1, "knee_pushups", RepPrescription(listOf(12)))))
        repository.store(ladder("squats", rung(1, "box_squats", RepPrescription(listOf(15)))))

        repository.remove("pushups")

        assertNull("the removed family is undeclared again", repository.relationOf("pushups"))
        assertNotNull("and the other family is untouched", repository.relationOf("squats"))
        assertEquals("with its own row still stored", 1, rig.database.count("progression_relation_variant"))
    }

    // ================================================================ helpers

    private fun rung(level: Int, exerciseId: String, prescription: Prescription) =
        ProgramProgressionVariant(level = level, exerciseId = exerciseId, prescription = prescription)

    private fun ladder(familyId: String, vararg variants: ProgramProgressionVariant) =
        ProgramProgressionRelation(familyId = familyId, variants = variants.toList())

    /** The failure a lambda threw, or a failed assertion if it threw nothing. */
    private fun thrownBy(block: () -> Unit): Throwable = try {
        block()
        fail("expected the domain to refuse this relation, and it accepted it")
        AssertionError("unreachable")
    } catch (failure: Throwable) {
        failure
    }

    /**
     * The same, for a block that calls a suspending read.
     *
     * A separate helper rather than a `suspend` parameter on [thrownBy], because the domain refusals
     * are the *pure* cases and this stage's own lesson is that a `suspend` lambda cannot be smuggled
     * into `assertThrows`/`runCatching` — so the two shapes stay visibly distinct.
     */
    private suspend fun thrownBySuspending(block: suspend () -> Unit): Throwable = try {
        block()
        fail("expected this read to be refused, and it succeeded")
        AssertionError("unreachable")
    } catch (failure: Throwable) {
        failure
    }
}