package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ProgramConfiguration` model contract: what a default and a custom configuration are, what
 * "the same effective selection" means, and which proposed selections are a no-op.
 *
 * What they pin:
 *
 *  * `DEFAULT` and `CUSTOM` are the whole vocabulary of [ProgramConfigurationSource], stored as stable
 *    names rather than ordinals;
 *  * two configurations with the same enabled ids are one effective selection regardless of the order
 *    the ids arrived in, and a source-only difference is not an effective difference;
 *  * a proposed selection identical to the current one is a no-op — the current source and the current
 *    version come back untouched;
 *  * any real change — including one that lands exactly on the authoritative default set — becomes
 *    `DEFAULT` when it equals the default set and `CUSTOM` otherwise, and advances the version by
 *    exactly one;
 *  * an empty selection disables everything; the model never falls back to the defaults on its own,
 *    because silently re-enabling a disabled exercise is the one thing this layer must not do;
 *  * reset-to-default normalizes the source to `DEFAULT`, advances the version only when the effective
 *    selection actually changed, and never rewinds the counter.
 *
 * The ids used as fixtures are real ids of the shipped exercise library; the repository suite asserts
 * them against the catalogue itself.
 */
class ProgramConfigurationTest {

    private val defaults = setOf("pushups", "pushups_wide", "plank", "squats", "cat_cow", "dead_bug", "pullups", "rows")
    private val subset = setOf("pushups", "plank", "squats")
    private val otherSubset = setOf("cat_cow", "dead_bug", "bird_dog")

    // ---- construction -------------------------------------------------------------------------

    @Test
    fun theSourceVocabularyIsTheTwoStableTokens() {
        assertEquals(
            listOf("DEFAULT", "CUSTOM"),
            ProgramConfigurationSource.entries.map { it.name }
        )
    }

    @Test
    fun defaultConfigurationIsTheDefaultSourceAtTheInitialVersion() {
        val configuration = ProgramConfiguration.default(defaults)

        assertEquals(ProgramConfigurationSource.DEFAULT, configuration.source)
        assertEquals(defaults, configuration.enabledExerciseIds)
        assertEquals(ProgramConfiguration.INITIAL_VERSION, configuration.configurationVersion)
        assertTrue(configuration.isDefault)
    }

    @Test
    fun customConfigurationIsTheCustomSource() {
        val configuration = ProgramConfiguration.custom(subset, configurationVersion = 3)

        assertEquals(ProgramConfigurationSource.CUSTOM, configuration.source)
        assertEquals(subset, configuration.enabledExerciseIds)
        assertEquals(3, configuration.configurationVersion)
        assertFalse(configuration.isDefault)
    }

    @Test
    fun anEmptySelectionIsStructurallyRepresentableAndIsNotTheDefaultSet() {
        // Whether an empty selection is *allowed* is the validator's decision (Task 10); the model
        // only has to be able to represent it without inventing exercises.
        val configuration = ProgramConfiguration.custom(emptySet(), configurationVersion = 1)

        assertEquals(emptySet<String>(), configuration.enabledExerciseIds)
        assertNotEquals(ProgramConfigurationSource.DEFAULT, configuration.source)
    }

    @Test
    fun aConfigurationVersionCannotPrecedeTheInitialVersion() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProgramConfiguration.custom(subset, configurationVersion = -1)
        }

        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("-1"))
    }

    @Test
    fun exerciseIdsMustBeIdentifiable() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProgramConfiguration.custom(setOf("pushups", "  "), configurationVersion = 1)
        }

        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("blank"))
    }

    // ---- effective selection / equality -------------------------------------------------------

    @Test
    fun equalSelectionsAreTheSameEffectiveSelectionRegardlessOfOrder() {
        val first = ProgramConfiguration.custom(setOf("pushups", "plank", "squats"), configurationVersion = 1)
        val second = ProgramConfiguration.custom(setOf("squats", "pushups", "plank"), configurationVersion = 1)

        assertEquals(first, second)
        assertTrue(first.hasSameEffectiveSelectionAs(second))
    }

    @Test
    fun aDifferentVersionWithTheSameSelectionIsStillTheSameEffectiveSelection() {
        val first = ProgramConfiguration.custom(subset, configurationVersion = 1)
        val second = ProgramConfiguration.custom(subset, configurationVersion = 9)

        assertTrue(first.hasSameEffectiveSelectionAs(second))
        assertNotEquals(first, second)
    }

    @Test
    fun aSourceOnlyDifferenceIsNotAnEffectiveDifference() {
        val asDefault = ProgramConfiguration.default(defaults, configurationVersion = 4)
        val asCustom = ProgramConfiguration.custom(defaults, configurationVersion = 4)

        assertTrue(asDefault.hasSameEffectiveSelectionAs(asCustom))
        assertNotEquals(asDefault, asCustom)
    }

    @Test
    fun aDifferentSelectionIsNotANoOp() {
        val current = ProgramConfiguration.default(defaults)

        assertFalse(current.isNoOpFor(subset))
        assertFalse(current.applying(subset, defaults).hasSameEffectiveSelectionAs(current))
    }

    @Test
    fun anIdenticalSelectionIsANoOp() {
        val current = ProgramConfiguration.custom(subset, configurationVersion = 5)

        assertTrue(current.isNoOpFor(subset))
        assertTrue(current.isNoOpFor(setOf("squats", "plank", "pushups")))
    }

    // ---- applying a proposed selection --------------------------------------------------------

    @Test
    fun applyingAnIdenticalSelectionReturnsTheCurrentConfigurationUntouched() {
        val current = ProgramConfiguration.custom(subset, configurationVersion = 5)

        val applied = current.applying(subset, defaults)

        assertSame(current, applied)
        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(5, applied.configurationVersion)
    }

    @Test
    fun applyingANewSelectionBecomesCustomWithTheNextVersion() {
        val applied = ProgramConfiguration.default(defaults).applying(subset, defaults)

        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(subset, applied.enabledExerciseIds)
        assertEquals(ProgramConfiguration.INITIAL_VERSION + 1, applied.configurationVersion)
    }

    @Test
    fun applyingTheAuthoritativeDefaultSetIsDefaultRatherThanCustom() {
        val current = ProgramConfiguration.custom(subset, configurationVersion = 7)

        val applied = current.applying(defaults, defaults)

        assertEquals(ProgramConfigurationSource.DEFAULT, applied.source)
        assertEquals(defaults, applied.enabledExerciseIds)
        assertEquals(8, applied.configurationVersion)
    }

    @Test
    fun applyingADifferentSelectionFromAnAlreadyCustomConfigurationStaysCustom() {
        val current = ProgramConfiguration.custom(subset, configurationVersion = 2)

        val applied = current.applying(otherSubset, defaults)

        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(otherSubset, applied.enabledExerciseIds)
        assertEquals(3, applied.configurationVersion)
    }

    @Test
    fun applyingAnEmptySelectionDisablesEverythingRatherThanReEnablingTheDefaults() {
        val applied = ProgramConfiguration.default(defaults).applying(emptySet(), defaults)

        assertEquals(ProgramConfigurationSource.CUSTOM, applied.source)
        assertEquals(emptySet<String>(), applied.enabledExerciseIds)
        assertEquals(1, applied.configurationVersion)
    }

    @Test
    fun everyRealChangeAdvancesTheVersionByExactlyOne() {
        var configuration = ProgramConfiguration.default(defaults)
        val versions = mutableListOf<Int>()

        listOf(subset, otherSubset, defaults, subset).forEach { proposed ->
            configuration = configuration.applying(proposed, defaults)
            versions += configuration.configurationVersion
        }

        assertEquals(listOf(1, 2, 3, 4), versions)
    }

    // ---- reset to default ---------------------------------------------------------------------

    @Test
    fun resetToDefaultFromCustomIsDefaultWithTheNextVersion() {
        val reset = ProgramConfiguration.custom(subset, configurationVersion = 6).resetToDefault(defaults)

        assertEquals(ProgramConfigurationSource.DEFAULT, reset.source)
        assertEquals(defaults, reset.enabledExerciseIds)
        assertEquals(7, reset.configurationVersion)
    }

    @Test
    fun repeatedResetToDefaultIsANoOp() {
        val current = ProgramConfiguration.default(defaults, configurationVersion = 7)

        val reset = current.resetToDefault(defaults)

        assertSame(current, reset)
        assertEquals(7, reset.configurationVersion)
    }

    @Test
    fun resetToDefaultNormalizesTheSourceWithoutAdvancingWhenTheSelectionAlreadyMatches() {
        // A CUSTOM record whose selection already equals the authoritative default: the effective
        // configuration does not change, so the version must not move, but the source is normalized.
        val current = ProgramConfiguration.custom(defaults, configurationVersion = 4)

        val reset = current.resetToDefault(defaults)

        assertEquals(ProgramConfigurationSource.DEFAULT, reset.source)
        assertEquals(4, reset.configurationVersion)
    }

    @Test
    fun resetToDefaultNeverRewindsTheVersion() {
        val reset = ProgramConfiguration.custom(subset, configurationVersion = 11).resetToDefault(defaults)

        assertTrue(reset.configurationVersion > 11)
    }

    // ---- deterministic representation ----------------------------------------------------------

    @Test
    fun orderedExerciseIdsIsADeterministicSortedRenderingOfTheSelection() {
        val one = ProgramConfiguration.custom(setOf("squats", "pushups", "plank"), configurationVersion = 1)
        val other = ProgramConfiguration.custom(setOf("plank", "squats", "pushups"), configurationVersion = 1)

        assertEquals(listOf("plank", "pushups", "squats"), one.orderedExerciseIds)
        assertEquals(one.orderedExerciseIds, other.orderedExerciseIds)
        assertEquals(one.enabledExerciseIds.sorted(), one.orderedExerciseIds)
    }
}
