package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig
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

    // P8 (§6 Phase 4 / §3.3) — publication marker (DEBUG-only enforcement state for §3.3's
    // immutability onset; the single-shot semantics are a plan §P8 IMPLEMENTATION DECISION, not
    // new RFC rules, and the marker is runtime state on THIS finalizer instance — it never
    // enters the published carrier contents, which must carry exactly Published Pose State per
    // §3.3/§4.3). Set at the tail of [publish]; cleared only by the documented re-arm evidences
    // (new pipeline window / new authoring cycle / different carrier). Release builds never
    // read or write these fields' enforcement role ([published] stays false — no runtime
    // verification path is added to release; the marker assignment itself is compiled out).
    private var published = false
    private var publishingPose: SkeletonPose? = null
    private var publishingBuildToken = 0L
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
     * This resolver is the **single writer** of the neck/head local offsets. The legacy
     * direction-based `buildHead` fallback that previously ran in `buildGaze` was removed once
     * this path was proven byte-identical (`HeadTargetBaselineTest`, maxDeviation ~6e-5).
     *
     * If the pose declared no `headTarget` (a non-gaze pose), this is a no-op. Otherwise the gaze
     * direction is derived from the neck's current world position toward `headTarget.world`, biased
     * upright by `headTarget.upBias`, and written at the authored bone lengths
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
        // Single source of truth for head orientation: place neck/head along the gaze direction
        // at their authored bone lengths, matching the historical buildHead math now inlined here.
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

        // P8 (§6 Phase 4 / §3.3) — publish-order re-entry guard (DEBUG-only). The single-shot
        // semantics are an IMPLEMENTATION DECISION of plan §P8, NOT §6 Phase 4's literal text:
        // §6 fixes the internal write order and §3.3 the immutability onset; this guard is the
        // enforcement mechanism FOR §3.3 — a second publication of the same carrier through this
        // finalizer would silently republish the reused private `outputPose` buffer. Two
        // legitimate boundary events re-arm the marker (neither is a violation):
        //  (a) SkeletonPipeline opening a new execution window for this carrier
        //      ([beginPublishWindow]) — RFC §6 Phase 0.5 / §4.5 R11 ownership transfer;
        //  (b) a fresh authoring pass over the carrier — the per-build intent-carrier reset
        //      bumps [SkeletonPose.buildCycleToken]; the jointsBuffer instance is reused across
        //      builds (BasePose §build-template), so a new build cycle is a new publish cycle;
        //  and a different carrier object always belongs to a different frame's publish unit.
        if (BuildConfig.DEBUG) {
            val reArmed = published &&
                (publishingPose !== pose || publishingBuildToken != pose.buildCycleToken)
            if (reArmed) published = false
            check(!published) {
                "$PUBLISH_ORDER_VIOLATION this SkeletonPoseFinalizer already published the " +
                    "carrier at hand and no re-arm evidence exists (same carrier, same authoring " +
                    "cycle, no new pipeline window) — re-finalizing would republish the reused " +
                    "outputPose buffer after RFC §3.3 immutability onset"
            }
            publishingPose = pose
            publishingBuildToken = pose.buildCycleToken
        }

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
            // P12 WP-I — the published carrier must report the SAME kinematic state as the
            // skip branch does (where `copyFrom` above carries the input's `true`): `outputPose`
            // was just refreshed from the hierarchy by the flatten above, so its flat joint array
            // IS current. Without this write the published Pose State said "transforms not
            // updated" for exactly the frames whose realization ran in the engine stage (the
            // stage consumes the build-window marker), i.e. the flag-ON configuration published a
            // different Kinematic State than flag-OFF for the same frame. Bookkeeping only: the
            // FK/flatten decision above is already made, no geometry is affected, and no
            // production reader consumes this field after publication.
            outputPose.isTransformsUpdated = true
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

        // P8 — explicit publish tail: final flatten-completion check → Validation Stamp
        // writes → publication marker (RFC §6 Phase 4 fixed internal order). The tail is the
        // ONLY return path of this function; no caller reaches the published buffer without it.
        return publish(outputPose)
    }

    /**
     * P8 (§6 Phase 4 / §3.3) — the single explicit publish tail of [finalize].
     *
     * Fixed internal order (RFC §6 Phase 4: publication completes ONLY after BOTH write sets —
     * every published transform and every attributable Validation Stamp write — are finished):
     *
     * ```
     * final flatten-completion check → applyValidationStamps → published = true → return
     * ```
     *
     * The marker is set ONLY after the final validation-stamp writes complete; nothing after it
     * performs a Published Pose State write (enforced debug-side at [applyValidationStamps] and
     * at the [finalize] entry). The marker is DEBUG-only enforcement state on this instance (a
     * plan §P8 IMPLEMENTATION DECISION for §3.3 enforcement — not a new RFC rule, not a state
     * category, and never carrier content).
     */
    private fun publish(outputPose: SkeletonPose): SkeletonPose {
        // §6 Phase 4 write set 1 completion: every published transform is in the carrier
        // before the stamp phase begins (debug-only verification).
        if (BuildConfig.DEBUG) assertFinalFlattenComplete(outputPose)

        // B5 — populate the §1.2 STATE stamps the validator consumes (no geometry inference
        // left in the validator). Computed from the final solved `outputPose`, so the stamps
        // reflect exactly the geometry the validator previously re-derived.
        applyValidationStamps(outputPose)

        // §3.3 immutability onset: the publication marker, set ONLY after the last
        // attributable stamp write above (§6 Phase 4 order). Debug-only.
        if (BuildConfig.DEBUG) {
            published = true
        }
        return outputPose
    }

    /**
     * P8 — re-arm evidence (a) for the [finalize] publish-order guard: SkeletonPipeline opens a
     * NEW execution window over the carrier it is about to run (RFC §6 Phase 0.5 → stages;
     * §4.5 R11 ownership transfer). The frame is a new publish unit, so the publication marker
     * THIS finalizer instance holds from the previous frame is cleared. Called ONLY under
     * `BuildConfig.DEBUG` (see [SkeletonPipeline.runStages]) — release builds never reach it,
     * so no runtime verification path is added there.
     */
    internal fun beginPublishWindow() {
        published = false
    }

    /**
     * P8 — final flatten-completion check (debug-only, called from [publish] before the
     * stamp phase): every published transform must already bit-match the flattened hierarchy.
     *
     * The extremity-derivation endpoints ([EXTREMITY_DERIVED_JOINTS]) are the sole authorized
     * carrier-only writes at publish time: `adjustFootOrientation` / `adjustHandOrientation`
     * compute them into the carrier (no upstream node carries the derived positions), so the
     * node-vs-carrier comparison excludes exactly those joints — the set was probe-verified
     * bit-exact on every other joint across all production pose families and progress sweeps.
     */
    private fun assertFinalFlattenComplete(pose: SkeletonPose) {
        for (root in pose.roots) checkFlattened(root, pose)
    }

    private fun checkFlattened(node: SkeletonNode, pose: SkeletonPose) {
        if (!EXTREMITY_DERIVED_JOINTS.contains(node.joint)) {
            val w = node.worldPosition
            val j = pose.getJoint(node.joint)
            check(w.x == j.x && w.y == j.y && w.z == j.z) {
                "$PUBLISH_ORDER_VIOLATION transform ${node.joint} was not flattened into the " +
                    "published carrier before the stamp phase (RFC §6 Phase 4: Flatten completes " +
                    "every published transform FIRST)"
            }
        }
        for (child in node.children) checkFlattened(child, pose)
    }

    companion object {
        /**
         * P8 — centralized prefix of every publish-order enforcement failure (entry re-entry,
         * post-marker stamp write, pre-stamp flatten gap). Follows the same centralization
         * idiom as the R2/R3/R5/R8 enforcement texts of this track; P8's guard is a plan §P8
         * IMPLEMENTATION DECISION enforcing §3.3, so the message names the ORDER violation
         * rather than an RFC rule number.
         */
        internal const val PUBLISH_ORDER_VIOLATION = "Publish-order violation:"

        /**
         * Joints whose published value is derived directly into the carrier by the Finalizer's
         * W1 extremity derivation (no node-side world position corresponds to the derived
         * value). Excluded from the final flatten-completion check — probe-verified as the
         * EXACT mismatch set on every production pose family.
         */
        private val EXTREMITY_DERIVED_JOINTS = setOf(
            Joint.HEEL_F, Joint.TOE_F, Joint.HEEL_B, Joint.TOE_B,
            Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
            Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P
        )
    }

    // B5 — §1.2 stamp production (engine-owned). Reuses the identical femur-direction math
    // the validator's old `validateHipRom` used, so the rule's verdicts are byte-identical.
    private val hipRomStampScratch = HipRomStamp(0f, 0f, 0f, 0f)

    private fun applyValidationStamps(pose: SkeletonPose) {
        // P8 (§3.3) — the stamp phase is part of the publish tail: it may run ONLY before the
        // publication marker. A write reaching here after `published=true` (a late re-entry on
        // an already-published finalizer instance) mutates Published Pose State past immutability
        // onset and throws in DEBUG. (During the normal single pass the marker is still unset.)
        if (BuildConfig.DEBUG) {
            check(!published) {
                "$PUBLISH_ORDER_VIOLATION Validation Stamp write attempted after the §3.3 " +
                    "immutability onset (published=true) — stamp writes belong to the publish " +
                    "tail and may never follow publication"
            }
        }
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

        // W1b (hand extremity derivation): a hand resting on a support plane (the floor for a
        // push-up, a bar, a box) must lie *in* that plane, not slope into it. The neutral hand
        // direction is the forearm direction, which points down-and-forward to a planted hand, so
        // the palm/knuckles/fingertips inherit that downward slope and dig through the floor. When
        // the pose declares a support plane for this hand, project the forearm direction onto the
        // plane so the completed hand lies flat on its support. Genuine wrist articulation is
        // applied AFTER this (via [wristRotation]), so intent is never overridden; a hand with no
        // declared support plane is untouched, preserving every non-support pose exactly.
        // W1b (environment-driven): flatten the hand onto the surface its declared support
        // contact rests on (ground by default; a box/wall if the pose declares one). Driven entirely
        // by `pose.environment` + `metadata.support.contacts` — no per-pose hardcoded plane, so the
        // same logic keeps palms flat in every pose that rests a hand on a surface. A hand that is
        // not declared as a support contact is untouched (e.g. hanging/pull-up grips on a bar).
        val handExt = if (handId == Joint.HAND_A) Extremity.HAND_A else Extremity.HAND_P
        // Only auto-flatten when the engine owns the hand. Skip when the pose has ALREADY authored a
        // wrist articulation for this hand (recorded in `extremityArticulations`, e.g. PikePushUp's
        // deliberate grip) — flattening would fight that intent. Also skip when the hand is not
        // actually PLANTED: a supporting hand rests BELOW its elbow (palm on the floor); a gripping
        // hand on an overhead bar sits ABOVE the elbow and must not be driven down to the ground.
        // This geometric test distinguishes "hand on the floor" from "hand on a bar" without a magic
        // distance threshold.
        // B-3 — the hand's declared support is resolved over its DECLARATION FAMILY (HAND and
        // FOREARM, same side): a pose that plants the forearm (a forearm plank) declares the
        // support as `*_FOREARM`, and the hand at the end of that planted forearm is the extremity
        // this derivation orients. See [declaredHandSupportPoint].
        val declaredHandSupport = declaredHandSupportPoint(pose, handId)
        val planted = pose.getJoint(handId).y <= pose.getJoint(elbowId).y + 1.0f
        val poseOwnsWrist = pose.extremityArticulations.containsKey(handExt)
        val handSupportNormal = if (declaredHandSupport != null && !poseOwnsWrist && planted) {
            supportPlaneNormalFor(pose, declaredHandSupport)
        } else null

        // Heading (exercise intent): if the exercise declared a root-relative forward
        // direction for this extremity, transform it to world space and project onto
        // the support plane. This replaces the forearm-derived direction entirely —
        // the hand faces the declared heading, not the current forearm angle.
        val declaredHeading = pose.getHeading(handExt)
        if (declaredHeading != null && handSupportNormal != null) {
            // Transform root-relative heading to world space via pelvis rotation.
            val pelvisRot = pose.getJointRotation(Joint.PELVIS)
            SkeletonMath.rotAround(declaredHeading, pelvisRot.axis, pelvisRot.angle, tempDir)
            tempDir.normalize()
            // Project onto the support plane so the hand lies flat on the surface.
            val nd = handSupportNormal.dot(tempDir)
            tempDir.set(
                tempDir.x - handSupportNormal.x * nd,
                tempDir.y - handSupportNormal.y * nd,
                tempDir.z - handSupportNormal.z * nd
            )
            if (tempDir.mag() < 1e-3f) {
                tempDir.set(1f, 0f, 0f)
                val nd2 = handSupportNormal.dot(tempDir)
                tempDir.set(
                    tempDir.x - handSupportNormal.x * nd2,
                    tempDir.y - handSupportNormal.y * nd2,
                    tempDir.z - handSupportNormal.z * nd2
                )
            }
            tempDir.normalize()
        } else if (handSupportNormal != null) {
            // Legacy fallback: project the forearm direction onto the support plane.
            // Used when no heading is declared — preserves byte-identical behavior
            // for all existing exercises.
            val nd = handSupportNormal.dot(tempDir)
            tempDir.set(
                tempDir.x - handSupportNormal.x * nd,
                tempDir.y - handSupportNormal.y * nd,
                tempDir.z - handSupportNormal.z * nd
            )
            // A forearm perpendicular to the support (hand straight down) has no in-plane
            // component; fall back to a world-forward heading laid into the plane so the hand
            // still lies flat instead of collapsing to a zero-length direction.
            if (tempDir.mag() < 1e-3f) {
                tempDir.set(1f, 0f, 0f)
                val nd2 = handSupportNormal.dot(tempDir)
                tempDir.set(
                    tempDir.x - handSupportNormal.x * nd2,
                    tempDir.y - handSupportNormal.y * nd2,
                    tempDir.z - handSupportNormal.z * nd2
                )
            }
            tempDir.normalize()
        }

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
        // W1b (environment-driven): flatten the foot onto the surface its declared support
        // contact rests on (ground, or a box/step top). Derived from pose.environment + declared
        // support — no per-pose special casing; the same code plants feet in every pose.
        // B-3 — the foot's declared support is resolved over its DECLARATION FAMILY (FOOT and TOES,
        // same side): the plank/push-up families declare their planted foot as `*_TOES`. See
        // [declaredFootSupportPoint].
        val declaredFootSupport = declaredFootSupportPoint(pose, ankleId)
        val footExt = if (ankleId == Joint.ANKLE_F) Extremity.FOOT_F else Extremity.FOOT_B
        val supportNormal = if (declaredFootSupport != null) supportPlaneNormalFor(pose, declaredFootSupport) else null

        // Heading (exercise intent): same pattern as hands. If the exercise declared a
        // root-relative forward direction for this foot, use it instead of the toe hint.
        val declaredFootHeading = pose.getHeading(footExt)
        if (declaredFootHeading != null && supportNormal != null) {
            val pelvisRot = pose.getJointRotation(Joint.PELVIS)
            SkeletonMath.rotAround(declaredFootHeading, pelvisRot.axis, pelvisRot.angle, tempFootDir)
            tempFootDir.normalize()
            val nd = supportNormal.dot(tempFootDir)
            tempFootDir.set(
                tempFootDir.x - supportNormal.x * nd,
                tempFootDir.y - supportNormal.y * nd,
                tempFootDir.z - supportNormal.z * nd
            )
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
        } else if (supportNormal != null) {
            // Legacy fallback: project the toe-hint-derived direction onto the support plane.
            val nd = supportNormal.dot(tempFootDir)
            tempFootDir.set(
                tempFootDir.x - supportNormal.x * nd,
                tempFootDir.y - supportNormal.y * nd,
                tempFootDir.z - supportNormal.z * nd
            )
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
    /** Maps an ankle joint to the front/back foot (whole-foot) support point. */
    private fun footSupportPointFor(ankleId: Joint): SupportPoint = when (ankleId) {
        Joint.ANKLE_F -> SupportPoint.RIGHT_FOOT
        Joint.ANKLE_B -> SupportPoint.LEFT_FOOT
        else -> SupportPoint.RIGHT_FOOT
    }

    /** Maps an ankle joint to the front/back TOES support point (the same side as [footSupportPointFor]). */
    private fun toesSupportPointFor(ankleId: Joint): SupportPoint = when (ankleId) {
        Joint.ANKLE_F -> SupportPoint.RIGHT_TOES
        Joint.ANKLE_B -> SupportPoint.LEFT_TOES
        else -> SupportPoint.RIGHT_TOES
    }

    /**
     * B-3 — resolves the support point the pose DECLARED for the foot whose ankle is [ankleId], over
     * that foot's declaration family: the contact may be declared as a `*_FOOT` (whole foot planted)
     * or as a `*_TOES` (toe end planted — how the whole plank/push-up family declares its feet).
     * The two families name the same physical support in the engine's own contact→joint map
     * ([contactJointsFor] maps both to the identical `{ankle, heel, toe}` triple), so the
     * declaration is honoured **verbatim**: the resolved value is the pose's own [SupportPoint] and
     * it is the value handed to [supportPlaneNormalFor]. Nothing is re-mapped, aliased or
     * re-interpreted, and no new contact→joint or contact→side mapping is introduced by this
     * resolver — it consults the two points the existing side map names (B-3 previously asked only
     * the `*_FOOT` one, so a `*_TOES` declaration silently produced `null` and the foot was never
     * planted).
     *
     * The SIDE resolution ([footSupportPointFor] / [toesSupportPointFor]) is exactly the one this
     * file already used: this resolver ADDS the TOES family to it and changes no side convention.
     * The `A/P/F/B ↔ left/right` convention and its contradictory maps are the separate, still-open
     * B-4 finding and are deliberately untouched here.
     *
     * Returns `null` when the pose declared no foot support for this ankle (a free-hanging foot), so
     * every non-declaring pose keeps its previous geometry. Allocation-free (no intermediate
     * Pair/List — the finalizer's per-frame paths allocate nothing).
     */
    private fun declaredFootSupportPoint(pose: SkeletonPose, ankleId: Joint): SupportPoint? {
        val foot = footSupportPointFor(ankleId)
        if (pose.isSupported(foot)) return foot
        val toes = toesSupportPointFor(ankleId)
        return if (pose.isSupported(toes)) toes else null
    }

    /**
     * B-3 — the hand-side twin of [declaredFootSupportPoint]: resolves the hand's declared support
     * over its declaration family — `*_HAND` (a pressing/gripping hand) or `*_FOREARM` (a
     * forearm-planted pose such as a forearm plank, where the planted forearm's declaration governs
     * the hand at its end) — on the side this file already associates with [handId]
     * (`A` → `LEFT_*`, `P` → `RIGHT_*`, the association the hand support gate has always used,
     * unchanged; the side convention itself is B-4). Returns `null` when neither family is declared,
     * so an unsupported hand is untouched. Allocation-free.
     */
    private fun declaredHandSupportPoint(pose: SkeletonPose, handId: Joint): SupportPoint? {
        val hand = if (handId == Joint.HAND_A) SupportPoint.LEFT_HAND else SupportPoint.RIGHT_HAND
        if (pose.isSupported(hand)) return hand
        val forearm = if (handId == Joint.HAND_A) SupportPoint.LEFT_FOREARM else SupportPoint.RIGHT_FOREARM
        return if (pose.isSupported(forearm)) forearm else null
    }

    // ---------------------------------------------------------------------------
    // Environment-driven support-plane derivation (W1b, replaces per-pose hardcoded planes).
    //
    // The pose declares WHICH extremities rest on WHAT via `metadata.environment` (a ground plane
    // plus optional box/step/wall props) and `metadata.support.contacts` (which support points).
    // From those two declarative facts the engine derives, for every supported extremity, the
    // surface normal it should rest on — so palms lie flat on the floor, feet plant on the floor
    // or a box, and a wall-slide hand lies flat on a wall. This is the SAME logic for every pose;
    // no pose re-declares "the floor is at y=0". A pose with no environment is byte-identical.
    // ---------------------------------------------------------------------------

    /** Maps a support point to the joints that physically touch the surface for that extremity. */
    private fun contactJointsFor(point: SupportPoint): List<Joint> = when (point) {
        SupportPoint.LEFT_FOOT, SupportPoint.LEFT_TOES -> listOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
        SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_TOES -> listOf(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
        SupportPoint.LEFT_KNEE -> listOf(Joint.KNEE_F)
        SupportPoint.RIGHT_KNEE -> listOf(Joint.KNEE_B)
        SupportPoint.LEFT_HAND -> listOf(Joint.HAND_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
        SupportPoint.RIGHT_HAND -> listOf(Joint.HAND_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
        SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM -> listOf(Joint.ELBOW_A, Joint.ELBOW_P)
        SupportPoint.HIPS, SupportPoint.BACK, SupportPoint.PELVIS -> listOf(Joint.HIP_F, Joint.HIP_B, Joint.PELVIS)
        SupportPoint.CUSTOM -> emptyList()
        SupportPoint.LEFT_ELBOW, SupportPoint.RIGHT_ELBOW -> emptyList()
    }

    /** For each supported extremity, the surface normal it should rest flat against. */
    private fun supportPlaneNormalFor(pose: SkeletonPose, point: SupportPoint): Vector3? {
        val env = pose.environment
        val joints = contactJointsFor(point).map { pose.getJoint(it) }
        if (joints.isEmpty()) return null
        val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
        val cy = joints.sumOf { it.y.toDouble() }.toFloat() / joints.size
        val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
        // Default support = the ground plane (normal +Y). A prop overrides it when the contact lies
        // within the prop's horizontal footprint (box/step/bench: on its top; wall: on its face).
        var bestNormal: Vector3? = groundNormal(tempFootNormal)
        var bestDist = kotlin.math.abs(cy - env.ground.level)
        for (prop in env.props) {
            val footprint = propFootprint(prop) ?: continue
            val within = cx in (footprint.cx - footprint.hw)..(footprint.cx + footprint.hw) &&
                cz in (footprint.cz - footprint.hd)..(footprint.cz + footprint.hd)
            if (!within) continue
            when (prop) {
                is BoxProp, is StepProp, is BenchProp -> {
                    val top = footprint.cy + footprint.hh
                    val d = kotlin.math.abs(cy - top)
                    if (d < bestDist) { bestDist = d; bestNormal = groundNormal(tempFootNormal) }
                }
                is WallProp -> {
                    val side = if (cx >= footprint.cx) 1f else -1f
                    bestNormal = tempFootNormal.set(-side, 0f, 0f)
                }
            }
        }
        return bestNormal
    }

    /** Uniform footprint (center + half-extents) for the box-like props that can support a contact. */
    private data class PropFootprint(val cx: Float, val cy: Float, val cz: Float, val hw: Float, val hh: Float, val hd: Float)
    private fun propFootprint(prop: EnvironmentProp): PropFootprint? = when (prop) {
        is BoxProp -> PropFootprint(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is StepProp -> PropFootprint(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is BenchProp -> PropFootprint(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is WallProp -> PropFootprint(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
    }

    private fun groundNormal(out: Vector3): Vector3 = out.set(0f, 1f, 0f)
}
