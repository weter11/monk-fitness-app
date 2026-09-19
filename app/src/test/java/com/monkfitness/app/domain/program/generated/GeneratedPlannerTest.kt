package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * §8 and §9's **Generated Planner**, pinned on the plan it produces.
 *
 * The claims here are the ones §30 step 10's architecture gates name: deterministic generation, the
 * focus assignment of every slot, hard constraints that are never violated, repeated use that stays
 * distinct, and an output that is a value rather than a draft — with nothing to persist it and no
 * calendar to place it on.
 */
class GeneratedPlannerTest {

    // ------------------------------------------------------------------ determinism (§9)

    @Test
    fun theSameRequestProducesTheSamePlan() {
        val request = GeneratedPlannerRig.request()

        assertEquals(
            "same inputs, same plan — bit for bit, in one process and in the next",
            GeneratedPlanner.plan(request),
            GeneratedPlanner.plan(request)
        )
        assertEquals(
            "and the plan's own parts are values, so an equality is an equality of content",
            GeneratedPlanner.plan(request).slots,
            GeneratedPlanner.plan(request).slots
        )
    }

    @Test
    fun theOrderOfTheLibraryViewAndOfEverySetInsideItChangesNothing() {
        val forwards = GeneratedPlanner.plan(GeneratedPlannerRig.request())
        val backwards = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(candidates = GeneratedPlannerRig.LIBRARY.reversed())
        )
        // The same candidates, with each one's focus membership built in the other order: a Set has no
        // order, so nothing may read one.
        val reshuffledFocuses = GeneratedPlannerRig.LIBRARY.map { candidate ->
            candidate.copy(focuses = linkedSetOf(*candidate.focuses.toTypedArray().reversedArray()))
        }

        assertEquals(
            "the library view is a set of allowed exercises, not an ordering that ranks them",
            forwards,
            backwards
        )
        assertEquals(
            "and an exercise's focus membership is a set too",
            forwards,
            GeneratedPlanner.plan(GeneratedPlannerRig.request(candidates = reshuffledFocuses))
        )
    }

    @Test
    fun thePlanCoversTheSchedulesSlotsOverTheDurationsWholeWeeks() {
        assertEquals(
            "three sessions a week over an indefinite Program: the §20 horizon's four whole weeks",
            12,
            GeneratedPlannerRig.request().slotCount
        )
        assertEquals(
            "three sessions a week over thirty days is the same window, read from the duration",
            12,
            GeneratedPlannerRig.request(duration = ProgramDuration.FixedDays(30)).slotCount
        )
        assertEquals(
            "a six-day program still holds one whole week rather than a fraction of one",
            3,
            GeneratedPlannerRig.request(duration = ProgramDuration.FixedDays(6)).slotCount
        )
        assertEquals(
            "a fixed-weekday schedule holds one slot per named weekday per week",
            12,
            GeneratedPlannerRig.request(schedule = GeneratedPlannerRig.THREE_FIXED_WEEKDAYS).slotCount
        )
        assertEquals(
            "and five sessions a week over four whole weeks is twenty slots",
            20,
            GeneratedPlannerRig.request(schedule = ProgramSchedule.FlexiblePerWeek(5)).slotCount
        )
        assertEquals(
            "the planned slots are exactly that many days",
            12,
            GeneratedPlanner.plan(GeneratedPlannerRig.request()).slotCount
        )
    }

    @Test
    fun theSlotCountIsNeverAFunctionOfACalendarAWeekdayOrAClock() {
        val weekdays = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val sameSize = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY))

        assertEquals(
            "which weekdays a schedule names does not change how many slots a plan holds: the dates " +
                "are the Scheduler's business, not the generator's (§20, §30 step 7)",
            GeneratedPlannerRig.request(schedule = weekdays).slotCount,
            GeneratedPlannerRig.request(schedule = sameSize).slotCount
        )
    }

    // ------------------------------------------------------------------ the plan's own shape

    @Test
    fun everySlotPlansOneElementPerAssignedFocusInTheAssignmentsOwnOrder() {
        val plan = GeneratedPlanner.plan(GeneratedPlannerRig.request())

        plan.slots.forEach { slot ->
            assertEquals(
                "slot ${slot.position} plans the focuses it assigned, in the assignment's order — " +
                    "the primary first",
                slot.assignment.focuses,
                slot.elements.map { it.focus }
            )
            assertTrue(
                "and one element per focus, so '1 primary + 0–2 secondary' is a statement about the " +
                    "workout rather than a label on it: ${slot.elements}",
                slot.elements.size == slot.assignment.focuses.size
            )
        }
        assertEquals(
            "the plan's elements are its slots' elements, counted once",
            plan.slots.sumOf { it.elements.size },
            plan.elementCount
        )
    }

    @Test
    fun thePlanIsPrescribedInEachExercisesOwnDimensionWithThePolicysTargets() {
        val plan = GeneratedPlanner.plan(GeneratedPlannerRig.request())
        val timeBased = GeneratedPlannerRig.request(
            candidates = GeneratedPlannerRig.LIBRARY.map {
                it.copy(dimension = PrescriptionDimension.TIME_BASED)
            }
        )

        plan.slots.flatMap { it.elements }.forEach { element ->
            assertEquals(
                "a rep-based exercise is prescribed in repetitions (§10)",
                RepPrescription(GenerationPolicy.DEFAULT_REP_PRESCRIPTION_TARGETS),
                element.prescription
            )
        }
        GeneratedPlanner.plan(timeBased).slots.flatMap { it.elements }.forEach { element ->
            assertEquals(
                "and a time-based exercise in seconds — the prescription belongs to the plan and is " +
                    "written in the element's own dimension",
                TimePrescription(GenerationPolicy.DEFAULT_TIME_PRESCRIPTION_TARGETS),
                element.prescription
            )
        }
    }

    @Test
    fun theTwoPrescriptionShapesAreTheOnesSectionTenWritesOut() {
        assertEquals(
            "§10's own example, used unchanged rather than invented here",
            listOf(12, 10, 8, 6),
            GenerationPolicy.DEFAULT_REP_PRESCRIPTION_TARGETS
        )
        assertEquals(
            "and its other one",
            listOf(30, 30, 45),
            GenerationPolicy.DEFAULT_TIME_PRESCRIPTION_TARGETS
        )
        assertEquals(
            "no amount of work is attached to any focus or exercise: the plan has no score to tune",
            PrescriptionDimension.entries.filter {
                it != PrescriptionDimension.REP_BASED && it != PrescriptionDimension.TIME_BASED
            }.map { it.name },
            listOf("SET_BASED", "DIFFICULTY_BASED", "REST_BASED")
        )
    }

    @Test
    fun theFocusConfigurationThePlanWasBuiltForTravelsWithIt() {
        val configuration = FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL))

        assertEquals(
            "a saved revision can state what its plan was built for without a second lookup",
            configuration,
            GeneratedPlanner.plan(GeneratedPlannerRig.request(focus = configuration)).focus
        )
    }

    // ------------------------------------------------------------------ selection (§9)

    @Test
    fun anExerciseTheEquipmentCannotSupportIsNeverChosen() {
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(availableEquipment = setOf(RigEquipment.NONE))
        )
        val allowed = GeneratedPlannerRig.LIBRARY
            .filter { it.requiredEquipment.isEmpty() }
            .map { it.exerciseId }
            .toSet()

        plan.slots.flatMap { it.elements }.forEach { element ->
            assertTrue(
                "'${element.exerciseId}' was chosen although the user has none of the equipment it " +
                    "needs — §9's hard execution constraints are never silently violated",
                element.exerciseId in allowed
            )
        }
        assertTrue(
            "and every focus still plannable without equipment is planned",
            plan.elementCount > 0
        )
    }

    @Test
    fun anExerciseTheSelectionForbidsIsNeverChosen() {
        val onlyPush = GeneratedPlannerRig.LIBRARY.filter { it.trains(Focus.PUSH) }
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.PUSH)),
                candidates = onlyPush
            )
        )

        assertEquals(
            "the allowed selection is the library the planner sees, so nothing else can be chosen",
            setOf("push-dip", "push-pushup", "push-plank"),
            plan.slots.flatMap { it.elements }.map { it.exerciseId }.toSet()
        )
    }

    @Test
    fun theUsersOwnChoiceOutranksAnAdaptivePreference() {
        val userChoice = "push-pushup"
        val adaptivePreference = "push-dip"

        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.PUSH)),
                preferences = GeneratedPlannerRig.preferences(
                    userPreferred = listOf(userChoice),
                    adaptivePreferred = listOf(adaptivePreference)
                )
            )
        )

        assertEquals(
            "§9's first level is the user's own choice, and the request states the two levels " +
                "separately precisely so this is decided by the request rather than assumed",
            userChoice,
            plan.slots.first().elements.first().exerciseId
        )
    }

    @Test
    fun anAdaptivePreferenceOutranksTheGeneratorsOwnDiversityRule() {
        val adaptivePreference = "push-dip"

        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.PUSH)),
                preferences = GeneratedPlannerRig.preferences(
                    adaptivePreferred = listOf(adaptivePreference)
                )
            )
        )

        assertEquals(
            "the preference is ranked above diversity, so it is chosen every time — not only where " +
                "the generator had no favourite of its own",
            List(plan.slotCount) { adaptivePreference },
            plan.slots.map { it.elements.first().exerciseId }
        )
    }

    @Test
    fun anExerciseUsedMostRecentlyIsPassedOverWhileAnAlternativeExists() {
        // Two exercises of the same family that train the same focus and are both unpreferred, so
        // usage, family balance and the canonical id agree and only recency can separate them.
        val pair = listOf(
            GeneratedPlannerRig.candidate("pull-a", "pull-family", setOf(Focus.PULL)),
            GeneratedPlannerRig.candidate("pull-b", "pull-family", setOf(Focus.PULL))
        )
        val configuration = FocusPlan.focused(setOf(Focus.PULL))

        val untouched = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(candidates = pair, focus = configuration)
        )
        val afterUsingOne = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                candidates = pair,
                focus = configuration,
                preferences = GeneratedPlannerRig.preferences(recent = listOf("pull-a"))
            )
        )

        assertEquals(
            "with nothing recently used, the canonical id decides — 'pull-a'",
            "pull-a",
            untouched.slots.first().elements.single().exerciseId
        )
        assertEquals(
            "and an exercise the caller just reported as the most recent one is passed over while " +
                "the alternative is still unused: §9's recency axis, read from the plain list",
            "pull-b",
            afterUsingOne.slots.first().elements.single().exerciseId
        )
        assertNotEquals(untouched.slots, afterUsingOne.slots)
    }

    @Test
    fun anExerciseIsNotRepeatedWhileAnUnusedAlternativeExists() {
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(focus = FocusPlan.focused(setOf(Focus.LEGS)))
        )
        val legsElements = plan.slots
            .map { slot -> slot.elements.single().exerciseId }
            .filter { it.startsWith("legs-") }

        assertTrue(
            "there are two leg exercises, so the first two slots draw on both rather than repeating " +
                "one: ${plan.slots.map { it.elements.single().exerciseId }}",
            legsElements.take(2).toSet().size == 2
        )
    }

    @Test
    fun theDeterministicTieBreakIsTheCanonicalExerciseId() {
        // Two exercises that train the same focus, in the same family, neither preferred, neither
        // recent: the id decides, so the plan is the same on any device and in any locale.
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.LEGS)),
                candidates = listOf(
                    GeneratedPlannerRig.candidate("legs-zzz", "legs", setOf(Focus.LEGS)),
                    GeneratedPlannerRig.candidate("legs-aaa", "legs", setOf(Focus.LEGS))
                )
            )
        )

        assertEquals(
            "the smaller id wins the tie, and the two then alternate rather than repeating one: " +
                "usage and family balance separate them as soon as either has been used once",
            List(plan.slotCount) { index -> if (index % 2 == 0) "legs-aaa" else "legs-zzz" },
            plan.slots.map { it.elements.single().exerciseId }
        )
    }

    @Test
    fun repeatedUseIsAllowedAndEveryOccurrenceIsItsOwnElement() {
        // One exercise, one focus: the plan must use it repeatedly rather than leave the slot empty.
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.POSTURE)),
                candidates = listOf(
                    GeneratedPlannerRig.candidate("posture-wall-slide", "posture", setOf(Focus.POSTURE))
                )
            )
        )

        assertTrue("the plan is filled", plan.elementCount >= 3)
        assertEquals(
            "and every occurrence is a slot's own element — the plan's elements are values, and the " +
                "draft identities that keep them apart are minted when the plan becomes a draft (§9)",
            plan.elementCount,
            plan.slots.sumOf { it.elements.count { element -> element.exerciseId == "posture-wall-slide" } }
        )
    }

    // ------------------------------------------------------------------ what cannot be planned

    @Test
    fun aFocusNothingTrainsIsReportedRatherThanFilled() {
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.PUSH, Focus.POSTURE)),
                candidates = GeneratedPlannerRig.LIBRARY.filter { it.trains(Focus.PUSH) }
            )
        )

        assertEquals(
            "POSTURE cannot be planned, and the plan says why instead of substituting PUSH for it",
            listOf(GenerationLimitation.UnusableFocus(Focus.POSTURE, FocusUnusableReason.NO_EXERCISE_TRAINS_THE_FOCUS)),
            plan.limitations
        )
        assertTrue(
            "so the plan holds no POSTURE element at all",
            plan.slots.flatMap { it.elements }.none { it.focus == Focus.POSTURE }
        )
        assertTrue(
            "and it still holds the focus that can be planned",
            plan.slots.flatMap { it.elements }.any { it.focus == Focus.PUSH }
        )
    }

    @Test
    fun anEquipmentExcludedFocusIsReportedAndNeverSilentlySubstituted() {
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL)),
                candidates = GeneratedPlannerRig.LIBRARY.filter { it.trains(Focus.PUSH) || it.trains(Focus.PULL) },
                availableEquipment = setOf(RigEquipment.NONE)
            )
        )

        assertEquals(
            "PULL is available only on equipment the user does not have, and that is reported as " +
                "the equipment fact it is",
            listOf(
                GenerationLimitation.UnusableFocus(
                    Focus.PULL,
                    FocusUnusableReason.EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT
                )
            ),
            plan.limitations
        )
        assertFalse("no substitution happened", plan.isUnplannable(Focus.PUSH))
    }

    @Test
    fun aDimensionWithoutASubtypeIsReportedRatherThanPrescribed() {
        val plan = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.focused(setOf(Focus.CORE)),
                candidates = listOf(
                    GeneratedPlannerRig.candidate(
                        "core-weighted-plank",
                        "core-stability",
                        setOf(Focus.CORE),
                        dimension = PrescriptionDimension.DIFFICULTY_BASED
                    )
                )
            )
        )

        assertEquals(
            "§10 names three dimensions without a subtype, and a generator may not invent an " +
                "algorithm for them: the plan reports the fact instead",
            listOf(
                GenerationLimitation.UnusableFocus(
                    Focus.CORE,
                    FocusUnusableReason.PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED
                ),
                GenerationLimitation.NoPlannableFocus
            ),
            plan.limitations
        )
        assertEquals("and it plans no slot at all", emptyList<GeneratedSlot>(), plan.slots)
    }

    @Test
    fun aPlanIsNeverPrescribedInADimensionTheDomainCannotState() {
        val plan = GeneratedPlanner.plan(GeneratedPlannerRig.request())
        val implemented = setOf(PrescriptionDimension.REP_BASED, PrescriptionDimension.TIME_BASED)

        plan.slots.flatMap { it.elements }.forEach { element ->
            assertTrue(
                "every generated element's prescription is in a dimension with a subtype, so no " +
                    "plan element states something the domain cannot mean (§10)",
                element.prescription.dimension in implemented
            )
        }
    }

    @Test
    fun theReportsCountsAreThePlansOwnCounts() {
        val plan = GeneratedPlanner.plan(GeneratedPlannerRig.request())

        assertEquals(
            "the per-focus counts of the plan add up to its elements, and each slot contributes one " +
                "to each focus it trains",
            plan.elementCount,
            plan.focusCounts.values.sum()
        )
        assertEquals(
            "a slot trains the focuses it assigned and no others",
            plan.slots.sumOf { it.assignment.focuses.size },
            plan.elementCount
        )
    }

    @Test
    fun aBalancedRequestReportsNoLimitationBecauseTheWholeVocabularyIsAvailable() {
        val plan = GeneratedPlanner.plan(GeneratedPlannerRig.request())

        assertEquals(
            "nothing was unplannable, so nothing is reported",
            emptyList<GenerationLimitation>(),
            plan.limitations
        )
    }

    @Test
    fun aCustomConfigurationPlansOnlyTheFocusesItAllocates() {
        // A Custom configuration whose shares the planner apportions, over the same library and
        // horizon: the two plans differ only where the user's own numbers say they should.
        val balanced = GeneratedPlanner.plan(GeneratedPlannerRig.request())
        val custom = GeneratedPlanner.plan(
            GeneratedPlannerRig.request(
                focus = FocusPlan.custom(
                    listOf(
                        FocusAllocation(Focus.PUSH, 50),
                        FocusAllocation(Focus.PULL, 30),
                        FocusAllocation(Focus.LEGS, 20)
                    )
                )
            )
        )

        assertNotEquals(balanced.assignments, custom.assignments)
        assertTrue(
            "and a focus the user did not allocate is never planned",
            custom.slots.flatMap { it.elements }.none {
                it.focus !in setOf(Focus.PUSH, Focus.PULL, Focus.LEGS)
            }
        )
        assertTrue(
            "while the balanced plan reaches the whole vocabulary",
            balanced.focusCounts.keys.size == Focus.entries.size
        )
    }

}
