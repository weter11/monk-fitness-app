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
 * General, shared angular limits for a two-bone limb chain, expressed in degrees.
 *
 * These describe anatomical *angular* ranges rather than the reach-distance band, so the
 * engine can reject orientations the distance clamp could not (e.g. an over-folded knee or a
 * hyperextended elbow) and so validation can mirror them. The values are named and shared —
 * never per-exercise magic numbers.
 *
 * - `minFlexionDegrees` / `maxFlexionDegrees`: allowed interior angle at the middle joint
 *   (elbow/knee). `minFlexionDegrees` corresponds to the historical most-folded flexion;
 *   `maxFlexionDegrees` caps hyperextension (180° == perfectly straight limb).
 * - `maxRootDeviationDegrees`: allowed deviation of the proximal bone (root -> middle) from
 *   its anatomical neutral direction. A generous general bound; the solver clamps this only
 *   when a neutral direction is supplied by the caller.
 */
data class AngularJointLimits(
    val minFlexionDegrees: Float,
    val maxFlexionDegrees: Float,
    val maxRootDeviationDegrees: Float
) {
    companion object {
        // Named, shared bounds (no magic numbers). These are general human-range caps, not
        // per-exercise values.
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
 * Named, shared anatomical range-of-motion (ROM) limits for the hip — a genuine 3-DOF
 * ball-and-socket at the acetabulum (UNI-3). Unlike the knee/elbow middle-joint band, the hip
 * was previously unbounded (only a shared 30° knee-flexion floor limited the chain), so a pose
 * could flex / abduct / rotate the hip beyond human ROM and still validate as clean. These
 * named degrees are the single source of truth read by both the validator (`HIP_ROM_LIMIT`
 * rule) and (optionally) the constraint solver's femur-direction clamp. No per-exercise magic
 * numbers.
 *
 * Flexion / extension are measured about the sagittal (Z) axis from the neutral down direction;
 * abduction / adduction about the lateral (X) axis; internal / external rotation about the
 * femur's long axis. Generous human-range caps: a full split (~90° abduction) and a deep squat
 * (~120° flexion) pass, while a femur swung "through the torso" (~180°) is caught.
 */
data class HipRomLimits(
    val maxFlexionDegrees: Float,
    val maxExtensionDegrees: Float,
    val maxAbductionDegrees: Float,
    val maxAdductionDegrees: Float,
    val maxInternalRotationDegrees: Float,
    val maxExternalRotationDegrees: Float,
    /**
     * Maximum total excursion of the femur from its neutral (straight-down) direction. A single,
     * axis-label-agnostic cap that reliably catches an over-range hip (e.g. a femur swung "through
     * the torso", ~180°) without false-positives on valid extreme-but-anatomical poses. The
     * per-axis fields above remain the shared vocabulary for finer, future per-plane checks; the
     * validator's over-range detector uses this total-excursion bound.
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

data class IKConstraint(
    val minimumFlexionAngle: Float,
    val maximumExtensionRatio: Float,
    val angularLimits: AngularJointLimits = AngularJointLimits.ArmAngularLimits,
    /**
     * PR-11 opt-in. When `false` (the default) a limb is capped at
     * [maximumExtensionRatio]·(L1+L2) — the 0.98 safety band that keeps dynamic motion from
     * ever snapping to a locked-out joint. When `true`, the effective cap becomes the true
     * anatomical length (ratio 1.0), so a deliberately rigid reference limb (Dead Hang arms,
     * Pike Sit / Middle Split legs, …) can be perfectly straight instead of a few percent bent.
     * This is a policy flag only; it never changes bent-limb behaviour.
     */
    val allowFullExtension: Boolean = false
) {
    /**
     * The extension ratio the solver should actually honour: 1.0 when full extension is opted
     * into, otherwise the default safety cap. Kept here so both [SkeletonMath.solveIK] and
     * [SkeletonMath.solveStraightLimb] read a single source of truth.
     */
    val effectiveExtensionRatio: Float
        get() = if (allowFullExtension) 1f else maximumExtensionRatio

    /**
     * Returns a copy of this constraint with full extension enabled (allocation-free when the
     * flag is already set). Callers that want a truly straight reference limb opt in via this.
     */
    fun fullyExtended(): IKConstraint = if (allowFullExtension) this else copy(allowFullExtension = true)

    companion object {
        val ArmConstraint = IKConstraint(30f, 0.98f, AngularJointLimits.ArmAngularLimits)
        val LegConstraint = IKConstraint(30f, 0.98f, AngularJointLimits.LegAngularLimits)
    }
}

/**
 * A fixed support contact the end-effector must respect. When an IK solve would
 * drive a planted hand/foot through the support surface, the solver clamps the
 * end joint *onto* the [normal]/[point] plane (projecting the target onto the
 * surface) instead of along the free direction. This keeps a contact on the
 * ground / bar / prop and prevents penetration, while still recording the
 * reachability clamp.
 *
 * The plane is general data supplied by the caller (a ground plane, a bar-top
 * plane, a prop surface) — never a single-pose magic constant.
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
    // Static scratch for transforming a frame-relative pole into world space inside solveIK.
    private val poleWorldScratch = Vector3()

    // Scratch for deriving the default world pole (never allocates per solve).
    private val defaultPoleAim = Vector3()

    // Zero pole reused by the UNI-9 degenerate-straight-limb fallback: a zero-magnitude pole makes
    // [solveTriangleJoint] pick its stable world-down bend plane (never allocates per solve).
    private val straightDegeneratePole = Vector3(0f, 0f, 0f)

    private const val DEG2RAD = 3.1415927f / 180f
    private const val RAD2DEG = 180f / 3.1415927f

    class NearStraightLimbResult(var x: Float = 0f, var y: Float = 0f, var d: Float = 0f)

    /**
     * Computes the perpendicular (kX, kY)-style local offset for a two-bone limb
     * that should look nearly straight, driven by a target flexion angle rather
     * than an arbitrary linear-extension ratio (which does not correspond
     * linearly to visual straightness near full extension).
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

    /**
     * Standard Ease-In-Out Quintic
     */
    fun easeIO(x: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        return cx * cx * cx * (cx * (cx * 6 - 15) + 10)
    }

    /**
     * Reference Implementation Ease-In-Out (Quadric)
     */
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

    // Hard-locked 2-bone IK solver
    class IKResult(
        val joint: Vector3 = Vector3(),
        val end: Vector3 = Vector3(),
        var requestedDistance: Float = 0f,
        var clampedDistance: Float = 0f,
        var clampAmount: Float = 0f,
        var angularClampAmount: Float = 0f
    )

    /**
     * Analytical IK with strict Biological Clamps
     */
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
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)

        // PR-11: honour a true straight limb when the constraint opts into full extension.
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
        //    The angular clamp records whether the *requested* pose (what the author actually
        //    asked for, via `dMag`) violates the joint's flexion limits — e.g. a fully extended
        //    limb (180 deg interior angle) is a hyperextension past `maxFlexionDegrees`. The
        //    clamp is computed from the requested angle so a straight-limb request that the reach
        //    band pre-clamps to 98% reach is still honestly reported as a hyperextension.
        //
        //    The *placed* joint is solved at the capped angle (not the reach-band-clamped
        //    distance), so the final interior angle always honours the angular limit while the
        //    resulting distance is still coerced inside the reach band. Diagnostic-instrument
        //    rule: surface the violation, don't mute it.
        val denom = (2f * L1 * L2)
        val reqCosT = ((L1 * L1 + L2 * L2 - dMag * dMag) / denom).coerceIn(-1f, 1f)
        val reqTheta = acos(reqCosT) * RAD2DEG
        val cappedTheta = reqTheta.coerceIn(limits.minFlexionDegrees, limits.maxFlexionDegrees)
        val angularClampAmount = when {
            reqTheta < limits.minFlexionDegrees -> limits.minFlexionDegrees - reqTheta
            reqTheta > limits.maxFlexionDegrees -> reqTheta - limits.maxFlexionDegrees
            else -> 0f
        }

        // Geometry solve: place the middle joint at the capped interior angle, then keep the
        // resulting distance inside the reach band.
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

        // 3) Contact awareness: if the (clamped) end penetrates the support plane, re-solve the
        //    end *onto* the plane at the same reachable distance, directed toward the target's
        //    surface projection. This keeps a planted hand/foot on the ground/bar instead of
        //    driving it through, while the recorded clamp still surfaces unreachability (PR-10).
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
     * Middle-joint position for a two-bone chain given a *fixed* end point: the standard
     * triangle IK offset (perpendicular to the root→end axis, sized by the pole, at height
     * `h` from the axis). Allocation-free: writes [outJoint].
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
     * Middle-joint position for a straight limb given a fixed end point: the middle sits at
     * exactly `L1` along the root→end direction (clamped so it never overshoots the end).
     * Allocation-free: writes [outJoint].
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
     * Re-solves the end joint onto the [contact] plane at the reachable [dist] from [root],
     * directed toward the target's surface projection, then derives the middle joint (triangle
     * or straight). Sliding the contact along the surface at the same radius keeps the end on
     * the support surface (no penetration) at the closest reachable point to the authored
     * target. Allocation-free: mutates [result].
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
     * Analytical straight / rigid-segment limb solve. Places the middle and end joints
     * collinear with `root -> target`, so the two-bone chain becomes a rigid straight
     * segment aimed at the target. The end is clamped into `[minDist, maxDist]` — the same
     * biological band [solveIK] uses — so an unreachable target still yields a valid straight
     * limb (respecting [IKConstraint.effectiveExtensionRatio]; PR-11 lets a constraint opt into
     * full extension so a limb can reach its true anatomical length instead of the 0.98 safety
     * cap, giving a genuinely straight reference). The middle sits at exactly `L1` along the aim
     * direction, keeping the first bone length exact; the second bone spans the (clamped) remainder.
     *
     * Allocation-free: writes into [result]; reuses no scratch beyond scalar locals.
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
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)

        // PR-11: honour a true straight limb when the constraint opts into full extension.
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

        // Contact awareness: a planted straight limb that would penetrate the support surface is
        // re-solved onto the surface at the same reachable distance (see [resolveContactPlane]).
        if (contact != null) {
            val signed = (root.x + dirX * dist - contact.point.x) * contact.normal.x +
                (root.y + dirY * dist - contact.point.y) * contact.normal.y +
                (root.z + dirZ * dist - contact.point.z) * contact.normal.z
            if (signed < 0f) {
                resolveContactPlane(root, target, dist, Vector3(0f, 0f, 0f), L1, L2, constraint, contact, result, straight = true)
                return result
            }
        }

        // Middle at exactly L1 along the aim direction (keeps the upper bone length exact);
        // never let it overshoot the clamped end.
        //
        // UNI-9: when the (clamped) reach is shorter than the upper bone (`dist < L1`) a straight
        // limb is geometrically impossible without collapsing the second bone to zero length —
        // the old `middleDist = min(L1, dist)` wrote `middle == end == target`, a degenerate
        // zero-length shin/forearm that only survived because the global ConstraintSolver later
        // re-baked it via triangle IK. If that solver was skipped (no contact registered) the pose
        // shipped a zero-length segment and failed BONE_LENGTH. Fall back to a valid bent limb here
        // (the same triangle solve the ConstraintSolver would apply) so both bone lengths are
        // preserved at bake time, removing the hidden dependency on the solver. A zero pole selects
        // the solver's stable world-down bend plane.
        if (dist < L1) {
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
     * Rotates a direction authored in a parent's LOCAL frame into world space using the
     * parent's current [JointRotation]. Reuses [rotAround] (the same axis-angle convention the
     * Forward-Kinematics traversal uses) so a pole written in the chest/pelvis frame follows
     * the body exactly as it rotates. Allocation-free: writes into [out].
     */
    fun toWorldDirection(localDir: Vector3, rotation: JointRotation, out: Vector3): Vector3 {
        return rotAround(localDir, rotation.axis, rotation.angle, out)
    }

    /**
     * Inverse of [toWorldDirection]: rotates a world-space direction into the parent's LOCAL
     * frame. Used to convert a legacy world-space pole into the local frame at authoring time.
     * Allocation-free: writes into [out].
     */
    fun toLocalDirection(worldDir: Vector3, rotation: JointRotation, out: Vector3): Vector3 {
        return rotAround(worldDir, rotation.axis, -rotation.angle, out)
    }

    // --- Shoulder-girdle (scapula) degrees of freedom -----------------------------
    //
    // The scapula is a real joint: elevation/depression (about the transverse X axis) and
    // protraction/retraction (about the vertical Y axis) are genuine rotations, never a raw
    // translation of the shoulder (BIOMECHANICS.md §4/§10). The pull-family expresses scapular
    // activation as an intensity in shared, named units (depression 0..1, retraction 0..N);
    // the constants below map that intensity to a rotation within the general human scapular
    // range of motion. They are named and shared — not per-exercise magic numbers.
    const val SCAPULA_DEPRESSION_TO_RAD = 0.0218f // ~1.25 deg per activation unit
    const val SCAPULA_RETRECTION_TO_RAD = 0.035f  // ~2.0 deg per activation unit

    // Scratch column buffers for composing the scapular rotation (no hot-path allocation).
    private val scapColAX = Vector3(); private val scapColAY = Vector3(); private val scapColAZ = Vector3()
    private val scapColBX = Vector3(); private val scapColBY = Vector3(); private val scapColBZ = Vector3()
    private val scapColRX = Vector3(); private val scapColRY = Vector3(); private val scapColRZ = Vector3()
    private val scapRotA = JointRotation(Vector3(1f, 0f, 0f), 0f)
    private val scapRotB = JointRotation(Vector3(0f, 1f, 0f), 0f)

    /**
     * Composes the scapula's local rotation from elevation/depression and protraction/retraction
     * activation. `depression`/`retraction` are the pull-family activation intensities (shared
     * units); the result is a real [JointRotation] the FK traversal applies to the scapula, which
     * in turn derives the shoulder (glenoid) position. `sideSign` is -1 for the left/active (-Z)
     * girdle and +1 for the right/passive (+Z) girdle so that depression drops *both* shoulders
     * symmetrically (the depression pivot is mirrored across the body's mid-line).
     * Allocation-free: writes into [out].
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

    // --- Shoulder-girdle (clavicle) degrees of freedom -----------------------------
    //
    // The clavicle is the PROXIMAL member of the shoulder girdle (CHEST -> CLAVICLE ->
    // SCAPULA -> SHOULDER). It carries genuine rotations at the SC (sternoclavicular) and
    // AC (acromioclavicular) joints: elevation/depression (about the transverse X axis),
    // protraction/retraction (about the vertical Y axis) and axial rotation about its own
    // long (sagittal Z) axis. Previously the clavicle was a rigid pass-through (UNI-7):
    // only the scapula was driven, so overhead reaching under-drove shoulder height. This
    // helper gives the clavicle a real, named-ROM DOF mirroring [buildScapularRotation];
    // activation is expressed in shared units and the constants map it into the general
    // human clavicular range of motion. Named and shared — not per-exercise magic numbers.
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
     * Composes the clavicle's local rotation from elevation/depression, protraction/retraction
     * and axial-rotation activation. `elevation`/`protraction`/`axialRotation` are shared-unit
     * activation intensities (named ROM constants below map them to radians); the result is a
     * real [JointRotation] the FK traversal applies to the clavicle, which sits *between* the
     * chest and the scapula and therefore lifts and carries the whole shoulder (glenoid) on
     * overhead reaches. `sideSign` is -1 for the left/active (-Z) girdle and +1 for the
     * right/passive (+Z) girdle so that elevation raises *both* shoulders symmetrically (the
     * elevation pivot is mirrored across the body's mid-line). Composition:
     * `R = Ry(protraction) · Rx(-elevation · sideSign) · Rz(axialRotation)`
     * (the negated elevation term makes a positive `elevation` raise the shoulder, the mirror
     * of scapular depression, which lowers it).
     * Allocation-free: writes into [out]. Mirrors [buildScapularRotation].
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

    // --- General axis-angle composition -------------------------------------------
    //
    // Composes two [JointRotation]s into a single equivalent rotation `out = a ∘ b`
    // (apply `b` first, then `a`) by multiplying their rotation matrices with the existing
    // matrix utilities — no duplicated rotation math, no quaternion type. This is the shared
    // primitive that lets a joint carry more than one degree of freedom in one exact rotation
    // (e.g. a 2-DOF wrist/ankle, UNI-8). Allocation-free: writes into [out].
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

    // --- Wrist and ankle: 2-DOF combined articulation (UNI-8) ---------------------
    //
    // The real wrist and ankle are not single-axis hinges: the wrist combines flexion/extension
    // with radial/ulnar deviation, and the ankle combines dorsi/plantar-flexion with
    // inversion/eversion. Previously the hand/foot completion took a single axis-angle
    // [JointRotation], so an author could express one of those axes but never *combine* two
    // (dorsiflexion **and** inversion, or wrist flexion **and** deviation) — the second DOF
    // silently overwrote the first. These helpers compose the two anatomical DOFs into one exact
    // rotation (via [composeRotations]) that FK propagates and the finalizer honours through the
    // existing single-rotation completion path, mirroring how [buildScapularRotation] /
    // [buildClavicularRotation] gave the shoulder girdle real DOFs.
    //
    // Axes follow the shared skeleton frame (sagittal motion about Z, the convention every pose
    // uses for pitch/lean): flexion/dorsiflexion about the mediolateral Z axis; wrist deviation
    // about the antero-posterior Y axis; ankle inversion/eversion about the long X axis. Angles
    // are radians — the caller supplies anatomical values, so there are no per-pose magic numbers.
    private val wristRotFlex = JointRotation(Vector3(0f, 0f, 1f), 0f)
    private val wristRotDev = JointRotation(Vector3(0f, 1f, 0f), 0f)
    private val ankleRotFlex = JointRotation(Vector3(0f, 0f, 1f), 0f)
    private val ankleRotInv = JointRotation(Vector3(1f, 0f, 0f), 0f)

    /**
     * Composes the wrist's 2-DOF local rotation from [flexion] (flexion/extension about the
     * mediolateral Z axis) and [deviation] (radial/ulnar deviation about the antero-posterior Y
     * axis): `R = Rz(flexion) · Ry(deviation)`. The two combine exactly instead of one axis
     * dropping the other. Allocation-free: writes into [out].
     */
    fun buildWristRotation(flexion: Float, deviation: Float, out: JointRotation): JointRotation {
        wristRotFlex.axis.set(0f, 0f, 1f); wristRotFlex.angle = flexion
        wristRotDev.axis.set(0f, 1f, 0f); wristRotDev.angle = deviation
        return composeRotations(wristRotFlex, wristRotDev, out)
    }

    /**
     * Composes the ankle's 2-DOF local rotation from [dorsiflexion] (dorsi/plantar-flexion about
     * the mediolateral Z axis) and [inversion] (inversion/eversion about the long X axis):
     * `R = Rz(dorsiflexion) · Rx(inversion)`. The two combine exactly instead of one axis
     * dropping the other. Allocation-free: writes into [out].
     */
    fun buildAnkleRotation(dorsiflexion: Float, inversion: Float, out: JointRotation): JointRotation {
        ankleRotFlex.axis.set(0f, 0f, 1f); ankleRotFlex.angle = dorsiflexion
        ankleRotInv.axis.set(1f, 0f, 0f); ankleRotInv.angle = inversion
        return composeRotations(ankleRotFlex, ankleRotInv, out)
    }

    // --- Hip: 3-DOF ball-and-socket authoring (UNI-10) ----------------------------
    //
    // The hip is a genuine ball-and-socket at the acetabulum. Poses previously expressed hip
    // motion inconsistently (raw `hip.localRotation` in some poses, IK-implied femur in others)
    // and femoral internal/external rotation was entangled with the IK pole. This composer gives
    // the hip a first-class, named authoring path mirroring [buildChestOrientation]: flexion/
    // extension in the sagittal plane about the mediolateral Z axis; abduction/adduction in the
    // frontal plane about the antero-posterior Y axis; internal/external rotation about the femur's
    // long X axis (kept separate from the IK pole, which only selects the knee-bend plane).
    // [sideSign] mirrors abduction and axial rotation across the body mid-line (-1 left, +1 right)
    // so a positive value spreads/rotates both legs symmetrically. The anatomical ROM vocabulary
    // lives in [HipRomLimits] (shared, named — no per-pose magic numbers); this helper composes
    // the authored angles and leaves ROM enforcement to the validator's `HIP_ROM_LIMIT` rule.
    // Composition: `R = Rz(flexion) · Ry(abduction · sideSign) · Rx(rotation · sideSign)`.
    // Allocation-free: writes into [out].
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

    /**
     * Angle (degrees) between two directions, allocation-free. Used by the angular
     * joint-limit validator and by any caller that needs the deviation of a proximal bone from
     * its anatomical neutral direction (the root-joint cone in [AngularJointLimits]).
     */
    fun angleBetweenDegrees(a: Vector3, b: Vector3): Float {
        val ma = a.mag()
        val mb = b.mag()
        if (ma < 1e-6f || mb < 1e-6f) return 0f
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z) / (ma * mb)
        return acos(dot.coerceIn(-1f, 1f)) * RAD2DEG
    }

    /**
     * Phase 1 (F6) — default world-space IK pole, derived by the engine when a pose omits one.
     *
     * The bend plane is anchored to world-up projected perpendicular to the `root -> target` aim,
     * so elbows/knees bend in the body's natural sagittal/frontal plane regardless of how the
     * parent frame is rotated (the solver receives a world pole, never a parent-frame one — F4).
     * If the aim is (near) vertical the anchor falls back to world-forward (+Z), then to world-X,
     * so the pole is always a well-defined unit vector. Allocation-free: writes into [out].
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
     * Phase 1 (F5) — bone-length invariant check. Returns true iff the solved chain preserves
     * both bone lengths to within [eps] (the IK solve must be exact). Written by the IK path and
     * folded into [SkeletonPose.boneLengthsVerified]. Allocation-free.
     */
    fun bonesExact(root: Vector3, mid: Vector3, end: Vector3, L1: Float, L2: Float, eps: Float = 1e-3f): Boolean {
        val d1x = mid.x - root.x; val d1y = mid.y - root.y; val d1z = mid.z - root.z
        val d1 = sqrt(d1x * d1x + d1y * d1y + d1z * d1z)
        val d2x = end.x - mid.x; val d2y = end.y - mid.y; val d2z = end.z - mid.z
        val d2 = sqrt(d2x * d2x + d2y * d2y + d2z * d2z)
        return abs(d1 - L1) <= eps && abs(d2 - L2) <= eps
    }

    /**
     * Frame-relative IK overload. The pole is authored in the limb-root's LOCAL frame and is
     * transformed into world space with [parentRotation] before solving. The analytical solver
     * itself is unchanged. This keeps the elbow direction stable as the parent frame rotates
     * (e.g. a twisting thorax), eliminating pole-vector flips and uneven arm motion.
     *
     * Phase 1 (F4): DEPRECATED. The IK layer must be strictly world-space — the pose/finalizer
     * owns any parent-frame→world conversion (or calls [deriveDefaultPole]) before solving.
     * Retained only so existing callers keep compiling until Phase 3 removes it.
     */
    @Deprecated(
        "Phase 1: IK is world-only. Convert the pole to world space (toWorldDirection) or call " +
            "deriveDefaultPole before solveIK; the frame-relative overload will be removed in Phase 3.",
        ReplaceWith(
            "solveIK(root, target, L1, L2, toWorldDirection(poleLocal, parentRotation, Vector3()), constraint, result, contact)",
            "com.monkfitness.app.animation.SkeletonMath.toWorldDirection"
        )
    )
    fun solveIK(
        root: Vector3,
        target: Vector3,
        L1: Float,
        L2: Float,
        poleLocal: Vector3,
        parentRotation: JointRotation,
        constraint: IKConstraint,
        result: IKResult = IKResult(),
        contact: ContactConstraint? = null
    ): IKResult {
        toWorldDirection(poleLocal, parentRotation, poleWorldScratch)
        return solveIK(root, target, L1, L2, poleWorldScratch, constraint, result, contact)
    }

    // High-fidelity 3D Rotation Matrix utilities for zero-allocation FK propagation

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
