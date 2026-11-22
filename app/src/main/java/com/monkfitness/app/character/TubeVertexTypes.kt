package com.monkfitness.app.character

import com.google.android.filament.VertexBuffer

/**
 * The vertex-buffer type each declarable attribute is declared with.
 *
 * A material can report any attribute; this is the one place that decides which of them a rigid
 * tube can actually declare, so an attribute the layer cannot type is refused instead of reaching
 * Filament as a half-declared slot.
 */
internal object TubeVertexTypes {

    fun of(attribute: TubeAttribute): VertexBuffer.AttributeType? = when (attribute) {
        TubeAttribute.POSITION -> VertexBuffer.AttributeType.FLOAT3
        TubeAttribute.UV0 -> VertexBuffer.AttributeType.FLOAT2
        TubeAttribute.UV1 -> VertexBuffer.AttributeType.USHORT2
        TubeAttribute.COLOR -> VertexBuffer.AttributeType.UBYTE4
        TubeAttribute.TANGENTS, TubeAttribute.BONE_INDICES, TubeAttribute.BONE_WEIGHTS -> null
    }

    /** Whether the attribute's integer type is read as a normalized float by the shader. */
    fun isNormalized(attribute: TubeAttribute): Boolean =
        attribute == TubeAttribute.UV1 || attribute == TubeAttribute.COLOR
}
