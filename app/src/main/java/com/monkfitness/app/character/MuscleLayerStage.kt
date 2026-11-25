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
 * writes that section's own instance's tint uniforms; neither touches geometry or renderable material
 * state, because a renderable the scene already references cannot have either replaced while a frame is
 * in flight. Sections are addressed by index into the keys handed in, so no muscle identity or solve
 * verdict crosses into this class.
 */
internal class MuscleLayerStage(
    private val engine: Engine,
    private val scene: Scene,
    private val provider: UbershaderProvider?,
    private val skinner: MuscleLayerSkinner,
    sectionKeys: List<String>
) {

    private val keys: List<String> = sectionKeys.toList()

    /** One mask bit and one highlight per section; the asset's shared instance is never touched. */
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
     * One app-owned instance per section, created from the asset's Material and assigned to its
     * renderable once, before any entity reaches the scene. A selection is a uniform write on one of
     * these; nothing here is created, swapped or destroyed while frames are being drawn.
     */
    private var sectionMaterials: Array<MaterialInstance?> = emptyArray()

    private val highlightSelection = MuscleHighlightSelection(object : MuscleHighlightTarget {
        override fun setHighlight(section: Int, on: Boolean) {
            val instance = sectionMaterials.getOrNull(section) ?: return
            // baseColorFactor is the term every lit variant reads; emissiveFactor is written too for
            // the variants that carry it, but the tint must not depend on that variant being chosen.
            if (on) {
                instance.setParameter(BASE_COLOR_PARAMETER, HIGHLIGHT_RED, HIGHLIGHT_GREEN, HIGHLIGHT_BLUE, 1f)
                instance.setParameter(HIGHLIGHT_PARAMETER, HIGHLIGHT_RED, HIGHLIGHT_GREEN, HIGHLIGHT_BLUE)
            } else {
                instance.setParameter(BASE_COLOR_PARAMETER, MUSCLE_RED, MUSCLE_GREEN, MUSCLE_BLUE, 1f)
                instance.setParameter(HIGHLIGHT_PARAMETER, 0f, 0f, 0f)
            }
        }
    })

    /** Why a selection cannot tint its section, or null when the highlight is live. */
    var highlightError: String? = null
        private set

    /** True while the selected section's own instance carries the highlight uniform. */
    var highlightApplied: Boolean = false
        private set

    /** Why the layer could not be built, or null when it draws. */
    var failure: String? = null
        private set

    /** How many instances the asset carries a renderable for. */
    var renderableCount: Int = 0
        private set

    /** How many instances the last frame showed. */
    val visibleCount: Int get() = state.shownCount

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
            val own = arrayOfNulls<MaterialInstance>(resolved.size)
            // Published before the first instance exists, so a refusal half way through still leaves
            // every instance the teardown has to free reachable from [sectionMaterials].
            sectionMaterials = own
            val renderables = IntArray(resolved.size)
            for (section in resolved.indices) {
                val renderable = renderableManager.getInstance(resolved[section])
                if (renderable == 0) {
                    return refuse("section ${loaded.getName(resolved[section])} has no renderable instance")
                }
                renderables[section] = renderable
            }
            val baseMaterial = renderableManager.getMaterialInstanceAt(renderables[0], 0).material
                ?: return refuse("the layer's first section has no material")
            val missing = APPEARANCE_PARAMETERS.filterNot(baseMaterial::hasParameter)
            if (missing.isNotEmpty()) {
                val reason = "the layer's material has no ${missing.joinToString()}"
                if (HIGHLIGHT_PARAMETER in missing) highlightError = reason
                return refuse(reason)
            }
            for (section in resolved.indices) {
                // createInstance starts from the Material's own defaults, not from the glTF instance
                // gltfio authored, so the asset's surface constants are restated on every section.
                val instance = baseMaterial.createInstance(HIGHLIGHT_LABEL)
                instance.setParameter(BASE_COLOR_PARAMETER, MUSCLE_RED, MUSCLE_GREEN, MUSCLE_BLUE, 1f)
                instance.setParameter(METALLIC_PARAMETER, 0f)
                instance.setParameter(ROUGHNESS_PARAMETER, MUSCLE_ROUGHNESS)
                instance.setParameter(HIGHLIGHT_PARAMETER, 0f, 0f, 0f)
                own[section] = instance
            }
            // Assigned before any entity reaches the scene: no frame can observe a renderable whose
            // material is mid-replacement.
            for (section in resolved.indices) {
                renderableManager.setMaterialInstanceAt(renderables[section], 0, own[section]!!)
            }

            // Only from here is the asset the stage's: every refusal above leaves the finally below
            // the one owner of the loader and asset.
            asset = loaded
            scene.addEntities(loaded.entities)
            entities = resolved
            renderableCount = resolved.size
            buffer = ByteBuffer.allocateDirect(skinner.paletteCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            // Each section is visible until its owning layer is toggled or the session is destroyed.
            for (section in entities.indices) {
                renderableManager.setLayerMask(entities[section], LAYER_BIT, LAYER_BIT)
            }
            state.showAll(entities.size)
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
                        releaseMaterials()
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

    /** Moves the highlight with one uniform write per affected section; no renderable state moves. */
    fun select(section: Int) {
        val target = if (section in entities.indices) section else MuscleSectionState.NO_SECTION
        highlightSelection.select(target)
        state.select(target)
        highlightApplied = target != MuscleSectionState.NO_SECTION && sectionMaterials.getOrNull(target) != null
    }

    fun hideAll() {
        for (section in entities.indices) setShown(section, false)
        select(MuscleSectionState.NO_SECTION)
    }

    /** Releases the asset before the provider that owns its materials. */
    fun destroy() {
        val loaded = asset
        asset = null
        loaded?.let {
            scene.removeEntities(it.entities)
            assetLoader?.destroyAsset(it)
        }
        // Renderables no longer reference these instances; clearing the last tint is the final write,
        // then each instance may die. Freeing one earlier trips Filament's in-use precondition.
        highlightSelection.select(MuscleSectionState.NO_SECTION)
        releaseMaterials()
        assetLoader?.destroy()
        assetLoader = null
        entities = IntArray(0)
        renderableCount = 0
        state.hideAll()
        highlightApplied = false
    }

    private fun releaseMaterials() {
        for (material in sectionMaterials) material?.let { engine.destroyMaterialInstance(it) }
        sectionMaterials = emptyArray()
        highlightApplied = false
    }

    private fun refuse(reason: String): String {
        failure = reason
        return reason
    }

    private companion object {
        const val LAYER_BIT = 1
        const val HIGHLIGHT_PARAMETER = "emissiveFactor"
        const val BASE_COLOR_PARAMETER = "baseColorFactor"
        const val METALLIC_PARAMETER = "metallicFactor"
        const val ROUGHNESS_PARAMETER = "roughnessFactor"
        const val HIGHLIGHT_LABEL = "muscleSection"
        const val HIGHLIGHT_RED = 0.95f
        const val HIGHLIGHT_GREEN = 0.62f
        const val HIGHLIGHT_BLUE = 0.10f

        // `muscles_sup.glb`'s authored material, restated because createInstance starts from the
        // Material's defaults rather than from the instance gltfio configured.
        const val MUSCLE_RED = 0.62f
        const val MUSCLE_GREEN = 0.2f
        const val MUSCLE_BLUE = 0.24f
        const val MUSCLE_ROUGHNESS = 0.55f

        val APPEARANCE_PARAMETERS = listOf(
            BASE_COLOR_PARAMETER,
            METALLIC_PARAMETER,
            ROUGHNESS_PARAMETER,
            HIGHLIGHT_PARAMETER
        )
    }
}
