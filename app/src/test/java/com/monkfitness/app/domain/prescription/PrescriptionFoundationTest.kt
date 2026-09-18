package com.monkfitness.app.domain.prescription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The prescription foundation.
 *
 * Two things are pinned here. First, that a prescription is **per set**: `12 / 10 / 8 / 6` and
 * `30s / 30s / 45s` must survive the round trip through the model, because a uniform prescription
 * expanded from a `sets + target` pair would lose exactly the information the plan carries.
 *
 * Second, the *room* left for the dimensions that are not implemented yet: the five dimension names
 * exist, and only two of them have a subtype — so a later stage adds `SetBased`, `DifficultyBased` or
 * `RestBased` as an additive change, and no algorithm, coefficient or unit for them can be smuggled in
 * today.
 */
class PrescriptionFoundationTest {

    private val packageDir = File("src/main/java/com/monkfitness/app/domain/prescription").let { dir ->
        if (dir.isDirectory) dir else File("app/$dir")
    }

    // ------------------------------------------------------------------ the two implemented dimensions

    @Test
    fun aRepetitionPrescriptionKeepsEverySetAtItsOwnValue() {
        val prescription = RepPrescription(listOf(12, 10, 8, 6))

        assertEquals(PrescriptionDimension.REP_BASED, prescription.dimension)
        assertEquals(4, prescription.setCount)
        assertEquals(listOf(12, 10, 8, 6), prescription.perSetReps)
        assertEquals(listOf(12, 10, 8, 6), prescription.perSetTargets)
        assertEquals(12, prescription.targetForSet(1))
        assertEquals(6, prescription.targetForSet(4))
        assertEquals(36, prescription.totalTarget)
    }

    @Test
    fun aTimePrescriptionKeepsEverySetAtItsOwnDuration() {
        val prescription = TimePrescription(listOf(30, 30, 45))

        assertEquals(PrescriptionDimension.TIME_BASED, prescription.dimension)
        assertEquals(3, prescription.setCount)
        assertEquals(listOf(30, 30, 45), prescription.perSetSeconds)
        assertEquals(45, prescription.targetForSet(3))
        assertEquals(105, prescription.totalTarget)
    }

    @Test
    fun aUniformPrescriptionIsOnlyShorthandForThePerSetList() {
        assertEquals(RepPrescription(listOf(10, 10, 10)), RepPrescription.uniform(sets = 3, reps = 10))
        assertEquals(
            TimePrescription(listOf(30, 30)),
            TimePrescription.uniform(sets = 2, seconds = 30)
        )
        assertEquals(3, RepPrescription.uniform(sets = 3, reps = 10).setCount)
    }

    // ------------------------------------------------------------------ what a prescription may not say

    @Test
    fun aPrescriptionWithoutSetsIsNotRepresentable() {
        assertRejects("an empty repetition prescription") { RepPrescription(emptyList()) }
        assertRejects("an empty time prescription") { TimePrescription(emptyList()) }
    }

    @Test
    fun aPrescribedSetAlwaysAsksForSomething() {
        assertRejects("a zero-repetition set") { RepPrescription(listOf(10, 0)) }
        assertRejects("a negative-repetition set") { RepPrescription(listOf(10, -1)) }
        assertRejects("a zero-second set") { TimePrescription(listOf(30, 0)) }
        assertRejects("a uniform prescription of no sets") { RepPrescription.uniform(sets = 0, reps = 5) }
        assertRejects("a uniform prescription of no repetitions") {
            TimePrescription.uniform(sets = 3, seconds = 0)
        }
    }

    @Test
    fun askingForASetThePrescriptionDoesNotHaveIsARefusalNotAValue() {
        val prescription = RepPrescription(listOf(10, 8))

        assertRejects("set 0") { prescription.targetForSet(0) }
        assertRejects("set 3 of 2") { prescription.targetForSet(3) }
    }

    @Test
    fun theTwoUnitChannelsStayDistinctAcrossTheSealedHierarchy() {
        val rep: Prescription = RepPrescription(listOf(10))
        val timed: Prescription = TimePrescription(listOf(30))

        // Both are prescriptions with one target; the dimension is what says in which unit.
        assertEquals(1, rep.setCount)
        assertEquals(1, timed.setCount)
        assertEquals(PrescriptionDimension.REP_BASED, rep.dimension)
        assertEquals(PrescriptionDimension.TIME_BASED, timed.dimension)
        assertTrue(rep is RepPrescription)
        assertTrue(timed is TimePrescription)
    }

    // ------------------------------------------------------------------ the room left for the rest

    @Test
    fun theFiveDimensionsAreNamedAndOnlyTwoAreImplemented() {
        assertEquals(
            listOf("REP_BASED", "TIME_BASED", "SET_BASED", "DIFFICULTY_BASED", "REST_BASED"),
            PrescriptionDimension.entries.map { it.name }
        )

        val implementors = packageDir.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            ?.flatMap { source ->
                Regex("""class (\w+)\([^)]*\)\s*:\s*Prescription\b""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(source.readText())
                    .map { it.groupValues[1] }
            }
            ?.toSet()
            ?: emptySet()

        assertEquals(
            "only the two implemented dimensions may have a Prescription subtype; the reserved " +
                "ones are named and left unimplemented",
            setOf("RepPrescription", "TimePrescription"),
            implementors
        )
    }

    @Test
    fun noAlgorithmForTheReservedDimensionsHasBeenSlippedIn() {
        val mainSources = File("src/main/java").let { dir ->
            if (dir.isDirectory) dir else File("app/$dir")
        }
        val forbiddenTypes = listOf(
            "SetPrescription", "DifficultyPrescription", "RestPrescription",
            "SetBasedPrescription", "DifficultyBasedPrescription", "RestBasedPrescription"
        )

        val found = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                forbiddenTypes.filter { text.contains("class $it") }.map { "${file.name}: $it" }
            }
            .toList()

        assertTrue(
            "the reserved dimensions must not be implemented in this change, found: $found",
            found.isEmpty()
        )
    }

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }
}
