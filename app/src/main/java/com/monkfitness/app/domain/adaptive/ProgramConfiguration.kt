package com.monkfitness.app.domain.adaptive

/**
 * Where a [ProgramConfiguration] came from.
 *
 * The two values are the whole vocabulary: a selection either is the authoritative default set or it
 * is the user's own. They are stored by [name] — never by ordinal — so reordering this enum cannot
 * re-point a record a device has already written.
 */
enum class ProgramConfigurationSource {
    /** The effective enabled set is the authoritative default exercise set. */
    DEFAULT,

    /** The user changed the enabled set away from the authoritative default. */
    CUSTOM
}

/**
 * The user's exercise configuration: which of the existing exercises a generated workout may use, and
 * the version that identifies the configuration that was effective.
 *
 * Pure domain: no storage, no Android, no clock, and no knowledge of the exercise library — the
 * authoritative default set and any proposed selection are supplied by the caller, so this model can
 * be reasoned about and tested without a device. It represents only what the user selected: whether a
 * selection is biomechanically sound, balanced across training domains or usable with the user's
 * equipment is not decided here (the configuration validator owns that), and no operation on this type
 * ever invents an exercise or falls back to the defaults on the user's behalf — a selection that
 * disables everything stays disabled until the user says otherwise.
 *
 * The three semantics this type exists to define, and nothing beyond them:
 *
 *  * **effective selection** — two configurations are the same effective selection when their enabled
 *    id sets are equal, whatever order the ids arrived in and whatever their `source` says;
 *  * **no-op** — [applying] a selection equal to the current one returns *this* instance, so the
 *    current `source` and `configurationVersion` are preserved exactly and no caller can accidentally
 *    advance the version by re-submitting what is already configured;
 *  * **versioning** — every real change advances [configurationVersion] by exactly one. The version is
 *    monotonic and is never reused, including by [resetToDefault], which is why a reset that changes
 *    nothing does not move it and a configuration can always be identified afterwards.
 *
 * @property source whether the enabled set is the authoritative default or the user's own.
 * @property enabledExerciseIds the exercises a workout may be generated from. Emptiness is
 *   representable here (validation is a separate concern) and is never silently repaired.
 * @property configurationVersion monotonically increasing identity of the effective configuration.
 */
data class ProgramConfiguration(
    val source: ProgramConfigurationSource,
    val enabledExerciseIds: Set<String>,
    val configurationVersion: Int
) {

    init {
        require(configurationVersion >= INITIAL_VERSION) {
            "a configuration version is monotonically increasing and cannot precede " +
                "$INITIAL_VERSION, was $configurationVersion"
        }
        require(enabledExerciseIds.none { it.isBlank() }) {
            "an enabled exercise id must identify an exercise; blank ids: " +
                enabledExerciseIds.filter { it.isBlank() }
        }
    }

    /** Whether this configuration's selection is the authoritative default set. */
    val isDefault: Boolean
        get() = source == ProgramConfigurationSource.DEFAULT

    /**
     * The enabled ids in a deterministic order.
     *
     * A selection is a set and storage keeps it as one, so this is the canonical rendering for
     * anything that needs a stable sequence — a comparison an audit can read, or a signature.
     */
    val orderedExerciseIds: List<String>
        get() = enabledExerciseIds.sorted()

    /** Whether [other] enables exactly the same exercises, regardless of source and version. */
    fun hasSameEffectiveSelectionAs(other: ProgramConfiguration): Boolean =
        enabledExerciseIds == other.enabledExerciseIds

    /** Whether applying [proposedEnabledExerciseIds] would change nothing. */
    fun isNoOpFor(proposedEnabledExerciseIds: Set<String>): Boolean =
        proposedEnabledExerciseIds == enabledExerciseIds

    /**
     * The configuration that results from applying [proposedEnabledExerciseIds].
     *
     * An identical selection is a no-op and returns this instance untouched. A different selection is
     * stored as it was proposed — never merged with what is currently enabled — and becomes `DEFAULT`
     * when it equals [defaultEnabledExerciseIds] and `CUSTOM` otherwise, so a selection that is
     * already the default set is never labelled as a custom configuration.
     */
    fun applying(
        proposedEnabledExerciseIds: Set<String>,
        defaultEnabledExerciseIds: Set<String>
    ): ProgramConfiguration {
        if (isNoOpFor(proposedEnabledExerciseIds)) return this

        val source = if (proposedEnabledExerciseIds == defaultEnabledExerciseIds) {
            ProgramConfigurationSource.DEFAULT
        } else {
            ProgramConfigurationSource.CUSTOM
        }

        return ProgramConfiguration(source, proposedEnabledExerciseIds, configurationVersion + 1)
    }

    /**
     * The configuration that results from resetting the selection to [defaultEnabledExerciseIds].
     *
     * The result is always `DEFAULT`: the authoritative set is what a reset *means*. The version
     * advances only when the effective selection actually changes, so a reset on an already-default
     * configuration is a no-op and repeating it cannot inflate the counter — and it never rewinds.
     */
    fun resetToDefault(defaultEnabledExerciseIds: Set<String>): ProgramConfiguration {
        if (enabledExerciseIds == defaultEnabledExerciseIds && isDefault) return this

        val selectionChanged = enabledExerciseIds != defaultEnabledExerciseIds
        val version = if (selectionChanged) configurationVersion + 1 else configurationVersion

        return ProgramConfiguration(
            ProgramConfigurationSource.DEFAULT,
            defaultEnabledExerciseIds,
            version
        )
    }

    companion object {

        /** The version of a configuration that has never been persisted after a real change. */
        const val INITIAL_VERSION = 0

        /** The authoritative default configuration of the default enabled set. */
        fun default(
            enabledExerciseIds: Set<String>,
            configurationVersion: Int = INITIAL_VERSION
        ): ProgramConfiguration = ProgramConfiguration(
            ProgramConfigurationSource.DEFAULT,
            enabledExerciseIds,
            configurationVersion
        )

        /** A configuration the user selected themselves. */
        fun custom(
            enabledExerciseIds: Set<String>,
            configurationVersion: Int
        ): ProgramConfiguration = ProgramConfiguration(
            ProgramConfigurationSource.CUSTOM,
            enabledExerciseIds,
            configurationVersion
        )
    }
}
