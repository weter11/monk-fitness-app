package com.monkfitness.app

import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.CameraFrame
import com.monkfitness.app.animation.CameraFraming
import com.monkfitness.app.animation.FIT_ONLY_ZOOM_CEILING
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.ProjectedPoint
import com.monkfitness.app.animation.ProjectedSkeleton
import com.monkfitness.app.animation.ScreenSpaceCompensation
import com.monkfitness.app.animation.ScreenSpaceScale
import com.monkfitness.app.animation.ScreenSpaceSettings
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonEngine
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonProjector
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonStyle
import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * The media hero's own contract, measured through the production projection path: the rig it draws
 * fills the surface, it is at least twice the small preview it replaced, it is not clipped at the
 * authored camera, and the rotation stops before the hero would crop it.
 *
 * The numbers are the handset's: a 325 dp-wide screen at density 502 gives 919 px of content width and
 * 2194 px (699 dp) of content area, which is the denominator of the hero's 45 % cap.
 */
class HeroFitTest {

    private val engine = SkeletonEngine(DEF, STYLE)
    private val framing = CameraFraming(engine, SETTINGS)
    private val projector = SkeletonProjector()
    private val compensator = ScreenSpaceCompensation(SETTINGS)
    private val scale = ScreenSpaceScale()

    @Test
    fun theRigFillsTheHeroAtEveryRoomTheLayoutCanGiveIt() {
        // The room the info block and the pinned controls leave is measured on the device; sweeping it
        // asserts the rule holds wherever the layout lands, not at one pinned measurement.
        val rooms = listOf(HeroGeometry.MIN_HEIGHT_DP, 240f, 279f, CONTENT_HEIGHT_PX / DENSITY * HeroGeometry.MAX_CONTENT_FRACTION)

        for (family in FAMILIES) {
            for (room in rooms) {
                val m = measure(family, room)
                val share = if (family.wideLying) m.sharesOfWidth else m.sharesOfHeight
                val required = if (family.wideLying) 0.85f else HeroGeometry.RIG_HEIGHT_FRACTION
                assertTrue(
                    "${family.name} at room=$room dp: the rig is ${m.rigWidthPx}x${m.rigHeightPx} px in a " +
                        "${m.heroWidthPx}x${m.heroHeightPx} px hero — ${"%.3f".format(share)} of the " +
                        "dimension it is measured on, below $required",
                    share >= required - 0.001f
                )
                assertTrue(
                    "${family.name} at room=$room dp: the rig must be at least twice the small preview " +
                        "it replaced (${m.rigWidthPx}x${m.rigHeightPx} vs ${m.previewWidthPx}x${m.previewHeightPx})",
                    m.previewRatio >= 2.0f
                )
                assertTrue(
                    "${family.name} at room=$room dp: the frame must not clip the rig at the authored camera",
                    m.containedAtDefault
                )
            }
        }
    }

    @Test
    fun theHeroStaysInsideItsShareOfTheContentArea() {
        val contentHeightDp = CONTENT_HEIGHT_PX / DENSITY
        for (family in FAMILIES) {
            for (room in listOf(HeroGeometry.MIN_HEIGHT_DP, 279f, contentHeightDp)) {
                val hero = measure(family, room)
                val share = hero.heroHeightPx / CONTENT_HEIGHT_PX
                assertTrue(
                    "${family.name} at room=$room dp: the hero is ${"%.3f".format(share)} of the content area",
                    share <= HeroGeometry.MAX_CONTENT_FRACTION + 0.001f
                )
                assertTrue(
                    "${family.name}: the hero may never be shorter than the floor",
                    hero.heroHeightPx >= HeroGeometry.MIN_HEIGHT_DP * DENSITY - 0.5f
                )
            }
        }
    }

    @Test
    fun rotationStopsBeforeTheHeroCropsTheRig() {
        for (family in FAMILIES) {
            val m = measure(family, ROOM_AT_FONT_1)
            // At or below the floor the drag still turns: a hero too tight to rotate is worse than a
            // cropped extreme view, so the floor is what the user gets and the crop is reported.
            val limit = HeroGeometry.yawLimit(m.containedYaw, m.declaredReach)
            assertTrue(
                "${family.name}: the rotation must never be blocked below the floor (limit=$limit)",
                limit >= HeroGeometry.MIN_YAW_LIMIT - 1e-4f || limit >= m.declaredReach - 1e-4f
            )
            if (m.containedYaw >= HeroGeometry.MIN_YAW_LIMIT) {
                assertTrue(
                    "${family.name}: a rotation inside the limit must not crop the rig " +
                        "(limit=${Math.toDegrees(m.containedYaw.toDouble()).toFloat()} deg, " +
                        "crop at 45 deg=${m.cropAtFortyFive} px)",
                    m.cropAtFortyFive <= 2f
                )
            } else {
                // Reported, not asserted away: the hero crops this pose's extreme view by design.
                println(
                    "rotation clamp ${family.name}: contained=+" +
                        "${Math.toDegrees(m.containedYaw.toDouble()).toFloat()} deg, kept=" +
                        "${Math.toDegrees(limit.toDouble()).toFloat()} deg, crop at 45 deg=" +
                        "${m.cropAtFortyFive} px per side"
                )
            }
        }
    }

    /** The measured table, printed so the report quotes the instrument rather than a hand reading. */
    @Test
    fun reportTheMeasuredTable() {
        for (family in FAMILIES) {
            for (room in listOf(HeroGeometry.MIN_HEIGHT_DP, ROOM_AT_FONT_1)) {
                val m = measure(family, room)
                println(
                    "hero ${family.name} room=$room: hero=${"%.1f".format(m.heroHeightPx)}px " +
                        "rig=${"%.0f".format(m.rigWidthPx)}x${"%.0f".format(m.rigHeightPx)} " +
                        "ofH=${"%.3f".format(m.sharesOfHeight)} ofW=${"%.3f".format(m.sharesOfWidth)} " +
                        "preview=${"%.0f".format(m.previewWidthPx)}x${"%.0f".format(m.previewHeightPx)} " +
                        "ratio=${"%.2f".format(m.previewRatio)} contained=${m.containedAtDefault} " +
                        "yawLimit=${Math.toDegrees(m.containedYaw.toDouble()).toFloat()}deg " +
                        "cropAt45=${m.cropAtFortyFive}px"
                )
            }
        }
    }

    /** One pose family measured at one layout: everything the criteria are read from. */
    private data class Measurement(
        val heroWidthPx: Float,
        val heroHeightPx: Float,
        val rigWidthPx: Float,
        val rigHeightPx: Float,
        val previewWidthPx: Float,
        val previewHeightPx: Float,
        val previewRatio: Float,
        val containedAtDefault: Boolean,
        val containedYaw: Float,
        val declaredReach: Float,
        val cropAtFortyFive: Float
    ) {
        val sharesOfHeight: Float get() = rigHeightPx / heroHeightPx
        val sharesOfWidth: Float get() = rigWidthPx / heroWidthPx
    }

    private fun measure(family: Family, roomDp: Float): Measurement {
        val builder = MotionProbe.build(family.pose)
        val declaration = builder.metadata.camera
        val authored = Camera(declaration)
        val sides = listOf(Side.RIGHT)

        val extent = framing.rigExtent(
            camera = authored,
            builder = builder,
            sides = sides,
            fitReachableYaws = false,
            withMargin = false
        )
        val widthFit = (CONTENT_WIDTH_PX - 2f * extent.stroke) / extent.width
        val rigHeightPx = extent.figureHeight * widthFit + 2f * extent.stroke

        val heroDp = HeroGeometry.heightDp(
            rigHeightPx = rigHeightPx,
            density = DENSITY,
            contentHeightDp = CONTENT_HEIGHT_PX / DENSITY,
            roomDp = roomDp
        )
        val heroHeightPx = heroDp * DENSITY

        val frame = framing.exerciseFrame(
            camera = Camera(declaration),
            builder = builder,
            sides = sides,
            width = CONTENT_WIDTH_PX,
            height = heroHeightPx,
            ceiling = FIT_ONLY_ZOOM_CEILING,
            fitReachableYaws = false
        )

        // The same pose on the preview the hero replaced: the content width, and the 80 dp the old card
        // gave it, framed by the same rule but with the pose's own zoom as its ceiling.
        val previewHeightPx = 80f * DENSITY
        val previewFrame = framing.exerciseFrame(
            camera = Camera(declaration),
            builder = builder,
            sides = sides,
            width = PREVIEW_WIDTH_PX,
            height = previewHeightPx
        )

        val drawn = unionBox(builder, sides, declaration, frame, CONTENT_WIDTH_PX, heroHeightPx, ground = false)
        val preview = unionBox(builder, sides, declaration, previewFrame, PREVIEW_WIDTH_PX, previewHeightPx, ground = false)
        val rigWidthPx = drawn[1] - drawn[0]
        val rigHeightPxDrawn = drawn[3] - drawn[2]
        val previewWidthPx = preview[1] - preview[0]
        val previewHeightPxDrawn = preview[3] - preview[2]

        return Measurement(
            heroWidthPx = CONTENT_WIDTH_PX,
            heroHeightPx = heroHeightPx,
            rigWidthPx = rigWidthPx,
            rigHeightPx = rigHeightPxDrawn,
            previewWidthPx = previewWidthPx,
            previewHeightPx = previewHeightPxDrawn,
            previewRatio = minOf(rigWidthPx / previewWidthPx, rigHeightPxDrawn / previewHeightPxDrawn),
            // Containment is the frame's own claim, and the frame reserves the ground contact too.
            containedAtDefault = run {
                val box = unionBox(builder, sides, declaration, frame, CONTENT_WIDTH_PX, heroHeightPx, ground = true)
                box[0] >= -SLACK && box[1] <= CONTENT_WIDTH_PX + SLACK &&
                    box[2] >= -SLACK && box[3] <= heroHeightPx + SLACK
            },
            containedYaw = framing.containedYawLimit(
                camera = Camera(declaration),
                builder = builder,
                sides = sides,
                frame = frame,
                width = CONTENT_WIDTH_PX,
                height = heroHeightPx
            ),
            declaredReach = minOf(-declaration.minYaw, declaration.maxYaw),
            cropAtFortyFive = cropAt(builder, sides, declaration, frame, CONTENT_WIDTH_PX, heroHeightPx, FORTY_FIVE)
        )
    }

    /** How far the rig leaves the hero, per side, at one yaw offset. */
    private fun cropAt(
        builder: PoseBuilder,
        sides: List<Side>,
        declaration: CameraDefinition,
        frame: CameraFrame,
        width: Float,
        height: Float,
        yawOffset: Float
    ): Float {
        var worst = 0f
        val pipeline = SkeletonPipeline(DEF)
        for (side in sides) {
            for (i in 0..MOTION_SAMPLES) {
                val context = PoseContext(
                    progress = i.toFloat() / MOTION_SAMPLES,
                    side = side,
                    definition = DEF
                )
                val pose = pipeline.produceFrame(builder, context).pose
                val camera = cameraFor(declaration, declaration.defaultYaw + yawOffset, frame)
                val box = drawnBox(pose, camera, width, height, ground = true)
                worst = max(worst, max(max(-box[0], box[1] - width), max(-box[2], box[3] - height)))
            }
        }
        return if (worst <= SLACK) 0f else worst
    }

    private fun unionBox(
        builder: PoseBuilder,
        sides: List<Side>,
        declaration: CameraDefinition,
        frame: CameraFrame,
        width: Float,
        height: Float,
        ground: Boolean
    ): FloatArray {
        val union = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        val pipeline = SkeletonPipeline(DEF)
        for (side in sides) {
            for (i in 0..MOTION_SAMPLES) {
                val context = PoseContext(
                    progress = i.toFloat() / MOTION_SAMPLES,
                    side = side,
                    definition = DEF
                )
                val pose = pipeline.produceFrame(builder, context).pose
                val box = drawnBox(pose, cameraFor(declaration, declaration.defaultYaw, frame), width, height, ground)
                union[0] = minOf(union[0], box[0])
                union[1] = maxOf(union[1], box[1])
                union[2] = minOf(union[2], box[2])
                union[3] = maxOf(union[3], box[3])
            }
        }
        return union
    }

    private fun cameraFor(declaration: CameraDefinition, yaw: Float, frame: CameraFrame): Camera {
        val camera = Camera()
        camera.yaw = yaw
        camera.pitch = declaration.defaultPitch
        frame.applyTo(camera)
        return camera
    }

    /**
     * Everything the renderers stroke — bones, indicator discs and torso faces — plus, when [ground]
     * is set, the contact shadows the frame also reserves. The figure alone is what a screenshot
     * shows, so that is what the fill criteria are read from.
     */
    private fun drawnBox(pose: SkeletonPose, camera: Camera, width: Float, height: Float, ground: Boolean): FloatArray {
        val buffer = ProjectedSkeleton()
        projector.project(pose, camera, engine, width, height, buffer, pose.environment.ground.level)
        val box = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)

        fun include(x0: Float, x1: Float, y0: Float, y1: Float) {
            box[0] = minOf(box[0], x0)
            box[1] = maxOf(box[1], x1)
            box[2] = minOf(box[2], y0)
            box[3] = maxOf(box[3], y1)
        }

        for (i in 0 until buffer.boneCount) {
            val bone = buffer.bones[i]
            compensator.computeScale(bone.p1, camera.zoom, scale)
            val half = bone.thickness * scale.thicknessScale * 0.5f
            val outline = STYLE.outlineWidth * scale.outlineScale
            include(
                minOf(bone.p1.x, bone.p2.x) - half - outline, maxOf(bone.p1.x, bone.p2.x) + half + outline,
                minOf(bone.p1.y, bone.p2.y) - half - outline, maxOf(bone.p1.y, bone.p2.y) + half + outline
            )
        }
        for (indicator in buffer.indicators) {
            compensator.computeScale(indicator.point, camera.zoom, scale)
            val radius = (if (indicator.id == Joint.HEAD_POS) STYLE.headRadius else STYLE.jointRadius) *
                scale.radiusScale + 2f * scale.outlineScale
            include(
                indicator.point.x - radius, indicator.point.x + radius,
                indicator.point.y - radius, indicator.point.y + radius
            )
        }
        for (i in 0 until buffer.faceCount) {
            val half = STYLE.outlineWidth * 0.5f
            for (p in buffer.faces[i].points) include(p.x - half, p.x + half, p.y - half, p.y + half)
        }
        if (ground) {
            val shadows: Array<ProjectedPoint> = buffer.shadowPoints
            for (p in shadows) {
                compensator.computeScale(p, camera.zoom, scale)
                val rx = STYLE.shadowRadiusX * scale.shadowScale
                val ry = STYLE.shadowRadiusY * scale.shadowScale
                include(p.x - rx, p.x + rx, p.y - ry, p.y + ry)
            }
        }
        return box
    }

    private enum class Family(val pose: String, val wideLying: Boolean) {
        STANDING_SQUAT("AirSquatPose", false),
        QUADRUPED_CATCOW("CatCowPose", false),
        QUADRUPED_PUSHUP("StandardPushUpPose", false),
        WIDE_LYING_BRIDGE("GluteBridgePose", true),
        WIDE_LYING_TILT("PelvicTiltPose", true)
    }

    private companion object {
        val DEF = SkeletonDefinition.DEFAULT_ADULT
        val STYLE = SkeletonStyle.DEFAULT
        val SETTINGS = ScreenSpaceSettings.DEFAULT

        /** The handset: 325 dp wide at density 502, so content width and content area in pixels. */
        const val DENSITY = 3.1375f
        const val CONTENT_WIDTH_PX = 919f
        const val CONTENT_HEIGHT_PX = 2194f

        /** The preview the hero replaced: the content width and the 80 dp the card gave the demo. */
        const val PREVIEW_WIDTH_PX = 820f

        /** The room left at font 1.0 on the handset, measured (details block + pinned controls). */
        const val ROOM_AT_FONT_1 = 279f

        /**
         * Sampled at the resolution the frame itself is fitted with: a coarser sweep reports a shorter
         * union than the one the frame reserves, and the fill would read low by a few per cent.
         */
        const val MOTION_SAMPLES = 32
        const val FORTY_FIVE = 0.7854f
        const val SLACK = 0.5f

        val FAMILIES = Family.entries.toList()
    }
}
