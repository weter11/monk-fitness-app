package com.monkfitness.app.character

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.monkfitness.app.anatomy.AnatomyFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The authored muscle layer: one skinned renderable per muscle and side, driven by the same palette the
 * body uploads followed by one bone per helper, so a solved path moves the mesh without touching a
 * vertex on the CPU.
 *
 * The asset is loaded once and never rebuilt. A hide moves one section's layer mask and a selection
 * sets one section's own material parameter; neither touches geometry, because a renderable the scene
 * already references cannot have its geometry replaced. Sections are addressed by index into the keys
 * handed in, so no muscle identity or solve verdict crosses into this class.
 */
internal class MuscleLayerStage(
    private val engine: Engine,
    private val scene: Scene,
    private val provider: UbershaderProvider?,
    private val skinner: MuscleLayerSkinner,
    sectionKeys: List<String>
) {

    private val keys: List<String> = sectionKeys.toList()

    /** One mask bit and at most one highlight per section; this stage only pushes what it reports. */
    val state = MuscleSectionState(keys.size)

    private val renderableManager: RenderableManager = engine.renderableManager
    private var assetLoader: AssetLoader? = null
    private var asset: FilamentAsset? = null

    /** Palette joint count the asset's skin declares, checked against the skinner's. */
    private var jointCount = 0

    private var entities = IntArray(0)
    private var prepared = false
    private val matrices = FloatArray(skinner.paletteCount * 16)
    private var buffer: FloatBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * One clone per section. The asset gives every section the same instance, so a highlight written
     * to it would tint all sixteen; a clone is also ours to free, which the asset's is not.
     */
    private var materials: Array<MaterialInstance?> = emptyArray()

    /** Why a selection cannot tint its section, or null when the highlight is live. */
    var highlightError: String? = null
        private set

    /** Why the layer could not be built, or null when it draws. */
    var failure: String? = null
        private set

    /** How many instances the asset carries a renderable for. */
    var renderableCount: Int = 0
        private set

    /** How many instances the last frame showed. */
    val visibleCount: Int get() = state.shownCount

    /** The section carrying the highlight, or [MuscleSectionState.NO_SECTION]. */
    val selected: Int get() = state.selected

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

            val byName = HashMap<String, Int>(keys.size * 2)
            for (entity in loaded.renderableEntities) {
                val name = loaded.getName(entity) ?: continue
                // Two renderables under one name would tie an instance's visibility to the wrong draw.
                if (byName.put(name, entity) != null) {
                    return refuse("two renderables are named $name")
                }
            }
            val resolved = IntArray(keys.size) { index ->
                byName[keys[index]] ?: return refuse("the asset has no section named ${keys[index]}")
            }
            for (entity in resolved) {
                if (renderableManager.getInstance(entity) == 0) {
                    return refuse("section ${loaded.getName(entity)} has no renderable instance")
                }
            }

            ownMaterials(resolved)
            // Only from here is the asset the stage's: every refusal above leaves the finally below
            // the one owner of the loader, the asset and the clones.
            asset = loaded
            scene.addEntities(loaded.entities)
            entities = resolved
            renderableCount = resolved.size
            buffer = ByteBuffer.allocateDirect(skinner.paletteCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            // Nothing draws until a frame says an instance is visible: a stale mask would show last
            // session's muscles over a body that has already moved.
            for (section in entities.indices) renderableManager.setLayerMask(entities[section], LAYER_BIT, 0)
            state.hideAll()
            return null
        } catch (error: Throwable) {
            val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            refuse(reason)
            throw IllegalStateException(reason, error)
        } finally {
            if (asset == null) {
                try {
                    scene.removeEntities(loaded?.entities ?: IntArray(0))
                    loaded?.let { loader.destroyAsset(it) }
                } finally {
                    // After the renderables that reference them; before the loader that made the asset.
                    try {
                        releaseMaterials()
                    } finally {
                        loader.destroy()
                    }
                }
                assetLoader = null
                entities = IntArray(0)
                renderableCount = 0
                state.hideAll()
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
        for (section in entities.indices) {
            val renderable = renderableManager.getInstance(entities[section])
            if (renderable == 0) continue
            buffer.rewind()
            renderableManager.setBonesAsMatrices(renderable, buffer, jointCount, 0)
        }
        skinUploads++
        return true
    }

    /** Shows or hides one section; an unchanged mask costs no renderable write. */
    fun setShown(section: Int, on: Boolean) {
        if (section !in entities.indices || !state.show(section, on)) return
        renderableManager.setLayerMask(entities[section], LAYER_BIT, if (on) LAYER_BIT else 0)
    }

    /** Moves the highlight, tinting the section that carries it and clearing the one that lost it. */
    fun setSelected(section: Int) {
        val previous = state.selected
        if (!state.select(section)) return
        tint(previous, on = false)
        tint(section, on = true)
    }

    fun hideAll() {
        for (section in entities.indices) setShown(section, false)
        setSelected(MuscleSectionState.NO_SECTION)
    }

    /** Releases the asset before the provider that owns its materials. */
    fun destroy() {
        val loaded = asset
        asset = null
        loaded?.let {
            scene.removeEntities(it.entities)
            assetLoader?.destroyAsset(it)
        }
        // The clones are referenced by renderables the asset owned, so they outlive its teardown;
        // freeing one earlier trips Filament's own in-use precondition and takes the activity down.
        releaseMaterials()
        assetLoader?.destroy()
        assetLoader = null
        entities = IntArray(0)
        renderableCount = 0
        state.hideAll()
    }

    /**
     * Gives each section its own material instance, so a highlight is one section's parameter.
     *
     * The tint is the emissive term this asset does not author, so clearing it returns the section to
     * exactly the colour the asset shipped. A material without that parameter reports itself instead
     * of leaving a selection that draws nothing.
     */
    private fun ownMaterials(resolved: IntArray) {
        val source = renderableManager.getMaterialInstanceAt(renderableManager.getInstance(resolved[0]), 0)
        if (!source.material.hasParameter(HIGHLIGHT_PARAMETER)) {
            highlightError = "the layer's material carries no $HIGHLIGHT_PARAMETER"
            return
        }
        val own = arrayOfNulls<MaterialInstance>(resolved.size)
        // Published before the first clone, so a throw half way through still leaves every clone the
        // teardown has to free reachable from [materials].
        materials = own
        for (section in resolved.indices) {
            val renderable = renderableManager.getInstance(resolved[section])
            val clone = MaterialInstance.duplicate(
                renderableManager.getMaterialInstanceAt(renderable, 0),
                HIGHLIGHT_LABEL
            )
            clone.setParameter(HIGHLIGHT_PARAMETER, 0f, 0f, 0f)
            own[section] = clone
            renderableManager.setMaterialInstanceAt(renderable, 0, clone)
        }
    }

    private fun tint(section: Int, on: Boolean) {
        if (section !in entities.indices) return
        val instance = materials.getOrNull(section) ?: return
        val red = if (on) HIGHLIGHT_RED else 0f
        val green = if (on) HIGHLIGHT_GREEN else 0f
        val blue = if (on) HIGHLIGHT_BLUE else 0f
        instance.setParameter(HIGHLIGHT_PARAMETER, red, green, blue)
    }

    private fun releaseMaterials() {
        for (material in materials) material?.let { engine.destroyMaterialInstance(it) }
        materials = emptyArray()
    }

    private fun refuse(reason: String): String {
        failure = reason
        return reason
    }

    private companion object {
        const val LAYER_BIT = 1
        const val HIGHLIGHT_PARAMETER = "emissiveFactor"
        const val HIGHLIGHT_LABEL = "muscleSection"
        const val HIGHLIGHT_RED = 0.62f
        const val HIGHLIGHT_GREEN = 0.42f
        const val HIGHLIGHT_BLUE = 0.10f
    }
}
