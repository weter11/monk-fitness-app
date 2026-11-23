package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counts the tube's vertex uploads take.
 *
 * `VertexBuffer.setBufferAt` counts *elements of the buffer it is handed*: the JNI shifts the caller's
 * count by the buffer's element size before sizing the descriptor. A `FloatBuffer` therefore takes one
 * count per float and a `ByteBuffer` one per byte, and passing a float buffer's byte count overruns it
 * — Filament answers with `BufferOverflowException` instead of uploading, which on the render thread
 * ends the session rather than drawing it.
 */
class TubeUploadSizeTest {

    private val plan = TubeAttributePlanner.plan(TubeAttributePlanner.UBERSHADER_ATTRIBUTES)
        as TubeAttributePlan.Supported
    private val layout = TubeVertexLayout(plan, vertexCapacity = 6_912, indexCapacity = 41_472)

    @Test
    fun aFloatUploadCountsComponentsAndNotBytes() {
        val vertices = layout.vertexCapacity
        for (attribute in listOf(TubeAttribute.POSITION, TubeAttribute.UV0)) {
            val components = attribute.bytesPerVertex / 4
            assertEquals(
                "a float buffer upload of $vertices vertices of $attribute takes $components counts each",
                vertices * components,
                layout.floatUploadCount(attribute, vertices),
            )
            assertTrue(
                "$attribute's byte count would overrun a float buffer of the same run",
                layout.floatUploadCount(attribute, vertices) < vertices * attribute.bytesPerVertex,
            )
        }
    }

    @Test
    fun aByteUploadCountsEveryByteOfTheStride() {
        val vertices = layout.vertexCapacity
        for (attribute in listOf(TubeAttribute.UV1, TubeAttribute.COLOR)) {
            assertEquals(
                "a byte buffer upload of $vertices vertices of $attribute covers its whole stride",
                vertices * attribute.bytesPerVertex,
                layout.byteUploadCount(attribute, vertices),
            )
        }
    }

    @Test
    fun primingAFullCapacityFillsItsBufferExactly() {
        // The ring is allocated one vertex's worth per attribute: a prime that asks for more throws
        // out of the JNI instead of uploading, and the layer then never builds.
        for (attribute in plan.attributes) {
            val floats = TubeVertexTypes.of(attribute) ==
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT2 ||
                TubeVertexTypes.of(attribute) ==
                com.google.android.filament.VertexBuffer.AttributeType.FLOAT3
            val requested = if (floats) {
                layout.floatUploadCount(attribute, layout.vertexCapacity)
            } else {
                layout.byteUploadCount(attribute, layout.vertexCapacity)
            }
            assertEquals(
                "priming $attribute must fill its buffer exactly, never past it",
                layout.vertexCapacity * attribute.bytesPerVertex,
                if (floats) requested * 4 else requested,
            )
        }
    }
}
