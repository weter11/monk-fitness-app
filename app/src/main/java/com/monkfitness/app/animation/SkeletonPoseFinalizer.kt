package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SkeletonPoseFinalizer is responsible for completing the 3D pose before it is projected to screen space.
 * It adds biomechanical details like Heel/Toe and Hand segments that are not part of the core PoseBuilder logic.
 * This stage ensures the 3D skeleton is anatomically complete and that all world positions and rotations are derived by FK traversal.
 *
 * The finalizer operates exclusively over a populated `pose.roots` hierarchy (the rotation-driven model).
 * A `SkeletonPose` reaching `finalize` must always supply `roots`; the legacy position-to-rotation
 * reconstruction bridge was removed in Phase E (RFC_ENGINE_CLEANUP_PLAN), so empty-`roots` input is
 * a programming error caught by a `check` at the top of [finalize].
 */
class SkeletonPoseFinalizer(
    private val definition: SkeletonDefinition
) {
    private val outputPose = SkeletonPose()
    private val tempDir = Vector3()
    private val tempForwardHint = Vector3()
    private val tempFootDir = Vector3()
    private val tempFootNormal = Vector3()
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
     * Phase 3 (F1) — read-only chest-frame guarantee. The finalizer owns conversion,
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
        // when the pose actually registered contacts. (Phase B collapsed FINALIZER_OWNS_CONVERSION
        // to its true branch.)
        val guardActive = pose.contacts.isNotEmpty()
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
     * This resolver is the **single writer** of the neck/head local offsets (the legacy
     * direction-based `buildHead` fallback in `buildGaze` was removed once this path was proven
     * byte-identical — `HeadTargetBaselineTest`, maxDeviation ~6e-5).
     *
     * If the pose declared no `headTarget` (a non-gaze pose), this is a no-op. Otherwise the gaze
     * direction is derived from the neck's current world position toward `headTarget.world`, biased
     * upright by `headTarget.upBias`, and written with the same math `buildHead` uses
     * (`neck.localPosition = dir * neckLength`, `head.localPosition = dir * 18f`). Because the pose
     * records the synthetic target as `neckWorldPos + gazeDir * 100`, resolving here reproduces the
     * identical direction the pose authored — the geometry equals the pre-Phase-7 baseline while the
     * intent layer (a named world target the engine owns) is what is newly present.
     *
     * The neck/head nodes are located in the already-FK-flattened `pose.roots` tree (updated just
     * before this call in [finalize]); only their *local* positions are rewritten, never the world
     * tree upstream of them.
     */
    private fun resolveHeadTarget(pose: SkeletonPose) {
        val target = pose.headTarget ?: return

        val neck = findJointNode(pose.roots[0], Joint.NECK_END) ?: return
        val head = findJointNode(pose.roots[0], Joint.HEAD_POS) ?: return

        // Direction from the (FK-current) neck world position toward the gaze target.
        tempV1.set(target.world).subtract(neck.worldPosition)
        if (tempV1.mag() < 1e-4f) tempV1.set(target.upBias) else tempV1.normalize()
        // Same math as BasePose.buildHead — single source of truth for head orientation.
        neck.localPosition.set(tempV1.x * definition.neckLength, tempV1.y * definition.neckLength, tempV1.z * definition.neckLength)
        head.localPosition.set(tempV1.x * 18f, tempV1.y * 18f, tempV1.z * 18f)

        // The neck/head local offsets are written AFTER the initial FK flatten (above), so they
        // have not yet propagated into the output pose. Re-propagate the neck->head subtree so
        // HEAD_POS carries the extended neck length instead of collapsing onto the neck (which
        // otherwise fails the validator's BONE_LENGTH rule on NECK_END->HEAD_POS). The neck's
        // parent (chest) world transform is already current from the flatten above.
        val neckParent = neck.parent ?: return
        neck.updateWorldTransforms(neckParent.worldPosition, neckParent.worldRotation)
        neck.flatten(outputPose)
    }

    /**
     * B2 (RFC_BRANCH_B_IMPLEMENTATION §2) — consumes the §1.1 `spineIntent` and `jointIntents`
     * carriers. Every trunk/hip/girdle/extremity authoring helper now forwards its intent through the
     * sole-mutator `IntentBuilder`, so these carriers are populated after a build. The Finalizer is
     * now the documented consumer: it re-derives each declared node rotation from the carrier and
     * re-propagates the full FK tree.
     *
     * The helpers ALSO write the node during `build()` (so build-time logic that reads a node's world
     * transform — e.g. arm IK under a rotating chest — keeps working), so the carrier re-application
     * here is **idempotent**: the applied rotation equals the authored node rotation and the re-FK'd
     * world state equals the pre-B2 baseline exactly (proven by `FinalizerIntentConsumersTest`,
     * maxDeviation 0.0). The carrier therefore genuinely drives the final geometry while remaining a
     * pure no-op on output until the node-write is deleted in B4.
     *
     * Always consumes the carriers (Phase B collapsed FINALIZER_CONSUMES_INTENT to its true branch); flip the carrier population off to skip
     * and restore the pre-B2 finalize. No-op when [SkeletonPose.jointIntents] is empty.
     */
    private fun applyIntentCarriers(roots: List<SkeletonNode>, pose: SkeletonPose) {
        // (Phase B collapsed FINALIZER_CONSUMES_INTENT to its true branch — consumption is always on.)
        if (pose.jointIntents.isEmpty()) return
        // Contact poses are solver-settled: the ConstraintSolver has already honoured the declared
        // trunk/hip intents when it repositions the root to hold every contact. Re-applying the
        // carriers here would re-FK the whole tree and can displace the solver-settled contacts, so
        // the consumer is a no-op for contact poses (the carriers are still populated = live; the
        // full intent-only migration of contact instruments lands in B4).
        if (pose.hasContacts()) return
        for (a in pose.jointIntents) {
            val n = findJointNode(roots[0], a.joint) ?: continue
            n.localRotation.copyFrom(a.rotation)
        }
        // Re-propagate the whole tree so every declared articulation reaches its descendants, then
        // flatten into the output pose. The `spineIntent` carrier is already reflected via the
        // per-joint `jointIntents` entries recorded by buildSpineCurve, so no separate spine pass is
        // needed (and applying both would be idempotent anyway).
        for (root in roots) root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
        for (root in roots) root.flatten(outputPose)
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

    /**
     * Finalizes the 3D pose over a populated rotation-driven `pose.roots` hierarchy.
     */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        // M2 (RFC_ENGINE_PIPELINE §8.1): the ConstraintSolver pass that used to run here has been
        // moved up into [SkeletonPipeline.runStages], which is now the **sole** caller of both the
        // Solver and the Finalizer, in fixed order. The Finalizer therefore NEVER calls the Solver
        // (no re-entrancy), and a contact pose reaches `finalize` already solver-settled. The
        // rendering/test paths that call `finalizer.finalize(pose)` directly must route through the
        // pipeline ([SkeletonPipeline.produceFrame]) so the Solver is not skipped for contact poses.

        // Phase 3 (F1/F4): the finalizer is the *exclusive* writer of local transforms. This is
        // the single conversion entry point — any world↔local frame work (pole→world, the
        // `toLocalDirection` limb bakes already performed by the solver, extremity derivation)
        // is concentrated here.

        // Phase E (L1 compatibility bridge removal): `pose.roots` is now a required invariant.
        // Every production pose and the eval/test control paths populate `roots` via `build()` /
        // `fromHierarchy`, so an empty-roots pose reaching `finalize` is a programming error, not a
        // supported legacy path. Fail fast instead of silently taking the deleted bridge.
        check(pose.roots.isNotEmpty()) { "SkeletonPoseFinalizer.finalize requires a populated pose.roots (legacy bridge removed in Phase E)" }

        outputPose.copyFrom(pose)

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

        // B2 (RFC_BRANCH_B_IMPLEMENTATION §2) — consume the §1.1 `spineIntent` / `jointIntents`
        // carriers: re-derive the declared node rotations and re-propagate FK. This is idempotent
        // with the node write the authoring helpers also perform during build, so geometry is
        // byte-identical to the pre-B2 baseline (proven by FinalizerIntentConsumersTest).
        applyIntentCarriers(pose.roots, pose)

        // Issue F: derive the chest frame only when the author left it unauthored (identity);
        // an authored chest rotation (thoracic twist / side-bend / flex, possibly asymmetric)
        // is already propagated to the upper chain by FK and must not be overwritten.
        reconstructChestFrame(pose.roots, pose)

        // Phase 7 (Gap 7 / F8 / W17): resolve the gaze from the pose-declared `headTarget`
        // intent. This resolver is the sole writer of the neck/head local offsets; poses only
        // *declare* the gaze target (via `buildGaze`). A pose that declared no target (non-gaze
        // pose) is a no-op and keeps its authored head.
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
                articulationFor(pose, Extremity.FOOT_F, Joint.ANKLE_F, Joint.KNEE_F, relAnkle)
            )
        }
        if (pose.isExtremityAutomatic(Extremity.FOOT_B)) {
            adjustFootOrientation(
                outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B,
                articulationFor(pose, Extremity.FOOT_B, Joint.ANKLE_B, Joint.KNEE_B, relAnkle)
            )
        }
        if (pose.isExtremityAutomatic(Extremity.HAND_A)) {
            adjustHandOrientation(
                outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
                articulationFor(pose, Extremity.HAND_A, Joint.HAND_A, Joint.ELBOW_A, relWrist)
            )
        }
        if (pose.isExtremityAutomatic(Extremity.HAND_P)) {
            adjustHandOrientation(
                outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P,
                articulationFor(pose, Extremity.HAND_P, Joint.HAND_P, Joint.ELBOW_P, relWrist)
            )
        }

        // B5 — populate the §1.2 STATE stamps the validator consumes (no geometry inference
        // left in the validator). Computed from the final solved `outputPose`, so the stamps
        // reflect exactly the geometry the validator previously re-derived.
        applyValidationStamps(outputPose)

        return outputPose
    }

    // B5 — §1.2 stamp production (engine-owned). Reuses the identical femur-direction math
    // the validator's old `validateHipRom` used, so the rule's verdicts are byte-identical.
    private val hipRomStampScratch = HipRomStamp(0f, 0f, 0f, 0f)

    private fun applyValidationStamps(pose: SkeletonPose) {
        pose.hipRomStamps.clear()
        for (i in 0 until 2) {
            val hip = if (i == 0) Joint.HIP_F else Joint.HIP_B
            val knee = if (i == 0) Joint.KNEE_F else Joint.KNEE_B
            // Front hip at -Z, back hip at +Z; abduction is toward -Z for the front leg and
            // +Z for the back leg, so the same mirror sign keeps abduction positive for both.
            val abductionSign = if (i == 0) -1f else 1f
            val stamp = SkeletonMath.computeHipRomStamp(
                pose.getJointRotation(Joint.PELVIS),
                pose.getJoint(hip),
                pose.getJoint(knee),
                abductionSign,
                pose.getJointRotation(hip),
                hipRomStampScratch
            )
            pose.hipRomStamps[hip] = stamp
        }

        // B5 — bilateral symmetry stamp: the knee/elbow perpendicular-deviation magnitudes the
        // old `validateBilateralSymmetry` computed (2-D, parent->foot vs knee). Captured here
        // so the validator only reads the delta + opposite-bend flag.
        val kneeF = signedPerpDev(pose, Joint.HIP_F, Joint.ANKLE_F, Joint.KNEE_F)
        val kneeB = signedPerpDev(pose, Joint.HIP_B, Joint.ANKLE_B, Joint.KNEE_B)
        val elbowA = signedPerpDev(pose, Joint.SHOULDER_A, Joint.HAND_A, Joint.ELBOW_A)
        val elbowP = signedPerpDev(pose, Joint.SHOULDER_P, Joint.HAND_P, Joint.ELBOW_P)
        var delta = 0f
        var opposite = false
        if (kotlin.math.abs(kneeF) > 0.1f && kotlin.math.abs(kneeB) > 0.1f) {
            if (kneeF * kneeB < 0f) opposite = true
            delta = kotlin.math.max(delta, kotlin.math.abs(kotlin.math.abs(kneeF) - kotlin.math.abs(kneeB)))
        }
        if (kotlin.math.abs(elbowA) > 0.1f && kotlin.math.abs(elbowP) > 0.1f) {
            if (elbowA * elbowP < 0f) opposite = true
            delta = kotlin.math.max(delta, kotlin.math.abs(kotlin.math.abs(elbowA) - kotlin.math.abs(elbowP)))
        }
        pose.bilateralSymmetryDelta = delta
        pose.bilateralOppositeBend = opposite
    }

    /** 2-D signed perpendicular deviation of [mid] from the [a]->[b] line (X/Y plane). */
    private fun signedPerpDev(pose: SkeletonPose, a: Joint, b: Joint, mid: Joint): Float {
        val pa = pose.getJoint(a); val pb = pose.getJoint(b); val pm = pose.getJoint(mid)
        val vx = pb.x - pa.x; val vy = pb.y - pa.y
        val lenSq = vx * vx + vy * vy
        if (lenSq < 1e-4f) return 0f
        val cross = vx * (pm.y - pa.y) - vy * (pm.x - pa.x)
        return cross / kotlin.math.sqrt(lenSq)
    }


    /**
     * Branch C — resolves the wrist/ankle articulation rotation for [extremity] to feed the W1
     * geometry derivation. The pose-authored value is the single source of truth: when the pose
     * populated [SkeletonPose.extremityArticulations] for this extremity the carrier value is
     * returned verbatim (it already carries the rotation *relative to the parent segment*, so no
     * ancestor-chain removal is needed). When the carrier is empty (a pose that still authors the
     * node directly, or a neutral limb) the value is read from the node's **local** rotation in the
     * authored hierarchy — the exact rotation the legacy helpers wrote via `localRotation.set` /
     * `buildWristArticulation` / `buildAnkleArticulation`. This is the rotation *relative to the
     * joint's true parent segment* and is therefore identical to the carrier for every migrated and
     * legacy pose, keeping the pre-Branch-C path byte-identical.
     *
     * NB: the previous implementation derived the fallback via the world-relative
     * `inverse(parentWorld) ∘ nodeWorld`. That collapses to the identity rotation for a *straight*
     * limb (where the wrist/ankle world rotation equals its parent's, so the ancestor chain cancels
     * and the authored local articulation is silently dropped). Reading the node's local rotation
     * instead recovers the authored articulation even when the limb is straight, which is the
     * correct, author-intent-preserving behavior — and is what the carrier already carries.
     */
    private fun articulationFor(
        pose: SkeletonPose,
        extremity: Extremity,
        nodeId: Joint,
        parentId: Joint,
        out: JointRotation
    ): JointRotation {
        val carried = pose.extremityArticulations[extremity]
        if (carried != null) {
            out.copyFrom(carried)
            return out
        }
        // Mixed-mode fallback: the authored local rotation of the wrist/ankle node, which is already
        // expressed relative to the joint's parent segment (forearm / shank). `pose.roots` is always
        // populated at `finalize` (Phase E invariant), so the node is always resolvable.
        val node = findJointNode(pose.roots[0], nodeId)
        if (node != null) {
            out.copyFrom(node.localRotation)
            return out
        }
        return relativeRotation(pose.getJointRotation(nodeId), pose.getJointRotation(parentId), out)
    }

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

        // R1 (foot extremity derivation): a *neutral* foot — one with no authored ankle
        // articulation — must not point below the floor. The shank-perpendicular forward
        // direction tilts downward whenever the shank leans forward (standing, plank, mid-swing),
        // which drives the toe under the ground even for poses that declare no support contact
        // (Burpee frame 0, KettlebellSwing mid-swing). This was the "near-horizontal-shank foot"
        // sharp edge documented in ENGINE_AUTOMATIC_ORIENTATION_AUDIT §"residual engine limits".
        //
        // Clamp only the NEUTRAL direction's *downward* pitch to horizontal (a flat foot). Genuine
        // plantar flexion / pointed toe is authored as the ankle articulation ([ankleRotation],
        // applied by computeHeelToe AFTER this), so this never overrides intent — it only removes
        // the accidental downward tilt that pure shank geometry leaks into an un-articulated foot.
        // Upward pitch (dorsiflexion hint) is left untouched. Magnitude is preserved (unit).
        if (tempFootDir.y < 0f) {
            val horiz = kotlin.math.sqrt(tempFootDir.x * tempFootDir.x + tempFootDir.z * tempFootDir.z)
            if (horiz > 1e-4f) {
                tempFootDir.set(tempFootDir.x / horiz, 0f, tempFootDir.z / horiz)
            } else {
                // Foot pointed straight down with no horizontal heading: lay it flat along +X.
                tempFootDir.set(1f, 0f, 0f)
            }
        }


        // contact, the foot's long axis must lie *in* the support plane, not poke through it.
        // A steep shank (deep squat, wide split, wall-seat) makes the natural (shank-perpendicular)
        // forward direction tilt downward, which drives the toe/heel below the ground and fails the
        // no-penetration check. Projecting the direction onto the contact plane keeps the planted
        // foot flat on its support (generic support-plane reasoning — no per-pose special casing).
        // Free-hanging feet (no contact) are untouched, so non-support poses are unchanged.
        val supportNormal = supportPlaneNormalForFoot(pose, ankleId)
        if (supportNormal != null) {
            val nd = supportNormal.dot(tempFootDir)
            tempFootDir.set(
                tempFootDir.x - supportNormal.x * nd,
                tempFootDir.y - supportNormal.y * nd,
                tempFootDir.z - supportNormal.z * nd
            )
            // A purely normal shank direction (foot perpendicular to the support) has no in-plane
            // component to project onto; fall back to the (already world-flat) +X heading so the
            // foot still lies in the plane instead of collapsing to a zero vector.
            if (tempFootDir.mag() < 1e-3f) {
                tempFootDir.set(1f, 0f, 0f)
                val nd2 = supportNormal.dot(tempFootDir)
                tempFootDir.set(
                    tempFootDir.x - supportNormal.x * nd2,
                    tempFootDir.y - supportNormal.y * nd2,
                    tempFootDir.z - supportNormal.z * nd2
                )
            }
            tempFootDir.normalize()
        }

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

    /**
     * Returns the support-plane normal for a foot whose [ankleId] is registered as a fixed
     * support contact, or `null` when the ankle is not a contact (a free-hanging foot). The
     * normal comes straight from the contact the pose already declared, so the foot inherits
     * exactly the support the pose asked the solver to honor — ground, wall, prop or bar — and
     * nothing is invented here. Allocation-free: reuses [tempFootNormal] scratch.
     */
    private fun supportPlaneNormalForFoot(pose: SkeletonPose, ankleId: Joint): Vector3? {
        for (spec in pose.contacts) {
            if (spec.endJoint == ankleId && spec.contact != null) {
                val n = spec.contact.normal
                val nMag = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
                if (nMag < 1e-4f) return null
                return tempFootNormal.set(n.x / nMag, n.y / nMag, n.z / nMag)
            }
        }
        return null
    }
}
