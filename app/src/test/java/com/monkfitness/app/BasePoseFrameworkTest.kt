package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class BasePoseFrameworkTest {

    @Test
    fun testPoseMetadataImmutableNewFields() {
        val metadata = PoseMetadata(
            name = "PushUp",
            pivotType = PivotType.FEET,
            supportContacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND),
            exerciseFamily = "PushUpFamily",
            defaultGrip = "Wide",
            motionType = "Push",
            bodyOrientation = "Prone"
        )
        assertEquals("PushUp", metadata.name)
        assertEquals(PivotType.FEET, metadata.pivotType)
        assertTrue(metadata.supportContacts.contains(SupportContact.LEFT_HAND))
        assertEquals("PushUpFamily", metadata.exerciseFamily)
        assertEquals("Wide", metadata.defaultGrip)
        assertEquals("Push", metadata.motionType)
        assertEquals("Prone", metadata.bodyOrientation)
    }

    @Test
    fun testPushUpPosePipelineMatches() {
        val standardPose = StandardPushUpPose()
        val widePose = WidePushUpPose()
        val kneePose = KneePushUpPose()
        val declinePose = DeclinePushUpPose()
        val diamondPose = DiamondPushUpPose()
        val militaryPose = MilitaryPushUpPose()

        val def = SkeletonDefinition.DEFAULT_ADULT
        val context = PoseContext(0.5f, Side.LEFT, def)

        // Evaluate builds at progress 0.5f (bottom of rep)
        val sPose = standardPose.build(context)
        val wPose = widePose.build(context)
        val kPose = kneePose.build(context)
        val dPose = declinePose.build(context)
        val dmPose = diamondPose.build(context)
        val mPose = militaryPose.build(context)

        assertNotNull(sPose)
        assertNotNull(wPose)
        assertNotNull(kPose)
        assertNotNull(dPose)
        assertNotNull(dmPose)
        assertNotNull(mPose)

        // Check hand alignments and standard values
        assertNotEquals(sPose.getJoint(Joint.HAND_A).z, wPose.getJoint(Joint.HAND_A).z, 1e-4f)
        assertNotEquals(sPose.getJoint(Joint.HAND_A).z, dmPose.getJoint(Joint.HAND_A).z, 1e-4f)
    }

    @Test
    fun testSquatPoseFramework() {
        val squat = SquatPose()
        val airSquat = AirSquatPose()

        val def = SkeletonDefinition.DEFAULT_ADULT
        val context = PoseContext(0.5f, Side.LEFT, def)

        val sPose = squat.build(context)
        val aPose = airSquat.build(context)

        assertNotNull(sPose)
        assertNotNull(aPose)
    }

    @Test
    fun testLungePoseFramework() {
        val lunge = AlternatingForwardLungesPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val context = PoseContext(0.25f, Side.LEFT, def)

        val pose = lunge.build(context)
        assertNotNull(pose)
    }
}
