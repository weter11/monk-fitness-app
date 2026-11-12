package com.monkfitness.app

import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.CameraFraming
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.ProjectedSkeleton
import com.monkfitness.app.animation.ScreenSpaceCompensation
import com.monkfitness.app.animation.ScreenSpaceScale
import com.monkfitness.app.animation.ScreenSpaceSettings
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonEngine
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonProjector
import com.monkfitness.app.animation.SkeletonStyle
import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The safe band contains the rig for the WHOLE animation cycle at every camera azimuth the manual
 * rotation can reach. The hero derives [CameraFraming.exerciseFrame] for the safe-band surface (not
 * the whole hero), so the athlete with its ground contact is drawn only between the two overlay zones.
 *
 * This is the geometry that makes "the overlay must never cover the exercise" a measured fact, tested
 * against the registry's animated exercises for the standing, lying and wide-range cases.
 */
class HeroSafeBandFramingTest {

    private val engine = SkeletonEngine(DEF, STYLE)
    private val projector = SkeletonProjector()
    private val compensator = ScreenSpaceCompensation(SETTINGS)
    private val framing = CameraFraming(engine, SETTINGS)

    /** Representative cases, named by the posture they hold over the rep. */
    private val corpus = mapOf(
        "standing" to listOf("AirSquatPose", "SquatPose", "StepUpPose", "StandardPullUpPose"),
        "lying" to listOf("DeadBugPose", "GluteBridgePose", "LegRaisePose", "SupermanPose"),
        "wide-range" to listOf("BurpeePose", "CossackSquatPose", "StandardPushUpPose", "AlternatingForwardLungesPose")
    )

    private val phases = (0..16).map { it / 16f }
    /** The +/- 90 degrees the drag gesture's clamp and the pose's yaw declaration allow. */
    private val yawOffsets = listOf(-1.5708f, -0.7854f, 0f, 0.7854f, 1.5708f)
    private val alternateSides = setOf("StaticBirdDogHoldPose", "AlternatingBirdDogPose")

    /**
     * Safe-band surfaces at the hero's aspect ratio, derived from the real [HeroGeometry] caps for a
     * representative window and three text scales. Width is a 325 dp hero at a 2.6x density.
     */
    private fun safeBandSurfaces(): List<Pair<Float, Float>> {
        val windowHeightDp = 760f
        return listOf(1.0f, 1.3f, 1.5f).map { fontScale ->
            val heroHeight = HeroGeometry.sessionHeroHeightDp(
                windowHeightDp = windowHeightDp,
                headerDp = 44f * fontScale,
                controlsDp = 132f * fontScale,
                fontScale = fontScale
            )
            val zones = HeroGeometry.overlayZones(heroHeight, 64f * fontScale, 56f * fontScale)
            val band = HeroGeometry.safeBand(heroHeight, zones)
            // dp -> px at the density the existing framing audit used for the hero (861 px / 325 dp).
            val density = 861f / 325f
            (325f * density) to (band.height * density)
        }
    }

    private fun sidesOf(name: String): List<Side> =
        if (name in alternateSides) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)

    private fun publishedFrame(name: String, progress: Float, side: Side, pipeline: SkeletonPipeline): SkeletonPose =
        SkeletonPose().apply {
            copyFrom(
                pipeline.produceFrame(
                    MotionProbe.build(name),
                    PoseContext(progress = progress, side = side, definition = DEF)
                ).pose
            )
        }

    /** Everything the renderers actually stroke (bones, discs, faces) plus the ground contact. */
    private fun drawnBox(buffer: ProjectedSkeleton, zoom: Float, withGround: Boolean): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var stroke = 0f
        val scale = ScreenSpaceScale()

        for (i in 0 until buffer.boneCount) {
            val b = buffer.bones[i]
            compensator.computeScale(b.p1, zoom, scale)
            val half = b.thickness * scale.thicknessScale * 0.5f
            minX = min(minX, minOf(b.p1.x, b.p2.x) - half)
            maxX = max(maxX, maxOf(b.p1.x, b.p2.x) + half)
            minY = min(minY, minOf(b.p1.y, b.p2.y) - half)
            maxY = max(maxY, maxOf(b.p1.y, b.p2.y) + half)
            stroke = max(stroke, STYLE.outlineWidth * scale.outlineScale)
        }
        for (j in buffer.indicators) {
            compensator.computeScale(j.point, zoom, scale)
            val radius = (if (j.id == Joint.HEAD_POS) STYLE.headRadius else STYLE.jointRadius) * scale.radiusScale
            minX = min(minX, j.point.x - radius)
            maxX = max(maxX, j.point.x + radius)
            minY = min(minY, j.point.y - radius)
            maxY = max(maxY, j.point.y + radius)
            stroke = max(stroke, 2f * scale.outlineScale)
        }
        for (i in 0 until buffer.faceCount) {
            for (p in buffer.faces[i].points) {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
                stroke = max(stroke, STYLE.outlineWidth * 0.5f * scale.outlineScale)
            }
        }
        if (withGround) {
            for (p in buffer.shadowPoints) {
                compensator.computeScale(p, zoom, scale)
                val rx = STYLE.shadowRadiusX * scale.shadowScale
                val ry = STYLE.shadowRadiusY * scale.shadowScale
                minX = min(minX, p.x - rx); maxX = max(maxX, p.x + rx)
                minY = min(minY, p.y - ry); maxY = max(maxY, p.y + ry)
            }
        }
        return floatArrayOf(minX - stroke, maxX + stroke, minY - stroke, maxY + stroke)
    }

    @Test
    fun theWholeMotionStaysInsideTheSafeBandAtEveryReachableYaw() {
        val slack = 0.5f // the sampling margin is a fraction of a pixel at this resolution
        var checked = 0
        for ((category, names) in corpus) {
            for (name in names) {
                val definition = MotionProbe.build(name).metadata.camera
                for ((width, bandHeight) in safeBandSurfaces()) {
                    // The frame is derived ONCE for this safe-band surface, as the hero does.
                    val frame = framing.exerciseFrame(
                        camera = Camera(definition),
                        builder = MotionProbe.build(name),
                        sides = sidesOf(name),
                        width = width,
                        height = bandHeight
                    )
                    for (side in sidesOf(name)) {
                        val pipeline = SkeletonPipeline(DEF)
                        for (progress in phases) {
                            val pose = publishedFrame(name, progress, side, pipeline)
                            for (offset in yawOffsets) {
                                val camera = Camera(definition)
                                camera.yaw = definition.defaultYaw + offset
                                frame.applyTo(camera)
                                val buffer = ProjectedSkeleton()
                                projector.project(
                                    pose, camera, engine, width, bandHeight, buffer, pose.environment.ground.level
                                )
                                val box = drawnBox(buffer, camera.zoom, withGround = true)
                                // Contained in the safe band's LOCAL coordinate space [0, bandHeight].
                                assertTrue(
                                    "$category/$name progress=$progress yaw=$offset: left edge ${box[0]} < 0",
                                    box[0] >= -slack
                                )
                                assertTrue(
                                    "$category/$name progress=$progress yaw=$offset: right edge ${box[1]} > $width",
                                    box[1] <= width + slack
                                )
                                assertTrue(
                                    "$category/$name progress=$progress yaw=$offset: top edge ${box[2]} < 0 (rig under top overlay)",
                                    box[2] >= -slack
                                )
                                assertTrue(
                                    "$category/$name progress=$progress yaw=$offset: bottom edge ${box[3]} > $bandHeight (rig under bottom overlay)",
                                    box[3] <= bandHeight + slack
                                )
                                checked++
                            }
                        }
                    }
                }
            }
        }
        // Non-vacuity: every case × surface × phase × yaw evaluated (one side per case here).
        val expected = corpus.values.sumOf { it.size } * safeBandSurfaces().size *
            phases.size * yawOffsets.size
        assertEquals("the safe-band sweep must not be vacuous", expected, checked)
    }

    @Test
    fun theFrameIsFixedForTheWholeRepAndUsesTheSafeBand() {
        for ((width, bandHeight) in safeBandSurfaces()) {
            for (name in corpus.values.flatten()) {
                val definition = MotionProbe.build(name).metadata.camera
                val frame = framing.exerciseFrame(
                    Camera(definition), MotionProbe.build(name), sidesOf(name), width, bandHeight
                )
                assertTrue("$name: the frame may not exceed the authored zoom", frame.zoom <= definition.defaultZoom)
                // Applying the frame touches zoom/anchor alone; yaw (rotation) is untouched.
                val camera = Camera(definition)
                val yaw = camera.yaw
                frame.applyTo(camera)
                assertEquals("the fixed frame must not rotate the camera", yaw, camera.yaw, 0f)
            }
        }
    }

    private companion object {
        val DEF = SkeletonDefinition.DEFAULT_ADULT
        val STYLE = SkeletonStyle.DEFAULT
        val SETTINGS = ScreenSpaceSettings.DEFAULT
    }
}
