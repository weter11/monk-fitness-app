package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramTargetOccurrenceComponentEntity
import com.monkfitness.app.data.model.ProgramTargetOccurrenceEntity
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence

/**
 * The target-occurrence rows ⇄ their domain value (§30 step 14).
 *
 * This file is a **translation**, and the translation is the whole claim of the phase: a
 * [PlannedOccurrence] written here comes back out of [toDomain] as an equal one, with its key, its
 * planned date and every one of its components — in order — exactly as it went in. So the mapping
 * is deliberately dull, and each of its dullnesses is load-bearing:
 *
 *  * `occurrenceKey` is copied. It is never split on `:`, `,` or anything else, never trimmed,
 *    lower-cased, decoded, normalized, re-encoded or compared as a prefix. There is no parsing step
 *    here at all, because the components are stored beside the key and the key never has to be read
 *    to recover them.
 *  * `plannedFor` is converted through [storedDate], so a stored value is either a real date or a
 *    loud failure — never a silently substituted one.
 *  * The components are **not sorted here**. [toDomain] takes them in the order the query returned,
 *    which is the order `position` stores, which is the order the caller presented. A mapper that
 *    reordered them would make a stored occurrence's order meaningless and would hide a write that
 *    wrote the wrong order.
 *  * `ruleId` and `workoutId` are each read out of their own column. Neither is derived from the
 *    other, from the key, from a `ProgramDayId`, from a plan day's `position` or `name`, from a
 *    date, from a weekday, from a list index or from a slot id, and no `"legacy"`-style placeholder
 *    is ever substituted for a missing one.
 *
 * There is no mapper that goes the other way from a slot: a `WorkoutSlot` has no components, so
 * anything that tried to manufacture them from it would be inventing the payload this stage stores.
 */

/**
 * The domain occurrence of one stored parent row plus the components stored under it.
 *
 * The components arrive already ordered by `position`; this function preserves that order as given
 * and does nothing else to it. The list is exactly what was stored — no reordering, no
 * deduplication, no filtering, and no component added when the stored list is short.
 */
internal fun ProgramTargetOccurrenceEntity.toDomain(
    components: List<ProgramTargetOccurrenceComponentEntity>
): PersistedTargetOccurrence = PersistedTargetOccurrence(
    programId = ProgramId(programId),
    occurrence = PlannedOccurrence(
        occurrenceKey = occurrenceKey,
        plannedFor = storedDate("program_target_occurrence.plannedFor", plannedFor),
        components = components.map { it.toDomain() }
    )
)

/**
 * The domain component of one stored component row.
 *
 * Both identities are read out of the columns that hold them and passed through unchanged.
 */
internal fun ProgramTargetOccurrenceComponentEntity.toDomain(): OccurrenceComponent =
    OccurrenceComponent(ruleId = ruleId, workoutId = workoutId)

/**
 * The stored parent row of one occurrence, under the Program the caller states.
 *
 * The identity columns come from the value and the date from the value, so nothing is read out of
 * the occurrence key and nothing is filled in on the value's behalf.
 */
internal fun PersistedTargetOccurrence.toEntity(): ProgramTargetOccurrenceEntity =
    ProgramTargetOccurrenceEntity(
        programId = programId.value,
        occurrenceKey = occurrence.occurrenceKey,
        plannedFor = storedDateValue(occurrence.plannedFor)
    )

/**
 * The stored component rows of one occurrence, one row per component at its presented position.
 *
 * Every component produces a row: none is skipped, none is merged with another, and none is
 * synthesized. The position is the component's own index in the presented list, so the stored order
 * is the caller's order rather than a sorted or deduplicated one.
 */
internal fun PersistedTargetOccurrence.toComponentEntities(): List<ProgramTargetOccurrenceComponentEntity> =
    occurrence.components.mapIndexed { index, component ->
        ProgramTargetOccurrenceComponentEntity(
            programId = programId.value,
            occurrenceKey = occurrence.occurrenceKey,
            position = index,
            ruleId = component.ruleId,
            workoutId = component.workoutId
        )
    }
