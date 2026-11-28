package com.monkfitness.app.character

import com.google.android.filament.Box
import com.google.android.filament.BufferObject
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SkinningBuffer
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.UbershaderProvider
import com.monkfitness.app.anatomy.AnatomyFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One partition's exported skin: its GLB bytes and the palette that drives it, under the label its
 * diagnostics use. The label is the only identity the renderer holds, so no muscle or layer type
 * crosses this seam.
 */
internal class MuscleLayerAsset(
    val label: String,
    val bytes: ByteArray,
    val skinner: MuscleLayerSkinner
)

/**
 * The authored muscle layers: one merged skinned renderable per partition, driven by the same palette
 * the body uploads followed by one bone per helper, so a solved path moves the mesh without touching
 * a vertex on the CPU.
 *
 * A partition's sections are concatenated into one vertex buffer and one index buffer, so the scene
 * holds two skinned renderables — not one per muscle and side — and each partition uploads one bone
 * buffer per frame. Sections stay addressable by their slice: hiding one zeroes its four skin weights,
 * so the GPU sends every vertex of it to the origin in every pose, and showing it restores the
 * authored weights — both only when the mask actually changes; a selection rewrites the vertex colour
 * of the two affected sections and nothing else.
 * Sections are addressed by index into the keys handed in, and each key names the asset that carries
 * it, so no muscle identity, side or solve verdict crosses into this class.
 */
internal class MuscleLayerStage(
    private val engine: Engine,
    private val scene: Scene,
    private val provider: UbershaderProvider?,
    assets: List<MuscleLayerAsset>,
    sectionKeys: List<String>,
    sectionAssets: IntArray
) {

    private val layers: Array<Layer> = Array(assets.size) { Layer(assets[it]) }

    private val keys: List<String> = sectionKeys.toList()

    /** Which asset carries each section, and its slice inside that asset's merged buffer. */
    private val sectionLayer: IntArray
    private val sectionLocal: IntArray

    /** The keys each asset carries, in the frame's own section order. */
    private val layerKeys: Array<Array<String>>

    /** Every global section of each asset, in the order its slice occupies. */
    private val layerSections: Array<IntArray>

    /** One mask bit and one highlight per section; the merged renderable carries both in geometry. */
    val state = MuscleSectionState(keys.size)

    private val renderableManager: RenderableManager = engine.renderableManager

    private var prepared = false

    /** True once the buffers are freed, so a second teardown cannot touch native handles twice. */
    private var destroyed = false

    /** Sections the loaded partitions publish between them; the readout's `mesh total`. */
    var renderableCount: Int = 0
        private set

    /** Frames whose palettes reached the GPU; a frozen counter means the upload never ran. */
    var skinUploads: Long = 0
        private set

    /** Mask-driven weight rewrites; a steady frame must not move it. */
    var weightWrites: Int = 0
        private set

    /** Selection-driven colour rewrites; a steady frame must not move it. */
    var colorWrites: Int = 0
        private set

    /** Why an asset could not be built, or null when every partition loaded. */
    var failure: String? = null
        private set

    /** Why a selection cannot tint its section, or null when the highlight is live. */
    var highlightError: String? = null
        private set

    /** True while the selected section's colour is the highlight's. */
    var highlightApplied: Boolean = false
        private set

    init {
        require(assets.isNotEmpty()) { "the layer needs at least one asset" }
        require(sectionAssets.size == keys.size) { "$sectionAssets marks ${keys.size} sections" }
        // Two instances under one key would tie one instance's mask to another's draw.
        require(keys.toSet().size == keys.size) { "two instances share a section: $keys" }
        sectionLayer = IntArray(keys.size)
        sectionLocal = IntArray(keys.size)
        val counts = IntArray(assets.size)
        for (section in keys.indices) {
            val layer = sectionAssets[section]
            require(layer in assets.indices) { "section $section names asset $layer of ${assets.size}" }
            sectionLayer[section] = layer
            sectionLocal[section] = counts[layer]
            counts[layer]++
        }
        for (index in assets.indices) {
            require(counts[index] > 0) { "asset ${assets[index].label} carries no section" }
        }
        val collected = Array(assets.size) { index -> ArrayList<String>(counts[index]) }
        val sections = Array(assets.size) { index -> ArrayList<Int>(counts[index]) }
        for (section in keys.indices) {
            collected[sectionLayer[section]] += keys[section]
            sections[sectionLayer[section]] += section
        }
        layerKeys = Array(assets.size) { index -> collected[index].toTypedArray() }
        layerSections = Array(assets.size) { index -> sections[index].toIntArray() }
    }

    private val highlightSelection = MuscleHighlightSelection(object : MuscleHighlightTarget {
        override fun setHighlight(section: Int, on: Boolean) {
            if (section !in keys.indices) return
            val layer = layers[sectionLayer[section]]
            if (layer.slices.isEmpty()) return
            layer.paint(sectionLocal[section], on)
            uploadStream(layer, COLOR_SLOT, layer.colors)
            colorWrites++
        }
    })

    /** How many sections the last frame showed. */
    val visibleCount: Int get() = state.shownCount

    /**
     * Builds one merged renderable per partition. Returns the failures joined, or null when both
     * skins draw; a refusal is per asset and leaves the other one live.
     */
    fun prepare(): String? {
        if (prepared) return failure
        prepared = true
        val materialProvider = provider ?: run {
            for (index in layers.indices) layers[index].failure = "the layer has no material provider"
            return publishFailures()
        }
        for (index in layers.indices) load(index, materialProvider)
        renderableCount = layers.filter { it.entities.isNotEmpty() }.sumOf { it.slices.size }
        return publishFailures()
    }

    /**
     * Skins one solved frame into each partition's buffer and uploads it once per partition. [body]
     * is the rig's own bone matrices; each partition's helpers follow them, so one frame drives both
     * skins.
     */
    fun skin(frame: AnatomyFrame, body: FloatArray): Boolean {
        var uploaded = false
        for (index in layers.indices) {
            val layer = layers[index]
            val buffer = layer.skinningBuffer ?: continue
            layer.skinner.write(frame, body, layer.matrices)
            layer.palette.clear()
            layer.palette.put(layer.matrices)
            layer.palette.rewind()
            buffer.setBonesAsMatrices(engine, layer.palette, layer.jointCount, 0)
            uploaded = true
        }
        if (uploaded) skinUploads++
        return uploaded
    }

    /** Hides or shows one section; only a changed mask rewrites its weights. */
    fun setShown(section: Int, on: Boolean) {
        if (!state.show(section, on)) return
        val index = sectionLayer[section]
        val layer = layers[index]
        val weights = layer.weights ?: return
        if (!weights.setHidden(sectionLocal[section], hidden = !on)) return
        uploadStream(layer, BONE_WEIGHTS_SLOT, floatBytes(weights.weights))
        weightWrites++
        applyMask(index)
    }

    /** Moves the highlight onto one section's vertices; only a moved selection rewrites colours. */
    fun select(section: Int) {
        val target = if (section in keys.indices && layers[sectionLayer[section]].slices.isNotEmpty()) {
            section
        } else {
            MuscleSectionState.NO_SECTION
        }
        highlightSelection.select(target)
        state.select(target)
        highlightApplied = target != MuscleSectionState.NO_SECTION
    }

    fun hideAll() {
        for (section in keys.indices) setShown(section, false)
        select(MuscleSectionState.NO_SECTION)
    }

    /** Skips a partition's draw when every one of its sections is hidden. */
    private fun applyMask(index: Int) {
        val layer = layers[index]
        if (layer.entities.isEmpty()) return
        var shown = false
        for (section in layerSections[index]) {
            if (state.draws(section)) {
                shown = true
                break
            }
        }
        renderableManager.setLayerMask(layer.entities[0], LAYER_BIT, if (shown) LAYER_BIT else 0)
    }

    /** Drains the render thread, then releases each partition's buffers, instance and skin. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        // A queued frame referencing a freed instance or buffer is a native crash inside FEngine::loop.
        engine.flushAndWait()
        highlightSelection.select(MuscleSectionState.NO_SECTION)
        for (index in layers.indices) {
            val layer = layers[index]
            if (layer.entities.isNotEmpty()) scene.removeEntities(layer.entities)
            for (entity in layer.entities) if (entity != 0) engine.destroy(entity)
            layer.entities = IntArray(0)
            layer.material?.let { engine.destroyMaterialInstance(it) }
            layer.material = null
            for (buffer in layer.bufferObjects) engine.destroyBufferObject(buffer)
            layer.bufferObjects.clear()
            layer.vertexBuffer?.let { engine.destroy(it) }
            layer.vertexBuffer = null
            layer.indexBuffer?.let { engine.destroy(it) }
            layer.indexBuffer = null
            layer.skinningBuffer?.let { engine.destroySkinningBuffer(it) }
            layer.skinningBuffer = null
            layer.jointCount = 0
            layer.matrices = FloatArray(0)
        }
        renderableCount = 0
        state.hideAll()
        highlightApplied = false
    }

    private fun load(index: Int, materialProvider: UbershaderProvider) {
        val layer = layers[index]
        var shared: SkinningBuffer? = null
        try {
            val mesh = MuscleMeshAsset.read(layer.bytes)
            layer.jointCount = mesh.jointCount
            require(layer.jointCount == layer.skinner.paletteCount) {
                "${layer.label} skins ${layer.jointCount} joints, its palette has ${layer.skinner.paletteCount}"
            }
            val byName = HashMap<String, MuscleSectionMesh>(mesh.sections.size * 2)
            for (section in mesh.sections) {
                if (byName.put(section.name, section) != null) {
                    throw IllegalStateException("${layer.label} names two sections ${section.name}")
                }
            }
            val ordered = layerKeys[index].map { key ->
                byName[key] ?: throw IllegalStateException("${layer.label} has no section named $key")
            }
            val layout = MuscleSectionLayout.of(ordered)

            val instance = instanceFor(materialProvider, layer.label)
            val missing = APPEARANCE_PARAMETERS.filterNot(instance.material::hasParameter)
            if (missing.isNotEmpty()) {
                val reason = "the material of ${layer.label} has no ${missing.joinToString()}"
                if (HIGHLIGHT_PARAMETER in missing) layer.highlightError = reason
                throw IllegalStateException(reason)
            }
            instance.setParameter(BASE_COLOR_PARAMETER, 1f, 1f, 1f, 1f)
            instance.setParameter(METALLIC_PARAMETER, 0f)
            instance.setParameter(ROUGHNESS_PARAMETER, MUSCLE_ROUGHNESS)
            instance.setParameter(HIGHLIGHT_PARAMETER, 0f, 0f, 0f)
            layer.material = instance

            shared = SkinningBuffer.Builder()
                .boneCount(mesh.jointCount)
                .initialize()
                .build(engine)
            layer.skinningBuffer = shared

            layer.slices = layout.slices
            layer.bindPositions = mergedPositions(ordered, layout)
            layer.weights = MuscleSectionWeights(mergedWeights(ordered, layout), layout.slices)
            layer.colors = baseColors(layout.vertexCount)

            val entity = EntityManager.get().create()
            layer.entities = intArrayOf(entity)
            layer.vertexBuffer = buildVertexBuffer(ordered, layout, layer)
            val indices = mergedIndices(ordered, layout)
            val indexBuffer = IndexBuffer.Builder()
                .indexCount(indices.size)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            layer.indexBuffer = indexBuffer
            val indexUpload = intBuffer(indices)
            layer.uploads += indexUpload
            indexBuffer.setBuffer(engine, indexUpload, 0, indices.size * 4)

            // A scene entity without a transform reads an unset component on every frame.
            engine.transformManager.create(entity)
            RenderableManager.Builder(1)
                .boundingBox(boxOf(layer.bindPositions))
                .culling(false)
                .castShadows(false)
                .receiveShadows(false)
                // A skinned renderable is DYNAMIC to Filament even though only the GPU moves the
                // vertices: the engine refuses skinning on STATIC geometry.
                .geometryType(RenderableManager.Builder.GeometryType.DYNAMIC)
                .geometry(
                    0, RenderableManager.PrimitiveType.TRIANGLES,
                    layer.vertexBuffer!!, indexBuffer, 0, indices.size
                )
                .material(0, instance)
                .enableSkinningBuffers(true)
                .skinning(shared, mesh.jointCount, 0)
                .build(engine, entity)
            scene.addEntity(entity)
            renderableManager.setLayerMask(entity, LAYER_BIT, LAYER_BIT)

            layer.matrices = FloatArray(mesh.jointCount * 16)
            layer.palette = ByteBuffer.allocateDirect(mesh.jointCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        } catch (error: Throwable) {
            layer.failure = "${layer.label}: " +
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            if (layer.entities.isNotEmpty()) scene.removeEntities(layer.entities)
            for (entity in layer.entities) if (entity != 0) engine.destroy(entity)
            layer.material?.let { engine.destroyMaterialInstance(it) }
            layer.material = null
            for (buffer in layer.bufferObjects) engine.destroyBufferObject(buffer)
            layer.bufferObjects.clear()
            layer.vertexBuffer?.let { engine.destroy(it) }
            layer.vertexBuffer = null
            layer.indexBuffer?.let { engine.destroy(it) }
            layer.indexBuffer = null
            shared?.let { engine.destroySkinningBuffer(it) }
            layer.skinningBuffer = null
            layer.entities = IntArray(0)
            layer.slices = emptyList()
            layer.jointCount = 0
            layer.weights = null
        }
    }

    /**
     * One material instance per partition: the provider's untextured variant with vertex colours, so
     * the section tint is a vertex stream rather than a per-section instance.
     */
    private fun instanceFor(materialProvider: UbershaderProvider, label: String): MaterialInstance {
        val key = MaterialProvider.MaterialKey()
        key.hasVertexColors = true
        val instance = materialProvider.createMaterialInstance(key, intArrayOf(0, 0), "muscle-$label", null)
            ?: throw IllegalStateException("the provider has no material for $label")
        val missing = instance.material.requiredAttributes - REQUIRED_ATTRIBUTES
        if (missing.isNotEmpty()) {
            engine.destroyMaterialInstance(instance)
            throw IllegalStateException(
                "the material of $label needs $missing, the section layout supplies $REQUIRED_ATTRIBUTES"
            )
        }
        return instance
    }

    /** One vertex stream: the attribute it fills, how it is typed, and the bytes it carries. */
    private class Stream(
        val attribute: VertexBuffer.VertexAttribute,
        val type: VertexBuffer.AttributeType,
        val normalized: Boolean,
        val bytes: ByteArray
    )

    private fun buildVertexBuffer(
        sections: List<MuscleSectionMesh>,
        layout: MuscleSectionLayout,
        layer: Layer
    ): VertexBuffer {
        val weights = layer.weights
            ?: throw IllegalStateException("${layer.label} builds a vertex buffer with no weights")
        val streams = streams(sections, layout, weights.weights)
        val builder = VertexBuffer.Builder()
            .vertexCount(layout.vertexCount)
            .bufferCount(streams.size)
            .enableBufferObjects(true)
        for ((index, stream) in streams.withIndex()) {
            builder.attribute(stream.attribute, index, stream.type)
            if (stream.normalized) builder.normalized(stream.attribute)
        }
        val vertexBuffer = builder.build(engine)
        require(vertexBuffer.vertexCount == layout.vertexCount) {
            "${layer.label} built ${vertexBuffer.vertexCount} vertices of ${layout.vertexCount}"
        }
        for ((index, stream) in streams.withIndex()) {
            val objectBuffer = BufferObject.Builder()
                .size(stream.bytes.size)
                .bindingType(BufferObject.BindingType.VERTEX)
                .build(engine)
            vertexBuffer.setBufferObjectAt(engine, index, objectBuffer)
            layer.bufferObjects += objectBuffer
            upload(layer, objectBuffer, stream.bytes)
        }
        return vertexBuffer
    }

    /**
     * The merged partition's streams, typed exactly as [TubeMesh]'s plan types its slots: uv1 as
     * normalized ushort2, colour as normalized ubyte4, the tangent frame as a normalized short4
     * quaternion. Weights and colour are the two streams a hide or a selection rewrites.
     */
    private fun streams(
        sections: List<MuscleSectionMesh>,
        layout: MuscleSectionLayout,
        weights: FloatArray
    ): List<Stream> {
        val vertexCount = layout.vertexCount
        val positions = FloatArray(vertexCount * 3)
        val uvs = FloatArray(vertexCount * 2)
        val tangents = ByteArray(vertexCount * 8)
        val joints = IntArray(vertexCount * 4)
        for ((index, section) in sections.withIndex()) {
            val slice = layout.slice(index)
            section.positions.copyInto(positions, slice.vertexOffset * 3)
            section.uvs.copyInto(uvs, slice.vertexOffset * 2)
            tangentBytes(section).copyInto(tangents, slice.vertexOffset * 8)
            section.joints.copyInto(joints, slice.vertexOffset * 4)
        }
        return listOf(
            Stream(VertexBuffer.VertexAttribute.POSITION, VertexBuffer.AttributeType.FLOAT3, false, floatBytes(positions)),
            Stream(VertexBuffer.VertexAttribute.UV0, VertexBuffer.AttributeType.FLOAT2, false, floatBytes(uvs)),
            Stream(VertexBuffer.VertexAttribute.UV1, VertexBuffer.AttributeType.USHORT2, true, ByteArray(vertexCount * 4)),
            Stream(VertexBuffer.VertexAttribute.COLOR, VertexBuffer.AttributeType.UBYTE4, true, baseColors(vertexCount)),
            Stream(VertexBuffer.VertexAttribute.TANGENTS, VertexBuffer.AttributeType.SHORT4, true, tangents),
            Stream(VertexBuffer.VertexAttribute.BONE_INDICES, VertexBuffer.AttributeType.USHORT4, false, ushortBytes(joints)),
            Stream(VertexBuffer.VertexAttribute.BONE_WEIGHTS, VertexBuffer.AttributeType.FLOAT4, false, floatBytes(weights))
        )
    }

    private fun mergedPositions(
        sections: List<MuscleSectionMesh>,
        layout: MuscleSectionLayout
    ): FloatArray {
        val positions = FloatArray(layout.vertexCount * 3)
        for ((index, section) in sections.withIndex()) {
            section.positions.copyInto(positions, layout.slice(index).vertexOffset * 3)
        }
        return positions
    }

    private fun mergedWeights(
        sections: List<MuscleSectionMesh>,
        layout: MuscleSectionLayout
    ): FloatArray {
        val weights = FloatArray(layout.vertexCount * 4)
        for ((index, section) in sections.withIndex()) {
            section.weights.copyInto(weights, layout.slice(index).vertexOffset * 4)
        }
        return weights
    }

    private fun mergedIndices(
        sections: List<MuscleSectionMesh>,
        layout: MuscleSectionLayout
    ): IntArray {
        val indices = IntArray(layout.indexCount)
        for ((index, section) in sections.withIndex()) {
            val slice = layout.slice(index)
            for (entry in section.indices.indices) {
                indices[slice.indexOffset + entry] = section.indices[entry] + slice.vertexOffset
            }
        }
        return indices
    }

    /** The section's authored normal and tangent, packed into snorm16 quaternions. */
    private fun tangentBytes(section: MuscleSectionMesh): ByteArray {
        val quats = ByteBuffer.allocateDirect(section.vertexCount * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val orientation = SurfaceOrientation.Builder()
            .vertexCount(section.vertexCount)
            .normals(direct(section.normals))
            .tangents(direct(section.tangents))
            .build()
        try {
            orientation.getQuatsAsFloat(quats)
        } finally {
            orientation.destroy()
        }
        quats.rewind()
        val bytes = ByteBuffer.allocate(section.vertexCount * 8).order(ByteOrder.nativeOrder())
        for (entry in 0 until section.vertexCount * 4) {
            val value = quats.get(entry).coerceIn(-1f, 1f)
            bytes.putShort((value * 32767f).toInt().toShort())
        }
        return bytes.array()
    }

    private fun boxOf(positions: FloatArray): Box {
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (vertex in positions.indices step 3) {
            for (component in 0 until 3) {
                val value = positions[vertex + component]
                if (value < min[component]) min[component] = value
                if (value > max[component]) max[component] = value
            }
        }
        val box = Box()
        box.setCenter(
            (min[0] + max[0]) * 0.5f,
            (min[1] + max[1]) * 0.5f,
            (min[2] + max[2]) * 0.5f
        )
        box.setHalfExtent(
            maxOf((max[0] - min[0]) * 0.5f, 1e-3f),
            maxOf((max[1] - min[1]) * 0.5f, 1e-3f),
            maxOf((max[2] - min[2]) * 0.5f, 1e-3f)
        )
        return box
    }

    private fun baseColors(vertexCount: Int): ByteArray {
        val colors = ByteArray(vertexCount * 4)
        for (vertex in 0 until vertexCount) writeVertexColor(colors, vertex, highlight = false)
        return colors
    }

    /** Rewrites one whole stream of a loaded partition; only a mask or selection change reaches here. */
    private fun uploadStream(layer: Layer, slot: Int, bytes: ByteArray) {
        val buffer = layer.bufferObjects.getOrNull(slot) ?: return
        upload(layer, buffer, bytes)
    }

    private fun upload(layer: Layer, buffer: BufferObject, bytes: ByteArray) {
        // Filament reads an upload's bytes until the driver has consumed it, so the descriptor stays
        // reachable from the layer for as long as the buffer object does.
        val upload = direct(bytes)
        layer.uploads += upload
        buffer.setBuffer(engine, upload, 0, bytes.size)
    }

    private fun floatBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        return buffer.array()
    }

    private fun ushortBytes(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder())
        for (value in values) buffer.putShort(value.toShort())
        return buffer.array()
    }

    private fun intBuffer(values: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        buffer.asIntBuffer().put(values)
        buffer.rewind()
        return buffer
    }

    private fun direct(bytes: ByteArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    private fun direct(values: FloatArray): ByteBuffer = direct(floatBytes(values))

    private fun publishFailures(): String? {
        var joined: String? = null
        var error: String? = null
        for (layer in layers) {
            val failed = layer.failure ?: continue
            joined = if (joined == null) failed else "$joined; $failed"
            val highlight = layer.highlightError
            if (highlight != null) error = if (error == null) highlight else "$error; $highlight"
        }
        if (joined != null) failure = joined
        if (error != null) highlightError = error
        if (renderableCount > 0) state.showAll()
        return joined
    }

    /**
     * One partition's Filament state and the CPU arrays its two rewritable streams mirror. Every
     * field has an empty value until [load] publishes it, so a refusal leaves nothing a later frame
     * or teardown could dereference.
     */
    private class Layer(val spec: MuscleLayerAsset) {
        val label: String get() = spec.label
        val bytes: ByteArray get() = spec.bytes
        val skinner: MuscleLayerSkinner get() = spec.skinner

        var material: MaterialInstance? = null
        var skinningBuffer: SkinningBuffer? = null
        var vertexBuffer: VertexBuffer? = null
        var indexBuffer: IndexBuffer? = null
        var jointCount = 0
        var entities = IntArray(0)
        var slices: List<MuscleSectionSlice> = emptyList()
        var bindPositions = FloatArray(0)
        /** The authored skin weights and the copy the BONE_WEIGHTS stream mirrors; null until load. */
        var weights: MuscleSectionWeights? = null
        var colors = ByteArray(0)
        var matrices = FloatArray(0)
        var palette: FloatBuffer =
            ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        /** Slot 3 is the colour stream and slot 6 the weight stream; both are rewritten in place. */
        val bufferObjects = ArrayList<BufferObject>()
        /** Every CPU descriptor Filament may still be reading; released with the layer. */
        val uploads = ArrayList<ByteBuffer>()
        var failure: String? = null
        var highlightError: String? = null

        fun paint(slice: Int, on: Boolean) {
            val place = slices[slice]
            for (vertex in 0 until place.vertexCount) {
                writeVertexColor(colors, place.vertexOffset + vertex, on)
            }
        }
    }

    private companion object {
        const val LAYER_BIT = 1
        const val COLOR_SLOT = 3
        const val BONE_WEIGHTS_SLOT = 6
        const val HIGHLIGHT_PARAMETER = "emissiveFactor"
        const val BASE_COLOR_PARAMETER = "baseColorFactor"
        const val METALLIC_PARAMETER = "metallicFactor"
        const val ROUGHNESS_PARAMETER = "roughnessFactor"

        const val MUSCLE_RED = 0.62f
        const val MUSCLE_GREEN = 0.2f
        const val MUSCLE_BLUE = 0.24f
        const val MUSCLE_ROUGHNESS = 0.55f
        const val HIGHLIGHT_RED = 0.95f
        const val HIGHLIGHT_GREEN = 0.62f
        const val HIGHLIGHT_BLUE = 0.10f

        val APPEARANCE_PARAMETERS = listOf(
            BASE_COLOR_PARAMETER,
            METALLIC_PARAMETER,
            ROUGHNESS_PARAMETER,
            HIGHLIGHT_PARAMETER
        )

        val REQUIRED_ATTRIBUTES = setOf(
            VertexBuffer.VertexAttribute.POSITION,
            VertexBuffer.VertexAttribute.TANGENTS,
            VertexBuffer.VertexAttribute.UV0,
            VertexBuffer.VertexAttribute.UV1,
            VertexBuffer.VertexAttribute.COLOR,
            VertexBuffer.VertexAttribute.BONE_INDICES,
            VertexBuffer.VertexAttribute.BONE_WEIGHTS
        )
    }
}

/**
 * One partition's authored skin weights and the copy its `BONE_WEIGHTS` stream mirrors.
 *
 * Hiding a section zeroes its four weights per vertex: under GPU skinning a zero weight sends the
 * vertex to (0, 0, 0) in every pose, which collapsing `POSITION` alone cannot do because the bone
 * transforms still move the slice. Showing it copies the authored slice back, so a hide/show cycle is
 * bit-exact rather than clipping the section to its first vertex. A request for the state the mask
 * already holds reports no change, so a steady frame uploads nothing.
 */
internal class MuscleSectionWeights(
    bind: FloatArray,
    private val slices: List<MuscleSectionSlice>
) {

    /** The weights exactly as exported, four per vertex, kept so a shown section can be restored. */
    val bindWeights: FloatArray = bind.copyOf()

    /** The weights the vertex buffer carries; only a mask change rewrites a section's slice. */
    val weights: FloatArray = bind.copyOf()

    private val sectionHidden = BooleanArray(slices.size)

    /** Zeroes or restores one section; false when the mask already held that state. */
    fun setHidden(section: Int, hidden: Boolean): Boolean {
        if (sectionHidden[section] == hidden) return false
        sectionHidden[section] = hidden
        val place = slices[section]
        val start = place.vertexOffset * 4
        val end = (place.vertexOffset + place.vertexCount) * 4
        if (hidden) {
            weights.fill(0f, start, end)
        } else {
            bindWeights.copyInto(weights, destinationOffset = start, startIndex = start, endIndex = end)
        }
        return true
    }
}

/** The authored muscle colour, or the selection tint, as one normalized-ubyte vertex colour. */
private fun writeVertexColor(colors: ByteArray, vertex: Int, highlight: Boolean) {
    val red = if (highlight) 0.95f else 0.62f
    val green = if (highlight) 0.62f else 0.2f
    val blue = if (highlight) 0.10f else 0.24f
    colors[vertex * 4] = (red * 255f).toInt().toByte()
    colors[vertex * 4 + 1] = (green * 255f).toInt().toByte()
    colors[vertex * 4 + 2] = (blue * 255f).toInt().toByte()
    colors[vertex * 4 + 3] = 0xFF.toByte()
}
