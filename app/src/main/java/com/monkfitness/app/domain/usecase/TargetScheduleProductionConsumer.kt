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
 * The five values one target scheduling run needs that **storage does not state**: window, selection,
 * sources, asOf, pauses. Every one of them is stated rather than derived and none has a default — each is
 * a separate scheduling decision, and a value that guessed one would be a second policy wearing a
 * consumer's clothes.
 *
 * An empty [sources] map is a real statement (this pass has no derived rule); a *missing* entry is a
 * dependency the planner refuses in its own typed vocabulary, which is why nothing here manufactures one.
 * A pure value: no repository, no clock, no identity generator, and no ordering or filtering of what the
 * caller supplied.
 */
data class TargetScheduleRunContext(
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>
)

/**
 * What one target scheduling run actually found. Six outcomes are kept apart and none is an empty success:
 * [SourceMissing] is not an empty schedule, because a revision that says nothing about target semantics
 * is a different claim from one that schedules nothing.
 *
 * A failed pass is not a case here at all: the target stages' own typed refusals propagate unchanged,
 * since a second translation policy at this boundary would be a second answer to "which rule refused".
 */
sealed interface TargetScheduleRunResult {

    data class Scheduled(
        val revisionId: RevisionId,
        val input: TargetScheduleInput,
        val result: TargetScheduleOrchestrationResult
    ) : TargetScheduleRunResult

    data class ProgramNotFound(val programId: ProgramId) : TargetScheduleRunResult

    data class CurrentRevisionMissing(val programId: ProgramId) : TargetScheduleRunResult

    /** The current revision states no explicit target source; no pass was run. */
    data class SourceMissing(val revisionId: RevisionId) : TargetScheduleRunResult

    /** The stored target source does not read back; no pass was run. */
    data class SourceMalformed(val revisionId: RevisionId, val reason: String) : TargetScheduleRunResult
}

/**
 * The first production consumer of the target contour: a Program plus an explicit run context become one
 * real planning / application / persistence pass, assembled from the stages that already exist.
 *
 * It owns assembly and nothing else. It reads the *current* revision through the Program aggregate — never
 * one derived from a slot, a date, a revision number or a day position — then that revision's explicit
 * source and the Program's stored occurrences, and hands the set to the existing adapter and orchestrator:
 * neither is bypassed nor reimplemented. It reads no legacy schedule, reconstructs no execution state (the
 * occurrence reader returns the stored payload for a key, keeping the reconciler's comparison honest),
 * filters nothing before the temporal stage, and never decides the five context values.
 *
 * ### Why the failures are not absorbed
 *
 * A missing Program, a Program whose current revision is not stored, a revision with no target source and
 * one whose source does not read are four different facts, each a result of its own. None becomes an empty
 * schedule, an empty occurrence list, a `null` or a successful pass that planned nothing: that would tell
 * the caller a Program was scheduled when nothing about it was ever stated.
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
     * The order of the reads is the contract: the Program and its current revision first, then the source
     * those two name, and only then the stored occurrences — so a revision that states no source never
     * reaches the occurrence read, the adapter or the pass, and a refused run cannot leave a target row.
     * Typed refusals from the target stages propagate unchanged and untranslated.
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
