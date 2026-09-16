package com.monkfitness.app.domain.adaptive

/**
 * The program configuration that is effective for ONE workout session.
 *
 * A [ProgramConfiguration] is the user's **future** configuration: it is persisted, and it is mutable
 * by definition — every real edit advances its version, so reading it again later describes the
 * workouts that have not started yet. This type is the other half of that boundary. It is what the
 * configuration *was* at the instant a session started, and it is the only configuration that session
 * may generate from afterwards: an edit made while the session runs produces a new
 * [ProgramConfiguration], never a new snapshot for a session that has already begun, and a finished
 * workout is never reconstructed against a newer selection.
 *
 * It carries identity and selection, and nothing else:
 *
 *  * [configurationVersion] is copied from the configuration that was captured. The version is the
 *    configuration repository's to advance and every one of its rules — no-op edits, `DEFAULT`
 *    labelling, monotonicity — stays there; this type performs no arithmetic on it, so a snapshot can
 *    only ever report the version it was taken at.
 *  * [enabledExerciseIds] is the captured selection, exactly as it was persisted. It is never merged
 *    with, repaired by or re-enabled from a later configuration, and an empty selection stays empty:
 *    deciding what a selection may contain belongs to the configuration validator, not here.
 *
 * It also holds no exercise metadata, no equipment rule and no validation outcome. It answers one
 * question — which exercises the session it belongs to may use — and it answers it from one frozen
 * answer, not from a second exercise catalogue and not from a second configuration.
 *
 * @property configurationVersion the version of the configuration captured at the session's start.
 * @property enabledExerciseIds the exercises that session may use, exactly as captured.
 */
data class WorkoutConfigurationSnapshot(
    val configurationVersion: Int,
    val enabledExerciseIds: Set<String>
) {

    init {
        require(configurationVersion >= ProgramConfiguration.INITIAL_VERSION) {
            "a captured configuration version cannot precede " +
                "${ProgramConfiguration.INITIAL_VERSION}, was $configurationVersion"
        }
        require(enabledExerciseIds.none { it.isBlank() }) {
            "an enabled exercise id must identify an exercise"
        }
    }

    /**
     * The captured selection in a deterministic order.
     *
     * The selection is a set — storage keeps it as one — so this is the canonical rendering for
     * anything that needs a stable sequence, an audit comparison or a signature of the snapshot.
     */
    val orderedExerciseIds: List<String>
        get() = enabledExerciseIds.sorted()

    /** Whether the session this snapshot belongs to may use [exerciseId]. */
    fun enables(exerciseId: String): Boolean = exerciseId in enabledExerciseIds

    /**
     * Whether [other] captured the same effective selection, whatever versions the two were taken at.
     *
     * Two sessions that ran on the same enabled set are the same selection even though they were
     * captured at different moments; the version is what distinguishes the captures, not the content.
     */
    fun hasSameEffectiveSelectionAs(other: WorkoutConfigurationSnapshot): Boolean =
        enabledExerciseIds == other.enabledExerciseIds

    companion object {

        /**
         * The snapshot of [configuration]: the version it reports and the selection it enables at
         * this instant. This is the single capture the app takes per session — it copies the two
         * values it is given and derives nothing.
         */
        fun capture(configuration: ProgramConfiguration): WorkoutConfigurationSnapshot =
            WorkoutConfigurationSnapshot(
                configurationVersion = configuration.configurationVersion,
                enabledExerciseIds = configuration.enabledExerciseIds
            )
    }
}
