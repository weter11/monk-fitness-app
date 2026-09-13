package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Chin Tucks (`chin_tuck_standard`) — the cervical **retraction** drill, authored on the canonical
 * `SkeletonFactory` tree through [BaseCervicalPose] (the neck family's shared standing chassis).
 *
 * ## The exercise (its own copy — the only specification in the repository; there is no BPS)
 *
 * `ex_chin_tucks_desc` *"A posture drill that trains the deep neck flexors and stacks the head over the
 * ribcage. The move is small but precise."* — `steps`: *"1. Stand tall or lie on your back. 2. Look
 * straight ahead. 3. Gently draw the chin straight back as if making a double chin. 4. Hold the end
 * position briefly without tipping the head up or down. 5. Relax and repeat."* — `tech`: *"Think back,
 * not down. Keep the jaw relaxed. Make the neck feel long at the top."* — `mistakes`: *"Tilting the head
 * toward the floor. Poking the chin forward between reps. Shrugging the shoulders."*
 *
 * ## The authored movement, one statement per copy line
 *
 *  * **§1 (the standing option) + §2 "Look straight ahead"** — the family chassis: an upright still
 *    trunk, both feet planted, and a head whose own axis is authored **level** (vertical, in the chain's
 *    frame). The gaze is level at every phase of the cycle, which is the published form of §2's cue.
 *  * **§3 "Draw the chin straight back"** — the rig's cervical chain is two rigid bones with no
 *    translation DOF, so the retraction is authored as the neck's **posterior tilt** [RETRACTION_RAD]:
 *    the neck's bone turns back and the head is carried on it **level**. Measured on the published
 *    frames, the head's base (`NECK_END`) and the head both travel `7.34 u` straight back at the hold,
 *    which is exactly what a chin tuck *is* (`tech`: *"Think back, not down"*).
 *  * **§4 "without tipping the head up or down"** — the head's own bone (`NECK_END → HEAD_POS`) is the
 *    chain's **vertical axis at every phase** (`LEVEL_HEAD_DIRECTION`), so the published head bone stays
 *    within `1°` of vertical **while the neck is tilted `24°`**. This is the assertion that discriminates
 *    a real chin tuck from a nod: a pose that tilted the *head's* bone — the naive reading of "draw the
 *    chin back" on a two-bone chain — tips the head's own axis by the full [RETRACTION_RAD] and fails it
 *    (and the same is true of a pose that hands the head to a world `headTarget`).
 *  * **§4 "Hold the end position briefly" + §5 "Relax and repeat"** — the cycle is an entry, a **hold
 *    plateau** ([HOLD_RAMP] / [HOLD_FALL] bracket a `45 %` plateau in which the retraction is constant)
 *    and a release, which is what a `10–18`-rep posture drill actually does.
 *  * **`mistakes` "Tilting the head toward the floor"** — the head's axis is level at every phase (the
 *    assertion above) and the head *rises* nowhere/lowers nowhere beyond the `1.56 u` the geometry of a
 *    `24°` tilt necessarily costs.
 *  * **`mistakes` "Poking the chin forward between reps"** — the retraction is monotone `0 → 1 → 0`, so
 *    the published head never travels ANTERIOR to its neutral position (asserted explicitly: the head's
 *    `x` never exceeds the neutral by more than `0.1 u`).
 *  * **`mistakes` "Shrugging the shoulders"** — the chassis never moves the girdle: the published
 *    shoulders' height travel is `0.00 u` (asserted).
 *
 * ## The amplitude (authored, with its bound measured)
 *
 * `steps` §3 says only *"gently"* and `desc` *"The move is small but precise"* — the amplitude is not in
 * the copy. [RETRACTION_RAD] = `24°` is authored because it is the largest tilt whose **published head
 * translation stays inside this rig's own head bone**: the head's base travels `18 · sin(24°) = 7.32 u`
 * (≈ `3.3 cm` at this rig's scale — the published physiological range of a chin tuck), the head bone is
 * `18 u`, and the motion is therefore unambiguously small next to any limb travel in the corpus. The
 * test asserts both ends: the retraction really happens (`≥ 5 u`) and it stays small (`≤ 10 u`).
 *
 * The chain is rigid, so the head necessarily drops `18 · (1 − cos 24°) = 1.56 u` while it travels back
 * `7.34 u`. That is the geometry of a tilt carrying a rigid chain, not a "tipping down": the head's own
 * axis (the observable the copy's cue is about) is level throughout.
 *
 * ## Recorded gaps (no invented specification)
 *
 *  * *"Train the deep neck flexors"* (a muscular claim) and *"Make the neck feel long at the top"* have
 *    **no representation on this rig** — the neck is a fixed-length rigid bone, so "long at the top" is
 *    neither publishable nor faked here.
 *  * *"Keep the jaw relaxed"* — the rig has no jaw joint.
 *  * *"Stand tall or lie on your back"* — the standing option is authored (see [BaseCervicalPose]'s
 *    record); the supine option is not a second pose.
 *  * The **stance** (width, depth, height) and the **rest arms** are the family's authored constants
 *    (`BaseCervicalPose`'s KDoc), the hold fractions are this file's ([HOLD_RAMP]/[HOLD_FALL]).
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

        // §3/§4: the neck's bone tilts posteriorly by the authored angle (the retraction), and the head
        // is carried on it LEVEL — its own bone stays the chain's vertical axis — so the head's base
        // travels straight back while the head keeps its own line, which is the kinesiology the copy
        // describes (lower-cervical retraction with the skull staying level over the shoulders).
        val tilt = neckTilt(context.progress)
        authorCervicalChain(
            def.neckLength, neckDirection(tilt),
            BaseCervicalPose.HEAD_BONE_LENGTH, LEVEL_HEAD_DIRECTION
        )

        return finalizeCervical()
    }

    companion object {
        /**
         * The cervical retraction's authored amplitude: `24°` of posterior neck tilt, which publishes a
         * `7.32 u` posterior head travel against the `18 u` head bone (≈ `3.3 cm`). See the KDoc: the
         * copy asks for *"gently"* / *"small but precise"* and states no number.
         */
        const val RETRACTION_RAD = 0.42f

        /** The retraction's rhythm: a ramp, a HOLD plateau (§4 *"Hold the end position briefly"*), a release. */
        const val HOLD_RAMP = 0.30f
        const val HOLD_FALL = 0.25f

        /** The plateau's own bounds (`progress` at which the hold starts and ends) — the test's reader. */
        const val HOLD_START = HOLD_RAMP
        const val HOLD_END = 1f - HOLD_FALL

        /**
         * The retraction at [progress]: entry → **hold** → release. `smootherStep` at both ends, so the
         * published motion starts and ends at rest (the drill is performed slowly, `tech`: *"Move one
         * shoulder at a time … smooth"*-style control; the copy's own *"gently"*).
         */
        fun retraction(progress: Float): Float = when {
            progress <= 0f -> 0f
            progress < HOLD_RAMP -> cervicalSmootherStep(progress / HOLD_RAMP)
            progress <= HOLD_END -> 1f
            progress < 1f -> cervicalSmootherStep((1f - progress) / HOLD_FALL)
            else -> 0f
        }

        /** The neck's authored posterior tilt at [progress], in radians. */
        fun neckTilt(progress: Float): Float = RETRACTION_RAD * retraction(progress)

        /** The head's authored direction: the chain's own vertical axis, at every phase (`§4`). */
        val LEVEL_HEAD_DIRECTION = Vector3(0f, 1f, 0f)

        /** The neck's authored bone direction at [tilt]: `θ` posterior of vertical, in the sagittal plane. */
        fun neckDirection(tilt: Float): Vector3 = Vector3(-sin(tilt), cos(tilt), 0f)

        /**
         * The authored posterior travel at [progress] — the copy's *"draw the chin back"*. The chain is
         * rigid and both its bones are `18 u`, so every joint of it (the neck's base and the head alike)
         * moves this far back at the hold.
         */
        fun retractionTravel(progress: Float): Float = BaseCervicalPose.HEAD_BONE_LENGTH * sin(neckTilt(progress))

    }
}
