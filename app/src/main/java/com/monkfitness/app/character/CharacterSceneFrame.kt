package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.MuscleInstanceId
import com.monkfitness.app.anatomy.MuscleLayer

/**
 * Linear base colours for the two procedural surfaces, plus how far a station's tendon fraction
 * tints it toward [tendon]. Values are material inputs, not a style: the shader multiplies them in.
 */
class SkinPalette(
    val muscleRed: Float,
    val muscleGreen: Float,
    val muscleBlue: Float,
    val tendonRed: Float,
    val tendonGreen: Float,
    val tendonBlue: Float,
    /** `0..1`; the fraction of the tendon colour the path's end stations take. */
    val tendonBlend: Float = 0.55f
) {

    init {
        for (component in floatArrayOf(muscleRed, muscleGreen, muscleBlue, tendonRed, tendonGreen, tendonBlue, tendonBlend)) {
            require(component in 0f..1f) { "palette component $component outside 0..1" }
        }
    }

    companion object {
        val DEFAULT = SkinPalette(
            muscleRed = 0.62f, muscleGreen = 0.20f, muscleBlue = 0.24f,
            tendonRed = 0.86f, tendonGreen = 0.82f, tendonBlue = 0.74f
        )
    }
}

/**
 * The asset's morph weights, one slot per declared target, written in place.
 *
 * A preset is longer than some assets declare, so [apply] writes the prefix and never grows the
 * array: Filament reads exactly [count] weights and a longer buffer is a silent truncation.
 */
class MorphWeightSet(val count: Int) {

    init {
        require(count >= 0) { "count $count cannot be negative" }
    }

    val weights: FloatArray = FloatArray(count)

    /** Writes the preset's first [count] entries; returns how many landed. */
    fun apply(preset: MorphologyPreset): Int {
        val written = minOf(count, preset.weights.size)
        for (index in 0 until written) weights[index] = preset.weights[index]
        return written
    }

    /** Copies the live weights into [out] from index 0; returns the number of entries written. */
    fun copyInto(out: FloatArray): Int {
        val written = minOf(count, out.size)
        System.arraycopy(weights, 0, out, 0, written)
        return written
    }

    fun clear() {
        weights.fill(0f)
    }
}

/**
 * The CPU-side geometry a procedural layer is rebuilt into every time the anatomy moves: parallel
 * vertex arrays and an index buffer, all sized once.
 *
 * [claim] is a capacity gate, not a resize: a layer that cannot fit is refused before any vertex is
 * written, so a partial tube can never be uploaded as if it were whole.
 */
class DynamicGeometrySlot(vertexCapacity: Int, indexCapacity: Int) {

    init {
        require(vertexCapacity >= 0) { "vertexCapacity $vertexCapacity cannot be negative" }
        require(indexCapacity >= 0) { "indexCapacity $indexCapacity cannot be negative" }
        require(indexCapacity % 3 == 0) { "indexCapacity ${indexCapacity} is not whole triangles" }
    }

    val positions = FloatArray(vertexCapacity * 3)
    val normals = FloatArray(vertexCapacity * 3)
    val uvs = FloatArray(vertexCapacity * 2)
    /** Linear RGB per vertex, so a tendon end can be tinted without a second draw call. */
    val colors = FloatArray(vertexCapacity * 3)
    val indices = IntArray(indexCapacity)

    var vertexCount: Int = 0
        private set

    var indexCount: Int = 0
        private set

    /**
     * Advances every time a builder writes new index content, so a mesh can tell whether the array
     * still holds the topology it wrote and skip re-emitting it.
     */
    var topologyRevision: Long = 0L
        private set

    val triangleCount: Int get() = indexCount / 3

    val vertexCapacity: Int get() = positions.size / 3

    val indexCapacity: Int get() = indices.size

    /** Returns the slot to empty geometry; capacity and arrays stay. */
    fun begin() {
        vertexCount = 0
        indexCount = 0
    }

    /** Reserves room for one layer inside the slot; false when either count is too large. */
    fun claim(vertices: Int, indices: Int): Boolean =
        vertices >= 0 && indices >= 0 && vertices <= vertexCapacity - vertexCount &&
            indices <= indexCapacity - indexCount

    /** Appends one vertex; the caller has already passed [claim]. */
    fun pushVertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, v: Float,
        r: Float, g: Float, b: Float
    ) {
        val vertex = vertexCount
        require(vertex < vertexCapacity) { "vertex $vertex outside ${vertexCapacity}" }
        positions[vertex * 3] = x
        positions[vertex * 3 + 1] = y
        positions[vertex * 3 + 2] = z
        normals[vertex * 3] = nx
        normals[vertex * 3 + 1] = ny
        normals[vertex * 3 + 2] = nz
        uvs[vertex * 2] = u
        uvs[vertex * 2 + 1] = v
        colors[vertex * 3] = r
        colors[vertex * 3 + 1] = g
        colors[vertex * 3 + 2] = b
        vertexCount = vertex + 1
    }

    /** Appends one triangle; the caller has already passed [claim]. */
    fun pushTriangle(a: Int, b: Int, c: Int) {
        require(indexCount + 3 <= indexCapacity) { "triangle at $indexCount outside ${indexCapacity}" }
        indices[indexCount] = a
        indices[indexCount + 1] = b
        indices[indexCount + 2] = c
        indexCount += 3
    }

    /**
     * Reserves [count] vertices and returns the index they start at, for a builder that fills
     * [positions] and the other attribute arrays itself. This is the capacity gate; the run is not
     * re-checked per vertex, which is what keeps a 7,000-vertex rebuild one pass.
     */
    internal fun claimVertexRun(count: Int): Int {
        require(count >= 0 && vertexCount + count <= vertexCapacity) {
            "a vertex run of $count at $vertexCount is outside $vertexCapacity"
        }
        val start = vertexCount
        vertexCount += count
        return start
    }

    /**
     * Reserves [count] index slots and returns the offset to write them at, for a builder that fills
     * [indices] in one pass. This is the capacity gate; the run itself is not re-checked per index.
     */
    internal fun claimIndexRun(count: Int): Int {
        require(count >= 0 && indexCount + count <= indexCapacity) {
            "an index run of $count at $indexCount is outside $indexCapacity"
        }
        val start = indexCount
        indexCount += count
        topologyRevision++
        return start
    }

    /**
     * Re-declares a topology [indices] already holds, so a frame that only moved the pose keeps the
     * bytes — and the upload — the previous one left. Index content is a function of how many heads
     * drew and the per-head layout, never of where their paths are.
     */
    internal fun reuseIndices(count: Int) {
        require(count >= 0 && count <= indexCapacity) { "an index count of $count is outside $indexCapacity" }
        indexCount = count
    }

    /** Whether [index] is referenced by the geometry this slot currently holds. */
    fun references(index: Int): Boolean {
        for (i in 0 until indexCount) if (indices[i] == index) return true
        return false
    }
}

/** Which drawable a layer fills. The body is the skinned asset; the muscle tubes are procedural. */
enum class CharacterLayerId {
    BODY,
    SUPERFICIAL_MUSCLE,
    DEEP_MUSCLE
}

/** One drawable layer of the character scene: what it fills, how it is tinted, whether it draws. */
class CharacterLayer(
    val id: CharacterLayerId,
    val palette: SkinPalette = SkinPalette.DEFAULT,
    visible: Boolean = true
) {

    var visible: Boolean = visible
        internal set

    /** The anatomy layer this drawable shows, or null for the skinned body and the overlay. */
    val muscleLayer: MuscleLayer? get() = when (id) {
        CharacterLayerId.SUPERFICIAL_MUSCLE -> MuscleLayer.SUPERFICIAL
        CharacterLayerId.DEEP_MUSCLE -> MuscleLayer.DEEP
        CharacterLayerId.BODY -> null
    }
}

/**
 * Which instances draw this frame: by layer, minus the individual instances a reviewer hid.
 *
 * Immutable, so a frame can hold one without the controller's next toggle rewriting what the frame
 * was built from; the controller hands out a new value instead.
 */
data class MuscleSelection(
    val layers: Set<MuscleLayer> = MuscleLayer.entries.toSet(),
    val hidden: Set<MuscleInstanceId> = emptySet(),
    /** The procedural tubes are a diagnostic view, not part of the shipped character. */
    val showsTubes: Boolean = false,
    /** The helper-bound muscle asset, which is the layer Stage 3 replaces the tubes with. */
    val showsMeshes: Boolean = false
) {

    fun shows(instance: MuscleInstanceId, layer: MuscleLayer): Boolean =
        showsTubes && layer in layers && instance !in hidden

    /** Whether the skinned asset draws [instance]; the tubes have their own predicate. */
    fun showsMesh(instance: MuscleInstanceId, layer: MuscleLayer): Boolean =
        showsMeshes && layer in layers && instance !in hidden

    /** True when at least one instance can draw: either view is on and some layer is selected. */
    val showsAnyLayer: Boolean get() = (showsTubes || showsMeshes) && layers.isNotEmpty()

    fun withLayer(layer: MuscleLayer, on: Boolean): MuscleSelection =
        copy(layers = if (on) layers + layer else layers - layer)

    fun withInstance(instance: MuscleInstanceId, on: Boolean): MuscleSelection =
        copy(hidden = if (on) hidden - instance else hidden + instance)
}

/**
 * The dev-only switchboard for the procedural layers, and the one place their revision advances.
 *
 * [revision] changes exactly when the selection does, so the render path rebuilds the tubes on a
 * toggle and never on a frame that only moved the pose.
 */
class CharacterLayerController(
    val layers: List<CharacterLayer> = listOf(
        CharacterLayer(CharacterLayerId.BODY),
        CharacterLayer(CharacterLayerId.SUPERFICIAL_MUSCLE),
        CharacterLayer(CharacterLayerId.DEEP_MUSCLE)
    )
) {

    private var current = MuscleSelection()

    val selection: MuscleSelection get() = current

    /** Advances only when the selection actually changed. */
    var revision: Int = 0
        private set

    fun layer(id: CharacterLayerId): CharacterLayer? = layers.firstOrNull { it.id == id }

    /**
     * Toggles a muscle layer and returns its new state. [CharacterLayerId.BODY] never toggles: the
     * tubes are read against the body, and a scene with neither is not a diagnostic.
     */
    fun toggleLayer(id: CharacterLayerId): Boolean {
        val muscleLayer = layer(id)?.muscleLayer ?: return true
        val on = muscleLayer !in current.layers
        current = current.withLayer(muscleLayer, on)
        publish()
        return on
    }

    fun toggleTubes(on: Boolean = !current.showsTubes): Boolean {
        if (on == current.showsTubes) return on
        current = current.copy(showsTubes = on)
        publish()
        return on
    }

    /** Switches the helper-bound muscle asset, the layer the tubes are the diagnostic stand-in for. */
    fun toggleMeshes(on: Boolean = !current.showsMeshes): Boolean {
        if (on == current.showsMeshes) return on
        current = current.copy(showsMeshes = on)
        publish()
        return on
    }

    /** Hides or shows one instance without touching its layer. */
    fun setInstanceVisible(instance: MuscleInstanceId, visible: Boolean) {
        val next = current.withInstance(instance, visible)
        if (next == current) return
        current = next
        publish()
    }

    /** True when [layer]'s instances are shown at all this frame. */
    fun showsLayer(layer: MuscleLayer): Boolean =
        layer(idOf(layer))?.visible == true && (current.showsTubes || current.showsMeshes)

    private fun publish() {
        for (layer in layers) {
            val muscleLayer = layer.muscleLayer
            layer.visible = muscleLayer == null || muscleLayer in current.layers
        }
        revision++
    }

    private fun idOf(layer: MuscleLayer): CharacterLayerId = when (layer) {
        MuscleLayer.SUPERFICIAL -> CharacterLayerId.SUPERFICIAL_MUSCLE
        MuscleLayer.DEEP -> CharacterLayerId.DEEP_MUSCLE
    }
}

/**
 * One frame of character state handed from the anatomy solve to the renderer: the solved frame, the
 * geometry it was tessellated into, and the appearance inputs that do not move with the pose.
 *
 * Every field is owned by the caller that built it, and the same carriers are rewritten on the next
 * frame — a reader consumes them before the next solve, never holds them.
 */
class CharacterSceneFrame(
    val anatomy: AnatomyFrame,
    val skin: SkinPalette = SkinPalette.DEFAULT,
    val morph: MorphWeightSet,
    val geometry: DynamicGeometrySlot,
    selection: MuscleSelection = MuscleSelection()
) {

    /** What the geometry is built for; [select] is the only way it changes. */
    var selection: MuscleSelection = selection
        private set

    /** The selection revision [geometry] was last built for; `-1` until the first build. */
    var builtRevision: Int = -1
        private set

    /** Adopts the controller's current selection; a no-op when it is the one already held. */
    fun select(selection: MuscleSelection) {
        this.selection = selection
    }

    /** True when the geometry does not yet reflect [revision]. */
    fun needsRebuild(revision: Int): Boolean = builtRevision != revision

    fun markBuilt(revision: Int) {
        builtRevision = revision
    }
}
