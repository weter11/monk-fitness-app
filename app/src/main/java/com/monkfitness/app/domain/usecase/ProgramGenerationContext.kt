package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * Where the plain signals a generation pass is planned against come from.
 *
 * [GenerationPreferences] are plain inputs the planner consumes and may not derive, which leaves one
 * question: who reads the facts that fill them. That is this port, at the application boundary and not in
 * `domain/program/generated`, so the pure package keeps holding no repository, no DAO, no clock and no
 * Android type.
 *
 * [preferencesFor] is asked **once per generation operation** and forwarded into the existing
 * `GenerationRequest`. Generate, Regenerate and Preview all reach it through the same private pass, so a
 * preview cannot be planned against one set of facts and applied against another.
 *
 * It is a **reader of stated facts**, not a place to compute a signal: nothing here may derive a number,
 * translate a unit, classify an exercise, or turn an absence into a zero.
 *
 * @param draft the working draft the pass was asked about. A `null` [ProgramEditorDraft.programId] means
 *   the draft will create a Program, and a Program that does not exist has no history to read.
 */
fun interface GenerationContextSource {

    /** The signals generation is planned against for [draft], read once and held by the caller. */
    suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences
}

/**
 * The production [GenerationContextSource]: this Program's own performed sessions, and nothing else.
 *
 * It writes nothing — one repository, one `suspend` read, mapped into a value — so "Generate only ever
 * alters a draft" stays structurally true of the pass that reads through it.
 *
 * Scope is this Program alone, read through `sessionsOfProgram`, the repository that already assembles a
 * complete `WorkoutSession` from its snapshot and occurrence rows; this is not a second path to those
 * rows. A draft with no `programId` has no history to read and gets [GenerationPreferences.NONE] without
 * a read at all.
 *
 * `recentExposureByFocus` and `recentLoadByFocus` read the focus off the session's **own snapshot**
 * — a copy of what was presented, never an inference from the current revision or the catalogue. An
 * exercise that trains two focuses is still one assignment, so classifying it here would reconstruct a
 * history nobody recorded.
 *
 * `userPreferredExerciseIds` needs no read: it is stated draft configuration, which is why a draft that
 * was never saved still carries it.
 *
 * Two signals stay neutral, and each omission is a recorded gap rather than a hole to paper over:
 * `adaptivePreferredExerciseIds` — the stored `FamilyProgressionState.currentExerciseId` is family- and
 * revision-scoped, and no contract makes it an exercise-selection preference; and `recovery` —
 * `RecoveryContext` is the adaptive stage's own per-decision-window value, so elapsed time or
 * `currentExerciseId` would be a substitute measurement, not the fact.
 *
 * @param sessions where one Program's performed history is read. A port rather than the concrete
 *   repository so a suite can state a fixture history.
 */
class ProgramHistoryGenerationContext(
    private val sessions: GenerationSessionHistory
) : GenerationContextSource {

    override suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences {
        // Forwarded before anything is read: it is stated configuration exactly as `focus` is, so an
        // unsaved draft still states it (Generate alters only a draft).
        val preferred = draft.preferredExercises.exerciseIds
        val programId = draft.programId ?: return GenerationPreferences(userPreferredExerciseIds = preferred)
        val history = sessions.sessionsOfProgram(programId)
        val performed = recentExerciseIdsOf(history)
        val exposure = recentExposureByFocusOf(history)
        val load = recentLoadByFocusOf(history)
        return GenerationPreferences(
            userPreferredExerciseIds = preferred,
            recentExerciseIds = performed,
            recentExposureByFocus = exposure,
            recentLoadByFocus = load
        )
    }

    /**
     * The performed exercises of these sessions, **most recent first**, each stated once.
     *
     * The repository returns sessions in start order, so most-recent-first is the reverse of it and
     * nothing needs re-sorting. Inside one session the occurrences keep presentation order. A repeated
     * exercise keeps its **most recent** position: the first sighting in this order is its latest use,
     * and the recency axis reads the list by index.
     */
    private fun recentExerciseIdsOf(sessions: List<WorkoutSession>): List<String> = sessions
        .asReversed()
        .flatMap { session -> session.exercises }
        .filter { occurrence -> occurrence.results.isNotEmpty() }
        .map { occurrence -> occurrence.exerciseId }
        .distinct()

    /**
     * `recentExposureByFocus`: focus assignments that were actually executed, per focus.
     *
     * The unit is one presented element, counted once per **performed occurrence**, so a workout that
     * trained three focuses contributes to three entries. This is deliberately not a count of workouts:
     * deriving it from the session count would be a different measurement wearing this name.
     *
     * An occurrence whose snapshot recorded no focus contributes **nothing at all**, not a zero.
     */
    private fun recentExposureByFocusOf(sessions: List<WorkoutSession>): Map<Focus, Int> =
        sessions.asReversed()
            .flatMap { session -> performedOccurrencesWithRecordedFocus(session) }
            .groupingBy { entry -> entry.first }
            .eachCount()

    /**
     * `recentLoadByFocus`: the **confirmed sets** performed, attributed to each recorded focus.
     *
     * The unit is a set row. A partially executed occurrence contributes exactly the sets it confirmed;
     * repetitions and seconds are never converted into a count of sets, and no scalar load score is
     * computed — a `LoadProfile` is family-scoped and multi-dimensional, so collapsing it to one number
     * per focus would be a cross-dimension conversion, not a measurement.
     *
     * As with exposure, an occurrence with no recorded focus stays **absent**.
     */
    private fun recentLoadByFocusOf(sessions: List<WorkoutSession>): Map<Focus, Int> =
        sessions.asReversed()
            .flatMap { session -> performedOccurrencesWithRecordedFocus(session) }
            .groupingBy { entry -> entry.first }
            .fold(0) { total, entry -> total + entry.second }

    /**
     * The performed occurrences of one session paired with the focus its **own snapshot** recorded.
     *
     * One pass serves both signals because they share this pairing exactly: an occurrence with fewer than
     * one confirmed set is neither exposure nor load, so filtering once cannot make the two disagree. The
     * join key is the plan element identity — the snapshot's "what was presented" — and never the exercise id,
     * which is not a presentation identity (the same exercise may appear twice in one day).
     *
     * A snapshot element with `focus == null` is **dropped**, not defaulted: that row means no focus was
     * recorded, which is what a manual program or a pre-existing workout produces.
     */
    private fun performedOccurrencesWithRecordedFocus(
        session: WorkoutSession
    ): List<Pair<Focus, Int>> {
        val focusByElement = session.snapshot.workout.exercises
            .mapNotNull { element -> element.focus?.let { focus -> element.programExerciseId to focus } }
            .toMap()
        return session.exercises
            .filter { occurrence -> occurrence.results.isNotEmpty() }
            .mapNotNull { occurrence ->
                focusByElement[occurrence.programExerciseId]
                    ?.let { focus -> focus to occurrence.results.size }
            }
    }
}

/**
 * One Program's performed session history — the single read [ProgramHistoryGenerationContext] makes.
 *
 * Deliberately narrower than `WorkoutSessionRepository`: it names one read, so it cannot write a session,
 * confirm a set or finish a workout even by accident, which makes "the context source performs no writes"
 * a property of its shape.
 *
 * The composition root wires it as a lambda over
 * `workoutSessionRepository.sessionsOfProgram`, not a top-level `val`: Kotlin initialises top-level
 * properties in file order, and a `val` naming the container's node would capture it before the container
 * had built it.
 */
fun interface GenerationSessionHistory {

    /** Every stored session of [programId], fully assembled, in start order. */
    suspend fun sessionsOfProgram(programId: ProgramId): List<WorkoutSession>
}