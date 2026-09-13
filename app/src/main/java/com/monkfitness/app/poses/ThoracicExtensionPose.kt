package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * Thoracic Extension — full rewrite for natural kneeling thoracic mobility.
 *
 * Tall kneeling: thighs vertical, shins FLAT on the floor, ankles and toes planted,
 * pelvis neutral (no forward shift, no lumbar contribution). The extension originates
 * in the thoracic spine: the chest node arches up and back about the lateral (Z) axis,
 * the rib cage opens upward, the head follows the extension, and the hands rest behind
 * the head with the elbows flared outward to open the chest. The previous implementation
 * floated the knees/shins/feet off the floor and drove the lumbar into extension; both
 * are corrected here.
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
        // B3 — every production pose declares its posture intent. This pose authors a
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

        // Thoracic extension about the lateral (Z) axis. The arch originates in the spine BELOW
        // the chest (the thoracolumbar junction), so the chest node itself tips up and BACK (-X)
        // and carries the neck/head/shoulders with it — that is what makes the rib cage open and
        // the gaze travel backward. Driving the chest's own localRotation alone would rotate the
        // children in place but never translate the chest, so the extension would be invisible at
        // the CHEST/HEAD joints (the previous defect).
        val extAngle = lerp(0f, 0.5f, progress)
        lumbar!!.localPosition.set(0f, 0f, 0f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // B4a — carrier-backed two-segment spine curve (lumbar + thoracic about the lateral Z axis).
        // The arch originates below the chest (thoracolumbar junction) so the chest tips up and BACK
        // carrying neck/head/shoulders. buildSpineCurve writes the nodes for build-time FK AND records
        // the LUMBAR/CHEST joint intents (mixed mode, byte-identical to the bare localRotation.set).
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
        // Branch C: neutral ankle articulation recorded as the §1.3 intent (identity flexion).
        buildAnkleArticulation(Extremity.FOOT_F, 0f, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, 0f, 0f, ankleB!!)
        // W1: engine now derives heel/toe from the (vertical) shank + neutral ankle.

        // Arms: hands behind the head, elbows flared outward/up to open the chest.
        // B-8b — the head base is PROJECTED from geometry the pose owns, never read from the
        // engine-owned neck node. `SkeletonPoseFinalizer.resolveHeadTarget` (Phase 7) is the sole
        // writer of the neck's local offsets, so a build that reads `neck.worldPosition` consumes
        // the PREVIOUS frame's write — and the skeleton template's zero offset on a builder's very
        // first build, when the neck sits AT the chest and the arms are realized against a target
        // the pose never sees again (measured: declared-target delta 17.87u, published
        // ELBOW_A/HAND_A delta 29.93/17.91 cold vs settled, published `maxIkClampAmount` 15.47 on
        // the cold frame vs 5.52 in the rep). The resolver places the neck along the gaze the pose
        // declares above (`buildGaze`, same `headDir`, same `def.neckLength`) inside the chest
        // frame this pose declares, so the same point is expressible from authored intent alone:
        // authored gaze direction x definition neck length, rotated to world by the declared chest
        // frame via the family's existing frame helper (the thoracic reaches use it too).
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
     * R2/R4 — reach-band authoring (fourth reach-band cleanup batch).
     *
     * The pose's whole upper-body choreography is "hands behind the head with the elbows flared
     * outward" (BPS §6: "Arms may rest at the sides, be extended overhead, or be clasped behind the
     * head with elbows wide — the choice modulates intensity"). The composed target is the head
     * base projected from the pose's own geometry (B-8b: authored `headDir × def.neckLength` rotated
     * by the declared chest frame) offset `(−12, +6, ±0.55 · shoulderWidth)`. Measured through the
     * production entry point (`SkeletonPipeline.produceFrame(pose, ctx)`) at `origin/main` @
     * `ca011ad`, that point sits `34.6182` (p = 0.00) … `37.3581` (p = 1.00) u from its own shoulder
     * — INSIDE the arm chain's minimum-flexion reach
     * `SkeletonMath.minReach(80, 66, 30°) = 40.1344` at EVERY one of the `15` sampled phases, i.e.
     * the authored clasp asks for an interior elbow angle of `25.17° … 27.60°` (`152.4° … 154.8°` of
     * flexion) against the `IKConstraint`'s own `30°` stop (`150°`).
     *
     * Verdict: unintended authoring error, not an intentional ROM limit — the same class the
     * second batch fixed on `DeepSquatHoldPose` (`24.80°`) and the third on `HamstringStretchPose`'s
     * tuck (`18.89°`) and `ProneCobraStretchPose`'s start (`14.39°`), and the same one the M13 pass
     * fixed on `HamstringStretchPose`'s own start hand (`37.2108`, relocation `2.9237`). The
     * authored request is past the model's fold stop, so the solver relocated the hands
     * `5.5162 … 2.7763` u along the ray and published them ON the stop (the realized elbow reads
     * exactly `30.0000°` interior at every phase). The projection is a declaration correction: the
     * authored RAY (from the shoulder to the hands-behind-the-head point) is preserved, only the
     * radius lands on the annulus, and the published frame moves by the margin (`0.004` u).
     *
     * Residual, recorded and NOT resolved here: after the projection the hands still publish at the
     * chain's fold stop — `5.5` u short of the authored clasp at p = 0 — because the model's `30°`
     * interior stop is what a `80 + 66` chain can fold to. A clasp that lands exactly behind the
     * head needs either a longer arm or a shallower head/neck placement; that is a pose-design
     * (owner) decision, not a reach-band one, and the reach convention deliberately does not retune
     * the exercise geometry.
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
