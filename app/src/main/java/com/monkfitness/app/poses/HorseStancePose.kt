package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Horse Stance (`horse_stance_hold`) — the "ma bu" isometric hold, authored on the canonical
 * `BaseSquatPose` pipeline (SkeletonFactory tree + declared pelvis tilt + registered package bake
 * limbs). The stance is a squat hold, so the shared squat machinery is the correct family; what this
 * variant owns is its OWN stance geometry:
 *
 *  - **feet double shoulder-width apart** — `ex_horse_stance_steps` §1. The ankles are authored at
 *    `±shoulderWidth` (`46` each side of the mid-line ⇒ `92` between them = exactly `2 ×
 *    shoulderWidth`), not at the family default `±1.5 · hipWidth` (`33`).
 *  - **toes pointed slightly outward** — §1. Authored through the sanctioned exercise-intent channel
 *    ([setHeading] on each planted foot, resolved by the Finalizer's `adjustFootOrientation` against
 *    the declared `LEFT_FOOT`/`RIGHT_FOOT` support); `15°` is the conventional "slightly out" angle.
 *    The ankle's own articulation vocabulary (`buildAnkleArticulation`: dorsiflexion + inversion) has
 *    no yaw DOF, so the foot heading is the only sanctioned way to express toe-out — see the batch
 *    inventory's recorded gaps.
 *  - **sink the hips until the thighs are parallel to the floor, then hold** — §2/§4. The cycle
 *    rides the descent from a tall stance (`pelvisY 200`) to the hold (`150`, the depth at which the
 *    thigh measures ~15° below horizontal — "parallel" to within the rig's thigh:shin proportions)
 *    and back, which is the LOOP convention's ping-pong `progress 0 → 1 → 0`.
 *  - **back straight** — the trunk rides a `2.3° → 6.9°` lean (enough to keep the mass over the feet
 *    at the deep position); nothing hinges or rounds.
 *  - **hands in front or on your hips** — §3. Authored on the hips (the fists-at-the-waist stance),
 *    `6` forward / `12` above / `5` outboard of each hip joint.
 *
 * ## Reach (`everyAuthoredTargetIsInsideItsChainsReach` pins this)
 *
 * The depth is authored so BOTH ends of the cycle sit inside their chains' reachable annuli by
 * construction — measured chords: the tall stance `186.06` u against the leg's
 * `[56.01, 205.80]` band, the hold `155.70` u — so the reach stamp reads `0` with no projection
 * helper and the published stance is the authored stance (the naive family-default stance this
 * batch's RED run measured instead relocated the ankle `10.85` u and clamped the standing leg
 * `30.93` u).
 *
 * ## Recorded gaps (no BPS exists for this exercise — `docs/Biomechanical Pose Specification (BPS)/`
 * carries all 53 sibling exercises and none of the 17 this phase covers)
 *
 *  - "Tuck your pelvis slightly to avoid overarching the lower back" (`ex_horse_stance_tech`) is NOT
 *    authored: it is a cue against an anterior pelvic tilt, and the authored posture has no anterior
 *    tilt at all (the pelvis and the trunk are vertical); the rig expresses a pelvic tilt as the
 *    whole-body root rotation, so "tilt the pelvis without moving the trunk" is not a representation
 *    this authoring surface has, and inventing one would be a posture the cue does not ask for.
 */
class HorseStancePose : BaseSquatPose() {

    override val squatH = HOLD_PELVIS_Y
    override val pelvisXEnd = HOLD_PELVIS_X
    override val leanAngleEnd = HOLD_LEAN
    override val armLeanEnd = 0f

    /** Knees spread with the stance (`mistakes`: "letting the knees collapse inward"). */
    override val legPoleF = Vector3(1f, 0f, -0.3f)
    override val legPoleB = Vector3(1f, 0f, 0.3f)
    override val armPoleA = Vector3(0f, -1f, -1f)
    override val armPoleP = Vector3(0f, -1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        // One full sink-and-rise cycle; the hold itself is the reversal at progress 1 (LOOP).
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        ),
        exerciseFamily = "horse_stance",
        motionType = "Isometric Hold",
        bodyOrientation = "upright"
    )

    /** The stance's own descent: tall → hold, on the family's (pelvisY, pelvisX, lean) triple. */
    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> =
        Triple(
            SkeletonMath.lerp(TALL_PELVIS_Y, HOLD_PELVIS_Y, progress),
            SkeletonMath.lerp(TALL_PELVIS_X, HOLD_PELVIS_X, progress),
            SkeletonMath.lerp(TALL_LEAN, HOLD_LEAN, progress)
        )

    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        // The feet never move: the whole cycle rides the pelvis over a planted stance
        // (`tech`: "Distribute weight evenly across the entire foot").
        outF.set(FOOT_X, FLOOR_ANKLE_Y, -def.shoulderWidth)
        outB.set(FOOT_X, FLOOR_ANKLE_Y, def.shoulderWidth)
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        // Fists at the waist: forward of, above and just outboard of each hip joint.
        val handX = pelvisX + HAND_FORWARD
        val handY = pelvisY + HAND_ABOVE_HIP
        outA.set(handX, handY, -(def.hipWidth + HAND_OUTBOARD))
        outP.set(handX, handY, def.hipWidth + HAND_OUTBOARD)
    }

    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        // "toes pointed slightly outward" — a root-relative heading per planted foot, resolved by the
        // Finalizer against the declared foot support and projected onto the floor plane.
        setHeading(Extremity.FOOT_F, Vector3(TOE_OUT_COS, 0f, -TOE_OUT_SIN))
        setHeading(Extremity.FOOT_B, Vector3(TOE_OUT_COS, 0f, TOE_OUT_SIN))
    }

    companion object {
        /** The tall stance at the top of the cycle (progress 0): the entry the lifter sinks from. */
        private const val TALL_PELVIS_Y = 200f
        private const val TALL_PELVIS_X = -30f
        private const val TALL_LEAN = 0.04f

        /**
         * The hold (progress 1). `HOLD_PELVIS_Y = 150` is the depth at which the realized thigh sits
         * ~15° below horizontal — the exercise's "thighs parallel to the floor" (see the class KDoc:
         * the rig's thigh:shin ratio places exact parallelism at a slightly deeper/splayed stance,
         * and 15° is inside the measurement the test pins).
         */
        private const val HOLD_PELVIS_Y = 150f
        private const val HOLD_PELVIS_X = -60f
        private const val HOLD_LEAN = 0.12f

        /** The planted ankles: forward of the pelvis, on the family's floor frame (`y = 25`). */
        private const val FOOT_X = 30f
        private const val FLOOR_ANKLE_Y = 25f

        private const val HAND_FORWARD = 6f
        private const val HAND_ABOVE_HIP = 12f
        private const val HAND_OUTBOARD = 5f

        private const val TOE_OUT_DEG = 15f
        private val TOE_OUT_RAD = Math.toRadians(TOE_OUT_DEG.toDouble()).toFloat()
        private val TOE_OUT_COS = kotlin.math.cos(TOE_OUT_RAD)
        private val TOE_OUT_SIN = kotlin.math.sin(TOE_OUT_RAD)
    }
}
