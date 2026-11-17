package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * X9's placement: every derived frame puts its own rest direction on the published segment
 * direction, and every branch of the rule (root, parallel, antipodal, degenerate) is decided rather
 * than left to a normalise.
 */
class SegmentFrameDirectionTest {

    private val frames = SegmentFrames(AnatomyPoseFixture.DEFINITION)

    @Test
    fun theBindPoseIsOneUsableFrameAndTwentyTwoCarriedRolls() {
        val set = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.bindPose(), set)

        val byStatus = SegmentId.entries.groupBy { set.status(it) }
        assertEquals("the root is the only frame two directions pin", listOf(SegmentId.PELVIS), byStatus[SegmentFrameStatus.USABLE])
        assertEquals("every other frame carries its roll", 22, byStatus[SegmentFrameStatus.TWIST_UNAUTHORED]?.size)
        assertEquals("no frame is unestablished at bind", null, byStatus[SegmentFrameStatus.INVALID])

        for (id in SegmentId.entries) {
            assertEquals(
                "${id.name} at bind is the bind frame",
                0f,
                AnatomyPoseFixture.deviationFromBindFrame(set, id),
                1e-7f
            )
        }
    }

    @Test
    fun everyDerivedFramePlacesItsRestDirectionOnThePublishedDirection() {
        var checked = 0
        for (pilot in AnatomyPoseFixture.PILOTS) {
            for (pose in AnatomyPoseFixture.swept(pilot)) {
                val set = SegmentFrameSet()
                frames.derive(pose, set)
                for (id in SegmentId.entries) {
                    val placed = AnatomyPoseFixture.placedDirection(set, id) ?: continue
                    val published = AnatomyPoseFixture.publishedDirection(pose, id) ?: continue
                    val error = AnatomyPoseFixture.directionError(placed, published)
                    val reason = set.reason(id)
                    // An inherited or forced-identity frame keeps the parent's aim, so it promises the
                    // branch's own threshold; only a computed rotation promises τ_dir.
                    if (reason == SegmentFrameReason.COINCIDENT) continue
                    val tolerance = if (reason == SegmentFrameReason.NEAR_PARALLEL) SegmentRest.TAU_ROT else SegmentRest.TAU_DIR
                    assertTrue(
                        "$pilot: ${id.name} claims $placed but the pose publishes $published ($error rad, reason $reason)",
                        error <= tolerance
                    )
                    checked++
                }
            }
        }
        assertTrue("the sweep measured directions", checked >= 300)
    }

    @Test
    fun aFrameThatFollowsItsParentKeepsTheParentColumnForColumn() {
        // The clavicle row is coincident in every frame, so its frame is the thorax's, not a normalised nothing.
        val pose = AnatomyPoseFixture.livePose("squat_standard", 0.6f)
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        for (id in listOf(SegmentId.CLAVICLE_A, SegmentId.CLAVICLE_P)) {
            assertEquals("${id.name} inherits", SegmentFrameReason.COINCIDENT, set.reason(id))
            assertEquals("${id.name} is the thorax frame", 0f, frameDelta(set, id, SegmentId.THORAX), 1e-7f)
        }
    }

    @Test
    fun theTerminalRowsInheritTheNearestSegmentDirection() {
        val pose = AnatomyPoseFixture.livePose("birddog_hold", 0.25f)
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        assertEquals("${SegmentId.SKULL.name} inherits the neck", 0f, frameDelta(set, SegmentId.SKULL, SegmentId.NECK), 1e-7f)
        assertEquals("${SegmentId.TOES_F.name} inherits the foot", 0f, frameDelta(set, SegmentId.TOES_F, SegmentId.FOOT_F), 1e-7f)
        assertEquals("${SegmentId.TOES_B.name} inherits the foot", 0f, frameDelta(set, SegmentId.TOES_B, SegmentId.FOOT_B), 1e-7f)
        assertEquals("a terminal row is a carried roll", SegmentFrameStatus.TWIST_UNAUTHORED, set.status(SegmentId.SKULL))
    }

    @Test
    fun theHandOfPikePushUpInheritsTheForearmInsteadOfNormalisingAZeroVector() {
        var sawDegenerateHand = false
        for (pose in AnatomyPoseFixture.swept("pike_pushup_standard")) {
            val set = SegmentFrameSet()
            frames.derive(pose, set)
            for ((id, inherited) in listOf(SegmentId.HAND_A to SegmentId.FOREARM_A, SegmentId.HAND_P to SegmentId.FOREARM_P)) {
                val spec = SegmentSpec.of(id)
                val node = pose.getJoint(spec.node!!)
                val child = pose.getJoint(spec.child!!)
                val span = Vector3(child.x - node.x, child.y - node.y, child.z - node.z).mag()
                if (span < 1e-3f) {
                    sawDegenerateHand = true
                    assertEquals("${id.name} has no separable direction", SegmentFrameReason.COINCIDENT, set.reason(id))
                    assertEquals("${id.name} inherits the forearm frame", 0f, frameDelta(set, id, inherited), 1e-7f)
                }
            }
        }
        assertTrue("pike push-up authors a zero-length hand pair; the sweep must meet it", sawDegenerateHand)
    }

    @Test
    fun theRootFrameOrthogonalisesTheTorsoAgainstTheHipLine() {
        // A constructed pelvis whose hip line and torso are 33.85° apart (dot 0.8305) is the case the
        // orthogonalisation exists for: the raw pair is not a triad and cannot be used as one.
        val angle = (33.85 * PI / 180.0).toFloat()
        val hipLine = Vector3(0f, 0f, -1f)
        val torso = Vector3(sin(angle), 0f, cos(angle))
        val shear = abs(torso.dot(hipLine))
        assertEquals("the counterexample's measured shear", 0.8305f, shear, 1e-4f)
        assertEquals("the counterexample's measured angle", 33.85f, degrees(acos(shear)), 1e-2f)

        val pose = AnatomyPoseFixture.poseWithLegDirection(torso, hipLine, Vector3(0f, -1f, 0f))
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        assertEquals("a non-orthogonal pair is still a usable frame", SegmentFrameStatus.USABLE, set.status(SegmentId.PELVIS))

        val x = column(set, SegmentId.PELVIS, 0)
        val y = column(set, SegmentId.PELVIS, 1)
        val z = column(set, SegmentId.PELVIS, 2)
        assertEquals("+Z is the hip line", -1f, z.z, 1e-6f)
        assertEquals("+Y is perpendicular to +Z", 0f, y.dot(z), 1e-6f)
        assertEquals("+X is perpendicular to both", 0f, x.dot(y) + x.dot(z), 1e-6f)
        assertEquals("the triad is right handed", 1f, x.dot(y.cross(z)), 1e-5f)
        assertEquals("+Y is unit", 1f, y.mag(), 1e-6f)

        // Without the orthogonalisation the same pair gives a sheared "triad": X = Y × Z is still
        // perpendicular to Y, but it is sin(33.85°) = 0.557 long instead of unit — a 44 % squash of
        // the sagittal axis, which is what an attachment authored on the pelvis would inherit.
        val naiveY = torso
        val naiveX = naiveY.cross(hipLine)
        assertEquals("the un-orthogonalised Y·Z", shear, abs(naiveY.dot(hipLine)), 1e-6f)
        assertEquals("the un-orthogonalised +X is not unit", sin(angle), naiveX.mag(), 1e-4f)
        assertEquals("the derived +X is unit", 1f, x.mag(), 1e-6f)
        assertTrue(
            "the derived +Y is the projection of the torso, so it is not the raw torso either",
            AnatomyPoseFixture.angleBetween(y, torso) > 0.1f
        )
    }

    @Test
    fun theParentIsCarriedBeforeTheRotationAndTheOtherOrderIsNotTheSameFrame() {
        val angle = (35.0 * PI / 180.0).toFloat()
        val rootY = Vector3(0f, cos(angle), sin(angle))
        val rootZ = Vector3(0f, -sin(angle), cos(angle))
        val femur = Vector3(1f, 0f, 0f)
        val pose = AnatomyPoseFixture.poseWithLegDirection(rootY, rootZ, femur)
        val set = SegmentFrameSet()
        frames.derive(pose, set)

        val a = column(set, SegmentId.PELVIS, 1).multiply(-1f)  // pelvis +Y is the femur's rest direction, negated
        val b = femur
        val placed = AnatomyPoseFixture.placedDirection(set, SegmentId.FEMUR_F)!!
        assertEquals("R = D · P places the direction", 0f, AnatomyPoseFixture.directionError(placed, b), SegmentRest.TAU_DIR)

        // P · D is the reversed order: the minimal rotation applied to the rest direction before the
        // parent's frame, instead of after it. It is a different frame wherever the two do not commute.
        val axis = a.cross(b).normalizedCopy()
        val rotation = acos(a.normalizedCopy().dot(b).coerceIn(-1f, 1f))
        val restDir = SegmentRest.restDir(SegmentId.FEMUR_F)!!
        val rotatedRest = rotate(restDir, axis, rotation)
        val reversed = column(set, SegmentId.PELVIS, 0).multiply(rotatedRest.x)
            .add(column(set, SegmentId.PELVIS, 1).multiply(rotatedRest.y))
            .add(column(set, SegmentId.PELVIS, 2).multiply(rotatedRest.z))
        val reversedError = AnatomyPoseFixture.angleBetween(reversed, b)
        assertTrue(
            "P · D must not also place the direction (error $reversedError rad): a command that commutes would make this control vacuous",
            reversedError > 0.05f
        )
    }

    @Test
    fun aNonAxisAlignedAntipodeIsPlacedByTheProjectedHalfTurn() {
        val a = Vector3(1f, 1f, 1f).normalizedCopy()
        val b = Vector3(-a.x, -a.y, -a.z)
        val rootY = Vector3(-a.x, -a.y, -a.z)
        val set = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.poseWithLegDirection(rootY, perpendicularTo(a), b), set)

        val placed = AnatomyPoseFixture.placedDirection(set, SegmentId.FEMUR_F)!!
        val residual = AnatomyPoseFixture.directionError(placed, b)
        assertTrue(
            "the antipodal branch must place a on −a (residual $residual rad, bound ${asin(1e-3f)} rad)",
            residual <= asin(1e-3f)
        )
        assertEquals("the branch is reported", SegmentFrameReason.NEAR_ANTIPODAL, set.reason(SegmentId.FEMUR_F))

        // A half-turn about a coordinate axis misses: that is what the projection out of `a` avoids.
        val wrongAxis = Vector3(1f, 0f, 0f)
        val wrong = rotate(a, wrongAxis, PI.toFloat())
        assertEquals("a coordinate-axis half-turn lands 70.53° away", 70.53f, degrees(AnatomyPoseFixture.angleBetween(wrong, b)), 1e-2f)
    }

    @Test
    fun aNearAntipodeIsDecidedByTheTieBreakAndTheTwoSidesAreDocumentedAsAFlip() {
        val a = Vector3(0f, -1f, 0f)
        val justBelow = Vector3(0f, 1f, -1e-4f).normalizedCopy()
        val justAbove = Vector3(0f, 1f, -2e-3f).normalizedCopy()

        val below = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.poseWithLegDirection(a.multiply(-1f), Vector3(0f, 0f, -1f), justBelow), below)
        assertEquals("within τ_rot the antipodal branch runs", SegmentFrameReason.NEAR_ANTIPODAL, below.reason(SegmentId.FEMUR_F))
        assertTrue(
            "both sides place the direction",
            AnatomyPoseFixture.directionError(AnatomyPoseFixture.placedDirection(below, SegmentId.FEMUR_F)!!, justBelow) <= asin(1e-3f)
        )

        val above = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.poseWithLegDirection(a.multiply(-1f), Vector3(0f, 0f, -1f), justAbove), above)
        assertEquals("outside τ_rot the general branch runs", SegmentFrameReason.UNSET, above.reason(SegmentId.FEMUR_F))
        assertTrue(
            "and it also places the direction",
            AnatomyPoseFixture.directionError(AnatomyPoseFixture.placedDirection(above, SegmentId.FEMUR_F)!!, justAbove) <= SegmentRest.TAU_DIR
        )

        // The two branches rotate about different axes — the half-turn's axis comes from the
        // tie-break, the general branch's from a × b — so the frames they build are different
        // rotations of the parent. That is the discontinuity a · b = −1 is documented with.
        val tieBreakAxis = a.cross(leastAligned(a)).normalizedCopy()
        val generalAxis = a.cross(justAbove).normalizedCopy()
        val axisSplit = AnatomyPoseFixture.angleBetween(tieBreakAxis, generalAxis)
        assertTrue(
            "the two sides of a · b = −1 rotate about different axes ($axisSplit rad apart)",
            axisSplit > 0.5f
        )
    }

    @Test
    fun aNearParallelPairKeepsTheParentFrameInsteadOfNormalisingTinyCrossProduct() {
        val a = Vector3(0f, -1f, 0f)
        val within = Vector3(0f, -1f, 5e-4f).normalizedCopy()
        val outside = Vector3(0f, -1f, 5e-3f).normalizedCopy()
        val rootY = a.multiply(-1f)
        val rootZ = Vector3(0f, 0f, -1f)

        val inside = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.poseWithLegDirection(rootY, rootZ, within), inside)
        assertEquals("within τ_rot the rotation is identity", SegmentFrameReason.NEAR_PARALLEL, inside.reason(SegmentId.FEMUR_F))
        assertEquals("so the parent frame is kept", 0f, frameDelta(inside, SegmentId.FEMUR_F, SegmentId.PELVIS), 1e-7f)

        val out = SegmentFrameSet()
        frames.derive(AnatomyPoseFixture.poseWithLegDirection(rootY, rootZ, outside), out)
        assertEquals("outside τ_rot the rotation is computed", SegmentFrameReason.UNSET, out.reason(SegmentId.FEMUR_F))
        assertEquals(
            "and it follows the published direction",
            0f,
            AnatomyPoseFixture.directionError(AnatomyPoseFixture.placedDirection(out, SegmentId.FEMUR_F)!!, outside),
            SegmentRest.TAU_DIR
        )
    }

    @Test
    fun aStraighteningLimbSweepsThroughTheRestDirectionWithoutFlipping() {
        val rootY = Vector3(0f, 1f, 0f)
        val rootZ = Vector3(0f, 0f, -1f)
        val set = SegmentFrameSet()
        var previousRoll: Vector3? = null
        var worstStep = 0f
        for (step in -10..10) {
            val angle = (step * 6.0 * PI / 180.0).toFloat()
            val direction = Vector3(sin(angle), -cos(angle), 0f)
            frames.derive(AnatomyPoseFixture.poseWithLegDirection(rootY, rootZ, direction), set)
            assertEquals(
                "step $step places its direction",
                0f,
                AnatomyPoseFixture.directionError(AnatomyPoseFixture.placedDirection(set, SegmentId.FEMUR_F)!!, direction),
                SegmentRest.TAU_DIR
            )
            val roll = column(set, SegmentId.FEMUR_F, 0)
            previousRoll?.let { worstStep = maxOf(worstStep, AnatomyPoseFixture.angleBetween(it, roll)) }
            previousRoll = roll
        }
        assertTrue("no step flips the frame (worst step $worstStep rad)", worstStep < 0.2f)
    }

    @Test
    fun aNonFinitePoseIsRefusedRatherThanSilentlyIdentity() {
        val pose = AnatomyPoseFixture.bindPose()
        pose.getJoint(com.monkfitness.app.animation.Joint.CHEST).y = Float.NaN
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        assertEquals(SegmentFrameStatus.INVALID, set.status(SegmentId.PELVIS))
        assertEquals(SegmentFrameReason.NON_FINITE_GEOMETRY, set.reason(SegmentId.PELVIS))
        for (id in SegmentId.entries) {
            assertEquals("${id.name} inherits the refusal", SegmentFrameStatus.INVALID, set.status(id))
        }
    }

    @Test
    fun aCoincidentHipLineMarksTheRootDegenerateWithNoFrameAtAll() {
        val pose = AnatomyPoseFixture.bindPose()
        pose.getJoint(com.monkfitness.app.animation.Joint.HIP_F).set(pose.getJoint(com.monkfitness.app.animation.Joint.HIP_B))
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        assertEquals(SegmentFrameReason.ROOT_DEGENERATE, set.reason(SegmentId.PELVIS))
        assertEquals(SegmentFrameStatus.INVALID, set.status(SegmentId.PELVIS))
        assertEquals("no axis is written for a frame that does not exist", 0f, column(set, SegmentId.PELVIS, 1).mag(), 0f)
    }

    @Test
    fun aCollapsedTorsoIsRootDegenerateAsWell() {
        val pose = AnatomyPoseFixture.bindPose()
        pose.getJoint(com.monkfitness.app.animation.Joint.CHEST).set(pose.getJoint(com.monkfitness.app.animation.Joint.PELVIS))
        val set = SegmentFrameSet()
        frames.derive(pose, set)
        assertEquals(SegmentFrameReason.ROOT_DEGENERATE, set.reason(SegmentId.PELVIS))
    }

    @Test
    fun aRowWhoseRestLengthIsZeroHasNoRestDirectionAndIsReportedInvalid() {
        // Zero shoulder width leaves the scapula row with no rest span, so its rest frame states no
        // direction. That is a definition defect the derivation reports rather than normalising.
        val degenerate = com.monkfitness.app.animation.HumanSkeletonDefinition(shoulderWidth = 0f)
        val set = SegmentFrameSet()
        SegmentFrames(degenerate).derive(AnatomyPoseFixture.bindPose(degenerate), set)
        assertEquals(SegmentFrameReason.ZERO_REST_DIRECTION, set.reason(SegmentId.SCAPULA_A))
        assertEquals(SegmentFrameStatus.INVALID, set.status(SegmentId.SCAPULA_A))
        assertEquals(0f, column(set, SegmentId.SCAPULA_A, 0).mag(), 0f)
    }

    private fun frameDelta(set: SegmentFrameSet, a: SegmentId, b: SegmentId): Float {
        var worst = 0f
        for (axis in 0..2) for (component in 0..2) {
            worst = maxOf(worst, abs(set.axis(a, axis, component) - set.axis(b, axis, component)))
        }
        return worst
    }

    private fun column(set: SegmentFrameSet, id: SegmentId, axis: Int): Vector3 =
        Vector3(set.axis(id, axis, 0), set.axis(id, axis, 1), set.axis(id, axis, 2))

    private fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()

    private fun asin(value: Float): Float = kotlin.math.asin(value)

    private fun leastAligned(v: Vector3): Vector3 {
        val out = Vector3()
        SegmentRest.leastAlignedAxis(v.x, v.y, v.z, out)
        return out
    }

    private fun perpendicularTo(v: Vector3): Vector3 {
        val candidate = if (abs(v.x) < 0.9f) Vector3(1f, 0f, 0f) else Vector3(0f, 1f, 0f)
        return v.cross(candidate).normalizedCopy()
    }

    private fun rotate(v: Vector3, axis: Vector3, angle: Float): Vector3 {
        val k = axis.normalizedCopy()
        val c = cos(angle)
        val s = sin(angle)
        val dot = v.dot(k)
        return Vector3(
            v.x * c + (k.y * v.z - k.z * v.y) * s + k.x * dot * (1f - c),
            v.y * c + (k.z * v.x - k.x * v.z) * s + k.y * dot * (1f - c),
            v.z * c + (k.x * v.y - k.y * v.x) * s + k.z * dot * (1f - c)
        )
    }

}
