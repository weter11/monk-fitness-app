package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * Where the plain signals a generation pass is planned against come from.
 *
 * §30 step 10 states the signals as **plain inputs** ([GenerationPreferences]): the planner may consume
 * them and may not derive them. That left exactly one unanswered question — *who reads the facts that
 * fill them in* — and this interface is the answer. It is a port, at the application/use-case boundary
 * and deliberately not in `domain/program/generated`, so the pure package keeps holding no repository,
 * no DAO, no clock and no Android type (§25, §30 step 10).
 *
 * ### One read, one pass
 *
 * [preferencesFor] is asked **once per generation operation** and its answer is forwarded into the
 * already-existing `GenerationRequest`. `Generate`, `Regenerate` and `Preview` all reach it through the
 * same private pass, so a preview cannot be planned against one set of facts and applied against
 * another — §33's *"no silent substitution"* applied to context rather than to focus.
 *
 * ### What this port may not become
 *
 * It is a **reader of stated facts**, not a place to compute a signal. Nothing here may derive a
 * number, translate one unit into another, classify an exercise, or turn an absence into a zero: the
 * stage's order of preference is a real fact, then an explicit neutral absence, then nothing at all.
 * `docs/PROGRAM_GENERATION_CONTEXT.md` states, per signal, which of the three this stage reached and
 * why.
 *
 * @param draft the working draft the pass was asked about. Its [ProgramEditorDraft.programId] is the
 *   scope of the history: `null` means the draft will create a Program, and a Program that does not
 *   exist has no history to read.
 */
fun interface GenerationContextSource {

    /** The signals generation is planned against for [draft], read once and held by the caller. */
    suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences
}

/**
 * The production [GenerationContextSource]: this Program's own performed sessions, and nothing else.
 *
 * ```text
 * ProgramEditorDraft.programId
 *          ↓  WorkoutSessionRepository.sessionsOfProgram — the whole assembled WorkoutSession graph
 * performed occurrences (results.isNotEmpty())
 *          ↓  most recent session first, presentation order inside a session, first sighting kept
 * recentExerciseIds
 *          ↓  paired with the focus the session's OWN snapshot recorded (§19)
 * recentExposureByFocus   = performed occurrences, counted per recorded focus
 * recentLoadByFocus       = confirmed sets, summed per recorded focus
 * ```
 *
 * ### The focus-keyed signals, and where their fact actually lived
 *
 * P27 left `recentExposureByFocus` and `recentLoadByFocus` neutral and said why: no object on the way
 * from a plan to a performed set carried a focus. P29's audit found that statement was **correct but
 * incomplete** — the fact existed and was being *discarded*. `GeneratedElement.focus` is real, the
 * Focus Planner assigns it, and `GeneratedSlot` asserts that a slot's elements are exactly its
 * assignment's focuses in order; `PlanReconciler` then dropped it when it materialised an element into a
 * `ProgramExercise`. From that point on no honest read of historical focus was possible, which is why
 * reconstructing one from the exercise catalogue was the only alternative and was correctly refused.
 *
 * So the fix is a **copy**, not an inference, and the two signals read the value off the session's own
 * snapshot — the immutable record of what was presented (§19). They do not read the current revision,
 * do not classify an exercise, and do not invent a focus where none was recorded. See
 * `docs/PROGRAM_GENERATION_ADAPTIVE_HISTORY.md`.
 *
 * ### Three signals are filled, and each for its own reason
 *
 * **`recentExerciseIds`** is filled because its semantic unit is a thing the session graph already
 * records: *an exercise the user actually performed, most recent first*.
 *
 * **`recentExposureByFocus`** and **`recentLoadByFocus`** are filled because P29 gave their unit a real
 * owner: the focus assignment an occurrence was **presented under**, recorded at snapshot creation.
 *
 * **`userPreferredExerciseIds`** is filled because §30 step 28 gave it a real owner: the user's own
 * stated ordering, persisted as revision content and carried on the draft. It needs no read, because
 * a draft's configuration is stated rather than inferred — which is also why a draft that has never
 * been saved can still carry one (`Generate` alters only a draft, §7).
 *
 * The remaining two are left at their neutral value, and each omission is a **recorded gap** rather
 * than a hole to paper over:
 *
 * | signal | why it stays neutral |
 * | --- | --- |
 * | `adaptivePreferredExerciseIds` | the stored `FamilyProgressionState.currentExerciseId` is *"the exercise the family is currently on"* — family-scoped and revision-scoped, and no existing contract defines it as an exercise-selection preference for generation. Promoting it would fabricate the preference the field is named for, and adding a ranking heuristic would invent §9's precedence rather than read it. |
 * | `recovery` | `RecoveryContext` is produced by the adaptive stage's own `AdaptiveJudgementRule` for *one decision window of one family*, and that rule itself receives `UNKNOWN` as its documented absence. There is no production-owned recovery context for a generation request, so there is none to read — and elapsed time, time since the last workout or `currentExerciseId` would each be a substitute measurement, not the fact. |
 *
 *
 * ### What counts as a performed occurrence
 *
 * An occurrence is exposure when it has **at least one confirmed set** — the same rule
 * `ProgramAdaptiveIntegration.observationsOf` applies, and the same one `ExposureObservation` states in
 * its own invariant (*"a skipped exercise or a missed slot produces no observation rather than a zero
 * one"*). So a skipped occurrence, an occurrence with no confirmed set, a `MISSED` slot, a `PLANNED`
 * slot nobody attempted and planned-but-never-started content are all **absent**, and absence stays
 * absence: nothing is counted as zero and no synthetic observation is created.
 *
 * A session's own `status` is deliberately **not** a filter. A cancelled or still-running attempt
 * holds real confirmed sets, and §19 preserves that partial work in the session; dropping it would
 * invent a rule about which attempts count that no existing domain statement owns.
 *
 * ### Scope is the Program, and the Program alone
 *
 * History is read through `WorkoutSessionRepository.sessionsOfProgram` — the repository that already
 * assembles a complete `WorkoutSession` from its own snapshot and occurrence rows, so this is the one
 * path to those facts and not a second one through a DAO. Nothing else is consulted: not another
 * Program's sessions, not an All-Programs aggregation, not global progress, and not an older
 * revision's state read as a fact of the current one. A draft with no `programId` has no Program to
 * scope to and receives [GenerationPreferences.NONE] without a read at all.
 *
 * ### It writes nothing
 *
 * There is no table, entity, DAO, migration or generation snapshot behind this class: it holds one
 * repository, calls one `suspend` read on it, and maps the result into a value. §33's *"Generate only
 * ever alters a draft"* stays structurally true of the pass that reads through it.
 *
 * @param sessions where one Program's performed history is read. A port rather than the concrete
 *   repository so a suite states a fixture history, and so the legacy session path stays named by
 *   `WorkoutSessionRepository` alone.
 */
class ProgramHistoryGenerationContext(
    private val sessions: GenerationSessionHistory
) : GenerationContextSource {

    override suspend fun preferencesFor(draft: ProgramEditorDraft): GenerationPreferences {
        // The user's own preference is the draft's, and is forwarded before anything is read: it is
        // stated configuration exactly as `focus` is, so it needs no storage read to be believed, and a
        // draft that has not been saved still states it (Generate alters only a draft, §7).
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
     * The repository already returns a Program's sessions in start order, so "most recent first" is
     * the reverse of that order and nothing has to be re-sorted by a comparison of its own. Within one
     * session the occurrences keep their presentation order, which is the order the user met them in.
     * A repeated exercise keeps its **most recent** position: the first sighting in this order is the
     * latest use of that exercise, and §9's recency axis reads the list by index.
     */
    private fun recentExerciseIdsOf(sessions: List<WorkoutSession>): List<String> = sessions
        .asReversed()
        .flatMap { session -> session.exercises }
        .filter { occurrence -> occurrence.results.isNotEmpty() }
        .map { occurrence -> occurrence.exerciseId }
        .distinct()

    /**
     * `recentExposureByFocus`: **focus assignments that were actually executed**, per focus.
     *
     * The unit is §8's own: one element of one slot's `FocusAssignment`, counted once because the
     * occurrence that presented it has at least one confirmed set. So the counter is *per performed
     * occurrence*, and a workout that trained three focuses contributes to three entries — which is
     * exactly why this is not a count of workouts, and why deriving it from the session count would be
     * a different measurement wearing this name.
     *
     * The focus is the one **recorded on the session's own snapshot** (§19). Nothing here reads the
     * current revision, and nothing classifies an exercise: `ProductionFocusClassification` is
     * deliberately unreachable from this file, because classifying a performed exercise by catalogue
     * membership would reconstruct a history nobody recorded, and an exercise that *trains* two
     * focuses is not two assignments.
     *
     * A performed occurrence with **no recorded focus contributes nothing at all** — not a zero. §12
     * and `ExposureObservation`'s own invariant: absence produces no observation rather than a zero one.
     */
    private fun recentExposureByFocusOf(sessions: List<WorkoutSession>): Map<Focus, Int> =
        sessions.asReversed()
            .flatMap { session -> performedOccurrencesWithRecordedFocus(session) }
            .groupingBy { entry -> entry.first }
            .eachCount()

    /**
     * `recentLoadByFocus`: the **confirmed sets** performed, attributed to each recorded focus.
     *
     * The unit is a **set** — `SetResult` rows, which is what §19 stores and what §10 prescribes. So a
     * partially executed occurrence contributes exactly the sets it confirmed, repetitions are never
     * converted into a count of sets, seconds are never converted into a count of sets, and no scalar
     * load score is computed or compared: a `LoadProfile` is family-scoped and multi-dimensional, and
     * collapsing it to one number per focus would be a cross-dimension conversion rather than a
     * measurement.
     *
     * As with exposure, an occurrence whose snapshot recorded no focus stays **absent** — an absent
     * focus is not a focus that was trained zero times.
     */
    private fun recentLoadByFocusOf(sessions: List<WorkoutSession>): Map<Focus, Int> =
        sessions.asReversed()
            .flatMap { session -> performedOccurrencesWithRecordedFocus(session) }
            .groupingBy { entry -> entry.first }
            .fold(0) { total, entry -> total + entry.second }

    /**
     * The performed occurrences of one session paired with the focus its **own snapshot** recorded.
     *
     * One pass serves both signals because the two share this pairing exactly: an occurrence with fewer
     * than one confirmed set is not exposure and contributes no load, so filtering once cannot make the
     * two disagree. The occurrence is joined to the snapshot element by its plan element identity — the
     * same identity §19 uses to say *what was presented* — and never by exercise id, which is not a
     * presentation identity (§9 allows the same exercise twice in one day).
     *
     * A snapshot element with `focus == null` is **dropped**, not defaulted: that is the row that means
     * *no focus was recorded*, and it is what a manual program, a user-authored element or a pre-P29
     * workout produces.
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
 * A port so a suite can state a fixture history without a database, and so this class holds no
 * concrete storage collaborator of its own. Deliberately narrower than `WorkoutSessionRepository`:
 * it names one read, and the interface therefore cannot be used to write a session, confirm a set or
 * finish a workout even by accident — which makes "the context source performs no writes" a property
 * of its shape rather than a promise about its body.
 *
 * The composition root wires the one production implementation: a lambda over
 * `workoutSessionRepository.sessionsOfProgram`. It is a lambda rather than a top-level `val` because
 * Kotlin initialises top-level properties in file order, and a `val` naming the container's node would
 * capture it before the container had built it.
 */
fun interface GenerationSessionHistory {

    /** Every stored session of [programId], fully assembled, in start order. */
    suspend fun sessionsOfProgram(programId: ProgramId): List<WorkoutSession>
}