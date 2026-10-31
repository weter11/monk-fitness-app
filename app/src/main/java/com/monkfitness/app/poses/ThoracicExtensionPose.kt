package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Thoracic Extension — tall kneeling thoracic mobility: thighs vertical, shins flat on the floor, pelvis
 * neutral, the arch originating in the thoracic spine with the hands behind the head.
 *
 * The arch is authored on the two-segment spine curve, not on the chest's own `localRotation`:
 * driving the chest alone rotates its children in place and never translates the chest.
 */
class ThoracicExtensionPose : BaseThoracicPose() {

    // Head follows the extension (looks up and slightly back) — constant, rotates with thorax via FK.
    private val headDir = Vector3(-0.12f, 1.0f, 0f).normalize()

    override val metadata = PoseMetadata(
        camera = thoracicCamera(0.26f, 1.35f),
        durationSeconds = 3.2f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = thoracicGround
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val progress = context.progress

        // Tall kneeling: pelvis sits directly above the knees, neutral (no tilt, no shift).
        spinePitch = 0f
        val kneeFloorX = 0f
        val kneeFloorY = 15f
        val pelvisX = kneeFloorX
        val pelvisY = kneeFloorY + def.thighLength

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, 0f)
        declareJointIntent(Joint.PELVIS, JointRotation(axisZ, 0f))

        // Thoracic extension about the lateral (Z) axis. The arch originates BELOW the chest (the
        // thoracolumbar junction), so the chest node tips up and BACK (-X) and carries the
        // neck/head/shoulders with it — that is what makes the rib cage open. Driving the chest's own
        // localRotation alone would rotate the children in place but never translate the chest
        // (the previous defect).
        val extAngle = lerp(0f, 0.5f, progress)
        lumbar!!.localPosition.set(0f, 0f, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // Carrier-backed two-segment spine curve (lumbar + thoracic about the lateral Z axis):
        // `buildSpineCurve` writes the nodes for build-time FK and records the LUMBAR/CHEST joint
        // intents (mixed mode, byte-identical to the bare `localRotation.set`).
        buildSpineCurve(lumbar!!, chest!!, extAngle, extAngle * 0.4f, axisZ)

        buildGaze(neck!!, head!!, def.neckLength, headDir)
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs: knees and shins FIXED on the floor (no floating). Thighs vertical, shins flat back.
        targetF.set(pelvisX, kneeFloorY, -def.hipWidth)
        targetB.set(pelvisX, kneeFloorY, def.hipWidth)
        val thighVecF = Vector3(targetF.x - pelvis!!.worldPosition.x, targetF.y - pelvis!!.worldPosition.y, 0f)
        val thighVecB = Vector3(targetB.x - pelvis!!.worldPosition.x, targetB.y - pelvis!!.worldPosition.y, 0f)
        val shinVec = Vector3(-def.shinLength, 0f, 0f)
        kneeF!!.localPosition.set(thighVecF)
        kneeB!!.localPosition.set(thighVecB)
        ankleF!!.localPosition.set(shinVec)
        ankleB!!.localPosition.set(shinVec)

        // Feet flat on the floor, toes pointing back (no plantar flexion, no sky-pointing).
        // neutral ankle articulation recorded as the §1.3 intent (identity flexion).
        buildAnkleArticulation(Extremity.FOOT_F, 0f, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, 0f, 0f, ankleB!!)
        // The engine now derives heel/toe from the (vertical) shank + neutral ankle.

        // Arms: hands behind the head, elbows flared outward/up to open the chest.
        // The head base is PROJECTED from geometry the pose owns, never read from the
        // engine-owned neck node: `resolveHeadTarget` is the sole writer of the neck's offsets, so a
        // build that reads `neck.worldPosition` consumes the PREVIOUS frame's write (measured: cold-frame
        // declared-target delta 17.87 u, published ELBOW_A/HAND_A delta 29.93/17.91). The same point is
        // expressible from authored intent: the declared gaze × `def.neckLength`, rotated to world by the
        // declared chest frame.
        reachLocal.set(
            headDir.x * def.neckLength,
            headDir.y * def.neckLength,
            headDir.z * def.neckLength
        )
        chestLocalToWorld(reachLocal, reachWorld)

        targetP.set(reachWorld.x - 12f, reachWorld.y + 6f, def.shoulderWidth * 0.55f)
        poleP.set(0f, 0.6f, 2f)
        bakeThoracicArm(shoulderP!!.worldPosition, targetP, def, poleP, elbowP!!, handP!!, armPBuffer)

        targetA.set(reachWorld.x - 12f, reachWorld.y + 6f, -def.shoulderWidth * 0.55f)
        poleA.set(0f, 0.6f, -2f)
        bakeThoracicArm(shoulderA!!.worldPosition, targetA, def, poleA, elbowA!!, handA!!, armABuffer)

        applyThoracicHands()
        return finalizeThoracicPose()
    }

    /**
     * The hands-behind-the-head clasp, projected onto the arm chain's own annulus.
     *
     * The composed target (head base offset `(−12, +6, ±0.55 · shoulderWidth)`) sits `34.6182 …
     * 37.3581` u from its own shoulder — INSIDE the chain's `minReach = 40.1344` at every sampled
     * phase, i.e. it asks for a `25.17° … 27.60°` interior elbow against the `30°` stop. Verdict:
     * unintended authoring error, not an intentional ROM limit (the same class as
     * `ProneCobraStretchPose`'s start): the authored RAY is preserved, only the radius lands on the
     * annulus. Residual: the hands still publish at the fold stop, `5.5` u short of the authored clasp at
     * p = 0, which is a pose-design decision rather than a reach-band one.
     */
    override fun projectArmTargetToReach(
        def: SkeletonDefinition,
        shoulderWorld: Vector3,
        target: Vector3
    ) {
        SkeletonMath.clampTargetToReach(
            shoulderWorld, target, def.upperArmLength, def.forearmLength, def.armIKConstraint,
            target, REACH_MARGIN
        )
    }
}
