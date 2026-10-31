package com.monkfitness.app.animation

import kotlin.math.*

class Vector3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vector3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(v: Vector3): Vector3 {
        this.x = v.x
        this.y = v.y
        this.z = v.z
        return this
    }

    operator fun plus(v: Vector3) = Vector3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vector3) = Vector3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vector3(x / s, y / s, z / s)

    fun add(v: Vector3): Vector3 {
        x += v.x
        y += v.y
        z += v.z
        return this
    }

    fun subtract(v: Vector3): Vector3 {
        x -= v.x
        y -= v.y
        z -= v.z
        return this
    }

    fun multiply(s: Float): Vector3 {
        x *= s
        y *= s
        z *= s
        return this
    }

    fun divide(s: Float): Vector3 {
        x /= s
        y /= s
        z /= s
        return this
    }

    fun mag() = sqrt(x * x + y * y + z * z)

    fun normalize(): Vector3 {
        val m = mag()
        return if (m > 1e-6) this.divide(m) else this.set(0f, 0f, 0f)
    }

    fun normalizedCopy(): Vector3 {
        val m = mag()
        return if (m > 1e-6) this / m else Vector3(0f, 0f, 0f)
    }

    fun dot(v: Vector3) = x * v.x + y * v.y + z * v.z

    fun cross(v: Vector3, result: Vector3): Vector3 {
        val rx = y * v.z - z * v.y
        val ry = z * v.x - x * v.z
        val rz = x * v.y - y * v.x
        return result.set(rx, ry, rz)
    }

    fun cross(v: Vector3) = Vector3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )

    fun copy() = Vector3(x, y, z)

    override fun toString(): String = "Vector3(x=$x, y=$y, z=$z)"
}

/**
 * Shared angular limits for a two-bone limb chain, in degrees.
 *
 * These are anatomical *angular* ranges, not the reach-distance band, so the engine can reject
 * orientations the distance clamp cannot (an over-folded knee, a hyperextended elbow) and
 * validation can mirror them. Named and shared — never per-exercise magic numbers.
 *
 * `minFlexionDegrees`/`maxFlexionDegrees` bound the interior angle at the middle joint;
 * `maxFlexionDegrees` caps hyperextension (180° == perfectly straight). `maxRootDeviationDegrees`
 * bounds the proximal bone's deviation from its anatomical neutral, clamped only when the caller
 * supplies a neutral direction.
 */
data class AngularJointLimits(
    val minFlexionDegrees: Float,
    val maxFlexionDegrees: Float,
    val maxRootDeviationDegrees: Float
) {
    companion object {
        // Named, shared human-range caps — not per-exercise values.
        const val DEFAULT_MIN_FLEXION_DEGREES = 30f
        const val DEFAULT_MAX_FLEXION_DEGREES = 180f
        const val DEFAULT_MAX_ROOT_DEVIATION_DEGREES = 170f

        val ArmAngularLimits = AngularJointLimits(
            minFlexionDegrees = DEFAULT_MIN_FLEXION_DEGREES,
            maxFlexionDegrees = DEFAULT_MAX_FLEXION_DEGREES,
            maxRootDeviationDegrees = DEFAULT_MAX_ROOT_DEVIATION_DEGREES
        )
        val LegAngularLimits = AngularJointLimits(
            minFlexionDegrees = DEFAULT_MIN_FLEXION_DEGREES,
            maxFlexionDegrees = DEFAULT_MAX_FLEXION_DEGREES,
            maxRootDeviationDegrees = DEFAULT_MAX_ROOT_DEVIATION_DEGREES
        )
    }
}

/**
 * Shared anatomical range-of-motion limits for the hip — a genuine 3-DOF ball-and-socket at the
 * acetabulum, unlike the knee/elbow middle-joint band, so without these a pose could flex, abduct
 * or rotate the hip beyond human ROM and still validate clean. These degrees are the single source
 * of truth for the validator's `HIP_ROM_LIMIT` rule and the solver's femur-direction clamp.
 *
 * Flexion/extension are measured about the sagittal (Z) axis from the neutral down direction;
 * abduction/adduction about the lateral (X) axis; internal/external rotation about the femur's long
 * axis. Caps are generous: a full split (~90° abduction) and a deep squat (~120° flexion) pass,
 * while a femur swung "through the torso" (~180°) is caught.
 */
data class HipRomLimits(
    val maxFlexionDegrees: Float,
    val maxExtensionDegrees: Float,
    val maxAbductionDegrees: Float,
    val maxAdductionDegrees: Float,
    val maxInternalRotationDegrees: Float,
    val maxExternalRotationDegrees: Float,
    /**
     * Maximum total excursion of the femur from its neutral (straight-down) direction. A single
     * axis-label-agnostic cap that catches an over-range hip without false-positives on valid
     * extreme-but-anatomical poses; the validator's over-range detector uses this bound.
     */
    val maxExcursionDegrees: Float
) {
    companion object {
        const val DEFAULT_MAX_FLEXION = 150f
        const val DEFAULT_MAX_EXTENSION = 25f
        const val DEFAULT_MAX_ABDUCTION = 95f
        const val DEFAULT_MAX_ADDUCTION = 40f
        const val DEFAULT_MAX_INTERNAL_ROTATION = 45f
        const val DEFAULT_MAX_EXTERNAL_ROTATION = 60f
        const val DEFAULT_MAX_EXCURSION = 150f

        val DEFAULT = HipRomLimits(
            maxFlexionDegrees = DEFAULT_MAX_FLEXION,
            maxExtensionDegrees = DEFAULT_MAX_EXTENSION,
            maxAbductionDegrees = DEFAULT_MAX_ABDUCTION,
            maxAdductionDegrees = DEFAULT_MAX_ADDUCTION,
            maxInternalRotationDegrees = DEFAULT_MAX_INTERNAL_ROTATION,
            maxExternalRotationDegrees = DEFAULT_MAX_EXTERNAL_ROTATION,
            maxExcursionDegrees = DEFAULT_MAX_EXCURSION
        )
    }
}

/**
 * A hip's femur direction decomposed into the four anatomical angles [HipRomLimits] bounds,
 * produced by the engine so the validator reads it instead of re-deriving geometry.
 *
 * All angles are in degrees; signs follow [HipRomLimits] (sagittal + = flexion toward +X; frontal
 * + = abduction, mirrored per side; axial + = internal rotation about the femur's long axis).
 */
data class HipRomStamp(
    val excursionDegrees: Float,
    val sagittalDegrees: Float,
    val frontalDegrees: Float,
    val axialDegrees: Float
)

data class IKConstraint(
    val minimumFlexionAngle: Float,
    val maximumExtensionRatio: Float,
    val angularLimits: AngularJointLimits = AngularJointLimits.ArmAngularLimits,
    /**
     * When `false` (the default) a limb is capped at [maximumExtensionRatio]·(L1+L2) — the 0.98
     * safety band that keeps dynamic motion from snapping to a locked-out joint. When `true` the cap
     * is the true anatomical length, so a deliberately rigid reference limb (Dead Hang arms, Pike
     * Sit / Middle Split legs) can be perfectly straight instead of a few percent bent. A policy flag
     * only; it never changes bent-limb behaviour.
     */
    val allowFullExtension: Boolean = false
) {
    /**
     * The ratio the solver actually honours: 1.0 when full extension is opted into, otherwise the
     * safety cap. Kept here so both solve paths read a single source of truth.
     */
    val effectiveExtensionRatio: Float
        get() = if (allowFullExtension) 1f else maximumExtensionRatio

    /** Copy with full extension enabled (allocation-free when already set). */
    fun fullyExtended(): IKConstraint = if (allowFullExtension) this else copy(allowFullExtension = true)

    companion object {
        val ArmConstraint = IKConstraint(30f, 0.98f, AngularJointLimits.ArmAngularLimits)
        val LegConstraint = IKConstraint(30f, 0.98f, AngularJointLimits.LegAngularLimits)
    }
}

/**
 * A fixed support contact the end-effector must respect: when a solve would drive a planted
 * hand/foot through the surface, the end joint is clamped *onto* the [normal]/[point] plane rather
 * than along the free direction, so the contact stays on the ground/bar/prop without penetration
 * while the reachability clamp is still recorded.
 *
 * The plane is general caller data — a ground plane, a bar top, a prop surface — never a single-pose
 * constant.
 */
data class ContactConstraint(
    val normal: Vector3,
    val point: Vector3
) {
    companion object {
        /** Horizontal ground plane at height [level]: normal (0,1,0), passing through (0,level,0). */
        fun ground(level: Float) = ContactConstraint(Vector3(0f, 1f, 0f), Vector3(0f, level, 0f))
    }
}

    object SkeletonMath {
    // Scratch for deriving the default world pole (never allocates per solve).
    private val defaultPoleAim = Vector3()

    // Zero pole reused by the degenerate-straight-limb fallback: a zero-magnitude pole makes
    // [solveTriangleJoint] pick its stable world-down bend plane (never allocates per solve).
    private val straightDegeneratePole = Vector3(0f, 0f, 0f)

    private const val DEG2RAD = 3.1415927f / 180f
    private const val RAD2DEG = 180f / 3.1415927f

    class NearStraightLimbResult(var x: Float = 0f, var y: Float = 0f, var d: Float = 0f)

    /**
     * The perpendicular (kX, kY) local offset for a two-bone limb that should look nearly straight,
     * driven by a target flexion angle rather than a linear-extension ratio — the ratio does not
     * correspond linearly to visual straightness near full extension.
     */
    fun solveNearStraightLimb(
        L1: Float,
        L2: Float,
        targetFlexionDegrees: Float,
        result: NearStraightLimbResult = NearStraightLimbResult()
    ): NearStraightLimbResult {
        val phi = targetFlexionDegrees * PI.toFloat() / 180f
        val d = sqrt(L1 * L1 + L2 * L2 + 2f * L1 * L2 * cos(phi))
        val x = (L1 * L1 - L2 * L2 + d * d) / (2f * d)
        val y = -sqrt((L1 * L1 - x * x).coerceAtLeast(0f))
        result.x = x
        result.y = y
        result.d = d
        return result
    }

    /** Ease-in-out quintic. */
    fun easeIO(x: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        return cx * cx * cx * (cx * (cx * 6 - 15) + 10)
    }

    /** Ease-in-out quadratic. */
    fun easeInOut(x: Float): Float {
        return if (x < 0.5f) {
            2f * x * x
        } else {
            1f - (-2f * x + 2f).pow(2) / 2f
        }
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerp(a: Vector3, b: Vector3, t: Float, result: Vector3): Vector3 {
        result.set(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
        return result
    }

    fun lerp(a: Vector3, b: Vector3, t: Float): Vector3 = lerp(a, b, t, Vector3())

    // Rodrigues rotation: rotate v around unit axis k by ang
    fun rotAround(v: Vector3, axis: Vector3, ang: Float, result: Vector3): Vector3 {
        val kx: Float; val ky: Float; val kz: Float
        val m = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
        if (m > 1e-6f) {
            kx = axis.x / m; ky = axis.y / m; kz = axis.z / m
        } else {
            kx = 0f; ky = 0f; kz = 1f
        }

        val c = cos(ang)
        val s = sin(ang)
        val dot = v.x * kx + v.y * ky + v.z * kz

        val cx = ky * v.z - kz * v.y
        val cy = kz * v.x - kx * v.z
        val cz = kx * v.y - ky * v.x

        val omc = 1f - c
        result.set(
            v.x * c + cx * s + kx * dot * omc,
            v.y * c + cy * s + ky * dot * omc,
            v.z * c + cz * s + kz * dot * omc
        )
        return result
    }

    fun rotAround(v: Vector3, axis: Vector3, ang: Float): Vector3 {
        return rotAround(v, axis, ang, Vector3())
    }

    class IKResult(
        val joint: Vector3 = Vector3(),
        val end: Vector3 = Vector3(),
        var requestedDistance: Float = 0f,
        var clampedDistance: Float = 0f,
        var clampAmount: Float = 0f,
        var angularClampAmount: Float = 0f,
        // The straight-limb fallback outcome of THIS solve, recorded at the branch that actually
        // took it. Private scratch; producers fold it into the carrier stamp.
        var straightIntentDropped: Boolean = false
    )

    /**
     * Minimum reachable end distance for a 2-bone chain: the distance with the middle joint at its
     * tightest allowed interior angle.
     */
    fun minReach(L1: Float, L2: Float, constraint: IKConstraint): Float {
        val minCos = cos(constraint.minimumFlexionAngle * DEG2RAD)
        return sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)
    }

    /** Maximum reachable end distance for a 2-bone chain (honours the extension cap). */
    fun maxReach(L1: Float, L2: Float, constraint: IKConstraint): Float =
        (L1 + L2) * constraint.effectiveExtensionRatio

    /**
     * Projects an authored IK [target] onto the *reachable annulus* `[minReach, maxReach]` centred
     * on [root], along the root->target direction, into [out]; a target inside the band is copied
     * unchanged. [margin] keeps the result strictly inside the band so the solver records **zero**
     * clamp — the reachability signal stays honest, fixing the authored target rather than muting
     * the validator.
     *
     * This is "reachable by construction": poses author the intended direction and the engine
     * guarantees a real limb can place the end-effector there. Allocation-free.
     */
    fun clampTargetToReach(
        root: Vector3,
        target: Vector3,
        L1: Float,
        L2: Float,
        constraint: IKConstraint,
        out: Vector3,
        margin: Float = 0.02f
    ): Vector3 {
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)
        val lo = minReach(L1, L2, constraint) * (1f + margin)
        val hi = maxReach(L1, L2, constraint) * (1f - margin)
        if (dMag < 1e-6f || dMag in lo..hi) {
            return out.set(target.x, target.y, target.z)
        }
        val clamped = dMag.coerceIn(lo, hi)
        val s = clamped / dMag
        return out.set(root.x + dx * s, root.y + dy * s, root.z + dz * s)
    }

    /** Analytical IK with strict biological clamps. */
    fun solveIK(
        root: Vector3,
        target: Vector3,
        L1: Float,
        L2: Float,
        pole: Vector3,
        constraint: IKConstraint,
        result: IKResult = IKResult(),
        contact: ContactConstraint? = null
    ): IKResult {
        // Per-solve reset: a reused scratch must not leak a previous straight limb's outcome into
        // this solve (bake sites reuse one IKResult per limb).
        result.straightIntentDropped = false
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)

        // Honour a true straight limb when the constraint opts into full extension.
        val maxDist = (L1 + L2) * constraint.effectiveExtensionRatio

        val minCos = cos(constraint.minimumFlexionAngle * DEG2RAD)
        val minDist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)

        val limits = constraint.angularLimits

        // 1) Distance band (reach limits).
        var dist = dMag.coerceIn(minDist, maxDist)
        val distanceClampAmount = when {
            dMag < minDist -> minDist - dMag
            dMag > maxDist -> dMag - maxDist
            else -> 0f
        }

        // 2) Angular band on the middle joint interior angle.
        //
        //    The clamp is computed from the *requested* angle (`dMag`), so a straight-limb request
        //    that the reach band pre-clamps to 98% is still honestly reported as a hyperextension
        //    past `maxFlexionDegrees`. The *placed* joint is solved at the capped angle instead, so
        //    the interior angle honours the limit while the distance stays inside the reach band:
        //    surface the violation, don't mute it.
        val denom = (2f * L1 * L2)
        val reqCosT = ((L1 * L1 + L2 * L2 - dMag * dMag) / denom).coerceIn(-1f, 1f)
        val reqTheta = acos(reqCosT) * RAD2DEG
        val cappedTheta = reqTheta.coerceIn(limits.minFlexionDegrees, limits.maxFlexionDegrees)
        val angularClampAmount = when {
            reqTheta < limits.minFlexionDegrees -> limits.minFlexionDegrees - reqTheta
            reqTheta > limits.maxFlexionDegrees -> reqTheta - limits.maxFlexionDegrees
            else -> 0f
        }

        // Place the middle joint at the capped interior angle, then keep the resulting distance
        // inside the reach band.
        dist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * cos(cappedTheta * DEG2RAD)).coerceIn(minDist, maxDist)

        result.requestedDistance = dMag
        result.clampAmount = max(distanceClampAmount, angularClampAmount)
        result.angularClampAmount = angularClampAmount

        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dx / dMag; dirY = dy / dMag; dirZ = dz / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }

        val endX = root.x + dirX * dist
        val endY = root.y + dirY * dist
        val endZ = root.z + dirZ * dist

        // 3) Contact awareness: if the clamped end penetrates the support plane, re-solve it *onto*
        //    the plane at the same reachable distance, toward the target's surface projection, so a
        //    planted hand/foot stays on the ground/bar instead of driving through it.
        if (contact != null) {
            val signed = (endX - contact.point.x) * contact.normal.x +
                (endY - contact.point.y) * contact.normal.y +
                (endZ - contact.point.z) * contact.normal.z
            if (signed < 0f) {
                resolveContactPlane(root, target, dist, pole, L1, L2, constraint, contact, result, straight = false)
                return result
            }
        }

        result.end.set(endX, endY, endZ)
        solveTriangleJoint(root, result.end, pole, L1, L2, result.joint)
        return result
    }

    /**
     * Middle joint for a two-bone chain given a *fixed* end: the standard triangle IK offset,
     * perpendicular to the root->end axis, sized by the pole at height `h` from the axis.
     */
    private fun solveTriangleJoint(
        root: Vector3,
        end: Vector3,
        pole: Vector3,
        L1: Float,
        L2: Float,
        outJoint: Vector3
    ) {
        val dxe = end.x - root.x
        val dye = end.y - root.y
        val dze = end.z - root.z
        val dMag = sqrt(dxe * dxe + dye * dye + dze * dze)
        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dxe / dMag; dirY = dye / dMag; dirZ = dze / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }

        val a = (dMag * dMag + L1 * L1 - L2 * L2) / (2f * dMag)
        val h = sqrt(max(L1 * L1 - a * a, 0f))

        val pDotDir = pole.x * dirX + pole.y * dirY + pole.z * dirZ
        var px = pole.x - dirX * pDotDir
        var py = pole.y - dirY * pDotDir
        var pz = pole.z - dirZ * pDotDir

        val pMag = sqrt(px * px + py * py + pz * pz)
        if (pMag < 1e-4f) {
            val wdDotDir = dirY
            px = 0f - dirX * wdDotDir
            py = 1f - dirY * wdDotDir
            pz = 0f - dirZ * wdDotDir
            val pMag2 = sqrt(px * px + py * py + pz * pz)
            if (pMag2 > 1e-6f) {
                px /= pMag2; py /= pMag2; pz /= pMag2
            } else {
                px = 0f; py = 0f; pz = 1f
            }
        } else {
            px /= pMag; py /= pMag; pz /= pMag
        }

        outJoint.set(
            root.x + dirX * a + px * h,
            root.y + dirY * a + py * h,
            root.z + dirZ * a + pz * h
        )
    }

    /**
     * Middle joint for a straight limb: exactly `L1` along the root->end direction, clamped so it
     * never overshoots the end.
     */
    private fun solveStraightMiddle(root: Vector3, end: Vector3, L1: Float, outJoint: Vector3) {
        val dxe = end.x - root.x
        val dye = end.y - root.y
        val dze = end.z - root.z
        val dMag = sqrt(dxe * dxe + dye * dye + dze * dze)
        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dxe / dMag; dirY = dye / dMag; dirZ = dze / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }
        val middleDist = minOf(L1, dMag)
        outJoint.set(
            root.x + dirX * middleDist,
            root.y + dirY * middleDist,
            root.z + dirZ * middleDist
        )
    }

    /**
     * Re-solves the end joint onto the [contact] plane at the reachable [dist] from [root], toward
     * the target's surface projection, then derives the middle joint (triangle or straight).
     * Sliding along the surface at the same radius keeps the end on its support without
     * penetration, at the closest reachable point to the authored target.
     */
    private fun resolveContactPlane(
        root: Vector3,
        target: Vector3,
        dist: Float,
        pole: Vector3,
        L1: Float,
        L2: Float,
        constraint: IKConstraint,
        contact: ContactConstraint,
        result: IKResult,
        straight: Boolean
    ) {
        // Normalize the contact normal so caller-supplied planes are robust.
        val nMag = sqrt(contact.normal.x * contact.normal.x + contact.normal.y * contact.normal.y + contact.normal.z * contact.normal.z)
        val nx = if (nMag > 1e-6f) contact.normal.x / nMag else 0f
        val ny = if (nMag > 1e-6f) contact.normal.y / nMag else 1f
        val nz = if (nMag > 1e-6f) contact.normal.z / nMag else 0f

        // Signed distance of the root from the plane, and its projection onto the plane.
        val rx = root.x - contact.point.x
        val ry = root.y - contact.point.y
        val rz = root.z - contact.point.z
        val drop = rx * nx + ry * ny + rz * nz
        val rpx = root.x - drop * nx
        val rpy = root.y - drop * ny
        val rpz = root.z - drop * nz

        // Projection of the authored target onto the plane.
        val tx = target.x - contact.point.x
        val ty = target.y - contact.point.y
        val tz = target.z - contact.point.z
        val tSigned = tx * nx + ty * ny + tz * nz
        val tpx = target.x - tSigned * nx
        val tpy = target.y - tSigned * ny
        val tpz = target.z - tSigned * nz

        // In-plane slide direction from the root projection toward the target projection.
        var hx = tpx - rpx; var hy = tpy - rpy; var hz = tpz - rpz
        val hMag = sqrt(hx * hx + hy * hy + hz * hz)
        // Slide distance along the surface so the end stays exactly `dist` from the root.
        val sPlane = sqrt(max(dist * dist - drop * drop, 0f))

        if (hMag > 1e-4f) {
            val inv = sPlane / hMag
            hx *= inv; hy *= inv; hz *= inv
        } else {
            // Target is directly above/below the root's plane projection: choose any in-plane axis.
            hx = 1f; hy = 0f; hz = 0f
            val dotn = hx * nx + hy * ny + hz * nz
            hx -= nx * dotn; hy -= ny * dotn; hz -= nz * dotn
            val hm = sqrt(hx * hx + hy * hy + hz * hz)
            if (hm > 1e-6f) {
                hx = hx / hm * sPlane; hy = hy / hm * sPlane; hz = hz / hm * sPlane
            } else {
                hx = 0f; hy = 0f; hz = 0f
            }
        }

        result.end.set(rpx + hx, rpy + hy, rpz + hz)
        if (straight) {
            solveStraightMiddle(root, result.end, L1, result.joint)
        } else {
            solveTriangleJoint(root, result.end, pole, L1, L2, result.joint)
        }
        val dex = result.end.x - root.x
        val dey = result.end.y - root.y
        val dez = result.end.z - root.z
        result.clampedDistance = sqrt(dex * dex + dey * dey + dez * dez)
    }

    /**
     * Analytical straight / rigid-segment limb solve: the middle and end joints are placed
     * collinear with `root -> target`, so the two-bone chain becomes a rigid segment aimed at the
     * target. The end is clamped into the same biological band [solveIK] uses, so an unreachable
     * target still yields a valid straight limb; a constraint may opt into full extension to reach
     * its true anatomical length instead of the 0.98 cap. The middle sits at exactly `L1`, keeping
     * the first bone exact; the second spans the clamped remainder.
     */
    fun solveStraightLimb(
        root: Vector3,
        target: Vector3,
        L1: Float,
        L2: Float,
        constraint: IKConstraint,
        result: IKResult = IKResult(),
        contact: ContactConstraint? = null
    ): IKResult {
        // Per-solve reset (see solveIK): the scratch reading always describes THIS solve.
        result.straightIntentDropped = false
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)

        // Honour a true straight limb when the constraint opts into full extension.
        val maxDist = (L1 + L2) * constraint.effectiveExtensionRatio
        val minCos = cos(constraint.minimumFlexionAngle * PI.toFloat() / 180f)
        val minDist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)

        val dist = dMag.coerceIn(minDist, maxDist)

        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dx / dMag; dirY = dy / dMag; dirZ = dz / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }

        result.requestedDistance = dMag
        result.clampedDistance = dist
        result.clampAmount = if (dMag < minDist) {
            minDist - dMag
        } else if (dMag > maxDist) {
            dMag - maxDist
        } else {
            0f
        }

        // A planted straight limb that would penetrate the support is re-solved onto the surface at
        // the same reachable distance (see [resolveContactPlane]).
        if (contact != null) {
            val signed = (root.x + dirX * dist - contact.point.x) * contact.normal.x +
                (root.y + dirY * dist - contact.point.y) * contact.normal.y +
                (root.z + dirZ * dist - contact.point.z) * contact.normal.z
            if (signed < 0f) {
                // The limb is slid onto the surface and its middle placed by the straight-collinear
                // helper, so the rigid-straight solve was not delivered: the fallback is honest iff
                // the collinear middle had to be pulled inside the end (`dist < L1`) or the requested
                // distance was unreachable. Geometry-observing flag only — no placement changes.
                if (dist < L1) result.straightIntentDropped = true
                resolveContactPlane(root, target, dist, Vector3(0f, 0f, 0f), L1, L2, constraint, contact, result, straight = true)
                return result
            }
        }

        // When the clamped reach is shorter than the upper bone (`dist < L1`) a straight limb is
        // geometrically impossible without collapsing the second bone to zero length: a plain
        // `middleDist = min(L1, dist)` writes `middle == end == target`, a degenerate zero-length
        // shin/forearm that only survived because the global ConstraintSolver later re-baked it via
        // triangle IK — and if no contact was registered, that solver never ran and the pose shipped
        // a zero-length segment. Fall back to a valid bent limb here (the same triangle solve) so
        // both bone lengths hold at bake time, with no hidden dependency on the solver. A zero pole
        // selects the stable world-down bend plane.
        if (dist < L1) {
            // The straight request degenerates below the proximal bone and this branch executes the
            // bent fallback: record the outcome at the branch that took it (geometry untouched).
            result.straightIntentDropped = true
            result.end.set(
                root.x + dirX * dist,
                root.y + dirY * dist,
                root.z + dirZ * dist
            )
            solveTriangleJoint(root, result.end, straightDegeneratePole, L1, L2, result.joint)
            return result
        }

        val middleDist = minOf(L1, dist)
        result.joint.set(
            root.x + dirX * middleDist,
            root.y + dirY * middleDist,
            root.z + dirZ * middleDist
        )
        result.end.set(
            root.x + dirX * dist,
            root.y + dirY * dist,
            root.z + dirZ * dist
        )

        return result
    }

    /**
     * Rotates a direction authored in a parent's LOCAL frame into world space with the parent's
     * [JointRotation], using the same axis-angle convention as the FK traversal, so a pole written
     * in the chest/pelvis frame follows the body exactly as it rotates.
     */
    fun toWorldDirection(localDir: Vector3, rotation: JointRotation, out: Vector3): Vector3 {
        return rotAround(localDir, rotation.axis, rotation.angle, out)
    }

    /**
     * Inverse of [toWorldDirection]: a world-space direction into the parent's LOCAL frame, used to
     * convert a legacy world-space pole at authoring time.
     */
    fun toLocalDirection(worldDir: Vector3, rotation: JointRotation, out: Vector3): Vector3 {
        return rotAround(worldDir, rotation.axis, -rotation.angle, out)
    }

    // Scapula degrees of freedom. Elevation/depression (transverse X) and
    // protraction/retraction (vertical Y) are genuine rotations, never a raw translation of the
    // shoulder. The pull family expresses scapular activation as an intensity in shared units
    // (depression 0..1, retraction 0..N); the constants below map that intensity into the general
    // human scapular range of motion. Named and shared — not per-exercise magic numbers.
    const val SCAPULA_DEPRESSION_TO_RAD = 0.0218f // ~1.25 deg per activation unit
    const val SCAPULA_RETRECTION_TO_RAD = 0.035f  // ~2.0 deg per activation unit

    // Scratch column buffers for composing the scapular rotation (no hot-path allocation).
    private val scapColAX = Vector3(); private val scapColAY = Vector3(); private val scapColAZ = Vector3()
    private val scapColBX = Vector3(); private val scapColBY = Vector3(); private val scapColBZ = Vector3()
    private val scapColRX = Vector3(); private val scapColRY = Vector3(); private val scapColRZ = Vector3()
    private val scapRotA = JointRotation(Vector3(1f, 0f, 0f), 0f)
    private val scapRotB = JointRotation(Vector3(0f, 1f, 0f), 0f)

    /**
     * Composes the scapula's local rotation from [depression]/[retraction] activation intensities,
     * which FK applies to the scapula and so to the shoulder (glenoid) position. `sideSign` is -1
     * for the left/active (-Z) girdle and +1 for the right/passive (+Z), so depression drops *both*
     * shoulders symmetrically — the pivot is mirrored across the mid-line.
     */
    fun buildScapularRotation(
        retraction: Float,
        depression: Float,
        sideSign: Float,
        out: JointRotation
    ): JointRotation {
        // R = Ry(retraction) * Rx(depression * sideSign): retraction rotates both blades
        // medially about the vertical axis; depression pitches the girdle down on both sides
        // via a mirrored transverse-axis rotation.
        val ax = depression * SCAPULA_DEPRESSION_TO_RAD * sideSign
        val ay = retraction * SCAPULA_RETRECTION_TO_RAD
        scapRotA.axis.set(1f, 0f, 0f); scapRotA.angle = ax
        scapRotB.axis.set(0f, 1f, 0f); scapRotB.angle = ay
        rotationToMatrix(scapRotA, scapColAX, scapColAY, scapColAZ)
        rotationToMatrix(scapRotB, scapColBX, scapColBY, scapColBZ)
        multiplyMatrices(scapColBX, scapColBY, scapColBZ, scapColAX, scapColAY, scapColAZ, scapColRX, scapColRY, scapColRZ)
        getRotationFromMatrix(scapColRX, scapColRY, scapColRZ, out)
        return out
    }

    // Clavicle degrees of freedom. The clavicle is the PROXIMAL member of the shoulder girdle
    // (CHEST -> CLAVICLE -> SCAPULA -> SHOULDER) and carries genuine SC/AC rotations: elevation
    // (transverse X), protraction (vertical Y) and axial rotation about its own long Z axis. Left
    // as a rigid pass-through, only the scapula was driven and overhead reaching under-drove
    // shoulder height, so this mirrors [buildScapularRotation] with shared-unit activation mapped
    // into the general human clavicular range. Named and shared — no per-exercise magic numbers.
    const val CLAVICLE_ELEVATION_TO_RAD = 0.5236f    // ~30 deg per elevation unit
    const val CLAVICLE_PROTRACTION_TO_RAD = 0.2618f // ~15 deg per protraction unit
    const val CLAVICLE_AXIAL_TO_RAD = 0.1745f       // ~10 deg per axial-rotation unit

    // Scratch column buffers for composing the clavicular rotation (no hot-path allocation).
    private val clavColAX = Vector3(); private val clavColAY = Vector3(); private val clavColAZ = Vector3()
    private val clavColBX = Vector3(); private val clavColBY = Vector3(); private val clavColBZ = Vector3()
    private val clavColCX = Vector3(); private val clavColCY = Vector3(); private val clavColCZ = Vector3()
    private val clavColRX = Vector3(); private val clavColRY = Vector3(); private val clavColRZ = Vector3()
    private val clavRotX = JointRotation(Vector3(1f, 0f, 0f), 0f)
    private val clavRotY = JointRotation(Vector3(0f, 1f, 0f), 0f)
    private val clavRotZ = JointRotation(Vector3(0f, 0f, 1f), 0f)

    /**
     * Composes the clavicle's local rotation from shared-unit activation intensities (the named
     * constants map them to radians). The clavicle sits *between* the chest and the scapula, so this
     * lifts and carries the whole shoulder on overhead reaches. `sideSign` is -1 for the left/active
     * (-Z) girdle and +1 for the right/passive (+Z), so elevation raises *both* shoulders
     * symmetrically. `R = Ry(protraction) · Rx(-elevation · sideSign) · Rz(axialRotation)` — the
     * negated elevation makes a positive value raise the shoulder, the mirror of scapular depression.
     */
    fun buildClavicularRotation(
        elevation: Float,
        protraction: Float,
        axialRotation: Float,
        sideSign: Float,
        out: JointRotation
    ): JointRotation {
        // R = Ry(protraction) * Rx(elevation * sideSign) * Rz(axialRotation)
        // Elevation raises the shoulder (symmetric via mirrored transverse-axis rotation);
        // protraction carries the clavicle forward/back about the vertical; axial rotation
        // twists the clavicle about its own long axis (the AC-joint component).
        val ax = -elevation * CLAVICLE_ELEVATION_TO_RAD * sideSign
        val ay = protraction * CLAVICLE_PROTRACTION_TO_RAD
        val az = axialRotation * CLAVICLE_AXIAL_TO_RAD
        clavRotX.axis.set(1f, 0f, 0f); clavRotX.angle = ax
        clavRotY.axis.set(0f, 1f, 0f); clavRotY.angle = ay
        clavRotZ.axis.set(0f, 0f, 1f); clavRotZ.angle = az
        rotationToMatrix(clavRotX, clavColAX, clavColAY, clavColAZ)
        rotationToMatrix(clavRotY, clavColBX, clavColBY, clavColBZ)
        rotationToMatrix(clavRotZ, clavColCX, clavColCY, clavColCZ)
        multiplyMatrices(clavColBX, clavColBY, clavColBZ, clavColAX, clavColAY, clavColAZ, clavColRX, clavColRY, clavColRZ)
        multiplyMatrices(clavColRX, clavColRY, clavColRZ, clavColCX, clavColCY, clavColCZ, clavColAX, clavColAY, clavColAZ)
        getRotationFromMatrix(clavColAX, clavColAY, clavColAZ, out)
        return out
    }

    // General axis-angle composition: `out = a ∘ b` (apply `b` first, then `a`) by multiplying the
    // rotation matrices with the existing utilities — no duplicated rotation math, no quaternion
    // type. This is the primitive that lets a joint carry more than one DOF in one exact rotation.
    private val composeAX = Vector3(); private val composeAY = Vector3(); private val composeAZ = Vector3()
    private val composeBX = Vector3(); private val composeBY = Vector3(); private val composeBZ = Vector3()
    private val composeRX = Vector3(); private val composeRY = Vector3(); private val composeRZ = Vector3()

    fun composeRotations(a: JointRotation, b: JointRotation, out: JointRotation): JointRotation {
        rotationToMatrix(a, composeAX, composeAY, composeAZ)
        rotationToMatrix(b, composeBX, composeBY, composeBZ)
        multiplyMatrices(composeAX, composeAY, composeAZ, composeBX, composeBY, composeBZ, composeRX, composeRY, composeRZ)
        getRotationFromMatrix(composeRX, composeRY, composeRZ, out)
        return out
    }

    // Wrist and ankle: 2-DOF combined articulation. Neither is a single-axis hinge — the wrist
    // combines flexion/extension with radial/ulnar deviation, the ankle dorsi/plantar-flexion with
    // inversion/eversion. A single axis-angle rotation can express one axis but never combine two:
    // the second DOF silently overwrites the first. These helpers compose both into one exact
    // rotation (via [composeRotations]) that FK propagates and the finalizer honours.
    //
    // Axes follow the shared skeleton frame: flexion/dorsiflexion about the mediolateral Z axis,
    // wrist deviation about the antero-posterior Y axis, ankle inversion/eversion about the long X
    // axis. Angles are radians — the caller supplies anatomical values.
    private val wristRotFlex = JointRotation(Vector3(0f, 0f, 1f), 0f)
    private val wristRotDev = JointRotation(Vector3(0f, 1f, 0f), 0f)
    private val ankleRotFlex = JointRotation(Vector3(0f, 0f, 1f), 0f)
    private val ankleRotInv = JointRotation(Vector3(1f, 0f, 0f), 0f)

    /**
     * The wrist's 2-DOF rotation from [flexion] (about mediolateral Z) and [deviation] (about
     * antero-posterior Y): `R = Rz(flexion) · Ry(deviation)`, so neither axis drops the other.
     */
    fun buildWristRotation(flexion: Float, deviation: Float, out: JointRotation): JointRotation {
        wristRotFlex.axis.set(0f, 0f, 1f); wristRotFlex.angle = flexion
        wristRotDev.axis.set(0f, 1f, 0f); wristRotDev.angle = deviation
        return composeRotations(wristRotFlex, wristRotDev, out)
    }

    /**
     * The ankle's 2-DOF rotation from [dorsiflexion] (about mediolateral Z) and [inversion] (about
     * the long X axis): `R = Rz(dorsiflexion) · Rx(inversion)`, so neither axis drops the other.
     */
    fun buildAnkleRotation(dorsiflexion: Float, inversion: Float, out: JointRotation): JointRotation {
        ankleRotFlex.axis.set(0f, 0f, 1f); ankleRotFlex.angle = dorsiflexion
        ankleRotInv.axis.set(1f, 0f, 0f); ankleRotInv.angle = inversion
        return composeRotations(ankleRotFlex, ankleRotInv, out)
    }

    // Hip: 3-DOF ball-and-socket authoring. Flexion/extension in the sagittal plane about the
    // mediolateral Z axis, abduction/adduction in the frontal plane about the antero-posterior Y
    // axis, internal/external rotation about the femur's long X axis — the last kept separate from
    // the IK pole, which only selects the knee-bend plane. [sideSign] mirrors abduction and axial
    // rotation across the mid-line (-1 left, +1 right) so a positive value spreads both legs
    // symmetrically. The ROM vocabulary lives in [HipRomLimits]; ROM enforcement stays with the
    // validator's `HIP_ROM_LIMIT` rule.
    // `R = Rz(flexion) · Ry(abduction · sideSign) · Rx(rotation · sideSign)`.
    private val hipRotFlex = JointRotation(Vector3(0f, 0f, 1f), 0f)
    private val hipRotAbd = JointRotation(Vector3(0f, 1f, 0f), 0f)
    private val hipRotAxial = JointRotation(Vector3(1f, 0f, 0f), 0f)

    fun buildHipRotation(
        flexion: Float,
        abduction: Float,
        rotation: Float,
        sideSign: Float,
        out: JointRotation
    ): JointRotation {
        hipRotFlex.axis.set(0f, 0f, 1f); hipRotFlex.angle = flexion
        hipRotAbd.axis.set(0f, 1f, 0f); hipRotAbd.angle = abduction * sideSign
        hipRotAxial.axis.set(1f, 0f, 0f); hipRotAxial.angle = rotation * sideSign
        // R = (Rz(flexion) · Ry(abduction · sideSign)) · Rx(rotation · sideSign).
        composeRotations(hipRotFlex, hipRotAbd, out)
        composeRotations(out, hipRotAxial, out)
        return out
    }

    // Scratch for the hip-ROM stamp computation (no hot-path allocation).
    private val hipScratchV1 = Vector3()
    private val hipScratchV2 = Vector3()
    private val hipScratchV3 = Vector3()

    /**
     * Produces a [HipRomStamp] for one hip from the solved skeleton. The engine writes the stamp
     * once so the validator only *reads* it — no `toLocalDirection` / `angleBetweenDegrees` /
     * `atan2` re-derivation on the validation path. The four angles are byte-identical to the
     * inline math they replaced, so the rule's verdicts are unchanged.
     *
     * [pelvisRotation] is the pelvis world rotation, [hipPos]/[kneePos] the hip/knee world
     * positions, [abductionSign] mirrors abduction/axial so both legs report positively outward,
     * and [hipRotation] is the hip's authored local rotation whose swing-twist yields the axial
     * component.
     */
    fun computeHipRomStamp(
        pelvisRotation: JointRotation,
        hipPos: Vector3,
        kneePos: Vector3,
        abductionSign: Float,
        hipRotation: JointRotation,
        out: HipRomStamp
    ): HipRomStamp {
        hipScratchV1.set(kneePos.x - hipPos.x, kneePos.y - hipPos.y, kneePos.z - hipPos.z)
        if (hipScratchV1.mag() < 1e-5f) {
            return HipRomStamp(0f, 0f, 0f, 0f)
        }
        // Femur direction expressed in the pelvis' local frame.
        toLocalDirection(hipScratchV1, pelvisRotation, hipScratchV2)
        val fMag = hipScratchV2.mag()
        if (fMag < 1e-5f) return HipRomStamp(0f, 0f, 0f, 0f)
        val fx = hipScratchV2.x / fMag
        val fy = hipScratchV2.y / fMag
        val fz = hipScratchV2.z / fMag

        // (1) Total excursion from the neutral down direction (0,-1,0).
        val excursion = angleBetweenDegrees(hipScratchV2, hipScratchV3.set(0f, -1f, 0f))
        // (2) Sagittal elevation (flexion/extension): sin(sagittal) = fx.
        val sagittal = Math.toDegrees(kotlin.math.asin(fx.coerceIn(-1f, 1f).toDouble())).toFloat()
        // (3) Frontal elevation (abduction/adduction): sin(frontal) = fz * abductionSign.
        val frontal = Math.toDegrees(kotlin.math.asin((fz * abductionSign).coerceIn(-1f, 1f).toDouble())).toFloat()
        // (4) Axial femoral twist: 2·atan2(qx, qw) of the hip's authored rotation.
        val axis = hipRotation.axis
        val axisMag = kotlin.math.sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
        val axial = if (axisMag < 1e-6f) {
            0f
        } else {
            val half = hipRotation.angle * 0.5f
            val s = kotlin.math.sin(half)
            val qw = kotlin.math.cos(half)
            val qx = axis.x / axisMag * s
            Math.toDegrees((2f * kotlin.math.atan2(qx, qw)).toDouble()).toFloat()
        }
        // Mirror the axial twist across the body mid-line so internal/external stay consistent per side.
        val axialMirrored = axial * abductionSign
        return HipRomStamp(excursion, sagittal, frontal, axialMirrored)
    }

    /**
     * Angle in degrees between two directions. Used by the angular joint-limit validator and for
     * the deviation of a proximal bone from its neutral direction (the root-joint cone in
     * [AngularJointLimits]).
     */
    fun angleBetweenDegrees(a: Vector3, b: Vector3): Float {
        val ma = a.mag()
        val mb = b.mag()
        if (ma < 1e-6f || mb < 1e-6f) return 0f
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z) / (ma * mb)
        return acos(dot.coerceIn(-1f, 1f)) * RAD2DEG
    }

    /**
     * Default world-space IK pole, derived by the engine when a pose omits one.
     *
     * The bend plane is anchored to world-up projected perpendicular to the `root -> target` aim, so
     * elbows and knees bend in the body's natural sagittal/frontal plane however the parent frame is
     * rotated — the solver receives a world pole, never a parent-frame one. A (near) vertical aim
     * falls back to world-forward (+Z), then to world-X, so the pole is always a well-defined unit
     * vector.
     */
    fun deriveDefaultPole(root: Vector3, target: Vector3, out: Vector3): Vector3 {
        var ax = target.x - root.x
        var ay = target.y - root.y
        var az = target.z - root.z
        val aMag = sqrt(ax * ax + ay * ay + az * az)
        if (aMag > 1e-6f) {
            ax /= aMag; ay /= aMag; az /= aMag
        } else {
            ax = 1f; ay = 0f; az = 0f
        }
        // Anchor on world-up, made perpendicular to the aim.
        var px = 0f; var py = 1f; var pz = 0f
        var dot = px * ax + py * ay + pz * az
        px -= ax * dot; py -= ay * dot; pz -= az * dot
        var m = sqrt(px * px + py * py + pz * pz)
        if (m < 1e-4f) {
            // Aim is vertical: anchor on world-forward instead.
            px = 0f; py = 0f; pz = 1f
            dot = px * ax + py * ay + pz * az
            px -= ax * dot; py -= ay * dot; pz -= az * dot
            m = sqrt(px * px + py * py + pz * pz)
            if (m < 1e-4f) {
                px = 1f; py = 0f; pz = 0f
            } else {
                px /= m; py /= m; pz /= m
            }
        } else {
            px /= m; py /= m; pz /= m
        }
        return out.set(px, py, pz)
    }

    /**
     * True iff the solved chain preserves both bone lengths to within [eps] — the IK solve must be
     * exact. Folded into [SkeletonPose.boneLengthsVerified].
     */
    fun bonesExact(root: Vector3, mid: Vector3, end: Vector3, L1: Float, L2: Float, eps: Float = 1e-3f): Boolean {
        val d1x = mid.x - root.x; val d1y = mid.y - root.y; val d1z = mid.z - root.z
        val d1 = sqrt(d1x * d1x + d1y * d1y + d1z * d1z)
        val d2x = end.x - mid.x; val d2y = end.y - mid.y; val d2z = end.z - mid.z
        val d2 = sqrt(d2x * d2x + d2y * d2y + d2z * d2z)
        return abs(d1 - L1) <= eps && abs(d2 - L2) <= eps
    }

    // Rotation matrix utilities: column-vector form, for zero-allocation FK propagation.

    fun rotationToMatrix(rot: JointRotation, colX: Vector3, colY: Vector3, colZ: Vector3) {
        val c = cos(rot.angle)
        val s = sin(rot.angle)
        val omc = 1f - c
        val x = rot.axis.x
        val y = rot.axis.y
        val z = rot.axis.z

        colX.set(
            c + x * x * omc,
            y * x * omc + z * s,
            z * x * omc - y * s
        )
        colY.set(
            x * y * omc - z * s,
            c + y * y * omc,
            z * y * omc + x * s
        )
        colZ.set(
            x * z * omc + y * s,
            y * z * omc - x * s,
            c + z * z * omc
        )
    }

    fun getRotationFromMatrix(colX: Vector3, colY: Vector3, colZ: Vector3, result: JointRotation) {
        val tr = colX.x + colY.y + colZ.z
        val angle = acos(((tr - 1f) / 2f).coerceIn(-1f, 1f))
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val x = colY.z - colZ.y
        val y = colZ.x - colX.z
        val z = colX.y - colY.x
        val mag = sqrt(x * x + y * y + z * z)
        if (mag < 1e-4f) {
            result.set(0f, 0f, 1f, angle)
        } else {
            result.set(x / mag, y / mag, z / mag, angle)
        }
    }

    fun matrixMultiplyVector(pX: Vector3, pY: Vector3, pZ: Vector3, v: Vector3, result: Vector3) {
        val rx = pX.x * v.x + pY.x * v.y + pZ.x * v.z
        val ry = pX.y * v.x + pY.y * v.y + pZ.y * v.z
        val rz = pX.z * v.x + pY.z * v.y + pZ.z * v.z
        result.set(rx, ry, rz)
    }

    fun multiplyMatrices(
        pX: Vector3, pY: Vector3, pZ: Vector3,
        lX: Vector3, lY: Vector3, lZ: Vector3,
        rX: Vector3, rY: Vector3, rZ: Vector3
    ) {
        matrixMultiplyVector(pX, pY, pZ, lX, rX)
        matrixMultiplyVector(pX, pY, pZ, lY, rY)
        matrixMultiplyVector(pX, pY, pZ, lZ, rZ)
    }

    fun transposeMultiply(
        pX: Vector3, pY: Vector3, pZ: Vector3,
        wX: Vector3, wY: Vector3, wZ: Vector3,
        rX: Vector3, rY: Vector3, rZ: Vector3
    ) {
        val rxX = pX.dot(wX)
        val rxY = pY.dot(wX)
        val rxZ = pZ.dot(wX)

        val ryX = pX.dot(wY)
        val ryY = pY.dot(wY)
        val ryZ = pZ.dot(wY)

        val rzX = pX.dot(wZ)
        val rzY = pY.dot(wZ)
        val rzZ = pZ.dot(wZ)

        rX.set(rxX, rxY, rxZ)
        rY.set(ryX, ryY, ryZ)
        rZ.set(rzX, rzY, rzZ)
    }

    fun getRotationToAlign(from: Vector3, to: Vector3, tempScratch: Vector3, result: JointRotation) {
        val fromMag = from.mag()
        val toMag = to.mag()
        if (fromMag < 1e-4f || toMag < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val dot = (from.dot(to) / (fromMag * toMag)).coerceIn(-1f, 1f)
        val angle = acos(dot)
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        if (angle > PI.toFloat() - 1e-4f) {
            val perp = if (abs(from.x) < 0.9f) Vector3(1f, 0f, 0f) else Vector3(0f, 1f, 0f)
            val axis = from.cross(perp, tempScratch).normalize()
            result.set(axis, angle)
            return
        }
        val axis = from.cross(to, tempScratch).normalize()
        result.set(axis, angle)
    }

    fun getRotationToAlign(to: Vector3, result: JointRotation) {
        val toMag = to.mag()
        if (toMag < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val dx = to.x / toMag
        val dy = to.y / toMag
        val dz = to.z / toMag

        val angle = acos(dx.coerceIn(-1f, 1f))
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val ax = 0f
        val ay = -dz
        val az = dy
        val amag = sqrt(ay * ay + az * az)
        if (amag < 1e-4f) {
            result.set(0f, 0f, 1f, angle)
        } else {
            result.set(ax, ay / amag, az / amag, angle)
        }
    }
}
