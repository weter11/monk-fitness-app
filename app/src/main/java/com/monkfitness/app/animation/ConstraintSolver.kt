package com.monkfitness.app.animation

import kotlin.math.*

/**
 * A fixed support contact registered by [BasePose.bakeIkLimb], capturing everything the solver
 * needs to re-solve that limb after the root has been repositioned.
 *
 * `endJoint` is the contact end-effector. The proximal chain is reconstructed from the fixed
 * skeleton topology via [chainForEnd]: `rootJoint` is the joint whose world position seeds the
 * solve, `middleJoint` the middle of the 2-bone chain, and `parentRotationJoint` the frame the
 * offsets are authored in. Single-bone contacts (knee/elbow/hip/head/toe/heel) have
 * `middleJoint == endJoint` and are re-baked as one rigid bone.
 */
class ContactSpec(
    val endJoint: Joint,
    val rootJoint: Joint,
    val parentRotationJoint: Joint,
    val middleJoint: Joint,
    val targetWorld: Vector3,
    val pole: Vector3,
    val length1: Float,
    val length2: Float,
    val constraint: IKConstraint,
    val straight: Boolean,
    val contact: ContactConstraint?
)

/**
 * Global contact-constraint / root-repositioning layer.
 *
 * The engine is otherwise purely local (per-limb 2-bone IK + FK), so it cannot satisfy "fixed
 * contacts + posture" at once and inconsistent authored geometry shows up as penetration, folded
 * limbs or floating contacts rather than as error redistributed upstream. This is a deterministic,
 * allocation-free relaxation that runs between IK and FK: it gathers the fixed [ContactSpec]s,
 * repositions the root so every contact is reached, then re-bakes each contact limb from the moved
 * root. A fixed small number of iterations keeps it bounded and smooth (no flicker between frames).
 *
 * A contact is a surface/anchor constraint honoured where it is, not a hard 3-D point the root is
 * dragged to at full extension: the root is translated only to restore reachability, and a
 * reachable contact is left in place with its limb re-baked to aim at it (forcing full extension
 * lifted the pelvis into a floating "V", e.g. Middle Split). A straight limb whose target falls
 * inside the proximal bone length is re-baked bent so it stays valid, and a residual the root alone
 * cannot satisfy is shared into a pelvis tilt composed with the authored rotation. Symmetric stances
 * have zero imbalance, so correct poses keep their exact authored orientation.
 *
 * It never invents contacts (only consumes the registered ones), never moves a non-contact limb's
 * authored shape (translating/tilting the root lets every other limb follow rigidly), and preserves
 * bone lengths by re-baking through the same analytical IK and local-frame bake the pose used.
 */
object ConstraintSolver {

    private const val MAX_ITERATIONS = 16
    private const val RELAX = 0.5f
    private const val EPS = 1e-3f
    private const val DEG2RAD = 3.1415927f / 180f
    // Default overhead-bar grip height (world Y) for the HANGING_UNDER_BAR seed when the pose
    // registers no bar contact; mirrors the validation/vertical-pull convention (bar at 500).
    private const val DEFAULT_BAR_Y = 500f

    // The solver is the sole mover of the root/pelvis transform: the root is seeded from the pose's
    // declared [PostureIntent] and contact conflicts are resolved by `contactPrecedence`, instead of
    // the pose hand-computing `pelvisY`/`pelvisX`.
    private const val POSTURE_SEED_RELAX = 0.5f
    // Inter-frame smoothing gain: how strongly each frame eases the solved root toward the previous
    // one. Small so the posture still settles but frames don't jitter when contacts are marginally
    // inconsistent. Zero = strict per-frame solve.
    private const val SMOOTH_GAIN = 0.25f

    // The solver keeps NO cross-frame memory of its own: inter-frame state is owned by
    // SkeletonPipeline's Frame History and supplied per solve via [solve]'s `previousRootWorld`, so
    // behaviour is a function of current-frame inputs plus supplied history, never of object
    // identity. Any persistent pose-keyed or static previous-root cache here re-creates that
    // violation; `arch/FrameHistoryOwnershipAuditTest` and `InterFrameSmoothingTest` pin it.

    // Persistent scratch — no hot-path allocation.
    private val zero = Vector3()
    private val identity = JointRotation()
    private val delta = Vector3()
    private val away = Vector3()
    private val dir = Vector3()
    private val rootWorld = Vector3()
    private val ikResult = SkeletonMath.IKResult()
    private val nodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }

    // Posture-DOF scratch: the solver is not translation-only. When contacts are reachable it leaves
    // the root where the contact pins it, and an asymmetric reach residual is shared into a pelvis
    // tilt composed with the authored rotation instead of one vertical translation. Symmetric
    // contacts have zero imbalance, so a correct pose is a strict no-op.
    private val tiltDelta = JointRotation()
    private val authoredPelvisRot = JointRotation()
    private val authoredPelvisPos = Vector3()
    private val rootDeltaRot = JointRotation()
    private val pelvisMatX = Vector3(); private val pelvisMatY = Vector3(); private val pelvisMatZ = Vector3()
    private val tiltMatX = Vector3(); private val tiltMatY = Vector3(); private val tiltMatZ = Vector3()
    private val outMatX = Vector3(); private val outMatY = Vector3(); private val outMatZ = Vector3()
    private val imbA = Vector3(); private val imbB = Vector3()
    private const val TILT_GAIN = 0.01f

    // Posture-pass CCD scratch — reused matrix/rotation buffers (no hot-path allocation). The posture
    // pass is a true solve: it distributes an unreachable/asymmetric residual across the limb's free
    // joint angles (hip->knee->ankle / shoulder->elbow->wrist), not into the pelvis alone.
    private var activeRoots: List<SkeletonNode> = emptyList()
    private val authoredRotBuf = Array(Joint.entries.size) { JointRotation() }
    private val ccdDelta = JointRotation()
    private val ccdA = Vector3(); private val ccdB = Vector3()
    private val ccdPX = Vector3(); private val ccdPY = Vector3(); private val ccdPZ = Vector3()
    private val ccdDX = Vector3(); private val ccdDY = Vector3(); private val ccdDZ = Vector3()
    private val ccdMX = Vector3(); private val ccdMY = Vector3(); private val ccdMZ = Vector3()
    private val ccdOpX = Vector3(); private val ccdOpY = Vector3(); private val ccdOpZ = Vector3()
    private val ccdOldX = Vector3(); private val ccdOldY = Vector3(); private val ccdOldZ = Vector3()
    private val ccdNewX = Vector3(); private val ccdNewY = Vector3(); private val ccdNewZ = Vector3()
    private const val POSTURE_MAX_ITERS = 12
    private const val POSTURE_EPS = 1e-3f
    private const val POSTURE_DAMP = 0.5f
    // How strongly each CCD step is pulled back toward the authored joint angle (shape
    // regularization). Small so reachable contacts are still honoured; non-zero so an
    // over-constrained residual lands in the closest authored posture, not an arbitrary one.
    private const val POSTURE_REG = 0.1f

    /**
     * Maps a contact end-effector to its proximal chain in the fixed skeleton topology. Returns null
     * for joints that are not a supported contact limb (registration is then skipped at bake time).
     *
     * Every contact is a 2-bone chain `(rootJoint -> middleJoint -> endJoint)`. Planted body parts
     * that are the end of a single bone (knee, elbow/forearm, hip, head, toe/heel) are modelled as a
     * degenerate chain with `middleJoint == endJoint`, re-baked as one rigid segment. The
     * `parentRotationJoint` is the frame that segment's offset is authored in — the bone's actual
     * parent, which for an identity-rotation link equals the pelvis/scapula frame the legs and arms
     * entries have always used.
     */
    fun chainForEnd(end: Joint): ContactChain? = when (end) {
        Joint.ANKLE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.ANKLE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)
        // Kneeling: the knee is the end of the thigh (1-bone). middle == end.
        Joint.KNEE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.KNEE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)

        Joint.HAND_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.HAND_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)
        // Forearm / elbow plank: the elbow is the end of the forearm (1-bone). middle == end.
        Joint.ELBOW_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.ELBOW_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)

        // Hips: seated / kneeling. The hip is the end of the (identity) pelvis->hip offset
        // (1-bone), framed in the pelvis. middle == end.
        Joint.HIP_F -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_F)
        Joint.HIP_B -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_B)

        // Head: head-stand. The head is the end of the (identity) chest->neck->head chain,
        // modelled as a 1-bone neck segment framed in the chest. middle == end.
        Joint.HEAD_POS -> ContactChain(Joint.CHEST, Joint.CHEST, Joint.HEAD_POS)

        // Toe / heel stands: the end of the foot (1-bone, ANKLE -> TOE/HEEL), framed in the ankle.
        // middle == end.
        Joint.TOE_F -> ContactChain(Joint.ANKLE_F, Joint.ANKLE_F, Joint.TOE_F)
        Joint.TOE_B -> ContactChain(Joint.ANKLE_B, Joint.ANKLE_B, Joint.TOE_B)
        Joint.HEEL_F -> ContactChain(Joint.ANKLE_F, Joint.ANKLE_F, Joint.HEEL_F)
        Joint.HEEL_B -> ContactChain(Joint.ANKLE_B, Joint.ANKLE_B, Joint.HEEL_B)

        else -> null
    }

    data class ContactChain(
        val rootJoint: Joint,
        val parentRotationJoint: Joint,
        val middleJoint: Joint
    )

    /**
     * Repositions the root so all registered fixed contacts are honoured, then re-bakes each contact
     * limb from the moved root. Mutates [pose]: node local transforms, then a fresh FK + flatten.
     *
     * When the pose declares a non-[PostureIntent.Kind.CUSTOM] intent the solver runs even with no
     * contacts, to seed/pin the coarse pelvis height from it (the relaxation loop is a no-op for
     * contact-less poses). A pose that neither registers contacts nor names a posture is a no-op.
     *
     * [previousRootWorld] is the Frame History input for inter-frame smoothing: the settled root the
     * previous frame produced, or `null` on the first frame of the window. It is supplied by
     * [SkeletonPipeline] and never derived from the solver's own memory, so this method is a pure
     * function of current-frame inputs plus supplied history. The value is in the solver's own root
     * space (the pelvis node's local position), so easing numerics are unchanged.
     */
    fun solve(
        pose: SkeletonPose,
        definition: SkeletonDefinition,
        previousRootWorld: Vector3? = null
    ) {
        val contacts = pose.contacts
        val postureDriven = pose.postureIntent.kind != PostureIntent.Kind.CUSTOM
        if (contacts.isEmpty() && !postureDriven) return
        val roots = pose.roots
        if (roots.isEmpty()) return

        for (i in nodeMap.indices) nodeMap[i] = null
        for (root in roots) collectNodes(root)

        val pelvis = nodeMap[Joint.PELVIS.index] ?: return
        // Preserve the authored pelvis orientation: the solver only ever *adds* a posture correction,
        // never wipes a deliberate lean (e.g. Deep Overhead Squat's folded pelvis).
        authoredPelvisRot.copyFrom(pelvis.localRotation)
        authoredPelvisPos.set(pelvis.localPosition)
        // Reset the recorded root-displacement deltas; they are recomputed below.
        pose.rootTranslationDelta = 0f
        pose.rootRotationDelta = 0f
        // The solver is a secondary producer of the Bone-Lengths-Verified flag and may only
        // strengthen it (merge rule AND): capture the primary authoring-time reading before
        // settlement writes anything, and assign the merged value exactly once at the end.
        val primaryVerified = pose.boneLengthsVerified

        // Snapshot the authored joint configuration (the "goal shape") before any mutation, so the
        // posture pass can regularize back toward it — joints bend only as far as contacts force.
        activeRoots = roots
        for (i in nodeMap.indices) {
            val n = nodeMap[i]
            if (n != null) authoredRotBuf[i].copyFrom(n.localRotation)
        }

        // A contact anchored to a horizontal support (ground) is what enables the pelvis-tilt DOF:
        // feet planted on the floor can be honoured by tilting the trunk rather than lifting the root
        // out of the floor plane.
        var hasGroundContact = false
        for (spec in contacts) {
            val c = spec.contact
            if (c != null && abs(c.normal.y) > 0.5f) { hasGroundContact = true; break }
        }

        // Seed the root/pelvis from the declared PostureIntent before relaxing, replacing the pose's
        // hand-computed `pelvisY`. CUSTOM is a no-op, so non-posture poses are unchanged.
        seedRootFromPostureIntent(pose, definition, pelvis)

        // Inter-frame smoothing: ease the seeded/solved root toward the previous frame's, so
        // marginally inconsistent contacts don't jitter. Gain 0 leaves the per-frame solve exact.
        // Local accumulation of this settlement's contact-limb findings; the carrier is written back
        // exactly once after the loop, so a mid-settle assignment cannot erase the primary reading.
        var contactFindings = true
        // Local accumulation of straight-limb fallback outcomes seen by the contact re-bake — from
        // the solver's own branch decision (a straight spec re-baked bent because the moved root left
        // it inside L1) and from the scratch reading of the straight solve. Merged into the carrier
        // exactly once after the loop, OR-only, so it can never erase an authoring-time `true`.
        var straightFindings = false
        if (SMOOTH_GAIN > 0f) {
            val prev = previousRootWorld
            if (prev != null) {
                pelvis.localPosition.x = SkeletonMath.lerp(prev.x, pelvis.localPosition.x, 1f - SMOOTH_GAIN)
                pelvis.localPosition.y = SkeletonMath.lerp(prev.y, pelvis.localPosition.y, 1f - SMOOTH_GAIN)
                pelvis.localPosition.z = SkeletonMath.lerp(prev.z, pelvis.localPosition.z, 1f - SMOOTH_GAIN)
            }
        }

        for (iter in 0 until MAX_ITERATIONS) {
            // FK so world positions reflect the current root placement.
            for (root in roots) root.updateWorldTransforms(zero, identity)

            // 1) How far the root must move to satisfy *reachability* only. A contact whose target
            //    is already inside the biological band is left where it is and the limb is re-baked
            //    to aim at it, rather than forced to full extension — forcing `maxReach` on a
            //    straight limb whose target sat closer than L1 lifted the root to fake a straight
            //    leg (the floating-pelvis bug).
            delta.set(0f, 0f, 0f)
            var moved = false
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                rootWorld.set(parent.worldPosition)
                away.set(rootWorld).subtract(spec.targetWorld)
                val dist = away.mag()

                val maxReach = (spec.length1 + spec.length2) * spec.constraint.effectiveExtensionRatio
                val minCos = cos(spec.constraint.minimumFlexionAngle * DEG2RAD)
                val minReach = sqrt(
                    spec.length1 * spec.length1 + spec.length2 * spec.length2 -
                        2f * spec.length1 * spec.length2 * minCos
                )

                // Only translate to bring an *unreachable* contact into band; a reachable contact is
                // never pushed to full extension.
                val desired = dist.coerceIn(minReach, maxReach)

                val diff = desired - dist
                if (abs(diff) > EPS) {
                    val inv = 1f / max(dist, 1e-5f)
                    dir.set(away.x * inv, away.y * inv, away.z * inv)
                    delta.x += dir.x * diff * RELAX
                    delta.y += dir.y * diff * RELAX
                    delta.z += dir.z * diff * RELAX
                    moved = true
                }
            }

            // Apply the averaged correction (damped Jacobi step). Per-contact corrections are
            // weighted by `contactPrecedence`: a contact listed earlier wins conflicts, so the root
            // is pulled harder toward its reach band. An empty list means uniform mean, so symmetric
            // stances don't double-count and N contacts don't overshoot.
            if (moved) {
                applyRootDelta(pelvis, delta, contacts, pose.contactPrecedence)
            }

            // 2) Posture DOF: when a reach residual remains (the root alone cannot satisfy the
            //    contacts), share it into a pelvis tilt instead of one translation. Symmetric ground
            //    contacts have zero imbalance, so the authored pelvis rotation is preserved.
            if (moved && hasGroundContact) {
                applyPelvisTilt(pelvis, contacts)
            }

            // 3) Re-bake every contact limb from the (possibly moved/tilted) proximal root to its
            //    target, writing offsets into the parent's true local frame. The end-effector stays
            //    on its support plane, and a straight limb whose target sits inside the proximal
            //    bone length is re-baked bent rather than collapsing the knee onto the ankle.
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                val rotNode = nodeMap[spec.parentRotationJoint.index] ?: continue
                val middle = nodeMap[spec.middleJoint.index] ?: continue
                val end = nodeMap[spec.endJoint.index] ?: continue

                rootWorld.set(parent.worldPosition)
                val parentRot = rotNode.worldRotation

                // A straight limb is only representable when the target is at least one bone (L1)
                // away; inside that radius it would degenerate to a point. Fall back to the triangle
                // IK so the limb stays valid and the contact stays on its surface.
                away.set(rootWorld).subtract(spec.targetWorld)
                val reachMag = away.mag()
                val canBeStraight = spec.straight && reachMag >= spec.length1 - 1e-3f

                if (canBeStraight) {
                    SkeletonMath.solveStraightLimb(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.constraint, ikResult, spec.contact
                    )
                } else {
                    // The parent frame rotation is set by the solver (translation and/or tilt), so
                    // the world-space pole is recomputed each pass.
                    SkeletonMath.solveIK(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.pole, spec.constraint, ikResult, spec.contact
                    )
                }
                // Record the fallback outcome from branch evidence and scratch only, never a
                // reconstructed predicate: `!canBeStraight` on a straight spec means the solver
                // itself executed the bent fallback.
                if (spec.straight && (!canBeStraight || ikResult.straightIntentDropped)) {
                    straightFindings = true
                }

                // The re-baked contact limb must preserve both bone lengths too.
                if (!SkeletonMath.bonesExact(rootWorld, ikResult.joint, ikResult.end, spec.length1, spec.length2)) {
                    contactFindings = false
                }

                dir.set(ikResult.joint).subtract(rootWorld)
                SkeletonMath.toLocalDirection(dir, parentRot, middle.localPosition)
                // A 1-bone (degenerate) contact limb has `middle == end` (kneeling knee, planted
                // elbow, head-stand): its IK result collapses to one segment, so the second offset
                // would be zero and would wipe the bone length. The segment is written above.
                if (middle !== end) {
                    dir.set(ikResult.end).subtract(ikResult.joint)
                    SkeletonMath.toLocalDirection(dir, parentRot, end.localPosition)
                }
            }

            if (!moved) break
        }

        // Publish the strengthened stamp exactly once: the primary authoring-time reading ANDed with
        // every contact limb's settlement finding. An unconditional `true` here erased a primary
        // `false` from a non-contact limb, which is why the merge is strengthen-only.
        pose.boneLengthsVerified = ValidationStampMerge.verified(primaryVerified, contactFindings)

        // The straight-intent-dropped flag strengthened exactly once with this settlement's fallback
        // findings (merge rule OR, strengthen-only).
        pose.straightIntentDropped =
            ValidationStampMerge.dropped(pose.straightIntentDropped, straightFindings)

        // True posture pass. The loop above is a root-reposition relaxation (translate + tilt the
        // pelvis, then re-bake each contact limb). When contacts are over-constrained or asymmetric
        // the pelvis alone cannot settle them, so the residual is distributed across the *free joint
        // angles* of each contact limb via damped CCD — honouring the contacts while staying as close
        // to the authored shape as they allow, instead of leaving the residual or floating the
        // pelvis. For well-posed poses the residual is ~0 and this is a strict no-op.
        solvePosture(contacts)

        // Record how far the solver displaced the root from its authored transform, so the
        // PELVIS_INTENT rule can surface unexpected root motion.
        val dxp = pelvis.localPosition.x - authoredPelvisPos.x
        val dyp = pelvis.localPosition.y - authoredPelvisPos.y
        val dzp = pelvis.localPosition.z - authoredPelvisPos.z
        pose.rootTranslationDelta = kotlin.math.sqrt(dxp * dxp + dyp * dyp + dzp * dzp)
        // Relative rotation R_rel = R_authored^-1 * R_current; its angle is the root turn.
        SkeletonMath.rotationToMatrix(authoredPelvisRot, pelvisMatX, pelvisMatY, pelvisMatZ)
        SkeletonMath.rotationToMatrix(pelvis.localRotation, tiltMatX, tiltMatY, tiltMatZ)
        SkeletonMath.transposeMultiply(pelvisMatX, pelvisMatY, pelvisMatZ, tiltMatX, tiltMatY, tiltMatZ, outMatX, outMatY, outMatZ)
        SkeletonMath.getRotationFromMatrix(outMatX, outMatY, outMatZ, rootDeltaRot)
        pose.rootRotationDelta = kotlin.math.abs(rootDeltaRot.angle)

        // The solved root is NOT persisted here: that would be cross-frame memory keyed by
        // SkeletonPose identity. The pipeline commits its Frame History after finalization
        // (`commitFrameHistory`) and supplies the previous root to the next solve as an input, so
        // this method ends its temporal work with the current frame.

        // Final FK + flatten so the finalized pose reflects the solved root placement.
        SkeletonPose.fromHierarchy(roots, pose)

        // Settlement result, populated exactly once. The world position is read AFTER
        // `fromHierarchy` so it reflects the post-FK settled root. Membership is the settled root
        // transform, the declared contacts and the conflict-resolution outcome — no copy of final
        // joint rotations. The outcome is the precedence winner (earliest non-empty entry in
        // `pose.contactPrecedence`), null when the list is empty (uniform-mean path) or no contacts
        // were declared.
        //
        // The field is named `declaredContactJoints`, not `settledContactJoints`, because the solver
        // does not track per-contact settlement: it re-bakes each declared contact's limb toward its
        // target under a bounded iteration, so naming it "settled" would claim data that isn't there.
        val settledRootWorld = Vector3(pelvis.worldPosition.x, pelvis.worldPosition.y, pelvis.worldPosition.z)
        val declaredContactJoints: List<Joint> = contacts.map { it.endJoint }
        val conflictWinner: Joint? = pickPrecedenceWinner(pose.contactPrecedence, contacts)
        pose.settlementResult = SettlementInfo(settledRootWorld, declaredContactJoints, conflictWinner)
    }

    /**
     * The highest-priority contact: the first end joint whose name appears in [precedence]. Null
     * when [precedence] is empty (uniform-mean path — every contact equal) or nothing matches.
     */
    private fun pickPrecedenceWinner(precedence: List<String>, contacts: List<ContactSpec>): Joint? {
        if (precedence.isEmpty() || contacts.isEmpty()) return null
        for (name in precedence) {
            val match = contacts.firstOrNull { it.endJoint.name == name }
            if (match != null) return match.endJoint
        }
        return null
    }

    /**
     * Seeds the root/pelvis transform from the pose's declared [PostureIntent] — the engine-owned
     * replacement for the pose hand-computing `pelvisY`/`pelvisX`.
     *
     * A contact-less pose can never be moved by the relaxation loop (there is no anchored contact to
     * satisfy), so its seed is pinned exactly and the solver owns the coarse pelvis height. A
     * contact-bearing pose is only eased toward it, so the authored root and the contact relaxation
     * dominate. x/z always stay authored — lateral/forward shift is a pose-level shape decision.
     *
     * [PostureIntent.Kind.CUSTOM] leaves the authored root untouched: the pose owns its shape when
     * that shape is genuinely shape-driven (planks, dynamic jumps, leans) rather than postural.
     */
    private fun seedRootFromPostureIntent(pose: SkeletonPose, def: SkeletonDefinition, pelvis: SkeletonNode) {
        val intent = pose.postureIntent
        if (intent.kind == PostureIntent.Kind.CUSTOM) return

        val seedY = postureSeedY(intent, def, pose)
        if (pose.contacts.isEmpty()) {
            // No anchored contact: the relaxation loop cannot move the root, so pin the seed
            // exactly — the solver is the sole owner of the coarse pelvis height.
            pelvis.localPosition.y = seedY
        } else {
            // Contact-bearing pose: only ease toward the seed, so the authored root and the
            // relaxation dominate.
            pelvis.localPosition.y = SkeletonMath.lerp(pelvis.localPosition.y, seedY, POSTURE_SEED_RELAX)
        }
    }

    /**
     * The exact coarse pelvis-Y target implied by a [PostureIntent.Kind]:
     *  - [SEATED_NEAR_FLOOR]: floor (0) + seated hip height ≈ shin + small clearance.
     *  - [HANGING_UNDER_BAR]: `barY - vertReach - torsoLength`, falling back to the default bar
     *    height when the pose supplied no bar contact.
     *  - [STANDING]: floor + thigh + shin + a small stand clearance.
     *  - [CUSTOM]: the authored pelvis (callers skip seeding entirely).
     *
     * The intent's tolerance is deliberately NOT folded in: it scopes how strictly the relaxation
     * must honour the seed before the PELVIS_INTENT rule flags a residual.
     */
    private fun postureSeedY(intent: PostureIntent, def: SkeletonDefinition, pose: SkeletonPose): Float {
        return when (intent.kind) {
            PostureIntent.Kind.SEATED_NEAR_FLOOR -> def.shinLength * 0.35f
            PostureIntent.Kind.STANDING -> def.shinLength + def.thighLength + 25f
            PostureIntent.Kind.HANGING_UNDER_BAR -> {
                // The bar height is the highest hand-grip contact plane; fall back to a default
                // overhead bar when none is registered (a pure HANGING intent with no contacts).
                var barY = DEFAULT_BAR_Y
                for (spec in pose.contacts) {
                    val c = spec.contact
                    if (c != null && abs(c.normal.y) > 0.5f) continue
                    // A vertical (bar) contact: its point.y is the grip height.
                    if (c != null) barY = c.point.y
                }
                val vertReach = def.upperArmLength + def.forearmLength
                barY - vertReach - def.torsoLength
            }
            PostureIntent.Kind.CUSTOM -> 0f
        }
    }

    /**
     * Applies the accumulated per-contact root [delta] to [pelvis], weighting each contact by its
     * position in [precedence]: earlier entries win conflicts, so the root is pulled toward the
     * highest-priority reachable contact. An empty list yields the uniform mean, preserving
     * symmetric-stance behaviour.
     */
    private fun applyRootDelta(
        pelvis: SkeletonNode,
        delta: Vector3,
        contacts: List<ContactSpec>,
        precedence: List<String>
    ) {
        if (contacts.isEmpty()) return
        if (precedence.isEmpty()) {
            delta.divide(contacts.size.toFloat())
            pelvis.localPosition.add(delta)
            return
        }
        // Precedence weight per contact: 1.0 at index 0, decreasing linearly, floored at 0.25 so no
        // contact is ever fully ignored.
        var weightSum = 0f
        val weights = FloatArray(contacts.size)
        for (i in contacts.indices) {
            val name = contacts[i].endJoint.name
            val idx = precedence.indexOf(name)
            val w = if (idx < 0) 1f else max(0.25f, 1f - idx * (0.75f / max(1, precedence.size - 1)))
            weights[i] = w
            weightSum += w
        }
        if (weightSum <= 0f) {
            delta.divide(contacts.size.toFloat())
            pelvis.localPosition.add(delta)
            return
        }
        pelvis.localPosition.x += delta.x * weightSum / contacts.size.toFloat()
        pelvis.localPosition.y += delta.y * weightSum / contacts.size.toFloat()
        pelvis.localPosition.z += delta.z * weightSum / contacts.size.toFloat()
    }

    /**
     * CCD posture relaxation. For every contact, walks the chain from the end-effector up to its
     * root joint and rotates each free joint (damped) so the end-effector is pulled onto its fixed
     * target. Starting from the authored configuration and moving joints only as far as the contact
     * forces makes the converged posture the closest reachable shape to the author's intent.
     *
     * Allocation-free and deterministic. A no-op when the contacts are already satisfied (residual
     * ≤ [POSTURE_EPS]), and only the joints on the contact chain move, so non-contact limbs stay
     * rigid.
     */
    private fun solvePosture(contacts: List<ContactSpec>) {
        for (iter in 0 until POSTURE_MAX_ITERS) {
            for (root in activeRoots) root.updateWorldTransforms(zero, identity)

            var maxResidual = 0f
            for (spec in contacts) {
                val end = nodeMap[spec.endJoint.index] ?: continue
                val root = nodeMap[spec.rootJoint.index] ?: continue
                val ex = spec.targetWorld.x - end.worldPosition.x
                val ey = spec.targetWorld.y - end.worldPosition.y
                val ez = spec.targetWorld.z - end.worldPosition.z
                val res = sqrt(ex * ex + ey * ey + ez * ez)
                if (res > maxResidual) maxResidual = res
                if (res <= POSTURE_EPS) continue

                // CCD from the end-effector upward: rotate each joint to aim the end-effector at the
                // target, re-evaluating FK after every joint so the next one sees the updated
                // position (the order cannot be swapped).
                var node: SkeletonNode? = end
                while (node != null) {
                    ccdAim(node, end, spec.targetWorld)
                    for (r in activeRoots) r.updateWorldTransforms(zero, identity)
                    if (node === root) break
                    node = node.parent
                }
            }

            if (maxResidual <= POSTURE_EPS) break
        }
    }

    /**
     * Rotates [node] (damped) so the end-effector [end] is pulled toward [target]: a world-space
     * shortest-arc rotation from `node->end` onto `node->target`, applied to the *local* rotation by
     * conjugating with the parent frame so it composes correctly through the FK chain.
     */
    private fun ccdAim(node: SkeletonNode, end: SkeletonNode, target: Vector3) {
        ccdA.set(end.worldPosition).subtract(node.worldPosition)
        ccdB.set(target).subtract(node.worldPosition)
        if (ccdA.mag() < 1e-5f || ccdB.mag() < 1e-5f) return

        SkeletonMath.getRotationToAlign(ccdA, ccdB, ccdDX, ccdDelta)
        ccdDelta.angle *= POSTURE_DAMP
        if (abs(ccdDelta.angle) < 1e-4f) return

        // P = parent world rotation (identity at the body root). New local L' = (P^-1 ∘ Delta ∘ P) ∘ L.
        val parent = node.parent
        if (parent == null) {
            ccdPX.set(1f, 0f, 0f); ccdPY.set(0f, 1f, 0f); ccdPZ.set(0f, 0f, 1f)
        } else {
            SkeletonMath.rotationToMatrix(parent.worldRotation, ccdPX, ccdPY, ccdPZ)
        }
        SkeletonMath.rotationToMatrix(ccdDelta, ccdDX, ccdDY, ccdDZ)

        // M = P^T ∘ Delta  (P^T is the inverse of a rotation matrix).
        SkeletonMath.transposeMultiply(ccdPX, ccdPY, ccdPZ, ccdDX, ccdDY, ccdDZ, ccdMX, ccdMY, ccdMZ)
        // MoP = M ∘ P = P^T ∘ Delta ∘ P
        SkeletonMath.multiplyMatrices(ccdMX, ccdMY, ccdMZ, ccdPX, ccdPY, ccdPZ, ccdOpX, ccdOpY, ccdOpZ)
        // L' = MoP ∘ L
        SkeletonMath.rotationToMatrix(node.localRotation, ccdOldX, ccdOldY, ccdOldZ)
        SkeletonMath.multiplyMatrices(ccdOpX, ccdOpY, ccdOpZ, ccdOldX, ccdOldY, ccdOldZ, ccdNewX, ccdNewY, ccdNewZ)
        SkeletonMath.getRotationFromMatrix(ccdNewX, ccdNewY, ccdNewZ, node.localRotation)

        // Shape regularization: nudge the joint back toward its authored angle by [POSTURE_REG]
        // (slerp(current, authored, POSTURE_REG)), so the converged posture is the closest reachable
        // shape to the author's intent. Well-posed contacts have ~0 residual and never get here.
        regularizeTowardAuthored(node)
    }

    /**
     * Slerps [node]'s local rotation a fraction [POSTURE_REG] back toward its authored value (from
     * [authoredRotBuf]): `current' = current ∘ (current⁻¹ ∘ authored)^POSTURE_REG`.
     */
    private fun regularizeTowardAuthored(node: SkeletonNode) {
        val auth = authoredRotBuf[node.joint.index]
        SkeletonMath.rotationToMatrix(node.localRotation, ccdOldX, ccdOldY, ccdOldZ)
        SkeletonMath.rotationToMatrix(auth, ccdPX, ccdPY, ccdPZ)
        // R = cur⁻¹ ∘ auth = transpose(cur) ∘ auth
        SkeletonMath.transposeMultiply(ccdOldX, ccdOldY, ccdOldZ, ccdPX, ccdPY, ccdPZ, ccdMX, ccdMY, ccdMZ)
        SkeletonMath.getRotationFromMatrix(ccdMX, ccdMY, ccdMZ, ccdDelta)
        if (abs(ccdDelta.angle) < 1e-4f) return
        ccdDelta.angle *= POSTURE_REG
        // Re-derive R's matrix from the scaled angle, then current' = cur ∘ R.
        SkeletonMath.rotationToMatrix(ccdDelta, ccdDX, ccdDY, ccdDZ)
        SkeletonMath.multiplyMatrices(ccdOldX, ccdOldY, ccdOldZ, ccdDX, ccdDY, ccdDZ, ccdNewX, ccdNewY, ccdNewZ)
        SkeletonMath.getRotationFromMatrix(ccdNewX, ccdNewY, ccdNewZ, node.localRotation)
    }

    /**
     * Shares an asymmetric reach residual into a small trunk rotation rather than a pure
     * translation. For a symmetric stance the imbalance is ~0 and the authored rotation is
     * untouched; the correction is composed *with* it, never replacing it.
     */
    private fun applyPelvisTilt(pelvis: SkeletonNode, contacts: List<ContactSpec>) {
        val imb = signedImbalance(contacts, pelvis) ?: return
        if (abs(imb) <= 1e-3f) return

        // A lateral (Z-position) imbalance is corrected by a roll about the lateral/side-bend X
        // axis (Trendelenburg), damped so it converges — NOT a pitch about Z, which would tilt the
        // trunk forward/back instead of dropping the passive hip.
        tiltDelta.set(1f, 0f, 0f, imb * TILT_GAIN)

        // pelvis.localRotation = authoredPelvisRot * tiltDelta  (preserves deliberate lean).
        SkeletonMath.rotationToMatrix(authoredPelvisRot, pelvisMatX, pelvisMatY, pelvisMatZ)
        SkeletonMath.rotationToMatrix(tiltDelta, tiltMatX, tiltMatY, tiltMatZ)
        SkeletonMath.multiplyMatrices(pelvisMatX, pelvisMatY, pelvisMatZ, tiltMatX, tiltMatY, tiltMatZ, outMatX, outMatY, outMatZ)
        SkeletonMath.getRotationFromMatrix(outMatX, outMatY, outMatZ, pelvis.localRotation)
    }

    /**
     * Net signed lateral imbalance of the ground contacts, weighted by how far each target is from
     * its proximal root along the lateral axis. Null when no ground contact is present, so the
     * caller skips the tilt entirely. Symmetric stances sum to ~0.
     */
    private fun signedImbalance(contacts: List<ContactSpec>, pelvis: SkeletonNode): Float? {
        var sum = 0f
        var ground = false
        for (spec in contacts) {
            val c = spec.contact
            if (c == null || abs(c.normal.y) <= 0.5f) continue
            ground = true
            val root = nodeMap[spec.rootJoint.index] ?: continue
            val lateral = spec.targetWorld.z - root.worldPosition.z
            // Passive side (+Z) joints are `_B`/`_P`, active side (-Z) `_F`/`_A`. Central joints
            // (head) contribute ~0 lateral offset and are harmless either way.
            val name = spec.endJoint.name
            val sign = if (name.endsWith("_B") || name.endsWith("_P")) 1f else -1f
            sum += sign * lateral
        }
        return if (ground) sum else null
    }

    private fun collectNodes(node: SkeletonNode) {
        nodeMap[node.joint.index] = node
        for (child in node.children) collectNodes(child)
    }
}
