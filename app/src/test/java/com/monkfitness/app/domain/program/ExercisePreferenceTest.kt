package com.monkfitness.app.domain.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * §30 step 28's [ExercisePreference] — the value itself, and the three operations a user performs on it.
 *
 * ### What makes this suite worth writing
 *
 * A `List<String>` would pass almost every test a user-facing ranking could be given, and fail the ones
 * that matter. "Most preferred first" is only a fact if the order is preserved; "no duplicates" is only a
 * fact if the type refuses them; "no preference is not a default ranking" is only a fact if the type can
 * *express* an empty one without it being read as something else. Each of those is a refusal a bare list
 * has no way to make, so each is asserted here as a refusal rather than as a value.
 *
 * Every test names the failure mode it rules out, because the failure modes are all silent: a duplicated
 * entry, a reordered list or an absent preference produces a screen that looks finished and a plan that is
 * quietly not what the user asked for.
 */
class ExercisePreferenceTest {

    // ------------------------------------------------------------------ the order is the value

    @Test
    fun theUsersOrderIsTheValueAndNothingReordersIt() {
        val stated = ExercisePreference.of("muscle-ups", "pull-ups", "dips")

        assertEquals(
            "§9's 'user choice' is an ORDER — the list is the ranking, first is most preferred, and nothing " +
                "in this type re-sorts it (which is why it is not a Set and not a sorted copy)",
            listOf("muscle-ups", "pull-ups", "dips"),
            stated.exerciseIds
        )
        assertEquals(
            "the reversed statement is a different value, not an equal one re-read",
            stated,
            ExercisePreference.of("muscle-ups", "pull-ups", "dips")
        )
        assertFalse(
            "and deliberately not equal to its own reversal",
            stated == ExercisePreference.of("dips", "pull-ups", "muscle-ups")
        )
    }

    @Test
    fun reorderingAnEntryChangesTheExactPreferenceOrder() {
        val stated = ExercisePreference.of("muscle-ups", "pull-ups", "dips")

        assertEquals(
            "moving the third entry to the front is a re-ranking, and the other two keep their relative " +
                "order",
            listOf("dips", "muscle-ups", "pull-ups"),
            stated.moved("dips", 1).exerciseIds
        )
        assertEquals(
            "moving the first entry to the end is a different re-ranking, and it is the control for the " +
                "one above: the two are the same set and opposite statements",
            listOf("pull-ups", "dips", "muscle-ups"),
            stated.moved("muscle-ups", 3).exerciseIds
        )
        assertEquals(
            "moving it to its own position changes nothing, which is what makes a disabled move button " +
                "safe rather than a silent rewrite",
            stated,
            stated.moved("pull-ups", 2)
        )
    }

    @Test
    fun removingAnEntryLeavesEveryOtherPositionWhereItWas() {
        val stated = ExercisePreference.of("muscle-ups", "pull-ups", "dips")

        assertEquals(
            "remove the middle entry and the two that remain keep their order and their spacing — " +
                "removing a preference is not a re-ranking of the others",
            listOf("muscle-ups", "dips"),
            stated.without("pull-ups").exerciseIds
        )
        assertEquals(
            "removing an exercise the preference does not name changes nothing",
            stated,
            stated.without("handstand")
        )
        assertTrue(
            "and removing the last entry leaves the stated absence rather than an empty ranking",
            ExercisePreference.of("pull-ups").without("pull-ups").isEmpty
        )
    }

    @Test
    fun preferringAnExerciseAddsItAsTheLeastPreferredAndNeverTwice() {
        val stated = ExercisePreference.of("muscle-ups")

        assertEquals(
            "a new entry is appended: a screen may not invent a position the user did not state, and the " +
                "least preferred is the only position that is not a judgement",
            listOf("muscle-ups", "pull-ups"),
            stated.preferring("pull-ups").exerciseIds
        )
        assertEquals(
            "and preferring one that is already named is a no-op rather than a second entry, because a " +
                "duplicate would give 'most preferred' two answers at one position",
            stated,
            stated.preferring("muscle-ups")
        )
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun theSameExerciseCannotBePreferredTwice() {
        // Two paths, because there are two ways in: the constructor a caller reaches directly, and the
        // `preferring` operation a screen reaches through. Refusing only the first would leave the second
        // as a hole.
        try {
            ExercisePreference.of("pull-ups", "pull-ups")
            fail("expected the duplicate to be refused")
        } catch (refused: IllegalArgumentException) {
            assertTrue(
                "the refusal says the order would have two answers at one position: ${refused.message}",
                refused.message!!.contains("preferred once")
            )
        }
        assertEquals(
            "and the operation that could have produced one reports the unchanged value instead",
            ExercisePreference.of("pull-ups", "dips"),
            ExercisePreference.of("pull-ups", "dips").preferring("pull-ups")
        )
    }

    @Test
    fun aBlankExerciseNameIsRefusedRatherThanStored() {
        try {
            ExercisePreference.of("pull-ups", "   ")
            fail("expected the blank name to be refused")
        } catch (refused: IllegalArgumentException) {
            assertTrue(
                "a blank name is not an exercise, and storing one would put an unroutable id into the " +
                    "request: ${refused.message}",
                refused.message!!.contains("blank")
            )
        }
    }

    @Test
    fun anOutOfRangePositionIsRefusedRatherThanClamped() {
        val stated = ExercisePreference.of("muscle-ups", "pull-ups", "dips")

        for (position in listOf(0, 4, -1)) {
            try {
                stated.moved("dips", position)
                fail("expected position $position to be refused")
            } catch (refused: IllegalArgumentException) {
                assertTrue(
                    "the refusal names the range rather than quietly clamping, because a clamped move is a " +
                        "silent success the user cannot see (§33): ${refused.message}",
                    refused.message!!.contains("1..3")
                )
            }
        }
        try {
            stated.moved("handstand", 1)
            fail("expected moving an unnamed exercise to be refused")
        } catch (refused: IllegalArgumentException) {
            assertTrue(
                "moving an exercise this preference does not name is a caller bug and says so",
                refused.message!!.contains("does not name")
            )
        }
    }

    // ------------------------------------------------------------------ absence

    @Test
    fun noPreferenceIsEmptyAndNeverAnImplicitRanking() {
        assertTrue(
            "`NONE` states that the user named nothing — it is empty",
            ExercisePreference.NONE.isEmpty
        )
        assertEquals("with nothing in it", 0, ExercisePreference.NONE.size)
        assertEquals(
            "and it is equal to a preference built from no ids, because there is no other state to be in",
            ExercisePreference.NONE,
            ExercisePreference(emptyList())
        )
        assertEquals(
            "which is NOT a ranking of the catalogue: there is no built-in list anywhere in this type, so " +
                "an empty preference cannot be read as 'every exercise in some order'",
            emptyList<String>(),
            ExercisePreference.NONE.exerciseIds
        )
    }

    @Test
    fun membershipIsAskedAsAPresenceAndNeverAsAPosition() {
        val stated = ExercisePreference.of("muscle-ups", "pull-ups")

        assertTrue("a named exercise is preferred", "pull-ups" in stated)
        assertFalse("an unnamed one is not", "dips" in stated)
        assertTrue(
            "membership says nothing about rank: being preferred is the whole of what it answers, and the " +
                "position is read from the list itself",
            "pull-ups" in stated
        )
    }
}