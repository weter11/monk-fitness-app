package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDayType

/**
 * §6's **semantic validation**: a document the schema accepted, judged as a program.
 *
 * ```text
 * schema validation      "is this document the shape the format defines?"    the reader
 * semantic validation    "is what it says a program this app can hold?"      this file
 * ```
 *
 * ### The line between the two, exactly
 *
 * This file holds the rules the **domain's own value types would throw on** — a fixed duration of zero
 * days, an empty weekday set, a focus allocation that does not sum to a hundred, a per-set target of
 * zero, a blank exercise id, a prescription in a dimension §10 names but does not implement, a rest day
 * carrying elements. Every one of them is a `require` inside `ProgramDuration`, `ProgramSchedule`,
 * `FocusPlan`, `ProgramDay`, `ProgramExercise` or `Prescription`, so without this layer the *file* would
 * reach those constructors and the failure would surface as a defect instead of as a finding about a
 * file. Deciding them here is what makes the mapping step total: the values it builds cannot be refused.
 *
 * The rules that are about a plan **as a whole** — the Program has a name, it has days, its days are
 * numbered in order, a work day plans something — are deliberately *not* copied here. They belong to
 * [com.monkfitness.app.domain.program.ProgramDraftValidation], which §7 already made the gate between a
 * draft and a save, and §6 asks for exactly that reuse: the draft is validated through the domain's own
 * validation, and its findings arrive as [ProgramTransferIssue.PlanNotSavable] with the domain's
 * sentences. A second copy of "a REST day prescribes nothing, and a TRAINING day must prescribe
 * something" would be a second rule, and a second rule is one that can drift.
 *
 * ### What it never does
 *
 * It does not repair, substitute, drop, reorder or clamp anything (§5, §33): a document with a finding
 * produces findings, and the caller decides what to tell the user. Where a domain value would be
 * *adjusted* to fit — a weekday list sorted into ISO order, a focus list put in canonical order, a
 * percentage rounded — the adjustment is refused instead, because a document that is quietly reordered
 * is a document that says something the user did not write.
 */
object ProgramTransferValidation {

    private const val DURATION: String = "revision.duration"
    private const val SCHEDULE: String = "revision.schedule"
    private const val FOCUS: String = "revision.focus"

    /** The dimensions a plan element may prescribe in — §10's two implemented dimensions. */
    private val SUPPORTED_DIMENSIONS = listOf(
        PrescriptionDimension.REP_BASED,
        PrescriptionDimension.TIME_BASED
    )

    /** Every finding this document has, in document order: duration, schedule, focus, then the plan. */
    fun issuesIn(document: ProgramTransferDocument): List<ProgramTransferIssue> =
        durationIssues(document.revision.duration) +
            scheduleIssues(document.revision.schedule) +
            focusIssues(document.revision.focus) +
            document.revision.days.flatMapIndexed { index, day -> dayIssues(day, index) }

    // ------------------------------------------------------------------ the revision's configuration

    private fun durationIssues(duration: DurationTransfer): List<ProgramTransferIssue> = when (duration) {
        is DurationTransfer.FixedDays -> if (duration.days >= 1) {
            emptyList()
        } else {
            listOf(
                ProgramTransferIssue.InvalidDuration(
                    "$DURATION.days",
                    "a fixed program runs for at least one calendar day (§20), was ${duration.days}"
                )
            )
        }
        DurationTransfer.Indefinite -> emptyList()
    }

    private fun scheduleIssues(schedule: ScheduleTransfer): List<ProgramTransferIssue> =
        when (schedule) {
            is ScheduleTransfer.FixedWeekdays -> {
                val weekdays = schedule.weekdays
                when {
                    weekdays.isEmpty() -> listOf(
                        ProgramTransferIssue.InvalidSchedule(
                            "$SCHEDULE.weekdays",
                            "a fixed-weekday schedule names at least one weekday (§20)"
                        )
                    )
                    weekdays.distinct().size != weekdays.size -> listOf(
                        ProgramTransferIssue.InvalidSchedule(
                            "$SCHEDULE.weekdays",
                            "a weekday is named at most once, found " +
                                "${weekdays.map { it.name }}"
                        )
                    )
                    weekdays != weekdays.sortedBy { day -> day.value } -> listOf(
                        ProgramTransferIssue.InvalidSchedule(
                            "$SCHEDULE.weekdays",
                            "the weekdays are held in ISO order (Monday first), found " +
                                "${weekdays.map { it.name }}"
                        )
                    )
                    else -> emptyList()
                }
            }

            is ScheduleTransfer.FlexiblePerWeek -> if (schedule.sessionsPerWeek in 1..7) {
                emptyList()
            } else {
                listOf(
                    ProgramTransferIssue.InvalidSchedule(
                        "$SCHEDULE.sessionsPerWeek",
                        "a flexible schedule trains between 1 and 7 days a week (§20), was " +
                            "${schedule.sessionsPerWeek}"
                    )
                )
            }
        }

    private fun focusIssues(focus: FocusTransfer): List<ProgramTransferIssue> = when (focus) {
        FocusTransfer.Balanced -> emptyList()

        is FocusTransfer.Focused -> {
            val focuses = focus.focuses
            when {
                focuses.isEmpty() -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.focuses",
                        "a FOCUSED plan names the focuses it is built around; a plan that names none " +
                            "is the BALANCED configuration (§8)"
                    )
                )
                focuses.distinct().size != focuses.size -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.focuses",
                        "a focus is named at most once, found ${focuses.map { it.name }}"
                    )
                )
                focuses != FocusPlan.canonical(focuses) -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.focuses",
                        "the focuses are held in the vocabulary's own order, found " +
                            "${focuses.map { it.name }} instead of " +
                            "${FocusPlan.canonical(focuses).map { it.name }}"
                    )
                )
                else -> emptyList()
            }
        }

        is FocusTransfer.Custom -> {
            val allocations = focus.allocations
            val focuses = allocations.map { allocation -> allocation.focus }
            val total = allocations.sumOf { allocation -> allocation.percent }
            val notAShare = allocations.firstOrNull { allocation -> allocation.percent <= 0 }
            when {
                allocations.isEmpty() -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.allocations",
                        "a CUSTOM plan states at least one share; a plan that states none is the " +
                            "BALANCED configuration (§8)"
                    )
                )
                focuses.distinct().size != focuses.size -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.allocations",
                        "a focus carries one share, found ${focuses.map { it.name }} (§8)"
                    )
                )
                focuses != FocusPlan.canonical(focuses) -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.allocations",
                        "the shares are held in the vocabulary's own order, found " +
                            "${focuses.map { it.name }} instead of " +
                            "${FocusPlan.canonical(focuses).map { it.name }}"
                    )
                )
                notAShare != null -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.allocations[${allocations.indexOf(notAShare)}].percent",
                        "a share of ${notAShare.percent}% is not an allocation: a focus the user did " +
                            "not ask for is left out of the configuration (§8)"
                    )
                )
                total != FocusPlan.FULL_ALLOCATION -> listOf(
                    ProgramTransferIssue.InvalidFocus(
                        "$FOCUS.allocations",
                        "custom focus percentages must sum to ${FocusPlan.FULL_ALLOCATION}%, was " +
                            "$total%: " + allocations.joinToString(", ") { allocation ->
                            "${allocation.focus.name}=${allocation.percent}%"
                        }
                    )
                )
                else -> emptyList()
            }
        }
    }

    // ------------------------------------------------------------------ the plan

    private fun dayIssues(day: DayTransfer, index: Int): List<ProgramTransferIssue> {
        val path = "revision.days[$index]"
        val nameIssue = if (day.name != null && day.name.isBlank()) {
            listOf(ProgramTransferIssue.BlankDayName("$path.name"))
        } else {
            emptyList()
        }
        val restIssue = if (day.type == ProgramDayType.REST && day.exercises.isNotEmpty()) {
            listOf(ProgramTransferIssue.RestDayWithExercises(path, day.exercises.size))
        } else {
            emptyList()
        }
        return nameIssue + restIssue +
            day.exercises.flatMapIndexed { position, element ->
                elementIssues(element, "$path.exercises[$position]")
            }
    }

    private fun elementIssues(
        element: ExerciseTransfer,
        path: String
    ): List<ProgramTransferIssue> {
        val blankId = if (element.exerciseId.isBlank()) {
            listOf(ProgramTransferIssue.BlankExerciseId("$path.exerciseId"))
        } else {
            emptyList()
        }
        val dimension = if (element.prescription.dimension in SUPPORTED_DIMENSIONS) {
            emptyList()
        } else {
            listOf(
                ProgramTransferIssue.UnsupportedPrescriptionDimension(
                    "$path.prescription.dimension",
                    element.prescription.dimension
                )
            )
        }
        val targets = element.prescription.perSetTargets
        val empty = if (targets.isEmpty()) {
            listOf(ProgramTransferIssue.EmptyPrescription("$path.prescription.perSetTargets"))
        } else {
            emptyList()
        }
        val notWork = targets.withIndex()
            .filter { (_, target) -> target <= 0 }
            .map { (position, target) ->
                ProgramTransferIssue.NonPositiveTarget(
                    "$path.prescription.perSetTargets[$position]",
                    target
                )
            }
        return blankId + dimension + empty + notWork
    }
}
