package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * `ProgramRevision` is conceptually immutable, and that is asserted rather than promised.
 *
 * A revision is the historical record of a program's structure: a session started under it must stay
 * explained by it, which only holds if nothing can change it after the fact. Four independent checks
 * are made here, because each one catches a different way of losing that property:
 *
 *  * the type is a data class with value semantics, so "changing" it means producing the next value;
 *  * every declared field is final and no setter exists, so there is no mutable state to write to;
 *  * every property in the primary constructor is declared `val`, so the compiler enforces it at the
 *    source level rather than only in the compiled shape;
 *  * the collection it carries is declared as the read-only interface, not as a mutable collection.
 */
class ProgramRevisionImmutabilityTest {

    private val sourceFile = File("src/main/java/com/monkfitness/app/domain/program/ProgramRevision.kt")
        .let { if (it.isFile) it else File("app/$it") }

    @Test
    fun aRevisionIsADataClassSoChangingItMeansProducingTheNextValue() {
        val methods = ProgramRevision::class.java.declaredMethods.map { it.name }

        assertTrue(
            "a data class exposes copy(); found ${methods.filter { it.startsWith("copy") }}",
            methods.any { it.startsWith("copy") }
        )

        // The component names carry a mangling suffix, because the properties are typed with inline
        // value classes: the JVM signature has to distinguish `ProgramRevision.copy(RevisionId, ...)`
        // from one that takes plain strings, and the compiler does it by decorating the name. That
        // suffix is itself the evidence that a revision stores typed ids unboxed.
        val components = methods.filter { it.startsWith("component") }
            .map { it.substringBefore('-') }
            .sorted()
        assertEquals(
            "a data class exposes one component per property — nine of them, the Goals & Focus " +
                "configuration included (§6, §8)",
            (1..9).map { "component$it" },
            components
        )
        assertTrue(
            "the accessors of typed-id properties are value-class mangled, proving the ids are " +
                "inline: ${methods.filter { it.startsWith("getRevisionId") || it.startsWith("getProgramId") }}",
            methods.any { it.startsWith("getRevisionId") && it.contains('-') } &&
                methods.any { it.startsWith("getProgramId") && it.contains('-') }
        )
    }

    @Test
    fun everyDeclaredFieldIsFinalAndTheTypeHasNoSetters() {
        val fields = ProgramRevision::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }

        assertTrue("a revision declares fields", fields.isNotEmpty())
        val mutable = fields.filterNot { Modifier.isFinal(it.modifiers) }.map { it.name }
        assertTrue("every field of a revision is final, found mutable: $mutable", mutable.isEmpty())

        val setters = ProgramRevision::class.java.declaredMethods
            .map { it.name }
            .filter { it.length > 3 && it.startsWith("set") && it[3].isUpperCase() }
        assertTrue("a revision exposes no setter, found: $setters", setters.isEmpty())
    }

    @Test
    fun everyPropertyInThePrimaryConstructorIsAVal() {
        val source = sourceFile.readText()
        val constructor = Regex("""data class ProgramRevision\((.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.get(1)
        assertTrue(
            "expected the ProgramRevision primary constructor in ${sourceFile.absolutePath}",
            constructor != null
        )

        val parameters = constructor!!.lines()
            .map { it.trim() }
            .filter { it.contains(":") && !it.startsWith("*") && !it.startsWith("/") }
        assertTrue("expected constructor properties, found $parameters", parameters.isNotEmpty())
        val notVals = parameters.filterNot { it.startsWith("val ") }
        assertTrue("every revision property must be a val, found: $notVals", notVals.isEmpty())
    }

    @Test
    fun theRevisionCarriesAReadOnlyCollectionNotAMutableOne() {
        val days = ProgramRevision::class.java.getDeclaredField("days")

        assertEquals(
            "the plan is declared as the read-only list interface, never as a mutable collection",
            java.util.List::class.java,
            days.type
        )
        assertFalse(days.type.isArray)
    }

    @Test
    fun producingTheNextRevisionLeavesTheOriginalUntouched() {
        val original = revision(ProgramMode.MANUAL)
        val regenerated = original.copy(mode = ProgramMode.GENERATED)

        assertEquals(ProgramMode.MANUAL, original.mode)
        assertEquals(ProgramMode.GENERATED, regenerated.mode)
        assertEquals(original, revision(ProgramMode.MANUAL))
        assertNotEquals(original, regenerated)

        // Identity travels with the value: a structural change is a *new* revision, never an edit.
        val nextRevision = original.copy(
            revisionId = RevisionId("rev-2"),
            revisionNumber = 2
        )
        assertEquals(RevisionId("rev-1"), original.revisionId)
        assertEquals(RevisionId("rev-2"), nextRevision.revisionId)
    }

    private fun revision(mode: ProgramMode) = ProgramRevision(
        revisionId = RevisionId("rev-1"),
        programId = ProgramId("program-1"),
        revisionNumber = 1,
        mode = mode,
        duration = ProgramDuration.FixedDays(28),
        schedule = ProgramSchedule.FlexiblePerWeek(3),
        days = listOf(
            ProgramDay(
                programDayId = ProgramDayId("day-1"),
                position = 1,
                type = ProgramDayType.TRAINING,
                name = "Day 1",
                exercises = listOf(
                    ProgramExercise(
                        programExerciseId = ProgramExerciseId("element-1"),
                        exerciseId = "pushups",
                        prescription = com.monkfitness.app.domain.prescription.RepPrescription(
                            listOf(12, 10, 8, 6)
                        ),
                        origin = ProgramExerciseOrigin.GENERATED
                    )
                )
            )
        ),
        createdAt = java.time.Instant.parse("2026-09-18T09:00:00Z")
    )
}
