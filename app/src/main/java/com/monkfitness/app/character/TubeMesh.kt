package com.monkfitness.app.character

import com.google.android.filament.Box
import com.google.android.filament.BufferObject
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The debug tube layer, as one primitive for the life of the layer.
 *
 * The primitive is built once, on the first frame that has geometry, with every buffer sized to the
 * mesh's whole capacity. A later frame only moves vertices, because the mesh below writes one quota
 * per head of the definition whether or not the solver could solve it — a refused head collapses onto
 * a point and its triangles have zero area. `IndexBuffer.setBuffer` and `RenderableManager.setGeometryAt`
 * against a renderable the scene already references fault inside Filament's render pass on this device
 * (`CHARACTER_DEVICE_VALIDATION.md` §7.7), so nothing here rewrites live geometry: the layer is hidden
 * by its layer mask, never by shrinking the draw.
 *
 * Positions still move every frame through a [BufferObject] stream in a three-deep ring, because a
 * vertex upload against a live renderable is the one upload the device tolerates; the three slots
 * cover the two frames a pipelined renderer can hold while the third is being filled. [prepare]
 * never leaves a half-built primitive behind: a material whose required attributes this layer cannot
 * fill is reported as [failure] and the body still draws.
 */
internal class TubeMesh(
    private val engine: Engine,
    private val scene: Scene,
    private val vertexCapacity: Int,
    private val indexCapacity: Int,
    private val palette: SkinPalette,
    private val provider: UbershaderProvider?,
    private val assetInstance: FilamentInstance?
) {

    private val renderableManager: RenderableManager = engine.renderableManager
    private val bounds = MeshBounds()
    private val box = Box()

    private var layout: TubeVertexLayout? = null
    private var plan: TubeAttributePlan.Supported? = null
    private var material: MaterialInstance? = null
    private var ownsMaterial = false
    private var rings: Array<Ring>? = null
    private var parity = 0
    private var prepared = false
    private var destroyed = false
    private var visible = false

    /** The entity the layer owns for its whole life; each shape replaces its components, not its id. */
    private var entity: Int = 0

    /** The primitive that draws now: the buffers bound to the entity, and the streams behind them. */
    private var primitive: Primitive? = null

    /** Which material candidate drew: 0 is the provider's own permutation, n>0 a donor instance. */
    var materialCandidate: Int = -1
        private set

    /** How many times a primitive was built. One per layer is the point of the fixed topology. */
    var primitiveBuilds: Int = 0
        private set

    /** Why the layer could not be built, or null when it draws. */
    var failure: String? = null
        private set

    /** Why no material instance could be used, when that is what refused the layer. */
    private var materialRefusal: String? = null

    /** The attributes the material asked for, as planned, for the diagnostics overlay. */
    var materialAttributes: String = ""
        private set

    /** True when the material takes its colour from the vertices rather than from a factor. */
    var usesVertexColors: Boolean = false
        private set

    fun prepare(): String? {
        if (destroyed) return "tube layer already destroyed"
        if (prepared) return failure
        prepared = true

        val chosen = selectMaterial()
            ?: return refuse(materialRefusal ?: "no material instance is available for the muscle layer")
        material = chosen.instance
        plan = chosen.plan

        materialAttributes = chosen.plan.attributes.joinToString(",") { it.name }
        usesVertexColors = TubeAttribute.COLOR in chosen.plan.attributes
        if (chosen.flatTint) {
            // The fallback instance carries the tint in baseColorFactor, so the overlay says which one drew.
            materialAttributes += " (flat tint)"
        }

        layout = TubeVertexLayout(chosen.plan, vertexCapacity, indexCapacity)
        rings = Array(RING_DEPTH) { Ring(vertexCapacity, indexCapacity) }
        entity = EntityManager.get().create()
        return null
    }

    /** Releases the primitive before the engine it belongs to, in the order Filament requires. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        releasePrimitive()
        if (entity != 0) {
            EntityManager.get().destroy(entity)
            entity = 0
        }
        // A duplicated or provider-created instance is ours; one owned by the asset must not be freed here.
        if (ownsMaterial) material?.let { engine.destroyMaterialInstance(it) }
        material = null
        rings = null
        layout = null
        plan = null
    }

    /**
     * Uploads one solved frame. The primitive is built once, sized to the layer's whole capacity, and
     * every later frame costs one position stream: a refusal or a layer toggle moves vertices, never
     * the topology, so nothing here tears a live primitive down mid-rep.
     */
    fun upload(geometry: DynamicGeometrySlot, rebuild: Boolean) {
        if (destroyed || failure != null) return
        val built = layout ?: return
        val slots = rings ?: return
        val count = geometry.vertexCount
        if (count == 0 || geometry.indexCount == 0) {
            // No heads drawn is not a primitive with zero indices: it is no draw, and nothing to keep
            // alive in the scene for the frames that follow.
            releasePrimitive()
            return
        }

        val current = primitive
        if (rebuild || current == null) {
            releasePrimitive()
            primitive = buildPrimitive(geometry, built)
        } else {
            uploadPositions(current, geometry, built, slots)
        }
    }

    /** Objects toggles a layer; the mask is kept for the primitive a later frame will build. */
    fun setVisible(on: Boolean) {
        if (destroyed || !prepared || failure != null) return
        if (on == visible) return
        visible = on
        primitive?.let { renderableManager.setLayerMask(entity, LAYER_BIT, if (on) LAYER_BIT else 0) }
    }

    /**
     * Builds the primitive the layer keeps for its life: every buffer sized to the mesh's whole
     * capacity — one quota per head of the definition — and the renderable handed the index run this
     * frame wrote. Since that run is now a constant, the primitive is built once and only ever
     * receives position uploads.
     */
    private fun buildPrimitive(geometry: DynamicGeometrySlot, built: TubeVertexLayout): Primitive {
        primitiveBuilds++
        val declared = plan ?: error("a primitive needs a planned attribute set")
        val instance = material ?: error("a primitive needs a material instance")
        val count = geometry.vertexCount
        val indexCount = geometry.indexCount
        // Capacity, not this frame's counts: a buffer that cannot hold the next refusal would be the
        // mid-rep reallocation this layer exists without.
        val streams = Ring(vertexCapacity, indexCapacity)

        built.writePositions(geometry, streams.positions, bounds)

        val vertexBuffer = buildVertexBuffer(declared, vertexCapacity)
        val streamsByAttribute = LinkedHashMap<TubeAttribute, BufferObject>()
        for (slot in declared.slots) {
            val attribute = slot.attribute
            val buffer = BufferObject.Builder()
                .size(vertexCapacity * attribute.bytesPerVertex)
                .bindingType(BufferObject.BindingType.VERTEX)
                .build(engine)
            vertexBuffer.setBufferObjectAt(engine, slot.bufferIndex, buffer)
            streamsByAttribute[attribute] = buffer
        }

        // A material that does not ask for UV0, UV1 or colour has no slot to upload them to, and
        // asking for one is an exception: the plan is the authority on which attributes exist.
        streamsByAttribute[TubeAttribute.POSITION]?.setBuffer(
            engine, streams.positions, 0, built.floatUploadCount(TubeAttribute.POSITION, count)
        )
        built.bufferIndexOfOrNull(TubeAttribute.UV0)?.let {
            built.writeUvs(geometry, streams.uvs)
            streamsByAttribute[TubeAttribute.UV0]?.setBuffer(
                engine, streams.uvs, 0, built.floatUploadCount(TubeAttribute.UV0, count)
            )
        }
        built.bufferIndexOfOrNull(TubeAttribute.UV1)?.let {
            built.writeUv1Dummy(streams.uv1Bytes, count)
            streamsByAttribute[TubeAttribute.UV1]?.setBuffer(
                engine, streams.uv1Bytes, 0, built.byteUploadCount(TubeAttribute.UV1, count)
            )
        }
        built.bufferIndexOfOrNull(TubeAttribute.COLOR)?.let {
            built.writeColors(geometry, streams.colorBytes)
            streamsByAttribute[TubeAttribute.COLOR]?.setBuffer(
                engine, streams.colorBytes, 0, built.byteUploadCount(TubeAttribute.COLOR, count)
            )
        }

        built.writeIndices(geometry, streams.indices)
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, streams.indices, 0, indexCount)

        if (bounds.isEmpty) bounds.fallback()
        applyBounds()
        RenderableManager.Builder(1)
            // Culling is off and the box is the builder's static one, which is the shape the surviving
            // T1/T4 controls used. A culled renderable would need a DYNAMIC geometry type to keep its
            // AABB in step with a mesh that moves every frame, and that is the code path under test.
            .boundingBox(box)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, indexCount)
            .material(0, instance)
            .build(engine, entity)
        // A scene entity without a transform reads an unset component on every frame.
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        renderableManager.setLayerMask(entity, LAYER_BIT, if (visible) LAYER_BIT else 0)

        return Primitive(vertexBuffer, indexBuffer, streamsByAttribute, streams, count, indexCount)
    }

    /** Streams one frame's positions into the primitive that is already built. */
    private fun uploadPositions(
        primitive: Primitive,
        geometry: DynamicGeometrySlot,
        built: TubeVertexLayout,
        slots: Array<Ring>
    ) {
        val ring = slots[parity]
        parity = (parity + 1) % RING_DEPTH
        built.writePositions(geometry, ring.positions, bounds)
        primitive.uploads[TubeAttribute.POSITION]?.setBuffer(
            engine, ring.positions, 0, built.floatUploadCount(TubeAttribute.POSITION, geometry.vertexCount)
        )
    }

    /**
     * Frees the primitive in the order its references dictate: the scene first, then the entity's
     * components, then the buffers those components bound. The id itself belongs to the layer and
     * goes only in [destroy] — an id freed per shape is one the entity pool can hand to a later
     * primitive while an older handle still exists.
     */
    private fun releasePrimitive() {
        val current = primitive ?: return
        primitive = null
        scene.remove(entity)
        engine.destroyEntity(entity)
        current.uploads.values.forEach { engine.destroyBufferObject(it) }
        engine.destroyVertexBuffer(current.vertexBuffer)
        engine.destroyIndexBuffer(current.indexBuffer)
    }

    private fun buildVertexBuffer(declared: TubeAttributePlan.Supported, vertexCount: Int): VertexBuffer {
        val builder = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(declared.bufferCount)
            .enableBufferObjects(true)
        for (slot in declared.slots) {
            val attribute = TubeVertexTypes.slot(slot.attribute)
            val type = TubeVertexTypes.of(slot.attribute)
            // The planner refuses these before a builder exists, so reaching one is a bug.
            if (attribute == null || type == null) {
                throw IllegalStateException("attribute ${slot.attribute} cannot be declared")
            }
            builder.attribute(attribute, slot.bufferIndex, type)
            if (TubeVertexTypes.isNormalized(slot.attribute)) builder.normalized(attribute)
        }
        return builder.build(engine)
    }

    private fun applyBounds() {
        box.setCenter(bounds.centreX, bounds.centreY, bounds.centreZ)
        box.setHalfExtent(
            maxOf(bounds.halfX, MIN_HALF_EXTENT),
            maxOf(bounds.halfY, MIN_HALF_EXTENT),
            maxOf(bounds.halfZ, MIN_HALF_EXTENT)
        )
    }

    /** The instance that will draw, the slots its shader needs, and whether its tint is per-vertex. */
    private class ChosenMaterial(
        val instance: MaterialInstance,
        val plan: TubeAttributePlan.Supported,
        val flatTint: Boolean
    )

    /**
     * Picks the instance by what its shader requires, never by its name.
     *
     * Every lit Filament shading model requires `TANGENTS` (`MaterialBuilder` sets it for anything
     * that is not unlit, whatever textures the material declares), so the layer asks for the unlit
     * permutation first and only falls back to the asset's own instances when one of them needs
     * nothing a rigid tube cannot fill. The planner stays the authority: an instance that still asks
     * for `TANGENTS` is refused by name rather than under-filled.
     */
    private fun selectMaterial(): ChosenMaterial? {
        val provided = provider?.createMaterialInstance(tubeKey(), IntArray(UV_SETS), LABEL, null)
        val donors = assetInstance?.materialInstances.orEmpty()

        val required = ArrayList<Collection<String>>(donors.size + 1)
        provided?.let { required += it.material.requiredAttributes.map { attribute -> attribute.name } }
        for (donor in donors) {
            required += donor.material.requiredAttributes.map { attribute -> attribute.name }
        }

        val choice = TubeAttributePlanner.choose(required)
        if (choice == null) {
            provided?.let { engine.destroyMaterialInstance(it) }
            materialRefusal = (
                required.firstOrNull()?.let { TubeAttributePlanner.planFromNames(it) } as? TubeAttributePlan.Refused
                )?.reason
            return null
        }

        materialCandidate = choice.candidate
        if (provided != null && choice.candidate == 0) {
            // The tint lives in the vertices, so the factor stays neutral and only scales it.
            provided.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
            provided.setParameter("metallicFactor", METALLIC)
            provided.setParameter("roughnessFactor", ROUGHNESS)
            provided.setCullingMode(Material.CullingMode.BACK)
            ownsMaterial = true
            return ChosenMaterial(provided, choice.plan, flatTint = false)
        }

        val donorIndex = choice.candidate - if (provided != null) 1 else 0
        val donor = donors.getOrNull(donorIndex)
        // Ours only if it was chosen; a provider instance that lost to a donor still owns memory.
        provided?.let { engine.destroyMaterialInstance(it) }
        if (donor == null) return null
        val copy = MaterialInstance.duplicate(donor, LABEL)
        copy.setParameter("baseColorFactor", palette.muscleRed, palette.muscleGreen, palette.muscleBlue, 1f)
        copy.setParameter("metallicFactor", METALLIC)
        copy.setParameter("roughnessFactor", ROUGHNESS)
        copy.setCullingMode(Material.CullingMode.BACK)
        ownsMaterial = true
        return ChosenMaterial(copy, choice.plan, flatTint = true)
    }

    /**
     * The ubershader permutation the tube layer requests. `unlit` is what keeps `TANGENTS` out of the
     * shader's `requires`; it also keeps the draw to one pass, which the frame budget depends on.
     */
    private fun tubeKey(): MaterialProvider.MaterialKey = MaterialProvider.MaterialKey().apply {
        unlit = true
        doubleSided = false
        hasVertexColors = true
        alphaMode = OPAQUE_ALPHA_MODE
        baseColorUV = 0
        metallicRoughnessUV = 0
    }

    private fun refuse(reason: String): String {
        failure = reason
        releasePrimitive()
        if (ownsMaterial) material?.let { engine.destroyMaterialInstance(it) }
        material = null
        rings = null
        layout = null
        plan = null
        return reason
    }

    /** One primitive and everything it keeps alive: its buffers and the CPU streams behind them. */
    private class Primitive(
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
        val uploads: Map<TubeAttribute, BufferObject>,
        /** Held, not read: Filament reads these buffers until the GPU has consumed the upload. */
        val streams: Ring,
        val vertexCount: Int,
        val indexCount: Int
    )

    /** One set of CPU-side buffers: three per frame, plus one per primitive that stays alive with it. */
    private class Ring(vertexCapacity: Int, indexCapacity: Int) {

        val positionBytes: ByteBuffer = direct(vertexCapacity * TubeAttribute.POSITION.bytesPerVertex)
        val positions: FloatBuffer = positionBytes.asFloatBuffer()

        val uvBytes: ByteBuffer = direct(vertexCapacity * TubeAttribute.UV0.bytesPerVertex)
        val uvs: FloatBuffer = uvBytes.asFloatBuffer()

        val uv1Bytes: ByteBuffer = direct(vertexCapacity * TubeAttribute.UV1.bytesPerVertex)
        val colorBytes: ByteBuffer = direct(vertexCapacity * TubeAttribute.COLOR.bytesPerVertex)

        val indexBytes: ByteBuffer = direct(indexCapacity * UINT_BYTES)
        val indices: IntBuffer = indexBytes.asIntBuffer()

        private companion object {
            fun direct(bytes: Int): ByteBuffer =
                ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
    }

    private companion object {
        const val RING_DEPTH = 3
        const val LAYER_BIT = 1
        const val LABEL = "muscleTubes"
        const val OPAQUE_ALPHA_MODE = 0
        const val UV_SETS = 8
        const val METALLIC = 0f
        const val ROUGHNESS = 0.55f
        const val UINT_BYTES = 4
        const val MIN_HALF_EXTENT = 1e-4f
    }
}
