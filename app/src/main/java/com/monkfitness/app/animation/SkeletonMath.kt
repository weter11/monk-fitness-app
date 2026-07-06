package com.monkfitness.app.animation

import kotlin.math.*

data class Vector3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(v: Vector3) = Vector3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vector3) = Vector3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vector3(x / s, y / s, z / s)

    fun mag() = sqrt(x * x + y * y + z * z)
    fun normalize(): Vector3 {
        val m = mag()
        return if (m > 1e-6) this / m else Vector3(0f, 0f, 0f)
    }

    fun dot(v: Vector3) = x * v.x + y * v.y + z * v.z
    fun cross(v: Vector3) = Vector3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )

    fun copy() = Vector3(x, y, z)
}

object SkeletonMath {
    fun easeIO(x: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        return cx * cx * cx * (cx * (cx * 6 - 15) + 10)
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerp(a: Vector3, b: Vector3, t: Float): Vector3 = a + (b - a) * t

    // Rodrigues rotation: rotate v around unit axis k by ang
    fun rotAround(v: Vector3, axis: Vector3, ang: Float): Vector3 {
        val k = axis.normalize()
        val c = cos(ang)
        val s = sin(ang)
        val dot = k.dot(v)
        val cross = k.cross(v)

        return (v * c) + (cross * s) + (k * (dot * (1 - c)))
    }

    // Hard-locked 2-bone IK solver
    data class IKResult(val joint: Vector3, val end: Vector3)

    fun solveIK(root: Vector3, target: Vector3, L1: Float, L2: Float, pole: Vector3): IKResult {
        val d = target - root
        val dMag = d.mag()
        val dist = dMag.coerceIn(abs(L1 - L2) + 1f, L1 + L2 - 0.5f)
        val dir = d.normalize()
        val end = root + (dir * dist)

        val a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist)
        val h = sqrt(max(L1 * L1 - a * a, 0f))

        var p = pole.copy()
        p = p - (dir * p.dot(dir)) // component perpendicular to bone axis

        if (p.mag() < 1e-4) {
            p = Vector3(0f, 1f, 0f) - (dir * dir.y)
        }
        p = p.normalize()

        val joint = root + (dir * a) + (p * h)
        return IKResult(joint, end)
    }
}
