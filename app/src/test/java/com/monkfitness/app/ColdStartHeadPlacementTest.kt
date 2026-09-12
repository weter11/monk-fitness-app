package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * B-7 regression gate — the **first frame a cold pipeline publishes** must carry the same head as the
 * frame the same pose publishes once its own state has settled at the same progress.
 *
 * Why the first frame is the interesting one (P11 whole-system audit §B-7, `docs/AUDIT_P11_WHOLE_SYSTEM.md`):
 * `BasePose.buildGaze` records the head intent as an **absolute world point**
 * (`neckWorld + gazeDir * 100`) and the Finalizer resolves the head direction as
 * `normalize(target − neck.worldPosition)`. Because the pose nodes are reused across builds, reading
 * `neck.worldPosition` before the pose's authoring-FK pass used the *previous* build's tree state — and
 * on a builder's **first** build that state is the template's zero transform, so the target landed 100
 * units from the world origin instead of 100 units along the gaze from the neck. The published
 * consequence was a first frame whose head pointed up to 180° away from the authored gaze (42.73 units
 * off for the kneeling push-up, 71.80° of direction error), snapping into place on the second frame —
 * which the `ExerciseValidator` reports as `POSITION_DISCONTINUITY` / `VELOCITY_DISCONTINUITY` ERRORs on
 * frames 1–2 of every cold run.
 *
 * What this test does **not** do: it never warms a pipeline up to hide the defect. Every measurement is
 * taken on the frames the production pipeline publishes (`SkeletonPipeline.produceFrame`), snapshotted
 * by value because `produceFrame` publishes a reused buffer (T-7 in the same audit).
 *
 * Scope: the head/neck chain and the inter-frame continuity of that chain. It deliberately says nothing
 * about the arm chain on the first frame — the cold-frame limb realization is a separate, recorded
 * finding (`B-8`) and must not be smuggled in here.
 */
class ColdStartHeadPlacementTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Representative gaze families; each entry builds a FRESH pose so every measurement is cold. */
    private fun poses(): List<Pair<String, PoseBuilder>> = listOf(
        "pushup_knee" to KneePushUpPose(),
        "pushup_standard" to StandardPushUpPose(),
        "squat_standard" to SquatPose(),
        "lunge_forward" to AlternatingForwardLungesPose(),
        "pullup_standard" to StandardPullUpPose(),
        "plank_standard" to StaticForearmPlankPose()
    )

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun snap(s: SkeletonPose) = SkeletonPose().apply { copyFrom(s) }

    private fun dist(a: Vector3, b: Vector3) =
        sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))

    /** Real frame cadence: one build per 1/149 of the rep, starting at progress 0 on a cold pipeline. */
    private fun coldSweep(pose: PoseBuilder, frames: Int = 150): List<SkeletonPose> {
        val pipe = SkeletonPipeline(def)
        val step = if (frames > 1) frames - 1f else 1f
        return (0 until frames).map { i -> snap(pipe.produceFrame(pose.build(ctx(i / step))).pose) }
    }

    /** The same progress published by a pipeline whose own history has settled on it. */
    private fun settledFrame(pose: PoseBuilder, progress: Float = 0f, frames: Int = 60): SkeletonPose {
        val pipe = SkeletonPipeline(def)
        var last: SkeletonPose? = null
        for (k in 0 until frames) last = snap(pipe.produceFrame(pose.build(ctx(progress))).pose)
        return last!!
    }

    /** Direction of the neck→head segment in the published frame. */
    private fun headDir(f: SkeletonPose): Vector3 {
        val n = f.getJoint(Joint.NECK_END); val h = f.getJoint(Joint.HEAD_POS)
        return Vector3(h.x - n.x, h.y - n.y, h.z - n.z).normalize()
    }

    private fun angleDeg(a: Vector3, b: Vector3): Float = Math.toDegrees(
        acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f).toDouble())
    ).toFloat()

    // -------------------------------------------------------------------------------------------
    // 1. The cold first frame realizes the same head as the settled frame at the same progress.
    // -------------------------------------------------------------------------------------------
    @Test
    fun firstFrameHeadMatchesTheSettledFrame() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((id, pose) in poses()) {
            val cold = coldSweep(pose, frames = 1)[0]
            val settled = settledFrame(pose)
            val dHead = dist(cold.getJoint(Joint.HEAD_POS), settled.getJoint(Joint.HEAD_POS))
            val dNeck = dist(cold.getJoint(Joint.NECK_END), settled.getJoint(Joint.NECK_END))
            val dDir = angleDeg(headDir(cold), headDir(settled))
            rows.add("%-18s HEAD_POS Δ=%.4f NECK_END Δ=%.4f direction Δ=%.2f°".format(id, dHead, dNeck, dDir))
            if (dHead > 0.5f) failures.add("$id: first published frame's HEAD_POS is %.4f units from the settled frame at the same progress".format(dHead))
            if (dNeck > 0.5f) failures.add("$id: first published frame's NECK_END is %.4f units from the settled frame at the same progress".format(dNeck))
            if (dDir > 1f) failures.add("$id: first published frame's head direction is %.2f° from the settled direction".format(dDir))
        }
        assertTrue(
            "Cold first frame does not realize the settled head (build history leaked into the head intent):\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // -------------------------------------------------------------------------------------------
    // 2. No pop at the start of a cold sweep: frame 0 → 1 (and 1 → 2) stay inside the engine's own
    //    inter-frame bound, so the first frame is not a transient outlier.
    // -------------------------------------------------------------------------------------------
    @Test
    fun firstFramesAreContinuousInAColdSweep() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val bound = 15f // ExerciseValidator's own POSITION_DISCONTINUITY threshold
        for ((id, pose) in poses()) {
            val f = coldSweep(pose)
            var worstStep = 0f; var worstAt = 0; var worstJoint = Joint.HEAD_POS
            for (j in listOf(Joint.HEAD_POS, Joint.NECK_END)) {
                for (i in 1 until f.size) {
                    val d = dist(f[i].getJoint(j), f[i - 1].getJoint(j))
                    if (d > worstStep) { worstStep = d; worstAt = i; worstJoint = j }
                }
            }
            var worstDir = 0f; var worstDirAt = 0
            for (i in 1 until f.size) {
                val a = angleDeg(headDir(f[i]), headDir(f[i - 1]))
                if (a > worstDir) { worstDir = a; worstDirAt = i }
            }
            rows.add("%-18s worst step=%.4f (%s at frame %d) worst direction step=%.2f° (frame %d)".format(
                id, worstStep, worstJoint.name, worstAt, worstDir, worstDirAt))
            if (worstStep > bound) {
                failures.add("$id: %s moves %.4f units between frames %d and %d (bound %.1f) — the first frame is a transient".format(worstJoint.name, worstStep, worstAt - 1, worstAt, bound))
            }
            if (worstDir > 5f) {
                failures.add("$id: head direction jumps %.2f° between frames %d and %d (bound 5°)".format(worstDir, worstDirAt - 1, worstDirAt))
            }
        }
        assertTrue(
            "Cold sweep is not continuous at its start:\n" + failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // -------------------------------------------------------------------------------------------
    // 3. Every later frame stays converged (the fix must not merely move the transient): sampling
    //    the cold trajectory at the rep's key progresses must reproduce the settled frame published
    //    at the SAME progress, and the neck→head bone length is untouched at every frame.
    //
    //    Tolerances: the head DIRECTION is the gaze intent's own output and must be
    //    history-independent (≤1°). The head POSITION is downstream of the engine's documented
    //    inter-frame smoothing (RFC §4.5/§5 R10 — behaviour is a function of the current frame plus
    //    the supplied history), so mid-rep frames legitimately differ from an independently settled
    //    run at the same progress by a sub-unit amount; the bound is 1.0 unit, while the cold FIRST
    //    frame (p=0, measured by [firstFrameHeadMatchesTheSettledFrame]) is held to 0.5.
    // -------------------------------------------------------------------------------------------
    @Test
    fun laterFramesStayConvergedAndBoneLengthIsPreserved() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val sampled = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for ((id, pose) in poses()) {
            val f = coldSweep(pose)
            var worstBone = 0f
            for (x in f) {
                worstBone = max(worstBone, abs(dist(x.getJoint(Joint.HEAD_POS), x.getJoint(Joint.NECK_END)) - 18f))
            }
            val deltas = sampled.joinToString(" ") { p ->
                val cold = f[round(p * (f.size - 1f)).toInt()]
                val settled = settledFrame(pose, p)
                val dPos = max(
                    dist(cold.getJoint(Joint.HEAD_POS), settled.getJoint(Joint.HEAD_POS)),
                    dist(cold.getJoint(Joint.NECK_END), settled.getJoint(Joint.NECK_END))
                )
                val dDir = angleDeg(headDir(cold), headDir(settled))
                if (dPos > 1f) failures.add("$id at p=$p: cold head/neck %.4f units from the settled frame at the same progress".format(dPos))
                if (dDir > 1f) failures.add("$id at p=$p: cold head direction %.2f° from the settled direction".format(dDir))
                "p=$p:Δpos=%.4f/Δdir=%.2f°".format(dPos, dDir)
            }
            rows.add("%-18s %s | worst |bone−18| = %.4f".format(id, deltas, worstBone))
            if (worstBone > 0.01f) {
                failures.add("$id: neck→head length deviates by %.4f from the authored 18 units".format(worstBone))
            }
        }
        assertTrue(
            "Frames after the first are not converged / bone length moved:\n" +
                failures.joinToString("\n") + "\n" + rows.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // -------------------------------------------------------------------------------------------
    // 4. The engine's own validator no longer reports the cold-start head errors.
    // -------------------------------------------------------------------------------------------
    @Test
    fun validatorReportsNoColdStartHeadErrors() {
        val validator = ExerciseValidator(
            ValidatorConfig(isStaticExercise = false, allowFootGroundPenetration = true)
        )
        val camera = Camera()
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((id, pose) in poses()) {
            val env = pose.metadata.environment ?: EnvironmentDefinition()
            val f = coldSweep(pose)
            var prev: SkeletonPose? = null
            var prePrev: SkeletonPose? = null
            val headIssues = mutableListOf<String>()
            for (i in f.indices) {
                val report = validator.validate(f[i], def, env, camera, 1080f, 1920f, prev, prePrev, 0.0166f)
                report.allIssues
                    .filter { it.severity == ValidationSeverity.ERROR }
                    .filter { it.joint == Joint.HEAD_POS || it.joint == Joint.NECK_END }
                    .forEach { headIssues.add("frame $i ${it.ruleId} ${it.joint}: ${it.message}") }
                prePrev = prev; prev = f[i]
            }
            rows.add("%-18s head/neck ERRORs across the cold sweep = %d".format(id, headIssues.size))
            if (headIssues.isNotEmpty()) failures.add("$id: " + headIssues.take(4).joinToString(" | "))
        }
        rows.forEach { println(it) }
        assertEquals(
            "Cold-start head errors still reported:\n" + failures.joinToString("\n"),
            0, failures.size
        )
    }
}
