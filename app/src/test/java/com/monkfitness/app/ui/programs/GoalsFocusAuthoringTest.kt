package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7's *Goals & Focus* authoring rules, as the pure functions the screen calls.
 *
 * The screen renders and nothing else, so the three questions it cannot answer for itself — what a
 * multi-select toggle produces, whether the typed percentages describe a configuration yet, and which
 * of the seven focuses a label exists for — are decided here, without a Compose harness (§17).
 *
 * The claim underneath all of it is §7's: **the user states the goal and the focus, and the domain's
 * own vocabulary decides what that statement means**. Nothing here completes an allocation, splits a
 * percentage, reorders a selection into a priority or answers for a focus the user did not name.
 */
class GoalsFocusAuthoringTest {

    // ---------------------------------------------------------------- FOCUSED

    @Test
    fun choosingOneFocusIsAFocusedPlanNamingExactlyThatFocus() {
        val chosen = toggledFocus(FocusPlan.Balanced, Focus.PULL)

        assertEquals(
            "§8's FOCUSED, naming the one focus the user tapped and nothing else",
            FocusPlan.Focused(listOf(Focus.PULL)),
            chosen
        )
        assertEquals(Goal.FOCUSED, chosen?.goal)
        assertEquals(listOf(Focus.PULL), chosen?.eligibleFocuses)
    }

    @Test
    fun choosingSeveralFocusesNamesAllOfThem() {
        var current: FocusPlan = FocusPlan.focused(listOf(Focus.PUSH))
        current = toggledFocus(current, Focus.CORE)!!
        current = toggledFocus(current, Focus.MOBILITY)!!

        assertEquals(
            "three named focuses, and the multi-select added each rather than replacing the last",
            listOf(Focus.PUSH, Focus.CORE, Focus.MOBILITY),
            current.eligibleFocuses
        )
    }

    @Test
    fun tappingAChosenFocusAgainRemovesIt() {
        val current = toggledFocus(FocusPlan.focused(listOf(Focus.PUSH, Focus.LEGS)), Focus.LEGS)

        assertEquals(listOf(Focus.PUSH), current?.eligibleFocuses)
    }

    @Test
    fun theLastFocusCannotBeRemovedSoAFocusedPlanNeverNamesNothing() {
        val only = FocusPlan.focused(listOf(Focus.PUSH))

        assertNull(
            "§8: a plan that names no focus is the BALANCED configuration, not an empty FOCUSED one — so " +
                "unchecking the only focus leaves the state as it was instead of building an impossible plan",
            toggledFocus(only, Focus.PUSH)
        )
    }

    @Test
    fun theOrderFocusesWereTappedInIsNotAPriorityThatSurvives() {
        val tapped = toggledFocus(
            toggledFocus(FocusPlan.Balanced, Focus.CONDITIONING)!!,
            Focus.PUSH
        )!!
        val builtTheOtherWay = FocusPlan.focused(listOf(Focus.PUSH, Focus.CONDITIONING))

        assertEquals(
            "§8 holds a FOCUSED plan's focuses in the vocabulary's own order, so the tapping order is not " +
                "stored as a rank that something downstream could read",
            FocusPlan.canonical(listOf(Focus.CONDITIONING, Focus.PUSH)),
            tapped.eligibleFocuses
        )
        assertEquals(
            "and two equal selections compare equal whatever order the user built them in",
            builtTheOtherWay,
            tapped
        )
    }

    @Test
    fun aToggleOnAnyConfigurationOtherThanAFocusedOneStartsFromAnEmptySelection() {
        val fromCustom = toggledFocus(FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 100))), Focus.PULL)

        assertEquals(
            "a CUSTOM plan states shares, not a selection, so the multi-select starts empty and the " +
                "first tap is the user's first choice",
            listOf(Focus.PULL),
            fromCustom?.eligibleFocuses
        )
    }

    // ---------------------------------------------------------------- CUSTOM

    @Test
    fun theUsersOwnSharesAreTheAllocation() {
        val entry = FocusPercentEntry()
            .withPercent(Focus.PUSH, 70)
            .withPercent(Focus.MOBILITY, 30)

        val plan = entry.toFocusPlan()

        assertEquals(
            "§8's CUSTOM with exactly the shares the user typed — no even split, no rounding, no " +
                "redistribution of the remainder",
            listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30)),
            plan?.allocations
        )
        assertEquals(70, (plan as FocusPlan.Custom).percentFor(Focus.PUSH))
        assertEquals(30, plan.percentFor(Focus.MOBILITY))
    }

    @Test
    fun anAllocationIsHeldInTheVocabularysOwnOrderWhateverOrderItWasTypedIn() {
        val typedMobilityFirst = FocusPercentEntry()
            .withPercent(Focus.MOBILITY, 30)
            .withPercent(Focus.PUSH, 70)

        assertEquals(
            listOf(Focus.PUSH, Focus.MOBILITY),
            typedMobilityFirst.toFocusPlan()?.allocations?.map { allocation -> allocation.focus }
        )
    }

    @Test
    fun aShareThatDoesNotAddUpToOneHundredIsRefusedByTheDomainRatherThanCompleted() {
        val short = FocusPercentEntry().withPercent(Focus.PUSH, 70)

        assertNull(
            "70% alone is not a configuration and nothing may guess the missing 30",
            short.toFocusPlan()
        )
        assertEquals(
            "and the dialog can still show how much is unaccounted for, which decides nothing",
            30,
            short.remainingPercent()
        )
        assertNull(
            "over-allocating is refused the same way",
            FocusPercentEntry().withPercent(Focus.PUSH, 70).withPercent(Focus.PULL, 40).toFocusPlan()
        )
    }

    @Test
    fun anEmptyAllocationIsNotAConfiguration() {
        assertNull(
            "no share at all is the BALANCED configuration, not a CUSTOM one with nothing in it",
            FocusPercentEntry().toFocusPlan()
        )
        assertEquals(100, FocusPercentEntry().remainingPercent())
    }

    @Test
    fun aZeroMeansTheFocusIsNotInThePlanAndNeverBecomesAnAllocation() {
        val entry = FocusPercentEntry().withPercent(Focus.PUSH, 100).withPercent(Focus.PULL, 0)

        assertEquals(
            "§8: a focus stated as 0% is a focus the user did not ask for — left out, not allocated 0%",
            listOf(Focus.PUSH),
            entry.statedFocuses()
        )
        assertEquals(
            "and the finished plan omits it rather than carrying a zero share",
            listOf(Focus.PUSH),
            entry.toFocusPlan()?.eligibleFocuses
        )
        assertEquals("and reads as no share at all", 0, (entry.toFocusPlan() as FocusPlan.Custom).percentFor(Focus.PULL))
    }

    @Test
    fun theDomainRefusesAConfigurationTheUserCouldNotHaveTypedAndWeDoNotRepair() {
        // A negative or non-positive share is unrepresentable at `FocusAllocation`, so a caller that
        // somehow holds one cannot turn it into a plan — the refusal is the domain's own.
        val refused = try {
            FocusAllocation(Focus.PUSH, 0)
            null
        } catch (thrown: IllegalArgumentException) {
            thrown
        }

        assertNotNull("a 0% share is refused where the value is built, not patched afterwards", refused)
        assertNull(
            "and `withPercent` stores an unusable number as the absence of a share, so the entry can " +
                "never hand one to the domain",
            FocusPercentEntry().withPercent(Focus.PUSH, -5).toFocusPlan()
        )
    }

    @Test
    fun oneFocusCarryingTwoSharesIsRefused() {
        // The entry is a map, so a focus cannot be stated twice; the domain refuses the same shape for
        // a caller that builds the list directly, and the entry is simply not a way to reach it.
        val entry = FocusPercentEntry().withPercent(Focus.PUSH, 50).withPercent(Focus.PUSH, 50)

        assertEquals("the second entry for a focus replaces the first, so one focus carries one share", 50, entry.percentOf(Focus.PUSH))
        assertNull("and 50% is not 100%", entry.toFocusPlan())
    }

    @Test
    fun aSingleFullShareIsAConfigurationTheUserStated() {
        val entry = FocusPercentEntry().withPercent(Focus.CORE, 100)

        assertEquals(
            "100% for one focus is the user saying exactly that, and it is a CUSTOM plan",
            FocusPlan.custom(listOf(FocusAllocation(Focus.CORE, 100))),
            entry.toFocusPlan()
        )
    }

    // ---------------------------------------------------------------- the seven labels

    @Test
    fun everyFocusInTheVocabularyHasExactlyOneLabel() {
        val labels = Focus.entries.map { focus -> focus to focusLabelRes(focus) }

        assertEquals(
            "§8's seven focuses, seven labels, and no two focuses sharing one label",
            Focus.entries.size,
            labels.size
        )
        assertEquals(
            "a focus with no label is a focus the editor cannot show, and two focuses sharing a label " +
                "is a vocabulary the reader cannot act on",
            labels.size,
            labels.map { (_, label) -> label }.toSet().size
        )
        labels.forEach { (focus, label) ->
            assertTrue("$focus must resolve to a real string resource", label != 0)
        }
        assertEquals(
            "and the labels are this feature's own resources rather than somebody else's",
            setOf(
                R.string.programs_focus_push,
                R.string.programs_focus_pull,
                R.string.programs_focus_legs,
                R.string.programs_focus_core,
                R.string.programs_focus_mobility,
                R.string.programs_focus_posture,
                R.string.programs_focus_conditioning
            ),
            labels.map { (_, label) -> label }.toSet()
        )
    }

    // ---------------------------------------------------------------- BALANCED

    @Test
    fun balancedStatesNoShareAtAllAndNamesNothing() {
        assertEquals(
            "§8's BALANCED is its own form: the whole vocabulary is eligible and no share is stated",
            FocusPlan.Balanced,
            FocusPlan.DEFAULT
        )
        assertEquals(Focus.entries.toList(), FocusPlan.Balanced.eligibleFocuses)
        assertEquals(
            "so there is nothing for the share dialog to pre-fill and nothing to round",
            emptyList<FocusAllocation>(),
            FocusPlan.Balanced.statedAllocations
        )
    }
}