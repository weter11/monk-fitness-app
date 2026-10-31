package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * BasePlankPose owns the Plank family's shared scaffolding. Both members — the prone forearm plank and
 * the lateral side plank — are isometric, floor-supported holds, and what they share is engine
 * plumbing, not choreography: the hierarchy, allocation-free IK buffers, [BasePose.bakeIkLimb], the
 * breathing micro-driver, the camera/ground and finalization. Everything that differs (prone vs rolled
 * anchoring, two-forearm vs single-forearm support, leg stacking, scapular behaviour) is variant
 * biomechanics and lives in the concrete pose.
 */
abstract class BasePlankPose : BasePose() {

    // Height at which a planted forearm / plantar-flexed toe rests on the mat.
    // Mirrors FootDefinition.ankleHeight so limbs sit *on* the ground, not through it.
    protected val contactY = 15f

    /**
     * Shared camera + environment (single source — no per-variant literals). Pitch lowered 0.22 -> 0.16
     * so the eye travels *along* the near-horizontal plank line instead of down onto it, and zoom
     * widened 1.30 -> 1.22 so the long forearm-to-toe span stays framed. Yaw unchanged.
     */
    protected val plankCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.16f,
        defaultZoom = 1.22f
    )
    protected val plankEnvironment = EnvironmentDefinition(
        ground = GroundDefinition(visible = true, level = 0f)
    )

    protected var roots: List<SkeletonNode>? = null
    protected var pelvis: SkeletonNode? = null; protected var chest: SkeletonNode? = null; protected var neck: SkeletonNode? = null; protected var head: SkeletonNode? = null
    // The shoulder girdle's own nodes (CHEST -> CLAVICLE -> SCAPULA -> SHOULDER). The two planks are
    // supported BY the girdle, so their scapular articulation is authored on this canonical pair.
    protected var clavicleA: SkeletonNode? = null; protected var scapulaA: SkeletonNode? = null
    protected var clavicleP: SkeletonNode? = null; protected var scapulaP: SkeletonNode? = null
    protected var shoulderA: SkeletonNode? = null; protected var elbowA: SkeletonNode? = null; protected var handA: SkeletonNode? = null; protected var palmA: SkeletonNode? = null; protected var knucklesA: SkeletonNode? = null; protected var fingertipsA: SkeletonNode? = null
    protected var shoulderP: SkeletonNode? = null; protected var elbowP: SkeletonNode? = null; protected var handP: SkeletonNode? = null; protected var palmP: SkeletonNode? = null; protected var knucklesP: SkeletonNode? = null; protected var fingertipsP: SkeletonNode? = null
    protected var hipF: SkeletonNode? = null; protected var kneeF: SkeletonNode? = null; protected var ankleF: SkeletonNode? = null; protected var heelF: SkeletonNode? = null; protected var toeF: SkeletonNode? = null
    protected var hipB: SkeletonNode? = null; protected var kneeB: SkeletonNode? = null; protected var ankleB: SkeletonNode? = null; protected var heelB: SkeletonNode? = null; protected var toeB: SkeletonNode? = null

    protected val legFBuffer = SkeletonMath.IKResult()
    protected val legBBuffer = SkeletonMath.IKResult()
    protected val armABuffer = SkeletonMath.IKResult()
    protected val armPBuffer = SkeletonMath.IKResult()

    protected val targetA = Vector3(); protected val targetP = Vector3()
    protected val targetF = Vector3(); protected val targetB = Vector3()
    protected val poleA = Vector3(); protected val poleP = Vector3()
    protected val poleF = Vector3(); protected val poleB = Vector3()
    protected val scratchShoulderA = Vector3(); protected val scratchShoulderP = Vector3()

    // A declared `*_FOREARM` support contact is ONE physical chain — shoulder -> elbow -> hand — and
    // the engine realises the elbow from the arm's own IK solve ([bakeIkLimb]), not from a pose-authored
    // position. So the ONLY coherent plant is for the SHOULDER to sit on the sphere of radius
    // `upperArmLength` around the elbow's mat contact: the elbow then lands ON the mat as the chain's
    // own solution instead of being numerically moved onto the plane (the pre-fix authoring produced a
    // 38–45-unit penetration at `ELBOW_A`/`ELBOW_P`).
    //
    // The trunk has to be re-anchored on the plant rather than fixed by a pole: measured pre-fix
    // (`StaticForearmPlankPose` @ p=0.5), the 146-unit arm was asked to reach a hand plant 75.9 units
    // ahead of a shoulder 21.5 units off the mat, so the solve bulged the elbow ~60 units out of the
    // shoulder→hand chord and the authored pole aimed that bulge INTO the mat. Both bulges are ~60
    // units off the chord — no pole choice fixes it. Hence: plant the forearm, prop the shoulder on the
    // pillar, derive the trunk from the two.

    /** The world-X anchor of the family's planted forearm base (the authored footprint). */
    protected val forearmPlantX = 120f

    protected val plantElbow = Vector3()
    protected val plantHand = Vector3()
    protected val plantShoulder = Vector3()
    protected val plantPole = Vector3()
    private val plantScratch = Vector3()

    /**
     * The horizontal run of a flat forearm: the elbow→hand segment is the definition's forearm length and
     * all of it lies in the mat plane, so the run is what remains after the authored inward hand tuck.
     */
    protected fun flatForearmRun(def: SkeletonDefinition, elbowZ: Float, handZ: Float): Float {
        val dz = elbowZ - handZ
        val run2 = def.forearmLength * def.forearmLength - dz * dz
        return if (run2 > 0f) sqrt(run2) else 0f
    }

    /**
     * Plans ONE planted forearm as the physical chain `shoulder -> elbow -> hand`, writing the chain's
     * three world points into [plantElbow] / [plantHand] / [plantShoulder] and the bend-plane pole into
     * [plantPole].
     *
     * The inputs are the plant's own geometry — the elbow's mat contact, the hand's flat contact one
     * forearm length ahead, and the pillar's lean — never a hand-tuned pole. The pole is *derived* as the
     * offset of the elbow's mat contact from the shoulder→hand chord, i.e. the pose states "the elbow
     * seats under the shoulder" and the engine's IK realises exactly that chain.
     *
     * [pillarLean] is the settled frame's authored arm lean: the upper arm is vertical (90° elbow, BPS
     * §6) at 0 and leans back over the elbow as the body settles, which is what keeps a deep hip settle
     * reachable for the planted leg (a vertical pillar cannot move the hips far toward the feet).
     */
    protected fun planPlantedForearm(
        def: SkeletonDefinition,
        elbowX: Float,
        elbowZ: Float,
        handZ: Float,
        pillarLean: Float
    ) {
        plantElbow.set(elbowX, contactY, elbowZ)
        plantHand.set(elbowX + flatForearmRun(def, elbowZ, handZ), contactY, handZ)
        plantShoulder.set(
            plantElbow.x - def.upperArmLength * sin(pillarLean),
            contactY + def.upperArmLength * cos(pillarLean),
            plantElbow.z
        )
        plantScratch.set(plantHand).subtract(plantShoulder)
        val chord = plantScratch.mag()
        if (chord < 1e-4f) {
            plantPole.set(0f, -1f, 0f)
        } else {
            plantScratch.divide(chord)
            val along = (plantElbow.x - plantShoulder.x) * plantScratch.x +
                (plantElbow.y - plantShoulder.y) * plantScratch.y +
                (plantElbow.z - plantShoulder.z) * plantScratch.z
            plantPole.set(
                plantElbow.x - (plantShoulder.x + plantScratch.x * along),
                plantElbow.y - (plantShoulder.y + plantScratch.y * along),
                plantElbow.z - (plantShoulder.z + plantScratch.z * along)
            )
        }
    }

    /**
     * The trunk inclination (`−π/2` is horizontal) connecting a shoulder propped at [shoulderY] to a
     * pelvis at [bodyY]. The trunk is rigid, so the two heights pin it exactly — derived, never a second
     * authored value.
     */
    protected fun proppedTrunkPitch(def: SkeletonDefinition, bodyY: Float, shoulderY: Float): Float {
        val sinA = ((shoulderY - bodyY) / def.torsoLength).coerceIn(-1f, 1f)
        return asin(sinA) - (PI.toFloat() / 2f)
    }

    /**
     * The hip's world X for that rigid trunk: with the shoulder anchored by the plant and the inclination
     * pinned by the two heights, the pelvis's X is a consequence — the trunk cannot slide.
     */
    protected fun proppedHipX(def: SkeletonDefinition, shoulderX: Float, pitch: Float): Float =
        shoulderX - def.torsoLength * cos(pitch + PI.toFloat() / 2f)

    /**
     * The braced hip height: the pelvis sits one torso length down the straight line the trunk forms with
     * the planted foot. `Plank*.md` §3/§11 require one straight line shoulder→hip→ankle at the braced
     * hold, and that line's own geometry fixes the hip's height — the pose authors no plank height of its
     * own. Falls back to a horizontal trunk when the shoulder and foot coalesce.
     */
    protected fun bracedBodyY(
        def: SkeletonDefinition,
        shoulderX: Float, shoulderY: Float,
        footX: Float, footY: Float
    ): Float {
        val run = sqrt((footX - shoulderX) * (footX - shoulderX) + (footY - shoulderY) * (footY - shoulderY))
        if (run < 1e-4f) return shoulderY
        return shoulderY + (footY - shoulderY) * def.torsoLength / run
    }

    protected fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        clavicleA = nodes.clavicleA; scapulaA = nodes.scapulaA
        clavicleP = nodes.clavicleP; scapulaP = nodes.scapulaP
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    /**
     * Breathing / postural-stabilization micro-driver in [0, 1]: a half-sine of progress, exactly **0 at
     * progress 0 and 1**, so the entering/exiting contract (the authored hip height, driven to the braced
     * line, with planted supports settled) is untouched at the endpoints while the mid-hold gets a
     * rib-cage swell. Smooth with zero endpoint velocity, so PING_PONG never snaps.
     */
    protected fun breathingSwell(progress: Float): Float = sin(progress * PI.toFloat())

    /**
     * The family's scapular girdle drive, on the ONE canonical channel
     * ([SkeletonMath.buildScapularRotation]). [a]/[p] are the SIGNED per-blade activation in the shared
     * girdle units, and each member derives its own signs from its own layout (measured, never assumed):
     * the canonical girdle nodes share the chest's frame but their shoulder offsets sit on different local
     * axes in these two layouts, so "toward this blade's planted elbow" is a different sign per side.
     * An unmirrored drive is a twist about the trunk's long axis: measured on the forearm plank's braced
     * frame, one glenoid travels `6.08` u toward the mat while the other travels `6.08` u away from it —
     * the winging the BPS files explicitly forbid.
     *
     * The rig coupling to author around: the scapula is the shoulder's PARENT
     * (`CHEST -> CLAVICLE -> SCAPULA -> SHOULDER`), so rotating it displaces the glenoid — and here the
     * glenoid is the arm chain's IK root while the ELBOW and HAND are the declared, planted contacts. The
     * DOF's displacement is `~94 %` aligned with the shoulder→hand chord, so the planted arm absorbs it as
     * a rigid-chain translation and **the planted elbow sinks or floats by the glenoid's own vertical
     * travel** (`1.52` u per activation unit; `1.55` u/unit on the side plank's support arm). No authored
     * pole can change that, so [GIRDLE_PROTRACTION] is derived from that measurement and the family's own
     * plant contract, not chosen.
     */
    protected fun driveScapula(a: Float, p: Float) {
        SkeletonMath.buildScapularRotation(a, 0f, -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(p, 0f, 1f, scapulaP!!.localRotation)
    }

    /**
     * Flattens the hierarchy, mirrors wrist joints onto the hand joints (the renderer expects WRIST_*).
     */
    protected fun finalizePlankPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * The family's authored scapular protraction at the peak of the stabilization cycle, in shared
         * girdle units (`SCAPULA_RETRECTION_TO_RAD` = `2.005°`, so this is `1.203°` at the peak).
         *
         * **Derived from the plant's own flat-forearm budget, not chosen.** [driveScapula] records the
         * measured rigid-chain coupling (`1.52` u per activation unit on the braced forearm plank, `1.55`
         * on the side plank, worst `1.91` across the rep), so the declared flat-forearm contact — the
         * `1.5` u band between the two ends of one physical forearm — bounds the amplitude at `~0.8`
         * units. Measured at `0.60`: articulation peaks at `1.2033°`, the glenoid travels `0.9660` u
         * toward its planted elbow, and the worst flatness is `1.2223` u (`81.5 %` of the band). The pull
         * family's `4` units is NOT available here: those poses' hands are free, so their girdle travel
         * costs their plant nothing.
         */
        const val GIRDLE_PROTRACTION = 0.60f
    }
}
