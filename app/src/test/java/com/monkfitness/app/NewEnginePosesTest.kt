package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class NewEnginePosesTest {

    private val context0 = PoseContext(
        progress = 0f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 3000f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    private val context1 = PoseContext(
        progress = 1f,
        side = Side.LEFT,
        definition = SkeletonDefinition.DEFAULT_ADULT,
        deltaTime = 16.6f,
        cycleDuration = 3000f,
        playbackSpeed = 1f,
        mirrored = false,
        phase = 0f,
        loopIndex = 0
    )

    @Test
    fun testProneCobraStretchPoseBuildsCorrectly() {
        val pose = ProneCobraStretchPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should rest at 15f on the floor at start", 15f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should remain at 15f on the floor at end", 15f, pelvisY1, 1e-4f)
    }

    @Test
    fun testIsometricSidePlankPoseBuildsCorrectly() {
        val pose = IsometricSidePlankPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at resting position (15f)", 15f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to side plank height (35f)", 35f, pelvisY1, 1e-4f)
    }

    @Test
    fun testStaticForearmPlankPoseBuildsCorrectly() {
        val pose = StaticForearmPlankPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at resting position (15f)", 15f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to plank height (35f)", 35f, pelvisY1, 1e-4f)
    }

    @Test
    fun testStandardPullUpPoseBuildsCorrectly() {
        val pose = StandardPullUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang", 240.92883f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to full contraction", 285.35968f, pelvisY1, 1e-4f)
    }

    @Test
    fun testUnderhandChinUpPoseBuildsCorrectly() {
        val pose = UnderhandChinUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang", 240.07559f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift slightly higher", 286.1499f, pelvisY1, 1e-4f)
    }

    @Test
    fun testNeutralGripPullUpPoseBuildsCorrectly() {
        val pose = NeutralGripPullUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang", 240.0f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to full contraction", 284.52225f, pelvisY1, 1e-4f)
    }

    @Test
    fun testWideGripPullUpPoseBuildsCorrectly() {
        val pose = WideGripPullUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang", 247.77292f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift", 291.2081f, pelvisY1, 1e-4f)
    }

    @Test
    fun testHangPoseBuildsCorrectly() {
        val pose = HangPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        // At progress = 0f, breathingSwayY = sin(0) * 2f = 0f. PelvisY = 242.91608f (bar 500 - arm - torso).
        assertEquals("Pelvis Y should start at resting hang height", 242.91608f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        // At progress = 1f, cycle = 2f * PI. sin(2*PI) = 0f. PelvisY unchanged.
        assertEquals("Pelvis Y should end at resting hang height", 242.91608f, pelvisY1, 1e-4f)
    }

    @Test
    fun testAlternatingForwardLungesPoseBuildsCorrectly() {
        val pose = AlternatingForwardLungesPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x
        assertEquals("Pelvis X should start at 0f", 0f, pelvisX0, 1e-4f)

        // At progress = 0.25f, cycle = PI/2, sin(cycle) = 1.0f. activeDrop = 1f, pelvisX is the authored step amplitude.
        val contextMid = PoseContext(progress = 0.25f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val resultMid = pose.build(contextMid)
        val pelvisXMid = resultMid.getJoint(Joint.PELVIS).x
        assertEquals("Pelvis X should shift forward on step", 54.559998f, pelvisXMid, 1e-4f)
    }

    @Test
    fun testAlternatingReverseLungesPoseBuildsCorrectly() {
        val pose = AlternatingReverseLungesPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisX0 = result0.getJoint(Joint.PELVIS).x
        assertEquals("Pelvis X should start at 0f", 0f, pelvisX0, 1e-4f)

        // At progress = 0.25f, cycle = PI/2, sin(cycle) = 1.0f. activeDrop = 1f, pelvisX is the authored step amplitude.
        val contextMid = PoseContext(progress = 0.25f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val resultMid = pose.build(contextMid)
        val pelvisXMid = resultMid.getJoint(Joint.PELVIS).x
        assertEquals("Pelvis X should shift backward on step", -53.32f, pelvisXMid, 1e-4f)
    }

    @Test
    fun testAlternatingSideLungesPoseBuildsCorrectly() {
        val pose = AlternatingSideLungesPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisZ0 = result0.getJoint(Joint.PELVIS).z
        assertEquals("Pelvis Z should start at 0f", 0f, pelvisZ0, 1e-4f)

        // At progress = 0.25f, cycle = PI/2, sin(cycle) = 1.0f. activeDrop = 1f, pelvisZ is the authored step amplitude.
        val contextMid = PoseContext(progress = 0.25f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val resultMid = pose.build(contextMid)
        val pelvisZMid = resultMid.getJoint(Joint.PELVIS).z
        assertEquals("Pelvis Z should shift sideways on step", 36.0f, pelvisZMid, 1e-4f)
    }

    @Test
    fun testRegistryIntegration() {
        // Verify they are successfully registered and retrieved
        val cobraConfig = PoseRegistry.getPoseConfig("cobra_stretch_hold")
        assertNotNull("cobra_stretch_hold should be registered", cobraConfig)
        assertTrue(cobraConfig!!.builder is ProneCobraStretchPose)

        val sidePlankConfig = PoseRegistry.getPoseConfig("side_plank_standard")
        assertNotNull("side_plank_standard should be registered", sidePlankConfig)
        assertTrue(sidePlankConfig!!.builder is IsometricSidePlankPose)

        val plankConfig = PoseRegistry.getPoseConfig("plank_standard")
        assertNotNull("plank_standard should be registered", plankConfig)
        assertTrue(plankConfig!!.builder is StaticForearmPlankPose)

        val pullupConfig = PoseRegistry.getPoseConfig("pullup_standard")
        assertNotNull("pullup_standard should be registered", pullupConfig)
        assertTrue(pullupConfig!!.builder is StandardPullUpPose)

        val chinupConfig = PoseRegistry.getPoseConfig("chinup_standard")
        assertNotNull("chinup_standard should be registered", chinupConfig)
        assertTrue(chinupConfig!!.builder is UnderhandChinUpPose)

        val neutralPullupConfig = PoseRegistry.getPoseConfig("pullup_neutral")
        assertNotNull("pullup_neutral should be registered", neutralPullupConfig)
        assertTrue(neutralPullupConfig!!.builder is NeutralGripPullUpPose)

        val widePullupConfig = PoseRegistry.getPoseConfig("pullup_wide")
        assertNotNull("pullup_wide should be registered", widePullupConfig)
        assertTrue(widePullupConfig!!.builder is WideGripPullUpPose)

        val hangConfig = PoseRegistry.getPoseConfig("dead_hang")
        assertNotNull("dead_hang should be registered", hangConfig)
        assertTrue(hangConfig!!.builder is HangPose)

        val lungeForwardConfig = PoseRegistry.getPoseConfig("lunge_forward")
        assertNotNull("lunge_forward should be registered", lungeForwardConfig)
        assertTrue(lungeForwardConfig!!.builder is AlternatingForwardLungesPose)

        val lungeReverseConfig = PoseRegistry.getPoseConfig("lunge_reverse")
        assertNotNull("lunge_reverse should be registered", lungeReverseConfig)
        assertTrue(lungeReverseConfig!!.builder is AlternatingReverseLungesPose)

        val lungeSideConfig = PoseRegistry.getPoseConfig("lunge_side")
        assertNotNull("lunge_side should be registered", lungeSideConfig)
        assertTrue(lungeSideConfig!!.builder is AlternatingSideLungesPose)
    }
}
