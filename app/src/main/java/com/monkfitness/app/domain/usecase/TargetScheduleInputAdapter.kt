package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import java.time.LocalDate

/**
 * One explicitly stated target rule, in the vocabulary the *caller* owns.
 *
 * A target scheduling pass is fully described only if the caller says, for every rule it wants,
 * exactly four things: which rule it is, which workout it produces, how often it recurs and the
 * date it is anchored to. This value is that statement, and nothing else. It is deliberately a
 * separate type from [TargetSchedule] rather than an alias of it: [TargetSchedule] is the *engine's*
 * input, and keeping a caller-authored record distinct from it is what makes "the caller supplied
 * this" a fact a reader can check instead of an assumption.
 *
 * Nothing here is defaulted, derived or validated at construction. A blank identity is not
 * representable as a *rule* — it is representable as a *claim* — so [TargetScheduleInputAdapter] is
 * where a malformed claim is refused, with a typed reason naming what was missing. Deciding that a
 * blank rule id is acceptable, or that two claims about one rule can be merged, would be scheduling
 * policy, and policy does not live at an input boundary.
 */
data class TargetScheduleDefinition(
    val ruleId: String,
    val workoutId: String,
    val cadence: ScheduleCadence,
    val anchorDate: LocalDate
) {
    /**
     * The deterministic field-by-field conversion into the engine's own [TargetSchedule].
     *
     * Every field is copied; nothing is computed. There is no branch here, and there could not be
     * one: a conversion that chose a cadence or an identity for itself would be inventing the target
     * semantics the caller did not state.
     */
    fun toTargetSchedule(): TargetSchedule = TargetSchedule(
        ruleId = ruleId,
        workoutId = workoutId,
        cadence = cadence,
        anchorDate = anchorDate
    )
}

/**
 * Every value one target scheduling pass needs, in the shape the application layer holds it.
 *
 * The [scheduleDefinitions] are the only thing this boundary converts; every other property is
 * forwarded unchanged, which is what makes the pipeline after this point the only place target
 * semantics exist. The named properties are deliberately the same ten the orchestration request
 * carries, so the conversion is a value substitution and not a reshaping.
 *
 * Three of them are *caller-owned facts this stage refuses to reconstruct*:
 *
 *  * [existingOccurrences] — the persisted slot does not carry a full occurrence payload, so there
 *    is no honest way to read one back. Until a dedicated semantic read-back contract exists, the
 *    caller states them;
 *  * [sources] — a derived rule's source occurrences are semantic, and resolving them is the
 *    planning stage's work, not an input boundary's;
 *  * [programDayBindings] — a `workoutId` to `ProgramDayId` mapping cannot be recovered from a day
 *    number, a name, a date or an id's own text, so it is stated.
 */
data class TargetScheduleInput(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val scheduleDefinitions: List<TargetScheduleDefinition>,
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val existingOccurrences: List<ExistingOccurrence>,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>,
    val programDayBindings: List<TargetProgramDayBinding>
)

/** Typed refusals for a target scheduling input that does not state what a pass requires. */
sealed class TargetScheduleInputException(message: String) : IllegalArgumentException(message) {

    /** A definition claims a rule without naming it, so no occurrence could ever be attributed. */
    data class BlankTargetRuleIdentity(val index: Int) : TargetScheduleInputException(
        "target schedule definition $index has a blank rule identity"
    )

    /** A definition names a rule but not the workout that rule produces. */
    data class BlankTargetWorkoutIdentity(val index: Int) : TargetScheduleInputException(
        "target schedule definition $index has a blank workout identity"
    )

    /**
     * Two definitions claim one rule identity.
     *
     * A rule id is the identity the rest of the pipeline keys on: the resolver sorts by it, the
     * composer writes it into the occurrence key, and a derived cadence names it as its source. Two
     * claims about one identity cannot both be true, and silently keeping one of them would be a
     * scheduling decision made here.
     */
    data class DuplicateTargetRuleIdentity(val ruleId: String, val index: Int) :
        TargetScheduleInputException(
            "duplicate explicit target rule identity '$ruleId' at definition $index"
        )
}

/**
 * Phase 13 input boundary: explicit application definitions
 * [TargetScheduleDefinition] -> the engine's own [TargetSchedule], and
 * [TargetScheduleInput] -> [TargetScheduleOrchestrationRequest].
 *
 * This adapter is the *only* place where caller-owned Program data becomes a target scheduling
 * request, and it does so by deterministic value conversion alone:
 *
 * ```text
 * explicit target definitions + bindings
 *         ↓  TargetScheduleInputAdapter
 * TargetScheduleOrchestrationRequest
 *         ↓  TargetScheduleOrchestrator
 * ```
 *
 * It does not run the pass. It holds no planning, temporal, presentation or persistence
 * collaborator, and no repository, clock or identity generator: an equivalent input always
 * describes an equality-identical request.
 *
 * What it deliberately does **not** do is read the legacy scheduling vocabulary. A revision's
 * `ProgramSchedule` states *when slots fall*; it does not state a target rule, because a rule also
 * needs a rule identity, a workout identity, an anchor date and, for a derived rule, its source. A
 * mapper that produced those would be inventing them, so none exists — neither here nor in the
 * legacy contour, and no implicit `ProgramSchedule -> TargetSchedule` path is introduced by this
 * stage. The same holds for every other identity a Program holds: a `ProgramDay.position` is an
 * ordering, a `ProgramDay.name` is a label, and an id's own text is opaque (§1), so none of them can
 * stand in for a target identity the caller has to state.
 *
 * The KDoc of this file names no target stage type on purpose. The production-source scans that pin
 * "which component owns this vocabulary" read file text without stripping comments, so a KDoc that
 * spelled those types out would register as a caller and invert a guard that is meant to stay closed.
 * The stage document names them; the source does not.
 */
class TargetScheduleInputAdapter {

    /**
     * The whole conversion, in one expression per field.
     *
     * Order is the caller's: [TargetScheduleInput.scheduleDefinitions] is converted positionally and
     * never sorted, grouped or deduplicated, because a canonical order would be a second, invisible
     * statement about which rule matters. The duplicate check below is a *refusal*, not a
     * canonicalisation: it says two claims about one identity cannot both be true, and leaves the
     * caller to supply one.
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
     * The checks are about *identity and shape only* — a named rule, a named workout, one claim per
     * rule. Whether a rule is date-eligible, paused, missed, in the past or in the future, what a
     * composition means, and whether a cadence is resolvable at all are all downstream questions:
     * [ScheduleCadence] already rejects an impossible cadence at construction, and the temporal
     * stage is the only authority on dates. No `(ruleId, anchorDate)` uniqueness rule is added here
     * either: [TargetSchedule] states none, and inventing one would be a second semantic rule about
     * what a pass means.
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
