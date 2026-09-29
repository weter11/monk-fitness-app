package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.TargetScheduleOccurrenceRepository
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import java.time.LocalDate

/**
 * The five values one target scheduling run needs that **storage does not state**.
 *
 * ```text
 * persisted, revision-owned target source   →  rules + bindings
 * persisted target occurrences              →  existing occurrences
 * ------------------------------------------------------------------
 * this value                                →  window, selection, sources, asOf, pauses
 * ```
 *
 * Each is stated rather than derived, and none has a default: a window, a composition, a resolved
 * source map, an as-of date and a set of pause windows are five separate scheduling decisions, and a
 * value that guessed any of them would be a second policy wearing a consumer's clothes. The target
 * core already treats the window as an explicit bounded input and the pause list as an explicit set
 * of date windows the caller has already converted, and this type keeps that ownership intact.
 *
 * It is a pure value: no repository, no clock, no identity generator, no ambient state, and no
 * defaulting, sorting, deduplication or filtering of what the caller supplied.
 *
 * @property window the explicit bounded range the pass plans inside.
 * @property selection which rules compose into one occurrence on a shared date.
 * @property sources the explicitly stated resolved sources a derived rule depends on. An empty map
 *   is a real statement — this pass has no derived rule — and a *missing* entry is a dependency the
 *   planner refuses in its own typed vocabulary, which is why nothing here manufactures one.
 * @property asOf the date the temporal stage classifies against.
 * @property pauses the explicit pause windows the temporal stage reads.
 */
data class TargetScheduleRunContext(
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>
)

/**
 * What one target scheduling run actually found.
 *
 * Six outcomes are kept apart, and none of them is an empty success:
 *
 *  * [Scheduled] — the pass ran and persisted; the result carries the assembled input, so a caller
 *    can see exactly what was run as well as what came of it;
 *  * [ProgramNotFound] — no Program with that identity is stored;
 *  * [CurrentRevisionMissing] — the Program is stored but the revision its pointer names is not,
 *    which is invalid persisted data rather than an absent Program;
 *  * [SourceMissing] — the current revision states **no** explicit target source. This is not an
 *    empty source and it is not an empty schedule: a revision that says nothing about target
 *    semantics is a different claim from one that schedules nothing, and only the second is a run;
 *  * [SourceMalformed] — the stored rows do not read back as a source this vocabulary can speak,
 *    and are reported rather than defaulted to something plausible;
 *  * a **failed pass is not a case here at all**: the target stages' own typed refusals propagate
 *    unchanged, because a second translation policy at this boundary would be a second answer to
 *    "which rule refused", and the stages that own those rules already say it.
 */
sealed interface TargetScheduleRunResult {

    /** The pass ran against the assembled input, and [input] is exactly what it was given. */
    data class Scheduled(
        val revisionId: RevisionId,
        val input: TargetScheduleInput,
        val result: TargetScheduleOrchestrationResult
    ) : TargetScheduleRunResult

    /** No Program with this identity is stored. */
    data class ProgramNotFound(val programId: ProgramId) : TargetScheduleRunResult

    /** The Program is stored, but the revision its pointer names is not. */
    data class CurrentRevisionMissing(val programId: ProgramId) : TargetScheduleRunResult

    /** The current revision states no explicit target source; no pass was run. */
    data class SourceMissing(val revisionId: RevisionId) : TargetScheduleRunResult

    /** The stored target source does not read back; no pass was run. */
    data class SourceMalformed(val revisionId: RevisionId, val reason: String) : TargetScheduleRunResult
}

/**
 * The first production consumer of the target scheduling contour: a Program, plus an explicit run
 * context, become one real target planning / application / persistence pass.
 *
 * ```text
 * ProgramId
 *     ↓  Program aggregate / current revision
 * current RevisionId
 *     ↓  TargetScheduleSourceBridge
 * explicit rules + explicit program-day bindings          (persisted, revision-owned)
 * existing target occurrences                            (persisted, program-owned)
 *     ↓  TargetExistingOccurrenceReader
 * existingOccurrences
 *     ↓  TargetScheduleInputAdapter
 * TargetScheduleOrchestrationRequest
 *     ↓  TargetScheduleOrchestrator
 * plan → temporal decision → presentation → target slot + target occurrence
 * ```
 *
 * Everything above this class was already built and separately callable; what was missing was a
 * production caller, and every previous caller of a target stage was a test. This is the seam that
 * makes the contour reachable from production code **without** making it production's scheduler:
 * nothing in the UI, in the legacy planning path or in the session runtime calls this, and the
 * legacy planner remains the owner of the slots the application actually trains from.
 *
 * ### What it owns, and only this
 *
 * Assembly. It reads the *current* revision through the Program aggregate — the revision the stored
 * pointer names, never one derived from a slot, a date, a revision number, a day position or a
 * source row — reads that revision's explicit target source through the source bridge, reads the
 * Program's stored target occurrences, and hands the whole set to the existing input adapter, which
 * converts and the existing orchestrator, which runs. The adapter is not bypassed and the
 * orchestrator is not reimplemented: the three semantic boundaries behind them keep their owners.
 *
 * ### What it deliberately does not do
 *
 *  * it does **not** read the legacy scheduling vocabulary, and it does not reconstruct a rule, a
 *    cadence, an anchor date or a binding out of one. The source states those, and a revision that
 *    states none is a typed refusal rather than something to be filled in from elsewhere;
 *  * it does **not** reconstruct execution state. It does not read a slot's status, does not build
 *    an occurrence value, does not create a result record and does not aggregate anything: the
 *    stored occurrence is read through the existing reader, and the execution classification is that
 *    reader's policy verdict. The reader is given each stored occurrence as a **key** and returns
 *    the stored payload back, which is what keeps the reconciler's payload comparison honest;
 *  * it does **not** filter the stored occurrences before the target stages see them. Every stored
 *    target occurrence of the Program is passed on, in the repository's own read order, and the
 *    temporal stage decides which of them are past, missed, paused or still to come;
 *  * it does **not** decide a window, a composition, a resolved source, an as-of date or a pause.
 *    Those five arrive in the run context and are forwarded unchanged — no defaulting, no sorting,
 *    no filtering, no horizon, no conversion of a stored pause instant into a date window;
 *  * it does **not** hold an identity generator, a clock, a second scheduler or a UI state, it
 *    names no Room type, and it never writes outside the repositories it already owns.
 *
 * ### Why the failures are not absorbed
 *
 * A missing Program, a Program whose current revision is not stored, a revision with no target
 * source and a revision whose target source does not read are four different facts, and each is a
 * result of its own. None of them becomes an empty schedule, an empty occurrence list, a `null`, or
 * a successful pass that planned nothing: a run that planned nothing is not something a stored
 * absence can be turned into, and reporting it as one would tell the caller a Program was scheduled
 * when in fact nothing about it was ever stated.
 */
class TargetScheduleProductionConsumer(
    private val programRepository: ProgramRepository,
    private val planRepository: ProgramPlanRepository,
    private val sourceBridge: TargetScheduleSourceBridge,
    private val occurrenceRepository: TargetScheduleOccurrenceRepository,
    private val existingOccurrenceReader: TargetExistingOccurrenceReader,
    private val inputAdapter: TargetScheduleInputAdapter,
    private val orchestrator: TargetScheduleOrchestrator
) {

    /**
     * Runs one target scheduling pass for [programId] against an explicitly stated [context].
     *
     * The order of the reads is the contract: the Program and its current revision first, then the
     * source those two name, and only then the stored occurrences. A revision that states no source
     * therefore never reaches the occurrence read, never reaches the adapter and never reaches the
     * pass — so a refused run cannot leave a target row behind.
     *
     * Typed refusals raised by the target stages themselves — a duplicate rule identity at the input
     * boundary, a missing binding at presentation, a conflicting stored payload at persistence, a
     * derived rule whose source the caller did not state — propagate unchanged and untranslated.
     *
     * @return [TargetScheduleRunResult.Scheduled] when the pass ran, or the typed absence that says
     *   why it did not.
     */
    suspend fun run(
        programId: ProgramId,
        context: TargetScheduleRunContext
    ): TargetScheduleRunResult {
        programRepository.programById(programId) ?: return TargetScheduleRunResult.ProgramNotFound(programId)
        val currentRevision = planRepository.currentRevision(programId)
            ?: return TargetScheduleRunResult.CurrentRevisionMissing(programId)
        val revisionId = currentRevision.revisionId
        val source = when (val read = sourceBridge.definitionsAndBindingsOf(revisionId)) {
            is TargetScheduleSourceRead.Source -> read.source
            is TargetScheduleSourceRead.Missing -> return TargetScheduleRunResult.SourceMissing(read.revisionId)
            is TargetScheduleSourceRead.Malformed ->
                return TargetScheduleRunResult.SourceMalformed(read.revisionId, read.reason)
        }
        val storedOccurrences = occurrenceRepository.occurrencesOfProgram(programId)
        val input = TargetScheduleInput(
            programId = programId,
            revisionId = revisionId,
            scheduleDefinitions = source.rules,
            window = context.window,
            selection = context.selection,
            existingOccurrences = existingOccurrenceReader.existingOccurrencesOf(
                programId,
                storedOccurrences.map { it.occurrence }
            ),
            sources = context.sources,
            asOf = context.asOf,
            pauses = context.pauses,
            programDayBindings = source.programDayBindings
        )
        return TargetScheduleRunResult.Scheduled(
            revisionId = revisionId,
            input = input,
            result = orchestrator.apply(inputAdapter.adapt(input))
        )
    }
}
