package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding

/**
 * What a **caller** states about the target scheduling semantics of the revision a save will create: the
 * rules ([rules]), one explicit `workoutId -> ProgramDayId` statement per workout ([programDayBindings]),
 * and [asSourceOf], which turns them into the stored source.
 *
 * It is its own type rather than a field on the draft or an extension of `ProgramSchedule`: a draft is the
 * Program's *structure*, and structure is what decides whether a save warrants a revision at all, while a
 * legacy schedule says only *when slots fall* and carries no rule identity, workout identity or anchor
 * date — putting either there would manufacture three of the four fields a target rule needs.
 *
 * Nothing is defaulted: the anchor date is an explicit authoring input, and a binding names the **drafted**
 * plan day because day identities are minted at save.
 */
class TargetScheduleAuthoring(
    rules: List<TargetScheduleDefinition>,
    programDayBindings: List<TargetScheduleAuthoringBinding>
) {

    /** The explicitly stated rules, copied once so a caller mutating its own list cannot rewrite this. */
    val rules: List<TargetScheduleDefinition> = rules.toList()

    val programDayBindings: List<TargetScheduleAuthoringBinding> = programDayBindings.toList()

    /**
     * The exact `TargetScheduleSource` this authoring states for [revisionId], or a typed refusal. Every
     * rule is forwarded field for field and every binding is re-pointed from the drafted handle to the
     * identity the saved revision carries; a binding whose drafted day was not minted is refused rather
     * than pointed at some other day.
     *
     * @param revisionId the identity the save created — never the one it replaced.
     */
    fun asSourceOf(
        revisionId: RevisionId,
        mintedProgramDays: Map<ProgramDayId, ProgramDayId>
    ): TargetScheduleSource {
        // A revision with no rules cannot produce an occurrence, so an authoring that states none is
        // not a source claiming "no target semantics" — it is a statement that cannot be stored, and
        // the repository would read it back as a typed absence. Refuse here, where they diverge.
        if (rules.isEmpty()) {
            throw TargetScheduleAuthoringException.NoRulesStated
        }
        val bindings = programDayBindings.map { binding ->
            val minted = mintedProgramDays[binding.draftedProgramDayId]
                ?: throw TargetScheduleAuthoringException.ProgramDayNotInTheSavedRevision(
                    workoutId = binding.workoutId,
                    draftedProgramDayId = binding.draftedProgramDayId
                )
            TargetProgramDayBinding(workoutId = binding.workoutId, programDayId = minted)
        }
        return TargetScheduleSource(revisionId = revisionId, rules = rules, programDayBindings = bindings)
    }

    /**
     * Field-by-field equality, so "the authoring round-tripped unchanged" is a comparison. Written out
     * because the two properties are defensive copies, so a `data class` could not hold both.
     */
    override fun equals(other: Any?): Boolean = other is TargetScheduleAuthoring &&
        rules == other.rules &&
        programDayBindings == other.programDayBindings

    override fun hashCode(): Int = 31 * rules.hashCode() + programDayBindings.hashCode()

    override fun toString(): String =
        "TargetScheduleAuthoring(rules=$rules, programDayBindings=$programDayBindings)"
}

/**
 * One explicit `workoutId -> plan day` statement, naming the day by the **draft's** handle, which is
 * what a caller holds while authoring and what never reaches storage. Re-pointing it is a
 * re-identification of a stated fact, never a choice of which plan day a workout presents.
 */
data class TargetScheduleAuthoringBinding(
    val workoutId: String,
    val draftedProgramDayId: ProgramDayId
)

/**
 * Typed refusals for an authoring that cannot honestly become a stored target source. Each is a
 * refusal rather than a repair: completing or re-pointing would be scheduling policy made here.
 */
sealed class TargetScheduleAuthoringException(message: String) : IllegalArgumentException(message) {

    /**
     * The authoring states no rule, which cannot produce an occurrence. A revision that states nothing at
     * all reads as [TargetScheduleSourceRead.Missing] instead, reached by *not* supplying an authoring.
     */
    data object NoRulesStated : TargetScheduleAuthoringException(
        "an explicit target schedule authoring states no rule, which is not a source any revision can hold"
    )

    /**
     * A binding names a drafted plan day the saved revision does not carry, so there is nothing to bind
     * to. Guessing which day was meant is exactly the inference this contour refuses.
     */
    data class ProgramDayNotInTheSavedRevision(
        val workoutId: String,
        val draftedProgramDayId: ProgramDayId
    ) : TargetScheduleAuthoringException(
        "target schedule authoring binding for workout '$workoutId' names drafted plan day " +
            "'${draftedProgramDayId.value}', which the saved revision does not carry"
    )

    /**
     * A **stored** source's binding names a plan day the draft does not carry: the read-side counterpart of
     * [ProgramDayNotInTheSavedRevision]. It makes "carry the previous source forward" a refusal — which day
     * that workout presents now is the caller's statement, not a function of the day that disappeared.
     */
    data class StoredProgramDayNotInTheDraft(
        val workoutId: String,
        val programDayId: ProgramDayId
    ) : TargetScheduleAuthoringException(
        "the stored target schedule source binds workout '$workoutId' to plan day " +
            "'${programDayId.value}', which this draft does not carry, so it cannot be carried " +
            "forward: replacing or clearing the target source is the only honest continuation"
    )
}
