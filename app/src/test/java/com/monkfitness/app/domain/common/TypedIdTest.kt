package com.monkfitness.app.domain.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The identity vocabulary, pinned as a contract rather than as a convention.
 *
 * The invariant worth having a test for is **non-interchangeability**: every id in the architecture is
 * its own type, so a revision id cannot be passed where a slot id is expected, even though both are
 * strings underneath. That is asserted here over every pair of id types by reflection rather than by
 * reading the source, and the *set* of id types is asserted against the source so that adding a
 * twelfth id without deciding what it identifies fails the suite.
 */
class TypedIdTest {

    private val ids: List<Pair<String, (String) -> Any>> = listOf(
        "ProgramId" to { value: String -> ProgramId(value) },
        "RevisionId" to { value: String -> RevisionId(value) },
        "ProgramDayId" to { value: String -> ProgramDayId(value) },
        "ProgramExerciseId" to { value: String -> ProgramExerciseId(value) },
        "SlotId" to { value: String -> SlotId(value) },
        "SessionId" to { value: String -> SessionId(value) },
        "SessionExerciseId" to { value: String -> SessionExerciseId(value) },
        "SetLogId" to { value: String -> SetLogId(value) },
        "PauseId" to { value: String -> PauseId(value) },
        "AdjustmentId" to { value: String -> AdjustmentId(value) },
        "DecisionId" to { value: String -> DecisionId(value) }
    )

    private val commonDir = File("src/main/java/com/monkfitness/app/domain/common").let { dir ->
        // Unit-test JVM cwd is app/, but fall back to the repo root layout for safety.
        if (dir.isDirectory) dir else File("app/$dir")
    }

    // ------------------------------------------------------------------ identity is not a string

    @Test
    fun everyIdIsItsOwnTypeAndNoneIsAssignableToAnother() {
        assertTrue(
            "expected the id vocabulary to exist at ${commonDir.absolutePath}",
            commonDir.isDirectory
        )

        val interchangeable = mutableListOf<String>()
        for ((nameA, _) in ids) {
            for ((nameB, _) in ids) {
                if (nameA == nameB) continue
                val a = Class.forName("com.monkfitness.app.domain.common.$nameA")
                val b = Class.forName("com.monkfitness.app.domain.common.$nameB")
                if (a.isAssignableFrom(b)) interchangeable += "$nameB is a $nameA"
            }
        }

        assertTrue(
            "typed ids must not be interchangeable, found: $interchangeable",
            interchangeable.isEmpty()
        )
    }

    @Test
    fun twoIdsOfDifferentTypesAreNeverEqualEvenForTheSameText() {
        assertNotEquals(ProgramId("same-text") as Any, RevisionId("same-text") as Any)
        assertNotEquals(SessionId("same-text") as Any, SlotId("same-text") as Any)
        assertNotEquals(DecisionId("same-text") as Any, AdjustmentId("same-text") as Any)
    }

    @Test
    fun eachIdCarriesItsOwnValueAndComparesByValue() {
        assertEquals("program-1", ProgramId("program-1").value)
        assertEquals("revision-1", RevisionId("revision-1").value)
        assertEquals("day-1", ProgramDayId("day-1").value)
        assertEquals("element-1", ProgramExerciseId("element-1").value)
        assertEquals("slot-1", SlotId("slot-1").value)
        assertEquals("session-1", SessionId("session-1").value)
        assertEquals("occurrence-1", SessionExerciseId("occurrence-1").value)
        assertEquals("set-1", SetLogId("set-1").value)
        assertEquals("pause-1", PauseId("pause-1").value)
        assertEquals("adjustment-1", AdjustmentId("adjustment-1").value)
        assertEquals("decision-1", DecisionId("decision-1").value)

        // Value semantics: equal text is the same id, and a set of ids de-duplicates by value.
        assertEquals(ProgramId("x"), ProgramId("x"))
        assertEquals(setOf(ProgramId("x"), ProgramId("x")), setOf(ProgramId("x")))
    }

    @Test
    fun noIdCanBeBlank() {
        ids.forEach { (name, create) ->
            try {
                create("   ")
                fail("$name accepted a blank id")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "$name must explain why a blank id is rejected",
                    expected.message?.isNotBlank() == true
                )
            }
        }
    }

    @Test
    fun everyIdIsAnInlineValueClassSoTheTypeSafetyIsFree() {
        val notValueClasses = ids.map { (name, _) ->
            name to Class.forName("com.monkfitness.app.domain.common.$name")
        }.filterNot { (_, cls) ->
            cls.declaredMethods.any { it.name == "box-impl" || it.name == "constructor-impl" }
        }.map { it.first }

        assertTrue(
            "every typed id must be an inline value class (wrapping a string at no runtime cost), " +
                "found plain classes: $notValueClasses",
            notValueClasses.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the set is exactly the set

    @Test
    fun theIdVocabularyIsExactlyTheTypesTheArchitectureNames() {
        val declared = commonDir.listFiles { file -> file.isFile && file.extension == "kt" }
            ?.sortedBy { it.name }
            ?.flatMap { source ->
                Regex("""value class (\w+)""").findAll(source.readText()).map { it.groupValues[1] }
            }
            ?.toSet()
            ?: emptySet()

        assertEquals(
            "the identity vocabulary changed; every id must be one the architecture names",
            ids.map { it.first }.toSet(),
            declared
        )
    }
}
