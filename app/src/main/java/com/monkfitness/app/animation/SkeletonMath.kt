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

data class IKConstraint(
    val minimumFlexionAngle: Float,
    val maximumExtensionRatio: Float
) {
    companion object {
        val ArmConstraint = IKConstraint(30f, 0.95f)
        val LegConstraint = IKConstraint(5f, 0.98f)
    }
}

object SkeletonMath {
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
        // We need a unit axis. Normalization can happen in-place on a copy.
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

        // cross = k cross v
        val cx = ky * v.z - kz * v.y
        val cy = kz * v.x - kx * v.z
        val cz = kx * v.y - ky * v.x

        // (v * c) + (cross * s) + (k * (dot * (1 - c)))
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
    class IKResult(val joint: Vector3 = Vector3(), val end: Vector3 = Vector3())

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

        // MAX LIMIT
        val maxDist = (L1 + L2) * constraint.maximumExtensionRatio

        // MIN LIMIT
        val minCos = cos(constraint.minimumFlexionAngle * PI.toFloat() / 180f)
        val minDist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)

        val dist = dMag.coerceIn(minDist, maxDist)

        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dx / dMag; dirY = dy / dMag; dirZ = dz / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }

        result.end.set(root.x + dirX * dist, root.y + dirY * dist, root.z + dirZ * dist)

        val a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist)
        val h = sqrt(max(L1 * L1 - a * a, 0f))

        // p = component of pole perpendicular to bone axis
        val pDotDir = pole.x * dirX + pole.y * dirY + pole.z * dirZ
        var px = pole.x - dirX * pDotDir
        var py = pole.y - dirY * pDotDir
        var pz = pole.z - dirZ * pDotDir

        val pMag = sqrt(px * px + py * py + pz * pz)
        if (pMag < 1e-4f) {
            // Shank is parallel to hint, fallback to world down relative to shank
            // worldDown = (0, 1, 0)
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
}
