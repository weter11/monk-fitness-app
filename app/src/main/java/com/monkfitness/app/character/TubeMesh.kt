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
 * The debug tube layer as one Filament renderable: buffers sized once, rewritten in place, and visible
 * only while the layer controller says so.
 *
 * Every CPU-side buffer comes in three copies ([Ring]) because `setBufferAt` hands Filament the address
 * of a direct buffer and only releases it after the GPU has consumed it; rewriting a copy whose upload
 * is still pending is a tear, not an exception. Three slots cover the two frames a pipelined renderer
 * can hold while the third is being filled. [prepare] never leaves a half-built renderable behind: a
 * material whose required attributes this layer cannot fill is reported as [failure] and the body still
 * draws.
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
    private val entity: Int = EntityManager.get().create()
    private val bounds = MeshBounds()
    private val box = Box()

    private var layout: TubeVertexLayout? = null
    private var vertexBuffer: VertexBuffer? = null
    private var indexBuffer: IndexBuffer? = null
    private var material: MaterialInstance? = null
    private var ownsMaterial = false
    private var rings: Array<Ring>? = null
    private var parity = 0
    private var prepared = false
    private var destroyed = false
    private var visible = false
    private var inScene = false
    private var uploadedVertexCount = 0
    private var uploadedIndexCount = 0
    private var primed = false

    /**
     * One streaming buffer per declared attribute.
     *
     * `setBufferAt` writes through the vertex buffer's own storage, which the renderable's primitive
     * captured when it was built; a `BufferObject` is the streamed handle instead, and the vertex
     * buffer holds only the binding. Updating the former after the renderable exists dereferences
     * nothing inside Filament's frame — a SIGSEGV with no log line and no attributable tombstone.
     */
    private val uploads = LinkedHashMap<TubeAttribute, BufferObject>()

    /** Which material candidate drew: 0 is the provider's own permutation, n>0 a donor instance. */
    var materialCandidate: Int = -1
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
        val plan = chosen.plan

        materialAttributes = plan.attributes.joinToString(",") { it.name }
        usesVertexColors = TubeAttribute.COLOR in plan.attributes
        if (chosen.flatTint) {
            // The fallback instance carries the tint in baseColorFactor, so the overlay says which one drew.
            materialAttributes += " (flat tint)"
        }

        val built = TubeVertexLayout(plan, vertexCapacity, indexCapacity)
        layout = built
        vertexBuffer = buildVertexBuffer(plan)
        indexBuffer = buildIndexBuffer()
        rings = Array(RING_DEPTH) { Ring(vertexCapacity, indexCapacity) }
        createUploads(plan)
        primeBuffers()

        buildRenderable()
        return null
    }

    /**
     * Tears the layer down before the engine it belongs to; the entity goes first so nothing in the
     * scene can reference a buffer that is already gone.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (inScene) {
            scene.removeEntity(entity)
            inScene = false
        }
        // The entity owns the renderable, and the renderable is what keeps the material instance
        // alive: Filament throws when the instance goes first, which a configuration change turns
        // into a crash on the way out of the activity.
        engine.destroyEntity(entity)
        uploads.clear()
        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        // A duplicated or provider-created instance is ours; one owned by the asset must not be freed here.
        if (ownsMaterial) material?.let { engine.destroyMaterialInstance(it) }
        vertexBuffer = null
        indexBuffer = null
        material = null
        rings = null
        layout = null
    }

    /**
     * Uploads one solved frame. Positions move every frame; colour, UV and indices only change when the
     * set of drawn heads did, so a pose change costs one 12-byte-per-vertex copy.
     */
    fun upload(geometry: DynamicGeometrySlot, rebuild: Boolean) {
        if (destroyed || failure != null) return
        val vb = vertexBuffer ?: return
        val ib = indexBuffer ?: return
        val built = layout ?: return
        val pool = rings ?: return
        val count = geometry.vertexCount
        if (count == 0) return

        val ring = pool[parity]
        parity = (parity + 1) % RING_DEPTH

        built.writePositions(geometry, ring.positions, bounds)
        uploads[TubeAttribute.POSITION]?.setBuffer(
            engine, ring.positions, 0, built.floatUploadCount(TubeAttribute.POSITION, count)
        )

        // A head that refuses changes both counts without touching the selection, so the shape — not
        // just the revision — decides whether the static attributes and the index count still hold.
        val shapeMoved = rebuild || count != uploadedVertexCount || geometry.indexCount != uploadedIndexCount
        if (shapeMoved) {
            // A material that does not ask for UV0, UV1 or colour has no slot to upload them to, and
            // asking for one is an exception: the plan is the authority on which attributes exist.
            built.bufferIndexOfOrNull(TubeAttribute.UV0)?.let { slot ->
                built.writeUvs(geometry, ring.uvs)
                uploads[TubeAttribute.UV0]?.setBuffer(
                    engine, ring.uvs, 0, built.floatUploadCount(TubeAttribute.UV0, count)
                )
            }
            built.bufferIndexOfOrNull(TubeAttribute.UV1)?.let { slot ->
                built.writeUv1Dummy(ring.uv1Bytes, vertexCapacity)
                uploads[TubeAttribute.UV1]?.setBuffer(
                    engine, ring.uv1Bytes, 0, built.byteUploadCount(TubeAttribute.UV1, vertexCapacity)
                )
            }
            built.bufferIndexOfOrNull(TubeAttribute.COLOR)?.let { slot ->
                built.writeColors(geometry, ring.colorBytes)
                uploads[TubeAttribute.COLOR]?.setBuffer(
                    engine, ring.colorBytes, 0, built.byteUploadCount(TubeAttribute.COLOR, vertexCapacity)
                )
            }
            built.writeIndices(geometry, ring.indices)
            ib.setBuffer(engine, ring.indices, 0, geometry.indexCount)
            renderableManager.setGeometryAt(
                entity, 0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, geometry.indexCount
            )
            uploadedVertexCount = count
            uploadedIndexCount = geometry.indexCount
        }

        updateBounds()
    }

    /** Objects toggles a layer; a zero index count is not a draw of nothing, it is unfilled geometry. */
    fun setVisible(on: Boolean) {
        if (destroyed || !prepared || failure != null) return
        if (on == visible) return
        visible = on
        renderableManager.setLayerMask(entity, LAYER_BIT, if (on) LAYER_BIT else 0)
    }

    /** Creates one streaming buffer per declared attribute and binds it to the vertex buffer. */
    private fun createUploads(plan: TubeAttributePlan.Supported) {
        val vb = vertexBuffer ?: return
        for (slot in plan.slots) {
            val buffer = BufferObject.Builder()
                .size(vertexCapacity * slot.attribute.bytesPerVertex)
                .bindingType(BufferObject.Builder.BindingType.VERTEX)
                .build(engine)
            vb.setBufferObjectAt(engine, slot.bufferIndex, buffer)
            uploads[slot.attribute] = buffer
        }
    }

    /**
     * Fills every declared buffer once, before the renderable that references it exists, so the
     * primitive is built against a vertex buffer whose streams are all present.
     */
    private fun primeBuffers() {
        val ib = indexBuffer ?: return
        val built = layout ?: return
        val ring = rings?.firstOrNull() ?: return

        uploads[TubeAttribute.POSITION]?.setBuffer(
            engine, ring.positions, 0, built.floatUploadCount(TubeAttribute.POSITION, vertexCapacity)
        )
        uploads[TubeAttribute.UV0]?.setBuffer(
            engine, ring.uvs, 0, built.floatUploadCount(TubeAttribute.UV0, vertexCapacity)
        )
        uploads[TubeAttribute.UV1]?.let { buffer ->
            built.writeUv1Dummy(ring.uv1Bytes, vertexCapacity)
            buffer.setBuffer(engine, ring.uv1Bytes, 0, built.byteUploadCount(TubeAttribute.UV1, vertexCapacity))
        }
        uploads[TubeAttribute.COLOR]?.setBuffer(
            engine, ring.colorBytes, 0, built.byteUploadCount(TubeAttribute.COLOR, vertexCapacity)
        )
        ib.setBuffer(engine, ring.indices, 0, indexCapacity)
        primed = true
    }

    private fun buildVertexBuffer(plan: TubeAttributePlan.Supported): VertexBuffer {
        val builder = VertexBuffer.Builder()
            .vertexCount(vertexCapacity)
            .bufferCount(plan.bufferCount)
            .enableBufferObjects(true)
        for (slot in plan.slots) {
            when (slot.attribute) {
                TubeAttribute.POSITION ->
                    builder.attribute(ATTRIBUTE_POSITION, slot.bufferIndex, VertexBuffer.AttributeType.FLOAT3)

                TubeAttribute.UV0 ->
                    builder.attribute(ATTRIBUTE_UV0, slot.bufferIndex, VertexBuffer.AttributeType.FLOAT2)

                TubeAttribute.UV1 -> {
                    builder.attribute(ATTRIBUTE_UV1, slot.bufferIndex, VertexBuffer.AttributeType.USHORT2)
                    builder.normalized(ATTRIBUTE_UV1)
                }

                TubeAttribute.COLOR -> {
                    builder.attribute(ATTRIBUTE_COLOR, slot.bufferIndex, VertexBuffer.AttributeType.UBYTE4)
                    builder.normalized(ATTRIBUTE_COLOR)
                }

                // The planner refuses these before a builder exists, so reaching one is a bug.
                else -> throw IllegalStateException("attribute ${slot.attribute} cannot be declared")
            }
        }
        return builder.build(engine)
    }

    private fun buildIndexBuffer(): IndexBuffer =
        IndexBuffer.Builder()
            .indexCount(indexCapacity)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)

    /**
     * Builds the renderable with an explicit non-empty box: Filament aborts on an empty bounds while
     * culling is on, and its builder defaults leave the box empty.
     */
    private fun buildRenderable() {
        val vb = vertexBuffer ?: return
        val ib = indexBuffer ?: return
        val instance = material ?: return

        require(primed) { "a renderable must be built against primed buffers" }
        bounds.fallback()
        applyBounds()
        RenderableManager.Builder(1)
            .boundingBox(box)
            .culling(true)
            // The box above is what makes culling legal: Filament aborts on an empty AABB while
            // culling is on, whatever the shadow flags say, so bounds.fallback() is the real guard.
            .castShadows(false)
            .receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 0)
            .material(0, instance)
            .build(engine, entity)
        // A scene entity without a transform reads an unset component on every frame.
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        inScene = true
        renderableManager.setLayerMask(entity, LAYER_BIT, 0)
    }

    private fun updateBounds() {
        if (bounds.isEmpty) bounds.fallback()
        applyBounds()
        renderableManager.setAxisAlignedBoundingBox(entity, box)
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
        if (inScene) {
            scene.removeEntity(entity)
            inScene = false
        }
        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        if (ownsMaterial) material?.let { engine.destroyMaterialInstance(it) }
        vertexBuffer = null
        indexBuffer = null
        material = null
        rings = null
        layout = null
        return reason
    }

    /** One parity of the CPU-side buffers: three exist so no slot is rewritten while its upload is pending. */
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

        val ATTRIBUTE_POSITION: VertexBuffer.VertexAttribute = VertexBuffer.VertexAttribute.POSITION
        val ATTRIBUTE_UV0: VertexBuffer.VertexAttribute = VertexBuffer.VertexAttribute.UV0
        val ATTRIBUTE_UV1: VertexBuffer.VertexAttribute = VertexBuffer.VertexAttribute.UV1
        val ATTRIBUTE_COLOR: VertexBuffer.VertexAttribute = VertexBuffer.VertexAttribute.COLOR
    }
}
