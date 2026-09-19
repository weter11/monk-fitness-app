package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.decision.presentedWorkout
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.LocalDate

/**
 * The **target opportunity** of one adaptive decision, chosen by one deterministic rule (§4).
 *
 * ```text
 * the next not-yet-started, still-startable opportunity
 * of the same Program
 * under the same Revision the completed Session belonged to
 * ```
 *
 * ### Why each clause is there
 *
 *  * **same Revision.** Adaptive state is revision-scoped: a newly saved revision starts from its own
 *    baseline, and an edit must not let a session that ran under an old plan adapt a different one (§4,
 *    §18). The revision is also what makes the adjustment *applicable*: its `before` and `after` are
 *    elements of a plan day, and a later revision mints new day and element identities, so an adjustment
 *    can only be composed against the revision that presents the element it changes — the composition
 *    [presentedWorkout] performs and `SessionRuntime` refuses to perform otherwise;
 *  * **not yet started.** An adjustment is *consumed* when a slot's session snapshot is taken (§16), so
 *    an opportunity that already has an attempt can no longer present it. Its snapshot is frozen and no
 *    later decision may re-explain it (§19);
 *  * **still startable.** A taken or withdrawn opportunity is not ahead of the user, so a change
 *    targeted at one could never be shown. `isStartable` is the domain's own statement of the statuses
 *    a session may still be started for — the same predicate `SessionRuntime.startRefusal` is stated
 *    over — which is what keeps "which opportunities are ahead" a single fact rather than two lists;
 *  * **the completed one is excluded.** The completion just took it: it is not ahead of the user, and
 *    §4 forbids an adaptation targeting the already-completed current slot outright;
 *  * **its day is still to come** — *strictly* after the decision's own day. This is the clause that keeps
 *    the rule *temporal* and not merely *status-based*: a `MISSED` opportunity is startable (the runtime
 *    will begin one, and §20 neither slides a missed slot nor forbids training it late), so **status alone
 *    would let the adaptive stage target a past day** — an opportunity the user has already lived through,
 *    whose adjustment would then sit unconsumed while the user trains whatever today's schedule actually
 *    holds. "Future" therefore has to mean a date, and the date is read in the injected zone (§26).
 *
 * ### What it deliberately does not do
 *
 * It creates no slot, moves no date, renumbers no plan day, supersedes nothing and reschedules nothing:
 * §25 forbids the adaptive layer from touching opportunity timing, so "the next opportunity" is *the
 * next one the schedule already holds*, in the order the schedule holds them, and the only date it reads
 * is the one it is handed. A past-but-startable opportunity is *excluded*, never moved — sliding a missed
 * workout is the Scheduler's business (§20) and never the adaptive stage's.
 *
 * @param slots the revision's opportunities, in any order.
 * @param completedSlotId the opportunity the completed Session took.
 * @param notBefore the decision's own calendar date in the caller's zone. An opportunity planned for
 *   that day or earlier is not strictly ahead of the decision — an opportunity planned for *today* may
 *   already be under way, and one planned for yesterday has passed — so the rule takes the first one
 *   planned for a later day.
 * @return the eligible opportunity with the earliest planned date — identity as the tiebreak — or `null`
 *   when none is eligible, which §4 makes an expected result rather than an exception.
 */
fun adaptiveTargetSlotOf(
    slots: List<WorkoutSlot>,
    completedSlotId: SlotId,
    notBefore: LocalDate
): WorkoutSlot? =
    slots
        .filter { slot ->
            slot.slotId != completedSlotId &&
                slot.attempts.isEmpty() &&
                slot.isStartable &&
                slot.plannedFor.isAfter(notBefore)
        }
        .minWithOrNull(compareBy({ it.plannedFor }, { it.slotId.value }))

/**
 * Which element **of the target opportunity's presentation** one adaptive decision is about — and the
 * ladder it resolves against, or the exact reason there is none.
 *
 * ### The rule
 *
 * ```text
 * the first element of the opportunity's presentation, in the presentation's own order,
 * whose presented exercise's family is one the completed Session exposed
 * and for which a ladder is declared
 * ```
 *
 * The subject of a decision is the family the completion just trained — that is what its window, its
 * evidence and its signals are about (§6, §11) — and the target is that family's **next presentation**,
 * so the element is looked up in the opportunity the user is about to be shown. Three facts follow, and
 * all three are stated rather than hoped for:
 *
 *  * a day that trains no family the completion exposed yields
 *    [AdaptiveTargetElement.NoExposedFamilyIsPresented] — the family's window opens again when its own
 *    next appearance is completed, which is when its evidence has moved on. Adapting some other element
 *    instead would pick a subject the completion says nothing about;
 *  * the presentation's own order decides which element of a family is the subject when the day presents
 *    a family more than once, so the choice is a property of the plan rather than of a collection order.
 *    Repeated use of an exercise is legal (§9) and every occurrence is its own element, so this cannot be
 *    resolved by exercise id without inventing a preference between two presentations of one plan;
 *  * the presentation is composed by the domain's own `presentedWorkout` — the plan day plus the
 *    adjustments still standing for the opportunity (§16) — so an element that already carries an
 *    adjustment is decided about **as the user would see it**, and the adjustment the new decision
 *    supersedes is the one that stands for that very element. This is why the parameter is an
 *    `EffectiveWorkout` and not a plan day: a second composition here would be a second answer to *"what
 *    does this opportunity present?"*.
 *
 * ### Ownership is not applied here
 *
 * The chosen element carries what the plan says about it — [ProgramExercise.isPinned] and
 * [ProgramExercise.origin], in that order of precedence — and the engine decides what that means: an
 * element the user authored or pinned is not adapted **at all** (§15, §18), which is a rule this layer
 * must not pre-empt. Skipping a user-owned element to find the next automatic one would be this layer
 * deciding the question the engine already answers, and it would silently adapt an element *after* the
 * user's own choice had been considered and skipped.
 *
 * @param presented the opportunity's presentation, composed by [presentedWorkout].
 * @param plan the plan day the opportunity presents — the source of each element's ownership.
 * @param exposedFamilies the families the completed Session observed work for, by the caller's own
 *   classification.
 * @param classification the caller's exercise-to-family classification.
 * @param relations the ladder source (§10).
 */
fun adaptiveTargetElementOf(
    presented: List<PresentedElement>,
    plan: ProgramDay,
    exposedFamilies: Set<String>,
    classification: ExerciseFamilyClassification,
    relations: ProgressionRelationProvider
): AdaptiveTargetElement {
    var classifiedAnElement = false
    var presentedAnExposedFamily = false

    for (element in presented) {
        val familyId = element.familyOf(classification, plan) ?: continue
        classifiedAnElement = true
        if (familyId !in exposedFamilies) continue
        presentedAnExposedFamily = true

        val relation = relations.relationOf(familyId) ?: continue
        return AdaptiveTargetElement.Chosen(
            element = ProgramAdaptiveElement(
                presentation = element.presentation,
                familyId = familyId,
                ownership = element.ownership
            ),
            relation = relation
        )
    }

    return when {
        !classifiedAnElement -> AdaptiveTargetElement.NoFamilyIsClassified
        presentedAnExposedFamily -> AdaptiveTargetElement.NoDeclaredRelation
        else -> AdaptiveTargetElement.NoExposedFamilyIsPresented
    }
}

/**
 * One element of an opportunity's presentation, with the one fact a presentation does not state: the
 * plan element's ownership.
 *
 * @property presentation what the opportunity presents for this element (§16): the plan element's own
 *   exercise and prescription, or the ones a standing adjustment put in their place.
 * @property ownership whether this element is the plan's automatic content or the user's (§7, §15).
 */
data class PresentedElement(
    val presentation: EffectiveExercise,
    val ownership: ProgramElementOwnership
)

/**
 * The family one presented element belongs to, by the caller's classification.
 *
 * The presented exercise is classified first — an element a standing adjustment re-pointed is classified
 * as the variant the user would actually be shown — and the plan's own exercise is the fallback for a
 * variant the classification does not know.
 *
 * An exercise **neither** knows names no family here, and that is the difference between this choice and
 * the signal layer's own convention (§13, where an unclassified exercise is its own family so it is
 * compared with itself and nothing else). The signal layer needs a *bucket*; a target element needs a
 * **name a ladder can be declared for**, and inventing one out of the exercise id would turn *"this app
 * has no family catalogue"* into *"this family's ladder is undeclared"* — two different gaps that
 * [AdaptiveTargetElement] reports separately, and only one of which is the caller's to fix.
 */
private fun PresentedElement.familyOf(
    classification: ExerciseFamilyClassification,
    plan: ProgramDay
): String? {
    classification.familyOf(presentation.exerciseId)?.let { return it }
    return plan.exercises
        .firstOrNull { it.programExerciseId == presentation.programExerciseId }
        ?.let { exercise -> classification.familyOf(exercise.exerciseId) }
}

/** What [adaptiveTargetElementOf] found: the element and its ladder, or the gap that stopped it. */
sealed interface AdaptiveTargetElement {

    /** The element the decision is about and the family's own declared ladder. */
    data class Chosen(
        val element: ProgramAdaptiveElement,
        val relation: ProgramProgressionRelation
    ) : AdaptiveTargetElement {

        init {
            require(element.familyId == relation.familyId) {
                "the element and the ladder that resolves it are about one family: " +
                    "element=${element.familyId} relation=${relation.familyId}"
            }
        }
    }

    /** No element of the opportunity's presentation belongs to a family the caller classifies. */
    data object NoFamilyIsClassified : AdaptiveTargetElement

    /** The opportunity presents none of the families the completion exposed. */
    data object NoExposedFamilyIsPresented : AdaptiveTargetElement

    /** The opportunity presents an exposed family, and no ladder is declared for it (§10). */
    data object NoDeclaredRelation : AdaptiveTargetElement
}

/**
 * The family one plan element's exercise belongs to, by the caller's classification — or `null` when the
 * classification does not know it, exactly as [PresentedElement.familyOf] answers.
 */
fun ProgramExercise.familyOf(classification: ExerciseFamilyClassification): String? =
    classification.familyOf(exerciseId)

/**
 * Who authored a plan element, in the adaptive engine's vocabulary (§7, §15).
 *
 * `pinned` outranks `user-authored`: a pinned element is exempt from automatic change whether or not the
 * user also edited it, and an element the generator produced and the user then edited is `USER_AUTHORED`
 * — the two facts the plan stores ([ProgramExerciseOrigin], [ProgramExercise.isPinned]) are exactly the
 * two levels of precedence, and the mapping states which wins.
 */
val ProgramExercise.ownership: ProgramElementOwnership
    get() = when {
        isPinned -> ProgramElementOwnership.PINNED
        origin == ProgramExerciseOrigin.USER_AUTHORED -> ProgramElementOwnership.USER_AUTHORED
        else -> ProgramElementOwnership.AUTOMATIC
    }
