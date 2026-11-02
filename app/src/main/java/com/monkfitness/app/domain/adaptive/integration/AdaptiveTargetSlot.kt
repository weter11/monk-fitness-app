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
 * The next not-yet-started, still-startable opportunity of the same Program under the same Revision,
 * strictly after the decision's own day.
 *
 * The date clause is what keeps the rule temporal rather than status-based: a `MISSED` opportunity is
 * startable, so status alone would let the stage target a past day the user has already lived through.
 * Nothing here moves a date or creates a slot; a past-but-startable opportunity is excluded, never
 * rescheduled.
 *
 * This is the plan's next workout, which a family's own [adaptiveTargetSlotOf] is not: a rotating plan
 * presents a given family once every few days, and a family is adapted at *its* next appearance.
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
 * One family's own next opportunity: the earliest opportunity that is still ahead **and whose plan day
 * presents that family**.
 *
 * A rotation presents each family on its own days, so the plan's next workout is usually a day this
 * family does not appear on. Targeting it would attach the adjustment to a workout that never shows the
 * family — it would sit unconsumed and every decision would be a read window.
 *
 * A day presenting the family only through a pinned or user-authored element still counts: ownership is
 * the engine's rule, and skipping such a day here would pre-empt it.
 *
 * @return `null` when the revision holds no further appearance of [familyId].
 */
fun adaptiveTargetSlotOf(
    slots: List<WorkoutSlot>,
    days: List<ProgramDay>,
    completedSlotId: SlotId,
    notBefore: LocalDate,
    familyId: String,
    classification: ExerciseFamilyClassification
): WorkoutSlot? =
    slots
        .filter { slot ->
            slot.slotId != completedSlotId &&
                slot.attempts.isEmpty() &&
                slot.isStartable &&
                slot.plannedFor.isAfter(notBefore)
        }
        .filter { slot -> slot.presents(days, familyId, classification) }
        .minWithOrNull(compareBy({ it.plannedFor }, { it.slotId.value }))

private fun WorkoutSlot.presents(
    days: List<ProgramDay>,
    familyId: String,
    classification: ExerciseFamilyClassification
): Boolean = days
    .firstOrNull { day -> day.programDayId == programDayId }
    ?.exercises
    ?.any { exercise -> classification.familyOf(exercise.exerciseId) == familyId } == true

/**
 * The element of the target opportunity's presentation one decision is about, and the ladder it resolves
 * against.
 *
 * The subject is the family the completion just exposed, looked up in the presentation the user is about
 * to be shown; the presentation's own order decides when a day presents a family twice, so the choice is
 * a property of the plan rather than of a collection order. A family the catalogue declares no ladder for
 * is answered with its element rather than with a relation, which is what lets the caller derive the one
 * rung from what the plan already presents.
 *
 * Ownership is not applied here. A pinned or user-authored element is not adapted **at all**, and
 * skipping it to find the next automatic one would pre-empt the engine's own rule.
 */
suspend fun adaptiveTargetElementOf(
    presented: List<PresentedElement>,
    plan: ProgramDay,
    exposedFamilies: Set<String>,
    classification: ExerciseFamilyClassification,
    relations: ProgressionRelationProvider
): AdaptiveTargetElement {
    var classifiedAnElement = false
    var firstDeclaredNowhere: ProgramAdaptiveElement? = null

    for (element in presented) {
        val familyId = element.familyOf(classification, plan) ?: continue
        classifiedAnElement = true
        if (familyId !in exposedFamilies) continue

        val candidate = ProgramAdaptiveElement(
            presentation = element.presentation,
            familyId = familyId,
            ownership = element.ownership
        )
        val relation = relations.relationOf(familyId)
        if (relation == null) {
            if (firstDeclaredNowhere == null) firstDeclaredNowhere = candidate
            continue
        }
        return AdaptiveTargetElement.Chosen(element = candidate, relation = relation)
    }

    return when {
        !classifiedAnElement -> AdaptiveTargetElement.NoFamilyIsClassified
        firstDeclaredNowhere != null -> AdaptiveTargetElement.NoDeclaredRelation(firstDeclaredNowhere)
        else -> AdaptiveTargetElement.NoExposedFamilyIsPresented
    }
}

/** One element of an opportunity's presentation, plus the ownership a presentation does not state. */
data class PresentedElement(
    val presentation: EffectiveExercise,
    val ownership: ProgramElementOwnership
)

/**
 * An exercise **neither** name knows names no family: the signal layer makes an unknown exercise its own
 * family because it needs a bucket, but a target needs a name a ladder can be declared for, and inventing
 * one from the exercise id would turn "no catalogue" into "undeclared ladder" — two gaps the caller
 * reports separately.
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

sealed interface AdaptiveTargetElement {

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

    data object NoFamilyIsClassified : AdaptiveTargetElement

    data object NoExposedFamilyIsPresented : AdaptiveTargetElement

    /** The element a family with no ladder is presented as: the pass derives its single rung from it. */
    data class NoDeclaredRelation(val element: ProgramAdaptiveElement) : AdaptiveTargetElement
}

fun ProgramExercise.familyOf(classification: ExerciseFamilyClassification): String? =
    classification.familyOf(exerciseId)

/**
 * An element the user pinned is exempt from automatic change whether or not they also edited it, so
 * `pinned` outranks `user-authored`.
 *
 * The origin decides only who owns the *amount*: an element whose load the user handed to the engine is
 * the plan's automatic content to the adaptive stage, which is the whole point of the toggle.
 */
val ProgramExercise.ownership: ProgramElementOwnership
    get() = when {
        isPinned -> ProgramElementOwnership.PINNED
        origin == ProgramExerciseOrigin.USER_AUTHORED -> ProgramElementOwnership.USER_AUTHORED
        else -> ProgramElementOwnership.AUTOMATIC
    }
