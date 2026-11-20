package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3D exercise framing's contract: one camera per (exercise, viewport aspect), containing every
 * joint of the whole motion cycle with the body envelope's margin, holding the authored viewpoint,
 * and never moving within a rep.
 *
 * Containment is measured on the joints the production pipeline publishes — through [X1Bind] into
 * the asset metres the mesh is skinned in, then through the view the framer returned — so the
 * invariant is checked on what is drawn rather than on the solver's own model of it. The view's
 * basis is re-derived here from `eye`/`target`/`up` the way Filament derives it from `lookAt`, so a
 * frame that aims the camera anywhere but where it claims is measured as such.
 */
class ExerciseCameraFramingInvariantTest {

    private val framer = ExerciseCameraFramer3D()
    private val definition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(definition)

    private val aspects = listOf(0.45f, 0.5625f, 1.0f, 1.333f)

    /** The corpus the composition work is measured on: a floor plank, a push-up, a pull, a standing rep. */
    private val corpus = listOf(
        "pushup_standard", "row_standard", "birddog_reps", "plank_standard",
        "glute_bridge_standard", "squat_standard", "superman_prone"
    )

    /**
     * The NDC box ([minX], [maxX], [minY], [maxY]) every joint of [id] occupies through [view] on a
     * surface of [aspect], swept over the whole motion cycle: the containment evidence.
     */
    private fun boundsThrough(id: String, aspect: Float, view: CameraView3D, phases: Int = PHASES): FloatArray {
        val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
        val pipeline = SkeletonPipeline(definition)
        val sides = if (config.alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val point = FloatArray(3)
        for (side in sides) {
            for (i in 0..phases) {
                val pose = pipeline.produceFrame(
                    config.builder,
                    PoseContext(progress = i.toFloat() / phases, side = side, definition = definition)
                ).pose
                for (joint in Joint.entries) {
                    X1Bind.toAssetInto(pose.joints[joint.index], unitsPerMetre, point)
                    val ndc = project(view, point, aspect)
                    if (ndc[0] < minX) minX = ndc[0]
                    if (ndc[0] > maxX) maxX = ndc[0]
                    if (ndc[1] < minY) minY = ndc[1]
                    if (ndc[1] > maxY) maxY = ndc[1]
                }
            }
        }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    /**
     * [point] in NDC through [view]: the basis Filament builds from `lookAt` (`right = forward × up`,
     * re-orthogonalised) and a `fovVerticalDegrees` perspective held at [aspect].
     */
    private fun project(view: CameraView3D, point: FloatArray, aspect: Float): FloatArray {
        val fx = view.targetX - view.eyeX
        val fy = view.targetY - view.eyeY
        val fz = view.targetZ - view.eyeZ
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        val forward = floatArrayOf(fx / fl, fy / fl, fz / fl)
        val rx = forward[1] * view.upZ - forward[2] * view.upY
        val ry = forward[2] * view.upX - forward[0] * view.upZ
        val rz = forward[0] * view.upY - forward[1] * view.upX
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        val right = floatArrayOf(rx / rl, ry / rl, rz / rl)
        val up = floatArrayOf(
            right[1] * forward[2] - right[2] * forward[1],
            right[2] * forward[0] - right[0] * forward[2],
            right[0] * forward[1] - right[1] * forward[0]
        )
        val dx = point[0] - view.eyeX
        val dy = point[1] - view.eyeY
        val dz = point[2] - view.eyeZ
        val depth = dx * forward[0] + dy * forward[1] + dz * forward[2]
        val x = dx * right[0] + dy * right[1] + dz * right[2]
        val y = dx * up[0] + dy * up[1] + dz * up[2]
        val tan = tan(Math.toRadians(view.fovVerticalDegrees / 2.0)).toFloat()
        return floatArrayOf(x / (depth * tan * aspect), y / (depth * tan))
    }

    private fun distance(view: CameraView3D): Float {
        val dx = view.eyeX - view.targetX
        val dy = view.eyeY - view.targetY
        val dz = view.eyeZ - view.targetZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The authored azimuth and elevation the view actually points along, in the engine's own axes. */
    private fun aimedAngles(view: CameraView3D): FloatArray {
        // The camera stands on the subject's front side, so it looks along `towards`, whose
        // engine-space components are `(−sin yaw · cos pitch, −sin pitch, cos yaw · cos pitch)`.
        // The asset-space look direction comes back through `toEngine` at unit scale.
        val dx = view.targetX - view.eyeX
        val dy = view.targetY - view.eyeY
        val dz = view.targetZ - view.eyeZ
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val engine = X1Bind.toEngine(
            com.monkfitness.app.animation.Vector3(dx / length, dy / length, dz / length), 1f
        )
        val pitch = asin((-engine.y).coerceIn(-1f, 1f))
        val yaw = atan2(-engine.x, engine.z)
        return floatArrayOf(yaw, pitch)
    }

    @Test
    fun everyJointOfTheNamedCorpusStaysInsideTheFramedViewport() {
        var evaluations = 0
        var worst = 0f
        for (aspect in aspects) {
            for (id in corpus) {
                val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
                val view = framer.frame(config.builder, config.alternating, aspect)
                val bounds = boundsThrough(id, aspect, view)
                val widest = bounds.maxOf { abs(it) }
                worst = maxOf(worst, widest)
                evaluations++
                assertTrue(
                    "$id @ $aspect: a joint leaves the containment box — NDC x[${bounds[0]}..${bounds[1]}] " +
                        "y[${bounds[2]}..${bounds[3]}] must stay inside ±$CONTAINMENT (eye " +
                        "(${view.eyeX}, ${view.eyeY}, ${view.eyeZ}) target (${view.targetX}, " +
                        "${view.targetY}, ${view.targetZ}))",
                    widest <= CONTAINMENT
                )
            }
        }
        assertEquals("every exercise must be measured on every aspect", corpus.size * aspects.size, evaluations)
        assertTrue("the measured worst containment ($worst) is suspiciously loose", worst > 0.3f)
    }

    @Test
    fun everyExerciseInTheRegistryStaysInsideTheFramedViewport() {
        val configs = PoseRegistry.animationIds.sorted().mapNotNull { id -> PoseRegistry.getPoseConfig(id)?.let { id to it } }
        assertTrue("the registry is empty", configs.isNotEmpty())
        var evaluations = 0
        for (aspect in aspects) {
            for ((id, config) in configs) {
                val view = framer.frame(config.builder, config.alternating, aspect)
                val bounds = boundsThrough(id, aspect, view, phases = COARSE_PHASES)
                val widest = bounds.maxOf { abs(it) }
                evaluations++
                assertTrue(
                    "$id @ $aspect: a joint leaves the containment box — NDC x[${bounds[0]}..${bounds[1]}] " +
                        "y[${bounds[2]}..${bounds[3]}] must stay inside ±$CONTAINMENT",
                    widest <= CONTAINMENT
                )
            }
        }
        assertEquals(
            "every resolvable animation in the registry must be measured on every aspect",
            configs.size * aspects.size, evaluations
        )
    }

    @Test
    fun theCameraNeverMovesWithinARep() {
        for (id in corpus) {
            val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
            val pipeline = SkeletonPipeline(definition)
            val sides = if (config.alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
            var reference: CameraView3D? = null
            var frames = 0
            for (side in sides) {
                for (step in 0..PHASES) {
                    // Play the rep the way the renderer does, then re-derive the frame: the view the
                    // session draws with must not depend on the phase that was just played.
                    pipeline.produceFrame(
                        config.builder,
                        PoseContext(progress = step.toFloat() / PHASES, side = side, definition = definition)
                    )
                    val view = framer.frame(config.builder, config.alternating, PHONE)
                    frames++
                    val expected = reference
                    if (expected == null) {
                        reference = view
                    } else {
                        assertBits("$id at progress=${step.toFloat() / PHASES} side=$side: the eye moved", expected.eyeX, view.eyeX)
                        assertBits("$id at progress=${step.toFloat() / PHASES} side=$side: the eye moved", expected.eyeY, view.eyeY)
                        assertBits("$id at progress=${step.toFloat() / PHASES} side=$side: the eye moved", expected.eyeZ, view.eyeZ)
                        assertBits("$id: the target moved with progress", expected.targetX, view.targetX)
                        assertBits("$id: the target moved with progress", expected.targetY, view.targetY)
                        assertBits("$id: the target moved with progress", expected.targetZ, view.targetZ)
                        assertBits("$id: the up axis moved with progress", expected.upX, view.upX)
                    }
                }
            }
            assertTrue("$id was not sampled", frames > 1)
        }
    }

    @Test
    fun theFrameIsNotAnySinglePhaseOfTheRep() {
        // Non-vacuity for the test above: fitting each phase on its own puts the camera somewhere
        // else, so "one frame for the rep" is a property of this rule and not of the motion being
        // still. The aim point is what moves first — a row's travel is along the view axis, where
        // the distance does not change but the subject's centre does.
        var widest = 0f
        for (id in MOVING_CORPUS) {
            val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
            val views = (0..8).map { step ->
                framer.frame(PhasePinned(config.builder, step / 8f), config.alternating, PHONE)
            }
            val aim = views.first()
            val spread = views.maxOf { view ->
                val dx = view.targetX - aim.targetX
                val dy = view.targetY - aim.targetY
                val dz = view.targetZ - aim.targetZ
                sqrt(dx * dx + dy * dy + dz * dz)
            }
            widest = maxOf(widest, spread)
            assertTrue(
                "$id: a per-phase fit must move measurably ($spread m over the rep) — otherwise the " +
                    "one-frame invariant proves nothing",
                spread > 0.01f
            )
        }
        assertTrue("no pose in the non-vacuity set moves a per-phase fit ($widest m)", widest > 0.2f)
    }

    @Test
    fun theAuthoredViewpointIsTheCamerasViewpoint() {
        for (id in corpus) {
            val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
            val declaration = config.builder.metadata.camera
            for (aspect in aspects) {
                val angles = aimedAngles(framer.frame(config.builder, config.alternating, aspect))
                assertEquals(
                    "$id @ $aspect: the camera's azimuth must be the authored yaw",
                    declaration.defaultYaw.toDouble(), angles[0].toDouble(), 1e-4
                )
                assertEquals(
                    "$id @ $aspect: the camera's elevation must be the authored pitch",
                    declaration.defaultPitch.toDouble(), angles[1].toDouble(), 1e-4
                )
            }
        }

        // the overrides the corpus declares are distinguishable in the shot, not just in the data:
        // a plank and a row author 0.16, a push-up the 0.22 default.
        val plank = aimedAngles(framer.frame(config("plank_standard").builder, false, PHONE))[1]
        val row = aimedAngles(framer.frame(config("row_standard").builder, false, PHONE))[1]
        val pushup = aimedAngles(framer.frame(config("pushup_standard").builder, false, PHONE))[1]
        assertEquals("plank_standard must reflect its authored pitch", 0.16f.toDouble(), plank.toDouble(), 1e-4)
        assertEquals("row_standard must reflect its authored pitch", 0.16f.toDouble(), row.toDouble(), 1e-4)
        assertEquals("pushup_standard must reflect the default pitch", 0.22f.toDouble(), pushup.toDouble(), 1e-4)
        assertTrue("the two authored elevations must not collapse to one shot", abs(pushup - plank) > 0.05f)
    }

    @Test
    fun theViewIsAPureFunctionOfTheExerciseAndTheAspect() {
        for (id in corpus) {
            val config = config(id)
            val byAspect = framer.frame(config.builder, config.alternating, 540f / 960f)
            val byPixels = framer.frame(config.builder, config.alternating, 540, 960)
            assertBits("$id: the pixel size and the aspect must solve one view", byAspect.eyeX, byPixels.eyeX)
            assertBits("$id: the pixel size and the aspect must solve one view", byAspect.eyeZ, byPixels.eyeZ)
            assertBits("$id: the target must not depend on the pixel size", byAspect.targetY, byPixels.targetY)

            // a degenerate surface must not produce a NaN view — the camera still has to draw.
            val degenerate = framer.frame(config.builder, config.alternating, 0, 0)
            assertTrue("$id: a degenerate surface must still yield a finite view", finite(degenerate))
            assertTrue("$id: a zero aspect must fall back to a square", finite(framer.frame(config.builder, config.alternating, 0f)))

            // landscape and portrait must differ: the fit is not aspect-blind.
            val landscape = framer.frame(config.builder, config.alternating, 1333, 1000)
            assertTrue(
                "$id: a wider surface must not leave the subject at the same distance",
                abs(distance(landscape) - distance(byPixels)) > 0.01f
            )
        }
    }

    @Test
    fun theFramedSubjectFillsTheSurfaceWithoutTouchingIt() {
        for (aspect in aspects) {
            for (id in corpus) {
                val config = config(id)
                val view = framer.frame(config.builder, config.alternating, aspect)
                val bounds = boundsThrough(id, aspect, view)
                val fill = bounds.maxOf { abs(it) }
                assertTrue(
                    "$id @ $aspect: the athlete occupies only $fill of the limiting axis — the frame is " +
                        "so wide the exercise reads as a speck",
                    fill > MINIMUM_FILL
                )
                assertTrue("$id @ $aspect: the athlete touches the edge ($fill)", fill <= CONTAINMENT)
            }
        }

        // the pose the legacy fixed camera cut off now sits inside the frame, by a margin.
        val superman = boundsThrough("superman_prone", PHONE, framer.frame(config("superman_prone").builder, false, PHONE))
        assertTrue(
            "superman_prone must be drawn whole (measured NDC y[${superman[2]}..${superman[3]}], the " +
                "legacy camera ran it 4.15% of the frame past the bottom edge)",
            superman[2] > -CONTAINMENT
        )
    }

    @Test
    fun theStageFramesThroughTheSolverAndOnlyOncePerSurface() {
        val source = File("src/main/java/com/monkfitness/app/character/CharacterSession.kt")
        assertTrue("CharacterSession must be at the module root's path: ${source.absolutePath}", source.isFile)
        val text = source.readText()
        assertTrue(
            "the stage must place its camera through the framing solver",
            text.contains("ExerciseCameraFramer3D")
        )
        assertEquals(
            "the stage must place its camera in exactly ONE place (a second call site is a per-frame " +
                "camera move); found ${text.split("camera.lookAt(").size - 1}",
            1, text.split("camera.lookAt(").size - 1
        )
        assertTrue(
            "the stage must re-frame on resize",
            Regex("""override fun onResized\([^)]*\)\s*\{[^}]*applyFraming\(""").containsMatchIn(text)
        )
    }

    private fun config(id: String) = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")

    private fun assertBits(label: String, expected: Float, actual: Float) {
        assertEquals(label, java.lang.Float.floatToIntBits(expected), java.lang.Float.floatToIntBits(actual))
    }

    private fun finite(view: CameraView3D): Boolean =
        listOf(view.eyeX, view.eyeY, view.eyeZ, view.targetX, view.targetY, view.targetZ, view.upX, view.upY, view.upZ)
            .all { it.isFinite() }

    /**
     * A pose that always builds one phase of another, whatever progress it is asked for: what a
     * per-phase fit would have measured, kept only to prove the one-frame rule is doing work.
     */
    private class PhasePinned(private val delegate: PoseBuilder, private val phase: Float) : PoseBuilder {
        override val metadata get() = delegate.metadata

        override fun build(context: PoseContext): SkeletonPose =
            delegate.build(PoseContext(progress = phase, side = context.side, definition = context.definition))
    }

    private companion object {
        /** The four viewports the corpus is measured on: two portrait phone aspects, square, 4:3 landscape. */
        val PHONE = 0.5625f
        const val PHASES = 64
        const val COARSE_PHASES = 32

        /** The containment box: `8%` of each axis is kept clear of the subject's extremes. */
        const val CONTAINMENT = 0.92f

        /** The weakest fill any measured exercise may leave on the limiting axis. */
        const val MINIMUM_FILL = 0.45f

        /** The poses whose rep travels far enough that a per-phase fit would visibly move. */
        val MOVING_CORPUS = listOf("squat_standard", "pushup_standard", "row_standard", "birddog_reps")
    }
}
