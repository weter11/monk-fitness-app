package com.monkfitness.app.character

/** How often a vertex attribute's bytes change, and therefore which buffer ring slot uploads it. */
enum class TubeUpload { EVERY_FRAME, PER_BUILD }

/**
 * The vertex attributes this layer can declare, named exactly as Filament names them so a runtime
 * `Material.getRequiredAttributes()` maps by name without this file importing a platform type.
 */
enum class TubeAttribute(val bytesPerVertex: Int, val uploads: TubeUpload) {
    POSITION(12, TubeUpload.EVERY_FRAME),
    UV0(8, TubeUpload.PER_BUILD),
    UV1(4, TubeUpload.PER_BUILD),
    COLOR(4, TubeUpload.PER_BUILD),

    /** A tangent quaternion the shaded path does not ask for; carried so a refusal can name it. */
    TANGENTS(8, TubeUpload.PER_BUILD),

    /** Skinning belongs to the renderable, not to a generated rigid mesh; never supplied here. */
    BONE_INDICES(8, TubeUpload.PER_BUILD),
    BONE_WEIGHTS(16, TubeUpload.PER_BUILD)
}

/**
 * Which vertex-buffer slots a tube declares, or the reason it cannot draw with a given material.
 *
 * The decision is a value so a required attribute can never be silently missing: Filament only warns
 * about that and reads driver defaults, which shade a mesh with garbage rather than failing.
 */
sealed interface TubeAttributePlan {

    class Supported(val slots: List<TubeSlot>) : TubeAttributePlan {

        init {
            require(slots.isNotEmpty()) { "a vertex buffer needs at least one attribute" }
            require(slots.map { it.bufferIndex } == slots.indices.toList()) {
                "buffer indices ${slots.map { it.bufferIndex }} must be 0..${slots.size - 1}"
            }
        }

        val attributes: List<TubeAttribute> get() = slots.map { it.attribute }

        val bufferCount: Int get() = slots.size

        fun bufferIndexOf(attribute: TubeAttribute): Int =
            slots.firstOrNull { it.attribute == attribute }?.bufferIndex
                ?: throw IllegalArgumentException("attribute $attribute is not declared")

        /** Bytes one attribute needs for [vertexCount] vertices, on both sides of the upload. */
        fun bytesFor(attribute: TubeAttribute, vertexCount: Int): Int {
            bufferIndexOf(attribute)
            return vertexCount * attribute.bytesPerVertex
        }
    }

    class Refused(val reason: String) : TubeAttributePlan
}

object TubeAttributePlanner {

    /** The set the glTF ubershader declares: `requires : [uv0, uv1, color]` plus position. */
    val UBERSHADER_ATTRIBUTES: Set<TubeAttribute> =
        linkedSetOf(TubeAttribute.POSITION, TubeAttribute.UV0, TubeAttribute.UV1, TubeAttribute.COLOR)

    /**
     * Plans the slots for [required]. A material that asks for anything this layer cannot fill is
     * refused with its own names, so the diagnostics screen says which attribute is missing rather
     * than showing a mesh with one bone index and no colour.
     */
    fun plan(required: Collection<TubeAttribute>): TubeAttributePlan {
        val wanted = required.toSet()
        val unsupported = wanted.filter { it in UNSUPPORTED }
        if (unsupported.isNotEmpty()) {
            return TubeAttributePlan.Refused(
                "material requires " + unsupported.joinToString(", ") { it.name } + ", which a rigid tube does not supply"
            )
        }
        val declared = wanted + TubeAttribute.POSITION
        val ordered = TubeAttribute.entries.filter { it in declared }
        return TubeAttributePlan.Supported(ordered.mapIndexed { index, attribute -> TubeSlot(attribute, index) })
    }

    /** Plans from Filament's own attribute names, which is what a material reports at runtime. */
    fun planFromNames(names: Collection<String>): TubeAttributePlan {
        val mapped = ArrayList<TubeAttribute>(names.size)
        for (name in names) {
            val attribute = TubeAttribute.entries.firstOrNull { it.name == name }
                ?: return TubeAttributePlan.Refused("material requires unknown attribute $name")
            mapped += attribute
        }
        return plan(mapped)
    }

    private val UNSUPPORTED = setOf(TubeAttribute.TANGENTS, TubeAttribute.BONE_INDICES, TubeAttribute.BONE_WEIGHTS)
}

/** One attribute and the buffer slot it occupies. */
class TubeSlot(val attribute: TubeAttribute, val bufferIndex: Int)
