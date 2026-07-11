package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.DeclinePushUpPose
import org.junit.Assert.*
import org.junit.Test

class EnvironmentSystemTest {

    @Test
    fun testEnvironmentDefinitionAndProps() {
        val center = Vector3(10f, 20f, 30f)
        val box = BoxProp(center = center, width = 40f, height = 50f, depth = 60f)
        val step = StepProp(center = center, width = 10f, height = 15f, depth = 20f)
        val bench = BenchProp(center = center, width = 30f, height = 45f, depth = 80f)
        val wall = WallProp(center = center, width = 5f, height = 100f, depth = 200f)

        val env = EnvironmentDefinition(
            ground = GroundDefinition(visible = false, level = 15f),
            props = listOf(box, step, bench, wall)
        )

        assertFalse(env.ground.visible)
        assertEquals(15f, env.ground.level, 1e-4f)
        assertEquals(4, env.props.size)
        assertTrue(env.props[0] is BoxProp)
        assertTrue(env.props[1] is StepProp)
        assertTrue(env.props[2] is BenchProp)
        assertTrue(env.props[3] is WallProp)
    }

    @Test
    fun testDeclinePushUpMetadataContainsAuthoritativeBox() {
        val declinePose = DeclinePushUpPose()
        val metadata = declinePose.metadata

        assertNotNull(metadata.environment)
        val props = metadata.environment.props
        assertEquals(1, props.size)

        val firstProp = props[0]
        assertTrue("First prop must be a BoxProp", firstProp is BoxProp)
        val box = firstProp as BoxProp
        assertEquals(40f, box.height)
        assertEquals(60f + 210f * kotlin.math.cos(kotlin.math.asin(((60f - 65f) / 210f).coerceIn(-1f, 1f))) + 10f, box.center.x, 1e-3f)
        assertEquals(20f, box.center.y, 1e-3f)
        assertEquals(0f, box.center.z, 1e-3f)
        assertEquals(70f, box.width, 1e-3f)
        assertEquals(60f, box.depth, 1e-3f)
    }

    @Test
    fun testDefaultEnvironmentDefinition() {
        val metadata = PoseMetadata()
        assertTrue(metadata.environment.ground.visible)
        assertEquals(0f, metadata.environment.ground.level, 1e-4f)
        assertTrue(metadata.environment.props.isEmpty())
    }
}
