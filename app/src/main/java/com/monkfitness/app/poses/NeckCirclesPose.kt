package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Neck Circles (`neck_circles_hold`) — the standing **cervical cone**, authored on the canonical
 * `SkeletonFactory` tree through [BaseCervicalPose] (the neck family's shared standing chassis).
 *
 * ## What the exercise is, and where that comes from
 *
 * **There is no BPS for this exercise and no exercise copy either**: `WorkoutGenerator` passes
 * `R.string.ex_neck_circles` as *every* field (name/description/technique/steps), so the catalog entry
 * is the title and nothing else, and the three localizations carry only that title. The identity is
 * therefore the exercise's **name**, read across all three locales, and this is the one
 * authored-by-convention decision of this pose — recorded rather than presented as specification:
 *
 *  * `values/strings.xml` `ex_neck_circles` = *"Neck Circles"*;
 *  * `values-ru` = *"Круговые движения головой"* ("circular movements of the **head**");
 *  * `values-uk` = *"Обертання головою"* ("rotation of the head").
 *
 * Two independent translations agree on the subject (the head) and on the motion (a circle/rotation of
 * it), and the catalog files the exercise under `neck_mobility`/`SPINE` as a **timer** mobility drill —
 * i.e. a continuous, controlled circling of the head on a still body, not a hold and not a stretch.
 *
 * ## The authored movement
 *
 *  * The neck's long axis describes a **cone** of half-angle [CONE_RAD] about the vertical, its azimuth
 *    `φ = 2π · progress` advancing one full turn per cycle: `dir(φ) = (sinθ·cosφ, cosθ, sinθ·sinφ)`.
 *    The head is the neck's own child and inherits that axis, so the head's base and the head itself
 *    ride the same cone — the classic cervical circle (chin forward → to one shoulder → up/back → to the
 *    other shoulder).
 *  * The cervical chain is two rigid bones (`CHEST → NECK_END → HEAD_POS`), and the cone is authored as
 *    **both bones lying on `dir(φ)`** (the channel the engine's own gaze resolver writes) and nothing
 *    else: the published geometry is the neck's tip rolling on a circle of radius
 *    `neckLength · sin θ = 4.66 u` and the head's centre on `(neckLength + 18) · sin θ = 9.32 u`, both at
 *    a **constant height** (`· cos θ`), so the locus is a horizontal circle rather than a roll.
 *  * The body is the family chassis and does not move at all: the pelvis, the trunk and the shoulders
 *    are still for the whole circle, and both feet stay planted. "Cervical motion without translating
 *    the whole body" is the property this pose's own copy implies (a neck drill) and the one its tests
 *    measure on every joint outside the chain.
 *
 * ## The amplitude (authored, with its bound measured)
 *
 * The title says only "circles" and states no range. [CONE_RAD] = `15°` is authored as a **low-amplitude
 * cervical** circle: the published head then travels on a circle of radius `36 · sin 15° = 9.32 u`
 * (`18.6 u` diameter, ≈ `8.6 cm` at this rig's scale — a controlled neck circle), and the head's own
 * height changes by `0.00 u` by construction (`36 · cos θ` is constant), which is exactly what makes the
 * locus a horizontal circle rather than a roll. Nothing here is a maximal cervical range: the cone's
 * `15°` is well inside a real neck circle and is stated as an authored constant.
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * The drill's **direction** (which way around) is not specified anywhere and is authored
 *    counter-clockwise in the published azimuth ordering; the mirror is the same pose played the other
 *    way, and `LoopMode.LOOP`'s reverse repeat already plays this cycle both ways.
 *  * The **tempo/repetition count** is the catalog's timer, not a kinematic property: `durationSeconds`
 *    is the family's authored cycle length.
 *  * A real neck circle also carries a little upper-cervical extension at the back of the circle; this
 *    rig's cone keeps the head's own level fixed to the neck's axis (a pure cone), which is the
 *    representation available — recorded, not faked.
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
         * then circles at radius `36 · sin 15° = 9.32 u` (see the KDoc); the `18 u` head bone's own
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
