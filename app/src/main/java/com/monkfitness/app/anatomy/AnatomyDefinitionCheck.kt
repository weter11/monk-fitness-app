package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose

/** What a definition check refused, and at which subject. */
enum class DefinitionIssueKind {
    DUPLICATE_ID,
    NO_HEADS,
    HEAD_LABEL,
    UNRESOLVED_WRAP,
    GLOBAL_WRAP_NAMED,
    DUPLICATE_WRAP,
    TENDON_FRACTION,
    RADIUS_KNOT,
    ENVELOPE_SLACK,
    EXEMPTION_HEAD,
    EXEMPTION_OBSTACLE,
    EXEMPTION_SOURCE,
    NON_POSITIVE_DIMENSION,
    SAMPLING,
    EPSILON,
    ATTACHMENT_INSIDE_OBSTACLE
}

data class DefinitionIssue(val kind: DefinitionIssueKind, val subject: String, val detail: String)

/**
 * Structural verdict plus the frame-provenance list.
 *
 * [issues] is what fails a definition; [frameUnverified] is what a reviewer still owes and can never
 * be a pass, because an attachment authored in an unrecorded convention is indistinguishable from a
 * mirrored one on every geometric check the solver has.
 */
class DefinitionReport(val issues: List<DefinitionIssue>, val frameUnverified: List<String>) {
    val isClean: Boolean get() = issues.isEmpty()
}

/**
 * Mechanical checks only: nothing here knows what a muscle does, and no check reads a coordinate's
 * value beyond finiteness and the declared frame.
 */
object AnatomyDefinitionCheck {

    fun check(definition: AnatomyDefinition): DefinitionReport = check(definition, emptyList())

    /**
     * The static verdict plus §7.4.2's L3 bind-stance obstacle check.
     *
     * [bindStance] is a separate input because it is the only check that needs a pose: an attachment
     * sitting inside its own union's obstacle cannot be cleared by any detour, so the definition is
     * what has to change, and a per-frame refusal would report the same defect sixty times a second.
     */
    fun check(definition: AnatomyDefinition, bindStance: List<DefinitionIssue>): DefinitionReport {
        val issues = mutableListOf<DefinitionIssue>()
        val unverified = mutableListOf<String>()

        duplicates(definition.muscles.map { it.id }, "muscle").forEach { issues += it }
        duplicates(definition.wrapSurfaces.map { it.id }, "wrap").forEach { issues += it }
        duplicates(definition.landmarks.map { it.id }, "landmark").forEach { issues += it }

        if (!(definition.epsNumMetres > 0f) || !definition.epsNumMetres.isFinite()) {
            issues += DefinitionIssue(
                DefinitionIssueKind.EPSILON, definition.id,
                "epsNumMetres ${definition.epsNumMetres} must be finite and positive"
            )
        }

        val sampling = definition.sampling
        if (!(sampling.spacingMetres > 0f) || sampling.minSamples < 2 ||
            sampling.maxSamples < sampling.minSamples || sampling.maxPointsPerHead < sampling.maxSamples
        ) {
            issues += DefinitionIssue(
                DefinitionIssueKind.SAMPLING, definition.id,
                "sampling must have a positive spacing, 2 ≤ min ≤ max, and maxPoints ≥ max"
            )
        }

        val globalIds = definition.wrapSurfaces.filter { it.global }.map { it.id }.toSet()

        for (muscle in definition.muscles) {
            if (muscle.heads.isEmpty()) {
                issues += DefinitionIssue(DefinitionIssueKind.NO_HEADS, muscle.id, "declares no heads, so no path")
            }
            if (!(muscle.widthMetres > 0f) || !(muscle.thicknessMetres > 0f)) {
                issues += DefinitionIssue(
                    DefinitionIssueKind.NON_POSITIVE_DIMENSION, muscle.id,
                    "width ${muscle.widthMetres} / thickness ${muscle.thicknessMetres} must be positive"
                )
            }
            if (muscle.profile !in 0f..1f) {
                issues += DefinitionIssue(DefinitionIssueKind.NON_POSITIVE_DIMENSION, muscle.id, "profile ${muscle.profile} outside 0..1")
            }
            if (muscle.passCap < 1) {
                issues += DefinitionIssue(DefinitionIssueKind.NON_POSITIVE_DIMENSION, muscle.id, "passCap ${muscle.passCap} below 1")
            }
            if (!(muscle.corridorMetres > 0f)) {
                issues += DefinitionIssue(DefinitionIssueKind.NON_POSITIVE_DIMENSION, muscle.id, "corridorMetres ${muscle.corridorMetres} must be positive")
            }

            if (muscle.heads.size > 1) {
                val labels = muscle.heads.map { it.label.trim() }
                if (labels.any { it.isEmpty() } || labels.toSet().size != labels.size) {
                    issues += DefinitionIssue(
                        DefinitionIssueKind.HEAD_LABEL, muscle.id,
                        "a multi-head muscle needs one distinct authored label per head, was $labels"
                    )
                }
            }

            tendonIssues(muscle).forEach { issues += it }
            radiusIssues(muscle).forEach { issues += it }

            val named = muscle.wraps
            val duplicateNames = named.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (duplicateNames.isNotEmpty()) {
                issues += DefinitionIssue(DefinitionIssueKind.DUPLICATE_WRAP, muscle.id, "names $duplicateNames twice")
            }
            for (id in named) {
                val surface = definition.wrapSurface(id)
                if (surface == null) {
                    issues += DefinitionIssue(DefinitionIssueKind.UNRESOLVED_WRAP, muscle.id, "names unknown obstacle \"$id\"")
                } else if (surface.global) {
                    issues += DefinitionIssue(
                        DefinitionIssueKind.GLOBAL_WRAP_NAMED, muscle.id,
                        "names \"$id\", which is global and already in every head's union"
                    )
                }
            }

            exemptionIssues(definition, muscle, globalIds).forEach { issues += it }

            muscle.heads.forEachIndexed { index, head ->
                for (attachment in head.attachments) {
                    if (attachment.frame != AttachmentFrame.DERIVED_SEGMENT) {
                        unverified += "${muscle.id}/head$index/${attachment.segment}"
                    }
                }
            }
        }

        return DefinitionReport(issues + bindStance, unverified)
    }

    /**
     * §7.4.2 L3 at the bind stance: every authored attachment of every head against every obstacle in
     * that head's union, in ellipsoid-normalized distance.
     *
     * A normalized distance below one is inside the obstacle. The check reports the worst station per
     * head and obstacle rather than each one, because the fix is the surface or the attachment, and
     * the reviewer needs the number that has to move past one.
     */
    fun bindStance(
        definition: AnatomyDefinition,
        skeleton: SkeletonDefinition,
        bindPose: SkeletonPose,
        attachmentScale: Double
    ): List<DefinitionIssue> {
        val frames = SegmentFrames(skeleton)
        val set = SegmentFrameSet()
        frames.derive(bindPose, set)
        val builder = MusclePathBuilder(definition, attachmentScale.toFloat())
        val obstacle = ObstacleGeometry()
        val control = DoubleArray(MAX_CONTROL_POINTS * 3)
        val issues = mutableListOf<DefinitionIssue>()

        for (muscle in definition.muscles) {
            val union = definition.union(muscle)
            if (union.isEmpty()) continue
            for (side in MuscleSide.entries) {
                for ((headIndex, head) in muscle.heads.withIndex()) {
                    val count = builder.resolveControl(head, side, bindPose, set, control)
                    if (count < 2) continue
                    for (surface in union) {
                        obstacle.load(surface, side, bindPose, set, attachmentScale)
                        if (obstacle.nonFinite || obstacle.degenerate) continue
                        var worst = Double.MAX_VALUE
                        for (station in 0 until count) {
                            val distance = normalized(
                                control[station * 3], control[station * 3 + 1], control[station * 3 + 2], obstacle
                            )
                            if (distance < worst) worst = distance
                        }
                        if (worst >= INSIDE_OBSTACLE) continue
                        issues += DefinitionIssue(
                            DefinitionIssueKind.ATTACHMENT_INSIDE_OBSTACLE,
                            "${muscle.id}/head$headIndex/${side}",
                            "an attachment sits %.2f of the way to \"%s\"'s centre, and no detour can "
                                .format(worst, surface.id) + "clear a point that starts inside the obstacle"
                        )
                    }
                }
            }
        }
        return issues
    }

    /** Ellipsoid-normalized distance: `< 1` inside, `1` on the surface, `> 1` outside. */
    private fun normalized(x: Double, y: Double, z: Double, obstacle: ObstacleGeometry): Double {
        val dx = x - obstacle.centre[0]
        val dy = y - obstacle.centre[1]
        val dz = z - obstacle.centre[2]
        var sum = 0.0
        for (axis in 0 until 3) {
            val projection = dx * obstacle.axes[axis * 3] + dy * obstacle.axes[axis * 3 + 1] + dz * obstacle.axes[axis * 3 + 2]
            val radius = obstacle.radii[axis]
            sum += (projection / radius) * (projection / radius)
        }
        return kotlin.math.sqrt(sum)
    }

    private const val MAX_CONTROL_POINTS = 64

    private const val INSIDE_OBSTACLE = 1.0

    private fun tendonIssues(muscle: MuscleDefinition): List<DefinitionIssue> {
        val tendon = muscle.tendon
        val inRange = tendon.originFraction in 0f..1f && tendon.insertionFraction in 0f..1f
        val sum = tendon.originFraction + tendon.insertionFraction
        return if (!inRange || sum > 1f) {
            listOf(
                DefinitionIssue(
                    DefinitionIssueKind.TENDON_FRACTION, muscle.id,
                    "fractions ${tendon.originFraction}/${tendon.insertionFraction} must lie in 0..1 and sum to at most 1, was $sum"
                )
            )
        } else {
            emptyList()
        }
    }

    private fun radiusIssues(muscle: MuscleDefinition): List<DefinitionIssue> {
        val knots = muscle.radiusKnots
        val problem = when {
            knots.size < 2 -> "needs at least two knots"
            knots.first().first != 0f || knots.last().first != 1f -> "must start at station 0 and end at station 1"
            knots.any { (station, radius) -> !station.isFinite() || !radius.isFinite() || radius < 0f || station !in 0f..1f } ->
                "has a non-finite or negative knot"
            knots.zipWithNext().any { (a, b) -> b.first <= a.first } -> "stations must increase"
            else -> null
        }
        return if (problem == null) emptyList()
        else listOf(DefinitionIssue(DefinitionIssueKind.RADIUS_KNOT, muscle.id, "radiusKnots $problem"))
    }

    private fun exemptionIssues(
        definition: AnatomyDefinition,
        muscle: MuscleDefinition,
        globalIds: Set<String>
    ): List<DefinitionIssue> = muscle.contactExemptions.mapIndexedNotNull { index, exemption ->
        val path = "${muscle.id}.contactExemptions[$index]"
        when {
            exemption.headIndex !in muscle.heads.indices -> DefinitionIssue(
                DefinitionIssueKind.EXEMPTION_HEAD, path,
                "headIndex ${exemption.headIndex} is outside 0..${muscle.heads.size - 1}"
            )
            exemption.obstacleId !in globalIds && exemption.obstacleId !in muscle.wraps -> DefinitionIssue(
                DefinitionIssueKind.EXEMPTION_OBSTACLE, path,
                "obstacle \"${exemption.obstacleId}\" is not in this head's union"
            )
            exemption.obstacleId.let { definition.wrapSurface(it) } == null -> DefinitionIssue(
                DefinitionIssueKind.EXEMPTION_OBSTACLE, path,
                "obstacle \"${exemption.obstacleId}\" does not resolve"
            )
            exemption.fromStation !in 0f..1f || exemption.toStation !in 0f..1f || exemption.fromStation >= exemption.toStation ->
                DefinitionIssue(DefinitionIssueKind.EXEMPTION_OBSTACLE, path, "the interval must lie in 0..1 and increase")
            exemption.envelopeSlackMetres < 0f || !exemption.envelopeSlackMetres.isFinite() -> DefinitionIssue(
                DefinitionIssueKind.ENVELOPE_SLACK, path, "slack ${exemption.envelopeSlackMetres} must be finite and non-negative"
            )
            exemption.source.isBlank() -> DefinitionIssue(
                DefinitionIssueKind.EXEMPTION_SOURCE, path, "names no review row, and an unsourced exemption is not an exemption"
            )
            else -> null
        }
    }

    private fun duplicates(ids: List<String>, kind: String): List<DefinitionIssue> =
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.map { (id, count) ->
            DefinitionIssue(DefinitionIssueKind.DUPLICATE_ID, id, "$kind appears $count times")
        }
}
