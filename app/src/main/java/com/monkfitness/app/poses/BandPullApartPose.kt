package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Band Pull-Aparts (`band_pull_aparts_standard`) — the standing banded HORIZONTAL PULL, the
 * `posture` family's third member. It is the standing sibling of the two bar-supported members of
 * this batch: the same pull (scapular retraction driving an elbow-extended arm), authored on the
 * standing channel instead of a bar.
 *
 * ## What the exercise is (the catalog's own statement, `strings.xml`)
 *
 *  * `ex_band_pull_aparts_desc` — *"reinforces scapular retraction and shoulder alignment"*: the
 *    exercise's stated PURPOSE is the girdle, so the pose drives the scapulae rather than the arms.
 *  * `steps` — *"Hold a light resistance band at shoulder height with straight arms"* (§1), *"Pull the
 *    band apart by moving the hands out to the sides"* (§2), *"Finish when the band reaches the chest
 *    and the shoulder blades squeeze together"* (§3), *"Return to the start under control"* (§4).
 *    So: the hands travel OUTWARD on straight arms at shoulder height, the end of the rep has the
 *    band at the chest and the blades retracted, and the rep returns.
 *  * `tech` — *"Keep the ribs stacked over the pelvis and avoid arching the back… Think about moving
 *    from the upper back, not the wrists."*
 *  * `mistakes` — *"Bent wrists, shrugged shoulders, and letting the lower back overextend to finish
 *    the rep."*
 *
 * ## The authored movement, one statement per copy line
 *
 *  * **"straight arms" (§1)**: the hands ride an arc of constant radius [ARM_RADIUS] about each
 *    shoulder — `0.96 ×` the arm chain's own `maxReach` — so the elbow stays at the chain's
 *    near-full extension for the WHOLE sweep. The sweep is a pure shoulder-rotation of an extended
 *    arm, which is what a pull-apart is.
 *  * **"moving the hands out to the sides" (§2)**: the sweep runs from [START_SWEEP] off the forward
 *    direction to [END_SWEEP] (a quarter turn of a straight arm), in the horizontal plane through the
 *    shoulders, so the lateral travel and the fore/aft travel are both large and opposite.
 *  * **"the band reaches the chest … blades squeeze together" (§3)**: at the end of the sweep the
 *    hands stand just in front of the shoulder line (so the band spans across the chest's face) and
 *    the scapulae are retracted by [TOP_RETRACTION] activation units — a real
 *    [SkeletonMath.buildScapularRotation], measured as the shoulder joints moving posteriorly.
 *  * **"ribs stacked over the pelvis … avoid arching" (tech)**: the trunk is authored straight —
 *    pelvis tilt `0`, thoracic `0` — and the test asserts the chest stays stacked over the pelvis.
 *  * **"shrugged shoulders" (a mistake)**: the girdle drive is depression-only (never elevation), and
 *    the hands are placed at the shoulder's own height, so the test can assert the shoulders never
 *    rise and the hands never float above them.
 *  * **"Bent wrists" (a mistake)**: the wrists are left to the engine's own derivation (no wrist
 *    articulation is authored), so the hand follows the forearm — no authored bend can exist.
 *
 * ## Gaps recorded (not invented)
 *
 *  * **The band itself is not modeled.** The rig's environment vocabulary has no band primitive
 *    (`EnvironmentProp` = box/step/bench/wall; `EnvironmentAnchorType` = floor/bar/wall/bench/
 *    parallel bars/rings), and the family's existing member declares none either
 *    (`FacePullPose`: ground only). The band is therefore implied by the exercise's name and by the
 *    hand travel, not authored as a prop — the same state `face_pull_banded` ships in. Recorded
 *    rather than invented (a `BandProp` is an engine/vocabulary change, outside this phase).
 *  * **The band's tension/stiffness** is not modeled anywhere in the rig; the rep's resistance is
 *    implied by the sweep, not simulated.
 *  * **The hand width at the start** is not stated by the copy (§1 says "straight arms", not how wide
 *    they start); [START_SWEEP] is authored at the narrowest sweep the straight-arm arc admits, and
 *    the exact start angle is recorded as an authored constant rather than presented as specification.
 */
class BandPullApartPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var scapulaA: SkeletonNode? = null; private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var scapulaP: SkeletonNode? = null; private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    override val metadata = PoseMetadata(
        // The standing family's own frame (`FacePullPose`: the pull-apart's standing sibling).
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M8 — the planted feet, on the ONE canonical support channel (`metadata.support`).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        ),
        exerciseFamily = "posture",
        motionType = "Horizontal Pull",
        bodyOrientation = "Upright"
    )

    private fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        scapulaA = nodes.scapulaA; shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        scapulaP = nodes.scapulaP; shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()

        // B3 — STANDING posture: the solver owns the coarse pelvis height (seed == standH), so the
        // pose authors everything relative to the chain roots it owns (M8).
        declarePosture(jointsBuffer, PostureIntent.Kind.STANDING)
        pelvis!!.localPosition.set(0f, 0f, 0f)
        // tech: "Keep the ribs stacked over the pelvis and avoid arching the back" — a straight trunk:
        // no pelvic tilt and no thoracic extension (one spine-intent call, the single authorized path).
        buildSpineCurve(pelvis!!, chest!!, 0f, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)

        // desc: "reinforces scapular retraction" / steps §3: "the shoulder blades squeeze together".
        // Depression only — `mistakes`' "shrugged shoulders" is an elevation the pose may never author.
        val progress = context.progress
        SkeletonMath.buildScapularRotation(SkeletonMath.lerp(0f, TOP_RETRACTION, progress), SkeletonMath.lerp(0f, TOP_DEPRESSION, progress), -1f, scapulaA!!.localRotation)
        SkeletonMath.buildScapularRotation(SkeletonMath.lerp(0f, TOP_RETRACTION, progress), SkeletonMath.lerp(0f, TOP_DEPRESSION, progress), 1f, scapulaP!!.localRotation)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        // --- 1. Planted feet (M8's frame rule: targets authored around the chain root the pose owns).
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val legTargetF = Vector3(0f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * STANDING_STANCE)
        val legTargetB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * STANDING_STANCE)
        bakeIkLimb(hipF!!.worldPosition, legTargetF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, legTargetB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer)

        // --- 2. The sweep: straight arms carrying the hands out to the sides --------------------
        val sweep = SkeletonMath.lerp(START_SWEEP, END_SWEEP, progress)
        val forward = cos(sweep) * ARM_RADIUS
        val lateral = sin(sweep) * ARM_RADIUS
        val shoulderAWorld = shoulderA!!.worldPosition
        val shoulderPWorld = shoulderP!!.worldPosition
        val targetA = Vector3(shoulderAWorld.x + forward, shoulderAWorld.y, shoulderAWorld.z - lateral)
        val targetP = Vector3(shoulderPWorld.x + forward, shoulderPWorld.y, shoulderPWorld.z + lateral)
        // Reachable by construction (the targets sit at [ARM_RADIUS] from the very root they are
        // solved from); projected anyway, so a future edit of the radius is projected rather than
        // silently solver-clamped (the M8 rule).
        SkeletonMath.clampTargetToReach(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetA)
        SkeletonMath.clampTargetToReach(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetP)
        // The elbows hang below the extended arm, opening a touch outboard (no authored wrist, so
        // `mistakes`' "bent wrists" cannot exist — the hand follows the forearm).
        bakeIkLimb(shoulderAWorld, targetA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -0.25f), def.armIKConstraint, shoulderA!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderPWorld, targetP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 0.25f), def.armIKConstraint, shoulderP!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    companion object {
        /**
         * The straight-arm radius: `0.96 ×` the arm chain's own `maxReach` (`143.080`), i.e. the
         * chain's near-full extension — "straight arms" in this rig's terms (the chain's `0.98`
         * extension cap means even a locked-out chain measures `~157°` interior, measured).
         */
        const val ARM_RADIUS = 137.6f

        /** The narrowest straight-arm sweep: the hands in front of the shoulders, band at the sternum. */
        const val START_SWEEP = 0.2618f

        /** A quarter turn of the extended arm: the hands out at the sides, the band across the chest. */
        const val END_SWEEP = 1.2566f

        /** desc's "scapular retraction": the shared girdle activation units (as the vertical-pull family). */
        const val TOP_RETRACTION = 4f
        const val TOP_DEPRESSION = 1f

        /** The standing stance the M8 correction established for this family (`1.2 × hipWidth`). */
        const val STANDING_STANCE = 1.2f
    }
}
