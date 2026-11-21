package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonPose

/**
 * The clearance verdict for one head's finished path.
 *
 * Guards run before any geometry, a decided violation outranks an undecided minimum, and the reason
 * is the first matching row of the package's precedence order rather than the first failure met along
 * the path — so two implementations that hit two conditions report the same one.
 */
class AnatomyValidator(
    private val definition: AnatomyDefinition,
    private val attachmentScale: Double
) {

    private val obstacle = ObstacleGeometry()
    private val verdict = PairVerdict()
    private val boundaries = DoubleArray(definition.wrapSurfaces.size)
    private val orientations = IntArray(definition.wrapSurfaces.size)
    private val radii = HashMap<String, RadiusProfile>()

    /** Each muscle's obstacle union, resolved once: `union()` builds a fresh list per call. */
    private val unions: Map<String, List<WrapSurface>> =
        definition.muscles.associate { it.id to definition.union(it) }

    private var rangeLow = 0.0
    private var rangeHigh = 1.0

    init {
        require(attachmentScale > 0.0 && attachmentScale.isFinite()) {
            "attachmentScale $attachmentScale must be finite and positive"
        }
    }

    /** The numeric margin in engine units: the version's metres converted once, never per call. */
    val margin: Double = definition.epsNumMetres * attachmentScale

    /**
     * Checks [slot]'s path in [frame] and writes its status, reason and location.
     *
     * [failureOutcome] is the search outcome a geometric refusal is recorded under. The pass loop
     * owns that field and passes its own, so this never claims a search stopped when none ran.
     */
    fun validate(
        muscle: MuscleDefinition,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        frame: AnatomyFrame,
        slot: Int,
        failureOutcome: SearchOutcome
    ): Boolean {
        if (muscle.flat) {
            // A two-sided strip has no single radius, so no tube envelope exists to certify.
            return refuse(frame, slot, PathReason.UNSUPPORTED_GEOMETRY, SearchOutcome.REJECTED_BEFORE_SEARCH)
        }
        if (!(frame.restLengths[slot].isFinite()) || !(frame.restLengths[slot] > 0f)) {
            return refuse(frame, slot, PathReason.REST_LENGTH_INVALID, SearchOutcome.REJECTED_BEFORE_SEARCH)
        }
        val points = frame.pointCounts[slot]
        if (points < 2) {
            return refuse(frame, slot, PathReason.DEGENERATE_SEGMENT, SearchOutcome.REJECTED_BEFORE_SEARCH)
        }
        for (index in 0 until points) {
            for (component in 0..2) {
                if (!frame.pointComponent(slot, index, component).isFinite()) {
                    return refuse(frame, slot, PathReason.NON_FINITE_GEOMETRY, SearchOutcome.REJECTED_BEFORE_SEARCH)
                }
            }
        }

        val union = unions.getValue(muscle.id)
        for (index in union.indices) {
            obstacle.load(union[index], side, pose, frames, attachmentScale)
            if (obstacle.nonFinite) {
                return refuse(frame, slot, PathReason.NON_FINITE_GEOMETRY, SearchOutcome.REJECTED_BEFORE_SEARCH)
            }
            if (obstacle.degenerate) {
                return refuse(frame, slot, PathReason.DEGENERATE_OBSTACLE, SearchOutcome.REJECTED_BEFORE_SEARCH, index)
            }
        }

        val profile = radii.getOrPut(muscle.id) { RadiusProfile(muscle.radiusKnots, attachmentScale) }
        val last = points - 1
        envelope(muscle, side, union, pose, frames, frame, slot, profile, frame.heads[slot].headIndex, last)

        // Row 5: a resolved endpoint inside a declared obstacle, except inside its reviewed envelope.
        for (index in union.indices) {
            obstacle.load(union[index], side, pose, frames, attachmentScale)
            for (endpoint in ENDPOINTS) {
                val point = if (endpoint == 0) 0 else last
                val station = point.toDouble() / last
                if (isExempt(index, station)) continue
                if (obstacle.contains(
                        frame.pointComponent(slot, point, 0).toDouble(),
                        frame.pointComponent(slot, point, 1).toDouble(),
                        frame.pointComponent(slot, point, 2).toDouble()
                    )
                ) {
                    return refuse(
                        frame, slot, PathReason.ENDPOINT_INSIDE_OBSTACLE, failureOutcome,
                        obstacleIndex = index, segmentIndex = point
                    )
                }
            }
        }

        var degenerateSegment = NO_INDEX
        var degenerateObstacle = NO_INDEX
        var crossingSegment = NO_INDEX
        var crossingObstacle = NO_INDEX
        var uncertainSegment = NO_INDEX
        var uncertainObstacle = NO_INDEX

        for (index in union.indices) {
            obstacle.load(union[index], side, pose, frames, attachmentScale)
            for (segment in 0 until last) {
                val from = segment.toDouble() / last
                val to = (segment + 1).toDouble() / last
                if (!clip(index, from, to)) continue

                val ax = frame.pointComponent(slot, segment, 0).toDouble()
                val ay = frame.pointComponent(slot, segment, 1).toDouble()
                val az = frame.pointComponent(slot, segment, 2).toDouble()
                val bx = frame.pointComponent(slot, segment + 1, 0).toDouble()
                val by = frame.pointComponent(slot, segment + 1, 1).toDouble()
                val bz = frame.pointComponent(slot, segment + 1, 2).toDouble()

                val dx = bx - ax
                val dy = by - ay
                val dz = bz - az
                val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                val sx = ax + dx * rangeLow
                val sy = ay + dy * rangeLow
                val sz = az + dz * rangeLow
                val ex = ax + dx * rangeHigh
                val ey = ay + dy * rangeHigh
                val ez = az + dz * rangeHigh
                if (!(length * (rangeHigh - rangeLow) > 0.0)) {
                    // Row 6: a zero-length piece inside the solid is decided before any quadratic.
                    if (obstacle.contains(sx, sy, sz) && degenerateSegment == NO_INDEX) {
                        degenerateSegment = segment
                        degenerateObstacle = index
                    }
                    continue
                }

                obstacle.testSegment(
                    sx,
                    sy,
                    sz,
                    ex,
                    ey,
                    ez,
                    from + (to - from) * rangeLow,
                    from + (to - from) * rangeHigh,
                    profile,
                    margin,
                    verdict
                )
                if (verdict.outcome == StageOutcome.INTERSECT && crossingSegment == NO_INDEX) {
                    crossingSegment = segment
                    crossingObstacle = index
                } else if (verdict.outcome == StageOutcome.UNCERTAIN && uncertainSegment == NO_INDEX) {
                    uncertainSegment = segment
                    uncertainObstacle = index
                }
            }
        }

        if (degenerateSegment != NO_INDEX) {
            return refuse(
                frame, slot, PathReason.DEGENERATE_SEGMENT, failureOutcome,
                obstacleIndex = degenerateObstacle, segmentIndex = degenerateSegment
            )
        }
        if (crossingSegment != NO_INDEX) {
            return refuse(
                frame, slot, PathReason.CLEARANCE_VIOLATION, failureOutcome,
                obstacleIndex = crossingObstacle, segmentIndex = crossingSegment
            )
        }
        if (uncertainSegment != NO_INDEX) {
            return refuse(
                frame, slot, PathReason.UNCERTIFIED_MINIMUM, failureOutcome,
                obstacleIndex = uncertainObstacle, segmentIndex = uncertainSegment
            )
        }

        frame.statuses[slot] = PathStatus.VALID.ordinal
        frame.reasonCodes[slot] = PathReason.NO_REASON.ordinal
        frame.searchOutcomes[slot] = SearchOutcome.CLEARED.ordinal
        frame.failingSegments[slot] = NO_INDEX
        frame.failingObstacles[slot] = NO_INDEX
        return true
    }

    /** Whether a station lies inside the reviewed envelope for this obstacle and head. */
    private fun isExempt(obstacleIndex: Int, station: Double): Boolean = when (orientations[obstacleIndex]) {
        ORIGIN -> station <= boundaries[obstacleIndex]
        INSERTION -> station >= boundaries[obstacleIndex]
        else -> false
    }

    /**
     * Narrows the segment to its non-exempt part. Returns false when the whole segment is exempt;
     * otherwise [rangeLow] and [rangeHigh] hold the fraction of the segment to test.
     */
    private fun clip(obstacleIndex: Int, from: Double, to: Double): Boolean {
        rangeLow = 0.0
        rangeHigh = 1.0
        when (orientations[obstacleIndex]) {
            ORIGIN -> {
                val boundary = boundaries[obstacleIndex]
                if (to <= boundary) return false
                if (from < boundary) rangeLow = (boundary - from) / (to - from)
            }
            INSERTION -> {
                val boundary = boundaries[obstacleIndex]
                if (from >= boundary) return false
                if (to > boundary) rangeHigh = (boundary - from) / (to - from)
            }
        }
        return rangeHigh > rangeLow
    }

    /**
     * The effective envelope: the declared interval cut at the first station where the path carries
     * the margin plus the exemption's own slack. Computed from geometry alone, so the mask can never
     * depend on its own result.
     */
    private fun envelope(
        muscle: MuscleDefinition,
        side: MuscleSide,
        union: List<WrapSurface>,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        frame: AnatomyFrame,
        slot: Int,
        profile: RadiusProfile,
        headIndex: Int,
        last: Int
    ) {
        for (index in orientations.indices) orientations[index] = NONE
        val exemptions = muscle.contactExemptions
        for (position in exemptions.indices) {
            val exemption = exemptions[position]
            if (exemption.headIndex != headIndex) continue
            val index = indexOf(union, exemption.obstacleId)
            if (index < 0) continue
            obstacle.load(union[index], side, pose, frames, attachmentScale)
            if (obstacle.nonFinite || obstacle.degenerate) continue

            val target = margin + exemption.envelopeSlackMetres * attachmentScale
            if (exemption.fromStation <= 0f) {
                val bound = exemption.toStation.toDouble()
                orientations[index] = ORIGIN
                boundaries[index] = firstClearance(frame, slot, profile, last, target, bound, true)
            } else {
                val bound = exemption.fromStation.toDouble()
                orientations[index] = INSERTION
                boundaries[index] = firstClearance(frame, slot, profile, last, target, bound, false)
            }
        }
    }

    /**
     * The first station, scanned from the exempted endpoint toward [bound], at which the path carries
     * [target] clearance — refined by bisection between the last station below it and the first at or
     * above it, because a boundary that snapped to a sample could never land on the margin itself.
     */
    private fun firstClearance(
        frame: AnatomyFrame,
        slot: Int,
        profile: RadiusProfile,
        last: Int,
        target: Double,
        bound: Double,
        fromOrigin: Boolean
    ): Double {
        var below = Double.NaN
        val samples = if (fromOrigin) 0..last else last downTo 0
        for (sample in samples) {
            val station = sample.toDouble() / last
            if (fromOrigin && station > bound) break
            if (!fromOrigin && station < bound) break
            if (clearanceAt(frame, slot, station, profile, last) >= target) {
                if (below.isNaN()) return station
                var low = below
                var high = station
                for (iteration in 0 until BOUNDARY_STEPS) {
                    val mid = 0.5 * (low + high)
                    if (clearanceAt(frame, slot, mid, profile, last) >= target) high = mid else low = mid
                }
                return high
            }
            below = station
        }
        return bound
    }

    /** `d − r` at a fractional station of the path, through the same distance the predicate uses. */
    private fun clearanceAt(
        frame: AnatomyFrame,
        slot: Int,
        station: Double,
        profile: RadiusProfile,
        last: Int
    ): Double {
        val position = station * last
        var segment = position.toInt()
        if (segment >= last) segment = last - 1
        if (segment < 0) segment = 0
        val u = position - segment
        val ax = frame.pointComponent(slot, segment, 0).toDouble()
        val ay = frame.pointComponent(slot, segment, 1).toDouble()
        val az = frame.pointComponent(slot, segment, 2).toDouble()
        val bx = frame.pointComponent(slot, segment + 1, 0).toDouble()
        val by = frame.pointComponent(slot, segment + 1, 1).toDouble()
        val bz = frame.pointComponent(slot, segment + 1, 2).toDouble()
        val d = obstacle.distanceTo(ax + (bx - ax) * u, ay + (by - ay) * u, az + (bz - az) * u)
        return d - profile.at(station)
    }

    private fun indexOf(union: List<WrapSurface>, id: String): Int {
        for (index in union.indices) if (union[index].id == id) return index
        return NO_INDEX
    }

    private fun refuse(
        frame: AnatomyFrame,
        slot: Int,
        reason: PathReason,
        outcome: SearchOutcome,
        obstacleIndex: Int = NO_INDEX,
        segmentIndex: Int = NO_INDEX
    ): Boolean {
        frame.statuses[slot] = PathStatus.INVALID.ordinal
        frame.reasonCodes[slot] = reason.ordinal
        frame.searchOutcomes[slot] = outcome.ordinal
        frame.failingSegments[slot] = segmentIndex
        frame.failingObstacles[slot] = obstacleIndex
        return false
    }

    private companion object {
        const val NONE = 0
        const val ORIGIN = 1
        const val INSERTION = 2

        /** Bisection depth that places the envelope boundary on the margin, not on a sample. */
        const val BOUNDARY_STEPS = 40
        val ENDPOINTS = intArrayOf(0, 1)
    }
}
