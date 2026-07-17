package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class EnvironmentAnchorsTest {

    @Test
    fun testSupportMathAnchorResolution() {
        val anchor1 = EnvironmentAnchor("bar1", EnvironmentAnchorType.BAR, Vector3(0f, 500f, 0f))
        val anchor2 = EnvironmentAnchor("wall1", EnvironmentAnchorType.WALL, Vector3(-100f, 0f, 0f))
        val env = EnvironmentDefinition(
            anchors = listOf(anchor1, anchor2)
        )

        // 1. Resolve by ID
        val resolvedBar = SupportMath.resolveAnchor(env, "bar1")
        assertNotNull(resolvedBar)
        assertEquals(EnvironmentAnchorType.BAR, resolvedBar!!.type)
        assertEquals(500f, resolvedBar.worldPosition.y)

        val resolvedNonExistent = SupportMath.resolveAnchor(env, "non_existent")
        assertNull(resolvedNonExistent)

        // 2. Resolve Position
        val posBar = SupportMath.resolveAnchorPosition(env, "bar1", Vector3(0f, 0f, 0f))
        assertEquals(500f, posBar.y)

        val posFallback = SupportMath.resolveAnchorPosition(env, "non_existent", Vector3(12f, 34f, 56f))
        assertEquals(12f, posFallback.x)
        assertEquals(34f, posFallback.y)
        assertEquals(56f, posFallback.z)

        // 3. Resolve by Type
        val resolvedWallType = SupportMath.resolveAnchorByType(env, EnvironmentAnchorType.WALL)
        assertNotNull(resolvedWallType)
        assertEquals("wall1", resolvedWallType!!.id)

        val resolvedBenchType = SupportMath.resolveAnchorByType(env, EnvironmentAnchorType.BENCH)
        assertNull(resolvedBenchType)
    }

    @Test
    fun testHangPoseAnchorMetadataAndMigration() {
        val pose = HangPose()
        val metadata = pose.metadata

        // Check EnvironmentDefinition has anchor
        val anchors = metadata.environment.anchors
        assertEquals(1, anchors.size)
        val anchor = anchors[0]
        assertEquals("pullup_bar", anchor.id)
        assertEquals(EnvironmentAnchorType.BAR, anchor.type)
        assertEquals(500f, anchor.worldPosition.y)

        // Check SupportDefinition has correct contacts and anchor reference
        val support = metadata.support
        assertEquals(PivotType.HANDS, support.pivot)
        assertEquals(2, support.contacts.size)

        val leftHandContact = support.contacts.find { it.point == SupportPoint.LEFT_HAND }
        val rightHandContact = support.contacts.find { it.point == SupportPoint.RIGHT_HAND }

        assertNotNull("Left hand contact should be defined", leftHandContact)
        assertEquals("pullup_bar", leftHandContact!!.anchorId)
        assertNotNull("Right hand contact should be defined", rightHandContact)
        assertEquals("pullup_bar", rightHandContact!!.anchorId)

        // Test building with multiple progresses
        val context0 = PoseContext(progress = 0f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val pose0 = pose.build(context0)
        assertNotNull(pose0)
        assertEquals(242.91608f, pose0.getJoint(Joint.PELVIS).y, 1e-4f)

        val contextMid = PoseContext(progress = 0.5f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val poseMid = pose.build(contextMid)
        assertNotNull(poseMid)
        // Breathing sway is sin(0.5 * 2 * PI) = sin(PI) = 0. PelvisY unchanged at resting hang height.
        assertEquals(242.91608f, poseMid.getJoint(Joint.PELVIS).y, 0.1f)
    }

    @Test
    fun testStandardPullUpPoseAnchorMetadataAndMigration() {
        val pose = StandardPullUpPose()
        val metadata = pose.metadata

        // Check EnvironmentDefinition has anchor
        val anchors = metadata.environment.anchors
        assertEquals(1, anchors.size)
        val anchor = anchors[0]
        assertEquals("pullup_bar", anchor.id)
        assertEquals(EnvironmentAnchorType.BAR, anchor.type)
        assertEquals(500f, anchor.worldPosition.y)

        // Check SupportDefinition has correct contacts and anchor reference
        val support = metadata.support
        assertEquals(PivotType.HANDS, support.pivot)
        assertEquals(2, support.contacts.size)

        val leftHandContact = support.contacts.find { it.point == SupportPoint.LEFT_HAND }
        val rightHandContact = support.contacts.find { it.point == SupportPoint.RIGHT_HAND }

        assertNotNull("Left hand contact should be defined", leftHandContact)
        assertEquals("pullup_bar", leftHandContact!!.anchorId)
        assertNotNull("Right hand contact should be defined", rightHandContact)
        assertEquals("pullup_bar", rightHandContact!!.anchorId)

        // Test building with multiple progresses
        val context0 = PoseContext(progress = 0f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val pose0 = pose.build(context0)
        assertNotNull(pose0)
        assertEquals(240.92883f, pose0.getJoint(Joint.PELVIS).y, 1e-4f)

        val context1 = PoseContext(progress = 1f, side = Side.LEFT, definition = SkeletonDefinition.DEFAULT_ADULT)
        val pose1 = pose.build(context1)
        assertNotNull(pose1)
        assertEquals(285.35968f, pose1.getJoint(Joint.PELVIS).y, 1e-4f)
    }
}
