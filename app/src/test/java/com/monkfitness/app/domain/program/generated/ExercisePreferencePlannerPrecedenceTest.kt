package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9's **priority order**, measured on the selector that already implements it — with §30 step 28's
 * **real** user preference as the input, so the order is proved end to end rather than asserted about
 * the code that reads it.
 *
 * ```text
 * user choice
 * >
 * hard execution constraints
 * >
 * adaptive preference
 * >
 * progression need
 * >
 * recency/diversity
 * >
 * deterministic tie-break
 * ```
 *
 * ### What this suite does NOT do
 *
 * It does not restate or re-derive the ranking. `ExerciseSelector` is untouched by P28 and this suite
 * calls it exactly as P10 wrote it, so a failure here means **the input reached the selector wrongly** —
 * which is P28's only possible contribution — rather than that the algorithm is wrong. Every test drives
 * `ExerciseSelector.ranked` directly and reads the order it returns.
 *
 * ### Why each level is stated as its own test
 *
 * The order is only meaningful if a *lower* level cannot outrank a *higher* one, and that claim is
 * falsifiable per pair: construct two candidates where one wins on level N and the other on level N+1, and
 * assert level N decides. A single "the order is correct" test would pass for any permutation that happened
 * to satisfy the one fixture it used; these are built so that swapping any two comparisons fails at least
 * one of them.
 *
 * The two levels P10 recorded as **deliberate absences** — *progression need* (the Adaptive Engine's own
 * result, §30 step 11) and nothing at all — are asserted as absences here for the same reason P10 recorded
 * them: the level must stay empty rather than be filled with an invented stand-in.
 */
class ExercisePreferencePlannerPrecedenceTest {

    // ------------------------------------------------------------------ 1. user choice, and its order

    @Test
    fun theUsersFirstPreferenceIsTheOneTheSelectorPicksFirst() {
        // `pull-row` and `pull-up` are both PULL and both usable with full equipment, so nothing else in
        // §9's order separates them — only the preference does. Without a preference the deterministic
        // tie-break alone would put `pull-row` first (its id sorts lower), which is exactly the plan this
        // assertion rules out.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = ExercisePreference.of("pull-up").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals(
            "§9's first level is the user's own preference, so the exercise they named first is chosen " +
                "first even though its id sorts later than the alternative",
            "pull-up",
            ranked.first().exerciseId
        )
    }

    @Test
    fun theOrderWithinThePreferenceIsTheUsersOrderAndNotTheCatalogueOrder() {
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                // Deliberately the reverse of the catalogue's id order: `pull-up` sorts after `pull-row`.
                userPreferred = ExercisePreference.of("pull-up", "pull-row").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals(
            "the preference is an ORDER (§9's 'user choice'), and the selector reads it by index — so " +
                "the second-named exercise is chosen only after the first-named one",
            listOf("pull-up", "pull-row"),
            ranked.map { candidate -> candidate.exerciseId }
        )
    }

    @Test
    fun aSecondOperationOverAnUnchangedPreferenceSeesTheSameOrder() {
        // The preference is not consumed by being read once: a preview and the generate the user is shown
        // it must rank identically, or the two buttons would plan differently (§33's *no silent
        // substitution*, applied to §9's order).
        val preferences = GeneratedPlannerRig.preferences(
            userPreferred = ExercisePreference.of("pull-up", "pull-row").exerciseIds
        )
        val request = GeneratedPlannerRig.request(preferences = preferences)

        val first = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())
        val second = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals("two rankings over one unchanged request are the same ranking", first, second)
    }

    // ------------------------------------------------------------------ 2. hard constraints outrank the preference

    @Test
    fun aPreferredExerciseTheEquipmentForbidsIsNeverSelected() {
        // §9's order states *user choice > hard execution constraints*, and the selector honours that by
        // applying the constraint FIRST: `usableCandidatesFor` removes everything the equipment cannot
        // support, and only the survivors are ranked at all. `pull-up` needs a bar the user does not
        // declare here, so preferring it cannot make it selectable — which is the whole meaning of *"hard
        // execution constraints must never be silently violated"*.
        val request = GeneratedPlannerRig.request(
            availableEquipment = setOf(RigEquipment.DUMBBELLS),
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = ExercisePreference.of("pull-up").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertFalse(
            "the preferred exercise needs equipment the user did not declare, so no amount of preference " +
                "puts it in the ranking at all",
            ranked.any { candidate -> candidate.exerciseId == "pull-up" }
        )
        assertEquals(
            "and the level the constraint removed is gone from `usableCandidatesFor` too, not filtered " +
                "after ranking",
            listOf("pull-row"),
            request.usableCandidatesFor(Focus.PULL).map { candidate -> candidate.exerciseId }
        )
    }

    @Test
    fun theSamePreferenceSelectsTheExerciseOnceTheEquipmentAllowsIt() {
        // The control for the test above, and the reason that one is meaningful: the *only* difference is
        // the declared equipment, so the previous failure cannot be explained by the preference being
        // ignored outright.
        val request = GeneratedPlannerRig.request(
            availableEquipment = GeneratedPlannerRig.ALL_EQUIPMENT + RigEquipment.PULL_UP_BAR,
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = ExercisePreference.of("pull-up").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals(
            "with the bar declared the same preference now decides, which is what makes the constraint " +
                "test above a constraint result rather than a preference that never worked",
            "pull-up",
            ranked.first().exerciseId
        )
    }

    @Test
    fun aPreferredExerciseInAnUnimplementedPrescriptionDimensionIsNeverSelected() {
        // The other hard constraint: `core-weighted-plank` is prescribed in a dimension §10 names and does
        // not implement, so it is unusable whatever the user prefers. A preference cannot make an
        // unprescribable exercise prescribable.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = ExercisePreference.of("core-weighted-plank").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.CORE, emptyMap(), emptyMap())

        assertFalse(
            "§10's unimplemented dimension is a hard constraint like equipment, so preferring the " +
                "exercise does not put it in the ranking",
            ranked.any { candidate -> candidate.exerciseId == "core-weighted-plank" }
        )
    }

    // ------------------------------------------------------------------ 3. the preference outranks the adaptive one

    @Test
    fun theUsersPreferenceOutranksAnAdaptivePreferenceForTheOtherExercise() {
        // §9's third level. `GenerationPreferences` states the two lists separately, so "the user outranks
        // the adaptive layer" is a fact of the input rather than an assumption — and the input is exactly
        // what P28 now produces for the first list.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = listOf("pull-row"),
                adaptivePreferred = listOf("pull-up")
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals(
            "the user's own choice outranks the adaptive layer for the exercise it names, even though " +
                "the adaptive layer asked for the other one",
            "pull-row",
            ranked.first().exerciseId
        )
    }

    @Test
    fun anAdaptivePreferenceStillRanksTheExercisesTheUserDidNotName() {
        // The level is *below* the user's, not removed by it: among candidates the user did not name, the
        // adaptive preference decides. Without this, the previous test would also pass if the adaptive list
        // had been dropped entirely — the two together are what pin the *relative* order of §9's first
        // and third levels.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = listOf("push-pushup"),
                adaptivePreferred = listOf("push-dip")
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PUSH, emptyMap(), emptyMap())

        assertEquals(
            "the user names push-pushup for this focus and the adaptive layer names push-dip; both are " +
                "usable, and §9's first level outranks the third",
            "push-pushup",
            ranked.first().exerciseId
        )
        assertEquals(
            "and the adaptive preference still decides among the rest, ahead of the id tie-break which " +
                "would also have chosen push-dip — so the position is the adaptive level's, not luck",
            "push-dip",
            ranked[1].exerciseId
        )
    }

    // ------------------------------------------------------------------ 4. progression need stays absent

    @Test
    fun theProgressionLevelStaysEmptyRatherThanHoldingAnInventedStandIn() {
        // §30 step 11 owns progression; P10 recorded the level as empty on purpose. If this stage had
        // quietly filled it — with a recency heuristic, a family counter or anything else — the order would
        // stop being §9's and would be a new algorithm wearing its name. The selector's comparison list is
        // the place that is observable.
        val source = File("src/main/java/com/monkfitness/app/domain/program/generated")
            .let { dir -> if (dir.isDirectory) dir else File("app/$dir") }
            .resolve("ExerciseSelector.kt")
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        assertFalse(
            "§9's progression level must stay unmodelled at this stage: nothing in the selector may " +
                "compare a progression signal",
            Regex("""progression|Progression""").containsMatchIn(source)
        )
        assertEquals(
            "and the selector still holds exactly the six comparisons §9's order names — the two that are " +
                "absences included, with nothing in front of user choice and nothing behind the tie-break",
            6,
            Regex("""\{ candidate: GenerationCandidate<E> ->""").findAll(source).count()
        )
    }

    // ------------------------------------------------------------------ 5. recency, diversity, tie-break

    @Test
    fun diversityOutranksTheIdTieBreakAmongExercisesThatShareAFamily() {
        // §9's *recency/diversity* level, above the deterministic tie-break. `push-pushup` and `push-plank`
        // share the `push-horizontal` family, and they tie on every level above: neither is preferred by
        // the user, neither by the adaptive layer, and neither is in the recent list. The only things that
        // separate them are this cycle's use counts and the family balance — and both point the same way,
        // so the pair also pins that neither level is being read in the wrong order relative to the other.
        //
        // `push-dip` is the control: a different family, unused this cycle and unused by its family, so it
        // is best on all three and is expected first — which is what makes the other two positions a
        // statement about diversity rather than about the list happening to start that way.
        val request = GeneratedPlannerRig.request()

        val ranked = ExerciseSelector.ranked(
            request = request,
            focus = Focus.PUSH,
            usesThisCycle = mapOf("push-pushup" to 2),
            familyUsesThisCycle = mapOf("push-horizontal" to 2)
        )

        assertEquals(
            "unused this cycle, unused family, unused recently — the best candidate on every lower level",
            "push-dip",
            ranked.first().exerciseId
        )
        assertEquals(
            "`push-plank` and `push-pushup` tie on preference and on recency; diversity and family balance " +
                "put the unused one first, ahead of the id tie-break which would have chosen push-plank " +
                "anyway — so the pair is ordered by use count and the whole ranking is read as a ranking",
            listOf("push-dip", "push-plank", "push-pushup"),
            ranked.map { candidate -> candidate.exerciseId }
        )
    }

    @Test
    fun anExerciseNeverUsedRecentlyOutranksOneUsedRecentlyAndTheOrderIsTotal() {
        // Recency is an *avoidance* rule, and it sits below diversity. The recent list is "most recent
        // first", so it is read backwards: an exercise absent from it has not been used at all and is the
        // best candidate, and the entry at index 0 is the worst.
        //
        // `push-plank` and `push-pushup` tie on every level above recency here — no preference, no
        // adaptive preference, no use this cycle, same family — so the whole order is decided by recency
        // and the tie-break together, which is exactly the claim.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(recent = listOf("push-pushup", "push-plank"))
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PUSH, emptyMap(), emptyMap())

        assertEquals(
            "never used recently sorts first, then the oldest entry, and the entry used most recently " +
                "sorts last — read backwards, as 'most recent first' requires",
            listOf("push-dip", "push-plank", "push-pushup"),
            ranked.map { candidate -> candidate.exerciseId }
        )
        assertEquals(
            "and the last entry is the one at index 0 of the recent list, which is the most recent",
            "push-pushup",
            ranked.last().exerciseId
        )
    }

    @Test
    fun withNoPreferenceAtAllTheOrderIsTheTieBreakAlone() {
        // The absence case, and it is the honest control for every test above: with `ExercisePreference.NONE`
        // the selector falls through all six levels to the canonical id, ascending. It does **not** fall
        // back to any built-in ranking — which is the claim that would break if `NONE` were ever read as
        // "every exercise, in catalogue order".
        val request = GeneratedPlannerRig.request(
            preferences = GenerationPreferences.NONE
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertEquals(
            "no preference, no adaptive preference, no history, one focus, two candidates: only the " +
                "deterministic tie-break is left, and it sorts by the canonical id ascending",
            listOf("pull-row", "pull-up"),
            ranked.map { candidate -> candidate.exerciseId }
        )
        assertTrue(
            "and `NONE` really is the all-defaults value this stage relies on, not a populated ranking",
            GenerationPreferences.NONE.userPreferredExerciseIds.isEmpty()
        )
    }

    @Test
    fun aPreferenceThatNamesAnExerciseFromAnotherFocusNeverWins() {
        // A preference is not a candidate list. Naming a PUSH exercise while planning PULL must not make it
        // selectable — `usableCandidatesFor` filters by focus first, so the answer is simply absent from
        // the ranking rather than ranked last. This is the boundary that keeps a preference from widening
        // anything.
        val request = GeneratedPlannerRig.request(
            preferences = GeneratedPlannerRig.preferences(
                userPreferred = ExercisePreference.of("push-pushup").exerciseIds
            )
        )

        val ranked = ExerciseSelector.ranked(request, Focus.PULL, emptyMap(), emptyMap())

        assertFalse(
            "a preference names what the user would rather see; it does not add an exercise to a focus " +
                "it does not train (§9's focus membership is a boundary, not a preference)",
            ranked.any { candidate -> candidate.exerciseId == "push-pushup" }
        )
        assertEquals(
            "so the PULL ranking is decided entirely by the levels below the one that had nothing to say",
            listOf("pull-row", "pull-up"),
            ranked.map { candidate -> candidate.exerciseId }
        )
    }
}

/** The suite reads one production source from the repository root or from `app/`, as the other arch suites do. */
private typealias File = java.io.File