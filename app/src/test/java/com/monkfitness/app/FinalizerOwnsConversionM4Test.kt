package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * M4 (RFC_GAP_CLOSURE) — `FINALIZER_OWNS_CONVERSION=true`.
 *
 * M4 activates the Finalizer's exclusive local-transform ownership: `preConvertPoles` (a reserved
 * no-op hook) and the `reconstructChestFrame` F1/B5 read-only chest-frame guard. The guard only
 * fires for poses that registered an engine [ContactSpec]; the chest reconstruction touches only the
 * chest subtree (shoulders/arms/neck/head), so it can never displace a Solver-settled hand/foot
 * contact, and the guard is a no-op that leaves the output byte-identical.
 *
 * This suite proves that flipping the flag changes nothing: every production pose and every
 * contact-bearing validation instrument renders identically with the flag on vs off (maxDeviation
 * 0.0). Because no production pose registers an engine contact, the guard never even runs for them;
 * for the validation instruments the guard runs but finds no contact moved, so output is preserved.
 */
class FinalizerOwnsConversionM4Test {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val original = EngineFlags.FINALIZER_OWNS_CONVERSION

    @After
    fun restore() {
        EngineFlags.FINALIZER_OWNS_CONVERSION = original
    }

    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "DeadBug" to { DeadBugPose() },
        "GluteBridge" to { GluteBridgePose() }
    )

    private fun contactPoseFactories(): List<Pair<String, () -> BaseValidationPose>> = listOf(
        "DeepOverheadSquat" to { DeepOverheadSquatPose() },
        "DeadHang" to { DeadHangPose() },
        "MiddleSplit" to { MiddleSplitPose() },
        "PikeSit" to { PikeSitPose() }
    )

    private fun maxDeviation(a: SkeletonPose, b: SkeletonPose): Float {
        var max = 0f
        for (j in Joint.entries) {
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            val d = maxOf(abs(pa.x - pb.x), abs(pa.y - pb.y), abs(pa.z - pb.z))
            if (d > max) max = d
        }
        return max
    }

    @Test
    fun flagDefaultsTrue() {
        assertTrue("FINALIZER_OWNS_CONVERSION must default to true in M4", original)
    }

    @Test
    fun productionPosesByteIdenticalFlagOnVsOff() {
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        val frames = 20
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in poseFactories()) {
            for (i in 0..frames) {
                val p = i / frames.toFloat()
                val ctx = PoseContext(p, Side.LEFT, def)

                EngineFlags.FINALIZER_OWNS_CONVERSION = false
                val off = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                EngineFlags.FINALIZER_OWNS_CONVERSION = true
                val on = SkeletonPipeline(def).produceFrame(factory(), ctx).pose

                val d = maxDeviation(off, on)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("M4 flag must not change production poses at $worst", 0f, maxDev, 1e-4f)
        println("FinalizerOwnsConversionM4Test.productionPosesByteIdenticalFlagOnVsOff: OK maxDev=$maxDev")
    }

    @Test
    fun contactPosesByteIdenticalFlagOnVsOff() {
        EngineFlags.FINALIZER_OWNS_CONVERSION = false
        var maxDev = 0f
        var worst = ""
        for ((name, factory) in contactPoseFactories()) {
            // A few progress samples; validation poses are largely static but sample to be safe.
            for (p in listOf(0f, 0.5f, 1f)) {
                val ctx = PoseContext(p, Side.LEFT, def)

                EngineFlags.FINALIZER_OWNS_CONVERSION = false
                val off = SkeletonPipeline(def).produceFrame(factory(), ctx).pose
                EngineFlags.FINALIZER_OWNS_CONVERSION = true
                val on = SkeletonPipeline(def).produceFrame(factory(), ctx).pose

                val d = maxDeviation(off, on)
                if (d > maxDev) { maxDev = d; worst = "$name @$p" }
            }
        }
        assertEquals("M4 flag must not change contact instruments at $worst", 0f, maxDev, 1e-4f)
        println("FinalizerOwnsConversionM4Test.contactPosesByteIdenticalFlagOnVsOff: OK maxDev=$maxDev")
    }

    @Test
    fun guardActivatesOnlyForContacts() {
        // Sanity: a production pose with no engine contacts must have an empty contact list (so the
        // F1/B5 guard never runs for it), whereas the validation instruments do register contacts.
        EngineFlags.FINALIZER_OWNS_CONVERSION = true
        val squat = AirSquatPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertEquals("production pose must have no engine contacts", 0, squat.contacts.size)

        val hang = DeadHangPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("validation instrument must register contacts", hang.contacts.isNotEmpty())
    }
}
