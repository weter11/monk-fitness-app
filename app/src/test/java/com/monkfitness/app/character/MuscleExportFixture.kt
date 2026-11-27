package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.MuscleLayer
import java.io.File

/**
 * The anatomy as the exported muscle assets see it.
 *
 * The atlas is split into one asset per layer, and the runtime draws the superficial one; a mesh or
 * skinner fixture is therefore built over that layer's helpers, derived from the manifest rather
 * than named, so a re-export widens these fixtures with no edit — and keeps a section the asset does
 * not carry from passing as one it does.
 */
object MuscleExportFixture {

    const val ANATOMY = "src/main/assets/anatomy/anatomy_v1.json"
    const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
    const val MUSCLES = "src/main/assets/characters/muscles_sup.glb"

    /** The layer `MUSCLES` draws; the deep layer's asset is exported beside it. */
    val LAYER = MuscleLayer.SUPERFICIAL

    fun atlas(): HelperBindSet = HelperBindSet.read(File(MANIFEST).readText())

    fun binds(): HelperBinds = atlas().partition(LAYER)

    /** The shipped definition reduced to the muscles [binds] carries at least one helper for. */
    fun definition(
        shipped: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ANATOMY).readText()),
        binds: HelperBinds = binds()
    ): AnatomyDefinition {
        val exported = binds.binds.mapTo(HashSet()) { it.head.muscleId }
        return shipped.copy(muscles = shipped.muscles.filter { it.id in exported })
    }
}
