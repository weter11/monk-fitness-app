package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Chin Tucks (`chin_tuck_standard`) — the cervical **retraction** drill on the canonical
 * `SkeletonFactory` tree through [BaseCervicalPose] (the neck family's shared standing chassis). There is
 * no BPS for this exercise; its own copy is the only specification in the repository.
 *
 * The rig's cervical chain is two rigid bones with no translation DOF, so the retraction is authored as
 * the neck's **posterior tilt** [RETRACTION_RAD]: the neck's bone turns back and the head is carried on it
 * **level**. Measured, the head's base (`NECK_END`) and the head both travel `7.34 u` straight back at the
 * hold — which is what a chin tuck *is* ("think back, not down"). The head's own bone
 * (`NECK_END → HEAD_POS`) is the chain's vertical axis at every phase ([LEVEL_HEAD_DIRECTION]) and stays
 * within `1°` of vertical **while the neck is tilted `24°`**: this is the assertion that discriminates a
 * real chin tuck from a nod — a pose that tilted the *head's* bone (the naive reading on a two-bone chain)
 * tips the head's axis by the full [RETRACTION_RAD], and so does one that hands the head to a world
 * `headTarget`.
 *
 * The amplitude is authored because the copy says only "gently" / "small but precise": [RETRACTION_RAD] =
 * `24°` is the largest tilt whose published head translation stays inside the rig's own head bone
 * (`18 · sin(24°) = 7.32 u` ≈ `3.3 cm` at this rig's scale). The chain being rigid, the head necessarily
 * drops `18 · (1 − cos 24°) = 1.56 u` while travelling back — the geometry of a tilt carrying a rigid
 * chain, not a "tipping down".
 *
 * The cycle is an entry, a `45 %` hold plateau ([HOLD_RAMP]/[HOLD_FALL]) and a release; the retraction is
 * monotone `0 → 1 → 0`, so the published head never travels anterior to its neutral position.
 *
 * Recorded gaps: "train the deep neck flexors" and "make the neck feel long at the top" have no
 * representation on a fixed-length rigid bone and are not faked; the rig has no jaw joint ("keep the jaw
 * relaxed"); and "stand tall or lie on your back" authors the standing option only ([BaseCervicalPose]'s
 * record), the supine option being recorded rather than duplicated into a second pose.
 */
class ChinTuckPose : BaseCervicalPose() {

    override val metadata = PoseMetadata(
        camera = cervicalCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = cervicalGround,
        support = cervicalSupport,
        exerciseFamily = "neck_mobility",
        motionType = "Cervical Retraction",
        bodyOrientation = "upright"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        buildCervicalChassis(def)

        // §3/§4: the neck's bone tilts posteriorly by the authored angle while the head is carried LEVEL —
        // its own bone stays the chain's vertical axis — so the head's base travels straight back and the
        // head keeps its own line (lower-cervical retraction with the skull over the shoulders).
        val tilt = neckTilt(context.progress)
        authorCervicalChain(
            def.neckLength, neckDirection(tilt),
            BaseCervicalPose.HEAD_BONE_LENGTH, LEVEL_HEAD_DIRECTION
        )

        return finalizeCervical()
    }

    companion object {
        /**
         * The retraction's authored amplitude: `24°` of posterior neck tilt, which publishes a `7.32 u`
         * posterior head travel against the `18 u` head bone (≈ `3.3 cm`). The copy states no number.
         */
        const val RETRACTION_RAD = 0.42f

        const val HOLD_RAMP = 0.30f
        const val HOLD_FALL = 0.25f

        /** The plateau's own bounds (`progress` at which the hold starts and ends) — the test's reader. */
        const val HOLD_START = HOLD_RAMP
        const val HOLD_END = 1f - HOLD_FALL

        /**
         * The retraction at [progress]: entry → **hold** → release, with `smootherStep` at both ends so the
         * published motion starts and ends at rest (the copy's own "gently").
         */
        fun retraction(progress: Float): Float = when {
            progress <= 0f -> 0f
            progress < HOLD_RAMP -> cervicalSmootherStep(progress / HOLD_RAMP)
            progress <= HOLD_END -> 1f
            progress < 1f -> cervicalSmootherStep((1f - progress) / HOLD_FALL)
            else -> 0f
        }

        fun neckTilt(progress: Float): Float = RETRACTION_RAD * retraction(progress)

        val LEVEL_HEAD_DIRECTION = Vector3(0f, 1f, 0f)

        fun neckDirection(tilt: Float): Vector3 = Vector3(-sin(tilt), cos(tilt), 0f)

        /**
         * The authored posterior travel at [progress] — the copy's *"draw the chin back"*. The chain is rigid
         * and both its bones are `18 u`, so every joint of it moves this far back at the hold.
         */
        fun retractionTravel(progress: Float): Float = BaseCervicalPose.HEAD_BONE_LENGTH * sin(neckTilt(progress))

    }
}
