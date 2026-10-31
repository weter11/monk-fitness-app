package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class WallSlidesPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(wallProp())
        ),
        // The planted feet, on the ONE canonical support channel (`metadata.support`). The
        // WALL contact (the forearms the exercise slides up the wall) is deliberately NOT declared
        // here: the wall's contact plane is the wall-plane finding, not this pass's.
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    /**
     * The wall is a plane of constant X (`WallProp.width` is an X extent), so its +X face
     * ([WALL_FACE_X]) is where the whole arm chain is authored and the published forearm lies in it
     * (BPS §6/§8: elbows and wrists maintain wall contact). The face stays `6` u behind the spine
     * plane: pulling it forward onto the spine plane puts the declared foot-contact centroid on the
     * face, and the finalizer then re-orients the feet `90°` onto the wall (measured `HEEL_F/TOE_F`
     * rotating off the forward axis). The extent is sized from the measured contact envelope
     * (fingertip apex `y ≈ 428`, abducted-elbow apex `|z| ≈ 126`).
     */
    private fun wallProp() = WallProp(
        center = Vector3(WALL_FACE_X - WALL_THICKNESS * 0.5f, WALL_HEIGHT * 0.5f, 0f),
        width = WALL_THICKNESS,
        height = WALL_HEIGHT,
        depth = WALL_DEPTH
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
    /** Scratch for the derived wall-plane elbow pole (allocation-free per frame). */
    private val armPoleBuffer = Vector3()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()`): LUMBAR is coincident with
        // PELVIS and CLAVICLE_*/SCAPULA_* with CHEST, so every transform authored below resolves as before.
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)

        // STANDING posture: the solver owns the coarse pelvis height (seed == standH).
        // The -5f x is a shape decision (lean toward the wall) and stays authored.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)

        // standH is no longer read: every target below is authored in the chain root's own frame
        // , so the pose does not need to name the solver-owned root height.
        pelvis!!.localPosition = Vector3(-5f, 0f, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), 0f)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // Targets are authored in the frame this pose OWNS (the chain root's own world
        // position). The root height is solver-owned (the STANDING intent pins the pelvis after
        // this build), so a floor-anchored Y sits a whole standing root height above the hip and
        // the solver relocates the effector along that upward direction. `SkeletonMath.maxReach` is
        // the chain's own reachable length, so the authored target is realizable as declared.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val targetAnkleF = Vector3(-5f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * 1.2f)
        val targetAnkleB = Vector3(-5f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // The hands are authored ON the wall's contact face, not in the body's own plane: the
        // contract is that the elbows and the wrists/hands contact the wall (BPS §6/§8), so the arm
        // chain is authored in the wall's face plane and the published forearm lies in it exactly.
        val handX = WALL_FACE_X
        // Same frame rule as the feet: the slide's travel (−10 → +60 from the shoulder) is
        // authored around the shoulder the pose owns at build time, not the floor-anchored height.
        val handY = lerp(shoulderA!!.worldPosition.y - 10f, shoulderA!!.worldPosition.y + 60f, context.progress)

        val handZ_A = lerp(-def.shoulderWidth - 15f, -def.shoulderWidth - 25f, context.progress)
        val handZ_P = lerp(def.shoulderWidth + 15f, def.shoulderWidth + 25f, context.progress)

        val targetHandA = Vector3(handX, handY, handZ_A)
        val targetHandP = Vector3(handX, handY, handZ_P)

        // The authored "W" is tighter than the arm chain can fold (19.0 u from the shoulder
        // against the chain's own minimum reachable 40.1344 u at the 30° flexion stop), so the reach
        // projection below would otherwise relocate it — and because the shoulder is 6 u in front of
        // the wall's face, that relocation carries an out-of-plane component and the forearm leaves
        // the wall exactly where the rep starts. Extending the authored direction to the chain's own
        // minimum reach WITHIN the wall's plane keeps the declared target realizable as declared.
        extendToInPlaneReach(shoulderA!!.worldPosition, targetHandA, def)
        extendToInPlaneReach(shoulderP!!.worldPosition, targetHandP, def)

        // The declared target is projected onto the arm chain's reachable band first (the reach-target
        // helper), so the realized arm is exactly what the pose declared. With the in-plane
        // extension above this helper now copies the target unchanged.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // The elbow's bend plane IS the wall's plane. The shoulder sits 6 u in front of the
        // wall's face, so the shoulder→hand chord itself leaves the plane; the elbow must therefore be
        // placed on the chord's perpendicular circle AT the plane ([wallPlanePole] solves exactly
        // that). Pre-fix the pole `(-1, 0, ∓1)` — commented as "points backward and outward to keep
        // contact with the wall plane" — put the elbow `40.9 … 51.6` u BEHIND the face instead.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, wallPlanePole(shoulderA!!.worldPosition, targetHandA, def, armPoleBuffer), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, wallPlanePole(shoulderP!!.worldPosition, targetHandP, def, armPoleBuffer), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * Extends an authored wall-plane target to the arm chain's own minimum reach, in the wall's
     * plane. The authored "W" sits `19.0` u from the shoulder against `minReach = 40.1344` u, and
     * because the shoulder is 6 u in front of the face the naive annular projection would put the hand
     * up to `6.9` u BEHIND the wall — so only the in-plane components are extended (`x` untouched).
     */
    private fun extendToInPlaneReach(root: Vector3, target: Vector3, def: SkeletonDefinition): Vector3 {
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val lo = SkeletonMath.minReach(def.upperArmLength, def.forearmLength, def.armIKConstraint) * (1f + REACH_MARGIN)
        if (distance >= lo) return target
        val inPlane = sqrt(dy * dy + dz * dz)
        if (inPlane < 1e-4f) return target
        val scale = sqrt(max(lo * lo - dx * dx, 0f)) / inPlane
        return target.set(target.x, root.y + dy * scale, root.z + dz * scale)
    }

    /**
     * The pole that puts the elbow on the wall's contact plane. `solveIK` places the elbow on a
     * circle around the chord's foot; the pole selects only the perpendicular DIRECTION, so that
     * offset must carry exactly `n.x = (WALL_FACE_X − chordFoot.x) / h`. Of the two solutions the one
     * hanging BELOW the chord is chosen: the elbow trails the hands up the wall (wrists above elbows).
     */
    private fun wallPlanePole(root: Vector3, hand: Vector3, def: SkeletonDefinition, out: Vector3): Vector3 {
        val dx = hand.x - root.x
        val dy = hand.y - root.y
        val dz = hand.z - root.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-4f) return out.set(0f, -1f, 0f)
        val ux = dx / d; val uy = dy / d; val uz = dz / d
        val l1 = def.upperArmLength
        val a = (d * d + l1 * l1 - def.forearmLength * def.forearmLength) / (2f * d)
        val h = sqrt(max(l1 * l1 - a * a, 0f))
        val footX = root.x + ux * a
        // The X the perpendicular offset must supply, clamped when the chain's circle cannot reach the
        // plane at all (then the elbow lands as close to the wall as the chain allows).
        val wanted = if (h < 1e-4f) 0f else ((WALL_FACE_X - footX) / h).coerceIn(-1f, 1f)

        // Orthonormal basis of the plane perpendicular to the chord.
        val mx = 1f - ux * ux; val my = -ux * uy; val mz = -ux * uz
        val mMag = sqrt(mx * mx + my * my + mz * mz)
        if (mMag < 1e-4f) return out.set(0f, -1f, 0f)
        val cos = (wanted / mMag).coerceIn(-1f, 1f)
        val sin = sqrt(max(1f - cos * cos, 0f))

        // p = u × x̂ = (0, uz, −uy); +p and −p are the two sides of the chord.
        var ny = cos * (my / mMag) + sin * (uz / mMag)
        var nz = cos * (mz / mMag) + sin * (-uy / mMag)
        if (ny > 0f) {
            ny = cos * (my / mMag) - sin * (uz / mMag)
            nz = cos * (mz / mMag) - sin * (-uy / mMag)
        }
        return out.set(cos * (mx / mMag), ny, nz)
    }

    private companion object {
        /**
         * The wall's contact surface — the +X face of the [WallProp], and the one plane this
         * pose authors the whole arm chain in (`handX`), so the published elbow and wrist/hand sit on
         * the wall's own face by construction. Kept at H1's `-11` (6 u behind the spine plane, see
         * [wallProp]): moving the face onto the spine plane makes the finalizer resolve the DECLARED
         * foot contacts onto the wall (measured).
         */
        const val WALL_FACE_X = -11f
        const val WALL_THICKNESS = 8f
        /** Tall enough to be the surface the arms slide ON: the athlete's standing height plus the
         *  overhead reach (measured published apex `y ≈ 428`). */
        const val WALL_HEIGHT = 500f
        /** Wide enough to receive the abducted arms that the W and the slide put on it (measured
         *  elbow apex `|z| ≈ 126`). */
        const val WALL_DEPTH = 300f
        /** The reach-target helper's own margin (`SkeletonMath.clampTargetToReach`), reused so the
         *  in-plane extension lands exactly inside the band that helper treats as reachable. */
        const val REACH_MARGIN = 0.02f
    }
}
