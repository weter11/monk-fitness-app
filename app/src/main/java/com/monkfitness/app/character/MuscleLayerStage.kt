package com.monkfitness.app.character

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleInstanceId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The authored muscle layer: one skinned renderable per muscle and side, driven by the same palette the
 * body uploads followed by one bone per helper, so a solved path moves the mesh without touching a
 * vertex on the CPU.
 *
 * The asset is loaded once and never rebuilt: a layer toggle moves the layer mask, exactly as the tube
 * layer does, because a renderable the scene already references cannot have its geometry replaced.
 */
internal class MuscleLayerStage(
    private val engine: Engine,
    private val scene: Scene,
    private val provider: UbershaderProvider?,
    private val skinner: MuscleLayerSkinner,
    private val instances: List<MuscleInstanceId>
) {

    private val renderableManager: RenderableManager = engine.renderableManager
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null

    /** Palette joint count the asset's skin declares, checked against the skinner's. */
    private var jointCount = 0

    private var entities = IntArray(0)
    private var prepared = false
    private val matrices = FloatArray(skinner.paletteCount * 16)
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val visible = BooleanArray(instances.size)

    /** Why the layer could not be built, or null when it draws. */
    var failure: String? = null
        private set

    /** How many instances the asset carries a renderable for. */
    var renderableCount: Int = 0
        private set

    /** How many instances the last frame showed. */
    var visibleCount: Int = 0
        private set

    /** Frames whose palette reached the GPU; a frozen counter means the upload never ran. */
    var skinUploads: Long = 0
        private set

    fun prepare(bytes: ByteArray): String? {
        if (prepared) return failure
        prepared = true
        val materialProvider = provider ?: return refuse("the layer has no material provider")
        val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        direct.put(bytes)
        direct.rewind()
        val loader = AssetLoader(engine, materialProvider, EntityManager.get())
        assetLoader = loader
        var loaded: FilamentAsset? = null
        try {
            loaded = loader.createAsset(direct) ?: return refuse("gltfio could not load ${HelperBinds.ASSET_PATH}")
            asset = loaded
            val resources = ResourceLoader(engine)
            try {
                resources.loadResources(loaded)
            } finally {
                resources.destroy()
            }

            jointCount = if (loaded.instance.skinCount > 0) loaded.instance.getJointCountAt(0) else 0
            if (jointCount != skinner.paletteCount) {
                return refuse("the layer skins $jointCount joints, its palette has ${skinner.paletteCount}")
            }

            val byName = HashMap<String, Int>(instances.size * 2)
            for (entity in loaded.renderableEntities) {
                val name = loaded.getName(entity) ?: continue
                // Two renderables under one name would tie an instance's visibility to the wrong draw.
                if (byName.put(name, entity) != null) {
                    return refuse("two renderables are named $name")
                }
            }
            val resolved = IntArray(instances.size) { index ->
                val instance = instances[index]
                byName[nameOf(instance)] ?: return refuse("the asset has no section for $instance")
            }
            for (entity in resolved) {
                if (renderableManager.getInstance(entity) == 0) {
                    return refuse("section ${loaded.getName(entity)} has no renderable instance")
                }
            }

            scene.addEntities(loaded.entities)
            entities = resolved
            renderableCount = resolved.size
            buffer = ByteBuffer.allocateDirect(skinner.paletteCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            // Nothing draws until a frame says an instance is visible: a stale mask would show last
            // session's muscles over a body that has already moved.
            for (entity in entities) renderableManager.setLayerMask(entity, LAYER_BIT, 0)
            visible.fill(false)
            visibleCount = 0
            return null
        } catch (error: Throwable) {
            val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            refuse(reason)
            throw IllegalStateException(reason, error)
        } finally {
            if (asset == null) {
                try {
                    scene.removeEntities(loaded?.entities ?: IntArray(0))
                } finally {
                    try {
                        loaded?.let { loader.destroyAsset(it) }
                    } finally {
                        loader.destroy()
                    }
                }
                assetLoader = null
                entities = IntArray(0)
                renderableCount = 0
                visible.fill(false)
                visibleCount = 0
                jointCount = 0
            }
        }
    }

    /**
     * Skins one solved frame and uploads the palette. [body] is the rig's own bone matrices; the
     * helpers are appended after them, so one buffer drives the whole layer.
     */
    fun skin(frame: AnatomyFrame, body: FloatArray): Boolean {
        if (asset == null) return false
        skinner.write(frame, body, matrices)
        buffer.clear()
        buffer.put(matrices)
        for (entity in entities) {
            val renderable = renderableManager.getInstance(entity)
            if (renderable == 0) continue
            buffer.rewind()
            renderableManager.setBonesAsMatrices(renderable, buffer, jointCount, 0)
        }
        skinUploads++
        return true
    }

    /** Hides or shows one instance; the index is the frame's instance order. */
    fun setVisible(index: Int, on: Boolean) {
        if (index !in entities.indices || visible[index] == on) return
        visible[index] = on
        renderableManager.setLayerMask(entities[index], LAYER_BIT, if (on) LAYER_BIT else 0)
        var count = 0
        for (shown in visible) if (shown) count++
        visibleCount = count
    }

    fun hideAll() {
        for (index in entities.indices) setVisible(index, false)
    }

    /** Releases the asset before the provider that owns its materials. */
    fun destroy() {
        val loaded = asset
        asset = null
        loaded?.let {
            scene.removeEntities(it.entities)
            assetLoader?.destroyAsset(it)
        }
        assetLoader?.destroy()
        assetLoader = null
        entities = IntArray(0)
        renderableCount = 0
        visibleCount = 0
    }

    private fun refuse(reason: String): String {
        failure = reason
        return reason
    }

    private companion object {
        const val LAYER_BIT = 1

        fun nameOf(instance: MuscleInstanceId): String = "${instance.muscleId}_${instance.side.name}"
    }
}
