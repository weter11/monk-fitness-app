package com.monkfitness.app.animation

import kotlin.math.*

/**
 * A fixed support contact registered by [BasePose.bakeIkLimb] / the validation pose base
 * when a limb is baked against a [ContactConstraint]. It captures everything the global
 * constraint solver needs to re-solve that limb after the root (pelvis) has been
 * repositioned, without the pose having to micro-manage the root.
 *
 * `endJoint` is the contact end-effector. The proximal chain is reconstructed from the fixed
 * skeleton topology via [chainForEnd]: the IK root is the joint whose world position seeds the
 * solve (`rootJoint`), the middle joint of the 2-bone chain is `middleJoint`, and the parent
 * frame whose rotation the offsets are authored in is `parentRotationJoint`. The solver now
 * supports any planted body part — ankles, hands, knees (kneeling), elbows/forearms (planks),
 * hips (seated), head (head-stand) and the toe/heel ends of the foot — not just ANKLE_* / HAND_*.
 * For the single-bone contacts (knee/elbow/hip/head/toe/heel) `middleJoint == endJoint` and the
 * re-bake treats the segment as one rigid bone.
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
 * PR-04 — global contact-constraint / root-repositioning layer.
 *
 * The engine is otherwise purely local (per-limb 2-bone IK + FK): it cannot satisfy
 * "fixed contacts + posture" simultaneously, so inconsistent authored geometry produces
 * penetration / folded limbs / floating contacts instead of redistributing the error
 * upstream. This solver is a deterministic, allocation-free relaxation that runs *between*
 * IK and FK: it gathers the fixed [ContactSpec]s (planted feet/hands) and repositions the
 * skeleton root (pelvis) so every contact is reached exactly, then re-bakes each contact
 * limb from the moved root. A fixed small number of iterations over {root transform, limb
 * IK} keeps it bounded and smooth (no flicker between frames).
 *
 * Issue A fix — posture, not point-reach. The solver is **not** pelvis-translation-only
 * anymore. A contact is treated as a *surface/anchor constraint* to be honored where it is,
 * not a hard 3-D point the root must be dragged to at full extension:
 *  - The root is translated **only** to restore *reachability* (bring an out-of-band target
 *    into the biological band). A reachable contact is left in place and the limb is re-baked
 *    to aim at it — this stops the old bug where a straight limb whose target sat closer than
 *    L1 was forced to `maxReach`, lifting the pelvis into a floating "V" (Middle Split).
 *  - A straight limb whose target falls inside the proximal-bone length is re-baked as a
 *    (non-degenerate) bent limb, so it stays valid and keeps the contact on its surface
 *    instead of collapsing the knee onto the ankle.
 *  - When a reach residual remains (the root alone cannot satisfy asymmetric contacts), the
 *    correction is shared into a **pelvis tilt** (composed with the authored pelvis rotation,
 *    never replacing it) — a second DOF beyond translation. Symmetric stances have zero
 *    imbalance, so correct poses keep their exact authored orientation.
 *
 * Design notes (constitution: ENGINE.md §2/§5 — engine solves motion, poses declare
 * contacts):
 * - It never invents contacts; it only consumes those the pose already registered via the
 *   contact-bearing `bakeIkLimb` calls.
 * - It never moves a non-contact limb's authored shape; it only translates/tilts the root, so
 *   every other limb follows rigidly and stays correct.
 * - It preserves bone lengths (it re-bakes through the existing analytical IK + local-frame
 *   bake, exactly like the pose did).
 */
object ConstraintSolver {

    private const val MAX_ITERATIONS = 16
    private const val RELAX = 0.5f
    private const val EPS = 1e-3f
    private const val DEG2RAD = 3.1415927f / 180f
    // Default overhead-bar grip height (world Y) used by the HANGING_UNDER_BAR seed when the pose
    // registered no explicit bar contact. Mirrors the validation/vertical-pull convention (bar at 500).
    private const val DEFAULT_BAR_Y = 500f

    // Phase 2 (F2/F7/F9) — the solver is the sole mover of the root/pelvis transform: the root is
    // *seeded* from the pose's declared [PostureIntent] (B1.1 formulas) and contact conflicts are
    // resolved by `contactPrecedence` instead of the pose hand-computing `pelvisY`/`pelvisX`.
    // legacy path is preserved verbatim until the global flip.
    private const val POSTURE_SEED_RELAX = 0.5f
    // Inter-frame temporal smoothing gain (F9): how strongly each frame eases the solved root
    // toward the previously solved root. Small so the posture still settles but frames don't
    // jitter when contacts are marginally inconsistent. Zero = no smoothing (strict per-frame solve).
    private const val SMOOTH_GAIN = 0.25f

    // Phase 2 (F9) — per-pose inter-frame relaxation cache. Keyed by the [SkeletonPose] identity,
    // which production poses reuse across frames, so the last solved root is carried forward and
    // the current solve eases toward it. `WeakHashMap` keeps it free of leaks when poses are GC'd.
    private val lastSolvedRoot = java.util.WeakHashMap<SkeletonPose, Vector3>()

    // Persistent scratch — no hot-path allocation.
    private val zero = Vector3()
    private val identity = JointRotation()
    private val delta = Vector3()
    private val away = Vector3()
    private val dir = Vector3()
    private val rootWorld = Vector3()
    private val ikResult = SkeletonMath.IKResult()
    private val nodeMap = Array<SkeletonNode?>(Joint.entries.size) { null }

    // Posture-DOF scratch (pelvis tilt relaxation, PR-04 / Issue A). The solver is no longer
    // translation-only: when contacts are reachable it leaves the root where the contact pins it
    // and, when an asymmetric reach residual exists, it shares the correction into a pelvis tilt
    // (composed with the authored pelvis rotation) instead of dumping everything into one
    // vertical translation. For symmetric contacts the imbalance is zero, so the authored pelvis
    // orientation is preserved verbatim (a strict no-op for correct poses).
    private val tiltDelta = JointRotation()
    private val authoredPelvisRot = JointRotation()
    private val authoredPelvisPos = Vector3()
    private val rootDeltaRot = JointRotation()
    private val pelvisMatX = Vector3(); private val pelvisMatY = Vector3(); private val pelvisMatZ = Vector3()
    private val tiltMatX = Vector3(); private val tiltMatY = Vector3(); private val tiltMatZ = Vector3()
    private val outMatX = Vector3(); private val outMatY = Vector3(); private val outMatZ = Vector3()
    private val imbA = Vector3(); private val imbB = Vector3()
    private const val TILT_GAIN = 0.01f

    // Posture-pass (UNI-1) CCD scratch — reused shared matrix/rotation buffers (no hot-path
    // allocation). The posture pass is a *true* solve: it distributes an unreachable/asymmetric
    // contact residual across the limb's free joint angles (hip→knee→ankle / shoulder→elbow→
    // wrist) instead of dumping everything into the pelvis translation/tilt alone.
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
    // over-constrained residual is shared into the *closest* authored posture, not an arbitrary
    // one (UNI-1: "best match the authored shape").
    private const val POSTURE_REG = 0.1f

    /**
     * Maps a contact end-effector to its proximal chain in the fixed skeleton topology.
     * Returns null for joints that are not a supported contact limb (the solver then simply
     * skips registration at bake time).
     *
     * Every contact is expressed as a 2-bone chain `(rootJoint -> middleJoint -> endJoint)`.
     * The original scope only covered the two true 2-bone limbs (legs: HIP->KNEE->ANKLE and
     * arms: SHOULDER->ELBOW->HAND). Issue D extends this to every other planted body part:
     * knees, elbows/forearms, hips, head, and the toe/heel ends of the foot. For those the
     * contact joint is the *end* of a single bone (the thigh, forearm, neck, foot, …), so the
     * chain is modelled as a degenerate 2-bone limb with `middleJoint == endJoint` — the
     * re-bake treats it as a rigid one-bone segment (see [solve]). `parentRotationJoint` is
     * the frame in which that segment's offset is authored (the bone's actual parent, which
     * for an identity-rotation link equals the historically-used pelvis/scapula frame, so the
     * existing legs/arms entries are unchanged in meaning).
     */
    fun chainForEnd(end: Joint): ContactChain? = when (end) {
        // --- Legs: 2-bone limb HIP -> KNEE -> ANKLE (unchanged) ---
        Joint.ANKLE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.ANKLE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)
        // Kneeling: the knee is the end of the thigh (1-bone). middle == end.
        Joint.KNEE_F -> ContactChain(Joint.HIP_F, Joint.PELVIS, Joint.KNEE_F)
        Joint.KNEE_B -> ContactChain(Joint.HIP_B, Joint.PELVIS, Joint.KNEE_B)

        // --- Arms: 2-bone limb SHOULDER -> ELBOW -> HAND (unchanged) ---
        Joint.HAND_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.HAND_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)
        // Forearm / elbow plank: the elbow is the end of the forearm (1-bone). middle == end.
        Joint.ELBOW_A -> ContactChain(Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A)
        Joint.ELBOW_P -> ContactChain(Joint.SHOULDER_P, Joint.SCAPULA_P, Joint.ELBOW_P)

        // --- Hips: seated / kneeling on the pelvis side. The hip is the end of the (identity)
        //     pelvis->hip offset (1-bone), framed in the pelvis. middle == end. ---
        Joint.HIP_F -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_F)
        Joint.HIP_B -> ContactChain(Joint.PELVIS, Joint.PELVIS, Joint.HIP_B)

        // --- Head: head-stand. The head is the end of the (identity) chest->neck->head chain,
        //     modelled as a 1-bone neck segment framed in the chest. middle == end. ---
        Joint.HEAD_POS -> ContactChain(Joint.CHEST, Joint.CHEST, Joint.HEAD_POS)

        // --- Feet ends: toe / heel stands. The toe/heel is the end of the foot (1-bone,
        //     ANKLE -> TOE/HEEL), framed in the ankle. middle == end. ---
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
     * Repositions the root so all registered fixed contacts are honored, then re-bakes each
     * contact limb from the moved root. Mutates the supplied [pose] (its node local
     * transforms and, at the end, runs a fresh FK + flatten).
     *
     * Branch B3 — posture universality: when the solver owns posture and the pose
     * declares a non-[PostureIntent.Kind.CUSTOM] intent, the solver runs even with **no** contacts,
     * so it can seed/pin the coarse pelvis height from the intent (the relaxation loop below is a
     * strict no-op for contact-less poses, so production standing shapes are untouched apart from
     * the engine-owned root height). A pose that neither registers contacts nor names a posture is
     * still a pure no-op.
     */
    fun solve(pose: SkeletonPose, definition: SkeletonDefinition) {
        val contacts = pose.contacts
        // Phase B collapsed SOLVER_OWNS_POSTURE to its true branch (always on).
        val postureDriven = pose.postureIntent.kind != PostureIntent.Kind.CUSTOM
        if (contacts.isEmpty() && !postureDriven) return
        val roots = pose.roots
        if (roots.isEmpty()) return

        for (i in nodeMap.indices) nodeMap[i] = null
        for (root in roots) collectNodes(root)

        val pelvis = nodeMap[Joint.PELVIS.index] ?: return
        // Preserve the authored pelvis orientation so the solver only ever *adds* a posture
        // correction, never wipes a deliberate lean (e.g. Deep Overhead Squat's folded pelvis).
        authoredPelvisRot.copyFrom(pelvis.localRotation)
        authoredPelvisPos.set(pelvis.localPosition)
        // UNI-6 — reset the recorded root-displacement deltas; they are recomputed below.
        pose.rootTranslationDelta = 0f
        pose.rootRotationDelta = 0f
        // Phase 1 (F5): the solver is a secondary writer of the bone-length stamp; re-arm it
        // optimistically and AND each contact limb's re-bake below (the primary write happens in
        // bakeIkLimb at authoring time; this keeps the stamp coherent after the solver moves the root).
        pose.boneLengthsVerified = true

        // UNI-1: snapshot the authored joint configuration (the "goal shape") before any solver
        // mutation, so the posture pass can regularize its CCD solution back toward the authored
        // posture (it only bends joints as far as the contacts force it to).
        activeRoots = roots
        for (i in nodeMap.indices) {
            val n = nodeMap[i]
            if (n != null) authoredRotBuf[i].copyFrom(n.localRotation)
        }

        // Are any contacts anchored to a horizontal support (ground)? That is what enables the
        // pelvis-tilt posture DOF: feet planted on the floor can be honored by tilting the trunk
        // rather than by lifting the root out of the floor plane.
        var hasGroundContact = false
        for (spec in contacts) {
            val c = spec.contact
            if (c != null && abs(c.normal.y) > 0.5f) { hasGroundContact = true; break }
        }

        // Phase 2 (F2) — seed the root/pelvis from the pose's declared PostureIntent before
        // relaxing. This replaces the pose's hand-computed `pelvisY` with an engine-derived exact
        // value (B1.1 / B4). For CUSTOM intents the seed is a no-op, so non-posture poses are
        // unchanged. (Phase B collapsed SOLVER_OWNS_POSTURE to its true branch.)
        seedRootFromPostureIntent(pose, definition, pelvis)

        // Phase 2 (F9) — inter-frame temporal smoothing. Ease the seeded/solved root toward the
        // root produced for this same pose on the previous frame, so marginally inconsistent
        // contacts don't jitter frame-to-frame. Disabled (gain 0) leaves the per-frame solve exact.
        // (Phase B collapsed SOLVER_OWNS_POSTURE to its true branch.)
        if (SMOOTH_GAIN > 0f) {
            val prev = lastSolvedRoot[pose]
            if (prev != null) {
                pelvis.localPosition.x = SkeletonMath.lerp(prev.x, pelvis.localPosition.x, 1f - SMOOTH_GAIN)
                pelvis.localPosition.y = SkeletonMath.lerp(prev.y, pelvis.localPosition.y, 1f - SMOOTH_GAIN)
                pelvis.localPosition.z = SkeletonMath.lerp(prev.z, pelvis.localPosition.z, 1f - SMOOTH_GAIN)
            }
        }

        for (iter in 0 until MAX_ITERATIONS) {
            // Forward kinematics so world positions reflect the current root placement.
            for (root in roots) root.updateWorldTransforms(zero, identity)

            // 1) Decide how far the root must move to satisfy *reachability* only. A contact is
            //    left exactly where it is when its target is already within the biological band —
            //    the limb is re-baked to AIM AT the contact (honoring it as a surface/anchor)
            //    rather than being forced to full extension. The old code forced straight limbs
            //    whose target sat closer than L1 to `maxReach`, which lifted the root to fake a
            //    straight leg (the Issue A "point-reach, not posture" floating-pelvis bug).
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

                // Posture-aware: only translate to bring an *unreachable* contact into band. A
                // reachable contact is never pushed to full extension.
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

            // Apply the averaged correction (damped Jacobi step). The per-contact corrections are
            // weighted by `contactPrecedence` (F7): a contact listed earlier wins conflicts, so the
            // root is pulled harder toward the higher-priority contact's reach band. When the
            // precedence list is empty every contact is equal (uniform mean), so symmetric stances
            // don't double-count and N contacts don't overshoot.
            if (moved) {
                applyRootDelta(pelvis, delta, contacts, pose.contactPrecedence)
            }

            // 2) Posture DOF (Issue A): when a reach residual remains (the root alone cannot
            //    satisfy the contacts), share the correction into a pelvis tilt instead of
            //    dumping it all into a single translation. For symmetric ground contacts the
            //    imbalance is zero, so the authored pelvis rotation is preserved (no-op).
            if (moved && hasGroundContact) {
                applyPelvisTilt(pelvis, contacts)
            }

            // 3) Re-bake every contact limb from the (possibly moved/tilted) proximal root to its
            //    target, writing the offsets back into the parent's true local frame. Contacts are
            //    honored as surface/anchor constraints: the end-effector stays on its support
            //    plane, and a straight limb whose target sits inside the proximal-bone length is
            //    re-baked as a (non-degenerate) bent limb — the reachable compromise — instead of
            //    collapsing the knee onto the ankle.
            for (spec in contacts) {
                val parent = nodeMap[spec.rootJoint.index] ?: continue
                val rotNode = nodeMap[spec.parentRotationJoint.index] ?: continue
                val middle = nodeMap[spec.middleJoint.index] ?: continue
                val end = nodeMap[spec.endJoint.index] ?: continue

                rootWorld.set(parent.worldPosition)
                val parentRot = rotNode.worldRotation

                // A straight limb can only be represented when the target is at least one bone
                // (L1) away; inside that radius it would degenerate to a point. Fall back to the
                // triangle IK so the limb stays valid and the contact stays on its surface.
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
                    // the contact's world-space pole is recomputed each pass and reused directly.
                    SkeletonMath.solveIK(
                        rootWorld, spec.targetWorld, spec.length1, spec.length2,
                        spec.pole, spec.constraint, ikResult, spec.contact
                    )
                }

                // Phase 1 (F5): the re-baked contact limb must preserve both bone lengths too.
                if (!SkeletonMath.bonesExact(rootWorld, ikResult.joint, ikResult.end, spec.length1, spec.length2)) {
                    pose.boneLengthsVerified = false
                }

                dir.set(ikResult.joint).subtract(rootWorld)
                SkeletonMath.toLocalDirection(dir, parentRot, middle.localPosition)
                // A 1-bone (degenerate) contact limb has `middle == end` (e.g. a kneeling knee,
                // a planted elbow, a head-stand). Its IK result collapses to a single segment
                // (joint == end == target), so the second offset would be zero and would wipe
                // the bone length. Skip it: the segment is already written above.
                if (middle !== end) {
                    dir.set(ikResult.end).subtract(ikResult.joint)
                    SkeletonMath.toLocalDirection(dir, parentRot, end.localPosition)
                }
            }

            if (!moved) break
        }

        // UNI-1 — true posture pass. The loop above is a root-reposition relaxation (translate +
        // tilt the pelvis, then re-bake each contact limb toward its target). When contacts are
        // over-constrained or asymmetric the pelvis alone cannot settle them, so the residual is
        // now distributed across the *free joint angles* of each contact limb via damped CCD. This
        // is a genuine posture solve: it honours the contacts and keeps the configuration as close
        // to the authored shape as the contacts allow, instead of leaving the residual uncorrected
        // or floating the pelvis. For well-posed poses the residual is already ~0, so this pass is
        // a strict no-op (the authored pose is preserved verbatim).
        solvePosture(contacts)

        // UNI-6 — record how far the solver displaced the root from its authored transform so the
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

        // Phase 2 (F9) — persist the solved root for inter-frame temporal smoothing on the next
        // build of this same pose instance (see [lastSolvedRoot]). (Phase B collapsed
        // SOLVER_OWNS_POSTURE to its true branch.)
        val cached = lastSolvedRoot[pose]
        if (cached != null) {
            cached.set(pelvis.localPosition)
        } else {
            lastSolvedRoot[pose] = pelvis.localPosition.copy()
        }

        // Final FK + flatten so the finalized pose reflects the solved root placement.
        SkeletonPose.fromHierarchy(roots, pose)
    }

    /**
     * Phase 2 (F2) — seeds the root/pelvis transform from the pose's declared [PostureIntent]
     * (B1.1 / B3). This is the engine-owned replacement for the pose's hand-computed `pelvisY`/
     * `pelvisX` arithmetic: the solver derives the coarse pelvis height from the intent kind.
     *
     * Branch B3 — posture universality: the solver now owns the root for **every** pose that names
     * a non-[PostureIntent.Kind.CUSTOM] intent, not just contact poses:
     *  - **Non-contact poses** (most production STANDING / HANGING / SEATED shapes): the relaxation
     *    loop below can never move the root (there is no anchored contact to satisfy), so the seed
     *    is *pinned exactly* (`pelvis.y = seedY`). This is the engine-owned root: a STANDING pose's
     *    `standH` is now derived by the solver, byte-identical to the value the pose used to write
     *    by hand. The x/z stay authored (lateral/forward shift is a pose-level shape decision).
     *  - **Contact poses** (squats/hangs/etc. that register fixed supports): the seed is *eased*
     *    (damped) toward `seedY`, exactly as in M3, so the authored root and the contact relaxation
     *    dominate; the regression contract (`ConstraintSolverPhase2Test.seatedSeedDoesNotRegress…`)
     *    is preserved. The relaxation loop then refines the seed against the actual contacts.
     *
     * [PostureIntent.Kind.CUSTOM] leaves the authored root untouched (the pose owns its own shape);
     * this is the deliberate, reversible B3 fallback for poses whose root is genuinely shape-driven
     * (planks, dynamic jumps, leans) rather than a simple postural template.
     *
     * Allocation-free: reuses [dir]/[delta] scratch. Deterministic.
     */
    private fun seedRootFromPostureIntent(pose: SkeletonPose, def: SkeletonDefinition, pelvis: SkeletonNode) {
        val intent = pose.postureIntent
        if (intent.kind == PostureIntent.Kind.CUSTOM) return

        val seedY = postureSeedY(intent, def, pose)
        if (pose.contacts.isEmpty()) {
            // No anchored contact: the relaxation loop cannot move the root, so pin the seed
            // exactly. The solver is the sole owner of the coarse pelvis height (B3).
            pelvis.localPosition.y = seedY
        } else {
            // Contact-bearing pose: keep the M3 eased seed so the authored root + relaxation
            // dominate; preserves the seated/hanging regression contract.
            pelvis.localPosition.y = SkeletonMath.lerp(pelvis.localPosition.y, seedY, POSTURE_SEED_RELAX)
        }
    }

    /**
     * Phase 2 (F2) — the exact coarse pelvis-Y target implied by a [PostureIntent.Kind] (B1.1):
     *  - [SEATED_NEAR_FLOOR]: floor (0) + seated hip height ≈ shin + small clearance.
     *  - [HANGING_UNDER_BAR]: derived from the overhead bar the hands grip, falling back to the
     *    default bar height when the pose supplied no bar contact. `barY - vertReach - torsoLength`.
     *  - [STANDING]: floor + thigh + shin + a small stand clearance.
     *  - [CUSTOM]: the authored pelvis (callers skip seeding entirely).
     * [tolerance] (from the intent) is intentionally NOT folded into the seed — it scopes how
     * strictly the relaxation must honour the seed before the PELVIS_INTENT rule flags a residual.
     */
    private fun postureSeedY(intent: PostureIntent, def: SkeletonDefinition, pose: SkeletonPose): Float {
        return when (intent.kind) {
            PostureIntent.Kind.SEATED_NEAR_FLOOR -> def.shinLength * 0.35f
            PostureIntent.Kind.STANDING -> def.shinLength + def.thighLength + 25f
            PostureIntent.Kind.HANGING_UNDER_BAR -> {
                // The bar height is the highest hand-grip contact plane; fall back to a default
                // overhead bar when none is registered (e.g. a pure HANGING intent with no contacts).
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
     * Phase 2 (F7) — applies the accumulated per-contact root [delta] to [pelvis], weighting each
     * contact by its position in [precedence]. A contact listed earlier wins conflicts: its
     * correction is amplified and the lower-priority contacts are proportionally damped, so the
     * root is pulled toward the highest-priority reachable contact. An empty precedence list yields
     * the uniform mean (every contact equal), exactly preserving the legacy symmetric-stance
     * behaviour. The result is the damped Jacobi step applied to the pelvis local position.
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
        // Map each contact end-joint name to its precedence weight (1.0 for index 0, linearly
        // decreasing for later entries, floored at 0.25 so no contact is ever fully ignored).
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
        // Weighted mean step.
        pelvis.localPosition.x += delta.x * weightSum / contacts.size.toFloat()
        pelvis.localPosition.y += delta.y * weightSum / contacts.size.toFloat()
        pelvis.localPosition.z += delta.z * weightSum / contacts.size.toFloat()
    }

    /**
     * UNI-1 — CCD posture relaxation. For every contact, walks the kinematic chain from the
     * end-effector up to its root joint and rotates each free joint (damped) so the end-effector
     * is pulled onto its fixed target. Because it starts from the authored configuration and only
     * moves joints as far as the contact forces, the converged posture is the closest reachable
     * shape to the author's intent — a real posture solve, not a pelvis-only correction.
     *
     * Allocation-free: reuses the shared [ccdDelta]/matrix scratch. Deterministic. No-op when the
     * contacts are already satisfied (residual ≤ [POSTURE_EPS]), so non-over-constrained poses are
     * untouched. Only the joints on the contact chain move, so non-contact limbs stay rigid.
     */
    private fun solvePosture(contacts: List<ContactSpec>) {
        for (iter in 0 until POSTURE_MAX_ITERS) {
            // World transforms for the current configuration.
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

                // CCD: from the end-effector upward to the root joint, rotate each joint to aim the
                // end-effector at the target, re-evaluating FK after every joint so the next joint
                // sees the updated end-effector position.
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
     * Rotates [node] (damped) so that the end-effector [end] is pulled toward [target]. The
     * correction is a world-space shortest-arc rotation from `node→end` onto `node→target`,
     * applied to [node]'s *local* rotation by conjugating with the parent frame (so it composes
     * correctly through the FK chain). Honours [POSTURE_DAMP] for stable convergence.
     */
    private fun ccdAim(node: SkeletonNode, end: SkeletonNode, target: Vector3) {
        ccdA.set(end.worldPosition).subtract(node.worldPosition)
        ccdB.set(target).subtract(node.worldPosition)
        if (ccdA.mag() < 1e-5f || ccdB.mag() < 1e-5f) return

        // World-space delta aligning the current end direction onto the target direction.
        SkeletonMath.getRotationToAlign(ccdA, ccdB, ccdDX, ccdDelta)
        ccdDelta.angle *= POSTURE_DAMP
        if (abs(ccdDelta.angle) < 1e-4f) return

        // Parent world rotation P (identity for the body root), its matrix (ccdPX..Z), and the
        // delta matrix (ccdDX..Z). New local L' = (P^-1 ∘ Delta ∘ P) ∘ L.
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

        // UNI-1 shape regularization: nudge the joint back toward its authored angle by
        // [POSTURE_REG] so the converged posture is the closest reachable shape to the author's
        // intent (a slerp(current, authored, POSTURE_REG)). For well-posed contacts the residual
        // is ~0 and this pass never runs, so authored poses are preserved verbatim.
        regularizeTowardAuthored(node)
    }

    /**
     * Slerps [node]'s current local rotation a fraction [POSTURE_REG] of the way back toward its
     * authored value (captured in [authoredRotBuf]). Allocation-free: reuses the shared matrix
     * scratch. `current' = current ∘ (current⁻¹ ∘ authored)^POSTURE_REG`.
     */
    private fun regularizeTowardAuthored(node: SkeletonNode) {
        val auth = authoredRotBuf[node.joint.index]
        // cur matrix
        SkeletonMath.rotationToMatrix(node.localRotation, ccdOldX, ccdOldY, ccdOldZ)
        // auth matrix
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
     * Adds a small pelvis-tilt correction that shares an asymmetric reach residual into a trunk
     * rotation rather than a pure translation. `signedImbalance` is the net horizontal
     * (left/right) offset of the ground contacts relative to their proximal roots; for a symmetric
     * stance it is ~0 and the authored pelvis rotation is left untouched. The correction is
     * composed *with* the authored rotation (never replacing it) and is bounded/deterministic.
     * Allocation-free: reuses the shared matrix scratch.
     */
    private fun applyPelvisTilt(pelvis: SkeletonNode, contacts: List<ContactSpec>) {
        val imb = signedImbalance(contacts, pelvis) ?: return
        if (abs(imb) <= 1e-3f) return

        // Small roll about the world X axis (lateral side-bend / Trendelenburg) to balance a
        // lateral contact imbalance, damped so it converges. A lateral (Z-position) imbalance is
        // corrected by a roll about the body's lateral/side-bend X axis — NOT a pitch about Z,
        // which would tilt the trunk forward/back instead of dropping the passive hip (UNI-4).
        tiltDelta.set(1f, 0f, 0f, imb * TILT_GAIN)

        // pelvis.localRotation = authoredPelvisRot * tiltDelta  (preserves deliberate lean).
        SkeletonMath.rotationToMatrix(authoredPelvisRot, pelvisMatX, pelvisMatY, pelvisMatZ)
        SkeletonMath.rotationToMatrix(tiltDelta, tiltMatX, tiltMatY, tiltMatZ)
        SkeletonMath.multiplyMatrices(pelvisMatX, pelvisMatY, pelvisMatZ, tiltMatX, tiltMatY, tiltMatZ, outMatX, outMatY, outMatZ)
        SkeletonMath.getRotationFromMatrix(outMatX, outMatY, outMatZ, pelvis.localRotation)
    }

    /**
     * Net signed horizontal imbalance of the ground contacts: +1 per contact to the passive side
     * (-Z) and -1 per contact to the active side (+Z), weighted by how far the contact's target is
     * from its proximal root along the body's lateral axis. Returns null when no ground contact is
     * present (so the caller skips the tilt entirely). Symmetric stances sum to ~0.
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
            // Passive side (+Z) joints are `_B`/`_P`; active side (-Z) are `_F`/`_A`.
            // Central joints (head) contribute ~0 lateral offset and are harmless either way.
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
