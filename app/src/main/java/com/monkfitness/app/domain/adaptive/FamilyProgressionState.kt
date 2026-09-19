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
 * ### The window bookkeeping (§15, §18 step 12)
 *
 * The last five fields are the facts a **single** window cannot state about itself and that the
 * engine takes as inputs ([ProgramAdaptiveWindow]): the confirmation counts, the cooldown position
 * and the recovery exit count. They are stored because they are *state* — each window's verdict
 * advances them, and a window cannot count itself.
 *
 * They are deliberately **not** the Stage-1 `FamilyAdaptationState`'s five counters copied over. The
 * pilot's counters are scoped by the legacy revision integer and describe the pilot's own state
 * machine, which the target tree does not share (see the note above on the two generations); these
 * five are the target engine's own window facts, read by the target policy, and they arrive here
 * because §30 step 12's integration proved they cannot be reconstructed from the decision trail (the
 * argument is in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md`). What each one means is the window type's
 * vocabulary, not this entity's:
 *
 *  * [precedingProgressQualifyingWindows] / [precedingRegressQualifyingWindows] — consecutive windows
 *    *before* this one in which §7's progression (or regression) conditions held;
 *  * [precedingRecoveryQualifyingWindows] — the same, for §14's reduced-absorption pattern;
 *  * [qualifyingWindowsSinceLastChange] — the cooldown position: eligible windows since the family's
 *    last progression-level change, or `null` when it has never had one. `null` is not `0`: the
 *    cooldown exists to stop oscillation *after* a change, so the first earned change has none to
 *    serve ([ProgramAdaptiveWindow] states the same distinction on the engine's side);
 *  * [recoveryQualifyingWindows] — windows completed while the family is in recovery: §14's exit gate.
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
 * @property precedingProgressQualifyingWindows consecutive preceding windows in which the progression
 *   conditions held, `>= 0`.
 * @property precedingRegressQualifyingWindows the same, for the regression conditions.
 * @property precedingRecoveryQualifyingWindows the same, for §14's recovery-entry pattern.
 * @property qualifyingWindowsSinceLastChange eligible windows since the family's last level change, or
 *   `null` when it has never had one.
 * @property recoveryQualifyingWindows windows completed while the family is in recovery.
 */
data class FamilyProgressionState(
    val revisionId: RevisionId,
    val familyId: String,
    val progressionLevel: Int,
    val adaptationState: AdaptiveState,
    val currentExerciseId: String? = null,
    val updatedAt: Instant,
    val precedingProgressQualifyingWindows: Int = 0,
    val precedingRegressQualifyingWindows: Int = 0,
    val precedingRecoveryQualifyingWindows: Int = 0,
    val qualifyingWindowsSinceLastChange: Int? = null,
    val recoveryQualifyingWindows: Int = 0
) {

    init {
        require(familyId.isNotBlank()) { "a family progression state must name its family" }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingRecoveryQualifyingWindows >= 0) {
            "precedingRecoveryQualifyingWindows must be >= 0, was $precedingRecoveryQualifyingWindows"
        }
        require(qualifyingWindowsSinceLastChange == null || qualifyingWindowsSinceLastChange >= 0) {
            "qualifyingWindowsSinceLastChange must be null or >= 0, was " +
                "$qualifyingWindowsSinceLastChange"
        }
        require(recoveryQualifyingWindows >= 0) {
            "recoveryQualifyingWindows must be >= 0, was $recoveryQualifyingWindows"
        }
    }

    /** Whether the family currently carries a concrete exercise. */
    val carriesExercise: Boolean
        get() = currentExerciseId != null
}
