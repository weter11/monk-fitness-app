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
 * The authored muscle layers: one skinned renderable per muscle and side, driven by the same palette
 * the body uploads followed by one bone per helper, so a solved path moves the mesh without touching
 * a vertex on the CPU.
 *
 * Each partition is its own skin — `muscles_sup.glb` and `muscles_deep.glb` bind disjoint helper
 * ranges — so a frame is skinned twice into two palettes and the sections of each asset are uploaded
 * from their own. Sections are addressed by index into the keys handed in, and each key names the
 * asset that carries it, so no muscle identity, side or solve verdict crosses into this class.
 *
 * The assets are loaded once and never rebuilt. A hide moves one section's layer mask and a selection
 * writes that section's own instance's tint uniforms; neither touches geometry or renderable material
 * state, because a renderable the scene already references cannot have either replaced while a frame
 * is in flight. A partition that refuses to load is torn down on its own and reported: the other one
 * keeps drawing, which is what keeps a bad export from taking the character down with it.
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

    /** Which asset carries each section, and its renderable's place inside that asset. */
    private val sectionLayer: IntArray
    private val sectionLocal: IntArray

    /** The keys each asset carries, in the frame's own section order. */
    private val layerKeys: Array<Array<String>>

    /** One mask bit and one highlight per section; the asset's shared instance is never touched. */
    val state = MuscleSectionState(keys.size)

    private val renderableManager: RenderableManager = engine.renderableManager

    private var prepared = false

    /** Section count the loaded assets publish between them. */
    var renderableCount: Int = 0
        private set

    /** Frames whose palettes reached the GPU; a frozen counter means the upload never ran. */
    var skinUploads: Long = 0
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
        for (section in keys.indices) collected[sectionLayer[section]] += keys[section]
        layerKeys = Array(assets.size) { index -> collected[index].toTypedArray() }
    }

    private val highlightSelection = MuscleHighlightSelection(object : MuscleHighlightTarget {
        override fun setHighlight(section: Int, on: Boolean) {
            val instance = instanceOf(section) ?: return
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

    /** Why an asset could not be built, or null when every partition loaded. */
    var failure: String? = null
        private set

    /** How many sections the last frame showed. */
    val visibleCount: Int
        get() {
            var shown = 0
            for (section in keys.indices) {
                if (state.draws(section) && entityOf(section) != 0) shown++
            }
            return shown
        }

    /**
     * Loads, validates and publishes every partition. Returns the failures joined, or null when both
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
        var loaded = 0
        for (index in layers.indices) loaded += layers[index].renderables.size
        renderableCount = loaded
        return publishFailures()
    }

    /**
     * Skins one solved frame into each partition's palette and uploads it. [body] is the rig's own
     * bone matrices; each partition's helpers are appended after them, so one frame drives both skins.
     */
    fun skin(frame: AnatomyFrame, body: FloatArray): Boolean {
        var uploaded = false
        for (index in layers.indices) {
            val layer = layers[index]
            if (layer.asset == null) continue
            layer.skinner.write(frame, body, layer.matrices)
            layer.buffer.clear()
            layer.buffer.put(layer.matrices)
            for (renderable in layer.renderables) {
                layer.buffer.rewind()
                renderableManager.setBonesAsMatrices(renderable, layer.buffer, layer.jointCount, 0)
            }
            uploaded = true
        }
        if (uploaded) skinUploads++
        return uploaded
    }

    /** Shows or hides one section; an unchanged mask costs no renderable write. */
    fun setShown(section: Int, on: Boolean) {
        if (!state.show(section, on)) return
        val entity = entityOf(section)
        if (entity == 0) return
        renderableManager.setLayerMask(entity, LAYER_BIT, if (on) LAYER_BIT else 0)
    }

    /** Moves the highlight with one uniform write per affected section; no renderable state moves. */
    fun select(section: Int) {
        val target = if (section in keys.indices && entityOf(section) != 0) section else MuscleSectionState.NO_SECTION
        highlightSelection.select(target)
        state.select(target)
        highlightApplied = target != MuscleSectionState.NO_SECTION && instanceOf(target) != null
    }

    fun hideAll() {
        for (section in keys.indices) setShown(section, false)
        select(MuscleSectionState.NO_SECTION)
    }

    /** Drains the render thread, then releases each asset before the provider that owns its materials. */
    fun destroy() {
        // A queued frame referencing an instance freed here is a native crash inside FEngine::loop.
        engine.flushAndWait()
        highlightSelection.select(MuscleSectionState.NO_SECTION)
        for (index in layers.indices) {
            val layer = layers[index]
            val loaded = layer.asset
            layer.asset = null
            if (loaded != null) scene.removeEntities(loaded.entities)
            layer.renderables = IntArray(0)
            try {
                if (loaded != null) layer.loader?.destroyAsset(loaded)
            } finally {
                releaseMaterials(layer)
                layer.loader?.destroy()
                layer.loader = null
            }
        }
        renderableCount = 0
        state.hideAll()
        highlightApplied = false
    }

    private fun load(index: Int, materialProvider: UbershaderProvider) {
        val layer = layers[index]
        val direct = ByteBuffer.allocateDirect(layer.bytes.size).order(ByteOrder.nativeOrder())
        direct.put(layer.bytes)
        direct.rewind()
        val loader = AssetLoader(engine, materialProvider, EntityManager.get())
        layer.loader = loader
        var loaded: FilamentAsset? = null
        try {
            loaded = loader.createAsset(direct)
                ?: throw IllegalStateException("gltfio could not load ${layer.label}")
            val resources = ResourceLoader(engine)
            try {
                resources.loadResources(loaded)
            } finally {
                resources.destroy()
            }

            layer.jointCount = if (loaded.instance.skinCount > 0) loaded.instance.getJointCountAt(0) else 0
            require(layer.jointCount == layer.skinner.paletteCount) {
                "${layer.label} skins ${layer.jointCount} joints, its palette has ${layer.skinner.paletteCount}"
            }

            val byName = HashMap<String, Int>(layerKeys[index].size * 2)
            for (entity in loaded.renderableEntities) {
                val name = loaded.getName(entity) ?: continue
                // Two renderables under one name would tie an instance's visibility to the wrong draw.
                if (byName.put(name, entity) != null) {
                    throw IllegalStateException("${layer.label} names two renderables $name")
                }
            }
            val resolved = IntArray(layerKeys[index].size) { local ->
                byName[layerKeys[index][local]]
                    ?: throw IllegalStateException("${layer.label} has no section named ${layerKeys[index][local]}")
            }
            val own = arrayOfNulls<MaterialInstance>(resolved.size)
            // Published before the first instance exists, so a refusal half way through still leaves
            // every instance the teardown has to free reachable from [Layer.materials].
            layer.materials = own
            val renderables = IntArray(resolved.size)
            for (local in resolved.indices) {
                val renderable = renderableManager.getInstance(resolved[local])
                require(renderable != 0) {
                    "section ${layerKeys[index][local]} has no renderable instance"
                }
                renderables[local] = renderable
            }
            val baseMaterial = renderableManager.getMaterialInstanceAt(renderables[0], 0).material
                ?: throw IllegalStateException("${layer.label} has no material")
            val missing = APPEARANCE_PARAMETERS.filterNot(baseMaterial::hasParameter)
            if (missing.isNotEmpty()) {
                val reason = "the material of ${layer.label} has no ${missing.joinToString()}"
                if (HIGHLIGHT_PARAMETER in missing) layer.highlightError = reason
                throw IllegalStateException(reason)
            }
            for (local in resolved.indices) {
                // createInstance starts from the Material's own defaults, not from the glTF instance
                // gltfio authored, so the asset's surface constants are restated on every section.
                val instance = baseMaterial.createInstance(HIGHLIGHT_LABEL)
                instance.setParameter(BASE_COLOR_PARAMETER, MUSCLE_RED, MUSCLE_GREEN, MUSCLE_BLUE, 1f)
                instance.setParameter(METALLIC_PARAMETER, 0f)
                instance.setParameter(ROUGHNESS_PARAMETER, MUSCLE_ROUGHNESS)
                instance.setParameter(HIGHLIGHT_PARAMETER, 0f, 0f, 0f)
                own[local] = instance
            }
            // Assigned before any entity reaches the scene: no frame can observe a renderable whose
            // material is mid-replacement.
            for (local in resolved.indices) {
                renderableManager.setMaterialInstanceAt(renderables[local], 0, own[local]!!)
            }

            layer.asset = loaded
            layer.renderables = renderables
            layer.matrices = FloatArray(layer.skinner.paletteCount * 16)
            layer.buffer = ByteBuffer.allocateDirect(layer.skinner.paletteCount * 16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            scene.addEntities(loaded.entities)
            // Each section is visible until its owning layer is toggled or the session is destroyed.
            for (local in layerKeys[index].indices) {
                renderableManager.setLayerMask(resolved[local], LAYER_BIT, LAYER_BIT)
            }
        } catch (error: Throwable) {
            layer.failure = "${layer.label}: " +
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
            // Nothing of this partition reaches the scene or the stats: the other skin is unaffected.
            try {
                if (loaded != null) scene.removeEntities(loaded.entities)
            } finally {
                try {
                    loaded?.let { loader.destroyAsset(it) }
                } finally {
                    releaseMaterials(layer)
                    loader.destroy()
                }
            }
            layer.loader = null
            layer.renderables = IntArray(0)
            layer.jointCount = 0
        }
    }

    private fun publishFailures(): String? {
        var joined: String? = null
        var error: String? = null
        for (layer in layers) {
            val failure = layer.failure ?: continue
            joined = if (joined == null) failure else "$joined; $failure"
            val highlight = layer.highlightError
            if (highlight != null) error = if (error == null) highlight else "$error; $highlight"
        }
        if (joined != null) failure = joined
        if (error != null) highlightError = error
        if (renderableCount > 0) state.showAll()
        return joined
    }

    private fun entityOf(section: Int): Int {
        if (section !in keys.indices) return 0
        val local = sectionLocal[section]
        val renderables = layers[sectionLayer[section]].renderables
        return if (local < renderables.size) renderables[local] else 0
    }

    private fun instanceOf(section: Int): MaterialInstance? {
        if (section !in keys.indices) return null
        val local = sectionLocal[section]
        val materials = layers[sectionLayer[section]].materials
        return if (local < materials.size) materials[local] else null
    }

    private fun releaseMaterials(layer: Layer) {
        for (material in layer.materials) material?.let { engine.destroyMaterialInstance(it) }
        layer.materials = emptyArray()
    }

    /**
     * One partition's Filament state. Every field has an empty value until [load] publishes it, so a
     * refusal leaves nothing a later frame or teardown could dereference.
     */
    private class Layer(val spec: MuscleLayerAsset) {
        val label: String get() = spec.label
        val bytes: ByteArray get() = spec.bytes
        val skinner: MuscleLayerSkinner get() = spec.skinner

        var loader: AssetLoader? = null
        var asset: FilamentAsset? = null
        var jointCount = 0
        var renderables = IntArray(0)
        var materials: Array<MaterialInstance?> = emptyArray()
        var matrices = FloatArray(0)
        var buffer: FloatBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var failure: String? = null
        var highlightError: String? = null
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

        // The muscle assets' authored material, restated because createInstance starts from the
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
