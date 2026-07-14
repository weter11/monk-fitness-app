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
        result: IKResult = IKResult()
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

        result.end.set(root.x + dirX * dist, root.y + dirY * dist, root.z + dirZ * dist)

        val a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist)
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

        result.joint.set(
            root.x + dirX * a + px * h,
            root.y + dirY * a + py * h,
            root.z + dirZ * a + pz * h
        )

        return result
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
        result: IKResult = IKResult()
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
        result.requestedDistance = dMag
        result.clampedDistance = dist
        result.clampAmount = if (dMag < minDist) {
            minDist - dMag
        } else if (dMag > maxDist) {
            dMag - maxDist
        } else {
            0f
        }

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
        result: IKResult = IKResult()
    ): IKResult {
        toWorldDirection(poleLocal, parentRotation, poleWorldScratch)
        return solveIK(root, target, L1, L2, poleWorldScratch, constraint, result)
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
