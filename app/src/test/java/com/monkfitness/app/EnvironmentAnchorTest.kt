package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

class EnvironmentAnchorTest {

    @Test
    fun testEnvironmentAnchorLookupHelpers() {
        val barAnchor = EnvironmentAnchor(
            id = "test_bar",
            type = EnvironmentAnchorType.BAR,
            worldPosition = Vector3(0f, 500f, 0f)
        )
        val envDef = EnvironmentDefinition(
            anchors = listOf(barAnchor)
        )

        val resolved = SupportMath.findAnchor(envDef, "test_bar")
        assertNotNull(resolved)
        assertEquals("test_bar", resolved!!.id)
        assertEquals(EnvironmentAnchorType.BAR, resolved.type)
        assertEquals(500f, resolved.worldPosition.y, 1e-4f)

        val notFound = SupportMath.findAnchor(envDef, "nonexistent")
        assertNull(notFound)

        val posWithFallback = SupportMath.getAnchorPosition(envDef, "nonexistent", Vector3(1f, 2f, 3f))
        assertEquals(1f, posWithFallback.x, 1e-4f)
        assertEquals(2f, posWithFallback.y, 1e-4f)
        assertEquals(3f, posWithFallback.z, 1e-4f)
    }

    @Test
    fun testHangAndPullUpPosesWithEnvironmentAnchors() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.RIGHT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )

        // 1. Verify HangPose builds correctly and resolves anchor
        val hangPose = HangPose()
        val rawHang = hangPose.build(context)
        val finalizedHang = finalizer.finalize(rawHang)

        assertNotNull(finalizedHang)
        assertTrue(finalizedHang.isTransformsUpdated)

        val barAnchorInHang = SupportMath.findAnchor(hangPose.metadata.environment ?: EnvironmentDefinition(), "pullup_bar")
        assertNotNull(barAnchorInHang)
        assertEquals(500f, barAnchorInHang!!.worldPosition.y, 1e-4f)

        // 2. Verify StandardPullUpPose builds correctly and resolves anchor
        val pullupPose = StandardPullUpPose()
        val rawPullup = pullupPose.build(context)
        val finalizedPullup = finalizer.finalize(rawPullup)

        assertNotNull(finalizedPullup)
        assertTrue(finalizedPullup.isTransformsUpdated)

        val barAnchorInPullup = SupportMath.findAnchor(pullupPose.metadata.environment ?: EnvironmentDefinition(), "pullup_bar")
        assertNotNull(barAnchorInPullup)
        assertEquals(500f, barAnchorInPullup!!.worldPosition.y, 1e-4f)
    }
}
