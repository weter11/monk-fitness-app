package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract tests for `ProgressionResolver` and the six pilot `ProgressionProfile` ladders.
 *
 * What they pin:
 *
 *  * a family's progression level is an abstract position on that family's own axis — the resolver
 *    returns the profile's own step for a level and never a universal reps delta;
 *  * the level never leaves `-2..+2`: a request past either boundary is HOLD, not a fifth level and
 *    not a substitute exercise;
 *  * the resolver answers only from the profile ladder and the caller's allowed exercise ids, so a
 *    variation the user disabled is unreachable — including by jumping over a disabled intermediate;
 *  * an explicitly declared per-profile volume fallback is the *only* route from "the requested
 *    variation is unavailable" to an adjustment, and a profile that declares none holds;
 *  * `MAINTAIN_STIMULUS` changes nothing, and `RECOVERY_LOAD` neither decrements nor destroys the
 *    stored level;
 *  * every id in every pilot ladder is an existing exercise id of that family in the library, and
 *    the resolver returns nothing else;
 *  * the three new production files stay pure domain code.
 */
class ProgressionResolverTest {

    // ------------------------------------------------------------------ fixtures

    private val pushups = PilotProgressionProfiles.pushups
    private val squats = PilotProgressionProfiles.squats
    private val lunges = PilotProgressionProfiles.lunges
    private val plank = PilotProgressionProfiles.plank
    private val pullups = PilotProgressionProfiles.pullups
    private val gluteBridge = PilotProgressionProfiles.gluteBridge

    private val levels = FamilyAdaptationState.MIN_LEVEL..FamilyAdaptationState.MAX_LEVEL

    private fun ladderIds(profile: ProgressionProfile): List<String> =
        profile.ladder.map { it.exerciseId }

    private fun allLadderExercises(profile: ProgressionProfile): Set<String> =
        ladderIds(profile).toSet()

    private fun resolve(
        profile: ProgressionProfile,
        level: Int,
        direction: AdaptiveAction,
        current: String? = null,
        allowedIds: Set<String> = allLadderExercises(profile)
    ): ProgressionResolution = ProgressionResolver.resolve(
        familyId = profile.familyId,
        level = level,
        direction = direction,
        currentExerciseId = current,
        allowedExerciseIds = allowedIds,
        profile = profile
    )

    // ------------------------------------------------------------------ boundaries

    /** Requirement 1: at -2 a reverse request has nowhere to go — HOLD, level preserved. */
    @Test
    fun theLowerBoundHoldsAReverseRequest() {
        PilotProgressionProfiles.all.forEach { profile ->
            val resolution = resolve(
                profile = profile,
                level = FamilyAdaptationState.MIN_LEVEL,
                direction = AdaptiveAction.REDUCE_STIMULUS,
                current = profile.stepAt(FamilyAdaptationState.MIN_LEVEL).exerciseId
            )

            assertEquals(profile.familyId, ProgressionOutcome.HOLD, resolution.outcome)
            assertEquals(profile.familyId, FamilyAdaptationState.MIN_LEVEL, resolution.level)
            assertNull(profile.familyId, resolution.exerciseId)
            assertEquals(profile.familyId, 0, resolution.adjustment)
        }
    }

    /** Requirement 2: at +2 an increase request cannot move higher — HOLD, level preserved. */
    @Test
    fun theUpperBoundHoldsAnIncreaseRequest() {
        PilotProgressionProfiles.all.forEach { profile ->
            val resolution = resolve(
                profile = profile,
                level = FamilyAdaptationState.MAX_LEVEL,
                direction = AdaptiveAction.INCREASE_STIMULUS,
                current = profile.stepAt(FamilyAdaptationState.MAX_LEVEL).exerciseId
            )

            assertEquals(profile.familyId, ProgressionOutcome.HOLD, resolution.outcome)
            assertEquals(profile.familyId, FamilyAdaptationState.MAX_LEVEL, resolution.level)
            assertNull(profile.familyId, resolution.exerciseId)
        }
    }

    /**
     * Requirement 13: the level is an input of the documented range, not something to clamp. A level
     * outside it is a caller error and is rejected loudly rather than silently coerced to a boundary.
     */
    @Test
    fun aLevelOutsideTheDocumentedRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            resolve(pushups, level = 3, direction = AdaptiveAction.INCREASE_STIMULUS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolve(pushups, level = -3, direction = AdaptiveAction.REDUCE_STIMULUS)
        }
    }

    /** The requested family and the profile it is resolved with must agree, or the answer would be fiction. */
    @Test
    fun aResolutionIsRefusedForAFamilyThatDoesNotOwnTheProfile() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgressionResolver.resolve(
                familyId = "squats",
                level = 0,
                direction = AdaptiveAction.INCREASE_STIMULUS,
                allowedExerciseIds = allLadderExercises(pushups),
                profile = pushups
            )
        }
    }

    // ------------------------------------------------------------------ family mapping

    /**
     * Requirement 3: the six pilot families have six different mappings. The axis and the ladder are
     * the family's own: a variation ladder, a duration ladder, a mixed one, and a corrective volume
     * ladder are all represented, and no two families share a ladder.
     */
    @Test
    fun eachPilotFamilyHasItsOwnAxisAndLadder() {
        assertEquals(ProgressionAxis.REP_VARIATION, pushups.axis)
        assertEquals(ProgressionAxis.REP_VARIATION, squats.axis)
        assertEquals(ProgressionAxis.REP_VARIATION, lunges.axis)
        assertEquals(ProgressionAxis.TIMER, plank.axis)
        assertEquals(ProgressionAxis.MIXED, pullups.axis)
        assertEquals(ProgressionAxis.CORRECTIVE, gluteBridge.axis)

        assertEquals(
            listOf(
                ProgressionStep("pushups_knee"),
                ProgressionStep("pushups", adjustment = -1),
                ProgressionStep("pushups"),
                ProgressionStep("pushups_wide"),
                ProgressionStep("decline_pushups")
            ),
            pushups.ladder
        )
        assertEquals(
            listOf(
                ProgressionStep("squats_sumo"),
                ProgressionStep("squats", adjustment = -1),
                ProgressionStep("squats"),
                ProgressionStep("cossack_squat"),
                ProgressionStep("squats_jump")
            ),
            squats.ladder
        )
        assertEquals(
            listOf(
                ProgressionStep("lunges_reverse"),
                ProgressionStep("lunges", adjustment = -1),
                ProgressionStep("lunges"),
                ProgressionStep("step_ups"),
                ProgressionStep("lunges_side")
            ),
            lunges.ladder
        )
        assertEquals(
            listOf(
                ProgressionStep("plank", adjustment = -2),
                ProgressionStep("plank", adjustment = -1),
                ProgressionStep("plank"),
                ProgressionStep("plank", adjustment = 1),
                ProgressionStep("plank", adjustment = 2)
            ),
            plank.ladder
        )
        assertEquals(
            listOf(
                ProgressionStep("hang"),
                ProgressionStep("pullups_chin"),
                ProgressionStep("pullups"),
                ProgressionStep("pullups_neutral"),
                ProgressionStep("pullups_wide")
            ),
            pullups.ladder
        )
        assertEquals(
            listOf(
                ProgressionStep("glute_bridge", adjustment = -2),
                ProgressionStep("glute_bridge", adjustment = -1),
                ProgressionStep("glute_bridge"),
                ProgressionStep("glute_bridge", adjustment = 1),
                ProgressionStep("glute_bridge", adjustment = 2)
            ),
            gluteBridge.ladder
        )

        // Not one shared mechanism: the timer family advances on a single exercise's duration, the
        // corrective family has no harder variation to reach, and the rep-variation families each
        // climb their own set of variations.
        assertEquals(setOf("plank"), plank.ladder.map { it.exerciseId }.toSet())
        assertEquals(setOf("glute_bridge"), gluteBridge.ladder.map { it.exerciseId }.toSet())
        assertEquals(6, PilotProgressionProfiles.all.map { it.ladder }.toSet().size)
    }

    /** Requirement 4: from the current exercise, an increase resolves to the family's next variation. */
    @Test
    fun anIncreaseMovesToTheNextVariationOfTheFamilyLadder() {
        val resolution = resolve(
            profile = pushups,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pushups"
        )

        assertEquals(ProgressionOutcome.STEP, resolution.outcome)
        assertEquals(1, resolution.level)
        assertEquals("pushups_wide", resolution.exerciseId)
        assertEquals(0, resolution.adjustment)
    }

    /** Requirement 5: from the current exercise, a reduction resolves to the previous variation. */
    @Test
    fun aReductionMovesToThePreviousVariationOfTheFamilyLadder() {
        // Level 0 down to -1 is the ladder's bridge step: the same exercise at reduced volume.
        val bridged = resolve(
            profile = pushups,
            level = 0,
            direction = AdaptiveAction.REDUCE_STIMULUS,
            current = "pushups"
        )
        assertEquals(ProgressionOutcome.STEP, bridged.outcome)
        assertEquals(-1, bridged.level)
        assertEquals("pushups", bridged.exerciseId)
        assertEquals(-1, bridged.adjustment)

        // And one level below it is the previous variation of the ladder.
        val variation = resolve(
            profile = pushups,
            level = -1,
            direction = AdaptiveAction.REDUCE_STIMULUS,
            current = "pushups"
        )
        assertEquals(ProgressionOutcome.STEP, variation.outcome)
        assertEquals(-2, variation.level)
        assertEquals("pushups_knee", variation.exerciseId)
        assertEquals(0, variation.adjustment)
    }

    /**
     * A ladder step may carry the low-level adjustment instead of a new variation: the bridge from
     * knee push-ups to standard push-ups is standard push-ups at reduced volume. The adjustment is
     * what the caller hands to the data layer's existing difficulty-adjustment mechanism.
     */
    @Test
    fun aLadderStepCanCarryALowLevelAdjustmentOnANewVariation() {
        val resolution = resolve(
            profile = pushups,
            level = -2,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pushups_knee"
        )

        assertEquals(ProgressionOutcome.STEP, resolution.outcome)
        assertEquals(-1, resolution.level)
        assertEquals("pushups", resolution.exerciseId)
        assertEquals(-1, resolution.adjustment)
    }

    /** The plank family progresses on duration only: both directions stay on the same exercise. */
    @Test
    fun thePlankFamilyProgressesOnDurationOnly() {
        val up = resolve(plank, level = 0, direction = AdaptiveAction.INCREASE_STIMULUS, current = "plank")
        assertEquals(ProgressionOutcome.STEP, up.outcome)
        assertEquals(1, up.level)
        assertEquals("plank", up.exerciseId)
        assertEquals(1, up.adjustment)

        val down = resolve(plank, level = 0, direction = AdaptiveAction.REDUCE_STIMULUS, current = "plank")
        assertEquals(ProgressionOutcome.STEP, down.outcome)
        assertEquals(-1, down.level)
        assertEquals("plank", down.exerciseId)
        assertEquals(-1, down.adjustment)
    }

    /**
     * The pull-up family is the mixed one: its easiest rung is the timer-based dead hang, and every
     * rung above it is a grip variation of the same exercise.
     */
    @Test
    fun thePullupFamilyMixesATimerRegressionWithGripVariations() {
        val regression = resolve(
            pullups,
            level = -1,
            direction = AdaptiveAction.REDUCE_STIMULUS,
            current = "pullups_chin"
        )
        assertEquals(ProgressionOutcome.STEP, regression.outcome)
        assertEquals(-2, regression.level)
        assertEquals("hang", regression.exerciseId)

        val progression = resolve(
            pullups,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pullups"
        )
        assertEquals(ProgressionOutcome.STEP, progression.outcome)
        assertEquals(1, progression.level)
        assertEquals("pullups_neutral", progression.exerciseId)
    }

    /**
     * The glute-bridge family has exactly one variation in the library, so its ladder is corrective
     * volume: the level moves, the exercise never does, and no harder variation is invented.
     */
    @Test
    fun theGluteBridgeFamilyProgressesOnCorrectiveVolumeOnly() {
        levels.forEach { level ->
            assertEquals("glute_bridge", gluteBridge.stepAt(level).exerciseId)
        }

        val up = resolve(gluteBridge, level = 0, direction = AdaptiveAction.INCREASE_STIMULUS, current = "glute_bridge")
        assertEquals(ProgressionOutcome.STEP, up.outcome)
        assertEquals(1, up.level)
        assertEquals("glute_bridge", up.exerciseId)
        assertEquals(1, up.adjustment)
    }

    // ------------------------------------------------------------------ custom configuration

    /**
     * Requirement 6 and the plan's step 6: with the military and decline push-up variations disabled,
     * no level, direction or current exercise can reach either of them. The family falls back to
     * volume on the variation the user actually enabled.
     */
    @Test
    fun disabledMilitaryAndDeclinePushupVariationsAreNeverReached() {
        val enabled = setOf("pushups_knee", "pushups", "pushups_wide")

        val resolution = resolve(
            profile = pushups,
            level = 1,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pushups_wide",
            allowedIds = enabled
        )
        assertEquals(ProgressionOutcome.FALLBACK, resolution.outcome)
        assertEquals(1, resolution.level)
        assertEquals("pushups_wide", resolution.exerciseId)
        assertEquals(1, resolution.adjustment)

        val disabled = setOf("pushups_military", "decline_pushups")
        levels.forEach { level ->
            AdaptiveAction.entries.forEach { direction ->
                ladderIds(pushups).forEach { current ->
                    val swept = resolve(pushups, level, direction, current = current, allowedIds = enabled)
                    assertTrue(
                        "pushups level=$level $direction current=$current returned ${swept.exerciseId}",
                        swept.exerciseId == null || swept.exerciseId !in disabled
                    )
                }
            }
        }
    }

    /**
     * Requirement 7: a disabled intermediate variation cannot be jumped over. `pushups_wide` (+1) is
     * disabled while `decline_pushups` (+2) stays allowed, and the resolver selects neither the
     * skipped variation nor the one above it — the profile's own fallback is the only answer.
     */
    @Test
    fun aDisabledIntermediateVariationIsNotJumpedOver() {
        val resolution = resolve(
            profile = pushups,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pushups",
            allowedIds = setOf("pushups_knee", "pushups", "decline_pushups")
        )

        assertEquals(ProgressionOutcome.FALLBACK, resolution.outcome)
        assertEquals(0, resolution.level)
        assertEquals("pushups", resolution.exerciseId)
        assertEquals(1, resolution.adjustment)

        // The same shape on a profile with no declared fallback: HOLD, and the reachable variation
        // two levels up is not substituted for the blocked one.
        val held = resolve(
            profile = squats,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "squats",
            allowedIds = setOf("squats", "squats_sumo", "squats_jump")
        )
        assertEquals(ProgressionOutcome.HOLD, held.outcome)
        assertEquals(0, held.level)
        assertNull(held.exerciseId)
    }

    /**
     * Requirement 8: the volume fallback is exactly as wide as a profile declares it. Push-ups
     * declare one; squats and lunges declare none, and the identical request shape holds for them.
     */
    @Test
    fun theVolumeFallbackExistsOnlyWhereTheProfileDeclaresIt() {
        assertNotNull("pushups declares the pilot volume fallback", pushups.fallbackStep)
        assertEquals(
            listOf("pushups"),
            PilotProgressionProfiles.all.filter { it.fallbackStep != null }.map { it.familyId }
        )

        val pushupFallback = resolve(
            profile = pushups,
            level = 1,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "pushups_wide",
            allowedIds = setOf("pushups_knee", "pushups", "pushups_wide")
        )
        assertEquals(ProgressionOutcome.FALLBACK, pushupFallback.outcome)

        val squatHold = resolve(
            profile = squats,
            level = 1,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "cossack_squat",
            allowedIds = setOf("squats", "squats_sumo", "cossack_squat")
        )
        assertEquals(ProgressionOutcome.HOLD, squatHold.outcome)
        assertNull(squatHold.exerciseId)

        val lungeHold = resolve(
            profile = lunges,
            level = 1,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "step_ups",
            allowedIds = setOf("lunges", "lunges_reverse", "step_ups")
        )
        assertEquals(ProgressionOutcome.HOLD, lungeHold.outcome)
        assertNull(lungeHold.exerciseId)
    }

    /**
     * The fallback is one low-level adjustment step on the variation the family currently sits on —
     * in either direction — and it never moves the level, because the level's own step was the thing
     * that was unavailable.
     */
    @Test
    fun theFallbackAdjustsTheCurrentVariationWithoutMovingTheLevel() {
        val reduced = resolve(
            profile = pushups,
            level = 1,
            direction = AdaptiveAction.REDUCE_STIMULUS,
            current = "pushups_wide",
            allowedIds = setOf("pushups_knee", "pushups_wide")
        )

        assertEquals(ProgressionOutcome.FALLBACK, reduced.outcome)
        assertEquals(1, reduced.level)
        assertEquals("pushups_wide", reduced.exerciseId)
        assertEquals(-1, reduced.adjustment)
    }

    /**
     * Requirement 9: no permitted fallback means HOLD. A family whose whole enabled set is the
     * variation it already performs cannot be moved onto another one — and it is not allowed to turn
     * that into a volume change the profile never declared.
     */
    @Test
    fun aProfileWithoutAFallbackHoldsWhenTheDesiredVariationIsUnavailable() {
        for (profile in PilotProgressionProfiles.all.filter { it.fallbackStep == null }) {
            for (level in levels) {
                for (direction in AdaptiveAction.entries) {
                    val requested = when (direction) {
                        AdaptiveAction.INCREASE_STIMULUS -> level + 1
                        AdaptiveAction.REDUCE_STIMULUS -> level - 1
                        else -> level
                    }
                    // A step that stays on the same exercise needs no other exercise to be enabled,
                    // so it is legitimately reachable and not the case under test here.
                    if (requested in levels &&
                        profile.stepAt(requested).exerciseId == profile.stepAt(level).exerciseId
                    ) {
                        continue
                    }

                    val resolution = resolve(
                        profile = profile,
                        level = level,
                        direction = direction,
                        current = profile.stepAt(level).exerciseId,
                        allowedIds = setOf(profile.stepAt(level).exerciseId)
                    )
                    assertEquals(
                        "${profile.familyId} level=$level $direction must hold",
                        ProgressionOutcome.HOLD,
                        resolution.outcome
                    )
                }
            }
        }
    }

    /**
     * Requirement 10: impossible progression is HOLD — no clamp to a boundary, no substitution of a
     * different exercise, and no invented target when the family state cannot be placed on the ladder.
     */
    @Test
    fun impossibleProgressionHoldsInsteadOfClampingOrSubstituting() {
        PilotProgressionProfiles.all.forEach { profile ->
            val resolution = resolve(
                profile = profile,
                level = 0,
                direction = AdaptiveAction.INCREASE_STIMULUS,
                current = null,
                allowedIds = emptySet()
            )
            assertEquals(profile.familyId, ProgressionOutcome.HOLD, resolution.outcome)
            assertEquals(profile.familyId, 0, resolution.level)
            assertNull(profile.familyId, resolution.exerciseId)
            assertEquals(profile.familyId, 0, resolution.adjustment)
        }

        // The caller's current exercise is not on this profile's ladder, so it cannot be adjusted.
        val offLadder = resolve(
            profile = pushups,
            level = 1,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            current = "diamond_pushups",
            allowedIds = setOf("pushups", "diamond_pushups")
        )
        assertEquals(ProgressionOutcome.HOLD, offLadder.outcome)
        assertEquals(1, offLadder.level)
        assertNull(offLadder.exerciseId)
    }

    // ------------------------------------------------------------------ maintain and recovery

    /** Requirement 14: `MAINTAIN_STIMULUS` is not a progression move at any level. */
    @Test
    fun maintainStimulusChangesNothing() {
        PilotProgressionProfiles.all.forEach { profile ->
            levels.forEach { level ->
                val resolution = resolve(
                    profile = profile,
                    level = level,
                    direction = AdaptiveAction.MAINTAIN_STIMULUS,
                    current = profile.stepAt(level).exerciseId
                )

                assertEquals(profile.familyId, ProgressionOutcome.HOLD, resolution.outcome)
                assertEquals(profile.familyId, level, resolution.level)
                assertNull(profile.familyId, resolution.exerciseId)
                assertEquals(profile.familyId, 0, resolution.adjustment)
            }
        }
    }

    /**
     * Requirement 15: `RECOVERY_LOAD` is not a progression-level move. It never decrements the
     * stored level, never destroys it, and orders no target here — the recovery load itself belongs
     * to the recovery profile, which is not this layer's decision.
     */
    @Test
    fun recoveryLoadNeitherDecrementsNorDestroysTheStoredLevel() {
        PilotProgressionProfiles.all.forEach { profile ->
            levels.forEach { level ->
                val resolution = resolve(
                    profile = profile,
                    level = level,
                    direction = AdaptiveAction.RECOVERY_LOAD,
                    current = profile.stepAt(level).exerciseId
                )

                assertEquals(profile.familyId, ProgressionOutcome.HOLD, resolution.outcome)
                assertEquals("${profile.familyId} level=$level", level, resolution.level)
                assertNull(profile.familyId, resolution.exerciseId)
            }
        }

        assertEquals(2, resolve(pushups, 2, AdaptiveAction.RECOVERY_LOAD, current = "pushups_military").level)
        assertEquals(-2, resolve(pushups, -2, AdaptiveAction.RECOVERY_LOAD, current = "pushups_knee").level)
    }

    // ------------------------------------------------------------------ invariants over all inputs

    /**
     * Requirements 11, 12 and 13, swept over every profile, level, direction, ladder exercise as the
     * caller's current exercise, and every subset of the ladder as the caller's allowed set: the
     * resolver returns only ladder exercises the caller allowed, and its level algebra is always the
     * one the outcome claims.
     */
    @Test
    fun noResolutionEverLeavesTheLadderOrTheAllowedSet() {
        var checked = 0

        PilotProgressionProfiles.all.forEach { profile ->
            val ladder = ladderIds(profile)
            val exercised = ladder.toSet()

            subsetsOf(ladder).forEach { allowedIds ->
                levels.forEach { level ->
                    AdaptiveAction.entries.forEach { direction ->
                        ladder.forEach { current ->
                            val resolution = resolve(profile, level, direction, current = current, allowedIds = allowedIds)
                            val where = "${profile.familyId} level=$level $direction current=$current allowed=$allowedIds"

                            checked++
                            assertEquals(where, profile.familyId, resolution.familyId)
                            assertTrue(
                                "$where produced level ${resolution.level}",
                                resolution.level in levels
                            )

                            when (resolution.outcome) {
                                ProgressionOutcome.HOLD -> {
                                    assertEquals(where, level, resolution.level)
                                    assertNull(where, resolution.exerciseId)
                                    assertEquals(where, 0, resolution.adjustment)
                                }
                                ProgressionOutcome.FALLBACK -> {
                                    assertEquals(where, level, resolution.level)
                                    assertEquals(where, profile.stepAt(level).exerciseId, resolution.exerciseId)
                                    assertTrue("$where adjusted by ${resolution.adjustment}", resolution.adjustment != 0)
                                }
                                ProgressionOutcome.STEP -> {
                                    assertEquals(
                                        where,
                                        profile.stepAt(resolution.level).exerciseId,
                                        resolution.exerciseId
                                    )
                                    assertTrue(
                                        "$where stepped from $level to ${resolution.level}",
                                        resolution.level == level + 1 || resolution.level == level - 1
                                    )
                                }
                            }

                            resolution.exerciseId?.let { target ->
                                assertTrue("$where returned $target, not a ladder exercise", target in exercised)
                                assertTrue("$where returned $target, not in $allowedIds", target in allowedIds)
                            }
                        }
                    }
                }
            }
        }

        assertEquals(6 * 32 * 5 * 4 * 5, checked)
    }

    /** The resolver is a pure function: the same request always resolves to the same value. */
    @Test
    fun theSameRequestAlwaysResolvesToTheSameValue() {
        val request = { resolve(pushups, level = 0, direction = AdaptiveAction.INCREASE_STIMULUS, current = "pushups") }

        val first = request()
        val second = request()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    // ------------------------------------------------------------------ pilot profiles and the library

    /**
     * Requirement 16: all six pilot families have a profile, and every ladder id is an existing
     * exercise id of that family in the library. The library is read from the generator's catalog
     * rather than restated here, so a ladder id that stops existing — or moves to another family —
     * fails this test instead of drifting silently out of the exercise library.
     */
    @Test
    fun everyPilotLadderUsesExistingExerciseIdsOfItsOwnFamily() {
        val library = exerciseLibrary()

        assertEquals(
            setOf("pushups", "squats", "lunges", "plank", "pullups", "glute_bridge"),
            PilotProgressionProfiles.all.map { it.familyId }.toSet()
        )

        PilotProgressionProfiles.all.forEach { profile ->
            assertEquals(
                "${profile.familyId}: a ladder maps every level exactly once",
                levels.count(),
                profile.ladder.size
            )
            assertEquals(
                "${profile.familyId} resolves to its own profile",
                profile,
                PilotProgressionProfiles.forFamily(profile.familyId)
            )
            ladderIds(profile).forEach { id ->
                assertEquals(
                    "$id must be an existing library exercise of family ${profile.familyId}",
                    profile.familyId,
                    library[id]
                )
            }
        }

        assertNull(PilotProgressionProfiles.forFamily("no_such_family"))
    }

    /**
     * The architectural boundary, mechanically: the resolver, the profile model and the pilot
     * profiles are pure domain Kotlin — no Android, data layer, persistence, UI, clock, random
     * source, workout generator or difficulty-adjustment call, and no Task 8+ symbol.
     */
    @Test
    fun theResolverAndItsProfilesArePureDomainCode() {
        val forbidden = listOf(
            "System.currentTimeMillis", "System.nanoTime", "LocalDate", "LocalDateTime", "Instant",
            "Random", "UUID", "kotlin.time", "java.util.concurrent",
            "import android.", "import androidx.", "import com.monkfitness.app.data.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "WorkoutGenerator", "MainViewModel", "applyDifficultyAdjustment", "Room", "DataStore",
            "FamilyProgressionState", "AdaptiveDecisionRecord"
        )

        listOf("ProgressionProfile.kt", "ProgressionResolver.kt", "PilotProgressionProfiles.kt").forEach { name ->
            val source = File(adaptiveSourceDir, name)
            assertTrue("expected $name at ${source.absolutePath}", source.isFile)
            val hits = forbidden.filter { source.readText().contains(it) }
            assertTrue("$name must stay pure domain code, found: $hits", hits.isEmpty())
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Every subset of [ids], so the allowed-set sweep covers the user's whole configuration space. */
    private fun subsetsOf(ids: List<String>): List<Set<String>> =
        (0 until (1 shl ids.size)).map { mask ->
            ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }

    /**
     * The exercise catalogue as the library defines it: id to family id, read from the generator's
     * own entries (`baseRepExercise` / `baseTimerExercise`).
     */
    private fun exerciseLibrary(): Map<String, String> {
        val source = File("src/main/java/com/monkfitness/app/domain/usecase/WorkoutGenerator.kt")
            .let { file ->
                if (file.isFile) file
                else File("app/src/main/java/com/monkfitness/app/domain/usecase/WorkoutGenerator.kt")
            }
        assertTrue("expected the exercise catalogue at ${source.absolutePath}", source.isFile)

        val entries = CATALOGUE_ENTRY.findAll(source.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
        assertTrue("expected to read the exercise catalogue, found ${entries.size} entries", entries.size > 50)
        return entries
    }

    private val adaptiveSourceDir = File("src/main/java/com/monkfitness/app/domain/adaptive")
        .let { dir -> if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app/domain/adaptive") }

    private val CATALOGUE_ENTRY = Regex("""base(?:Rep|Timer)Exercise\(\s*"([A-Za-z0-9_]+)",\s*"([A-Za-z0-9_]+)"""")
}
