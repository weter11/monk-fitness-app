package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.EVERY_EXERCISE
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.FAMILY
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.element
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.improvement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.relation
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.request
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.snapshot
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig.window
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §18's tie-breaking rule, as a gate: *"All tie-breaking is deterministic"*, and the stage's brief makes
 * it a hard one — equal domain inputs, equal results, whatever order the caller's collections arrived in.
 *
 * Determinism here is not a property of one sorting call. It is enforced at three levels, and the suite
 * exercises all three:
 *
 *  * **at construction**, where the values that could otherwise differ by order (a relation's variants,
 *    an intensity profile's per-family entries) are canonical, and an out-of-order one is refused
 *    outright;
 *  * **in the derivation**, where the window's observations are re-ordered by their own facts before
 *    anything is counted;
 *  * **in the result**, where the comparison's channels are held in one canonical order and the whole
 *    decision is a value that can simply be compared.
 */
class ProgramAdaptiveDeterminismTest {

    @Test
    fun theSameRequestProducesTheSameResult() {
        val request = request(snapshot = snapshot(exposures = improvement()), window = window(level = 2))

        assertEquals(ProgramAdaptiveEngine.decide(request), ProgramAdaptiveEngine.decide(request))
    }

    @Test
    fun theOrderOfTheCollectionsThatHaveNoOrderChangesNothing() {
        val forward = request(
            snapshot = snapshot(exposures = improvement()),
            element = element(),
            relation = relation(),
            window = window(level = 2),
            availableExerciseIds = LinkedHashSet(EVERY_EXERCISE),
            familyOfExercise = LinkedHashMap(
                mapOf(
                    ProgramAdaptiveRig.PRESENTED to FAMILY,
                    ProgramAdaptiveRig.EASIER to FAMILY,
                    ProgramAdaptiveRig.HARDER to FAMILY
                )
            )
        )
        val backward = request(
            snapshot = snapshot(exposures = improvement()),
            element = element(),
            relation = relation(),
            window = window(level = 2),
            availableExerciseIds = LinkedHashSet(EVERY_EXERCISE.toList().reversed()),
            familyOfExercise = LinkedHashMap(
                mapOf(
                    ProgramAdaptiveRig.HARDER to FAMILY,
                    ProgramAdaptiveRig.EASIER to FAMILY,
                    ProgramAdaptiveRig.PRESENTED to FAMILY
                )
            )
        )

        assertEquals(
            "the caller's own insertion order is not a tie-break: it decides nothing",
            ProgramAdaptiveEngine.decide(forward), ProgramAdaptiveEngine.decide(backward)
        )
        assertEquals(
            "the selection is a set, so the two spellings of it are the same selection",
            forward.availableExerciseIds, backward.availableExerciseIds
        )
    }

    @Test
    fun theOrderedValuesRefuseAnOrderTheyDoNotHoldRatherThanNormalizeItSilently() {
        // The relation and the intensity profile are values whose order is part of what they mean, so a
        // caller that built one from an unordered source has to canonicalize it. The alternative —
        // sorting silently — would make "the same ladder" two different values depending on the caller.
        assertThrows(IllegalArgumentException::class.java) {
            ProgramProgressionRelation(FAMILY, relation().variants.reversed())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IntensityLoad(listOf(IntensityEntry("squats", 1), IntensityEntry("pushups", 2)))
        }
        assertEquals(
            "and two relations built from differently ordered sources are one value once canonical",
            relation(),
            ProgramProgressionRelation(FAMILY, relation().variants.sortedBy { it.identity })
        )
    }

    @Test
    fun aRelationThatIsNotHeldInItsCanonicalOrderIsRefusedRatherThanSortedSilently() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProgramProgressionRelation(
                familyId = FAMILY,
                variants = listOf(
                    ProgramProgressionVariant(
                        1, ProgramAdaptiveRig.HARDER, ProgramAdaptiveRig.presentation().prescription
                    ),
                    ProgramProgressionVariant(
                        1, ProgramAdaptiveRig.EASIER, ProgramAdaptiveRig.presentation().prescription
                    )
                )
            )
        }

        assertTrue(
            "a relation is a value, so an order the value does not hold is refused rather than " +
                "quietly normalized: ${failure.message}",
            failure.message!!.contains("level then exercise-id order")
        )
    }

    @Test
    fun everyComparisonOfTheSameTwoProfilesIsTheSameValue() {
        val baseline = LoadProfile(
            scope = AdaptiveScope.EXERCISE,
            volume = VolumeLoad(sets = 3, repetitions = 30),
            intensity = IntensityLoad(
                listOf(IntensityEntry("pushups", 2), IntensityEntry("squats", 1))
            ),
            density = DensityLoad(workingSeconds = 90, restSeconds = 60),
            exposure = ExposureLoad(opportunities = 4, completedOpportunities = 3)
        )
        val candidate = baseline.copy(
            intensity = IntensityLoad(
                listOf(IntensityEntry("pushups", 3), IntensityEntry("squats", 1))
            )
        )

        assertEquals(ProgramLoadComparison.of(baseline, candidate), ProgramLoadComparison.of(baseline, candidate))
        assertEquals(
            listOf(
                ProgramLoadChannel.VOLUME_SETS,
                ProgramLoadChannel.VOLUME_REPETITIONS,
                ProgramLoadChannel.VOLUME_SECONDS,
                ProgramLoadChannel.INTENSITY_LEVEL,
                ProgramLoadChannel.INTENSITY_LEVEL,
                ProgramLoadChannel.DENSITY_WORKING_SECONDS,
                ProgramLoadChannel.DENSITY_REST_SECONDS,
                ProgramLoadChannel.EXPOSURE_OPPORTUNITIES,
                ProgramLoadChannel.EXPOSURE_COMPLETED
            ),
            ProgramLoadComparison.of(baseline, candidate).channels.map { it.channel }
        )
        assertEquals(
            "the per-family channels are ordered by family, so a caller's map order cannot decide",
            listOf("pushups", "squats"),
            ProgramLoadComparison.of(baseline, candidate)
                .channels.filter { it.channel == ProgramLoadChannel.INTENSITY_LEVEL }
                .map { it.subject }
        )
    }

    @Test
    fun anIntensityProfileThatIsNotInFamilyOrderIsRefusedRatherThanSortedSilently() {
        assertThrows(IllegalArgumentException::class.java) {
            IntensityLoad(listOf(IntensityEntry("squats", 1), IntensityEntry("pushups", 2)))
        }
    }

    @Test
    fun theGuardReachesTheSameVerdictAndTheSameViolationOrderEveryTime() {
        val baseline = ProgramAdaptiveRig.profile(scope = AdaptiveScope.EXERCISE, sets = 3, repetitions = 30)
        val tooMuch = ProgramAdaptiveRig.profile(
            scope = AdaptiveScope.EXERCISE, sets = 6, repetitions = 90
        )

        val first = ProgramAggregateLoadGuard.guard(
            action = AdaptiveAction.PROGRESS,
            baselineLoad = baseline,
            candidateLoad = tooMuch,
            policy = ProgramAdaptivePolicy.V1
        )
        val second = ProgramAggregateLoadGuard.guard(
            action = AdaptiveAction.PROGRESS,
            baselineLoad = baseline,
            candidateLoad = tooMuch,
            policy = ProgramAdaptivePolicy.V1
        )

        assertEquals(first, second)
        assertEquals(
            listOf(ProgramLoadChannel.VOLUME_SETS, ProgramLoadChannel.VOLUME_REPETITIONS),
            (first as ProgramGuardVerdict.Filtered).exceeded.map { it.channel }
        )
    }
}
