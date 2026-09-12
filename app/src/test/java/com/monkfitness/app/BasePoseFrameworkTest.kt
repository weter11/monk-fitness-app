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
            // B-2 — the single support declaration channel. The former `pivotType` /
            // `supportContacts` duplicates are gone: a pose that declared support on that
            // write-only channel (the two planks) silently published an EMPTY support model.
            support = SupportDefinition(
                pivot = PivotType.FEET,
                contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
            ),
            exerciseFamily = "PushUpFamily",
            defaultGrip = "Wide",
            motionType = "Push",
            bodyOrientation = "Prone"
        )
        assertEquals("PushUp", metadata.name)
        assertEquals(PivotType.FEET, metadata.support.pivot)
        assertTrue(metadata.support.contacts.contains(SupportContact.LEFT_HAND))
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

        // Evaluate the PUBLISHED frames of the production pipeline at progress 0.5f (bottom of rep).
        // P12 WP-I: `build()` alone registers limb intent (§12.7a) — the hands are realized by the
        // engine-owned stage, so hand-alignment readings must come from the frame the engine
        // produces. The claim below (the variants differ in hand placement) is unchanged.
        val sPose = SkeletonPipeline(def).produceFrame(standardPose, context).pose
        val wPose = SkeletonPipeline(def).produceFrame(widePose, context).pose
        val kPose = SkeletonPipeline(def).produceFrame(kneePose, context).pose
        val dPose = SkeletonPipeline(def).produceFrame(declinePose, context).pose
        val dmPose = SkeletonPipeline(def).produceFrame(diamondPose, context).pose
        val mPose = SkeletonPipeline(def).produceFrame(militaryPose, context).pose

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
