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
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SkeletonProjector
import com.monkfitness.app.animation.SkeletonStyle
import com.monkfitness.app.animation.YawLimits
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The media hero's own contract, measured through the production projection path: the rig it draws
 * fills the surface, it is at least twice the small preview it replaced, it is not clipped at the
 * authored camera, and the rotation the gesture is allowed is the rotation the hero can draw.
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
                    "${family.name} at room=$room dp: the frame must not clip the figure at the authored camera",
                    m.figureContainedAtDefault
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

    /**
     * The criterion the hero's rotation exists for: at the clamp the figure is whole — nothing of it
     * leaves the hero on either side, in any frame of the rep, at either end of the turn.
     */
    @Test
    fun theRotationClampKeepsTheWholeFigureInsideTheHero() {
        for (family in FAMILIES) {
            val hero = heroFor(family.name, MotionProbe.build(family.pose), family.sides(), ROOM_AT_FONT_1)
            val plus = edgeMargin(hero, hero.limits.positive)
            val minus = edgeMargin(hero, -hero.limits.negative)
            println(
                "clamp ${family.name}: +${degrees(hero.limits.positive)}deg/-${degrees(hero.limits.negative)}deg " +
                    "margin+${"%.1f".format(plus)}px margin-${"%.1f".format(minus)}px"
            )
            for ((offset, margin) in listOf(hero.limits.positive to plus, -hero.limits.negative to minus)) {
                assertTrue(
                    "${family.name}: at yaw offset ${degrees(offset)}deg the nearest edge of the figure is " +
                        "${"%.1f".format(margin)} px outside the hero (margin must be >= 0)",
                    margin >= -SLACK
                )
            }
        }
    }

    /**
     * The same criterion over the whole library, and the per-pose clamp the report quotes: a pose whose
     * silhouette widens fast gets a few degrees, a slim one the gesture's full reach — all of it read
     * off the rig, none of it a table.
     */
    @Test
    fun everyPoseInTheLibraryIsContainedAtBothYawLimits() {
        val ids = PoseRegistry.animationIds.sorted()
        assertTrue("the library must expose its engine poses to this instrument", ids.size >= 60)

        var narrowest = Float.MAX_VALUE
        for (id in ids) {
            val config = PoseRegistry.getPoseConfig(id)
            assertTrue("$id is registered without a pose builder", config != null)
            val sides = if (config!!.alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
            val hero = heroFor(id, config.builder, sides, ROOM_AT_FONT_1)
            val plus = edgeMargin(hero, hero.limits.positive)
            val minus = edgeMargin(hero, -hero.limits.negative)
            narrowest = min(narrowest, min(plus, minus))
            println(
                "library clamp $id: +${degrees(hero.limits.positive)}/-${degrees(hero.limits.negative)}deg " +
                    "minMargin=${"%.1f".format(min(plus, minus))}px"
            )
            for ((offset, margin) in listOf(hero.limits.positive to plus, -hero.limits.negative to minus)) {
                assertTrue(
                    "$id: at yaw offset ${degrees(offset)}deg the figure leaves the hero by " +
                        "${"%.1f".format(-margin)} px (limits=+${degrees(hero.limits.positive)}/" +
                        "-${degrees(hero.limits.negative)}deg)",
                    margin >= -SLACK
                )
            }
        }
        println("library clamp: narrowest margin over ${ids.size} poses = ${"%.1f".format(narrowest)}px")
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
                        "ratio=${"%.2f".format(m.previewRatio)} contained=${m.figureContainedAtDefault}"
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
        val figureContainedAtDefault: Boolean
    ) {
        val sharesOfHeight: Float get() = rigHeightPx / heroHeightPx
        val sharesOfWidth: Float get() = rigWidthPx / heroWidthPx
    }

    /** The hero one pose gets at one layout: the surface, its frame, and the clamp that frame allows. */
    private data class Hero(
        val name: String,
        val builder: PoseBuilder,
        val sides: List<Side>,
        val declaration: CameraDefinition,
        val width: Float,
        val height: Float,
        val frame: CameraFrame,
        val limits: YawLimits
    )

    private fun heroFor(name: String, builder: PoseBuilder, sides: List<Side>, roomDp: Float): Hero {
        val declaration = builder.metadata.camera
        val extent = framing.rigExtent(
            camera = Camera(declaration),
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

        val contained = framing.containedYawLimits(
            camera = Camera(declaration),
            builder = builder,
            sides = sides,
            frame = frame,
            width = CONTENT_WIDTH_PX,
            height = heroHeightPx,
            margin = CONTENT_WIDTH_PX * HeroGeometry.YAW_SAFETY_FRACTION
        )

        return Hero(
            name = name,
            builder = builder,
            sides = sides,
            declaration = declaration,
            width = CONTENT_WIDTH_PX,
            height = heroHeightPx,
            frame = frame,
            limits = HeroGeometry.yawLimits(contained, minOf(-declaration.minYaw, declaration.maxYaw))
        )
    }

    /**
     * The smallest gap between the drawn figure and the hero's four edges over the whole rep at one yaw
     * offset, negative when it leaves the surface. Measured on the figure alone — the ground contact is
     * what the clamp also reserves, and that only makes the clamp shorter.
     */
    private fun edgeMargin(hero: Hero, yawOffset: Float): Float {
        var worst = Float.MAX_VALUE
        val pipeline = SkeletonPipeline(DEF)
        for (side in hero.sides) {
            for (i in 0..MOTION_SAMPLES) {
                val context = PoseContext(
                    progress = i.toFloat() / MOTION_SAMPLES,
                    side = side,
                    definition = DEF
                )
                val pose = pipeline.produceFrame(hero.builder, context).pose
                val camera = cameraFor(hero.declaration, hero.declaration.defaultYaw + yawOffset, hero.frame)
                val box = drawnBox(pose, camera, hero.width, hero.height, ground = false)
                worst = min(worst, min(min(box[0], hero.width - box[1]), min(box[2], hero.height - box[3])))
            }
        }
        return worst
    }

    private fun measure(family: Family, roomDp: Float): Measurement {
        val builder = MotionProbe.build(family.pose)
        val declaration = builder.metadata.camera
        val sides = family.sides()

        val extent = framing.rigExtent(
            camera = Camera(declaration),
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
            // Containment is the frame's own claim, read on the figure: the anchor centres the
            // figure, so the contact shadow may run past an edge onto the floor it belongs to.
            figureContainedAtDefault = run {
                val box = unionBox(builder, sides, declaration, frame, CONTENT_WIDTH_PX, heroHeightPx, ground = false)
                box[0] >= -SLACK && box[1] <= CONTENT_WIDTH_PX + SLACK &&
                    box[2] >= -SLACK && box[3] <= heroHeightPx + SLACK
            }
        )
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
        WIDE_LYING_TILT("PelvicTiltPose", true);

        fun sides(): List<Side> = listOf(Side.RIGHT)
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
        const val SLACK = 0.5f

        val FAMILIES = Family.entries.toList()

        fun degrees(radians: Float): String = "%.2f".format(Math.toDegrees(radians.toDouble()))
    }
}
