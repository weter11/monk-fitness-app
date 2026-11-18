package com.monkfitness.app.anatomy

import com.monkfitness.app.PoseFrameSweep
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.acos

/**
 * The stage-1 measurement: every registered builder at nine stations, with the derived frame and the
 * pole comparison taken on each published frame. The table this test prints is the report's evidence.
 */
class SegmentFrameMeasurementTest {

    @Test
    fun theSweepCoversEveryRegisteredBuilderAtNineStations() {
        assertEquals("registered builders", 66, AnatomyPoseFixture.BUILDERS.size)
        assertEquals("stations", 9, AnatomyPoseFixture.STATIONS.size)
        assertEquals("published frames", 594, sweep.frames)
        assertEquals("one usable root per frame", 594, sweep.counts[SegmentFrameStatus.USABLE.ordinal])
    }

    @Test
    fun noFrameIsInvalidAndNoAxisIsNonFinite() {
        assertEquals("INVALID frames", 0, sweep.counts[SegmentFrameStatus.INVALID.ordinal])
        assertEquals("non-finite axis components", 0, sweep.nonFiniteAxes)
        assertEquals("zero rest directions", 0, sweep.reasons[SegmentFrameReason.ZERO_REST_DIRECTION.ordinal])
        assertTrue(
            "the near-antipodal branch is exercised by the sweep",
            sweep.reasons[SegmentFrameReason.NEAR_ANTIPODAL.ordinal] >= 1
        )
    }

    @Test
    fun everyPublishedFrameEstablishesOneUsableRootAndTwentyTwoCarriedRolls() {
        assertEquals(594, sweep.counts[SegmentFrameStatus.USABLE.ordinal])
        assertEquals(22 * 594, sweep.counts[SegmentFrameStatus.TWIST_UNAUTHORED.ordinal])
        assertEquals("the reason tally covers every instance of every frame", 23 * 594, sweep.reasons.sum())
        assertEquals(
            "the five inheriting instances report COINCIDENT in every frame",
            5 * 594,
            sweep.reasons[SegmentFrameReason.COINCIDENT.ordinal] - sweep.coincidentDerived
        )
        assertEquals(
            "and the only deriving rows that collapse are the two pike-push-up hands",
            2 * 9,
            sweep.coincidentDerived
        )
    }

    @Test
    fun thePoleStatusesPartitionEveryInstanceOfEveryFrame() {
        assertEquals(23 * 594, sweep.poleCounts.sum())
        assertEquals(
            "nine structurally unavailable instances per frame",
            9 * 594,
            sweep.poleCounts[PoleRollStatus.POLE_UNAVAILABLE.ordinal]
        )
        assertEquals(
            "fourteen measurable instances per frame",
            14 * 594,
            sweep.poleCounts.sum() - sweep.poleCounts[PoleRollStatus.POLE_UNAVAILABLE.ordinal]
        )
        assertTrue(
            "the pike push-up hand is the degenerate case the sweep must meet",
            sweep.poleCounts[PoleRollStatus.POLE_LEG_DEGENERATE.ordinal] >= 2 * 9
        )
        assertEquals(
            "the measured sample size is the count of measured statuses",
            sweep.poleCounts[PoleRollStatus.POLE_MEASURED.ordinal],
            sweep.measured.size
        )
    }

    @Test
    fun everyReportedRollIsAnAngleAndEveryUnmeasuredOneIsNaN() {
        for ((id, theta) in sweep.measured) {
            assertTrue("$id reported $theta", theta.isFinite())
            assertTrue("$id reported $theta outside [0, π/2]", theta >= 0f && theta <= (PI / 2.0).toFloat())
        }
        for ((id, theta) in sweep.unmeasured) {
            assertTrue("$id is not measured and reported $theta instead of NaN", theta.isNaN())
        }
        assertTrue("the sweep measured rolls", sweep.measured.size > 5000)
    }

    @Test
    fun theFourPilotsAreMeasuredAtEveryStation() {
        assertEquals(4, sweep.pilotFrames.size)
        for ((pilot, frames) in sweep.pilotFrames) {
            assertEquals("$pilot stations", 9, frames)
        }
        println(sweep.report())
    }

    @Test
    fun thePelvisFrameFollowsTheAuthoredTiltWhereAFixedBindFrameCannot() {
        val rows = mutableListOf<String>()
        for ((builder, station) in listOf("squat_standard" to 0.999f, "birddog_hold" to 0f)) {
            val pose = AnatomyPoseFixture.livePose(builder, station)
            val set = SegmentFrameSet()
            SegmentFrames(AnatomyPoseFixture.DEFINITION).derive(pose, set)

            val rotation = pose.getJointRotation(Joint.PELVIS)
            val authored = SkeletonMath.rotAround(Vector3(0f, 1f, 0f), rotation.axis, rotation.angle, Vector3())
            val derivedY = Vector3(set.axis(SegmentId.PELVIS, 1, 0), set.axis(SegmentId.PELVIS, 1, 1), set.axis(SegmentId.PELVIS, 1, 2))

            val authoredTilt = degrees(acos(authored.normalizedCopy().dot(Vector3(0f, 1f, 0f)).coerceIn(-1f, 1f)))
            val derivedError = degrees(acos(authored.normalizedCopy().dot(derivedY).coerceIn(-1f, 1f)))
            val fromWorldUp = degrees(acos(derivedY.y.coerceIn(-1f, 1f)))

            assertTrue("$builder: derived +Y follows the authored tilt ($derivedError°)", derivedError <= 3f)
            assertTrue("$builder: the fixed bind frame would throw the tilt away", authoredTilt > 20f)
            rows += "  ${builder.padEnd(16)} authored ${"%.2f".format(authoredTilt)}°  derived-vs-authored ${"%.2f".format(derivedError)}°  " +
                "derived tilt from world +Y ${"%.2f".format(fromWorldUp)}°  rootRotationDelta ${"%.3f".format(pose.rootRotationDelta)}"
        }
        println("pelvis root frame vs the authored tilt")
        rows.forEach { println(it) }
    }

    private fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()

    private companion object {
        val sweep: Sweep by lazy { Sweep() }
    }

    /** One pass over the 594 published frames, tallied by instance and status. */
    private class Sweep {
        val counts = IntArray(SegmentFrameStatus.entries.size)
        val reasons = IntArray(SegmentFrameReason.entries.size)
        val poleCounts = IntArray(PoleRollStatus.entries.size)
        val measured = mutableListOf<Pair<SegmentId, Float>>()
        val unmeasured = mutableListOf<Pair<SegmentId, Float>>()
        val pilotFrames = LinkedHashMap<String, Int>()
        var nonFiniteAxes = 0
        var coincidentDerived = 0
        var frames = 0

        private val perInstance = Array(SegmentId.entries.size) { mutableListOf<Float>() }
        private val perPilot = HashMap<String, Array<MutableList<Float>>>()
        private val pilotUnavailable = HashMap<String, Int>()
        private val pilotFramesSeen = HashMap<String, Int>()

        init {
            val segmentFrames = SegmentFrames(AnatomyPoseFixture.DEFINITION)
            val set = SegmentFrameSet()
            val comparator = PoleComparator(AnatomyPoseFixture.DEFINITION)
            for (builder in AnatomyPoseFixture.BUILDERS) {
                comparator.reset()
                val pilot = builder in AnatomyPoseFixture.PILOTS
                val published = PoseFrameSweep.sweep(
                    PoseRegistry.getPoseConfig(builder)!!.builder,
                    AnatomyPoseFixture.STATIONS,
                    AnatomyPoseFixture.DEFINITION
                )
                if (pilot) pilotFramesSeen[builder] = published.size
                for (frame in published) {
                    frames++
                    val pose = AnatomyPoseFixture.poseOf(frame)
                    segmentFrames.derive(pose, set)
                    comparator.measure(pose, set)
                    for (id in SegmentId.entries) {
                        counts[set.status(id).ordinal]++
                        reasons[set.reason(id).ordinal]++
                        if (set.reason(id) == SegmentFrameReason.COINCIDENT && !SegmentSpec.of(id).inherits) coincidentDerived++
                        for (axis in 0..2) for (component in 0..2) {
                            if (!set.axis(id, axis, component).isFinite()) nonFiniteAxes++
                        }
                        val status = comparator.status(id)
                        poleCounts[status.ordinal]++
                        val theta = comparator.theta(id)
                        if (status == PoleRollStatus.POLE_MEASURED) {
                            measured += id to theta
                            perInstance[id.ordinal] += theta
                            if (pilot) {
                                perPilot.getOrPut(builder) { Array(SegmentId.entries.size) { mutableListOf() } }[id.ordinal] += theta
                            }
                        } else {
                            unmeasured += id to theta
                            if (pilot && status == PoleRollStatus.POLE_UNAVAILABLE) {
                                pilotUnavailable[builder] = (pilotUnavailable[builder] ?: 0) + 1
                            }
                        }
                    }
                }
            }
            pilotFramesSeen.forEach { (builder, count) -> pilotFrames[builder] = count }
        }

        fun report(): String {
            val out = StringBuilder()
            out.appendLine("594-frame sweep (66 builders x 9 stations)")
            out.appendLine(
                "frames=$frames usable=${counts[SegmentFrameStatus.USABLE.ordinal]} " +
                    "twist=${counts[SegmentFrameStatus.TWIST_UNAUTHORED.ordinal]} " +
                    "invalid=${counts[SegmentFrameStatus.INVALID.ordinal]} nonFiniteAxes=$nonFiniteAxes"
            )
            for (reason in SegmentFrameReason.entries) {
                out.appendLine("  reason ${reason.name}=${reasons[reason.ordinal]}")
            }
            for (status in PoleRollStatus.entries) {
                out.appendLine("  pole ${status.name}=${poleCounts[status.ordinal]}")
            }
            out.appendLine("per-instance |theta| (deg) over the sweep: n / median / max; anchored = a rest plane exists")
            for (id in SegmentId.entries) {
                val values = perInstance[id.ordinal].sorted()
                val anchored = SegmentRest.restPole(id) != null
                if (values.isEmpty()) {
                    out.appendLine("  ${id.name.padEnd(12)} n=0 anchored=$anchored")
                } else {
                    out.appendLine(
                        "  ${id.name.padEnd(12)} n=${values.size} med=${"%.2f".format(degrees(values[values.size / 2]))} " +
                            "max=${"%.2f".format(degrees(values.last()))} anchored=$anchored"
                    )
                }
            }
            out.appendLine("per pilot: frames / measured instances / worst |theta| deg / |d|*theta at d=0.03 m")
            for ((pilot, arrays) in perPilot) {
                var measuredCount = 0
                var worst = 0f
                var worstId = ""
                for (id in SegmentId.entries) {
                    for (theta in arrays[id.ordinal]) {
                        measuredCount++
                        if (theta > worst) {
                            worst = theta
                            worstId = id.name
                        }
                    }
                }
                out.appendLine(
                    "  ${pilot.padEnd(18)} frames=${pilotFrames[pilot]} measured=$measuredCount " +
                        "unavailable=${pilotUnavailable[pilot]} worst=${"%.2f".format(degrees(worst))}° at $worstId " +
                        "-> ${"%.4f".format(0.03f * worst)} m"
                )
            }
            return out.toString()
        }

        private fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()
    }
}
