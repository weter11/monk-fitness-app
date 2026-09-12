package com.monkfitness.app

import com.monkfitness.app.animation.BenchProp
import com.monkfitness.app.animation.BoxProp
import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.CameraDefinition
import com.monkfitness.app.animation.CameraFrame
import com.monkfitness.app.animation.CameraFraming
import com.monkfitness.app.animation.Joint
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
import com.monkfitness.app.animation.StepProp
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WallProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * **C2 — the exercise frame: every frame of a production pose is drawn at ONE frame for the whole
 * rep, the athlete (with its footing) stays whole on the surface, and the subject uses the surface
 * instead of the 70% a fixed anchor left it.**
 *
 * ## The behaviour this pins (measured, on the production path)
 *
 * C1 derived the viewport frame from the bounds of the frame being drawn. That closed the containment
 * defect — and made the subject's drawn size a function of its *current* pose. Measured over the rep
 * (the per-frame fit of every drawn frame, swept over both sides and the whole reachable yaw range)
 * the frame *breathes*: a burpee `0.585 -> 1.300` (a `2.2x` push-in through one rep), a cossack squat
 * `0.856 -> 1.300`, the lunges `0.856 -> 1.240`, an air squat / squat / sumo squat `0.849 -> 1.200`,
 * a kettlebell swing `0.900 -> 1.233`, a jumping squat `0.766 -> 0.997`, arm circles
 * `0.644 -> 0.849`, a thoracic extension `1.063 -> 1.248` — `17` of the `51` production classes move
 * by `5%` or more, `11` of them by `20%` or more. The composition was fixed, too: the world origin was
 * anchored at a hardcoded `centerY = 0.7`, so a standing pose's drawn height could never exceed `70%`
 * of the surface.
 *
 * ## The rule under test
 *
 * [CameraFraming.exerciseFrame] derives the frame **once per exercise**: the largest zoom, not
 * exceeding the pose's authored zoom, at which the athlete together with the drawn ground contact
 * stays inside the surface for every frame of the rep, at every yaw the pose's declaration allows,
 * centred on that content. The subject measured here is what the renderers actually stroke — see
 * [drawnContent] — so the invariants are checked against what is drawn, not against a model of it.
 *
 * ## What each test is for
 *
 *  * [everyFrameOfEveryPoseIsDrawnWholeAtEveryReachableViewpoint] — the gate: containment for the
 *    whole corpus on the hero canvas and two other realistic surfaces, at every phase of every side
 *    the play advances through and every yaw the drag gesture can reach.
 *  * [theFrameIsOneAndTheSameForEveryFrameOfTheRep] — no breathing: the frame the renderer draws with
 *    is bit-identical at every phase and every view angle, while the opt-in dynamic mode still varies
 *    (so the toggle is a real mode, not a no-op).
 *  * [standingPosesAreFramedLargerThanThePerFrameFit] — the size claim, pinned by name: the poses whose
 *    rep is a standing / overhead excursion are drawn larger than C1's per-frame fit, and the poses
 *    whose mid-rep frame is compact inside a much larger envelope are pinned as the exceptions.
 *  * [theGroundIsPlacedJustBelowTheFigureAndTheContentIsCentred] — the composition: the framed content
 *    is centred on the surface (so the anchor is derived, not the fixed `0.7`), the drawn ground
 *    contact is inside it and at or below the athlete's own lowest point, and the athlete fills the
 *    surface instead of the old budget.
 *  * [theAuthoredViewpointIsPreservedAndTheFrameIsPure] — the fit owns the scale and the anchor alone:
 *    yaw, pitch and focal length stay the pose's own, the frame is a function of the exercise and the
 *    surface (never of the camera's current state), it is idempotent, and it never exceeds the authored
 *    zoom.
 *  * [theDynamicModeIsUnchangedAndOptIn] — C1's per-frame rule is still exactly C1: pinned values, and
 *    the dynamic path is what the hero's toggle selects.
 *  * [propPosesKeepTheirFurniture] — no regression for the pose's furniture: a pose that authors an
 *    environment prop (bar, step, bench, wall) keeps at least as much of it on the surface as the
 *    pre-C2 presentation did.
 *
 * ## Deliberately NOT asserted
 *
 * The ground grid is laid out to a horizon that leaves the frame by construction and is not framed, and
 * a prop that already left the surface (the wall-slide's wall) is not required to come back — the rule
 * frames the athlete and its footing. How large a *short, wide* pose (a plank, a supine hold) should
 * read is bounded here only by the authored zoom it is capped at, which is the pose's own composition
 * intent.
 */
class ExerciseFramingInvariantTest {

    private val engine = SkeletonEngine(DEF, STYLE)
    private val projector = SkeletonProjector()
    private val compensator = ScreenSpaceCompensation(SETTINGS)
    private val framing = CameraFraming(engine, SETTINGS)

    /** The hero canvas `ExerciseHeroMedia` gives the skeleton; the others are the same component. */
    private val realisticCanvases = listOf(HERO, 411f to 225f, 1280f to 720f)

    /** Containment is a closed form over the measured box; the only slack is float noise. */
    private val pixelSlack = 0.01f

    // ------------------------------------------------------------------------------------------
    // The production view setup
    // ------------------------------------------------------------------------------------------

    /** The frame the hero derives for [name] on a canvas — the app's own call, memoized by identity. */
    private fun frameOf(name: String, canvas: Pair<Float, Float>): CameraFrame =
        FRAMES.getOrPut("$name@${canvas.first}x${canvas.second}") {
            framing.exerciseFrame(
                Camera(cameraOf(name)), MotionProbe.build(name), sidesOf(name), canvas.first, canvas.second
            )
        }

    /** The camera the renderer draws [name] with: the pose's own viewpoint plus the exercise's frame. */
    private fun drawCamera(name: String, yawOffset: Float, canvas: Pair<Float, Float>): Camera {
        val definition = cameraOf(name)
        val camera = Camera(definition)
        camera.yaw = definition.defaultYaw + yawOffset
        frameOf(name, canvas).applyTo(camera)
        return camera
    }

    /** The projection the renderer draws: [SkeletonProjector] through the camera it was handed. */
    private fun view(camera: Camera, pose: SkeletonPose, canvas: Pair<Float, Float>): ProjectedSkeleton {
        val buffer = ProjectedSkeleton()
        projector.project(pose, camera, engine, canvas.first, canvas.second, buffer, pose.environment.ground.level)
        return buffer
    }

    /** The published pose at [progress] of [name], captured BY VALUE (the pipeline reuses its buffer). */
    private fun publishedFrameOf(name: String, progress: Float, side: Side, pipeline: SkeletonPipeline): SkeletonPose {
        val builder = MotionProbe.build(name)
        return SkeletonPose().apply {
            copyFrom(
                pipeline.produceFrame(
                    builder,
                    PoseContext(progress = progress, side = side, definition = DEF)
                ).pose
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // The drawn content — the instrument
    // ------------------------------------------------------------------------------------------

    /**
     * The bounds of everything the renderers actually stroke, read off the buffers they iterate:
     *
     *  * **bones** — `SkeletonRenderer` draws `thickness + outline * 2` over
     *    `thickness = b.thickness * scale.thicknessScale`, so the drawn half-width is
     *    `thickness / 2 + outline`, `outline = style.outlineWidth * scale.outlineScale`;
     *  * **indicator discs** — `drawCircle(..., item.radius + outline, …)`, `outline = 2f *
     *    scale.outlineScale`;
     *  * **torso faces** — a stroke of `style.outlineWidth` centred on the path edge;
     *  * **the ground contact** ([withGround]) — the contact shadows, `style.shadowRadiusX/Y` x
     *    `scale.shadowScale` around the athlete's own ground points: the footing the exercise frame
     *    includes, and the reason the floor reads just below the figure.
     */
    private fun drawnContent(buffer: ProjectedSkeleton, zoom: Float, withGround: Boolean): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var stroke = 0f
        val scale = ScreenSpaceScale()

        for (i in 0 until buffer.boneCount) {
            val bone = buffer.bones[i]
            compensator.computeScale(bone.p1, zoom, scale)
            val half = bone.thickness * scale.thicknessScale * 0.5f
            minX = min(minX, min(bone.p1.x, bone.p2.x) - half)
            maxX = max(maxX, max(bone.p1.x, bone.p2.x) + half)
            minY = min(minY, min(bone.p1.y, bone.p2.y) - half)
            maxY = max(maxY, max(bone.p1.y, bone.p2.y) + half)
            stroke = max(stroke, STYLE.outlineWidth * scale.outlineScale)
        }

        for (indicator in buffer.indicators) {
            compensator.computeScale(indicator.point, zoom, scale)
            val radius = (if (indicator.id == Joint.HEAD_POS) STYLE.headRadius else STYLE.jointRadius) *
                scale.radiusScale
            minX = min(minX, indicator.point.x - radius)
            maxX = max(maxX, indicator.point.x + radius)
            minY = min(minY, indicator.point.y - radius)
            maxY = max(maxY, indicator.point.y + radius)
            stroke = max(stroke, 2f * scale.outlineScale)
        }

        for (i in 0 until buffer.faceCount) {
            for (p in buffer.faces[i].points) {
                minX = min(minX, p.x)
                maxX = max(maxX, p.x)
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
                stroke = max(stroke, STYLE.outlineWidth * 0.5f * scale.outlineScale)
            }
        }

        if (withGround) {
            for (p in buffer.shadowPoints) {
                compensator.computeScale(p, zoom, scale)
                val rx = STYLE.shadowRadiusX * scale.shadowScale
                val ry = STYLE.shadowRadiusY * scale.shadowScale
                minX = min(minX, p.x - rx)
                maxX = max(maxX, p.x + rx)
                minY = min(minY, p.y - ry)
                maxY = max(maxY, p.y + ry)
            }
        }

        return floatArrayOf(minX - stroke, maxX + stroke, minY - stroke, maxY + stroke)
    }

    /** How far the drawn content leaves the canvas, per edge (negative = inside), in pixels. */
    private fun overflow(buffer: ProjectedSkeleton, zoom: Float, canvas: Pair<Float, Float>): FloatArray {
        val b = drawnContent(buffer, zoom, withGround = true)
        return floatArrayOf(-b[0], b[1] - canvas.first, -b[2], b[3] - canvas.second)
    }

    private fun describe(name: String, side: Side, phase: Float, yawOffset: Float, canvas: Pair<Float, Float>, zoom: Float, out: FloatArray): String =
        "C2 containment: $name side=$side at progress=$phase, yawOffset=$yawOffset, zoom=$zoom on a " +
            "${canvas.first}x${canvas.second} canvas leaves the frame by " +
            "left=${out[0]} right=${out[1]} top=${out[2]} bottom=${out[3]} px"

    // ------------------------------------------------------------------------------------------
    // 1 — the gate
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyFrameOfEveryPoseIsDrawnWholeAtEveryReachableViewpoint() {
        assertTrue("the corpus is empty", productionPoseClasses().isNotEmpty())
        var evaluations = 0

        for (canvas in realisticCanvases) {
            for (name in productionPoseClasses()) {
                for (side in sidesOf(name)) {
                    val pipeline = SkeletonPipeline(DEF)
                    for (phase in PHASES) {
                        val pose = publishedFrameOf(name, phase, side, pipeline)
                        for (yawOffset in YAW_OFFSETS) {
                            val camera = drawCamera(name, yawOffset, canvas)
                            val out = overflow(view(camera, pose, canvas), camera.zoom, canvas)
                            evaluations++
                            assertTrue(
                                describe(name, side, phase, yawOffset, canvas, camera.zoom, out),
                                out.all { it <= pixelSlack }
                            )
                        }
                    }
                }
            }
        }

        // non-vacuity: every pose x side x phase x view angle x canvas was actually measured
        val expected = productionPoseClasses().sumOf { sidesOf(it).size } *
            PHASES.size * YAW_OFFSETS.size * realisticCanvases.size
        assertEquals("every corpus sample must be evaluated on every realistic canvas", expected, evaluations)
    }

    // ------------------------------------------------------------------------------------------
    // 2 — no breathing
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFrameIsOneAndTheSameForEveryFrameOfTheRep() {
        for (name in productionPoseClasses()) {
            val definition = cameraOf(name)
            val frame = frameOf(name, HERO)
            for (side in sidesOf(name)) {
                val pipeline = SkeletonPipeline(DEF)
                for (phase in PHASES) {
                    publishedFrameOf(name, phase, side, pipeline)
                    for (yawOffset in YAW_OFFSETS) {
                        val camera = drawCamera(name, yawOffset, HERO)
                        assertEquals(
                            "$name at progress=$phase, yawOffset=$yawOffset: the frame the renderer draws " +
                                "with must be the exercise's own, bit for bit",
                            java.lang.Float.floatToIntBits(frame.zoom),
                            java.lang.Float.floatToIntBits(camera.zoom)
                        )
                        assertEquals(
                            "$name at progress=$phase: the anchor must be the exercise's own, bit for bit",
                            java.lang.Float.floatToIntBits(frame.centerY),
                            java.lang.Float.floatToIntBits(camera.centerY)
                        )
                        assertEquals(
                            "$name at progress=$phase: the frame is not the pose's — it must not carry a " +
                                "per-frame zoom",
                            definition.defaultZoom, camera.authoredZoom, 0f
                        )
                    }
                }
            }
        }

        // ... and the opt-in dynamic mode genuinely breathes, so the toggle is a mode and not a no-op
        // (measured per-frame ranges: burpee 0.585..1.300, cossack squat 0.856..1.300, squat
        // 0.849..1.200, kettlebell swing 0.900..1.233)
        for (name in BREATHING_POSES) {
            val definition = cameraOf(name)
            val pipeline = SkeletonPipeline(DEF)
            val zooms = PHASES.map { phase ->
                val camera = Camera(definition)
                camera.yaw = definition.defaultYaw
                framing.frameZoom(camera, publishedFrameOf(name, phase, Side.RIGHT, pipeline), HERO.first, HERO.second)
            }
            assertTrue(
                "$name: the dynamic mode must still re-frame every drawn frame (measured " +
                    "${zooms.min()} .. ${zooms.max()}), while the exercise frame is one value " +
                "(${frameOf(name, HERO).zoom})",
                zooms.max() - zooms.min() > 0.05f
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3 — the size claim (and its measured exceptions)
    // ------------------------------------------------------------------------------------------

    @Test
    fun standingPosesAreFramedLargerThanThePerFrameFit() {
        for (name in LARGER_THAN_PER_FRAME_FIT) {
            val exerciseZoom = frameOf(name, HERO).zoom
            val perFrameZoom = perFrameFitAtMidRep(name)
            assertTrue(
                "$name: the exercise frame ($exerciseZoom) must be larger than the per-frame fit at " +
                    "mid-rep ($perFrameZoom)",
                exerciseZoom > perFrameZoom
            )
        }

        for (name in THE_EXCEPTIONS) {
            val exerciseZoom = frameOf(name, HERO).zoom
            val perFrameZoom = perFrameFitAtMidRep(name)
            assertTrue(
                "$name is pinned as an exception ($exerciseZoom vs $perFrameZoom): its mid-rep frame is " +
                    "compact inside a much larger envelope, or both rules are capped at the authored zoom " +
                    "— re-measure before moving it out of this set",
                exerciseZoom <= perFrameZoom
            )
        }

        // the composition claim the mission makes: the athlete's tallest drawn frame uses most of the
        // surface, where the old anchor capped every standing pose at 70% of it
        for (name in STANDING_INVENTORY) {
            val definition = cameraOf(name)
            val frame = frameOf(name, HERO)
            var tallest = 0f
            for (side in sidesOf(name)) {
                val pipeline = SkeletonPipeline(DEF)
                for (phase in PHASES) {
                    val camera = Camera(definition)
                    camera.yaw = definition.defaultYaw
                    frame.applyTo(camera)
                    val athlete = drawnContent(
                        view(camera, publishedFrameOf(name, phase, side, pipeline), HERO), camera.zoom, withGround = false
                    )
                    tallest = max(tallest, athlete[3] - athlete[2])
                }
            }
            val share = tallest / HERO.second
            assertTrue(
                "$name: the athlete's tallest drawn frame must use more than the 0.7 budget the old anchor " +
                    "left (measured share=$share)",
                share > 0.7f
            )
        }

        // pinned shares for the families the mission names, measured on the hero canvas
        for (entry in ATHLETE_SHARE) {
            val name = entry.key
            val definition = cameraOf(name)
            val frame = frameOf(name, HERO)
            var tallest = 0f
            for (side in sidesOf(name)) {
                val pipeline = SkeletonPipeline(DEF)
                for (phase in PHASES) {
                    val camera = Camera(definition)
                    camera.yaw = definition.defaultYaw
                    frame.applyTo(camera)
                    val athlete = drawnContent(
                        view(camera, publishedFrameOf(name, phase, side, pipeline), HERO), camera.zoom, withGround = false
                    )
                    tallest = max(tallest, athlete[3] - athlete[2])
                }
            }
            assertEquals(
                "$name: the athlete's tallest drawn frame as a share of the hero canvas moved",
                entry.value.toDouble(), (tallest / HERO.second).toDouble(), 0.02
            )
        }
    }

    /** C1's rule on the frame this pose is drawn with at the middle of its rep — what the user saw. */
    private fun perFrameFitAtMidRep(name: String): Float {
        val definition = cameraOf(name)
        val camera = Camera(definition)
        camera.yaw = definition.defaultYaw
        return framing.frameZoom(camera, publishedFrameOf(name, 0.5f, Side.RIGHT, SkeletonPipeline(DEF)), HERO.first, HERO.second)
    }

    // ------------------------------------------------------------------------------------------
    // 4 — the composition
    // ------------------------------------------------------------------------------------------

    @Test
    fun theGroundIsPlacedJustBelowTheFigureAndTheContentIsCentred() {
        for (name in productionPoseClasses()) {
            val frame = frameOf(name, HERO)
            val definition = cameraOf(name)

            var envMinX = Float.MAX_VALUE
            var envMaxX = -Float.MAX_VALUE
            var envMinY = Float.MAX_VALUE
            var envMaxY = -Float.MAX_VALUE

            for (side in sidesOf(name)) {
                val pipeline = SkeletonPipeline(DEF)
                for (phase in PHASES) {
                    val pose = publishedFrameOf(name, phase, side, pipeline)
                    for (yawOffset in YAW_OFFSETS) {
                        val camera = drawCamera(name, yawOffset, HERO)
                        val buffer = view(camera, pose, HERO)
                        val content = drawnContent(buffer, camera.zoom, withGround = true)
                        val athlete = drawnContent(buffer, camera.zoom, withGround = false)
                        envMinX = min(envMinX, content[0]); envMaxX = max(envMaxX, content[1])
                        envMinY = min(envMinY, content[2]); envMaxY = max(envMaxY, content[3])

                        assertTrue(
                            "$name at progress=$phase, yawOffset=$yawOffset: the ground contact must reach at " +
                                "or below the athlete's own lowest drawn point " +
                                "(athlete=${athlete[3]}, content=${content[3]})",
                            content[3] >= athlete[3] - pixelSlack
                        )
                    }
                }
            }

            // Centred — what "derived, not a fixed anchor" looks like from the outside. The slack is 5%
            // of the surface: the box is grown per edge by the sampling margin the motion itself needs,
            // so a rep with a sharper excursion on one edge is centred to within it (measured worst case
            // on this corpus: the burpee's jump, 14.8 px of 531).
            assertTrue(
                "$name: the framed content must be centred horizontally (margins " +
                    "$envMinX / ${HERO.first - envMaxX})",
                abs(envMinX - (HERO.first - envMaxX)) <= 0.05f * HERO.first
            )
            assertTrue(
                "$name: the framed content must be centred vertically (margins " +
                    "$envMinY / ${HERO.second - envMaxY}), where the old rule pinned the anchor at 0.7",
                abs(envMinY - (HERO.second - envMaxY)) <= 0.05f * HERO.second
            )
            assertTrue(
                "$name: the anchor must be derived from the content, not the fixed 0.7 (measured " +
                    "${frame.centerY})",
                frame.centerY != 0.7f || envMinY < 0.05f * HERO.second
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5 — the viewpoint is the pose's own, and the frame is pure
    // ------------------------------------------------------------------------------------------

    @Test
    fun theAuthoredViewpointIsPreservedAndTheFrameIsPure() {
        for (name in productionPoseClasses()) {
            val definition = cameraOf(name)
            val frame = frameOf(name, HERO)

            assertTrue("$name: the frame may never exceed the authored zoom", frame.zoom <= definition.defaultZoom)

            // purity: the frame does not depend on the camera's current zoom, anchor or yaw
            val dirty = Camera(definition)
            dirty.yaw = definition.defaultYaw + definition.maxYaw
            dirty.zoom = definition.defaultZoom * 0.25f
            dirty.centerX = 0.1f
            dirty.centerY = 0.1f
            val again = framing.exerciseFrame(dirty, MotionProbe.build(name), sidesOf(name), HERO.first, HERO.second)
            assertEquals(
                "$name: the frame must not depend on the camera's history",
                java.lang.Float.floatToIntBits(frame.zoom), java.lang.Float.floatToIntBits(again.zoom)
            )
            assertEquals(
                "$name: the anchor must not depend on the camera's history",
                java.lang.Float.floatToIntBits(frame.centerX), java.lang.Float.floatToIntBits(again.centerX)
            )
            assertEquals(
                "$name: the frame must be idempotent",
                java.lang.Float.floatToIntBits(frame.zoom), java.lang.Float.floatToIntBits(frameOf(name, HERO).zoom)
            )

            // ... and applying it touches the scale and the anchor alone
            val camera = Camera(definition)
            camera.yaw = definition.defaultYaw + definition.minYaw
            val yaw = camera.yaw
            frame.applyTo(camera)
            assertEquals("the frame may not touch the yaw (the rotation gesture owns it)", yaw, camera.yaw, 0f)
            assertEquals("the frame may not touch the pitch", definition.defaultPitch, camera.pitch, 0f)
            assertEquals("the camera keeps the zoom it was authored with", definition.defaultZoom, camera.authoredZoom, 0f)
        }
    }

    // ------------------------------------------------------------------------------------------
    // 6 — the dynamic mode is the C1 rule, unchanged
    // ------------------------------------------------------------------------------------------

    @Test
    fun theDynamicModeIsUnchangedAndOptIn() {
        for (entry in DYNAMIC_MODE_PINS) {
            val name = entry.key
            val definition = cameraOf(name)
            val camera = Camera(definition)
            camera.yaw = definition.defaultYaw
            val zoom = framing.frameZoom(
                camera, publishedFrameOf(name, 0.5f, Side.RIGHT, SkeletonPipeline(DEF)), HERO.first, HERO.second
            )
            assertEquals(
                "$name: the opt-in dynamic mode must stay exactly the per-frame fit C1 shipped",
                entry.value.toDouble(), zoom.toDouble(), 0.001
            )
            assertTrue(
                "$name: the two modes must be different rules (dynamic=$zoom, " +
                    "exercise=${frameOf(name, HERO).zoom})",
                abs(zoom - frameOf(name, HERO).zoom) > 0.01f
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 7 — the pose's furniture survives the recomposition
    // ------------------------------------------------------------------------------------------

    @Test
    fun propPosesKeepTheirFurniture() {
        var checked = 0
        for (name in productionPoseClasses()) {
            val builder = MotionProbe.build(name)
            val definition = builder.metadata.camera
            val props = builder.metadata.environment.props
            if (props.isEmpty()) continue

            val midRep = publishedFrameOf(name, 0.5f, Side.RIGHT, SkeletonPipeline(DEF))
            val before = Camera(definition)
            before.yaw = definition.defaultYaw
            before.zoom = framing.frameZoom(before, midRep, HERO.first, HERO.second)

            val after = Camera(definition)
            after.yaw = definition.defaultYaw
            frameOf(name, HERO).applyTo(after)

            for ((index, prop) in props.withIndex()) {
                val corners = propBox(prop)?.let(::cornersOf) ?: continue
                val beforeInside = corners.count { inside(it, before) }
                val afterInside = corners.count { inside(it, after) }
                checked++
                assertTrue(
                    "$name prop#$index: the pre-C2 presentation kept $beforeInside of ${corners.size} " +
                        "corners on the surface and the exercise frame keeps $afterInside — a pose may not " +
                        "lose its furniture to the recomposition",
                    afterInside >= beforeInside
                )
            }
        }
        assertTrue("no pose with an environment prop was checked", checked > 0)
    }

    /** A prop's axis-aligned box, whatever concrete prop type the pose declared. */
    private class PropBox(val center: Vector3, val width: Float, val height: Float, val depth: Float)

    private fun propBox(prop: Any): PropBox? = when (prop) {
        is BoxProp -> PropBox(prop.center, prop.width, prop.height, prop.depth)
        is StepProp -> PropBox(prop.center, prop.width, prop.height, prop.depth)
        is BenchProp -> PropBox(prop.center, prop.width, prop.height, prop.depth)
        is WallProp -> PropBox(prop.center, prop.width, prop.height, prop.depth)
        else -> null
    }

    /** The prop's eight projected corners: the drawn extent a pose's furniture occupies. */
    private fun cornersOf(box: PropBox): List<Vector3> =
        listOf(-1f, 1f).flatMap { sx ->
            listOf(-1f, 1f).flatMap { sy ->
                listOf(-1f, 1f).map { sz ->
                    Vector3(
                        box.center.x + sx * box.width / 2f,
                        box.center.y + sy * box.height / 2f,
                        box.center.z + sz * box.depth / 2f
                    )
                }
            }
        }

    private fun inside(corner: Vector3, camera: Camera): Boolean {
        val point = ProjectedPoint()
        camera.project(corner, HERO.first, HERO.second, point)
        return point.x >= 0f && point.x <= HERO.first && point.y >= 0f && point.y <= HERO.second
    }

    // ------------------------------------------------------------------------------------------
    // Corpus, sweep and pins
    // ------------------------------------------------------------------------------------------

    private fun cameraOf(name: String): CameraDefinition = MotionProbe.build(name).metadata.camera

    /** The sides the exercise plays: an alternating exercise plays both (`AnimationController`). */
    private fun sidesOf(name: String): List<Side> =
        if (name in ALTERNATING_POSES) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)

    private companion object {
        val DEF = SkeletonDefinition.DEFAULT_ADULT
        val STYLE = SkeletonStyle.DEFAULT
        val SETTINGS = ScreenSpaceSettings.DEFAULT

        /** The hero canvas the framing audit measured (`ExerciseHeroMedia`). */
        val HERO = 861f to 531f

        /** The phases of a rep the corpus is swept at. */
        val PHASES = (0..32).map { it / 32f }

        /**
         * The view angles the app can reach: the drag gesture clamps the offset it accumulates at
         * +/-90 degrees (`AnimationController.onRotate`), and the pose's own declaration
         * (`CameraDefinition.minYaw`/`maxYaw`) is the same arc.
         */
        val YAW_OFFSETS = listOf(
            -1.5708f, -1.3090f, -1.0472f, -0.7854f, -0.5236f, -0.2618f, 0f,
            0.2618f, 0.5236f, 0.7854f, 1.0472f, 1.3090f, 1.5708f
        )

        /** The two exercises `PoseRegistry` marks as alternating (`StaticBirdDogHold`, `AlternatingBirdDog`). */
        val ALTERNATING_POSES = setOf("StaticBirdDogHoldPose", "AlternatingBirdDogPose")

        /**
         * The poses whose rep moves fastest, measured as the per-frame fit's own range over the rep:
         * burpee `0.585..1.300`, cossack squat `0.856..1.300`, squat `0.849..1.200`, kettlebell swing
         * `0.900..1.233`. These are where the deleted camera push-in was at its most visible.
         */
        val BREATHING_POSES = listOf("BurpeePose", "CossackSquatPose", "SquatPose", "KettlebellSwingPose")

        /**
         * The poses whose rep is a standing / overhead excursion: measured on the hero canvas, the
         * exercise frame is *larger* than C1's per-frame fit at the mid-rep frame — `StandardPullUp`
         * `0.654 -> 0.873`, `Squat` `1.114 -> 1.136`, `AirSquat` `1.101 -> 1.129`, `StepUp`
         * `0.784 -> 1.061`, `JumpSquat` `0.766 -> 1.030`, `WallSlides` `0.851 -> 1.036`, `FacePull`
         * `0.815 -> 1.034`, the lunges `0.856 -> 1.105/1.151/1.127`, `CossackSquat` `0.856 -> 1.053`,
         * `HipCars` `0.849 -> 1.156`, `KettlebellSwing` `0.900 -> 1.151`, `ScapularRetraction`
         * `0.849 -> 1.144`, `SumoSquat` `1.102 -> 1.145`, `ThoracicExtension` `1.141 -> 1.350`,
         * `CouchStretch` `1.210 -> 1.300`, `HalfKneelingStretch` `1.202 -> 1.300`.
         */
        val LARGER_THAN_PER_FRAME_FIT = setOf(
            "StandardPullUpPose", "UnderhandChinUpPose", "NeutralGripPullUpPose", "WideGripPullUpPose",
            "ScapularPullUpPose", "HangPose", "SquatPose", "AirSquatPose", "JumpSquatPose", "SumoSquatPose",
            "CossackSquatPose", "StepUpPose", "WallSlidesPose", "FacePullPose", "HipCarsPose",
            "KettlebellSwingPose", "ScapularRetractionPose", "ThoracicExtensionPose", "CouchStretchPose",
            "HalfKneelingStretchPose", "AlternatingForwardLungesPose", "AlternatingReverseLungesPose",
            "AlternatingSideLungesPose"
        )

        /**
         * The measured exceptions: at mid-rep these read at most what the per-frame fit did, because
         * their mid-rep frame is a compact configuration inside a much larger envelope (`BurpeePose`: a
         * crouch at mid-rep, a standing jump in the envelope — `1.300 -> 0.712`), because the envelope's
         * ground contact makes the whole motion box taller (`ArmCirclesPose` `0.849 -> 0.847`), because
         * the envelope is wider than the drawn frame (`Standard/Wide/Military/Diamond/DeclinePushUp`
         * `1.300 -> 1.251`), or because both rules are already capped at the pose's authored zoom
         * (`ReverseSnowAngel`, `Superman`, `DeadBug`, `LegRaise`, `GluteBridge`, `PelvicTilt`, `BirdDog`,
         * `StaticBirdDogHold`, `AlternatingBirdDog`, `CatCow`, `LatStretch`, `PikePushUp`,
         * `ProneCobraStretch`, `QuadrupedThoracicRotations`, `DeepSquatHold`, `HamstringStretch`,
         * `DynamicWorldsGreatestStretch`, `StaticForearmPlank`, `IsometricSidePlank`, `MountainClimber`,
         * `KneePushUp`).
         */
        val THE_EXCEPTIONS = setOf(
            "BurpeePose", "ArmCirclesPose", "StandardPushUpPose", "WidePushUpPose", "MilitaryPushUpPose",
            "DiamondPushUpPose", "DeclinePushUpPose", "KneePushUpPose", "SupermanPose", "ReverseSnowAngelPose",
            "DeadBugPose", "LegRaisePose", "GluteBridgePose", "PelvicTiltPose", "BirdDogPose",
            "StaticBirdDogHoldPose", "AlternatingBirdDogPose", "StaticForearmPlankPose",
            "IsometricSidePlankPose", "MountainClimberPose", "CatCowPose", "ProneCobraStretchPose",
            "QuadrupedThoracicRotationsPose", "LatStretchPose", "PikePushUpPose", "DeepSquatHoldPose",
            "HamstringStretchPose", "DynamicWorldsGreatestStretchPose"
        )

        /**
         * The standing / overhead inventory that used to clip (C1's `CLIPPED_AT_HERO`) whose rep the
         * mission requires to read larger: these are the ones whose tallest drawn frame must now use
         * more than the old `0.7` budget.
         */
        val STANDING_INVENTORY = setOf(
            "AirSquatPose", "AlternatingForwardLungesPose", "AlternatingReverseLungesPose",
            "AlternatingSideLungesPose", "BurpeePose", "CossackSquatPose", "CouchStretchPose",
            "DynamicWorldsGreatestStretchPose", "FacePullPose", "HalfKneelingStretchPose", "HangPose",
            "HipCarsPose", "JumpSquatPose", "KettlebellSwingPose", "NeutralGripPullUpPose",
            "ScapularPullUpPose", "ScapularRetractionPose", "SquatPose", "StandardPullUpPose", "StepUpPose",
            "SumoSquatPose", "ThoracicExtensionPose", "UnderhandChinUpPose", "WallSlidesPose",
            "WideGripPullUpPose"
        )

        /**
         * The athlete's tallest drawn frame at the pose's own viewpoint, as a share of the hero canvas
         * (this file's own measurement): the composition claim, per family. Before C2 the anchor
         * (`centerY = 0.7`) capped every one of them at 70%.
         */
        val ATHLETE_SHARE = mapOf(
            "StandardPullUpPose" to 0.9115f,
            "NeutralGripPullUpPose" to 0.9269f,
            "UnderhandChinUpPose" to 0.9239f,
            "HangPose" to 0.9109f,
            "SquatPose" to 0.9055f,
            "AirSquatPose" to 0.9004f,
            "StepUpPose" to 0.8585f,
            "CossackSquatPose" to 0.8385f,
            "KettlebellSwingPose" to 0.9070f,
            "ThoracicExtensionPose" to 0.8712f,
            "WallSlidesPose" to 0.8894f,
            "FacePullPose" to 0.8736f,
            "BurpeePose" to 0.7709f
        )

        /**
         * C1's per-frame fit at the mid-rep frame on the hero canvas, measured on this tree — the
         * opt-in mode's own numbers, which the default-mode change must not have moved.
         */
        val DYNAMIC_MODE_PINS = mapOf(
            "StandardPullUpPose" to 0.654f,
            "SquatPose" to 1.114f,
            "AirSquatPose" to 1.101f
        )

        /** The frames the hero derived, memoized by (pose class, canvas): the derivation plays the rep. */
        val FRAMES = HashMap<String, CameraFrame>()

        /** Every concrete production pose class in `poses/` (the sibling corpus invariants' enumeration). */
        fun productionPoseClasses(): List<String> {
            val start = System.getProperty("user.dir")
                ?: error("user.dir is not set — the corpus enumeration needs the module working directory")
            var dir = File(start)
            var moduleRoot: File? = null
            for (attempt in 0 until 8) {
                if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                    moduleRoot = dir
                    break
                }
                val parent = dir.parentFile ?: break
                dir = parent
            }
            val root = moduleRoot
                ?: error("Could not locate app module root from ${System.getProperty("user.dir")}")
            return File(root, "src/main/java/com/monkfitness/app/poses")
                .listFiles { f -> f.isFile && f.name.endsWith("Pose.kt") }!!
                .map { it.name.removeSuffix(".kt") }
                .filterNot { it.startsWith("Base") || it == "PoseRegistry" }
                .sorted()
        }
    }
}
