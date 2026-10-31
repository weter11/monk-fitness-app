package com.monkfitness.app.animation

import com.monkfitness.app.BuildConfig
import kotlin.math.*

/**
 * Completes the 3D pose before projection: adds the heel/toe and hand segments that PoseBuilder does
 * not build, and derives all world positions and rotations by FK traversal.
 *
 * It operates exclusively over a populated `pose.roots` hierarchy; empty roots is a programming
 * error caught by a `check` at the top of [finalize].
 */
class SkeletonPoseFinalizer(
    private val definition: SkeletonDefinition
) {
    private val outputPose = SkeletonPose()
    private val tempDir = Vector3()

    // Publication marker (DEBUG-only) for the immutability onset: runtime state on THIS finalizer
    // instance, never carrier content. Set at the tail of [publish], cleared only by the
    // documented re-arm evidence (new pipeline window / new authoring cycle / different carrier).
    // Release builds never read it (the assignment is compiled out).
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
     * Fallback chest-frame reconstruction, used only when the author left the chest rotation
     * unauthored (identity).
     *
     * FK alone cannot be trusted for a non-upright trunk (e.g. a push-up plank oriented by the
     * pelvis/legs), so the chest is re-derived from the spine (`pelvis -> chest`, chest-local +Y)
     * and the shoulder line (`shoulderA -> shoulderP`, chest-local -Z), `colX = lean × colZ`. For a
     * symmetric thorax this equals the FK-derived frame, so a neutral trunk is unchanged; a
     * degenerate spine or shoulder line is skipped.
     *
     * An *authored* chest rotation is never overwritten: FK already propagates the author's
     * thoracic twist / side-bend / flex to the shoulders, arms, neck and head, and deriving from a
     * symmetric shoulder line would discard that intent.
     *
     * With contacts the reconstruction must be a no-move operation: solver-settled contact
     * end-effectors are snapshotted first and asserted unchanged, otherwise the chest frame is
     * rolled back to the solver-settled value and `rootTranslationDelta` is flagged.
     *
     * Allocation-free: reuses the shared column scratch and re-runs FK for the chest subtree only.
     */
    private fun reconstructChestFrame(roots: List<SkeletonNode>, pose: SkeletonPose) {
        if (roots.isEmpty()) return
        val pelvis = findJointNode(roots[0], Joint.PELVIS) ?: return
        val chest = findJointNode(roots[0], Joint.CHEST) ?: return
        val shoulderA = findJointNode(roots[0], Joint.SHOULDER_A) ?: return
        val shoulderP = findJointNode(roots[0], Joint.SHOULDER_P) ?: return
        // The chest's parent is the segment the reconstructed absolute frame is expressed
        // relative to: the LUMBAR for the two-segment spine, the PELVIS for an inline
        // single-segment hierarchy. Composing against the actual parent keeps an authored
        // lower-spine rotation instead of discarding it, and is identical to a pelvis-relative
        // reconstruction when the lumbar is a pass-through (single-bend poses unchanged).
        val chestParent = chest.parent ?: return

        // Never overwrite an authored chest rotation: it is the single source of truth and FK
        // already propagated it to the whole upper chain.
        if (chest.localRotation.angle > 1e-4f || chest.localRotation.angle < -1e-4f) {
            return
        }

        // Snapshot solver-settled contact end-effectors BEFORE mutating the chest frame, so the
        // reconstruction can be asserted read-only on them. Only meaningful with registered contacts.
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
        // RIGHT-HANDED orthonormal chest frame: colY = lean (spine/up), colZ = -(shoulderA -
        // shoulderP) (chest-forward, toward the passive shoulder), colX = lean x colZ (lateral).
        // Matches the FK-derived frame for the standard hierarchy, so a neutral trunk is unchanged.
        // NOTE: use the two-argument `cross(dst)` overload so the result lands in the scratch
        // buffer; the single-argument overload allocates a new vector and leaves `tempColX`
        // untouched, producing a degenerate matrix (colX == colY) and a wrong chest rotation.
        shVec.multiply(-1f)
        if (lean.cross(shVec, tempColX).mag() < 1e-4f) return
        tempColX.normalize()

        SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, reconRot)

        // chest.localRotation = parentWorldRotation^-1 * reconRot (the parent's world rotation,
        // not the chest's); a rotation matrix's inverse is its transpose.
        SkeletonMath.rotationToMatrix(chestParent.worldRotation, parentMatX, parentMatY, parentMatZ)
        SkeletonMath.rotationToMatrix(reconRot, worldMatX, worldMatY, worldMatZ)
        SkeletonMath.transposeMultiply(
            parentMatX, parentMatY, parentMatZ,
            worldMatX, worldMatY, worldMatZ,
            localMatX, localMatY, localMatZ
        )
        SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, chest.localRotation)

        // Re-run FK for the chest subtree in the reconstructed frame, anchored at the chest's
        // parent so the lower-spine segment stays the driver (identical world positions when the
        // lumbar is a pass-through).
        chest.updateWorldTransforms(chestParent.worldPosition, chestParent.worldRotation)
        chest.flatten(outputPose)

        // Assert the reconstruction moved no solver-settled contact; otherwise roll the chest frame
        // back so contacts stay exactly where the solver pinned them, and flag the residual.
        if (guardActive) enforceContactNoMove(chest, chestParent, pose)
    }

    /**
     * Resolves the gaze from the pose-declared `headTarget`; the single writer of the neck/head
     * local offsets. No target ⇒ no-op.
     *
     * The direction runs from the neck's current world position toward `headTarget.world`, biased
     * upright by `upBias`, written at the authored bone lengths (`neckLength`, `18f` for the head).
     * Because the pose records the target as `neckWorldPos + gazeDir * 100`, this reproduces the
     * identical direction the pose authored.
     *
     * Only local positions are rewritten, never the world tree upstream of them.
     */
    private fun resolveHeadTarget(pose: SkeletonPose) {
        val target = pose.headTarget ?: return

        val neck = findJointNode(pose.roots[0], Joint.NECK_END) ?: return
        val head = findJointNode(pose.roots[0], Joint.HEAD_POS) ?: return

        // Direction from the (FK-current) neck world position toward the gaze target.
        tempV1.set(target.world).subtract(neck.worldPosition)
        if (tempV1.mag() < 1e-4f) tempV1.set(target.upBias) else tempV1.normalize()
        // Place neck/head along the gaze direction at their authored bone lengths.
        neck.localPosition.set(tempV1.x * definition.neckLength, tempV1.y * definition.neckLength, tempV1.z * definition.neckLength)
        head.localPosition.set(tempV1.x * 18f, tempV1.y * 18f, tempV1.z * 18f)

        // The offsets were written AFTER the initial FK flatten, so re-propagate the neck->head
        // subtree: otherwise HEAD_POS collapses onto the neck and fails the validator's BONE_LENGTH
        // rule on NECK_END->HEAD_POS. The chest world transform is already current.
        val neckParent = neck.parent ?: return
        neck.updateWorldTransforms(neckParent.worldPosition, neckParent.worldRotation)
        neck.flatten(outputPose)
    }

    /**
     * Consumes `spineIntent` / `jointIntents`: re-derives each declared node rotation from the
     * carrier and re-propagates FK.
     *
     * The authoring helpers also write the node during `build()` (so build-time logic reading a
     * node's world transform — e.g. arm IK under a rotating chest — keeps working), so this
     * re-application is idempotent. No-op when [SkeletonPose.jointIntents] is empty.
     */
    private fun applyIntentCarriers(roots: List<SkeletonNode>, pose: SkeletonPose) {
        if (pose.jointIntents.isEmpty()) return
        // Contact poses are solver-settled: re-applying the carriers would re-FK the whole tree and
        // can displace the contacts the solver just pinned, so this is a no-op for them.
        if (pose.hasContacts()) return
        for (a in pose.jointIntents) {
            val n = findJointNode(roots[0], a.joint) ?: continue
            n.localRotation.copyFrom(a.rotation)
        }
        // Re-propagate the whole tree so every declared articulation reaches its descendants, then
        // flatten. `spineIntent` is already reflected via the per-joint entries recorded by
        // buildSpineCurve, so no separate spine pass is needed.
        for (root in roots) root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
        for (root in roots) root.flatten(outputPose)
    }

    // Contact end-effector world-position snapshot, reused across the guard: keyed by end-joint
    // index, holding the world positions captured before reconstruction.
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
     * Verifies every snapshotted contact end-effector is unchanged after the chest-frame
     * reconstruction (within [EPS]). If any moved, the chest local rotation is rolled back to its
     * solver-settled value — leaving every contact exactly where the solver pinned it — and
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

        // The reconstruction displaced a solver-settled contact: roll the chest frame back. This
        // path only runs for an identity (unauthored) chest, so the settled local rotation IS
        // identity — restoring it re-flattens the subtree to the exact pose the solver settled.
        chest.localRotation.set(Vector3(0f, 1f, 0f), 0f)
        chest.updateWorldTransforms(chestParent.worldPosition, chestParent.worldRotation)
        chest.flatten(outputPose)

        // Flag the residual so the PELVIS_INTENT / contact rules can surface the unexpected move.
        pose.rootTranslationDelta = kotlin.math.max(pose.rootTranslationDelta, maxMove)
    }

    // Tolerance for the contact no-move assertion.
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

    /** Finalizes the pose over a populated rotation-driven `pose.roots` hierarchy. */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        // The ConstraintSolver runs in [SkeletonPipeline.runStages], the sole caller of both the
        // Solver and the Finalizer, in fixed order. The Finalizer therefore NEVER calls the Solver
        // (no re-entrancy) and a contact pose arrives already solver-settled — callers that invoke
        // finalize directly must route through the pipeline or the Solver is skipped for contacts.

        // The finalizer is the exclusive writer of local transforms: all world↔local frame work
        // (pole→world, extremity derivation) is concentrated here.

        // `pose.roots` is a required invariant: every production pose and eval/test control path
        // populates it via `build()` / `fromHierarchy`, so empty roots here is a programming error.
        // Fail fast rather than silently degrading.
        check(pose.roots.isNotEmpty()) { "SkeletonPoseFinalizer.finalize requires a populated pose.roots (legacy bridge removed in Phase E)" }

        // Publish-order re-entry guard (DEBUG-only): a second publication of the same carrier
        // through this finalizer would silently republish the reused private `outputPose` buffer.
        // Two legitimate boundary events re-arm the marker: (a) the pipeline opening a new
        // execution window for this carrier ([beginPublishWindow]); (b) a fresh authoring pass —
        // the per-build reset bumps [SkeletonPose.buildCycleToken] and the pose buffer is reused
        // across builds, so a new build cycle is a new publish cycle. A different carrier object
        // always belongs to a different frame's publish unit.
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

        // Run FK over the hierarchy from the authored local rotations/offsets.
        if (!pose.isTransformsUpdated) {
            val size = pose.roots.size
            for (i in 0 until size) {
                pose.roots[i].updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
            }
            for (i in 0 until size) {
                pose.roots[i].flatten(outputPose)
            }
            pose.isTransformsUpdated = true
            // The published carrier must report the SAME kinematic state as the skip branch:
            // `outputPose` was just refreshed from the hierarchy, so its flat joint array IS
            // current. Without this the published state said "transforms not updated" for exactly
            // the frames whose realization ran in the engine stage — a different state than the
            // other configuration for the same frame. Bookkeeping only: no geometry is affected.
            outputPose.isTransformsUpdated = true
        }
        outputPose.roots = pose.roots

        // Consume the intent carriers: re-derive the declared node rotations and re-propagate FK.
        applyIntentCarriers(pose.roots, pose)

        // Derive the chest frame only when the author left it unauthored (identity); an authored
        // rotation is already propagated to the upper chain by FK and must not be overwritten.
        reconstructChestFrame(pose.roots, pose)

        // Resolve the gaze from the pose-declared `headTarget` — the sole writer of the neck/head
        // local offsets. A pose that declared no target keeps its authored head.
        resolveHeadTarget(pose)

        // Engine ownership of extremity orientation: heel/toe and palm/fingertip geometry is
        // derived for every extremity by default, skipped ONLY when the pose explicitly opted into
        // MANUAL_OVERRIDE (it authored the endpoints, e.g. a stylized toe or grip). Ownership is
        // never inferred from whether the HEEL/TOE/PALM/FINGERTIPS nodes exist — the factory always
        // creates them, which silently disabled derivation for every pose.
        //
        // The relative ankle/wrist articulation is passed in so inherited torso/limb tilt is
        // removed; identity articulation lays the foot/hand along the limb, equalling the FK frame.
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

        // Explicit publish tail: flatten-completion check → validation stamp writes → publication
        // marker, in that fixed order. The tail is the ONLY return path of this function.
        return publish(outputPose)
    }

    /**
     * The single explicit publish tail of [finalize], in fixed internal order:
     * `flatten-completion check → applyValidationStamps → published = true`.
     *
     * Publication completes only after BOTH write sets — every published transform and every
     * attributable validation stamp — are finished. Nothing after the marker may perform a
     * published-state write (enforced debug-side at [applyValidationStamps] and [finalize]).
     */
    private fun publish(outputPose: SkeletonPose): SkeletonPose {
        // Write set 1 completion: every published transform is in the carrier before the stamp
        // phase begins (debug-only verification).
        if (BuildConfig.DEBUG) assertFinalFlattenComplete(outputPose)

        // Populate the state stamps the validator consumes, computed from the final solved
        // `outputPose` so no geometry inference is left in the validator.
        applyValidationStamps(outputPose)

        // Immutability onset: the publication marker, set ONLY after the last stamp write. Debug-only.
        if (BuildConfig.DEBUG) {
            published = true
        }
        return outputPose
    }

    /**
     * Re-arm evidence for the [finalize] publish-order guard: the pipeline opens a NEW execution
     * window over the carrier it is about to run, so the frame is a new publish unit and the marker
     * this instance holds from the previous frame is cleared. Called ONLY under `BuildConfig.DEBUG`.
     */
    internal fun beginPublishWindow() {
        published = false
    }

    /**
     * Final flatten-completion check (debug-only, before the stamp phase): every published
     * transform must already bit-match the flattened hierarchy.
     *
     * The extremity-derivation endpoints ([EXTREMITY_DERIVED_JOINTS]) are the sole authorized
     * carrier-only writes at publish time — `adjustFootOrientation` / `adjustHandOrientation`
     * compute them into the carrier and no node carries them — so they are excluded.
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
        /** Centralized prefix of every publish-order enforcement failure. */
        internal const val PUBLISH_ORDER_VIOLATION = "Publish-order violation:"

        /**
         * Joints whose published value is derived directly into the carrier by the extremity
         * derivation (no node-side world position corresponds). Excluded from the flatten-
         * completion check — probe-verified as the exact mismatch set on every pose family.
         */
        private val EXTREMITY_DERIVED_JOINTS = setOf(
            Joint.HEEL_F, Joint.TOE_F, Joint.HEEL_B, Joint.TOE_B,
            Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
            Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P
        )
    }

    // Stamp production (engine-owned), reusing the identical femur-direction math the validator
    // used, so the rule's verdicts are byte-identical.
    private val hipRomStampScratch = HipRomStamp(0f, 0f, 0f, 0f)

    private fun applyValidationStamps(pose: SkeletonPose) {
        // The stamp phase is part of the publish tail: it may run ONLY before the publication
        // marker. A write here after `published=true` (a late re-entry on an already-published
        // instance) mutates published state past immutability onset and throws in DEBUG.
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

        // Bilateral symmetry stamp: the knee/elbow perpendicular-deviation magnitudes (2-D,
        // parent->foot vs knee), captured here so the validator only reads the delta + flag.
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
     * Resolves the wrist/ankle articulation rotation for [extremity], feeding the geometry
     * derivation. The pose-authored carrier value is the single source of truth — it is already
     * relative to the parent segment, so no ancestor-chain removal is needed. When the carrier is
     * empty (a pose that still authors the node directly, or a neutral limb) the node's **local**
     * rotation is read instead: the exact rotation the legacy helpers wrote, likewise relative to
     * the true parent segment, so both paths agree.
     *
     * NB: deriving the fallback as the world-relative `inverse(parentWorld) ∘ nodeWorld` collapses
     * to identity for a *straight* limb (the wrist/ankle world rotation equals its parent's, so the
     * chain cancels and the authored articulation is silently dropped).
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
        // Mixed-mode fallback: the node's authored local rotation, already relative to the parent
        // segment (forearm / shank). `pose.roots` is always populated at `finalize`.
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

        // A hand resting on a support plane must lie *in* it, not slope into it: the neutral hand
        // direction is the forearm direction, which points down-and-forward to a planted hand, so
        // the palm/knuckles/fingertips would dig through the floor. The forearm direction is
        // therefore projected onto the plane its declared support contact rests on (ground by
        // default, a box/wall if declared) — driven entirely by `pose.environment` +
        // `metadata.support.contacts`, so the same logic keeps palms flat in every pose and a hand
        // with no declared support (hanging/pull-up grips on a bar) is untouched. Genuine wrist
        // articulation is applied AFTER this via [wristRotation], so intent is never overridden.
        val handExt = if (handId == Joint.HAND_A) Extremity.HAND_A else Extremity.HAND_P
        // Skip flattening when the pose ALREADY authored a wrist articulation for this hand — it
        // would fight that intent — and when the hand is not actually PLANTED: a supporting hand
        // rests BELOW its elbow, while a gripping hand on an overhead bar sits ABOVE it and must not
        // be driven down. This geometric test needs no magic distance threshold.
        // The hand's declared support is resolved over its declaration family (HAND and FOREARM,
        // same side): a forearm plank declares `*_FOREARM` and the hand at its end is the extremity
        // this derivation orients. See [declaredHandSupportPoint].
        val declaredHandSupport = declaredHandSupportPoint(pose, handId)
        val planted = pose.getJoint(handId).y <= pose.getJoint(elbowId).y + 1.0f
        val poseOwnsWrist = pose.extremityArticulations.containsKey(handExt)
        val handSupportNormal = if (declaredHandSupport != null && !poseOwnsWrist && planted) {
            supportPlaneNormalFor(pose, declaredHandSupport)
        } else null

        // Heading (exercise intent): a declared root-relative forward direction replaces the
        // forearm-derived direction entirely — the hand faces the declared heading.
        val declaredHeading = pose.getHeading(handExt)
        if (declaredHeading != null && handSupportNormal != null) {
            // Transform the root-relative heading to world space via the pelvis rotation.
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
            // Fallback when no heading is declared: project the forearm direction onto the plane.
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

        // Compose the authored wrist orientation with the forearm direction so grips (pronation /
        // supination / wrist flexion) reach the completed hand. [wristRotation] is relative to the
        // forearm frame, not world, so applying it to the already-world direction does not
        // double-count the trunk/parent frame. Identity leaves the result unchanged.
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

        // A *neutral* foot — one with no authored ankle articulation — must not point below the
        // floor: the shank-perpendicular forward direction tilts downward whenever the shank leans
        // forward (standing, plank, mid-swing), driving the toe under the ground even for poses
        // that declare no support contact.
        //
        // Clamp only the NEUTRAL direction's *downward* pitch to horizontal. Genuine plantar
        // flexion / pointed toe is authored as the ankle articulation applied by computeHeelToe
        // AFTER this, so this never overrides intent — it only removes the accidental tilt that
        // pure shank geometry leaks into an un-articulated foot. Upward pitch (dorsiflexion hint)
        // is left untouched. Magnitude is preserved (unit).
        if (tempFootDir.y < 0f) {
            val horiz = kotlin.math.sqrt(tempFootDir.x * tempFootDir.x + tempFootDir.z * tempFootDir.z)
            if (horiz > 1e-4f) {
                tempFootDir.set(tempFootDir.x / horiz, 0f, tempFootDir.z / horiz)
            } else {
                // Foot pointed straight down with no horizontal heading: lay it flat along +X.
                tempFootDir.set(1f, 0f, 0f)
            }
        }


        // A planted foot's long axis must lie *in* the support plane, not poke through it: a steep
        // shank (deep squat, wide split, wall seat) tilts the shank-perpendicular direction down and
        // drives the toe/heel below the ground, failing the no-penetration check. Projecting onto
        // the contact plane keeps it flat on whatever surface its declared support rests on (ground
        // or box/step top) — derived from pose.environment, no per-pose special casing.
        // Free-hanging feet (no contact) are untouched.
        // The support is resolved over the declaration family (FOOT and TOES, same side) because
        // the plank/push-up families declare their planted foot as `*_TOES`. See
        // [declaredFootSupportPoint].
        val declaredFootSupport = declaredFootSupportPoint(pose, ankleId)
        val footExt = if (ankleId == Joint.ANKLE_F) Extremity.FOOT_F else Extremity.FOOT_B
        val supportNormal = if (declaredFootSupport != null) supportPlaneNormalFor(pose, declaredFootSupport) else null

        // Heading (exercise intent): a declared root-relative forward direction is used instead
        // of the toe hint.
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
            // Fallback when no heading is declared: project the toe-hint direction onto the plane.
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

        // computeHeelToe composes the authored ankle orientation with this neutral
        // (shank-perpendicular) direction and keeps the pitch clamp as a bound on the result.
        // [ankleRotation] is relative to the shank frame, not world, so applying it to the
        // already-world direction does not double-count the trunk/parent frame. Identity leaves
        // the neutral direction unchanged, so flat-foot rendering is preserved.
        val foot = definition.foot
        foot.computeHeelToe(ankle, tempFootDir, ankleRotation, pose.getJoint(heelId), pose.getJoint(toeId))
    }

    /**
     * Resolves the support point the pose DECLARED for the foot whose ankle is [ankleId], over that
     * ankle's canonical declaration family: whole-foot (`*_FOOT`) then toe-end (`*_TOES`) of THE
     * SAME physical foot. The family comes from the one canonical `SupportPoint ↔ Joint` map
     * ([SupportMath.supportPointsFor], the exact inverse of [SupportMath.jointsFor]), so this
     * resolver owns no side convention and `ANKLE_F` can only ever resolve to `LEFT_*`. The
     * resolved value is handed verbatim to [supportPlaneNormalFor].
     *
     * Returns `null` when no foot support was declared (a free-hanging foot), so non-declaring
     * poses keep their previous geometry. Allocation-free.
     */
    private fun declaredFootSupportPoint(pose: SkeletonPose, ankleId: Joint): SupportPoint? {
        val family = SupportMath.supportPointsFor(ankleId)
        for (i in family.indices) {
            if (pose.isSupported(family[i])) return family[i]
        }
        return null
    }

    /**
     * Hand-side twin of [declaredFootSupportPoint]: `*_HAND` (a pressing/gripping hand) before
     * `*_FOREARM` (a forearm plank, where the planted forearm's declaration governs the hand at its
     * end), from the same canonical source, so `HAND_A`/`HAND_P` never resolve to the opposite arm.
     *
     * Returns `null` when neither is declared, so an unsupported hand is untouched. Allocation-free.
     */
    private fun declaredHandSupportPoint(pose: SkeletonPose, handId: Joint): SupportPoint? {
        val family = SupportMath.supportPointsFor(handId)
        for (i in family.indices) {
            if (pose.isSupported(family[i])) return family[i]
        }
        return null
    }

    // Environment-driven support-plane derivation. The pose declares WHICH extremities rest on WHAT
    // via `metadata.environment` (a ground plane plus optional box/step/wall props) and
    // `metadata.support.contacts` (which support points); from those two facts the engine derives
    // the surface normal each supported extremity rests on — palms flat on the floor, feet planted
    // on the floor or a box, a wall-slide hand flat on a wall. Same logic for every pose: none
    // re-declares "the floor is at y=0", and a pose with no environment is byte-identical.

    private fun supportPlaneNormalFor(pose: SkeletonPose, point: SupportPoint): Vector3? {
        val env = pose.environment
        // The contact surface joints come from the ONE canonical `SupportPoint ↔ Joint` map (the
        // same map the declaration gate resolves the side from), so a plane can never be derived
        // from the opposite side's joints.
        val joints = SupportMath.jointsFor(point).map { pose.getJoint(it) }
        if (joints.isEmpty()) return null
        val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
        val cy = joints.sumOf { it.y.toDouble() }.toFloat() / joints.size
        val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
        // Default support = the ground plane (normal +Y); a prop overrides it when the contact lies
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
