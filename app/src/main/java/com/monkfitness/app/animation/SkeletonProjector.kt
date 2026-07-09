package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SkeletonProjector is responsible for transforming a 3D SkeletonPose into a 2D ProjectedSkeleton.
 * Reuses internal buffers to eliminate per-frame allocations.
 */
class SkeletonProjector {
    private val torsoPoints = Array(8) { ProjectedPoint() }
    private val tempV = Vector3(0f, 0f, 0f)

    fun project(
        pose: SkeletonPose,
        camera: Camera,
        engine: SkeletonEngine,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton
    ) {
        val style = engine.style

        // 1. Project Joints
        Joint.entries.forEach { id ->
            camera.project(pose.getJoint(id), width, height, buffer.jointsMap[id.ordinal])
        }

        // 2. Indicators
        buffer.indicators[0].update(buffer.jointsMap[Joint.HEAD_POS.ordinal], Joint.HEAD_POS)
        buffer.indicators[1].update(buffer.jointsMap[Joint.WRIST_A.ordinal], Joint.WRIST_A, indicator = true)

        // 3. Bones
        buffer.boneCount = engine.bones.size
        engine.bones.forEachIndexed { i, bone ->
            if (i < buffer.bones.size) {
                buffer.bones[i].update(
                    buffer.jointsMap[bone.parentJoint.ordinal],
                    buffer.jointsMap[bone.childJoint.ordinal],
                    bone.thickness,
                    bone.colorMultiplier
                )
            }
        }

        // 4. Torso faces
        updateTorsoFaces(pose, camera, style, width, height, buffer)

        // 5. Ground
        updateGround(pose, camera, width, height, buffer)

        // 6. Depth Range
        var dMin = Float.MAX_VALUE
        var dMax = Float.MIN_VALUE
        buffer.jointsMap.forEach {
            dMin = min(dMin, it.depth)
            dMax = max(dMax, it.depth)
        }
        buffer.depthMin = dMin
        buffer.depthMax = dMax
    }

    private fun updateTorsoFaces(
        pose: SkeletonPose,
        camera: Camera,
        style: SkeletonStyle,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton
    ) {
        val hipF = pose.getJoint(Joint.HIP_F); val hipB = pose.getJoint(Joint.HIP_B)
        val shoulderA = pose.getJoint(Joint.SHOULDER_A); val shoulderP = pose.getJoint(Joint.SHOULDER_P)
        val pelvis = pose.getJoint(Joint.PELVIS); val chest = pose.getJoint(Joint.CHEST)

        val lean = (chest - pelvis).normalize()
        val shVec = (shoulderA - shoulderP).normalize()
        val chestNorm = lean.cross(shVec).normalize()

        val offC = chestNorm * style.torsoChestDepth
        val offH = chestNorm * style.torsoHipDepth

        camera.project(hipF + offH, width, height, torsoPoints[0])
        camera.project(hipF - offH, width, height, torsoPoints[1])
        camera.project(hipB + offH, width, height, torsoPoints[2])
        camera.project(hipB - offH, width, height, torsoPoints[3])
        camera.project(shoulderA + offC, width, height, torsoPoints[4])
        camera.project(shoulderA - offC, width, height, torsoPoints[5])
        camera.project(shoulderP + offC, width, height, torsoPoints[6])
        camera.project(shoulderP - offC, width, height, torsoPoints[7])

        buffer.faceCount = 6
        buffer.faces[0].update(torsoPoints[4], torsoPoints[6], torsoPoints[2], torsoPoints[0]) // front
        buffer.faces[1].update(torsoPoints[7], torsoPoints[5], torsoPoints[1], torsoPoints[3]) // back
        buffer.faces[2].update(torsoPoints[5], torsoPoints[4], torsoPoints[0], torsoPoints[1]) // left
        buffer.faces[3].update(torsoPoints[6], torsoPoints[7], torsoPoints[3], torsoPoints[2]) // right
        buffer.faces[4].update(torsoPoints[5], torsoPoints[7], torsoPoints[6], torsoPoints[4]) // top
        buffer.faces[5].update(torsoPoints[0], torsoPoints[2], torsoPoints[3], torsoPoints[1]) // bottom
    }

    private fun updateGround(pose: SkeletonPose, camera: Camera, width: Float, height: Float, buffer: ProjectedSkeleton) {
        var lineIdx = 0
        for (x in -260..260 step 65) {
            if (lineIdx >= buffer.gridLines.size) break
            camera.project(Vector3(x.toFloat(), 0f, -170f), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(Vector3(x.toFloat(), 0f, 170f), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        for (z in -170..170 step 65) {
            if (lineIdx >= buffer.gridLines.size) break
            camera.project(Vector3(-260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(Vector3(260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        buffer.gridLineCount = lineIdx

        val shadowJoints = listOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)
        shadowJoints.forEachIndexed { i, id ->
            val pt = pose.getJoint(id)
            camera.project(Vector3(pt.x, 0f, pt.z), width, height, buffer.shadowPoints[i])
        }
    }
}
