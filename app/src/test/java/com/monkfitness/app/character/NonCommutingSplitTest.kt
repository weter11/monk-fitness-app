package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parent and child swing about different axes: the world composition and the local inverse both hold. */
class NonCommutingSplitTest {

    private val rig = RetargetFixtures.chainRig(
        names = listOf("Hips", "LeftUpLeg", "LeftLeg", "LeftFoot"),
        parents = listOf(-1, 0, 1, 2),
        translations = listOf(
            Vector3(0f, 0.95f, 0f),
            Vector3(0f, -0.02f, 0.09f),
            Vector3(0f, -0.45f, 0f),
            Vector3(0f, -0.40f, 0f)
        )
    )
    private val bones = mapOf(
        Joint.PELVIS to 0,
        Joint.HIP_F to 1,
        Joint.KNEE_F to 2,
        Joint.ANKLE_F to 3
    )
    private val thigh = RetargetFixtures.axisAngle(Vector3(1f, 0f, 0f), 30f)

    private fun normalize(v: Vector3): Vector3 {
        val length = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Vector3(v.x / length, v.y / length, v.z / length)
    }

    @Test
    fun theChildComposesAfterTheParentAndItsLocalRotationIsTheInverseParentLeftMultiplied() {
        val bind = RetargetFixtures.bind
        val hip = bind.getValue(Joint.HIP_F)
        val upper = RetargetFixtures.rotate(thigh, Vector3(0f, -1f, 0f))
        // A swing perpendicular to the thigh: it is the minimal rotation the retarget can measure,
        // and it does not share the thigh's axis, so parent and child do not commute.
        val shinAxis = normalize(Vector3(upper.y, -upper.x, 0f))
        val shin = RetargetFixtures.axisAngle(shinAxis, 50f)
        val knee = Vector3(hip.x + upper.x * 112f, hip.y + upper.y * 112f, hip.z + upper.z * 112f)
        val lower = RetargetFixtures.rotate(shin, upper)
        val ankle = Vector3(knee.x + lower.x * 98f, knee.y + lower.y * 98f, knee.z + lower.z * 98f)
        val pose = RetargetFixtures.pose(
            mapOf(Joint.HIP_F to hip, Joint.KNEE_F to knee, Joint.ANKLE_F to ankle)
        )

        val matrices = RetargetFixtures.retargeter(rig).boneMatrices(pose, RetargetFixtures.unitsPerMetre)
        val hipBone = RetargetFixtures.boneMatrix(matrices, rig, Joint.HIP_F, bones)
        val kneeBone = RetargetFixtures.boneMatrix(matrices, rig, Joint.KNEE_F, bones)
        val hipWorld = RetargetFixtures.worldRotation(hipBone)
        val kneeWorld = RetargetFixtures.worldRotation(kneeBone)

        val bindDown = Vector3(0f, -1f, 0f)
        assertEquals(
            "the thigh must land on the published direction",
            0f,
            RetargetFixtures.angleBetween(RetargetFixtures.rotate(BoneMap.basis, RetargetFixtures.rotate(hipWorld, bindDown)), upper),
            0.01f
        )
        assertEquals(
            "the shin must land on the published direction",
            0f,
            RetargetFixtures.angleBetween(RetargetFixtures.rotate(BoneMap.basis, RetargetFixtures.rotate(kneeWorld, bindDown)), lower),
            0.01f
        )

        val expectedLocal = BoneMap.basis.conjugate() * (thigh.conjugate() * shin * thigh) * BoneMap.basis
        val local = hipWorld.conjugate() * kneeWorld
        assertEquals(
            "local rotation is the inverse parent left-multiplied onto the child",
            0f,
            RetargetFixtures.angleDegrees(local.conjugate() * expectedLocal),
            0.02f
        )
        assertTrue("a non-commuting pair must produce a non-identity local rotation", RetargetFixtures.angleDegrees(local) > 1f)

        // Dropping the parent factor lands the shin off the published direction; keeping this check
        // separate means the local-rotation assertion cannot be satisfied by a symmetric mistake.
        val correction = BoneMap.basis * kneeWorld * hipWorld.conjugate() * BoneMap.basis.conjugate()
        val withoutParent = BoneMap.basis.conjugate() * correction * BoneMap.basis
        val missed = RetargetFixtures.angleBetween(
            RetargetFixtures.rotate(BoneMap.basis, RetargetFixtures.rotate(withoutParent, bindDown)),
            lower
        )
        assertTrue("a composition with no parent factor must miss the published direction (missed $missed)", missed > 1f)
    }
}
