package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import java.time.LocalDate

/**
 * Groups occurrences that Stage 2 has already resolved. It never evaluates cadence, discovers dates,
 * or crosses date boundaries.
 */
object TargetOccurrenceComposer {
    fun compose(
        occurrences: List<ResolvedScheduleOccurrence>,
        selection: CompositionSelection
    ): List<PlannedOccurrence> {
        validate(occurrences)
        val selectedIds = selection.combinedRuleIds
        val byDate = occurrences.groupBy { it.plannedDate }.toSortedMap()

        return byDate.flatMap { (date, resolvedOnDate) ->
            val separate = resolvedOnDate
                .filterNot { it.ruleId in selectedIds }
                .map { occurrence ->
                    PlannedOccurrence(
                        occurrenceKey = "${occurrence.ruleId}:$date",
                        plannedFor = date,
                        components = listOf(occurrence.component())
                    )
                }
                .sortedBy { it.components.single().ruleId }
            val combinedComponents = resolvedOnDate
                .filter { it.ruleId in selectedIds }
                .map { it.component() }
                .sortedBy { it.ruleId }
            val combined = if (combinedComponents.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    PlannedOccurrence(
                        occurrenceKey = "combined:$date:" +
                            combinedComponents.joinToString("|") { component ->
                                "${component.ruleId.length}:${component.ruleId}"
                            },
                        plannedFor = date,
                        components = combinedComponents
                    )
                )
            }

            separate + combined
        }
    }

    private fun validate(occurrences: List<ResolvedScheduleOccurrence>) {
        val sourceKeys = HashSet<Pair<String, LocalDate>>()
        val workoutByRule = HashMap<String, String>()
        occurrences.forEach { occurrence ->
            require(occurrence.ruleId.isNotBlank()) { "a resolved occurrence needs a rule identity" }
            require(occurrence.workoutId.isNotBlank()) { "a resolved occurrence needs a workout identity" }
            require(sourceKeys.add(occurrence.ruleId to occurrence.plannedDate)) {
                "duplicate resolved occurrence for ${occurrence.ruleId} on ${occurrence.plannedDate}"
            }
            val existingWorkout = workoutByRule.put(occurrence.ruleId, occurrence.workoutId)
            require(existingWorkout == null || existingWorkout == occurrence.workoutId) {
                "resolved rule ${occurrence.ruleId} names multiple workouts"
            }
        }
    }

    private fun ResolvedScheduleOccurrence.component() = OccurrenceComponent(ruleId, workoutId)
}
