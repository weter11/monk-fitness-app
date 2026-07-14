package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SkeletonPoseFinalizer is responsible for completing the 3D pose before it is projected to screen space.
 * It adds biomechanical details like Heel/Toe and Hand segments that are not part of the core PoseBuilder logic.
 * This stage ensures the 3D skeleton is anatomically complete and that all world positions and rotations are derived by FK traversal.
 *
 * Under the progressive rotation-driven migration, SkeletonPoseFinalizer serves as a compatibility layer:
 * - If a custom hierarchy (pose.roots) is supplied, it bypasses legacy position-to-rotation reconstruction.
 * - If pose.roots is empty, it runs the legacy reconstruction bridge to derive 3D transforms with zero regressions.
 */
class SkeletonPoseFinalizer(
    private val definition: SkeletonDefinition
) {
    private val outputPose = SkeletonPose()
    private val tempDir = Vector3()
    private val tempForwardHint = Vector3()
    private val tempFootDir = Vector3()
    private val tempHorizontalDir = Vector3()
    private val handJointsBuffer = HandJoints()
    private val tempV1 = Vector3()

    private var cachedRootsIdentity: List<SkeletonNode>? = null
    private var cachedHasHeelToeF = false
    private var cachedHasHeelToeB = false
    private var cachedHasHandDetailA = false
    private var cachedHasHandDetailP = false

    private fun containsJoint(roots: List<SkeletonNode>, joint: Joint): Boolean {
        for (i in 0 until roots.size) {
            if (containsJointNode(roots[i], joint)) return true
        }
        return false
    }

    private fun containsJointNode(node: SkeletonNode, joint: Joint): Boolean {
        if (node.joint == joint) return true
        val children = node.children
        for (i in 0 until children.size) {
            if (containsJointNode(children[i], joint)) return true
        }
        return false
    }

    private fun refreshJointPresenceCache(roots: List<SkeletonNode>) {
        if (roots === cachedRootsIdentity) return
        cachedRootsIdentity = roots
        cachedHasHeelToeF = containsJoint(roots, Joint.HEEL_F) && containsJoint(roots, Joint.TOE_F)
        cachedHasHeelToeB = containsJoint(roots, Joint.HEEL_B) && containsJoint(roots, Joint.TOE_B)
        cachedHasHandDetailA = containsJoint(roots, Joint.PALM_A) && containsJoint(roots, Joint.FINGERTIPS_A)
        cachedHasHandDetailP = containsJoint(roots, Joint.PALM_P) && containsJoint(roots, Joint.FINGERTIPS_P)
    }

    // Pre-allocated standard SkeletonNode hierarchy (for legacy compat path)
    private var roots: List<SkeletonNode>? = null
    private val nodesMap = Array<SkeletonNode?>(Joint.entries.size) { null }

    private val IDENTITY_ROTATION = JointRotation()
    private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

    // Scratch buffers for 3D rotation math to achieve zero allocations in the hot path
    private val tempColX = Vector3()
    private val tempColY = Vector3()
    private val tempColZ = Vector3()
    private val tempBoneVec = Vector3()
    private val parentMatX = Vector3()
    private val parentMatY = Vector3()
    private val parentMatZ = Vector3()
    private val worldMatX = Vector3()
    private val worldMatY = Vector3()
    private val worldMatZ = Vector3()
    private val localMatX = Vector3()
    private val localMatY = Vector3()
    private val localMatZ = Vector3()

    private fun ensureHierarchy() {
        if (roots != null) return

        // Create all nodes
        for (joint in Joint.entries) {
            nodesMap[joint.index] = SkeletonNode(joint)
        }

        fun getNode(joint: Joint): SkeletonNode = nodesMap[joint.index]!!

        // Parent-child connections
        // Spine
        getNode(Joint.PELVIS).addChild(getNode(Joint.CHEST))
        getNode(Joint.CHEST).addChild(getNode(Joint.NECK_END))
        getNode(Joint.NECK_END).addChild(getNode(Joint.HEAD_POS))

        // Left Arm (Active)
        getNode(Joint.CHEST).addChild(getNode(Joint.SHOULDER_A))
        getNode(Joint.SHOULDER_A).addChild(getNode(Joint.ELBOW_A))
        getNode(Joint.ELBOW_A).addChild(getNode(Joint.HAND_A))
        getNode(Joint.HAND_A).addChild(getNode(Joint.WRIST_A))
        getNode(Joint.WRIST_A).addChild(getNode(Joint.PALM_A))
        getNode(Joint.PALM_A).addChild(getNode(Joint.KNUCKLES_A))
        getNode(Joint.KNUCKLES_A).addChild(getNode(Joint.FINGERTIPS_A))

        // Right Arm (Passive)
        getNode(Joint.CHEST).addChild(getNode(Joint.SHOULDER_P))
        getNode(Joint.SHOULDER_P).addChild(getNode(Joint.ELBOW_P))
        getNode(Joint.ELBOW_P).addChild(getNode(Joint.HAND_P))
        getNode(Joint.HAND_P).addChild(getNode(Joint.WRIST_P))
        getNode(Joint.WRIST_P).addChild(getNode(Joint.PALM_P))
        getNode(Joint.PALM_P).addChild(getNode(Joint.KNUCKLES_P))
        getNode(Joint.KNUCKLES_P).addChild(getNode(Joint.FINGERTIPS_P))

        // Left Leg (Foreground)
        getNode(Joint.PELVIS).addChild(getNode(Joint.HIP_F))
        getNode(Joint.HIP_F).addChild(getNode(Joint.KNEE_F))
        getNode(Joint.KNEE_F).addChild(getNode(Joint.ANKLE_F))
        getNode(Joint.ANKLE_F).addChild(getNode(Joint.HEEL_F))
        getNode(Joint.ANKLE_F).addChild(getNode(Joint.TOE_F))

        // Right Leg (Background)
        getNode(Joint.PELVIS).addChild(getNode(Joint.HIP_B))
        getNode(Joint.HIP_B).addChild(getNode(Joint.KNEE_B))
        getNode(Joint.KNEE_B).addChild(getNode(Joint.ANKLE_B))
        getNode(Joint.ANKLE_B).addChild(getNode(Joint.HEEL_B))
        getNode(Joint.ANKLE_B).addChild(getNode(Joint.TOE_B))

        roots = listOf(getNode(Joint.PELVIS))
    }

    private fun setupTransforms(node: SkeletonNode, parentWorldRot: JointRotation, pose: SkeletonPose) {
        val parentNode = node.parent
        if (parentNode == null) {
            // Root node (PELVIS)
            node.localPosition.set(pose.getJoint(node.joint))

            // Set pelvis rotation based on Chest-Pelvis direction (spine)
            val chestPos = pose.getJoint(Joint.CHEST)
            val pelvisPos = pose.getJoint(Joint.PELVIS)
            tempBoneVec.set(chestPos).subtract(pelvisPos)
            SkeletonMath.getRotationToAlign(Vector3(0f, 1f, 0f), tempBoneVec, tempV1, node.localRotation)

            node.worldPosition.set(node.localPosition)
            node.worldRotation.copyFrom(node.localRotation)
        } else {
            // Child node
            val parentPos = parentNode.worldPosition
            val childPos = pose.getJoint(node.joint)

            // Apply anatomical offsets in local space as defined by the parent's segment
            when (node.joint) {
                Joint.HIP_F -> {
                    node.localPosition.set(0f, 0f, -definition.hipWidth)
                }
                Joint.HIP_B -> {
                    node.localPosition.set(0f, 0f, definition.hipWidth)
                }
                Joint.SHOULDER_A -> {
                    node.localPosition.set(0f, 0f, -definition.shoulderWidth)
                }
                Joint.SHOULDER_P -> {
                    node.localPosition.set(0f, 0f, definition.shoulderWidth)
                }
                Joint.NECK_END -> {
                    node.localPosition.set(0f, definition.neckLength, 0f)
                }
                Joint.HEAD_POS -> {
                    node.localPosition.set(0f, 18f, 0f)
                }
                else -> {
                    // Standard joint: calculate local position rotated backward by parent's world rotation
                    tempBoneVec.set(childPos).subtract(parentPos)
                    SkeletonMath.rotAround(tempBoneVec, parentWorldRot.axis, -parentWorldRot.angle, node.localPosition)
                }
            }

            // Compute the world rotation of this joint
            if (node.joint == Joint.CHEST) {
                // Chest is the torso, compute its 3D rotation matrix to avoid position subtractions in projector/renderer
                val chestPos = pose.getJoint(Joint.CHEST)
                val pelvisPos = pose.getJoint(Joint.PELVIS)
                val shoulderA = pose.getJoint(Joint.SHOULDER_A)
                val shoulderP = pose.getJoint(Joint.SHOULDER_P)

                val lean = tempColY.set(chestPos).subtract(pelvisPos).normalize()
                val shVec = tempColZ.set(shoulderA).subtract(shoulderP).normalize()
                val chestNorm = lean.cross(shVec, tempColX).normalize()

                // colZ should be -shVec
                tempColZ.multiply(-1f)

                SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, node.worldRotation)
            } else {
                // Shortest arc rotation aligning Vector3(1f, 0f, 0f) with bone direction
                tempBoneVec.set(childPos).subtract(parentPos)
                SkeletonMath.getRotationToAlign(tempBoneVec, node.worldRotation)
            }

            // localRotation is the relative rotation: R_local = R_parent.inverse * R_world
            // Compute relative rotation via matrix transpose multiplication:
            SkeletonMath.rotationToMatrix(parentWorldRot, parentMatX, parentMatY, parentMatZ)
            SkeletonMath.rotationToMatrix(node.worldRotation, worldMatX, worldMatY, worldMatZ)
            SkeletonMath.transposeMultiply(parentMatX, parentMatY, parentMatZ, worldMatX, worldMatY, worldMatZ, localMatX, localMatY, localMatZ)
            SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, node.localRotation)

            // Set temporary world transforms during setup pass
            node.worldPosition.set(childPos)
        }

        // Setup children
        for (child in node.children) {
            setupTransforms(child, node.worldRotation, pose)
        }
    }

    /**
     * Finalizes the 3D pose. Supports both modern rotation-driven custom hierarchies and legacy position-driven poses.
     */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        outputPose.copyFrom(pose)

        if (pose.roots.isNotEmpty()) {
            // Modern rotation-driven path: Execute Forward Kinematics traversal directly using direct local joint rotations/offsets
            if (!pose.isTransformsUpdated) {
                val size = pose.roots.size
                for (i in 0 until size) {
                    pose.roots[i].updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
                }
                for (i in 0 until size) {
                    pose.roots[i].flatten(outputPose)
                }
                pose.isTransformsUpdated = true
            }
            outputPose.roots = pose.roots

            refreshJointPresenceCache(pose.roots)

            if (!cachedHasHeelToeF) {
                adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
            }
            if (!cachedHasHeelToeB) {
                adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            }
            if (!cachedHasHandDetailA) {
                adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
            }
            if (!cachedHasHandDetailP) {
                adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
            }
        } else {
            // Legacy position-driven compatibility bridge: Compute anatomical foot & hand extensions procedurally
            adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
            adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)

            adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
            adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

            // Ensure standard compatibility hierarchy is created
            ensureHierarchy()

            // Run setupTransforms to reconstruct orientation parameters and set proper local anatomical offsets
            setupTransforms(nodesMap[Joint.PELVIS.index]!!, IDENTITY_ROTATION, outputPose)

            // Propagate world positions and world rotations via Forward Kinematics traversal
            roots!!.forEach { it.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION) }

            // Flatten standard compatibility hierarchy back into outputPose
            roots!!.forEach { it.flatten(outputPose) }

            outputPose.roots = roots!!
        }

        return outputPose
    }

    private fun adjustHandOrientation(
        pose: SkeletonPose,
        elbowId: Joint,
        handId: Joint,
        wristId: Joint,
        palmId: Joint,
        knucklesId: Joint,
        fingertipsId: Joint
    ) {
        val elbow = pose.getJoint(elbowId)
        val hand = pose.getJoint(handId)

        val wrist = pose.getJoint(wristId)
        wrist.set(hand)

        tempDir.set(wrist).subtract(elbow).normalize()

        // Promote the wrist to a real joint: compose the authored wrist orientation with
        // the forearm direction so grips (pronation / supination / wrist flexion) are
        // honored by the completed hand. Identity rotation leaves the result unchanged.
        val wristRotation = pose.getJointRotation(handId)

        val handDef = definition.hand
        handDef.computeHandJoints(wrist, tempDir, wristRotation, handJointsBuffer)

        pose.getJoint(palmId).set(handJointsBuffer.palm)
        pose.getJoint(knucklesId).set(handJointsBuffer.knuckles)
        pose.getJoint(fingertipsId).set(handJointsBuffer.fingertips)
    }

    private fun adjustFootOrientation(
        pose: SkeletonPose,
        kneeId: Joint,
        ankleId: Joint,
        heelId: Joint,
        toeId: Joint
    ) {
        val knee = pose.getJoint(kneeId)
        val ankle = pose.getJoint(ankleId)
        val providedToe = pose.getJoint(toeId)

        val shank = (ankle - knee).normalize()

        if ((providedToe - ankle).mag() > 1e-3) {
            tempForwardHint.set(providedToe).subtract(ankle).normalize()
        } else {
            tempForwardHint.set(1f, 0f, 0f)
        }

        tempFootDir.set(shank).multiply(tempForwardHint.dot(shank))
        tempFootDir.set(tempForwardHint.x - tempFootDir.x, tempForwardHint.y - tempFootDir.y, tempForwardHint.z - tempFootDir.z)

        if (tempFootDir.mag() < 1e-3) {
            val worldDown = Vector3(0f, -1f, 0f)
            tempFootDir.set(shank).multiply(worldDown.dot(shank))
            tempFootDir.set(worldDown.x - tempFootDir.x, worldDown.y - tempFootDir.y, worldDown.z - tempFootDir.z)
        }
        tempFootDir.normalize()

        val pitch = atan2(tempFootDir.y, sqrt(tempFootDir.x * tempFootDir.x + tempFootDir.z * tempFootDir.z))
        val clampedPitch = pitch.coerceIn(definition.foot.minPitch, definition.foot.maxPitch)

        if (abs(pitch - clampedPitch) > 1e-3) {
            tempHorizontalDir.set(tempFootDir.x, 0f, tempFootDir.z).normalize()
            tempFootDir.set(
                tempHorizontalDir.x * cos(clampedPitch),
                sin(clampedPitch),
                tempHorizontalDir.z * cos(clampedPitch)
            )
        }

        val foot = definition.foot
        foot.computeHeelToe(ankle, tempFootDir, pose.getJoint(heelId), pose.getJoint(toeId))
    }
}
