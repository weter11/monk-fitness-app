package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetExistingOccurrence
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import java.time.LocalDate

/**
 * One explicitly stated target rule, in the vocabulary the *caller* owns: which rule, which workout it
 * produces, how often it recurs, the date it anchors to.
 *
 * Deliberately separate from [TargetSchedule], the engine's own input, so "the caller supplied this" is
 * a fact a reader can check. Nothing is defaulted, derived or validated here — a blank identity is
 * representable as a *claim*, and [TargetScheduleInputAdapter] is where a malformed claim is refused.
 */
data class TargetScheduleDefinition(
    val ruleId: String,
    val workoutId: String,
    val cadence: ScheduleCadence,
    val anchorDate: LocalDate
) {
    /**
     * The deterministic field-by-field conversion into the engine's [TargetSchedule]; nothing is
     * computed, because choosing a cadence or identity here would invent semantics the caller omitted.
     */
    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchorDate
    )
}

/**
 * Every value one target scheduling pass needs. The [scheduleDefinitions] are the only thing this
 * boundary converts; every other property is forwarded unchanged, so the pipeline after this point is
 * the only place target semantics exist.
 *
 * Three are *caller-owned facts this stage refuses to reconstruct*: [existingOccurrences] (the persisted
 * slot carries no full occurrence payload to read back), [sources] (resolving a derived rule is the
 * planning stage's work) and [programDayBindings] (`workoutId -> ProgramDayId` cannot be recovered from
 * a day number, a name, a date or an id's own text).
 */
data class TargetScheduleInput(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val scheduleDefinitions: List<TargetScheduleDefinition>,
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val existingOccurrences: List<TargetExistingOccurrence>,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>,
    val programDayBindings: List<TargetProgramDayBinding>
)

sealed class TargetScheduleInputException(message: String) : IllegalArgumentException(message) {

    data class BlankTargetRuleIdentity(val index: Int) : TargetScheduleInputException(
        "target schedule definition $index has a blank rule identity"
    )

    data class BlankTargetWorkoutIdentity(val index: Int) : TargetScheduleInputException(
        "target schedule definition $index has a blank workout identity"
    )

    /**
     * Two definitions claim one rule identity. The rest of the pipeline keys on it — the resolver sorts
     * by it, the composer writes it into the occurrence key — and keeping one claim silently would be a
     * scheduling decision made here.
     */
    data class DuplicateTargetRuleIdentity(val ruleId: String, val index: Int) :
        TargetScheduleInputException(
            "duplicate explicit target rule identity '$ruleId' at definition $index"
        )
}

/**
 * The input boundary: explicit [TargetScheduleDefinition] -> the engine's [TargetSchedule], and
 * [TargetScheduleInput] -> [TargetScheduleOrchestrationRequest]. It is the only place caller-owned Program
 * data becomes a target scheduling request, by deterministic value conversion alone: it does not run the
 * pass and holds no collaborator, repository, clock or identity generator.
 *
 * It never reads the legacy scheduling vocabulary. A `ProgramSchedule` states *when slots fall*, not a
 * target rule, and a mapper producing the missing identity, workout and anchor would be inventing them;
 * `ProgramDay.position` is an ordering, `.name` a label and an id's text is opaque, so none can stand in
 * for a stated target identity.
 *
 * This KDoc names no target stage type on purpose: the production-source scans that pin "which component
 * owns this vocabulary" read file text without stripping comments, so naming them would register this
 * file as a caller and invert a guard meant to stay closed.
 */
class TargetScheduleInputAdapter {

    /**
     * The whole conversion, in one expression per field.
     *
     * Order is the caller's: definitions are converted positionally, never sorted, grouped or deduplicated,
     * since a canonical order would be a second, invisible statement about which rule matters. The
     * duplicate check is a *refusal*, not a canonicalisation.
     */
    fun adapt(input: TargetScheduleInput): TargetScheduleOrchestrationRequest {
        val schedules = targetSchedules(input.scheduleDefinitions)
        return TargetScheduleOrchestrationRequest(
            programId = input.programId,
            revisionId = input.revisionId,
            schedules = schedules,
            window = input.window,
            selection = input.selection,
            existing = input.existingOccurrences,
            sources = input.sources,
            asOf = input.asOf,
            pauses = input.pauses,
            programDayBindings = input.programDayBindings
        )
    }

    /**
     * Validates the shape of the caller's own claims, then converts them one for one.
     *
     * The checks concern *identity and shape only* — a named rule, a named workout, one claim per rule.
     * Date eligibility, pauses and cadence resolvability are downstream questions, and no
     * `(ruleId, anchorDate)` uniqueness rule is added: [TargetSchedule] states none, and inventing one
     * would be a second semantic rule about what a pass means.
     */
    private fun targetSchedules(
        definitions: List<TargetScheduleDefinition>
    ): List<TargetSchedule> {
        definitions.forEachIndexed { index, definition ->
            if (definition.ruleId.isBlank()) {
                throw TargetScheduleInputException.BlankTargetRuleIdentity(index)
            }
            if (definition.workoutId.isBlank()) {
                throw TargetScheduleInputException.BlankTargetWorkoutIdentity(index)
            }
            if (definitions.take(index).any { it.ruleId == definition.ruleId }) {
                throw TargetScheduleInputException.DuplicateTargetRuleIdentity(definition.ruleId, index)
            }
        }
        return definitions.map { definition -> definition.toTargetSchedule() }
    }
}
