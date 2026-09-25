package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.PlannedOccurrence

/**
 * One stored target occurrence: **semantic schedule data and nothing else**.
 *
 * A target slot persisted by Stage 10 remembers only `targetOccurrenceKey`. The occurrence that key
 * names also carries [PlannedOccurrence.components] — the ordered rule and workout identities that
 * say *what* the occurrence is — and a slot row has nowhere to put them. This type is that missing
 * half as a value: the whole `PlannedOccurrence`, stored verbatim and read back verbatim.
 *
 * ### Why this is not `ExistingOccurrence`
 *
 * The two are deliberately different types, and the difference is the whole point of this stage:
 *
 * ```text
 * PersistedTargetOccurrence   semantic schedule data
 * ExistingOccurrence          semantic schedule data + execution state + actual results
 * ```
 *
 * [com.monkfitness.app.domain.program.ExistingOccurrence] adds an `OccurrenceExecution` and a list of
 * `ActualResult`, and neither can be recovered from storage by this stage:
 *
 *  * `WorkoutSlot.status` does not encode `STARTED` versus `CANCELLED` — it has no member for either;
 *  * attempts live in `WorkoutSession`, not in the slot row;
 *  * the work actually performed lives in the session graph and the set log.
 *
 * So this type stops at the semantic boundary on purpose. Reconstructing execution is a later
 * phase's question, with a different set of rules, and inventing one here would be inventing it
 * twice. Nothing in this file constructs an execution state, derives a result, or reads a session.
 *
 * ### The identity, and nothing else
 *
 * The membership identity of a target occurrence is exactly `(programId, occurrenceKey)`. Two
 * Programs may hold the same occurrence key without either being the other's, and one Program
 * cannot hold the same key twice. [occurrenceKey] is an opaque token: it is stored as written,
 * compared as written, and read back as written. It is never split, trimmed, normalized, decoded,
 * sorted, re-encoded, or turned back into a `ruleId`, a `workoutId` or a date, because the key's own
 * text is not a serialization of anything.
 *
 * The same rule holds for the components: [ruleId] and [workoutId] are stored as the two
 * independent identities they are. They are not derived from a `ProgramDayId`, a plan day's
 * `position` or `name`, a date, a weekday, a list index or a slot id, and no component is ever
 * fabricated, defaulted, substituted or filled in to make a record look complete.
 */
data class PersistedTargetOccurrence(
    val programId: ProgramId,
    val occurrence: PlannedOccurrence
) {

    init {
        require(occurrence.occurrenceKey.isNotBlank()) {
            "a persisted target occurrence needs a non-blank occurrence identity"
        }
        require(occurrence.components.isNotEmpty()) {
            "a persisted target occurrence must contain work: ${occurrence.occurrenceKey}"
        }
        require(occurrence.components.none { component -> component.isBlank() }) {
            "a persisted target occurrence's components need real identities: ${occurrence.occurrenceKey}"
        }
    }

    /** The stored occurrence's own key — read, never parsed. */
    val occurrenceKey: String get() = occurrence.occurrenceKey

    /**
     * Whether [other] is this occurrence's exact semantic payload.
     *
     * Equality is over [PlannedOccurrence]'s own `data class` equality, which compares the key, the
     * planned date and the component list **in order**. Order is semantic: it is the order the
     * caller presented, and reordering a stored payload is a different payload, not the same one
     * written twice. Nothing here compares component *sets*, ignores order, or normalizes the two
     * sides before deciding.
     */
    fun hasSamePayloadAs(other: PersistedTargetOccurrence): Boolean =
        programId == other.programId && occurrence == other.occurrence

    private fun OccurrenceComponent.isBlank(): Boolean = ruleId.isBlank() || workoutId.isBlank()
}

/**
 * The one refusal a target semantic record can produce.
 *
 * A repeated write of the same `(programId, occurrenceKey)` is **idempotent only when the persisted
 * payload is identical**. A write that would change [storedPayload] — a different planned date, a
 * different component identity, a different component order, or a different component set — is
 * refused with [ConflictingSemanticPayload] and the stored record is left exactly as it was. It is
 * never overwritten, never merged, never partially updated and never dropped so the new payload can
 * take its place.
 *
 * [storedPayload] and [requestedPayload] are the two payloads as they stand, so the refusal says
 * which record was found and which one was refused rather than only that something differed.
 */
sealed class TargetOccurrencePersistenceException(
    message: String
) : IllegalStateException(message) {

    /**
     * A second write named an existing target identity with a different semantic payload.
     *
     * @property programId the Program the record belongs to; a payload can never move between them.
     * @property occurrenceKey the stored occurrence's own key, as text and never as a source of anything.
     * @property storedPayload the semantic payload the record already holds.
     * @property requestedPayload the payload the refused write offered.
     */
    data class ConflictingSemanticPayload(
        val programId: ProgramId,
        val occurrenceKey: String,
        val storedPayload: PlannedOccurrence,
        val requestedPayload: PlannedOccurrence
    ) : TargetOccurrencePersistenceException(
        "target occurrence $occurrenceKey of ${programId.value} already exists with a different " +
            "semantic payload: stored $storedPayload, refused $requestedPayload"
    )
}
