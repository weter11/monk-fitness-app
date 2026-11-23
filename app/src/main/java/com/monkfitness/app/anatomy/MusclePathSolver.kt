package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonPose
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
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
    private val attachmentScale: Double,
    /**
     * Keeps step 4a's entry and exit construction points instead of letting the waypoint supersede
     * them. They sit on the surface, so a route anchored on one can never clear; the switch exists
     * so that claim is measured rather than asserted.
     */
    private val retainConstructionPoints: Boolean = false
) {

    private val points = DoubleArray(MAX_POINTS * 3)
    /** The polyline as [unfold] received it, so a repair that does not work leaves no trace. */
    private val unfoldSaved = DoubleArray(MAX_POINTS * 3)
    private val smoothed = DoubleArray(MAX_POINTS * 3)
    private var count = 0
    /** Per segment, the first obstacle in union order that does not accept it; frozen per pass. */
    private val blockedBy = IntArray(MAX_POINTS) { NO_INDEX }
    private val entryStations = DoubleArray(MAX_POINTS)
    private val exitStations = DoubleArray(MAX_POINTS)
    /** The 1-based pass that accepted, or the pass the loop stopped on. Reported, never assumed. */
    var lastPass: Int = 0
        private set
    private val obstacle = ObstacleGeometry()
    private val unions: Map<String, List<WrapSurface>> =
        definition.muscles.associate { it.id to definition.union(it) }
    /** One profile per muscle: each owns two arrays, and every head is solved on every frame. */
    private val profiles: Map<String, RadiusProfile> =
        definition.muscles.associate { it.id to RadiusProfile(it.radiusKnots, attachmentScale) }
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
        val profile = profiles.getValue(muscle.id)
        val corridor = muscle.corridorMetres * attachmentScale
        val firstDetour = smallestDetour(union, side, pose, frames)
        var detourScale = 1.0
        if (firstDetour > corridor) {
            // No candidate can be placed inside the declared corridor: the search stops before a pass.
            return refuse(frame, slot, PathReason.NO_GEOMETRIC_CAUSE, SearchOutcome.SEARCH_EXHAUSTED)
        }

        var lastReason = PathReason.NO_GEOMETRIC_CAUSE
        var lastSegment = NO_INDEX
        var lastObstacle = NO_INDEX

        for (pass in 0 until muscle.passCap) {
            lastPass = pass + 1
            // The sample count is recomputed from the current arc length at the start of every pass,
            // so a detour is sampled at the declared spacing instead of inheriting the chord's grid.
            if (pass > 0) count = resample(count)
            if (insertDetours(union, side, pose, frames, detourScale, profile) > 0) {
                smooth()
            }
            project(union, side, pose, frames, profile)
            unfold(union, side, pose, frames, profile)

            // A capacity refusal is decided before the corridor and the pass cap: it is an abort
            // inside this pass, not an exhausted search.
            if (count > frame.pointCapacity) {
                return refuse(frame, slot, PathReason.BUFFER_CAPACITY, SearchOutcome.REJECTED_IN_SEARCH, segmentIndex = count - 1)
            }

            val check = check(union, side, pose, frames, profile, frame, slot)
            when (check) {
                Check.CLEARED -> {
                    if (!writePath(frame, slot, profile)) {
                        return refuse(frame, slot, PathReason.BUFFER_CAPACITY, SearchOutcome.REJECTED_IN_SEARCH, segmentIndex = count - 1)
                    }
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
            // The scale is a multiplier on d_1, never a distance: d_k = 1.5^(k-1) * d_1.
            detourScale *= ESCALATION
            if (firstDetour * detourScale > corridor) {
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
        val wanted = ((total / (definition.sampling.spacingMetres * attachmentScale)).roundToInt() + 1)
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
     * Inserts one lateral waypoint per run of consecutive crossed segments. The direction is the
     * chord's own lateral component, so the detour is a function of the geometry it avoids rather
     * than of a world axis.
     *
     * A run is replaced as a whole: the samples inside the surface and the two entry/exit stations
     * are construction points the waypoint supersedes, and a point on the surface is a
     * zero-clearance point the predicate refuses at every detour distance — keeping one would turn a
     * clearable route into a pass-cap failure.
     */
    private fun insertDetours(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        detourScale: Double,
        profile: RadiusProfile
    ): Int {
        if (!freezeBlockedPairs(union, side, pose, frames, profile)) return 0

        var inserted = 0
        var write = 0
        var index = 0
        while (index < count) {
            copy(points, index, smoothed, write++)
            if (index >= count - 1) break

            val blocker = blockedBy[index]
            if (blocker == NO_INDEX) {
                index++
                continue
            }
            var end = index
            while (end + 1 < count - 1 && blockedBy[end + 1] == blocker) end++

            val added = if (retainConstructionPoints) 3 else 1
            if (write + (count - index) + added > MAX_POINTS) {
                // No room for a waypoint: the run survives as it is and the next pass sees it again.
                for (interior in index + 1..end) copy(points, interior, smoothed, write++)
                index = end + 1
                continue
            }

            obstacle.load(union[blocker], side, pose, frames, attachmentScale)
            if (retainConstructionPoints) {
                lerp(index, entryStations[index], write)
                write++
            }
            if (!placeWaypoint(index, end + 1, detourScale, write)) {
                for (interior in index + 1..end) copy(points, interior, smoothed, write++)
                index = end + 1
                continue
            }
            write++
            inserted++
            if (retainConstructionPoints) {
                lerp(end, exitStations[end], write)
                write++
            }
            index = end + 1
        }
        if (inserted > 0) {
            System.arraycopy(smoothed, 0, points, 0, write * 3)
            count = write
        }
        return inserted
    }

    /**
     * Freezes the `(segment, obstacle)` pairs to act on this pass, against the pass's input polyline:
     * the first obstacle in union order that does not accept a segment owns it. Segments created by
     * this pass are first tested by the next one.
     */
    private fun freezeBlockedPairs(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        profile: RadiusProfile
    ): Boolean {
        var blocked = false
        for (segment in 0 until count - 1) {
            blockedBy[segment] = NO_INDEX
            for (obstacleIndex in union.indices) {
                obstacle.load(union[obstacleIndex], side, pose, frames, attachmentScale)
                obstacle.testSegment(
                    points[segment * 3], points[segment * 3 + 1], points[segment * 3 + 2],
                    points[(segment + 1) * 3], points[(segment + 1) * 3 + 1], points[(segment + 1) * 3 + 2],
                    segment.toDouble() / (count - 1), (segment + 1).toDouble() / (count - 1),
                    profile, validator.margin, verdict
                )
                if (verdict.outcome == StageOutcome.CLEAR) continue
                blockedBy[segment] = obstacleIndex
                entryStations[segment] = clampStation(verdict.rootLo, 0.0)
                exitStations[segment] = clampStation(verdict.rootHi, 1.0)
                blocked = true
                break
            }
        }
        return blocked
    }

    /**
     * Places one waypoint on the chord `from → to`, one numeric margin outside the support plane in
     * the chosen lateral direction: `d = (h(l̂_d) + e) · detourScale`.
     *
     * False when the chord has no direction, so a degenerate segment cannot invent one.
     */
    private fun placeWaypoint(from: Int, to: Int, detourScale: Double, at: Int): Boolean {
        val ax = points[from * 3]
        val ay = points[from * 3 + 1]
        val az = points[from * 3 + 2]
        val dx = points[to * 3] - ax
        val dy = points[to * 3 + 1] - ay
        val dz = points[to * 3 + 2] - az
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!(length > 0.0)) return false
        val dxn = dx / length
        val dyn = dy / length
        val dzn = dz / length

        // The lateral component of the chord offset, or the local axis least aligned with the chord —
        // chosen in the obstacle's own frame, because world axes assume Rw = I.
        val mx = 0.5 * (ax + points[to * 3]) - obstacle.centre[0]
        val my = 0.5 * (ay + points[to * 3 + 1]) - obstacle.centre[1]
        val mz = 0.5 * (az + points[to * 3 + 2]) - obstacle.centre[2]
        val along = mx * dxn + my * dyn + mz * dzn
        val ux = mx - along * dxn
        val uy = my - along * dyn
        val uz = mz - along * dzn
        val uLength = sqrt(ux * ux + uy * uy + uz * uz)

        var lx: Double
        var ly: Double
        var lz: Double
        if (uLength > MIN_LATERAL_METRES * attachmentScale) {
            lx = ux / uLength; ly = uy / uLength; lz = uz / uLength
        } else {
            // Chord direction in local axes, then the least-aligned local axis, rotated back.
            val tx = obstacle.axes[0] * dxn + obstacle.axes[1] * dyn + obstacle.axes[2] * dzn
            val ty = obstacle.axes[3] * dxn + obstacle.axes[4] * dyn + obstacle.axes[5] * dzn
            val tz = obstacle.axes[6] * dxn + obstacle.axes[7] * dyn + obstacle.axes[8] * dzn
            val ax0 = abs(tx); val ay0 = abs(ty); val az0 = abs(tz)
            val chooseX = ax0 <= ay0 && ax0 <= az0
            val chooseY = !chooseX && ay0 <= az0
            val ox = if (chooseX) 1.0 else 0.0
            val oy = if (chooseY) 1.0 else 0.0
            val oz = if (chooseX || chooseY) 0.0 else 1.0
            lx = obstacle.axes[0] * ox + obstacle.axes[3] * oy + obstacle.axes[6] * oz
            ly = obstacle.axes[1] * ox + obstacle.axes[4] * oy + obstacle.axes[7] * oz
            lz = obstacle.axes[2] * ox + obstacle.axes[5] * oy + obstacle.axes[8] * oz
        }

        // h(l̂_d) is the support function of the uninflated ellipsoid, so the margin is added once.
        val hx = obstacle.axes[0] * lx + obstacle.axes[1] * ly + obstacle.axes[2] * lz
        val hy = obstacle.axes[3] * lx + obstacle.axes[4] * ly + obstacle.axes[5] * lz
        val hz = obstacle.axes[6] * lx + obstacle.axes[7] * ly + obstacle.axes[8] * lz
        val rx = obstacle.radii[0] * hx
        val ry = obstacle.radii[1] * hy
        val rz = obstacle.radii[2] * hz
        val support = sqrt(rx * rx + ry * ry + rz * rz)
        val distance = (support + validator.margin) * detourScale

        smoothed[at * 3] = obstacle.centre[0] + lx * distance
        smoothed[at * 3 + 1] = obstacle.centre[1] + ly * distance
        smoothed[at * 3 + 2] = obstacle.centre[2] + lz * distance
        return true
    }

    /** The point at [station] along segment [segment], written to [at]. */
    private fun lerp(segment: Int, station: Double, at: Int) {
        for (component in 0..2) {
            val from = points[segment * 3 + component]
            val to = points[(segment + 1) * 3 + component]
            smoothed[at * 3 + component] = from + (to - from) * station
        }
    }

    private fun clampStation(station: Double, fallback: Double): Double =
        if (station.isNaN()) fallback else station.coerceIn(0.0, 1.0)

    /**
     * Removes the one-point fold that [project] can leave behind.
     *
     * A sample pushed out of an obstacle while its neighbours stay put becomes a spike: the curvature
     * test reads it as a fold and the head is refused, although the route itself is clearable. Moving
     * that sample onto the chord of its neighbours and pushing it back out of the obstacle it was
     * clearing straightens the spike where it is, so a path the projection did not fold is untouched.
     *
     * Bounded and undecided: a fold this cannot remove reaches [check] and is refused there, exactly as
     * before.
     */
    private fun unfold(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        profile: RadiusProfile
    ) {
        if (worstTurn() <= thetaMax) return
        System.arraycopy(points, 0, unfoldSaved, 0, count * 3)
        var pass = 0
        while (pass++ < UNFOLD_PASSES && worstTurn() > thetaMax) {
            val fold = turnIndex
            val from = (fold - UNFOLD_WINDOW).coerceAtLeast(1)
            val to = (fold + UNFOLD_WINDOW).coerceAtMost(count - 2)
            // The fold is the apex of a detour, so the repair is a window and not a point: relaxing the
            // samples around it toward their own chords and pushing the whole window back out of the
            // obstacle turns a spike into the arc the detour was trying to be.
            for (index in from..to) {
                for (component in 0..2) {
                    points[index * 3 + component] =
                        0.5 * (points[(index - 1) * 3 + component] + points[(index + 1) * 3 + component])
                }
            }
            for (index in from..to) projectSample(union, side, pose, frames, profile, index)
        }
        // A fold this cannot remove is the geometry's, not the projection's: the polyline goes back
        // exactly as it came in, and the check reports the cause it would have reported anyway.
        if (worstTurn() > thetaMax) System.arraycopy(unfoldSaved, 0, points, 0, count * 3)
    }

    /** Pushes one interior sample out of every obstacle it still sits inside, as [project] does. */
    private fun projectSample(
        union: List<WrapSurface>,
        side: MuscleSide,
        pose: SkeletonPose,
        frames: SegmentFrameSet,
        profile: RadiusProfile,
        index: Int
    ) {
        val station = index.toDouble() / (count - 1)
        for (obstacleIndex in union.indices) {
            obstacle.load(union[obstacleIndex], side, pose, frames, attachmentScale)
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
            // Indices 0 and last are the resolved attachments: they are boundary conditions, and a
            // route that cannot clear from them is refused, never repaired by moving the muscle.
            for (index in 1 until count - 1) {
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

    /** The largest direction change between consecutive segments. A zero-length pair contributes
     * nothing: it has no direction to turn. */
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

    private fun writePath(frame: AnatomyFrame, slot: Int, profile: RadiusProfile): Boolean {
        // Only this slot restarts: the frame's other slots, its rest lengths and its mask belong to
        // the solve, and a head that clears must not erase them.
        frame.pointCounts[slot] = 0
        var written = 0
        for (index in 0 until count) {
            if (!frame.writePoint(slot, points[index * 3].toFloat(), points[index * 3 + 1].toFloat(), points[index * 3 + 2].toFloat())) {
                return false
            }
            written++
        }
        frame.modelledPathLengths[slot] = arcLength().toFloat()
        return written == count
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
        const val UNFOLD_PASSES = 6
        const val UNFOLD_WINDOW = 2
        const val ESCALATION = 1.5
        const val SMOOTH_BLEND = 0.25

        /** Below this lateral offset the chord runs through the centre and the fallback axis decides. */
        const val MIN_LATERAL_METRES = 1e-6
    }
}
