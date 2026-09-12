package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class BurpeePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M8 — the rep's plant, on the ONE canonical support channel (`metadata.support`): the
        // hands, which plant where the trunk reaches (the plank/push-up plant). The hand derivation
        // is self-gating — it only orients a hand that is BELOW its elbow
        // (`SkeletonPoseFinalizer` `planted`) — so the stand/jump phases, where the hands are free,
        // are untouched by this declaration.
        //
        // Measured on the merged base (the M7 plant correction landed as PR #240 / `eea705c`):
        // this declaration FIXES the plant's derivative chain — pre-declaration the planted hand's
        // fingertips hung 21.01 units BELOW the floor (`FINGERTIPS_A/P −21.010` at p=0.25/0.75,
        // `KNUCKLES −11.46`, `PALM −5.73`); with it the whole chain is realized in the declared
        // plane (~3.8e-06) while the stand/jump phases stay untouched (the self-gating above).
        //
        // The FEET are deliberately NOT declared: the foot's plant is the remaining one-line
        // follow-up of this finding (its chain is clean on the merged base — measured `ANKLE_F`
        // 15.00–19.10, `HEEL_F` 11.92–15.00, `TOE_F` 15.00–36.67, nothing below the surface — so
        // declaring it is unblocked, but it is a further production change this pass does not make).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_HAND, SupportContact.RIGHT_HAND)
        )
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F))
        kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F))
        ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F))
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F))
        toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B))
        toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    /**
     * One phase of the rep. All positions are world-space targets for the chains; the limb
     * *lengths* are never authored (the solver owns them) — M7's correction is that every target
     * below is derived from the rig's own reachable span, so no chain has to be clamped to a
     * length it cannot make (the previous table authored the ankles inside the knee's minimum
     * reach, which pushed the feet through the floor, and the plank's feet only 65 units behind
     * the hips, which collapsed a plank into a crouch).
     *
     * @param handsPlanted true while the hands rest on their floor plant (they must not slide).
     * @param armAngle the arm pendulum's angle from the downward vertical, radians, + forward
     *   (only read while the hands are free — the planted hand IS the plant point).
     */
    private data class PhaseInfo(
        val pelvisX: Float,
        val pelvisY: Float,
        val torsoAngle: Float,
        val ankleF: Vector3,
        val ankleB: Vector3,
        val handsPlanted: Boolean,
        val armAngle: Float,
        val armPole: Vector3
    )

    /** The body's rigid line, measured from the feet up: where the pelvis sits for a line at [gamma]. */
    private fun linePelvis(ankleX: Float, ankleY: Float, gamma: Float, legSpan: Float) =
        Vector3(ankleX + legSpan * cos(gamma), ankleY + legSpan * sin(gamma), 0f)

    private fun getPhase(p: Float, def: SkeletonDefinition): PhaseInfo {
        val torso = def.torsoLength
        val legSpan = LEG_SPAN * reach(def.thighLength, def.shinLength, def.legIKConstraint)
        val armSpan = ARM_SPAN * reach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        val ankleY = def.foot.ankleHeight
        val standY = ankleY + legSpan                       // tall: hips one leg-span above the feet
        val squatY = armSpan - torso * cos(SQUAT_TRUNK_TILT) // hands-down: shoulder exactly one arm-span up
        val plantX = torso * sin(SQUAT_TRUNK_TILT)          // ... and directly over the hand plant
        val lineLength = torso + legSpan                    // shoulder -> ankle, straight
        // The plank's body line: the shoulder sits one arm-span above the planted hands.
        val plankGamma = asin((armSpan - ankleY) / lineLength)
        // The push-up bottom: the same line pivoted about the planted feet by a shoulder drop.
        val dipGamma = asin((armSpan - DIP_SHOULDER_DROP - ankleY) / lineLength)
        val lineAnkleX = plantX - lineLength * cos(plankGamma)   // the feet's plant, shared by the plank
        val plankPelvis = linePelvis(lineAnkleX, ankleY, plankGamma, legSpan)
        val plankTilt = (PI / 2 - plankGamma).toFloat()
        val jumpPelvisY = standY + JUMP_RISE

        if (p < 0.2f) {
            // stand -> squat with the hands reaching down to their plant (feet stay where they are)
            val t = p / 0.2f
            val st = SkeletonMath.easeInOut(t)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = lerp(standY, squatY, st),
                torsoAngle = lerp(0f, -SQUAT_TRUNK_TILT, st),
                ankleF = Vector3(0f, ankleY, -def.hipWidth),
                ankleB = Vector3(0f, ankleY, def.hipWidth),
                handsPlanted = false,
                armAngle = 0f,
                armPole = Vector3(1f, 0f, 0f)
            )
        } else if (p < 0.4f) {
            // feet shoot back to their plank plant while the hands stay planted
            val t = (p - 0.2f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val liftF = FOOT_LIFT * sin(st * PI.toFloat()).pow(2)
            return PhaseInfo(
                pelvisX = lerp(0f, plankPelvis.x, st),
                pelvisY = lerp(squatY, plankPelvis.y, st),
                torsoAngle = lerp(-SQUAT_TRUNK_TILT, -plankTilt, st),
                ankleF = Vector3(lerp(0f, lineAnkleX, st), ankleY + liftF, -def.hipWidth),
                ankleB = Vector3(lerp(0f, lineAnkleX, st), ankleY + liftF, def.hipWidth),
                handsPlanted = true,
                armAngle = 0f,
                armPole = Vector3(0f, -1f, 0f)
            )
        } else if (p < 0.6f) {
            // the plank itself, and the optional push-up: the rigid line pivots about the planted
            // feet (the hands and the feet never move) while the elbows bend.
            val t = (p - 0.4f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val gamma = plankGamma - (plankGamma - dipGamma) * sin(st * PI.toFloat())
            val pelvis = linePelvis(lineAnkleX, ankleY, gamma, legSpan)
            return PhaseInfo(
                pelvisX = pelvis.x,
                pelvisY = pelvis.y,
                torsoAngle = -(PI / 2 - gamma).toFloat(),
                ankleF = Vector3(lineAnkleX, ankleY, -def.hipWidth),
                ankleB = Vector3(lineAnkleX, ankleY, def.hipWidth),
                handsPlanted = true,
                armAngle = 0f,
                armPole = Vector3(0f, -1f, 0f)
            )
        } else if (p < 0.8f) {
            // feet return under the hips ("move together"); the hands stay planted
            val t = (p - 0.6f) / 0.2f
            val st = SkeletonMath.easeInOut(t)
            val liftF = FOOT_LIFT * sin(st * PI.toFloat()).pow(2)
            return PhaseInfo(
                pelvisX = lerp(plankPelvis.x, 0f, st),
                pelvisY = lerp(plankPelvis.y, squatY, st),
                torsoAngle = lerp(-plankTilt, -SQUAT_TRUNK_TILT, st),
                ankleF = Vector3(lerp(lineAnkleX, 0f, st), ankleY + liftF, -def.hipWidth),
                ankleB = Vector3(lerp(lineAnkleX, 0f, st), ankleY + liftF, def.hipWidth),
                handsPlanted = true,
                armAngle = 0f,
                armPole = Vector3(1f, 0f, 0f)
            )
        } else if (p < 0.9f) {
            // the jump: full triple extension (the whole body rises, the limbs stay extended) with
            // the arms driving overhead through the front
            val t = (p - 0.8f) / 0.1f
            val st = SkeletonMath.easeInOut(t)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = lerp(squatY, jumpPelvisY, st),
                torsoAngle = lerp(-SQUAT_TRUNK_TILT, 0f, st),
                ankleF = Vector3(0f, lerp(ankleY, ankleY + JUMP_RISE, st), -def.hipWidth),
                ankleB = Vector3(0f, lerp(ankleY, ankleY + JUMP_RISE, st), def.hipWidth),
                handsPlanted = false,
                armAngle = lerp(0f, PI.toFloat(), st),
                armPole = Vector3(0f, 0f, 1f)
            )
        } else {
            // the landing: soft knees — the feet land and the hips absorb below the stand, then
            // extend back to it (which is the loop's seam: p = 1 is the stand, p = 0 resumes there)
            val t = (p - 0.9f) / 0.1f
            val st = SkeletonMath.easeInOut(t)
            return PhaseInfo(
                pelvisX = 0f,
                pelvisY = lerp(jumpPelvisY, standY, st) - LAND_ABSORB * sin(st * PI.toFloat()),
                torsoAngle = 0f,
                ankleF = Vector3(0f, lerp(ankleY + JUMP_RISE, ankleY, st), -def.hipWidth),
                ankleB = Vector3(0f, lerp(ankleY + JUMP_RISE, ankleY, st), def.hipWidth),
                handsPlanted = false,
                armAngle = lerp(PI.toFloat(), 0f, st),
                armPole = Vector3(1f, 0f, 0f)
            )
        }
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        val info = getPhase(context.progress, def)

        pelvis!!.localPosition = Vector3(info.pelvisX, info.pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), info.torsoAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // Solve IK for legs
        bakeIkLimb(hipF!!.worldPosition, info.ankleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, info.ankleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // Solve IK for arms — the hand is either the floor plant (planted phases: a fixed contact
        // that must not slide) or a point on the arm's own reachable circle about the shoulder,
        // so the shoulder->hand distance is exactly the authored (in-band) span at every frame.
        val armSpan = ARM_SPAN * reach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        val lateral = HAND_OUTSIDE_SHOULDER
        val armPlane = sqrt(armSpan * armSpan - lateral * lateral)
        val plantX = def.torsoLength * sin(SQUAT_TRUNK_TILT)

        bakeIkLimb(shoulderA!!.worldPosition, handTarget(info, -1, armPlane, plantX, def), def.upperArmLength, def.forearmLength, poleFor(info.armPole, -1), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, handTarget(info, 1, armPlane, plantX, def), def.upperArmLength, def.forearmLength, poleFor(info.armPole, 1), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private fun handTarget(
        info: PhaseInfo, side: Int, armPlane: Float, plantX: Float, def: SkeletonDefinition
    ): Vector3 {
        val z = side * (def.shoulderWidth + HAND_OUTSIDE_SHOULDER)
        if (info.handsPlanted) return Vector3(plantX, 0f, z)
        val shoulder = if (side < 0) shoulderA!!.worldPosition else shoulderP!!.worldPosition
        return Vector3(
            shoulder.x + armPlane * sin(info.armAngle),
            shoulder.y - armPlane * cos(info.armAngle),
            z
        )
    }

    /** The pole mirrors with the body side (the A/P limbs are mirror images of one another). */
    private fun poleFor(pole: Vector3, side: Int) = Vector3(pole.x, pole.y, pole.z * side)

    private fun reach(l1: Float, l2: Float, constraint: IKConstraint): Float =
        (l1 + l2) * constraint.effectiveExtensionRatio

    private companion object {
        /** Authored limb spans as a fraction of the solver's reachable length (inside its 0.98 band). */
        const val LEG_SPAN = 0.99f
        const val ARM_SPAN = 0.99f

        /** Hands-down trunk inclination (§3: "hips hinge and knees flex, hands reach the floor"). */
        val SQUAT_TRUNK_TILT = 1.0472f   // 60°

        /** How far the shoulder drops at the push-up bottom (the dip the original pose authored). */
        const val DIP_SHOULDER_DROP = 42f

        /** The jump: the whole body rises this far with its limbs fully extended. */
        const val JUMP_RISE = 45f

        /** Knee-bend absorb on landing, before extending back to the stand. */
        const val LAND_ABSORB = 40f

        /** How far the feet arc off the floor on the kick-back / return transitions. */
        const val FOOT_LIFT = 28f

        /** The hands grip this far outside the shoulders (the original authoring's ±(width+5)). */
        const val HAND_OUTSIDE_SHOULDER = 5f
    }
}
