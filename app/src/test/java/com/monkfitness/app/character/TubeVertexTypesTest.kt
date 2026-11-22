package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vertex-buffer types the tube's attributes are declared with.
 *
 * A material reports what it needs; the layer decides whether it can declare it. These pin that the
 * two sides cannot drift: anything the planner is willing to place has a type, anything it refuses
 * has none, and an integer type is always normalized so the shader reads it as a float.
 */
class TubeVertexTypesTest {

    @Test
    fun everyAttributeAPlannerCanPlaceHasAVertexType() {
        for (attribute in TubeAttribute.entries) {
            val plannable = TubeAttributePlanner.plan(setOf(attribute)) is TubeAttributePlan.Supported
            if (plannable) {
                assertNotNull("$attribute is plannable, so it needs a vertex type", TubeVertexTypes.of(attribute))
                assertNotNull("$attribute is plannable, so it needs a vertex slot", TubeVertexTypes.slot(attribute))
            } else {
                assertNull("$attribute is refused by the planner, so it has no vertex type", TubeVertexTypes.of(attribute))
                assertNull("$attribute is refused by the planner, so it has no vertex slot", TubeVertexTypes.slot(attribute))
            }
        }
    }

    @Test
    fun everyAttributeOfASupportedPlanIsTyped() {
        val plan = TubeAttributePlanner.plan(TubeAttributePlanner.UBERSHADER_ATTRIBUTES)
        assertTrue("the ubershader's own attribute set must plan", plan is TubeAttributePlan.Supported)
        for (attribute in (plan as TubeAttributePlan.Supported).attributes) {
            assertNotNull("$attribute is in the plan but has no vertex type", TubeVertexTypes.of(attribute))
        }
    }

    @Test
    fun anIntegerAttributeIsAlwaysNormalized() {
        for (attribute in TubeAttribute.entries) {
            val type = TubeVertexTypes.of(attribute) ?: continue
            val integer = type == com.google.android.filament.VertexBuffer.AttributeType.USHORT2 ||
                type == com.google.android.filament.VertexBuffer.AttributeType.UBYTE4
            assertEquals(
                "$attribute must be normalized exactly when its type is an integer type",
                integer,
                TubeVertexTypes.isNormalized(attribute),
            )
        }
    }
}
