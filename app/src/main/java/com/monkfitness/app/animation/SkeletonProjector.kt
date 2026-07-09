package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SkeletonProjector is responsible for transforming a 3D SkeletonPose into a 2D ProjectedSkeleton.
 * Reuses internal buffers to eliminate per-frame allocations.
 */
class SkeletonProjector {
    private val torsoPoints = Array(8) { ProjectedPoint() }
    private val tempV = Vector3(0f, 0f, 0f)
    private val tempV2 = Vector3(0f, 0f, 0f)
    private val tempV3 = Vector3(0f, 0f, 0f)
    private val tempV4 = Vector3(0f, 0f, 0f)

    private val shadowJoints = arrayOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)

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
        val jointEntries = Joint.entries
        for (i in buffer.joints.indices) {
            val id = jointEntries[i]
            camera.project(pose.getJoint(id), width, height, buffer.joints[id.index])
        }

        // 2. Indicators
        buffer.indicators[0].update(buffer.joints[Joint.HEAD_POS.index], Joint.HEAD_POS)
        buffer.indicators[1].update(buffer.joints[Joint.WRIST_A.index], Joint.WRIST_A, indicator = true)

        // 3. Bones
        val engineBones = engine.bones
        buffer.boneCount = engineBones.size
        for (i in 0 until engineBones.size) {
            val bone = engineBones[i]
            if (i < buffer.bones.size) {
                buffer.bones[i].update(
                    buffer.joints[bone.parentJoint.index],
                    buffer.joints[bone.childJoint.index],
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
        for (i in buffer.joints.indices) {
            val it = buffer.joints[i]
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

        val lean = tempV.set(chest).subtract(pelvis).normalize()
        val shVec = tempV2.set(shoulderA).subtract(shoulderP).normalize()
        val chestNorm = lean.cross(shVec, tempV3).normalize()

        val offC = tempV.set(chestNorm).multiply(style.torsoChestDepth)
        val offH = tempV2.set(chestNorm).multiply(style.torsoHipDepth)

        camera.project(tempV3.set(hipF).add(offH), width, height, torsoPoints[0])
        camera.project(tempV3.set(hipF).subtract(offH), width, height, torsoPoints[1])
        camera.project(tempV3.set(hipB).add(offH), width, height, torsoPoints[2])
        camera.project(tempV3.set(hipB).subtract(offH), width, height, torsoPoints[3])
        camera.project(tempV3.set(shoulderA).add(offC), width, height, torsoPoints[4])
        camera.project(tempV3.set(shoulderA).subtract(offC), width, height, torsoPoints[5])
        camera.project(tempV3.set(shoulderP).add(offC), width, height, torsoPoints[6])
        camera.project(tempV3.set(shoulderP).subtract(offC), width, height, torsoPoints[7])

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
            camera.project(tempV.set(x.toFloat(), 0f, -170f), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(tempV.set(x.toFloat(), 0f, 170f), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        for (z in -170..170 step 65) {
            if (lineIdx >= buffer.gridLines.size) break
            camera.project(tempV.set(-260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(tempV.set(260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        buffer.gridLineCount = lineIdx

        for (i in 0 until shadowJoints.size) {
            val id = shadowJoints[i]
            val pt = pose.getJoint(id)
            camera.project(tempV.set(pt.x, 0f, pt.z), width, height, buffer.shadowPoints[i])
        }
    }
}
