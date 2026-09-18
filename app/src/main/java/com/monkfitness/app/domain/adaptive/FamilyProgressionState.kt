package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant

/**
 * One exercise family's **current** progression state, as the Program System stores it (§23
 * `FamilyProgressionState`).
 *
 * This is the stored counterpart of the state the adaptive stage carries between decisions: a family
 * has exactly one current state per revision, which is why the identity is
 * `(revisionId, familyId)` rather than a caller convention, and why a new revision starts from
 * baseline while the previous revision's rows stay readable (§16, §30 step 11).
 *
 * It deliberately is **not** the Stage-1 `FamilyAdaptationState`: that type carries the pilot's five
 * hysteresis counters and the policy position, and its state is scoped by the legacy revision
 * integer. None of those is stored in the target table (§23 names only the fields below), so
 * projecting a row into that type would mean inventing counts, and a fabricated confirmation count
 * would let the program move faster than the user earned. The two generations stay separate until
 * §30 step 15 retires the legacy one.
 *
 * Two consequences of that separation are worth stating, because both are easy to get wrong:
 *
 *  * **no level bound is claimed here.** The §10 progression axis of the target engine is not this
 *    stage's design, and the pilot's `-2..+2` range is a property of the pilot's own axis; the field
 *    is therefore carried as an integer with no invented range.
 *  * **the write stamp is persistence, not progression.** [updatedAt] records when the row was last
 *    written and is stamped by the repository that writes it, never by a policy or an engine.
 *
 * @property revisionId the revision this state belongs to. A decision never creates a revision, and
 *   neither does a state change: the state follows the revision it was made under (§16).
 * @property familyId the exercise family this state belongs to, by id. The domain owns no family
 *   catalogue.
 * @property progressionLevel the family's abstract position on its own progression axis.
 * @property adaptationState the state the family currently holds.
 * @property currentExerciseId the exercise id the family is currently on, or `null` when it carries
 *   none.
 * @property updatedAt when this row was last written.
 */
data class FamilyProgressionState(
    val revisionId: RevisionId,
    val familyId: String,
    val progressionLevel: Int,
    val adaptationState: AdaptiveState,
    val currentExerciseId: String? = null,
    val updatedAt: Instant
) {

    init {
        require(familyId.isNotBlank()) { "a family progression state must name its family" }
    }

    /** Whether the family currently carries a concrete exercise. */
    val carriesExercise: Boolean
        get() = currentExerciseId != null
}
