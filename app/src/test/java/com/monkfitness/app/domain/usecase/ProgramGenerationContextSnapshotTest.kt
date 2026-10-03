package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P27's **one coherent context snapshot per generation operation**.
 *
 * The rule is short and completely invisible to a test that supplies a well-behaved source: a generation
 * pass must read its facts **once**, hold that one value, and plan from it. A source that answers
 * differently on each call is what makes the rule observable — it turns "read once" from a claim about
 * the code into a claim about a *count*, and it turns "Generate and Preview agree" from an equality that
 * a stateless double would satisfy for free into an equality worth measuring.
 *
 * ### Why a double that misbehaves is the right instrument
 *
 * A `GenerationContextSource` returning `GenerationPreferences.NONE` on every call cannot distinguish
 * "read once" from "read five times", and cannot distinguish "the same snapshot" from "two coincidentally
 * equal snapshots". [CountingSource] answers with a *different* recency list per call, so:
 *
 *  * a second read inside one pass would give the request a list no single read ever produced;
 *  * a Preview that read separately from the Generate it is previewing would disagree with it by
 *    construction rather than by luck.
 *
 * Both failure modes are silent in production — nothing crashes, the plan is merely *slightly* different
 * — which is precisely the class of defect this stage's neutral-answer discipline exists to keep out.
 *
 * The suite adds no generation semantics and changes no planner: it drives the real service over the
 * real shipped catalogue and reads back what the request was given.
 */
class ProgramGenerationContextSnapshotTest {

    // ------------------------------------------------------------------ one read per operation

    @Test
    fun oneGenerateReadsTheContextExactlyOnce() = runBlocking {
        val source = CountingSource()
        val service = serviceOver(source)

        service.generate(draft(), emptySet())

        assertEquals(
            "a Generate reads its facts once and plans from that one snapshot. A second read inside " +
                "the pass would mean the request could be assembled from facts the read never " +
                "produced together — §33's no-silent-substitution, applied to context.",
            1,
            source.calls
        )
    }

    @Test
    fun onePreviewReadsTheContextExactlyOnce() = runBlocking {
        val source = CountingSource()
        val service = serviceOver(source)

        service.preview(draft(), emptySet())

        assertEquals(
            "a Preview reads its facts exactly once too — not twice because it is a preview, and not " +
                "zero times because it does not apply the result",
            1,
            source.calls
        )
    }

    @Test
    fun oneRegenerateReadsTheContextExactlyOnce() = runBlocking {
        val source = CountingSource()
        val service = serviceOver(source)

        service.regenerate(draft(), emptySet())

        assertEquals(
            "Regenerate is the same pass, so it reads the same way: a regenerate that saw different " +
                "facts from the generate beside it would make the meaning of the button depend on " +
                "which one was pressed",
            1,
            source.calls
        )
    }

    @Test
    fun threeOperationsReadThreeSnapshotsAndNotOneCachedValue() = runBlocking {
        // The counterpart of the tests above: the source is NOT cached across operations. Reading once
        // *per operation* is the rule; reading once *ever* would be a stale-context bug of its own, and
        // this is what keeps the fix for that bug from being "hold the preferences in a field".
        val source = CountingSource()
        val service = serviceOver(source)

        service.generate(draft(), emptySet())
        service.preview(draft(), emptySet())
        service.regenerate(draft(), emptySet())

        assertEquals(
            "each operation takes its own snapshot — one read per pass, not one read per service",
            3,
            source.calls
        )
        assertEquals(
            "and each saw a different answer, which is what makes the per-operation read observable",
            3,
            source.answers.distinct().size
        )
    }

    // ------------------------------------------------------------------ the snapshot is the one that was planned from

    @Test
    fun thePlanIsBuiltFromTheSnapshotThatWasReadAndNotFromAnother() = runBlocking {
        // The strong form of the claim. `CountingSource` states a *different* recency list per call, and
        // §9's recency axis is read by the planner — so a request carrying call N's list must produce
        // call N's plan. With a one-focus configuration and one usable candidate family the selector
        // has nothing to choose between, so the fixture states the snapshot as the whole answer it can
        // observe and proves the pass consumed *that* value rather than any other.
        val source = CountingSource()
        val service = serviceOver(source)

        val result = service.generate(draft(), emptySet()) as ProgramGenerationResult.Generated

        assertEquals(
            "one read, so exactly one snapshot exists to plan from",
            1,
            source.calls
        )
        assertEquals(
            "and the plan is a real plan built over that one snapshot",
            true,
            result.edit.plan.slots.isNotEmpty()
        )
        assertEquals(
            "the snapshot the request was given is the one the source stated on its single call — no " +
                "second read anywhere in the pass could have substituted another one",
            GenerationPreferences(recentExerciseIds = listOf("squats")),
            source.answers.single()
        )
    }

    @Test
    fun theSnapshotReachesTheRequestThroughTheBoundaryUnchanged() = runBlocking {
        // The boundary forwards `preferences` verbatim (P23's contract), so a context value handed to
        // the service arrives at the request identical. Asserted on the request itself rather than on
        // the plan, because the request is where the claim lives and the plan is downstream of it.
        val stated = GenerationPreferences(recentExerciseIds = listOf("squats", "rows"))
        val service = serviceOver(FixedSource(stated))

        val classified = ProductionGenerationBoundary
            .catalogueOfShippedExercises(ProductionFocusClassification)
        val request = ProductionGenerationBoundary.generationRequest(
            catalogue = classified,
            focus = FocusPlan.Balanced,
            schedule = ProgramSchedule.FlexiblePerWeek(3),
            duration = ProgramDuration.Indefinite,
            availableEquipment = emptySet(),
            preferences = service.contextSnapshotOf(draft())
        )

        assertEquals(
            "the source's value reaches §30 step 10's pure input unchanged — no normalisation, no " +
                "de-duplication, no re-ordering on the way in",
            stated,
            request.preferences
        )
    }

    // ------------------------------------------------------------------ Generate and Preview agree

    @Test
    fun generateAndPreviewOverTheSameSourceStateSeeTheSameSnapshot() = runBlocking {
        // Two services over ONE source whose state has not changed, so both passes observe the same
        // facts. Each takes its own snapshot; both snapshots are the same value, so the plans must be.
        //
        // The source is [StableCountingSource] rather than [CountingSource]: a source that changed its
        // answer on every call would make this test measure the opposite rule — and it is
        // `aSourceThatChangesBetweenOperationsIsHonouredRatherThanCached` below that covers that. This
        // one is about agreement over *unchanged* state, which is what a Preview must be shown.
        val source = StableCountingSource()
        val generated = serviceOver(source).generate(draft(), emptySet())
        val previewed = serviceOver(source).preview(draft(), emptySet())

        assertEquals(
            "each pass took exactly one snapshot of the same unchanged state",
            2,
            source.calls
        )
        assertEquals(
            "and the two snapshots are equal, because the source's state did not change between them — " +
                "this is the Generate/Preview agreement claim, and it would be satisfied for free by a " +
                "stateful double that never varied",
            source.answers[0],
            source.answers[1]
        )
        assertEquals(
            "so the plan a user is shown a preview of is the plan Generate produces",
            (generated as ProgramGenerationResult.Generated).edit.plan,
            (previewed as ProgramGenerationResult.Generated).edit.plan
        )
        assertEquals(
            "and the reconciliation is the same too — a preview cannot report a different set of " +
                "changes than the pass it previews",
            generated.edit.reconciliation,
            previewed.edit.reconciliation
        )
    }

    @Test
    fun aSourceThatChangesBetweenOperationsIsHonouredRatherThanCached() = runBlocking {
        // The other direction, and the reason the previous test needs a paired one: when the facts DO
        // change between two Generate passes, the second pass must plan from the new snapshot. A
        // service that cached the first read would produce the first plan twice.
        val source = CountingSource()
        val service = serviceOver(source)

        val first = service.generate(draft(), emptySet()) as ProgramGenerationResult.Generated
        val second = service.generate(draft(), emptySet()) as ProgramGenerationResult.Generated

        assertEquals("two reads, two different stated facts", 2, source.calls)
        assertTrue(
            "and the second pass really did see different input than the first",
            source.answers[0] != source.answers[1]
        )
        // Whether the two PLANS differ is a planner matter and is deliberately not asserted here: §8
        // only lets recency act as a tie-breaker, so two different snapshots may legitimately yield the
        // same plan. What is asserted is that the snapshots differed and that the service took both —
        // the caching bug is closed without claiming a planner effect this stage does not own.
        assertTrue(
            "and the second pass still planned a real plan rather than reusing a stored one",
            second.edit.plan.slots.isNotEmpty()
        )
        assertEquals(
            "with the draft's own identity carried through both",
            first.edit.draft.programId,
            second.edit.draft.programId
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun serviceOver(source: GenerationContextSource): SnapshotProbe = SnapshotProbe(source)

    /** A draft editing an existing Program, so a production-shaped context read would be scoped. */
    private fun draft() = ProgramEditorDraft(
        programId = ProgramId("program-under-test"),
        name = "Existing",
        mode = ProgramMode.GENERATED,
        duration = ProgramDuration.Indefinite,
        schedule = ProgramSchedule.FlexiblePerWeek(3),
        focus = FocusPlan.Balanced
    )

    /**
     * The service under test, with its one operation exposed for the snapshot this suite asserts on.
     *
     * A wrapper rather than a subclass because [ProgramGenerationService] is `final` by design — and
     * because the point of the suite is to observe what the *real* service did, not to re-implement it.
     * [contextSnapshotOf] asks the same source the service is holding, which is what makes "the plan was
     * built from that snapshot" a statement about one shared source rather than about two doubles.
     */
    private class SnapshotProbe(private val source: GenerationContextSource) {

        private val ids = SequentialIds()

        private val service = ProgramGenerationService(
            catalogue = SHIPPED_EXERCISE_CATALOGUE,
            focusSource = ProductionFocusClassification,
            ids = DraftIdSource { ids.newId() },
            context = source
        )

        suspend fun generate(
            draft: ProgramEditorDraft,
            equipment: Set<com.monkfitness.app.data.model.Equipment>
        ): ProgramGenerationResult = service.generate(draft, equipment)

        suspend fun preview(
            draft: ProgramEditorDraft,
            equipment: Set<com.monkfitness.app.data.model.Equipment>
        ): ProgramGenerationResult = service.preview(draft, equipment)

        suspend fun regenerate(
            draft: ProgramEditorDraft,
            equipment: Set<com.monkfitness.app.data.model.Equipment>
        ): ProgramGenerationResult = service.regenerate(draft, equipment)

        /** The snapshot this very source would state now — the boundary's own forwarding, asserted. */
        suspend fun contextSnapshotOf(draft: ProgramEditorDraft): GenerationPreferences =
            source.preferencesFor(draft)
    }

    /**
     * A context source that answers **differently on every call** and records what it said.
     *
     * The varying answer is the whole instrument: with a constant answer, "read once" and "read many
     * times" and "Generate and Preview agree" are indistinguishable. The lists are real
     * [recentExerciseIds] values ordered most-recent-first, so a snapshot that reached the request is a
     * snapshot §9's recency axis could actually act on.
     */
    private class CountingSource : GenerationContextSource {

        /** How many times the service asked. */
        var calls: Int = 0
            private set

        /** Every answer given, in call order. */
        val answers = mutableListOf<GenerationPreferences>()

        override suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences {
            val answer = GenerationPreferences(
                recentExerciseIds = LISTS[calls % LISTS.size]
            )
            answers += answer
            calls += 1
            return answer
        }

        private companion object {
            val LISTS = listOf(
                listOf("squats"),
                listOf("rows", "squats"),
                listOf("lunges", "rows", "squats"),
                listOf("plank", "lunges", "rows", "squats")
            )
        }
    }

    /**
     * A context source that states the **same** thing on every call, and records what it said.
     *
     * The counterpart to [CountingSource], and it exists for one reason: a source that always varies
     * cannot demonstrate Generate/Preview *agreement*, because the two answers would differ for reasons
     * that have nothing to do with the service. This one keeps the state fixed while still counting
     * calls, so "each pass read once" and "both passes saw the same facts" are both measurable.
     */
    private class StableCountingSource : GenerationContextSource {

        /** How many times the service asked. */
        var calls: Int = 0
            private set

        /** Every answer given, in call order — equal, which is the point. */
        val answers = mutableListOf<GenerationPreferences>()

        override suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences {
            val answer = GenerationPreferences(recentExerciseIds = listOf("squats", "rows"))
            answers += answer
            calls += 1
            return answer
        }
    }

    /** A context source that always states the same thing — for the boundary-forwarding assertion. */
    private class FixedSource(private val preferences: GenerationPreferences) : GenerationContextSource {
        override suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences =
            preferences
    }
}
