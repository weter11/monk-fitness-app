package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.MuscleLayer
import java.io.File

/**
 * The anatomy as the exported muscle assets see it.
 *
 * The atlas is split into one asset per layer — each partition binds its own helpers under the
 * 255-bone limit — so a mesh or skinner fixture is built over the helpers of the layers it names,
 * derived from the manifest rather than named, so a re-export widens these fixtures with no edit —
 * and keeps a section the asset does not carry from passing as one it does.
 */
object MuscleExportFixture {

    const val ANATOMY = "src/main/assets/anatomy/anatomy_v1.json"
    const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"

    /** Both partitions, in the order the runtime builds them. */
    val LAYERS: List<MuscleLayer> = MuscleLayer.entries

    private val ASSETS: Map<MuscleLayer, String> = mapOf(
        MuscleLayer.SUPERFICIAL to "src/main/assets/characters/muscles_sup.glb",
        MuscleLayer.DEEP to "src/main/assets/characters/muscles_deep.glb"
    )

    fun atlas(): HelperBindSet = HelperBindSet.read(File(MANIFEST).readText())

    fun binds(layer: MuscleLayer): HelperBinds = atlas().partition(layer)

    fun assetPath(layer: MuscleLayer): String = ASSETS.getValue(layer)

    /** The shipped definition reduced to the muscles the named partitions export at least one head for. */
    fun definition(
        shipped: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ANATOMY).readText()),
        layers: List<MuscleLayer> = LAYERS
    ): AnatomyDefinition {
        val exported = layers.flatMap { binds(it).binds }.mapTo(HashSet()) { it.head.muscleId }
        return shipped.copy(muscles = shipped.muscles.filter { it.id in exported })
    }
}
