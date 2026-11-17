package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition

/**
 * Resolves engine joints onto glTF bone nodes by normalized name and the aliases [BoneMap]
 * declares; an unresolved joint stays at rest instead of failing the load.
 */
object MonkJointResolver {

    /** Joint → glTF node index, for every joint the asset can actually drive. */
    fun resolve(rig: GltfRig): Map<Joint, Int> {
        val normalToNode = HashMap<String, Int>()
        rig.nodes.forEach { node ->
            if (node.name.isNotEmpty()) normalToNode.putIfAbsent(GltfRig.normalizeName(node.name), node.index)
        }
        val out = LinkedHashMap<Joint, Int>()
        BoneMap.resolve(rig.nodes.map { it.name }).forEach { (joint, bone) ->
            val index = normalToNode[bone]
            if (index != null) out[joint] = index
        }
        return out
    }

    /**
     * Pelvis height of the engine's rest stance: the same constant its pose builders use, so the
     * retargeted root translation is zero at the start of every exercise.
     */
    fun restPelvisHeight(definition: SkeletonDefinition): Float =
        definition.shinLength + definition.thighLength + 25f
}
