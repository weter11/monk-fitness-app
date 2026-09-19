package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.LoadProfile

/**
 * One measurable channel of a load comparison (§17).
 *
 * The four dimensions the architecture keeps apart are measured here as **eight channels**, and the
 * split is the point: §17 forbids a total, a weight and any conversion between its parts, so nothing
 * in this stage may add a repetition to a second, and not even the two volume channels may be summed.
 * A comparison therefore reports the channels separately and lets a policy state its own rule per
 * channel — which is what makes "this change added repetitions but not sets" a fact rather than a
 * number nobody can decompose.
 *
 * `INTENSITY_LEVEL` is the one channel that is per family (a level is comparable only inside its own
 * hierarchy, §15), so its comparisons carry the family id as their subject.
 */
enum class ProgramLoadChannel {

    /** How many sets the scope prescribes. */
    VOLUME_SETS,

    /** How many repetitions the scope prescribes, in the unit a repetition-based plan is written in. */
    VOLUME_REPETITIONS,

    /** How many seconds of work the scope prescribes, in the unit a time-based plan is written in. */
    VOLUME_SECONDS,

    /** One family's position in its own progression hierarchy. */
    INTENSITY_LEVEL,

    /** The scope's working time — the density channel that says how the work is arranged in time. */
    DENSITY_WORKING_SECONDS,

    /** The scope's rest time. */
    DENSITY_REST_SECONDS,

    /** How many opportunities the scope had. Context, never workload (§17). */
    EXPOSURE_OPPORTUNITIES,

    /** How many of those opportunities produced work. */
    EXPOSURE_COMPLETED
}

/**
 * Why two channel readings were **not** compared.
 *
 * Reporting the reason rather than a number is what stops a comparison from being made out of two
 * measurements that merely happen to both be numbers (§17, §18): a comparison that cannot be stated is
 * a result of its own, and the guard has no verdict to reach from it.
 */
enum class ProgramIncomparableReason {

    /** The two profiles are stated at different granularities: `EXERCISE` against `SESSION` says nothing. */
    DIFFERENT_SCOPE,

    /** The two sides measure different units — repetitions against seconds, or one of them not at all. */
    DIFFERENT_UNIT,

    /** An intensity level was compared for a family only one of the two profiles covers. */
    FAMILY_NOT_COVERED,

    /** Neither side records this channel at all, so there is nothing to compare. */
    NOTHING_RECORDED
}

/**
 * One channel's comparison: the two readings, or the explicit fact that they cannot be compared.
 *
 * [subject] is the family a level belongs to and `null` for the scope-wide channels, so a per-family
 * reading can never be read as a scope-wide one.
 */
sealed interface ProgramChannelComparison {

    /** The channel this comparison is about. */
    val channel: ProgramLoadChannel

    /** The family the channel is stated for, or `null` when it is stated for the scope as a whole. */
    val subject: String?

    /** The two readings of a channel both sides measure in the same unit and at the same scope. */
    data class Compared(
        override val channel: ProgramLoadChannel,
        override val subject: String?,
        val baseline: Int,
        val candidate: Int
    ) : ProgramChannelComparison {

        /** Whether the candidate reads higher than the baseline on this channel. */
        val increases: Boolean
            get() = candidate > baseline

        /** Whether the candidate reads lower than the baseline on this channel. */
        val decreases: Boolean
            get() = candidate < baseline

        /** The exact integer difference, candidate minus baseline. */
        val delta: Int
            get() = candidate - baseline
    }

    /** A channel the two sides do not share a scope or a unit for. */
    data class Incomparable(
        override val channel: ProgramLoadChannel,
        override val subject: String?,
        val reason: ProgramIncomparableReason
    ) : ProgramChannelComparison
}

/**
 * Two `LoadProfile`s compared, channel by channel, at the scope they are stated at (§18).
 *
 * The comparison is a **value and not a verdict**: it says what the two profiles do to each of the
 * eight channels and where they cannot be compared at all. Whether a difference is acceptable is the
 * aggregate load guard's decision and belongs to its policy, so a threshold never hides in here.
 *
 * Ordering is part of the value. The channels are held in [ProgramLoadChannel] order, and the
 * per-family intensity channels in ascending family-id order inside that position, so two comparisons
 * of the same profiles are the same value whatever order the caller discovered their families in — and
 * a guard's tie-breaking is a read of this list rather than a second sort.
 *
 * ### What it refuses to do
 *
 * Profiles stated at different scopes produce a comparison whose every channel is
 * [ProgramIncomparableReason.DIFFERENT_SCOPE] — *not* a comparison of the overlapping-looking numbers.
 * Repetitions are only ever compared against repetitions, and seconds against seconds. An intensity
 * level is compared only for a family both profiles cover. There is no total, no normalisation and no
 * cross-unit conversion anywhere in this file.
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

    /** The channels that could be compared. */
    val comparable: List<ProgramChannelComparison.Compared>
        get() = channels.filterIsInstance<ProgramChannelComparison.Compared>()

    /** The channels that could not be compared, each with its reason. */
    val incomparable: List<ProgramChannelComparison.Incomparable>
        get() = channels.filterIsInstance<ProgramChannelComparison.Incomparable>()

    /** Whether any channel of the two profiles could be compared at all. */
    val hasComparableChannel: Boolean
        get() = comparable.isNotEmpty()

    /** The comparable channels on which the candidate reads higher than the baseline. */
    val increases: List<ProgramChannelComparison.Compared>
        get() = comparable.filter { it.increases }

    /** The comparable channels on which the candidate reads lower than the baseline. */
    val decreases: List<ProgramChannelComparison.Compared>
        get() = comparable.filter { it.decreases }

    /**
     * Whether the candidate is above the baseline on at least one comparable channel.
     *
     * This is the reading §7's *"recent load not HIGH"* is stated with: a recent context that is above
     * the plan's own prescribed load on any channel it can be compared on is a context that is already
     * carrying more than the plan asks for — and it takes one channel, not a total, to say so.
     */
    val isAboveBaseline: Boolean
        get() = increases.isNotEmpty()

    /**
     * Whether the candidate is at or above the baseline on **every** channel the two can be compared on,
     * with at least one channel comparable.
     *
     * This is the reading the guard's recent-context rule is stated with: "the load the plan already
     * asked for was met or exceeded". A profile pair that shares no comparable channel answers `false`,
     * because nothing was established.
     */
    val candidateIsAtOrAboveBaselineEverywhere: Boolean
        get() = hasComparableChannel && decreases.isEmpty()

    /** The comparison of [channel] for [subject], or `null` when the comparison does not state it. */
    fun channelOf(channel: ProgramLoadChannel, subject: String? = null): ProgramChannelComparison? =
        channels.firstOrNull { it.channel == channel && it.subject == subject }

    companion object {

        /**
         * Compares [baseline] against [candidate], channel by channel.
         *
         * The scope is the pair's own when both sides agree on it, and the two sides are compared
         * channel by channel within that scope. When they disagree, every channel is reported
         * incomparable ([ProgramIncomparableReason.DIFFERENT_SCOPE]) and no number travels between them
         * — which is the mechanical form of §18's *"do not compare unrelated measurements merely
         * because both happen to contain numbers"*.
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

        /** The canonical order of the comparison's channels. */
        private fun canonical(channels: List<ProgramChannelComparison>): List<ProgramChannelComparison> =
            channels.sortedWith(compareBy({ it.channel.ordinal }, { it.subject ?: "" }))

        /**
         * Every channel of a comparison, in canonical order, with the `INTENSITY_LEVEL` channel expanded
         * to one comparison per family the two profiles mention between them — ascending, so a caller's
         * map iteration order cannot decide the order of the result.
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

        /** Compares two scope-wide readings, or reports that neither side records the channel. */
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
         * Compares two readings that are only comparable when both sides are in the **same unit**: a
         * repetition count against a repetition count, a duration against a duration. When one side is
         * written in the other unit — or writes no amount at all — the channel is reported incomparable
         * rather than compared, and no conversion is invented to bridge it (§17).
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
