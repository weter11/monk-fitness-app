package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan

/**
 * What one planned slot is **for**: one primary focus and `0–2` secondary focuses.
 *
 * This is the Focus Planner's unit of decision and the reason focus is not a day type. A primary is
 * always present, secondaries are at most [GenerationPolicy.MAX_SECONDARY_FOCUSES], no focus appears
 * twice in one slot, and the primary is never also a secondary (a workout whose emphasis is `PUSH`
 * does not *secondarily* train `PUSH`).
 *
 * The secondaries are held in the **canonical** vocabulary order, not in the order the allocation
 * arithmetic happened to pick them, so two assignments stating the same emphasis are the same value
 * however the planner reached them — freezing the working order would make plan equality depend on an
 * internal ranking step.
 */
data class FocusAssignment(
    val primary: Focus,
    val secondary: List<Focus> = emptyList()
) {

    init {
        require(secondary.size <= GenerationPolicy.MAX_SECONDARY_FOCUSES) {
            "a slot takes 1 primary focus and 0..${GenerationPolicy.MAX_SECONDARY_FOCUSES} secondary " +
                "focuses, found ${secondary.size + 1} focuses (§8)"
        }
        require(secondary.distinct().size == secondary.size) {
            "a focus is assigned to a slot once, found $secondary"
        }
        require(primary !in secondary) {
            "the primary focus is not repeated among the secondaries: $primary / $secondary"
        }
        require(secondary == FocusPlan.canonical(secondary)) {
            "the secondaries of an assignment are held in the vocabulary's own order, found " +
                "$secondary instead of ${FocusPlan.canonical(secondary)}"
        }
    }

    /** Every focus this slot trains, the primary first. */
    val focuses: List<Focus>
        get() = listOf(primary) + secondary

    /** Whether this slot trains [focus]. */
    fun trains(focus: Focus): Boolean = focus == primary || focus in secondary
}

/**
 * One plan element the generator produced: which focus of the slot it serves, which exercise, and
 * what it prescribes.
 *
 * [focus] is not decoration: the planner allocates exposure **per focus** and plans one element per
 * assigned focus, so the element's focus is what lets a screen or a test say *which* of a workout's
 * focuses an exercise is there for without re-deriving it. [familyId] travels with the element because
 * selection is on family membership and family load is a dimension of its own; it is copied from the
 * candidate and never resolved here.
 *
 * Prescriptions belong to the plan and never to the library: the value is built from the exercise's own
 * dimension and the policy's targets, and nothing here reads or writes exercise metadata.
 */
data class GeneratedElement(
    val focus: Focus,
    val exerciseId: String,
    val familyId: String,
    val prescription: Prescription
)

/**
 * One generated plan day: its place in the plan, what it is for, and the elements that express it.
 *
 * A slot plans **exactly one element per assigned focus, in the assignment's own order** (primary
 * first), and the invariant is asserted rather than assumed: that is what makes "1 primary + 0–2
 * secondary" a statement about the workout rather than a label on it, and it is why a slot can never
 * carry an element for a focus it did not assign.
 */
data class GeneratedSlot(
    val position: Int,
    val assignment: FocusAssignment,
    val elements: List<GeneratedElement>
) {

    init {
        require(position >= 1) { "a slot's position is 1-based, was $position" }
        require(elements.map { it.focus } == assignment.focuses) {
            "slot $position plans one element per assigned focus, in the assignment's order: " +
                "assigned ${assignment.focuses}, planned ${elements.map { it.focus }}"
        }
    }
}

/**
 * Why a focus cannot be planned at all by a request.
 *
 * The three reasons are kept apart because they mean three different things to a user: *no exercise
 * of this focus is in your selection*, *the equipment you have cannot support any of them*, and *this
 * focus is only available in a prescription dimension the app does not generate yet*. None of them is
 * a silent substitution or a violated hard constraint — the plan simply does not contain that focus,
 * and says why.
 */
enum class FocusUnusableReason {

    /** The user's allowed exercises contain nothing that trains this focus. */
    NO_EXERCISE_TRAINS_THE_FOCUS,

    /** Every exercise that trains this focus needs equipment the user does not have. */
    EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT,

    /** Every exercise that trains this focus is prescribed in a dimension with no subtype yet. */
    PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED
}

/**
 * Something the planner could not do, reported rather than worked around.
 *
 * An expected state is a *result*, for a pure decision as much as for a repository call. A generator
 * whose selection makes a focus unplannable has not failed — it has produced a plan that does not
 * contain that focus, and the honest output says so instead of filling the gap with an exercise the
 * equipment forbids or a prescription the domain cannot state. Silently violating a hard constraint or
 * silently substituting a focus are exactly what this type exists to make impossible.
 */
sealed interface GenerationLimitation {

    /** The user-facing sentence, naming the value that was found rather than a rule that failed. */
    val message: String

    /** A focus of the requested configuration that no usable exercise can plan. */
    data class UnusableFocus(
        val focus: Focus,
        val reason: FocusUnusableReason
    ) : GenerationLimitation {

        override val message: String = when (reason) {
            FocusUnusableReason.NO_EXERCISE_TRAINS_THE_FOCUS ->
                "${focus.name} cannot be planned: none of the allowed exercises trains it"
            FocusUnusableReason.EVERY_EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT ->
                "${focus.name} cannot be planned: every exercise that trains it needs equipment the " +
                    "user does not have"
            FocusUnusableReason.PRESCRIPTION_DIMENSION_NOT_IMPLEMENTED ->
                "${focus.name} cannot be planned: every exercise that trains it is prescribed in a " +
                    "dimension this stage does not generate"
        }
    }

    /** No focus of the configuration can be planned, so the plan holds no slot at all. */
    data object NoPlannableFocus : GenerationLimitation {

        override val message: String =
            "no focus of this configuration can be planned from the allowed exercises, the available " +
                "equipment and the implemented prescription dimensions, so the plan has no slot"
    }
}

/**
 * The Generated Planner's output: a plan of slots, each with its focuses and its elements.
 *
 * It is a **value and not a draft**: no `ProgramDay`, no `ProgramExercise` and above all no identity
 * appears here, because a produced plan is not yet a plan the user is editing. Turning it into a draft
 * — and reconciling it with what the user already has — is [ProgramGeneratedEditor]'s job, and it is
 * the only place draft identities are minted.
 *
 * It persists nothing, and cannot: the type holds no repository, no DAO, no session, no slot, no date
 * and no clock, so a generation pass has no way to write anything anywhere.
 */
data class GeneratedPlan(
    val focus: FocusPlan,
    val slots: List<GeneratedSlot>,
    val limitations: List<GenerationLimitation> = emptyList()
) {

    init {
        require(slots.map { it.position } == (1..slots.size).toList()) {
            "a plan's slots are numbered 1..${slots.size} in order, found ${slots.map { it.position }}"
        }
    }

    val slotCount: Int
        get() = slots.size

    /** How many elements the whole plan prescribes, counting every occurrence. */
    val elementCount: Int
        get() = slots.sumOf { it.elements.size }

    val assignments: List<FocusAssignment>
        get() = slots.map { it.assignment }

    /**
     * How many assignments each focus received, in the vocabulary's canonical order, omitting the
     * focuses the plan does not train.
     *
     * This is the plan's **own** exposure, in the unit the Focus Planner allocates: a slot that trains
     * three focuses contributes one to each of the three, so the figure is not a count of workouts and
     * must not be read as one.
     */
    val focusCounts: Map<Focus, Int>
        get() = Focus.entries
            .map { focus -> focus to slots.sumOf { slot -> slot.assignment.focuses.count { it == focus } } }
            .filter { (_, count) -> count > 0 }
            .toMap()

    /** Whether [focus] is one of the focuses this plan could not plan. */
    fun isUnplannable(focus: Focus): Boolean =
        limitations.any { it is GenerationLimitation.UnusableFocus && it.focus == focus }
}
