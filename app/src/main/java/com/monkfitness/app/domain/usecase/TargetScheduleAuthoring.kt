package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding

/**
 * What a **caller** states about the target scheduling semantics of the revision a save is about to
 * create.
 *
 * ```text
 * caller-owned authoring values
 *         ↓  ProgramSaveService.save(draft, plannedStartDate, targetSchedule)
 * creation / revision-save application boundary
 *         ↓  TargetScheduleSourceRepository.store(...)
 * revision-owned explicit target source
 * ```
 *
 * ### Why this value exists
 *
 * Stage 18 gave the immutable revision a place to *hold* an explicit target source, but nothing could
 * produce one: no production path could state a rule identity, a workout identity, a cadence and an
 * anchor date, and inventing any of them is what this whole contour exists to prevent. This value is
 * the missing half — the **authoring** statement — and it is deliberately its own type rather than a
 * field on the draft or an extension of the legacy `ProgramSchedule`:
 *
 *  * it is not on [com.monkfitness.app.domain.program.ProgramEditorDraft], because a draft is the
 *    Program's *structure* ([com.monkfitness.app.domain.program.ProgramStructure]) and the structural
 *    comparison is what decides whether a save warrants a revision at all (§6). Target semantics are
 *    not structure, and putting them there would either make a target-only change mint a revision or
 *    make the change invisible — both are scheduling decisions this value must not make;
 *  * it is not part of `ProgramSchedule`, because a legacy schedule says *when slots fall* and a target
 *    rule additionally needs a rule identity, a workout identity, an anchor date and, for a derived
 *    rule, the identity of the rule it derives from. Three of those four have **no** counterpart in the
 *    legacy vocabulary, so any mapper between the two would have to manufacture them.
 *
 * ### What it holds, and what it deliberately does not
 *
 * It holds exactly two caller-stated lists:
 *
 *  * [rules] — each rule's own `ruleId`, `workoutId`, cadence and `anchorDate`, verbatim;
 *  * [programDayBindings] — one explicit `workoutId -> ProgramDayId` statement per workout the
 *    revision presents.
 *
 * It states **no** `plannedStartDate`, **no** cadence, **no** anchor, **no** `DerivedExcluding`
 * source, **no** binding, and nothing derived from a `ProgramDay`'s `position`, `name` or id text, and
 * nothing read from the Scheduler, from resolved occurrences, slots, sessions or performance. The
 * anchor date in particular is an explicit authoring input: there is no documented ownership rule in
 * this repository equating it with a Program's `plannedStartDate`, so this value does not make that
 * substitution on the caller's behalf.
 *
 * It holds no repository, DAO, Room entity, clock, identity generator or UI state, and it decides
 * nothing: [asSourceOf] is a value substitution plus one identity re-pointing, and the resulting
 * `TargetScheduleSource` is refused or stored by [TargetScheduleSourceRepository] under the same typed
 * refusals as every other write.
 *
 * ### Why a binding names a *drafted* plan day
 *
 * A revision's plan-day identities are **minted at save** ([com.monkfitness.app.domain.usecase
 * .ProgramEditorService.mintRevision]): the draft's day handles are the editor session's own and never
 * reach storage, so a caller cannot know the final `ProgramDayId` before the save. This value therefore
 * names the day by the draft's handle, and [asSourceOf] re-points it through the correspondence the
 * editor reports for the revision it actually minted. That correspondence is produced by the single
 * component that performs the re-identification and is never re-derived here — a binding to a day that
 * the saved revision does not carry is refused, not guessed.
 */
class TargetScheduleAuthoring(
    rules: List<TargetScheduleDefinition>,
    programDayBindings: List<TargetScheduleAuthoringBinding>
) {

    /** The explicitly stated rules, copied once so a caller mutating its own list cannot rewrite this. */
    val rules: List<TargetScheduleDefinition> = rules.toList()

    /** The explicitly stated bindings, copied on the same terms as [rules]. */
    val programDayBindings: List<TargetScheduleAuthoringBinding> = programDayBindings.toList()

    /**
     * The exact `TargetScheduleSource` this authoring states for [revisionId], or a typed refusal.
     *
     * Every rule is forwarded field for field and every binding is re-pointed from the drafted plan-day
     * handle to the identity the saved revision carries for it. There is no branch on a cadence, no
     * default anchor, no `ProgramSchedule` read, and no substitute identity: a rule the caller did not
     * state does not appear, and a binding whose drafted day is not among [mintedProgramDays] is
     * refused rather than pointed at some other day.
     *
     * @param revisionId the identity of the revision the save created — never the one it replaced.
     * @param mintedProgramDays the editor's own correspondence from each drafted plan-day handle to the
     *   identity that handle became in the saved revision.
     */
    fun asSourceOf(
        revisionId: RevisionId,
        mintedProgramDays: Map<ProgramDayId, ProgramDayId>
    ): TargetScheduleSource {
        // A revision with no rules cannot produce an occurrence at all, so an authoring that states
        // none is not a source claiming "no target semantics" — it is a statement that cannot be
        // stored, and the repository would read it back as a typed absence rather than as what the
        // caller said. Refusing here keeps the two apart at the authoring boundary.
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
     * Field-by-field equality, so "the authoring round-tripped unchanged" is a comparison rather than a
     * hope. Written out because the two collection properties are defensive copies rather than
     * constructor parameters, so a `data class` could not hold both.
     */
    override fun equals(other: Any?): Boolean = other is TargetScheduleAuthoring &&
        rules == other.rules &&
        programDayBindings == other.programDayBindings

    /** The hash of the same two fields [equals] compares. */
    override fun hashCode(): Int = 31 * rules.hashCode() + programDayBindings.hashCode()

    /** The same two fields, spelled out — so a failure names what differed. */
    override fun toString(): String =
        "TargetScheduleAuthoring(rules=$rules, programDayBindings=$programDayBindings)"
}

/**
 * One explicit `workoutId -> plan day` statement, naming the day by the **draft's** handle.
 *
 * [draftedProgramDayId] is the editor session's own identity for a plan day, which is what a caller
 * holds while authoring and what never reaches storage: a save mints a fresh identity for every day
 * ([com.monkfitness.app.domain.usecase.ProgramEditorService.mintRevision]). Naming the drafted handle
 * is what makes the statement authorable at all, and re-pointing it is a re-identification of a fact
 * the caller stated — never a choice of which plan day a workout presents.
 */
data class TargetScheduleAuthoringBinding(
    val workoutId: String,
    val draftedProgramDayId: ProgramDayId
)

/**
 * Typed refusals for an authoring that cannot honestly become a stored target source.
 *
 * Each is a refusal rather than a repair: completing a source that states no rule, or pointing a
 * binding at some other plan day, would be a scheduling decision made at an authoring boundary.
 */
sealed class TargetScheduleAuthoringException(message: String) : IllegalArgumentException(message) {

    /**
     * The authoring states no rule.
     *
     * An empty rule list is never a valid source — a revision with no rules cannot produce an
     * occurrence — so this is not "this revision states it has no target semantics", it is a statement
     * that cannot be stored. The typed absence [TargetScheduleSourceRead.Missing] is what a revision
     * that states nothing at all reads as, and it is reached by *not* supplying an authoring.
     */
    data object NoRulesStated : TargetScheduleAuthoringException(
        "an explicit target schedule authoring states no rule, which is not a source any revision can hold"
    )

    /**
     * A binding names a drafted plan day that the saved revision does not carry.
     *
     * The day exists — it is the caller's own draft handle — but the revision the save created has no
     * such day, so there is nothing to bind to. Guessing which of the revision's days was meant would
     * be exactly the inference this contour refuses.
     */
    data class ProgramDayNotInTheSavedRevision(
        val workoutId: String,
        val draftedProgramDayId: ProgramDayId
    ) : TargetScheduleAuthoringException(
        "target schedule authoring binding for workout '$workoutId' names drafted plan day " +
            "'${draftedProgramDayId.value}', which the saved revision does not carry"
    )

    /**
     * A **stored** source's binding names a plan day the draft being saved does not carry.
     *
     * This is the read-side counterpart of [ProgramDayNotInTheSavedRevision], and it is what makes
     * *"carry the previous revision's source forward"* a refusal rather than a repair. A structural
     * edit that removed the plan day a workout presented leaves a stored binding pointing at a day
     * the new revision will not have; there is no honest substitute, because which day that workout
     * should present now is the caller's statement and not a function of the day that disappeared.
     * Deciding it here would make this boundary a scheduling policy.
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
