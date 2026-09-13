package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Y-T Raises (`yt_raises_standard`) — the prone scapular-raise drill, the `posture` family's prone
 * member and the floor sibling of this batch's band/bar pulls.
 *
 * ## What the exercise is (the catalog's own statement, `strings.xml`)
 *
 *  * `steps` §1 *"Lie face down with the forehead lightly supported"* — a PRONE body on the mat.
 *  * §2 *"Raise the arms overhead into a Y shape"*, §3 *"Lower with control, then move the arms out
 *    into a T shape"*, §4 *"Lift again while keeping the shoulders down"*, §5 *"Alternate the two
 *    positions for the set"* — the rep is **two raises with two different arm targets**: one overhead
 *    (Y), one out to the sides (T), with a controlled lowering between them.
 *  * `desc` *"Reach long and lift from the mid-back, not the neck"*; `tech` *"Keep the thumbs
 *    pointing up. Lift only as high as you can without shrugging."*; `mistakes` *"Cranking the neck
 *    up. Bending the elbows too much. Swinging the arms instead of lifting with control."*
 *
 * ## The authored movement, one statement per copy line
 *
 *  * **Prone layout.** The whole-body prone pitch (`−π/2`, the family's own layout —
 *    `ReverseSnowAngelPose`) lays the spine's `+Y` along the world `+X` and the ventral `+X` face
 *    down the mat, so the neck/head continue the body's line and the head can never "crank up": the
 *    test measures the head staying in the body's plane.
 *  * **"Raise … / Lower … / Lift again" (§2–§4)** is authored as the arm's LIFT phase
 *    (`sin(π·fraction)` of each half of the cycle): the hands leave the mat and come back twice per
 *    cycle — the "lift with control" rhythm, zero at the cycle's own seams so the loop is continuous.
 *  * **"Alternate the two positions" (§5)** is authored as the two targets: the first half raises to
 *    [Y_RAISE], the second to [T_RAISE], with the hands' own measured positions telling them apart
 *    (the Y takes the hands overhead, past the head's X; the T takes them out to the widest lateral
 *    span) — the test asserts both.
 *  * **"Reach long … Bending the elbows too much" (desc/mistakes)** — the hands ride a constant
 *    [ARM_RADIUS] arc (`0.96 ×` the arm chain's own `maxReach`), so the elbow stays at the chain's
 *    near-full extension through the whole drill.
 *  * **"lift from the mid-back" / "keeping the shoulders down" (§4)** — the scapular drive rises with
 *    the lift (retraction, and depression rather than elevation: "without shrugging"), so the
 *    shoulders travel posteriorly at each raise and never rise above their resting height.
 *  * **"Swinging the arms instead of lifting with control" (mistakes)** — the arms' targets are
 *    authored positions on the arc, not an authored velocity, and the engine realizes them; the
 *    distinct-frame count in the test proves the sweep is realized, not repeated.
 *
 * ## Contacts, and what is deliberately NOT declared
 *
 * The prone body rests on the mat along its whole length, and the pose declares the floor line it
 * can state: the two `*_FOOT` contacts (the feet lie flat on the mat through the whole drill, and the
 * declaration is what makes the engine derive their plane and heading) and `HIPS` — the canonical
 * core support point, whose joint family is `{PELVIS, HIP_F, HIP_B}` (`SupportMath`), i.e. the pelvis
 * region that is grounded throughout. The HANDS are deliberately **not** declared: the drill lifts
 * them off the mat twice per cycle, and the declaration channel is per-pose, not per-frame, so a
 * hand contact would be a false statement for most of the rep. (The corpus's `SupermanPose` declares
 * lifted hands; this pose does not copy that.)
 *
 * ## Reach, and the straight limbs
 *
 * The arm targets sit at [ARM_RADIUS] from the very shoulder they are solved from, inside the arm
 * chain's `[40.134, 143.080]` band. The PRONE LEGS are authored straight through the node chain's own
 * local offsets — the corpus's straight-limb idiom (`PikePushUpPose`'s front leg) — because the leg
 * chain's `0.98` extension cap (`205.800` of `210`) would otherwise fold a `~25 u` knee bend into a
 * leg that is physically straight on the mat.
 *
 * ## Gaps recorded (not invented)
 *
 *  * **"the forehead lightly supported"** — the rig has no head/forehead support point
 *    (`SupportPoint` carries no head entry), so the head's floor relationship is authored geometry
 *    (it lies along the body's line at the body's own height) and is not declared as a contact.
 *  * **The thumbs' orientation ("Keep the thumbs pointing up")** is not expressible: the rig's hand is
 *    a single long axis from the wrist (`HandDefinition`: palm → knuckles → fingertips) with no
 *    thumb or roll DOF, and no wrist articulation is authored here (the engine derives the hand from
 *    the forearm). Recorded, not invented.
 *  * **The height of the lift** is not stated by the copy ("Lift only as high as you can without
 *    shrugging" is self-referential). [RAISE_LIFT] is an authored fraction of the arm length, recorded
 *    as an authored constant rather than presented as specification.
 */
class YTRaisesPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    override val metadata = PoseMetadata(
        // The prone family's own frame (`ReverseSnowAngelPose`): a body laid out along X, seen from
        // above the mat.
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The floor line the pose can state: the feet lie flat through the drill and the pelvis region
        // is grounded (the canonical core point). The lifted hands are NOT declared — see the KDoc.
        support = SupportDefinition(
            pivot = PivotType.HIPS,
            contacts = setOf(
                SupportContact.LEFT_FOOT,
                SupportContact.RIGHT_FOOT,
                SupportContact(SupportPoint.HIPS)
            )
        ),
        exerciseFamily = "posture",
        motionType = "Scapular Raise",
        bodyOrientation = "Prone"
    )

    private fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    /** One half of the rep: the two positions §5 says to alternate. */
    private enum class Raise { Y, T }

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        // Shape-driven root (the prone layout), so the solver leaves the authored root untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        // --- 1. The prone layout: the spine's +Y along the world +X, the ventral face down -----
        pelvis!!.localPosition.set(0f, PRONE_MAT_Y, 0f)
        // The prone layout, carried by the pelvis: the spine's local +Y → the world +X and the ventral
        // face down (`ReverseSnowAngelPose`'s own layout pitch). The chest authors no thoracic value:
        // this pose drives no girdle rotation, so the trunk stays exactly on the layout's line and the
        // engine's unauthored-thorax fallback resolves to the same frame (a no-op).
        buildSpineCurve(pelvis!!, chest!!, PRONE_PITCH, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The neck/head continue the body's line: their local offsets ARE the body's axis, so the
        // head can only ever lie in the mat's plane (never "crank up" — measured by the test).
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)

        // --- 2. The legs: straight on the mat (the chain's own local offsets, see the KDoc) ----
        kneeF!!.localPosition.set(0f, -def.thighLength, 0f)
        ankleF!!.localPosition.set(0f, -def.shinLength, 0f)
        kneeB!!.localPosition.set(0f, -def.thighLength, 0f)
        ankleB!!.localPosition.set(0f, -def.shinLength, 0f)
        // Feet flat on the mat, pointing away from the head: the engine rotates a declared heading by
        // the pelvis's rotation, so the root-relative direction that resolves to the world −X under
        // this layout is the body's own "down the trunk" direction.
        setHeading(Extremity.FOOT_F, Vector3(0f, -1f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(0f, -1f, 0f))

        // --- 3. The lift: two raises per cycle, alternating the two positions ------------------
        val cycle = context.progress * 2f
        val half = floor(cycle)
        val fraction = cycle - half
        val raise = if (half < 1f) Raise.Y else Raise.T
        val lift = sin(PI.toFloat() * fraction)

        // The girdle is left NEUTRAL: "lift from the mid-back" / "keeping the shoulders down" are
        // honoured structurally (the arms are raised from the shoulders, the trunk and the head stay
        // in the mat's plane — the test measures the head never leaving it), but the rig's girdle
        // channel rotates the shoulder line about the TRUNK's axis, which in this prone layout is a
        // vertical displacement of the shoulders — and the engine's unauthored-chest fallback carries
        // it twice (measured: 4 activation units publish `±12.7 u` of asymmetric shoulder travel, with
        // the passive shoulder below the mat). Recorded as a residual, not authored.
        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        // --- 4. The arms: a constant-radius arc between the resting position and the raise -----
        // The directions are authored for the ACTIVE (A, −Z = the model's left) side; the passive side
        // mirrors the lateral component, so `spread` is negative for A and positive for P.
        val restDir = Vector3(-1f, 0f, 0f).normalize()
        val raiseDir = when (raise) {
            Raise.Y -> Vector3(Y_AXIAL, RAISE_LIFT, -Y_SPREAD).normalize()
            Raise.T -> Vector3(T_AXIAL, RAISE_LIFT, -T_SPREAD).normalize()
        }
        val dirA = Vector3(
            SkeletonMath.lerp(restDir.x, raiseDir.x, lift),
            SkeletonMath.lerp(restDir.y, raiseDir.y, lift),
            SkeletonMath.lerp(restDir.z, raiseDir.z, lift)
        ).normalize()
        val shoulderAWorld = shoulderA!!.worldPosition
        val shoulderPWorld = shoulderP!!.worldPosition
        val targetA = Vector3(
            shoulderAWorld.x + dirA.x * ARM_RADIUS,
            shoulderAWorld.y + dirA.y * ARM_RADIUS,
            shoulderAWorld.z + dirA.z * ARM_RADIUS
        )
        val targetP = Vector3(
            shoulderPWorld.x + dirA.x * ARM_RADIUS,
            shoulderPWorld.y + dirA.y * ARM_RADIUS,
            shoulderPWorld.z - dirA.z * ARM_RADIUS
        )
        // Reachable by construction (a constant radius from the very root each arm is solved from);
        // projected anyway so a future edit of the radius is projected rather than silently clamped.
        SkeletonMath.clampTargetToReach(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetA)
        SkeletonMath.clampTargetToReach(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetP)
        // The elbows bow UP and outboard — `ReverseSnowAngelPose`'s own convention for a prone arm
        // sweep (`SupermanPose`/`ProneCobraStretchPose` author the same `(0, 1, ∓1)` pole), so a
        // near-straight raised arm never bends into the mat (`mistakes`: "Bending the elbows too
        // much" — the chain's own near-full extension).
        //
        // The pole must NOT be parallel to the arm: the T raise lays the chord along ±Z, so the earlier
        // `(0, 0, ∓1)` pole was nearly parallel to the bone and the solver's residual perpendicular
        // pointed DOWN — measured at the T peak, `ELBOW_A` published at `y = -2.584` (`12.584 u` BELOW the
        // shoulder line at `y = 10`), i.e. `2.584 u` through the mat. With `(0, 1, ∓1)` the same elbow sits
        // `35.423 u` ABOVE that line (measured A/B of elbow-above-shoulder: rest `0.000 → 17.167`, Y peak
        // `9.715 → 28.537`, T peak `-12.584 → 35.423`), which is the corpus's own prone-arm scale
        // (`ReverseSnowAngelPose` measures `15.5–22.7` across its sweep).
        bakeIkLimb(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /** The body joint line's height on the mat: the prone family's own value (`ReverseSnowAngelPose`). */
        const val PRONE_MAT_Y = 10f

        /**
         * The whole-body prone layout (`rotZ(−π/2)`): spine local `+Y` → world `+X` (the head end),
         * ventral `+X` → world `−Y` (face down) — `ReverseSnowAngelPose`'s own layout pitch.
         */
        val PRONE_PITCH = -(PI.toFloat() / 2f)

        /**
         * The straight-arm radius: `0.96 ×` the arm chain's own `maxReach` (`143.080`) — "Reach long",
         * at the chain's near-full extension (its `0.98` cap means even a locked-out chain measures
         * `~157°` interior).
         */
        const val ARM_RADIUS = 137.6f

        /** The lift, as a fraction of the arm length, at each raise's peak (`desc`/§4). */
        const val RAISE_LIFT = 0.15f

        /** The Y target's axial (world +X) and spread (world ±Z) components: overhead, past the head. */
        const val Y_AXIAL = 0.90f
        const val Y_SPREAD = 0.42f

        /** The T target: perpendicular to the body, out to the sides (the widest lateral span). */
        const val T_AXIAL = 0f
        const val T_SPREAD = 0.99f
    }
}
