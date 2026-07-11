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
        assertEquals("Pelvis Y should start at deep hang (230f)", 230f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to full contraction (380f)", 380f, pelvisY1, 1e-4f)
    }

    @Test
    fun testUnderhandChinUpPoseBuildsCorrectly() {
        val pose = UnderhandChinUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang (230f)", 230f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift slightly higher (395f)", 395f, pelvisY1, 1e-4f)
    }

    @Test
    fun testNeutralGripPullUpPoseBuildsCorrectly() {
        val pose = NeutralGripPullUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang (230f)", 230f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift to full contraction (395f)", 395f, pelvisY1, 1e-4f)
    }

    @Test
    fun testWideGripPullUpPoseBuildsCorrectly() {
        val pose = WideGripPullUpPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should start at deep hang (230f)", 230f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        assertEquals("Pelvis Y should lift (360f)", 360f, pelvisY1, 1e-4f)
    }

    @Test
    fun testHangPoseBuildsCorrectly() {
        val pose = HangPose()
        assertNotNull(pose.metadata)

        val result0 = pose.build(context0)
        assertNotNull(result0)
        val pelvisY0 = result0.getJoint(Joint.PELVIS).y
        // At progress = 0f, breathingSwayY = sin(0) * 2f = 0f. PelvisY = 220f.
        assertEquals("Pelvis Y should start at resting hang height (220f)", 220f, pelvisY0, 1e-4f)

        val result1 = pose.build(context1)
        assertNotNull(result1)
        val pelvisY1 = result1.getJoint(Joint.PELVIS).y
        // At progress = 1f, cycle = 2f * PI. sin(2*PI) = 0f. PelvisY = 220f.
        assertEquals("Pelvis Y should end at resting hang height (220f)", 220f, pelvisY1, 1e-4f)
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
    }
}
