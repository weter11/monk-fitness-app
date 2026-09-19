package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.prescription.RepPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * §8's goals and focus vocabulary, pinned as a value algebra.
 *
 * This suite is deliberately small and total: the vocabulary is seven focuses and three goals and
 * nothing else, and the only rule §8 states about a configuration is that custom percentages sum to
 * 100%. Everything asserted here is therefore either *the vocabulary itself* (a fifth focus or a
 * fourth goal would be a different product decision, not a refactoring) or *a state the domain
 * refuses to represent*.
 */
class FocusPlanTest {

    @Test
    fun theFocusVocabularyIsExactlyTheSevenValuesSectionEightNames() {
        assertEquals(
            "PUSH / PULL / LEGS / CORE / MOBILITY / POSTURE / CONDITIONING — and no eighth value " +
                "invented for a day type (§8)",
            listOf("PUSH", "PULL", "LEGS", "CORE", "MOBILITY", "POSTURE", "CONDITIONING"),
            Focus.entries.map { it.name }
        )
        assertEquals(
            "the three goals of §8, and no fourth mode: 'semi-automatic' is a workflow, not a goal",
            listOf("BALANCED", "FOCUSED", "CUSTOM"),
            Goal.entries.map { it.name }
        )
    }

    @Test
    fun focusAndDayTypeAreTwoDifferentDimensionsThatHappenToShareOneWord() {
        // The confusion §8 of the stage's brief calls out by name is *equating* the two vocabularies,
        // and the honest form of the rule is not "their names differ" — MOBILITY is both a day type
        // and a focus — but "they are different dimensions, of different sizes, and nothing states a
        // correspondence between them".
        val focuses = Focus.entries.map { it.name }.toSet()
        val dayTypes = ProgramDayType.entries.map { it.name }.toSet()

        assertEquals(
            "seven focuses, and a day type is not one of them",
            7,
            focuses.size
        )
        assertEquals(
            "four day types: what a day is for, not which emphasis a workout has",
            listOf("TRAINING", "MOBILITY", "POSTURE_MOBILITY", "REST"),
            ProgramDayType.entries.map { it.name }
        )
        assertNotEquals(
            "and the two vocabularies are not the same vocabulary under another name",
            focuses,
            dayTypes
        )
        assertEquals(
            "they share exactly one word — MOBILITY — and neither contains the other, which is " +
                "precisely why inferring a focus from a day type would be a guess and not a rule",
            setOf("MOBILITY"),
            focuses intersect dayTypes
        )
        assertTrue(
            "no focus is TRAINING or REST, and no day type is CONDITIONING, CORE, PUSH, PULL, LEGS " +
                "or POSTURE",
            dayTypes.intersect(setOf("CONDITIONING", "CORE", "PUSH", "PULL", "LEGS", "POSTURE")).isEmpty()
        )
    }

    @Test
    fun theGoalIsDerivedFromTheConfigurationRatherThanStoredBesideIt() {
        assertEquals(Goal.BALANCED, FocusPlan.Balanced.goal)
        assertEquals(Goal.FOCUSED, FocusPlan.focused(setOf(Focus.PUSH)).goal)
        assertEquals(
            Goal.CUSTOM,
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, FocusPlan.FULL_ALLOCATION))).goal
        )
        assertEquals(
            "the configuration a Program starts with states no share and no named focus",
            FocusPlan.Balanced,
            FocusPlan.DEFAULT
        )
    }

    @Test
    fun theEligibleFocusesAreTheWholeVocabularyForBalancedAndTheStatedOnesOtherwise() {
        assertEquals(
            "BALANCED leaves every focus eligible and states no share",
            Focus.entries.toList(),
            FocusPlan.Balanced.eligibleFocuses
        )
        assertEquals(
            listOf(Focus.PUSH, Focus.PULL),
            FocusPlan.focused(setOf(Focus.PULL, Focus.PUSH)).eligibleFocuses
        )
        assertEquals(
            "a CUSTOM configuration's eligible focuses are the ones it allocates",
            listOf(Focus.PUSH, Focus.LEGS),
            FocusPlan.custom(
                listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.LEGS, 40))
            ).eligibleFocuses
        )
        assertEquals(
            "and only a CUSTOM configuration states a share",
            emptyList<FocusAllocation>(),
            FocusPlan.focused(setOf(Focus.PUSH)).statedAllocations
        )
    }

    @Test
    fun aConfigurationsFocusesAreHeldInTheVocabularysOwnOrderWhateverOrderTheyWereBuiltIn() {
        // The determinism rule the whole planner rests on: a Set has no meaningful order, so a
        // configuration built from one may not let that order decide what the plan is.
        val builtBackwards = FocusPlan.focused(listOf(Focus.CONDITIONING, Focus.CORE, Focus.PUSH))
        val builtForwards = FocusPlan.focused(listOf(Focus.PUSH, Focus.CORE, Focus.CONDITIONING))

        assertEquals(
            "two focused configurations that name the same focuses are the same configuration",
            builtForwards,
            builtBackwards
        )
        assertEquals(listOf(Focus.PUSH, Focus.CORE, Focus.CONDITIONING), builtBackwards.focuses)
        assertEquals(
            "and a custom configuration is held in the same canonical order",
            FocusPlan.custom(
                listOf(FocusAllocation(Focus.LEGS, 40), FocusAllocation(Focus.PUSH, 60))
            ).allocations.map { it.focus },
            listOf(Focus.PUSH, Focus.LEGS)
        )
    }

    @Test
    fun aConfigurationBuiltInTheWrongOrderIsRefusedRatherThanSilentlySorted() {
        val outOfOrder = assertThrows(IllegalArgumentException::class.java) {
            FocusPlan.Focused(listOf(Focus.PULL, Focus.PUSH))
        }

        assertTrue(
            "the value states its own order requirement instead of normalizing behind the caller's " +
                "back: ${outOfOrder.message}",
            outOfOrder.message!!.contains("vocabulary's own order")
        )
    }

    @Test
    fun customPercentagesMustSumToExactlyOneHundred() {
        val tooLittle = assertThrows(IllegalArgumentException::class.java) {
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.PULL, 30)))
        }
        assertTrue(
            "99% is not a plan: ${tooLittle.message}",
            tooLittle.message!!.contains("must sum to 100%")
        )

        val tooMuch = assertThrows(IllegalArgumentException::class.java) {
            FocusPlan.custom(
                listOf(
                    FocusAllocation(Focus.PUSH, 60),
                    FocusAllocation(Focus.PULL, 30),
                    FocusAllocation(Focus.LEGS, 20)
                )
            )
        }
        assertTrue(
            "and neither is 110%: ${tooMuch.message}",
            tooMuch.message!!.contains("must sum to 100%")
        )

        assertEquals(
            "100% over one focus is a valid configuration — it is a plan about one emphasis",
            FocusPlan.FULL_ALLOCATION,
            FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 100))).allocations.single().percent
        )
    }

    @Test
    fun aShareThatIsNotAPositiveWholePercentageIsRefused() {
        val zero = assertThrows(IllegalArgumentException::class.java) {
            FocusAllocation(Focus.PUSH, 0)
        }
        assertTrue(
            "a focus stated at 0% is a focus the user did not ask for, and the way to say that is to " +
                "leave it out: ${zero.message}",
            zero.message!!.contains("not an allocation")
        )

        val negative = assertThrows(IllegalArgumentException::class.java) {
            FocusPlan.custom(
                listOf(
                    FocusAllocation(Focus.PUSH, 120),
                    FocusAllocation(Focus.PULL, -20)
                )
            )
        }
        assertTrue(
            "and a negative share cannot smuggle a configuration past the hundred-percent rule: " +
                "${negative.message}",
            negative.message!!.contains("not an allocation") ||
                negative.message!!.contains("must sum to 100%")
        )
    }

    @Test
    fun aConfigurationsOwnStatesAreTheOnesItCannotBeBuiltWithout() {
        assertTrue(
            "a FOCUSED plan that names nothing is the BALANCED configuration, not an empty one",
            assertThrows(IllegalArgumentException::class.java) {
                FocusPlan.Focused(emptyList())
            }.message!!.contains("names the focuses it is built around")
        )
        assertTrue(
            "a focus is named once — the value is spoken to directly, because the factory would " +
                "canonicalize the duplicate away while the stored form must not",
            assertThrows(IllegalArgumentException::class.java) {
                FocusPlan.Focused(listOf(Focus.PUSH, Focus.PUSH))
            }.message!!.contains("at most once")
        )
        assertTrue(
            "and a custom configuration allocates a focus once",
            assertThrows(IllegalArgumentException::class.java) {
                FocusPlan.Custom(
                    listOf(FocusAllocation(Focus.PUSH, 50), FocusAllocation(Focus.PUSH, 50))
                )
            }.message!!.contains("one share")
        )
    }

    @Test
    fun thePercentageOfAFocusIsZeroWhenTheConfigurationDoesNotAllocateIt() {
        val configuration = FocusPlan.custom(listOf(FocusAllocation(Focus.PUSH, 100)))

        assertEquals(100, configuration.percentFor(Focus.PUSH))
        assertEquals(
            "a focus the configuration does not allocate has no share — not a share of zero that " +
                "someone could change later",
            0,
            configuration.percentFor(Focus.PULL)
        )
    }

    @Test
    fun aBalancedConfigurationIsNotAFocusedOneWithEverythingNamed() {
        // The distinction is real and it is what keeps the planner's own spread from being mistaken
        // for the user's: BALANCED states nothing, FOCUSED states the focuses.
        assertNotEquals(FocusPlan.Balanced, FocusPlan.Focused(Focus.entries.toList()))
        assertEquals(Goal.BALANCED, FocusPlan.Balanced.goal)
        assertEquals(Goal.FOCUSED, FocusPlan.Focused(Focus.entries.toList()).goal)
    }

    @Test
    fun aStructureCarriesTheConfigurationAndAChangeInItIsStructural() {
        val base = ProgramStructure.of(
            mode = ProgramMode.GENERATED,
            duration = ProgramDuration.Indefinite,
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            days = listOf(day())
        )
        val refocused = ProgramStructure.of(
            mode = ProgramMode.GENERATED,
            duration = ProgramDuration.Indefinite,
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            days = listOf(day()),
            focus = FocusPlan.focused(setOf(Focus.PUSH))
        )

        assertEquals(
            "§6 lists goals/focus among the changes that create a revision",
            listOf(ProgramStructureAspect.FOCUS),
            refocused.differencesFrom(base)
        )
        assertEquals(
            "and a plan built for a different goal is the same plan only in its days",
            base.days,
            refocused.days
        )
    }

    private fun day(): ProgramDay = ProgramDay(
        programDayId = ProgramDayId("day-1"),
        position = 1,
        type = ProgramDayType.TRAINING,
        name = null,
        exercises = listOf(
            ProgramExercise(
                programExerciseId = ProgramExerciseId("element-1"),
                exerciseId = "pushup",
                prescription = RepPrescription(listOf(10)),
                origin = ProgramExerciseOrigin.GENERATED
            )
        )
    )
}
