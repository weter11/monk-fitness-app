package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import java.io.File

/**
 * The anatomy as the exported muscle asset sees it.
 *
 * The library now carries the whole superficial and deep atlas while `muscles_sup.glb` is still the
 * regional exporter's Stage-3 output, so a mesh or skinner fixture is built over the muscles the
 * manifest itself binds. Deriving the subset from the manifest rather than naming it is what lets a
 * re-export widen these fixtures with no edit — and keeps a section the asset does not carry from
 * passing as one it does.
 */
object MuscleExportFixture {

    const val ANATOMY = "src/main/assets/anatomy/anatomy_v1.json"
    const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
    const val MUSCLES = "src/main/assets/characters/muscles_sup.glb"

    fun binds(): HelperBinds = HelperBinds.read(File(MANIFEST).readText())

    /** The shipped definition reduced to the muscles [binds] carries at least one helper for. */
    fun definition(
        shipped: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ANATOMY).readText()),
        binds: HelperBinds = binds()
    ): AnatomyDefinition {
        val exported = binds.binds.mapTo(HashSet()) { it.head.muscleId }
        return shipped.copy(muscles = shipped.muscles.filter { it.id in exported })
    }
}
