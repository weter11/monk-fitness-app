package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MotionDriversTest {

    private val epsilon = 1e-4f

    @Test
    fun testStatelessPureDrivers() {

        assertEquals(0f, MotionDrivers.PositiveHalfSine(0f), epsilon)
        assertEquals(1f, MotionDrivers.PositiveHalfSine(0.25f), epsilon)
        assertEquals(0f, MotionDrivers.PositiveHalfSine(0.5f), epsilon)
        assertEquals(0f, MotionDrivers.PositiveHalfSine(0.75f), epsilon)

        assertEquals(0f, MotionDrivers.NegativeHalfSine(0f), epsilon)
        assertEquals(0f, MotionDrivers.NegativeHalfSine(0.25f), epsilon)
        assertEquals(0f, MotionDrivers.NegativeHalfSine(0.5f), epsilon)
        assertEquals(1f, MotionDrivers.NegativeHalfSine(0.75f), epsilon)

        // Symmetry: LeftPhase and RightPhase map to Positive and Negative Half-Sine
        assertEquals(MotionDrivers.PositiveHalfSine(0.25f), MotionDrivers.LeftPhase(0.25f), epsilon)
        assertEquals(MotionDrivers.NegativeHalfSine(0.75f), MotionDrivers.RightPhase(0.75f), epsilon)


        assertEquals(MotionDrivers.PositiveHalfSine(0.25f), MotionDrivers.AlternatingLeftRight(0.25f), epsilon)
        assertEquals(MotionDrivers.NegativeHalfSine(0.75f), MotionDrivers.AlternatingRightLeft(0.75f), epsilon)


        assertEquals(0f, MotionDrivers.FullSine(0f), epsilon)
        assertEquals(1f, MotionDrivers.FullSine(0.25f), epsilon)
        assertEquals(0f, MotionDrivers.FullSine(0.5f), epsilon)
        assertEquals(-1f, MotionDrivers.FullSine(0.75f), epsilon)

        assertEquals(1f, MotionDrivers.Cosine(0f), epsilon)
        assertEquals(0f, MotionDrivers.Cosine(0.25f), epsilon)
        assertEquals(-1f, MotionDrivers.Cosine(0.5f), epsilon)
        assertEquals(0f, MotionDrivers.Cosine(0.75f), epsilon)


        assertEquals(0.5f, MotionDrivers.WeightShift(0f), epsilon)
        assertEquals(1.0f, MotionDrivers.WeightShift(0.25f), epsilon)
        assertEquals(0.5f, MotionDrivers.WeightShift(0.5f), epsilon)
        assertEquals(0.0f, MotionDrivers.WeightShift(0.75f), epsilon)


        assertEquals(1f, MotionDrivers.ForwardBack(0f), epsilon)
        assertEquals(0f, MotionDrivers.ForwardBack(0.25f), epsilon)
        assertEquals(-1f, MotionDrivers.ForwardBack(0.5f), epsilon)


        assertEquals(0f, MotionDrivers.VerticalLift(0f), epsilon)
        assertEquals(1f, MotionDrivers.VerticalLift(0.5f), epsilon)
        assertEquals(0f, MotionDrivers.VerticalLift(1f), epsilon)

        assertEquals(0f, MotionDrivers.PushPhase(0f), epsilon)
        assertEquals(1f, MotionDrivers.PushPhase(0.5f), epsilon)

        assertEquals(1f, MotionDrivers.PullPhase(0f), epsilon)
        assertEquals(0f, MotionDrivers.PullPhase(0.5f), epsilon)


        assertEquals(0f, MotionDrivers.Pulse(0f), epsilon)
        assertEquals(1f, MotionDrivers.Pulse(0.5f), epsilon)

        assertEquals(0f, MotionDrivers.DoublePulse(0f), epsilon)
        assertEquals(0f, MotionDrivers.DoublePulse(0.5f), epsilon)
        assertEquals(0f, MotionDrivers.DoublePulse(1.0f), epsilon)
        assertTrue(MotionDrivers.DoublePulse(0.25f) > 0.9f)
        assertTrue(MotionDrivers.DoublePulse(0.75f) > 0.9f)


        assertEquals(0f, MotionDrivers.ParabolicLift(0f), epsilon)
        assertEquals(1.0f, MotionDrivers.ParabolicLift(0.5f), epsilon)
        assertEquals(0f, MotionDrivers.ParabolicLift(1f), epsilon)


        assertEquals(0f, MotionDrivers.EaseInOut(0f), epsilon)
        assertEquals(0.5f, MotionDrivers.EaseInOut(0.5f), epsilon)
        assertEquals(1f, MotionDrivers.EaseInOut(1f), epsilon)
        assertTrue(MotionDrivers.EaseInOut(-0.5f) == 0f)
        assertTrue(MotionDrivers.EaseInOut(1.5f) == 1f)
    }

    @Test
    fun testAlternatingMotionDrivers() {

        val start = MotionDrivers.alternating(0f)
        val mid = MotionDrivers.alternating(0.5f)
        val end = MotionDrivers.alternating(1f)


        assertEquals(0f, start.phase, epsilon)
        assertEquals(0.5f, mid.phase, epsilon)
        assertEquals(1f, end.phase, epsilon)


        val firstHalfPeak = MotionDrivers.alternating(0.25f)
        assertEquals(1f, firstHalfPeak.left, epsilon)
        assertEquals(0f, firstHalfPeak.right, epsilon)


        val secondHalfPeak = MotionDrivers.alternating(0.75f)
        assertEquals(0f, secondHalfPeak.left, epsilon)
        assertEquals(1f, secondHalfPeak.right, epsilon)


        assertEquals(0f, start.left, epsilon)
        assertEquals(0f, start.right, epsilon)
        assertEquals(0f, mid.left, epsilon)
        assertEquals(0f, mid.right, epsilon)
        assertEquals(0f, end.left, epsilon)
        assertEquals(0f, end.right, epsilon)






        assertEquals(0.5f, start.weightShift, epsilon)
        assertEquals(1f, firstHalfPeak.weightShift, epsilon)
        assertEquals(0.5f, mid.weightShift, epsilon)
        assertEquals(0f, secondHalfPeak.weightShift, epsilon)

        // lift and pelvisDrop run at double frequency, so they are zero at 0 / 0.5 / 1
        assertEquals(0f, start.lift, epsilon)
        assertEquals(0f, mid.lift, epsilon)
        assertEquals(0f, end.lift, epsilon)
        assertTrue(firstHalfPeak.lift > 0.9f)
        assertTrue(secondHalfPeak.lift > 0.9f)
    }

    @Test
    fun testBilateralMotionDrivers() {
        val start = MotionDrivers.bilateral(0f)
        val mid = MotionDrivers.bilateral(0.5f)
        val end = MotionDrivers.bilateral(1f)


        assertEquals(0f, start.phase, epsilon)
        assertEquals(0.5f, mid.phase, epsilon)
        assertEquals(1f, end.phase, epsilon)


        assertEquals(0f, start.left, epsilon)
        assertEquals(0f, start.right, epsilon)
        assertEquals(1f, mid.left, epsilon)
        assertEquals(1f, mid.right, epsilon)
        assertEquals(0f, end.left, epsilon)
        assertEquals(0f, end.right, epsilon)


        assertEquals(0.5f, start.weightShift, epsilon)
        assertEquals(0.5f, mid.weightShift, epsilon)
        assertEquals(0.5f, end.weightShift, epsilon)


        assertEquals(1f, start.lift, epsilon)
        assertEquals(0f, mid.lift, epsilon)
        assertEquals(1f, end.lift, epsilon)
    }

    @Test
    fun testJumpMotionDrivers() {
        val start = MotionDrivers.jump(0f)
        val takeoff = MotionDrivers.jump(0.25f)
        val apex = MotionDrivers.jump(0.5f)
        val landing = MotionDrivers.jump(0.75f)
        val end = MotionDrivers.jump(1f)

        // Lift represents airborne vertical displacement:
        // 0 during crouch (0.0 to 0.25), smooth arc during flight (0.25 to 0.75), 0 during landing (0.75 to 1.0)
        assertEquals(0f, start.lift, epsilon)
        assertEquals(0f, takeoff.lift, epsilon)
        assertEquals(1f, apex.lift, epsilon)
        assertEquals(0f, landing.lift, epsilon)
        assertEquals(0f, end.lift, epsilon)


        assertEquals(0f, start.left, epsilon)
        assertEquals(1f, apex.left, epsilon)
        assertEquals(0f, end.left, epsilon)

        // Pelvis drop: peaks in prep crouch (midpoint of 0.0..0.25 is 0.125) and landing absorption (midpoint of 0.75..1.0 is 0.875)
        val maxPrepCrouch = MotionDrivers.jump(0.125f)
        val maxLandingCrouch = MotionDrivers.jump(0.875f)

        assertEquals(1f, maxPrepCrouch.pelvisDrop, epsilon)
        assertEquals(1f, maxLandingCrouch.pelvisDrop, epsilon)


        assertEquals(0f, takeoff.pelvisDrop, epsilon)
        assertEquals(0f, apex.pelvisDrop, epsilon)
        assertEquals(0f, landing.pelvisDrop, epsilon)
        assertEquals(0f, start.pelvisDrop, epsilon)
        assertEquals(0f, end.pelvisDrop, epsilon)
    }

    @Test
    fun testContinuousMotionDrivers() {
        val start = MotionDrivers.continuous(0f)
        val mid = MotionDrivers.continuous(0.5f)
        val end = MotionDrivers.continuous(1f)


        assertEquals(0f, start.phase, epsilon)
        assertEquals(0.5f, mid.phase, epsilon)
        assertEquals(1f, end.phase, epsilon)

        // left and right oscillate in anti-phase
        assertEquals(0.5f, start.left, epsilon)
        assertEquals(0.5f, start.right, epsilon)

        val peakLeft = MotionDrivers.continuous(0.25f)
        assertEquals(1f, peakLeft.left, epsilon)
        assertEquals(0f, peakLeft.right, epsilon)

        val peakRight = MotionDrivers.continuous(0.75f)
        assertEquals(0f, peakRight.left, epsilon)
        assertEquals(1f, peakRight.right, epsilon)
    }

    @Test
    fun testClampingBehavior() {

        val negative = MotionDrivers.alternating(-0.5f)
        val positive = MotionDrivers.alternating(1.5f)

        assertEquals(0f, negative.phase, epsilon)
        assertEquals(1f, positive.phase, epsilon)

        val negativeBilateral = MotionDrivers.bilateral(-0.2f)
        val positiveBilateral = MotionDrivers.bilateral(1.2f)

        assertEquals(0f, negativeBilateral.phase, epsilon)
        assertEquals(1f, positiveBilateral.phase, epsilon)
    }

    @Test
    fun testContinuityAndNoVelocitySpikes() {
        // Small progress changes must yield small outputs: a velocity spike would be a popped animation.
        val steps = 1000
        val dt = 1f / steps

        for (i in 0 until steps) {
            val p1 = i * dt
            val p2 = (i + 1) * dt


            val a1 = MotionDrivers.alternating(p1)
            val a2 = MotionDrivers.alternating(p2)
            assertTrue(abs(a2.left - a1.left) < 0.05f)
            assertTrue(abs(a2.right - a1.right) < 0.05f)
            assertTrue(abs(a2.pelvisDrop - a1.pelvisDrop) < 0.05f)


            val b1 = MotionDrivers.bilateral(p1)
            val b2 = MotionDrivers.bilateral(p2)
            assertTrue(abs(b2.phase - b1.phase) < 0.05f)
            assertTrue(abs(b2.pelvisDrop - b1.pelvisDrop) < 0.05f)


            val j1 = MotionDrivers.jump(p1)
            val j2 = MotionDrivers.jump(p2)
            assertTrue(abs(j2.lift - j1.lift) < 0.05f)
            assertTrue(abs(j2.pelvisDrop - j1.pelvisDrop) < 0.05f)


            val c1 = MotionDrivers.continuous(p1)
            val c2 = MotionDrivers.continuous(p2)
            assertTrue(abs(c2.left - c1.left) < 0.05f)
            assertTrue(abs(c2.right - c1.right) < 0.05f)
        }
    }
}
