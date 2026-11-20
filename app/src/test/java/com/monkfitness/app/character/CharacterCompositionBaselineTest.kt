package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The composition the 3D exercise framing replaces, measured on the camera it replaces: the fixed
 * view every exercise was shot with before this feature — eye `(0, 1.05, 3.4)`, target
 * `(0, 1.0, 0)`, `45`° vertical — against the joint positions the production pipeline publishes, in
 * the asset metres the mesh is skinned in.
 *
 * The defect this records: the fixed view was composed for a standing figure and never measured
 * against what it was drawing. On the same corpus and the same viewports, `superman_prone` runs
 * `2.08%` of the frame HEIGHT past the bottom edge at every aspect — the hands and feet the pose
 * extends are drawn outside the surface — while `pushup_standard` lands `0.31%` above that edge and
 * the widest poses use a third of the frame's width. One camera cannot hold a floor plank and a
 * standing squat, and this measurement is the "before" the framing has to beat.
 *
 * The numbers are pinned per (exercise, aspect): they are this instrument's record of the camera
 * being replaced, so they must be re-measured rather than adjusted when the pipeline's own geometry
 * moves. A "bottom margin" here is the distance from the lowest drawn joint to the bottom edge, as a
 * percentage of the frustum's full vertical span (`2` in NDC), so `0%` means "exactly on the edge"
 * and a negative value means the frame cuts the subject.
 */
class CharacterCompositionBaselineTest {

    /** The camera `CharacterSession` used to place every exercise under, in asset metres. */
    private val legacyEye = floatArrayOf(0f, 1.05f, 3.4f)
    private val legacyTarget = floatArrayOf(0f, 1.0f, 0f)

    /** The seven exercises the framing audit measures, as the hero asks for them. */
    private val corpus = listOf(
        "pushup_standard", "row_standard", "birddog_reps", "plank_standard",
        "glute_bridge_standard", "squat_standard", "superman_prone"
    )

    private val aspects = listOf(0.45f, 0.5625f, 1.0f, 1.333f)

    private val definition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(definition)

    /**
     * The NDC box ([minX], [maxX], [minY], [maxY]) every joint of [id] occupies under the legacy
     * camera on a surface of [aspect], swept over the whole motion cycle.
     */
    private fun legacyBounds(id: String, aspect: Float): FloatArray {
        val config = PoseRegistry.getPoseConfig(id) ?: error("no pose registered for $id")
        val pipeline = SkeletonPipeline(definition)
        val sides = if (config.alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val point = FloatArray(3)
        for (side in sides) {
            for (i in 0..PHASES) {
                val pose = pipeline.produceFrame(
                    config.builder,
                    PoseContext(progress = i.toFloat() / PHASES, side = side, definition = definition)
                ).pose
                for (joint in Joint.entries) {
                    X1Bind.toAssetInto(pose.joints[joint.index], unitsPerMetre, point)
                    val ndc = legacyNdc(point, aspect)
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
     * The legacy camera's projection: a `45`° vertical perspective from [legacyEye] towards
     * [legacyTarget], `+Y` up — the same basis Filament derives from `lookAt`.
     */
    private fun legacyNdc(point: FloatArray, aspect: Float): FloatArray {
        val fx = legacyTarget[0] - legacyEye[0]
        val fy = legacyTarget[1] - legacyEye[1]
        val fz = legacyTarget[2] - legacyEye[2]
        val length = sqrt(fx * fx + fy * fy + fz * fz)
        val forward = floatArrayOf(fx / length, fy / length, fz / length)
        // right = normalize(cross(forward, +Y)) = (-fz, 0, fx) / |..|
        val lateral = sqrt(forward[2] * forward[2] + forward[0] * forward[0])
        val right = floatArrayOf(-forward[2] / lateral, 0f, forward[0] / lateral)
        val up = floatArrayOf(
            right[1] * forward[2] - right[2] * forward[1],
            right[2] * forward[0] - right[0] * forward[2],
            right[0] * forward[1] - right[1] * forward[0]
        )
        val dx = point[0] - legacyEye[0]
        val dy = point[1] - legacyEye[1]
        val dz = point[2] - legacyEye[2]
        val depth = dx * forward[0] + dy * forward[1] + dz * forward[2]
        val x = dx * right[0] + dy * right[1] + dz * right[2]
        val y = dx * up[0] + dy * up[1] + dz * up[2]
        val tan = tan(Math.toRadians(FOV_VERTICAL_DEGREES / 2.0)).toFloat()
        return floatArrayOf(x / (depth * tan * aspect), y / (depth * tan))
    }

    /** The lowest drawn joint's distance to the bottom edge, as a percentage of the NDC span. */
    private fun bottomMarginPercent(bounds: FloatArray): Float = (bounds[2] + 1f) * 50f

    @Test
    fun everyExerciseAndAspectIsMeasuredOverTheWholeCycle() {
        var evaluations = 0
        for (aspect in aspects) {
            for (id in corpus) {
                val bounds = legacyBounds(id, aspect)
                assertTrue(
                    "$id @ $aspect: the measurement is degenerate ($bounds)",
                    bounds.all { it.isFinite() }
                )
                evaluations++
            }
        }
        assertEquals("every exercise must be measured on every aspect", corpus.size * aspects.size, evaluations)
    }

    @Test
    fun supermanProneRunsPastTheBottomFrustumEdge() {
        for (aspect in aspects) {
            val bounds = legacyBounds("superman_prone", aspect)
            assertTrue(
                "superman_prone @ $aspect must overflow the bottom edge under the legacy camera " +
                    "(measured NDC y min ${bounds[2]}) — this is the defect the framing exists for",
                bounds[2] < -1f
            )
            assertEquals(
                "superman_prone @ $aspect: the overflow this baseline records moved",
                -1.0415f.toDouble(), bounds[2].toDouble(), 0.002
            )
            assertEquals(
                "superman_prone @ $aspect: the bottom margin this baseline records moved",
                -2.076f.toDouble(), bottomMarginPercent(bounds).toDouble(), 0.1
            )
        }
    }

    @Test
    fun pushupStandardSitsOnTheBottomEdge() {
        for (aspect in aspects) {
            val bounds = legacyBounds("pushup_standard", aspect)
            assertTrue(
                "pushup_standard @ $aspect must stay inside the bottom edge while sitting on it " +
                    "(measured NDC y min ${bounds[2]})",
                bounds[2] > -1f
            )
            assertTrue(
                "pushup_standard @ $aspect: the frame must leave under 0.5% of its vertical span below " +
                    "the figure (measured ${bottomMarginPercent(bounds)}%)",
                abs(bottomMarginPercent(bounds)) < 0.5f
            )
        }
    }

    @Test
    fun theLegacyCameraLeavesAFixedCompositionForEveryPose() {
        // The widest pose of the corpus at the narrowest viewport, the standing pose at the widest:
        // one camera cannot compose both, which is why the frame had to be measured rather than fixed.
        val widest = legacyBounds("pushup_standard", 0.45f)
        val standing = legacyBounds("squat_standard", 1.0f)
        assertEquals("pushup_standard @ 0.45: horizontal extent moved", 0.6758f.toDouble(), widest[1].toDouble(), 0.002)
        assertEquals("squat_standard @ 1.0: vertical extent moved", 0.4132f.toDouble(), standing[3].toDouble(), 0.002)
        assertTrue(
            "the fixed camera must spend under 40% of the narrow viewport's width on the widest pose " +
                "(measured ${widest[1]})",
            widest[1] < 0.8f
        )
    }

    private companion object {
        /** The lens `CharacterSession` configured the fixed camera with. */
        const val FOV_VERTICAL_DEGREES = 45.0

        /** Samples across `[0, 1]` per side: `1/64` of the rep. */
        const val PHASES = 64
    }
}
