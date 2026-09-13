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
        // M8 — the planted feet, on the ONE canonical support channel (`metadata.support`). The
        // WALL contact (the forearms the exercise slides up the wall) is deliberately NOT declared
        // here: the wall's contact plane is M15's finding, not this pass's.
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
    )

    /**
     * M15 — THE WALL IS A PLANE OF CONSTANT X, AND IT IS THE PLANE THIS POSE AUTHORS EVERY WALL
     * CONTACT IN.
     *
     * `WallProp.width` is the X extent of the slab, so the prop's contact surface is one fixed X —
     * its +X face ([WALL_FACE_X]). The pose therefore authors its ARM CHAIN in exactly that plane
     * (see `handX` in [build]) so the published forearm lies in it, which is what the exercise's own
     * contract requires: BPS §6/§8 "the elbows and wrists maintain wall contact throughout … elbows
     * and the backs of the wrists/hands contact the wall", and `Movement Ownership Matrix` §Wall Slide
     * ("Followers: … Elbow, Wrist/Hand (**on wall**)").
     *
     * This placement keeps the wall's slab where H1 put it (`x ∈ [-19, -11]`, face `-11`), i.e. `6` u
     * behind the plane the athlete's spine is authored in (`PELVIS/CHEST/HEAD_POS.x = -5`). Pulling the
     * face forward onto the spine plane is **refuted by measurement** (see the M15 record in
     * `docs/STABILIZATION_AUDIT.md`): the athlete's ankles sit at that same X, and the finalizer's
     * `supportPlaneNormalFor` resolves a declared contact's surface from the centroid of its canonical
     * joints, so a wall footprint covering the spine plane contains the declared foot contact centroid
     * (on the cold frame the heel/toe are coincident with the ankle, putting it exactly on the face)
     * and the feet are re-oriented `90°` onto the wall's face instead of the floor — measured
     * `HEEL_F/TOE_F` rotating off the forward axis. The standoff is therefore a real constraint of the
     * current engine, recorded as the H1 complement.
     *
     * What M15 does own is the wall's **extent**: the wall must be the surface the arms actually slide
     * ON. The pre-M15 prop was `160` wide × `180` tall and its top edge (`y = 180`) was below the
     * athlete's own pelvis (`y = 235`), while the declared wall contacts travel at `y = 332 … 428`
     * (measured) — no wall contact could reach it. The extent below is sized from the measured contact
     * envelope of the corrected chain (fingertip apex `y ≈ 428`, abducted-elbow apex `|z| ≈ 126`) with
     * the athlete's standing height (`HEAD_POS.y = 391`) as the lower bound.
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
    /** M15 — scratch for the derived wall-plane elbow pole (allocation-free per frame). */
    private val armPoleBuffer = Vector3()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()` — the M11 shape the migrated
        // families publish). The factory's added nodes are pass-throughs between the links this pose
        // already authored: LUMBAR is coincident with the PELVIS (identity rotation) and
        // CLAVICLE_*/SCAPULA_* are coincident with the CHEST, so every transform authored below
        // resolves exactly as before. What changes is that the five canonical joints carry their
        // authored transforms instead of publishing at the world origin.
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
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)

        // Wall slides: Standing against the wall
        // B3 — STANDING posture: the solver owns the coarse pelvis height (seed == standH).
        // The -5f x is a shape decision (lean toward the wall) and stays authored.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)

        // standH is no longer read: every target below is authored in the chain root's own frame
        // (M8), so the pose does not need to name the solver-owned root height.
        pelvis!!.localPosition = Vector3(-5f, 0f, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), 0f)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute Spine transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 1. Static Standing Feet (no foot sliding or penetration)
        // M8 — targets are authored in the frame this pose OWNS (the chain root's own world
        // position). The root height is solver-owned (B3: the STANDING intent pins the pelvis after
        // this build), so a floor-anchored Y sits a whole standing root height above the hip and
        // the solver relocates the effector along that upward direction. `SkeletonMath.maxReach` is
        // the chain's own reachable length, so the authored target is realizable as declared.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val targetAnkleF = Vector3(-5f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * 1.2f)
        val targetAnkleB = Vector3(-5f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // 2. Arms (sliding along the wall)
        // M15 — the hands are authored ON the wall's contact face, not in the body's own plane: the
        // contract is that the elbows and the wrists/hands contact the wall (BPS §6/§8), so the arm
        // chain is authored in the wall's face plane and the published forearm lies in it exactly.
        val handX = WALL_FACE_X
        // M8 — same frame rule as the feet: the slide's travel (−10 → +60 from the shoulder) is
        // authored around the shoulder the pose owns at build time, not the floor-anchored height.
        val handY = lerp(shoulderA!!.worldPosition.y - 10f, shoulderA!!.worldPosition.y + 60f, context.progress)

        val handZ_A = lerp(-def.shoulderWidth - 15f, -def.shoulderWidth - 25f, context.progress)
        val handZ_P = lerp(def.shoulderWidth + 15f, def.shoulderWidth + 25f, context.progress)

        val targetHandA = Vector3(handX, handY, handZ_A)
        val targetHandP = Vector3(handX, handY, handZ_P)

        // M15 — the authored "W" is tighter than the arm chain can fold (19.0 u from the shoulder
        // against the chain's own minimum reachable 40.1344 u at the 30° flexion stop), so the R2
        // projection below would otherwise relocate it — and because the shoulder is 6 u in front of
        // the wall's face, that relocation carries an out-of-plane component and the forearm leaves
        // the wall exactly where the rep starts. Extending the authored direction to the chain's own
        // minimum reach WITHIN the wall's plane keeps the declared target realizable as declared.
        extendToInPlaneReach(shoulderA!!.worldPosition, targetHandA, def)
        extendToInPlaneReach(shoulderP!!.worldPosition, targetHandP, def)

        // M8 — the same reach band as everywhere else: the authored contact height is reserved
        // against the arm chain's minimum reach (`SkeletonMath.minReach` at the 30° stop), so the
        // declared target is projected onto the reachable band first (R2 reach-target helper) and
        // the realized arm is exactly what the pose declared. M15 — with the in-plane extension above
        // this helper now copies the target unchanged (`docs` R2: "a target inside the band is copied
        // unchanged"), so no authored wall contact is ever relocated off the wall's face plane.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // M15 — the elbow's bend plane IS the wall's plane. The shoulder sits 6 u in front of the
        // wall's face, so the shoulder→hand chord itself leaves the plane; the elbow must therefore be
        // placed on the chord's perpendicular circle AT the plane ([wallPlanePole] solves exactly
        // that). Pre-fix the pole `(-1, 0, ∓1)` — commented as "points backward and outward to keep
        // contact with the wall plane" — put the elbow `40.9 … 51.6` u BEHIND the face instead.
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, wallPlanePole(shoulderA!!.worldPosition, targetHandA, def, armPoleBuffer), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, wallPlanePole(shoulderP!!.worldPosition, targetHandP, def, armPoleBuffer), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * M15 — EXTENDS AN AUTHORED WALL-PLANE TARGET TO THE ARM CHAIN'S OWN MINIMUM REACH, IN THE WALL'S
     * PLANE.
     *
     * The authored "W" (`handY = shoulderY − 10`, `handZ = shoulderWidth + 15` outboard) sits `19.0` u
     * from the shoulder, while the arm chain's own `minimumFlexionAngle` stop puts the chain's minimum
     * reachable shoulder→hand distance at `SkeletonMath.minReach(80, 66, armIKConstraint) = 40.1344` u:
     * the authored target is not realizable by an `80 + 66` arm, and `SkeletonMath.clampTargetToReach`
     * answers by projecting it onto the reachable annulus **along its own ray**. Because the shoulder
     * sits `6` u in front of the wall's face, that ray carries an out-of-plane component, so the
     * projection would place the W hand up to `6.9` u BEHIND the wall — the forearm would leave the
     * wall exactly where the rep starts (measured, `p = 0 … 0.5`).
     *
     * This helper keeps the authored DIRECTION and moves the target out to the same band the R2 helper
     * uses (`minReach · (1 + margin)`), but only along the in-plane components, so the target stays on
     * the wall's face plane (`x` untouched) and is realizable as declared: `clampTargetToReach` then
     * copies it verbatim, the solver records no relocation, and the forearm stays flat on the wall for
     * the whole rep. It does NOT re-tune the slide — the authored travel (`−10 → +60` from the
     * shoulder) and the authored abduction (`15 → 25` outboard) are unchanged; the arm simply reaches
     * the W at its own tightest fold, which is the closest a real arm can get to the authored W.
     *
     * Pose-side only: no engine file, no second solver path — the rule is expressed with the engine's
     * own `SkeletonMath.minReach` and the R2 helper's own margin.
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
     * M15 — THE POLE THAT PUTS THE ELBOW ON THE WALL'S CONTACT PLANE.
     *
     * `SkeletonMath.solveIK` places the elbow on the circle of radius `h = sqrt(L1² − a²)` around the
     * chord's foot (`a` = the foot's distance from the shoulder along the shoulder→hand chord): the
     * pole selects only the DIRECTION of that perpendicular offset. "Forearms flat on the wall" means
     * the elbow and the wrist share one X — the wall's face ([WALL_FACE_X]) — so the perpendicular
     * offset must carry exactly the X that closes the gap between the chord's foot and the plane:
     * `n.x = (WALL_FACE_X − chordFoot.x) / h`. This builds that `n` from the chord's own perpendicular
     * basis (`m = x̂ − u.x·u`, whose X component is `1 − u.x²`; and `p = u × x̂`, X-free), choosing the
     * of the two solutions whose perpendicular offset hangs BELOW the chord: the elbow the exercise
     * puts on the wall trails the hands up it (the "W" the BPS §1/§3 describes, wrists above elbows).
     *
     * Pose-side only — the same shape as the B-7 planted-forearm pole derivation ("derive the pole
     * from the chain's own statement of where the elbow bends"): no engine file, no second solver, no
     * frame conversion. Allocation-free (writes [out]).
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
         * The wall's contact surface — the +X face of the [WallProp], and (M15) the one plane this
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
        /** The R2 reach-target helper's own margin (`SkeletonMath.clampTargetToReach`), reused so the
         *  in-plane extension lands exactly inside the band that helper treats as reachable. */
        const val REACH_MARGIN = 0.02f
    }
}
