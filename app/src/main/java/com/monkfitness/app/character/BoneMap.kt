package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import kotlin.math.PI

/** How a bone the engine cannot drive is allowed to move: named, never inherited by accident. */
enum class TwistPolicy { TWIST_NONE, TWIST_FRACTIONAL, TWIST_ANIMATED }

/**
 * The one declared joint → bone map: driven, undrivable and visual-only bones are all named here, so
 * coverage is assertable against the map instead of against one asset's inventory.
 */
object BoneMap {

    /** Every joint the engine can drive; scapula is listed although no shipped asset carries the bone. */
    val byJoint: Map<Joint, String> = mapOf(
        Joint.PELVIS to "Hips",
        Joint.LUMBAR to "Spine",
        Joint.CHEST to "Chest",
        Joint.NECK_END to "Neck",
        Joint.HEAD_POS to "Head",
        Joint.CLAVICLE_A to "LeftShoulder",
        Joint.CLAVICLE_P to "RightShoulder",
        Joint.SCAPULA_A to "LeftScapula",
        Joint.SCAPULA_P to "RightScapula",
        Joint.SHOULDER_A to "LeftArm",
        Joint.SHOULDER_P to "RightArm",
        Joint.ELBOW_A to "LeftForeArm",
        Joint.ELBOW_P to "RightForeArm",
        Joint.HAND_A to "LeftHand",
        Joint.HAND_P to "RightHand",
        Joint.HIP_F to "LeftUpLeg",
        Joint.HIP_B to "RightUpLeg",
        Joint.KNEE_F to "LeftLeg",
        Joint.KNEE_B to "RightLeg",
        Joint.ANKLE_F to "LeftFoot",
        Joint.ANKLE_B to "RightFoot",
        Joint.TOE_F to "LeftToeBase",
        Joint.TOE_B to "RightToeBase"
    )

    /**
     * Distinct bones with weights summing to 1, propagated proximal → distal. Empty while the map
     * is one-to-one: the split is authored only for a rig that carries the second spine bone.
     */
    val distribution: Map<Joint, List<Pair<String, Float>>> = emptyMap()

    /**
     * Asset → engine axes, `Ry(+90°)`: the asset's `+Z` forward and `+X` left become engine `+X`
     * forward and `−Z` left. Declared here only; the position-driven retarget is its first consumer.
     */
    val basis: Quat = Quat.fromAxisAngle(0f, 1f, 0f, (PI / 2).toFloat())

    /**
     * Joints no bone can drive, declared so the driven and undrivable sets partition the engine's
     * joints and a coverage test can tell "no bone" from "the map forgot it".
     */
    val UNMAPPED: List<Joint> = listOf(
        Joint.WRIST_A, Joint.WRIST_P,
        Joint.PALM_A, Joint.PALM_P,
        Joint.KNUCKLES_A, Joint.KNUCKLES_P,
        Joint.FINGERTIPS_A, Joint.FINGERTIPS_P,
        Joint.HEEL_F, Joint.HEEL_B
    )

    /**
     * Bones a character may carry that no engine joint drives: the crown and five single-segment
     * fingers per hand, which ride their mapped parent rigidly.
     */
    val VISUAL_ONLY: List<String> = listOf(
        "HeadTop_End",
        "LeftHandThumb", "LeftHandIndex", "LeftHandMiddle", "LeftHandRing", "LeftHandPinky",
        "RightHandThumb", "RightHandIndex", "RightHandMiddle", "RightHandRing", "RightHandPinky"
    )

    /** Applies to every unmapped bone until a character declares otherwise. */
    val twistPolicy: TwistPolicy = TwistPolicy.TWIST_NONE

    /**
     * Accepted spellings per joint in match priority order, already normalized: a first-party rig
     * uses the first entry, the rest keep third-party naming conventions loadable.
     */
    val aliases: Map<Joint, List<String>> = mapOf(
        Joint.PELVIS to listOf("hips", "pelvis", "root"),
        Joint.LUMBAR to listOf("spine", "spine1", "spine01", "spinelower"),
        Joint.CHEST to listOf("spine2", "chest", "upperchest", "spinetop", "spine3"),
        Joint.NECK_END to listOf("neck", "neck1"),
        Joint.HEAD_POS to listOf("head"),
        Joint.CLAVICLE_A to listOf("leftshoulder", "lclavicle", "claviclel", "shoulderl"),
        Joint.CLAVICLE_P to listOf("rightshoulder", "rclavicle", "clavicler", "shoulderr"),
        Joint.SCAPULA_A to listOf("leftscapula", "lscapula", "scapulal"),
        Joint.SCAPULA_P to listOf("rightscapula", "rscapula", "scapular"),
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

    /**
     * Joint → the normalized bone name it matched. A rig without a bone for a joint simply leaves
     * that joint out, which is why an incomplete rig still animates.
     */
    fun resolve(boneNames: Collection<String>): Map<Joint, String> {
        val normalized = boneNames.mapTo(HashSet()) { GltfRig.normalizeName(it) }
        val out = LinkedHashMap<Joint, String>()
        aliases.forEach { (joint, names) ->
            names.firstOrNull(normalized::contains)?.let { out[joint] = it }
        }
        return out
    }
}
