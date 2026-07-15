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
    private val handJointsBuffer = HandJoints()
    private val tempV1 = Vector3()

    // Scratch rotations for resolving a wrist/ankle rotation into the segment (forearm/shank)
    // frame — the joint's own articulation relative to its parent, not its world rotation.
    private val relWrist = JointRotation()
    private val relAnkle = JointRotation()

    // Scratch rotation reused by the modern-path chest-frame reconstruction (no hot-path allocation).
    private val reconRot = JointRotation()

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

    private fun findJointNode(node: SkeletonNode, joint: Joint): SkeletonNode? {
        if (node.joint == joint) return node
        for (child in node.children) {
            val found = findJointNode(child, joint)
            if (found != null) return found
        }
        return null
    }

    /**
     * Ports the legacy cross-product chest-frame reconstruction into the modern rotation-driven
     * path. After FK, the chest is re-derived as a full 3-D orientation from the spine
     * (`pelvis -> chest`, chest-local +Y) and the shoulder line (`shoulderA -> shoulderP`,
     * chest-local -Z): `normal = lean × shoulderLine`, giving an orthonormal basis
     * `(normal, lean, -shoulderLine)`. This guarantees thoracic twist (about +Y) and
     * side-bend (about +X) are captured and propagated to the shoulders/arms/neck/head.
     *
     * For the standard hierarchy (shoulders are children of the chest along its local Z) the
     * reconstructed frame equals the FK-derived frame, so sagittal/neutral poses are unchanged.
     * A degenerate spine or shoulder line (e.g. coincident joints) is skipped — the authored
     * frame is left intact. Allocation-free: reuses the shared column scratch buffers and
     * re-runs FK for the chest subtree only.
     */
    private fun reconstructChestFrame(roots: List<SkeletonNode>) {
        if (roots.isEmpty()) return
        val pelvis = findJointNode(roots[0], Joint.PELVIS) ?: return
        val chest = findJointNode(roots[0], Joint.CHEST) ?: return
        val shoulderA = findJointNode(roots[0], Joint.SHOULDER_A) ?: return
        val shoulderP = findJointNode(roots[0], Joint.SHOULDER_P) ?: return
        if (chest.parent !== pelvis) return

        val pelvisW = pelvis.worldPosition
        val chestW = chest.worldPosition
        val sAW = shoulderA.worldPosition
        val sPW = shoulderP.worldPosition

        // lean (chest-local +Y) = pelvis -> chest
        val lean = tempColY.set(chestW).subtract(pelvisW)
        if (lean.mag() < 1e-4f) return
        lean.normalize()
        // shoulderLine = shoulderA -> shoulderP
        val shVec = tempColZ.set(sAW).subtract(sPW)
        if (shVec.mag() < 1e-4f) return
        shVec.normalize()
        // Guard against a degenerate (collinear) spine/shoulder line.
        tempColX.set(lean).cross(shVec)
        if (tempColX.mag() < 1e-4f) return
        // Build a proper RIGHT-HANDED orthonormal chest frame:
        //   colY = lean (spine / up)
        //   colZ = -(shoulderA - shoulderP)  (chest-forward, toward the passive shoulder)
        //   colX = lean x colZ (lateral). This matches the FK-derived frame for the standard
        //   hierarchy, so a neutral/sagittal trunk is unchanged and twist/side-bend are captured
        //   (the chest frame is derived from the actual shoulder line, not a single authored axis).
        shVec.multiply(-1f)
        tempColX.set(lean).cross(shVec).normalize()

        SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, reconRot)

        // chest.localRotation = parentWorldRotation^-1 * reconRot (parentWorldRotation is a
        // rotation matrix, so its inverse is its transpose).
        SkeletonMath.rotationToMatrix(pelvis.worldRotation, parentMatX, parentMatY, parentMatZ)
        SkeletonMath.rotationToMatrix(reconRot, worldMatX, worldMatY, worldMatZ)
        SkeletonMath.transposeMultiply(
            parentMatX, parentMatY, parentMatZ,
            worldMatX, worldMatY, worldMatZ,
            localMatX, localMatY, localMatZ
        )
        SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, chest.localRotation)

        // Re-run FK for the chest subtree with the corrected local rotation so the shoulders,
        // arms, neck and head are propagated in the reconstructed frame. For the standard
        // hierarchy this re-flattens to identical world positions (no behavioural change).
        chest.updateWorldTransforms(pelvisW, pelvis.worldRotation)
        chest.flatten(outputPose)
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

        // Left Arm (Active): shoulder girdle chain CHEST -> CLAVICLE -> SCAPULA -> SHOULDER
        getNode(Joint.CHEST).addChild(getNode(Joint.CLAVICLE_A))
        getNode(Joint.CLAVICLE_A).addChild(getNode(Joint.SCAPULA_A))
        getNode(Joint.SCAPULA_A).addChild(getNode(Joint.SHOULDER_A))
        getNode(Joint.SHOULDER_A).addChild(getNode(Joint.ELBOW_A))
        getNode(Joint.ELBOW_A).addChild(getNode(Joint.HAND_A))
        getNode(Joint.HAND_A).addChild(getNode(Joint.WRIST_A))
        getNode(Joint.WRIST_A).addChild(getNode(Joint.PALM_A))
        getNode(Joint.PALM_A).addChild(getNode(Joint.KNUCKLES_A))
        getNode(Joint.KNUCKLES_A).addChild(getNode(Joint.FINGERTIPS_A))

        // Right Arm (Passive): shoulder girdle chain CHEST -> CLAVICLE -> SCAPULA -> SHOULDER
        getNode(Joint.CHEST).addChild(getNode(Joint.CLAVICLE_P))
        getNode(Joint.CLAVICLE_P).addChild(getNode(Joint.SCAPULA_P))
        getNode(Joint.SCAPULA_P).addChild(getNode(Joint.SHOULDER_P))
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
                Joint.CLAVICLE_A, Joint.CLAVICLE_P, Joint.SCAPULA_A, Joint.SCAPULA_P -> {
                    // Girdle bones sit coincident with their parent in the legacy bridge; the
                    // scapula derives the shoulder position via its own (identity here) rotation.
                    node.localPosition.set(0f, 0f, 0f)
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
        // PR-04: global contact-constraint / root-repositioning pass. Runs before the FK
        // flatten so fixed support contacts are honored and the root/pelvis is derived from
        // them rather than a fixed authored value. No-op when the pose registered no contacts
        // (the common production case), so non-contact poses are untouched.
        if (pose.roots.isNotEmpty() && pose.hasContacts()) {
            ConstraintSolver.solve(pose, definition)
        }

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

            // PR-09: reconstruct the chest as a full 3-D frame (spine + shoulder line) so
            // thoracic twist/side-bend are captured and propagated to the upper chain.
            reconstructChestFrame(pose.roots)

            refreshJointPresenceCache(pose.roots)

            if (!cachedHasHeelToeF) {
                adjustFootOrientation(
                    outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
                    relativeRotation(outputPose.getJointRotation(Joint.ANKLE_F), outputPose.getJointRotation(Joint.KNEE_F), relAnkle)
                )
            }
            if (!cachedHasHeelToeB) {
                adjustFootOrientation(
                    outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B,
                    relativeRotation(outputPose.getJointRotation(Joint.ANKLE_B), outputPose.getJointRotation(Joint.KNEE_B), relAnkle)
                )
            }
            if (!cachedHasHandDetailA) {
                adjustHandOrientation(
                    outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
                    relativeRotation(outputPose.getJointRotation(Joint.HAND_A), outputPose.getJointRotation(Joint.ELBOW_A), relWrist)
                )
            }
            if (!cachedHasHandDetailP) {
                adjustHandOrientation(
                    outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P,
                    relativeRotation(outputPose.getJointRotation(Joint.HAND_P), outputPose.getJointRotation(Joint.ELBOW_P), relWrist)
                )
            }
        } else {
            // Legacy position-driven compatibility bridge: Compute anatomical foot & hand extensions procedurally.
            // The legacy convention derives each joint's rotation from its bone direction, so there is no wrist/ankle
            // articulation separate from the segment; pass identity so the hand/foot extend rigidly along the segment
            // and the already-world direction is not double-counted by the (forearm/shank) frame (Issue C).
            adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, IDENTITY_ROTATION)
            adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B, IDENTITY_ROTATION)

            adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A, IDENTITY_ROTATION)
            adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P, IDENTITY_ROTATION)

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

    /**
     * Resolves [worldRotation] into the rotation *relative to its parent segment frame*
     * [parentRotation]: `inverse(parentRotation) ∘ worldRotation`. Applying the result to a
     * segment direction (which is already a world vector framed by [parentRotation]) adds only
     * the joint's own articulation instead of re-framing the direction by the whole ancestor
     * chain. Allocation-free: writes into [out].
     */
    private fun relativeRotation(worldRotation: JointRotation, parentRotation: JointRotation, out: JointRotation): JointRotation {
        SkeletonMath.rotationToMatrix(parentRotation, parentMatX, parentMatY, parentMatZ)
        SkeletonMath.rotationToMatrix(worldRotation, worldMatX, worldMatY, worldMatZ)
        SkeletonMath.transposeMultiply(parentMatX, parentMatY, parentMatZ, worldMatX, worldMatY, worldMatZ, localMatX, localMatY, localMatZ)
        SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, out)
        return out
    }

    private fun adjustHandOrientation(
        pose: SkeletonPose,
        elbowId: Joint,
        handId: Joint,
        wristId: Joint,
        palmId: Joint,
        knucklesId: Joint,
        fingertipsId: Joint,
        wristRotation: JointRotation
    ) {
        val elbow = pose.getJoint(elbowId)
        val hand = pose.getJoint(handId)

        val wrist = pose.getJoint(wristId)
        wrist.set(hand)

        tempDir.set(wrist).subtract(elbow).normalize()

        // Promote the wrist to a real joint: compose the authored wrist orientation with
        // the forearm direction so grips (pronation / supination / wrist flexion) are
        // honored by the completed hand. The passed [wristRotation] is the hand's rotation
        // *relative to the forearm (elbow) frame* (not its world rotation), so applying it to
        // the already-world forearm direction does not double-count the trunk/parent frame
        // (Issue C). Identity rotation leaves the result unchanged.
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
        toeId: Joint,
        ankleRotation: JointRotation
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

        // Promote the ankle to a real joint: computeHeelToe composes the authored ankle
        // orientation with this neutral (shank-perpendicular) foot direction and keeps the
        // pitch clamp as a bound on the resulting direction. The passed [ankleRotation] is
        // the ankle's rotation *relative to the shank (knee) frame* (not its world rotation),
        // so applying it to the already-world foot direction does not double-count the
        // trunk/parent frame (Issue C). Identity rotation leaves the neutral direction
        // unchanged, so flat-foot rendering is preserved.
        val foot = definition.foot
        foot.computeHeelToe(ankle, tempFootDir, ankleRotation, pose.getJoint(heelId), pose.getJoint(toeId))
    }
}
