package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's Focus Planner, pinned on the properties §8 states and on the arithmetic this stage chose.
 *
 * Two kinds of claim are asserted here, and they are deliberately different in strength:
 *
 *  * **the properties §8 fixes** — one primary and 0–2 secondary focuses per slot, determinism, a
 *    horizon-wide decision, softness rather than a hard two-day rule. These may not be violated by any
 *    implementation, and they are asserted as properties.
 *  * **this stage's own arithmetic** — the exact apportionment of a given configuration over a given
 *    horizon. §8 leaves the coefficients open, so these are pinned as *the numbers this policy
 *    produces*, written out by hand (computed outside the implementation, not re-derived by it), which
 *    is what makes a change in the arithmetic visible as a failure instead of as a drift.
 */
class FocusPlannerTest {

    private val vocabulary = Focus.entries.toList()

    // ------------------------------------------------------------------ the shape §8 fixes

    @Test
    fun everySlotGetsOnePrimaryAndAtMostTwoSecondaryFocuses() {
        val assignments = allocate(slots = 12)

        assertEquals("one assignment per slot", 12, assignments.size)
        assignments.forEachIndexed { index, assignment ->
            assertTrue(
                "slot ${index + 1} is built around exactly one focus",
                assignment.primary in vocabulary
            )
            assertTrue(
                "slot ${index + 1} takes at most two secondary focuses (§8), took " +
                    "${assignment.secondary}",
                assignment.secondary.size <= 2
            )
            assertEquals(
                "and a focus is served once per slot: ${assignment.focuses}",
                assignment.focuses.distinct().size,
                assignment.focuses.size
            )
        }
    }

    @Test
    fun aSlotDoesNotRepeatItsPrimaryAmongItsSecondaries() {
        val assignments = allocate(slots = 12)

        assignments.forEach { assignment ->
            assertTrue(
                "a workout whose emphasis is ${assignment.primary} does not secondarily train " +
                    "${assignment.primary}",
                assignment.primary !in assignment.secondary
            )
        }
    }

    @Test
    fun theAllocationIsAFunctionOfItsArguments() {
        assertEquals(
            "the same request produces the same assignment of every slot",
            allocate(slots = 12),
            allocate(slots = 12)
        )

        // A caller's collection order may not decide a plan: a Set has no order, so the pass reads the
        // vocabulary's own one.
        val focuses = linkedSetOf(Focus.CONDITIONING, Focus.PUSH, Focus.PULL, Focus.LEGS, Focus.CORE, Focus.MOBILITY, Focus.POSTURE)
        assertEquals(
            "and a set built in a different order is the same configuration",
            FocusPlanner.allocate(FocusPlan.Balanced, 12, vocabulary),
            FocusPlanner.allocate(FocusPlan.Balanced, 12, focuses.toList())
        )
    }

    @Test
    fun theHorizonIsDecidedAsAWholeAndNotOneWorkoutAtATime() {
        val short = countsOf(allocate(slots = 3))
        val long = countsOf(allocate(slots = 12))

        assertNotEquals(
            "the same configuration over three slots and over twelve is not the same plan: what the " +
                "horizon can still hold is part of the comparison (§8)",
            short,
            long
        )
        assertEquals(
            "three slots hold nine assignments: two focuses per slot at most, plus the primary",
            9,
            short.values.sum()
        )
        assertEquals(
            "with every focus of the vocabulary reached over the longer horizon",
            vocabulary.sortedBy { it.ordinal },
            long.keys.sortedBy { it.ordinal }
        )
    }

    // ------------------------------------------------------------------ the arithmetic, written out

    @Test
    fun aBalancedConfigurationSpreadsOverTheWholeVocabularyAndTheFirstCycleIsWrittenOut() {
        // Hand-computed from the policy's own rule: need(focus) = weight · assignment − covered ·
        // total weight, ties broken by the vocabulary's order. Three slots, nine assignments, seven
        // equal weights — the allocation below is what that arithmetic produces, and a change in the
        // arithmetic shows up here rather than in a plan nobody looked at.
        assertEquals(
            listOf(
                FocusAssignment(Focus.PUSH, listOf(Focus.PULL, Focus.LEGS)),
                FocusAssignment(Focus.CORE, listOf(Focus.MOBILITY, Focus.POSTURE)),
                FocusAssignment(Focus.CONDITIONING, listOf(Focus.PULL, Focus.LEGS))
            ),
            allocate(slots = 3)
        )
        assertEquals(
            "and over one cycle every focus is reached, none twice as often as another by more than " +
                "one assignment",
            mapOf(
                Focus.PUSH to 1,
                Focus.PULL to 2,
                Focus.LEGS to 2,
                Focus.CORE to 1,
                Focus.MOBILITY to 1,
                Focus.POSTURE to 1,
                Focus.CONDITIONING to 1
            ),
            countsOf(allocate(slots = 3))
        )
    }

    @Test
    fun aCustomConfigurationIsApportionedByTheUsersOwnPercentages() {
        // PUSH 50% / PULL 25% / LEGS 25%, three slots. PUSH carries twice the weight of each of the
        // others, so over three slots it leads once and appears in all three — never absent, never
        // taking a slot away from a focus that still owes exposure.
        val configuration = FocusPlan.custom(
            listOf(
                FocusAllocation(Focus.PUSH, 50),
                FocusAllocation(Focus.PULL, 25),
                FocusAllocation(Focus.LEGS, 25)
            )
        )

        assertEquals(
            listOf(
                FocusAssignment(Focus.PUSH, listOf(Focus.PULL, Focus.LEGS)),
                FocusAssignment(Focus.PULL, listOf(Focus.PUSH, Focus.LEGS)),
                FocusAssignment(Focus.LEGS, listOf(Focus.PUSH, Focus.PULL))
            ),
            FocusPlanner.allocate(configuration, 3, configuration.eligibleFocuses)
        )
    }

    @Test
    fun aFocusedConfigurationAllocatesOnlyTheFocusesItNames() {
        val configuration = FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL))

        val assignments = FocusPlanner.allocate(configuration, 3, configuration.eligibleFocuses)

        assertEquals(
            listOf(
                FocusAssignment(Focus.PUSH, listOf(Focus.PULL)),
                FocusAssignment(Focus.PULL, listOf(Focus.PUSH)),
                FocusAssignment(Focus.PUSH, listOf(Focus.PULL))
            ),
            assignments
        )
        assertTrue(
            "a focus the configuration does not name is never planned, however much the plan owes it",
            assignments.flatMap { it.focuses }.toSet() == setOf(Focus.PUSH, Focus.PULL)
        )
    }

    @Test
    fun theWeightsAreTheConfigurationsOwnAndNothingElse() {
        assertEquals(
            "BALANCED gives every plannable focus the same weight — no coefficient, no per-focus tuning",
            vocabulary.associateWith { 1 },
            FocusPlanner.weightsOf(FocusPlan.Balanced, vocabulary)
        )
        assertEquals(
            "FOCUSED gives the named focuses the same weight and the others none",
            mapOf(Focus.PUSH to 1, Focus.PULL to 1, Focus.LEGS to 0),
            FocusPlanner.weightsOf(FocusPlan.focused(setOf(Focus.PUSH, Focus.PULL)), listOf(Focus.PUSH, Focus.PULL, Focus.LEGS))
        )
        assertEquals(
            "and CUSTOM uses the user's percentages verbatim — they are the only numbers a user states",
            mapOf(Focus.PUSH to 60, Focus.PULL to 40),
            FocusPlanner.weightsOf(
                FocusPlan.custom(
                    listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.PULL, 40))
                ),
                listOf(Focus.PUSH, Focus.PULL)
            )
        )
    }

    // ------------------------------------------------------------------ the signals it considers

    @Test
    fun exposureTheUserAlreadyHadSuppressesFurtherAllocationOfThatFocus() {
        val withoutSignals = allocate(slots = 3)
        val afterPressingTwice = allocate(
            slots = 3,
            preferences = GeneratedPlannerRig.preferences(recentExposure = mapOf(Focus.PUSH to 3))
        )

        assertNotEquals(
            "recent exposure changes the plan",
            withoutSignals,
            afterPressingTwice
        )
        assertEquals(
            "and the focus that already had three assignments before the plan starts is not asked " +
                "for again — the deficit it carries is negative until long after this horizon ends",
            0,
            countsOf(afterPressingTwice)[Focus.PUSH] ?: 0
        )
        assertTrue(
            "while the focuses that had nothing still get exposure",
            countsOf(afterPressingTwice).keys.containsAll(
                listOf(Focus.PULL, Focus.LEGS, Focus.CORE)
            )
        )
    }

    @Test
    fun recentLoadDemotesAFocusSoftlyAndNeverForbidsIt() {
        val loaded = allocate(
            slots = 3,
            preferences = GeneratedPlannerRig.preferences(
                recentLoad = mapOf(Focus.PUSH to GenerationPolicy.DEFAULT_RECENT_LOAD_THRESHOLD)
            )
        )

        assertNotEquals(
            "a focus whose recent load has reached the policy's threshold is passed over while " +
                "another focus still owes exposure",
            allocate(slots = 3),
            loaded
        )
        assertEquals(
            "and passed over is all it is: no slot of the plan is left without a focus",
            3,
            loaded.size
        )

        // The soft half of the rule, and the reason it is not §14's forbidden hard 48-hour rule: when
        // nothing else owes exposure, the loaded focus is chosen anyway.
        val only = FocusPlan.focused(setOf(Focus.PUSH))
        val withLoad = FocusPlanner.allocate(
            only, 3, only.eligibleFocuses,
            GeneratedPlannerRig.preferences(
                recentLoad = mapOf(Focus.PUSH to GenerationPolicy.DEFAULT_RECENT_LOAD_THRESHOLD)
            )
        )
        val withoutLoad = FocusPlanner.allocate(only, 3, only.eligibleFocuses)

        assertEquals(
            "a plan about one loaded focus is still a plan about it: the penalty has a fallback, so " +
                "it can never leave a slot with nothing to train",
            withoutLoad,
            withLoad
        )
    }

    @Test
    fun theRecoveryWindowIsAPreferenceAndNotATwoDayRule() {
        val only = FocusPlan.focused(setOf(Focus.PUSH))

        assertEquals(
            "the same focus leads every slot of a one-focus plan, however many slots there are: the " +
                "window demotes a focus only while another one owes exposure, which is what makes it " +
                "a preference rather than a universal hard rule (§8, §14)",
            List(6) { FocusAssignment(Focus.PUSH) },
            FocusPlanner.allocate(only, 6, only.eligibleFocuses)
        )

        // And where another focus does owe exposure, the window is visible: no focus leads two
        // consecutive slots of the balanced first cycle.
        val primaries = allocate(slots = 3).map { it.primary }
        assertEquals(
            "the window's own size decides how far back it looks",
            listOf(Focus.PUSH, Focus.CORE, Focus.CONDITIONING),
            primaries
        )
    }

    @Test
    fun aCautiousRecoveryContextNarrowsHowMuchAWorkoutStacksAndNothingElse() {
        val unknown = allocate(slots = 3, preferences = GeneratedPlannerRig.preferences())
        val cautious = allocate(
            slots = 3,
            preferences = GeneratedPlannerRig.preferences(recovery = RecoveryContext.CAUTIOUS)
        )

        assertTrue(
            "§14 lets recovery make a plan more conservative, and the only conservatism this stage " +
                "expresses is stacking fewer focuses: ${cautious.map { it.secondary }}",
            cautious.all { it.secondary.size <= GenerationPolicy.DEFAULT_CAUTIOUS_SECONDARY_FOCUS_LIMIT }
        )
        assertTrue(
            "the narrowing is observable: the ordinary context does stack two secondaries somewhere",
            unknown.any { it.secondary.size == 2 }
        )
        assertNotEquals(unknown, cautious)
        assertEquals(
            "and it changes nothing else: the slot count is the horizon's, the configuration's focuses " +
                "are the same, and the schedule and duration are not inputs of this decision at all",
            unknown.size,
            cautious.size
        )
    }

    @Test
    fun thereIsNothingToPlanWhenNoFocusIsPlannable() {
        assertEquals(
            "a horizon over no plannable focus is empty, not an invented default focus",
            emptyList<FocusAssignment>(),
            FocusPlanner.allocate(FocusPlan.Balanced, 3, emptyList())
        )
        assertEquals(
            "and a horizon of no slots holds no assignment",
            emptyList<FocusAssignment>(),
            FocusPlanner.allocate(FocusPlan.Balanced, 0, vocabulary)
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun allocate(
        slots: Int,
        preferences: GenerationPreferences = GenerationPreferences.NONE,
        focus: FocusPlan = FocusPlan.Balanced
    ): List<FocusAssignment> = FocusPlanner.allocate(
        focus = focus,
        slotCount = slots,
        plannableFocuses = vocabulary,
        preferences = preferences,
        policy = GenerationPolicy.DEFAULT
    )

    private fun countsOf(assignments: List<FocusAssignment>): Map<Focus, Int> =
        Focus.entries
            .map { focus -> focus to assignments.sumOf { it.focuses.count { served -> served == focus } } }
            .filter { (_, count) -> count > 0 }
            .toMap()
}
