package com.monkfitness.app.character

import kotlin.math.abs

/**
 * What a parsed rig is: every check the asset has to satisfy, measured rather than assumed. It reads
 * a [GltfRig] only, so it runs on the JVM without an engine, a GPU or an Android import.
 */
data class RigReport(
    val jointCount: Int,
    val mappedBones: List<String>,
    val missingBones: List<String>,
    val visualOnly: List<String>,
    val duplicateNames: List<String>,
    val matrixNodes: List<String>,
    val nonFiniteInverseBinds: List<String>,
    /** Worst `|IBM · rest_world − I|` over every skin joint. */
    val worstBindResidual: Float,
    val worstBindJoint: String?,
    val maxInfluencesPerVertex: Int,
    val skinnedRenderables: Int,
    val triangleCount: Int,
    val morphTargetCount: Int,
    val problems: List<String>
) {
    val ok: Boolean get() = problems.isEmpty()

    fun summary(): String = buildString {
        append("joints=$jointCount mapped=${mappedBones.size} visualOnly=${visualOnly.size}")
        append(" triangles=$triangleCount morphs=$morphTargetCount")
        append(" influences=$maxInfluencesPerVertex skinned=$skinnedRenderables")
        append(" worstBindResidual=%.6f ($worstBindJoint)".format(worstBindResidual))
        if (problems.isNotEmpty()) append(" problems=" + problems.joinToString("; "))
    }
}

/** Audits a parsed glTF rig against the character rig contract. */
object RigAssetValidator {

    const val MIN_TRIANGLES = 30_000
    const val MAX_TRIANGLES = 40_000
    const val MAX_INFLUENCES = 4
    const val SKINNED_RENDERABLES = 1

    /** Asset-space tolerance on `IBM · rest_world = I`, in metres. */
    const val BIND_TOLERANCE = 1e-3f

    fun audit(rig: GltfRig): RigReport {
        val problems = ArrayList<String>()

        val jointNames = rig.jointNodes.map { rig.nodes[it].name }
        val jointNameSet = jointNames.toSet()
        val required = BoneMap.byJoint.values.toList()
        val missing = required.filterNot { it in jointNameSet }

        val duplicated = rig.nodes.groupBy { it.name }
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.size > 1 }
            .keys.toList()

        val matrixNodes = rig.nodes.filter { it.usesMatrix }.map { it.name }
        val visualOnly = BoneMap.VISUAL_ONLY.filter { it in jointNameSet }

        val world = rig.restWorldTransforms()
        val nonFinite = ArrayList<String>()
        var worstResidual = 0f
        var worstJoint: String? = null
        for (i in rig.jointNodes.indices) {
            val inverse = rig.inverseBinds.getOrNull(i)
            if (inverse == null) {
                problems += "no inverse bind for joint $i"
                continue
            }
            if (inverse.any { !it.isFinite() }) {
                nonFinite += jointNames[i]
                continue
            }
            val product = Mat4.multiply(inverse, world[rig.jointNodes[i]])
            if (product.any { !it.isFinite() }) {
                nonFinite += jointNames[i]
                continue
            }
            // Compare against the identity entry by entry: a residual is the largest single entry.
            var entry = 0f
            val identity = Mat4.identity()
            for (k in 0 until 16) entry = maxOf(entry, abs(product[k] - identity[k]))
            if (entry > worstResidual) {
                worstResidual = entry
                worstJoint = jointNames[i]
            }
        }

        if (missing.isNotEmpty()) problems += "missing mapped bones: ${missing.joinToString()}"
        if (duplicated.isNotEmpty()) problems += "duplicate node names: ${duplicated.joinToString()}"
        if (matrixNodes.isNotEmpty()) problems += "nodes authored as a raw matrix: ${matrixNodes.joinToString()}"
        if (nonFinite.isNotEmpty()) problems += "non-finite inverse binds: ${nonFinite.joinToString()}"
        if (worstResidual > BIND_TOLERANCE) {
            problems += "IBM·rest world is not the identity at $worstJoint ($worstResidual)"
        }
        if (rig.maxInfluencesPerVertex > MAX_INFLUENCES) {
            problems += "${rig.maxInfluencesPerVertex} skin influences per vertex exceeds $MAX_INFLUENCES"
        }
        if (rig.skinnedNodes.size != SKINNED_RENDERABLES) {
            problems += "${rig.skinnedNodes.size} skinned renderables, expected $SKINNED_RENDERABLES"
        }
        if (rig.triangleCount !in MIN_TRIANGLES..MAX_TRIANGLES) {
            problems += "triangle count ${rig.triangleCount} outside $MIN_TRIANGLES..$MAX_TRIANGLES"
        }
        if (rig.inverseBinds.size != rig.jointNodes.size) {
            problems += "${rig.inverseBinds.size} inverse binds for ${rig.jointNodes.size} joints"
        }

        return RigReport(
            jointCount = rig.jointNodes.size,
            mappedBones = required.filter { it in jointNameSet },
            missingBones = missing,
            visualOnly = visualOnly,
            duplicateNames = duplicated,
            matrixNodes = matrixNodes,
            nonFiniteInverseBinds = nonFinite,
            worstBindResidual = worstResidual,
            worstBindJoint = worstJoint,
            maxInfluencesPerVertex = rig.maxInfluencesPerVertex,
            skinnedRenderables = rig.skinnedNodes.size,
            triangleCount = rig.triangleCount,
            morphTargetCount = rig.morphTargetCount,
            problems = problems
        )
    }
}
