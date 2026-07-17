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

    private fun findJointNode(node: SkeletonNode, joint: Joint): SkeletonNode? {
        if (node.joint == joint) return node
        for (child in node.children) {
            val found = findJointNode(child, joint)
            if (found != null) return found
        }
        return null
    }

    /**
     * Phase 3 (F1) — the finalizer's single local-transform conversion entry point. When
     * [EngineFlags.FINALIZER_OWNS_CONVERSION] is off this is intentionally a no-op so the legacy
     * finalize path is byte-identical and the global flip is purely opt-in. When on, this is where
     * any remaining world↔local frame conversion would be concentrated (the limb `toLocalDirection`
     * bakes already happen in the solver's `bakeIkLimb`; extremity derivation and the chest frame
     * run later in [finalize]). The finalizer is the *exclusive* writer of every local transform —
     * no other component mutates `nodes`/`localPosition`/`localRotation` after this point.
     */
    private fun preConvertPoles(pose: SkeletonPose) {
        if (!EngineFlags.FINALIZER_OWNS_CONVERSION) return
        // Reserved hook: with the flag on, pole→world conversion and any deferred frame work is
        // asserted to live here. The deprecated frame-relative `bakeIkLimb` overload (Phase 1 F4)
        // is the only remaining caller that converts its own pole; it will be deleted in the
        // follow-up that migrates its ~18 pose callers, after which this method owns 100% of
        // conversion. No behaviour change today.
    }

    /**
     * Fallback chest-frame reconstruction for the modern rotation-driven path.
     *
     * When the pose author has NOT explicitly authored a chest rotation (the chest's `localRotation`
     * is identity), the chest world orientation cannot be trusted from FK alone for a non-upright
     * trunk (e.g. a push-up plank oriented by the pelvis/legs), so the chest is re-derived as a full
     * 3-D orientation from the spine (`pelvis -> chest`, chest-local +Y) and the shoulder line
     * (`shoulderA -> shoulderP`, chest-local -Z): `colX = lean × colZ`, giving an orthonormal basis
     * `(colX, lean, -shoulderLine)`. For a symmetric thorax this equals the FK-derived frame, so a
     * sagittal/neutral trunk is unchanged. A degenerate spine or shoulder line is skipped.
     *
     * Issue F: an *authored* chest rotation is never overwritten. The rotation-driven path already
     * propagates the author's thoracic twist / side-bend / flex (and any asymmetry) to the
     * shoulders, arms, neck and head via FK, so deriving the frame from a symmetric shoulder line
     * would discard that intent and force a symmetric-thorax assumption. When `chest.localRotation`
     * is non-identity the function returns early, leaving the authored frame (and the already-
     * flattened world transforms) intact.
     *
     * Phase 3 (F1) — read-only chest-frame guarantee. When [EngineFlags.FINALIZER_OWNS_CONVERSION]
     * is enabled AND the pose carries fixed contacts, the reconstruction is a *no-move* operation:
     * the world positions of every Solver-settled contact end-effector are snapshotted before the
     * reconstruction and asserted unchanged afterwards (B5). If applying the reconstructed frame
     * would displace a contact, the chest frame is rolled back to the Solver-settled value
     * (contacts left exactly where the solver pinned them) and `rootTranslationDelta` is flagged so
     * the validator can surface the residual. The authored-chest early-return above takes precedence
     * — an authored chest never reaches the guard.
     *
     * Allocation-free: reuses the shared column scratch buffers and re-runs FK for the chest subtree
     * only.
     */
    private fun reconstructChestFrame(roots: List<SkeletonNode>, pose: SkeletonPose) {
        if (roots.isEmpty()) return
        val pelvis = findJointNode(roots[0], Joint.PELVIS) ?: return
        val chest = findJointNode(roots[0], Joint.CHEST) ?: return
        val shoulderA = findJointNode(roots[0], Joint.SHOULDER_A) ?: return
        val shoulderP = findJointNode(roots[0], Joint.SHOULDER_P) ?: return
        // The chest's parent is the segment the reconstructed absolute frame is expressed
        // relative to. For the two-segment spine this is the LUMBAR (PELVIS -> LUMBAR -> CHEST);
        // for an inline single-segment hierarchy it is the PELVIS itself. Composing against the
        // actual parent means an authored lower-spine (lumbar/pelvis-tilt) rotation is combined
        // with the thoracic frame instead of being discarded (Issue E). When the lumbar is a
        // pass-through (identity, coincident with the pelvis) this is identical to the old
        // PELVIS-relative reconstruction, so single-bend poses are unchanged.
        val chestParent = chest.parent ?: return

        // Issue F: do NOT overwrite an explicitly authored chest rotation. The modern
        // rotation-driven path already computes the chest's world orientation from its
        // `localRotation` via FK and propagates it to the shoulders, arms, neck and head. The
        // geometric reconstruction below is only a fallback for chests whose rotation was NOT
        // authored (identity) — e.g. a trunk oriented purely by the pelvis/legs (push-up plank).
        //
        // Overwriting an authored rotation would (a) discard the thoracic twist / side-bend /
        // flex the pose author built (`buildChestTwist`, `buildChestOrientation`, the explicit
        // `chest.localRotation.set(...)` calls), and (b) force a symmetric-thorax assumption onto
        // the pose by re-deriving the forward axis from the shoulder line. When the author has
        // expressed intent, that intent is the single source of truth, so leave `chest.localRotation`
        // (and the already-flattened world transforms) untouched.
        if (chest.localRotation.angle > 1e-4f || chest.localRotation.angle < -1e-4f) {
            return
        }

        // Phase 3 (F1/B5) — snapshot Solver-settled contact end-effectors BEFORE mutating the
        // chest frame, so we can assert the reconstruction is read-only on them. Only meaningful
        // when the finalizer owns conversion and the pose actually registered contacts.
        val guardActive = EngineFlags.FINALIZER_OWNS_CONVERSION && pose.contacts.isNotEmpty()
        if (guardActive) buildContactSnapshot(pose)

        val pelvisW = pelvis.worldPosition
        val chestW = chest.worldPosition
        val sAW = shoulderA.worldPosition
        val sPW = shoulderP.worldPosition

        // lean (chest-local +Y) = pelvis -> chest (the full two-segment spine direction)
        val lean = tempColY.set(chestW).subtract(pelvisW)
        if (lean.mag() < 1e-4f) return
        lean.normalize()
        // shoulderLine = shoulderA -> shoulderP
        val shVec = tempColZ.set(sAW).subtract(sPW)
        if (shVec.mag() < 1e-4f) return
        shVec.normalize()
        // Build a proper RIGHT-HANDED orthonormal chest frame:
        //   colY = lean (spine / up)
        //   colZ = -(shoulderA - shoulderP)  (chest-forward, toward the passive shoulder)
        //   colX = lean x colZ (lateral). This matches the FK-derived frame for the standard
        //   hierarchy, so a neutral/sagittal trunk is unchanged.
        // NOTE: use the two-argument `cross(dst)` overload so the result is written into the
        // scratch buffer. The single-argument overload (`Vector3.cross(v)`) allocates a NEW
        // vector and leaves `tempColX` untouched, which previously produced a degenerate
        // matrix (colX == colY) and a wrong chest world rotation (Issue F).
        shVec.multiply(-1f)
        if (lean.cross(shVec, tempColX).mag() < 1e-4f) return
        tempColX.normalize()

        SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, reconRot)

        // chest.localRotation = parentWorldRotation^-1 * reconRot, where parentWorldRotation is
        // the chest's PARENT (lumbar in the standard spine, pelvis inline). A rotation matrix's
        // inverse is its transpose.
        SkeletonMath.rotationToMatrix(chestParent.worldRotation, parentMatX, parentMatY, parentMatZ)
        SkeletonMath.rotationToMatrix(reconRot, worldMatX, worldMatY, worldMatZ)
        SkeletonMath.transposeMultiply(
            parentMatX, parentMatY, parentMatZ,
            worldMatX, worldMatY, worldMatZ,
            localMatX, localMatY, localMatZ
        )
        SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, chest.localRotation)

        // Re-run FK for the chest subtree with the corrected local rotation so the shoulders,
        // arms, neck and head are propagated in the reconstructed frame. Anchored at the chest's
        // parent so the lower-spine segment stays the driver. For the standard hierarchy with a
        // pass-through lumbar this re-flattens to identical world positions (no behavioural change).
        chest.updateWorldTransforms(chestParent.worldPosition, chestParent.worldRotation)
        chest.flatten(outputPose)

        // Phase 3 (F1/B5) — assert the reconstruction did not move any Solver-settled contact
        // end-effector. If it did, roll the chest frame back to the Solver-settled value (leaving
        // contacts exactly where the solver pinned them) and flag the residual. The fallback frame
        // is only ever re-applied to the thorax when it is provably read-only on contacts.
        if (guardActive) enforceContactNoMove(chest, chestParent, pose)
    }

    /**
     * Phase 7 (Gap 7 / F8 / W17) — resolves the gaze from the pose-declared `headTarget` intent.
     *
     * When [EngineFlags.HEAD_TARGET_ENABLED] is false, or the pose declared no `headTarget`, this
     * is a no-op: the legacy `buildHead(direction)` call inside the pose's `buildGaze` already wrote
     * the neck/head local offsets, so the rendered head is byte-identical to the pre-Phase-7 baseline.
     *
     * When the flag is on AND a target is present, this resolver is the **single writer** of the
     * neck/head local offsets (the pose's `buildGaze` skips its own `buildHead` in that mode). The
     * gaze direction is derived from the neck's current world position toward `headTarget.world`,
     * biased upright by `headTarget.upBias`, and written with the exact same math `buildHead` uses
     * (`neck.localPosition = dir * neckLength`, `head.localPosition = dir * 18f`). Because the pose
     * records the synthetic target as `neckWorldPos + gazeDir * 100`, resolving here reproduces the
     * identical direction the pose authored — so the flag-on path is also byte-identical to the
     * legacy direction path. The intent layer's value (a named world target the engine owns) is what
     * is newly present; the geometry is unchanged.
     *
     * The neck/head nodes are located in the already-FK-flattened `pose.roots` tree (updated just
     * before this call in [finalize]); only their *local* positions are rewritten, never the world
     * tree upstream of them.
     */
    private fun resolveHeadTarget(pose: SkeletonPose) {
        if (!EngineFlags.HEAD_TARGET_ENABLED) return
        val target = pose.headTarget ?: return
        if (pose.roots.isEmpty()) return

        val neck = findJointNode(pose.roots[0], Joint.NECK_END) ?: return
        val head = findJointNode(pose.roots[0], Joint.HEAD_POS) ?: return

        // Direction from the (FK-current) neck world position toward the gaze target.
        tempV1.set(target.world).subtract(neck.worldPosition)
        if (tempV1.mag() < 1e-4f) tempV1.set(target.upBias) else tempV1.normalize()
        // Same math as BasePose.buildHead — single source of truth for head orientation.
        neck.localPosition.set(tempV1.x * definition.neckLength, tempV1.y * definition.neckLength, tempV1.z * definition.neckLength)
        head.localPosition.set(tempV1.x * 18f, tempV1.y * 18f, tempV1.z * 18f)
    }

    // Phase 3 (F1/B5) — contact end-effector world-position snapshot, reused across the guard.
    // Keyed by end-joint index; values are the world positions captured before reconstruction.
    private val guardNodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }
    private val guardSnapshotX = FloatArray(Joint.entries.size)
    private val guardSnapshotY = FloatArray(Joint.entries.size)
    private val guardSnapshotZ = FloatArray(Joint.entries.size)
    private val guardContactIdx = IntArray(64)
    private var guardContactCount = 0

    private fun buildContactSnapshot(pose: SkeletonPose) {
        for (i in guardNodeMap.indices) guardNodeMap[i] = null
        guardContactCount = 0
        if (pose.roots.isEmpty()) return
        collectNodes(pose.roots[0])
        for (spec in pose.contacts) {
            val idx = spec.endJoint.index
            val n = guardNodeMap[idx] ?: continue
            guardSnapshotX[idx] = n.worldPosition.x
            guardSnapshotY[idx] = n.worldPosition.y
            guardSnapshotZ[idx] = n.worldPosition.z
            if (guardContactCount < guardContactIdx.size) guardContactIdx[guardContactCount++] = idx
        }
    }

    private fun collectNodes(node: SkeletonNode) {
        guardNodeMap[node.joint.index] = node
        for (child in node.children) collectNodes(child)
    }

    /**
     * Phase 3 (F1/B5) — verifies every snapshotted contact end-effector is unchanged after the
     * chest-frame reconstruction (within [EPS]). If any moved, the reconstructed chest local
     * rotation is rolled back to its Solver-settled value (the chest subtree is re-flattened in
     * the original frame, leaving every contact exactly where the solver pinned it) and
     * [SkeletonPose.rootTranslationDelta] is flagged so the validator can surface the residual.
     */
    private fun enforceContactNoMove(chest: SkeletonNode, chestParent: SkeletonNode, pose: SkeletonPose) {
        var maxMove = 0f
        for (k in 0 until guardContactCount) {
            val idx = guardContactIdx[k]
            val n = guardNodeMap[idx] ?: continue
            val dx = n.worldPosition.x - guardSnapshotX[idx]
            val dy = n.worldPosition.y - guardSnapshotY[idx]
            val dz = n.worldPosition.z - guardSnapshotZ[idx]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (d > maxMove) maxMove = d
        }
        if (maxMove <= EPS) return

        // The reconstruction displaced a Solver-settled contact: roll the chest frame back so the
        // contacts stay put (F1 guarantee). This path only runs for an identity (unauthored) chest,
        // so the Solver-settled chest local rotation IS identity — restoring it re-flattens the
        // subtree to the exact pose the solver settled, leaving every contact where it was pinned.
        chest.localRotation.set(Vector3(0f, 1f, 0f), 0f)
        chest.updateWorldTransforms(chestParent.worldPosition, chestParent.worldRotation)
        chest.flatten(outputPose)

        // Flag the residual so the PELVIS_INTENT / contact rules can surface the unexpected move.
        pose.rootTranslationDelta = kotlin.math.max(pose.rootTranslationDelta, maxMove)
    }

    // Phase 3 (F1/B5) — tolerance for the contact no-move assertion (1e-3f, per IMPLEMENTATION_BRIDGE B5).
    private val EPS = 1e-3f

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

        // Phase 3 (F1/F4): the finalizer is the *exclusive* writer of local transforms. This is
        // the single conversion entry point — any world↔local frame work (pole→world, the
        // `toLocalDirection` limb bakes already performed by the solver, extremity derivation)
        // is concentrated here. With [EngineFlags.FINALIZER_OWNS_CONVERSION] off this is a
        // documented no-op so the legacy path is byte-identical.
        preConvertPoles(pose)

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

            // Issue F: derive the chest frame only when the author left it unauthored (identity);
            // an authored chest rotation (thoracic twist / side-bend / flex, possibly asymmetric)
            // is already propagated to the upper chain by FK and must not be overwritten.
            reconstructChestFrame(pose.roots, pose)

            // Phase 7 (Gap 7 / F8 / W17): resolve the gaze from the pose-declared `headTarget`
            // intent. When the flag is off (or the pose declared no target) this is a no-op and the
            // legacy `buildHead(direction)` path (run during pose.build()) is authoritative, so the
            // output is byte-identical to the pre-Phase-7 baseline. When on, the pose only *declares*
            // the target and this resolver is the single writer of the neck/head local offsets.
            resolveHeadTarget(pose)

            // W1 — Engine ownership of extremity orientation.
            //
            // The engine derives heel/toe and palm/fingertip geometry for every extremity by
            // default (ExtremityOrientationMode.AUTOMATIC). Derivation is skipped ONLY when the pose
            // has *explicitly* opted that extremity into MANUAL_OVERRIDE — i.e. it deliberately
            // authored the endpoint local positions (a stylized toe / grip) and wants them
            // preserved. Ownership is read from the pose's explicit declaration, never inferred from
            // whether the HEEL/TOE/PALM/FINGERTIPS nodes exist (the factory always creates them, so
            // node-existence silently disabled this derivation for every pose — the W1 bug).
            //
            // The relative ankle/wrist rotation (articulation w.r.t. the parent segment) is passed
            // in so inherited torso/limb tilt is removed automatically; identity articulation lays
            // the foot/hand flat along the limb, equalling the FK frame for a neutral limb.
            if (pose.isExtremityAutomatic(Extremity.FOOT_F)) {
                adjustFootOrientation(
                    outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F,
                    relativeRotation(outputPose.getJointRotation(Joint.ANKLE_F), outputPose.getJointRotation(Joint.KNEE_F), relAnkle)
                )
            }
            if (pose.isExtremityAutomatic(Extremity.FOOT_B)) {
                adjustFootOrientation(
                    outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B,
                    relativeRotation(outputPose.getJointRotation(Joint.ANKLE_B), outputPose.getJointRotation(Joint.KNEE_B), relAnkle)
                )
            }
            if (pose.isExtremityAutomatic(Extremity.HAND_A)) {
                adjustHandOrientation(
                    outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
                    relativeRotation(outputPose.getJointRotation(Joint.HAND_A), outputPose.getJointRotation(Joint.ELBOW_A), relWrist)
                )
            }
            if (pose.isExtremityAutomatic(Extremity.HAND_P)) {
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
