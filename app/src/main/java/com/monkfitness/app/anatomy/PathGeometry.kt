package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonPose
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What one stage of the segment test decided. Only stage 1 and stage 3 may accept. */
enum class StageOutcome { CLEAR, INTERSECT, UNCERTAIN }

/**
 * One segment–obstacle result, reused across pairs: a pass over 18 heads × 48 points × several
 * obstacles must not allocate one record per pair.
 */
class PairVerdict {
    var outcome: StageOutcome = StageOutcome.UNCERTAIN
    /** 1, 2 or 3: which stage carried the claim. */
    var stage: Int = 0
    /** Whether stage 2 exhibited a station inside the solid by more than its allowance. */
    var witnessed: Boolean = false
    var tMinimum: Double = 0.0
    /** `d` at the minimising station, engine units. */
    var distance: Double = 0.0
    /** `d − r` at the minimising station. */
    var clearance: Double = 0.0
    var lower: Double = 0.0
    var upper: Double = 0.0
    var rootLo: Double = Double.NaN
    var rootHi: Double = Double.NaN
    /** The point-to-ellipsoid residual at the minimum; the evaluation budget is built from it. */
    var distanceResidual: Double = 0.0
}

/**
 * The tube radius along one path: affine between the definition's knots, in engine units.
 *
 * The taper is part of the shape the predicate certifies, so it is read as declared rather than
 * assumed constant — a guarantee that ignored it would certify a tube nobody draws.
 */
class RadiusProfile(knots: List<Pair<Float, Float>>, scale: Double) {

    /** Knot stations across the whole path, ascending. */
    val stations = DoubleArray(knots.size) { knots[it].first.toDouble() }

    /** Knot radii in engine units. */
    val radii = DoubleArray(knots.size) { knots[it].second.toDouble() * scale }

    /** The widest modelled radius, engine units: stage 1 expands its sphere by it. */
    val maxRadius: Double = if (radii.isEmpty()) 0.0 else radii.max()

    init {
        require(stations.isNotEmpty()) { "a radius profile needs at least one knot" }
        require(stations.size == radii.size) { "a radius profile needs one radius per knot" }
    }

    /** The modelled radius at global station [t], affine between knots. */
    fun at(t: Double): Double {
        if (stations.size == 1) return radii[0]
        if (t <= stations[0]) return radii[0]
        if (t >= stations[stations.size - 1]) return radii[radii.size - 1]
        var high = 1
        while (high < stations.size && stations[high] < t) high++
        val low = high - 1
        val span = stations[high] - stations[low]
        if (span <= 0.0) return radii[low]
        return radii[low] + (radii[high] - radii[low]) * ((t - stations[low]) / span)
    }
}

/**
 * One obstacle resolved into world space for this frame, and the three-stage test against it.
 *
 * Nothing is rescaled to a unit sphere: non-uniform axis scaling does not preserve Euclidean
 * distance, so the root and the surface point are solved in the obstacle's own coordinates.
 */
class ObstacleGeometry {

    val centre = DoubleArray(3)
    val axes = DoubleArray(9)
    val radii = DoubleArray(3)

    var maxRadius: Double = 0.0
        private set
    var nonFinite: Boolean = false
        private set
    var degenerate: Boolean = false
        private set

    private val solid = SolidDistance()
    private val local = DoubleArray(3)
    private var bracketHalfWidth = 0.0
    private var bestGap = Double.POSITIVE_INFINITY
    private var bestStation = 0.0
    private var bestResidual = 0.0

    /**
     * Resolves [surface] through [side]'s segment instance: an obstacle on a bilateral segment is
     * per side for the same reason an attachment is — the two instances are different bodies.
     */
    fun load(surface: WrapSurface, side: MuscleSide, pose: SkeletonPose, frames: SegmentFrameSet, scale: Double) {
        nonFinite = false
        degenerate = false
        val id = side.instance(surface.segment)
        val node = SegmentSpec.of(id).node
        if (node == null) {
            nonFinite = true
            maxRadius = 0.0
            return
        }
        val origin = pose.getJoint(node)
        for (axis in 0 until 3) {
            axes[axis * 3] = frames.axis(id, axis, 0).toDouble()
            axes[axis * 3 + 1] = frames.axis(id, axis, 1).toDouble()
            axes[axis * 3 + 2] = frames.axis(id, axis, 2).toDouble()
        }
        val ox = surface.centreMetres.first * scale
        val oy = surface.centreMetres.second * scale
        val oz = surface.centreMetres.third * scale
        centre[0] = origin.x + axes[0] * ox + axes[3] * oy + axes[6] * oz
        centre[1] = origin.y + axes[1] * ox + axes[4] * oy + axes[7] * oz
        centre[2] = origin.z + axes[2] * ox + axes[5] * oy + axes[8] * oz

        radii[0] = surface.radiiMetres.first * scale
        radii[1] = surface.radiiMetres.second * scale
        radii[2] = surface.radiiMetres.third * scale
        maxRadius = max(radii[0], max(radii[1], radii[2]))

        for (index in 0 until 3) {
            if (!radii[index].isFinite() || !centre[index].isFinite()) nonFinite = true
            if (!(radii[index] > 0.0)) degenerate = true
        }
        if (frames.status(id) == SegmentFrameStatus.INVALID) nonFinite = true
    }

    /**
     * Loads world geometry that is already resolved — centre, axes and semi-axes in engine units —
     * for a caller that has converted the obstacle itself rather than through a segment frame.
     */
    fun loadResolved(
        centreX: Double,
        centreY: Double,
        centreZ: Double,
        axisX: DoubleArray,
        radiusX: Double,
        radiusY: Double,
        radiusZ: Double
    ) {
        centre[0] = centreX
        centre[1] = centreY
        centre[2] = centreZ
        for (index in 0 until 9) axes[index] = axisX[index]
        radii[0] = radiusX
        radii[1] = radiusY
        radii[2] = radiusZ
        maxRadius = max(radiusX, max(radiusY, radiusZ))
        nonFinite = false
        degenerate = false
        for (index in 0 until 3) {
            if (!radii[index].isFinite() || !centre[index].isFinite()) nonFinite = true
            if (!(radii[index] > 0.0)) degenerate = true
        }
    }

    /** The obstacle's bracket half-width from the last test, for the report. */
    fun lastBracketHalfWidth(): Double = bracketHalfWidth

    /** Distance to the closed solid ellipsoid, engine units; `0` inside or on the surface. */
    fun distanceTo(px: Double, py: Double, pz: Double): Double {
        toLocal(px - centre[0], py - centre[1], pz - centre[2])
        return solid.compute(local[0], local[1], local[2], radii, maxRadius)
    }

    /** The point-to-ellipsoid residual `η` of the last [distanceTo] call. */
    fun distanceResidual(): Double = solid.residual

    /** Whether the point is inside or on the surface. */
    fun contains(px: Double, py: Double, pz: Double): Boolean {
        toLocal(px - centre[0], py - centre[1], pz - centre[2])
        val qx = local[0] / radii[0]
        val qy = local[1] / radii[1]
        val qz = local[2] / radii[2]
        return qx * qx + qy * qy + qz * qz <= 1.0
    }

    /**
     * Tests the segment from `a` to `b` against this obstacle, writing the verdict into [out].
     *
     * [stationStart] and [stationEnd] are the endpoints' stations on the whole path, so the tube
     * radius is read where the segment actually is — including a segment clipped to a non-exempt
     * piece.
     */
    fun testSegment(
        ax: Double,
        ay: Double,
        az: Double,
        bx: Double,
        by: Double,
        bz: Double,
        stationStart: Double,
        stationEnd: Double,
        radius: RadiusProfile,
        margin: Double,
        out: PairVerdict
    ) {
        out.witnessed = false
        out.stage = 3
        out.distanceResidual = 0.0
        out.rootLo = Double.NaN
        out.rootHi = Double.NaN
        bracketHalfWidth = 0.0

        if (nonFinite || degenerate || !margin.isFinite()) {
            out.outcome = StageOutcome.UNCERTAIN
            return
        }

        // Stage 1: the only cheap certified CLEAR. A segment that fails it is uncertain, never rejected.
        val half = 0.5 * sqrt(square(bx - ax) + square(by - ay) + square(bz - az))
        val midDistance = sqrt(
            square((ax + bx) * 0.5 - centre[0]) +
                square((ay + by) * 0.5 - centre[1]) +
                square((az + bz) * 0.5 - centre[2])
        )
        if (!half.isFinite() || !midDistance.isFinite()) {
            out.outcome = StageOutcome.UNCERTAIN
            return
        }
        if (midDistance - half > maxRadius + radius.maxRadius + margin) {
            out.outcome = StageOutcome.CLEAR
            out.stage = 1
            return
        }

        out.witnessed = stageTwo(ax, ay, az, bx, by, bz, out)

        stageThree(ax, ay, az, bx, by, bz, stationStart, stationEnd, radius, margin, out)
    }

    /**
     * Stage 2: the chord, whose roots are diagnostics. A witness station is recorded, but the
     * coefficient formation carries no proved forward-error enclosure, so a witnessed entry is
     * routed to stage 3 rather than reported as an intersection.
     */
    private fun stageTwo(
        ax: Double,
        ay: Double,
        az: Double,
        bx: Double,
        by: Double,
        bz: Double,
        out: PairVerdict
    ): Boolean {
        toLocal(ax - centre[0], ay - centre[1], az - centre[2])
        val q0x = local[0] / radii[0]
        val q0y = local[1] / radii[1]
        val q0z = local[2] / radii[2]

        toLocal(bx - ax, by - ay, bz - az)
        val qdx = local[0] / radii[0]
        val qdy = local[1] / radii[1]
        val qdz = local[2] / radii[2]

        val a = qdx * qdx + qdy * qdy + qdz * qdz
        val b = 2.0 * (q0x * qdx + q0y * qdy + q0z * qdz)
        val c0 = q0x * q0x + q0y * q0y + q0z * q0z - 1.0
        val delta = b * b - 4.0 * a * c0
        val deltaTolerance = DELTA_KAPPA * (b * b + abs(4.0 * a * c0))

        if (a == 0.0 || !delta.isFinite()) return false
        if (abs(delta) <= deltaTolerance || delta < -deltaTolerance) return false

        val root = sqrt(delta)
        // sign(0) = +1: a symmetric chord from the centre has b = 0, and sign(0) = 0 divides by zero.
        val q = -0.5 * (b + if (b >= 0.0) root else -root)
        if (q == 0.0) return false
        val t0 = q / a
        val t1 = c0 / q
        out.rootLo = min(t0, t1)
        out.rootHi = max(t0, t1)

        val from = max(out.rootLo, 0.0)
        val to = min(out.rootHi, 1.0)
        if (!(to > from)) return false

        val station = 0.5 * (from + to)
        val s = q0x * q0x + q0y * q0y + q0z * q0z +
            2.0 * station * (q0x * qdx + q0y * qdy + q0z * qdz) +
            station * station * a
        return s <= 1.0 - WITNESS_KAPPA * (s + 1.0)
    }

    /** Stage 3: bracket the minimum of `d(t) − r(t)` on each radius piece. */
    private fun stageThree(
        ax: Double,
        ay: Double,
        az: Double,
        bx: Double,
        by: Double,
        bz: Double,
        stationStart: Double,
        stationEnd: Double,
        radius: RadiusProfile,
        margin: Double,
        out: PairVerdict
    ) {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val span = stationEnd - stationStart
        val length = sqrt(dx * dx + dy * dy + dz * dz)

        bestGap = Double.POSITIVE_INFINITY
        bestStation = 0.0
        bestResidual = 0.0

        // A local function that closes over a mutable local is compiled into a `Ref` object per call,
        // which would allocate once per segment pair — the one thing this stage cannot do.
        evaluate(ax, ay, az, dx, dy, dz, 0.0, stationStart, span, radius)

        val knots = radius.stations
        var start = 0.0
        for (index in knots.indices) {
            if (span <= 0.0) break
            val u = (knots[index] - stationStart) / span
            if (u <= start) continue
            val end = min(u, 1.0)
            piece(ax, ay, az, bx, by, bz, dx, dy, dz, start, end, stationStart, span, radius)
            start = end
            if (start >= 1.0) break
        }
        piece(ax, ay, az, bx, by, bz, dx, dy, dz, start, 1.0, stationStart, span, radius)
        evaluate(ax, ay, az, dx, dy, dz, 0.0, stationStart, span, radius)
        evaluate(ax, ay, az, dx, dy, dz, 1.0, stationStart, span, radius)

        val radiusAtMinimum = radius.at(stationStart + span * bestStation)
        val evaluationBudget = bestResidual * maxRadius * 0.5 + RADIUS_ROUNDING * abs(radiusAtMinimum)
        out.tMinimum = bestStation
        out.clearance = bestGap
        out.distance = bestGap + radiusAtMinimum
        out.distanceResidual = bestResidual
        out.lower = bestGap - bracketHalfWidth - evaluationBudget
        out.upper = bestGap + evaluationBudget
        out.outcome = when {
            out.lower >= margin -> StageOutcome.CLEAR
            out.upper < margin -> StageOutcome.INTERSECT
            else -> StageOutcome.UNCERTAIN
        }
    }

    /** One `g(u)` evaluation, updating the running minimum. */
    private fun evaluate(
        ax: Double, ay: Double, az: Double,
        dx: Double, dy: Double, dz: Double,
        u: Double, stationStart: Double, span: Double,
        radius: RadiusProfile
    ) {
        toLocal(ax + dx * u - centre[0], ay + dy * u - centre[1], az + dz * u - centre[2])
        val d = solid.compute(local[0], local[1], local[2], radii, maxRadius)
        val g = d - radius.at(stationStart + span * u)
        if (g < bestGap) {
            bestGap = g
            bestStation = u
            bestResidual = solid.residual
        }
    }

    /** Golden section on one radius piece; the iteration count is declared, never a convergence test. */
    private fun piece(
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        dx: Double, dy: Double, dz: Double,
        from: Double, to: Double,
        stationStart: Double, span: Double,
        radius: RadiusProfile
    ) {
        if (!(to > from)) return
        evaluate(ax, ay, az, dx, dy, dz, from, stationStart, span, radius)
        evaluate(ax, ay, az, dx, dy, dz, to, stationStart, span, radius)

        val length = sqrt(square(bx - ax) + square(by - ay) + square(bz - az))
        val radiusGradient = abs(radius.at(stationStart + span * to) - radius.at(stationStart + span * from)) / (to - from)
        val width = (to - from) * GOLDEN_SHRINK
        bracketHalfWidth = max(bracketHalfWidth, (length + radiusGradient) * width * 0.5)

        var low = from
        var high = to
        var x1 = high - (high - low) / GOLDEN
        var x2 = low + (high - low) / GOLDEN
        var f1 = gapAt(ax, ay, az, dx, dy, dz, x1, stationStart, span, radius)
        var f2 = gapAt(ax, ay, az, dx, dy, dz, x2, stationStart, span, radius)
        for (iteration in 0 until MINIMIZER_STEPS) {
            if (f1 < f2) {
                high = x2
                x2 = x1
                f2 = f1
                x1 = high - (high - low) / GOLDEN
                f1 = gapAt(ax, ay, az, dx, dy, dz, x1, stationStart, span, radius)
            } else {
                low = x1
                x1 = x2
                f1 = f2
                x2 = low + (high - low) / GOLDEN
                f2 = gapAt(ax, ay, az, dx, dy, dz, x2, stationStart, span, radius)
            }
        }
        evaluate(ax, ay, az, dx, dy, dz, if (f1 < f2) x1 else x2, stationStart, span, radius)
    }

    private fun gapAt(
        ax: Double,
        ay: Double,
        az: Double,
        dx: Double,
        dy: Double,
        dz: Double,
        u: Double,
        stationStart: Double,
        span: Double,
        radius: RadiusProfile
    ): Double {
        toLocal(ax + dx * u - centre[0], ay + dy * u - centre[1], az + dz * u - centre[2])
        val d = solid.compute(local[0], local[1], local[2], radii, maxRadius)
        return d - radius.at(stationStart + span * u)
    }

    /** Rotates `(x, y, z)` into the obstacle's frame: `Rᵀ · v`. */
    private fun toLocal(x: Double, y: Double, z: Double) {
        val lx = axes[0] * x + axes[1] * y + axes[2] * z
        val ly = axes[3] * x + axes[4] * y + axes[5] * z
        val lz = axes[6] * x + axes[7] * y + axes[8] * z
        local[0] = lx
        local[1] = ly
        local[2] = lz
    }

    companion object {
        /** Fixed iteration count: two implementations return the same bracket for the same input. */
        const val MINIMIZER_STEPS = 30

        /** Relative fail-closed band on the discriminant: it routes a pair to stage 3, never clears. */
        const val DELTA_KAPPA = 1e-12

        /** Witness allowance. It is not a forward-error bound, which is why a witness is not a verdict. */
        const val WITNESS_KAPPA = 1e-12

        /** Half a binary32 ulp: the authored radius is a `Float` well before it reaches here. */
        const val RADIUS_ROUNDING = 5.960464477539063e-8

        val GOLDEN: Double = (1.0 + sqrt(5.0)) / 2.0

        /** `φ⁻ⁿ` at [MINIMIZER_STEPS]: the bracket width as a fraction of a piece. */
        val GOLDEN_SHRINK: Double = Math.pow(1.0 / GOLDEN, MINIMIZER_STEPS.toDouble())
    }
}

private fun square(value: Double): Double = value * value

/**
 * Distance from a point to a closed solid ellipsoid, never to its surface.
 *
 * The solid distance is convex and is `0` inside, which is what makes `d(t) − r(t)` convex on a
 * radius piece; the surface distance is not, and a minimiser over it converges into the wrong basin.
 */
internal class SolidDistance {

    /** `η = |Σ (x_i / a_i)² − 1|` at the returned surface point: the residual, not a bound. */
    var residual: Double = 0.0
        private set

    private val point = DoubleArray(3)
    private val surface = DoubleArray(3)

    /** [lx], [ly], [lz] are already in the obstacle's own frame. */
    fun compute(lx: Double, ly: Double, lz: Double, radii: DoubleArray, maxRadius: Double): Double {
        val rx = radii[0]
        val ry = radii[1]
        val rz = radii[2]
        residual = 0.0

        val qx = lx / rx
        val qy = ly / ry
        val qz = lz / rz
        if (qx * qx + qy * qy + qz * qz <= 1.0) return 0.0

        if (rx == ry && ry == rz) {
            // The sphere case is solved directly: λ would be a difference of nearly equal squares.
            return sqrt(lx * lx + ly * ly + lz * lz) - rx
        }

        point[0] = lx
        point[1] = ly
        point[2] = lz

        var low = 0.0
        var high = maxRadius
        var grown = 0
        while (sum(high, rx, ry, rz) > 0.0) {
            high *= 2.0
            grown++
            if (grown >= BRACKET_STEPS || !high.isFinite()) {
                residual = Double.NaN
                return Double.NaN
            }
        }
        for (iteration in 0 until BISECTION_STEPS) {
            val mid = 0.5 * (low + high)
            if (sum(mid, rx, ry, rz) > 0.0) low = mid else high = mid
            if (high - low <= abs(high) * 2e-12) break
        }
        val lambda = 0.5 * (low + high)

        surface[0] = rx * rx * point[0] / (rx * rx + lambda)
        surface[1] = ry * ry * point[1] / (ry * ry + lambda)
        surface[2] = rz * rz * point[2] / (rz * rz + lambda)

        val onSurface = square(surface[0] / rx) + square(surface[1] / ry) + square(surface[2] / rz)
        residual = abs(onSurface - 1.0)
        return sqrt(
            square(point[0] - surface[0]) + square(point[1] - surface[1]) + square(point[2] - surface[2])
        )
    }

    private fun sum(lambda: Double, rx: Double, ry: Double, rz: Double): Double {
        val sx = rx * point[0] / (rx * rx + lambda)
        val sy = ry * point[1] / (ry * ry + lambda)
        val sz = rz * point[2] / (rz * rz + lambda)
        return sx * sx + sy * sy + sz * sz - 1.0
    }

    private companion object {
        const val BISECTION_STEPS = 80
        const val BRACKET_STEPS = 200
    }
}
