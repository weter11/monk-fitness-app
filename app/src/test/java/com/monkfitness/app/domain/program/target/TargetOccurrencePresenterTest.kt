package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TargetOccurrencePresenterTest {
    @Test
    fun oneOccurrenceAndOneComponentPresentTheExplicitProgramDay() {
        val occurrence = occurrence(DAY_ONE, "strength:2026-10-05", component("strength"))
        val programDayId = day("program-day-7")

        val result = present(listOf(occurrence), binding("strength", programDayId))

        assertEquals(listOf(TargetOccurrencePresentation(occurrence, programDayId)), result)
    }

    @Test
    fun multipleOccurrencesEachCarryTheirOwnExplicitProgramDay() {
        val first = occurrence(DAY_ONE, "strength:2026-10-05", component("strength"))
        val second = occurrence(DAY_TWO, "mobility:2026-10-06", component("mobility"))

        val result = present(
            listOf(first, second),
            binding("strength", day("day-strength")),
            binding("mobility", day("day-mobility"))
        )

        assertEquals(
            listOf(
                TargetOccurrencePresentation(first, day("day-strength")),
                TargetOccurrencePresentation(second, day("day-mobility"))
            ),
            result
        )
    }

    @Test
    fun multipleComponentsBoundToOneProgramDayKeepTheCompleteOccurrence() {
        val occurrence = occurrence(
            DAY_ONE,
            "combined:2026-10-05",
            component("strength"),
            component("mobility")
        )

        val result = present(
            listOf(occurrence),
            binding("strength", day("shared-day")),
            binding("mobility", day("shared-day"))
        )

        assertEquals(1, result.size)
        assertEquals(
            occurrence.copy(
                components = canonicalComponents(occurrence.components)
            ),
            result.single().occurrence
        )
        assertEquals(day("shared-day"), result.single().programDayId)
    }

    @Test
    fun componentsBoundToDifferentProgramDaysAreExplicitlyRejected() {
        val occurrence = occurrence(
            DAY_ONE,
            "combined:2026-10-05",
            component("strength", "strength-workout"),
            component("mobility", "mobility-workout")
        )

        val failure = assertThrows(TargetOccurrencePresentationException.MultiDayProgramOccurrence::class.java) {
            present(
                listOf(occurrence),
                binding("strength-workout", day("strength-day")),
                binding("mobility-workout", day("mobility-day"))
            )
        }

        assertEquals(occurrence.occurrenceKey, failure.occurrenceKey)
        assertEquals(setOf(day("strength-day"), day("mobility-day")), failure.programDayIds)
    }

    @Test
    fun multiDayRejectionDoesNotChooseTheFirstComponentProgramDay() {
        val occurrence = occurrence(
            DAY_ONE,
            "combined:first-must-not-win",
            component("first", "first-workout"),
            component("second", "second-workout")
        )

        assertThrows(TargetOccurrencePresentationException.MultiDayProgramOccurrence::class.java) {
            present(
                listOf(occurrence),
                binding("first-workout", day("first-day")),
                binding("second-workout", day("second-day"))
            )
        }
    }

    @Test
    fun multiDayRejectionDoesNotChooseTheLastComponentProgramDay() {
        val occurrence = occurrence(
            DAY_ONE,
            "combined:last-must-not-win",
            component("first", "first-workout"),
            component("second", "second-workout")
        )

        assertThrows(TargetOccurrencePresentationException.MultiDayProgramOccurrence::class.java) {
            present(
                listOf(occurrence),
                binding("first-workout", day("first-day")),
                binding("second-workout", day("last-day"))
            )
        }
    }

    @Test
    fun missingBindingIsRejectedWithTheOccurrenceAndWorkoutIdentity() {
        val occurrence = occurrence(DAY_ONE, "strength:2026-10-05", component("strength"))

        val failure = assertThrows(TargetOccurrencePresentationException.MissingProgramDayBinding::class.java) {
            present(listOf(occurrence))
        }

        assertEquals(occurrence.occurrenceKey, failure.occurrenceKey)
        assertEquals("strength", failure.workoutId)
    }

    @Test
    fun sameWorkoutCannotBindToTwoDifferentProgramDays() {
        val failure = assertThrows(TargetOccurrencePresentationException.ConflictingProgramDayBinding::class.java) {
            present(
                occurrences = emptyList(),
                bindings = listOf(
                    binding("strength", day("day-b")),
                    binding("strength", day("day-a"))
                )
            )
        }

        assertEquals("strength", failure.workoutId)
        assertEquals(setOf(day("day-a"), day("day-b")), failure.programDayIds)
    }

    @Test
    fun duplicateEquivalentBindingIsRejectedRatherThanSilentlyDeduplicated() {
        assertThrows(TargetOccurrencePresentationException.DuplicateProgramDayBinding::class.java) {
            present(
                occurrences = emptyList(),
                bindings = listOf(
                    binding("strength", day("same-day")),
                    binding("strength", day("same-day"))
                )
            )
        }
    }

    @Test
    fun blankBindingWorkoutIdentityIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            binding("  ", day("day"))
        }
    }

    @Test
    fun blankProgramDayIdentityIsRejectedByItsTypedValue() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgramDayId("  ")
        }
    }

    @Test
    fun blankOccurrenceOrComponentIdentityIsRejected() {
        assertThrows(TargetOccurrencePresentationException.InvalidTargetOccurrenceIdentity::class.java) {
            present(listOf(occurrence(DAY_ONE, "  ", component("strength"))), binding("strength", day("day")))
        }
        assertThrows(TargetOccurrencePresentationException.InvalidTargetOccurrenceIdentity::class.java) {
            present(listOf(occurrence(DAY_ONE, "key", component(" ", "strength"))), binding("strength", day("day")))
        }
    }

    @Test
    fun reorderedBindingsProduceIdenticalOutput() {
        val occurrences = listOf(
            occurrence(DAY_ONE, "a:2026-10-05", component("a")),
            occurrence(DAY_TWO, "b:2026-10-06", component("b"))
        )
        val forward = present(
            occurrences,
            binding("a", day("day-a")),
            binding("b", day("day-b"))
        )
        val reversed = present(
            occurrences,
            binding("b", day("day-b")),
            binding("a", day("day-a"))
        )

        assertEquals(forward, reversed)
    }

    @Test
    fun reorderedOccurrenceInputProducesCanonicalOutput() {
        val first = occurrence(DAY_ONE, "z-key", component("z"))
        val second = occurrence(DAY_ONE, "a-key", component("a"))
        val bindings = listOf(binding("a", day("day-a")), binding("z", day("day-z")))

        val forward = present(listOf(first, second), bindings)
        val reversed = present(listOf(second, first), bindings)

        assertEquals(listOf("a-key", "z-key"), forward.map { it.occurrence.occurrenceKey })
        assertEquals(forward, reversed)
    }

    @Test
    fun reorderedComponentsDoNotChangeTheResolvedPresentationIdentity() {
        val components = listOf(component("first"), component("second"))
        val forwardOccurrence = occurrence(DAY_ONE, "combined:key", *components.toTypedArray())
        val reversedOccurrence = forwardOccurrence.copy(components = components.reversed())
        val bindings = listOf(
            binding("first", day("shared-day")),
            binding("second", day("shared-day"))
        )

        val forward = present(listOf(forwardOccurrence), bindings)
        val reversed = present(listOf(reversedOccurrence), bindings)

        assertEquals(forward.single().programDayId, reversed.single().programDayId)
        assertEquals(
            forwardOccurrence.copy(components = canonicalComponents(components)),
            forward.single().occurrence
        )
        assertEquals(
            reversedOccurrence.copy(components = canonicalComponents(components)),
            reversed.single().occurrence
        )
    }

    @Test
    fun outputIsSortedByPlannedDateThenOccurrenceKey() {
        val input = listOf(
            occurrence(DAY_TWO, "z2", component("z")),
            occurrence(DAY_ONE, "z1", component("z")),
            occurrence(DAY_ONE, "a", component("a")),
            occurrence(DAY_ONE, "m", component("m"))
        )

        val result = present(
            input,
            binding("a", day("day-a")),
            binding("m", day("day-m")),
            binding("z", day("day-z"))
        )

        assertEquals(
            listOf(DAY_ONE to "a", DAY_ONE to "m", DAY_ONE to "z1", DAY_TWO to "z2"),
            result.map { it.occurrence.plannedFor to it.occurrence.occurrenceKey }
        )
    }

    @Test
    fun originalOccurrenceIsRetainedUnchangedAndUnmutated() {
        val original = occurrence(
            DAY_ONE,
            "combined:original",
            component("first"),
            component("second")
        )
        val snapshot = original.copy(components = original.components.toList())
        val occurrences = mutableListOf(original)
        val bindings = mutableListOf(
            binding("first", day("shared-day")),
            binding("second", day("shared-day"))
        )

        val result = present(occurrences, bindings)

        assertEquals(original, result.single().occurrence)
        assertEquals(snapshot, original)
        assertEquals(listOf(original), occurrences)
        assertEquals(2, bindings.size)
    }

    @Test
    fun occurrenceKeyAndPlannedDateAreNeverRewritten() {
        val original = occurrence(DAY_TWO, "opaque:target:key", component("strength"))
        val keySnapshot = original.occurrenceKey
        val dateSnapshot = original.plannedFor

        val result = present(listOf(original), binding("strength", day("day")))

        assertEquals(keySnapshot, result.single().occurrence.occurrenceKey)
        assertEquals(dateSnapshot, result.single().occurrence.plannedFor)
        assertNotEquals(result.single().programDayId.value, result.single().occurrence.occurrenceKey)
    }

    @Test
    fun emptyOccurrencesReturnEmptyEvenWithNoBindings() {
        assertEquals(emptyList<TargetOccurrencePresentation>(), present(emptyList()))
    }

    @Test
    fun repeatedExecutionIsEqualityIdentical() {
        val occurrence = occurrence(
            DAY_ONE,
            "combined:key",
            component("first"),
            component("second")
        )
        val bindings = listOf(
            binding("second", day("shared-day")),
            binding("first", day("shared-day"))
        )

        assertEquals(present(listOf(occurrence), bindings), present(listOf(occurrence), bindings))
    }

    @Test
    fun everySuccessfulOutputHasExactlyOneTypedProgramDayIdentity() {
        val occurrence = occurrence(DAY_ONE, "key", component("strength"))
        val result = present(
            listOf(occurrence),
            binding("strength", day("typed-day"))
        )

        assertEquals(
            listOf(
                TargetOccurrencePresentation(occurrence, day("typed-day"))
            ),
            result
        )
        assertTrue(result.all { it.programDayId.value.isNotBlank() })
    }

    @Test
    fun duplicateTargetOccurrenceIdentityIsRejected() {
        val occurrence = occurrence(DAY_ONE, "same-key", component("strength"))

        assertThrows(TargetOccurrencePresentationException.DuplicateTargetOccurrenceIdentity::class.java) {
            present(listOf(occurrence, occurrence), binding("strength", day("day")))
        }
    }

    private fun present(
        occurrences: List<PlannedOccurrence>,
        vararg bindings: TargetProgramDayBinding
    ) = TargetOccurrencePresenter.present(occurrences, bindings.toList())

    private fun present(
        occurrences: List<PlannedOccurrence>,
        bindings: List<TargetProgramDayBinding>
    ) = TargetOccurrencePresenter.present(occurrences, bindings)

    private fun canonicalComponents(components: List<OccurrenceComponent>): List<OccurrenceComponent> =
        components.sortedWith(
            compareBy<OccurrenceComponent> { it.ruleId }.thenBy { it.workoutId }
        )

    private fun binding(workoutId: String, programDayId: ProgramDayId) =
        TargetProgramDayBinding(workoutId, programDayId)

    private fun day(value: String) = ProgramDayId(value)

    private fun component(ruleId: String, workoutId: String = ruleId) =
        OccurrenceComponent(ruleId, workoutId)

    private fun occurrence(
        plannedFor: LocalDate,
        key: String,
        vararg components: OccurrenceComponent
    ) = PlannedOccurrence(key, plannedFor, components.toList())

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.parse("2026-10-05")
        val DAY_TWO: LocalDate = LocalDate.parse("2026-10-06")
    }
}
