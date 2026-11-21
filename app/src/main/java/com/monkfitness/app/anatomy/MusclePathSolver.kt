package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonPose
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The bounded construction loop: build a detour, smooth, project, check, and escalate until the
 * clearance predicate accepts or the pass budget runs out.
 *
 * Two outcomes are kept apart. [SearchOutcome] says what the loop did; [PathReason] says what
 * geometry it last hit. A corridor refused before the first candidate has no geometric cause at all,
 * which is why the two are never collapsed into one field.
 */
class MusclePathSolver(
    private val definition: AnatomyDefinition,
    private val builder: MusclePathBuilder,
    private val validator: AnatomyValidator,
    private val attachmentScale: Double
) {

    private val points = DoubleArray(MAX_POINTS * 3)
    private val smoothed = DoubleArray(MAX_POINTS * 3)
    private var count = 0
    private val obstacle = ObstacleGeometry()
    private val unions: Map<String, List<WrapSurface>> =
        definition.muscles.associate { it.id to definition.union(it) }
    private val verdict = PairVerdict()

    init {
        require(attachmentScale > 0.0 && attachmentScale.isFinite()) {
            "attachmentScale $attachmentScale must be finite and positive"
        }
    }

    /** Angle a polyline may turn at an interior sample before it is a curvature violation. */
    val thetaMax: Double = definition.thetaMaxRadians.toDouble()

    /**
     * Solves [head] for [side] into [frame]'s [slot]. Returns the point count written, or
     * [NO_INDEX] when the loop refused: the frame then carries the outcome and the last cause.
     */
    fun solve(
        muscle: MuscleDefinition,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        frame: AnatomyFrame,
        slot: Int
    ): Int {
        val control = builder.resolveControl(head = muscle.heads[frame.heads[slot].headIndex], side, pose, frames, points)
        if (control < 2) return refuse(frame, slot, PathReason.DEGENERATE_SEGMENT, SearchOutcome.REJECTED_BEFORE_SEARCH)
        count = resample(control)

        val union = unionOf(muscle)
        val profile = RadiusProfile(muscle.radiusKnots, attachmentScale)
        val corridor = muscle.corridorMetres * attachmentScale
        var detour = smallestDetour(union, side, pose, frames)
        if (detour > corridor) {
            // No candidate can be placed inside the declared corridor: the search stops before a pass.
            return refuse(frame, slot, PathReason.NO_GEOMETRIC_CAUSE, SearchOutcome.SEARCH_EXHAUSTED)
        }

        var lastReason = PathReason.NO_GEOMETRIC_CAUSE
        var lastSegment = NO_INDEX
        var lastObstacle = NO_INDEX

        for (pass in 0 until muscle.passCap) {
            if (insertDetours(union, side, pose, frames, detour, profile) > 0) {
                smooth()
            }
            project(union, side, pose, frames, profile)

            val check = check(union, side, pose, frames, profile, frame, slot)
            when (check) {
                Check.CLEARED -> {
                    writePath(frame, slot, profile)
                    frame.statuses[slot] = PathStatus.VALID.ordinal
                    frame.reasonCodes[slot] = PathReason.NO_REASON.ordinal
                    frame.searchOutcomes[slot] = SearchOutcome.CLEARED.ordinal
                    frame.failingSegments[slot] = NO_INDEX
                    frame.failingObstacles[slot] = NO_INDEX
                    return count
                }
                Check.TURN -> {
                    lastReason = PathReason.CURVATURE_VIOLATION
                    lastSegment = turnSegment
                    lastObstacle = NO_INDEX
                }
                Check.TANGENT -> {
                    lastReason = PathReason.TANGENT_DISCONTINUITY
                    lastSegment = turnSegment
                    lastObstacle = NO_INDEX
                }
                Check.BLOCKED -> {
                    lastReason = blockedReason
                    lastSegment = blockedSegment
                    lastObstacle = blockedObstacle
                }
            }
            detour *= ESCALATION
            if (detour > corridor) {
                return refuse(frame, slot, lastReason, SearchOutcome.SEARCH_EXHAUSTED, lastObstacle, lastSegment)
            }
        }

        return refuse(frame, slot, lastReason, SearchOutcome.PASS_CAP, lastObstacle, lastSegment)
    }

    private enum class Check { CLEARED, BLOCKED, TURN, TANGENT }

    private var blockedReason = PathReason.NO_GEOMETRIC_CAUSE
    private var blockedSegment = NO_INDEX
    private var blockedObstacle = NO_INDEX
    private var turnSegment = NO_INDEX

    /** Resamples the control polyline at the version's spacing, equal-arc. */
    private fun resample(control: Int): Int {
        var total = 0.0
        for (index in 1 until control) total += distance(points, index - 1, index)
        val wanted = (total / definition.sampling.spacingMetres * attachmentScale).toInt()
            .coerceIn(definition.sampling.minSamples, definition.sampling.maxSamples)
        System.arraycopy(points, 0, smoothed, 0, control * 3)
        val source = smoothed
        var segment = 1
        var start = 0.0
        var length = distance(source, 0, 1)
        for (index in 0 until wanted) {
            val target = if (wanted == 1) 0.0 else total * index / (wanted - 1)
            while (segment < control - 1 && start + length < target) {
                start += length
                segment++
                length = distance(source, segment - 1, segment)
            }
            val u = if (length > 0.0) (target - start) / length else 0.0
            for (component in 0..2) {
                val from = (segment - 1) * 3 + component
                val to = segment * 3 + component
                points[index * 3 + component] = source[from] + (source[to] - source[from]) * u
            }
        }
        return wanted
    }

    /**
     * Inserts one lateral waypoint per blocked segment. The direction is the segment's own normal,
     * so the detour is a function of the geometry it avoids rather than of a world axis.
     */
    private fun insertDetours(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        detour: Double,
        profile: RadiusProfile
    ): Int {
        var inserted = 0
        var write = 0
        smoothed.fill(0.0)
        for (index in 0 until count) {
            copy(points, index, smoothed, write++)
            if (index == count - 1) continue

            var pushed = false
            for (obstacleIndex in union.indices) {
                obstacle.load(union[obstacleIndex], side, pose, frames, attachmentScale)
                obstacle.testSegment(
                    points[index * 3], points[index * 3 + 1], points[index * 3 + 2],
                    points[(index + 1) * 3], points[(index + 1) * 3 + 1], points[(index + 1) * 3 + 2],
                    index.toDouble() / (count - 1), (index + 1).toDouble() / (count - 1),
                    profile, validator.margin, verdict
                )
                if (verdict.outcome == StageOutcome.CLEAR) continue
                if (write + 1 >= MAX_POINTS) break
                val dx = points[(index + 1) * 3] - points[index * 3]
                val dy = points[(index + 1) * 3 + 1] - points[index * 3 + 1]
                val dz = points[(index + 1) * 3 + 2] - points[index * 3 + 2]
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                if (!(length > 0.0)) continue
                // Normal of the segment against world +Y: a stable, deterministic lateral direction.
                val nx0 = dy * 0.0 - dz * 1.0
                val ny0 = dz * 0.0 - dx * 0.0
                val nz0 = dx * 1.0 - dy * 0.0
                val normalLength = sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0)
                if (!(normalLength > 0.0)) continue
                val mid = 0.5
                smoothed[write * 3] = points[index * 3] + dx * mid + nx0 / normalLength * detour
                smoothed[write * 3 + 1] = points[index * 3 + 1] + dy * mid + ny0 / normalLength * detour
                smoothed[write * 3 + 2] = points[index * 3 + 2] + dz * mid + nz0 / normalLength * detour
                write++
                inserted++
                pushed = true
                break
            }
            if (pushed) continue
        }
        if (inserted > 0) {
            System.arraycopy(smoothed, 0, points, 0, write * 3)
            count = write
        }
        return inserted
    }

    /** One Catmull-Rom pass at quarter blend; never run after the final check. */
    private fun smooth() {
        if (count < 3) return
        for (index in 0 until count) {
            copy(points, index, smoothed, index)
        }
        for (index in 1 until count - 1) {
            for (component in 0..2) {
                val previous = points[(index - 1) * 3 + component]
                val here = points[index * 3 + component]
                val next = points[(index + 1) * 3 + component]
                val after = points[(minOf(index + 2, count - 1)) * 3 + component]
                val target = (-previous + 9 * here + 9 * next - after) / 16.0
                smoothed[index * 3 + component] = here + (target - here) * SMOOTH_BLEND
            }
        }
        System.arraycopy(smoothed, 0, points, 0, count * 3)
    }

    /** Pushes every sample that sits inside its tube out to `r + e` along the outward direction. */
    private fun project(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        profile: RadiusProfile
    ) {
        for (obstacleIndex in union.indices) {
            obstacle.load(union[obstacleIndex], side, pose, frames, attachmentScale)
            for (index in 0 until count) {
                val station = index.toDouble() / (count - 1)
                val need = profile.at(station) + validator.margin
                val d = obstacle.distanceTo(points[index * 3], points[index * 3 + 1], points[index * 3 + 2])
                if (d >= need) continue
                val dx = points[index * 3] - obstacle.centre[0]
                val dy = points[index * 3 + 1] - obstacle.centre[1]
                val dz = points[index * 3 + 2] - obstacle.centre[2]
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                if (!(length > 0.0)) continue
                val push = (need - d) / length
                points[index * 3] += dx * push
                points[index * 3 + 1] += dy * push
                points[index * 3 + 2] += dz * push
            }
        }
    }

    /** The same predicate the validator applies, plus the continuity rows of §7.4.3. */
    private fun check(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        profile: RadiusProfile,
        frame: AnatomyFrame,
        slot: Int
    ): Check {
        blockedSegment = NO_INDEX
        blockedObstacle = NO_INDEX
        blockedReason = PathReason.NO_GEOMETRIC_CAUSE
        var uncertain = false

        for (obstacleIndex in union.indices) {
            obstacle.load(union[obstacleIndex], side, pose, frames, attachmentScale)
            for (index in 0 until count - 1) {
                obstacle.testSegment(
                    points[index * 3], points[index * 3 + 1], points[index * 3 + 2],
                    points[(index + 1) * 3], points[(index + 1) * 3 + 1], points[(index + 1) * 3 + 2],
                    index.toDouble() / (count - 1), (index + 1).toDouble() / (count - 1),
                    profile, validator.margin, verdict
                )
                when (verdict.outcome) {
                    StageOutcome.CLEAR -> Unit
                    StageOutcome.INTERSECT -> {
                        blockedReason = PathReason.CLEARANCE_VIOLATION
                        blockedSegment = index
                        blockedObstacle = obstacleIndex
                        return Check.BLOCKED
                    }
                    StageOutcome.UNCERTAIN -> {
                        uncertain = true
                        if (blockedSegment == NO_INDEX) {
                            blockedReason = PathReason.UNCERTIFIED_MINIMUM
                            blockedSegment = index
                            blockedObstacle = obstacleIndex
                        }
                    }
                }
            }
        }
        if (blockedSegment != NO_INDEX) return Check.BLOCKED

        val turn = worstTurn()
        if (turn > thetaMax) {
            turnSegment = turnIndex
            return Check.TURN
        }
        return Check.CLEARED
    }

    private var turnIndex = NO_INDEX

    /**
     * The largest direction change between consecutive segments. A zero-length pair contributes
     * nothing: it has no direction to turn.
     */
    private fun worstTurn(): Double {
        var worst = 0.0
        turnIndex = NO_INDEX
        for (index in 1 until count - 1) {
            val ax = points[index * 3] - points[(index - 1) * 3]
            val ay = points[index * 3 + 1] - points[(index - 1) * 3 + 1]
            val az = points[index * 3 + 2] - points[(index - 1) * 3 + 2]
            val bx = points[(index + 1) * 3] - points[index * 3]
            val by = points[(index + 1) * 3 + 1] - points[index * 3 + 1]
            val bz = points[(index + 1) * 3 + 2] - points[index * 3 + 2]
            val la = sqrt(ax * ax + ay * ay + az * az)
            val lb = sqrt(bx * bx + by * by + bz * bz)
            if (!(la > 0.0) || !(lb > 0.0)) continue
            val cosine = (ax * bx + ay * by + az * bz) / (la * lb)
            val angle = kotlin.math.acos(cosine.coerceIn(-1.0, 1.0))
            if (angle > worst) {
                worst = angle
                turnIndex = index
            }
        }
        return worst
    }

    private fun smallestDetour(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet
    ): Double {
        var smallest = Double.MAX_VALUE
        for (index in union.indices) {
            obstacle.load(union[index], side, pose, frames, attachmentScale)
            if (obstacle.nonFinite || obstacle.degenerate) continue
            smallest = minOf(smallest, obstacle.maxRadius)
        }
        return if (smallest == Double.MAX_VALUE) 0.0 else smallest + validator.margin
    }

    private fun unionOf(muscle: MuscleDefinition): List<WrapSurface> = unions.getValue(muscle.id)

    private fun writePath(frame: AnatomyFrame, slot: Int, profile: RadiusProfile) {
        frame.clear()
        for (index in 0 until count) {
            frame.writePoint(slot, points[index * 3].toFloat(), points[index * 3 + 1].toFloat(), points[index * 3 + 2].toFloat())
        }
        frame.modelledPathLengths[slot] = arcLength().toFloat()
    }

    private fun arcLength(): Double {
        var total = 0.0
        for (index in 1 until count) total += distance(points, index - 1, index)
        return total
    }

    private fun refuse(
        frame: AnatomyFrame,
        slot: Int,
        reason: PathReason,
        outcome: SearchOutcome,
        obstacleIndex: Int = NO_INDEX,
        segmentIndex: Int = NO_INDEX
    ): Int {
        frame.statuses[slot] = PathStatus.INVALID.ordinal
        frame.reasonCodes[slot] = reason.ordinal
        frame.searchOutcomes[slot] = outcome.ordinal
        frame.failingSegments[slot] = segmentIndex
        frame.failingObstacles[slot] = obstacleIndex
        frame.pointCounts[slot] = 0
        return NO_INDEX
    }

    private fun copy(from: DoubleArray, fromIndex: Int, to: DoubleArray, toIndex: Int) {
        to[toIndex * 3] = from[fromIndex * 3]
        to[toIndex * 3 + 1] = from[fromIndex * 3 + 1]
        to[toIndex * 3 + 2] = from[fromIndex * 3 + 2]
    }

    private fun distance(source: DoubleArray, from: Int, to: Int): Double {
        val dx = source[to * 3] - source[from * 3]
        val dy = source[to * 3 + 1] - source[from * 3 + 1]
        val dz = source[to * 3 + 2] - source[from * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val MAX_POINTS = 256
        const val ESCALATION = 1.5
        const val SMOOTH_BLEND = 0.25
    }
}
