package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.OccurrenceComposer
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.ScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.lang.reflect.Modifier

class TargetOccurrenceComposerTest {
    @Test
    fun noSelectionLeavesEveryResolvedOccurrenceSeparate() {
        val occurrences = listOf(
            resolved("mobility", "Mobility", DAY_TWO),
            resolved("strength", "Strength", DAY_ONE),
            resolved("posture", "Posture", DAY_ONE)
        )

        val result = TargetOccurrenceComposer.compose(occurrences, CompositionSelection())

        assertEquals(
            listOf("posture", "strength", "mobility"),
            result.flatMap { it.components }.map { it.ruleId }
        )
        assertTrue(result.all { it.components.size == 1 })
    }

    @Test
    fun twoSelectedRulesOnOneDateBecomeOneCombinedOccurrence() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("mobility", "Mobility", DAY_ONE),
                resolved("posture", "Posture", DAY_ONE)
            ),
            selection("mobility", "posture")
        )

        assertEquals(1, result.size)
        assertEquals(listOf("mobility", "posture"), result.single().components.map { it.ruleId })
        assertEquals(listOf("Mobility", "Posture"), result.single().components.map { it.workoutId })
    }

    @Test
    fun selectedAndUnselectedWorkOnOneDateProduceCombinedAndSeparateOccurrences() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("strength", "Strength", DAY_ONE),
                resolved("mobility", "Mobility", DAY_ONE),
                resolved("posture", "Posture", DAY_ONE)
            ),
            selection("mobility", "posture")
        )

        assertEquals(2, result.size)
        assertEquals(listOf("strength"), result[0].components.map { it.ruleId })
        assertEquals(listOf("mobility", "posture"), result[1].components.map { it.ruleId })
    }

    @Test
    fun threeSelectedRulesOnOneDateBecomeOneAuditableOccurrence() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("c", "Workout C", DAY_ONE),
                resolved("a", "Workout A", DAY_ONE),
                resolved("b", "Workout B", DAY_ONE)
            ),
            selection("c", "a", "b")
        )

        assertEquals(1, result.size)
        assertEquals(
            listOf(
                "a" to "Workout A",
                "b" to "Workout B",
                "c" to "Workout C"
            ),
            result.single().components.map { it.ruleId to it.workoutId }
        )
    }

    @Test
    fun selectedRuleWithNoOccurrenceOnADateContributesNothingThere() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("strength", "Strength", DAY_ONE),
                resolved("mobility", "Mobility", DAY_ONE),
                resolved("strength", "Strength", DAY_TWO),
                resolved("mobility", "Mobility", DAY_TWO)
            ),
            selection("mobility", "posture")
        )

        assertEquals(
            listOf(
                listOf("strength"),
                listOf("mobility"),
                listOf("strength"),
                listOf("mobility")
            ),
            result.map { occurrence -> occurrence.components.map { it.ruleId } }
        )
        assertEquals(
            listOf(
                "strength:2026-10-05",
                "combined:2026-10-05:8:mobility",
                "strength:2026-10-06",
                "combined:2026-10-06:8:mobility"
            ),
            result.map { it.occurrenceKey }
        )
        assertTrue(result.flatMap { it.components }.none { it.ruleId == "posture" })
    }

    @Test
    fun compositionNeverCrossesDateBoundaries() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("a", "Workout A", DAY_TWO),
                resolved("b", "Workout B", DAY_ONE),
                resolved("a", "Workout A", DAY_ONE),
                resolved("b", "Workout B", DAY_TWO)
            ),
            selection("a", "b")
        )

        assertEquals(
            listOf(
                "combined:2026-10-05:1:a|1:b",
                "combined:2026-10-06:1:a|1:b"
            ),
            result.map { it.occurrenceKey }
        )
        assertEquals(listOf(DAY_ONE, DAY_TWO), result.map { it.plannedFor })
    }

    @Test
    fun strongSemanticExampleIsIndependentOfEveryInputPermutation() {
        val occurrences = listOf(
            resolved("a", "Workout A", DAY_ONE),
            resolved("b", "Workout B", DAY_ONE),
            resolved("c", "Workout C", DAY_ONE),
            resolved("b", "Workout B", DAY_TWO),
            resolved("a", "Workout A", DAY_TWO)
        )
        val selection = selection("b", "c")
        val expected = listOf(
            listOf("a"),
            listOf("b", "c"),
            listOf("a"),
            listOf("b")
        )

        val permutations = listOf(
            occurrences,
            listOf(occurrences[2], occurrences[0], occurrences[1], occurrences[3], occurrences[4]),
            occurrences.reversed(),
            listOf(occurrences[4], occurrences[1], occurrences[3], occurrences[0], occurrences[2])
        )

        val results = permutations.map { TargetOccurrenceComposer.compose(it, selection) }
        results.forEach { result ->
            assertEquals(expected, result.map { occurrence -> occurrence.components.map { it.ruleId } })
            assertEquals(listOf(DAY_ONE, DAY_ONE, DAY_TWO, DAY_TWO), result.map { it.plannedFor })
        }
        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun differentSetInsertionOrdersProduceIdenticalOutput() {
        val occurrences = listOf(
            resolved("a", "Workout A", DAY_ONE),
            resolved("b", "Workout B", DAY_ONE),
            resolved("c", "Workout C", DAY_ONE)
        )
        val forward = TargetOccurrenceComposer.compose(
            occurrences,
            CompositionSelection(linkedSetOf("a", "b", "c"))
        )
        val reverse = TargetOccurrenceComposer.compose(
            occurrences.reversed(),
            CompositionSelection(linkedSetOf("c", "b", "a"))
        )

        assertEquals(forward, reverse)
    }

    @Test
    fun repeatedExecutionIsEqualityIdentical() {
        val occurrences = listOf(
            resolved("b", "Workout B", DAY_ONE),
            resolved("a", "Workout A", DAY_TWO),
            resolved("c", "Workout C", DAY_ONE)
        )
        val selection = selection("b", "c")

        assertEquals(
            TargetOccurrenceComposer.compose(occurrences, selection),
            TargetOccurrenceComposer.compose(occurrences, selection)
        )
    }

    @Test
    fun componentAndSeparateOrderingAreCanonical() {
        val combined = TargetOccurrenceComposer.compose(
            listOf(
                resolved("z", "Workout Z", DAY_ONE),
                resolved("a", "Workout A", DAY_ONE),
                resolved("m", "Workout M", DAY_ONE)
            ),
            selection("z", "a", "m")
        ).single()

        val separate = TargetOccurrenceComposer.compose(
            listOf(
                resolved("z", "Workout Z", DAY_ONE),
                resolved("a", "Workout A", DAY_ONE),
                resolved("m", "Workout M", DAY_ONE)
            ),
            CompositionSelection()
        )

        assertEquals(listOf("a", "m", "z"), combined.components.map { it.ruleId })
        assertEquals(listOf(DAY_ONE, DAY_ONE, DAY_ONE), separate.map { it.plannedFor })
        assertEquals(listOf("a", "m", "z"), separate.map { it.components.single().ruleId })
    }

    @Test
    fun occurrenceKeysDependOnDateAndMembershipOnly() {
        val first = TargetOccurrenceComposer.compose(
            listOf(
                resolved("a", "Workout A", DAY_ONE),
                resolved("b", "Workout B", DAY_ONE)
            ),
            selection("a", "b")
        ).single()
        val reversed = TargetOccurrenceComposer.compose(
            listOf(
                resolved("b", "Workout B", DAY_ONE),
                resolved("a", "Workout A", DAY_ONE)
            ),
            selection("b", "a")
        ).single()
        val differentMembership = TargetOccurrenceComposer.compose(
            listOf(
                resolved("a", "Workout A", DAY_ONE),
                resolved("c", "Workout C", DAY_ONE)
            ),
            selection("a", "c")
        ).single()
        val differentDate = TargetOccurrenceComposer.compose(
            listOf(
                resolved("a", "Workout A", DAY_TWO),
                resolved("b", "Workout B", DAY_TWO)
            ),
            selection("a", "b")
        ).single()

        assertEquals(first.occurrenceKey, reversed.occurrenceKey)
        assertNotEquals(first.occurrenceKey, differentMembership.occurrenceKey)
        assertNotEquals(first.occurrenceKey, differentDate.occurrenceKey)
    }

    @Test
    fun duplicateRuleAndDateSourceOccurrenceIsRejectedRatherThanDeduplicated() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceComposer.compose(
                listOf(
                    resolved("strength", "Strength", DAY_ONE),
                    resolved("strength", "Strength", DAY_ONE)
                ),
                CompositionSelection()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceComposer.compose(
                listOf(
                    resolved("strength", "Strength", DAY_ONE),
                    resolved("strength", "Changed workout", DAY_ONE)
                ),
                CompositionSelection()
            )
        }
    }

    @Test
    fun theSameRuleMayResolveOnceOnDifferentDates() {
        val result = TargetOccurrenceComposer.compose(
            listOf(
                resolved("strength", "Strength", DAY_ONE),
                resolved("strength", "Strength", DAY_TWO)
            ),
            selection("strength")
        )

        assertEquals(listOf(DAY_ONE, DAY_TWO), result.map { it.plannedFor })
    }

    @Test
    fun blankSourceAndSelectionIdentitiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceComposer.compose(
                listOf(resolved(" ", "Workout", DAY_ONE)),
                CompositionSelection()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetOccurrenceComposer.compose(
                listOf(resolved("rule", " ", DAY_ONE)),
                CompositionSelection()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompositionSelection(setOf("rule", " "))
        }
    }

    @Test
    fun emptyInputReturnsEmptyAndNoEmittedOccurrenceHasNoComponents() {
        assertTrue(TargetOccurrenceComposer.compose(emptyList(), selection("missing")).isEmpty())
        val result = TargetOccurrenceComposer.compose(
            listOf(resolved("rule", "Workout", DAY_ONE)),
            selection("rule")
        )

        assertTrue(result.all { it.components.isNotEmpty() })
    }

    @Test
    fun compositionUsesOnlySuppliedResolvedDatesAndNeverCreatesOne() {
        val occurrences = listOf(
            resolved("a", "Workout A", DAY_ONE),
            resolved("b", "Workout B", DAY_TWO)
        )

        val result = TargetOccurrenceComposer.compose(occurrences, selection("a", "b"))

        assertEquals(occurrences.map { it.plannedDate }.distinct(), result.map { it.plannedFor })
        assertTrue(result.all { occurrence -> occurrences.any { it.plannedDate == occurrence.plannedFor } })
        assertEquals(2, result.flatMap { it.components }.size)
    }

    @Test
    fun plannedOccurrenceResultContainsOnlyPureIdentityDateAndComponentValues() {
        val result = TargetOccurrenceComposer.compose(
            listOf(resolved("a", "Workout A", DAY_ONE)),
            selection("a")
        ).single()
        val fieldTypes = result::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.type.name }
            .toSet()

        assertEquals(setOf("java.lang.String", "java.time.LocalDate", "java.util.List"), fieldTypes)
    }

    @Test
    fun stageOneComposerAndTargetComposerShareTheDefinedSemantics() {
        val rules = listOf(
            ScheduleRule.daily("a", "Workout A"),
            ScheduleRule.daily("mobility", "Mobility"),
            ScheduleRule.daily("posture", "Posture")
        )
        val resolved = rules.map { rule ->
            ResolvedScheduleOccurrence(rule.ruleId, rule.workoutId, DAY_ONE, rule.cadence)
        }.reversed()
        val selection = selection("mobility", "posture")

        assertEquals(
            TargetOccurrenceComposer.compose(resolved, selection).map { it.copy(occurrenceKey = "parity") },
            OccurrenceComposer.compose(DAY_ONE, rules, selection).map { it.copy(occurrenceKey = "parity") }
        )
        assertEquals(
            TargetOccurrenceComposer.compose(resolved, CompositionSelection())
                .map { it.copy(occurrenceKey = "parity") },
            OccurrenceComposer.compose(DAY_ONE, rules, CompositionSelection())
                .map { it.copy(occurrenceKey = "parity") }
        )
    }

    private fun resolved(ruleId: String, workoutId: String, date: LocalDate) =
        ResolvedScheduleOccurrence(ruleId, workoutId, date, ScheduleCadence.Daily)

    private fun selection(vararg ids: String) = CompositionSelection(ids.toSet())

    private companion object {
        val DAY_ONE: LocalDate = LocalDate.parse("2026-10-05")
        val DAY_TWO: LocalDate = LocalDate.parse("2026-10-06")
    }
}
