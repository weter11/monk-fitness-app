package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.LoadProfile

/**
 * One measurable channel of a load comparison. The four dimensions are measured as **eight channels**
 * and the split is the point: nothing here may add a repetition to a second, or sum the two volume
 * channels, so "this change added repetitions but not sets" stays a fact rather than a number nobody
 * can decompose. `INTENSITY_LEVEL` is per family — a level is comparable only inside its own hierarchy.
 */
enum class ProgramLoadChannel {

    VOLUME_SETS,

    VOLUME_REPETITIONS,

    VOLUME_SECONDS,

    INTENSITY_LEVEL,

    DENSITY_WORKING_SECONDS,

    DENSITY_REST_SECONDS,

    EXPOSURE_OPPORTUNITIES,

    EXPOSURE_COMPLETED
}

/**
 * Why two readings were **not** compared: a comparison that cannot be stated is a result of its own,
 * not a number made out of two measurements that happen to both be numbers.
 */
enum class ProgramIncomparableReason {

    DIFFERENT_SCOPE,

    DIFFERENT_UNIT,

    FAMILY_NOT_COVERED,

    NOTHING_RECORDED
}

/** One channel's comparison, or the explicit fact that it cannot be made. [subject] is the family a level belongs to, `null` for scope-wide channels. */
sealed interface ProgramChannelComparison {

    val channel: ProgramLoadChannel

    /** The family the channel is stated for, or `null` when it is stated for the scope as a whole. */
    val subject: String?

    data class Compared(
        override val channel: ProgramLoadChannel,
        override val subject: String?,
        val baseline: Int,
        val candidate: Int
    ) : ProgramChannelComparison {

        val increases: Boolean
            get() = candidate > baseline

        val decreases: Boolean
            get() = candidate < baseline

        val delta: Int
            get() = candidate - baseline
    }

    data class Incomparable(
        override val channel: ProgramLoadChannel,
        override val subject: String?,
        val reason: ProgramIncomparableReason
    ) : ProgramChannelComparison
}

/**
 * Two `LoadProfile`s compared channel by channel. A **value and not a verdict**: whether a difference is
 * acceptable belongs to the guard's policy, so no threshold hides in here.
 *
 * The channels are held in canonical order, so two comparisons of the same profiles are the same value
 * whatever order the caller discovered their families in.
 */
data class ProgramLoadComparison(
    val scope: AdaptiveScope,
    val channels: List<ProgramChannelComparison>
) {

    init {
        require(channels == canonical(channels)) {
            "a comparison holds its channels in canonical order, found " +
                "${channels.map { it.channel.name + (it.subject?.let { s -> ":" + s } ?: "") }}"
        }
    }

    val comparable: List<ProgramChannelComparison.Compared>
        get() = channels.filterIsInstance<ProgramChannelComparison.Compared>()

    val incomparable: List<ProgramChannelComparison.Incomparable>
        get() = channels.filterIsInstance<ProgramChannelComparison.Incomparable>()

    val hasComparableChannel: Boolean
        get() = comparable.isNotEmpty()

    val increases: List<ProgramChannelComparison.Compared>
        get() = comparable.filter { it.increases }

    val decreases: List<ProgramChannelComparison.Compared>
        get() = comparable.filter { it.decreases }

    /**
     * The reading the recent-load gate is stated with: one channel above the plan's own prescription is
     * enough to say the context is already carrying more than the plan asks for — not a total.
     */
    val isAboveBaseline: Boolean
        get() = increases.isNotEmpty()

    /** A profile pair sharing no comparable channel answers `false`: nothing was established. */
    val candidateIsAtOrAboveBaselineEverywhere: Boolean
        get() = hasComparableChannel && decreases.isEmpty()

    fun channelOf(channel: ProgramLoadChannel, subject: String? = null): ProgramChannelComparison? =
        channels.firstOrNull { it.channel == channel && it.subject == subject }

    companion object {

        /**
         * Profiles at different scopes are reported as every channel [ProgramIncomparableReason.DIFFERENT_SCOPE]
         * and no number travels between them.
         */
        fun of(baseline: LoadProfile, candidate: LoadProfile): ProgramLoadComparison {
            val scope = baseline.scope
            if (baseline.scope != candidate.scope) {
                return ProgramLoadComparison(scope, channels(baseline, candidate) { channel, subject ->
                    ProgramChannelComparison.Incomparable(
                        channel = channel,
                        subject = subject,
                        reason = ProgramIncomparableReason.DIFFERENT_SCOPE
                    )
                })
            }

            val comparison = ProgramLoadComparison(scope, channels(baseline, candidate) { channel, subject ->
                when (channel) {
                    ProgramLoadChannel.VOLUME_SETS -> amounts(
                        channel, subject, baseline.volume.sets, candidate.volume.sets,
                        ProgramIncomparableReason.NOTHING_RECORDED
                    )

                    ProgramLoadChannel.VOLUME_REPETITIONS -> unitAmounts(
                        channel, subject,
                        baseline.volume.repetitions, candidate.volume.repetitions,
                        baseline.volume.durationSeconds, candidate.volume.durationSeconds
                    )

                    ProgramLoadChannel.VOLUME_SECONDS -> unitAmounts(
                        channel, subject,
                        baseline.volume.durationSeconds, candidate.volume.durationSeconds,
                        baseline.volume.repetitions, candidate.volume.repetitions
                    )

                    ProgramLoadChannel.INTENSITY_LEVEL ->
                        if (subject == null) {
                            ProgramChannelComparison.Incomparable(
                                channel, null, ProgramIncomparableReason.NOTHING_RECORDED
                            )
                        } else {
                            val below = baseline.intensity.levelOf(subject)
                            val above = candidate.intensity.levelOf(subject)
                            when {
                                below != null && above != null ->
                                    ProgramChannelComparison.Compared(channel, subject, below, above)

                                else -> ProgramChannelComparison.Incomparable(
                                    channel, subject, ProgramIncomparableReason.FAMILY_NOT_COVERED
                                )
                            }
                        }

                    ProgramLoadChannel.DENSITY_WORKING_SECONDS -> amounts(
                        channel, subject,
                        baseline.density.workingSeconds, candidate.density.workingSeconds,
                        ProgramIncomparableReason.NOTHING_RECORDED
                    )

                    ProgramLoadChannel.DENSITY_REST_SECONDS -> amounts(
                        channel, subject,
                        baseline.density.restSeconds, candidate.density.restSeconds,
                        ProgramIncomparableReason.NOTHING_RECORDED
                    )

                    ProgramLoadChannel.EXPOSURE_OPPORTUNITIES -> amounts(
                        channel, subject,
                        baseline.exposure.opportunities, candidate.exposure.opportunities,
                        ProgramIncomparableReason.NOTHING_RECORDED
                    )

                    ProgramLoadChannel.EXPOSURE_COMPLETED -> amounts(
                        channel, subject,
                        baseline.exposure.completedOpportunities,
                        candidate.exposure.completedOpportunities,
                        ProgramIncomparableReason.NOTHING_RECORDED
                    )
                }
            })
            return comparison
        }

        private fun canonical(channels: List<ProgramChannelComparison>): List<ProgramChannelComparison> =
            channels.sortedWith(compareBy({ it.channel.ordinal }, { it.subject ?: "" }))

        /**
         * `INTENSITY_LEVEL` expands to one comparison per family in ascending order, so a caller's map
         * iteration order cannot decide the order of the result.
         */
        private fun channels(
            baseline: LoadProfile,
            candidate: LoadProfile,
            comparisonOf: (ProgramLoadChannel, String?) -> ProgramChannelComparison
        ): List<ProgramChannelComparison> {
            val families = (baseline.intensity.levels.map { it.familyId } +
                candidate.intensity.levels.map { it.familyId }).distinct().sorted()
            return canonical(
                ProgramLoadChannel.entries.flatMap { channel ->
                    if (channel == ProgramLoadChannel.INTENSITY_LEVEL) {
                        families.map { family -> comparisonOf(channel, family) }
                    } else {
                        listOf(comparisonOf(channel, null))
                    }
                }
            )
        }

        private fun amounts(
            channel: ProgramLoadChannel,
            subject: String?,
            baseline: Int,
            candidate: Int,
            nothingRecorded: ProgramIncomparableReason
        ): ProgramChannelComparison =
            if (baseline == 0 && candidate == 0) {
                ProgramChannelComparison.Incomparable(channel, subject, nothingRecorded)
            } else {
                ProgramChannelComparison.Compared(channel, subject, baseline, candidate)
            }

        /**
         * Only comparable when both sides are in the **same unit**. One side in the other unit is reported
         * incomparable rather than bridged by a conversion.
         */
        private fun unitAmounts(
            channel: ProgramLoadChannel,
            subject: String?,
            baseline: Int,
            candidate: Int,
            baselineOtherUnit: Int,
            candidateOtherUnit: Int
        ): ProgramChannelComparison {
            val baselineUsesThisUnit = baseline > 0
            val candidateUsesThisUnit = candidate > 0
            return when {
                baselineUsesThisUnit && candidateUsesThisUnit ->
                    ProgramChannelComparison.Compared(channel, subject, baseline, candidate)

                (baselineUsesThisUnit && candidateOtherUnit > 0) ||
                    (candidateUsesThisUnit && baselineOtherUnit > 0) ->
                    ProgramChannelComparison.Incomparable(
                        channel, subject, ProgramIncomparableReason.DIFFERENT_UNIT
                    )

                else -> ProgramChannelComparison.Incomparable(
                    channel, subject, ProgramIncomparableReason.NOTHING_RECORDED
                )
            }
        }
    }
}
