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

data class IKConstraint(
    val minimumFlexionAngle: Float,
    val maximumExtensionRatio: Float,
    val angularLimits: AngularJointLimits = AngularJointLimits.ArmAngularLimits
) {
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

        val maxDist = (L1 + L2) * constraint.maximumExtensionRatio

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

        // 2) Angular band on the middle joint interior angle. The distance band already bounds
        //    the middle joint, but an explicit angular cap rejects orientations it could not
        //    (e.g. a hyperextended elbow). Clamp the joint toward the limit by adjusting the
        //    solved distance, staying inside the reach band.
        var angularClampAmount = 0f
        {
            val denom = (2f * L1 * L2)
            var cosT = ((L1 * L1 + L2 * L2 - dist * dist) / denom).coerceIn(-1f, 1f)
            var theta = acos(cosT) * RAD2DEG
            if (theta < limits.minFlexionDegrees) {
                angularClampAmount = limits.minFlexionDegrees - theta
                theta = limits.minFlexionDegrees
                val newCos = cos(theta * DEG2RAD)
                dist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * newCos).coerceIn(minDist, maxDist)
            } else if (theta > limits.maxFlexionDegrees) {
                angularClampAmount = theta - limits.maxFlexionDegrees
                theta = limits.maxFlexionDegrees
                val newCos = cos(theta * DEG2RAD)
                dist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * newCos).coerceIn(minDist, maxDist)
            }
        }

        result.requestedDistance = dMag
        result.clampedDistance = dist
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
     * limb (respecting [IKConstraint.maximumExtensionRatio], which PR-11 can raise to 1.0 for a
     * truly straight reference). The middle sits at exactly `L1` along the aim direction, keeping
     * the first bone length exact; the second bone spans the (clamped) remainder.
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

        val maxDist = (L1 + L2) * constraint.maximumExtensionRatio
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
     * Frame-relative IK overload. The pole is authored in the limb-root's LOCAL frame and is
     * transformed into world space with [parentRotation] before solving. The analytical solver
     * itself is unchanged. This keeps the elbow direction stable as the parent frame rotates
     * (e.g. a twisting thorax), eliminating pole-vector flips and uneven arm motion.
     */
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
