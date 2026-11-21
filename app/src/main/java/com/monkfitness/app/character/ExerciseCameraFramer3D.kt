package com.monkfitness.app.character

import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.Vector3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where a 3D camera stands and what it looks at: [eye] sees [target] with `+Y` tilted to [up] and
 * holds [fovVerticalDegrees] vertically. Filament needs nothing else to place a perspective view.
 */
data class CameraView3D(
    val eyeX: Float,
    val eyeY: Float,
    val eyeZ: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float,
    val fovVerticalDegrees: Float
)

/**
 * The one 3D camera an exercise is shot with: a pure function of (exercise, viewport aspect) that
 * samples the exercise's whole motion cycle once and solves the view around it.
 *
 * The viewpoint is the pose's own [CameraDefinition] `yaw`/`pitch`, read on the axes
 * `animation.Camera` projects with — `yaw` an azimuth about `+Y`, `pitch` an elevation. The camera
 * stands opposite `towards`, on the side the subject faces: a real camera there views the figure
 * unmirrored, where reusing the 2D screen axes end to end would show it left-right reversed. `pitch`
 * therefore reads as elevation, positive above the subject, which is where the fixed view this
 * replaces stood; the screen-up axis is the 2D projection's own, so a flatter authored `pitch` tilts
 * the 3D frame the same way it tilts the 2D one.
 *
 * The subject is every joint over every sampled phase of every side the exercise plays — in asset
 * metres, through [X1Bind] — each grown by [Settings.bodyEnvelopeMetres], so a limb's surface, the
 * crown above the head joint and the contact shadow under a foot all sit inside the measured
 * envelope. The envelope is measured on the camera's own axes rather than on the corners of a
 * world-axis box: an obliquely viewed world box projects up to its diagonal onto the screen, so the
 * frame would be sized by corners the body never reaches — measured on this corpus, that left a
 * push-up at `14%` of the frame's height. The joint envelope, by contrast, fills `88%` of the
 * limiting axis by construction.
 *
 * Because the camera is derived from the whole cycle and from nothing else, it cannot breathe within
 * a rep, and re-deriving it after the rep has played returns the same numbers.
 */
class ExerciseCameraFramer3D(
    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT,
    private val settings: Settings = Settings()
) {

    /**
     * @param samples phases sampled across `[0, 1]`, per side — 32 is one sample per 3% of the rep.
     * @param bodyEnvelopeMetres radial padding around every sampled joint.
     * @param paddingFactor how far past the exact fitting distance the camera is pulled back, so the
     *   subject fills the limiting axis without touching its edge. `1.13` puts the widest point at
     *   `0.885` of the half-extent.
     * @param fovVerticalDegrees the lens the runtime projects with.
     */
    data class Settings(
        val samples: Int = 32,
        val bodyEnvelopeMetres: Float = 0.12f,
        val paddingFactor: Float = 1.13f,
        val fovVerticalDegrees: Float = 45f,
        val minimumDistanceMetres: Float = 0.5f
    )

    /** The sweep's own pipeline, built per measurement; the renderer's is never touched. */
    private val unitsPerMetre: Float = X1Bind.unitsPerMetre(definition)
    private val point = FloatArray(3)

    /** Every sampled joint as `x, y, z` triples in asset metres — one cycle's worth, reused. */
    private var samples = FloatArray(3 * Joint.entries.size * 64)
    private var sampleCount = 0

    /**
     * The view [builder] is shot with on a surface of [aspect] (`width / height`): the authored
     * viewpoint, aimed at the centre of the motion's projected extents, far enough out that the
     * envelope's nearest and farthest points alike stay inside the frustum, then pulled back by
     * [Settings.paddingFactor].
     */
    fun frame(builder: PoseBuilder, alternating: Boolean, aspect: Float): CameraView3D {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 1f

        val declaration = builder.metadata.camera
        // A declaration that leaves both angles at zero is not a viewpoint — it is the unset
        // definition, which would shoot the figure dead-on at eye level — so the runtime's own
        // default 3/4 view stands in for it.
        val unset = declaration.defaultYaw == 0f && declaration.defaultPitch == 0f
        val yaw = if (unset) CameraDefinition.DEFAULT.defaultYaw else declaration.defaultYaw
        val pitch = if (unset) CameraDefinition.DEFAULT.defaultPitch else declaration.defaultPitch

        // The basis `animation.Camera.project` implies: `right` is its screen x, `up` its screen y,
        // `towards` the axis its depth cue grows along. Rotating them through the asset basis puts
        // them in the metres the mesh is skinned in; the camera stands opposite `towards`.
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val right = X1Bind.toAssetDirection(Vector3(cy, 0f, sy))
        val up = X1Bind.toAssetDirection(Vector3(-sy * sp, cp, cy * sp))
        val towards = X1Bind.toAssetDirection(Vector3(-sy * cp, -sp, cy * cp))

        sample(builder, alternating)

        val pad = settings.bodyEnvelopeMetres
        var minR = Float.MAX_VALUE
        var maxR = -Float.MAX_VALUE
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minD = Float.MAX_VALUE
        var maxD = -Float.MAX_VALUE
        var i = 0
        while (i < sampleCount) {
            val x = samples[i]
            val y = samples[i + 1]
            val z = samples[i + 2]
            val r = x * right.x + y * right.y + z * right.z
            val u = x * up.x + y * up.y + z * up.z
            val d = x * towards.x + y * towards.y + z * towards.z
            if (r - pad < minR) minR = r - pad
            if (r + pad > maxR) maxR = r + pad
            if (u - pad < minU) minU = u - pad
            if (u + pad > maxU) maxU = u + pad
            if (d - pad < minD) minD = d - pad
            if (d + pad > maxD) maxD = d + pad
            i += 3
        }

        val midR = (maxR + minR) * 0.5f
        val midU = (maxU + minU) * 0.5f
        val midD = (maxD + minD) * 0.5f

        val tanV = tan(Math.toRadians(settings.fovVerticalDegrees / 2.0)).toFloat()
        val tanH = tanV * safeAspect

        // A point in front of the aim point is nearer the camera than one at its depth, so the fit is
        // solved per point: what it needs is the depth its lateral offset subtends at the aim plane,
        // minus its own offset toward the camera. With no depth extent this is the flat
        // `max(halfU / tanV, halfR / tanH)`.
        var needed = 0f
        i = 0
        while (i < sampleCount) {
            val x = samples[i]
            val y = samples[i + 1]
            val z = samples[i + 2]
            val a = abs(x * right.x + y * right.y + z * right.z - midR) + pad
            val b = abs(x * up.x + y * up.y + z * up.z - midU) + pad
            val c = x * towards.x + y * towards.y + z * towards.z - midD - pad
            needed = max(needed, max(a / tanH - c, b / tanV - c))
            i += 3
        }

        val distance = max(needed * settings.paddingFactor, settings.minimumDistanceMetres)
        val targetX = right.x * midR + up.x * midU + towards.x * midD
        val targetY = right.y * midR + up.y * midU + towards.y * midD
        val targetZ = right.z * midR + up.z * midU + towards.z * midD

        return CameraView3D(
            eyeX = targetX - towards.x * distance,
            eyeY = targetY - towards.y * distance,
            eyeZ = targetZ - towards.z * distance,
            targetX = targetX,
            targetY = targetY,
            targetZ = targetZ,
            upX = up.x,
            upY = up.y,
            upZ = up.z,
            fovVerticalDegrees = settings.fovVerticalDegrees
        )
    }

    /** [frame] for a surface of [width] x [height] pixels. */
    fun frame(builder: PoseBuilder, alternating: Boolean, width: Int, height: Int): CameraView3D =
        frame(builder, alternating, if (height > 0) width.toFloat() / height else 1f)

    /**
     * Plays [builder]'s whole motion, recording every joint in asset metres. The pipeline is
     * constructed per call and its history is reset first: a pipeline that has already played — even
     * one whose history was cleared — leaves the last fractions of a millimetre of its settlement
     * state in the next frame, which would make the view depend on how often it had been framed.
     * A cold sweep is the same sweep every time.
     */
    private fun sample(builder: PoseBuilder, alternating: Boolean) {
        sampleCount = 0
        val pipeline = SkeletonPipeline(definition)
        pipeline.resetHistory()
        // The first frames a builder publishes are centimetres away from every later one — its own
        // first pass settles. Warming the pair before measuring is what makes two framings of the
        // same exercise agree, and a view that changed because the app framed it twice would be the
        // same breathing this class exists to remove.
        repeat(WARM_UP_FRAMES) {
            pipeline.produceFrame(
                builder,
                PoseContext(progress = 0.5f, side = Side.RIGHT, definition = definition)
            )
        }
        pipeline.resetHistory()
        val sides = if (alternating) BOTH_SIDES else ONE_SIDE
        for (side in sides) {
            for (i in 0..settings.samples) {
                val context = PoseContext(
                    progress = i.toFloat() / settings.samples,
                    side = side,
                    definition = definition
                )
                val pose = pipeline.produceFrame(builder, context).pose
                for (joint in Joint.entries) {
                    X1Bind.toAssetInto(pose.joints[joint.index], unitsPerMetre, point)
                    if (sampleCount + 3 > samples.size) samples = samples.copyOf(samples.size * 2)
                    samples[sampleCount] = point[0]
                    samples[sampleCount + 1] = point[1]
                    samples[sampleCount + 2] = point[2]
                    sampleCount += 3
                }
            }
        }
    }

    private companion object {
        val ONE_SIDE = listOf(Side.RIGHT)
        val BOTH_SIDES = listOf(Side.RIGHT, Side.LEFT)

        /**
         * Frames played and discarded before measuring, so the sweep starts from a settled builder.
         * Eleven is the engine's own cold-start fixed point, the count the invariant sweep and the
         * retarget fixtures also warm by; a different count measures a different, still-settling
         * builder.
         */
        const val WARM_UP_FRAMES = 11
    }
}
