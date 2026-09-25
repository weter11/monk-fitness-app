package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PlannedOccurrence

/** Immutable result of comparing stored occurrence facts with a replacement target plan. */
data class TargetOccurrenceReconciliation(
    val preserved: List<ExistingOccurrence>,
    val superseded: List<ExistingOccurrence>,
    val added: List<PlannedOccurrence>
)

/**
 * Reconciles target-domain occurrence values without resolving dates or touching execution state.
 * Occurrence identity is supplied by Stage 3 and is the only membership key.
 */
object TargetOccurrenceReconciler {
    fun reconcile(
        existing: List<ExistingOccurrence>,
        replacement: List<PlannedOccurrence>
    ): TargetOccurrenceReconciliation {
        val existingByKey = indexExisting(existing)
        val replacementByKey = indexReplacement(replacement)
        replacementByKey.forEach { (key, occurrence) ->
            val existingOccurrence = existingByKey[key]
            require(existingOccurrence == null || existingOccurrence.occurrence == occurrence) {
                "replacement occurrence $key conflicts with the existing occurrence payload"
            }
        }

        val replacementKeys = replacementByKey.keys
        val preserved = existing
            .filter { it.execution != OccurrenceExecution.PLANNED }
            .canonicalExistingOrder()
        val superseded = existing
            .filter {
                it.execution == OccurrenceExecution.PLANNED &&
                    it.occurrence.occurrenceKey !in replacementKeys
            }
            .canonicalExistingOrder()
        val added = replacement
            .filter { it.occurrenceKey !in existingByKey }
            .canonicalPlannedOrder()

        return TargetOccurrenceReconciliation(preserved, superseded, added)
    }

    private fun indexExisting(existing: List<ExistingOccurrence>): Map<String, ExistingOccurrence> {
        val result = LinkedHashMap<String, ExistingOccurrence>()
        existing.forEach { occurrence ->
            val key = occurrence.occurrence.occurrenceKey
            require(result.put(key, occurrence) == null) { "duplicate existing occurrence key $key" }
        }
        return result
    }

    private fun indexReplacement(replacement: List<PlannedOccurrence>): Map<String, PlannedOccurrence> {
        val result = LinkedHashMap<String, PlannedOccurrence>()
        replacement.forEach { occurrence ->
            require(result.put(occurrence.occurrenceKey, occurrence) == null) {
                "duplicate replacement occurrence key ${occurrence.occurrenceKey}"
            }
        }
        return result
    }

    private fun List<ExistingOccurrence>.canonicalExistingOrder(): List<ExistingOccurrence> =
        sortedWith(compareBy<ExistingOccurrence> { it.occurrence.plannedFor }
            .thenBy { it.occurrence.occurrenceKey })

    private fun List<PlannedOccurrence>.canonicalPlannedOrder(): List<PlannedOccurrence> =
        sortedWith(compareBy<PlannedOccurrence> { it.plannedFor }
            .thenBy { it.occurrenceKey })
}
