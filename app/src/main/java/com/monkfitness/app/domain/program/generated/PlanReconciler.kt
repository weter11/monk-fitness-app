package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.renumbered

/**
 * §7's four levels of authority, as a value a screen can show and a test can assert.
 *
 * ```text
 * PINNED  >  EXPLICIT USER OVERRIDE  >  COMPATIBLE USER CHANGE  >  PURE GENERATED CONTENT
 * ```
 *
 * The distinction between the first two is [ProgramExercise.isPinned], and the distinction between
 * the last two is whether what the user holds agrees with what the planner would produce. Nothing is
 * stored twice: a *level* is a reading of a plan element at the moment of reconciliation, which is
 * exactly what §7 means by *"compatible" versus "conflicting" is a comparison the editor makes when
 * it reconciles, not a property to be frozen here"*.
 */
enum class PreservationLevel {

    /** Pinned: never automatically changed (§7's highest level). */
    PINNED,

    /** The user's own element, which the plan does not agree with: kept, and shown as a conflict. */
    USER_OVERRIDE,

    /** The user's own element, which the plan agrees with: kept, and nothing to report (§7). */
    COMPATIBLE,

    /** The generator's own element — the only level a regenerate pass may replace (§7). */
    GENERATED
}

/** What reconciliation did with one element, or with one day. */
enum class ChangeKind {

    /** The element is in the reconciled plan, unchanged, with the identity it already had. */
    PRESERVED,

    /** The element is in the reconciled plan for the first time, with a fresh identity. */
    ADDED,

    /** The element is no longer part of the plan. Its identity is gone with it. */
    DROPPED,

    /** The whole day is no longer part of the plan: nothing on it survived. */
    DAY_REMOVED
}

/**
 * One thing reconciliation decided, about one element of one day.
 *
 * A *replacement* is deliberately reported as its two halves — a [ChangeKind.DROPPED] and a
 * [ChangeKind.ADDED] on the same day — rather than as an inferred pairing. Pairing them would mean
 * asserting which new element "stands in for" which old one, and the planner has no such relation: it
 * produced the elements its focuses needed, and an element that left the plan left it. A screen is
 * free to pair them; the domain does not invent the relation.
 *
 * @property level which of §7's four levels the element belongs to.
 * @property kind what happened to it.
 * @property dayPosition the plan day's 1-based position.
 * @property exerciseId the exercise the change is about, or `null` for a whole-day change.
 * @property programExerciseId the element's draft identity, or `null` for a whole-day change.
 * @property programDayId the day's identity, for a whole-day change.
 * @property agreesWithPlan whether the element matches what the plan produces for that day — the
 *   distinction between §7's *compatible* and *conflicting* user changes. Meaningful for
 *   [ChangeKind.PRESERVED]; `true` for an addition and `false` for a removal.
 */
data class ReconciliationChange(
    val level: PreservationLevel,
    val kind: ChangeKind,
    val dayPosition: Int,
    val exerciseId: String? = null,
    val programExerciseId: ProgramExerciseId? = null,
    val programDayId: ProgramDayId? = null,
    val agreesWithPlan: Boolean = true
)

/**
 * What a reconciliation did — §7's *"conflicts with user choices are shown explicitly"*, as a value.
 *
 * The report is the whole reason `Regenerate` can be trusted: every element of the previous draft is
 * accounted for at most once, so "the plan was not replaced wholesale" is not a claim about the code
 * but a count a caller (or a test) can read. The helpers below are the questions a caller actually
 * asks — what survived, what changed, what the user would want to know about.
 *
 * @property changes every change, in day order and then in plan order within the day.
 */
data class ReconciliationReport(val changes: List<ReconciliationChange>) {

    /**
     * Whether the plan and the draft already agreed: nothing was added, nothing was dropped and no
     * day was removed.
     *
     * This is *not* the same as an empty report. A report names every element the draft holds, so a
     * regeneration that agrees with the draft still reports all of them as preserved — which is what
     * makes "the plan was not replaced wholesale" countable. What a caller usually means by "nothing
     * changed" is this predicate, and [conflicts] is what is worth showing when it is true.
     */
    val changedNothing: Boolean
        get() = addedCount == 0 && droppedCount == 0 && removedDayCount == 0

    /** Every change at one of §7's levels. */
    fun withLevel(level: PreservationLevel): List<ReconciliationChange> =
        changes.filter { it.level == level }

    /** Every change of one kind. */
    fun ofKind(kind: ChangeKind): List<ReconciliationChange> =
        changes.filter { it.kind == kind }

    /** How many elements survived, at any level. */
    val preservedCount: Int
        get() = changes.count { it.kind == ChangeKind.PRESERVED }

    /** How many elements entered the plan, each with a fresh identity. */
    val addedCount: Int
        get() = changes.count { it.kind == ChangeKind.ADDED }

    /** How many elements left the plan. */
    val droppedCount: Int
        get() = changes.count { it.kind == ChangeKind.DROPPED }

    /** How many days left the plan entirely. */
    val removedDayCount: Int
        get() = changes.count { it.kind == ChangeKind.DAY_REMOVED }

    /**
     * What the user should be told: content they own — pinned or authored — that the plan would not
     * have produced there.
     *
     * An empty list is the ordinary case (the user's plan and the generated one agree), and it is not
     * the same as [isEmpty]: a regeneration can replace a dozen generated elements and still have no
     * conflict at all, because none of the user's own choices was affected.
     */
    val conflicts: List<ReconciliationChange>
        get() = changes.filter {
            it.kind == ChangeKind.PRESERVED &&
                !it.agreesWithPlan &&
                (it.level == PreservationLevel.PINNED || it.level == PreservationLevel.USER_OVERRIDE)
        }

}

/** A reconciliation's two outputs: the plan days it produced, and what it did to get there. */
data class ReconciledPlan(
    val days: List<ProgramDay>,
    val report: ReconciliationReport
)

/**
 * §7's regeneration, as **reconciliation and never replacement**.
 *
 * ### The one sentence this file implements
 *
 * ```text
 * the plan keeps everything the user owns, and may change only what the generator produced
 * ```
 *
 * Applied per day, in the previous draft's own order, that sentence decides the whole algorithm:
 *
 *  * a **pinned** element is kept where it is — its day, its place, its prescription, its pin and its
 *    identity. §7's *"pinned: never automatically change"* has no exception here, not even for a
 *    pinned element the plan disagrees with: disagreement is *reported*
 *    ([ReconciliationReport.conflicts]) and never acted on.
 *  * a **user-authored** element is kept for the same reasons — §7's *"explicit user override: never
 *    silently replace"* and its *"an element the user modified inside a Generated Program must be
 *    treated as user-authored/override state and preserved"*. It is kept whether or not the plan
 *    agrees with it; agreement decides only how it is *reported* (compatible or override).
 *  * a **generated** element may change — and even then only when it has to. An element that already
 *    says what the plan says (same exercise, same prescription) *keeps its identity*, so a
 *    regeneration that changes one element does not churn the other twenty. §7's *"unchanged
 *    generated plan stays unchanged"* is that rule, and re-minting an unchanged element would be a
 *    hidden whole-plan replacement in slow motion.
 *  * a **newly needed** element is added with a fresh draft identity, and an element the plan no
 *    longer produces is dropped. Dropped elements are always generated ones: an element the user owns
 *    is not the plan's to remove, and there is no code path here that could remove one.
 *
 * ### An element the user owns **stands in** for the plan's element it agrees with
 *
 * Matching is one-to-one against everything the draft holds, and it is on what an element *says* —
 * the exercise and the prescription, together, greedily in plan order — not on identity or position.
 * That is what makes §7's *compatible user change* a real level rather than a label: when the user's
 * own element is what the plan would have produced anyway, it satisfies that plan element, so nothing
 * is added beside it. The alternative — treating the user's element as extra content and adding the
 * plan's identical element next to it — would duplicate the exercise on the day, which is not what
 * "the user had already made this change" means.
 *
 * The user's own content therefore comes **first** on a day, and the generator's content follows in
 * plan order around it — which is §8's *"establishes pinned/manual/fixed workouts first; fills
 * remaining slots around them"*, read one day at a time.
 *
 * ### Identity, and why it matters so little and so much
 *
 * A draft's identities are working handles: `Save` re-mints every day and element identity into the
 * revision it writes, so nothing here decides what is persisted. What it does decide is what the user
 * *sees* between regeneration and save — an element that survived with its identity is the same
 * element to every screen, and one that was replaced is recognisably new.
 *
 * ### What it never touches
 *
 * No revision (there is none in scope), no slot, no session, no history, no date and no clock: this
 * file is given a draft and a plan and returns days. The Scheduler still owns which dates those days
 * land on (§20), and a regeneration neither creates, moves nor cancels an opportunity.
 */
object PlanReconciler {

    /**
     * Reconciles [plan] with the plan [previous] already holds.
     *
     * @param previous the draft being regenerated — the record of everything the user owns.
     * @param plan the plan the generator just produced.
     * @param ids where the identities of added days and elements come from (§26).
     */
    fun reconcile(
        previous: ProgramEditorDraft,
        plan: GeneratedPlan,
        ids: DraftIdSource
    ): ReconciledPlan {
        val positions = 1..maxOf(previous.days.size, plan.slots.size)
        val reconciled = positions.fold(Reconciling()) { state, position ->
            state.withPosition(
                position = position,
                previousDay = previous.days.firstOrNull { it.position == position },
                slot = plan.slots.firstOrNull { it.position == position },
                ids = ids
            )
        }
        return ReconciledPlan(
            days = reconciled.days.renumbered(),
            report = ReconciliationReport(reconciled.changes)
        )
    }

    /** The days reconciled so far, and the changes that produced them. */
    private data class Reconciling(
        val days: List<ProgramDay> = emptyList(),
        val changes: List<ReconciliationChange> = emptyList()
    ) {

        /** This reconciliation with one plan day reconciled. */
        fun withPosition(
            position: Int,
            previousDay: ProgramDay?,
            slot: GeneratedSlot?,
            ids: DraftIdSource
        ): Reconciling {
            val previousElements = previousDay?.exercises.orEmpty()
            val preserved = previousElements.filter { it.isOwnedByTheUser }
            val planned = slot?.elements.orEmpty()

            // One-to-one matching against *everything* the day holds: a plan element the user's own
            // element already says is satisfied by it, and a plan element a generated element says is
            // that element kept.
            val matched = previousElements.matchAgainst(planned)
            val built = planned.mapIndexed { index, element ->
                val satisfiedBy = matched.reused[index]
                when {
                    satisfiedBy == null -> ReconciledElement(
                        element = element.asGeneratedElement(ids),
                        kind = ChangeKind.ADDED,
                        level = PreservationLevel.GENERATED
                    )
                    satisfiedBy.isOwnedByTheUser -> null // the user's own element stands in for it
                    else -> ReconciledElement(
                        element = satisfiedBy,
                        kind = ChangeKind.PRESERVED,
                        level = PreservationLevel.GENERATED
                    )
                }
            }

            val elements = preserved + built.filterNotNull().map { it.element }
            val preservedChanges = preserved.map { element ->
                ReconciliationChange(
                    level = element.preservationLevel(agreesWithPlan = planned.agreesWith(element)),
                    kind = ChangeKind.PRESERVED,
                    dayPosition = position,
                    exerciseId = element.exerciseId,
                    programExerciseId = element.programExerciseId,
                    agreesWithPlan = planned.agreesWith(element)
                )
            }
            val plannedChanges = built.filterNotNull().map { kept ->
                ReconciliationChange(
                    level = kept.level,
                    kind = kept.kind,
                    dayPosition = position,
                    exerciseId = kept.element.exerciseId,
                    programExerciseId = kept.element.programExerciseId,
                    agreesWithPlan = true
                )
            }
            val droppedChanges = matched.unmatched
                .filterNot { it.isOwnedByTheUser }
                .map { element ->
                    ReconciliationChange(
                        level = PreservationLevel.GENERATED,
                        kind = ChangeKind.DROPPED,
                        dayPosition = position,
                        exerciseId = element.exerciseId,
                        programExerciseId = element.programExerciseId,
                        agreesWithPlan = false
                    )
                }

            val day = if (elements.isEmpty()) {
                // Nothing survives and nothing is planned: the day is not part of the plan at all.
                // A day that plans nothing is a draft state a save refuses, so keeping an empty day
                // would silently invent one.
                null
            } else {
                (previousDay?.asWorkDay() ?: newGeneratedDay(position, ids)).copy(exercises = elements)
            }
            val removalChanges = if (day == null && previousDay != null) {
                listOf(
                    ReconciliationChange(
                        level = PreservationLevel.GENERATED,
                        kind = ChangeKind.DAY_REMOVED,
                        dayPosition = position,
                        programDayId = previousDay.programDayId,
                        agreesWithPlan = false
                    )
                )
            } else {
                emptyList()
            }

            return Reconciling(
                days = days + listOfNotNull(day),
                changes = changes + preservedChanges + plannedChanges + droppedChanges + removalChanges
            )
        }
    }

    /** One element of the reconciled day, and whether the plan brought it or kept it. */
    private data class ReconciledElement(
        val element: ProgramExercise,
        val kind: ChangeKind,
        val level: PreservationLevel
    )

    /** Which of a day's elements each plan element is satisfied by, and which are left over. */
    private data class Matches(
        val reused: List<ProgramExercise?>,
        val unmatched: List<ProgramExercise>
    )

    /**
     * The plan's elements matched one-to-one against a day's elements.
     *
     * Two occurrences of one exercise are matched as two occurrences (§9) rather than collapsed: a
     * match removes the element it consumed, so the second plan element of the same exercise looks
     * for a second element to be satisfied by.
     */
    private fun List<ProgramExercise>.matchAgainst(planned: List<GeneratedElement>): Matches =
        planned.fold(Matches(emptyList(), this)) { state, element ->
            val index = state.unmatched.indexOfFirst { it.saysTheSameAs(element) }
            if (index < 0) {
                Matches(state.reused + null, state.unmatched)
            } else {
                Matches(
                    reused = state.reused + state.unmatched[index],
                    unmatched = state.unmatched.filterIndexed { position, _ -> position != index }
                )
            }
        }

    /** Whether a plan element is this one: the same exercise, prescribed the same way, per set. */
    private fun ProgramExercise.saysTheSameAs(element: GeneratedElement): Boolean =
        exerciseId == element.exerciseId && prescription == element.prescription

    /** Whether the plan produces an element like this one anywhere on the day — §7's *compatible*. */
    private fun List<GeneratedElement>.agreesWith(element: ProgramExercise): Boolean =
        any { it.exerciseId == element.exerciseId && it.prescription == element.prescription }

    /**
     * Whether the user owns this element — §7's two highest levels together.
     *
     * Both are kept; they differ only in what is reported about them, which is why one predicate
     * answers "may the plan change this?" and [preservationLevel] answers "under which level?".
     */
    private val ProgramExercise.isOwnedByTheUser: Boolean
        get() = isPinned || origin == ProgramExerciseOrigin.USER_AUTHORED

    /** §7's level for an element the user owns: a pin outranks authorship. */
    private fun ProgramExercise.preservationLevel(agreesWithPlan: Boolean): PreservationLevel = when {
        isPinned -> PreservationLevel.PINNED
        agreesWithPlan -> PreservationLevel.COMPATIBLE
        else -> PreservationLevel.USER_OVERRIDE
    }

    /** The plan element as a draft element, with an identity of its own and the generator's origin. */
    private fun GeneratedElement.asGeneratedElement(ids: DraftIdSource): ProgramExercise = ProgramExercise(
        programExerciseId = ProgramExerciseId(ids.newId()),
        exerciseId = exerciseId,
        prescription = prescription,
        origin = ProgramExerciseOrigin.GENERATED,
        isPinned = false
    )

    /**
     * A day the plan added: a training day, unnamed.
     *
     * The type is [ProgramDayType.TRAINING] because a slot is a workout — §8's focus dimension is not
     * a day type, and generated days are all loaded work. The name is left `null` on purpose: the
     * domain does not author user-facing text (§25), so a generated day is presented by its position
     * and its type like any other unnamed day.
     */
    private fun newGeneratedDay(position: Int, ids: DraftIdSource): ProgramDay = ProgramDay(
        programDayId = ProgramDayId(ids.newId()),
        position = position,
        type = ProgramDayType.TRAINING
    )

    /**
     * A day that already exists, kept as the user has it, ready to hold work.
     *
     * Its identity, its name and its type survive — the user renamed it, or typed it, and neither is
     * the plan's to change. The one exception is a rest day: a rest day prescribes nothing (§20) and
     * there is no rest day in a generated plan, so a rest day that now holds elements is a training
     * day. The alternative — dropping the plan's elements to keep the day a rest day — would discard
     * the plan to preserve a type.
     */
    private fun ProgramDay.asWorkDay(): ProgramDay =
        if (type == ProgramDayType.REST) copy(type = ProgramDayType.TRAINING) else this
}
