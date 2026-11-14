package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Transforms a 3D SkeletonPose into a 2D ProjectedSkeleton, reusing internal buffers so a frame
 * allocates nothing.
 */
class SkeletonProjector {
    private val torsoPoints = Array(8) { ProjectedPoint() }
    private val tempV = Vector3(0f, 0f, 0f)
    private val tempV2 = Vector3(0f, 0f, 0f)
    private val tempV3 = Vector3(0f, 0f, 0f)

    // Preallocated default unit vectors to completely prevent GC pressure/allocations in the hot path
    private val defaultSpineDir = Vector3(0f, 1f, 0f)
    private val defaultShoulderDir = Vector3(0f, 0f, -1f)

    private val shadowJoints = arrayOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)

    fun project(
        pose: SkeletonPose,
        camera: Camera,
        engine: SkeletonEngine,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton,
        groundLevel: Float = 0f
    ) {
        val style = engine.style

        val jointEntries = Joint.entries
        for (i in 0 until jointEntries.size) {
            val joint = jointEntries[i]
            camera.project(pose.getJoint(joint), width, height, buffer.joints[joint.index])
        }

        buffer.indicators[0].update(buffer.joints[Joint.HEAD_POS.index], Joint.HEAD_POS)
        buffer.indicators[1].update(buffer.joints[Joint.WRIST_A.index], Joint.WRIST_A, indicator = true)

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

        updateTorsoFaces(pose, camera, style, width, height, buffer)

        updateGround(pose, camera, width, height, buffer, groundLevel)

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

        // Torso rotation comes from the CHEST world rotation — the first-class source of truth.
        val chestRot = pose.getJointRotation(Joint.CHEST)

        // Derive lean (spine) and shVec (shoulder line) from the rotation, not from positions.
        val lean = SkeletonMath.rotAround(defaultSpineDir, chestRot.axis, chestRot.angle, tempV).normalize()
        val shVec = SkeletonMath.rotAround(defaultShoulderDir, chestRot.axis, chestRot.angle, tempV2).normalize()
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

    private fun updateGround(
        pose: SkeletonPose,
        camera: Camera,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton,
        groundLevel: Float
    ) {
        // The floor is a lattice of screen-sized cells, not a patch of world space. A world patch is
        // right at the zoom its pose was composed at and wrong at any other: the media hero fits the
        // figure, so it draws a pose two to five times larger, and a cell authored at 65 world units
        // then spans 120-300 px — the fourteen lines of a two-per-cent stroke read as two stray
        // lines, which is no floor at all. So the cell is measured in pixels at the drawn zoom, and
        // the patch is grown until it covers the surface, so the floor never ends inside it.
        val zoom = camera.zoom.coerceAtLeast(MINIMUM_GRID_ZOOM)
        val cosYaw = abs(cos(camera.yaw))
        val sinYaw = abs(sin(camera.yaw))
        val rise = abs(sin(camera.pitch))
        // A ground point's screen travel per world unit. At the authored yaw the x axis runs mostly
        // into depth and the z axis mostly across, so the two axes cover different screen dimensions
        // and their extents are not interchangeable. A level camera draws no floor at all, so each
        // divisor has a floor of its own rather than an infinity.
        val xPerUnit = (cosYaw * zoom).coerceAtLeast(MINIMUM_GRID_ZOOM)
        val yPerUnit = (sinYaw * rise * zoom).coerceAtLeast(MINIMUM_GRID_ZOOM)
        val zPerUnit = (sinYaw * zoom).coerceAtLeast(MINIMUM_GRID_ZOOM)
        var step = GRID_CELL_PX / max(xPerUnit, zPerUnit)
        val halfX = max(GRID_MIN_HALF_SPAN, GROUND_COVER_FRACTION * height / yPerUnit)
        val halfZ = max(GRID_MIN_HALF_SPAN, GROUND_COVER_FRACTION * width / zPerUnit)
        // A surface large enough to need more lines than the buffer holds gets a coarser lattice
        // rather than a truncated patch: the floor must still reach every edge of what is drawn.
        val cells = 2f * (halfX + halfZ) / step + 2f
        if (cells > buffer.gridLines.size) {
            step = 2f * (halfX + halfZ) / (buffer.gridLines.size - 2)
        }

        var lineIdx = 0
        val linesX = (halfX / step).toInt()
        for (i in -linesX..linesX) {
            if (lineIdx >= buffer.gridLines.size) break
            val line = buffer.gridLines[lineIdx]
            camera.project(
                tempV.set(i * step, groundLevel, -halfZ),
                width, height, line.p1
            )
            camera.project(
                tempV2.set(i * step, groundLevel, halfZ),
                width, height, line.p2
            )
            lineIdx++
        }
        val linesZ = (halfZ / step).toInt()
        for (i in -linesZ..linesZ) {
            if (lineIdx >= buffer.gridLines.size) break
            val line = buffer.gridLines[lineIdx]
            camera.project(
                tempV.set(-halfX, groundLevel, i * step),
                width, height, line.p1
            )
            camera.project(
                tempV2.set(halfX, groundLevel, i * step),
                width, height, line.p2
            )
            lineIdx++
        }
        buffer.gridLineCount = lineIdx

        for (i in 0 until shadowJoints.size) {
            val id = shadowJoints[i]
            val pt = pose.getJoint(id)
            camera.project(tempV.set(pt.x, groundLevel, pt.z), width, height, buffer.shadowPoints[i])
        }
    }

    private companion object {
        /** The lattice's cell on screen: the same floor at any zoom, on any surface. */
        const val GRID_CELL_PX = 64f

        /** How far past each edge the floor reaches, so no edge shows the patch ending. */
        const val GROUND_COVER_FRACTION = 0.62f

        /** A floor smaller than this is not a floor: keeps a tiny surface from collapsing the patch. */
        const val GRID_MIN_HALF_SPAN = 65f

        /** A degenerate viewport divides the extents by zero; the floor still has to be drawn. */
        const val MINIMUM_GRID_ZOOM = 1e-3f
    }
}
