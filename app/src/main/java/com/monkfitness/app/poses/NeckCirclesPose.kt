package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Neck Circles (`neck_circles_hold`) — the standing cervical cone. There is no BPS and no exercise copy
 * either (`WorkoutGenerator` passes the title as every field); the three localizations agree on a circle
 * of the head, which is the authored-by-convention identity here.
 *
 * Both cervical bones lie on the cone direction, so the locus is a horizontal circle at constant height,
 * not a roll.
 */
class NeckCirclesPose : BaseCervicalPose() {

    override val metadata = PoseMetadata(
        camera = cervicalCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = cervicalGround,
        support = cervicalSupport,
        exerciseFamily = "neck_mobility",
        motionType = "Circular Mobility",
        bodyOrientation = "upright"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        buildCervicalChassis(def)

        val phi = azimuth(context.progress)
        // Both bones of the cervical chain lie ON the cone's direction at this azimuth, so the neck's tip
        // rolls at `neckLength · sin θ` and the head's centre at `(neckLength + 18) · sin θ`, on the same
        // axis (the drill's own picture: one continuous cervical circle, not a nod that also turns).
        val dir = coneDirection(phi)
        authorCervicalChain(
            def.neckLength, dir,
            BaseCervicalPose.HEAD_BONE_LENGTH, dir
        )

        return finalizeCervical()
    }

    companion object {
        private const val TWO_PI = 2f * PI.toFloat()

        /**
         * The cone's half-angle: `15°`, the authored low-amplitude cervical circle. The published head
         * then circles at radius `36 · sin 15° = 9.32 u`; the `18 u` head bone's own
         * excursion stays a fraction of any limb travel in the corpus.
         */
        const val CONE_RAD = 0.2618f

        /** The head's radius about the neck base: `(neckLength + headBone) · sin θ`. */
        fun headCircleRadius(def: SkeletonDefinition): Float =
            (def.neckLength + BaseCervicalPose.HEAD_BONE_LENGTH) * sin(CONE_RAD)

        /** The azimuth at [progress]: one full turn per cycle, starting at the chin-down phase. */
        fun azimuth(progress: Float): Float = progress * TWO_PI

        /** The cone direction at azimuth [phi] — the published `CHEST → NECK_END → HEAD_POS` axis. */
        fun coneDirection(phi: Float): Vector3 =
            Vector3(sin(CONE_RAD) * cos(phi), cos(CONE_RAD), sin(CONE_RAD) * sin(phi))

        /** The head's authored horizontal offset from the neck base at [progress]. */
        fun headOffset(progress: Float, def: SkeletonDefinition): Vector3 {
            val d = coneDirection(azimuth(progress))
            val r = def.neckLength + BaseCervicalPose.HEAD_BONE_LENGTH
            return Vector3(d.x * r, d.y * r, d.z * r)
        }
    }
}
