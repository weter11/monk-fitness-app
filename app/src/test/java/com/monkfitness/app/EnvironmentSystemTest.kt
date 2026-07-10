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
            groundVisible = false,
            props = listOf(box, step, bench, wall)
        )

        assertFalse(env.groundVisible)
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
        assertEquals(150f, box.center.x)
        assertEquals(20f, box.center.y)
        assertEquals(0f, box.center.z)
        assertEquals(60f, box.width)
        assertEquals(60f, box.depth)
    }

    @Test
    fun testDefaultEnvironmentDefinition() {
        val metadata = PoseMetadata()
        assertTrue(metadata.environment.groundVisible)
        assertTrue(metadata.environment.props.isEmpty())
    }
}
