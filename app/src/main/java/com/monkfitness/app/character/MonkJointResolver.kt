package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition

/**
 * Resolves engine joints onto glTF bone nodes by normalized name and common rig aliases; an
 * unresolved joint stays at rest instead of failing the load.
 */
object MonkJointResolver {

    private val candidates: Map<Joint, List<String>> = mapOf(
        Joint.PELVIS to listOf("hips", "pelvis", "root"),
        Joint.LUMBAR to listOf("spine", "spine1", "spine01", "spinelower"),
        Joint.CHEST to listOf("spine2", "chest", "upperchest", "spinetop", "spine3"),
        Joint.NECK_END to listOf("neck", "neck1"),
        Joint.HEAD_POS to listOf("head"),
        Joint.CLAVICLE_A to listOf("leftshoulder", "lclavicle", "claviclel", "shoulderl"),
        Joint.CLAVICLE_P to listOf("rightshoulder", "rclavicle", "clavicler", "shoulderr"),
        Joint.SHOULDER_A to listOf("leftarm", "leftupperarm", "lupperarm", "upperarml"),
        Joint.SHOULDER_P to listOf("rightarm", "rightupperarm", "rupperarm", "upperarmr"),
        Joint.ELBOW_A to listOf("leftforearm", "leftlowerarm", "lforearm", "lowerarml"),
        Joint.ELBOW_P to listOf("rightforearm", "rightlowerarm", "rforearm", "lowerarmr"),
        Joint.HAND_A to listOf("lefthand", "lhand", "handl"),
        Joint.HAND_P to listOf("righthand", "rhand", "handr"),
        Joint.HIP_F to listOf("leftupleg", "leftupperleg", "leftthigh", "lthigh", "upperlegl"),
        Joint.HIP_B to listOf("rightupleg", "rightupperleg", "rightthigh", "rthigh", "upperlegr"),
        Joint.KNEE_F to listOf("leftleg", "leftlowerleg", "leftshin", "lshin", "lowerlegl"),
        Joint.KNEE_B to listOf("rightleg", "rightlowerleg", "rightshin", "rshin", "lowerlegr"),
        Joint.ANKLE_F to listOf("leftfoot", "lfoot", "footl"),
        Joint.ANKLE_B to listOf("rightfoot", "rfoot", "footr"),
        Joint.TOE_F to listOf("lefttoebase", "lefttoe", "ltoe", "toel"),
        Joint.TOE_B to listOf("righttoebase", "righttoe", "rtoe", "toer")
    )

    /** Joint → glTF node index, for every joint the asset can actually drive. */
    fun resolve(rig: GltfRig): Map<Joint, Int> {
        val normalToNode = HashMap<String, Int>()
        rig.nodes.forEach { node ->
            if (node.name.isNotEmpty()) normalToNode.putIfAbsent(GltfRig.normalizeName(node.name), node.index)
        }
        val out = LinkedHashMap<Joint, Int>()
        candidates.forEach { (joint, names) ->
            for (name in names) {
                val index = normalToNode[name]
                if (index != null) {
                    out[joint] = index
                    break
                }
            }
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
