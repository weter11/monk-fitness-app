package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
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
 * GenerationPreferences(recentExerciseIds = …)
 * ```
 *
 * ### Exactly one signal is filled, and that is the honest answer
 *
 * Of the six signals, **`recentExerciseIds` is the only one production can state today**, because it
 * is the only one whose semantic unit is a thing the session graph already records: *an exercise the
 * user actually performed, most recent first*. Every other signal is left at its neutral value, and
 * each omission is a **recorded gap** rather than a hole to paper over:
 *
 * | signal | why it stays neutral |
 * | --- | --- |
 * | `userPreferredExerciseIds` | no persisted, user-authored preference ordering exists. The draft's `USER_AUTHORED` elements are *plan content* that reconciliation preserves, not a ranked wish list, and reading them as one would be a different meaning the codebase does not state. |
 * | `adaptivePreferredExerciseIds` | the stored `FamilyProgressionState.currentExerciseId` is *"the exercise the family is currently on"* — family-scoped and revision-scoped, and no existing contract defines it as an exercise-selection preference for generation. Promoting it would fabricate the preference the field is named for. |
 * | `recentExposureByFocus` | the unit is **focus assignments** the recent context was loaded with. Neither `WorkoutSession`, `SessionExercise`, `EffectiveExercise` nor `ProgramExercise` carries a focus, and a slot's `FocusAssignment` is a generated-plan value that reconciliation does not keep per element. Classifying performed exercises through `ProductionFocusClassification` would be exactly the reconstruction algorithm this stage must not invent — and an exercise that *trains* two focuses is not two assignments. |
 * | `recentLoadByFocus` | the same missing link, one dimension up: there is no focus to attribute a performed set to. A `LoadProfile` is family-scoped and multi-dimensional; summing it, or converting repetitions into a count of sets, would be a cross-dimension conversion. |
 * | `recovery` | `RecoveryContext` is produced by the adaptive stage's own `AdaptiveJudgementRule` for *one decision window of one family*, and that rule itself receives `UNKNOWN` as its documented absence. There is no production-owned recovery context for a generation request, so there is none to read. |
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
        val programId = draft.programId ?: return GenerationPreferences.NONE
        val performed = recentExerciseIdsOf(sessions.sessionsOfProgram(programId))
        return if (performed.isEmpty()) {
            GenerationPreferences.NONE
        } else {
            GenerationPreferences(recentExerciseIds = performed)
        }
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